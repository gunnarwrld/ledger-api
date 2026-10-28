package dev.ledgerforge.ledger.service

import dev.ledgerforge.ledger.domain.Account
import dev.ledgerforge.ledger.domain.AccountNotFoundException
import dev.ledgerforge.ledger.persistence.AccountRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Locks wallet rows in a stable order and reloads them.
 *
 * A plain `SELECT ... FOR UPDATE` is not enough when the account is already managed:
 * Hibernate keeps the stale snapshot and then fails the lock query with a version conflict
 * instead of waiting for the latest committed balance. `refresh` overwrites that snapshot
 * while the row lock is held.
 */
@Component
class AccountLocks(
    private val accounts: AccountRepository,
    private val entityManager: EntityManager,
) {
    fun lockInOrder(ids: Collection<UUID>): Map<UUID, Account> =
        ids
            .distinct()
            .sortedBy(UUID::toString)
            .associateWith(::lock)

    private fun lock(id: UUID): Account {
        val account = accounts.findById(id).orElseThrow { AccountNotFoundException(id) }
        entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE)
        return account
    }
}
