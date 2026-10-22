# Backend Portfolio Overhaul Design

**Date:** 2026-10-22  
**Owner:** Guilherme Henrique  
**Target:** Senior specialist, Staff, and Principal backend engineering roles  
**Status:** Approved direction; implementation planning pending

## Objective

The portfolio will give reviewers a short path to inspect working Java code, concurrency controls, durable persistence, automated tests, CI results, and written engineering decisions. Each claim in the profile must point to code, a test, an ADR, or a workflow run.

The work will preserve every repository and its history. It will not change visibility, archive projects, claim unverified scale, or deploy an application.

## Audit summary

The account contains 52 accessible repositories, including 34 public repositories. Most public projects from 2021 through 2023 are coursework, bootcamp exercises, or small front-end applications. Updating each repository would create a large amount of shallow activity without improving the backend portfolio.

The current technical evidence sits in these repositories:

- `faang-engineer-path`: Java 21 in-memory and PostgreSQL ledger labs with concurrency tests and green CI.
- `cadastro-pessoas`: Spring Boot and PostgreSQL CRUD application with unit tests. A legacy Azure workflow still deploys on pushes to `main`.
- `universal-domain-rules-discover-skill`: Python validation, schemas, behavioral tests, and green CI.
- `qa-sentinel-skill`: a QA audit protocol with validation and green CI.
- `clinic-management-system`: a Next.js interface with type checking, linting, build validation, and green CI.
- `ghenriquec`: the public profile README that links to this evidence.

The remaining public repositories will stay intact. The profile will identify them as earlier study work through its selection of featured projects, without rewriting their history or presenting coursework as production systems.

## Selected approach

The implementation will deepen one Java system, harden one existing Spring application, then update the profile. This sequence gives reviewers evidence of system design and maintenance work while keeping the scope testable.

### Phase 1: Reliable ledger service

`faang-engineer-path` will remain the engineering-lab repository. A new `labs/reliable-ledger-service` lab will integrate the useful ideas from the two existing ledgers without deleting or rewriting them.

The service will use Java 21, Spring Boot, PostgreSQL, Maven, Flyway, JUnit 5, and Testcontainers. It will expose a small HTTP API for account creation, transfers, balance queries, and reconciliation checks. The API exists to make boundaries and failure responses inspectable; the project will not present itself as a payment product.

The code will separate four responsibilities:

1. The domain model defines accounts, transfers, journal entries, idempotency semantics, and invariants without Spring or JDBC types.
2. The application layer coordinates commands through ports and returns typed outcomes.
3. The PostgreSQL adapter owns transactions, row locking, migrations, and durable replay records.
4. The HTTP adapter validates requests, maps outcomes to stable problem responses, and applies request-size and identifier limits.

The database transaction for a successful transfer will:

1. reserve an idempotency key and bind it to a request fingerprint;
2. lock both account rows in canonical identifier order;
3. verify funds and overflow boundaries;
4. write balanced debit and credit journal entries;
5. update account projections;
6. store the replayable response;
7. add an outbox record;
8. commit all writes together.

Database constraints will reject negative balances, zero or negative transfer amounts, self-transfers, duplicate idempotency keys, and unbalanced journal groups where PostgreSQL can enforce the rule without application state. The application will run a reconciliation query that compares account projections with journal totals and reports mismatches without repairing data.

### Phase 2: Failure and concurrency evidence

Tests will cover observable contracts rather than framework wiring. The suite will include:

- sequential and concurrent replay of one idempotency key;
- one key reused with a different payload;
- opposing transfers without deadlock;
- concurrent withdrawals that cannot overdraw an account;
- rollback after insufficient funds, overflow, and injected failure before commit;
- balanced journal entries and reconciliation mismatch detection;
- outbox creation in the same transaction;
- malformed HTTP input, unknown resources, and bounded identifiers;
- restart-style replay through a fresh application client against the same database.

