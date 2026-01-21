# Guidance for future AI agents

## Project Mission
This project is a faithful recreation of the **Sonic the Hedgehog** game engine in Java. It aims to:
1.  Use the original ROM data to render levels.
2.  Perfectly and precisely replicate the original physics. (This is IMPORTANT. The engine must recreate the original pixel-for-pixel)
3.  Eventually support user-made characters and level editing tools.

## Current Status
The project is in an **alpha** state. Core systems are functional with 291 passing tests.

### Rendering
*   **Status:** ✅ Functional.
*   **Level Data:** Patterns, chunks, and blocks load correctly from ROM via Kosinski decompression.
*   **Flipping Logic:** Horizontal/vertical flip implemented in `PatternRenderCommand`, `BatchedPatternRenderer`, and `SpritePieceRenderer`.
*   **Sprite Rendering:** Player sprites render with DPLC-based animation via `PlayerSpriteRenderer` and `SpritePieceRenderer`.
*   **Supported Zones:** EHZ, CPZ, ARZ have zone-specific scroll handlers.

### Decompression
*   **Status:** ✅ Complete.
*   All four formats implemented with tests: Nemesis, Kosinski, Enigma, Saxman.

### Camera
*   **Status:** ✅ ROM-accurate (core logic).
*   Deadzone following, spindash lag buffer.
*   **Per-zone parallax:** Incomplete – EHZ, CPZ, ARZ implemented; other zones need scroll handlers.

### Physics
*   **Status:** ⚠️ Functional (needs validation).
*   Ground speed model, slope handling, 360° sensor array (A-F), loop ground modes, springs.
*   **TODO:** Validate against original ROM behavior for accuracy.

### Audio
*   **Status:** ✅ Implemented (parity testing ongoing).
*   YM2612 FM synthesis, SN76489 PSG, SMPS driver/sequencer.
*   Reference: `docs/MUSIC_IMPLEMENTATION.md`, `docs/AudioParityPlan.md`.

### Suggested Tasks
1.  **Audio parity testing** – Compare output against SMPSPlay reference.
2.  **Additional zone support** – Implement scroll handlers for remaining zones.
3.  **Game objects** – Enemies, bosses, zone-specific gimmicks.
4.  **Title screen / menus** – Implement game flow beyond level play.
5.  **Special Stage completion** – Finish special stage mechanics and results screen.

## Agent Directives
1.  **Branching:** Always create pull requests from the same branch within a session. Use the following naming convention:
    *   `feature/ai-` for new features.
    *   `bugfix/ai-` for bug fixes.
2.  **Code Structure:** Keep logic within existing or new manager classes. Avoid putting all logic into `Engine.java` to maintain a strong object-oriented design.

## Key information
*   **Entry point:** `uk.co.jamesj999.sonic.Engine` (declared in the manifest). A `main` method creates an OpenGL canvas with an `FPSAnimator`.
*   **Build:** `mvn package`. Tests can be run with `mvn test` (JUnit 4).
*   **Run:** `java -jar target/sonic-engine-0.05-BETA-jar-with-dependencies.jar`.
*   **ROM Requirement:** A compatible Sonic 2 ROM named `Sonic The Hedgehog 2 (W) (REV01) [!].gen` is expected for level loading. Without it, level logic may fail at runtime. If the ROM is not present locally, it can be downloaded for debugging, coding, and testing from `http://bluetoaster.net/secretfolder/Sonic%20The%20Hedgehog%202%20%28W%29%20%28REV01%29%20%5B!%5D.gen`. The ROM is likely to be in .gitignore, so may be invisible to the agent.
*   **Important packages** under `src/main/java/uk/co/jamesj999/sonic`:
    *   `Control` – input handling
    *   `camera` – camera logic
    *   `configuration` – game settings via `SonicConfiguration` and `SonicConfigurationService`
    *   `data` – ROM loaders and game classes
    *   `debug` – debug overlay (`DebugRenderer`), enabled via the `DEBUG_VIEW_ENABLED` configuration flag
    *   `game` – core game-agnostic interfaces and providers
    *   `game.sonic2` – Sonic 2-specific implementations
    *   `game.sonic2.objects` – object factories and instance classes
    *   `game.sonic2.objects.badniks` – badnik AI implementations
    *   `game.sonic2.constants` – ROM offsets, object IDs, audio constants
    *   `graphics` – GL wrappers and render managers
    *   `level` – level structures (patterns, blocks, chunks, collision)
    *   `level.objects` – game object management, rendering, factories
    *   `physics` – sensors and terrain collision
    *   `sprites` – sprite classes, including playable character logic
    *   `timer` – utility timers for events
    *   `tools` – utilities such as `KosinskiReader` for decompressing Sega data
