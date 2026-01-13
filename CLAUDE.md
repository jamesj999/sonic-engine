# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Java-based Sonic the Hedgehog game engine that faithfully recreates the original Mega Drive/Genesis physics. It loads game data from the original Sonic 2 ROM and aims for pixel-perfect gameplay recreation.

**Critical requirement:** The engine must replicate original physics pixel-for-pixel. Accuracy is paramount.

## Build & Run Commands

```bash
# Build (creates executable JAR with dependencies)
mvn package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=TestCollisionLogic

# Run the engine (requires ROM file)
java -jar target/sonic-engine-0.1.20260110-jar-with-dependencies.jar
```

## ROM Requirement

The engine requires `Sonic The Hedgehog 2 (W) (REV01) [!].gen` in the working directory. This file is gitignored. For development/testing, it can be downloaded from: `http://bluetoaster.net/secretfolder/Sonic%20The%20Hedgehog%202%20%28W%29%20%28REV01%29%20%5B!%5D.gen`

`TestRomLogic` is intentionally skipped when the ROM is absent.

## Reference Materials

- **`docs/s2disasm/`** - Sonic 2 disassembly (68000 assembly). Use this to understand original game logic, ROM addresses, and behavior. Essential for accuracy verification.
- **`docs/SMPS-rips/SMPSPlay/`** - SMPS audio driver source and reference implementations
- **`docs/s2ssedit-0.2.0/`** - Special stage editor source code

These directories are untracked (not in git) but available locally.

## ROM Offset Finder Tool

If `docs/s2disasm` is present, use the **RomOffsetFinder** tool to search for disassembly items and find their ROM offsets. This streamlines finding offsets for items defined in the disassembly.

### Quick Reference

```bash
# Search for items (by label or filename)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search ring" -q

# List all files of a compression type (nem, kos, eni, sax)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="list nem" -q

# Test decompression at a ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 nem" -q

# Auto-detect compression type at offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 auto" -q
```

### Compression Types
| Type | Extension | CLI Arg |
|------|-----------|---------|
| Nemesis | `.nem` | `nem` |
| Kosinski | `.kos` | `kos` |
| Enigma | `.eni` | `eni` |
| Saxman | `.sax` | `sax` |

### Programmatic Usage

```java
// Search the disassembly
DisassemblySearchTool searchTool = new DisassemblySearchTool("docs/s2disasm");
List<DisassemblySearchResult> results = searchTool.search("Ring");

// Test decompression at a ROM offset
CompressionTestTool testTool = new CompressionTestTool("path/to/rom.gen");
CompressionTestResult result = testTool.testDecompression(0x3000, CompressionType.NEMESIS);
```

See `uk.co.jamesj999.sonic.tools.disasm` package for full API.

## Architecture

### Entry Point
`uk.co.jamesj999.sonic.Engine` - OpenGL canvas with FPSAnimator. Creates the game loop via `display()` → `update()` → `draw()`.

### Core Managers (Singleton Pattern)
The codebase uses singletons extensively via `getInstance()`:
- **LevelManager** - Level loading, rendering, zone/act management
- **SpriteManager** - All game sprites (player, objects, enemies)
- **SpriteCollisionManager** - Collision detection between sprites
- **GraphicsManager** - OpenGL rendering, shader management
- **AudioManager** - SMPS audio driver, YM2612/PSG synthesis
- **GameStateManager** - Score, lives, emerald tracking
- **Camera** - Camera position tracking, following player

### Key Packages
| Package | Purpose |
|---------|---------|
| `sprites.playable` | Sonic/Tails player logic, physics |
| `physics` | Terrain collision, sensors |
| `level` | Level structures, rendering, scrolling |
| `level.objects` | Game object management and rendering |
| `audio` | SMPS driver, YM2612/PSG chip emulation |
| `data` | ROM loading, decompression (Kosinski, Nemesis, Saxman) |
| `game.sonic2` | Sonic 2-specific implementations |
| `tools` | Compression utilities (KosinskiReader, etc.) |

### Terminology (differs from standard Sonic 2 naming)
- **Pattern** - 8x8 pixel tile
- **Chunk** - 16x16 pixel tile (composed of Patterns)
- **Block** - 128x128 pixel area (composed of Chunks)

### Configuration
`SonicConfigurationService` loads from `config.json`. Key settings:
- `DEBUG_VIEW_ENABLED` - Overlays sensor/collision info (default: true)
- `DEBUG_MODE` - Enables debug movement controls
- `AUDIO_ENABLED` - Sound on/off
- `ROM_FILENAME` - ROM path

## Audio Engine

The audio system emulates the Mega Drive sound hardware:
- **YM2612** - FM synthesis chip (6 channels)
- **PSG (SN76489)** - Square wave + noise (4 channels)
- **SMPS Driver** - Sega's sound driver format

Reference implementations in `docs/SMPS-rips/SMPSPlay/libs/download/libvgm/emu/cores/` contain high-accuracy source code.

