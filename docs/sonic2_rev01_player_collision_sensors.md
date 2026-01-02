# Sonic 2 (Mega Drive/Genesis) REV01: Player solid collision (sensor bars)

This document is intended as a source of truth for how **Sonic 2 REV01** stores, loads, and evaluates **solid terrain collision for the player**, with emphasis on the **sensor bars** used to stop movement when Sonic hits solid tiles.

It is written so a coding agent can compare an existing reimplementation against the original behaviour, and then implement deltas in a clean, testable way.

_Generated: 2026-01-02 (UTC)._


---

## Scope

Covered here is the **player vs level terrain** collision used for floors, walls, ceilings, and slopes (the “solid tile” system). This includes:


The ROM data involved (global collision shape tables and per-zone collision index tables), how it is loaded, and the runtime query pipeline that turns a world coordinate and a solidity mode into a signed distance and surface angle.


Not covered is object-to-object collision (monitors, enemies), per-object solid platforms, and water or special-cased gimmick physics, except where they touch the terrain query primitives.


---

## High level architecture

At runtime, player collision is built from three layers of data.


First, the **level layout** selects **128×128 chunks**. Each chunk is an **8×8 grid of 16×16 blocks**.


Second, each 16×16 block entry in a chunk contains flip flags and a 10-bit **block ID**, plus 4 **solidity bits** that decide whether this block is solid for the current collision mode.


Third, the block ID is mapped through a **per-zone collision index table** (primary or secondary) to a **collision ID** 0–255. The collision ID indexes global shape tables to retrieve a collision height at a specific x or y inside the 16×16 tile, plus a surface angle.


The player’s “sensor bars” are implemented by calling the same collision query routines at a small set of sample points around Sonic’s body (typically two points for floor and ceiling, one point for each wall), where each query internally scans across up to two adjacent 16×16 blocks in the direction of movement.


---

## Data formats and key RAM variables

### 128×128 chunk table and chunk entry word

Chunks are stored in RAM at `$FFFF0000` (chunk table base). Each chunk is 128 bytes: 8 rows × 8 columns × 2 bytes per entry.


Each chunk entry is a 16-bit word with the following effective layout in REV01:


| Bits | Meaning |
|---:|---|
| 0–9 | 16×16 block ID (0–1023, but Sonic 2 uses 0–767 in practice) |
| 10 | X flip |
| 11 | Y flip |
| 12–15 | Solidity flags, where the bit selected depends on the current collision mode |


The engine tests solidity with `btst d5,d4`, where `d4` is the chunk entry word and `d5` is a bit index stored in the player object (see below). This is how Sonic 2 supports “primary” vs “secondary” collision and top-only vs all-sides solidity using the same tile data.


### Player object fields that control solidity

Two bytes in the player’s object work RAM select which solidity bits to test in the chunk entry word:


| Field | Offset in object struct | Meaning |
|---|---:|---|
| `top_solid_bit` | `$3E` | Bit number to test for top solidity (typically `$0C` or `$0E`) |
| `lrb_solid_bit` | `$3F` | Bit number to test for left/right/bottom solidity (typically `$0D` or `$0F`) |


On initialisation, Sonic sets `top_solid_bit=$0C` and `lrb_solid_bit=$0D`, which corresponds to using the “primary” solidity bits in chunk entries.


The player’s radii are also stored per-object and drive sensor placement:


| Field | Offset | Typical standing value |
|---|---:|---:|
| `x_radius` | `$16` | 9 |
| `y_radius` | `$17` | `$13` (19) |



### Radii vary by state (standing vs ball)

Although these fields are initialised to standing defaults, the stock engine treats them as **stateful** and updates them as Sonic changes form. Most of the “sensor bars” are derived directly from `x_radius` and `y_radius`, so if you only model standing values you will diverge whenever Sonic enters a ball state.

