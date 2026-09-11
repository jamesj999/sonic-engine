# S3K Speed-Shoes Byte-Timer Modeling Design

## Goal

Model Sonic 3 & Knuckles' speed-shoes expiry timer at its true ROM granularity
(a byte counter decremented every 8th level frame) so the engine can tick speed
shoes at ROM-accurate display time uniformly across S1/S2/S3K, and then retire
the `speedShoesTimerPrePhysicsExtraTicks` compensation field without regressing
the S3K CNZ trace frontier.

## Implementation status (2026-06-01): RESOLVED — implemented

### 2026-07-16 correction: low-byte address semantics

The cadence constant is now zero: `(Level_frame_counter+1).w` is an absolute
address expression selecting the low byte of the word, not an arithmetic
`Level_frame_counter + 1` value. Native `andi.b #7` therefore accepts counter
low-byte zero. Standalone MGZ exposed the old phase at speed-shoes expiry;
correcting it advanced physics from frame 6496 to 7346 and animation from 6524
to 7366 while both full green fleets held. The historical diagnosis below is
retained as the record of the earlier implementation decision, but its
`ALIGN=7` conclusion is superseded by this address-semantics correction.

Landed: `PhysicsFeatureSet.speedShoesTimerDecimation` (S1/S2 `1`, S3K `8`) and a
`SpeedShoesTimer` that counts from `ROM_DURATION_FRAMES / decimation` (150 for
S3K) and decrements only on aligned level frames. The decrement gate uses
`(frameCounter + 7) & (decimation-1) == 0` (`LEVEL_FRAME_PHASE_OFFSET = 7`).

The phase offset is the crux: the engine `LevelManager` frame counter, read at the
pre-physics `TimerManager.update()` point, leads ROM `Level_frame_counter` (as
read in `Sonic_Display`) by **2 (mod 8)** — a constant seed-phase offset. ROM
decrements on `Level_frame_counter & 7 == 7`, which maps to engine
`frameCounter & 7 == 1`, i.e. ALIGN 7. The earlier ALIGN=1 was wrong because it
was derived from the `AizFlippingBridge` `(frameCounter+3)&7` gate, which only
drives an **SFX** — a field trace replay does NOT compare, so that calibration
was never validated. The CNZ speed-shoes phase IS validated (via x_speed).

Validation (ROM paths set): all three S3K trace frontiers hold at baseline with
no regression — CNZ f17276 (1952 err), AIZ f8941 (789 err), MGZ f4124 (3852 err).
S1/S2 are byte-identical (decimation 1): WFZ holds f8863, EHZ1 passes, GHZ1
passes. Unit guards pass: `TestSpeedShoesTimer`, `TestPhysicsProfile`,
`TestHybridPhysicsFeatureSet`. The +2 offset holding across CNZ/AIZ/MGZ confirms
it is a genuine constant seed-phase property, not a per-trace fit.

The byte timer still decrements at the pre-physics tick site (not display time);
removing `speedShoesTimerPrePhysicsExtraTicks` and unifying to display-time
ticking remains optional future cleanup, now unblocked.

### Earlier attempt (superseded by the above)

A first implementation of this design was built and validated, then reverted:
`speedShoesTimerDecimation` (S1/S2 `1`, S3K `8`) on `PhysicsFeatureSet`,
`SpeedShoesTimer` duration `ROM_DURATION_FRAMES / decimation` (150 for S3K) and a
decrement gated on `(LevelManager.getFrameCounter() + 1) & (decimation-1) == 0`
at the existing pre-physics tick site. `ALIGN = 1` was derived (not guessed) from
the matching `(frameCounter + 3) & 7` gate in `AizFlippingBridgeObjectInstance`,
which shows the engine frame counter is phase-aligned (offset 0) with ROM
`Level_frame_counter` mod 8 at the object-update read point.

Result against the acceptance guards (ROM paths set):
- Unit tests pass; S1/S2 byte-identical (decimation 1).
- `TestS3kAizTraceReplay` held f8941; `TestS3kMgzTraceReplay` held f4124.
- **`TestS3kCnzTraceReplay` REGRESSED f17276 -> f6566** (x_speed expected
  `0x0368`, actual `0x0380`; `+0x18` = one boosted-accel frame, i.e. shoes
  mistimed). A null-level fallback bug was ruled out (fixing it changed nothing,
  so the level manager is available and advancing at the read point).

