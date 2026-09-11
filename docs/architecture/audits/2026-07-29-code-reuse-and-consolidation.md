# Code Reuse and Consolidation Audit

**Date:** 2026-07-29
**Scope:** `src/main/java`, `src/test/java`, architecture guards, and current
architecture guidance on `develop` at `eb1b138c4`.

## Executive summary

OpenGGF has already made several sound consolidation choices: game differences
generally enter through `GameModule` providers and typed rule records; object
services are injected; level mutation, palette ownership, animation channels,
render effects, trace replay, and headless integration testing all have shared
frameworks. The principal reuse problem is no longer an absence of abstractions.
It is that several successful abstractions have become broad aggregation points
or coexist with legacy paths that bypass them.

The highest-value consolidation work is:

1. finish migrations into existing shared seams, especially
   `HeadlessTestFixture`, typed module providers, and runtime-owned registries;
2. replace class-key and static service lookup with lifecycle-specific typed
   ports;
3. make session/runtime state have one authoritative owner instead of mirroring
   it in `LevelManager`;
4. extract small, parity-preserving execution kernels from demonstrably parallel
   cross-game implementations; and
5. decompose the largest runtime classes by responsibility while preserving
   frame order exactly.

This is not a recommendation for a broad framework migration. The safest route
is a sequence of narrow consolidations that remove a known duplicate path or
dependency edge and are justified by ROM evidence. Full inheritance hierarchies
for superficially similar game objects would make accuracy harder to audit.

## Evidence and method

The audit combined source inspection, structural counts, existing ArchUnit
ratchets, and comparisons across game implementations and test infrastructure.
No runtime behavior was changed.

Current structural indicators include:

| Indicator | Current evidence | Interpretation |
|---|---:|---|
| Core runtime cycle | 16 top-level slices, 122 set-ratcheted edges | Package ownership remains highly interdependent |
| Shared-to-game-specific dependencies | 20 frozen violations | Shared layers still construct or know concrete game types |
| Cross-game package dependencies | 37 frozen violations | Reuse exists, but some of it crosses the wrong boundary |
| Low-level-to-runtime dependencies | 209 frozen violations | Audio, graphics, and data are not yet reusable lower layers |
| `GameModule` | 601 lines, about 65 declared methods | The main extension point has become a provider and lifecycle aggregate |
| `LevelManager` | 3,793 lines | The documented thin coordinator is still a major behavior and state owner |
| `ObjectManager` | 4,678 lines | Extracted collaborators coexist with scheduling, rendering, identity, and rewind ownership |
| `AbstractPlayableSprite` | 5,293 lines | Native state, capabilities, physics, presentation, and compatibility remain coupled |
| `SidekickCpuController` | 5,891 lines | A parity-sensitive state machine has accumulated too many responsibilities |
| `GameLoop` / `Engine` | 4,490 / 2,968 lines | Root dispatch still contains game and mode choreography |

The existing frozen ArchUnit rules and explicit set-based edge ratchets are
useful: they prevent architectural debt from growing. They should now be treated
as a measurable consolidation backlog. A frozen violation or allowlisted edge is
containment, not completion.

## What should remain shared

Several existing abstractions are good reuse boundaries and should be extended
instead of replaced:

- `GameRules` and the provider/profile decision tree keep differences at the
  smallest accurate owner.
- `ObjectServices` is the correct object-instance dependency boundary.
- `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`,
  `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`,
  `SpecialRenderEffectRegistry`, and `ScrollEffectComposer` are appropriate
  runtime-owned composition points.
- `HeadlessTestFixture`, `SharedLevel`, and `AbstractTraceReplayTest` encode
  ordering that individual tests should not recreate.
- `RecordingFrameDriver` is a strong shared execution seam across headless
  tests, trace capture, and benchmarks.
- `CommonPlacementParser` correctly shares the S2/S3K binary commonality while
  leaving S1's materially different layout local.
- Object profiles and rule records are preferable to game-name branches in
  shared code.

## Prioritized findings

