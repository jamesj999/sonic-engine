# S3K MGZ Bring-Up Design

## Goal

Bring Marble Garden Zone to a broad, disassembly-backed Sonic 3 and Knuckles parity state in the main workspace, covering:

- MGZ-specific level events for both acts
- MGZ animated tiles with boss gating
- MGZ Act 1 to Act 2 seamless transition
- MGZ Act 2 earthquake, background-event, and collapse orchestration
- MGZ boss-chain object support sufficient for end-to-end zone flow

This design explicitly targets the locked-on S3K behavior described in `docs/skdisasm/sonic3k.asm` and uses the existing long-form zone analyses in `docs/architecture/research/s3k-zones/` as the required depth baseline for any analysis updates.

## Workspace And Analysis Constraints

### Main-workspace requirement

The MGZ analysis source of truth must remain in the main workspace:

- `docs/architecture/research/s3k-zones/mgz-analysis.md`

The work should not move the analysis into a worktree-specific path or depend on a forked copy as the authoritative document. If implementation work later uses worktrees, the canonical analysis remains the copy in the main repository checkout.

### Required analysis depth

`docs/architecture/research/s3k-zones/mgz-analysis.md` is already in the same depth class as the longer existing zone analyses:

- `docs/architecture/research/s3k-zones/lbz-analysis.md`
- `docs/architecture/research/s3k-zones/aiz-analysis.md`
- `docs/architecture/research/s3k-zones/icz-analysis.md`
- `docs/architecture/research/s3k-zones/hcz-analysis.md`

Any follow-up expansion of the MGZ analysis should match those documents in specificity: concrete labels, trigger thresholds, object identities, state-machine stages, and cross-cutting concerns. Short summary-only notes are not sufficient.

### Disassembly authority

The analysis is a guide, but the ROM disassembly is the final authority. The implementation must cross-reference at least these `docs/skdisasm/sonic3k.asm` labels:

- `MGZ1_Resize`
- `MGZ2_Resize`
- `MGZ1_ScreenEvent`
- `MGZ1_BackgroundEvent`
- `MGZ2_ScreenEvent`
- `MGZ2_QuakeEvent`
- `MGZ2_ChunkEvent`
- `MGZ2_LevelCollapse`
- `MGZ2_BackgroundEvent`
- `MGZ2_BGEventTrigger`
- `AnimateTiles_MGZ`
- `AniPLC_MGZ`
- `Obj_MGZ2DrillingRobotnik`
- `Obj_MGZEndBoss`
- `Obj_MGZEndBossKnux`
- `Obj_MGZ2_BossTransition`
- `Obj_MGZ2LevelCollapseSolid`

## Scope

### In scope

- Broad MGZ zone bring-up for S3K act flow and boss-chain orchestration
- New MGZ zone event implementation integrated with `Sonic3kLevelEventManager`
- MGZ Act 1 intro player-state parity
- MGZ animated tile hookup in `Sonic3kPatternAnimator`
- MGZ boss-gated AniPLC behavior
- MGZ Act 1 results-triggered seamless transition to Act 2
- MGZ Act 2 resize/camera lock logic
- MGZ Act 2 quake trigger orchestration and associated boss spawns
- MGZ Act 2 background-event trigger state and background-collision gating
- MGZ Act 2 collapse sequencing, including runtime tile/chunk mutation and temporary collapse solids
- Registration and implementation of MGZ boss-related objects currently missing from the object registry
- Automated tests covering animation, event flow, and at least key headless orchestration paths

### Out of scope

- Pixel-perfect completion of every deep boss attack-state nuance in the first pass if the zone orchestration is already correct
- Generic cross-zone abstractions invented solely for MGZ unless the engine clearly needs them
- Palette cycling implementation for MGZ, because the disassembly and analysis both confirm MGZ maps to `AnPal_None`
- Replacing the existing `SwScrlMgz` parallax implementation wholesale unless a disassembly cross-check reveals a concrete parity bug

## Current Baseline

The current codebase already has:

- `SwScrlMgz` registered through `Sonic3kScrollHandlerProvider`
- MGZ art registry coverage for existing MGZ badniks and level-art objects
- `Background_collision_flag` support in `GameStateManager` and `GroundSensor`
- `MutableLevel` plus dirty-region invalidation support for runtime level mutation
- established zone-event patterns in `Sonic3kAIZEvents` and `Sonic3kHCZEvents`
- established S3K pattern-animation infrastructure in `Sonic3kPatternAnimator`

The current codebase does not yet have:

- MGZ event wiring in `Sonic3kLevelEventManager`
- MGZ cases in `Sonic3kPatternAnimator`
- object factory coverage for `MGZ2DrillingRobotnik`, `MGZEndBoss`, or `MGZEndBossKnux`
- MGZ collapse solids
- a completed MGZ boss-chain orchestration path

