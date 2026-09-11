# Sonic 2 sound driver behaviour spec

**Date:** 2026-08-30
**Branch:** `feature/ai-sdre-spec-s2` (from `feature/ai-sound-driver-re`, with
`feature/ai-sdre-gaps`, `feature/ai-sdre-refute-structural-fit` and
`feature/ai-sdre-refute-oracle-plan` merged)
**Kind:** design (per-game driver behaviour spec — the S2 lane of the gap analysis'
§4.1 plan)
**Inputs:**

| Key | Document |
|---|---|
| S2M | `docs/architecture/research/audio/2026-08-30-s2-sound-driver-routine-map.md` |
| GA | `docs/architecture/designs/audio/2026-08-30-sound-driver-re-gap-analysis.md` |
| CD | `docs/architecture/audits/audio/2026-08-30-smps-behaviour-claims-digest.md` |
| EM | `docs/architecture/audits/audio/2026-08-30-smps-engine-architecture-map.md` |

**Source rule (sources-closed).** Every ROM statement below was derived from
`docs/s2disasm/s2.sounddriver.asm` (anchors `sd:NNN`), `docs/s2disasm/s2.asm`
(`s2:NNN`), `docs/s2disasm/s2.constants.asm` (`const:NNN`),
`docs/s2disasm/sound/_smps2asm_inc.asm` (`inc:NNN`) and the sound data under
`docs/s2disasm/sound/`, read for this spec. No emulator source, no reverted branch, no
third-party SMPS documentation. Engine code was read only to say what the engine does
today (section (e) of each subsystem), never as evidence of hardware behaviour. Where
the disassembly cannot answer, the item is in §18.

**Shipped configuration.** `FixDriverBugs = fixBugs` (`sd:8`) with `fixBugs = 0`
(`s2.asm:27`) and `OptimiseDriver = 0` (`sd:9`): every `if FixDriverBugs` block is
absent, every `if ~~FixDriverBugs` and `if ~~OptimiseDriver` block is the shipped code.
Sound data uses `FixMusicAndSFXDataBugs = fixBugs` (`s2.asm:68`), also off. All
statements below describe the shipped path; §16 lists the FixBugs sites a normal run
actually reaches.

**Claims coverage.** §17.2 maps every S2-relevant CD row to the section that settles
it, including the rows whose purported anchors are wrong.

---

## 1. Invocation boundary and cadence

### 1.1 ROM behaviour

The driver is a Z80 program in IM 1; the only interrupt is V-blank, entering `zVInt`
at `0038h` (`sd:392-393`). **One `zVInt` per hardware V-blank is the driver
invocation**, including lag frames: the 68k feeds the queue from `sndDriverInput`,
which every V-int routine calls — including `Vint_Lag` (`s2:539-541`) — between
`stopZ80`/`startZ80`, but the Z80 interrupt itself fires from the VDP regardless of
what the 68k is doing.

Order within one `zVInt` (`sd:393-535`), interrupts disabled until the `ei` at
`sd:501`/`sd:534`:

1. `zBankSwitchToMusic`; `zDoSFXFlag = 0`; `ix = zAbsVar` (`sd:399-402`).
2. If `StopMusic != 0` → `zPauseMusic`, then straight to step 6 (`sd:403-407`) —
   a paused frame runs no fades, no queue, no track updates (§12).
3. `zUpdateEverything` (`sd:411-452`): `zUpdateFadeout` if `FadeOutCounter != 0`;
   `zUpdateFadeIn` if `FadeInFlag != 0`; `zCycleQueue` if any of `Queue0..2` is
   non-zero; `zPlaySoundByIndex` if `QueueToPlay != 80h`; decrement
   `zSpindashPlayingCounter` if non-zero; PAL double-update decision (§1.2).
4. `zUpdateMusic` (once, or twice on a PAL double-update frame) — `TempoWait` first,
   then DAC, FM1-6, PSG1-3 music tracks (§3, §5).
5. SFX pass (`sd:454-487`): `bankswitch SoundIndex`, `zDoSFXFlag = 80h`, then the
   3 FM SFX tracks and 3 PSG SFX tracks whose bit 7 is set, via
   `zFMUpdateTrack`/`zPSGUpdateTrack`. **`ix` is inherited** from `zUpdateMusic`'s last
   music track (`zSongPSG3`) and pre-incremented by `zTrack.len` per iteration, which
   lands on `zSFX_FM3` only because `zUpdateMusic` ends where it does
   (FixDriverBugs-off, `sd:460-463`).
6. `zUpdateDAC` (`sd:492-535`): `bankswitch SndDAC_Start`; if `zCurDAC` bit 7 is clear,
   restore the DAC loop registers and force `b = 1` so the interrupted `djnz` expires
   immediately (one forced-early DAC sample per V-blank); otherwise start the queued
   sample (§15). `ei`, return into the DAC loop.

So the order is **music before SFX** (CAD-10 confirmed for S2), and one invocation =
fades → queue → dispatch → music (tempo-gated durations, tracks always serviced) →
SFX → DAC hand-back.

