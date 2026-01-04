# Sonic 2 (Genesis) REV01 object collision boxes and interaction (spikes, springs, monitors)

This document describes how **Sonic 2 REV01** stores and evaluates **player vs object collision** for interactive objects such as spikes and springs. It focuses on the engine’s “box” style interactions (solid blocking, riding, and touch response hitboxes), and on how those boxes are defined (or implied) by object code.

This is intended as a “source of truth” for comparing a reimplementation against the original.

## Scope

This covers object collision in two senses.

One sense is **solid object collision** (platforms, blocks, rideable solids, objects that push the player out of overlap).

Another sense is **touch response collision** (enemies, hazards, monitors, collectables, and other objects that trigger a response on overlap).

Terrain collision (tiles and sensors) is out of scope here, except where object collision depends on the same player radii.

## Where the collision data lives

The level’s object placement data provides only **object identity and placement** (object ID, X, Y, subtype). It does not provide an explicit hitbox definition.

Object hitboxes in Sonic 2 are defined in one of two ways.

The first way is by writing a **collision_flags byte** in the object’s runtime state (object RAM). This drives the shared “touch response” system, which uses a global table of sizes.

The second way is by passing explicit widths and heights into shared **SolidObject** style routines, or by doing a bespoke check in the object’s own code (common for solids and many setpiece objects). This makes solidity “data driven” only in the sense that the object code chooses constants (often dependent on subtype).

## Object RAM fields that matter

Offsets below are within a single object slot (object_size is $40 bytes).

| Field name (disasm) | Offset | Size | Meaning |
|---|---:|---:|---|
| x_pos / y_pos | $08 / $0C | long | Position (16.16 fixed point) |
| x_vel / y_vel | $10 / $12 | word | Velocity |
| y_radius / x_radius | $16 / $17 | byte | Collision radii used heavily by player code and by object solidity routines |
| width_pixels | $19 | byte | Sprite and solidity helper width used by many objects |
| collision_flags | $20 | byte | Touch response hitbox selector and category |
| collision_property | $21 | byte | Touch response “HP” or special meaning depending on object |
| status | $22 | byte | Includes bits written by SolidObject indicating standing and pushing |

Conventions for SolidObject outputs, as used across objects.

Bits 3 to 6 of the object’s status after a SolidObject call.

| Bit | Mask | Meaning |
|---:|---:|---|
| 3 | p1_standing | Sonic standing on object |
| 4 | p2_standing | Tails standing on object |
| 5 | p1_pushing | Sonic pushing object |
| 6 | p2_pushing | Tails pushing object |

The high word of d6 after a SolidObject call.

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | p1_touch_side | Sonic touching object side |
| 1 | p2_touch_side | Tails touching object side |
| 2 | p1_touch_bottom | Sonic touching object bottom |
| 3 | p2_touch_bottom | Tails touching object bottom |
| 4 | p1_touch_top | Sonic touching object top |
| 5 | p2_touch_top | Tails touching object top |

(See the disassembly constants file for exact masks and naming.)

## The two main collision systems

### Touch response (generic hitboxes)

The touch response system is the shared “player touches object” detector and dispatcher.

The key routine is:

- **TouchResponse** at ROM **$3F554** (label `TouchResponse`, comment `loc_3F554` in the disasm)
- Size table **Touch_Sizes** at ROM **$3F600** (label `Touch_Sizes`, comment `byte_3F600`)

#### collision_flags encoding

The object’s `collision_flags` byte (offset $20 in object RAM) encodes both a size and a category.

The low 6 bits select an entry in Touch_Sizes.

The high 2 bits select the collision category.

| Bits | Value range | Meaning in TouchResponse |
|---|---|---|
| 0 to 5 | $00 to $3F | Size index into Touch_Sizes |
| 6 to 7 | $00 | Enemy style interaction (also used for some non enemies) |
| 6 to 7 | $40 | Special category (various object specific behaviours) |
| 6 to 7 | $80 | Hurt category (hazards) |
| 6 to 7 | $C0 | Boss category (delegates to boss specific logic) |

TouchResponse masks with `#$3F` for size selection, and with `#$C0` for category selection.

#### Touch_Sizes table (ROM $3F600)

Values are **width radius** and **height radius** in pixels (half extents for an axis aligned overlap check). The code treats these as radii (it subtracts width, then adds 2×width, and similarly for height).

