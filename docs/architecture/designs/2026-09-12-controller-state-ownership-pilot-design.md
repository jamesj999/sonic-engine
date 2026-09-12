# Controller state ownership pilot

## Status

Pilot implemented after the Java-normalisation first tranche became focused-green.
It moves comparison-only diagnostic construction and formatting only; gameplay
state remains in the controller.

## Decision

The first extraction may separate construction and formatting of
`SidekickCpuController.NormalStepDiagnostics` from CPU decisions. It must be a
package-private composed collaborator owned by `SidekickCpuController`, not a
general controller framework and not a new Mod API type.

`NormalStepDiagnostics` remains the existing `@ModApi` comparison record. Its
field order, sentinel values, `withCpuResult`, `withPostPhysics`, formatting,
and rewind storage in `SidekickCpuRewindExtra` are compatibility boundaries.
The controller continues to own all state transitions, input publication,
follow-history reads, sidekick writes, and the native-ending pose.

## Pilot boundary

`SidekickNormalStepDiagnosticRecorder` should own only:

| Responsibility | Inputs | Output |
| --- | --- | --- |
| pre-CPU observation | frame/state/branch plus sidekick status, object-control, motion and position | initial `NormalStepDiagnostics` |
| CPU-result observation | chosen branch, delay/history slot, recorded values, generated inputs, CPU-side motion and nudge | updated immutable record |
| post-physics observation | final status, object-control, motion and position | updated immutable record |
| rendering | latest immutable record and current pushing-grace count | current `formatLatestNormalStepDiagnostics` text |

The controller calls it at the same sites as `beginNormalStepDiagnostics`,
`finishNormalStepDiagnostics`, and `recordDiagnosticPostPhysics`. The recorder
does not receive `AbstractPlayableSprite`, `SpriteManager`, `ObjectManager`, a
random source, a queue, or a mutable gameplay callback. Passing scalar samples
at each boundary prevents it from becoming a second CPU owner.

`recordDiagnosticPostPhysics` has two separate responsibilities today. Only
the record update belongs to the pilot. It must still invoke
`applyPendingNativeEndingPoseAfterPhysics` immediately afterward in the
controller. That method changes control bits, locks, velocities and animation,
so moving it into a diagnostic class would make observation own gameplay.

## Sidekick ownership and rewind map

| State family | Current owner | Rewind treatment | Extraction status |
| --- | --- | --- | --- |
| CPU routine, counters, bounds, input latches, despawn and follow gates | `SidekickCpuController` | `SidekickCpuRewindExtra` | keep in controller |
| follow/history data and leader position records | `AbstractPlayableSprite` / `SpriteManager` | sprite snapshot, with controller counters referencing its own timing only | assess later; do not duplicate history |
| leader, sidekick, respawn strategy, carry trigger and transient carrier identity | live playable graph / runtime setup | `@RewindTransient`; rebind through existing graph | keep structural ownership |
| carry latches/cooldowns | `TailsCarryController` | nested carry snapshot carried by `SidekickCpuRewindExtra` | keep composed owner |
| latest normal-step diagnostic | controller today | `SidekickCpuRewindExtra.latestNormalStepDiagnostics` | pilot recorder may own construction, controller retains snapshot field/API |
| pre-object Ctrl_2 diagnostic latches | controller | scalar snapshot fields | keep in controller; they represent live global publication timing |
| pending native ending pose | controller | scalar snapshot | keep in controller; post-physics gameplay mutation |

The proposed recorder has no independent rewind adapter. Its current immutable
record is captured through the existing controller snapshot, preserving registry
order and avoiding a second snapshot key.

## Ordered sidekick update contract

1. The controller clears frame-local ownership and samples/update counters.
2. NORMAL logic records its pre-CPU observation before it chooses a follow
   branch or publishes generated Ctrl_2 input.
3. CPU logic reads the established leader history and current controller state,
   writes sidekick input/motion state, then records the CPU-result observation.
4. Object and playable physics run in their native ordering; they may change
   final sidekick state and Ctrl_2 globals.
5. `recordDiagnosticPostPhysics` records the final observation for the same
   controller frame, then the controller applies a pending native ending pose.
6. Rewind restores the controller scalar snapshot and nested carry snapshot;
   graph-owned references remain live and the next frame rebuilds observations.

The recorder must not cache a sidekick reference across step 6, and no record
may be used as gameplay input or trace hydration.

## AIZ ownership assessment

`Sonic3kAIZEvents` contains two cohesive but currently intertwined state groups.
They are candidates for later independent designs, not extraction targets in
this pilot.

| Group | Mutable state | Existing owning collaborators / external effects | Rewind boundary |
| --- | --- | --- | --- |
| fire sequence | `eventsFg5`, fire phase, fixed-point BG copy, rise speed, wave phase, phase counters, mutation/terrain/art flags, Kos queue ordinals/handles | `S3kSeamlessMutationExecutor`, palette and scroll owners, S3K Kos queues, level transition | existing AIZ event snapshot/accessor sidecar plus hardware-facade rebind |
| battleship sequence | `eventsFg4`, boss/spawn guards, auto-scroll and camera-freeze latches, wrap/repeat/smooth-scroll values, screen-shake values, terrain/art ordinals/handles | camera, parallax/scroll, object spawning, water shake and S3K Kos queues | existing AIZ event snapshot/accessor sidecar plus hardware-facade rebind |

Neither group is a simple value object: both order camera writes, object
allocation, palette/mutation requests and ROM-backed work. A future extraction
must preserve the current pre-physics battleship pass, ScreenEvents/shake
ordering, queue claim/rebind boundary, and post-restore reconciliation before
moving fields. Fire and battleship should remain separate owners because their
transitions and resource lifecycles differ.

## Acceptance evidence for a later implementation

- A recorder unit test proves pre/CPU/post samples and formatting remain byte-for-byte
  equivalent for representative NORMAL branches.
- Existing sidekick rewind tests prove `latestNormalStepDiagnostics` restores
  without a new rewind key or changed restore order.
- S3K AIZ → HCZ headless/bootstrap tests and affected trace fixtures remain green.
- A field-by-field review confirms the recorder contains no gameplay mutator,
  object reference, RNG access, queue handle, or trace-comparison input.
