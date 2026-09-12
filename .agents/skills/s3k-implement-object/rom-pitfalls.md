# Sonic 3 & Knuckles Object Implementation — ROM Behavioural Pitfalls

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

## P1 — Touch-response directional/state guards diverge from ROM

**Symptom.** Object rejects a rolling / spindash / invincible / shield
touch under a condition ROM doesn't gate on.

**Root cause.** Engine adds an extra gate (player below object, specific
direction, specific timer state) that ROM's touch-response routine doesn't
apply. ROM uses overlap-only for the kill; position only chooses bounce
direction.

**What to check.** When porting `onPlayerAttack` for an S3K badnik or
interactive object, list every gate ROM applies in `Touch_ChkValue` /
`Touch_KillEnemy` (or the S3K equivalent in `sonic3k.asm`). Reproduce only
those.

**ROM citation.** Sonic 3 & Knuckles touch-response routines live in
`docs/skdisasm/sonic3k.asm` near the `Touch_ChkValue` / `Touch_KillEnemy`
labels. S2 equivalent at `docs/s2disasm/s2.asm:84807-84890`.

**Originating commit (S2).** `c2d998751 fix(s2): CPZ Grabber badnik
rolling-kill independent of vertical position`.

---

## P2 — ROM multi-frame init collapsed into one engine frame

**Symptom.** Trace divergence appears N frames before the ROM state
transition fires, then the transition is one frame early. `scriptTimer` /
counter values differ by exactly N.

**Root cause.** ROM dispatches object init across multiple frames: outer
`ObjXX_Init` writes routine and returns, the next frame enters the inner
case 0 which performs the real init. The engine constructor often does
both in zero frames, collapsing ROM's two-frame init into one.

**What to check.** When porting init that has both an outer dispatch label
(`Obj_Init`) and an inner main-routine case 0 with non-trivial setup,
preserve the frame count. Don't pre-resolve the inner init in the
constructor unless ROM's dispatch never actually returns between them.

**ROM citation.** S3K object init pattern lives near each `ObjXX_Index`
table. Search for `bsr.w Obj_Init` or analogous. S2 origin example at
`docs/s2disasm/s2.asm:78271-78284, 78368-78372`.

**Originating commit (S2).** `44d7939e1 fix(s2): WFZ Tornado collapsed
two-frame init compensation`.

---

## P3 — Global object state vs ROM per-player object state bytes

**Symptom.** Knuckles or Tails (player 2) interaction with an object is
suppressed by a state flag Sonic set, or vice versa. Sidekick fails to
trigger a spring / hover-platform / bumper Sonic just used.

**Root cause.** Engine uses a single field for state that ROM tracks
per-player at SST offsets. When the first player flips the global, the
second player sees "already triggered".

**What to check.** List every SST byte ROM reads/writes. If the offset is
`objoff_36`, `objoff_37`, or any per-player pair, the engine must use a
per-sprite map (`IdentityHashMap<AbstractPlayableSprite, Integer>`), not a
global field.

**ROM citation.** S3K per-player object state at the `objoff_36/37`
convention; search `sonic3k.asm` for `objoff_36(a0)` and `objoff_37(a0)`.
S2 origin example at `docs/s2disasm/s2.asm:57870-57879`.

**Originating commit (S2).** `3cb72b6af fix(s2): CNZ Flipper per-player
launch cooldown + ROM-accurate y_pos`.

---

## P4 — Character-dependent coordinate adjustments where ROM uses a fixed offset

**Symptom.** Sidekick Y diverges from leader Y by a character-specific
amount after a rolling launch or hurt — usually 1-5 px.

**Root cause.** Engine reaches for a helper like
`getRollHeightAdjustment()` when ROM has a literal `addq.w #N, y_pos(a1)`
(constant for all characters).

**What to check.** When ROM source shows `addq.w #<literal>, y_pos(a1)`,
port that as a literal `NativePositionOps.addYPosPreserveSubpixel(player,
<literal>)`. Don't substitute a character-aware helper. Reserve raw
`setCentreYPreserveSubpixel(...)` calls for lower-level sprite internals or
non-playable/object-local state.

**Originating commit (S2).** `3cb72b6af` (secondary).

---

## P4b — Rolling status/radius writes without a ROM y_pos write

**Symptom.** A player or sidekick shifts vertically by 1-5 px on an object
release frame even though the ROM trace keeps `y_pos` unchanged. The frame
often also flips `Status_Roll`, `Status_InAir`, or collision radii.

**Root cause.** Engine `setRolling(true)` changes top-left-based sprite
dimensions, which changes `getCentreY()` unless the object restores the
pre-change center. ROM status/radius writes do not imply a `y_pos` write.

**What to check.** If the ROM block writes `y_radius`, `x_radius`,
`Status_Roll`, `jumping`, or `anim` but does not write `x_pos`/`y_pos`,
capture the native center before the engine dimension change and restore it
with `setCentreYPreserveSubpixel(...)` after `setRolling(...)`. Do not use
`getRollHeightAdjustment()` unless the ROM explicitly adjusts `y_pos`.

**ROM citation.** `Obj_HCZConveyorBelt` release `loc_312D4`
(`docs/skdisasm/sonic3k.asm:66440-66457`) writes status/radii/animation but
does not write `y_pos`.

**Originating commit.** `fix: preserve HCZ conveyor release center y`.

---

## P4c — Camera culling uses ROM coarse-back camera registers

**Symptom.** An object disappears one frame before the ROM would process it,
often causing a missed same-frame sidekick/player interaction near the right
edge of the object's cull window.

**Root cause.** Engine code compares against `camera.getX() & $FF80`, while
the S3K object routine reads `Camera_X_pos_coarse_back`, which `Load_Sprites`
sets to `(Camera_X_pos - $80) & $FF80` before `Process_Sprites`.

**What to check.** When an S3K object routine cites
`Camera_X_pos_coarse_back`, compute the same `$80`-shifted coarse value before
left/right cull comparisons. Do not substitute the current raw camera coarse
unless the ROM routine actually reads `Camera_X_pos`.

**ROM citation.** `Obj_HCZConveyorBelt` cull check
(`docs/skdisasm/sonic3k.asm:66355-66364`) and `Load_Sprites`
coarse-back setup (`docs/skdisasm/sonic3k.asm:37472-37478`).

**Originating commit.** `fix: model HCZ conveyor coarse-back culling`.

---

## P5 — SolidObject returns non-solid prematurely on state transition

**Symptom.** Rider drops from a moving solid on the exact frame of an
internal state-machine transition. Trace shows a 1-px y divergence at the
transition frame.

**Root cause.** Engine's `isSolidFor()` gates on internal state
(`routineSecondary != STATE_FALL`). ROM's main dispatcher calls the solid
positioning unconditionally; the object stays solid until it physically
warps off-screen.

**What to check.** `isSolidFor()` for S3K moving solids (AIZ collapsing
platforms, MGZ falling pillars, HCZ retractable spikes, CNZ platforms,
etc.) should track physical existence, not state-machine routine.

**Originating commit (S2).** `719c4034e fix(s2): HTZ Lift
solid-while-falling + ROM-order gravity/move`.

---

## P6 — Gravity-before-move vs ROM's move-before-gravity ordering

**Symptom.** A falling object's y_pos rolls over one frame earlier than
ROM. Surfaces as a one-frame off-by-one on a rider's y at the transition
frame.

**Root cause.** Engine free-fall does `yVel += gravity; yFixed += yVel`.
ROM consistently does `ObjectMove` (move) first, then `addi.w #$<gravity>,
y_vel(a0)`.

**What to check.** When porting an S3K free-fall routine, preserve the
`ObjectMove` → gravity order. S3K's `ObjectMoveAndFall` equivalent will
have the same pattern.

**Originating commit (S2).** `719c4034e` (secondary).

---

## P7 — Centre Y vs top-left Y for kill / boundary checks

**Symptom.** Sidekick fails to die crossing the bottom kill plane, or dies
one frame late.

**Root cause.** Engine compares `sprite.getY()` (top-left) against the
kill plane. ROM compares centre Y (`y_pos(a0)`).

**What to check.** Kill / boundary / out-of-bounds checks must compare
against `getCentreY()` not `getY()`. Same for X-axis side boundaries:
`getCentreX()` not `getX()`.

**ROM citation.** S3K bottom-kill is gated by `Tails_LevelBound`-analog
inside the S3K Tails/Knuckles AI; search `sonic3k.asm` for level-boundary
labels.

**Originating commit (S2).** `4361de0e8 fix(s2): sidekick level-bound
bottom kill uses centre Y to match ROM` (fix applied universally for CPU
sidekicks; S3K already used centre Y on its physics path, so the change
is symmetric).

---

## P8 — Per-game post-event flow divergence (S3K immediate vs S2 deferred)

**Symptom.** Sidekick despawn / level-end / boss-defeat flow differs
between S3K and S2 in a way that the engine generalised over.

**Root cause.** Some post-event flows have intentionally different ROM
semantics across games. The most prominent in trace work:

- **Sidekick death**: S3K `sub_13ECA` warps the sidekick to the despawn
  marker (x=0x4000) on the frame after kill. S2 `Obj02_Dead` defers the
  warp until the sidekick falls past `Tails_Max_Y_pos + 0x100` (several
  frames of gravity-only execution after the kill).

**What to check.** When implementing or modifying post-event flows
(despawn, results-screen handoff, level-end), look at each game's ROM
equivalent separately. If they diverge, route the behavior through the smallest
accurate owner from `docs/architecture/per-game-rule-placement.md`. Never gate on
`gameId`.

**ROM citation.** S3K immediate-warp baseline at
`docs/skdisasm/sonic3k.asm:26800-26809` (`sub_13ECA`). S2 deferred flow at
`docs/s2disasm/s2.asm:40736-40759`.

**Originating commit.** `a4aca7d6f fix(s2): sidekick death uses
deferred-despawn flow to match S2 Obj02_Dead`.

---

## P9 — Integer math drops y_sub carry in 16:16 position updates

**Symptom.** Post-warp / post-teleport y_pos is exactly 1 pixel low (or
high) relative to ROM. The error appears only when the pre-event
`y_sub_pos + (y_vel & 0xFF00)` overflows the 16-bit subpixel boundary.

**Root cause.** ROM `ObjectMoveAndFall` / `MoveSprite` treats `y_pos:y_sub`
as a single 32-bit long and executes `add.l d0,d3` where `d0 = y_vel<<8`
(sign extended). Subpixel overflow carries into `y_pos`. Java code that
does `y_pos += (y_vel >> 8)` after a `setCentreYPreserveSubpixel(...)`
warp treats the halves as independent integers and DROPS the carry. The
overflowed low byte still lands in `y_sub` (because `setCentreY*`
preserves it), but `y_pos` is short by 1.

**What to check.** Any code path that:
1. Writes playable-sprite native `x_pos` / `y_pos` with `NativePositionOps`
   (or lower-level/raw preserve-subpixel setters in sprite internals),
2. THEN integrates by a velocity stored in subpixel units (`x_vel` / `y_vel`),
must use `AbstractSprite.move(xSpeed, ySpeed)` — which mirrors ROM's
`add.l d0, x_pos(a0)` / `add.l d0, y_pos(a0)` — rather than manual
`centreY += (ySpeed >> 8)` arithmetic.

**ROM citation.** S3K `MoveSprite` (`docs/skdisasm/sonic3k.asm:36032-36042`)
and `ObjectMoveAndFall`. Same 16:16 fixed-point convention as S1 / S2.
Engine equivalent: `AbstractSprite.move` in
`src/main/java/com/openggf/sprites/AbstractSprite.java`.

**Originating commit.** `<pending>` (S2 trace frontier advancement loop
iter 1: HTZ F538 + MCZ F443 deferred-despawn sub-pixel & solid-contact
gating; cross-applies to S3K objects warping & integrating velocity).

---

## P10 — Solid object contacts must skip dead / despawning players

**Symptom.** A dying CPU sidekick (or main player) "lands" on a moving
solid object (lift / platform / drawbridge) under the impact point while
ROM would have him fall past it. Engine's sidekick `y` freezes at the
platform top and `y_speed` drops to 0, while ROM keeps Tails falling
through the platform.

**Root cause.** ROM `SolidObject_ChkBounds` (S3K equivalent of S2
`s2.asm:35178-35182`) gates the full bounding-box check with:

```
SolidObject_ChkBounds:
    tst.b    obj_control(a1)
    bmi.w    SolidObject_TestClearPush   ; bit 7 set => skip
    cmpi.b   #6,routine(a1)              ; routine >= 6?
    bhs.w    SolidObject_NoCollision     ; Dead/Gone/Respawning => skip
```

The two gates are independent. The `obj_control bit 7` path covers
respawning / object-controlled states. The `routine >= 6` path covers the
Dead / Gone / Respawning routines themselves.

For S3K, `obj_control` is set on frame N+1 immediately via `sub_13ECA`
(`docs/skdisasm/sonic3k.asm:26800-26809`), which means the `obj_control`
gate covers most of the dead-fall window. The S2 deferred-despawn flow
spends multiple frames in routine = 6 BEFORE `obj_control` flips, so the
`routine >= 6` gate is required there. An engine that ports only the
`obj_control` gate will still apply solid contacts to a sidekick mid-
deferred-death-fall (S2-specific), but the rule is universal.

**What to check.** `blocksSolidContacts(player, candidate)` (or whatever
the engine's SolidObject pre-filter is named) needs BOTH gates:
1. `player.isObjectControlled()` — mirrors `obj_control` bit 7.
2. CPU sidekick state `DEAD_FALLING` (engine equivalent of ROM Tails
   routine = 6) — must short-circuit even though `obj_control` is still
   0 during S2 deferred-despawn.

**ROM citation.** S3K `SolidObject_ChkBounds` in
`docs/skdisasm/sonic3k.asm` (mirrors S2 `s2.asm:35178-35182`). Engine
equivalent: `ObjectManager.SolidContacts.blocksSolidContacts` in
`src/main/java/com/openggf/level/objects/ObjectManager.java`.

**Originating commit.** `<pending>` (S2 trace frontier advancement loop
iter 1: HTZ F538 + MCZ F443).

---

## P11 — Solid object break/trigger condition leaks main-player state into sidekick contact

**Symptom.** Sidekick (Tails / Knuckles) is suddenly knocked airborne +
rolling + Y shifted by 1 px while the main player is rolling through
nearby terrain. Trace shows ROM keeps the sidekick grounded with
`status.standing` and `status.pushing`, while the engine reports
`status.in_air | rolling` and a fresh downward `y_vel`. The divergence
appears on the exact frame the main player passes a breakable /
launchable object even though the sidekick isn't standing on that
object.

**Root cause.** The engine cached `playerWasRolling = player.getRolling()`
inside the object's per-frame `update(...)` method, with `player` being
whichever sprite the object manager happened to pass (typically the main
player). The break/launch decision in `onSolidContact(player, contact)`
then OR'd the cache with the contacting player's own `getRolling()`. When
the main player was rolling, the cache made the OR true even for the
sidekick's side / bottom contact, so the object's break path fired with
the sidekick as the victim.

**What to check.** Any S3K solid object with a state-dependent break /
launch / monitor-pop / trigger:
1. Per-player conditions must read the *contacting* player's state, not
   a per-frame cached "saw rolling once" flag. Use the player parameter
   of `onSolidContact` directly.
2. ROM equivalents in S3K cache main / sidekick anim per-player in the
   object's SST and check `status(a0) & standing_mask` — never a global
   "was rolling" cache.
3. Side / bottom contact rarely breaks ROM solids. Most breakable
   objects only fire on `contact.standing()`. Rolling player into the
   underside gets a CEILING collision via `SolidObject`, not a break.

**ROM citation.** S2 `docs/s2disasm/s2.asm:48889-48959` (Obj32 / breakable
block). S3K equivalents in `docs/skdisasm/sonic3k.asm` use the same
SolidObject + per-player anim cache pattern; check for `standing_mask`
and per-character anim bytes (objoff_32 / objoff_33 equivalents) in the
object's main routine.

**Originating commit.** `<pending>` (S2 trace frontier advancement loop
iter 3: HTZ F979 BreakableBlock; cross-game mirror for S3K-implementable
objects following the same pattern).

---

## P12 -- Angle-based player detection ported as simplified bounding-box + facing guard

**Symptom.** A patrolling badnik that should attack the player when in
horizontal range either never attacks (player always on "wrong" facing
side), attacks at completely wrong frames, or its position drifts from
the ROM trace's position by tens of pixels over the trace lifetime
because it skips ROM attacks. Trace shows ROM badnik moving with periodic
stationary attack pauses; engine badnik continuously oscillates with no
pauses.

**Root cause.** ROM uses `Obj_GetOrientationToPlayer`
(`docs/s2disasm/s2.asm:72320-72346`) which picks the *closest* player
(MainCharacter vs Sidekick) by absolute horizontal x distance, then
returns `d2 = obj.x - closest_player.x` (signed word). The badnik's
attack-trigger condition is typically:

```
addi.w #$60, d2          ; d2 += 0x60 (offset)
cmpi.w #$C0, d2          ; compare against 0xC0
blo.s <attack>           ; branch if (d2 + 0x60) < 0xC0 unsigned
```

This is the canonical "is player within roughly +/-96px horizontally"
test. **There is no Y-axis check and no facing-direction guard.** The
test is symmetric around the badnik's x_pos.

A naive engine port replaces this with `Math.abs(player.x - obj.x) <=
DETECT_X_RANGE && Math.abs(player.y - obj.y) <= DETECT_Y_RANGE &&
playerIsLeft == facingLeft`. The added Y check is wrong (ROM has none).
The `playerIsLeft == facingLeft` guard is fundamentally wrong: it means
the badnik only attacks when the player is "in front" of it, but ROM
attacks regardless of facing. Plus the engine usually only reads
MainCharacter, ignoring the Sidekick selection ROM does.

**What to check.** When porting any badnik that uses
`Obj_GetOrientationToPlayer` followed by an `addi.w/cmpi.w/blo` pattern:

1. The check is horizontal-only -- do NOT add a Y bounds gate.
2. Compare against the *closest* of MainCharacter and Sidekick. Iterate
   `services().sidekicks()` and pick the sprite with minimum
   `Math.abs(currentX - sprite.getCentreX())`, preferring MainCharacter
   on ties (ROM `bls.s` keeps MainCharacter when distances equal).
3. The detection result is `(currentX - player.getCentreX() + 0x60) &
   0xFFFF < 0xC0`. Implement as a literal unsigned 16-bit window, not
   as separate "in front" + "in range" Java conditionals.
4. Preserve ROM ordering inside the patrol routine: detection runs
   BEFORE the direction-timer decrement and BEFORE `ObjectMove`. When
   attack triggers, the badnik enters attack state and does NOT move
   on the trigger frame.
5. ROM does NOT update `render_flags` / `x_flip` when transitioning to
   attack; the badnik continues to face whichever direction it was
   patrolling. Don't reset facing on attack entry.
6. Projectile fire direction in `loc_38C22`-style spawn routines uses
   ONLY MainCharacter (not the closest player) to decide left vs right.
   ROM `cmp.w x_pos(a2),d0 ; blo.s + ; neg.w d1` with `a2 = MainCharacter`.
7. Position at spawn for the projectile is `x_pos/y_pos` (no -8 or other
   offset) unless the ROM explicitly adds one.

**ROM citation.** S3K shares this idiom for several patrolling-shooter
badniks. Search `docs/skdisasm/sonic3k.asm` for the
`GetOrientationToPlayer` analogue (same closest-player selection
pattern by `mvabs.w` of horizontal delta) and `addi.w #$60, d2 ; cmpi.w
#$C0, d2 ; blo` gating in the badnik's main routine. S2 original at
`docs/s2disasm/s2.asm:75923-75976` (ObjA5 Spiny) and
`docs/s2disasm/s2.asm:72320-72346` (`Obj_GetOrientationToPlayer`).

**Originating commit (S2).** `<pending>` (trace frontier advancement loop iter
4: S2 CPZ F844 Spiny detection ported with simplified bounding-box +
facing guard instead of ROM closest-player horizontal-only gate). Cross-
applied to S3K here because S3K reuses the same `GetOrientationToPlayer`
pattern in numerous patrolling-shooter badniks. Verify each S3K badnik
implementation against its specific routine before relying on the
pattern.

---

## P13 -- SlopedSolidProvider.getSlopeBaseline() returning halfHeight when ROM slope table encodes absolute offsets

**Symptom.** Player rolling-air-falls toward a sloped platform / bridge /
ICZ snowpile that ROM cleanly lands them on, but the engine fires "no
contact" and lets them fall through. Frontier divergence appears as a y /
y_speed / air mismatch on the exact landing frame: ROM has `y_speed=0`,
`air=0`, snapped y position; engine still has `y_speed = previous +
gravity`, `air=1`, kept falling.

**Root cause.** `SlopedSolidProvider.getSlopeBaseline()` controls
`resolveSlopedContact`'s shift between the raw slope sample and the
effective surface Y:

```
slopeOffset = slopeSample - slopeBase
baseY       = anchorY - slopeOffset
relY        = playerCenterY - baseY + 4 + playerYRadius
```

S3K slope-sampling helpers (`SolidObjCheckSloped`,
`SolidObjCheckSloped2`, `loc_19EB6`-style direct surface compares)
read the slope sample directly: `move.b (a2,d0.w),d3 / ext.w d3 /
move.w y_pos(a0),d0 / sub.w d3,d0`.  There is no baseline subtraction;
the slope table value IS the offset from object_y to the surface.
S3K's slope tables (e.g. `IczBigSnowPile_HeightTable`, AIZ flipping
bridge tables) already encode that convention, so `getSlopeBaseline()`
must return `0` for any S3K slope object whose data is sampled
directly by these helpers.

The `COLLISION_HEIGHT` baseline pattern came from S1's GHZ bridge /
SLZ seesaw slope tables, which encode the surface offset relative to
the object's bottom edge.  S3K slope data does NOT follow that
convention -- positive values lift the surface above object_y,
negative values drop below.

**What to check.** When porting any S3K SlopedSolidProvider:

1. Find the ROM routine that samples the slope and computes the
   surface.  S3K uses `SolidObjCheckSloped` (sonic3k.asm:41982-42015),
   `SolidObjCheckSloped2` (sonic3k.asm:41887-41914), or the
   per-object loc_19EB6-style direct subtraction.
2. Look at the slope data values relative to ROM `y_pos(a0) - d3`:
   - If slope[mid] ~= 0 and the surface visually sits at object_y,
     the table encodes absolute offsets -> `getSlopeBaseline()` returns 0.
   - If slope[mid] ~= halfHeight and the surface visually sits at
     `object_y - halfHeight`, the table is relative to object bottom
     -> `getSlopeBaseline()` returns halfHeight (rare; S1 only).
3. Check existing S3K SlopedSolidProvider impls: `IczBigSnowPileInstance`
   and `AizFlippingBridgeObjectInstance` both return 0 with the
   comment "Height table values are absolute offsets from obj_y" --
   that is the S3K-standard pattern.
4. Confirm via trace replay: compute `surfaceTop = anchorY - rawSlopeSample`
   and `playerBottom = playerY + yRadius + 4`. ROM lands when
   `surfaceTop - playerBottom` is in `(-16, 0]`. The engine's `relY`
   must equal `playerBottom - surfaceTop` for `relY` to land in
   `[0, 16)`. If `slopeBase != 0` shifts the apparent surface by
   halfHeight, the landing window shifts the same amount and the
   player misses.

**ROM citation.** `docs/skdisasm/sonic3k.asm` (SolidObjCheckSloped /
SolidObjCheckSloped2 / per-object loc_19EB6 equivalents -- slope
sample -> surface Y, direct subtraction, no baseline).  Engine
equivalent: `SlopedSolidProvider.getSlopeBaseline()`, `ObjectManager.
resolveSlopedContact` (`baseY = anchorY - slopeOffset`).

