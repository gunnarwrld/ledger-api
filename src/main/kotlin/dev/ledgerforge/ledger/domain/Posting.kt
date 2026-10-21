package dev.ledgerforge.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Immutable
@Table(name = "postings")
class Posting(
    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    var journalEntry: JournalEntry = JournalEntry(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    var account: Account = Account(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    var side: PostingSide = PostingSide.DEBIT,

    @Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 1,

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    var currencyCode: String = "USD",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)

enum class PostingSide {
    DEBIT,
    CREDIT,
}
