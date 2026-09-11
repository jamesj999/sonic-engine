# S2 Trace Regression Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove false Tails failures from solo-Sonic S2 traces and restore the S2 CPU recovery-counter behavior without weakening S3K parity.

**Architecture:** The trace binder will use the recorded sidekick presence bit as the authority for whether CPU state is comparable. A typed `SidekickCpuRules` flag will own the S2/S3K difference in carrying the shared recovery timer into normal CPU state.

**Tech Stack:** Java 17, JUnit 5, Maven, JSON trace fixtures.

## Global Constraints

- Trace data remains comparison-only and never hydrates engine state.
- Do not add zone, route, or frame carve-outs.
- Express game-wide CPU behavior through typed `GameRules`.

---

### Task 1: Sidekick Presence Authority

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceBinder.java`
- Modify: `src/main/java/com/openggf/trace/TraceBinder.java`

**Interfaces:**
- Consumes: `TraceCharacterState.present()`
- Produces: CPU comparisons only for a sidekick recorded as present.

- [ ] Add a binder test where auxiliary CPU state exists but the recorded sidekick is absent.
- [ ] Run the focused test and verify the current binder reports `tails_cpu_present`.
- [ ] Gate CPU comparisons on recorded sidekick presence.
- [ ] Preserve SCZ/WFZ Sonic+Tails session metadata; suppression is represented by `sidekick_present=0`.
- [ ] Rerun focused trace parsing/binder tests.

### Task 2: Render-Entry Counter Reset

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuDespawnParity.java`

**Interfaces:**
- Consumes: current and delayed sprite status plus horizontal and vertical render-boundary state.
- Produces: the one-tick delay only for the native fresh render-entry shape.

- [ ] Add an S2 regression test proving central-height render entry resets immediately.
- [ ] Run it and verify the broad OOZ2 predicate delays the reset.
- [ ] Require proximity to the vertical render boundary as well as the horizontal boundary.
- [ ] Run focused CPU despawn tests.

### Task 3: Fleet Verification and Delivery

**Files:**
- Modify if the frontier moves: `docs/status/trace-frontier-log.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: all S2 `*TraceReplay` tests and the session diff.
- Produces: verified commit pushed from `bugfix/ai-s2-trace-regressions`.

- [ ] Run every S2 trace replay test and classify any remaining failures.
- [ ] Run focused unit tests and a Maven package build.
- [ ] Review the diff, stage only this session's intended files, and satisfy commit trailers.
- [ ] Commit and push the current branch.
