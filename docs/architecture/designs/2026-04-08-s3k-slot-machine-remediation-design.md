# S3K Slot Machine Bonus Stage Remediation Design

## Goal

Replace the current incomplete S3K Slot Machine bonus-stage implementation with a ROM-shaped implementation that is complete, accurate, and architecturally aligned with the existing S3K bonus-stage lifecycle.

This remediation explicitly treats the current Slots code as partially useful scaffolding, not as the final architecture. Where the current implementation approximates the disassembly, this design requires replacement rather than incremental polish.

## Why A Remediation Spec Exists

The earlier Slot Machine design established the correct high-level target, but the current branch state confirms that several core routines were implemented as approximations instead of faithful ports.

The current implementation is still structurally inaccurate in the following areas:

- the player runtime is a custom-physics wrapper over the normal playable classes rather than a faithful `Obj_Sonic_RotatingSlotBonus` path
- stage rendering uses a semantic Java tile model rather than a `Slots_RenderLayout` port
- reel logic compresses `Slots_CycleOptions` into a simplified helper
- slot collision and tile interactions are reduced to a simplified semantic model
- screen/background deformation is approximate rather than driven by the ROM slot handlers
- cage and reward objects are partially runtime-rendered instead of fully object-shaped
- exit logic does not fully mirror the ROM fade/wind-down path

This spec supersedes the earlier implementation assumptions wherever the two conflict.

## Scope

### In scope

- locked-on S3K Slot Machine bonus stage at zone `0x15`
- dedicated slot-bonus player runtime matching `Obj_Sonic_RotatingSlotBonus`
- ROM-shaped slot-stage state, including scalar/rotation state, reel state, payout state, and exit state
- `Slots_RenderLayout` parity
- `Slots_CycleOptions` parity
- slot-specific collision and tile interaction behavior
- slot cage object parity
- slot ring and spike reward object parity
- `Slots_ScreenInit`, `Slots_ScreenEvent`, `Slots_BackgroundInit`, and `Slots_BackgroundEvent` parity
- `AnPal_Slots` parity
- exit wind-down/fade and integration with the existing checkpoint return lifecycle
- Sonic, Tails, and Knuckles parity within the slot-player path

### Out of scope

- 2P or competition variants
- S&K standalone differences
- shared S2/S3K slot gameplay architecture
- "close enough" behavior that knowingly deviates from the ROM

## Non-Negotiable Implementation Rules

- Keep the existing `GameLoop` and `Sonic3kBonusStageCoordinator` lifecycle ownership unless the ROM requires a distinct stage-local owner.
- Replace approximation layers when the ROM exposes a clearly distinct routine or state machine.
- Do not hide slot-stage geometry behind generic object rendering.
- Do not hide slot-stage gameplay inside generic playable physics.
- Prefer smaller focused slot subsystems over a single oversized runtime file.
- Remove or collapse superseded slot-stage code paths as part of the remediation instead of leaving broken or duplicate implementations behind.

## ROM Parity Inventory

The remediation is only complete when all of the following are present and correct.

### Player runtime

- dedicated slot-bonus player init replacing the normal main sprite during the bonus stage
- Sonic/Tails/Knuckles-specific art and mapping selection
- Tails-alone slot-player setup parity where the ROM differentiates it
- slot-specific ground and air movement
- slot-specific collision integration and rollback
- object-controlled capture/release path
- slot-specific camera coupling

### Stage rendering

- stage layout sourced from ROM-derived slot layout data
- slot layout copied into its expanded runtime buffer with the ROM row stride rather than treated as a compact semantic grid
- slot piece/map definitions copied into the runtime chunk table in the same shape the ROM uses
- `Slots_RenderLayout`-style transformed piece submission
- correct colored wall, goal, bumper, ring, R-marker, peppermint, and slot-window representation
- correct goal and peppermint animation timing
- slot-stage ring-piece animation driven from the same ring-frame source the ROM uses
- correct transient tile-animation behavior for ring, bumper, spike, and related slot-piece state changes

### Reel and option state

- `Slots_CycleOptions`-shaped state machine
- correct visible reel symbol progression
- correct target selection and reward resolution
- correct timing between spin, settle, lock, and payout handoff
- correct option-strip art source selection and upload behavior

### Collision and tile interactions