### Long-term, route-triggered. Make level/session state single-owned

`LevelManager` is described as a thin coordinator, but it mirrors the loaded
level and zone/act state held by `WorldSession` and still owns frame scheduling,
render dispatch, checkpoints, transitions, and reset choreography. Its extracted
collaborators frequently depend back on the manager, so extraction has reduced
file-local code without establishing a clean ownership direction.

**Evidence**

- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/game/session/WorldSession.java`
- `src/main/java/com/openggf/LevelFrameStep.java`
- `src/main/java/com/openggf/level/LevelRenderer.java`
- `src/main/java/com/openggf/level/LevelCheckpointCoordinator.java`
- `src/main/java/com/openggf/level/LevelActTransitionExecutor.java`

**Consolidation direction**

Separate state by lifetime before moving ownership:

- `WorldSession` may remain authoritative only for durable world identity,
  loaded layout, and state intentionally preserved across editor/runtime swaps;
- a gameplay-runtime-epoch owner should hold transition, checkpoint, rewind,
  and frame state that must be recreated with `GameplayModeContext`; and
- rewind snapshots must name which durable and epoch-owned values they capture
  and restore.

Pass narrow live state views and ports to level collaborators. Move remaining
frame choreography into `LevelFrameStep`; retain `LevelManager` temporarily as
load coordinator and compatibility facade. Specify creation, editor re-entry,
act-transition, reset, and rewind boundaries for each state cluster before
moving it.

Start with one mirrored state cluster, not a rewrite. Add a guard that prevents
new state mirrors before moving transition or rewind-sensitive state. Undertake
this work only when it removes an active route/release blocker or when nearby
S3K work already crosses the relevant ownership boundary.

**Benefit:** removes synchronization invariants and makes collaborators
independently testable.
**Risk:** very high. Act transitions, rewind boundaries, and object update order
are trace-sensitive.

### Long-term, route-triggered. Decompose runtime aggregate roots around explicit pipelines

`ObjectManager`, `AbstractPlayableSprite`, `PlayableSpriteMovement`, and
`SidekickCpuController` remain very large even after helper extraction. The
issue is not repeated syntax alone: state storage, execution order, rendering,
identity, rewind, and compatibility APIs still share owners.

**Evidence**

- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
- `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java`
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

**Consolidation direction**

Characterize the ROM phase boundaries and define explicit live state views or
ports before moving behavior. A value may be snapshotted only where the ROM
samples it at a known boundary; state written by one phase and observed later in
the same frame must remain live. For objects, separate an
`ObjectExecutionPipeline`, render index/command builder, and rewind/identity
store. For playable sprites, separate native state storage from movement and
ability execution. Preserve the current owner as a forwarding facade until
focused traces prove equivalent scheduling.

This is decomposition, not a shared base-class campaign. Behavior should move
only when its inputs, outputs, and exact phase are explicit.

This is not a current standalone priority. Apply it opportunistically when it
directly unlocks the S3K vertical slice or makes a route fix safer; otherwise
prefer the lower-risk consolidation work below.

**Benefit:** smaller blast radius for parity work and clearer reuse seams.
**Risk:** very high; extraction can change observable ordering without changing
individual algorithms.

### P1. Replace layered service locators with typed lifecycle ports

There are three overlapping service access mechanisms:

- static `GameServices`, with required and nullable variants;
- injected `ObjectServices`; and
- class-key lookup through `GameModule.getGameService(Class<T>)` and
  `ObjectServices.gameService(Class<T>)`.

Static access remains common in game-local managers, and class-key registration
is an untyped escape hatch that hides dependencies and lifetimes.

**Evidence**

- `src/main/java/com/openggf/game/GameServices.java`
- `src/main/java/com/openggf/game/GameModule.java`
- `src/main/java/com/openggf/level/objects/ObjectServices.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- the three `Sonic*GameModule` class-key switchboards

**Consolidation direction**

