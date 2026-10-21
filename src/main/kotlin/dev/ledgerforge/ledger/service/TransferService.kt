package dev.ledgerforge.ledger.service

import dev.ledgerforge.ledger.api.TransferRequest
import dev.ledgerforge.ledger.api.TransferResponse
import dev.ledgerforge.ledger.domain.AccountKind
import dev.ledgerforge.ledger.domain.AccountNotFoundException
import dev.ledgerforge.ledger.domain.BalanceLimitExceededException
import dev.ledgerforge.ledger.domain.EntryType
import dev.ledgerforge.ledger.domain.IdempotencyConflictException
import dev.ledgerforge.ledger.domain.InsufficientFundsException
import dev.ledgerforge.ledger.domain.PostingDraft
import dev.ledgerforge.ledger.domain.PostingSide
import dev.ledgerforge.ledger.domain.TransferIdempotency
import dev.ledgerforge.ledger.persistence.AccountRepository
import dev.ledgerforge.ledger.persistence.TransferIdempotencyRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class TransferService(
    private val transaction: TransferTransaction,
    private val idempotency: TransferIdempotencyRepository,
) {
    fun transfer(key: String, request: TransferRequest): TransferResponse {
        require(key.isNotBlank() && key.length <= 128) { "Idempotency-Key must contain 1 to 128 characters" }
        val currency = requireIsoCurrency(request.currencyCode)
        require(request.amountMinor > 0) { "amountMinor must be positive" }
        require(request.sourceAccountId != request.destinationAccountId) { "Source and destination must differ" }
        val canonical = "${request.sourceAccountId}|${request.destinationAccountId}|${request.amountMinor}|$currency"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

        return try {
            transaction.execute(key, hash, request.copyCurrency(currency))
        } catch (exception: DataIntegrityViolationException) {
            val existing = idempotency.findById(key).orElseThrow { exception }
            if (existing.requestHash != hash) throw IdempotencyConflictException()
            existing.toResponse(request)
        }
    }

    private fun TransferRequest.copyCurrency(currency: String) =
        TransferRequest(sourceAccountId, destinationAccountId, amountMinor, currency)

    private fun TransferIdempotency.toResponse(request: TransferRequest) = TransferResponse(
        transferId = transferId,
        journalEntryId = journalEntryId,
        sourceAccountId = request.sourceAccountId,
        destinationAccountId = request.destinationAccountId,
        amountMinor = request.amountMinor,
        currencyCode = request.currencyCode,
        createdAt = createdAt,
    )
}

@Service
class TransferTransaction(
    private val accounts: AccountRepository,
    private val idempotency: TransferIdempotencyRepository,
    private val journalWriter: JournalWriter,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun execute(key: String, requestHash: String, request: TransferRequest): TransferResponse {
        idempotency.findById(key).orElse(null)?.let { existing ->
            if (existing.requestHash != requestHash) throw IdempotencyConflictException()
            return existing.toResponse(request)
        }

        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val receipt = TransferIdempotency(
            idempotencyKey = key,
            requestHash = requestHash,
            transferId = UUID.randomUUID(),
            journalEntryId = UUID.randomUUID(),
            createdAt = now,
        )
        idempotency.saveAndFlush(receipt)

        val orderedIds = listOf(request.sourceAccountId, request.destinationAccountId).sortedBy(UUID::toString)
        val locked = orderedIds.associateWith { id ->
            accounts.findLockedById(id) ?: throw AccountNotFoundException(id)
        }
        val source = locked.getValue(request.sourceAccountId)
        val destination = locked.getValue(request.destinationAccountId)
        require(source.kind == AccountKind.CUSTOMER_WALLET && destination.kind == AccountKind.CUSTOMER_WALLET) {
            "Transfers are allowed only between customer wallets"
        }
        require(source.currencyCode == request.currencyCode && destination.currencyCode == request.currencyCode) {
            "Transfer currency must match both account currencies"
        }
        if (source.balanceMinor < request.amountMinor) throw InsufficientFundsException()
        source.balanceMinor = subtractExact(source.balanceMinor, request.amountMinor)
        destination.balanceMinor = addExact(destination.balanceMinor, request.amountMinor)
        accounts.saveAll(listOf(source, destination))

        journalWriter.write(
            entryId = receipt.journalEntryId,
            entryType = EntryType.TRANSFER,
            referenceId = receipt.transferId,
            drafts = listOf(
                PostingDraft(source.id, PostingSide.DEBIT, request.amountMinor, request.currencyCode),
                PostingDraft(destination.id, PostingSide.CREDIT, request.amountMinor, request.currencyCode),
            ),
            createdAt = now,
        )
        return receipt.toResponse(request)
    }

    private fun addExact(balance: Long, amount: Long): Long = try {
        Math.addExact(balance, amount)
    } catch (_: ArithmeticException) {
        throw BalanceLimitExceededException()
    }

    private fun subtractExact(balance: Long, amount: Long): Long = try {
        Math.subtractExact(balance, amount)
    } catch (_: ArithmeticException) {
        throw InsufficientFundsException()
    }

    private fun TransferIdempotency.toResponse(request: TransferRequest) = TransferResponse(
        transferId = transferId,
        journalEntryId = journalEntryId,
        sourceAccountId = request.sourceAccountId,
        destinationAccountId = request.destinationAccountId,
        amountMinor = request.amountMinor,
        currencyCode = request.currencyCode,
        createdAt = createdAt,
    )
}
