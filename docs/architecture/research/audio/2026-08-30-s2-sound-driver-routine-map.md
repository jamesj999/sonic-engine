# Sonic 2 Z80 sound driver routine map

Inventory of every routine in the shipped Sonic 2 (REV01) Z80 SMPS driver, grouped by
subsystem, with the per-track RAM layout, the 68k request interface, the coordination
flags, the data tables, and every `FixDriverBugs` conditional and what the shipped
(`fixBugs = 0`) path does.

## Sources and rules

- Primary: `docs/s2disasm/s2.sounddriver.asm` (4104 lines; assembled as Z80 and
  Saxman-compressed into the ROM at `Snd_Driver`, `s2.asm:91436-91441`). Anchors below
  of the form `sd:NNN` mean `docs/s2disasm/s2.sounddriver.asm:NNN`.
- 68k interface: `docs/s2disasm/s2.asm` (`s2.asm:NNN`), ID constants
  `docs/s2disasm/s2.constants.asm` (`const:NNN`), header macros
  `docs/s2disasm/sound/_smps2asm_inc.asm` (`inc:NNN`).
- `FixDriverBugs = fixBugs` (`sd:8`) and `fixBugs = 0` (`s2.asm:27`): every
  `if FixDriverBugs` block is **not** assembled; every `if ~~FixDriverBugs` block **is**.
  `OptimiseDriver = 0` (`sd:9`) so every `if OptimiseDriver` block is also absent and the
  `if ~~OptimiseDriver` bodies are the shipped code. Sound data uses
  `FixMusicAndSFXDataBugs = fixBugs` (`s2.asm:68`), also off.
- Sources-closed: no emulator source or third-party SMPS documentation was consulted.
  Where a value could only be derived (not read) from the disassembly it is marked
  *derived* and listed under open questions.

## 1. Memory map (Z80 address space)

| Range | Contents | Anchor |
|---|---|---|
| `0000-0002` | entry: `di; ld sp,zStack; jp zStartDAC` | `sd:316-319` |
| `0007` | `zPalModeByte` — written by the 68k at driver load (`sne`, non-zero on PAL) | `sd:324`, `s2.asm:91314-91315` |
| `0008/0010/0018/0028` | `rst` targets: `zFMBusyWait`, `zWriteFMIorII`, `zWriteFMI`, `zWriteFMII` | `sd:331-389` |
| `0038` | `zVInt` — IM1 interrupt entry, one call per hardware VBlank | `sd:393` |
| … | driver code and tables through `zDecEnd` | `sd:393-4082` |
| `12FE-1307` | global bytes (`zPALUpdTick` … `zPaused`), see §5.3 | `sd:4087-4096` |
| `1380-1B7F` | `zMusicData`: Saxman decompression buffer for compressed songs (`$800` bytes) | `sd:177` |
| `1B80` | `zStack` top (stack grows down into the music buffer) and `zAbsVar` (`zVar`, `$18` bytes) | `sd:178-181` |
| `1B98-1D3B` | 10 music tracks: DAC, FM1-6, PSG1-3, `$2A` bytes each | `sd:183-200` |
| `1D3C-1E37` | 6 SFX tracks: FM3, FM4, FM5, PSG1, PSG2, PSG3 | `sd:202-213` |
| `1E38-1FF3` | 1-up save area: copy of `zVar` plus the 10 music tracks (`$1BC` bytes) | `sd:215-227` |
| `4000-4003` | YM2612 A0/D0/A1/D1 | `sd:169-172` |
| `6000` | bank register (`bankswitch` macro writes 9 bits, `sd:257-278`) | `sd:173` |
| `7F11` | PSG | `sd:174` |
| `8000-FFFF` | 68k ROM window (`zmake68kPtr`, `sd:309`) | `sd:175` |

Track-count equates (`sd:235-242`): `MUSIC_TRACK_COUNT = 10`, `MUSIC_DAC_FM_TRACK_COUNT = 7`,
`MUSIC_FM_TRACK_COUNT = 6`, `MUSIC_PSG_TRACK_COUNT = 3`, `SFX_TRACK_COUNT = 6`,
`SFX_FM_TRACK_COUNT = 3`, `SFX_PSG_TRACK_COUNT = 3`.

## 2. Per-track RAM structure (`zTrack`, `$2A` bytes, `sd:84-140`)

| Off | Field | Meaning |
|---|---|---|
| `00` | `PlaybackControl` | bit 7 (`80`) playing; bit 4 (`10`) do-not-attack next note (E7); bit 3 (`08`) modulation on; bit 2 (`04`) SFX is overriding this music track (on the DAC track it is reused as "muted during fade-in"); bit 1 (`02`) track at rest |
| `01` | `VoiceControl` | FM: channel bits 0-1, bit 2 = YM part II (FM4-6); values `0,1,2,4,5,6` (`zFMDACInitBytes`, `sd:2107`; DAC track = 6). PSG: `80/A0/C0` latch bytes (`zPSGInitBytes`, `sd:2112`), `E0` = PSG3 as noise (set by F3) |
| `02` | `TempoDivider` | duration multiplier from header byte +4 / E5 / EB |
| `03-04` | `DataPointer` (LE) | current position in track data |
| `05` | `Transpose` | signed semitone offset, header byte / E9 / spindash rev |
| `06` | `Volume` | FM: added to slot TLs at voice set / E6 (higher = quieter). PSG: attenuation 0-F (+envelope) |
| `07` | `AMSFMSPan` | value written to `B4+ch`; init `C0`; E0 replaces bits 7-6 (and 2), keeps `37` |
| `08` | `VoiceIndex` | FM voice number (EF); PSG envelope number (F5, header byte 6; 0 = none) |
| `09` | `VolFlutter` | PSG envelope position; reset to 0 at each attacked note |
| `0A` | `StackPointer` | gosub stack offset, starts `2A` (end of track), F8 subtracts 2, E3 adds 2 |
| `0B` | `DurationTimeout` | counts down each update; `TempoWait` increments it to stall |
| `0C` | `SavedDuration` | last explicit duration, reused when a note follows a note |
| `0D` | `SavedDAC` / `FreqLow` | DAC: pending drum id (`80` = rest). FM/PSG: frequency low |
| `0E` | `FreqHigh` | FM block/fnum high; PSG: `FF` marks a rest |
| `0F` | `NoteFillTimeout` | counts down to cut the note (E8) |
| `10` | `NoteFillMaster` | reload value for `0F` at each attacked note |
| `11-12` | `ModulationPtr` (LE) | address of the F0 parameter block (`ww xx yy zz`) |
| `13` | `ModulationWait` | frames before modulation starts |
| `14` | `ModulationSpeed` | frames per step (counter) |
| `15` | `ModulationDelta` | signed step, negated when steps run out |
| `16` | `ModulationSteps` | remaining steps (`zz >> 1`) |
| `17-18` | `ModulationVal` (LE, 16-bit) | accumulated frequency offset |
| `19` | `Detune` | signed byte added to frequency (E1) |
| `1A` | `VolTLMask` | slot mask from `zVolTLMaskTbl[alg]` at last voice set |
| `1B` | `PSGNoise` | last F3 value, rewritten to the PSG when an SFX releases PSG3 |
| `1C-1D` | `VoicePtr` (LE) | SFX-only custom voice table |
| `1E-1F` | `TLPtr` (LE) | address of the 4 TL bytes of the current voice |
| `20-29` | `LoopCounters[10]` | F7 loop counters by index (no bounds check, `sd:130-134`) |
| `2A` | `GoSubStack` | grows downward from here (into the loop counters) |

## 3. Driver variables (`zVar` at `zAbsVar` = `1B80`, `sd:142-166`)

