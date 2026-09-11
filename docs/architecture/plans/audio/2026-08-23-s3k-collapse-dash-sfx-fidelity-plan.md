# S3K Collapse and Dash SFX Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make S3K Collapse `$59` and spindash-release Dash `$B6` match the shipped locked-on ROM from request through final channel shutdown.

**Architecture:** Use the pinned native GPGX diagnostic core to establish each sound's ordered per-service chip-write and lifecycle oracle. Compare ROM-loaded OpenGGF playback against that evidence, then correct only the smallest shared SMPS interpreter or track-lifecycle semantic that accounts for the divergence; do not add per-sound runtime exceptions or tune waveform constants.

**Tech Stack:** Java 21, JUnit Jupiter, OpenGGF SMPS runtime, native BizHawk/GPGX headless diagnostics, Sonic 3&K locked-on ROM.

**Spec:** Approved conversational design from 2026-08-23: trace `$59` and `$B6`, test their complete lifecycles, fix only source-proved shared behavior, and preserve sibling/cross-game behavior.

## Global Constraints

- The ROM and `docs/skdisasm/Sound/Z80 Sound Driver.asm` with `fix_sndbugs=0` are authoritative.
- Diagnostic captures are comparison evidence only and never runtime inputs.
- No SFX-ID, game-name, zone, frame, or route carve-out may enter shared runtime code.
- Every production change follows strict RED/GREEN TDD.
- Maven must report JDK 21 before every recorded Maven gate.
- The main checkout's unrelated S1 special-stage changes remain untouched.

---

### Task 1: Establish native Collapse and Dash lifecycle evidence

**Files:**
- Modify only if required: `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`
- Modify only if required: `tools/bizhawk-headless/tests/GpgxZ80AudioCapabilityTests.cs`
- Create: `docs/architecture/research/audio/2026-08-23-s3k-collapse-dash-native-audit.md`
- Create only if bounded evidence is needed by tests: `docs/architecture/research/audio/s3k-collapse-dash-lifecycle-v1.json`

**Interfaces:**
- Consumes: locked-on S3K ROM, existing diagnostic observer ABI, and ROM SFX IDs `$59`/`$B6`.
- Produces: deterministic request-to-terminal evidence with ordered YM/PSG writes, service ordinals, active-track count, and exact terminal boundary.

- [x] Run the existing observer/capability harness against the verified ROM and record the unmodified baseline.
- [x] Add the smallest diagnostic-only capture needed to distinguish duration, loop, modulation, PSG attenuation/noise, and terminal silence.
- [x] Capture twice into agent-managed scratch and require byte-identical output, zero observer fault/overflow, and matching source IDs.
- [x] Cite the exact SFX bytecode and Z80 driver routines that explain every retained lifecycle transition.
- [x] Run native observer tests and verify any tracked oracle can be regenerated exactly.

### Task 2: Reproduce the divergences in OpenGGF with ROM-backed tests

**Files:**
- Create: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`

**Interfaces:**
- Consumes: Task 1 lifecycle evidence and `Sonic3kSmpsLoader.loadSfx(int)`.
- Produces: focused failures naming the first divergent driver update and field/write.

- [x] Add a Collapse test that runs `$59` through terminal completion and compares track activity, loop count, PSG writes, modulation, and silence boundary to native evidence.
- [x] Run it and verify RED at the first real divergence rather than at setup or fixture parsing.
- [x] Add a Dash test that runs `$B6` through terminal completion and compares its FM/PSG durations, modulation, envelope, and shutdown.
- [x] Run it and verify RED at the first real divergence.
- [x] Add sibling and cross-game controls for the affected interpreter and contention paths.

### Task 3: Implement the source-derived shared fix

**Files:**
- Modify only the proved owner, expected candidates:
  - `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
  - `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
  - `src/main/java/com/openggf/audio/smps/SmpsSequencerSnapshot.java`
- Modify tests from Task 2.

**Interfaces:**
- Consumes: first-divergence evidence from Task 2 and source behavior from Task 1.
- Produces: data-driven SMPS semantics shared by all applicable tracks, including snapshot/rollback fidelity for any changed state.

- [x] State one root-cause hypothesis tied to the first divergent source instruction/routine.
- [x] Implement the minimum semantic correction; if the first hypothesis fails, revert it before testing a new one.
- [x] Run the two RED tests and require GREEN without weakening assertions.
- [x] Confirm that no persistent track/snapshot schema changed.
- [x] Run S3K sibling, S1, and S2 controls to prove no cross-game semantic regression.

### Task 4: Verify, document, and deliver for listening

**Files:**
- Modify: `CHANGELOG.md`
- Create: `docs/architecture/validation/audio/2026-08-23-s3k-collapse-dash-sfx-validation.md`

**Interfaces:**
- Consumes: corrected implementation and deterministic tests.
- Produces: a clean local branch/JAR for human listening before integration.

- [x] Run `mvn -v` and the focused S3K ROM-backed suite.
- [x] Run affected audio snapshot/rewind, S1, and S2 regression suites.
- [x] Compare an identical JDK21/all-ROM full suite against an isolated exact-parent baseline; no base-passing test may newly fail.
- [x] Package exact clean HEAD, record JAR SHA-256 and embedded commit in the handoff, and run ZIP integrity validation.
- [x] Commit with all seven policy trailers; do not merge or push until the listening gate passes.
- [ ] Listen to Collapse from first onset through its final tail and to low/high-charge Dash release, including after another SFX; only then integrate according to `AGENTS.md`.
