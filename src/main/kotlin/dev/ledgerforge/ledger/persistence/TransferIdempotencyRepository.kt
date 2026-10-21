package dev.ledgerforge.ledger.persistence

import dev.ledgerforge.ledger.domain.TransferIdempotency
import org.springframework.data.jpa.repository.JpaRepository

interface TransferIdempotencyRepository : JpaRepository<TransferIdempotency, String>