**Originating commit.** `<pending>` (cross-game mirror of S2 P13 entry;
the same pattern applies to any S3K SlopedSolidProvider whose slope
table encodes absolute offsets from object_y.  The S2 case fixed was
HTZ1 trace F988 Sonic missing the Seesaw landing because
SeesawObjectInstance returned `COLLISION_HEIGHT` from
`getSlopeBaseline()`, shifting the effective surface 8 px below
ROM's.)

---

## P14 -- Engine edge-triggers ENEMY touch response but ROM polls every frame

**Symptom.** A badnik (or other ENEMY-category object) that should be
destroyed when the player transitions into an attacking state while
already overlapping the badnik stays alive instead. Trace shows ROM
killed the badnik AND applied the canonical Touch_KillEnemy bounce
(typically `y_vel -= $100` for side, `y_vel = -y_vel` for top, `y_vel +=
$100` for upward); engine has the badnik still in the active list and
the player's y_vel/x_vel unchanged. The S2 MCZ trace surfaced this
at frame 825 when Sonic was standing in the Crawlton's bounding box
with `invulnerable_time != 0` (Touch_NoHurt path), then pressed
B+Down to start a Spindash; ROM re-checked anim on f825, saw
Spindash, ran Touch_KillEnemy and set `y_vel = -$100`. The same
pattern applies to S3K badniks whose touch response decisions are
keyed on the current player animation (e.g. Lance / spear-type
badniks that bounce rolling players, multi-sprite bosses whose
`boss_hitcount2` check fires from Touch_Enemy).

**Root cause.** The engine's `ObjectManager.TouchResponses.
processCollisionLoop` historically edge-triggered ENEMY (and SPECIAL)
touch callbacks. ROM has no such gate: the touch loop iterates every
frame and `Touch_Enemy` re-reads `status_secondary(a0)` and
`anim(a0)` each call, so the decision between Touch_KillEnemy and
Touch_ChkHurt is made per-frame. The same applies to S3K where
`Collision_response_list` is pre-built during ExecuteObjects and then
re-walked every frame -- there is no overlap-memory gating.

**What to check.** When implementing an S3K badnik or any object that
uses the `ENEMY` `TouchCategory`:
1. Make `onPlayerAttack` idempotent and self-gating (`isDestroyed()`
   check up front, capture pre-destruction state before mutating).
2. Read the player's current animation/state inside
   `onPlayerAttack`, not from a cached `update()` value. ROM
   Touch_Enemy reads `anim(a0)` each call.
3. S3K bosses with `boss_hitcount2` must accept multiple touches per
   overlap. The continuous polling lets the rolling/spindash player
   land subsequent hits without the engine suppressing the callback.
4. The fix is ENEMY-only. SPECIAL (collision_flags 0x40-0x7F) is
   still edge-triggered to keep monitors / object-controlled
   SolidObjects from firing responses every frame. If an S3K SPECIAL
   object needs every-frame polling, opt-in via
   `TouchResponseProvider.requiresContinuousTouchCallbacks()`.

**ROM citation.** `docs/skdisasm/sonic3k.asm` `Collision_response_list`
walking + `Touch_Enemy` equivalent (S3K mirrors S2 line 84807-84890;
S3K's `loc_1E600` Touch_Enemy variant reads anim per-call). Cross-game
S2 cite: `docs/s2disasm/s2.asm:84502-84548` (`Touch_Loop`),
`s2.asm:84807-84890` (`Touch_Enemy` / `Touch_KillEnemy`). Engine
equivalent: `ObjectManager.TouchResponses.processCollisionLoop`
(`shouldTrigger` decision).

**Originating commit.** `<pending>` (cross-game mirror of S2 P14
entry; ENEMY-continuous polling restored a per-frame Touch_Enemy
re-check that any future S3K badnik with state-dependent kill / hit
behaviour will rely on. Originating S2 trace: MCZ F825 Crawlton with
Sonic transitioning to Spindash during overlap.)

---

## P15 -- Object update() resolves solid contacts BEFORE refreshing slope / collision state

**Symptom.** A sloped solid (seesaw, bridge, tilting platform, flipping
platform) ports the ROM logic but the rider's Y trails ROM by one frame
during a state transition.  Frontier divergence appears the frame the
slope changes shape: ROM has snapped to the new surface; the engine
still uses the previous frame's surface and lags 1-8 px depending on
the slope-table delta.  The state itself transitioned at the right
time -- it just happened AFTER the engine had already finished its
slope sample.

**Root cause.** ROM `Obj_Main` for sloped solids updates the
mapping_frame / slope-table choice BEFORE the SolidObjCheckSloped2 /
loc_19EB6 call.  A naive engine port often inverts this:

```java
public void update(int frame, PlayableEntity player) {
    SolidCheckpointBatch batch = services().solidExecution().resolveSolidNowAll();
    // ... read standing players from batch ...
    int target = calculateTargetAngle();
    updateAngle(target);   // <-- TOO LATE: mapping_frame changes
                            //     after collision has already run
}
```

Because `getSlopeData()` and `isSlopeFlipped()` both key off
`mappingFrame`, sampling them inside `resolveSolidNowAll` returns the
previous frame's surface; the rider's Y lands one transition behind
ROM.  See S2 P15 / cross-game mirror for the full S2 case study
(HTZ Seesaw, mapping_frame 2 -> 1 transition, Sonic 3 px low for one
frame).

**What to check.** When implementing any S3K solid object whose
collision geometry depends on a tickable state byte (mapping_frame,
animation frame, internal angle, depression amount, slope offset
table choice), look at the ROM `Obj_Main` to see where the state
update happens relative to the SolidObjCheckSloped2 / loc_19EB6 /
PlatformObject call:

1. If ROM updates the state *before* the collision call, the engine
   must update its equivalent *before* `resolveSolidNowAll()` /
   `checkpointAll()`.  Compute the target from the PREVIOUS frame's
   standing-player references (kept as instance fields) plus the
   CURRENT player x positions -- ROM does that via per-player
   standing bits on `status(a0)` and reads of `x_pos(a1)` for the
   selected character.
2. The previous-frame standing references are valid because ROM
   itself reads them before the collision call clears / re-sets them.
   In the engine, the latched `standingPlayer1` / `standingPlayer2`
   fields from the end of the prior `update()` give the same view.
3. Watch for sibling helpers that already follow the ROM order:
   `BridgeObjectInstance` (S2 EHZ bridge) calls
   `updateDepressionState()`, `rebuildBridgeShape()`,
   `updateSlopeData()` FIRST and only then runs `checkpointAll()`.
   `Sonic3kCollapsingPlatformObjectInstance` and
   `AizFlippingBridgeObjectInstance` follow analogous patterns and
   are good templates for new S3K sloped solids.
4. The fix is purely a reorder; no new state, no new flags.  Don't
   try to "buffer" the previous-frame slope -- match ROM order and
   the divergence disappears.

**ROM citation.** S2 originating fix: `docs/s2disasm/s2.asm:47037-47115`
(Obj14_Main + Obj14_UpdateMappingAndCollision).  S3K analogues live
in `docs/skdisasm/sonic3k.asm` per-object: AIZ flipping bridge
state-update routine + `loc_19EB6` SolidObjCheckSloped2 call, MGZ
tilting blocks, ICZ segment columns.  Engine equivalent: any S3K
solid object's `update(int, PlayableEntity)` that calls
`services().solidExecution().resolveSolidNowAll()` should perform
slope-shape state updates BEFORE that call, mirroring ROM order.

**Originating commit.** `<pending>` (cross-game mirror of S2 P15
entry; HTZ Seesaw mapping_frame transition surfaced the ordering
issue.  S3K state-driven sloped solids share the same idiom -- ROM
updates state, then calls collision -- and a future S3K trace will
surface the same bug if the engine port reverses the order.)

---

## P17 -- Child object out_of_range uses own X instead of parent anchor, causing chunk-boundary unload

**Symptom.** A parent object's child (e.g. a multi-piece boss arm, attached
segment, swung weapon, fixed-offset projectile spawner) silently vanishes
shortly after the parent appears on screen. The parent stays alive and behaves
normally, but a feature that depends on the child (contact, launch, swing
attack) never fires. Trace replay shows the parent's state advancing
correctly while a hit/launch event that requires the child silently fails.
The engine's `parent.child` reference is non-null and `child.destroyed=false`,
but the child is no longer in `ObjectManager.dynamicObjects`.

**Root cause.** ROM dispatchers for parent+child objects often share one
`Obj_Index` table whose tail uses `move.w objoff_30(a0),d0 / jmpto
JmpTo_MarkObjGone2`. The child's init routine stores the PARENT's `x_pos`
into `objoff_30(a0)` BEFORE applying the `addi.w` offset that moves the
child relative to the parent. So both parent and child use the same camera-
relative chunk reference and unload together.

A naive engine port adds the child via `addDynamicObjectAfterCurrent` and
lets it default to `getOutOfRangeReferenceX() = getX()` (the child's
current position). The default `ObjectManager.isOutOfRangeS1` rounds X to
128-byte chunks. If the child's offset puts it into a different chunk than
the parent, the child can be unloaded as soon as the camera enters the
parent's chunk -- leaving the parent with an orphaned `child` reference and
permanent disablement of the child-dependent feature.

**What to check.** For any parent+child pair where ROM's `Obj_*_Init` does:

```
move.w x_pos(a0),objoff_30(a0)  ; save parent x BEFORE offset
addi.w #$xx,x_pos(a0)            ; apply child offset
```

the child must override `getOutOfRangeReferenceX()` to return the parent's
x position. S3K parent+child idioms to audit:
- Multi-piece boss children spawned by the main boss init
- AIZ battleship attachments (turrets, arms)
- Body segments on Caterkiller-style badniks
- Any projectile that fires from a fixed parent-relative offset and reads
  the parent via `objoff_3C`

The fix is one method:

```java
@Override
public int getOutOfRangeReferenceX() {
    return parentCenterX;  // ROM objoff_30(a0) = parent x_pos
}
```

**ROM citation.** S2 originating bug: `docs/s2disasm/s2.asm:47151`
(`Obj14_Ball_Init`), `s2.asm:46996` (Obj14 dispatcher tail with
`move.w objoff_30(a0),d0 / jmpto JmpTo_MarkObjGone2`), `s2.asm:30040-30057`
(`MarkObjGone2` chunk-rounded comparison). S3K analogue is the standard
multi-piece boss / hazard pattern; cite the specific
`Obj_Init` block when porting an S3K instance. Engine equivalent: any
`AbstractObjectInstance` subclass spawned at a positional offset from a
parent should override `getOutOfRangeReferenceX()` to return the parent's
centre X.

**Originating commit.** `<pending>` (cross-game mirror of S2 P17 entry;
HTZ Seesaw ball alone got unloaded when its chunk-boundary crossed the
camera threshold, while the parent stayed loaded.  S3K parent+child
objects share the ROM idiom and will surface the same trace divergence if
the engine port relies on the default `getX()` reference X.)

---

## P18 -- Shared monitor icon rewards use pre-move velocity tests

**Symptom.** A monitor reward applies one frame too early. The same shared
engine helper is used by S2 and S3K monitors, so a timing fix in the base
routine must be proven against all monitor-content ROM routines.

**Root cause.** The ROM monitor-content routine tests the icon's `y_vel`
before moving it. If the current rise step adds `$18` and lands exactly on
zero, the routine returns; the reward branch runs on the next object update.
A shared engine helper that applies the reward immediately after changing
`iconVelY` from negative to zero is one frame early.

**What to check.** For shared monitor code, verify S1, S2, and S3K before
changing the base routine. S3K has both S&K-side and S3-side monitor-content
code; cite the side that applies to the object being ported. If one game
differs, gate the behaviour at the owning abstraction instead of changing
every game implicitly.

**ROM citation.** S3K `docs/skdisasm/sonic3k.asm:40723-40753`; S3-side
`docs/skdisasm/s3.asm:33392-33421`; S2 analogue
`docs/s2disasm/s2.asm:25618-25631`; S1 analogue
`docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:35-43`.

**Originating commit.** `<pending>` (cross-game mirror of S2 P19 entry;
CNZ speed-shoes monitor reward timing advanced the S2 CNZ frontier from f976
to f1146 after confirming S1, S2, and S3K all use the same pre-move velocity
test).

---

## P19 -- AllocateObject ported as FindNextFreeObj changes same-pass dispatch

**Symptom.** A newly created child runs one frame too early, often exposing a
queue submission, state transition, or collision side effect on the parent's
trigger frame instead of the following frame.

**Root cause.** ROM `AllocateObject`/`FindFreeObj` scans from the lowest dynamic
SST, while `FindNextFreeObj` scans forward from the running owner. Replacing an
`AllocateObject` call with engine `spawnChild()` can place the child after the
parent, where `Process_Sprites` reaches it in the same pass. The ROM may have
chosen an already-visited lower slot and deferred the child's first dispatch.

**What to check.** Match the allocation primitive literally:

1. ROM `AllocateObject` or `FindFreeObj` uses `spawnFreeChild()`.
2. ROM `FindNextFreeObj` uses `spawnChild()` (or `spawnChildAfterSlot()` when
   the ROM temporarily changes the owner pointer before searching).
3. Do not replace the distinction with an unconditional one-frame delay. Slot
   occupancy decides whether a lowest-free child runs now or next pass.
4. When timing matters, guard both the selected slot and whether it has already
   been visited in the current object pass.

**ROM citation.** Upright egg-capsule `sub_868F8` calls `AllocateObject` before
publishing `Obj_LevelResults` (`docs/skdisasm/sonic3k.asm:181978-181990`).
`Obj_LevelResultsInit` immediately queues three KosM archives on its first
dispatch (`sonic3k.asm:62542-62575`). Engine equivalents are
`spawnFreeChild()` and `spawnChild()` in `AbstractObjectInstance`.

**Originating commit.** `fix(s3k): preserve results-owner lowest-free slot`.

---

## P20 -- ROM global publication relayed through an engine-only proxy owner

**Symptom.** A control restore, camera change, or follow-up spawn happens one
or more frames late even though the producer publishes its global flag on the
correct frame. Each extra engine object in the notification chain commonly
adds one object-pass delay.

**Root cause.** ROM consumers read a shared RAM flag directly in their own SST
dispatch. An engine port instead asks an intermediate object to observe the
flag, latch it, and notify the real consumer. Slot ordering then turns a
same-pass producer-to-consumer edge into one or more deferred callbacks.

**What to check.** When a ROM object reads a global (`_unkFAA8`,
`End_of_level_flag`, `Boss_flag`, event bytes, or equivalent):

1. Let the owning engine object consume the semantic shared state directly.
2. Preserve producer/consumer SST ordering; a lower-slot write must be visible
   to a later-slot reader in the same object pass.
3. Keep proxy callbacks only for lifecycle retirement, and make them immediate
   and idempotent. Do not make gameplay timing depend on them.
4. Do not replace the shared predicate with a zone/frame timer or trace value.

**ROM citation.** CNZ end-boss `loc_6E724` reads `_unkFAA8` directly after
`Obj_LevelResultsWait2` clears it, then restores both players in that dispatch
(`docs/skdisasm/sonic3k.asm:146087-146103`). The egg capsule is not part of
that publication edge.

**Originating commit.** `fix(s3k): consume CNZ results publication in boss slot`.

---

## P19 -- HurtCharacter spill may be a next-object-tick effect

**Symptom.** A trace enters hurt on the correct frame but the engine spends
rings one frame earlier than the ROM, or spilled Obj37 rings appear as fully
initialized ring objects on the hit frame instead of after the ROM owner object
runs.

**Root cause.** Some S3K solid hurt paths call `sub_24280`, which rewinds the
player's 16:16 `y_pos` by `y_vel<<8`, swaps the hurt object/player registers,
and calls `HurtCharacter`. `HurtCharacter` allocates an `Obj_Bouncing_Ring`
owner but does not itself clear `Ring_count`; the owner object's init routine
spawns the visible lost rings and zeroes the counter on its next execution.
Directly calling the engine's eager `spawnLostRings(...)` from the hurt contact
spends rings on the same comparison row.

**What to check.** When porting S3K objects that hurt via `sub_24280` or
`HurtCharacter`, confirm whether the ROM path allocates an owner object before
the visible spill. Model both the pre-hurt Y rewind and the delayed ring-spend
ordering; do not special-case the trace frame or zone.

**ROM citation.** `Obj_InvisibleHurtBlockHorizontal` routes contact through
`sub_1F58C` (`docs/skdisasm/sonic3k.asm:43268-43431`), which calls
`sub_24280` (`docs/skdisasm/sonic3k.asm:49200-49220`) before
`HurtCharacter` (`docs/skdisasm/sonic3k.asm:21065-21088`). The S3K
`Obj_Bouncing_Ring` init path reads `Ring_count`, creates spilled rings, and
clears the counter at `docs/skdisasm/sonic3k.asm:35490-35616`.

**Originating commit.** `<pending>` (ICZ hidden hurt block moved the
complete-run trace from f3174 same-frame ring spend to f3273 lost-ring
re-collection after adding delayed spill ordering and the `sub_24280` Y rewind).

---

## P20 -- `SolidObjectFull`/`SolidObject_cont` right edge is inclusive when the ROM reject is `bhi`

**Pattern.** S3K solid helpers that route through the standard
`SolidObjectFull`/`SolidObject_cont` side bounds reject only when the relative X
comparison is strictly higher than the doubled half-width. A contact at
`relX == width*2` is still a ROM contact, so the engine object must opt into an
inclusive right edge when the disassembly shows the `bhi` reject.

**Engine symptom.** Player or sidekick state loses `Status_Push` at the exact
right edge of a solid. The AIZ2 rock case had Sonic at the edge after a
roll-stop animation clear; ROM re-set `Status_Push` through the inclusive
side-contact path, while the engine default exclusive edge let the push bit
stay clear until the object opted into `usesInclusiveRightEdge()`.

**What to check.** When porting an S3K solid:
1. Read the object's helper call and the helper's X reject branch. If it uses
   the standard `bhi` reject, implement `usesInclusiveRightEdge()`.
2. Keep the rule object-local; bespoke collision handlers and objects using a
   different helper may have different edge semantics.
3. Add an exact-edge test where the player's native `x_pos` satisfies
   `relX == width*2`, and assert the player/object push state matches ROM.

**ROM citation.** AIZ/LRZ/EMZ rock uses the standard solid side-contact path;
the S&K-side helper rejects the X bound with `bhi` and then sets `Status_Push`
in the side branch (`docs/skdisasm/sonic3k.asm:41403-41406,41494-41500`).
The S2 Obj42 SteamSpring mirror is documented in the S2 pitfall catalogue.

**Originating commit.** `<pending>` cross-game mirror of the S2 MTZ SteamSpring
inclusive right-edge fix; prior AIZ2 rock fix advanced `TestS3kAizTraceReplay`
from frame 14193 to frame 14299.

**Cross-game back-ref.** Same `bhi`-inclusive-right-edge rule confirmed in S1
`SolidObject` (`docs/s1disasm/_incObj/sub SolidObject.asm:167-168`), commit
`caf70abb7` (FZ boss exact-edge roll-bounce, f837 -> f1724). See
`s1-implement-object/rom-pitfalls.md` P14.

---

## P21 -- Multi-piece objects spawn a real OST slot per part via `AllocateObject`/`AllocateObjectAfterCurrent`, not internal arrays

**Pattern.** A chain / multi-segment / multi-piece object must spawn each part as
a genuine SST-slot object through `AllocateObject` (lowest free slot) or
`AllocateObjectAfterCurrent` (next free after parent), exactly as ROM does.
Modeling the pieces as one object carrying internal position arrays (or reserving
slots without occupying them) under-allocates the dynamic object RAM, shifting
every later `AllocateObject`/streaming slot → cascading slot drift and a
downstream object mis-slotted much later. Count matters: a ROM `dbf d1` loop with
`d1 = N` runs N+1 times (body + fall-through).

**What to check.** Trace the ROM spawn loop; each `AllocateObject*` +
`obID` write is a real child object the engine must spawn as a real
`ObjectInstance` on its own slot (`spawnFreeChild` for `AllocateObject`,
`spawnChild` for `AllocateObjectAfterCurrent` — see P46 in the S2 catalogue for
the allocator mapping). Render-only children still occupy a slot. Parent-recreated
render-only children resolve via `TestRewindCoverageGuard` baseline entries.

**ROM citation.** `docs/skdisasm/sonic3k.asm:37911` (`AllocateObject`, lowest
free) / `:37917` (`AllocateObjectAfterCurrent`, after parent).

**Originating commit (S1 origin).** `9f47f557f` (S1 swinging-platform chain links
spawned as real OST slots; `docs/s1disasm/_incObj/15 Swinging Platforms.asm:67-105`).
See `s1-implement-object/rom-pitfalls.md` P8.

**S3K confirmation.** CNZ Batbot's render-only body and lamp are also real
`CreateChild1_Normal` SSTs. Restoring their after-parent slot occupancy and
independent raw animation advanced the complete-run physics frontier from
f3129 to f4100 (`docs/skdisasm/sonic3k.asm:186195-186407`).

---

## P22 -- Object delete/cancel checks run only in the routines that `bsr` them, not every frame

**Pattern.** A child/projectile delete-or-cancel subroutine (`*_ChkCancel`,
`*_ChkDel`) is reached by ROM only from specific `Obj_routine` jump-table entries,
not from the active/in-flight routine. Calling it from the engine object's
per-frame update unconditionally deletes the object during a phase ROM keeps it
alive → its slot frees at the wrong time → slot drift.

**What to check.** Find every `bsr`/`jsr` to the delete/cancel subroutine and note
which `Obj_routine` values reach it. Gate the engine call to the equivalent
engine routine/state; do not run it from the shared per-frame entry.

**ROM citation.** S3K objects dispatch through `Obj_routine` index tables; a
delete/cancel subroutine belongs only in the routines that `bsr` it (same dispatch
structure as S1/S2). S1 origin:
`docs/s1disasm/_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:162-194,220-249`.

**Originating commit (S1 origin).** `53da8c24a` (S1 Buzz Bomber Missile cancels
only in flare phase, not active — deleted ~840 frames early). See
`s1-implement-object/rom-pitfalls.md` P9.

---

## P23 -- Dormant / consumed objects must not be in `Collision_response_list` until active

**Pattern.** S3K only touch-checks objects that added themselves to
`Collision_response_list` during ExecuteObjects. So an object in a pre-activation
waiting state, or a broken/consumed terminal state, must not be present in the
response list (equivalently, its engine `getCollisionFlags()` returns `0`) until
it activates — otherwise it can hurt the player while dormant, or preempt an
adjacent live object's contact. This is the S3K analog of S1's `obColType ==
col_none` / S2's `collision_flags == 0` skip.

**What to check.** For any object with a waiting state or a consumed terminal
state: trace when ROM adds it to / removes it from the response list (or writes
its `collision_flags`). Gate the engine's `getCollisionFlags()` to `0` outside
the active window, derived from the (monotonic) activation routine — no new
rewind field.

**ROM citation.** `docs/skdisasm/sonic3k.asm` `Collision_response_list` (built
during ExecuteObjects; only added objects are touch-checked). S1 origin:
`Sonic ReactToItem.asm:52-53` (`tst.b obColType / bne`).

**Originating commits (S1 origin).** `466f408a8` (S1 broken monitor clears col
type — *consumed* case) + SYZ Roller dormant case. See
`s1-implement-object/rom-pitfalls.md` P7 and P11.

**Cross-game note.** Off-screen-delete render-bound (S1 P10 / S2 P52: use the ROM
render box / `out_of_range` camera-coarse bound, not a raw `isOnScreenX(<N>)`
margin) is already covered for S3K by P17 (child uses own X vs parent anchor) and
the inline `out_of_range` camera-coarse checks (`docs/skdisasm/sonic3k.asm`
`.enemy_out_of_range` family). Verify each short-lived object deletes on the ROM
camera-coarse bound keyed on its width, not a fixed engine margin.
The same rule applies to behavior gates that read retained `render_flags` bit 7:
CNZ Clamer's frame-8 projectile needs the full `$14x$10` render box, not an
X-only visibility check (`docs/skdisasm/sonic3k.asm:185930-185942`).

---

## P24 -- Object pushes the player: `add.w speed,x_pos(a1)` / `move.w pos,x_pos(a1)` preserve the rider's sub-pixel -- never `setCentreX`/`setCentreY` (they ZERO it)

**Symptom.** A frontier reads as a "sub-pixel RAM-gated" small constant X/Y residual: the engine is byte-exact with ROM until an object first pushes/carries the player (conveyor, fan, moving solid, `MvSonicOnPtfm`-style carry, `SolidObject` side-push), then a CONSTANT fraction behind, crossing an integer boundary 1 frame off.

**Root cause.** S3K object->player position writes operate on the PIXEL word only -- `add.w <speed>,x_pos(a1)` / `sub.w d0,x_pos(a1)` / `move.w <pos>,x_pos(a1)` write the `x_pos`/`y_pos` pixel word and leave the sub-pixel word UNTOUCHED. S3K positions are 16.16 (`x_pos` pixel word at `$10`, sub-pixel word at `$12`; `y_pos` at `$14`/`$16`), so a `.w` write to the pixel word preserves the 16-bit fraction, just like S1/S2. The engine's `setCentreX(short)` / `setCentreY(short)` ZERO the sub-pixel; any object-push path using them discards fraction every frame.

**What to check / fix.** For any S3K object that pushes/carries the player or sets him to a pixel position the ROM writes via `add.w`/`sub.w`/`move.w` to the pixel word: use `player.shiftX/shiftY` (incremental push) or `setCentreXPreserveSubpixel`/`setCentreYPreserveSubpixel` / `setX`/`setY` (set-to-position). Keep `setCentreX`/`setCentreY` ONLY where ROM explicitly clears the fraction (e.g. `move.w #0,x_sub(a1)` or a full 32-bit replace that overwrites the sub word). FAITHFUL-OR-BOUNCE per call site against the object's actual routine in `docs/skdisasm/sonic3k.asm`. (Note: HCZ conveyors and moving platforms are the prime S3K candidates.)

**Also covers object SELF-motion.** Same rule when an object moves ITSELF (`add.w speed,x_pos(a0)` preserves its own 16.16 sub-pixel word): a rideable object that self-moves via a sub-pixel-zeroing centre setter drifts ~1px and surfaces it where the player rides/hits it. Use `SubpixelMotion.moveSprite`/`shiftX`/`shiftY`. (Objects that move in integer pixel steps with no sub-pixel accumulator need no change.) See S1 P15 for the `-Dobjsubpxaudit` method.

**Originating commit (S1 origin).** `b5bc778d4` (S1 Conveyor preserves rider sub-pixel via `shiftX`; SBZ2 f2224 -> f2323). See `s1-implement-object/rom-pitfalls.md` P15 / `s2-implement-object/rom-pitfalls.md` P53.

---

## P25 -- Obj37 post-owner ring allocation is S3K after-current, unlike S2

**Pattern.** S3K `Obj_Bouncing_Ring` keeps the HurtCharacter-created owner slot
for ring 0, then allocates each remaining ring with
`AllocateObjectAfterCurrent`. Do not replace this with S2's plain
`AllocateObject` remainder semantics.

**Engine symptom.** A hurt spill appears with the right count but the post-owner
ring slots fill earlier low holes instead of following the owner/previous ring.
This shifts the dynamic-object countdown phase and can move ring floor probes,
collection windows, or same-frame object execution.

**What to check / fix.**
1. Treat owner preallocation and post-owner allocation as separate ROM facts.
2. For S3K, keep the post-owner chain anchored to the owner/previous Obj37 slot
   via `AllocateObjectAfterCurrent`.
3. Cross-check S2 separately: S2 also preallocates an Obj37 owner, but its
   `Obj37_Init` calls plain `AllocateObject` for the remaining rings.

**ROM citation.** S3K `HurtCharacter` creates the first Obj_Bouncing_Ring owner
(`docs/skdisasm/sonic3k.asm:21065-21088`). `Obj_Bouncing_Ring` then loops with
`AllocateObjectAfterCurrent` for the remaining rings
(`docs/skdisasm/sonic3k.asm:35549-35591`). S2 analog:
`docs/s2disasm/s2.asm:85444-85461,25125-25146`.

**Originating commit (S2 origin).** `d27307e27` S2 ARZ2 Obj37 allocation split:
see `s2-implement-object/rom-pitfalls.md` P79.

---

## P26 -- Obj_WaitOffscreen consumes retained Render_Sprites state

**S3K-specific.**

**Symptom.** A dormant badnik begins its patrol one object dispatch early or
late, so its touch box misses an exact-edge player contact much later even
though its velocity and collision size are correct.

**Root cause.** `Obj_WaitOffscreen` replaces the operation pointer with
`loc_85AD2`. That routine reads render bit 7 left by the preceding
`Render_Sprites` pass, restores the saved pointer on its own dispatch, and
only runs the original object's setup on the following dispatch. Recomputing
visibility inside `update()` collapses the render/restore boundary and can also
sample the pre-scroll camera instead of the render-visible camera.

**What to check.** For every object that calls `Obj_WaitOffscreen`:

1. Keep the placeholder, pointer-restore, and object-setup dispatches separate.
2. Retain visibility from the post-camera render phase; do not infer render bit
   7 from a fresh camera query inside the next object update.
3. Use the placeholder's authored render extent and include any camera motion
   between object execution and the render pass.
4. Keep collision disabled until the setup dispatch writes the object's real
   collision flags.

**ROM citation.** `Obj_WaitOffscreen` and `Render_Sprites` at
`docs/skdisasm/sonic3k.asm:180266-180298,36318-36365`; Jawz caller at
`183518-183570`.

**Originating commit.** `<pending: HCZ milestone 46>`.

**CNZ confirmation.** The retained routine also performs its coarse-X deletion
while still dormant. A never-visible Batbot wrapper otherwise held slot 6 until
the later near Batbot loaded, shifting the complete-run allocation order
(`docs/skdisasm/sonic3k.asm:180279-180300`).

---

---

## P27 -- Animation callbacks own child allocation and routine handoff

**Symptom.** A child object becomes visible or interactive dozens of frames
early even though its parent has entered the apparently correct routine. Later
player movement diverges when the premature child first overlaps or applies a
force.

**Root cause.** The parent routine starts an `Animate_Raw*` script and stores a
callback in `$34`; the callback performs the child allocation and routine
advance only after the animation command/counter completes. Spawning the child
on routine entry collapses the animation-owned boundary. Accelerating scripts
such as `Animate_RawGetFaster` can make this error much larger than one frame.

**What to check.**

1. Read the complete animation data, including delay, loop/end command, and any
   extra counter byte consumed by the animation helper.
2. Keep routine entry, animation completion, callback dispatch, child
   initialization, and first active child dispatch as distinct boundaries.
3. If a consolidated engine object folds the child into its parent, determine
   whether allocation lands above or below the current SST slot. An above-slot
   child can consume routine 0 in the callback's same ExecuteObjects pass, so
   the callback itself may already represent its initialization boundary.
4. Allocate from the callback's native slot/order path, not merely when the
   parent first appears active.
5. When a parent callback mutates its routine, retain any later child-slot work
   from that same ExecuteObjects pass; do not re-gate folded child behavior on
   the parent's post-callback routine.

**ROM citation.** HCZ end-boss turbine `loc_6B1E6` installs
`byte_6BDF4` and callback `loc_6B212`; that callback alone creates the water
column. The column later creates spray child `loc_6B3DE`, whose
`loc_6B3FC` setup returns before `loc_6B410` begins suction
(`docs/skdisasm/sonic3k.asm:141030-141069,141205-141229,142241-142247,
177749-177792`).

**Originating commit.** `<pending: HCZ milestone 51>`.

---

## P28 -- Preserve mutable data registers across sequential P1/P2 helper calls

**Symptom.** Player 1 matches an object force or carry routine while Player 2
moves in the wrong direction or by the wrong amount, despite both calls using
the same helper and apparently identical geometry.

**Root cause.** The parent routine initializes a data register once and invokes
the helper for P1 and P2 without restoring it. If the helper negates,
increments, shifts, or otherwise mutates that register, P2 consumes the value
left by P1. Recomputing an absolute result independently per player changes the
native behavior.

**What to check.** Trace every input/output register across the full caller,
not only inside the helper. Port native player order and thread every mutated
value through eligibility early returns as well as the active branch.

**ROM citation.** HCZ `sub_6B9AC` initializes `d2=+$20000` once, then calls
`sub_6B9C8` for P1 and P2. A player right of the column negates `d2`, so the
second player's direction depends on the first call. Its sibling `sub_6B9E2`
likewise shares `a1`; P1 carry consumes `(a1)+`, shifting P2's grab-zone table
view by one word (`docs/skdisasm/sonic3k.asm:141757-141881,141925-141930`).

**Originating commit.** `<pending: HCZ milestone 53>`.

---

## P29 -- Touch-list coordinates follow the add-to-list call site

**Symptom.** A moving hazard hurts one frame early or late at an exact vertical
or horizontal edge even though its final rendered position matches ROM.

**Root cause.** Collision-response membership and coordinate timing are
separate facts. A routine can move or refresh a child immediately before
`Add_SpriteToCollisionResponseList`; that entry must expose the refreshed live
coordinate, while objects that add before movement retain their earlier sample.

**What to check.** Read the entire operation tail and locate movement/child
refresh relative to the exact list-add helper. Opt the specific object into
current touch state only when the ROM adds after movement; do not globally
change previous-list snapshot semantics.

**ROM citation.** HCZ turbine `loc_6B1A8` dispatches its routine, calls
`Refresh_ChildPosition`, then tail-calls
`Child_DrawTouch_Sprite2_FlickerMove`, which adds the refreshed child
(`docs/skdisasm/sonic3k.asm:141019-141033,178139-178153`). CNZ Batbot likewise
runs `Chase_Object` and `MoveSprite2` before its draw/touch tail
(`docs/skdisasm/sonic3k.asm:186312-186319,20656-20710`).

**Originating commits.** `<pending: HCZ milestone 56>`; CNZ f2920 Batbot live
touch-coordinate milestone.

---

## P30 -- Controller persistence must follow the ROM's active-state gate

**Symptom.** A distant controller no longer affects players, yet its SST slot
remains occupied for the rest of the act. A much later boss child or lost-ring
spill then allocates into a different slot and observes a different object-loop
countdown phase.

**Root cause.** Invisible controller objects are often persistent only while a
player-owned phase/capture byte is nonzero. If the idle tail explicitly calls
`Delete_Sprite_If_Not_In_Range`, unconditional engine persistence converts a
temporary capture safeguard into a permanent slot leak.

**What to check.** Trace every per-player state byte tested immediately before
the range-delete helper. Make persistence depend on those active states, and
let ordinary off-screen deletion clear placement-loaded state so the controller
can respawn if the camera returns.

**ROM citation.** HCZ twisting loop `loc_3909C` tests both player phase bytes
and calls `Delete_Sprite_If_Not_In_Range` only when both are zero
(`docs/skdisasm/sonic3k.asm:76482-76505,37262-37277`).

**Originating commit.** `<pending: HCZ milestone 57>`.

---

## P31 -- Routine handoff does not imply immediate collision disable

**Symptom.** A spinning or animated hazard becomes harmless as soon as its
parent clears an activation flag, while ROM still permits one or more contacts
during the visible slowdown/retraction animation.

**Root cause.** The routine handoff installs an `Animate_Raw*` script but does
not write `collision_flags`. Collision remains owned by the later animation
callback, so clearing it on routine entry shortens the native touch lifetime.

**What to check.** Track the collision byte separately from the routine and
animation speed. Locate the exact instruction that clears it and preserve the
old value through every intermediate animation dispatch.

**ROM citation.** HCZ end-boss turbine `loc_6B244` selects routine 8 and
`byte_6BE01`; `loc_6B262` alone clears collision after
`Animate_RawGetSlower` completes (`docs/skdisasm/sonic3k.asm:141084-141106,
142249-142257,177749-177792`).

**Originating commit.** `<pending: HCZ milestone 59>`.

---

## P32 -- Operation-pointer handoff can transfer reference ownership

**Symptom.** Rewind capture reports a stale reference after a former parent
unloads, or an independently launched child disappears early because it still
uses the generic lifetime of its attached phase. A downstream effect can also
outlive the launched child it still reads.

**Root cause.** A child can read `parent3` while attached, then replace its
operation pointer with a launched routine that never reads the former parent
again. Retaining that Java reference invents an ownership edge after the ROM
handoff. Conversely, a separately allocated trail that still reads the
launched child's status remains owned by that child until its native delete
marker becomes visible.

**What to check.** Trace every parent-pointer read before and after the
operation-pointer write. Sever both directions only when the new operation no
longer consumes the old parent; keep downstream children attached to the
object whose state they still read. Reproduce the exact
`Sprite_CheckDeleteTouchXY` bounds and one-entry delete-marker timing, and opt
only the independent operation out of generic culling. Rewind recreation must
use the captured `RewindObjectContext.spawn()` metadata rather than probe
constructor defaults.

**ROM citation.** Turbo Spiker's attached shell reads `parent3`; `loc_87D72`
installs independent `loc_87DA4`, while the separately allocated trail at
`loc_87DC0` continues reading the shell's status bit 7
(`docs/skdisasm/sonic3k.asm:184034-184083`). The range helper installs the
delete operation and sets that marker before the slot is freed on its next
entry (`docs/skdisasm/sonic3k.asm:179032-179047,179136-179139`).

**Originating commit.** `<pending: Turbo Spiker HCZ closure repair>`.

---

## P33 -- Standing-bit consumers can run before the solid helper

**Symptom.** A launcher, switch, or platform reacts on the exact landing frame,
anchoring or accelerating the player one frame before the ROM.

**Root cause.** The object routine tests its existing p1/p2 standing bits before
calling `SolidObjectTop` or `SolidObjectFull`. A compatibility callback after the
engine resolves contact observes the newly established bit instead. Use a manual
solid checkpoint: consume `previousStanding` first, then resolve the helper.

**What to check.** Preserve the instruction order around every standing-bit test
and solid-helper call. Do not treat an object-local standing test as a post-contact
listener unless the disassembly actually calls the helper first.

**ROM citation.** FBZ/DEZ player launcher `loc_3B97A` calls `sub_3B9D8` for both
players before `SolidObjectTop` (`docs/skdisasm/sonic3k.asm:79415-79427`).

**Originating commit.** `<pending: FBZ complete-run trace f202>`.

---

## P34 -- Byte access to a word field selects a big-endian half

**Symptom.** A word accumulator reverses, clamps, or changes phase far too early,
although its word-sized integration and final position formula look correct.

**Root cause.** A 68k byte instruction at the word field's base address reads the
word's high byte. Java `value & 0xFF` reads the low byte instead. For a word at
`$32(a0)`, `cmp.b $32(a0),d2` compares `(value >>> 8) & 0xFF`.

**What to check.** Record both the operand size and the exact SST address for every
mixed byte/word access. Model 16-bit wrapping separately from selecting the high or
low byte; never infer the byte half from the Java variable name.

**ROM citation.** FBZ floating-platform drop routine integrates word `$32(a0)` but
compares byte `$32(a0)` at `loc_3A6D0` (`docs/skdisasm/sonic3k.asm:78267-78316`).

**Originating commit.** `<pending: FBZ complete-run trace f681>`.

---

## P35 -- Predictive terrain helpers probe the next centre, not a sprite edge

**Symptom.** A walking badnik turns at a nonexistent ledge, patrols in the wrong
direction, and misses a later player collision even though its velocity and timers
match the disassembly.

**Root cause.** `ObjHitFloor2_DoRoutine` predicts one additional X integration
(`x_pos + x_vel << 8`) and passes that centre X to `ObjCheckFloorDist2`. Replacing
it with `currentX +/- width_pixels` changes both the coordinate and the cadence.

**What to check.** Follow the exact helper body before choosing a generic patrol
probe. Preserve its fixed-point prediction, centre/edge semantics, y-radius use,
and signed acceptance band (`-1 <= distance < $C`).

**ROM citation.** `ObjHitFloor2_DoRoutine` and `ObjCheckFloorDist2`
(`docs/skdisasm/sonic3k.asm:177986-178007,20054-20068`); FBZ Blaster calls it at
`loc_89512` (`docs/skdisasm/sonic3k.asm:186458-186472`).

**Originating commit.** `<pending: FBZ complete-run trace f868>`.

---

## P36 -- Directional grab endpoints can exclude a capture cell

**Symptom.** A player releases from a horizontal grab at the correct endpoint,
then is re-captured on the next frame even though the ROM remains free.

**Root cause.** The same subtype/orientation bit that selects the endpoint can
also exclude one quantized capture cell. Porting only the endpoint release test
leaves the generic capture box active at that boundary. The ROM aligns the
relative coordinate to a $20-pixel cell before testing the one-sided exclusion.

**What to check.** Trace both the release branch and the subsequent capture
routine. Preserve signed relative-coordinate wrapping, the exact
`(delta + range) & ~$1F` quantization, and which outer cell is rejected for
each orientation. Keep this separate from any generic post-release cooldown;
the exclusion is positional and remains valid when the cooldown is zero.

**ROM citation.** FBZ horizontal chain capture `loc_3ACEA` quantizes the
relative X coordinate and rejects the leftmost or rightmost cell according to
subtype bit 6 / status bit 0
(`docs/skdisasm/sonic3k.asm:78783-78818`).

**Originating commit.** `<pending: FBZ complete-run trace f1658>`.

---

## P37 -- A prior collision list can still expose live object coordinates

**Symptom.** A moving object is present in the expected collision-response
list, but a player misses it by one movement step at an exact hitbox edge.

**Root cause.** S3K's `Collision_response_list` preserves prior-pass
membership as SST pointers, not copied position records. When the player slot
runs before the next object pass, dereferencing the pointer reads the object's
live frame-start `x_pos` / `y_pos`. Pairing the prior membership with an
older engine pre-update snapshot makes coordinates two object passes stale.

**What to check.** Separate list membership timing from pointed-to state timing.
For pointer lists, preserve which objects were published by the prior pass but
read fields from the live object at the consuming slot. Use a retained
pre-update position only for pipelines whose list/snapshot contract actually
copies coordinates. Include inclusive-edge tests because a one-step error can
otherwise remain invisible.

**ROM citation.** `Touch_Process` reads an SST pointer from
`Collision_response_list`, then reads `x_pos(a1)` and `y_pos(a1)` live
(`docs/skdisasm/sonic3k.asm:20661-20717`). FBZ complete-run f2038 exposes
the distinction with a TechnoSqueek moving from $04CA to $04C8 at the
Insta-Shield's inclusive 48x48 edge.

**Originating commit.** `<pending: FBZ complete-run trace f2038>`.

---

## P38 -- Object update counters are not automatically `Level_frame_counter`

**Symptom.** A globally phased moving object is consistently one step ahead or
behind the ROM, and a player receives an otherwise-correct landing or ride-seat
offset from that displaced surface.

**Root cause.** `ObjectManager` passes its free-running VBlank-style execution
counter to `ObjectInstance.update(...)`. A disassembly routine that explicitly
reads `Level_frame_counter` must instead use the gameplay counter exposed by the
injected level manager. Treating both clocks as interchangeable can survive
unit tests that call `update(frame, ...)` directly, then drift after replay
bootstrap, title-card preludes, lag frames, or seamless transitions.

**What to check.** Identify the exact ROM counter symbol and whether the object
runs before the engine's late-frame gameplay-counter increment. For S3K
`Process_Sprites`, `services().levelManager().getFrameCounter() + 1` is the
currently visible `Level_frame_counter`; keep the update argument only as an
isolated-test fallback. Do not introduce a zone, route, or trace-frame phase
offset. Add a test whose update argument deliberately differs from the injected
gameplay counter.

**ROM citation.** FBZ floating platform mode 3 reads
`(Level_frame_counter+1).w` at `loc_3A664`
(`docs/skdisasm/sonic3k.asm:78229-78243`). FBZ complete-run f2641 shows the
engine platform at Y `$0AA6` while its retained prior position and the ROM's
current position are `$0AA7`; both solid routines apply the correct `$2D`
landing-seat delta.

**Originating commit.** `<pending: FBZ complete-run trace f2641>`.

---

## P39 -- `Oscillating_table` addresses include a two-byte control word

**Symptom.** An oscillating object jumps between extreme positions, misses an
otherwise-valid ride contact, or appears to read `$00`/`$FF` where the ROM
uses a smoothly changing oscillator value.

**Root cause.** S3K labels the complete structure `Oscillating_table`: a
two-byte control word followed by interleaved oscillator values and deltas.
The engine's `OscillationManager.getByte(...)` and `getWord(...)` address only
the data payload after that control word. Passing a raw disassembly address
such as `Oscillating_table+$0A` therefore reads payload offset `$0A` instead of
the correct `$08`, commonly selecting a delta high byte rather than the value
high byte.

**What to check.** Establish whether the abstraction accepts an address into
the complete ROM table or into its payload. For `OscillationManager`, subtract
two from an `Oscillating_table+offset` expression. Use a test snapshot whose
value and delta high bytes are intentionally different; default oscillator
data can hide the error when adjacent bytes happen to agree. Prefer named
constants documenting both the ROM expression and the translated payload
offset.

**ROM citation.** FBZ floating platform modes 1 and 2 read
`Oscillating_table+$0A` and `Oscillating_table+$1E` at `loc_3A63A` and
`loc_3A646` (`docs/skdisasm/sonic3k.asm:78211-78228`). The engine payload
offsets are `$08` and `$1C`. FBZ complete-run f2795 exposed the incorrect raw
offsets as a subtype `$10` platform alternating between Y `$0A80` and `$0B7F`
instead of presenting the ROM landing surface near `$0A9D`.

**Originating commit.** `<pending: FBZ complete-run trace f2795>`.

---

## P40 -- Object-controlled frames still advance global controller edges

**Symptom.** A player correctly escapes an object by pressing jump, but on the
first free frame an elemental-shield move, flight, glide, or another
second-press action fires even though the button was only held.

**Root cause.** The ROM's V-int controller update derives `Ctrl_1_Press` from
the global held-state transition every frame. `object_control` can skip the
player movement routine, but it does not pause that controller edge detector.
An engine-local movement edge latch that returns before observing input during
object control remains stale; when movement resumes, the still-held button is
mistaken for a new press.

**What to check.** Separate controller edge generation from whether normal
player physics runs. On every object-controlled movement-suppression frame,
advance the held-state latch used to derive the next press edge. Do not replay
or synthesize a press when the button remains held after the object consumes
it. Test a press on the final controlled frame followed by the same held input
on the first uncontrolled frame, using a visible second-press ability so the
failure cannot hide as an internal boolean.

**ROM citation.** FBZ horizontal chain Obj72 tests the A/B/C press bits in
`Ctrl_1_logical` and releases the player in `sub_3AA7E`
(`docs/skdisasm/sonic3k.asm:78513-78568`). In the complete run, f3061 has
`Ctrl_1_logical=$1810` and performs the chain jump; f3062 has `$1800`, so the
held B is not a second press. The stale engine edge instead invoked Fire Shield
dash and wrote `x_vel=ground_vel=$0800`, `y_vel=0`.

**Originating commit.** `<pending: FBZ complete-run trace f3062>`.

---


## P41 -- Moving solids need a stable live-SST latch owner

**Symptom.** A jump from a moving platform receives S3K's one-pixel
upward-contact lift, or a continuing rider loses standing/pushing ownership,
even though the previous frame ended on the same object instance.

**Root cause.** ROM standing and pushing bits live in the object's SST status
byte, so moving the object does not change their owner. In the engine,
`AbstractObjectInstance.updateDynamicSpawn(...)` replaces `getSpawn()` with
an immutable `ObjectSpawn` containing the new coordinates. The generic solid
latch normally uses that spawn value as its key. A moving object that does not
opt into an instance-scoped key therefore publishes each coordinate as a new
latch owner; the next `SolidObjectFull` pass cannot observe the prior standing
bit and can fall through into new-contact classification.

**What to check.** Any solid that calls `updateDynamicSpawn` while it can carry
or push a player should declare `usesInstanceSolidStateLatchKey() == true`.
This is a provider/profile decision, not a zone or trace-frame branch. Add a
contract test alongside the object's movement-profile tests. Do not globally
replace spawn keys with instance keys: placed-object latches intentionally use
stable spawn identity across unload/reload, while a live moving SST slot owns
its transient status locally.

**ROM citation.** FBZ Obj71 updates `x_pos`/`y_pos` through its movement
callbacks and then calls `SolidObjectFull_Offset`
(`docs/skdisasm/sonic3k.asm:78164-78299`); its standing bits remain in that
same object's `status(a0)`. At complete-run f3199, the subtype `$30` platform
moved from X `$0BBB` to `$0BBD`. Losing the old coordinate-keyed standing
bit let `SolidObject_cont -> loc_1E154` apply `subq.w #1,y_pos` to Sonic's
otherwise exact jump. Instance-scoped ownership advanced the strict frontier
to f3222.

The same rule applies independently to every real child/member SST slot. FBZ
Obj77 rotating-platform members update their circular `x_pos`/`y_pos` and call
`SolidObjectFull` from `loc_3B86A`/`loc_3B8C2`
(`docs/skdisasm/sonic3k.asm:79333-79388`). At complete-run f15331 the special
member's native P2-pushing bit clears in its unchanged slot status byte
(`$C0->$80`), and `sub_1E0C2` clears Tails' `Status_Push`. A coordinate-keyed
engine latch instead remained under the member's prior dynamic spawn after it
moved from Y `$0186` to `$0184`. Instance-scoped ownership lets the same live
member clear its old push bit without conflating sibling members.

**Originating commits.** `<pending: FBZ complete-run traces f3199 and f15331>`.

---

## P42 -- SolidObject_cont's unsigned BHI keeps the exact right edge solid

**Symptom.** A player at the precise right-hand boundary of a platform or trap
fails to stand or push for one frame, while positions one pixel farther inward
behave correctly.

**Root cause.** The shared ROM routine compares the unsigned horizontal
distance and rejects it with `bhi`, not `bhs`. Equality therefore remains
inside the solid span. Expressing the test as a conventional half-open range
silently excludes the exact boundary.

**What to check.** Read the branch mnemonic at the shared solid callsite and
encode its equality semantics in the object's `SolidRoutineProfile`. Add a
test at exactly `d1 * 2`, not only at interior and exterior coordinates.

**ROM citation.** FBZ ObjE4 passes `d1=$1B` to `SolidObjectFull`; the shared
`SolidObject_cont` rejects only with unsigned `bhi`
(`docs/skdisasm/sonic3k.asm:41399-41407`). Complete-run f3637 exposed the
incorrect half-open right edge.

**Originating commit.** `<pending: FBZ complete-run trace f3637>`.

---

## P43 -- Manual RideObject_SetRide must perform the complete native reset

**Symptom.** A player lands on a manually controlled carrier at the correct
position but retains stale airborne velocity, status, or flip state, causing a
later transfer or launch to diverge.

**Root cause.** `RideObject_SetRide` is a state transition, not merely a
standing-bit assignment. A hand-written carrier path that sets ownership while
omitting the routine's velocity and movement-state resets leaves state that the
ROM clears atomically.

**What to check.** When an object reproduces `RideObject_SetRide` outside the
shared solid path, mirror every native write before publishing the ride
relationship. Test the player's velocities and status immediately after
landing and through a transfer to another carrier. If `Status_OnObj` was set,
clear the previous `interact` object's standing bit and the engine's scoped
ride record before installing the new carrier; clearing only same-family
participant tables leaves ordinary solids live.

**ROM citation.** FBZ wire-cage handling reaches `RideObject_SetRide` from
`sub_39F7E` after its custom surface check
(`docs/skdisasm/sonic3k.asm:77203-77241`). Complete-run f3887 retained stale
state until the full reset was mirrored.

**Originating commit.** `<pending: FBZ complete-run trace f3887>`.

---

## P44 -- Unsigned two-branch landing ranges can deliberately exclude zero

**Symptom.** A player lands one frame early when their feet are exactly flush
with a moving carrier's surface.

**Root cause.** Translating a pair of unsigned 68k branches into an intuitive
inclusive range can change its endpoints. In FBZ's cage routine, `bhi`
rejects positive gaps and the following unsigned `blo` also rejects zero.
The accepted band is `-$10..-$01`, not `-$10..$00`.

**What to check.** Evaluate each comparison in 16-bit unsigned ordering and
write boundary tests for `-$11`, `-$10`, `-$01`, `$00`, and `$01`.
Do not infer inclusivity from the geometric meaning of the values.

**ROM citation.** `sub_39F7E` performs `bhi` followed by
`cmpi.w #-$10,d0; blo` before `RideObject_SetRide`
(`docs/skdisasm/sonic3k.asm:77203-77241`). A BizHawk state probe at
complete-run f3936 confirmed that an exact zero gap is rejected until f3937.

**Originating commit.** `<pending: FBZ complete-run trace f3936>`.

---

## P45 -- S3K rideables must publish the native Tails_CPU_interact pointer bank

**Symptom.** Sonic remains exact while CPU Tails later diverges around an
object that Tails previously rode, often long after the object has left the
screen.

**Root cause.** S3K stores the high word of the interacting object's code
pointer in `Tails_CPU_interact`. The value can persist beyond the contact and
affect the CPU routine. A rideable object without
`RomObjectCodePointerProvider` leaves the engine bank word at zero even when
all visible solid behavior is correct.

**What to check.** For objects that can become Tails' native interaction
target, implement `RomObjectCodePointerProvider` with the disassembly code
bank and test it on every counted subtype/family. Preserve the latch according
to native lifetime; do not clear it merely because contact ended. Separate
active standing/control cleanup from persistent `interact`: a dismount routine
that never writes `interact(a0)` must retain the last object identity and slot.

**ROM citation.** FBZ Obj71 executes at `$0003A5DA`, and the complete-run ROM
latched `Tails_CPU_interact=$0003` at f1053 and retained it through f3990.
The floating platform and both wire-cage families therefore publish bank
`$0003`.

**Originating commit.** `<pending: FBZ complete-run trace f3990>`.

---

## P46 -- Object cadence may read Level_frame_counter+1, not the update clock

**Symptom.** Periodic child objects are consistently phase-shifted even though
their interval and movement math are correct, producing hazards at positions
that never exist in the ROM.

**Root cause.** The object gates allocation on the low byte at
`(Level_frame_counter+1).w`. A manager-supplied update counter is a different
clock and can be one or more phases away during trace playback.

**What to check.** Preserve the exact counter address and byte selection from
the disassembly. When an engine API exposes the stored level counter, apply the
native `+1` explicitly and mask to a byte before cadence tests. Use a unit
test where the manager clock is divisible by the interval but the native
counter byte is not.

**ROM citation.** FBZ ObjE4 reads `(Level_frame_counter+1).w` at
`loc_3CD4C` and `loc_3CDD0` before its four-frame flame allocations
(`docs/skdisasm/sonic3k.asm:80708-80772`). FBZ Obj7F independently reads the
same native clock at `loc_3C534` before arming a ballistic-projectile burst
(`docs/skdisasm/sonic3k.asm:80160-80174`); using ObjectManager's VBla clock
removes the `$9E` projectile that hurts Tails at complete-run f15235. Focused
regressions deliberately make the object-update and native clock gates
disagree, proving that only the native byte may allocate children.

**Originating commit.** `<pending: FBZ exact-cadence regression>`.

---

## P47 -- GetSineCosine callers may deliberately consume d1 cosine

**Symptom.** A rotating or lateral child follows a plausible orbit, but its
horizontal offset and velocity are near zero at the exact frame where the ROM
places it near the orbit's horizontal extreme.

**Root cause.** `GetSineCosine` returns sine in `d0` and cosine in `d1`.
Treating the helper as a scalar sine lookup ignores which output register the
caller subsequently moves or scales. The resulting motion can look coherent
while being phase-shifted by a quarter turn.

**What to check.** Follow data flow from both output registers after every
trigonometric helper call. Test a discriminating angle where sine and cosine
differ substantially, including the spawned coordinate after its first native
movement update.

**ROM citation.** FBZ ObjE4's `sub_3CEC0` and `loc_3CF4C` consume `d1` after
`GetSineCosine` (`docs/skdisasm/sonic3k.asm:80856-80911`). At complete-run
f4262, angles `$7C/$FC` therefore produce the ROM child X coordinates
`$097C/$09A2`; using sine instead produced a near-centre flame and the f4276
Tails damage divergence.

**Originating commit.** `<pending: FBZ complete-run trace f4276>`.

---

## P48 -- Keep native width_pixels separate from expanded solid-call widths

**Symptom.** Standing collision is correct, but the player faces the wrong
direction or enters a balancing animation at the wrong point on an object.

**Root cause.** An object may widen the `d1` argument passed to
`SolidObjectFull` without changing its stored `width_pixels`. Shared player
logic such as `Sonic_Balance` reads the stored object field, so reusing the
expanded collision span changes later behavior.

**What to check.** Track the source of every width independently: mapping
dimensions, `width_pixels`, render bounds, and each solid-routine argument.
Expose separate engine values when the disassembly does. Add a regression at
a coordinate that changes the sign of the balance calculation between the two
widths.

**ROM citation.** FBZ Obj78 loads `width_pixels` from `byte_3B6D8`, while
`loc_3B718` adds `$B` only to the `SolidObjectFull` width
(`docs/skdisasm/sonic3k.asm:79240-79272`). At complete-run f4545, subtype 2
must use native width `$18`; the expanded width incorrectly flipped Sonic's
facing bit.

**Originating commit.** `<pending: FBZ complete-run trace f4545>`.

---

## P49 -- render_flags bit 7 gates use the render box, not the object centre

**Symptom.** An on-screen-gated hazard or sound has the correct cadence but is
consistently phase-shifted as the camera approaches it from an edge.

**Root cause.** The disassembly tests `render_flags` bit 7, which
`Render_Sprites` derives from the object's `width_pixels` and
`height_pixels` extents. A centre-point viewport test becomes true later on
entry and false earlier on exit.

**What to check.** When a routine tests the sign of `render_flags`, use the
engine's ROM-style render-box predicate with the object's native extents and
the correct previous-render-pass timing. Add a boundary test with the centre
outside the viewport but the sprite box overlapping it.

**ROM citation.** FBZ ObjE4 tests `render_flags(a0)` before allocating flames
at `loc_3CD6E` and `loc_3CDEC`, after setting `width_pixels=$10`
(`docs/skdisasm/sonic3k.asm:80654-80772`). A centre-point check delayed the
complete-run flame phase by 16 pixels/frames and caused the f5882 sidekick
hurt divergence.

**Originating commit.** `<pending: FBZ complete-run trace f5882>`.

---

## P50 -- only the ridden solid may consume an airborne ride latch

**Symptom.** A jumping player is pulled down by one pixel or freshly lands on
a moving platform after an unrelated, earlier SST solid slot runs.

**Root cause.** `SolidObjectFull` processes objects in slot order, but the
standing ownership belongs to one specific object. Letting any solid clear the
player's airborne ride record consumes the ridden object's native standing
latch before its own routine executes.

**What to check.** Couple airborne unseat logic to identity equality between
the executing solid and the recorded riding object. Regress with two solids in
slot order: an unrelated earlier solid followed by the actual ridden platform.

**ROM citation.** S3K `SolidObjectFull_1P` clears `Status_OnObj` and the
object's standing bit only while executing that object's own state
(`docs/skdisasm/sonic3k.asm:41017-41035`). The complete-run FBZ snake-platform
case at f5857 exposed the folded-engine ordering bug.

**Originating commit.** `<pending: FBZ complete-run trace f5857>`.

---

## P51 -- S3K SolidObjectFull accepts its exact right X boundary

**Symptom.** A player is separated to the correct solid edge and has zero
velocity, but loses the pushing bit on the next frame while remaining at that
same coordinate.

**Root cause.** S3K `SolidObjectFull` rejects horizontal range with unsigned
`BHI`, so `relX == d1*2` is still inside. A shared profile with an exclusive
right edge resolves the inbound frame but reports no contact once equality is
reached.

**What to check.** For every direct S3K `SolidObjectFull` caller, preserve its
inclusive right edge independently of the object's stored render width. Regress
the provider profile and a stationary grounded contact at exact equality.

**ROM citation.** FBZ Egg Prison `sub_89D9C` passes `d1=$2B` directly to
`SolidObjectFull` (`docs/skdisasm/sonic3k.asm:187185-187191`). At complete-run
f5916, Sonic reaches `x_pos = prison_x + $2B`; ROM retains both P1 pushing
bits while an exclusive engine profile cleared them.

**Originating commit.** `<pending: FBZ complete-run trace f5917>`.

---

## P52 -- Do not carry S2's approximate render height into S3K

**S3K-specific:**

**Symptom.** A moving object that should fall just below the screen remains
collision-active, bounces on terrain outside the visible area, and later
returns to affect gameplay.

**Root cause.** S2 `BuildSprites` uses a 32-pixel approximate vertical band
when `render_flags` bit 4 is clear. S3K `Render_Sprites` instead always reads
the object's `height_pixels`. Reusing S2's margin in S3K keeps
`render_flags` bit 7 set below the native bottom edge.

**What to check.** Identify which game's sprite renderer latches the on-screen
bit and which object field or fallback it reads. Put the difference in a typed
per-game rule. Regress the exact bottom-edge coordinate and consume the
latched bit on the following object update.

**ROM citation.** S3K Obj_Bouncing_Ring initializes radii and
`width_pixels=8` but leaves cleared `height_pixels=0`, and its floor probe is
gated by `render_flags` (`docs/skdisasm/sonic3k.asm:35579-35591,
35624-35650`). S3K `Render_Sprites` reads `height_pixels` directly
(`sonic3k.asm:36337-36370`), unlike S2's 32-pixel approximate path
(`docs/s2disasm/s2.asm:30560-30611`). At FBZ complete-run f7333, the extra
margin let one spilled ring bounce back and be collected.

**Originating commit.** `<pending: FBZ complete-run trace f7333>`.

---

## P53 -- Allocate ScreenInit-owned objects before initial placement

**S3K-specific:**

**Symptom.** Fresh zone load assigns every placed object one SST slot earlier
than the ROM, so later slot-sensitive interactions and persistent object
identities diverge even though every placed object exists.

**Root cause.** Some S3K zone `ScreenInit` routines allocate a persistent
event-owned object before `Load_Sprites` performs its initial placement pass.
Creating the controller after placement changes native allocation order.
Trace replay or manager reset can expose a second bug by deleting that owner
without letting the event runtime reconcile it.

**What to check.** Audit the zone's `ScreenInit` and event-entry routines for
`Create_New_Sprite` calls. When present, defer initial object placement until
the event runtime has created or adopted the owner. Make reset reconciliation
an event-provider lifecycle hook driven by zone state, not by trace route or
frame. Regress fresh load, act transition, and placement-manager reset: the
owner must retain one identity and the native slot without duplication.

**ROM citation.** FBZ `ScreenInit_FBZ` creates `Obj_FBZOutdoorBGMotion`
before its initial sprite placement. Native FBZ1 therefore has the motion
controller in SST slot 4; allocating it after placement shifted the engine to
slots 6/7 and moved the complete-run lost-ring allocation at f7340.

**Originating commit.** `<pending: FBZ complete-run trace f7340>`.

---

## P54 -- render_flags consumers observe the previous render pass

**Symptom.** An on-screen-gated countdown starts one frame or several pixels
too early even after its width and height extents match the ROM.

**Root cause.** Object update reads the `render_flags` value latched by the
previous `Render_Sprites` pass. Replacing it with a fresh, expanded
`isOnScreen` query changes both the native bounds and the phase at which the
countdown becomes active.

**What to check.** Trace where the disassembly last wrote `render_flags` and
which render pass produced bit 7. Reproduce the exact render box, including
right/bottom exclusivity and native `width_pixels`/`height_pixels`, and preserve
previous-pass timing. Add boundary tests immediately outside each edge.

**ROM citation.** FBZ wall-missile launcher `loc_3C828` gates its countdown on
the prior `render_flags` value produced with width `$10` and height `4`. The
engine's `$20`-margin query began the countdown 17 frames early, causing the
sidekick to be hit at complete-run f7409.

**Originating commit.** `<pending: FBZ complete-run trace f7409>`.

---

## P55 -- Direction and render flip bits are separate native state

**Symptom.** A captured player is placed on the opposite side of a rotating
object for one frame even though the engine has already set the intended
logical direction.

**Root cause.** ROM code may clear `render_flags` flip bits directly before a
position helper runs. In the engine, changing the direction field does not
necessarily clear cached horizontal/vertical render flips, and that stale
render state can negate a freshly computed offset.

**What to check.** Follow every native write to `render_flags` bits 0-1
independently of status-facing and logical direction. If the routine masks or
clears those bits, mirror the cached render-flip write before the same-frame
position calculation. Regress capture while entering with the opposite facing
and render flip.

**ROM citation.** FBZ spinning pole `loc_3C0DC` clears `render_flags` bits 0-1
before placing the player at angle `$E0`. Retaining the engine's cached
horizontal flip changed the native `pole_x + $A` placement to `pole_x - $A`
at complete-run f7849.

**Originating commit.** `<pending: FBZ complete-run trace f7849>`.

---

## P56 -- Preserve routine-pointer wait states that stop shared movement

**Symptom.** A moving object reaches the correct surface once, then shifts by
one pixel on a later frame even though its gravity, radii, and floor-distance
math all match the disassembly.

**Root cause.** The native collision branch changes the object's routine
pointer to a dedicated wait state. That state no longer falls through the
shared movement, gravity, or terrain-probe code. A folded engine boolean such
as `rising=false` can look equivalent while continuing to execute the falling
branch every frame, so a second probe with post-landing radii moves the object
to a coordinate the ROM never visits.

**What to check.** Treat every native routine/address write as a control-flow
transition, not merely a descriptive mode. List which helpers each destination
routine calls and model a distinct engine state whenever a destination omits
movement or collision work. Regress the first landing and then verify that the
next wait update performs no terrain interaction until its actual activation
condition changes.

**ROM citation.** FBZ magnetic platform `loc_3B3C0` changes its routine to
`loc_3B3EC` after the first negative `ObjCheckFloorDist`. `loc_3B3EC` only
waits for magnetic activation; it does not call `MoveSprite2`, gravity, or a
second floor probe. Re-running the falling branch with radius `$10` moved the
engine platform from native Y `$0370` to `$036F`, causing the complete-run
player landing divergence at f8909.

**Originating commit.** `<pending: FBZ complete-run trace f8909>`.

---

## P57 -- Engine ordering bridges must prove local contact, not pointer liveness

**Symptom.** A CPU sidekick misses a native one-pixel follow correction even
though its speed, facing, delayed leader sample, and object-control byte all
match the disassembly.

**Root cause.** An engine-only ordering bridge uses a live `interact` object as
evidence that the sidekick is still locally supported. Native interact pointers
can remain latched after the object is hundreds of pixels away. Pointer
liveness alone therefore suppresses behavior that the ROM routine does not
gate on that pointer.

**What to check.** Separate native pointer semantics from engine-only collision
bridges. If a bridge exists solely to preserve a same-frame or prior-pass local
contact, require the native contact state or a bounded spatial overlap in
addition to object liveness. Regress both a genuinely local support and a live
but vertically remote stale pointer; do not special-case a zone, route, object
ID, or trace frame.

**ROM citation.** S3K `loc_13E0A`/`loc_13E34` gates the grounded +/-1 `x_pos`
nudge on ground speed, facing, and `object_control` bit 0, not `interact(a0)`
(`docs/skdisasm/sonic3k.asm:26707-26741`). At FBZ complete-run f9389, Tails'
live button pointer was `$574` pixels vertically remote; treating it as local
support suppressed the native +1 correction. The same bridge remains valid for
the proven AIZ local-contact case inside its existing `$80`-pixel band.

**Originating commit.** `<pending: FBZ complete-run trace f9389>`.

---

## P58 -- Preserve SolidObjectFull_Offset's symmetric live-radius lower bound

**S3K-specific:**

**Symptom.** A rolling player receives a side push from a full-solid object one
frame before the ROM, then returns to the native coordinate on the following
frame. Object position, horizontal width, and upper overlap boundary are exact.

**Root cause.** The shared resolver applies normal `SolidObject_cont` vertical
geometry to a `SolidObjectFull_Offset_1P` caller. The offset routine adds the
player's live `y_radius` to d2 and doubles that sum for the lower reject bound;
it does not add `default_y_radius` for the lower half. Using the normal lower
half makes a rolling player's collision box one pixel too tall at the boundary.

**What to check.** Identify the exact solid routine, including `_Offset` and
one-player variants, before selecting a shared geometry profile. Preserve d2
and d3 roles independently, and expose a provider capability when the shared
resolver otherwise uses `default_y_radius`. Regress exact equality at the lower
reject boundary and the next entering frame.

**ROM citation.** FBZ Obj71 calls `SolidObjectFull_Offset_1P`; after adding live
`y_radius(a1)` to d2, the routine copies and doubles d2 to form the symmetric
vertical span (`docs/skdisasm/sonic3k.asm:41294-41317`). At complete-run f9624,
normal-solid geometry side-pushed rolling Tails from `$1486` to `$148B` one
frame early; the ROM performs that push at f9625.

**Originating commit.** `<pending: FBZ complete-run trace f9624>`.

---

## P59 -- Resume routines preserve state unless the destination reloads it

**Symptom.** A badnik completes an attack or interrupt at the correct frame,
then walks farther than the ROM before its next turn even though its velocity,
turn delay, and recurring timer constant are individually correct.

**Root cause.** The engine treats a return to a named state as a fresh state
entry and reloads a countdown. The native continuation routine only changes
the routine byte and callback pointer, preserving the partially consumed
countdown from before the interrupt. A reload exists, but only in a different
turn-completion callback.

**What to check.** For every attack, magnetism, hurt, and wait-state return,
list the exact fields written by the destination label. Do not infer
initialization from the semantic state name. Preserve timers and accumulators
that the label does not write, and test both the resume frame and the later
callback that legitimately reloads them.

**ROM citation.** FBZ Blaster `loc_8956A` writes routine 2 and restores the
turn callback, but does not copy recurring `$3A` into patrol countdown `$2E`;
that reload occurs only in `loc_8955A` (`docs/skdisasm/sonic3k.asm:186475-186486`).
At complete-run f9965, resetting `$2E` from 64 to 128 after the attack let the
engine Blaster walk to `$1406` instead of turning near the ROM's `$13E0`, where
it incorrectly hurt Tails.

**Originating commit.** `<pending: FBZ complete-run trace f9965>`.

---

## P60 -- Apply Player_TouchFloor radius deltas from the pre-resize native centre

**Symptom.** A rolling player lands on an object at the correct frame and keeps
the correct subpixel fraction, but the engine's centre Y is exactly twice the
rolling-to-standing radius difference away from the ROM (10 pixels for Sonic).

**Root cause.** `Player_TouchFloor` saves the live `y_radius`, installs the
default radii, then adds `live_y_radius - default_y_radius` once to native
`y_pos`. Engine `setRolling(false)` also changes top-left-backed sprite
dimensions, moving `getCentreY()` in the opposite direction before an object
applies the ROM delta. Computing from that already-shifted centre therefore
inverts and doubles the error.

**What to check.** When a custom object calls `RideObject_SetRide` or otherwise
reproduces `Player_TouchFloor`, capture native centre Y and live Y radius before
clearing rolling state. After installing standing dimensions, write
`capturedCentreY + liveYRadius - standYRadius` through
`NativePositionOps.writeYPosPreserveSubpixel(...)`. Do not derive the write
from the centre returned after `setRolling(false)`.

**ROM citation.** `Player_TouchFloor` saves `y_radius`, installs
`default_y_radius`, subtracts the default from the saved value, and adds the
result to `y_pos` (`docs/skdisasm/sonic3k.asm:24335-24363`). FBZ wire cage
`sub_39F7E` seats the player and calls `RideObject_SetRide`, which invokes that
floor reset only when clearing `Status_InAir`
(`docs/skdisasm/sonic3k.asm:77618-77659`, `42027-42049`). At complete-run
f10499, native rolling Sonic moves from the cage's pre-reset `$AED` to `$AE8`;
the engine moved to `$AF2` before this correction.

**Originating commit.** `<pending: FBZ complete-run trace f10499>`.

---

## P61 -- Preserve native sign-bit sentinels that preempt shared thresholds

**S3K-specific:**

**Symptom.** Shared movement reaches the same numerical threshold as the ROM
and changes animation, facing, sound, or child-object state, but a player owned
by a transport object should have kept the prior state. The first mismatch is
often a one-bit status difference followed by position drift from a later
direction-dependent branch.

**Root cause.** The threshold port is numerically correct but omits a native
signed-byte guard immediately before the threshold's effects. Objects publish
negative sentinel values such as `$80`; the native `BMI` treats that state as
authoritative and returns before otherwise-valid shared logic runs.

**What to check.** Audit every `tst.b field / bmi` or `bpl` adjacent to a shared
threshold. Preserve the field's sign bit as a semantic runtime state and place
the guard at the same effect boundary: after calculations the ROM still
performs, but before animation, facing, SFX, dust/child allocation, or state
transition writes. Prefer the native state field over zone, object-id, route,
or frame conditions.

**ROM citation.** S3K `sub_14C20`/`sub_14CAC` perform the retail high-byte skid
threshold comparison, then test `flip_type(a0)` and return on `BMI` before
writing Stop animation, facing, SFX, or dust
(`docs/skdisasm/sonic3k.asm:28041-28167`). FBZ moving wire cage writes
`flip_type=$80`; at complete-run f11804, omitting the sentinel guard flipped
Tails left despite native status remaining `$08`, causing the next frame's
direction-dependent follow nudge.

**Originating commit.** `<pending: FBZ complete-run trace f11804>`.

---

## P62 -- Do not translate signed 68K arithmetic shifts as Java division

**Symptom.** An object moving at half speed matches the ROM on positive and
even negative displacements, but is one pixel behind on every odd negative
displacement. The position error can change a boundary collision or leave a
native per-player solid latch active for one extra frame.

**Root cause.** 68K `asr.w #1` sign-extends and rounds an odd negative word
toward negative infinity. Java integer `/ 2` truncates toward zero. They agree
for positive and even values but differ for values such as `-$03`: native ASR
produces `-$02`, while Java division produces `-$01`.

**What to check.** When porting a signed `asr.w` object displacement, use a
signed arithmetic shift (`value >> 1`) after preserving the native word-width
semantics. Do not replace it with `/ 2`. Keep logical shifts (`lsr`) distinct,
and add odd-negative, even-negative, and positive boundary cases to the focused
test whenever the shifted coordinate participates in collision.

**ROM citation.** FBZ screw door `loc_3BCB4` negates negative-direction
displacement before `asr.w #1` in both the bit-5 half-speed horizontal branch
and the vertical branch (`docs/skdisasm/sonic3k.asm:79688-79710`). At complete-
run f12435 the native subtype `$12` door moved from Y `$846` to `$845`, clearing
its Player 2 pushing bit and Tails' `Status_Push`; Java `/ 2` left the door at
`$846` for one extra frame.

**Originating commit.** `<pending: FBZ complete-run trace f12435>`.

---

## P63 -- Keep delayed native status snapshots separate from live contact state

**S3K-specific:**

**Symptom.** A push-driven object moves one frame early even though its live
solid contact and the player's Push bit both appear correct. The premature
whole-pixel move can also erase a non-zero subpixel fraction and create a
camera mismatch on the same frame.

**Root cause.** The object routine combines two different temporal samples:
object standing/pushing bits left by the prior SolidObject pass, and player
status bytes explicitly saved before that pass. Reading the player's current
Push flag collapses those samples and admits movement one frame early. A
separate error is translating a native `addq.w` position write with a setter
that resets the fractional word.

**What to check.** Model every saved status byte (`$3E/$3F`-style scratch) as
its own rewindable per-participant latch. Consume native P1/P2 in ROM order,
then process additional engine sidekicks as a labelled extension. Snapshot
current player status at the same pre-SolidObject point, and use
`NativePositionOps` for word-only `x_pos`/`y_pos` writes so subpixels survive.

**ROM citation.** S3K subtype-$03 `Obj_Spikes` reads the prior object's
pushing bits and saved Player 1/2 status bytes in `loc_24356`/`sub_2438A`, then
refreshes `$3E/$3F` before the current `SolidObjectFull` calls
(`docs/skdisasm/sonic3k.asm:49239-49343`). Its successful push uses
`addq.w #1,x_pos` for both spike and player. At FBZ complete-run f13766, using
live Push moved the spike/player one frame early and reset Sonic's `$A300`
fraction; native first moves them on f13767 with the fraction preserved.

**Originating commit.** `<pending: FBZ complete-run trace f13766>`.

---

## P64 -- Manual solid checkpoints return same-entry contact state; they do not emit compatibility callbacks

**Symptom.** A player visibly lands on an object during its solid pass, but the
object-local reaction in the next instruction does not run until a later frame
or never runs. The resolved player may be grounded with zero vertical speed
while the native routine would already have launched or released them.

**Root cause.** `MANUAL_CHECKPOINT` deliberately exposes the current
`SolidCheckpointBatch` to the executing object without invoking
`SolidObjectListener` compatibility callbacks. Ignoring the returned batch and
waiting for `onSolidContact(...)` therefore loses the standing/pushing bits that
the ROM reads immediately after its `SolidObjectFull` call.

**What to check.** When a native SST routine calls a solid helper and then tests
its standing/pushing bits in the same entry, consume `standingNow()` or
`pushingNow()` from the returned manual checkpoint at that exact program point.
Treat that fresh batch as authoritative: never fall back to an older listener
latch when a participant result is absent. The checkpoint and player-query
identity sets must match exactly (or fail closed before reactions), so neither a
query-only nor batch-only participant is silently ignored. Keep native P1/P2
reaction order before labelled multi-sidekick extensions. If
the reaction launches or transfers a rider, release the exact ride owner through
`ObjectManager.releaseRidingObject(...)` so the folded engine ride record and
the object's native standing bit clear together. Test through the real
post-movement `ObjectManager` pipeline; direct listener calls are supplemental
only.

**ROM citation.** FBZ spring plunger `loc_89C86` calls `sub_86A3E`
(`SolidObjectFull` with d1=$1B, d2=$04, d3=$06), then immediately tests Player 1
and Player 2 standing bits and calls `sub_8635E` to launch each at y-speed
`-$0A00` (`docs/skdisasm/sonic3k.asm:187094-187119`). At complete-run f14411,
the engine landed both players but ignored the checkpoint batch, leaving
P1 y-speed zero instead of launching in the same SST entry.

**Originating commit.** `<pending: FBZ complete-run trace f14411>`.

---

## P65 -- Preserve observable 68K register clobbers between sequential tests

**Symptom.** Native P1 reacts correctly, but P2 does not react even though the
disassembly's following `btst` appears to test an object status bit that is not
set for P2. Replacing the routine with independent per-player booleans looks
cleaner but disagrees with retail behavior.

**Root cause.** A subroutine between the two tests can overwrite the data
register that originally held object status. The later test observes the
clobbered register value, not a fresh status read. If a bit in an SFX id,
constant, return code, or helper scratch value aliases the later mask, the
second branch becomes coupled to the first branch's execution.

**What to check.** Track live 68K registers across every `jsr`/tail `jmp` and
subsequent conditional test. Do not assume the compiler-like intent was to keep
an earlier field value unless the assembly saves or reloads it. Model an
observable clobber in the smallest object-local owner, cite the exact constant
bit, and add a complete truth table. Keep extension-only players outside native
register accidents unless the ROM has a corresponding slot.

**ROM citation.** FBZ `Obj_FBZSpringPlunger` loads `status(a0)` into d0, tests
P1, and calls `sub_8635E`. That helper replaces d0 with `sfx_Spring=$B1` before
tail-jumping `Play_SFX`; on return, `loc_89CC4` tests P2 standing bit 4 in the
clobbered `$B1`, whose bit 4 is set. Consequently P1 standing launches both
players even when plunger status is only `$08` and P2's interact pointer names
another object (`docs/skdisasm/sonic3k.asm:187098-187116`, `181311-181320`;
`docs/skdisasm/sonic3k.constants.asm:1622`). FBZ complete-run f14411 exposes
this retail quirk.

The clobber can also change which persistent object-status bit a helper reads,
sets, or clears. Do not reduce that case to "skip P2 on dirty frames": retain
independent latches for the intended and aliased bits, select exactly one from
the observed register value for each native P2 invocation, and let capture and
release mutate only the selected latch. Aggregate lifetime/ownership may need
to count either latch. Rewind-capture every persistent aliased bit, but keep
same-call register dirtiness invocation-local. Additional engine sidekicks use
deterministic extended-P2 state and must not inherit a retail two-slot register
accident.

FBZ `Obj_FBZWireCageStationary` calls P1 with d6=standing bit 3, then—when
`FixBugs` is disabled—only increments d6 before P2. A changed non-empty P1
player DPLC leaves d6 at the player-art base (low bits zero), so P2 receives
bit 1 rather than standing bit 4. `sub_3A270` uses that same bit for its entry
`btst`, `RideObject_SetRide` uses it for `bset status(a0)`, and both release
paths use it for `bclr`. BizHawk at complete-run f16894 records
`$00100000->$00100001`; unchanged P1 mapping returns before the clobber and
restores d6 `$03->$04` on the next call
(`docs/skdisasm/sonic3k.asm:77875-77890,77896-78137,42027-42048`).

**Originating commit.** `<pending: FBZ complete-run trace f14411 P2>`.

---

## P66 -- Preserve branches that skip shared collision and impact tails

**Symptom.** A launched projectile is missing long before the player reaches
its later collision point, even though launch cadence, velocity, gravity, hurt
flags, and descending impact math all look correct in isolation.

**Root cause.** The native rising branch jumps past the shared target/floor
impact tail. A flattened port updates rising velocity and then falls through
that tail on every frame, so the projectile collides with the launch surface it
is specifically meant to pass through and deletes itself immediately.

**What to check.** Draw the control-flow edges around every movement sign test,
not just the arithmetic in each block. Record which branches reach target,
terrain, collision-list, draw, and delete tails. Test a deliberately penetrated
surface throughout the full sign transition: the last negative-velocity call
must still skip the impact helper even when its gravity add reaches zero; the
next call, which enters with zero/nonnegative velocity, may run the helper.
Apply the same branch oracle to special target-height and ordinary terrain
paths rather than testing only one subtype. If impact installs a new callback,
keep the detection frame live through every shared tail it still reaches and
convert on the next object callback. Audit parent counters separately: a target
branch may decrement family state immediately while an ordinary floor branch
only installs the pending callback.

**ROM citation.** FBZ missile child `loc_3C6CC` calls `MoveSprite2`, then its
negative `y_vel` branch adds `$18` and jumps directly to `loc_3C740`. Only the
nonnegative entry at `loc_3C6F4` adds `$10` and reaches the target-height or
`ObjCheckFloorDist` impact paths. Both install `loc_3C768` and continue through
`loc_3C740`; target impact also decrements parent `$40` immediately. On the next
callback, `loc_3C768` adds Y+4 and converts the same slot to `Obj_Explosion`
(`docs/skdisasm/sonic3k.asm:80282-80348`). At complete-run f15235, flattening
those branches removed all three native projectiles before slot 9's `$9E` hurt
overlap with Tails.

**Originating commit.** `<pending: FBZ complete-run trace f15235>`.

---

## P67 -- Solid checkpoints before same-entry deletion require manual execution

**Symptom.** A player remains grounded and `Status_OnObj` stays set after the
solid beneath them explodes or moves off screen, even though the native object
slot is recycled on that same callback. Position and velocity may remain frozen
because the engine deletes the object before its ordinary post-update solid
pass can release the rider.

**Root cause.** The native routine relocates or mutates the object, calls
`SolidObjectFull`, and only then executes its delete/cull tail. Engine
`AUTO_AFTER_UPDATE` resolution is structurally later than that tail and is
suppressed once `isDestroyed()` becomes true, so the native release pass never
runs.

**What to check.** Trace every collision helper and delete/cull edge in exact
instruction order. When a solid helper precedes possible same-entry deletion,
use `MANUAL_CHECKPOINT` and resolve the all-participant batch at the native
program point before culling, on both destructive and ordinary callbacks. Do
not also leave the automatic pass enabled. Preserve native P1 then P2 ordering,
then process labelled multi-sidekick extensions, and test through the real
`ObjectManager` path that every standing/on-object/ride latch clears and Air
is set before the object becomes destroyed.

**ROM citation.** FBZ missile-launcher companion `loc_3C636` moves
`x_pos(a0)` and cull anchor `$44(a0)` to `$7F00` when parent `$40` is zero;
regardless of that branch, it calls `SolidObjectFull` and then tail-jumps to
`Sprite_OnScreen_Test2`
(`docs/skdisasm/sonic3k.asm:80231-80269`). At complete-run f16682, both native
players changed from grounded/on-object to Air before slot 16 was recycled; the
engine culled first and left both latched.

**Originating commit.** `<pending: FBZ complete-run trace f16682>`.

---

## P68 -- Native coarse-X culling is part of SST allocation order

**Symptom.** A later dynamic system diverges even though its own movement and
collision math are exact. Lost rings, child objects, explosions, or other
`FindFreeObj` users can occupy consistently earlier slots than ROM, changing a
slot-derived loop counter, velocity ordinal, or update order.

**Root cause.** An earlier object used a convenient two-dimensional visibility
test or a held/ridden exemption where the native tail used
`Delete_Sprite_If_Not_In_Range`. The engine therefore removed an SST occupant
because it was vertically off screen, or retained it because a participant was
attached, while native lifetime depended only on the coarse X anchor. The
apparently unrelated slot drift then propagated into later allocation.

**What to check.** Port the exact delete tail separately from rendering
visibility. For `Delete_Sprite_If_Not_In_Range`, compare the spawn/cull anchor
with the camera using native unsigned `$80` chunks and the `$280` horizontal
window; do not add a Y test or a held-player exemption. Extend only the visible
viewport term for widescreen, retain the coarse margins, and destroy placement
objects through the respawnable offscreen path. Before changing a later
slot-sensitive system, compare the complete native and engine SST occupancy and
identify the first missing or extra occupant.

**ROM citation.** FBZ stationary wire cage `loc_3A164` ends with
`Delete_Sprite_If_Not_In_Range`; it does not call a two-dimensional on-screen
test and does not exempt an active rider. In the complete run near f18257, two
vertically distant cages remained in native slots 5 and 9 but were absent in
the engine. Every later object shifted earlier, so lost-ring slot/d7 cadence
selected a velocity ordinal 18 pixels above the native ring and Tails missed
it even though the lost-ring implementation itself was correct.

**Originating commit.** `<pending: FBZ complete-run trace f18257>`.

---

## P69 -- Routine changes do not implicitly reset raw-animation state

**Symptom.** An object performs the correct attack once, then repeats it too
early or too late. A duplicate child can appear on an otherwise exact
trajectory because the parent returned to its detection routine before ROM.

**Root cause.** The port treated a routine transition as a new animation and
cleared its animation index/timer, even though the native transition wrote only
`routine`, `mapping_frame`, or a separate wait counter. S3K raw-animation state
is stored in independent SST fields; it survives until an explicit script
setup, terminal command, or clear. A carried delay from the prior routine can
therefore be intentional timing state for the next routine.

**What to check.** List every SST field written on each transition and preserve
all others. Model both `anim_frame` and `anim_frame_timer`; preserving only the
timer is insufficient when entry can occur during more than one source frame.
Port terminal commands separately: for `Animate_RawNoSSTMultiDelay`, `$FC`
restarts at the setup pair, while `$F4` clears the timer, invokes the callback,
and then clears the animation index. Test entry from every reachable source
pair, not only the trace-observed delay, and count child allocations through a
complete return-to-detection cycle.

**ROM citation.** FBZ Blaster `loc_89528` starts its attack wait by writing
routine 6, mapping frame 0, and counter `$39=$10`; `loc_8957A` enters routine 8
and creates children without clearing `anim_frame` or `anim_frame_timer`.
Consequently `byte_89768` first consumes the carried `byte_8975E` state. Near
complete-run f18766, clearing a carried `$11` timer ended the engine attack 17
frames early and produced a second primary projectile that native did not
create until f18783.

**Originating commit.** `<pending: FBZ complete-run trace f18766>`.

---


## How to add a new entry
## P33 -- Object-controlled players retain object-owned mapping frames

**Symptom.** A player or CPU sidekick has the right position, velocity, raw
animation byte, and object-control state, but displays an ordinary player
animation frame instead of the literal mapping frame written by an intro,
cutscene, or capture object. A later routine handoff can also replace the
displayed frame one object slot too early.

**Root cause.** S3K tests `object_control` bit 1 before calling
`Animate_Sonic`/`Animate_Tails`. An object that writes a literal
`mapping_frame` together with a bit-1 control byte therefore owns the displayed
frame until that bit is cleared. Separately, an object running after the player
slot can write a new raw `anim` byte on release without retroactively running
the animator or changing the frame already published that tick. Engine forced
animation helpers that clear or recompute mapping state during placement,
release, or CPU routine changes collapse those native ownership boundaries.

**What to check.** For any object-controlled player sequence:

1. Record the exact `object_control`, `anim`, and `mapping_frame` writes; do not
   infer one from another.
2. If bit 1 is set, preserve the literal mapping frame and suppress the normal
   player animator until the ROM clears bit 1.
3. Keep CPU-routine changes separate from animation ownership. A routine write
   that does not write `anim`, `mapping_frame`, or `object_control` must retain
   all three.
4. Respect slot order on release: a post-player object may publish the next raw
   animation byte while the old displayed frame remains visible until the next
   player dispatch.
5. Do not clear an event-authored animation merely because a sidekick is being
   installed as an established follower; verify whether the native init path
   actually writes that byte.
6. Treat an engine forced-animation value as an owner, not as the native byte
   itself. If a level-start/event owner must release on landing, consume that
   explicit owner from the landing path before the same player slot reaches
   Animate and publish the native raw animation write there. Do not clear every
   matching Hurt/Fall or Fly value globally: unrelated CPU recovery can carry
   the same numeric animation through a different native routine.

**ROM citation.** `Sonic_Control` and `Tails_Control` skip their animators for
bit 1 (`docs/skdisasm/sonic3k.asm:22067-22076,26257-26272`). The AIZ plane
intro writes player `mapping_frame=0` with `$53`, later publishes Hurt after
the player slot, and its CPU-sidekick dormant marker writes `$83` without an
animation write; Fly begins only at the catch-up trigger
(`docs/skdisasm/sonic3k.asm:26389-26397,26474-26534,
135492-135495,135609-135619`). HCZ level-start setup writes `$1B` before the
ordinary sidekick init path; the landing routine writes Walk before Animate,
and flight recovery writes Walk during its routine-4-to-6 handoff
(`docs/skdisasm/sonic3k.asm:8111-8148,24325-24329,26631-26648`).

**Originating commits.** `b18c254e3`; `<pending: S3K intro landing milestone>`.

---

## P34 -- Public animation bytes can select private velocity tiers

**Symptom.** A player's trace-visible raw animation byte and physics are both
correct, but the displayed mapping frame comes from the ordinary Walk or Run
script instead of a short private script. The mismatch begins only above a
specific ground-speed threshold and can alternate among slope-frame banks.

**Root cause.** A negative animation-table entry is executable selection logic,
not merely a reference to the public animation ID. S3K Tails' Walk entry `$FF`
handler leaves the public `anim` byte at Walk but changes the internal script
pointer at `|ground_vel| >= $700` to private `AniTails1F`. That two-frame script
uses `$C3/$C4`, whose slope-bank stride differs from both ordinary Walk and Run.
Treating every velocity tier as a public raw-animation substitution makes the
trace byte wrong; omitting the private tier makes only the mapping frame wrong.

**What to check.** Follow every negative entry in the character's animation
pointer table through its speed comparisons and private pointer selections.
Record the exact threshold, whether the public animation byte is rewritten,
and the slope-frame stride selected for each branch. Load private scripts even
when no public animation constant names them, and test the production
ROM-backed profile as well as the shared animator helper.

**ROM citation.** `Animate_Tails` selects `AniTails1F` at absolute
`ground_vel >= $700` without changing the Walk byte
(`docs/skdisasm/sonic3k.asm:29462-29489`); `AniTails1F` is `$FF,$C3,$C4`
(`docs/skdisasm/General/Sprites/Tails/Anim - Tails.asm:79`).

**Originating commit.** `<pending: S3K Tails high-speed animation tier>`.

---

+## P35 -- Objects that read global oscillators must not advance them

**Symptom.** Every oscillating platform or hazard in the level changes phase
while one particular object is active. The target object can look locally
plausible, but a later unrelated platform is hundreds of oscillator ticks away
from ROM.

**Root cause.** The object port calls the engine's global oscillator update
before reading the table. ROM object routines read `Oscillating_table` only;
`OscillateNumDo` advances the shared table once at the level-loop tail after
all object slots. An object-local update therefore adds a second tick per frame,
and a different counter domain can defeat frame-number deduplication entirely.

**What to check.** When an object reads `Oscillating_table+N`, port only the
read and local position calculation. Keep the single global update under the
level loop's owner. Add a test that snapshots the complete oscillator table,
executes the object once, and proves the table is unchanged.

**ROM citation.** S3K calls `OscillateNumDo` once after
`Process_Sprites` at the `LevelLoop` tail, while
`Obj_MGZMovingSpikePlatform` only reads `Oscillating_table+$12`
(`docs/skdisasm/sonic3k.asm:7909,71029-71072`).

**Originating commit.** `<pending: MGZ moving-spike oscillator ownership milestone>`.

---

+## P36 -- S3K rideable objects must expose their operation-pointer high word

**S3K-specific.**

**Symptom.** CPU Tails lands on the correct live solid with matching position,
standing bit, and SST slot, but `Tails_CPU_interact` retains the preceding
object's value. The next off-screen `sub_13EFC` comparison can then despawn
Tails incorrectly, and trace replay first reports an interact-word mismatch.

**Root cause.** S3K stores the high word of the stood-on object's operation
pointer, not its object ID, in `Tails_CPU_interact`. An engine solid that does
not implement `RomObjectCodePointerProvider` cannot refresh that latch even
when the ride instance is otherwise correct.

**What to check.** Every S3K object that can set Player 2's standing bit must
expose the high word of its live locked-on-ROM operation pointer through
`RomObjectCodePointerProvider`. Use the pointer for the exact routine installed
in word 0 of that SST state; do not substitute the object ID or zone name. Add a
focused assertion for the returned word.

**ROM citation.** `TailsCPU_UpdateObjInteract` copies `(a3)` into
`Tails_CPU_interact`, and `sub_13EFC` compares that word against the current
stood-on slot (`docs/skdisasm/sonic3k.asm:26816-26843`).
`Obj_MGZ2LevelCollapseSolid` runs at `$0005180A`
(`docs/skdisasm/sonic3k.asm:106955-106970`), so its high word is `$0005`.

**Originating commit.** `<pending: MGZ collapse-carrier interact milestone>`.

---


## P37 -- A zero velocity is not symmetric across SolidObject side branches

**Symptom.** An airborne player is separated from the left edge of a full
solid at the correct pixel, but retains a stale nonzero `ground_vel` even
though native `x_vel` is zero. Physics and fall-animation cadence then diverge
together despite matching position and vertical velocity.

**Root cause.** S3K `SolidObject_cont` uses sign branches, not an abstract
strict "moving into" test. On the object's left edge (`d0 > 0`), only
`x_vel < 0` skips `loc_1E056`; therefore `x_vel == 0` clears both
`ground_vel` and `x_vel`. On the right edge (`d0 < 0`), `x_vel == 0`
does skip the clear. Treating zero identically on both sides preserves stale
ground speed on left-side contacts.

**What to check.** For every full-solid port that reaches the standard S3K
left/right branch:

1. Preserve the exact signed branch boundaries from the helper, including
   whether zero falls through or branches away.
2. If the shared engine predicate keeps legacy strict-sign behavior, opt the
   concrete provider into `zeroXSpeedStopsOnLeftSideContact()` with a citation.
3. Test `x_vel == 0` with nonzero `ground_vel`; asserting position alone will
   miss the bug.
4. Do not make the right-side zero case symmetric unless that object's ROM
   routine uses a different helper.

**ROM citation.** S3K `SolidObject_cont` left/right classification and stop
path at `docs/skdisasm/sonic3k.asm:41468-41483`. The MGZ invisible block calls
`SolidObjectFull2` at `docs/skdisasm/sonic3k.asm:42656-42691`.

**Originating commit.** `<pending: MGZ invisible-block zero-speed side-stop
milestone>`.

---

## P38 -- Player `routine=2` writes must clear the engine hurt state

**Symptom.** An object launch matches native position and velocity on its trigger
frame, but the next player tick uses hurt gravity/routine 4 instead of normal
air control. Trace replay reports a routine mismatch first, followed by velocity
and position drift.

**Root cause.** S3K objects can write `move.b #2,routine(a1)` unconditionally
after taking over a player that arrived in routine 4. The engine represents that
outer routine with `AbstractPlayableSprite.hurt`; copying only the launch
velocity leaves the wrong player dispatcher active.

**What to check.** Whenever an object routine writes player `routine=2`, call
`player.setHurt(false)` at the same state boundary. Do not apply this broadly to
interactions such as horizontal springs whose ROM tail does not write the
routine, and do not clear the separate invulnerability timer.

**ROM citation.** S3K up, down, diagonal-up, and diagonal-down spring tails at
`docs/skdisasm/sonic3k.asm:47720-47729,48139-48143,48213-48217,48304-48308`.
Cross-game origin: `s2-implement-object/rom-pitfalls.md` P36.

**Originating commit.** `<pending: CNZ spring routine-handoff milestone>`.

---

## P39 -- Preserve `blo`/`bhs` half-open trigger endpoints

**Symptom.** A player exactly N pixels from an object triggers one frame early,
even though all nearby positions and approach velocity match.

**Root cause.** ROM coordinate windows commonly compare the lower endpoint with
`blo` and the upper endpoint with `bhs`, producing `[lower, upper)`. Translating
that to a symmetric absolute-distance `<= N` check admits the exclusive upper
edge.

**What to check.** Port each signed/unsigned comparison in order. Test both
`upper-1` and `upper`; do not infer symmetry merely because the constants are
written as `origin-N` and `origin+N`.

**ROM citation.** S3K horizontal spring `sub_2326C` rejects its computed upper
X bound with `bhs` (`docs/skdisasm/sonic3k.asm:47957-48024`).

**Originating commit.** `<pending: CNZ horizontal-spring boundary milestone>`.

---

## P40 -- Moving touch objects publish live SST coordinates

**Symptom.** A moving or oscillating S3K touch object overlaps the player at an
exact boundary in the ROM, but the engine callback fires one frame late even
though the shared overlap comparison itself is correct.

**Root cause.** S3K `Collision_response_list` stores SST pointers rather than
copied coordinates. When an object moves before calling a draw-and-touch helper,
the next player-slot `Touch_Loop` reads the object's live post-move `x_pos/y_pos`.
Using the engine's older pre-update coordinate adds an unintended second frame
of position latency.

**What to check.** Confirm the object's routine order around movement and its
collision-list publishing helper. If movement precedes publication, opt the
object into `usesCurrentTouchResponseState()` and test an edge that differs by
one movement unit. Do not change the global overlap geometry or bypass the
previous-list membership rule.

**ROM citation.** CNZ `Obj_CNZBalloon` updates its sine-bobbed `y_pos` before
`Sprite_CheckDeleteTouch3` at
`docs/skdisasm/sonic3k.asm:66776-66795`; `Touch_Loop` dereferences the queued
SST pointer at `docs/skdisasm/sonic3k.asm:20656-20710`.

**Originating commit.** `<pending: CNZ balloon live-touch milestone>`.

---

## P41 -- Persistent interact words require providers on every ridden solid

**Symptom.** CPU Tails remains attached to, or merely walks away from, an
off-screen solid where the ROM performs its `$7F00` marker warp. The current
solid's native pointer word may appear correct in isolation, but the cached
comparison word is zero or was refreshed too late.

**Root cause.** S3K stores word 0 of the ridden object's SST code pointer in
`Tails_CPU_interact` and retains it after contact ends. A later off-screen ride
compares the new SST word against that persistent value. Modeling only the
solid visible at the failing frame misses the earlier support that established
the latch; an SST slot may also have been recycled, so current slot contents
are not evidence of the cached object's type.

**Correct pattern.** Implement `RomObjectCodePointerProvider` on every solid
that genuinely participates in this CPU-Tails ride path, using the high word
of the installed ROM routine pointer. Reconstruct the latch history from
frames where `Status_OnObj` is set and the interact slot is live, not from the
slot's contents at the eventual mismatch. Test both the earlier provider and
the mismatch target.

**ROM citation.** `sub_13EFC` retains and compares `Tails_CPU_interact` at
`docs/skdisasm/sonic3k.asm:26816-26843`; CNZ door routines occupy
`$00030xxx-$00031xxx` at lines 66036-66167, while spring variants occupy
`$00022xxx-$00023xxx` at lines 47500-47540.

**Originating commit.** `<pending: CNZ persistent interact-word milestone>`.

---

## P42 -- Direct mapping flips are not gameplay facing writes

**Symptom.** A player-controlled object displays the correct direct mapping
frame but changes the player's status byte, movement direction, wall-push
semantics, or later animation branch when the mapping visually flips.

**Root cause.** ROM object routines can write flip bits directly to the
player's `render_flags` while leaving `Status_Facing` unchanged. Treating every
visual horizontal flip as `setDirection(...)` aliases two independent native
state fields.

**Correct pattern.** When an object owns direct player mappings, translate
native `render_flags` writes with `setRenderFlips(...)`. Call
`setDirection(...)` only when the disassembly explicitly modifies the player
status facing bit. Test a flipped direct frame with the opposite gameplay
direction retained.

**ROM citation.** CNZ cylinder `loc_32610` writes `PlayerTwistFrames` to
`mapping_frame`, masks `render_flags`, and ORs `PlayerTwistFlip` without
touching `status` at `docs/skdisasm/sonic3k.asm:68078-68100`.

**Originating commit.** `<pending: CNZ cylinder render-flip milestone>`.

---

## P43 -- Respawnable self-deletes must remain in the S3K Camera-Y scan

**Symptom.** An object correctly deletes after moving off-screen but never
reappears when vertical camera motion exposes its layout row. Later shared-RNG
objects may initialize with shifted phases even when player physics still
matches for hundreds of frames.

**Root cause.** S3K has independent X-cursor and Camera-Y placement passes.
`Sprite_CheckDelete*` clears the live entry's respawn bit; if the layout entry
is still between the X cursors, a later `loc_1B982` Y-strip scan can recreate
it. Removing the engine spawn from both the live set and deferred Y-pass set
loses that native rescan opportunity.

**Correct pattern.** For a respawnable off-screen self-delete under S3K's
two-axis placement, remove the dead SST but retain the layout entry in the
deferred Camera-Y set. Let ordinary X-cursor trimming clear it when the entry
actually leaves the horizontal range. Test delete, a Y-coarse transition away,
and a later strip transition that recreates a distinct instance.

**ROM citation.** `Sprite_CheckDeleteTouch3` reaches the respawn-bit-clearing
delete path at `docs/skdisasm/sonic3k.asm:37262-37276`; the independent Y-strip
scan is `loc_1B982` at lines 37723-37762.

**Originating commit.** `<pending: S3K Y-pass self-delete respawn milestone>`.

---

+---

## P44 -- Grounded squash-edge escapes can still publish push while moving away

**Symptom.** A grounded player is separated sideways from the lower half of a
full-solid object and all positions and velocities match ROM, but
`Status_Push` is missing for that frame. This often occurs beside upright
spikes or another short solid when the player is moving away from the nearer
edge.

**Root cause.** `SolidObject_Squash` / the S3K lower-half branch sends an
`abs(d0) < $10` overlap back through the normal left/right helper. The later
AtEdge path publishes the player and object push bits for any grounded side
separation; it does not require the player to be moving into the solid.
Treating the squash escape as correction-only loses the transient status bit.

**Correct pattern.** For concrete `SolidObjectFull` callers whose disassembly
uses this shared escape, implement
`groundedSquashEdgeSideContactSetsPush()`. Keep position correction and speed
zeroing under their existing movement-direction gates; only the grounded push
publication is unconditional. Test a lower-half overlap within $10 pixels
while moving away.

**ROM citation.** S3K `SolidObjectFull` escapes the lower-half squash at
`docs/skdisasm/sonic3k.asm:41564-41568` and publishes grounded push through
`loc_1E06E` at lines 41473-41495. S2 follows the corresponding
`SolidObject_Squash -> SolidObject_LeftRight` path at
`docs/s2disasm/s2.asm:35336-35402`.

**Originating commit.** `<pending: shared spike squash-edge push milestone>`.

+---

## P45 -- Every S3K layout entry owns a respawn-table byte

**S3K-specific:**

**Symptom.** A destroyed or broken object reloads as a fresh live object after
vertical or backward camera movement even though ROM reloads its remembered
shell/state. The layout Y word may have bit 15 clear, which makes the same
record look non-tracked under S1/S2 parsing rules.

**Root cause.** S3K `Load_Sprites` advances the `a3` respawn-table cursor for
every six-byte layout entry and stores `a3` into the spawned SST's
`respawn_addr`. Persistence is therefore not conditional on the S1/S2 layout
high-bit convention. Reusing `ObjectSpawn.respawnTracked()` as the S3K
persistence gate discards native remembered state.

**Correct pattern.** When `ObjectPlacementController` is in S3K two-axis
cursor mode, allow every real layout entry to persist remembered destruction.
Keep the explicit `respawnTracked` gate for S1/S2 modes and for synthetic
non-layout objects. Test an entry with bit 15 clear, mark it remembered, unload
it, and verify a later load observes the remembered state.

**ROM citation.** S3K initializes and advances `Object_respawn_table` alongside
every layout record at `docs/skdisasm/sonic3k.asm:37513-37656`; the Camera-Y
loader sets bit 7 and writes `respawn_addr(a1)` at lines 37741-37758.

**Originating commit.** `<pending: S3K all-entry respawn persistence milestone>`.

---

## P46 -- `Obj_WaitOffscreen` uses the render box, not the placement window

**S3K-specific:**

**Symptom.** An object whose normal routine begins with a formation, timer, or
child allocation starts that sequence before the sprite is visibly on-screen.
The object may become interactive while its ROM counterpart is still using the
inert off-screen mapping.

**Root cause.** S3K `Obj_WaitOffscreen` replaces the operation pointer and sets
`width_pixels`/`height_pixels` to `$20`. It restores the saved operation only
after Render_Sprites publishes the sign bit in `render_flags`. A generic
placement margin, spawn window, or point-in-camera test is not equivalent to
that render-box overlap.

**Correct pattern.** Preserve the object's wait state until its native render
box overlaps the viewport, including the ROM's exclusive touching edge. Start
normal animation and gameplay work only after that boundary; do not use a wider
object-placement margin as an activation proxy.

**ROM citation.** `Obj_WaitOffscreen` installs the `$20` extents and restores
the saved operation from the render flag at
`docs/skdisasm/sonic3k.asm:180271-180303`; the special-stage entry ring invokes
it before animation/collision dispatch at lines 128219-128269.

**Originating commit.** `<pending: S3K entry-ring render activation milestone>`.

---

## P47 -- Project later playable slots before object-local cooperative checks

**S3K-specific:**

**Symptom.** A moving object and CPU sidekick match the trace independently,
but an object-local player collision happens one frame late. Correcting the
object's motion or widening its hitbox then creates a false solid contact on
the following frame.

**Root cause.** A folded engine object update can run before the CPU sidekick's
pending movement even when the native cooperative routine observes Player 2 at
the later slot phase. The subsequent folded solid checkpoint may likewise need
the object's pre-update publication because the ROM called `SolidObjectFull`
before the cooperative bounce routine.

**Correct pattern.** Follow the disassembly's P1/P2 probe order. For the later
native player slot, project only the pending movement visible at the ROM call
site; do not shift the whole object routine. When a post-update engine
checkpoint folds a pre-bounce `SolidObjectFull` call, use the existing
pre-update object-position contract for that bounded handoff, then release it
when the native vertical contact completes.

**ROM citation.** CNZ's top calls `MoveSprite2`, `SolidObjectFull`, and only
then `CNZMinibossTop_CheckPlayerBounce`; that helper probes Player 1 followed
by Player 2 at `docs/skdisasm/sonic3k.asm:145053-145103,145530-145578`.

**Originating commit.** `<pending: CNZ miniboss P2 bounce milestone>`.

---

## P70 -- Fixed-slot player-bound aggregate inherits ordinary manager culling

**Symptom.** A player-bound visual appears correctly, then disappears after
the player has travelled for a few seconds. Repeating the action that creates
the visual makes it reappear at the player's new location.

**Root cause.** Direct object tests exercise `update()`, but `ObjectManager`
can cull the aggregate before that method runs. The aggregate remains anchored
at its creation position while its child render positions follow the player,
so ordinary spawn-anchor `out_of_range` handling eventually deletes it. In the
ROM, fixed power-up slots can deliberately omit an `out_of_range` tail and live
until a semantic state flag clears.

**Correct pattern.** Audit every lifetime owner: manager pre/post-update
culling, remembered-placement unload, fixed-slot rules, `isPersistent()`, and
the out-of-range reference. Treat the absence of ROM culling as behavior. Make
a fixed-slot aggregate persistent when the ROM does, while retaining its
explicit form/state deletion condition. If the ROM does cull it, update or
override the reference anchor accurately instead. Add a manager-level test
that moves the player and camera beyond the ordinary culling window and checks
that the object remains registered, plus a test for its semantic deletion.

**ROM citation.** `Obj_HyperSonic_Stars` is installed in the fixed
`Invincibility_stars` slots during Hyper transformation. Its main and child
routines draw without an `out_of_range` tail and delete only after
`Super_Sonic_Knux_flag` clears at `loc_19486`.

**Originating commit.** `<pending: Hyper Sonic fixed-slot lifetime fix>`.

## How to add a new entry
When a trace-replay-bug-fixing iteration commits an object fix whose root
cause is a class of bug (not a one-off):

1. Identify which pitfall pattern category fits, or pick the next P-number
   if none fit.
2. Append a new entry following the template above.
3. Reference the originating commit hash so future readers can see the full
   diff and test cases.
4. Mirror to the other skill tree's copy of `s3k-implement-object/rom-pitfalls.md` in the
   same commit. Use the commit trailer `Skills: updated`.
5. If the pattern is cross-game, copy the entry into
   the `s2-implement-object` skill's `rom-pitfalls.md` with the analogous
   S2 disasm citation.

S3K-specific considerations: many patterns surface differently in S3K
because of (a) zone-set-aware object IDs, (b) the dual S&K-side / S3-side
ROM addresses, and (c) S3K's larger animated-state and PLC system. When
adding an entry that's specifically S3K-flavoured (e.g. zone-set
mis-resolution, S&K-vs-S3 address confusion), tag it with a leading
"**S3K-specific:**" marker so it doesn't get duplicated to the S2 file.

