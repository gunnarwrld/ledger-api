package dev.ledgerforge.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "accounts")
class Account(
    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 120)
    var name: String = "",

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    var currencyCode: String = "USD",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    var kind: AccountKind = AccountKind.CUSTOMER_WALLET,

    @Column(name = "balance_minor", nullable = false)
    var balanceMinor: Long = 0,

    @Column(name = "system_key", unique = true, length = 80, updatable = false)
    var systemKey: String? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)

enum class AccountKind {
    CUSTOMER_WALLET,
    CASH_ASSET,
}
