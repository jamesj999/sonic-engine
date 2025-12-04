# Music Test Plan

Context: FM instruments remain flaky, DAC drums vary (kick okay, snare noisy), and several tracks misbehave (loops/tempo/section jumps). Focus is on diagnosis and fixes; the sound-test harness is already complete.

## Goals
- Use the sound-test harness to A/B songs and inspect channel data without gameplay.
- Verify SMPS parsing (pointers, flags, durations, key/vol offsets) and the DAC sample pipeline (decode, mapping, playback rate).
- Fix looping/tempo errors, instrument loading issues, noisy DAC snare, and long PSG sustains.

## Current Findings to Chase
- Looping/tempo/section errors (0x84 Mystic Cave, 0x8F Oil Ocean, 0x99 Invincibility):
  - Saxman blobs are little-endian only; the loader now forces LE and clamps size to ROM bounds.
  - Coordination flags consume the right param lengths and implement call/return; per-track tick multiplier (E5) is wired but its effect still needs hardware validation.
  - Dividing timing is per-track; EB updates all tracks. Tempo drops/desyncs still appear in-game (e.g., 0x8F tempo falling to 9/2) and need tracing.
- Instruments missing/wrong timbre:
  - FM operator order fixed to 1,3,2,4; still need to confirm TL application for short (19-byte) voices and mid-track EF loads.
  - Instruments still silent/misloaded at runtime; inspect EF usage and voice pointer math.
- DAC path:
  - Table/map reads are LE; DAC plays but some tracks shut DAC off (0x8F, 0x99). Check sequencer writes that could clear YM 0x2B or hit F2 prematurely.
  - Snare (0x82) still noisy; playback rate/ladder likely off.
- PSG issues:
  - PSG intros for 0x84/0x8F/0x82/0x8E start mid-song; likely pointer/loop handling or flag side-effects.
  - Occasional long sustain/volume spikes align with duration scaling; verify note fill (E8) and EC PSG volume override handling.
- Tests:
  - SSG-EG repeat test now passes after envelope clamp and higher output gain; behaviour is approximate and should be refined later.

## Diagnostics: SMPS Parsing & Mapping
- Cross-check `SmpsLoader.musicMap` offsets against the ROM music flags table at `0xECF36` and pointer banks (`0xF0000`/`0xF8000`). Add a parser unit test that resolves each ID to a nonzero pointer and fails on mismatch.
- Validate Saxman decompression by asserting header size vs payload and sane header fields for sample tracks (CPZ `0x8C`, HTZ `0x86`, MTZ `0x82`). Header size parsing assumes little-endian; big-endian is fallback only if LE is invalid.
- Confirm Z80 relocation math in `SmpsSequencer.relocate`: test with base `0x1380` that per-channel pointers land inside the decompressed blob.
- Add parser sanity log in the sound test: decoded header (voice ptr, DAC ptr, FM/PSG key+vol offsets) and first 8 bytes at each track pointer.
- Implement/verify remaining flags: E5 behaviour, E1/E2 detune placeholders, and ensure F6/F8/F7/F9 flows don’t prematurely end tracks.

## Diagnostics: DAC Path (Crunchy Drums)
- Verify DAC pointer table read in `SmpsLoader.loadDacData` (base `0xECF7C`, bank `0xE0000`) against docs; add a test that checks sample lengths and nonempty decode for IDs `0x81`–`0x8A`.
- Re-evaluate `DcmDecoder`: confirm nibble order and unsigned-to-signed conversion; add a utility to emit decoded samples as 8-bit PCM for reference comparison.
- Re-derive DAC playback rate: replace heuristic step with a table that matches Sonic 2’s driver; expose rate in debug overlay and validate with the 0x82 snare pitch.
- Inspect DAC mixing: keep ladder-quantization toggle in sound test; if bypass fixes crunch, adjust ladder model.

## Diagnostics: FM/PSG Instruments
- Voice load: ensure 25-byte voices are read for Sonic 2; confirm operator order 1,3,2,4 is used for all reg writes. Add a sound-test toggle to dump loaded voice bytes per channel and assert TL > 0 when expected.
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
2) Nail remaining flag handling (E5 behaviour, tempo/dividing timing changes) and verify with sound test on 0x84/0x8F/0x99.
3) Fix FM/PSG instrument loading issues (TL application, EF loads) and re-test audibility.
4) Re-derive DAC rate; validate 0x82 snare pitch; iterate ladder toggle.
5) Lock in fixes with regression tests and document song ID ↔ ROM offset mappings in `docs/MUSIC_IMPLEMENTATION.md`.
