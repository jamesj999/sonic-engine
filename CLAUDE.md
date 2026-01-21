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
# Search for items (by label or filename) - includes calculated ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search SpecialStars" -q

# List all files of a compression type (nem, kos, eni, sax, bin)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="list nem" -q

# Test decompression at a ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0xDD8CE nem" -q

# Auto-detect compression type at offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 auto" -q

# Verify a calculated offset against actual ROM data
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify ArtNem_SpecialHUD" -q

# Batch verify all items of a type (shows [OK], [!!] mismatch, [??] not found)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify-batch nem" -q

# Export verified offsets as Java constants
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="export nem ART_" -q
```

The `search` command automatically calculates ROM offsets using known anchor points from `RomOffsetCalculator.ANCHOR_OFFSETS`. Verified offsets are added as runtime anchors to improve accuracy for nearby items.

### CLI Commands

| Command | Description |
|---------|-------------|
| `search <pattern>` | Search for items by label/filename |
| `find <label> [offset]` | Find ROM offset by decompression search |
| `test <offset> <type>` | Test decompression at offset |
| `list [type]` | List all includes (optionally by type) |
| `verify <label>` | Verify calculated offset against ROM |
| `verify-batch [type]` | Batch verify all/filtered items |
| `export <type> [prefix]` | Export verified offsets as Java constants |

### Compression Types
| Type | Extension | CLI Arg |
|------|-----------|---------|
| Nemesis | `.nem` | `nem` |
| Kosinski | `.kos` | `kos` |
| Enigma | `.eni` | `eni` |
| Saxman | `.sax` | `sax` |
| Uncompressed | `.bin` | `bin` |

### Palette Macro Support

The tool parses `palette` macro lines from the disassembly:
```assembly
Pal_SS: palette Special Stage Main.bin ; comment
```
Search for palettes with `search Pal_SS`. They appear as `art/palettes/` paths with `Uncompressed` type.

### Programmatic Usage

```java
// Search the disassembly
DisassemblySearchTool searchTool = new DisassemblySearchTool("docs/s2disasm");
List<DisassemblySearchResult> results = searchTool.search("Ring");

// Test decompression at a ROM offset
CompressionTestTool testTool = new CompressionTestTool("path/to/rom.gen");
CompressionTestResult result = testTool.testDecompression(0x3000, CompressionType.NEMESIS);

// Verify and export offsets
RomOffsetFinder finder = new RomOffsetFinder("docs/s2disasm", "rom.gen");
VerificationResult vr = finder.verify("ArtNem_SpecialHUD");
List<VerificationResult> batch = finder.verifyBatch(CompressionType.NEMESIS);

// Export as Java constants
ConstantsExporter exporter = new ConstantsExporter();
exporter.exportAsJavaConstants(batch, "ART_", writer);
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
| `level.objects` | Game object management, rendering, factories |
| `audio` | SMPS driver, YM2612/PSG chip emulation |
| `data` | ROM loading, decompression (Kosinski, Nemesis, Saxman) |
| `game` | Core game-agnostic interfaces and providers |
| `game.sonic2` | Sonic 2-specific implementations |
| `game.sonic2.objects` | Object factories and instance classes |
| `game.sonic2.objects.badniks` | Badnik AI implementations |
| `game.sonic2.scroll` | Zone-specific parallax scroll handlers |
| `game.sonic2.constants` | ROM offsets, object IDs, audio constants |
| `tools` | Compression utilities (KosinskiReader, etc.) |

### Terminology (differs from standard Sonic 2 naming)
- **Pattern** - 8x8 pixel tile
- **Chunk** - 16x16 pixel tile (composed of Patterns)
- **Block** - 128x128 pixel area (composed of Chunks)

### Configuration
`SonicConfigurationService` loads from `config.json`. Key settings:
- `DEBUG_VIEW_ENABLED` - Overlays sensor/collision info (default: true)
- `DEBUG_MODE_KEY` - Key to toggle debug movement mode (default: 68 = 'D' key). When active, Sonic can fly freely with arrow keys, ignoring collision/physics.
- `AUDIO_ENABLED` - Sound on/off
- `ROM_FILENAME` - ROM path

## Level Resource Overlay System

Some Sonic 2 zones share level resources with overlays applied to customize the graphics. The most notable example is **Hill Top Zone (HTZ)**, which shares base data with **Emerald Hill Zone (EHZ)** and applies HTZ-specific overlays.