| Off | Field | Set by | Read by / effect |
|---|---|---|---|
| `00` | `SFXPriorityVal` | `zCycleQueue` (`sd:1533-1536`); zeroed by `zKillSFXPrio` (`sd:2334`), `zStopSoundEffects` (`sd:2345`), any SFX track hitting F2 (`sd:3532`), 1-up start (`sd:1721`) | `zCycleQueue` compares `zSFXPriority[new]` against it (`sd:1527-1528`); equal or higher passes |
| `01` | `TempoTimeout` | preloaded with tempo at song load (`sd:1822`) | `TempoWait` accumulator (`sd:597-600`) |
| `02` | `CurrentTempo` | song load (`sd:1821`), EA (`sd:3208`), speed-up/slow-down (`sd:2708`) | `TempoWait` |
| `03` | `StopMusic` | **68k** writes `7F` (pause) / `80` (unpause) (`s2.asm:1294-1298`); cleared on unpause (`sd:1437`) and `MusID_Stop` (`sd:2546-2547`) | `zVInt` skips all updating while non-zero (`sd:403-408`) |
| `04` | `FadeOutCounter` | `zFadeOutMusic` = `28` (`sd:2431`) | `zUpdateEverything` calls `zUpdateFadeout` while non-zero (`sd:412-414`) |
| `05` | `FadeOutDelay` | 3 at start and after each step | frames between fade steps |
| `06` | `Communication` | E2 (`sd:3060`) | never read (68k touches `zAbsVar` only at `s2.asm:1272` and `s2.asm:91315`) |
| `07` | `DACUpdating` | `FF` while the DAC track updates (`sd:556-562`) | F2 uses it to know it is on the DAC track (`sd:3519-3521`) |
| `08` | `QueueToPlay` | **68k** writes a music id (`s2.asm:1306`); driver rewrites `80` when consumed (`sd:1563`), on clear (`sd:2571`), on song init (`sd:2624`) | `zCycleQueue` only runs when `80` (`sd:1497-1499`); `zPlaySoundByIndex` when not `80` (`sd:428-430`); Sega PCM aborts when it changes (`sd:1636-1638`) |
| `09-0B` | `Queue0-2` | **68k** writes SFX/command ids when the slot is 0 (`s2.asm:1320-1327`) | `zCycleQueue` scans and clears (`sd:1509-1513`) |
| `0C-0D` | `VoiceTblPtr` | song header word 0 (`sd:1809-1811`) | `zSetVoiceMusic` (`sd:3300`). The 68k's 4th SFX slot aliases `0C` (`s2.asm:1309-1316`, fixBugs off) but `SoundQueue.SFX2` is never written (`const:1883`) |
| `0E` | `FadeInFlag` | `80` by E4 (`sd:3150-3151`); 0 when fade-in ends (`sd:2740-2741`) | blocks SFX (`sd:2118-2120`); triggers `zUpdateFadeIn` (`sd:416-418`) |
| `0F` | `FadeInDelay` | 2 after each step | frames between fade-in steps |
| `10` | `FadeInCounter` | `28` by E4; preserved across song init (`sd:2589`, `2609`) | steps remaining |
| `11` | `1upPlaying` | `80` at 1-up start (`sd:1710-1711`); 0 by E4 and by any other song | blocks SFX; routes speed-up/slow-down to the save copy (`sd:2688-2703`) |
| `12` | `TempoMod` | header byte +5 (`sd:1812-1813`) | restored by `zSlowDownMusic` |
| `13` | `TempoTurbo` | `zSpedUpTempoTable[song]` (`sd:1743-1747`) | `zSpeedUpMusic`, and song load when `SpeedUpFlag` set |
| `14` | `SpeedUpFlag` | `80`/0 by speed-up/slow-down; cleared by fade-out (`sd:2434`); preserved across song init | song load picks turbo tempo (`sd:1815-1820`) |
| `15` | `DACEnabled` | `80` when the song leaves FM6 free, 0 when it uses 7 FM+DAC (`sd:1893-1935`); `80` after clear (`sd:2557-2559`) | rewritten to YM `2B` after Sega PCM (`sd:1660-1665`) and by E4 (`sd:3159-3162`) |
| `16` | `MusicBankNumber` | playlist entry bit 7 (`sd:1754-1755`) | `zBankSwitchToMusic` chooses `MusicPoint1`/`MusicPoint2` (`sd:2837-2847`) |
| `17` | `IsPalFlag` | `FF` unless playlist entry bit 6 set (`sd:1758-1762`) | ANDed with `zPalModeByte` for the 1-in-6 double update (`sd:441-450`) |

### 3.1 Global bytes outside `zVar` (`sd:4087-4096`)

| Addr | Name | Meaning |
|---|---|---|
| `12FE` | `zPALUpdTick` | counts 5→0; on wrap, `zUpdateMusic` runs twice that frame (PAL only). Reset to 5 at song load (`sd:1823-1824`) |
| `12FF` | `zCurDAC` | `81+` = drum queued for start; after start holds the zero-based index (`sd:504-508`). Never cleared when a sample ends |
| `1300` | `zCurSong` | last music id given to `zPlayMusic` |
| `1301` | `zDoSFXFlag` | 0 while updating music, `80` during SFX update, `FF` during SFX unpause; selects the voice table in `cfSetVoiceCont` and the branch in F2 |
| `1302` | `zRingSpeaker` | ring L/R alternation (0 → play `SndID_RingLeft`) |
| `1303` | `zGloopFlag` | gloop alternation |
| `1304` | `zSpindashPlayingCounter` | `3C` at each spindash rev, decremented every VInt (`sd:432-437`) |
| `1305` | `zSpindashExtraFrequencyIndex` | 0..`0B` semitone boost |
| `1306` | `zSpindashActiveFlag` | `FF` when the last SFX loaded was the spindash rev |
| `1307` | `zPaused` | `FF` while paused; not touched by `zClearTrackPlaybackMem` |

None of these are cleared by `zClearTrackPlaybackMem` (which clears only `1B80-1E37`,
`sd:2564-2568`) or by `zStartDAC`.

## 4. 68k → driver interface

| Routine | Anchor | Behaviour |
|---|---|---|
| `SoundQueue` RAM | `const:1879-1887` | `Music0`, `SFX0`, `SFX1`, `SFX2` (never written), `Music1` |
| `PlayMusic` | `s2.asm:1520-1527` | writes `Music0` if empty else `Music1` |
| `PlaySound` / `PlaySound2` / `PlaySoundLocal` | `s2.asm:1535-1562` | write `SFX0` / `SFX1` / `SFX0` (only when the object is on screen). Music and command ids may be sent through any slot |
| `sndDriverInput` | `s2.asm:1270-1330` | called from every 68k VInt routine (incl. `Vint_Lag`, `s2.asm:539-541`) between `stopZ80`/`startZ80` (Z80 bus held). If `QueueToPlay == 80`: take `Music0` else `Music1`; ids `>= MusID_Pause (FE)` become `StopMusic = id - FE + 7F` (`FE→7F`, `FF→80`) and are not sent; otherwise `QueueToPlay = id`. Then for `d1 = 3..0` (fixBugs off: 4 slots, `s2.asm:1309-1316`) copy `SFX0[d1]` into `Queue0[d1]` when the driver slot is 0 |
| `SoundDriverLoad` | `s2.asm:91301-91323` | bus request, release reset, `DecompressSoundDriver` (68k Saxman, `s2.asm:91342-91416`), `zPalModeByte = PAL`, hold reset, release bus, wait, release reset |
| Pause / unpause senders | `s2.asm:1593`, `1620`, `1631`, `6825` | level pause loop, slow-motion, special-stage unpause |

The 68k never reads driver RAM. Music ids sent through the SFX slots reach
`zCycleQueue`, which promotes them straight to `QueueToPlay` (`sd:1520-1522`).