## P48 -- Only the horizontal spring locks the player's grounded controls

**Symptom.** After a vertical or diagonal launcher, the player's first grounded
frames ignore held left/right. The trace shows both sides landing with the same
`inertia`, then the ROM applying acceleration on the very next frame while the
engine holds the landing value for several frames before catching up. The drift
is small at first and compounds for the rest of the act.

**Root cause.** `move_lock` is the ROM's only grounded-input lock, and in the
spring family only the horizontal spring `sub_23190` (`loc_231BE`, `move.w #$F,$32(a1)`) writes it. The vertical/diagonal launch `sub_22F98` (sonic3k.asm:47700-47772)
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

**ROM citation.** docs/skdisasm/sonic3k.asm:47907. Cross-game: S2 `loc_18B1C` at
`docs/s2disasm/s2.asm:34031`, S3K `loc_231BE` at
`docs/skdisasm/sonic3k.asm:47907`, S1 `.doBounce` at
`docs/s1disasm/_incObj/41 Springs.asm:144`.

**Originating commit.** `<pending: spring grounded control lock milestone>`.

## P49 -- Child object omits the `Child_*_Sprite` parent-destroyed delete

**Symptom.** A parent badnik is destroyed correctly -- the player bounces, the
explosion spawns, the score is awarded -- but its attached children (orbiting
spikes, spinning arms, held projectiles) keep running and keep hurting. The
divergence shows up nowhere near the kill: the trace stays byte-identical for
several seconds and then the player is suddenly in the hurt routine with the
rings lost, while the ROM sails on. A `slot_dump`/`object_removed` diff shows
the ROM removing the child slots exactly **one frame after** the parent's
destruction.

