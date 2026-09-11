# ArchUnit Baseline Documentation Ratchet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep documented ArchUnit debt counts mechanically aligned with frozen baseline files so exception docs do not drift from enforced architecture state.

**Architecture:** Treat `docs/architecture/archunit-exceptions.md` as an audited view of `src/test/resources/archunit/frozen/stored.rules`. Add a test that extracts the documented counts and compares them against the frozen store for the rules that publish counts.

**Tech Stack:** Java 21, JUnit 5, ArchUnit freeze-store text files.

---

## File Map

- Modify: `docs/architecture/archunit-exceptions.md`
- Modify or add: `src/test/java/com/openggf/tests/architecture/TestArchitecturalReviewGuard.java`
- Read-only source: `src/test/resources/archunit/frozen/stored.rules`

## Tasks

### Task 1: Correct the known drift

- [ ] Update the shared-layer/game-specific dependency count in `docs/architecture/archunit-exceptions.md` from `64` to the current frozen value `81`.
- [ ] Confirm the rule label in the docs matches the ArchUnit method name `shared_layers_do_not_depend_on_game_specific_packages`.

### Task 2: Add mechanical documentation validation

- [ ] Add a test helper in `TestArchitecturalReviewGuard` that reads `docs/architecture/archunit-exceptions.md` and `src/test/resources/archunit/frozen/stored.rules`.
- [ ] Parse documented entries with a stable convention such as `` `rule_name`: N ``. If the existing docs do not use this format, first normalize the relevant section to that format.
- [ ] Parse the frozen store by locating each rule id and counting stored violation lines for that rule.
- [ ] Assert each documented count equals the frozen count.

### Task 3: Make future ratchets explicit

- [ ] Add a short docs note: when a frozen baseline shrinks or grows intentionally, update the docs count in the same commit.
- [ ] Add the relevant test name to the docs note so contributors know which guard will fail.

### Task 4: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchitecturalReviewGuard,com.openggf.tests.TestArchUnitRules" test`.
- [ ] Commit with `test: guard architecture baseline documentation`.