Keep `GameServices` at composition roots and legacy adapters. Add typed,
lifecycle-specific ports for the small set of services currently reached by
class key, then prohibit new `getGameService` registrations. Inject those ports
into managers that execute every frame. Do not grow `ObjectServices` into a
second `GameModule`.

**Benefit:** compile-time ownership and fewer null/fallback code paths.
**Risk:** medium-high because tests and standalone construction paths currently
rely on global fallbacks.

### P1. Split `GameModule` into cohesive facets

`GameModule` combines object/art factories, physics and rules, screens and
minigames, debug providers, save/data-select behavior, cross-game donation,
level lifecycle hooks, runtime art, and arbitrary services. The three module
implementations repeat provider storage, lazy construction, passthrough
getters, data-select cache setup, and donor capability plumbing.

**Evidence**

- `src/main/java/com/openggf/game/GameModule.java`
- `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`

**Consolidation direction**

Introduce cohesive typed facets, for example object runtime, level runtime,
presentation, and cross-game donation. `GameModule` may remain the composition
API during migration, but consumers should depend on a facet. Extract invariant
construction/cache plumbing into small factories rather than a behavior-heavy
abstract base module.

`applySeamlessMutation(LevelManager, ...)` should consume a mutation surface or
pipeline, not the level facade.

**Benefit:** prevents the universal module dependency from spreading and makes
provider lifetimes explicit.
**Risk:** medium-high; provider caching and reset semantics vary and must not be
silently normalized.

### P1. Finish the runtime-registry migration

`ZoneFeatureProvider` spans lifecycle, physics phases, water, collision
capabilities, direct rendering, and registration into the runtime render
registries. Palette cyclers and `DefaultObjectServices` can also construct
fallback runtime registries for standalone paths. These are parallel ownership
routes around the runtime-owned framework stack.

**Evidence**

- `src/main/java/com/openggf/game/ZoneFeatureProvider.java`
- `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2PaletteCycler.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- the “runtime-owned registries” frozen ArchUnit rule

**Consolidation direction**

Split zone behavior into phase-specific contributors registered at level load:
physics hooks, water state/data, layout mutation, and render effects. Existing
registries remain owners. Encode phase and priority explicitly; generic
callbacks would conceal ROM ordering. Replace ad hoc fallback registries with
explicit test/standalone compositions.

**Benefit:** one route for each runtime concern and less provider accretion.
**Risk:** high because phase ordering is gameplay state.

### P1. Complete the shared headless-test path

`HeadlessTestFixture.builder()` is already used broadly, but significant manual
bootstrap remains. The repeated sequence includes singleton reset, headless
graphics initialization, sprite/team setup, level load, ground sensor wiring,
camera initialization, event initialization, and optional ground snap.

**Evidence**

- `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- `src/test/java/com/openggf/tests/SharedLevel.java`
- 208 test files use `HeadlessTestFixture.builder()`
- 20 files call `GroundSensor.setLevelManager(...)` without mentioning the
  fixture
- 139 files contain `TestEnvironment.resetAll()`
- 203 files directly initialize headless `GraphicsManager`

These are source-file counts from `rg -l` on the cited calls; they overlap and
do not imply that every occurrence is a complete duplicated gameplay bootstrap.

**Consolidation direction**

Add only the missing narrow fixture modes or hooks—such as existing-level,
visual-only, or no-ground-snap—and migrate integration tests that recreate the
full ordering. Do not force renderer-only unit tests through a gameplay fixture.
Once the integration-test island is small, add a source guard against manual
full bootstrap.

**Benefit:** removes fragile setup variants and makes production/test ordering
more consistent.
**Risk:** medium; the fixture's team-before-load, S3K player-state, sidekick
bounds, and ground-snap ordering are deliberate.

### P1. Consolidate ROM location and validation policy

ROM discovery is duplicated across test utilities, JUnit conditions, production
ROM management, trace tools, object discovery, art intake, and offset tooling.
Property names and validation behavior vary, which makes a developer tool or
test capable of resolving a different file than the runtime.

**Evidence**

