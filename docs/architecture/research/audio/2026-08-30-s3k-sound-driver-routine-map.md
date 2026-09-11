# S3K Z80 sound driver routine map

Sources-closed inventory of the Sonic 3 & Knuckles sound driver, built only
from the disassembly under `docs/skdisasm/`. No emulator source, no third-party
SMPS write-up, and no engine code was used as evidence for anything below;
where the disassembly is ambiguous the item is listed under
[open questions](#open-questions) instead of being resolved from memory.

Anchor conventions:

- `D:NNN` is `docs/skdisasm/Sound/Z80 Sound Driver.asm:NNN` (5315 lines).
- `K:NNN` is `docs/skdisasm/sonic3k.asm`, `S3:NNN` is `docs/skdisasm/s3.asm`,
  `KC:NNN` is `sonic3k.constants.asm`, `KM:NNN` is `sonic3k.macros.asm`,
  `INC:NNN` is `docs/skdisasm/Sound/_smps2asm_inc.asm`,
  `LP:NNN` is `docs/skdisasm/Lockon S3/LockOn Pointers.asm`.
- Z80 addresses are the driver's own `org 0` image addresses (the driver is
  copied to Z80 RAM at `$A00000` and runs from `0000h`).
- Byte values are written in the driver's `NNh` style.

## Bug-fix conditional