## 5. Entry, update cadence, tempo

### 5.1 `zVInt` (`sd:393-537`)

Per VBlank (interrupts stay disabled until the `ei` at `sd:499`/`535`):

1. `zBankSwitchToMusic`; `zDoSFXFlag = 0`; `ix = zAbsVar`.
2. If `StopMusic != 0` → `zPauseMusic` then `jp zUpdateDAC` (no fades, queue, or track updates while paused).
3. `zUpdateEverything` (`sd:411`): `zUpdateFadeout` if `FadeOutCounter`; `zUpdateFadeIn` if `FadeInFlag`;
   `zCycleQueue` if any of `Queue0..2` non-zero; `zPlaySoundByIndex` if `QueueToPlay != 80`;
   decrement `zSpindashPlayingCounter`; PAL double-update (`sd:441-450`); `zUpdateMusic`.
4. `bankswitch SoundIndex`; `zDoSFXFlag = 80`; update the 3 FM SFX tracks (`zFMUpdateTrack`) and 3 PSG SFX
   tracks (`zPSGUpdateTrack`) whose bit 7 is set. `ix` is inherited from `zUpdateMusic` (last music
   track), so `add ix,zTrack.len` lands on `zSFX_FM3` (`sd:460-464`, FixDriverBugs off).
5. `zUpdateDAC` (`sd:492`): `bankswitch SndDAC_Start`; if `zCurDAC` bit 7 clear, restore the DAC loop
   registers and force `b = 1` so the running `djnz` expires immediately (`sd:494-500`); otherwise
   start the queued sample (§6).

### 5.2 `zUpdateMusic` (`sd:544-590`) and `TempoWait` (`sd:596-619`)

Shipped order: `TempoWait` first, then `DACUpdating = FF`, `zDACUpdateTrack` if the DAC track plays,
`DACUpdating = 0`, FM1-6 via `zFMUpdateTrack`, PSG1-3 via `zPSGUpdateTrack`, `ret`.

`TempoWait`: `TempoTimeout += CurrentTempo`; on carry return (tracks tick normally); otherwise
`inc DurationTimeout` on all 10 music tracks, cancelling that frame's decrement. Tempo `FF`
stalls one frame in 256; `80` stalls every other frame. SFX tracks are not tempo-scaled.
Because `TempoWait` runs before the first update of a new song, a fresh song may stall its
first note by one frame; the `82` init (rest bit) exists so that stalled first frame does not
write a zero frequency (`sd:545-553`, `1842-1846`).

PAL: when `zPalModeByte & IsPalFlag`, `zPALUpdTick` counts 5→0 and `zUpdateMusic` runs twice
every sixth frame (`sd:441-453`). Songs with playlist bit 6 (`MusFlag_SlowerOnPAL`, only the
drowning countdown, `sd:3819`, `3854`) set `IsPalFlag = 0` and so run at 5/6 speed on PAL.

### 5.3 Duration and note parsing (shared shape)

- `zSetDuration` (`sd:929-941`): `SavedDuration = DurationTimeout = duration × TempoDivider`
  (repeated addition; divider 0 wraps `djnz` 255 times).
- `zFinishTrackUpdate` (`sd:947-964`): store pointer; `DurationTimeout = SavedDuration`; if bit 4
  set return; else reload `NoteFillTimeout`, `VolFlutter = 0`, and if bit 3 set re-run
  `zSetModulation` from `ModulationPtr`.
- Note bytes `80-DF`: `80` is a rest; `81+` index the frequency table after `Transpose`. A note
  followed by another note reuses `SavedDuration`; a bare duration byte (`01-7F`) sets the
  duration without changing the pitch (FM/PSG: `zFMDoNext`/`zPSGDoNext` go to `.gotduration`
  without `zFMSetFreq`, `sd:862-869`, `1159-1169`).

## 6. DAC / PCM

| Routine | Anchor | Behaviour |
|---|---|---|
| `zStartDAC` | `sd:624-628` | `im 1`, `zClearTrackPlaybackMem`, `ei`, `iy = zDACDecodeTbl`, `de = 0` |
| `zWaitLoop` | `sd:647-650` | spin while `de == 0` (no sample) |
| `zWriteToDAC` | `sd:680-727` | per byte: `djnz $` (b from c), `di`, select `2A`, high nibble → table via self-modified `iy+n` (`sd:695`), add to shadow `a'` accumulator (starts `80`, `sd:502-503`), write, `ei`; second `djnz $`, same for the low nibble, `inc hl`, `dec de`, loop. 295 cycles for two samples at `b = 1` (`sd:728`), plus `13 × (b-1)` per sample |
| `zDACDecodeTbl` | `sd:732` | 16 deltas (`sound/DAC/deltas.bin`), listed in the comment as `0,1,2,4,8,10,20,40,80,FF,FE,FC,F8,F0,E0,C0` |
| `zDACUpdateTrack` | `sd:759-816` | `dec DurationTimeout`; on zero parse: coord flags; note byte → `SavedDAC`; following byte duration or reuse. `zDACAfterDur`: store pointer; if bit 2 set return (muted); if `SavedDAC == 80` return; else `zDACMasterPlaylist[(id-81)*2]` → `zCurDAC = sample id`, rate byte → `zDACStoreDelay+1` (`sd:809-815`). A bare duration byte therefore re-triggers `SavedDAC` |
| `zUpdateDAC .dacqueued` | `sd:502-535` | `a' = 80`; `zCurDAC -= 81` (index; only 6 bits survive the `×4`, `sd:509-513`); patch `zDACStartAddress+1`, `zDACStoreLength+2`; swap the return address for `zWriteToDAC`; `hl = pointer`, `de = length`, `bc = 0100 | delay` |
| `zDACPtrTbl` / `zDACLenTbl` | `sd:3879-3893` | 7 samples, each `dw ptr, dw len` (Kick, Snare, Clap, Scratch, Timpani, Tom, Bongo) |
| `zDACMasterPlaylist` | `sd:3895-3928` | 17 drums `81-91`: `db sample-id, dpcmLoopCounter(rate × scale)`; scales for `88-91` at `sd:3919-3928` |
| `zPlaySegaSound` | `sd:1603-1665` | `2B = 80`; `bankswitch Snd_Sega`; `de = size/2`; per pair: byte→`2A` data, `djnz` with `pcmLoopCounter(Snd_Sega.sample_rate)` (`sd:1604`), abort when `QueueToPlay != 80`; 152 cycles per pair at `b = 1`; then bank back and `2B = DACEnabled`. Runs inside VInt with interrupts off, so music/SFX freeze for its duration. No panning reset (FixDriverBugs off, `sd:1606-1611`) |

Loop-counter formulas (`sd:311-314`): `pcmLoopCounterBase(rate, base) = 1 + (Z80_Clock/rate - base + 6)/13`
with `Z80_Clock = 53693175/15` (`const:2142`), `base = 152/2` (Sega) or `295/2` (DPCM), integer division.

*Derived* counters from the `.wav` headers in the disassembly (the `generated/*.inc` files that
carry `.sample_rate` are not present; see open questions): Kick 8250 Hz → `b=23`; Snare 24000 → 1;
Clap 17000 → 6; Scratch 15000 → 8; Timpani 7375 → 27; Tom 13500 → 10; Bongo 7375 → 27;
`88`-`8B` Timpani ×1.30/1.20/0.97/0.95 → 18/21/28/29; `8C`-`8E` Tom ×1.70/1.30/1.10 → 2/5/8;
`8F`-`91` Bongo ×2.00/1.75/1.30 → 8/11/18; SEGA 16500 Hz → `b=12`.

