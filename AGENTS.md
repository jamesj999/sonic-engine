# Guidance for future AI agents

## Project Mission
This project is a faithful recreation of the **Sonic the Hedgehog** game engine in Java. It aims to:
1.  Use the original ROM data to render levels.
2.  Perfectly and precisely replicate the original physics. (This is IMPORTANT. The engine must recreate the original pixel-for-pixel)
3.  Eventually support user-made characters and level editing tools.

## Current Status
The project is in a **pre-alpha** state.

### Rendering
*   **Status:** Work In Progress.
*   **Issues:**
    *   **Level Data:** Large chunks of the level data are missing (currently testing with Emerald Hill Zone).
    *   **Flipping Logic:** The horizontal 'reversing' (flipping) logic is likely broken or missing in both rendering and physics.

### Physics
*   **Status:** Functional but very incomplete.
*   **Focus:** Less concern for now. Focus should be on fixing rendering issues unless physics changes are required to support rendering (e.g., collision mapping).

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
    *   `graphics` – GL wrappers and render managers
    *   `level` – level structures (patterns, blocks, chunks, collision)
    *   `physics` – sensors and terrain collision
    *   `sprites` – sprite classes, including playable character logic
    *   `timer` – utility timers for events
    *   `tools` – utilities such as `KosinskiReader` for decompressing Sega data
*   **Tests:** Live under `src/test/java/uk/co/jamesj999/sonic/tests` and cover ROM loading, decompression, and collision.

## ROM Offset Finder Tool

If `docs/s2disasm` is present, you can use the **RomOffsetFinder** tool to search for disassembly items and find their ROM offsets. This is useful for locating data in the ROM that is defined in the disassembly.

### Prerequisites
- `docs/s2disasm/` directory (Sonic 2 disassembly) must be present
- ROM file `Sonic The Hedgehog 2 (W) (REV01) [!].gen` in the project root

### Usage via Maven

```bash
# Search for items by label or filename pattern
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search <pattern>"

# Find ROM offset for a specific disassembly item
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="find <label>"

# Test decompression at a ROM offset
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test <offset> <type>"

# List all files of a compression type
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="list <type>"
```

### Examples

```bash
# Search for ring-related items
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="search ring" -q

# List all Nemesis-compressed files
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="list nem" -q

# Test Nemesis decompression at offset 0x3000
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 nem" -q

# Test with auto-detection
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="test 0x3000 auto" -q
```

### Compression Types
| Type | Extension | Argument |
|------|-----------|----------|
| Nemesis | `.nem` | `nem` |
| Kosinski | `.kos` | `kos` |
| Enigma | `.eni` | `eni` |
| Saxman | `.sax` | `sax` |
| Uncompressed | `.bin` | `bin` |

### Programmatic Usage

The tools in `uk.co.jamesj999.sonic.tools.disasm` can also be used programmatically:

```java
// Search the disassembly
DisassemblySearchTool searchTool = new DisassemblySearchTool("docs/s2disasm");
List<DisassemblySearchResult> results = searchTool.search("Ring");

// Test decompression at a ROM offset
CompressionTestTool testTool = new CompressionTestTool("path/to/rom.gen");
CompressionTestResult result = testTool.testDecompression(0x3000, CompressionType.NEMESIS);
if (result.isSuccess()) {
    System.out.printf("Decompressed %d bytes from %d compressed bytes%n",
        result.getDecompressedSize(), result.getCompressedSize());
}
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
*   **Skipped Tests**: `TestRomLogic` is skipped in the test environment because it requires a valid ROM file, which is not available. This is a known and accepted test outcome.
*   **File Endings**: Ensure all source code files end with a newline character.
