package dev.ledgerforge.ledger.domain

/** Validates the accounting invariant before a journal entry can be persisted. */
object JournalEntryRules {
    fun validate(postings: List<PostingDraft>) {
        require(postings.size >= 2) { "A journal entry must contain at least two postings" }
        require(postings.all { it.amountMinor > 0 }) { "Posting amounts must be positive minor units" }
        require(postings.map { it.currencyCode }.distinct().size == 1) {
            "All postings in a journal entry must use one currency"
        }
        val debits =
            postings
                .filter { it.side == PostingSide.DEBIT }
                .fold(0L) { total, posting -> Math.addExact(total, posting.amountMinor) }
        val credits =
            postings
                .filter { it.side == PostingSide.CREDIT }
                .fold(0L) { total, posting -> Math.addExact(total, posting.amountMinor) }
        require(debits == credits && debits > 0) { "Journal entry debits must equal credits" }
    }
}

data class PostingDraft(
    val accountId: java.util.UUID,
    val side: PostingSide,
    val amountMinor: Long,
    val currencyCode: String,
)