**Root cause.** ROM child objects do not check their own lifetime. Their tail
call does it for them: `Child_Draw_Sprite`, `Child_DrawTouch_Sprite`,
`Child_CheckParent` and `Child_AddToTouchList` all begin
`movea.w parent3(a0),a1 / btst #7,status(a1) / bne.w Go_Delete_Sprite`
(`docs/skdisasm/sonic3k.asm:178046-178072`), and `Touch_EnemyNormal` sets that
bit on the badnik it destroys (`sonic3k.asm:20952-20953`). In
`Child_DrawTouch_Sprite` the test runs **before**
`Add_SpriteToCollisionResponseList`, so a child deleted on that pass never
publishes a touch entry. An engine child that models only the parent's
*position* inherits none of this and outlives its parent forever.

**What to check.** For every child object, read which tail call its routine
`jmp`s to, **per routine**. The delete is not a property of the object; it is a
property of the routine. A child commonly starts on `Child_DrawTouch_Sprite`
while attached and switches to `Sprite_CheckDeleteTouchXY` once launched or
breaking -- at which point it is genuinely independent and must **not** be
deleted with the parent. Porting the check to the whole class instead of the
attached branch is the mirror-image bug.

**Correct pattern.** In the branch whose ROM routine ends in a `Child_*` tail
call, after the movement step, test the parent's destroyed state, suppress this
frame's touch-list publication, and destroy the child.

