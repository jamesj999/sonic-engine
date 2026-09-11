# Architecture Deep Dive

This page explains the engine's major architectural patterns so you know where to make
changes when adding objects, bosses, zones, or engine features.

## Package Layout

```
com.openggf/
  Engine.java                -- Application entry, window creation, main loop setup
  GameLoop.java              -- Per-frame orchestration: input, update, render
  LevelFrameStep.java        -- Single-frame level update sequence

  game/                      -- Game module system
    GameModule.java          -- Interface each game implements
    GameModuleRegistry.java  -- Maps game identifiers ("s1","s2","s3k") to modules
    GameServices.java        -- Global facade over engine and runtime-owned services
    session/                 -- EngineServices, SessionManager, WorldSession, mode contexts
    rewind/                  -- Frame rewind primitives, keyframes, registry, playback controller
      identity/              -- Stable player/object/spawn ids for reference rebinding
      schema/                -- Compact field-capture schemas, codecs, and state blobs
    zone/                    -- Typed zone runtime state adapters
    palette/                 -- Shared palette ownership/composition
    animation/               -- Shared animated tile channel graph
    mutation/                -- Deterministic level-layout mutation pipeline
    render/                  -- Staged special render effects + advanced render modes

    sonic1/                  -- Sonic 1 module
      Sonic1GameModule.java  -- S1 provider wiring
      Sonic1.java            -- S1 ROM parsing and game data
      constants/             -- ROM addresses
      objects/               -- Object instances and registry
      audio/                 -- S1 SMPS configuration
      scroll/                -- S1 parallax scroll handlers
      events/                -- S1 per-zone level events

    sonic2/                  -- Sonic 2 module (same structure as above)
    sonic3k/                 -- Sonic 3&K module (same structure)

  level/                     -- Level infrastructure
    LevelManager.java        -- Public coordinator/facade for the active level
    LevelTilemapManager.java -- Tile grid, chunk/block state, VRAM upload
    LevelRenderer.java       -- Scene, sprite/object, and ending-background render passes
    LevelActTransitionExecutor.java -- In-place act-transition reload choreography
    LevelWaterCoordinator.java      -- Water loading and playable underwater state
    LevelCheckpointCoordinator.java -- Checkpoint/respawn state and restore
    LevelDirtyRegionDispatcher.java -- MutableLevel dirty-region dispatch
    objects/                 -- Base object classes, registry interface, spawn records
      AbstractObjectInstance.java  -- Base class for all game objects
      ObjectRegistry.java         -- Interface: maps object IDs to factories
      ObjectManager.java          -- Active object tracking, spawn/despawn
      ObjectServices.java         -- Contextual services available to objects
      ObjectSpawn.java            -- Data record: x, y, objectId, subtype, flags
    render/                  -- Sprite rendering (PatternSpriteRenderer)
    resources/               -- Resource loading and decompression orchestration

  physics/                   -- Physics and collision
    CollisionSystem.java        -- Player/object terrain checks
    TerrainCollisionManager.java -- Terrain collision orchestration
    ObjectTerrainUtils.java      -- Wall/floor/ceiling distance checks

  sprites/                   -- Sprite system
    playable/                -- Player character classes
    animation/               -- Animation controller
    art/                     -- Sprite art set, DPLC handling

  graphics/                  -- GPU rendering pipeline
    PatternAtlas.java        -- Tile texture atlas
    TilemapGpuRenderer.java  -- Background plane rendering
    GLCommand.java           -- Render command interface

  audio/                     -- Sound system
    smps/                    -- SMPS sequencer, channel state, DAC data
    synth/                   -- FM synthesis (Ym2612Chip), PSG (PsgChip)
    driver/                  -- Sound driver orchestration

  camera/                    -- Camera position, boundaries, shake
  data/                      -- ROM reading, decompression (Kosinski, Nemesis, etc.)
  debug/                     -- Debug overlays and visualization
  tools/                     -- Offline tools (RomOffsetFinder, rewind inventory, etc.)
```

## The GameModule / Provider Pattern

The engine supports three games through a pluggable module system. Each game implements
the `GameModule` interface, which returns a collection of **providers** -- objects that
supply game-specific behavior for a generic engine capability.

Here is a simplified view of what `GameModule` provides:

| Provider | What it supplies |
|----------|-----------------|
| `ObjectRegistry` | Maps object IDs to factory functions that create instances |
| `ScrollHandlerProvider` | Per-zone parallax background scroll logic |
| `PhysicsProvider` | Per-character physics profiles (speeds, gravity, acceleration) |
| `LevelEventProvider` | Per-zone dynamic camera boundaries, triggers, cutscenes |
| `WaterDataProvider` | Per-zone water heights, underwater palettes, dynamic handlers |
| `ObjectArtProvider` | Sprite art sets, PLCs, mappings |
| `ZoneArtProvider` | Zone-specific tile art configuration |
| `TitleScreenProvider` | Game-specific title screen |
| `LevelSelectProvider` | Game-specific level select |
| `EndingProvider` | Credits and ending cutscene |
| `ZoneRegistry` | Zone/act metadata and identifiers |
| `TouchResponseTable` | Collision response rules |

The engine core calls these providers without knowing which game is active. To add
behavior for a specific game, you implement or extend the relevant provider in that
game's module directory.

**Example:** When the engine needs to know the water height for the current zone, it
calls `gameModule.getWaterDataProvider().getWaterHeight(zone, act)`. Sonic 2's module
returns water heights for ARZ and CPZ; Sonic 1's returns heights for LZ and SBZ3;
Sonic 3&K's returns heights for HCZ and LBZ. The engine does not know or care which
zones have water -- it just asks the provider.

## Services and Session Ownership

The engine uses a scoped service architecture:

**EngineServices** (`com.openggf.game.session.EngineServices`) is the process-level root.
It owns services that are not recreated with gameplay sessions:
- ROM data access
- Graphics pipeline
- Audio system
- Configuration
- Debug/profiling services
- ROM detection and cross-game feature donation

**SessionManager** owns the current `WorldSession` plus the active mode context.
`WorldSession` is durable world state: active `GameModule`, save session context,
current zone/act metadata, and the loaded `Level`/`MutableLevel`. It survives editor
mode swaps.

**GameplayModeContext** is disposable gameplay state. It is rebuilt when gameplay is
entered or resumed, and owns `Camera`, `TimerManager`, `GameStateManager`, `FadeManager`,
`GameRng`, `SolidExecutionRegistry`, `WaterSystem`, `ParallaxManager`,
`TerrainCollisionManager`, `CollisionSystem`, `SpriteManager`, `LevelManager`, rewind
controllers, and the shared runtime framework stack.

**GameServices** is the static facade for non-object code. Gameplay-scoped accessors
resolve through the active `GameplayModeContext`; engine-global accessors resolve
through `EngineServices`. Code that can receive explicit dependencies should still do so,
but managers, event handlers, HUD code, and render orchestration commonly use
`GameServices`.

**ObjectServices** is the contextual tier for object instances. It provides access to
things that are specific to the current gameplay context:
- Current level and camera
- Object lifecycle helpers and object-manager-backed operations
- Sound effect playback
- Game state (rings, lives, score)

Every object instance receives an `ObjectServices` reference via `services()`. This is
how objects interact with the world: `services().playSfx(id)`, camera queries,
game-state updates, and object-manager-backed helpers. New runtime child objects
should be spawned through `spawnChild(...)`, `spawnFreeChild(...)`, or an existing
`level.objects` lifecycle wrapper rather than direct manager calls.

The separation exists because the planned level editor will have multiple simultaneous
level contexts. Process services stay shared; object services are backed by the active
gameplay context.

### Object Service Access Contract

Object instances must treat `ObjectServices` as their runtime boundary:

- Use `services()` for required gameplay dependencies such as camera, object manager,
  audio, game state, render manager, level manager, zone features, and RNG.
- Use `tryServices()` only for optional fallback paths where the object can safely run
  before injection, such as legacy direct-construction tests or debug-only probes.
- Do not call `GameServices`, `EngineServices`, `RuntimeManager`, `GameModuleRegistry`,
  or manager `getInstance()` methods from normal object code. Those process-global
  roots are reserved for documented bridge classes such as `DefaultObjectServices`,
  `BootstrapObjectServices`, `ObjectManager`, and registry/composition code.
- Do not call `services()` from object constructors. Object services are injected by the
  object manager after construction unless the object is created through a managed
  construction-context helper. Initialize service-dependent state lazily in `update()`
  or through an explicit post-construction path.
