# Guidance for future Codex agents

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
    *   `feature/` for new features.
    *   `bugfix/` for bug fixes.
2.  **Code Structure:** Keep logic within existing or new manager classes. Avoid putting all logic into `Engine.java` to maintain a strong object-oriented design.

## Key information
*   **Entry point:** `uk.co.jamesj999.sonic.Engine` (declared in the manifest). A `main` method creates an OpenGL canvas with an `FPSAnimator`.
*   **Build:** `mvn package`. Tests can be run with `mvn test` (JUnit 4).
*   **Run:** `java -jar target/sonic-engine-0.05-BETA-jar-with-dependencies.jar`.
*   **ROM Requirement:** A compatible Sonic 2 ROM named `Sonic The Hedgehog 2 (W) (REV01) [!].gen` is expected for level loading. Without it, level logic may fail at runtime. If the ROM is not present locally, it can be downloaded for debugging, coding, and testing from `http://bluetoaster.net/secretfolder/Sonic%20The%20Hedgehog%202%20%28W%29%20%28REV01%29%20%5B!%5D.gen`.
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
