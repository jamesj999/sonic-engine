# S3K CNZ Bring-Up Design

## Scope

This design covers a full bring-up of **S3K CNZ**: **Carnival Night Zone** from **Sonic 3 & Knuckles**.

It explicitly does **not** mean Sonic 2 Casino Night Zone unless a comparison is called out as `S2 CNZ`.

The goal is to bring S3K CNZ onto the engine's shared runtime-owned framework stack while preserving ROM behavior as closely as practical. This is a full-zone effort, not a narrow parallax or palette pass. It includes:

- zone runtime and event translation
- scroll/deform behavior
- animated tiles
- palette cycling and underwater mirroring
- CNZ-specific object orchestration where the ROM delegates zone behavior to objects
- seamless Act 1 -> Act 2 transition behavior
- Act 2 route split behavior, including Knuckles teleporter/capsule flow
- ROM/disassembly-backed visual validation

## Goals

- Reconstruct CNZ behavior from the ROM/disassembly, not from a simplified gameplay approximation.
- Keep CNZ logic aligned with the project's shared runtime-owned systems instead of introducing zone-local one-off state where shared systems already exist.
- Preserve traceability from Java code back to specific disassembly routines, RAM fields, thresholds, palette targets, and VRAM destinations.
- Make validation part of the deliverable, not a follow-up.

## Non-Goals

- General S3K object completion outside the CNZ-specific objects and helpers needed for zone bring-up.
- Broad cleanup or refactoring unrelated to CNZ parity.
- Reworking the engine's shared frameworks unless CNZ exposes a specific gap that blocks ROM-accurate behavior.

## Naming Rule

To avoid ambiguity between Sonic 2 and Sonic 3:

- In documentation and comments, prefer `S3K CNZ` or `Carnival Night Zone`.
- Use bare `CNZ` only where the codebase already fixes the naming, such as class names or existing constants.
- If Sonic 2 Casino Night Zone must be referenced, call it `S2 CNZ` or `Casino Night Zone`.

This naming rule applies to the design doc, code comments, Javadocs, tests, and validation reports.

## Source of Truth

The primary source of truth is the S&K-side disassembly and ROM behavior, not the current Java branch state.

Primary anchors:

- `docs/skdisasm/sonic3k.asm`
- `docs/architecture/research/s3k-zones/cnz-analysis.md`
- S&K-side ROM addresses and labels only

Important CNZ anchors include:

- `CNZ1_ScreenInit`
- `CNZ1_ScreenEvent`
- `CNZ1_BackgroundInit`
- `CNZ1_BackgroundEvent`
- `CNZ1_BossLevelScroll`
- `CNZ1_BossLevelScroll2`
- `CNZ2_ScreenInit`
- `CNZ2_ScreenEvent`
- `CNZ2_BackgroundInit`
- `CNZ2_BackgroundEvent`
- `CNZ1_Deform`
- `AnimateTiles_CNZ`
- `AniPLC_CNZ`
- `AnPal_CNZ`
- `PLC_EggCapsule`
- `ArtKosM_CNZTeleport`
- `ShakeScreen_Setup` where CNZ invokes it
- CNZ miniboss, scroll-control, teleporter, beam, capsule, and water-helper object routines

## Architecture

### Framework-First Rule

CNZ should be structured around the engine's shared runtime-owned systems first, with ROM accuracy controlling how those systems are used.

The implementation should prefer:

- `ZoneRuntimeRegistry` for typed CNZ cross-system state
- `PaletteOwnershipRegistry` and `S3kPaletteWriteSupport` for palette writes
- `AnimatedTileChannelGraph` and `S3kAnimatedTileChannels` when CNZ animation behavior fits the graph model
- `ZoneLayoutMutationPipeline` or `S3kSeamlessMutationExecutor` for deterministic live layout changes
- `SpecialRenderEffectRegistry` and `AdvancedRenderModeController` only if CNZ exposes render-stage behavior that requires them
- `ScrollEffectComposer` and related helpers where they can express `CNZ1_Deform` accurately without obscuring the ROM math

`ScrollEffectComposer` migration is optional. If direct scroll math in `SwScrlCnz` is easier to keep ROM-traceable and better documented, direct math is acceptable. Composer adoption is in scope only when it improves clarity without hiding CNZ-specific deform semantics.

### Runtime Ownership

`CnzZoneRuntimeState` should become the canonical adapter for CNZ state that is consumed across subsystems.

It should expose, directly or via narrow typed accessors:

- FG/BG event routine state
- CNZ boss/background scroll mode state
- ROM-equivalent `Events_bg` fields that matter to scroll, animation, transition, or object coordination
- `Events_fg_5` and related transition handoff semantics
- Act 2 route state for Sonic/Tails versus Knuckles
- teleporter/capsule progress state where needed outside the owning object
- wall-grab suppression state
- any CNZ-specific water-target state that must be observed across systems

The runtime adapter exists to make shared state explicit. It must not become a vague bag of booleans.

The runtime adapter should be expanded incrementally, with each field justified by an immediate consumer:

| Runtime surface | Consuming slice(s) |
|-----------------|--------------------|
| FG/BG event routine state | 1, 2, 3 |
| Boss/background scroll mode | 1 |
| `Events_bg+$08/$0C`-style boss scroll inputs | 1, 2 |
| `Events_bg+$10` deform-derived animated-tile input | 0, 4 |
| `Camera_X_pos_BG_copy` equivalent published input | 0, 4 |
| `Events_fg_5` transition state | 2 |
| Act 2 route state | 3 |
| teleporter/capsule progress | 3 |
| wall-grab suppression state | 1 |
| water-target state / arm flags | 5 |

### Deform Output Publication

CNZ has one critical shared-state edge that the spec must treat as first-class:

- `CNZ1_Deform` computes a distinct deform-derived BG X value equivalent to `Events_bg+$10`
- `AnimateTiles_CNZ` then derives its DMA phase from `(Events_bg+$10 - Camera_X_pos_BG_copy) & $3F`

This output must not remain trapped inside `SwScrlCnz`.

The implementation must publish the deform-derived animated-tile inputs through explicit runtime state or another equally traceable shared surface. That publication step is a precondition for parity-correct `AnimateTiles_CNZ`.

### Subsystem Ownership

Ownership should remain narrow:

- `Sonic3kCNZEvents` owns ROM event translation and zone-level orchestration.
- `SwScrlCnz` owns deform math and boss-scroll path selection.
- `Sonic3kPatternAnimator` owns `AnimateTiles_CNZ`, AniPLC registration, and any direct DMA path that belongs to animated tiles. This is currently greenfield CNZ work and should be treated as such in planning.
- `Sonic3kPaletteCycler` owns `AnPal_CNZ`, including underwater mirroring.
- CNZ-specific object classes own gameplay execution, but cross-system side effects must be published through explicit runtime/event bridges rather than hidden inside local object state.

### Water System Integration

The existing `WaterSystem` should remain the authoritative owner of current and target water levels for S3K CNZ.

CNZ-specific logic should integrate by:

- publishing object-driven CNZ water triggers through explicit zone/runtime state where cross-system consumers need them
- applying target-level writes through the existing water infrastructure rather than creating a CNZ-local water controller
- introducing CNZ-specific adaptation only if the current `WaterSystem` contract cannot reproduce the object-gated target changes and timing documented in the ROM

## Design Constraints

### ROM Accuracy Rule

The implementation must strive for ROM accuracy even when routed through shared Java frameworks.

That means:

- matching disassembly thresholds and branch structure where behavior depends on them
- preserving multi-stage event chains instead of collapsing them into simplified flags
- preserving object-to-event and event-to-scroll dependencies
- keeping palette targets, VRAM destinations, and layout-copy semantics exact unless a documented engine abstraction is required

If a behavior cannot be expressed one-for-one, the alternative must preserve the externally visible ROM behavior and be documented in code as an intentional adaptation.

### Documentation Rule

Heavy documentation is required.

Every meaningful CNZ class or method added or substantially changed must carry code-local documentation that ties it to ROM behavior.

Required forms:

- class Javadocs naming the routines or object labels being modeled
- method Javadocs for non-trivial state-machine stages, deform math, animated-tile phase math, palette writes, and transition steps
- targeted block comments for thresholds, RAM field mappings, VRAM regions, palette entries, and layout-copy rules
- explicit "engine adaptation" comments when the Java structure differs from the ROM but preserves the behavior

Comments should justify behavior, not restate obvious code.

## Feature Slices

The work is framework-first in structure, but the implementation slices are defined by ROM gameplay behavior.

### Slice 0: Shared Runtime Scaffolding And Deform Output Publication

Primary sources:

- `CNZ1_Deform`
- `AnimateTiles_CNZ`
- current CNZ runtime adapter and scroll handler behavior

Deliverables:

- expansion of `CnzZoneRuntimeState` from the current minimal surface to the fields needed by later slices
- explicit publication of the deform-derived inputs equivalent to `Events_bg+$10` and `Camera_X_pos_BG_copy`
- traceable ownership of those values so `Sonic3kPatternAnimator` does not re-derive them from unrelated approximations
- documentation that ties the published values to the ROM formulas they support

