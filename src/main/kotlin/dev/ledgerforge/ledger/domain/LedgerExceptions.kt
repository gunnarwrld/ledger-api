package dev.ledgerforge.ledger.domain

class AccountNotFoundException(
    id: java.util.UUID,
) : RuntimeException("Account $id was not found")

class InsufficientFundsException : RuntimeException("The source account has insufficient funds")

class IdempotencyConflictException : RuntimeException("This idempotency key was already used for a different request")

class BalanceLimitExceededException : RuntimeException("The resulting account balance exceeds the supported range")

class SystemCashAccountCreationRaceException(
    cause: Throwable,
) : RuntimeException("Cash account was created concurrently", cause)
