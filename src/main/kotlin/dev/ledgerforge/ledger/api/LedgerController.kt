package dev.ledgerforge.ledger.api

import dev.ledgerforge.ledger.service.AccountService
import dev.ledgerforge.ledger.service.TransferService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Validated
class LedgerController(
    private val accountService: AccountService,
    private val transferService: TransferService,
) {
    @PostMapping("/accounts")
    fun createAccount(
        @Valid @RequestBody request: CreateAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val account = accountService.create(request)
        return ResponseEntity.created(URI.create("/api/v1/accounts/${account.id}")).body(account)
    }

    @GetMapping("/accounts/{id}")
    fun getAccount(
        @PathVariable id: UUID,
    ): AccountResponse = accountService.get(id)

    @GetMapping("/accounts/{id}/balance")
    fun getBalance(
        @PathVariable id: UUID,
    ): BalanceResponse = accountService.balance(id)

    @GetMapping("/accounts/{id}/statement")
    fun getStatement(
        @PathVariable id: UUID,
    ): List<StatementLineResponse> = accountService.statement(id)

    @PostMapping("/transfers")
    fun transfer(
        @RequestHeader("Idempotency-Key")
        @NotBlank
        @Size(max = 128) idempotencyKey: String,
        @Valid @RequestBody request: TransferRequest,
    ): ResponseEntity<TransferResponse> = ResponseEntity.status(HttpStatus.CREATED).body(transferService.transfer(idempotencyKey, request))
}
