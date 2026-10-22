# PostgreSQL Ledger — Transaction and Idempotency Lab

A deliberately narrow **Java 21 + JDBC + PostgreSQL** exercise comparing an in-memory transfer model with durable relational database transactions.

> **Danger — test setup deletes tables.** The JUnit test class drops and recreates `accounts` and `transfer_requests` in the configured database before each test. **Never point these tests at production or a shared database.**

## What this demonstrates

- Atomic debit and credit using **one PostgreSQL transaction**.
- Durable idempotency keys with a unique constraint; concurrent duplicate requests wait for a consistent outcome.
- Request fingerprints: a key cannot replay a different source, destination or amount.
- Row-level account locking in lexicographic ID order to avoid A/B–B/A deadlocks.
- Conservation of funds, rejection of overdrafts, and rollback after overflow or insufficient funds.
- Verification against **two distinct JDBC clients**, rather than an in-process cache.

These guarantees are tested with a disposable PostgreSQL instance. There is no HTTP API, authentication, tenant isolation, event journal, monitoring, online migration, or deployment pipeline.

## Execute the integration tests

Requires JDK 21, Maven and Docker Compose. From the repository root:

```bash
docker compose -f labs/postgres-ledger/compose.yaml up -d --wait
export LEDGER_JDBC_URL="jdbc:postgresql://localhost:15432/ledger"
export LEDGER_DB_USER="ledger"
export LEDGER_DB_PASSWORD="ledger"
mvn -f labs/postgres-ledger/pom.xml --batch-mode verify
docker compose -f labs/postgres-ledger/compose.yaml down -v
```

The test-only credentials are deliberately local and the database is bound to loopback on port 15432. **Tests drop and recreate their named tables**; never reuse the environment variables with a real database.

If you already have a disposable PostgreSQL instance, set the same three environment variables to that instance instead.

The [GitHub Actions workflow](../../.github/workflows/postgres-ledger.yml) creates a throwaway PostgreSQL service with test-only credentials and executes the same command.

## Design

See [ARCHITECTURE.md](ARCHITECTURE.md) for transaction and retry semantics, correctness arguments, and production limitations.

For a JVM-local counterpart, see [Ledger Core](../ledger-core/README.md).

## Scope

**This is an educational experiment, not production financial software.** Account opening is an administrative helper, financial amounts do not specify a currency, idempotency keys never expire, and no general-purpose storage abstraction is implemented.
