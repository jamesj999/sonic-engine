# Optional Disassembly References Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve GitHub-visible pinned Sonic Retro references without making the disassemblies part of OpenGGF's required checkout, build, test, or runtime graph, and record candidates for later extraction into OpenGGF-org projects.

**Architecture:** Keep the existing `.gitmodules` entries and gitlinks, but make initialization explicitly opt-in. A structural guard owns the documentation and CI boundary. A point-in-time architecture audit ranks extraction candidates without moving code.

**Tech Stack:** Git submodules, GitHub Actions YAML, Java 21/JUnit 5, Markdown, Maven.

**Spec:** `docs/architecture/designs/2026-08-29-optional-disassembly-references.md`

## Global Constraints

- The disassemblies are development references only.
- Normal clone, Maven build, ordinary tests, CI, release, and runtime must not require initialized submodules.
- Runtime assets must continue to come only from user-supplied ROMs.
- `AGENTS.md` and `CLAUDE.md` must remain identical.
- This task inventories extraction candidates but does not move any tool.

---

### Task 1: Guard the optional-reference boundary

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

**Interfaces:**
- Consumes: existing `.gitmodules`, contributor guidance, and GitHub Actions workflows.
- Produces: expanded JUnit method
  `canonicalDisassembliesStayOptionalAndTrackableWhileLocalReferencesStayIgnored()`.

- [x] **Step 1: Write the failing test**

Add a source-policy test that requires the default developer clone to omit
`--recurse-submodules`, requires an explicit optional `git submodule update --init`
instruction, and rejects recursive submodule checkout in `.github/workflows/*.yml`.

- [x] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -Dmse=off -Dtest=TestBuildToolingGuard#canonicalDisassembliesStayOptionalAndTrackableWhileLocalReferencesStayIgnored test -B
```

Expected: FAIL because `docs/guide/contributing/dev-setup.md` currently makes
`--recurse-submodules` part of the default clone command.

- [x] **Step 3: Keep the test isolated from existing guard baselines**

The new method must read only the setup/guidance/workflow files it owns. It must not
modify the existing supported-documentation inventory, where another session is
repairing the retired `docs/guide/PLAN.md` path.

### Task 2: Make initialization explicitly optional

**Files:**
- Modify: `docs/guide/contributing/dev-setup.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: the existing pinned gitlinks and `.gitmodules` URLs.
- Produces: an ordinary default clone path and a separate opt-in research command.

- [x] **Step 1: Change the default clone command**

Use `git clone https://github.com/OpenGGF/OpenGGF.git` in the default setup block.

- [x] **Step 2: Add the optional research step**

State that engine builds, tests, and runtime do not require the disassemblies. Give
`git submodule update --init` only to contributors doing disassembly-backed research.

- [x] **Step 3: Keep agent guidance mirrored**

Apply identical wording to `AGENTS.md` and `CLAUDE.md`.

- [x] **Step 4: Run the focused test to verify GREEN**

Run the Task 1 Maven command. Expected: PASS with one executed test.

### Task 3: Audit potential OpenGGF-org subprojects

**Files:**
- Create: `docs/architecture/audits/2026-08-29-org-project-extraction-candidates.md`

**Interfaces:**
- Consumes: repository ownership, dependency, fixture, workflow, and tooling evidence.
- Produces: ranked extraction recommendations with scope, coupling, migration order,
  and explicit keep-in-repository decisions.

- [x] **Step 1: Inventory standalone surfaces**

Cover `tools/bizhawk-headless`, `tools/bizhawk`, `tools/retro`, `tools/traces`,
`com.openggf.tools.disasm`, audio parity tooling, trace fixtures, capture tooling,
runtime decompression, hooks, and agent skills.

- [x] **Step 2: Score boundaries**

For each candidate record independence, external users, engine coupling, release
cadence, test-data ownership, and extraction prerequisites.

- [x] **Step 3: Recommend an order**

Separate “extract first”, “prepare then extract”, “revisit only with a second
consumer”, and “keep inside OpenGGF”.

### Task 4: Verify and integrate

**Files:**
- Verify all files above.

**Interfaces:**
- Consumes: completed documentation, guard, and audit.
- Produces: verified `develop` integration with no loss of unrelated workspace edits.

- [x] **Step 1: Run focused verification**

Run the new guard method, `git diff --check`, and `cmp -s AGENTS.md CLAUDE.md`.

- [ ] **Step 2: Run full regression suites**

Run the ordinary ROM-backed suite with absolute S1/S2/S3K ROM paths and then
`mvn -Dmse=off -Pguards test -B`. Compare exact failures with the updated baseline.

- [ ] **Step 3: Review and commit**

Run repository policy hooks, inspect the complete diff, and commit with all required
documentation trailers.

- [ ] **Step 4: Integrate without overwriting active main-workspace edits**

Fetch origin, reconcile the latest committed `develop`, merge into the main workspace,
reapply or preserve any overlapping uncommitted files, verify the merged tree, push only
`develop`, and remove the task worktree and local branch after successful delivery.
