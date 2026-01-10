# Sonic 2 (Genesis) REV01: Collision plane switching (Obj03 PlaneSwitcher)

This document describes how Sonic 2 implements collision plane (solid layer) switching using the invisible boundary objects commonly referred to as “plane switchers” or “path switchers”. It is intended as a strategy brief for a coding agent to reimplement the behaviour in a separate engine while pulling the object data directly from the original REV01 ROM.

Primary reference is the Sonic Retro `s2disasm` disassembly (REV01 build option) and Sonic Retro SCHG pointer documentation (see Sources at the end).

## What “collision plane switching” means in Sonic 2

Sonic 2 maintains two “solid collision paths” (often called path 0 and path 1). The player object carries two bytes that control which path is used when probing level collision:

| Player object field | Offset in player object | Values used by the game | Meaning |
|---|---:|---|---|
| `top_solid_bit` | `$3E` | `$0C` or `$0E` | Selects which collision path is used for top (floor) solidity checks |
| `lrb_solid_bit` | `$3F` | `$0D` or `$0F` | Selects which collision path is used for left, right, bottom solidity checks |

In the stock game, these are set as a pair: path 0 uses (`$0C`, `$0D`) and path 1 uses (`$0E`, `$0F`).

Your engine can represent this as `player.collisionLayer = 0 or 1`, and map that to your two preloaded collision datasets.

### Context: solid tiles, chunks, and why switchers exist

Sonic 2 terrain is built from small solid tiles assembled into chunks, then chunks assembled into the level. Each chunk stores two independent collision layers (here called A/B, or path 0/1). The player collides with only one layer at a time, so loops, crossing paths, and wave tracks are built by swapping the player's active layer at specific boundaries. In Sonic 1, similar effects were achieved by swapping entire chunks; Sonic 2 uses layer switching instead for finer control.

## Object responsible: ObjID 03 “Collision plane switcher”

The switching logic is implemented by object ID `$03`, labelled `ObjPtr_PlaneSwitcher` in the object pointer table.

Key ROM offsets (REV01):

| Item | ROM offset |
|---|---:|
| Object pointer table (`Obj_Index`) | `$1600C` |
| ObjID `$03` routine entry (PlaneSwitcher) | `$1FCDC` |
| PlaneSwitcher size table `word_1FD68` (half-span in pixels) | `$1FD68` (values `$20,$40,$80,$100`) |
| PlaneSwitcher debug mappings label | `$1FFB8` (`Obj03_MapUnc_1FFB8`) |

Only the routine logic and subtype decoding are required for functional reimplementation. The mappings are only used for debug visibility in some builds.

## High level behaviour

A PlaneSwitcher is a line segment boundary. It is either a vertical boundary at `x = obj.x` spanning a vertical range around `obj.y`, or a horizontal boundary at `y = obj.y` spanning a horizontal range around `obj.x`.

When the player crosses the boundary (left to right for vertical, top to bottom for horizontal) while also being within the segment’s span, the object updates the player’s collision path (and optionally the player sprite draw priority) based on the object’s subtype bitfield.

The object also maintains a per-player “which side am I on” state so it only triggers when the player crosses from one side to the other.
### Collision layers vs visual priority

Collision layers (A/B, or path 0/1) are independent from visual priority (L/H). Either side of the switcher has its own target collision layer and its own target priority. Some switchers only change priority (see render_flags bit 0 / "priority only") and leave collision unchanged.

Rendering expectations for priority:

- Low priority (L): sprites are drawn between low-priority tiles and high-priority tiles, so high-priority tiles can occlude the player.
- High priority (H): sprites are drawn after high-priority tiles, so the player appears in front of them.

For terminology used in some guides:
- `layer_1` refers to the left or top side.
- `layer_2` refers to the right or bottom side.
- `priority_1` refers to the left or top side.
- `priority_2` refers to the right or bottom side.

## PlaneSwitcher internal state (mirrors the original object)

The original uses these object fields:

| Field | Meaning |
|---|---|
| `objoff_32` (word) | Half-span in pixels (derived from subtype size) |
| `objoff_34` (byte) | Side state for player 1 (Sonic) |
| `objoff_35` (byte) | Side state for player 2 (Tails) |

Side state is treated as:

