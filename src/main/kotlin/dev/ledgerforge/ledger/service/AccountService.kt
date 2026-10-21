package dev.ledgerforge.ledger.service

import dev.ledgerforge.ledger.api.AccountResponse
import dev.ledgerforge.ledger.api.BalanceResponse
import dev.ledgerforge.ledger.api.CreateAccountRequest
import dev.ledgerforge.ledger.api.StatementLineResponse
import dev.ledgerforge.ledger.domain.Account
import dev.ledgerforge.ledger.domain.AccountKind
import dev.ledgerforge.ledger.domain.AccountNotFoundException
import dev.ledgerforge.ledger.domain.BalanceLimitExceededException
import dev.ledgerforge.ledger.domain.EntryType
import dev.ledgerforge.ledger.domain.JournalEntry
import dev.ledgerforge.ledger.domain.JournalEntryRules
import dev.ledgerforge.ledger.domain.Posting
import dev.ledgerforge.ledger.domain.PostingDraft
import dev.ledgerforge.ledger.domain.PostingSide
import dev.ledgerforge.ledger.domain.SystemCashAccountCreationRaceException
import dev.ledgerforge.ledger.persistence.AccountRepository
import dev.ledgerforge.ledger.persistence.JournalEntryRepository
import dev.ledgerforge.ledger.persistence.PostingRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AccountService(
    private val creation: AccountCreationTransaction,
    private val accounts: AccountRepository,
    private val postings: PostingRepository,
) {
    fun create(request: CreateAccountRequest): AccountResponse = try {
        creation.create(request)
    } catch (exception: SystemCashAccountCreationRaceException) {
        val key = "CASH:${requireIsoCurrency(request.currencyCode)}"
        if (accounts.findBySystemKey(key) == null) {
            throw exception.cause as? DataIntegrityViolationException ?: exception
        }
        // The transaction that won first-use creation has committed; retry this whole account transaction.
        creation.create(request)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): AccountResponse = AccountResponse.from(requireAccount(id))

    @Transactional(readOnly = true)
    fun balance(id: UUID): BalanceResponse {
        val account = requireAccount(id)
        return BalanceResponse(account.id, account.currencyCode, account.balanceMinor)
    }

    @Transactional(readOnly = true)
    fun statement(id: UUID): List<StatementLineResponse> {
        requireAccount(id)
        val page = PageRequest.of(0, 500)
        return postings.findRecentForAccount(id, page)
            .asReversed()
            .map(StatementLineResponse::from)
    }

    private fun requireAccount(id: UUID): Account =
        accounts.findById(id).orElseThrow { AccountNotFoundException(id) }
}

@Service
class AccountCreationTransaction(
    private val accounts: AccountRepository,
    private val journalEntries: JournalEntryRepository,
    private val postings: PostingRepository,
) {
    @Transactional
    fun create(request: CreateAccountRequest): AccountResponse {
        val currency = requireIsoCurrency(request.currencyCode)
        require(request.openingBalanceMinor >= 0) { "Opening balance cannot be negative" }
        val customer = accounts.saveAndFlush(
            Account(
                name = request.name.trim(),
                currencyCode = currency,
                kind = AccountKind.CUSTOMER_WALLET,
            ),
        )
        if (request.openingBalanceMinor > 0) {
            val cash = getOrCreateCashAccount(currency)
            val locked = lockAccounts(listOf(customer.id, cash.id))
            val customerAccount = locked.getValue(customer.id)
            val cashAccount = locked.getValue(cash.id)
            cashAccount.balanceMinor = addExact(cashAccount.balanceMinor, request.openingBalanceMinor)
            customerAccount.balanceMinor = addExact(customerAccount.balanceMinor, request.openingBalanceMinor)
            accounts.saveAll(listOf(cashAccount, customerAccount))

            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val entry = journalEntries.save(
                JournalEntry(entryType = EntryType.OPENING_BALANCE, createdAt = now),
            )
            val draft = listOf(
                PostingDraft(cashAccount.id, PostingSide.DEBIT, request.openingBalanceMinor, currency),
                PostingDraft(customerAccount.id, PostingSide.CREDIT, request.openingBalanceMinor, currency),
            )
            JournalEntryRules.validate(draft)
            postings.saveAll(draft.map { posting ->
                Posting(
                    journalEntry = entry,
                    account = locked.getValue(posting.accountId),
                    side = posting.side,
                    amountMinor = posting.amountMinor,
                    currencyCode = posting.currencyCode,
                    createdAt = now,
                )
            })
        }
        return AccountResponse.from(customer)
    }

    private fun getOrCreateCashAccount(currency: String): Account {
        val key = "CASH:$currency"
        accounts.findBySystemKey(key)?.let { return it }
        return try {
            accounts.saveAndFlush(
                Account(
                    name = "Cash control $currency",
                    currencyCode = currency,
                    kind = AccountKind.CASH_ASSET,
                    systemKey = key,
                ),
            )
        } catch (exception: DataIntegrityViolationException) {
            // Let the transaction roll back before the facade checks the winning row and retries.
            throw SystemCashAccountCreationRaceException(exception)
        }
    }

    private fun lockAccounts(ids: List<UUID>): Map<UUID, Account> = ids.distinct()
        .sortedBy(UUID::toString)
        .associateWith { id -> accounts.findLockedById(id) ?: throw AccountNotFoundException(id) }

    private fun addExact(balance: Long, amount: Long): Long = try {
        Math.addExact(balance, amount)
    } catch (_: ArithmeticException) {
        throw BalanceLimitExceededException()
    }
}