*   **Tests:** Live under `src/test/java/uk/co/jamesj999/sonic/tests` and cover ROM loading, decompression, and collision.

## Multi-Game Support Architecture

The engine supports multiple Sonic games (Sonic 1, Sonic 2, Sonic 3&K) through a provider-based abstraction layer.

### Core Components
| Class/Interface | Purpose |
|-----------------|---------|
| `GameModule` | Central interface defining all game-specific providers |
| `GameModuleRegistry` | Singleton holding the current game module |
| `RomDetectionService` | Auto-detects ROM type and sets appropriate module |
| `RomDetector` | Interface for game-specific ROM detection logic |

### Key Providers
| Provider | Purpose |
|----------|---------|
| `ZoneRegistry` | Zone/level metadata (names, act counts, start positions) |
| `ObjectRegistry` | Object creation factories and ID mappings |
| `SpecialStageProvider` | Chaos Emerald special stage logic |
| `BonusStageProvider` | Checkpoint bonus stage logic (S3K) |
| `ScrollHandlerProvider` | Per-zone parallax scroll handlers |
| `ZoneFeatureProvider` | Zone-specific mechanics (CNZ bumpers, water) |
| `RomOffsetProvider` | Type-safe ROM address access |

### Usage
```java
// Access current game module
GameModule module = GameModuleRegistry.getCurrent();
ObjectRegistry objects = module.createObjectRegistry();
ZoneRegistry zones = module.getZoneRegistry();

// Auto-detect ROM and set module
GameModuleRegistry.detectAndSetModule(rom);
```

## Object & Badnik System

Game objects use a factory pattern with game-specific registries.

### Key Classes
| Class | Purpose |
|-------|---------|
| `Sonic2ObjectRegistry` | Factory registry for Sonic 2 objects |
| `Sonic2ObjectRegistryData` | Static name mappings for object IDs |
| `AbstractBadnikInstance` | Base class for enemy AI with collision handling |
| `ObjectFactory` | Functional interface for object creation |

### Adding New Objects
1. Add object ID to `Sonic2ObjectIds.java`
2. Create instance class extending `AbstractObjectInstance` (or `AbstractBadnikInstance` for enemies)
3. Register factory in `Sonic2ObjectRegistry.registerDefaultFactories()`

### Game-Specific Art Loading

**Important:** Keep `ObjectArtData` game-agnostic. Game-specific art (badniks, zone objects) uses a provider pattern:

1. **Add ROM address** to `Sonic2Constants.java`
2. **Add art key** to `Sonic2ObjectArtKeys.java`
3. **Add public loader method** to `Sonic2ObjectArt.java`:
   ```java
   public ObjectSpriteSheet loadNewBadnikSheet() {
       Pattern[] patterns = safeLoadNemesisPatterns(ADDR, "Name");
       if (patterns.length == 0) return null;
       return new ObjectSpriteSheet(patterns, createMappings(), palette, 1);
   }
   ```
4. **Register in provider** `Sonic2ObjectArtProvider.loadArtForZone()`:
   ```java
   registerSheet(Sonic2ObjectArtKeys.NEW_BADNIK, artLoader.loadNewBadnikSheet());
   ```

