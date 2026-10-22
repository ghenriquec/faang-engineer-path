# Reliable Ledger Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 ledger lab that demonstrates durable idempotency, ordered PostgreSQL locks, balanced journal entries, reconciliation, a transactional outbox, bounded HTTP inputs, and repeatable verification.

**Architecture:** Domain records carry validated identifiers and amounts. `LedgerService` coordinates use cases through focused account, transfer, and reconciliation ports; PostgreSQL adapters implement write use cases as Spring transactions with explicit JDBC SQL. MVC controllers expose the application API and map domain failures to RFC 9457 problem responses.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring JDBC, PostgreSQL 17, Flyway, Maven, JUnit 5, Testcontainers 2.0.5, GitHub Actions

**Spec:** `docs/superpowers/specs/2026-10-22-backend-portfolio-overhaul-design.md`

## Global Constraints

- Keep `labs/ledger-core` and `labs/postgres-ledger` intact.
- Use integer minor units; accept no floating-point monetary values.
- Bind SQL values through `JdbcClient`; do not concatenate request data into SQL.
- Keep the lab self-contained under `labs/reliable-ledger-service`.
- Use a disposable Testcontainers database; accept no user-supplied integration-test database URL.
- Add no deployment workflow, broker, cloud resource, unsupported benchmark, or production claim.
- Give GitHub Actions `contents: read` and no write permissions.
- Commit directly to `main` through the GitHub integration; create no pull request.

## Review Focus

- Unicode, whitespace, path separators, and overlong account IDs must fail before persistence; Task 1 tests these values.
- `Long.MAX_VALUE` destination balances must make a transfer fail without writes; Task 3 tests overflow rollback.
- Concurrent reuse of one key with two payloads must commit one payload and reject the other; Task 3 tests this race before implementing transfers.
- A database failure after journal insertion must roll back balances, journal, request, and outbox; Task 3 installs a test-only PostgreSQL trigger before implementing transfers.
- Problem responses must not include SQL text, JDBC URLs, or exception class names; Task 5 tests client-visible bodies.

---

### Task 1: Project shell and domain contracts

**Files:**
- Create: `labs/reliable-ledger-service/pom.xml`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/ReliableLedgerApplication.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/AccountId.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/IdempotencyKey.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/TransferCommand.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/TransferReceipt.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/LedgerException.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/InvalidLedgerCommand.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/AccountAlreadyExists.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/AccountNotFound.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/InsufficientFunds.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/IdempotencyConflict.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/domain/LedgerDomainTest.java`

**Interfaces:**
- Produces: `AccountId.of(String)`, `IdempotencyKey.of(String)`, `TransferCommand(AccountId from, AccountId to, long amountMinor)`, and `TransferReceipt(UUID transferId, long sequence, AccountId from, AccountId to, long amountMinor)`.
- Validation: account IDs match `[a-z0-9][a-z0-9-]{2,63}`; idempotency keys contain 8 through 128 printable ASCII characters; transfers require distinct accounts and `amountMinor > 0`.

- [ ] **Step 1: Add the Maven build with Java 21, Boot 3.5.16, JDBC, Web, Validation, Flyway, PostgreSQL, and test dependencies**

Run: `docker run --rm -v "$PWD":/workspace -w /workspace/labs/reliable-ledger-service maven:3.9.11-eclipse-temurin-21 mvn --batch-mode --no-transfer-progress test -DskipTests`
Expected: build reaches test compilation; it may report no tests.

- [ ] **Step 2: Write `LedgerDomainTest` with literal valid and invalid boundary cases**

Assert `acct-001` and `request-0001` succeed. Assert blank, Unicode, slash-containing, 65-character account IDs, seven-character keys, 129-character keys, self-transfers, zero amounts, and negative amounts throw `InvalidLedgerCommand`.

```java
assertEquals("acct-001", AccountId.of("acct-001").value());
assertThrows(InvalidLedgerCommand.class, () -> AccountId.of("áccount"));
assertThrows(InvalidLedgerCommand.class,
        () -> new TransferCommand(AccountId.of("acct-001"), AccountId.of("acct-001"), 1));
assertThrows(InvalidLedgerCommand.class,
        () -> new TransferCommand(AccountId.of("acct-001"), AccountId.of("acct-002"), 0));
