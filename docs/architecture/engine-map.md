# Engine map

On-demand reference for how the runtime is organised. `CLAUDE.md` / `AGENTS.md` keep
only the load-bearing rules and gotchas; this file holds the structural detail you
need when you are actually working inside one of these subsystems.

Companion references:

- [Object implementation reference](object-implementation-reference.md) — objects, badniks, art loading, constants.
- [Headless testing](../guide/contributing/headless-testing.md) — `HeadlessTestRunner` setup and test infrastructure.
- [Per-game rule placement](per-game-rule-placement.md) — where a per-game behavioural difference belongs.
- [AGENTS_S3K.md](../../AGENTS_S3K.md) — Sonic 3&K specifics.

## Contents

- [Entry point and packages](#entry-point-and-packages)
- [Two-tier service architecture](#two-tier-service-architecture)
- [Session ownership](#session-ownership)
- [Level editor](#level-editor)
- [Runtime-shared framework stack](#runtime-shared-framework-stack)
- [LevelManager decomposition](#levelmanager-decomposition)
- [MutableLevel](#mutablelevel)
- [Consolidated subsystems](#consolidated-subsystems)
- [Multi-game support](#multi-game-support)
- [Per-game physics framework](#per-game-physics-framework)
- [Unified level event framework](#unified-level-event-framework)
- [Multi-sidekick system](#multi-sidekick-system)
- [Rewind framework](#rewind-framework)
- [Level resource overlay system](#level-resource-overlay-system)
- [Virtual pattern ID system](#virtual-pattern-id-system)
- [Data select and save system](#data-select-and-save-system)
- [Special stages](#special-stages)
- [Audio engine](#audio-engine)
- [Configuration and startup](#configuration-and-startup)
- [Tooling](#tooling)

## Entry point and packages

`com.openggf.Engine` — GLFW window with a manual timing loop (`display()` → `update()` → `draw()`).
Running the engine needs LWJGL (OpenGL/OpenAL/GLFW bindings) and JOML, both already in `pom.xml`.

Package names under `src/main/java/com/openggf` are mostly self-describing. The
non-obvious placements:

| Package / class | Note |
|---|---|
| `level.objects` | Hosts the `ObjectServices` **interface** (not under `game`), the unified `ObjectManager`, shared base classes, and utility helpers |
| `game` | Game-agnostic interfaces incl. `GameServices`, `PlayableEntity`, `DamageCause`. `DataSelectProvider` lives here even though the framework is under `game.dataselect` |
| `game.profiles.*` | Canonical cross-game object behaviour profiles. New solid / touch-response / lifecycle vocabulary belongs here, adapted by `level.objects` execution code — not in game-local profile types |
| `game.zone` / `palette` / `animation` / `mutation` / `render` | Runtime-owned shared frameworks |
| `game.rewind` | Keyframes, deterministic seek/replay, generic field capture, identity ids, policy registry, compact schema capture |
| `level.scroll.compose` | Shared deform/parallax composition around `ScrollEffectComposer` |
| `audio.*` | Split across `audio` (backend), `audio.synth` (chip emulation), `audio.smps` (sequencer/loader), `audio.driver`, `audio.runtime`, `audio.rewind`, `audio.debug` |
| `audio.synth` | `PsgChip` is the sole PSG core (clean-room SN76489 written from `docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md`; band-limited through the LGPL `BlipDeltaBuffer`); `Ym2612Chip` is a facade over the Nuked-OPN2 port in `audio.synth.nuked`, which is the sole FM core |
| `physics` | Sensors, terrain collision, unified `CollisionSystem` |
| `tools` | Compression utilities (Kosinski, Nemesis, Saxman), `ObjectDiscoveryTool`, disassembly tools incl. `RomOffsetFinder` |
| `LevelFrameStep` | Lives at the `com.openggf` package **root**, not under `level` |

## Two-tier service architecture

**Tier 1 — `GameServices` (static facade).** Global access for managers, event handlers,
and other non-object code. Exposes gameplay-scoped accessors (`camera()`, `level()`,
`parallax()`, `water()`, `gameState()`, `timers()`, `sprites()`, `fade()`, `collision()`, …)
which require an active `GameplayModeContext`, plus engine globals (`rom()`, `audio()`,
`graphics()`, `configuration()`, `module()`, `debugOverlay()`, …) resolved via
`EngineServices`, plus `*OrNull()` variants. See `GameServices.java` for the full surface.

**Tier 2 — `ObjectServices` (injected per-object).** Interface at
`com.openggf.level.objects.ObjectServices`; `DefaultObjectServices` is the production
implementation backed by `GameplayModeContext` and `EngineContext`. Objects receive it via
injection at construction (ThreadLocal context set by `ObjectManager`), and reach it with
`services()` — `objectManager`, `renderManager`, `audioManager`, `camera`, `gameState`,
`zoneFeatureProvider`, plus audio shortcuts, level-transition requests, world session, RNG,
ROM, config.

Objects must never call `getInstance()`; the `TestObjectServicesMigrationGuard` scanner
enforces it, and `TestNoServicesInObjectConstructors` ensures objects don't call
`services()` during construction.

## Session ownership

Gameplay state is split by lifetime across three layers in `com.openggf.game.session`:

- **`WorldSession`** — durable across editor mode swaps. Owns the active `GameModule`, the
  loaded `Level` (incl. `MutableLevel`), and zone/act metadata.
- **`GameplayModeContext`** — disposable, rebuilt on each gameplay session entry. Owns all
  gameplay-scoped managers (camera, timers, game state, fade, RNG, water, parallax,
  collision, sprites, level, …) plus the runtime-shared registries below. Provides
  `initializeFreshGameplayState()` for editor-exit counter reset.
- **`SessionManager`** — lifecycle (`openGameplaySession`, `enterEditorMode`,
  `resumeGameplayFromEditor`).

Editor entry/exit uses teardown+rebuild (no parking): the mode context is
destroyed/recreated while `WorldSession` survives, then `LevelManager.restoreInheritedLevel()`
reapplies any `MutableLevel` edits. The old `GameRuntime` / `RuntimeManager` façade is
retired — prefer explicit dependencies from `GameplayModeContext`, `GameServices`, or
`ObjectServices`. Full design:
`docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md`.

## Level editor

`com.openggf.editor` + `GameMode.EDITOR`: `LevelEditorController`, `EditorInputHandler`,
`EditorMouseTransform`, `EditorHistory` (+ `commands.*` undoable strokes),
`persistence.EditorSaveManager` / `EditorSaveEnvelope` / `EditorSavePayload`, and
`render.EditorToolbarRenderer`. `GameMode.EDITOR` is integrated into `Engine` and
`SessionManager`.

With `debug.flags.editor` enabled in `config.yaml`, toggle into edit mode mid-play, paint
chunks with the mouse, undo/redo strokes via `Block.saveState()` / `restoreState()`, and
persist edits through the editor save envelope. Enter/exit rides the teardown+rebuild
session path above. In-mode key/mouse bindings are hardcoded in `EditorInputHandler` — see
[CONFIGURATION.md](../../CONFIGURATION.md).

## Runtime-shared framework stack

`GameplayModeContext` hosts the shared registries/controllers that normalise zone-specific
behaviour across games (reached through `GameServices` or `gameplayMode.getX()`):

| Framework | Responsibility |
|---|---|
| `ZoneRuntimeRegistry` | Typed per-zone runtime state adapters over raw event/state bytes |
| `PaletteOwnershipRegistry` | Multi-writer palette arbitration, precedence, underwater mirroring |
| `AnimatedTileChannelGraph` | Shared animated tile channels for script-driven and custom uploads |
| `ZoneLayoutMutationPipeline` | Deterministic queued/immediate live layout edits and redraw sequencing |
| `ScrollEffectComposer` | Deform/parallax composition (with `DeformationPlan`, `WaterlineBlendComposer`) |
| `SpecialRenderEffectRegistry` | Staged additional render passes layered into the normal scene |
| `AdvancedRenderModeController` | Frame-level render-mode state such as per-line/per-cell scroll overrides |

Prefer plugging into these over new zone-local registries or one-off manager state.

Two ROM-state-driven hooks that exist specifically to avoid zone carve-outs:

- **Camera honours the ROM `Fast_V_scroll_flag`.** The vertical camera-tracking cap is
  raised when the player rides a platform that sets it (e.g. the ICZ path-follow / ridden
  moving platforms). Model the flag, not the zone.
- **`ZoneRuntimeState.requiresFullWidthBgTilemap()`.** `LevelTilemapManager` consumes this
  generic default method to decide on a full-width background tilemap instead of probing
  `GameStateManager` directly (introduced when the HTZ earthquake overlay migrated into
  `SpecialRenderEffectRegistry`). Express new such needs as `ZoneRuntimeState` predicates.

## LevelManager decomposition

`LevelManager` is a thin coordinator and public compatibility facade. Keep new behaviour in
the collaborator that owns it:

| Class | Responsibility |
|---|---|
| `LevelManager` | Thin coordinator, level load orchestration |
| `LevelTilemapManager` | Tilemap loading, chunk/block management, VRAM upload |
| `LevelRenderer` | Normal level, sprite/object, ending-background render passes |
| `LevelPlayableArtInitializer` | Player/sidekick renderer setup, DPLC bank reservation, dust/tail art |
| `LevelDirtyRegionDispatcher` | `MutableLevel` dirty-set consumption and mutation-effect dispatch |
| `LevelWaterCoordinator` | Water provider loading, dynamic water advancement, playable underwater state |
| `LevelCheckpointCoordinator` | Checkpoint/respawn state, checkpoint restore, rewind checkpoint capture |
| `LevelActTransitionExecutor` | ROM-aligned in-place act-transition reload choreography |
| `LevelLoadPreparer` | One level build running ahead of a seamless transition on a daemon thread; the install always joins it, so it changes timing only. Games opt in through `PreparableLevelLoader` (S3K: `Sonic3k.prepareLevelBuild`/`installPreparedLevel`) |
| `LevelTilemapPrebuilder` | Builds FG/BG tilemaps for a not-yet-installed level over its own `LevelGeometry.forLevel` and `LevelLayoutLookup.blockAt`; the live manager adopts them via `adoptPrebuiltTilemaps` and `swapToPrebuiltTilemaps` |
| `LevelLostRingSpawnCoordinator` | Lost-ring scattering, the deferred spawn queue, and its dynamic-slot reservations |
| `LevelTransitionCoordinator` | Transition request/consume state for acts, warps, title cards, respawns |
| `LevelDebugRenderer` | Debug overlay rendering (collision, chunks, paths) |
| `LevelGeometry` *(record)* | Immutable level dimension/boundary data |
| `LevelDebugContext` *(record)* | Snapshot of debug state for rendering |

Level loading reads from the ROM through classes in `com.openggf.data`.

## MutableLevel

`MutableLevel` (`com.openggf.level`) provides snapshot + mutation + dirty-region tracking
for level tile data, and is the foundation for the level editor. Undo/redo uses
`Block.saveState()` / `restoreState()`. Dirty regions are processed per-frame by
`LevelFrameStep.processDirtyRegions()`.

**Copy-on-write snapshot epoch:** `AbstractLevel.snapshotEpoch` + `cowEnsureWritable` clone
`Block.chunkDescs` / `Chunk.patternDescs` / `Map.data` on first write per epoch so rewind
snapshots stay isolated from later live edits. Integrated through
`DirectLevelMutationSurface.setBlockInMap`.

**Routing rule (enforced):** gameplay-path tile edits (code under `game/sonic1|2|3k`,
`level/objects`) must route through `ZoneLayoutMutationPipeline` / a `LevelMutationSurface`
— never a direct `getMap().setValue(...)`. `TestNoDirectMapMutationsInGameplay` enforces
this; editor commands and initial layout decoders (e.g. `Sonic3kLevel`) are exempt.

## Consolidated subsystems

- **`ObjectManager`** inner classes: `Placement` (spawn windowing), `SolidContacts`
  (riding/landing/ceiling/side collision), `TouchResponses` (enemy bounce/hurt),
  `PlaneSwitchers`. Injects `ObjectServices` into all objects at construction.
- **`RingManager`** inner classes: `RingPlacement` (collection state, sparkle, spawning),
  `RingRenderer` (cached pattern rendering), `LostRingPool` (lost ring physics).
- **`PlayableSpriteController`** (`sprites.playable`) coordinates `DrowningController`
  (same package) and three managers in `sprites.managers`: `PlayableSpriteMovement`
  (physics), `PlayableSpriteAnimation` (animation state), `SpindashDustController`.
- **`CollisionSystem`** (`com.openggf.physics`) orchestrates collision in phases: terrain
  probes via `TerrainCollisionManager` → solid object resolution via
  `ObjectManager.SolidContacts` → post-resolution ground mode / headroom checks. Supports
  trace recording via `CollisionTrace` (`RecordingCollisionTrace` / `NoOpCollisionTrace`).
- **`UiRenderPipeline`** (`graphics.pipeline`) enforces render order: Scene → HUD overlay →
  Fade pass. `Engine.display()` drives screen transitions through it. `RenderOrderRecorder`
  is available for tests.
- **`Sonic2LevelAnimationManager`** implements both `AnimatedPatternManager` and
  `AnimatedPaletteManager` via `Sonic2PatternAnimator` (uses `AniPlcParser` /
  `AniPlcScriptState`) and `Sonic2PaletteCycler`.
- **`CNZBumperManager`** unifies placement windowing and ROM-accurate bounce physics for
  all 6 bumper types.

## Multi-game support

Game-specific behaviour is isolated behind `GameModule`. `GameModuleRegistry` holds the
current module; `RomDetectionService` auto-detects the ROM type.

| Class / interface | Purpose |
|---|---|
| `GameModule` | Central interface defining all game-specific providers |
| `GameModuleRegistry` | Singleton holding the current game module |
| `RomDetectionService` | Auto-detects ROM type and sets the module |
| `RomDetector` | Per-game ROM detection logic |
| `ZoneRegistry` | Zone/level metadata (names, act counts, start positions) |
| `ObjectRegistry` | Object creation factories and ID mappings |
| `SpecialStageProvider` | Chaos Emerald special stage logic |
| `BonusStageProvider` | Checkpoint bonus stage logic (S3K) |
| `ScrollHandlerProvider` | Per-zone parallax scroll handlers |
| `ZoneFeatureProvider` | Zone-specific mechanics (CNZ bumpers, water) |
| `RomOffsetProvider` | Type-safe ROM address access |

`Sonic1GameModule`, `Sonic2GameModule`, and `Sonic3kGameModule` are all merged and
functional; see `GameModule.java` for the authoritative provider list.

**Adding a new game:** create the `GameModule` implementation and a `RomDetector`,
implement the required providers, register the detector in
`RomDetectionService.registerBuiltInDetectors()`, and add a `GameProfile` factory method in
`RomOffsetFinder.GameProfile`.

## Per-game physics framework

| Class | Purpose |
|---|---|
| `PhysicsProfile` | Immutable per-character movement constants (18 fields, subpixels where 256 = 1px) |
| `PhysicsModifiers` | Water / speed shoes multiplier rules (shared `STANDARD` across all games) |
| `CollisionModel` | Enum: `UNIFIED` (S1) vs `DUAL_PATH` (S2/S3K) |
| `PhysicsProvider` | Interface tying the above together, per game module |

**Resolution flow:** `AbstractPlayableSprite`'s constructor calls `defineSpeeds()` (S2
fallback values) → `resolvePhysicsProfile()` queries
`GameModuleRegistry.getCurrent().getPhysicsProvider()` → profile values overwrite
fallbacks, modifiers and typed `GameRules` are cached → getters apply modifiers dynamically
(water / speed shoes).

**Collision model.** S1 (`UNIFIED`): single collision index, solidity bits hardcoded per
routine, no dynamic path switching. S2/S3K (`DUAL_PATH`): dual collision indices (Primary
bits 0x0C/0x0D, Secondary bits 0x0E/0x0F), per-sprite `top_solid_bit` / `lrb_solid_bit`,
plane switchers dynamically swap collision paths. `setTopSolidBit()` /
`setLrbSolidBit()` on `AbstractPlayableSprite` **silently ignore calls under `UNIFIED`**, which
makes springs and plane switchers automatic no-ops for S1.

**Adding a difference:** identify it in the disassembly with exact ROM references, choose
the smallest accurate owner per [per-game-rule-placement.md](per-game-rule-placement.md),
gate at the owning call site (preserving existing fallback behaviour when the owner is
absent in tests), and add focused tests or trace replay coverage.

Physics tests live in `src/test/java/com/openggf/game/`: `TestPhysicsProfile`,
`TestPhysicsProfileRegression`, `TestSpindashGating`, `TestCollisionModel`.

## Unified level event framework

- **`AbstractLevelEventManager`** (`game/`) — shared state machine: dual routine counters
  (`eventRoutineFg` and `eventRoutineBg`; S1/S2 use only Fg, S3K uses both), zone/act
  tracking, `initLevel()` / `update()` lifecycle, boss spawn coordination.
- **`Sonic1LevelEventManager`** (`game/sonic1/events/`) — per-zone handler classes.
- **`Sonic2LevelEventManager`** (`game/sonic2/`) — HTZ earthquake, boss arenas,
  EHZ/CPZ/ARZ/CNZ events.
- **`Sonic3kLevelEventManager`** (`game/sonic3k/`) — per-zone handlers in
  `game/sonic3k/events/`: `Sonic3kAIZEvents`, `Sonic3kCNZEvents`, `Sonic3kHCZEvents`,
  `Sonic3kICZEvents`, `Sonic3kLBZEvents`, `Sonic3kMGZEvents`, `Sonic3kMHZEvents`. Other
  zones use default/no-op behaviour until implemented.
- **`PlayerCharacter`** enum (`game/`) — `SONIC_AND_TAILS`, `SONIC_ALONE`, `TAILS_ALONE`,
  `KNUCKLES`, matching the ROM's `Player_mode` for character-specific branching.

Each `GameModule` returns its subclass via `LevelEventProvider`; call sites use
`AbstractLevelEventManager` polymorphically.

## Multi-sidekick system

The engine extends the ROM's single CPU sidekick (Tails at `$FFFFB040`) to an arbitrary
number of sidekicks via comma-separated `characters.sidekick` in `config.yaml` (e.g.
`"tails,knuckles,sonic,sonic"`). This is a novelty feature, not present in any official
game.

| Class | Purpose |
|---|---|
| `SidekickCpuController` | Per-sidekick AI state machine (INIT, SPAWNING, APPROACHING, NORMAL, PANIC); holds `leader` for daisy-chain following and `getEffectiveLeader()` for chain healing |
| `SidekickRespawnStrategy` | Interface for per-character respawn behaviour during APPROACHING |
| `TailsRespawnStrategy` | Flies in from above (ROM-accurate). Default strategy |
| `KnucklesRespawnStrategy` | Glides in from screen edge, drops when X-aligned or after 3s timeout |
| `SonicRespawnStrategy` | Walks/spindashes in from nearest floor at screen edge; `requiresPhysics() = true` |
| `SpriteManager.getSidekicks()` | Ordered list of all CPU-controlled sidekicks |

**Daisy chain.** Each sidekick follows the one in front via a 17-frame position/input
history delay. `getEffectiveLeader()` uses a direct CPU leader immediately once that leader
is in NORMAL — the 15-frame `isSettled()` threshold is only for healing past broken or
not-yet-normal links, never for skipping a direct NORMAL leader while its history warms. If
a middle sidekick despawns before settling, the walk continues up to the nearest settled
leader (or the main player).

**VRAM banks.** Duplicate characters (e.g. multiple Sonics) need separate DPLC pattern
banks to avoid atlas corruption. Banks are allocated at `SIDEKICK_PATTERN_BASE` (`0x38000+`)
with a global running offset; tail appendages (Obj05) for duplicate Tails use `0x39000+`.

**Implementation details that bite:**

- `reset()` must preserve `leader` — it is a structural chain relationship, not per-level
  state. Nulling it permanently breaks the sidekick.
- Strategies relying on ground speed (Sonic) must return `true` from `requiresPhysics()` so
  `SpriteManager` doesn't skip the physics pipeline during APPROACHING.
- Only sidekick[0] receives Player 2 controller input.
- Respawn uses `getEffectiveLeader()` for both condition checks and approach targeting,
  enabling parallel respawn when all sidekicks despawn simultaneously.

## Rewind framework

Gameplay-scoped keyframe capture, deterministic seek/replay, held-rewind trace debugging,
and coverage audits: `RewindRegistry` / `RewindController` / `PlaybackController`. Coverage
spans core managers plus player, sidekick, object, ring, level, palette, parallax,
mutation, render-mode, and PLC progress state.

Automatic capture uses `GenericFieldCapturer`, `GenericRewindEligibility`,
`@RewindTransient` / `@RewindDeferred`, stable identity ids in
`com.openggf.game.rewind.identity`, and compact schema codecs/policies in
`com.openggf.game.rewind.schema` (`CompactFieldCapturer`, `RewindCodecs`,
`RewindPolicyRegistry`, `RewindSchemaRegistry`). Default non-badnik object subclasses use
compact schema-backed sidecar state when all default scalar fields have codecs. Prefer
central eligibility, codecs, and policy-registry rules over per-object annotations or
rewind overrides unless bespoke state genuinely requires it. The standalone
`RewindFieldInventoryTool` lives at `com.openggf.tools.rewind`.

**Object coverage guard.** `RewindCoverageAnalyzer` + the report-only
`TestRewindCoverageGuard` (vs `src/test/resources/rewind/coverage-baseline.txt`) flag any
spawnable object lacking a recreate path, holding an uncaptured `final` scalar, or holding
an object reference not captured as a rewind id. Run `RewindFieldInventoryTool --coverage`
for the report. A new gap fails the guard — fix coverage, or add its gap key to the
baseline if the gap is intentional.

**Static-state guard.** `StaticStateRewindCoverageAnalyzer` +
`TestStaticStateRewindCoverageGuard` (vs
`src/test/resources/rewind/static-state-coverage-baseline.txt`) catch the "level trigger"
bug class: a global static manager (mutable state + `reset()`) consumed by gameplay objects
across frames but never registered with the rewind registry, so a backward seek leaves it
stale while the consuming objects' own instance fields roll back correctly — the MGZ
dash-trigger / trigger-platform desync and its `ButtonVineTriggerManager` / HCZ water-skim /
tunnel / breakable-bar analogs. Fix by adding a `RewindSnapshottable` adapter (see
`Sonic3kLevelTriggerStaticAdapter`) and registering it via the owning
`AbstractLevelEventManager#extraRewindAdapters()`.

## Level resource overlay system

Some zones share level resources with overlays (e.g. HTZ shares base data with EHZ, then
applies HTZ-specific pattern/block overlays). Implemented in `com.openggf.level.resources`:

- `LoadOp` — a single load operation (ROM address, compression, dest offset)
- `LevelResourcePlan` — lists of `LoadOp`s for patterns, blocks, chunks, collision
- `ResourceLoader` — loading with overlay composition (copy-on-write)
- `Sonic2LevelResourcePlans` — factory for zone-specific resource plans

To add overlay support for another zone: add ROM offsets to `Sonic2Constants`, create a
plan in `Sonic2LevelResourcePlans`, and update `getPlanForZone()`.

`PlcParser` in `level.resources` provides game-agnostic PLC parsing — see the `plc-system`
skill (cross-game) and `s3k-plc-system` (S3K specifics).

## Virtual pattern ID system

The VDP uses 11-bit pattern indices (0x000–0x7FF, 2048 tiles). The engine extends this with
a **virtual pattern ID** space so multiple subsystems can cache patterns without colliding.
`PatternAtlas` uses a tiered lookup: a flat array (`fastEntries[8192]`) for dense low IDs
(level tiles) and a `HashMap<Integer, Entry>` for sparse high IDs.

Each category claims a non-overlapping base: level tiles at `0x00000`, special stages
per-game in `0x01000`–`0x10000`, objects at `0x20000`, HUD at `0x28000`, water surface at
`0x30000`, sidekicks at `0x38000+`, title cards at `0x40000` / `0x50000`, results/credits/
special UI in the higher ranges. The owning manager class holds the authoritative
`*_PATTERN_BASE` constant.

Key classes: `PatternAtlas` (storage), `DynamicPatternBank` (fixed-size bank for
DPLC-driven updates), `PlayerSpriteRenderer` (uses `renderPatternWithId()` to bypass the
11-bit limit in `PatternDesc`), and `GraphicsManager.renderPatternWithId(patternId, desc, x, y)`.

The range table and capacity limits are in
[Known discrepancies](../status/known-discrepancies.md).

The engine works in Mega Drive coordinates (Y increases downward);
`BatchedPatternRenderer` flips to the OpenGL convention automatically.

## Data select and save system

Full data select (save/load) with cross-game donation. `DataSelectProvider`
(`com.openggf.game`) holds the lifecycle states; `DataSelectSessionController` is the
presentation-independent state machine; each game implements `DataSelectHostProfile` (team
configs, slot counts, zone labels, restart destinations). S3K renders with
`S3kDataSelectManager`; S1/S2 route through `CrossGameDataSelectPresentations.donated(...)`
— there is no simplified fallback presentation. `SaveManager` (`game.save`) persists slots
as JSON with SHA256 integrity and quarantines corrupt files; in-game progression saves go
through `writeSlotAsync` (snapshot encoded on the frame, file written by the single
`save-writer` thread, flushed before any slot read/delete and at shutdown). Title-screen `ONE_PLAYER`
flows through `StartupRouteResolver` → `TitleActionRoute.DATA_SELECT` → controller →
`DataSelectAction` → `Engine.launchGameplayFromDataSelect()`.

## Special stages

S2 special stage code lives in `com.openggf.game.sonic2.specialstage`
(`Sonic2SpecialStageManager` plus track animator / decoder / loader / constants). The track
frame format, segment types, orientation triggers, and progression rules are documented in
those classes' Javadoc.

## Audio engine

Emulates Mega Drive sound hardware: **YM2612** (FM synthesis, 6 channels), **PSG/SN76489**
(square wave + noise, 4 channels), and the **SMPS driver** (Sega's sound format, with
`OVERFLOW` / `OVERFLOW2` / `TIMEOUT` tempo modes). Per-game audio data lives under
`game.sonicX.audio`. `audio.runtime` holds the deterministic FIFO/PCM ring buffers used by
gameplay rewind.

**Accuracy expectation:** implement features identically to hardware. The FM core is the
Nuked-OPN2 port (`audio.synth.nuked`, pinned by `tools/audio/nuked-opn2/PIN.md`); its only
reference is the pinned `ym3438.c`, and `Ym2612Chip` is engine glue over it (write pacing,
frame summation, resampling, DAC streaming, mutes, snapshot) pinned against the C build by
`TestYm2612ChipNukedParity`. For the PSG cross-reference the libvgm `emu/cores/sn76489*.c`
cores, and for the sequencer the SMPSPlay source, rather than simplified versions. Do not
"twiddle knobs" — diagnose against a source of truth.

Reference material: `docs/SMPS-rips/SMPSPlay/` (SMPSPlay source), `docs/SMPS-rips/` (ripped
audio data and SMPSPlay configurations), `docs/YM2612.java.example` (a Gens YM2612 port,
missing PCM functionality, possibly incorrect), and the provenance stubs for external
YM2612/SN76489/SMPS references under `docs/architecture/research/audio/` (each `.md` stub
names the original URL; the pages themselves are not tracked).

## Configuration and startup

`SonicConfiguration` / `SonicConfigurationService` (`com.openggf.configuration`) load
`config.yaml` and migrate a legacy `config.json` to YAML
on first run. Key bindings are stored as GLFW key-name strings (e.g. `"D"` /
`"GLFW_KEY_D"`) and resolved to integer key codes at lookup. Full key list:
[CONFIGURATION.md](../../CONFIGURATION.md).

Settings worth knowing:

- `startup.legalDisclaimer` (default `true`) — `Engine.init()` boots through
  `GameMode.LEGAL_DISCLAIMER` first. That screen owns a `FadeManager` reveal, a 5-second
  readability gate, and a fade-to-black on dismiss; control then chains into the
  master-title or direct-gameplay path inside `Engine.exitLegalDisclaimer()`. **Set this
  `false` in tests that boot the full `Engine`.**
- `debug.testMode.enabled` — replaces the master-title game-select with a trace picker
  (dev-only; requires `debug.testMode.catalogDir`).
- `debug.testMode.catalogDir` — directory scanned by `TraceCatalog` (default
  `src/test/resources/traces`).
- `debug.flags.debugView` (default `false`) — runtime debug shortcuts and overlay
  rendering, incl. sensor and collision info during gameplay.
- `debug.flags.editor` — enables the in-engine level editor toggle.

## Tooling

### RomOffsetFinder

`com.openggf.tools.disasm.RomOffsetFinder` searches disassembly items, calculates ROM
offsets, verifies them against ROM data, and exports verified results as Java constants.
Requires a disassembly tree under `docs/s1disasm/`, `docs/s2disasm/`, or `docs/skdisasm/`.

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=<command>" -q
```

Commands: `search <pattern>`, `verify <label>`, `verify-batch [type]`, `list [type]`,
`test <offset> <type>`, `export <type> [prefix]`, `search-rom <hex> [start] [end]`,
`plc <name>`. Status codes: `[OK]` match, `[!!]` mismatch, `[??]` not found, `[ER]` error.
Pass `--game s1|s2|s3k` (auto-detected from the disasm path otherwise).

Per-game quirks: **S1** uses `bincludePalette` directives and `sonic.asm`; most object
mappings are inline `spritePiece` macros. **S2** is the default profile, uses `s2.asm` and
the `palette` macro (expanded to `art/palettes/`). **S3K** uses `sonic3k.asm` and
`binclude` for palettes, and encodes compression type in the label suffix (`_KosM`, `_Kos`,
`_Nem`) since files use a `.bin` extension — the tool auto-infers.

Search results for art labels list which PLCs reference that art; `plc <name>` dumps a PLC
definition's art entries. Permanent anchor offsets live in the
`GameProfile.sonic1()` / `sonic2()` / `sonic3k()` factory methods; verified offsets are
added as runtime anchors during a session. A programmatic API is available in
`com.openggf.tools.disasm` (`RomOffsetFinder`, `DisassemblySearchTool`,
`ConstantsExporter`). The `s1disasm-guide` / `s2disasm-guide` / `s3k-disasm-guide` skills
cover full per-game usage.

### Agent workflow CLIs

Five `com.openggf.tools` CLIs reduce context loss when implementing objects/zones/trace
fixes: `AgentWorkflowTool` (object-task preflight checklist), `RomArtIntakeTool` (S3K
ROM-backed art/mapping/PLC intake), `ObjectScaffoldTool` (guard-friendly object/badnik +
test skeleton), `TraceTriageTool` (first-divergence brief from a trace report), and
`ZoneSpecNormalizerTool` (normalises a zone-analysis spec). Exact invocations, per-task
runbooks, the CI guard-failure explainer, the pitfall index, the documentation-obligation
checklist, and delegation prompt templates:
[docs/agent-workflow/README.md](../agent-workflow/README.md).

`ObjectDiscoveryTool` (`com.openggf.tools`) enumerates placed objects; for S3K it uses
composite `"objectId:name"` keys so same-ID-different-name objects across zone sets get
separate entries.
