# OpenGGF User Guide

OpenGGF is an open-source, Java-based engine that reimplements classic Mega Drive / Genesis
Sonic the Hedgehog games. It is not an emulator: it reads data from original ROM images and
runs its own implementation of the game logic, physics, rendering, and audio. No copyrighted
assets are included. You must supply your own legally obtained ROM files.

The engine currently supports Sonic the Hedgehog (S1), Sonic the Hedgehog 2 (S2), and
Sonic 3 & Knuckles (S3K) at varying levels of completeness.

## Choose Your Path

### I want to play

You have ROM files and want to get the engine running.

- [Getting Started](playing/getting-started.md) -- From zero to playing in five minutes
- [Configuration](playing/configuration.md) -- Common setup questions answered
- [Controls](playing/controls.md) -- Full keyboard reference
- [Game Status](playing/game-status.md) -- What works, what's incomplete, what to expect
- [Troubleshooting](playing/troubleshooting.md) -- Fixing common problems

### I want to cross-reference the engine against the disassembly

You know s1disasm, s2disasm, or skdisasm and want to understand how the engine represents
the same concepts -- or you want to check the engine's accuracy.

- [How the Engine Reads ROMs](cross-referencing/how-the-engine-reads-roms.md) -- The data pipeline from ROM bytes to rendered screen
- [68000 Primer](cross-referencing/68000-primer.md) -- Just enough assembly to read object routines
- [Mapping Exercises](cross-referencing/mapping-exercises.md) -- Learn to trace any feature between disassembly and engine
- [Architecture Overview](cross-referencing/architecture-overview.md) -- Where things live in the codebase
- [Tooling](cross-referencing/tooling.md) -- RomOffsetFinder and other built-in tools
- [Per-Game Notes](cross-referencing/per-game-notes.md) -- S1, S2, and S3K specific quirks

### I want to contribute

You want to add objects, bosses, zones, or engine improvements.

- [Dev Setup](contributing/dev-setup.md) -- Environment, build, and test setup
- [Architecture Deep Dive](contributing/architecture.md) -- Providers, services, and runtime
- [Documentation and Branch Policy](contributing/documentation-policy.md) -- Commit trailers, changelog/discrepancy updates, and PR documentation checks
- [Tutorial: Implement an Object](contributing/tutorial-implement-object.md) -- Worked example from disassembly to running code
- [Adding Bosses](contributing/adding-bosses.md) -- Boss-specific patterns
- [Adding Zones](contributing/adding-zones.md) -- Bringing up a new zone
- [Audio System](contributing/audio-system.md) -- SMPS driver, FM synthesis, PSG
- [Rewind System](contributing/rewind-system.md) -- Using, validating, and extending frame rewind
- [Testing](contributing/testing.md) -- Writing and running tests
- [Trace Replay Testing](contributing/trace-replay.md) -- BizHawk recordings, replay tests, and divergence analysis

## Related Documents

These documents live at the repository root and complement this guide:

- [README](../../README.md) -- Project overview and FAQ
- [CHANGELOG](../../CHANGELOG.md) -- Detailed release history
- [ROADMAP](../../ROADMAP.md) -- Development priorities
- [CONFIGURATION](../../CONFIGURATION.md) -- Full configuration reference (all keys)
- [CREDITS](../../CREDITS.md) -- Community resources this project builds on
- [OBJECT_CHECKLIST](../../OBJECT_CHECKLIST.md) -- Sonic 2 object implementation status
- [S1_OBJECT_CHECKLIST](../../S1_OBJECT_CHECKLIST.md) -- Sonic 1 object implementation status
- [S3K_OBJECT_CHECKLIST](../../S3K_OBJECT_CHECKLIST.md) -- Sonic 3&K object implementation status
