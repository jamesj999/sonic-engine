# Sonic 2 Object Implementation — ROM Behavioural Pitfalls

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

## P82 — Dynamic ROM effects must keep their object id and init frame

**Symptom.** A trace reports `obj_sNN_type` expected `0x58`, actual missing,
or a later slot is reused one frame early after a boss-defeat sequence starts,
even though the engine diagnostics show a visually correct `Boss Explosion` at
the ROM coordinates.

**Root cause.** The dynamic effect was treated as an anonymous Java transient
instead of a real SST object. ROM helper routines such as S2
`Boss_LoadExplosion` write the effect's object id into the allocated slot, and
routine-0 init may play sound and return/jump without also consuming the main
routine's animation decrement in the same object pass.

**What to check.** For explosion, dust, sparkle, score, projectile, and other
helper-spawned effects, verify whether the ROM writes an object id into the
new slot and whether the init routine falls through to main logic. Carry the
per-game id into `ObjectSpawn.objectId()` (S2 Boss Explosion Obj58; S1
Explosion Obj3F) and return after init when the ROM init path does not execute
main logic until the next frame.

**ROM citation.** S2 defines `ObjID_BossExplosion` as Obj58, and
`Boss_LoadExplosion` writes that id into the allocated slot before copying boss
position and random offsets. Obj58 init sets mapping/art/timer state and plays
SFX, while Obj58 main decrements the frame timer on later passes
(`docs/s2disasm/s2.constants.asm:690`,
`docs/s2disasm/s2.asm:61193-61218,61413-61433`). S1 boss defeat helpers load
Obj3F explosions (`docs/s1disasm/_inc/Object Pointers.asm:78`,
`docs/s1disasm/_incObj/sub BossDefeated & BossMove.asm:9-14`).

**Originating commit.** `fix(s2): preserve boss explosion object identity`.

---

## P0A — Child-to-parent shared counters must be visible in the same object pass

**Symptom.** A parent object waits one extra frame to release the player or
advance state even though trace diagnostics show the shared child counter has
already reached zero. The first mismatch is often a player velocity or control
state written by the parent's release routine.

**Root cause.** The engine keeps the parent counter as a Java field/array and
only checks it from the parent's scheduled `update`. ROM child routines can
decrement a parent SST byte through an object pointer, and the parent-visible
RAM change is immediate even when the child slot runs before the parent slot in
the same `ExecuteObjects` pass.

**What to check.** For prize displays, multi-part bosses, counters, latches,
and parent-owned child effects, identify child writes through parent pointers
(`objoff_2A(a0)`, explicit parent SST addresses, or similar). If a parent
branches on that byte later in the same frame, notify the parent or otherwise
make the shared state visible immediately instead of waiting for the parent's
next frame.

**ROM citation.** CNZ ObjDC decrements ObjD6's active-prize counter through
`objoff_2A(a0)` before `CollectRing`; ObjD6 checks `objoff_2C` and releases via
`loc_2BE2E` with `y_vel=$400`
(`docs/s2disasm/s2.asm:25470-25494,59151-59188,59215-59224`).

**Originating commit.** `fix(s2): advance CNZ2 point pokey prize release`.

---

## P0 — LoadChildObject child accidentally uses lowest-free slot/order

**Symptom.** A boss or compound object is position-correct for most of a trace,
then a child-to-parent report arrives one frame early or with a player target a
few pixels off. Slot diagnostics show the child below its parent even though the
ROM child runs after the parent.

**Root cause.** Engine code used `spawnFreeChild` / lowest-free allocation for a
ROM path that calls `LoadChildObject`. In S2, `LoadChildObject` allocates after
the current SST slot, so the child should normally execute after the parent in
the same `ExecuteObjects` pass. If the child is lower than the parent, it can
run before the parent and make report bytes like `objoff_28` visible too early.
If the parent also manually advances that managed child to model a
body-before-child handoff, the managed child may need to defer its spawn-frame
update so it does not consume init outside the parent-owned ordering.

**What to check.** When porting a boss part, targeting sensor, projectile, lock
marker, or other object spawned from a parent routine, verify whether the ROM
uses `FindFreeObj` / `AllocateObject` or `LoadChildObject` /
`AllocateObjectAfterCurrent`. Choose `spawnFreeChild` only for the former and
`spawnChild` for the latter. If the parent keeps an explicit child reference and
calls the child's `update` inline for ROM order, add focused coverage proving
both the allocated slot and whether same-frame ObjectManager execution is
suppressed.

**ROM citation.** `docs/s2disasm/s2.asm:72978-72986` (`LoadChildObject` uses
`AllocateObjectAfterCurrent`); DEZ Death Egg Robot sensor call site and report
handoff at `docs/s2disasm/s2.asm:82785-82786,82792-82808,83478-83559`.

**Originating commit.** `fix(s2): advance DEZ robot sensor slot/order trace
frontier`.

---

## P0B — Parent-owned cosmetic children can steal gameplay-critical lower slots

**Symptom.** A later object interaction is one frame late even though both
objects are position-correct. Slot diagnostics show a cosmetic or auxiliary
child occupying a lower SST slot that the ROM uses for the gameplay object
which must execute first.

**Root cause.** The Java parent/child model can keep auxiliary children alive
or allocate them into lower slots that are free only because earlier engine
slot occupancy already drifted. For Obj50, a wing child below the parent stole
OOZ1's source Obj48 slot, so the target Obj48 ran before the source ball moved
the player into it.

**What to check.** For parent-owned visual children and appendages, verify both
allocation order and unload cleanup against the ROM slot events around dense
object clusters. If a child is structurally tied to its parent, expire it from
`onUnload()` as well as player destruction paths, and add a focused slot-order
test for any downstream object handoff that depends on parent/child ordering.

**ROM citation.** Obj48 captures/moves players in SST order
(`docs/s2disasm/s2.asm:51224-51357`). Obj50 creates a wing child and the wing
validates its parent slot before display/delete
(`docs/s2disasm/s2.asm:60567-60616,60637-60652`).

**Originating commit.** `fix(s2): advance OOZ1 launcher-ball slot order`.

---

## P0C — Invisible or consolidated ROM child SST entries still consume slots

**Symptom.** A compound object looks and collides correctly, but later object
allocation drifts. Slot diagnostics show the ROM has additional same-id child
entries for a multi-sprite chain or secondary platform while the engine keeps
only one consolidated Java parent object.

**Root cause.** The port collapsed a ROM object assembly into one renderer or
solid provider and skipped child SST entries that have little or no independent
visual code. Even if the parent can draw/collide the assembly, later
`FindFreeObj` / `AllocateObject` scans still observe the occupied ROM slots.

**What to check.** For rotating platforms, chains, multi-part platforms,
bosses, and decorative assemblies, trace every `AllocateObjectAfterCurrent`,
`LoadChildObject`, or helper that writes the same object id into a child SST
entry. Preserve those child slots even when rendering or collision remains
centralized in the parent, and add occupancy coverage around dense object
windows.

**ROM citation.** ARZ Obj83 allocates a chain multisprite child and two
platform subobjects after the parent before later object allocation scans run
(`docs/s2disasm/s2.asm:57437-57466,57472-57484,57612-57622`).

**Originating commit.** `fix(s2): advance ARZ2 Obj83 slot pressure`.

---

## P1 — Touch-response directional/state guards diverge from ROM

**Symptom.** Object rejects a rolling / spindash / invincible touch under a
condition ROM doesn't gate on. Trace shows the player passing through the
badnik or platform without the expected event (kill, bounce, launch).

**Root cause.** Engine added an extra gate (player below object, specific
direction, specific timer state) that ROM `Touch_Enemy` / `Touch_Killable` /
`Touch_KillEnemy` doesn't apply. ROM typically uses overlap-only for the
kill itself, and *only* uses position to choose the bounce direction.

**What to check.** When porting `onPlayerAttack` / touch-response code for a
new badnik or interactive object: read the ROM `subObjData` table at the
top of the object file and the `Touch_*` routine entry. List every gate the
ROM applies; reproduce *only* those. Do not add directional guards "for
safety".

**ROM citation.** `docs/s2disasm/s2.asm:84807-84890` (`Touch_Enemy` /
`Touch_KillEnemy`). Object touchbox via `subObjData ..., w, h, flags`
(e.g. `s2.asm:76603` for Grabber).

**Originating commit.** `c2d998751 fix(s2): CPZ Grabber badnik rolling-kill
independent of vertical position`.

---

## P2 — ROM multi-frame init collapsed into one engine frame

**Symptom.** A trace divergence appears N frames before the ROM-correct
state transition fires, then the transition itself is one frame early.
Timer values like `scriptTimer` differ by exactly N at the divergence.

**Root cause.** ROM dispatches object init across multiple frames: outer
`Obj_Init` writes routine and `rts`, the next frame enters the inner case
0 which performs the real init work. The engine constructor often does
both in zero frames, pre-setting `routine = subtype - <base>`. That
collapses ROM's two-frame init into one, shifting every downstream timer
by 1.

**What to check.** When porting object init, find ROM's outer init label
(`ObjXX_Init`), its `bra.w DisplaySprite` / `rts`, and the inner main
routine's case 0. If they live in separate frames in ROM (each ends in
`rts` from a dispatcher), preserve the frame count in the engine — don't
pre-resolve the inner init in the constructor.

**What to check (cont).** For trace replay paths that rely on the
pre-gameplay prelude (S2 v9.2-s2 native-prelude mode), expose a
`compensateForCollapsedInit()` hook that the trace replay bootstrap can
call to add the missing frame's worth of state changes. Gate it by zone /
condition so it doesn't fire in normal play.

**ROM citation.** `docs/s2disasm/s2.asm:78271-78284` (`ObjB2_Init` outer
dispatch) + `s2.asm:78368-78372` (`ObjB2_Main_WFZ_Start_init` inner case 0).

**Originating commit.** `44d7939e1 fix(s2): WFZ Tornado collapsed two-frame
init compensation`.

---

## P3 — Global object state vs ROM per-player object state bytes

**Symptom.** Sidekick (player 2) interaction with an object is suppressed
by a state flag set by Sonic's interaction, or vice versa. Tails fails to
trigger a Flipper / spring / monitor that Sonic just triggered.

**Root cause.** Engine uses a single `int` or `boolean` field for state
that ROM tracks per-player at SST offsets like `objoff_36` (P1) and
`objoff_37` (P2). When Sonic sets the global flag, Tails-side checks see
"already triggered" and bail.

**What to check.** When porting an interactive object, list every SST byte
the ROM reads/writes. If the offset is `objoff_36`, `+1` (i.e. `objoff_37`),
or any per-player pair, the engine state must be a per-sprite map
(`IdentityHashMap<AbstractPlayableSprite, Integer>` or similar), not a
global field. The originating Flipper case had a launch cooldown that
mattered to both players independently.

**ROM citation.** `docs/s2disasm/s2.asm:57870-57879` (Flipper per-player
state bytes); analogous pairs exist for springs, bumpers, monitors.

**Originating commit.** `3cb72b6af fix(s2): CNZ Flipper per-player launch
cooldown + ROM-accurate y_pos`.

---

## P4 — Character-dependent coordinate adjustments where ROM uses a fixed offset

**Symptom.** Tails Y diverges from Sonic Y by a character-specific amount
after a rolling launch, hurt, or other state transition — usually 1-5px.

**Root cause.** Engine reaches for a helper like
`getRollHeightAdjustment()` (which returns `runHeight - rollHeight` and
differs per character: Sonic 10, Tails 2) when ROM has a literal `addq.w
#N, y_pos(a1)` (constant for all characters).

**What to check.** When the ROM source code has `addq.w #<literal>,
y_pos(a1)` or `subq.w #<literal>, y_pos(a1)`, port that as a literal
`NativePositionOps.addYPosPreserveSubpixel(player, 5)`. Do not substitute a
character-aware helper "because Tails is shorter". Reserve raw
`setCentreYPreserveSubpixel(...)` calls for lower-level sprite internals or
non-playable/object-local state.

**ROM citation.** `docs/s2disasm/s2.asm:58042` (Flipper rolling entry:
`addq.w #5, y_pos(a1)`).

**Originating commit.** `3cb72b6af fix(s2): CNZ Flipper per-player launch
cooldown + ROM-accurate y_pos` (secondary bug).

---

## P5 — SolidObject returns non-solid prematurely on state transition

**Symptom.** Rider drops from a solid object on the exact frame of an
internal state-machine transition (e.g. WAIT → SLIDE → FALL). One-pixel y
divergence appears at the transition frame as the engine treats the rider
as airborne while ROM keeps them riding.

**Root cause.** Engine's `isSolidFor()` gates on `routineSecondary !=
STATE_FALL` or similar internal state. ROM's `Obj_Main` calls
`PlatformObject` unconditionally; the lift continues to position riders
across the entire state-machine lifecycle. ROM only stops being solid when
the object physically leaves the screen, often via a `move.w #$4000,
x_pos(a0)` off-screen warp inside the fall handler.

**What to check.** `isSolidFor()` for moving solids should track *physical
existence on screen*, not internal state-machine routine. Look at the
ROM's exit condition (usually an off-screen check inside the FALL handler
that performs the warp) and only return false after that warp has fired.

**ROM citation.** `docs/s2disasm/s2.asm:47381-47466` (`Obj16_Main` dispatch
order + `Obj16_Fall`).

**Originating commit.** `719c4034e fix(s2): HTZ Lift solid-while-falling +
ROM-order gravity/move`.

---

## P6 — Gravity-before-move vs ROM's move-before-gravity ordering

**Symptom.** A falling object's y_pos transitions integer values one frame
earlier than ROM. Often surfaces as a single-frame off-by-one on a rider's
camera_y / player y at the transition frame.

**Root cause.** Engine free-fall code does `yVel += gravity; yFixed +=
yVel` (gravity then move). ROM `Obj_*_Fall` consistently does
`ObjectMove` (which adds yVel to y_pos in subpixel) **first**, then `addi.w
#$<gravity>, y_vel(a0)` after. The order matters because the y_pos
integer rolls over one frame later in ROM.

**What to check.** When porting a free-fall routine, preserve the
`ObjectMove → addi.w gravity` order. Search the ROM routine for the
`ObjectMove` / `MoveSprite` call and the gravity `addi.w`; the call comes
*before* the addi.

**ROM citation.** `docs/s2disasm/s2.asm:47444-47466` (`Obj16_Fall`),
`s2.asm:29967-29981` (`ObjectMoveAndFall` reference impl).

**Originating commit.** `719c4034e fix(s2): HTZ Lift solid-while-falling +
ROM-order gravity/move` (secondary bug).

---

## P7 — Centre Y vs top-left Y for kill / boundary checks

**Symptom.** Sidekick (or player) fails to die when crossing the bottom
kill plane that ROM would trigger, or dies one frame late.

**Root cause.** Engine compares `sprite.getY()` (top-left convention,
y_radius pixels above centre) against the kill plane. ROM `Tails_LevelBound`
and `Sonic_LevelBound` compare `y_pos(a0)` which is the centre.

**What to check.** Any kill / boundary / out-of-bounds check the object
triggers (or any check the object's collision drives) must compare against
`getCentreY()` not `getY()`. Same for X-axis side boundaries:
`getCentreX()` not `getX()`.

**ROM citation.** `docs/s2disasm/s2.asm:39929-39940` (`Tails_LevelBound`
bottom check), `s2.asm:36936-36960` (`Sonic_LevelBound`),
`s2.asm:84999-85019` (`KillCharacter` follow-up).

**Originating commit.** `4361de0e8 fix(s2): sidekick level-bound bottom
kill uses centre Y to match ROM`.

---

## P8 — Per-game post-event flow divergence (S2 deferred vs S3K immediate)

**Symptom.** Sidekick warps off-screen immediately on death in the engine,
but ROM keeps Tails at his death position for several frames until he
falls past a threshold.

**Root cause.** ROM `Obj02_Dead` (S2) defers the despawn warp until
`y_pos > Tails_Max_Y_pos + 0x100`, running gravity (`ObjectMoveAndFall`)
each frame until that threshold. ROM S3K `sub_13ECA` writes the despawn
marker immediately, one frame after kill. Engine generalised to the S3K
flow for both games, breaking S2.

**What to check.** When implementing sidekick state machines or
post-event flows that touch despawn / clean-up / return-to-pool, look at
each game's ROM equivalent separately. If they diverge, route the behavior
through the smallest accurate owner from `docs/architecture/per-game-rule-placement.md`
and branch on that semantic rule/profile/provider value — never on `gameId`.

**ROM citation.** `docs/s2disasm/s2.asm:40736-40759` (`Obj02_Dead` +
`Obj02_CheckGameOver` deferred-fall), `docs/s2disasm/s2.asm:29967-29981`
(`ObjectMoveAndFall`), `docs/s2disasm/s2.asm:39043-39052`
(`TailsCPU_Despawn` final warp). S3K immediate-warp baseline at
`docs/skdisasm/sonic3k.asm:26800-26809` (`sub_13ECA`).

**Originating commit.** `a4aca7d6f fix(s2): sidekick death uses
deferred-despawn flow to match S2 Obj02_Dead`.

---

## P9 — Integer math drops y_sub carry in 16:16 position updates

**Symptom.** Post-warp / post-teleport y_pos is exactly 1 pixel low (or
high) relative to ROM. The error appears only when the pre-event
`y_sub_pos + (y_vel & 0xFF00)` overflows the 16-bit subpixel boundary.
HTZ trace F538: ROM 0x0008 vs engine 0x0007 (-1px).

**Root cause.** ROM `ObjectMoveAndFall`
(`docs/s2disasm/s2.asm:29967-29981`) treats `y_pos:y_sub` as a single
32-bit long and executes `add.l d0,d3` where `d0 = y_vel<<8` (sign
extended). Subpixel overflow carries into `y_pos`. Java code that does
`y_pos += (y_vel >> 8)` after a `setCentreYPreserveSubpixel(...)` warp
treats the two halves as independent integers and DROPS the carry. The
overflowed low byte still lands in `y_sub` (because `setCentreY*` preserves
it), but `y_pos` is short by 1.

**What to check.** Any code path that:
1. Writes playable-sprite native `x_pos` / `y_pos` with `NativePositionOps`
   (or lower-level/raw preserve-subpixel setters in sprite internals),
2. THEN integrates by a velocity stored in subpixel units (`x_vel` / `y_vel`),
must use `AbstractSprite.move(xSpeed, ySpeed)` — which mirrors ROM's
`add.l d0, x_pos(a0)` / `add.l d0, y_pos(a0)` — rather than manual
`centreY += (ySpeed >> 8)` arithmetic. The same applies any time you
need to add `y_vel` to position and ROM stores the full position as a long.

**ROM citation.** `docs/s2disasm/s2.asm:29967-29981`
(`ObjectMoveAndFall`); same convention in S1 / S3K
(`docs/s1disasm/_incObj/sub ObjectFall & SpeedToPos.asm`,
`docs/skdisasm/sonic3k.asm` `ObjectMoveAndFall` / `MoveSprite`).
Engine equivalent: `AbstractSprite.move` in
`src/main/java/com/openggf/sprites/AbstractSprite.java`.

**Originating commit.** `<pending>` (trace frontier advancement loop iter
1: HTZ F538 + MCZ F443 deferred-despawn sub-pixel & solid-contact gating).

---

## P10 — Solid object contacts must skip dead / despawning players

**Symptom.** A dying CPU sidekick (or main player) "lands" on a moving
solid object (lift / platform / drawbridge) under the impact point while
ROM would have him fall past it. Engine's `tails_y` freezes at the
platform top and `tails_y_speed` drops to 0, while ROM keeps Tails falling
through the platform.

**Root cause.** ROM `SolidObject_ChkBounds`
(`docs/s2disasm/s2.asm:35178-35182`) gates the full bounding-box check
with:

```
SolidObject_ChkBounds:
    tst.b    obj_control(a1)
    bmi.w    SolidObject_TestClearPush   ; bit 7 set => skip
    cmpi.b   #6,routine(a1)              ; routine >= 6?
    bhs.w    SolidObject_NoCollision     ; Dead/Gone/Respawning => skip
```

The two gates are independent. The `obj_control bit 7` path covers
respawning / object-controlled states (post-warp). The `routine >= 6`
path covers the Dead / Gone / Respawning routines themselves. An engine
that ports only the `obj_control` gate will still apply solid contacts to
a sidekick mid-deferred-death-fall (S2 routine = 6 with `obj_control = 0`),
landing dead Tails on platforms.

**What to check.** `blocksSolidContacts(player, candidate)` (or whatever
the engine's SolidObject pre-filter is named) needs BOTH gates:
1. `player.isObjectControlled()` — mirrors `obj_control` bit 7.
2. CPU sidekick state `DEAD_FALLING` (engine equivalent of ROM Tails
   routine = 6 / Obj02_Dead) — must short-circuit even though
   `obj_control` is still 0 during S2 deferred-despawn.

For Sonic / Tails / Knuckles main-player code paths, ROM routine = 6 is
the same death state and the engine should similarly skip solid contacts
based on its `dead` / death-state flag.

**ROM citation.** `docs/s2disasm/s2.asm:35178-35182`
(`SolidObject_ChkBounds`). Same convention in S1 and S3K with their
respective ROM offsets. Engine equivalent: `ObjectManager.SolidContacts.
blocksSolidContacts` in `src/main/java/com/openggf/level/objects/
ObjectManager.java`.

**Originating commit.** `<pending>` (trace frontier advancement loop iter
1: HTZ F538 + MCZ F443 deferred-despawn sub-pixel & solid-contact gating).

---

## P11 — Solid object break/trigger condition leaks main-player state into sidekick contact

**Symptom.** Sidekick (Tails) is suddenly knocked airborne + rolling + Y
shifted by 1 px while the main player is rolling through nearby terrain.
Trace shows ROM keeps the sidekick grounded with `status.standing` and
`status.pushing`, while the engine reports `status.in_air | rolling` and a
fresh downward `y_vel`. The divergence appears on the exact frame the
main player passes a breakable / launchable object even though the
sidekick isn't standing on that object.

**Root cause.** The engine cached `playerWasRolling = player.getRolling()`
inside the object's per-frame `update(...)` method, with `player` being
whichever sprite the object manager happened to pass (typically the main
player). The break/launch decision in `onSolidContact(player, contact)`
then OR'd the cache with the contacting player's own `getRolling()`:
`isRolling = playerWasRolling || player.getRolling()`. When the main
player was rolling, the cache made the OR true even for the sidekick's
side / bottom contact, so the object's break path fired with the
sidekick as the victim — knocking them airborne, snapping `y_radius`
from 11 down to 7 (`-1 px` apparent Y shift), and setting
`rolling | in_air | y_vel = -$300`.

**What to check.** Any solid object with a state-dependent break /
launch / monitor-pop / arrow-trigger:
1. Per-player conditions must read the *contacting* player's state, not
   a per-frame cached "saw rolling once" flag. Use the player parameter
   of `onSolidContact` directly: `player.getRolling()`,
   `player.getAir()`, etc.
2. ROM `Obj32_Main`, `Obj26_Main` (monitor), `Obj13_Main` (spring) check
   the *object's* `status(a0) & standing_mask` (the per-player standing
   bits the SolidObject routine sets on the OBJECT, indexed by which
   player is standing on it) plus that player's *animation* — never a
   global "was rolling" cache. Per-player anim is cached in
   `breakableblock_mainchar_anim` (objoff_32) and
   `breakableblock_sidekick_anim` (objoff_33), giving each player its
   own state byte.
3. Side / bottom contact almost never breaks ROM solids. Most breakable
   objects only fire on `contact.standing()` (the player is currently
   seated on top via SolidObject's standing path). A rolling player
   hitting the underside gets a CEILING collision via SolidObject and
   bonks; they do not break the block. Do not invent synthetic
   `touchBottom()` / `touchSide()` break paths "for completeness".
4. `update(...)` should not mutate player-derived caches that are read
   from another player's `onSolidContact`. If you need per-player
   state, key it on the player instance (IdentityHashMap) or read it
   inside the contact callback.

**ROM citation.** `docs/s2disasm/s2.asm:48889-48959` (Obj32 / BreakableBlock):
- 48891-48892 cache MainCharacter.anim / Sidekick.anim per-player
- 48899-48901 run SolidObject, then `andi.b #standing_mask, d0`
- 48911-48913 check each standing player's anim against `AniIDSonAni_Roll`
- 48940-48950 `Obj32_BouncePlayer` sets rolling + in_air + `y_vel = -$300`

**Originating commit.** `<pending>` (trace frontier advancement loop iter
3: HTZ F979 BreakableBlock leaked main-player rolling state into Tails'
side-contact callback, knocking Tails airborne with the wrong character
as victim).

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

**ROM citation.** `docs/s2disasm/s2.asm:75923-75976` (ObjA5 / Spiny
patrol-detection-attack flow), `docs/s2disasm/s2.asm:72320-72346`
(`Obj_GetOrientationToPlayer` closest-player selection +
`d2 = obj.x - player.x`), `docs/s2disasm/s2.asm:76050-76070`
(`loc_38C22` spike spawn uses MainCharacter for direction).

**Originating commit.** `<pending>` (trace frontier advancement loop iter
4: CPZ F844 Spiny detection ported with `dx <= 0x80 && dy <= 0x40 &&
playerIsLeft == facingLeft` bounding-box + facing guard; replaced with
ROM horizontal-only closest-player gate. Reduced CPZ trace errors
494 -> 434; frontier still at f844 due to a residual ~22 px Spiny
position drift -- the new detection fires at a different first frame
than ROM, indicating subtle timing/order details remain. Pattern itself
applies to every patrolling-shooter badnik that uses the angle-based
attack gate. ObjA4/Asteron, ObjA6/SpinyOnWall, and at least three S3K
analogues share the same idiom.)

**Additional example.** `<pending>`: MCZ Crawlton (`Obj9E`) used only
MainCharacter for its `Obj_GetOrientationToPlayer` gate. Routing its detection
through `ObjectPlayerQuery.nearestByRomX(NATIVE_P1_P2, ...)` matched the helper's
native Sonic/Tails closest-X selection (`docs/s2disasm/s2.asm:75229-75243`,
`docs/s2disasm/s2.asm:72755-72796`) and cleared `s2_mcz1`.

---

## P13 -- SlopedSolidProvider.getSlopeBaseline() returning halfHeight when ROM slope table encodes absolute offsets

**Symptom.** Player rolling-air-falls toward a sloped platform/seesaw/bridge
that ROM cleanly lands them on, but the engine fires "no contact" and lets
them fall through. Frontier divergence appears as a y / y_speed / air mismatch
on the exact landing frame: ROM has `y_speed=0`, `air=0`, snapped y position;
engine still has `y_speed = previous + gravity`, `air=1`, kept falling. HTZ1
trace F988 surfaced this when Sonic should land on the tilted Seesaw (slope
sample 20, ROM surface = `obj_y - 20 = 980`, player bottom = 983, ROM lands;
engine `baseY = obj_y - (20 - 8) = 988`, computes `relY = -5 < minRelY = 0`,
returns null, no landing.)

**Root cause.** `SlopedSolidProvider.getSlopeBaseline()` controls
`resolveSlopedContact`'s shift between the raw slope sample and the
effective surface Y:

```
slopeOffset = slopeSample - slopeBase
baseY       = anchorY - slopeOffset
relY        = playerCenterY - baseY + 4 + playerYRadius
```

ROM `SlopedPlatform_cont` (s2.asm:35787-35793) reads the slope sample
directly: `move.b (a2,d0.w),d3 / ext.w d3 / move.w y_pos(a0),d0 /
sub.w d3,d0`.  There is no baseline subtraction; the slope table value
IS the offset from object_y to the surface.  S2's slope tables (e.g.
`byte_21C8E` for the seesaw) already encode that convention, so
`getSlopeBaseline()` must return `0` for any S2 slope object whose
data is sampled directly by SlopedPlatform / SlopedPlatform_cont.

The `COLLISION_HEIGHT` baseline pattern came from S1's GHZ bridge /
SLZ seesaw slope tables, which encode the surface offset relative to
the object's bottom edge (so the table needs to be shifted up by
COLLISION_HEIGHT to land on the object center).  S2 slope data
does NOT follow that convention -- positive values lift the surface
above object_y, negative values drop below.