| Side state | Vertical meaning | Horizontal meaning |
|---:|---|---|
| 0 | player is left of boundary | player is above boundary |
| 1 | player is right of boundary | player is below boundary |

The initial state is seeded at object init based on the player’s position relative to the boundary, then the object runs its main routine immediately.

## Subtype bitfield (exact from the Obj03 routine)

The subtype byte is used as a bitfield:

| Bit | Mask | Meaning |
|---:|---:|---|
| 0–1 | `$03` | Size index (selects half-span via `word_1FD68`) |
| 2 | `$04` | Orientation (0 = vertical boundary, 1 = horizontal boundary) |
| 3 | `$08` | Collision path when on side 1 (right or below). 0 = path 0, 1 = path 1 |
| 4 | `$10` | Collision path when on side 0 (left or above). 0 = path 0, 1 = path 1 |
| 5 | `$20` | Sprite priority when on side 1 (right or below). 1 = high priority |
| 6 | `$40` | Sprite priority when on side 0 (left or above). 1 = high priority |
| 7 | `$80` | “Only switch when grounded”. If set, do nothing while the player is in-air |

Size decoding:

`sizeIndex = subtype & 3`

`halfSpanPixels = [0x20, 0x40, 0x80, 0x100][sizeIndex]`

The full segment length is `2 * halfSpanPixels`, so the four sizes are 64px, 128px, 256px, 512px (4, 8, 16, 32 blocks).

These values are commonly described as the switcher radius sizes (32, 64, 128, 256).
### Visual depiction (debug overlays)

Debug overlays typically draw the threshold line along the switcher axis (horizontal or vertical) with length `2 * halfSpanPixels`. They also render a translucent rectangle representing the valid trigger span, extending outward on the current side of the threshold. The filled side should flip as the player crosses the boundary (reflecting `current_side`).

Some tooling also renders labels showing the layer and priority for each side, plus the player�s current layer/priority; this is optional and not required for the in-game logic.

The original span check is inclusive on the lower bound and exclusive on the upper bound:

- vertical boundary: `obj.y - halfSpan <= player.y < obj.y + halfSpan`
- horizontal boundary: `obj.x - halfSpan <= player.x < obj.x + halfSpan`

## Trigger logic (per player, per PlaneSwitcher)

In pseudocode (single player, but the original runs this separately for Sonic and Tails):

```java
// Called every frame for each PlaneSwitcher object and each player.
void updatePlaneSwitcher(PlaneSwitcher sw, Player p) {
    if (debugPlacementMode) return; // original checks Debug_placement_mode

    int subtype = sw.subtype & 0xFF;

    // Optional "only if grounded" gate (still update current_side even if airborne)
    boolean groundedGate = (subtype & 0x80) != 0 && p.isInAir();

    boolean horizontal = (subtype & 0x04) != 0;
    int half = sw.halfSpanPixels;

    // Determine whether player is within the segment span (orthogonal axis range check)
    boolean inSpan = horizontal
        ? (p.x >= sw.x - half && p.x < sw.x + half)
        : (p.y >= sw.y - half && p.y < sw.y + half);

    // Determine current side (side 1 is >= boundary)
    int sideNow = horizontal
        ? ((p.y >= sw.y) ? 1 : 0)   // below or above
        : ((p.x >= sw.x) ? 1 : 0);  // right or left

    // Only act on a side transition while in range and not gated.
    if (inSpan && !groundedGate && sideNow != sw.sideStateFor(p)) {
        // If your loader supports the object "X-flip" flag (render_flags bit 0):
        // the original routine skips collision-path changes when that flag is set.
        // It still applies the sprite priority bits. This is commonly referred to
        // as a "priority only" layer switcher.
        boolean skipCollisionChange = sw.renderFlagsXFlip;

        if (!skipCollisionChange) {
            int pathBit = (sideNow == 1) ? 0x08 : 0x10; // bit3 for side1, bit4 for side0
            int path = ((subtype & pathBit) != 0) ? 1 : 0;
            p.setCollisionPath(path); // path 0 => (top,lrb)=(0x0C,0x0D), path 1 => (0x0E,0x0F)
        }

        int prioBit = (sideNow == 1) ? 0x20 : 0x40; // bit5 for side1, bit6 for side0
        boolean highPrio = (subtype & prioBit) != 0;
        p.setHighPriority(highPrio);
    }

    // Current side is always updated after checks, even when out of range.
    sw.setSideStateFor(p, sideNow);
}
```

