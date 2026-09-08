# Sonic 1 Object Implementation — ROM Behavioural Pitfalls

Source-cited examples accumulated from trace fixes. Search headings and symptoms,
then read entries relevant to the object or divergence being investigated:

```bash
rg -n '^##|timer|standing|touch|child|slot|camera' <skill-dir>/rom-pitfalls.md
```

Entries describe historical fixes, not universal implementation requirements.
Verify the current code and the target game's ROM routine before applying a
cross-game analogy. Preserve useful citations when adding or correcting an entry;
read-only investigation does not require expanding this catalog.

---

## P1 — CalcSine orbital position uses integer arithmetic shift, not floating-point round

**Symptom.** A satellite or orbiting child object is 1 px off its ROM
position in one or both axes, causing an off-by-one touch contact (premature
hurt, missed bounce) compared to ROM. The discrepancy is only visible at
certain orbit angles.

**Root cause.** ROM `Orb_CircleSpikeball` (and any object that calls
`CalcSine` to drive orbital/circular motion) does:

```asm
jsr CalcSine        ; d0 = sine (-0x100..0x100), d1 = cosine
asr.w #4,d1        ; cosine >> 4  (arithmetic right shift, truncates)
add.w obX(a1),d1   ; parent X + (cosine >> 4)
asr.w #4,d0        ; sine >> 4
add.w obY(a1),d0   ; parent Y + (sine >> 4)
```

Naive engine port uses `(int) Math.round(Math.cos(radians) * 16.0)`.
`CalcSine` sine=254 → `254 >> 4 = 15`; floating-point `Math.round(254/256.0 * 16) = Math.round(15.875) = 16`.
The 1-unit rounding difference places the child 1 px further from the parent
than ROM, which can make touch boxes meet when they should miss (or miss when
they should meet).

**What to check.** Any circular/orbital motion driven by `CalcSine`:
use `TrigLookupTable.sinHex(angle) >> 4` and
`TrigLookupTable.cosHex(angle) >> 4` for the positional components.
Do not use `Math.sin`/`Math.cos` with `Math.round` for ROM-accurate placement.

**ROM citation.** `docs/s1disasm/_incObj/60 Badnik - Orbinaut.asm:181-191`
(`Orb_CircleSpikeball`). `CalcSine` defined at
`docs/s1disasm/_incObj/sub CalcSine.asm`.

**Cross-game note.** S2 and S3K share the same `CalcSine` convention and the
same `asr.w #4` radius convention for circular child objects. Apply the same
`TrigLookupTable.sinHex/cosHex >> 4` pattern for any circular object in those
games.

**Originating commit.** See `bugfix/ai-s1-syz-slz-advance`
(SLZ2 f1016 -> f1493; Orbinaut spike placed 1px low by float round, touching
player touch-box top edge 4 frames before ROM's contact).

---

## P2 — Landing-frame standing bit: read it AFTER the solid-contact checkpoint, not before

**Symptom.** An object behaviour gated on "the player *just* started standing
on me this frame" fires one frame late: a fall/collapse/launch timer starts a
frame late, the object moves a frame late, and a rider is held ~1 px off ROM
for the divergence window.

**Root cause.** ROM resolves solid contact and the object's reaction to that
contact **within the same frame**. `Plat_Solid` (routine 2) calls
`PlatformObject`, which sets the standing bit (`bset #3,obStatus`) and bumps
the routine, then *falls through* to `Plat_Action` → the subtype handler,
which reads that **just-set** standing bit on the same frame (e.g. `.type03`
writes `move.w #30,objoff_3A` to start the fall timer). A naive engine port
runs its per-object "react to standing" logic (`moveFallOnStand()`) **before**
`checkpointAll()` commits the new standing state, so it reads the *previous*
frame's `playerStanding=false` and misses the same-frame trigger.

**What to check.** Any object routine that reacts to "player is now standing
on me" on the contact frame (collapse timers, fall delays, conveyor engage,
switch trip) must read the standing/push flag **after** the solid-contact
checkpoint inside `update()`, not from the pre-checkpoint snapshot. Add a
post-`checkpointAll()` guard keyed on the freshly-committed standing bit.

**ROM citation.** `docs/s1disasm/_incObj/18 Platforms.asm:201-216`
(`Plat_Action` `.type03` reads the standing bit set by `PlatformObject` in
`Plat_Solid` the same frame).

**Cross-game note.** S2 and S3K `SolidObject`/`PlatformObject` set the standing
bit during the same-frame solid pass that precedes the object's action handler;
the same "read standing after the checkpoint" rule applies.

**Originating commit.** See `bugfix/ai-s1-ghz1-advance` (GHZ1 f3246 -> GREEN;
type-03 platform fall timer started one frame late, holding Sonic 1 px high).

---

## P3 — Triggered countdown timer: ROM does not decrement on the same frame it sets the timer

**Symptom.** A timer-driven state machine transitions (or a child spawns) exactly
1 frame earlier than ROM. The timer value matches ROM when observed right after the
trigger event, but the transition fires too soon.

**Root cause.** ROM timer-setting code branches to `locret_XXXX` (rts) immediately
after writing the timer value — the decrement path is only entered on the *following*
frame when the timer is already non-zero. Naive engine code does:

```java
if (timer == 0) {
    if (contact) { timer = DELAY; }  // set
}
if (timer > 0) { timer--; /* ... */ }  // BUG: decrement fires on same frame
```

That collapses ROM's two-frame path into one, advancing every subsequent timer tick
by 1.

**What to check.** Whenever a ROM object reads a timer with `tst.w`/`beq.s` and
jumps to a common decrement label (`loc_10FC0`, etc.) only when non-zero: the `beq`
means "skip decrement if zero, fall through to rts". The set path (`move.w #N, timer`)
must `return` immediately in the engine — do NOT also run the decrement block in the
same `update()` call.

**ROM citation.** `docs/s1disasm/_incObj/5B SLZ Staircase.asm:104-119`
(`Stair_Type00`): timer check / set / rts, then the decrement at `loc_10FC0` is only
reached when entering non-zero. Same pattern in `Stair_Type02` at asm:122-137.
Equivalent bomb-fuse pattern: `docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm` (SLB bomb fuse
`skipsSameFrameUpdateAfterSpawn`; commit `bugfix/ai-slz1-advance`).

**Cross-game note.** The same tst/beq-over-decrement pattern appears in S2 and S3K
timer-driven objects. Verify every new object's timer-set path falls through to rts
*without* running the decrement in the same dispatch.

**Originating commit.** `bugfix/ai-s1-slz1-staircase`
(SLZ1 f933 → f2872; Staircase timer decremented 1 tick early on trigger frame).

---

## P4 — SolidObject Y contact window uses airHalfHeight (d2), not groundHalfHeight (d3)

**Symptom.** When a grounded player rides a multi-piece solid object and then walks
onto an adjacent piece, the fresh-landing snap seats the player 1px too low (engine
`newCentreY = ROM_y - 1`). The error appears on the piece-transition frame and every
fresh-landing frame where the player is grounded.

**Root cause.** ROM `SolidObject_cont` / `Solid_ChkCollision` always uses d2 (the
input top half-height, i.e. `airHalfHeight`) for the Y bounding-box calculation:

```asm
move.b  obHeight(a1), d3   ; d3 = Sonic's y_radius
ext.w   d3
add.w   d3, d2             ; d2 = airHalfHeight + y_radius  (maxTop)
```

The engine's `processMultiPieceCollision` was using `groundHalfHeight` for grounded
players, inflating `maxTop` by 1. That makes `distY = relY = 3` in ROM become
`distY = 4` in the engine, and the snap formula `newCentreY = playerCentreY - distY + 3`
produces `- 4 + 3 = -1` offset rather than `-3 + 3 = 0`.

The `groundHalfHeight` (d3) is only used by `MvSonicOnPtfm` / `MvSonicOnPtfm2` for
the *continued-ride re-seat*, not for the initial bounding-box Y test.

**What to check.** For any `MultiPieceSolidProvider` object, `processMultiPieceCollision`
uses `params.airHalfHeight()` for the Y window. This is handled generically in the
engine — no per-object override needed. But if you are implementing a new
multi-piece object and observe a 1px grounded seat error on piece transitions, confirm
that `SolidObjectParams.airHalfHeight()` carries the correct top half-height from
the ROM `SolidObject` call site's d2 value.

**ROM citation.** `docs/s1disasm/_incObj/sub SolidObject.asm:170-176`
(S1 `Solid_ChkCollision` Y window: `add.w d3,d2` where d2=airHalfHeight).
`docs/s2disasm/s2.asm:35361-35373` (S2 `SolidObject_cont` Y window: same convention).

**Cross-game note.** All three games (S1, S2, S3K) use d2 = airHalfHeight for the
`SolidObject` Y detection window. The engine fix is in the shared
`ObjectSolidContactController.processMultiPieceCollision` and applies universally.

**Originating commit.** `bugfix/ai-s1-slz1-staircase`
(SLZ1 f933 → f2872; staircase piece fresh-landing 1px low when grounded).

---

---

## P5 — SynchroAnimate global counter: objects read v_ani0_frame from BEFORE the current frame's SynchroAnimate call

**Symptom.** An object whose animation or harmfulness is gated on `v_ani0_frame` (the global sync counter) fires one tick early at multiples of 12 gfc — e.g. a spike marks itself harmful when ROM would have it harmless, causing a spurious HURT one trace-frame early.

**Root cause.** Two layered bugs under one class:

1. *Per-object unseeded counter:* Any per-object `animCounter` initialized to 0 at construction diverges from the shared `v_ani0_frame` whenever the object streams in mid-level (the real `v_ani0_frame` has been ticking since level start). Always derive `v_ani0_frame` from `levelManager.getFrameCounter() + 1` (= current gfc), not from a per-object accumulator. See also SBZ Electrocuter pattern (`v_framecount` source).

2. *Off-by-one: ExecuteObjects runs before SynchroAnimate:* ROM `Level_MainLoop` (sonic.asm:2980) order is:
   ```
   addq.w #1,(v_framecount).w   ; gfc++ at top of loop (line 2984)
   jsr ExecuteObjects            ; objects READ v_ani0_frame (line 2988)
   jsr SynchroAnimate            ; UPDATES v_ani0_frame (line 3010)
   ```
   At loop gfc=N, objects read `v_ani0_frame` set by iteration N-1's `SynchroAnimate`. `SynchroAnimate` ticks on calls 1, 13, 25, … (underflow branch `bpl`: initial `v_ani0_time=0` underflows to 0xFF on call 1). After N-1 calls, tick count = `ceil((N-1)/12) = (N+10)/12` (integer division). The correct formula is:
   ```java
   animCounter = (-((gfc + ANIM_FRAME_DURATION - 2) / ANIM_FRAME_DURATION)) & 0x07;
   //           = (-((gfc + 10) / 12)) & 7   for ANIM_FRAME_DURATION=12
   ```
   Using `(gfc + 11) / 12` (the naive "after N calls") overshoots by 1 tick at every multiple of 12.

**What to check.** Any object that reads `(v_ani0_frame).w` in `Hel_RotateSpikes` or similar:
- Derive from `levelManager.getFrameCounter() + 1` (never from a per-object counter or a constructor-seeded value).
- Use `(gfc + 10) / 12` not `(gfc + 11) / 12` for the tick count.

**ROM citation.** `docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:95-105` (`Hel_RotateSpikes`: `move.b (v_ani0_frame).w,d0`). `SynchroAnimate`: `docs/s1disasm/sonic.asm:3111-3119` (bpl branch on underflow; reload to 11). `Level_MainLoop` order: `docs/s1disasm/sonic.asm:2984-3010`.

**Originating commits.** `bugfix/ai-s1-ghz3-f4650` (f4650 -> f5043, unseeded counter); `bugfix/ai-s1-ghz3-f5043` (f5043 -> f6464, off-by-one formula).

## P6 — Object-set control lock (locktime/move_lock) decrements only on grounded frames