The driver does **not** use the 68k `FixBugs` symbol. Its own switch is
`fix_sndbugs = 0` (`D:16`), and there are 92 conditional sites (listed in
[section 12](#12-every-fix_sndbugs-site-and-the-shipped-path)). Every value
below describes the `fix_sndbugs = 0` branch, i.e. what the shipped ROM runs.
The second build switch is `SonicDriverVer` (`K:27` sets 4 for S&K, `S3:22`
sets 3 for Sonic 3), covered in [section 13](#13-the-two-driver-images-s3-vs-sk).

## 1. Installation, images, and memory map

### 1.1 Two driver images in the locked-on ROM

| Half | Build | Load path | Shape |
|---|---|---|---|
| S&K (`< 200000h`) | `SonicDriverVer = 4` (`K:27`), `Size_of_Snd_driver_guess = $E00`, `Size_of_Snd_driver2_guess = $690` (`K:41-42`) | `SndDrvInit` (`K:1404-1439`): Kosinski-decompresses `Z80_SoundDriver` to Z80 `0000h`, then `Z80_SoundDriverData` to `z80_SoundDriverPointers` (`1300h`), copies the 16 `Z80_DefaultVariables` (`K:1445-1462`, all zero) to `zDataStart`, sets `zPalFlag` from `Graphics_flags` bit 6, pulses Z80 reset. | Code image `0000h-12FFh` (assert at `D:4426`), data image at `1300h` (`D:4438-4448`). |
| S3 (`>= 200000h`) | `SonicDriverVer = 3` (`S3:22`), guesses `$1300` / `$843` (`S3:28-29`) | `SndDrvInit` (`S3:1566-1601`) copies `$1300+$843+$BC` raw bytes (uncompressed) to Z80 `0000h`; no Kosinski. | Same source, assembled with the S3 conditionals. |

Only the S&K image is ever installed in the locked-on cartridge: the 68k boots
from the S&K half (`SonicAndKnucklesStartup` `K:412-418`, `BlueSpheresStartup`
`K:357-362`), and the S3 half's image is a verbatim copy of the Sonic 3 ROM
whose bank bytes address `0xxxxxh` (see `bankswitchToMusicS3`, section 13).
The S3-half data the S&K driver *does* reach (AIZ–LBZ music, DAC `9Ch-C0h`,
S3 credits, 2P menu, countdown) is declared in the lock-on pointer file
(`LP:315-336` and following `DACDECLARE` entries) at `200000h`-relative
addresses, and the S&K bank macros carry enough bits to reach them.

### 1.2 Z80 address-space constants (`D:98-107`)

| Symbol | Value | Meaning |
|---|---|---|
| `z80_stack` | `2000h` | initial SP (`D:524`) |
| `z80_stack_end` | `1FA0h` | RAM declarations must end here (`D:214-217`) |
| `zYM2612_A0/D0` | `4000h/4001h` | YM part I address/data |
| `zYM2612_A1/D1` | `4002h/4003h` | YM part II address/data |
| `zBankRegister` | `6000h` | bank bit shifter |
| `zPSG` | `7F11h` | SN76489 write port |
| `zROMWindow` | `8000h` | 32 KiB banked ROM window |

### 1.3 Variable RAM (`zDataStart = 1C00h`, `D:113-173`)

| Addr | Symbol | Meaning / owner |
|---|---|---|
| 1C00-01 | unused | |
| 1C02 | `zPalFlag` | 68k writes 1 on PAL (`K:1425-1428`); read by `zVInt` |
| 1C03 | unused | |
| 1C04 | `zPalDblUpdCounter` | PAL double-update countdown |
| 1C05-07 | `zSoundQueue0..2` | driver-side queue (music, SFX, SFX) |
| 1C08 | `zTempoSpeedup` | 68k `Change_Music_Tempo` (`K:1517`) |
| 1C09 | `zNextSound` | popped queue entry |
| 1C0A | `zMusicNumber` | 68k `Play_Music` (`K:1471`) |
| 1C0B-0C | `zSFXNumber0/1` | 68k `Play_SFX` (`K:1493`) |
| 1C0D | `zFadeOutTimeout` | `zTempVariablesStart`; fade-out step counter |
| 1C0E | `zFadeDelay` | fade step spacing |
| 1C0F | `zFadeDelayTimeout` | fade step countdown |
| 1C10 | `zPauseFlag` | 68k writes 1 / 80h (`K:1547,1588,1601`) |
| 1C11 | `zHaltFlag` | `cfHaltSound` |
| 1C12 | `zFM3Settings` | written, never read |
| 1C13 | `zTempoAccumulator` | `TempoWait` |
| 1C14 | unused | |
| 1C15 | `unk_1C15` | written, never read |
| 1C16 | `zFadeToPrevFlag` | `cfFadeInToPrevious`; 29h = 1-up in progress, FFh = restore requested |
| 1C17 | `unk_1C17` | refresh register dump, never read |
| 1C18 | `unk_1C18` | written, never read |
| 1C19 | `zUpdatingSFX` | 1 during SFX pass, 0 during music pass |
| 1C1A-20 | unused | |
| 1C21 | `unk_1C21` | S3 only, never read |
| 1C22-23 | unused | |
| 1C24 | `zCurrentTempo` | main tempo modifier |
| 1C25 | `zContinuousSFX` | last continuous SFX index |
| 1C26 | `zContinuousSFXFlag` | 80h = extend loop |
| 1C27 | `zSpindashRev` | escalating transpose |
| 1C28 | `zRingSpeaker` | 0/1 ring side toggle |
| 1C29 | `zFadeInTimeout` | fade-in step counter |
| 1C2A-2B | `zVoiceTblPtrSave` | 1-up save |
| 1C2C | `zCurrentTempoSave` | 1-up save |
| 1C2D | `zSongBankSave` | 1-up save |
| 1C2E | `zTempoSpeedupSave` | 1-up save |
| 1C2F | `zSpeedupTimeout` | speed-up double-update countdown |
| 1C30 | `zDACIndex` | bit 7 = playing; bits 0-6 = 1-based sample id |
| 1C31 | `zContSFXLoopCnt` | continuous SFX per-track loop budget |
| 1C32 | `zSFXSaveIndex` | index of channel being overridden |
| 1C33-34 | `zSongPosition` | header cursor during load |
| 1C35-36 | `zTrackInitPos` | init-byte cursor during load |
| 1C37-38 | `zVoiceTblPtr` | current song voice table |
| 1C39-3A | `zSFXVoiceTblPtr` | current SFX voice table |
| 1C3B | `zSFXTempoDivider` | from SFX header |
| 1C3C-3D | unused | |
| 1C3E | `zSongBank` | bank byte of the current song |
| 1C3F | `PlaySegaPCMFlag` | 1 = run `zPlaySEGAPCM` |
| 1C40 | `zTracksStart` | see 1.4 |

`zStopAllSound` (`D:2460`) zero-fills `1C0Dh` through `1FD3h` — the whole
temp/track/save region *plus* `34h` bytes into the stack area (`D:2464-2469`,
shipped `+34h`). Everything from `zFadeOutTimeout` to `PlaySegaPCMFlag`
inclusive (including `zSongBank`, `zDACIndex`, `zSpindashRev`, `zRingSpeaker`,
`zFadeToPrevFlag`, `zPauseFlag`, `zHaltFlag`) is therefore reset by any
"stop all"; the input mailboxes (`1C05-1C0C`) are not.

### 1.4 Track RAM (`zTrack.len = 30h`)

| Slot | Addr | Init `VoiceControl` |
|---|---|---|
| `zSongFM6_DAC` | 1C40 | 06h |
| `zSongFM1..FM5` | 1C70, 1CA0, 1CD0, 1D00, 1D30 | 00h, 01h, 02h, 04h, 05h |
| `zSongPSG1..3` | 1D60, 1D90, 1DC0 | 80h, A0h, C0h |
| `zSFX_FM3..FM6` | 1DF0, 1E20, 1E50, 1E80 | from SFX header |
| `zSFX_PSG1..3` | 1EB0, 1EE0, 1F10 | from SFX header |
| `zTracksSFXEnd` | 1F40 | |
| `zSaveSongDAC..PSG3` | 1DF0 … 1F70 (overlaps SFX area) | 1-up backup |
| `zTracksSaveEnd` | 1FA0 | = `z80_stack_end` |

Init values come from `zFMDACInitBytes` (`D:1899-1907`; a seventh `80h,06h`
entry exists only in the shipped build and is used only if a song header
declares seven FM+DAC tracks) and `zPSGInitBytes` (`D:1913-1916`).

## 2. `zTrack` structure (`D:21-96`)

| Off | Field | Meaning |
|---|---|---|
| 00 | `PlaybackControl` | bit 0 noise channel (PSG) / FM3 special mode (FM); bit 1 do-not-attack next note; bit 2 SFX overriding this track; bit 3 alternate-frequency mode; bit 4 resting; bit 5 pitch-slide (never set in this driver); bit 6 sustain frequency (set only by mod-envelope `81h/83h`, never cleared in the shipped build); bit 7 playing |
| 01 | `VoiceControl` | bits 0-1 FM channel; bit 2 part II (FM4-6/DAC); bits 5-6 PSG channel; bit 7 PSG track |
| 02 | `TempoDivider` | note-length multiplier (`zComputeNoteDuration`) |
| 03-04 | `DataPointer` | current position in track data (Z80 address; ROM data through `8000h` window) |
| 05 | `Transpose` | signed semitone offset |
| 06 | `Volume` | FM: 7-bit attenuation added to carrier TL; PSG: 4-bit attenuation |
| 07 | `ModulationCtrl` | 0 off; 80h normal modulation; 1..8 = mod envelope index+1 |
| 08 | `VoiceIndex` | FM voice / PSG volume envelope (1-based, 0 = none) |
| 09 | `StackPointer` | offset of top of per-track call stack; init 30h; each `F8` pushes two bytes (2Eh, 2Ch, then 2Ah = `Voices`, 28h = `LoopCounters`) |
| 0A | `AMSFMSPan` | last B4h value; init C0h |
| 0B | `DurationTimeout` | frames left in current note; init 1 |
| 0C | `SavedDuration` | last explicit duration (already × divider) |
| 0D | `FreqLow` / `SavedDAC` | FM/PSG frequency low byte; DAC: last sample id |
| 0E | `FreqHigh` | frequency high byte (FM: block bits included) |
| 0F | `VoiceSongID` | 81h+song index for cross-song voices (`EF` with bit 7) |
| 10 | `Detune` | signed frequency displacement (`E1`) |
| 11 | `Unk11h` | receives the dead "pitch slide" byte in alt-freq mode |
| 12-16 | unused | |
| 17 | `VolEnv` | PSG/FM volume-envelope index |
| 18 | `FMVolEnv` / `HaveSSGEGFlag` | FM vol-env index (1-based); 80h = custom SSG-EG present |
| 19 | `FMVolEnvMask` / `SSGEGPointerLow` | |
| 1A | `PSGNoise` / `SSGEGPointerHigh` | last `F3` value / SSG-EG data pointer high |
| 1B | `FeedbackAlgo` | copy of B0h byte |
| 1C-1D | `TLPtr` | pointer to the 4 TL bytes of the current voice (in the voice data, ROM or UVB) |
| 1E | `NoteFillTimeout` | frames until key-off |
| 1F | `NoteFillMaster` | reload value for 1E |
| 20-21 | `ModulationPtr` | pointer to the four `F0` parameter bytes |
| 22 | `ModulationValLow` / `ModEnvSens` | accumulated modulation / envelope multiplier−1 |
| 23 | `ModulationValHigh` | |
| 24 | `ModulationWait` | |
| 25 | `ModulationSpeed` / `ModEnvIndex` | |
| 26 | `ModulationDelta` | |
| 27 | `ModulationSteps` | |
| 28-29 | `LoopCounters` | `F7`/`EB` counters indexed by parameter (may overflow) |
| 2A-2B | `Voices` | SFX-only: voice table pointer used when `zUpdatingSFX` = 1 |
| 2C-2F | `Stack_top` | call stack |

## 3. Entry, update cadence, tempo, pause

### 3.1 Boot (`D:365-370`, `D:523-548`)

`EntryPoint` (`0000h`): `di; di; im 1; jp zInitAudioDriver`. In S&K a filler
byte `F2h` follows (`D:374-376`). `zInitAudioDriver`: SP = `2000h`, ~65 k
iteration busy loop, `zStopAllSound`, `zSongBank` = bank of `Snd_Bank2_Start`
(S&K; S3 stores a junk `zmakeSongBank(0B0000h)` — `D:535-541`), zero
`zSpindashRev`, `zDACIndex`, `PlaySegaPCMFlag`, `zRingSpeaker`,
`zPalDblUpdCounter` = 5, `ei`, `jp zPlayDigitalAudio`. The Z80 then lives in
the DAC loop forever; all music/SFX work happens inside the interrupt.

### 3.2 Per-frame interrupt `zVInt` (`0038h`, `D:470-521`)

Triggered once per vertical blank. Sequence:

1. `di`, save `af`, `iy`, shadow `bc/de/hl`.
2. `.doupdate`: store the refresh register to `unk_1C17` (shipped only,
   `D:477-480`), `call zUpdateEverything`.
3. PAL: if `zPalFlag`, decrement `zPalDblUpdCounter`; when it reaches 0 reload
   it (S&K: 5, `D:488-495`; S3 shipped: 6) and **jump back to `.doupdate`** —
   a second full update in the same frame. So PAL runs 7 updates per 6 frames
   (S&K, reload 5) or 8 per 7 (S3, reload 6).
4. Bank-switch to `DAC_Banks[zDACIndex & 7Fh]` (`D:509-516`; table at
   `D:630-647`, entry 0 = `DacBank1` so an idle DAC still yields a valid bank).
5. Restore registers, `ld b,1`, `ret`. `b = 1` makes the interrupted
   `djnz $` in the DAC loop fall through immediately (see 9.1).

### 3.3 `zUpdateEverything` (`D:653-657`) → `zUpdateSFXTracks` → `zUpdateMusic`

Order inside one update:

1. `zPauseUnpause` (`D:2232`) — may abort the whole update (3.6).
2. `zUpdateSFXTracks` (`D:727-764`): `zUpdatingSFX` = 1, `bankswitchToSFX`,
   run `zUpdateFMorPSGTrack` for each of the 7 SFX slots whose bit 7 is set.
3. Tail of `zTrackUpdLoop` (`D:747-764`, executed after *both* the SFX pass
   and the music pass): speed-up handling (3.5).
4. `zUpdateMusic` (`D:658-725`): `TempoWait`, `zDoMusicFadeOut`,
   `zDoMusicFadeIn`, 1-up gating of the mailbox (4.6), `zFillSoundQueue` +
   three `zCycleSoundQueue` if any mailbox byte is non-zero (4.2), bank-switch
   to `zSongBank`, `zUpdatingSFX` = 0, `zFadeInToPrevious` if
   `zFadeToPrevFlag == FFh`, `zUpdateDACTrack` if the DAC slot is playing, then
   `zUpdateFMorPSGTrack` for FM1..PSG3.

Consequences: SFX tracks are advanced *before* the queue is read, so a newly
requested SFX is initialised during the music phase (`DurationTimeout` = 1)
and plays its first note on the **next** frame's SFX pass; a newly loaded song
is initialised and then immediately walked by the music loop in the same
frame, so its first notes sound on the frame of the request.

### 3.4 Main tempo `TempoWait` (`D:2607-2625`)

`zTempoAccumulator += zCurrentTempo`; on 8-bit carry every one of the nine
music track `DurationTimeout` bytes is incremented (the frame is "skipped").
Tempo `T` therefore makes `T/256` of the updates no-ops for note timing; `0`
runs every frame, `80h` every other frame. SFX tracks are not affected. The
tempo comes from song header byte 5 (`D:1822-1824`) and `cfSetTempo`
(`FF 00`, `D:3861`). The per-track `TempoDivider` (header byte 4, or the SFX
header byte 2) multiplies every duration byte in `zComputeNoteDuration`
(`D:1082-1093`, 8-bit result, overflow ignored) and note fill (`cfNoteFill`).

### 3.5 Tempo speed-up (speed shoes, special stage)

68k `Change_Music_Tempo` (`K:1517-1522`) writes `zTempoSpeedup`. Callers:
speed shoes on (`K:40841`, value 8), speed shoes off (`K:22091`, value 0), and
the special stage rate ramp (`K:11455-11464`, `d0 = 2*(20h - rate_hi) + 8`,
so 8 at full rate and larger — slower — before it). The tail at
`D:751-764` runs after every track loop: if `zTempoSpeedup != 0` and
`zSpeedupTimeout == 0`, reload the timeout from `zTempoSpeedup` and
`jp zUpdateMusic` (an extra music update, which itself runs the tail again);
otherwise decrement. Because the tail executes at least twice per frame the
countdown of 8 yields one extra music update every fourth frame (5 music
updates per 4 frames). The extra pass also advances fades and `TempoWait`.
`zStopAllSound` clears `zTempoSpeedup` (`D:2471-2472`), so the 68k must resend
it after any song change; the 1-up path saves/zeroes/restores it (4.6).

### 3.6 Pause / unpause `zPauseUnpause` (`D:2232-2305`)

68k writes `zPauseFlag` = 1 to pause and `80h` to resume (`K:1547`, `K:1588`,
`K:1601`; the frame-advance path writes `80h` too).

- Flag 1: `pop de` (discard the return into `zUpdateEverything` so the
  following `ret` lands in `zVInt`), `dec a` → 0, set flag = 2, jump to
  `zPauseAudio` (`D:2541-2585`): B4h-B6h (FM1-3, part I) and B4h-B5h (FM4-5,
  part II) written 0 (pan off = silent; FM6 pan untouched), key-off (28h) for
  channel codes 0-5 (including the invalid 3), then `zPSGSilenceAll`
  (`D:2587-2597`: 9Fh, BFh, DFh, FFh). Shipped code calls `zPSGSilenceAll`
  once more before that (`D:2542-2544`).
- Flag 2 (every later frame while paused): `pop de`, `dec a` → 1, `ret nz` —
  **no track, fade, queue, or tempo processing at all**. A DAC sample already
  playing finishes on its own (the DAC loop is not stopped).
- Flag 80h: clear the flag; if `zFadeOutTimeout != 0` jump to `zStopAllSound`
  (a pause during a fade-out kills the song). Otherwise re-send B4h for six
  slots starting at `zSongFM1` — FM1-5 plus `zSongPSG1` (`D:2251-2257`; the
  PSG "track" is harmless because `zWriteFMIorII` returns on bit 7 of
  `VoiceControl`, `D:562-564`), only for tracks with bit 7 set unless
  `zHaltFlag` is non-zero. Then a second loop meant for SFX tracks starts at
  `zTracksSFXEnd` (`1F40h`) for 7 slots (`D:2277-2286`), i.e. it reads the
  1-up save slots PSG2/PSG3, the stack area, and `2000h-208Fh`; it never
  touches the real SFX slots. Net shipped effect: an SFX running across a
  pause on FM3-5 keeps pan = 0 (silent) until its next `E0` or its end.

### 3.7 Halt (`cfHaltSound`, `FF 02`, `D:3887-3930`)

Non-zero: `zHaltFlag` = value, clear bit 7 and key-off all nine music slots,
`zPSGSilenceAll`. Zero: set bit 7 on all nine slots (including slots the song
never used). Not reached by any shipped stream (see the meta-command
reachability note below).

## 4. Requests, queue, dispatch, channel ownership

### 4.1 68k interface

| Routine | Effect |
|---|---|
| `Play_Music` (`K:1471-1476`) | `zMusicNumber = d0` (overwrites) |
| `Play_SFX` (`K:1493-1510`) | if `d0 == zSFXNumber0` ignore; else if `zSFXNumber0 == 0` store there, else store in `zSFXNumber1` (overwriting) |
| `Play_SFX_Local` (`K:1482`) | dead S2 leftover |
| `Play_SFX_Continuous` (`K:180655-180660`) | `Play_SFX` only when `(V_int_run_count+3) & 0Fh == 0` (every 16 frames) |
| `Change_Music_Tempo` (`K:1517`) | `zTempoSpeedup = d0` |
| pause | `zPauseFlag` 1 / 80h |

All 68k writes bracket the Z80 with `stopZ80`/`startZ80` (`KM:94-104`). The 68k
never reads driver RAM (`grep 'Z80_RAM+z'` in `sonic3k.asm` is writes only).
Any ID may be passed to either mailbox — `Play_Music` is used for `cmd_*`
values and `Play_SFX` for `cmd_MutePSG`/`cmd_StopSFX` (`K:64983-64987`).

### 4.2 Queue (`zFillSoundQueue` `D:2628-2641`, `zCycleSoundQueue` `D:1619-1631`)

When any of `zMusicNumber`, `zSFXNumber0`, `zSFXNumber1` is non-zero,
`zFillSoundQueue` copies the three bytes into `zSoundQueue0..2` and clears the
mailboxes. `zCycleSoundQueue` is then called three times: pop `zSoundQueue0`
into `zNextSound`, shift the others down, clear `zSoundQueue2`, and fall into
`zPlaySoundByIndex` with `a = zNextSound`. Order is therefore music slot, SFX
slot 0, SFX slot 1. A zero entry falls to `zPlayMusic`, which returns on the
negative index (`D:1718-1719`). There is **no priority list** —
`zID_PriorityList` (`D:239`) is unused; a later request always takes the
channel.

### 4.3 Type dispatch `zPlaySoundByIndex` (`D:1641-1665`)

Checked in this order (S&K):

| Test | ID range | Target |
|---|---|---|
| `== mus_CreditsK` | `DCh` | `zPlayMusicCredits` (`D:1709-1713`; S&K only) → music index `32h` |
| `== cmd_SEGA` | `FFh` | `zPlaySegaSound` (4.8) |
| `< mus__End` | `01h-32h` | `zPlayMusic` (4.4) |
| `< sfx__End` | `33h-DFh` | `zPlaySound_CheckRing` (4.5) |
| `< cmd__First` | `E0h` | `zStopAllSound` |
| `< cmd__End` | `E1h-E5h` | `zFadeEffects` (`D:1667-1672`): E1 `zFadeOutMusic`, E2 `zStopAllSound`, E3 `zPSGSilenceAll`, E4 `zStopSFX`, E5 `zFadeOutMusic`; shipped also zeroes `unk_1C18` first (`D:1659-1662`) |
| otherwise | `E6h-FEh` | `zStopAllSound` (so `cmd_StopSEGA` = `FEh` left in the mailbox after a SEGA chime becomes a stop-all on the next frame, S&K) |

IDs from `KC:1420-1670`: `cmd_FadeOut E1`, `cmd_Stop E2`, `cmd_MutePSG E3`,
`cmd_StopSFX E4`, `cmd_FadeOut2 E5`, `cmd_StopSEGA FE`, `cmd_SEGA FF`;
music `01h-32h` (`mus_ExtraLife = 2Ah`, `mus_Ending = 32h`); SFX `33h-DBh`,
`mus_CreditsK = DCh`, `DDh-DFh` unused SFX slots that alias `Sound_DB`
(`D:4667`). `sfx__FirstContinuous = BCh`, `sfx_Spindash = ABh`.

### 4.4 Music load `zPlayMusic` → `zBGMLoad` (`D:1717-1885`)

Non-1-up songs: `zPlayMusic_DoFade` = `zStopAllSound` then `zBGMLoad`:

1. Bank byte from `z80_MusicBanks[index]` (`D:2841-2867`) via self-modifying
   `ld a,(z80_MusicBanks)` (`D:1800-1806`); store in `zSongBank`; switch.
2. Write B6h = C0h on part II directly (FM6/DAC pan both speakers,
   `D:1814-1819`).
3. `hl = z80_MusicPointers[index]` (`D:4585-4612`, `GetPointerTable` id 4).
   Header layout (from the reads at `D:1822-1830`, `D:1838-1849`,
   `D:1857-1875`): `+0..1` voice table pointer → `zVoiceTblPtr`; `+2` FM+DAC
   track count; `+3` PSG track count; `+4` tempo divider; `+5` main tempo →
   `zTempoAccumulator` and `zCurrentTempo`; then per FM/DAC track 4 bytes
   (pointer, transpose, volume); then per PSG track 6 bytes (pointer,
   transpose, volume, `ModulationCtrl`, `VoiceIndex`).
4. Each track: `PlaybackControl` = 80h, `VoiceControl` from the init tables,
   `TempoDivider` = header byte 4, copied header bytes, then
   `zInitFMDACTrack`/`zZeroFillTrackRAM` (`D:2171-2199`: `ModulationCtrl` = 0,
   `VoiceIndex` = 0 for FM/DAC, `StackPointer` = 30h, `AMSFMSPan` = C0h,
   `DurationTimeout` = 1, bytes 0Ch-2Fh zero).
5. `zClearNextSound`.

Song data pointers are Z80 addresses inside the `8000h` window
(`zmake68kPtr`, `D:353`), so a song and its tracks must sit in one 32 KiB bank
(`finishBank`, `KM:288-294`). Voice tables are either in-bank or the universal
bank in Z80 RAM (`z80_UniVoiceBank`, `D:4674`, `INC:291-303`).

### 4.5 SFX load `zPlaySound_CheckRing` → `zPlaySound` (`D:1919-2107`)

1. Ring toggle (`D:1919-1926`): index `sfx__First` (0, Ring Right) toggles
   `zRingSpeaker` and **plays index `zRingSpeaker`** — i.e. alternately
   `Sound_33` (right) and `Sound_34` (left). The 68k only ever requests
   `sfx_RingRight` (`K:6317`, `35456`, `40810`, …); `sfx_RingLeft` is never
   requested directly. `zRingSpeaker` is cleared by `zInitAudioDriver` and by
   every `zStopAllSound` (it lies in the wiped range).
2. `bankswitchToSFX`, `zUpdatingSFX` = 0, `c = zID_SFXPointers`.
3. `sfx_Spindash` → `zPlaySound` directly (no rev reset). Index `< BCh` →
   `zPlaySound_Normal` (`zSpindashRev` = 0) → `zPlaySound`. Index `>= BCh`
   (continuous): if equal to `zContinuousSFX`, set `zContinuousSFXFlag` = 80h,
   reload `zContSFXLoopCnt` from the SFX header's track count and **return
   without restarting** (`D:1946-1958`); otherwise flag = 0, remember the
   index, and play.
4. `zPlaySound` (`D:1975-2107`): SFX header `+0..1` voice pointer →
   `zSFXVoiceTblPtr`; `+2` tempo divider → `zSFXTempoDivider`; `+3` track
   count → `zContSFXLoopCnt`; then per track 6 bytes: `80h`, channel id,
   pointer, transpose, volume. For each track:
   - `zGetSFXChannelPointers` (`D:2109-2169`): FM ids `02h,04h,05h,06h` map to
     slots FM3..FM6 (shipped `dec a` for ids with bit 2, `D:2113-2117`); PSG
     ids `80h,A0h,C0h,E0h` map to PSG1,PSG2,PSG3,PSG3. For PSG the shipped path
     first calls `zSilencePSGChannel` with a stale `ix`, then unconditionally
     writes `FFh` (noise silence) to the PSG (`D:2121-2140`). Returns `ix` =
     SFX slot, `hl` = the overridden music slot (`zSFXOverriddenChannel`,
     `D:2215-2227`), `zSFXSaveIndex` = slot index.
   - `set 2,(hl)`: the music slot is marked overridden.
   - If the SFX slot's previous `VoiceControl` was `02h` (FM3) call
     `zFM3NormalMode` (27h = 0).
   - Copy header bytes, `zInitFMDACTrack`, store `Voices` =
     `zSFXVoiceTblPtr`, `zKeyOffIfActive` (28h key-off for FM slots),
     `zFMClearSSGEGOps` (90h-9Ch = 0; a no-op for PSG because
     `zWriteFMIorII` returns on bit 7).
   - Dead code around `zUpdatingSFX` and a compare against `(iy+1)` in ROM
     (`D:2006-2020`, `D:2043-2069`, `D:2071-2091`) has no effect.

Effect on the overridden music track while bit 2 is set: FM register writes
are dropped in `zWriteFMIorII` (`D:564-565`), `zFMSendFreq` (`D:816-817`),
`zFMNoteOn` (`D:1121-1126`), `zKeyOffIfActive`; PSG writes are skipped
(`D:4081-4082`, `zRestTrack` `D:4222-4223`); DAC sample queuing is skipped
(`D:2899-2901`). The track's data pointer, durations, loops and volume keep
advancing normally.

### 4.6 SFX end and music restore `cfStopTrack` (`F2`, `D:3443-3535`)

Clear bit 7, `unk_1C15` = 1Fh, `zKeyOffIfActive`, then
`zGetSFXChannelPointers` with `c = VoiceControl`. Music tracks (`zUpdatingSFX`
= 0) exit via `zStopCleanExit` (`D:3514-3518`: `pop ix; pop hl; pop hl; ret`
— drops the coordination-flag return and the `zGetNextNote` return, so the
`ret` lands in `zTrackUpdLoop`). For a DAC track the same three pops return to
the *caller of `zUpdateMusic`*, so on the frame a DAC track executes `F2`/`E3`
the FM/PSG music tracks of that same update are skipped (derived from the
stack shape; see open questions).

SFX tracks (`zUpdatingSFX` = 1): `ix` = overridden music slot; clear its bit 2;
PSG slot → `zStopPSGTrack` (`D:3521-3532`: if bit 0 and `PSGNoise` has bit 7,
resend the noise byte); FM slot playing → if FM3, write 27h = `4Fh` (special)
or `0Fh` (normal) per bit 0 (`D:3474-3481`); re-upload the music track's voice
— `VoiceIndex` negative → `zSetVoiceUploadAlter` (cross-song) else switch to
`zSongBank` (`bankswitchToMusic`), `zGetFMInstrumentOffset` from
`zVoiceTblPtr`, `zSendFMInstrument`, switch back to SFX bank — then if
`HaveSSGEGFlag` bit 7, `zSendSSGEGData`. The music track's frequency is not
resent until its next update (its own `zUpdateFreq`/`zFMSendFreq` path resumes
now that bit 2 is clear).

`zStopSFX` (`cmd_StopSFX`, `D:1675-1699`): `zUpdatingSFX` = 1 and
`zSilenceStopTrack` (`D:1701-1705`, two dummy pushes then `cfSilenceStopTrack`)
for every playing SFX slot. Because `zFMSilenceChannel` → `zKeyOnOff` writes
28h with `c = VoiceControl` for PSG slots too (`D:2656-2665`, comment at
`D:2663`), a PSG SFX stopped this way emits a spurious key-on of FM1
operators (data `80h`/`A0h`/`C0h`).

### 4.7 Continuous SFX (`cfLoopContinuousSFX`, `FC`, `D:3712-3743`)

If `zContinuousSFXFlag == 80h`: decrement `zContSFXLoopCnt`; jump to the
target; when the count reaches zero clear the flag (each track consumes one
unit, so all tracks loop once per 68k re-request). Otherwise clear
`zContinuousSFX` (and, shipped, the flag again, `D:3718-3720`), skip the
2-byte target, and let the track run on to its `F2`. 30 SFX files use it
(`BC`-`DB`); the 68k keeps them alive with `Play_SFX_Continuous` every 16
frames.

### 4.8 SEGA chime `zPlaySegaSound` (`D:2703-2716`) / `zPlaySEGAPCM` (`D:4372-4419`)

`cmd_SEGA`: `zStopAllSound`, `PlaySegaPCMFlag` = 1 (shipped S&K does not clear
the mailboxes/queue — `D:2705-2716`), `pop hl; ret` — abandons the rest of
`zUpdateMusic` (the `ret` returns to `zVInt`). The DAC idle loop then enters
`zPlaySEGAPCM` with interrupts **disabled**: S&K clears the flag first, S3
calls `zFillSoundQueue` (`D:4374-4379`); enables DAC (2Bh = 80h), switches to
the SEGA bank (`bankswitch3`), streams `SEGA_PCM.size` unsigned 8-bit bytes to
2Ah, polling `zMusicNumber` for `cmd_StopSEGA` each byte. No `zVInt` runs
during the chime, so nothing else updates. Per-byte loop is 105 T-states plus
`13*(b-1)` for `b = pcmLoopCounter(SEGA_PCM.sample_rate)` (`KM:270-271`,
`D:4396-4412`). The 68k sequence is at `K:5485-5500` (play, wait ≤3 s or
Start, `cmd_StopSEGA`). After the chime, S3 clears the flag and the three
mailboxes (`D:4415-4420`); S&K leaves `FEh` in `zMusicNumber` (see 4.3).

### 4.9 1-up save/restore (`zPlayMusic` `D:1721-1784`, `zFadeInToPrevious` `D:2725-2789`)

`mus_ExtraLife` request:

- If `zFadeInTimeout != 0` (a previous restore is still fading in): clear all
  three mailboxes, the queue and `zNextSound`, and **drop the request**
  (`D:1725-1737`).
- Else if `zFadeToPrevFlag == 29h` (1-up already playing): `zBGMLoad` again
  without re-saving (`D:1739-1742`).
- Else: clear mailboxes/queue, save `zSongBank`, `zTempoSpeedup` (then zero
  it), copy the nine music slots to `zTracksSaveStart` (`1DF0h`, on top of the
  SFX slots), strip bit 7 of each saved `PlaybackControl` — the shipped
  `set 2,(hl)` is immediately overwritten by `ld (hl),a` (`D:1763-1774`), so
  bit 2 is *not* set here — set `zFadeToPrevFlag` = 29h, save
  `zCurrentTempo` and `zVoiceTblPtr`, `zBGMLoad` (no `zStopAllSound`, so the
  save area survives; the 1-up's own tracks start with bit 2 clear and
  co-write channels with any SFX until that SFX ends).

While `zFadeToPrevFlag == 29h` (`D:663-676`): a queued `mus_ExtraLife` is
cleared; a queued music ID `< 32h` is **left in `zMusicNumber`** (deferred)
while both SFX mailboxes are cleared; any other value (`32h`, commands, SFX
via `Play_Music`) is cleared. `zFillSoundQueue` is not run, so no SFX can
start during the jingle. Because SFX slots overlap the save area, the SFX pass
still advances whatever SFX tracks were live, corrupting the saved copy (see
open questions).

Restore: the 1-up track ends with `E2 FF` → `zFadeToPrevFlag` = FFh → next
music update calls `zFadeInToPrevious`: flag = 0, restore tempo, speed-up,
voice table, bank; copy the save area back; `PlaybackControl |= 84h` on the
DAC slot (`D:2748-2757`) and on FM1..PSG3; for FM slots clear bit 2,
`Volume += 40h`, re-upload the voice (`zGetFMInstrumentPointer` +
`zSendFMInstrument`); `zFadeInTimeout` = 40h, `zFadeDelay` =
`zFadeDelayTimeout` = 2. PSG and DAC slots keep bit 2 (silent) until
`zDoMusicFadeIn` finishes (5.3). Frequencies are not resent until each track's
next note.

Related: `cfFadeInToPrevious` (`E2 xx`, `D:3077-3079`) stores any byte;
`smpsNop` values `00h`, `01h`, `25h` appear in five songs (Countdown, Chaos
Emerald, Game Complete ×2, S&K Credits) and simply sit in `zFadeToPrevFlag`
with no driver effect other than the two magic values.

## 5. Fades and stop

### 5.1 `zFadeOutMusic` (`E1`/`E5`, `D:2307-2315`) and `zDoMusicFadeOut` (`D:2331-2391`)

Start: `zFadeOutTimeout` = 28h, `zFadeDelayTimeout` = `zFadeDelay` = 6, then
`zHaltDACPSG` (`D:2317-2328`): zero the `PlaybackControl` of the DAC, PSG1,
PSG2, PSG3 slots and `zPSGSilenceAll` — DAC and PSG stop immediately, FM
fades. Each music update: if the timeout is negative call `zHaltDACPSG` and
clear bit 7 (legacy, nothing sets it here); decrement `zFadeDelayTimeout`;
when it expires reload 6 and decrement `zFadeOutTimeout`; at zero
`zStopAllSound`; otherwise switch to `zSongBank` and for the six FM/DAC slots
`Volume++` (clamped at 7Fh) and `zSendTL` for slots that are playing and not
SFX-overridden. 40 steps × 6 updates = 240 music updates (≈4 s NTSC; faster
under speed-up or PAL double updates).

### 5.2 `zDoMusicFadeIn` (`D:2393-2458`)

While `zFadeInTimeout != 0`: switch to `zSongBank`; `zFadeDelay--`; when it
hits zero reload from `zFadeDelayTimeout` (2), `Volume--` and `zSendTL` on the
five FM slots FM1-FM5 (the DAC slot is skipped), `zFadeInTimeout--`; when that
reaches zero clear bit 2 on PSG1-3 and on the DAC slot. 40h steps × 2 = 128
music updates during which PSG and DAC are mute.

### 5.3 `zStopAllSound` (`D:2460-2529`)

Wipe `1C0Dh-1FD3h` (1.3), `zTempoSpeedup` = 0, then for the six FM channel
codes in `zFMDACInitBytes`: `zFMSilenceChannel` (D1L/RR = FFh and TL = 7Fh on
all four operators, key-off) and `zFMClearSSGEGOps`; shipped also re-zeroes
`zFadeOutTimeout` (`D:2500-2504`); `zPSGSilenceAll`; 2Bh = 0 (DAC off);
`zFM3NormalMode` (`D:2511-2520`: `zFM3Settings` = 0, 27h = 0);
`zClearNextSound`. Note that a DAC sample in flight is cut because
`zDACIndex` is wiped and the DAC loop tests bit 7 every nibble (`D:4343-4345`).

### 5.4 Key on/off helpers

- `zFMNoteOn` (`D:1113-1141`): skip if frequency is zero or bits 1/2 of
  `PlaybackControl` (shipped tests `06h`, not `16h`, so a *resting* track
  still keys on — but the callers already returned on bit 4); write 28h with
  `F0h | channel`.
- `zKeyOffIfActive` (`D:1143-1150`) → `zKeyOff` (`D:1156-1162`, returns for
  PSG) → `zKeyOnOff` (`D:1168-1177`): 28h with `c` = channel (operators off).

## 6. Track update and note parsing

### 6.1 FM `zUpdateFMorPSGTrack` (`D:766-812`)

`zTrackRunTimer` (`D:1102-1109`, `DurationTimeout--`). Expired: `zGetNextNote`;
if resting return; `zPrepareModulation`, `zUpdateFreq`, `zDoModulation`,
`zFMSendFreq`, `zFMNoteOn`. Still running: if resting return; `zDoFMVolEnv`;
note fill countdown → `zKeyOffIfActive` when it reaches zero; `zUpdateFreq`;
if bit 6 (sustain) return; `zDoModulation` and fall into `zFMSendFreq`
(`D:815-873`): A4h then A0h via `zWriteFMIorII` (dropped if SFX-overridden).
FM3 special mode instead writes ADh/AEh/ACh/A6h (+ A9h/AAh/A8h/A2h) with
frequency + a word read from `(de)` — `de` is whatever the preceding call left
(`D:843-845`), see open questions.

### 6.2 PSG `zUpdatePSGTrack` (`D:4058-4138`)

Same timer; new note: `zGetNextNote`, return if resting, `zPrepareModulation`.
Running: note fill → `zRestTrack` (silences the channel unless overridden).
Then `zUpdateFreq`, `zDoModulation`; if SFX-overridden return; write
`(l & 0Fh) | VoiceControl` and `((l & F0h) | h) >> 4` (nibble swap) to the
PSG; volume = `Volume` + envelope value (`zDoVolEnv` when `VoiceIndex != 0`);
return if resting; if the sum has bit 4 set use 0Fh; `| VoiceControl + 10h`,
`+ 20h` more when bit 0 (noise) — the volume goes to the noise register while
the tone still goes to PSG3's tone register.

### 6.3 DAC `zUpdateDACTrack` (`D:2869-2918`)

Timer; on expiry read bytes: `>= E0h` coordination flag (`zHandleDACCoordFlag`
returns to `loc_BE9`, `D:2921-2927`); `>= 80h` sample id (stored in
`SavedDAC`); `< 80h` duration only → reuse `SavedDAC`. `80h` is a rest. For a
sample: `zKeyOffIfActive`, `zFM3NormalMode`, and unless the DAC slot has bit 2
set store the id in `zDACIndex` (bit 7 clear → the DAC loop picks it up, and
aborts any sample already playing). Then the duration byte; a missing duration
reuses `SavedDuration` (`D:2914-2917`).

### 6.4 `zGetNextNote` (`D:907-1079`)

Clears bits 1 and 4; loops over bytes: `>= E0h` → coordination flag
(`D:2930-2945`, return address `loc_BF9`); otherwise `zKeyOffIfActive` first
(`D:920-922`), then:

- Bit 3 set → `zAltFreqMode` (`D:994-1041`): byte pair = literal frequency
  (high, low); non-zero adds sign-extended `Transpose`; a third byte is stored
  in `Unk11h` (shipped `D:1035-1039`); a fourth is the raw duration.
- `< 80h` → duration (`zStoreDuration`).
- `80h` → rest (`zRestTrack` = set bit 4; PSG silenced unless overridden).
- `81h-DFh` → note index `n - 81h + Transpose` (8-bit). PSG: `hl =
  zPSGFrequencies[idx]` (`D:2799-2815`, 84 words,
  `min(3FFh, round(PSG_Sample_Rate / (2f)))`, `PSG_Sample_Rate = Z80_Clock/16`,
  `KC:204-206`). FM: octave loop (`D:940-957`) — `block = idx / 12` (no clamp;
  indices ≥ 96 or wrapped negatives spill into bit 6 of `h`), `hl =
  zFMFrequencies[idx mod 12]` (`D:2825-2831`, 12 words,
  `round(f * 2^21 / FM_Sample_Rate)`, `FM_Sample_Rate = M68000_Clock/144`),
  `h |= block << 3`. PSG indices beyond 83 read into `zFMFrequencies` and
  `z80_MusicBanks` (deterministic table overrun).
- Duration byte after a note: if `< 80h` consume it; else reuse
  `SavedDuration` (shipped writes it into `DurationTimeout` itself,
  `D:976-980`).
- `zFinishTrackUpdate` (`D:1056-1079`): save pointer, `DurationTimeout =
  SavedDuration`; unless bit 1 (no attack): zero `ModEnvIndex`,
  `ModEnvSens`, `VolEnv`, reload `NoteFillTimeout` from `NoteFillMaster`.

`zUpdateFreq` (`D:1434-1456`): `hl = Freq + sign-extended Detune`.

## 7. Modulation and envelopes

### 7.1 Normal modulation (`F0`, `ModulationCtrl = 80h`)

`cfModulation` (`D:3405-3412`) stores the pointer to its four bytes
(wait, speed, delta, steps). `zPrepareModulation` (`D:1237-1261`; at every
attacked note): copy wait/speed/delta into `ModulationWait/Speed/Delta`,
`ModulationSteps = steps/2`, accumulator = 0. `zDoModulation` (`D:1279-1328`):
`ModulationWait--` (held at 1 afterwards); `ModulationSpeed--`; at zero reload
speed from the data, accumulator += sign-extended delta; `hl += accumulator`;
`ModulationSteps--`; at zero reload from data byte 3 (full count) and negate
`ModulationDelta`. `cfDisableModulation` (`FA`) clears bit 7; `cfSetModulation`
(`F4 xx`) and `cfAlterModulation` (`F1 psg fm`) overwrite the byte.

### 7.2 Modulation envelopes (`ModulationCtrl = 1..8`)

`zDoModEnvelope` (`D:1330-1431`): table `z80_ModEnvPointers[ctrl-1]`
(`D:4470-4487`, envelopes 00-07). Per update, byte at `ModEnvIndex`:
positive → `hl += value * (ModEnvSens+1)`, index++; `80h` → index = 0;
`82h` → index = byte at Z80 address `ModEnvIndex+1` (`D:1376-1384`: `bc` still
holds the index, so this reads **driver code bytes** at `0001h-0020h`);
`84h` → `ModEnvSens += byte at index+1` (same hazard), index += 2; `81h`,
`83h` → set bit 6 (sustain) and abandon the frequency update (`D:1370-1372`);
`85h-FFh` → applied as a negative (`h = FFh`). Shipped envelopes using `82h`:
`ModEnv_03/04/05/06/07`; used by CNZ1/2 (`F4 02` → ModEnv_01), LBZ1/2
(`F4 01` → ModEnv_00), S3 Miniboss (`F4 04/06/07/08` → ModEnv_03/05/06/07).
With the S&K image, `GetPointerTable` occupies `0008h-0014h`, so the `82h`
reads resolve to those opcode bytes (e.g. ModEnv_03 reads `000Dh`); the exact
resulting indices are a property of the installed code image (open question).

### 7.3 Volume envelopes (`z80_VolEnvPointers`, `D:4494-4577`, 39 entries)

`zDoVolEnv` (`D:4153-4213`): byte at `VolEnv`: `< 80h` → return it, index++;
`80h` → index = 0 and re-read; `81h` → set bit 4 (rest) and return to the
caller of the *track update* without touching volume (`D:4204-4208`);
`83h` → rest and `zRestTrack` (silence); any other negative value → index =
byte at Z80 address `index+1` (same code-read hazard; only `VolEnv_0A`
contains one and no stream uses it). PSG tracks select an envelope with
`VoiceIndex` (`F5 xx`, header byte). FM tracks use `cfFMVolEnv` (`FF 06 env
mask`, `D:4033-4040`) and `zDoFMVolEnv` (`D:1190-1235`): for each operator
whose mask bit is set (bit 0 → 40h, 1 → 48h, 2 → 44h, 3 → 4Ch) write
`(instrument TL byte + envelope) & 7Fh` — the track `Volume` is **not**
added on this path. No shipped stream uses `FF 06`.

### 7.4 Volume model

- FM `Volume` (header byte, `E4`, `E5 xx yy`, `E6`): `cfSetVolume`
  (`D:3113-3138`) stores `(param ^ 7Fh) & 7Fh`; `cfChangeVolume`
  (`D:3156-3176`, PSG returns) adds with saturation (0 = loudest, 7Fh =
  quietest); `zSendTL` (`D:3178-3216`): for each of the four TL bytes, bit 7
  set → `(TL + Volume) & 7Fh`, else raw TL. Fade-in adds 40h, fade-out
  increments.
- PSG `Volume`: header byte as attenuation 0-Fh; `cfSetVolume` PSG branch
  stores `((param >> 3) ^ 0Fh) & 0Fh`; `cfChangePSGVolume` (`EC`,
  `D:3273-3288`): clear bit 4, `VolEnv--` (so the envelope re-applies the
  same step), `Volume += param`, saturate at 0Fh.
- Pan/AMS/FMS (`E0`, `D:3010-3037`): `AMSFMSPan = (old & 3Fh) | param`,
  written to B4h immediately (dropped when overridden; re-sent on unpause).

## 8. Voice loading, SSG-EG, FM3 special mode

### 8.1 Instrument format and upload

25 bytes per voice (`zGetFMInstrumentOffset`, `D:1470-1481`): `B0h`
(feedback/algorithm), then 4×5 operator bytes in register order
`30h,38h,34h,3Ch` (DT/MUL), `50h..` (RS/AR), `60h..` (AM/D1R), `70h..` (D2R),
`80h..` (D1L/RR), then 4 TL bytes (`40h,48h,44h,4Ch`) — tables at
`D:1484-1525`. `zSendFMInstrument` (`D:1531-1583`): B4h from `AMSFMSPan`, B0h
(saved to `FeedbackAlgo`), the 20 operator bytes (shipped ignores the SSG-EG
attack-rate caveat, `D:1559-1566`), save `TLPtr`, `zSendTL`. All writes go
through `zWriteFMIorII`, so a voice change on an overridden music track is
silently dropped and only reapplied by `cfStopTrack` (4.6).

`cfSetVoice` (`EF`, `D:3345-3396`): FM → `zSetMaxRelRate` (D1L/RR = FFh all
operators, `D:2676-2700`), `VoiceIndex = param`; negative → second byte =
`VoiceSongID` and `zSetVoiceUploadAlter` (`D:3361-3374`: voice table of
`z80_MusicPointers[id-81h]` **without changing the bank**); positive →
`zGetFMInstrumentPointer` (`D:1461-1468`: `zVoiceTblPtr`, or the track's
`Voices` when `zUpdatingSFX`). PSG → `VoiceIndex = param`, skipping the second
byte when negative. No shipped stream uses the two-byte form.

### 8.2 SSG-EG (`FF 05`, `D:3972-4031`)

`HaveSSGEGFlag` = 80h, pointer saved; `zSendSSGEGData` writes the four bytes to
`90h,98h,94h,9Ch` and rewinds `de` by one. Cleared to 0 by `zFMClearSSGEGOps`
on SFX start and stop-all. Not reached by any shipped stream.

### 8.3 FM3 special mode (`FE`, `D:3771-3826`)

Only on FM3 (`VoiceControl == 02h`): set bit 0, then four `ldi` pairs from
`zFM3FreqShiftTable` (`D:3838-3839`) to `de`, where `de` was swapped from `hl`
= the jump target resolved by `PointerTableOffset` (`D:2934-2941`) — i.e. the
handler's own address; the copy overwrites driver code. Then 27h = 4Fh via
`zWriteFM3Settings` (`D:3812-3826`). Not reached by any shipped stream; the
`zGetSpecialFM3DataPointer` shipped body is a bare `ret` (`D:884-894`).

## 9. DAC and PCM timing

### 9.1 `zPlayDigitalAudio` (`D:4258-4357`)

Idle: 2Bh = 0, loop with `ei` until `PlaySegaPCMFlag` or `zDACIndex != 0`.
Start: 2Bh = 80h, `iy = DecTable`, set bit 7 of `zDACIndex`, `hl =
word at 8000h + 2*(id-1)` — the DAC bank's offset table (`startDACBank`,
`KM:327-…`, 68 entries `81h-C4h`) → 5-byte setup record
(`DAC_Setup`, `KM:310-325`): rate byte, 16-bit length, 16-bit in-bank pointer.
The rate byte is patched into two `ld b,N` instructions (self-modifying).
Per byte: high nibble then low nibble; each nibble = `c += DecTable[nibble]`
written to 2Ah; `DecTable` (`Sound/DAC/deltas.bin`, 16 bytes) is
`00 01 02 04 08 10 20 40 80 FF FE FC F8 F0 E0 C0`, accumulator starts at 80h.
Cycle budget from the listing (`D:4299-4350`): 303 T-states per two nibbles at
`N = 1`, plus `13*(N-1)` per nibble (`djnz $`), plus ~3.3 T-states per ROM
read per the source note (`D:4301-4302`). `dpcmLoopCounter(rate) =
1 + (Z80_Clock/rate - 151 + 6)/13` (`KM:270-272`, integer). After each
low-nibble write the loop re-reads `zDACIndex`; bit 7 clear (a new request or
a stop-all wipe) restarts from the idle path; length exhaustion clears
`zDACIndex` and returns to idle (2Bh = 0). Interrupts are enabled only during
the `djnz` waits; `zVInt` returns with `b = 1` so the wait ends immediately
after the interrupt — the frame's update time is added to that nibble's
period. The per-frame `DAC_Banks` switch in `zVInt` is what makes the sample
pointer valid; the bank is also changed to the song/SFX bank inside every
update and restored at `zVInt`'s end.

Rate scaling per id is declared in `KM:… DAC_Setup` lines (e.g. `83h` = data
of `82h` at 0.80, `8Bh` at 0.82, `93h` at 0.56); the actual rate bytes live in
the ROM's setup records.

### 9.2 SEGA PCM

See 4.8. `Sega.wav` is 14434 Hz mono 8-bit, so `pcmLoopCounter` = 12 and the
loop period is 105 + 13×11 = 248 T-states (≈14434 Hz at NTSC `Z80_Clock` =
`53693175/15`, `KC:202-204`).

## 10. Coordination flags (`zCoordFlagSwitchTable` `D:2948-2980`, extra table `D:2982-2990`)

Parameter counts are what the handler consumes (`de` is post-incremented once
by the dispatcher, `D:2943-2945`, so a no-parameter flag executes `dec de`).

| Byte | Handler | Params | Effect |
|---|---|---|---|
| E0 | `cfPanningAMSFMS` `D:3010` | 1 | 7.4 |
| E1 | `cfDetune` `D:3061` | 1 | `Detune` |
| E2 | `cfFadeInToPrevious` `D:3077` | 1 | `zFadeToPrevFlag` (4.9) |
| E3 | `cfSilenceStopTrack` `D:3088` | 1 (unused) | `zFMSilenceChannel` (also on PSG tracks: FM1 key-on hazard) then `F2` |
| E4 | `cfSetVolume` `D:3113` | 1 | absolute volume |
| E5 | `cfChangeVolume2` `D:3140` | 2 (first ignored) | FM relative volume |
| E6 | `cfChangeVolume` `D:3156` | 1 | FM relative volume; PSG ignored |
| E7 | `cfPreventAttack` `D:3218` | 0 | bit 1 |
| E8 | `cfNoteFill` `D:3230` | 1 | × divider → `NoteFillTimeout/Master` |
| E9 | `cfSpindashRev` `D:3039` | 0 | `Transpose += zSpindashRev`; if the result ≠ 10h, `zSpindashRev++` (used by `Sound_AB`; reset by every normal SFX, `FF 07`, boot, and stop-all) |
| EA | `cfPlayDACSample` `D:2997` | 1 | `zDACIndex` (unused by shipped streams) |
| EB | `cfConditionalJump` `D:3247` | 3 | if `LoopCounters[i] == 1` clear it and jump |
| EC | `cfChangePSGVolume` `D:3273` | 1 | 7.4 |
| ED | `cfSetKey` `D:3295` | 1 | `Transpose = param - 40h` |
| EE | `cfSendFMI` `D:3308` | 2 | raw part-I register write |
| EF | `cfSetVoice` `D:3345` | 1 or 2 | 8.1 |
| F0 | `cfModulation` `D:3405` | 4 | 7.1 |
| F1 | `cfAlterModulation` `D:3421` | 2 | PSG uses byte 1, FM byte 2 |
| F2 | `cfStopTrack` `D:3443` | 1 (unused) | 4.6 |
| F3 | `cfSetPSGNoise` `D:3541` | 1 | shipped: return if `VoiceControl` bit 2 (FM4-6); write DFh; store; set bit 0; non-zero → send byte; zero → clear bit 0, send FFh. No SFX-override check; FM1-3 tracks are not excluded. |
| F4 | `cfSetModulation` `D:3433` | 1 | `ModulationCtrl` |
| F5 | `cfSetPSGVolEnv` `D:3583` | 1 | PSG `VoiceIndex` |
| F6 | `cfJumpTo` `D:3598` | 2 | absolute Z80 pointer |
| F7 | `cfRepeatAtPos` `D:3613` | 4 | loop counter init/decrement/jump |
| F8 | `cfJumpToGosub` `D:3641` | 2 | push return on track stack |
| F9 | `cfJumpReturn` `D:3667` | 0 | pop |
| FA | `cfDisableModulation` `D:3686` | 0 | clear bit 7 of `ModulationCtrl` |
| FB | `cfChangeTransposition` `D:3697` | 1 | `Transpose += param` |
| FC | `cfLoopContinuousSFX` `D:3712` | 2 | 4.7 |
| FD | `cfToggleAltFreqMode` `D:3746` | 1 | shipped: `== 1` sets bit 3, anything else clears |
| FE | `cfFM3SpecialMode` `D:3771` | 4 | 8.3; non-FM3 tracks skip 3 bytes |
| FF 00 | `cfSetTempo` `D:3861` | 1 | `zCurrentTempo` (Countdown uses 40h/20h/10h/08h) |
| FF 01 | `cfPlaySoundByIndex` `D:3874` | 1 | nested request |
| FF 02 | `cfHaltSound` `D:3887` | 1 | 3.7 |
| FF 03 | `cfCopyData` `D:3932` | 3 | `ldir` into the stream |
| FF 04 | `cfSetTempoDivider` `D:3953` | 1 | all nine music `TempoDivider`s |
| FF 05 | `cfSetSSGEG` `D:3972` | 4 | 8.2 |
| FF 06 | `cfFMVolEnv` `D:4033` | 2 | 7.3 |
| FF 07 | `cfResetSpindashRev` `D:4046` | 0 | `zSpindashRev` = 0 |

Shipped-stream usage counts (from `grep` over `Sound/Music` and `Sound/SFX`):
`F0` 55/98 files, `F3` 33/36, `F5` 38/23, `E5` 31/41, `E7` 37/35, `EC` 20/23,
`E8` 16/0, `F4` 5/0, `FA` 3/3, `FF 00` 1/0, `E2` 7/0, `E9` 0/1, `FF 07` 0/1,
`FC` 0/30, `E4` 1/0, `E6` 1/0, `EF xx yy` 0/0, `E1` 0/0, `FB` 0/0, `EB` 0/0,
`EE` 0/0, `FD` 0/0, `FE` 0/0, `FF 01-06` 0/0. The existing note
`2026-08-08-s3k-smps-meta-command-reachability.md` covers `FF 01/02/03` in
more depth.

## 11. Data image (`1300h`, `D:4438-5310`)

`z80_SoundDriverPointers` (`D:4450-4462`, seven words in the shipped layout —
`zID_UniVoiceBank = 2`, `zID_MusicPointers = 4`, `zID_SFXPointers = 6`,
`zID_ModEnvPointers = 8`, `zID_VolEnvPointers = 0Ah`, `D:239-245`; reached
through `ptrMasterIndex` at `0015h`, `D:408-412`), then the modulation
envelopes, volume envelopes, `z80_MusicPointers` (51 S&K / 50 S3 entries),
`z80_SFXPointers` (173 entries, last four alias `Sound_DB`), and the universal
voice bank (`23h` voices). The data must end before `1C00h` (`D:5303-5305`).

## 12. Every `fix_sndbugs` site and the shipped path

| `D:` | Shipped (`fix_sndbugs = 0`) behaviour |
|---|---|
| 110-119 | `zDataStart = 1C00h`; no `zSpecFM3Freqs` buffers exist |
| 233-245 | seven-entry master pointer table with the unused priority/limit slots |
| 259, 274, 301 | bank macros end with `xor a; ld (hl),a` (bit 23 = 0) |
| 374 | S&K filler byte `F2h` after the entry jump |
| 391, 408 | `GetPointerTable` reads the table address through `ptrMasterIndex` |
| 431 | `PointerTableOffset` pads with three `nop` before falling into `ReadPointer` |
| 477 | `zVInt` stores `r` into `unk_1C17` |
| 488 | S3 PAL reload value 6 (S&K 5) |
| 502 | S3 zeroes `unk_1C21` |
| 535 | S3 boot `zSongBank` junk; S&K `Snd_Bank2_Start` |
| 584, 609 | `nop` between YM address and data writes |
| 683 | redundant reload of `zFadeToPrevFlag` |
| 769, 2870, 4059 | note timer via `zTrackRunTimer` |
| 827, 1128, 1170, 1593, 3310, 3818 | `call` + `ret` instead of `jp` (timing only) |
| 838, 885 | FM3 special mode never sets `de` |
| 953 | dead `ex af,af'` after the octave loop |
| 976, 2914 | missing duration copies `SavedDuration` into `DurationTimeout` inline |
| 983 | unreachable Battletoads pitch-slide code |
| 1016, 1303, 1437 | branchy sign extension (same result) |
| 1035 | alt-freq third byte stored in `Unk11h` |
| 1101 | `zTrackRunTimer` exists |
| 1118 | `zFMNoteOn` masks `06h` (rest bit not checked) |
| 1212, 3197 | `and 7Fh` after TL arithmetic; no overflow clamp in `zSendTL` |
| 1349, 4158 | envelope byte read via `(hl)`; `82h/84h` follow-ups read via `bc` = index (code-region read) |
| 1539 | instrument upload ignores the SSG-EG attack-rate rule |
| 1601 | no `zSendFMInstrDataRSAR` |
| 1659 | `unk_1C18` zeroed before a fade effect |
| 1689 | `zStopSFX` `call`/`ret` |
| 1763 | 1-up save loop: `set 2,(hl)` overwritten, bit 2 *not* set |
| 1800 | `zBGMLoad` self-modifying bank read |
| 1906 | seventh `zFMDACInitBytes` entry |
| 1948 | redundant `ld c,a` on the continuous-SFX path |
| 1981 | `unk_1C15` zeroed in `zPlaySound` |
| 2006, 2043, 2071 | dead special-SFX code; compare against `(iy+1)` |
| 2095 | `zFMClearSSGEGOps` also called for PSG SFX tracks (harmless) |
| 2113 | FM4-6 ids decremented to skip the FM3/FM4 gap |
| 2121 | PSG SFX init silences via stale `ix`, then unconditional `FFh` |
| 2159 | `rst PointerTableOffset; ret` |
| 2204, 2217 | no filler entry in the two SFX channel tables (indices 0-7) |
| 2251 | unpause pan loop covers six slots (includes `zSongPSG1`) |
| 2277 | unpause "SFX" loop starts at `zTracksSFXEnd` for seven slots |
| 2347, 2404, 2420, 2433 | fade counters decremented via `a` |
| 2464 | `zStopAllSound` wipe length `+34h` |
| 2481 | `zFMSilenceChannel` (with key-off through `zKeyOnOff`) per channel |
| 2500 | `ld b,7`; `zFadeOutTimeout` re-zeroed |
| 2512 | `zFM3NormalMode` stores `zFM3Settings` |
| 2542 | extra `zPSGSilenceAll` at the top of `zPauseAudio` |
| 2705 | `zPlaySegaSound` does not clear mailboxes/queue |
| 2748 | DAC slot restore via `ld a,(…); or 84h; ld (…),a` |
| 3089 | `cfSilenceStopTrack` silences FM regardless of track type |
| 3190 | no clamp in `zSendTL` |
| 3394 | dead `ret` after `zSetVoicePSG` |
| 3445, 3460 | `cfStopTrack` writes `unk_1C15` = 1Fh and `unk_1C18` = 0 |
| 3542 | `cfSetPSGNoise` tests `VoiceControl` bit 2 and skips the override check |
| 3718, 3725 | continuous-SFX flag re-cleared; loop counter via `a` |
| 3747 | `FD` enables only on parameter `== 1` |
| 3813 | `zWriteFM3Settings` stores `zFM3Settings` |
| 3985, 4002, 4016 | SSG-EG upload does not force AR = 1Fh |
| 4121 | PSG noise volume branch layout |
| 4190 | `zDoVolEnvFullRest` sets bit 4 before `zRestTrack` |
| 4236 | `zSilencePSGChannel` tests bit 0 instead of comparing with DFh |
| 4424 | S&K junk byte after `zPlaySEGAPCM` |
| 4451 | seven-word data pointer table |

## 13. The two driver images: S3 vs S&K

`SonicDriverVer` sites (`grep` list: `D:326, 358, 374, 488, 502, 535, 704,
1642, 1707, 1808, 2357, 2398, 2734, 2856, 2862, 3455, 3493, 4374, 4415,
4424, 4566, 4600, 4606`):

- **Bank switching.** S&K: `bankswitch2` writes 8 bits (address bits 15-22)
  then 0 (`D:267-280`; `zmake68kBank = (addr & 3F8000h) / 8000h`, `D:356`) —
  any bank in 4 MiB. S3: `bankswitchToMusicS3` (`D:309-323`) writes only 4 bits
  from `a`, then a fixed 1, then four 0s: song banks are confined to
  `080000h-0FFFFFh` and `zmakeSongBank` masks to `0Fh` (`D:358-362`).
  `bankswitchToSFX` in S3 is a compile-time bit pattern for `SndBank`
  (`D:325-341`). This is why the S3-half image cannot serve the locked-on
  cartridge and why the S&K image can reach S3-half data.
- **Credits ID.** S&K maps `DCh` to music index `32h` (`D:1642-1645`,
  `1707-1714`); S3 treats `DCh-DFh` as SFX (`Sound_DB` aliases).
- **Music tables.** Entry `2Dh` is `Snd_Minib` (S3) vs `Snd_Minib_SK` (S&K)
  and S&K appends `Snd_SKCredits` (`D:2856-2867`, `4600-4611`).
- **PAL reload** 6 vs 5 (`D:488`); S3 clears `unk_1C21` (`D:502`).
- **Boot bank** junk vs `Snd_Bank2_Start` (`D:535`).
- **`cfStopTrack`** uses `jr` vs `jp` for the clean exit (`D:3455`).
- **SEGA PCM** queue handling (`D:4374`, `4415`; 4.8).
- **`VolEnv_25`** has two extra `9` steps in S3 (`D:4566-4570`).
- **Installation** raw copy with `+$BC` (S3) vs Kosinski (S&K), section 1.1.
- **Bank layout.** S&K half: `Snd_Bank1` (credits, game over, continue,
  results, invincibility, menu, final boss, game complete), `Snd_Bank2`
  (FBZ1/2, MHZ, SOZ, LRZ, SSZ, DEZ, minibosses, boss, DDZ, pachinko, special
  stage, slots, Knuckles, title, 1-up, emerald), `DacBank1` (`81h-9Bh`,
  `B2h/B3h` S&K), `SndBank` (SEGA PCM + all SFX) — `K:201150-201245`.
  S3 half (locked-on data): AIZ-LBZ, gumball, competition zones, S3 credits,
  2P menu, countdown, `DacBank2` (`9Ch-AAh`), `DacBank3` (`ABh-C0h`) —
  `LP:315-336` ff.; S3 standalone layout at `S3:117946-118120`.

## Open questions

1. **Reads above `1FFFh` during unpause.** `zPauseUnpause`'s second loop reads
   `PlaybackControl`/`VoiceControl`/`AMSFMSPan` from `1F40h-208Fh`
   (`D:2277-2298`). What the Z80 sees at `2000h-208Fh` on hardware (RAM
   mirror or open bus) is not stated in the disassembly; if it mirrors
   `0000h+` the first byte is `F3h` (bit 7 set) and the next `F3h` (bit 7 set
   → treated as PSG → skipped). The stack bytes at `1FA0h-1FFFh` are whatever
   earlier calls left. Until settled, the engine can only reproduce the
   observable rule "no SFX slot pan is restored".
2. **`de` in FM3 special-mode frequency writes** (`D:843-870`) and the code
   overwritten by `cfFM3SpecialMode` (`D:3789-3806`). Unreached by shipped
   streams, so the exact target (handler address vs table entry — the source
   comment and the `PointerTableOffset` dereference disagree) is not needed
   for parity but is unresolved.
3. **Modulation-envelope `82h` index source.** The new index is a driver
   opcode byte at `ModEnvIndex+1` (`D:1376-1384`). Reproducing CNZ, LBZ and
   the S3 miniboss exactly requires the installed S&K code image bytes at
   `0001h-0020h`; they are derivable from the ROM's Kosinski-compressed
   driver blob, but whether the engine currently loads that blob is an
   engine question, not a ROM one.
4. **DAC track `F2` skipping the rest of the music update.** Derived from the
   stack shape (`D:2921-2927`, `D:3514-3518`); affects only the single frame a
   DAC track terminates (jingles). Not confirmed by a trace.
5. **1-up save corruption by live SFX.** The save area overlaps the SFX slots
   (`D:190-212`). Any SFX running during the jingle advances inside the saved
   copy of FM3-6/PSG tracks, and the SFX start writes the SFX header into
   them. The restore then resumes those tracks from wherever the SFX left
   them. The disassembly does not comment on it; the observable consequence
   on real hardware is unverified.
6. **`cmd_StopSEGA` residue (S&K).** `FEh` left in `zMusicNumber` reaches
   `zPlaySoundByIndex` on the next update and becomes `zStopAllSound`
   (`D:1656-1657`). Harmless by inspection, unconfirmed by trace.
7. **`F3` on FM1-3 music tracks.** The shipped guard tests `VoiceControl`
   bit 2, so an FM1-3 track executing `F3` would set its bit 0 (FM3 special
   mode for FM3). No shipped stream appears to do this (36 SFX and 33 songs
   use `F3`, all on PSG tracks by macro placement) but the placement was not
   audited byte by byte.
8. **Exact YM/PSG write timing** within an update is out of scope here; see
   `2026-08-22-s3k-ym-write-timing-calculation.md`.
