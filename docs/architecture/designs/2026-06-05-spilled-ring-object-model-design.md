# Spilled-Ring Object Model (Obj37 in the unified touch loop) — Design

**Status:** Design approved (architecture). Pending spec review → implementation plan.
**Date:** 2026-06-05
**Driver:** Trace `TestS2Mtz2LevelSelectTraceReplay` f641 (Tails hurt ~5 frames early) and a class of
hurt/touch-ordering traces. The engine's spilled (lost) rings live outside the unified object touch
loop and update their physics at the wrong frame point, so ROM's slot-order "break on first
overlapping object" — where a lower-slot spilled ring suppresses a later hazard — is not reproduced.

---

## Problem

In ROM, scattered rings (Obj37, `collision_flags = $47`) are **real objects in `Dynamic_Object_RAM`**.
`TouchResponse` (docs/s2disasm/s2.asm:84956-85046) checks placed/level rings first (`Touch_Rings`),
then runs `Touch_Loop` over the object RAM **in slot order, breaking on the first overlapping object**
(`Touch_CheckCollision` → `Touch_ChkValue` → `rts`). `Touch_ChkValue` (s2.asm:85196-85219) routes
`collision_flags & $C0` in `$40–$7F` (size `!= $46`) to the ring/collectible branch: if the toucher is
not invulnerable (`invulnerable_time < 90`) it collects the ring (`move.b #4,routine(a1)`,
`move.w a0,parent(a1)`) and `rts` — and either way the overlap **ends the loop**, so any
higher-slot hazard (spikes, badnik claw) is **not** reached that frame.

The engine diverges in **two** ways:

1. **Outside the touch loop.** Spilled rings live in `RingManager.LostRingPool`
   (src/main/java/com/openggf/level/rings/RingManager.java:1105-1523), collided by a separate
   `checkCollection()` (RingManager.java:1289-1319). The unified loop
   `ObjectManager.TouchResponses.processCollisionLoop` (ObjectManager.java:5284-5424, break at 5422)
   never sees them, so the break-on-first-overlap cannot account for a lower-slot spilled ring.
2. **Wrong frame point.** Lost-ring physics runs in `LevelManager.update()` (step 7 of
   `LevelFrameStep`), **after** player physics + touch responses — whereas ROM Obj37 executes in the
   object loop (step 2/3). So lost-ring positions drift ~1 frame from ROM.