- When an object creates a child object that needs services during construction, use
  `spawnChild(...)`, `spawnFreeChild(...)`, or an explicit construction-context wrapper
  instead of `new ChildInstance(...)` followed by `addDynamicObject(...)`.

The test guard suite enforces this contract with `TestObjectServicesMigrationGuard`,
`TestNoServicesInObjectConstructors`, and `TestConstructionContextGuard`. If a new
exception is truly needed, document the exact bridge line and reason in the guard rather
than exempting a whole class.

### Object Behavior Profiles And Control Contracts

Object behavior vocabulary should be shared at the game layer and executed by the
object layer:

- Canonical profiles live under `com.openggf.game.profiles.*`, with family
  subpackages such as solid routines, touch response, and object lifecycle. These
  profiles describe cross-game behavior; they are not zone-local or object-manager
  implementation details.
- `level.objects` remains the compatibility and execution layer. It may adapt
  legacy provider booleans and hooks to canonical profiles, but new profile types
  should not be invented in game-specific object packages.
- `ObjectControlState` should describe object-control intent and derived predicates
  instead of adding new raw setter combinations.
- `ObjectPlayerQuery` plus `ObjectPlayerParticipationPolicy` should decide which
  playable entities participate in object logic. Code that uses only the focused
  player or first sidekick needs an explicit native-P1/P2 reason.
- `ObjectLifetimeOps` should own object destruction, offscreen expiry,
  respawn-latch mutation, and slot transfer operations. Direct lifecycle mutation
  is legacy or compatibility code unless a documented profile gap requires it.

When source guards enforce these rules, keep their baselines as shrink-only migration
artifacts. Adding a new object, boss, badnik, or trace fix should either use the shared
contract or document why an existing compatibility wrapper is still required.

## Runtime-Owned Systems

The old `GameRuntime`/`RuntimeManager` facade has been retired from production code.
Mutable gameplay state now lives on `GameplayModeContext`; durable world state lives
on `WorldSession`; process-level services live behind `EngineServices`. New behavior
should route through these owners, `GameServices`, `ObjectServices`, or explicit
injection rather than direct singleton or retired-runtime lookups.

The current framework stack includes:

- Rewind framework - gameplay-scoped keyframe capture, restore, deterministic replay, and
  held-rewind support. It also owns the generic and compact-schema field capture
  paths, stable identity ids, and policy registry used to close object/player
  snapshot coverage. Default object subclass scalar capture is centrally gated so
  broad object coverage does not require repeated leaf-object edits. See
  [Rewind System](rewind-system.md).
- `ZoneRuntimeRegistry` - typed per-zone runtime state adapters over raw event/state bytes
- `PaletteOwnershipRegistry` - palette-write arbitration, precedence, and underwater mirroring
- `AnimatedTileChannelGraph` - shared animated tile channels for script-driven and custom uploads
- `ZoneLayoutMutationPipeline` - deterministic queued/immediate live layout edits and redraw sequencing
- `SpecialRenderEffectRegistry` - staged additional render passes layered into the normal scene
- `AdvancedRenderModeController` - frame-level render-mode state such as per-line/per-cell scroll overrides

Related scroll/deform reuse lives in `level.scroll.compose`, centered on `ScrollEffectComposer`
and helper plans such as `DeformationPlan` and `WaterlineBlendComposer`.

## Current Migration Status

The runtime-owned framework stack is the preferred architecture, but migration is still partial:

- Sonic 2 uses it for HTZ/CNZ typed runtime state, palette ownership, animated tile orchestration, CNZ staged render effects (slot overlay), and CNZ layout edits queued through `ZoneLayoutMutationPipeline`.
- Sonic 3&K uses it for AIZ/HCZ/CNZ typed runtime state, AIZ staged render effects and advanced render modes (fire-transition and battleship overlays), HCZ/SOZ animated tiles, CNZ runtime-state-backed scroll behavior, and seamless terrain-swap writes routed through the mutation pipeline.
- Shared scroll/deform composition helpers are already live in the AIZ, HCZ, and MGZ handlers; prefer extending those helpers before copying bespoke scanline-fill logic into another zone.
- Other implemented zones still mix runtime-owned systems with older zone-local machinery. Before extending a zone, inspect whether it already has a typed runtime-state adapter, palette ownership integration, channel-graph usage, mutation-pipeline writes, scroll-composer usage, or render-registry wiring.

As a contributor, be aware that:

- New code should prefer receiving dependencies through method parameters or
  `ObjectServices` rather than calling static `getInstance()` methods.
- New zone behavior should prefer the runtime-owned framework stack over bespoke zone-local
  registries, buffers, or render-mode booleans.
- Some process-global `getInstance()` compatibility paths still exist for bootstrap and legacy tests, but they are not the current production style.

## Architecture Ratchets And Migration Sequence

The architecture guard suite is meant to make the preferred direction
incremental. Existing debt is frozen in explicit baselines or source-text
budgets, and new work should reduce those numbers rather than growing them.
Current source ratchets cover four high-pressure seams:

- `Engine` and `GameLoop` should be composition and mode-dispatch roots, not
  owners of concrete Sonic 1, Sonic 2, or Sonic 3&K behavior.
- `ObjectManager` should stay game-agnostic. Rewind recreation, dynamic children,
  and object lifecycle special cases should move through registered codecs,
  factories, or provider contracts instead of naming concrete Sonic objects.
- Low-level graphics and audio code should not look up gameplay-scoped services
  directly. Camera, fade, level, sprite, or gameplay state should arrive from the
  render/audio orchestration layer through explicit parameters or context objects.
- Large root dispatch methods in `Engine` and `GameLoop` should not grow. When a
  change touches one of those methods, prefer extracting a focused collaborator
  and lowering the documented budget.

The target architecture is:

1. `Engine` wires process services, window/runtime bootstrapping, and top-level
   mode transitions only.
2. `GameLoop` delegates each mode to provider-backed mode controllers or
   existing module interfaces.
3. Game modules own concrete game objects, art, save/data-select presentation,
   special/bonus-stage bootstrap details, and debug-only game-specific helpers.
4. Shared managers such as `ObjectManager` depend on shared lifecycle/profile
   contracts and registries, not concrete `game.sonic*` classes.
5. Graphics/audio infrastructure remains a lower layer; gameplay state is pushed
   to it by callers rather than pulled through `GameServices`.

Use this migration order when cleaning up a boundary:

1. Add or reuse a provider/registry contract at the current shared boundary.
2. Move one concrete Sonic dependency behind that contract without changing
   runtime behavior.
3. Run the focused architecture guard that owns the boundary.
4. If the count shrinks, lower the source-ratchet budget or frozen baseline in
   the same commit and update `docs/architecture/archunit-exceptions.md`.
5. Repeat with the next concrete dependency or oversized dispatcher block.

## Level Initialization: LevelInitProfile

Each game defines a `LevelInitProfile`: a declarative sequence of initialization steps
that run when a level loads. This replaced a monolithic `loadLevel()` method.

The steps (13 in total, defined by the `InitStep` record) include:

1. Load level layout data
2. Decompress tile art
3. Load chunk and block mappings
4. Set up collision arrays
5. Load object placement list
6. Configure palettes
7. Set up water (if applicable)
8. Initialize camera and scroll boundaries
9. Register zone-specific objects
10. Load PLCs (sprite art)
11. Configure level events
12. Set player start position
13. Initialize audio (zone music)

Each game's profile specifies which steps to run and in what order. Some steps are
shared across games; others are game-specific. The profile is defined in the game
module (e.g., `Sonic2LevelInitProfile`).

## Object Lifecycle

### Placement Data

Each act has an object placement list in the ROM: a sequence of records specifying
object ID, position, subtype, and render flags. These are loaded into `ObjectSpawn`
records when the level initializes.

### Spawning

The `ObjectManager` tracks which objects are in range. As the camera scrolls, objects
whose X position falls within the spawn window are created:

1. The `ObjectSpawn` record is passed to `ObjectRegistry.create(spawn)`.
2. The registry looks up the object ID and calls the registered factory function.
3. The factory creates and returns an `ObjectInstance` subclass (e.g.,
   `ArrowShooterObjectInstance`).
4. The instance is added to the active object list.

### Update Loop

Every frame, the engine calls `update(frameCounter, player)` on each active object.
This is the equivalent of the 68000 jumping to the object's routine entry point. The
object reads its state, makes decisions, updates its position, and prepares render
commands.

### Rendering

After all objects have updated, the engine collects render commands. Each object's
`appendRenderCommands(commands)` method adds GPU draw calls to a command list. The
commands are sorted by priority bucket and executed.

### Destruction

