# Software Engineering Labs & Roadmap

**Practical Java engineering exercises, architecture decisions, and a transparent learning backlog.**

This repository combines **implemented, testable labs** with a personal study roadmap. I use small, focused systems to explore failure handling, consistency, concurrency, and engineering trade-offs before adding infrastructure.

> A working lab is evidence of the particular behavior tested; it is **not** evidence of production-scale experience, security certification, or a deployed distributed system.

## Implemented labs

| Lab | Language | Demonstrated concepts | Evidence |
| --- | --- | --- | --- |
| **[Ledger Core](labs/ledger-core/README.md)** | Java 21 | In-memory atomic transfers, idempotency, ordered locks, contention and retry semantics | [JUnit tests](labs/ledger-core/src/test/java/dev/ghenriquec/ledger/LedgerTest.java) · [ADR](labs/ledger-core/ARCHITECTURE.md) · [CI](.github/workflows/ledger-core.yml) |
| **[PostgreSQL Ledger](labs/postgres-ledger/README.md)** | Java 21, JDBC, PostgreSQL | Database transactions, durable idempotency, row locks, rollback and concurrent contention | [Integration tests](labs/postgres-ledger/src/test/java/dev/ghenriquec/ledger/sql/PostgresLedgerTest.java) · [ADR](labs/postgres-ledger/ARCHITECTURE.md) · [CI](.github/workflows/postgres-ledger.yml) |

### Run Ledger Core

```bash
mvn -f labs/ledger-core/pom.xml --batch-mode verify
```

**Two distinct implementation boundaries:** Ledger Core is **in-memory and single-process**, while PostgreSQL Ledger uses durable relational transactions and integration tests against a disposable database. Neither is an HTTP API or a financial product. Their respective [in-memory](labs/ledger-core/ARCHITECTURE.md) and [database-backed](labs/postgres-ledger/ARCHITECTURE.md) architecture notes explain guarantees, failure modes and trade-offs.

## Engineering tracks

| Area | Practical goal | Progress |
| --- | --- | --- |
| Java and concurrency | Demonstrate atomic transfers, idempotency, lock ordering, and race tests | **Implemented:** Ledger Core lab |
| Persistence and consistency | Use transaction boundaries, row locking and replayable idempotency responses | **Implemented as a PostgreSQL lab**; production controls remain out of scope |
| Distributed messaging | Study outbox, at-least-once delivery, deduplication and retry backoff | Planned |
| Reliability | Define failure modes, SLOs, runbooks and observability | Planned |
| System design | Publish case studies with constraints, alternatives and consequences | In progress: [Ledger Core ADR](labs/ledger-core/ARCHITECTURE.md) |
| Cloud and delivery | Reproducible infrastructure and production-inspired CI/CD practices | Planned |
| Algorithms and interviews | Explain complexity, test edge cases and evaluate trade-offs | Planned |
| Open source | Submit useful issues and code contributions to external projects | Planned |

## How an engineering lab earns a place here

1. **Specify invariants and failure modes** before implementation.
2. **Make trade-offs explicit:** the simplest correct design is the starting point.
3. **Prove behavior** with executable tests and repeatable commands.
4. **Document non-goals and limits,** especially around durability and security.
5. **Use CI as a quality gate** and fix failed checks rather than suppress them.
6. **Identify next experiments** without presenting them as shipped capabilities.

## Other public work

- [People Registration](https://github.com/ghenriquec/cadastro-pessoas) — Java / Spring Boot and React CRUD project.
- [Universal Domain Rules Discovery](https://github.com/ghenriquec/universal-domain-rules-discover-skill) — structured domain discovery, validation, and test traceability.
- [QA Sentinel](https://github.com/ghenriquec/qa-sentinel-skill) — portable, read-only QA audit protocol.

## Next lab backlog

- [x] Compare the in-memory model to PostgreSQL transactions with durable idempotency keys.
- [ ] Add controlled failure injection and a repeatable concurrency stress-test harness.
- [ ] Evaluate optimistic locking against ordered pessimistic locks.
- [ ] Produce an architecture case study on event delivery and reconciliation.
- [ ] Establish a consistent benchmark methodology instead of publishing unsupported performance claims.

## Author

**Guilherme Henrique** · [GitHub](https://github.com/ghenriquec) · [LinkedIn](https://www.linkedin.com/in/guihenriquecoelho/)

## License

See [LICENSE](LICENSE).
