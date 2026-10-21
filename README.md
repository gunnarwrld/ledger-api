# Ledger Forge API

A Kotlin and Spring Boot REST service for a small, auditable wallet ledger. It stores money as signed 64-bit **integer minor units**, persists balanced journal entries in PostgreSQL, and serializes concurrent wallet mutations with database row locks.

## Design at a glance

- **Double entry:** every posting belongs to a journal entry. The application validates equal debit and credit totals before writing; a deferred PostgreSQL constraint trigger checks the completed entry again at commit. An opening balance debits a per-currency cash asset and credits the wallet liability. A wallet transfer debits the source wallet and credits the destination wallet.
- **Minor units:** requests use `amountMinor` as a positive `Long` and an uppercase ISO 4217 `currencyCode`. No floating-point amount enters the API or ledger. For example, `1250 SEK` means 12.50 SEK.
- **Idempotency:** `POST /api/v1/transfers` requires an `Idempotency-Key`. The key, SHA-256 request fingerprint, transfer ID, and journal ID are recorded in the same transaction as the balance updates and postings. A retry with the same key and request returns the original receipt; reusing a key for different transfer data returns `409 Conflict`. The database primary key arbitrates concurrent first requests with the same key.
- **Concurrency:** transfers run at PostgreSQL `READ COMMITTED` isolation. The service takes `SELECT ... FOR UPDATE` locks on both wallet rows in ascending UUID order before checking funds and changing balances. This prevents two requests from spending the same available balance and avoids opposite-direction lock ordering. JPA `@Version` adds a stale-write check. Balance changes and journal inserts commit or roll back together.
- **Append-only audit:** journal entries and postings are marked immutable in Hibernate and protected from `UPDATE` and `DELETE` by PostgreSQL triggers. Foreign keys use `ON DELETE RESTRICT`. The account balance is a transactionally maintained read projection; statements come from the immutable postings.

This is an internal ledger demonstration, not a payment rail. It intentionally leaves authentication and authorization, multi-tenancy, FX, fees, settlement, reversals, retention policy, and operational key management outside scope. Opening balances are demo funding entries against an internal cash control account.

## Requirements

- JDK 21 or newer
- Docker with Compose
- Internet access the first time Gradle resolves dependencies

The project compiles bytecode for Java 21. Gradle Wrapper pins the build distribution.

## Run locally

Start PostgreSQL:

```bash
docker compose up -d postgres
```

The compose file uses `ledger` / `ledger` credentials for local development only. Flyway applies the schema and append-only/balance enforcement triggers at application startup.

Run the API:

```bash
./gradlew bootRun
```

The API listens on `http://localhost:8080`. Interactive docs are at `http://localhost:8080/swagger-ui`; generated JSON is at `http://localhost:8080/v3/api-docs`. The checked-in contract is [`openapi.yaml`](openapi.yaml).

To use a different database, set `DB_URL`, `DB_USER`, and `DB_PASSWORD`. Example:

```bash
DB_URL=jdbc:postgresql://localhost:5432/ledger DB_USER=ledger DB_PASSWORD=ledger ./gradlew bootRun
```

## Example flow

Create two wallets. Opening balances are expressed in the currency's minor unit (SEK uses öre):

```bash
curl -sS -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Everyday","currencyCode":"SEK","openingBalanceMinor":50000}'

curl -sS -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Savings","currencyCode":"SEK","openingBalanceMinor":0}'
```

Use the returned account IDs in a transfer. Keep the key stable if retrying after a timeout:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: savings-transfer-001' \
  -d '{"sourceAccountId":"<everyday-id>","destinationAccountId":"<savings-id>","amountMinor":1250,"currencyCode":"SEK"}'
```

Read the balance or the latest 500 posting lines:

```bash
curl -sS http://localhost:8080/api/v1/accounts/<account-id>/balance
curl -sS http://localhost:8080/api/v1/accounts/<account-id>/statement
```

### HTTP surface

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/accounts` | Create a wallet and optionally post an opening balance |
| `GET` | `/api/v1/accounts/{id}` | Read account metadata and current balance |
| `GET` | `/api/v1/accounts/{id}/balance` | Read integer balance and currency |
| `GET` | `/api/v1/accounts/{id}/statement` | Read up to 500 recent immutable posting lines |
| `POST` | `/api/v1/transfers` | Transfer between wallets with an idempotency key |

Insufficient funds and balance overflow return `422`; an idempotency-key payload conflict returns `409`; invalid input returns `400`; a missing account returns `404`. Errors use RFC 9457 Problem Details.

## Tests

Run the complete test suite:

```bash
./gradlew test
```

Tests use H2 in PostgreSQL compatibility mode, so they do not need a running Docker daemon. Last full run: `BUILD SUCCESSFUL` — **8 tests, 0 failures, 0 errors, 0 skipped**. The suite includes journal invariant unit tests, an HTTP idempotency/replay check, a key-reuse conflict check, and a 24-worker concurrency test that verifies the source cannot overdraw and that debits, credits, and customer/cash control balances reconcile.