**What to check.** When porting any S2/S3K SlopedSolidProvider:

1. Find the ROM routine that calls `SlopedPlatform` / `SlopedPlatform_cont`
   (or the S3K equivalent SolidObjCheckSloped2 / loc_19EB6).
2. Look at the slope data values relative to ROM `y_pos(a0) - d3`:
   - If slope[mid] ~= 0 and the surface visually sits at object_y,
     the table encodes absolute offsets -> `getSlopeBaseline()` returns 0.
   - If slope[mid] ~= halfHeight and the surface visually sits at
     `object_y - halfHeight`, the table is relative to object bottom
     -> `getSlopeBaseline()` returns halfHeight (rare; S1 only).
3. Check `Sonic2/S3k BridgeObjectInstance.getSlopeBaseline()` and
   `AizFlippingBridgeObjectInstance.getSlopeBaseline()`: both return
   0 with the comment "Height table values are absolute offsets from
   obj_y".  That is the S2/S3K-standard pattern; objects that copy
   the S1 `COLLISION_HEIGHT` baseline without checking will fail
   the same way.
4. Confirm via trace replay: compute `surfaceTop = anchorY - rawSlopeSample`
   and `playerBottom = playerY + yRadius + 4`. ROM lands when
   `surfaceTop - playerBottom` is in `(-16, 0]`. The engine's `relY`
   must equal `-(surfaceTop - playerBottom) = playerBottom - surfaceTop`
   for `relY` to land in `[0, 16)`. If `slopeBase != 0` shifts the
   apparent surface by halfHeight, the landing window shifts the
   same amount and the player misses.

**ROM citation.** `docs/s2disasm/s2.asm:35787-35793` (SlopedPlatform_cont
slope sample -> surface Y, direct subtraction, no baseline);
`s2.asm:47103-47115` (Obj14_UpdateMappingAndCollision setup before
calling SlopedPlatform).  Engine equivalent:
`SlopedSolidProvider.getSlopeBaseline()`, `ObjectManager.
resolveSlopedContact` (`baseY = anchorY - slopeOffset`).

**Originating commit.** `<pending>` (trace frontier advancement loop iter
5: HTZ F988 Sonic missed Seesaw landing because SeesawObjectInstance
returned `COLLISION_HEIGHT` from `getSlopeBaseline()`, shifting the
effective surface 8 px below ROM's, so `relY` went negative and the
contact resolver returned null.  Companion fix: `Obj14_Main` calls
`Obj14_SetMapping` exactly once per frame, but the engine was calling
`updateAngle()` twice (once with the recomputed target, once
unconditionally with currentAngle), advancing `mapping_frame` at
twice ROM's rate during tilt transitions.  Both bugs surfaced
together at the f988 frontier.)

---

## P14 -- Engine edge-triggers ENEMY touch response but ROM polls every frame

**Symptom.** A badnik (or other ENEMY-category object) that should be
destroyed when the player transitions into an attacking state while
already overlapping the badnik stays alive instead. Trace shows ROM
killed the badnik AND applied the canonical Touch_KillEnemy bounce
(typically `y_vel -= $100` for side, `y_vel = -y_vel` for top, `y_vel +=
$100` for upward); engine has the badnik still in the active list and
the player's y_vel/x_vel unchanged. The MCZ trace surfaced this at
frame 825 when Sonic was standing in the Crawlton's bounding box with
`invulnerable_time != 0` (Touch_NoHurt path), then pressed B+Down to
start a Spindash. ROM `Touch_Enemy` re-read `anim(a0)` on frame 825,
saw `AniIDSonAni_Spindash`, ran Touch_KillEnemy and set
`y_vel = -$100`. Engine had Sonic in the badnik's overlap from a
previous frame so the touch callback was edge-suppressed.

**Root cause.** The engine's ReactToItem-equivalent loop in
`ObjectManager.TouchResponses.processCollisionLoop` historically
edge-triggered ENEMY (and SPECIAL) touch callbacks via:
```
boolean shouldTrigger = category == BOSS
        || category == HURT
        || provider.requiresContinuousTouchCallbacks()
        || !overlappingSet.contains(instance);
```
The intent was an optimisation: once a player overlaps an enemy and
the response runs, don't re-run while the overlap persists. ROM has
no such gate. ROM `Touch_Loop` (s2.asm:84502-84548) iterates every
frame and `Touch_Enemy` (s2.asm:84807-84890) re-reads
`status_secondary(a0)` and `anim(a0)` each call. The decision between
Touch_KillEnemy and Touch_ChkHurt is therefore made per-frame, not
once on entry, so any later state transition into Spindash / Roll /
Invincibility immediately switches the response to kill-and-bounce.

The same gate suppresses cases like rolling into an enemy from above
while frame-0 of the overlap is a hurt path (e.g. spike contact then
roll), and any pattern where touch happens before attacking state is
set.

**What to check.** When implementing a badnik (any
`AbstractBadnikInstance` subclass) or any object that uses the
`ENEMY` `TouchCategory`:
1. Do NOT rely on `wasAlreadyDestroyed` / "overlap memory" as a
   correctness mechanism. The framework now polls every frame, so the
   object's own `onPlayerAttack` must be idempotent and self-gating
   (`isDestroyed()` check at top of `onPlayerAttack`,
   pre-destruction state captured before mutating).
2. ROM Touch_Enemy reads the current `anim` byte each call. If your
   badnik wants to differentiate behaviour based on player state
   (e.g. boss multi-sprite hit_count vs Touch_KillEnemy bounce),
   read the player's current animation in `onPlayerAttack`, not
   cached state from `update()`.
