# Agent Test Isolation Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the session/LWJGL isolation contract explicit and mechanically guarded for both Codex and Claude agents.

**Architecture:** Keep isolation in the existing `TestSessionCoordinator` and Maven Surefire configuration. Add machine-readable isolation metadata to the session manifest and start/end markers, then make the coordinator self-test and `TestBuildToolingGuard` verify the runtime and documentation contract. Update `AGENTS.md` and `CLAUDE.md` together so agent instructions remain byte-identical.

**Tech Stack:** Java 21 source-launch coordinator, Maven Surefire, JUnit 5, POSIX/PowerShell wrappers, Markdown policy documentation.

**Spec:** `docs/architecture/designs/2026-08-23-test-session-isolation-design.md`

## Global Constraints

- Preserve all unrelated user changes in the main workspace.
- Use `tools/testing/test-session.sh` or `tools/testing/test-session.ps1` for certifying builds and tests.
- Keep `AGENTS.md` and `CLAUDE.md` synchronized and byte-identical.
- Keep LWJGL extraction per Surefire fork under the coordinator-owned session temp root.
- Do not claim test success without fresh verification evidence.

---

### Task 1: Add failing isolation-contract tests

**Files:**
- Modify: `tools/testing/TestSessionCoordinatorSelfTest.java`
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

- [x] Add self-test assertions for the isolation fields in the manifest, start marker, end marker, and child environment.
- [x] Add a tooling-guard assertion that `AGENTS.md` and `CLAUDE.md` explicitly identify Codex and Claude, require the session wrappers, and describe per-fork LWJGL extraction.
- [x] Run the focused tests and confirm they fail because the new metadata and wording were absent.

### Task 2: Expose the isolation contract from the coordinator

**Files:**
- Modify: `tools/testing/TestSessionCoordinator.java`

- [x] Add stable session isolation names and values to the coordinator-owned properties and child environment.
- [x] Add the session temp root and LWJGL extraction template to the manifest and human-readable start/end markers.
- [x] Preserve existing path quoting, lease, source-identity, and report behavior.
- [x] Run the coordinator self-test and confirm the new assertions pass.

### Task 3: Document the agent workflow and keep the Maven contract honest

**Files:**
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `docs/architecture/designs/2026-08-23-test-session-isolation-design.md`

- [x] Add a mandatory Codex/Claude test-session contract covering wrapper use, session-owned temp roots, per-fork LWJGL extraction, parallel worktree behavior, and non-certifying raw Maven runs.
- [x] Document the new marker/manifest fields as the evidence agents must report.
- [x] Keep the mirrored agent documents byte-identical.

### Task 4: Verify the complete change

- [x] Run `tools/testing/TestSessionCoordinatorSelfTest.java` through its existing self-test path.
- [x] Run the focused `TestBuildToolingGuard` test.
- [x] Run the session process harness.
- [x] Run the relevant Maven guard profile and record any pre-existing failures separately.
- [x] Review the diff and confirm the main workspace still contains only the user’s existing S1 changes.
