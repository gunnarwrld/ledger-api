package dev.ledgerforge.ledger.persistence

import dev.ledgerforge.ledger.domain.Account
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findBySystemKey(systemKey: String): Account?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun findLockedById(@Param("id") id: UUID): Account?
}
