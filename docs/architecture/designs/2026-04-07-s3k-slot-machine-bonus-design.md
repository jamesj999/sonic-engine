# S3K Slot Machine Bonus Stage Design

## Goal

Implement the Sonic 3 and Knuckles locked-on Slot Machine bonus stage as a one-shot, ROM-accurate feature using the existing S3K bonus-stage lifecycle and level pipeline, with no placeholder behavior, no intentionally omitted gameplay pieces, and no temporary fallback architecture.

## Scope

### In scope

- Locked-on S3K single-player Slot Machine bonus stage at zone `0x15`
- Full stage bootstrap, runtime, payout flow, and exit flow
- Dedicated slot-bonus player runtime matching the ROM's `Obj_Sonic_RotatingSlotBonus`
- Character-aware parity for Sonic, Tails, and Knuckles within the slot-bonus player path
- Cage/controller behavior, reel state machine, payout tables, ring/spike reward emitters, and animated slot options
- Level-pipeline rendering parity for the rotating slot-stage layout
- Bonus-stage lifecycle integration with the current coordinator, title card, fade, and return-to-checkpoint flow
- Design documentation of all slot-stage elements that must reach parity

### Out of scope

- 2P / competition variants
- S&K-standalone / non-locked-on slot-stage behavior differences
- Shared S2/S3K slot gameplay framework
- Partial implementation phases, TODO-based rollout, or intentionally incomplete parity

### Future-reference note

The design should explicitly note that alternate ROM variants may exist, but the implementation target is the locked-on S3K version only. Future work may investigate those variants once the locked-on path is complete and verified.

## Constraints

- Prefer ROM-shaped architecture when the ROM has a clearly distinct runtime path.
- Reuse existing engine systems only when they preserve parity and reduce complexity without distorting ownership boundaries.
- Treat Sonic 2 CNZ slot machine code as a rendering/reference source, not as the owner of S3K slot gameplay.
- Treat Sonic 1 special-stage math as a reference only where the lookup-table or scalar style truly matches.
- Keep Slot Machine behavior in S3K-specific code unless near-identical cross-game parity is demonstrated later.
- Do not force the normal playable controller to impersonate slot-stage behavior if the ROM replaces the player object.

## Existing Engine Baseline

### Bonus-stage lifecycle

The engine already supports S3K bonus-stage entry and exit through:

- `GameLoop.enterBonusStage(...)`
- `GameLoop.doEnterBonusStage(...)`
- `GameLoop.applyDeferredBonusStageSetup()`
- `GameLoop.exitBonusStage(...)`
- `Sonic3kBonusStageCoordinator`

This lifecycle already handles:

- saving zone/checkpoint/ring/timer/player state
- loading bonus-stage zones through `LevelManager.loadZoneAndAct(...)`
- entering bonus-stage title cards before gameplay
- exiting back to the saved zone and restoring checkpoint state

### Existing S3K bonus-stage precedent

Gumball and Pachinko already established the current preferred pattern:

- keep the stage in `GameMode.BONUS_STAGE`
- load the actual S3K level data for the zone
- implement ROM-specific stage behavior through S3K-specific objects and runtime code
- rely on the existing coordinator for entry/exit persistence and rewards

This means Slots should follow the same high-level lifecycle, but with its own dedicated runtime pieces.

### Existing reuse candidates

#### Sonic 2 CNZ slot machine

Useful as a reference for:

- reel/window presentation ideas
- shader-based scrolling display techniques
- how to represent face indices and offsets cleanly in engine code

Not suitable as the core runtime owner because CNZ is a local object feature, while S3K Slots is the entire stage's gameplay loop.

#### Sonic 1 special-stage scalar code

Useful as a reference for:

- table-driven trigonometric/scalar logic
- stage rotation concepts

Not suitable as the owning runtime because S3K Slots is still a level-mode bonus stage, not a separate special-stage manager.

## ROM Findings That Drive The Design

The locked-on S3K slot stage is not just a local slot display. The ROM explicitly does all of the following:

- swaps `Player_1` to `Obj_Sonic_RotatingSlotBonus`
- runs `Slots_RenderLayout` every frame during the main level loop
- runs `Slots_CycleOptions` every frame during the main level loop
- initializes slot-stage layout and object tables in `sub_4B6AA`
- uses a dedicated cage/controller object at `loc_4BF62`
- stores slot-stage runtime state in `SStage_scalar_*`, `Stat_table`, and slot-specific timers
- spawns transient reward objects:
  - `Obj_SlotRing`
  - `Obj_SlotSpike`
