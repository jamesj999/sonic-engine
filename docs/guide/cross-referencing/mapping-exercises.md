# Mapping Exercises

This page teaches you how to trace any feature between the Sonic disassemblies and the
OpenGGF engine. Rather than providing a static lookup table (which would go out of date
as the disassemblies evolve), each exercise teaches a **method** you can repeat for any
feature in any game.

Every exercise follows the same three steps:
1. Find it in the disassembly
2. Find it in the engine
3. Verify the correspondence

If you are not comfortable reading 68000 assembly, start with the
[68000 Primer](68000-primer.md) first.

**A note on games:** These exercises use Sonic 2 and its disassembly (s2disasm) because
S2 is the engine's most complete module. The *methods* apply to all three games, but the
disassembly structure differs significantly between s1disasm, s2disasm, and skdisasm --
different directory layouts, different label conventions, different object code
organisation. See [How the Engine Reads ROMs](how-the-engine-reads-roms.md) for a
comparison table, and [Per-Game Notes](per-game-notes.md) for the specifics of each game.

---

## Exercise 1: Tracing Level Data

**Question:** Where does the engine load the EHZ level layout from?

### Step 1: Find It in the Disassembly

Open `s2.asm` and search for `Off_Level`. You will find a level loading function near
line 20100:

```asm
    lea     (Off_Level).l,a0       ; a0 = pointer to the level offset table
    move.w  (a0,d0.w),d0          ; d0 = word offset for this zone/act
    lea     (a0,d0.l),a0          ; a0 = pointer to compressed layout data
    lea     (Level_Layout).w,a1   ; a1 = destination in RAM
    jmpto   JmpTo_KosDec          ; decompress using Kosinski
```

This tells us:
- `Off_Level` is a table of 16-bit word offsets. Each entry points (relative to the
  table start) to Kosinski-compressed level layout data.
- The zone/act combination is converted into a table index via bit manipulation.
- The data is decompressed into `Level_Layout` RAM.

The `Off_Level` table itself is a `BINCLUDE` directive pointing to binary data. You can
find the label in the disassembly to see where it is defined.

### Step 2: Find It in the Engine

Open the Sonic 2 constants file at
`src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java`. Search for
`LEVEL_LAYOUT`:

```
DEFAULT_LEVEL_LAYOUT_DIR_ADDR = 0x045A80
LEVEL_LAYOUT_DIR_ADDR_LOC = 0xE46E
```

The first value is the default location of the `Off_Level` table in the ROM. The second
is the address of the instruction that *references* the table (used for cross-validation).

The engine loads level layouts through a resource plan. The loading path is:

1. `Sonic2Constants` provides the table address.
2. `Sonic2.java` reads the table to find the offset for the requested zone/act.
3. The compressed data at that offset is decompressed using Kosinski decompression.
4. The resulting layout grid is passed to `LevelManager` for rendering.

### Step 3: Verify

You can use the RomOffsetFinder tool to confirm the address:

```
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search Level" -q
```

This searches for all disassembly items with "Level" in the name. The results will
include the layout data files and their calculated ROM offsets. You can compare these
against the constants in the engine.

### The Transferable Skill

For any piece of level data in any game:
1. Find the loading label in the disassembly (`Off_Level`, `Level_EHZ1`, etc.).
2. Search the game's constants file for the corresponding address.
3. Trace from the constant to the loading code to see how the engine processes it.
4. Use RomOffsetFinder to cross-check.

---

## Exercise 2: Tracing an Object (ArrowShooter)

**Question:** How does the ArrowShooter (Obj22) from Aquatic Ruin Zone work in the
engine compared to the disassembly?

### Step 1: Find It in the Disassembly

Search `s2.asm` for `Obj22`. You will find it around line 51034:

```asm
Obj22:
    moveq   #0,d0
    move.b  routine(a0),d0
    move.w  Obj22_Index(pc,d0.w),d1
    jmp     Obj22_Index(pc,d1.w)

Obj22_Index:
    dc.w Obj22_Init - Obj22_Index         ; routine 0
    dc.w Obj22_Main - Obj22_Index         ; routine 2
    dc.w Obj22_ShootArrow - Obj22_Index   ; routine 4
    dc.w Obj22_Arrow_Init - Obj22_Index   ; routine 6
    dc.w Obj22_Arrow - Obj22_Index        ; routine 8
```

This object has **five routines** (states). But notice that routines 0-4 are for the
shooter itself, while routines 6-8 are for the arrow it fires. In the original game,
both the shooter and the arrow share the same object ID (0x22) -- they are distinguished
by which routine they are in.

**Routine 0 (Init):** Sets up mappings, art tile, priority (3), display width ($10),
initial mapping frame (1), and advances to routine 2.

**Routine 2 (Main):** Checks if the player is within detection range. Calls
`Obj22_DetectPlayer` for both the main character and the sidekick:

```asm
Obj22_DetectPlayer:
    move.w  x_pos(a0),d0          ; shooter X
    sub.w   x_pos(a1),d0          ; subtract player X
    bcc.s   +                     ; if positive, skip negate
    neg.w   d0                    ; absolute value
+
    cmpi.w  #$40,d0               ; within 64 pixels?
    bhs.s   +                     ; no: skip
    moveq   #1,d2                 ; yes: set detection flag
+
    rts
```

If a player is detected, animation switches to "detecting" (anim 1: toggling frames 1-2).
If the player leaves detection range while in detecting mode, animation switches to
"firing" (anim 2).

**Routine 4 (ShootArrow):** Allocates a new object slot, copies the shooter's properties
into it, sets the new object to routine 6 (arrow init), plays the Pre-Arrow Firing sound
($DB), then returns the shooter itself to routine 2.

**Routine 6 (Arrow_Init):** Sets collision to $9B (hurts player), velocity to $400 (4
pixels/frame, negated if facing left), plays the Arrow Firing sound ($AE), advances to
routine 8.

**Routine 8 (Arrow):** Moves the arrow each frame, checks for wall collision. If the
arrow hits a wall (negative distance from `ObjCheckLeftWallDist` or
`ObjCheckRightWallDist`), it is deleted. Otherwise, `MarkObjGone` handles off-screen
cleanup.

The animation script at `Ani_obj22`:
```asm
byte_idle:   dc.b $1F, 1, $FF               ; delay 31, frame 1, loop
byte_detect: dc.b $03, 1, 2, $FF            ; delay 3, frames 1-2, loop
byte_fire:   dc.b $07, 3, 4, $FC, 4, 3, 1, $FD, 0  ; firing sequence with callbacks
```

### Step 2: Find It in the Engine

The engine splits this single disassembly object into **two classes**, because the
shooter and the arrow are logically independent entities:

- `ArrowShooterObjectInstance.java` -- The stationary shooter (routines 0-4)
- `ArrowProjectileInstance.java` -- The fired arrow (routines 6-8)

**Finding the classes:** Open the Sonic 2 object registry at
`src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistry.java` and search for
`0x22` or `ARROW_SHOOTER`:

```
registerFactory(Sonic2ObjectIds.ARROW_SHOOTER,
        (spawn, registry) -> new ArrowShooterObjectInstance(spawn,
                registry.getPrimaryName(spawn.objectId())));
```

This tells you: when the engine encounters object ID 0x22 in the level data, it creates
an `ArrowShooterObjectInstance`.

**The shooter:** `ArrowShooterObjectInstance.java` implements:
- Detection: compares player X to shooter X, threshold of `0x40` (64 pixels) -- matching
  the `cmpi.w #$40,d0` in the ASM.
- Animation states: idle (anim 0), detecting (anim 1), firing (anim 2) -- matching the
  three entries in `Ani_obj22`.
- Arrow spawning: creates an `ArrowProjectileInstance` and adds it to the object manager,
  then plays the Pre-Arrow Firing SFX -- matching routine 4's `AllocateObject` + sound.

