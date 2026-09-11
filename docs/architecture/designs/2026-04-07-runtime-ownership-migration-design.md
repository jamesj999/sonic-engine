# Runtime Ownership Migration Design

**Date:** 2026-04-07
**Status:** Design
**Scope:** Finalize gameplay runtime ownership, introduce world/session/mode boundaries, and stub editor-mode switching for a future release

## Overview

The engine has partially migrated from pervasive singleton ownership to an explicit `GameRuntime` plus `GameServices` / `ObjectServices` model. That direction is correct, but the current architecture still mixes runtime-owned managers, bootstrap singletons, global game-module state, and ad hoc rebinding logic. This makes ownership ambiguous and blocks the longer-term goal of seamless switching between gameplay and editor mode.

This design finishes the runtime migration around one architectural rule:

- bootstrap-only globals are allowed before a loaded session exists
- once a session exists, gameplay systems must be owned by explicit session/mode objects, not singleton fallbacks

The future editor requirement shapes the design:

- the loaded world must remain alive while switching modes
- live gameplay actors and time-driven systems are disposable
- entering editor places the editor cursor at the current character position
- exiting editor rebuilds gameplay actors at the cursor position
- score, rings, lives, emeralds, timers, and checkpoint/session progress are not preserved across editor exit

The editor itself is out of scope for this release. This migration only creates the ownership boundaries and a stub `EditorModeContext` so later editor work has a stable foundation.

## Current Architecture

Today the primary mutable gameplay container is `GameRuntime`, managed globally by `RuntimeManager`. `Engine`, `GameLoop`, `LevelManager`, `SpriteManager`, and other systems can resolve managers either from the active runtime or from singleton bootstrap instances. `GameModuleRegistry` also remains a global process-wide selector for the active game module.

This produces several architectural problems:

- world state and gameplay state are still mixed together
- singleton fallbacks remain part of normal gameplay code paths
- some subsystems require manual rebinding after runtime creation
- game/module selection is global rather than session-owned
- editor mode would have to either mutate gameplay systems in place or tear down large portions of a graph that was not designed for swapping

In short, the durable object in the current architecture is still the gameplay runtime. That is the wrong lifetime for the future editor requirement.

## Target Architecture

The target architecture has four ownership layers.

### 1. EngineContext

`EngineContext` is process-wide and survives mode switches.

Responsibilities:

- windowing and GL lifecycle
- audio device/backend lifecycle
- configuration access
- ROM access and ROM selection state
- long-lived render/audio caches
- other true application-wide services that should not be rebuilt on gameplay/editor switches

`EngineContext` is the narrow replacement for today's bootstrap/global responsibilities. It is not responsible for loaded-level state or live gameplay systems.

### 2. WorldSession

`WorldSession` is the long-lived mutable representation of the currently loaded world.

Responsibilities:

- current game/module identity
- loaded zone/act metadata
- `MutableLevel`
- shared tile/block/chunk/palette state
- object placement data and other editor-visible world data
- shared world data that must survive gameplay <-> editor transitions

`WorldSession` must not own live players, sidekicks, timer-driven gameplay state, active object AI instances, or gameplay-only event loops.

This is the major architectural shift: the loaded world becomes the durable object, not the gameplay runtime.

### 3. ModeContext

`ModeContext` is a swappable layer built on top of one `WorldSession`.

Two concrete forms are planned:

- `GameplayModeContext`
- `EditorModeContext`

Only `GameplayModeContext` will be fully implemented in this release. `EditorModeContext` will be a stub with lifecycle hooks and minimal placeholder state.

### 4. SessionManager

`SessionManager` owns:

- the active `EngineContext`
- the active `WorldSession`
- the active `ModeContext`

It is responsible for:

- creating a session from a ROM/module/zone selection
- switching between gameplay and editor mode
- tearing down and rebuilding mode-scoped dependencies
- keeping the shared `WorldSession` alive across mode transitions

`RuntimeManager` should either evolve into this role or be replaced by it. Regardless of naming, the active container must no longer mean only gameplay.