```

- [ ] **Step 3: Run the domain test and confirm RED**

Run: `docker run --rm -v "$PWD":/workspace -w /workspace/labs/reliable-ledger-service maven:3.9.11-eclipse-temurin-21 mvn -Dtest=LedgerDomainTest test`
Expected: FAIL because the domain types do not exist.

- [ ] **Step 4: Implement the domain records and sealed exception hierarchy**

`LedgerException` permits `InvalidLedgerCommand`, `AccountAlreadyExists`, `AccountNotFound`, `InsufficientFunds`, and `IdempotencyConflict`. Exception messages contain field names and public identifiers, never credentials or SQL.

- [ ] **Step 5: Run the test and full module suite**

Run: `docker run --rm -v "$PWD":/workspace -w /workspace/labs/reliable-ledger-service maven:3.9.11-eclipse-temurin-21 mvn --batch-mode --no-transfer-progress verify`
Expected: PASS with all domain cases green.

- [ ] **Step 6: Commit**

Commit message: `feat: define reliable ledger domain contracts`

### Task 2: Versioned schema and account persistence

**Files:**
- Create: `labs/reliable-ledger-service/src/main/resources/application.yaml`
- Create: `labs/reliable-ledger-service/src/main/resources/db/migration/V1__ledger_schema.sql`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/AccountStore.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/LedgerService.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/persistence/PostgresAccountStore.java`
- Create: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/support/PostgresIntegrationTest.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/persistence/AccountPersistenceTest.java`

**Interfaces:**
- Produces: `AccountStore.openAccount(AccountId,long)`, `AccountStore.balance(AccountId)`, `LedgerService.openAccount(String,long)`, and `LedgerService.balance(String)`.
- Schema produces: `accounts`, `transfer_requests`, `ledger_entries`, and `outbox_events`; `accounts` stores opening and current balance projections with nonnegative checks.

- [ ] **Step 1: Write `AccountPersistenceTest` against `postgres:17-alpine`**

Assert an account persists across two `LedgerService` instances, duplicate creation throws `AccountAlreadyExists`, unknown lookup throws `AccountNotFound`, and negative opening balance fails before SQL.

```java
first.openAccount("acct-001", 500);
assertEquals(500, second.balance("acct-001"));
assertThrows(AccountAlreadyExists.class, () -> second.openAccount("acct-001", 0));
assertThrows(AccountNotFound.class, () -> second.balance("acct-404"));
```

- [ ] **Step 2: Run the persistence test and confirm RED**

Run: `docker run --rm -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "$PWD":/workspace -w /workspace/labs/reliable-ledger-service -v /var/run/docker.sock:/var/run/docker.sock maven:3.9.11-eclipse-temurin-21 mvn -Dtest=AccountPersistenceTest test`
Expected: FAIL because the store, service, and schema do not exist.

- [ ] **Step 3: Implement Flyway schema, Testcontainers base, port, service, and JDBC account methods**

`application.yaml` reads `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` with no credential defaults. Tests supply container properties through `@DynamicPropertySource`.

- [ ] **Step 4: Run the persistence test and full suite**

Run: same command with `-Dtest=AccountPersistenceTest`, then replace the selector with `verify`.
Expected: PASS; Flyway applies V1 to a container database.

- [ ] **Step 5: Commit**

Commit message: `feat: persist ledger accounts with versioned schema`

### Task 3: Atomic transfer, journal, durable replay, and outbox

**Files:**
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/TransferStore.java`
- Modify: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/LedgerService.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/persistence/PostgresTransferStore.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/TransferResult.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/persistence/TransferTransactionTest.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/persistence/TransferConcurrencyTest.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/persistence/TransferRollbackTest.java`

**Interfaces:**
- Consumes: Task 1 domain records and Task 2 schema.
- Produces: `TransferStore.transfer(IdempotencyKey,TransferCommand)` and `LedgerService.transfer(String,String,String,long)` returning `TransferResult(TransferReceipt receipt, boolean replayed)`.
- Transaction contract: one commit contains the idempotency record, two opposite-signed journal entries, both balance updates, and one `transfer.committed` outbox row.

- [ ] **Step 1: Write failing transaction tests**

Assert one transfer changes `2_000/100` to `1_925/175`; a fresh service replays the same receipt; conflicting payload throws `IdempotencyConflict`; insufficient funds and `Long.MAX_VALUE` overflow leave every table unchanged; journal signs sum to zero; one outbox row contains the transfer ID and sequence. Add concurrent tests for 20 same-key requests, 50 withdrawals from a balance of 25, eight opposing workers, and same-key conflicting payloads. Add a PostgreSQL trigger that raises SQLSTATE `P0001` on outbox insertion and assert rollback of every transfer table.

```java
TransferResult created = ledger.transfer("request-0001", "acct-001", "acct-002", 75);
assertFalse(created.replayed());
assertEquals(1_925, ledger.balance("acct-001"));
assertEquals(175, ledger.balance("acct-002"));
assertEquals(created.receipt(), freshClient.transfer(
        "request-0001", "acct-001", "acct-002", 75).receipt());
