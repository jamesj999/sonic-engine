# CNZ Miniboss Frame 15058 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove the engine-only CNZ miniboss coil overlap at standalone frame
15058 by correcting the earliest ROM-proven parent state-machine cadence error.

**Architecture:** Diagnose and fix the owning `CnzMinibossInstance` routine,
counter, or callback transition. Shared touch response remains unchanged and
temporary trace instrumentation is removed before commit.

**Tech Stack:** Java 21, Maven, JUnit 5, committed comparison-only S3K trace,
S&K-side `sonic3k.asm`.

## Global Constraints

- Do not run LBZ tests.
- Do not hydrate engine state from trace data.
- Do not branch on frame, route, or zone identity.
- Do not mask or delay the coil collision.
- Write and verify a failing behavior test before production changes.

---

### Task 1: Locate the first parent cadence departure

**Files:**
- Temporarily modify as needed:
  `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`
- Inspect:
  `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- Inspect: `docs/skdisasm/sonic3k.asm`

- [ ] Add a temporary comparison-only diagnostic that prints the live parent
      routine, X position, wait counter, and callback beside ROM slot 8
      `object_state` values from arena release through frame 15058.
- [ ] Run the bounded CNZ scenario and record the first mismatching state
      transition.
- [ ] Match that transition to the complete native routine and helper in
      `sonic3k.asm`.
- [ ] Remove the temporary diagnostic after extracting the evidence.

### Task 2: Protect the native handoff with TDD

**Files:**
- Modify the narrowest existing test under
  `src/test/java/com/openggf/game/sonic3k/objects/`
- Modify:
  `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`

- [ ] Add one behavior test that drives the real parent state machine across
      the identified counter/callback boundary and asserts the independently
      derived native routine, position, or callback result.
- [ ] Run only that test and verify it fails on `9b5391450` for the expected
      cadence mismatch.
- [ ] Implement the smallest object-local correction backed by the complete
      disassembly routine.
- [ ] Run the focused test and the complete CNZ miniboss object suite; require
      zero failures.

### Task 3: Verify trace and regression scope

**Files:**
- Modify if frontier moves: `CHANGELOG.md`
- Modify if frontier moves: `docs/status/trace-frontier-log.md`

- [ ] Run the standalone CNZ replay without frontier-only mode and record the
      error count plus first divergence.
- [ ] Run the complete CNZ scenario class and focused rewind coverage guards.
- [ ] Run frontier-only AIZ, MGZ, and CNZ complete-run canaries; do not run
      LBZ.
- [ ] If the frontier moved, record the source-of-truth diagnosis and fresh
      before/after evidence in the changelog and trace-frontier log.
- [ ] Run `git diff --check`, inspect the full diff, stage every task artifact,
      commit with required trailers, and review the committed diff without
      pushing.
