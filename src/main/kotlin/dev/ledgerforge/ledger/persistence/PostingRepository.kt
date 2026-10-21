package dev.ledgerforge.ledger.persistence

import dev.ledgerforge.ledger.domain.Posting
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PostingRepository : JpaRepository<Posting, UUID> {
    @Query("select p from Posting p join fetch p.journalEntry join fetch p.account where p.account.id = :accountId order by p.createdAt desc, p.id desc")
    fun findRecentForAccount(@Param("accountId") accountId: UUID, pageable: Pageable): List<Posting>

    @Query("select count(p) from Posting p")
    fun countPostings(): Long
}