Timing perturbations visible in the source: every VInt forces one early sample (`ld b,1`,
`sd:497`); interrupt entry/exit and the 68k `stopZ80` around `sndDriverInput` and DMA stall the
loop; a new drum replaces `hl/de` mid-sample (cutting the old one).

## 7. Request queue, dispatch, priority

### 7.1 `zCycleQueue` (`sd:1496-1550`)

Runs only when `QueueToPlay == 80`. `c = SFXPriorityVal`; for the three slots in order: take and
zero the byte; `< 81` → next slot; `>= F8` (command) or `< A0` (music) → `QueueToPlay = id` and return;
SFX: `zSFXPriority[id - A0]`; if lower than `c` → skip; else `c = priority`, `QueueToPlay = id`. After the
first SFX examined (accepted or not) the routine returns: if `c` has bit 7 set (only Jump, `80`)
it is not stored, else `SFXPriorityVal = c`. At most one request leaves the queue per frame;
a rejected SFX is discarded (its slot was already zeroed) and later slots wait for the next frame.

### 7.2 `zPlaySoundByIndex` (`sd:1555-1600`)

`00` → `zClearTrackPlaybackMem`. `< 80` → ignored. Else `QueueToPlay = 80`, then:
`< A0` (`MusID__End`) → `zPlayMusic`; `A0-F0` → `zPlaySound_CheckRing`; `F1-F7` ignored; `FE/FF`
ignored (`cp MusID_Pause; ret nc`); `F8-FD` → self-modified `jr` into `zCommandIndex`:

| Id | Const (`const:961-966`) | Routine |
|---|---|---|
| `F8` | `MusID_StopSFX` | `zStopSoundEffects` (`sd:2344`) |
| `F9` | `MusID_FadeOut` | `zFadeOutMusic` (`sd:2423`) |
| `FA` | `SndID_SegaSound` | `zPlaySegaSound` (`sd:1603`) |
| `FB` | `MusID_SpeedUp` | `zSpeedUpMusic` (`sd:2686`) |
| `FC` | `MusID_SlowDown` | `zSlowDownMusic` (`sd:2697`) |
| `FD` | `MusID_Stop` | `zStopSoundAndMusic` (`sd:2545`) |

Id ranges (`const:828-970`): music `81-9F` (`zMasterPlaylist`, 31 songs, `sd:3823-3855`), SFX
`A0-F0` (`SoundIndex`, `s2.asm:91668`), commands `F8-FD`, pause/unpause `FE/FF` handled on the
68k. Sound-test index = id − `80`. Id `80` ("reserved for silence") is not filtered by the
driver: it reaches `zPlayMusic` with index `-1` (see open questions).

### 7.3 `zSFXPriority` (`sd:3716-3722`)

81 bytes indexed by `id - A0`. Notable: Jump (`A0`) = `80` (always wins, never stored);
`AA` Splash `68`; `AE` `60`; `B1` `60`; `B3` `60`; `BF` ContinueJingle `7F`; `C0` `6F`;
`CD` Blip `6F`; `D1/D2` `60`; `DA` Gloop `60`; `DB` `62`; `DC-DE` `60`; `E4-E6` `60`;
`E7` `6F`; `EA/EB` `6F`; `ED` Error `71`; `F0` `6F`; all others `70`.

## 8. Music loading

### 8.1 `zPlayMusic` (`sd:1667-1736`)

1. FixDriverBugs off: `zStopSoundEffects` first (`sd:1668-1673`) — every song start kills all
   SFX and restores their music channels.
2. `zCurSong = id`. If `MusID_ExtraLife` (`98`): if `1upPlaying` already → `zBGMLoad` (restart
   without re-saving); else clear bit 2 on the 10 music tracks, clear bit 7 on the 6 SFX
   tracks, copy `1B80-1FF3` (`zVar` + 10 music tracks, `$1BC` bytes) to `zTracksSaveStart`,
   `1upPlaying = 80`, then `SFXPriorityVal = 0` **after** the backup (`sd:1714-1722`) so the old
   priority comes back with E4.
3. Otherwise `1upPlaying = FadeInCounter = FadeOutCounter = 0`.

### 8.2 `zBGMLoad` (`sd:1738-2006`)

- `zInitMusicPlayback` (§8.4).
- `idx = zCurSong - 81`; `TempoTurbo = zSpedUpTempoTable[idx]` (`sd:3859-3866`).
- Playlist byte (`music_metadata`, `sd:3821-3822`): bit 7 → `MusicBankNumber` (`80` = in the
  `MusicPoint2` bank); bit 6 → `IsPalFlag = 0`; bit 5 → uncompressed; bits 0-4 → index into the
  bank's leading pointer table (`music_ptr`, `s2.asm:91486-91489`), read at `zROMWindow + 2·idx`
  after `zBankSwitchToMusic`.
- Compressed songs: `zSaxmanDec` (§8.5) into `zMusicData`; header then at `1380`. Uncompressed
  songs are read in place through the ROM window.
- Header (`inc:306-357`): `+0/1` voice table pointer → `VoiceTblPtr`; `+2` FM+DAC count; `+3` PSG
  count; `+4` tempo divider; `+5` tempo → `TempoMod`; `CurrentTempo = TempoTimeout =`
  `SpeedUpFlag ? TempoTurbo : TempoMod`; `zPALUpdTick = 5`.
- FM/DAC entries (4 bytes each from `+6`: pointer, transpose, volume) into `zSongDAC`, `zSongFM1`…:
  `PlaybackControl = 82`, `VoiceControl = zFMDACInitBytes[n]`, divider, `StackPointer = 2A`,
  `AMSFMSPan = C0`, `DurationTimeout = 1`.
- If fewer than 7 FM+DAC tracks: key off FM6 (`28 = 06`), TL `42/46/4A/4E = FF` (part II,
  FixDriverBugs off, `sd:1906-1916`), `B6 = C0`, `DACEnabled = 80`; else `DACEnabled = 0`.
  Write `2B`.
- PSG entries (6 bytes: pointer, transpose, volume, [skipped], envelope): `82`, `zPSGInitBytes[n]`,
  divider, stack, `DurationTimeout = 1`, `VoiceIndex = byte 6` (`sd:1955-2003`).
- `zInitSFX` (`sd:2008-2075`): for each playing SFX track `res 2` on its music track (dead in
  practice: SFX were already stopped); then `zFMNoteOff` on the 6 FM music tracks and
  `zPSGNoteOff` on the 3 PSG tracks using their freshly written `VoiceControl` (undefined tracks
  carry `VoiceControl = 0` after the clear, so an undefined PSG track writes PSG byte `1F`).

### 8.3 `zSpedUpTempoTable` (`sd:3859-3866`)

31 bytes, one per song `81-9F`: `68 BE FF F0 FF DE FF DD 68 80 D6 7B 7B FF A8 FF 87 FF FF C9 97 FF FF CD CD AA F2 DB D5 F0 80`.

### 8.4 `zInitMusicPlayback` (`sd:2580-2658`)

Saves `SFXPriorityVal`, `1upPlaying`, `SpeedUpFlag`, `FadeInCounter`, `Queue0`, `Queue1` (not
`Queue2`, FixDriverBugs off `sd:2598-2601`, `2613-2617`); zero-fills `1B80-1D3B` (`zVar` + 10
music tracks; SFX tracks untouched); restores; `QueueToPlay = 80`; then (FixDriverBugs off,
`sd:2631-2657`) `zFMSilenceAll` + `zPSGSilenceAll` — this writes `FF` to YM registers `30-8F` on
all six channels, including FM3-5 that SFX may own, which is why `zPlayMusic` stops SFX first.

### 8.5 `zSaxmanDec` (`sd:3931-4082`)

