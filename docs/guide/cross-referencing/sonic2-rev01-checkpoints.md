# Sonic 2 (Genesis) REV01 (Sonic 2 “01”) Checkpoints (Starposts) (Object $79)

This document is intended to be a source-of-truth reference for reimplementing **Sonic 2 REV01** checkpoints (starposts) in another engine while pulling data directly from the ROM. It is based on the provided `s2disasm` and written so a coding agent can compare an existing implementation against the original.

Repository referenced (local zip): `s2disasm` (fragag)
Primary files:
- `s2.asm` (main disassembly)
- `s2.constants.asm` (RAM + constants)
- `mappings/sprite/obj79_a.asm` (starpost mappings)
- `mappings/sprite/obj79_b.asm` (special-stage star mappings)
- `art/nemesis/Star pole.nem` (Nemesis art)
- `sound/sfx/A1 - Checkpoint.asm` (SFX)

---

## Terminology

Sonic 2 calls these “star poles / starposts” (often called “lampposts” in older docs). Internally they are **Object ID $79** (`ObjID_Starpost`).

There are two distinct visual/gameplay pieces:

Checkpoint pole (object $79, routine 0/2/4)
- the pole sprite itself, idle or activated

Checkpoint activation “dongle” (object $79, routine 6)
- the short-lived dangling sparkle/marker that swings briefly after activation

Special-stage “stars” (object $79, routine 8)
- the four orbiting stars that appear only when you hit a checkpoint with >= 50 rings (and fewer than 7 emeralds, in 1P)

---

## ROM offsets and labels (REV01)

All offsets below are ROM addresses as annotated in the disassembly labels/comments.

### Core object code
- Starpost object entry (`Obj79`) (Object $79): **ROM $001F0B4** (`s2.asm`, “Object 79 - Star pole / starpost / checkpoint”)
- Activation + save: `Obj79_CheckActivation` / `Obj79_SaveData`: within the same block (starting at **$001F0B4**)
- Load-from-checkpoint: `Obj79_LoadData`: **ROM $001F35E**
- Create special stars: `Obj79_MakeSpecialStars`: **ROM $001F4C4**
- Special star update logic: `Obj79_Star`: **ROM $001F536**

### Sprite mappings
- Starpost + dongle mappings: `Obj79_MapUnc_1F424` -> `mappings/sprite/obj79_a.asm` (**ROM $001F424**)
- Special star mappings: `Obj79_MapUnc_1F4A0` -> `mappings/sprite/obj79_b.asm` (**ROM $001F4A0**)

### Level start / respawn hook
- Level boundary + start location setup: `LevelSizeLoad` (**ROM $0000BFBC**)
  - If a checkpoint was previously activated, `LevelSizeLoad` calls `Obj79_LoadData` to restore saved state before camera initialization.

### Art + VRAM tile base
- VRAM tile base for checkpoint graphics: `ArtTile_ArtNem_Checkpoint = $047C` (`s2.constants.asm`)
- Pattern load request list: `PlrList_Std2` includes `plreq ArtTile_ArtNem_Checkpoint, ArtNem_Checkpoint` (`s2.asm`)
- Checkpoint Nemesis art: `ArtNem_Checkpoint: BINCLUDE "art/nemesis/Star pole.nem"` (`s2.asm`)

---

## Object placement in level data

Starposts are placed in level object layouts as **Object $79** with a **subtype** that encodes:
- low 7 bits: a per-act “checkpoint index” (increasing along level progression)
- bit 7 ($80): a flag used by checkpoint restore logic (see “camera min X lock” below)

Important: starposts are not “one global checkpoint”. Multiple starposts exist per act; the engine tracks the **highest index activated**.

---

## Runtime state: variables saved and restored

### The “activated checkpoint” marker
- `Last_star_pole_hit` (1 byte): the last activated starpost subtype (for player 1)
- `Saved_Last_star_pole_hit` (1 byte): copy used for restoring (`Obj79_LoadData` copies it back)

These are in the global work RAM map (see `s2.constants.asm` around `Last_star_pole_hit`).

### Saved checkpoint snapshot (player 1)

When player 1 activates a checkpoint, `Obj79_SaveData` stores:

