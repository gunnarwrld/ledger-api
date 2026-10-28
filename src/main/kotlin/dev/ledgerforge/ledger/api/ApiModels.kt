package dev.ledgerforge.ledger.api

import dev.ledgerforge.ledger.domain.Account
import dev.ledgerforge.ledger.domain.AccountKind
import dev.ledgerforge.ledger.domain.EntryType
import dev.ledgerforge.ledger.domain.Posting
import dev.ledgerforge.ledger.domain.PostingSide
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

class CreateAccountRequest(
    @field:NotBlank @field:Size(max = 120)
    val name: String,
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currencyCode: String,
    @field:PositiveOrZero
    val openingBalanceMinor: Long = 0,
)

class TransferRequest(
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    @field:Positive
    val amountMinor: Long,
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currencyCode: String,
)

data class AccountResponse(
    val id: UUID,
    val name: String,
    val currencyCode: String,
    val kind: AccountKind,
    val balanceMinor: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(account: Account) =
            AccountResponse(
                id = account.id,
                name = account.name,
                currencyCode = account.currencyCode,
                kind = account.kind,
                balanceMinor = account.balanceMinor,
                createdAt = account.createdAt,
            )
    }
}

data class BalanceResponse(
    val accountId: UUID,
    val currencyCode: String,
    val balanceMinor: Long,
)

data class TransferResponse(
    val transferId: UUID,
    val journalEntryId: UUID,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    val amountMinor: Long,
    val currencyCode: String,
    val createdAt: Instant,
)

data class StatementLineResponse(
    val journalEntryId: UUID,
    val entryType: EntryType,
    val referenceId: UUID?,
    val postedAt: Instant,
    val side: PostingSide,
    val amountMinor: Long,
    val signedAmountMinor: Long,
    val currencyCode: String,
) {
    companion object {
        fun from(posting: Posting): StatementLineResponse {
            val positive =
                (posting.account.kind == AccountKind.CASH_ASSET && posting.side == PostingSide.DEBIT) ||
                    (posting.account.kind == AccountKind.CUSTOMER_WALLET && posting.side == PostingSide.CREDIT)
            return StatementLineResponse(
                journalEntryId = posting.journalEntry.id,
                entryType = posting.journalEntry.entryType,
                referenceId = posting.journalEntry.referenceId,
                postedAt = posting.createdAt,
                side = posting.side,
                amountMinor = posting.amountMinor,
                signedAmountMinor = if (positive) posting.amountMinor else -posting.amountMinor,
                currencyCode = posting.currencyCode,
            )
        }
    }
}
