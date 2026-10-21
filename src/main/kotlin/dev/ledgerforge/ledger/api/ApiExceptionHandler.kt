package dev.ledgerforge.ledger.api

import dev.ledgerforge.ledger.domain.AccountNotFoundException
import dev.ledgerforge.ledger.domain.BalanceLimitExceededException
import dev.ledgerforge.ledger.domain.IdempotencyConflictException
import dev.ledgerforge.ledger.domain.InsufficientFundsException
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(AccountNotFoundException::class)
    fun notFound(exception: AccountNotFoundException) = problem(HttpStatus.NOT_FOUND, exception.message ?: "Account not found")

    @ExceptionHandler(IdempotencyConflictException::class)
    fun idempotencyConflict(exception: IdempotencyConflictException) =
        problem(HttpStatus.CONFLICT, exception.message ?: "Idempotency key conflict")

    @ExceptionHandler(InsufficientFundsException::class, BalanceLimitExceededException::class)
    fun unprocessable(exception: RuntimeException) =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, exception.message ?: "Transfer cannot be applied")

    @ExceptionHandler(MethodArgumentNotValidException::class, ConstraintViolationException::class, IllegalArgumentException::class)
    fun badRequest(exception: Exception) = problem(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(exception: DataIntegrityViolationException) =
        problem(HttpStatus.CONFLICT, "The request conflicts with an existing ledger record")

    private fun problem(status: HttpStatus, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("about:blank")
            title = status.reasonPhrase
        }
}
