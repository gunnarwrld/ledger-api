package dev.ledgerforge.ledger.service

import dev.ledgerforge.ledger.api.CreateAccountRequest
import dev.ledgerforge.ledger.api.TransferRequest
import dev.ledgerforge.ledger.domain.AccountKind
import dev.ledgerforge.ledger.domain.InsufficientFundsException
import dev.ledgerforge.ledger.persistence.AccountRepository
import dev.ledgerforge.ledger.persistence.PostingRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerIntegrationTest @Autowired constructor(
    private val accountService: AccountService,
    private val transferService: TransferService,
    private val accounts: AccountRepository,
    private val postings: PostingRepository,
    private val jdbc: JdbcTemplate,
    private val mvc: MockMvc,
) {
    @BeforeEach
    fun clearLedger() {
        jdbc.execute("delete from idempotency_records")
        jdbc.execute("delete from postings")
        jdbc.execute("delete from journal_entries")
        jdbc.execute("delete from accounts")
    }

    @Test
    fun `replaying a transfer returns the same result and posts once`() {
        val source = accountService.create(CreateAccountRequest("Source", "SEK", 5_000))
        val destination = accountService.create(CreateAccountRequest("Destination", "SEK", 0))
        val request = TransferRequest(source.id, destination.id, 1_250, "SEK")

        val first = transferService.transfer("replay-key", request)
        val replay = transferService.transfer("replay-key", request)

        assertEquals(first, replay)
        assertEquals(3_750, accountService.balance(source.id).balanceMinor)
        assertEquals(1_250, accountService.balance(destination.id).balanceMinor)
        assertEquals(4, postings.countPostings()) // two opening postings and two transfer postings
    }

    @Test
    fun `reusing an idempotency key for a different request conflicts`() {
        val source = accountService.create(CreateAccountRequest("Source", "SEK", 5_000))
        val destination = accountService.create(CreateAccountRequest("Destination", "SEK", 0))
        transferService.transfer("fixed-key", TransferRequest(source.id, destination.id, 100, "SEK"))

        org.junit.jupiter.api.Assertions.assertThrows(dev.ledgerforge.ledger.domain.IdempotencyConflictException::class.java) {
            transferService.transfer("fixed-key", TransferRequest(source.id, destination.id, 200, "SEK"))
        }
        assertEquals(4_900, accountService.balance(source.id).balanceMinor)
        assertEquals(100, accountService.balance(destination.id).balanceMinor)
    }

    @Test
    fun `parallel transfers cannot overdraw and all journal balances reconcile`() {
        val source = accountService.create(CreateAccountRequest("Source", "SEK", 1_000))
        val destination = accountService.create(CreateAccountRequest("Destination", "SEK", 0))
        val count = 24
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        try {
            val tasks = (1..count).map { index ->
                Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        transferService.transfer(
                            "parallel-$index",
                            TransferRequest(source.id, destination.id, 100, "SEK"),
                        )
                        true
                    } catch (_: InsufficientFundsException) {
                        false
                    }
                }
            }
            val futures = tasks.map(executor::submit)
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers should reach the start barrier")
            start.countDown()
            val succeeded = futures.count { it.get(30, TimeUnit.SECONDS) }

            assertEquals(10, succeeded)
            assertEquals(0, accountService.balance(source.id).balanceMinor)
            assertEquals(1_000, accountService.balance(destination.id).balanceMinor)
            val debitTotal = jdbc.queryForObject(
                "select coalesce(sum(amount_minor), 0) from postings where side = 'DEBIT'",
                BigDecimal::class.java,
            )
            val creditTotal = jdbc.queryForObject(
                "select coalesce(sum(amount_minor), 0) from postings where side = 'CREDIT'",
                BigDecimal::class.java,
            )
            assertEquals(debitTotal, creditTotal)
            val allAccounts = accounts.findAll()
            val customerBalances = allAccounts.filter { it.kind == AccountKind.CUSTOMER_WALLET }.sumOf { it.balanceMinor }
            val cashBalances = allAccounts.filter { it.kind == AccountKind.CASH_ASSET }.sumOf { it.balanceMinor }
            assertEquals(customerBalances, cashBalances)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `HTTP account creation defaults an omitted opening balance to zero`() {
        mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New wallet\",\"currencyCode\":\"SEK\"}"),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.balanceMinor").value(0))
            .andExpect(jsonPath("$.currencyCode").value("SEK"))
    }

    @Test
    fun `HTTP transfer replay and balance statement endpoints work`() {
        val source = accountService.create(CreateAccountRequest("Source", "SEK", 1_000))
        val destination = accountService.create(CreateAccountRequest("Destination", "SEK", 0))
        val body = "{\"sourceAccountId\":\"${source.id}\",\"destinationAccountId\":\"${destination.id}\",\"amountMinor\":300,\"currencyCode\":\"SEK\"}"
        val first = mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", "http-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val firstId = Regex("\"transferId\":\"([^\"]+)\"").find(first)!!.groupValues[1]
        val second = mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", "http-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        assertEquals(firstId, Regex("\"transferId\":\"([^\"]+)\"").find(second)!!.groupValues[1])

        mvc.perform(get("/api/v1/accounts/${source.id}/balance"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(700))
            .andExpect(jsonPath("$.currencyCode").value("SEK"))
        mvc.perform(get("/api/v1/accounts/${source.id}/statement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }
}