A prior focused fix that injected a touch-loop break (without fixing #2) advanced mtz2 f641→f646 but
**regressed mcz1** f2757→f1665: the drifted lost-ring position reported an overlap where ROM had none,
so the (correct) break wrongly suppressed a real hurt. Fixing the order **requires** also fixing the
position timing — which is why this design adopts the full ROM-object model.

---

## Goal / non-goals

**Goal.** Model spilled rings as real ROM Obj37 objects that (a) execute their physics in the object
loop at the ROM frame point and (b) participate in the unified slot-ordered touch loop with ROM
`Touch_ChkValue` semantics (collect-if-eligible, then break). Result: a lower-slot spilled ring
suppresses a later hazard exactly as ROM does, with frame-accurate positions, across S1/S2/S3K.

**Success criteria.**
- mtz2 advances past f641 (Tails hurt timing matches ROM).
- **mcz1 does NOT regress** (the position-drift misfire is gone — the whole point).
- No green trace regresses (S1 ×10, S2 ehz1/scz/wfz); no S2/S3K frontier moves backward.
- Lost-ring rewind and rendering remain correct.

**Non-goals.**
- Placed/level rings stay on their separate `Touch_Rings` bitmap path (RingManager `collectStageRings`
  / S1 Obj25 touch path) — **unchanged**. This design touches **only spilled Obj37 rings**.
- No new gameplay behavior; comparison-only (no engine state hydrated from trace data).
- No zone/route/frame/gameId carve-outs — per-game differences gated on `PhysicsFeatureSet`.

---

## Architecture

Retire the parallel `LostRingPool` lifecycle (deferred physics + separate collection + bespoke rewind)
in favor of a real **`LostRingObjectInstance`** (Obj37):

- Lives in the object **slot table + `execOrder`** (it already allocates dynamic slots today via
  `allocateSlotAfter`, ObjectManager — now it also *executes* in those slots).
- **Executes its physics in the object loop** (`LevelFrameStep` step 2/3), at the ROM frame point —
  removing the deferred `LevelManager.update()` physics call.
- **Participates in the unified touch loop** in slot order via `collision_flags = $47`; the loop's
  ring-category handler reproduces `Touch_ChkValue`.

`LostRingPool` slims to a **spawner + render registry**: `spawnLostRings()` allocates slots (as today)
and spawns `LostRingObjectInstance`s through the normal object-spawn path, and exposes the live ring
set for the renderer. Bespoke physics/collection/rewind are removed.

### Components

| Component | Responsibility | Notes / refs |
|---|---|---|
| `LostRingObjectInstance` (new) | ROM Obj37, **per-ring** state only: position/velocity/`lifetime`/`phaseOffset` (+ collected flag, `sparkleStartFrame`); `updateMovement()` = bounce physics (gravity, per-game floor cadence, lifetime countdown, off-bottom delete); `collision_flags=$47`. **Reads** the shared spin frame for its displayed mapping — it does NOT own the spin counter. | Mirrors current per-ring `LostRing.java` state + `LostRingPool.updatePhysics` math, relocated into an object. ROM Obj37 / `Obj_LostRings`. |
| **Shared spill-animation owner** (kept) | Owns the **global** ROM spill-spin state `Ring_spill_anim_counter` / `Ring_spill_anim_accum` / `Ring_spill_anim_frame` (decelerating spin: accumulator += counter each frame). Ticked **once per frame** (not per ring); each ring renders `sharedSpillAnimFrame + phaseOffset`. | RingManager.java:1121-1124. This state is GLOBAL in ROM, not per-object — moving it into per-ring instances would desync the spin. Stays in the slimmed `LostRingPool` (or a dedicated `SpillAnimationState`) and ticks in `RingManager.update()`. |
| `ObjectManager.TouchResponses` lost-ring handler | A **dedicated branch evaluated every frame on overlap** (NOT edge-triggered), **keyed on the `LostRingObjectInstance` type / an explicit lost-ring collectible marker** (with `collision_flags == $47`) — NOT on the raw `$40–$7F`/size byte shape. On overlap: collect-if-not-invulnerable (`invulnerable_time ≥ 90` skip-collection gate), then **break**. Placed before the SPECIAL edge-trigger gate (ObjectManager.java:5378) OR via a lost-ring touch profile whose `requiresContinuousTouchCallbacks()` is true — so the ring still collects once `invulnerable_time` drops below 90 while the player is *continuously* overlapping (no new overlap edge occurs). | Models `Touch_ChkValue` ring branch, s2.asm:85196-85219, which re-runs every frame in the loop. **Type-keyed** so it does NOT capture other SPECIAL objects that share the `$47` byte shape — notably S1 placed rings (`Sonic1RingInstance.RING_COLLISION_FLAGS = 0x47`, Sonic1RingInstance.java:34/167), preserving the "placed rings unchanged" non-goal. Shared across players/sidekick. |
| `RingManager` / `LostRingPool` (slimmed) | `spawnLostRings()` → atomic slot allocation + spawn instances (see Allocation contract below); own the shared spill-anim state; hold live-ring registry for `RingRenderer`. Remove per-ring `updatePhysics`, `checkCollection`, bespoke per-ring rewind snapshot. | RingManager.java:1105-1523. |
| `LostRingRewindCodec` (new) | A `RewindDynamicObjectCodec` registered centrally for `LostRingObjectInstance`: captures per-ring fields and **recreates** the ring via a factory on restore (through `ObjectManager.recreateDynamicObject`). Without it, lost rings are diagnostic-only and **vanish on seek**. | See Rewind section. Precedent: Shield + Stars dynamic codecs. ObjectManager.java:3127, 3264, 3404. |
| `RingRenderer` | Reads the live `LostRingObjectInstance` set (registry) for positions and the shared spill-anim frame, keeping cached pattern rendering. | RingManager `RingRenderer`. |

### Frame ordering (before → after)

```
BEFORE                                        AFTER
2/3. object exec loop (no lost rings)         2/3. object exec loop INCLUDES LostRingObjectInstance
     player physics + unified touch loop           player physics + unified touch loop now sees
        (lost rings absent)                            lost rings in slot order (collect+break)
7.   LevelManager.update():                   7.   LevelManager.update(): lost-ring physics REMOVED
        LostRingPool.updatePhysics()  <-- drift          (physics now ran in the object loop, on time)
        checkCollection() (separate)
```

### Touch handler semantics (ROM `Touch_ChkValue`, s2.asm:85196-85219)

On overlap with a spilled ring in slot order (the engine branch is **keyed on the
`LostRingObjectInstance` type / lost-ring marker**, with `collision_flags == $47`, so it never matches
other SPECIAL objects sharing the byte shape — e.g. S1 placed rings at `$47`):
1. ROM context: `collision_flags & $C0` selects the collectible branch ($40–$7F); size byte
   `$47 & $3F = $07` (size `$46` is a monitor, not a ring — excluded).
2. If `invulnerable_time ≥ 90` (MainCharacter's, or the toucher's own when MainCharacter is the
   toucher): **skip collection**.
3. Else: collect — mark the ring collected (ROM `routine(a1)=4` sparkle), credit a ring, play SFX,
   set `parent` to the toucher.
4. **Either way: break the touch loop** (the ring was the first overlapping object). Later-slot
   hazards are not reached this frame.

**Every-frame evaluation (not edge-triggered).** ROM `Touch_ChkValue` runs inside `Touch_Loop` every
frame, so the ring re-evaluates the invuln gate each frame it overlaps. The engine's generic SPECIAL
($40–$7F) path is **edge-triggered** unless `requiresContinuousTouchCallbacks()` is true
(ObjectManager.java:5378) — which would mean a ring that overlaps during invulnerability, breaks
without collecting, and is *still continuously overlapping* when `invulnerable_time` later drops below
90, would never collect (no new overlap edge). The design therefore uses a **dedicated branch keyed on
the `LostRingObjectInstance` type / lost-ring marker, evaluated every frame on overlap** (placed before
the SPECIAL edge-trigger gate), or equivalently a lost-ring touch profile with
`requiresContinuousTouchCallbacks() == true`. The break itself happens on every overlapping frame
regardless. Because the branch is type-keyed, it does not alter any other SPECIAL object's listener
behavior.

Self-collection of just-spilled rings is prevented as in ROM by the freshly-spilled state (the engine's
existing initial animation-counter / lifetime gate that already blocks same-frame re-pickup).

---

## Cross-game (S1 / S2 / S3K)

Obj37 exists in all three games. Differences become Obj37 object behavior gated on `PhysicsFeatureSet`,
never `gameId`:

- **Floor-check cadence:** S1 every 4 frames (mask `#3`), S2/S3K every 8 (mask `#7`) — already a
  per-game value in `LostRingPool`; relocate as object data / a feature value.
- **S3K-only:** reverse-gravity rings and lightning-shield attraction — preserved as Obj37 behavior
  gated on existing S3K feature flags.
- The touch handler + invuln gate are ROM-identical across games (`Touch_ChkValue`), so shared.
- **Capacity:** ROM caps spilled rings at `0x20` (32). The engine must mirror this cap and ensure 32
  ring-objects + other dynamic objects do not exhaust the per-game dynamic slot pool
  (`ObjectSlotLayout`: S1 first=32/count=96, S2 16/112, S3K 4/89). The atomic allocation contract above
  handles slot pressure (stop-on-(-1), truncate) without allocation failure.

### Placement & ownership (resolved open question)

`LostRingObjectInstance` is **game-agnostic**, placed in `level.rings` alongside its spawner/owner
(`RingManager`/`LostRingPool`), with a **single, centrally-registered** `LostRingRewindCodec`. Rationale:

- Lost rings are **not** level-layout objects — they are spawned dynamically by the shared `RingManager`
  on a hit, so they never flow through a per-game `ObjectRegistry`/`ObjectSpawn`/`ObjectSlotLayout`
  placement path. There is no per-game registry that would naturally own them.
- Their behavior is already shared across games today (the `LostRingPool` is game-agnostic); the only
  differences (floor cadence, S3K reverse-gravity / lightning-shield) are `PhysicsFeatureSet`-gated, not
  game-identity branches.
- The ROM object id (`$37`) is uniform across S1/S2/S3K, and the dynamic rewind codec is cleaner as one
  central registration than three per-game copies (matching the central Shield/Stars codec precedent).

Per-game object **id constants** (e.g. `Sonic2ObjectIds`) are not required, since the ring is never
resolved through the per-game object factory; if a numeric id is needed for the occupancy oracle /
touch tables it is exposed as a shared constant (`$37`) on the ring class.

---

## Rewind

> **Correction (spec review):** generic field capture stores an object's *fields*, but **restoring a
> non-placement dynamic object requires a registered `RewindDynamicObjectCodec`**. `ObjectManager`
> restores dynamic entries only via `recreateDynamicObject(entry)` (ObjectManager.java:3264); classes
> without a codec are **diagnostic-only** and are NOT recreated on seek (ObjectManager.java:3127,
> 3404) — i.e. the rings would **disappear when the player rewinds across a spill**. A stable id alone
> is insufficient.

Spilled rings are **dynamic** (spawned on hit, not from the level layout / placement), so they take the
dynamic-object rewind path. Requirements:
- Implement and **centrally register a `LostRingRewindCodec`** (a `RewindDynamicObjectCodec`) for
  `LostRingObjectInstance`: it captures the per-ring fields and provides a **recreate factory** so
  `recreateDynamicObject` rebuilds the ring (with services + restored field state) on seek. This mirrors
  the existing Shield + Stars player-bound dynamic codecs.
- The ring exposes a **stable identity id** (`com.openggf.game.rewind.identity`) for deterministic
  seek/replay across the spawn/despawn of up to 32 transient rings.
- The **shared spill-animation state** (`Ring_spill_anim_*`) is captured by the slimmed
  `LostRingPool`/`SpillAnimationState` rewind path (it is global, not per-ring) — this small bespoke
  snapshot is **retained** (only the per-ring snapshot is retired).
- The old bespoke **per-ring** `LostRingPool` rewind snapshot is retired once the codec path is green.
- A rewind torture/round-trip test covers spilling rings, seeking across the spill, and replaying —
  asserting rings reappear with correct positions and the spin frame stays in sync.

---

## Edge cases & error handling

- **Atomic slot-allocation contract (spawn).** `allocateSlotAfter()` can return **-1** (no free slot),
  and the generic `addDynamicObjectInternal` path may otherwise allocate a *different* first-free slot
  or mark the object destroyed depending on pre-assignment (ObjectManager.java:1770) — either of which
  would break ROM slot order or graceful truncation. `spawnLostRings` therefore follows one explicit
  contract: iterate the rings to spawn; for each, `allocateSlotAfter(previousSuccessfulRingSlot)`; if it
  returns **-1, stop spawning** (truncate the spill); **only construct and register a ring for a real
  (≥0) slot**, and advance `previousSuccessfulRingSlot` to it. No ring is constructed for a failed
  allocation, so slot order and the live-ring registry never diverge. (RingManager.java:1157.)
- **Slot exhaustion:** the contract above caps naturally; additionally cap spilled-ring spawns at the
  ROM `0x20` (32) limit. Never throw; `log()` if a cap or a -1 truncates the spill.
- **Lifetime / off-bottom delete:** ring deletes at `lifetime ≤ 0` or below the camera bottom (ROM
  Obj37 delete), through the normal object delete path (`SlotAllocator.release`).
- **Collected state:** a collected ring plays the sparkle then deletes; it must not re-trigger the
  touch break after collection.
- **Invulnerability:** overlap during invuln breaks the loop but does not collect (modeled exactly).
- **Determinism:** ring spawn velocities/phase are derived deterministically (no `Math.random`);
  preserve the current angle-based scatter math.

---

## Testing

1. **Unit — physics frame-exactness:** `LostRingObjectInstance` bounce/gravity/floor-cadence/lifetime
   match ROM Obj37 step-for-step (per game), ported from the current `LostRingPool.updatePhysics`
   assertions.
2. **Unit — ordering invariant (the mtz2 mechanism):** with a spilled ring at a *lower* slot than a
   hazard, both overlapping the player, the touch loop collects the ring and **breaks**, so the hazard
   does not fire; with the ring at a *higher* slot, the hazard fires first. Direct, ROM-cited.
2b. **Unit — type-keying guard (placed rings unchanged):** an S1 placed ring object
   (`Sonic1RingInstance`, `collision_flags == $47`) overlapping in the object loop is handled by its
   existing listener path, **not** the lost-ring branch — proving the branch keys on type, not the
   `$47` byte shape, and the "placed rings unchanged" non-goal holds.
3. **Unit — invuln gate + every-frame collect:** overlap during `invulnerable_time ≥ 90` breaks
   without collecting; with the player *continuously* overlapping, the ring collects on the first frame
   `invulnerable_time` drops below 90 (proves the dedicated/continuous ring branch, not edge-trigger).
4. **Unit — shared spin sync:** all live rings render `sharedSpillAnimFrame + phaseOffset` from the
   single global owner; ticking once per frame keeps every ring's spin in lockstep (ROM
   `Ring_spill_anim_*`).