This means MGZ is not a simple "add one switch case" task. It is a zone-level feature bring-up spanning events, object runtime, and runtime world mutation.

## Recommended Architecture

Implement MGZ as a coordinated set of MGZ-specific components rather than scattering behavior across generic managers.

### Core event owner: `Sonic3kMGZEvents`

Create a new zone-specific handler:

- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java`

This class should extend `Sonic3kZoneEvents` and own all MGZ FG/BG event state that is currently absent. It should expose MGZ-specific state needed by:

- `Sonic3kLevelEventManager`
- `Sonic3kPatternAnimator`
- `SwScrlMgz` if additional MGZ2 state hooks are required
- MGZ boss/collapse objects

Its responsibilities should include:

- Act 1 intro-fall setup integration
- Act 1 screen shake behavior
- Act 1 background event transition sequencing
- Act 2 resize state machine
- Act 2 screen-event routine state
- Act 2 quake trigger state and one-shot flags
- Act 2 chunk-event routine state
- Act 2 background-event and BG trigger state
- collapse state progression
- shared MGZ event variables analogous to the ROM `Events_fg_*` and `Events_bg[*]`

### Animation owner: `Sonic3kPatternAnimator`

MGZ animated tiles belong in the existing S3K pattern animator, but only as MGZ-specific cases:

- add `resolveAniPlcAddr()` support for MGZ acts
- add MGZ update behavior that mirrors `AnimateTiles_MGZ`
- gate all MGZ AniPLC updates on `Boss_flag`

The pattern animator should query MGZ boss state from `Sonic3kLevelEventManager` / `Sonic3kMGZEvents`, just as AIZ already consults event state.

### Boss and collapse object surface

Add the missing MGZ boss-chain object classes and register them in `Sonic3kObjectRegistry`. These classes should own object-local behavior, while `Sonic3kMGZEvents` owns zone orchestration.

Required object surface:

- `Mgz2DrillingRobotnikInstance`
- `MgzEndBossInstance`
- `MgzEndBossKnuxInstance`
- `MgzBossTransitionInstance`
- `MgzLevelCollapseSolidInstance`

The naming can follow existing repo conventions, but the important split is:

- zone event manager owns when they spawn and what global flags they affect
- object instances own their runtime object behavior, collision, and side effects local to that object

### Runtime mutation path

MGZ2 chunk destruction and collapse should use the existing runtime level-mutation infrastructure rather than bespoke tilemap hacks.

Preferred direction:

- operate against the currently loaded level when it is mutable
- if the current level is not mutable, snapshot or convert through the existing level manager path before mutation is required
- mark dirty regions and invalidate tilemaps through existing `MutableLevel` / `LevelManager` mechanisms

The collapse path should therefore be modeled as explicit world-state mutation plus redraw invalidation, not as a purely visual fake.

## Detailed Feature Design

### 1. MGZ Act 1 intro state

`Sonic3kLevelEventManager.applyZonePlayerState()` currently contains a TODO for MGZ1 intro-fall behavior. This should be completed so MGZ1 matches the ROM's falling start:

- airborne start state
- forced animation matching the falling intro
- sidekick handling consistent with the existing HCZ intro conventions where applicable

This work belongs in `Sonic3kLevelEventManager`, because that class already owns zone-specific spawn-state application.

### 2. MGZ Act 1 screen and background events

#### `MGZ1_ScreenEvent`

Implement:

- screen-shake offset application to camera copy
- shake-sound triggering using MGZ rules
- normal tile drawing flow

This should be minimal but real. MGZ's identity depends on the quake feel, so the screen-event path cannot remain absent.

#### `MGZ1_BackgroundEvent`

Implement the two-state BG flow:

- normal state
- transition-preparation / transition-completion state

The transition path must:

- respond to `Events_fg_5`
- queue or otherwise apply the Act 2 art/block/chunk changes
- trigger the seamless reload to Act 2
- apply the `-$2E00, -$600` player/camera offset
- clear the MGZ BG/FG event state used by Act 2

The engine already has a seamless-transition pattern in HCZ and AIZ. MGZ should reuse that style, but the payload must match MGZ's actual disassembly behavior and state clearing.

### 3. MGZ animated tiles

Implement `AniPLC_MGZ` resolution for both acts and the wrapper logic from `AnimateTiles_MGZ`.

Requirements:

- the MGZ scripts resolve from the proper ROM address
- destination tile indices and script metadata come from the ROM script parser rather than hardcoded frame tables where possible
- MGZ animation halts when `Boss_flag` is set

This is a strict parity requirement, not an optional polish item. The disassembly wrapper is unambiguous.

### 4. MGZ Act 2 resize flow

Implement `MGZ2_Resize` as a dedicated state machine in `Sonic3kMGZEvents`.

Responsibilities:

- lock camera Y and max X when the player enters the boss approach range
- revert if the player retreats before boss commitment
- lock min X and spawn the end boss once the camera reaches the final threshold
- stop advancing once the boss is active

The `MGZ1_Resize` bug note should be preserved as a code comment and behavior decision. The implementation target should be the intended locked-on S3K behavior, not accidental Act 1 boss leakage.

### 5. MGZ Act 2 quake orchestration

Implement the seven-state `MGZ2_QuakeEvent` orchestration using explicit MGZ state fields.

Requirements:

- track the three one-shot trigger flags
- validate X/Y trigger windows using center-coordinate semantics
- apply camera bounds and lock conditions at the correct times
- spawn `MGZ2DrillingRobotnik` at the exact disassembly-backed coordinates and facings
- force continuous shake when the event is active
- clear or restore bounds when the continuation threshold is met

This is event orchestration, not just object spawning. The zone must still behave correctly if object AI is incomplete.

### 6. MGZ Act 2 chunk events

Implement `MGZ2_ChunkEvent` as runtime world mutation.

Requirements:

- detect the three trigger regions from the disassembly
- enforce the event-1 continuous-shake prerequisite
- mutate the relevant chunk data progressively every seven frames
- redraw or invalidate the appropriate map/tile regions
- terminate after the disassembly-backed step count and clear the relevant event state

This is the largest world-state requirement in MGZ. The implementation should prefer correctness and traceability over premature abstraction.

### 7. MGZ Act 2 collapse sequence

Implement `MGZ2_LevelCollapse` with these engine-visible outcomes:

- remove the arena support chunks
- spawn the temporary solid supports
- progress the collapse columns with staggered delay and capped motion
- drive the switch from normal draw to collapse draw behavior
- stop the collapse when all columns reach terminal state
- advance the screen-event state to the post-collapse boss BG movement phase

The design does not require a generic "special VBlank routine" framework unless the current renderer truly cannot express MGZ's collapse any other way. The success criterion is correct gameplay and visibly correct collapse behavior, not a literal emulator of the original interrupt plumbing.

### 8. MGZ Act 2 background event and BG trigger logic

Implement the MGZ2 BG state machine and its "background rises up" trigger path.

Requirements:

- manage the four BG trigger states
- branch correctly between Sonic/Tails and Knuckles routes
- toggle `Background_collision_flag` as the disassembly specifies
- propagate BG offset / movement state needed by scroll and object code
- support boss BG movement override after collapse

If `SwScrlMgz` needs MGZ2-specific state inputs beyond the current act-based logic, expose narrowly scoped setters or state accessors rather than teaching the scroll handler to own event progression.

### 9. MGZ boss-chain objects

The broad bring-up requires the boss chain to exist as real runtime objects.

#### `MGZ2DrillingRobotnik`

Minimum required parity for bring-up:

- correct spawn coordinates and facing
- boss flag, music, palette, and PLC side effects
- enough active behavior to participate in quake encounters
- defeat path that sets the collapse trigger

#### `MGZEndBoss`

Minimum required parity for bring-up:

- correct boss spawn, arena integration, and boss setup
- end-boss state progression sufficient to finish the zone
- CNZ transition trigger at the end of the fight
- palette fade integration for the MGZ-to-CNZ transition

#### `MGZEndBossKnux`

If Knuckles-specific object logic differs materially, it should be represented explicitly rather than hidden in a conditional inside the main boss class.

#### `MGZ2_BossTransition`

Implement the post-defeat carry / transition object so that:

- Sonic/Tails path is supported
- Knuckles path bypasses Tails-carry logic as the analysis documents

#### `MGZ2LevelCollapseSolid`

Implement invisible solid supports as dedicated temporary solid objects. They should be simple, explicit, and tied to the MGZ collapse sequence rather than generalized prematurely.

## File Design

### New files

- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java`
- `src/main/java/com/openggf/game/sonic3k/objects/Mgz2DrillingRobotnikInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/MgzEndBossKnuxInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/MgzBossTransitionInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/MgzLevelCollapseSolidInstance.java`
- MGZ-specific tests under `src/test/java/com/openggf/game/sonic3k/` and `src/test/java/com/openggf/game/sonic3k/events/`