- `src/test/java/com/openggf/tests/RomTestUtils.java`
- `src/test/java/com/openggf/tests/rules/RomCache.java`
- `src/test/java/com/openggf/tests/rules/RequiresRomCondition.java`
- `src/main/java/com/openggf/data/RomManager.java`
- `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- `src/main/java/com/openggf/tools/ObjectDiscoveryTool.java`
- `src/main/java/com/openggf/tools/disasm/RomOffsetFinder.java`

**Consolidation direction**

Create a game-keyed `RomLocationResolver` that returns path plus provenance and
can apply an expected fingerprint policy. Keep consumers responsible for
failure semantics: JUnit may skip, command-line tools should fail clearly, and
`RomCache` should retain ownership of mutable/closeable cached ROM objects.

**Benefit:** one compatibility policy for properties, environment, config, and
defaults.
**Risk:** medium; a shared resolver must not silently accept the wrong game or
collapse distinct skip/fail behavior.

### P2. Extract the clearest low-risk duplicate kernels

The following are suitable early consolidation candidates because their common
algorithm is narrow and their variations can be explicit data or callbacks:

| Candidate | Repeated implementations | Recommended shared seam | Risk |
|---|---|---|---|
| ROM header detection | `Sonic1RomDetector`, `Sonic2RomDetector`, `Sonic3kRomDetector` | descriptor/template for normalized names, predicate, priority, and module factory | Low |
| Trace gzip/plain opening | `TraceData`, `TraceCatalog`, several replay tests | neutral `TraceIo.openUtf8Reader(Path)` | Low |
| HUD static mappings | three `*HudStaticArtFactory` classes | immutable layout/palette profile plus shared mapping builder | Low-medium |
| Ring art assembly/cache | three `*RingArt` classes | shared cache/sheet template with injected ROM/frame source | Medium |
| BK2 frame injection | three special-stage replay harnesses and run-chain tests | `RecordedInputCursor` or scoped logical-input override | Medium |
| CLI entry convention | several tools parse, print, and exit independently | `run(args, out, err) -> exitCode`; `main` only exits | Low-medium |

These extractions should each delete a duplicate implementation and gain
cross-game contract tests. They should not introduce a broad utility class.

### P2. Share fixed-air countdown machinery behind an exact profile

The S1, S2, and S3K fixed-air countdown managers repeat the same broad native
state machine: install while in water, routine transition, 59-frame cadence,
air decrement events, number-bubble latching, player lookup, and rewind state.

**Evidence**

- `src/main/java/com/openggf/game/sonic1/events/Sonic1FixedAirCountdownManager.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2FixedAirCountdownManager.java`
- `src/main/java/com/openggf/game/sonic3k/S3kFixedAirCountdownManager.java`

**Consolidation direction**

Extract a `FixedAirCountdownController` kernel with a per-game profile for native
field layout, RNG consumption, allocation failure, P1/P2 ownership, and bubble
spawn mechanism. The profile must express differences rather than defaulting
them away.

**Benefit:** one well-tested execution algorithm and rewind contract.
**Risk:** high; RNG consumption, RAM offsets, and slot failure affect traces.

### P2. Consolidate mechanics as executors, not object inheritance

Springs and results screens contain repeated mechanics but also important ROM
differences. Existing shared helpers have not captured all common state
transitions.

**Evidence**

- three game-local spring object implementations plus `SpringBounceHelper` and
  `SpringHelper`
- three results-screen object implementations plus `AbstractResultsScreen`

**Consolidation direction**

For springs, extract a launch executor for common position nudge, velocity,
player-state clearing, animation, sound, and collision-layer operations; retain
game-local contact detection and dispatch order. For results, first share digit
pattern writing and result-element rendering primitives, not the state machine.

**Benefit:** removes repeated mechanics without hiding game-specific control
flow.
**Risk:** high; S1 collision behavior, S2 perfect bonus, and S3K transition/art
queue timing are intentional differences.

### P2. Remove concrete game construction from shared object code

`DefaultPowerUpSpawner` imports and constructs concrete S1 and S3K object
classes. This is an acknowledged shared-to-game-specific layering violation and
one source of the frozen ArchUnit baseline.

**Evidence**

- `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java`
- `src/main/java/com/openggf/game/GameModule.java`
- the “shared code should not construct concrete Sonic provider classes” guard

**Consolidation direction**

Supply a typed `PowerUpObjectFactory` through module/donor composition. The
shared spawner should own slot and rewind registration, while the factory owns
semantic shield/splash object creation. Preserve fixed-slot and donor rules as
typed behavior, not game-name switches.

**Benefit:** removes a concrete package dependency and clarifies donation.
**Risk:** medium; factory ownership must not split lifecycle and rewind
registration.

### P3. Remove root/debug dependency leaks

Debug render and movement code still read static `Engine` state. This couples
headless lower layers to the entry point and adds edges to the core runtime
cycle.

**Evidence**

- `src/main/java/com/openggf/level/LevelDebugRenderer.java`
- `src/main/java/com/openggf/sprites/managers/DebugSpriteMovementManager.java`

**Consolidation direction**

Inject a small debug selection/command port from the debug composition root.

**Benefit:** easier headless isolation and one fewer dependency direction.
**Risk:** low.

## Recommended sequence

1. **Ratchet new growth.** Prohibit new class-key game services, new concrete
   game imports in shared code, and new manual full headless bootstraps.
2. **Take low-risk deletion wins.** Consolidate trace I/O, ROM detectors, HUD
   mapping builders, and debug selection state.
3. **Unify ROM resolution and headless setup.** These reduce friction and risk
   for every later parity migration.
4. **Introduce typed module facets and a power-up object factory.** Use them to
   shrink the 20 shared-to-game and 37 cross-game frozen baselines.
5. **Finish runtime-registry ownership.** Move zone feature callbacks and
   fallback registries onto explicit compositions.
6. **When an active S3K route or release blocker crosses the boundary, establish
   single-owned level state.** Move one lifetime-classified state cluster at a
   time and verify trace ordering.
7. **Extract parity-sensitive kernels.** Fixed-air countdown, spring launching,
   ring art assembly, and results rendering follow only with cross-game source
   and trace characterization.
8. **Decompose aggregate roots opportunistically.** Make each route-motivated
   extraction reduce a named responsibility and a dependency/size ratchet; do
   not use line count alone as the goal.

## Success measures

A consolidation slice should improve at least one observable measure without
regressing parity:

- fewer frozen ArchUnit violations or core-runtime dependency edges;
- fewer consumers of `GameServices` or class-key `getGameService`;
- fewer manual headless bootstrap sites;
- deletion of one complete duplicate algorithm;
- one authoritative owner for migrated state;
- reduced public surface of `GameModule`, `LevelManager`, or another aggregate;
- unchanged affected cross-game characterization and trace-replay results on
  JDK 21, with each slice naming its focused classes and the baseline/development
  comparison command; run the full `*TraceReplay` sweep only where the changed
  ownership or scheduling surface requires it.

Line-count reduction is supporting evidence, not a success criterion. A smaller
file that merely delegates through another broad facade has not improved the
architecture.

## Areas intentionally not recommended for consolidation

- Do not merge S1 object/ring placement parsing into the S2/S3K parser; their
  binary and respawn semantics differ.
- Do not introduce shared code that branches on game or zone identity.
- Do not unify whole spring, results-screen, title-card, or special-stage state
  machines merely because their class shapes are similar.
- Do not move ROM asset bytes into shared source resources; runtime assets
  remain ROM-backed and provider-owned.
- Do not create a second fixture hierarchy, registry stack, or broad feature
  bag. Consolidation should finish or narrow the existing abstraction.

## Conclusion

OpenGGF's reuse architecture is directionally sound but incomplete. The project
would gain more from closing legacy bypasses and clarifying ownership than from
inventing another cross-game superclass. The best program is “one owner, one
path, explicit profile”: use one authoritative state owner, route each concern
through one existing framework, and share only the algorithm whose ROM-varying
inputs are explicit.
