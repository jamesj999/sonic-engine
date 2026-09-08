# SMPS driver behaviour claims digest

- Date: 2026-08-30
- Branch: `feature/ai-sdre-map-claims`, based on `feature/ai-sound-driver-re` at
  `f087b8947be4b664896958c6cfa55093223df62d`
- Purpose: one deduplicated checklist of every driver-level behaviour that prior work has
  claimed about the shipped Sonic 1 / Sonic 2 / Sonic 3 & Knuckles sound drivers, plus every
  recorded audible defect, so that the sound-driver reverse-engineering specs can be checked
  for coverage and the eventual oracle can be checked for assertions against it.

## What this document is not

It records **no verdicts**. Every "claim" row is a statement somebody made; none is endorsed
here, and none of the prior implementations behind them is endorsed. The ROM disassemblies
are the sole source of truth for driver behaviour. Where this digest names a disassembly
line, that is the location of a *label* found by `grep` in the local tree on 2026-08-30 — it
says the routine exists and where, not that it does what the claim says. Where a claimed
anchor could not be found by name, the row says so and the resolution is an open question
(section 15), not a correction.

Sources closed for this digest, by instruction: SMPSPlay, libvgm, Genesis Plus GX sound
code, the reverted branch `feature/ai-smps-transaction-parity`, and third-party SMPS
documentation that paraphrases emulator code were not opened. The engine's own source was
read only to record what the engine does today (marked "engine today"), never as evidence
of hardware behaviour.

## Sources digested

