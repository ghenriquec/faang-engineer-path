# Ledger Core

**A focused Java 21 lab for atomic transfers, idempotency and concurrency.**

This is an **executable learning artifact**, not a bank, production financial service, or distributed ledger. It intentionally starts with an in-memory model so concurrency and failure semantics can be tested independently of a database or message broker.

## Guarantees demonstrated inside one JVM

- Amounts are expressed in `long` minor units (for example, cents), never floating-point.
- Successful transfers atomically change two account balances under ordered locks.
- Reusing an idempotency key with the **same** payload returns the first receipt; a conflicting payload is rejected.
- Concurrent transfers in opposite directions cannot deadlock due to deterministic account lock ordering.
- Insufficient funds or destination overflow does not mutate either balance.
- Failure releases the idempotency reservation to allow a later retry.

## Run tests

Prerequisites: **JDK 21** and Maven.

```bash
mvn -f labs/ledger-core/pom.xml --batch-mode verify
```

This command runs nine JUnit tests, including multi-threaded duplicate submission and opposite-direction transfer cases. CI executes the same command on supported changes. The tests demonstrate the specific invariants asserted; they are not a formal proof of every possible interleaving.

## Code map

```text
labs/ledger-core/
├── pom.xml
├── README.md
├── ARCHITECTURE.md
└── src/
    ├── main/java/dev/ghenriquec/ledger/Ledger.java
    └── test/java/dev/ghenriquec/ledger/LedgerTest.java
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for linearization points, safety properties, bottlenecks, and the evolution path toward a durable service.

## Out of scope, intentionally

**There is no persistence, distributed idempotency, authentication, transfer authorization, event journal, FX, cross-currency conversion, HTTP API, observability, or production deployment.** A process crash loses all in-memory balances and receipts. Do not use this lab with real money or personal financial data.
