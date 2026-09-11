# Medium-Risk Reuse Consolidation

**Date:** 2026-07-29
**Base:** `develop` at `8c9b7378b3bf255bd979292c61a4b8584272c12c`

## Purpose

Continue the code-reuse audit with three bounded consolidations whose behavior
can be characterized independently:

1. make ROM detector construction and bootstrap-module mutation single-owned;
2. share only the exact CLI value and raw-integer parsing duplicated by the
   trace capture and benchmark tools; and
3. centralize test-only BK2 row-to-logical-input plumbing without introducing
   another cursor or changing any production playback cursor.

This tranche does not change ROM location policy, gameplay behavior, trace
authority, command-line syntax, or headless gameplay setup.

## Considered approaches

### Broad framework migration

Introduce one ROM resolver spanning discovery, detection, validation, and
lifecycle; one generic command parser for every tool; and one playback cursor
for production and tests.

This removes more surface area at once, but it collapses materially different
failure and ownership contracts. Tests may skip missing ROMs while tools must
fail; tool syntaxes differ; production recording cursors have commit/rollback
semantics that special-stage test feeders do not. This approach is rejected.

### Independent narrow kernels

Extract only behavior proven identical, retain compatibility facades, and pin
the boundaries with focused tests. This is the selected approach because each
change can be reviewed, reverted, and verified independently.

### Documentation and guards only

Leave implementation unchanged and add source guards against further
duplication. This contains growth but leaves two bootstrap mutation owners,
duplicate built-in detector construction, and fragile BK2 row plumbing. It is
useful only after the ownership is consolidated.

## Design

### 1. ROM detector catalog and bootstrap ownership

Add a small built-in detector catalog in `com.openggf.game`. It returns fresh
detector instances:

- `all()` returns the built-ins in their declared order;
- `forGame(GameId)` returns the detector for one game.

The catalog is an explicitly annotated composition root because it is the
single approved shared-layer boundary that assembles concrete game detectors.

Fresh instances preserve the existing extension assumption that detectors may
eventually hold state. `RomDetectionService` uses `all()` instead of directly
constructing three detectors. Test `RomCache` maps its test game enum to
`GameId` and uses `forGame`.

`GameModuleRegistry` remains the sole owner of bootstrap-module mutation and
fallback logging through a package-private operation that accepts an already
computed `Optional<GameModule>`. Both its public detection entry point and the
deprecated public `RomDetectionService.detectAndSetModule` compatibility entry
point detect through `RomDetectionService`, then delegate the result to that
single mutation operation. Detection, registration, priority ordering, stable
ordering for equal priorities, exception isolation, and immutable detector
snapshots remain owned by `RomDetectionService`.

The existing concrete Sonic detector classes and their declared, non-final
`canHandle` methods remain unchanged.

### 2. CLI parsing kernel

Add package-private `CliArguments` with two operations:

- require the value following a named option while preserving the caller's
  error wording;
- parse an integer without adding validation policy.

Only `TraceCaptureTool.Args` and `TraceBenchmarkTool.Args` adopt it. Their
parsers continue to own recognized flags, defaults, trace selection, validation
policy—including the benchmark-only minimum check—usage text, and process exit
behavior. No other command-line tool is migrated because its positional or
failure contract differs.

### 3. Test-only recorded-input rows

Add public, stateless `RecordedInputRows` under test support in
`com.openggf.tests.trace`; its consumers live in child packages, so its
constructor and consumed methods are public. An instance owns:

- a `Bk2Movie`;
- an absolute base offset;
- lookup of current and preceding physical BK2 rows;
- conversion to `LogicalInputSnapshot`; and
- scoped installation of an `InputHandler` logical override, cleared in a
  `finally` block.

Callers pass the local row to `snapshotAt(int)` or
`withLogicalOverride(int, InputHandler, Runnable)`. They retain all segment,
lag-row, and advancement state. The helper never seeks or advances, so existing
call-site conditions remain mechanically visible.

The preceding input is physical BK2 row `N - 1`, including across lag/skipped
rows; it is not the last gameplay row. Before physical row zero, the existing
neutral/null predecessor contract is preserved. Bounds are validated before an
override is installed.

`withLogicalOverride` accepts a `Runnable`, returns no value, propagates runtime
exceptions, and always clears the override in `finally`. It rejects an
`InputHandler` that already has a logical override; the current migration sites
all require an initially clear handler, and silently replacing caller-owned
state would be unsafe.

Migrate the exact repeated feeders in `S1SpecialStageReplayHarness`,
`S2SpecialStageReplayHarness`, `S3kSpecialStageReplayHarness`,
`TestS1GhzMazeRoundTripChain`, `TestS2EhzHalfpipeRoundTripChain`, and the two
named round-trip tests' local feeders. `AbstractRunChainTest` remains unchanged:
its boundary-await paths deliberately rely on `Bk2Movie.getFrame` clamping
beyond the recorded frame count, which differs from the strict row helper.
All row counters and advancement remain at their current call sites. S2
completed-pass replay remains explicit because its current/previous row
identities come from recorded binder data.

