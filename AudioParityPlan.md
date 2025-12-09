# Audio Parity Plan (Sonic 2 vs SMPSPlay)
**Instructions:** Aim for accuracy first. Use the SMPSPlay source under `docs/SMPS-rips/SMPSPlay/src` and the Sonic 2 configs/rips under `docs/SMPS-rips/Sonic The Hedgehog 2` as primary references. Derive data from the original ROM where possible; if not available, use the bundled ripped data or hardcode known values. If critical details are still missing, perform an online search to confirm expected behavior before diverging.

Composite plan that preserves the original gap list, adds SMPSPlay code references, and notes new work landed in this branch.

## Timing & Tempo
- Gap: Need full `TEMPO_OVERFLOW2`, NTSC/PAL frame rates, PAL-speedup flag (bank bit 6), Speed Shoes tempos.
- Current: Region enum, speed-shoes table, tempo accumulator added; `samplesPerFrame` set only via `setRegion` (ctor still needs default).
- SMPSPlay refs: `src/Engine/smps.c` (`DoTempo`), `src/Engine/smps_structs.h` (TEMPO_OVERFLOW2), PAL disable flag use in loader.
- Implementation steps (do all):
  1) In `SmpsSequencer` ctor, call `setRegion(Region.NTSC)` so `samplesPerFrame` is non-zero by default.
  2) Match `DoTempo`: `tempoAccumulator += tempoWeight; if >= 0x100 -> tempoAccumulator &= 0xFF; tick()`. No extra ticks.
  3) PAL: use 50Hz frame rate; if PAL-speedup not disabled (`!palSpeedupDisabled`), multiply tempoWeight by exact 60/50 (or the driver’s constant) rather than a guess.
  4) Speed Shoes: derive fast tempos from ROM/plan data; if ROM unavailable, hardcode the table already in code. When `speedShoes` flag is set, override `tempoWeight` with that value (fallback to normal tempo).
  5) Add regression test: NTSC vs PAL vs PAL-disabled vs speed-shoes to assert tick counts over fixed frames.

## Command/Flag Coverage
- Gap: Ensure E2/E4/F2/F9 and tick-mults match driver; fades still “stop”.
- Current: E2/E4/F2/F9 implemented; F9 writes silence; E4 stops track.
- SMPSPlay refs: `src/Engine/smps_commands.c/h`, dispatcher in `src/Engine/smps.c`.
- Implementation steps:
  1) E2: store comm byte; expose getter for tests; add unit test.
  2) E4/F2: implement fade envelopes (TL/volume) honoring driver’s step/delay; stop tracks only after fade completes; unit test for gradual attenuation.
  3) E5/EB: verify per-track vs global tick-mult semantics (64<= values, zero handling) against SMPSPlay; add test that durations scale.
  4) F9: confirm writes to TL and RR for all ops; add test that logs correct registers.
  5) Keep unknown flags skipping parameter bytes consistent with SMPSPlay table lengths.

## Instrument Parsing Order
- Gap: Must use hardware operator order for Sonic 2.
- Current: Swap removed; hardware order retained.
- SMPSPlay refs: `src/Engine/smps_structs.h` (INSMODE_HW), `smps.c` DoInsChange.
- Implementation steps:
  1) Document mapping (Op list HW: 0x00,0x04,0x08,0x0C) and ensure `Sonic2SmpsData` outputs HW order.
  2) Ensure `SmpsSequencer` uses HW slot mapping when writing TL/params.
  3) Add test: load a known voice, assert FM writes hit expected registers with correct TL order.

## DAC Rates & Playlist
- Gap: Align DAC rate math, interpolation toggle, playlist aliases (88–91).
- Current: DAC interpolate toggle added; aliases partially mapped in loader.
- SMPSPlay refs: `src/Engine/dac.c`, `src/Engine/smps_drums.c`.
- Implementation steps:
  1) Prefer deriving rates/aliases from ROM tables (ECF7C/ECF9C); if ROM unavailable, fall back to hardcoded values equivalent to DAC.ini (BaseRate/RateDiv/BaseCycles/LoopCycles/LoopSamples, per-ID rates, aliases 88–91).
  2) Use parsed/derived rates to compute dacStep; expose config to toggle interpolation.
  3) Add test: DAC note produces expected sample count/frequency for a known rate (compare to SMPSPlay output or expected step).

