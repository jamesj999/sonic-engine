# Sound driver RE: gap analysis

**Date:** 2026-08-30
**Branch:** `feature/ai-sdre-gaps` (from `feature/ai-sound-driver-re`, with the six map
lanes merged: `feature/ai-sdre-engine-map` `b4b8d34c0`, `-map-s1` `b4d2a9af9`,
`-map-s2` `dc50b9367`, `-map-s3k` `1e5b006ee`, `-map-oracle` `f2a4c2558`,
`-map-claims` `b86f9ab60`)
**Kind:** design (gap synthesis and lane plan for the next phases of the sound-driver
reverse-engineering workflow)
**Inputs:** the six maps below. Nothing here re-derives ROM behaviour; every ROM statement
cites a map section, and every engine statement cites the engine map or a source line read
for this synthesis (`SmpsSequencer.java` at the merged head).

| Key | Document |
|---|---|
| EM | `docs/architecture/audits/audio/2026-08-30-smps-engine-architecture-map.md` |
| S1 | `docs/architecture/research/audio/2026-08-30-s1-sound-driver-routine-map.md` |
| S2 | `docs/architecture/research/audio/2026-08-30-s2-sound-driver-routine-map.md` |
| S3K | `docs/architecture/research/audio/2026-08-30-s3k-sound-driver-routine-map.md` |
| OR | `docs/architecture/audits/audio/2026-08-30-audio-oracle-tooling-map.md` |
| CD | `docs/architecture/audits/audio/2026-08-30-smps-behaviour-claims-digest.md` |

**Source rule.** Sources-closed: no SMPSPlay, libvgm, GPGX sound code, the reverted
`feature/ai-smps-transaction-parity` branch, or third-party SMPS write-ups were opened.
Engine code was read only for "engine today". Where a map left a behaviour as an open
question it stays an open question here.

**Stance.** The current architecture (`SmpsSequencer` + `SmpsDriver` + per-game
`SmpsSequencerConfig`/`GameAudioProfile`/`CoordFlagHandler`, EM §1) is kept. Every gap
below is expressed as an adaptation point on that architecture first; the two places where
an adaptation would be dishonest are named as such in §1.4.

---

## 0. Verdict in one paragraph

The engine models the *same state* as the three ROM drivers for tracks, tempo, voices,
envelopes, modulation, fades and channel ownership (EM §2 maps every ROM track field to a
`Track` field, and the S1 invocation-level oracle is a byte-identical `MATCH` over 14,690
ticks, OR "Engine comparison"). It models a *different cadence* in four places that are
observable in the YM/PSG write stream and would desync a per-invocation oracle for S2 and
S3K on the first tempo-delay frame: (1) S2/S3K delay frames skip the whole track service in
the engine but only pre-increment `DurationTimeout` in the ROM; (2) the sequencer frame is a
sample-domain period phase-free of V-blank; (3) PAL is a tempo multiplier, not a
double-update; (4) the S3K speed-up countdown is decremented once per frame where the ROM
tail decrements it at least twice. It models a *different decomposition* in four more
places that are observable at request/admission granularity: the sound queue, the global
priority latch, pause, and the 1-up save. None of these needs a rewrite of the track model;
they need a driver-invocation boundary that is frame-locked and fires on every ROM update,
and a small set of ROM-state fields (`queue0..2`, `sndPrio`, `pauseFlag`) owned by
`SmpsDriver`. The oracle side is the larger gap: S2 and S3K have no reference capture path
of any kind, and the one working S1 path is broken at the wrapper (OR blockers 1-3).

---

## 1. Structural fit

### 1.1 Legend

- **Fit:** `same` (same state, same cadence), `adapt` (same state, different cadence or
  decomposition; adaptation point identified), `absent` (no engine counterpart).
- **Observable:** `writes` (differs in the YM/PSG write stream), `ram` (differs only in a
  driver-RAM-shaped comparison), `pcm` (differs only in rendered audio), `internal`
  (not observable at any oracle boundary).
- **Risk:** parity risk for a per-invocation write-stream + RAM oracle. `high` = would
  desync on ordinary music; `med` = desyncs on a specific event (SFX, pause, 1-up); `low` =
  only under a RAM comparison or edge data.

### 1.2 Subsystem table

