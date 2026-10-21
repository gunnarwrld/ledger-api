package dev.ledgerforge.ledger.persistence

import dev.ledgerforge.ledger.domain.JournalEntry
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JournalEntryRepository : JpaRepository<JournalEntry, UUID>
