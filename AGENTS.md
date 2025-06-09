# Guidance for future Codex agents

This repository contains a Java 21 Maven project implementing a Sonic‑style game engine.  The code is still **pre‑alpha** and focuses on core mechanics like movement and collision.

## Key information

* Entry point: `uk.co.jamesj999.sonic.Engine` (declared in the manifest).  A `main` method creates an OpenGL canvas with an `FPSAnimator`.
* Build with `mvn package`.  Tests can be run with `mvn test` (JUnit 4).
* A compatible Sonic 2 ROM named `Sonic The Hedgehog 2 (W) (REV01) [!].gen` is expected for level loading.  Without it, level logic may fail at runtime.
* Important packages under `src/main/java/uk/co/jamesj999/sonic`:
  * `Control` – input handling
  * `camera` – camera logic
  * `configuration` – game settings via `SonicConfiguration` and `SonicConfigurationService`
  * `data` – ROM loaders and game classes
  * `debug` – debug overlay (`DebugRenderer`), enabled via the `DEBUG_VIEW_ENABLED` configuration flag
  * `graphics` – GL wrappers and render managers
  * `level` – level structures (patterns, blocks, chunks, collision)
  * `physics` – sensors and terrain collision
  * `sprites` – sprite classes, including playable character logic
  * `timer` – utility timers for events
  * `tools` – utilities such as `KosinskiReader` for decompressing Sega data
* Tests live under `src/test/java/uk/co/jamesj999/sonic/tests` and cover ROM loading, decompression and collision.

## Useful tips

* Running the engine requires OpenGL libraries from the JogAmp suite (JOGL, JOCL, JOAL), already declared as dependencies in `pom.xml`.
* `DEBUG_VIEW_ENABLED` (true by default) overlays sensor and collision info during gameplay.
* Level loading is performed by `LevelManager`, which reads from the ROM through classes in `uk.co.jamesj999.sonic.data`.

This project aims for accuracy with the original Sonic games and will eventually include user-made characters and level editing tools.