**ROM citation.** `docs/skdisasm/sonic3k.asm:178046-178072`
(`Child_Draw_Sprite` family), `:20952-20953` (`Touch_EnemyNormal` setting status
bit 7). Worked example: `Obj_StarPointer`'s orbit routine `loc_8BEE6` tails into
`Child_DrawTouch_Sprite` (`:190853-190858`) while its launched `loc_8BF4C`
(`:190860-190866`) and breaking `loc_8BF74` (`:190873-190876`) routines tail into
`Sprite_CheckDeleteTouchXY` and do not.

**Cross-game.** S1/S2 have no `Child_*_Sprite` helper family; their children use
per-object parent checks, so this exact idiom is **S3K-specific**, but the
underlying question -- *what deletes this child, and in which routine?* -- is
universal.

**Originating commit.** `<pending: ICZ Star Pointer orphaned orbit points>`;
`OrbinautBadnikInstance.OrbinautOrbInstance` is the pre-existing correct example
in the same file family.

---

## P50 -- Some object branches read persistent *run* state, not level state

**Symptom.** An object behaves consistently and plausibly in the engine, matches
its disassembly line for line, and still takes the wrong branch in a mid-run
trace segment. Nothing about the object, the zone, the player or the frame
explains it.

**Why.** A handful of S3K objects branch on save/run-scoped globals that no
level-local state implies: `Chaos_emerald_count`, `Super_emerald_count`,
`Collected_special_ring_array`, `SK_alone_flag`. Those hold whatever the *whole
playthrough* has accumulated. A standalone segment replay arms one zone in
isolation, so every such global reads as its power-on default and the object
takes the "nothing collected yet" branch forever.

**Worked example.** `SSEntryRing_Main`'s collision handler `loc_6170A`
(`docs/skdisasm/sonic3k.asm:128276-128293`) awards 50 rings at `loc_61794`
(`:128325-128333`) when `Chaos_emerald_count` is 7 and `SSEntry_CheckLevel`
(`:128433-128443`) reports an S3-half level; otherwise it runs the special-stage
capture at `loc_6173A` (`:128295-128298`). Same ring, same zone, same player --
the only discriminator is a counter set by earlier special stages.

**What to check.** When porting a branch, classify each tested global as
*level-scoped* (reset on level load) or *run-scoped* (survives level loads).
Note the run-scoped ones in the port's Javadoc. If a trace frontier lands on one
of them, the object is probably right and the harness is missing progression:
verify against the run manifest's `emeralds_before` / `rings_before` before
touching the object. **Do not** seed the counter from trace data to make a
segment green -- that is hard rule 4 hydration; the ordered run chain is where
those branches are legitimately reachable.

**Cross-game.** S1 and S2 have the same shape with `Emerald_count` /
`Got_emerald` gating their special-stage and ending branches, so the
classification habit is universal; only the specific globals are S3K's.

**Originating commit.** `<pending: ICZ frame 2336 giant-ring diagnosis>`.

## P51 — `Obj_WaitOffscreen` suppresses every routine, including Init

**Contract.** Any S3K object whose entry point begins with `jsr (Obj_WaitOffscreen).l` runs **no
routine at all** — not even Init — until `Render_Sprites` has set `render_flags` bit 7, and the
release frame itself still runs no routine.

`Obj_WaitOffscreen` (`docs/skdisasm/sonic3k.asm:180271-180302`) pops its return address into
`$34(a0)` and overwrites `(a0)` with a placeholder that only draws a `$20x$20` `Map_Offscreen`
and deletes itself past coarse-X `$280`. Once bit 7 is set it takes the restore path, puts
`$34(a0)` back and `rts`.

**There are 52 call sites in `sonic3k.asm`.** Check the object's first instruction before porting.

**Symptom when missed.** The engine starts the object's behaviour on its spawn frame, so it
travels tens to hundreds of pixels away from where the ROM has it. This does **not** look like an
object bug in a trace: it surfaces as an unrelated-looking **single-field player divergence**
much later, when the object the ROM was about to interact with simply is not there. A measured
case sat motionless for 173 frames in the ROM while the engine swam `0x100+` px away, and printed
as a lone `y_speed` sign flip 218 frames later.

**Engine pattern.** Use the established `waitingForOnscreen` / `placeholderRenderedOnscreen` +
`refreshPostCameraRenderState` shape — see `RibotBadnikInstance`, `CorkeyBadnikInstance`,
`MantisBadnikInstance`.

**Unit-test consequence.** Existing tests that drive the object's behaviour directly will fail
once the gate is added, because they never release it. That is the tests pinning pre-gate
behaviour, not the gate being wrong — add a test-only release hook, as `ClamerObjectInstance:931`
already does.

**Beware the second defect.** A badnik whose ObjDat flags classify it SPECIAL takes the object's
own touch path, so the engine's ENEMY-category `applyEnemyBounce` never runs. In the ROM the
player's ±`$100` bounce comes from `EnemyDefeated` (`sonic3k.asm:179752-179771`), which the badnik
calls itself. Fixing the offscreen gate alone can leave the bounce still missing.

---

## P52 — A missing KosM producer deadlocks every *later* art consumer, and prints as player physics

**Symptom.** A single player field diverges mid-run — `x_speed`, or a status bit — with no
object anywhere near the reported frame. The real cause is an object several hundred frames
earlier that queued Kosinski art and is still waiting for it, holding a global byte that gates
something else entirely.

**Mechanism.** S3K art readiness is released through the recorded hardware-timing port, and a
release requires **kind + ordinal + submission fingerprint** to match. Ordinals are assigned in
submission order. So if the engine fails to submit *any* `Queue_Kos_Module` job the ROM issued,
every subsequent submission is assigned an ordinal that is N too low, its fingerprint will never
match the recorded row for that ordinal, and **it never becomes ready — for the rest of the
run**. One missing producer bricks every art consumer after it.

**Why it hides.** An object stuck in its art-wait routine is not visibly broken: it simply does
not act. If its post-art work writes a cross-object global, the divergence surfaces in whatever
consumes that global. The measured case: `Obj_HCZLargeFan` (sonic3k.asm:65588-65634) clears
`(_unkF7C7).w` after its eight-frame drop; `HCZ_WaterTunnels` (sonic3k.asm:8848-8899) is gated on
that byte at `:8870`. A KosM ordinal four behind left the fan waiting forever, so the water tunnel
never engaged, so the trace reported `x_speed 0x0400 vs 0x0300` — a player physics field, 1000+
frames after the actual defect.

**What to check, in this order.**
1. Dump the fixture's `hardware_timing.jsonl` ordinals for the segment.
2. Probe every engine `submitPrepared` with its ordinal, source and stack.
3. Diff the two sequences. The first ordinal where they part is the missing producer; everything
   after it is collateral.

**Do not** "fix" the consumer, and do not release the handle by relaxing the fingerprint match —
that is the hardware-timing exception leaking outside its port. Find the unimplemented producer.

**Cross-game.** S1 PLC and S2 DPLC queues use the same ordinal-keyed port, so the same
one-missing-producer-deadlocks-the-rest failure applies to all three games.