**Important:** Strive for hardware accuracy. Reference libvgm cores and SMPSPlay source rather than implementing simplified versions.

## Branch Naming Convention

- `feature/ai-*` - New features
- `bugfix/ai-*` - Bug fixes

## Code Style Notes

- Keep logic in manager classes, not in `Engine.java`
- Ensure source files end with a newline
- Uses Java 21 features

## Coordinate System & Rendering

### Y-Axis Convention
The engine uses Mega Drive/Genesis screen coordinates internally where **Y increases downward** (Y=0 at top of screen). OpenGL uses the opposite convention (Y=0 at bottom), so the `BatchedPatternRenderer` flips the Y coordinate during rendering:
```java
int screenY = screenHeight - y;  // BatchedPatternRenderer.java:94
```

When working with screen positions, always use the Mega Drive convention (Y down = positive). The graphics layer handles the OpenGL conversion automatically.

### Sprite Tile Ordering
Mega Drive VDP sprites use **column-major** tile ordering, not row-major. For a sprite piece that is W tiles wide and H tiles tall:
```java
// Column-major: tiles go top-to-bottom within each column, then left-to-right
int tileIndex = column * heightTiles + row;
```

When a sprite is horizontally flipped, the VDP draws tiles from the **last column first**. When vertically flipped, it draws from the **bottom row first**. The flip flags also flip each individual tile's pixels.

Example for a 3x2 tile sprite (width=3, height=2):
```
Normal order:     H-Flipped order:
[0][2][4]         [4][2][0]
[1][3][5]         [5][3][1]
```

## Sonic 2 Special Stage Implementation

The special stage uses a unique pseudo-3D rendering system. Key files are in `uk.co.jamesj999.sonic.game.sonic2.specialstage`.

### Key Classes

| Class | Purpose |
|-------|---------|
| `Sonic2SpecialStageManager` | Main manager, coordinates all special stage systems |
| `Sonic2SpecialStageDataLoader` | Loads and decompresses data from ROM |
| `Sonic2TrackAnimator` | Manages segment sequencing and animation timing |
| `Sonic2TrackFrameDecoder` | Decodes track frame bitstream into VDP tiles |
| `Sonic2SpecialStageConstants` | ROM offsets and segment type constants |

### Track Frame Format

Each of the 56 track frames is a compressed bitstream with 3 segments:
1. **Bitflags** - 1 bit per tile: 0 = RLE (fill), 1 = UNC (unique)
2. **UNC LUT** - Lookup table for unique tiles (variable length)
3. **RLE LUT** - Lookup table for fill tiles (variable length)

The decoder reads the bitflags to determine which LUT to use for each tile. Only UNC tiles get their `flip_x` bit (0x0800) toggled when the track is flipped.

The VDP plane is 128 cells wide, displayed as 4 strips of 32 tiles via H-scroll interleaving. When flipping, tiles are reversed within each 32-tile strip, not the entire 128-tile row.

### Segment Types and Animations

| Type | Name | Frames | Description |
|------|------|--------|-------------|
| 0 | TURN_THEN_RISE | 24 | Turn (0x26-0x2B) + Rise (0x00-0x10) |
| 1 | TURN_THEN_DROP | 24 | Turn (0x26-0x2B) + Drop (0x15-0x25) |
| 2 | TURN_THEN_STRAIGHT | 12 | Turn (0x26-0x2B) + Exit curve (0x2C-0x30) |
| 3 | STRAIGHT | 16 | Straight frames (0x11-0x14) repeated 4x |
| 4 | STRAIGHT_THEN_TURN | 11 | Straight (0x11-0x14) + Enter curve (0x31-0x37) |

### Layout Data Format

The layout is Nemesis compressed. Decompressed format:
- **Offset table**: 7 words (14 bytes) - one offset per stage
- **Layout bytes**: Each byte defines a segment
  - Bits 0-6: Segment type (0-4)
  - Bit 7 (0x80): Flip flag (left turn vs right turn)

The layout does NOT loop - the stage ends at checkpoint 3 (4th checkpoint) before reaching the end. Checkpoints are defined in separate object location data, not in the layout.

### Orientation/Flip System

The original game maintains a persistent `SSTrack_Orientation` state that only updates at specific **trigger frames**:
- **0x12** (Straight frame 2)
- **0x0E** (Rise frame 14)
- **0x1A** (Drop frame 6)

At trigger frames, orientation is set to the **current segment's** flip bit. Between triggers, orientation persists unchanged.

This means:
- **STRAIGHT_THEN_TURN**: Flip flag is on THIS segment. Entry curve frames use current segment's flip.
- **TURN_THEN_STRAIGHT**: Uses PREVIOUS segment's flip (continues the turn).
- **TURN_THEN_RISE/DROP**: Orientation updates at rise frame 14 or drop frame 6.

### Stage Progression

- 4 checkpoints per stage (acts 0-3)
- At checkpoint 3, `SS_Check_Rings_flag` triggers stage end
- Ring requirements are checked at each checkpoint
- Stage ends with emerald award or failure before layout runs out