| Index | Width radius (px) | Height radius (px) | Notes |
|---:|---:|---:|---|
| $00 | 4 | 4 | Tiny (generic default) |
| $01 | 20 | 20 |  |
| $02 | 12 | 20 |  |
| $03 | 20 | 12 |  |
| $04 | 4 | 16 |  |
| $05 | 12 | 18 |  |
| $06 | 16 | 16 | Monitors (size index checked in engine code) |
| $07 | 6 | 6 | Rings (many ring objects use their own handling, but this size exists) |
| $08 | 24 | 12 |  |
| $09 | 12 | 16 |  |
| $0A | 16 | 8 |  |
| $0B | 8 | 8 |  |
| $0C | 20 | 16 |  |
| $0D | 20 | 8 |  |
| $0E | 14 | 14 |  |
| $0F | 24 | 24 |  |
| $10 | 40 | 16 |  |
| $11 | 16 | 24 |  |
| $12 | 8 | 16 |  |
| $13 | 32 | 112 |  |
| $14 | 64 | 32 |  |
| $15 | 128 | 32 |  |
| $16 | 32 | 32 |  |
| $17 | 8 | 8 |  |
| $18 | 4 | 4 |  |
| $19 | 32 | 8 |  |
| $1A | 12 | 12 |  |
| $1B | 8 | 4 |  |
| $1C | 24 | 4 |  |
| $1D | 40 | 4 |  |
| $1E | 4 | 8 |  |
| $1F | 4 | 24 |  |
| $20 | 4 | 40 |  |
| $21 | 4 | 16 |  |
| $22 | 24 | 24 |  |
| $23 | 12 | 24 |  |
| $24 | 72 | 8 |  |
| $25 | 24 | 40 |  |
| $26 | 16 | 4 |  |
| $27 | 32 | 2 |  |
| $28 | 4 | 64 |  |
| $29 | 24 | 128 |  |
| $2A | 32 | 16 |  |
| $2B | 16 | 32 |  |
| $2C | 16 | 48 |  |
| $2D | 16 | 64 |  |
| $2E | 16 | 80 |  |
| $2F | 16 | 2 |  |
| $30 | 16 | 1 |  |
| $31 | 2 | 8 |  |
| $32 | 32 | 28 |  |

#### What the overlap test actually does

TouchResponse uses a player point derived from the player’s position and radii, rather than using the player’s full sprite rectangle.

Important details.

The player X used for the test is `x_pos - 8` (so the “touch” origin is offset left by 8 pixels).

The player Y used for the test is `y_pos - 8 - y_radius`.

If the player is ducking, TouchResponse special cases the vertical origin and effective radius (it nudges the top point down and forces a smaller y radius for the test). This is why “dynamic radii” matter for correctness.

After an overlap is found, the category bits in collision_flags decide whether TouchResponse routes to enemy kill logic, hurt logic, special logic, or boss logic. Hurt logic falls into HurtCharacter at ROM $3F878.

### Solid object collision (blocking and riding)

Solid object collision is the system that prevents the player passing through certain objects, sets standing and pushing state, and moves the player with platforms.

The key family of routines is located in the main collision module.

- **SolidObject** at ROM **$19718** (label `SolidObject`, comment `loc_19718`)
- **SolidObject_cont** at ROM **$199F0** (label `SolidObject_cont`, comment `loc_199F0`)
- **SolidObject_Always_SingleCharacter** at ROM **$1978E** (label `SolidObject_Always_SingleCharacter`, comment `loc_1978E`)
- **SlopedSolid** at ROM **$197D0** (label `SlopedSolid`, comment `loc_197D0`)

SolidObject is called by objects, usually once per frame, after any object movement has been applied.

#### SolidObject call signature

Inputs are passed in registers.

| Register | Meaning |
|---|---|
| d1 | Object width (radius style, half width in pixels, with some object specific padding often applied) |
| d2 | Object height (half height) used when the player is jumping |
| d3 | Object height (half height) used when the player is walking |
| d4 | Object X position (word), used for relative X calculations |

The routine uses the player’s own `x_radius` and `y_radius` fields for their size. That means correctness depends on the player code setting those radii per state (standing, rolling, and so on) before object collision is evaluated.

Outputs are communicated through the object status bits and d6 (as described earlier), and via side effects such as updating the player’s `status` bit 3 (on object) and calling platform movement helpers.

SolidObject_cont contains the core “resolve overlap” logic, including.

Top collision placing the player on the object and clearing vertical speed.

Side collision cancelling horizontal movement and setting pushing flags.

Bottom collision cancelling upward movement.