**Originating investigation.** HCZ Sonic+Tails segment frame 2478 (1135 errors). RESOLVED
2026-08-15: the missing producer was `HCZGeyser_ReloadEnemyArtAndDelete`'s
`jsr (LoadEnemyArt).l` (`docs/skdisasm/sonic3k.asm:65002-65005`), lost because the
engine camera-unloaded the geyser 29 frames into its 150-frame countdown. See P53 and
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P53 -- An object with NO `out_of_range` may be holding a global timer whose expiry is an art submission

**S3K origin, cross-game shape.** This is the well-known "ROM object never unloads"
family (P23 cross-game note / S1 P10 / S2 P52) with a much more expensive failure mode
than a missing sprite.

**Symptom.** A player physics field diverges more than a thousand frames after anything
visible happens, and the KosM/PLC ordinal ledger is N behind the recording from some
earlier point onward (see P52). Chasing the ordinal skew leads to a "missing producer"
that turns out to be *implemented* -- it simply died before it could run.

**Mechanism.** Several S3K objects that overwrite shared VRAM restore it on the way out,
and the restore is the *last* thing a long countdown does. `Obj_HCZWaterWall`'s
horizontal geyser is the measured case: `HCZWaterWall_Horizontal_UpdateChildSprites`
arms `move.w #150,$30(a0)` and installs `HCZGeyser_CleanupDelay`
(`docs/skdisasm/sonic3k.asm:64992-65000`), which is a bare `subq.w #1,$30(a0)` with
**no range test at all**, and whose expiry runs
`HCZGeyser_ReloadEnemyArtAndDelete` -> `jsr (LoadEnemyArt).l`
(`:65002-65005`), re-queueing all four `PLCKosM_HCZ1` archives (`:64354-64359`).
The geyser scrolls off screen long before 150 frames elapse. An engine that applies its
shared camera unload therefore deletes the object mid-countdown, the four
`Queue_Kos_Module` submissions never happen, and **every later KosM job in the run is
four ordinals behind** -- the P52 deadlock, with the "missing producer" being an object
that was fully implemented and simply killed early.

**What to check / fix.**
1. Audit the object's ENTIRE body for `out_of_range`, `MarkObjGone`,
   `Delete_Sprite_If_Not_In_Range` and `Go_Delete_SpriteSlotted`. Count the deletes and
   name each one. `Sprite_OnScreen_Test` is a DRAW, not an unload -- do not read it as one.
2. If none of the deletes is the shared camera macro, set
   `usesCustomOutOfRangeCheck() = true` and have `isCustomOutOfRange()` return the ROM's
   answer (often just `false`; the object already owns its own delete tests).
3. Range behaviour can be ROUTINE-dependent within one object. `Obj_HCZWaterWall`'s
   vertical branch DOES call `Delete_Sprite_If_Not_In_Range`, but only from
   `HCZWaterWall_Vertical_WaitPlayer` (`:65135-65136`); the horizontal branch never does.
   Model per phase, not per object, when the routines disagree.
4. Sibling audit: any object whose tail restores shared art
   (`Restore the overwritten badnik explosion art`, `sonic3k.asm:128487`, is another
   instance of the same shape) is a candidate for the same defect.

**A second, cheaper failure mode of the same defect: the freed SLOT re-phases every
later child allocation.** `Obj_SlotBonus`'s live routine `loc_4BF9A`
(`docs/skdisasm/sonic3k.asm:99324-99560`) likewise has no `out_of_range`,
`MarkObjGone` or `Delete_Current_Sprite` on any path, and the fixture's `slot_dump`
shows it holding SST slot 4 continuously for the whole stage. The engine's shared
camera unload retired it, and slot 4 is the LOWEST dynamic slot the ROM keeps
occupied -- so the next `AllocateObject` (`:37911-37914`, forward scan from the
first slot) handed slot 4 to a freshly spawned `Obj_SlotRing`. That ring now sits
at the cage's own index rather than above it, the ascending object walk has already
passed it, it loses the routine-0 tick it should have run on its spawn frame, and
its `$40` countdown reaches zero one frame late. Only the FIRST ring of a batch is
affected, which reads as an isolated non-cascading one-frame reward delay rather
than as an occupancy defect -- so when a single reward in a batch is late and the
rest are on cadence, check the parent's slot before the child's timer.

**Diagnostic that settles it in one run.** Probe the object's phase machine each
dispatch AND probe `ObjectManager.unloadCounterBasedOutOfRange` for that class. If the
phase log simply stops with a countdown still running, and the unload probe's last line
is `outOfRange=true custom=false`, you have it.

**Originating commit.** HCZ Sonic+Tails segment frame 2478, 1135 errors -> 0; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P54 -- `SolidObjectTop`'s d4 decides whether the rider is dragged, and it is per routine

**Symptom.** A moving top-solid platform is ported correctly -- the right speed
ramp, the right timers, the right release -- and the rider ends up exactly one
pixel further along the direction of travel than the ROM, every frame the
platform moves. A probe on the object itself shows the platform's own `x_pos`
and the value it writes to the rider are both correct; something after the
object's update adds the platform's per-frame delta a second time.

**Root cause.** `MvSonicOnPtfm` (`docs/skdisasm/sonic3k.asm:41647-41684`) ends
with `sub.w x_pos(a0),d2 / sub.w d2,x_pos(a1)`, where `d2` is the `d4` the
caller passed to `SolidObjectTop`. `d4` is a *carry reference*, not the
platform's position: the rider is dragged by `d4 - x_pos(a0)`. A caller that
loads `d4` **after** moving the platform produces a zero carry and does not drag
the rider; a caller that stacks `x_pos` **before** moving and pops it into `d4`
produces the full delta and does.

**Both forms appear in one object.** `Obj_FBZDEZPlayerLauncher`'s launch routine
`loc_3B9AC` does `move.w x_pos(a0),d4` after `MoveSprite2`
(`sonic3k.asm:79428`) -- no carry, because `sub_3B9D8` has already written the
rider's `x_pos` explicitly. Its return-to-home routine `loc_3BA4A` instead does
`move.w x_pos(a0),-(sp)` before stepping and `move.w (sp)+,d4` after
(`:79475`, `:79486`) -- full carry. `Obj_FBZRotatingPlatform`'s `loc_3B86A`
(`:79328`) is another pre-move stacker.

**What to check.** For every top-solid port, find the instruction that loads
`d4` and ask whether the platform has already moved at that point. Then set
`SolidObjectProvider.carriesRiderOnHorizontalMove` accordingly -- and make it
routine-dependent (a field latched by whichever routine ran this frame) when the
object's routines disagree, exactly as with `usesCustomOutOfRangeCheck` in P53.
The engine default is `true`, so a post-move-`d4` object silently gets a carry
it should not have.

**Second trap in the same family.** `SolidObjectTop` takes one vertical
parameter, `d3`, and both the landing test and `MvSonicOnPtfm`'s per-frame
re-seat use that same value. When the ROM caller passes a bare `d3`, the engine
params are `SolidObjectParams.of(d1, d3, d3)` -- not `of(d1, d3, d3 + 1)`. The
`+1` shape copied from other platforms costs exactly one pixel on the ride
frames while the landing frame still matches, which reads as a landing bug and
is not one.

**Cross-game.** S1 `MvSonicOnPtfm` / S2 `SolidObject`'s `objoff_2E` carry
reference are the same mechanism; `carriesRiderOnHorizontalMove`'s own javadoc
documents the S2 `Obj65` subtypes that differ. The habit -- *find the
instruction that loads the carry reference* -- is universal.

**Originating commit.** FBZ Sonic+Tails segment frame 64: `Obj_FBZDEZPlayerLauncher`
implemented; see `docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P55 -- "The ROM skipped `SolidObjectTop` this frame" is NOT "the object is not solid"

**Symptom.** A top-solid object is ported correctly and its own frontier clears,
then a *new* divergence appears on exactly the frame the object changes routine:
both characters report `air` 1 against an expected 0 and `status_byte` `0x0002`
against `0x0008`, and the camera follows them a couple of pixels the wrong way.
The object itself is in the right state; its riders have been thrown off it.

**Root cause.** Many S3K objects change phase with a `bra.w` that jumps *over*
their `SolidObjectTop` call, so the ROM performs no solid processing at all on
that one frame. In 68k that is a no-op for the rider: `status(a0)`'s standing
bits and the player's `Status_OnObj` are simply left alone, and the next
frame's routine picks the rider straight back up. In the engine, a
`SolidObjectProvider` that reports `isSolidFor() == false` is telling the
*generic platform path* the ride has ended, and it unseats the player.
"No solid call this frame" and "not solid this frame" are opposite statements.

**Measured case.** `Obj_LRZCollapsingBridge`'s break routine `loc_39D84`
(`docs/skdisasm/sonic3k.asm:77496`) is entered by `bra.w loc_39D84` at `:77416`,
skipping `loc_39CCC`'s `SolidObjectTop` (`:77429-77435`); `loc_39CE8` calls it
again on the following frame (`:77440-77445`), and only `sub_39D1A` (`:77458`)
ever clears a rider's bits. Modelling the skip as `isSolidFor() == false` moved
the LRZ segment frontier only from 100 to 110 instead of to 208.

**What to check.** Read what the skipping routine *does to rider state*. If it
neither writes the standing bits nor moves the object, the accurate engine model
for a stationary platform is to stay solid: a skipped re-seat and a performed
re-seat are indistinguishable in position, and staying solid preserves the
"rider untouched" semantics the ROM actually has. Only report not-solid when the
ROM routine genuinely releases the rider.

**The tempting wrong fix.** `preservesObjectManagedRideWhileNotSolidFor` looks
like it is for this, and it is not -- it is the object-control capture contract
(S2 CNZ `Obj85`, `obj_control=$81`). Setting it on a plain top-solid platform
changed the *landing seat* and drove the same frontier backwards, from 110 to
101. Measure before adopting a flag whose javadoc describes a different object
family.

**Cross-game.** S1/S2 `SolidObject` callers have the same skip-the-call idiom;
the question -- *does this ROM routine release the rider, or merely not touch
it?* -- is universal.

**Originating commit.** `<pending: LRZ collapsing bridge, segment frame 100 -> 208>`;
see `docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P56 -- S3K `SolidObjectTop` rejects the zero-distance boundary: the player must already have penetrated by 1px

**Symptom.** A top-solid object is ported correctly -- right `d1`, right `d3`,
right routine -- and a rider lands **exactly one frame early**. The trace prints
this as a whole cluster of fields flipping together on one frame (`y_speed`
expected non-zero actual `0`, `air` 1 vs 0, `rolling` 1 vs 0, `status_byte`
`0x0006` vs `0x0008`, `y` off by a couple of pixels), and the engine's state on
frame *N* is bit-for-bit the recording's state on frame *N+1*. The sub-pixel
column matches on the failing frame and only diverges on the next one, so it
reads like sub-pixel accumulation and is not.

**Root cause.** Every S3K top-solid new-landing entry point -- `sub_1E410`,
`loc_1E42E`, `SolidObjCheckSloped` and `SolidObjCheckSloped2` -- converges on
`loc_1E45A` (`docs/skdisasm/sonic3k.asm:42005-42015`):

```
        sub.w   d1,d0          ; d0 = objTop - (y_pos(a1) + y_radius(a1) + 4)
        bhi.w   locret_1E4D4   ; C=0 and Z=0 -> reject.  d0 == 0 has Z=1, falls through
        cmpi.w  #-$10,d0
        blo.w   locret_1E4D4   ; UNSIGNED compare against $FFF0
```

The `cmpi.w #-$10,d0` is an **unsigned** compare. For `d0 == 0` it computes
`0 - $FFF0`, which borrows, sets C, takes `blo` and returns. The `bhi` above it
lets `d0 == 0` through precisely *because* `Z` is set, so the zero case is
filtered by the second test, not the first. The accepted window is therefore
`d0` in `[-$10,-1]` -- **the player must already be at least one pixel inside
the surface**. Touching it exactly is not a landing; the ROM waits one more
frame and lands from further in.

The landing then writes `y_pos(a1) = y_pos(a1) + d0 + 3`, so a `d0 == 0`
landing would seat the rider `+3` px -- a distinctive 3-pixel `y` error if an
engine accepts the boundary where the ROM does not.

**What to check.** Compute `d0` yourself at the failing frame:
`d0 = (object y_pos - d3) - (player y_pos + player y_radius + 4)`. If it is
exactly `0`, this is the defect. Remember the rolling `y_radius` (Sonic `$E`,
Tails `$E`) and that unrolling on landing adjusts `y_pos` by the radius delta
(`1` for Tails, `5` for Sonic) *after* the `+3`.

**Engine mapping.** `ObjectSolidContactController`'s `distY` is `-d0`, and the
boundary lives in `GameRules`' `CollisionRules.topSolidLandingAllowsZeroDist`.
S2 already sets it `false`; **S3K currently sets it `true`, which contradicts
the ROM above.** Flipping it is correct in isolation but reds
`TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay` and their complete-run
siblings, because `CnzTrapDoorInstance` and `AizTransitionFloorObjectInstance`
model the same one-frame-early symptom with a *second* mechanism
(`getTopSolidPlayerPositionHistoryFrames() == 1`, sampling the previous frame's
player position) layered on top of the wrong boundary. The two must be resolved
together -- do not "fix" it per object with
`providerAllowsZeroDistanceTopSolidLanding`, which just re-adds the
compensation.

**Cross-game.** S1's `UNIFIED` `PlatformObject` path genuinely does accept the
boundary, so this is an S3K/S2 contract, not a universal one. The habit --
*read the condition codes, not the mnemonic's name* -- is universal: `blo`
after `cmpi.w #-$10` is unsigned and excludes zero, which is invisible if you
read the pair as "d0 >= -16".

**Originating investigation.** LRZ Sonic+Tails segment frame 208 (11942
errors). Flipping the flag alone moves it to frame 361 / 6480 errors; see
`docs/status/trace-frontier-log.md`, 2026-08-15. NOT landed -- held pending the
phase-model resolution above.

---

## P57 -- A solid object's acquire-time exemptions are tested only when its standing bit is CLEAR

**Symptom.** A player lands correctly on a solid object, then is unseated the
moment they change state on top of it -- most visibly on the roll-entry frame,
where the trace prints `air` expected 0 / actual 1 and `status_byte` expected
`0x000C` / actual `0x0006` on the same frame. `camera_y` diverges by ~3px on
that frame too, *before* any player position field does, because `MoveCameraY`
picks its grounded arm from the air bit: the camera is the symptom, the ride is
the cause.

**Root cause.** S3K's per-object solid wrappers open with the object's OWN
standing bit and branch away before any exemption test:

```
SolidObject_Monitor_SonicKnux:
        btst    d6,status(a0)        ; already standing on the monitor?
        bne.s   Monitor_ChkOverEdge  ; -> continued ride; NOTHING below runs
        cmpi.b  #2,anim(a1)          ; rolling animation? -> rts (not solid)
        ...                          ; Knuckles glide / slide exemptions
```
(`docs/skdisasm/sonic3k.asm:40559-40576`; `SolidObject_Monitor_Tails` has the
identical shape at `:40583-40590` with the competition-mode test.)

So the roll/glide/competition exemptions are **acquire-time gates only**.
`Monitor_ChkOverEdge` (`:40594-40612`) releases the rider on exactly two
conditions: `Status_InAir` set, or the rider leaving the `[-d1, +d1*2]`
horizontal span. An engine `isSolidFor()` that evaluates the exemptions
unconditionally, every frame, unseats a rider who merely starts rolling.

**What to check.** For every ported per-object solid wrapper, find the first
instruction. If it is `btst d6,status(a0)`, everything after the `bne` belongs
in the *acquire* path only, and `isSolidFor()` must short-circuit to `true`
while that object is the player's ride.

**The second half of the same contract.** The standing bit must then be
*cleared* when the rider leaves -- `Monitor_ChkOverEdge`'s `.notonmonitor` arm
does `bclr d6,status(a0)` (`:40613-40617`). If the engine latches its
equivalent bookkeeping and never clears it, `Obj_MonitorBreak`
(`:40628-40634`) later forces `Status_InAir` on a player who is nowhere near
the object: it releases P1/P2 purely on `standing_mask|pushing_mask`. The
measured case surfaced ~60 frames after the ride, as the player rolled into the
same monitor at speed and was thrown airborne by their own break. **Fixing the
acquire gate without the clear just relocates the divergence.**

**Cross-game.** Not yet verified against S1/S2 wrappers, but the habit --
*read what the first `btst`/`bne` skips over, and ask whether the routine it
skips to still clears the bit on exit* -- is universal.

**Originating commit.** MGZ Sonic+Tails segment frame 321 -> 1909; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P58 -- S3K `ObjCheckCeilingDist` is the `eori.w #$F` / `FindFloor` probe, not the legacy object-ceiling entry

**Symptom.** An object that rises into the ceiling and then does something
periodic there ends up **one frame late and one pixel low, forever**. Nothing
about the object looks wrong: its phase parity is right, its step values are
right, its routine transitions happen. The trace prints this nowhere near the
object -- as a *player* velocity divergence on the frame the player's path first
grazes the object's hitbox, typically a whole-vector sign inversion if the
object is a boss (`neg.w x_vel / y_vel / ground_vel`,
`docs/skdisasm/sonic3k.asm:20913-20915`).

**Root cause.** `ObjCheckCeilingDist` (`docs/skdisasm/sonic3k.asm:20351-20366`)
is not a generic "distance to ceiling":

```
        move.w  y_pos(a0),d2
        sub.w   d0,d2            ; d0 = y_radius
        eori.w  #$F,d2           ; <-- low-nibble inversion
        movea.w #-$10,a3
        move.w  #$800,d6
        moveq   #$D,d5
        bsr.w   FindFloor
```

The `eori.w #$F,d2` changes the low-nibble arithmetic inside `FindFloor`, and
collision data still comes from the height map indexed by X. In the engine that
is `ObjectTerrainUtils.checkNativeUpwardCeilingDist`.
`ObjectTerrainUtils.checkCeilingDist` is a **different contract** -- the legacy
S1/S2 object-ceiling path -- and at the same geometry it can report one pixel
more clearance. A `bpl`-style "keep going while `d1 >= 0`" loop therefore takes
one extra step.

**What to check.** Any S3K object porting `jsr (ObjCheckCeilingDist).l` must use
`checkNativeUpwardCeilingDist` (or `checkCeilingDistWithFlipAwareAngle` when the
ROM consumes `Primary_Angle` afterwards). Grep for `checkCeilingDist(` in the
`sonic3k` packages: any hit is a candidate defect.

**Measured case.** `Obj_Tunnelbot`'s `TunnelbotMiniboss_CeilingRise`
(`:184769-184778`) rises `subq.w #1,y_pos` per frame and hands off when `d1`
goes negative. `TunnelbotBadnikInstance` used the legacy entry and stopped one
frame late, so every frame of the following `TunnelbotMiniboss_RumbleWait`
(`:184790-184796`) `-2/+1` ladder sat 1px low and the player's boss bounce fired
one frame early. `MgzMinibossInstance` ports the *same* ROM routine and was
already on the native probe -- **two ports of one routine disagreeing is itself
the tell**; when you find a duplicated routine, diff the two ports first.

**Cross-game.** S1/S2 object ceiling checks legitimately use the legacy entry,
so this is an S3K contract. The habit -- *a helper's name is not its contract;
read which ROM entry point it models* -- is universal.

**Bonus trap in the same file family.** `move.b (V_int_run_count+3).w,d1` is an
**address**: `V_int_run_count` is a longword (`addq.l #1,(V_int_run_count).w`,
`:543`), so `+3` selects its low byte. Porting it as `vIntRunCount + 3` inverts
bit 0 -- and therefore inverts any `btst #0,d1` two-way step -- and rotates any
`andi.b #7,d1` gate by three frames. Four engine sites carried this misreading;
`MgzMinibossInstance` (both rumble routines) was fixed with the above,
`AizFlippingBridgeObjectInstance:304` and `CluckoidBadnikInstance:171` are still
outstanding.

**Originating commit.** MGZ Sonic+Tails segment frame 1909 -> 4603; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P59 -- A one-shot "arm" guard on a ROM timer that is re-armed every frame

**Symptom.** A timed object works: it arms on the right frame, does the right
thing for the right number of frames, and its own unit tests pass. Then a
*second* character interacts with it slightly later than the first and gets
nothing. The trace prints this as a lone sidekick velocity field -- a large
value expected, `0x0000` actual -- with no object field diverging at all,
because the object's status and position were correct throughout.

**Root cause.** ROM object routines are straight-line code executed top to
bottom every frame. A `move.w #$3C,$30(a0)` sitting above the routine's
`tst.w $30(a0)` countdown is therefore a **re-load**, not a one-shot arm: as
long as the trigger condition holds, the timer is refreshed to its full value
on every one of those frames, and the countdown only starts from the *last*
frame the condition held. An engine port that guards the arm with
`if (timer == 0)` -- an entirely natural-looking "don't re-trigger while
active" -- shortens the window by exactly the duration of the triggering
action.

**Measured case.** `Obj_MGZDashTrigger`'s `loc_25D9C`
(`docs/skdisasm/sonic3k.asm:51493-51545`) probes contact with `sub_1DD0E`, then
stores `#$3C` into `$30(a0)` for P1 (`:51502-51521`) and again for P2
(`:51527-51545`), and only *then* falls into `loc_25E22`'s
`tst.w $30(a0)` / `subq.w #1,$30(a0)`. Sonic held the spindash animation
against the trigger for eight frames, so the ROM's window ran 60 frames from
the eighth; the engine's ran from the first. Tails landed on the trigger 62
frames after the first spindash frame and was launched by `sub_25EA6`
(`:51580-51608`) in the ROM and not in the engine -- printing as
`tails_y_speed` expected `-0428`, actual `0x0000`, MGZ segment frame 4603.

**Second half of the same shape.** The ROM tested P1 and P2 with two separate
masks (`andi.b #$11,d6` then `andi.b #$22,d6`) and ran both arms; the engine
`break`ed out of its player loop after the first player armed. Where the ROM
falls through two independent blocks, do not short-circuit -- later blocks
often overwrite shared per-arm state (here `$32(a0)`, the child-sprite step).

**What to check.** For every ported timer, locate the store and the countdown
in the ROM listing and note their **order within one execution of the
routine**. A store *above* the countdown re-arms; a store reached only through
a branch that the countdown's non-zero case skips is a genuine one-shot. Never
add a `timer == 0` guard the ROM does not have.

**Cross-game.** Universal. S1/S2 objects have the same straight-line structure;
the habit -- *read the routine top to bottom and ask what runs on a frame where
the timer is already non-zero* -- applies everywhere.

**Originating commit.** MGZ Sonic+Tails segment frame 4603 -> 4716; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P60 -- `getOnScreenHalfWidth()` defaults to 16, and the offscreen gate makes a wide object non-solid

**Symptom.** A character walks straight *through* a solid object that is
correctly implemented, correctly sized for collision, and visible on screen.
Nothing about the object is wrong -- its `getSolidParams()` half-width is right,
its routine is right, it is loaded in the right slot. The trace prints this
some frames later as a lone sidekick field (here `tails_x_speed` expected
`0x0000` / actual `0x04E6`), because the ROM's consequence was a death the
engine never performed.

**Root cause.** Two different ROM widths are in play and the engine only
overrides one of them.

* `SolidObject`'s X extent comes from the `d1` the caller loads --
  `width_pixels(a0) + $B` for the `Obj_Spikes` family
  (`docs/skdisasm/sonic3k.asm:49017-49019`). The engine models this in
  `getSolidParams()`.
* Whether the solid helper runs **at all** comes from `render_flags(a0)` bit 7,
  which `Render_Sprites` sets from `width_pixels(a0)` -- `SolidObject`'s own
  entry is `tst.b render_flags(a0) / bpl.w loc_1E0A2`
  (`sonic3k.asm:41390-41392`). The engine models this in
  `isWithinSolidContactBounds()` via `getOnScreenHalfWidth()` /
  `getOnScreenHalfHeight()`.

`AbstractObjectInstance.getOnScreenHalfWidth()` returns a flat **16**. Any
object whose ROM `width_pixels` exceeds `$10` and does not override it is
judged offscreen too early: its assumed footprint clears the camera edge while
its real one still straddles it, `isWithinSolidContactBounds()` returns false,
and the object performs **no solid processing whatsoever** on the frames where
the ROM is still solid.

**Measured case.** MGZ1's floor-spike strip at `(0x1050, 0x0220)` is layout
subtype `$30`, so `Obj_Spikes` stores `Spikes_Dimensions[6]` = `$40, $10`
(`sonic3k.asm:48926-48934` table, `:48937-48939` store): half-width `$40`,
footprint `0x1010-0x1090`. `Sonic3kSpikeObjectInstance` overrode
`getOnScreenHalfHeight()` but not `getOnScreenHalfWidth()`, so the engine used
`0x1040-0x1060`, entirely left of a camera at `0x106E`. Tails, whose own
`SolidObjectFull` P2 gate (`sonic3k.asm:41011-41012`
`tst.b render_flags(a1) / bpl.w locret_1DCB4`) had just released him back
on-screen, ran through the strip instead of being crushed by
`loc_1E126`'s `cmpi.w #$10,d4 / Kill_Character` (`sonic3k.asm:41595-41602`).

**What to check.** For every object with a ROM `width_pixels` (or
`height_pixels`) other than `$10`, override **both** `getOnScreenHalfWidth()`
and `getOnScreenHalfHeight()` from the same ROM table the object's init reads.
A `getOnScreenHalfHeight()` override with no width sibling is a strong tell.
The failure is silent: the object still renders, still reports the right solid
params, and simply stops being solid a few pixels early.

**Cross-game.** Universal. S1/S2 `Render_Sprites` equivalents read the same
width byte, and the same default lives in the shared
`AbstractObjectInstance`.

**Originating commit.** MGZ Sonic+Tails segment frame 4716 -> 10709; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

## P61 -- `swap` on a register `GetSineCosine` only word-wrote carries an inherited residue

**Shape.** A ROM routine calls `GetSineCosine`, then does `swap dN` and a long
shift or add on the result. `GetSineCosine` writes only `d0.w` and `d1.w`
(`move.w SineTable(pc,d0.w),d1`, `sonic3k.asm:3025`), so after `swap` the *low*
word of the register is whatever the caller -- or, for `d1`, whatever the
**previously executed object slot** -- happened to leave in the high word. A
straight `sin << 12` port silently models that residue as zero.

**Why it bites.** `Obj_MGZSwingingPlatform`'s `sub_34074` (`sonic3k.asm:70487`)
does `swap d0 / swap d1 / asr.l #4` and then accumulates five steps, so the
residue reaches the integer part:

```
platformX = pivotX + ((20480*C + 5*(H >> 4)) >> 16)
```

with `C` the cosine word and `H` the inherited high word. The carry is at most
one pixel and is only *possible* when `k = (5 * (C & $F)) & $F >= 11`; whether
it actually happens needs `H`. A rider standing on the object inherits the whole
error, so it surfaces as a one-pixel player `x` with a byte-identical `x_sub` --
never as an object field.

**What to check.** Whenever you port a `swap`/`asr.l`/`add.l` sequence applied
to a `GetSineCosine`, `Random_Number` or similar word-writing helper's output,
work out where the register's high word came from before deciding it is zero.
`move.l (a0),d0` in `Process_Sprites`' `sub_1AAFC` (`sonic3k.asm:35983-35988`)
makes `d0`'s high word the high word of the object's own routine pointer --
deterministic and usually small. `d1` is *not* set by the dispatcher and carries
across object slots.

**Do not fit an angle table for it.** The residue is not a function of the angle
alone, so any per-angle table is right on the recording it was measured against
and wrong on the next one. See
`docs/S3K_KNOWN_DISCREPANCIES.md`, "MGZ swinging platform endpoint".

**Cross-game.** S1/S2 `CalcSine` has the same word-write shape; check any
`swap` that follows one.

**Originating commit.** MGZ Sonic+Tails segment frame 10709; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

## P62 -- a range table read with `(a2)+` is often *cumulative*, not four offsets

**Shape.** A routine loads a coordinate into `dN`, then walks a `dc.w` table
with `add.w (a2)+,dN` **twice per axis without reloading the coordinate**. The
second word is the window *span*, not a second offset from the object.

`Obj_HiddenMonitorMain` (`sonic3k.asm:176046-176080`) is the canonical case:

```
        move.w  x_pos(a0),d0      ; d0 = monitor x
        move.w  x_pos(a1),d1      ; d1 = signpost x
        add.w   (a2)+,d0          ; d0 = monX - $E
        cmp.w   d0,d1
        blo.s   loc_8374C         ; out
        add.w   (a2)+,d0          ; d0 = monX - $E + $1C = monX + $E
        cmp.w   d0,d1
        bhs.s   loc_8374C         ; out
```

with `word_8379E: dc.w -$E, $1C, -$80, $C0` (`:176098`). The real windows are
`x ∈ [monX - $E, monX + $E)` and `y ∈ [monY - $80, monY + $40)` -- **half** the
X width and a third of the downward Y reach that the literal table words
suggest. Comparisons are unsigned word compares (`blo` / `bhs`).