| Key | Document |
|---|---|
| INV | `docs/architecture/audits/2026-08-29-smps-parity-branch-claims-inventory.md` — the 23 class-(b) claims of the reverted branch, keyed by commit sha |
| LC | `docs/architecture/validation/audio/2026-08-21-smps-playback-listening-checklist.md` — 29 rows (6 cross-game, 5 S1, 7 S2, 11 S3K), keyed `LC-X<n>`, `LC-S1<n>`, `LC-S2<n>`, `LC-S3K<n>` in table order |
| RM | `docs/architecture/plans/audio/2026-08-21-smps-playback-authenticity-roadmap.md` — consulted only for the Phase 1 wording the LC rows abbreviate |
| KD | `docs/status/known-discrepancies.md` — audio sections: Gloop Sound Toggle; Spindash Release Transpose Fix; S2 Music Offsets Resolved from Hardcoded REV01 Table; §31 YM2612 Output Scale, Resting Level and DAC Presentation; §32 PSG Tone-2-Linked Noise; §33 FM:PSG Mix Balance |
| S3KD | `docs/S3K_KNOWN_DISCREPANCIES.md` — "SEGA Screen: an engine addition the ROM does not have" (the only audio entry) |
| S3KB | `docs/status/s3k-known-bugs.md` — "Blue Sphere FM Pickup Onset" and "CNZ1 Miniboss Arena Entry — Music Play-In Missing" (the only audio items) |
| KB | `docs/status/known-bugs.md` — no driver-level audio item (the one hit is the game-over card's music request, game-side) |
| CL | `CHANGELOG.0.6.md`, cited by line number at the branch commit above |

Disassembly files referred to below:

- S1: `docs/s1disasm/s1.sounddriver.asm` (2867 lines), `docs/s1disasm/s1.sounddriver.ram.asm`,
  `docs/s1disasm/sound/z80.asm` (`zPlayPCMLoop` at `:115`)
- S2: `docs/s2disasm/s2.sounddriver.asm` (4104 lines; `FixDriverBugs = fixBugs` at `:8`),
  `docs/s2disasm/sound/sfx/BC - Spin Dash Release.asm`
- S3K: `docs/skdisasm/Sound/Z80 Sound Driver.asm` (5315 lines; `fix_sndbugs = 0` at `:16`)

## Tag vocabulary

Each row carries `{game(s), subsystem, purported ROM anchor, status, source}`.

- **unverified** — a claim about shipped driver behaviour that the RE programme has not yet
  derived from the disassembly. This is the default for every driver claim regardless of
  whether some engine code currently implements it.
- **known-bug** — a recorded audible defect or a documented engine divergence from the ROM
  (intentional or not), with the owning document named.
- **resolved-by-chip-cores** — a chip-level (YM2612 / SN76489) behaviour that the Nuked-OPN2
  facade or the clean-room `PsgChip` now models natively, so the driver RE need not
  re-derive it. Notes record any residual engine default that still deviates.

Subsystems: `cadence`, `admission`, `request`, `override`, `fade`, `pause`, `voice`, `seq`,
`dac`, `data`, `chip`, `defect`.

## 1. Service cadence and tempo (`cadence`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| CAD-01 | On song load every driver seeds each track's `DurationTimeout` to 1 and the tempo accumulator from the header tempo. | S1, S2, S3K | S3K `zPlayMusic` `:1717`; S2 `zPlayMusic` `:1667`; S1 `Sound_PlayBGM` `:754` | unverified | INV `34ebb6653`; LC-S3K1 ("seeded tempo phase") |
| CAD-02 | S2/S3K `TempoWait` runs before track service; a carry extends every active track's duration but does not skip service — envelopes, note fill and modulation still run that VInt. | S2, S3K | S3K `TempoWait` `:2607`, `zUpdateMusic` `:658`, `zTrackUpdLoop` `:734`; S2 `TempoWait` `:596` | unverified | INV `34ebb6653`; LC-S2-1 ("carry/no-carry note holds while envelopes/modulation continue"); CL:3763-3766 |
| CAD-03 | S1 tempo is a countdown (`v_main_tempo_timeout`) whose expiry calls `TempoWait`; the accumulator/timeout is reset on a tempo change, whereas S2/S3K preserve it. | S1 vs S2/S3K | S1 `UpdateMusic` `:147` (`:174-176`), `cfSetTempo` `:2256`; S3K `fix_sndbugs` blocks near `zPlaySoundByIndex` `:1659` | unverified | INV `34ebb6653`; RM Phase 1 |
| CAD-04 | Zero-tempo songs (S3K title screen header `FF 00`) still receive a first service tick. | S3K | `TempoWait` `:2607` | unverified | INV `34ebb6653` |
| CAD-05 | S3K speed-up: the speed-up tail is shared by `zUpdateSFXTracks` and every `zUpdateMusic`; `zSpeedupTimeout` is reloaded from `zTempoSpeedup` and decremented there (`:745-757`). The LC/RM phrasing is "two timeout services per outer VInt that produce an extra music update every four VInts at value 8". The INV names a routine `zDoSpeedUp` that does not exist as a label (see §15). | S3K | `:745-757`, `zTempoSpeedup` `:127`, `zSpeedupTimeout` `:162` | unverified | INV `34ebb6653`; LC-S3K3; RM Phase 1; CL:3767-3768 |
| CAD-06 | S2 PAL: an extra music-only update every fifth VInt, for eligible songs, while SFX stay single-service. | S2 | `.pal_timer` `:438-447` (`zAbsVar.IsPalFlag` `:165`, `zPALUpdTick`) | unverified | INV `34ebb6653`; LC-S2-7; RM Phase 1; CL:3767 |
| CAD-07 | S3K locked-on PAL: `zPalDblUpdCounter` is tested before decrementing under `fix_sndbugs=0`, reloads 6, and repeats the complete `zUpdateEverything` (SFX, music, fade and speed tails) every sixth VInt; the repeated tail decrements to 5. | S3K | `zPalDblUpdCounter` `:123`, `:485-499`, `:549` | unverified | INV `9334eaba0`; LC-S3K8; RM Phase 1; CL:3768-3769 |
| CAD-08 | No driver applies a `x1.2` (or any) tempo multiplier on PAL; S1 has no PAL compensation at all. | S1, S2, S3K | S1/S2/S3K PAL paths | unverified | INV `34ebb6653`; RM Phase 1 |
| CAD-09 | S3K per-VInt order is `zPauseUnpause`, then `zUpdateSFXTracks`, then `zUpdateMusic`, so an SFX that finishes releases its channel before the same VInt's music update. | S3K | `zUpdateEverything` `:653-655`, `zUpdateSFXTracks` `:727` | unverified | INV `4bd00b064`; LC-S3K2; CL:3772-3773 |
| CAD-10 | S1 services music before SFX ("music-before-SFX service order"); S1/S2 SFX-vs-music order in general. | S1, S2 | S1 `UpdateMusic` `:147`; S2 `zUpdateEverything` `:411`, `zUpdateMusic` `:544` | unverified | INV `4bd00b064`; LC-S1-1 |
| CAD-11 | S1 is driven directly from the 68k ("direct 68k cadence"), i.e. the S1 driver is 68k code serviced per V-int rather than a Z80 program. | S1 | S1 `UpdateMusic` `:147` | unverified | LC-S1-1 |
| CAD-12 | Speed-up enable/disable changes tempo at a definite phase and restores normal tempo afterwards; S1 uses a per-song sped-up tempo table. | S1, S2, S3K | S1 `SpeedUpIndex` `:74`, `:795-807`, `f_speedup`; S2 `zSpeedUpMusic` `:2661/:2686`, `zAbsVar.TempoTurbo` `:161`, `SpeedUpFlag` `:162`; S3K `zTempoSpeedup` `:127` | unverified | LC-X5 |

## 2. SFX admission, priority, channel ownership and release (`admission`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| ADM-01 | S1 and S2 gate every SFX request through one global priority value: a lower-priority request is rejected even when another channel role is free; bit 7 of the table entry means "keep the old latch"; channels are taken at admission; the latch clears when an SFX track stops. | S1, S2 | S1 `Sound_PlaySFX` `:977`, `StopSFX` `:1226`; S2 `zAbsVar.SFXPriorityVal` `:143`, `zSFXPriority` `:3716`, `zPlaySound` `:2178` | unverified | INV `2c0844a9f`; LC-S1-2; LC-S2-3; CL:3770-3771 |
| ADM-02 | S3K has no SFX priority gate. | S3K | `zPlaySound` `:1975`, `zPlaySoundByIndex` `:1641` | unverified | INV `2c0844a9f`; CL:3772 |
| ADM-03 | When SFX channels are claimed — at request time vs at first service — differs per game ("admission-time override" for S3K). | S1, S2, S3K | S1/S2 SFX load; S3K `zPlaySound` `:1975` | unverified | INV `2c0844a9f`; LC-S3K2 |
| ADM-04 | After an SFX releases a channel, S1/S2 leave the interrupted PSG music track resting until its next note; S3K restores live state in the same VInt ("same-VInt release"). | S1, S2 vs S3K | S1 `StopSFX` `:1226`; S2 `zStopPSGSFXTrack` `:3565`; S3K `zUpdateSFXTracks` `:727` | unverified | INV `76f9571e1`; LC-S3K2 |
| ADM-05 | S2 performs no FM register reset ("synthetic takeover/reset writes") on SFX takeover or release. | S2 | S2 SFX load/stop | unverified | INV `76f9571e1`; LC-S2-4 |
| ADM-06 | S1 `Sound_PlayBGM` under `FixBugs=0` keeps normal and special SFX tracks, their channel locks, continuous state and the priority latch alive across an ordinary song change and marks their channels as overrides on the new song. | S1 | `Sound_PlayBGM` `:754` | unverified | INV `644fb6f68`; LC-S1-2 |
| ADM-07 | S2 and S3K stop all SFX before loading an ordinary BGM. | S2, S3K | S2 `zPlayMusic` `:1667`; S3K `zPlayMusic` `:1717` | unverified | INV `644fb6f68`; LC-S2-4 |
| ADM-08 | Overlapping FM and PSG SFX have a definite admission order, priority rejection, channel takeover, retained-music-channel set and release timing. | S1, S2, S3K | (aggregate of ADM-01..07) | unverified | LC-X2 |
| ADM-09 | S3K SFX have fixed physical track residences per channel; a repeated same-id SFX (explosion, collapse) replaces the running instance within a definite service phase; ownership is physical, not logical. | S3K | `zPlaySound` `:1975`, `zUpdateSFXTracks` `:727` | unverified | S3KB "Blue Sphere FM Pickup Onset"; CL:3868-3871 |

## 3. Driver-side request transforms (`request`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| REQ-01 | The Z80 driver alternates ring SFX between left (`sfx_RingLeft`) and right (`sfx_RingRight`) on every raw ring request by toggling `zRingSpeaker`, and resets it to left when music is played. Every 68k caller sends the same right-hand id. | S3K (and S1/S2 equivalents claimed) | S3K `zPlaySound_CheckRing` `:1919-1925`, `zRingSpeaker` `:156`, reset at `:547`; S1/S2 equivalents unnamed | unverified | INV (raised by the inventory); LC-S3K10 (marked PASS 2026-08-21 by a listener); CL:17-27 (engine implemented on develop, PR #179); CL:2722-2723 |
| REQ-02 | The S2 driver plays the Gloop sound only on every other request by toggling `zGloopFlag` inside `zPlaySound_CheckGloop`. Engine today implements the toggle in `BlueBallsObjectInstance`, not the driver (documented intentional placement). | S2 | `zPlaySound_CheckGloop` `:2138`, `zGloopFlag` `:4092` | unverified (driver claim); known-bug (placement is a documented divergence) | KD "Gloop Sound Toggle" |
| REQ-03 | S2 spindash-rev pitch is owned by the driver: a `$3C`-service timeout and a saturating 0-11 semitone index advanced per request; the gameplay charge counter does not set pitch. The INV also asserts an S3K driver-side equivalent. | S2 (S3K asserted) | S2 `zPlaySound_CheckSpindash` `:2152`, `zSpindashPlayingCounter` `:4093`, `zSpindashExtraFrequencyIndex` `:4094`, `zSpindashActiveFlag` `:4095`; S3K `zPlaySoundByIndex` `:1641`, `fix_sndbugs=0` block `:1659` | unverified | INV `4f7e23dd7`; LC-S2-2; CL:3789-3791 |
| REQ-04 | The S2 spindash-release SFX (`$BC`) header really carries a `$90` FM5 transpose under `FixMusicAndSFXDataBugs=0`; `$10` is the bug-fixed value. Engine today patches `$90` to `$10` in `Sonic2SfxData.java:120` (KD records this as a deliberate fix); CL:3778-3780 says the opposite was landed and is stale. | S2 | `sound/sfx/BC - Spin Dash Release.asm:11`; `s2.asm:68` (`FixMusicAndSFXDataBugs = fixBugs`) | unverified (driver/data claim); known-bug (engine divergence documented in KD) | INV `556ab16c0`; KD "Spindash Release Transpose Fix"; LC-S2-2; CL:3778-3780 |
| REQ-05 | S1 `PlaySoundID` dispatches normal SFX (`$A0-$CF`) and special SFX (`$D0`) through disjoint pointer tables. | S1 | `PlaySoundID` `:676`, `Sound_PlaySFX` `:977`, `Sound_PlaySpecial` `:1117` | unverified | CL:7570 |
| REQ-06 | S2 resolves a music id through `zMasterPlaylist` and per-bank `MusicPoint` tables inside the Saxman-compressed driver blob. Engine today uses a hardcoded REV01 offset map and its `Sonic2Music` ids are shifted relative to the playlist order. | S2 | `zMasterPlaylist` `:3823`; `sound/_smps2asm_inc.asm` | known-bug (documented divergence with removal condition) | KD "S2 Music Offsets Resolved from Hardcoded REV01 Table" |
| REQ-07 | S3K `FF 01` (`SND_CMD`), `FF 02` (`MUS_PAUSE`) and `FF 03` (`COPY_MEM`) meta commands are reached by no shipped S&K/S3 music or SFX stream. | S3K | (research: `docs/architecture/research/audio/2026-08-08-s3k-smps-meta-command-reachability.md`) | unverified | CL:4929-4940 |

## 4. 1-up / music-override save and restore (`override`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| OVR-01 | The driver owns exactly one save slot, belonging to the 1-up jingle: S1 `Sound_PlayBGM` backs up `v_1up_ram` and sets `f_1up_playing`; S3K `zPlayMusic` copies `zTracksStart` to `zTracksSaveStart` and sets `zFadeToPrevFlag`. There is no stack. | S1, S3K (S2 by analogy) | S1 `Sound_PlayBGM` `:754`, `v_1up_ram` (ram `:34-85`), `f_1up_playing` (ram `:59`); S3K `zPlayMusic` `:1717`, `zTracksSaveStart` `:202`, `zFadeToPrevFlag` `:145`; S2 `zTracksSaveStart` `:215` | unverified | CL:6542 |
| OVR-02 | Any other music request abandons the saved song: S1 by `clr.b f_1up_playing`, S3K by `zStopAllSound` zeroing the whole backup area. Invincibility and Super are ordinary music, not overrides. | S1, S3K | S1 `Sound_PlayBGM` `:754`; S3K `zStopAllSound` `:2460` | unverified | CL:6542 |
| OVR-03 | When the 1-up jingle starts, all active SFX stop and new SFX are blocked until the driver's restore boundary. | S1, S2, S3K | S3K `zUpdateMusic` `:658-680` (1-up queue clearing), `zFadeInToPrevious` `:2725`; S1/S2 1-up save/restore | unverified | INV `1ad387c2d`; LC-X6; LC-S1-4 |
| OVR-04 | Saved priority across a 1-up: S1 clears the saved priority; S2 under `FixDriverBugs=0` LDIR-copies `SFXPriorityVal` before clearing it, so the restore reinstates the stale latch. | S1, S2 | S2 `zAbsVar.SFXPriorityVal` `:143`, `zTracksSaveStart` `:215`; S1 `Sound_PlayBGM` `:754` | unverified | INV `1ad387c2d`; LC-S2-6 |
| OVR-05 | SFX reopen immediately on song restore in S3K, but only after the fade-in in S1/S2. | S1, S2, S3K | S3K `zFadeInToPrevious` `:2725`; S1 `cfFadeInToPrevious` `:2166`; S2 `cfFadeInToPrevious` `:3084` | unverified | INV `1ad387c2d` |
| OVR-06 | S3K plays the 1-up jingle at normal speed: `zTempoSpeedup` is saved into `zTempoSpeedupSave` when the jingle loads and restored with the displaced song. | S3K | `zTempoSpeedupSave` `:161`; save `:1750-1753`; restore `:2731`; fade-in normal speed `:2473` | unverified | INV `68d0c38fa`; LC-S3K5; CL:3785-3788 |
| OVR-07 | Restoring the displaced song in S3K starts a `$40`-step FM-only fade-in; PSG is excluded. S1/S2 restore-fade shape is unspecified. | S3K (S1/S2 open) | `zFadeInToPrevious` `:2725`, `zDoMusicFadeIn` `:2393` | unverified | INV `7ac56b1b4`, `1ad387c2d`; LC-S3K5; CL:3791-3792 |
| OVR-08 | S1 `cfFadeInToPrevious` under `FixBugs=0` does not repair `$2B` (DAC enable) when restoring, so the jingle's DAC mode persists and FM6 can stay inaudible. | S1 | `cfFadeInToPrevious` `:2166` | unverified | INV `1465d9b5e`; LC-S1-4 |
| OVR-09 | A repeated 1-up while the first is still playing is stable (saved-song identity preserved, no double save). | S1, S2, S3K | (aggregate of OVR-01..07) | unverified | LC-X6; LC-S1-4; LC-S2-6; LC-S3K5 |
| OVR-10 | The S3K special-stage (Blue Sphere) music starts at normal tempo even when speed shoes were active in the level; the outgoing level fade is unchanged. | S3K | (unnamed) | unverified | LC-S3K9 ("Enter a Blue Sphere stage while speed shoes are active", retest pending) |

## 5. Fades (`fade`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| FADE-01 | Fade-out channel set: S3K halts DAC and PSG immediately and fades FM only; the S1/S2 channel set is unspecified. | S3K (S1/S2 open) | `zFadeOutMusic` `:2307-2330` (`jp zPSGSilenceAll` `:2323`), `zDoMusicFadeOut` `:2331` | unverified | INV `442c3cd70`; LC-S3K6; CL:3784 |
| FADE-02 | S1 stops normal and special SFX when a fade-out starts. | S1 | `FadeOutMusic` `:1360` | unverified | INV `442c3cd70`; LC-S1-5; CL:3784 |
| FADE-03 | S1 and S2 clear the speed-shoes state on fade-out. | S1, S2 | S1 `FadeOutMusic` `:1360`; S2 `zFadeOutMusic` `:2423` | unverified | INV `442c3cd70`; CL:3785 |
| FADE-04 | On the terminal fade count every driver stops all audio without applying a final volume step. | S1, S2, S3K | S3K `zDoMusicFadeOut` `:2331`; S1/S2 fade routines | unverified | INV `442c3cd70`; LC-X4; CL:3785-3786 |
| FADE-05 | Fade length and step: S3K music fade-out is 40 steps at delay 6 ("40-step, delay-6"); the S1/S2 equivalent entry cue uses a delay-3 fade. | S3K vs S1/S2 | S3K `zFadeDelay` `:137`, `zFadeDelayTimeout` `:138`, `zFadeOutMusic` `:2307` | unverified | LC-S3K6 ("40-step delayed fade"); CL:306-309 |
| FADE-06 | S3K plays `sfx_EnterSS` before the special-stage entry fade. | S3K | (68k side; unnamed) | unverified | CL:306-309 |

## 6. Pause and unpause (`pause`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| PAUSE-01 | S1 pause pans FM1-6 to zero, keys them off and silences PSG; resume restores pan without a voice reload. | S1 | `PauseMusic` `:555`, `.unpausemusic` `:584`, `.unpausedallfm` `:628` | unverified | INV `9aa3acc35`; LC-S1-3 |
| PAUSE-02 | S2 pause under `FixDriverBugs=0` runs `zFMSilenceAll`, which destructively writes `$FF` to `$30-$8F` on both ports; voices are reloaded on resume. | S2 | `zPauseMusic` `:1422`, `.unpause` `:1432`, `zFMSilenceAll` `:2518` | unverified | INV `9aa3acc35`; LC-S2-5 |
| PAUSE-03 | S3K pause mutes FM1-5, leaves FM6/DAC running, and calls `zPSGSilenceAll` redundantly. | S3K | `zPauseAudio` `:2541-2560` (redundant call `:2543`), `zPSGSilenceAll` `:2587` | unverified | INV `9aa3acc35`; LC-S3K4 |
| PAUSE-04 | While paused, track service stops but an already-started DAC sample keeps playing. | S3K (per game open) | `zPauseUnpause` `:2232`, `zPauseAudio` `:2541` | unverified | INV `76855b939`; LC-X3 |
| PAUSE-05 | Resume re-sends a definite subset of FM state (pan/AMS/FMS only vs full instrument), per game. | S1, S2, S3K | S1 `:584-628`; S2 `.unpause` `:1432`; S3K `zPauseUnpause` `:2232` | unverified | INV `9aa3acc35`; LC-X3 |

## 7. FM voice upload and register write order (`voice`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| VOICE-01 | S2 and S3K upload voices using their driver's own register tables and write order; S2 preserves `FixDriverBugs=0` 8-bit total-level behaviour; S3K has a distinct raw voice traversal and SSG-EG register order. | S2, S3K | S2/S3K voice-set routines (unnamed) | unverified | CL:3847-3851 |
| VOICE-02 | An S1 FM SFX takes its channel through a visible SetVoice, note-off, frequency, note-on sequence; it does not upload its instrument into a still-music-owned channel or clear chip envelope/feedback state on acquisition. | S1 | S1 `Sound_PlaySFX` `:977` and voice-set routine (unnamed) | unverified | CL:3852-3857 |
| VOICE-03 | S1 GHZ music: initialisation writes, 68k voice-upload / TL order, PSG rest and maximum-note semantics, note-fill exit behaviour, tied-note keying and modulation phase match the `FixBugs=0` driver over a 14,690-tick cycle. | S1 | S1 `UpdateMusic` `:147` and its flag handlers | unverified | CL:3877-3882; LC-S1-1 |
| VOICE-04 | S3K Blue Sphere pickup: both notes keep the `$05` then `$0A` carrier attenuation; the admission key-off / SSG-EG clear happens before the following driver update's `RR=FF` / voice / key-on phase; the first note emits one final modulated frequency before key-on; replacing another FM5 SFX does not transiently upload the music voice; the upload is 34 writes per pickup with a definite relative-cycle spacing. | S3K | `zPlaySound` `:1975`, `zUpdateSFXTracks` `:727` | known-bug (listening gate open) | S3KB "Blue Sphere FM Pickup Onset"; LC-S3K11 |
| VOICE-05 | FM register writes are paced by the chip's busy window; a busy-polling driver holds each strobe for the 32-cycle busy window plus two clocks. Two `$28` key writes closer than that lose the first. | S1, S2, S3K | (chip; the driver's busy-poll loops unnamed) | resolved-by-chip-cores (engine facade paces at 34 internal cycles; whether each driver actually polls busy is a driver question and remains unverified) | CL:38-49 |

