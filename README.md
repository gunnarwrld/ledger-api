# Ledger Forge API

[![CI](https://github.com/gunnarwrld/ledger-api/actions/workflows/ci.yml/badge.svg)](https://github.com/gunnarwrld/ledger-api/actions/workflows/ci.yml)
[![Coverage](.github/badges/coverage.svg)](https://github.com/gunnarwrld/ledger-api/actions/workflows/ci.yml)

A Kotlin and Spring Boot REST service for a small, auditable wallet ledger. It stores money as signed 64-bit **integer minor units**, persists balanced journal entries in PostgreSQL, and serializes concurrent wallet mutations with database row locks.

## Architecture

```text
HTTP  /api/v1          LedgerController
                         |  problem details (RFC 9457)
AccountService           TransferService
  opening journals         idempotency + retries
         \                 /
          JournalWriter / AccountLocks
                 |
        Spring Data JPA + Hibernate
                 |
        Flyway schema  ->  PostgreSQL 17
        append-only triggers, deferred balance checks
```

The API process is stateless. PostgreSQL is the source of truth: Flyway creates the tables and triggers, Hibernate validates the mapping, and each command commits the balance projection and its journal together. Springdoc serves the interactive API docs. Actuator exposes readiness so Compose (or a platform probe) can tell when Flyway has finished and the database is reachable.

Checked-in contract: [`openapi.yaml`](openapi.yaml). Runtime docs: `http://localhost:8080/swagger-ui` and `http://localhost:8080/v3/api-docs`.

## Design decisions

### Double entry

Every movement is a journal entry with at least two postings. The application checks the invariant before insert: one currency, positive minor-unit amounts, and equal debit and credit totals. A deferred PostgreSQL constraint trigger checks the completed entry again at commit, so a partial insert cannot land.

An opening balance debits a per-currency cash asset and credits the customer-wallet liability. A transfer debits the source wallet and credits the destination wallet. No floating-point amount enters the API or the ledger. `1250` `SEK` means 12.50 SEK.

`accounts.balance_minor` is a transactionally maintained read projection, not the audit trail. A second deferred trigger rejects a commit when that projection disagrees with the signed sum of immutable postings (credits increase a wallet, debits increase cash). Statements are read from the postings.

### Idempotency

`POST /api/v1/transfers` requires an `Idempotency-Key` of 1 to 128 characters. The service hashes the canonical request (`source|destination|amount|currency`) with SHA-256 and writes the key, hash, transfer id, and journal id in the **same transaction** as the balance update and postings.

The idempotency row is inserted before the wallets are locked. A second request with the same key blocks on the primary key until the first transaction commits or rolls back:

- Same key and same hash returns the original receipt and does not post again.
- Same key and a different hash returns `409 Conflict`.
- A rejected transfer (validation, unknown account, insufficient funds) rolls the idempotency row back, so the key can be reused after the caller fixes the request.
- Only a duplicate `idempotency_records` primary key is treated as a replay. Other integrity failures, including the balance trigger, are not turned into a successful retry.

### Locking strategy

Transfers and opening balances run at PostgreSQL `READ COMMITTED`. Before checking funds or changing a balance, the service locks every involved account with `PESSIMISTIC_WRITE` and **refreshes** the managed entity, in ascending UUID string order.

The refresh matters. A cash-control account may already be loaded by its system key, and a new wallet is already in the persistence context after insert. A later `SELECT ... FOR UPDATE` that does not refresh keeps the stale version; Hibernate then aborts with a conflicting-version error instead of waiting for the latest committed balance. Refreshing under the lock replaces that snapshot after the row lock is acquired.

Locking in one global order prevents deadlocks between A→B and B→A transfers, and between a transfer and an opening balance that touches the same wallet. `@Version` remains a stale-write check on the `UPDATE`. If two requests race to create the first cash-control row for a currency, the `system_key` unique constraint fails the loser and the whole account-creation transaction is retried. Transient lock and optimistic-lock failures are retried a small number of times so a contended transfer is not reported as a server error when the funds are still available.

### Append-only audit

Journal entries and postings are immutable in Hibernate and protected from `UPDATE` and `DELETE` by PostgreSQL triggers. Foreign keys use `ON DELETE RESTRICT`. The balance column can change; the posting history cannot.

This is an internal ledger demonstration, not a payment rail. Authentication, multi-tenancy, FX, fees, settlement, reversals, and retention policy are out of scope. Opening balances are demo funding entries against an internal cash control account.

## Requirements

- JDK 21
- Docker with Compose, for the app stack and for tests
- Internet access the first time Gradle resolves dependencies

The project compiles bytecode for Java 21. Gradle Wrapper pins the build distribution.

## Run locally

Start PostgreSQL and the API. Compose waits until Postgres accepts connections, then until the app's readiness probe succeeds:

```bash
docker compose up --build -d
docker compose ps
```

The compose file uses `ledger` / `ledger` credentials for local development only. Flyway applies the schema at startup. The API listens on `http://localhost:8080`. Readiness is `http://localhost:8080/actuator/health/readiness`.

To run the API from Gradle against the Compose database instead of the app container:

```bash
docker compose up -d postgres
./gradlew bootRun
```

Override the database with `DB_URL`, `DB_USER`, and `DB_PASSWORD` when it is not the local default.

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

Insufficient funds and balance overflow return `422`. An idempotency-key payload conflict returns `409`. Invalid input, including a missing or blank `Idempotency-Key`, returns `400`. A missing account returns `404`. Errors use RFC 9457 Problem Details.

## Tests

Integration tests start PostgreSQL 17 with Testcontainers and run Flyway, including the append-only and balance triggers. Docker must be running. Unit tests for the journal rules do not need a database.

```bash
./gradlew test
```

`./gradlew check` also runs ktlint and detekt. Coverage is Kover:

```bash
./gradlew koverXmlReport koverHtmlReport
```

HTML output is `build/reports/kover/html/index.html`. The line-coverage badge in this README is generated from the Kover XML report:

```bash
python3 scripts/ci_summary.py --write
```

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests and on pushes to `master`. It runs `./gradlew check` (ktlint, detekt, and the full test suite), publishes the JUnit and Kover reports as a workflow artifact and a job summary, checks that the coverage badge matches the report, and builds the application image.