**Why it bites.** A too-wide accept box silently takes the *other* ROM branch.
Here `loc_83760` reveals the monitor and does `bclr #0,$38(a1)`, clearing the
signpost's landed bit; `Obj_EndSignLanded` then re-launches the sign
(`:176203-176228`), so the whole end-of-act -- `Set_PlayerEndingPose`, the
results owner, the seamless act transition -- slips by tens of frames. The
symptom is far from the cause: zeroed player velocities and animation `$13`
arriving late, with no object field compared anywhere near it. In MGZ1 the
monitor at `$2F00` is `$10` from the sign at `$2F10` -- rejected by the ROM,
accepted by the naive box.

**How to spot it in the fixture.** The out-of-range branch rewrites the object's
code pointer to `Sprite_OnScreen_Test`, so a monitor that resolved out of range
shows up in `object_near` / `object_state` as `Sprite_OnScreen_Test`
(`$0001B588`) rather than `Obj_Monitor`. That is a direct ROM readout of which
branch the recording took -- check it before porting the box.

**What to check.** Any `(a2)+` bounds walk. If the base register is not
reloaded between the two adds, the table is `low, span`. Ports that read it as
`low, high` are wrong on every object that uses the shape.

**Originating commit.** MGZ Sonic+Tails segment frame 12932; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P63 -- An object branch gated on save-game inventory cannot be reached by a standalone segment replay

**Symptom.** A per-zone segment trace diverges hard and permanently at the
frame the player touches one specific object, with a huge error mass and no
warning beforehand. The player's *physics* is fine on both sides -- positions
and speeds agree to within a pixel across the touch -- but `rings` takes a
large one-frame step in the ROM that the engine never takes, and
`player_animation_id` / `player_mapping_frame` snap to values the engine
produced from an entirely different ROM routine. Everything after cascades.

**Root cause.** Several S3K objects branch on **inventory that lives outside
the level** -- `Chaos_emerald_count`, `Super_emerald_count`,
`Collected_special_ring_array`, `SK_alone_flag`. `Obj_SSEntryRing`'s collision
arm `loc_6170A` (`docs/skdisasm/sonic3k.asm:128283-128291`) is the canonical
case:

```
        cmpi.b  #7,(Chaos_emerald_count).w
        bne.s   loc_6173A          ; fewer than 7 -> special-stage capture
        tst.w   (SK_alone_flag).w
        bne.s   loc_61794          ; S&K alone -> claim 50 rings
        bsr.w   SSEntry_CheckLevel
        beq.s   loc_61794          ; an S3 level -> claim 50 rings
        cmpi.b  #7,(Super_emerald_count).w
        beq.s   loc_61794          ; S&K level, all Super Emeralds -> 50 rings
```

`loc_61794` (`:128318-128327`) does `moveq #50,d0 / jmp (AddRings).l` and
`bset #5,$38(a0)` so the ring retires itself. `loc_6173A` (`:128290-128295`)
instead writes `mapping_frame = 0`, `anim = $1C`, `object_control = $53`.
`SSEntry_CheckLevel` (`:128433-128443`) returns 1 for `Current_zone >= 7` or
exactly 4, else 0.

A per-zone **segment** fixture is a slice out of the middle of a long movie.
Its `metadata.json`, its `physics.csv` and its frame `-1` bootstrap aux events
carry position, RNG, oscillator phase and frame counters -- but **no inventory
counters**. So the engine starts the segment with an empty save state and takes
the low-inventory arm of every such branch, however faithfully the branch
itself is ported.

**What to check.** Before opening an object's implementation, check whether the
ROM branch reads a global that survives across zones. If it does, and the
fixture is a segment (`segment_index` present in `metadata.json`, a
`run_manifest.json` in the parent run directory), the object is probably
correct and the **bootstrap** is the gap. Instrument the predicate and print
each term rather than assuming which one failed -- in the measured case the
engine's zone test, its S3-half test and its Super-Emerald test were all right
and only `hasAllEmeralds()` was false.

**Do not** repair it by keying on the run id, the fixture name, the zone or a
frame index -- that is a route carve-out. The value is usually recoverable from
the parent `run_manifest.json`, whose transitions carry `emeralds_before` /
`emeralds_after` and `rings_before` / `rings_after`, but seeding an inventory
counter from recorded data is gameplay-state hydration and a hard-rule-4
policy decision, not something to land inside a trace round.

**The readout trick that identifies the object (see P62).** When the diverging
fields are player fields, the responsible object is still nameable: an
`object_state` aux row whose `object_code` **changes** on the divergence frame
is the ROM rewriting that slot's code pointer. Here slot 4 went
`0x00061682 -> 0x0001ABB6` on the touch frame and vanished the next.
`0x0001ABB6` is `Delete_Current_Sprite` (`sonic3k.asm`, immediately before
`Delete_Referenced_Sprite` and `Draw_Sprite`), so the *previous* code is the
identity you want. Bracket a raw address by grepping the disassembly for
`loc_<hex>` / `sub_<hex>` labels either side of it and counting instruction
bytes inward.

**Corollary worth keeping.** Both arms of such a branch write distinct,
compared values, so the fixture tells you which arm each side took without any
instrumentation at all: the ROM's `rings +50` is `loc_61794`, and the engine's
`anim = $1C` with `mapping_frame = 0` is `loc_6173A`. Look for a branch whose
two arms write *different compared fields* before writing any probe.

**Cross-game.** S1's `Chaos_emerald_count` / S2's `Emerald_count` gate
end-of-act and special-stage behaviour the same way, and S1/S2 complete-run
segment fixtures have the same bootstrap shape.

**Originating investigation.** MGZ Sonic+Tails segment, frame 17383 (3410 of
3446 errors); see `docs/status/trace-frontier-log.md`, 2026-08-15.

## P64 -- S3K Tails' off-screen "landed on something" respawn compares against a *zero* latch

`sub_13EFC` (`docs/skdisasm/sonic3k.asm:26816-26843`) is Tails' off-screen
watchdog. Its usually-quoted arm is the 5-second timer (`cmpi.w #5*60`), but the
arm that actually fires in practice is the other one:

```
        tst.b   render_flags(a0)
        bmi.s   loc_13F28              ; ON-screen: flight_timer = 0, then latch refresh
        btst    #Status_OnObj,status(a0)
        beq.s   loc_13F18              ; not on an object: just tick the timer
        movea.w interact(a0),a3
        move.w  (Tails_CPU_interact).w,d0
        cmp.w   (a3),d0                ; word 0 of the stood-on object SST = its CODE POINTER high word
        bne.s   loc_13F24              ; ANY difference -> sub_13ECA, immediately
```

Three things trip engine ports here:

1. **There is no "latch is armed" precondition.** `Tails_CPU_interact` is zeroed
   with the whole CPU block at level init (`clearRAM Tails_CPU_interact,$100`,
   `:5415,7621`), and the refresh at `loc_13F2E` only runs on a frame where
   Tails is *already* on an object. So the **first** off-screen on-object CPU
   frame always compares `0` against a live code word (>= `$0003`) and
   mismatches. Tails landing on anything at all while off-screen sends him
   straight to `sub_13ECA`. Suppressing that as "unarmed" is an engine
   invention, and it silently defers the respawn by however long the trace runs.
2. **`sub_13ECA` is a sentinel, not a physics event** -- `x_pos = $7F00`,
   `y_pos = 0`, `Tails_CPU_routine = 2`, `object_control = $81`,
   `status = 1<<Status_InAir` (`:26800-26809`). In a trace this reads as a wild
   `x`/`y` jump with the sub-pixels **unchanged** (`move.w` touches only the
   integer word), which is why it can first surface as a `tails_x_sub`
   mismatch. If a sidekick divergence reports `x` expected `0x7F00` and `y`
   expected `0x0000`, stop looking at physics: that is this respawn.
3. **The compared value is the code pointer high word, not the object id.** S2's
   equivalent compares `id(a3)`; S3K compares a *pointer*. An engine object must
   publish it (`RomObjectCodePointerProvider`), and an object that does not is
   invisible to the compare -- the branch cannot fire however correct the
   surrounding logic is. Check the provider before concluding the branch model
   is wrong; a probe at the decision site distinguishes the two in one run.

The high word is stable across an object's own code-pointer rewrites as long as
they stay in one bank: `Obj_FBZDEZPlayerLauncher` cycles `loc_3B97A` ->
`loc_3BA4A` (`sonic3k.asm:79408,79416`), both `$0003xxxx`, so its published word
is `$0003`.

**Originating investigation.** FBZ1 Sonic+Tails segment, frame 116 (frontier
`116 -> 180`); see `docs/status/trace-frontier-log.md`, 2026-08-15.

---

## P65 -- A spawn-frame fallthrough runs the object's Animate tail too, not just its physics

**Symptom.** A player-substitute object's position, velocity and sub-pixels match
the recording from row 0, but its `mapping_frame` runs exactly one *executed*
frame behind for the whole stage. The errors land on a fixed period (every N-th
row, `frame_span: 1`) and never drift, and the emitted script is the correct one
-- a phase offset, not a wrong animation.

**Mechanism.** S3K's bonus-stage player `Obj_Sonic_RotatingSlotBonus`
(`docs/skdisasm/sonic3k.asm:98655`) dispatches its routine and then, at
`loc_4B97C` (`:98669-98671`), **unconditionally** does `move.b #2,anim(a0)` and
`bsr.s sub_4B99E` -> `Animate_Sonic`/`_Tails`/`_Knuckles` (`:98679-98695`). Its
routine-0 handler `loc_4B9CE` (`:98710-98741`) has no `rts` and falls straight
through into the movement dispatcher, so the object's **creation call** performs
a whole physics tick *and* the animation tail. On that call `anim` ($02) differs
from the `prev_anim` left by `clearRAM Object_RAM` (`:7619`), so
`Animate_Sonic`'s change branch (`:24741`) clears `anim_frame`/
`anim_frame_timer` and falls through to `loc_12A2A`'s
`subq.b #1,anim_frame_timer(a0)` (`:25151`), which underflows at once: the first
script frame is published and the timer is loaded with
`($400 - |ground_vel|) >> 8` on that same call.

That creation call is the one-shot `Process_Sprites` pass `Level:` runs at
`loc_6468` (`:7849-7855`) **before** `LevelLoop` first increments
`Level_frame_counter` (`:7885-7889`), so it precedes the first recorded row of a
bonus segment (whose row 0 already carries `gameplay_frame_counter == 1`).

**The trap.** An engine bootstrap that reconstructs this represented pass will
naturally model the *physics* half -- it is the visible one, since without it
`y_speed` is half the recorded value on row 0. The animation half is invisible
at row 0 (the published frame is script index 0 either way) and only shows up as
a one-frame phase error on the *next* script step, hundreds of rows of identical
values later.

**What to check.** For any object whose routine-0 handler falls through, port
the **whole** invocation, including whatever the object's shared tail
(`Animate_*`, `Perform_Player_DPLC`, `Draw_Sprite`) does after the routine
dispatch. Read past the `jsr ...Index(pc,d1.w)` to the end of the object's entry
point before deciding what a spawn frame does.

**Why a "+1 frame" fix would have been wrong.** The delay is not a constant: it
is `($400 - |ground_vel|) >> 8` evaluated at the spawn call, where `ground_vel`
is still 0. Modelling the pass reproduces both the value and the phase for any
recording; adding one to a duration reproduces neither.

**Cross-game.** S1/S2 have the same "one `Process_Sprites`/`ObjectsLoad` pass
before the level loop" shape, and the same rule applies to any engine
reconstruction of it.

**Originating commit.** S3K Sonic+Tails Slots bonus segment, frontier
`5 -> 913`, 900 -> 829 errors; see `docs/status/trace-frontier-log.md`,
2026-08-15.

---

## P66 -- Whether a spawned child runs on its spawn frame is decided by its SLOT, and it is worth exactly one frame of every countdown it owns

**Symptom.** An object's timed effect -- a ring award, a drop, a detonation --
lands consistently one frame later (or earlier) than the ROM, with every position,
velocity and animation field byte-identical. In a trace it prints as a lone
counter/score field with nothing else diverging, and the delta stays constant
because the parent re-spawns on a fixed cadence.

**Mechanism.** The main object pass walks slots in ascending index order.
`AllocateObject` (`docs/skdisasm/sonic3k.asm:37911-37914`) scans
`Dynamic_object_RAM` forward from its first slot, so a child can land **either
side** of its parent. Above the parent's slot, the walk has not reached it yet and
the child runs its routine 0 **in the same frame it was created**; below, it has
already been passed and does not run until the next frame. A child whose routine 0
is a countdown therefore expires at `spawn + N - 1` or `spawn + N` purely by slot
position -- `Obj_SlotRing` seeds `$40` to `$1A` (`:99482`) and decrements once per
tick (`subq.w #1,$40(a0) / bne.w Draw_Sprite`, `:35883-35884`), and lands on
spawn+25 because the cage occupies slot 4 while every ring allocates into 5+.

**What to check.** Do not hardcode either answer, and do not infer it from one
fixture: compare the child's allocated slot index against the parent's, using the
engine's own ROM-modelled allocation. `ObjectManager.isSlotAlreadyExecutedThisFrame`
already exists for objects on the normal dispatch; runtimes that tick their objects
themselves must make the same comparison explicitly. The fixture's `slot_dump` aux
gives you the ROM's answer directly -- read it before theorising.

**The trap that makes this expensive.** Getting it wrong is worth one frame, and
one frame is also what a mis-phased frame-counter gate costs. Two such errors of
opposite sign cancel completely in whichever recording happens to start on the
right parity, so the class goes green over both defects and a second fixture on
the other parity shows them **added**. If a spawn-frame-dispatch change moves a
green class by one frame, look for the phase error it was cancelling rather than
reverting.

**Originating commit.** `<pending: S3K slots bonus ring-award cadence>`; see
`docs/status/trace-frontier-log.md`, 2026-08-15.

**Cross-game.** S1's `FindFreeObj` and S2's `FindFreeObj`/`FindNextFreeObj` have
the same lowest-free semantics and the same ascending pass, so the rule is
universal; only the allocator names differ.

---

## P67 -- `Level_frame_counter` is incremented BEFORE the object pass, so a ported gate needs the object-visible value

**Symptom.** An object gated on `btst #n,(Level_frame_counter+1).w` does the right
thing at the right rate but on the wrong parity/phase, so everything it drives is
consistently one frame out.

**Mechanism.** Every ROM main loop does `Wait_VSync` then
`addq.w #1,(Level_frame_counter).w` then `Process_Sprites`
(`docs/skdisasm/sonic3k.asm:10742-10744`, `:63207-63209`; the level and
competition loops have the same shape). Objects running this frame therefore read
the **already-incremented** value, which is also what a recorder sampling per frame
writes into `gameplay_frame_counter`. The engine advances its counter in
`LevelManager.update()`, *after* object execution, so `getFrameCounter()` during an
object update is one below the ROM's. This is why the engine's ported gates read
`getFrameCounter() + 1` -- `LevelManager`:922 passes exactly that into
`ObjectManager.update`, and `CnzBumperObjectInstance`,
`AizFallingLogObjectInstance`, `PointPokeyObjectInstance`,
`Sonic1SpinPlatformObjectInstance` and others follow it.

**What to check.** Any new `Level_frame_counter` gate must read the object-visible
value, and any *local* counter standing in for it is suspect on two counts: it is
seeded at some arbitrary phase, and it does not stall on lag frames the way the ROM
counter does (a lag row repeats the counter while the V-blank count advances).
A free-running `counter++` passed down from a coordinator is the shape to grep for.

**Cross-game.** Identical in S1 and S2 (`Level_frame_counter` / `v_framecount`),
same loop ordering.

**Originating commit.** `<pending: S3K slots bonus ring-award cadence>`.

---

## P68 -- an early `rts` inside a ROM routine also skips that routine's TAIL, including a tail the engine hosts in another class

**Symptom.** One frame near a state change does the right thing for position or
velocity but *also* performs an interaction the ROM did not, so a re-arm, a pickup
or a trigger fires exactly one frame early and everything after it is phase-shifted
by one step.

**Mechanism.** A ROM routine's terminating `bsr`/`jsr` is not a separate pass: it is
the last thing the routine does, and **every** earlier `rts` or `bra locret_*`
suppresses it. `sub_9580` (`docs/skdisasm/sonic3k.asm:11914-12080`) is the worked
case. Its bumper different-cell unlock, `loc_96CE`, is
`move.w d2,(Special_stage_velocity).w / rts` (`:12037-12039`) -- an `rts`, where its
same-cell siblings `loc_96F2`/`loc_96F8` `bra` into the shared tail at `loc_96FA`.
So that frame skips the position update *and* `bsr.s sub_972E` (`:12078`), the cell
check. The player neither moves nor interacts, which is precisely what lets the ROM
re-arm on the same bumper one frame later, from the far side of the sphere. Two
other exits do the same: the fade rotation's `rts` (`:11921-11922`) and the mid-turn
`bne.w locret_972C` (`:11949`).

**What to check.** When the engine splits a ROM routine across classes -- a "player"
object holding the movement and a "manager" holding the routine's tail call -- the
manager's gate reproduces only the tail's *own* conditions
(`tst.b jumping / bmi`, `tst.b clear_routine / bne`) and silently loses every
earlier exit. Enumerate the routine's `rts` / `bra locret_*` sites and have the
first half report whether execution reached the tail, rather than re-deriving each
exit's condition at the call site. A gate that returns `null`/`false` to mean "skip
the position update" is the tell: the same exit almost certainly skips more.

**Cross-game.** The shape is universal wherever a routine ends in a `bsr` to its
collision/interaction helper; S1's `Sonic_Loops`-style tails and S2's
`SSPlayer_*` movement routines have the same structure.

**Originating commit.** `<pending: S3K special-stage bumper cell-check skip>`;
`TestS3kSonicTailsSs7SpecialStageTraceReplay` 26 errors -> green,
`…Ss3…` 113 -> 45. See `docs/status/trace-frontier-log.md`, 2026-08-16.

---

## P69 -- modelling `object_control` with the engine's CONTROL LOCK freezes the recorded `Ctrl_1_logical` word the sidekick follows

**Symptom.** A CPU sidekick reacts to the leader's button press exactly one frame
late (or not at all) while the leader is being held by an object. The report shows
a one-frame `tails_cpu_ctrl2_held` / `tails_cpu_ctrl2_pressed` divergence with a
long tail of `tails_x/y/x_speed/y_speed/rolling/status_byte` errors behind it, and
it is tempting to read that as a follow-delay phase bug.

**Mechanism.** `Ctrl_1_locked` and `object_control` are different bytes with
different effects. `Obj01_Control` copies `Ctrl_1 -> Ctrl_1_logical` and only *then*
tests `btst #0,object_control(a0)` (`docs/skdisasm/sonic3k.asm:21968-21973`), so an
object-controlled player still refreshes the logical pad word every frame, and
`Sonic_RecordPos` (`:22132`) writes that live word into `Stat_table`. The sidekick
reads it back 16 entries later at `Pos_table_index - $44`
(`loc_13DA6`/`loc_13DD0`, `:26682-26700`). `Ctrl_1_locked`, by contrast, makes the
engine's ROM-faithful short-circuit in `AbstractPlayableSprite.setLogicalInputState`
skip the copy, so the recorded word freezes at whatever it held when the lock was
set -- and 16 frames later the sidekick replays a word that never happened.

**What to check.** When an object holds a player, port the exact byte the ROM
writes. `sub_4A428`'s Pachinko magnet-orb capture (`:97086-97097`) writes only
`move.b #1,object_control(a1)`; its release tail (`:97024-97042`) clears
`object_control` bits 0-1. Neither touches `Ctrl_*_locked`, so neither may call
`setControlLocked`. Movement suppression is already carried by
`ObjectControlState`; reaching for the control lock as well is what breaks the
follow replay. The tell is a leader history entry whose value matches **no** BK2
row in the window -- a latch, not a lag.

**Cross-game.** S2 `Obj01_Control` has the same ordering (`s2.asm:35933-35935`),
and the S2 sidekick reads the same delayed `Stat_table`, so any S2 object capture
modelled with a control lock has the identical defect.

**Originating commit.** `<pending: S3K Pachinko magnet-orb control-lock removal>`;
`TestS3kSonicTailsPachinko3BonusTraceReplay` 41 errors -> 22,
`…PachinkoBonus…` 2280 -> 2166, `…Pachinko2Bonus…` 2120 -> 1969. See
`docs/status/trace-frontier-log.md`, 2026-08-16.

**Second instance, same stage, different object.** `Obj_PachinkoEnergyTrap`'s
`sub_49FE4` (`:96640-96678`) writes only `move.w y_pos(a0),y_pos(a1)`,
`move.b #$81,object_control(a1)` and `bset #Status_InAir,status(a1)`. The engine
also called `setControlLocked(true)`, zeroed `x_vel`/`y_vel`/`ground_vel`, cleared
the on-object bit, and wrote Y through the sub-pixel-resetting setter. Note the
generalisation: **the invented writes travel in a pack**, and the velocity clears
are as damaging as the lock — a zeroed `ground_vel` also changes the rolling
animation's frame duration, which surfaces as a `player_mapping_frame` oscillation
that looks like a separate animation defect. `TestS3kSonicTailsPachinko3BonusTraceReplay`
22 -> green.

## P70 -- an object's per-player subroutine is called once per player, and the
main-player-only part is only the tail after `cmpa.w #Player_1,a1`

**Symptom.** A `tails_*` field diverges at the object's own coordinate (for example
`tails_y` expected exactly the object's `y_pos`) while the equivalent `player_*`
field is correct, and the object's engine class takes a single `PlayableEntity`.

**Mechanism.** The S3K idiom is a main routine that does
`bsr sub_X / lea (Player_2).w,a1 / bsr sub_X`, with the subroutine branching on
`cmpa.w #Player_1,a1` only for the *global* consequences. `Obj_PachinkoEnergyTrap`
`loc_49FD6` (`docs/skdisasm/sonic3k.asm:96634-96637`) is the clean example: both
players are snapped to the beam and given `object_control = $81`, and only the rise
latch, the exit countdown and the level restart are gated on Player 1
(`:96665-96677`). `Obj_PachinkoMagnetOrb` `loc_4A408` (`:96943-96949`) has the same
shape with no Player-1 gate at all.

**What to check.** Port the subroutine, then run it over
`services().playerQuery().playersFor(MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)`
rather than over the single `update()` participant, and pass down an
`isMainPlayer` flag for the tail. Check at the same time whether the ROM has a
capture *latch*: `sub_49FE4` has none — a player outside the band returns at
`locret_4A078` with nothing written — so an engine `capturedPlayer` field that
keeps applying the capture is a second invention.

**Originating commit.** `<pending: S3K Pachinko energy-trap capture port>`;
`TestS3kSonicTailsPachinko2BonusTraceReplay` first error frame 137 -> 242,
1725 -> 1605 errors; `…PachinkoBonus…` 1743 -> 1698.

## P71 -- state the ROM re-establishes on the LEVEL SPAWN, restored by the engine only on the way back out

**Symptom.** A gameplay effect that depends on a persistent player power-up simply
never happens inside a bonus/special sub-level: rings are not attracted, a fire dash
does not fire, a bubble bounce does not bounce. The trace symptom is usually a
`rings` off-by-one many frames after the real event, because the missing effect only
becomes observable when a scoring branch runs.

**Mechanism.** S3K stores the player's elemental shield across a level reload in
`Saved_status_secondary` / `Saved2_status_secondary`, and re-gives it in
`SpawnLevelMainSprites_SpawnPowerup` (`docs/skdisasm/sonic3k.asm:8264-8290`), which
runs on EVERY level spawn. The bonus zones are not exceptions to it -- they have
their own explicit arms into the restore (`cmpi.b #$13,(Current_zone).w / beq
loc_69E0`, and the same for `#$14`, :8270-8273), so the shield is live for the
DURATION of the bonus stage. The engine had captured the value at entry and consumed
it only when returning to the level, which reads as a plausible "save and restore"
pair and is half the ROM's behaviour.

**What to check.** When the engine models a ROM `Saved_*` variable, find every ROM
READER of it, not just the one that matches the engine's save/restore intuition. A
value written on the way in and read on the way out is the shape you expect; a value
written on the way in and read by the *destination's own spawn code* is the shape
that gets missed. Here `loc_2D4CA` (:61925-61930) writes both saved slots, the
bonus spawn consumes `Saved_status_secondary` (clearing it), and the return-to-level
spawn consumes `Saved2_status_secondary` -- two readers, two restores.

**Cross-game.** S1/S2 have no elemental shields, but the pattern -- a destination
level spawn re-establishing state the engine only restores on the return trip -- is
game-agnostic.

**Originating commit.** `<pending: S3K bonus-stage entry shield restore>`;
`TestS3kSonicTailsPachinkoBonusTraceReplay` 1698 errors -> 1692, first error frame
122 `rings` -> 180. See `docs/status/trace-frontier-log.md`, 2026-08-16.

## P72 -- objects placed before `LevelLoop` have already run once when the first frame is recorded

**Symptom.** An object-owned countdown fires exactly one gameplay pass late, for the
object's whole life, with everything else in the level aligned. If the object writes a
player field the fix looks like a one-pixel physics drift; it is not physics at all.
The classic tell is a trace error whose expected/actual differ by the object's own
per-frame step (1 for a `subq.w #1,y_pos`), starting at the exact frame the countdown
constant predicts.

**Mechanism.** S3K's level-init path runs a full object pass BEFORE the main loop
exists. After `SpawnLevelMainSprites` (`docs/skdisasm/sonic3k.asm:7849`) it calls
`Load_Sprites`, `Load_Rings`, `Draw_LRZ_Special_Rock_Sprites` and then
`Process_Sprites` once (`:7853`), and only afterwards does `LevelLoop` (`:7885`) begin
`addq.w #1,(Level_frame_counter).w` / `Process_Sprites` (`:7889`, `:7894`). So every
object placed by `SpawnLevelMainSprites` executes once at `Level_frame_counter == 0`,
before any recorded row. An object whose Init falls through into its main body with no
`rts` -- `Obj_PachinkoEnergyTrap` does `move.l #loc_49F5C,(a0)` and drops straight into
`loc_49F5C` (`:96602-96612`) -- burns a countdown tick on that pass too.

**What to check.** For any object placed at level init rather than by the layout
cursor, ask whether the engine gave it the pre-`LevelLoop` pass. A bootstrap that
"reconstructs the state after the setup pass" and then discards the pending pass
authority reconstructs the *placement* but not the *execution*, and the object starts
one pass behind. Do NOT close the gap by initialising the counter one lower: run the
represented pass for the object, or the value desyncs the first recording whose
countdown you did not measure.

**How to prove it without the engine.** The ROM counter, not the row index, is the
invariant. Find the row where the behaviour first appears in two different fixtures and
compare `gameplay_frame_counter`, not `frame`: for the Pachinko trap both fixtures step
`y_pos` first where the counter reads `0x00F0` (= `4*60`, the ROM's `move.b #4*60,$25(a0)`
at `0x49F4C`: `117C 00F0 0025`), at row 240 in one and row 242 in the other. Two
fixtures agreeing on the ROM counter and disagreeing on the row index is a derivation;
one fixture's row index is a fit.

**Cross-game.** S1 and S2 declare `InitialProcessSpritesLifecycle.NONE` -- they have no
equivalent pre-loop pass -- so this is S3K-scoped. The general lesson (an object placed
outside the main loop may have executed before frame 0) is not.

**Originating commit.** `<pending: Pachinko trap represented setup pass>`;
`TestS3kSonicTailsPachinko2BonusTraceReplay` 1605 errors -> 1418, first error frame 242
`tails_y` -> 469 `x_speed`. See `docs/status/trace-frontier-log.md`, 2026-08-16.

---

## P73 -- the on-object balance width is `width_pixels`, not the solid half-width and not the engine default

**Symptom.** A rider stands still on a solid object and the trace reports a
one-frame `animation_id` `0x0005` vs `0x0006` (plus its `mapping_frame`), which
self-heals immediately and looks like a transient worth ignoring. It is a real
object defect, and while it stands it MASKS every later frontier in the class,
because the comparator reports the lowest failing frame.

**Root cause.** When ground velocity reaches zero the ROM sets the standing
animation `#5` and then, if `Status_OnObj` is set, runs the balance test against
the interacting object's OWN `width_pixels(a1)` byte:

```
        move.b  width_pixels(a1),d1
        move.w  d1,d2
        add.w   d2,d2
        subq.w  #4,d2            ; Sonic: subq.w #2,d2
        add.w   x_pos(a0),d1
        sub.w   x_pos(a1),d1
        cmpi.w  #4,d1            ; Sonic: cmpi.w #2,d1
        blt.s   <balance left>
        cmp.w   d2,d1
        bge.s   <balance right>  ; else fall through, anim stays 5
```

