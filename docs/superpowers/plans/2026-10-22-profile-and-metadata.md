# Profile and Repository Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the GitHub profile route reviewers to verified backend evidence without inflated claims or decorative noise.

**Architecture:** Treat the profile as an index. Link each capability to a source file, test, ADR, or passing workflow from the completed ledger and Spring work; adjust repository descriptions and topics only where the GitHub connection permits the change.

**Tech Stack:** GitHub profile README, repository metadata, Markdown link validation

**Spec:** `docs/superpowers/specs/2026-10-22-backend-portfolio-overhaul-design.md`

## Global Constraints

- Preserve accurate employment and contact details already supplied by the owner.
- Add no employer metrics, production-scale numbers, certifications, or Staff/Principal title claim.
- Use no badge wall, generated contribution graph, repository deletion, archive, rename, or visibility change.
- Feature only behavior verified on the default branch.
- Commit directly to `main`; create no pull request.

## Review Focus

- A link to a file that moved during implementation must resolve on `main`; Task 1 checks every GitHub URL.
- A green workflow link must point to the active workflow rather than one historical run; Task 1 checks workflow paths.
- The profile must distinguish labs from deployed systems; Task 1 reviews every evidence row for scope language.
- Metadata topics must correspond to code present in the repository; Task 2 compares topics to dependencies and files.
- A cached public profile may lag the repository README; Task 3 verifies both the repository content and the public page when available.

---

### Task 1: Evidence-led profile README

**Files:**
- Modify in `ghenriquec/ghenriquec`: `README.md`

**Interfaces:**
- Consumes: final default-branch paths and workflow names from the ledger and `cadastro-pessoas` plans.
- Produces: a compact profile with an engineering focus, three primary evidence rows, explicit lab scope, technical focus, and contact links.

- [ ] **Step 1: Fetch final default-branch files and workflow states before drafting**

- [ ] **Step 2: Rewrite the evidence table around reliable ledger, People Registration, and domain-rule validation**

Each row links to implementation, tests, architecture decisions, and CI where those artifacts exist. Keep clinic UI and QA Sentinel as secondary work.

- [ ] **Step 3: Check every Markdown URL with HTTP HEAD or GitHub fetch**

Expected: each link resolves; workflow links identify active workflow files.

- [ ] **Step 4: Compare every technical claim with the linked file and remove unsupported wording**

- [ ] **Step 5: Commit**

Commit message: `docs: route profile to verified backend evidence`

### Task 2: Repository descriptions and topics

**Files:**
- No repository content files unless the connection cannot update metadata and a README needs a matching clarification.

**Interfaces:**
- Produces metadata for `faang-engineer-path`, `cadastro-pessoas`, `qa-sentinel-skill`, `universal-domain-rules-discover-skill`, and `clinic-management-system`.

- [ ] **Step 1: Read each repository's final language, dependencies, README, description, and topics**

- [ ] **Step 2: Set concise descriptions and evidence-backed topics through the connected GitHub account where supported**

Use `java`, `spring-boot`, `postgresql`, `concurrency`, `idempotency`, and `testcontainers` for `faang-engineer-path`; use only implemented subsets for other repositories.

- [ ] **Step 3: Read metadata back and compare it with the requested values**

Expected: exact description and topic matches, or a recorded connector limitation with no workaround that changes repository content unnecessarily.

### Task 3: Final account verification

**Files:**
- No file changes expected.

**Interfaces:**
- Produces: exact commit SHAs, test counts, workflow conclusions, metadata results, and a list of remaining limits.

- [ ] **Step 1: Fetch default-branch heads for every changed repository**

- [ ] **Step 2: Fetch workflow runs tied to those heads and inspect failures before reporting**

- [ ] **Step 3: Open the public profile and verify visible links and wording**

- [ ] **Step 4: Report exact changes without promising later work**

