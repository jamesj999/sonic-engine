# Suite Failure Remediation Design

## Requirements

The post-backport test suite must be brought back to a trustworthy state on
JDK 21. The work must:

- distinguish reproducible product defects from stale reports, test-fixture
  defects, and fork-order contamination;
- exclude trace replay parity from remediation and delivery gating until PLC,
  DPLC, and decompression queue work makes trace validation deterministic;
- repair shared production contracts before individual downstream assertions;
- preserve the comparison-only hardware-timing trace contract;
- avoid trace-, frame-, game-, or zone-specific exceptions in shared runtime
  code;
- update architecture and rewind inventories when production structure changes;
- finish with focused tests and a complete non-trace suite run allowed to reach
  its natural conclusion.

The reported `101` failures and `35` errors are an initial observation, not an
assumption that 136 independent defects exist.

## Exploration Synthesis

Focused JDK 21 runs identify one deterministic production-contract cluster and
two deterministic test-contract/harness clusters:

1. S3K initial object setup authority is absent after a production level load.
   Fourteen lifecycle tests reproduce this directly, while twenty CNZ trace
   assertions fail at the same setup-token precondition before comparing
   gameplay.
2. The canonical full-frame path services `POST_OBJECTS` after ScreenEvents,
   matching S3K `LevelLoop` ordering, while a boundary test expects it
   immediately after object execution. The focused evidence therefore shows a
   stale test expectation, not a missing production call. The alternate
   object-scan path has its own reduced sequence and services the boundary
   immediately after its owned scan.
3. Results-screen construction now requires `ObjectServices.hardwareTiming()`.
   Lightweight object-test services intentionally lack a gameplay timing
   context, producing construction errors before boss/result behavior runs.
   The focused evidence does not show a production-session failure.

The deterministic guard inventory is: the `AGENTS.md`/`CLAUDE.md` mirror,
three class-size budgets, three zone-event direct-`GameServices` uses, one
playable-runtime direct-`GameServices` use, a `PlayerMovementRules` record
budget overage, object-physics violations in the LBZ Robotnik ship and cup
elevator, and rewind coverage/inventory/recreate gaps. These are independently
actionable but must be resolved without weakening thresholds or baseline
inventories to conceal defects.

Trace reports are retained as diagnostic context only. Their failures are not
classified or remediated by this effort because PLC, DPLC, and decompression
queue work must land before trace replay can be a deterministic validation
surface.

The full Maven suite is slow rather than hung. Four reused Surefire forks can
leave the console quiet while reports continue to be written. Completion
judgments must use process state and report timestamps, not console silence.

## Architecture Decision

Remediation will follow a root-cause ladder:

1. Restore production lifecycle and timing boundaries.
2. Re-run affected clusters in isolation and in deliberately reused forks.
3. Fix proven reset/ownership leaks before investigating remaining gameplay
   assertions.
4. Address independent architecture and rewind guards.
5. Rebuild the non-trace inventory from a clean suite run and only then pursue
   remaining behavior failures.

The working lifecycle hypothesis is that initial setup authority is lost
between the level-load profile request and canonical admission. It remains owned by
`InitialProcessSpritesLifecycleCoordinator` and published from the level-load
profile. Consumption remains in the canonical frame-admission path. The fix
must restore that ownership chain rather than manufacturing setup tokens in
trace or test code.

Hardware timing remains session-owned. `LevelFrameStep` is the single owner of
canonical boundary order. Results art submits real timed queue work during
construction, so construction and rewind harnesses must inject a
timing-capable `ObjectServices`. That service must allocate the same handle
ordinals and readiness transitions as production. Rewind restoration must
rebind captured handles to restored service state without resubmitting work.
Unit services must not admit recorded completion edges or derive readiness from
trace data.
The production queue lifecycle will not be deferred merely to accommodate an
incomplete test service, and a nullable or no-op timing dependency is not
acceptable.

## Feature Design

### Lifecycle restoration

Trace the load path from `Sonic3kLevelInitProfile` through `LevelManager` to the
coordinator. Add or strengthen tests at the publication boundary and retain the
existing consumption tests. The first admitted S3K iteration performs the
one-shot initial object pass and returns `SETUP_ONLY`; the same input row is
retried for the ordinary gameplay frame. Pause and rewind must preserve pending
authority.

### Canonical hardware boundaries

The full frame executes `VINT_SERVICE`, `PRE_MAIN_LOOP`, the profile-specific
player/object dispatch, camera and ScreenEvents, then `POST_OBJECTS` exactly
once.
For inline-solid profiles, physics precedes object execution; for legacy
profiles, object execution precedes physics. `POST_OBJECTS` follows the object
portion in either profile and does not redefine that ROM-shaped ordering.
Completion retired at this boundary is first visible to object/event consumers
on their next dispatch. The service call occurs exactly once and the observer
sees the boundary only after the service has made eligible work ready. Nested
helper paths must not duplicate it. Setup-only and paused iterations retain
their documented reduced boundary sets.

