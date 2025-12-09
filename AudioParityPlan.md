# Audio Parity Plan (Sonic 2 vs SMPSPlay)

## Timing & Tempo
- Gap: Sequencer fixed ~60Hz, simple accumulator; ignores PAL-speed flag (bank bit 6) and Speed Shoes fast tempos. Files: `src/main/java/uk/co/jamesj999/sonic/audio/smps/SmpsSequencer.java` (processTempoFrame, samplesPerFrame, tempoWeight). Reference data: `docs/SMPS-rips/Sonic The Hedgehog 2/SpeedUpTempos.txt`, PAL flag in `docs/SMPS-rips/Sonic The Hedgehog 2/Pointers.txt`, driver timing `TempoMode=Overflow2` in `docs/SMPS-rips/Sonic The Hedgehog 2/DefDrv.txt`. Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs.h` (TEMPO_OVERFLOW2), `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (tempo handling/DoTempo).
- Fix: Add NTSC/PAL samples-per-frame, honor PAL-disable flag per song, load fast tempo table for speed shoes, implement Overflow2 exactly.

## Command/Flag Coverage
- Gap: Only subset implemented; fades/comms stubs. See `SmpsSequencer.handleFlag`, `handleFadeIn` (stop). Reference flags: `docs/SMPS-rips/Sonic The Hedgehog 2/DefCFlag.txt`. Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps_commands.h` + `smps_commands.c` (coord flag table and handlers), `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (main dispatcher).
- Fix: Implement E2 (SET_COMM), ED/EE variants, real fades (E4/F2) with gradual attenuation, ensure tick-mult (E5/EB) semantics, match F9 SND_OFF.

## Instrument Parsing Order
- Gap: Voices reordered + op2/op3 swapped in `src/main/java/uk/co/jamesj999/sonic/audio/smps/Sonic2SmpsData.java#getVoice`. SMPSPlay uses `InsMode=Hardware` (`DefDrv.txt`). Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (DoInsChange/voice write order), `docs/SMPS-rips/SMPSPlay/src/loader_def.c` (InsMode parsing).
- Fix: Support hardware-order load for Sonic 2; gate swaps/remaps by driver metadata.

## DAC Rates & Playlist
- Gap: Always interpolate; no playlist aliases. Timing constants in `src/main/java/uk/co/jamesj999/sonic/audio/synth/Ym2612Chip.java` (DAC_*). Loader maps only 0x81–0x91 (`Sonic2SmpsLoader.loadDacData`). Reference data: `docs/SMPS-rips/Sonic The Hedgehog 2/DAC.ini`, `DefDrum.txt` aliases. Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/dac.c` (rate/playlist handling), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_drums.c` (DAC drum mapping).
- Fix: Use DAC.ini base rate/divisor, playlist remaps 88–91, make interpolation optional, align pitch math to driver.

## PSG Envelopes, Clock & Noise
- Gap: Envelopes hardcoded (`Sonic2PsgEnvelopes.java`); noise/pan simplified. PSG handling in `SmpsSequencer` PSG functions and `src/main/java/uk/co/jamesj999/sonic/audio/synth/PsgChip.java`. Reference data: `docs/SMPS-rips/Sonic The Hedgehog 2/PSG.lst`; SN76496 clock in SMPSPlay is 3.579545 MHz with tone `f = clock/(32*N)`, which corresponds to `CLOCK_DIV = 8` in our chip; comment currently mismatches. Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (PSG writes/envelope), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs_int.h` (PSG envelope state), SN core init in `docs/SMPS-rips/SMPSPlay/src/Sound.c`.
- Fix: Keep `CLOCK_DIV = 8` to match SMPSPlay pitch; fix comment; load envelopes from list, ensure additive volume semantics, implement noise clock/pan per driver.

## Pitch/Detune & Modulation
- Gap: Simplified pitch wrap (`getPitchSlideFreq`, `applyModulation`). Driver: `DefDrv.txt` (DetuneOctWrap=False, ModAlgo=68k_a) and `DefCFlag.txt`. Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (DoPitchSlide/DoModulation), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs.h` (MODALGO_ definitions).
- Fix: Match 68k_a modulation (DoModulation on DoNote), align detune/pitch-slide wrap to SMPSPlay DoPitchSlide.

