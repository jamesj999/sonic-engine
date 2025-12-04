# Music Test Plan

Context: FM instruments are mostly silent/basic, DAC drums are crunchy, and some tracks appear cross-wired (e.g., Chemical Plant resembling Sky Chase). This plan focuses on tooling to reproduce issues quickly and diagnostics to isolate data path vs. synthesis bugs.

## Goals
- Build a ROM-driven sound-test harness to A/B songs and inspect channel data without loading gameplay.
- Verify SMPS parsing (pointers, voices, key/vol offsets) and DAC sample pipeline (decode, mapping, playback rate).
- Identify and fix the root causes of crunchy DAC output and missing/incorrect instruments.

## Deliverable: Sound-Test Harness (Maven target)
- Add a `soundtest` Maven profile that runs a lightweight entry point (e.g., `uk.co.jamesj999.sonic.audio.debug.SoundTestApp`) via `exec-maven-plugin`.
- Features:
  - Keyboard UI mirroring Sonic 2 sound test: up/down to change track ID, enter/play to (re)start, space to pause/resume, backspace to stop.
  - On-screen overlay with song ID, resolved ROM offset, Z80 base, tempo/dividing timing, active channel count, and per-channel meters (FM 1-6, PSG 1-3, DAC).
  - Toggleable debug logs (per-frame register writes, note events) and a “solo/mute channel” hotkey set.
  - Optional WAV dump of the first N seconds for offline inspection (`target/soundtest-dumps/<id>.wav`).
- Data loading: reuse `AudioManager` + `JOALAudioBackend`, but allow a `--null-audio` flag to run with `NullAudioBackend` for parsing-only tests. Accept `--rom <path>` and `--song <hex>` overrides.

## Diagnostics: SMPS Parsing & Mapping
- Cross-check `SmpsLoader.musicMap` offsets against the ROM music flags table at `0xECF36` and pointer banks (`0xF0000`/`0xF8000`). Add a parser unit test that resolves each ID to a nonzero pointer and fails on mismatch.
- Validate Saxman decompression by asserting the header’s compressed size vs. actual payload length and the decompressed header signature (voice pointer and channel counts should be sane) for a sample set of tracks (CPZ `0x8C`, HTZ `0x86`, MTZ `0x82`).
- Confirm Z80 relocation math in `SmpsSequencer.relocate`: add a test that feeds known Z80 base (`0x1380`) and asserts per-channel pointers land inside the decompressed blob.
- Add a parser sanity log in sound test: decoded header (voice pointer, DAC pointer, FM/PSG key+vol offsets) and first 8 bytes of each track pointer target to spot corruption quickly.

## Diagnostics: DAC Path (Crunchy Drums)
- Verify DAC pointer table read in `SmpsLoader.loadDacData` (base `0xECF7C`, bank `0xE0000`) against SCHG docs; add a small test that checks sample lengths and nonempty decode for IDs `0x81-0x8A`.
- Re-evaluate `DcmDecoder`: confirm nibble order and unsigned-to-signed conversion. Add a utility to emit decoded samples as 8-bit PCM and compare against reference captures; flag if accumulator start or clamp differs.
- Re-derive DAC playback rate: `Ym2612Chip.playDac` currently uses a heuristic `dacStep` from the rate byte. Implement a table-based step size that matches Sonic 2’s driver (rate byte is a divider; consult coordination flags doc) and expose it in debug overlay.
- Inspect DAC mixing: ladder effect simulation may be exaggerating crunch. Add a toggle to bypass ladder quantization in sound test and compare output; if bypass fixes crunch, revise ladder model.

## Diagnostics: FM/PSG Instruments (Beepy output)
- Voice load: ensure 25-byte voices are read for Sonic 2 (TL bytes present). Add a sound-test toggle to dump loaded voice bytes per channel and assert TL ≠ 0 when expected.
- Key/volume offsets: log `E9` and `E6` handling per channel in sound test. Add a unit test that applies offsets to a known voice and asserts TL attenuation stays within 0–0x7F.
- Frequency math: confirm note → FNUM/BLOCK mapping matches SMPS tables (S2 uses note base C-0 at `0x81`). Add a table-based mapper test to catch octave shifts or detune errors.
- Coordination flags: add logging for `E0/E6/E7/E8/E9/EA/EB/F0/F2/F6/F7/F8/F9/F3/F5` when hit; verify tempo/dividing-timing updates take effect mid-playback (possible cause of pattern drift).
- PSG: add a per-channel oscilloscope view (simple waveform graph) in overlay to verify tone/noise periods and volumes.

## Regression Tests to Add
- `SmpsLoaderTest`: round-trip offsets → decompressed size → header fields for a few song IDs; ensure failures surface during CI even without audio output.
- `DcmDecoderTest`: known compressed nibble stream → expected PCM bytes; include sign/offset coverage.
- `SequencerPointerTest`: validate relocate logic and jump/loop/call/return stacks using a tiny handcrafted SMPS blob.
- Optional golden-audio test (skippable if ROM missing): render first 1s of CPZ and assert RMS energy and nonzero variance on FM+DAC channels separately to catch silent instruments.

## Execution Order
1) Build sound-test harness + CLI/overlay + logs/WAV dump.
2) Add parser + DAC + sequencer unit tests (ROM-less where possible).
3) Use sound test to A/B DAC ladder toggle and rate table; fix DAC decode/step if crunch persists.
4) Use voice dump + coordination flag logs to correct instrument loading and tempo/pattern skew.
5) Lock in fixes with golden/regression tests and document known song ID ↔ ROM offset mappings in `docs/MUSIC_IMPLEMENTATION.md`.