| State (concept) | `x_radius` | `y_radius` | `y_pos` adjustment | Where it happens (examples) |
|---|---:|---:|---:|---|
| Standing (default) | `9` | `$13` (19) | `0` | Set during `Obj01_Init`, and also written during jump setup before the ball check |
| Ball (rolling / spindash release / normal jump entry) | `7` | `$0E` (14) | `+5` | Written when a spindash is unleashed, and when jumping from a standing state |

The `+5` adjustment is the difference between the standing and ball radii (`$13 - $0E = 5`), and keeps the **bottom** of the collision capsule aligned when shrinking into a ball. In the original engine the inverse transition back to standing is handled in the roll unroll logic (not covered in this section), and should only be applied when there is room to stand.

Crouching/ducking for spindash charge does **not** shrink the radii. The radii change only when the charge is released into a roll.



### Per-zone collision index tables (block ID → collision ID)

Sonic 2 uses two per-zone collision index tables:


Primary collision index table, decompressed to RAM at `Primary_Collision`.


Secondary collision index table, decompressed to RAM at `Secondary_Collision`.


Each table is **0x300 bytes** (768 entries). The collision query routines mask the chunk entry block ID with `0x3FF`, but Sonic 2’s art and collision mappings are authored so the resulting IDs are within the 0x000–0x2FF range.


At runtime, a RAM pointer `Collision_addr` is set to either `Primary_Collision` or `Secondary_Collision` before doing terrain queries. The choice is driven by the player’s solidity mode, which is represented by `top_solid_bit` and `lrb_solid_bit`.


The engine loads these tables during level initialisation using Kosinski decompression.


### Global collision shape tables (collision ID → shape and angle)

The collision ID 0–255 indexes global tables in ROM:


A 256-byte “curve and resistance mapping” table (commonly treated as an **angle map**).


A 4096-byte “vertical collision array” containing 256 collision shapes × 16 samples each, used for floor and ceiling.


A 4096-byte “horizontal collision array” containing 256 collision shapes × 16 samples each, used for left and right walls.


In REV01 these live at the following ROM offsets (from the code-split ROM map):


| Asset                            | Purpose                                                               | ROM start   | ROM end   | Size                |
|:---------------------------------|:----------------------------------------------------------------------|:------------|:----------|:--------------------|
| Curve and resistance mapping.bin | Collision curve/angle mapping (256 bytes)                             | 0x042D50    | 0x042E50  | 0x100 (256 bytes)   |
| Collision array 1.bin            | Collision height map for vertical scans (floor/ceiling), 256*16 bytes | 0x042E50    | 0x043E50  | 0x1000 (4096 bytes) |
| Collision array 2.bin            | Collision height map for horizontal scans (walls), 256*16 bytes       | 0x043E50    | 0x044E50  | 0x1000 (4096 bytes) |



---

## ROM offsets for key collision routines (REV01)

These offsets match the disassembly label addresses (for REV01):


| Routine/Label                             | ROM offset   | Role                                                                            |
|:------------------------------------------|:-------------|:--------------------------------------------------------------------------------|
| AnglePos (aka Sonic_AnglePos in comments) | 0x1E234      | Grounded floor find (two bottom sensors), returns distance and angle            |
| Find_Tile                                 | 0x1E596      | Translate world (x,y) to chunk entry pointer (a1)                               |
| FindFloor                                 | 0x1E7D0      | Vertical scan for solid floor or ceiling using ColArrayVertical                 |
| FindWall                                  | 0x1E9B0      | Horizontal scan for solid wall using ColArrayHorizontal                         |
| ConvertCollisionArray                     | 0x1EAE0      | Present but effectively a no-op (RTS) in REV01                                  |
| LoadCollisionIndexes                      | 0x0049BC     | Decompress per-zone primary/secondary 16x16 collision index tables to RAM       |
| Sonic_DoLevelCollision                    | 0x1AEAA      | Airborne collision against floor, ceiling, and walls                            |
| Sonic_CheckFloor                          | 0x1EC4E      | Two-sensor downward check around Sonic; chooses best and returns distance/angle |
| Sonic_CheckCeiling                        | 0x1EF2E      | Two-sensor upward check around Sonic; chooses best distance/angle               |
| CheckLeftWallDist                         | 0x1F05E      | Left wall distance at x-10 using FindWall                                       |
| CheckRightWallDist                        | 0x1EEDC      | Right wall distance at x+10 using FindWall                                      |
| Sonic_Init sets default solidity bits     | 0x19F76      | Initialises top_solid_bit=$0C and lrb_solid_bit=$0D (primary solidity bits)     |