Interpretation: the committed per-frame approximation is **load-bearing** for the
CNZ trace. The ROM-accurate every-8th-frame gate produces an *immediate*
divergence at f6566 (not a <=8-frame expiry shift), which means the engine frame
counter's phase during S3K CNZ trace replay does not reproduce ROM's
`Level_frame_counter` at the speed-shoes window the way the AIZ object-update read
point does — so the every-8th-frame decrement fires on the wrong absolute frames
and mistimes expiry, while the phase-independent per-frame model is immune. AIZ
holding f8941 is consistent: its trace likely does not exercise a speed-shoes
expiry in a phase-sensitive window.

### Frame-by-frame diagnosis (2026-06-01)

Instrumented the committed (per-frame) run, which matches the CNZ trace through
the unrelated f17276 frontier, to capture the speed-shoes window:

- `SHOE_PICKUP frameCounter=5369`; `SHOE_EXPIRE frameCounter=6569` — i.e. expiry
  is **exactly pickup + 1200**. The per-frame timer is **pickup-relative**.
- The trace divergence is at trace frame 6566 (gameplay_frame_counter 6567),
  inside that window.
- The decimation-8 model (ALIGN=1, gate `(fc+1)&7==0`) takes its first decrement
  at fc=5375 and expires at `5375 + 149*8 = 6567` — a **1198-frame span, 2 frames
  early**. Those 2 frames leave shoes active at gfc 6567 in one model but not the
  other, producing the observed `x_speed +0x18` divergence.

**Why per-frame uniquely matches:** the trace's expiry is pickup-relative
(pickup+1200); the per-frame timer is pickup-relative and phase-independent, so
it is immune. The every-8th-frame timer is **global-grid quantized** (gated on
`Level_frame_counter`), so its expiry depends on the engine frame counter's grid
phase at the decrement read point. To land expiry at 6569 (matching the trace)
the gate would need `fc&7==1` (ALIGN=7), which **contradicts** the principled
ROM value ALIGN=1 derived from the AIZ-bridge calibration. That contradiction
means the engine level frame counter is **not reliably congruent to ROM
`Level_frame_counter` (mod 8)** at the speed-shoes read point — the pre-physics
`TimerManager.update()` reads the counter at a different grid phase (~2 frames
off) than the `Sonic_Display`/object-update point ROM and the AIZ bridge use.

**Revised blocker (refined):** the byte timer cannot be implemented in a
principled (non-trace-fitting) way until the frame-counter grid phase is made
trustworthy at the decrement read point. Concrete next steps:
1. Instrument the decrement to log the frame counter it actually reads vs the
   pickup frame, on both AIZ and CNZ, and measure the constant offset between
   the pre-physics read, the object-update read, and ROM `Level_frame_counter`.
2. Decrement the byte timer at the **display/object-update** read point
   (`tickStatus`), where the AIZ-bridge phase is verified, using the principled
   `(fc+1)&7==0` — and confirm the offset is 0 there (this diagnosis suggests it
   may still be off; step 1 settles it).
3. Only ship the byte timer once expiry matches the trace with the principled
   ALIGN, never by setting ALIGN to fit one trace.

Until then the pickup-relative per-frame approximation is retained: it is
correct-by-robustness (phase-independent) and is the only model that holds the
S3K CNZ frontier at f17276.

## Background

`SpeedShoesTimer` (`com.openggf.timer.timers`) currently models speed shoes for
all three games as a per-frame countdown from `ROM_DURATION_FRAMES = 0x4B0`
(1200), decremented in the pre-physics `TimerManager.update()`
(GameLoop:592 / HeadlessTestRunner.stepFrame, before `LevelFrameStep`).

Two distinct per-game differences are at play; the spec keeps them separate:

- **Timing offset** — whether the timer decrements before or after player
  movement within a frame. ROM decrements in the player's display routine,
  after movement. The engine ticks pre-physics, so
  `PhysicsFeatureSet.speedShoesTimerPrePhysicsExtraTicks` (S1 `0`, S2 `1`,
  S3K `0`) adds frames to compensate. The S2 `+1` is movement-equivalent to
  ROM `Obj01_ChkShoes` (s2.asm:36008-36025), which is why it yields the correct
  boosted-frame count.