**The arrow:** `ArrowProjectileInstance.java` implements:
- Velocity of `0x400` (4 pixels/frame) -- matching `move.w #$400,x_vel(a0)`.
- Collision flags `0x9B` -- matching `move.b #$9B,collision_flags(a0)`.
- Wall collision check using `ObjectTerrainUtils.checkLeftWallDist` /
  `checkRightWallDist` -- matching `ObjCheckLeftWallDist` / `ObjCheckRightWallDist`.
- Arrow Firing SFX ($AE) played on first update.

### Step 3: Verify

Compare specific constants between the disassembly and the engine:

| Value | Disassembly | Engine |
|-------|-------------|--------|
| Detection range | `cmpi.w #$40,d0` | `DETECTION_DISTANCE = 0x40` |
| Arrow velocity | `move.w #$400,x_vel(a0)` | `ARROW_VELOCITY = 0x400` |
| Collision flags | `move.b #$9B,collision_flags(a0)` | `COLLISION_FLAGS = 0x9B` |
| Shooter priority | `move.b #3,priority(a0)` | `PRIORITY = 3` |
| Arrow priority | `move.b #4,priority(a0)` | `PRIORITY = 4` |
| Pre-Arrow SFX | `SndID_PreArrowFiring` ($DB) | `Sonic2Sfx.PRE_ARROW_FIRING` (0xDB) |
| Arrow SFX | `SndID_ArrowFiring` ($AE) | `Sonic2Sfx.ARROW_FIRING` (0xAE) |
| Initial frame | `move.b #1,mapping_frame(a0)` | `animFrame = 1` |

### The Transferable Skill

For any object:
1. Find `Obj__` in the disassembly and read the routine dispatch table.
2. Search the game's `ObjectRegistry` for the hex ID to find the engine class.
3. Map each ASM routine to the corresponding engine method or state.
4. Compare constants (distances, velocities, collision flags, SFX IDs) to verify accuracy.

The biggest structural difference you will encounter is the engine splitting one
disassembly object into multiple classes when the original uses different routines for
logically separate entities (parent + child, shooter + projectile, spawner + spawned).

---

## Exercise 3: Tracing Art and Sprites

**Question:** Where does the engine get the ArrowShooter's graphics?

### Step 1: Find It in the Disassembly

In `Obj22_Init`, you can see:
```asm
    move.l  #Obj22_MapUnc_25804,mappings(a0)
    move.w  #make_art_tile(ArtTile_ArtNem_ArrowAndShooter,0,0),art_tile(a0)
```

This tells us:
- The sprite mappings are at label `Obj22_MapUnc_25804` (uncompressed, inline).
- The art tiles start at the VRAM tile index for `ArtTile_ArtNem_ArrowAndShooter`.

The art itself is Nemesis-compressed. Search for `ArtNem_ArrowAndShooter` to find the
BINCLUDE directive. The PLC (Pattern Load Cue) system loads this art into VRAM when
Aquatic Ruin Zone starts.

The mappings file at `mappings/sprite/obj22.asm` defines 5 frames:
- Frame 0: The arrow projectile (4x1 tiles, the arrow itself)
- Frame 1: The shooter idle (3x2 + 1x2 tiles, the stone column with face)
- Frame 2: The shooter with detected eye (adds a 1x1 eye tile to frame 1)
- Frame 3: The shooter open mouth variant A
- Frame 4: The shooter open mouth variant B

### Step 2: Find It in the Engine

The engine's art loading goes through several layers:

1. **PLC configuration:** `Sonic2Constants` defines PLC table entries. When ARZ loads,
   the zone's PLC entries tell the engine which art to decompress and where to place it
   in the pattern atlas.

2. **Art registry:** `Sonic2PlcArtRegistry` maps PLC entries to art keys. The
   ArrowShooter's art is registered under the key `Sonic2ObjectArtKeys.ARROW_SHOOTER`.

