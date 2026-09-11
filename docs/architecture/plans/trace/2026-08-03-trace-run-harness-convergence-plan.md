# Trace-run harness convergence implementation plan

Date: 2026-08-03

Design: `docs/architecture/designs/trace/2026-08-03-trace-run-harness-convergence-design.md`

## 1. Lock shared row ownership with failing tests

Add focused JUnit 5 tests for a new `TraceRunSpecialStageRowDriver`. Cover
admission, duplicate admission, publication ordering, cursor advancement only
after publication, advertised comparison, delivery serial, generation and
row-frame validation, early close, and exact completion. Lock the special-local
destination contract to `rowsConsumed == 0`; test that a one-row receipt is
rejected because no row-zero publication evidence exists rather than silently
initializing the comparison cursor to one. Run the tests before implementation
and record the expected compile/test failure.

## 2. Reproduce launcher ordering before production changes

Before changing `TraceSessionLauncher` or the headless chain adapter, add
launcher tests for the reported untouched-comparator closure path. Record
whether input was consumed or closure preceded first admission. Add a
call-order regression proving the admitted special-stage row must publish
before any pending action can close, verify, or replace its segment owner. Run
these tests and record the expected failure against the current launcher.

## 3. Implement and adopt the shared special-stage row driver

Implement the value-only driver in `trace.replay.runs`. Refactor
`TraceSessionLauncher` so input, lag policy, preserved VBlank, hardware timing,
dynamic-art publication, exhaustion, and closure all use its cursor and pending
admission. Refactor `AbstractRunChainTest` to replace its local row array and
independent comparison accumulator with the same driver. Both adapters pass
pre- and post-production snapshots through the driver's shared atomic
publication validator and forward its optional comparison to their report
sinks.

In `TraceSessionLauncher.afterProductionIteration`, publish the admitted shared
driver row before `drainPendingRunBoundaryActions()` or any other action that
can close, verify, or replace the current owner. Prove an admitted row cannot
be closed before its post-production publication and that a full advertised
segment closes with its comparison count equal to its input row count. Reject
`SPECIAL_LOCAL` destination receipts with `rowsConsumed != 0`.

Run:

`mvn -Dtest=com.openggf.trace.replay.runs.TestTraceRunSpecialStageRowDriver,com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.TestSpecialStageVisualTraceSession,com.openggf.TestSpecialStageHardwareTimingLifecycle test`

## 4. Remove duplicated fixture behavior

Add contract tests showing the terminal playable prefix and fixed-slot suffix
dispatch through `GameplayModeContext.getSpriteManager()`. Give
`TraceReplayFixture` shared default implementations and remove equivalent live
and headless loops.

Run the fixture/comparator tests plus the launcher tests.

## 5. Add catalog and emerald-route parity coverage

Add a guard that independently enumerates eligible on-disk
`<game>/runs/*/run_manifest.json` files, requires exactly one master-title
catalog entry for each, and loads/plans each through `TraceRunReplayWalker`.
Add a dedicated `TestS1CompleteEmeraldRunChain` lane. Extract a shared target
segment/committed-row stop condition from the chain body, leaving existing
lanes end-of-run, and set the emerald lane's explicit executable frontier to
segment 1 with committed represented cursor 1. Do not
reuse the short-maze test's route-specific assertions or use an expected
failure.

Run the new guard and emerald lane first. Record frame 3596 as the expected red
frontier before changing S1 runtime behavior.

## 6. Correct the S1 deleted-player animation owner

Instrument the focused ring-flash/signpost test to identify the exact native
state transition that clears `$1C`. Add a failing object-level regression which
reproduces the production ordering. Correct the smallest S1 owner so the
retained deleted-SST representation preserves the ROM's null animation without
consulting trace identity, frame, zone, route, or visual mode. Represent native
slot presence explicitly, capture it for rewind, restore it on player reset,
and prove generic hidden/object-control combinations do not suppress another
game's live player-slot dispatch.

Run the object regression, the emerald prefix lane, standalone GHZ1/GHZ3 trace
tests, and S1 PLC producer coverage.

## 7. Verify harness parity and record the frontier

With JDK 21 and discovered/hash-verified ROMs, run:

`mvn -Dsonic1.rom.path="$S1_ROM" -Dtest='com.openggf.tests.trace.s1.*TraceReplay,com.openggf.tests.trace.runs.TestS1*Chain' test`

`mvn -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" -Dtest='com.openggf.tests.trace.runs.Test*Chain' test`

`mvn -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" test`

Update `docs/status/trace-frontier-log.md` with the exact commands, covered
emerald segment/row, pass/fail/error counts, and first independent remaining
frontier. Update `CHANGELOG.md` and the integration `README.md` entry.

## 8. Review, integrate, and clean up

Request code review and resolve every valid issue. Fetch and fast-forward the
unchanged main-workspace branch without overwriting user changes, record its
full-suite baseline, run the same suite and focused tests in the worktree,
merge into the main-workspace branch, compare the merged suite with baseline,
push the main branch, and remove the clean merged worktree and local branch.
