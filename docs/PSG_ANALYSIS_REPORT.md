# Sonic 2 PSG Audio Analysis Report

## Executive Summary
- PSG envelope handling now mirrors Sonic 2's SMPSPlay behavior: `0x80/0x81` are Hold, `0x82` respects loop targets, `0x83` stops and idles the track, and `0x84` is consumed to stay in sync. The first envelope byte is applied on note start, so attack envelopes no longer skip index 0.
- All 13 PSG envelopes from `docs/SMPS-rips/Sonic The Hedgehog 2/PSG.lst` are embedded in code (no runtime PSG.lst dependency) and loaded for music and SFX. A ROM parity test guards against drift.
- At-rest gating suppresses further PSG volume writes after Hold/Stop, eliminating the background hiss that appeared on held noise. Chemical Plant noise (0x8C) and Spindash Release (0xBC) regain their intended envelope shaping now that envelopes 10–13 are present and index 0 is applied.
- PSG mixer volume matches SMPSPlay (`sn76489.c`): 4-bit attenuation table headed by 4096 with white noise at 0.5x. Loudness differences traced back to envelope handling, not gain scaling.
- Noise that uses the “tone 2 match” mode now latches tone frequency even while in noise mode, so modulation/pitch slides drive the noise pitch like SMPSPlay (affecting 0xBC in particular).

## Findings vs SMPSPlay
- **PSG chip parity (`PsgChip.java` vs `sn76489.c`):** Volume table, bipolar output, tone divider behavior, and white-noise attenuation all match; no mixer discrepancies were found.
- **Envelope flow (`SmpsSequencer.processPsgEnvelope`):**
  - Fixed `0x80` misinterpreted as loop/reset; now Hold/at-rest like the Sonic 2 driver.
  - Implemented loop (`0x82 xx`) and CHGMULT (`0x84 xx`) consumption so scripts advance identically to SMPSPlay.
  - STOP (`0x83`) now silences, marks at-rest, and issues a note-off to halt PSG/noise output.
  - First envelope byte plus channel volume offset now apply on the first tick to avoid attack pops and missing initial noise energy.
  - At-rest state prevents further volume writes, matching SMPSPlay's `PBKFLG_ATREST` behavior and removing residual hiss.
- **Data source:** Runtime uses the embedded 13-envelope table; ROM loading is lenient (no nibble clamping) and a unit test confirms the hardcoded tables match the cartridge data.
- **Regression coverage:** New tests cover hold/initial-step behavior and ROM parity for all 13 envelopes to prevent reintroducing the skip/loop bug or dropping envelopes beyond 9.

## Completed Task List
- [x] Implement Sonic 2 PSG envelope semantics (Hold for `0x80/0x81`, loop targets for `0x82`, STOP + note-off for `0x83`, consume param for `0x84`) and mark tracks at rest.
- [x] Apply the first envelope step on note start so attack envelopes do not skip index 0.
- [x] Embed all 13 PSG envelopes from PSG.lst in code, remove runtime PSG.lst reliance, and relax loader validation.
- [x] Add unit tests for envelope semantics and ROM parity (`TestSmpsSequencer#testPsgEnvelopeHoldAndInitialStepApplied`, `TestSonic2PsgEnvelopesAgainstRom`).
- [x] Confirm PSG mixer parity with SMPSPlay's volume table (white-noise 0.5x attenuation) to rule out gain mismatch as the loudness source.

## Driver Configuration & Timing
- Tempo handling remains aligned with Sonic 2's Overflow2 (0x100) logic.
- `NoteOnPrevent` semantics remain aligned: rests short-circuit articulation, and STOP now idles PSG channels after the stop command.