3. **Object rendering:** In `ArrowShooterObjectInstance.appendRenderCommands()`:
   ```
   PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.ARROW_SHOOTER);
   renderer.drawFrameIndex(frame, currentX, currentY, hFlip, false);
   ```
   The renderer looks up the mapping definition for the requested frame index, finds the
   tiles in the pattern atlas, and draws them.

### Step 3: Verify

Use RomOffsetFinder to confirm the art address:
```
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search ArrowAndShooter" -q
```

You can also use the `plc` command to inspect which PLC entry loads this art:
```
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="plc ARZ" -q
```

### The Transferable Skill

For any object's art:
1. Find the `art_tile` assignment in the object's init routine to identify the art label.
2. Find the PLC entry that loads that art (search for the art label in PLC tables).
3. In the engine, search `*PlcArtRegistry` or `*ObjectArtKeys` for the corresponding key.
4. Use RomOffsetFinder's `search` and `plc` commands to verify addresses.

---

## Exercise 4: Tracing Audio

**Question:** Where does the engine find the Pre-Arrow Firing sound effect?

### Step 1: Find It in the Disassembly

In `s2.constants.asm`, search for `PreArrowFiring`:
```asm
SndID_PreArrowFiring = id(SndPtr_PreArrowFiring)    ; DB
```

This tells us the sound ID is $DB. The `id()` macro calculates the ID from the sound's
position in the SFX pointer table. `SndPtr_PreArrowFiring` is the label of the pointer
table entry that points to the SMPS data for this sound.

In the object code:
```asm
    move.w  #SndID_PreArrowFiring,d0
    jsr     (PlaySound).l
```

### Step 2: Find It in the Engine

Open `src/main/java/com/openggf/game/sonic2/audio/Sonic2Sfx.java`:
```
PRE_ARROW_FIRING(0xDB, "Pre-Arrow Firing"),
```

The engine's `Sonic2Sfx` enum maps the same ID ($DB) to a named constant. When the
ArrowShooter fires, it calls:
```
services().playSfx(Sonic2Sfx.PRE_ARROW_FIRING.id);
```

This goes through the audio system:
1. `Sonic2SmpsConstants` holds the SFX pointer table address in the ROM.
2. `Sonic2SmpsLoader` reads the pointer at index ($DB - SFX base ID) to find the SMPS data.
3. `SmpsSequencer` plays the sound using the parsed SMPS header and sequence data.

### Step 3: Verify

Use `search-rom` to find the SFX pointer table pattern in the ROM, or search the
constants file for the pointer table address:

```
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search SndPtr" -q
```

### The Transferable Skill

For any sound effect or music track:
1. Find the `SndID_` constant in the disassembly's constants file to get the hex ID.
2. Search the engine's SFX/music enum for the same hex value.
3. Trace through `SmpsConstants` to find the pointer table address, and through
   the game's `SmpsLoader` subclass to see how the data is parsed.

---

## Exercise 5: Investigating a Discrepancy

**Question:** I think the engine does something differently from the original. How do I
check?

This exercise does not have a fixed answer -- it is a method for investigating any
suspected accuracy issue.

### Step 1: Identify What the Disassembly Does

Read the relevant section of the disassembly carefully. Common pitfalls:

- **Macro expansion:** Some instructions in the disassembly are macros that expand to
  multiple real instructions. Check `Macros.asm` or the macro setup file if something
  looks unfamiliar.
- **Conditional assembly:** Some code is gated on revision flags (`rev02even`, `zoneOrderedTable`).
  Make sure you are reading the code that applies to the ROM revision the engine targets.
- **Shared subroutines:** An object might call `JmpTo_AnimateSprite`, `JmpTo_MarkObjGone`,
  or other shared routines. The behavior of these shared routines matters. Search for
  the `JmpTo_` target to find the actual implementation.
- **Context from callers:** The code you are reading may depend on register values set
  by a calling routine. Trace back to see what values are passed in.

### Step 2: Find the Engine Code

