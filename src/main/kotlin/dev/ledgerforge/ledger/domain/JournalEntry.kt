package dev.ledgerforge.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Immutable
@Table(name = "journal_entries")
class JournalEntry(
    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30, updatable = false)
    var entryType: EntryType = EntryType.TRANSFER,
    @Column(name = "reference_id", updatable = false)
    var referenceId: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)

enum class EntryType {
    OPENING_BALANCE,
    TRANSFER,
}