### Existing files to modify

- `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlMgz.java` only if new event-fed hooks are required
- any MGZ art/PLC registry files needed for boss objects

## Error Handling And Ownership Rules

### Event state ownership

MGZ event routines should not be split arbitrarily across unrelated classes. `Sonic3kMGZEvents` is the owner of MGZ global event state. Other classes may read or set narrowly defined flags, but they should not each invent their own parallel MGZ routine counters.

### Coordinate semantics

All trigger windows and boss spawn points must follow the engine's documented coordinate rules:

- ROM `x_pos` / `y_pos` map to `getCentreX()` / `getCentreY()`
- `getX()` / `getY()` are top-left bounds only

MGZ has several precise X/Y trigger windows. Mixing center and top-left semantics here would silently break parity.

### Fallback behavior

If a subsystem cannot complete a behavior because a required resource is missing, the code should fail loudly in logs and avoid corrupting event state. Silent no-op collapse or silent boss-spawn failure would make MGZ debugging needlessly expensive.

### No fake generic layer

Do not invent a generic "S3K boss event framework" or generic "special collapse mode" unless at least one other zone already needs the same shape. MGZ should be implemented cleanly, but the code should stay honest about being MGZ-specific where that is the true boundary.

## Testing Strategy

### TDD requirement

Implementation should follow test-first additions for each new behavior slice. The first tests should target the highest-confidence orchestration behaviors before object-AI depth work.