- slot-grid collision footprint matching the ROM routines
- ring pickup checks matching the ROM offsets and behavior
- bumper launch behavior
- goal trigger behavior
- spike reversal behavior
- slot-window increment behavior

### Cage and rewards

- cage capture range and snap behavior
- control lock and release behavior
- reward spawn cadence and angles
- ring reward object timing and grant behavior
- spike reward object timing and drain behavior
- correct bookkeeping of active reward objects

### Visual systems

- slot-specific palette cycling for idle and capture states
- slot-specific screen deformation inputs
- slot-specific background deformation inputs

### Exit and lifecycle

- saved ring count and extra-life flags restored on slot-player init
- HUD ring refresh triggered on slot-player init
- slot exit wind-down
- slot fade timing
- live ring count and extra-life flags written back to saved bonus-stage state on exit
- coordinator exit handoff
- checkpoint return through the existing bonus-stage lifecycle
- no sidekick participation in the locked-on single-player slot stage

## Architecture

### Lifecycle owner

The existing bonus-stage lifecycle remains the outer owner:

- `GameLoop` still owns load, title card, fade, and return-to-checkpoint sequencing
- `Sonic3kBonusStageCoordinator` still owns active slot runtime creation, per-frame dispatch, and cleanup

No replacement is needed here beyond tightening the handoff points.

### Slot runtime owner

`S3kSlotBonusStageRuntime` becomes a thin coordinator with five responsibilities:

- bootstrap slot-stage state and player swap
- own the slot-specific subsystem instances
- execute the per-frame call order
- expose slot runtime state to palette/scroll/render integration points
- cleanly restore the original player and sidekick state on exit

It should stop directly owning detailed gameplay rules where a dedicated slot subsystem is more faithful.

### Slot state owner

Create or reshape a dedicated slot-stage state holder that owns the ROM-shaped mutable stage fields:

- scalar and rotation state
- stat-table-derived angle state
- reel state
- payout and reward state
- last-collision state
- animation timers
- palette mode
- exit mode

This state object should be authoritative. Other slot systems consume and mutate it through explicit methods rather than duplicating parallel state.

### Slot player runtime

Replace the current `S3kSlotBonusPlayer` custom-physics approximation with a dedicated slot-player runtime that still integrates with sprite rendering and HUD state, but owns its own slot movement and collision path.

Responsibilities:

- player init/status flags
- character-specific art/mapping setup
- per-frame movement integration
- collision rollback
- tile hit reporting
- object-controlled capture/release path
- camera anchor contribution

This runtime must be shaped by `Obj_Sonic_RotatingSlotBonus`, not by the normal `PlayableSpriteMovement` model.

### Slot render/layout system

Replace the semantic-cell layout renderer with a renderer that ports `Slots_RenderLayout` directly enough that:

- layout pieces come from ROM-derived slot layout and piece tables
- the layout is staged into the same expanded runtime buffer shape the ROM uses, including the wider row stride used by the slot layout buffer
- the piece-definition table is staged into the runtime chunk table shape the ROM uses
- the transformed point grid and transient slot tile-animation state are staged in ROM-equivalent runtime buffers rather than hidden in unrelated abstractions
- transformed screen positions come from the slot rotation/scalar state
- animated piece selection is driven by the slot animation timers
- transient tile animations are driven by slot runtime animation state matching the ROM helper routines around `Slots_RenderLayout`

The stage layout is a post-sprite stage-render pass, not a population of generic objects.

### Slot option-cycle system

Replace the simplified reel helper with a dedicated system for `Slots_CycleOptions`.

Responsibilities:

- per-frame reel-state progression
- visible reel symbol updates
- target selection
- final reward resolution
- option-strip art source selection
- option-strip staging and DMA/update handoff in the same conceptual position as the ROM routine
- handoff to the cage/reward flow when a result is ready

### Slot collision/tile system

Use a focused slot collision subsystem that owns:

- player collision footprint checks
- ring pickup checks
- tile classification
- tile response generation

The player runtime should ask this system for slot-specific collisions rather than embedding ad hoc semantic checks.

### Slot objects

Keep the slot cage, ring reward, and spike reward as dedicated S3K objects, but restore normal object ownership:

- the cage object owns its render commands and state transitions
- reward objects own their own lifetime and render commands
- the runtime injects/spawns them and updates cross-cutting stage state only where necessary

The runtime should not manually render these objects as a substitute for object implementation.

### Slot visual systems

Retain the engine integration points, but make them consume ROM-shaped slot state:

- `Sonic3kPaletteCycler` for `AnPal_Slots`
- `SwScrlSlots` for slot screen/background deformation
- the cage-driven `Events_bg` anchor/state that the ROM screen and background handlers consume
- a stage-layout render hook that preserves the ROM ordering of `Render_Sprites` followed by `Slots_RenderLayout`

## Required Per-Frame Order

The frame order must be explicit and stable. The ROM does not treat Slots as an early-stage background pass; it updates gameplay during the main level loop, renders normal sprites, then runs `Slots_RenderLayout`, then runs `Slots_CycleOptions`.

1. clear slot-player last-collision state
2. advance slot-stage rotation/scalar state
3. advance the slot-player runtime
4. run slot collision and tile response handling
5. advance cage state
6. advance reward child objects
7. update camera from slot-player state
8. update palette-cycle mode and screen/background inputs
9. render normal sprites and objects through the regular sprite path
10. run the slot stage-layout render pass in the ROM-equivalent post-sprite position
11. advance `Slots_CycleOptions` in the ROM-equivalent position after layout rendering

If the engine needs to precompute render data earlier for practical reasons, the externally visible ordering and frame-to-frame state transitions must still match the ROM.
This order is part of the contract. Any major deviation must be justified against the disassembly.

## Integration Boundaries

### Keep

- current `GameLoop` bonus-stage entry and exit flow
- `Sonic3kBonusStageCoordinator` as slot runtime owner
- current saved-state and reward-restoration lifecycle

### Replace or substantially rewrite

- `S3kSlotBonusPlayer`
- `S3kSlotLayoutRenderer`
- `S3kSlotReelStateMachine`
- `S3kSlotGridCollision`
- `S3kSlotTileInteraction`
- `S3kSlotBonusCageObjectInstance`
- `S3kSlotRingRewardObjectInstance`
- `S3kSlotSpikeRewardObjectInstance`
- `SwScrlSlots`
- large parts of `S3kSlotBonusStageRuntime`
- `S3kSlotRomData` where it still encodes reconstructed semantics instead of ROM-shaped data

### Cleanup requirement

The remediation must explicitly clean up the existing broken Slot Machine implementation while the new one is introduced.

That includes:

- deleting dead helper code that only exists to support superseded semantic-slot behavior
- removing runtime-side rendering fallbacks once cage and reward objects render through their proper ownership paths
- eliminating duplicate state holders when the new ROM-shaped slot-stage state becomes authoritative
- deleting obsolete tests that encode the behavior of the broken approximation rather than the ROM
- renaming or reshaping classes whose current names imply fidelity they do not actually provide
- ensuring there is exactly one active Slot Machine gameplay path in production code when the remediation is complete

The final codebase should not contain both:

- a ROM-shaped Slot Machine implementation
- a parallel approximation-layer Slot Machine implementation kept around “just in case”

If a temporary compatibility seam is required during execution, it must be removed before the remediation is claimed complete.

## Testing And Verification

The remediation must be verified at three levels.

### Pure state and table tests

- ROM-derived slot tables
- reel target selection
- reward decoding
- animation timer progression
- collision/tile classification

### Headless runtime tests

- slot-player swap and restore
- sidekick suppression
- player movement and collision responses
- goal trigger and exit handoff
- cage capture, reward spawn cadence, and release

### In-engine parity checks

- stage rotation and piece placement
- player visuals for Sonic/Tails/Knuckles
- reel timing and reward outcomes
- cage motion and reward object motion
- palette cycle behavior
- exit wind-down and fade

Completion requires all three. Test coverage alone is not sufficient if in-engine behavior is still visibly wrong.

## Execution Strategy

Implement in four ordered slices:

1. Replace slot state, player runtime, and collision/tile handling.
2. Replace layout rendering and option-cycle logic.
3. Replace cage/reward/exit behavior.
4. Replace screen/background/palette behavior, remove superseded code paths, and perform parity verification.

Each slice should leave the branch in a coherent, testable state, but the feature should not be claimed complete until all four slices are done and verified together.