---

## Loading collision data from ROM

### Global collision shape tables

The global collision tables are not decompressed or transformed in REV01. A legacy conversion routine `ConvertCollisionArray` exists at `0x1EAE0` but immediately returns.


For a reimplementation, you can treat these as immutable ROM resources loaded once, or memory-mapped directly from the ROM image.


### Per-zone collision index tables

During level load, the engine decompresses two Kosinski-compressed streams into RAM (`Primary_Collision` and `Secondary_Collision`) via `LoadCollisionIndexes` (`sub_49BC`).


In a ROM-driven engine, you can either:


Parse a per-zone pointer table (as the original does), or use a known offset table produced from the disassembly’s ROM map. The code-split ROM map enumerates the compressed ranges for each zone’s collision index tables.


Compressed collision index ranges in ROM (REV01 code-split map):


| Zone(s)     | Which     | ROM start   | ROM end   | Compressed size   |
|:------------|:----------|:------------|:----------|:------------------|
| ARZ         | primary   | 0x045610    | 0x045760  | 0x150 (336 bytes) |
| ARZ         | secondary | 0x045760    | 0x0458C0  | 0x160 (352 bytes) |
| CNZ         | primary   | 0x0452A0    | 0x045330  | 0x90 (144 bytes)  |
| CNZ         | secondary | 0x045330    | 0x0453C0  | 0x90 (144 bytes)  |
| CPZ and DEZ | primary   | 0x0453C0    | 0x0454E0  | 0x120 (288 bytes) |
| CPZ and DEZ | secondary | 0x0454E0    | 0x045610  | 0x130 (304 bytes) |
| EHZ and HTZ | primary   | 0x044E50    | 0x044F40  | 0xF0 (240 bytes)  |
| EHZ and HTZ | secondary | 0x044F40    | 0x045040  | 0x100 (256 bytes) |
| MCZ         | primary   | 0x045200    | 0x0452A0  | 0xA0 (160 bytes)  |
| MTZ         | primary   | 0x045040    | 0x045100  | 0xC0 (192 bytes)  |
| OOZ         | primary   | 0x045100    | 0x045200  | 0x100 (256 bytes) |
| WFZ and SCZ | primary   | 0x0458C0    | 0x0459A0  | 0xE0 (224 bytes)  |
| WFZ and SCZ | secondary | 0x0459A0    | 0x045A80  | 0xE0 (224 bytes)  |


Notes:

Some zones appear only once in the map (primary only). In the original engine these can be implemented by pointing the missing table to the primary table or to an all-zero stream, depending on how the zone is authored. Verify against the zone’s collision usage before assuming “identical”.


---

## The collision query pipeline

All player terrain collision reduces to three primitives:


Finding the chunk entry word for a world coordinate.


Scanning vertically to find floor or ceiling.


Scanning horizontally to find a wall.


Each scan returns a **signed distance** in pixels and a surface **angle**. The caller uses the sign and magnitude to decide whether to snap Sonic to the surface, stop movement, or treat it as no collision.


### Primitive 1: Find_Tile (world x,y → chunk entry pointer)

`Find_Tile` (`0x1E596`) maps a pixel coordinate to a pointer into the chunk table at `$FFFF0000`.


It indexes `Level_Layout` (a byte array of chunk IDs) using `(y & 0x7F0) + ((x >> 3) & 0x7F0) + 0x40`, then multiplies the chunk ID by 0x80 (128 bytes per chunk) using a precomputed table `word_1E5D0`.


Within the selected chunk, it adds:


`(y & 0x70)` to choose one of the 8 rows (16 bytes per row), and