Testcontainers will create an isolated PostgreSQL instance for integration tests. Tests must not accept an arbitrary database URL and must not issue destructive SQL against a user-supplied database.

CI will run compilation, unit tests, integration tests, and package verification on pushes to `main` and pull requests. The workflow will use read-only repository permissions, pinned major action versions, Maven dependency caching, concurrency cancellation, and timeouts. No workflow will deploy the lab.

### Phase 3: Security and operational design

The lab will document its security boundary and avoid claims that require infrastructure it does not have. The implementation will include:

- strict validation for identifiers and monetary amounts;
- parameterized SQL through the persistence adapter;
- credentials supplied through environment variables;
- generic client error bodies with no SQL or credential leakage;
- minimal GitHub Actions permissions;
- a sample database role that grants only the required table and sequence privileges;
- deterministic request fingerprints for idempotency conflict detection.

An ADR will record the choice of ordered pessimistic locks over optimistic retries for this workload. Another ADR will explain double-entry journal and projection reconciliation. A third ADR will cover the transactional outbox boundary and state that the lab does not run a message broker or promise delivery.

The operational notes will define proposed service-level indicators, diagnostic fields, and a failure runbook. They will label every unmeasured target as a design proposal. The code will not publish unsupported latency, throughput, availability, or scale numbers.

## `cadastro-pessoas` hardening

The first commit to `cadastro-pessoas` will disable automatic Azure deployment by changing the deployment workflow to manual dispatch only. That commit will include `[skip ci]` because the existing workflow reacts to pushes on `main`. It will also fix the backend working directory and update obsolete action versions, but it will not invoke the deployment job.

Later commits will add Flyway migrations, isolated test configuration, HTTP-level integration tests, and clearer package boundaries where the existing application permits incremental changes. Tests will protect CPF validation, person/contact ownership, missing resources, malformed requests, and persistence behavior. The work will avoid a repository-wide rewrite that hides the original project behind generated structure.

## Profile and repository metadata

The profile README will lead with verified specialties and link to the reliable ledger service, its tests, ADRs, and CI. It will retain accurate employment and contact information already supplied by the account owner. It will describe lab limitations in the same section as technical guarantees.

Repository descriptions and topics will use concrete terms such as `java`, `spring-boot`, `postgresql`, `concurrency`, `idempotency`, and `testcontainers` only after the repository contains the corresponding implementation. The overhaul will not add decorative badge walls, contribution-graph manipulation, unsupported job titles, or claims about employer impact.

## Delivery sequence

1. Implement and verify the reliable ledger service through test-first increments.
2. Commit the lab to `main` through the GitHub integration and watch its workflows.
3. Neutralize the automatic Azure deployment before changing `cadastro-pessoas`.
4. Add and verify the selected Spring Boot hardening increments.
5. Update the profile README and repository metadata after the linked evidence exists.
6. Recheck the default branches, commit SHAs, workflow conclusions, and profile links.

Each commit will contain one reviewable behavior or safety change. No pull request will be created.

## Completion criteria

The overhaul is complete when:

- a clean checkout can run each documented build command;
- the reliable ledger service passes its unit and PostgreSQL integration suites;
- tests exercise the documented concurrency, rollback, journal, outbox, and reconciliation contracts;
- GitHub Actions on the final `main` commit concludes successfully;
- `cadastro-pessoas` cannot deploy from a normal push to `main`;
- the profile links resolve to files and workflows on the default branch;
- README and ADR claims match implemented behavior;
- the final report lists exact commits, test results, workflow results, and unresolved limits.

## Explicit non-goals

- Production deployment or production credentials.
- A public payment, banking, or healthcare service.
- Kubernetes, service meshes, Kafka, or cloud resources added for appearance.
- Benchmark claims without a repeatable benchmark and recorded environment.
- Rewriting historical study repositories to imply senior-level authorship at the time they were created.
- Repository deletion, archival, visibility changes, or fabricated employment history.