Reads a 16-bit size then the stream; descriptor bits from `c` (reloaded every 8, `sd:3970-3978`);
bit set → literal byte; bit clear → two bytes `lo`, `hi`: count `= (hi & F) + 3`, offset
`= ((hi >> 4) << 8 | lo) + 12` masked to 12 bits; if the offset lies before the data written so far
the run is zero-filled, else copied from `zMusicData + offset` (`sd:3993-4046`). The byte source
and remaining-count are self-modified operands (`zGetNextByte+1`, `zDecEndOrGetByte+1`,
`sd:4055-4077`); reaching zero pops the caller (`zDecEnd`, `sd:4079`).

## 9. SFX loading and channel ownership

### 9.1 Pre-checks (`sd:2116-2176`)

- `1upPlaying` or `FadeInFlag` → `zKillSFXPrio` (priority reset, SFX dropped).
- `zSpindashActiveFlag = 0`.
- `SndID_Ring` (`B5`): if `zRingSpeaker == 0` play `SndID_RingLeft` (`CE`) instead; toggle
  `zRingSpeaker` (`cpl`). The 68k always requests `B5`.
- `SndID_Gloop` (`DA`): toggle `zGloopFlag`; play only when it becomes `FF` (every other request).
- `SndID_SpindashRev` (`E0`): if `zSpindashPlayingCounter != 0` then
  `index = zSpindashExtraFrequencyIndex + 1` else `0`; stored only while `< 0C`; counter `= 3C`;
  `zSpindashActiveFlag = FF`.

### 9.2 `zPlaySound` (`sd:2178-2332`)

`bankswitch SoundIndex`; pointer from `SoundIndex[(id - A0)·2]`; SFX header (`inc:360-386`):
voice pointer (2), tempo divider (1), channel count (1); per channel 6 bytes
`80, chanid, ptr(2), transpose, volume`. Per channel:

- PSG (`chanid >= 80`): if `C0` write `DF` and `FF` to the PSG (silence PSG3 and noise); index `= chanid >> 4 & F`.
- FM: index `= (chanid - 2) · 2` (FM3→0, FM4→4, FM5→6; no SFX may use FM1/2/6 or DAC, `sd:737-745`).
- `set 2` on the music track (`zMusicTrackOffs`, `sd:747-750`); zero the SFX track
  (`zSFXTrackOffs`, `sd:754-757`); copy playback/voice control; `TempoDivider` from header;
  `DurationTimeout = 1`; `StackPointer = 2A`; copy pointer + transpose; if
  `zSpindashActiveFlag` add `zSpindashExtraFrequencyIndex` to the transpose (`sd:2297-2308`);
  copy volume; FM only: `AMSFMSPan = C0`, `VoicePtr` = header voice pointer.
- No key-off is sent for the taken FM channel; the SFX's own EF flag loads its voice.
- `zBankSwitchToMusic` on exit.

### 9.3 SFX end and music restore

| Routine | Anchor | Restore action |
|---|---|---|
| `cfStopTrack` on an SFX track | `sd:3514-3599` | `res 7`, `res 4`; note off; `SFXPriorityVal = 0`; FM: if the music track's bit 2 is set → `res 2`, `set 1`, `zBankSwitchToMusic`, `zSetVoiceMusic(VoiceIndex)` (voice, `B4` pan, TLs with volume), bank back to `SoundIndex`. PSG: `res 2`, `set 1` unconditionally; if the music track is `E0` rewrite `PSGNoise`. Pops two return addresses so the SFX loop continues |
| `zStopSoundEffects` (`F8`) | `sd:2344-2420` | same restore for every playing SFX track, plus priority reset. Runs with the music bank selected |
| `zInitSFX` | `sd:2014-2044` | clears bit 2 (`res 2`, FixDriverBugs off) instead of setting it — unreachable in the shipped flow |

While bit 2 is set on a music track it keeps parsing and advancing, but `zFMNoteOn`,
`zFMNoteOff`, `zFMUpdateFreq`, `zPSGUpdateFreq`, `zPSGUpdateVol`, `zPSGNoteOff`, `zSetChanVol`,
`cfSetVoice`, `cfSetPSGNoise` and `cfPanningAMSFMS` all return early; the DAC track skips
sample triggering.

## 10. Track update: FM

| Routine | Anchor | Behaviour |
|---|---|---|
| `zFMUpdateTrack` | `sd:821-837` | `dec DurationTimeout`. Expired: `res 4`, `zFMDoNext`, `zFMPrepareNote`, `zFMNoteOn`, `zDoModulation`, `zFMUpdateFreq`. Running: `zNoteFillUpdate`, `zDoModulation`, `zFMUpdateFreq` |
| `zFMDoNext` | `sd:842-870` | `res 1`; run coord flags until a note/duration; `zFMNoteOff` (key off unless bits 4/2); note → `zFMSetFreq`; then duration handling → `zFinishTrackUpdate` |
| `zFMSetFreq` | `sd:874-916` | `a -= 80`; 0 → `zFMDoRest` (`set 1`, freq 0); else `(a + Transpose)·2` into the low byte of a `ld de,(zFrequencies)` operand (`sd:902-905`) — indices outside 0..95 read neighbouring bytes of the same 256-byte page |
| `zFMPrepareNote` | `sd:1079-1087` | rest → return; freq 0 → `zSetRest`; else falls into `zFMUpdateFreq` |
| `zFMUpdateFreq` | `sd:1089-1118` | bit 2 → return; `hl = sign-extend(Detune) + de`; write `A4+ch = h`, `A0+ch = l` via `zWriteFMIorII` |
| `zFMNoteOn` | `sd:2797-2807` | bits 1/2 → return; `28 = VoiceControl | F0` (part I) |
| `zFMNoteOff` | `sd:2814-2829` | bits 4/2 → return; `28 = VoiceControl` |
| `zNoteFillUpdate` | `sd:968-979` | if `NoteFillTimeout` non-zero, decrement; on reaching 0: `set 1`, discard the caller's return address and jump to the note-off routine (the rest of that frame's track update is skipped) |
| `zDoModulation` | `sd:986-1047` | pops its return address; bit 1 or bit 3 clear → return to the *caller's caller* (frequency is **not** rewritten this frame); `ModulationWait` counts down first; `ModulationSpeed` counts down per step; when it expires reload it from `ptr+1`; if `ModulationSteps == 0` reload from `ptr+3` and negate `ModulationDelta` (no write); else `dec` steps, `ModulationVal += sign-extend(Delta)`, `de = Freq + ModulationVal`, jump to the frequency writer |

Consequence: the YM frequency registers are written at note-on (through `zFMPrepareNote`) and
on each modulation step; never otherwise.

## 11. Track update: PSG

