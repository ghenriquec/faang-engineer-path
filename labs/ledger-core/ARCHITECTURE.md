# Architecture Decision Record — In-Memory Ledger Core

## Context

A transfer from account A to B must move a positive integer amount without duplicating effects under retries, losing funds under concurrency, or deadlocking when requests run in opposite directions.

**Scope:** single JVM; in-memory state; no actual payments or persistence.

## Invariants

1. Each account has a unique, immutable ID and a nonnegative balance.
2. A successful transfer debits exactly the amount credited; no intermediate balance can be externally observed through the supported API.
3. The same successful idempotency key and payload yield the same receipt, with a single debit.
4. A key submitted with a different payload is rejected, rather than silently reusing a receipt.
5. Insufficient funds and numeric overflow never partially apply.
6. Opposite-direction transfer requests acquire locks in the same global account-ID order.

## Implementation and linearization

- A `ConcurrentHashMap` atomically reserves each idempotency key using `putIfAbsent`. The winner performs the transfer. Concurrent duplicates await its `CompletableFuture` rather than run it again.
- An **intent** contains source, destination, and amount. This is compared with the intent of a pre-existing reservation to reject key reuse for another command.
- Distinct accounts each own a `ReentrantLock`. We lock the lexicographically smaller account ID first, regardless of transfer direction. This prevents cyclic lock acquisition.
- Within both locks, validate funds and compute destination balance using `Math.addExact` *before* mutating either balance. Receipt sequencing and balance writes occur inside the critical section.
- A successful receipt is completed and cached in the idempotency map for the lifetime of the ledger. An unsuccessful attempt completes exceptionally and removes its reservation, so a subsequent call can retry.
- `balance(id)` locks that account to observe its state, but reading two balances sequentially is **not** a consistent multi-account snapshot.

## Trade-offs and hazards

| Choice | Benefit | Limitation |
| --- | --- | --- |
| Per-account locking | Allows independent account pairs to proceed concurrently | Contention on hot accounts |
| Ordered lock acquisition | Avoids AB/BA deadlocks | Callers must not bypass the locking protocol |
| In-memory idempotency registry | Clear retry semantics within one process | Memory grows indefinitely; crashes erase receipts |
| Completed futures for duplicates | No duplicate concurrent execution | Waiting duplicate receives a `CompletionException` after another request fails |
| Integer minor units | Avoids floating-point precision errors | Currency scale is not represented; `long` has finite range |
| Monotonic receipt sequence | Unique local order values | Not a durable ledger offset and not a global commit log |

### Important remaining race

A failed request removes its idempotency reservation. A new request can reserve the same key while an older duplicate is still observing the earlier failed future. This **does not duplicate a successful transfer**, but different clients may observe different attempts under the same key following a failure. A production API must specify a durable key-state machine, rejection windows, and retry classification.

### Crash consistency

Balances and receipt cache are in the same process heap and **are lost on restart**. Locks do not substitute for database transactions; this code offers no cross-process or cross-region consistency guarantee.

## Path to a durable, production-inspired design

1. Model accounts, transactions, and idempotency keys in PostgreSQL with durable unique constraints.
2. Transfer inside a database transaction, using row-level locks in canonical order or optimistic version checks.
3. Store a replayable response with a request fingerprint in the same transaction as debits and credits.
4. Define `PENDING`, `COMMITTED`, and `FAILED` states with expiration, reconciliation, and retry contracts.
5. Add ledger journal entries and conservation checks; design a reconciliation procedure.
6. Introduce an outbox and asynchronous event publishing *only when justified by a real boundary*.
7. Define SLOs, request tracing, metrics, an operational runbook, and load tests against a real database.
8. Review tenant isolation, auth, fraud/abuse controls, and data protection before exposing any public API.

## Verification approach

The JUnit suite covers happy paths, duplicate sequential and concurrent requests, conflicting keys, insufficient funds, retry after funding, overflow atomicity, invalid input, and opposing transfer races. These tests help catch regressions; further property-based and model-checking exercises would raise confidence.
