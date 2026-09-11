# Sonic 1 sound driver: ROM routine map (SMPS 68k Type 1b, shipped `FixBugs = 0`)

Date: 2026-08-30
Scope: the 68k sound driver linked into Sonic 1 REV01 (`SoundDriver: include
"s1.sounddriver.asm"`, `sonic.asm:5229`) and the Z80 DAC program it loads
(`sound/z80.asm`). Every routine is anchored to the disassembly line that defines it.

## Source rule

Written from the Sonic 1 disassembly only:

- `docs/s1disasm/s1.sounddriver.asm` (driver code, tables, song/SFX includes)
- `docs/s1disasm/s1.sounddriver.ram.asm` (track struct and driver RAM layout)
- `docs/s1disasm/sound/z80.asm` (Z80 DAC/PCM program, Kosinski-compressed in ROM)
- `docs/s1disasm/sound/_smps2asm_inc.asm` (byte layout of the song/SFX macros)
- `docs/s1disasm/sonic.asm`, `_Constants.asm`, `_Variables.asm`, `Macros.asm`,
  `_inc/Queue Sound Routines.asm`, `_inc/PauseGame.asm`, and the object files that
  issue sound requests (68k-side callers only)
- `docs/s1disasm/sonic.lst` for absolute RAM/ROM addresses, and the local
  `s1.gen` ROM (CRC32 `AFE05EEE`) to spot-check table bytes

No emulator source, no SMPS documentation that paraphrases emulator code, and no
engine code was used as evidence. Where the source leaves something unpinned it is
listed under "Open questions" rather than resolved from memory.

Anchor notation used below (all paths relative to `docs/s1disasm/`):

| Prefix | File |
|---|---|
| `SD:` | `s1.sounddriver.asm` |
| `RAM:` | `s1.sounddriver.ram.asm` |
| `Z80:` | `sound/z80.asm` |
| `S:` | `sonic.asm` |
| `C:` | `_Constants.asm` |
| `INC:` | `sound/_smps2asm_inc.asm` |

ROM addresses (`loc_`/`sub_` names in the source comments and `sonic.lst`) are quoted
where a routine has one; they are addresses, not line numbers.

## 1. FixBugs status

`FixBugs = 0` (`S:20`). The shipped ROM takes the `else` (or absent) branch of every
conditional below; `FixMusicAndSFXDataBugs = FixBugs` (`SD:2637`) extends the same
switch to song data. The shipped path is what the engine must model.

| # | Site | Shipped (`FixBugs = 0`) path | What the fixed branch would do |
|---|---|---|---|
| 1 | `UpdateMusic` `SD:189-196` | `tst.w v_soundqueue0` tests only slots 0 and 1; a request written to `v_soundqueue2` alone is never noticed | OR all three slots |
| 2 | `PlaySoundID` `SD:682-689` | music range check is `<= $9F`; IDs `$94-$9F` index past `MusicIndex` and crash | check `<= bgm__Last` (`$93`) |
| 3 | `PlaySoundID` `SD:697-707` | special-SFX range is `$D0-$DF` via `blo`; `$D1-$DF` index past `SpecSoundIndex` and crash | check `<= spec__Last` (`$D0`) |
| 4 | `Sound_PlayBGM` `SD:817-837` | register set-up order: `d7` (FM count) is loaded and tested for zero *before* `d4` (tempo divider), `d6` (track stride), `d5` (first duration) are initialised, so a song with zero FM/DAC tracks would reach the PSG loader with stale `d4/d5/d6` | initialise `d4/d6/d5` first. No shipped song has zero FM/DAC tracks (all headers declare `$06` or `$07`), so the shipped path is not exercised |
| 5 | `Sound_PlaySFX` `SD:1010-1015` | no `moveq #0,d7` before `move.b (a1)+,d7` (track count); the upper bytes of `d7` still hold the SFX index `<<2` from `SD:1003`, harmless while that value fits in a byte (all shipped SFX indices are `< $40`) | clear `d7` |
| 6 | `Sound_PlaySpecial` `SD:1134-1138` | same missing `moveq #0,d7` | clear `d7` |
| 7 | `StopSFX` `SD:1243-1248` | when an SFX FM4 track is stopped while the special-SFX FM4 track is playing, `a3` is **not** saved before `a5` is redirected; after `SetVoice` the loop restores `a5` from a stale `a3` | `movea.l a5,a3` |
| 8 | `StopSFX` `SD:1265-1270` | `SetVoice` is entered with only the low byte of `d0` loaded (`move.b VoiceIndex,d0`); the upper bytes are whatever the caller left | `moveq #0,d0` |
| 9 | `StopSFX` `SD:1280-1284` | the PSG restore path does not check that the special PSG3 track is playing before treating channel `$C0`/`$E0` as owned by it | add the `tst.b PlaybackControl(a0)` check that `cfStopTrack` has |
| 10 | `StopSpecialSFX` `SD:1325-1330` | same `SetVoice` byte-only `d0` | `moveq #0,d0` |
| 11 | `StopAllSound` `SD:1469-1474` | clears `$390` bytes (`$000-$38F`): all variables and all track RAM **except offsets `$20-$2F` of the special PSG3 track** (base `$370`): its `VoicePtr` and `LoopCounters` survive a stop | clear `$3A0` bytes |
| 12 | `InitMusicPlayback` `SD:1494-1497`, `SD:1510-1513` | only `v_soundqueue0/1` (one word) are preserved across the RAM clear; a pending `v_soundqueue2` is lost | also preserve slot 2 |
| 13 | `InitMusicPlayback` `SD:1516-1543` | calls `FMSilenceAll` and `PSGSilenceAll`: key-off + TL `$7F` on all six FM channels and silence all four PSG channels, including channels an SFX currently owns | write each music track's channel byte instead and let `.sendfmnoteoff` handle it |
| 14 | `DoFadeIn` `.fadedone` `SD:1652-1662` | after a fade-in completes, the DAC track's `AMSFMSPan` is **not** re-sent to register `$B6`; any `$E0` pan flag the DAC track processed while its override bit was set (see §12) was stored but never written | write `$B6` from `AMSFMSPan` if the DAC track is playing |
| 15 | `PSGDoVolFX` `SD:1938-1952` | envelope byte with bit 7 set: only `$80` holds (`VolEnvHold`); any other negative byte falls through to `.gotflutter` and is added to the volume | treat every negative byte as hold |
| 16 | `PSGNoteOff` `SD:2005-2013` | keying off PSG3 (`$DF`) does **not** also silence the noise channel | also write `$FF` |
| 17 | `cfFadeInToPrevious` `SD:2175-2181` | after restoring the saved song, register `$2B` (DAC enable) is left as-is | write `$2B = 0` (DAC off) — see open question 5 |
| 18 | `cfStopSpecialFM4` `SD:2298-2303` | `SetVoice` byte-only `d0` (in this path `d0` was fully written by `move.b VoiceControl` after `moveq #0`, so the upper bytes are zero) | `moveq #0,d0` |
| 19 | `SendVoiceTL` `SD:2391-2398` | for an SFX track (`f_voice_selector = $80`) the voice pointer is read from `SMPS_Track.VoicePtr(a6)` — offset `$20` from the **driver RAM base**, i.e. `v_special_voice_ptr` (`RAM:55`) — not from the track. So `cfChangeFMVolume` (`$E6`) on an SFX FM track uploads TL bytes from the *special SFX* voice bank (voice index taken from the SFX track), or from whatever `v_special_voice_ptr` holds | use `VoicePtr(a5)` |
| 20 | Level select `S:2222-2229` | 68k-side workaround: the sound test refuses `$94-$9F` so #2 cannot be triggered from the menu | none needed |
| 21 | Song data `sound/music/Mus83 - MZ.asm:183` | PSG3's first three notes overflow `PSGFrequencies` (index past the 70-entry table reads the bytes that follow it at `SD:2066+`) and play invalid frequencies | lower them an octave |
| 22 | Song data `sound/music/Mus86 - SBZ.asm:128` | FM3 detune is not reset when the intro loops | add `smpsDetune 0` |
| 23 | Song data `sound/music/Mus91 - Credits.asm:731` | three extra rests and a stray FM-only `$E6 $0C` on a PSG track delay and mute the following notes | delete them |
| 24 | SFX data `sound/sfx/SndBC - Teleport.asm:7` | FM5 track transpose byte is `$90` (the driver masks the index with `$7F` after adding transpose, so the value wraps) | `$10` |

## 2. Memory map

### 2.1 Driver RAM (`SMPS_RAM`, `RAM:33-109`)

`v_snddriver_ram` is at `$FFFFF000` (`_Variables.asm:114`, `sonic.lst:1544`). Offsets
below are relative to it; `a6` holds this base inside the driver (`SD:170`).

