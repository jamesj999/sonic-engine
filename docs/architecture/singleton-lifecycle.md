# Service Lifecycle

This branch no longer treats gameplay state as a web of global singletons. The engine is now split into a process-level service root, durable world session state, and disposable mode contexts. This document describes the current model and the few compatibility boundaries that still exist while the migration closes.

## 1. Architecture Overview

OpenGGF now uses scoped access patterns, each with a different lifetime.

| Layer | Entry point | Scope | Intended users |
|---|---|---|---|
| Process services | `EngineServices` | One per process/bootstrap | engine bootstrap, process-global managers |
| World session | `WorldSession` | One per launched game/session | durable world state that survives editor swaps |
| Gameplay mode | `GameplayModeContext` | One per gameplay entry/resume | gameplay-owned mutable state |
| Static service facade | `GameServices` | facade over current gameplay mode + engine services | non-object gameplay code |
| Per-object injection | `ObjectServices` | object-scoped handle | `AbstractObjectInstance` subclasses |

### Process-Level Services

These services are not recreated on every level load. Typical examples:

- `SonicConfigurationService`
- `GraphicsManager`
- `AudioManager`
- `RomManager`
- `PerformanceProfiler`
- `DebugOverlayManager`
- `PlaybackDebugManager`
- `RomDetectionService`
- `CrossGameFeatureProvider`

They are assembled into `EngineServices` during bootstrap.

### World Session State

`WorldSession` owns durable state that should survive editor mode entry/exit:

- active `GameModule`
- save-session context
- current zone/act/apparent-act metadata
- loaded `Level`, including `MutableLevel` editor mutations

### Gameplay-Mode State

These services live inside `GameplayModeContext` and are recreated when gameplay is torn down and rebuilt:

- `Camera`
- `LevelManager`
- `SpriteManager`
- `GameStateManager`
- `TimerManager`
- `FadeManager`
- `CollisionSystem`
- `TerrainCollisionManager`
- `WaterSystem`
- `ParallaxManager`
- `GameRng`
- `SolidExecutionRegistry`
- runtime-shared registries such as `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`, `SpecialRenderEffectRegistry`, and `AdvancedRenderModeController`
- `RewindRegistry`, `RewindController`, and `PlaybackController`

This is the state that used to be singleton-heavy. Production code should now reach it through `GameServices`, `ObjectServices`, `GameplayModeContext`, or explicit injection, not through `getInstance()` or retired runtime-facade lookups.

## 2. Access Rules

### Non-Object Code

Managers, controllers, level/event code, HUD code, and other non-object runtime logic should use `GameServices`:

```java
Camera camera = GameServices.camera();
LevelManager level = GameServices.level();
GameStateManager gameState = GameServices.gameState();
AudioManager audio = GameServices.audio();
```

Use the `*OrNull()` variants only when code genuinely supports the absence of an active gameplay mode:

```java
LevelManager level = GameServices.levelOrNull();
```

### Object Code

Anything extending `AbstractObjectInstance` should use injected `ObjectServices`:

```java
services().camera()
services().objectManager()
services().audioManager()
services().gameState()
services().gameModule()
```

Object code should not call:

- `Foo.getInstance()`
- retired runtime-facade lookups
- `GameServices.*` when the injected object handle is available

The main exception is framework code inside `AbstractObjectInstance` / `DefaultObjectServices`, where the object-layer bridge itself is implemented.

## 3. Lifecycle

### Engine Bootstrap

1. The engine assembles process-global services.
2. Those services are wrapped in `EngineServices`.
3. `SessionManager.openGameplaySession(...)` creates a `WorldSession` and `GameplayModeContext`.
4. `GameplaySessionFactory.attachManagers(...)` wires the gameplay-owned managers and shared registries into the mode context.

### Gameplay Reset

Destroying gameplay should rebuild gameplay-owned state, not null out process services or durable world data. The normal flow is:

1. reset module/session state as needed
2. destroy the current `GameplayModeContext`
3. create a fresh `GameplayModeContext`
4. load the requested ROM/zone/module state into the new context

### Editor Round Trip

Editor mode preserves `WorldSession` data and rebuilds gameplay mode state:

1. capture loaded level, zone/act metadata, and editor cursor/playtest state
2. destroy the active `GameplayModeContext`
3. enter `EditorModeContext` over the same `WorldSession`
4. on playtest resume, create a fresh `GameplayModeContext`
5. call `LevelManager.restoreInheritedLevel()` so the new runtime is rebuilt over the surviving `Level`

### Level/Object Construction

`ObjectManager` is the construction boundary for object DI:

1. it creates the object
2. it installs `ObjectServices`
3. the object runs update/render logic against injected services

If an object needs service-dependent behavior during construction, it must use the construction-context bridge supplied by `AbstractObjectInstance`. It must not escape to `GameServices`.

## 4. Compatibility Boundaries

The migration is not yet fully at the ideal end state. A small amount of compatibility scaffolding still exists:

- `EngineServices.fromLegacySingletonsForBootstrap()` remains the temporary bootstrap bridge for process services.
- Some process-global classes still expose `getInstance()` for compatibility, but new production code should not depend on those accessors.
- Test guard names may still mention `RuntimeManager`/`GameRuntime`; those guards enforce that retired facade usage stays out of production code.

Treat those APIs as migration boundaries, not endorsed architecture.

## 5. Testing Guidance

### Preferred Runtime Reset

Tests should use the shared reset/runtime helpers instead of manually reassembling engine state in arbitrary order.

Use:

- `TestEnvironment.resetAll()` for full environment reset
- `SessionManager.openGameplaySession(...)` plus `GameplaySessionFactory.attachManagers(...)` when a focused test needs to assemble a gameplay mode directly
- `@RequiresRom(...)` for ROM-backed tests once the Jupiter migration is complete

### Headless Gameplay Tests

Headless tests still follow the same runtime model:

1. reset environment/runtime
2. initialize headless graphics if required
3. create/load gameplay state
4. drive the test through `HeadlessTestRunner` or explicit runtime APIs

Prefer examples like:

```java
GraphicsManager graphics = GameServices.graphics();
LevelManager level = GameServices.level();
Camera camera = GameServices.camera();
```

Avoid stale singleton-era setup snippets like:

```java
GraphicsManager.getInstance()
LevelManager.getInstance()
Camera.getInstance()
```

## 6. Guardrails

The migration is enforced by source/bytecode guards in test code. The important ones are:

- `TestRuntimeSingletonGuard`
- `TestProductionSingletonClosureGuard`
- `TestObjectServicesMigrationGuard`
- `TestNoServicesInObjectConstructors`

When these fail, the correct fix is normally one of:

1. move access to `GameServices`
2. move access to injected `ObjectServices`
3. inject the dependency directly
4. if and only if it is a real bootstrap boundary, document the exception explicitly

Weakening a guard to preserve a convenience singleton path is usually the wrong move.