| Routine | Anchor | Behaviour |
|---|---|---|
| `zPSGUpdateTrack` | `sd:1123-1139` | expired: `res 4`, `zPSGDoNext`, `zPSGDoNoteOn`, `zPSGDoVolFX`, `zDoModulation`, `zPSGUpdateFreq`. Running: `zNoteFillUpdate`, `zPSGUpdateVolFX`, `zDoModulation`, `zPSGUpdateFreq` |
| `zPSGDoNext` | `sd:1145-1169` | like `zFMDoNext` without a note-off |
| `zPSGSetFreq` | `sd:1175-1196` | `a -= 81`; carry (`80`) → `set 1`, `Freq = FFFF`, `zPSGNoteOff`; else `(a + Transpose)·2` into the low byte of `ld de,(zPSGFrequencies)` (`sd:1180-1184`) |
| `zPSGDoNoteOn` | `sd:1202-1207` | `FreqHigh` bit 7 → `zSetRest`; else `de = Freq` and fall into `zPSGUpdateFreq` |
| `zPSGUpdateFreq` | `sd:1209-1251` | bits 1/2 → return; `hl = sign-extend(Detune) + de`; `E0` tracks write as `C0`; first byte `reg | (l & F)`, second `(hl >> 4) & 3F` |
| `zPSGUpdateVolFX` | `sd:1265-1269` | `VoiceIndex == 0` → return (no per-frame volume write without an envelope) |
| `zPSGDoVolFX` | `sd:1276-1303` | `b = Volume`; envelope `zPSG_EnvTbl[VoiceIndex-1][VolFlutter]`, `inc VolFlutter`; byte `< 80` → `b += byte`; byte `== 80` → `zVolEnvHold` (`dec VolFlutter; ret` — no write this frame and none until the next attacked note, FixDriverBugs off `sd:1339-1350`); byte `81-FF` → added like a value |
| `zPSGUpdateVol` / `zPSGCheckNoteFill` / `zPSGSendVol` | `sd:1307-1337` | bits 1/2 → return; if bit 4 set: write only when `NoteFillMaster == 0` or `NoteFillTimeout != 0`; clamp `b >= 10` to `F`; write `VoiceControl | 10 | b` |
| `zPSGNoteOff` | `sd:1357-1381` | bit 2 → return; write `VoiceControl | 1F`; noise is **not** silenced when PSG3 stops (FixDriverBugs off) |
| `zSetRest` | `sd:1255-1257` | `set 1` |

## 12. Frequency, envelope and mask tables

| Table | Anchor | Contents |
|---|---|---|
| `zFrequencies` | `sd:1384-1409` | 8 octaves × 12 words, `dw round(f · 2^21 / FM_Sample_Rate) + octave · 800` with base `f` = B 15.39 … Bb 29.15 Hz (`sd:1387`); `FM_Sample_Rate = M68000_Clock / 144` (`const:2143`). Note `81` = index 0 = B. Kept in one 256-byte page (`ensure1byteoffset 0C0h`) |
| `zPSGFrequencies` | `sd:1053-1076` | 70 words, `min(3FF, round(PSG_Sample_Rate / (2f)))`, `PSG_Sample_Rate = Z80_Clock / 16` (`const:2144`), six octaves from C 130.98 Hz; last entry (223721.56 Hz) yields the maximum-rate "hi-hat" value |
| `zVolTLMaskTbl` | `sd:3236-3238` | by algorithm: `8,8,8,8,C,E,E,F`; bit n selects the TL register at `40+ch+4n` (hardware slot order) as a volume slot |
| `zPSG_EnvTbl` | `sd:3725-3808` | 13 envelope pointers; data `zPSG_Env1..13` (attenuation deltas, `80` terminator; note `Env6` is stored before `Env5`) |
| `zFMDACInitBytes` | `sd:2107` | `6,0,1,2,4,5,6` |
| `zPSGInitBytes` | `sd:2112` | `80,A0,C0` |
| `zMusicTrackOffs` / `zSFXTrackOffs` | `sd:747-757` | `FM3, 0, FM4, FM5, PSG1, PSG2, PSG3, PSG3` (index 2 unused; index `E` maps noise to PSG3) |

## 13. Voice loading and FM volume

| Routine | Anchor | Behaviour |
|---|---|---|
| `cfSetVoice` (EF) | `sd:3271-3279` | `VoiceIndex = a`; bit 2 → return (stored for later restore); else `cfSetVoiceCont` |
| `cfSetVoiceCont` | `sd:3285-3293` | `zDoSFXFlag != 0` → `hl = VoicePtr` (SFX table) and `zSetVoice`; else `zSetVoiceMusic` |
| `zSetVoiceMusic` | `sd:3300-3303` | `hl = VoiceTblPtr` |
| `zSetVoice` | `sd:3305-3397` | `hl += 25·a`; byte 0 → `B0+ch` (FB/ALG, kept via self-modified `.a_backup`); 4 bytes → `30+ch` step 4 (DT/MUL); 16 bytes → `50,60,70,80 (+ch, step 4)` in that order (RS/AR, AM/D1R, D2R, D1L/RR); `B4+ch = AMSFMSPan`; `TLPtr = hl`; `VolTLMask = zVolTLMaskTbl[alg]`; then `zSetFMTLs` |
| `zSetFMTLs` | `sd:3399-3432` | 4 TL bytes → `40+ch` step 4; for slots whose mask bit is set, `TL + Volume` with no clamp (FixDriverBugs off `sd:3412-3421`), so the sum can exceed `7F` |
| `zSetChanVol` | `sd:3439-3460` | PSG → return; bit 2 → return; `Volume` bit 7 → return; re-run `zSetFMTLs` from `TLPtr` |

Registers `28` and `2A/2B/27` are always part I (`zWriteFMI`); per-channel registers go
through `zWriteFMIorII` (`sd:343-347`), which selects part II when `VoiceControl` bit 2 is set.

## 14. Fades

| Routine | Anchor | Behaviour |
|---|---|---|
| `zFadeOutMusic` (F9) | `sd:2423-2436` | `FadeOutDelay = 3`, `FadeOutCounter = 28`, DAC track `PlaybackControl = 0`, `SpeedUpFlag = 0` |
| `zUpdateFadeout` | `sd:2442-2512` | delay counts 3→0 (one step per 4 frames); each step: `dec FadeOutCounter`, at 0 → `zClearTrackPlaybackMem`; FM tracks: `inc Volume`, `>= 80` → `res 7`, else `zSetChanVol`; PSG tracks: `inc Volume`, `> 10` → `res 7`, else `zPSGUpdateVol` with the raw volume (envelope ignored, FixDriverBugs off `sd:2492-2503`). 40 steps ≈ 160 frames. New SFX are not blocked during a fade-out |
| `cfFadeInToPrevious` (E4) | `sd:3084-3164` | restore the save block over `1B80-1FF3`; `zBankSwitchToMusic`; DAC track `set 2` (muted); `c = 28 - FadeInCounter`; FM playing tracks: `set 1`, `Volume += c`, `zSetVoiceMusic(VoiceIndex)`; PSG playing tracks: `set 1`, `zPSGNoteOff`, `Volume += c` (noise not restored, FixDriverBugs off `sd:3136-3143`); `FadeInFlag = 80`, `FadeInCounter = 28`, `1upPlaying = 0`, `2B = DACEnabled`; pops three return addresses and `jp zUpdateDAC` — the remainder of this frame's music and SFX updates are abandoned |
| `zUpdateFadeIn` | `sd:2725-2791` | delay 2→0 (one step per 3 frames); when `FadeInCounter == 0`: DAC `res 2`, `FadeInFlag = 0`; else `dec` counter, FM: `dec Volume`, `zSetChanVol`; PSG: `dec Volume`, `zPSGUpdateVol` (raw volume) |

## 15. Pause, unpause, stop, silence

| Routine | Anchor | Behaviour |
|---|---|---|
| `zPauseMusic` | `sd:1422-1464` | entered with flags from `StopMusic`: bit 7 (`80`) → unpause. Pause: if `zPaused` already set return (so every frame while paused only `zUpdateDAC` runs; a DAC sample in progress keeps playing to its end); else `zPaused = FF`, `zFMSilenceAll`, `zPSGSilenceAll`. Unpause: `StopMusic = 0`, `zPaused = 0`, `zResumeTrack` over DAC+FM1-6, then `bankswitch SoundIndex`, `zDoSFXFlag = FF`, `zResumeTrack` over the 3 FM SFX tracks, `zDoSFXFlag = 0`, `zBankSwitchToMusic` |
| `zResumeTrack` | `sd:1468-1492` | for playing, non-overridden tracks: `B4+ch = AMSFMSPan` then `cfSetVoiceCont(VoiceIndex)`. The DAC track (VoiceControl 6) is included and so reloads voice 0 into FM6 registers. Key states, PSG volumes and frequencies are not restored |
| `zFMSilenceAll` | `sd:2518-2540` | `28 = 0,1,2` and `4,5,6` (key off all), then `FF` to registers `30-8F` on both parts |
| `zPSGSilenceAll` | `sd:1412-1418` | `9F, BF, DF, FF` |
| `zStopSoundAndMusic` (FD) | `sd:2545-2547` | `StopMusic = 0` then `zClearTrackPlaybackMem` (`zPaused` is left as-is) |
| `zClearTrackPlaybackMem` | `sd:2553-2574` | `2B = 80`, `DACEnabled = 80`, `27 = 0`, zero `1B80-1E37` (`zVar` + all 16 tracks), `QueueToPlay = 80`, `zFMSilenceAll`, `zPSGSilenceAll`. Also the boot path (`zStartDAC`) and the fade-out terminal |

