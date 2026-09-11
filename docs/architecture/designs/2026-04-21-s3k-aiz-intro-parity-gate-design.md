# S3K AIZ Intro Parity Gate Design

## Goal

Define the immediate gate for Sonic 3&K AIZ replay parity work so the branch
does not mix harness defects with real engine parity bugs.

The next step is not "fix the whole AIZ to HCZ run." The next step is to make
the authoritative BK2-backed replay gate trustworthy, then use that gate to
drive parity only through the AIZ intro window.

## Current Evidence

The branch already contains a real BK2-backed end-to-end S3K fixture:

- trace directory: `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`
- BK2 movie: `s3-aiz1-2-sonictails.bk2`
- recorded checkpoints:
  - `intro_begin` at trace frame `0`
  - `gameplay_start` at trace frame `1500`
  - `aiz1_fire_transition_begin` at trace frame `1651`
  - `aiz2_reload_resume` at trace frame `5610`
  - `aiz2_main_gameplay` at trace frame `5610`

A recent local replay run, captured in non-committed output at
`target/surefire-reports/TEST-com.openggf.tests.trace.s3k.TestS3kAizTraceReplay.xml`,
failed structurally, not with a gameplay-field divergence:

- `Elastic window drift budget exhausted for gameplay_start`
- the same local output shows engine checkpoints at:
  - `intro_begin` at replay frame `0`
  - `gameplay_start` at replay frame `1528`

This local output is useful for diagnosis, but it is not a committed branch
artifact. The durable branch evidence is the recorded checkpoint stream plus the
current controller and detector code.

By the controller budget formula
`maxEngineSpan = span + max(180, span)`, the first window
`intro_begin -> gameplay_start` has a budget of `3000` engine ticks for a trace
span of `1500`. A replay `gameplay_start` at frame `1528` therefore does not
support the claim that the first window exhausted its budget. Combined with the
controller's current chained-window map, that local failure is more consistent
with the wrong second elastic window opening at `gameplay_start`.

There is also a spec/implementation mismatch in the replay harness:

- the committed design says elastic windows are:
  - `intro_begin -> gameplay_start`
  - `aiz1_fire_transition_begin -> aiz2_main_gameplay`
- the current controller implementation uses:
  - `intro_begin -> gameplay_start`
  - `gameplay_start -> aiz2_main_gameplay`
- the current detector emits `aiz1_fire_transition_begin`, but omits it from
  `REQUIRED_ORDER` and the current detector tests treat it as diagnostics-only

This mismatch must be resolved before using the replay result to make claims
about intro parity or later-run parity.

## Decision

Use a two-stage gate:

1. Fix the replay-window/controller contract so it matches the committed S3K
   trace-fixture design.
2. Re-run the authoritative BK2 replay and treat only the `intro_begin ->
   gameplay_start` span as the active parity target until that window either
   passes cleanly or fails with a concrete first divergence.

This is the recommended path because it removes one known false signal first.
Without that correction, intro debugging risks chasing a harness defect instead
of a ROM-parity bug.

## Required Contract

The replay harness must follow these rules:

- elastic window 1 opens on recorded `intro_begin` and closes on
  `gameplay_start`
- frames after `gameplay_start` and before `aiz1_fire_transition_begin` are
  strict-comparison frames, not part of either elastic window
- elastic window 2 opens on recorded `aiz1_fire_transition_begin` and closes on
  `aiz2_main_gameplay`
- `aiz1_fire_transition_begin` must be treated as a required checkpoint by both
  the trace contract and the replay detector contract; it is not
  diagnostics-only
- optional checkpoints remain diagnostics-only and never act as elastic-window
  anchors
- replay remains strict outside elastic windows
- a structural failure inside a window is valid, but it must reflect the
  correct checkpoint pairing
- the drift-budget rule remains
  `maxEngineSpan = span + max(180, span)` from
  `S3kElasticWindowController`

## Investigation Scope After Harness Alignment

Once the window contract matches the design, the next parity loop is:

1. Run the authoritative S3K replay test.
2. Verify that the replay no longer opens a chained second elastic window at
   `gameplay_start`.
3. If it fails before or at `gameplay_start`, treat AIZ intro parity as the
   immediate engine task.
4. If it closes the intro window and then fails in the strict span
   `[gameplay_start, aiz1_fire_transition_begin)`, treat that strict region as
   the next active parity target rather than folding it back into intro work.
5. If it reaches `aiz1_fire_transition_begin` and then fails structurally or
   semantically in the second window, treat that as second-window parity work,
   not intro parity work.
6. Use BK2 playback, trace checkpoints, probe output, and disassembly to find
   the first real divergence in the earliest failing region.
7. Fix only the root cause needed to move the first red point forward.
8. Re-run the replay and repeat.

## Out Of Scope

This gate explicitly does not include:

- fixing the full AIZ to HCZ replay in one pass
- post-intro AIZ gameplay parity work before the intro window is trustworthy
- fire-transition parity work before the intro window is either green or proven
  no longer first-red
- splitting the authoritative fixture into smaller committed fixtures
- weakening the replay contract to ignore real mismatches

## Diagnostics To Prefer

When the replay is still red after harness alignment, prefer evidence in this
order:

1. the first replay failure and latest checkpoint boundary
2. replay probe output around the failing window
3. trace `aux_state.jsonl` checkpoint/state events
4. disassembly-backed AIZ intro behavior docs

Do not diagnose later-run drift until the earliest failing checkpoint window is
understood.

## Success Criteria

This gate is complete only when one of these is true:

- the controller no longer opens a second elastic window at `gameplay_start`,
  and `aiz1_fire_transition_begin` is the second required window anchor
- the authoritative replay gets beyond `gameplay_start` without a
  controller-budget error attributed to `gameplay_start`
- after that harness alignment, the first remaining red point, if any, is a
  concrete divergence or semantic checkpoint failure at or after
  `gameplay_start`

Implementation planning may begin after this spec is approved. The first plan
must treat replay-window alignment as part of the gate, not as optional cleanup.
