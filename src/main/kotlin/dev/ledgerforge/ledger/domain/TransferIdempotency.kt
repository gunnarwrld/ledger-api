package dev.ledgerforge.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "idempotency_records")
class TransferIdempotency(
    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    var idempotencyKey: String = "",

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    var requestHash: String = "",

    @Column(name = "transfer_id", nullable = false, unique = true, updatable = false)
    var transferId: UUID = UUID.randomUUID(),

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    var journalEntryId: UUID = UUID.randomUUID(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)