## 16. Coordination flags (`zCoordFlag`, `sd:2853-3000`)

`zCoordFlag`: `(a - E0)·4` patched into a `jr` (`sd:2861`) over a 4-byte `jp` table; `a` = the
following byte, `hl` past it. Flags without a parameter `dec hl`.

| Flag | Routine | Anchor | Effect |
|---|---|---|---|
| `E0 xx` | `cfPanningAMSFMS` | `sd:3006-3048` | PSG → return; **bit 2 set → return before storing** (shipped); `AMSFMSPan = (old & 37) \| xx`; write `B4+ch` |
| `E1 xx` | `cfDetune` | `sd:3051` | `Detune = xx` |
| `E2 xx` | `cfSetCommunication` | `sd:3059` | `Communication = xx` (unread) |
| `E3` | `cfJumpReturn` | `sd:3066-3080` | pop `hl` from the gosub stack, `StackPointer += 2` (the byte after `E3` was consumed as `a` but is irrelevant) |
| `E4` | `cfFadeInToPrevious` | `sd:3084` | §14 |
| `E5 xx` | `cfSetTempoDivider` | `sd:3168` | this track's `TempoDivider = xx` |
| `E6 xx` | `cfChangeFMVolume` | `sd:3175-3179` | `Volume += xx`, `zSetChanVol` |
| `E7` | `cfPreventAttack` | `sd:3183-3187` | `set 4`, `dec hl` |
| `E8 xx` | `cfNoteFill` | `sd:3191-3195` | `NoteFillTimeout = NoteFillMaster = xx` |
| `E9 xx` | `cfChangeTransposition` | `sd:3199-3203` | `Transpose += xx` |
| `EA xx` | `cfSetTempo` | `sd:3207-3209` | `CurrentTempo = xx` (`TempoMod`/`TempoTurbo` untouched) |
| `EB xx` | `cfSetTempoMod` | `sd:3214-3230` | `TempoDivider = xx` on all 10 music tracks (even when issued from an SFX track) |
| `EC xx` | `cfChangePSGVolume` | `sd:3243-3247` | `Volume += xx` (no hardware write) |
| `ED xx` | `cfClearPush` | `sd:3254-3261` | shipped: `ret` without `dec hl`, so the byte after `ED` is skipped |
| `EE` | `cfStopSpecialFM4` | `sd:3264-3267` | `dec hl`; no-op |
| `EF xx` | `cfSetVoice` | `sd:3271` | §13 |
| `F0 ww xx yy zz` | `cfModulation` / `zSetModulation` | `sd:3467-3501` | `set 3`; `ModulationPtr = hl-1`; copy `ww,xx,yy`; `Steps = zz >> 1`; unless bit 4: `ModulationVal = 0` |
| `F1` | `cfEnableModulation` | `sd:3506-3509` | `dec hl`, `set 3` |
| `F2` | `cfStopTrack` | `sd:3514-3599` | `res 7`, `res 4`; PSG → `zPSGNoteOff`; DAC (`DACUpdating`) → pop once and return to `zUpdateMusic`; FM music → `zFMNoteOff`, pop twice; SFX → §9.3 |
| `F3 xx` | `cfSetPSGNoise` | `sd:3604-3611` | `VoiceControl = E0`, `PSGNoise = xx`; bit 2 → return; write `xx` to PSG |
| `F4` | `cfDisableModulation` | `sd:3615-3618` | `dec hl`, `res 3` |
| `F5 xx` | `cfSetPSGTone` | `sd:3623-3625` | `VoiceIndex = xx` |
| `F6 lo hi` | `cfJumpTo` | `sd:3630-3633` | `hl = hi:lo` (absolute Z80 address) |
| `F7 ii nn lo hi` | `cfRepeatAtPos` | `sd:3644-3677` | `LoopCounters[ii]`: 0 → set `nn`; `dec`; non-zero → jump, zero → skip the address |
| `F8 lo hi` | `cfJumpToGosub` | `sd:3681-3700` | `StackPointer -= 2`; push the address after the operand; `hl = hi:lo` |
| `F9` | `cfOpF9` | `sd:3704-3712` | `88 = 0F`, `8C = 0F` on part I (D1L/RR of slots `+8`/`+C` of FM channel 1, regardless of the current track); `dec hl` |

## 17. Special cases

- **1-up** (`98`): §8.1 save, §14 restore via E4 at the end of the jingle. While `1upPlaying`:
  SFX requests are dropped with priority reset; `zSpeedUpMusic`/`zSlowDownMusic` write the
  *saved* copy (`zSaveVar`, `sd:2688-2718`) so the tempo change applies after restore.
- **Speed shoes**: 68k sends `MusID_SpeedUp` on pickup (`s2.asm:25946-25947`) and
  `MusID_SlowDown` on expiry (`s2.asm:36325`, `39054`). Driver: `CurrentTempo = TempoTurbo` /
  `TempoMod`, `SpeedUpFlag = 80` / `0` (`sd:2686-2718`); `TempoTimeout` is not reset.
  `SpeedUpFlag` survives song changes (§8.4) so a new song starts at its turbo tempo; fade-out
  clears it.
- **Special stage**: no driver-side special case. `MusID_SpecStage` (`92`) is an ordinary playlist
  entry; the special stage only sends `MusID_Unpause` on resume (`s2.asm:6825`).
- **Ring L/R**: `zRingSpeaker` alternation, §9.1; the SFX data for `B5`/`CE` carry the panning.
- **Gloop**: every second request is dropped, §9.1.
- **Spindash rev**: cumulative transpose up to +11 semitones while requests arrive within 60
  frames of each other, §9.1/§9.2.
- **Sega chant**: `SndID_SegaSound` (`FA`) is a command, §6; the 68k's Sega screen sends it
  (`s2.asm:4186`, `78500`).
- **PAL**: driver-load flag plus per-song bit 6, §5.2.
- **Stop (`FD`)** and **sound `00`** both end in `zClearTrackPlaybackMem`; `FD` also clears
  `StopMusic`.

## 18. Self-modifying code inventory

