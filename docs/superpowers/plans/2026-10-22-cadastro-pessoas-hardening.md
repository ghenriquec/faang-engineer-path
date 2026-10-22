# Cadastro Pessoas Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop automatic production deployment and turn the Spring Boot project into a reproducible Java 17 application with versioned PostgreSQL schema and HTTP-level tests.

**Architecture:** Preserve the controllers and service structure while adding database migrations, isolated integration tests, explicit update semantics, and safe configuration. Make deployment a manual action whose build uses the real `backend` module.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Data JPA, PostgreSQL, Flyway, Maven, JUnit 5, Testcontainers 2.0.5, GitHub Actions

**Spec:** `docs/superpowers/specs/2026-10-22-backend-portfolio-overhaul-design.md` in `ghenriquec/faang-engineer-path`

## Global Constraints

- The first remote commit must neutralize push-triggered Azure deployment and include `[skip ci]`.
- Do not run `workflow_dispatch`, use Azure credentials, or deploy an artifact.
- Preserve the React application and public API paths.
- Upgrade Spring Boot from 3.1.2 to 3.5.16 without moving to Java 21.
- Replace Hibernate schema mutation with Flyway migrations and `ddl-auto=validate`.
- Use Testcontainers for persistence tests; use no developer or production database.
- Commit directly to `main` through the GitHub integration; create no pull request.

## Review Focus

- A normal push to `main` must not start the Azure workflow; Task 1 verifies the trigger set through GitHub after commit.
- Updating a missing person must return 404 instead of creating a row; Task 3 tests the HTTP behavior.
- A person update that omits contacts must not erase contacts by accident; Task 3 pins replace semantics with an explicit contacts field.
- Duplicate CPF and duplicate contact email must return 409 without leaking SQL; Task 2 tests both database constraints and response mapping.
- Integration tests must refuse external database configuration; Task 2 boots PostgreSQL only through Testcontainers properties.

---

### Task 1: Make Azure deployment manual-only

**Files:**
- Modify: `.github/workflows/main_elotech.yml`

**Interfaces:**
- Produces: a workflow with only `workflow_dispatch`, `permissions: contents: read`, `backend/pom.xml` Maven cache, `mvn -f backend/pom.xml package`, artifact actions v4, and an unchanged Azure app/secret reference.

- [ ] **Step 1: Replace the `push` trigger with manual dispatch and update action versions and backend paths**

- [ ] **Step 2: Run `git diff --check` and inspect the parsed workflow trigger**

Expected: no `push` key exists under `on`; build and artifact paths point into `backend`.

- [ ] **Step 3: Commit directly to `main` with skip-ci protection**

Commit message: `ci: make Azure deployment manual only [skip ci]`

- [ ] **Step 4: Fetch workflow runs after the commit**

Expected: no Azure workflow run exists for the commit SHA. The workflow file on `main` exposes manual dispatch only.

### Task 2: Upgrade dependencies and introduce Flyway schema ownership

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/db/migration/V1__people_and_contacts.sql`
- Create: `backend/src/test/java/com/elotech/support/PostgresIntegrationTest.java`
- Create: `backend/src/test/java/com/elotech/PersistenceContractTest.java`

**Interfaces:**
- Produces: PostgreSQL tables `pessoas` and `contatos`, unique CPF and email constraints, contact ownership FK with cascade deletion, and Spring configuration with `ddl-auto=validate` and disabled SQL logging.

- [ ] **Step 1: Upgrade Boot to 3.5.16 and add Flyway plus Testcontainers test dependencies**

- [ ] **Step 2: Write failing persistence tests for schema creation, duplicate CPF, duplicate email, ownership, and cascade deletion**

```java
Pessoa saved = pessoaRepository.saveAndFlush(validPessoa("11144477735"));
assertThrows(DataIntegrityViolationException.class,
        () -> pessoaRepository.saveAndFlush(validPessoa("11144477735")));
pessoaRepository.deleteById(saved.getId());
assertEquals(0, contatoRepository.count());
```

- [ ] **Step 3: Run tests through the existing Maven wrapper and confirm RED**

Run: `docker run --rm -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "$PWD":/workspace -w /workspace -v /var/run/docker.sock:/var/run/docker.sock maven:3.9.11-eclipse-temurin-17 mvn -f backend/pom.xml -Dtest=PersistenceContractTest test`
Expected: FAIL because Flyway migration and test database support are absent.

- [ ] **Step 4: Add V1 and safe runtime settings**

Runtime datasource values come from `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; no password or production URL enters the repository.

- [ ] **Step 5: Run targeted and full backend tests**

Run: targeted test, then the Docker-backed Maven command with `verify`.
Expected: PASS against a disposable PostgreSQL container.

- [ ] **Step 6: Commit**

Commit message: `build: add versioned PostgreSQL schema and integration tests`

### Task 3: Define create and update semantics at the HTTP boundary

**Files:**
- Modify: `backend/src/main/java/com/elotech/Controller/PessoaController.java`
- Modify: `backend/src/main/java/com/elotech/Service/PessoaService.java`
- Modify: `backend/src/main/java/com/elotech/dto/PessoaDTO.java`
- Modify: `backend/src/main/java/com/elotech/Exceptions/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/elotech/PessoaApiIntegrationTest.java`
- Modify: existing service tests when signatures change.

**Interfaces:**
- Produces: `PessoaService.create(PessoaDTO)`, `PessoaService.update(long,PessoaDTO)`, and HTTP problem responses for 400, 404, and 409.
- Update contract: the payload includes the full contacts collection; update replaces that collection after validating ownership and never upserts a missing person.

- [ ] **Step 1: Write failing MockMvc integration tests against PostgreSQL**

Cover 201 create, 400 invalid CPF/future birth date, 404 get/update/delete, 200 full replacement update, contact ownership, 409 duplicate CPF, 409 duplicate email, and 204 delete with contact cascade.

```java
mockMvc.perform(put("/api/pessoas/404")
        .contentType(APPLICATION_JSON).content(validPersonJson()))
    .andExpect(status().isNotFound());
mockMvc.perform(post("/api/pessoas")
        .contentType(APPLICATION_JSON).content(personWithInvalidCpfJson()))
    .andExpect(status().isBadRequest())
    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
```

- [ ] **Step 2: Run `PessoaApiIntegrationTest` and confirm RED on missing-resource update and contact persistence**

- [ ] **Step 3: Implement separate create/update service methods and map database conflicts without SQL details**

- [ ] **Step 4: Run targeted and full backend suites**

- [ ] **Step 5: Commit**

Commit message: `fix: enforce people and contact HTTP invariants`

### Task 4: CI and documentation

**Files:**
- Modify: `.github/workflows/java-tests.yml`
- Modify: `README.md`
- Create: `backend/compose.yaml`

**Interfaces:**
- Produces: a read-only CI job with Java 17, backend Maven cache, 15-minute timeout, and `verify`; README commands reproduce PostgreSQL startup and the full test suite.

- [ ] **Step 1: Update CI to run `verify` for backend, migration, and workflow changes**

- [ ] **Step 2: Document schema ownership, update semantics, environment variables, tests, and manual-only deployment**

- [ ] **Step 3: Execute every README build and test command locally**

Expected: clean build and test exit codes; no Azure action runs.

- [ ] **Step 4: Commit through the GitHub integration and wait for the Java workflow**

Expected: the Java workflow concludes successfully on the final SHA; the Azure workflow has no run for that SHA.

