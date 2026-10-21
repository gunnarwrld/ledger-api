package dev.ledgerforge.ledger.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class JournalEntryRulesTest {
    private val left = UUID.randomUUID()
    private val right = UUID.randomUUID()

    @Test
    fun `accepts equal debit and credit totals`() {
        assertDoesNotThrow {
            JournalEntryRules.validate(
                listOf(
                    PostingDraft(left, PostingSide.DEBIT, 1250, "SEK"),
                    PostingDraft(right, PostingSide.CREDIT, 1250, "SEK"),
                ),
            )
        }
    }

    @Test
    fun `rejects unequal totals`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalEntryRules.validate(
                listOf(
                    PostingDraft(left, PostingSide.DEBIT, 1250, "SEK"),
                    PostingDraft(right, PostingSide.CREDIT, 1249, "SEK"),
                ),
            )
        }
    }

    @Test
    fun `rejects mixed currency and non-positive amounts`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalEntryRules.validate(
                listOf(
                    PostingDraft(left, PostingSide.DEBIT, 100, "SEK"),
                    PostingDraft(right, PostingSide.CREDIT, 100, "EUR"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalEntryRules.validate(
                listOf(
                    PostingDraft(left, PostingSide.DEBIT, 0, "SEK"),
                    PostingDraft(right, PostingSide.CREDIT, 0, "SEK"),
                ),
            )
        }
    }
}