**Symptom.** An object that disables the player's D-pad for N frames (a horizontal spring, a launcher, anything writing `locktime`/`move_lock`) lets input back in too early when the lock window spans airborne frames — the player starts accelerating/decelerating before ROM does. Typical trace signature: a 1px `x` and ~0x80 `x_speed`/`x_sub` divergence on the frame just after the player lands from the launch, with input freshly pressed that the ROM still ignores.

**Root cause.** ROM's horizontal control lock is the single RAM word `locktime` (S1, objoff_3E) = `move_lock` (S2/S3K). It is decremented **only on grounded frames**, inside `Sonic_SlopeRepel` (S1) / the slope-repel routine (S2/S3K), which the grounded player modes (`MdNormal`/`MdRoll`) call but the airborne modes (`MdJump`/`MdJump2`) do **not**. So while the player is airborne the lock is FROZEN — its countdown pauses for the entire jump/launch arc and resumes only on landing.

The engine models `locktime`/`move_lock` as `moveLockTimer`, decremented only in the grounded `doSlopeRepel()` path — correct. The trap: do NOT invent a per-object lock field decremented every frame (e.g. S1 spring's old `springingFrames`, ticked unconditionally in `tickStatus()`). A per-frame counter expires several frames early whenever the lock spans airborne frames.

**What to check.** Any object porting a `move.w #N,locktime(a1)` / `move.w #N,move_lock(a1)`:
- Set the lock through `player.setMoveLockTimer(N)`, NOT a bespoke counter. `moveLockTimer` already has the grounded-only decrement and feeds the same `!air && (moveLocked || ...)` ground control-lock gate.
- Confirm in the ROM which spring/launcher variant actually sets the lock. In S1 only `Spring_LR` (horizontal) sets `locktime`; vertical/down springs do not.

**ROM citation.** `docs/s1disasm/_incObj/41 Springs.asm:145` (`Spring_BounceLR`: `move.w #15,locktime(a1)`). Grounded-only decrement: `docs/s1disasm/_incObj/01 Sonic.asm:1383,1410` (`Sonic_SlopeRepel` `tst.w locktime` / `subq.w #1,locktime`), called from `Sonic_MdNormal`/`Sonic_MdRoll` (`asm:323,352`) but not the airborne modes (`asm:328-341,357-370`). S2 equivalent: `docs/s2disasm/s2.asm:34031` (horizontal spring `move.w #$F,move_lock(a1)`).

**Originating commit.** `bugfix/ai-s1-slz2-f1493` (SLZ2 f1714 -> f2552, 215 -> 137; S1 LR spring routed control lock through moveLockTimer).

---

## P7 — Dormant object has obColType=0 until it activates: ReactToItem skips it entirely

**Symptom.** A badnik/hazard that waits in an inert state before activating (curled, hidden, pre-trigger) wrongly hurts or interacts with the player while still dormant. Trace signature: the player loses rings / enters the hurt routine (`rtn` 2 -> 4) on a frame where ROM has him pass the object untouched; the object is on-screen and near the player but ROM never reacts to it.

**Root cause.** S1 `ReactToItem` gates every object on its `obColType` byte: `move.b obColType(a1),d0 / bne React_CheckHitboxOverlap` — if `obColType == 0` (col_none), the object is skipped before any hitbox test. Many objects do NOT set `obColType` in their init routine (routine 0 / `*_Main`); they write it only when they transition into an active/damaging/destroyable state. So a freshly spawned, not-yet-triggered object is non-collidable even though it has a position, a size, and is rendered. Engine object classes that hardcode `getCollisionFlags()` to a non-zero size index from construction make the dormant object collidable from frame 0.

**What to check.** For any object that has a pre-activation waiting state:
- Trace where the ROM first writes `obColType` (`move.b #col_*,obColType(a0)`). Everything before that write is col_none.
- Gate `getCollisionFlags()` to return `0` until the engine reaches the equivalent activated state. If the activation state is monotonic (the object never returns to the dormant routine once it has activated), derive the gate from the existing routine/secondary-routine field — no new rewind-captured field needed.
- Distinguish this from the on-screen render gate (`isOnScreenForTouch()` / `requiresRenderFlagForTouch()`): that handles "off-screen or DisplaySprite-not-yet-run". This pitfall is the orthogonal "on-screen but obColType still 0" case.

**ROM citation.** `docs/s1disasm/_incObj/Sonic ReactToItem.asm:52-53` (`tst`/`bne` obColType gate). Concrete object: `docs/s1disasm/_incObj/43 Badnik - Roller.asm:19-38` (`Roll_Main` sets height/width but never obColType) + `:86-100` (`Roll_Action_FromLeft` ob2ndRout=0 leaves it untouched until activation at `:96` sets `$8E`; stop-and-unfold `:177` sets `$0E`).

**Cross-game note.** S2 `Touch_Loop` has no render-flag gate but still checks `collision_flags(a1)` (zero = skipped); S3K only adds opted-in objects to `Collision_response_list`. So the "dormant -> not in collision until activated" invariant holds in all three — verify each new waiting-state object reports zero collision until it writes its colType / adds itself to the list.

**Originating commit.** `bugfix/ai-s1-syz1-f2338` (SYZ1 f2338 -> f4430; curled SYZ Roller (Obj 0x43) reported col=0x0E from spawn and hurt the falling player where ROM's dormant Roller has obColType=0).

---

## P8 — Multi-piece objects must spawn real OST-slot children (FindFreeObj/FindNextFreeObj), NOT internal arrays or bare slot-reservations

**Symptom.** A whole zone's object slots drift out of ROM alignment, surfacing as a downstream object mis-slotted dozens-to-hundreds of frames later (e.g. trace `obj_sNN_slot exp 0xNN act 0xMM`, or a far-off object missing/extra). The multi-piece object itself looks correct on screen.

**Root cause.** Modeling a chain / collapsing-floor row / multi-segment stomper / multi-link platform as ONE engine object that carries internal `linkX[]`/`linkY[]` arrays (render-only) — or that "reserves" slots without actually occupying them as real `ObjectInstance`s — under-allocates the OST. ROM spawns each piece as a genuine object via `FindFreeObj` (lowest free slot) or `FindNextFreeObj` (next free after parent), consuming real SST slots. Every subsequent `FindFreeObj`/ObjPosLoad call in the zone then sees a different free-slot pattern → cascading slot drift. **Count matters:** ROM `dbf d1` with `d1 = N` runs **N+1** times (the loop body + the fall-through), so a "spawn d1 children" loop creates `d1+1` pieces — off-by-one here under-allocates by one slot.

**What to check.** For any chain/floor/multi-segment object: trace the ROM spawn loop. Each `FindFreeObj`/`FindNextFreeObj` + `_move.b #id,obID(a1)` is a REAL child object the engine must spawn as a real `ObjectInstance` on its own slot (`spawnChild` / `allocateChild*`), not an internal array entry. Count the `dbf` iterations as `d1+1`. Render-only children that have no collision still occupy a slot in ROM — spawn them.

**ROM citation.** `docs/s1disasm/_incObj/15 Swinging Platforms.asm:67-105` (`.makechain`: `FindNextFreeObj`/`FindFreeObj` per link, `dbf d1,.makechain` = chain-length+1 children). `docs/s1disasm/_incObj/sub FindFreeObj.asm` (FindFreeObj lowest-free; FindNextFreeObj next-after-parent). MZ3 Chained Stomper: `docs/s1disasm/_incObj/31 MZ Chained Stompers.asm:59-97` (size-2 spike-fold = 2 children).

**Rewind note.** Parent-recreated render-only children resolve via `TestRewindCoverageGuard` baseline entries (the recreate path is parent-driven; baseline-entry the child's `#recreate` + `#finalScalar` keys). Precedents: `SpikedBallChain$ChainChild`, `CollapsingLedge$Fragment`.

**Cross-game note.** S2/S3K spawn multi-piece children through `AllocateObject` (lowest-free) / `AllocateObjectAfterCurrent` (after parent) — `docs/s2disasm/s2.asm` and `docs/skdisasm/sonic3k.asm:37911,37917`. Same "real OST slot per piece, count = dbf+1" rule.

**Originating commit.** `9f47f557f` (S1 SBZ2 Obj 0x15 swinging-platform chain links — render-only children spawned as real OST slots). Banked: MZ3 ChainStomp `StomperPieceChild`; S1 GHZ Obj 0x17 spiked-pole helix `HelixSpikeChild` (`Hel_Main .loopBuildHelix`, `docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:46-90` — 16-spike helixes held 1 slot instead of 16, leaving GHZ3's SST 30 slots emptier than ROM, so a hit's `RLoss_Count` found 31 free slots instead of 19 and scattered 12 extra rings; the player re-collected 3 of them and reached `GotThroughAct` with 59 rings against ROM's 56, stretching `Got_Bonus` by 3 frames and softlocking the GHZ3 -> MZ1 boundary). **The tell is a ring count, not a slot field:** the run comparators leave `EngineDiagnostics.rings` at -1, so ring divergence is silent — dump the ROM `slot_dump` against `ObjectManager.occupiedDynamicSlotIds()` and diff the object-id MULTISET; a single id whose count is wildly off (32 vs 2) names the under-allocating multi-piece object immediately.

---

## P9 — Object delete/cancel checks must run only in the ROM routines that call them, not every frame

**Symptom.** A child/projectile object is deleted hundreds of frames too early (or too late), freeing its slot at the wrong time → slot drift / a downstream object mis-slotted. The object's own motion looks right until it vanishes.

**Root cause.** Porting a "cancel if parent destroyed" (or any delete-gate) check into the engine object's per-frame `update()` unconditionally, when ROM only calls that check from SPECIFIC routines. ROM dispatches by `obRoutine`: the cancel subroutine is `bsr`'d only from the routines that should run it, not from the active/in-flight routine. Running it every frame deletes the object during a phase ROM keeps it alive.

**What to check.** When porting any `*_ChkCancel` / `*_ChkDel` / delete-gate subroutine: find every `bsr`/`jsr` to it and note WHICH `obRoutine` values reach it. Gate the engine's call to the equivalent engine routine/state — do not call it from the shared per-frame entry.

**ROM citation.** `docs/s1disasm/_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:162-194,220-249` — `Msl_ChkCancel` (cancel missile if parent Buzz Bomber destroyed) is `bsr`'d only from `Msl_Main` (routine 0) and `Msl_Animate` (routine 2, the flare phases), NOT from the active `Msl_FromBuzz` (routine 4). The engine ran it every frame → missile deleted ~840 frames early.

**Cross-game note.** S2/S3K objects use the same `obRoutine`/`Obj_routine` jump-table dispatch; a delete/cancel check belongs in the routines that ROM `bsr`s it from. Verify the calling routines per object before porting.

**Originating commit.** `53da8c24a` (S1 Buzz Bomber Missile cancels-on-parent-destroyed only in flare phase, not active).

---

## P10 — Off-screen delete uses ROM's obRender render-box `[camX-obActWid, camX+320+obActWid)`, not a raw `isOnScreenX(160)` margin

**Symptom.** A short-lived object (debris, shrapnel, projectile) lingers a few frames longer (or unloads earlier) than ROM, so the count of that object's instances differs at a given frame → slot drift.

**Root cause.** Using a fixed engine `isOnScreenX(160)` (or any raw pixel margin) for the off-screen delete instead of the ROM render-box bound. ROM deletes when the `obRender` on-screen bit is clear, which `BuildSprites` computes from the camera-X-coarse window widened by the object's `obActWid` (and chunk-rounded). A 160px margin keeps the object alive over a wider/narrower window than ROM (e.g. Walking Bomb shrapnel lingered 24 frames vs ROM's 16).

**What to check.** For any object whose ROM delete is `tst.b obRender(a0) / bpl DeleteObject` (or the `out_of_range` macro): model the delete with the engine's ROM-render-box helper keyed on the object's `obActWid`, not `isOnScreenX(<arbitrary>)`. Scope the fix to the specific object — the broad badnik `isPersistent` keep-alive is a separate, inert concern.

**ROM citation.** `docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:218-219` (shrapnel `tst.b obRender(a0) / bpl.w DeleteObject`). Render-box computation: `docs/s1disasm/_inc/BuildSprites.asm:47-58` (camera-X-coarse + width window sets the `obRender` on-screen bit).

**Cross-game note.** S2 `MarkObjGone` uses the chunk-rounded camera-X-coarse bound `$80 + screen_width + $80` (`docs/s2disasm/s2.asm`, `MarkObjGone`); S3K's render/display path is equivalent. Use the ROM render bound, not a raw margin, in all three.

**Originating commit.** `0a15683b9` (S1 Walking Bomb shrapnel deletes via ROM obRender render bound, not raw isOnScreenX).

---

## P11 — Broken/consumed objects must report `col_none` so ReactToItem skips them (orthogonal to P7's dormant case)

**Symptom.** A broken monitor (or any consumed/spent object) still reports a non-zero collision type and PREEMPTS an adjacent live object's interaction — e.g. an already-broken monitor's stale col_item shadows the break of a neighbouring monitor the player actually touches.

**Root cause.** S1 `ReactToItem` gates each object on `obColType` (`tst / bne` — `col_none` = skipped, `Sonic ReactToItem.asm:52-53`). A broken/consumed object that fails to write `col_none` keeps its old collision flags, so `ReactToItem` still considers it (and, because ReactToItem returns after the first overlap, it can consume the contact that should have hit the adjacent object). This is the *post-activation/consumed* mirror of **P7** (which covers the *pre-activation/dormant* case).

**What to check.** For any object with a "broken"/"consumed"/"spent" terminal state: confirm the ROM writes `move.b #col_none,obColType(a0)` on entering that state, and gate the engine's `getCollisionFlags()` to return `0` there. Cross-reference P7 for the dormant-before-activation case.

**ROM citation.** `docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:183` (`move.b #col_none,obColType(a0)` — broken monitor stops further collision). Gate: `docs/s1disasm/_incObj/Sonic ReactToItem.asm:52-53`.

**Cross-game note.** S2 `Touch_Loop` reads `collision_flags(a1)` and `bne` to the check (zero = skipped, `docs/s2disasm/s2.asm:85048-85049`); S3K only touch-checks objects added to `Collision_response_list`. A consumed object must clear its flags / not re-add itself in all three.

**Originating commit.** `466f408a8` (S1 broken monitor clears col type so it stops preempting ReactToItem).

---

## P12 — Collapse/release drops must clear the player's on-object status bit, not just the engine's riding link

**Symptom.** A collapsing ledge/floor (or any "drop the rider" object) releases the player, but the player stays grounded / pinned at the object's last surface Y for a frame instead of falling — a 1px seat error and a 1-frame-late airborne transition.

**Root cause.** Calling only the engine's `clearRidingObject` (which drops the internal riding bookkeeping) leaves the player's `onObject`/grounded flags set, so the player is still treated as standing. ROM's release path does `bclr #3,obStatus(a1)` (clear the player's on-platform bit) — the player becomes airborne. The engine must mirror that: `setOnObject(false)` + `setAir(true)` on release, not just unlink the ride.

**What to check.** For any object that drops/releases a standing player (collapse, retract, vanish, switch-off): after `clearRidingObject`, also clear the player's on-object status and set air — matching the ROM `bclr #3,obStatus(a1)`.

**ROM citation.** `docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm:104-105` (CollapseLedge `.delayCollapse` `bclr #3,obStatus(a1)`) and `:233-240` (CollapseFloor `.delayCollapse` `bclr #3,obStatus(a1)`).

**Cross-game note.** S2/S3K release routines likewise `bclr` the player's standing/on-object status bit; clearing only the engine ride link under-models them.

**Originating commits.** `7a9a8a4b2` (S1 CollapseFloor airborne-drop on collapse), `446d87185` (S1 GHZ CollapsingLedge clears rider on-object/push status on collapse-release).

---

## P13 — Routine transitions in the engine's pre-update SOLID pass run 1 frame AHEAD of ROM's in-execution PlatformObject routine advance — defer the first frame

**Symptom.** A timer-driven object (collapse delay, fall delay) started by a solid-contact routine transition fires 1 frame early vs ROM.

**Root cause.** ROM advances the object's `obRoutine` *inside* `PlatformObject`, which runs during the object's own `ExecuteObjects` slot — so the new routine's body first executes on the *next* frame. The engine resolves solid contact in a PRE-update pass before the object's `update()`, so the routine transition it sets is observed by the same-frame `update()` body → the timer/state advances one frame early. (This is the routine-advance sibling of **P2**, which is about reading the standing BIT after the checkpoint; P13 is about the ROUTINE transition the solid pass triggers.)

**What to check.** When a solid-contact/PlatformObject routine advance starts a per-object timer or state machine, guard the first frame: a `*EnteredThisFrame` flag that skips the new routine's body on the transition frame, matching ROM's "advance now, run next frame" cadence.

**ROM citation.** `docs/s1disasm/_incObj/1A, 53 Collapsing Ledges and Floors.asm` (CollapseFloor routine-4 entry via the solid pass; the collapse timer must not tick on the entry frame). See also P2 (`18 Platforms.asm:201-216`) for the standing-bit-read sibling.

**Cross-game note.** S2/S3K `SolidObject`/`PlatformObject` also advance the object routine during the object's own slot execution; an engine pre-update solid pass that sets the routine will see it one frame early — apply the same first-frame defer.

**Originating commit.** `7a9a8a4b2` (S1 Collapsing Floor defers routine-4 entry one frame via a `collapseEnteredThisFrame` guard).

---

## P14 — SolidObject edge tests are inclusive (`bhi`): `relX == width*2` IS a contact

**Symptom.** An object's side/top contact misses on the exact-edge frame — e.g. a rolling player grazing the precise right edge of a boss body produces no bounce, firing the bounce one frame late (when the player has penetrated 1px).

**Root cause.** ROM `SolidObject`'s X-range gate is `cmp.w d3,d0 / bhi.w Solid_NoCollision` where `d3 = width*2` and `d0 = playerX - objX + halfWidth`. `bhi` is exclusive-greater, so `d0 == d3` (player centre exactly at `objX + halfWidth`, i.e. `relX == width*2`) is NOT rejected — it IS a valid contact. An engine overlap gate using `relX >= width*2 → no contact` (exclusive) drops that exact-edge frame.

**What to check.** For any object using the plain `SolidObject` family, the right-edge X gate must treat `relX == width*2` as in-contact. In the engine, opt into `usesInclusiveRightEdge()` (the flag S3K horizontal springs and S1 spikes already use for their `SolidObject`-family `bhi` gates). Verify against the object's actual ROM solid routine.

**ROM citation.** `docs/s1disasm/_incObj/sub SolidObject.asm:167-168` (`cmp.w d3,d0 / bhi.w Solid_NoCollision`).

**Cross-game note.** S2 `SolidObject_cont` (`docs/s2disasm/s2.asm:35353-35354`) and S3K `SolidObject` (`docs/skdisasm/sonic3k.asm:41364-41365`) use the same `cmp.w d3,d0 / bhi` inclusive right edge. Apply `usesInclusiveRightEdge()` for any `SolidObject`-family object in those games too.

**Originating commit.** `caf70abb7` (S1 FZ boss uses ROM-inclusive SolidObject right edge for roll-bounce; f837 -> f1724).

---

## P15 — Object pushes the player: `add.w speed,obX(a1)` / `move.w pos,obX(a1)` preserve the rider's sub-pixel — never `setCentreX`/`setCentreY` (they ZERO it)

**Symptom.** A frontier reads as a "sub-pixel RAM-gated" 1px/0.x-px X (or Y) residual — the engine drifts a constant fraction behind ROM, crossing an integer boundary 1 frame off. The player is being pushed/carried/captured by an object (conveyor, fan/wind, moving solid, teleporter capture, MvSonicOnPtfm carry, ...) and the engine is byte-EXACT until the first frame that object touches him, then a CONSTANT delta thereafter.

**Root cause.** Almost every ROM object→player position write operates on the PIXEL word only: `add.w <speed>,obX(a1)` / `sub.w d0,obX(a1)` / `move.w <pos>,obX(a1)` write obX/obY at offset `$8`/`$C` and leave the sub-pixel words (`x_sub`/`y_sub` at `$A`/`$E`) UNTOUCHED — the rider keeps his accumulated fraction. The engine's `setCentreX(short)` / `setCentreY(short)` ZERO the sub-pixel (`this.xSubpixel = 0`). Calling them in an object-push path discards up to ~1px of fraction every frame the object acts on the player, putting him progressively behind ROM.

**What to check / fix.** For any object that pushes or carries the player each frame, or captures/repositions him to a pixel position the ROM writes via `add.w`/`sub.w`/`move.w` to the pixel word:
- **Incremental push** (`add.w speed,obX(a1)`): use `player.shiftX(delta)` / `player.shiftY(delta)` — they add an integer pixel delta and preserve the sub-pixel.
- **Set-to-position** (`move.w pos,obX(a1)`): use `player.setCentreXPreserveSubpixel(x)` / `setCentreYPreserveSubpixel(y)` (or `setX`/`setY`, which write the pixel word only).
- **Only keep `setCentreX`/`setCentreY` (sub-pixel zeroing) where ROM EXPLICITLY clears the fraction**, e.g. `move.w #0,obSubpixelX(a0)`. The S1 level-side-boundary clamp does exactly this (`Boundary_Sides`: `move.w d0,obX(a0)` THEN `move.w #0,obSubpixelX(a0)`, `01 Sonic.asm:1097-1100`) — there `setCentreX` is CORRECT. Always read the object's actual ROM routine before changing the setter; FAITHFUL-OR-BOUNCE per call site.

Audit method: gate a probe in `AbstractSprite.setCentreX/Y` on a system property that logs the first non-`AbstractSprite` caller whenever a NON-zero sub-pixel is zeroed (dedup per call site), then run the trace sweep; each hit is a candidate to verify against the disasm.

**ROM citation.** Sub-pixel-PRESERVING object pushes (engine should use `shiftX`/`setCentreXPreserveSubpixel`): S1 Conveyor `add.w conv_speed(a0),obX(a1)`; SLZ Fan `add.w d0,obX(a1)` (`5D SLZ Fan.asm:82`); SBZ Teleporter capture `move.w obX(a0),obX(a1)` (`72 SBZ Teleporter.asm:73-74`); `SolidObject` side-push `sub.w d0,obX(a1)` (`sub SolidObject.asm:239`); `MvSonicOnPtfm` Y-seat `move.w d0,obY(a1)` + X-carry `sub.w d2,obX(a1)` (`sub MvSonicOnPtfm.asm:36,39`); Spring side-push `addq.w #8,obX(a1)` (`41 Springs.asm:137`); MZ PushBlock align `add.w d0,obX(a1)` (`33 MZ, LZ Pushable Blocks.asm:404`). Sub-pixel-ZEROING (engine `setCentreX` is correct): level-side boundary `move.w #0,obSubpixelX(a0)` (`01 Sonic.asm:1100`).

**Also covers object SELF-motion, not just player-push.** The same rule applies when an object moves ITSELF: ROM `add.w speed,obX(a0)` adds to the object's own pixel word and preserves its own `x_sub`, so a rideable/collidable object that self-moves via `setCentreX`/`setCentreY` (zeroing) would drift ~1px and surface that 1px wherever the player rides/hits it. Use `SubpixelMotion.moveSprite` / `shiftX`/`shiftY` for self-motion too. (An object that legitimately moves in INTEGER pixel steps — e.g. SLZ staircase/seesaw use integer `int x`/`int y` self-position with no sub-pixel accumulator, matching ROM's integer/oscillation-table motion — has no fraction to discard and needs no change; a 16.16-accumulator object like the GHZ boss `xFixed`/`yFixed` already preserves it.) A `-Dobjsubpxaudit` probe in `setCentreX/Y` (log non-`AbstractSprite` object-code callers that zero a non-zero subpixel on any sprite) finds these. **S1 audit result (commit below): a full 19-zone sweep found ZERO object-self-motion subpixel-zeroing instances** — the gated 1px-object reds (SLZ1 f2872 staircase, GHZ3 f8021 boss, SLZ2/SLZ3) are genuinely counter-phase / RAM-gated, NOT self-motion-subpixel.

**Cross-game note.** S2/S3K conveyors, fans, moving solids, and `MvSonicOnPtfm`/`SolidObject` side-pushes use the same `add.w`/`sub.w`/`move.w obX(a1)` pixel-word convention and likewise PRESERVE the rider's sub-pixel — any S2/S3K object-push OR self-move path using a sub-pixel-zeroing centre setter is the same bug. (S3K `x_pos` is 16.16 with the same `$8`/`$A` obX/x_sub layout.)

**Originating commit.** `b5bc778d4` (S1 Conveyor Belt preserves rider sub-pixel X on push via `shiftX`; SBZ2 f2224 -> f2323). Companion: SBZ Teleporter capture uses `setCentreXPreserveSubpixel`/`setCentreYPreserveSubpixel`. Object-self-motion audit (no instances found in S1): this commit (catalogue update; no engine fix needed). **Extension (SBZ2 f2920 -> f6839, branch `bugfix/ai-sbz2-f2920`):** capture is only ONE of a controlling object's player-position writes — the SBZ Teleporter ALSO writes the player in its rise (`Tele_Bump` `move.w d2,obY(a1)`), its waypoint snap (`Tele_Bend` `move.w objoff_36/38,obX/obY(a1)`), its release (`andi.w #$7FF,obY(a1)`), and its per-frame travel step (`loc_167DA`, a 16.16 `move.l obX(a1) += obVelX<<8`). All four were still using sub-pixel-zeroing `setCentreX/Y` (the travel step via a private 16.8 accumulator seeded from `playerX<<8`, which double-discarded the fraction). Audit EVERY phase of an obj_control object, not just the entry/capture frame; for the 16.16 velocity-integration step, use the sprite's `AbstractSprite.move(xSpeed,ySpeed)` (the exact ROM `loc_167DA` add) rather than a hand-rolled accumulator.

## P16 — Trigger objects that throw the player off must clear Status_OnObj, SET Status_InAir, AND stop being solid — not just drop the riding link (extends P12)

A capsule/switch/button the player rides and then triggers (Egg Prison switch, end-of-level capsule) does three things in ROM on the trigger frame; the engine must mirror all three:
1. `bclr #3,(v_player+obStatus)` — clear `Status_OnObj` (engine `setOnObject(false)`).
2. `bset #1,(v_player+obStatus)` — SET `Status_InAir`, i.e. throw the player AIRBORNE (engine `setAir(true)`). This is the part P12 (collapse-release) didn't need but trigger-switches do — the player is launched/falls, not re-seated on terrain.
3. Advance `obRoutine` past the only routine that calls `SolidObject` (e.g. `Pri_Switch` routine 4 → `Pri_Explosion` `$A`) so the object **stops being solid** (engine `isSolidFor()` returns `false` once triggered). Otherwise the engine re-seats the just-released player onto the still-solid depressed switch every frame (`air=0`, `y_speed=0`) while ROM is airborne.

Doing only (1) means the player is re-grabbed next frame by the still-solid object. ROM ref `3E Prison Capsule.asm:94,101-102`. Originating commit `5511805d0` (Egg Prison switch → GHZ3 GREEN). Same `bclr`-release family as P12.

**Do NOT lock the camera (minX/maxX) at the switch trigger.** ROM `Pri_Switch` on the switch-stood frame does `clr.b (f_timecount)` + `clr.b (f_lockscreen)` + `move.b #1,(f_lockctrl)` + force-right input (`3E Prison Capsule.asm:97-99`) — it NEVER writes `v_limitleft2`/`v_limitright2`. The camera must keep scrolling to `v_limitright2`, which each act-3 boss's escape routine already expanded to `boss_*_end` via `addq.w #2,(v_limitright2)`. Pinning `camera.setMaxX(camera.getX())`/`setMinX(camera.getX())` at the trigger freezes the camera at wherever it happened to be when the player landed on the switch — fine for horizontal-flee bosses (camera already at the boundary), but in LZ3 the player lands on the switch ~10px before the camera reaches `v_limitright2`, freezing `camera_x` short and diverging. The "camera stays put because `v_limitleft2 = v_limitright2`" reasoning is the **Signpost** end-of-act path (`0D Signpost.asm:54,163`); act-3 boss zones never run it (`SignpostArtLoad` skips act 3, `sonic.asm:3164-3165`). Originating commit (see TRACE_FRONTIER_LOG, LZ3 f18196 → GREEN: removed the Egg Prison camera lock).

## P17 — Horizontally-moving solid objects do NOT drag a standing rider (ROM passes POST-move obX to MvSonicOnPtfm → zero carry delta)

A solid object whose caller runs its `*_Move` (updating `obX`) BEFORE `move.w obX(a0),d4` passes the *post-move* `obX` as the carry reference, so `MvSonicOnPtfm`'s `sub.w obX(a0),d2` computes a **zero** horizontal delta — the standing rider is NOT carried sideways (he stays put and walks/falls off the edge as the object slides out from under him). Universal to such objects (every Spikes caller passes post-move obX: `36 Spikes.asm:52,96`; `sub MvSonicOnPtfm.asm:38-39`), NOT a zone carve-out. The engine's generic continued-ride carry defaults to dragging the rider by the object X-delta — override `carriesRiderOnHorizontalMove()` to `false` for horizontally-moving solids. Symptom: a stationary player (no input, zero velocity) dragged +N px/frame on a horizontally-moving object, then misses the ROM airborne/fall transition. Originating commit `37e8a19f2` (moving Spikes Obj0x36 → MZ1 f4230 -> f6222). Contrast: VERTICALLY-moving platforms DO carry the rider's Y (normal `MvSonicOnPtfm` Y re-seat) — this is specifically the HORIZONTAL drag.

## P18 — PlatformObject-family ridable objects need the COMPLETE landing/riding flag set matching their exact ROM landing routine

A top-solid object the player lands on / rides has a small family of `SolidObjectProvider` flags that each model one ROM landing-routine detail. A MISSING flag is a 1px / 1-frame ride-or-landing divergence that surfaces as a trace red the moment the player touches that object. When implementing or revisiting ANY ridable S1 object, read its exact ROM solid routine and set ALL of these to match — not just the obvious ones:

- **`usesCollisionHalfWidthForTopLanding()`** — `true` iff the ROM routine passes `d1 = obActWid` DIRECTLY into the solid helper (PlatformObject / Swing_Solid / MBlock PlatformObject / **SlopeObject / SlopeObject_AssumeStoodOn**). `false` iff it passes `d1 = obActWid + sonic_solid_width` (`$B`) (full `SolidObject`, e.g. Obj0x56 FloatingBlock, Obj0x33 PushBlock). A wrong `false` narrows the standable top width by `$B` → ride/landing not detected near the edge → player falls through onto terrain (MZ3 f8646 MovingBlock signature, MZ3 f6430 swing platform; SLZ3 f6364 Seesaw — the SlopeObject helper does its X-range check on `d1 = #96/2` with no narrowing then `bra Plat_NoXCheck_AltY`, so the slope objects Seesaw Obj0x5E / CollapsingLedge Obj0x53 / CollapsingFloor Obj0x1A all need `true`).
- **`rejectsZeroDistanceTopSolidLanding()`** — `true` iff the ROM landing band routes through `Plat_NoXCheck_AltY` (`PlatformObject` and `Swing_Solid` both do): `cmpi.w #-16,d0 / blo` is UNSIGNED so it rejects the exact-touch `d0 = 0` (standable band is `[-16,-1]`, strict penetration). `false` iff it routes through `SolidObject_Landed`'s `cmpi.w #$10,d3 / blo` which ACCEPTS `d3 = 0`. Wrong `false` seats a fast-faller 1 frame early. NOTE: this OR-reject takes precedence over `allowsZeroDistanceTopSolidLanding()` in the resolver, so an object that set `usesPlatformObjectLandingSnap()=false` (which makes `allowsZeroDistanceTopSolidLanding` permissive for its continued-ride model) still needs this explicit reject if its NEW-landing routine is a PlatformObject/`Plat_NoXCheck_AltY` band — Obj15 SwingingPlatform was exactly this gap.
- **`getTopLandingSnapAdjustment()`** — `-1` iff the object models the `MvSonicOnPtfm2` ride surface (`obY-9`) in `getSolidParams` half-height but PlatformObject detects/snaps the FIRST landing from `obY-8`. `0` iff the object already uses half-height `8` for detect and `9` only for the continued-ride `groundHalfHeight` (Obj52 MovingBlock, Obj59 Elevator — `SolidObjectParams(width, 8, 9)`).
- **`carriesAirborneRiderAfterExitPlatform()`** — `true` iff the routine is `ExitPlatform` → `*_Move` → UNCONDITIONAL `MvSonicOnPtfm`/`MvSonicOnPtfm2` (the helper does not test the on-object bit, so the just-jumped/just-walked-off rider gets one final post-move Y carry). Obj18/52/59/5A/63/15 all do this; the slope/full-solid objects (Seesaw, Bridge, FloatingBlock, PushBlock, CollapsingLedge/Floor) do not.
- **`usesPreUpdatePositionForSolidContact()`** — `true` iff routine-2 calls the solid helper BEFORE the object's `*_Move` (detect-before-move: Obj18 `Plat_Solid`, Obj5A `Circ_Platform`, Obj15 `Swing_SetSolid` fall-through, Obj63 conveyor). `false` iff it moves first then calls the helper (Obj52 `MBlock_Platform`: `bsr MBlock_Move` THEN `jsr PlatformObject`).

**Disasm method.** For each ridable object find its solid routine and answer four questions: (1) `PlatformObject`/`Swing_Solid` vs full `SolidObject` (→ first two flags); (2) does it `subq.w #9` vs `subq.w #8` and what half-height does `getSolidParams` use (→ snap); (3) `ExitPlatform`-then-unconditional-`MvSonicOnPtfm` (→ carry); (4) helper-before-move vs move-before-helper (→ preUpdate). `Plat_NoXCheck_AltY` and the PlatformObject body live in `docs/s1disasm/_incObj/sub PlatformObject & SlopeObject.asm`; `MvSonicOnPtfm` in `sub MvSonicOnPtfm.asm`.

**Examples (3 wins + 1 correctness fix this family):** SLZ Circling Platform Obj0x5A missing `carriesAirborneRiderAfterExitPlatform` → SLZ2 advanced; SLZ Elevator Obj0x59 missing `rejectsZeroDistanceTopSolidLanding` → SLZ2 GREEN (`2e1670928`); SLZ Circling Platform full flag set (`f9fac6f25` family); SwingingPlatform Obj0x15 missing `rejectsZeroDistanceTopSolidLanding` (Swing_Solid → Plat_NoXCheck_AltY rejects d0=0) — ROM-faithful, regression-neutral (`caf70abb7` family); Seesaw Obj0x5E missing `usesCollisionHalfWidthForTopLanding` (SlopeObject `d1 = #96/2` = obActWid, no `$B` narrowing) so a rolling-jump re-landing on the seesaw's raised end at relX=90 (inside ±0x30, outside the narrowed ±0x25) fell through — `bugfix/ai-slz3-f6364` (SLZ3 f6364 -> f6507, 256 -> 249 errors). A full S1 13-object audit (`SolidObjectProvider` flags vs ROM routine) found only the Obj15 gap; the FloatingBlock Obj0x56 is a full `SolidObject` and all its flags were already ROM-correct (SYZ3 f6358 is a wall-pushback 1px-integer / RAM-gated residual, NOT a flag gap).

## P19 — Hand-rolled object animation timers must use ROM AnimateSprite's countdown cadence: every step (including the FIRST) lasts speed+1 frames

An object that does NOT use the shared animation helper and instead rolls its own frame timer must reproduce ROM `AnimateSprite` exactly, or its whole animation runs one frame early. ROM (`docs/s1disasm/_incObj/sub AnimateSprite.asm:17-23`) is a DECREMENT timer: each frame `subq.b #1,obTimeFrame; bpl => no change`; when it underflows it reloads `obTimeFrame` with the script speed byte, reads the frame ID at `obAniFrame`, then `addq.b #1,obAniFrame` (`afEnd`/`$FF` wraps the index to 0). Because `obTimeFrame` is cleared to 0 when the object is allocated, the FIRST call underflows immediately and shows frame[0], so EVERY step — first included — is held for `speed + 1` frames.

The trap: a count-up `tick++; if (tick >= speed+1) { tick=0; index++ }` holds the first step for only `speed` frames (the index advances on call `speed+1`, having displayed frame[0] only `speed` times), then `speed+1` for all later steps. Net effect: the animation is permanently one frame ahead, and any frame-gated action (launch/throw/attack/explode) fires one frame early. Symptom in a trace: a projectile/child spawned by the object is exactly 1px/1frame ahead of ROM along its whole path, producing an early HURT/contact.

Mirror ROM precisely: `displayedFrame` (obFrame) starts at 0; `scriptIndex` (obAniFrame) is read-then-incremented; `timeFrame` (obTimeFrame) starts at 0 and is decrement-then-reload. Prefer the shared `AnimationTimer`/`AnimateSprite` path when the object's animation has no bespoke needs.

**Originating commit.** `bugfix/ai-sbz1-f6082-slotprobe` (S1 Ball Hog Obj0x1E rolled a count-up timer whose 9-frame first step threw its Cannonball Obj0x20 one frame early → SBZ1 f6082 -> GREEN).

## P20 — An object's first update (ROM routine 0 / init) sets the routine and `bra DisplaySprite`-RETURNS; it must NOT also run the resolved routine the same frame

When the engine collapses ROM routine 0 (the object's init handler) into a lazy `ensureInitialized()` and then falls straight through to the resolved-routine `switch` on the same `update()` call, the object runs ROM routine 0 AND routine 2 (or whatever init selected) on its spawn frame — putting it permanently one frame ahead of ROM. ROM routine 0 is itself a full frame: it sets `obRoutine` (e.g. `addq.b #2,obRoutine`) and ends with `bra DisplaySprite` (`rts`-equivalent), so the resolved routine's body (movement/fall/timer) runs only from the NEXT frame. Symptom: an object's whole trajectory (and any position/off-screen-derived event — landing, self-delete, slot free) is shifted one frame earlier than ROM; for off-screen self-deleting objects this frees their SST slot a frame early and cascades the `FindFreeObj` slot allocation of later spawns (a slot-cadence divergence in trace `obj_sNN_slot` fields).

Fix: on the first update, run `ensureInitialized()` (which performs the routine-0 work: set routine, spawn any routine-0 children, set initial velocities) and then RETURN — do not execute the resolved routine until the next frame. Verify with a per-frame RAM probe of the object's slot (`obRoutine` transition frame, position) that the engine's routine-transition frame matches ROM's.

**Originating commit.** `bugfix/ai-lz2-f1068-reprobe` (S1 Animals Obj0x28 ran the routine-2 `ObjectFall` on its spawn/init frame, landing + hopping + self-deleting one frame early → freed SST slot 0x20 a frame early → LZ2 f1068 -> f2394). Same one-frame-ahead family as P13 (which defers a routine TRANSITION in the pre-update SOLID pass); P20 is the INIT-frame variant.

**`FindFreeObj`/`FindNextFreeObj`-spawned children are the common case — and may be ASYMMETRIC within one spawn batch.** A child object the parent creates via FindFreeObj is allocated with `obRoutine = 0` (cleared slot), so on its first execution it runs the object's routine-0 init (which sets `obRoutine = obSubtype`/`addq #2` and returns WITHOUT moving) and only moves the NEXT frame — model this as `skipsSameFrameUpdateAfterSpawn() == true`. But a child the parent builds by REUSING its own slot (`movea.l a0,a1`) and falling straight through to the resolved routine moves the SAME frame (no init pass). So a single spawn loop can mix immediate-move and deferred-move children. Originating commit `bugfix/ai-slz3-f3249`: S1 Walking Bomb Obj0x5F `Bom_BurnFuseAndExplode` (`docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:181-220,30-33`) reuses the fuse slot for shrapnel piece 0 (falls into `Bom_Shrapnel`/`SpeedToPos`, moves same frame) but allocates pieces 1-3 via `FindNextFreeObj` (run `Bom_Main` routine-0 first, move next frame). The engine moved all four same-frame, so the hitting piece was one flight-step ahead (1px low) and the just-landed STANDING player's `ReactToItem` box missed it (Y separation 0x21 vs the boundary-inclusive 0x20). Fix: `deferFirstMove` flag on `Sonic1BombShrapnelInstance` for pieces 1-3 only (SLZ3 f3249 -> f4026, 714 -> 480 errors). PC-probe (`event.onmemoryexecute` on `ReactToItem`/`React_CollisionDetected`) confirmed ROM's shrapnel was at the pre-update position and Sonic was standing. **Variant — maker-reuse keeps routine 0, and the slot-reuse is itself load-bearing for downstream cadence:** a maker that REUSES its own slot for child #0 can keep `obRoutine = 0` (LCon_MakePtfms just sets obID/X/Y/subtype and `rts`, it does NOT fall through to the resolved routine), so child #0 moves NEXT frame, not same-frame. Crucially, modeling the maker as a SEPARATE self-deleting spawner that allocates EVERY child via FindFreeObj drops the maker's slot from the platform set and shifts all later FindFreeObj results up one slot, flipping a downstream child from below-the-maker (already processed -> next-frame) to above-it (same-frame) — a 1-executed-frame / 1px lead with no lag delta. Originating commit `bugfix/ai-lz1-conveyor-spawn`: S1 LZ Conveyor Obj0x63 maker `loc_12460` (`movea.l a0,a1`, routine stays 0; `docs/s1disasm/_incObj/63 LZ Conveyor.asm:95-145`) — the engine spawned platform #0 fresh, shifting the ridden descending platform from ROM slot 72 (< maker slot 92 -> next-frame) to engine slot 96 (same-frame), leaving the ridden platform 1px ahead (LZ1 f5745 -> f7325). Fix: `ObjectLifetimeOps.detachSlotForTransfer` + force platform #0 into the maker's slot; platforms #1..N keep FindFreeObj.

## P21 — A `RememberState` (`out_of_range`) object's `isPersistent()` must use `isInRangeAt(getX())`, NOT a symmetric `isOnScreenX(margin)`

Many S1 layout objects end every routine with `bra.w RememberState`, whose `out_of_range.w` macro deletes the object once its CHUNK-ALIGNED X leaves `[camera-128, camera-128 + 0x280]` (`docs/s1disasm/_incObj/sub RememberState.asm`; `docs/s1disasm/Macros.asm:273-290`). That window is ASYMMETRIC: ~512px of right slack but only ~one 0x80 chunk of left slack. A symmetric `isOnScreenX(margin)` (e.g. `±192`) keeps the object alive too far to the LEFT — for a `±192` margin, up to ~64px past the ROM left cull. For an object whose only job is to occupy an SST slot, that extra survival keeps its slot busy a few frames too long, so a later `FindFreeObj` spawn lands in a different slot than ROM (a slot-cadence divergence in trace `obj_sNN_slot` fields — a +1/-1 offset on a downstream object). Symptom: occupancy matches ROM while both are on-screen, then the engine retains the object several frames after ROM's `slot_dump` shows it freed (camera reaches the chunk-cross frame).

Fix: `isPersistent()` returns `!isDestroyed() && isInRangeAt(getX())` (the engine's exact `out_of_range` macro). Use the custom-coordinate overloads (`isInRangeAt(origX/baseX/...)`) for objects whose RememberState tests a coordinate other than `obX`. Sibling objects already doing this: FloatingBlock, LabyrinthBlock, MovingBlock, Platform, Seesaw, LavaGeyser. Verify by dumping engine `ObjectManager` slot occupancy vs the trace `slot_dump` aux across the window — the engine's slot-free frame should match ROM's.

**Originating commits.** `bugfix/ai-lz2-f2394` (S1 LZ Waterfall Obj0x65 used `isOnScreenX(192)`, retaining three off-screen-left waterfalls in slots 35/39/43 ~64px past the ROM cull → the air-bubble maker's `FindFreeObj` shifted +1 → LZ2 f2394 -> f6418). `bugfix/ai-sbz2-despawn` (S1 SBZ Small Door Obj0x2A `AutoDoor` used `isOnScreenX(160)`, retaining the door ~32px past the ROM left cull → its SST slot freed one ObjPosLoad batch late → SBZ2 first slot-occupancy divergence advanced f1080 -> f1334; ROM `ADoor_OpenShut` ends `bra.w RememberState`, `docs/s1disasm/_incObj/2A SBZ Small Door.asm:55`). NOTE: the sibling FlappingDoor (`Sonic1FlappingDoorObjectInstance`) still uses `isOnScreenX(160)` — likely the same latent bug, not yet trace-validated. Contrast with persistent objects that have NO `out_of_range`/`DeleteObject` path (e.g. SYZ boss blocks) which should return `true` regardless of screen position.

## P22 — Object `getX()`/`getY()` must return the ROM CENTRE (`obX`/`obY`), never the sprite top-left (centre − half-width)

ROM `obX`/`obY` are the object CENTRE, and the shared touch overlap (`ObjectTouchResponseController.isOverlapping`) reads `instance.getX()`/`getY()` (and the frame-start `getPreUpdateX/Y` snapshot of them) as the object centre — it computes the box left edge as `objectX - objectWidth`, matching ROM `_incObj/Sonic ReactToItem.asm` testing `obX(a1)` against the React_Sizes half-extents. The default `getX()` returns `getSpawn().x()` (centre), and every other S1 object (boss, cannonball, shrapnel, badniks) returns its centre. If an object OVERRIDES `getX()`/`getY()` to subtract its render half-width (`obActWid` / a `WIDTH_PIXELS`-style constant) to produce a render top-left, its entire touch box is shifted up-left by that half-width, so a marginal contact (a few px) silently misses. `obActWid` is the render/off-screen-cull half-width ONLY — it is NOT a position offset and must not be folded into `getX()`/`getY()`. Render code should subtract the half-width at draw time (or `drawFrameIndex` centres the sprite from `xPos>>16`), keeping `getX()`/`getY()` as the centre.

Symptom: a `col_*|col_hurt` (or enemy/boss) object whose CENTRE matches ROM within a pixel or two still reports `ov=0` in the trace `scan`/`touchBox` context and never fires its hurt/bounce; the player passes through. Check the eng-near object position against the ROM `object_near` centre — if it is offset by a constant half-width in BOTH axes, `getX()`/`getY()` are returning top-left.

**Originating commit.** `bugfix/ai-slz3-f5917` (S1 SLZ Boss Spikeball Obj0x7B subtracted `WIDTH_PIXELS=$C` (= `obActWid` 24/2) in `getX()`/`getY()`, shifting its `col_16x16` hurt box 12px up-left; the rolling player rising into the falling ball missed the hurt → SLZ3 f5917 -> f6113). Fix: `getX()=xPos>>16`, `getY()=yPos>>16` (centre); the boss-hit check and rendering already used the centre.

## P23 — Spawn `FindFreeObj` children in the object's OWN update routine, NOT in the player touch callback — or the child runs one frame early

Some objects are "broken/triggered" by the player's touch pass (ROM `ReactToItem` / `Touch_Response`, which runs inside Sonic's slot-0 collision processing) but actually allocate their effect children (explosion, power-up, debris) in the object's OWN routine, which executes later in the same frame at the object's (usually high) SST slot during `ExecuteObjects`. The S1 monitor is the canonical case: `ReactToItem` only bounces the player and sets the monitor's `obRoutine = 4`; the monitor's own `Mon_BreakOpen` (run when `ExecuteObjects` reaches its slot) does the two `FindFreeObj` spawns of the power-up (Obj0x2E) + explosion (Obj0x27) (`docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:181-198`). Because `FindFreeObj` returns the LOWEST free slot, those children almost always land in a slot BELOW the spawning object's slot. `ExecuteObjects` runs low→high and has already PASSED those lower slots, so the children get their first routine execution NEXT frame, not the spawn frame (`docs/s1disasm/_incObj/27, 3F Explosions.asm:30-53`: `ExItem_Main` first runs a frame after spawn).

The engine runs S1 touch responses BEFORE the object exec loop (`objectsExecuteAfterPlayerPhysics=true`: `LevelManager.applyTouchResponses` during `tickPlayablePhysics`, then `updateObjectPositionsPostPhysicsWithoutTouches`). So if you `spawnFreeChild(...)` directly inside `onTouchResponse`, the child lands in `dynamicObjects` BEFORE the exec loop rebuilds `execOrder` — it gets swept into the SAME frame's `execOrder` and runs immediately, one frame early. For a fixed-lifetime animation child (the explosion counts mapping frames 0→5 then `DeleteObject`), running a frame early means it EXPIRES a frame early and frees its SST slot a frame early → the next `FindFreeObj` allocation takes that freed slot instead of ROM's higher slot → a global ±1 SST-slot offset cascade (trace `obj_sNN_slot` / `obj_extra_sNN_*` fields drift across many downstream objects, surfacing hundreds of frames later).

Fix: split the break/trigger. In `onTouchResponse` do only the ROM `ReactToItem` part (bounce the player, mark broken, set a `pendingBreakSpawn` flag) and DEFER the `FindFreeObj` `spawnFreeChild(...)` calls to the object's own `update()` (ROM's own-routine spawn). `ObjectManager`'s slot-relative deferral (it wires a freshly-spawned child into `execOrder` only when its exec index is GREATER than `currentExecSlot`) then correctly runs a lower-slot child next frame and a higher-slot child same frame — matching ROM — but only because `update()` runs with `updating=true` and `currentExecSlot` set to the spawning object's slot. The same deferral does NOT engage from the touch callback (`updating=false`, `currentExecSlot=-1`). Symptom: occupancy/positions match ROM for thousands of frames, then a `obj_sNN_slot exp 0xNN act 0x(NN-1)` first error appears far downstream; trace it back via a per-frame engine-slot-vs-`slot_dump` diff to a transient effect child (explosion) that the engine deletes one frame before ROM.

**Originating commit.** `bugfix/ai-lz2-f6418-badnik` (S1 Monitor Obj0x26 spawned its power-up + explosion in `onTouchResponse`; the slot-38 explosion ran/expired a frame early at f3505 vs ROM f3506, freeing slot 38 so the next Obj0x36 took slot 38 not 39 → a -1 cascade surfacing at LZ2 f6418). Fix: deferred both `spawnFreeChild` calls to `Sonic1MonitorObjectInstance.update()` (ROM `Mon_BreakOpen`), LZ2 f6418 -> f7800. Related to the one-frame-ahead family P13/P20 (those defer a routine transition / init-frame move of the object itself; P23 defers the SPAWN PHASE of its children so slot ordering matches ROM).

## P24 — A `FindFreeObj` failure (`bne .nocountdown`) is a RETRY, not a no-op: the pool-full path still consumes its pre-`FindFreeObj` RNG but must NOT spawn / advance counters / clear production state

Several S1 producer objects do `jsr (RandomNumber) ... move.b/w d0,<spawn-interval> ; jsr (FindFreeObj) ; bne .nocountdown` — i.e. they consume one `RandomNumber` (to re-arm their spawn-interval counter) BEFORE testing `FindFreeObj`, then bail to `rts` if the dynamic SST is full. The critical part is what the bail does NOT do: it does NOT spawn the child, does NOT consume the object's later (post-spawn) `RandomNumber` calls, does NOT decrement the "how many to spawn" counter, and does NOT clear the "production active" flag. So while the 96-slot dynamic pool (`v_lvlobjspace..v_lvlobjend`, slots 32–127) is full, the object keeps re-arming its interval and **retries every interval, burning one `RandomNumber` per retry**, until a slot frees. If the engine instead always "spawns" (its `addDynamicObject` silently fails when full) and unconditionally advances its counters/clears its production flag, it stops retrying several intervals early → it consumes FEWER `RandomNumber` calls than ROM → the global RNG seed drifts → every later RNG-derived value (other objects' spawn positions, angles, types) diverges. This surfaces as a downstream object with a correct SLOT but a wrong RNG-derived POSITION (e.g. `obj_extra_sNN_x` a few px off) hundreds of frames after the real cause.

**What to check.** For any object whose ROM routine has `jsr (FindFreeObj) / bne <bail>`, model the bail explicitly: query a non-mutating free-slot probe (`ObjectManager.hasFreeDynamicSlot()` → `SlotAllocator.hasFreeSlot()`) at the exact ROM position (after the pre-`FindFreeObj` RNG, before the spawn + post-spawn RNG + counter updates). If no free slot: return after the pre-RNG only. Do not rely on `addDynamicObject` failing — by then you have already consumed the wrong RNG and mutated counters.

**How to find it.** Engine-instrument `GameRng.nextRaw()` to log per-frame the caller class, run the trace, and diff per-frame RNG-call counts against a BizHawk `event.onmemoryexecute` hook on `RandomNumber` (S1 `0x29AC`) that records `emu.framecount()` + `M68K A0` (caller slot). The first frame where ROM makes MORE `RandomNumber` calls than the engine is the pool-full retry the engine skipped. Confirm by probing the producer object's RAM (`objoff_3A` interval cycling while its "count" byte and "production" flag stay frozen = it is retrying against a full pool).

**Originating commit.** `bugfix/ai-lz2-f7800` (S1 LZ drowning countdown Obj0x0A `Drown_Countdown` `.makenum`: `docs/s1disasm/_incObj/0A LZ Drowning Countdown.asm:280-340`. The engine's `Sonic1FixedAirCountdownManager.makeItem` always spawned + decremented `objoff_34` + cleared `objoff_36`, stopping ~5 bubble retries early while the 96-slot pool was full f7682–7796, leaving the engine RNG 6 calls behind ROM by the time the LZ air-bubble maker Obj0x64 spawned at f7800 → its bubble X was 5px off (`obj_extra_s72_x`, slot 0x72=114). Fix: gate `makeItem`'s post-`obj3a` body on `hasFreeDynamicSlot()`. LZ2 f7800 -> f8491, 1107 -> 220 errors). Distinct from P8 (which is about the SUCCESS path under-allocating real child slots); P24 is about the FAILURE path under-consuming RNG.

## P25 — A multi-segment badnik's child/body segments must despawn IN SYNC with the parent's off-screen despawn; gate child persistence on the parent being DESTROYED (slot freed), not on its pre-destroy "marked for delete" state

Multi-segment badniks (a head + N trailing body segments, each its own SST slot) delete the whole chain together in ROM. The head's off-screen tail (`out_of_range` → `Cat_Despawn`) sets the head's `obRoutine = $A` (Cat_Delete) and `rts` on frame N — it does NOT delete yet. The body segments execute AFTER the head in slot order; on the SAME frame N each segment detects `cmpi.b #$A,obRoutine(a1)` (parent routine $A), branches to `.delete` which sets its OWN `obRoutine = $A` and (with REV01 `FixBugs = 0`) **falls through to `DisplaySprite`** — so every segment is still drawn on frame N. On frame N+1 the head AND all segments run `Cat_Delete` → `DeleteObject` together. Net: head + bodies free their slots on the SAME frame (N+1), one frame after the head's `out_of_range` fired (`docs/s1disasm/_incObj/78 Badnik - Caterkiller.asm:139-152,368-391`; the FixBugs-only `DeleteChild` early-delete is OFF in the shipping ROM).

The engine has TWO removal paths for a body segment: (a) its own deferred-delete latch (set in `update()` when it sees the head deleting, then `markDestroyed()` next frame), and (b) the generic `unloadCounterBasedOutOfRange` pass, which culls any non-persistent off-screen object BEFORE its `update()` runs that frame. The trap: the head's off-screen despawn maps to a one-frame window where `head.isDeleting() == true` but `head.isDestroyed() == false` (the head removes itself the NEXT frame, top of its update). If the body's `isPersistent()` returns false as soon as `head.isDeleting()` flips (`return !head.isDestroyed() && !head.isDeleting()`), then on frame N the off-screen segments are culled by path (b) — before path (a)'s `update()` can latch their deferred delete — freeing their SST slots one frame EARLY (frame N instead of N+1). That early slot free lets the next `FindFreeObj`/placement allocation (rings, the next placement object) grab the segment's slot a frame ahead of ROM → a slot-cadence inversion that surfaces far downstream.

Fix: gate the segment's persistence on the parent's slot still being LIVE, i.e. `return !head.isDestroyed();` (drop the `&& !head.isDeleting()`). The segment then survives the frame the head sets its delete routine, its own `update()` latches the deferred delete, and it frees on the same frame as the head — matching ROM's together-delete. (The player-kill/fragment path is unaffected: `destroyBadnik` sets head destroyed immediately, the segments are on-screen so path (b) never culls them, and the `bodyDeletionEarliestFrame` +2 defer still governs.)

**Originating commit.** `bugfix/ai-caterkiller-despawn` (S1 Caterkiller Obj0x78: off-screen body segments slots 66/67/106 were culled at f1334 via the out_of_range pass — `head.isDeleting()` flipped the frame the head went off-screen, before each segment's `update()` could latch — while ROM (and the engine head) free at f1335; the early slot-66 free let a later-spawned conveyor Obj0x68 land on engine slot 79 instead of ROM slot 66. Fix: `Sonic1CaterkillerBodyInstance.isPersistent()` returns `!head.isDestroyed()`; the conveyor now lands on ROM slot 66 and the body chain frees in sync with the head at f1335. SBZ2 first-error f2323 holds — a further slot-62 conveyor↔lost-ring interleave link remains — but the f1334 Caterkiller-body slot divergence is resolved). Generalize to any future segmented badnik (head + body chain). Related to P21 (off-screen persistence semantics) and P23 (child slot-cadence timing), but P25 is specifically the child↔parent despawn-frame SYNC under the parent's off-screen `out_of_range` tail.

## P26 — A maker/spawner object that latches `v_obj63`-style "already spawned" state must CLEAR that latch when its cluster goes `out_of_range`, or the cluster spawns ONCE and is gone forever after the first visit

Maker/spawner objects (LZ conveyor Obj0x63 `loc_12460`, SBZ spin conveyor Obj0x6F `loc_16380`) read child positions from an `ObjPos*Platform` table and spawn a cluster of children. To avoid double-spawning while the maker stays on-screen they do `bset #0,(v_obj63,slot)` and `bne DeleteObject` (self-delete if the bit was already set). The crucial counterpart is in the children's `out_of_range` tail: when the cluster leaves the screen, ROM CLEARS that latch so a later re-entry re-creates the cluster. For Obj0x63 this is `loc_12378`: `move.b objoff_2F(a0),d0 / bpl DeleteObject / andi.w #$7F,d0 / bclr #0,(v_obj63,d0.w)` — only the ONE child whose `objoff_2F` is negative clears the bit (`docs/s1disasm/_incObj/63 LZ Conveyor.asm:22-28`). That child is "platform #0", which the maker creates by reusing its OWN slot (`movea.l a0,a1 / bra LCon_MakePtfms`, asm:111-112) so the maker's negative subtype ($80+) stays in `objoff_2F`; children #1..N (allocated via `FindFreeObj`) have `objoff_2F=0` and just delete.

The trap: it is easy to implement the spawn-side latch (`testAndSetSpawned`) and the maker's slot-reuse for platform #0, but FORGET the clear-on-`out_of_range`. Then the first time the player passes the spawner the cluster spawns correctly; the platforms eventually go off-screen and are deleted, but the latch stays set; every later re-entry runs the maker, sees the latch set, and self-deletes WITHOUT spawning. The cluster is permanently gone — only any directly-placed/`RememberState` decorations (e.g. the conveyor wheels, sub=0x7F) remain. Because the missing objects are moving platforms, the player falls straight through the now-empty loop where ROM lands him on a platform.

**What to check.** For any maker/spawner with a `testAndSetSpawned`/`bset`-style latch, implement the matching CLEAR on the child's out_of_range unload. Tag the maker-reincarnated child (platform #0) with the maker's dedup slot and clear it in `onUnload()`, gated to the out_of_range path (`mode==PLATFORM && initialized && !isDestroyed()`) so the maker's own post-spawn self-delete never clears it. Clear the MAKER's `v_obj63` index (`objoff_2F & 0x7F`), not the child's path-group index — they coincide for some maker subtypes but not all (e.g. LZ maker slots 6/7 reuse pf1/pf2 data, so path group ≠ maker slot).

**How to find it.** When a trace shows the player falling through a region where ROM lands him on a moving platform (`y`/`air` divergence, `on_object 0->1` in the ROM aux), dump all instances of the object id near the player across several frames: if the only survivors are the static/decorative subtype (velocity 0, `mode==WHEEL`) and the moving platforms are absent — and a spawner of that id ran a few hundred frames earlier then self-deleted — the dedup latch was never cleared. The `object_appeared` aux shows ROM re-spawning the cluster on each re-entry; the engine spawns it only on the first.

**Originating commit.** `bugfix/ai-lz1-f9716` (S1 LZ conveyor Obj0x63: `Sonic1LZConveyorObjectInstance` had the spawn-side `testAndSetSpawned` + maker-slot reuse for platform #0 but no clear-on-out_of_range, so the group1 loop near LZ1 trace f9716 had only its 5 wheels left after the first visit and the falling player dropped through instead of landing on the re-spawned platform @129D,0455. Fix: tag platform #0 with `makerDedupSlot` and add `onUnload()` → `conveyorState.clearSpawned(makerDedupSlot)`, mirroring the existing `Sonic1SpinConveyorObjectInstance.onUnload` precedent. LZ1 f9716 -> f12097 (168 -> 35 errors); LZ3 f11802 -> f18196 (481 -> 6 errors) — the same conveyor re-spawn bug). Related to P21 (off-screen persistence) but P26 is specifically the GLOBAL spawn-dedup latch that must be released on the cluster's out_of_range, not the object's own persistence.

## P27 — An in-place `obID` change (e.g. spikeball → Explosion) KEEPS the object's slot + scratch RAM (`objoff_*`), so downstream raw-RAM scans still match the lingering post-change object — model it as the SAME object surviving, not destroy + spawn-separate

When ROM "replaces" an object with another type via `move.b #id_X,obID(a0)` (an IN-PLACE id change, the same family as the Walking Bomb → Explosion / OOZ transfers documented in the `trace-replay-bug-fixing` skill), the object KEEPS its OST slot and ALL of its scratch fields (`objoff_30`/`objoff_3C`/etc.) — only `obID` (and usually `obRoutine`) change. The post-change object then runs the new type's code but its old scratch bytes persist until the slot is finally `DeleteObject`-ed (which zeroes the RAM). Any OTHER object that scans raw object RAM by a scratch offset will therefore still match the lingering post-change object for its whole remaining life.

The trap: the engine usually models such a transform as "destroy the old object + spawn a fresh replacement object". That is behaviourally close for the replacement's own logic, but it (a) frees the old slot and allocates the replacement in a DIFFERENT `FindFreeObj` slot (ROM keeps the SAME slot), and (b) DROPS the old scratch fields — so any cross-object raw-RAM scan keyed on those fields no longer sees it. If a third object's behaviour depends on that scan, the engine diverges.

Concrete case (SLZ3 f11325 -> f12712): the SLZ boss spikeball (Obj0x7B) self-destructs/lands → `BossSpikeball_Explode` does `move.b #id_Explosion,obID(a0)` (`docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm`:833-864). The object becomes the Obj3F bomb explosion in its own slot but keeps `objoff_3C` = its linked seesaw address for the whole ~41-frame explosion life (Obj3F frames 0-5 @8f, `27, 3F Explosions.asm`:62-85). The boss's `BSLZ_MakeBall .checkForBall` duplicate-drop guard (released-ROM FixBugs=0) is a raw 4-byte `objoff_3C` scan of slots 1..63, so it still matches the lingering explosion and ABORTS a fresh drop on that seesaw. The engine had destroyed the spikeball and spawned a separate `ExplosionObjectInstance`, dropping the seesaw link, so its `targetSeesawHasPendingBall` missed it → the boss dropped where ROM aborted → each wrong drop added a ~40-frame `BSLZ_MakeBall` pause (the boss does NOT `BossMove` during the countdown) → ~96px boss phase-lag → missed seesaw spring at f11325.

**What to check.** When a ROM object does an in-place `obID` change, model it as the SAME object surviving (keep the instance in its slot, switch its render/behaviour to the new type, retain its scratch fields, run the new type's lifetime, then delete) — do NOT destroy it and spawn a separate replacement, especially when ANY other object scans object RAM by a scratch offset the post-change object retains. Keep the replacement's lifetime byte-exact (here Obj3F: S1 init/reload duration 7, frames 0-5 @8 frames each, delete at frame 5) because the scan window = the post-change object's alive duration.

**How to find it.** A long-fight CADENCE drift (a periodic spawner's position/timing slowly diverging) whose first divergence is a single missing/extra spawn: dump the spawner's per-frame decision (here the boss `ob2ndRout` + `obBossX`) AND the raw OST slots its guard scans. If ROM aborts/acts on a slot holding a POST-change object (id already mutated, e.g. an Explosion) carrying a stale scratch pointer while the engine's equivalent slot is empty/freed, the engine modelled the transform as destroy+spawn instead of in-place. (gpgx Genesis does not fire `onmemoryexecute`, so reconstruct the cadence from per-frame `mainmemory` sampling: spawner routine/position + a raw slot dump of `obID`+the scanned scratch field at the abort frame.)

**Originating commit.** `bugfix/ai-slz3-dropcadence` (S1 SLZ boss spikeball Obj0x7B: `Sonic1SLZBossSpikeball.updateExploding` now keeps the ball alive in its slot as the Obj3F explosion — collision off, renders via the shared explosion renderer, retains its seesaw — instead of destroy + spawn-separate `ExplosionObjectInstance`, so the boss `BSLZ_MakeBall .checkForBall` duplicate-scan sees the lingering explosion exactly as ROM does. Drop cadence matches ROM through 25 events and now also aborts at vfc 10938; SLZ3 f11325 -> f12712, 65 -> 22 errors). Same in-place-transfer family as the "In-place object→object transfer" note in the `trace-replay-bug-fixing` skill (`detachSlotForTransfer`/`addReplacementAtTransferredSlot`) — P27 adds the cross-object raw-RAM-scan consequence and the "keep the scratch fields" requirement.

## P28 — A multi-state object's FRAGMENT / break-apart off-screen delete must clear the respawn bit (`setDestroyedByOffscreen()`), NOT `setDestroyed(true)`, or the layout entry never respawns

Some S1 objects have a secondary "broken apart" mode that is NOT a player kill: e.g. the Caterkiller (Obj0x78) enters fragment mode when the player touches a BODY segment (`triggerFragmentFromBodyHit` → routine `$C` `Cat_Fragment`), and the head + segments bounce around independently. The ROM fragment tail is `tst.b obRender(a0) / bpl.w Cat_Despawn` (`docs/s1disasm/_incObj/78 Badnik - Caterkiller.asm:206-208` `Cat_Fragment` `.displayOrDelete`): an OFF-SCREEN fragment routes to `Cat_Despawn`, which does `bclr #7,2(a2,d0.w)` (clear the `v_objstate` respawn-block bit, Caterkiller.asm:139-148) BEFORE `DeleteObject`. That is the RememberState off-screen unload — the layout entry is free to re-create the object when the camera returns.

The trap: model the fragment's off-screen delete as `setDestroyed(true)` (engine `destroyedRespawnable=false`). That keeps the REV01 `bset` placement counter bit (`objState[counter]`) LATCHED. When the cursor later re-crosses the layout entry, `trySpawnCountered`'s `bset` test sees the bit already set and SKIPS the respawn — the object is gone for the rest of the level. Because the released ROM (FixBugs=0) intentionally SHARES one counter value across several respawn-tracked entries, a stale latched bit also blocks UNRELATED objects mapped to the same counter. This is a slot-cadence time bomb: the missing object frees SST slots that ROM keeps occupied, so a much-later `FindFreeObj` (here a scattered-ring spill, then a Basaran drop-gate keyed on its slot via `(v_vblank+d7)&7`, d7=127−slot) lands in the wrong slot.

**What to check.** For ANY object whose off-screen self-delete is the ROM `RememberState` / `out_of_range`→`*_Despawn` family (the tail does `bclr #7` then DeleteObject), call `setDestroyedByOffscreen()` (respawnable; the cleanup path clears the placement counter bit via `clearsRespawnStateOnCounterBasedOutOfRange()`), NOT `setDestroyed(true)`. Reserve `setDestroyed(true)` for genuine player kills / in-place→Explosion transforms (`destroyBadnik`) that ROM does NOT route through a respawn-bit clear. The Caterkiller's main walk path already used `setDestroyedByOffscreen()`; only the FRAGMENT path under-modelled it.

**How to find it.** A badnik that ROM RESPAWNS when the camera revisits its layout X (visible as the same `object_appeared` X reappearing in the `slot_dump`/aux at a later frame) but the engine never re-creates. Diff engine SST occupancy vs the ROM `slot_dump` to find the missing object, trace its layout-entry respawn through `trySpawnCountered` (the `objStateBit` test), then walk back to the despawn: if its destroy left `isDestroyedRespawnable()==false` while the ROM tail does `bclr #7`, the off-screen delete used the wrong API.

**Originating commit.** `bugfix/ai-mz2-f4610` (S1 Caterkiller Obj0x78 `Sonic1CaterkillerBadnikInstance.updateFragment` off-screen branch: `setDestroyed(true)` → `setDestroyedByOffscreen()`. The MZ2 @0x200 Caterkiller the player frag-hit at the start then walked away from now respawns at f3031 as ROM does, restoring slots 39/41/42/52 → the f4019 scattered-ring spill lands in ROM slots [35,39,41] not [35,54,58] → the Basaran takes slot 40 not 39 → its drop-gate fires on ROM's frame → the f4610 bounce registers. MZ2 f4610 → f8661, 824 → 665 errors; full S1 sweep + S2 EHZ1 held GREEN). Related to P21 (RememberState slot-cadence) and the P25 multi-segment despawn family, but P28 is specifically the secondary FRAGMENT/break-apart death path keeping the respawn bit set.

## P29 — Translate `bchg` as a toggle of the existing bit, not as a value inferred from the resulting motion

**Symptom.** An object launch has byte-perfect position and velocity, but the
player uses the wrong slope-dependent animation bank afterward. The mismatch
can remain hidden on flat terrain and appear only when the launch crosses a
curve while control is locked.

**Root cause.** The ROM uses `bchg #0,obStatus(a1)`, which toggles the player's
pre-contact facing bit regardless of launch direction. Replacing that with
`setDirection(xVel > 0 ? RIGHT : LEFT)` derives a new value instead. Those are
not equivalent when the player was already facing along the launch direction:
ROM intentionally leaves facing opposite to the new velocity after the toggle.

**What to check.** Translate every object-side `bchg` literally from the old
bit value. Do not infer the new flag from velocity, position delta, subtype,
or object facing unless the disassembly separately assigns it with `bset`,
`bclr`, or `move`.

**ROM citation.** `docs/s1disasm/_incObj/41 Springs.asm:146-149`
(`Spring_BounceLR`: set velocity/inertia, then toggle Sonic's status bit 0).

**Originating commit.** S1 SYZ1 animation frontier frame 690 → 1742: the
horizontal spring's rightward launch now toggles the prior right-facing state
to left, restoring mappings `$2B-$2D` on the following steep curve.

## P30 — Player edge balance reads the object's raw `obActWid`, not the padded `SolidObject` collision width

**Symptom.** The player plays the *balancing* animation while standing on an
object where the ROM stands idle. It reports as an animation mismatch, not a
position one: `player_animation_id` `0x06` (`id_Balance`) against `0x05`
(`id_Wait`), and `player_mapping_frame` `0x3A` (`fr_Balance1`) against `0x01`
(`fr_Stand`). Position and riding state look perfectly correct on both sides,
which is what makes it confusing -- the recorded `player_x` and
`player_stand_on_obj` match the engine exactly.

**How to spot it.** Compare the engine's `d1` against the ROM's before assuming
a position bug. If `player_x` and `player_stand_on_obj` both match the
recording, the width is the only remaining input.

**What `obActWid` actually is.** `_Constants.asm:230` names it *action width*,
and the per-object comments call it *sprite display width*, but neither is the
whole story: it is one byte with several consumers and it is never the rendered
sprite extent (the mappings own that). It is read by `BuildSprites`' horizontal
on-screen cull, `d1 = obX - cameraX +/- obActWid` tested against 0 and 320
(`docs/s1disasm/_inc/BuildSprites.asm:49-58`); by `Sonic_Balance`
(`01 Sonic.asm:423`); and by many objects as their own solidity width passed
straight to `PlatformObject`, sometimes after adding `sonic_solid_width`. So an
object whose ROM `obActWid` differs from the engine's default has *two* wrong
consumers, not one -- the balance window and the render cull -- and where the
byte genuinely serves both, supplying it at `getOnScreenHalfWidth()` fixes both
and `getBalanceWidthPixels()` inherits it. Where a class already models its
on-screen width from a different quantity, override the balance accessor alone.

**The siting trap, and why it is invisible.** `obActWid` is one byte with three
ROM consumers -- `BuildSprites`' horizontal on-screen cull, `Sonic_Balance`, and
many objects' own solidity width -- so the normally correct home for it is
`getOnScreenHalfWidth()`, which `getBalanceWidthPixels()` defaults to. **But on a
top-solid object that override is a SILENT NO-OP for balance.**
`getBalanceWidthPixels()` returns `getSolidParams().halfWidth()` for top-solid
objects *before* consulting the on-screen accessor, so the value never reaches
the balance test. It compiles, it looks like the blessed fix, it changes
nothing, and it measures clean. On a top-solid object, override
`getBalanceWidthPixels()`.

**Tell one, nine instances: an unused constant, not a missing one.** In nine of the objects
found so far the correct ROM byte was already present in the class and simply
never handed to the balance test: Obj1A's `ACTIVE_WIDTH = 0x64` had exactly one
reference in the whole file, its own declaration; Obj53 modelled 68 as
`CFLO_ACT_WIDTH` for its cull while balance fell through to 32; Obj36 Spikes and
Obj33 Push Block build their solid params as `actWidth + 0x0B`; Obj26 Monitor
hardcodes `0x1A`, which *is* `15 + $B`. The 2026-08-20 reachability round added
three more: Obj3B GHZ purple rock held `ACT_WIDTH = 0x13` with one reference,
`getTopLandingHalfWidth()`; Obj33 push block derived `activeWidth` from
`PushB_Var` for its collision width and never passed it on; and Obj71 invisible
barrier evaluated the ROM's own `((subtype & $F0) + $10) >> 1` into a live
`halfWidth` field and did the same. Round two added a ninth, Obj2A SBZ small
door, whose `ACT_WIDTH = 8` sat one line above the solid params it was not
feeding. The last two show the shape is not only
constants -- a *derived field* hides just as well. When auditing an object for
this, grep for its width constant or field and count the references.

**Tell two, one instance: the right byte is not always missing, it can be
*substituted*.**
Obj11 GHZ Bridge had no `obActWid` constant at all; it used `logCount * 8`,
which is the ROM's *collision* half-width from `Bri_CheckOnBridge`
(`11 GHZ Bridge.asm:122-126`), not `obActWid`. That is harder to spot than an
idle constant, because there is nothing unused to grep for and the expression
looks purposeful. When an object's width comes from a computation, check which
ROM quantity the computation reproduces.

The two counts are kept apart deliberately. Two named shapes with their own
instances are more useful than six instances of a blurred one, and the second
shape is the one a reviewer waves through: someone did derive the width from the
ROM, just from the wrong quantity.

**Enforced since `TestS1BalanceWidthRomParityGuard`.** Every S1
`SolidObjectProvider` must have a ROM-cited entry there; omission fails. The
top-solid fallback's premise -- that a platform caller passes `obActWid` through
as `d1` -- holds for seven S1 objects and failed for four. All four are now fixed, so the
guard is green; it is enforced rather than deleted because deleting it would
mean seven overrides that only preserve behaviour those classes already get.

**Cross-game.** The same shape is documented in `PlayableSpriteMovement` for the
S2 CPZ/WFZ moving platform Obj19, whose subtype `width_pixels` is
`$20`/`$18`/`$40`, and for SmashableGround (`docs/s2disasm/s2.asm:48703-48705`)
whose balance width is `$10` while its SolidObject width is `$1B`.

**Symptom.** Sonic stands still safely inboard on a wide full-solid object, but
the engine selects Balance while the ROM retains Wait. Physics and standing
contact remain exact; only animation/facing diverge.

**Root cause.** S1 `Sonic_Move` resolves the stood-on SST slot and reads its
raw `obActWid` to form the edge window. Many full-solid callers separately add
Sonic's `$B` collision width before calling `SolidObject`; that padded value is
not written back to `obActWid`. Falling back to a generic 16-pixel render width
or reusing the padded collision width therefore moves the balance edge.

**What to check.** Every solid object with a ROM `obActWid` other than the
shared 16-pixel default must override `getBalanceWidthPixels()` with that exact
byte or subtype-backed field. Keep `getSolidParams().halfWidth()` faithful to
the caller's distinct `SolidObject` argument. Do not infer either from render
dimensions.

**ROM citation.** `docs/s1disasm/_incObj/01 Sonic.asm:409-430`;
Obj2F `LGrass_Data`/`LGrass_Main` at `2F, 35 MZ Large Grassy Platforms and
Burning Grass.asm:23-53`; Obj6B `Sto_Var`/`Sto_Main` at `6B SBZ Stomper and
Sliding Door.asm:25-42`; Obj84 `EggmanCylinder_Main` at `85,84,86 Boss - FZ
Main, Cylinders, and Plasma Balls.asm:660-723`; Obj31 `CStom_Var2` and
`CStom_MainBlock` at `31 MZ Chained Stompers.asm:112-135,184-190`.

**Originating commit.** `bugfix/ai-s1-balance-owner` (FZ, SBZ1, and SYZ2
animation traces green; MZ1 f749 → f2596). Earlier examples: Obj56 floating
block (`ab3112b73`) and Obj61 Labyrinth block (`fc5d5e922`). Obj31 chained
stomper was the next exposed instance: raw `$38/$30/$10`, not the collision
argument extended by `$B`.
Obj30 MZ glass pillar followed (`d7422d98f`): `Glass_Main` writes
`move.b #64/2,obActWid(a1)` = 32 to every pillar child including the parent's own
slot, reused as child 0 via `movea.l a0,a1`
(`30 MZ Large Green Glass Blocks.asm:57,78`), and overwrites it with `#32/2` only
for the reflection child at `:84`. The routine 2/6 `SolidObject` argument is
`#64/2+sonic_solid_width` = `$2B` (`:99,:117`). The default 16 put `d1` at `-6`
where the ROM has `10`, so the player balanced on a pillar he was standing well
inside. A standing audit of every S1 `SolidObjectProvider` against its ROM byte
is recorded in
`docs/architecture/audits/2026-08-19-s1-obactwid-balance-width-audit.md`; the
Obj30 case is not the only one.

**Reachability is a separate question from the byte, and it has its own tell.**
A wrong `obActWid` is only observable if the player can be grounded, standing
still, with that object recorded as his stood-on object. Three of the audit's
rows fail that test for reasons in the ROM rather than the level: Obj44 GHZ edge
wall calls `EdgeWall_SolidWall`, a stripped-down solid routine that never sets
`Status_OnObj` at all (`_incObj/sub SolidWall.asm:14-67`); Obj3E's prison switch
clears the player's `Status_OnObj` on the same frame `SolidObject` sets it
(`3E Prison Capsule.asm:88-109`); and Obj36's spikes hurt on contact from above
for exactly the subtypes whose width differs, the standable sideways ones being
`#32/2` = 16 already. **Check the solid routine's name before the width** -- if
it is not `SolidObject` or `PlatformObject`, it may not produce a stood-on
object, and then no width is wrong. Recorded in the guard as
`RECORDED_UNREACHABLE`, kept distinct from `RECORDED_UNASSESSED` so a later
reader can tell "looked, cannot happen" from "nobody looked".

**A defeated boss is exempt from the balance test entirely.** `Sonic_Balance`
reads the stood-on object's `obStatus` and branches to `Sonic_LookUp` on bit 7
before it ever reaches the width
(`docs/s1disasm/_incObj/01 Sonic.asm:418-420`), and `Sonic ReactToItem.asm:268`
sets that bit on a defeated boss. So no boss's post-defeat width is ever
balance-observable, however carefully the ROM re-writes it -- the FZ boss's
`BossFinal_Eggman_Fall` writes `#96/2` and `#64/2` that nothing can see. Model
them for the render cull if you like; do not treat them as balance evidence.

**`obActWid` can legitimately be zero, and zero is a *total* balance window, not
an absent one.** The FZ plasma launcher's `BossPlasma_Main` writes
`move.b #16/2,obWidth(a0)` where it meant `obActWid`, and unlike the neighbouring
cylinders it was never repaired in either revision; `DeleteObject` zeroes a slot
before `FindFreeObj` hands it out, so the byte stays 0 for the object's life.
Substituting into `d1 = obActWid + dx`, `d2 = 2*obActWid - 4` gives `dx < 4` or
`dx >= -4`, which leave no gap -- the ROM balances the whole time the player
stands on it. When an object has no `obActWid` write at all, the answer is 0, and
0 is a real value; do not reach for the shared default.

**The default is not always too small.** Every early instance widened the balance
window, which made "the inherited 16 is too narrow" an unexamined habit. The SBZ
small door's `obActWid` is 8, so the inherited 16 made the window *wider* than the
ROM's and suppressed balance across `4 <= |dx| < 12`. Check the direction before
describing the symptom.

## P31 -- Only the horizontal spring locks the player's grounded controls

**Symptom.** After a vertical or diagonal launcher, the player's first grounded
frames ignore held left/right. The trace shows both sides landing with the same
`inertia`, then the ROM applying acceleration on the very next frame while the
engine holds the landing value for several frames before catching up. The drift
is small at first and compounds for the rest of the act.

**Root cause.** `move_lock` is the ROM's only grounded-input lock, and in the
spring family only the left/right spring `.doBounce` (`move.w #15,locktime(a1)`) writes it. `Spring_Up` `.bounceUp` (lines 88-101) and `Spring_Down` `.bounceDown` (lines 193-200)
write no lock of any kind. An engine "springing"/"recently launched" marker used
by objects for their own re-contact and carry tests must not also gate
horizontal input -- doing so invents a control lock the ROM has nowhere. It is
easy to miss because `move_lock` is decremented only in the grounded
slope-repel step, so an invented timer that ticks every frame looks harmless in
the air and then bites on the landing frame, several hundred rows from the
object that set it.

**Correct pattern.** Gate grounded input on the modelled `move_lock` timer
alone. A spring that really does set `move_lock` should call the engine's
move-lock setter; keep any launch marker free of input semantics.

**ROM citation.** docs/s1disasm/_incObj/41 Springs.asm:144. Cross-game: S2 `loc_18B1C` at
`docs/s2disasm/s2.asm:34031`, S3K `loc_231BE` at
`docs/skdisasm/sonic3k.asm:47907`, S1 `.doBounce` at
`docs/s1disasm/_incObj/41 Springs.asm:144`.

**Originating commit.** `<pending: spring grounded control lock milestone>`.

## P32 -- merged into P30

This entry duplicated P30 above: both describe an object whose ROM `obActWid`
differs from the engine's default shifting the `Sonic_Balance` window. Two lanes
catalogued the same defect within an hour. The number is retained because commit
`d7422d98f` cites it; read P30.