Use the methods from the previous exercises:
- For objects: find the class via the object registry.
- For physics: search in the `physics/` package.
- For level events: search the game's `*LevelEvent*.java` files.
- For audio: search the `audio/` package and the game's SMPS constants.

### Step 3: Run the Engine with Debug Overlays

Launch the engine and navigate to the area where you see the discrepancy. Use the
debug keys to get more information:

| Key | What it shows |
|-----|---------------|
| F1 | Debug text overlay: positions, speeds, state values |
| F4 | Sensor labels: collision sensor ray positions |
| F5 | Object labels: object names and positions |
| F6 | Camera bounds: current camera boundary rectangle |
| F7 | Player bounds: collision bounding box |
| D | Free-fly debug mode: move the camera freely |

The debug overlay (F1) shows the player's position, velocity, and ground angle, which
you can compare frame-by-frame against expected values from the disassembly.

### Step 4: Write or Run a Test

If you can reproduce the issue programmatically, write a test using the
`HeadlessTestFixture`:

1. Set up a level and place the player at the relevant position.
2. Step frames and assert specific values (position, velocity, state).
3. Compare against the expected values from the disassembly.

See [Testing](../contributing/testing.md) for details on the test framework.

### Step 5: Report the Discrepancy

Once you have confirmed a difference:

- **Known issues:** Check `docs/status/known-bugs.md` or `docs/S3K_KNOWN_DISCREPANCIES.md` to
  see if it is already documented.
- **New issue:** Open a GitHub issue describing the discrepancy, which game and zone
  it affects, the expected behavior (from the disassembly), and the observed behavior
  (from the engine). Include the disassembly line numbers and the engine source file
  if possible.

---

## Quick Reference: The Method Card

This table summarizes how to start tracing any type of feature. The left column is what
you want to find; the middle columns are where to look; the right column is how to verify.

| I want to find... | Disasm starting point | Engine starting point | Verification |
|-|-|-|-|
| Level layout data | `Off_Level`, `Level_` labels | `*Constants.java` -- `LEVEL_*` fields | `RomOffsetFinder verify` |
| Level chunks/blocks | `mappings/128x128/`, `map256/` | `*Constants.java`, loaded via `LevelResourcePlan` | Binary compare against disasm `.bin` |
| Object behavior | `Obj__:` routine label | `*ObjectRegistry` -- search for hex ID | Compare constants and state machine |
| Object art (tiles) | `ArtNem_`, `Nem_` labels | `*PlcArtRegistry` or `*ObjectArtKeys` | `RomOffsetFinder test <offset> nem` |
| Sprite mappings | `mappings/sprite/obj__.asm` | Parsed at load time, drawn via `PatternSpriteRenderer` | Frame count and piece layout comparison |
| Collision data | `collision/` or `collide/` dirs | `*Constants.COLLISION_*` fields | Binary compare against disasm `.bin` |
| Music or SFX | `sound/` dir, `SndID_` constants | `*SmpsConstants.java`, `*Sfx.java` | `search-rom` for pointer table bytes |
| Palettes | `Pal_` labels | `*Constants.PALETTE_*` fields | Read 32 bytes at ROM offset, compare |
| Level events | `LevEvents_` labels | `*LevelEvent.java` per-zone classes | Compare camera trigger thresholds |
| Animation scripts | `Ani_obj__` inline data | Loaded from ROM or coded in object class | Compare frame sequences and commands |
| Player physics | `Sonic_Move`, `Sonic_Jump` etc. | `physics/` package | Step-frame position/velocity comparison |
| Water system | `Water_` labels, `WaterHeight` | `*WaterDataProvider`, `DynamicWaterHandler` | Compare water heights and palettes |

## Next Steps

- [Architecture Overview](architecture-overview.md) -- Understand the codebase layout
- [Tooling](tooling.md) -- Detailed RomOffsetFinder reference
- [Per-Game Notes](per-game-notes.md) -- S1/S2/S3K specific differences
- [Tutorial: Implement an Object](../contributing/tutorial-implement-object.md) -- Go
  from reading to writing