**PAL double update.** When `zPalModeByte & IsPalFlag` is non-zero (`sd:441-444`),
`zPALUpdTick` is decremented; when it reaches 0 it is reloaded with 5 and
`zUpdateMusic` is called an extra time before the normal call (`sd:445-452`).
`zPALUpdTick` is reset to 5 at song load (`sd:1823-1824`). Counting from a reload,
the tick decrements 4, 3, 2, 1, 0 on five successive V-ints and fires on the fifth —
**one extra music update every 5th V-int**, i.e. 6 music updates per 5 PAL frames =
1.2×, exactly compensating 50 vs 60 Hz. The in-source comment "Every 6 frames (0-5)"
(`sd:448`) and S2M §5.2's "runs twice every sixth frame" are both wrong about the
period; CD CAD-06's "every fifth VInt" is correct (S2M correction). SFX and DAC stay
single-service (music-only double update; CAD-06's shape confirmed). Songs with
playlist bit 6 (`MusFlag_SlowerOnPAL`, `sd:3816`, only `zMusIDPtr_Countdown`,
`sd:3854`) get `IsPalFlag = 0` (`sd:1758-1761`) and so run at 5/6 speed on PAL.
S1-style PAL compensation does not exist anywhere else; **no tempo multiplier of any
kind** is applied for PAL (CAD-08 confirmed for S2).

**The 68k side of the boundary** (`sndDriverInput`, `s2:1270-1330`): with the Z80 bus
held, the music slots are read **only when `QueueToPlay == 80h`** — a busy driver
defers both music slots a frame; when it is `80h`, `Music0` is taken if non-zero,
else `Music1`. Ids `>= MusID_Pause (FEh)` become `StopMusic = id - FEh + 7Fh`
(`FE→7F` pause, `FF→80h` unpause) and are not queued; any other id is written to
`QueueToPlay`. Then for `d1 = 3..0` — **four** slots with fixBugs off
(`s2:1306-1314`) — copy `Sound_Queue.SFX0[d1]` into `Queue0[d1]` when the driver
byte is 0. The `d1 = 2` read is `SoundQueue.SFX2`, which no 68k routine ever
writes (`const:1883`), so that slot is always empty; the `d1 = 3` read is
`SoundQueue.SFX0 + 3` = **`Music1`** (the struct is Music0, SFX0, SFX1, SFX2,
Music1), and the write lands on `Queue0 + 3` = the **first byte of
`VoiceTblPtr`** — so a pending `Music1` request (PlayMusic's overflow slot,
`s2:1526`) can be stolen into `VoiceTblPtr`'s low byte whenever that byte is 0,
and cleared. Rare, but not harmless-by-construction.

### 1.2 State read/written

Reads: `StopMusic (zVar+03)`, `FadeOutCounter (+04)`, `FadeInFlag (+0E)`,
`Queue0..2 (+09..0B)`, `QueueToPlay (+08)`, `zSpindashPlayingCounter (1304h)`,
`zPalModeByte (0007h)`, `IsPalFlag (+17)`, `zPALUpdTick (12FEh)`, `zCurDAC (12FFh)`.
Writes: `zDoSFXFlag (1301h)` 0 → 80h across the pass, `zPALUpdTick`, the bank register.

### 1.3 Observable effect

Which V-int a note lands in. Everything in §3-§15 is timed by this boundary. One
oracle tick = one `zVInt` completion; on a PAL double-update frame the second
`zUpdateMusic` is *inside* the same tick.

### 1.4 Test vectors

1. **Idle frame.** No queue bytes, no song: `zVInt` performs bank switches and the
   DAC restore only — zero YM/PSG data writes. RAM delta: none (`zDoSFXFlag` ends 80h).
2. **Lag frame with a queued SFX.** 68k `Vint_Lag` still calls `sndDriverInput`
   (`s2:539-541`), so a queued `Sound_Queue.SFX0` byte reaches `Queue0` and is
   dispatched on the next Z80 V-int — SFX are not delayed by 68k lag beyond the normal
   one-frame queue latency.
3. **PAL cadence.** PAL console, EHZ music (playlist bit 6 clear → `IsPalFlag = FFh`).
   After song load (`zPALUpdTick = 5`): V-ints 1-4 single `zUpdateMusic`
   (tick 4, 3, 2, 1), V-int 5 double (tick 0 → reload 5), V-ints 6-9 single, V-int 10
   double… Music advances 6 ticks per 5 frames; the SFX pass runs once per frame
   throughout.
4. **Pause id transform.** 68k writes `MusID_Pause (FEh)` to `Music0` →
   `sndDriverInput` computes `FEh - FEh + 7Fh = 7Fh` → `StopMusic = 7Fh`; the id never
   reaches `QueueToPlay` (`s2:1290-1298`). `MusID_Unpause (FFh)` → `StopMusic = 80h`.

### 1.5 Engine today

`SmpsSequencer.advanceBatch` runs `processTempoFrame()` whenever a sample counter
crosses `samplesPerFrame` — a sample-domain 1/60 s period phase-free of the outer
presentation frame (EM §3.2 step 6); commands are drained once per outer frame before
rendering (EM §3.2 step 3). **Adaptation point** (GA §1.2 #1, §1.4 item 1): the
boundary must become frame-locked with one service event per ROM update, including
delay frames. PAL is modelled as `tempoWeight *= 1.2`
(`SmpsSequencer.calculateTempo`) — the right average rate but not the ROM's
every-5th-frame double update (GA §1.2 #17; divergence, deferred until after NTSC
parity).

---

## 2. Driver RAM and track struct (comparison vocabulary)

### 2.1 ROM layout

Z80 address space (`sd:169-233`, S2M §1): `zAbsVar` (`zVar`, 18h bytes) at `1B80h`;
10 music tracks (`zSongDAC`, `zSongFM1-6`, `zSongPSG1-3`) at `1B98h`, 2Ah bytes each;
6 SFX tracks (`zSFX_FM3/FM4/FM5`, `zSFX_PSG1-3`) at `1D3Ch`; the 1-up save area
`zTracksSaveStart` (`zVar` + 10 music tracks, 1BCh bytes) at `1E38h`. Globals outside
`zVar` at `12FEh-1307h` (`sd:4087-4096`): `zPALUpdTick`, `zCurDAC`, `zCurSong`,
`zDoSFXFlag`, `zRingSpeaker`, `zGloopFlag`, `zSpindashPlayingCounter`,
`zSpindashExtraFrequencyIndex`, `zSpindashActiveFlag`, `zPaused`. `zPalModeByte` is a
*code* byte at `0007h` (`sd:324`) written by the 68k loader (`s2:91311-91312`).
`zMusicData` (`1380h-1B7Fh`) is the Saxman output buffer; the stack grows down from
`zStack = 1B80h` into it (`sd:177-178`).

`zTrack` fields by offset (`sd:84-140`): `00 PlaybackControl` (bit 7 playing, bit 4
do-not-attack, bit 3 modulation on, bit 2 SFX-overriding — reused on the DAC track as
"muted during fade-in", §13 — bit 1 at-rest), `01 VoiceControl` (FM: channel bits 0-1
+ bit 2 = part II; DAC track = 6; PSG: latch bytes 80/A0/C0, E0 = noise),
`02 TempoDivider`, `03-04 DataPointer` (LE Z80 address), `05 Transpose`, `06 Volume`,
`07 AMSFMSPan`, `08 VoiceIndex` (FM voice / PSG envelope id), `09 VolFlutter` (PSG
envelope cursor), `0A StackPointer` (init 2Ah), `0B DurationTimeout`,
`0C SavedDuration`, `0D SavedDAC`/`FreqLow`, `0E FreqHigh` (PSG: FFh = rest),
`0F NoteFillTimeout`, `10 NoteFillMaster`, `11-12 ModulationPtr`, `13-16` modulation
wait/speed/delta/steps, `17-18 ModulationVal` (LE 16-bit), `19 Detune`,
`1A VolTLMask`, `1B PSGNoise`, `1C-1D VoicePtr` (SFX custom voice table),
`1E-1F TLPtr`, `20-29 LoopCounters[10]`, with the gosub stack growing down from
`2A` into the loop counters (no bounds check, `sd:130-137`).

`zVar` fields (`sd:142-166`): `00 SFXPriorityVal`, `01 TempoTimeout`,
`02 CurrentTempo`, `03 StopMusic`, `04 FadeOutCounter`, `05 FadeOutDelay`,
`06 Communication` (written by `E2`, `sd:3059-3060`, never read — the 68k touches
driver RAM only at `s2:1272-1329` and `s2:91311-91312`), `07 DACUpdating`,
`08 QueueToPlay`, `09-0B Queue0-2`, `0C-0D VoiceTblPtr`, `0E FadeInFlag`,
`0F FadeInDelay`, `10 FadeInCounter`, `11 1upPlaying`, `12 TempoMod`,
`13 TempoTurbo`, `14 SpeedUpFlag`, `15 DACEnabled`, `16 MusicBankNumber`,
`17 IsPalFlag`.

### 2.2 Clearing rules (what survives what)

- `zClearTrackPlaybackMem` (`sd:2553-2572`; boot via `zStartDAC` `sd:624-626`,
  `MusID_Stop`, fade-out terminal, sound `00`) zeroes `1B80h-1E37h` (`zVar` + all 16
  tracks) then `QueueToPlay = 80h`. It does **not** touch the save area or any
  `12FEh-1307h` global (`zPaused`, ring/gloop/spindash state survive a stop-all).
- `zInitMusicPlayback` (`sd:2580-2654`; every song load) zeroes `1B80h-1D3Bh` (`zVar`
  + 10 music tracks; SFX tracks untouched) while preserving `SFXPriorityVal`,
  `1upPlaying`, `SpeedUpFlag`, `FadeInCounter`, `Queue0`, `Queue1` — **not `Queue2`**
  (FixDriverBugs off, `sd:2595-2601`, `2610-2617`): a pending third-slot request is
  lost on song load.
- `zRingSpeaker` and `zGloopFlag` are toggled (`sd:2127-2134`, `2144-2146`) and
  **never reset by any routine** — unlike the S3K claim (CD REQ-01) there is no
  music-load or stop-all reset site in S2; the toggles persist for the session.

### 2.3 Field registry classification (engine mapping)

Engine `Track`/sequencer fields per EM §2.2-2.4. Classification for a RAM-shaped
comparison:

| ROM field | Engine | Class |
|---|---|---|
| `PlaybackControl` bits 7/4/3/1/2 | `active`/`tieNext`/`modEnabled`/`resting`/`overridden` | derived (re-pack into one byte) |
| `VoiceControl` | `type`+`channelId`+`noiseMode` | derived |
| `TempoDivider`, `Transpose`, `Volume`, `Detune` | `dividingTiming`, `keyOffset`, `volumeOffset`, `detune` | compared |
| `DataPointer` | `pos` + `z80StartAddress` | derived |
| `AMSFMSPan` | `(pan&C0h)\|(ams<<4)\|fms` | derived |
| `VoiceIndex` | `voiceId`/`instrumentId` | compared |
| `VolFlutter` | `envPos` | compared (note S2 keeps the raw cursor; engine also keeps decoded `envValue`) |
| `StackPointer`+`GoSubStack`+`LoopCounters` | `returnSp`/`returnStack`/`loopCounters` | derived (re-pack the overlapping region, §18 q8) |
| `DurationTimeout`, `SavedDuration` | `duration`, `scaledDuration` | compared |
| `FreqLow/High` | `baseFnum`(+`baseBlock`) | derived — the ROM stores the **table word** (base+octave), pre-detune, pre-modulation (`sd:905-912`, `1185-1187`); detune and modulation are applied at write time (`sd:1089-1117`), so `Freq` = engine base value, EM open q 5 answered for S2 |
| `NoteFillTimeout/Master` | `fill`-derived | derived |
| `ModulationPtr` | none (values copied) | derived from the `F0` position |
| `ModulationWait/Speed/Delta/Steps/Val` | `mod*` pairs | compared |
| `VolTLMask`, `TLPtr`, `VoicePtr` | recomputed / materialised copy | derived / not-compared |
| `PSGNoise` | `psgNoiseParam` | compared |
| `SFXPriorityVal`, `Queue0-2`, `QueueToPlay`, `StopMusic`/`zPaused` | absent (GA §1.2 #4, #5, #14) | absent — adaptation points |
| `Communication` | `commData` | compared (trivially: only `E2` writes) |
| `DACUpdating`, `zDoSFXFlag`, `zCurDAC`, self-modified operands (S2M §18), `zPalModeByte` | implicit / absent | not-compared |
| `zRingSpeaker`/`zGloopFlag`/spindash trio | `AudioManager.ringLeft` / `BlueBallsObjectInstance.gloopToggle` / absent | adaptation (§14) |
| `TempoMod/TempoTurbo/SpeedUpFlag/CurrentTempo/TempoTimeout` | `normalTempo`, config map, `speedShoes`, `tempoWeight`, `tempoAccumulator` | compared/derived |
| `1upPlaying` + save area | parked live driver (EM §1.4) | adaptation (§13) |
| `DACEnabled`, `MusicBankNumber`, `IsPalFlag`, `VoiceTblPtr` | absent / loader-implicit | absent (`DACEnabled` is genuinely observable — §5) |

---

## 3. Main tempo and durations

### 3.1 ROM behaviour

`TempoWait` (`sd:596-619`) runs **at the top of `zUpdateMusic`** (FixDriverBugs off,
`sd:545-551`): `TempoTimeout += CurrentTempo`; **on carry return** (this frame's
decrements stand — a "normal" frame); on **no carry**, `inc DurationTimeout` on all
**10 music track slots unconditionally** — no playing-bit test (`sd:609-616`) — which
cancels that frame's `dec` in each track update. Track updates **always run**: on a
delay frame note-fill, PSG envelopes, modulation and frequency writes all still
happen; only the duration countdown is stalled. SFX tracks are not tempo-scaled
(their durations decrement every frame, `sd:821-835` via the SFX pass).

Consequences, all from the shipped code:

- A tempo of `FFh` stalls 1 frame in 256; `80h` stalls every other frame; the stall
  probability is `(256 - tempo)/256`.
- `CurrentTempo = TempoTimeout = tempo` at song load (`sd:1820-1822`), so the phase
  restarts on every load; `EA xx` (`cfSetTempo`, `sd:3207-3209`) and
  speed-up/slow-down (`sd:2707-2710`) replace `CurrentTempo` but do **not** reset
  `TempoTimeout` (CAD-03's S2 half confirmed; CAD-12's "definite phase": the new rate
  takes effect at the next `TempoWait` with the accumulated phase kept).
- Because `TempoWait` runs before the first track update of a new song, a fresh song
  with `tempo < 80h` stalls its first frame; the `82h` init ("playing + at rest",
  §5) exists so the stalled first frame writes nothing (`sd:545-551`, `1842-1846`).

Durations: `zSetDuration` (`sd:929-942`) computes
`SavedDuration = DurationTimeout = duration × TempoDivider` by repeated addition
(`djnz`), so divider `00h` wraps to 256 iterations — duration × 256 (S2M open q 7;
no shipped header found using 0, unverified — §18 q4). `zFinishTrackUpdate`
(`sd:947-962`): store pointer, `DurationTimeout = SavedDuration`; if bit 4
(do-not-attack) return; else reload `NoteFillTimeout` from `NoteFillMaster`,
`VolFlutter = 0`, and if bit 3 re-arm modulation from `ModulationPtr`.

### 3.2 State

Reads/writes `CurrentTempo`, `TempoTimeout`, every music track's `DurationTimeout`;
per-track `TempoDivider`, `SavedDuration`, `NoteFillTimeout/Master`, `VolFlutter`.

### 3.3 Observable effect

On a delay frame the YM/PSG stream is **not** silent: PSG envelope volume writes
(every running frame with `VoiceIndex != 0`, §8), PSG frequency writes (every running
frame, §7), modulation-step FM frequency writes (§9), and note-fill cut-offs all
continue. Only new notes are postponed. This is CD CAD-02's substance — but CAD-02's
wording is **wrong for S2 in both details**: it is *no-carry* (not carry) that extends
durations, and the increment hits every music track slot, not just active ones
(anchor `sd:596` correct, behaviour corrected here).

### 3.4 Test vectors

1. **EHZ tempo phase.** EHZ header (`sound/music/82 - EHZ.asm:5`,
   `smpsHeaderTempo $01, $9E`; conversion is identity since
   `SonicDriverVer == SourceDriver == 2`, `inc:190-192`): after load
   `CurrentTempo = TempoTimeout = 9Eh`. Frame 1: `9Eh+9Eh = 13Ch` → carry → normal
   (first note not stalled), `TempoTimeout = 3Ch`. Frame 2: `9Eh+3Ch = DAh` → no
   carry → **delay** (all 10 `DurationTimeout` +1), `= DAh`. Frame 3: `178h` → carry,
   `= 78h`. Frame 4: `116h` → carry, `= 16h`. Frame 5: `B4h` → no carry → delay.
   Stall rate 98/256.
2. **1-up jingle.** `98 - Extra Life.asm:5` (`smpsHeaderTempo $02, $CD`): divider 2 —
   a note byte `0Ch` becomes `DurationTimeout = 18h` (`zSetDuration`: 2 × 0Ch).
   Accumulator from `CDh`: frames 1-4 carry (`19Ah, 167h, 134h, 101h`), frame 5
   `CEh` → delay. Stall rate 51/256.
3. **Speed shoes.** With EHZ playing, command `FBh`: `CurrentTempo = TempoTurbo = BEh`
   (`zSpedUpTempoTable[1]`, `sd:3860` second byte), `SpeedUpFlag = 80h`,
   `TempoTimeout` untouched (`sd:2686-2710`) — stall rate drops from 98/256 to 66/256
   with no phase reset.
4. **Delay-frame side effects.** EHZ PSG1 (`fTone_03` envelope, header
   `82 - EHZ.asm:12`) holding a note across frame 2 of vector 1: the delay frame still
   emits the PSG attenuation write from envelope position advance (§8) and the PSG
   frequency write (§7) — a captured stream shows no gap at the stall.

### 3.5 Engine today

`TempoMode.OVERFLOW2` (`Sonic2SmpsSequencerConfig.CONFIG`;
`SmpsSequencer.processTempoFrame`, the `OVERFLOW2` branch at
`SmpsSequencer.java:1282-1291` at the merged head) ticks **only** on accumulator
overflow — on a ROM delay frame the engine runs no `tick()` at all, so envelopes,
modulation, note-fill and the per-frame PSG writes freeze (GA §1.2 #2,
risk **high**; EM §5.2 item 5). Adaptation: take the `TIMEOUT` shape — always tick,
pre-increment all music durations on the no-carry condition (including inactive
slots, which the engine's `t.active && t.duration > 0` gate currently skips — a
RAM-level deviation even for S1). Divider-0 wrap: engine behaviour unverified
(GA §1.2 #3).

---

## 4. Queue, dispatch, priority / admission

### 4.1 ROM behaviour

**`zCycleQueue`** (`sd:1496-1550`) runs only when `QueueToPlay == 80h` and any queue
byte is non-zero. `c = SFXPriorityVal`. For slots `Queue0..2` in order: read and zero
the byte; `< MusID__First (81h)` → next slot (a `01h-80h` byte is silently discarded);
`>= CmdID__First (F8h)` → `QueueToPlay = id`, return; music (`81h-9Fh`, detected by
carry on `sub SndID__First`) → `QueueToPlay = id`, return; otherwise SFX: fetch
`zSFXPriority[id - A0h]` (`sd:3716-3722`); if `< c` → **rejected** — the slot was
already zeroed, so the request is discarded, and the routine still falls through to
the store/return; else `c = new priority`, `QueueToPlay = id`. After examining the
**first SFX** (accepted or rejected) the routine always returns: if `c` has bit 7
(only Jump's `80h`) it is not stored back, else `SFXPriorityVal = c`
(`sd:1531-1536`). Net: **at most one request leaves the queue per invocation**;
later slots wait a frame; a same-frame lower-priority SFX behind a higher one is
delayed, not dropped — but a *rejected* SFX is dropped outright with no track init
(CD ADM-01 confirmed for S2, including "no channel-free fallback": the priority gate
runs before any channel logic).

**`zPlaySoundByIndex`** (`sd:1555-1600`): `00` → `zClearTrackPlaybackMem`; `01-7Fh`
ignored (`ret p` after `or a`, valid because `MusID__First-1 = 80h`); else
`QueueToPlay = 80h`, then by range (`const:832-970`): `81h-9Fh` → `zPlayMusic`;
`A0h-F0h` → `zPlaySound_CheckRing`; `F1h-F7h` ignored; `F8h-FDh` → command table
(`sd:1586-1600`): `F8` `zStopSoundEffects`, `F9` `zFadeOutMusic`, `FA`
`zPlaySegaSound`, `FB` `zSpeedUpMusic`, `FC` `zSlowDownMusic`, `FD`
`zStopSoundAndMusic`; `FE/FF` ignored (handled 68k-side, §1.1). Id `80h` is *not*
filtered: it would reach `zPlayMusic` with index −1 (§18 q3).

**`SFXPriorityVal` clear sites:** any SFX track hitting `F2` (`sd:3533-3534`),
`zStopSoundEffects` (`sd:2345-2346`), `zKillSFXPrio` — reached when an SFX request
arrives while `1upPlaying` or `FadeInFlag` (`sd:2118-2120`, `2334-2337`) — and 1-up
start (`sd:1720-1721`, after the backup — §13). It is preserved across ordinary song
loads (`sd:2589`, `2622`).

**Priority table** (`zSFXPriority`, `sd:3716-3722`, 81 bytes indexed `id - A0h`):
`A0` Jump = `80h`; `AA` = `68h`; `AE`, `B1`, `B3`, `D1`, `D2`, `DA`, `DC-DE`,
`E4-E6` = `60h`; `DB` = `62h`; `BF` = `7Fh`; `C0`, `CD`, `E7`, `EA`, `EB`, `F0` =
`6Fh`; `ED` = `71h`; all others `70h`.

### 4.2 State

Reads/writes `QueueToPlay`, `Queue0-2`, `SFXPriorityVal`; reads `zSFXPriority` (ROM
via `SoundIndex` bank? No — the priority table is in driver RAM-resident code space,
`sd:3716`).

### 4.3 Observable effect

Which id is dispatched on which invocation, and whether an SFX plays at all. No
chip writes from the queue itself.

### 4.4 Test vectors

1. **Jump + Ring, same frame.** `Queue0 = A0h`, `Queue1 = B5h`, `SFXPriorityVal = 0`.
   Frame 1 `zCycleQueue`: Jump priority `80h ≥ 0` → `QueueToPlay = A0h`; bit 7 set →
   **not stored**, `SFXPriorityVal` stays 0; return (Ring untouched). Same frame,
   `zPlaySoundByIndex` dispatches Jump. Frame 2: `QueueToPlay` is back to `80h`,
   `Queue1 = B5h` → Ring priority `70h ≥ 0` → dispatched, `SFXPriorityVal = 70h`.
2. **Priority rejection.** `SFXPriorityVal = 70h` (standing from vector 1),
   `Queue0 = D1h` (priority `60h`): `60h < 70h` → discarded, no track touched,
   `SFXPriorityVal` stays `70h` (stored back unchanged). The latch clears only when
   the ring SFX ends (`F2` on its track) — after which `D1h` would be accepted.
3. **Music through an SFX slot.** `Queue0 = 93h` (Boss): `sub A0h` carries →
   promoted straight to `QueueToPlay = 93h` — music ids work from `PlaySound`
   (`s2:1535-1537`) as well as `PlayMusic`.
4. **Equal priority passes.** Standing `70h`, request `CCh` (Spring, `70h`):
   `cp c` no-carry → accepted (equal or higher passes), `SFXPriorityVal = 70h`.

### 4.5 Engine today

All pending `AudioPresentationCommand`s drain in submission order at the outer-frame
boundary (EM §3.2 step 3); there are no queue bytes, no one-per-frame serialisation,
no global latch — priority lives per SFX sequencer with per-channel arbitration
(`SmpsDriver.shouldStealLock`, EM §1.4), and `Sonic2AudioProfile.getSfxPriority`
reads the Java table `Sonic2SmpsConstants.SFX_PRIORITY_TABLE` (EM §4.5). Adaptations
(GA §1.2 #4, #5): a ROM-shaped 3-byte mailbox + `QueueToPlay` in `SmpsDriver` with
the one-per-frame cycle rule, and a driver-global `sndPrio` gating whole-request
admission in `SmpsRequestAdmissionPolicy` before channel locks, cleared at the four
ROM sites above; the priority table read once from ROM (`zSFXPriority` bytes are in
the Saxman-compressed driver blob — §17.1).

---

## 5. Music load and silence bursts

### 5.1 ROM behaviour

**`zPlaySound_CheckRing` is not on this path** — music never passes the SFX gate.
`zPlayMusic` (`sd:1667-1724`):

1. **`zStopSoundEffects` first** (FixDriverBugs off, `sd:1668-1673`): every song
   start kills all SFX with the full per-track restore of §6.3 (CD ADM-07 confirmed
   for S2). Note the restore runs with the **music bank already selected** semantics
   of §6.3's caller rules.
2. `zCurSong = id`. If `MusID_ExtraLife (98h)` → §13. Otherwise
   `1upPlaying = FadeInCounter = FadeOutCounter = 0` (`sd:1728-1734`).

`zBGMLoad` (`sd:1738-1810`): `zInitMusicPlayback` (below); `TempoTurbo` from
`zSpedUpTempoTable[id-81h]` (`sd:1740-1747`, table `sd:3859-3867`); playlist byte
`zMasterPlaylist[id-81h]` (`sd:3823-3855`, built by `music_metadata`, `sd:3818-3820`):
bit 7 → `MusicBankNumber` (`80h` = `MusicPoint2` bank), bit 6 → `IsPalFlag = 0`,
bit 5 → **uncompressed**, bits 0-4 → index into the bank's leading `music_ptr` table
(`s2:91486-91489`) read through the ROM window. Compressed songs → `zSaxmanDec` into
`zMusicData` (`sd:3931-4082`: 16-bit little-endian size prefix, then LZSS with
descriptor bits, offsets `+12h` masked to 12 bits, before-start runs zero-filled —
CD DATA-01's framing confirmed); uncompressed songs are read in place through the
ROM window. Exactly **four playlist entries are uncompressed** — `98` Extra Life,
`9B` Game Over, `9D` Got Emerald, `9E` Credits (`sound/music/list of compressed
songs.txt` lists the other 27; DATA-01's "exactly four" confirmed). Extra Life being
uncompressed is what keeps the 1-up overlay from clobbering the saved song's
decompressed bytes in `zMusicData` (§13).

Header (`inc:306-357` macros): `+0/1` voice pointer → `VoiceTblPtr`; `+2` FM+DAC
count; `+3` PSG count; `+4` tempo divider (all tracks); `+5` tempo →
`TempoMod`, and `CurrentTempo = TempoTimeout = SpeedUpFlag ? TempoTurbo : TempoMod`
(`sd:1811-1822`); `zPALUpdTick = 5`. FM/DAC entries (4 bytes: pointer, transpose,
volume) fill `zSongDAC`, `zSongFM1…` with `PlaybackControl = 82h` (playing + at
rest, FixDriverBugs off `sd:1842-1846`), `VoiceControl` from
`zFMDACInitBytes = 6,0,1,2,4,5,6` (`sd:2107-2108`), divider, `StackPointer = 2Ah`,
`AMSFMSPan = C0h`, `DurationTimeout = 1` (CD CAD-01 confirmed for S2 —
anchor: the seeding is in `zBGMLoad` at `sd:1857`/`1970`, not in `zPlayMusic`
itself). PSG entries (6 bytes: pointer, transpose, volume, one skipped byte,
envelope) likewise with `zPSGInitBytes = 80h,A0h,C0h` (`sd:2112-2113`) and
`VoiceIndex` = byte 6 (`sd:1991-1994`).

**FM6/DAC decision** (`sd:1889-1937`): if FM+DAC count `== 7` → `DACEnabled = 0`
(FM6 in use, DAC off); else key off FM6 (`28h = 06h`), TL `42h/46h/4Ah/4Eh = FFh` on
part II (FixDriverBugs off `sd:1906-1917`), `B6h = C0h` (DAC panning, since the DAC
track never runs `zSetVoice`, `sd:1919-1924`), `DACEnabled = 80h`. Then write
`2Bh = DACEnabled` (`sd:1935-1937`).

**`zInitMusicPlayback`** (`sd:2580-2654`): save/zero/restore per §2.2, then
`QueueToPlay = 80h`, then (FixDriverBugs off, `sd:2649-2653`) **`zFMSilenceAll` +
`zPSGSilenceAll`**. `zFMSilenceAll` (`sd:2518-2540`): key-off `28h = 02,06, 01,05,
00,04` (b counts 3→1; each iteration writes the channel byte, then rewrites it with
bit 2 set — both through `zWriteFMI`, since `28h` is a part-I register and the bit
selects the part-II *channels* in the data byte),
then `FFh` to registers `30h-8Fh` on **both parts** (96 writes each) — including
FM3-5 that SFX may own, which is why step 1 of `zPlayMusic` stops SFX first.
`zPSGSilenceAll` (`sd:1412-1418`): `9Fh, BFh, DFh, FFh`.

**`zInitSFX`** (`sd:2008-2076`) then runs: for each playing SFX track, `res 2` on its
music track — dead in the shipped flow since SFX were already stopped, and inverted
from S1's `set 2` (FixDriverBugs off `sd:2039-2043`); then `zFMNoteOff` on the 6 FM
music tracks and `zPSGNoteOff` on the 3 PSG music tracks using their freshly written
`VoiceControl` (`sd:2051-2075`). An undefined PSG track carries `VoiceControl = 0`
after the clear, so its note-off writes PSG byte `1Fh` (§18 q5).

### 5.2 Observable effect (the ordered song-start burst)

For a 6-FM song (e.g. EHZ, `smpsHeaderChan $06, $03` — FM+DAC count 6, so FM6
unused): key-off ×6 (`28h`: 02,06,01,05,00,04), `FFh`→`30h-8Fh` both parts,
PSG `9F BF DF FF`, `28h = 06h`, TL `42/46/4A/4E = FFh` (part II), `B6h = C0h`,
`2Bh = 80h`, then `zInitSFX`'s `28h` note-offs ×6 (`00,01,02,04,05,06`) and PSG
`9F BF DF` — three `zPSGNoteOff`s on the music PSG tracks; the noise `FFh` write
is the FixBugs branch (§7), so it is **not** repeated here.
Control returns from `zBGMLoad` to the same `zUpdateEverything` pass, so that
pass then calls `zUpdateMusic`: the first notes are parsed in the same V-int
that finishes the load (`DurationTimeout` was initialised to 1). A long Saxman
load can span several video frames before that first update completes, but it
does not create a separate driver invocation between load and note parsing.

### 5.3 Test vectors

1. **EHZ load** (id `82h` via `PlayMusic`): burst above;
   `VoiceTblPtr = EHZ_Voices` (Z80 address in `zMusicData`), `TempoMod = 9Eh`,
   `TempoTurbo = BEh`, `DACEnabled = 80h`, all 10 tracks `82h/…` with divider 1,
   PSG1 `VoiceIndex = 3`, PSG2 = 1, PSG3 = 2 (`82 - EHZ.asm:4-14`, `fTone` equates
   `inc:65-66`).
2. **Song load with SFX playing:** ring SFX on FM5 → `zStopSoundEffects` restores
   `zSongFM5` (voice + `B4h` + TLs, §6.3) *before* the destructive
   `zFMSilenceAll` — so the restore burst is audible-order irrelevant but
   write-order visible: restore writes, then silence writes, then init writes.
3. **`SpeedUpFlag` persistence:** with speed shoes active, load Boss (`93h`):
   `CurrentTempo = TempoTimeout = zSpedUpTempoTable[12h] = FFh` (19th byte,
   `sd:3864`) — the new song starts at its turbo tempo (CAD-12).
4. **Queue2 loss:** `Queue2 = C6h` pending when a song load runs →
   after `zInitMusicPlayback` the byte is gone (not preserved, `sd:2595-2617`);
   the ring-spill SFX never plays.

### 5.4 Engine today

Sequencer construction + first-read priming (EM §3.2 step 7); `stopAll` exists but
the init burst's write shape is unverified against the above (GA §1.2 #7, verify).
The `DACEnabled` latch and the destructive `FFh→30h-8Fh` sweep have no engine
counterpart; the S2 music-id → offset resolution uses a hardcoded REV01 map
(`Sonic2SmpsLoader`, documented divergence KD "S2 Music Offsets", CD REQ-06/DEF-06 —
the ROM behaviour is the `zMasterPlaylist` + `music_ptr` resolution above, which is
the removal condition's target).

---

## 6. SFX load, ownership, override, restore

### 6.1 Pre-checks (`sd:2116-2176`)

`zPlaySound_CheckRing`: if `1upPlaying | FadeInFlag` → `zKillSFXPrio` (drop request
**and zero the priority latch**). `zSpindashActiveFlag = 0` for every request that
passes. Then the request transforms of §14 (ring, gloop, spindash), then
`zPlaySound`.

### 6.2 `zPlaySound` (`sd:2178-2331`)

`bankswitch SoundIndex`; pointer from `SoundIndex[(id-A0h)·2]` (`s2:91668`); SFX
header (`inc:360-386`): voice table pointer (2), tempo divider (1), channel count
(1); then per channel 6 bytes: `80h, chanid, ptr(2), transpose, volume`. Per
channel:

- PSG (`chanid` bit 7): if `C0h` (PSG3) first write `DFh` then `FFh` to the PSG
  (silence PSG3 tone *and* noise, `sd:2221-2228`); index = `chanid >> 4 & 0Fh`.
- FM: index = `(chanid - 2) · 2` — SFX may use only FM3 (`02`), FM4 (`04`), FM5
  (`05`); DAC, FM1, FM2, FM6 are structurally excluded (`sd:735-757`).
- `set 2` on the corresponding music track (`zMusicTrackOffs`, `sd:747-750`); zero
  the SFX track (2Ah bytes); copy the header's playback byte (`80h`) and `chanid`
  into `PlaybackControl`/`VoiceControl`; divider from header;
  `DurationTimeout = 1`; `StackPointer = 2Ah`; pointer + transpose (+ spindash
  boost, §14.3); volume; FM only: `AMSFMSPan = C0h` and `VoicePtr` = header voice
  pointer (`sd:2313-2324`).
- **No key-off, no TL reset, no register write of any kind for the taken FM
  channel** (CD ADM-05 confirmed): the SFX's own `EF` flag uploads its voice on its
  first service — which is the *same* invocation (the SFX pass runs after dispatch,
  §1.1, and `DurationTimeout = 1` expires immediately). Channels are claimed at
  request time (CD ADM-03 for S2: admission-time override).
- `zBankSwitchToMusic` on exit.

While bit 2 is set on a music track it keeps parsing and advancing every frame, but
all its hardware writes are gated: `zFMNoteOn` (`sd:2797-2800`), `zFMNoteOff`
(`sd:2814-2817`), `zFMUpdateFreq` (`sd:1089-1091`), `zPSGUpdateFreq`
(`sd:1209-1212`), `zPSGUpdateVol` (`sd:1307-1310`), `zPSGNoteOff` (`sd:1357-1359`),
`zSetChanVol` (`sd:3439-3443`), `cfSetVoice` (`sd:3271-3275`), `cfSetPSGNoise`
(`sd:3604-3608`), `cfPanningAMSFMS` (`sd:3022-3029` — returns before even *storing*
the new pan, so a pan flag executed under override is lost entirely, §10) and the
DAC track's sample trigger (`sd:798-799`).

### 6.3 SFX end and music restore

`cfStopTrack` on an SFX track (`sd:3514-3599`): `res 7`, `res 4`; FM → `zFMNoteOff`,
PSG → `zPSGNoteOff` (on the *SFX* track — real writes, since the SFX track has no
bit 2); `SFXPriorityVal = 0`; then the music-track restore:

- FM (`sd:3538-3562`): if the music track's bit 2 is set → `res 2`, `set 1`
  (at rest), `zBankSwitchToMusic`, `zSetVoiceMusic(VoiceIndex)` — the full voice
  upload of §10 including `B4h = AMSFMSPan` and TLs with `Volume` — then
  `bankswitch SoundIndex`. **Frequency is not resent**; the channel stays keyed off
  and silent until the music track's next note (CD ADM-04 confirmed for S2).
- PSG (`sd:3565-3590`): `res 2`, `set 1` **unconditionally** (no bit-2 check); if
  the music track is `E0h` (noise) rewrite `PSGNoise` to the PSG; tone PSG tracks
  get no volume or frequency write until their next note/envelope frame.
- Pops two return addresses (`sd:3593-3599`), abandoning the rest of that track's
  update.

`zStopSoundEffects` (`F8h`, `sd:2344-2418`): the same restore for every playing SFX
track, plus the latch reset. Runs with the music bank selected (callers: command
dispatch, `zPlayMusic`).

### 6.4 Test vectors

1. **Ring right, full lifecycle.** Request `B5h` with `zRingSpeaker = FFh` (§14.1
   keeps `B5h`): header `B5 - Ring.asm:1-15` — voice `Sound_Ring_Voices`
   (`C5 - Tally End.asm:49-67`), divider 1, one channel `cFM5 (05h)`, transpose 0,
   volume `05h`. Load: `set 2` on `zSongFM5`; `zSFX_FM5` zeroed then
   `PlaybackControl = 80h`, `VoiceControl = 05h`, `DurationTimeout = 1`,
   `AMSFMSPan = C0h`. Same invocation, SFX pass, first service emits (all FM5 data
   writes on part II because `VoiceControl` bit 2 is set; `ch = VoiceControl&3 = 1`):
   - `EF 00` voice upload (voice bytes `04; 37 77 72 49; 1F 1F 1F 1F; 07 07 0A 0D;
     00 00 0B 0B; 1F 1F 0F 0F; TL 23 23 80 80` — the bytes the `smpsVc*` macros
     emit for `SonicDriverVer == 2` with `SourceSMPS2ASM = 0`, ops in `4,2,3,1`
     order and algorithm-derived bit 7 OR'd onto the slot TLs, `inc:917-958`; the
     in-file byte comment above `Sound_Ring_Voices` lists the middle operator pair
     in the S1/S3K `4,3,2,1` order and does not match the assembled bytes):
     `B1 = 04`; `31/35/39/3D = 37/77/72/49`;
     `51/55/59/5D = 1F×4`; `61/65/69/6D = 07/07/0A/0D`; `71/75/79/7D = 00/00/0B/0B`;
     `81/85/89/8D = 1F/1F/0F/0F`; `B5 = C0`; algorithm 4 → `zVolTLMaskTbl[4] = 0Ch`
     (`sd:3235-3238`) → TL writes `41 = 23h`, `45 = 23h`, `49 = 80h+05h = 85h`,
     `4D = 80h+05h = 85h` (the masked slots carry the `80h`-flagged TL bytes;
     unclamped 8-bit add, bit 7 lands in the register,
     `sd:3399-3431`; hardware effect §18 q6).
   - `E0 40` (`smpsPan panRight`): `AMSFMSPan = (C0h & 37h) | 40h = 40h`; `B5 = 40`.
   - Note `nE5, $05`: key-off `28 = 05` (part I); frequency: index
     `C1h-80h = 65 = 5·12+5` → octave 5, base note E (the table's word 0 is B so
     that note `81h` = index 1 = C, `sd:1384-1409`);
     `zMakeFMFrequency(20.64) = round(20.64 · 2^21 / 53266.99) = 813 = 32Dh`;
     word `= 32Dh + 5·800h = 2B2Dh` → `A5 = 2B`, `A1 = 2D`; key-on `28 = F5`.
   - 5 frames later `nG5` (index 68 → G, `round(24.51·39.3707) = 965 = 3C5h` →
     `2BC5h`): `28 = 05`, `A5 = 2B`, `A1 = C5`, `28 = F5`. Then `nC6, $1B`
     (index 73 → oct 6 C, `644 = 284h` → `3284h`): `28 = 05`, `A5 = 32`,
     `A1 = 84`, `28 = F5`.
   - After `1Bh` frames `smpsStop` (`F2`): `28 = 05`; `SFXPriorityVal = 0`;
     `zSongFM5` restore: `res 2`, `set 1`, EHZ voice `VoiceIndex` re-uploaded
     (`zSetVoiceMusic`) with EHZ FM5 volume `25h` in the TLs, `B5 = <music pan>`;
     **no `A5/A1` write** until EHZ FM5's next note.
2. **Overridden music pan is lost.** If EHZ FM5's data hits `E0 xx` while the ring
   SFX owns FM5, the flag returns before storing (`sd:3022-3029`): after restore the
   channel plays with the *old* `AMSFMSPan` until the next `E0` in the music.
3. **PSG3 SFX admission silences noise.** Any SFX with a `cPSG3 (C0h)` channel
   writes `DF, FF` to the PSG at load time even though the music PSG3 track may be a
   tone track — one-off silence, then the SFX's own writes.
4. **SFX blocked during 1-up.** `1upPlaying = 80h`, request `A0h`: dropped, and
   `SFXPriorityVal = 0` (`zKillSFXPrio`) — the block *resets* the latch as a side
   effect.

### 6.5 Engine today

Locks per chip channel (`SmpsDriver.fmLocks/psgLocks`), `Track.overridden`, release
"restores instrument/volume/pan/frequency" (EM §1.4). Adaptation (GA §1.2 #6): the
restore burst must be the ordered list above — voice + pan + TLs, **at-rest, no
frequency**; any frequency resend in `SmpsDriver.setChannelOverridden` release is a
write the ROM never emits and must go. Song-load SFX policy: S2 stops all SFX
(§5.1 step 1) — profile knob confirmed. The pan-lost-under-override shipped bug
(vector 2) must be reproduced, not fixed.

---

## 7. Note parse, frequency, key on/off

### 7.1 ROM behaviour

Track bytes `E0h-FFh` are coordination flags (§11); `80h-DFh` notes; `01h-7Fh`
durations. FM (`zFMDoNext`, `sd:842-870`): on a note byte, key-off first
(`zFMNoteOff` — skipped if bit 4 or bit 2), then `zFMSetFreq`; a following byte
`< 80h` is a new duration, else `SavedDuration` is reused. A *bare* duration byte
(no note) reaches `.gotduration` without touching the frequency — the previous
note's pitch is re-attacked. PSG (`zPSGDoNext`, `sd:1145-1169`): same shape without
the key-off.

`zFMSetFreq` (`sd:874-914`): `a -= 80h`; zero → `zFMDoRest` (`sd:918-923`: `set 1`,
`Freq = 0`); else index `= a + Transpose`, doubled into the low byte of a
`ld de,(zFrequencies)` operand (self-modifying, `sd:896-905`) — the table is kept in
one 256-byte page (`ensure1byteoffset 0C0h`, `sd:1396`), so an index outside 0-95
wraps within the page and reads neighbouring non-table bytes rather than faulting.
`zFrequencies` (`sd:1384-1409`): 8 octaves × 12 words,
`round(f · 2^21 / FM_Sample_Rate) + octave · 800h`, base octave B 15.39 Hz … Bb
29.15 Hz (`sd:1386-1390`), `FM_Sample_Rate = M68000_Clock/144` with
`M68000_Clock = 53693175/7` (`const:2139-2143`) — so note `81h` (index 1) is C.

`zPSGSetFreq` (`sd:1175-1196`): `a -= 81h`; carry (byte `80h`) → rest: `set 1`,
`Freq = FFFFh`, `zPSGNoteOff`; else `(a + Transpose)·2` into `zPSGFrequencies`
(`sd:1053-1075`: 70 words, `min(3FFh, round(PSG_Sample_Rate/(2f)))`,
`PSG_Sample_Rate = Z80_Clock/16`, six octaves from C 130.98 Hz, last entry the
maximum-rate "hi-hat" word).

Write cadence:

- **FM frequency is written at note-on and on modulation steps only.**
  Expiry path: `zFMDoNext` → `zFMPrepareNote` (`sd:1079-1087`: rest → return;
  `Freq == 0` → `zSetRest`; else fall into `zFMUpdateFreq`) → `zFMNoteOn` →
  `zDoModulation`. Running path: `zNoteFillUpdate` → `zDoModulation` →
  `jp zFMUpdateFreq` — but `zDoModulation` (§9) **pops the return address** and only
  falls through to the frequency writer when a modulation step actually lands;
  otherwise it returns to the caller's caller, skipping the write (`sd:986-997`).
- **PSG frequency is written every running frame** in which modulation is inactive
  or steps: same structure, but on the expiry path `zPSGDoNoteOn` (`sd:1202-1207`)
  writes the frequency, and on the running path the same `zDoModulation` gate
  applies — however PSG tracks nearly always carry `VoiceIndex != 0` envelopes whose
  volume writes are unconditional (§8). Where modulation is *off* (bit 3 clear),
  `zDoModulation` returns to the caller's caller — so a PSG track without
  modulation also skips the per-frame frequency rewrite. (The gap analysis' summary
  "PSG freq every frame" holds only for modulating tracks; the precise rule is:
  frequency writes happen at note-on and on modulation steps, FM and PSG alike.)
- `zFMUpdateFreq` (`sd:1089-1117`): `hl = signext(Detune) + Freq`; write
  `A4h+ch = h` then `A0h+ch = l` (part per `VoiceControl` bit 2). `zPSGUpdateFreq`
  (`sd:1209-1251`): `hl = signext(Detune) + Freq`; `E0h` tracks write with latch
  `C0h`; byte 1 `reg | (l & 0Fh)`, byte 2 `(hl >> 4) & 3Fh`. `zPSGUpdateFreq`
  returns without writing when bit 1 or bit 2 is set (`and 6`, `sd:1210-1212`);
  `zFMUpdateFreq` tests **only bit 2** (`sd:1090`) — an FM rest never reaches it
  because `zFMPrepareNote` returns on bit 1 and `zDoModulation` returns to the
  caller's caller on bit 1.
- Key-on (`zFMNoteOn`, `sd:2797-2806`): `28h = VoiceControl | F0h`, part I always;
  skipped when bit 1 or 2. Key-off (`zFMNoteOff`, `sd:2814-2827`):
  `28h = VoiceControl`; skipped when bit 4 or 2. `zPSGNoteOff` (`sd:1357-1380`):
  `VoiceControl | 1Fh`; noise is **not** silenced when PSG3 stops (FixBugs-off,
  `sd:1369-1379` — audible in CNZ end-of-level music per the in-source comment).
- Note fill (`zNoteFillUpdate`, `sd:968-979`): when `NoteFillTimeout` reaches 0,
  `set 1` and jump directly to the note-off routine, abandoning the rest of that
  frame's update (volume/modulation/frequency skipped that frame).

### 7.2 Test vectors

1. **Ring pitches** — §6.4 vector 1 (`2B2Dh`, `2BC5h`, `3284h` with full write
   order).
2. **Jump PSG.** `A0 - Jump.asm`: PSG1, transpose `F4h` (−12), `nF2, $05` →
   index `(9Eh-81h) + F4h = 1Dh - 0Ch = 11h = 17` → word 17 = 349.56 Hz →
   `round(223721.56/699.12) = 320 = 140h` → writes `80h | (40h & 0Fh) = 80h`,
   then `(140h >> 4) & 3Fh = 14h`. Attack-frame volume: `VoiceIndex = 0`
   (`smpsPSGvoice $00`) → single write `VoiceControl | 10h | Volume = 90h` (§8).
   After 5 frames `nBb2, $15` → index `(A3h-81h) - 12 = 16h = 22` → 468.03 Hz →
   `239 = EFh` → writes `8Fh`, `0Eh`.
3. **Tied note.** `smpsNoAttack (E7)` before a note sets bit 4: the following
   expiry skips key-off (bit 4 checked in `zFMNoteOff`) and, via
   `zFinishTrackUpdate`'s bit-4 early return, keeps `NoteFillTimeout` and
   `VolFlutter` — the spindash rev's `nG5,$16,smpsNoAttack → nG6,$18` re-pitches
   without a key edge (`E0 - Spin Dash Rev.asm:13-15`).
4. **Rest.** FM byte `80h`: `zFMDoRest` zeroes `Freq`, sets bit 1; the pending
   key-on and frequency writes are suppressed (`zFMPrepareNote` returns on rest) —
   a rest emits only the preceding key-off.

### 7.3 Engine today

`baseFnum/baseBlock`, modulation-gated `forceModulationWrite`/`modStepChanged`
flags (EM §2.2) — the S1/S2 "write on note-on and mod step" shape is modelled
(GA §1.2 #8: verify, low risk). Frequency tables are Java-resident
(`SmpsSequencer.FNUM_TABLE_*`; EM §4.5) — §17.1. Page-wrap on out-of-range
transposes is not modelled (no shipped S2 stream found relying on it; §18 q7).

---

## 8. PSG volume and envelopes

### 8.1 ROM behaviour

Expiry path: `zPSGDoVolFX` (`sd:1276-1305`); running path: `zPSGUpdateVolFX`
(`sd:1265-1269`) — which returns immediately when `VoiceIndex == 0`, so **a track
without an envelope writes volume only on the attack frame**; a track with
`VoiceIndex != 0` writes attenuation **every running frame**: envelope byte =
`zPSG_EnvTbl[VoiceIndex-1][VolFlutter++]` (`sd:3725-3806`; note `zPSG_Env6` is
stored before `zPSG_Env5`, `sd:3754-3764`); byte `< 80h` → added to `Volume`;
`== 80h` → `zVolEnvHold` (`sd:1339-1349`, FixBugs off): `dec VolFlutter; ret` —
**no volume write this frame and none until the next attacked note** (the cursor
parks on the terminator; CD's S2 "80h halts volume writes" confirmed); `81h-FFh` →
added like a value (two's-complement louder).

`zPSGUpdateVol` (`sd:1307-1325`): bits 1/2 → return; if bit 4 (tie): write only when
`NoteFillMaster == 0` or `NoteFillTimeout != 0` (`sd:1329-1336`); clamp `b ≥ 10h` to
`0Fh`; write `VoiceControl | 10h | b`. `VolFlutter` resets to 0 at every attacked
note (`zFinishTrackUpdate`, `sd:957`).

### 8.2 Test vectors

1. **EHZ PSG1, `fTone_03`** (`zPSG_Env3 = 0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,80h`,
   `sd:3747-3748`), `Volume = 04h`: successive frames write attenuation
   `94h, 94h, 95h, 95h, 96h, 96h, … 9Bh, 9Bh`, then the `80h` terminator freezes
   writes; the channel holds `0Bh` attenuation until the next note resets
   `VolFlutter`.
2. **Envelope on a delay frame:** the frame-3.4-vector-4 shape — the attenuation
   write stream continues through tempo stalls (running branch always calls
   `zPSGUpdateVolFX`).
3. **`EC 05` mid-track** (`cfChangePSGVolume`, `sd:3243-3246`): `Volume += 5` with
   **no hardware write** — audible only from the next envelope frame or attack.
4. **Tie + fill gate:** with bit 4 set and `NoteFillMaster != 0`,
   `NoteFillTimeout == 0` → the volume write is suppressed entirely
   (`sd:1329-1336`) — the cut note does not get a fresh attenuation write.

### 8.3 Engine today

`processPsgEnvelope` with `PsgEnvCmd80` config; S2 envelopes are Java-resident
(`Sonic2PsgEnvelopes`, EM §4.5) — read them from the driver blob instead (§17.1).
The `80h` halt semantic is config-selected (GA §1.2 #12: verify per flag). The
every-running-frame write only happens if delay frames tick (§3.5).

---

## 9. Modulation

### 9.1 ROM behaviour

`F0 ww xx yy zz` (`cfModulation`/`zSetModulation`, `sd:3467-3501`): `set 3`;
`ModulationPtr = hl-1` (the parameter block's address — re-read at every re-arm);
copy `ww/xx/yy` into wait/speed/delta; `Steps = zz >> 1` (halved); unless bit 4
(tie), zero `ModulationVal`. Re-armed from `ModulationPtr` at every attacked note
when bit 3 is set (`zFinishTrackUpdate`, `sd:958-962`). `F1`/`F4` set/clear bit 3
(`sd:3506-3509`, `3615-3618`).

`zDoModulation` (`sd:986-1050`), called on both paths after volume/note handling:
pops its return address; bit 1 set or bit 3 clear → return to caller's caller (no
frequency write this frame, §7); `ModulationWait` counts down first (no write while
waiting); then `ModulationSpeed` counts down — non-zero → return (no write);
expired → reload speed from `ptr+1`; if `ModulationSteps == 0` → reload steps from
`ptr+3` **raw — the `zz >> 1` halving happens only at arm time** (`sd:3494-3495`
vs `sd:1011-1015`), so the first leg runs `zz/2` steps and every later leg `zz` —
and **negate `ModulationDelta`** with no write that frame; else `dec` steps,
`ModulationVal += signext(Delta)`, `de = Freq + ModulationVal`, jump into the
frequency writer (a real write, even under a tempo delay frame).

### 9.2 Test vectors

1. **Jump SFX** (`smpsModSet $02,$01,$F8,$65` → wait 2, speed 1, delta −8, steps
   `65h>>1 = 32h`): frames 1-2 after the attack no frequency write; from frame 3 a
   write every frame: `140h-8 = 138h` → `88h/13h`, `130h` → `80h/13h`, `128h` →
   `88h/12h`… for 50 steps; then one silent frame (delta → +8, steps reload **to
   `65h` raw = 101 steps**, §9.1), then 101 rising writes — legs after the first
   are twice the length of the first.
2. **Ring FM5 has no `F0`** — bit 3 stays clear, so its running frames emit **no**
   `A5/A1` rewrites: the only frequency writes are the three note-ons of §6.4.
3. **Steps-halving check:** `zz = 65h` (odd) → 32h steps, i.e. the *first*
   down-leg is 50 frames, not 101 — while every later leg is 101 (raw reload,
   §9.1). The first-turnaround frame count and the leg asymmetry are both
   checkable signatures in a captured stream.

### 9.3 Engine today

`modPending*` copies + init/counter pairs (EM §2.3) — same state, `same` fit
(GA §1.2 #10); the ROM re-reads through `ModulationPtr` while the engine copies at
`F0` time — indistinguishable for ROM data (the bytes are immutable), `derived` for
the RAM comparison. The delay-frame gate is §3's issue, not modulation's.

---

## 10. Voice upload and FM volume

### 10.1 ROM behaviour

`EF xx` (`cfSetVoice`, `sd:3271-3279`): `VoiceIndex = xx`; bit 2 → return (stored
for the §6.3 restore); else `cfSetVoiceCont` (`sd:3285-3293`): `zDoSFXFlag != 0` →
`hl = VoicePtr` (SFX custom table), else `zSetVoiceMusic` → `hl = VoiceTblPtr`.

`zSetVoice` (`sd:3305-3397`), voice = 25 bytes at `hl + 25·index`, `ch =
VoiceControl & 3`, part by bit 2, every write through the busy-polling `rst`
helpers (§10.3):

1. byte 0 → `B0h+ch` (feedback/algorithm; kept via self-modified `.a_backup`);
2. bytes 1-4 → `30h+ch, 34h+ch, 38h+ch, 3Ch+ch` (DT/MUL, register order);
3. bytes 5-20 → `50h+ch … 8Ch+ch` step 4 (RS/AR ×4, AM/D1R ×4, D2R ×4, D1L/RR ×4);
4. `B4h+ch = AMSFMSPan` (from track RAM, not the data stream);
5. `TLPtr = hl` (the 4 TL bytes' address), `VolTLMask = zVolTLMaskTbl[alg]`
   (`sd:3235-3238`: `8,8,8,8,0Ch,0Eh,0Eh,0Fh` — bit n marks TL register `40h+ch+4n`
   as a volume slot);
6. `zSetFMTLs` (`sd:3399-3431`): 4 TL bytes → `40h+ch` step 4; slots whose mask bit
   is set get `TL + Volume` — **an unclamped 8-bit add whose bit 7 reaches the
   register** (FixBugs off `sd:3410-3424`; CD VOICE-01's S2 half confirmed;
   hardware meaning §18 q6).

`E6 xx` (`cfChangeFMVolume`, `sd:3175-3178`): `Volume += xx` then `zSetChanVol`
(`sd:3439-3456`): PSG → return; bit 2 → return; `Volume` bit 7 → return; else
re-run `zSetFMTLs` from `TLPtr` — four TL writes immediately. `EC` (PSG) changes
`Volume` with no write (§8).

### 10.2 Register routing

`28h`, `2Ah/2Bh/27h` always part I (`zWriteFMI`); per-channel registers via
`zWriteFMIorII` (`sd:343-347`), part II iff `VoiceControl` bit 2 — FM4-6 and the
DAC track. `zWriteFMI/II` (`sd:352-389`) `rst zFMBusyWait` **before the address
strobe and again before the data strobe** (`sd:331-337`) — the S2 driver polls the
YM busy flag around every single byte (CD VOICE-05's driver half confirmed for S2;
the busy window's length is a chip question, resolved-by-chip-cores).

### 10.3 Test vectors

1. **Ring voice upload** — §6.4 vector 1's 26-write sequence (1 `B0h+ch` + 4
   DT/MUL + 16 + 1 pan + 4 TL) with computed TLs (`23/23/85/85`).
2. **`E6` mid-note:** EHZ FM5 data `smpsAlterVol $F8` (`E6 F8`): `Volume = 25h-8 =
   1Dh`, immediate TL rewrite of the masked slots only.
3. **Restore-path upload:** §6.4 vector 1's tail — same 26 writes with the music
   track's voice/volume/pan, no frequency, no key.
4. **Volume bit 7 guard:** after `E6` pushes `Volume` to `≥ 80h`, `zSetChanVol`
   stops writing TLs at all (`sd:3448-3450`) — the stale TLs stay on the chip.

### 10.4 Engine today

`FmVoiceWriteProfile.S2_Z80` (`Sonic2SmpsSequencerConfig`), `VolMode` TL masking —
`same via profile` (GA §1.2 #9); the unclamped 8-bit TL add must be preserved
exactly (no saturation "cleanup").

---

## 11. Coordination flags

Dispatcher: `zCoordFlag` (`sd:2853-2871`) — `(byte - E0h)·4` self-modified into a
`jr` over a `jp` table (`sd:2870-3001`); the operand byte is pre-loaded into `a`;
flags without a parameter `dec hl`. Per-flag shipped behaviour and engine site:

| Flag | Routine (anchor) | Shipped effect | Engine (EM §1.4/§4.3) |
|---|---|---|---|
| `E0 xx` | `cfPanningAMSFMS` (`sd:3006-3045`) | PSG → return; **bit 2 → return before storing** (§6.4 v2); else `AMSFMSPan = (old & 37h) \| xx`, write `B4h+ch`. PSG test is `bit 7; ret m` (§18 q2) | shared switch; verify the store-skip under override |
| `E1 xx` | `cfDetune` (`sd:3051-3053`) | `Detune = xx` (applied at every freq write, §7) | `detune` |
| `E2 xx` | `cfSetCommunication` (`sd:3059-3061`) | `Communication = xx`; never read | `commData` |
| `E3` | `cfJumpReturn` (`sd:3066-3079`) | pop from gosub stack, `StackPointer += 2`; the byte after `E3` was fetched as the operand but is irrelevant (no `dec hl` needed — `hl` is replaced) | `returnStack` |
| `E4` | `cfFadeInToPrevious` (`sd:3084-3163`) | §13 | `RestoreMusicOverride` path |
| `E5 xx` | `cfSetTempoDivider` (`sd:3168-3170`) | this track's `TempoDivider = xx` | `dividingTiming` |
| `E6 xx` | `cfChangeFMVolume` (`sd:3175-3178`) | §10 | `volumeOffset` + refresh |
| `E7` | `cfPreventAttack` (`sd:3183-3186`) | `set 4`; `dec hl` | `tieNext` |
| `E8 xx` | `cfNoteFill` (`sd:3191-3194`) | `NoteFillTimeout = NoteFillMaster = xx` | `fill` |
| `E9 xx` | `cfChangeTransposition` (`sd:3199-3202`) | `Transpose += xx` | `keyOffset` |
| `EA xx` | `cfSetTempo` (`sd:3207-3209`) | `CurrentTempo = xx`; `TempoMod`/`TempoTurbo`/`TempoTimeout` untouched | tempo set |
| `EB xx` | `cfSetTempoMod` (`sd:3214-3226`) | `TempoDivider = xx` on all **10 music** tracks (even from an SFX track) | shared |
| `EC xx` | `cfChangePSGVolume` (`sd:3243-3246`) | `Volume += xx`, no write | `volumeOffset` |
| `ED xx` | `cfClearPush` (`sd:3254-3258`) | `ret` without `dec hl` — **the byte after `ED` is skipped** (S1 leftover) | config param override — verify 1-operand skip |
| `EE` | `cfStopSpecialFM4` (`sd:3264-3266`) | `dec hl`; no-op (special SFX not ported from S1) | config |
| `EF xx` | `cfSetVoice` (`sd:3271-3279`) | §10 | voice load |
| `F0 ww xx yy zz` | `cfModulation` (`sd:3467-3501`) | §9 | mod set |
| `F1` | `cfEnableModulation` (`sd:3506-3509`) | `dec hl`; `set 3` | `modEnabled` |
| `F2` | `cfStopTrack` (`sd:3514-3599`) | `res 7`, `res 4`; note-off; DAC → pop once; music → pop twice; SFX → §6.3 restore + latch clear | track end |
| `F3 xx` | `cfSetPSGNoise` (`sd:3604-3610`) | `VoiceControl = E0h`, `PSGNoise = xx`; bit 2 → skip the PSG write | `noiseMode`/`psgNoiseParam` |
| `F4` | `cfDisableModulation` (`sd:3615-3618`) | `dec hl`; `res 3` | |
| `F5 xx` | `cfSetPSGTone` (`sd:3623-3625`) | `VoiceIndex = xx` | `instrumentId` |
| `F6 lo hi` | `cfJumpTo` (`sd:3630-3633`) | `hl = hi:lo` — an **absolute Z80 address** into `zMusicData`/ROM window | `pos` (relative) — derived |
| `F7 ii nn lo hi` | `cfRepeatAtPos` (`sd:3644-3676`) | `LoopCounters[ii]`: 0 → load `nn`; `dec`; non-zero → jump, zero → skip the address (count includes the first encounter) | `loopCounters` |
| `F8 lo hi` | `cfJumpToGosub` (`sd:3681-3699`) | `StackPointer -= 2`, push return, jump | `returnStack` |
| `F9` | `cfOpF9` (`sd:3704-3712`) | `88h = 0Fh`, `8Ch = 0Fh` on **part I** — D1L/RR of FM channel 1 operators 3/4, regardless of which track executed it (S1 SYZ leftover); `dec hl` | shared — verify it targets FM1 unconditionally |

No `FAh-FFh` flags exist in this driver: bytes `E0h-FFh` all dispatch through the
26-entry table (`FA+` would index past `cfOpF9` into unrelated code — no shipped
stream contains them; §18 q7).

Engine: shared `handleFlag` switch, no S2 handler class (EM §4.3). The table above
is the per-flag verification list GA §1.2 #22 asks for; `ED`'s skipped operand and
`E0`'s store-skip are the two most likely "cleaned up" divergences to check.

---

## 12. Pause / unpause

### 12.1 ROM behaviour

Entry: §1.1 step 2 with `StopMusic` in the flags (`or a` at `s2d` caller,
`sd:403-406`): `7Fh` (positive) → pause, `80h` (negative, `jp m`) → unpause
(`zPauseMusic`, `sd:1422-1463`).

**Pause:** if `zPaused` already `FFh` → return (every later paused frame does
nothing but `zUpdateDAC` — an in-flight DAC sample plays to its end, then silence;
no new drum can start because the DAC track is not serviced; CD PAUSE-04 for S2).
First pause frame: `zPaused = FFh`; `zFMSilenceAll` — the **destructive** key-offs
and `FFh → 30h-8Fh` sweep of §5.1 (CD PAUSE-02 confirmed; anchors `zPauseMusic
sd:1422`, `.unpause sd:1432`, `zFMSilenceAll sd:2518`) — then `zPSGSilenceAll`.
Track RAM is untouched.

**Unpause:** `StopMusic = 0`, `zPaused = 0`; `zResumeTrack` (`sd:1468-1491`) over
DAC + FM1-6 with the music bank: for each playing, non-overridden track, write
`B4h+ch = AMSFMSPan` then `cfSetVoiceCont(VoiceIndex)` — a **full voice reload**
(§10) including TLs. The DAC track (`VoiceControl = 6`) is included, so **voice 0
of the music voice table is written into FM6's registers** (shipped quirk; audible
effect gated by `2Bh`, §18 q9). Then `bankswitch SoundIndex`, `zDoSFXFlag = FFh`,
`zResumeTrack` over the 3 FM SFX tracks (custom voice tables), `zDoSFXFlag = 0`,
`zBankSwitchToMusic`. **Not restored:** key states (notes stay off until their next
note-on), FM frequencies, all PSG state (frequency/volume return via the per-frame
running writes and next notes), `2Bh` (still holds `DACEnabled` from before —
never rewritten here). The unpause frame runs no track updates (`zVInt` jumps to
`zUpdateDAC` after `zPauseMusic`). CD PAUSE-05 for S2: resume = pan + full voice
reload, nothing else.

### 12.2 Test vectors

1. **Pause during EHZ + ring SFX:** frame N (`StopMusic = 7Fh`): key-offs
   `28h = 02,06,01,05,00,04`, 192 × `FFh` TL/env writes, PSG `9F BF DF FF`. Frames
   N+1…: no writes except DAC residue. Kick drum triggered on frame N−1 finishes
   streaming.
2. **Unpause with EHZ (6 FM music tracks playing, no SFX):** 7 iterations of
   `zResumeTrack`: for the DAC track, pan write `B6h = C0h` (`B4h + (6 & 3)`,
   part II since bit 2 of `VoiceControl = 6`), then voice 0 of `EHZ_Voices`
   uploaded to the ch-2 part-II register set — FM6's registers; then FM1-6's
   own pan + voice uploads. Then silence until each track's next note keys on.
3. **Pause → `MusID_Stop` → unpause:** the stop cannot run while paused. `FDh`
   lands in `QueueToPlay` (it is `< MusID_Pause`, so `sndDriverInput` queues it,
   `s2:1296-1302`) and sits there, because dispatch runs only on a V-int that
   begins with `StopMusic == 0` (§1.1 step 2) and `s2:1298` is the only 68k
   `StopMusic` writer. The frame after the unpause clears `StopMusic`,
   `zStopSoundAndMusic` wipes tracks with `zPaused` already 0. The stale-`zPaused`
   hazard S2M q11 asked about (`zClearTrackPlaybackMem` clears only
   `1B80h-1E37h`, leaving `zPaused`) is therefore unreachable through the
   documented 68k interface; `zStopSoundAndMusic`'s own `StopMusic = 0`
   (`sd:2545-2547`) covers the race where the 68k posts a pause *after* this
   V-int's `StopMusic` check — that pause request is eaten (§18 q10, resolved).

### 12.3 Engine today

Absent: presentation-level `SILENT` frames, no driver pause state (EM §1.4 Pause).
Adaptation (GA §1.2 #14): a driver-level pause flag with the S2 bursts — the
destructive silence sweep and the FM6-voice-0 resume quirk are ROM behaviours to
reproduce, not clean up.

---

## 13. 1-up save / restore and speed shoes

### 13.1 ROM behaviour

**Save** (`zPlayMusic` 1-up path, `sd:1674-1724`): if `1upPlaying` already set →
plain `zBGMLoad` restart (no re-save; repeated 1-ups are stable — CD OVR-09).
Else: `res 2` on all 10 music tracks, `res 7` on all 6 SFX tracks (SFX die
silently — their channels were already restored by the §5.1 step-1
`zStopSoundEffects` a few instructions earlier); copy `1B80h-1D3Bh` (`zVar` + 10
music tracks, `1BCh` bytes) to `zTracksSaveStart` (`1E38h-1FF3h`);
`1upPlaying = 80h`; **then**
`SFXPriorityVal = 0` — after the backup (FixBugs off `sd:1714-1722`), so the stale
latch is restored with the song (CD OVR-04's S2 half confirmed). Then `zBGMLoad`
loads the jingle (uncompressed, §5.1 — `zMusicData` still holds the saved song's
decompressed data, which is what makes the restored `DataPointer`s valid).
While `1upPlaying`: SFX requests are dropped with a latch reset (§6.1);
`zSpeedUpMusic`/`zSlowDownMusic` write `zSaveVar.CurrentTempo`/`SpeedUpFlag` — the
**backup** — but the tempo *value* still comes from the **live**
`TempoTurbo`/`TempoMod` (`sd:2686-2703`), i.e. the jingle's own tempi while it
plays; so a speed change during the jingle applies after restore, at the
jingle's tempo value (CD CAD-12/OVR-06-analogue; §13.2 v1).

**Restore** (`E4` at the jingle's end, `cfFadeInToPrevious`, `sd:3084-3163`): block
copy back over `1B80h-1D3Bh`; `zBankSwitchToMusic`; DAC track `set 2` ("muted
during fade-in" reuse of the override bit); `c = 28h - FadeInCounter`; FM playing
tracks: `set 1` (at rest), `Volume += c`, `zSetVoiceMusic(VoiceIndex)` (voice +
pan + attenuated TLs; **no frequency, no key**); PSG playing tracks: `set 1`,
`zPSGNoteOff`, `Volume += c` (noise **not** re-latched, FixBugs off
`sd:3136-3143`); `FadeInFlag = 80h`, `FadeInCounter = 28h`, `1upPlaying = 0`,
`2Bh = DACEnabled` (the *restored* song's value); pops three return addresses and
jumps to `zUpdateDAC` — the rest of that invocation is abandoned.
`zUpdateFadeIn` (`sd:2725-2789`) then steps every 3rd frame (delay 2 → 0):
`dec FadeInCounter`, FM `dec Volume` + `zSetChanVol`, PSG `dec Volume` +
`zPSGUpdateVol` with the raw volume (envelope ignored, FixBugs off
`sd:2771-2780`); at counter 0: DAC `res 2`, `FadeInFlag = 0` — **SFX unblock only
here** (CD OVR-05's S2 half: after the fade-in, not at restore). ~120 frames of
fade + the latch behaviour of §6.1 throughout.

**Abandonment** (CD OVR-02 for S2): any non-1-up music request simply clears
`1upPlaying` (`sd:1728-1731`); the save area is left stale, and the jingle's `E4`
is never reached because the jingle's tracks were wiped by the new load.

**Speed shoes** (outside 1-up): §3.1 — `CurrentTempo = TempoTurbo`/`TempoMod`,
`SpeedUpFlag = 80h/0` (`sd:2686-2711`); `SpeedUpFlag` survives song loads (§2.2)
and is cleared by fade-out (§13.2/§14 of S2M; `sd:2433-2435`). 68k senders:
pickup `s2:25946-25947`, expiry `s2:36325`, `s2:39054`.

### 13.2 Test vectors

1. **1-up during EHZ with speed shoes:** save carries `CurrentTempo = BEh`,
   `SpeedUpFlag = 80h`; jingle plays at its own `CDh` (header, §3.4 v2 — the S2
   jingle is *not* tempo-boosted: `zBGMLoad` reads `SpeedUpFlag`, which
   `zInitMusicPlayback` preserved… note: the jingle therefore loads at
   `zSpedUpTempoTable[17h] = CDh` turbo — equal to its normal `CDh`, so audibly
   identical). Speed-shoes expiry during the jingle reads the **live** `TempoMod`
   — the *jingle's* `CDh`, not EHZ's `9Eh` (`sd:2697-2703`) — and writes
   `zSaveVar.CurrentTempo = CDh`, `zSaveVar.SpeedUpFlag = 0`; after `E4` the
   level music resumes at tempo `CDh` (*faster* than its normal `9Eh`: stall
   rate 51/256 vs 98/256) until the next song load or tempo command.
2. **Stale priority:** ring SFX (`70h`) playing when the 1-up starts → backup holds
   `SFXPriorityVal = 70h` (zeroed only after the copy); after `E4`, until some SFX
   ends, requests with priority `< 70h` (e.g. `DAh` gloop, `60h`) are rejected even
   though no SFX is playing (CD OVR-04).
3. **Restore burst:** EHZ, all 6 FM + 3 PSG playing, restore at
   `FadeInCounter = 0` → `c = 28h`: per FM track voice upload with
   `Volume + 28h` in the masked TLs; PSG tracks `VoiceControl|1Fh` writes + volume
   offsets; `2Bh = 80h`; no `A4/A0/28h` writes. 40 fade steps × 3 frames restore
   volumes to nominal, then `FadeInFlag = 0`.
4. **1-up then invincibility before `E4`:** the invincibility load clears
   `1upPlaying`; when it ends, the game-side request for the level music replays it
   from scratch — the driver's save is orphaned (the DEF-10 "stack vs one slot"
   shape: the ROM has exactly one save slot and only `E4` consumes it; CD OVR-01
   for S2 confirmed by the single `zTracksSaveStart` block, `sd:215-227`).

### 13.3 Engine today

Parked second live `SmpsDriver` in `AudioVoiceRegistry`'s override stack
(EM §1.4, §3.2). Adaptation (GA §1.2 #15): keep the parked-driver model but make
the restore emit the burst above (attenuated voice re-uploads, at-rest, no
frequency) and start the S2 fade-in shape (steps 28h, delay 2, envelope-ignoring
PSG writes); route speed-up during a 1-up to the parked driver
(`zSaveVar` semantics). The stale-latch restore (vector 2) becomes reproducible
once §4's global latch exists.

---

## 14. Request transforms (ring, gloop, spindash, SEGA)

### 14.1 Ring L/R (`sd:2124-2135`)

Every 68k caller sends `SndID_Ring (B5h)` (e.g. `s2:25040-25082`). Driver: if
`zRingSpeaker == 0` → substitute `SndID_RingLeft (CEh)`; then `cpl` the loaded
value and store (0 ↔ FFh). So requests alternate CE (left), B5 (right), CE, …
The two SFX differ only in data (`B5 - Ring.asm` pans right, `CE - Ring Left
Speaker.asm` left). **No reset site exists** (§2.2): the alternation phase persists
across song loads, stop-all, everything — unlike S3K's claimed load-time reset
(CD REQ-01: the S1/S2 "equivalents" row is answered for S2 — the mechanism exists,
the reset does not). Engine: `AudioManager.ringLeft` + `ResetRingAlternation`
command (EM §1.4) — the reset command has no S2 ROM counterpart; driver-RAM
residence per GA §1.2 #19 with **no** reset for the S2 profile.

### 14.2 Gloop (`sd:2138-2149`)

`SndID_Gloop (DAh)`: toggle `zGloopFlag` (`cpl`); play only when it becomes `FFh` —
every other request, first request after boot plays (0 → FFh). No reset site.
Engine: toggle lives in `BlueBallsObjectInstance.gloopToggle` (documented
intentional placement, KD "Gloop Sound Toggle"; CD REQ-02/DEF-07) — the ROM
behaviour is driver-side; the spec records it so the divergence stays chosen.

### 14.3 Spindash rev (`sd:2152-2176`, `2297-2308`)

`SndID_SpindashRev (E0h)`: if `zSpindashPlayingCounter != 0` →
`index = zSpindashExtraFrequencyIndex + 1` else 0; stored only while `< 0Ch`
(caps at `0Bh`); `zSpindashPlayingCounter = 3Ch` (decremented once per invocation,
`sd:432-436`); `zSpindashActiveFlag = FFh`. In `zPlaySound`, when the active flag
is set the stored index is **added to the header transpose** of every channel of
the SFX (`sd:2297-2308`) — rev header transpose is `FEh`
(`E0 - Spin Dash Rev.asm:7`), so successive revs within 60 frames of each other
play at `FEh, FFh, 00h … 09h`: a +0…+11-semitone ladder (CD REQ-03 confirmed:
driver-owned, `3Ch`-frame timeout, saturating 0-11). Any other SFX request clears
only `zSpindashActiveFlag` (`sd:2122`), not the index; the ladder restarts at 0
once the counter has expired before the next rev.

**Spindash release** (`BCh`) is a different SFX: its FM5 header transpose ships as
`90h` (`BC - Spin Dash Release.asm:7-12`, FixMusicAndSFXDataBugs off) — the
"fixed" value is `10h`. The shipped driver uses `90h` verbatim: index
`= (note - 80h) + 90h`, an 8-bit wrap into a large positive offset with the §7
page-wrap rule. Engine: `Sonic2SfxData.java:118-125` patches `90h → 10h`
(documented divergence, KD "Spindash Release Transpose Fix"; CD REQ-04/DEF-05 —
CD's note that `CHANGELOG.0.6.md:3778-3780` claims the opposite landed is
consistent with what the engine source shows today: the patch is present).
Engine spindash ladder: **not modelled for S2** — `Sonic2AudioProfile` overrides no
`adjustSfxPitch` (the default `GameAudioProfile.adjustSfxPitch` is identity; only
`Sonic3kAudioProfile` overrides it). Divergence; adaptation per GA §1.2 #19
(driver state: counter + index + active flag).

### 14.4 SEGA chant (`zPlaySegaSound`, `sd:1603-1664`)

Command `FAh`. `2Bh = 80h` (DAC on; **no panning reset** — FixBugs off
`sd:1606-1611`, the chant plays with whatever `B6h` panning is current);
`bankswitch Snd_Sega`; stream `Snd_Sega.size/2` byte-pairs to `2Ah` data port with
a `djnz` of `pcmLoopCounter(Snd_Sega.sample_rate)` per byte (152 cycles per 2 bytes
at `b = 1`, `sd:1626-1652`); abort after the *first* byte of a pair when `QueueToPlay != 80h`
(`sd:1636-1638` — a new request stops the chant, possibly on an odd byte); then
bank back and
`2Bh = DACEnabled`. Runs **inside `zVInt` with interrupts implicitly consumed** —
music, SFX and the frame loop freeze for the chant's duration. Engine: `SegaPcmSpec`
+ host-linear sample with lifecycle events (EM; CD CHIP-08 divergence recorded);
adaptation GA §1.2 #20 (render through the DAC path at the Z80 loop period).

### 14.5 Test vectors

1. **Ring alternation from boot:** requests B5, B5, B5 → played CE, B5, CE
   (`zRingSpeaker` starts 0). A song load between requests does **not** reset the
   phase.
2. **Gloop:** requests DA ×4 from boot → played on 1st and 3rd (flag 0→FF play,
   FF→0 drop, …).
3. **Spindash ladder:** rev at frames 0, 30, 60, … (Δ < 60): transposes
   `FEh, FFh, 00h, …` — rev *N* plays `FEh + (N−1)`; the 12th rev and beyond hold
   `09h` (`FEh + 0Bh`, the index stops being stored at `0Ch`). A rev after
   a >60-frame gap restarts at `FEh`.
4. **SEGA abort:** chant playing, 68k queues any id → `QueueToPlay != 80h` at the
   next pair boundary → chant stops, `2Bh = DACEnabled`, and the queued id
   dispatches next invocation.

---

## 15. DAC / DPCM

### 15.1 ROM behaviour

The DAC track parses like other tracks (`zDACUpdateTrack`, `sd:759-815`): a note
byte (`81h-91h`) is stored in `SavedDAC`; on the frame the duration expires and
unless bit 2, `zDACMasterPlaylist[(SavedDAC-81h)·2]` (`sd:3895-3926`) yields
`(sample id 81h-87h, rate byte)`; `zCurDAC = sample id`, rate →
`zDACStoreDelay+1` (self-modified). A **bare duration byte re-triggers the stored
`SavedDAC`** (`sd:774-783`); `SavedDAC = 80h` is a rest. Playback: `zUpdateDAC`
(§1.1 step 6) patches pointer/length from `zDACPtrTbl/zDACLenTbl` (`sd:3879-3887`,
7 samples: Kick, Snare, Clap, Scratch, Timpani, Tom, Bongo) and enters
`zWriteToDAC` (`sd:680-728`): per byte, two nibbles through `zDACDecodeTbl`
(`sd:732-733`, 16 deltas `0,1,2,4,8,10h,20h,40h,80h,FFh,FEh,FCh,F8h,F0h,E0h,C0h`)
accumulated into shadow `a'` (start `80h`, `sd:506-507`) and written to `2Ah` —
**295 cycles for two samples at `b = 1`** (cycle counts annotated per instruction,
sum at `sd:727-728`), plus `13·(b-1)` per sample from the `djnz`. The formula
`dpcmLoopCounter(rate) = 1 + (Z80_Clock/rate - 147 + 6)/13` (`sd:311-314`,
`Z80_Clock = 53693175/15`, `const:2139-2143`) converts a sample rate to the rate
byte. CD DAC-02 **confirmed: 295**, not 288. A new drum replaces `hl/de`
mid-sample (old sample cut); every V-int forces one early sample (`ld b,1`,
`sd:499`); `zCurDAC` keeps the started sample's index and is never cleared at
sample end (S2M §3.1). While a music song uses 7 FM+DAC tracks, `DACEnabled = 0`
and drum triggers still parse but `2Bh = 0` mutes them at the chip.

### 15.2 Test vectors

1. **Kick trigger:** DAC track note `81h`, duration `04h`: on expiry frame,
   `zCurDAC = 81h`; next `zUpdateDAC` starts the stream with
   `b = dpcmLoopCounter(SndDAC_Kick.sample_rate)` — *derived* 23 for the 8250 Hz
   `.wav` (the `generated/*.inc` files carrying `.sample_rate` are not in the tree;
   §18 q1): effective rate `Z80_Clock / ((295 + 26·22)/2) ≈ 8257 Hz` —
   self-consistent with the formula.
2. **Re-trigger by bare duration:** DAC data `81h 04h 04h` → the second `04h`
   re-fires the kick with no new note byte.
3. **Drum cut:** Snare (`82h`, rate byte 1 → ~24 kHz *derived*) triggered while the
   kick streams → kick cut at the next V-int's sample start.
4. **Pause residue:** §12.2 vector 1.

### 15.3 Engine today

The Z80 loop is modelled inside `Ym2612Chip` from `DacData.baseCycles`, which is
**288** for S2 (`DacData.java:10-13`) — divergence, KD §31/CD DAC-02/DEF-08;
adaptation GA §1.2 #21 / deferred item 11: 295 from this listing. Intra-frame
perturbations (forced early sample, `stopZ80` holds) are hardware timing outside
the RAM/write oracle (GA §3, bus-hold tier).

---

## 16. FixBugs sites a shipped run reaches

The full inventory is S2M §19 and is confirmed against the source read for this
spec. The subset with an *observable* effect on ordinary play, each already
embedded above: `zPlayMusic` stops SFX every song (§5.1); `TempoWait`-first (§3.1);
`82h` rest-init (§5.1); SFX-pass `ix` inheritance (§1.1); `zVolEnvHold` freeze
(§8); `zPSGNoteOff` leaves noise (§7); fade PSG raw-volume writes (§13.1, §17.2
FADE); `zInitMusicPlayback` destructive silence + `Queue2` loss (§5.1, §2.2);
1-up stale latch (§13.1); `E0` store-skip under override (§6.4); `ED` operand skip
(§11); TL bit-7 pass-through (§10.1); Sega PCM panning (§14.4); 4-slot 68k queue
copy (§1.1); spindash-release `90h` transpose (§14.3, data-side). The engine must
take the shipped branch at every one of these; §14.3's `90h` patch is currently
the one *chosen* exception (documented in KD).

## 17. ROM-read data tables and claim coverage

### 17.1 Tables the engine holds as Java copies (verify once, then read from ROM)

| Table | ROM location | Engine copy |
|---|---|---|
| `zSFXPriority` (81 bytes) | driver blob, `sd:3716-3722` | `Sonic2SmpsConstants.SFX_PRIORITY_TABLE` |
| `zSpedUpTempoTable` (31 bytes) | `sd:3859-3867` | `Sonic2SmpsSequencerConfig.SPEED_UP_TEMPOS` |
| `zPSG_EnvTbl` + envelopes | `sd:3725-3806` | `Sonic2PsgEnvelopes` |
| `zFrequencies` / `zPSGFrequencies` | `sd:1384-1409` / `sd:1053-1075` (formulas; assembler output not dumped — §18 q1) | `SmpsSequencer.FNUM_TABLE_*` |
| `zMasterPlaylist` + `music_ptr` banks | `sd:3823-3855`, `s2:91486-91489` | hardcoded REV01 offset map (`Sonic2SmpsLoader`) |
| `zDACMasterPlaylist` / `zDACPtrTbl` | `sd:3879-3926` (rate bytes build-generated — §18 q1) | `DacData` mapping |

All are inside the Saxman-compressed driver blob or the 68k banks; reading them
from the user ROM at load time is rule-1-compatible and is deferred implementation
item 13 of GA §4.3.

### 17.2 CD claim disposition (every S2-relevant row)

| CD row | Section | Verdict |
|---|---|---|
| CAD-01 | §5.1 | derived — anchor refined to `zBGMLoad` `sd:1857`/`1970`, `sd:1820-1822` |
| CAD-02 | §3.1/§3.3 | derived **with corrections**: S2 extends on *no-carry*, and on all 10 slots regardless of playing bit |
| CAD-03 (S2 half) | §3.1 | derived (accumulator preserved on `EA`/speed change) |
| CAD-06 | §1.1 | derived — "every fifth VInt" **correct**; S2M §5.2's "sixth" and the `sd:448` comment are wrong |
| CAD-08 | §1.1 | derived (no multiplier; engine's 1.2 is the divergence) |
| CAD-10 (S2 half) | §1.1 | derived (music before SFX) |
| CAD-12 (S2 half) | §3.1, §5.3 v3, §13 | derived |
| ADM-01 (S2 half) | §4.1 | derived (incl. bit-7 no-store, reject-outright) |
| ADM-03 (S2) | §6.2 | derived (request-time claim) |
| ADM-04 (S2) | §6.3 | derived (at-rest until next note; noise re-latch the one exception) |
| ADM-05 | §6.2 | derived (no takeover writes; release *does* upload voice/pan/TL) |
| ADM-07 (S2) | §5.1 | derived |
| ADM-08 | §§4, 6 | covered by constituents |
| REQ-01 (S2 row) | §14.1 | derived — alternation exists; **no reset site** (differs from the S3K-shaped claim) |
| REQ-02 | §14.2 | derived (driver-side in ROM; engine placement documented divergence) |
| REQ-03 (S2 half) | §14.3 | derived |
| REQ-04 | §14.3 | derived (`90h` shipped; engine patches; CL row stale as CD already notes) |
| REQ-06 | §5.1, §17.1 | derived (playlist mechanism specified; engine divergence stands) |
| OVR-01/02 (S2 by analogy) | §13.1/§13.2 v4 | derived (one slot; abandonment = flag clear, not wipe) |
| OVR-03 (S2) | §13.1, §6.1 | derived |
| OVR-04 (S2) | §13.1, §13.2 v2 | derived |
| OVR-05 (S2) | §13.1 | derived (SFX unblock at fade-in end) |
| OVR-09 (S2) | §13.1 | derived (restart path) |
| FADE-03 (S2) | §13.1/S2M §14 | derived (`sd:2433-2435`) |
| FADE-04 (S2) | fade §: `zUpdateFadeout` decrements then jumps to `zClearTrackPlaybackMem` **before** the volume loops (`sd:2450-2452`) | derived |
| FADE-05 (S2 half) | `sd:2423-2436`: delay 3, counter 28h → one step per 4 frames, 40 steps; SFX **not** blocked during fade-out | derived |
| PAUSE-02 | §12.1 | derived |
| PAUSE-04 (S2) | §12.1 | derived |
| PAUSE-05 (S2) | §12.1 | derived |
| VOICE-01 (S2 half) | §10 | derived |
| VOICE-05 (driver half, S2) | §10.2 | derived (busy poll before both strobes) |
| SEQ-05 (S2) | §§7-9 | derived |
| DAC-01 (S2) | §15.1 | derived |
| DAC-02 | §15.1 | derived — **295**; engine 288 divergence |
| DAC-06 | §15, §12 | covered |
| DATA-01 | §5.1 | derived (LE size; exactly four uncompressed: 98, 9B, 9D, 9E) |
| DATA-02 | loader policy | not a driver-spec item (GA §3) |
| DEF-05/06/07/08 | §14.3, §5.4, §14.2, §15.3 | ROM behaviour stated; divergences remain chosen |
| CD §15 rows (S2) | — | `zSFXPriorityVal` → `zVar.SFXPriorityVal` field (`sd:143`); `zSpeedupTimeout` — no S2 counterpart (speed-up is a tempo swap, §3.1); `zPauseUnpause` → `zPauseMusic` (`sd:1422`); `zFadeInToPrevious`/`zDoMusicFadeOut` → `cfFadeInToPrevious` (`sd:3084`) / `zUpdateFadeout` (`sd:2442`); `.dac_playback_loop` → `zWriteToDAC` (`sd:680`); PAL "counter" → `zPALUpdTick` (`sd:4087`) + `.pal_timer` (`sd:438`); ring-speaker equivalent → §14.1 (answered) |

## 18. Open questions

Carried from S2M §20 (renumbered) plus new ones from this spec; none resolved from
memory:

1. `zDACMasterPlaylist` rate bytes, `zDACPtrTbl` lengths, and the exact
   `zFrequencies`/`zPSGFrequencies` words: build-generated (`generated/*.inc`
   absent; assembler rounding not dumped). The §7/§15 vector values are hand
   evaluations of the source formulas — confirm against the ROM bytes once a
   dump path exists. (S2M q1, q2.)
2. `cfPanningAMSFMS` uses `bit 7,(ix+d); ret m` (`sd:3018-3021`): whether Z80 `BIT`
   sets S from the tested bit is a hardware question; the source intends "return
   for PSG". (S2M q4.)
3. Music id `80h` reaching `zBGMLoad` with index −1 (reads the byte before
   `zMasterPlaylist`); no 68k caller found sending it, unexhaustively. (S2M q5.)
4. `TempoDivider = 0` → ×256 via `djnz` wrap; whether any shipped header or
   `E5`/`EB` operand is 0 was not swept. (S2M q7.)
5. `zInitSFX` writes PSG byte `1Fh` for uninitialised PSG tracks; the audible
   effect depends on the PSG's last latched register. (S2M q6.)
6. YM2612 handling of a TL byte with bit 7 set (§10.1) — hardware question; the
   oracle observes the effect. (S2M q10.)
7. Frequency-table page-wrap reads (§7.1) and flag bytes `FAh-FFh` (§11): no
   shipped S2 stream was exhaustively swept for reliance on either.
8. Gosub-stack / loop-counter collisions (`sd:130-137`): whether any shipped song
   nests deep enough for the stack to overwrite `LoopCounters[≥4]` — decides
   whether the engine's separate arrays are a `derived` re-pack or a real gap
   (GA §1.2 #24).
9. `zResumeTrack` writing voice 0 into FM6 through the DAC track while `2Bh` may
   be `80h` — audible effect is a YM2612 DAC-priority question. (S2M q9.)
10. *(resolved by this review)* `MusID_Stop` while paused cannot dispatch:
    every paused V-int skips `zUpdateEverything` (`StopMusic != 0`, `sd:403-407`)
    and `s2:1298` is the only 68k `StopMusic` writer, so the id waits in
    `QueueToPlay` until the unpause (§12.2 v3). No stale-`zPaused` state is
    reachable through `sndDriverInput`. (Was S2M q11.)
11. DAC intra-frame timing perturbations (forced early sample, `stopZ80`/DMA
    holds, interrupt latency): not derivable from the source; oracle-only tier.
    (S2M q3; hard rule 3 — never a spec constant.)
12. The engine-side question EM open q 6 (`noteOnPrevent` dead knob) does not
    involve S2 (`Sonic2SmpsSequencerConfig` leaves it defaulted) — noted for
    completeness, owner unchanged.

## 19. Review record (adversarial pass, 2026-08-30)

Sources-closed re-derivation against `docs/s2disasm/s2.sounddriver.asm`, `s2.asm`,
`s2.constants.asm`, `sound/_smps2asm_inc.asm` and the data under `docs/s2disasm/sound/`
only. Over half the test vectors were recomputed from scratch (all of §1.4, §3.4,
§4.4, §14.5; §6.4 v1/v4; §7.2 v2-v4; §8.2 v1; §9.2 v1/v3; §10.3 v1-v4; §12.2 v1-v3;
§13.2 v1-v3; §15.2 v1/v3), and every routine/line anchor spot-checked resolved to the
claimed code. Verdicts: **refuted** = the original claim was wrong and is now fixed
in place; **weakened** = right substance, wrong detail or misleading wording, fixed;
**stands** = attacked and survived.

| # | Claim (as originally written) | Verdict | Evidence |
|---|---|---|---|
| 1 | §6.4 v1: ring voice bytes `37 72 77 49 / 07 0A 07 0D / 00 0B 00 0B / 1F 0F 1F 0F / TL 23 80 23 80`, TL writes `23/80/28/85` | **refuted, fixed** | The values were copied from the byte *comment* above `Sound_Ring_Voices` (`C5 - Tally End.asm:53-56`), which lists ops in `4,3,2,1` order. The assembled bytes come from the `smpsVc*` macros, whose `SonicDriverVer == 2` branch emits ops in `4,2,3,1` order with algorithm-derived bit 7 on slot TLs (`inc:953-958`; `SourceSMPS2ASM = 0` per `smpsHeaderStartSong` default). Emission for alg 4, TLs `00/23/00/23`, masks on ops 1 and 3, gives `37 77 72 49 / 07 07 0A 0D / 00 00 0B 0B / 1F 1F 0F 0F / TL 23 23 80 80` → TL writes `23/23/85/85`. Cross-check: only this order puts the `80h`-flagged TLs in the positions `zVolTLMaskTbl[4] = 0Ch` marks as volume slots. |
| 2 | §1.1: 68k boundary "if `QueueToPlay == 80h` take `Music0` else `Music1`"; 4th SFX slot "harmless because `SoundQueue.SFX2` is never written" | **refuted, fixed** | `s2:1273-1284`: `QueueToPlay != 80h` skips *both* music slots (`bne.s .doSFX`); `Music1` is the fallback when `Music0` is empty. The `d1 = 3` iteration reads `SoundQueue.SFX0 + 3` = `Music1` (struct order Music0, SFX0, SFX1, SFX2, Music1, `const:1879-1885`), not `SFX2` (that is `d1 = 2`); a pending `Music1` (written by `PlayMusic`'s overflow, `s2:1526`) can be stolen into `VoiceTblPtr`'s low byte and cleared. |
| 3 | §13.2 v1: speed-shoes expiry during the 1-up jingle writes `zSaveVar.CurrentTempo` = EHZ's `TempoMod = 9Eh`; music resumes at normal tempo | **refuted, fixed** | `sd:2697-2703` (shipped branch): `zSlowDownMusic` loads `a` from the **live** `zAbsVar.TempoMod` — during the jingle that is ExtraLife's `CDh` — and only the *store* is redirected to `zSaveVar`. After `E4` the level music runs at `CDh` until the next load/tempo event. §13.1 amended to say value-from-live, store-to-backup. |
| 4 | §12.2 v3: "`FDh` while paused clears `StopMusic` and wipes tracks but leaves `zPaused = FFh`" (open question §18 q10) | **refuted, fixed; q10 resolved** | Dispatch is gated: a paused V-int takes `zPauseMusic → zUpdateDAC` (`sd:403-407`) and never runs `zPlaySoundByIndex`; `s2:1298` is the sole 68k `StopMusic` writer, so `StopMusic` stays `7Fh` while `zPaused = FFh`. The `FDh` waits in `QueueToPlay` until unpause. `zStopSoundAndMusic`'s `StopMusic = 0` covers the mid-V-int race (pause posted after the check is eaten). |
| 5 | §13.1: 1-up save/restore copies "`1B80h-1F3Bh`" | **refuted (arithmetic), fixed** | `zTracksSaveEnd - zTracksSaveStart = 1BCh`; source `1B80h-1D3Bh`, destination `1E38h-1FF3h` (`sd:215-227`, `1704-1709`, `3087-3090`). `1F3Bh` fits neither range. |
| 6 | §5.2: song-load burst ends with "`zInitSFX`'s … PSG `9F BF DF FF` again" | **refuted, fixed** | `zInitSFX`'s PSG loop is 3 × `zPSGNoteOff` on the music PSG tracks (`sd:2069-2074`) → `9F BF DF`; the noise `FFh` in `zPSGNoteOff` is FixBugs-only code (`sd:1369-1379`). |
| 7 | §10.3: voice upload is a "27-write sequence" | **refuted (count), fixed** | 1 (`B0h+ch`) + 4 (DT/MUL) + 16 + 1 (`B4h+ch`) + 4 (TL) = 26 (`sd:3305-3431`). |
| 8 | §14.5 v3: "an 11th rev and beyond hold `09h`" | **refuted (off-by-one), fixed** | Rev *N* plays `FEh + (N−1)` (`sd:2159-2175`, `2297-2308`); the cap `0Bh` is first *played* on the 12th rev. |
| 9 | §7.1: FM/PSG frequency writers "both return without writing when bit 1 or bit 2 is set" | **weakened, fixed** | `zFMUpdateFreq` tests only bit 2 (`sd:1090`); bit-1 rests are filtered upstream (`zFMPrepareNote` `sd:1080-1081`, `zDoModulation` `sd:989-990`). `zPSGUpdateFreq` genuinely tests `and 6` (`sd:1210-1212`). Observable behaviour as originally stated; the routine attribution was wrong. |
| 10 | §9.1: modulation "reload steps from `ptr+3`" (halving implied to apply throughout) | **weakened, fixed** | `srl a` exists only at arm time (`sd:3494-3495`); the mid-note reload stores `zz` raw (`sd:1011-1015`). First leg `zz/2` steps, later legs `zz` — §9.2 v1/v3 updated with the 50-vs-101 signature. |
| 11 | §5.1: `zFMSilenceAll` key-off "`28h = 02,01,00` then `06,05,04` (part I write then `set 2,c` part II write)" | **weakened, fixed** | Actual sequence interleaves `02,06, 01,05, 00,04` (`sd:2519-2528`), and both writes go through `zWriteFMI` — `28h` is a part-I register; `set 2,c` retargets the *channel bits in the data byte*. (§5.2 already listed the right order.) |
| 12 | §14.4: SEGA chant "abort between pairs" | **weakened, fixed** | The `QueueToPlay` check sits between the two bytes of a pair (`sd:1636-1638`); the chant can stop on an odd byte. |
| 13 | §1.1 cadence: PAL double update every 5th V-int; `sd:448` comment and S2M §5.2 wrong | **stands** | Recount from `sd:441-452` + reload 5 at `sd:1823-1824`: decrements 4,3,2,1,0 → fires every 5th; 6 music updates per 5 frames = 1.2×. |
| 14 | §3: `TempoWait` no-carry stalls; increment hits all 10 slots; runs before first track update; `EA`/speed keep phase | **stands** | `sd:596-619` (`ret c`, `MUSIC_TRACK_COUNT` loop with no playing test), `sd:545-551`, `sd:3207-3209`, `sd:2707-2711`. §3.4 v1/v2/v3 accumulator sequences recomputed byte-exact (carry pattern, `13Ch/DAh/178h/116h/B4h`; `19Ah…CEh`; turbo `BEh` = `zSpedUpTempoTable[1]`, `sd:3860`). |
| 15 | §4: one dequeue per invocation; reject-outright; Jump `80h` never stored; priority table values | **stands** | `sd:1496-1550` re-traced; all 81 `zSFXPriority` bytes re-read (`sd:3717-3722`) and every §4.1 exception value re-verified (A0=80, AA=68, BF=7F, DB=62, ED=71, F0=6F, …). §4.4 v1-v4 recomputed. |
| 16 | §5: load order, silence burst, FM6/DAC decision, `82h` init, `DurationTimeout = 1`, Queue2 loss, four uncompressed songs | **stands** | `sd:1738-2076`, `2580-2654` re-traced; `music/list of compressed songs.txt` lists exactly the other 27 (98/9B/9D/9E absent); EHZ header values confirmed (`82 - EHZ.asm:4-14`). |
| 17 | §6: admission-time claim, no takeover writes, restore = voice+pan+TL at-rest with no frequency, PSG unconditional `res 2`, pan-lost-under-override | **stands** | `sd:2178-2331` (only chip write in `zPlaySound` is the PSG3 `DF/FF` pair), `sd:3514-3599`, `sd:3022-3029`. Ring lifecycle (§6.4 v1) recomputed end-to-end: note bytes `nE5/nG5/nC6 = C1h/C4h/C9h` (`inc:31-45`), frequency words `2B2Dh/2BC5h/3284h` re-derived from the `sd:1384-1409` formula with `const:2141-2143` clocks. |
| 18 | §7/§8: write cadences, PSG envelope `80h` freeze, tie gate, noise-not-silenced | **stands** | `sd:968-979`, `1265-1349`, `1357-1380`; §7.2 v2 (Jump: `140h → 80h,14h`; `EFh → 8Fh,0Eh`) and §8.2 v1 (Env3 `94h…9Bh` hold) recomputed; Jump header transpose `F4h`, volume 0 confirmed (`A0 - Jump.asm:7`). |
| 19 | §12: pause destructive sweep, unpause = pan + full voice reload incl. DAC-track voice-0-to-FM6 quirk, nothing else restored | **stands** | `sd:1422-1491` re-traced; `zResumeTrack` register math recomputed (`B6h`, ch-2 part II). |
| 20 | §15: DAC 295 cycles/2 samples, `dpcmLoopCounter` formula, forced early sample, re-trigger by bare duration | **stands** | `sd:311-314`, `499`, `680-728`, `759-815`; §15.2 v1 recomputed (`dpcmLoopCounter(8250) = 23`, effective ≈ 8257 Hz), v3 (`b = 1` → ≈ 24.3 kHz). |
| 21 | Engine-today statements (§1.5, §3.5, §5.4, §14.3, §15.3) | **stands** | Spot-checked in-tree: `SmpsSequencer.java:1282-1291` OVERFLOW2 skip-tick branch, `DacData.java:10-16` baseCycles 288, `Sonic2SfxData.java:116-121` `90h→10h` patch, `adjustSfxPitch` overridden only by `Sonic3kAudioProfile`, `Sonic2SmpsSequencerConfig:60-61` OVERFLOW2/S2_Z80. |
| 22 | Data-side anchors (headers, transposes, pan) | **stands** | `smpsHeaderTempo` at `82 - EHZ.asm:5` / `98 - Extra Life.asm:5`; spindash rev transpose `FEh` (`E0 - Spin Dash Rev.asm:7`); release `90h` shipped (`BC - Spin Dash Release.asm:11`); `B5` pans right / `CE` pans left (`B5 - Ring.asm:11`, `CE - Ring Left Speaker.asm:12`); 68k senders `s2:25040/25042/25082` (ring), `25946`, `36325`, `39054` (tempo). One citation corrected: the rev tie is at `E0 - Spin Dash Rev.asm:13-15`, not `:11-14`. |
