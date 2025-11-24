# Guidance for future Codex agents

## Project Mission
This project is a faithful recreation of the **Sonic the Hedgehog** game engine in Java. It aims to:
1.  Use the original ROM data to render levels.
2.  Perfectly and precisely replicate the original physics.
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

## Key information
*   **Entry point:** `uk.co.jamesj999.sonic.Engine` (declared in the manifest). A `main` method creates an OpenGL canvas with an `FPSAnimator`.
*   **Build:** `mvn package`. Tests can be run with `mvn test` (JUnit 4).
*   **ROM Requirement:** A compatible Sonic 2 ROM named `Sonic The Hedgehog 2 (W) (REV01) [!].gen` is expected for level loading. Without it, level logic may fail at runtime.
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
*   **Dependencies:** Running the engine requires OpenGL libraries from the JogAmp suite (JOGL, JOCL, JOAL), already declared as dependencies in `pom.xml`.
*   **Debug:** `DEBUG_VIEW_ENABLED` (true by default) overlays sensor and collision info during gameplay.
*   **Level Loading:** Performed by `LevelManager`, which reads from the ROM through classes in `uk.co.jamesj999.sonic.data`.