The helper accepts no `TraceData`, gameplay owner, or timing service. It cannot
hydrate gameplay from trace comparison data and remains test-only.

## Error handling and compatibility

- Null or closed ROM handling and Sonic 2 bootstrap fallback remain unchanged.
- A throwing custom detector is logged and skipped as today.
- Existing public detector/service APIs remain callable.
- CLI exception types and user-visible messages remain unchanged.
- BK2 exhaustion and invalid-row failures occur before mutating input override
  state.
- Scoped logical input is cleared after both successful and exceptional test
  steps.

## Testing

ROM tests characterize fresh catalog instances, declared order, priority and
equal-priority behavior, first-match behavior, unregistering, exception
isolation, immutable snapshots, bootstrap success/fallback, and the deprecated
forwarder.

CLI tests use red-green characterization of missing values, malformed integers,
minimum bounds, and retained capture/benchmark parser behavior.

Recorded-input-row tests cover offset mapping, physical predecessor
selection across lag rows, row-zero behavior, both players' direction/action
and Start state, bounds-before-installation, scoped cleanup, and cursor
non-ownership. They also pin rejection of a pre-existing override. Existing
special-stage harness and run-chain tests remain the integration safety net.

Each consolidation is committed separately and receives specification and code
quality review before the tranche-level review.

## Deferred work

- ROM path/provenance/fingerprint policy remains separate from detector
  orchestration because consumers have different skip/fail contracts.
- `RomManager` keeps its unknown/null string-to-Sonic-2 compatibility behavior.
- Production user-recording and trace playback cursors are not unified.
- Broad CLI parser unification is rejected.
- Headless fixture migration is deferred. Most direct runner use is
  intentional; the two AIZ intro diagnostics require characterization of team,
  camera, event, and intro state before any fixture substitution.

## Validation-discovered suite interaction

The first clean branch sweep introduced three full-suite-only failures absent
from the same-ROM base sweep:

- `TestPlayableSpriteRollSpeed.s3kTailsStopsRollingBelowMinimumRollSpeedThreshold`
- `TestLiveTraceComparatorObserver.existingFiveArgConstructorDelegatesWithNullObserver`
- `TestLiveTraceComparatorObserver.nullObserverIsHonoured`

All three passed when their two consumer classes ran alone. Retained
full-suite reports and fixed single-fork reproducers subsequently proved two
pre-existing runtime-state leaks: a prior headless fixture could leave a
registered sidekick in the active session sprite manager, and a prior level
diagnostic could leave a session collision system that zeroed the roll-speed
test's ground speed. The consolidation changed fork neighbours and exposed the
leaks; it did not create their runtime state.

The affected consumer classes participate in the existing full-reset contract
with `@FullReset` and `SingletonResetExtension`. To make that contract a
committed deterministic regression rather than relying on incidental Maven
suite order, test support also provides
`RuntimeStateContaminationExtension`. Before every affected consumer test, the
contaminator:

- resets to a known test environment and registers a CPU-controlled Tails in
  the active session sprite manager; and
- attaches a session-owned collision system whose ground-wall response sets
  the playable sprite's ground speed to zero.

Each consumer declares one ordered `@ExtendWith` chain:

```java
@ExtendWith({
        RuntimeStateContaminationExtension.class,
        SingletonResetExtension.class
})
```

JUnit invokes the contaminator first and the real reset callback second. The
existing comparator and roll-speed assertions then prove that the reset
replaced both contaminated session owners before the test body. Removing the
reset extension yields five failures in the nine affected tests; restoring
only that extension makes the same command green. The regression therefore
uses behavior, not source-text inspection, annotation reflection, retries, or
test-class ordering.

Production state ownership and runtime lookup behavior remain unchanged. The
ordered legacy reproducers remain supporting diagnosis evidence, while the
extension-chain test is the durable regression gate. Final validation must run
the affected classes, both ordered reproducers, the focused tranche, the
ArchUnit suites, and a clean same-ROM full sweep at the exact reviewed head.
That exact-head sweep is compared to retained clean exact-base reports, and
generated rewind reports are restored afterward.

The remediation also aligns all shared-layer frozen-baseline metadata with its
14-entry violation set and preserves frozen rule UUID
`e0b8ef04-86e9-4001-b35e-c5de3ef4d940`. The exception guide must identify
entries 1–9 as `MasterTitleRomPreview` dependencies and entries 10–14 as
`DefaultPowerUpSpawner` dependencies, rather than attributing all 14 to the
spawner. The validation record also enumerates the earlier base-only
SnaleBlaster failures.
