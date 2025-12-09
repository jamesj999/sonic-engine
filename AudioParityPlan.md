# Audio Parity Plan (Sonic 2 vs SMPSPlay)

Composite plan that preserves the original gap list, adds SMPSPlay code references, and notes new work landed in this branch.

## Timing & Tempo
- Gap: Need full `TEMPO_OVERFLOW2`, NTSC/PAL frame rates, PAL-speedup flag (bank bit 6), Speed Shoes tempos.
- Current: Region enum, speed-shoes table, tempo accumulator added; `samplesPerFrame` set only via `setRegion` (ctor still needs default).
- SMPSPlay refs: `src/Engine/smps.c` (`DoTempo`), `src/Engine/smps_structs.h` (TEMPO_OVERFLOW2), PAL disable flag use in loader.
- Actions: Init `samplesPerFrame` in ctor (NTSC), verify PAL multiplier vs `DoTempo`, honor per-song PAL-disable flag, load SpeedUpTempos from data file instead of hardcode.

## Command/Flag Coverage
- Gap: Ensure E2/E4/F2/F9 and tick-mults match driver; fades still “stop”.
- Current: E2/E4/F2/F9 implemented; F9 writes silence; E4 stops track.
- SMPSPlay refs: `src/Engine/smps_commands.c/h`, dispatcher in `src/Engine/smps.c`.
- Actions: Implement real fades (E4/F2) envelopes; confirm tick-mult semantics (E5/EB) and SND_OFF side effects.

## Instrument Parsing Order
- Gap: Must use hardware operator order for Sonic 2.
- Current: Swap removed; hardware order retained.
- SMPSPlay refs: `src/Engine/smps_structs.h` (INSMODE_HW), `smps.c` DoInsChange.
- Actions: Keep hardware order; document op slot mapping; add regression to prevent reintroducing swap.

## DAC Rates & Playlist
- Gap: Align DAC rate math, interpolation toggle, playlist aliases (88–91).
- Current: DAC interpolate toggle added; aliases partially mapped in loader.
- SMPSPlay refs: `src/Engine/dac.c`, `src/Engine/smps_drums.c`.
- Actions: Parse DAC.ini rates/aliases, expose interpolate config, verify pitch math vs driver.

## PSG Envelopes, Clock & Noise
- Gap: Envelopes hardcoded; noise/pan parity not confirmed.
- Current: Clock comment fixed (master/8); envelopes still hardcoded.
- SMPSPlay refs: `src/Engine/smps.c` (PSG writes/envelopes), `src/Engine/smps_structs_int.h` (PSG env state), SN init in `src/Sound.c`.
- Actions: Load PSG.lst data, ensure additive envelope semantics, validate noise clock/pan against SN core.

## Pitch/Detune & Modulation
- Gap: Ensure pitch slide wrap and modulation match 68k_a.
- Current: Pitch slide helper present; modulation implemented.
- SMPSPlay refs: `src/Engine/smps.c` (DoPitchSlide/DoModulation), `smps_structs.h` (MODALGO).
- Actions: Cross-check wrap/clamp logic with SMPSPlay; add tests.

## Tempo Dividers & Per-Track Timing
- Gap: Per-track tick multipliers must follow driver rules and locked timing mode.
- Current: Track dividers present.
- SMPSPlay refs: `src/Engine/smps.c` (rate divider logic), `smps_structs.h` (timing flags).
- Actions: Verify against driver behavior; add coverage.

## SFX Coverage & Routing
- Gap: Full SFX table, proper channel stealing and restoration.
- Current: SFX pointer table loader added; SmpsDriver locks channels but tracks aren’t yet paused/unpaused.
- SMPSPlay refs: `src/loader_smps.c` (SFX load), `src/Engine/smps.c` (priority/override).
- Actions: Implement track override notifications, resume/key-on after release, map remaining SFX IDs.

## Bank/Pointer Resolution
- Gap: Need full bank/flag decoding for music/SFX.
- Current: Music flags used; SFX table used.
- SMPSPlay refs: `src/loader_smps.c` (bank flag decode), `src/loader_def.c` (driver defs).
- Actions: Verify uncompressed flag, bank selection, pointer base; add tests.

## Audio Backend Fidelity
- Gap: YM2612/PSG emu still approximate vs SMPSPlay cores.
- Current: Synth interface refactored; SmpsDriver mixes multiple sequencers.
- SMPSPlay refs: `src/Sound.c` (core init), `src/Engine/smps.c` (pan/AMS/FMS writes).
- Actions: Either swap to mature cores or improve envelopes/LFO/pan law/headroom; add master gain.

## Pan/AMS/FMS & Channel Masking
- Gap: Need FM6 DAC-off option and full pan/AMS/FMS parity.
- Current: Pan applied; channel locking exists.
- SMPSPlay refs: `src/Engine/smps.c` (pan/AMS/FMS), `smps_structs.h` (channel modes).
- Actions: Respect FM6 DAC-off option; confirm pan bits/stereo mix.

## Fade Handling & Master Volume
- Gap: No true fade envelopes or master gain.
- Current: E4 stops track only.
- SMPSPlay refs: `src/Engine/smps.c` (fade logic), `smps_structs_int.h` (fade state).
- Actions: Implement fade-in/out envelopes (TL/PSG), add master gain/clipping guard.

## Suggested Execution Order
1) Timing/tempo safety (init samplesPerFrame) + PAL/Speed Shoes parity.
2) Channel override/pause/resume for SFX.
3) Flags/fades completion.
4) SFX map/priority + DAC aliases/rates.
5) PSG envelope/noise loading + pitch/mod verification.
6) Backend fidelity (cores or improved synth) + master gain/pan law.
7) Regression tests (tempo, flags, channel overrides, TL wrap) vs SMPSPlay traces.
