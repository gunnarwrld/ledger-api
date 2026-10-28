package dev.ledgerforge.ledger.service

import dev.ledgerforge.ledger.domain.EntryType
import dev.ledgerforge.ledger.domain.JournalEntry
import dev.ledgerforge.ledger.domain.JournalEntryRules
import dev.ledgerforge.ledger.domain.Posting
import dev.ledgerforge.ledger.domain.PostingDraft
import dev.ledgerforge.ledger.persistence.AccountRepository
import dev.ledgerforge.ledger.persistence.JournalEntryRepository
import dev.ledgerforge.ledger.persistence.PostingRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class JournalWriter(
    private val accounts: AccountRepository,
    private val entries: JournalEntryRepository,
    private val postings: PostingRepository,
) {
    fun write(
        entryId: UUID,
        entryType: EntryType,
        referenceId: UUID,
        drafts: List<PostingDraft>,
        createdAt: Instant,
    ) {
        JournalEntryRules.validate(drafts)
        val entry =
            entries.save(
                JournalEntry(id = entryId, entryType = entryType, referenceId = referenceId, createdAt = createdAt),
            )
        postings.saveAll(
            drafts.map { draft ->
                val account = accounts.getReferenceById(draft.accountId)
                Posting(
                    journalEntry = entry,
                    account = account,
                    side = draft.side,
                    amountMinor = draft.amountMinor,
                    currencyCode = draft.currencyCode,
                    createdAt = createdAt,
                )
            },
        )
    }
}