### HTZ/EHZ Resource Composition

From the s2disasm `SonLVL.ini`:
```ini
[Hill Top Zone Act 1/2]
tiles=../art/kosinski/EHZ_HTZ.bin|../art/kosinski/HTZ_Supp.bin:0x3F80
blocks=../mappings/16x16/EHZ.bin|../mappings/16x16/HTZ.bin:0x980
chunks=../mappings/128x128/EHZ_HTZ.bin
colind1=../collision/EHZ and HTZ primary 16x16 collision index.bin
colind2=../collision/EHZ and HTZ secondary 16x16 collision index.bin
```

**What the overlays do:**
- **Patterns (8×8 tiles):** Base EHZ_HTZ data is loaded, then HTZ_Supp replaces tiles starting at byte offset `0x3F80` (tile index 0x01FC). This replaces EHZ palm tree foreground tiles with HTZ fir trees.
- **Blocks (16×16 mappings):** Base EHZ blocks are loaded, then HTZ blocks overwrite starting at byte offset `0x0980` (block index 0x0130).
- **Chunks and Collision:** Fully shared between EHZ and HTZ (no overlay needed).

### ROM Addresses (Rev01)

| Resource | ROM Address | Notes |
|----------|-------------|-------|
| Base patterns (EHZ_HTZ) | `0x095C24` | Kosinski compressed |
| HTZ supplement patterns | `0x098AB4` | Overlay at +0x3F80 bytes |
| Base blocks (EHZ) | `0x094E74` | Kosinski compressed |
| HTZ supplement blocks | `0x0985A4` | Overlay at +0x0980 bytes |
| Shared chunks (EHZ_HTZ) | `0x099D34` | Kosinski compressed |
| Primary collision | `0x044E50` | Shared EHZ/HTZ |
| Secondary collision | `0x044F40` | Shared EHZ/HTZ |

### Implementation

The overlay system is implemented in the `uk.co.jamesj999.sonic.level.resources` package:

| Class | Purpose |
|-------|---------|
| `LoadOp` | Describes a single load operation (ROM address, compression, dest offset) |
| `LevelResourcePlan` | Holds lists of LoadOps for patterns, blocks, chunks, collision |
| `ResourceLoader` | Performs the actual loading with overlay composition |
| `Sonic2LevelResourcePlans` | Factory for zone-specific resource plans |

**Key points:**
- Each `LoadOp` specifies a `destOffsetBytes` (0 for base, non-zero for overlays)
- `ResourceLoader.loadWithOverlays()` allocates a fresh buffer, loads base, then applies overlays
- Overlays never mutate cached data (copy-on-write pattern)
- `Sonic2.loadLevel()` checks for custom plans via `Sonic2LevelResourcePlans.getPlanForZone()`

### Adding Similar Overlay Support for Other Zones

If another zone requires overlay-based loading:

1. Add ROM offset constants to `Sonic2Constants.java`
2. Create a plan factory method in `Sonic2LevelResourcePlans.java`
3. Update `getPlanForZone()` to return the plan for that zone ID
4. Write tests in `LevelResourceOverlayTest.java`

## Multi-Game Support Architecture

The engine supports multiple Sonic games (Sonic 1, Sonic 2, Sonic 3&K) through a provider-based abstraction layer. Game-specific behavior is isolated behind interfaces, allowing the engine core to remain game-agnostic.

### Core Components

| Class/Interface | Purpose |
|-----------------|---------|
| `GameModule` | Central interface defining all game-specific providers |
| `GameModuleRegistry` | Singleton holding the current game module |
| `RomDetectionService` | Auto-detects ROM type and sets appropriate module |
| `RomDetector` | Interface for game-specific ROM detection logic |

### GameModule Interface

The `GameModule` interface is the entry point for all game-specific functionality:

```java
// Access the current game module
GameModule module = GameModuleRegistry.getCurrent();

// Get game-specific providers
ObjectRegistry objects = module.createObjectRegistry();
ZoneRegistry zones = module.getZoneRegistry();
SpecialStageProvider specialStage = module.getSpecialStageProvider();
ScrollHandlerProvider scroll = module.getScrollHandlerProvider();
```

### Provider Interfaces

