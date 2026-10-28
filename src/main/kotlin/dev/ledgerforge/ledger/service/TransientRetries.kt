package dev.ledgerforge.ledger.service

import org.springframework.dao.TransientDataAccessException

internal const val TRANSIENT_ATTEMPTS = 5

internal fun <T> retryingTransientConflicts(block: () -> T): T {
    var last: TransientDataAccessException? = null
    repeat(TRANSIENT_ATTEMPTS) { attempt ->
        try {
            return block()
        } catch (exception: TransientDataAccessException) {
            last = exception
            if (attempt == TRANSIENT_ATTEMPTS - 1) {
                throw exception
            }
        }
    }
    throw last ?: IllegalStateException("transient retry failed")
}