`((x >> 3) & 0x0E)` to choose one of the 8 columns (2 bytes per entry).


The resulting address is the chunk entry word for the 16×16 block containing (x,y).


A reimplementation should reproduce this addressing exactly, because it defines how the engine treats coordinates at chunk and block boundaries.


### Primitive 2: FindFloor and FindFloor2 (vertical scan)

`FindFloor` (`0x1E7D0`) scans for solid ground or ceiling along the vertical axis.


Inputs (as used by the player code):


`d2` is the query y coordinate in pixels.


`d3` is the query x coordinate in pixels.


`d5` is the solidity bit index to test in the chunk entry word. For floors and ceilings this is `top_solid_bit(a0)`.


`d6` is 0 for floor scans, `0x0800` for ceiling scans (this is XORed with the chunk entry flip bits to invert interpretation).


`a3` is the step in pixels to check the adjacent 16×16 tile if the current one is empty (typically `+0x10` for floor, `-0x10` for ceiling).


`a4` points to an angle buffer (one byte) where the routine writes the surface angle.


Algorithm summary:


It calls `Find_Tile` to get the chunk entry word, masks out the 10-bit block ID, and tests solidity by `btst d5,d4`.


If the block is not solid or has no collision ID, it shifts y by `a3` and calls `FindFloor2` to measure distance to the next block boundary, then returns distance plus 16. This is the “sensor bar” behaviour (scan up to 2 blocks).


If the block is solid, it looks up collision ID `cid = Collision_addr[blockID]`.


The angle buffer receives `ColCurveMap[cid]`, then is modified by X and Y flip flags so it matches the transformed surface.


It computes `localX = x & 0xF` (after applying X flip), then indexes the vertical collision array at `ColArrayVertical[cid*16 + localX]` to get a height sample.


The height sample is sign-extended to a word and transformed for Y flip and for ceiling vs floor (via XOR with `d6`).


Finally it converts the sample to a signed distance `d1` in pixels from the query point to the surface. A result of 0 means no collision, positive means free space before impact, negative means penetration (caller usually resolves by pushing Sonic out).


Important behaviours to match:


A sample value of `0` is treated as “no collision” and causes the scan to fall through to the adjacent 16×16 block.


A sample value of `0x10` is treated as a special “full height” case and causes `FindFloor2` to be called on the tile one step opposite `a3`.



### Primitive 3: FindWall and FindWall2 (horizontal scan)

`FindWall` (`0x1E9B0`) is the horizontal analogue of `FindFloor`.


It takes the same style of inputs, but:


`d2` is y, `d3` is x.


`d5` is usually `lrb_solid_bit(a0)` when checking left or right walls.


`d6` is 0 for right scans, `0x0400` for left scans.


`a3` is the step in x to check the adjacent 16×16 tile if the current one is empty (typically `+0x10` for right, `-0x10` for left).


It computes `localY = y & 0xF` (after applying Y flip), then indexes the horizontal collision array at `ColArrayHorizontal[cid*16 + localY]`.


The remainder of the logic mirrors `FindFloor`: flip transforms, scan up to two blocks, and return a signed pixel distance in `d1`.


---

## Player sensor bars and how they are used

The player collision code places a small number of sample points around Sonic and calls the primitives above. Each primitive internally checks up to two adjacent 16×16 blocks, which is why the sensors behave like “bars” rather than single points.


Summary table (upright case and the rotated variants used when Sonic is running on steep surfaces):