Notes for fidelity:

The original sets both `top_solid_bit` and `lrb_solid_bit` together. For best compatibility with other future object behaviours, keep the concept of two selectors internally even if you expose a single `collisionLayer` in your engine.

The `current_side` state is kept up to date even when the player is outside the switcher's span, or when a grounded-only switcher is skipped due to the player being airborne.

## Strategy for reimplementation in your Java engine

This strategy assumes you already load level object entries from the ROM and already have both collision layers decoded.

### 1. Data model

Represent ObjID `$03` as a lightweight trigger object rather than a renderable sprite. Store `x`, `y`, `subtype`, `halfSpanPixels` (precomputed from subtype), and per-player side state (`sideStateP1`, `sideStateP2`). If your object loader exposes object flip flags, also store `renderFlagsXFlip` (to match the original behaviour where that flag skips collision-path changes).

### 2. Player integration

Add either `collisionPath` (0 or 1) or the original pair (`topSolidBit`, `lrbSolidBit`). Also add a draw priority flag (or equivalent) if you want to honour subtype bits 5 and 6.

Wire your level collision probing so it selects collision data based on `collisionPath` (or on the `topSolidBit` and `lrbSolidBit` selectors).

### 3. Object update ordering

In the original, PlaneSwitchers run as normal objects inside the object update loop and can change the player’s collision path before the next collision probe that frame.

In a reimplementation, aim to apply PlaneSwitchers before running the player’s “find floor / wall” collision probes for the frame. If your engine probes collision inside the player update, evaluate PlaneSwitchers near the start of the player update, or run a dedicated “pre-collision triggers” pass that includes Obj03.

### 4. Multiple PlaneSwitchers

Sonic 2 levels contain many PlaneSwitchers. The original behaviour is event-driven (it only applies when you cross a boundary), so multiple switchers can coexist without constantly fighting.

Replicate this by maintaining per-switcher per-player side state and only applying on transitions.

Also ensure the side state is updated even when the player is outside the range; otherwise jumping around a switcher can cause incorrect triggers when re-entering its span.

### 5. ROM pulling specifics (what you need from the ROM)

From each object layout entry, you need `objId` (byte), `x` and `y` (pixel coordinates), `subtype` (byte), and optionally the placement flip flag that maps to `render_flags` bit 0 (if you support that feature). When `objId == 0x03`, instantiate `PlaneSwitcher`.

### 6. Suggested validation approach

Validate against REV01 in an emulator by choosing a level with obvious multi-path collision and verifying that solids switch exactly when crossing the boundaries. Test all four sizes and both orientations (subtype bits 0–2), plus the grounded-only flag (bit 7). If your object loader supports flip flags, also test “priority-only” variants (flip flag set) where collision path is not changed but sprite priority is.

### 7. Common level design patterns

Loops: typically one grounded-only switcher mid-loop to swap to the inside path, and one after the loop to return to the default path. A pre-loop switcher can be used defensively to ensure the correct starting layer.

Waves (e.g., Chemical Plant crossing waves): two switchers set the player to the correct collision layer at entry. Additional priority-only switchers can adjust sprite priority at crests/valleys while keeping the collision layer unchanged.

Zig-zag paths: switchers placed near springs or turnarounds ensure the player is forced onto the alternate path after reversal.
## Sources

`s2disasm` (Sonic Retro disassembly). The `Obj03` routine contains the authoritative subtype decoding and state machine:
- https://www.git.fragag.ca/s2disasm.git/tree/s2.asm?id=37a5d3c1a6a9fb30e8b7455857560a7832cf1179

Player object field offsets and the documented `top_solid_bit` and `lrb_solid_bit` values:
- https://www.git.fragag.ca/s2disasm.git/tree/s2.constants.asm?id=37a5d3c1a6a9fb30e8b7455857560a7832cf1179

Sonic Retro SCHG pointer page (ObjID 03 offsets for REV01):
- https://info.sonicretro.org/SCHG:Sonic_the_Hedgehog_2_(16-bit)/Object_Editing/Pointers