| Patched operand | Written by | Stores |
|---|---|---|
| `zDACStartAddress+1`, `zDACStoreLength+2` (`sd:524-529`) | `zUpdateDAC` (`sd:514-517`) | low byte of the `zDACPtrTbl`/`zDACLenTbl` entry of the sample being started |
| `zDACStoreDelay+1` (`sd:532`) | `zDACAfterDur` (`sd:814`) | the drum's rate byte; persists as the current sample's `c` reload |
| `zDACDataStore+2` (`sd:809`) | `zDACAfterDur` (`sd:807`) | low byte into `zDACMasterPlaylist` |
| `.highnybble+2`, `.lownybble+2` (`sd:698`, `721`) | `zWriteToDAC` | the current nibble as an `iy` displacement |
| `zFMSetFreq .storefreq+2` (`sd:904`), `zPSGSetFreq .storefreq+2` (`sd:1184`) | note parsing | low byte of the frequency-table entry |
| `zPlaySoundByIndex .commandjump+1` (`sd:1583`) | command dispatch | `jr` displacement |
| `zInitSFX .trackstore+1` (`sd:2037`) | `zInitSFX` | low byte into `zMusicTrackOffs` |
| `zPlaySound .voiceptr+1` (2 bytes), `.is_psg+1`, `.bgm_to_override+1`, `.bgm_to_sfx+2` (`sd:2321`, `2313`, `2243`, `2250`) | `zPlaySound` | SFX voice pointer, PSG marker, track-table offsets |
| `zStopSoundEffects .fmpointer+2`, `.psgpointer+2` (`sd:2370`, `2399`) | `zStopSoundEffects` | music-track table offset |
| `coordflagLookup+1` (`sd:2870`) | `zCoordFlag` | `jr` displacement |
| `zSetVoice .a_backup+1` (`sd:3386`) | `zSetVoice` | the voice's FB/ALG byte |
| `cfStopTrack .fmtrackoffs+2`, `.psgtrackoffs+2` (`sd:3546`, `3579`) | `cfStopTrack` | music-track table offset |
| `zGetNextByte+1`, `zDecEndOrGetByte+1` (`sd:4071`, `4055`) | `zSaxmanDec` | source pointer and remaining byte count (+1) |
| `zPalModeByte` (`sd:324`) | 68k | PAL flag stored in the code area |

## 19. `FixDriverBugs` sites (all OFF in the shipped ROM)

| Anchor | Shipped behaviour (`fixBugs = 0`) | What the fixed branch would do |
|---|---|---|
| `sd:460-464` | no `ld ix` before the SFX loop; relies on `zUpdateMusic` leaving `ix` at `zSongPSG3` | set `ix` explicitly |
| `sd:545-553` | `TempoWait` called at the top of `zUpdateMusic` | move it after the track updates |
| `sd:584-588` | explicit `ret` at the end of `zUpdateMusic` | fall through into `TempoWait` |
| `sd:1341-1350` | `zVolEnvHold`: `dec VolFlutter; ret` — no PSG volume write once the envelope terminator is reached | back up two and re-apply the final envelope value each frame |
| `sd:1369-1380` | `zPSGNoteOff` does not silence the noise channel when stopping PSG3 | write `FF` after `DF` |
| `sd:1606-1611` | Sega PCM plays with whatever `B6` panning is current | reset `B6 = C0` |
| `sd:1668-1673` | `zPlayMusic` calls `zStopSoundEffects` before every song | remove the call |
| `sd:1698-1702`, `1714-1722` | 1-up: `SFXPriorityVal` zeroed after the backup, so it is restored non-zero by E4 | zero before the backup |
| `sd:1836-1839`, `1848-1853`, `1858-1861`, `1881-1884` (FM) and `1950-1953`, `1962-1967`, `1971-1974`, `1998-2001` (PSG) | `VoiceControl` written only for channels the header defines | pre-fill all 10 tracks in `zInitMusicPlayback` |
| `sd:1842-1846`, `1956-1960` | `PlaybackControl = 82` (playing + rest) | `80` |
| `sd:1906-1916` | when FM6 is unused, TL `42/46/4A/4E = FF` on part II | rely on `zFMSilenceChannel` |
| `sd:2039-2043` | `zInitSFX` does `res 2` on the music track of a playing SFX | `set 2` |
| `sd:2055-2067` | `zInitSFX` sends only `zFMNoteOff` to the 6 FM music tracks | `zFMSilenceChannel` (max RR, TL `7F`) for non-overridden tracks |
| `sd:2078-2103` | `zFMSilenceChannel`, `zSetMaxRelRate`, `zFMOperatorWriteLoop` not assembled | — |
| `sd:2492-2503` | fade-out writes the raw PSG `Volume`, ignoring the envelope | only for envelope 0 |
| `sd:2598-2601`, `2613-2617` | `Queue2` not preserved across `zInitMusicPlayback` | preserve it |
| `sd:2631-2657` | `zInitMusicPlayback` ends with `zFMSilenceAll` + `zPSGSilenceAll` (all channels, all registers `30-8F`) | pre-fill `VoiceControl` instead |
| `sd:2771-2782` | fade-in writes the raw PSG `Volume`, ignoring the envelope | only for envelope 0 |
| `sd:3022-3028`, `3035-3039` | E0 returns before storing `AMSFMSPan` when overridden; old panning returns after the SFX | store first, then gate the hardware write |
| `sd:3136-3143` | E4 does not rewrite `PSGNoise` for an `E0` track | restore it |
| `sd:3255-3258` | ED returns without `dec hl` (skips one byte) | fall through to `dec hl` |
| `sd:3412-3421` | `TL + Volume` unclamped; bit 7 of the sum reaches the register | set bit 7 first and saturate |

68k side: `sndDriverInput` loops four SFX slots (`s2.asm:1309-1316`); the fourth aliases
`VoiceTblPtr` but is never non-zero.

### 19.1 Sound-data `FixMusicAndSFXDataBugs` sites (also OFF)

| File | Shipped |
|---|---|
| `sound/sfx/BC - Spin Dash Release.asm:7-12` | FM5 header transpose `90` (not `10`) |
| `sound/music/8A - DEZ.asm:106-112` | FM4 loops to `DEZ_Loop03` without resetting the voice |
| `sound/music/8D - SCZ.asm:122-125` | no `smpsSetvoice $06` before `SCZ_Jump01` |
| `sound/music/9E - Credits.asm:1090-1098`, `1113-1117` | PSG2 `smpsAlterPitch -$C` then `+$18` (frequency-table underflow) |

## 20. Open questions

1. `zDACMasterPlaylist` rate bytes and `zDACPtrTbl` lengths: the disassembly generates them at
   build time from `sound/DAC/generated/*.inc` (absent here); the values in §6 are derived from
   the `.wav` headers and the `dpcmLoopCounter` formula, not read from a decompressed driver.
2. `zFrequencies` / `zPSGFrequencies` exact words: formulas and constants are in the source but
   the assembler's `roundFloatToInteger` result was not dumped; confirm against the ROM.
3. Effective DAC/PCM sample rate on hardware: VInt forced early samples, interrupt latency and
   68k bus requests (`stopZ80` around `sndDriverInput` and DMA) are not derivable from the source.
4. `cfPanningAMSFMS` uses `bit 7,(ix+d); ret m` (`sd:3018-3021`): whether S reflects the tested
   bit after `BIT` on the Z80 is a hardware question; the source treats it as "return for PSG".
5. Music id `80` reaches `zBGMLoad` with index `-1` (reads the byte before `zMasterPlaylist` and
   `zSpedUpTempoTable`); whether any 68k caller ever sends `80` was not checked.
6. `zInitSFX` writes PSG byte `1F` for uninitialised PSG tracks (`VoiceControl = 0`); the effect
   depends on the PSG's last latched register and was not pinned.
7. `zSetDuration` with `TempoDivider = 0` multiplies by 256 (`djnz` wrap); whether any shipped
   header or E5/EB operand is 0 was not checked.
8. DAC bare-duration re-trigger (§6) and the un-overridden PSG restore in `zStopSoundEffects`
   are stated from the code; no shipped song/SFX was checked for dependence on them.
9. `zResumeTrack` writes voice 0 into FM6 through the DAC track on unpause; the audible effect
   while `2B = 80` is a YM2612 question outside the source.
10. YM2612 handling of a TL byte with bit 7 set (§13, `sd:3412-3421`) is a hardware question.
11. `MusID_Stop` while paused leaves `zPaused = FF`, so the next pause request silences nothing;
    whether the 68k can produce that sequence was not traced.