Sonic's copy is `Sonic_Move` (`docs/skdisasm/sonic3k.asm:22460-22473`); Tails' is
`Tails_InputAcceleration_Path` (`:27820-27831`) with the shift of 4 rather than 2.
`width_pixels` comes from the object's `SetUp_ObjAttributes*` data block
(field order at `:176907-176912`) and is **independent of the `d1` the object
passes to `SolidObject*`**. The two genuinely differ: `Obj_ICZSegmentColumn`'s
segment has `width_pixels = $20` (`word_8ACEE`, `:188863-188864`; ROM image
`0x8ACEE: 02 80 20 10 0A 00`) while `sub_8AC70` passes `moveq #$2B,d1` to
`SolidObjectFull` (`:188811-188815`).

**Why it bites.** `AbstractObjectInstance.getBalanceWidthPixels()` defaults to the
top-solid `SolidObjectParams.halfWidth()`, or to `getOnScreenHalfWidth()`'s generic
16 for everything else. A full-solid object therefore silently balances on a 16px
half-width. With the ICZ column, `d1 = 16 + 14 = 30` against `d2 = 28` reads as
"past the right edge" where the ROM's `d1 = 32 + 14 = 46` against `d2 = 60` is
comfortably mid-platform.

**What to check.** For every rideable object, read the `width_pixels` byte out of
its attribute block (verify it in the ROM image -- it is one byte at a known
address) and override `getBalanceWidthPixels()` whenever it differs from whatever
the default would return. Do not reuse the solid `d1`, the top-landing half-width,
or the rendered footprint; all three are separate ROM quantities.

**Cross-game.** S1 `obActWid` (`docs/s1disasm/_incObj/01 Sonic.asm:340-351`) and
S2 (`s2.asm:36259-36271`) use the same byte for the same test, so the habit is
universal; only the edge margin differs per game and per character.

**Originating commit.** `<pending: ICZ segment-column balance width>`;
`TestS3kSonicTailsIczSegmentTraceReplay` 2862 errors -> 2860, first error frame
1983 `tails_animation_id` -> 2336 `player_animation_id` (the giant-ring emerald
boundary of known-discrepancies section 21). See
`docs/status/trace-frontier-log.md`, 2026-08-17.

---

## P74 -- a `subq #1 / bpl` countdown waits N+1 frames, and the branch that ARMS it does not publish

**Symptom.** An animation-driven layout/collision byte settles on its resting value
exactly one frame before the ROM does, thousands of frames into a stage, and the only
compared field that notices is whatever that byte gates -- here a `routine` bump.
Every earlier contact looks fine because the phase error only matters at a knife
edge.

**Mechanism, two halves, and they compound.**

*The countdown.* The 68000 idiom is pre-decrement and test-for-negative:

```
        subq.b  #1,2(a0)
        bpl.s   (return)        ; 0 is non-negative, so the reload of N buys
        move.b  #N,2(a0)        ; N waiting passes, then the (N+1)th steps
```

A reload of `#1` therefore steps every **2** passes, `#5` every **6**, `#7` every
**8**. An engine `if (--timer > 0) return; timer = N;` steps every **N**, so
transcribing the ROM's literal reload gives a period one short -- and "fixing" that
by storing `N+1` in the constant hides the real convention and leaves the constant
looking like a fitted number.

*The arming pass.* In S3K's slot machine the branch that claims a transient
animation slot (`loc_4BF30`, `sonic3k.asm:99283-99300`, and its ring/bumper/spike
siblings) writes only the type, the target byte's pointer and the restore id. It
does **not** publish the first animation frame. A slot released with
`clr.l (a0) / clr.l 4(a0)` (`:98511-98512`) starts with countdown 0, so the first
`sub_4B592` pass falls straight through and publishes frame 0 -- later the same game
frame, from `Slots_RenderLayout` (`:98159-98161`). Publishing frame 0 eagerly at
creation AND letting that frame's pass decrement the countdown spends the creation
pass twice, so every subsequent step and the terminating restore land one frame
early.

**What to check.** Model the idiom, not the period: initialise the countdown to 0,
`if (--timer >= 0) return;`, then reload with the ROM's own literal. Keep the arming
branch write-free unless the listing shows a store. And read the terminator: all four
handlers (`loc_4B5C2`/`loc_4B5F2`/`loc_4B65A`/`loc_4B626`, `:98420-98513`) publish a
table entry of `0` and only then write the resting id, so the restore costs one
further step beyond the last visible frame.

**Cross-game.** `subq/bpl` countdowns are everywhere in all three disassemblies; the
period-vs-reload trap applies to every one of them.

**Originating commit.** `<pending: slot transient-animation countdown>`;
`TestS3kSlotsBonusTraceReplay$SonicAndTails$Segment1` frame 5106
`routine 0x0002 vs 0x0004` -> GREEN, 5259 rows compared.

---

## `clr.b spin_dash_flag` is a BYTE write, and the engine splits that byte across three flags

**Symptom.** A player or sidekick that once passed through a spin tube, flipper or dash
trigger never decelerates again while rolling. Ground velocity is changed only by slope
gravity: it climbs on a slope and then sits perfectly flat on level ground, where the ROM
loses a few units per frame. The reported field is usually `x_sub` or `x` — a sub-pixel or
one-pixel drift — because those cross an integer boundary first, but `x_speed` and
`g_speed` diverge on the *same* frame and are the real origin.

**The ROM shape.** `spin_dash_flag` is one byte with several meanings: bit 0 is pinball
mode, bit 7 is the speed lock, and a nonzero value also marks a spindash charge.
`Obj_AutoSpin` writes `$01` or `$81` to it; the roll routines then test it at their own
entry and skip their whole body when bit 7 is set:

```
Tails_RollSpeed:
        ...
        tst.b   spin_dash_flag(a0)
        bmi.w   loc_14DF0          ; skip input, friction AND deceleration
```

(`docs/skdisasm/sonic3k.asm:28180-28181`; `Sonic_RollSpeed` at `:22935-22936` is identical.)
Every ROM site that releases the player writes the whole byte — `move.b #0,spin_dash_flag(a1)`
or `clr.b spin_dash_flag(a1)` — so all three meanings clear together.

**What to check.** The engine represents that one byte as `pinballMode`,
`pinballSpeedLock` and `spindash`. **Any port of a `clr.b`/`move.b #0` to `spin_dash_flag`
must clear all three.** Clearing a subset leaves a latch that no later code will remove,
and the symptom appears thousands of frames later in a completely different object's
neighbourhood — the AIZ ride vine's grab (`AIZRideVineHandle_CheckGrab`,
`docs/skdisasm/sonic3k.asm:46743`) cleared only the charge, and a lock set by a spin tube
~3,100 rows earlier survived to the end of the level.

**How to find it fast.** Put a stack probe on the *setter* and count transitions across the
whole segment. One `-> true` and no `-> false` in four thousand rows is conclusive on its
own, and takes one run. Do not start from the reported field: check whether `x_speed` and
`g_speed` diverge on the same frame, and if they do, the sub-pixel is downstream.

**Do not assume the constant is wrong.** The engine's rolling friction here was already
exactly right (`getRunAccel() / 2`, matching `Acceleration_P2 asr #1`). Instrument the
branch before touching a value — the defect was that a correct computation was being
skipped, and any "correction" to the friction number would have been a fitted model.

**Known remaining sites (audit, unverified).** `ForcedSpinObjectInstance` (S2),
`MGZDashTriggerObjectInstance` and `CnzCannonInstance` clear pinball mode and the charge
but not the bit-7 lock. Check each against its own ROM write before changing it.

**Originating commit.** AIZ ride-vine grab clears the full byte; S3K complete-emeralds
chain segment 6 `1,955 -> 0` errors (segment green).

---

## A grab-and-hold object drops a rider whose `render_flags` bit 7 is clear

**Symptom.** A player or CPU sidekick is captured by a grab object (vine handle, grapple,
pulley, parachute) and never gets off. The recorded row shows the ROM's rider frozen where
it was, `x_vel`/`y_vel` zero, and `y_vel` then accumulating the ordinary `0x38` gravity step
per frame while `x_pos` does not move at all — a plain resumption of normal physics from a
standstill — while the engine keeps writing the object's hold position and hold mapping
frame. Because the object's frame table keeps running, the *animation* and *mapping_frame*
divergences look like an animation defect rather than a lifetime one.

**The ROM shape.** These objects test the held player's render flag at their own entry,
*before* the routine check and *before* they read the buttons:

```
        tst.b   (a2)                    ; grab byte
        beq.w   ..._CheckGrab
        bmi.w   ..._ForcedRelease
        tst.b   render_flags(a1)
        bpl.w   ..._ReleasePlayer       ; rider not drawn last frame -> drop it
        cmpi.b  #4,routine(a1)
        bhs.w   ..._ReleasePlayer
```

`AIZRideVineHandle_ProcessPlayer` (`docs/skdisasm/sonic3k.asm:46486-46496`) and
`sub_266B0` (LBZ ride grapple) are the same routine shape. Bit 7 is written by the
*preceding* display pass, so the drop lands on the handle's next pass — natural one-frame
visibility, not a latency to model.

**The release target is the quiet one.** `AIZRideVineHandle_ReleasePlayer`
(`docs/skdisasm/sonic3k.asm:46548-46552`) is
`clr.b object_control(a1) / clr.b (a2) / move.b #$3C,2(a2) / rts`. It sits two lines below
`AIZRideVineHandle_ForcedRelease`, which *does* write `x_vel=$300`, `y_vel=$200` and
`Status_InAir` — reaching for the wrong one gives the rider a launch the ROM never
performs. The off-screen release writes no velocity, no status and no animation.

**What to check.** For any object that holds a player under `object_control`, enumerate
*every* branch out of its hold routine, not just the ones the player can trigger. The
engine had the forced eject, the hurt/dead release (standing in for `routine >= 4`) and the
jump release, and was missing only the render-flag one — invisible until a route carried a
CPU sidekick off the top of the screen while held. Use the existing playable-sprite
contract `hasRenderFlagOnScreenState()` / `isRenderFlagOnScreen()`; do not invent a
visibility test.

**Corollary — check the sibling.** `LbzRideGrappleInstance.updateHeldPlayer` already
modelled this exact predicate, with the same helper and the same ROM idiom, while
`AizVineHandleLogic` did not. When a grab object is missing a release branch, diff it
against every other grab object in the tree before deriving the branch from scratch.

**Originating commit.** AIZ giant ride vine off-screen release; S3K complete-emeralds chain
segment 6 `7,434 -> 1,955` errors, frontier f3339 -> f3887.

---

## The touch pass sets a flag; the object's OWN pass performs the reaction

**Symptom.** A ROM-correct knockback/reward is computed with the right magnitude and
angle, on the right frame, and the recorded row still shows a different value -- usually
one written by a *different* object. The engine's value is often the suspiciously round
one, because the overwriting object writes whole-pixel deltas.

**The ROM shape.** A `$C0`-category object's `Touch_Response` entry does nothing but set
`collision_property(a0)`. The reaction runs later, from the object's own SST slot pass.
`Obj_PachinkoItemOrb`'s converted reward is the canonical example: `loc_4A312`
(`sonic3k.asm:96843-96845`) and `loc_4A34C` (`:96866-96869`) both open with
`tst.b collision_property(a0) / bsr sub_4A362`, and `sub_4A362`/`sub_4A384`
(`:96874-96916`) then dispatch through `loc_61100`. `Obj_Bumper`'s pachinko branch
(`loc_32EF0`, `:68906-68908`) and the gumball items have the identical shape.

**Why the phase is load-bearing.** The engine's touch pass runs at the player's slot,
ahead of every object. Dispatching the reaction there puts it *before* every object that
executes after the player -- so any of them that writes the same player field wins. At
Pachinko `pachinko_2` row 469 the reward orb's `sub_61176` push (`muls.w #-$700`,
`:127882-127887`) is immediately overwritten by `Obj_PachinkoMagnetOrb`'s attraction step
(`loc_4A464`, `:96975-96986`, `sub.w x_pos(a1),d1 / asl.w #8,d1 / neg.w d1`). In ROM the
magnet is a LOWER slot (17 vs 25), so the push lands last and survives. Latch the touch
and dispatch from `update()`, at the ROM's position within the routine -- for `loc_4A34C`
that is *before* `MoveSprite2`.

**The tell in the fixture.** Every row written by an attraction/position-copy step has a
velocity that is an exact multiple of `0x100`, because the write is `delta << 8`. The one
row that is not is the row some other object wrote. Sorting the CSV that way names the
writer before any engine reasoning starts.

**Related, same routine.** `sub_4A384`'s `move.l #Delete_Current_Sprite,(a0)` (`:96888`)
is UNCONDITIONAL and its callers never inspect `d2`, so even the subtype-4 push deletes
the Pachinko orb -- unlike the gumball-machine path `loc_60F28`, whose delete is skipped
on `d2 = 0`. Reusing the gumball dispatch wholesale leaves a collected orb alive and
touch-eligible forever. Two adjacent ROM paths through one dispatch table can disagree
about deletion; read both callers, not just the table.

**Originating commit.** `<pending: Pachinko reward dispatch phase>`. Measured neutral on
the target class (the bonus-stage slot occupancy still orders the magnet after the reward
in the engine -- see `docs/status/trace-frontier-log.md`, 2026-08-16), and neutral across
`-Ptrace-replay` (29 red), `-Ptrace-replay-r7` (32 red) and the default sweep.

## S3K has no opt-in "remember" flag -- every layout entry is respawn-tracked, and `Delete_Current_Sprite` latches it forever

S1 and S2 make destruction persistence opt-in per layout entry (S1: bit 7 of the object-id
byte; S2: bit 15 of the layout Y word). **S3K does not.** `Load_Sprites` gives every
six-byte entry its own `Object_respawn_table` byte, unconditionally `bset #7,(a3)` on load
and unconditionally stores the address in `respawn_addr(a1)`
(`docs/skdisasm/sonic3k.asm:37745-37766`; the sibling loaders at `:37804`, `:37847`,
`:37892` do the same). Bit 15 of the S3K Y word is not a remember flag at all -- the
loader masks position with `d5 = $FFF` and takes render flags from bits 14/13 only
(`rol.w #3,d2 / andi.w #3,d2`).

Only the `Go_Delete_SpriteSlotted` family clears the bit again (`bclr #7,(a2)`,
`:179056-179061`), and that is the *alive-goes-offscreen* path. An object that ends
through plain `Delete_Current_Sprite` -- a rock smashed by the player
(`AIZLRZEMZRock_BreakFromTop` -> `AIZLRZEMZRock_Delete`, `:44022-44057`), a badnik that
became an explosion -- leaves bit 7 set and **never reloads for the rest of the level**.

Two consequences when implementing an S3K object:

- Do not gate persistence on the shared parser's `respawnTracked` flag; for S3K it is
  reading a bit the ROM ignores. The engine's S3K model is the placement controller's
  permanent-destroy latch, not the S1/S2 `remembered` set.
- The latch has to survive a giant-ring special-stage round trip. That entry sets
  `Respawn_table_keep = 1` (`:128409-128412`), which makes the reload skip the table wipe
  at `:37429-37438`, so the table is already populated when `Load_Sprites` first scans the
  entry window. Anything that re-establishes the state *after* the entry-window scan is
  one scan too late.

**Originating commit.** `<pending: S3K destroy-latch round trip>` -- AIZ1's rock at
`(0x1D00, 0x04A9)`, smashed before the segment-0 giant ring, came back intact on the
segment-2 return and blocked Sonic at `x = 0x1CDD` from frame 94. Chain frontier
80379 errors @ f94 -> 74575 @ f384.

## A routine that reads its own standing bits at entry has one dispatch of trigger latency

`Obj_CollapsingPlatform`'s trigger looks like "start the countdown when a player stands on
me", and porting it that way collapses the platform one frame early -- because the ROM does
not read the standing bits at contact time. `loc_20594` (`docs/skdisasm/sonic3k.asm:44819`)
tests `$3A`, and only if it is already set does it touch the `$38` countdown. It then falls
through to `loc_205A6` (`:44826-44830`), which reads `status(a0) & standing_mask` and sets
`$3A` -- **before** reaching `sub_205B6` (`:44835`), whose `SolidObjectTopSloped2` is the
thing that writes those bits.

So the read at the top of dispatch N observes what dispatch N-1's solid helper wrote. The
landing dispatch sets nothing; the next dispatch sets `$3A` and does not decrement; the one
after that is the first decrement. Fragments land on `landing + 9`, not `landing + 8`.

Generalise the shape, not the object: **whenever a routine both reads a field at entry and
writes it via a later sub-call in the same dispatch, the read is one dispatch stale, and a
port that reacts inside the contact callback loses that latency.** The engine's
`update()`-then-solid-pass split already mirrors the routine-body-then-`sub_205B6` order, so
the fix is to let the callback set only the flag and let the next `update()` act on it --
never a counter tweaked by one.

Watch for the compensations such an early trigger accretes downstream. This one had grown
three: a one-frame deferral of the CreateFragments slope skip, a `+1` on the post-collapse
solid-stay timer, and a saved-standing-bit escape hatch letting P1 re-acquire during the
deferral. Each was individually plausible and each was absorbing the same frame. Removing
the trigger defect alone made segment 4 of the S3K chain **worse** (292 -> 50,060 errors);
the pair, plus the timer, restored 292 exactly. Two wrongs that cancel come out together.

**Originating commit.** `<pending: S3K collapsing-platform trigger latency>` -- AIZ act 2
(`aiz_4`, chain segment 6), where the ROM seats Tails on the platform at frame 118 on the
dispatch the engine had already fragmented. Frontier f118 `sidekick_y` -> f150.

## A player rides exactly one object, and re-seating clears the previous object's standing bit

`RideObject_SetRide` (`docs/skdisasm/sonic3k.asm:42027-42046`) is the tail every top-solid
landing goes through, and it opens by *un-seating* the player from whatever they were on:

```
RideObject_SetRide:
        btst    #Status_OnObj,status(a1)
        beq.s   loc_1E4A0
        movea.w interact(a1),a3
        bclr    d6,status(a3)          ; clear the PREVIOUS object's standing bit
loc_1E4A0:
        move.w  a0,interact(a1)
        ...
        bset    d6,status(a0)          ; set THIS object's standing bit
```

So an object's own standing bit is not private state it can rely on across frames. Any
other solid object that seats the same character clears it. And because objects run in slot
order, **the lower-slot object wins the read the higher-slot object depends on**: where two
solids overlap one character, the lower slot clears the higher slot's bit every frame,
before the higher slot's routine gets to read it.

Measured instance: AIZ act 2 has two collapsing platforms whose spans overlap Tails. Slot 05
has already fragmented and sits in `loc_205DE`, which still calls `SolidObjectTopSloped2`;
slot 08 is intact. Per frame, slot 05 clears slot 08's bit and slot 08 re-sets it. A
V-blank sample therefore shows slot 08's bit **set** for 35 straight frames while slot 08's
own `loc_20594` reads it **clear** on every one of those entries, so its `$3A` trigger never
latches and its `$38` countdown never starts. Slot 08 fragments 35 dispatches after Tails
lands; nineteen P1 landings elsewhere in the corpus fragment after 9.

Two things to take from it when implementing any S3K object that reads its own standing bit:

- **Do not model the standing bit as per-object state the object controls.** It is one
  shared "who is this character riding" relation, and the object's bit is a projection of
  it. An engine that gives each object an independent flag will latch triggers the ROM never
  latches — and the symptom appears in the *other* object, frames later.
- **A V-blank-sampled fixture cannot see this.** The recorder samples after every object has
  run, so it records the last writer's value. Only a routine-entry read shows what the
  object's own code saw. When a recorded bit and an object's behaviour disagree, suspect
  intra-frame ordering before suspecting the object.

**Originating commit.** `<pending: S3K single-rider standing-bit invariant>` — S3K
complete-emeralds chain segment 6, frame 150. See `docs/status/trace-frontier-log.md`,
2026-08-20, for the PC-execute and write-hook probe output naming both writers.

### Follow-up: the engine had every piece of state right and still got it wrong

Worth reading with the entry above, because the fix was not where any of it pointed. Three
plausible engine defects were proposed and each was refuted by a probe: that the engine
stopped re-claiming the rider (it does re-claim, every frame, in slot order); that it never
cleared the previous object's bit (it does, twice per frame); that it batched all object
updates before all solid passes (the interleaving is already ROM-correct).

The defect was that the object's own trigger was a **sticky flag set from the contact
callback** rather than a **fresh read at dispatch entry**. `loc_205A6` re-reads
`status(a0) & standing_mask` every dispatch; a latch taken when the contact happens can
never observe a clear that arrives afterwards.

**So when porting any ROM routine that reads a field at its own entry, port the READ, not
the event that last wrote the field.** They differ exactly when something else writes
between the two, which is precisely the case the trace catches and code review does not.

## The standing bit selects the surface formula, not just the trigger — and the two differ by a pixel

A sloped solid seats a player through one of two routines, and **which one runs depends on
the object's own standing bit**, the same bit any other solid can clear out from under it
(see the single-rider entry above):

| path | routine | formula |
|---|---|---|
| fresh landing | `RideObject_SetRide` / `loc_1E45A` (`docs/skdisasm/sonic3k.asm:42004-42019`) | `surface - y_radius - 1` |
| continued ride | `SolidObjSloped2` / `loc_1E260` (`:41744-41752`) | `surface - y_radius` |

The fresh-landing formula looks relative but is not: `d0 = surface - (y_pos(a1) + y_radius
+ 4)` then `d2 = y_pos(a1) + d0 + 3`, so `y_pos(a1)` cancels and it resolves to
`surface - y_radius - 1`. The continued-ride path writes `y_pos(a0) - slope[d0] - y_radius`.
**They are one pixel apart, permanently.**

The consequence is easy to miss and hard to debug: a player standing still on an unchanging
slope can move one pixel purely because the object *re-landed* them instead of *carrying*
them, or vice versa. Measured instance: two overlapping AIZ collapsing platforms, where the
lower slot clears the higher slot's bit every frame, so the higher slot re-lands the sidekick
fresh every frame; the moment the lower one stops being solid the bit survives, the seat
becomes a continued ride, and the sidekick drops a pixel with nothing else having changed.

When porting a solid object, therefore:

- **Route the two formulas on the object's standing bit as the ROM does**, not on an engine
  "who is riding what" record. Those agree only while nothing else touches the bit.
- **A one-pixel `y` on a stationary player is a routing symptom**, not a rounding one. Check
  which of the two routines wrote the value before touching any arithmetic. A write hook on
  the player's `y_pos` logging PC and `a0` names it immediately — same writer, different PC.

**Originating commit.** `<pending: S3K f167 handover>` — see `docs/status/trace-frontier-log.md`,
2026-08-20, for the PC evidence and the unresolved engine-routing contradiction.

## A per-player release must not withdraw the other player's solid pass from the same dispatch

ROM object routines that release a rider do it **after** the dispatch's solid pass, and the
solid pass covers both players in one call. `loc_205DE` (`docs/skdisasm/sonic3k.asm:44850-44859`)
is the canonical shape:

```
loc_205DE:
        bsr.w   sub_205B6          ; SolidObjectTopSloped2 -- BOTH players seated here
        subq.b  #1,$38(a0)
        bne.s   locret_2061E
        move.l  #loc_20620,(a0)    ; object stops being solid from the NEXT dispatch
        lea     (Player_1).w,a1
        moveq   #p1_standing_bit,d6
        bsr.s   sub_205FC          ; release P1
        lea     (Player_2).w,a1
        moveq   #p2_standing_bit,d6
                                   ; falls through: release P2
```

The engine splits that one pass into a **per-player** solid callback. So an engine object
that flips its own "no longer solid" state inside Player 1's release callback silently
withdraws the pass Player 2 was entitled to on the same dispatch — the ROM had already
seated them before the release was even considered.

The symptom is not a missing collision. It is the *next* object in the batch taking a
different path: the player it should have re-landed becomes a continued ride instead (see
the standing-bit/formula entry above), and the sidekick moves one pixel a frame early. Nothing
looks broken at the release site; the damage shows up in a different object.

When porting any ROM routine of this shape:

- **Keep the whole dispatch solid.** Mark the release dispatch with a flag set when the
  release fires and cleared at the top of the next `update()`, and report solid while it is
  set. The ROM's guarantee is per-*dispatch*, not per-*player*.
- **Do not gate that on "was already riding".** `sub_205B6` is unconditional; a rider record
  the engine happens not to hold is not a ROM predicate.
- **Check the release's own guard mirrors `sub_205FC`'s `btst d6,status(a0)`.** A release
  loop that runs for both players releases neither one that the object does not actually
  hold the standing bit for.
- **Suspect this whenever a two-player trace diverges on the frame an object stops being
  solid, and the diverging player is the one the engine processes second.** Log the object's
  per-player solid callbacks with identity, state and pending-release: the missing callback
  is visible immediately.

**Originating commit.** S3K complete-emeralds segment 6, first non-camera mismatch
f167 -> f3245. See `docs/status/trace-frontier-log.md`, 2026-08-20.

## Port every field a ROM state block writes — an omitted clear can fire hundreds of frames later

When a ROM routine installs a state by writing a block of player fields, porting "the fields
that obviously matter" is not enough. `Tails_Catch_Up_Flying`'s `loc_13B50`
(`docs/skdisasm/sonic3k.asm:26498-26529`) writes velocities, `status`, radii, the flip
selector, `double_jump_flag`, `object_control`, facing, animation — and also:

```
move.b  d0,spin_dash_flag(a0)
move.b  d0,spin_dash_flag(a0)     ; ROM writes it twice; harmless, but it IS written
move.w  d0,spin_dash_counter(a0)
```

The engine's port had every other field and omitted those two. Nothing failed for **769
frames**, because `spin_dash_flag` is only read at the top of `Tails_Spindash`
(`:28696`), which runs solely from the grounded routine — and the whole recovery flight is
airborne. The charge sat in RAM across the entire flight and detonated on the first grounded
frame after landing: `doUpdateSpindash` saw the down button released, took the release path,
and read the speed table at index `(counter >> 8) = 0`, launching Tails at `0x800`.

What makes this class expensive to debug is the delay and the disguise. The reported
divergence was `sidekick_y` off by **one pixel** — the roll-height adjustment applied on the
way out of the spindash — hundreds of frames from the omission, in a different subsystem,
wearing the signature of an unrelated known bug.

When porting a ROM block-write:

- **Diff the routine's writes against your port field by field.** A missed *clear* is far
  more dangerous than a missed *set*, because a clear only matters when the field was
  already dirty, which is exactly the case no unit test sets up.
- **A duplicated ROM instruction is still an instruction.** Do not treat the second
  `move.b d0,spin_dash_flag(a0)` as noise to be tidied away; it tells you the field is in
  the block.
- **Ask where the omitted field is READ.** If the reader is gated to a state the block's
  own state excludes (grounded vs airborne here), the bug is guaranteed to be latent and
  delayed rather than immediate. That is a reason to be more careful, not less.
- **When a trace's headline field is a small positional delta, read the per-field error
  histogram before theorising.** Here the same frame carried `g_speed 0x0800 vs 0x0024`,
  `rolling 1 vs 0` and a roll animation; the one-pixel `y` was the least informative field
  of the set and the only one in the headline.

**Originating commit.** S3K complete-emeralds segment 6, 8,940 -> 7,663 errors, frontier
f3245 -> f3339. See `docs/status/trace-frontier-log.md`, 2026-08-20.

## VDP windows replace Plane A; they do not inherit its scroll

**Evidence:** S3&K `SpecialVInt_LBZ2WindowCopy`, `SpecialVInt_LBZ2ScrollAClear`,
`SpecialVInt_LBZ2WindowClear`, and `LBZ2BGE_PlatformDetach`.

An ending can scroll Plane A while keeping its standing platform fixed in the
VDP window. Copying the terrain into a sprite overlay and giving that overlay
the foreground scroll lifts the platform into a correctly grounded player.
Read the VDP window-position registers and the actual source/destination
nametable cells. The window replaces the foreground even where its pixels
are transparent, and its priority bits must feed the sprite-occlusion mask.
Count the row-copy VBlanks separately from subsequent clear/restore VBlanks.
A captured nametable also needs rewind-safe dimensions and GPU invalidation
when its contents are restored.

LBZ2 uses 28 copies plus one clear, then a window starting at screen Y=40.
Only the exposed upper strip takes the detach scroll; two final VBlanks clear
Plane A and restore the hidden rows before disabling the window. See
`docs/architecture/audits/2026-09-09-lbz2-ending-sequence.md` and the pixel test
`TestForegroundWindowRendering`. The rendering principle also applies to S1/S2
scenes that use the hardware window.

## Sanctuary routes must exercise the ROM destination, not only an engine alias

`SSEntryFlash_GoSS` / `loc_618AC` writes `$1701` before restarting the level.
The engine also exposes the sanctuary through its legacy `$1601` alias. A
headless test loading only the alias can pass while giant-ring entry has no
controller, emeralds, custom palette, camera setup, or background handler.
`ScreenEvents` dispatches `$1701` to `HPZS_*`; connect the resource profile
and stock object factories to that destination and test both entry identities.
Keep the paired `$1700` Death Egg boss act outside the sanctuary profile.