Special case handling for a few object IDs (for example a different “top collision” threshold for the CNZ pressure spring object ID, which is `ObjID_LauncherSpring`).

SlopedSolid is a companion routine for collisions with a slope height profile, used by diagonal springs and similar sloped solids.

## Diagnostics and common pitfalls (symptoms to likely engine mismatches)

### Springs
If spring "push" feels inconsistent, or right-facing springs never work:
- Check velocity units: Sonic 2 uses 16-bit signed velocities in 8.8 fixed point (`$1000` = 16px/frame).
- Horizontal springs: Ensure you gate activation by approach direction and flip `x_vel` sign based on the spring's X-flip flag.
- Orientation: Ensure `Obj41` subtype bits 4-6 are correctly decoded.

### Spikes
If spikes trigger repeatedly with no cooldown (and ring loss becomes chaotic):
- Check `invulnerable_time`: The hurt routine MUST set this (typically to 120 frames) and the spike content check MUST respect it.
- "Already hurt" guard: Only trigger hurt if the player is not already in the hurt routine.
- Contact type: Spikes only hurt if the SolidObject call reports the correct contact type (standing for upright, side touch for sideways).

### Item monitors
If Sonic "floats rolling" above monitors instead of breaking them:
- **Bug:** You are likely treating monitors as solid for rolling players.
- **Fix:** `SolidObject_Monitor` explicitly returns early (not solid) if Sonic is rolling/spinning. This allows the player to overlap the monitor and trigger the `TouchResponse` break logic.
- **Order:** Ensure SolidObject logic runs before TouchResponse (or that they interact correctly regarding position updates).

## Case studies (spikes, springs, monitors)

These examples show that object hitboxes are often implied by the helper routine parameters and not by a standalone “collision box struct”.

### Spikes (Object 36)

Spikes are object ID `ObjID_Spikes`, implemented by **Obj36**.

The object entry point is at ROM **$15900** (comment `Sprite_15900` before `Obj36`).

Initialization chooses size and orientation from the upper subtype nibble and loads width and y radius from a small table at ROM **$15916** (label `Obj36_InitData`, comment `byte_15916`).

Obj36_InitData (ROM $15916), interpreted as pairs of `width_pixels` and `y_radius`.

| Upper subtype nibble group | width_pixels | y_radius | Meaning (from object code) |
|---:|---:|---:|---|
| 0 | 16 | 16 | Upright or ceiling spikes |
| 1 | 32 | 16 | Upright or ceiling spikes |
| 2 | 48 | 16 | Upright or ceiling spikes |
| 3 | 64 | 16 | Upright or ceiling spikes |
| 4 | 16 | 16 | Sideways spikes |
| 5 | 16 | 32 | Sideways spikes |
| 6 | 16 | 48 | Sideways spikes |
| 7 | 16 | 64 | Sideways spikes |

During update, spikes call SolidObject rather than using collision_flags.

For upright spikes (routine `Obj36_Upright`), the object computes its SolidObject parameters as:

- d1 = width_pixels + $0B
- d2 = y_radius
- d3 = y_radius + 1
- d4 = x_pos

Then it calls `SolidObject` and inspects the object status bits set by SolidObject.

If Sonic or Tails is standing on the spikes, Obj36 directly calls a spike specific hurt helper `Touch_ChkHurt2` (a thin wrapper that checks invulnerability and then calls HurtCharacter).

Sideways spikes use the “touch side” outputs in d6 high word and then call Touch_ChkHurt2 when a side touch occurs.

Upside down spikes use the “touch bottom” outputs in d6 high word and then call Touch_ChkHurt2 when a bottom touch occurs.

Important takeaway.

Spikes do not rely on TouchResponse’s collision_flags box selection for their primary hurt behaviour. They rely on SolidObject overlap resolution and on SolidObject’s reported contact sides.

### Springs (Object 41)

Normal springs are object ID `ObjID_Spring`, implemented by **Obj41**.

The initialization routine is at ROM **$188A8** (comment `loc_188A8` before `Obj41_Init`).

Springs choose their orientation from subtype bits and use different mapping frames and art tiles accordingly. They store a strength (vertical or horizontal velocity applied to the player) in `objoff_30`, selected from `Obj41_Strengths`.

Typical strength values (stored as signed words):
- `-$1000` (Red spring, strong): 16 px/frame (8.8 fixed).
- `-$0A00` (Yellow spring, weak): 10 px/frame (8.8 fixed).

Collision and interaction is done via the solid object system, not via collision_flags.

