# Visual Trace and Headless Replay Parity Design

## Status

Implemented; integration-baseline comparison and final review are recorded in
the matching validation report. This design makes the
trace session launched from the master title screen execute the same recorded
row contract as the headless replay harness, with particular emphasis on PLC,
Kosinski queue, and player dynamic-art timing.

## Requirements

### Goals

- A visual trace session and a headless replay that start from the same trace
  and BK2 rows apply the same logical input, execution phase, VBlank closure,
  and trace expectation to each production iteration.
- S1/S2 PLC queue diagnostics and S3K Kosinski module/direct-queue readiness
  continue to be observed after their native service boundaries.
- Dynamic-art diagnostics are compared only after
  `PlcFrameLifecycleCoordinator.finish()` publishes the iteration.
- Visual rewind and ordinary forward playback use the same suppressed-row
  closure as headless replay.
- Input-alignment and native-prelude bootstrap mismatches are visible in the
  live HUD/reporting path instead of being silently deferred into physics
  divergences.
- Existing ordinary BK2 playback without an attached trace observer retains
  its current input and timing behaviour.

### Non-goals

- Do not broaden `HardwareTimingReplayPort` authority. It may still only delay
  readiness of matching, prepared, production-submitted ROM work.
- Do not turn S1/S2 PLC or player-DPLC comparison data into replay authority.
- Do not hydrate physics, queue, object, art, or event state from trace values.
- Do not change fixture schemas, regenerate traces, or tune gameplay to a
  particular route, zone, frame number, or trace name.
- Do not merge the headless runner and `GameLoop` into one runtime loop; they
  remain separate front ends over shared row-policy and row-closure seams.

### Acceptance criteria

- S3K native-prefix visual replay validates the current trace/BK2 row while
  driving gameplay from the previous BK2 row, including correct action-edge
  and Start-edge history.
- The live fixture can peek at BK2 row `-1`, so held jump at the prelude
  boundary is primed exactly as in headless replay.
- VBlank-starved represented iterations are marked exactly once before the
  PLC lifecycle finishes. Each stored physical row owns at most one replay
  closure, even when its diagnostic counter delta is greater than one.
- A suppressed S3K held-counter title-card row executes the
  `LEVEL_TITLE_CARD` hardware-timed object scan and its
  `VINT_SERVICE -> POST_OBJECTS -> PRE_MAIN_LOOP` boundaries; other suppressed
  rows use `LAG` service. Pending in-level title cards, level-event VBlank-only
  state, retained fixed objects, control lock, and object VBlank counters
  advance in the same order as headless replay.
- Queue diagnostics remain sampled after service/preparation. Nonterminal
  dynamic-art diagnostics are sampled after production publication, and the
  existing terminal forwarding iteration remains the terminal owner.
- Live gameplay comparison uses the same S1/S2 and S3K split-row expectation
  normalization as headless replay and validates BK2 input alignment.
- Native-prelude bootstrap comparison is performed from a shared read-only
  engine snapshot capture.
- Bootstrap, launch, or in-iteration replay failure tears down installed
  timing authority without demanding successful consumption of an incomplete
  recorded schedule, restores user configuration, and returns to the master
  title without leaving a live observer, HUD, rewind owner, or timing hook.
- Focused parity tests and the full Java test suite introduce no regression on
  JDK 21.

## Exploration synthesis

Two independent source audits found the same high-confidence gaps.

1. `PlaybackDebugManager` always applies its current BK2 cursor, while the
   headless S3K path deliberately validates the current row and applies the
   previous row for traces with a recorded pre-level prefix.
2. `TraceReplaySessionBootstrap` peeks at recording row `-1` to prime a held
   jump, but `TraceSessionLauncher.LiveFixture` inherits the fixture's `-1`
   sentinel implementation.
3. Headless replay marks represented iterations whose gameplay and VBlank
   counters both remain held. Forward visual replay does not, although the PLC
   lifecycle consumes that marker when deciding which dynamic-art row to
   publish.