| Context                                            | Routine                         | Sample points (pixel coords)   | Scan                                                      | Notes                                                                          |
|:---------------------------------------------------|:--------------------------------|:-------------------------------|:----------------------------------------------------------|:-------------------------------------------------------------------------------|
| Floor check (grounded or landing)                  | Sonic_CheckFloor (0x1EC4E)      | (x±x_radius, y+y_radius)       | FindFloor downward (a3=+0x10), up to 2 blocks             | Chooses best of left vs right sensor. Treats distance >= 0x0E as no floor.     |
| Ceiling check (jumping into ceiling)               | Sonic_CheckCeiling (0x1EF2E)    | (x±x_radius, y−y_radius)       | FindFloor upward (d6=0x800, a3=−0x10), up to 2 blocks     | Used to stop upward movement and to handle running on ceilings at high angles. |
| Right wall check (upright)                         | CheckRightWallDist (0x1EEDC)    | (x+0x0A, y)                    | FindWall to the right (a3=+0x10), up to 2 blocks          | 0x0A equals x_radius+1 for standing Sonic (x_radius=9).                        |
| Left wall check (upright)                          | CheckLeftWallDist (0x1F05E)     | (x−0x0A, y)                    | FindWall to the left (d6=0x400, a3=−0x10), up to 2 blocks | Sets horizontal flip flag for FindWall when scanning left.                     |
| Right check when rotated (running on wall/ceiling) | CheckRightCeilingDist (0x1EE7C) | (x+y_radius, y−x_radius)       | FindWall style scan with swapped radii                    | Used when surface angle implies right side is 'up'.                            |
| Left check when rotated (running on wall/ceiling)  | CheckLeftCeilingDist (0x1EFF6)  | (x−y_radius, y−x_radius)       | FindWall style scan with swapped radii                    | Used when surface angle implies left side is 'up'.                             |



### Grounded floor alignment: AnglePos

When Sonic is on the ground, `AnglePos` (`0x1E234`) is the main routine that keeps him glued to the floor and updates his surface angle.


It performs two `FindFloor` scans at x offsets `+x_radius` and `-x_radius`, using y offset `+y_radius` from Sonic’s origin.


It selects the closer surface (the smaller distance), adopts the corresponding angle, and returns the chosen distance in `d1`.


A key threshold is `0x0E` (14 pixels). If the best distance is **greater than or equal to 14**, `AnglePos` treats this as “no floor” and does not update the grounded angle state. This is important for edge behaviour.


The caller then uses the returned distance to adjust Sonic’s y position and to decide whether he remains grounded.


### Airborne collision: Sonic_DoLevelCollision

`Sonic_DoLevelCollision` (`0x1AEAA`) handles collision resolution when Sonic is in the air.


It classifies the movement direction based on angle and velocity, then queries:


The floor with `Sonic_CheckFloor` when moving downward.


The ceiling with `Sonic_CheckCeiling` when moving upward.


Left and right walls using `CheckLeftWallDist` and `CheckRightWallDist` when moving laterally.


Based on the signed distances, it clamps position and zeroes velocity components, which is what produces the “stop moving when hitting a solid tile” result.


---

## Strategy for reimplementation in a ROM driven engine

This is an implementation strategy rather than a line-by-line port, but each step maps directly to the original responsibilities.


### 1. Treat terrain collision as a pure query over immutable level data

Build a `CollisionData` object for the loaded zone that contains:


A reference to `Level_Layout` and the chunk table ($FFFF0000 equivalent).


The decompressed `Primary_Collision[0x300]` and `Secondary_Collision[0x300]` tables.


References to `ColCurveMap[256]`, `ColArrayVertical[4096]`, `ColArrayHorizontal[4096]`.


Everything above can be derived directly from the ROM image using the offsets in this document.


### 2. Implement the three primitives as deterministic functions

Implement `findTileWordPtr(x,y)`, `findFloor(x,y,solidBitIndex,flipMode,step)`, and `findWall(x,y,solidBitIndex,flipMode,step)` with identical semantics to the 68k routines.


To make comparison easy, keep the I/O signature close to the original:


Inputs are pixel coordinates (not subpixel), solidity bit index, direction flip flag, step of ±16, and an angle out parameter.


Outputs are distance (signed int), chosen chunk entry word (useful for debugging), and surface angle byte.


### 3. Match Sonic’s sensor placement and mode switching

Implement wrappers that place sensors exactly like the original routines:


`checkFloor` uses (x±x_radius, y+y_radius).


`checkCeiling` uses (x±x_radius, y−y_radius).


