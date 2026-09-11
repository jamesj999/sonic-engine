# Trace Profile Gating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent diagnostic `Debug*` and `*Probe` trace classes from running under the normal `trace-replay` Maven profile unless explicitly requested.

**Architecture:** Keep production trace replay tests opt-in through `-Ptrace-replay`, but separate durable replay tests from diagnostic probes by Maven include/exclude policy and a small guard. This avoids accidental ROM-backed debug work in CI or local trace sweeps.

**Tech Stack:** Maven Surefire, Java 21, JUnit 5.

---

## File Map

- Modify: `pom.xml`
- Modify or add: `src/test/java/com/openggf/tests/architecture/TestBuildToolingGuard.java`
- Modify docs only when adding a durable trace-policy note: `docs/status/trace-frontier-log.md`

## Tasks

### Task 1: Identify current diagnostic classes

- [ ] Run `rg --files src/test/java/com/openggf/tests/trace | rg "(Debug|Probe).*\\.java$"`.
- [ ] Confirm the current list includes diagnostic classes such as `DebugS3kAizReplayWindowProbe.java` and `DebugS1Mz1RingParity.java`.

### Task 2: Tighten the `trace-replay` profile

- [ ] In `pom.xml`, keep durable trace replay includes such as `**/Test*TraceReplay.java`, `**/*TraceReplayTest.java`, and shared replay bases if they are concrete tests.
- [ ] Add Surefire excludes for `**/Debug*.java`, `**/*Debug*.java`, `**/*Probe.java`, and `**/*Probe*.java` inside the `trace-replay` profile.
- [ ] Do not change default-profile trace exclusions; this plan only narrows the trace profile.

### Task 3: Add build-tooling guard coverage

- [ ] Extend `TestBuildToolingGuard` with a source-level assertion that the `trace-replay` profile contains diagnostic excludes.
- [ ] Add a second assertion that the profile does not use only the broad `**/tests/trace/**/*.java` include without diagnostic excludes.
- [ ] Run `mvn "-Dtest=com.openggf.tests.TestBuildToolingGuard" test`.

### Task 4: Verify profile behavior

- [ ] Run `mvn "-Dmse=off" "-Ptrace-replay" "-Dtest=DebugS3kAizReplayWindowProbe" test`.
- [ ] Expected: no diagnostic test is selected by the profile, or Maven reports no matching tests with `-DfailIfNoTests=false` if that flag is used.
- [ ] On machines with the required ROM, run one durable trace replay class with the matching ROM property. On machines without the ROM, do not fake the signal: run the build-tooling guard and record `ROM-backed trace execution skipped: ROM not present` in the final implementation notes.

### Task 5: Commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestBuildToolingGuard" test`.
- [ ] Commit with `test: gate diagnostic trace probes`.