4. `RecordingFrameDriver.skipFrameFromRecording()` runs the S3K held-counter
   title-card object scan, pending title-card dispatch, level-event
   VBlank-only state, and object VBlank counter. `GameLoop` and the visual
   rewind stepper currently run only `LAG` service and the object counter.
5. `LiveTraceComparator.afterFrameAdvanced()` executes inside the production
   iteration. PLC/Kos queue snapshots are already ready at that point, but
   player dynamic-art publication happens later, when the outer
   `PlcFrameLifecycleCoordinator.finish()` returns. Live comparison therefore
   observes the preceding unpublished snapshot.
6. Headless comparison normalizes diagnostics from a following split
   VBlank-only row and validates trace/BK2 input alignment. Live comparison
   uses the raw current row and records input only for display.
7. Headless replay performs native-prelude frame-zero comparison. Live replay
   does not, and ordinary/run bootstrap failure paths do not explicitly abort
   an installed timing port.

The recorded hardware-timing admission, production-submission identity,
boundary observer, schedule handoff, and final verification paths are already
correct. S1/S2 physical PLC queue snapshots are also captured at the correct
post-service point. The fix therefore belongs in replay row admission,
suppressed-row closure, and post-production comparison—not in queue owners or
the timing port.

## Architecture decision

### 1. Admit one immutable represented-row policy before input synchronization

Introduce an immutable replay row policy containing the represented trace
index/frame, execution phase, validation BK2 index, applied BK2 index, applied
row predecessor, whether a VBlank closure is represented, and whether the
first sidekick animation is held. The policy is derived from existing
`TraceReplayBootstrap` predicates; it contains no gameplay or expected queue
state.

The row policy has two stages. A pure latch runs near the top of
`GameLoop.stepInternalBody()`, before playback Start is inspected and before
`GameLoop.syncPlaybackInputBridge()`. It selects movie rows and derives edges
but mutates no gameplay, PLC, or timing state. `PlaybackDebugManager` invokes
the observer's new prepare hook for its current BK2 row, then applies the
policy-selected input. The existing represented-row activation callback in
`LevelIterationAdmissionController` subsequently commits once-only lifecycle
side effects and latches hardware timing only when the row is actually
admitted. A user-paused or structural-gap iteration may retain the pure policy
for retry but cannot mark the PLC lifecycle or timing port.

Playback-supplied Start is passed to `LevelFrameStep.admit` as ROM input. It
must not also toggle the outer, user-facing audio/HUD pause; that path remains
owned by live keyboard/gamepad input. This removes the current one-row-late
playback Start edge. After represented activation, the cached skip decision is
queried before generic timers or overlay work, and later consumers reuse it.
Querying an offset or skip decision before pure preparation, or for a
different cursor, fails closed.

Extend `PlaybackFrameObserver` with default pure-prepare and applied-input-
offset methods, both preserving the current zero-offset behaviour.
The live policy selects `-1` only for gameplay-running phases when
`TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace)`
selects the existing cross-harness prefix policy. `ADVANCE_ONLY`,
`VBLANK_ONLY`, and `PLAYABLE_ANIMATION_ONLY` consume the current row, exactly
like `TraceReplayFrameClosureDriver.driveS3k`. `PlaybackDebugManager`
continues to advance and report its current cursor row, but derives held input
and action/Start edges from the policy's applied row and predecessor.

The observer never supplies input bits. It selects only a relative BK2 index
inside the already loaded movie. Out-of-range selection fails closed rather
than falling back to current input. With no observer, the default zero offset
preserves normal playback.

Named runs install `TraceRunReplayWalker.BoundaryProbe` as the playback
observer. It forwards pure preparation and the applied offset to its active
delegate, just as it forwards skip/VBlank callbacks; detached structural gaps
retain offset zero. Its before-frame boundary hook remains ordered before
represented-row activation.

