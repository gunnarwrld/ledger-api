package dev.ledgerforge.ledger.persistence

import dev.ledgerforge.ledger.domain.Account
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findBySystemKey(systemKey: String): Account?
}