For the upward spring (routine `Obj41_Up`, comment `loc_18980`), the object performs a SolidObject check against each character using `SolidObject_Always_SingleCharacter` at ROM $1978E, with fixed parameters.

- d1 = $1B
- d2 = 8
- d3 = $10
- d4 = x_pos

If the appropriate standing bit is set in the spring object’s status after the SolidObject call, the spring triggers its bounce routine (comment `loc_189CA`), which sets the player’s vertical velocity to the spring strength, sets the “in air” status bit, clears “on object”, and forces the spring animation.

Diagonal springs rely on the sloped solidity routines (`SlopedSolid` at ROM $197D0), using a slope height table selected by subtype.

Important takeaway.

Springs define their “hitbox” by the SolidObject or SlopedSolid parameters inside their object code. There is no standalone collision rectangle stored in layout data for springs.

### Monitors (Object 26)

Monitors are object ID `ObjID_Monitor`, implemented by **Obj26**.

Monitors demonstrate a hybrid approach.

They set `collision_flags(a0) = $46` in init.

This is category $40 with size index $06 (the Touch_Sizes entry annotated as monitors). This allows the shared touch response system to find and react to monitor overlap when the player attacks.

Monitors also implement solidity by calling SolidObject via a monitor specific wrapper.

- SolidObject_Monitor at ROM **$1271C** (comment `loc_1271C`)

The monitor uses:

- d1 = $1A
- d2 = $0F
- d3 = $10
- d4 = x_pos

It calls SolidObject for each character. **Crucially**, it checks if the character is in a rolling/spinning state. If so, it treats the monitor as non-solid (returns early) so that the player can pass through/overlap and trigger the `TouchResponse` break logic.


Important takeaway.

Many objects use both systems at once: collision_flags for “touch response triggers” and SolidObject parameters for physical blocking and standing.

## Player radii and state dependence (why this matters to object collision)

Object collision depends on dynamic player radii.

Sonic’s standing radii are set on init (Sonic object init sets `y_radius = $13` and `x_radius = 9`) and are restored when Sonic returns from a rolling state (for example `Sonic_ResetOnFloor_Part2` at ROM $1B0AC sets `y_radius = $13` and adjusts y_pos upward by 5 pixels to prevent embedding in the ground).

Rolling and other states reduce y_radius and sometimes x_radius elsewhere in the player code.

If your engine keeps standing radii always, SolidObject and TouchResponse will not match Sonic 2 behaviour in edge cases, including springs and spikes.

## Strategy for reimplementation in a Java engine

### Core recommendation

Represent object collision with two explicit subsystems.

One subsystem is “touch response hitboxes” driven by collision_flags and Touch_Sizes.

The other subsystem is “solid object collision” driven by explicit width and height parameters chosen by each object, with contact results expressed in the same masks that Sonic 2 uses (standing bits, pushing bits, touch side and touch bottom bits).

### ROM data extraction plan

TouchResponse size table.

Read Touch_Sizes from ROM offset $3F600 (51 entries, 2 bytes each). These are radii in pixels.

Spikes size table.

Read Obj36_InitData from ROM offset $15916 (8 entries, 2 bytes each). Use this to set width_pixels and y_radius based on the upper subtype nibble.

Everything else.

Most object collision boxes are not stored in explicit data. They are baked into object code as immediates passed into SolidObject and related routines, or as constants written to collision_flags.

A pragmatic approach is to build a small per object “collision descriptor” map by consulting the disassembly and mirroring what each object does in init and update.

### Implementation mapping (high level)

TouchResponse reimplementation.

Replicate the overlap test exactly (including the player origin offsets and the crouch special case), then route by collision_flags category.

SolidObject family reimplementation.

Implement SolidObject_cont as the authoritative solver. It is the function that decides which side was hit, applies snapping, clears velocity, sets pushing and standing bits, and optionally calls platform movement helpers.

Object specific wiring.

For spikes (Obj36), emulate what Obj36 does.

Set width_pixels and y_radius from Obj36_InitData, then call SolidObject with the same derived d1 d2 d3 values. If SolidObject reports standing, side touch, or bottom touch, call your HurtCharacter equivalent, respecting invincibility and invulnerability timers.

For springs (Obj41), emulate what Obj41 does.

Call SolidObject_Always_SingleCharacter or SlopedSolid with the same hardcoded parameters. If standing is reported, apply the spring’s velocity and state changes.

For monitors (Obj26), emulate what Obj26 does.