Expose a read-only `peekInputMaskAt(offset)` on `PlaybackDebugManager`, using
the same replay-validation mask conversion as the headless driver.
`LiveFixture.peekRecordingInputAt()` delegates to it. Cursor advance remains
owned by the playback manager.

The visual rewind stepper does not go through the playback observer. It must
therefore consume the same immutable row-policy factory and apply the
policy-selected BK2 row plus predecessor when publishing held/action/Start
input. It may not independently rederive “current versus previous” input.

### 2. Share the suppressed-row production closure

Extract the production-owned, trace-value-free suppressed-row closure now
embedded in `RecordingFrameDriver` into a main-source helper. Its inputs are
the active `LevelFrameContext`, `PlcLifecycleFrame`, level manager, and narrow
callbacks for starting an in-level title card and applying its player-control
lock. It:

1. runs a `LEVEL_TITLE_CARD` hardware-timed object scan when the provider
   advances on the held level counter, otherwise services `LAG` VBlank work;
2. starts a pending held-counter in-level title card;
3. advances level-event VBlank-only state; and
4. advances `ObjectManager.vblaCounter` exactly once for each represented
   VBlank.

`RecordingFrameDriver`, forward visual playback, and visual rewind call this
helper. An `ADVANCE_ONLY` row with no represented VBlank does not call it. Any
other suppressed stored physical row calls it exactly once. A positive
`vblankCounter` delta answers whether the row represents a VBlank closure; it
is not a request to replay multiple hardware boundary cycles. This matches
the headless driver's one-row/one-closure contract and prevents an invalid
`PRE_MAIN_LOOP -> VINT_SERVICE` wrap under one hardware-timing raw-row latch.
The helper consumes no trace object, frame number, expected queue descriptor,
or hardware completion edge.

In `GameLoop`, a held-counter trace provider update is deferred from the
generic overlay update to this helper so it occurs once inside the
hardware-timed object scan. Ordinary non-trace play retains the generic
overlay update. A prepared trace policy that claims a gameplay-running phase
while such a provider still requires held-counter dispatch fails rather than
silently dropping or double-running the update.

The early skip decision also gates `TimerManager.update()`. Suppressed visual
rows therefore do not advance generic gameplay timers that
`RecordingFrameDriver.skipFrameFromRecording()` leaves held. Full gameplay
rows retain their current timer order.

### 3. Apply lifecycle side effects once per admitted row

During represented-row activation, `LiveTraceComparator` calls
`markVblankStarvedIterationForReplay(previous, current)` before any production
work. Repeated activation for the same cursor is idempotent; pure preparation
for a new cursor clears the prior policy. Once-only side effects, including
holding the first sidekick animation for
`FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD`, are executed from the
admitted policy exactly once rather than from repeated observer queries.

The hardware timing observer continues to latch `raw_frame` before production
admission. No physics or auxiliary comparison value reaches the timing
service.

### 4. Keep queue comparison in-frame; defer dynamic-art comparison

`LiveTraceComparator.afterFrameAdvanced()` continues to compare physics and
physical PLC/Kos queue state, because those snapshots are ready after the
frame's service/preparation boundaries. It records a pending nonterminal
dynamic-art expectation instead of pulling the diagnostics snapshot.

For `PLAYABLE_ANIMATION_ONLY`, `afterFrameAdvanced()` records a post-row
playable-prefix action instead of executing it. In the launcher post-finish
hook, that action runs after the row's lifecycle has published and before any
comparison snapshot or terminal/segment cleanup. Its resulting DPLC
submission therefore belongs to the next production closure (or terminal
forwarding), matching headless ordering.

`TraceSessionLauncher.beforeProductionIteration()` captures the comparator
owning that iteration and the immutable diagnostics baseline. After the outer
PLC lifecycle returns, `afterProductionIteration()` first runs any deferred
playable-prefix action, then asks that same comparator to publish its pending
dynamic-art comparison from one immutable snapshot. Capturing the iteration
owner avoids a run-segment rebind sending the previous segment's pending row
to the next comparator.