## 8. Sequence bytecode, modulation and envelopes (`seq`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| SEQ-01 | S3K modulation-envelope bytes `$80-$84` are commands; `$85-$FF` are signed pitch deltas (negative values must not freeze the envelope). | S3K | `zDoModulation` `:1279` | unverified | INV `10b27e755`; LC-S3K1; CL:3781-3782 |
| SEQ-02 | Under `fix_sndbugs=0`, `$82`/`$84` fetch their operand via `INC BC / LD A,(BC)` with BC still holding the envelope index, so the operand comes from low Z80 RAM at index+1, not from the envelope data. | S3K | `zDoModulation` `:1279`, `fix_sndbugs` blocks `:1303`, `:1349` | unverified | INV `d08af3083`; LC-S3K1; CL:3782-3783 |
| SEQ-03 | S3K has exactly 8 modulation-envelope pointers and `$27` PSG volume-envelope pointers. An earlier engine comment cited "Mod. Pointer List: 130E (W, 3C)"; the two disagree. | S3K | pointer tables reached from `zPlaySound_Bankswitch` `:1928` | unverified | INV `6d393dcf7`; CL:3759-3760 |
| SEQ-04 | S1 PSG envelopes each end in a `$80` hold terminator. | S1 | S1 PSG envelope table (unnamed) | unverified | INV `6d393dcf7`; CL:3758 |
| SEQ-05 | PSG envelope cadence, note fill, and tied notes are advanced per service tick per the game's driver (part of the "music for 60 s" audible aggregate). | S1, S2, S3K | (aggregate) | unverified | LC-X1; LC-S1-1 |