3. The fix targets `ENEMY` only. SPECIAL is still edge-triggered to
   keep monitors / object-controlled SolidObjects from firing
   responses every frame. If a SPECIAL object genuinely needs every-
   frame polling (e.g. a state machine that wants to observe the
   player's animation transition), implement
   `TouchResponseProvider.requiresContinuousTouchCallbacks()` and
   return true.
4. Trace replay test for any new badnik: run the badnik trace replay
   with the player overlapping it from frame N-1 and starting a
   spindash on frame N. ROM will fire Touch_KillEnemy on frame N;
   engine must too. If it doesn't, check whether your badnik has
   custom logic that depends on a one-shot trigger flag.

**ROM citation.** `docs/s2disasm/s2.asm:84502-84548` (`TouchResponse`
/ `Touch_Loop` — iterates every frame, no overlap memory),
`s2.asm:84807-84890` (`Touch_Enemy` / `Touch_KillEnemy` — reads
`status_secondary(a0)` / `anim(a0)` per call; the same routine handles
both hurt and kill outcomes based on current state).  Same per-frame
loop in S1 (`docs/s1disasm/_incObj/Sonic ReactToItem.asm`) and S3K
(`docs/skdisasm/sonic3k.asm` `Collision_response_list` dispatcher).
Engine equivalent: `ObjectManager.TouchResponses.processCollisionLoop`
(`shouldTrigger` decision around the `!overlappingSet.contains`
edge gate).

**Originating commit.** `<pending>` (trace frontier advancement loop
iter 6: MCZ F825 Sonic standing in Crawlton overlap was edge-trigger-
suppressed from the per-frame Touch_Enemy re-check; once continuous
polling was restored for ENEMY, Spindash entry on f825 fired
Touch_KillEnemy correctly and produced y_vel = -0x100, advancing the
MCZ frontier from 825 to 862 (619 errors -> 618 errors; same touch
loop also unmasks any future "transition-into-attack-during-overlap"
pattern in S1/S2/S3K badnik implementations).

---

## P15 -- Object update() resolves solid contacts BEFORE refreshing slope / collision state

**Symptom.** A sloped solid (seesaw, bridge, tilting platform) ports the
ROM logic but the rider's Y trails ROM by one frame during a state
transition.  Frontier divergence appears the frame the slope changes shape:
ROM has snapped to the new surface; the engine still uses the previous
frame's surface and lags 1-8 px depending on the slope-table delta.  HTZ1
trace f1017: ROM transitioned `mapping_frame` 2 -> 1 (tilted -> flat) and
sampled `SLOPE_FLAT[20]=5`, putting Sonic at `y=0x03D0`; engine still had
`mapping_frame=2` (xFlip) at sample time, sampled `SLOPE_TILTED[27]=2`
and put Sonic at `y=0x03D3`.  The state itself transitioned at the right
time -- it just happened AFTER the engine had already finished its
slope sample.

**Root cause.** ROM `Obj14_Main` (seesaw) order:

```
Obj14_Main:
    move.b objoff_3A(a0),d1     ; previous-frame target
    btst   #p1_standing_bit,status(a0)
    beq.s  loc_21A12             ; if no standing, alt path
    ; ... recompute d1 from player x ...
Obj14_UpdateMappingAndCollision:
    bsr.w  Obj14_SetMapping      ; <-- mapping_frame UPDATED here
    lea    (byte_21C8E).l,a2     ; slope table selected from NEW mapping_frame
    btst   #0,mapping_frame(a0)
    beq.s  +
    lea    (byte_21CBF).l,a2
+
    move.w x_pos(a0),-(sp)
    moveq  #0,d1
    move.b width_pixels(a0),d1
    moveq  #8,d3
    move.w (sp)+,d4
    bra.w  SlopedPlatform        ; <-- collision uses NEW state
```

ROM updates the slope-relevant state, **then** runs the collision call.
A naive engine port often inverts this:

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
previous frame's surface; the rider's Y lands one transition behind ROM.

**What to check.** When implementing any solid object whose collision
geometry depends on a tickable state byte (mapping_frame, animation
frame, internal angle, depression amount, slope offset table choice),
look at the ROM `Obj_Main` to see where the state update happens
relative to the SolidObject / SlopedPlatform / PlatformObject call:

1. If ROM updates the state *before* the collision call, the engine
   must update its equivalent *before* `resolveSolidNowAll()` /
   `checkpointAll()`.  Compute the target from the PREVIOUS frame's
   standing-player references (kept as instance fields) plus the
   CURRENT player x positions -- ROM does exactly that via
   `btst p1_standing_bit, status(a0)` on the entry-frame status and
   `move.w x_pos(a1), d0` on the current player position.
2. The previous-frame standing references are valid because ROM
   itself reads them before the collision call clears / re-sets them.
   In the engine, the latched `standingPlayer1` / `standingPlayer2`
   fields from the end of the prior `update()` give the same view.
3. If ROM updates the state *after* the collision call (e.g. some
   gravity / move-by-velocity is interleaved with collision in ROM
   order), preserve that placement -- don't blindly hoist state
   updates to the top of `update()`.
4. Watch for sibling helpers that already follow the ROM order:
   `BridgeObjectInstance.update()` calls `updateDepressionState()`,
   `rebuildBridgeShape()`, `updateSlopeData()` FIRST and only then
   runs `checkpointAll()`.  That is the correct template.
5. The fix is purely a reorder; no new state, no new flags.  Don't
   try to "buffer" the previous-frame slope -- match ROM order and
   the divergence disappears.

**ROM citation.** `docs/s2disasm/s2.asm:47037-47115` (Obj14_Main +
Obj14_UpdateMappingAndCollision: target compute -> Obj14_SetMapping
-> SlopedPlatform).  Same idiom in S1 Obj48 / Obj49 (S1 seesaw) and
in S3K AIZ flipping bridge and Tension bridge -- they all update
the slope-relevant state byte before calling the slope-collision
routine.  Engine equivalent: any solid object's
`update(int, PlayableEntity)` that calls
`services().solidExecution().resolveSolidNowAll()` should perform
slope-shape state updates BEFORE that call, mirroring ROM order.

**Originating commit.** `<pending>` (trace frontier advancement loop
iter 7: HTZ f1017 Sonic running across HTZ Seesaw during
mapping_frame=2 -> mapping_frame=1 transition.  Engine resolved
SlopedPlatform contact with stale mapping_frame=2, sampling
SLOPE_TILTED[27]=2 instead of ROM's SLOPE_FLAT[20]=5, leaving
Sonic 3 px low.  Reorder fixed: target compute + updateAngle()
now run BEFORE resolveSolidNowAll(), so the slope sampled uses the
freshly-transitioned mapping_frame.  Frontier: HTZ f1017 (943 errs)
-> f1084 (1180 errs, +67 frames).  Pattern applies to every
state-driven sloped solid that uses MANUAL_CHECKPOINT solid
execution.)



## P16 -- Monitor (and ROM SolidObject_AtEdge) push bit set on any grounded side contact, not just movingInto

**Symptom.** Player breaks a monitor (Touch_Monitor rolling break path) from
the side, but ROM sets `in_air=1` while the engine keeps the player grounded
(or vice-versa: engine fires in_air when ROM keeps grounded).  The break itself
is correct; the divergence is on the airborne transition that `Obj26_Break`
applies based on the monitor's accumulated standing/pushing bits.  MCZ f862:
ROM `air=1`, engine `air=0` after Sonic rolls into a monitor he pushed two
frames earlier.

**Root cause.** ROM `SolidObject_cont` -> `SolidObject_LeftRight` ->
`SolidObject_AtEdge` (`docs/s2disasm/s2.asm:35241-35248`) sets the OBJECT's
push bit and the player's pushing bit for ANY side contact when the player
is not airborne:

```
SolidObject_AtEdge:
    sub.w d0,x_pos(a1)
    btst #status.player.in_air,status(a1)
    bne.s SolidObject_SideAir       ; air -> no push, exit
    move.l d6,d4
    addq.b #pushing_bit_delta,d4
    bset d4,status(a0)              ; <- set OBJECT's push bit unconditionally
    bset #status.player.pushing,status(a1)
```

The engine's monitor-specific `resolveMonitorContact` was more restrictive,
gating `pushing` on `movingInto && !exactEdgeOverlap`:
```
boolean pushing = !player.getAir() && movingInto && !exactEdgeOverlap;
```
Position correction and speed zeroing should be gated on movingInto (ROM
`SolidObject_StopCharacter` runs only when moving toward the object), but the
push BIT is set even at rest on an edge.

`Obj26_Break` (s2.asm:25502-25515) keys its airborne transition off the
monitor's accumulated push/standing bits, so missing the push set when the
player was at rest on the monitor's edge cascades into "broken monitor
should fling Sonic upward but engine keeps him grounded" two frames later
when he rolls back into it.

**What to check.** When implementing any SolidObjectProvider whose ROM
counterpart routes through SolidObject_cont (i.e. uses the standard
SolidObject family, not a custom side-handler):

1. The push bit on the OBJECT and player must be set for any grounded side
   contact, regardless of movingInto.  `SolidObject_AtEdge` (ROM) /
   `resolveMonitorContact` / `resolveContactInternal` (engine) should
   compute `pushing = !player.getAir()` for the side branch.
2. Position correction (`sub.w d0, x_pos`) and speed zeroing
   (`SolidObject_StopCharacter`'s `move.w #0, inertia` / `x_vel`) remain
   gated on movingInto.  ROM only zeroes speed when the player is moving
   into the object; at rest or moving away the position correction and
   speed zeroing skip but the push bit still gets set.
3. The push bit must persist across rolling frames.  ROM
   `SolidObject_Monitor_Sonic` returns early when the player is rolling,
   leaving the previously-set push bit intact.  The engine's
   IdentityHashMap (or per-player flag) used by listeners like
   `MonitorObjectInstance.onSolidContact` must not be cleared while the
   player is in the overlap area.
4. Listener objects (BreakableBlock, Spring, Monitor, etc.) that key
   behaviour on "player was previously standing/pushing" should rely on
   the accumulated state, not on a single-frame contact check.
5. Cross-game: S1 `SolidObject_AtEdge` (s1disasm/_incObj/sub SolidObject.asm,
   `Solid_Centre` -> push bit set) and S3K's
   `SolidObjectFull2_1P` (sonic3k.asm `loc_1E06E` notes: "ROM loc_1E06E
   sets Status_Push for any grounded side contact") use the same rule.

**ROM citation.** `docs/s2disasm/s2.asm:35241-35253` (`SolidObject_AtEdge`),
`s2.asm:25502-25515` (`Obj26_Break` consumes the bits), `s2.asm:25448-25467`
(`SolidObject_Monitor_Sonic` returns early for rolling, preserving bits).
Engine equivalent: `ObjectManager.resolveMonitorContact` push gate
(`src/main/java/com/openggf/level/objects/ObjectManager.java`) and any
listener consuming the accumulated push/standing state in
`onSolidContact` (`MonitorObjectInstance`, etc.).

**Originating commit.** `<pending>` (trace frontier advancement loop iter
8: MCZ f862 Sonic spindash-rolled into a Monitor at the exact same Y, ROM
fired Obj26_Break with the monitor's p1_pushing bit still set from a prior
edge-rest frame and put Sonic airborne (y_vel=0, air=1) while the engine's
resolveMonitorContact had gated `pushing` on movingInto && !exactEdgeOverlap,
so the bit was never set, mainCharacterPushing stayed false, and the break
left Sonic grounded.  Fix: drop the movingInto/exactEdgeOverlap gates from
the push bit (keep them only on position correction / speed zeroing).
Frontier: MCZ f862 (618 errs) -> f903 (612 errs, +41 frames).  Pattern
applies to every solid object whose ROM uses standard SolidObject_cont
side resolution.)

---

## P17 -- Child object out_of_range uses own X instead of parent anchor, causing chunk-boundary unload

**Symptom.** A parent object's child (Seesaw ball, segmented body part, attached
hazard, swung weapon, etc.) silently vanishes shortly after the parent appears
on screen. The parent stays alive and behaves normally otherwise — its update
runs, players can stand on it, scroll handlers see it — but a feature that
depends on the child (ball launching the rider, body segment contact, attached
hazard hitbox) never fires. Trace replay shows the parent's state advancing
correctly while a player launch / contact event that requires the child silently
fails to trigger. The engine's `parent.ball` (or equivalent child reference) is
non-null and `child.destroyed=false`, but the child is no longer in
`ObjectManager.dynamicObjects` and is not in `execOrder[child.slot]`.

**Root cause.** The ROM dispatcher routes Obj14 (and similar parent+child
objects) through a single `Obj_Index` table that calls `MarkObjGone2` with
`d0 = objoff_30(a0)`. Crucially, `Obj14_Ball_Init` (`docs/s2disasm/s2.asm:47151`)
stores the parent seesaw's `x_pos` into `objoff_30(a0)` BEFORE applying the
ball's `addi.w #$28, x_pos(a0)` offset. So the ball's out-of-range check uses
the PARENT'S x position, not its own offset position. Both parent and ball
share the same camera-relative chunk reference and unload together.

A naive engine port adds the child via `addDynamicObjectAfterCurrent(child)` and
lets it fall back to the default `getOutOfRangeReferenceX()` which returns
`getX()` (the child's current position). The default
`ObjectManager.isOutOfRangeS1` formula rounds X to the nearest 128-byte chunk
(`objX & 0xFF80`) and compares against `(cameraX - 128) & 0xFF80`. For a
flipped seesaw whose parent is at `0x1520` and child at `0x1520 - 0x28 = 0x14F8`:
- Parent chunk: `0x1520 & 0xFF80 = 0x1500`
- Child chunk: `0x14F8 & 0xFF80 = 0x1480`
When the camera advances to `cameraX >= 0x1580`, the screen-rounded value is
`0x1500`. The parent's distance is `0x1500 - 0x1500 = 0` (in range), but the
child's distance is `0x1480 - 0x1500 = 0xFF80` unsigned = 65408 (way past the
640 threshold). The child alone is unloaded, leaving the parent's `child` field
pointing at an instance that's no longer in dynamicObjects.

**What to check.** For any parent+child pair where ROM's `Obj_Init` does the
sequence:

```
move.w x_pos(a0),objoff_30(a0)  ; save parent x BEFORE offset
addi.w #$xx,x_pos(a0)            ; apply child offset
[...]
move.b status(a0),status(a1)     ; copy parent status (with flip) to child
move.l a0,objoff_3C(a1)          ; store parent pointer for the child
```

then the child needs `getOutOfRangeReferenceX()` to return the parent's x_pos
(or, equivalently, the value saved in `objoff_30`). This applies to:
- Seesaw ball (`Obj14_Ball_Init` at s2.asm:47142)
- Any moving-solid child that's offset from its parent
- Body segments on Caterkiller / Crawl / multi-piece badniks
- Attached projectiles fired at a fixed offset from a parent gun mount

The fix is one method:

```java
@Override
public int getOutOfRangeReferenceX() {
    return parentCenterX;  // ROM objoff_30(a0) = parent x_pos
}
```

For S3K, the same rule applies whenever the disasm shows
`move.w x_pos(a0),objoff_30(a0)` immediately followed by `addi.w` /
`subi.w` on `x_pos(a0)` inside the child's init routine.

**ROM citation.** `docs/s2disasm/s2.asm:47151` (`Obj14_Ball_Init` saves seesaw_x
in objoff_30 before applying the ball offset), `s2.asm:46996`
(`Obj14` dispatcher's `move.w objoff_30(a0),d0 / jmpto JmpTo_MarkObjGone2`),
`s2.asm:30040-30057` (`MarkObjGone2` uses d0 = objoff_30 for the chunk-rounded
out_of_range comparison). Engine equivalent: any `AbstractObjectInstance`
subclass spawned as a positional offset from a parent should override
`getOutOfRangeReferenceX()` to return the parent's centre X.

**Originating commit.** `<pending>` (trace frontier advancement loop iter 9:
HTZ f4305 Sonic and Tails landed on the second (flipped) HTZ seesaw, but the
seesaw's ball had been unloaded the instant the camera entered chunk 0x1500
because the ball at 0x14F8 lived in chunk 0x1480. The seesaw's `ball` field
held a now-orphaned reference; `Obj14_Ball_Main`'s `objoff_3A` delta check
never ran, so the seesaw's `storedY=0x0760` launch velocity sat unused and
the players never went airborne. Adding `getOutOfRangeReferenceX()` =
parent seesaw x kept the ball alive while the parent is in range, restoring
the launch path. Frontier: HTZ f4305 (396 errs) -> f5044 (446 errs, +739 frames).
Pattern applies to every parent+offset-child pair in S2/S3K.)

---

## P18 -- Object bounce routines preserve unwritten velocity / inertia fields

**Symptom.** A bumper / launcher trace diverges immediately after touch:
ROM keeps the player's previous `x_vel`, `y_vel`, or `inertia`, while the
engine zeros one of them and changes the next solid-object contact or sidekick
follow state. CNZ f339/f340 surfaced this when ObjD7 Hex Bumper bounced Sonic
into Obj86 Flipper; ROM preserved the unwritten velocity component, but the
engine initialized both axes and cleared inertia.

**Root cause.** Many ROM object handlers write only the fields they need for
the chosen branch. ObjD7 left/right bounce writes `x_vel` only; up/down adjusts
the existing `x_vel` and writes `y_vel`; `ObjD7_BounceEnd` sets air / clears
status bits and plays sound, but does not clear `inertia`. A naive engine port
initializes `xVel = 0`, `yVel = 0`, then calls `setGSpeed(0)`, erasing ROM
state that later routines still observe.

**What to check.** For every object bounce / launch branch, list the exact ROM
writes. Preserve any velocity or inertia field the branch does not write:
initialize from the live player value, mutate only the written component, and
avoid clearing `gSpeed` unless the disassembly writes `inertia(a1)`.

**ROM citation.** `docs/s2disasm/s2.asm:59403-59454`
(`ObjD7_BouncePlayerOff`: left/right write only `x_vel`; up/down adjust
existing `x_vel` and write `y_vel`; bounce end does not clear inertia).

**Originating commit.** `<pending>` (trace frontier advancement loop iter 10:
CNZ ObjD7 Hex Bumper velocity preservation and TouchResponse timing advanced
the CNZ frontier from f202 to f507).

---

## P19 -- Shared monitor icon rewards use pre-move velocity tests

**Symptom.** A monitor reward applies one frame too early. In CNZ this made a
speed-shoes monitor double the player's air-control acceleration one physics
frame before the ROM did.

**Root cause.** The ROM monitor-content routine tests the icon's `y_vel`
before moving it. If the current rise step adds `$18` and lands exactly on
zero, the routine returns; the reward branch runs on the next object update.
A shared engine helper that applies the reward immediately after changing
`iconVelY` from negative to zero is one frame early.

**What to check.** For shared monitor code, verify S1, S2, and S3K before
changing the base routine. If all games match, keep it shared and cite all
three. If one differs, gate the behaviour at the owning abstraction instead
of changing every game implicitly.

**ROM citation.** S2 `docs/s2disasm/s2.asm:25618-25631`; S1
`docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:35-43`; S3K
`docs/skdisasm/sonic3k.asm:40723-40753` and S3-side
`docs/skdisasm/s3.asm:33392-33421`.

**Originating commit.** `<pending>` (trace frontier advancement loop iter 11:
CNZ speed-shoes monitor reward timing advanced the CNZ frontier from f976 to
f1146).

---

## P20 -- Level-event globals may need pre-object update order

**Symptom.** An object waits one frame too long for a zone-global routine to
finish. In CNZ, ObjD6 Point Pokey kept Sonic riding the cage for one extra
frame because the shared slot-machine manager was updated in the engine's late
zone-feature phase, after Point Pokey had already checked completion.

**Root cause.** S2 `LevEvents_CNZ` calls `SlotMachine` from the level-event
path, before the relevant object observes the global state. Treating the slot
machine as an ordinary late zone feature changed the producer/consumer order:
the global routine became inactive one frame too late from the object's point
of view.

**What to check.** When an object reads a zone-global manager or RAM flag,
locate the ROM writer and the ROM object execution order before choosing the
engine hook. Keep the ordering fix at the smallest owning scope. For CNZ this
means the slot-machine tick belongs in the S2 CNZ pre-physics/level-event
phase, while CNZ bumpers remain in the normal zone-feature update phase.

**ROM citation.** `docs/s2disasm/s2.asm:21494-21500`
(`LevEvents_CNZ` calls `SlotMachine`) and `docs/s2disasm/s2.asm:58827-58840`
(`SlotMachine` routine dispatch).

**Originating commit.** `<pending>` (trace frontier advancement loop iter 12:
CNZ Point Pokey / slot-machine ordering advanced the CNZ frontier from f1691
to f3830).

---

## P21 -- Sonic 2 object streaming is X-window only

**Symptom.** A placement object spawns hundreds of frames late when the route
passes above or below it. In CNZ, ObjD4 Big Block at `x=$0F00,y=$03A0` did not
exist until the camera-Y band reached it, leaving the oscillating block 537
updates behind the ROM at the first contact.

**Root cause.** S2 `ObjectsManager_GoingForward` / `ObjectsManager_GoingBackward`
load objects directly from the X-window scan via `ChkLoadObj`; there is no
`Camera_Y_pos` eligibility test in that path. Reusing a shared vertical spawn
filter for S2 exec-then-load placement silently delayed off-route objects whose
movement later affects the player.

**What to check.** For S2 object placement bugs, compare the object's update
count or phase against the ROM, not just its current coordinates. Keep the
vertical-filter bypass scoped to S2 placement; S3K and other games may still
need their own spawn-window rules.

**ROM citation.** `docs/s2disasm/s2.asm:32870-32950`
(`ObjectsManager_GoingBackward` / `ObjectsManager_GoingForward` call
`ChkLoadObj` from the X-window scan).

**Originating commit.** `<pending>` (trace frontier advancement loop iter 13:
S2 exec-then-load placement bypassed the vertical spawn filter and advanced the
CNZ frontier from f3830 to f3906).

---

## P22 -- Object-local capture may need previous-frame status

**Symptom.** A recapture on the same object is off by one pixel even though the
current object position, subtype, and player speeds match ROM. In CNZ, Tails
landed/re-landed on Obj85 LauncherSpring at the right X and subpixel, but the
engine treated the contact like a fresh non-rolling Tails capture and applied
the first-capture lift a second time.

**Root cause.** Some object routines observe player status as it existed before
the engine's current-frame normalization path. S2 Obj85's vertical capture
calls `SolidObject_Always_SingleCharacter`, then writes rolling/radii after the
standing bit is set. If engine-side physics has temporarily cleared the current
rolling flag before the object sees the contact, a port that checks only
`player.getRolling()` cannot distinguish a fresh Tails capture from a rolling
recapture. Use the player's recorded previous status when the ROM path depends
on that pre-normalized state.

**What to check.** For object-controlled capture/release paths, compare the
previous and current trace status bits before adding character-specific
position corrections. If a correction exists only to bridge engine top-left
hitbox semantics, gate it with the ROM-visible status history, not only the
current engine flag. Keep the hook object-local; do not change shared
SolidObject behavior for one object's capture quirk.

**ROM citation.** `docs/s2disasm/s2.asm:57520-57540`
(`Obj85_Up`/`loc_2AD26` captures after `SolidObject_Always_SingleCharacter`
sets the standing bit, then writes rolling/y_radius/x_radius).

**Originating commit.** `<pending>` (trace frontier advancement loop iter 14:
S2 Obj85 Tails recapture used previous-frame rolling status and advanced the
CNZ frontier from f3906 to f3957).

---

## P23 -- Full-solid bottom overlap may use live rolling y_radius

**Symptom.** A rolling airborne player is pushed sideways by a moving full
solid after ROM would already reject the vertical overlap. In CNZ, Sonic kept
ROM-correct air-control speed through ObjD4 Big Block, but the engine's solid
resolver classified a side contact at `relY=93`, snapped him 2 px right, and
zeroed `x_speed`.

**Root cause.** S2 `SolidObject_cont` adds the live `y_radius(a1)` to `d2`,
then doubles that same value for the lower reject bound. Rolling players
therefore use the smaller rolling radius on both the top and bottom halves.
The engine's default full-solid lower-half rule intentionally uses the taller
standing radius for some S2/S3K solids, but ObjD4 is a direct `SolidObject`
caller and needs the live-radius path.

**What to check.** When porting a full solid:
1. Read the exact helper it calls (`SolidObject`, `SolidObjectFull2`,
   `PlatformObject`, monitor variant, slope variant).
2. If the helper builds the lower bound from the same `d2 += y_radius(a1)`
   value used for the top bound, override
   `fullSolidBottomOverlapUsesCurrentYRadiusOnly(...)` on that object.
3. Keep the override object-local. Do not broaden shared lower-half behaviour
   unless all affected games and object families have been checked.
4. Trace symptom to look for: live position/speed matches ROM before solid
   contact, then the engine applies a sideways push/zero while ROM reports no
   contact and preserves air-control acceleration.

**ROM citation.** `docs/s2disasm/s2.asm:58348-58356` (ObjD4 passes
`d1=$2B,d2=$20,d3=$21` to `SolidObject`), `s2.asm:35135-35166`
(`SolidObject_cont` adds live `y_radius(a1)` to `d2`, doubles it, and rejects
when `d3 >= d4`).

**Originating commit.** `<pending>` (trace frontier advancement loop iter 15:
CNZ ObjD4 Big Block lower-half overlap used standing-radius height in the
engine and falsely side-pushed Sonic at f4074. Overriding
`fullSolidBottomOverlapUsesCurrentYRadiusOnly` advanced the CNZ frontier from
f4074 / 197 errors to f4121 / 227 errors).

---

## P24 -- Landing radius restore is not always shared across games

**Symptom.** A sidekick or object-controlled player stays one pixel too high
or too low on the first grounded frame after a launch/capture release, even
though position, subpixels, and speeds matched the previous frame. In CNZ,
Tails landed from Obj85 with ROM and engine both at `y=$0331`, then the engine
snapped to `y=$0330` on the next grounded frame and missed the following
Obj72-area airborne/rolling handoff.

**Root cause.** S2 `Tails_ResetOnFloor` only restores Tails's standing radii
inside the rolling branch. If Tails lands while already non-rolling but still
has object-written rolling radii (`y_radius=$0E,x_radius=7`), ROM leaves those
radii in place. The shared engine cleanup previously restored any non-rolling
custom radii to standing defaults, which is correct for S3K
`Player_TouchFloor` but not for S1/S2 reset-on-floor routines.

**What to check.** Before moving radius or landing cleanup into shared
playable code, read the reset routine for each game and character:

1. S1/S2 Sonic apply fixed roll-clear lifts only when rolling is set.
2. S2 Tails applies the one-pixel lift and `$0F/$09` radius restore only when
   rolling is set.
3. S3K restores default radii before checking roll state and uses the
   current-radius delta model.
4. Gate shared cleanup through the owning `GameRules` rule, provider/profile,
   or narrower object hook instead of assuming all games consume the same
   landing radii.

**ROM citation.** `docs/s2disasm/s2.asm:40629-40636`
(`Tails_ResetOnFloor_Part2` branches past radius restore when rolling is
clear), `docs/s2disasm/s2.asm:37781-37786` (S2 Sonic fixed rolling lift), and
`docs/skdisasm/sonic3k.asm:24341-24363` (S3K Player_TouchFloor restores
defaults and applies radius delta).

**Originating commit.** `<pending>` (S2 CNZ frame 5328 Tails Y mismatch was
caused by the shared non-rolling radius restore; gating it behind the S3K
radius-delta feature advanced the CNZ frontier from f5328 / 221 errors to
f5336 / 219 errors).

---

## P25 -- Obj85 preserved roll must suppress stale held jump, not fresh delayed press

**Symptom.** Tails either jumps too early out of the vertical Obj85 stopper
handoff, or never performs the later chamber-exit jump. In CNZ, letting all
delayed jump state through made Tails launch around frame 4028 while ROM stayed
grounded in the stopper. Suppressing all delayed jump state while the preserved
roll flag was set fixed that early launch but missed ROM's later fresh delayed
jump press at frame 5336.

**Root cause.** S2 Tails CPU copies Sonic's delayed logical input word before
the follow/filter path. Obj85's object-local preserved-roll handoff can leave a
held jump bit in that delayed sample while Tails is still grounded, but that is
not equivalent to a fresh press. The stale held bit must be suppressed during
the grounded preserved-roll handoff; the later fresh delayed jump press must
remain available so `Tails_Jump` can set `y_vel=-$680` and rolling air state.

**What to check.**
1. Keep Obj85 preserved-roll jump filtering object-scoped through the existing
   preserved-roll flag; do not change generic sidekick CPU jump semantics.
2. Distinguish delayed held jump from delayed jump press. Grounded preserved
   Obj85 frames with no fresh press should clear both held and press before
   `PlayableSpriteMovement` derives a new edge from held input.
3. Once Tails is airborne, do not clear held jump; the hold is used by jump
   height handling.
4. If another object needs similar handling, add a named object-owned marker
   rather than broadening the Obj85 gate.

**ROM citation.** `docs/s2disasm/s2.asm:38939-38946` (Tails CPU copies the
delayed `Ctrl_1_Logical` sample), `docs/s2disasm/s2.asm:57611-57625` (Obj85
vertical release path), and `docs/s2disasm/s2.asm:36996-37070`
(`Sonic_Jump`/`Tails_Jump` setup, including the `-$680` jump velocity).

**Originating commit.** `<pending>` (S2 CNZ frame 5336 Tails failed to enter
air+rolling because preserved-roll filtering cleared a fresh delayed jump
press. Suppressing only grounded stale held jump advanced the CNZ frontier from
f5336 / 219 errors to f5399 / 215 errors).

---

## P26 -- Riding solids can own stale logical horizontal input windows

**Symptom.** A player accelerates one or more frames before ROM while standing
on a moving/scripted solid, even though the trace CSV/BK2 input column already
shows the direction and the ROM `state_snapshot` sees no `move_lock` or
control lock. In CNZ, Sonic's right input appeared at frame 5997 while riding
ObjD5, but ROM inertia stayed zero until frame 6000. In MTZ3, Sonic's RIGHT
input was already visible at f12146 while riding Obj65 subtype 5 near platform
X `$28AE`, but ROM kept inertia zero for the first three input frames and then
consumed the later right edge near X `$28FC` immediately.

**Root cause.** Some solid-helper/object phase combinations expose BK2-aligned
input before the player movement routine consumes the corresponding logical
horizontal value for the sampled physics row. Treating that as a game-wide
input offset breaks nearby jump/input edges. The timing belongs to the current
riding object/helper, not to all S2 movement.

**What to check.**
1. When a trace shows early acceleration while the player is riding a concrete
   solid, inspect the object's exact helper (`PlatformObject`, `PlatformObjectD5`,
   direct `SolidObject`, or bespoke checkpoint) before changing shared input
   handling.
2. Prefer the `SolidObjectProvider.staleHorizontalLogicalInputFramesWhileRiding`
   hook with a default of zero. Override it only on the object whose helper
   proves the stale window.
3. Keep existing object-specific windows on the owning object. SCZ Tornado,
   CNZ ObjD5, and MTZ Obj65 use the hook; shared movement should not branch on
   game id or object id directly.

**ROM citation.** `docs/s2disasm/s2.asm:58435-58443` (ObjD5 calls
`PlatformObjectD5` after its state routine), `docs/s2disasm/s2.asm:35617-35657`
(`PlatformObjectD5` continued-riding/skip-existing-platform helper), and
`docs/s2disasm/s2.asm:35402-35420` (`MvSonicOnPtfm` writes rider position).
For MTZ Obj65, see `docs/s2disasm/s2.asm:53159-53220` (subtype 5 conveyor and
MTZ3 stop checks) plus `docs/s2disasm/s2.asm:36552-36567`
(`Sonic_Move` logical horizontal consumption).

**Originating commit.** `<pending>` (S2 CNZ frame 5997 Sonic accelerated three
frames before ROM while riding ObjD5. Moving stale horizontal suppression to a
per-solid hook and opting in ObjD5 advanced the CNZ frontier from f5997 / 197
errors to f6018 / 289 errors while S1 GHZ and S2 EHZ stayed green). `<pending>`
S2 MTZ3 Obj65 second-stop stale logical window advances the MTZ3 frontier from
f12146 / 650 to f12592 / 497.

---

## P27 -- SolidObject_Always objects must bypass offscreen full-solid gates

**Symptom.** A sidekick or offscreen-adjacent player passes through the side of
an invisible/full solid even though ROM zeros `x_vel` and `inertia` at the
solid edge. In CNZ, Tails reached Obj74 at `x=$1535` while airborne/rolling;
ROM stopped him against the left edge, but the engine reported Obj74 as
`no-touch` and kept accelerating.

**Root cause.** Obj74 does not call the regular `SolidObject` helper. It calls
`SolidObject_Always`, whose disassembly comment explicitly says Obj74/Obj30
check solidity even if the object is offscreen. Applying the shared
sidekick-on-screen/full-solid offscreen gate to Obj74 skips exactly the side
contact ROM still resolves.

**What to check.**
1. For every solid object, identify the exact helper it calls before assuming
   the normal render/on-screen gate applies.
2. If the helper is `SolidObject_Always` or
   `SolidObject_Always_SingleCharacter`, override
   `bypassesOffscreenSolidGate()` on that object/class.
3. Keep the bypass per object/helper. Do not disable the shared offscreen gate
   for all S2 solids, because the regular `SolidObject` P2 path still gates on
   sidekick render state.

**ROM citation.** `docs/s2disasm/s2.asm:34863-34873`
(`SolidObject_Always` / `SolidObject_Always_SingleCharacter`) and
`docs/s2disasm/s2.asm:46152-46161` (`Obj74_Main` calls
`SolidObject_Always` after deriving subtype dimensions).

**Originating commit.** `<pending>` (S2 CNZ frame 6018 Tails missed Obj74's
left-edge side stop because the engine applied the offscreen sidekick full-solid
gate to a `SolidObject_Always` caller).

---

## P28 -- SPECIAL touch objects use Touch_Sizes radii and object-specific bounce tails

**Symptom.** A SPECIAL object bounces or triggers several frames too early, or
the immediate post-bounce physics fields differ even though the written
velocity matches ROM. In CNZ, ObjD8 applied `y_vel=-$700` at frame 6276 while
ROM was still falling, then later zeroed `inertia` even though ROM preserved
`$040E`.

**Root cause.** S2 `collision_flags` low six bits index the `Touch_Sizes` table,
whose bytes are X/Y radii, not full width/height. ObjD8 sets
`collision_flags=$D7`, selecting `Touch_Sizes[$17] = 8,8`; replacing this with
an approximate center-distance box changes the trigger frame. Also read the
object's common bounce tail literally: ObjD8's `loc_2C806` sets in-air and
clears roll-jump/pushing/jumping, but does not clear `inertia`.

**What to check.**
1. Decode `collision_flags & $3F` and use the `Touch_Sizes` radii before
   writing any manual SPECIAL-object overlap.
2. Prefer the shared touch-response rectangle math. If an object must poll its
   own `collision_property` or cooldown bytes, copy the ROM rectangle shape
   locally rather than inventing a center-distance approximation.
3. Do not assume all object rebounds clear `inertia`. Check for an explicit
   `clr.w inertia(a1)` in the object routine before calling `setGSpeed(0)`.

**ROM citation.** `docs/s2disasm/s2.asm:59570` (ObjD8
`collision_flags=$D7`), `docs/s2disasm/s2.asm:84623` (`Touch_Sizes[$17] =
8,8`), and `docs/s2disasm/s2.asm:59687-59692` (ObjD8 bounce tail does not
clear `inertia`).

**Originating commit.** `<pending>` (S2 CNZ frame 6276 early ObjD8 bounce and
frame 6281 inertia mismatch; fixing ObjD8 touch radii and preserving inertia
advanced the CNZ frontier to frame 6815).

---

## P29 -- Moving objects may own bespoke out_of_range delete bounds

**Symptom.** A moving object disappears before ROM would delete it, so later
collisions or bounces are missing even though the spawn record and movement
routine are correct. In CNZ, the moving ObjD7 Hex Bumper from spawn
`x=$1FF8,y=$028C,subtype=1` was gone by frame 8082; ROM still had it alive at
slot 38 and used it to launch Sonic left.

**Root cause.** Not every object tail-calls the standard `MarkObjGone` /
`out_of_range` macro with the current object X. Moving ObjD7 runs its animation
and movement, then checks both `objoff_30` and `objoff_32` movement bounds. It
only deletes when both bounds are outside the camera window, so a single-X
generic delete check can remove it too early.

**What to check.**
1. Read the end of the object routine before assuming the shared
   counter-based out-of-range path is correct.
2. If the ROM routine tests range endpoints, parent anchors, or other custom
   words, prefer the narrowest per-object hook over a game-wide behavior flag.
3. Keep stationary subtypes on the shared path unless the stationary ROM
   routine also bypasses the standard macro.

**ROM citation.** `docs/s2disasm/s2.asm:59489-59510` (moving ObjD7 tests
`objoff_30` and `objoff_32`, displaying if either bound remains in range and
deleting only after both fail the range check).

**Originating commit.** `<pending>` (S2 CNZ frame 8082 missing moving ObjD7;
keeping ObjD7 alive by its ROM movement bounds advanced the CNZ frontier to
frame 8419).

---

## P30 -- `bmi` countdown timers fire at -1, not 0

**Symptom.** A badnik or object waits one frame longer than ROM before a state
transition. Timer-indexed trace fields (e.g. `timer`, `scriptTimer`) match ROM
for one extra frame, then diverge by 1 as the state-machine branch fires one
frame late. OOZ1/OOZ2 trace: Octus hovered one frame lower than ROM (4 px
extra downward displacement) because the rise delay fired late.

**Root cause.** ROM countdown loops use:
```
subq.w  #1, timer(a0)
bmi.s   <transition>
```
`bmi` ("branch if minus") fires when the result is negative — that is, when the
timer decrements from 0 to -1 (the **next** frame after it reaches 0). Java
`if (timer <= 0)` fires when the timer reaches 0, one frame early. The correct
port is `timer--; if (timer < 0)`.

**What to check.** For every countdown timer in a ROM object routine, find the
branch instruction: `bmi` fires at -1, `beq` fires at 0, `bne` fires as long as
the value is non-zero. Do not use `<= 0` as a default "timer expired" check —
read the actual branch opcode in the disassembly.

**ROM citation.** `docs/s2disasm/s2.asm:59958-59967` (Octus `ObjA2_DelayBeforeRise`:
`subq.w #1, d1 / bmi.s ObjA2_Rise` — fires when d1 wraps to -1). The same pattern
appears in virtually every S2/S3K object's wait/delay phase.

**Originating commit.** `31567cb35 Fix S2 Octus collision size and rise-state
transition timing`.

---

## P31 -- Property table byte-offset mistakenly divided as entry index

**Symptom.** Object selects the wrong art frame, wrong collision dimensions,
wrong speed constant, or wrong movement waypoint from an object properties table.
The wrong frame or dimension is consistent (not random) and is typically one or
two entries away from the correct one. MTZ LongPlatform (Obj65) trace: platforms
always selected the wrong mapping_frame (and wrong props) because the table index
was off by a factor of 2 in the byte dimension.

**Root cause.** ROM property tables are addressed by byte offset via
`lea Table(pc,d0.w), a1` — `d0` is a byte offset, not a 0-based entry index.
A 2-byte-per-field table addressed with `d0 = subtype << 2` means the byte
offset is `subtype * 4`; the engine's `entryIndex = d0 / 4` collapses the
separate byte strides used for the first field (byte offset ÷ 2 → entry) and the
art frame index (byte offset ÷ 4 → frame). These two derived values must be
computed from the raw byte offset independently:
```
int entryIndex = d0 >> 1;   // byte offset / sizeof(word) = entry number
int frameIndex = d0 >> 2;   // byte offset / sizeof(longword) = art frame
```
Using `d0 >> 2` for both silently picks the wrong entry in the props table while
occasionally getting the frame right, giving inconsistent but deterministic
errors.

**What to check.** When a ROM routine has:
```
moveq   #<N>, d0
move.b  subtype(a0), d1
mulu.w  d1, d0
lea     SomeTable(pc,d0.w), a1
move.w  (a1)+, firstField(a0)   ; first word read
move.w  (a1),  secondField(a0)  ; second word read
```
trace the byte offset `d0` at the `lea` and at every subsequent `move.w`. Each
derived value (frame index, dimension, speed) is the byte offset divided by the
field size. Don't collapse them to a single Java `index = d0 / totalStride`.

**ROM citation.** `docs/s2disasm/s2.asm:52366-52376` (`Obj65_Properties` table,
2 words per entry), `s2.asm:52386-52394` (`Obj65_Init`: `mulu.w #4,d0 /
lea Obj65_Properties(pc,d0.w),a1 / move.w (a1)+,d1 / move.w (a1),d2`).

**Originating commit.** `a574826b6 Fix S2 MTZ SteamSpring timing and
MTZLongPlatform props-lookup`.

---

## P32 -- Solid checkpoint must run before state-machine position update, not after

**Symptom.** A player riding a vertically-moving solid is one frame behind ROM's
position during the transition. Specifically, the player lands on the rising
platform's pre-move surface in the engine but ROM places them at the pre-move
surface too — yet ROM then launches them (spring-fires / snaps position) one
frame before the engine does. MTZ1 trace: SteamSpring didn't fire until one frame
after ROM because the engine ran the state machine (which moved the spring up)
before the solid checkpoint.

**Root cause.** ROM `Obj42` (`loc_26688`) calls `SolidObject_Always_SingleCharacter`
**first** at the top of every frame, then the state machine branches run and update
`y_pos`. This means the solid contact sees the **pre-move** surface. The engine
naively put the state machine first (updating `yOffset` → new platform Y) and
then ran `checkpointAll()`, so players saw the post-move surface and the spring
fire was delayed by one frame.

```
; ROM order (s2.asm:52030-52049):
loc_26688:
    bsr SolidObject_Always_SingleCharacter   ; contact on PRE-move y
    bsr Obj42_StateMachine                   ; NOW update y_pos

; Naive engine order (WRONG):
void update() {
    updateStateMachine();   // moves yOffset first
    checkpointAll();        // contact on POST-move y  ← one frame late
}
```

The same rule applies when an object must fire a spring/launch from the contact
result before the position changes: capture the contact batch before the
state machine, then use the batch to decide whether to launch.

**What to check.** For any vertically (or horizontally) moving solid: find the
ROM dispatch order in `Obj_Main`. If the SolidObject/PlatformObject call appears
before the movement code, put `checkpointAll()` / `resolveSolidNowAll()` at the
top of `update()` before any position mutation. See also P15 (slope state update
order) for the complementary rule on sloped solids.

**ROM citation.** `docs/s2disasm/s2.asm:52030-52049` (`loc_26688`:
`SolidObject_Always_SingleCharacter` called BEFORE the `Obj42` state-machine
dispatch). `s2.asm:52121-52124` (`loc_2678E`: spring fire inside the standing
player loop, also pre-move).

**Originating commit.** `a574826b6 Fix S2 MTZ SteamSpring timing and
MTZLongPlatform props-lookup`.

---

## P33 -- Per-game rule/profile/provider values must be set to the correct ROM value when gates are added

**Symptom.** A per-game rule/profile/provider value is added to gate new
behaviour, the guard is wired into the owning code path, and all existing tests
still pass — but the trace diverges at the exact frame the guarded behaviour
should fire, because the value is set to the wrong default for one or more games.
CNZ2 trace regressed from f1490 to f936 when the legacy
`pinballLandingPreservesPinballMode` gate was added with `false` in `SONIC_2`
even though S2 ROM preserves pinball mode on landing.

**Root cause.** Per-game gates need an explicit owner and a complete Sonic 1 /
Sonic 2 / Sonic 3&K value table. Use
`docs/architecture/per-game-rule-placement.md` to choose the smallest accurate
owner before adding the gate. It is easy to wire the guard, verify that one or
two games behave correctly, and forget to set the value for the remaining game.
Unit tests rarely cover the exact multi-frame state required to exercise a
newly-gated branch, so the error is silent until the trace replay runs.

**What to check.** When adding any per-game rule/profile/provider value:
1. Open the disassembly for ALL three games and find the equivalent routine.
2. Choose the owner using `docs/architecture/per-game-rule-placement.md`.
3. Set the correct Sonic 1, Sonic 2, and Sonic 3&K values immediately — never
   leave any game at the fallback default unless you have verified the
   disassembly confirms it.
4. If a game's behaviour is unknown, mark it `TODO` in a comment beside the
   constant and log it in `docs/status/known-discrepancies.md`, but do not leave the
   wrong value silently in place.
5. Run the relevant trace replay for all three games after the change.

**ROM citation.** `docs/s2disasm/s2.asm:37770-37771` (`Sonic_ResetOnFloor` S2:
`bclr #status.player.in_pinball_mode,status(a1)` is absent — pinball mode is
NOT cleared on landing), `s2.asm:40625-40626` (S2 `Tails_ResetOnFloor_Part2`:
same omission). Compare S1 which does NOT have pinball mode at all, and S3K
which has its own `Player_TouchFloor`.

**Originating commit.** `7eaa19993 Preserve S2 pinball_mode mirror across
landing to restore CNZ2 frontier`.

---

## P34 — `Ctrl_1` byte-read is just-pressed edge, not held state

**Symptom.** An object that grabs or releases the player on button press
triggers one frame too early (on the frame the button is held from a prior
action) rather than only on a fresh press.

**Root cause.** ROM reads `move.w (Ctrl_1).w, d0` — this loads a word where
the **high byte is `Ctrl_1_Held`** (currently held buttons) and the **low
byte is `Ctrl_1_Press`** (buttons pressed this frame only). Any subsequent
`andi.b #buttons, d0` or `btst #button, d0` operates on the **low byte**
(`Ctrl_1_Press`), so the check tests just-pressed state. Engine methods like
`isJumpPressed()` return held state; `isJumpJustPressed()` (or its
equivalent) returns the just-pressed edge. If the object grabs on a held
check, it immediately releases the player the same frame the player arrived
via a held jump.

**What to check.** Whenever the ROM does
`move.w (Ctrl_1).w, d0 / andi.b #..., d0` or
`move.w (Ctrl_2).w, d0 / andi.b #..., d0`, the engine must use
`isJumpJustPressed()` / `isActionJustPressed()`, not the `*Pressed()` held
variants. This applies equally to grab-initiation, grab-release, and any
other button-gated state transition in object code.

**ROM citation.** `Obj7F_Action` (`s2.asm:56083-56106`):
`move.w (Ctrl_1).w,d0 / andi.b #button_B_mask|button_C_mask|button_A_mask,d0`.

**Originating commit.** `d14450c48 Fix S2 MCZ VineSwitch (0x7F) release input
and Tails grab`.

---

## P35 — Sidekick pass left as "Player 2 deferred" stub

**Symptom.** Object behaves correctly for Sonic but Tails can never interact
with it, or the interaction counter diverges whenever Tails reaches the object
first (wrong player targeted, wrong timing).

**Root cause.** ROM typically processes both `MainCharacter` and `Sidekick`
in sequence: `lea (MainCharacter).w,a1 / bsr Obj_Action` then
`lea (Sidekick).w,a1 / bsr Obj_Action` (or an analogous loop). Engine
implementations often stub the second pass with a `// Player 2 deferred`
comment and never fill it in. As a result, Tails cannot grab vines, trigger
switches, or interact with any object that explicitly processes both sprites.

**What to check.** After implementing the main-player interaction, search the
disassembly for a second `a1` load targeting `Sidekick` or `Ctrl_2` within
the same sub-routine. Implement the sidekick pass using
`services().sidekicks()` before committing. Do not leave "Player 2 deferred"
stubs in production code; they silently break two-player trace parity.

**ROM citation.** `Obj7F_Action` (`s2.asm:56071-56080`):
`lea (MainCharacter).w,a1 / move.w (Ctrl_1).w,d0 / bsr.s Obj7F_Action` then
`lea (Sidekick).w,a1 / move.w (Ctrl_2).w,d0 / bsr.s Obj7F_Action`.

**Originating commit.** `d14450c48 Fix S2 MCZ VineSwitch (0x7F) release input
and Tails grab`.

---

## P36 — `move.b #2,routine(a1)` clears the Hurt routine; engine must call `setHurt(false)`

**Symptom.** After a state-changing object interaction (spring launch, vine
release, conveyor exit, teleporter, etc.) the engine player's airborne gravity
stays at +$30 instead of +$38, and `Sonic_UpVelCap` (-$FC0) is skipped.
Trace-replay y_speed diverges by exactly the cap delta (0x40) plus an extra
0x08-per-frame gravity-step shortfall when the player launched from a hurt
state.

**Root cause.** ROM dispatches the player's outer state through
`Obj01_Index` / `Obj02_Index`: `0=Init, 2=Control, 4=Hurt, 6=Dead, 8=Gone`.
The Hurt routine (`Obj01_Hurt loc_1B12C`) runs its own physics tick with
`addi.w #$30,y_vel(a0)` and no upward velocity cap. ROM objects that "wake"
the player into normal play write `move.b #2,routine(a1)` directly,
unconditionally clearing the Hurt routine. The next player tick then dispatches
to `Obj01_Control` -> `Obj01_MdAir`, which uses +$38 gravity and the
`Sonic_UpVelCap` cap (`s2.asm:37113`).

The engine encodes Hurt as a boolean `hurt` field on the sprite, and the
trace test maps `isHurt() -> rtn=04` for comparison.
`PlayableSpriteMovement.airbornePhysics()` short-circuits `doJumpHeight()`
(and therefore the velocity cap) when `hurt=true`;
`AbstractPlayableSprite.getGravity()` returns 0x30 instead of 0x38 when hurt.
Without an explicit `setHurt(false)` in the engine object code, the
spring/vine/launcher launches Sonic but leaves him in the hurt physics
regime.

**What to check.** For every object that writes `move.b #2,routine(a1)` in
ROM:
- The Vertical/Diagonal Spring branches (`Obj41_Up`, `Obj41_Down`,
  `Obj41_DiagonallyUp`, `Obj41_DiagonallyDown` — `s2.asm:33735, 34023,
  34090, 34173`) all clear the routine. Note that `Obj41_Horizontal`
  does NOT, because horizontal springs keep the player grounded.
- `Touch_ChkValue` post-hurt recovery branches.
- Object-control exits (e.g., `loc_298E6` in `Obj7F` for vine grab).
- Teleporters, launchers, and tubes that take over the player and then
  release it back into Control.

Mirror the ROM by calling `player.setHurt(false)` alongside the velocity /
position assignment that ROM does under the `move.b #2,routine(a1)` line.
Do NOT also reset `invulnerable_time` — ROM keeps the existing value, and
`AbstractPlayableSprite` already exposes invulnerability via a separate
counter that the spring does not touch.

**ROM citation.** Spring up `Obj41_Up loc_189CA` (`s2.asm:33728-33735`):
```
loc_189CA:
    move.w  #(1<<8)|(0<<0),anim(a0)
    addq.w  #8,y_pos(a1)
    move.w  objoff_30(a0),y_vel(a1)
    bset    #status.player.in_air,status(a1)
    bclr    #status.player.on_object,status(a1)
    move.b  #AniIDSonAni_Spring,anim(a1)
    move.b  #2,routine(a1)                ; <-- clears Hurt
```

**Originating commit.** Fix S2 vertical/diagonal Spring (Obj41) clears Hurt
routine on launch — advances MCZ2 frontier from 925 to 1006.

---

## P37 — Parent-spawner factory returning `null` re-spawns children every frame

**Symptom.** A "parent-spawner" object (e.g. MTZ Obj6C with subtype bit 7 set)
spawns N children to form a cluster. The cluster appears to work at first, but
something downstream is subtly off — landing positions for the player are 5-10
pixels misaligned, child phase relationships drift, or a single child slot at
an unusually high slot index (engine slot 127 for S2 with 112 dynamic slots)
turns out to be the one the player actually stands on. The "lost" object
debug formatter / `eng-near` window may even omit the player's standing
target because the formatter truncates to the first ~12 slots by index.

The MTZ2 case: Sonic landed 7 pixels below the ROM landing height because the
engine had silently spawned dozens of redundant conveyor cohorts, and the
cohort he physically landed on was one freshly re-spawned several frames after
camera entry, not the original cohort the ROM tracks.

**Root cause.** The factory pattern used `return null` to mean "I spawned my
children via `ObjectManager.addDynamicObject`; do not register a parent
instance." The `inlineCreateObject` / `applyPendingSpawns` paths interpret a
`null` factory result as "spawn failed" and release the pre-allocated parent
slot WITHOUT calling `registerActiveObject(spawn, instance)`. Because the
parent `ObjectSpawn` is never added to `activeObjects`, the placement's
"already loaded" gate (`!activeObjects.containsKey(spawn)`) lets the parent
re-enter `sortedNewSpawns` every frame the camera keeps the chunk in window.
Each re-entry spawns a full N-child cohort, filling slots and producing
multiple parallel cohorts of the same cluster with different waypoint phases.

ROM does not have this problem because the parent's `Obj6C_Init` reuses the
parent's own SST entry as the first child (`movea.l a0,a1`), overwriting its
subtype with the first child's subtype (clearing bit 7). The parent slot
becomes a regular child object that runs `Obj6C_Main` from the next frame on
and never re-enters the `loc_28112` parent-spawn branch.

**Fix.** Mirror the ROM "parent becomes first child" pattern. The factory
constructs the first child from `layout[0]` (using the parent's own
`ObjectSpawn` slot), spawns the remaining N-1 children via
`addDynamicObject`, and returns the first child instance. Because the factory
now returns non-null, `registerActiveObject` runs, `activeObjects` contains
the spawn, and the placement does not re-enter the spawn into
`sortedNewSpawns` on subsequent frames.

**What to check.**
1. Any factory in `*ObjectRegistry.java` that returns `null` on a "parent
   subtype set" path. Greppable pattern: `(subtype & 0x80) != 0` /
   `return null;` inside a static factory.
2. Cross-reference with ROM `Init`: if the ROM uses `movea.l a0,a1` (or
   equivalent) to write the first child into the parent slot, replicate by
   returning the first child from the factory rather than `null`.
3. Verify after the fix that `eng-near` only shows the expected N child
   instances at the cluster (no duplicates at incrementing slot numbers,
   no slot index >> the rest of the cluster).
4. Watch for child base-position inheritance: parent-spawned children use
   the parent's `x_pos/y_pos` as `objoff_30/objoff_32` (waypoint base), not
   their own per-child layout offset. The engine constructor must accept an
   explicit `baseX/baseY` distinct from the child's spawn position.

**ROM citation.** S2 Obj6C `loc_28112`/`Obj6C_LoadSubObject`
(`docs/s2disasm/s2.asm:54269-54301`): `movea.l a0,a1` sets the first child
target to the parent's own slot; the `dbf` loop then `JmpTo8_AllocateObject`s
remaining children. After the loop `addq.l #4,sp; rts` unwinds the
intermediate stack frame and returns to `Obj6C`'s post-jsr instruction.

**Originating commit.** Fix S2 MTZ2 Conveyor (Obj6C) parent factory re-spawn
loop — engine now returns the first child from the factory instead of `null`,
so `activeObjects` records the spawn and placement stops re-spawning the
cluster every frame. Advances MTZ2 frontier y mismatch from 7 px to 1 px at
frame 305.

---

## P38 — `SolidObject` contact mutates velocity before hurt helpers read it

**Symptom.** Upside-down spikes or similar full-solid hazards hurt the player
at the right frame, but the post-hurt Y/subpixel or knockback state is off by
1-2 pixels. Trace context often shows the engine using a "pre-contact" velocity
while the ROM has already zeroed or changed that velocity inside the solid
routine.

**Root cause.** S2 Obj36 calls `SolidObject` first, then checks the returned
touch mask and calls `Touch_ChkHurt2`. `SolidObject_cont` / inside-contact
branches may mutate `y_vel(a1)` before returning. `Touch_ChkHurt2` reads the
current `y_vel(a1)`, not a saved pre-solid value, then subtracts
`y_vel<<8` from `y_pos` before `HurtCharacter`.

**What to check.** When an object calls `SolidObject` or
`SolidObject_Always*` before a hurt/helper routine, preserve the ROM call
order. Do not feed hurt helpers an ObjectManager pre-contact snapshot unless
the ROM saved one explicitly. Also check S2 `SolidObject_cont` lower-Y bounds:
it doubles the live `y_radius(a1)`, so rolling underside contact can differ
from ports that reuse a standing/default radius.

**ROM citation.** S2 Obj36 upside-down spike path
(`docs/s2disasm/s2.asm:29260-29283`) calls `SolidObject` before
`Touch_ChkHurt2`; `Touch_ChkHurt2` subtracts current `y_vel<<8`
(`docs/s2disasm/s2.asm:29297-29312`). S2 `SolidObject_cont` doubles live
`y_radius(a1)` for its lower-Y reject bound
(`docs/s2disasm/s2.asm:35156-35169`).

**Originating commit.** Fix S2 MTZ3 Obj36 spike contact/hurt ordering -- MTZ3
frontier advances from frame 3603 to frame 3617.

---

## P39 -- Same object ID can dispatch to different objects by subtype/routine

**Symptom.** A placement with an already-implemented object ID does nothing, or
uses the wrong movement/contact math, even though the engine has a class for
that ID. Trace context shows the ROM object ID matches the engine object ID, but
the ROM routine byte and nearby state do not match the implemented path.

**Root cause.** S2 objects often multiplex distinct behaviours under one ID.
Obj06 is both the EHZ spiral and the MTZ cylinder: `Obj06_Init` branches to
`Obj06_Cylinder` when the subtype is negative, setting `routine=4`, while
non-negative subtypes follow the spiral-path controller. Treating every Obj06
placement as a spiral leaves MTZ cylinder placements invisible to the player.

**What to check.** During init-porting, do not stop once the object ID maps to a
class. Read the init routine's subtype branches and routine writes, then make
sure each branch has an engine mode. For sine/cosine helpers, also preserve the
ROM return register: `CalcSine` returns sine in `d0` and cosine in `d1`; a path
that multiplies `d1` must use cosine, not sine.

**ROM citation.** S2 Obj06 init/cylinder path (`docs/s2disasm/s2.asm:46720-46811`,
`s2.asm:46853-46931`): negative subtype branches to `Obj06_Cylinder`; the
active rider path calls `CalcSine` and multiplies `d1` by `$2800` for the
vertical offset.

**Originating commit.** Fix S2 MTZ3 Obj06 cylinder mode -- MTZ3 frontier
advances from frame 4280 to frame 4656.

---

## P40 -- Native `x_pos` / `y_pos` writes must preserve the sibling subpixel byte

---

## P41 -- Constructor-modeled child init must not also run main routine on the spawn frame

**Symptom.** A child projectile or helper object is positionally correct at
spawn but drifts into contact one frame early or late. Trace diagnostics show
the child exists in the ROM and engine on the same frame, but the engine has
already applied the child's routine-2 movement while the ROM is still running
routine 0 initialization.

**Root cause.** ROM `FindNextFreeObj` / `AllocateObjectAfterCurrent` inserts a
child into the SST. If that slot is later in the current object pass, the ROM
does reach the new object on the same frame, but its `routine=0` init code runs
first and then returns. Java ports often apply that routine-0 setup in the
constructor, so allowing a same-frame `update()` makes the first engine update
represent the ROM's next-frame routine-2 code.

**What to check.** For any child whose constructor fills fields that ROM writes
in the child's routine-0 label, compare the child init routine with its main
routine. If the constructor already models routine 0 and `update()` starts at
routine 2, override `skipsSameFrameUpdateAfterSpawn()` so the first main update
waits until the next object pass. Do not disable same-frame execution globally;
ordinary children still need ROM slot-order execution when their Java update
models the same routine the ROM reaches.

**ROM citation.** `docs/s2disasm/s2.asm:75496-75520` (`ObjA1_LoadPincers`) and
`docs/s2disasm/s2.asm:75451-75475` (`ObjA2_Main`).

**Originating commit.** `fix(s2): defer constructor-initialized child main ticks`.

**Symptom.** Player integer position matches ROM after an object snaps or carries
the player, but `x_sub` or `y_sub` diverges. The next movement frame then drifts
by one or more pixels because ROM kept the existing subpixel residue while the
engine cleared it.

**Root cause.** ROM object code often writes only the native position word:
`move.w x_pos(a0),x_pos(a1)` or `move.w y_pos(a0),y_pos(a1)`. That changes the
integer word and leaves the adjacent subpixel byte/word untouched. Engine code
that uses `setCentreX(...)` / `setCentreY(...)` for a playable sprite rewrites a
higher-level coordinate and can clear or recompute the subpixel state.

**What to check.** When porting object code that writes `x_pos(a1)` or
`y_pos(a1)` directly to a playable sprite, use `NativePositionOps`:
`writeXPosPreserveSubpixel`, `writeYPosPreserveSubpixel`, or the corresponding
add helpers. Reserve raw centre setters for code paths where ROM also resets the
subpixel half or where the target is an object-local/non-playable coordinate.

**ROM citation.** S2 Obj69 Nut align and screw modes
(`docs/s2disasm/s2.asm:53566-53568`, `s2.asm:53579-53582`,
`s2.asm:53626-53629`) write `move.w x_pos(a0),x_pos(a1)` while leaving
`x_sub(a1)` intact.

**Originating commit.** Fix S2 MTZ3 Obj69 nut x_pos snap preserves player
x_sub -- MTZ3 frontier advances from frame 4793 to frame 5143.

---

## P42 -- Self-deleting transient anim timing must mirror the ROM `anim_frame_duration` countdown, not a uniform per-frame delay

**Symptom.** A short-lived self-deleting object (explosion, points popup,
sparkle) occupies its SST slot a few frames longer (or shorter) than ROM, even
though the player physics are fine. An object-occupancy oracle shows the engine
still holding the transient's id one or more frames after the ROM
`DeleteObject` / `Delete_Current_Sprite`. The lifespan is wrong by a small fixed
amount across every spawn of that object.

**Root cause.** ROM animation-driven transients run a predecrement-reload
countdown each frame, with a **different initial duration on the first/setup
frame** than the reload value:
```
subq.b  #1, anim_frame_duration(a0)   ; predecrement (see P30: fires at -1)
bpl.s   +                             ; still >= 0 -> just display this frame
move.b  #7, anim_frame_duration(a0)   ; reload
addq.b  #1, mapping_frame(a0)         ; advance frame
cmpi.b  #5, mapping_frame(a0)         ; reached the delete frame?
beq.w   DeleteObject
+ DisplaySprite
```
A Java port that approximates this with a single uniform `ANIM_DELAY` (e.g.
"advance every 8 frames, delete after N frames") gets the frame-0 hold wrong:
frame 0 in ROM lasts `initialDuration + 1` game frames, not `reload + 1`. For
the badnik-death explosion (Obj27) the ROM init duration differs **per game** —
S1 `ExItem_Main` loads `move.b #7`, while S2 `Obj27_Init` / S3K `loc_1E626`
load `move.b #3` — so a uniform delay that happened to match S1 (frame 0 = 8
game frames, delete +39) left S2/S3K lingering 4 frames past `DeleteObject`
(delete should be +35).

**What to check.** For any self-deleting animated transient: (1) port the exact
`subq/bpl/reload/advance/cmp/delete` loop, not a uniform delay; (2) read the
`move.b #N,anim_frame_duration` value in the **init/setup routine** separately
from the reload `move.b #N` in the animate routine — they are usually different;
(3) confirm whether the init duration differs per game and, if so, expose it as
object animation data at the owning boundary (e.g. a defaulted `GameModule`
accessor resolved at the object's first update) rather than a `gameId` branch;
(4) the first `update()` corresponds to the ROM init->main same-frame
fall-through (the spawn frame), so deletion lands `lifespan` game frames after
that first update. See also P30 (`bmi`/`bpl` countdowns fire at -1).

**ROM citation.** S2 `Obj27_Init`/`Obj27_Main` `docs/s2disasm/s2.asm:46672-46684`
(init `#3`, reload `#7`, delete at mapping_frame 5); S3K `loc_1E626`/`loc_1E66E`
`docs/skdisasm/sonic3k.asm:42195-42205` (init `#3`); S1 `ExItem_Main`/
`ExItem_Animate` `docs/s1disasm/_incObj/27, 3F Explosions.asm` (init `#7`).
Points popup Obj29 (`docs/s2disasm/s2.asm` `Obj29_Main`) is the velocity-driven
variant: delete when `y_vel >= 0`, 32 frames after spawn.

**Originating commit.** Object-lifetime piece (a): `ExplosionObjectInstance`
ROM-exact self-delete + `GameModule.explosionInitialAnimDuration()`; enables the
`TestS2ObjectOccupancyOracle` Obj27 assertion on green EHZ1/SCZ/WFZ.

---

## P43 — Rideable `SolidObject`/`SolidObjectFull` solid must opt into the standing-branch air-unseat or it side-pushes/carries a jump-off rider one frame early

**Pattern.** An object the player rides (platform, moving block, cog tooth,
elevator) collides via the ROM `SolidObject` / `SolidObjectFull` helper. Those
helpers test the player's standing bit on the object FIRST (`btst d6,status(a0)`,
`docs/s2disasm/s2.asm:35021-35022`). When the bit is set and the player is now
airborne (jumped off, got hurt, sprang away), the helper takes the standing-branch
air-unseat (`loc_1975A`, s2.asm:35035-35040): it clears `Status_OnObj`/`d6`, sets
`Status_InAir`, and returns `d4=0` WITHOUT falling into `SolidObject_cont`. So on
the release frame the platform carry (`MvSonicOnPtfm`, s2.asm:35635-35659) and the
side push (`SolidObject_AtEdge`, s2.asm:35432-35444) are BOTH skipped — the rider
keeps last frame's x_pos and moves only by his own velocity next frame.

**Engine symptom.** The engine clears its ride/standing state before the inline
solid pass, so the just-released airborne rider is reclassified as a FRESH contact
against the (often just-moved) object and is side-pushed or re-carried on the
release frame, landing 1+ px off and one frame ahead of ROM. Canonical trace
signature: a rider on a moving solid jumps off, and `*_x` is displaced by exactly
the object's per-frame motion delta one frame before ROM applies it (MTZ3 f2047
`tails_x` engine 0x07CA vs ROM 0x07BD; ROM applies +0xD only at f2048).

**What to check.** If the object uses the plain `SolidObject`/`SolidObjectFull`
helper (NOT a bespoke object-local capture path like CNZ cylinders), override
`SolidObjectProvider.airborneStaleStandingBitReturnsNoContact(player)` to return
`true`. The engine then mirrors the standing-branch air-unseat: clears support,
sets air, returns no contact — no carry, no side push. Keep it opt-in; objects
that manage the rider through `obj_control`/held-capture must NOT enable it.

**ROM citation.** `SolidObject` standing branch + air-unseat
`docs/s2disasm/s2.asm:35021-35044`; carry `MvSonicOnPtfm` s2.asm:35635-35659;
side push `SolidObject_AtEdge` s2.asm:35432-35444. Obj70 cog routes through the
shared helper via `JmpTo16_SolidObject` (s2.asm:55132). S3K analogue:
`SolidObjectFull_1P` `loc_1DC98` (docs/skdisasm/sonic3k.asm:41017-41035).

**Originating commit.** `<pending>` MTZ3 giant-cog ride-release:
`CogObjectInstance.airborneStaleStandingBitReturnsNoContact()` = true; MTZ3
frontier f2047 -> f2638, no green/frontier regression.

---

## P44 — Dynamic child slots may preserve parent `x_pos/y_pos` while mappings carry the visible offset

**Pattern.** Some ROM helper routines allocate child SST slots with the
parent's native `x_pos/y_pos`, then distinguish the children by routine,
mappings pointer, child pointer, or render data. The child slot's native
position is not always the visible piece's top-left or centre. `Obj1F`
collapsing-platform fragments are the canonical case: the parent copies its
`x_pos/y_pos` into every fragment, then advances the mappings pointer for each
piece before `Obj1F_FragmentFall` moves/deletes by the render flag path.

**Engine symptom.** Baking the piece offset into the child object's native
position changes slot pressure and culling timing. Traces usually report a
later unrelated Tails CPU/status mismatch because the wrong child slot survives
or frees on a different frame. In OOZ2, correcting the Obj1F fragment position,
delay, and render-bounds delete path advanced the frontier from f222
`tails_cpu_interact` to f919 `tails_status_byte`.

**What to check.** During object ports, separate native slot state from visible
piece offset. If the ROM child creation loop copies `x_pos(a0)`/`y_pos(a0)` and
changes mappings/subtype/routine fields, keep the engine child's centre at the
copied native position and apply per-piece offsets only during rendering or
collision. Also check whether a state bit is read before a shared helper writes
current-frame standing bits; if so, latch previous-frame contact rather than
consuming the engine's current contact result.

**ROM citation.** `Obj1F_Main` standing-bit read before `PlatformObject`
`docs/s2disasm/s2.asm:23815-23827`; `Obj1F_CreateFragments` and
`Obj1F_FragmentFall` child slot/mappings/delete flow
`docs/s2disasm/s2.asm:23860-23864,23880-23906`. Aquis wing slots are a related
slot-pressure case through `JmpTo12_AllocateObject` in `Obj50_Movement`.

**Originating commit.** `<pending>` S2 Obj1F/Aquis slot-pressure sweep:
`CollapsingPlatformObjectInstance` previous-frame contact latch and fragment
native-position/render-offset split; `AquisBadnikInstance` wing child slot and
bullet offset/range-unload parity.

---

## P45 — Rideable object balance bounds must expose ROM `width_pixels`

**Pattern.** S2/S3K player balance-on-object routines read `width_pixels(a1)`
from the stood-on object's SST, not the object's visible mapping width and not a
shared engine default. Objects whose init routine writes a non-default
`width_pixels` must expose that value through `getBalanceWidthPixels()` or
edge-balance will trigger on the wrong frame.

**Engine symptom.** Tails can flip facing or set/clear the object-standing status
bit one frame too early/late while standing near a rideable object's edge. In
EHZ1, Obj18's subtype width was missing from balance bounds, so Tails was
treated as beyond the left edge on frame 395 even though the ROM still had
`status=$08`. A first-pass Tails facing fix exposed the same class in HTZ1:
Obj16 writes `width_pixels=$20`, so the default 16-pixel balance width falsely
placed Tails on the left edge around frame 192. The same pattern recurred for
Obj14 seesaws: `Obj14_Init` writes `width_pixels=$30`, and falling back to the
16-pixel default made HTZ1 frame 1810 falsely enter edge balance while both
players were centered on the seesaw.

**What to check.** When porting rideable platforms, lifts, and blocks, identify
the object init value written to `width_pixels`. If the value varies by subtype
or differs from the shared default, override `getBalanceWidthPixels()` using the
ROM value. Keep this separate from collision half-widths when the object uses
different data for `SolidObject` bounds versus player balance checks.

**ROM citation.** S2 Sonic/Tails balance reads `width_pixels(a1)` in
`docs/s2disasm/s2.asm:36287-36296` and `docs/s2disasm/s2.asm:39361-39368`;
Tails' single-facing balance edge branch is `docs/s2disasm/s2.asm:39733-39743`.
Obj16 HTZ lift initializes `width_pixels=$20` at
`docs/s2disasm/s2.asm:47763-47771`; Obj14 HTZ seesaw initializes
`width_pixels=$30` at `docs/s2disasm/s2.asm:47402-47409`. S3K Tails uses the
same single-facing balance convention at `docs/skdisasm/sonic3k.asm:27842-27859`.

**Originating commit.** `<pending>` S2 Tails object-edge balance width sweep:
`ARZPlatformObjectInstance.getBalanceWidthPixels()` returns subtype width,
`HTZLiftObjectInstance.getBalanceWidthPixels()` returns `$20`, and
`PhysicsProfile.singleFacingBalance()` gates Tails' single-facing balance path.
Follow-up: `<pending>` `SeesawObjectInstance.getBalanceWidthPixels()` returns
Obj14's `$30` width byte.

---

## P46 — Child spawns must preserve `AllocateObject` vs `AllocateObjectAfterCurrent`

**Pattern.** S2 has two distinct object allocation helpers. `AllocateObject`
finds the lowest free SST slot, while `AllocateObjectAfterCurrent` scans after
the current object's slot. The choice is ROM-visible because Tails' interact
slot, object phase offsets, and later object streaming all consume SST slot
numbers directly.

**Engine symptom.** A generated child appears correct visually but occupies an
earlier slot than ROM. A later trace reports an unrelated sidekick CPU/interact
divergence because the child steals a slot that the ROM reused for a streamed
layout object. In HTZ1, Obj16 used `AllocateObjectAfterCurrent` to create Obj1C
scenery, but the engine used lowest-free-slot semantics; the Obj1C child stole
slot 16, delaying Obj92/Obj18 layout occupancy and keeping `s2_htz1` at frame
419/453.

**What to check.** For every child-spawning object, read the exact allocator
called by the ROM routine. Use `spawnFreeChild` only for `AllocateObject` /
lowest-free-slot calls. Use `spawnChild` for `AllocateObjectAfterCurrent` calls
so the child is allocated after the parent slot and may execute later in the
same slot pass when the ROM would reach it.

**ROM citation.** S2 allocator definitions:
`docs/s2disasm/s2.asm:33674-33711`. HTZ Obj16 scenery child:
`docs/s2disasm/s2.asm:47827-47833`.

**Originating commit.** `<pending>` S2 HTZ object slot parity:
`Sonic2LayerSwitcherObjectInstance` invisible Obj03 slot occupant,
S2 initial preload vertical-bypass parity, and
`HTZLiftObjectInstance.spawnScenery()` using `spawnChild()`.

**Cross-game back-ref.** Same "each multi-piece part is a real OST slot, never an
internal `linkX[]`/`linkY[]` array; count = `dbf`+1" rule — S1 swinging-platform
chain links `9f47f557f` (`docs/s1disasm/_incObj/15 Swinging Platforms.asm:67-105`,
render-only children spawned as real slots). See `s1-implement-object/rom-pitfalls.md`
P8.

**Follow-up example.** S2 ARZ Grounder Obj8D uses `AllocateObject` for its
four Obj8F wall children, and Obj8E uses `AllocateObject` for five Obj90 rocks.
Using after-current child allocation let same-frame Obj0A mouth bubbles take
the wrong SST slot before the wall pieces in ARZ2. Keep Grounder children on
`spawnFreeChild`.

**Additional ROM citation.** `docs/s2disasm/s2.asm:73497-73516`
(`loc_36C2C` Obj90 rocks) and `docs/s2disasm/s2.asm:73520-73533`
(`loc_36C64` Obj8F walls).

---

## P47 — `make_art_tile` priority bit must reach render commands

**Pattern.** S2 object `subObjData` art words encode palette and priority via
`make_art_tile(...)`. A priority argument of `1` is a ROM-visible sprite
priority bit, not just metadata for art loading.

**Engine symptom.** The object has the right mappings and frame but renders at
the wrong priority relative to foreground level tiles or other sprites. This is
easy to miss in headless object logic tests because position, collision, and
routine state still match.

**What to check.** When porting badniks, projectiles, monitors, and other
object children, read the `subObjData` art word for every rendered variant. If
the ROM uses `make_art_tile(..., palette, 1)`, route rendering through a
priority-forcing renderer path rather than the default `drawFrameIndex` call.
Check child/projectile `SubObjData2` rows separately; they may have different
priority from the parent.

**ROM citation.** Asteron parent `ObjA4_SubObjData` uses
`make_art_tile(ArtTile_ArtNem_MtzSupernova,0,1)` at
`docs/s2disasm/s2.asm:76325-76326`; Asteron spike/projectile
`ObjA4_SubObjData2` uses the same priority bit at
`docs/s2disasm/s2.asm:74656-74657`.

**Originating commit.** `fix(s2/ui): restore ROM prompt and Asteron priority`
restores high-priority render commands for `AsteronBadnikInstance` and
`BadnikProjectileInstance.ProjectileType.ASTERON_SPIKE`.

---

## P48 — Player `obj_control` is not global `Control_Locked`

**Symptom.** After an object captures Sonic or Tails, the player's logical input
stays stale for the whole capture window. Sidekick follow/control traces then
show delayed input continuing for many frames after ROM has refreshed
`Ctrl_1_Logical` / `Ctrl_2_Logical` to zero or to the current pad state.

**Root cause.** ROM object code often writes `obj_control(a1)` on the player,
for example `move.b #$81,obj_control(a1)`. That byte is player-local object
control: bit 0 makes `Obj01_Control` skip normal movement, and bit 7 blocks
touch/physics paths. It is not the global `Control_Locked` byte. ROM
`Obj01_Control` checks global `Control_Locked` first, refreshes
`Ctrl_1_Logical` from `Ctrl_1` when global control is unlocked, and only then
tests `obj_control` bit 0 to skip movement. Mapping `obj_control` bit 0 to
`player.setControlLocked(true)` wrongly freezes logical input.

**What to check.** When porting capture / tube / launcher / conveyor object
code:
1. Treat writes to `obj_control(a1)` as `ObjectControlState` updates only.
   Do not call `setControlLocked(true)` unless the ROM writes the global
   `Control_Locked` variable.
2. On release, clear the player object-control state if ROM clears
   `obj_control(a1)`, but do not clear global control unless ROM writes
   `Control_Locked`.
3. Re-read the player control routine order before adding a latch. In S2,
   logical input refresh and object-control movement suppression are separate
   branches.

**ROM citation.** `docs/s2disasm/s2.asm:36227-36235` (`Obj01_Control`:
global `Control_Locked` gates logical input refresh, then `obj_control` bit 0
suppresses movement separately); `docs/s2disasm/s2.asm:59005-59021` (ObjD6 /
Point Pokey writes `#$81` to player `obj_control`); `docs/s2disasm/s2.asm:58746-58756`
(ObjD6 release path clears object-control state without touching global
`Control_Locked`).

**Originating commits.** `<pending>` S2 CNZ1 Point Pokey capture no longer
maps player `obj_control` to global control lock; CNZ1 frontier advances from
frame 1637 to frame 3675 after the preceding Tails live-push fix. `8e946a794`
applies the same rule to Obj86 flippers: per-player `obj_control=1` suppresses
movement without freezing global logical input, greening the CNZ1 trace.

---

## P49 — `SolidObject_cont` right edge is inclusive when the ROM reject is `bhi`

**Pattern.** The standard S2 `SolidObject_cont` X bounds path computes a
relative X value and rejects the contact only when the unsigned comparison is
strictly higher than the doubled half-width. Objects that pass their half-width
through this helper therefore still collide when `relX == d1*2` on the right
edge.

**Engine symptom.** A player at the exact right edge of a solid object loses
`Status_Push`/`Status_OnObj`, while ROM keeps the side contact active. In MTZ1,
Tails reached Obj42 SteamSpring at `x_pos=$04CB` while the spring was at
`x_pos=$04B0` with `d1=$1B`; ROM kept the contact because `$04CB - $04B0 + $1B`
equals `$36`, exactly `d1*2`, but the engine's default exclusive right edge
cleared the push bit and advanced the Tails CPU state down the wrong path.

**What to check.** When porting an S2 solid object:
1. Find the exact ROM helper and branch used for the X reject. If it reaches
   `SolidObject_cont` and rejects with `bhi`, opt the object into
   `usesInclusiveRightEdge()`.
2. Keep the opt-in object-local. Do not make every solid inclusive without
   checking helpers that use different bounds or bespoke side handling.
3. Add a unit test at `object.x + halfWidth`/`relX == d1*2` so future
   refactors preserve the exact-edge push/contact behavior.

**ROM citation.** S2 Obj42 passes `d1=$1B` before calling
`SolidObject_Always_SingleCharacter` (`docs/s2disasm/s2.asm:52445-52451` in the
current checkout); `SolidObject_cont` rejects only with `cmp.w d3,d0 / bhi`
(`docs/s2disasm/s2.asm:35149-35152`).

**Originating commit.** `<pending>` S2 MTZ SteamSpring inclusive right-edge
solid contact; `TestS2MtzLevelSelectTraceReplay` advances from frame 1006
`tails_status_byte` to frame 1169 `tails_cpu_interact`.

**Cross-game back-ref.** Same `bhi`-inclusive-right-edge rule confirmed in S1
`SolidObject` (`docs/s1disasm/_incObj/sub SolidObject.asm:167-168`), commit
`caf70abb7` (FZ boss exact-edge roll-bounce, f837 -> f1724). See
`s1-implement-object/rom-pitfalls.md` P14.

---

## P50 — Object delete/cancel checks run only in the ROM routines that `bsr` them, not every frame

**Pattern.** A child/projectile "cancel if parent destroyed" (or other delete
gate) is `bsr`'d by ROM only from specific `Obj_routine` values, not from the
active/in-flight routine. Porting the check into the engine object's per-frame
update unconditionally deletes the object during a phase ROM keeps it alive,
freeing its slot at the wrong time → cascading slot drift / a downstream object
mis-slotted hundreds of frames later.

**What to check.** When porting any `*_ChkCancel` / `*_ChkDel` / delete-gate
subroutine, find every `bsr`/`jsr` to it and note which `routine(a0)` values
reach it. Gate the engine call to the equivalent engine routine/state — do not
call it from the shared per-frame entry. S2/S3K objects use the same
`routine(a0)` / `Obj_routine` jump-table dispatch as S1; verify the calling
routines per object.

**ROM citation.** S2/S3K objects dispatch through `Obj_routine` index tables
(e.g. `docs/s2disasm/s2.asm` per-object `*_Index` tables); a delete/cancel
subroutine belongs only in the routines that `bsr` it. S1 origin:
`docs/s1disasm/_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:162-194,220-249`
(`Msl_ChkCancel` `bsr`'d from `Msl_Main`/`Msl_Animate`, NOT active `Msl_FromBuzz`).

**Originating commit (S1 origin).** `53da8c24a` (S1 Buzz Bomber Missile cancels
only in flare phase, not active — missile was deleted ~840 frames early). See
`s1-implement-object/rom-pitfalls.md` P9.

---

## P51 — Dormant / consumed objects must report zero `collision_flags` so `Touch_Loop` skips them

**Pattern.** S2 `Touch_Loop` reads `collision_flags(a1)` and only checks the
object when non-zero (`docs/s2disasm/s2.asm:85048-85049`,
`move.b collision_flags(a1),d0 / bne Touch_CheckCollision`). S3K only
touch-checks objects added to `Collision_response_list`. So an object that is
(a) waiting before activation, or (b) in a broken/consumed terminal state, must
report zero collision until it activates / not re-add itself once consumed —
otherwise it can hurt the player while dormant, or (because the loop stops at
the first overlap) preempt an adjacent live object's contact.

**What to check.** For any object with a pre-activation waiting state or a
broken/consumed terminal state: trace where the ROM first writes / clears
`collision_flags` (or adds/removes itself from the response list). Gate the
engine's `getCollisionFlags()` to return `0` outside the active window. Derive
the gate from the (monotonic) activation routine — no new rewind field.

**ROM citation.** S2 `Touch_Loop` zero-gate `docs/s2disasm/s2.asm:85048-85049`;
S3K `Collision_response_list` opt-in (`docs/skdisasm/sonic3k.asm`, response-list
build during ExecuteObjects). S1 origin: `Sonic ReactToItem.asm:52-53`
(`tst.b obColType / bne`).

**Originating commits (S1 origin).** `466f408a8` (S1 broken monitor clears col
type so it stops preempting ReactToItem — *consumed* case) and the SYZ Roller
dormant case (`s1-implement-object/rom-pitfalls.md` P7 + P11).

---

## P52 — Off-screen delete uses the ROM render-box / `MarkObjGone` bound, not a raw engine pixel margin

**Pattern.** Short-lived objects (debris, shrapnel, projectiles) delete on the
ROM render-box / camera-X-coarse bound, not a fixed engine `isOnScreenX(<N>)`
margin. S2 `MarkObjGone` uses the chunk-rounded camera-X-coarse window
`$80 + screen_width + $80` (`docs/s2disasm/s2.asm`, `MarkObjGone`). A raw margin
keeps the object alive over a wider/narrower window than ROM, changing the
instance count at a given frame → slot drift.

**What to check.** For any object whose ROM delete is `tst.b render_flags / bpl
DeleteObject` or the standard out-of-range macro, model the delete with the
engine's ROM-render-box helper keyed on the object's `width_pixels`, not a raw
pixel margin. See also S2 P29 (objects with bespoke multi-endpoint delete
bounds) and P17 (child uses own X vs parent anchor).

**ROM citation.** S2 `MarkObjGone` chunk-rounded camera-X bound
(`docs/s2disasm/s2.asm`, `MarkObjGone`). S1 origin:
`docs/s1disasm/_incObj/5F Badnik - Walking Bomb.asm:218-219` +
`docs/s1disasm/_inc/BuildSprites.asm:47-58`.

**Follow-up example.** S2 Grounder Obj8F wall debris and Obj90 rock projectiles
delete from the previous `render_flags.on_screen` bit, but their sub-object data
does not set `render_flags.explicit_height`. That means the render bit comes
from `BuildSprites_ApproxYCheck`'s fixed +/-32px Y band, not an object's default
16px half-height. Using the narrower engine default cleared the ARZ2 Obj8F wall
slot `0x28` before ROM and caused f1760 / 916 (`obj_s28_type` missing); matching
the approximate BuildSprites band advances the trace to f1820 / 912
(`docs/s2disasm/s2.asm:30569-30588,73489-73494`).

**Originating commit (S1 origin).** `0a15683b9` (S1 Walking Bomb shrapnel deletes
via ROM obRender render bound, not raw isOnScreenX — lingered 24 vs ROM 16
frames). See `s1-implement-object/rom-pitfalls.md` P10.

---

## P53 — Object pushes the player: `add.w speed,obX(a1)` / `move.w pos,obX(a1)` preserve the rider's sub-pixel — never `setCentreX`/`setCentreY` (they ZERO it)

**Symptom.** A frontier reads as a "sub-pixel RAM-gated" small constant X/Y residual: the engine is byte-exact with ROM until an object first pushes/carries the player (conveyor, fan, moving solid, `MvSonicOnPtfm`/`SolidObject` side-push), then a CONSTANT fraction behind — crossing an integer boundary 1 frame off.

**Root cause.** S2 object→player position writes operate on the PIXEL word only — `add.w <speed>,obX(a1)` / `sub.w d0,obX(a1)` / `move.w <pos>,obX(a1)` write `x_pos`/`y_pos` at offset `$8`/`$C` and leave `x_sub`/`y_sub` (`$A`/`$E`) UNTOUCHED, so the rider keeps his accumulated fraction. The engine's `setCentreX(short)` / `setCentreY(short)` ZERO the sub-pixel. Any object-push path using them discards ~1px of fraction per frame.

**What to check / fix.** For any S2 object that pushes/carries the player or sets him to a pixel position the ROM writes via `add.w`/`sub.w`/`move.w` to the pixel word: use `player.shiftX/shiftY` (incremental push) or `setCentreXPreserveSubpixel`/`setCentreYPreserveSubpixel` / `setX`/`setY` (set-to-position). Keep `setCentreX`/`setCentreY` ONLY where ROM explicitly clears the fraction (`move.w #0,x_sub(a1)` or a full-replace). FAITHFUL-OR-BOUNCE per call site against the object's actual ROM routine.

**ROM citation.** S2 conveyors/fans/moving solids and `SolidObject`/`MvSonicOnPtfm` side-push/carry use the same `add.w`/`sub.w`/`move.w obX(a1)` pixel-word convention as S1 (preserves x_sub). Check each object's routine in `docs/s2disasm/s2.asm`; the `SolidObject_cont` side-push and the platform `MvSonicOnPtfm` carry are the shared cases. Only a `move.w #0,x_sub` (explicit clear) justifies a zeroing setter.

**Also covers object SELF-motion.** Same rule when an object moves ITSELF (`add.w speed,obX(a0)` preserves its own x_sub): a rideable object that self-moves via `setCentreX`/`setCentreY` drifts ~1px and surfaces it where the player rides/hits it. Use `SubpixelMotion.moveSprite`/`shiftX`/`shiftY`. (Objects that move in integer pixel steps with no sub-pixel accumulator need no change.) See S1 P15 for the `-Dobjsubpxaudit` method.

**Originating commit (S1 origin).** `b5bc778d4` (S1 Conveyor preserves rider sub-pixel via `shiftX`; SBZ2 f2224 -> f2323). See `s1-implement-object/rom-pitfalls.md` P15.

---

## P54 — Object-written logical input may be consumed one player step later

**Symptom.** A scripted object launches, jumps, or steers the player one frame
early or stops steering one frame early even though the object's routine,
timer, and stored state match ROM. Trace context shows the player velocity or
`air`/`rolling` state diverging on the frame the object writes or clears
`Ctrl_1_Logical`, while the object's own `routine_secondary` and countdown
bytes match.

**Root cause.** ROM object routines can write `Ctrl_1_Logical` /
`Ctrl_2_Logical` during the object pass. Depending on the frame edge, the
player may consume the previous logical byte for that player step and observe
the new object-written byte on the following step. The engine often runs object
script code before player physics and exposes `forcedInputMask` immediately, so
the same write affects the current physics step and shifts scripted jumps by one
frame. The inverse edge appears when a ROM routine clears the logical input at
the end of a countdown: the stored timer is already negative in the trace, but
the player still received the final frame of logical input.

**What to check.** For any object that writes `Ctrl_1_Logical` or
`Ctrl_2_Logical`:
1. Compare the player step that consumes the logical input, not only the object
   timer field. A matching `objoff_2E` with a one-frame player velocity mismatch
   usually means the forced input is being exposed on the wrong side of the
   engine's object/player ordering.
2. On a prepare/transition frame, do not apply the newly written mask early if
   ROM's player step has already run. Lock control or update object state
   separately from the forced input mask when needed.
3. On a countdown-clear edge, choose the current frame's forced mask from the
   timer value at routine entry, then decrement the stored timer so the aux
   state still matches ROM after the object update.
4. Keep the compensation local to the object routine that writes the logical
   controller bytes. Do not add zone, route, frame-number, or trace-name gates.

**ROM citation.** S2 WFZ Tornado ObjB2 prepare/jump script
(`docs/s2disasm/s2.asm:79007-79023`): `ObjB2_Prepare_to_jump` writes
`Ctrl_1_Logical` and seeds `objoff_2E=$38`; `ObjB2_Jump_to_plane` clears then
rewrites `Ctrl_1_Logical` around the countdown edge.

**Originating commit.** `d114fae44` S2 WFZ Tornado scripted-input ordering:
`TestS2WfzLevelSelectTraceReplay` advances f13978 -> f14038.

---

## P55 — Ordered Sonic/Sidekick SolidObject calls can carry later riders from the pre-mutation baseline

**Symptom.** Sidekick is exactly 1px behind ROM while already standing on a
solid object that Sonic pushes or compresses first in the same object routine.
The object position and standing slot agree, but the later sidekick participant
misses the first push delta.

**Root cause.** Some S2 objects call `SolidObject_Always_SingleCharacter` for
Sonic, mutate the object in response to that result, restore the routine-entry
registers, then call the same solid routine for Sidekick. The later call can
therefore compare the current object position against the pre-mutation `d4`
baseline and carry an already-standing sidekick by Sonic's delta. A naive engine
loop that only carries the player who caused the mutation drops that ordered
carry.

**What to check / fix.** When a ROM routine invokes the per-character
`SolidObject` path more than once with saved/restored `d1-d4`, inspect whether
the first player's contact mutates `x_pos`/`y_pos` before the later player's
solid call. If it does, process players in ROM order and carry later existing
riders from the previous solid-state baseline. Do not key the behavior on a
zone, route, or trace frame; key it on the object's actual per-player routine
ordering and standing state.

**ROM citation.** Obj45 OOZ horizontal spring:
`docs/s2disasm/s2.asm:50393-50420` (`Obj45_Horizontal` Sonic pass, restore,
Sidekick pass), `s2.asm:50465-50510` (horizontal compression moves object and
player), and `s2.asm:35193-35234` (`SolidObject45` continued-rider carry from
`d4`).

**Originating commit.** `fix: advance S2 OOZ2 Obj45 ordered carry`
(`TestS2Ooz2LevelSelectTraceReplay` advances f1601 -> f1603).

---

## P56 — Table bytes are unsigned when ROM loads them with `moveq #0` then `move.b`

**Pattern.** Some S2 object property tables use byte entries written as negative
assembly literals (for example `-$18` / `-$58`), but the routine loads them with
`moveq #0,dN` followed by `move.b (aX)+,dN`. That sequence zero-extends the
byte; it does not sign-extend it. The value `-$18` is therefore `$E8` (232), not
`-24`, unless the code later executes `ext.w`.

**Engine symptom.** A moving object reverses at the wrong endpoint because the
engine used `abs(-0x18)` / `Math.abs(tableByte)` or otherwise treated the table
literal as signed. In OOZ2, Obj43 subtype `$06` used a 24px travel span instead
of the ROM's `$E8` span; the upper sliding spike ran past its intended long
cycle and hurt Sonic at f1751 while the ROM was still in normal airborne motion.

**What to check.** For every byte-sized property table entry:
1. Read the load instruction, not just the literal in the table.
2. `moveq #0,dN` + `move.b ... ,dN` means unsigned 0..255.
3. `move.b ... ,dN` followed by `ext.w dN` means signed -128..127.
4. Keep signed position offsets (`move.w` table entries like parent/child
   offsets) separate from unsigned span/count bytes in engine records.

**ROM citation.** Obj43 loads `originXOffset` with `moveq #0,d1` /
`move.b (a2)+,d1`, then computes `objoff_32/objoff_34` from that unsigned span
(`docs/s2disasm/s2.asm:49972-50003`). The table rows use `-$18` and `-$58`
(`docs/s2disasm/s2.asm:49958-49961`), which become `$E8` and `$A8`.

**Originating commit.** `5b5c3ec30` S2 OOZ2 Obj43 unsigned travel span:
`SlidingSpikeObjectInstance` separates unsigned `originSpan` from signed
parent/child X offsets; OOZ2 advances from f1751 to f1873.

---

## P57 — Badnik points Obj29 is allocated by S2 Obj28 animal init, not by Obj27 explosion

**Pattern.** The S2 badnik death chain is not a single parent-owned spawn burst.
`Obj27_InitWithAnimal` allocates Obj28, copies `x_pos`, `y_pos`, and
`objoff_3E`, then returns through the explosion routine. Obj28's own first
routine pass later allocates Obj29 and maps the copied `objoff_3E` scratch to
the points frame.

**Engine symptom.** The points popup appears visually correct but takes the
wrong SST slot or appears one `ExecuteObjects` pass too early. This is easiest
to miss when Obj28 receives a lower slot than Obj27: ROM has already passed that
slot for the current object loop, so Obj28 cannot allocate Obj29 until the next
frame, while a parent-owned engine spawn creates Obj29 immediately.

**What to check / fix.** Trace which object routine actually owns each
`AllocateObject` call. If parent object A allocates child B, and B's routine-0
code allocates child C, spawn C from B's first update rather than from A's
factory or destruction helper. Preserve the ROM scratch fields copied between
objects so child B can perform its own allocation without hydrating trace data.

**ROM citation.** `Obj27_InitWithAnimal` allocates Obj28 and copies
`objoff_3E` (`docs/s2disasm/s2.asm:46707-46715`). `Obj28_InitRandom` allocates
Obj29 and derives `mapping_frame` from the copied scratch value
(`docs/s2disasm/s2.asm:24596-24636`).

**Originating commit.** `fix: advance S2 ARZ2 bubble and animal slot cadence`
(`TestS2Arz2LevelSelectTraceReplay` advances f669 -> f687).

---

## P58 - Some object-local SolidObject wrappers bypass the generic off-screen gate and may see post-player X

**Pattern.** Do not assume an object that uses the shared `SolidObject` geometry
enters through the generic `SolidObject_OnScreenTest` wrapper, and do not assume
the engine's usual pre-player object contact pass is equivalent for every
wrapper. Some S2 object routines call a per-object wrapper after the player
slots have already moved for the frame, and that wrapper may branch directly to
`SolidObject_cont`.

**Engine symptom.** A sidekick or player reaches a solid object's side one
frame late by exactly one pixel. The trace shows the ROM already set
`Status_Push` and cleared `x_vel`/`inertia`, while the engine still has live
ground speed and is one pixel beyond the object's side. In the originating MTZ1
case, Tails at f4835 should have stopped on Obj26 at `x=$16D6`, but the engine
remained at `$16D7` until the next frame.

**Root cause.** S2 Obj26 monitor `Obj26_Main` calls `SolidObject_Monitor` for
Sonic and then Sidekick from the object pass (`docs/s2disasm/s2.asm:25579-25605`).
`SolidObject_Monitor_Sonic` and `SolidObject_Monitor_Tails` branch directly to
`SolidObject_cont` (`docs/s2disasm/s2.asm:25617-25636`), so a generic off-screen
solid gate in the engine can skip a ROM-live side contact. Because the ROM
object routine sees the already-updated player X, an engine pass that evaluates
object contacts before flat-ground player movement may also miss the first
side-entry frame.

**What to check / fix.** For any object that appears to be "just a normal
SolidObject" but shows a one-pixel side-stop delay:
1. Inspect the object-specific wrapper and confirm whether it branches to
   `SolidObject_cont` directly or through `SolidObject_OnScreenTest`.
2. If the wrapper bypasses the on-screen test, expose that as object-local
   solid profile state (for Obj26, `bypassesOffscreenSolidGate()`), not as a
   zone or trace exception.
3. If ROM execution order means the object sees post-player flat-ground X, use
   a narrow object-local projection hook for fresh grounded side entry only.
   Do not project airborne, rolling, sloped, continued-riding, or route-specific
   cases unless the object routine proves the same ROM-state rule.
4. Preserve the player's subpixel when the projected side contact stops them;
   ROM `SolidObject_cont` writes the pixel word and leaves `x_sub` intact.

**ROM citation.** Obj26 main/wrapper:
`docs/s2disasm/s2.asm:25579-25605` and
`docs/s2disasm/s2.asm:25617-25636`. Shared side stop/push:
`docs/s2disasm/s2.asm:35424-35456`.

**Originating commit.** `fix: advance S2 MTZ1 monitor side-entry solid timing`
(`TestS2MtzLevelSelectTraceReplay` advances f4835 -> f5602).

---

## P59 — Fixed Obj0A air-countdown sidecars own S2 mouth-bubble cadence

**Pattern.** S2 player water-entry code installs Obj0A into fixed level-only
object RAM (`Sonic_BreathingBubbles` / `Tails_BreathingBubbles`). Those fixed
sidecars own `obj0a_timer`, `obj0a_flags`, `obj0a_next_bubble_timer`, and the
visible dynamic Obj0A allocations. The visible bubbles are still ordinary
dynamic SST children, but their allocation happens from the fixed object pass
after dynamic SST slots, not from the player physics update.

**Engine symptom.** Mouth bubbles appear visually correct but steal a lower SST
slot before a dynamic object that ROM executes earlier in `RunObjects`. In ARZ2
f687, a generic player-side mouth-bubble update allocated Obj0A into slot 61
before Grounder's Obj8D routine allocated its Obj8F wall children, while ROM
puts Obj8F into slots 61-63 and the mouth bubble into slot 64.

**What to check / fix.** Do not run S2 mouth-bubble RNG/allocation cadence from
generic per-player water code when the player has a fixed Obj0A sidecar. Model
the sidecar in the level-event/fixed-object owner, snapshot its timers for
rewind, and let it allocate visible Obj0A children via the lowest-free dynamic
object path. Preserve S2's `(RandomNumber & $F) + 8` next-bubble delay. Because
the fixed sidecar executes after dynamic SST slots, a child allocated into a
lower dynamic slot should run its first Obj0A dynamic pass on the next
`RunObjects` scan without an extra engine-only first-update skip.

**ROM citation.** Water-entry installs:
`docs/s2disasm/s2.asm:36385-36387` and `39552-39554`; fixed object RAM slots:
`docs/s2disasm/s2.constants.asm:1149-1157`; main loop / `RunObjects` order:
`docs/s2disasm/s2.asm:5094-5095`; Obj0A countdown and visible bubble
allocation: `docs/s2disasm/s2.asm:42088-42214`.

**Originating commit.** `fix: advance S2 ARZ2 Grounder bubble cadence`
(`TestS2Arz2LevelSelectTraceReplay` advances f687 -> f694).

---

## P60 — Launch/effect gates may depend on the current object pushing bits, not stale pending state

**Pattern.** Some S2 solid interactives split their response into two phases:
the first `SolidObject` pass compresses or arms the object, and a later release
tail checks the object's live `status(a0)` pushing bits before launching or
applying the effect. A naive engine port stores a persistent per-player
`pendingLaunch` flag and consumes it as soon as the object releases, even after
the ROM `SolidObject_TestClearPush` path has cleared the object/player pushing
bits or no current side-push occurred.

**Engine symptom.** The object position and visual release can match ROM, but
the player launches one frame early or at the wrong release strength. In OOZ2,
Obj45 released from the correct compressed X on f1873, but the engine consumed
a stale Sonic pending launch while ROM had just cleared the pushing bit and
kept Sonic at `x=$04A6` for another frame.

**What to check / fix.** When a ROM routine calls a launch/effect tail that
starts by testing `status(a0) & pushing_mask` or `bclr #pN_pushing_bit,status(a0)`:
1. Preserve any visual release motion that occurs before the launch tail.
2. Do not consume a stale engine pending flag after `SolidObject_TestClearPush`
   or a no-contact result. Require the current per-player object pushing bit
   to be set, mirroring the ROM `bclr` gate.
3. Keep the state object-local and per-player. Do not patch by zone, route,
   frame, or trace name.

**ROM citation.** Obj45 OOZ horizontal spring release/launch:
`docs/s2disasm/s2.asm:50435-50538`; current pushing bits are cleared by
`SolidObject_TestClearPush` / `Solid_NotPushing`
(`docs/s2disasm/s2.asm:35443-35462`) and set by side collision
(`docs/s2disasm/s2.asm:35276-35299`).

**Originating commit.** `5bf720281` S2 OOZ2 Obj45 current-push launch gate:
`TestS2Ooz2LevelSelectTraceReplay` advances f1873 -> f2176.

---

## P61 - AnimateSprite `$FC` advances routine before child allocation, and higher-slot children execute same frame

**Pattern.** Some animation scripts use `$FC` as a routine-control opcode, not
as an immediate callback. S2 `AnimateSprite` handles `$FC` by adding 2 to
`routine(a0)`, clearing `anim_frame_duration(a0)`, advancing `anim_frame(a0)`,
and returning. The target routine runs on the object's next dispatch. If that
routine allocates a child into a higher SST slot, the child can still execute
later in the same `RunObjects` pass.

**Engine symptom.** A projectile or effect appears in the correct slot family
but one or more frames too early, or appears at its spawn coordinates instead
of after its init routine's first movement. In ARZ2, Obj22's arrow occupied slot
`0x41` at f694 while the ROM did not allocate it until f696; when the ROM did
allocate it, `Obj22_Arrow_Init` fell through into `Obj22_Arrow/ObjectMove`, so
the first visible X was already `$0724` rather than the shooter X `$0720`.

**What to check / fix.** When an animation script contains `$FC`:
1. Model `$FC` as a pending routine dispatch, not as same-call object logic.
2. Let the destination routine call `AnimateSprite` again if the ROM routine
   does so after returning to the main routine.
3. Do not add a blanket first-update skip for children allocated by that
   destination routine. If `AllocateObject` puts the child in a higher slot,
   it should execute later in the same object pass; if it lands in a lower or
   already-processed slot, the manager's slot-order rules should defer it.

**ROM citation.** Generic `$FC` handling:
`docs/s2disasm/s2.asm:30481-30487`. Obj22 shoot routine and arrow init/move:
`docs/s2disasm/s2.asm:51570-51607`. Obj22 firing script:
`docs/s2disasm/s2.asm:51630-51638`.

**Originating commit.** `fix: advance S2 ARZ2 arrow shooter routine timing`
(`TestS2Arz2LevelSelectTraceReplay` advances f694 -> f723).

---

## P62 - Native sidekick checks may read raw Ctrl_2, not logical CPU follow input

**Pattern.** Some S2 object routines inspect the MainCharacter through
`Ctrl_1_Logical` but inspect the Sidekick through raw `Ctrl_2` before masking
button press bits. CPU Tails' autonomous follow jump is written to the logical
sidekick input path; it is not a raw player-2 pad press.

**Engine symptom.** A CPU sidekick triggers an object action that the ROM only
allows for a physical P2 input. In CNZ2, Obj86's vertical flipper launched
Tails from a synthesized follow jump, while ROM continued the flipper slide
because raw `Ctrl_2` had no A/B/C press edge.

**What to check / fix.** When porting an object that has separate
MainCharacter and Sidekick input branches:
1. Read whether the Sidekick branch loads `Ctrl_2`, `Ctrl_2_Logical`, or an
   object-local control byte.
2. Use raw P2 helpers for `Ctrl_2` branches; do not treat CPU-generated follow
   jumps as raw P2 input.
3. Keep the distinction object-local unless the ROM routine is a shared player
   control routine. Do not patch by zone, route, frame, or trace name.

**ROM citation.** Obj86 upward flipper input selection:
`docs/s2disasm/s2.asm:58345-58350`; low-byte A/B/C press mask:
`docs/s2disasm/s2.asm:58390`.

**Originating commit.** `<pending>` S2 CNZ2 Obj86 raw-P2 launch gate:
`TestS2Cnz2LevelSelectTraceReplay` advances f4644 -> f4730.

---

## P63 - SolidObject side return can trigger object response even when the push bit is not set

**Pattern.** Some S2 solid interactives distinguish the `SolidObject` return
code from the object's live pushing bits. A side-contact return (`d4==1`) may
run a compression/effect routine even when the current contact did not set the
player's push bit. Later launch/effect tails can still require the live
`status(a0)` pushing bit.

**Engine symptom.** An airborne player reaches a horizontal pressure spring
from the side and the engine reports a side contact, but the object does not
compress because the port only reacts to `pushingNow`. In OOZ2 this left Sonic's
`g_speed` at `0x0000` on f2484 while ROM had compressed Obj45 by one pixel,
shifted Sonic's `x_pos`, cleared `x_vel`, and wrote inertia `0x0040`.

**What to check / fix.** When a routine tests the `SolidObject` return value
(`cmpi.b #1,d4`, `beq`, or equivalent) and separately tests or clears
`status(a0)` push bits later:
1. Let the return-code path run from the contact kind (`SIDE`) even if the
   engine's push bit is false.
2. Only arm or consume launch/effect state from the current push bit when the
   ROM tail uses `status(a0)` / `bclr #pN_pushing_bit,status(a0)`.
3. Keep the distinction object-local and per-player. Do not replace it with a
   zone, route, frame, or trace-name exception.

**ROM citation.** Obj45 horizontal spring:
`docs/s2disasm/s2.asm:50393-50432` (`Obj45_Horizontal` checks the
`SolidObject_Always_SingleCharacter` side return), `s2.asm:50465-50524`
(`loc_2433C` compresses, moves `x_pos(a0/a1)`, clears `x_vel`, writes inertia),
and `s2.asm:50529-50540` (`Obj45_LaunchCharacterHorizontal` gates release
launch on live pushing bits).

**Originating commit.** `d01490049` S2 OOZ2 Obj45 airborne side compression:
`TestS2Ooz2LevelSelectTraceReplay` advances f2484 -> f2623.

---

## P64 - Negative SolidObject returns are not always ride captures

**Pattern.** Some S2 object-local wrappers branch on `tst.w d4` after
`SolidObject_Always_SingleCharacter`. A negative return means the player
contacted the object vertically, but it may be either top or bottom contact.
Only the top path has the prior `RideObject_SetRide` side effects; bottom
captures preserve the player's airborne/no-on-object state.

**Engine symptom.** A sidekick enters an object from below and the engine either
fails to capture them, or captures them as if they were riding on top. In CNZ2,
ObjD6 Point Pokey missed Tails' bottom capture at f4730, leaving Tails below
the cage with the wrong `y_pos` and velocity.

**What to check / fix.** When a routine tests `d4` after a single-character
`SolidObject` call:
1. Map the ROM return sign and contact kind separately. Negative top and bottom
   may both enter the object's capture/effect path, but only top should inherit
   ride/standing state.
2. Preserve per-player object state words such as ObjD6 `objoff_30` for Sonic
   and `objoff_34` for Sidekick. A release must target the captured player, not
   whichever player the engine update happened to receive first.
3. If the occupied routine releases on an off-screen/render-flag check, run the
   ROM release tail for the captured player and preserve any post-release
   cooldown before clearing the per-player state.

**ROM citation.** ObjD6 calls Sonic then Sidekick with separate state words and
captures on `tst.w d4 / bpl` after `SolidObject_Always_SingleCharacter`
(`docs/s2disasm/s2.asm:59030-59046`). Its occupied routine releases through
`loc_2BE2E` and clears the state in `loc_2BE9C`
(`docs/s2disasm/s2.asm:59188-59256`).

**Originating commit.** `82e595b5b` S2 CNZ2 ObjD6 bottom-capture/sidekick
ownership fix: `TestS2Cnz2LevelSelectTraceReplay` advances f4730 -> f4892.

---

## P65 - Platform nudge gates may read object standing bits, not live riding state

**Pattern.** Some S2 solid platform routines update bob, sag, nudge, or falling
state from `status(a0)&standing_mask` before calling `PlatformObject` or
`SolidObject` in the same object routine. That bit is the object's prior
standing latch, not a freshly computed current-frame ride query.

**Engine symptom.** A jump-off frame leaves a platform one pixel out of phase
even though player physics and object allocation otherwise match. In ARZ2, Obj18
slot `0x1F` relaxed its nudge angle one step early at f888, producing
`y_pos=$059A` where ROM still had `$059B`.

**What to check / fix.** When a routine reads `status(a0)&standing_mask` before
its solid helper:
1. Use the object standing latch from the previous solid pass, such as
   `ObjectManager.hasObjectStandingBit`, not `isPlayerRiding()` or a
   current-frame collision query.
2. Include Sonic and Sidekick bits when the object routine's standing mask
   covers both players.
3. Let the later solid helper clear or refresh the latch after movement. Do not
   replace this order dependency with a zone, route, frame, or trace-name
   exception.

**ROM citation.** Obj18 `Obj18_TopSolid` and `Obj18_FullSolid` read
`status(a0)&standing_mask`, run `Obj18_Move`/`Obj18_Nudge`, then call
`PlatformObject`/`SolidObject` (`docs/s2disasm/s2.asm:23219-23243,
23273-23301`). `Obj18_Nudge` computes `y_pos` from `obj18_y_offset`
(`docs/s2disasm/s2.asm:23311-23320`).

**Originating commit.** `38ffa3bb5` S2 ARZ2 Obj18 platform standing-latch
nudge: `TestS2Arz2LevelSelectTraceReplay` advances f888 -> f1028.

---

## P66 - Collision-result latches may be cleared at the start of every ROM collision pass

**Pattern.** Some object-local flags are not persistent state-machine latches.
They are cleared at the start of a collision helper, then re-set only if the
current collision pass produces a specific result. The owning object routine may
read the flag on the next frame, but if the routine does not consume it
immediately, the next collision pass clears it.

**Engine symptom.** A boss or object reacts to a player contact long after the
ROM would have forgotten it. In MCZ2, Obj57 reascended early because the engine
kept `bossHurtSonic` true from a drill hurt that occurred while
`Boss_Countdown` was still nonnegative; ROM cleared `boss_hurt_sonic` on each
`BossCollision_MCZ` pass and only consumed it when the countdown crossed
negative on the immediately following frame.

**What to check / fix.** When a ROM helper starts with `sf`, `clr`, or `move.b
#0` on an object-local "event" flag before running collision/effect logic:
1. Treat that flag as a collision-pass result, not a cycle-long latch.
2. Preserve the routine's consume timing. If the object routine only reads the
   flag after a timer crosses a threshold, clear stale values on frames where
   the ROM would run another helper pass first.
3. Keep the fix object-local and keyed on the ROM timer/routine state, not on a
   route or trace frame.

**ROM citation.** Obj57 `BossCollision_MCZ` clears and conditionally re-sets
`boss_hurt_sonic` (`docs/s2disasm/s2.asm:85732-85763,85769-85788`);
`Obj57_Main_Sub6` only consumes it after `Boss_Countdown` goes negative
(`docs/s2disasm/s2.asm:65987-65996`).

**Originating commit.** `9e8c0ca84` S2 MCZ2 Obj57 boss_hurt_sonic latch
lifetime: `TestS2Mcz2LevelSelectTraceReplay` advances f9662 -> f10111.

---

## P67 - Touch response positions use ROM `x_pos` / `y_pos`, not sprite top-left

**Pattern.** S2 `TouchResponse` tests an object's `collision_flags` size box
around the object's SST `x_pos(a1)` / `y_pos(a1)`. Objects whose Java
`getX()` / `getY()` intentionally return sprite top-left render bounds must
publish an explicit touch region at their ROM center position instead of
letting the shared touch loop reuse the render coordinate.

**Engine symptom.** A harmful object hurts the player one frame early or from
several pixels away even though the object's live center position matches ROM.
In HTZ1, the Obj14 seesaw ball was visually/post-update aligned with ROM, but
touch response tested its sprite top-left (`x_pos - width_pixels`,
`y_pos - 8`) and overlapped Sonic where ROM's center-based `TouchResponse`
missed. The false hurt at f7108 cascaded into Tails/player/camera drift.

**What to check / fix.** For every S2 `TouchResponseProvider` whose
`getX()` / `getY()` are render extents or sprite bounds:
1. Compare those methods to the ROM `x_pos` / `y_pos` fields used by the touch
   routine.
2. If they differ, implement `getMultiTouchRegions()` with a single
   `TouchRegion(getCentreX(), getCentreY(), collision_flags)` or the exact
   ROM child/piece positions.
3. Keep render/bounds coordinates separate from touch coordinates. Do not fix
   a touch bug by changing `getX()` / `getY()` if rendering, culling, or
   balance/out-of-range logic depends on top-left semantics.

**ROM citation.** S2 `TouchResponse` reads `x_pos(a1)` and `y_pos(a1)` for the
overlap test (`docs/s2disasm/s2.asm:85075,85092`), after loading the size from
`collision_flags(a1)` (`docs/s2disasm/s2.asm:85048-85063`). Obj14 writes the
ball's `collision_flags=$8B` during init (`docs/s2disasm/s2.asm:47596`).

**Originating commit.** `63753cbc` S2 HTZ1 Obj14 seesaw ball touch center:
`TestS2HtzLevelSelectTraceReplay` advances f7108 -> f7805.

---

## P68 - Break paths must clear both player on-object status and engine riding support

**Pattern.** S2 object break/collapse/release routines that force the player
airborne often clear both the player status bit and the object's standing mask.
When Java only calls `setAir(true)`, the engine can retain a stale
`onObject`/riding-provider latch even though the ROM object no longer supports
the player.

**Engine symptom.** The player becomes grounded again on the next movement
frame, clears rolling/airborne state, and loses the ROM gravity increment or
launch velocity. In HTZ1, Obj2F smashable ground set Sonic airborne/rolling in
ROM and the fragment routine only moved/displayed pieces, but the engine
recovered stale riding support on the deleted/non-solid support and zeroed
`y_speed` at f7805.

**What to check / fix.** When porting an object path that writes
`status.player.in_air`, clears `status.player.on_object`, or clears the
object's `standing_mask`:
1. Pair the airborne write with `player.setOnObject(false)`.
2. Clear the engine solid-contact rider latch with
   `ObjectManager.clearRidingObject(player)` when the live object was a
   potential support.
3. Verify the post-break fragment/display routine. Do not leave fragments solid
   unless the ROM fragment routine calls `SolidObject`.
4. Gate the behavior by the object's live ROM state. Do not add zone, route,
   frame, or trace-specific branches.

**ROM citation.** Obj2F's break path sets rolling/airborne state and clears
`status.player.on_object` (`docs/s2disasm/s2.asm:49239-49249`), then clears the
object standing mask before `BreakObjectToPieces`; Obj2F fragments only move,
apply gravity, delete offscreen, and display (`docs/s2disasm/s2.asm:49272-49294`).

**Originating commit.** `31dd2631` S2 HTZ1 Obj2F smashable-ground support
release: `TestS2HtzLevelSelectTraceReplay` advances f7805 -> green.

---

## P69 -- Routine-0 init can return before wait/move, while after-current children run initialized

**Pattern.** Some S2 objects do meaningful routine-0 initialization inside the
first object pass, then `rts` before their wait or movement routine. Children
allocated after the current slot may still be fully initialized by the parent
and execute their own wait routine later in that same object pass.

**Engine symptom.** A harmful moving object can touch the player one frame
early even though the movement formula itself is correct. In CPZ1, Obj1D
BlueBalls parent init was modeled in the constructor, so the first engine
`update()` ran the wait routine immediately and the parent entered movement one
object pass too soon. Touch response then saw the post-move y position at f4547;
ROM still tested the pre-move position on that frame and did not hurt until
f4548.

**What to check / fix.**
1. Read the object's routine-0 init tail before assuming constructor setup is
   equivalent to a ROM frame. If init ends in `rts`, preserve that object pass.
2. Check child allocation order separately. If the parent fills child SST fields
   before `AllocateObjectAfterCurrent`, child constructors may need to start as
   already initialized even while placed parents wait one pass.
3. Keep the fix object-local and routine-driven. Do not branch on zone, route,
   trace frame, or a known failing trace.

**ROM citation.** Obj1D dispatches through its routine table
(`docs/s2disasm/s2.asm:48317-48329`). `Obj1D_Init` initializes parent and
children, then returns (`docs/s2disasm/s2.asm:48341-48390`); the child loop
uses after-current allocation (`docs/s2disasm/s2.asm:48359-48365`).
`Obj1D_Wait` and `Obj1D_MoveArc` are separate later routines
(`docs/s2disasm/s2.asm:48393-48421`).

**Originating commit.** `0cba2f7e` S2 CPZ1 BlueBalls init cadence:
`TestS2CpzLevelSelectTraceReplay` advances f4547 -> green.

---

## P70 -- After-current linked children may need parent-owned lifetime

**Pattern.** Some S2 parent objects allocate fully initialized linked children
with `AllocateObjectAfterCurrent`, fill the child SST fields directly, and then
own the whole pair's unload/delete policy from the parent routine. The child may
have no generic `MarkObjGone` tail even though it moves and displays normally.

**Engine symptom.** A linked platform child appears one slot or one phase away
from ROM, reruns parent init on its first update, or disappears before it reaches
the camera because generic dynamic-object lifetime deletes it. In CPZ2, Obj7A
SidewaysPform's child was gone or phase-shifted by the f5689 Sonic landing
frame, so Sonic missed the ROM child ride and kept falling with `g_speed=$003C`
instead of landing with `g_speed=$01F9`.

**What to check / fix.**
1. For parent init loops that call `AllocateObjectAfterCurrent`, insert the
   child after the current parent slot, including when the parent itself is
   still being constructed by object placement.
2. If the parent writes all child fields during init, mark the child initialized
   so its first update runs the child routine rather than parent routine 0.
3. Check whether child start coordinates are already parent-computed spawn
   fields. Do not reapply the same offset in the child constructor.
4. Read parent and child lifetime tails separately. If only the parent checks
   range endpoints or clears the linked child, model that parent-owned lifetime
   instead of giving the child a generic out-of-range delete.
5. Keep collision toggles exact when the ROM uses equality tests; do not replace
   them with overlap heuristics unless the disassembly does.

**ROM citation.** Obj7A allocates children after the current object and fills
their id/routine/position/linkage fields (`docs/s2disasm/s2.asm:56192-56230`).
The parent owns pair deletion from range endpoints
(`docs/s2disasm/s2.asm:56239-56266`), while the child routine only moves,
checks collision, and displays (`docs/s2disasm/s2.asm:56269-56272`). The child
toggles both directions only on exact edge equality
(`docs/s2disasm/s2.asm:56307-56317`).

**Originating commit.** `fca42ed8d` S2 CPZ2 Obj7A after-current child and pair
lifetime: `TestS2Cpz2LevelSelectTraceReplay` advances f5689 -> f7035.

---

## P71 -- Render-flag checks must use BuildSprites bounds, not solid-contact bounds

**Pattern.** Some object routines wait for or branch on `render_flags.on_screen`
even when they are not solid objects. That flag is produced by `BuildSprites`,
not by `SolidObject`, and its vertical bounds depend on whether
`render_flags.explicit_height` is set.

**Engine symptom.** A badnik or hazard spawns in the correct slot and position,
but its state machine starts chasing, firing, deleting, or playing SFX dozens of
frames early/late. In OOZ2, Aquis Obj50 stayed in `WAIT_FOR_SCREEN` 37 frames
too long because the engine used a 16px solid-contact render box; the ROM
approximate-Y path had already set `render_flags.on_screen`, so the later
badnik bounce missed Sonic.

**What to check / fix.**
1. When a routine tests `btst #render_flags.on_screen,render_flags(a0)`, read
   the object's init code for `width_pixels`, `y_radius`, and
   `render_flags.explicit_height`.
2. If `explicit_height` is clear, model S2 `BuildSprites_ApproxYCheck`: X uses
   `width_pixels(a0)`, while Y uses the assumed 32px band.
3. If `explicit_height` is set, use the object's `y_radius(a0)` / custom render
   half-height.
4. Do not reuse `isWithinSolidContactBounds()` unless the ROM routine is
   actually consuming the solid-contact gate. The solid-contact half-height may
   be narrower than the render flag used by object AI.

**ROM citation.** Aquis Obj50 tests the flag in `Obj50_CheckIfOnScreen`
(`docs/s2disasm/s2.asm:60662-60671`). Obj50 init sets `width_pixels=$10` and
does not set `render_flags.explicit_height`
(`docs/s2disasm/s2.asm:60567-60574`). S2 `BuildSprites` uses `width_pixels` for
X and the approximate 32px Y band when `explicit_height` is clear
(`docs/s2disasm/s2.asm:30566-30611`).

**Originating commit.** `<pending>` S2 OOZ2 Aquis render-flag gate:
`TestS2Ooz2LevelSelectTraceReplay` advances f5737 -> f5762.

---

## P72 -- Orientation-to-player equality selects left/up

**Pattern.** S2 `Obj_GetOrientationToPlayer` does not treat equal X or Y as
right/down. Its branch sequence leaves movement index 0 selected on a zero
object-minus-player delta, so X equality selects the left delta and Y equality
selects the up delta.

**Engine symptom.** A chasing object appears in the correct slot and routine,
but its velocity or subpixel carry is one increment off only on frames where it
lines up exactly with the player on one axis. In ARZ2, Obj8C Whisp slot `$13`
matched the ROM until its Y equaled Sonic's Y; the engine used a strict `<`
test, accelerated down for one frame, and reached f1225 one pixel below the ROM.

**What to check / fix.**
1. When porting an object that calls `Obj_GetOrientationToPlayer`, mirror the
   helper's equality behavior instead of using strict less-than tests for both
   axes.
2. Use `playerX <= objectX` for the left delta and `playerY <= objectY` for the
   up delta when the movement table follows the standard index-0 left/up order.
3. Confirm the object has not rearranged the movement table before applying the
   rule. Keep the fix object-local and helper-driven; do not branch on zone,
   route, trace frame, or a known failing trace.

**ROM citation.** `Obj_GetOrientationToPlayer` leaves d0/d1 at index 0 on
equality via `tst.w d2; bpl.s` for X and `sub.w y_pos(a1),d3; bhs.s` for Y
(`docs/s2disasm/s2.asm:72812-72848`). Obj8C then indexes
`Obj8C_MovementDeltas` before `ObjectMove`
(`docs/s2disasm/s2.asm:73231-73249,30191-30204`).

**Originating commit.** `8d114451a` S2 ARZ2 Obj8C Whisp orientation equality:
`TestS2Arz2LevelSelectTraceReplay` advances f1225 -> f1294.

---

## P73 -- Some object movement helpers use high-word-only position temporaries

**Pattern.** Not every S2 object movement routine preserves a persistent
subpixel low word. Some routines rebuild a temporary 16.16 value from the
integer `x_pos(a0)` / `y_pos(a0)` every call, add shifted velocity, then write
only the high word back to object position.

**Engine symptom.** A projectile or child object follows the right broad arc
but drifts one or more pixels over several frames, making touch/hurt timing
early or late. In CNZ2, Obj51 split electric balls carried a Java
fixed-point low word between frames; the right-moving ball reached Tails'
touch box at f9183 even though ROM slot `$1B` was still just outside it.

**What to check / fix.**
1. Read the exact object-local movement helper before choosing a generic
   subpixel integrator.
2. If the ROM helper starts with `move.w x_pos(a0),dN; swap dN` and never
   loads `x_sub/y_sub`, rebuild the engine's working fixed-point value from
   integer x/y each call instead of preserving the prior low word.
3. If the split/spawn routine copies the object after such a helper, copy the
   integer position and velocity state, but do not invent persistent subpixel
   state for the clone.
4. Keep the rule object-local. Other helpers such as `ObjectMove` and
   playable-sprite movement do preserve real subpixel state and should keep
   using the longword position path.

**ROM citation.** Obj51 `loc_31FF8` rebuilds local longword temporaries from
`x_pos/y_pos`, adds `x_vel/y_vel << 8`, increments `y_vel` by `$38`, and writes
only the high word back (`docs/s2disasm/s2.asm:67059-67079`). Obj51 split
allocation copies the object after setting split velocities
(`docs/s2disasm/s2.asm:67082-67108`).

**Originating commit.** `ac491987d` S2 CNZ2 Obj51 high-word movement temporary:
`TestS2Cnz2LevelSelectTraceReplay` advances f9183 -> f9487.

---

## P74 -- MTZ3 object code checks `metropolis_zone_2`, not MTZ act 3

**Pattern.** Sonic 2 stores Metropolis Act 3 as a distinct ROM zone id:
`metropolis_zone_2 = $05`, with apparent act 2. Object code that reads
`Current_Zone` compares against `$05`; it does not see `metropolis_zone=$04`
plus `currentAct == 2`.

**Engine symptom.** An MTZ3 object takes the MTZ1/2 branch even though the
trace metadata says act 3. In MTZ3, Obj65 subtype 5 treated `$1BC0` as the
MTZ1/2 reverse point, moved from `$1BC0` to `$1BBE`, and carried Sonic/Tails
left. ROM was in `metropolis_zone_2`, skipped that reverse branch, moved right
to `$1BC2`, and kept the players on the advancing platform.

**What to check / fix.**
1. When porting S2 object code that reads `(Current_Zone).w`, compare against
   the ROM zone id from `romZoneId()` / `currentZone()`, not the engine's
   internal zone index plus act.
2. For MTZ3-specific object behavior, use `Sonic2ZoneConstants.ROM_ZONE_MTZ_3`
   (`0x05`) rather than `ROM_ZONE_MTZ && currentAct == 2`.
3. Keep this distinction to ROM-state predicates. Display/apparent-act behavior
   may still use `currentAct()` / `Apparent_act` when the ROM routine reads
   those values instead of `Current_Zone`.

**ROM citation.** S2 declares `metropolis_zone = $04` and
`metropolis_zone_2 = $05`, and defines `metropolis_zone_act_3` as
`(metropolis_zone_2<<8)|$00`
(`docs/s2disasm/s2.constants.asm:384-421`). Obj65 `loc_26E4A` compares
`Current_Zone` directly against `metropolis_zone_2`; `$1BC0` is a reverse point
only on the not-MTZ3 branch (`docs/s2disasm/s2.asm:53159-53177`).

**Originating commit.** `<pending>` S2 MTZ3 Obj65 MTZ3 ROM-zone conveyor branch:
`TestS2Mtz3LevelSelectTraceReplay` advances f9035 -> f9134.

---

## P75 -- Render-flag delete gates consume the previous BuildSprites result

**Pattern.** Some S2 object routines test `render_flags.on_screen` inside their
routine before the current frame's DisplaySprite/BuildSprites path refreshes
the bit. Init routines may also seed `render_flags.on_screen` and return before
the first routine that tests it.

**Engine symptom.** Dynamic SST slot order drifts because children or animals
delete one frame early or late even though their positions and broad culling
bounds look correct. In ARZ2, Obj0A bubbles spawned into lower already-passed
slots were deleted before their first ROM Obj0A_ChkWater pass, Obj28 animals
used a fresh post-move bounds check instead of the previous render flag, and
Grounder Obj8F/Obj90 children stayed alive after ROM had already deleted them
from the prior render bit. The stale Obj90 rocks occupied slots that the f1648
placement cluster needed, moving ARZ2 from a slot mismatch to the next Obj08
skid-dust frontier once fixed.

**What to check / fix.**
1. When a routine executes `btst #render_flags.on_screen,render_flags(a0)` or
   `tst.b render_flags(a0)` before its display tail, cache and consume the
   previous BuildSprites result rather than recomputing bounds immediately.
2. If routine 0 seeds `render_flags.on_screen` and then the first real routine
   tests the bit, preserve that init-set value through the object's first pass,
   including for children allocated into slots already passed by ExecuteObjects.
3. Keep MarkObjGone / RememberState tails separate from the early render-bit
   gate. If ROM deletes before `ObjectMoveAndFall` and then tails to MarkObjGone
   after movement, model both checks in that order.
4. Keep the fix object-local and routine-driven. Do not branch on zone, route,
   trace frame, or a known failing trace.

**ROM citation.** Obj0A init seeds `render_flags.on_screen|level_fg`, and
Obj0A_ChkWater later tests the render flag (`docs/s2disasm/s2.asm:41888,41951`).
Obj28 Walk/Fly flow reaches `Obj28_ChkDel` after movement
(`docs/s2disasm/s2.asm:24670-24688,24715-24727`). Obj8F/Obj90 Move tests the
previous render bit before `ObjectMoveAndFall`, then jumps to MarkObjGone after
movement; Grounder spawns the Obj8F/Obj90 children through that path
(`docs/s2disasm/s2.asm:73490-73494,73510-73516`).

**Originating commit.** `910c7e6c6` S2 ARZ2 Obj0A/Obj28/Obj8F/Obj90 prior
render-flag cleanup: `TestS2Arz2LevelSelectTraceReplay` advances f1648 ->
f1698.

---

## P76 -- Parent-created projectile pairs may initialize through the first child slot

**Pattern.** Some S2 parent routines allocate only one child object and leave a
routine-0 child initializer to reuse that first child's SST slot, fill its
movement/collision fields, and then allocate the paired child. The first child
returns from init without running movement, while the second child may already
be initialized and execute movement later in the same object pass.

**Engine symptom.** A harmful projectile pair is visually plausible but one
member reaches the player one movement tick early. In HTZ2, Obj52 lava ball
slot 20 had already advanced into Sonic's touch box at f8530, so the engine
entered hurt one frame before ROM. ROM slot 20 was still lower/outside the
touch box because its spawn-frame routine only ran the pair initializer.

**What to check / fix.**
1. When a parent routine creates a projectile or hazard pair, verify whether the
   parent allocates both children or only seeds one subtype child.
2. If the first child routine initializes both slots and returns, model that
   as a placeholder child with collision disabled until its routine-0 update.
3. Let the paired child start initialized if the ROM writes all of its fields
   before the allocator loop returns, and allow same-pass execution only when
   the allocated slot is still ahead in object order.
4. Keep the fix keyed to the object routine/slot cadence. Do not compensate by
   tuning velocity constants or branching on zone, route, or trace frame.

**ROM citation.** Obj52 parent `Obj52_CreateLavaBall` allocates one subtype-6
child (`docs/s2disasm/s2.asm:64306-64323`). The child initializer at
`loc_2FF78` reuses the current slot for ball 0, writes `collision_flags=$8B`,
velocity, radius, and animation fields, then calls `AllocateObject` for ball 1
before returning (`docs/s2disasm/s2.asm:64429-64469`). Movement is separate in
`Obj52_LavaBall_Move` (`docs/s2disasm/s2.asm:64504-64524`).

**Originating commit.** `a751be064` S2 HTZ2 Obj52 lava-ball pair init cadence:
`TestS2Htz2LevelSelectTraceReplay` advances f8530 -> f9150.

---

## P77 -- Pre-decrement countdown seeds may need Java off-by-one adjustment

**Pattern.** Some S2 object states write a timer literal, then a later routine
uses `subq.w #1,timer` / `bmi.s` to transition. The literal includes the state
entry frame and the pre-decrement sample, not just the number of later Java
updates that should remain in the state.

**Engine symptom.** Moving object position is correct in broad terms but
reaches a pixel or touch overlap one object pass late. In OOZ1, Obj4A Octus
descended one frame late, so Sonic's rolling hit at f6639 missed the enemy and
the bounce arrived after the ROM frame.

**What to check / fix.**
1. Count the state-entry frame and first decrement frame before copying a ROM
   timer literal into a Java state constant.
2. If the Java state machine stores the literal after the entry frame has
   already returned and decrements only on later updates, seed `literal - 1` or
   otherwise model the ROM pre-decrement transition so movement starts on the
   same object pass.
3. Keep the fix object-local and routine-driven. Do not compensate with a
   trace frame, route, or zone exception.

**ROM citation.** Obj4A writes `#60` to `objoff_2C`, `Obj4A_Hover`
pre-decrements and branches on negative, then `Obj4A_MoveDown` starts descent
with `addi.w #$10,y_vel` before `ObjectMove`
(`docs/s2disasm/s2.asm:60456-60480`). `TouchResponse` then reads object
`x_pos`, `y_pos`, and `collision_flags` in the player slot
(`docs/s2disasm/s2.asm:85036-85096`).

**Originating commit.** `cf2608003` S2 OOZ1 Octus hover countdown:
`TestS2OozLevelSelectTraceReplay` advances f6639 -> f7467.

---

## P78 -- Boss defeated flags are not always the same as active boss ids

**Pattern.** S2 boss/event handoffs may use a global `Boss_defeated_flag`
while leaving `Current_Boss_ID` nonzero for separate boundary or boss-active
logic. Event routines that test the defeated flag must not infer it from the
engine's active-boss id.

**Engine symptom.** A boss defeat sequence looks broadly correct, but the
camera release starts one object/frame boundary early or late. In HTZ2, Obj52
reached the flee threshold and the engine released `Camera_Max_X_pos` on the
same handoff row where the ROM trace still showed Camera_X clamped at `$2F5E`
and Obj52 at its pre-flee y position.

**What to check / fix.**
1. Confirm whether the level event reads `Boss_defeated_flag`, `Current_Boss_ID`,
   object-local `boss_defeated`, or another state byte. Model that exact byte.
2. Keep `Boss_defeated_flag` separate from active-boss bookkeeping when the
   disassembly does not clear `Current_Boss_ID` at the same handoff.
3. For defeated routines that latch the flag and then perform visible movement
   or boundary writes, compare the handoff row against the trace before moving
   all effects into one Java update. Stage only the routine-driven writes, not a
   zone/route/frame exception.
4. Clear engine-only active-boss bookkeeping when the boss object actually
   deletes if later engine systems still need a no-active-boss state.
5. If the defeated/flee routine keeps widening `Camera_Max_X_pos` or otherwise
   mutating global arena state before its own delete branch, keep the boss on a
   persistent/custom lifecycle path. Do not let the generic dynamic-object
   out-of-range culler stand in for `MarkObjGone` unless the ROM routine really
   tails into that helper.

**ROM citation.** HTZ2 routine 9 tests `Boss_defeated_flag` before changing
camera bounds (`docs/s2disasm/s2.asm:21293-21308`). Obj52 defeat seeds
`Boss_Countdown=$B3` and selects `boss_routine=8`, and
`Obj52_Mobile_Flee` latches `Boss_defeated_flag`, moves `y_pos`, and extends
`Camera_Max_X_pos` at the `$-3C` countdown threshold
(`docs/s2disasm/s2.asm:64553-64605`). The same flee routine continues
extending `Camera_Max_X_pos` until `$3160` and deletes from its own branch
instead of tail-calling `MarkObjGone` (`docs/s2disasm/s2.asm:64592-64628`).

**Originating commit.** `5e81c96a9` S2 HTZ2 Obj52 defeated-flag handoff:
`TestS2Htz2LevelSelectTraceReplay` advances f9150 -> f9361.
Follow-up `455acd880`: keeping Obj52 persistent through its ROM flee/delete
branch advances f9361 -> f9405.

---

## P79 -- Obj37 post-owner ring allocation is S2 plain AllocateObject, not S3K after-current

**Pattern.** S2 lost-ring spills have a preallocated Obj37 owner slot, but only
ring 0 uses that owner. Every remaining ring in `Obj37_Init` calls plain
`AllocateObject` and takes the lowest free SST slot. Do not reuse S3K's
`AllocateObjectAfterCurrent` chain for the S2 remainder.

**Engine symptom.** The first hurt frame shows correct player hurt state and a
reasonable lost-ring count, but the ring slots are shifted upward or collide
with debris/child-object holes. In ARZ2 round 15, the engine put post-owner
Obj37 rings after the owner/previous ring, while ROM filled lower holes around
the Grounder Obj8F/Obj90 debris cluster.

**What to check / fix.**
1. Distinguish owner preallocation from the allocation routine used by the
   remaining rings. S2 needs owner slot plus plain lowest-free allocation.
2. Keep this keyed to the slot-layout/object-allocation model, not to a trace
   route or zone name.
3. Cross-check S3K separately: S3K `Obj_Bouncing_Ring` uses
   `AllocateObjectAfterCurrent` for its post-owner remainder and must keep that
   behavior.

**ROM citation.** S2 `HurtCharacter` preallocates the first Obj37 owner with
`AllocateObject` (`docs/s2disasm/s2.asm:85444-85461`). S2 `Obj37_Init` starts
with `movea.l a0,a1` for ring 0 and then calls plain `AllocateObject` in the
loop (`docs/s2disasm/s2.asm:25125-25146`). S3K analog:
`docs/skdisasm/sonic3k.asm:21065-21088,35549-35591`.

**Originating commit.** `d27307e27` S2 ARZ2 Obj37 allocation split:
`TestS2Arz2LevelSelectTraceReplay` stays at f1717 but improves 1420 -> 980
errors.

---

## P80 -- Consolidated engine objects must not delete the live body when ROM deletes only a helper sub-object

**Pattern.** Some S2 object assemblies are represented by several SST slots:
a visible/solid body plus helper children or end-checker slots. A consolidated
engine class may own all those states, but its delete semantics still need to
match the specific ROM slot that reaches `DeleteObject`. If the ROM deletes a
helper slot while the body slot keeps running, the engine must keep the body
instance alive rather than replacing it with a fresh static visual.

**Engine symptom.** A rider standing on the object gets reseated through the
new-landing `SolidObject_Landed` path instead of continued `MvSonicOnPtfm`,
usually producing a one-pixel Y mismatch. In OOZ2, deleting Obj3E's live body
and spawning a replacement static capsule made Sonic snap to `$214`; ROM kept
the routine-2 body alive and continued riding at `$240-$18-$13 = $215`.

**What to check / fix.**
1. For multi-slot objects, identify which routine/object slot calls
   `DeleteObject`. Do not map that delete onto the consolidated Java parent
   unless the body slot is the one deleting.
2. Preserve the same `ObjectInstance` identity for rideable solids through
   results/cutscene/end-checker transitions when ROM keeps the body slot alive.
3. If a helper slot disappears but the body should persist visually and
   physically, transition the body state in place; do not spawn a replacement
   solid unless the ROM actually allocates a new body slot.

**ROM citation.** S2 Obj3E body routine calls `SolidObject` every body pass
with `d1=$2B,d2=$18,d3=$18` (`docs/s2disasm/s2.asm:84833-84845`). The
routine-$A end-checker scans for released animals, calls `Load_EndOfAct`, then
deletes that checker slot (`docs/s2disasm/s2.asm:84967-84978`); it does not
delete the routine-2 body slot.

**Originating commit.** `90011d815` S2 OOZ2 Obj3E capsule body lifetime:
`TestS2Ooz2LevelSelectTraceReplay` advances f12861 -> green.

---

## P81 -- Disabled `fixBugs` branches must not drive shipped P2 behavior

**Pattern.** The S2 disassembly includes conditional `fixBugs` blocks that are
not present in the shipped ROM behavior. If a P2/Tails write or branch lives
only inside one of those blocks, the engine must not implement it as normal
runtime behavior unless the selected ROM/build actually enables that code.

**Engine symptom.** CPU Tails interacts with the right object and slot but
takes a timer/state transition that only Sonic should trigger. In ARZ2, the
engine started Obj89 arrow's timer when CPU Tails stood on the arrow. The
shipped ROM leaves the P2-standing timer write under disabled `fixBugs`, so
`Obj89_Arrow_Platform` keeps calling `PlatformObject` and `MvSonicOnPtfm`
reseats hurt Tails one pixel lower on the next support pass.

**What to check / fix.**
1. When a copied routine contains `if fixBugs` / `else` blocks, verify which
   side is active in the shipped ROM before porting any behavior.
2. Treat P1 and P2 standing/contact checks separately when only one side of the
   ROM code is outside the disabled block.
3. Keep the fix keyed to the object-local ROM state and shipped build path. Do
   not compensate by route, zone, trace frame, or a known failing trace.

**ROM citation.** Obj89 arrow calls `PlatformObject` while
`obj89_arrow_timer` is zero. The P2-standing timer write appears only inside
the disabled `fixBugs` block, while the active P1-standing branch writes
`#$1F` and immediately decays it (`docs/s2disasm/s2.asm:65658-65683`).
`PlatformObject` processes P1 then P2, and continued support moves the rider
through `MvSonicOnPtfm` (`docs/s2disasm/s2.asm:35728-35739,35641-35660`).

**Originating commit.** `dd03abfa9` S2 ARZ2 Obj89 arrow CPU Tails timer gate:
`TestS2Arz2LevelSelectTraceReplay` advances f5929 -> f5968.

---

## P83 -- Shifted child positions can share the parent's unload anchor

**Symptom.** One child of a multi-object assembly unloads at a camera boundary
while the parent and sibling remain active. A captured parent list then exposes
the stale reference because the removed child correctly loses its rewind id.

**Root cause / fix.** The port derived each child's off-screen anchor from its
shifted Java spawn. ROM initialized the child's live `x_pos/y_pos` and its
`objoff_32/30` anchor from the parent first, then shifted only live position.
Carry the pre-shift parent anchor separately from the child's spawn/current
position. If Java also tracks the graph, retain the captured parent list as the
authority and use only a deferred inverse owner link for defensive detach and
post-restore relinking; do not hide the authoritative edge as transient.

**ROM citation.** MCZ Obj6A allocates its two after-current children and shifts
their live positions (`docs/s2disasm/s2.asm:54184-54204`), while
`Obj6A_InitSubObject` copies the unshifted parent position into each child's
`objoff_32/30` first (`s2.asm:54207-54213`). Both movement routines feed
`objoff_32` to `MarkObjGone2` (`s2.asm:54278-54280,54303-54305`).

**Originating commit.** `0b7c59be9` (`fix(rewind): detach unloaded MCZ rotating platform`).

---

+## P84 -- Objects that read global oscillators must not advance them

**Symptom.** Every oscillating platform or hazard in the level changes phase
while one particular object is active. The target object can look locally
plausible, but a later unrelated platform is hundreds of oscillator ticks away
from ROM.

**Root cause.** The object port calls the engine's global oscillator update
before reading the table. ROM object routines read `Oscillating_Data` only;
`OscillateNumDo` advances the shared table once at the level-loop tail after
all object slots. An object-local update therefore adds a second tick per frame,
and a different counter domain can defeat frame-number deduplication entirely.

**What to check.** When an object reads `Oscillating_Data+N`, port only the
read and local position calculation. Keep the single global update under the
level loop's owner. Add a test that snapshots the complete oscillator table,
executes the object once, and proves the table is unchanged.

**ROM citation.** S2's level loop calls `OscillateNumDo` after
`ExecuteObjects` (`docs/s2disasm/s2.asm:5091-5104`). S3K's concrete
origin is `Obj_MGZMovingSpikePlatform`
(`docs/skdisasm/sonic3k.asm:7909,71029-71072`).

**Originating commit (S3K).** `<pending: MGZ moving-spike oscillator ownership milestone>`.

---

+---

## P84 -- Grounded squash-edge escapes can still publish push while moving away

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

## P85 -- Only the horizontal spring locks the player's grounded controls

**Symptom.** After a vertical or diagonal launcher, the player's first grounded
frames ignore held left/right. The trace shows both sides landing with the same
`inertia`, then the ROM applying acceleration on the very next frame while the
engine holds the landing value for several frames before catching up. The drift
is small at first and compounds for the rest of the act.

**Root cause.** `move_lock` is the ROM's only grounded-input lock, and in the
spring family only the horizontal spring `loc_18AEE` (`loc_18B1C`, `move.w #$F,move_lock(a1)`) writes it. The up (`loc_189ca`, s2.asm:33924-33966), down (`loc_18cc6`, :34177-34196) and diagonal launches, the springboard `obj40` (:52262) and the cpz pipe-exit spring `obj7b` (:56341) write no lock
of any kind. An engine "springing"/"recently launched" marker used by objects
for their own re-contact and carry tests must not also gate horizontal input --
doing so invents a control lock the ROM has nowhere. It is easy to miss because
`move_lock` is decremented only in the grounded slope-repel step, so an invented
timer that ticks every frame looks harmless in the air and then bites on the
landing frame, several hundred rows from the object that set it.

**Correct pattern.** Gate grounded input on the modelled `move_lock` timer
alone. A spring that really does set `move_lock` should call the engine's
move-lock setter; keep any launch marker free of input semantics.

**ROM citation.** docs/s2disasm/s2.asm:34031.

**Originating commit.** `<pending: spring grounded control lock milestone>`.

## How to add a new entry

When a trace-replay-bug-fixing iteration commits an object fix whose root
cause is a class of bug (not a one-off):

1. Identify which pitfall pattern category fits, or pick the next P-number
   if none fit.
2. Append a new entry following the template above.
3. Reference the originating commit hash so future readers can see the full
   diff and test cases.
4. Mirror to the other skill tree's copy of `s2-implement-object/rom-pitfalls.md` in the
   same commit. Use the commit trailer `Skills: updated`.
5. If the pattern is cross-game (S2 + S3K share it), copy the entry into
   the `s3k-implement-object` skill's `rom-pitfalls.md` with the analogous
   S3K disasm citation.