Objects mark themselves for removal through the lifecycle contract. New code should use
`ObjectLifetimeOps` or an existing `level.objects` compatibility wrapper so respawn
latches, dynamic expiry, and slot-transfer behavior stay consistent. Legacy code may still
call `setDestroyed(true)` directly; treat that as a migration target rather than a pattern
to copy. Common reasons:
- Off-screen cleanup (the `isOnScreen()` check, equivalent to `MarkObjGone`)
- Defeated badnik (after explosion animation)
- Collected item (ring, monitor)
- Projectile hit a wall

### Dynamic Objects

Objects created at runtime (projectiles, explosions, debris) are not part of the
placement list. New object code should use `spawnChild(...)`, `spawnFreeChild(...)`,
or another `level.objects` compatibility wrapper so construction context and lifecycle
semantics stay centralized. Direct `ObjectManager.addDynamicObject(obj)` is reserved
for documented bridge code and unusual allocation paths that cannot use the standard
helpers. Dynamic objects follow the same
update/render/destroy lifecycle but are not subject to camera-based spawn/despawn.

## Rendering Pipeline

The rendering pipeline is GPU-based (OpenGL 4.1 core profile). Contributors adding
objects or zones rarely need to interact with it directly.

**What you need to know:**

- **PatternSpriteRenderer:** The primary way objects draw themselves. Call
  `getRenderer(artKey)` to get a renderer for your object's art, then
  `drawFrameIndex(frame, x, y, hFlip, vFlip)` to draw a mapping frame.
- **Priority buckets:** Objects specify a priority via `getPriorityBucket()`. Lower
  numbers draw behind higher numbers. This matches the VDP's priority system.
- **Debug rendering:** Override `appendDebugRenderCommands(ctx)` to draw bounding boxes,
  sensor lines, or labels when the debug overlay is active.

**What you do not need to touch:**

- The pattern atlas (tile upload, GPU texture management)
- The tilemap shader (background plane rendering)
- FBO compositing (priority plane layering)
- The LWJGL/OpenGL layer

## Audio Pipeline

The audio system reimplements the SMPS (Sample Music Playback System) sound driver:

1. **AbstractSmpsLoader** (with per-game subclasses like `Sonic2SmpsLoader`) parses music
   and SFX data from the ROM using pointer tables.
2. **SmpsSequencer** processes sequence commands each frame: note on/off, volume changes,
   tempo, loops, modulation.
3. **Ym2612Chip** produces FM synthesis audio from register writes.
4. **PsgChip** produces PSG audio (square waves and noise).
5. **DacData** manages PCM drum sample data and playback rates.

This is a reimplementation, not a blanket register-write parity guarantee.
S3K coordinate flags are handled by `Sonic3kCoordFlagHandler`; meta commands
`SND_CMD`, `MUS_PAUSE`, and `COPY_MEM` are proven absent from the
S&K-loader-supported S3K music/SFX streams and both native SFX tables
(`33-DF`, 173 entries each). Strict full-bank traversal covers every native
root and frontier, including the differing `9B`/`AD` payloads and `DC-DF`
aliases. ROM type-check bytes prove S&K's `DC` CreditsK music special case and
S3's `DC-DF` SFX dispatch. Their handler cases consume operands only for
stream alignment and deliberately do not claim the native dispatch, all-track
halt, or shared-Z80-memory-copy semantics.
The ROM inventory and native contracts
are recorded in
`docs/architecture/research/audio/2026-08-08-s3k-smps-meta-command-reachability.md`;
future custom-stream support must implement these at the audio driver boundary.

Each game has a `SmpsSequencerConfig` that captures driver differences:
- **Tempo mode:** S3K uses OVERFLOW (overflow = skip), S2 uses OVERFLOW2 (overflow = tick).
- **Note mapping:** S1 uses a different base note than S2/S3K.
- **PSG envelopes:** Per-game envelope tables.
- **Operator order:** S1 uses a different FM operator ordering.

To play a sound effect from an object: `services().playSfx(SfxEnum.SOUND_NAME.id)`.

## Next Steps

- [Tutorial: Implement an Object](tutorial-implement-object.md) -- Apply this knowledge
- [Adding Bosses](adding-bosses.md) -- Boss-specific patterns
- [Adding Zones](adding-zones.md) -- Bringing up a new zone
- [Audio System](audio-system.md) -- Audio details for contributors