| Provider | Purpose |
|----------|---------|
| `ZoneRegistry` | Zone/level metadata (names, act counts, start positions) |
| `ObjectRegistry` | Object creation factories and ID mappings |
| `SpecialStageProvider` | Chaos Emerald special stage logic |
| `BonusStageProvider` | Checkpoint bonus stage logic (S3K) |
| `ScrollHandlerProvider` | Per-zone parallax scroll handlers |
| `ZoneFeatureProvider` | Zone-specific mechanics (CNZ bumpers, water) |
| `RomOffsetProvider` | Type-safe ROM address access |
| `LevelEventProvider` | Dynamic camera boundaries, boss arenas |
| `TitleCardProvider` | Zone/act title card rendering |
| `DebugModeProvider` | Game-specific debug features |

### ROM Auto-Detection

The engine automatically detects the loaded ROM and configures the appropriate game module:

```java
// Automatic detection (called during ROM load)
GameModuleRegistry.detectAndSetModule(rom);

// Manual module setting
GameModuleRegistry.setCurrent(new Sonic2GameModule());
```

Detection is performed by `RomDetector` implementations registered with `RomDetectionService`. Each detector examines ROM headers/checksums to identify its game.

## Object & Badnik System

Game objects (springs, monitors, badniks, platforms) use a factory pattern with game-specific registries.

### Object Registration

```java
// ObjectRegistry interface
ObjectInstance create(ObjectSpawn spawn);
String getPrimaryName(int objectId);

// ObjectFactory functional interface
ObjectInstance create(ObjectSpawn spawn, ObjectRegistry registry);
```

Objects are registered in `Sonic2ObjectRegistry.registerDefaultFactories()`:

```java
registerFactory(Sonic2ObjectIds.SPRING,
    (spawn, registry) -> new SpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
```

### Key Object Classes

| Class | Purpose |
|-------|---------|
| `Sonic2ObjectRegistry` | Factory registry for Sonic 2 objects |
| `Sonic2ObjectRegistryData` | Static name mappings for object IDs |
| `AbstractObjectInstance` | Base class for all game objects |
| `PlaceholderObjectInstance` | Fallback for unimplemented objects |

### Badnik System

Badniks (enemies) extend `AbstractBadnikInstance` which provides:
- Common collision handling via `TouchResponseProvider`
- Destruction behavior (explosion, animal spawn, points)
- Movement/animation framework

```java
public abstract class AbstractBadnikInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable {

    // Subclasses implement AI logic
    protected abstract void updateMovement(int frameCounter, AbstractPlayableSprite player);

    // Collision size for touch response
    protected abstract int getCollisionSizeIndex();
}
```

Example badniks: `MasherBadnikInstance`, `BuzzerBadnikInstance`, `CoconutsBadnikInstance`

### Adding New Objects

1. Add the object ID to `Sonic2ObjectIds.java`
2. Create an instance class extending `AbstractObjectInstance` (or `AbstractBadnikInstance` for enemies)
3. Register the factory in `Sonic2ObjectRegistry.registerDefaultFactories()`
4. Add collision data to `Sonic2ObjectConstants.java` if needed

## Game-Specific Art Loading Pattern

**Important:** Keep `ObjectArtData` game-agnostic. Game-specific sprite sheets (badniks, zone-specific objects) should be loaded through the game-specific provider, not added to `ObjectArtData`.

### Architecture

| Class | Purpose |
|-------|---------|
| `ObjectArtData` | Game-agnostic art data (monitors, springs, spikes, etc.) |
| `Sonic2ObjectArt` | Sonic 2-specific art loader with public loader methods |
| `Sonic2ObjectArtProvider` | Registers sheets and provides key-based access |
| `Sonic2ObjectArtKeys` | String keys for Sonic 2-specific art |

### Adding Game-Specific Art (e.g., new Badnik)

1. **Add ROM address** to `Sonic2Constants.java`:
   ```java
   public static final int ART_NEM_NEWBADNIK_ADDR = 0x89B9A;
   ```

2. **Add art key** to `Sonic2ObjectArtKeys.java`:
   ```java
   public static final String NEW_BADNIK = "newbadnik";
   ```

3. **Add loader method** to `Sonic2ObjectArt.java`:
   ```java
   public ObjectSpriteSheet loadNewBadnikSheet() {
       Pattern[] patterns = safeLoadNemesisPatterns(
           Sonic2Constants.ART_NEM_NEWBADNIK_ADDR, "NewBadnik");
       if (patterns.length == 0) {
           return null;
       }
       List<SpriteMappingFrame> mappings = createNewBadnikMappings();
       return new ObjectSpriteSheet(patterns, mappings, paletteIndex, 1);
   }
   ```