`checkLeftWallDist` uses (x−10, y).


`checkRightWallDist` uses (x+10, y).


Select `Collision_addr` (primary vs secondary collision index table) and the solidity bit indices (`$0C/$0D` vs `$0E/$0F`) from the player’s state in the same way Sonic 2 does. A simple rule that matches REV01 is:


If `top_solid_bit == 0x0C` then use primary collision index table, else use secondary.


The same applies to left/right/bottom via `lrb_solid_bit`.


### 4. Integrate into your existing player movement phases

The collision code in Sonic 2 is sensitive to when it is run. For parity, keep the same overall order:


Apply acceleration, deceleration, gravity, and integrate position.


Run grounded collision glue (AnglePos) if grounded, else run airborne collision (Sonic_DoLevelCollision style).


Resolve penetration by snapping position using returned distances, and clear the corresponding velocity component when penetration occurs.


### 5. Build a comparison harness

For agent driven comparison against your engine, instrument the following per frame:


| Signal | Why it matters |
|---|---|
| Player x,y (pixel and subpixel) | Confirms integration order and rounding |
| x_radius, y_radius | Sensor placement |
| top_solid_bit, lrb_solid_bit | Collision mode selection |
| Collision_addr selected table | Primary vs secondary collision |
| For each sensor call: inputs and outputs (distance, angle) | Confirms FindFloor/FindWall semantics |


Then run deterministic replays with known inputs and compare these traces against traces generated by calling your reimplemented primitives on the same ROM derived data.


---

## Quick comparison checklist

Use this table as a fast “did we match the engine” sanity scan.


| Behaviour | Correct for Sonic 2 REV01 |
|---|---|
| Chunk entry word low 10 bits are block ID, bits 10 and 11 are X/Y flip | Yes |
| Solidity is tested by checking one of bits 12–15 (bit index from `top_solid_bit` or `lrb_solid_bit`) | Yes |
| Collision index tables are 0x300 bytes and map block ID to collision ID | Yes |
| Collision ID is used to index angle map and height maps (256 entries × 16 samples) | Yes |
| `FindFloor` and `FindWall` scan up to two adjacent 16×16 blocks (step ±16) | Yes |
| Standing Sonic sensors: x_radius=9, y_radius=19 | Yes |
| Wall checks use x±10 (x_radius+1 for standing Sonic) | Yes |
| Ground glue treats distance >= 14 as “no floor” | Yes |
| `ConvertCollisionArray` is effectively a no-op in REV01 | Yes |


---

## Current implementation gaps (engine review: 2026-01-02)

This section summarizes known discrepancies, bugs, and accuracy gaps observed in
the current Java implementation (relative to REV01), plus a concrete remediation
plan to reach parity. Use this as the short-term punch list.

### Discrepancies and likely bug sources

1. **Plane/Layer vs collision mode are conflated**
   - `sprite.layer` currently acts as the collision table selector in the sensor
     code, but the original intent of `layer` is the **level plane (A/B path)**
     selection used by the plane switcher (Obj03) and map lookup.
   - Result: collision is sampled from the wrong plane or wrong table, causing
     “phantom” ground or fall-throughs.

2. **Solidity bit testing is not per-bit**
   - REV01 uses `btst` on bits 12–15 of the chunk entry word, with the bit index
     taken from `top_solid_bit` or `lrb_solid_bit`.
   - The current code interprets those bits as two 2-bit enums (primary/secondary),
     which is a different model and will disagree for many tiles.

3. **FindFloor/FindWall semantics are not identical**
   - Special cases (`sample == 0`, `sample == 0x10`) should trigger the “scan the
     adjacent tile and return boundary distance” behavior. The current scan logic
     does not mirror REV01 and uses ad-hoc fallbacks.
   - Distances are stored as a `byte` instead of a signed word, which changes
     the sign/threshold behavior and makes negative penetration fragile.