- `Saved_x_pos`, `Saved_y_pos`: the checkpoint object’s world position (not the player’s)
- `Saved_Ring_count`: the ring count at time of activation
- `Saved_Timer`: the timer at time of activation
- `Saved_art_tile`: the player’s current `art_tile` (important for 2P art pointer adjustments / character tiles)
- `Saved_Solid_bits`: the player’s solid-bit / collision layer bits
- `Saved_Camera_*`: camera and parallax positions (X/Y for main BG and BG2/BG3)
- `Saved_Water_*`: water level and water flags (only applied if the level actually has water)
- `Saved_Extra_life_flags`: “extra life already awarded” flags at time of activation
- `Saved_Dynamic_Resize_Routine`: per-level dynamic boundary/event routine state
- `Saved_Camera_Max_Y_pos`: camera max Y boundary at time of activation

This snapshot is the “checkpoint state” that the engine can revert to.

There are parallel `_2P` variables for player 2 in 2-player mode.

---

## Graphics loading

### Art source and loading model

Checkpoint graphics are part of the standard level art set:

- Pattern Load Request list: `PlrList_Std2` always requests `ArtNem_Checkpoint` to VRAM tile base `ArtTile_ArtNem_Checkpoint ($047C)`.

In practice, this means you do not load checkpoint graphics on-demand when starposts spawn. They are available as soon as the level’s standard PLCs are processed.

### Sprite mappings and frames

Checkpoint pole mappings: `mappings/sprite/obj79_a.asm` (`Obj79_MapUnc_1F424`) defines 5 frames (indices 0–4).

Animation script `Ani_obj79` (in `s2.asm`) chooses frames as follows:
- anim 0: static frame 0 (idle)
- anim 1: static frame 1 (activated but not yet “finished”)
- anim 2: alternates frame 0 and frame 4 quickly (the short activation sparkle/flash state)

The spawned “dongle” object uses `mapping_frame = 2` (a 2x2 piece) and is animated by movement rather than the animation script.

Special stars mappings: `mappings/sprite/obj79_b.asm` (`Obj79_MapUnc_1F4A0`) defines:
- frame 0: a 2x2 star
- frames 1–2: tiny 1x1 sparkle frames used while orbiting (the routine cycles them)

---

## Activation rules (running through a checkpoint)

Main routine flow (1P + 2P):
- `Obj79_Main` checks activation for player 1, and also player 2 if `Two_player_mode` is enabled.

Activation guard:
- The object compares `Last_star_pole_hit & $7F` against its own `subtype & $7F`.
- If the last hit checkpoint index is already >= this checkpoint index, it will not re-activate.

Overlap region (player must pass through):
- Horizontal: player within roughly 16 pixels of the starpost center (a +/- 8 window implemented as `x_delta + 8 < $10`)
- Vertical: a larger window, roughly from 0x40 pixels above to 0x28 below (implemented as `y_delta + $40 < $68`)

On success (player passes through and it’s “new”):
- Plays `SndID_Checkpoint` (“ding-dong”)
- Spawns a secondary object (also ID $79) in routine 6 (“dongle”) that swings for a short duration
- Sets the main starpost animation to `anim = 1` (activated)
- Calls `Obj79_SaveData` to capture the checkpoint snapshot
- Marks the starpost as “activated” in the respawn table (so it stays activated if it despawns and respawns due to camera)

---

## Special-stage stars (>= 50 rings)

Stars are created at the moment the checkpoint is activated if all are true:
- not in 2-player mode
- `Emerald_count != 7`
- `Ring_count >= 50`

Creation (`Obj79_MakeSpecialStars`):
- Spawns 4 objects (same ID $79) with routine 8 (“star”)
- Each star stores:
  - a center point (checkpoint X, checkpoint Y - $30)
  - an angle offset (0, $40, $80, $C0)
  - a growth/lifetime counter (`objoff_36`) starting at 0
- The star motion is a sinusoidal orbit around that center point, with a grow-in period, a collidable middle period, then a shrink-out and deletion.

When do stars become “enterable”:
- When `objoff_36 == $80`, the star sets `collision_flags = $D8`.
- At this stage, collision triggers special stage entry:
  - sets `(f_bigring)` and sets `Game_Mode = SpecialStage` (via the star’s collision property processing)