## 9. DAC and PCM (`dac`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| DAC-01 | Z80 DAC playback streams each decoded sample as a `$2A` write; the per-byte cost is a fixed instruction path plus `djnz` pitch iterations, `(baseCycles + 26 * (rate - 1)) / 2` Z80 cycles per sample. The two halves of a byte are not equally long (S1: 124 and 177 cycles before the pitch loop). The loop stalls while the chip bus is busy. | S1, S2, S3K | S1 `sound/z80.asm` `zPlayPCMLoop` `:115`; S2 `zWriteToDAC` `:680`; S3K `zPlayDigitalAudio` `:4258` | unverified | KD §31 "DAC cadence" |
| DAC-02 | S2 `fixBugs=0` `zWriteToDAC` costs 295 Z80 cycles per two decoded samples. | S2 | `zWriteToDAC` `:680` | resolved-by-loader | `TestSonic2DacCadence`; KD §31; INV `0b269a9be` |
| DAC-03 | S1 (301) and S3K (297) DAC base cycles are engine constants with no recorded derivation. | S1, S3K | S1 `zPlayPCMLoop` `:115`; S3K `zPlayDigitalAudio` `:4258` | unverified | INV `0b269a9be` (by implication); KD §31 |
| DAC-04 | The S3K SEGA chant is unsigned PCM streamed byte-by-byte to the YM2612 DAC (`$2B` enable, `$2A` data) at the region's Z80 clock, not a host-rate sample. | S3K | `zPlaySEGAPCM` `:4372` | unverified | INV `4e5e2def3`; LC-S3K7 |
| DAC-05 | Starting the SEGA chant stops music, overrides and every SFX first; under `fix_sndbugs=0` the stop-SEGA path leaves the driver silent rather than restoring the discarded voices. The INV names `StopSEGA`; no `zStopSEGAPCM` label exists (see §15). | S3K | `zPlaySEGAPCM` `:4372`, `zStopAllSound` `:2460` | unverified | INV `f266983a3`; LC-S3K7 |
| DAC-06 | DAC drum rhythm within music and continuous DAC clocking through pause are audible aggregates of DAC-01 and PAUSE-04. | S1, S2, S3K | (aggregate) | unverified | LC-X1; LC-X3 |

