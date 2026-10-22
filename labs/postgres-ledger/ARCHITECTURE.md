# ADR — Database-Backed Transfer Semantics

## Decision

Store account balances and committed idempotency responses in PostgreSQL. Each transfer is one **READ COMMITTED** database transaction (the PostgreSQL default), with a unique idempotency key and non-key row locks acquired in stable account-ID order.

## Transfer steps

1. Validate key, account identifiers and a positive integer amount.
2. Begin a JDBC transaction.
3. Attempt `INSERT ... ON CONFLICT DO NOTHING RETURNING sequence` into `transfer_requests`.
4. If the key is already committed, read the stored request and compare its source, destination and amount. Return the original receipt only on exact match. PostgreSQL's unique-index conflict handling waits for a concurrent uncommitted winner before resolving `ON CONFLICT`.
5. If this is a new key, lock source and destination account rows using `SELECT ... FOR NO KEY UPDATE` in lexicographic account-ID order.
6. Use `FOR NO KEY UPDATE` (not `FOR UPDATE`) to lock mutable balances. The weaker lock still serializes concurrent balance updates but is compatible with the foreign-key `KEY SHARE` locks taken when a transfer request references account rows. `FOR UPDATE` caused a real deadlock in the first CI contention test despite account-ID ordering.
7. Check available funds and calculate both new balances, rejecting numeric overflow.
8. Update both account rows and **commit**. On any exception, **rollback**, including the idempotency-key insert.

## Invariants and examples

- The database `CHECK (balance_minor >= 0)` prevents negative persisted balances.
- The idempotency key is unique and its request fingerprint is checked before response replay.
- Same-key concurrent requests cannot each commit a transfer; one writer wins the unique constraint.
- Both account updates and the receipt commit or roll back as a unit.
- Each transaction takes row locks in one total order, avoiding opposing-transfer account lock cycles.
- The tests cover multiple JDBC clients, contention, overdrafts, rollback and replay.

## Trade-offs and unaddressed risks

- A transfer receipt is **durable only while the database retains its tables**; no backup or disaster recovery is configured.
- Failed idempotency reservations roll back, allowing retry. There is no durable record of failed validation attempts.
- Long-running transactions, hot account contention, external cancellation, database outages, retries after unknown commit outcomes and connection pool limits are not modeled.
- The generated identity sequence can have gaps after rollback; it is not a gap-free monetary journal.
- Money uses integer minor units but does not include a currency, scale, fee, or FX contract.
- The demonstration does not implement a double-entry journal, reconciliation, audit log, RBAC, abuse limits or schema migrations.
- The test harness **destroys** the named test tables before each test. It requires an explicit reset confirmation and refuses non-loopback JDBC URLs or database names other than `ledger_test`. These are safeguards, not a reason to run it against valuable data.

## Next engineering steps

Use Flyway/Liquibase for versioned migrations; introduce dedicated database roles and least privilege; test unknown commit outcomes and restart recovery; add double-entry bookkeeping and reconciliation; and define service-level expectations with measured load, tracing and runbooks.