- **Granularity** — the timer's width and decrement cadence. This is **not
  modeled today** and is the subject of this spec.

ROM granularity, verified:
- **S1/S2:** a **word** timer (`subq.w #1`) decremented **every** level frame,
  initialised to `(20*60) = 1200` (0x4B0). s1disasm/_incObj/01 Sonic.asm:175-184;
  s2.asm:36008-36025 `Obj01_ChkShoes`.
- **S3K:** a **byte** timer (`subq.b #1`) decremented **only on every 8th**
  level frame, initialised to `(20*60)/8 = 150`. `Sonic_ChkShoes`
  (sonic3k.asm:22067-22090):
  ```
  Sonic_ChkShoes:
      btst    #Status_SpeedShoes,status_secondary(a0)
      beq.s   Sonic_ExitChk
      tst.b   speed_shoes_timer(a0)
      beq.s   Sonic_ExitChk
      move.b  (Level_frame_counter+1).w,d0   ; low byte of the GLOBAL counter
      andi.b  #7,d0
      bne.s   Sonic_ExitChk                  ; gate: only every 8th frame
      subq.b  #1,speed_shoes_timer(a0)
      ...                                     ; on 0: restore speeds, clear bit, Change_Music_Tempo
  ```
  Init: `move.b #(20*60)/8,speed_shoes_timer(a1)` (sonic3k.asm:40818).

Both schemes expire after 1200 wall-clock frames; only the storage width and the
decrement cadence differ. **The 8-frame phase is derived from the GLOBAL
`Level_frame_counter`, not a per-sprite field** — every S3K speed-shoes timer in
play shares the same decrement frames.

On 2026-06-01 (see `docs/status/trace-frontier-log.md`) an attempt to make speed shoes
tick at display time uniformly — a `DisplayPhaseTimer` ticked from
`AbstractPlayableSprite.tickStatus()` (the engine's `Sonic_Display` analog),
with the `+1` field removed — was implemented and validated:

- S1/S2: behaviourally identical to the `+1` constant (CNZ 199 @ f3906,
  WFZ 277 @ f8863, MTZ3 995 @ f6913, `TestS2Ehz1TraceReplay` + variants PASS).
- S3K: **regressed** `TestS3kCnzTraceReplay` from f17276 (1952 errors) to f6568
  (3852 errors), because the per-frame approximation of S3K's every-8th-frame
  byte timer is misaligned by the pre→post-movement move.

The pre/post-movement timing is not S3K's real error; its every-8th-frame
granularity is. That attempt was reverted; the per-game `+1` remains.

## Design

### Granularity predicate (PhysicsFeatureSet)

Per CLAUDE.md, per-game ROM-mechanism differences are expressed as
`PhysicsFeatureSet` flags, never game-name `if/else`. (This is the sanctioned
pattern, not a zone/route/frame carve-out — the no-carve-out rule targets
zone/route/frame branches, not legitimate per-game feature flags.)

Add one field:

```
/** Level-frame cadence at which the speed-shoes timer decrements. S1/S2 use a
 *  per-frame word timer ({@code 1}); S3K uses a byte timer decremented only on
 *  every 8th level frame ({@code 8}) — ROM Sonic_ChkShoes gates subq.b on
 *  (Level_frame_counter+1) & 7 == 0 (sonic3k.asm:22072-22078). */
int speedShoesTimerDecimation   // SONIC_1 = 1, SONIC_2 = 1, SONIC_3K = 8
```

`SpeedShoesTimer` initial duration becomes `ROM_DURATION_FRAMES / decimation`
(1200 for S1/S2; 150 for S3K), and the timer decrements only on frames where the
global level-frame phase is aligned (see below). Removes the now-redundant
`durationFrames` helper's dependence on the deleted compensation field.

### Phase tracking and rewind

The decrement cadence is gated on the GLOBAL level frame counter, so **no
per-sprite phase field is introduced**:

- Decrement when `(levelFrameCounter + ALIGN) & (decimation - 1) == 0`, where
  `levelFrameCounter` is the engine's level frame counter (the same counter the
  trace records as `gameplayFrameCounter` and that `sidekickCpuUsesLevelFrameCounter`
  consumes). For `decimation == 1` this is always true (S1/S2 unchanged).
