# Animated Tile Channel Graph Design

**Date:** 2026-04-12

## Goal

Define a runtime-owned animated-tile framework that can host both Sonic 3&K's mixed scripted/custom tile animation and Sonic 2's simpler declarative pattern animation without changing frame order or flattening the two games into the same behavior model.

## Problem

Animated tile behavior is currently split across per-game animator classes with very different complexity profiles:

- `Sonic3kPatternAnimator` mixes:
  - standard AniPLC scripts
  - guard logic tied to zone state
  - scroll-derived custom DMA-style updates
  - composed tile uploads built from lookup data
  - bonus-stage-specific imperative animation paths
- `Sonic2PatternAnimator` is mostly a declarative AniPLC loader/ticker with much simpler runtime rules

That creates three concrete problems:

1. channel ownership is buried inside game-specific animator classes instead of runtime state
2. scripted and custom channels have no shared vocabulary, so every new complex zone adds more bespoke logic
3. there is no clean migration path from simple script-driven animation to richer state/scroll-driven animation across games

Phase 3 should solve those problems without trying to absorb every existing animated-tile path on day one.

## Design Principles

### 1. Runtime-Owned, Game-Adapted

The graph must be owned by gameplay runtime, while games build channel definitions through adapters. The runtime owns state and per-frame caches; the per-game animators define what channels exist.

### 2. Scripted And Custom Channels Share One Model

The framework must represent both:

- simple AniPLC-style frame uploads
- custom split/composed transfers driven by scroll or zone runtime state

If the graph only models scripts, S3K remains bespoke. If it only models custom DMA-like channels, Sonic 2 becomes ceremony for no gain.

### 3. Frame Order Stays Identical

The graph is a new ownership model, not a new scheduling model. It must run in the same animated-pattern slot used today.

### 4. Migrate By Facade, Not Rewrite

`Sonic3kPatternAnimator` and `Sonic2PatternAnimator` should remain as public integration surfaces in this phase. They become builders/facades over the graph rather than disappearing immediately.

### 5. Prove The Hard Cases First

The first graph implementation must prove:

- declarative script channels
- scroll-derived split transfers
- composed transfer channels

That means S3K HCZ and SOZ drive the design, while Sonic 2 proves the declarative adapter path.

## Proposed Architecture

The subsystem consists of four main parts.

### 1. AnimatedTileChannelGraph

Runtime-owned coordinator that stores the active channel set for the current level and evaluates them in deterministic order each update.

Responsibilities:

- hold channel definitions and runtime-local caches
- evaluate guards and phase sources
- suppress redundant uploads when phase/input values have not changed
- execute each channel's transfer strategy against the current `Level`

Non-responsibilities:

- deciding frame order in the global level pipeline
- reading raw event bytes directly
- owning palette/layout/render effects

### 2. AnimatedTileChannel

A single named channel definition with:

- `channelId`
- `gameId`
- `zoneIndex` / `actIndex` scope
- `guard`
- `phaseSource`
- `destinationPlan`
- `applyStrategy`
- optional local cache policy

The graph executes channels in a stable order defined by registration order or explicit priority when needed.

### 3. PhaseSource

A phase source provides the value that drives a channel's update logic. This should be typed enough to avoid overfitting to one game.

Required phase-source families for Phase 3:

- `FrameCounterPhaseSource`
  For ordinary time-based animation
- `ScriptPhaseSource`
  For AniPLC script frame progression
- `ScrollDerivedPhaseSource`
  For BG/camera-derived channels such as HCZ2 and SOZ1
- `ZoneStatePhaseSource`
  For typed runtime-state driven channels such as HCZ1 waterline equilibrium
- `LocalCounterPhaseSource`
  For self-contained special channels that maintain a local counter without depending on global zone state

### 4. ApplyStrategy

Apply strategies define how tile data reaches pattern memory.

Phase 3 needs three first-class strategies:

- `ScriptFrames`
  Declarative AniPLC-style frame upload, matching the current `AniPlcScriptState.tick(...)` behavior
- `SplitTransfer`
  One logical channel producing up to two contiguous uploads based on a phase split table
- `ComposedTransfer`
  One logical channel that assembles temporary output bytes/patterns from lookup data before uploading

These strategy types are enough for:

- Sonic 2 scripted animation
- HCZ2 split-table DMA behavior
- SOZ1 scroll-derived split transfers
- HCZ1 waterline recomposition

Anything outside that set stays on the compatibility path in Phase 3.

## Channel Model

Each channel should be understandable without reading its implementation internals. The minimal shape is:

- `guard`: should this channel run this frame?
- `phase`: what value drives the channel this frame?
- `cache`: has the relevant phase/input changed since last application?
- `transfer`: how is the output written?

### Guard

Examples:

- AIZ scripts disabled during boss active state
- HCZ1 custom waterline channel disabled during act-transition/boss gating
- S2 scripted zones always enabled once loaded

### Phase

Examples:

- script-local counter for a normal AniPLC channel
- `(eventsBg12 & 0x1F)` for HCZ2 strip channels
- `(eventsBg14 & 0x3F)` for HCZ2 wide strip channel
- `(eventsBg10 - cameraBgX) & 0x1F` for SOZ1 parallax-driven animation
- waterline delta sign/range for HCZ1 composed transfer

### Destination Plan

The destination plan defines where output lands.

Phase 3 needs:

- single contiguous destination ranges
- two-part split destination ranges where the second destination depends on the first transfer size

This covers both:

- script destinations such as HCZ/AIZ/S2 AniPLC tiles
- custom split channels such as HCZ2 and SOZ1

