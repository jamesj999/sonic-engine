# S2/S3K FM Voice Write Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce the shipped S2 and S3K FM voice-loading register streams without changing the corrected YM2612 hardware mapping.

**Architecture:** A typed `FmVoiceWriteProfile` in `SmpsSequencerConfig` selects the exact driver-owned register traversal. `SmpsSequencer` emits raw writes for ROM-driven voices, while each data loader preserves its game's raw voice bytes and the YM2612 remains format-agnostic.

**Tech Stack:** Java 21, JUnit Jupiter, Maven, Sonic 2/Sonic 3&K disassemblies, existing chip-write observer.

## Global Constraints

- Treat the shipped `FixBugs = 0` / `fix_sndbugs = 0` driver paths as authoritative.
- Do not change YM2612 raw-register slot mapping, key-bit mapping, algorithm routing, or chip port ordering.
- Cover music plus local, bank-shared, and global SFX voice sources.
- Keep runtime assets ROM-backed; disassemblies supply research evidence only.
- Work only on `bugfix/ai-s1-audio-parity-frontier`; do not merge or push before human testing.

---

### Task 1: Lock the driver write contracts with failing tests

**Files:**
- Create: `src/test/java/com/openggf/audio/smps/TestSmpsFmVoiceWriteProfiles.java`
- Modify: `src/test/java/com/openggf/tests/TestSonic3kVoiceData.java`

**Interfaces:**
- Consumes: existing `ChipWriteObserver`, `SmpsSequencer`, S2/S3K data sources.
- Produces: literal expected YM write streams for S2 and S3K.

- [x] Add an S2 music fixture whose five operator groups and TL group contain four distinct bytes. Assert B0, parameter, B4, and TL order, with no synthetic `0x28` or `0x90..0x9C` writes.
- [x] Add an S2 local-SFX fixture using the same distinct vector and assert the identical profile.
- [x] Add S3K music, local-SFX, and global-voice fixtures. Assert B4 precedes B0 and each source byte follows the literal `30,38,34,3C` family.
- [x] Assert S3K `getVoice()` returns the raw 25 ROM bytes, so a loader-side middle swap fails visibly.
- [x] Run `mvn -Dmse=off -Dtest=com.openggf.audio.smps.TestSmpsFmVoiceWriteProfiles,com.openggf.tests.TestSonic3kVoiceData test` and verify failures identify the old middle-slot permutation/key-off behavior.

### Task 2: Add typed profiles and exact runtime writers

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/Sonic2SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`

**Interfaces:**
- Produces: `SmpsSequencerConfig.FmVoiceWriteProfile` and `getFmVoiceWriteProfile()`.
- Consumes: raw 25-byte voice blobs and existing `Synthesizer.writeFm()`.

- [x] Add enum values `S1_68K`, `S2_Z80`, and `S3K_Z80`; require each game config to select one explicitly.
- [x] Extract profile dispatch in `refreshInstrument()` without altering note, contention, or snapshot state.
- [x] Preserve the existing S1 writer unchanged behind `S1_68K`.
- [x] Implement S2's literal `B0 -> grouped 30/34/38/3C -> B4 -> 40/44/48/4C` sequence.
- [x] Implement S3K's literal `B4 -> B0 -> grouped 30/38/34/3C -> 40/48/44/4C` sequence and retain explicit nonzero SSG-EG restoration afterward.
- [x] Make initial and later TL refreshes use the same profile mapping.
- [x] Run the Task 1 tests and verify they pass.

### Task 3: Preserve raw S3K voice data across every source

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kSmpsData.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kSfxData.java`
- Modify: relevant S3K presentation/source tests if their expectations describe the obsolete normalized representation.

**Interfaces:**
- Produces: raw 25-byte ROM voices from local, bank-shared, and global lookup paths.
- Consumes: `S3K_Z80` profile in `SmpsSequencer`.

- [x] Remove the per-group middle-byte swap from both S3K copy helpers and correct their format documentation.
- [x] Run Task 1 plus existing S3K voice-resolution and presentation snapshot tests.
- [x] Confirm no production code reads voice bytes from `docs/` or embeds fixture-derived assets.

### Task 4: Regression verification and delivery commit

**Files:**
- Modify: `CHANGELOG.md`
- Modify: this plan only to mark completed checkboxes if useful during execution.

**Interfaces:**
- Consumes: all preceding production and test changes.
- Produces: one reviewable branch commit with exact verification evidence.

- [x] Add a changelog entry describing corrected S2/S3K FM operator routing and exact driver write ordering.
- [x] Run focused JDK21 tests for the new profiles, YM2612 GPGX parity, chip observer, S2 sequencer/instrument parsing, S3K voice/coordination, and audio snapshots.
- [x] Discover and hash the S2 REV01 and locked-on S3K ROMs; run relevant ROM-backed audio smoke tests when available.
- [x] Run `git diff --check` and inspect the complete diff for accidental S1 or chip-core changes.
- [x] Commit with required project trailers. Do not merge, push, or remove the worktree.

## Verification record

- TDD RED: the new six-test profile suite failed on the generic helper's
  synthetic key-off/interleaved writes and the S3K loader's middle-byte swap;
  dedicated follow-up RED cases exposed S3K SSG-EG traversal and S2's
  `FixDriverBugs=0` high-bit TL behavior.
- Fresh focused plus ROM-backed JDK 21 suite: 101 tests, 0 failures, 0 errors,
  0 skips.
- Full three-ROM JDK 21 suite: 14,581 tests, 36 failures, 11 errors, 33 skips.
  The failure count is unchanged from the established branch baseline (36
  failures, 14 errors); no audio-profile, YM2612, S2 ROM-audio, or S3K
  voice-resolution test failed.
- ROM identities: S2 REV01 SHA-1
  `8bca5dcef1af3e00098666fd892dc1c2a76333f9`; locked-on S3K SHA-1
  `cfbf98c36c776677290a872547ac47c53d2761d6`.