## PSG Envelopes, Clock & Noise
- Gap: Envelopes hardcoded; noise/pan parity not confirmed.
- Current: Clock comment fixed (master/8); envelopes still hardcoded.
- SMPSPlay refs: `src/Engine/smps.c` (PSG writes/envelopes), `src/Engine/smps_structs_int.h` (PSG env state), SN init in `src/Sound.c`.
- Implementation steps:
  1) Prefer deriving envelopes from ROM (if present) or bundled PSG.lst; if external not available, hardcode the PSG.lst values into code matching SMPSPlay ordering.
  2) Ensure envelope values add to track volume (no clamp to 0 unless driver does).
  3) Validate SN76496 tone/noise frequency formula matches `clock/(32*N)`; add test to compare to SMPSPlay for a given period.
  4) Add test: envelope steps advance and hold per 0x80 command.

## Pitch/Detune & Modulation
- Gap: Ensure pitch slide wrap and modulation match 68k_a.
- Current: Pitch slide helper present; modulation implemented.
- SMPSPlay refs: `src/Engine/smps.c` (DoPitchSlide/DoModulation), `smps_structs.h` (MODALGO).
- Implementation steps:
  1) Mirror DoPitchSlide wrap rules (base freq thresholds) from SMPSPlay.
  2) Ensure modulation runs per 68k_a (DoModulation on DoNote).
  3) Add test: slide across octave boundary matches SMPSPlay FNum/block.

## Tempo Dividers & Per-Track Timing
- Gap: Per-track tick multipliers must follow driver rules and locked timing mode.
- Current: Track dividers present.
- SMPSPlay refs: `src/Engine/smps.c` (rate divider logic), `smps_structs.h` (timing flags).
- Implementation steps:
  1) Implement track-level divider exactly (0 => 256 semantics).
  2) Respect locked timing mode from driver definition.
  3) Add test: duration scales when divider changes mid-track.

## SFX Coverage & Routing
- Gap: Full SFX table, proper channel stealing and restoration.
- Current: SFX pointer table loader added; SmpsDriver locks channels but tracks aren’t yet paused/unpaused.
- SMPSPlay refs: `src/loader_smps.c` (SFX load), `src/Engine/smps.c` (priority/override).
- Implementation steps:
  1) On channel lock by SFX, mark corresponding music track overridden and stop its tick/key-ons.
  2) On release, clear override and re-key if needed (respect tie).
  3) Complete SFX ID map from ROM pointer table; if ROM not available, hardcode the table from docs; add test: overlapping SFX doesn’t permanently steal music channel.

## Bank/Pointer Resolution
- Gap: Need full bank/flag decoding for music/SFX.
- Current: Music flags used; SFX table used.
- SMPSPlay refs: `src/loader_smps.c` (bank flag decode), `src/loader_def.c` (driver defs).
- Implementation steps:
  1) Implement flag bits (bank, uncompressed, pointer index) per Pointers.txt.
  2) Prefer deriving offsets from ROM; if ROM unavailable, hardcode known offsets for Sonic 2 final.
  3) Add tests: known IDs resolve to expected ROM offsets for both banks; uncompressed flag honored.

## Audio Backend Fidelity
- Gap: YM2612/PSG emu still approximate vs SMPSPlay cores.
- Current: Synth interface refactored; SmpsDriver mixes multiple sequencers.
- SMPSPlay refs: `src/Sound.c` (core init), `src/Engine/smps.c` (pan/AMS/FMS writes).
- Implementation steps:
  1) Decide: integrate mature cores or implement missing EG/LFO/AMS/FMS parity.
  2) Add master gain/clip guard; implement stereo pan law matching SMPSPlay.
  3) Add test: pan/AMS/FMS writes hit synth as expected; clipping avoided.

## Pan/AMS/FMS & Channel Masking
- Gap: Need FM6 DAC-off option and full pan/AMS/FMS parity.
- Current: Pan applied; channel locking exists.
- SMPSPlay refs: `src/Engine/smps.c` (pan/AMS/FMS), `smps_structs.h` (channel modes).
- Implementation steps:
  1) Add FM6 DAC-off toggle (config/driver flag).
  2) Confirm pan bits write to YM regs per SMPSPlay; add test for pan masks.

## Fade Handling & Master Volume
- Gap: No true fade envelopes or master gain.
- Current: E4 stops track only.
- SMPSPlay refs: `src/Engine/smps.c` (fade logic), `smps_structs_int.h` (fade state).
- Implementation steps:
  1) Implement fade state machine (steps/delay) affecting TL/PSG volumes.
  2) Add master gain/clipping guard in mixer.
  3) Add test: fade reduces output over expected ticks.

## Suggested Execution Order
1) Timing/tempo safety (init samplesPerFrame) + PAL/Speed Shoes parity.
2) Channel override/pause/resume for SFX.
3) Flags/fades completion.
4) SFX map/priority + DAC aliases/rates.
5) PSG envelope/noise loading + pitch/mod verification.
6) Backend fidelity (cores or improved synth) + master gain/pan law.
7) Regression tests (tempo, flags, channel overrides, TL wrap) vs SMPSPlay traces.