## 10. Data framing and catalogs (`data`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| DATA-01 | S2 Saxman song payload lengths are little-endian and S2 has exactly four uncompressed playlist entries. | S2 | `zMasterPlaylist` `:3823` and the Saxman framing | unverified | INV `6d393dcf7`; CL:3756-3758 |
| DATA-02 | A DAC table that cannot be read is not a playable catalog (loader should fail closed, not yield an empty catalog). | S1, S2, S3K | (loader policy, no driver routine) | unverified | INV `6d393dcf7`; CL:3761-3762 |
| DATA-03 | S3K native SFX banks span `33-DF` (173 entries each); S&K `DC` is CreditsK music while `DD-DF` are SFX; the S3 driver dispatches `DC-DF` as SFX; `9B`/`AD` payloads differ between banks. | S3K | S3K SFX pointer tables (unnamed) | unverified | CL:4933-4936 |

## 11. Chip-level behaviour and hardware clocks (`chip`)

| ID | Claim | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| CHIP-01 | On PAL hardware the YM2612 runs at 53,203,424/7 Hz and the PSG at 53,203,424/15 Hz (NTSC: 53,693,175/7 and /15), so pitch and DAC timing differ from NTSC. Engine today hard-codes NTSC in both cores. | all | hardware clocks (no driver routine) | unverified (not modelled by the new cores) | INV `a0e346e41`; LC-S2-7; LC-S3K8 |
| CHIP-02 | PSG noise LFSR shifts on rising edges only (one shift per rising edge of the noise clock). Engine today: `PsgChip` defaults to the hardware rule, but `config.yaml:58` / `CONFIGURATION.md:280` still default `audio.psgNoiseShiftEveryToggle` to `true`, and the LC reference setup asks for `false`. | all | (chip) | resolved-by-chip-cores (residual config default) | INV `8d2f0dcf2`; KD §32; CL:135-157 |
| CHIP-03 | DAC output is stepped, not interpolated. Engine today defaults `audio.dacInterpolate` to `true` (`config.yaml:56`), a presentation option with no hardware counterpart; the LC reference setup asks for `false`. | all | (chip) | resolved-by-chip-cores (residual config default) | INV `8d2f0dcf2`; KD §31 |
| CHIP-04 | Tone-2-linked noise with tone 2 at period 0/1 keeps clocking (one shift per two ticks in the engine); whether real silicon shifts once per tick or once per two ticks is unmeasured. 22 SFX across the three games sit in that state. | all | (chip) | resolved-by-chip-cores (rate is an open hardware question) | KD §32 |
| CHIP-05 | YM2612 register-slot permutation is applied once at the register boundary; envelope reset phase, decay-to-sustain overshoot and discrete-chip carrier quantisation follow the discrete chip. | all | (chip) | resolved-by-chip-cores | CL:3872-3876 |
| CHIP-06 | YM2612 output scale (6144 per full-scale channel after the facade shift) and resting level (+288 LSB after master gain in YM2612 mode). | all | (chip) | resolved-by-chip-cores | KD §31 |
| CHIP-07 | The console mixes YM2612 and SN76489 at a fixed analogue ratio; the engine's 38 % PSG preamp is pre-rewrite parity, not a hardware calibration, and no two-chip capture exists. | all | (analogue mix, no driver routine) | known-bug (uncalibrated; removal condition in KD §33) | KD §33; CL:123-133; LC-X1 ("FM/PSG balance") |
| CHIP-08 | `YM2612 DAC` rendering of the SEGA chant vs host-linear sample — see DAC-04. Engine today renders SEGA PCM as a host-linear one-shot (INV table, `SampleBackedVoice`). | S3K | `zPlaySEGAPCM` `:4372` | unverified | INV `4e5e2def3` |

