# Visual Special-Stage Admission Observation Design

## Problem

When a visual complete run reaches Sonic 1's GHZ1 giant-ring transition, the
first `SPECIAL_STAGE` all-mode tick captures a `RunPlaybackObservation` while
the coordinator still owns the exhausted GHZ1 segment. `beforeAdmission` then
admits the special-stage destination and installs its fresh 3,728-row
`TraceRunSpecialStageRowDriver`, but `runCoordinatorTick` continues using the
pre-admission observation. Its stale `currentSegmentExhausted=true` value is
therefore applied to segment 1. The coordinator emits `CloseSegment(1)`, and
the new row driver correctly rejects closure with zero compared rows.

This is an adapter ownership bug. The coordinator and row driver are behaving
according to their contracts; the visual adapter has crossed a segment
admission boundary without invalidating an observation owned by the old
segment.

## Required invariant

A `RunPlaybackObservation` may drive production exhaustion, closure, or
ownership checks only for the coordinator segment that was current when the
observation was captured. If `beforeAdmission` changes the current segment,
the adapter must capture a new observation before any such decision.

No trace values are copied into gameplay state. The correction changes only
which production-owned observation is presented to the shared structural
coordinator.

## Design

`TraceSessionLauncher.runCoordinatorTick` will record the coordinator segment
index associated with `currentObservation`. After applying
`beforeAdmission`, it will compare that index with the coordinator's current
segment index. If admission changed ownership, it will recapture the
observation from current engine state before constructing
`productionObservation` or evaluating `currentSegmentExhausted`.

The existing same-iteration level-load production-owner substitution remains
unchanged. That path applies only after the observation has been made current
for the admitted segment and still carries value-free production ownership;
it does not authorize reuse of a source segment's exhaustion bit.

The implementation stays in the visual adapter. The shared coordinator will
not learn about UI, `TraceSessionLauncher`, or mutable observation repair, and
the row driver will retain strict early-close verification.

## Regression coverage

A launcher regression will construct the real coordinator path at the
transition gap, provide a source-owned exhausted observation for the first
special-stage tick, and invoke the production `runCoordinatorTick` seam. It
must prove:

- segment 1 is admitted with `SPECIAL_LOCAL` input ownership;
- no `CloseSegment(1)` action appears on that tick;
- the new special-stage row driver remains at row 0 and open;
- the following production admission can publish row 0 normally.

The test must fail before the production change with the reported
`dynamic-art segment expected 3728 rows but compared 0` closure failure, not
with synthetic setup or parser errors.

Focused verification will include the launcher run-branch tests, special-stage
row driver tests, visual special-stage lifecycle tests, the emerald prefix,
and the architectural source guards. The all-ROM suite remains a
baseline-comparison run because the repository currently reaches its known
heap ceiling with unrelated failures.

## Scope

This fix addresses the immediate zero-row closure when entering a represented
special stage. It does not claim to resolve the independent later emerald-run
frontiers or alter special-stage gameplay, PLC scheduling, or trace fixtures.