Preconditions:

- none

Constraint:

This slice exists specifically because `AnimateTiles_CNZ` depends on deform outputs that the current branch does not publish. Later slices may not treat that dependency as implicit.

### Slice 1: Act 1 Arena And Miniboss Path

Primary sources:

- `CNZ1_ScreenEvent`
- `CNZ1_BackgroundEvent`
- `CNZ1_BossLevelScroll`
- `CNZ1_BossLevelScroll2`
- CNZ miniboss object chain

Deliverables:

- accurate Act 1 BG routine progression from normal play into miniboss path
- upward camera/player shift on miniboss entry where the ROM applies it
- miniboss palette load and camera minimum-Y behavior
- Knuckles wall-grab suppression during the arena path
- boss/background scroll mode routing that reflects the ROM stages
- explicit object-to-event signaling for arena destruction
- publication of any boss-scroll inputs needed by downstream slices instead of keeping them local to one handler

Preconditions:

- Slice 0 shared runtime scaffolding

Constraint:

The implementation must not reduce this slice to a single `boss mode` boolean. It needs the ROM-relevant `Events_bg` semantics that downstream systems consume.

### Slice 2: Act 1 Post-Boss Handoff And Seamless Transition

Primary sources:

- later stages of `CNZ1_BackgroundEvent`
- signpost-triggered `Events_fg_5` chain

Deliverables:

- first post-boss handoff after the initial `Events_fg_5`
- FG refresh sequencing
- BG-to-FG layout/collision handoff
- second refresh and signpost phase
- seamless Act 1 -> Act 2 transition
- PLC, palette, water, and world-offset behavior associated with the transition
- any CNZ-specific screen-shake routing that is part of the post-boss or transition path

Preconditions:

- Slice 1 Act 1 arena/miniboss path
- Slice 5 object signaling needed for `Events_fg_5` handoff

Constraint:

The implementation must preserve the ROM's two-step `Events_fg_5` semantics. It must not simplify this into an immediate act swap.

### Slice 3: Act 2 Route Split

Primary sources:

- `CNZ2_ScreenEvent`
- `CNZ2_BackgroundEvent`
- teleporter/capsule/beam object routines

Deliverables:

- correct Sonic/Tails route behavior
- correct Knuckles teleporter route behavior
- teleporter object spawn and progression
- egg capsule spawn and PLC loading
- control lock/unlock and music restoration timing
- camera clamp behavior
- any Act 2 refresh or shake stages required for parity

Preconditions:

- Slice 2 seamless Act 1 -> Act 2 handoff

Constraint:

The Knuckles route is a cutscene/object sequence, not a background-mode flag.

### Slice 4: Animated Tiles And Palette Ownership

Primary sources:

- `AnimateTiles_CNZ`
- `AniPLC_CNZ`
- `AnPal_CNZ`
- `CNZ1_Deform`

Deliverables:

- CNZ AniPLC registration for both acts
- correct ownership split between the standard AniPLC scripts and the direct DMA path at tile `$308+`
- `AnimateTiles_CNZ` phase calculation derived from the real deform/scroll state
- parity-correct palette cycling for normal and underwater targets
- PLC verification for any CNZ path that depends on art being present before route/cutscene logic or validation screenshots are considered trustworthy

Preconditions:

- Slice 0 deform-output publication
- Slice 1 boss/background scroll routing

Constraint:

The implementation must use the ROM-driven phase inputs and palette destinations. Approximate or hardcoded substitutes are not acceptable for final parity.

### Slice 5: Object-Driven Environmental Control

Primary sources:

- CNZ water helper object routines
- miniboss and scroll-control object routines

Deliverables:

- object-driven water-target changes
- publication of object-owned side effects into explicit shared runtime/event state where other systems depend on them
- correct object/event links that feed scroll, transition, or validation-relevant behavior

Preconditions:

- Slice 0 shared runtime scaffolding

Constraint:

If the ROM delegates behavior to objects, the engine must treat that as primary zone behavior, not as optional polish.

## State And Dependency Model

The spec should preserve the ROM's important dependency edges.

Required dependency modeling:

- miniboss objects write arena-destruction inputs consumed by `CNZ1_ScreenEvent`
- event-side destruction accounting feeds miniboss movement/state behavior
- scroll-control object state feeds boss-scroll/deform behavior
- signpost and post-boss object chains feed the two-stage `Events_fg_5` transition logic
- deform output feeds `AnimateTiles_CNZ` phase selection
- teleporter route state affects object spawning, camera clamp, control lock, and end-of-level handling
- water helper objects affect water targets and any validation beat that depends on them