## 12. Known audible defects and engine-side divergences (`defect`)

| ID | Defect / divergence | Game(s) | Purported ROM anchor | Status | Source |
|---|---|---|---|---|---|
| DEF-01 | Blue Sphere FM pickup onset: repeated pickups could sound harder/more abrupt than retail; automated write-spacing parity claimed complete, human listening gate still open, implementation branch held out of integration. | S3K | `zPlaySound` `:1975` | known-bug | S3KB "Blue Sphere FM Pickup Onset"; LC-S3K11 |
| DEF-02 | CNZ1 miniboss arena entry fades music out but never plays the miniboss theme; silence until boss defeat. Game-side request gap, not a driver defect. | S3K | `sonic3k.asm:144841` (`cmd_FadeOut` via `Play_Music`) | known-bug | S3KB "CNZ1 Miniboss Arena Entry — Music Play-In Missing" |
| DEF-03 | The engine's S3K SEGA screen (SEGA sound + 180-frame hold) has no ROM counterpart; the locked-on ROM has no SEGA screen sequence and no SEGA sound command in its boot path. | S3K | `sonic3k.asm:5387-5388`, `s3.asm:4768-4770`, `sonic3k.asm:454-456` | known-bug (documented engine addition; product decision) | S3KD "SEGA Screen" |
| DEF-04 | FM:PSG balance is uncalibrated against hardware (see CHIP-07). | all | — | known-bug | KD §33 |
| DEF-05 | S2 spindash-release FM5 transpose is patched `$90` -> `$10` by the engine (see REQ-04). | S2 | `BC - Spin Dash Release.asm:11` | known-bug (documented) | KD "Spindash Release Transpose Fix" |
| DEF-06 | S2 music offsets come from a hardcoded REV01 map, and engine music ids are shifted relative to `zMasterPlaylist` (see REQ-06). | S2 | `zMasterPlaylist` `:3823` | known-bug (documented) | KD "S2 Music Offsets" |
| DEF-07 | Gloop toggle lives in the object, not the driver (see REQ-02). | S2 | `zPlaySound_CheckGloop` `:2138` | known-bug (documented intentional) | KD "Gloop Sound Toggle" |
| DEF-08 | S2 `zWriteToDAC` cadence: the loader carries the retail 295-cycle byte budget (see DAC-02). | S2 | `zWriteToDAC` `:680` | resolved | `TestSonic2DacCadence`; KD §31 |
| DEF-09 | Lost key-ons at the old 13-cycle write pacing (S1 GHZ 27/189, S2 EHZ 65/138, S3K AIZ1 12/357; LZ note at frame 102982 silent). | all | (chip pacing) | resolved-by-chip-cores | CL:38-49 |
| DEF-10 | Music stopped for the rest of an act after 1-up / invincibility / Super (engine held a stack where the driver has one slot). | S1, S2, S3K | see OVR-01/02 | known-bug (recorded as fixed on develop; the driver claim behind the fix remains OVR-01/02 unverified) | CL:6542 |
| DEF-11 | Special-stage rings all played through the right speaker for raw-id callers (Blue Sphere stage, Mega Chopper). | S3K (all games' raw-id path) | see REQ-01 | known-bug (recorded as fixed on develop, PR #179; listener PASS on LC-S3K10 2026-08-21 predates that fix) | CL:17-27; LC-S3K10 |
| DEF-12 | S1 SFX `$D0` (Waterfall) was dispatched through the normal table by coincidence of table adjacency. | S1 | see REQ-05 | known-bug (recorded as fixed on develop) | CL:7570 |

## 13. Listening-checklist row map

All 29 LC rows, in file order, mapped to the IDs above so that a spec or oracle covering
the IDs covers the row. Rows are unchecked in the source unless noted.

| LC row | Scene (abridged) | Covered by |
|---|---|---|
| LC-X1 | Normal music 60 s: tempo, pitch, FM/PSG balance, DAC rhythm, loop, modulation, envelopes | CAD-01..05, SEQ-01..05, DAC-01, DAC-06, CHIP-07 |
| LC-X2 | Overlapping FM and PSG SFX | ADM-01..08 |
| LC-X3 | Pause during music / SFX / DAC | PAUSE-01..05, DAC-06 |
| LC-X4 | Fade start through terminal cleanup | FADE-01..05 |
| LC-X5 | Speed-up enable and disable | CAD-05, CAD-12 |
| LC-X6 | First and repeated 1-up | OVR-01..09 |
| LC-S1-1 | GHZ music with drums, then ring/jump/spring/explosion SFX | CAD-10, CAD-11, VOICE-02, VOICE-03, SEQ-04, DAC-03 |
| LC-S1-2 | Two contending SFX, then BGM replacement | ADM-01, ADM-06 |
| LC-S1-3 | Pause/resume with FM, PSG, DAC active | PAUSE-01, PAUSE-04 |
| LC-S1-4 | 1-up while SFX owns a channel, retrigger before restore | OVR-03, OVR-04, OVR-05, OVR-08, OVR-09 |
| LC-S1-5 | Level/death fade while an SFX is active | FADE-02, FADE-03, FADE-04 |
| LC-S2-1 | EHZ/CPZ 60 s with drums | CAD-02, DAC-02 |
| LC-S2-2 | Spindash charge/release/timeout | REQ-03, REQ-04 |
| LC-S2-3 | Lower/equal/higher-priority SFX on free and occupied roles | ADM-01 |
| LC-S2-4 | Replace BGM while an SFX is active | ADM-05, ADM-07 |
| LC-S2-5 | Pause/resume during music | PAUSE-02, PAUSE-05 |
| LC-S2-6 | Speed shoes and 1-up in both orders, repeated 1-up | OVR-04, OVR-09, CAD-12 |
| LC-S2-7 | Eligible song in PAL mode | CAD-06, CHIP-01 |
| LC-S3K1 | AIZ then CNZ/LBZ music with modulation envelopes | CAD-01, SEQ-01, SEQ-02 |
| LC-S3K2 | Overlapping FM/PSG SFX during music, each ending | CAD-09, ADM-03, ADM-04 |
| LC-S3K3 | Speed shoes for at least eight VInts | CAD-05 |
| LC-S3K4 | Pause/resume with FM6/DAC and PSG active | PAUSE-03, PAUSE-04, PAUSE-05 |
| LC-S3K5 | First and repeated 1-up while speed-up active | OVR-06, OVR-07, OVR-09 |
| LC-S3K6 | Fade-out while DAC, PSG and FM active | FADE-01, FADE-04, FADE-05 |
| LC-S3K7 | Boot SEGA chant, then stop/skip | DAC-04, DAC-05, CHIP-08, DEF-03 |
| LC-S3K8 | Locked-on PAL through two repeat boundaries | CAD-07, CHIP-01 |
| LC-S3K9 | Enter Blue Sphere with speed shoes active (retest pending) | OVR-10, FADE-05, FADE-06 |
| LC-S3K10 | Collect special-stage rings (PASS, 2026-08-21) | REQ-01, DEF-11 |
| LC-S3K11 | Isolated and rapid Blue Sphere pickups | VOICE-04, ADM-09, DEF-01 |

## 14. Coverage expectations for the specs and the oracle

- Every `unverified` row is a behaviour a per-game driver spec must either derive from the
  disassembly (with the owning routine cited) or explicitly mark as not applicable to that
  game. A spec that is silent on a row has not covered it.
- Every `unverified` row whose claim is observable at the chip-write boundary is a candidate
  oracle assertion. Rows that are aggregates (ADM-08, SEQ-05, DAC-06, OVR-09) are covered by
  asserting their constituents.
- `known-bug` rows with a documented divergence (REQ-02/04/06, DEF-03/05/06/07/08) need a
  spec statement of the ROM behaviour so the divergence stays a *chosen* one; the oracle
  should assert the ROM behaviour and the engine's divergence should be an expected,
  named difference, not a silent pass.
- `resolved-by-chip-cores` rows are out of the driver spec's scope, but CHIP-01 (PAL clocks)
  and the two residual config defaults (CHIP-02, CHIP-03) are not settled by the cores and
  belong to whoever owns region and presentation configuration.

## 15. Anchors the sources name that do not exist as labels (open questions)

These are facts about a `grep` over the local disassemblies on 2026-08-30, not corrections.
The claim rows above keep the source's wording; resolving each name is for the RE work.

| Source name | Where cited | Grep result | Nearest candidates found |
|---|---|---|---|
| S1 `DOTEMPO` | INV (CAD-02, CAD-03) | not a label in `s1.sounddriver.asm` | `UpdateMusic` `:174-176` (`subq.b #1,SMPS_RAM.v_main_tempo_timeout` / `jsr TempoWait`), `.tempoloop` `:1555`, `cfSetTempo` `:2256` |
| S1 `PlayMusic` | INV (CAD-01) | not a label | `Sound_PlayBGM` `:754` |
| S1 `Sound_Play`/`UpdateSFX` | — | not labels | `PlaySoundID` `:676`, `Sound_PlaySFX` `:977`, `Sound_PlaySpecial` `:1117` |
| S2 `zSFXPriorityVal` | INV (ADM-01, OVR-04) | not a standalone label | struct field `zAbsVar.SFXPriorityVal` (`zVar` `:143`), routine `zSFXPriority` `:3716` |
| S2 `zSpeedupTimeout` | INV (CAD-05 by analogy) | absent from S2 | `zAbsVar.TempoTurbo` `:161`, `SpeedUpFlag` `:162`, `zSpeedUpMusic` `:2661/:2686` |
| S2 `zPauseUnpause` | INV (PAUSE-02) | absent from S2 | `zPauseMusic` `:1422`, `.unpause` `:1432` |
| S2 `zFadeInToPrevious` / `zDoMusicFadeOut` | INV (OVR-05, FADE-04) | absent from S2 | `cfFadeInToPrevious` `:3084`, `zUpdateFadeIn` `:2725`, `zFadeOutMusic` `:2423` |
| S2 `.dac_playback_loop` | INV (DAC-02) | not found as a label | `zWriteToDAC` `:680`; the sample-rate-to-`djnz` helper comment at `:311` |
| S2 PAL "counter" | INV (CAD-06) | no `zPalFlag`/`zPalDblUpdCounter` in S2 | `zAbsVar.IsPalFlag` `:165`, `zPalModeByte` `:324`, `zPALUpdTick`, `.pal_timer` `:438` |
| S3K `zDoSpeedUp` | INV (CAD-05) | not a label | the speed-up tail at `:745-757` following `zTrackUpdLoop` `:734` |
| S3K `zStopSEGAPCM` / "StopSEGA" | INV (DAC-05) | not a label | `zStopAllSound` `:2460`; the `zPlaySEGAPCM` `:4372` tail |
| S1/S2 ring-speaker equivalents | REQ-01 | not searched by name here | open |

Further open questions raised by the sources themselves:

1. SEQ-03 — 8 vs `$3C` modulation-envelope pointers: the INV records the disagreement; the
   pointer table reached from `zPlaySound_Bankswitch` `:1928` decides it.
2. DAC-02/DAC-03 — 295 vs 288 for S2, and the undocumented 301 (S1) / 297 (S3K) base cycles.
3. REQ-03 — whether S3K has any driver-side spindash-rev transpose at all.
4. OVR-07 / FADE-01 — the S1 and S2 restore-fade and fade-out channel sets are unspecified by
   every source.
5. CHIP-04 — the linked-noise clock rate at tone-2 period 0/1 on real silicon.
6. REQ-01 — whether the S1 and S2 drivers alternate ring speakers the way S3K's does (the
   engine applies the alternation to all three games; the anchors cited are S3K-only).

## 16. Ledger observations (for the documentation owners, not acted on here)

- `CHANGELOG.0.6.md:3756-3792` describes the reverted branch's driver changes (catalog
  fail-closed, cadence, priority latch, `$90` transpose retention, 295-cycle DPCM, reference
  chip defaults, signed modulation, bogus-`BC` operands, fade lifecycle, S3K 1-up speed,
  spindash ladder, FM-only restore fade) with no *Superseded* or *Reverted* marker, while the
  INV records that none of the corresponding runtime code is present and
  `docs/status/known-discrepancies.md` documents the opposite `$10` patch that the engine
  actually applies (`Sonic2SfxData.java:120`).
- `docs/architecture/validation/audio/2026-08-21-smps-playback-authenticity-validation.md`
  and the roadmap describe the same work as delivered; they were not digested as claim
  sources beyond the Phase 1 wording, and their status statements should not be read as
  current.
- LC-S3K10 (special-stage ring alternation) is marked PASS on 2026-08-21, before the
  2026-08-29 develop fix (CL:17-27) that changed the raw-id path it exercises.