4. **Sensor placement and radii are off**
   - Standing values should be `x_radius=9`, `y_radius=19`, but sensors currently
     use `y=±20`. This alone shifts floor contact by 1px.
   - Ball-state radii and the `+5` y-pos adjustment are not applied, so the
     collision capsule does not shrink/realign when rolling or jumping into ball.
   - Rotated wall/ceiling sensors are not using the swapped radii positions
     (`x±y_radius`, `y∓x_radius`), which breaks wall/ceiling contact points.

5. **Flip and angle transforms are incomplete**
   - The collision sample index is flipped, but the sample value itself is not
     transformed for ceiling/floor and Y/X flip logic (the REV01 `d6` behavior).
   - Angle transformations do not fully match REV01 for rotated or flipped tiles.

6. **Ground glue threshold does not match AnglePos**
   - REV01 treats distance `>= 0x0E` as “no floor” when grounded. The current
     logic uses a speed-based threshold (`min(speed+4, 14)`), which can detach
     Sonic too early or too late.

7. **Debug visuals do not reflect actual scan points**
   - Sensor distance labels are drawn at unrotated offsets, so they are wrong
     when Sonic is on walls/ceilings.
   - Collision overlays draw only vertical height maps and do not show horizontal
     scans, per-bit solidity, or which collision table was used.

### Remediation plan (recommended order)

1. **Separate plane selection from collision mode**
   - Treat `sprite.layer` strictly as the A/B map layer (plane switcher output).
   - Select **primary vs secondary collision tables** based on `top_solid_bit`
     and `lrb_solid_bit`, not sprite layer.

2. **Replace 2-bit collision modes with per-bit solidity**
   - Decode chunk entry bits 12–15 as **individual solidity flags**.
   - Implement `btst`-style checks with `top_solid_bit` and `lrb_solid_bit`.

3. **Rebuild FindFloor/FindWall as exact primitives**
   - Implement the two-block scan behavior, including `sample==0` and `sample==0x10`
     special cases, and return a signed word distance.
   - Keep inputs/outputs aligned with the disassembly (x/y pixel inputs, bit index,
     step ±16, angle out).

4. **Wire sensor placement to dynamic radii**
   - Replace hard-coded sensor offsets with `x_radius` / `y_radius` from state.
   - Apply the `+5` y-pos adjustment when entering ball state and the inverse on
     unroll (with clearance check).
   - Add the rotated wall/ceiling sensor variants using swapped radii positions.

5. **Fix flip and angle transforms**
   - Apply sample value transforms for X/Y flips and floor/ceiling direction
     (matching REV01 `d6` behavior).
   - Recompute angle transforms for flipped tiles and rotated surface cases.

6. **Align grounded glue to AnglePos**
   - Use the fixed `0x0E` cutoff for “no floor” when grounded, matching REV01.
   - Ensure only the closer of the two bottom sensors is used for ground glue.

7. **Upgrade debug overlays to be collision-accurate**
   - Draw sensor labels at the **actual rotated scan points**, not raw offsets.
   - Display per-sensor: input world coords, selected table (primary/secondary),
     solidity bit used, distance, and angle.
   - Add optional overlays for horizontal collision (FindWall) and per-bit
     solidity flags to validate tile authoring.

### Why a trace harness helps (minimal version)

Even a lightweight per-frame log of the 4–6 sensor queries (input coords, table
selected, bit index, returned distance/angle) can tell you whether a bug is in:
1) scan semantics, 2) sensor placement, or 3) collision response. That lets you
fix the correct layer without guessing. A full ROM-vs-engine diff harness is not
required to get value from this.

---

## Primary sources

Sonic 2 disassembly (fragag cgit mirror, code-split branch):

https://www.git.fragag.ca/s2disasm.git/

Key files used here: `s2.asm` (engine code and labels), `s2.constants.asm` (RAM/object field names), and `s2.txt` (ROM split map with file offset ranges).

Sonic Retro SPG documentation (conceptual explanations of the same collision system):

https://info.sonicretro.org/SPG_Solid_Tiles
https://info.sonicretro.org/SPG_Solid_Terrain
https://info.sonicretro.org/SPG_Slope_Collision
