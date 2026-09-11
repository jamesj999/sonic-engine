# S1 Ring Object Redesign

**Date:** 2026-04-01
**Status:** Approved

## Problem

Sonic 1 rings use a `Sonic1PhantomRingInstance` that deviates from the original ROM behavior. In the ROM, each ring is an independent object with its own slot, spawned via `FindFreeObj` from a parent layout entry. The phantom approach replaces this with a single placeholder object that manages N ring slots via countdown arrays and multi-touch regions — an abstraction that doesn't exist in the original game.

Additionally, per-game ring behavioral differences (sparkle timing, collision sizes, lightning shield gating) are inconsistently modeled, with S3K's sparkle delay being outright wrong (8 VBlanks instead of 5).

## Goals

1. Replace `Sonic1PhantomRingInstance` with individual `Sonic1RingInstance` objects — one per ring, each owning its own slot — matching the ROM's `Ring_Main` / `FindFreeObj` behavior.
2. Fix per-game ring timing bugs and make all behavioral differences explicit via configuration.
3. Keep RingManager as the unified renderer and collection detector across all three games.

## Non-Goals

- Changing how S2/S3K rings work (they don't use object slots in the ROM).
- Changing RingManager's rendering pipeline.
- Making S1 ring objects handle their own rendering or collection detection.

## Per-Game Ring Behavior Differences

| Attribute | S1 | S2 | S3K |
|-----------|----|----|-----|
| Layout format | Object (ID 0x25) with subtype | Separate ring table | Separate ring table |
| Object slots | Yes (1 per ring) | No | No |
| Spin delay | 8 VBlanks | 8 VBlanks | 8 VBlanks |
| Sparkle delay | 6 VBlanks | 8 VBlanks | 5 VBlanks |
| Sparkle total | 25 frames | 33 frames | 21 frames |
| Collision size | 6x6 | 6x6 | 8x8 |
| Floor check mask (lost rings) | 0x3 (every 4) | 0x7 (every 8) | 0x7 (every 8) |
| Lightning shield attraction | No | No | Yes |

## Design

### 1. Ring Configuration

Extend `PhysicsFeatureSet` (or a dedicated record it holds) with explicit ring configuration:

| Field | S1 | S2 | S3K |
|-------|----|----|-----|
| `ringSparkleDelay` | 6 | 8 | 5 |
| `ringCollisionWidth` | 6 | 6 | 8 |
| `ringCollisionHeight` | 6 | 6 | 8 |
| `ringFloorCheckMask` | 0x3 | 0x7 | 0x7 |
| `lightningShieldEnabled` | false | false | true |

`ringFloorCheckMask` already exists and is correct. The other fields are new.

Each game's `RingArt` class passes the correct `ringSparkleDelay` to the `RingSpriteSheet` 7-parameter constructor:

- `Sonic1RingArt`: already passes `SPARKLE_FRAME_DELAY = 6` (correct).
- `Sonic2RingArt`: add explicit `SPARKLE_FRAME_DELAY = 8` and use 7-parameter constructor.
- `Sonic3kRingArt`: add `SPARKLE_FRAME_DELAY = 5` and use 7-parameter constructor. **This fixes the S3K sparkle bug.**

RingManager reads `lightningShieldEnabled` from configuration before checking shield type, replacing the current fragile indirect protection.

### 2. Sonic1RingInstance

A new class replacing `Sonic1PhantomRingInstance`. Each instance represents a single ring in a single object slot.

**Extends:** `AbstractObjectInstance`
**Implements:** `TouchResponseProvider`

**States (matching ROM routines):**

| State | ROM Equivalent | Behavior |
|-------|---------------|----------|
| `INIT` | `Ring_Main` | Parent only: spawn children, transition to `ANIMATE` |
| `ANIMATE` | `Ring_Animate` | Exist in slot, provide touch response (flags 0x47) |
| `SPARKLE` | `Ring_Sparkle` + `Ring_Delete` | Countdown, touch response disabled (flags 0x00) |

**Construction:**

Each `Sonic1RingInstance` receives:
- `ObjectSpawn` — the layout entry (for parent) or a dynamically built spawn (for children)
- `RingSpawn` — direct reference to the corresponding entry in RingManager's spawn list, enabling fast identity-based lookup via `ringManager.isCollected(ringSpawn)`

**RingSpawn pairing:** `Sonic1RingPlacement.extract()` returns an additional `Map<ObjectSpawn, List<RingSpawn>>` that maps each ring layout entry to its expanded ring positions. The `Sonic1ObjectRegistry` factory receives this map. When creating a parent `Sonic1RingInstance`, the factory passes the full `List<RingSpawn>` — element 0 is the parent's own ring, elements 1..N are the children's rings. The parent passes the appropriate `RingSpawn` to each child at spawn time.

**Parent first update (INIT → ANIMATE):**

1. Read subtype: count = `(subtype & 0x07)`, spacing pattern = `(subtype >> 4) & 0x0F`
2. Look up spacing (dx, dy) from `Sonic1RingPlacement.getRingSpacing(subtype)`
3. Loop count-1 times:
   - Compute child position: `(baseX + (i+1)*dx, baseY + (i+1)*dy)`
   - Create child `Sonic1RingInstance` via `spawnChild(() -> new Sonic1RingInstance(childSpawn, childRingSpawn))`
   - Child gets its own slot via `allocateSlot()` (FindFreeObj equivalent)
   - Child starts directly in `ANIMATE` state
4. Parent transitions to `ANIMATE`

**ANIMATE state (per frame):**

- Check `ringManager.isCollected(ringSpawn)`:
  - If collected: clear collision flags to 0x00, transition to `SPARKLE`, start countdown
- Provide touch response with collision flags 0x47 (powerup category, size index 7) for slot-order blocking

**SPARKLE state (per frame):**

- Decrement countdown
- When countdown reaches 0: `setDestroyed(true)`
- Countdown duration: derived from `ringManager.isCollectedAndSparkleDone()` or computed as `sparkleFrameCount * sparkleDelay + 1` from ring configuration (S1: 4 * 6 + 1 = 25 frames)

**Destruction:**

Standard `setDestroyed(true)` → ObjectManager frees the slot in the normal exec loop cleanup. No special phantom slot release, no deferred cleanup, no reserved child slot machinery.

### 3. RingManager Changes

**Unchanged:**
- `RingPlacement` windowing, BitSet collection tracking, sparkle start frames
- `draw()` renders all active rings for all games
- `update()` AABB collection loop — still the authoritative collection detector
- `LostRingPool` — scattered ring physics, rendering, collection
- All public rendering methods

**Changed:**

- **Sparkle delay**: Read from ring configuration instead of defaulting from sprite sheet spin delay. Affects `isCollectedAndSparkleDone()` duration calculation.
- **Lightning shield guard**: Add explicit `lightningShieldEnabled` config check before attraction logic.
- **No new public methods needed**: S1 ring objects consume existing `isCollected(RingSpawn)` and `isCollectedAndSparkleDone(int, int, int)` APIs.

**Interaction flow per frame (S1):**
```
1. RingManager.update()       -> AABB detects collection, marks BitSet, sets sparkle start
2. ObjectManager exec loop    -> Sonic1RingInstance.update() checks isCollected(), manages countdown
3. RingManager.draw()         -> Renders uncollected as spin, collected as sparkle
4. Countdown reaches 0        -> setDestroyed(true), ObjectManager frees slot
```

**Interaction flow per frame (S2/S3K) — unchanged:**
```
1. RingManager.update()       -> AABB detects collection, marks BitSet, sets sparkle start
2. RingManager.draw()         -> Renders uncollected as spin, collected as sparkle
```

### 4. Cleanup

**Delete:**
- `Sonic1PhantomRingInstance.java`

**Remove from ObjectManager:**
- `preAllocatePhantomRingChildren()` method
- `allocateChildSlots()` / `freePhantomChildSlot()` / `freeAllReservedChildSlots()` methods
- `reservedChildSlots` map
- Any phantom-specific checks in `syncActiveSpawnsLoad()` or the exec loop

**Update:**
- `Sonic1ObjectRegistry`: factory for ID 0x25 creates `Sonic1RingInstance` instead of `Sonic1PhantomRingInstance`
- `Sonic1RingPlacement.extract()`: still expands ring groups into individual `RingSpawn` records for RingManager. The `ObjectSpawn` entries for 0x25 stay in the object list (no longer extracted out). Returns an additional `Map<ObjectSpawn, List<RingSpawn>>` mapping each ring layout entry to its expanded positions. This map is passed to `Sonic1ObjectRegistry` so the factory can pair each ring object with its `RingSpawn` references.
- `Sonic3kRingArt`: add `SPARKLE_FRAME_DELAY = 5`, use 7-parameter `RingSpriteSheet` constructor
- `Sonic2RingArt`: add explicit `SPARKLE_FRAME_DELAY = 8`, use 7-parameter constructor
- `getReservedChildSlotCount()` on `ObjectInstance` interface: remove if no other object type uses it; otherwise `Sonic1RingInstance` returns 0

## File Impact

| File | Action |
|------|--------|
| `Sonic1PhantomRingInstance.java` | Delete |
| `Sonic1RingInstance.java` | Create (new) |
| `Sonic1ObjectRegistry.java` | Update factory for ID 0x25 |
| `Sonic1RingPlacement.java` | Update `extract()` to keep object spawns in list |
| `ObjectManager.java` | Remove phantom slot reservation machinery |
| `PhysicsFeatureSet.java` | Add ring config fields |
| `Sonic3kRingArt.java` | Add `SPARKLE_FRAME_DELAY = 5`, use 7-param constructor |
| `Sonic2RingArt.java` | Add explicit `SPARKLE_FRAME_DELAY = 8`, use 7-param constructor |
| `RingManager.java` | Use config for sparkle delay, add lightning shield guard |
