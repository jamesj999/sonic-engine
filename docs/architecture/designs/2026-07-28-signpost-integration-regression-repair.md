# Signpost Integration Regression Repair

## Context

`develop` at `2c10ca812` cannot compile tests because
`TestS3kSignpostInstance` references production helpers introduced by the
ROM-backed signpost fixes `ed113599f` and `07b866ced`, while the current
`S3kSignpostInstance` no longer contains those helpers or their associated
runtime behavior. The tests survived a later integration but the corresponding
source changes did not.

This blocks every fresh trace-replay discovery sweep before Surefire starts.

## Goal

Restore the already-reviewed signpost behavior from the two ancestor commits so
the current test contract compiles and the trace fleet can rediscover against
the pushed `develop` baseline.

## Design

Restore only the missing production pieces whose tests are already present:

1. Reintroduce `ResultsChildTimingAdjustment` and
   `resultsChildTimingAdjustment(...)`, and use the selected adjustment when
   computing the results child's create-gate dispatch count.
2. Restore the ROM falling-dispatch order: sparkle and bump handling occur
   before gravity and movement; a nonzero bump cooldown decrements and returns
   without checking collision on the same dispatch.
3. Restore `romVelocityAfterGravity(...)` and
   `romBumpCheckAvailableAfterCooldownEntry(...)` as package-private helpers
   used by production and pinned by the existing tests.
4. Restore the ROM range-word interpretation by changing the positive bump
   boundaries from `$40/$30` to the exclusive `$20/$18` endpoints represented
   by `EndSign_Range`.

The implementation will be reconstructed from the exact ancestor diffs, then
adapted only where the current constructor or state fields have evolved. The
current independent `usesShortResultsChildRetireTail` flag must remain intact:
the restored timing adjustment subtracts only its own `catchUpEntries()` from
the create-gate count and is passed separately if the current results-object
constructor is extended to retain it. It must not replace or reinterpret the
short-retire-tail behavior. The change will not introduce new zone, route,
frame, or trace predicates.

## Evidence

- `ed113599f`: ROM-backed signpost bump bounds and
  `Obj_EndSignFall` ordering (`sonic3k.asm:176149-176160`,
  `176347-176405`).
- `07b866ced`: results-child timing adjustment that replaced the earlier
  inaccurate allocation-owner interpretation.
- Current compiler failure: twelve missing-symbol errors at
  `TestS3kSignpostInstance.java:134-160`.

## Validation

1. Preserve the current compiler failure as the red test evidence; the
   surviving focused tests already pin every restored behavior, so no duplicate
   test is needed.
2. Run `TestS3kSignpostInstance`.
3. Run the fresh `*TraceReplay` discovery sweep with all three ROM paths.
4. Run the full suite under JDK 21. The pre-fix baseline provides compiler
   evidence rather than a runnable test baseline; record every post-fix failure
   exactly, require the twelve signpost compiler errors to disappear, and
   reject any new compiler or test failure attributable to the repair.
5. Update `CHANGELOG.md` because production behavior is restored.
6. Update `docs/status/trace-frontier-log.md` when the fresh full replay sweep
   selects the next frontier, including the exact command and result.

ROM-backed commands use the properties implemented by the updated repository:
`sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path`, with the discovered
ROM paths.

## Integration

Implement in a new worktree and branch from the current main-workspace
`develop`; copy this design and its reviewed implementation plan into that
branch and stage them with the repair. Review independently. Before integration,
fetch and fast-forward the main-workspace `develop` while preserving unrelated
dirty changes, record the updated baseline, and add the required `README.md`
release/change-log summary for the non-master merge. Merge into `develop`, push,
remove the clean worktree and fully merged local branch, prune worktree metadata,
and resume the trace fleet.
