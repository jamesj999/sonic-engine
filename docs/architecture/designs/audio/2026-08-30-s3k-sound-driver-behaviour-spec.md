# S3K sound driver behaviour spec

**Date:** 2026-08-30
**Branch:** `feature/ai-sdre-spec-s3k` (from `feature/ai-sound-driver-re`, with
`feature/ai-sdre-gaps`, `feature/ai-sdre-refute-structural-fit` and
`feature/ai-sdre-refute-oracle-plan` merged)
**Kind:** design (per-game driver behaviour spec, S3K lane of the sound-driver RE workflow)
**Inputs:**

| Key | Document |
|---|---|
| MAP | `docs/architecture/research/audio/2026-08-30-s3k-sound-driver-routine-map.md` |
| GAP | `docs/architecture/designs/audio/2026-08-30-sound-driver-re-gap-analysis.md` |
| CD | `docs/architecture/audits/audio/2026-08-30-smps-behaviour-claims-digest.md` |

**Source rule.** Sources-closed. The sole behavioural source is the disassembly under
`docs/skdisasm/` — primarily `docs/skdisasm/Sound/Z80 Sound Driver.asm` (5315 lines),
plus the music/SFX data under `docs/skdisasm/Sound/`, `sonic3k.asm`,
`sonic3k.constants.asm`, `sonic3k.macros.asm` and `Sound/_smps2asm_inc.asm`. No
SMPSPlay, libvgm, GPGX sound code, the reverted `feature/ai-smps-transaction-parity`
branch, or third-party SMPS write-ups were opened. Engine source was read only to state
what the engine does today. Every anchor below was re-verified against the tree for this
spec (labels greppped, code re-read), not copied blind from the MAP. Where the
disassembly is ambiguous the item is in [section 18](#18-open-questions), not resolved
from memory.

**Anchors.** `D:NNN` = `docs/skdisasm/Sound/Z80 Sound Driver.asm:NNN`.
`K:NNN` = `docs/skdisasm/sonic3k.asm`, `KC:NNN` = `sonic3k.constants.asm`,
`KM:NNN` = `sonic3k.macros.asm`, `INC:NNN` = `docs/skdisasm/Sound/_smps2asm_inc.asm`.
Z80 addresses are driver-image addresses (image runs from `0000h`). Byte values use the
driver's `NNh` style. All behaviour is the shipped `fix_sndbugs = 0` build (`D:16`) of
the **S&K image** (`SonicDriverVer = 4`), the only image the locked-on cartridge ever
installs (MAP §1.1; `K:412-418`).

**Engine classes referenced** (for the (e) items): `SmpsSequencer`, `SmpsSequencerConfig`
(`com.openggf.audio.smps`), `SmpsDriver`, `SmpsRequestAdmissionPolicy`,
`PreparedSfxAdmission` (`com.openggf.audio.driver`), `Sonic3kAudioProfile`,
`Sonic3kSmpsSequencerConfig`, `Sonic3kSmpsConstants` (`com.openggf.game.sonic3k.audio`),
`Sonic3kCoordFlagHandler` (`...sonic3k.audio.smps`), `AudioManager`,
`AbstractSmpsAudioBackend` (`com.openggf.audio`), `AudioPresentationProducer`,
`OuterFramePresentation` (`com.openggf.audio.presentation`), `Ym2612Chip`, `DacData`
(`com.openggf.audio.synth`, `com.openggf.audio.smps`).

Sections follow the GAP §4.1 lane-plan order. Each subsystem gives (a) the ROM
behaviour with anchors, (b) driver state read/written, (c) the observable YM2612/PSG
write stream and/or driver-RAM effect at the invocation boundary, (d) hand-derived test
vectors, (e) the engine model and whether it is an adaptation point or a divergence.

---

## 1. Invocation boundary and cadence

### (a) ROM behaviour

One driver invocation is one execution of `zVInt` (`D:470-521`), entered via the Z80
`rst 38h` interrupt once per vertical blank. Between interrupts the Z80 sits in the DAC
loop `zPlayDigitalAudio` (`D:4258-4355`), which touches no track RAM. Inside one
invocation:

1. `di`, save registers; shipped build stores the refresh register to `unk_1C17`
   (`D:477-480`, write-only).
2. `.doupdate`: `call zUpdateEverything` (`D:653-655`) =
   `zPauseUnpause` (`D:2232`) → `zUpdateSFXTracks` (`D:727`) → fall through to
   `zUpdateMusic` (`D:658`). **SFX pass strictly before the music pass** (CD CAD-09).
3. PAL double update (`D:482-499`): if `zPalFlag` is set, test `zPalDblUpdCounter`
   *before* decrementing; when it is 0, reload it — **5 in the S&K image**
   (`D:488-493`; the value 6 is the `SonicDriverVer==3` branch only) — and jump back to
   `.doupdate` for a complete second `zUpdateEverything`; otherwise decrement. So PAL
   runs 7 full updates per 6 frames (derivation in TV 1.1).
4. Bank-switch to `DAC_Banks[zDACIndex & 7Fh]` (`D:508-515`; table `D:630-648`, dummy
   entry 0 keeps an idle DAC valid).
5. Restore registers, `ld b,1`, `ret` — `b = 1` makes the interrupted `djnz $` in the
   DAC loop fall through immediately (`D:519-520`, §15).

**Speed-up tail** (`D:743-758`): the code after `zTrackUpdLoop`'s `djnz` is shared by
the SFX pass and *every* music pass. If `zTempoSpeedup != 0`: when `zSpeedupTimeout`
is 0 it is reloaded from `zTempoSpeedup` and the tail does `jp zUpdateMusic` — an
extra, complete music update (tempo, fades, queue, tracks), whose own tail then
decrements the freshly reloaded timeout; otherwise the timeout is decremented. Because
the tail runs at least twice per frame (once after SFX, once after music), the value 8
produces one extra music update every fourth frame — 5 music updates per 4 frames
(derivation in TV 1.2). `zStopAllSound` zeroes `zTempoSpeedup` (`D:2472-2473`), so the
68k must resend it after any ordinary song load; the 1-up path saves/zeroes/restores it
(§13).

**Frames that do less than a full update:**

- Pause transition frame (`zPauseFlag = 1`) and every paused frame: `zPauseUnpause`
  pops the return address, so **no SFX pass, no music pass, no tempo, no fades, no
  queue processing at all** (`D:2238-2241`, §12).
- SEGA-PCM frames: `zPlaySEGAPCM` runs with interrupts disabled — `zVInt` does not run
  at all for the duration of the chant (`D:4372-4373`, §14.4/§15).
- There is no S1-style H-int second call and no song-load-frame skip in S3K; the 68k
  interface is mailbox-only (§4.1).

Boot (`D:523-551`): SP = `2000h`, a brief busy-wait (one 256-iteration
`djnz` pass — `dec c` takes `c` 0→FFh so the `jr z, .loop` falls through at once), `zStopAllSound`, `zSongBank` =
bank of `Snd_Bank2_Start`, zero `zSpindashRev`/`zDACIndex`/`PlaySegaPCMFlag`/
`zRingSpeaker`, `zPalDblUpdCounter` = 5, `ei`, `jp zPlayDigitalAudio`.

### (b) State

Reads `zPalFlag` (1C02), `zPalDblUpdCounter` (1C04), `zDACIndex` (1C30),
`zTempoSpeedup` (1C08), `zSpeedupTimeout` (1C2F), `zPauseFlag` (1C10). Writes
`unk_1C17`, `zPalDblUpdCounter`, `zSpeedupTimeout`, the bank register.

### (c) Observable effect

The invocation boundary defines one oracle tick: all YM/PSG writes of a frame happen
inside `zVInt`, in the order SFX pass → music pass (→ extra music passes under
speed-up). A newly requested SFX is *initialised* during the music phase (queue is read
there) and emits its first note writes on the **next** frame's SFX pass, because its
`DurationTimeout` is initialised to 1 and the SFX pass for this frame has already run
(`D:2187`, §3.3-order). A newly loaded song is initialised and then walked by the same
frame's music loop, so its first notes sound on the frame of the request.

### (d) Test vectors

**TV 1.1 — PAL double update (S&K image).** Boot sets counter 5. Per frame
(counter value at entry → updates run): F1 5→4 (1), F2 4→3 (1), F3 3→2 (1),
F4 2→1 (1), F5 1→0 (1), F6 0 → reload 5, second update, then the re-check decrements
5→4 (2 updates). Cycle = 6 frames, 7 updates. Expected RAM: `zPalDblUpdCounter`
sequence at frame end = 4,3,2,1,0,4,3,2,1,0,4,… Expected writes: on every 6th frame
the whole write stream of an update appears twice.

**TV 1.2 — speed shoes, `zTempoSpeedup = 8`.** After the 68k writes 8
(`Change_Music_Tempo`, `K` — `stopZ80; move.b d0,zTempoSpeedup; startZ80`), with
`zSpeedupTimeout = 0`: F1: SFX-pass tail sees 0 → reload 8, extra music update (its
tail: 8→7), normal music pass (tail: 7→6). F2: 6→5, 5→4. F3: 4→3, 3→2. F4: 2→1,
1→0. F5: 0 → extra update again. Expected: music updates per frame = 2,1,1,1,2,1,1,1,…
(5 per 4 frames); `zSpeedupTimeout` at frame end = 6,4,2,0,6,4,2,0,…
This falsifies both "extra update every 8 frames" and "every 9 frames" models.

**TV 1.3 — pause transition frame.** 68k writes `zPauseFlag = 1` (`K` `Pause_Main`).
Next `zVInt`: the only YM/PSG writes are the §12 pause burst; no track's
`DurationTimeout` changes, `zTempoAccumulator` unchanged, queue mailboxes unread.
Every following paused frame: zero writes, zero RAM deltas except `unk_1C17`.

**TV 1.4 — extra music update also advances fades.** During an `E1` fade
(§11) with speed shoes 8 active: fade steps advance once per music update, so the
40-step × delay-6 fade completes in 240 *music updates* = 192 frames (240 × 4/5), not
240 frames.

### (e) Engine today

`SmpsSequencer.advanceBatch` runs `processTempoFrame` whenever a sample-domain counter
crosses 1/60 s, phase-free of the outer frame (GAP §1.2 #1 — **adapt, high risk**; the
frame-locked boundary is GAP deferred item 1). The speed-up tail is modelled as
`speedupTimeout` decremented **once per frame** with an extra tick when it hits 0
(`SmpsSequencer.processTempoFrame`, comment "one extra zUpdateMusic every (N+1)
frames") — one extra update every 9 frames at 8 instead of every 4 (GAP #16 —
**divergence**, deferred item 3; the `zDoSpeedUp` label that comment cites does not
exist, see §18). PAL is `multiplier = 1.2` (`SmpsSequencer.calculateTempo`, ~line 824)
— **divergence** (GAP #17; no ROM driver has a tempo multiplier, CD CAD-08). Pause is
the presentation-side `SILENT` mode (`OuterFramePresentation.modeFor`), not a driver
flag (§12e). `Sonic3kAudioProfile.getSpeedMode() = FRAME_MULTIPLY`,
`getSpeedMultiplierValue() = 8` carries the right ROM value into the wrong cadence.

---

## 2. Driver RAM and track struct

### (a)(b) ROM layout

Global variables (`zDataStart = 1C00h`, `D:110-173`) and track RAM exactly as MAP
§1.3-1.4 — re-verified against `D:110-218`. Summary of the compared vocabulary:

| Addr | Symbol | Notes |
|---|---|---|
| 1C02 | `zPalFlag` | 68k writes 1 on PAL at `SndDrvInit` (`K` block after Kosinski copy) |
| 1C04 | `zPalDblUpdCounter` | §1 |
| 1C05-07 | `zSoundQueue0..2` | §4 |
| 1C08 | `zTempoSpeedup` | §1 |
| 1C09 | `zNextSound` | §4 |
| 1C0A-0C | `zMusicNumber`, `zSFXNumber0/1` | 68k mailboxes (§4) |
| 1C0D-0F | `zFadeOutTimeout`, `zFadeDelay`, `zFadeDelayTimeout` | §11 |
| 1C10 | `zPauseFlag` | §12 |
| 1C11 | `zHaltFlag` | §10 `FF 02` |
| 1C12 | `zFM3Settings` | written, never read |
| 1C13 | `zTempoAccumulator` | §3 |
| 1C15,17,18,21 | `unk_*` | written, never read (`1C21` S3 image only) |
| 1C16 | `zFadeToPrevFlag` | §13 |
| 1C19 | `zUpdatingSFX` | 1 during SFX pass |
| 1C24 | `zCurrentTempo` | §3 |
| 1C25-26 | `zContinuousSFX`, `zContinuousSFXFlag` | §14.2 |
| 1C27 | `zSpindashRev` | §14.3 |
| 1C28 | `zRingSpeaker` | §14.1 |
| 1C29 | `zFadeInTimeout` | §11 |
| 1C2A-2E | `zVoiceTblPtrSave`, `zCurrentTempoSave`, `zSongBankSave`, `zTempoSpeedupSave` | 1-up saves (§13) |
| 1C2F | `zSpeedupTimeout` | §1 |
| 1C30 | `zDACIndex` | bit 7 = playing; bits 0-6 = 1-based sample id |
| 1C31 | `zContSFXLoopCnt` | §14.2 |
| 1C32 | `zSFXSaveIndex` | §6 |
| 1C33-3B | `zSongPosition`, `zTrackInitPos`, `zVoiceTblPtr`, `zSFXVoiceTblPtr`, `zSFXTempoDivider` | load-time cursors |
| 1C3E | `zSongBank` | §5 |
| 1C3F | `PlaySegaPCMFlag` | §14.4 |
| 1C40 | `zTracksStart` | 9 music slots × 30h: FM6/DAC, FM1-5, PSG1-3 (`D:176-186`) |
| 1DF0 | `zTracksSFXStart` **= `zTracksSaveStart`** | 7 SFX slots (FM3-6, PSG1-3) overlapping the 9-slot 1-up save area (`D:190-212`) |
| 1F40 | `zTracksSFXEnd` | |
| 1FA0 | `zTracksSaveEnd` = `z80_stack_end` | |

`zTrack` structure (30h bytes, `D:21-96`): as MAP §2, re-verified. Load-bearing
details: `PlaybackControl` bit 0 = PSG-noise / FM3-special, bit 1 = do-not-attack,
bit 2 = SFX-overriding, bit 3 = alt-frequency mode, bit 4 = resting, bit 6 = sustain
frequency (set only by mod-envelope `81h`/`83h` and **never cleared in the shipped
build** — the clearing `res 6` in `zKeyOnOff` is `fix_sndbugs`-only, `D:1170-1172`),
bit 7 = playing. `StackPointer` init 30h; `LoopCounters` at 28h-29h may overflow into
`Voices` (2Ah-2Bh) and `Stack_top` (2Ch-2Fh). `Voices` is read only when
`zUpdatingSFX = 1` (`D:1461-1467`).

`zStopAllSound` (`D:2460-2470`) zero-fills from `zTempVariablesStart` (1C0Dh) for
`(1FA0h−1C0Dh−1)+34h` bytes — i.e. 1C0Dh through 1FD3h inclusive, 34h bytes past
`z80_stack_end` into the stack region. Everything from `zFadeOutTimeout` to
`PlaySegaPCMFlag` plus all 16 track slots is wiped; the mailboxes (1C0A-1C0C) and
queue (1C05-1C07) are **not** in the wiped range.

### (c) Observable effect

This table is the RAM-comparison vocabulary for the phase-B oracle (GAP §2 item 2:
poll `1C00-1FA0` from the BizHawk `Z80 RAM` domain once per frame).

### (d) Test vectors

**TV 2.1 — post-load track image (MHZ1 FM1).** After `Play_Music(0Fh)` is dispatched
(§5), `zSongFM1` (1C70h) holds: `PlaybackControl = 80h`, `VoiceControl = 00h`,
`TempoDivider = 01h` (header byte 4), `DataPointer` = `zmake68kPtr(Snd_MHZ1_FM1)`,
`Transpose = 00h`, `Volume = 0Dh` (header), `ModulationCtrl = 00h`,
`VoiceIndex = 00h`, `StackPointer = 30h`, `AMSFMSPan = C0h`, `DurationTimeout = 01h`,
bytes 0Ch-2Fh = 0 (`zInitFMDACTrack`/`zZeroFillTrackRAM`, `D:2171-2198`).
Header fields from `Music/MHZ1.asm`: `smpsHeaderChan $06,$03`,
`smpsHeaderTempo $01,$39`, FM1 pitch `$00` vol `$0D`.

**TV 2.2 — stop-all wipe extent.** Issue `cmd_Stop` (`E2h`): every byte 1C0Dh-1FD3h
reads 0; `zSoundQueue0-2` and the three mailboxes are unchanged by the wipe itself
(they were cleared earlier by `zFillSoundQueue`, §4). `zRingSpeaker`, `zSpindashRev`,
`zContinuousSFX`, `zFadeToPrevFlag`, `zTempoSpeedup` all read 0.

### (e) Engine today

Track state lives in `SmpsSequencer.Track` (`pos`, `keyOffset`, `volumeOffset`,
`duration`, `fill`, `detune`, `pan/ams/fms`, `loopCounters[]`, `returnStack[]`,
`modPending*`, `modEnv*`, `envPos`, `noiseMode`, `ssgEg[]`, `active`, `overridden`…);
driver globals partly in `SmpsDriver` (`continuousSfxId/Flag`, `contSfxLoopCnt`) and
partly outside the driver (`AudioManager.ringLeft`,
`SmpsCoordFlagRuntimeState.spindashRevCounter`, presentation pause). Snapshots exist
(`SmpsDriverSnapshot`/`SmpsTrackSnapshot`); the per-field
compared/derived/engine-only/not-compared registry of GAP #23 is the deliverable that
consumes this section. Recommended `not-compared`: `Unk11h`, `zFM3Settings`,
`unk_1C15/17/18/21`, offsets 12h-16h. Recommended `derived`: `DataPointer`
(= `pos` + Z80 base), `FeedbackAlgo`, `TLPtr`, `ModulationPtr` (= pos of the `F0`
params).

---

## 3. Main tempo and durations

### (a) ROM behaviour

`TempoWait` (`D:2607-2621`), called at the **top of every music update** (`D:659`):
`zTempoAccumulator += zCurrentTempo`; **on 8-bit carry** it increments the
`DurationTimeout` byte of all nine music slots (no playing-bit test — it increments
raw RAM of empty slots too), delaying every note by one frame. **The track walk always
runs regardless** — envelopes, note fill, modulation, and the per-frame frequency/
volume writes of §7 all continue on a "delay" frame; only note expiry is pushed out
(CD CAD-02). Tempo `T` therefore delays `T/256` of updates; `T = 0` never delays.
Both `zTempoAccumulator` and `zCurrentTempo` are seeded from song-header byte 5 at
load (`D:1829-1831`), so the first carry position is deterministic per song.
`cfSetTempo` (`FF 00`, `D:3861-3863`) replaces `zCurrentTempo` only — the accumulator
is **not** reset (CD CAD-03, S3K side).

Per-track `TempoDivider` (header byte 4 / SFX header byte 2): `zComputeNoteDuration`
(`D:1082-1091`) multiplies every duration byte by it via repeated addition — 8-bit,
overflow ignored; divider 0 iterates 256 times (result: duration × 256 mod 256 = 0 —
a 0 duration underflows the timer to FFh on first decrement, §18 q9 notes the S2
analogue). Duration timing: `zTrackRunTimer` (`D:1102-1107`) decrements
`DurationTimeout`; expiry (zero) parses new data. SFX tracks are unaffected by
`TempoWait` (it touches only the nine music slots).

### (b) State

Reads/writes `zTempoAccumulator` (1C13), `zCurrentTempo` (1C24), each music
`DurationTimeout` (+0Bh), `TempoDivider` (+02h), `SavedDuration` (+0Ch).

### (c) Observable effect

A delay frame is invisible in the write stream **except** through what still runs:
PSG frequency+volume and FM frequency writes still happen every running frame (§7),
envelope indices still advance, note fill still counts down. In RAM, a delay frame
shows `DurationTimeout` unchanged (pre-increment then decrement) while `VolEnv`,
`ModulationVal*`, `NoteFillTimeout` advance.

### (d) Test vectors

**TV 3.1 — 1UP song, tempo `20h`.** `Music/1UP (Sonic & Knuckles).asm` header:
`smpsHeaderTempo $01,$20`. Accumulator starts at 20h; update n (1-based) holds
`(20h·(n+1)) mod 256`, carry exactly when `20h·(n+1)` crosses 256 → first carry on
update 7 (20h·8 = 256, accumulator becomes 0), then every 8th update. Expected:
`zTempoAccumulator` after updates 1..8 = 40h,60h,80h,A0h,C0h,E0h,00h(carry),20h; on
update 7 all nine `DurationTimeout` bytes are one higher than the pure decrement
model predicts.

**TV 3.2 — MHZ1, tempo `39h`.** Accumulator after update n = `39h·(n+1) mod 256`;
carries land on updates 4, 8, 13, 17, 22 … (whenever `57·(n+1)` crosses a multiple
of 256). First-error probe for any cadence bug: compare `DurationTimeout` of
`zSongFM1` frame-by-frame from load.

**TV 3.3 — divider multiply.** A duration byte `18h` on a track with
`TempoDivider = 2` → `DurationTimeout = 30h` (`zComputeNoteDuration` loop). Byte
`60h` × divider 3 → `120h` truncates to `20h` (8-bit overflow, shipped).

**TV 3.4 — title screen, tempo `00h` (CD CAD-04).** `Music/Title (Sonic &
Knuckles).asm` header `smpsHeaderTempo $01,$00` (emitted verbatim for a
SourceDriver-3 song, `INC:190-192`). Accumulator stays 0 forever, no carry ever
occurs, notes are never delayed, and the track loop serves the song on the very first
update after load. "Zero tempo" is the *fastest* setting, not a freeze.

### (e) Engine today

`SmpsSequencerConfig.TempoMode.OVERFLOW` (selected by `Sonic3kSmpsSequencerConfig`):
on accumulator overflow the engine **skips the whole tick** — no envelope, modulation,
note-fill or §7 write runs on a delay frame (`SmpsSequencer.processTempoFrame`).
**Divergence, high risk** (GAP §1.2 #2): every S3K song with tempo > 0 diverges from
the ROM's write stream on its first carry frame. The fix shape (pre-increment
durations, always tick) is GAP deferred item 2; it must not land before the phase-B
comparison is red on exactly these fields. SFX bypass (tick every frame) matches the
ROM. `tempoOnFirstTick(true)` and accumulator seeding match `D:1829-1831`.

---

## 4. Queue, dispatch, admission

### 4.1 68k interface (a)

| Routine | Behaviour |
|---|---|
| `Play_Music` (`K`, label `Play_Music`) | `stopZ80; zMusicNumber = d0; startZ80` — unconditional overwrite |
| `Play_SFX` (`K`, label `Play_SFX`) | if `d0 == zSFXNumber0` **drop the request**; else if `zSFXNumber0 == 0` store there; else store in `zSFXNumber1` (overwriting whatever was there) |
| `Play_SFX_Local` | dead S2 leftover |
| `Play_SFX_Continuous` (`K:180655-180660`) | forwards to `Play_SFX` only when `V_int_run_count & 0Fh == 0` (the source's `(V_int_run_count+3).w` addresses the longword counter's low byte — a byte offset, not an addend) — at most once per 16 frames |
| `Change_Music_Tempo` | `zTempoSpeedup = d0` |
| `Pause_Main` / `Pause_ResumeMusic` / `Pause_FrameAdvance` (`K`) | `zPauseFlag` = 1 / 80h / 80h |

Apart from `Play_SFX`'s read of its own `zSFXNumber0` mailbox for the same-id
drop, the 68k never reads driver state; every access brackets the Z80 with
`stopZ80`/`startZ80`. Any ID may go to either mailbox (the 68k uses `Play_Music` for
`cmd_*` values too).

### 4.2 Queue and dispatch (a)

Inside `zUpdateMusic`, after the 1-up gate (§13): if any of the three mailboxes is
non-zero (`D:689-697`), `zFillSoundQueue` (`D:2628-2643`) copies
`zMusicNumber→zSoundQueue0`, `zSFXNumber0→zSoundQueue1`, `zSFXNumber1→zSoundQueue2`
and zeroes the mailboxes; then `zCycleSoundQueue` (`D:1619-1629`) runs **three times**
(`D:699-701`): pop slot 0 into `zNextSound`, shift down, clear slot 2, fall into
`zPlaySoundByIndex` with the popped value. Order per frame: music, SFX 0, SFX 1. A
zero entry reaches `zPlayMusic`, which returns immediately (`sub mus__First; ret m`,
`D:1718-1719`). **There is no priority gate anywhere** — `zID_PriorityList` (`D:239`)
is a vestigial pointer-table slot; a later request always takes the channel
(CD ADM-02).

`zPlaySoundByIndex` (`D:1641-1663`), S&K dispatch order (IDs from `KC:1421-1668`):

| Test | IDs | Target |
|---|---|---|
| `== mus_CreditsK` | `DCh` | `zPlayMusicCredits` (`D:1709-1712`): raw index 32h → 51st music entry `Snd_SKCredits` |
| `== cmd_SEGA` | `FFh` | `zPlaySegaSound` (§14.4) |
| `< mus__End` | `01h-32h` | `zPlayMusic` (§5, §13) |
| `< sfx__End` | `33h-DFh` | `zPlaySound_CheckRing` (§6; pointer table `D:4614-4668` has 173 entries, the last four aliasing `Sound_DB`) |
| `< cmd__First` | `E0h` | `zStopAllSound` |
| `< cmd__End` | `E1h-E5h` | `zFadeEffects` (`D:1667-1672`): E1 `zFadeOutMusic`, E2 `zStopAllSound`, E3 `zPSGSilenceAll`, E4 `zStopSFX`, E5 `zFadeOutMusic`; shipped zeroes `unk_1C18` first (`D:1659-1662`) |
| otherwise | `E6h-FEh` | `zStopAllSound` — this is what makes the `cmd_StopSEGA` (`FEh`) residue a stop-all (§14.4) |

### (b) State

Mailboxes 1C0A-1C0C, queue 1C05-1C07, `zNextSound` 1C09.

### (c) Observable effect

At most three requests are *dispatched* per music update, in mailbox order, during the
music phase; the queue bytes and `zNextSound` are directly comparable RAM. The same-id
mailbox drop happens 68k-side, so a spammed identical SFX id reaches the driver at
most once per driver-side acceptance window (until the driver clears the mailbox).

### (d) Test vectors

**TV 4.1 — music + SFX same frame.** 68k: `Play_Music(0Fh)` then `Play_SFX(62h)`
in one frame. Next music update: `zFillSoundQueue` yields queue (0Fh, 62h, 00h);
cycle 1 loads MHZ1 (§5), cycle 2 loads the jump SFX (§6), cycle 3 is a no-op. RAM
after: mailboxes 0, queue 0, `zNextSound` 0 (`zClearNextSound` at each load's end).

**TV 4.2 — same-id drop.** `Play_SFX(62h)` on frame N and again on frame N+0 (before
the driver's next update): second call sees `zSFXNumber0 == 62h` → dropped. After the
driver consumes the mailbox (they are zeroed in the same update that dispatches),
a *third* call on frame N+1 stores normally. Expected: exactly one jump-SFX
initialisation, then a second one a frame later.

**TV 4.3 — two different SFX, one frame.** `Play_SFX(A)` then `Play_SFX(B)`:
`zSFXNumber0 = A`, `zSFXNumber1 = B`; both are dispatched in the same music update,
A first. If they claim the same channel, B's `zPlaySound` re-initialises the same
SFX slot — B wins (no priority).

**TV 4.4 — `Play_SFX_Continuous` cadence.** With `V_int_run_count = v`, the forward
happens only when `v & 0Fh == 0` — i.e. on frames where `v ≡ 0 (mod 16)` (the
68k's `+3` is the byte address of the longword's LSB, not an addend).
A continuous SFX (§14.2) therefore gets at most one extension per 16 frames.

### (e) Engine today

All pending `AudioPresentationCommand`s are drained in submission order at the outer
frame boundary; there are no queue bytes, no three-per-update cycle, and no 68k-side
same-id drop (GAP §1.2 #4 — **adapt**, deferred item 4: give `SmpsDriver` the
ROM-shaped mailbox/queue). `SmpsRequestAdmissionPolicy.PERMISSIVE` is the S3K policy —
correct in that S3K has no priority latch; the per-channel takeover model
(`commitSfxAdmission`, `sfxClaimOwner`) supplies the "later request wins" outcome.

---

## 5. Music load and silence bursts

### (a) ROM behaviour

Ordinary song (`zPlayMusic` → `zPlayMusic_DoFade`, `D:1786-1788`): **`zStopAllSound`
first** — which wipes all SFX slots too, so S3K stops every SFX on an ordinary BGM
load (CD ADM-07) — then `zBGMLoad` (`D:1790-1882`):

1. Song bank byte from `z80_MusicBanks[index]` (`D:2841-2864`) via self-modifying
   code (`D:1803-1805`, shipped); stored to `zSongBank`; bank switched.
2. Direct write `B6h = C0h` on part II (FM6/DAC pan L+R, `D:1814-1818`) — bypasses
   `zWriteFMIorII`.
3. `hl = z80_MusicPointers[index]` (`D:4585-4608`; via master table id 4,
   `D:4450-4463`). Header: `+0..1` voice table → `zVoiceTblPtr` (the UVB-using songs
   store `z80_UniVoiceBank`); `+2` FM+DAC count; `+3` PSG count; `+4` tempo divider;
   `+5` main tempo → accumulator **and** `zCurrentTempo`; then 4 bytes per FM/DAC
   track (pointer lo/hi, transpose, volume), then 6 bytes per PSG track (pointer,
   transpose, volume, `ModulationCtrl`, `VoiceIndex`).
4. Per track: `PlaybackControl = 80h` and `VoiceControl` from `zFMDACInitBytes`
   (`D:1899-1907`: pairs `80h,06h / 80h,00h / 80h,01h / 80h,02h / 80h,04h / 80h,05h`,
   plus a shipped-only seventh `80h,06h` used only if a header declares 7 FM tracks)
   or `zPSGInitBytes` (`D:1913-1916`: `80h/A0h/C0h`); `TempoDivider` = header byte 4;
   then `zInitFMDACTrack`/`zZeroFillTrackRAM` (`D:2171-2198`): `ModulationCtrl = 0`,
   `VoiceIndex = 0` (FM/DAC only), `StackPointer = 30h`, `AMSFMSPan = C0h`,
   `DurationTimeout = 1`, bytes 0Ch-2Fh zeroed.
5. `zClearNextSound`.

`zStopAllSound` write burst (`D:2460-2521`), in order: RAM wipe (§2); for each of the
six channel ids 6,0,1,2,4,5 (from `zFMDACInitBytes`, `ix` walks the table):
`zFMSilenceChannel` (`D:2656-2662`) = `zSetMaxRelRate` (`80h,84h,88h,8Ch = FFh` —
note the +4 register stride of `zFMOperatorWriteLoop`, `D:2690-2699`, *not* the
instrument-table order) + TL `40h,44h,48h,4Ch = 7Fh` + key-off `28h = id`; then
`zFMClearSSGEGOps` (`90h,94h,98h,9Ch = 0`, `D:2532-2535`). Channel ids with bit 2
(6,4,5) go to part II, others to part I (`zWriteFMIorII`, `D:562-569`). Then
`zPSGSilenceAll` (`9Fh,BFh,DFh,FFh`, `D:2587-2597`), `2Bh = 0` (DAC off),
`zFM3NormalMode` (`27h = 0`, and `zFM3Settings = 0` shipped, `D:2511-2520`).

Song data pointers are Z80 window addresses (`zmake68kPtr`, `D:353`) — a song and its
tracks must live in one 32 KiB bank; the S&K bank macros can address the full 4 MiB
including the S3-half AIZ-LBZ data (MAP §13).

### (b) State

Everything in §2; plus `zSongBank`, `zVoiceTblPtr`, `zSongPosition`, `zTrackInitPos`.

### (c) Observable effect

The complete ordered write list for a song load is: [stop-all burst as above] +
`B6h = C0h` + nothing else — no key-ons, no voice uploads. First musical writes appear
in the same update's track walk (every `DurationTimeout` is 1, so every track parses
its first data immediately after load — the DAC slot too, `D:717-719`).

### (d) Test vectors

**TV 5.1 — stop-all burst, exact sequence.** For `cmd_Stop`: part II
`80h/84h/88h/8Ch=FFh`, `40h..4Ch=7Fh`, part I `28h=06h`, part II `90h..9Ch=0` (channel
6 → register+2 offsets: e.g. `82h,86h,8Ah,8Eh=FFh`, `42h..=7Fh`, `92h..=0`); then the
same shape for ids 0,1,2 on part I (`80h..8Ch`, `+0/+1/+2` channel offset), ids 4,5 on
part II; then `9Fh,BFh,DFh,FFh` to the PSG; `2Bh=00h`; `27h=00h` on part I.
(Register = base + (id & 3); id & 4 selects part II with the 4 subtracted,
`D:562-596`.)

**TV 5.2 — MHZ1 load.** `Play_Music(0Fh)`: burst of TV 5.1, then `B6h=C0h`, then RAM
of TV 2.1 for all 9 slots (FM1-5 volumes 0Dh,0Fh,0Ah,0Ah,16h; PSG transposes E8h,
volumes 02h; DAC track pointer = `Snd_MHZ1_DAC`). First note writes occur in the same
update.

**TV 5.3 — AIZ1 uses the universal voice bank.** `Music/AIZ1.asm` header uses
`smpsHeaderVoiceUVB` → `zVoiceTblPtr = z80_UniVoiceBank` (`1300h`-region address,
little-endian per `INC` `smpsHeaderVoiceUVB`), so `EF` voice loads for AIZ1 read Z80
RAM, not the ROM window — no bank dependency.

### (e) Engine today

`stopAll` + sequencer construction exist; the exact burst shape has never been
compared (GAP §1.2 #7 — **adapt (verify)**, deferred item 8). The engine loads music
via `Sonic3kSmpsLoader` from ROM (rule-1 compliant). Note for the comparator: the
engine has no equivalent of the direct `B6h = C0h` write.

---

## 6. SFX load, ownership, override, restore

### (a) ROM behaviour

`zPlaySound_CheckRing` (`D:1919-1926`): index 0 (= `sfx_RingRight`, `33h`) **toggles
`zRingSpeaker` first and then plays index `zRingSpeaker`** — the toggled value. From
the reset state 0 the first ring request plays index 1 = `Sound_34` (Ring **Left**),
the second plays index 0 = `Sound_33` (Right), and so on (§14.1).

`zPlaySound_Bankswitch` (`D:1928-1957`): bank-switch to `SndBank`,
`zUpdatingSFX = 0`, then: `sfx_Spindash` (`ABh`) goes straight to `zPlaySound` (no
spindash-rev reset); indices `< BCh` (`sfx__FirstContinuous`, `KC:1633`) go through
`zPlaySound_Normal` (`D:1968-1972`: `zSpindashRev = 0`); indices `>= BCh` are
continuous (§14.2).

`zPlaySound` (`D:1975-2104`): SFX header (`INC:360-386`): `+0..1` voice pointer →
`zSFXVoiceTblPtr`; `+2` tempo divider; `+3` track count → `zContSFXLoopCnt`; then per
track 6 bytes: `80h`, channel id, pointer lo/hi, transpose, volume. Per track:

- `zGetSFXChannelPointers` (`D:2109-2163`): FM ids map `02h→zSFX_FM3`, `04h→FM4`,
  `05h→FM5`, `06h→FM6` (shipped `dec a` closes the FM3/FM4 gap, `D:2113-2117`);
  PSG ids `80h→PSG1`, `A0h→PSG2`, `C0h→PSG3`, `E0h→PSG3` (five `srl a` + 2). For a
  PSG id the shipped path first calls `zSilencePSGChannel` with a **stale `ix`**
  (whatever track the caller last used) and then unconditionally writes `FFh` (noise
  silence) to the PSG (`D:2131-2136`). Returns `ix` = SFX slot (`zSFXChannelData`,
  `D:2202-2213`), `hl` = overridden music slot (`zSFXOverriddenChannel`,
  `D:2215-2226`), `zSFXSaveIndex` = index.
- `set 2,(hl)` — the music slot is marked overridden (`D:2003`).
- If the SFX slot's previous occupant was FM3 (`VoiceControl == 02h`), `zFM3NormalMode`
  (`27h = 0`, `D:2030-2032`).
- Copy the 6 header bytes (tempo divider inserted as byte 2), `zInitFMDACTrack`,
  store `Voices = zSFXVoiceTblPtr`, `zKeyOffIfActive` (28h key-off, FM only),
  `zFMClearSSGEGOps` — called even for PSG tracks, harmless because `zWriteFMIorII`
  returns on `VoiceControl` bit 7 (`D:2094-2099`).
- The `zUpdatingSFX`-gated blocks at `D:2006-2025`, `D:2043-2066`, `D:2071-2090` are
  dead (flag was just cleared): the "special SFX" system of older drivers.

**While bit 2 is set on a music track:** all FM register writes are dropped
(`zWriteFMIorII` `D:565-566`, `zFMSendFreq` `D:816-817`, `zFMNoteOn` `D:1117-1123`
masks 06h, `zKeyOffIfActive` `D:1144-1146`), PSG writes are skipped
(`D:4081-4082`, `zRestTrack` `D:4222-4223`), DAC queuing is skipped (`D:2901-2903`).
The track's data pointer, durations, loops, envelopes and volume state keep advancing
normally — takeover is write-suppression, not suspension.

**SFX end / restore** — `cfStopTrack` (`F2`, `D:3443-3518`): clear bit 7,
`unk_1C15 = 1Fh` (shipped), `zKeyOffIfActive`, `zGetSFXChannelPointers`. On a music
track (`zUpdatingSFX = 0`) it exits via `zStopCleanExit` (`D:3514-3518`) — two extra
stack pops, whose consequence for a DAC-track `F2` is §18 q4. On an SFX track:
`ix` := the overridden music slot; clear its bit 2; then:

- PSG slot → `zStopPSGTrack` (`D:3521-3530`): if the *music* track is a noise channel
  (bit 0) and its `PSGNoise` has bit 7, resend that noise byte to the PSG. Nothing
  else — the PSG music track stays silent until its own next update.
- FM slot, playing → if FM3: `27h = 4Fh` (special) or `0Fh` (normal) per bit 0
  (`D:3472-3481`); then re-upload the music track's voice: negative `VoiceIndex` →
  `zSetVoiceUploadAlter` (§8); else bank-switch to `zSongBank`, `zGetFMInstrumentOffset`
  from `zVoiceTblPtr`, `zSendFMInstrument` (§8 write order), bank back to SFX
  (`D:3490-3503`); if `HaveSSGEGFlag` bit 7, `zSendSSGEGData`. **The music track's
  frequency is not resent** — it reappears at the track's own next update via the
  every-frame `zFMSendFreq` (§7), now that bit 2 is clear.

This restore happens **inside the SFX pass**, i.e. before the same frame's music pass
(CD ADM-04, CAD-09): voice restored and frequency flowing again within one invocation.

`zStopSFX` (`cmd_StopSFX = E4h`, `D:1675-1694`): for every playing SFX slot,
`zSilenceStopTrack` (`D:1701-1704`) pushes two dummy words and runs
`cfSilenceStopTrack` (`E3` handler, `D:3088-3095`) = `zFMSilenceChannel` **regardless
of track type** + `cfStopTrack`. For a PSG SFX slot, `zFMSilenceChannel`'s key-off
(`zKeyOnOff` with `c = VoiceControl` = `80h/A0h/C0h`, `D:2661-2662`) writes
`28h = 80h/A0h/C0h` — channel code 0 with operator-mask bits set: a **spurious
partial key-on of FM1**. This is shipped behaviour the engine must emit (GAP §1.3).
`cfStopTrack` then enters `zGetSFXChannelPointers` while `ix` still names the
stopped PSG SFX slot: `zSilencePSGChannel` writes `1Fh + VoiceControl`
(`9Fh/BFh/DFh`) and, when that stopped slot's playback bit 0 is set, `FFh`;
the surrounding FixBugs=0 path then writes an additional unconditional `FFh`
(`D:4230-4248,2131-2136`). Only after that does the matching music PSG track
optionally re-latch its signed retained raw noise operand. This E4 sequence is
separate from the similarly-shaped stale-`ix`/`FFh` sequence during SFX
*admission*.

### (b) State

SFX slots 1DF0-1F40, overridden music slots' bit 2, `zSFXVoiceTblPtr`,
`zSFXTempoDivider`, `zContSFXLoopCnt`, `zSFXSaveIndex`, `zUpdatingSFX`.

### (c) Observable effect

Admission (during the music phase): per track — [PSG only: stale-`ix` silence write +
`FFh`] + key-off `28h = channel` (FM only) + SSG-EG `90h..9Ch+off = 0` (FM only) +
possibly `27h = 0`. First note writes on the next frame's SFX pass (§1c). Normal
release (during the SFX pass): key-off + [FM: `27h` if FM3, then `B4h`, `B0h`, 20
operator bytes, 4 TL bytes for the music voice] + [PSG noise re-latch if applicable].
E4 uses the same stop machinery, but each active PSG SFX also emits its raw YM `28h`
hazard, `1Fh + current SFX VoiceControl`, conditional `FFh` from that stopped SFX
track, and the FixBugs=0 unconditional `FFh` before any matching music re-latch.

### (d) Test vectors

**TV 6.1 — jump SFX (`62h`) over MHZ1 PSG1.** `Sound_62` uses `cPSG1` (`80h`), one
track, divider 01h, volume 00h. Admission writes: `zSilencePSGChannel` with stale
`ix` (value depends on the previous dispatch — for the first SFX after an ordinary
music load the last `ix` user was `zStopAllSound`'s silence walk, which leaves `ix`
at `zFMDACInitBytes+0Ch`, so `1Fh + (ix+1)` = `25h` is positive and **no** stale
write is emitted; in general the write is `(1Fh + the stale slot's VoiceControl
byte)` only when that sum lands in `80h-FFh`), then `FFh`; music PSG1 slot bit 2 set.
RAM: `zSFX_PSG1` (1EB0h) = `80h, 80h, 01h, ptr(Sound_62_PSG1), 00h, 00h`,
`DurationTimeout = 1`. Next frame's SFX pass: `F5 0Dh` (volume envelope 0Dh),
then note `nF2` — see TV 7.1 for the PSG frequency writes.

**TV 6.2 — channel mapping.** cFM4 (`04h`): bit 2 set → `dec a` → 3 → −2 → index 1 →
`zSFX_FM4`/`zSongFM4`. cPSG3 (`C0h`): srl×5 → 6 → +2−2 → index 6 → `zSFX_PSG3`/
`zSongPSG3`. cNoise (`E0h`): index 7 → also PSG3 (alias row).

**TV 6.3 — FM restore burst.** Ring SFX (`Sound_34`, FM5) ends by `smpsStop` (`F2`)
while MHZ1 owns FM5 (voice `16h` at that moment): the SFX pass emits key-off
`28h = 05h`… wait — key-off for the *SFX track* uses `zKeyOffIfActive` with the SFX
slot's `VoiceControl = 05h` → `28h = 05h` (operators off, FM5); then music FM5 voice
upload on part II: `B5h = pan`, `B1h = B0-byte`, `31h,39h,35h,3Dh`, `51h…`, `61h…`,
`71h…`, `81h…` (each set in instrument order 1,3,2,4), then TL `41h,49h,45h,4Dh`
with bit-7 TL bytes getting `+Volume` masked to 7 bits (§8). No `A5h/A1h` frequency
write until the music pass reaches FM5's update.

**TV 6.4 — E4 PSG hazard.** With a PSG1 SFX active, issue `cmd_StopSFX` (`E4h`):
expected writes start `28h = 80h`, `9Fh`, `FFh` (or `FFh,FFh` when the stopped
SFX playback bit 0/noise state is set), then any eligible matching music-noise
re-latch. `28h = 80h` is the spurious FM1 partial key-on. An oracle asserting
"no `28h` write may carry high nibble bits during E4" would be wrong; the correct
assertion is that this write **is present**.

### (e) Engine today

`SmpsDriver.prepareNewSfxAdmission`/`commitSfxAdmission` + per-channel claims
(`fmLocks`/`psgLocks` semantics, `Track.overridden`) model ownership. The S3K
host-owned E4 policy captures a complete immutable seven-slot projection before
the first write, rejects incomplete/ambiguous/raw-state-unavailable projections,
and then emits the native active-slot walk: FM3..FM6, PSG1..PSG3;
`zFMSilenceChannel` including the PSG-to-YM `28h` hazard; the current-SFX PSG
mute/conditional-`FFh`/unconditional-`FFh` walk in `zGetSFXChannelPointers`;
`cfStopTrack`; and the conditional FM voice/SSG-EG or PSG-noise restore. It clears
the same SFX claims and music override bits only after the physical program commits,
without rebuilding the driver or resending frequency. This source-corrected E4
candidate remains under review; the standalone E3 path and PSG-SFX *admission*
stale-`ix`/`FFh` behaviour remain separate gaps. No authenticated reference request
exercises E4, so it does not itself move the service-128 producer-input limitation
or assert a new comparator MATCH.

---

## 7. Note parse, frequency, key on/off

### (a) ROM behaviour

**FM track update** (`zUpdateFMorPSGTrack`, `D:766-799`): timer expiry →
`zGetNextNote`, return if resting, `zPrepareModulation`, `zUpdateFreq`,
`zDoModulation`, `zFMSendFreq`, `zFMNoteOn`. Note still running → if resting return;
`zDoFMVolEnv`; note-fill countdown → key-off at zero (and that
update ends there — no frequency write on the expiry frame); otherwise
`zUpdateFreq`; **if bit 6
(sustain) return; else `zDoModulation` + `zFMSendFreq`** — i.e. the FM frequency
(`A4h` then `A0h` + channel offset, `D:822-831`) is written **every running frame**,
not only on changes, unless the track is resting, SFX-overridden, or sustained.

**PSG track update** (`zUpdatePSGTrack`, `D:4058-4135`): same timer; running path:
note fill → `zRestTrack`; `zUpdateFreq`; `zDoModulation`; return if overridden; write
`(l & 0Fh) | VoiceControl` then `((l & F0h) | h)` nibble-swapped (`rrca`×4)
(`D:4083-4096`) — **every running frame**; volume = `Volume` + envelope value
(`zDoVolEnv` when `VoiceIndex != 0`); return if resting; if the sum has bit 4, use
0Fh; `| VoiceControl + 10h`, `+20h` more when the track is a noise channel (volume to
the noise register while the tone still went to the latched tone register)
(`D:4109-4135`) — also every running frame.

**`zGetNextNote`** (`D:907-1069`): clears bits 1 and 4; bytes `>= E0h` → coordination
flag (§10 dispatcher, `D:2930-2945`); otherwise `zKeyOffIfActive` first
(`D:919-921`), then: bit 3 → `zAltFreqMode` (`D:994-1041`: literal hi/lo frequency;
non-zero gets sign-extended `Transpose` added; a third byte is stored in `Unk11h`
(shipped); fourth byte is the raw duration); `< 80h` → duration; `80h` → rest
(`zRestTrack`); `81h-DFh` → note index `n − 81h + Transpose` (8-bit).
PSG: `hl = zPSGFrequencies[idx]` (`D:2799-2815`, 84 words,
`min(3FFh, round(PSG_Sample_Rate / (2f)))`, `PSG_Sample_Rate = Z80_Clock/16`); an
index ≥ 84 reads past the table into `zFMFrequencies`/`z80_MusicBanks`
(deterministic overrun).
FM: octave loop (`D:939-963`): `block = idx / 12` by repeated subtraction — **no
clamp** (idx ≥ 96 or an 8-bit-wrapped negative spills block bits into bit 6+ of `h`);
`hl = zFMFrequencies[idx mod 12]` (`D:2825-2829`, 12 words 644…1216 =
`round(f · 2^21 / FM_Sample_Rate)`, `FM_Sample_Rate = M68K_Clock/144`);
`h |= block << 3`. Duration byte after a note: `< 80h` consume ×divider; else reuse
`SavedDuration` (shipped writes it into `DurationTimeout` inline, `D:976-979`).
`zFinishTrackUpdate` (`D:1056-1069`): save pointer, `DurationTimeout = SavedDuration`;
unless bit 1 (no attack): zero `ModEnvIndex`, `ModEnvSens`, `VolEnv`, reload
`NoteFillTimeout` from `NoteFillMaster`.

**Key on/off:** `zFMNoteOn` (`D:1113-1132`): skip when frequency is zero or bits 1/2
set (shipped masks `06h` — a resting track would key on, but callers return on bit 4
first); write `28h = F0h | channel`. `zKeyOff`/`zKeyOnOff` (`D:1156-1176`):
`28h = VoiceControl` (high nibble 0). `zUpdateFreq` (`D:1434-1453`):
`hl = Freq + sign-extended Detune`.

### (b) State

`FreqLow/High`, `Detune`, `Transpose`, `DurationTimeout`, `SavedDuration`,
`NoteFillTimeout/Master`, `PlaybackControl` bits 1/3/4/6.

### (c) Observable effect

Per running FM music/SFX track per update: `A4h+ch` then `A0h+ch` (part per bit 2).
Per running PSG track per update: two tone bytes + one volume byte. Key-on
`28h = F0h|ch` only on attacked note expiry; key-off `28h = ch` precedes every parsed
byte run and note-fill expiry.

### (d) Test vectors

**TV 7.1 — PSG note `nF2` (jump SFX).** `nF2` = note byte `A2h`? — derivation:
note enum starts `nC0 = 81h` (`INC:31-46`); F2 is index 29 (2 octaves + 5 semitones)
→ byte `81h+1Dh = 9Eh`. Transpose 0 → table index 29 → f = 349.56 Hz →
`round(223721.56 / 699.12)` = **320 = 140h** (`PSG_Sample_Rate` =
3579545/16 = 223721.56; `KC` clock constants). Writes with `VoiceControl = 80h`:
byte 1 = `(40h & 0Fh)... ` → low nibble of `l`=40h is 0h → `80h`; byte 2 =
`((l & F0h) | h)` = `41h` nibble-swapped = `14h`. Expected: `80h`, `14h`, then the
volume byte `90h + (Volume 0 + envelope value)` per `sTone_0D` = `VolEnv_0D`
(`D:4527`: 2, 83h — value 2, then rest+silence on the next update's read of `83h`).
First frame volume write = `92h`.

**TV 7.2 — FM note `nE5` (ring SFX first note).** `nE5` → index 64 (5·12+4) → byte
`C1h`. Block = 5, remainder 4 → `zFMFrequencies[4] = 813 = 32Dh`; `h = 03h | 28h =
2Bh`. FM5 (`VoiceControl = 05h`): `A4h+5 = A9h` → part II → `A5h = 2Bh`, then
`A1h = 2Dh`; key-on `28h = F5h`. While the 5-frame note runs, `A5h/A1h = 2Bh/2Dh`
repeat **every SFX-pass frame** (no modulation on this note).

**TV 7.3 — missing duration reuse.** `Sound_33`'s stream `nE5,$05, nG5,$05, nC6,$1B`:
each note carries a duration. Contrast MHZ1 FM1 `nBb5,nG5,nA5,…` (durations omitted):
each omitted duration reuses `SavedDuration` (= 04h × divider 1) via the shipped
inline copy (`D:976-979`).

**TV 7.4 — every-frame FM writes vs sustain.** Give any FM track mod-envelope
`F4 01` (ModEnv_00): after 11 envelope steps the `83h` byte sets bit 6 (§9), and the
`A4h/A0h` pair **stops appearing** for the rest of the track's life (bit 6 is never
cleared in the shipped build). Before that byte, the pair appears every frame with
the envelope-shifted value.

### (e) Engine today

Frequency tables are Java-resident (`SmpsSequencer` FM table 644…1216 at lines
231-235 — values match `D:2825-2829`; ROM-read replacement is GAP deferred item 13).
The engine writes FM frequency on change/modulation-step (`forceModulationWrite`,
`modStepChanged` flags) — whether it reproduces the S3K every-frame cadence is
**unverified and likely divergent** (GAP §1.2 #8, deferred item 9). Table overruns
(PSG idx ≥ 84, FM block spill) are not modelled — model as ROM-read tables with the
ROM's neighbouring bytes if a stream is ever found to reach them (none known).

---

## 8. Voice upload and volume model

### (a) ROM behaviour

Instrument = 25 bytes: `B0h` byte, then four operator bytes for each of
`30h/38h/34h/3Ch` (DT/MUL), `50h/58h/54h/5Ch` (RS/AR), `60h/68h/64h/6Ch` (AM/D1R),
`70h/78h/74h/7Ch` (D2R), `80h/88h/84h/8Ch` (D1L/RR) — register order tables at
`D:1484-1511` (operator order 1,3,2,4) — then 4 TL bytes for `40h/48h/44h/4Ch`
(`D:1513-1518`). `zSendFMInstrument` (`D:1531-1575`): `B4h = AMSFMSPan` first, then
`B0h` (saved to `FeedbackAlgo`), then the 20 operator bytes in one loop (shipped
ignores the SSG-EG max-attack-rate rule, `D:1561-1571`), save `TLPtr`, `zSendTL`.
All through `zWriteFMIorII` — dropped entirely when the track is SFX-overridden.

`zSendTL` (`D:3178-3209`): per TL byte — bit 7 set → `(TL + Volume) & 7Fh` (shipped
has no overflow clamp beyond the mask); bit 7 clear → raw TL. FM `Volume` is 0 =
loudest, 7Fh = quietest.

`cfSetVoice` (`EF`, `D:3345-3392`): FM → `zSetMaxRelRate` **first** (D1L/RR = FFh on
all four operators — an audible release-shortening before every voice change), then
`VoiceIndex = param`; negative param → second byte = `VoiceSongID`,
`zSetVoiceUploadAlter` (`D:3361-3373`: voice table of `z80_MusicPointers[id−81h]`
**without switching banks** — only correct if that song shares the current bank or
uses the UVB; no shipped stream uses the two-byte form); positive →
`zGetFMInstrumentPointer` (`D:1461-1479`: `zVoiceTblPtr` for music, the track's
`Voices` for SFX). PSG → `VoiceIndex = param` (`cfStoreNewVoice`), skipping the
second byte when negative.

Volume flags: `cfSetVolume` (`E4`, `D:3113-3131`): FM stores `(param ^ 7Fh) & 7Fh`
and calls `zSendTL` immediately; PSG stores `((param >> 3) ^ 0Fh) & 0Fh`.
`cfChangeVolume2` (`E5`, `D:3140-3146`): first param ignored, second falls into
`cfChangeVolume` (`E6`, `D:3156-3172`): **PSG returns immediately**; FM adds with
saturation (signed overflow — attenuation pushed past 7Fh — saturates to 7Fh, the
quietest; a plain negative result saturates to 0, the loudest) and falls into `zSendTL`.
`cfChangePSGVolume` (`EC`, `D:3273-3285`): PSG only; clear bit 4, `VolEnv--`
(unconditional 8-bit decrement — index 0 wraps to FFh), `Volume += param`,
values ≥ 0Fh become 0Fh. Pan `E0` (`D:3010-3024`):
`AMSFMSPan = (old & 3Fh) | param` — old AMS/FMS bits are kept and OR-combined with
whatever the parameter carries — written to `B4h` immediately (dropped when
overridden; re-sent on unpause §12).

FM volume envelope (`FF 06` + `zDoFMVolEnv`, `D:4033-4037`, `D:1190-1226`): for each
operator whose mask bit is set, write `(instrument TL byte + envelope value) & 7Fh` —
track `Volume` is **not** added on this path. No shipped stream uses `FF 06`.
SSG-EG (`FF 05`, `D:3972-4020`): write the 4 params to `90h/98h/94h/9Ch`, rewind
`de` by 1; unreached by shipped streams (MAP §10 usage counts).

### (b) State

`VoiceIndex`, `VoiceSongID`, `FeedbackAlgo`, `TLPtr`, `Volume`, `AMSFMSPan`,
`FMVolEnv`, `FMVolEnvMask`, `HaveSSGEGFlag`, `SSGEGPointer`.

### (c) Observable effect

`EF n` on an un-overridden FM track: `80h..8Ch(+off) = FFh` ×4, `B4h+off`,
`B0h+off`, 20 operator writes, 4 TL writes — 29 register writes in fixed order.

### (d) Test vectors

**TV 8.1 — ring voice upload (`Sound_33`, FM4).** Voice bytes (from
`Sound_33_34_B9_Voices` comment block): `B0=04h`; DT/MUL `37h,72h,77h,49h`;
RS/AR `1Fh×4`; AM/D1R `07h,0Ah,07h,0Dh`; D2R `00h,0Bh,00h,0Bh`; D1L/RR
`1Fh,0Fh,1Fh,0Fh`; TL `23h,80h,23h,80h`. FM4 → part II, register offset +0.
Expected sequence: `80h,84h,88h,8Ch=FFh`; `B4h=C0h`; `B0h=04h`; `30h=37h`,
`38h=72h`, `34h=77h`, `3Ch=49h`; `50h,58h,54h,5Ch=1Fh`; `60h=07h`, `68h=0Ah`,
`64h=07h`, `6Ch=0Dh`; `70h=00h`, `78h=0Bh`, `74h=00h`, `7Ch=0Bh`; `80h=1Fh`,
`88h=0Fh`, `84h=1Fh`, `8Ch=0Fh`; TL with `Volume = 05h`: `40h=23h` (bit 7 clear →
raw), `48h=(80h+05h)&7Fh=05h`, `44h=23h`, `4Ch=05h`. Then pan `E0 panRight`:
`B4h=40h` (old C0h & 3Fh = 0, OR 40h).

**TV 8.2 — E6 saturation.** FM track at `Volume = 7Dh`; `E6 05h` → 82h → sign flip
→ minimum: `Volume = 7Fh`, TL resent. `E6 F0h` (−16) from `Volume = 08h` → negative
→ maximum: `Volume = 0`. PSG track: `E6` is a no-op (returns before any state
change) — S3K-specific (the S2 driver placement differs, comment at `D:3156-3159`).

**TV 8.3 — EC envelope re-step.** PSG track with `VolEnv = 3`, `Volume = 2`:
`EC 04h` → `VolEnv = 2` (decremented so the same envelope step re-applies),
`Volume = 6`, bit 4 cleared. `EC` with `VolEnv = 0` → `VolEnv = FFh` (unconditional
`dec`) — the next `zDoVolEnv` reads envelope byte at index FFh (past the table;
deterministic data, no stream known to do this).

### (e) Engine today

`FmVoiceWriteProfile.S3K_Z80` + `VolMode.BIT7` (`Sonic3kSmpsSequencerConfig`) carry
the order and TL rule — **same via profile** (GAP §1.2 #9), pending per-flag
verification. Engine `E5`/`E6`/`E4` in `Sonic3kCoordFlagHandler` match the FM
semantics including E6's PSG no-op; EC differs on the `VolEnv = 0` wrap (engine
guards `envPos > 0`) — edge-data divergence, no known shipped trigger. `E0` differs
at byte level: the engine replaces AMS/FMS from the parameter, the ROM ORs the
parameter over retained old bits (`D:3014-3018`) — invisible while old AMS/FMS are 0,
which is the shipped-data case. `zSetMaxRelRate`-before-`EF` must be verified in the
engine's voice-load path.

---

## 9. Modulation and envelopes

### 9.1 Normal modulation (`F0`, `ModulationCtrl = 80h`)

(a) `cfModulation` (`D:3405-3412`) stores only the **pointer** to its four parameter
bytes and sets `ModulationCtrl = 80h`. `zPrepareModulation` (`D:1237-1259`), run at
every attacked note (skipped when bit 1): copies wait/speed/delta into track RAM,
`ModulationSteps = steps/2` (halved on the first run), accumulator = 0.
`zDoModulation` (`D:1279-1326`): `ModulationWait--`, held at 1 afterwards
(`inc` back); `ModulationSpeed--`; only at 0: reload speed from `(iy+1)` (the
*data*) and accumulator += sign-extended `ModulationDelta`. Then, on **every**
post-wait update regardless of the speed counter, `hl += accumulator` and
`ModulationSteps--`; at 0: reload from data byte 3 (**full** count) and negate
`ModulationDelta`. `cfDisableModulation` (`FA`, `D:3686-3689`) clears bit 7;
`cfSetModulation` (`F4 xx`, `D:3433-3435`) and `cfAlterModulation` (`F1 psg fm`,
`D:3421-3425`) overwrite the control byte.

(d) **TV 9.1 — jump SFX sweep.** `Sound_62`: `smpsModSet $02,$01,$F8,$65` then
`nBb2,$15`. nBb2 = byte `A3h` (`81h` + 34; 2 octaves + 10 semitones, `INC:31-46`) —
index 34 → `zPSGFrequencies[34]` = `round(223721.56/(2·932.17))` = **120 = 78h**
(239 = `EFh` is index 22's value, 468.03 Hz — a wrong-row lookup). At the note: wait=2, speed=1, delta=−8, steps=65h/2=32h (50).
Update 1 after attack: wait 2→1, return (no mod term). Update 2: wait held at 1,
speed 1→0 → reload 1, accumulator = −8, freq = 120−8 = 112; steps 50→49. Updates
3…: accumulator −16, −24, … freq 104, 96, … After 50 delta applications steps hits
0 → reload 101 (65h full), delta = +8 → sweep reverses. Expected PSG write pairs per
update follow TV 7.1's encoding of each freq value.

**TV 9.2 — F0 re-arm without attack (title FM1).** `Snd_Title_FM1` sets
`smpsModSet $2A,…` then a tied note (`smpsNoAttack`): because
`zPrepareModulation` skips no-attack notes, the *pointer* changes but live counters
and accumulator carry over until the next attacked note — the engine comment in
`Sonic3kCoordFlagHandler` case 0xF0 documents exactly this and matches.

### 9.2 Modulation envelopes (`ModulationCtrl = 1..8`)

(a) `zDoModEnvelope` (`D:1330-1421`): envelope = `z80_ModEnvPointers[ctrl−1]`
(`D:4470-4487`, exactly **8 pointers**, envelopes 00-07). Byte at `ModEnvIndex`:
positive → `hl += value × (ModEnvSens+1)`, index++; `80h` → index = 0, re-read;
`82h` → **new index = the byte at Z80 address `ModEnvIndex+1`** — `bc` still holds
the bare index, so the operand is fetched from the driver **code region**
(`D:1376-1381`); `84h` → `ModEnvSens += byte at address index+1` (same hazard),
index += 2; `81h` and **`83h`** → set bit 6 (sustain) and abandon the frequency
update (`83h` falls into the `81h` path because the `jr nc` at `D:1369` requires
≥ 84h); `85h-FFh` → applied as negative (`h = FFh`) — ordinary negative deltas like
FFh/FEh in ModEnv_00 take this path, not the command path.

Envelope data (`D:4480-4487`): `ModEnv_01` = `00h` then falls through into
`ModEnv_00` = `01,02,01,00,-1,-2,-3,-4,-3,-2,-1,83h`; `ModEnv_02` ends `83h`;
`ModEnv_03/04/05/06/07` end `82h nn`.

**Reachability (corrects GAP §1.2 #11 and refines MAP §7.2):** `smpsModChange` (`F4`)
appears in exactly five songs — CNZ1/CNZ2 (`$02` → ModEnv_01), LBZ1/LBZ2 (`$01` →
ModEnv_00), and **Miniboss (Sonic 3)** (`$04/$06/$07/$08` → ModEnv_03/05/06/07)
(grep over `Sound/Music`, this spec). ModEnv_00/01 contain **no** `82h`/`84h` byte —
their negative bytes are plain deltas and their terminator `83h` is the sustain
command. The only shipped streams that execute `82h` are in `Snd_Minib` — and the
S&K music table maps **both** miniboss ids to `Snd_Minib_SK` (`D:2856-2860`), so the
installed locked-on image never plays `Snd_Minib`. Therefore: **no
loader-relevant S3K stream reaches the code-byte read**; it is reachable only in the
standalone-S3 build. CNZ/LBZ need only the ordinary envelope stepper plus the `83h`
sustain rule.

(d) **TV 9.3 — LBZ1 `F4 01`.** ModEnv_00 with `ModEnvSens = 0`: per update the
frequency offset sequence is +1, +2, +1, 0, −1, −2, −3, −4, −3, −2, −1, then `83h` →
bit 6 set → **frequency writes cease for the track's remaining lifetime** (§7,
TV 7.4). RAM: `ModEnvIndex` counts 0→11; `PlaybackControl` gains bit 6.

**TV 9.4 — S3-image-only `82h` derivation (documentation, not a locked-on vector).**
For `Snd_Minib` `F4 04` → ModEnv_03, index 12 reads `82h`, operand = code byte at
`000Dh`. In the S&K-image layout (EntryPoint 7 bytes — `di`, `di`, `im 1`, `jp` — plus the `F2h`
filler at `0007h` → `GetPointerTable` at `0008h`, `D:374-390`) address `000Dh` is the `add hl,bc`
opcode = `09h` → new index 9 → the envelope loops indices 9,10,11 (−2,−1,0) then
`82h` again: a repeating 3-step vibrato tail. This depends on the assembled image
bytes; kept as derivation, with §18 q3 still open on image-byte availability.

### 9.3 PSG volume envelopes

(a) `z80_VolEnvPointers` (`D:4494-4501`) has exactly **39 (= 27h) entries**
(`VolEnv_00`-`VolEnv_26`). `zDoVolEnv` (`D:4153-4212`): byte `< 80h` → return it as
attenuation to add, index++; `80h` → index = 0 and re-read (RESET semantics);
`81h` → set bit 4 and return past the *track update's* caller — volume untouched,
channel left sounding (`D:4204-4207`, rest-without-silence); `83h` → rest **and**
`zRestTrack` (silences the channel unless overridden, `D:4189-4193`); any other
negative → operand fetched via the same code-region `bc` hazard (`D:4178-4184`; only
`VolEnv_0A` contains one — its `-10h` — and no stream uses `VolEnv_0A`). PSG tracks
select the envelope with `VoiceIndex` (`F5`, header byte); the envelope index resets
to 0 at every attacked note (`zFinishTrackUpdate`).

(d) **TV 9.5 — `sTone_0D` on the jump SFX.** `VolEnv_0D` = `02h, 83h`: update 1
adds 2 to volume (write `92h` per TV 7.1); update 2 reads `83h` → rest + PSG silence
write `9Fh` (channel 1 volume F). **TV 9.6 — `VolEnv_06` hold (`80h`).**
`01,0Ch,03,0Fh,02,07,03,0Fh,80h`: after 8 values the `80h` resets the index to 0 and
immediately re-reads value `01h` — a looping envelope, not a hold. (S3K's `80h` is
RESET; a hold would need `81h`.)

### (e) Engine today

`applyModOnNote`, `halveModSteps`, `ModAlgo.MOD_Z80` (config) — **same** (GAP #10).
Mod envelopes: `Sonic3kCoordFlagHandler` F4/F1 copy the envelope bytes
(`copyModEnvelope`); the stepper lives in `SmpsSequencer` (`modEnv*` fields). The
code-byte `82h` operand is not modelled — after this section's reachability result
that is **acceptable for the locked-on target** (GAP deferred item 10 shrinks to
"standalone-S3 support"); what must be verified instead is the `83h` sustain
semantics (engine `modEnvHold`) and the never-cleared bit 6. PSG `80h` = RESET is
configured (`PsgEnvCmd80.RESET`) — matches. The `81h` vs `83h` distinction
(rest-keep-sounding vs rest-and-silence) is CD S3K row SEQ-01/§12-relevant and needs
a targeted engine check.

---

## 10. Coordination flags

Dispatcher: `zGetNextNote_cont`/`zUpdateDACTrack_cont` route bytes ≥ `E0h` through
`zHandleCoordFlag` (`D:2921-2945`), which pre-loads `a` with the first parameter and
pushes a return stub that does `inc de` — so a no-parameter handler must `dec de`.
Switch table `D:2948-2980`; meta table (`FF nn`) `D:2982-2990`.

Full table — Params is what the handler consumes; Usage is shipped music/SFX file
counts (MAP §10, spot-verified for F4/FC/FF00/E9/FF07/E2 by grep for this spec);
Engine = `Sonic3kCoordFlagHandler` case unless noted; Verdict per the shipped path.

| Byte | Handler (`D:`) | Params | Effect | Usage | Engine verdict |
|---|---|---|---|---|---|
| E0 | `cfPanningAMSFMS` 3010 | 1 | `AMSFMSPan=(old&3Fh)\|param`, write B4h | wide | deviates (byte-level): engine replaces AMS/FMS rather than OR-retaining (§8e) |
| E1 | `cfDetune` 3061 | 1 | `Detune = param` | 0/0 | same |
| E2 | `cfFadeInToPrevious` 3077 | 1 | store `zFadeToPrevFlag` (§13); `FFh`/`29h` magic | 7/0 files (`smpsFade` = `E2 FF` ending both 1UP songs' DAC tracks; inert `smpsNop` values 00/01/25 in Countdown ×2, Chaos Emerald, Game Complete ×2, S&K Credits ×2) | adapted: engine acts only on FFh via `GameServices.audio().restoreMusic()` — static reach-out flagged (GAP hygiene item 15); inert values match |
| E3 | `cfSilenceStopTrack` 3088 | 1 (unused) | `zFMSilenceChannel` **regardless of type** + F2 | in-stream + via E4 | deviates: engine stops/mutes but does not emit the silence burst nor the PSG→FM1 key-on hazard (§6) |
| E4 | `cfSetVolume` 3113 | 1 | absolute volume (§8) | 1/0 | same |
| E5 | `cfChangeVolume2` 3140 | 2 (first ignored) | FM relative volume | 3/0 at byte level (40 two-arg `smpsFMAlterVol`, music only; the macro-name count 31/41 includes the one-arg form, which assembles to `E6`) | same (engine skips the first byte) |
| E6 | `cfChangeVolume` 3156 | 1 | FM relative; PSG no-op | ~31/41 files (every one-arg `smpsFMAlterVol` — ≈503 music + 48 SFX instances — plus `smpsAlterVol` in S3 Credits; 1/0 counted only the `smpsAlterVol` macro name) | same |
| E7 | `cfPreventAttack` 3218 | 0 | set bit 1 | 37/35 | same (`tieNext`) |
| E8 | `cfNoteFill` 3230 | 1 | ×divider → `NoteFillTimeout/Master` | 16/0 | verify divider multiply |
| E9 | `cfSpindashRev` 3039 | 0 | §14.3 | 0/1 (`Sound_AB`) | same |
| EA | `cfPlayDACSample` 2997 | 1 | `zDACIndex = param` | 0/0 | same shape |
| EB | `cfConditionalJump` 3247 | 3 (index, target lo/hi) | if `LoopCounters[i]` would decrement to 0: clear it, jump; else skip target. The decrement is **not stored** on the no-jump path | 0/0 | **deviates**: engine consumes 4 bytes (index, count, target) and compares `== targetCount` — different arity and semantics; unreached by shipped data |
| EC | `cfChangePSGVolume` 3273 | 1 | §8 | 20/23 | same except `VolEnv=0` wrap (§8e) |
| ED | `cfSetKey` 3295 | 1 | `Transpose = param − 40h` | wide | same |
| EE | `cfSendFMI` 3308 | 2 | raw part-I register write | 0/0 | deviates on part: engine routes by track channel (part II for FM4-6); ROM always part I; unreached |
| EF | `cfSetVoice` 3345 | 1 (2 if negative) | §8 | universal | same via profile; verify `zSetMaxRelRate` prelude |
| F0 | `cfModulation` 3405 | 4 | §9.1 | 55/98 | same |
| F1 | `cfAlterModulation` 3421 | 2 (PSG byte 1, FM byte 2) | set `ModulationCtrl` | rare | same |
| F2 | `cfStopTrack` 3443 | 1 (unused) | §6 restore / track end | universal | restore burst gaps per §6e |
| F3 | `cfSetPSGNoise` 3541 | 1 | shipped: return if `VoiceControl` bit 2; write DFh; store param, set bit 0; param≠0 → write param raw; 0 → clear bit 0, write FFh. No override check; FM1-3 tracks not excluded (§18 q7) | 33/36 | same for PSG tracks; engine guards on type not bit 2 — equivalent for shipped placements |
| F4 | `cfSetModulation` 3433 | 1 | `ModulationCtrl = param` | 5 songs (§9.2) | same |
| F5 | `cfSetPSGVolEnv` 3583 | 1 | PSG `VoiceIndex` | 38/23 | same |
| F6 | `cfJumpTo` 3598 | 2 | absolute Z80 pointer | universal | same |
| F7 | `cfRepeatAtPos` 3613 | 4 | loop counter init/dec/jump | universal | same |
| F8 | `cfJumpToGosub` 3641 | 2 | push return on track stack (down from 30h) | wide | same |
| F9 | `cfJumpReturn` 3667 | 0 | pop | wide | same |
| FA | `cfDisableModulation` 3686 | 0 | clear bit 7 of `ModulationCtrl` | 3/3 | same |
| FB | `cfChangeTransposition` 3697 | 1 | `Transpose += param` | 0/0 | same |
| FC | `cfLoopContinuousSFX` 3712 | 2 | §14.2 | 0/30 | same (handler comments carry the ROM line-level walkthrough) |
| FD | `cfToggleAltFreqMode` 3746 | 1 | shipped: set bit 3 only when param == 1, else clear | 0/0 | same |
| FE | `cfFM3SpecialMode` 3771 | 4 | broken self-modifying copy (§18 q2); non-FM3 skips 3 bytes | 0/0 | adapted: engine consumes 4 bytes, no-op — acceptable (unreached) |
| FF 00 | `cfSetTempo` 3861 | 1 | `zCurrentTempo = param`; accumulator untouched | 2 songs (Countdown, S&K Credits) | same; verify no accumulator reset |
| FF 01 | `cfPlaySoundByIndex` 3874 | 1 | nested request | 0/0 | operand-skip only — documented |
| FF 02 | `cfHaltSound` 3887 | 1 | non-zero: `zHaltFlag = v`, clear bit 7 + key-off all nine music slots, `zPSGSilenceAll`; zero: set bit 7 on all nine | 0/0 | operand-skip only |
| FF 03 | `cfCopyData` 3932 | 3 | `ldir` into the stream | 0/0 | operand-skip only |
| FF 04 | `cfSetTempoDivider` 3953 | 1 | all nine music `TempoDivider`s; effective at next duration | 0/0 | verify all-track scope of `updateDividingTiming` |
| FF 05 | `cfSetSSGEG` 3972 | 4 | write `90h/98h/94h/9Ch`, set `HaveSSGEGFlag` | 0/0 | same register order; flag persistence used by §6 restore |
| FF 06 | `cfFMVolEnv` 4033 | 2 | §8 FM vol env | 0/0 | same shape |
| FF 07 | `cfResetSpindashRev` 4046 | 0 | `zSpindashRev = 0` | 0/1 (`Sound_AB`) | same |

### (d) Test vectors

**TV 10.1 — Countdown accelerando.** `Music/Countdown.asm` FM1: header tempo `7Fh`,
then `smpsSetTempoMod $40/$20/$10/$08` between call sections (emitted as
`FF 00 nn`, `INC:484-487`). Expected `zCurrentTempo` sequence 7Fh→40h→20h→10h→08h
with `zTempoAccumulator` carried across each change (no reset); audible: delay-frame
rate falls from ~50% to ~3% — the countdown speeds up.

**TV 10.2 — E5's dead first parameter.** Any two-arg `smpsFMAlterVol`
(e.g. AIZ1's `smpsFMAlterVol $0A, nG3`, `Music/AIZ1.asm:1166`): only the second
byte reaches `cfChangeVolume`. (A one-arg `smpsFMAlterVol` such as Countdown's
`smpsFMAlterVol $FF` assembles to `E6`, not `E5`.) A comparator asserting PSG volume changes from E5's first byte
(the S1/S2 shape) would be wrong for S3K.

**TV 10.3 — F8/F9 stack bytes.** `smpsCall` pushes the 16-bit return into track
offsets 2Eh-2Fh (first call), 2Ch-2Dh (second); a third call would clobber
`Voices` (2Ah-2Bh) and a fourth `LoopCounters` — RAM-comparable via `StackPointer`
(30h→2Eh→2Ch→…).

### (e) Engine

Per-row verdicts above; the actionable divergences are E3's silence burst, EB's
arity (unreached), E0's OR-retention (unreached at byte level by shipped data), and
the §6 restore-burst gaps. Everything else is same-or-config.

---

## 11. Fades

### (a) ROM behaviour

**Fade-out** (`E1`/`E5` → `zFadeOutMusic`, `D:2307-2312`): `zFadeOutTimeout = 28h`
(40 steps), `zFadeDelayTimeout = zFadeDelay = 6`; falls into `zHaltDACPSG`
(`D:2317-2323`): zero the `PlaybackControl` of FM6/DAC, PSG1, PSG2, PSG3 and
`zPSGSilenceAll` — **DAC and PSG stop immediately; only FM1-5 fade** (CD FADE-01).
Per music update, `zDoMusicFadeOut` (`D:2331-2385`): if the timeout is negative,
`zHaltDACPSG` again and clear the sign bit (legacy); `zFadeDelayTimeout--`; on expiry
reload 6 and `zFadeOutTimeout--`; **at zero → `zStopAllSound` with no final volume
step** (`D:2355`, CD FADE-04); otherwise bank-switch to the song bank and for the six
FM/DAC slots `Volume++` (clamped at 7Fh) and `zSendTL` for slots playing and not
overridden. Total: 40 × 6 = 240 music updates (≈4 s NTSC; shorter under speed-up or
PAL, §1 TV 1.4). SFX are *not* blocked during an S3K fade-out (nothing gates the
queue on `zFadeOutTimeout`; contrast the pause interaction below).

**Fade-in** (only entered via the 1-up restore, §13; `zDoMusicFadeIn`,
`D:2393-2453`): while `zFadeInTimeout != 0`: `zFadeDelay--`; at zero reload from
`zFadeDelayTimeout` (2), then for **FM1-FM5 only** (the DAC slot is skipped,
`b = 5` starting at `zSongFM1`) `Volume--` + `zSendTL`, and `zFadeInTimeout--`;
when it reaches zero, clear bit 2 on PSG1-3 and the DAC slot (`D:2442-2452`) —
PSG and DAC stay mute for the whole 40h × 2 = 128-update fade.

**Fade × pause:** unpausing while `zFadeOutTimeout != 0` executes `zStopAllSound`
(`D:2247-2249`) — a pause during a fade-out kills the song. **Fade × 1-up:** a
`mus_ExtraLife` request while `zFadeInTimeout != 0` is dropped after clearing the
mailboxes and queue (`D:1723-1735`).

### (b) State

`zFadeOutTimeout`, `zFadeDelay`, `zFadeDelayTimeout`, `zFadeInTimeout`, per-track
`Volume`, `PlaybackControl`.

### (c) Observable effect

Fade-out start: `zPSGSilenceAll` burst (`9Fh,BFh,DFh,FFh`) immediately; then every
6th music update a TL write set for each playing, un-overridden FM slot; at the end
the full stop-all burst of TV 5.1. Fade-in: TL writes on FM1-5 every 2nd update.

### (d) Test vectors

**TV 11.1 — E1 timeline over MHZ1.** Updates 1-5 after the request: no TL writes
(delay counting). Update 6: `Volume` of FM1-5 slots 0Eh,10h,0Bh,0Bh,17h (header +1)
and matching TL bursts (only bit-7 TL bytes move, §8). Update 240: stop-all burst;
`zFadeOutTimeout` RAM sequence 28h,…,1 then wiped to 0.

**TV 11.2 — fade-out + pause.** `E1`, then pause, then unpause after 10 frames:
expected — pause burst (§12), silent frames, then on the unpause frame the
stop-all burst instead of pan restoration.

**TV 11.3 — DAC cut at fade start.** With the MHZ1 DAC track playing a drum on the
fade frame: `zHaltDACPSG` zeroes the DAC track's playing bit but does **not** clear
`zDACIndex` — the in-flight sample finishes on its own (the DAC loop keys off
nothing); no further drums are queued. Distinguishes "track halted" from "sample
aborted" (§15's abort happens only on `zDACIndex` overwrite or stop-all wipe).

### (e) Engine today

`SmpsSequencer.processFade` with `fadeOutSteps 28h / delay 6 / fadeInSteps 40h /
delay 2` (`Sonic3kSmpsSequencerConfig`) — constants match. The engine fade applies to
PSG tracks too via `addPsg` and skips only DAC — the ROM's fade-out silences PSG at
the *start* and its fade-in excludes PSG entirely: the channel-set rule must be
checked (`processFade` skips `TrackType.DAC` only — **likely divergence** on PSG
handling both directions; GAP §1.2 #13). Fade-out's terminal stop and the
no-final-step rule appear modelled (`fadeState.steps == 0` → stop all). The
pause-kills-fading-song rule has no engine counterpart (pause is presentation-side).
`Sonic3kAudioProfile.executeFadeOut(manager)` passes `(0x28, 6)` — same constants.

---

## 12. Pause / unpause

### (a) ROM behaviour

`zPauseUnpause` (`D:2232-2301`), first thing in every update:

- `zPauseFlag = 0`: return (normal).
- `= 1` (68k pause): `pop de` — the eventual `ret` skips the whole update — set flag
  to 2, `zPauseAudio` (`D:2541-2580`): shipped extra `zPSGSilenceAll` first
  (`D:2542-2544`); `B4h,B5h,B6h = 0` on part I (FM1-3) and `B4h,B5h = 0` on part II
  (FM4-5) — **FM6's pan register is untouched** (pan 0 = both speakers off =
  silent); key-off `28h = 00h,01h,02h,03h,04h,05h` — six channel codes including the
  invalid 3 (`b = 6`, `D:2568-2577`); falls into `zPSGSilenceAll` again. DAC/FM6
  keeps sounding (CD PAUSE-03/04).
- `= 2` (every later paused frame): `pop de`, `dec a` → 1, `ret nz` — no processing
  at all; the flag stays 2.
- `= 80h` (unpause): clear the flag; **if `zFadeOutTimeout != 0` → `zStopAllSound`**
  (§11). Otherwise: loop 1 (`D:2250-2275`) — six slots from `zSongFM1` (FM1-5
  **plus `zSongPSG1`**, the shipped off-by-one; harmless because `zWriteFMIorII`
  rejects PSG tracks): for tracks playing (or unconditionally when `zHaltFlag` set),
  re-send `B4h = AMSFMSPan`. Loop 2 (`D:2277-2299`) — intended for SFX but starts at
  `zTracksSFXEnd` (1F40h) for 7 slots: it reads the 1-up save copies of PSG2/PSG3,
  the stack region, and `2000h-208Fh` (past RAM end, §18 q1); with plausible
  contents it writes nothing. **Net: no real SFX slot's pan is ever restored** — an
  SFX that spans a pause on FM3-5 stays pan-silent until its next `E0` or its end.

The 68k writes 1/80h in `Pause_Main` / `Pause_ResumeMusic` / `Pause_FrameAdvance`
(the frame-advance path also writes 80h) (`K`, `Pause_Game` block).

### (b) State

`zPauseFlag`, `zHaltFlag`; nothing else changes while paused.

### (c) Observable effect

Pause frame: `9Fh,BFh,DFh,FFh` ×2 bracketing `B4h/B5h/B6h(I) = 0`, `B4h/B5h(II) = 0`,
`28h = 0..5`. Paused frames: silence in the write stream, RAM frozen. Unpause frame:
up to five `B4h(+off)` writes with each track's `AMSFMSPan`, then a normal full
update (the same invocation continues into the SFX/music passes — the `pop de` only
happens on flag 1/2 paths).

### (d) Test vectors

**TV 12.1 — pause burst order.** Exactly: `9Fh,BFh,DFh,FFh`, `B4h=0`, `B5h=0`,
`B6h=0` (part I), `B4h=0`, `B5h=0` (part II), `28h=00h`, `28h=01h`, `28h=02h`,
`28h=03h`, `28h=04h`, `28h=05h`, `9Fh,BFh,DFh,FFh`.

**TV 12.2 — DAC through pause.** Queue a long DAC sample (any `zDACIndex` with a
multi-frame length), pause mid-sample: `2Ah` deltas continue (the DAC loop never
stops; `zVInt` still returns `b = 1`), no `2Bh = 0` is written. CD PAUSE-04
confirmed.

**TV 12.3 — SFX pan loss.** Start `Sound_34` (FM5, `panLeft`), pause, unpause:
no `B5h` write for the SFX slot appears in the unpause burst; the ring keeps playing
with pan = 0 (silent) until its next `E0` (it has none) or its end. RAM:
`zSFX_FM5.AMSFMSPan` still 80h — the *state* survives, the *register* does not.

**TV 12.4 — unpause during fade (TV 11.2).**

### (e) Engine today

**Absent as a driver behaviour** (GAP §1.2 #14): pause is
`OuterFramePresentation.modeFor → PresentationMode.SILENT` — no burst, no driver
flag, no pan-loss reproduction, and the sequencer clock simply does not advance.
Deferred item 7 adds `pauseFlag` semantics to `SmpsDriver` with this section's
bursts; the SFX pan-loss rule is the observable the comparator should pin first.

---

## 13. 1-up save / restore (and speed shoes across it)

### (a) ROM behaviour

`zPlayMusic` intercepts `mus_ExtraLife` (`2Ah`) (`D:1717-1783`):

1. If `zFadeInTimeout != 0` (previous restore still fading): clear all three
   mailboxes, all three queue slots and `zNextSound`, **drop the request**.
2. Else if `zFadeToPrevFlag == 29h` (a 1-up is already playing): `zBGMLoad` again —
   restart the jingle **without re-saving** (repeat-1-up stability, CD OVR-09).
3. Else: clear mailboxes/queue; save `zSongBank`, `zTempoSpeedup` (then zero it —
   the jingle runs at normal speed, CD OVR-06), `zCurrentTempo`, `zVoiceTblPtr`;
   copy the nine music slots (1C40h-1DF0h) onto `zTracksSaveStart` = **the SFX
   slots** (1DF0h-1FA0h); strip bit 7 of each saved `PlaybackControl` (the shipped
   `set 2,(hl)` is immediately overwritten by `ld (hl),a`, so bit 2 is *not* set —
   `D:1763-1773`); `zFadeToPrevFlag = 29h`; `zBGMLoad` (no `zStopAllSound` — the
   save area must survive).

Consequences of the overlap: the save copy **clobbers all seven SFX slots**, and the
bit-7 strip leaves those bytes non-playing — every live SFX stops being serviced
instantly, with no key-off written (its last chip state decays under the 1-up's own
writes as the jingle's tracks take the channels). New SFX cannot start: while
`zFadeToPrevFlag == 29h`, `zUpdateMusic` (`D:662-679`) clears both SFX mailboxes
every update and never runs `zFillSoundQueue`; a queued ordinary music id `< 32h`
is **left standing** in `zMusicNumber` (deferred until the flag drops); another
`mus_ExtraLife` or a non-music value is cleared. (This refines MAP §4.9/open q 5:
the save is not "corrupted by advancing SFX" — the SFX slots are inert after the
strip; the open question is retained for hardware confirmation, §18 q5.)

Restore: the 1-up's **DAC track** ends with `E2 FF` (`smpsFade`,
`Music/1UP (Sonic & Knuckles).asm:79`, dispatched by `zUpdateDACTrack`;
`cfFadeInToPrevious` stores FFh); the
next music update sees FFh (`D:714-716`) and runs `zFadeInToPrevious`
(`D:2725-2788`): clear the flag; restore `zCurrentTempo`, `zTempoSpeedup` (speed
shoes resume), `zVoiceTblPtr`, `zSongBank` (+ bank switch); copy the save area back;
DAC slot `PlaybackControl |= 84h` — bits 7 (playing) and **2** (write-suppression;
84h sets no bit 4, whatever the disassembly comment's "resting" gloss says — the
FM-only `res 2` below only makes sense for bit 2); for FM1..PSG3: `|= 84h`; FM
slots additionally: clear bit 2, `Volume += 40h`, voice re-upload
(`zGetFMInstrumentPointer` + `zSendFMInstrument` — §8 order, at the attenuated
volume); PSG slots **keep bit 2** (silent) and the DAC slot keeps bit 2, both until
the fade-in ends (§11); `zFadeInTimeout = 40h`, `zFadeDelay = zFadeDelayTimeout = 2`.
Frequencies are not resent by the restore itself; an FM slot (bit 2 now clear)
resumes §7's every-frame `A4h/A0h` writes from the same update's track walk unless
its saved state was resting (bit 4) or sustained (bit 6), while PSG and DAC stay
write-suppressed (bit 2) until the fade-in ends.

Non-1-up music while the flag is `29h` is deferred, not lost; `zStopAllSound` (any
stop/fade-terminal) wipes `zFadeToPrevFlag` and the save area — the saved song is
abandoned (CD OVR-02).

### (b) State

`zFadeToPrevFlag`, the four save bytes (1C2A-1C2E), `zFadeInTimeout`, save area
1DF0h-1FA0h, mailboxes.

### (c) Observable effect

1-up start: **no stop-all burst** (only `zBGMLoad`'s `B6h = C0h` and the jingle's own
notes); every live SFX goes silent without key-offs. Restore: per FM track a §8-order
voice upload at `Volume+40h`, then 128 updates of TL fade-in on FM1-5, then PSG/DAC
unmute (bit 2 cleared), their §7 per-frame writes resuming mid-note for any
non-resting track.

### (d) Test vectors

**TV 13.1 — 1-up over MHZ1 + speed shoes.** State: MHZ1 playing, `zTempoSpeedup=8`.
Request `2Ah`. Expected RAM: `zTempoSpeedupSave=8`, `zTempoSpeedup=0`,
`zCurrentTempoSave=39h`, `zCurrentTempo=20h` (1UP header), `zFadeToPrevFlag=29h`,
`zSaveSong*` = the pre-request music slots with bit 7 stripped; `zSFX_FM3..PSG3`
unreadable as SFX (same bytes). Speed-up extra updates cease immediately (§1 tail
sees 0).

**TV 13.2 — deferral.** While the jingle plays, request `mus_MHZ2 (10h)` then
`Play_SFX(62h)`: every update clears `zSFXNumber0/1` but leaves `zMusicNumber=10h`.
When `E2 FF` lands and the restore runs, the standing `10h` is processed by the
*next* update's queue pass — the deferred song loads (via `zStopAllSound`, wiping
the just-restored state: the restore is only audible for the interval until the
deferred load).

**TV 13.3 — repeat 1-up.** Request `2Ah` twice, 60 frames apart: second request
takes branch 2 — jingle restarts, `zTempoSpeedupSave` and the save area unchanged
(no double save). After `E2 FF`, restore returns to the original song.

**TV 13.4 — restore burst.** At restore with MHZ1 saved: five §8 voice uploads
(FM1-5, voices per MHZ1's `EF` state at save time) each at `Volume_orig+40h+…`; a
track saved mid-note resumes its every-frame `A4h/A0h` pair in the same update's
track walk (only saved-resting or bit-6-sustained tracks stay frequency-silent
until their next attacked note); `9Fh`-family writes absent
(PSG stays bit-2 muted). RAM: `zFadeInTimeout=40h` counting down by 1 every 2nd
music update.

### (e) Engine today

The 1-up is a second live `SmpsDriver` voice parked on `AudioVoiceRegistry`'s
override stack (`AbstractSmpsAudioBackend.isMusicOverride` gating;
`AudioPresentationCommand.RestoreMusicOverride`) — **adapt** (GAP §1.2 #15, §1.4
item 2): the parked-driver model holds the same information as the RAM copy but does
not express the SFX-slot clobber (now shown to be an SFX-kill, not a corruption) or
the deferred-music mailbox. `Sonic3kAudioProfile.blocksSfxDuringMusicRestoreFadeIn()
= false` — the ROM blocks new SFX *during the jingle* (mailbox clearing) but not
during the restore fade-in (queue processing is normal once the flag is 0): the
engine knob matches the fade-in phase; the jingle-phase block needs verification.
The restore burst (at-rest, `+40h`, §8 order, no frequency) is deferred item 6;
speed-up routing to the parked driver is the CD OVR-06 behaviour to keep.

---

## 14. Request transforms

### 14.1 Ring left/right

(a) §6: index 0 toggles `zRingSpeaker` then plays index `zRingSpeaker`
(`D:1919-1926`). `Sound_33` = FM4, `panRight` (`E0 40h`); `Sound_34` = FM5,
`panLeft`, then `smpsJump` into `Sound_33`'s note stream (`Sound_34_Jump00`) — same
notes, different channel and pan. Reset: `zRingSpeaker = 0` at boot (`D:547`) and by
the `zStopAllSound` wipe — i.e. by every ordinary song load, stop, fade-terminal and
SEGA chime, but **not** by a 1-up (no stop-all on that path, §13). The 68k only ever
requests `sfx_RingRight` (`33h`).

(d) **TV 14.1.** After any song load, request `33h` three times ≥1 frame apart:
plays Sound_34 (left, FM5), Sound_33 (right, FM4), Sound_34 (left) —
**left first**. RAM `zRingSpeaker`: 1, 0, 1. A raw `Play_SFX(34h)` does not toggle
(index ≠ 0) — it plays left and leaves the phase unchanged.

(e) Engine: `AudioManager.ringLeft` (init `true`, reset by
`ResetRingAlternation`) — first-left matches; residency in manager-not-driver is GAP
deferred item 14 (move to driver RAM so the toggle resets at the ROM sites — note
the 1-up exception above when that lands). CD REQ-01's "resets when music is played"
is correct in effect for ordinary music via the wipe, wrong for the 1-up.

### 14.2 Continuous SFX

(a) Ids ≥ `BCh` (`sfx__FirstContinuous`, `KC:1633`). Re-request of the running id:
`zContinuousSFXFlag = 80h`, `zContSFXLoopCnt` reloaded from the SFX header's track
count, **return without restarting** (`D:1939-1957`). Different id: flag = 0, store
id, load normally. In-stream `FC` (`cfLoopContinuousSFX`, `D:3712-3736`): flag =
80h → `zContSFXLoopCnt--`; non-zero → jump to target; zero → clear flag **and still
jump** (one more loop). Flag ≠ 80h → clear `zContinuousSFX` (shipped also re-clears
the flag), skip the 2-byte target, fall through (typically to `F2` or a fade-out
tail). The 68k side keeps continuous SFX alive via `Play_SFX_Continuous` (§4.1,
once per 16 frames). 30 SFX files use `FC`.

(d) **TV 14.2 — slide skid (`BCh`).** `Sound_BC`: 1 track (FM3), loop body
`nBb6,$16` + `smpsContinuousLoop`. Request at frame 0; 68k re-requests when
`v & 0Fh == 0`. Note duration 16h = 22 frames > 16-frame re-request period, so on
each `FC` (every 22 frames) the flag is normally 80h: `zContSFXLoopCnt` 1→0 → clear
flag, loop. If re-requests stop, the next `FC` sees flag 0 → clears
`zContinuousSFX`, falls into `smpsStop` → §6 release. RAM: `zContinuousSFX = BCh`
while alive; `zContinuousSFXFlag` pulses 80h→0 at each `FC`.

(e) Engine: `SmpsDriver.prepareContinuousSfxExtension` / `extendContinuousSfx` /
`continuousSfxFlag` + `Sonic3kCoordFlagHandler.handleContSfx` — **same** (GAP §1.2
#18); `Sonic3kAudioProfile.isContinuousSfx` uses the correct `BCh` threshold. The
16-frame cadence is game-side.

### 14.3 Spindash rev

(a) `sfx_Spindash` (`ABh`) bypasses the rev reset; every other **normal** SFX
(`33h-BBh` except `ABh`) zeroes `zSpindashRev` (`D:1968-1972`); continuous SFX
(≥ BCh) do not touch it; `FF 07` in the release stream also zeroes it; boot and
stop-all wipe it. `Sound_AB`'s FM5 stream opens with `smpsSpindashRev` (`E9`):
`Transpose += zSpindashRev`; if the result ≠ 10h, `zSpindashRev++`
(`D:3039-3050`). Header transpose is 0, so play n (0-based, no intervening reset)
sounds at +n semitones and the ladder caps when the transpose reaches exactly 10h
(16 plays).

(d) **TV 14.3.** Twenty spindash requests, no other SFX between: `Transpose` per
play = 0,1,2,…,0Fh,10h,10h,10h,10h; `zSpindashRev` after each = 1,2,…,0Fh,10h,10h
(stops incrementing at the cap; assumes each re-request lands while the previous
play is still running — once the `smpsLoop $00,$18` body exhausts, the stream's
own `FF 07` zeroes the rev before `F2`). Interleave one jump SFX after play 5: next
spindash plays at transpose 0 again (rev was reset). FM frequency per TV 7.2 with
idx = note + transpose. This answers CD §15 open q 3: **S3K does have a driver-side
spindash ladder**, of this escalating-transpose shape (not S2's `3Ch`-timeout
counter shape asserted by INV).

(e) Engine: `Sonic3kCoordFlagHandler` case 0xE9 + `onSfxStart` reset — **same**,
including the ≠10h cap and the reset condition (`< SLIDE_SKID_LOUD && != SPINDASH`).
Reset at stop-all/boot is a verify item.
`Sonic3kAudioProfile.adjustSfxPitch(SPINDASH_CHARGE) = 1.0` correctly disables the
game-side pitch so the driver ladder owns it.

### 14.4 SEGA chime

(a) `cmd_SEGA` (`FFh`) → `zPlaySegaSound` (`D:2703-2719`): `zStopAllSound`,
`PlaySegaPCMFlag = 1` — the shipped S&K build does **not** clear the mailboxes or
queue — `pop hl; ret` abandons the rest of the update. The DAC idle loop then enters
`zPlaySEGAPCM` (`D:4372-4422`) with interrupts disabled: S&K clears the flag first;
`2Bh = 80h`; `bankswitch3` to the SEGA bank; stream `SEGA_PCM.size` (24111)
unsigned bytes to `2Ah`, polling `zMusicNumber` for `cmd_StopSEGA` (`FEh`) each
byte. No `zVInt` runs during the chime — music, SFX, fades and the mailboxes are
frozen. Loop cost: 105 T-states + `13·(b−1)`, `b = pcmLoopCounter(sample_rate)`
(`KM:270-271`); `Sound/PCM/generated/Sega.inc` declares `sample_rate = 14434` →
`b = 1 + (3579545/14434 − 105 + 6)/13 = 12` → 248 T-states/byte ≈ 14434 Hz at NTSC.
After the chime the S&K build jumps back to the DAC loop leaving `FEh` in
`zMusicNumber` if the 68k sent it — the next update dispatches `FEh` →
`zStopAllSound` (§4 dispatch, harmless-by-inspection residue, §18 q6). The 68k
sequence (play, wait ≤3 s or Start, `cmd_StopSEGA`) is at `K:5485-5500`-region
(MAP §4.8). There is no `zStopSEGAPCM` routine — CD DAC-05's named anchor does not
exist; the stop is the per-byte mailbox poll (`D:4398-4400`).

(d) **TV 14.4.** Request `FFh` at frame N with MHZ1 playing: frame N's update ends
at the stop-all burst; from N+1 no `zVInt` output at all (frozen mailboxes readable
by the 68k only); `2Ah` bytes stream at 248 T periods; a `Play_Music(FEh)` write
mid-chime ends it within one byte-period; the next update after return performs a
second stop-all (residue). RAM at chime end: `PlaySegaPCMFlag = 0`,
`zMusicNumber = FEh` until consumed.

(e) Engine: `SegaPcmSpec` (`Sonic3kAudioProfile.getSegaPcmSpec`) + lifecycle events;
rendered as a host-linear sample, not through the DAC path at the Z80 loop period —
**adapt** (GAP §1.2 #20, CD CHIP-08/DAC-04); the freeze property is a lifecycle
event; the residue stop-all is unmodelled (harmless pending §18 q6).

---

## 15. DAC / PCM

### (a) ROM behaviour

`zUpdateDACTrack` (`D:2869-2918`): duration timer like any track; on expiry, bytes:
`>= E0h` → coordination flag; `>= 80h` → sample id (stored to `SavedDAC`); `< 80h`
→ duration only, reusing `SavedDAC`. `80h` is a rest. For a real sample:
`zKeyOffIfActive`, `zFM3NormalMode` (`27h = 0` — every drum note!), and unless the
DAC slot is SFX-overridden, `zDACIndex = id` with bit 7 clear — which makes the DAC
loop **abort any sample in flight** and start the new one (the loop re-reads
`zDACIndex` after every low nibble, `D:4343-4345`). Missing duration reuses
`SavedDuration` (shipped inline, `D:2914-2916`).

`zPlayDigitalAudio` (`D:4258-4355`): idle: `2Bh = 0`, `ei`-loop on
`PlaySegaPCMFlag`/`zDACIndex`. Start: `2Bh = 80h`, set bit 7 of `zDACIndex`,
read the 5-byte setup record via the `8000h` window (`startDACBank` offset table;
`DAC_Setup` macro `KM:310-315`: rate byte = `dpcmLoopCounter(rate)`, 16-bit length,
16-bit in-bank pointer; per-id rate scaling like `0.80` at `KM:400-…`); the rate
byte is patched into the two `ld b,N` instructions (self-modifying). Per byte: high
nibble then low nibble; each nibble `c += DecTable[nibble]` → `2Ah` write;
`DecTable` = `Sound/DAC/deltas.bin` (16 deltas, accumulator starts 80h). The two
nibble halves are asymmetric, and the annotated instruction budget totals **303
T-states per two nibbles at rate 1** plus `13·(N−1)` per nibble `djnz` and ~3.3
T-states per ROM read (`D:4299-4352`; `dpcmLoopCounter` is defined from 303/2 at
`KM:272`). Interrupts are enabled only inside the `djnz` waits; `zVInt` returns
with `b = 1`, so a frame's whole update time is added to that one nibble's period.
Length exhaustion clears `zDACIndex` → idle (`2Bh = 0`). Sample id bank comes from
the per-frame `DAC_Banks` switch in `zVInt` (§1).

### (b) State

`zDACIndex`, DAC track fields (`SavedDAC`, `SavedDuration`, `DurationTimeout`),
`PlaySegaPCMFlag`.

### (c) Observable effect

`2Ah` writes are per-nibble and outside the per-invocation write-order oracle (GAP
§1.2 #21 — pcm-only observable); the invocation-visible effects are `2Bh` enables/
disables, the `27h = 0` per drum note, key-offs, and `zDACIndex` RAM transitions.

### (d) Test vectors

**TV 15.1 — drum note.** MHZ1 DAC track plays a sample id `S` for duration `d`:
music-pass writes `28h = 06h`? — no: `zKeyOffIfActive` uses the DAC track's
`VoiceControl = 06h` → `28h = 06h` (FM6 operators off) — then `27h = 00h`; RAM
`zDACIndex = S` (bit 7 set once the loop picks it up), `SavedDAC = S`,
`DurationTimeout = d`.

**TV 15.2 — abort on retrigger.** Sample A (length ≫ 1 frame) playing; next DAC
note B: on B's music pass `zDACIndex = B` (bit 7 clear) → within one nibble period
the loop restarts with B's setup — no `2Bh = 0` between (the idle path is skipped
only when going through the start path; the abort re-enters from the top:
`2Bh = 0` then `2Bh = 80h` — both writes present).

**TV 15.3 — rate arithmetic.** For a sample declaring rate scale 1.0 and
`sample_rate = R`, the rate byte is `1 + (3579545/R − 151.5 + 6)/13` (integer,
`KM:270-272` composition) and the steady-state nibble period is
`303/2 + 13·(N−1)` T-states plus ROM-read penalty. (Engine's S3K `baseCycles = 297`
does not equal the listing's 303 — see (e).)

### (e) Engine today

`Ym2612Chip.dacPeriod(baseCycles, rate)` with `DacData.baseCycles` = **297** for S3K
(`DacData.java:10`) — the listing sums to 303 (`D:4352` total line; `KM:272`
defines `dpcmLoopCounter` from 303). **Divergence** (GAP §1.2 #21, deferred item
11): audible pitch/rate error ~2%, not parity-gating for the RAM/write oracle. The
abort-on-retrigger and idle/enable `2Bh` shape need verification; VInt-stretched
nibble periods and bus holds are oracle-only (hardware timing, hard rule 3 — never
a spec constant).

---

## 16. `fix_sndbugs` sites reachable by shipped data

The driver's own bug switch is `fix_sndbugs = 0` (`D:16`); there are 92 conditional
sites (full table MAP §12). The subset a shipped stream or normal play actually
reaches, with the shipped behaviour the engine must model:

| `D:` | Shipped behaviour (reached by) | Engine branch today |
|---|---|---|
| 239-245 | 7-word master pointer table incl. unused priority/limit slots (every lookup) | n/a (engine indexes data directly) |
| 374 | `F2h` filler byte after the entry jump — fixes the code-image byte layout that §9.2's `82h` operand would read (S3-image streams only) | n/a for locked-on (§9.2) |
| 477 | refresh register → `unk_1C17` (every frame) | not modelled; `not-compared` field |
| 488 | PAL reload 5 (S&K) — the 6 is S3-image-only (PAL) | engine has no double-update at all (§1e) |
| 584, 609 | `nop` between YM address/data writes (every write) | cycle-tier only |
| 683 | redundant `zFadeToPrevFlag` reload (every update) | n/a |
| 769, 2870, 4059 | note timer via `zTrackRunTimer` (every track) | same result |
| 953 | dead `ex af,af'` after the octave loop (every FM note) | n/a |
| 976, 2914 | missing-duration inline copy of `SavedDuration` (MHZ1 etc.) | verify (TV 7.3) |
| 1035 | alt-freq third byte → `Unk11h` (any FD-mode stream; none shipped) | consume-only fine |
| 1118 | `zFMNoteOn` masks 06h not 16h (every note-on) | callers gate on rest first — same result |
| 1214, 3197-3199 | `and 7Fh` after TL add; **no overflow clamp** in `zSendTL` (every TL send) | `VolMode.BIT7` — verify no clamp |
| 1659 | `unk_1C18` zeroed before fade effects (every E1-E5) | `not-compared` |
| 1763-1773 | 1-up save: `set 2` overwritten — bit 2 NOT set on saves (every 1-up) | §13e |
| 1803-1805 | self-modifying music-bank read (every song load) | n/a |
| 1906 | seventh `zFMDACInitBytes` entry (7-FM-track headers; none shipped) | n/a |
| 1948 | redundant `ld c,a` continuous path (every continuous re-request) | n/a |
| 1981 | `unk_1C15 = 0` in `zPlaySound` (every SFX) | `not-compared` |
| 2006-2090 | dead special-SFX blocks (every SFX load) | n/a |
| 2095-2099 | `zFMClearSSGEGOps` also for PSG SFX (harmless: writes rejected) | no writes — same |
| 2113-2117 | FM4-6 id `dec a` gap-skip (every FM4-6 SFX) | mapping same |
| 2121-2144 | PSG SFX init: stale-`ix` silence + unconditional `FFh` (every PSG SFX) | **missing** (§6e) |
| 2251-2257 | unpause pan loop covers six slots incl. PSG1 (every unpause) | pause absent (§12e) |
| 2277-2299 | unpause "SFX" loop reads 1F40h-208Fh (every unpause) | absent; observable rule = "no SFX pan restore" |
| 2461-2470 | stop-all wipe `+34h` into the stack region (every stop) | wipe range for RAM oracle |
| 2481-2492 | `zFMSilenceChannel` incl. key-off via `zKeyOnOff` (every stop-all) | burst verify (TV 5.1) |
| 2500-2504 | `zFadeOutTimeout` re-zeroed (every stop-all) | n/a |
| 2515-2517 | `zFM3Settings` stored (every stop-all) | `not-compared` |
| 2542-2544 | extra `zPSGSilenceAll` at pause (every pause) | §12 TV 12.1 |
| 2705-2716 | SEGA does not clear mailboxes/queue → `FEh` residue (every chime) | §14.4e |
| 2754-2756 | DAC restore `or 84h` shape (every 1-up restore) | §13 |
| 3089-3094 | E3 silences FM regardless of type — the FM1 key-on hazard (E4 on PSG SFX) | modelled by the complete S3K E4 host operation; standalone E3 remains missing (§6e) |
| 3445-3447, 3461-3462 | `unk_1C15/18` writes in `cfStopTrack` (every SFX end) | `not-compared` |
| 3542-3573 | F3 shipped guard/order (33 songs / 36 SFX) | equivalent for shipped placements (§10) |
| 3718-3720 | continuous flag re-clear (every non-retriggered FC) | same |
| 3751-3753 | FD enables only on param == 1 (none shipped) | same |
| 4121-4135 | PSG noise volume branch layout (every noise track frame) | same result |
| 4190-4192 | `83h` sets bit 4 before `zRestTrack` (every 83h envelope end) | verify |
| 4236-4244 | `zSilencePSGChannel` bit-0 test — noise not silenced on channel start (every PSG SFX) | part of §6 admission shape |

Unreached-by-shipped-data sites (FM3 special mode `838/885/3813`, SSG-EG upload
`3985-4016`, alt-freq `983/1016`, mod-env fetch
`1349`/vol-env fetch `4158` beyond the §9 cases, EB/EE/FB/FD/FE flags) have no
native-stream parity claim. The E4 operation nevertheless consumes retained
FM3/SSG-EG state exactly when a supported stream supplies it; unsupported raw
state remains fail-closed.

---

## 17. ROM-read data tables

Per GAP §3 "ROM-read data tables": each table's ROM-side identity, and the engine
copy to be equality-checked once (removal is a deferred implementation item, GAP
deferred item 13):

| Table | ROM source | Engine copy today |
|---|---|---|
| FM note frequencies (12 words 644-1216) | `zFMFrequencies` `D:2825-2829` (driver data image) | `SmpsSequencer` lines ~231-235 — values verified equal here |
| PSG note frequencies (84 words) | `zPSGFrequencies` `D:2799-2815` | sequencer-resident (equality check pending) |
| Mod envelopes (8) | `D:4470-4487` | loaded per-song via `SmpsProgramView.modEnvelopeByteAt` — provenance check: must come from the ROM driver blob, not a Java literal |
| PSG volume envelopes (39) | `D:4494-4572` (note `VolEnv_25` differs between images — S&K is the shorter) | same provenance check |
| Music banks (51 entries S&K) | `z80_MusicBanks` `D:2841-2864` | `Sonic3kSmpsLoader` address map |
| Music pointers (51) / SFX pointers (173) | `D:4585-4608` / `D:4614-4668` | loader |
| Universal voice bank (23h voices) | `D:4674-5303` | loader |
| FM/PSG init bytes | `D:1899-1916` | sequencer channel-order config (`FM_CHANNEL_ORDER` "16 0 1 2 4 5 6") |
| DAC setup records / `DAC_Banks` / `DecTable` | `KM:310-…`, `D:630-648`, `Sound/DAC/deltas.bin` | `DacData` (incl. the 297-vs-303 constant, §15e) |
| SEGA PCM | `SEGA_PCM` inc (`sample_rate 14434`, `size 24111`) | `Sonic3kSmpsConstants.SEGA_SOUND_*` |

S3K has **no** priority table and no speed-up tempo table (`speedUpTempos` empty in
`Sonic3kSmpsSequencerConfig` — correct).

---

## 18. Open questions

Carried forward (MAP open questions 1-8, renumbered identically), with lane owners;
none resolved from memory:

1. **Reads above `1FFFh` during unpause** (`D:2277-2299`): hardware mirror vs open
   bus is not stated in the disassembly. Until settled, the modellable rule is the
   observable one: *no SFX slot's pan is restored on unpause* (§12).
2. **FM3 special-mode `de`** (`D:843-870`) and the code overwritten by
   `cfFM3SpecialMode` (`D:3786-3800`): unreached by shipped streams; not needed for
   parity.
3. **Mod-envelope `82h` operand bytes** (`D:1376-1381`): this spec narrows
   reachability to the standalone-S3 image only (§9.2) — for the locked-on target
   the question is moot; if standalone S3 ever becomes a target, the engine needs
   the installed code-image bytes at `0001h-0020h` from the ROM's driver blob
   (rule-1 compliant source exists).
4. **DAC-track `F2`/`E3` stack shape** (`D:3514-3518` reached from
   `zUpdateDACTrack`'s dispatcher): by stack inspection the three pops return to the
   caller of `zUpdateMusic`, skipping the frame's remaining FM/PSG music tracks on
   the single frame a DAC track terminates. Unconfirmed by trace; phase-B/C item.
5. **1-up save-area aliasing on hardware**: §13 derives that the bit-7 strip makes
   the overlapped SFX slots inert (SFX killed, save consistent); the hardware-level
   consequence (e.g. of an SFX admitted in the same update ordering window) is
   unverified. Keep the derivation, keep the question.
6. **`cmd_StopSEGA` residue** (`FEh` → stop-all next update): harmless by
   inspection, unconfirmed by trace (§14.4).
7. **`F3` on FM1-3 music tracks — resolved.** The ROM-backed per-root fixed-point
   audit in `TestSonic3kSmpsMetaCommandReachability` walks every resolved S&K and
   standalone-S3 DAC/FM and PSG music root against its source bank. Every reachable
   shipped `F3` has operand `E7h` and comes from a PSG `VoiceControl` of `80h`,
   `A0h`, or `C0h`; no DAC/FM root (`16h`, `00h`, `01h`, `02h`, `04h`, `05h`,
   `06h`) reaches it. This deliberately records the byte-audited result rather than
   inferring PSG3 from macro placement: S&K music `1Dh` reaches `F3 E7h` from PSG1
   (`80h`, bank offset `72FBh`). `cfSetPSGNoise` still has the shipped `VoiceControl`
   bit-2 guard, so the retained FM3 bit remains necessary for arbitrary/synthetic
   streams even though the shipped music audit closes that route.
8. **Exact YM/PSG write timing within an update**: out of scope for the
   per-invocation oracle (GAP §1.2 #25); the existing
   `2026-08-22-s3k-ym-write-timing-calculation.md` covers the cycle tier.

New from this spec:

9. **Divider-0 duration wrap** (§3): `zComputeNoteDuration` with divider 0 iterates
   256 times → duration 0 → first `dec` yields FFh. No shipped S3K header uses
   divider 0 (all inspected use `$01`); confirm before modelling.
10. **Engine `E0` write-drop under override**: whether
    `Sonic3kCoordFlagHandler` case 0xE0's `ctx.writeFm` is suppressed for overridden
    tracks the way `zWriteFMIorII` drops it — engine-side verification item.

---

## 19. Claims-digest coverage (S3K rows)

Per CD §14, every S3K-relevant row is either **derived** here (with the owning
section), **contradicted**, or assigned a non-driver owner. "Derived" means the
behaviour is restated from the disassembly above; it does not endorse the row's
original wording where a correction is noted.

| CD row | Verdict | Where / correction |
|---|---|---|
| CAD-01 | derived | §3, §5 (TV 2.1: `DurationTimeout = 1`, accumulator seeded from header byte 5) |
| CAD-02 | derived | §3 (carry extends, service continues) |
| CAD-03 (S3K half) | derived | §3 (`FF 00` preserves the accumulator) |
| CAD-04 | derived | §3 TV 3.4 — zero tempo = never delayed (and trivially "a first tick") |
| CAD-05 | derived, anchors corrected | §1 TV 1.2; the tail is `D:743-758`; **`zDoSpeedUp` is not a label** (CD §15 confirmed); "extra music update every four VInts at 8" confirmed; the engine comment's "(N+1) frames" contradicted |
| CAD-07 | **contradicted** | §1: the locked-on cart installs only the S&K image → reload **5**, and the post-reload re-check decrements to 4 the same frame (`D:488-499`). Reload 6 belongs to the never-installed S3 image |
| CAD-08 | derived | §1(e): no driver has a PAL tempo multiplier; the engine's ×1.2 is engine-only |
| CAD-09 | derived | §1 (order), §6 (same-VInt release before the music pass) |
| CAD-12 (S3K) | derived | §1, §13 (speed-up state, save/restore across 1-up) |
| ADM-02 | derived | §4.2 (no priority anywhere) |
| ADM-03 (S3K) | derived | §6 (channels claimed at dispatch time, in the music phase) |
| ADM-04 (S3K) | derived, refined | §6: voice restored in the same VInt's SFX pass; **frequency only at the music track's next own update** |
| ADM-07 (S3K) | derived | §5 (`zStopAllSound` before every ordinary BGM) |
| ADM-08 | covered by constituents | §4, §6 |
| ADM-09 | derived | §6 (fixed physical slots via `zSFXChannelData`; same-id replacement = re-init at dispatch) |
| REQ-01 | derived, corrected | §14.1: driver plays the **post-toggle** index — first ring after reset is **Left**; reset mechanism is the stop-all wipe (ordinary music yes, 1-up no) |
| REQ-03 (S3K assertion) | derived, reshaped | §14.3: a driver-side ladder exists (E9/`zSpindashRev`), but it is escalating-transpose with reset-on-normal-SFX — not S2's timeout/counter shape. CD §15 open q 3 answered |
| REQ-07 | derived | §10 usage row (`FF 01/02/03` 0/0) + existing reachability note |
| OVR-01 | derived | §13 (one slot, no stack) |
| OVR-02 | derived | §13 (stop-all wipes flag + save area; invincibility/super are ordinary music) |
| OVR-03 (S3K) | derived, mechanism corrected | §13: SFX die by save-copy clobber + bit-7 strip (no key-off, no `zStopSFX` call); new SFX blocked by mailbox clearing while flag = 29h |
| OVR-05 (S3K half) | derived, nuanced | §13e: during the restore *fade-in* SFX are not blocked (queue runs normally); the block is during the jingle |
| OVR-06 | derived | §13 TV 13.1 (`zTempoSpeedupSave`) |
| OVR-07 | derived | §11/§13: restore fade-in is 40h steps, FM1-5 only; PSG **and DAC** stay bit-2 muted until it ends |
| OVR-09 | derived | §13 TV 13.3 (flag = 29h branch, no double save) |
| OVR-10 | derived | §13/§1: ordinary music load wipes `zTempoSpeedup`; normal speed until the 68k ramp resends |
| FADE-01 | derived | §11 (`zHaltDACPSG` at fade start; FM-only fade) |
| FADE-04 (S3K) | derived | §11 (terminal `zStopAllSound`, no final volume step) |
| FADE-05 (S3K half) | derived | §11 (28h steps × delay 6) |
| FADE-06 | not a driver item | 68k request site — GAP §3's request-site catalogue owns it |
| PAUSE-03 | derived | §12 (pan-0 FM1-5, FM6/DAC untouched, PSG silence twice) |
| PAUSE-04 | derived | §12 TV 12.2 |
| PAUSE-05 (S3K) | derived | §12: resume re-sends `B4h` only, music slots FM1-5 (+ the harmless PSG1 row); never SFX slots; no voice reload |
| VOICE-01 (S3K half) | derived | §8 (register traversal, TL rule), §10 `FF 05` (SSG-EG order 90/98/94/9C) |
| VOICE-04 | partially owned | §6/§8 give the admission/upload semantics; the write-count and relative-cycle-spacing clauses are cycle-tier (GAP §2 item 8) — S3KB listening gate stays open |
| VOICE-05 (driver half) | derived | §5/§8: this driver never polls busy — `zWriteFMI/II` are blind writes with a shipped `nop` (`D:584, 609`); the chip-side pacing stays `resolved-by-chip-cores` |
| SEQ-01 | derived, sharpened | §9.2: `80h-84h` are commands (incl. **83h = sustain**, not silence), `85h-FFh` negative deltas that must keep stepping |
| SEQ-02 | derived, reachability corrected | §9.2: the bogus-`bc` fetch exists (`D:1376-1381`) but **no locked-on stream reaches it** — only standalone-S3 `Snd_Minib` does |
| SEQ-03 | derived | §9.2/§9.3: exactly 8 mod-env pointers and 39 (= 27h) vol-env pointers; the "130E (W, 3C)" engine comment is wrong |
| SEQ-05 (S3K part) | covered by constituents | §3, §7, §9 |
| DAC-01 (S3K part) | derived | §15 (per-nibble deltas, asymmetric halves, abort-on-flag, `djnz` pitch loops) |
| DAC-03 (S3K part) | derived, sharpened | §15e: 297 is undocumented and does not match the listing's 303 (`D:4352`, `KM:272`) — divergence recorded, exact hardware-true constant is the listing value |
| DAC-04 | derived | §14.4 (unsigned bytes to `2Ah` at 248 T ≈ 14434 Hz) |
| DAC-05 | derived, anchor corrected | §14.4: **no `zStopSEGAPCM` label exists**; stop = per-byte `zMusicNumber` poll; stop-all first; shipped leaves silence + the `FEh` residue stop-all |
| DAC-06 | covered by constituents | §12, §15 |
| DATA-03 | derived | §4.2: SFX table 173 entries, last four alias `Sound_DB`; S&K maps `DCh` → music index 32h (`Snd_SKCredits` via raw-index path); the S3 build has no `mus_CreditsK` check (`D:1642-1645` conditional) so `DCh-DFh` dispatch as SFX there |
| CHIP-08 | owner noted | §14.4e (engine host-linear rendering is the divergence; chip question out of driver scope) |
| DEF-01 | recorded | §6/§8 semantics here; listening gate + cycle tier own the rest |
| DEF-02 | not a driver item | game-side request gap (CNZ1 miniboss play-in) — request-site catalogue |
| DEF-03 | recorded | §14.4: the ROM boot path has no SEGA screen/sound; the engine's is a documented product addition (S3KD) |
| DEF-11 | recorded | §14.1 covers the ROM rule the develop fix implements; LC-S3K10's stale PASS noted by CD |

S1/S2-only rows (CAD-06, CAD-10, CAD-11, ADM-01, ADM-05, ADM-06, REQ-02, REQ-04,
REQ-05, REQ-06, OVR-04, OVR-08, FADE-02, FADE-03, PAUSE-01, PAUSE-02, VOICE-02,
VOICE-03, SEQ-04, DAC-02, DATA-01, DEF-05..10, DEF-12) are **not applicable to this
game** and are owned by the S1/S2 spec lanes. CHIP-01..07, DATA-02, DEF-04 are
non-driver rows per GAP §3's ownership table.

---

## 20. What the phase-B oracle should assert first (summary)

In risk order (GAP §1.2): (1) the delay-frame rule — §3 TV 3.1 fields
(`DurationTimeout`, `VolEnv`, `ModulationVal*` on carry frames); (2) the §1 TV 1.2
speed-up cadence; (3) §6's admission/release write shapes including the deliberate
hazards (stale-`ix` PSG write, `FFh`, E4's FM1 key-on); (4) §7's every-frame
`A4h/A0h`; (5) §11-§13 event bursts. Break the comparison on purpose before trusting
its first green (project memory; GAP phase B).
---

## 21. Review record (adversarial re-derivation, 2026-08-30)

Second-pass sources-closed review: every behaviour statement re-read against the
disassembly, and 30+ of the test vectors recomputed by hand from
`docs/skdisasm/Sound/Z80 Sound Driver.asm`, the `Sound/Music` / `Sound/SFX`
sources, `_smps2asm_inc.asm`, `sonic3k.asm`, `sonic3k.constants.asm` and
`sonic3k.macros.asm`. Engine files were opened only to check the spec's (e)
claims. Corrections were applied in place above; this section is the ledger.

### Refuted and corrected

| # | Claim (as previously written) | Verdict | Evidence |
|---|---|---|---|
| 1 | §1 boot: "~65k busy loop" | refuted | `D:527-533`: `dec c` takes `c` 0→FFh, so `jr z, .loop` falls through after a single 256-iteration `djnz` |
| 2 | §4.1/TV 4.4/TV 14.2: continuous gate "`(v+3) & 0Fh == 0`", v ≡ 13 (mod 16) | refuted | `K:180655-180660`: `move.b (V_int_run_count+3).w,d1; andi.b #$F; bne` — `+3` is the byte address of the longword's LSB; the gate is `v & 0Fh == 0` |
| 3 | TV 9.1: nBb2 = byte `AAh`?, PSG table value 239 = `EFh` | refuted | `INC:31-46`: nBb2 = `81h`+34 = `A3h`; `D:2799-2815` index 34 = 932.17 Hz → `round(223721.56/1864.34)` = 120 = `78h`; 239 is index 22 (468.03 Hz) |
| 4 | §8(a): E6 saturation "overflow → 0, underflow → 7Fh" | refuted (TV 8.2 was already right) | `D:3160-3172`: `jp pe` (signed overflow) → 7Fh quietest; plain negative → `xor a` = 0 loudest |
| 5 | §13: "the 1-up's FM1 ends with `E2 FF`" | refuted | `Music/1UP (Sonic & Knuckles).asm:79`: `smpsFade` (E2 FF) ends `Snd_1UP_DAC`; every FM track ends `smpsStop` |
| 6 | §13/TV 13.4: `\|= 84h` = "playing + resting"; "bit 4 was set by 84h"; "no A4h/A0h until next attacked note" | refuted | 84h = bits 7+2 (`D:2748-2764`; the FM-only `res 2` confirms bit 2); bit 4 is untouched, so a mid-note FM track resumes §7's every-frame frequency writes in the same update's walk |
| 7 | §10 E5 usage "31/41", E6 usage "1/0"; TV 10.2's `smpsFMAlterVol $FF` as an E5 example | refuted | `INC:635-648`: one-arg `smpsFMAlterVol` assembles to `E6`; two-arg (`E5`) sits in 3 music files (40 instances), 0 SFX; one-arg ≈503 music + 48 SFX instances; `smpsAlterVol` only in S3 Credits |
| 8 | §10 E2 row parenthetical "(Countdown ×5, S&K Credits…)" | composition corrected (the 7/0 file count stood) | greps: `smpsNop` ×7 (Countdown ×2, Chaos Emerald, Game Complete ×2, Credits S&K ×2), `smpsFade` in both 1UP songs |
| 9 | TV 6.1: stale `ix` "points at the last PSG music slot processed" | refuted | after an ordinary load the last `ix` user is `zStopAllSound`'s walk over `zFMDACInitBytes` (`D:2477-2497`): `1Fh+06h` is positive → no stale write, only the unconditional `FFh` |

### Clarified (ambiguous or incomplete, not wrong in effect)

| # | Item | Fix |
|---|---|---|
| 10 | §9.1 prose read as if `hl += accumulator` / `ModulationSteps--` were speed-gated | they run on every post-wait update (`D:1301-1321` `.mod_sustain`); only the accumulator advance is |
| 11 | §7(a) note-fill expiry listed mid-sequence | `jp z, zKeyOffIfActive` ends the update — no frequency write on the expiry frame (`D:786-791`) |
| 12 | §4.1 "the 68k never reads driver RAM" | `Play_SFX` reads its own `zSFXNumber0` mailbox (`K:1493-1500`) |
| 13 | TV 9.4 "EntryPoint 6 bytes" | 7 bytes (`di, di, im 1, jp`) + `F2h` filler at `0007h`; the `000Dh` = `09h` conclusion already held |
| 14 | TV 14.3 ladder | assumes re-requests within the running stream; the stream's own `FF 07` resets the rev when the `smpsLoop $00,$18` body exhausts |

### Independently recomputed and confirmed (selection)

- TV 1.1/1.2: PAL reload 5 with same-frame re-check decrement (`D:488-499`);
  speed-up 5-per-4 cadence, timeout 6,4,2,0 (`D:743-758`).
- TV 2.1/2.2: FM1 track image vs `Music/MHZ1.asm` field by field; wipe extent
  `(1FA0h−1C0Dh−1)+34h` → 1C0Dh-1FD3h (`D:2461-2470`); mailboxes/queue outside it.
- TV 3.1-3.4: accumulator arithmetic for tempos 20h/39h/00h (carries on updates
  7/15/23… and 4,8,13,17,22); divider multiply 18h×2=30h, 60h×3→20h.
- TV 4.1-4.3, 5.1-5.3: 3× queue cycle; dispatch boundaries incl. `DCh` → raw
  index 32h = `Snd_SKCredits`; stop-all burst order (ids 6,0,1,2,4,5, +2 offsets
  on channel 6); `B6h = C0h`; AIZ1 `smpsHeaderVoiceUVB`.
- TV 6.2-6.4: channel-id maps (`D:2109-2163`); ring release restore; E4's
  spurious `28h = 80h` FM1 key-on (`D:2656-2662` unguarded `zKeyOnOff`).
- TV 7.1-7.4: nF2 = `9Eh` → 320 = `140h`, writes `80h, 14h, 92h` then `9Fh`;
  nE5 → block 5 rem 4 → `A5h=2Bh`, `A1h=2Dh`, `28h=F5h`; SavedDuration reuse
  (`D:976-979`); bit-6 sustain never cleared shipped (`D:1170-1176`).
- TV 8.1-8.3: ring voice upload byte-for-byte incl. TL rule (`23h,05h,23h,05h`)
  and pan `B4h=40h`; E6 saturation both directions; EC `VolEnv` 0→FFh wrap.
- TV 9.3-9.6: ModEnv_00 offsets and `83h` sustain; `000Dh` image byte = `09h`
  (`add hl,bc`); VolEnv_0D = `02,83h`; VolEnv_06 `80h` reset-loop; exactly 8
  mod-env and 39 vol-env pointers; `VolEnv_25` is shorter in the S&K image.
- TV 10.1/10.3: Countdown `FF 00` 40h/20h/10h/08h in file; F8 stack 2Eh→2Ch→2Ah.
- TV 11.1/12.1: first fade TL write on update 6, `zStopAllSound` on update 240
  with no final step; pause burst order incl. double `zPSGSilenceAll` and
  key-offs `28h = 0..5`.
- TV 13.1-13.3: `zPlayMusic` 1-up branches (`D:1717-1783`), `zUpdateMusic` gate
  (`D:662-679`), deferral boundary raw id < 32h.
- TV 14.1/14.2/14.4, 15.1-15.3: ring toggle-then-play (boot comment agrees:
  left first); skid `nBb6,$16` + `FC`; SEGA `b = 12` → 248 T/byte ≈ 14434 Hz,
  `FEh` residue, S&K clears `PlaySegaPCMFlag` before streaming; DAC loop total
  303 T re-summed from `D:4299-4352`; abort-on-retrigger re-entry shape.
- §9.2 reachability: `smpsModChange` exactly in CNZ1/CNZ2 (`$02`), LBZ1/LBZ2
  (`$01`), Miniboss (Sonic 3) (`$04/$06/$07/$08`); S&K music pointer *and* bank
  tables both map the second miniboss id to `Snd_Minib_SK`.
- Usage-column spot checks (file counts): E4 1/0 (all 48 instances in Credits
  S&K), E7 37/35, E8 16/0, EC 20/23, F0 55/98, F3 33/36 (`smpsPSGform`),
  F5 38/23, FA 3/3, FC 0/30, `FF 00` = Countdown + S&K Credits only.
- SFX pointer table: 169 real entries + 4 `Sound_DB` aliases = 173; 68k requests
  only `sfx_RingRight` (no `sfx_RingLeft` site).
- Engine (e)-claims: `DacData` S3K `baseCycles = 297`; `SmpsSequencer:1308`
  comment citing the nonexistent `zDoSpeedUp` label and its once-per-frame
  timeout model; PAL `multiplier = 1.2`; OVERFLOW skip-tick;
  `Sonic3kCoordFlagHandler` EB reading four parameter bytes, E5 skipping its
  first byte; `Sonic3kSmpsSequencerConfig` tempo/volume/fade constants;
  `Sonic3kAudioProfile` FRAME_MULTIPLY/8, `blocksSfxDuringMusicRestoreFadeIn()
  = false`, `executeFadeOut(0x28, 6)`.

No claim traceable to the reverted parity branch was found; §18's open
questions remain open.