| # | ROM subsystem | ROM shape (per game) | Engine today | Fit | Observable | Risk | Adaptation point |
|---|---|---|---|---|---|---|---|
| 1 | **Driver entry / invocation boundary** | One pass per V-int, locked to V-blank, including lag and paused frames (S1 §3.1; S2 §4 `sndDriverInput` from every VInt incl. `Vint_Lag`; S3K §3.2). S1 additionally runs `UpdateMusic` a second time from H-int on LZ delayed-transfer frames (S1 §3.1, open q 4) and skips the whole track walk plus `DoStartZ80` on a song-load frame (S1 §3.2 tamper table). | `SmpsSequencer.advanceBatch` runs `processTempoFrame` whenever `sampleCounter >= samplesPerFrame`, a sample-domain 1/60 s period that starts at sequencer construction and drifts by the fractional remainder; phase-free of the outer frame (EM §3.2 step 6). Commands are drained once per outer frame before rendering (EM §3.2 step 3). | adapt | writes (which outer frame a note lands in), ram (row alignment) | high for any oracle keyed by invocation ordinal | Lock the sequencer frame to the outer presentation frame: `AudioPresentationProducer.present` already has the frame; make `advanceBatch` consume "N frames" derived from it instead of counting samples, and emit one `ServiceEvent` per ROM update even when the tempo gate skips work (EM §5.2 item 4). S1 song-load-frame skip and the H-int second call are per-game cadence facts the S1 profile must expose (the H-int call is 68k-state-driven and needs a game-side predicate, not a driver constant). |
| 2 | **Main tempo** | S1: countdown; on expiry add 1 to every music `DurationTimeout`; track walk always runs (S1 §3.3). S2: `TempoTimeout += CurrentTempo`; no-carry → `inc DurationTimeout` on 10 tracks; **track updates still run** (S2 §5.2, §10 running branch). S3K: carry → increment nine tracks; **track loop still runs** (S3K §3.4, §6.1). | `TIMEOUT` always ticks and extends durations (S1 shape, `SmpsSequencer.java:1266-1281`). `OVERFLOW2` ticks **only** on overflow (`:1282-1291`); `OVERFLOW` ticks **only** on no-overflow (`:1298-1307`). On a delay frame no `tick()` runs at all. | adapt | **writes**: S2 writes PSG envelope volume every running frame when `VoiceIndex != 0` (S2 §11) and frequency on every modulation step (S2 §10); S3K writes `A4/A0` on **every** running FM frame unless sustain (S3K §6.1) and PSG freq+vol every frame (§6.2). All of those are missing on engine delay frames. Also `ram`: `ModulationVal`, `VolEnv`/`VolFlutter`, `NoteFillTimeout` advance in the ROM and not in the engine (EM §5.2 item 5). | **high** (every song with tempo `< FF`/`> 0`) | Make `OVERFLOW`/`OVERFLOW2` take the `TIMEOUT` shape: always `tick()`, and on the delay condition pre-increment **all** music track durations first — the three ROM `TempoWait` loops increment every music slot without testing the playing bit (S1 §3.3; S2 §5.2; S3K §3.4). The `TIMEOUT` branch is already that cadence, but its `t.active && t.duration > 0` gate is itself a RAM-level deviation the S1 RAM comparison will see. Measure before/after with the S2/S3K probes (§2). This answers EM open q 1 from S2 §5.2/§10 and S3K §3.4/§6.1. |
| 3 | **Per-track `TempoDivider` / duration** | Byte-multiply, divider 0 → 256 (S1 §3.3; S2 §5.3; S3K §3.4). `DurationTimeout := 1` at load so the first parse is on the next update (S1 §5.2; S2 §8.2; S3K §4.4). | `dividingTiming`, `rawDuration`/`scaledDuration` (EM §2.3). | same | internal | low | Verify divider-0 wrap once (S2 open q 7). |
| 4 | **Sound queue and dispatch** | S1: 3 slots, one accepted per pass, a second same-pass request is written back to slot 0 for next frame, slot 2 dead (S1 §4.2, FixBugs #1). S2: `QueueToPlay` + 3 slots; `zCycleQueue` runs only when `QueueToPlay == 80`; **one request per frame**, a rejected SFX is discarded, later slots wait (S2 §7.1). S3K: 3 mailboxes → 3-slot queue cycled **three times per update**, no priority; `Play_SFX` drops a request equal to `zSFXNumber0`, second request overwrites `zSFXNumber1` (S3K §4.1-4.2). 1-up gating of the mailbox (S3K §4.9). | All pending `AudioPresentationCommand`s drained in submission order at the outer-frame boundary (EM §3.2 step 3, §5.2 item 8). No queue bytes, no one-per-frame serialisation, no same-id mailbox drop. | adapt | writes (SFX start frame), ram (`queue0..2`, `nextSound`) | med (two requests in one frame; S3K same-id spam) | Give `SmpsDriver` a ROM-shaped request mailbox (3 bytes + `nextSound`/`QueueToPlay`) fed by the presentation queue and cycled by the driver's own per-frame service with the per-game rule (one per frame / three per frame / write-back). The presentation queue stays the transport; only the *admission phase* moves into the driver. Exposes the bytes for the RAM comparison. |
| 5 | **SFX priority / admission** | S1/S2: single global `v_sndprio`/`SFXPriorityVal`; a request below it is rejected outright (no track init); bit 7 = accept but do not store; cleared when an SFX track ends, `StopSFX`, 1-up, fade gates (S1 §4.2; S2 §3, §7.1). S3K: none (S3K §4.2). | Priority per SFX sequencer; arbitration per chip channel; "music always loses, special loses to normal, higher wins, equal → newer" (EM §1.4, §2.4). `SmpsAdmissionContext` carries `priorityBefore/After` diagnostically. `getSfxPriority` reads Java tables (EM §1.3). | adapt | writes (a rejected SFX never plays; S2 §7.1 discards it; engine may admit it on a free channel), ram (`sndPrio`) | med | Add a driver-global `sndPrio` to `SmpsDriver` for profiles that declare it (S1, S2), gate whole-request admission in `SmpsRequestAdmissionPolicy` before per-channel locks, and clear it at the ROM sites. Per-channel locks remain the *ownership* model; the global is the *admission* model. Read the priority tables from ROM (`SOUND_PRIORITIES_ADDR` is already declared, EM §4.5; S1 §4.2 gives `$71AE8`, S2 §7.3 gives `sd:3716`). |
| 6 | **Channel ownership, override, restore** | Bit 2 on the music track; overridden track keeps parsing/advancing, all hardware writes dropped (S1 §2.2; S2 §9.3; S3K §4.5). Restore on SFX end: S1 `SetVoice` from the music voice bank + at-rest, no freq until next note (S1 §13.1/13.3); S2 `zSetVoiceMusic` (voice, `B4`, TLs), `set 1` at-rest, PSG noise re-latch (S2 §9.3); S3K voice re-upload + FM3 `27h` + SSG-EG resend, **frequency not resent until next update** (S3K §4.6). S1 special-SFX layer (FM4/PSG3) that SFX outrank (S1 §7). S1 `Sound_PlayBGM` keeps SFX alive and re-marks their channels (S1 §5.2); S2/S3K stop all SFX before any song load (S2 §8.1; S3K §4.4). | `fmLocks/psgLocks`, `Track.overridden`, `updateOverrides`; release "restores instrument/volume/pan/frequency" (EM §1.4). `specialSfx` class exists (EM §1.4). | adapt | writes (restore burst shape; an engine frequency resend that the ROM does not emit; S1 vs S2/S3K song-load SFX policy) | med (every SFX end) | Make the restore burst profile-shaped: per game, the ordered list of writes emitted on release, with "at-rest, no frequency" for all three. Verify `SmpsDriver.setChannelOverridden`'s frequency restore against S1 §13, S2 §9.3, S3K §4.6 and remove it if the ROM never emits it. Song-load SFX policy is already a profile knob shape (`isMusicOverride` family); confirm S1 keeps SFX and S2/S3K stop them. |
| 7 | **Music load and init write burst** | S1: `InitMusicPlayback` `FMSilenceAll` (key-off ×6, TL `7F` ×24) + `PSGSilenceAll`, then per-track init, 7-track `$2B := 0` **never re-enabled** except `StopAllSound`, FM6 key-off/TL `7F`/`B6 := C0` otherwise, then `FMNoteOff`/`PSGNoteOff` on all music tracks honouring override (S1 §5.2). S2: `zFMSilenceAll` (`FF` to `30-8F` both parts) + PSG silence, `82` rest init, FM6 handling, `2B` from `DACEnabled`, `zInitSFX` note-offs (S2 §8.2, §8.4). S3K: `zStopAllSound` (per-channel `zFMSilenceChannel` D1L/RR `FF` + TL `7F` + key-off, SSG-EG clear, `2B = 0`, `27 = 0`), `B6 = C0` direct, per-track init (S3K §4.4, §5.3). | Sequencer construction + first `read` priming (EM §3.2 step 7); `stopAll` exists (EM §1.1). Write shape of the init burst not described by the engine map. | adapt (verify) | writes (the burst is the first thing any per-song oracle compares; S1 already matches at GHZ, S2/S3K unmeasured) | med | Spec the init/silence bursts per game as ordered write lists; compare the engine's `stopAll`/song-start burst against them with the per-game probe. S1 `$2B` latch (DAC disabled by a 7-track song until `StopAllSound`) is a driver global the engine must keep (S1 §14.3). |
| 8 | **Note parse, frequency, key on/off** | S1: freq written at note-on and on modulation steps only (tamper, S1 §3.2, §9.1); index `note-$80` with entry 0 reachable by negative transpose; 96-entry table; `>= 96` reads code (S1 §9.3). S2: same write cadence (S2 §10 "Consequence"); table page-wrap for out-of-range (S2 §10). S3K: `zFMSendFreq` on **every** running frame unless bit 6; octave loop with no clamp; PSG overrun into `zFMFrequencies` (S3K §6.1, §6.4). | `baseFnum/baseBlock`, `forceModulationWrite`, `modStepChanged` flags (EM §2.2); tables Java-resident (EM §4.5). | adapt (verify) | writes (S3K every-frame `A4/A0`; overrun behaviour on edge data) | med for S3K cadence; low for overruns | Confirm per game whether the engine writes FM frequency every running frame (S3K) or only on change (S1/S2); the flags suggest the S1/S2 shape is modelled and the S3K shape is an open question. Table overruns: model as ROM-read tables with the ROM's neighbouring bytes (S1 FixBugs #21 MZ is shipped data). |
| 9 | **Voice upload, TL/volume** | S1: 1,3,2,4 operator order, `FMSlotMask`, `SendVoiceTL` carry-skip, `(a6)` special-voice-pointer bug for SFX `$E6` (S1 §9.4-9.5, FixBugs #19). S2: register-order upload, `TL + Volume` unclamped (S2 §13). S3K: `zSendTL` `& 7Fh`, `zSetMaxRelRate` before every `EF`, FM vol-env ignores `Volume` (S3K §7.3-7.4, §8.1). | Three `FmVoiceWriteProfile`s, `VolMode`, `direct68kDriver` gates TL carry-skip (EM §4.1, §4.4). | same (via profile) | writes | low (S1 matched; S2/S3K unmeasured) | Per-flag verification in the spec lane; FixBugs #19 needs a decision (which shipped SFX carry `$E6` on FM — S1 open q 3). |
| 10 | **Modulation** | Pointer kept, bytes re-read; steps halved at note, full on reload; S1/S2 delta negation with no write that frame (S1 §10; S2 §10). S3K: same plus `ModulationCtrl` byte, `F1`/`F4`/`FA` (S3K §7.1). | `modPending*` copies, init+counter pairs (EM §2.3). | same | internal, except via #2 (delay frames) | low once #2 is fixed | Keep; RAM mapping needs `ModulationPtr` reconstructed from `pos` of the `F0` (EM §5.2 item 2). |
| 11 | **Modulation envelopes (S3K)** | `82h`/`84h` read the operand from **driver code bytes** at `ModEnvIndex+1`; reachable by CNZ, LBZ, S3 miniboss (S3K §7.2, open q 3). | `modEnvData` per track (EM §2.2). Whether the engine loads the installed code image bytes is an engine question the maps leave open. | adapt (verify) | writes (pitch on those songs) | med (three zones on the release route) | The engine must read the Kosinski-compressed driver blob from the ROM (rule 1 compliant) and expose bytes `0001h-0020h` to the envelope stepper. Spec lane item; implementation deferred. |
| 12 | **PSG volume envelopes** | S1: `$80` hold decrements the index back; other negative bytes added (FixBugs #15); envelope reset on every note incl. FM (S1 §15.3). S2: `80` halts volume writes until next attacked note (S2 §11). S3K: `81` rest-without-silence, `83` rest+silence, other negatives read code bytes (S3K §7.3). | `PsgEnvCmd80` config, `envHold/envAtRest` (EM §1.4). S2 envelopes are Java-resident (EM §4.5). | same (via config) | writes | low | Verify the three semantics per flag; read S2 envelopes from the driver blob. |
| 13 | **Fades** | S1: out 39 steps every 4 calls, `StopAllSound` at 160; SFX refused throughout; in: 40 steps every 3, done at 121, DAC muted by bit 2 (S1 §12). S2: out 40 steps/4 frames, **SFX not blocked**; in: 3 frames/step, DAC bit 2 (S2 §14). S3K: out `28h`×6, DAC+PSG halted immediately, FM only; in `40h`×2 FM1-5 only, PSG/DAC muted, `Volume += 40h` (S3K §5.1-5.2). | `FadeState` one struct, config constants, `isSfxBlockingMusic` profile knobs (EM §1.4, §4.2). | same (via config; verify constants) | writes | low-med | Verify each constant and SFX-gate against the map; S3K fade-in "FM1-5 only, DAC skipped" is a channel-set rule the config must carry. |
| 14 | **Pause / unpause** | S1: `f_pausemusic` 1→2→`$80`→0; pan `B4-B6 := 0` both parts, key-off ×6, PSG silence; unpause re-sends `B4` for playing non-overridden music+SFX+special FM tracks; no Z80 pause; the pause/unpause frame does no other driver work (S1 §11). S2: `zFMSilenceAll` (**destructive** `FF` to `30-8F`), PSG silence; unpause re-sends pan and **reloads voices** (`zResumeTrack`, incl. voice 0 into FM6 via the DAC track); DAC keeps playing (S2 §15). S3K: pan 0 on FM1-5 (FM6 untouched), key-off 0-5, PSG silence ×2; unpause re-sends `B4` for FM1-5 + PSG1 slot only; SFX pans never restored; **unpause** during fade-out → stop-all (the `zPauseUnpause` unpause branch checks `zFadeOutTimeout`; the paused frames themselves do nothing) (S3K §3.6). | **Absent.** Presentation `SILENT` mode; no sequencer advance, no writes (EM §1.4 Pause, §3.2). | absent | writes (pause and resume bursts), ram (`pauseFlag`, and S2's voice reload) | med (every pause) | Add a driver-level pause state to `SmpsDriver` (ROM flag semantics per profile) that emits the game's pause/resume bursts and freezes service; keep `SILENT` for the sink. The S2 destructive silence and FM6 voice-0 reload are ROM behaviours the engine must reproduce, not clean up. |
| 15 | **1-up save / restore** | S1: `$220`-byte RAM copy (variables + music tracks: tempo, speed-up, fade, ring toggle, queue); SFX killed, override marks cleared; speed-up edits the **backup**; restore sets at-rest, `Volume += d6`, `SetVoice` per FM track, fade-in (S1 §5.1, §12.2, §14.1). S2: `1BC`-byte copy incl. `SFXPriorityVal` restored stale; `zStopSoundEffects` first (S2 §8.1, §14, §17). S3K: nine slots copied **onto the SFX slots**; bit 7 stripped; deferred music id kept in mailbox; restore `|= 84h`, `Volume += 40h`, voice re-upload, PSG/DAC muted till fade-in ends; live SFX corrupt the save (S3K §4.9, open q 5). | Second live `SmpsDriver` voice parked in `AudioVoiceRegistry`'s override stack; restore resumes it (EM §1.4, §3.2 "Music-override cadence", §5.2 item 9). | adapt | writes (restore burst; S3K mute set), ram (`savedMusic` fields) | med | Keep the parked-driver model (it holds exactly the RAM copy's information) but make `RestoreMusicOverride` emit the game's restore burst (at-rest, attenuated voice re-upload, no frequency) and start the game's fade-in shape; route speed-up while a 1-up plays to the parked driver (S1 §14.1, S2 §17). The S3K save/SFX aliasing is a **non-goal** until an oracle shows it (open). The `E2 FF` static reach-out in `Sonic3kCoordFlagHandler` (EM §1.2) becomes a `CoordFlagContext` call. |
| 16 | **Speed shoes / tempo speed-up** | S1: `$E2/$E3` swap `v_main_tempo` from `SpeedUpIndex` (8 entries; `$89+` read `MusicIndex` bytes), reset timeout, `f_speedup` survives song load (S1 §14.2). S2: `TempoTurbo` from a 31-byte table, `TempoTimeout` not reset, flag survives load, fade clears (S2 §8.3, §17). S3K: `zTempoSpeedup` countdown in the tail that runs after **both** passes, so value 8 → one extra `zUpdateMusic` every fourth frame; cleared by `zStopAllSound` (S3K §3.5). | S1/S2: Java `SPEED_UP_TEMPOS` maps (EM §4.5). S3K: `speedupTimeout` reloaded to `speedMultiplier` and decremented **once per frame** (`SmpsSequencer.java:1310-1322`), i.e. one extra tick every 9 frames at 8. | adapt | writes (S3K note timing under speed shoes: 5/4 vs 10/9 — by inspection, unmeasured) | med (S3K speed shoes, special stage ramp) | Model the S3K tail as the ROM does: decrement once after the SFX pass and once after each music pass. Read S1/S2 tables from ROM; S1 table overrun for `$89+` is shipped behaviour. |
| 17 | **PAL** | S2: `zPALUpdTick` 5→0, second `zUpdateMusic` every sixth frame for eligible songs (S2 §5.2). S3K: `zPalDblUpdCounter` reload 5 (S&K), whole `zUpdateEverything` again — 7 updates per 6 frames (S3K §3.2). S1: none. | `tempoWeight *= 1.2` (`SmpsSequencer.java:824-825`). | adapt | writes, ram | low (NTSC is the release target) | NTSC-only oracle until a double-update model exists; the model is a profile-declared "extra update every Nth frame, music-only (S2) or everything (S3K)". Answers EM open q 7 for S&K: reload 5 (S3K §3.2). |
| 18 | **Continuous SFX (S3K)** | `FC` loop budget from the header track count, `zContinuousSFXFlag`, 16-frame 68k re-request (S3K §4.7). | `continuousSfxId/Flag/contSfxLoopCnt` + handler (EM §1.4). | same | writes | low | Verify against §4.7; the 16-frame re-request is game side. |
| 19 | **Request transforms** | Ring L/R: S1 substitutes `$CE`, toggle reset by **every song load** and `StopAllSound` (S1 §14.4); S2 `zRingSpeaker` `cpl` (S2 §9.1); S3K plays index `zRingSpeaker`, reset by stop-all (S3K §4.5). Gloop (S2 §9.1). Spindash: S2 `3C`-frame counter + 0-11 ladder in the driver (S2 §9.1-9.2); S3K `E9` flag with `zSpindashRev` reset by every normal SFX (S3K §10). Push latch `$A7`/`$ED` (S1 §14.5). | `AudioManager.ringLeft` + `ResetRingAlternation`; gloop in the object (CD REQ-02); `spindashRevCounter` runtime state + `adjustSfxPitch` (EM §1.2, §1.4). | adapt | writes (which ring SFX; spindash pitch) | low-med | Move the toggles into `SmpsDriver` as driver RAM (reset at the ROM sites: S1 song load, all games stop-all) so they appear in the RAM comparison. S2 spindash ladder becomes driver state with the `3C` counter. Push latch is an S1 driver global to add. |
| 20 | **SEGA PCM** | S1: 68k busy-wait, Z80 loop at `pcmLoopCounter(16000)` (S1 §14.6, §16.2). S2: inside VInt with interrupts off; aborts when `QueueToPlay` changes (S2 §6). S3K: `zStopAllSound` first, DAC loop with `di`, `cmd_StopSEGA` residue becomes a stop-all next frame (S3K §4.8, open q 6). | `SegaPcmSpec`, `SEGA_PCM_ENTER/LEAVE` events; host-linear sample (CD CHIP-08). | adapt | pcm (rate), writes (the stop-all before/after) | low | Render through the DAC path at the Z80 loop period; the "everything freezes" property is already a lifecycle event. |
| 21 | **DAC / DPCM** | S1: 68k writes `zDAC_Sample`, Z80 polling loop, abort-on-new-sample after every byte, timpani pitch latch permanent, 301 cycles/byte + pitch loops (S1 §16). S2: 295 cycles/2 samples + `13(b-1)`, VInt forces one early sample every frame, `zCurDAC` never cleared (S2 §6). S3K: 303/2 nibbles + `13(N-1)`, per-frame bank switch, `zDACIndex` bit 7 (S3K §9.1). | Z80 loop modelled inside `Ym2612Chip` from `DacData.baseCycles` (S1=301, S2=**288**, S3K=297 — EM §1.3) | adapt | pcm only (`2A` writes are per sample; not in a write-order oracle) | low for parity; audible | Take base cycles from the maps (S2 295 per S2 §6 — CD DAC-02 confirmed by the map; S3K 303 vs the engine's 297 needs the same reading of `D:4299-4350`); intra-frame perturbations (VInt early sample, bus holds) are hardware timing outside the RAM/write oracle — patch 0003 territory (§2). |
| 22 | **Coordination flags** | 26 flags S1 (S1 §8) with `$E6`-on-PSG, `$EE`-restores-FM4-regardless, `$F9`-writes-FM1; 26 flags S2 (S2 §16) with `ED` skipping a byte, `E0` returning before store when overridden; 40 S3K incl. `FF 00-07` (S3K §10) with `F3` on FM1-3 hazard. | Shared switch + config overrides for S1/S2; full handler for S3K (EM §1.4, §4.3). | same (per flag, unverified) | writes | low-med | The spec lane produces a per-flag, per-game table "ROM effect → engine site → verified/deviates"; the three S1 oddities and S2 `ED` are the ones most likely to be "cleaned up" today. |
| 23 | **Driver RAM / track struct view** | S1 §2, S2 §2-3, S3K §1.3-2: every field with address. | `SmpsTrackSnapshot` + `SmpsSequencerSnapshot` + `SmpsDriverSnapshot`; `S1AudioFieldRegistry` names 29 fields; `S1/S2/S3kCompleteRunStateNormalizer` exist reference-side; `normalizeEngine` has no production caller — only the normalizers' own unit tests invoke it (EM §5.1-5.2). | adapt | ram | — (this *is* the oracle vocabulary) | Per-game field registry classifying each ROM field as `compared`, `derived` (`FeedbackAlgo` from `voiceData[0]`, `VolTLMask` from algorithm, `ModulationPtr` from `pos`, `DataPointer` = `pos + z80StartAddress`), `engine-only`, or `not-compared` (`Unk11h`, `zFM3Settings`, S3K `12h-16h`). Loop counters and gosub stack must be re-packed into one overlapping region for S1/S2/S3K (S1 §2.2 stack note; S3K §2). |
| 24 | **RAM aliasing / struct overlap** | S1: gosub stack overwrites `LoopCounters[4..11]` (S1 §2.2, open q 9); FixBugs #11 special PSG3 `VoicePtr`/`LoopCounters` survive stop-all. S2: 4th SFX slot aliases `VoiceTblPtr` (never written) (S2 §4). S3K: save area over SFX slots (open q 5). | Separate arrays (EM §2.3). | absent | ram, writes (only if shipped data reaches them) | low | Decision item: model only the overlaps a shipped stream reaches; S1 open q 9 is the check to run. |
| 25 | **YM write timing within an update** | Bus-polled writes spaced by instruction timing; S1 polls busy before both halves (S1 §9.6, open q 11); Z80 drivers `rst` helpers (S2 §1; S3K §12 `584, 609`). | All writes issued during a tick land at the start of the next chunk, spaced only by the chip busy model (EM §3.2 step 9). Prior designs measured the largest intra-service delays as 68k bus holds, not driver constants (`2026-08-24-s3k-semantic-service-timing-design.md`). | adapt (deliberately) | writes only at **cycle** granularity; invisible to a per-invocation oracle | low for the RAM/write-order oracle | Out of scope for this workflow's oracle tier; a cycle-stamped tier is optional and last (§2). |

### 1.3 Things that are the same and need only verification

Track construction, header parsing (S1 §5.2, S2 §8.2, S3K §4.4 vs EM §1.3), FM/PSG
channel init bytes, `StackPointer`/gosub, `F7` loop counters, detune, `E0` pan composition,
note fill (`fillCounter` vs `fill+duration-scaledDuration` derivation, EM §2.3), the S3K
`FF 06` FM volume envelope (no shipped stream uses it, S3K §7.3), SSG-EG and FM3 special
mode (unreached, S3K §8.2-8.3), and the S3K `E3`/`zStopSFX` FM1 key-on hazard on PSG tracks
(S3K §4.6) — the last one is a ROM bug the engine must emit, and the spec must say so.

### 1.4 Where the honest answer is not an adaptation

1. **The sequencer frame clock.** Making the boundary frame-locked (#1) is not a change
   to the track model, but it is a re-plumb of `SmpsDriver.readHybrid`'s chunking and
   `SmpsSequencer.advanceBatch`'s sample counting into a "frames owed" model driven by the
   presentation frame. It touches the hybrid render loop, rewind snapshots (`sampleCounter`
   is captured, EM §5.1) and `AudioFrameClock`. It is a rewrite of the *clock*, not of the
   driver, and it is the prerequisite for every per-invocation comparison of S2/S3K. The
   S1 oracle passes today because `S1OpenGgfAudioCapture` bypasses the render loop and
   calls `advanceBatch(NTSC_SAMPLES)` directly (EM §5.1), so the live graph's phase drift
   has never been measured against a reference.
2. **The 1-up as a RAM copy.** If the oracle later shows the S3K SFX-slot corruption or the
   S1 "backup carries queue bytes and ring toggle" facts matter audibly, the parked-driver
   model cannot express them without becoming a RAM copy. Until then it is an adaptation;
   the spec should record the boundary.

Everything else — delay-frame ticks, queue bytes, global priority, pause, restore bursts,
speed-up tail, PAL double update — is additive on the existing owners.

---

## 2. Oracle: what a per-game write-stream + driver-RAM oracle needs

Ordered by what unblocks the most. Items 1-4 are the critical path for S2/S3K; the S1 GHZ
music oracle is the template and is proven to reproduce byte-identically (OR "Engine
comparison").

| # | Need | Exists today | Gap | Unblocks |
|---|---|---|---|---|
| 1 | **A runnable launcher/output policy** | Direct `run_bizhawk_lua.sh` outside the sandbox with an external `OGGF_OUT` works; `run_s1_audio_parity.sh` cannot (OR blocker 1; conflict re-reproduced for this review without BizHawk: `output_policy.py --output-root <worktree>/target/audio-parity/...` exits 2 "output root must remain outside both source trees", while `S1AudioParityTool.resolveSafeOutputRoot` rejects anything outside `<repo>/target/audio-parity`, `S1AudioParityTool.java:58-75`). | Either `S1AudioParityTool.resolveSafeOutputRoot` accepts an external run root, or TraceChaser's `output_policy.py` admits a consumer `target/`; the wrapper must export `OGGF_INPUT_REPOSITORY_ROOT`/`OGGF_WORKDIR`. | Wrapper-driven, reproducible runs of every probe and the committed S1 regression baseline. It does not gate a first capture: the direct outside-sandbox launcher path already produced the S1 reference and a `MATCH` with a hand-copied file (OR "Engine comparison"). Cheapest item. |
| 2 | **Per-game Lua driver-state probe (S2, S3K)** — one Z80 RAM image per emulated frame (that frame's `zVInt` having run), **without** per-invocation YM/PSG write attribution | S1 only (`s1_audio_driver_parity_probe.lua`, 68k RAM `$F000..`, `event.onmemorywrite` on the chip ports; OR §3). The S1 mechanism does **not** transfer to the Z80 drivers: stock BizHawk 2.11 GPGX registers memory callbacks for the single scope `M68K BUS` (the `GPGX` constructor builds `MemoryCallbackSystem(new[]{"M68K BUS"})` and all three `InitMemCallbacks` thunks pass `"M68K BUS"` to `CallMemoryCallbacks` — verified by disassembling the pinned `BizHawk.Emulation.Cores.dll`), so Lua has no Z80 PC hook (no `0038h` execute callback) and never observes Z80-issued YM/PSG writes; `Z80 RAM`/`Z80 BUS` exist only as readable memory *domains*. | S2: poll `1B80-1FF3` + `12FE-1307` (S2 §1, §3.1) once per frame from the `Z80 RAM` domain; S3K: poll `1C00-1FA0` (S3K §1.3-1.4) once per frame. That is a **RAM-only** oracle at one-snapshot-per-frame granularity (the PAL intra-frame double update is invisible to it — NTSC only), and snapshot-vs-`zVInt` alignment is an open question (§5). The RAM+write-stream oracle with per-invocation attribution needs the patch-0001 native observer install (OR blocker 2) — no Lua path exists on the stock core. | An S2/S3K driver-RAM comparison — those games have **no** oracle of any kind today (OR blocker 3, 6). The chip-write half moves behind OR blocker 2. |
| 3 | **Engine-side ROM-vocabulary state producer** at `SmpsDriverServiceObserver.onServiceEnd` | `SmpsDriverSnapshot` after every tick; `S1AudioFieldRegistry`; reference-side normalisers for all three games with `normalizeEngine` uncalled from `src/main` (their unit tests do call it) (EM §5.1-5.2). | Per-game `SmpsDriverSnapshot → NormalizedState` mapping in `tools/audio/completerun/<game>/` (guard boundary, EM §5.2 item 10), the field classification of §1.2 #23, and a `ServiceEvent` per ROM update including delay frames (#1 above, EM §5.2 item 4). | Any comparison at all for S2/S3K; a RAM comparison (not just chip writes) for S1. |
| 4 | **Comparator generalisation + committed fixtures + integrity guard** | `AudioParityComparator` is S1-specific (`AudioParitySchema` pins S1 roles); one BK2 (`s1-soundtest-ghz.bk2`); no committed reference for any game; the 08-09 reference SHA lives in prose (OR §6, blocker 8). | A per-game parity profile (ROM identity, RAM layout, roles, gating vs diagnostic fields); a sound-test BK2 per game; a committed reference **digest lock** (SHA-256 + tick count + callback proof counts) rather than the 117 MB capture; a guard test that fails when the lock, BK2 hash, or ROM hash drift; a `docs/status/` audio frontier log section in the trace-frontier format (command, commit, pass/fail, first-error tick/field). | Regression detection; the "green comparison vs comparison that never ran" hazard (memory: break a new comparison on purpose). |
| 5 | **S3K and S2 reference BK2s** | No *audio* fixture (OR §6) — but committed trace movies exist for both games (`src/test/resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/`, `.../traces/s3k/runs/s3k-sonic-tails-complete-emeralds/`, plus zone movies incl. S3K AIZ), which are candidate gameplay segments; only sound-test movies are absent for every game but S1. | Sound-test movies first (music only, mirrors S1 GHZ), then one gameplay segment per game exercising music + SFX + DAC + a pause + a 1-up (the CD §13 listening rows LC-S1-1..5, LC-S2-1..6, LC-S3K1..6 are the scene list); trimming an existing committed run is an acceptable source for the gameplay segment. AIZ1 is the S3K release slice. | Items 2-4 have nothing to run without them (sound-test shape). |
| 6 | **Gameplay request/admission timeline probe per game** | S1 GHZ1 only (`s1_ghz1_gameplay_audio_timeline_probe.lua`; last result `ADMISSION_EXTRA` at frame 958, OR matrix). | S2/S3K equivalents hooking `sndDriverInput`/`Play_SFX`/`Play_Music` — 68k-side routines, so within the `M68K BUS` callback scope and unaffected by item 2's Z80 limit; the Z80-side dispatch outcome is read from the RAM snapshot, not from a Z80 hook (S2 §4, §7.2; S3K §4.1-4.3). | Tests §1.2 #4 (queue) and #5 (priority) directly, which the RAM probe sees only as consequences. |
| 7 | **Full Z80 RAM image for S2** (as opposed to the track region) | Nothing. | Included in item 2 if the dump covers `0000-1FFF`; the self-modified operands (S2 §18) and `zPalModeByte` live in the code area and are RAM state the engine has no counterpart for — classify `not-compared`. | S2 DAC state (`zCurDAC`, patched `ld b,N`) if ever compared. |
| 8 | **Cycle-stamped chip writes for S1/S2** | S3K only, diagnostic, no BK2 driver (patch 0002; OR §1, blocker 5); S1/S2 lab reverted. | Would need a patch-0001 change to keep the `cycles` argument and an observer install (blocker 2). | Only intra-invocation ordering/spacing questions (§1.2 #25), which prior designs showed are dominated by 68k bus holds. **Last**, and optional for this workflow. |
| 9 | **PCM taps for DAC** | Patch 0003, S3K only, ~one SFX slice (OR §1). | Same install blocker; capacity too small for a level. | DAC base-cycle and perturbation questions (§1.2 #21) — audible, not parity-gating. |
| 10 | **Complete-run profiles** | Schema/store/comparator exist; all six producers absent (OR §5, blocker 4). | Out of this workflow's scope; the per-invocation oracle above is the smaller, sufficient tool. | Deferred. |

The order is deliberate: 1 → 2 → 3 → 4 gives S2 and S3K a driver-RAM music oracle using
only Lua and Java — a narrower shape than S1's (RAM rows without an attributed chip-write
stream, item 2). Nothing on that path needs the native observer core or the C# host; the
TraceChaser changes it needs are the launcher policy and the new probe scripts (a gitlink
bump — or consumer-side scripts, which `run_bizhawk_lua.sh` accepts by path). The
chip-write half of an S2/S3K oracle waits on the patch-0001 observer install (OR
blocker 2) and is not on this workflow's critical path.

---

## 3. Coverage: claims no spec would cover unless a subsystem is added

The per-game spec outline in §4 covers every `unverified` driver row in CD §1-10 through
the subsystems listed there. The following CD rows fall outside any driver subsystem the
maps describe and need an explicit owner or an explicit "not a driver spec item":

| CD row(s) | Why no driver subsystem covers it | Subsystem to add (or owner) |
|---|---|---|
| CHIP-01 (PAL chip clocks) | Hardware clock, not driver code; engine hard-codes NTSC in both cores. | **Region/hardware clocks** section in the config-owner's doc; the spec's PAL cadence section (§1.2 #17) must reference it. |
| CHIP-02, CHIP-03 (config defaults `psgNoiseShiftEveryToggle`, `dacInterpolate`) | Presentation configuration. | `CONFIGURATION.md` owner; not a spec item. |
| CHIP-04 (linked-noise clock rate at period 0/1) | Silicon measurement; the disassembly cannot answer it. | Hardware-question register in the spec's open questions; oracle can observe only if a PCM tap exists. |
| CHIP-07 / DEF-04 (FM:PSG analogue mix) | Analogue mixing outside both chips. | **Analogue mix calibration** — needs a two-chip hardware capture; no spec covers it. |
| DAC-01 intra-frame perturbations (VInt forced early sample, `stopZ80` holds, interrupt latency: S2 open q 3; S3K §9.1) | Bus/interrupt timing, not driver logic. | **Bus-hold/interrupt timing** as an oracle-only tier (patch 0003), never a spec constant (hard rule 3). |
| VOICE-04, VOICE-05, DEF-09 (write spacing, busy polling) | Cycle-level; S1 open q 11. | The optional cycle-stamped tier (§2 item 8). |
| DEF-02 (CNZ1 miniboss play-in), DEF-03 (SEGA screen), OVR-10 (Blue Sphere tempo), FADE-06 (`sfx_EnterSS`), S1 §3.1 H-int double call, S2 §4 `Vint_Lag` | Game-side request sites and interrupt paths, not driver behaviour. | **68k request-site catalogue** per game: every `Play_Music`/`Play_SFX`/`Change_Music_Tempo`/pause writer with its condition. The maps give the interface, not the catalogue. |
| REQ-06 / DEF-06 (S2 playlist from a Java table), EM §4.5 (priority tables, speed-up tempos, S2 envelopes, frequency tables in Java) | Data provenance, not driver logic. | **ROM-read data tables** subsystem: each table's ROM address, size, and a one-time equality check against the Java copy, with removal of the copy as the deferred implementation item. |
| S3K §7.2 open q 3 (mod-env `82h` reads driver code bytes) | Needs the installed code image, i.e. loader behaviour. | **Installed driver image** subsystem for S3K: which bytes of the Kosinski blob the sequencer must be able to read. |
| S1 §2.2 / S3K open q 5 / S2 §4 (struct overlaps and save-area aliasing) | RAM aliasing the engine's separate arrays cannot express. | **RAM aliasing decisions** section (§1.2 #24): per overlap, reachable-by-shipped-data yes/no, model/non-goal. |
| S2 open q 4, 6, 10; S3K open q 1 (Z80 `BIT` flag, PSG `1F` latch, TL bit 7 on the YM, open-bus reads above `1FFFh`) | Hardware semantics outside the driver source. | Hardware-question register; the oracle observes the *effect* and the spec records the observed rule. |
| DATA-02 (fail-closed DAC catalogue) | Loader policy. | Loader owner; not a driver spec item. |

Every other CD row (CAD-01..12, ADM-01..09, REQ-01..05, REQ-07, OVR-01..09, FADE-01..05,
PAUSE-01..05, VOICE-01..03, SEQ-01..05, DAC-02..06, DATA-01, DATA-03) maps to a §4
subsystem; the spec lane must cite the map section that settles each and mark the
CD status `derived` or `contradicted` (CD §14 rule). Two are already contradicted by the
maps and should be recorded as such when the specs land: CAD-07 says the S3K PAL reload is
6 for the locked-on ROM, S3K §3.2 says the S&K image (the only one installed) reloads 5;
CAD-05 says "an extra music update every four VInts at value 8", which S3K §3.5 confirms,
against the engine comment's "every (N+1) frames".

---

## 4. Lane plan

### 4.1 Per-game spec outline (the next phase of this workflow)

One spec per game, `docs/architecture/research/audio/2026-MM-DD-<game>-sound-driver-spec.md`,
sources-closed, with the subsystems in this order. The order is by oracle reachability
(what the §2 probe compares first) and by §1.2 risk, so a lane can stop partway and still
have produced the parts the oracle needs.

1. **Invocation boundary and cadence** — entry per V-int, lag/pause/H-int behaviour, tamper
   returns and skip frames, PAL double update, speed-up tail, what "one update" contains and
   in which order (music vs SFX pass). Output: the definition of one oracle tick.
2. **Driver RAM and track struct** — every field with address, and the field registry
   classification (`compared`/`derived`/`engine-only`/`not-compared`) with the engine
   `Track` field it maps to. Output: the comparison vocabulary.
3. **Main tempo and durations** — including the delay-frame rule (§1.2 #2) stated as "what
   runs on a delay frame".
4. **Queue, dispatch, priority/admission** — mailbox/slot bytes, per-frame cycle rule,
   priority latch set/clear sites, dispatch ranges, the ROM priority table bytes.
5. **Music load and silence bursts** — ordered write lists for song start, stop-all,
   `FMSilenceAll`/`zFMSilenceChannel`, `$2B`/`DACEnabled`/`27h`.
6. **SFX load, ownership, override, restore** — slot mapping tables, override-bit effects,
   the ordered restore burst, song-load SFX policy, S1 special layer.
7. **Note parse, frequency, key on/off** — tables (with ROM address and overrun rule),
   write cadence per frame, rest semantics.
8. **Voice upload and volume model** — byte layout, register order, TL masking, carry/clamp
   rule, `E6`/`EC` semantics.
9. **Modulation and envelopes** — normal modulation, mod envelopes (S3K incl. code-byte
   reads), PSG volume envelopes (with `80/81/83` semantics), FM vol-env.
10. **Coordination flags** — full per-flag table with param count, effect, engine site,
    and verified/deviates.
11. **Fades** — constants, channel sets, SFX gates, terminal action.
12. **Pause/unpause** — flag machine, pause burst, resume burst, what is and is not restored.
13. **1-up save/restore and speed shoes** — save set, gates while active, restore burst,
    fade-in, where speed-up writes go while a 1-up plays.
14. **Request transforms** — ring L/R, gloop, spindash, push, continuous SFX, SEGA.
15. **DAC/PCM** — 68k↔Z80 handshake, sample tables (ROM-read), base cycles from the listing,
    perturbations marked oracle-only.
16. **FixBugs sites reachable by shipped data** — the subset of the map's table that a
    shipped stream or a normal game path reaches, each with the engine's current branch.
17. **ROM-read data tables** — address, size, and the Java copy to be checked once.
18. **Open and hardware questions** — carried forward, never resolved from memory.

Per-game additions: S1 — the H-int second call (game-state predicate), the DAC-disable
latch, the special-SFX layer, the `SpeedUpIndex` overrun. S2 — Saxman/playlist decoding
and `MusicBankNumber`, `IsPalFlag`, `DACEnabled`, the 4th SFX slot alias, `zPaused`
outside the cleared region. S3K — S&K-image-only rule (S3K §1.1, §13), continuous SFX,
mod-env code reads, `zStopAllSound` wipe range, mailbox de-dupe, the unpause SFX-slot
rule, SEGA `cmd_StopSEGA` residue.

### 4.2 Oracle build order (the phase after the specs, still this workflow)

| Phase | Deliverable | Depends on |
|---|---|---|
| A | Launcher/output-policy repair; S1 GHZ parity re-run as the baseline; committed S1 reference digest lock + fixture-integrity guard; audio frontier-log section. | nothing (§2 items 1, 4) |
| B | S2 and S3K sound-test BK2s; per-game Lua driver-state probes (per-frame Z80 RAM snapshots — RAM-only, §2 item 2); per-game parity profiles in the Java comparator; engine-side `NormalizedState` producers — the first pass may sample the sequencer directly the way `S1OpenGgfAudioCapture` does (EM §5.1), since the per-update `ServiceEvent` including delay frames is itself deferred item 1 and must not land before this phase's red authorises it. First S2/S3K music comparisons — **expected red** at the first delay frame (§1.2 #2), showing up as missing/stale engine rows or diverged `DurationTimeout`/envelope fields; that red is the measurement that authorises the implementation work. Break the new comparison on purpose (corrupt one input field) before trusting its first green. | A for the committed baseline — a first capture can also use the manual direct-launcher procedure OR documents; spec sections 1-3 per game (§2 items 2, 3, 5) |
| C | Gameplay BK2 per game (music + SFX + DAC + pause + 1-up); per-game request/admission timeline probe; comparison extended to SFX slots and driver globals. | B; spec sections 4-6, 12-14 (§2 item 6) |
| D (optional) | S2/S3K per-invocation attributed chip-write streams (patch-0001 observer — see §2 item 2); cycle-stamped S1/S2 writes; PCM taps for DAC; complete-run producers. | observer core install (OR blocker 2) — not on this workflow's critical path |

### 4.3 Deferred to implementation workflows

Each with its authorising measurement from phase B/C, in the order §1.2 ranks them:

1. Frame-locked sequencer boundary with a per-update service event (§1.2 #1, §1.4 item 1).
2. Delay-frame tick model for `OVERFLOW`/`OVERFLOW2` (§1.2 #2).
3. S3K speed-up tail cadence (§1.2 #16).
4. ROM-shaped mailbox/queue in `SmpsDriver` with per-game cycle rules (§1.2 #4).
5. Global priority latch for S1/S2 profiles; ROM-read priority tables (§1.2 #5).
6. Restore burst shape on SFX end and on 1-up restore; removal of any frequency resend the
   ROM does not emit (§1.2 #6, #15).
7. Driver-level pause state with per-game pause/resume bursts (§1.2 #14).
8. Init/silence burst verification per game (§1.2 #7).
9. S3K every-frame FM frequency write (if #8 verification shows the engine differs).
10. S3K installed-code-image bytes for mod-envelope `82h`/`84h` (§1.2 #11).
11. DAC base cycles from the listings (S2 295, S3K 303 vs 297) (§1.2 #21).
12. PAL double-update model (§1.2 #17) — after NTSC parity.
13. ROM-read data tables replacing Java copies (§3 "ROM-read data tables"), including the
    S2 playlist (CD REQ-06) and the `$90 → $10` spindash patch decision (CD REQ-04).
14. Driver-RAM residence for the ring/gloop/spindash/push state (§1.2 #19).
15. Hygiene: re-cite the seven SMPSPlay-attributed comments in `SmpsSequencer` and the one
    in `Sonic3kCoordFlagHandler` from the disassemblies (EM provenance note); resolve the
    unread `noteOnPrevent` knob (EM open q 6); replace the `E2 FF` static reach-out with a
    `CoordFlagContext` call (EM §1.2).

None of these should land before its phase-B/C comparison is red on the specific field it
changes and green after; a comparison that never ran looks identical to a green one.

---

## 5. Open questions carried forward

From the maps, unchanged, with the lane that owns each:

- EM open q 5 (which `Freq` stores hold the detuned word) — spec section 7 per game.
- EM open q 6 (`noteOnPrevent` dead knob) — implementation hygiene.
- EM open q 8 (S1 `UpdateMusic` on lag/H-int frames) — S1 §3.1 settles lag frames (called);
  the H-int frequency is a game-state question — S1 spec section 1 plus the request-site
  catalogue.
- S1 open q 1-11, S2 open q 1-11, S3K open q 1-8 — carried into each spec's section 18.
- OR open questions (whether invocation-granular references suffice for S1/S2; patch 0002
  `service_entry_master_cycle` semantics) — answered empirically by phase B/C; if S2/S3K
  reach `MATCH` at invocation granularity the cycle tier stays optional.
- New from the oracle-plan review: where inside the emulated frame a Lua `Z80 RAM`
  domain snapshot lands relative to that frame's `zVInt`. Between interrupts the Z80
  idles in the DAC loop, which does not touch track RAM, so a frame-boundary snapshot
  should be post-`zVInt`-settled — but SEGA-PCM frames (interrupts off, S2 §6; S3K §4.8)
  and savestate boundaries are unverified. Phase B settles it empirically; it is a
  tooling question, not one the disassembly answers.
- New from this synthesis: does the live presentation graph's phase-free sequencer clock
  (§1.4 item 1) ever place a driver update in a different outer frame than the ROM would?
  `S1OpenGgfAudioCapture` bypasses the render loop, so the existing `MATCH` does not
  answer it; phase B's engine producer must run through `AudioPresentationProducer`, not
  `advanceBatch` directly, to find out.