Rows whose phase claimed a production lifecycle must receive a newer delivery
serial with the expected segment generation/row; missing or stale publication
fails loudly. `ADVANCE_ONLY` intentionally claims no phase and publishes
nothing, so it instead requires the delivery serial/generation/payload to
remain unchanged and compares that unchanged latest snapshot, as headless
does. The immutable row policy supplies this publishing-versus-input-only
classification; expected dynamic-art contents never decide it.

The last advertised row keeps the existing terminal path: run the native
trailing VBlank/object iteration, close the comparison segment, then compare
the forwarded terminal publication. Special-stage named-run publication
continues through its existing post-iteration path.

### 5. Share comparison expectation and bootstrap diagnostics

For a gameplay-comparable row, live replay chooses
`frameForGameplayComparison` for S1/S2 and
`s3kFrameForGameplayComparison` for S3K before calling `TraceBinder`. Queue and
dynamic-art lookups remain keyed to the represented raw/current row, not the
normalized diagnostics view.

The live comparator validates the current represented trace row against the
current BK2 validation mask. A mismatch becomes an explicit live error field,
ring-buffer entry, error-count increment, and first-error pause trigger; it
does not alter input or gameplay.

Move the read-only engine frame-zero snapshot capture into a main-source
utility shared by headless and live bootstrap. `TraceReplayDriver` feeds the
result to `LiveTraceComparator` before installing the frame observer.
`BootstrapDivergence.ERROR` contributes one live error and the first-error
pause; `WARNING` contributes one warning. Both become HUD mismatch-ring
entries with frame zero and retain their bootstrap field name/detail. They
remain separately available as bootstrap diagnostics in the final report and
never mutate replay state.

### 6. Abort incomplete timing authority and contain runtime failure

Add an idempotent abort operation to the fixture contract that detaches the
timing observer, replay-port rewind registration, and gameplay-context close
hook without calling `verifyRunComplete()` or
`RecordedCompletionAuthority.endRecordedAdmission()`. Strict end-recorded-
admission correctly rejects pending production submissions and is therefore
not an abort API.

Every abort is immediately followed by destruction of the same
`GameplayModeContext`; `GameplayModeContext.tearDownManagers()` resets the
hardware timing service and its admission policies. The abort handles partial
installation states (no port, port only, observer/rewind/hook installed), is
idempotent, and never replaces the primary failure. Successful completion
continues to call strict verify-and-close.

`runProductionIterationIfActive()` captures failures from the production body
and post-finish hook, preserving later failures as suppressed. Because the PLC
coordinator finishes in its own `finally`, cleanup begins only after that
production lifecycle has closed. The launcher then aborts timing, detaches the
playback observer/HUD/rewind owners, ends playback, restores configuration,
destroys the failed gameplay context, and returns to the master title. A
runtime replay validation failure is logged and contained rather than leaving
the engine in a half-active trace session. Fatal JVM `Error`s receive the same
best-effort cleanup and are then rethrown.

User-requested early exit is also an incomplete run. It aborts and immediately
destroys the gameplay context, then rebuilds the master title without using
the destroyed gameplay-owned `FadeManager`; skipping that cosmetic fade is
preferable to running fade iterations under detached recorded authority. A
future title/bootstrap-owned presentation fade may be added independently.
Bootstrap failure, special-stage launch failure, run-transition failure, Esc,
and runtime comparison/timing failure all converge on this immediate
detach-then-destroy cleanup. Ordinary trace completion alone uses strict
verification and may retain the existing gameplay-owned fade after authority
has closed successfully.

## Ownership and data flow