Set collision_flags to $46 and implement the monitor solidity wrapper (parameters shown above). Then allow touch response to handle attack breaking while solidity handles standing.

### Verification checklist (for comparing your current implementation)

- TouchResponse uses Touch_Sizes radii, not full widths and heights.
- TouchResponse uses player origin offsets (x_pos minus 8, y_pos minus 8 minus y_radius).
- Ducking alters the vertical test origin and the effective y radius in TouchResponse.
- collision_flags bit meaning matches (low 6 bits are size index, high 2 bits are category).
- SolidObject_cont sets the same contact outputs (standing bits in object status, touch side and touch bottom masks in d6 high word).
- Spikes hurt logic is driven by SolidObject contact masks, not by collision_flags.
- Springs trigger when standing is reported by SolidObject or SlopedSolid, and they use fixed constants for collision parameters.

## Current implementation alignment (2026-01-04)

This section summarizes how the Java engine currently aligns to REV01 and what remains.

Aligned / close:
- TouchResponse sizes are loaded from ROM Touch_Sizes (0x3F600) and treated as radii.
- TouchResponse uses the ROM origin offsets (x_pos-8, y_pos-8-(y_radius-3)) and the crouch special case (y + 12, height = 20).
- TouchResponse overlap test now mirrors the ROM asymmetric width/height checks (including the 0x10 width threshold).
- Spikes (Obj36) use Obj36_InitData tables and SolidObject params d1=width+0x0B, d2=y_radius, d3=y_radius+1.
- Springs (Obj41) use SolidObject params d1=0x1B, d2=8, d3=0x10.
- Monitors (Obj26) use collision_flags 0x46 plus SolidObject params d1=0x1A, d2=0x0F, d3=0x10.
- SolidObject uses the vertical offset (+4), the 0x10 landing threshold, and the 4px near-edge side rule.
- SlopedSolid is implemented for diagonal springs using the Obj41 slope tables.
- Spring interactions now apply the ROM-style position nudges (+8/-8 and +/-6) and trigger the spring animation state.
- TouchResponse routing now applies hurt logic and post-hit invulnerability frames for HURT and ENEMY categories.
- TouchResponse enemy kills use invincible/roll/spindash checks and ROM-style bounce behavior.
- Ringless hurt now triggers death state (KillCharacter) with ROM velocities and death animation ID (0x18).
- Hurt now spawns Obj37-style lost rings with ROM velocity/offset rules, CalcSine table output, and bounce/gravity timings.
- Pushing is tracked on the player when side contact is resolved while grounded.
- SolidObject contact results are surfaced via SolidContact (standing/touch side/bottom/top) for object-specific logic.
- On-object carry is implemented: when standing on a solid, the player is moved by the solid's delta (MvSonicOnPtfm analogue).
- Object visuals now use ROM art/mappings/animations for spikes/springs/monitors; monitor icon rise timing matches Obj2E (effect after rise).

Still divergent / missing:
- Sloped solids are still partial: diagonal springs use slope tables, but other sloped solids are not wired yet.
- SolidObject does not set per-object status bits or d6 contact masks; objects rely on SolidContact instead, but some routines still expect status-style flags.
- No special-case landing threshold for the launcher spring (0x14).
- TouchResponse routing is partial: hurt/invulnerability are implemented, but boss logic and enemy kill handling are still placeholder.
- Ring scatter is implemented, but invincibility-powered enemy kills remain incomplete.
- No p2/Tails solid-object handling; only main character is evaluated.
- Object positions are still mostly static (platform motion and moving solids are not implemented yet).

Recommended next steps:
1) Add object status flags and d6-style contact masks (or an equivalent mapping) for object-specific behaviours (spikes, monitors, and similar).
2) Wire invincibility-powered enemy kills and proper enemy HP/score handling.
3) Expand TouchResponse categories (boss logic, special-case routing) beyond hurt/enemy, and wire monitor effects that depend on special routing.

## Source anchors

Disassembly sources used for offsets and behaviour.

```
Repository: https://www.git.fragag.ca/s2disasm.git
Branch: code-split
Commit used here: c61b4478147d8ed0b974c41d4b260785a8d513f1
Files referenced: s2.asm, s2.constants.asm, s2rev01.bin
```

Sonic Retro SPG documents that describe the same systems at a conceptual level.

```
SPG Solid Objects (local file): /mnt/data/SPG_Solid Objects - Sonic Retro.htm
Other SPG docs provided by the project may give additional context on terrain collision and slope collision.
```