The implementation may translate these through typed runtime accessors and helper methods, but it must not erase the dependency graph.

High-level slice dependencies:

- Slice 0 unblocks Slice 1 and Slice 4
- Slice 5 feeds Slice 1 and Slice 2 where the ROM uses object-owned writes
- Slice 1 unblocks Slice 2 and provides state required by Slice 4
- Slice 2 unblocks Slice 3

## Testing Strategy

### Automated Verification

The implementation plan should include focused automated coverage for:

- CNZ runtime state transitions
- Act 1 boss-scroll/deform routing
- post-boss `Events_fg_5` sequencing
- seamless Act 1 -> Act 2 transition state
- Act 2 route split behavior
- animated-tile phase derivation from deform output
- palette cycling destinations, timers, counters, and underwater mirroring
- object-to-event bridge points for arena destruction and water-target changes

Tests should be surgical. They should validate behavior with ROM-facing names and comments rather than introducing opaque engine-only expectations.

Trace replay is not a required gate for the first CNZ bring-up spec because the currently documented replay workflows are Sonic 1-focused. If the existing headless or trace infrastructure can be extended to cover S3K CNZ sequences without building a parallel validation stack, that is a preferred enhancement for high-risk flows such as miniboss entry, seamless transition position/camera state, or teleporter-route motion.

### Regression Focus

The highest-risk regression surfaces are:

- collapsing the Act 1 -> Act 2 chain into a simplified transition
- losing object-driven arena destruction semantics
- incorrect pachinko/background tile phase logic
- missing underwater palette mirroring
- reducing Knuckles route behavior to a state flag without object/cutscene side effects

## Visual Validation

Visual validation is a required completion gate.

The final bring-up is not complete unless the engine is checked against the ROM/disassembly for specific CNZ beats.

Required visual checklist:

1. Act 1 miniboss entry shift, palette change, and boss-scroll path
2. arena destruction and the resulting lowering/handoff behavior
3. post-boss FG refresh sequence
4. seamless Act 1 -> Act 2 transition
5. Act 2 Sonic/Tails route behavior
6. Act 2 Knuckles teleporter/capsule route behavior
7. `AnimateTiles_CNZ` pachinko/background DMA behavior tied to deform phase
8. `AnPal_CNZ` parity in both normal and underwater palette targets
9. CNZ object-driven water target changes
10. any CNZ-specific shake behavior triggered in the verified boss or transition beats
11. PLC-backed art presence for teleporter/capsule and any validated route-specific scene before declaring a visual parity pass

The validation artifact should identify:

- the ROM/disassembly anchor for each beat
- the engine scene or trigger used to reproduce it
- whether the result matches, partially matches, or fails
- follow-up fixes required for any mismatch

## Implementation Guidance

The implementation plan should proceed in a framework-first order that still respects ROM dependencies:

1. expand `CnzZoneRuntimeState` and shared state surfaces first
2. publish deform-derived animated-tile inputs equivalent to `Events_bg+$10` and `Camera_X_pos_BG_copy`
3. complete object-driven pieces that publish critical zone behavior
4. fix `Sonic3kCNZEvents` to represent the real Act 1 and Act 2 orchestration
5. update `SwScrlCnz` to consume accurate CNZ event/runtime state
6. restore correct `AnimateTiles_CNZ` phase derivation and AniPLC/DMA ownership
7. complete `AnPal_CNZ` including underwater mirroring
8. add automated verification
9. perform ROM/disassembly-backed visual validation

This is still a full-zone bring-up, but the order should ensure that later systems consume stable CNZ runtime truth instead of local placeholders.

## Risks

- CNZ spreads important behavior across events, deform logic, animated tiles, and objects; any subsystem-only pass can miss critical dependencies.
- The existing branch state already under-models some CNZ behavior, so superficial extension of current code risks cementing the wrong abstraction.
- Shared framework routing can accidentally hide ROM semantics unless the code is heavily documented and the runtime adapter remains explicit.
- Visual parity failures are likely around seamless transition timing, pachinko/background phase math, and underwater palette mirroring.

## Completion Criteria

S3K CNZ bring-up is complete only when all of the following are true:

- the framework-owned runtime/state model is in place and explicit
- the ROM-critical CNZ slices above are implemented
- every non-trivial CNZ change is traceable in Javadoc/comments back to ROM behavior or a documented engine adaptation
- automated checks cover the critical state and dependency surfaces
- the visual validation checklist passes against ROM/disassembly evidence