- `ALIGN` calibrates the engine counter's origin to ROM's `(Level_frame_counter+1)`.
  Getting this offset right is the primary implementation risk and is guarded by
  a phase-alignment test (below).
- **Rewind:** the per-sprite state needing capture is only the timer's remaining
  decimated count, which `TimerManagerSnapshot` already records (`{code, ticks}`)
  — no snapshot-shape change. Correctness depends on the level frame counter
  itself being restored on rewind. Confirm that the engine's level frame counter
  is part of the rewound state; **if it is not, capture it** (the granularity
  fix must not silently desync the phase across a rewind).

### Tick site

Keep the existing pre-physics `TimerManager.update()` site for this change so
S1/S2 behaviour stays bit-identical to today. Moving all games to display-time
ticking (a `DisplayPhaseTimer` ticked from `tickStatus()`) and deleting
`speedShoesTimerPrePhysicsExtraTicks` is a **follow-up step**, valid only once
S3K granularity is correct (it is what regressed S3K CNZ when attempted first).

### Music slowdown

On expiry, ROM jumps to `Change_Music_Tempo` (sonic3k.asm:22090). Preserve the
existing `SpeedShoesTimer.perform()` music-off path; it must fire on the frame
the decimated count reaches zero (i.e. on an aligned frame), unchanged for
S1/S2.

## Scope

- Add `speedShoesTimerDecimation` to `PhysicsFeatureSet` (S1/S2 `1`, S3K `8`),
  its `CrossGameFeatureProvider` passthrough, and the test constructors.
- Make `SpeedShoesTimer` duration and decrement cadence honour the decimation
  and the global-frame phase gate.
- Verify (and, only if necessary, add) rewind capture of the level frame counter.

## Non-Goals

- Do not change S1/S2 boosted-movement-frame counts (must stay equivalent to the
  current `+1` behaviour: WFZ frontier f8863, EHZ1 passing).
- Do not move the tick to display time or remove
  `speedShoesTimerPrePhysicsExtraTicks` in this change (that is the follow-up
  once granularity is correct).
- Do not attempt to fix the unrelated pre-existing frontiers: S2 CNZ f3906
  `tails_y`, S3K CNZ f17276 `x_speed`. The S3K-CNZ acceptance is "no regression
  past f17276", not "fix f17276".
- Do not alter the rewind snapshot record shape unless the level frame counter
  proves not to be rewound.

## Acceptance

Regression guards (trace sweep, ROM paths set):
- `TestS3kCnzTraceReplay#replayMatchesTrace` first-error frame stays at or beyond
  the committed **f17276** baseline.
- Broaden beyond CNZ: `TestS3kAizTraceReplay` (>= committed f8941) and
  `TestS3kMgzTraceReplay` (>= committed f4124) do not regress — a phase-alignment
  bug would surface across all S3K traces, not just CNZ.
- `TestS2WfzLevelSelectTraceReplay` holds frontier f8863;
  `TestS2Ehz1TraceReplay`, `TestS1Ghz1TraceReplay`, `TestS1Mz1TraceReplay` pass.

Determinism + alignment:
- A rewind-determinism test: with S3K speed shoes active mid-timing, rewind to a
  frame inside the active window and resume; output must be bit-identical to
  continuous play through that frame (guards phase/count desync across rewind).
- A phase-alignment unit test: assert the S3K timer decrements on the expected
  aligned frames relative to the engine level frame counter (e.g. fixed frames
  spanning an 8-frame window), pinning the `ALIGN` calibration.

Unit guards:
- `TestTimerManager`, `TestPhysicsProfile`, `TestHybridPhysicsFeatureSet`, and a
  new `SpeedShoesTimer` decimation test.
- `speedShoesTimerPrePhysicsExtraTicks` is unchanged by this spec (removed only
  in the follow-up).

## References

- `docs/status/trace-frontier-log.md` — 2026-06-01 speed-shoes timing entry.
- sonic3k.asm:22067-22090 `Sonic_ChkShoes` (every-8th-frame gate + decrement);
  sonic3k.asm:40818 init `(20*60)/8`.
- s2.asm:36008-36025 `Obj01_ChkShoes`; s1disasm/_incObj/01 Sonic.asm:175-184.