- drives character-aware player art/mapping selection inside the rotating slot-bonus player object

These findings rule out a "normal player plus slot overlay" design.

## Recommended Architecture

Implement Slots as a dedicated S3K bonus-stage subsystem layered on top of the current level-based bonus-stage lifecycle.

### Top-level structure

- `Sonic3kBonusStageCoordinator`
  - continues to own entry/exit state
  - owns the active slot-stage runtime instance when `activeType == SLOT_MACHINE`
  - dispatches per-frame slot updates from `onFrameUpdate()`

- `GameLoop`
  - remains the lifecycle owner for title-card transition, load, fade, and return
  - gains Slots-specific bootstrap wiring after the bonus-stage title card completes

- S3K Slots runtime package
  - new package: `com.openggf.game.sonic3k.bonusstage.slots`
  - contains the stage runtime, player runtime, controller/state machine, layout renderer, and option animator

### Key runtime pieces

#### `S3kSlotBonusStageRuntime`

Top-level coordinator for the slot stage. Owns the stage-specific subsystems, manages bootstrap, and exposes:

- `initialize(...)`
- `update(frameCounter)`
- `reset()`
- read-only runtime state needed by renderers or reward objects

Responsibilities:

- stage bootstrap after bonus title card exit
- active player replacement
- slot-stage controller creation
- option animator ticking
- layout renderer ticking
- cleanup on bonus-stage exit or reset

#### `S3kSlotBonusPlayer`

A dedicated slot-bonus player runtime modeled on `Obj_Sonic_RotatingSlotBonus`. This should be implemented as an `AbstractPlayableSprite` subclass so it integrates with:

- camera focus
- existing sprite rendering / DPLC
- player art selection
- HUD / ring state
- existing sprite manager and physics update scheduling

Responsibilities:

- character-specific mapping selection for Sonic / Tails / Knuckles
- Tails-alone tail setup parity
- slot-stage left/right movement around the rotating layout
- jump handling
- air/ground transitions
- slot-stage collision sampling
- camera anchoring
- exit spin/fade path

This runtime should replace the normal main sprite by code during the slot stage rather than bolting a "slot mode" onto `PlayableSpriteController`.

#### `S3kSlotStageController`

Owner of ROM-equivalent stage globals and reel state. Responsibilities:

- scalar indices and scalar result state
- stage angle / rotation speed state
- reel positions, speeds, sub-states, and targets
- payout selection and resolution
- option-cycle state machine from `Slots_CycleOptions`
- stage-global timers:
  - goal frame timer/frame
  - peppermint frame timer/frame
  - ring frame usage
  - payout counters

This component should be authoritative for gameplay state. Rendering should consume its state, not duplicate it.

#### `S3kSlotLayoutRenderer`

Owner of `Slots_RenderLayout` parity. Responsibilities:

- rotating the slot-stage layout into screen-space renderables
- sampling the slot layout map and object-like tile definitions
- drawing colored walls, bumpers, goal, ring, R-and-peppermint, and slot-window pieces in the same stage-shaped render path as the ROM

This is not a generic object renderer. It is stage-layout rendering logic.

#### `S3kSlotOptionAnimator`

Owner of animated slot-option content. Responsibilities:

- update animated slot option tiles from `ArtUnc_SlotOptions`
- maintain goal and peppermint frame timers
- expose current option-frame state to the layout renderer or tile updater

This may feed tile updates through the level/pattern pipeline, but it should not own gameplay progression.

#### Slot controller objects

Implement dedicated S3K slot objects:

- `S3kSlotBonusCageObjectInstance`
  - capture logic
  - player snap/control lock
  - reel-arm trigger
  - payout spawn sequencing
  - transition to exit state

- `S3kSlotRingRewardObjectInstance`
  - orbit-inward ring payout object
  - reward delivery timing

- `S3kSlotSpikeRewardObjectInstance`
  - orbit-inward spike payout object
  - ring drain timing
  - spike-hit SFX cadence

These are stage-specific controller/reward objects, not generic cross-game systems.

## Detailed Runtime Flow

### Entry bootstrap

After the bonus-stage title card finishes and `GameMode` changes to `BONUS_STAGE`:

1. The normal S3K slot level data is already loaded via the standard bonus-stage load path.
2. `GameLoop.applyDeferredBonusStageSetup()` (or a replacement hook called from there) delegates to the slot runtime bootstrap.
3. The runtime:
   - captures the current main playable sprite identity and state needed for restoration
   - creates and swaps in `S3kSlotBonusPlayer` under the same sprite code
   - initializes slot controller globals
   - initializes option animation timers
   - injects or spawns the slot cage/controller object
   - applies slot-stage-specific camera/deform configuration
4. Bonus-stage HUD timer remains paused and carried-over ring count remains visible.

### Per-frame update

Each `BONUS_STAGE` frame, in addition to normal engine flow:

1. `Sonic3kBonusStageCoordinator.onFrameUpdate()` ticks the slot runtime.
2. The slot runtime:
   - updates slot-stage controller state
   - updates option animation state
   - updates any slot bootstrap-owned controller objects if needed
3. The slot-bonus player participates in normal sprite manager updates, but its movement/collision path is slot-specific.
4. The slot layout renderer and option animator update the visible rotating stage.

### Capture sequence

When the player enters the cage hitbox:

- snap player position to cage center
- zero X/Y/G speed
- enable object control lock
- start palette/goal-cycle state
- initialize reel resolution state

The cage/controller remains the owner of this capture flow so stage progression stays ROM-shaped.

### Reel resolution

The reel-resolution state machine follows the S3K ROM path, not the S2 CNZ one:

- initialize per-reel offset/speed/sub-state
- select target combination from S3K probability data
- advance each reel through wait / approach / reverse / done phases
- update option-tile windows to reflect current reel positions
- decode final result through the S3K payout table

### Reward payout

Positive result:

- spawn ring reward objects
- deliver reward through staged ring-object completion rather than a direct HUD increment shortcut

Negative result:

- spawn spike reward objects
- drain rings one at a time, matching the ROM timing path
- play the appropriate impact SFX cadence

The cage/controller should track active payout children and only advance when the reward sequence is complete.

### Exit

When the payout/goal flow finishes:

- transition the slot player into the ROM exit spin/fade path
- request bonus-stage exit through the existing provider/coordinator
- let the existing bonus-stage lifecycle restore the saved zone/checkpoint/timer state
- tear down the slot runtime cleanly

## File Design

### New files

- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRewardResolver.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`

### Existing files to modify

- `src/main/java/com/openggf/GameLoop.java`
  - replace the current `resolveBonusStageBootstrapSpawn()`-style special case with a slot runtime bootstrap path
  - trigger Slots bootstrap after the bonus-stage title card
  - restore the original main playable sprite cleanly on exit if runtime swapping requires it

- `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
  - own active slot runtime instance
  - initialize/tear down runtime on enter/exit
  - tick slot runtime from `onFrameUpdate()`

- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
  - likely no structural change required, but the design relies on existing replacement-by-code behavior
  - if needed, add a narrowly scoped helper for main playable replacement and restoration

- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
  - register any slot-stage objects that appear in the stage object list

- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
  - add missing slot-stage art/map/layout/animation/ROM offset constants

- `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
  - add slot-specific art keys

- `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`
  - register slot object art if dedicated object art is not already in level tiles

- `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
  - only if a thin adapter is needed for slot tile animation
  - do not move the full slot gameplay state machine into this class

## Rendering Strategy

### Layout rendering

The ROM's `Slots_RenderLayout` behaves more like a stage-specific sprite-layout renderer than a normal object system. The engine implementation should follow that shape:

- consume the slot stage layout map
- project/render visible slot pieces based on the current stage angle and camera
- keep the rendering pipeline explicit and testable

Preferred implementation:

- first implement a CPU-driven ROM-shaped layout renderer that is easy to validate
- only borrow CNZ shader ideas if they simplify reel-window drawing without distorting behavior

### Slot window display

Use the Sonic 2 CNZ implementation only as a reference for:

- representing current face plus next face plus offset
- efficient vertical window scrolling in a shader, if the S3K stage windows can be represented that way

Do not force S3K reel logic into `CNZSlotMachineManager` or a shared slot core.

## Player Replacement Strategy

The design should explicitly prefer temporary sprite replacement over "special mode" hacks.

Rationale:

- the ROM swaps the player object
- the slot-bonus player has different movement and collision semantics
- `SpriteManager.addSprite(...)` already supports replacement by sprite code
- keeping the slot player as its own sprite class makes ownership clearer and reduces normal-controller contamination

Requirements:

- preserve the logical main character code so existing camera/HUD lookups still work
- preserve character identity for Sonic/Tails/Knuckles art selection
- restore the original main playable sprite cleanly on exit

## ROM Parity Inventory

The written implementation must explicitly cover each of the following:

### Player parity

- Sonic/Tails/Knuckles mapping selection
- Tails-alone tail object setup
- rolling animation parity
- jump behavior
- left/right acceleration and deceleration
- air/ground transition behavior
- slot-stage camera tracking
- stage rotation coupling through scalar state

### Stage parity

- slot layout bootstrap from ROM layout data
- rotating layout render path
- collision against slot layout cells
- colored wall rendering
- goal rendering and goal-frame timing
- ring-frame usage
- R / peppermint animation timing
- animated slot options from `ArtUnc_SlotOptions`

### Reel parity

- target combination probability thresholds
- per-reel face sequences
- reel sub-state progression
- reverse-stop alignment logic
- final payout decode table

### Reward parity

- ring reward object spawn and motion
- spike reward object spawn and motion
- ring drain cadence
- reward completion conditions
- SFX timing for slot, goal, spike hit, bumper, flipper, and launch behavior as applicable

### Lifecycle parity

- entry bootstrap timing
- carried-over ring count shown in HUD
- extra-life flag restoration
- bonus-stage exit request timing
- zone/checkpoint return behavior
- slot runtime cleanup and state reset

## Testing Strategy

### Unit tests

- reward decode table matches ROM values
- target selection logic matches ROM thresholds
- reel state transitions match ROM tables/state progression
- option animation timer/frame logic matches ROM cadence

### Registry and asset tests

- slot-stage object registry coverage for zone `0x15`
- slot object art registration coverage
- slot constants/offset smoke tests

### Headless runtime tests

- entering Slots swaps the main sprite to `S3kSlotBonusPlayer`
- slot player movement and jump behavior are active in `BONUS_STAGE`
- cage capture locks player and starts reel resolution
- positive payout produces ring reward flow and increases saved rewards correctly
- negative payout produces spike reward flow and drains rings correctly
- bonus-stage exit restores the previous level/checkpoint state without leaked slot globals

### Pattern/render tests

- animated slot option tiles change at the expected destination tiles over time
- slot layout renderer updates visible stage content as angle/camera changes

### ROM-reference tests

- where feasible, use ROM-derived tables directly in assertions
- if trace capture is practical later, add targeted parity traces for one or two canonical slot-stage interactions

## Risks And Mitigations

### Risk: overloading generic systems

If slot behavior is spread across `GameLoop`, `PatternAnimator`, generic playable code, and random objects, parity bugs will be hard to reason about.

Mitigation:

- keep the slot runtime as the owner
- keep rendering, controller state, and reward sequencing in slot-specific classes

### Risk: fake reuse from S2 CNZ

A shared slot gameplay framework may appear attractive, but the underlying stage model is too different.

Mitigation:

- reuse only narrow rendering ideas
- keep gameplay/state ownership S3K-specific

### Risk: player replacement side effects

Swapping the main playable sprite could affect camera, HUD, or return-to-level restoration.

Mitigation:

- preserve the original sprite code
- centralize replacement/restore logic in the slot runtime bootstrap/teardown
- add explicit headless lifecycle tests

### Risk: incomplete ROM mapping

Missing one timer table, face table, or animation destination would leave the stage visibly wrong despite "mostly working."

Mitigation:

- document every required slot-stage element in the spec and later plan
- convert all known ROM tables/constants up front before implementation begins

## Recommended Implementation Direction

Use a ROM-shaped, S3K-specific implementation with:

- dedicated slot-bonus player runtime
- dedicated slot-stage controller
- dedicated slot layout renderer
- dedicated slot option animator
- dedicated slot cage/reward objects

Borrow from Sonic 2 CNZ only when a rendering technique is clearly compatible. Do not create a shared S2/S3K slot gameplay abstraction unless later evidence demonstrates unusually high parity across the full runtime shape.

## Success Criteria

The design is satisfied when the implementation:

- enters locked-on S3K Slots from star posts correctly
- runs a dedicated slot-bonus player runtime instead of the normal player controller
- renders the rotating slot-stage layout and animated slot options correctly
- resolves reel results from the S3K ROM tables and state machine
- spawns and resolves ring/spike payouts correctly
- exits cleanly through the existing bonus-stage lifecycle
- leaves no intentional TODOs, gap notes, or placeholder behavior in the slot-stage implementation
