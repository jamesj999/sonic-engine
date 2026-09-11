# SMPS SFX Takeover Onset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent FM SFX construction from corrupting live music and make Sonic 1 SFX takeover follow the shipped register-visible sequence.

**Architecture:** Make SFX construction state-only while leaving music startup unchanged. Establish SFX ownership before the first service write, then gate the legacy synthetic FM reset out of the direct Sonic 1 profile while retaining other profiles unchanged.

**Tech Stack:** Java 21, JUnit Jupiter, Maven, ROM-backed Sonic 1 SMPS loader, existing chip-write observer and snapshot APIs.

## Global Constraints

- Use JDK 21.
- Runtime SMPS bytes come only from the supplied ROM.
- Sonic 1 behavior follows the shipped `FixBugs = 0` driver.
- Do not change YM2612 operator/slot ordering in this work.
- Do not merge or push before human audio testing.

---

### Task 1: Prove and remove constructor-time chip mutation

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Create: `src/test/java/com/openggf/audio/smps/TestSmpsSfxConstructionPurity.java`

**Interfaces:**
- Consumes: `SmpsSequencer(AbstractSmpsData, DacData, Synthesizer, MusicRestoreSink, SmpsSequencerConfig)` and `ChipWriteObserver`.
- Produces: SFX construction that initializes logical state but emits no YM2612/PSG writes; music construction and explicit first-service behavior remain unchanged.

- [x] Write a real S1 ROM-backed or faithful in-memory SFX regression asserting zero chip writes and an unchanged synth snapshot across construction.
- [x] Run the focused test and verify RED from the current `$2B`, voice, and pan writes.
- [x] Split voice selection from `refreshInstrument` so `initSfxTracks` stores voice 0 without writing hardware; suppress the shared constructor's DAC-enable write for SFX only.
- [x] Run construction, music-start, snapshot, and chip-observer tests and verify GREEN.

### Task 2: Make Sonic 1 FM takeover register-authentic

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java`
- Create: `src/test/java/com/openggf/audio/driver/TestS1SfxTakeoverOrder.java`

**Interfaces:**
- Consumes: the direct-68k-driver profile and resolved SFX contention boundary.
- Produces: S1 acquisition without `forceSilenceChannel` or a synthetic leading `$28` write; other profiles retain current behavior.

- [x] Write a failing exact-order regression for an inherited FM5 state and `$C1`: first service begins at `B1=3C`, contains one instrument upload, then `$28=05`, frequency, `$28=F5`.
- [x] Add a failing regression that a non-direct profile still uses its existing takeover policy.
- [x] Implement the smallest typed direct-driver gate around legacy `silenceFmChannel`.
- [x] Run exact-order, contention, restoration, same-ID, signpost/chip-core, and snapshot tests and verify GREEN.

### Task 3: Focused and feature-level verification

**Files:**
- Modify: `CHANGELOG.md`
- Update: this plan's checkboxes and the existing S1 audio research result if observed hashes or conclusions change.

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: reviewable evidence for human audio testing without merge or push.

- [x] Run the explicit focused JDK 21 audio suite covering construction, driver, sequencer, chip, snapshots, gameplay timeline, and architecture guards.
- [x] Run the ROM-backed `$C1` diagnostic and confirm there are no pre-service chip writes.
- [x] Run `git diff --check` and inspect every changed file.
- [x] Record exact commands/results, commit with required policy trailers, and leave the development worktree clean.

## Verification record

- Focused JDK 21 suite: 123 tests, 0 failures, 0 errors, 0 skips.
- Full JDK 21 three-ROM suite: 14,550 tests, 36 failures, 14 errors, 33 skips.
  The exact 50 failure/error identities match the established branch baseline; the five
  additional passing tests are this change's new regressions.
- ROM-backed `$C1`: construction emitted zero writes; onset matched the complete expected
  FM5 register sequence through `$28=$F5`.