## Tempo Dividers & Per-Track Timing
- Gap: Global `dividingTiming`; per-track multipliers not fully matched. Code: `SmpsSequencer.setTrackDividingTiming/updateDividingTiming`. Reference data: `DefDrv.txt` (DefTimingMode=00, LockTimingMode=True). Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (track rate divider logic), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs.h` (timing mode flags).
- Fix: Enforce per-track tick multipliers as driver; lock timing mode per driver settings.

## SFX Coverage & Routing
- Gap: Only RING mapped; single SMPS SFX stream; other SFX fall back to WAV. Code: `JOALAudioBackend.playSfxSmps/playSfx`, `AudioManager.playSfx`. Reference data: `docs/SMPS-rips/Sonic The Hedgehog 2/SFX_Final/*.sfx`, `DefCFlag.txt`. Reference code: `docs/SMPS-rips/SMPSPlay/src/loader_smps.c` (SFX loading/pointers), `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (sound effect channel handling).
- Fix: Map full SFX pointer table (0x0FEE91 per Pointers.txt), allow concurrent SMPS SFX on proper channels, avoid WAV fallback when data exists.

## Bank/Pointer Resolution
- Gap: Hardcoded music map; limited SFX. Code: `Sonic2SmpsLoader.musicMap`, `resolveMusicOffset`, `loadSfx`. Reference data: `docs/SMPS-rips/Sonic The Hedgehog 2/Pointers.txt`, `Z80Drv_0925_Final.bin` offsets. Reference code: `docs/SMPS-rips/SMPSPlay/src/loader_smps.c` (bank flag decoding, pointer resolution), `docs/SMPS-rips/SMPSPlay/src/loader_def.c` (driver definitions informing loader).
- Fix: Parse bank flags for all songs, support 0xF0000/0xF8000 banks and uncompressed flag, build full SFX map.

## Audio Backend Fidelity
- Gap: Custom YM2612/PSG approximate (mono mix, simplified EG/LFO, soft clip, minimal pan); SMPSPlay uses mature cores. Files: `VirtualSynthesizer.java`, `Ym2612Chip.java`, `PsgChip.java`, `JOALAudioBackend.fillBuffer`. Reference code: `docs/SMPS-rips/SMPSPlay/src/Sound.c` (initializes YM2612 and SN76496 cores via `emu/cores`), `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (pan/AMS/FMS writes into cores).
- Fix: Swap in proven YM2612/PSG cores or improve EG/LFO/AMS/FMS scaling, stereo pan law, headroom, master gain.

## Pan/AMS/FMS & Channel Masking
- Gap: Pan/AMS/FMS applied on note-on; no FM6 DAC-off option. Code: `SmpsSequencer.applyFmPanAmsFms`, `JOALAudioBackend.updateSynthesizerConfig`. Reference data: `DefDrv.txt` (FM6DACOff optional). Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (pan/AMS/FMS flag handling), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs.h` (channel mode flags).
- Fix: Respect FM6 DAC-off; ensure pan bits map to stereo output.

## Fade Handling & Master Volume
- Gap: No real fades/master gain. Code: `SmpsSequencer.handleFadeIn/handleFlag`. Reference data: fade params in `DefDrv.txt` (FadeOutSteps/Delay, FadeInSteps/Delay). Reference code: `docs/SMPS-rips/SMPSPlay/src/Engine/smps.c` (fade handling), `docs/SMPS-rips/SMPSPlay/src/Engine/smps_structs_int.h` (fade state).
- Fix: Implement fade envelopes affecting TL/PSG volume per driver; add master gain to prevent clipping.

## Suggested Execution Order
1) Timing/tempo parity (PAL flag, SpeedUpTempos, Overflow2).
2) Full flag handling (fades, comms, tick-mults).
3) Instrument parsing (hardware order) + FM pan/AMS/FMS.
4) Bank/pointer/SFX maps + DAC playlist/rates.
5) PSG envelopes/noise + pitch/mod wrap + confirm `CLOCK_DIV = 8` comment.
6) Synth fidelity upgrades (or swap cores) + stereo/mix headroom.
7) Verify against SMPSPlay: load same `.sm2`/`.sfx`, compare register traces or PCM output.
