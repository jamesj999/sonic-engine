# Architecture Overview

This page provides a map of the engine's source code for readers who want to find where a
particular feature lives. It does not assume Java expertise -- just enough programming
background to navigate a directory tree and understand that files grouped together tend to
be related.

## Top-Level Package Map

All engine source lives under `src/main/java/com/openggf/`. Here is what each directory
contains:

```
com.openggf/
  Engine.java              Application entry point. Creates the window, initialises
                           the graphics pipeline, and starts the main loop.

  GameLoop.java            Per-frame orchestration. Calls input handling, game logic
                           updates, and rendering in sequence each frame.

  game/                    THE GAME MODULE SYSTEM. This is where game-specific behavior
                           lives. Each supported game has a subdirectory.
    GameModule.java        The interface that each game implements. Returns providers
                           for objects, physics, events, art, audio, etc.
    sonic1/                Everything specific to Sonic 1.
    sonic2/                Everything specific to Sonic 2.
    sonic3k/               Everything specific to Sonic 3 & Knuckles.

  level/                   LEVEL INFRASTRUCTURE. Loading layouts, managing the tile grid,
                           spawning objects.
    LevelManager.java      The active level: tile grid, camera boundaries, object list.
    objects/               Object system: base classes, registry interface, spawn records,
                           object manager.

  physics/                 PHYSICS AND COLLISION. Terrain sensors, slope handling,
                           floor/wall/ceiling checks, solid object interaction.

  sprites/                 SPRITE SYSTEM. Player character classes, animation controller,
                           sprite art sets.

  graphics/                GPU RENDERING. Shaders, pattern atlas, tilemap renderer,
                           render commands, FBO compositing.

  audio/                   SOUND SYSTEM. SMPS sequencer, YM2612 FM synthesis, SN76489 PSG,
                           DAC sample playback.

  camera/                  CAMERA. Position tracking, scroll boundaries, screen shake.

  data/                    ROM ACCESS. Binary reading, decompression algorithms (Kosinski,
                           Nemesis, Enigma, Saxman).

  debug/                   DEBUG OVERLAYS. Collision visualization, object labels, sensor
                           rays, state readouts.

  tools/                   OFFLINE TOOLS. RomOffsetFinder, ObjectDiscoveryTool. Not part
                           of the runtime engine.
```

## Inside a Game Module

Each game module (`sonic1/`, `sonic2/`, `sonic3k/`) follows the same internal structure:

```
sonic2/
  Sonic2GameModule.java    Wires everything together. Returns all providers.
  Sonic2.java              ROM parsing: reads level data tables, art addresses, etc.

  constants/               ROM addresses. Verified offsets into the ROM binary.
    Sonic2Constants.java   Main constants file (level data, PLC tables, etc.)
    Sonic2ObjectIds.java   Object ID hex constants (ARROW_SHOOTER = 0x22, etc.)
    Sonic2ObjectConstants.java  Object-specific addresses
    Sonic2AudioConstants.java   Audio pointer table addresses

  objects/                 Object implementations. One file per object type.
    Sonic2ObjectRegistry.java       Maps object IDs to factory functions.
    Sonic2ObjectRegistryData.java   Object ID -> name mappings.
    ArrowShooterObjectInstance.java An example object.
    badniks/               Badnik (enemy) implementations.
    bosses/                Boss implementations.

  audio/                   Game-specific audio configuration.
    Sonic2Sfx.java         SFX ID enum (maps hex IDs to names).
    Sonic2SmpsConstants.java  SMPS pointer table addresses.

  scroll/                  Parallax scroll handlers, one per zone.
  events/                  Level event classes, one per zone.
```

## Key Concepts for Cross-Referencers

### Constants Files = ROM Addresses

When you want to know "where does the engine read X from the ROM?", the constants file is
the first place to look. Every ROM address the engine uses is defined as a named constant:

```
LEVEL_LAYOUT_DIR_ADDR = 0x045A80
ART_LOAD_CUES_ADDR = 0x42660
```

### Object Registry = Object Pointer Table

The disassembly has an object pointer table that maps object IDs to code entry points.
The engine has an `ObjectRegistry` that maps object IDs to factory functions. To find
which class handles a given object ID, search the registry file.

### ObjectInstance = Object RAM Slot

In the original game, each active object occupies a 64-byte slot in object RAM, with fields
accessed by byte offset (`x_pos(a0)`, `routine(a0)`). In the engine, each active object is
an instance of a class with named fields. The `update()` method replaces the 68000 jumping
to the object's routine.

### ResourceLoader = Decompression

The `data/` package contains decompression implementations for every compression type used
by the games (Kosinski, Nemesis, Enigma, Saxman, KosinskiM). When the engine loads
compressed data from the ROM, it goes through `ResourceLoader` (in `level/resources/`),
which selects the correct decompressor based on the compression type.

### SmpsSequencer = Z80 Sound Driver

The Z80 sound driver is not emulated -- it is reimplemented. The `SmpsSequencer`
processes ROM SMPS sequence data and drives the YM2612 and SN76489 emulation
cores, but universal register-write parity is still a goal rather than a current
guarantee. In particular, S3K coordinate flags are owned by
`Sonic3kCoordFlagHandler`. Fixed-point traversal of every resolved S&K-loader
shipped-ROM music/SFX track entry and both native SFX banks (`33-DF`, 173
entries each) never reaches its `SND_CMD`, `MUS_PAUSE`, or `COPY_MEM` meta
commands. Strict full-bank traversal covers all native roots/frontiers,
including differing `9B`/`AD` payloads and `DC-DF` aliases; ROM type-check bytes
prove S&K's `DC` CreditsK music special case and S3's `DC-DF` SFX dispatch.
Their cases preserve operand alignment for custom/imported streams without
claiming native effects.
See the ROM inventory and source contract in
`docs/architecture/research/audio/2026-08-08-s3k-smps-meta-command-reachability.md`.

## Finding Things

| You want to find... | Look in... |
|---------------------|-----------|
| A specific object's behavior | `game/<game>/objects/` -- search for the object name or hex ID |
| A ROM address | `game/<game>/constants/` -- search for a keyword |
| How level data is loaded | `game/<game>/Sonic*.java` (the main game class) |
| How collision works | `physics/` -- `CollisionSystem`, `TerrainCollisionManager` |
| How rendering works | `graphics/` -- but you rarely need to touch this |
| How audio works | `audio/` -- `SmpsSequencer`, `Ym2612Chip`, `PsgChip` |
| How the camera moves | `camera/` |
| How player physics work | `physics/` and `sprites/playable/` |
| Zone-specific events | `game/<game>/events/` |
| Parallax backgrounds | `game/<game>/scroll/` |
| Debug overlay features | `debug/` |

## Next Steps

- [How the Engine Reads ROMs](how-the-engine-reads-roms.md) -- Data pipeline details
- [Mapping Exercises](mapping-exercises.md) -- Practice tracing features
- [Tooling](tooling.md) -- Built-in ROM exploration tools