4. **Add mappings method** to `Sonic2ObjectArt.java`:
   ```java
   private List<SpriteMappingFrame> createNewBadnikMappings() {
       List<SpriteMappingFrame> frames = new ArrayList<>();
       // Add SpriteMappingPiece for each frame
       return frames;
   }
   ```

5. **Register in provider** `Sonic2ObjectArtProvider.loadArtForZone()`:
   ```java
   registerSheet(Sonic2ObjectArtKeys.NEW_BADNIK, artLoader.loadNewBadnikSheet());
   ```

### DO NOT add to ObjectArtData

The following should NOT be added to `ObjectArtData`:
- Badnik/enemy sprites (Masher, Buzzer, ChopChop, etc.)
- Zone-specific object sprites
- Game-specific decorative elements

These should use the loader method pattern above, keeping `ObjectArtData` focused on common objects that could be shared across game implementations.

## Constants Files

Game-specific constants are organized in the `game.sonic2.constants` package:

| File | Contents |
|------|----------|
| `Sonic2Constants.java` | Primary ROM offsets (level data, palettes, collision) |
| `Sonic2ObjectIds.java` | Object type IDs (0x41 = Spring, 0x26 = Monitor, etc.) |
| `Sonic2ObjectConstants.java` | Touch collision table address and size data |
| `Sonic2AnimationIds.java` | Animation script IDs for player sprites |
| `Sonic2AudioConstants.java` | Music and SFX IDs |

## Adding New Game Support

To add support for a new game (e.g., Sonic 1):

1. **Create the GameModule implementation**
   ```java
   public class Sonic1GameModule implements GameModule {
       // Implement all provider methods
   }
   ```

2. **Create a RomDetector**
   ```java
   public class Sonic1RomDetector implements RomDetector {
       public boolean canHandle(Rom rom) {
           // Check ROM header for "SONIC THE HEDGEHOG"
       }
       public GameModule createModule() {
           return new Sonic1GameModule();
       }
   }
   ```

3. **Implement required providers**
   - `ZoneRegistry` - Zone names, act counts, start positions
   - `ObjectRegistry` - Object factories for game-specific objects
   - Audio profile with correct SFX/music mappings

4. **Register the detector**
   Add to `RomDetectionService.registerBuiltInDetectors()`:
   ```java
   Class<?> sonic1DetectorClass = Class.forName(
       "uk.co.jamesj999.sonic.game.sonic1.Sonic1RomDetector");
   ```

Optional providers can return `null` if the game doesn't use that feature (e.g., `getBonusStageProvider()` for Sonic 2).

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

### Player Sprite Coordinates

**Critical:** The original Sonic 2 ROM uses **center coordinates** for player position (`x_pos`, `y_pos`), not top-left corner. When implementing object interactions or collision checks:

| Method | Returns | Use Case |
|--------|---------|----------|
| `player.getX()` / `player.getY()` | Top-left corner of sprite bounding box | Rendering, bounding box calculations |
| `player.getCentreX()` / `player.getCentreY()` | Center of sprite (matches ROM `x_pos`/`y_pos`) | **Object interactions, collision checks** |

**Always use `getCentreX()`/`getCentreY()` for object interactions** to match original ROM behavior. Using `getX()`/`getY()` creates a vertical offset of ~19 pixels (half player height), causing incorrect collision detection (e.g., triggering checkpoints when running through loops beneath them).

Example from disassembly (`s2.asm`):
```assembly
move.w  x_pos(a3),d0        ; player CENTER X
sub.w   x_pos(a0),d0        ; object X
; ... collision check uses center-to-center delta
```

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

### VDP Sprite Coordinate Offset (Disassembly Only)

When reading sprite coordinates from the Sonic 2 disassembly, be aware that the VDP hardware adds 128 to both X and Y coordinates. This allows sprites to be positioned partially off-screen (a sprite at VDP X=0 is 128 pixels left of the visible area).

**This only matters when interpreting raw values from the disassembly.** Our Java engine uses direct screen coordinates (0,0 = top-left visible pixel).

Example from `s2.asm`:
```asm
; The results_screen_object macro adds 128 automatically:
results_screen_object macro startx, targetx, y, routine, frame
    dc.w    128+startx, 128+targetx, 128+y
    ...

; But direct VDP writes don't:
move.w  #$B4,y_pixel(a1)  ; $B4 = 180 in VDP space = 52 in screen space
```

To convert: **screen_position = vdp_value - 128**

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