assertEquals(0, jdbcClient.sql("select sum(direction * amount_minor) from ledger_entries")
        .query(Long.class).single());
```

- [ ] **Step 2: Run `TransferTransactionTest` and confirm RED**

Run: Docker-backed Maven command from Task 2 with `-Dtest=TransferTransactionTest,TransferConcurrencyTest,TransferRollbackTest`.
Expected: FAIL because transfer persistence is absent.

- [ ] **Step 3: Implement request fingerprinting and the transactional JDBC algorithm**

Use SHA-256 over the UTF-8 string `from + "\n" + to + "\n" + amountMinor`. Lock accounts with `SELECT ... ORDER BY id FOR NO KEY UPDATE`. Compute both balances with `Math.addExact` and `Math.subtractExact` before updates. Serialize the outbox payload with the configured Jackson `ObjectMapper`.

- [ ] **Step 4: Run the targeted test and full suite**

Run: targeted tests, then Docker-backed `mvn verify` three separate times.
Expected: three PASS results with no partial rows or concurrency timeouts.

- [ ] **Step 5: Commit**

Commit message: `feat: commit concurrent transfers journal and outbox atomically`

### Task 4: Reconciliation query

**Files:**
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/domain/ReconciliationMismatch.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/ReconciliationStore.java`
- Modify: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/application/LedgerService.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/persistence/PostgresReconciliationStore.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/persistence/ReconciliationTest.java`

**Interfaces:**
- Produces: `LedgerService.reconcile()` returning `List<ReconciliationMismatch>`; each mismatch contains `AccountId accountId`, `long projectedBalanceMinor`, and `long journalBalanceMinor`.

- [ ] **Step 1: Write failing reconciliation tests**

Assert normal transfers produce an empty list. Corrupt one balance through direct test SQL and assert one literal mismatch with projected `999` and journal-derived `90`. Assert reconciliation does not modify the row.

```java
assertTrue(ledger.reconcile().isEmpty());
jdbcClient.sql("update accounts set balance_minor = 999 where id = 'acct-001'").update();
assertEquals(List.of(new ReconciliationMismatch(AccountId.of("acct-001"), 999, 90)),
        ledger.reconcile());
assertEquals(999, ledger.balance("acct-001"));
```

- [ ] **Step 2: Run the test and confirm RED**

- [ ] **Step 3: Implement one read-only SQL aggregation over opening balances and signed journal entries**

- [ ] **Step 4: Run the targeted test and full suite**

- [ ] **Step 5: Commit**

Commit message: `feat: detect ledger projection drift`

### Task 5: HTTP contract and safe problem responses

**Files:**
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/AccountController.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/TransferController.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/ReconciliationController.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/LedgerExceptionHandler.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/LedgerRequests.java`
- Create: `labs/reliable-ledger-service/src/main/java/dev/ghenriquec/reliableledger/web/RequestSizeFilter.java`
- Test: `labs/reliable-ledger-service/src/test/java/dev/ghenriquec/reliableledger/web/LedgerHttpTest.java`

**Interfaces:**
- Produces: `POST /api/accounts`, `GET /api/accounts/{id}/balance`, `POST /api/transfers`, and `GET /api/reconciliation`.
- `POST /api/transfers` consumes `Idempotency-Key`; it returns 201 for a new commit and 200 for a replay. Problems use `application/problem+json` with stable types under `https://github.com/ghenriquec/faang-engineer-path/problems/`.