**DO NOT** add badnik/enemy sheets to `ObjectArtData` - it should remain game-agnostic.

### Constants Files
| File | Contents |
|------|----------|
| `Sonic2Constants.java` | Primary ROM offsets |
| `Sonic2ObjectIds.java` | Object type IDs (0x41=Spring, 0x26=Monitor) |
| `Sonic2ObjectConstants.java` | Touch collision data |
| `Sonic2AudioConstants.java` | Music and SFX IDs |

## Adding New Game Support

To add support for a new game (e.g., Sonic 1):
1. Create `GameModule` implementation (`Sonic1GameModule`)
2. Create `RomDetector` to identify the ROM
3. Implement required providers (`ZoneRegistry`, `ObjectRegistry`, audio profile)
4. Register detector in `RomDetectionService.registerBuiltInDetectors()`

## ROM Offset Finder Tool

If `docs/s2disasm` is present, you can use the **RomOffsetFinder** tool to search for disassembly items, find their ROM offsets, verify them against ROM data, and export as Java constants.

### Prerequisites
- `docs/s2disasm/` directory (Sonic 2 disassembly) must be present
- ROM file `Sonic The Hedgehog 2 (W) (REV01) [!].gen` in the project root (for `test`, `verify`, `verify-batch`, `export` commands)

### CLI Commands

| Command | Description |
|---------|-------------|
| `search <pattern>` | Search by label/filename, shows calculated offset |
| `list [type]` | List all includes, optionally filtered by compression type |
| `test <offset> <type>` | Test decompression at a ROM offset |
| `verify <label>` | Verify a calculated offset against ROM data |
| `verify-batch [type]` | Batch verify all offsets (optionally filtered by type) |
| `export <type> [prefix]` | Export verified offsets as Java constants |

### Usage via Maven

```bash
# Search for items - includes calculated ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search <pattern>" -q

# Verify a calculated offset against ROM data
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify <label>" -q

# Batch verify all Nemesis files
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify-batch nem" -q

# Export verified offsets as Java constants
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="export nem ART_" -q

# Test decompression at a ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test <offset> <type>" -q

# List all files of a compression type
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="list <type>" -q
```

### Examples

```bash
# Search for special stage stars art (shows ROM offset automatically)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search SpecialStars" -q

# Search for palettes (supports palette macro)
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search Pal_SS" -q

# Verify a specific label's offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify ArtNem_SpecialHUD" -q
# Output: [OK] ArtNem_SpecialHUD at 0xDD48A (Nemesis, 774 bytes)

# Batch verify all Nemesis files
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="verify-batch nem" -q
# Output:
# [OK] ArtNem_SpecialBack      0xDCD68
# [OK] ArtNem_SpecialHUD       0xDD48A
# [!!] ArtNem_SomeAsset        calc=0x12345 actual=0x12350
# Summary: 45 verified, 1 mismatch, 0 not found, 0 errors

# Export as Java constants
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="export nem ART_" -q
# Output:
# public static final long ART_NEM_SPECIAL_HUD_OFFSET = 0x0DD48AL;
# public static final int ART_NEM_SPECIAL_HUD_SIZE = 774;

# Test with auto-detection
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 auto" -q
```

### Verification Status Codes

| Code | Meaning |
|------|---------|
| `[OK]` | Calculated offset matches ROM data |
| `[!!]` | Mismatch - data found at different offset |
| `[??]` | Not found - couldn't locate data in ROM |
| `[ER]` | Error during verification |

### Adding New Anchor Offsets
The offset calculator uses known anchor points defined in `RomOffsetCalculator.ANCHOR_OFFSETS`. Verified offsets are automatically added as runtime anchors during a session. To add permanent anchors, update the `ANCHOR_OFFSETS` map.

