package dev.ledgerforge.ledger.service

import com.jayway.jsonpath.JsonPath
import dev.ledgerforge.ledger.support.PostgresTestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=40",
        "spring.datasource.hikari.connection-timeout=10000",
        "server.error.include-message=always",
        "server.error.include-binding-errors=always",
    ],
)
class LedgerIntegrationTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) {
        @LocalServerPort
        private var port: Int = 0

        private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        @BeforeEach
        fun clearLedger() {
            jdbc.execute(
                "TRUNCATE TABLE idempotency_records, postings, journal_entries, accounts RESTART IDENTITY CASCADE",
            )
        }

        @Test
        fun `transfer posts a balanced journal entry and moves wallet balances`() {
            val source = createAccount("Everyday", "SEK", 50_000)
            val destination = createAccount("Savings", "SEK", 0)

            val response =
                postTransfer(
                    key = "savings-transfer-001",
                    sourceId = source,
                    destinationId = destination,
                    amount = 1_250,
                    currency = "SEK",
                )

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(source.toString(), JsonPath.read(response.body, "$.sourceAccountId"))
            assertEquals(1_250, JsonPath.read<Int>(response.body, "$.amountMinor"))
            assertEquals(48_750L, balanceOf(source))
            assertEquals(1_250L, balanceOf(destination))
            assertEquals(1L, countTransferEntries())
            mvc
                .perform(get("/api/v1/accounts/$source/statement"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].signedAmountMinor").value(-1_250))
            assertLedgerInvariants()
        }

        @Test
        fun `replaying the same idempotency key returns the original receipt and posts once`() {
            val source = createAccount("Source", "SEK", 5_000)
            val destination = createAccount("Destination", "SEK", 0)

            val first = postTransfer("replay-key", source, destination, 1_250, "SEK")
            val replay = postTransfer("replay-key", source, destination, 1_250, "SEK")

            assertEquals(HttpStatus.CREATED, first.statusCode)
            assertEquals(HttpStatus.CREATED, replay.statusCode)
            assertEquals(
                JsonPath.read<String>(first.body, "$.transferId"),
                JsonPath.read<String>(replay.body, "$.transferId"),
            )
            assertEquals(
                JsonPath.read<String>(first.body, "$.journalEntryId"),
                JsonPath.read<String>(replay.body, "$.journalEntryId"),
            )
            assertEquals(3_750L, balanceOf(source))
            assertEquals(1_250L, balanceOf(destination))
            assertEquals(1L, countTransferEntries())
            assertEquals(1L, countIdempotencyRecords())
            assertLedgerInvariants()
        }

        @Test
        fun `reusing an idempotency key for a different transfer conflicts and does not post again`() {
            val source = createAccount("Source", "SEK", 5_000)
            val destination = createAccount("Destination", "SEK", 0)
            postTransfer("fixed-key", source, destination, 100, "SEK")

            val conflict = postTransfer("fixed-key", source, destination, 200, "SEK")

            assertProblem(conflict, HttpStatus.CONFLICT)
            assertEquals(4_900L, balanceOf(source))
            assertEquals(100L, balanceOf(destination))
            assertEquals(1L, countTransferEntries())
            assertLedgerInvariants()
        }

        @Test
        fun `a failed transfer does not consume the idempotency key`() {
            val source = createAccount("Source", "SEK", 100)
            val destination = createAccount("Destination", "SEK", 0)
            val donor = createAccount("Donor", "SEK", 1_000)

            val rejected = postTransfer("retry-after-funding", source, destination, 500, "SEK")
            assertProblem(rejected, HttpStatus.UNPROCESSABLE_ENTITY)
            assertEquals(0L, countTransferEntries())

            val funding = postTransfer("fund-source", donor, source, 500, "SEK")
            assertEquals(HttpStatus.CREATED, funding.statusCode)

            val retried = postTransfer("retry-after-funding", source, destination, 500, "SEK")
            assertEquals(HttpStatus.CREATED, retried.statusCode)
            assertEquals(100L, balanceOf(source))
            assertEquals(500L, balanceOf(destination))
            assertEquals(500L, balanceOf(donor))
            assertEquals(2L, countTransferEntries())
            assertLedgerInvariants()
        }

        @Test
        fun `concurrent retries of one idempotency key post the transfer once`() {
            val source = createAccount("Source", "SEK", 5_000)
            val destination = createAccount("Destination", "SEK", 0)
            val attempts = 16

            val responses =
                parallel(attempts) {
                    postLive(
                        "/api/v1/transfers",
                        transferJson(source, destination, 100, "SEK"),
                        "same-key",
                    )
                }

            assertTrue(responses.all { it.statusCode == HttpStatus.CREATED }, responses.joinToString { describe(it) })
            val transferIds = responses.map { JsonPath.read<String>(it.body, "$.transferId") }.toSet()
            assertEquals(setOf(transferIds.first()), transferIds)
            assertEquals(4_900L, balanceOf(source))
            assertEquals(100L, balanceOf(destination))
            assertEquals(1L, countTransferEntries())
            assertEquals(1L, countIdempotencyRecords())
            assertLedgerInvariants()
        }

        @Test
        fun `concurrent transfers cannot overdraw and keep the projection consistent`() {
            val source = createAccount("Source", "SEK", 1_000)
            val destination = createAccount("Destination", "SEK", 0)
            val attempts = 24

            val responses =
                parallel(attempts) { index ->
                    postLive(
                        "/api/v1/transfers",
                        transferJson(source, destination, 100, "SEK"),
                        "parallel-$index",
                    )
                }

            val succeeded = responses.count { it.statusCode.value() == 201 }
            val rejected = responses.count { it.statusCode.value() == 422 }
            val unexpected = responses.filter { it.statusCode.value() !in setOf(201, 422) }
            assertEquals(emptyList<String>(), unexpected.map { describe(it) })
            assertEquals(10, succeeded)
            assertEquals(attempts - 10, rejected)
            assertEquals(0L, balanceOf(source))
            assertEquals(1_000L, balanceOf(destination))
            assertEquals(10L, countTransferEntries())
            assertLedgerInvariants()
        }

        @Test
        fun `opposite concurrent transfers do not lose updates or deadlock`() {
            val left = createAccount("Left", "SEK", 10_000)
            val right = createAccount("Right", "SEK", 10_000)
            val outbound = 20
            val inbound = 20

            val responses =
                parallel(outbound + inbound) { index ->
                    if (index < outbound) {
                        postLive("/api/v1/transfers", transferJson(left, right, 100, "SEK"), "left-to-right-$index")
                    } else {
                        postLive("/api/v1/transfers", transferJson(right, left, 100, "SEK"), "right-to-left-$index")
                    }
                }

            val unexpected = responses.filter { it.statusCode.value() != 201 }
            assertEquals(emptyList<String>(), unexpected.map { describe(it) })
            assertEquals(10_000L, balanceOf(left))
            assertEquals(10_000L, balanceOf(right))
            assertEquals((outbound + inbound).toLong(), countTransferEntries())
            assertLedgerInvariants()
        }

        @Test
        fun `concurrent opening balances keep the cash control account consistent`() {
            val openings = 12
            val responses =
                parallel(openings) { index ->
                    postLive(
                        "/api/v1/accounts",
                        """{"name":"Wallet-$index","currencyCode":"SEK","openingBalanceMinor":100}""",
                    )
                }

            val unexpected = responses.filter { it.statusCode.value() != 201 }
            assertEquals(emptyList<String>(), unexpected.map { describe(it) })
            val customerIds = responses.map { UUID.fromString(JsonPath.read<String>(it.body, "$.id")) }
            assertEquals(openings, customerIds.toSet().size)
            customerIds.forEach { id -> assertEquals(100L, balanceOf(id)) }
            assertEquals(openings * 100L, cashBalance("SEK"))
            assertEquals(openings.toLong(), countEntries("OPENING_BALANCE"))
            assertLedgerInvariants()
        }

        @Test
        fun `account validation failures use problem details`() {
            mvc
                .perform(
                    post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"New wallet","currencyCode":"SEK"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.balanceMinor").value(0))

            mvc
                .perform(
                    post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"","currencyCode":"SEK","openingBalanceMinor":0}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))

            mvc
                .perform(
                    post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Broken","currencyCode":"sek","openingBalanceMinor":-1}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))

            mvc
                .perform(
                    post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Nope","currencyCode":"ZZZ","openingBalanceMinor":0}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))

            mvc
                .perform(
                    post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Nope"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        }

        @Test
        fun `transfer validation and missing accounts use problem details`() {
            val source = createAccount("Source", "SEK", 1_000)
            val destination = createAccount("Destination", "EUR", 1_000)
            val missing = UUID.randomUUID()

            assertProblem(
                postTransfer(null, source, destination, 100, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer(" ", source, destination, 100, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("x".repeat(129), source, destination, 100, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("zero", source, destination, 0, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("negative", source, destination, -5, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("lower", source, destination, 10, "sek"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("same", source, source, 10, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("mismatch", source, destination, 10, "SEK"),
                HttpStatus.BAD_REQUEST,
            )
            assertProblem(
                postTransfer("missing", source, missing, 10, "SEK"),
                HttpStatus.NOT_FOUND,
            )
            assertProblem(
                postTransfer("poor", source, createAccount("Empty", "SEK", 0), 5_000, "SEK"),
                HttpStatus.UNPROCESSABLE_ENTITY,
            )
            mvc
                .perform(get("/api/v1/accounts/$missing/balance"))
                .andExpect(status().isNotFound)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
            mvc
                .perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            mvc
                .perform(
                    post("/api/v1/transfers")
                        .header("Idempotency-Key", "malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sourceAccountId":""}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))

            assertEquals(1_000L, balanceOf(source))
            assertEquals(0L, countTransferEntries())
            assertLedgerInvariants()
        }

        @Test
        fun `postgres rejects journal mutation and unbalanced entries`() {
            val source = createAccount("Source", "SEK", 500)
            createAccount("Destination", "SEK", 0)
            val postingId = jdbc.queryForObject("select id from postings limit 1", UUID::class.java)

            assertThrows(DataAccessException::class.java) {
                jdbc.update("update postings set amount_minor = amount_minor + 1 where id = ?", postingId)
            }
            assertThrows(DataAccessException::class.java) {
                jdbc.update("update accounts set balance_minor = balance_minor + 1 where id = ?", source)
            }
            assertThrows(DataAccessException::class.java) {
                jdbc.update(
                    """
                    insert into journal_entries (id, entry_type, reference_id, created_at)
                    values (?, 'TRANSFER', null, now())
                    """.trimIndent(),
                    UUID.randomUUID(),
                )
            }

            val entryId = UUID.randomUUID()
            val debitId = UUID.randomUUID()
            val creditId = UUID.randomUUID()
            assertThrows(DataAccessException::class.java) {
                jdbc.execute(
                    ConnectionCallback<Unit> { connection: Connection ->
                        val previousAutoCommit = connection.autoCommit
                        connection.autoCommit = false
                        try {
                            connection
                                .prepareStatement(
                                    """
                                    insert into journal_entries
                                        (id, entry_type, reference_id, created_at)
                                    values (?, 'TRANSFER', null, now())
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setObject(1, entryId)
                                    statement.executeUpdate()
                                }
                            connection
                                .prepareStatement(
                                    """
                                    insert into postings
                                        (id, entry_id, account_id, side, amount_minor, currency_code, created_at)
                                    values (?, ?, ?, ?, ?, 'SEK', now())
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setObject(1, debitId)
                                    statement.setObject(2, entryId)
                                    statement.setObject(3, source)
                                    statement.setString(4, "DEBIT")
                                    statement.setLong(5, 40)
                                    statement.executeUpdate()
                                    statement.setObject(1, creditId)
                                    statement.setObject(2, entryId)
                                    statement.setObject(3, source)
                                    statement.setString(4, "CREDIT")
                                    statement.setLong(5, 41)
                                    statement.executeUpdate()
                                }
                            connection.commit()
                        } catch (exception: Exception) {
                            connection.rollback()
                            throw exception
                        } finally {
                            connection.autoCommit = previousAutoCommit
                        }
                    },
                )
            }
            assertEquals(500L, balanceOf(source))
            assertLedgerInvariants()
        }

        private fun createAccount(
            name: String,
            currency: String,
            opening: Long,
        ): UUID {
            val response =
                postJson(
                    "/api/v1/accounts",
                    """{"name":"$name","currencyCode":"$currency","openingBalanceMinor":$opening}""",
                )
            assertEquals(HttpStatus.CREATED, response.statusCode, response.body)
            return UUID.fromString(JsonPath.read(response.body, "$.id"))
        }

        private fun postTransfer(
            key: String?,
            sourceId: UUID,
            destinationId: UUID,
            amount: Long,
            currency: String,
        ): ResponseEntity<String> {
            val body =
                """
                {
                  "sourceAccountId":"$sourceId",
                  "destinationAccountId":"$destinationId",
                  "amountMinor":$amount,
                  "currencyCode":"$currency"
                }
                """.trimIndent()
            return postJson("/api/v1/transfers", body, key)
        }

        private fun transferJson(
            sourceId: UUID,
            destinationId: UUID,
            amount: Long,
            currency: String,
        ): String =
            """
            {
              "sourceAccountId":"$sourceId",
              "destinationAccountId":"$destinationId",
              "amountMinor":$amount,
              "currencyCode":"$currency"
            }
            """.trimIndent()

        private fun postLive(
            path: String,
            json: String,
            idempotencyKey: String? = null,
        ): ResponseEntity<String> {
            val builder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port$path"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey)
            }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val headers = HttpHeaders()
            response.headers().firstValue("content-type").ifPresent { headers.contentType = MediaType.parseMediaType(it) }
            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body())
        }

        private fun postJson(
            path: String,
            json: String,
            idempotencyKey: String? = null,
        ): ResponseEntity<String> {
            val request = post(path).contentType(MediaType.APPLICATION_JSON).content(json)
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey)
            }
            val response = mvc.perform(request).andReturn().response
            val headers = HttpHeaders()
            response.contentType?.let { headers.contentType = MediaType.parseMediaType(it) }
            return ResponseEntity.status(response.status).headers(headers).body(response.contentAsString)
        }

        private fun balanceOf(accountId: UUID): Long =
            jdbc.queryForObject(
                "select balance_minor from accounts where id = ?",
                Long::class.java,
                accountId,
            )!!

        private fun cashBalance(currency: String): Long =
            jdbc.queryForObject(
                "select balance_minor from accounts where system_key = ?",
                Long::class.java,
                "CASH:$currency",
            )!!

        private fun countTransferEntries(): Long = countEntries("TRANSFER")

        private fun countEntries(entryType: String): Long =
            jdbc.queryForObject(
                "select count(*) from journal_entries where entry_type = ?",
                Long::class.java,
                entryType,
            )!!

        private fun countIdempotencyRecords(): Long = jdbc.queryForObject("select count(*) from idempotency_records", Long::class.java)!!

        private fun assertProblem(
            response: ResponseEntity<String>,
            status: HttpStatus,
        ) {
            assertEquals(status.value(), response.statusCode.value(), response.body)
            val contentType = response.headers.contentType
            assertTrue(
                contentType != null && contentType.isCompatibleWith(MediaType.parseMediaType("application/problem+json")),
                "expected problem+json but was $contentType body=${response.body}",
            )
            assertEquals(status.value(), JsonPath.read<Int>(response.body, "$.status"))
        }

        private fun assertLedgerInvariants() {
            val drifted =
                jdbc.queryForObject(
                    """
                    select count(*) from (
                        select a.id
                        from accounts a
                        left join postings p on p.account_id = a.id
                        group by a.id, a.kind, a.balance_minor
                        having a.balance_minor <> coalesce(sum(
                            case
                                when a.kind = 'CUSTOMER_WALLET' and p.side = 'CREDIT' then p.amount_minor
                                when a.kind = 'CUSTOMER_WALLET' and p.side = 'DEBIT' then -p.amount_minor
                                when a.kind = 'CASH_ASSET' and p.side = 'DEBIT' then p.amount_minor
                                when a.kind = 'CASH_ASSET' and p.side = 'CREDIT' then -p.amount_minor
                                else 0
                            end
                        ), 0)
                    ) drifted
                    """.trimIndent(),
                    Long::class.java,
                )
            assertEquals(0L, drifted, "account balance projection drifted from postings")

            val postings =
                jdbc.queryForMap(
                    """
                    select
                        coalesce(sum(amount_minor) filter (where side = 'DEBIT'), 0) as debits,
                        coalesce(sum(amount_minor) filter (where side = 'CREDIT'), 0) as credits
                    from postings
                    """.trimIndent(),
                )
            assertEquals((postings["debits"] as Number).toLong(), (postings["credits"] as Number).toLong())

            val balances =
                jdbc.queryForMap(
                    """
                    select
                        coalesce(sum(balance_minor) filter (where kind = 'CUSTOMER_WALLET'), 0) as customer,
                        coalesce(sum(balance_minor) filter (where kind = 'CASH_ASSET'), 0) as cash
                    from accounts
                    """.trimIndent(),
                )
            assertEquals((balances["customer"] as Number).toLong(), (balances["cash"] as Number).toLong())
        }

        private fun describe(response: ResponseEntity<String>): String = "${response.statusCode.value()} ${response.body}"

        private fun <T> parallel(
            times: Int,
            action: (Int) -> T,
        ): List<T> {
            val ready = CountDownLatch(times)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(times)
            try {
                val futures =
                    (0 until times).map { index ->
                        executor.submit<T> {
                            ready.countDown()
                            check(start.await(20, TimeUnit.SECONDS)) { "workers missed the start barrier" }
                            action(index)
                        }
                    }
                assertTrue(ready.await(20, TimeUnit.SECONDS), "workers did not reach the start barrier")
                start.countDown()
                return futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }

        companion object {
            @DynamicPropertySource
            @JvmStatic
            fun datasource(registry: DynamicPropertyRegistry) {
                val postgres = PostgresTestcontainersConfiguration.container
                registry.add("spring.datasource.url", postgres::getJdbcUrl)
                registry.add("spring.datasource.username", postgres::getUsername)
                registry.add("spring.datasource.password", postgres::getPassword)
            }
        }
    }
