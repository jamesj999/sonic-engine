# Package Cycle Ratchets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace broad existing-cycle ignores with explicit frozen cycle baselines that can shrink one package cluster at a time.

**Architecture:** Keep ArchUnit as the dependency enforcement layer, but stop treating current cycles as an undifferentiated allowlist. Each known cycle cluster gets a named baseline, owner note, and decay target in architecture docs.

**Tech Stack:** Java 21, ArchUnit, JUnit 5, Maven.

---

## File Map

- Modify: `src/test/java/com/openggf/tests/architecture/TestArchUnitRules.java`
- Modify: `src/test/resources/archunit/frozen/stored.rules` or add rule-specific freeze files if the project convention supports them
- Modify: `docs/architecture/archunit-exceptions.md`

## Tasks

### Task 1: Inventory current cycles

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchUnitRules" test` to confirm current frozen rules pass.
- [ ] Temporarily remove or narrow the broad cycle ignore locally and run the package-cycle test to list current cycles.
- [ ] Do not commit the temporary removal unless the rule is replaced in the same commit.

### Task 2: Define named cycle clusters

- [ ] Group cycles by architectural reason, such as runtime/session bootstrap, level/object collision, rendering/art, and game-specific donation.
- [ ] Add docs entries for each cluster with current count, owner package, intended direction, and first decay target.

### Task 3: Replace broad ignore with frozen baselines

- [ ] In `TestArchUnitRules`, replace the broad current-slice ignore with one or more named `FreezingArchRule` cycle checks.
- [ ] Keep truly permanent composition roots as explicit allowlists, not hidden inside broad cycle ignores.
- [ ] Ensure new packages cannot join an existing frozen cycle without changing the frozen baseline.

### Task 4: Add a docs-count guard

- [ ] Add the cycle-cluster documentation assertions directly in this plan's implementation: every named cycle cluster in docs must have a matching test method or freeze-store entry.
- [ ] After the ArchUnit baseline documentation ratchet lands, migrate the duplicate helper into the shared documentation-count helper and keep this plan's cycle-cluster assertions as the caller.

### Task 5: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalReviewGuard" test`.
- [ ] Commit with `test: ratchet package cycle baselines`.