5. **Unit — atomic allocation/truncation:** when `allocateSlotAfter` returns -1 (or the `0x20` cap is
   hit), spawning stops; exactly the successfully-allocated rings are constructed/registered, in slot
   order; no ring exists for a failed slot.
6. **Occupancy oracle:** spilled rings now occupy slots; `TestS2ObjectOccupancyOracle` should see
   Obj37 in slots consistent with ROM (comparison-only).
7. **Rewind (dynamic codec):** torture/round-trip across a spill — rewinding *across* the spill
   recreates every ring via `LostRingRewindCodec` with correct positions, and the shared spin frame is
   restored in sync. Asserts rings do **not** vanish on seek (the codec is what prevents that).
8. **Trace:** mtz2 advances; **mcz1 does not regress**; greens stay green; full single-fork S1/S2/S3K
   `*TraceReplay` sweep shows no frontier moves backward.

---

## Risks

- **Blast radius:** physics, collection, rewind, and rendering all move from the pool into the object
  model. Mitigated by TDD (each piece behind a test before cutover) and the occupancy-oracle + trace
  guards. Stage the migration so the old pool path is removed only after the object path is green.
- **Slot pressure:** 32 ring-objects compete for the dynamic pool with other objects; mitigated by the
  ROM cap and graceful truncation, verified by a capacity test.
- **Determinism for rewind identity** across 32 transient rings — addressed by stable identity ids.

---

## Out of scope (follow-ups)

- Placed/level ring collection paths (`Touch_Rings` / `collectStageRings` / S1 Obj25) — unchanged.
- Any sidekick-CPU steering work (separate effort; the sidekick touch/bounce infrastructure is already
  ROM-correct per the 2026-06-05 investigation).