## GameplayModeContext

`GameplayModeContext` owns disposable gameplay projections over the shared world.

Responsibilities:

- player and sidekick runtime instances
- object runtime instances and object manager state
- ring runtime state
- timer state
- level event runtime state
- gameplay camera behavior and follow state
- collision runtime wiring
- gameplay-only HUD/session counters and other ephemeral frame-driven systems

When gameplay mode is destroyed, all of the above can be discarded and rebuilt from `WorldSession` plus entry parameters.

## EditorModeContext

For this release, `EditorModeContext` is a stub only. It exists to finalize the ownership boundary, not to ship editing features.

Responsibilities for the stub:

- define the editor-mode lifecycle contract
- hold a cursor position
- support entering editor at a position derived from gameplay
- support exiting editor with a cursor position used to rebuild gameplay

Deferred responsibilities for a future release:

- editor object representations
- tools and selection
- mutation UX
- undo/redo
- editor-specific render/input pipeline

## Mode Switching Lifecycle

Mode switching must be explicit and symmetric.

### Enter Editor

1. Read the main character position from `GameplayModeContext`.
2. Create editor cursor state at that position.
3. Tear down gameplay-only state:
   - live objects
   - object AI
   - timers
   - level event runtime state
   - gameplay camera-follow/player state
   - other disposable gameplay projections
4. Preserve the `WorldSession`.
5. Activate `EditorModeContext`.

### Exit Editor

1. Read cursor position from `EditorModeContext`.
2. Destroy editor-mode state.
3. Create a fresh `GameplayModeContext` against the same `WorldSession`.
4. Spawn main character and sidekicks at the cursor position.
5. Initialize gameplay session state as fresh gameplay state, not resumed state.

Explicit non-preserved data on editor exit:

- score
- rings
- lives
- emeralds
- timer state
- checkpoint data
- other session-progress counters

This is intentionally a rebuild of gameplay projections over a persistent world, not a pause/resume of gameplay runtime state.

## Game Module Ownership

The active `GameModule` must become session-owned, not process-global.

Current problem:

- `GameModuleRegistry` exposes one mutable global module selection for the whole process
- gameplay code reads from that global selection during normal frame execution

Target:

- `WorldSession` owns the active `GameModule`
- services resolve module-owned providers through the active session
- bootstrap code may use ROM detection to create a session, but gameplay code must not depend on a mutable global registry

This is required for future dependency swapping. An editor mode cannot safely depend on global module identity if multiple session shapes or previews later exist.

## Service Access Rules

After this migration, service access rules become:

- `EngineContext` services are accessed through explicit engine/session ownership, not gameplay singleton fallbacks
- world-scoped systems are accessed through `WorldSession`
- gameplay-scoped systems are accessed through `GameplayModeContext`
- object code uses `ObjectServices`, backed by explicit session + mode ownership
- non-object gameplay code uses a `GameServices` facade that resolves through the active session/mode context

The following patterns are migration targets and should disappear from gameplay flow:

- runtime-owned singleton fallbacks after session creation
- rebinding hooks that repair stale references after mode/runtime creation
- hiding singleton references from tests via bytecode indirection instead of removing them
- global module lookups during live gameplay frame execution

## LevelManager and World Ownership

`LevelManager` is currently too large and mixes multiple lifetimes:

- level/world ownership
- gameplay object/ring initialization
- transition orchestration
- rendering details
- service construction

This migration does not require a full rewrite of `LevelManager`, but it does require a lifetime split:

- world-persistent responsibilities move toward `WorldSession`
- gameplay-only projections move toward `GameplayModeContext`

At minimum, the migration must separate:

- data that survives gameplay/editor switching
- data rebuilt when gameplay mode is re-entered

`MutableLevel` should become a first-class part of `WorldSession`, not just a feature reachable from gameplay infrastructure.

## Scope for This Release

In scope:

- define and introduce `EngineContext`, `WorldSession`, `ModeContext`, `GameplayModeContext`, and stub `EditorModeContext`
- move active `GameModule` ownership off process-global state
- narrow bootstrap access to true pre-session startup only
- remove gameplay-time singleton fallbacks for runtime-owned managers
- rewire service facades to resolve through explicit ownership
- add session/mode-switch lifecycle entry points
- encode cursor handoff semantics for future editor switching
- add tests/guards enforcing the new ownership model

Not in scope:

- full editor implementation
- editor UI
- editor object representations beyond stubs
- undo/redo
- preserving gameplay progress through editor transitions
- broad unrelated refactoring outside what is needed to complete ownership migration

## Migration Strategy

The work should proceed in phases so gameplay remains functional while ownership moves.

### Phase 1: Introduce New Ownership Types

Add the new containers and make the target graph explicit:

- `EngineContext`
- `WorldSession`
- `ModeContext`
- `GameplayModeContext`
- stub `EditorModeContext`
- `SessionManager` or equivalent replacement/evolution of `RuntimeManager`

This phase may temporarily adapt existing classes into these containers rather than fully rewriting them.

### Phase 2: Move GameModule and Session Identity Into WorldSession

- eliminate live gameplay dependence on `GameModuleRegistry`
- resolve providers from `WorldSession`
- keep ROM detection/bootstrap logic only at session creation time

### Phase 3: Re-scope GameRuntime Into GameplayModeContext

Either:

- rename `GameRuntime` to `GameplayModeContext`, or
- split `GameRuntime` into world-scoped and gameplay-scoped pieces

The result must be clear: gameplay context is disposable, world session is durable.

### Phase 4: Remove Gameplay Singleton Fallbacks

For runtime-owned managers:

- no singleton fallback once a session exists
- no stale bootstrap references requiring rebinding
- no gameplay code path that silently falls back to bootstrap instances

Bootstrap-only globals remain allowed before session creation.

### Phase 5: Rewire Service Facades

- update `GameServices` to resolve through active session/mode ownership
- update `ObjectServices` to use gameplay mode + world session
- remove shims whose only purpose is hiding singleton access from guard tests

### Phase 6: Add Stub Editor Lifecycle

Add minimal APIs for:

- `enterEditorMode()`
- `exitEditorMode()`
- cursor-position handoff
- gameplay teardown and rebuild around a persistent `WorldSession`

This phase proves the architecture supports mode swapping even before the real editor ships.

### Phase 7: Tighten Enforcement

After migration:

- shrink or remove broad allowlists in architecture guard tests
- replace heuristic "don't mention singleton in leaf bytecode" patterns with direct ownership assertions
- ensure the test suite can actually compile and run the guard tests

## Risks and Design Constraints

### Risk: Partial Migration Leaves Two Ownership Models Alive

If the new containers are added but singleton fallbacks remain widely available, the codebase will continue to drift. The migration must end with explicit rules and test enforcement, not just additional wrapper types.

### Risk: Over-scoping Into Full Editor Work

This release must stop at boundary creation. Adding real editor behavior now would increase churn and make it harder to finish the ownership migration cleanly.

### Constraint: Preserve Existing Gameplay Behavior

The migration is architectural, but gameplay parity still matters. Existing gameplay flows must continue to work while ownership moves.

### Constraint: World Must Stay Mutable Across Modes

The design assumes live mutation of the currently loaded world. That means shared world data cannot be rebuilt on every mode switch.

## Verification Requirements

Before calling the migration complete, the following must be true:

- production compile passes
- architecture guard tests compile and run
- active gameplay no longer depends on global game-module state
- runtime-owned manager access in gameplay flow is explicit and session-owned
- entering and exiting the stub editor mode preserves the shared world and rebuilds gameplay actors at the expected cursor position
- gameplay session counters are reinitialized on editor exit rather than resumed

## Recommended Implementation Direction

The best implementation shape is:

- narrow app-wide bootstrap state in `EngineContext`
- durable loaded-world ownership in `WorldSession`
- disposable gameplay/editor projections through `ModeContext`

That gives the engine the cleanest path to future editor mode without requiring the whole application to rebuild its expensive global resources on every switch.