### Compression Types
| Type | Extension | Argument |
|------|-----------|----------|
| Nemesis | `.nem` | `nem` |
| Kosinski | `.kos` | `kos` |
| Enigma | `.eni` | `eni` |
| Saxman | `.sax` | `sax` |
| Uncompressed | `.bin` | `bin` |

### Palette Macro Support
The search also parses `palette` macros from the disassembly:
```asm
Pal_SSResult:   palette Special Stage/Results.bin   ; found as art/palettes/Special Stage/Results.bin
```

### Programmatic Usage

The tools in `uk.co.jamesj999.sonic.tools.disasm` can also be used programmatically:

```java
// Calculate ROM offset for a label
RomOffsetCalculator calculator = new RomOffsetCalculator("docs/s2disasm");
long offset = calculator.calculateOffset("ArtNem_SpecialStars");

// Add verified anchor for improved accuracy
calculator.addVerifiedAnchor("ArtNem_SpecialStars", 0xDD8CE);

// Search the disassembly (includes palette macros)
DisassemblySearchTool searchTool = new DisassemblySearchTool("docs/s2disasm");
List<DisassemblySearchResult> results = searchTool.search("Ring");

// Verify an offset against ROM
RomOffsetFinder finder = new RomOffsetFinder("docs/s2disasm", "path/to/rom.gen");
VerificationResult result = finder.verify("ArtNem_SpecialHUD");
if (result.isVerified()) {
    System.out.printf("Verified at 0x%X%n", result.getVerifiedOffset());
}

// Batch verify and export
List<VerificationResult> results = finder.verifyBatch(CompressionType.NEMESIS);
ConstantsExporter exporter = new ConstantsExporter();
exporter.exportAsJavaConstants(results, "ART_", new PrintWriter(System.out));
```

## Audio Engine hints
*   **Useful locations:** Work In Progress.
    *   `docs` – Contains lots of information about the audio engine in saved htm files.
	*   `docs/YM2612.java.example` – Contains a port of the Gens emulator's YM2612 implementation. Missing PCM functionality. May not be correct!
	*   `docs/SMPS-rips` – Contains ripped audio for various games, including `Sonic the Hedgehog 2`. Contains configurations for SMPSPlay.
	*   `docs/SMPS-rips/SMPSPlay` – This contains the source for SMPSPlay, which is an open-source implementation of playback of rips for game sfx/music, for games that use the SMPS driver for the Sega Genesis.
	*   `docs/SMPS-rips/SMPSPlay/libs/download/libvgm/emu/cores` – Contains source code for several consoles, but most importantly the ym2612(.c) for the sound chip, and sn76489(.c) which we are implementing on our own. These are extremely useful sources of truth for our project, as they are high-accuracy implementations.
*   **Important guidelines:** We strive for accuracy in the audio engine. Wherever possible, we should be implementing features identically to hardware. We should reference the existing libvgm cores, the SMPSPlay source, and the documentation to achieve this. We should not "twiddle knobs" or implement simplified versions of logic, instead preferring to diagnose issues and compare to reference/sources of truth.
## Useful tips
*   **Terminology**: The codebase uses specific terms for level components that differ from standard Sonic 2 naming:
    *   **Pattern:** An 8x8 pixel tile.
    *   **Chunk:** A 16x16 pixel tile, composed of Patterns.
    *   **Block:** A 128x128 pixel area, composed of Chunks.
*   **Dependencies:** Running the engine requires OpenGL libraries from the JogAmp suite (JOGL, JOCL, JOAL), already declared as dependencies in `pom.xml`.
*   **Debug:** `DEBUG_VIEW_ENABLED` (true by default) overlays sensor and collision info during gameplay.
*   **Level Loading:** Performed by `LevelManager`, which reads from the ROM through classes in `uk.co.jamesj999.sonic.data`.
*   **Skipped Tests**: `TestCollisionLogic` is skipped in the test environment because it requires a valid ROM file, which is not available. This is a known and accepted test outcome.
*   **File Endings**: Ensure all source code files end with a newline character.