```text
trace row + current BK2 cursor
        |
        v
pure represented-row latch
  immutable phase / validation row / applied row / VBlank closure policy
        |
        v
PlaybackDebugManager applies movie row (cursor + offset)
        |
        v
represented-row activation
  timing raw-row latch / no-VBlank marker / once-only held-sidekick effect
        |
        v
GameLoop production iteration
  normal LevelFrameStep OR shared suppressed-row closure
  -> PLC/Kos service and preparation
  -> physical queue snapshot is observable
  -> observer advances represented cursor and compares gameplay/queues
        |
        v
PlcFrameLifecycleCoordinator.finish()
  -> publishes player dynamic-art diagnostics
        |
        v
TraceSessionLauncher.afterProductionIteration()
  -> owning comparator compares pending dynamic-art row
  -> terminal/session actions may now close
```

Production owners decide what work exists. Recorded hardware timing may decide
only when matching S3K work becomes ready. Physics, queue, and dynamic-art data
remain read-only expected values below the production boundary.

## Failure handling and rollback

- Missing applied BK2 rows, input misalignment, stale/missing dynamic-art
  publication, schedule identity mismatch, or an invalid held-row lifecycle
  enters the explicit runtime-failure cleanup state after the outer lifecycle
  finishes.
- Aborting a partial launch detaches the strict close hook; immediate gameplay
  context destruction resets incomplete recorded admission without pretending
  it completed. Normal completion remains strict.
- The change is internal and has no fixture migration. Rollback is the feature
  commit; no persisted state or generated trace needs conversion.

## Test strategy

- Unit-test relative input selection, peeking, action/Start edges, bounds, and
  no-observer compatibility in `PlaybackDebugManager`.
- Unit-test `BoundaryProbe` forwarding for prepare/offset and detached gaps,
  and prove playback Start reaches ROM admission without toggling user pause.
- Unit-test live phase caching, no-VBlank marking, split-row normalization,
  input-alignment reporting, post-finish dynamic-art publication, and terminal
  forwarding in `LiveTraceComparator`.
- Add a production helper test proving held title-card scan boundary order,
  fallback `LAG` service, pending dispatch, event VBlank state, retained fixed
  slots, control lock, and VBlank counter advancement.
- Add row-policy parity tests proving forward and rewind use the same
  represented/validation/applied BK2 rows for every phase and that one stored
  row never repeats a hardware boundary cycle.
- Add launcher tests proving the same iteration comparator receives the
  post-finish publication across segment rebind, playable-animation-only work
  is submitted after publication, ADVANCE_ONLY requires no publication, and
  all partial-launch/transition/Esc/runtime-failure paths abort timing
  authority.
- Add orchestration-level transcript tests at the GameLoop/launcher seam: an
  S1/S2 ordinary-plus-lag sequence must show PLC service and preparation,
  physical queue comparison before outer finish, and player-DPLC comparison
  after publication; an S3K schema-2 held title-card row must show direct and
  module readiness only at their matching `POST_OBJECTS`/`PRE_MAIN_LOOP`
  boundaries from production-submitted work.
- Keep `TestHardwareTimingAuthorityGuard`,
  `TestS1S2PlcComparisonOnlyGuard`, queue comparison tests, PLC lifecycle
  parity tests, visual-run tests, and rewind tests green.
- Run the complete Maven suite on JDK 21, first on the updated integration
  baseline and then on the implementation and merged branch, comparing exact
  failures.

## Risks

- Applying a prior BK2 row can accidentally derive edges from the validation
  row. Tests must assert held masks and leading edges independently.
- Segment rebind can orphan a pending dynamic-art row. The launcher therefore
  retains the iteration's comparator until its post-finish hook drains.
- Title-card code already runs near the top of `GameLoop.updateLevelMode`.
  The shared closure must ensure a held-row provider update occurs exactly
  once, not both in the generic overlay update and the hardware-timed scan.
- A comparison error callback can pause playback during the iteration. Cleanup
  remains deferred until post-finish publication so pausing cannot truncate
  the production lifecycle.