| Offset | Abs | Name | Meaning |
|---|---|---|---|
| `$00` | `F000` | `v_sndprio` | current sound priority; a request must be `>=` this (unsigned) to be accepted. Bit 7 of an accepted request's priority means "do not store" (`SD:666-668`) |
| `$01` | `F001` | `v_main_tempo_timeout` | counts down each driver call; when it hits 0 `TempoWait` reloads it and delays every music track one frame |
| `$02` | `F002` | `v_main_tempo` | reload value for the above (music only) |
| `$03` | `F003` | `f_pausemusic` | `1` = pause requested by game, `2` = pause applied, `$80` = unpause requested, `0` = running (§11) |
| `$04` | `F004` | `v_fadeout_counter` | fade-out steps remaining (`$28` at start), 0 = no fade |
| `$05` | `F005` | – | unused |
| `$06` | `F006` | `v_fadeout_delay` | frames until the next fade-out step |
| `$07` | `F007` | `v_communication_byte` | written by flag `$E2`; never read by Sonic 1 |
| `$08` | `F008` | `f_updating_dac` | `$80` while `DACUpdateTrack` is parsing the DAC track (read by `cfStopTrack`) |
| `$09` | `F009` | `v_sound_id` | the single request selected from the queue this frame; `$80` = none. `0` at power-on, which makes the first driver call run `StopAllSound` (§4.3) |
| `$0A` | `F00A` | `v_soundqueue0` | request slot written by `QueueSound1` |
| `$0B` | `F00B` | `v_soundqueue1` | request slot written by `QueueSound2` |
| `$0C` | `F00C` | `v_soundqueue2` | request slot written by `QueueSound3` (dead in shipped ROM, FixBugs #1/#12) |
| `$0D` | `F00D` | – | unused |
| `$0E` | `F00E` | `f_voice_selector` | which voice bank `cfSetVoice`/`SendVoiceTL` use: `$00` music, `$80` SFX (per-track `VoicePtr`), `$40` special SFX (`v_special_voice_ptr`). Set by `UpdateMusic` as it walks the track groups |
| `$0F-$17` | | – | unused |
| `$18` | `F018` | `v_voice_ptr` | absolute pointer to the current song's voice bank |
| `$1C-$1F` | | – | unused |
| `$20` | `F020` | `v_special_voice_ptr` | absolute pointer to the special SFX (`$D0`) voice bank |
| `$24` | `F024` | `f_fadein_flag` | `$80` while fading the restored song in |
| `$25` | `F025` | `v_fadein_delay` | frames until the next fade-in step |
| `$26` | `F026` | `v_fadein_counter` | fade-in steps remaining |
| `$27` | `F027` | `f_1up_playing` | `$80` while the Extra Life jingle has a saved song under it |
| `$28` | `F028` | `v_tempo_mod` | song's normal main tempo (header byte 5) |
| `$29` | `F029` | `v_speeduptempo` | song's speed-shoes tempo (from `SpeedUpIndex`) |
| `$2A` | `F02A` | `f_speedup` | `$80` when speed-shoes tempo is active |
| `$2B` | `F02B` | `v_ring_speaker` | ring SFX side toggle; `0` → next ring plays `$CE` (left), non-zero → `$B5` (right) (§14.4; the RAM comment at `RAM:63` has the sides the other way round, the code is authoritative) |
| `$2C` | `F02C` | `f_push_playing` | `$80` while a push SFX is playing; blocks re-triggers until flag `$ED` clears it |
| `$2D-$3F` | | – | unused |
| `$40` | `F040` | `v_music_dac_track` | music DAC track |
| `$70` | `F070` | `v_music_fm1_track` | music FM1 |
| `$A0` | `F0A0` | `v_music_fm2_track` | music FM2 |
| `$D0` | `F0D0` | `v_music_fm3_track` | music FM3 |
| `$100` | `F100` | `v_music_fm4_track` | music FM4 |
| `$130` | `F130` | `v_music_fm5_track` | music FM5 |
| `$160` | `F160` | `v_music_fm6_track` | music FM6 |
| `$190` | `F190` | `v_music_psg1_track` | music PSG1 |
| `$1C0` | `F1C0` | `v_music_psg2_track` | music PSG2 |
| `$1F0` | `F1F0` | `v_music_psg3_track` | music PSG3 (also noise) |
| `$220` | `F220` | `v_sfx_fm3_track` | SFX FM3 |
| `$250` | `F250` | `v_sfx_fm4_track` | SFX FM4 |
| `$280` | `F280` | `v_sfx_fm5_track` | SFX FM5 |
| `$2B0` | `F2B0` | `v_sfx_psg1_track` | SFX PSG1 |
| `$2E0` | `F2E0` | `v_sfx_psg2_track` | SFX PSG2 |
| `$310` | `F310` | `v_sfx_psg3_track` | SFX PSG3 (also noise) |
| `$340` | `F340` | `v_spcsfx_fm4_track` | special SFX FM4 |
| `$370` | `F370` | `v_spcsfx_psg3_track` | special SFX PSG3 |
| `$3A0` | `F3A0` | `v_1up_ram_copy` | `$220`-byte copy of `$000-$21F` (variables + music tracks) saved when the Extra Life jingle starts |
| `$5C0` | `F5C0` | end | |

Region names: `v_1up_ram` = `$000-$21F` (what the 1-up backup covers, `RAM:34,85`);
`v_track_ram` = `$040-$39F` (18 tracks × `$30`).

### 2.2 Track struct (`SMPS_Track`, `RAM:1-31`), `$30` bytes

| Offset | Name | Size | Meaning |
|---|---|---|---|
| `$00` | `PlaybackControl` | b | bit flags, see below |
| `$01` | `VoiceControl` | b | hardware channel: FM `0,1,2` = part I ch 1-3; `4,5,6` = part II ch 1-3 (bit 2 selects part II, `SD:1714`); DAC track uses `6` (`FMDACInitBytes`, `SD:966`); PSG `$80,$A0,$C0` (latch byte prefix, `PSGInitBytes` `SD:970`); `$E0` = PSG3 running as noise (set by flag `$F3`) |
| `$02` | `TempoDivider` | b | note-length multiplier applied by `SetDuration` |
| `$03` | – | b | unused |
| `$04` | `DataPointer` | l | absolute pointer to the next track byte |
| `$08` | `Transpose` | b | signed semitone offset; loaded as a word together with `Volume` from the header |
| `$09` | `Volume` | b | attenuation: FM added to TL of carrier operators; PSG 0-`$F` |
| `$0A` | `AMSFMSPan` | b | last value written to `$B4+ch` (L/R bits 7-6, AMS 5-4, FMS 2-0) |
| `$0B` | `VoiceIndex` | b | FM: current voice number; PSG: current volume-envelope number (0 = none) |
| `$0C` | `VolEnvIndex` | b | PSG envelope read position (cleared on every new note, even on FM tracks `SD:442`) |
| `$0D` | `StackPointer` | b | gosub stack pointer, initialised to `$30` (= struct length), pushes grow downward by 4 |
| `$0E` | `DurationTimeout` | b | frames left in the current note |
| `$0F` | `SavedDuration` | b | last explicit duration (after divider), reused when a note has no duration byte |
| `$0F` | `SavedDAC` | b | DAC only: alias of the above; last DAC sample id |
| `$10` | `Freq` | w | FM: block/F-number; PSG: 10-bit period; `0` (FM) / `-1` (PSG) = rest |
| `$12` | `NoteTimeout` | b | note-fill countdown (`$E8`) |
| `$13` | `NoteTimeoutMaster` | b | note-fill reload value |
| `$14` | `ModulationPtr` | l | pointer to the 4 modulation parameter bytes in the track data |
| `$18` | `ModulationWait` | b | frames before modulation starts |
| `$19` | `ModulationSpeed` | b | frames between modulation steps |
| `$1A` | `ModulationDelta` | b | signed change per step |
| `$1B` | `ModulationSteps` | b | steps left in this half-cycle |
| `$1C` | `ModulationVal` | w | accumulated frequency offset |
| `$1E` | `Detune` | b | signed frequency offset added on every frequency write |
| `$1F` | `PSGNoise` | b | PSG: noise control byte (`$E0-$E7`) latched by `$F3` |
| `$1F` | `FeedbackAlgo` | b | FM: alias; voice byte 0 (`$B0` value) |
| `$20` | `VoicePtr` | l | FM SFX only: that SFX's voice bank |
| `$24` | `LoopCounters` | 12 b | `$F7` loop counters, indexed by the loop's first parameter |
| `$30` | `GoSubStack` | – | conceptual label; the stack actually lives *inside* the struct: the first `$F8` push lands at `$2C` (`StackPointer $30 - 4`), the second at `$28`, overlapping `LoopCounters[8..11]` then `[4..7]` |

`PlaybackControl` bits (all reads/writes in the driver):

| Bit | Meaning | Set by | Cleared by |
|---|---|---|---|
| 7 | track playing | song/SFX load (`SD:842,893`), SFX header word (`SD:1055,1164`) | `cfStopTrack`, `cfStopSpecialFM4`, `StopSFX`, `StopSpecialSFX`, fade-out end of range, `Sound_PlayBGM` 1-up path (SFX tracks), `FadeOutMusic` (DAC track) |
| 4 | hold: do not key-off before / key-on the next note (`$E7`) | `cfHoldNote` | every note boundary (`SD:352,1816`), `cfStopTrack` |
| 3 | modulation enabled | `cfModulation`, `cfEnableModulation` | `cfDisableModulation` |
| 2 | channel is overridden (SFX owns the hardware); all hardware writes from this track are suppressed | SFX/special load marking the music track; `Sound_PlaySFX` marking the special FM4/PSG3 track; `Sound_PlayBGM` re-marking; `cfFadeInToPrevious` on the DAC track | SFX stop paths (`cfStopTrack`, `StopSFX`, `StopSpecialSFX`, `cfStopSpecialFM4`), `Sound_PlayBGM` 1-up path, `DoFadeIn .fadedone` (DAC) |
| 1 | at rest (no note sounding) | rest note, `NoteTimeoutUpdate` expiry, `FMSetRest`/`PSGSetRest`, SFX restore paths, `cfFadeInToPrevious` | `FMDoNext`/`PSGDoNext` on each note fetch |
| 0, 5, 6 | unused by the driver; SFX headers write `$80` here | | |

## 3. Entry and update cadence

### 3.1 Callers

`UpdateMusic` (`SD:147`, `sub_71B4C`) is called:

- once per V-int after the per-mode VBlank routine, at `VBlank_Music` (`S:681-682`).
  Every mode reaches it, including lag frames (`VBlank_Lag` branches to `VBlank_Music`,
  `S:716,720,750`), the paused routine, and the special stage.
- a second time in the same frame from H-int when `f_doupdatesinhblank` was set
  (`S:1058-1064`): the delayed LZ screen update path calls `VBlank_UpdateScreen` and then
  `UpdateMusic` again. The driver has no re-entrancy guard; both calls run the full
  update, so on such frames the music advances two ticks.

The game never calls the driver from the main loop. All requests go through the
three queue bytes (§4.1).

### 3.2 `UpdateMusic` (`SD:147-273`)

1. `stopZ80`; spin until the bus-request bit reads 0 (`SD:154-155`), then test
   `zDAC_Status` bit 7 (`Z80:14`, address `$A01FFD`). If the Z80 is between its
   "not accepting" / "accepting" writes (§9.2), release the bus and retry from the top
   (`SD:157-165`). Nothing else in the driver waits on the Z80.
2. `f_voice_selector := 0` (music phase).
3. If `f_pausemusic != 0` → `PauseMusic` (§11) and return; **no tempo, queue, or track
   processing happens while paused**.
4. `v_main_tempo_timeout -= 1`; on zero `TempoWait` (§3.3).
5. If `v_fadeout_counter != 0` → `DoFadeOut` (§12.1).
6. If `f_fadein_flag != 0` → `DoFadeIn` (§12.2).
7. If the word at `v_soundqueue0` is non-zero → `CycleSoundQueue` (§4.2). FixBugs #1.
8. If `v_sound_id != $80` → `PlaySoundID` (§4.3).
9. Track walk, in this fixed order, only for tracks with bit 7 set:
   music DAC (`DACUpdateTrack`), music FM1-FM6 (`FMUpdateTrack`), music PSG1-3
   (`PSGUpdateTrack`); `f_voice_selector := $80`; SFX FM3-5, SFX PSG1-3;
   `f_voice_selector := $40`; special FM4, special PSG3.
   `f_updating_dac` is cleared after the DAC track (`SD:212`).
10. `DoStartZ80` (`SD:270`): `startZ80`, return.

Routines that "tamper with the return address" alter this flow:

| Routine | Stack adjust | Effect on the frame |
|---|---|---|
| `Sound_PlayBGM` `.locdblret` `SD:959-961` | `+4` | returns from `UpdateMusic` to *its* caller: no track updates on the frame a song is loaded, and **`DoStartZ80` is skipped** — the Z80 stays bus-requested until the next `startZ80` anywhere (next V-int's mode routine, or the next `UpdateMusic`). DAC playback stalls for that interval |
| `PlaySegaSound` `SD:747-748` | `+4` | same early return after the busy-wait |
| `cfFadeInToPrevious` `SD:2223-2225` | `+8` | returns into the `UpdateMusic` track loop, skipping the rest of the current track's update; it also executes `startZ80` itself, so the remaining tracks that frame are updated with the Z80 running |
| `cfStopTrack` `SD:2562`, `cfStopSpecialFM4` `SD:2309` | `+8` | return into the track loop, skipping the rest of the stopped track's update |
| `NoteTimeoutUpdate` `SD:469,475` | `+4` | on note-fill expiry, skips modulation and frequency update for that track this frame |
| `DoModulation` `SD:484,516` | `+4` unless a step fired | the `FMUpdateFreq`/`PSGUpdateFreq` write after modulation only happens on frames where a modulation step produced a new frequency |

### 3.3 Tempo (`TempoWait` `SD:1549-1561`, `sub_7260C`)

Reloads `v_main_tempo_timeout` from `v_main_tempo`, then adds 1 to `DurationTimeout`
of all ten music tracks (`SD:1551-1558`). Because each track's update then subtracts 1,
the frame is a no-op for the music: main tempo `T` means the music is held for one
frame every `T` driver calls. `T = 1` freezes the music (every call is a hold — the
smps2asm include refuses it, `INC:194-196`); `T = 0` wraps through `$FF`, i.e. one hold
every 256 calls. SFX and special SFX tracks are never held by the main tempo.

`v_main_tempo`/`v_main_tempo_timeout` are set at song load (`SD:812-813`), by `$EA`
(`cfSetTempo`, which also resets the countdown, `SD:2256-2259`), and by the
speed-up / slow-down commands (§14.2).

Per-track `TempoDivider` (`SetDuration` `SD:411-426`, `sub_71D40`): the raw duration
byte is added to itself `divider-1` times in byte arithmetic (`d0 = raw * divider`
mod 256). Divider 1 → unchanged; divider 0 → 255 additions → `raw*256 mod 256 = 0`,
which the countdown then treats as 256 frames. The result is stored in both
`SavedDuration` and `DurationTimeout`. Song header byte 4 sets it for all tracks;
`$E5` sets one track, `$EB` sets all ten music tracks (§8).

Shipped song tempos (`smpsHeaderTempo div, mod` = divider, main tempo): GHZ `1,3`;
LZ `2,6`; MZ `2,9`; SLZ `2,6`; SYZ `2,3`; SBZ `2,5`; Invincibility `1,8`; Extra Life
`2,5`; Special Stage `2,8`; Title `1,5`; Ending `1,5`; Boss `2,4`; FZ `2,6`; Got
Through `2,3`; Game Over `2,$13`; Continue `1,7`; Credits `1,$33`; Drowning `1,2`;
Get Emerald `1,6` (from each `sound/music/Mus*.asm:5`).

## 4. Sound requests, queue, priority

### 4.1 Game-side entry points

| Routine | Anchor | Writes |
|---|---|---|
| `QueueSound1` (formerly `PlaySound`) | `_inc/Queue Sound Routines.asm:10-12` | `v_soundqueue0` (`$FFFFF00A`) |
| `QueueSound2` (formerly `PlaySound_Special`) | `:24-26` | `v_soundqueue1` (`$FFFFF00B`) |
| `QueueSound3` (formerly `PlaySound_Unknown`) | `:39-41` | `v_soundqueue2` (`$FFFFF00C`) — never noticed by the shipped driver (FixBugs #1) and not used by the game |

The slots are plain bytes with no ordering beyond slot index; a second write to the
same slot in one frame overwrites the first. By convention music goes to slot 0 and
SFX to slot 1, but the driver treats both identically. IDs `$00-$80` written to a slot
are discarded (`SD:647-648`).

### 4.2 `CycleSoundQueue` (`SD:637-672`, `Sound_Play`, `$71F02`)

For each of the three slots in order (`d4 = 2`, `SD:641`):

1. Read and clear the slot.
2. If the ID is `< $81`, skip.
3. If `v_sound_id != $80` (a request was already accepted this pass), write this ID
   back into `v_soundqueue0` for the next frame and skip. (So two requests in one
   frame are serialised over two frames; a third would overwrite the second in slot 0.)
4. Look up `SoundPriorities[ID - $81]` (`SD:131-138`). If it is below the running
   priority `d3` (initialised from `v_sndprio`) skip; otherwise `d3 := priority`,
   `v_sound_id := ID`.

After the loop, `v_sndprio := d3` unless `d3` has bit 7 set (`SD:666-668`).

Priority bytes (ROM `$71AE8`, verified against `s1.gen`):

| IDs | Priority | Notes |
|---|---|---|
| `$81-$9F` | `$90` | music; bit 7 → never stored |
| `$A0` jump | `$80` | bit 7 → never stored |
| `$A1-$A9`, `$AB-$AD`, `$AF`, `$B0`, `$B2`, `$B4-$BE`, `$C1-$CF` | `$70` | stored while playing |
| `$AA` splash | `$68` | |
| `$AE` fireball, `$B1` electric, `$B3` flamethrower, `$C0` basaran | `$60` | |
| `$BF` get continue | `$7F` | blocks every `$70`-and-below SFX until it ends |
| `$D0-$DF` | `$80` | special SFX; never stored |
| `$E0-$E4` | `$90` | commands |

`v_sndprio` decays only when something clears it: an SFX track ending (`cfStopTrack`,
SFX phase only, `SD:2504-2506`), `StopSFX` (`SD:1227`), the SFX gate exits in
`Sound_PlaySFX` (`SD:1085-1087`), the Extra Life path of `Sound_PlayBGM`
(`SD:775,785`), and `StopAllSound`. `InitMusicPlayback` deliberately preserves it
across a song load (`SD:1489,1505`).

### 4.3 `PlaySoundID` (`SD:676-712`, `Sound_ChkValue`, `$71F4C`)

Takes `v_sound_id`, resets it to `$80`, and dispatches:

| `v_sound_id` | Target |
|---|---|
| `0` | `StopAllSound` (the power-on state: RAM is zeroed by `GameInit` `S:409-413`, so the first V-int's driver call initialises the chips through `StopAllSound`; `DACDriverLoad` `S:1225-1240` only loads the Z80) |
| `$01-$7F` | ignored |
| `$81-$9F` | `Sound_PlayBGM` (FixBugs #2: `$94-$9F` crash) |
| `$A0-$CF` | `Sound_PlaySFX` |
| `$D0-$DF` | `Sound_PlaySpecial` (FixBugs #3: `$D1-$DF` crash) |
| `$E0` | `FadeOutMusic` |
| `$E1` | `PlaySegaSound` |
| `$E2` | `SpeedUpMusic` |
| `$E3` | `SlowDownMusic` |
| `$E4` | `StopAllSound` |
| `$E5-$FF` | ignored |

Dispatch table `Sound_ExIndex` `SD:721-727`.

### 4.4 `StopAllSound` (`SD:1461-1482`, `Sound_E4`)

`$2B := $80` (DAC on), `$27 := 0` (timers off, FM3 normal), clear driver RAM
(`$390` bytes, FixBugs #11), `v_sound_id := $80`, `FMSilenceAll` (`SD:1426-1454`:
key-off all six channels, TL `$7F` on all 24 operators), `PSGSilenceAll`
(`SD:2021-2028`: `$9F $BF $DF $FF`). Everything including `f_speedup`,
`v_ring_speaker`, `f_push_playing` and the 1-up state is lost.

## 5. Music load: `Sound_PlayBGM` (`SD:754-961`, `Sound_81to9F`)

### 5.1 Extra Life path (`bgm_ExtraLife = $88`, `SD:755-786`)

If `f_1up_playing` is already set, exit (§3.2 tamper). Otherwise:

1. clear bit 2 on all ten music tracks; clear bit 7 on all six SFX tracks (SFX are
   killed without key-off; their hardware notes are silenced by the
   `InitMusicPlayback` chip silence, FixBugs #13);
2. `v_sndprio := 0`;
3. copy `$000-$21F` to `v_1up_ram_copy` (`SD:776-782`) — variables **and** music
   tracks, i.e. the current song's exact position, tempo, speed-shoes state, fade
   state, ring toggle;
4. `f_1up_playing := $80`, `v_sndprio := 0` again; fall into the common loader.

Special SFX tracks are not touched here; they keep playing over the jingle, but no
new SFX or special SFX can start while `f_1up_playing` is set (§6.1, §7).

### 5.2 Common path (`SD:789-961`)

- Non-1-up: `f_1up_playing := 0`, `v_fadein_counter := 0` (a fade-in in progress is
  abandoned — `f_fadein_flag` itself is cleared by the RAM clear below).
- `InitMusicPlayback` (`SD:1486-1545`, `sub_725CA`): save `v_sndprio`,
  `f_1up_playing`, `f_speedup`, `v_fadein_counter`, the `v_soundqueue0/1` word; zero
  `$000-$21F`; restore those; `v_sound_id := $80`; `FMSilenceAll`; `PSGSilenceAll`
  (FixBugs #13). Note `v_ring_speaker`, `f_push_playing`, `v_communication_byte`,
  `f_pausemusic`, fade-out state are all reset here.
- `v_speeduptempo := SpeedUpIndex[ID-$81]` (`SD:795-797`). The table has only eight
  entries (`SD:74-82`, ROM `$71A94` = `07 72 73 26 15 08 FF 05`); IDs `$89+` read the
  bytes that follow — the first bytes of `MusicIndex` (`SD:70-72` warns about this).
- Header (`SD:801-816`, layout from `INC:275-356`):

  | Byte | Meaning |
  |---|---|
  | 0-1 | voice bank offset, relative to song start → `v_voice_ptr` |
  | 2 | number of FM+DAC tracks (DAC counts as the first) |
  | 3 | number of PSG tracks |
  | 4 | tempo divider for every track |
  | 5 | main tempo → `v_tempo_mod`; `v_main_tempo` and timeout get either this or `v_speeduptempo` if `f_speedup` is set (`SD:805-813`) |
  | 6+ | per FM/DAC track: `dc.w` data offset, `dc.b` transpose, `dc.b` volume; per PSG track: `dc.w` offset, `dc.b` transpose, `dc.b` volume, `dc.b` (skipped, read into `d0` and dropped `SD:903`), `dc.b` initial envelope (`VoiceIndex`) |

- FM/DAC track init (`SD:838-854`): bit 7 set, `VoiceControl` from `FMDACInitBytes`
  (`6, 0, 1, 2, 4, 5, 6` — DAC first, then FM1-6), divider, `StackPointer := $30`,
  `AMSFMSPan := $C0`, `DurationTimeout := 1` (so the first byte is parsed on the next
  update), `DataPointer`, `Transpose`+`Volume` word. Everything else stays zero from
  the RAM clear (voice index 0, no modulation, no detune).
- If exactly 7 FM/DAC tracks: `$2B := 0` (DAC off, FM6 is a melodic channel) —
  Special Stage (`Mus89`) and Get Emerald (`Mus93`) do this. Otherwise (`SD:864-882`):
  key-off FM6, TL `$7F` on FM6's four operators, `$B6 := $C0`. **`$2B` is not written
  back to `$80` here**; the DAC is only re-enabled by `StopAllSound`.
- PSG track init (`SD:885-906`): as above with `PSGInitBytes` (`$80,$A0,$C0`), no
  pan/voice pointer, `VoiceIndex` from the header.
- SFX override re-marking (`SD:909-942`): for every SFX track with bit 7 set, set bit 2
  on the music track that owns the same channel (`SFX_BGMChannelRAM`, §6.3); if the
  special FM4 / PSG3 track is playing, set bit 2 on music FM4 / PSG3.
- `FMNoteOff` on music FM1-6 and `PSGNoteOff` on music PSG1-3 (`SD:945-957`) — both
  honour the override bit, so SFX-owned channels are not keyed off here.
- Tamper return (§3.2).

## 6. SFX load: `Sound_PlaySFX` (`SD:977-1087`, `Sound_A0toCF`)

### 6.1 Gates

Exit with `v_sndprio := 0` if `f_1up_playing`, `v_fadeout_counter` or
`f_fadein_flag` is non-zero (`SD:978-983`). Then:

- ring (`$B5`): if `v_ring_speaker == 0` substitute `$CE`; toggle bit 0 either way
  (`SD:984-991`). See §14.4.
- push (`$A7`): if `f_push_playing` set, return **without** clearing `v_sndprio`
  (`SD:994-998`); else set it to `$80`.

### 6.2 Header and track init (`SD:1001-1082`)

SFX header (`INC:360-384`): `dc.w` voice bank offset; `dc.b` tempo divider; `dc.b` track
count; per track: `dc.b $80` (initial `PlaybackControl`), `dc.b` channel id
(`cFM3=$02, cFM4=$04, cFM5=$05, cPSG1=$80, cPSG2=$A0, cPSG3=$C0, cNoise=$E0`,
`INC:171-177`), `dc.w` data offset, `dc.b` transpose, `dc.b` volume.

Per track: mark the music track for that channel overridden (bit 2) — FM index
`(id-2)*4`, PSG index `id>>3` into `SFX_BGMChannelRAM`; for `cPSG3` additionally write
`$DF` and `$FF` to the PSG (silence tone 3 and noise, `SD:1038-1044`); zero the `$30`-byte
SFX track; write the `$80,id` word into `PlaybackControl/VoiceControl`; divider;
pointer; transpose/volume word; `DurationTimeout := 1`; `StackPointer := $30`; FM
only: `AMSFMSPan := $C0`, `VoicePtr := voice bank`. The SFX track's previous contents
(a still-playing SFX on the same channel) are discarded without a key-off; the new
SFX's first note replaces it on the next update.

After the loop: if SFX FM4 is now playing, set bit 2 on **special** FM4; if SFX PSG3 is
playing, set bit 2 on special PSG3 (`SD:1072-1079`) — SFX outrank the special SFX.

### 6.3 Channel ownership tables

`SFX_BGMChannelRAM` (`SD:1093-1101`) and `SFX_SFXChannelRAM` (`SD:1103-1111`) map
`[FM3, –, FM4, FM5, PSG1, PSG2, PSG3, noise]` to the music and SFX track blocks; noise
(`$E0`) shares PSG3's track. An SFX can only use FM3, FM4, FM5 and the three PSG
channels; FM1, FM2, FM6 and DAC are music-only.

## 7. Special SFX: `Sound_PlaySpecial` (`SD:1117-1194`, `Sound_D0toDF`)

Only `$D0` (GHZ/LZ waterfall) exists (`SpecSoundIndex` `SD:2740-2742`). Same gates as
SFX but they exit *without* clearing `v_sndprio`. Voice bank → `v_special_voice_ptr`.
Track init as in §6.2 but the destination is fixed: FM tracks → `v_spcsfx_fm4_track`
(marking music FM4 overridden), PSG tracks → `v_spcsfx_psg3_track` (marking music
PSG3). No `VoicePtr` is stored (`SD:1173-1175`). After the loop, if SFX FM4 / SFX PSG3
are playing, the *special* tracks get bit 2 (SFX win), and for PSG3 the channel is
silenced (`SD:1188-1191`). The unused `SpecSFX_*` tables at `SD:1213-1222` are data
only.

The waterfall SFX itself (`sound/sfx/SndD0 - Waterfall.asm`) is one FM4 track that
holds a note with `$E7` for `$40` frames, then raises attenuation by 1 per frame for
`$22` frames, rests one frame and ends with `$EE`.

## 8. Coordination flags (`CoordFlag` `SD:2067-2071`, table `SD:2075-2127`)

Bytes `>= $E0` in track data. `a4` is the data pointer; parameters are consumed
in-line. Flags run inside the note-fetch loops of `DACUpdateTrack`, `FMDoNext`,
`PSGDoNext`, so they execute at note boundaries only.

| Flag | Routine | Anchor | Params | Effect on shipped ROM |
|---|---|---|---|---|
| `$E0` | `cfPanningAMSFMS` | `SD:2129-2138` | 1 | PSG: parameter consumed, ignored. FM/DAC: `AMSFMSPan := (old & $37) \| p` then write `$B4+ch` unless overridden (`WriteFMIorIIMain`). All bits of `p` are OR'd in; of the old value only the AMS/FMS bits (5-4, 2-0) survive, so previously set AMS/FMS cannot be cleared by this flag while L/R (7-6) and bit 3 are replaced |
| `$E1` | `cfDetune` | `SD:2145-2147` | 1 | `Detune := p` (applied at every frequency write) |
| `$E2` | `cfSetCommunication` | `SD:2150-2152` | 1 | `v_communication_byte := p`; nothing reads it. Every shipped song contains it |
| `$E3` | `cfJumpReturn` | `SD:2155-2163` | 0 | pop the gosub stack: `a4 := saved + 2`, clear the slot, `StackPointer += 4` |
| `$E4` | `cfFadeInToPrevious` | `SD:2166-2225` | 0 | restore the 1-up backup and start a fade-in (§12.2). Only `Mus88` uses it |
| `$E5` | `cfSetTempoDivider` | `SD:2228-2230` | 1 | this track's `TempoDivider := p` (SYZ, Credits) |
| `$E6` | `cfChangeFMVolume` | `SD:2233-2236` | 1 | `Volume += p` then `SendVoiceTL` (§9.5). On a PSG track this still adds to `Volume` and then `SendVoiceTL` reads FM voice data for it (the Credits bug, FixBugs #23) |
| `$E7` | `cfHoldNote` | `SD:2239-2241` | 0 | set bit 4: the next note is neither keyed off nor keyed on, keeps its envelope; `FinishTrackUpdate` also skips the note-fill/envelope/modulation reset (`SD:439-440`) |
| `$E8` | `cfNoteTimeout` | `SD:2244-2247` | 1 | `NoteTimeout := NoteTimeoutMaster := p` (§10) |
| `$E9` | `cfChangeTransposition` | `SD:2250-2253` | 1 | `Transpose += p` |
| `$EA` | `cfSetTempo` | `SD:2256-2259` | 1 | `v_main_tempo := v_main_tempo_timeout := p` (Drowning, Credits). Not routed through the speed-shoes state: the value is lost at the next `$E2/$E3` command |
| `$EB` | `cfSetTempoDividerAll` | `SD:2262-2273` | 1 | `TempoDivider := p` on all ten music tracks regardless of which track ran it (Credits) |
| `$EC` | `cfChangePSGVolume` | `SD:2276-2279` | 1 | `Volume += p`; no hardware write until the next envelope/volume update |
| `$ED` | `cfClearPush` | `SD:2282-2284` | 0 | `f_push_playing := 0` (end of `SndA7`) |
| `$EE` | `cfStopSpecialFM4` | `SD:2287-2310` | 0 | stop this track (bits 7,4 clear), `FMNoteOff`; if SFX FM4 is not playing, restore music FM4: clear bit 2, set at-rest, `SetVoice` from `v_voice_ptr`. Tamper `+8`. Written for the special FM4 track; run on any other track it still restores *music FM4* |
| `$EF` | `cfSetVoice` | `SD:2313-2325` | 1 | `VoiceIndex := p`; unless overridden, upload voice `p` from the bank chosen by `f_voice_selector` (§9.4) |
| `$F0` | `cfModulation` | `SD:2471-2481` | 4 | enable modulation and load wait/speed/delta/steps (§10) |
| `$F1` | `cfEnableModulation` | `SD:2484-2486` | 0 | set bit 3 (unused by shipped data) |
| `$F2` | `cfStopTrack` | `SD:2489-2563` | 0 | end of track (§13.3). Tamper `+8` |
| `$F3` | `cfSetPSGNoise` | `SD:2566-2574` | 1 | `VoiceControl := $E0`, `PSGNoise := p`, write `p` to the PSG unless overridden. Never undone: the track stays a noise track until reloaded |
| `$F4` | `cfDisableModulation` | `SD:2577-2579` | 0 | clear bit 3 |
| `$F5` | `cfSetPSGTone` | `SD:2582-2584` | 1 | `VoiceIndex := p` (PSG envelope number, 0 = flat) |
| `$F6` | `cfJumpTo` | `SD:2587-2593` | 2 (big-endian) | `a4 := a4 + offset - 1` where `a4` points just past the two bytes; the assembler writes `loc-*-1` (`INC:609`) |
| `$F7` | `cfRepeatAtPos` | `SD:2596-2608` | 4 | `idx, count, offset`: if `LoopCounters[idx] == 0` load `count`; decrement; if non-zero jump as `$F6`, else skip the offset. A counter is never reset except by the RAM clear, so re-entering a finished loop starts it again from `count` because it reads 0 |
| `$F8` | `cfJumpToGosub` | `SD:2611-2617` | 2 | `StackPointer -= 4`, store `a4` (pointing at the offset bytes) at `(a5,StackPointer)`, then jump. No overflow check (see §2.2 stack note) |
| `$F9` | `cfOpF9` | `SD:2620-2626` | 0 | write `$88 := $0F` and `$8C := $0F` on part I (D1L/RR of operators 3 and 4 of **FM1**, whatever track ran it). SYZ only |
| `$FA-$FF` | – | | | the jump table ends at `$F9`; these index past `coordflagLookup` into `cfPanningAMSFMS` code (`SD:2128+`). No shipped data uses them |

## 9. FM path

### 9.1 `FMUpdateTrack` (`SD:349-362`, `sub_71CCA`)

`DurationTimeout -= 1`. On zero: clear bit 4, `FMDoNext`, `FMPrepareNote`, `FMNoteOn`.
Otherwise: `NoteTimeoutUpdate`, `DoModulation`, `FMUpdateFreq` (the last two subject to
the stack tampers in §3.2).

### 9.2 `FMDoNext` (`SD:366-393`, `sub_71CEC`)

Clear at-rest; loop: fetch byte; `>= $E0` → `CoordFlag`. First non-flag byte: `FMNoteOff`
(honours hold/override), then if it is a note (`>= $80`) `FMSetFreq`; then if the next
byte is a duration (`< $80`) `SetDuration`, else push it back and reuse
`SavedDuration`. `FinishTrackUpdate` (`SD:436-456`): store pointer, reload
`DurationTimeout` from `SavedDuration`, and unless holding: reset `NoteTimeout` from
master, `VolEnvIndex := 0`, and if modulation is on reload wait/speed/delta/steps
(steps halved, `SD:449-451`) and zero `ModulationVal`.

A bare duration byte (no note) therefore re-triggers the previous note (key-on again
with the same `Freq`), which is how repeated notes are written.

### 9.3 Frequency: `FMSetFreq` (`SD:397-407`), table `FMFrequencies` (`SD:1792-1809`, `$72790`)

`idx = (note - $80 + Transpose) & $7F`; `note == $80` → `TrackSetRest` (`SD:430-432`:
at-rest, `Freq := 0`). Because the index is note `- $80` rather than `- $81`, entry 0 of
the table corresponds to "rest + 0" and is only reachable via negative transposition
(`SD:1773-1780`). The table is 96 words: 8 octaves of `B, C, C#, ... A#` with
`MakeFMFrequency(f) = round(f * 2^21 / FM_Sample_Rate) + octave * $800`,
`FM_Sample_Rate = M68000_Clock / 144`, `M68000_Clock = 53693175 / 7` (`C:10-13`; integer
division in the assembler). Indices `>= 96` read past the table into `PSGUpdateTrack`
code (`SD:1773-1790`).

`FMPrepareNote` / `FMUpdateFreq` (`SD:524-550`): if not resting and `Freq != 0`, write
`Freq + sign-extended Detune` as `$A4+ch` (high byte) then `$A0+ch` (low byte) unless
overridden. `Freq == 0` with a note pending → `FMSetRest`.

`FMNoteOn` (`SD:1668-1680`): `$28 := $F0 | VoiceControl` unless resting or overridden.
`FMNoteOff` (`SD:1684-1698`): `$28 := VoiceControl` unless holding (bit 4) or
overridden; `SendFMNoteOff` is the unconditional entry used by `StopSpecialSFX`.

### 9.4 Voices: `SetVoice` (`SD:2329-2375`, `sub_72C4E`)

Voice `n` is at `bank + 25*n`. Layout (write order, `FMInstrumentOperatorTable`
`SD:2440-2461`, `FMInstrumentTLTable` `SD:2463-2468`; operator order is 1, 3, 2, 4):

| Bytes | Registers |
|---|---|
| 0 | `$B0+ch` feedback/algorithm, also stored in `FeedbackAlgo` |
| 1-4 | `$30,$38,$34,$3C` DT/MUL |
| 5-8 | `$50,$58,$54,$5C` RS/AR |
| 9-12 | `$60,$68,$64,$6C` AM/D1R |
| 13-16 | `$70,$78,$74,$7C` D2R |
| 17-20 | `$80,$88,$84,$8C` D1L/RR |
| 21-24 | `$40,$48,$44,$4C` TL; `Volume` is **added** to the TL of operators selected by `FMSlotMask[algorithm]` = `8,8,8,8,$A,$E,$E,$F` (`SD:2379`, ROM `$72CAC` verified), tested LSB-first in the same 1,3,2,4 order — i.e. bit 3 = operator 4 (carrier for algorithms 0-3), bit 1 = operator 3, bit 2 = operator 2, bit 0 = operator 1 |

then `$B4+ch := AMSFMSPan`. Every write goes through `WriteFMIorII` — `SetVoice` itself
does not check the override bit; callers do (`cfSetVoice` `SD:2317-2318`).

`cfSetVoice` bank selection (`SD:2319-2325`): `f_voice_selector == 0` → `v_voice_ptr`;
`$80` → track `VoicePtr`; `$40` → `v_special_voice_ptr`.

### 9.5 `SendVoiceTL` (`SD:2383-2436`, `sub_72CB4`)

Used by `$E6` and both fades. Returns if overridden or if `Volume` is negative
(`SD:2418-2419`). Re-reads the voice's TL bytes (bank as in `cfSetVoice`, but with the
`(a6)` bug for SFX tracks, FixBugs #19), adds `Volume` to the masked operators, and
**skips the write when the byte addition carries** (`SD:2427-2428`) — an operator whose
TL + volume overflows `$FF` keeps its previous TL.

### 9.6 Register writes

`WriteFMI` (`SD:1726-1742`), `WriteFMII` (`SD:1753-1769`): poll `$A04000` bit 7 (busy)
before the address write and again before the data write. `WriteFMIorII`
(`SD:1713-1717`) adds `VoiceControl` to the register number for part I, or
`VoiceControl & ~4` for part II (`WriteFMIIPart` `SD:1746-1750`). `WriteFMIorIIMain`
(`SD:1702-1709`) is the override-checked variant used by `$E0`. Comment at
`SD:1720-1723`: these are the Type 1a write routines.

## 10. Modulation and note fill

`cfModulation` (`$F0`): `ModulationPtr := a4` (points at the 4 bytes), then wait,
speed, delta, `steps := p4 >> 1`, `ModulationVal := 0`, bit 3 set. `FinishTrackUpdate`
repeats the same load at every non-held note.

`DoModulation` (`SD:483-520`, `sub_71DC6`), every non-note-start frame while bit 3 is
set: if `ModulationWait != 0` decrement and stop. Else `ModulationSpeed -= 1`; on zero
reload speed from the data byte 1 and: if `ModulationSteps == 0` reload it from byte 3
(**the full value, not halved** — the first half-cycle is half length so the wave is
centred) and negate `ModulationDelta`, no frequency change this frame; otherwise
`ModulationSteps -= 1`, `ModulationVal += Delta`, `d6 := Freq + ModulationVal`, and fall
into `FMUpdateFreq`/`PSGUpdateFreq` (which add `Detune`). PSG uses the same routine
with `Freq` as a period, so a positive delta lowers pitch.

Note fill (`NoteTimeoutUpdate` `SD:460-479`, `sub_71D9E`): while `NoteTimeout != 0`,
decrement; on reaching zero set at-rest and key off (`FMNoteOff`, or `PSGNoteOff` for
PSG), skipping the rest of that track's update. `NoteTimeout` is reloaded from the
master at every non-held note. On PSG tracks `SetPSGVolume` additionally refuses to
write volume once the fill has expired while holding (`PSGCheckNoteTimeout`
`SD:1981-1986`).

## 11. Pause and unpause (`PauseMusic` `SD:555-629`, `loc_71E50`)

Game side (`_inc/PauseGame.asm:18,50,64`): Start writes `1`; unpause (or one slow-motion
frame) writes `$80`. Driver side, entered from `UpdateMusic` step 3 with the flags of
`tst.b f_pausemusic`:

- `1` (positive, not yet `2`): set `f_pausemusic := 2`, write `$B4,$B5,$B6 := 0` on both
  parts (pan bits cleared → all six FM channels output nothing), key-off all six
  channels, `PSGSilenceAll`, `DoStartZ80`.
- `2`: `DoStartZ80` only (idle while paused). Tempo, queue and fade state are frozen;
  requests queued while paused stay in the slots.
- `$80` (negative): `f_pausemusic := 0`; for every *playing, non-overridden* track in
  music DAC+FM1-6, SFX FM3-5 and special FM4, write `$B4+ch := AMSFMSPan`. Then
  `DoStartZ80`. Nothing is keyed back on, PSG volumes are not restored (they return at
  the next envelope/volume write), and the DAC sample the Z80 was playing was never
  stopped — the Z80 is not paused by the driver at all.

The frame that processes `1` or `$80` performs no other driver work.

## 12. Fades

### 12.1 Fade-out (`FadeOutMusic` `SD:1360-1367`, `DoFadeOut` `SD:1371-1422`)

`$E0`: `StopSFX`, `StopSpecialSFX` (§13), `v_fadeout_delay := 3`,
`v_fadeout_counter := $28`, DAC track bit 7 cleared (drums stop at once, no Z80 write),
`f_speedup := 0`.

`DoFadeOut` each call: if `delay != 0` decrement and return; else `counter -= 1`, and
when it reaches 0 → `StopAllSound` (§4.4); otherwise `delay := 3` and step: FM tracks
`Volume += 1`, stop the track when `Volume` goes negative, else `SendVoiceTL`; PSG tracks
`Volume += 1`, stop when `>= $10`, else `SetPSGVolume`. Steps land on calls 4, 8, ...,
156 (39 steps) and `StopAllSound` on call 160. SFX requests are refused throughout
(§6.1).

### 12.2 Fade-in from the 1-up backup (`cfFadeInToPrevious` `SD:2166-2225`, `DoFadeIn` `SD:1604-1664`)

`$E4` (end of `Mus88`): copy `v_1up_ram_copy` back over `$000-$21F` (this restores
tempo, timeouts, `f_speedup`, `v_fadein_*`, `f_1up_playing`, queue bytes, everything as
it was when the jingle started), set bit 2 on the DAC track (mutes DAC output until the
fade completes), `d6 := $28 - v_fadein_counter` (the restored counter — non-zero only if
the jingle interrupted an earlier fade-in), then for each playing music FM track: set
at-rest, `Volume += d6`, and if not overridden `SetVoice` from `v_voice_ptr` (uploads
the attenuated TL); for each playing PSG track: at-rest, `PSGNoteOff`, `Volume += d6`.
Then `f_fadein_flag := $80`, `v_fadein_counter := $28`, `f_1up_playing := 0`,
`startZ80`, tamper `+8`.

SFX tracks are not restored (they were killed at §5.1), but their override marks on
the music tracks were cleared there too, so restored music tracks own their channels
unless an SFX started during the jingle — impossible, because `Sound_PlaySFX` refuses
while `f_1up_playing` is set. A special SFX that was playing before the jingle keeps
playing and its bit-2 marks on music FM4/PSG3 are part of the restored image.

`DoFadeIn` each call: if `delay != 0` decrement, return; else if `counter == 0`: clear
DAC bit 2, `f_fadein_flag := 0` (FixBugs #14), done; else `counter -= 1`, `delay := 2`,
FM `Volume -= 1` + `SendVoiceTL`, PSG `Volume -= 1` + `SetPSGVolume` clamped to `$F`.
Steps on calls 1, 4, 7, ... (40 steps), completion on call 121. Because the tracks were
set at-rest, PSG volume writes are suppressed (`SetPSGVolume` checks bit 1) until each
PSG track reaches its next note; FM TL writes go out immediately.

## 13. SFX stop and music restore

### 13.1 `StopSFX` (`SD:1226-1307`, `Snd_FadeOut1`)

`v_sndprio := 0`; for each playing SFX track: clear bit 7; FM: `FMNoteOff`; if the
channel is FM4 and the special FM4 track is playing, restore the **special** track
instead (with `v_special_voice_ptr`; FixBugs #7 leaves `a5` wrong afterwards),
otherwise restore the music track for that channel with `v_voice_ptr`: clear bit 2, set
at-rest, `SetVoice(VoiceIndex)` (FixBugs #8). PSG: `PSGNoteOff`; the target is the
special PSG3 track if the channel is `$C0` or `$E0` (FixBugs #9: no playing check),
else the music track; clear bit 2, set at-rest, and if that track is a noise track
re-latch its `PSGNoise` byte.

### 13.2 `StopSpecialSFX` (`SD:1311-1353`, `Snd_FadeOut2`)

For special FM4 / PSG3 if playing: clear bit 7; if *not* overridden (bit 2 clear) send
the note-off (`SendFMNoteOff`/`SendPSGNoteOff`, unconditional), and restore music FM4
(clear bit 2, at-rest, and if playing `SetVoice` with `v_voice_ptr`) / music PSG3
(clear bit 2, at-rest, re-latch noise if a noise track). When the special track *is*
overridden by an SFX, nothing is restored: the SFX still owns the channel.

### 13.3 `cfStopTrack` (`$F2`, `SD:2489-2563`)

Clear bits 7 and 4. PSG: `PSGNoteOff`. FM: `FMNoteOff` unless this is the DAC track
(`f_updating_dac`), which just stops. Then, **only in the SFX phase**
(`f_voice_selector` negative, i.e. `$80`): `v_sndprio := 0` and restore as in §13.1
(special FM4 preferred over music FM4 when playing; music track only re-voiced if it
is playing — `SD:2525-2527`; PSG target is the special PSG3 track only if it is playing
and the channel is `$C0`/`$E0`). Music and special-SFX tracks that end simply stop; a
special SFX ending with `$F2` instead of `$EE` would leave music FM4 marked overridden.

## 14. Special cases

### 14.1 Extra Life (`$88`)

§5.1 backup, `f_1up_playing`. While set: `Sound_PlaySFX` and `Sound_PlaySpecial`
refuse (`SD:978-979,1118-1119`), `SpeedUpMusic`/`SlowDownMusic` edit the **backup
copy** instead of the live tempo (`SD:1569-1581,1588-1600`) so the jingle keeps its own
tempo and the restored song comes back at the right speed, and a second `$88` request
is ignored (`SD:757-758`). Requests for any other music replace the jingle normally
(`.bgmnot1up` clears the flag, and the backup is simply abandoned).

### 14.2 Speed shoes (`$E2` `SpeedUpMusic` `SD:1568-1581`, `$E3` `SlowDownMusic` `SD:1587-1600`)

`$E2`: `v_main_tempo := v_main_tempo_timeout := v_speeduptempo`, `f_speedup := $80`.
`$E3`: same from `v_tempo_mod`, `f_speedup := 0`. `f_speedup` survives a song load
(`InitMusicPlayback` preserves it) so the next song starts at its speed-shoes tempo
(`SD:807-809`); `FadeOutMusic` and `StopAllSound` clear it. Game: monitor
(`_incObj/26, 2E Monitors and Power-Ups.asm:311`), expiry (`_incObj/01 Sonic.asm:203`).
Per-song sped-up tempos are the `SpeedUpIndex` bytes; `$FF` for Invincibility means one
hold every 255 calls (effectively full speed).

### 14.3 Special stage

No driver-side special case. `GM_Special` queues `sfx_EnterSS` then `bgm_SS`
(`S:3225-3226,3272-3273`) without a stop; `Mus89` declares 7 FM/DAC tracks so its load
disables the DAC (`$2B := 0`, `SD:856-861`). The DAC stays disabled until the next
`StopAllSound` (a `bgm_Stop`, or the end of a `bgm_Fade`). `VBlank_SpecialStage` reaches
`VBlank_Music` like every other mode.

### 14.4 Ring left/right (`SD:984-991`)

`v_ring_speaker == 0` → request becomes `$CE` (`SndCE`, FM4, `panLeft`, first note
duration 4); otherwise stays `$B5` (`SndB5`, FM5, `panRight`, first note duration 5). The
byte is toggled on every ring request that passes the gates, and is zeroed by every
song load and by `StopAllSound`, so the first ring after a song starts is always the
left one. Because the two SFX use different FM channels, consecutive rings overlap
rather than restart. Both have priority `$70`. The game only ever requests `$B5`
(`_incObj/25, 37 Rings.asm:192`, monitors, level-select cheat `S:2129`).

### 14.5 Push (`SD:994-998`, `$ED`)

`f_push_playing` gates re-triggers of `$A7`; `SndA7` clears it with `$ED` just before
`$F2`. A song load or `StopAllSound` also clears it. If the push SFX is displaced by
another SFX on FM4 before reaching `$ED`, the flag stays set until the next song load.

### 14.6 "SEGA" chant (`$E1`, `PlaySegaSound` `SD:733-748`)

Writes `$88` to `zDAC_Sample`, `startZ80`, then busy-waits `$12 × $10000` `dbf`
iterations on the 68k and returns straight to `UpdateMusic`'s caller (§3.2). The Z80
plays the PCM at `pcmLoopCounter(16000)` (`Z80:197`). The game meanwhile sits in
`VBlank_SegaPCM` (`S:765-770`, mode `$14`), which only decrements a timer; the comment
at `S:1867` notes the CPU is frozen until the sound finishes. The chant is not a track
and does not touch the priority system.

### 14.7 Drowning, boss, invincibility

All plain music requests through `QueueSound1` (`_incObj/0A LZ Drowning Countdown.asm:226`,
`_inc/DynamicLevelEvents.asm:183` etc., `_incObj/sub ResumeMusic.asm`). `Mus92`
(Drowning) is the only song with no PSG tracks (`Chan $06,$00`); it and `Mus91` use
`$EA` to change tempo mid-song.

### 14.8 Level select sound test

`S:2222-2229` blocks `$94-$9F` (FixBugs #20). Sounds `$D1-$DF` are not reachable from the
sound test (`v_levselsound` is `$00-$4F`, `S:2213`), so FixBugs #3 is only reachable by
code.

## 15. PSG path

### 15.1 `PSGUpdateTrack` (`SD:1813-1828`, `sub_72850`)

`DurationTimeout -= 1`; on zero: clear bit 4, `PSGDoNext`, `PSGDoNoteOn`, `PSGDoVolFX`.
Otherwise: `NoteTimeoutUpdate`, `PSGUpdateVolFX`, `DoModulation`, `PSGUpdateFreq`.

`PSGDoNext` (`SD:1832-1859`) mirrors `FMDoNext` without a note-off; `PSGSetFreq`
(`SD:1863-1879`): `note == $80` → at-rest, `Freq := -1`, `FinishTrackUpdate`, `PSGNoteOff`;
otherwise `idx = (note - $81 + Transpose) & $7F` into `PSGFrequencies` then
`FinishTrackUpdate`. (Note: `PSGSetFreq` calls `FinishTrackUpdate` itself and the caller
calls it again after the duration byte — harmless double execution.)

### 15.2 Frequency table (`SD:2049-2063`, `$729CE`)

70 words, `C-1` upward by semitone, `MakePSGFrequency(f) = min($3FF, round(PSG_Sample_Rate / (2f)))`,
`PSG_Sample_Rate = Z80_Clock / 16`, `Z80_Clock = 53693175 / 15` (`C:12-14`). The last entry
(`223721.56 Hz`) evaluates to period 1. Indices `>= 70` read the code that follows
(FixBugs #21 exercises this in MZ).

`PSGUpdateFreq` (`SD:1890-1914`): `d6 := Freq + Detune`; unless overridden or resting,
write `VoiceControl | (d6 & $F)` (noise tracks substitute `$C0`) then `(d6 >> 4) & $3F`.
`PSGDoNoteOn` (`SD:1883-1885`): a negative `Freq` (rest) → `PSGSetRest` instead.

### 15.3 Volume and envelopes

`PSGUpdateVolFX` (`SD:1924-1926`): per-frame, only when `VoiceIndex != 0`.
`PSGDoVolFX` (`SD:1928-1959`): `d6 := Volume`; if `VoiceIndex == 0` → `SetPSGVolume`
directly; else envelope `PSG_Index[VoiceIndex-1]` (`SD:41-64`): read byte at
`VolEnvIndex`, increment the index, `$80` → `VolEnvHold` (decrement the index back,
`SD:1991-1993`, so the last value repeats forever — the terminator byte is never
applied); other bytes are added to `Volume`, clamped to `$F` (FixBugs #15 for other
negative bytes).

Envelopes (`SD:46-64`; `fTone_01..09` = `PSG1..PSG9`):

| # | Bytes |
|---|---|
| 1 | `0,0,0,1,1,1,2,2,2,3,3,3,4,4,4,5,5,5,6,6,6,7,$80` |
| 2 | `0,2,4,6,8,$10,$80` (the `$10` step drives the clamp to `$F`) |
| 3 | `0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,$80` |
| 4 | `0,0,2,3,4,4,5,5,5,6,$80` |
| 5 | `0×10,1×14,2×8,3×8,4,$80` |
| 6 | `3,3,3,2,2,2,2,1,1,1,0,0,0,0,$80` (note the source lists PSG6 before PSG5) |
| 7 | `0×6,1×5,2×5,3×3,4×3,5×3,6,7,$80` |
| 8 | `0×5,1×5,2×6,3×5,4×5,5×5,6×5,7×3,$80` |
| 9 | `0,1,2,...,$F,$80` |

`SetPSGVolume` (`SD:1964-1986`): refuse if resting, overridden, or (holding and note
fill expired); else write `VoiceControl | $10 | d6`. Noise tracks (`$E0`) therefore write
`$F0 | vol`, the noise attenuator.

`PSGNoteOff` (`SD:1997-2017`): `VoiceControl | $1F` unless overridden (FixBugs #16). For a
noise track that is `$FF`.

## 16. DAC path

### 16.1 68k side: `DACUpdateTrack` (`SD:277-331`, `sub_71C4E`)

`DurationTimeout -= 1`; on zero: `f_updating_dac := $80`; parse (flags via `CoordFlag`);
a sample byte (`>= $80`) is stored in `SavedDAC`, then an optional duration
(`SetDuration`, or `SavedDuration` reuse — the DAC track uses the same divider
multiplication as the others). Unless overridden: `$80` → nothing (rest);
`$81-$87` → write the byte to `zDAC_Sample` (`$A01FFF`); `$88-$8F` (bit 3) → look up
`DAC_sample_rate[id-$88]`, write it to `zTimpani_Pitch` (`$A000EA`, the timpani's
`zPCM_Table` pitch word, `Z80:219`) and play `$83`. The pitch write is permanent: a
later bare `$83` uses the last timpani rate (`SD:326-327`).

`DAC_sample_rate` (`SD:339-345`, ROM `$71CC4` verified `12 15 1C 1D FF FF`):
`$88 = $12`, `$89 = $15`, `$8A = $1C`, `$8B = $1D`, `$8C-$8D = $FF`; `$8E-$8F` index
past the table (`SD:334-335`). The values are `dpcmLoopCounter(7375 × 1.30/1.20/0.97/0.95)`.

Shipped DAC track data uses `dKick $81`, `dSnare $82`, `dTimpani $83`, `dHiTimpani $88`,
`dMidTimpani $89`, `dLowTimpani $8A`, `dVLowTimpani $8B` (`INC:90-91`).

### 16.2 Z80 program (`sound/z80.asm`)

Loaded by `DACDriverLoad` (`S:1225-1240`: bus request, reset off, `KosDec` into Z80 RAM,
reset pulse, release) at boot (`S:416`) and again on entering the title screen
(`S:1903`). Interrupts stay disabled (`Z80:43-45`); the program is one polling loop:

- init (`Z80:46-62`): `zDAC_Status := 0`, `zDAC_Sample := 0`, bank register set to the
  `SegaPCM` bank (9 bit-writes).
- `zWaitDACLoop` (`Z80:74-77`): spin until `zDAC_Sample` bit 7 is set. `id -= $81` and
  write the index back (`Z80:79-80`), so the 68k reading the byte back sees a
  non-negative value once the Z80 has claimed it. `>= 6` (`$87+`) → `zPlay_SegaPCM`
  (only `$88` is ever written; `$84-$86` index past the three-entry `zPCM_Table`,
  `Z80:88`).
- DPCM (`Z80:84-181`): `zPCM_Table` entry = start, length, pitch (`djnz` count), pad
  (`Z80:208-220`). Set `zDAC_Status := $80`, `$2B := $80` (DAC on), `zDAC_Status := 0`.
  Per byte: high nibble then low nibble through `zDACDecodeTbl`
  (`sound/dac/dpcm/deltas.bin`, 16 bytes `00 01 02 04 08 10 20 40 80 FF FE FC F8 F0 E0 C0`)
  accumulated into an 8-bit value starting at `$80`; each sample is written as
  `$2A := acc` bracketed by `zDAC_Status := $FF` / `:= $1F` (`Z80:133-136,158-161`);
  after each sample `djnz` spins `pitch` times. After every byte (two samples) the Z80
  re-reads `zDAC_Sample`: if bit 7 is set a new request arrived and the current sample
  is abandoned immediately (`Z80:171-173`). Loop cost is 301 cycles per byte plus the
  pitch loops (`Z80:180`, `SD:24`).
- SEGA PCM (`Z80:187-206`): raw 8-bit bytes from the ROM window, `pcmLoopCounter(16000)`
  spins per sample, 90 cycles per sample plus the loop; not interruptible by a new
  `zDAC_Sample` write until it finishes (no status check in this loop).

Pitch formula (`SD:22-24`): `pcmLoopCounterBase(rate, base) = 1 + (Z80_Clock/rate - base + 6)/13`
(integer), `pcmLoopCounter` uses `base = 90`, `dpcmLoopCounter` uses `base = 150`.
Sample rates (`sound/dac/*/generated/*.inc:1`): kick 8250, snare 24000, timpani 7375,
Sega 16500 (the Sega loop hard-codes 16000 regardless). Hand-evaluating the formula
gives `$17` (kick), `$01` (snare), `$1B` (timpani), `$0B` (Sega); the timpani-derived
`DAC_sample_rate` bytes above were confirmed in ROM, the `zPCM_Table` bytes were not
(the Z80 block is Kosinski-compressed in ROM) — see open question 7.

### 16.3 68k/Z80 handshake summary

- 68k → Z80: `zDAC_Sample` (sample id, bit 7 = pending) and `zTimpani_Pitch`. Writes
  happen with the Z80 bus-requested (inside `UpdateMusic`), except `PlaySegaSound`.
- Z80 → 68k: `zDAC_Status` bit 7. `UpdateMusic` will not hold the bus while it is set
  (`SD:157-165`), which keeps the 68k from stalling the Z80 between its `$2A` address
  and data writes.
- The driver never stops a playing sample except by writing a new one; a DAC rest
  (`$80`) or a stopped DAC track just lets the current sample run out.

## 17. Data index

| Item | Anchor / ROM |
|---|---|
| `Go_*` pointer block | `SD:28-35`, ROM `$71990` |
| `PSG_Index`, envelopes | `SD:41-64` |
| `SpeedUpIndex` | `SD:74-93`, `$71A94` |
| `MusicIndex` (19 songs `$81-$93`) | `SD:99-119`, `$71A9C` |
| `SoundPriorities` | `SD:131-138`, `$71AE8` |
| `DAC_sample_rate` | `SD:339-345`, `$71CC4` |
| `FMDACInitBytes`, `PSGInitBytes` | `SD:964-971` |
| `SFX_BGMChannelRAM`, `SFX_SFXChannelRAM` | `SD:1093-1111` |
| `FMFrequencies` | `SD:1801-1809`, `$72790` |
| `PSGFrequencies` | `SD:2057-2063`, `$729CE` |
| `coordflagLookup` | `SD:2075-2127`, `$72A64` |
| `FMSlotMask` | `SD:2379`, `$72CAC` |
| `FMInstrumentOperatorTable` / `TLTable` | `SD:2440-2468` |
| Z80 driver (Kosinski) | `SD:2632`, `$72E7C` |
| Song includes | `SD:2644-2681` |
| `SoundIndex` (48 SFX `$A0-$CF`) | `SD:2686-2735`, `$78B44` |
| `SpecSoundIndex` | `SD:2740-2742`, `$78C04` |
| `SegaPCM` | `SD:2858`, `$79688`, must not cross a `$8000` boundary (`SD:2853-2857`) |
| Song header/flag byte layouts | `INC:246-384`, `INC:388-632`, `INC:758-766` |
| Voice byte layout (25 bytes, op order 1,3,2,4 per row) | `INC:917-967` (S1 uses the `else` rows `INC:960-965`) |

## 18. Open questions

Behaviours the source does not pin down, or that depend on state outside the driver.

1. **`StopSFX` stale `a3` (FixBugs #7).** When an SFX FM4 track is stopped while the
   special FM4 track is playing, `a5` is restored from an `a3` that was last set either
   by an earlier iteration of the same loop (`SD:1258`) or by the interrupt's caller.
   The source shows the corruption but not its concrete effect (which track the loop
   continues on, or whether it faults); it needs a trace of a fade-out during the GHZ
   waterfall to characterise.
2. **`SetVoice` with a byte-only `d0` (FixBugs #8, #10).** The upper bytes of `d0` at
   `StopSFX`/`StopSpecialSFX` entry come from whatever `UpdateMusic`'s caller left in
   `d0` (V-int: the `VBlank_Index` offset word; H-int: `VBlank_UpdateScreen` state). If
   the upper word is non-zero the voice offset loop runs thousands of extra 25-byte
   steps and uploads garbage. Whether that ever happens in shipped play is a question
   about the interrupt paths, not the driver.
3. **`$E6` on SFX tracks (FixBugs #19).** Which bank `SendVoiceTL` reads depends on
   `v_special_voice_ptr`, which is zero until the first `$D0` is requested. With a zero
   pointer the TL bytes come from ROM address `25*voice + 21`. Which shipped SFX contain
   `$E6` on an FM track has not been enumerated here.
4. **Second `UpdateMusic` per frame from H-int.** The LZ delayed-transfer path calls the
   driver twice in one frame (`S:1062`). The source is unambiguous that it happens; how
   often `f_doupdatesinhblank` is set during real LZ play is a game-state question.
5. **`cfFadeInToPrevious` and `$2B` (FixBugs #17).** The shipped path leaves the DAC
   enable register alone on restore. The fix writes `$2B := 0`, which only matters if
   the DAC became enabled between the 7-track song's load and its restore — and
   nothing in the driver enables it except `StopAllSound`, which also wipes the backup.
   The scenario the fix targets is not derivable from the S1 source alone.
6. **`Sound_PlayBGM` zero-FM path (FixBugs #4).** Not reachable with shipped data; the
   stale-register behaviour is noted, not characterised.
7. **`zPCM_Table` pitch bytes.** Computed by hand from the assembler expression
   (`SD:22-24`) and the `.inc` sample rates, not confirmed against the decompressed Z80
   block in the ROM. The 68k-side `DAC_sample_rate` bytes, which use the same function,
   matched the ROM under integer-division semantics, so the arithmetic is settled; only
   the Z80 block itself is unverified.
8. **DPCM decode-table nibble order and accumulator wrap.** The loop adds the table
   entry to an 8-bit accumulator with no clamping (`Z80:131-132`); overflow wraps. The
   sample files (`kick.dpcm` etc.) are generated by the disassembly's build tools and
   are not ROM-verified here.
9. **Overlap of the gosub stack with `LoopCounters`** (§2.2). Two nested `$F8` calls
   overwrite `LoopCounters[4..11]`. Whether any shipped song nests calls two deep while
   using loop indices `>= 4` has not been checked.
10. **Exact frame on which the first note sounds after a load.** `DurationTimeout := 1`
    means the first parse happens on the *next* driver call, and the load frame itself
    skips the track walk (§3.2). Whether the game-visible latency is one or two V-ints
    depends on where in the frame the request was queued relative to V-int; the
    driver side is fixed at "next call".
11. **`WriteFMI`/`WriteFMII` busy polling.** The driver polls YM2612 status bit 7 before
    both halves of every write; how long the shipped chip actually asserts busy is a
    hardware question outside this source.