### Cache Policy

The graph should not reapply expensive channels every frame when their effective phase is unchanged.

Phase 3 cache policies:

- `Always`
  For ordinary scripted channels where the script state already controls cadence
- `OnPhaseChange`
  For scroll/state-driven channels that should upload only when the derived phase changes

## Runtime Placement

The graph belongs with the other runtime-owned registries:

- `GameRuntime`
- `RuntimeManager`
- `GameServices`

This is preferable to keeping ownership inside `LevelManager` or individual animators because:

- channel state is mutable gameplay/runtime state
- different games can register different channel sets behind the same runtime seam
- future phases can compose graph inputs with typed zone state and palette/layout systems cleanly

The per-game animators remain as adapters:

- `Sonic3kPatternAnimator` becomes the S3K graph builder/facade
- `Sonic2PatternAnimator` becomes the S2 graph builder/facade

`LevelManager` continues to call the animated pattern manager in the existing frame slot.

## Migration Shape

Phase 3 should migrate by facade rather than by replacement.

### Sonic 3&K

Keep `Sonic3kPatternAnimator` as the integration surface, but split its internals into:

- channel registration/build logic
- graph execution
- compatibility paths for channels still outside the graph

### Sonic 2

Keep `Sonic2PatternAnimator` as the integration surface, but replace its internal direct script ticking with graph-backed `ScriptFrames` channels.

This gives a real second-game proving case without forcing Sonic 2 to adopt custom channel semantics it does not currently need.

## Phase 3 Scope

### In Scope

#### S3K Proving Cases

- HCZ Act 1
  - existing AniPLC script channels
  - custom waterline composed transfer
- HCZ Act 2
  - existing AniPLC script channels
  - multi-channel split transfers driven by deform-derived phase values
- SOZ Act 1
  - scroll-derived split transfer channel
  - preserve the shared generic AniPLC slot behavior

#### S2 Adapter

- retrofit `Sonic2PatternAnimator` onto graph-backed `ScriptFrames` channels
- preserve the current zone-selection and script-loading behavior

### Explicitly Out Of Scope

- Pachinko and Gumball bespoke operators
- LBZ custom animation migration in the first implementation slice
- palette interaction changes
- layout mutation changes
- render-effect extraction
- S1 adoption

LBZ still matters architecturally, but it should remain a named follow-on proving case once the graph core is stable.

## Proving Cases And Why

### HCZ

HCZ is the most important Phase 3 proving case because it covers both complex channel types:

- HCZ1 proves composed transfers driven by typed zone/scroll state
- HCZ2 proves multiple split-transfer channels driven by derived phase masks

If HCZ can be expressed cleanly, the graph is modeling the real problem rather than just reorganizing AniPLC scripts.

### SOZ

SOZ1 is the cleanest non-HCZ custom channel because it is fundamentally a scroll-derived tile animation problem with a simpler transfer structure than LBZ.

### Sonic 2

Sonic 2 proves that the graph can host simple declarative animation without burdening a simpler game with S3K-specific complexity.

## Verification Strategy

Phase 3 should reuse the current pattern-animation testing style rather than inventing new infrastructure.

### Required Verification

1. unit tests for generic graph behavior
   - channel ordering
   - guard suppression
   - `OnPhaseChange` caching
   - split destination calculations
   - composed-transfer assembly behavior

2. S3K integration tests
   - HCZ scripted destination still changes
   - HCZ custom destination still changes
   - SOZ custom destination changes under scroll-derived phase movement

3. S2 regression tests
   - graph-backed scripted animation preserves current destination updates for representative zones

### Existing Test Shapes To Reuse

- `TestS3kHczPatternAnimation`
- `TestS3kPachinkoPatternAnimation` as a model for custom-vs-script destination assertions
- current headless fixtures and `AnimatedPatternManager` update-driven tests

## Success Criteria

Phase 3 is complete when:

1. `GameRuntime` owns an animated-tile channel graph or equivalent runtime registry
2. S3K HCZ Act 1 and Act 2 run through the graph for both scripted and custom channels
3. SOZ1 custom animated tile behavior runs through the graph
4. Sonic 2 scripted pattern animation runs through the same graph via declarative adapter channels
5. Pachinko/Gumball and other non-migrated special cases still behave through compatibility code paths
6. the current pattern-animation tests are green, with added graph-focused regression coverage

## Risks

### 1. Frame-Order Drift

If graph ownership changes when channels tick, visual parity can regress even when the same data is ultimately written.

Mitigation:

- keep `LevelManager` scheduling unchanged
- migrate by facade
- reuse current update-driven tests first

### 2. Over-Generalizing Around S2

If the graph is designed around Sonic 2's simpler script path, HCZ/SOZ/LBZ will still need escape hatches everywhere.

Mitigation:

- let S3K define the hard requirements
- use S2 as the declarative adapter proof, not the design baseline

### 3. Over-Generalizing Around S3K Specials

If every bespoke operator is pulled into Phase 3, the first slice becomes too broad.

Mitigation:

- restrict first-class strategies to `ScriptFrames`, `SplitTransfer`, and `ComposedTransfer`
- keep Pachinko/Gumball on compatibility paths for now

## Non-Goals

- unify all games under identical animated-tile semantics
- remove the existing per-game animator classes in this phase
- migrate every S3K custom tile path immediately
- combine animated-tile work with palette/layout/render-effect refactors

Phase 3 should create a stable shared animated-tile substrate, not finish every tile animation migration in one pass.