- [ ] **Step 1: Write failing `MockMvc` HTTP tests against the real service and container database**

Cover 201 account creation with `Location`, 409 duplicate, 404 unknown account, 201 transfer, 200 replay, 409 conflicting key, 422 insufficient funds, 400 malformed identifiers/amounts, and 413 for known-length and chunked bodies over 16 KiB. Force a SQL exception and assert the 500 body omits `jdbc:`, `select`, `org.postgresql`, and the exception class.

```java
mockMvc.perform(post("/api/transfers")
        .header("Idempotency-Key", "request-0001")
        .contentType(APPLICATION_JSON)
        .content("{\"from\":\"acct-001\",\"to\":\"acct-002\",\"amountMinor\":75}"))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.amountMinor").value(75));
mockMvc.perform(get("/api/accounts/acct-404/balance"))
    .andExpect(status().isNotFound())
    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
```

- [ ] **Step 2: Run the HTTP test and confirm RED**

- [ ] **Step 3: Implement request records, controllers, and exception mapping**

Cap JSON request bodies through server configuration at 16 KiB. Return request correlation IDs in problems without logging request bodies or credentials.

- [ ] **Step 4: Run HTTP tests and full suite**

- [ ] **Step 5: Commit**

Commit message: `feat: expose bounded ledger HTTP contracts`

### Task 6: ADRs, runbook, least-privilege SQL, and README

**Files:**
- Create: `labs/reliable-ledger-service/README.md`
- Create: `labs/reliable-ledger-service/docs/adr/0001-ordered-pessimistic-locks.md`
- Create: `labs/reliable-ledger-service/docs/adr/0002-journal-and-reconciliation.md`
- Create: `labs/reliable-ledger-service/docs/adr/0003-transactional-outbox-boundary.md`
- Create: `labs/reliable-ledger-service/docs/RUNBOOK.md`
- Create: `labs/reliable-ledger-service/docs/least-privilege-role.sql`
- Create: `labs/reliable-ledger-service/compose.yaml`
- Modify: `README.md`

**Interfaces:**
- Consumes: verified behavior from Tasks 1 through 5.
- Produces: reproducible local commands, explicit guarantees and limits, design consequences, proposed SLIs, and grants for only the four ledger tables and required sequences.

- [ ] **Step 1: Draft documents with no unsupported performance, availability, employer, or deployment claims**

- [ ] **Step 2: Execute every README command against the compose database**

Run the documented database start, `mvn verify`, application start, account creation, transfer, replay, and reconciliation commands.
Expected: responses match the README literals; reconciliation returns an empty list.

- [ ] **Step 3: Apply `least-privilege-role.sql` to a disposable database and run the application suite with that role**

Run Flyway with the database owner, then start the application role with Flyway disabled.
Expected: application operations pass; the role cannot create or drop schema objects.

- [ ] **Step 4: Review prose against implemented code and remove future-tense feature claims**

- [ ] **Step 5: Commit**

Commit message: `docs: explain reliable ledger decisions and operations`

### Task 7: CI and final repository verification

**Files:**
- Create: `.github/workflows/reliable-ledger-service.yml`
- Modify: `.gitignore`
- Modify: `README.md`

**Interfaces:**
- Produces: a `Reliable ledger service` workflow that runs module `verify` on relevant pushes and pull requests with a 15-minute timeout and read-only contents permission.

- [ ] **Step 1: Add the workflow with Temurin 21 and Maven cache keyed to the new POM**

- [ ] **Step 2: Validate workflow syntax and path filters, then run local full verification**

Run: Docker-backed `mvn verify` and `git diff --check`.
Expected: exit 0 and zero test failures.

- [ ] **Step 3: Commit through the GitHub integration and wait for all workflows on the resulting SHA**

Expected: reliable-ledger, ledger-core, and PostgreSQL-ledger workflows that match their path filters conclude successfully.

- [ ] **Step 4: Fetch the default-branch files and final commit through the GitHub integration**

Expected: remote content matches the verified local tree; no pull request or deployment exists.

