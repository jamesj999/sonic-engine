# Music Test Plan

Context: FM instruments remain flaky, DAC drums vary (kick okay, snare noisy), and some tracks misbehave (loops/tempo/section jumps). The sound-test harness is finished; focus now is on diagnosing and fixing audio issues.

## Goals
- Use the sound-test harness to A/B songs and inspect channel data without gameplay.
- Verify SMPS parsing (pointers, flags, durations, key/vol offsets) and the DAC sample pipeline (decode, mapping, playback rate).
- Fix looping/tempo errors, instrument loading issues, noisy DAC snare, and long PSG sustains.

## Current Findings to Chase
- Looping/tempo/section errors (e.g., 0x93 Super Sonic, 0x94 Hill Top, 0x84 Mystic Cave):
  - `handleFlag` covers only a subset of SMPS flags; unknown flags consume zero params and desync parsing.
  - `scaleDuration` wraps `rawDuration * dividingTiming` with `& 0xFF`; overflow becomes 0 → coerced to 256 ticks, causing slowdowns and stuck notes.
  - Tempo/coordination flags beyond EA/EB are ignored; mid-song tempo changes may be skipped.
- Instruments missing/wrong timbre:
  - FM operator order likely wrong (Sonic 2 voices order ops 1,3,2,4; we load 0,1,2,3). Mis-mapped DT/MUL/TL/EG can mute carriers and alter volumes.
  - Partial 25-byte voices are silently accepted; truncated TL/EG can zero volume.
- DAC snare (0x82) sounds like noise:
  - Rate formula in `Ym2612Chip.playDac` still suspect; verify against DAC.ini timing to avoid aliasing.
  - Mapping now keyed by noteId; sample table at 0xECF7C and map at 0xECF9C read little-endian and look sane.
- Long PSG sustains / volume tied to bad durations:
  - Same duration overflow as above; PSG volumes aren’t reset when parsing desyncs, so stale latched volumes persist.

## Diagnostics: SMPS Parsing & Mapping
- Cross-check `SmpsLoader.musicMap` offsets against the ROM music flags table at `0xECF36` and pointer banks (`0xF0000`/`0xF8000`). Add a parser unit test that resolves each ID to a nonzero pointer and fails on mismatch.
- Validate Saxman decompression by asserting header size vs payload and sane header fields for sample tracks (CPZ `0x8C`, HTZ `0x86`, MTZ `0x82`). Header size parsing now uses max(little, big endian); add regression coverage.
- Confirm Z80 relocation math in `SmpsSequencer.relocate`: test with base `0x1380` that per-channel pointers land inside the decompressed blob.
- Add parser sanity log in sound test: decoded header (voice ptr, DAC ptr, FM/PSG key+vol offsets) and first 8 bytes at each track pointer.
- Implement (or skip with parameter consumption) the missing flags (E1/E2/E4/E5/F1/F4/F5 etc.) so parsing stays aligned; add a tiny SMPS blob test to verify parameter consumption.

## Diagnostics: DAC Path (Crunchy Drums)
- Verify DAC pointer table read in `SmpsLoader.loadDacData` (base `0xECF7C`, bank `0xE0000`) against docs; add a test that checks sample lengths and nonempty decode for IDs `0x81–0x8A`.
- Re-evaluate `DcmDecoder`: confirm nibble order and unsigned-to-signed conversion; add a utility to emit decoded samples as 8-bit PCM for reference comparison.
- Re-derive DAC playback rate: replace heuristic step with a table that matches Sonic 2’s driver; expose rate in debug overlay and validate with the 0x82 snare pitch.
- Inspect DAC mixing: keep ladder-quantization toggle in sound test; if bypass fixes crunch, adjust ladder model.

## Diagnostics: FM/PSG Instruments
- Voice load: ensure 25-byte voices are read for Sonic 2; fix operator order to 1,3,2,4. Add a sound-test toggle to dump loaded voice bytes per channel and assert TL > 0 when expected.
- Key/volume offsets: log `E9` and `E6` handling per channel in sound test. Add a unit test that applies offsets to a known voice and asserts TL attenuation stays within 0–0x7F.
- Frequency math: confirm note → FNUM/BLOCK mapping matches SMPS tables (S2 base C-0 at `0x81`). Add a table-based mapper test to catch octave shifts or detune errors.
- Coordination flags: log `E0/E6/E7/E8/E9/EA/EB/F0/F2/F6/F7/F8/F9/F3/F5` when hit; verify tempo/dividing-timing updates mid-playback.
- PSG: add per-channel oscilloscope view to verify tone/noise periods and volumes.

## Regression Tests to Add
- `SmpsLoaderTest`: offsets → decompressed size → header fields for a few song IDs; fails in CI even without audio output.
- `DcmDecoderTest`: known compressed nibble stream → expected PCM bytes; include sign/offset coverage.
- `SequencerPointerTest`: validate relocate logic and jump/loop/call/return stacks using a tiny handcrafted SMPS blob.
- Duration overflow guard: test that `rawDuration * dividingTiming` clamps (no wrap to 0/256).
- Optional golden-audio test (skippable if ROM missing): render first 1s of CPZ and assert RMS energy and nonzero variance on FM+DAC separately to catch silent instruments.

## Execution Order
1) Add parser + DAC + sequencer unit tests (ROM-less where possible); cover Saxman size and duration clamp.
2) Implement missing flag handling and duration clamp; verify with sound test on 0x93/0x94/0x84.
3) Fix FM voice operator ordering; retest instrument audibility in sound test.
4) Re-derive DAC rate; validate 0x82 snare pitch; iterate ladder toggle.
5) Lock in fixes with golden/regression tests and document song ID ↔ ROM offset mappings in `docs/MUSIC_IMPLEMENTATION.md`.