Important for reimplementation: the stars are not a separate object ID. They are still object $79 with a different routine.

---

## Checkpoint “respawn persistence” (scrolling away and back)

The starpost uses an entry in the **Object Respawn Table** (per-object respawn index) to remember if it has been activated.

At init (`Obj79_Init`):
- If the respawn table indicates it is activated, it forces the starpost into activated visuals (`anim = 2`) and sets the respawn activated bit.
- Otherwise it compares against `Last_star_pole_hit` (so if you activated a later checkpoint, earlier ones will appear activated too).

This matters if your engine streams objects in/out based on camera: the starpost visuals must be deterministic from saved state even after despawn/respawn.

---

## Restoring state after death (continue from checkpoint)

### Where restore happens

On level start or restart, the engine decides whether to start at the act’s start location or from a checkpoint.

During `LevelSizeLoad` (ROM $0000BFBC):
- If `Last_star_pole_hit != 0`, it calls `Obj79_LoadData` to restore checkpoint state.
- If not, it loads the act’s default start position from the `StartLocations` table.

There is also related logic that controls whether the HUD and counters are cleared on level init:
- If starting from a checkpoint (`Last_star_pole_hit != 0`), the code path skips clearing rings/time/extra-life flags in `Level_ClrHUD` (later code may still override, see below).

### What `Obj79_LoadData` restores (and what it resets)

`Obj79_LoadData` restores:
- `MainCharacter x/y` to the saved checkpoint position
- `Timer` to the saved timer, then adjusts it (sets frame = 59 and decrements seconds by 1)
- camera X/Y and all saved BG parallax positions
- character `art_tile` and collision solid bits
- water state (only if `Water_flag` indicates the level supports water)
- `Dynamic_Resize_Routine` and `Camera_Max_Y_pos` state

Rings and extra-life flags:
- The routine copies `Saved_Ring_count` and `Saved_Extra_life_flags` into their live variables, but then immediately clears them (`Ring_count = 0`, `Extra_life_flags = 0`).

Practical outcome:
- Restarting from a checkpoint restores position and world/camera state, but the player restarts with **0 rings**.

This matches the classic Sonic behavior where rings are lost on death even if you respawn at a checkpoint.

### Camera minimum X lock (subtype bit 7)

After restoring, `Obj79_LoadData` checks:
- `tst.b (Last_star_pole_hit)` then `bpl.s return`
- if the value is negative (bit 7 set), it sets:
  - `Camera_Min_X_pos = Saved_x_pos - $A0`

Meaning:
- If the checkpoint’s subtype has bit 7 set ($80), the game prevents the camera from scrolling back behind a point near the checkpoint after respawn.

When implementing subtype parsing, preserve bit 7 and implement this behavior as part of restore.

---

## Animation details you need to match

Pole object (routine 0/2/4):
- routine 0 (`Obj79_Init`) sets mappings, tile base, render flags, width = 8px, priority = 5
- routine 2 (`Obj79_Main`) checks activation and then falls through to animation
- routine 4 (`Obj79_Animate`) uses `AnimateSprite` with `Ani_obj79`
- rendering is standard `MarkObjGone` behavior

Dongle object (routine 6):
- created when a checkpoint is activated
- initial state:
  - `mapping_frame = 2`
  - `objoff_30/32` store its center point (checkpoint x, checkpoint y - $14)
  - `objoff_36 = $20` is its lifetime counter
  - `parent` points back to the checkpoint object
- motion:
  - decrements `objoff_36` each frame
  - uses `angle` updated by -$10 each frame, feeding `CalcSine`, scaling by $C00 to produce a small circular swing
- when timer expires:
  - it sets the parent checkpoint to the “finished” look (`anim = 2`, `mapping_frame = 0`)
  - deletes itself

Stars object (routine 8):
- orbit uses `CalcSine` plus additional shaping math, and scales with `objoff_36` to produce grow-in, stable orbit, shrink-out
- becomes collidable when `objoff_36 == $80` by setting `collision_flags = $D8`
- deletes itself after shrink-out ends

---

## Implementation strategy for your Java engine

This is written to map onto the structure in `feature/ai-objects-expanded` while keeping the original behavior.

### 1) ROM-driven data extraction