### Animation tests

Add an MGZ pattern-animation test similar in spirit to existing HCZ animation coverage:

- verify MGZ AniPLC scripts load
- verify destination tiles change over updates
- verify updates stop while MGZ boss state is active

### Event unit tests

Add focused tests for:

- MGZ1 intro-fall player state
- MGZ1 background-event transition trigger behavior
- MGZ2 resize camera-lock progression
- MGZ2 quake trigger windows and one-shot flags
- MGZ2 BG trigger state transitions and background-collision toggling

### Headless integration tests

Add at least these higher-level tests:

- MGZ Act 1 to Act 2 seamless transition test
- MGZ Act 2 quake encounter orchestration test
- MGZ Act 2 collapse progression test
- MGZ end-boss spawn / zone-finish flow test

These tests should assert event progression, camera bounds, spawned object classes, and key game-state side effects rather than overfitting to implementation internals.

### Regression tests

Add checks that:

- MGZ still has no palette cycling path
- existing `SwScrlMgz` behavior remains stable where unchanged
- existing S3K level-loading tests continue to pass

## Risks And Mitigations

### Risk: collapse behavior becomes renderer-specific glue

If MGZ collapse is implemented as ad hoc tilemap rendering tricks, the gameplay and the visible world can diverge.

Mitigation:

- make runtime level mutation authoritative
- use visual redraw logic as a consequence of world-state change, not a substitute for it

### Risk: boss AI depth blocks zone bring-up

Aiming for full end-boss move parity immediately could stall the larger zone bring-up.

Mitigation:

- prioritize orchestration parity first
- keep deep boss-state complexity inside the MGZ boss classes
- require enough boss behavior to complete the zone end-to-end

### Risk: event state leaks across level loads

MGZ has a large amount of mutable state and multiple one-shot flags.

Mitigation:

- reset all MGZ event state in `init()`
- add reset assertions in tests for reload / transition paths

### Risk: analysis drift

If MGZ implementation decisions stop being checked against the main-workspace analysis and the disassembly, later edits will drift.

Mitigation:

- keep `docs/architecture/research/s3k-zones/mgz-analysis.md` as the canonical analysis in the main workspace
- include label references in MGZ implementation comments and tests

## Recommended Delivery Order

Even though the scope is broad, the implementation should still proceed in this order:

1. MGZ design/spec and plan
2. MGZ event-manager scaffolding and MGZ1 intro-state tests
3. MGZ animated tiles with boss gating
4. MGZ1 background transition
5. MGZ2 resize / quake / BG-trigger orchestration
6. MGZ chunk mutation and collapse solids
7. MGZ drilling boss and end-boss object chain
8. MGZ headless integration and regression sweep

This order keeps the highest-confidence, disassembly-clear orchestration pieces ahead of the deeper boss work without shrinking the final zone scope.

## Success Criteria

The MGZ bring-up is complete when:

- MGZ event logic is owned by a dedicated `Sonic3kMGZEvents` implementation
- MGZ1 starts with the correct falling intro state
- MGZ animated tiles run and halt under boss gating as in the ROM
- MGZ1 transitions seamlessly into MGZ2 with the correct offset and reset behavior
- MGZ2 quake regions trigger the correct camera locks and boss encounters
- MGZ2 background-event logic drives the correct collision and BG-move states
- MGZ2 collapse mutates the world and provides temporary footing through collapse solids
- MGZ boss-chain objects exist and support end-to-end zone completion into CNZ
- automated tests cover the major orchestration and animation behaviors
- the implementation remains grounded in both `docs/architecture/research/s3k-zones/mgz-analysis.md` and `docs/skdisasm/sonic3k.asm`
