# CNZ Task 7 - Regression-Check Notes

Captured: 2026-04-22
Related plan: `docs/architecture/plans/2026-04-22-s3k-cnz-trace-replay-ab-plan.md` (Task 7)
Related spec: `docs/architecture/designs/2026-04-22-s3k-cnz-trace-replay-design.md`

## Scope

Per plan Task 7, this is the verification gate before handing off to workstreams
C-G. The plan's expected outcome was:

- `TestS3kAizTraceReplay` PASS (no regression)
- `TestS3kCnzTraceReplay` FAIL with the Task 6 baseline divergences
- Remaining suite (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`) PASS

## Actual outcome (at 98a029fd8, latest A+B HEAD)

```
Tests run: 52, Failures: 1, Errors: 1, Skipped: 0
  ERROR    TestS3kAizTraceReplay#replayMatchesTrace
           java.lang.IllegalStateException: Required engine checkpoint missing
           at trace frame 1651: expected aiz1_fire_transition_begin
  FAILURE  TestS3kCnzTraceReplay#replayMatchesTrace
           1635 errors, 1090 warnings ... (matches Task 6 baseline)
  PASS     TestS3kAiz1SkipHeadless
  PASS     TestSonic3kLevelLoading
  PASS     TestSonic3kBootstrapResolver
  PASS     TestSonic3kDecodingUtils
```

So the CNZ baseline is reproducible as expected, but **AIZ is also red** - which
the plan did not predict.

## Investigation: is the AIZ failure caused by A+B?

Short answer: **no.**

A+B commits (3b9a09d3a..98a029fd8) only touched:

- `tools/bizhawk/s3k_trace_recorder.lua` (external Lua; not executed during
  `mvn test`)
- `docs/**` (pure documentation)
- `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`
  (brand-new class; cannot regress AIZ)
- `src/test/resources/traces/s3k/cnz/**` (brand-new fixture data; only read
  by `TestS3kCnzTraceReplay`)

None of those files can change the engine's AIZ simulation at runtime.

To verify empirically, the regression-check suite was re-run against a clean
worktree of every A+B HEAD and the commit immediately before Task 1
(`6fcde9d4f`). AIZ **fails identically at 6fcde9d4f** with the same
`aiz1_fire_transition_begin` checkpoint missing. Going back further to
`10c58723c` (the earliest A+B-adjacent commit that built the current replay
bootstrap), AIZ fails with a **different** error
("Out-of-order checkpoint aiz1_fire_transition_begin while waiting for
gameplay_start" from `S3kElasticWindowController.onEngineCheckpoint`) - meaning
the underlying regression window predates `10c58723c` and other commits layered
on top shifted the failure mode.

## AIZ trace fixture byte-identity

Per design spec section 10, `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`
must stay byte-identical. Verified: the AIZ `physics.csv` and `aux_state.jsonl`
have not been touched since `8c40e648e` (the initial trace replay gate commit),
and `metadata.json` was last touched by `5c28d2c61 test: pin s3k trace replay
team metadata` - both well before A+B. So the byte-identity invariant holds;
A+B does not touch the AIZ fixture.

## Decision

- **Task 7 outcome:** A+B is clean. The AIZ failure is an orthogonal,
  pre-existing regression on the branch base.
- **C-G dispatch: proceed.** The AIZ regression does not block CNZ workstreams
  because none of C-G's scope intersects the AIZ simulation. Each C-G
  subagent must be instructed that:
  1. `TestS3kAizTraceReplay` is expected to stay in its pre-existing failing
     state. Do not attempt to fix it. Do not let its failure block commits
     for your workstream.
  2. The other four members of the design-spec section 8.2 wider guard
     (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
     `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`) must remain
     green throughout your work. If any of them regresses during your
     workstream, that is your regression to fix.
  3. Your goal is a measurable reduction in `TestS3kCnzTraceReplay` errors
     and a forward shift in the first-divergence frame.
- **Add workstream H (AIZ parity recovery)** to the top-level to-do list.
  Blocking condition for final PR merge per design spec section 10, but
  independent of C-G. Can be worked in parallel.

## Next

- Extend
  `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md` with a
  pointer to this note so any C-G agent reading the baseline sees the AIZ
  caveat immediately.
- Dispatch C-G per plan Task 8.
- Open workstream H separately; it does not gate C-G.