At runtime or level-load time (matching your existing architecture for objects):
- Read object placements from the level object layout (you already do this).
- For each object $79:
  - store `x`, `y`, and `subtype` exactly (preserve bit 7)

Assets:
- Decompress `art/nemesis/Star pole.nem` into your pattern cache at VRAM tile base `$047C` (or your equivalent tile index) as part of “standard level assets”.
- Load mappings from the ROM ranges corresponding to `Obj79_MapUnc_1F424` and `Obj79_MapUnc_1F4A0` (or parse the disasm mapping files and bake them into your asset pipeline).

### 2) Engine-level checkpoint state block

Maintain a struct matching the saved variables (for player 1 at minimum):
- lastStarpostSubtype (byte)
- savedX, savedY (word)
- savedTimer (long) + the timer tweak on restore
- savedCamera positions (main + BG1/BG2/BG3)
- savedWater info (only relevant if zone has water)
- savedDynamicResizeRoutine, savedCameraMaxY
- savedArtTile and savedSolidBits (if these concepts exist in your engine; otherwise capture the equivalent “sprite tile base” and “collision layer bits”)

Do not overfit to Sonic’s RAM addresses in your engine, but keep one-to-one fields so the agent can reason about parity.

### 3) Activation behavior (collision / overlap)

Each frame for starpost objects in range:
- If debug placement mode is active, skip activation checks (original does).
- Compare `(lastStarpostSubtype & 0x7F)` to `(thisSubtype & 0x7F)` and require strictly less.
- Check overlap window roughly matching the original comparisons.

On activation:
- play checkpoint sound
- set pole anim = 1
- spawn dongle helper object (same object type, routine 6 in your internal state machine)
- if 1P and emeralds < 7 and rings >= 50: spawn 4 star helper objects (routine 8)
- update lastStarpostSubtype = thisSubtype (raw, including bit 7)
- snapshot all “saved” state fields

### 4) Respawn / restart from checkpoint

When the player loses a life and the act restarts:
- If lastStarpostSubtype == 0: use the act start location
- Else:
  - restore player position to savedX/savedY
  - restore camera/parallax/water/event state fields
  - restore timer to savedTimer, then apply the same tweak (frame=59 and second-1)
  - clear rings to 0 (match original)
  - if (lastStarpostSubtype & 0x80) != 0: set cameraMinX = savedX - 0xA0

### 5) Visual persistence when objects stream

If your object system despawns starposts and later respawns them from the ROM object layout, you need an equivalent of Sonic’s respawn-table “activated” bit.

A practical reimplementation:
- If `(lastStarpostSubtype & 0x7F) >= (thisSubtype & 0x7F)`, force this pole to render in its activated appearance.
- This teaches the agent to reproduce the same result without literally implementing the original respawn table bitfield, unless you already have one.

---

## Known pitfalls when reimplementing

If you see visual or state corruption in your current implementation, checkpoints have a couple of common failure modes:
- Treating subtype as signed without masking for ordering (you must compare on `& $7F`)
- Dropping subtype bit 7 (camera min X lock after restore will not work)
- Not restoring camera BG/BG2/BG3 positions, causing parallax jumps after death
- Spawning “stars” as a separate object ID rather than object $79 routine 8 (this breaks mappings and collision expectations)
- Loading checkpoint art on-demand instead of as part of standard level PLC (can lead to missing tiles if your load timing differs)

---

## Quick reference tables (summary)

### Constants
| Name | Value | Notes |
|---|---:|---|
| Object ID | $79 | Starpost, dongle, and stars all use $79 |
| Art tile base | $047C | `ArtTile_ArtNem_Checkpoint` |
| Rings threshold | 50 | Stars spawn only if rings >= 50 and emeralds != 7 and not 2P |

### Key routines (ROM)
| Routine | ROM | Purpose |
|---|---:|---|
| `Obj79` | $001F0B4 | Main object entry |
| `Obj79_SaveData` | $001F298 (within block) | Save checkpoint snapshot |
| `Obj79_LoadData` | $001F35E | Restore from checkpoint snapshot |
| `Obj79_MakeSpecialStars` | $001F4C4 | Spawn 4 orbiting stars |
| `Obj79_Star` | $001F536 | Orbit + collision + delete |
