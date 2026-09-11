# S3K Zone Runtime Framework Design

**Date:** 2026-04-12

## Goal

Define a state-first runtime architecture for S3K zone systems that reduces ambiguity across events, scroll, animated tiles, palette writes, layout mutation, and special render effects, while staying compatible with existing Sonic 3&K code and reusable by Sonic 2 and selected Sonic 1 subsystems through adapters.

## Problem

The current S3K implementation already has meaningful zone support, but its behavior is fragmented across:

- zone event handlers
- scroll handlers
- pattern animation
- palette cycling
- zone feature render hooks
- ad hoc layout mutation helpers

That fragmentation is visible in both the implementation and the analysis docs. Many of the hardest zones, especially HCZ, LBZ, SOZ, and AIZ transitions, depend on cross-system sequencing that is currently implicit rather than modeled.

This creates three recurring problems:

1. the same zone state is represented as unnamed event bytes, helper fields, and transient render decisions in multiple places
2. palette, pattern, and layout mutations are performed by multiple writers without clear ownership
3. adding a new zone or deepening an existing one tends to duplicate framework logic instead of reusing it

The goal of this design is not to flatten the games into one generic system. The goal is to create a small set of runtime frameworks that can host the complexity explicitly while preserving ROM-faithful behavior and frame order.

## Design Principles

### 1. State First

Named runtime state comes before generalized rendering or animation frameworks. If the engine does not know what state a zone is in, new visual abstractions will only reorganize hardcoded behavior.

### 2. Runtime Ownership Over Manager Sprawl

The new framework registries should be owned by gameplay runtime objects, not hidden inside `LevelManager` or spread across per-game managers.

### 3. Adapter-First Migration

Existing zone code stays authoritative at first. New systems are introduced as adapters around current implementations before any wholesale migration.

### 4. Per-Game Symmetry Is Not Required

S3K, S2, and S1 do not need to consume all new frameworks at the same depth. The design should support partial adoption where that is the right technical choice.

### 5. Frame Order Is Sacred

Any new system that changes ownership or sequencing must preserve the exact order in which events, scroll state, animated tiles, palette writes, layout mutation, and render hooks occur.

## Proposed Architecture

The new architecture is built around five cooperating subsystems.

### 1. TypedZoneRuntimeState

`TypedZoneRuntimeState` wraps anonymous per-zone event storage into named views that are specific to a zone and act.

Examples:

- `AizRuntimeState`
- `HczRuntimeState`
- `LbzRuntimeState`
- `SozRuntimeState`

This layer does not replace ROM-faithful event bytes internally. It wraps them so the rest of the engine consumes named state instead of raw offsets like `Events_bg+$10`.

Responsibilities:

- expose named zone state fields and derived values
- define zone-runtime lifecycle and reset semantics
- centralize per-zone state that is currently split across event handlers, scroll handlers, and helper classes
- provide adapter views for older games where state is fragmented but still meaningful

Non-responsibilities:

- rendering
- direct palette uploads
- direct pattern uploads
- performing layout mutation

### 2. AnimatedTileChannelGraph

`AnimatedTileChannelGraph` normalizes animated-tile behavior into runtime channels rather than forcing all logic into either raw AniPLC scripts or custom imperative update code.

Each channel definition may include:

- phase source
- mask or modulo behavior
- source art data
- split tables or remap tables
- VRAM destination
- guard condition
- optional repair or follow-up upload

The graph must support both:

- declarative script channels such as standard AniPLC behavior
- imperative/custom channels such as HCZ waterline swaps or split DMA-style updates

This allows one framework to host:

- standard scripted animation
- scroll-derived dynamic animation
- multi-channel zone-specific animation

### 3. PaletteOwnershipRegistry

`PaletteOwnershipRegistry` formalizes palette writes as named channels with explicit arbitration.

Each palette channel should declare:

- target palette line and color range
- condition or guard
- priority or ownership rules
- mirror targets, including underwater variants where needed
- whether it replaces, blends, or temporarily overrides the target range

This addresses the current problem where palette writes can be spread across:

- palette cyclers
- event handlers
- objects
- direct level helpers
- underwater sync logic

The registry should make ownership conflicts visible instead of letting them remain accidental.

### 4. ZoneLayoutMutationPipeline

`ZoneLayoutMutationPipeline` provides a structured path for runtime tile/layout changes using existing mutable-level infrastructure.

Each mutation sequence should separate:

- mutation intent
- concrete layout edits
- redraw invalidation
- collision or plane ownership changes
- completion/finalization

This framework builds on current `MutableLevel` and tilemap dirty-region support rather than replacing it.

### 5. SpecialRenderEffectFramework

`SpecialRenderEffectFramework` hosts transient zone-level render behaviors that are currently scattered through scroll handlers, feature providers, or event code.

Examples include:

- Window-plane style scene tricks
- per-line or cell-based special scroll modes
- transient plane overlays
- render-pass-specific effect draws
- scene-state-driven background effect rendering

This framework should not absorb ordinary per-zone drawing. It exists for special display orchestration that crosses normal subsystem boundaries.

## Runtime Placement

The correct home for the new registries is the gameplay runtime layer.

Primary hosts:

- `GameRuntime`
- `RuntimeManager`
- per-level state creation via `GameModule.createLevelState(...)`

This is preferable to putting new ownership inside `LevelManager` because:

- runtime already owns mutable gameplay state
- it gives clear lifecycle boundaries
- it allows per-game or per-zone adapters without inflating `LevelManager`

The runtime layer should own:

- typed zone state instances
- palette ownership registry
- special-effect registry or dispatcher
- optional animated-tile channel graph state

`LevelManager` remains the coordinator that invokes these systems in the correct order.

## Frame Order Contract

The architecture requires one explicit per-frame ordering model:

1. zone events update authoritative gameplay state
2. typed zone state view is refreshed or derived
3. scroll/effect channels compute derived visual state
4. animated tile channels update pattern targets
5. palette ownership resolution computes final palette writes
6. layout mutation pipeline commits map changes and redraw invalidation
7. render hooks and special effects execute

This is the most important safety rule in the design.

The new systems must not invent parallel owners that update in different places. They must fit into one deterministic frame pipeline.

## Integration With Existing Engine Seams

The engine already has several strong foundations.

### Runtime And Level State

Good existing seams:

- `GameRuntime`
- `RuntimeManager`
- `GameModule.createLevelState(...)`

Needed change:

- widen level-state creation into a zone-runtime bundle or companion object that can host typed state and framework registries

### Layout Mutation

Good existing seams:

- `MutableLevel`
- `LevelManager.processDirtyRegions()`
- `LevelTilemapManager.rebuildDirtyRegions(...)`

Needed change:

- move from ad hoc mutation callers toward a formal mutation pipeline with named stages and better partial rebuild behavior

### Scroll And Channel Derivation

Good existing seams:

- `ParallaxManager`
- `ZoneScrollHandler`
- `AbstractZoneScrollHandler`

Needed change:

- treat them as consumers or producers of normalized effect/channel state rather than forcing all logic into bespoke handlers

### Palette Upload

Good existing seams:

- `GraphicsManager.cachePaletteTexture(...)`
- underwater palette upload helpers

Needed change:

- introduce a central ownership registry before upload so GPU cache updates are downstream effects, not the place where ownership is decided

### Render Hooks

Good existing seams:

- `ZoneFeatureProvider` render hooks
- scene-pass boundaries in `LevelManager`

Needed change:

- extract a formal effect framework so render-time special behavior is not just more ad hoc provider branching

## Compatibility And Retrofit Findings

### Sonic 3&K

S3K is the primary proving ground.

Current fit:

- `TypedZoneRuntimeState`: medium to high depending on zone
- `AnimatedTileChannelGraph`: medium
- `PaletteOwnershipRegistry`: low to medium
- `ZoneLayoutMutationPipeline`: medium to high
- `SpecialRenderEffectFramework`: medium to high

Strongest retrofit anchors:

- `Sonic3kAIZEvents.getFireCurtainRenderState(...)`
- `S3kSeamlessMutationExecutor.apply(...)`
- `Sonic3kPatternAnimator.update(...)`
- `Sonic3kHCZEvents.updateAct1Fg(...)`
- `Sonic3kZoneFeatureProvider.render(...)`

Best current candidates:

- AIZ for typed state, layout mutation, and special render effects
- HCZ for typed state and animated/palette ownership cleanup
- slots/gumball for effect-channel style integration

Main retrofit risk:

- hidden frame-order coupling across events, scroll, pattern, palette, and render hooks, especially in AIZ and HCZ

### Sonic 2

Sonic 2 is the best cross-game adapter target.

Current fit:

- `TypedZoneRuntimeState`: medium
- `AnimatedTileChannelGraph`: high for standard animation, medium overall
- `PaletteOwnershipRegistry`: medium to high
- `ZoneLayoutMutationPipeline`: medium
- `SpecialRenderEffectFramework`: medium

Strongest retrofit anchors:

- `Sonic2PatternAnimator.loadScriptsForZone(...)`
- `Sonic2PaletteCycler` and its `PaletteCycle` base
- `Sonic2ZoneEvents.loadBossPalette(...)`
- `Sonic2CNZEvents` layout mutation helpers
- `Sonic2HTZEvents` plus `SwScrlHtz`

Main retrofit risk:

- over-normalizing split timing across events, scroll, camera, palette, and animation logic that currently produces correct parity through ordering rather than explicit ownership

### Sonic 1

Sonic 1 should adopt the subset that pays for itself.

Current fit:

- `TypedZoneRuntimeState`: medium to high
- `AnimatedTileChannelGraph`: low to medium
- `PaletteOwnershipRegistry`: medium
- `ZoneLayoutMutationPipeline`: medium to high
- `SpecialRenderEffectFramework`: low

Strongest retrofit anchors:

- `Sonic1LevelEventManager`
- `Sonic1LZWaterEvents`
- `Sonic1PatternAnimator`
- `Sonic1PaletteCycler`
- `Sonic1LevelInitProfile`

Main retrofit risk:

- forcing Sonic 1 into abstractions shaped around S3K complexity and turning straightforward imperative logic into harder-to-audit layered code

### Shared Engine Infrastructure

The shared engine is compatible enough to host these systems, but only if the registries live at runtime scope.

Key anchors:

- `GameRuntime`
- `RuntimeManager.createGameplay(...)`
- `GameModule.createLevelState(...)`
- `MutableLevel`
- `LevelManager.processDirtyRegions()`
- `LevelTilemapManager.rebuildDirtyRegions(...)`
- `ParallaxManager.update(...)`
- `ZoneScrollHandler`

Highest shared-risk subsystem:

- `PaletteOwnershipRegistry`

Palette writes are currently distributed across level state, per-game logic, and GPU upload helpers. That is workable for ad hoc behavior, but it becomes the most likely source of regressions once multiple frameworks attempt to layer palette animation, overrides, underwater variants, and special effects at the same time.

## Recommended Rollout

### Phase 1: Typed State Foundations In S3K

Targets:

- introduce runtime-owned typed zone state containers
- adapt AIZ and HCZ first
- keep current event bytes and authoritative routines intact

Goal:

- replace raw cross-system event-byte sharing with named state access

### Phase 2: Palette Ownership Registry

Targets:

- add registry and ownership resolution order
- adapt S3K palette cycler first
- use HPZ/HCZ-style conflict cases as proving tests

Goal:

- solve the most dangerous ownership ambiguity before layering more systems on top

### Phase 3: Animated Tile Channel Graph

Targets:

- wrap existing `Sonic3kPatternAnimator` cases in channel adapters
- absorb standard AniPLC paths first
- then add custom channels for HCZ/LBZ/SOZ-style dynamic art behavior

Goal:

- unify tile animation without losing support for imperative/custom channels

### Phase 4: Zone Layout Mutation Pipeline

Targets:

- formalize mutation staging on top of `MutableLevel`
- move AIZ seamless mutation path into the pipeline
- extend to HCZ/LBZ/CNZ-style mutation scenarios

Goal:

- make layout mutation a first-class runtime capability instead of a scattering of helper calls

### Phase 5: Special Render Effect Framework

Targets:

- extract effect ownership from render hooks and bespoke scene tricks
- start with AIZ fire curtain and one additional effect-heavy case
- keep ordinary per-zone drawing outside the framework

Goal:

- standardize special scene behavior without turning the entire renderer into a generalized effect graph

### Phase 6: Cross-Game Adapter Rollout

Targets:

- map Sonic 2 onto typed state, palette ownership, animated-tile channels, and selected mutation/effect hooks
- adopt only the beneficial subset in Sonic 1

Goal:

- reuse the architecture where it helps, without forcing full symmetry across all games

## Safety Rules

### No Hidden Ownership Transfer

The new frameworks must never silently become the new source of truth. They expose and coordinate behavior; they do not rewrite authoritative ROM-derived logic by accident.

### One Authoritative Frame Order

All subsystem work must obey the explicit frame-order contract defined above.

### Per-Subsystem Opt-In

Zones and games may adopt one framework without adopting all of them.

### Adapter-First Migration

Initial migrations wrap:

- `Sonic3kPatternAnimator`
- `Sonic3kPaletteCycler`
- `S3kSeamlessMutationExecutor`
- `Sonic2PatternAnimator`
- `Sonic2PaletteCycler`
- `Sonic1LZWaterEvents`

before deeper ownership moves occur.

### Verification Before Consolidation

Each subsystem migration needs dedicated validation:

- typed state equivalence tests
- tile-update ordering checks
- palette ownership and underwater mirror checks
- mutation plus redraw/collision assertions
- render-order tests and screenshot comparison for effect-heavy zones

## Non-Goals

This design is not:

- a full rewrite of all game modules
- a demand that all games share one identical runtime shape
- a reason to move simple Sonic 1 logic into heavyweight S3K-style abstractions
- a license to sacrifice parity for elegance

## Recommendation

Proceed with a state-first, runtime-owned, adapter-backed architecture.

The highest-value initial scope is:

1. typed zone runtime state in S3K
2. palette ownership registry
3. animated tile channel graph

That order addresses the largest current ambiguity first, minimizes ownership conflicts, and creates the clearest path for later S2 reuse without forcing premature generalization.