### Results art dependency

Results-screen tests must construct objects without a hidden global gameplay
session by supplying an explicit timing-capable service through
`ObjectServices`. Tests will cover handle allocation, construction, queue
progression, readiness, claim, and rewind restoration without resubmission.

The production rewind path also has a distinct reconstruction gap: its
restore-only results shell deliberately skips `loadArt()`, leaving the derived
ROM mapping table null after generic field restoration. After captured
constructor state (including `act`) is restored, `restoreRewindState` will
rehydrate only that immutable mapping data through the injected ROM reader,
using the same tile-index and nonzero-act name adjustment as initial loading.
This restore-only step must not queue, submit, service, or claim art. Timing
handles continue to rebind to the restored service snapshot without
resubmission.

Post-claim snapshots require a second restore branch. Claimed hardware jobs
retain their prepared payload in the session timing ledger, so the results
object will preserve its three stable ordinals after claim and read cloned
payloads from claimed jobs through a read-only timing API. A ROM-art assembly
helper will feed those payloads through the same placement logic as the
original claim, after which the object rebuilds its transient HUD patterns,
sprite sheet, and renderer. This reconstruction must not submit, service,
release, or claim work, and must leave job count and next ordinal unchanged.

### Isolation and fixture correctness

Reused-fork non-trace tests will include Snale Blaster with its suspected
registry predecessor. This separates singleton-reset defects from stale
compiled output. Fixes belong at the state owner or reset extension.

### Guard debt

`AGENTS.md` and `CLAUDE.md` will remain mirrored if agent guidance changes are
needed. Oversized production classes will be reduced by extracting cohesive
owners, not by raising limits. Direct `GameServices` access and raw position
writes will migrate to the established injected/provider APIs. Rewind coverage
will capture genuine state and add recreate/probe support where required.

Bonus-stage bootstrap ownership belongs to `BonusStageProvider`. It exposes an
optional semantic `BootstrapObject` containing the ROM spawn description,
concrete object type used for duplicate detection, and factory. The S3K
coordinator supplies the Pachinko energy-trap bootstrap for
`GLOWING_SPHERE`; the no-op and other bonus-stage providers return no
bootstrap. Shared `GameLoop` only queries and consumes this value and contains
no S3K object ID or concrete-object import.

## Acceptance Criteria

- Deterministic lifecycle, boundary, and results-art clusters pass in focused
  runs.
- Pending and ready-unclaimed results objects survive the production rewind
  capture/restore path with mappings rehydrated and no replacement timing
  submissions.
- Post-claim results snapshots rebuild transient render ownership from the
  claimed timing-ledger payloads without changing ledger identity or lifecycle.
- Setup-only admission performs exactly one setup pass and advances neither the
  consumed input row nor ordinary gameplay counters.
- The same clusters pass with a reused single fork.
- Architecture and rewind guards pass without blanket threshold increases.
- S3K glowing-sphere startup creates its provider-declared bootstrap exactly
  once, while other bonus types and games declare no bootstrap; shared startup
  code remains game-agnostic.
- A complete JDK 21 `mvn clean test` run with all discovered ROM properties is
  the authoritative inventory; pre-clean reports are retained separately when
  needed for comparison.
- Every remaining non-trace failure in that clean run is reproduced against
  the updated integration baseline and either fixed or dispositioned with
  concrete pre-existing evidence. Any unexplained non-trace failure blocks
  delivery.
- Trace replay results are reported separately and do not gate delivery.
- No Surefire XML from a trace package or `*Trace*` test is produced by an
  authoritative non-trace run.

## Non-goals

- Trace replay parity and frontier advancement are out of scope.
- CNZ trace metadata/sidecar fixture correction is deferred with the rest of
  the trace surface.
- Broad architecture migration unrelated to an observed failure is excluded.
- Timing readiness will not be hydrated from trace comparison data.

## Risks and Mitigations

- A lifecycle fix can double-run initial objects. Assert publication and
  consumption counts independently and test input-row retry behavior.
- Changing `POST_OBJECTS` expectations can conceal a production ordering
  regression. Verify the ROM-backed boundary order with non-trace unit tests.
- Shared-state defects can disappear in isolated tests. Include reused-fork
  sequences and randomized or reordered reproductions where practical.
- Full-suite silence can be mistaken for a hang. Monitor Surefire report
  timestamps and obtain a thread dump only when both reports and processes stop
  progressing.
