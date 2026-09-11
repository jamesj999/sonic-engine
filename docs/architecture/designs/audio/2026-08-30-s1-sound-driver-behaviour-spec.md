# Sonic 1 sound driver behaviour spec (SMPS 68k Type 1b, shipped `FixBugs = 0`)

**Date:** 2026-08-30
**Branch:** `feature/ai-sdre-spec-s1` (from `feature/ai-sound-driver-re`, with
`feature/ai-sdre-gaps`, `feature/ai-sdre-refute-structural-fit` and
`feature/ai-sdre-refute-oracle-plan` merged)
**Kind:** design (driver behaviour specification — the S1 lane of the sound-driver RE
workflow's per-game spec phase, gap analysis §4.1)
**Inputs:**

| Key | Document |
|---|---|
| MAP | `docs/architecture/research/audio/2026-08-30-s1-sound-driver-routine-map.md` |
| GAP | `docs/architecture/designs/audio/2026-08-30-sound-driver-re-gap-analysis.md` |
| CD | `docs/architecture/audits/audio/2026-08-30-smps-behaviour-claims-digest.md` |
| EM | `docs/architecture/audits/audio/2026-08-30-smps-engine-architecture-map.md` |

**Source rule (sources-closed).** Every ROM statement below was derived from the Sonic 1
disassembly read for this spec: `docs/s1disasm/s1.sounddriver.asm` (all 2867 lines),
`s1.sounddriver.ram.asm`, `sound/z80.asm`, `sound/_smps2asm_inc.asm`, the song/SFX data
under `sound/`, and the 68k-side callers in `sonic.asm` / `_Constants.asm` /
`_inc/Queue Sound Routines.asm` / `_inc/PauseGame.asm`. No SMPSPlay, libvgm, GPGX sound
code, the reverted `feature/ai-smps-transaction-parity` branch, or third-party SMPS
documentation was opened. Engine code was read only to describe what the engine does
today. MAP statements are cited only where re-verified here or where they concern song
data bytes spot-checked against the ROM by the MAP lane (marked "MAP-verified").

**Anchor notation** (paths relative to `docs/s1disasm/`): `SD:` = `s1.sounddriver.asm`,
`RAM:` = `s1.sounddriver.ram.asm`, `Z80:` = `sound/z80.asm`, `S:` = `sonic.asm`,
`C:` = `_Constants.asm`, `INC:` = `sound/_smps2asm_inc.asm`, `Q:` =
`_inc/Queue Sound Routines.asm`, `P:` = `_inc/PauseGame.asm`. `loc_`/`sub_` names are ROM
addresses, not line numbers. Engine anchors are `<Class>.java:<line>` at the merged head.

**FixBugs.** `FixBugs = 0` (`S:20`), `FixMusicAndSFXDataBugs = FixBugs` (`SD:2637`).
Every statement below is the shipped (`else`/absent) branch; §16 lists the sites a
shipped stream actually reaches.

**Test-vector conventions.** "U0, U1, ..." number consecutive `UpdateMusic` passes; U0 is
the pass whose `PlaySoundID` dispatches the request under discussion. "I" / "II" mark the
YM2612 part a register write goes to (`$4000/$4001` vs `$4002/$4003`); PSG bytes go to
`$C00011`. Register values are the bytes the driver writes; chip-internal masking (e.g. a
TL byte with bit 7 set) is out of scope. All vectors assume NTSC and are derived by hand
from the cited lines — none was measured from a fixture.

---

## 1. Invocation boundary and cadence

### 1.1 ROM behaviour

- `UpdateMusic` (`SD:147`, `sub_71B4C`) is called once per V-int from `VBlank_Music`
  (`S:681-682`). Every VBlank mode reaches it, including lag frames (`VBlank_Lag`
  branches back to `VBlank_Music`: `S:716`, `S:720`, `S:748`), the paused mode, and the
  special stage. There is **no PAL branch anywhere in the driver** — no region test, no
  double update, no tempo compensation (whole-file read; CD CAD-08 S1 half derived).
- A **deferred call** replaces the V-int call when `f_doupdatesinhblank` is set: the LZ
  water workaround in `VBlank_Levels` sets the flag and pops the return into
  `VBlank_Music` (`addq.l #4,sp` — "postpone updating the sound driver as well",
  `S:846-854`), so the V-int pass is **skipped**; the H-int handler then clears the flag,
  performs the deferred screen updates, and runs the frame's single `UpdateMusic` pass
  (`S:1050-1064`). Cadence is unchanged — one pass per frame — only the pass's position
  inside the frame moves. How often the deferral fires during LZ play is a game-state
  question (§18 q4).
- Entry sequence (`SD:148-165`): `stopZ80`, spin on the bus-request bit, then test
  `zDAC_Status` bit 7 (`$A01FFD`, `Z80:14`). If the Z80 is mid-sample-write ("not
  accepting"), release the bus and restart the entry. This is the only place the 68k
  waits on the Z80.
- One pass, in order (`SD:170-272`): `f_voice_selector := 0`; pause check (§12) —
  a pause/unpause frame does **nothing else**; tempo countdown (§3); fade-out (§11);
  fade-in (§11); queue cycle if the word at `v_soundqueue0` is non-zero (§4, FixBugs #1);
  `PlaySoundID` if `v_sound_id != $80` (§4); then the track walk **music before SFX**
  (CD CAD-10 derived): music DAC, FM1–6, PSG1–3; `f_voice_selector := $80`; SFX FM3–5,
  SFX PSG1–3; `f_voice_selector := $40`; special FM4, special PSG3. Only tracks with
  `PlaybackControl` bit 7 set are updated. Finally `DoStartZ80` (`SD:270-272`).
- **Stack-tamper returns** change the shape of a pass:

  | Routine | sp adjust | Effect |
  |---|---|---|
  | `Sound_PlayBGM` `.locdblret` (`SD:959-961`) | +4 | a song-load pass runs **no track updates** and **skips `DoStartZ80`** — the Z80 stays bus-requested until the next `startZ80` anywhere; DAC output stalls for that interval |
  | `PlaySegaSound` (`SD:747-748`) | +4 | same early return, after the 68k busy-wait (§14.3) |
  | `cfFadeInToPrevious` (`SD:2223-2225`) | +8 | returns into the track loop; runs its own `startZ80`, so the rest of that pass updates with the Z80 running |
  | `cfStopTrack` (`SD:2562`), `cfStopSpecialFM4` (`SD:2309`) | +8 | skip the rest of the stopped track's update |
  | `NoteTimeoutUpdate` (`SD:469`, `475`) | +4 | note-fill expiry skips modulation + frequency update for that track this pass |
  | `DoModulation` (`SD:484`, `516`) | +4 unless a step fired | the post-modulation frequency write happens only on frames where a modulation step produced a new value |

- **Oracle tick definition:** one tick = one complete `UpdateMusic` pass (normal, pause,
  or load-frame shape), in V-int order; a frame whose pass was deferred to H-int still
  contributes exactly one tick. The invocation boundary for a RAM comparison is the `rts` of `DoStartZ80` (or
  the tamper return).

### 1.2 State

Reads/writes `v_main_tempo_timeout`, `f_voice_selector`, `f_pausemusic`,
`v_fadeout_counter`, `f_fadein_flag`, `v_soundqueue0..2`, `v_sound_id`,
`f_updating_dac` (cleared after the DAC track, `SD:212`), and every track's
`PlaybackControl` bit 7.

### 1.3 Observable effect

The pass order fixes the write order within a frame: queue/dispatch bursts (song load,
SFX init) precede all track-generated writes; music-track writes precede SFX-track
writes; special-SFX writes come last. A song-load pass emits only the load burst (§5).

### 1.4 Test vectors

**TV1.1 — load-frame skip.** Input: GHZ (`$81`) in `v_soundqueue0` before U0; a music
track note due on U0. Expected: U0 emits the §5 load burst and **no** track writes (the
note that was due is discarded with the old track RAM); the Z80 bus is held from U0's
`stopZ80` until the next `startZ80` (first candidate: V-int mode code of the next
frame). Derivation: `.locdblret` tamper `SD:959-961` skips both the track walk and
`DoStartZ80`.

**TV1.2 — lag frame still ticks.** Input: a lag frame during GHZ play. Expected: the
driver pass runs normally (durations decrement, due notes are emitted). Derivation:
`VBlank_Lag`'s exits branch to `VBlank_Music` (`S:716,720,748`), which calls
`UpdateMusic` unconditionally.

### 1.5 Engine today

`SmpsSequencer.advanceBatch` runs `processTempoFrame` whenever a sample-domain 1/60 s
counter elapses, phase-free of the outer presentation frame (EM §3.2 step 6); commands
drain once per outer frame (EM §3.2 step 3). Adaptation point GAP §1.2 #1 (risk high for
any invocation-keyed oracle): frame-locked boundary + one `ServiceEvent` per ROM pass.
The S1 load-frame skip is a per-game cadence fact the S1 profile must expose; the H-int
deferral moves a pass within the frame without changing the tick count, so it matters only
to sub-frame write timing, and its trigger needs a game-side predicate, not a driver
constant (GAP §1.2 #1). The existing S1 `MATCH` bypassed the live clock
(`S1OpenGgfAudioCapture` calls `advanceBatch` directly, EM §5.1), so live-graph phase is
unmeasured (GAP §5 last item).

---

## 2. Driver RAM and track struct (comparison vocabulary)

### 2.1 ROM layout

`v_snddriver_ram` = `$FFFFF000` (`_Variables.asm:114`); `a6` holds the base inside the
driver (`SD:170`). Globals at `$00-$2C` (`RAM:33-64`), 18 tracks × `$30` bytes at
`$40-$39F` (`RAM:66-107`; music DAC `$40`, FM1-6 `$70..$160`, PSG1-3 `$190..$1F0`, SFX
FM3-5 `$220..$280`, SFX PSG1-3 `$2B0..$310`, special FM4 `$340`, special PSG3 `$370`),
1-up backup `$3A0-$5BF` (`RAM:109`). Track struct fields and `PlaybackControl` bits: MAP
§2.2, re-verified against `RAM:1-31` and the driver's reads/writes during this spec's
full read. The gosub stack lives *inside* the struct: `StackPointer` starts at `$30`;
the first `$F8` push lands at offset `$2C`, the second at `$28`, overlapping
`LoopCounters[8..11]` then `[4..7]` (`SD:2611-2616`, `RAM:29-30`; §18 q9).

### 2.2 Field registry classification (engine mapping per EM §2.3)

Driver globals:

| ROM field (offset) | Engine | Class |
|---|---|---|
| `v_sndprio` ($00) | none (per-sequencer priority; `SmpsAdmissionContext.priorityBefore/After` diagnostic) | **absent** → adaptation GAP §1.2 #5; compared once added |
| `v_main_tempo_timeout` ($01) | `SmpsSequencer.tempoAccumulator` | compared |
| `v_main_tempo` ($02) | `tempoWeight`/`normalTempo` | compared |
| `f_pausemusic` ($03) | none (presentation `SILENT`) | **absent** → GAP §1.2 #14 |
| `v_fadeout_counter` ($04), `v_fadeout_delay` ($06) | `FadeState` | compared (shape verified §11) |
| `v_communication_byte` ($07) | `commData` | compared |
| `f_updating_dac` ($08) | implicit (track type) | derived |
| `v_sound_id` ($09), `v_soundqueue0..2` ($0A-$0C) | command queue drained per outer frame | **absent** → GAP §1.2 #4 |
| `f_voice_selector` ($0E) | implicit (sequencer role) | derived |
| `v_voice_ptr` ($18), `v_special_voice_ptr` ($20) | program `voicePtr` / materialised copies | derived |
| `f_fadein_flag` ($24), `v_fadein_delay/counter` ($25/$26) | `FadeState` | compared |
| `f_1up_playing` ($27) | override stack presence | derived → GAP §1.2 #15 boundary |
| `v_tempo_mod` ($28), `v_speeduptempo` ($29), `f_speedup` ($2A) | `normalTempo`, config `SPEED_UP_TEMPOS`, `speedShoes` | compared / Java-resident table (§17) |
| `v_ring_speaker` ($2B) | `AudioManager.ringLeft` (`AudioManager.java:76`) | adaptation GAP §1.2 #19 (move into driver RAM; reset sites §14.1) |
| `f_push_playing` ($2C) | none | **absent** → GAP §1.2 #19 |
| `v_1up_ram_copy` ($3A0) | parked live driver (override stack) | not-compared until GAP §1.4 item 2 decides |

Track fields: mapping table EM §2.3 applies to S1 unchanged; classifications —
`compared`: `PlaybackControl` bits 7/4/2/1 (`active`,`tieNext`,`overridden`,`resting`),
`TempoDivider`←`dividingTiming`, `Transpose`←`keyOffset`, `Volume`←`volumeOffset`,
`AMSFMSPan`←recomposed `(pan&$C0)|(ams<<4)|fms`, `VoiceIndex`←`voiceId`/`instrumentId`,
`VolEnvIndex`←`envPos` (ROM keeps the index; engine also keeps decoded `envValue` —
compare the index), `StackPointer`+stack←`returnSp`/`returnStack` re-packed into the
overlapping region, `DurationTimeout`←`duration`, `SavedDuration`←`scaledDuration`,
`Freq`←`baseFnum(+baseBlock<<11)` (detune-free; see §18 q5 for which stores are
detuned), `NoteTimeout(Master)`←`fillCounter`/`fill`, modulation
wait/speed/delta/steps/val←`mod*` counters, `Detune`←`detune`, `PSGNoise`←
`psgNoiseParam`; `derived`: `VoiceControl` (recomputed at write time),
`FeedbackAlgo`←`voiceData[0]`, `ModulationPtr`←`pos` of the owning `$F0`,
`DataPointer`←`pos`+program base, `SavedDAC`←`note`; `engine-only`:
`voiceScratch`, `forceRefresh`, `modStep*` scratch, `dacMuted`; `not-compared`:
struct pad byte $03, `PlaybackControl` bits 0/5/6 (written `$80` by SFX headers, never
read: §6.2).

### 2.3 Test vectors

**TV2.1 — power-on.** Work RAM is zeroed by `GameInit` (`S:409-413`), so
`v_sound_id = 0`; the first driver pass takes the `beq.w StopAllSound` branch of
`PlaySoundID` (`SD:679`) and initialises the chips through the §5 stop-all burst.
Expected RAM after: everything `$000-$38F` zero except `v_sound_id = $80`
(`SD:1480`); expected writes: the §5 stop-all burst.

**TV2.2 — GHZ track init image.** After TV1.1's U0: `v_voice_ptr` = song base + header
word 0; `v_tempo_mod = v_main_tempo = v_main_tempo_timeout = 3`; DAC+FM tracks
(`Mus81` header `Chan $06,$03`, `Mus81 - GHZ.asm:4-15`): `PlaybackControl = $80`,
`VoiceControl` = 6,0,1,2,4,5 (`FMDACInitBytes` `SD:964-966`), `TempoDivider = 1`,
`StackPointer = $30`, `AMSFMSPan = $C0`, `DurationTimeout = 1`, `Transpose:Volume` =
`$F4:$12`, `$00:$0B`, `$F4:$14`, `$F4:$08`, `$F4:$20` for FM1-5; PSG1-3:
`VoiceControl` = `$80,$A0,$C0`, `VoiceIndex` = 3, 6, 4 (`fTone_03/06/04`); every other
field zero. Derivation: `SD:794-906` with the RAM cleared by `InitMusicPlayback`
(`SD:1498-1502`).

### 2.4 Engine today

`SmpsTrackSnapshot`/`SmpsSequencerSnapshot`/`SmpsDriverSnapshot` capture every engine
field (EM §5.1); `S1AudioFieldRegistry` names 29 mappings. Adaptation GAP §1.2 #23: the
per-game registry with the classification above, plus stack/loop-counter re-packing
(GAP §1.2 #24 — model only overlaps shipped data reaches; §18 q9 is the check).

---

## 3. Main tempo and durations

### 3.1 ROM behaviour

- Countdown model: every pass decrements `v_main_tempo_timeout`; on zero, `TempoWait`
  (`SD:1549-1561`) reloads it from `v_main_tempo` and adds 1 to `DurationTimeout` of
  **all ten music tracks, playing or not** (the loop tests no playing bit,
  `SD:1551-1558`). The track walk still runs — S1 has no "skip frame"; a hold frame is
  a net no-op only because each running track then decrements the same byte. SFX and
  special tracks are never held (CD CAD-02's S2/S3K claim is **n/a to S1**; the S1
  contrast — countdown, reset on change — is CD CAD-03, derived here).
- `T = 1` freezes music (every pass reloads and holds; the include refuses it,
  `INC:194-196` `convertMainTempoMod`); `T = 0` wraps through `$FF` → one hold per 256
  passes.
- Tempo writes: song load `SD:812-813` (header byte 5, or `v_speeduptempo` if
  `f_speedup`); `$EA` sets tempo **and resets the countdown** (`SD:2256-2258`); `$E2`/
  `$E3` set both (§13.2). CD CAD-03 anchor ✓ (`UpdateMusic` `SD:174-176`).
- Per-track duration (`SetDuration` `SD:411-426`): `d0 = raw` added to itself
  `divider-1` times in byte arithmetic → `raw*divider mod 256`; divider 1 → unchanged;
  **divider 0 → 255 additions → 0 → treated as 256 frames** by the countdown. Stored to
  both `SavedDuration` and `DurationTimeout`. Header byte 4 sets the divider for all
  tracks; `$E5` one track; `$EB` all ten music tracks.
- A note with no following duration byte reuses `SavedDuration` (`SD:384-387`).

### 3.2 State

`v_main_tempo(_timeout)`, all music `DurationTimeout`; per track
`TempoDivider`, `SavedDuration`, `DurationTimeout`.

### 3.3 Observable effect

None directly; a hold stretches the note grid by one frame per `T` passes, shifting
every subsequent key-on/off and DAC write by whole frames.

### 3.4 Test vectors

**TV3.1 — GHZ hold cadence.** GHZ loads at U0 (`smpsHeaderTempo $01,$03`). U1: timeout
3→2 (first note parse — see TV3.2); U2: 2→1; U3: 1→0 → `TempoWait`: reload 3, all ten
music `DurationTimeout` += 1; holds recur at U6, U9, … Derivation: `SD:174-176`,
`SD:1549-1561`, seed at `SD:812-813`.

**TV3.2 — first-note latency.** Any song load: every track's `DurationTimeout = 1`
(`SD:847`, `897`), and the load pass skips the walk (TV1.1) — so the first byte of every
track is parsed exactly on U1, one pass after the load pass (CD CAD-01 S1 half derived;
game-visible latency additionally depends on where in the frame the request was queued,
§18 q10).

**TV3.3 — divider stretch.** GHZ DAC opening `nRst $08` (`Mus81 - GHZ.asm:451`) with
divider 1, tempo 3: duration 8 set at U1; +1 on U3/U6/U9/U12; reaches 0 at U13 — 12
passes for 8 raw frames (ratio 3/2). Derivation: interleave the TV3.1 holds with one
decrement per pass.

### 3.5 Engine today

`TempoMode.TIMEOUT` reproduces the S1 shape — always tick, extend durations on expiry
(`SmpsSequencer.java:1266-1281`, re-read for this spec) — **except** the engine's
extension loop gates on `t.active && t.duration > 0`, while the ROM increments all ten
music slots unconditionally: a RAM-level deviation the S1 comparison will see on stopped
tracks (GAP §1.2 #2). SFX bypass the extension in both (ROM: only music slots are in the
loop). Divider-0 wrap: engine behaviour unverified (GAP §1.2 #3 assigns the check to S2
but the same multiply is S1's).

---

## 4. Queue, dispatch, priority/admission

### 4.1 ROM behaviour

- Game-side entry: `QueueSound1/2/3` write single bytes to `v_soundqueue0/1/2`
  (`Q:10-41`); slot 2 is dead — `UpdateMusic` tests only the **word** at
  `v_soundqueue0` (`SD:193-196`, FixBugs #1) and `InitMusicPlayback` preserves only that
  word (`SD:1493`, `1509`, FixBugs #12). A second write to the same slot in one frame
  overwrites the first. IDs `< $81` are discarded at cycle time (`SD:647-648`).
- `CycleSoundQueue` (`SD:637-672`; the original name `Sound_Play` survives as the
  comment `SD:636`): for each slot in order — read+clear; skip if `< $81`; if
  `v_sound_id != $80` already holds an accepted request, **write this ID back to slot
  0** for the next frame (one accepted request per pass; a second same-pass request is
  serialised, a third overwrites the second in slot 0); else look up
  `SoundPriorities[id-$81]` (`SD:131-138`, ROM `$71AE8`, MAP-verified) and accept iff
  `>=` the running priority `d3` (seeded from `v_sndprio`). After the loop
  `v_sndprio := d3` **unless d3 has bit 7 set** (`SD:666-668`) — bit 7 means "accept
  but never store".
- **The global priority gate lives here, not in `Sound_PlaySFX`** — a losing request is
  rejected before any track init (CD ADM-01's S1 anchor `Sound_PlaySFX :977` is the
  wrong routine for the gate; the correct anchor is `CycleSoundQueue` `SD:637-672`.
  The rest of ADM-01's S1 half — channels taken at admission, latch cleared when an SFX
  track stops — is derived in §6).
- Priority decay sites (nothing else lowers it): `cfStopTrack` in the SFX phase only
  (`SD:2504-2506`), `StopSFX` (`SD:1227`), the `Sound_PlaySFX` gate exits
  (`SD:1085-1087`), the 1-up load path (`SD:775`, `785`), `StopAllSound` (RAM clear).
  `InitMusicPlayback` deliberately preserves it across a song load (`SD:1489`, `1505`).
- `PlaySoundID` (`SD:676-712`): `0` → `StopAllSound`; `$01-$7F` ignored; `$81-$9F` →
  `Sound_PlayBGM` (FixBugs #2: `$94-$9F` index past `MusicIndex` — crash; blocked from
  the sound test by `LevSel_NoCheat` `S:2222-2229`); `$A0-$CF` → `Sound_PlaySFX`;
  `$D0-$DF` → `Sound_PlaySpecial` (FixBugs #3: only `$D0` exists, `SpecSoundIndex`
  `SD:2740-2742`); `$E0-$E4` → `Sound_ExIndex` (`SD:721-727`: FadeOut, Sega, SpeedUp,
  SlowDown, StopAll); `$E5-$FF` ignored. Normal and special SFX use **disjoint pointer
  tables** — `SoundIndex` (`SD:2686-2735`, `$78B44`) vs `SpecSoundIndex` (`$78C04`)
  (CD REQ-05 derived; the engine's historical adjacency-dispatch defect DEF-12 is a
  divergence from this, recorded fixed in CL).
- Priority bytes (`SD:131-138`): `$81-$9F` = `$90`; `$A0` = `$80`; `$AA` = `$68`;
  `$AE`,`$B1`,`$B3`,`$C0` = `$60`; `$BF` = `$7F`; all other `$A1-$CF` = `$70`;
  `$D0-$DF` = `$80`; `$E0-$E4` = `$90`.

### 4.2 State

`v_soundqueue0..2`, `v_sound_id`, `v_sndprio`.

### 4.3 Observable effect

Which request reaches a loader, and on which pass — every downstream write burst shifts
with the one-per-pass serialisation.

### 4.4 Test vectors

**TV4.1 — two requests in one frame.** Precondition `v_sndprio = 0`. Game writes
`$B5` (ring, priority `$70`) to slot 0 and `$A0` (jump, `$80`) to slot 1 in the same
frame. U0: slot 0 accepted (`$70 >= 0`), `v_sound_id = $B5`; slot 1 sees
`v_sound_id != $80` → `$A0` written back to slot 0; after the loop `v_sndprio = $70`
(bit 7 clear); `PlaySoundID` starts the ring. U1: `$A0` accepted (`$80 >= $70`), and
because `$80` has bit 7 set `v_sndprio` **stays `$70`**; jump starts one pass late.
Derivation: `SD:641-672`, priority table row `$A0`/`$B0`.

**TV4.2 — priority rejection without track init.** While `$BF` (Get Continue,
`$7F`) is the stored priority: request `$AE` (fireball, `$60`). U0: `$60 < $7F` → slot
cleared, `v_sound_id` stays `$80`, **no** `Sound_PlaySFX` call, no track or chip effect;
`v_sndprio` unchanged. Derivation: `SD:655-659`.

### 4.5 Engine today

No queue bytes, no one-per-pass serialisation, no global latch: all pending presentation
commands drain in submission order at the outer frame (EM §3.2 step 3); priority is
per-SFX-sequencer with per-channel arbitration (EM §1.4). Adaptations GAP §1.2 #4
(ROM-shaped mailbox in `SmpsDriver`, write-back rule) and #5 (driver-global `sndPrio`
gating whole-request admission in `SmpsRequestAdmissionPolicy`, cleared at the ROM
sites above; priority table read from ROM `$71AE8` — §17).

---

## 5. Music load and silence bursts

### 5.1 ROM behaviour

`Sound_PlayBGM` (`SD:754-961`), non-1-up path (§13 covers `$88`):

1. `f_1up_playing := 0`, `v_fadein_counter := 0` (`SD:790-791`) — an in-progress
   fade-in is abandoned (its flag dies in the RAM clear).
2. `InitMusicPlayback` (`SD:1486-1543`): save `v_sndprio`, `f_1up_playing`,
   `f_speedup`, `v_fadein_counter`, and the `v_soundqueue0/1` word; zero `$000-$21F`;
   restore them; `v_sound_id := $80`; then `FMSilenceAll` + `PSGSilenceAll`
   (FixBugs #13 — silences even SFX-owned channels). Note `v_ring_speaker`,
   `f_push_playing`, `v_communication_byte`, `f_pausemusic` and the fade-out state are
   all reset here.
3. `v_speeduptempo := SpeedUpIndex[id-$81]` (`SD:795-797`); the table has 8 entries
   (`SD:74-93`, `$71A94` = `07 72 73 26 15 08 FF 05`, MAP-verified) — IDs `$89+` read
   the first bytes of `MusicIndex` (shipped behaviour, warned at `SD:70-72`).
4. Header parse (`SD:801-816`; layout `INC:306-356`): word 0 voice-bank offset →
   `v_voice_ptr`; byte 2 FM+DAC count; byte 3 PSG count; byte 4 divider; byte 5 tempo →
   `v_tempo_mod` and (unless `f_speedup`) `v_main_tempo(_timeout)`. FixBugs #4 (register
   init order, zero-FM songs) is unreachable — every shipped header declares `$06` or
   `$07`.
5. FM/DAC track init (`SD:838-854`; TV2.2), PSG init (`SD:885-906`; the per-track byte between the volume and the envelope id —
   the frequency-envelope byte smps2asm emits — is read and dropped, `SD:903`).
6. FM6 handling (`SD:856-882`): if byte 2 == 7 (only `Mus89` Special Stage and `Mus93`
   Get Emerald): write `$2B := 0` (I) — DAC **disabled**. On the 68k side only `StopAllSound`
   re-enables it; the Z80 also writes `$2B := $80` whenever it starts a DPCM sample
   (`Z80:103-106`), but those two songs never trigger that — their `smpsHeaderDAC` label
   aliases the stopped PSG3 data, so the DAC track reaches `$F2` without ever writing a
   sample byte (`Mus89 - Special Stage.asm:171-174`, `Mus93 - Get Emerald.asm:73-77`)
   (the S1 DAC-disable latch; §14.3 note, CD OVR-08 context).
   Otherwise: `$28 := $06` (key-off FM6), TL `$7F` to FM6's operators
   (`$42,$4A,$46,$4E` II — note order op1,op3,op2,op4), `$B6 := $C0` (II).
7. SFX re-marking (`SD:909-942`): every playing SFX track sets bit 2 on the music track
   owning its channel (`SFX_BGMChannelRAM` `SD:1093-1101`); a playing special FM4/PSG3
   track marks music FM4/PSG3. **SFX survive an S1 song change** (CD ADM-06 derived,
   with two corrections: the priority latch survives via step 2's save/restore, but
   `f_push_playing` does **not** — it is cleared by the RAM zero; and special SFX also
   survive and re-mark).
8. `FMNoteOff` on music FM1-6 and `PSGNoteOff` on music PSG1-3 (`SD:944-957`) — both
   honour bit 2, so SFX-owned channels are not keyed off here.
9. Tamper return (TV1.1).

`StopAllSound` (`SD:1461-1482`, id `0` or `$E4`): `$2B := $80` (I, DAC on), `$27 := 0`
(I, timers off / FM3 normal), clear `$390` bytes — **all RAM except offsets `$20-$2F`
of the special PSG3 track** (FixBugs #11: its `VoicePtr`/`LoopCounters` survive),
`v_sound_id := $80`, `FMSilenceAll`, `PSGSilenceAll`.

`FMSilenceAll` (`SD:1426-1454`) write order: key-offs `$28 := $02,$06,$01,$05,$00,$04`
(I); then TL `$7F` to registers `$40,$44,$48,$4C`, `$41,$45,$49,$4D`, `$42,$46,$4A,$4E`,
each written to part I then part II before the next register.
`PSGSilenceAll` (`SD:2021-2028`): `$9F,$BF,$DF,$FF`.

### 5.2 Test vectors

**TV5.1 — GHZ load burst (U0 write list).** In order: `$28←$02,$06,$01,$05,$00,$04`
(I); TL `$7F` ×24 in the `FMSilenceAll` order above; PSG `$9F,$BF,$DF,$FF`; then FM6
silence: `$28←$06` (I), `$42←$7F`,`$4A←$7F`,`$46←$7F`,`$4E←$7F` (II), `$B6←$C0` (II);
then note-offs `$28←$00,$01,$02,$04,$05,$00` (I) — the sixth write comes from the
**uninitialised music FM6 slot**: GHZ's `Chan $06` initialises only DAC+FM1-5, so music
FM6's `VoiceControl` is still zero after the `InitMusicPlayback` clear and its note-off
keys FM1 off again instead of FM6 (the step-6 FM6 silence already covered the real FM6;
the shipped-branch comment at `SD:1526-1535` names exactly this gap) — and PSG
`$9F,$BF,$DF` — no other writes. RAM: TV2.2. Derivation: steps 2, 6, 8 above with GHZ's `Chan $06`.

**TV5.2 — stop-all burst.** Request `$E4`: `$2B←$80` (I), `$27←$00` (I), then
`FMSilenceAll` + `PSGSilenceAll` as above; RAM per TV2.1 plus the FixBugs #11 survivor
bytes if a waterfall had ever played. Derivation: `SD:1461-1482`.

### 5.3 Engine today

Sequencer construction + first-read priming (EM §3.2 step 7); the init burst's write
shape is not specified engine-side. Adaptation GAP §1.2 #7: spec the bursts (done above)
and compare `stopAll`/song-start against them; the `$2B` latch is a driver global the
engine must keep. Song-load SFX policy (S1 keeps SFX) is a profile knob to confirm
(GAP §1.2 #6); `AudioVoiceRegistry.ReplaceMusic` currently rebuilds the music voice
while SFX drivers continue — the re-marking semantics (bit 2 on the *new* song's
tracks) are implicit in `SmpsDriver.updateOverrides` and unverified.

---

## 6. SFX load, ownership, override, restore

### 6.1 ROM behaviour — load

`Sound_PlaySFX` (`SD:977-1087`):

- Gates: exit **clearing `v_sndprio`** if `f_1up_playing`, `v_fadeout_counter`, or
  `f_fadein_flag` is set (`SD:978-983`, `.clear_sndprio` `SD:1085-1087`). Ring and push
  transforms: §14. The push re-trigger exit (`SD:996-997`) returns *without* clearing
  the latch.
- Header (`INC:360-384`): word voice-bank offset; byte divider; byte track count; per
  track `dc.b $80, channel` (`cPSG1=$80, cPSG2=$A0, cPSG3=$C0, cNoise=$E0, cFM3=$02,
  cFM4=$04, cFM5=$05`, `INC:171-177`), word data offset, byte transpose, byte volume.
  FixBugs #5 (missing `moveq #0,d7`) is harmless — all shipped indices `< $40`.
- Per track (`SD:1020-1070`): set bit 2 on the owning **music** track
  (`SFX_BGMChannelRAM`; FM index `(id-2)*4`, PSG index `id>>3`; noise `$E0` maps to the
  PSG3 slot); for `cPSG3` also write `$DF` and `$FF` to the PSG (`SD:1038-1044`); zero
  the SFX track's `$30` bytes; write `$80,id` into `PlaybackControl/VoiceControl`;
  divider, pointer, transpose/volume word, `DurationTimeout := 1`,
  `StackPointer := $30`; FM only: `AMSFMSPan := $C0`, `VoicePtr := bank`. A
  still-playing SFX on the same channel is **discarded without a key-off**; the new
  SFX's first update replaces it (channels are taken at request time — CD ADM-03 S1
  half derived).
- After the loop (`SD:1072-1079`): a playing **SFX** FM4/PSG3 sets bit 2 on the
  **special** FM4/PSG3 track — SFX outrank the special layer.
- No chip write happens at admission except the `cPSG3` silence pair — an FM SFX's
  takeover is entirely deferred to its first track update (CD VOICE-02 derived; TV6.1).

`Sound_PlaySpecial` (`SD:1117-1194`): same gates but exits **without** clearing
`v_sndprio`; only `$D0` (waterfall) exists; voice bank → `v_special_voice_ptr`; fixed
destinations `v_spcsfx_fm4_track` / `v_spcsfx_psg3_track`, marking music FM4/PSG3; no
per-track `VoicePtr` (`SD:1173-1175`); after the loop, playing SFX FM4/PSG3 re-mark the
special tracks and silence PSG3 (`SD:1180-1191`).

### 6.2 ROM behaviour — stop and restore

- `StopSFX` (`SD:1226-1307`): `v_sndprio := 0`; per playing SFX track: clear bit 7;
  FM: `FMNoteOff` (writes `$28 ← channel`), then restore target = the **special FM4**
  track if channel is FM4 and special FM4 is playing (voice bank
  `v_special_voice_ptr`; FixBugs #7 corrupts `a5` afterwards — §18 q1), else the music
  track (bank `v_voice_ptr`): clear bit 2, set at-rest (bit 1), `SetVoice(VoiceIndex)`
  (FixBugs #8: byte-only `d0`). **No frequency write, no key-on** — the restored track
  stays silent until its next note (CD ADM-04's S1 claim derived, and it applies to FM
  as well as PSG). PSG: `PSGNoteOff`; restore target is the special PSG3 track whenever
  the channel is `$C0`/`$E0` — with **no check that it is playing** (FixBugs #9); clear
  bit 2, at-rest, and re-latch `PSGNoise` if the restored track is a noise track.
- `StopSpecialSFX` (`SD:1311-1353`): for special FM4/PSG3 if playing: clear bit 7; if
  **not** overridden send the unconditional note-off (`SendFMNoteOff` /
  `SendPSGNoteOff`) and restore music FM4/PSG3 (clear bit 2, at-rest, `SetVoice` with
  `v_voice_ptr` if playing / noise re-latch). If an SFX overrides the special track,
  nothing is restored.
- `cfStopTrack` (`$F2`, §10) performs the same restore in its SFX phase, including
  `v_sndprio := 0` — this is the "latch clears when an SFX track stops" half of
  CD ADM-01 (`SD:2504-2506`). Two differences from `StopSFX`: its music-FM restore
  touches the music track only if that track is **playing** (`SD:2525-2526` — a stopped
  music track keeps bit 2 and gets no voice re-upload), and its PSG restore *does* check
  that special PSG3 is playing (`SD:2542-2543`, the check FixBugs #9 notes is missing
  from `StopSFX`).
- Override semantics: bit 2 suppresses every hardware write from the marked track
  (`FMUpdateFreq` `SD:534`, `FMNoteOn/Off` `SD:1671/1687`, `WriteFMIorIIMain`
  `SD:1702-1705`, `SetPSGVolume` `SD:1967`, `PSGUpdateFreq` `SD:1894`, `PSGNoteOff`
  `SD:1998`, `cfSetPSGNoise` `SD:2569`, `cfSetVoice` `SD:2317`, `SendVoiceTL`
  `SD:2384`, DAC `SD:309`); the track keeps parsing and advancing state.

### 6.3 Test vectors

**TV6.1 — ring on FM5, admission → first update.** Precondition: `v_ring_speaker = 1`
(one ring already played since the last song load), GHZ playing, FM5 free of SFX.
Request `$B5` → stays `$B5`, toggle → 0 (§14.1). U0 writes: none for this SFX (track
init only; music FM5 gets bit 2). U1, SFX FM5 track (`SndB5 - Ring.asm`: `$EF $00`,
`$E0 $40`, `nE5=$C1 $05`, `nG5=$C4 $05`, `nC6=$C9 $1B`, `$F2`):

1. `cfSetVoice 0` upload (§8; part II, channel offset 1, voice bytes from the file's
   raw comment block `04 / 37 72 77 49 / 1F 1F 1F 1F / 07 0A 07 0D / 00 0B 00 0B /
   1F 0F 1F 0F / 23 80 23 80`): `$B1←$04`; `$31←$37 $39←$72 $35←$77 $3D←$49`;
   `$51←$1F $59←$1F $55←$1F $5D←$1F`; `$61←$07 $69←$0A $65←$07 $6D←$0D`;
   `$71←$00 $79←$0B $75←$00 $7D←$0B`; `$81←$1F $89←$0F $85←$1F $8D←$0F`; TL with
   volume 5 and `FMSlotMask[4] = $A` (mask bit 1 → 2nd TL byte, bit 3 → 4th):
   `$41←$23 $49←$85 $45←$23 $4D←$85`; `$B5←$C0` — all part II.
2. `$E0 $40`: `AMSFMSPan = ($C0 & $37) | $40 = $40` → `$B5←$40` (II).
3. Note `$C1`: `FMNoteOff` → `$28←$05` (I). `FMSetFreq`: index
   `($C1-$80+0) & $7F = 65` → octave 5, step 5 (E) → `Freq = $32D + 5*$800 = $2B2D`
   (table value derived below, TV7.1). Duration 5.
4. `FMPrepareNote`/`FMUpdateFreq`: `$A5←$2B` then `$A1←$2D` (II).
5. `FMNoteOn`: `$28←$F5` (I).

Derivation: §6.1 init, `FMDoNext` flag-then-note order (`SD:366-393`), `WriteFMIIPart`
register offset `ch & ~4 = 1` (`SD:1746-1750`), `SetVoice` §8.

**TV6.2 — ring end restore.** The `$F2` lands `5+5+$1B = $25` frames of note time after
TV6.1 (SFX are tempo-hold-free). On that pass: `$28←$05` (I, key-off from `FMNoteOff`);
`v_sndprio := 0`; music FM5 (playing, GHZ): bit 2 cleared, bit 1 set, `SetVoice` re-
uploads music FM5's current voice from `v_voice_ptr` — 25 register writes + `$B5 ←`
music FM5's `AMSFMSPan` — **no `$A5/$A1` write, no key-on**. Derivation: `cfStopTrack`
`SD:2489-2537`.

**TV6.3 — special-layer precedence.** Waterfall `$D0` playing on FM4; request `$A7`
(push, FM4). Admission marks music FM4 *and* (after the loop) special FM4 with bit 2;
the push SFX owns the hardware. When the push track ends (`$ED,$F2`), `cfStopTrack`
restores the **special** FM4 track (playing) with `v_special_voice_ptr`, not music FM4.
Derivation: `SD:1072-1075`, `SD:2512-2518`.

### 6.4 Engine today

Locks per chip channel + `Track.overridden` (EM §1.4). Two divergences to fix
(GAP §1.2 #6): `SmpsSequencer.setChannelOverridden` (`SmpsSequencer.java:657-688`,
re-read here) restores instrument, volume, pan, emits a key-off, **and re-sends the
frequency when `t.duration > 0`** (`restoreFrequency`, `:683-685, 690-731`) — the ROM
never emits that frequency write (TV6.2). The ROM's key-off comes from the *SFX* track's
`FMNoteOff` (same register/value, different provenance). The special-SFX layer exists as
a `specialSfx` class (EM §1.4); its FM4/PSG3-only residency and the SFX-outrank rule are
unverified. `FmSfxTakeoverMode.REGISTER_SEQUENCE` (S1 config) matches the deferred
takeover of TV6.1 in intent; per-write verification is the oracle's job.

---

## 7. Note parse, frequency, key on/off

### 7.1 ROM behaviour

- `FMUpdateTrack` (`SD:349-362`): duration expiry → clear bit 4, `FMDoNext`,
  `FMPrepareNote`, `FMNoteOn`; otherwise `NoteTimeoutUpdate`, `DoModulation`,
  `FMUpdateFreq` — so FM frequency is written **only at note-on and on modulation-step
  frames** (the `DoModulation` tamper skips the write otherwise). A detune-only track
  with no modulation writes frequency once per note.
- `FMDoNext` (`SD:366-393`): clear at-rest; flags (`>= $E0`) run first, at note
  boundaries only; the first non-flag byte triggers `FMNoteOff` **before** the new
  note's frequency; a note `>= $80` → `FMSetFreq`; a following byte `< $80` is the
  duration, else `SavedDuration` is reused — a bare duration byte therefore re-triggers
  the previous note (key-off + key-on with the same `Freq`).
- `FMSetFreq` (`SD:397-407`): `note == $80` → `TrackSetRest` (bit 1 set, `Freq := 0`);
  else `idx = (note - $80 + Transpose) & $7F`, `Freq := FMFrequencies[idx]`. Because
  the base is `- $80` rather than `- $81`, entry 0 is only reachable via negative
  transpose (`SD:1773-1780`). Table (`SD:1792-1809`, `$72790`): 96 words, 8 octaves of
  `B,C,C#,…,A#`, `MakeFMFrequency(f) = round(f · 2^21 / FM_Sample_Rate) + octave·$800`;
  `FM_Sample_Rate = M68000_Clock/144`, `M68000_Clock = 53693175/7` with assembler
  integer division (`C:11-13`). Indices ≥ 96 read the code that follows the table.
- `FMPrepareNote`/`FMUpdateFreq` (`SD:524-545`): skip if resting; `Freq == 0` with a
  note pending → `FMSetRest`; else write `Freq + sext(Detune)` as `$A4+off` (high)
  then `$A0+off` (low), unless overridden.
- `FMNoteOn` (`SD:1668-1676`): `$28 := $F0 | VoiceControl` (I) unless resting or
  overridden. `FMNoteOff` (`SD:1684-1693`): `$28 := VoiceControl` unless bit 4 (hold)
  or bit 2; `SendFMNoteOff` is the unconditional entry.
- PSG (`SD:1813-1914`): `PSGDoNext` mirrors `FMDoNext` **without a note-off**;
  `PSGSetFreq` uses `idx = (note - $81 + Transpose) & $7F` into `PSGFrequencies`
  (70 words, `SD:2049-2063`, `$729CE`; `MakePSGFrequency(f) = min($3FF,
  round(PSG_Sample_Rate/2f))`, `PSG_Sample_Rate = Z80_Clock/16`, `Z80_Clock =
  53693175/15`, `C:12-14`); `note == $80` → at-rest, `Freq := -1`, `PSGNoteOff`.
  `PSGUpdateFreq` writes `VoiceControl | (d6 & $F)` then `(d6 >> 4) & $3F` unless the
  track is resting or overridden (noise tracks substitute `$C0` as the latch channel) —
  but, exactly as on FM, it is reached on a non-note-start frame **only through
  `DoModulation`'s step path** (the `+4` entry tamper skips the `jsr PSGUpdateFreq` in
  `PSGUpdateTrack`, `SD:1822-1828`, `483-520`): PSG frequency goes out at note start
  (`PSGDoNoteOn`) and on modulation-step frames, not every frame. `PSGNoteOff` (`SD:1997-2004`): `VoiceControl | $1F`; keying off
  PSG3 does **not** silence noise (FixBugs #16). `PSGSetFreq` calls
  `FinishTrackUpdate` itself and the caller runs it again — harmless double execution
  (`SD:1863-1879`).
- Note fill (`NoteTimeoutUpdate` `SD:460-479`): loaded by `$E8`, reloaded from master
  at every non-held note; on expiry set at-rest + key-off and skip the rest of that
  track's update. On PSG, an expired fill under hold also suppresses volume writes
  (`PSGCheckNoteTimeout` `SD:1981-1986`).
- Register I/O (`SD:1702-1769`): `WriteFMI/WriteFMII` poll `$A04000` bit 7 before the
  address write and again before the data write (CD VOICE-05's "does the driver poll
  busy" — S1: yes, both halves; the busy-window duration is a hardware question, §18
  q11). `WriteFMIorII` adds `VoiceControl` (part I) or `VoiceControl & ~4` (part II)
  to the register number.

### 7.2 Test vectors

**TV7.1 — FM table entry derivation.** `M68000_Clock = 53693175/7 = 7670453` (integer);
`FM_Sample_Rate = 7670453/144 = 53267`. Entry 0 (B, 15.39 Hz):
`round(15.39·2097152/53267) = round(605.91) = 606 = $25E`. Entry 5 (E):
`round(20.64·2097152/53267) = round(812.61) = 813 = $32D`. Entry 65 (E, octave 5) =
`$32D + 5·$800 = $2B2D` — the value TV6.1 writes. Entry 1 (C):
`round(16.35·2097152/53267) = round(643.71) = 644 = $284`; `nC6` (`$C9`, idx 73) → `$284 + 6·$800 =
$3284`, the ring's third note.

**TV7.2 — PSG jump SFX (`SndA0`).** PSG1, transpose `$F4` (−12), volume 0, envelope 0
(`smpsPSGvoice $00`), data `nF2 $05`, `smpsModSet $02,$01,$F8,$65`, `nBb2 $15`,
`smpsStop`. First update: `nF2 = $9E` → idx `($9E-$81-12) & $7F = 17` → 349.56 Hz →
`PSG_Sample_Rate = 3579545/16 = 223721`; period `round(223721/699.12) = 320 = $140`.
Writes: `$80 | ($140 & $F) = $80`, then `($140 >> 4) & $3F = $14`, then volume
`$80|$10|0 = $90`. Five frames later `nBb2 = $A3` → idx `($A3-$81-12) & $7F = 22` →
row idx 12-23 (`261.96…494.95`), position 10 = **468.03 Hz** → period
`round(223721/936.06) = 239 = $EF` → writes `$8F`, `$0E`, `$90`. Derivation:
`SndA0 - Jump.asm`, `SD:1863-1879`, `SD:1890-1914`, note equates `INC:31-47`
(`nRst=$80`, C0=`$81`, 12 ids per octave → `nF2=$9E`, `nBb2=$A3`).

**TV7.3 — repeated note.** GHZ FM data `… nC5 $18, nC5 …` versus `… nC5 $18, $18 …`:
the bare `$18` re-fetches nothing — `FMDoNext` sees `$18 < $80` → `FMNoteOff`
(key-off), no `FMSetFreq`, `SetDuration($18)` → key-on with the previous `Freq`. Both
forms produce identical write streams (off, freq unchanged → no `$A4/$A0` rewrite? No:
`FMPrepareNote` runs each note start and rewrites `$A4/$A0` with the same value, then
key-on). Expected: `$28←ch`, `$A4+off←hi`, `$A0+off←lo`, `$28←$F0|ch` for both.
Derivation: `SD:379-393`, `SD:524-545`.

### 7.3 Engine today

`baseFnum/baseBlock`, `forceModulationWrite`/`modStepChanged` reproduce the S1
write-on-change cadence (EM §2.2; GAP §1.2 #8 says the S1/S2 shape is modelled — verify
per-flag with the oracle). Frequency tables are Java-resident
(`SmpsSequencer.FNUM_TABLE_68K`, `PSG_FREQ_TABLE_68K`; §17). Table overruns (idx ≥ 96 /
≥ 70) must be modelled as ROM-read tables including the ROM's neighbouring bytes —
FixBugs #21 (MZ PSG3) is shipped data that reaches the PSG overrun (§16).

---

## 8. Voice upload and volume model

### 8.1 ROM behaviour

- Voice layout: 25 bytes per voice at `bank + 25n`; operator order within each 4-byte
  row is **1, 3, 2, 4** (`FMInstrumentOperatorTable` `SD:2440-2461`,
  `FMInstrumentTLTable` `SD:2463-2468`; `INC:960-965` "else" rows for S1).
- `SetVoice` (`SD:2329-2375`): byte 0 → `$B0+off` and `FeedbackAlgo`; 20 operator
  bytes → `$30/$38/$34/$3C`, `$50/…`, `$60/…`, `$70/…`, `$80/…`; then 4 TL bytes →
  `$40/$48/$44/$4C`, adding `Volume` to the bytes whose position is selected by
  `FMSlotMask[algorithm]` = `8,8,8,8,$A,$E,$E,$F` (`SD:2379`, `$72CAC`,
  MAP-verified), tested LSB-first in storage order (bit 0 = op1, bit 1 = op3, bit 2 =
  op2, bit 3 = op4); **no carry check** — unlike `SendVoiceTL`. Finally `$B4+off ←
  AMSFMSPan`. `SetVoice` itself never checks the override bit; its callers do.
- Bank selection (`cfSetVoice` `SD:2313-2325`): `f_voice_selector` 0 → `v_voice_ptr`;
  `$80` → per-track `VoicePtr`; `$40` → `v_special_voice_ptr`.
- `SendVoiceTL` (`SD:2383-2436`; used by `$E6` and both fades): returns if overridden
  or `Volume` negative; re-reads the voice's 4 TL bytes and adds `Volume` to the
  masked positions, **skipping the write when the byte addition carries**
  (`SD:2427-2428`) — an operator whose TL+volume overflows keeps its old TL. For an
  SFX-phase track it reads the pointer from `SMPS_Track.VoicePtr(a6)` — offset `$20`
  from the **RAM base**, i.e. `v_special_voice_ptr` (FixBugs #19): a normal FM SFX's
  `$E6` uploads TL bytes from the special-SFX voice bank (zero until the first `$D0`;
  with a zero pointer the four bytes come from ROM `$15 + 25·voice`, inside the 68k
  vector table).
- Volume semantics: FM `Volume` is attenuation added to masked TLs; PSG `Volume` is
  0-`$F` attenuation (§9.2). `$E6` adds to FM `Volume` then `SendVoiceTL`; `$EC` adds
  to PSG `Volume` with no immediate write (§10).

### 8.2 Test vectors

**TV8.1 — ring voice upload.** The 31-write sequence of TV6.1 step 1, including the
algo-4 mask arithmetic (`$A` → TL positions 2 and 4 get `+$05`: `$80+$05 = $85`).

**TV8.2 — fade TL step with carry skip.** During a fade-out step (§11) on a music FM
track with voice TLs `$23,$80,$23,$80`, algorithm 4, `Volume = $7E`: masked positions
compute `$80+$7E = $FE` (no carry → written); at `Volume = $81` (already stopped —
volume goes negative first at `$80`, so use `Volume = $7F`+1 step): `$80+$80 = $100` →
carry → **write skipped**, operator keeps its previous TL. Derivation: `SD:2422-2432`.

**TV8.3 — FixBugs #19 reach.** Shipped normal SFX containing `$E6` (`smpsAlterVol`)
include `SndA3` (FM5), `SndAC` (FM5), `SndB0` (FM5), `SndB7` (FM5), `SndBE` (FM4),
`SndCC` (FM4), `SndB2`, `SndB9`, `SndBF`, `SndCB`, `SndCF` (grep of `sound/sfx/` for
this spec). Every one of the single-FM-track cases (A3, AC, B0, B7, BE, CC) necessarily
executes `$E6` on an FM SFX track → `SendVoiceTL` reads `v_special_voice_ptr`'s bank,
not the SFX's own. Expected for `SndCC` (Spring) before any waterfall has played:
TL source address = `0 + 25·0 + 21 = $15` (68k vector table bytes), `Volume` grows by
2 per loop iteration; after a waterfall, the source is the waterfall voice's TL row.
This resolves MAP open q 3's enumeration; the actual bytes at `$15` are ROM content the
oracle can capture (§18 q3 retires into a data check).

### 8.3 Engine today

`FmVoiceWriteProfile.S1_68K` + `VolMode` carrier masking + `direct68kDriver` TL
carry-skip (EM §4.1/§4.4); GAP §1.2 #9 rates this `same (via profile)`, S1 matched at
GHZ. FixBugs #19 needs a decision now that TV8.3 enumerates the reach: model the
special-bank read (driver-faithful) or record a named divergence in
`docs/status/known-discrepancies.md`. The engine's per-track `voiceData` copy cannot
express "read whatever `v_special_voice_ptr` points at now" without a driver-global.

---

## 9. Modulation and envelopes

### 9.1 Modulation

`cfModulation` (`$F0`, `SD:2471-2481`): store `ModulationPtr := a4`, load wait, speed,
delta, `steps := p4 >> 1` (halved), `ModulationVal := 0`, set bit 3.
`FinishTrackUpdate` (`SD:436-456`) repeats that load at every non-held note (steps
halved again). `DoModulation` (`SD:483-520`), every non-note-start frame with bit 3:
wait counts down first; then speed; on speed expiry reload speed from data byte 1 and
either — steps exhausted: reload steps from byte 3 (**full, not halved** — the first
half-cycle is half length so the wave centres), negate delta, **no frequency write
this frame**; or: `steps -= 1`, `ModulationVal += delta`,
`d6 := Freq + ModulationVal`, fall into `FMUpdateFreq`/`PSGUpdateFreq` (adding
`Detune`). PSG shares the routine with `Freq` as a period, so positive delta lowers
pitch. `$F1` sets bit 3, `$F4` clears it (`SD:2484-2486`, `2577-2579`).

### 9.2 PSG volume envelopes

`PSGUpdateVolFX` (`SD:1924-1926`): per frame, only when `VoiceIndex != 0`.
`PSGDoVolFX` (`SD:1928-1959`): `d6 := Volume`; envelope `PSG_Index[VoiceIndex-1]`
(`SD:41-64`); read byte at `VolEnvIndex`, post-increment; `$80` → `VolEnvHold`
(`SD:1991-1993`) decrements the index back and returns **without a volume write** — from
the terminator frame on, the envelope path emits no further volume writes for the rest of
the note (the chip retains the last value sent); the `$80` itself is never applied; any other bit-7 byte would fall through
and be added (FixBugs #15 — unreachable: all nine shipped envelopes contain only
non-negative bytes then `$80`, `SD:46-64`; CD SEQ-04 derived with that effect
correction). Values are added to `Volume` and clamped to `$F` at `$10`+.
`SetPSGVolume` (`SD:1964-1986`): refuse if resting, overridden, or holding with an
expired fill; else write `VoiceControl | $10 | vol` (noise tracks: `$F0 | vol`).
`VolEnvIndex` is cleared at every non-held note **even on FM tracks** (`SD:442`).

FM volume envelopes do not exist in this driver (S3K `FF 06` has no S1 counterpart).

### 9.3 Test vectors

**TV9.1 — jump SFX modulation onset.** From TV7.2: `nBb2` starts at pass U(n) with
wait 2, speed 1, delta `$F8` (−8), steps `$65>>1 = $32`. U(n+1): wait 2→1, no write.
U(n+2): wait 1→0, no write. U(n+3): speed 1→0 → reload 1; steps `$32`→`$31`;
`ModulationVal = −8`; `d6 = $EF − 8 = $E7` → writes `$80|($E7&$F) = $87`, then
`($E7>>4)&$3F = $0E`. U(n+4) onward: one step per pass, period falling 8/frame until
the (full) step reload negates delta. Derivation: `SD:483-520`, TV7.2 values.

**TV9.2 — GHZ PSG1 envelope.** Volume 1, `fTone_03` = `0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,
7,$80` (`SD:50`). Per frame volume bytes: `$91,$91,$92,$92,…,$98,$98` (16 writes), then the
terminator is reached → **no further volume writes** for the rest of the note (the chip
holds `$98`; `VolEnvIndex` parks on the `$80`). At the next attacked note `VolEnvIndex`
resets to 0. Derivation: `SD:1928-1993`, envelope data.

**TV9.3 — modulation reload at note.** `SndCC` (Spring): `smpsModSet $03,$01,$5D,$0F`
then `nB3 $0C`, then `smpsModOff`. At the `nB3` note start, `FinishTrackUpdate`
reloads wait 3, speed 1, delta `$5D`, steps `$0F>>1 = 7`, val 0. First modulated write
at U(note)+4 (wait 3 frames + first speed expiry): `nB3 = $B0` → idx
`($B0-$80+0) & $7F = $30 = 48` → octave 4, step 0 (B) → `Freq = $25E + 4·$800 =
$225E`; `$225E + $5D = $22BB`; FM4 (`VoiceControl 4`, part II, offset `4 & ~4 = 0`):
`$A4←$22`, `$A0←$BB` (both II). Derivation: `SD:436-456`, `SD:483-520`, table TV7.1.

### 9.4 Engine today

`modPending*` copies + init/counter pairs (EM §2.3) — GAP §1.2 #10 `same`; the copy
model must reconstruct `ModulationPtr` from the `$F0` position for the RAM comparison.
PSG envelopes: `PsgEnvCmd80` config models the `$80` hold (EM §1.4); the S1 semantics
(decrement-back, last value repeats, index compared not value) verified above; S1
envelopes are ROM-read via the loader (EM §1.3). `applyModOnNote(false)` /
`halveModSteps(true)` in `Sonic1SmpsSequencerConfig` match `SD:449-451`/`SD:2477-2479`.

---

## 10. Coordination flags

Dispatch: bytes ≥ `$E0` inside track data, `CoordFlag` (`SD:2067-2071`) via
`coordflagLookup` (`SD:2075-2127`, `$72A64`); flags run inside the note-fetch loops at
note boundaries only. `$FA-$FF` index past the table into `cfPanningAMSFMS` code — no
shipped data uses them. Engine site: shared `SmpsSequencer.handleFlag` switch; S1
config overrides `ED → 0 params` and marks `EE` an extra track-end flag
(`Sonic1SmpsSequencerConfig.java:63-83`); S1 has no game handler (EM §4.3).

| Flag | Routine (anchor) | Params | Shipped effect | Engine status |
|---|---|---|---|---|
| `$E0` | `cfPanningAMSFMS` `SD:2129-2138` | 1 | PSG: param consumed, ignored. FM/DAC: `AMSFMSPan := (old & $37) \| p` then `$B4+off` unless overridden. Old AMS/FMS bits survive (OR-composition — set AMS/FMS bits cannot be cleared by this flag); L/R and bit 3 replaced | unverified (compose rule is the check) |
| `$E1` | `cfDetune` `SD:2145-2147` | 1 | `Detune := p`, applied at every frequency write | unverified |
| `$E2` | `cfSetCommunication` `SD:2150-2152` | 1 | `v_communication_byte := p`; nothing reads it | unverified (`commData`) |
| `$E3` | `cfJumpReturn` `SD:2155-2163` | 0 | pop gosub: `a4 := saved+2`, slot zeroed, `StackPointer += 4` | unverified |
| `$E4` | `cfFadeInToPrevious` `SD:2166-2225` | 0 | §13.1 restore + fade-in; `Mus88` only | adaptation (GAP §1.2 #15) |
| `$E5` | `cfSetTempoDivider` `SD:2228-2230` | 1 | this track's `TempoDivider := p` | unverified |
| `$E6` | `cfChangeFMVolume` `SD:2233-2236` | 1 | `Volume += p`, `SendVoiceTL` (§8). On a **PSG** track it still adds and then reads FM voice data (Credits data bug, FixBugs #23 context) | **likely divergence** — GAP §1.2 #22 flags the PSG case as a probable clean-up; verify |
| `$E7` | `cfHoldNote` `SD:2239-2241` | 0 | set bit 4: next note neither keyed off nor on; `FinishTrackUpdate` skips fill/env/mod reset | unverified (`tieNext`) |
| `$E8` | `cfNoteTimeout` `SD:2244-2247` | 1 | `NoteTimeout := NoteTimeoutMaster := p` | unverified |
| `$E9` | `cfChangeTransposition` `SD:2250-2253` | 1 | `Transpose += p` | unverified |
| `$EA` | `cfSetTempo` `SD:2256-2259` | 1 | `v_main_tempo := timeout := p`; **not** routed through the speed-shoes pair — lost at the next `$E2`/`$E3` | unverified |
| `$EB` | `cfSetTempoDividerAll` `SD:2262-2273` | 1 | divider on all ten music tracks, whoever ran it | unverified |
| `$EC` | `cfChangePSGVolume` `SD:2276-2279` | 1 | `Volume += p`; no immediate write | unverified |
| `$ED` | `cfClearPush` `SD:2282-2284` | 0 | `f_push_playing := 0` | config override present ✓ (param count); latch itself absent (§14.2) |
| `$EE` | `cfStopSpecialFM4` `SD:2287-2310` | 0 | stop this track, `FMNoteOff`; if SFX FM4 not playing restore **music FM4** (bit 2 clear, at-rest, `SetVoice`) — run on any other track it still restores music FM4. Tamper +8 | `extraTrkEndFlags` covers the stop; the restore-FM4-regardless shape is a **likely divergence** (GAP §1.2 #22) |
| `$EF` | `cfSetVoice` `SD:2313-2325` | 1 | `VoiceIndex := p`; upload from the selector's bank unless overridden (§8) | unverified |
| `$F0` | `cfModulation` `SD:2471-2481` | 4 | §9.1 | unverified |
| `$F1` | `cfEnableModulation` `SD:2484-2486` | 0 | set bit 3 (unused by shipped data) | unverified |
| `$F2` | `cfStopTrack` `SD:2489-2563` | 0 | §6.2; SFX phase clears `v_sndprio` and restores | restore shape: divergence per §6.4 |
| `$F3` | `cfSetPSGNoise` `SD:2566-2574` | 1 | `VoiceControl := $E0`, `PSGNoise := p`, write `p` unless overridden; never undone until reload | unverified (`noiseMode`) |
| `$F4` | `cfDisableModulation` `SD:2577-2579` | 0 | clear bit 3 | unverified |
| `$F5` | `cfSetPSGTone` `SD:2582-2584` | 1 | `VoiceIndex := p` (envelope number; 0 = flat) | unverified |
| `$F6` | `cfJumpTo` `SD:2587-2593` | 2 big-endian | `a4 += offset - 1` (assembler writes `loc-*-1`, `INC:604-611`) | unverified (`relativePointers` ✓) |
| `$F7` | `cfRepeatAtPos` `SD:2596-2608` | 4 | `idx, count, offset`; counter loaded when 0, decremented, jump while non-zero; **never reset on exit** — re-entry reloads from `count` | unverified |
| `$F8` | `cfJumpToGosub` `SD:2611-2617` | 2 | push `a4` at `(a5, SP-4)`, jump; no overflow check (§2.1 overlap) | unverified |
| `$F9` | `cfOpF9` `SD:2620-2626` | 0 | `$88 := $0F`, `$8C := $0F` on **part I** — D1L/RR of FM1's ops 3/4, whatever track ran it (SYZ only) | **likely divergence** (GAP §1.2 #22); verify the fixed-FM1 target |

Test vectors: TV6.1 (`$EF`,`$E0`), TV6.2 (`$F2`), TV9.1/9.3 (`$F0`), TV9.2 (`$F5`
implied), TV11-13 below cover `$E4`. Two more:

**TV10.1 — `$E0` compose rule.** Track with `AMSFMSPan = $F2` (L+R, AMS 3, FMS 2) runs
`$E0 $40`: new value `($F2 & $37) | $40 = $72` → right-only, AMS 3, FMS 2 preserved.
The flag cannot clear AMS/FMS once set.

**TV10.2 — `$F7` counter reuse.** GHZ DAC `smpsLoop $00, $07, Loop00`
(`Mus81 - GHZ.asm:453-455`): first execution loads `LoopCounters[0] = 7`, decrements,
jumps while non-zero → the block plays 7 times; on song loop, re-entry finds 0 → loads
7 again. RAM: `LoopCounters[0]` visible cycling 7→0.

---

## 11. Fades

### 11.1 ROM behaviour

- **Fade-out** (`FadeOutMusic` `SD:1360-1367`, id `$E0`): `StopSFX` +
  `StopSpecialSFX` first (CD FADE-02 derived), `v_fadeout_delay := 3`,
  `v_fadeout_counter := $28`, the DAC track's whole `PlaybackControl` byte cleared (`clr.b`, `SD:1365`; drums stop
  at once — **no** Z80 write; the current sample runs out), `f_speedup := 0` (CD FADE-03 S1 half
  derived). `DoFadeOut` (`SD:1371-1422`) each pass: delay non-zero → decrement,
  return; else `counter -= 1`; **at 0 → `StopAllSound` with no final volume step**
  (CD FADE-04 S1 half derived); otherwise `delay := 3` and step: music FM tracks
  `Volume += 1`, track stopped when `Volume` goes negative, else `SendVoiceTL`; music
  PSG tracks `Volume += 1`, stopped at ≥ `$10`, else `SetPSGVolume`. Channel set:
  music FM + PSG only, DAC stopped up-front — answers CD open question 4's S1 half
  (FADE-01 "S1 unspecified").
- SFX are refused for the whole fade (`Sound_PlaySFX`/`Sound_PlaySpecial` gates,
  `SD:980-981`, `1120-1121`).
- **Fade-in** exists only as the 1-up restore fade (§13.1); `DoFadeIn`
  (`SD:1604-1664`): delay 2 between steps, 40 steps (`counter $28`), FM `Volume -= 1`
  + `SendVoiceTL`, PSG `Volume -= 1` + `SetPSGVolume` clamped to `$F`; on completion
  clear the DAC track's bit 2 and `f_fadein_flag` — without re-sending the DAC pan
  register (FixBugs #14). PSG volume writes are suppressed while tracks are at rest
  (restore set them at rest), so PSG re-enters at its next note; FM TL steps go out
  immediately.

### 11.2 Test vectors

**TV11.1 — fade-out timeline.** `$E0` processed at U0 (with the `StopSFX` burst if SFX
were playing). `DoFadeOut` runs from U1 (it precedes `PlaySoundID` in the pass, so U0
itself does not step): U1-U3 burn the delay; U4: counter `$28→$27`, first volume step
(each playing music FM track: 0-4 TL writes per the §8 mask/carry rule; each PSG
track: one volume byte). Steps recur at U8, U12, …, U156 (39 steps); at U160 the
counter reaches 0 → the §5 stop-all burst. Derivation: `SD:1363-1364`, `1371-1422`.

**TV11.2 — SFX refusal during fade.** Request `$B5` at U10 of TV11.1: `Sound_PlaySFX`
exits at the `v_fadeout_counter` gate, clearing `v_sndprio`; no track init, no ring
toggle (the gate precedes the ring transform — order: 1-up, fade-out, fade-in, then
ring, `SD:978-991`). Derivation: `SD:980-981`.

### 11.3 Engine today

`FadeState` with config constants (EM §1.4); GAP §1.2 #13 `same (via config; verify)`.
S1 constants to pin: out steps `$28`, delay 3, terminal stop-all with no last step,
DAC halt up-front, SFX gate both directions; in steps `$28`, delay 2, DAC muted via
bit 2. `blocksSfxDuringMusicRestoreFadeIn` (`GameAudioProfile.java:93`) is the S1 gate's
engine knob — must be true for S1 in both fade directions.

---

## 12. Pause and unpause

### 12.1 ROM behaviour

Game side writes `f_pausemusic := 1` on pause and `$80` on unpause (`P:18,50,64`).
Driver side (`PauseMusic` `SD:555-629`, dispatched before everything else in the pass —
a pause/unpause frame does no other driver work):

- value 1 (not yet 2): set `2`; write `$B4,$B5,$B6 := 0` — each register to part I
  **then** part II (`SD:560-568`: order `$B4`(I), `$B4`(II), `$B5`(I), `$B5`(II),
  `$B6`(I), `$B6`(II)) — pan cleared silences all six FM channels; key-offs
  `$28 := $02,$06,$01,$05,$00,$04` (I); `PSGSilenceAll` (`$9F,$BF,$DF,$FF`);
  `DoStartZ80`.
- value 2: `DoStartZ80` only. Tempo, queue, fades, tracks all frozen; requests queued
  while paused wait in the slots.
- value `$80`: clear the flag; for every *playing, non-overridden* track in music
  DAC+FM1-6, SFX FM3-5, and special FM4 (in that order), write `$B4+off :=
  AMSFMSPan`. Nothing is keyed on; no voice reload; PSG volumes return at the next
  envelope/volume write (CD PAUSE-01 and PAUSE-05 S1 halves derived — with the
  correction that resume also covers SFX and special FM tracks, not just music).
- The Z80 is never told to stop: an in-flight DAC sample keeps playing through the
  pause (CD PAUSE-04 S1 half derived; the DAC track itself freezes with the rest).

### 12.2 Test vectors

**TV12.1 — pause burst.** GHZ playing, drums active. Pass after `f_pausemusic := 1`:
exactly `$B4←0`(I), `$B4←0`(II), `$B5←0`(I), `$B5←0`(II), `$B6←0`(I), `$B6←0`(II),
`$28←$02,$06,$01,$05,$00,$04`(I), `$9F,$BF,$DF,$FF` — and no track processing. A kick
mid-sample continues to completion.

**TV12.2 — resume burst.** GHZ with no SFX: the pass with flag `$80` writes, in track
order, `$B4+off ← AMSFMSPan` for each playing, non-overridden track of music DAC +
FM1-5: the DAC track (`VoiceControl 6` → part II, offset `6 & ~4 = 2` → `$B6`(II))
then music FM1-5 with their stored pan values (GHZ: FM1's pan varies with song
position). PSG stays silent until each track's next volume write — for GHZ's
envelope-driven PSG tracks, the very next pass. Derivation: `SD:584-629`.

### 12.3 Engine today

**Absent** — presentation `SILENT` mode, no driver flag, no bursts (EM §1.4 Pause).
Adaptation GAP §1.2 #14: driver-level pause state with the S1 flag machine
(1→2→`$80`→0) emitting the two bursts above and freezing service; `SILENT` stays for
the sink.

---

## 13. 1-up save/restore and speed shoes

### 13.1 Extra Life (`$88`)

- Load (`Sound_PlayBGM` `SD:755-786`): a second `$88` while `f_1up_playing` is
  ignored (`SD:757-758`; CD OVR-09 S1 half derived). Otherwise: clear bit 2 on all ten
  music tracks; clear bit 7 on all six **normal** SFX tracks (killed without key-off —
  their notes die in `InitMusicPlayback`'s chip silence); `v_sndprio := 0`; copy
  `$000-$21F` → `v_1up_ram_copy` (**variables and music tracks**: song position,
  tempo, speed-shoes state, fade state, ring toggle, queue bytes); `f_1up_playing :=
  $80`; `v_sndprio := 0` again; fall into the common loader (§5) for `Mus88`.
  **Special SFX tracks are not touched** — the waterfall keeps playing over the
  jingle (CD OVR-03's "all active SFX stop" is **corrected** for S1: normal SFX stop;
  special SFX survive). New SFX and special SFX are refused while the flag is set
  (`SD:978-979`, `1118-1119`). Priority in the backup is 0 (cleared before the copy,
  `SD:775` — CD OVR-04 S1 half derived).
- Any other music request abandons the backup: `.bgmnot1up` clears `f_1up_playing`
  (`SD:790`; CD OVR-02 S1 half derived).
- Restore (`cfFadeInToPrevious`, `$E4` at the end of `Mus88`, `SD:2166-2225`): copy
  the backup over `$000-$21F` (restores tempo, timeouts, `f_speedup`, fade counters,
  ring toggle, queue bytes — everything as of the jingle start); set bit 2 on the DAC
  track (mutes DAC until fade-in completes); `d6 := $28 - v_fadein_counter`
  (restored; non-zero only if the jingle interrupted an earlier fade-in); per playing
  music FM track: set at-rest, `Volume += d6`, and if not overridden
  `SetVoice(v_voice_ptr, VoiceIndex)` — the attenuated re-upload; per playing PSG
  track: at-rest, `PSGNoteOff`, `Volume += d6`. Then `f_fadein_flag := $80`,
  `v_fadein_counter := $28`, `f_1up_playing := 0`, `startZ80`, tamper +8. **No
  frequency writes, no key-ons** — pitch returns at each track's next note. `$2B` is
  left alone (FixBugs #17; CD OVR-08 derived — the DAC-disable scenario is not
  derivable from S1 source alone, §18 q5-map). SFX reopen only when `DoFadeIn`
  completes and clears the flag (CD OVR-05 S1 half derived).

### 13.2 Speed shoes (`$E2`/`$E3`)

`SpeedUpMusic` (`SD:1568-1581`): `v_main_tempo := timeout := v_speeduptempo`,
`f_speedup := $80` — a definite phase (countdown restarts). `SlowDownMusic`
(`SD:1587-1600`): same from `v_tempo_mod`. **While `f_1up_playing`, both edit the
backup copy instead of live RAM** (`SD:1577-1581`, `1596-1600`) — the jingle keeps its
own tempo and the restored song comes back at the requested speed. `f_speedup`
survives a song load (§5 step 2) so the next song starts sped up (`SD:807-809`);
`FadeOutMusic` and `StopAllSound` clear it. Per-song sped-up tempos: `SpeedUpIndex`
(§5 step 3; `$FF` for Invincibility ≈ full speed; IDs `$89+` read `MusicIndex` bytes —
shipped overrun). CD CAD-12 S1 half derived.

### 13.3 Test vectors

**TV13.1 — 1-up over GHZ.** GHZ at U0 with speed shoes on. Request `$88`: U0 writes =
§5 load burst for `Mus88` (`Chan $06,$03`, tempo `$02,$05`); RAM: backup holds GHZ's
exact track image with `v_sndprio = 0`, `f_speedup = $80`; live `f_speedup` survives
into the jingle's load (so the jingle plays at its **speed-up table** tempo?
`Mus88`'s `SpeedUpIndex[$88-$81] = $05` — equal to its normal tempo `$05`, so the
jingle is audibly unaffected). Derivation: `SD:755-786`, `795-813`, table `SD:74-93`.

**TV13.2 — speed-up during the jingle.** `$E3` (shoes expire) at U20: live tempo
unchanged (jingle continues at 5); backup's `v_main_tempo := v_tempo_mod(GHZ) = 3`,
backup `f_speedup := 0`. At the jingle's `$E4`: GHZ resumes at normal tempo with a
40-step fade-in (TV13.3). Derivation: `SD:1596-1600`.

**TV13.3 — restore burst.** `$E4` pass: RAM copy-back; then per playing GHZ FM track
(not overridden): 25-write voice upload + `$B4+off` (Volume raised by `$28`); PSG:
`$9F/$BF/$DF` note-offs. Then per §11: fade steps at the 1st, 4th, 7th… subsequent
passes, 40 steps, completion on pass 121 clears DAC bit 2 and the flag — with **no**
`$B6` pan re-send (FixBugs #14: any `$E0` the DAC track processed while muted was
stored but never written). Derivation: `SD:2166-2225`, `1604-1664`.

### 13.4 Engine today

Parked second live driver on the override stack (EM §1.4, §3.2) — holds the same
information as the RAM copy; GAP §1.4 item 2 records the model boundary (queue bytes
and ring toggle inside the backup are inexpressible without a RAM copy). Adaptations
(GAP §1.2 #15/#16): `RestoreMusicOverride` must emit the TV13.3 burst (at-rest,
attenuated voice re-upload, **no frequency**) and start the S1 fade-in shape; speed-up
while a 1-up plays must route to the parked driver (`SD:1577-1581` is the ROM
warrant); `SPEED_UP_TEMPOS` should come from ROM `$71A94` including the `$89+`
overrun rule (§17). `Sonic1SmpsSequencerConfig.SPEED_UP_TEMPOS` (Java map, verified
equal to the ROM bytes for `$81-$88` at `Sonic1SmpsSequencerConfig.java:50-57`) has no
overrun entries — requests for `$89+` under speed shoes diverge (no shipped 68k caller
plays speed shoes outside levels, but the driver accepts it).

---

## 14. Request transforms

### 14.1 Ring left/right (`SD:984-991`)

If `v_ring_speaker == 0` the request byte becomes `$CE` (`SndCE`, FM4, `panLeft`);
otherwise it stays `$B5` (`SndB5`, FM5, `panRight`). Bit 0 of `v_ring_speaker` is
toggled either way (`bchg`, `SD:991`). Reset to 0 by **every song load** (RAM clear, §5 step 2) and `StopAllSound` — so
the first ring after any song start is the left one. The two SFX use different FM
channels, so consecutive rings overlap rather than restart. Both priority `$70`. The
game only ever requests `$B5` (`_incObj/25, 37 Rings.asm:192` per MAP). The RAM comment
`RAM:63` has the speakers reversed; the code is authoritative. This answers CD REQ-01's
"S1 equivalent unnamed" and CD open question 6 for S1: **yes, S1 alternates, by id
substitution in `Sound_PlaySFX`**, with reset-on-song-load rather than S3K's
reset-on-music-play semantics being equivalent here.

**TV14.1** — first ring after GHZ load: request `$B5` → dispatched as `$CE` (FM4,
`$E0 $80` pan-left in its data, first note `nE5 $04`); toggle → 1; second ring →
`$B5` (FM5); toggle → 0. Expected: alternating FM4/FM5 admissions; both tracks
playing simultaneously if requested within `$25` frames.

### 14.2 Push latch (`$A7`/`$ED`, `SD:994-998`, `SD:2282-2284`)

`f_push_playing` gates re-triggers: a second `$A7` while set returns **without
clearing `v_sndprio`**. `SndA7` clears the latch via `$ED` just before its `$F2`. A
song load or stop-all also clears it. If the push SFX is displaced on FM4 before
reaching `$ED`, the latch stays set until the next song load — pushes go silent
(shipped quirk).

**TV14.2** — `$A7` at U0 (starts), `$A7` again at U5: second request exits at the
latch, no track init; `SndA7` runs `nD1($8F) $07, nRst $02, nD1 $06, nRst $10, $ED,
$F2` → latch clears on the `$ED` pass (≈ frame U0+`$1F`), after which `$A7` re-arms.

### 14.3 "SEGA" chant (`$E1`, `PlaySegaSound` `SD:733-748`)

Writes `$88` to `zDAC_Sample` (`$A01FFF`), `startZ80`, busy-waits `$12 × $10000` `dbf`
iterations on the 68k, tamper-returns. The Z80 plays the raw PCM at
`pcmLoopCounter(16000) = 1 + (3579545/16000 − 90 + 6)/13 = 1 + (223−84)/13 = 1+10 =
$0B` spins per sample (`Z80:187-206`; 90 cycles + loop). Not a track; no priority
interaction; the 68k game sits in `VBlank_SegaPCM` meanwhile. The SEGA loop checks for
no new sample — it is uninterruptible until it completes (`Z80:189-205`). CD DAC-04's
S1 analogue derived; CD DAC-05 is S3K-only (n/a here).

### 14.4 Engine today

Ring toggle lives in `AudioManager.ringLeft` (`AudioManager.java:76`, reset semantics
via `ResetRingAlternation`, `:643,913`) — adaptation GAP §1.2 #19: move into driver
RAM with reset at song load + stop-all so the RAM comparison sees it. Push latch:
absent (same row). SEGA PCM: `SegaPcmSpec` + lifecycle events; render at the Z80 loop
period (GAP §1.2 #20). Gloop and spindash transforms do not exist in S1 (CD REQ-02/
REQ-03 n/a).

---

## 15. DAC / PCM

### 15.1 68k side (`DACUpdateTrack` `SD:277-331`)

Duration expiry → `f_updating_dac := $80`; parse (flags via `CoordFlag`); a byte
`>= $80` is stored in `SavedDAC`; optional duration via the same `SetDuration`
multiply. Unless overridden: `$80` → rest (no write); `$81-$87` → write the byte to
`zDAC_Sample`; `$88-$8F` (bit 3) → `DAC_sample_rate[id-$88]` → `zTimpani_Pitch`
(`$A000EA`) then play `$83` — the pitch write is **permanent**: a later bare `$83`
reuses it (`SD:322-330`). `DAC_sample_rate` (`SD:339-345`, `$71CC4` =
`12 15 1C 1D FF FF`, MAP-verified): `$8C-$8D` are `$FF`, `$8E-$8F` index past the
table. S1 DAC ids: `dKick=$81, dSnare=$82, dTimpani=$83`, `dHiTimpani=$88..
dVLowTimpani=$8B` (`INC:89-91`).

### 15.2 Z80 side (`sound/z80.asm`, loaded by `DACDriverLoad` `S:1225-1240` at boot
`S:416` and title `S:1903`)

Interrupts disabled forever (`Z80:43-45`); one polling program:

- `zWaitDACLoop` (`Z80:74-80`): spin until `zDAC_Sample` bit 7; `id -= $81`, write
  back (claims the request); `>= 6` → SEGA path.
- DPCM (`Z80:84-181`): `zPCM_Table` entry (start, length, pitch, pad); set
  `zDAC_Status := $80`, `$2B := $80` (DAC on), `zDAC_Status := 0`; per byte: high
  nibble then low nibble through `zDACDecodeTbl` (16 delta bytes) accumulated into an
  8-bit value starting `$80`; each sample: `zDAC_Status := $FF`, `$2A := acc`,
  `zDAC_Status := $1F`, then `djnz` spins `pitch` times; after each byte re-read
  `zDAC_Sample` — bit 7 set aborts the current sample immediately (`Z80:171-173`).
  Per-byte cost from the source's own cycle annotations: **301 cycles per byte (two
  samples) plus pitch loops**, split 124/177 between the halves (`Z80:115-180`; the
  `; 301 in total` annotation and per-instruction counts; `SD:24` encodes the same
  number in `dpcmLoopCounter`). This is CD DAC-01's S1 half and CD DAC-03's missing
  derivation: the engine's `DacData.baseCycles = 301` for S1 (EM §1.3) matches the
  disassembly's per-byte figure.
- Pitch formula (`SD:22-24`): `1 + (Z80_Clock/rate − base + 6)/13`, `base = 90`
  (SEGA) / `150` (DPCM, = 301/2). Hand-evaluations: kick 8250 → `$17`; snare 24000 →
  `$01`; timpani 7375 → `$1B`; hi-timpani (7375×1.30 = 9587) → `$12` — matching the
  ROM `DAC_sample_rate` bytes (MAP-verified), which pins the integer-division
  semantics; the Z80-block `zPCM_Table` bytes themselves are Kosinski-compressed and
  unverified (§18 q7).

### 15.3 Handshake

68k→Z80: `zDAC_Sample`, `zTimpani_Pitch`, written with the bus held (except
`PlaySegaSound`). Z80→68k: `zDAC_Status` bit 7 — `UpdateMusic` refuses to hold the bus
mid-sample-write (§1.1). The driver never stops a playing sample except by starting a
new one; a DAC rest or stopped DAC track lets the current sample run out (pause §12,
fade §11 rely on this).

### 15.4 Test vectors

**TV15.1 — first GHZ kick.** From TV3.3: the DAC track's opening rest expires at U13 →
parse `dKick $81` (next byte `dSnare` is a sample → pushed back, `SavedDuration = 8`
reused): write `$81 → zDAC_Sample` at U13. Z80: claims it (`0` written back), plays
kick at `zPCM_Table` pitch `pcmLoopCounterBase(8250,150) = 1+(433−150+6)/13 = $17`
spins/sample.

**TV15.2 — timpani pitch latch.** DAC data `dHiTimpani …` then later a bare
`dTimpani`: first write `$12 → zTimpani_Pitch`, `$83 → zDAC_Sample`; the later `$83`
plays at pitch `$12`, **not** the base `$1B` — the latch never resets. Derivation:
`SD:322-330` and the warning comment `SD:326-327`.

### 15.5 Engine today

The Z80 loop is modelled inside `Ym2612Chip` from `DacData.baseCycles` (S1 = 301 ✓ per
§15.2; GAP §1.2 #21 keeps S2/S3K corrections there). Intra-frame perturbations (bus
holds, abort-on-new-sample mid-byte) are hardware timing outside the RAM/write oracle
(GAP §3, hard rule 3). The timpani pitch latch is driver RAM the engine's note→rate
map must reproduce as a *latch*, not a per-note lookup — verify `DacData`'s rate map
semantics (adaptation note; unverified).

---

## 16. FixBugs sites reachable by shipped data

The MAP §1 table lists all 24 sites; the subset a shipped stream or normal play
actually reaches, with the engine's current branch:

| # (MAP) | Site | Reached by | Engine today |
|---|---|---|---|
| 1/12 | queue slot 2 dead (`SD:193-196`, `1493-1512`) | structural — `QueueSound3` unused by the game | no queue model (GAP #4); model 2 live slots + dead third |
| 7 | `StopSFX` stale `a3` (`SD:1243-1251`) | fade-out or SFX stop while the GHZ/LZ waterfall plays on special FM4 — a normal gameplay path | not modelled; behaviour itself uncharacterised (§18 q1) — spec the trigger, defer the effect |
| 8/10 | `SetVoice` byte-only `d0` (`SD:1265-1271`, `1325-1331`) | every `StopSFX`/`StopSpecialSFX` — corruption only if the caller left a dirty upper word (§18 q2) | engine passes clean ints; divergence only in the corrupt case — record as not-modelled |
| 9 | PSG restore without playing check (`SD:1280-1289`) | any SFX on PSG3/noise stopping via `StopSFX` while special PSG3 is idle: the *special* track gets the bit-2 clear/at-rest instead of music PSG3 → music PSG3 keeps bit 2 until §5/§6 clears it | engine restores the music track — **divergence from shipped behaviour**; decide model-or-document |
| 11 | stop-all clears `$390` not `$3A0` (`SD:1469-1474`) | any stop-all after a waterfall: special PSG3 `VoicePtr`/`LoopCounters` survive | separate arrays; survivor bytes only visible to a RAM oracle — classify `not-compared` until modelled |
| 13 | `InitMusicPlayback` silences SFX-owned channels (`SD:1516-1543`) | every song load during any SFX | init burst unmodelled (GAP #7) — the SFX-audible cutout is part of the §5 burst spec |
| 14 | fade-in completion skips DAC `$B6` re-send (`SD:1652-1662`) | 1-up restore when `Mus88`'s or the restored song's DAC data panned during the fade | fade-in modelled as config; the missing pan re-send must be reproduced (do **not** clean up) |
| 16 | PSG3 key-off leaves noise (`SD:2005-2013`) | every PSG3/noise note-off (e.g. `SndCB` PSG3 track, GHZ PSG3 noise) | verify `stopNote` PSG path emits only `$DF` |
| 19 | SFX `$E6` reads `v_special_voice_ptr` (`SD:2391-2398`) | TV8.3: `SndA3/AC/B0/B7/BE/CC` (+ multi-track B2/B9/BF/CB/CF) | **divergence** — engine uses the track's own voice copy; decision item §8.3 |
| 21 | `Mus83` PSG3 notes overflow `PSGFrequencies` (`Mus83 - MZ.asm:183` per MAP) | MZ music, always | Java table has no overrun bytes — model tables as ROM-reads with neighbours (§7.3) |
| 22 | `Mus86` FM3 detune not reset at loop (`Mus86 - SBZ.asm:128` per MAP) | SBZ music | data-faithful playback reproduces it automatically — no engine action |
| 23 | `Mus91` stray `$E6 $0C` on a PSG track (`Mus91 - Credits.asm:731` per MAP) | Credits | exercises the `$E6`-on-PSG path (§10) — engine must add-then-read-FM-voice, not ignore |
| 24 | `SndBC` FM5 transpose `$90` (`SndBC - Teleport.asm:11`; MAP's `:7` is the FixBugs guard line) | Teleport SFX | index wraps via `& $7F` (§7.1) — data-faithful; no S1 engine patch exists (the S2 `$90→$10` patch, CD REQ-04/DEF-05, is S2-only) |

Unreachable by shipped data (state for completeness): #2/#3 (ids `$94-$9F`, `$D1-$DF`
— blocked from the sound test `S:2222-2229`, `S:2213`), #4 (no zero-FM song), #5/#6
(indices < `$40`), #15 (no negative non-`$80` envelope byte in `SD:46-64`), #17
(scenario not derivable, §13.1), #18 (`d0` clean in that path), #20 (menu-side).

---

## 17. ROM-read data tables

Address column: MAP §17 anchors (ROM addresses from `sonic.lst`, spot checks marked
there). "Engine copy" names the Java-resident duplicate GAP §3 wants replaced by (or
verified once against) a ROM read.

| Table | ROM | Size | Engine copy / reader |
|---|---|---|---|
| `SoundPriorities` | `$71AE8` (`SD:131-138`) | 101 B | `Sonic1SmpsConstants.SOUND_PRIORITIES` (Java; ROM address declared as `SOUND_PRIORITIES_ADDR`, unread in production — EM §1.3) |
| `SpeedUpIndex` | `$71A94` (`SD:74-93`) | 8 B (+overrun into `MusicIndex`) | `Sonic1SmpsSequencerConfig.SPEED_UP_TEMPOS` (Java; equal for `$81-$88`, no overrun rule) |
| `MusicIndex` | `$71A9C` (`SD:99-119`) | 19 ptr | `Sonic1SmpsLoader` (ROM-read ✓) |
| `SoundIndex` / `SpecSoundIndex` | `$78B44` / `$78C04` (`SD:2686-2742`) | 48 / 1 ptr | `Sonic1SmpsLoader` (ROM-read ✓) |
| `PSG_Index` + envelopes | `SD:41-64` | 9 envelopes | S1 loader table (ROM-read per EM §1.3 ✓) |
| `FMFrequencies` | `$72790` (`SD:1801-1809`) | 96 w | `SmpsSequencer.FNUM_TABLE_68K` (Java) — verify against ROM incl. overrun neighbours |
| `PSGFrequencies` | `$729CE` (`SD:2057-2063`) | 70 w | `PSG_FREQ_TABLE_68K` (Java) — same |
| `DAC_sample_rate` | `$71CC4` (`SD:339-345`) | 6 B | `DacData` note map |
| `FMSlotMask` | `$72CAC` (`SD:2379`) | 8 B | `VolMode.ALGO` table |
| `FMDACInitBytes`/`PSGInitBytes` | `SD:964-971` | 7+3 B | channel-order config |
| Z80 DAC driver (Kosinski) | `$72E7C` (`SD:2632`) | — | `Sonic1SmpsLoader.loadDacData` (ROM-read ✓) |
| `SegaPCM` | `$79688` (`SD:2858`; must not cross `$8000`) | — | `SegaPcmSpec` |

Each Java copy needs a one-time equality check against the ROM bytes (GAP §3
"ROM-read data tables"); removal of the copies is deferred implementation (GAP §4.3
item 13).

---

## 18. Open and hardware questions

Carried unchanged from MAP §18 (q1-q11), owned by this spec's sections: q1 (`StopSFX`
stale `a3` effect) §16; q2 (byte-only `d0` upper-word dirtiness — an interrupt-path
question) §16; q3 (**partially retired**: TV8.3 enumerates the shipped SFX that reach
FixBugs #19; remaining: the ROM bytes read through a zero `v_special_voice_ptr`) §8;
q4 (H-int deferral frequency in real LZ play — game-state) §1; q5 (`$2B` on
restore scenario) §13; q6 (zero-FM path — unreachable) §16; q7 (`zPCM_Table` bytes vs
the compressed blob) §15; q8 (DPCM accumulator wrap and the generated sample files)
§15; q9 (gosub stack vs `LoopCounters[4..11]` in shipped songs) §2; q10 (queue-write
phase vs V-int) §3; q11 (busy-poll duration — hardware) §7.

From EM: q5 (which `Freq` stores are detuned — for S1, `Freq` always stores the
**base** table word; detune and modulation are added at write time, `SD:524-545`,
`SD:509-515` — settled for S1, remains open for S3K); q8 (S1 lag-frame call —
settled §1.1: lag frames call it once; the H-int path defers a call, it never doubles one).

Hardware-question register (not answerable from this source): YM2612 busy-window
duration (q11); the effective result of TL bytes ≥ `$80` (chip masking); DPCM delta
table audio character (q8).

New from this spec: none — every ambiguity met during derivation mapped onto an
existing question.

---

## 19. Claims-digest coverage (S1)

Status vocabulary per CD §14: **derived** (this spec derives it from the disassembly,
section cited), **corrected** (derived with a material correction to the claim's
content or anchor), **n/a** (not applicable to S1).

| CD row | Status | Where / correction |
|---|---|---|
| CAD-01 (S1 half) | derived | §3 TV3.2, §5 (anchor `Sound_PlayBGM :754` ✓) |
| CAD-02 | n/a (S2/S3K) | S1 contrast in §3.1: track walk always runs; holds via `TempoWait` +1 |
| CAD-03 | derived | §3.1 (anchors `:147`/`:174-176`, `cfSetTempo :2256` ✓); `$EA` also resets the countdown |
| CAD-08 (S1 half) | derived | §1.1 — no PAL path exists in the S1 driver |
| CAD-10 | derived | §1.1 — music DAC→FM→PSG, then SFX, then special |
| CAD-11 | derived | §1.1 — 68k driver, one pass per V-int (the H-int deferral moves a pass, never adds one) |
| CAD-12 (S1) | derived | §13.2 — timeout reset = definite phase; `SpeedUpIndex` per-song |
| ADM-01 (S1) | **corrected** | §4.1: the global gate lives in `CycleSoundQueue` (`SD:637-672`), not `Sound_PlaySFX :977`; rejection precedes track init; bit 7 = accept-but-never-store; clear sites §4.1/§6.2 |
| ADM-03 (S1) | derived | §6.1 — channels claimed at request time (bit 2 during the load) |
| ADM-04 (S1) | derived | §6.2 — applies to FM too: at-rest + voice re-upload, no freq/key-on |
| ADM-05 | n/a (S2) | — |
| ADM-06 | **corrected** | §5 step 7: SFX and priority latch survive, but `f_push_playing` does *not*, and special SFX also survive and re-mark |
| ADM-08 | derived | aggregate of §4/§6 |
| REQ-01 (S1 part) | derived | §14.1 — substitution to `$CE`, toggle, reset at song load/stop-all; CD §15 "S1 equivalents unnamed" → `SD:984-991`; `RAM:63` comment sides reversed |
| REQ-02/03 | n/a (S2/S3K) | no gloop or spindash transform exists in the S1 driver |
| REQ-04 | n/a (S2) | S1's own transpose quirk is `SndBC` `$90` (§16 #24) |
| REQ-05 | derived | §4.1 — disjoint `SoundIndex`/`SpecSoundIndex` |
| OVR-01 (S1) | derived | §13.1 (ram anchors `v_1up_ram` `RAM:34`, `f_1up_playing` `RAM:59` ✓) |
| OVR-02 (S1) | derived | §13.1 — `.bgmnot1up` clears the flag; backup abandoned |
| OVR-03 | **corrected** | §13.1 — normal SFX killed (silenced by the init burst, not key-off); **special SFX keep playing**; new SFX/special refused until the fade-in ends |
| OVR-04 (S1) | derived | §13.1 — prio cleared before *and* after the copy |
| OVR-05 (S1) | derived | §13.1/§11 — SFX reopen when `DoFadeIn` clears the flag (pass 121) |
| OVR-08 | derived | §13.1 (FixBugs #17; anchor `:2166` ✓) |
| OVR-09 (S1) | derived | §13.1 — second `$88` ignored (`SD:757-758`) |
| FADE-01 (S1 open) | **answered** | §11.1 — music FM+PSG fade; DAC stopped up-front; SFX/special stopped at start |
| FADE-02 | derived | §11.1 (anchor `:1360` ✓) |
| FADE-03 (S1) | derived | §11.1 (`SD:1366`) |
| FADE-04 (S1) | derived | §11.1 — terminal count → stop-all, no final step |
| FADE-05 (S1 part) | derived | §11.2 — delay 3, 39 steps, stop-all at pass 160 |
| PAUSE-01 | derived | §12.1 (anchors `:555/:584/:628` ✓) |
| PAUSE-04 (S1) | derived | §12.1/§15.3 — Z80 never paused; sample runs out |
| PAUSE-05 (S1) | **corrected** | §12.1 — resume re-sends pan for music **and SFX and special FM** tracks (playing, non-overridden), not music only |
| VOICE-02 | derived | §6.1/TV6.1 — no admission-time writes; SetVoice→note-off→freq→key-on at first update |
| VOICE-03 | out of scope | a measurement claim about the oracle, not derivable from source; the behaviours it aggregates are §5-§10 |
| VOICE-05 (S1 driver half) | derived | §7.1 — busy-poll before both halves of every FM write; window duration stays a hardware question |
| SEQ-04 | **corrected** | §9.2 — all nine envelopes end in `$80`, but the terminator is never *applied*: `VolEnvHold` steps the index back so the last value repeats |
| SEQ-05 (S1) | derived | aggregate §3/§9 |
| DAC-01 (S1 half) | derived | §15.2 — 301 cycles/byte, halves 124/177, stall via `zDAC_Status` handshake |
| DAC-03 (S1 half) | **answered** | §15.2 — the derivation now exists: `SD:22-24` + `Z80:115-180` annotations; engine 301 matches |
| DAC-04/05 | n/a (S3K) | S1 SEGA analogue in §14.3 |
| DATA-02 | not a driver spec item | loader policy (GAP §3) |
| CHIP-01 (S1 note) | derived | §1.1 — no PAL compensation of any kind in the driver |
| DEF-09/10/12 (S1 parts) | context | §7.1 (busy-poll), §13 (single save slot), §4.1 (disjoint tables — the ROM behaviour DEF-12's fix restored) |
| CD §15 anchors | **corrected** | `DOTEMPO` → `UpdateMusic` `SD:174-176` + `TempoWait` `SD:1549`; `PlayMusic` → `Sound_PlayBGM` `SD:754`; `Sound_Play` → comment alias of `CycleSoundQueue` (`SD:636`); `UpdateSFX` → no such routine, SFX update is the `UpdateMusic` track walk (`SD:235-256`); S1 ring equivalent → `SD:984-991` |

Rows not listed (CAD-04..07, CAD-09, ADM-02, ADM-07, ADM-09, REQ-06/07, OVR-06/07/10,
FADE-06, PAUSE-02/03, VOICE-01/04, SEQ-01..03, DAC-02, DAC-06, DATA-01/03, CHIP-02..08,
DEF-01..08, DEF-11) are S2/S3K or chip/config rows outside this game's driver spec.

---

## 20. Review record (adversarial pass, 2026-08-30)

Reviewed on `feature/ai-sdre-spec-review-s1` under the same sources-closed rule: full
re-read of `s1.sounddriver.asm` (all 2867 lines) and `s1.sounddriver.ram.asm`,
`sound/z80.asm` in full, and targeted reads of `sonic.asm`, `_Constants.asm`,
`_inc/Queue Sound Routines.asm`, `_inc/PauseGame.asm`, `sound/_smps2asm_inc.asm`, and the
cited song/SFX data files. More than half of the test vectors were recomputed by hand from
the disassembly. Verdicts: **refuted** — the claim contradicted the disassembly and has
been corrected in place; **weakened** — materially imprecise, tightened in place;
**stands** — re-derived and confirmed unchanged.

| # | Claim as originally written | Verdict | Evidence |
|---|---|---|---|
| R1 | §1.1: `f_doupdatesinhblank` produces a **second** driver call in the same frame; "music advances two ticks on such frames" | **refuted, fixed** | The LZ water workaround in `VBlank_Levels` sets the flag and pops the return into `VBlank_Music` (`addq.l #4,sp`, comment "postpone updating the sound driver as well", `S:846-854`), so the V-int pass is skipped; the H-int handler then runs the frame's single `UpdateMusic` pass (`S:1050-1064`). A deferral, never a doubling. Ripples fixed: §1.1 oracle tick definition, §1.5, §18 q4 and EM-q8 wording, §19 CAD-11 |
| R2 | §7.1: `PSGUpdateFreq` writes the period "every frame the track is neither resting nor overridden" | **refuted, fixed** | On non-note-start frames the `jsr PSGUpdateFreq` (`SD:1826`) is reached only when `DoModulation` returns through its step path; the `+4` entry tamper (`SD:485`) otherwise returns straight to the track walk. PSG frequency cadence = note start + modulation-step frames, same as FM |
| R3 | §9.2/TV9.2: after the `$80` terminator "the last pre-terminator value repeats forever" / "`$98` every subsequent frame of the note" | **refuted, fixed** | `VolEnvHold` (`SD:1991-1993`) decrements the index and returns **without** calling `SetPSGVolume`; from the terminator frame on no envelope volume write is emitted — the chip retains the last byte sent |
| R4 | TV5.1: load-burst note-offs `$28←$00,$01,$02,$04,$05,$06` | **refuted, fixed** | GHZ declares `Chan $06` (DAC+FM1-5); music FM6's `VoiceControl` is still 0 after the `InitMusicPlayback` clear, so the sixth `FMNoteOff` emits `$28←$00` (`SD:944-951`; the shipped-branch comment `SD:1526-1535` names the undefined-channel gap) |
| R5 | TV7.1: `FM_Sample_Rate = 7670453/144 = 53266` | **refuted, fixed** | Integer division gives 53267 (`144·53267 = 7670448 ≤ 7670453`). Recomputed entries 0/1/5 are unchanged (`$25E`, `$284`, `$32D`), so no downstream vector moves |
| R6 | §11.1: fade-out start "DAC track bit 7 cleared" | **weakened, fixed** | `clr.b v_music_dac_track.PlaybackControl` (`SD:1365`) zeroes the whole byte — same audible effect, different RAM image for the §2 field registry |
| R7 | §5 step 6: after `$2B := 0` "nothing re-enables it except `StopAllSound`" | **weakened, fixed** | The Z80 writes `$2B := $80` whenever it starts a DPCM sample (`Z80:103-106`); the claim holds in shipped play only because `Mus89`/`Mus93`'s DAC label aliases the stopped PSG3 data, so their DAC track reaches `$F2` without writing a sample byte |
| R8 | §5 step 5: "the PSG header's 4th byte is read and dropped" | **weakened, fixed** | The dropped byte is the per-track frequency-envelope byte between volume and envelope id (the 5th data byte of the 6-byte entry), `SD:903` with `INC:340-355` |
| R9 | §16 #24 anchor `SndBC - Teleport.asm:7` | **weakened, fixed** | The shipped `$90` transpose byte is line 11; line 7 is the `FixMusicAndSFXDataBugs` guard |
| R10 | §14.1: "The byte's bit 0 is toggled either way" | **weakened, fixed** | The toggle is `bchg #0` on `v_ring_speaker` (`SD:991`), not on the request byte |
| R11 | §6.2: `cfStopTrack` "performs the same restore" as `StopSFX` | **weakened, fixed** | Two checks differ: `cfStopTrack`'s music-FM restore runs only if the music track is playing (`SD:2525-2526`), and its PSG restore checks special PSG3 is playing (`SD:2542-2543`) — the check whose absence in `StopSFX` is FixBugs #9 |

Recomputed from the disassembly and **standing** (no change needed): TV2.1-TV2.2 (RAM
clear at `S:409-413`; GHZ init image incl. `FMDACInitBytes` 6,0,1,2,4,5 and PSG voice ids
3/6/4); TV3.1-TV3.3 (hold cadence, first-note latency, divider stretch to U13); TV4.1-TV4.2
(write-back serialisation, bit-7 accept-but-never-store, `$BF=$7F` vs `$AE=$60` rejection;
priority table bytes re-read in full); TV5.2; TV6.1 (all 31 voice-upload writes recomputed
against the `SndB5` raw voice block, `$E0 $40` compose to `$B5←$40`, `$2B2D` frequency,
`$28←$F5` key-on), TV6.2 (restore emits no frequency/key-on), TV6.3 (special-layer
precedence, `SD:1072-1075`/`SD:2512-2518`); TV7.1 values, TV7.2 (`$140`→`$80,$14,$90`;
`$EF`→`$8F,$0E,$90`; note ids `nF2=$9E`, `nBb2=$A3`), TV7.3; TV8.1-TV8.2 (mask `$A`
LSB-first over storage order op1,op3,op2,op4; `SendVoiceTL` carry skip vs `SetVoice`'s
none), TV8.3 (the `smpsAlterVol` grep reproduces exactly the listed normal SFX; `SndD0`
also contains `$E6` but is special-phase, where `v_special_voice_ptr` is the *correct*
bank); TV9.1 (`$87,$0E` at U(n+3)), TV9.3 (`$225E+$5D=$22BB` on part II offset 0);
TV10.1-TV10.2; TV11.1 (delay burn U1-U3, 39 steps U4→U156, stop-all at U160), TV11.2;
TV12.1-TV12.2 (pause/unpause bursts, resume covering music+SFX+special FM);
TV13.1-TV13.3 (backup/restore images, 1-up-aware `$E2`/`$E3` editing the backup,
`Mus88` tempo `$05` = its `SpeedUpIndex` byte, fade-in completion on pass 121, no `$B6`
re-send); TV14.1 (`SndCE` = FM4, `panLeft`, `nE5 $04`; `sfx_RingLeft` unreferenced by game
code outside `_Constants.asm`), TV14.2 (`$1F`-frame latch window); TV15.1-TV15.2 (U13
kick, pitch bytes `$17`/`$01`/`$1B`/`$12` re-derived from `pcmLoopCounterBase`, the
`DAC_sample_rate` bytes `12 15 1C 1D`, timpani latch warning `SD:326-327`); the SEGA
`$0B` loop count and 68k busy-wait `$12×$10000`; the Z80 per-byte 301-cycle split 124/177
(summed from the source's own per-instruction annotations); `StopAllSound`'s `$390`-byte
clear leaving special-PSG3 offsets `$20-$2F`; `InitMusicPlayback`'s exact save/restore
set; `TempoWait`'s unconditional ten-slot loop; the queue/dispatch ranges and stack-tamper
table of §1.1; the §2 RAM offsets and gosub/loop-counter overlap; all 26 coord-flag rows
of §10 (`cfOpF9`'s fixed part-I `$88`/`$8C` writes included); and the "Engine today"
anchors spot-checked at the merged head (`S1AudioFieldRegistry` = 29 `field(` entries;
`SPEED_UP_TEMPOS` equal to ROM `07 72 73 26 15 08 FF 05`; `setChannelOverridden`'s
restore-frequency divergence at `SmpsSequencer.java:683-685`; the TIMEOUT extension loop's
`t.active && t.duration > 0` gate at `SmpsSequencer.java:1266-1281`).

Not independently re-derived here: MAP-verified ROM byte spot checks (`$71A94`,
`SoundPriorities` bytes at `$71AE8`, `DAC_sample_rate` at `$71CC4` — re-derived from the
build formulas above but not re-read from a ROM dump), and the EM/GAP/CD engine-history
statements the spec quotes as inputs. No claim in the spec was traced to the reverted
parity branch or any third-party SMPS source.
