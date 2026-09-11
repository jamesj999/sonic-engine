# CNZ Trace Replay - Baseline Divergence Report

Captured: 2026-04-22
Test: `TestS3kCnzTraceReplay`
Trace: `src/test/resources/traces/s3k/cnz/`
Recorder: lua v3.1-s3k, profile `level_gated_reset_aware`

## Pre-existing AIZ regression caveat (read first)

`TestS3kAizTraceReplay` is currently red with
`IllegalStateException: Required engine checkpoint missing at trace frame 1651:
expected aiz1_fire_transition_begin` (from `S3kRequiredCheckpointGuard`). This
failure predates the CNZ workstreams and is **not** caused by the Task 1-6
A+B commits - see `docs/architecture/audits/s3k-zones/cnz-task7-regression.md` for the full
bisect.

If you are a workstream C/D/E/F/G agent: treat the AIZ test as expected-red,
do not try to fix it from within your workstream, and do not let it block
commits. Your responsibility is the other four members of the design-spec
section 8.2 guard (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
`TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`) plus any
regressions you introduce. Workstream H (tracked separately) owns the AIZ
recovery.

## Summary

- Total frames in trace: 42253
- First diverging frame: 1
- First diverging field: `x_speed`
- Total errors: 1635
- Total warnings: 1090

Latest checkpoint at failure: `gameplay_start z=3 a=0 ap=0 gm=12`
Latest zone/act state: `zoneact z=3 a=0 ap=0 gm=12`

The test fails almost immediately: at frame 0 the engine spawns Sonic free-standing
at the recorder's start position `(0x0018, 0x0600)`, but the ROM trace shows Sonic
being carried eastward by Tails - starting with `x_speed=0x0100` at frame 1 and a
small `y_speed=0x0010` at frame 2 as the carry descends. Because the engine never
applies the Tails-carry intro, the player immediately falls on its own trajectory
(actual `y_speed` overshoots to `0x00A8` by frame 2) and cascades divergences
through the rest of CNZ1.

Report filenames produced by the runtime are `s3k_cnz1_*` (derived from
`metadata.zone="cnz"` and `metadata.act=1`); they are archived here under the
task-spec-canonical `s3k_0300_*` names (zone id 3 decimal + act 0 zero-based) so
the plan's filenames match.

## First 20 divergences

| # | Frame (start) | Frame (end) | Field | Expected | Actual | Cascading | Likely cause |
| --- | ------------- | ----------- | ------- | --------- | --------- | --------- | ------------ |
| 1 | 1 | 196 | `x_speed` | `0x0100` | `0x0000` | false | C |
| 2 | 2 | 105 | `y_speed` | `0x0010` | `0x00A8` | true | C |
| 3 | 43 | 105 | `air` | `1` | `0` | true | C |
| 4 | 106 | 198 | `g_speed` | `0x0148` | `0x0000` | true | C |
| 5 | 107 | 107 | `y_speed` | `-0100` | `0x0000` | true | C |
| 6 | 193 | 198 | `y_speed` | `0x0000` | `0x0530` | true | C |
| 7 | 193 | 198 | `air` | `0` | `1` | true | C |
| 8 | 193 | 198 | `rolling` | `0` | `1` | true | C |
| 9 | 203 | 222 | `angle` | `0x0000` | `0x00FA` | true | C |
| 10 | 204 | 223 | `y_speed` | `0x0000` | `-00D0` | true | C |
| 11 | 210 | 215 | `x_speed` | `0x0600` | `0x0565` | false | C |
| 12 | 224 | 225 | `angle` | `0x0000` | `0x00FC` | true | C |
| 13 | 225 | 226 | `y_speed` | `0x0000` | `-0096` | true | C |
| 14 | 235 | 274 | `y_speed` | `-0610` | `-0700` | true | C |
| 15 | 250 | 266 | `x_speed` | `0x0579` | `0x0600` | false | C |
| 16 | 270 | 277 | `g_speed` | `0x0522` | `0x0600` | true | C |
| 17 | 270 | 281 | `air` | `0` | `1` | true | C |
| 18 | 270 | 352 | `rolling` | `0` | `1` | true | C |
| 19 | 274 | 286 | `x_speed` | `0x0552` | `0x04CA` | false | C |
| 20 | 280 | 363 | `y_speed` | `0x0000` | `0x00A8` | true | C |

Workstream-cause legend:
- **C** - Tails-carry intro (the intro arc / carry-drop window, roughly the first
  ~600 frames of CNZ1 while Sonic is riding Tails and bouncing into the first
  set of bumpers)
- **D** - CNZ1 mini-boss
- **E** - CNZ2 end-boss
- **F** - Knuckles cutscenes
- **G** - Stragglers (generic object parity, pattern animator, palette cycling)

## Raw reports

- `docs/architecture/validation/s3k-zones/cnz-trace-divergence.d/s3k_0300_report.json`
- `docs/architecture/validation/s3k-zones/cnz-trace-divergence.d/s3k_0300_context.txt`

## Related notes

- `docs/architecture/audits/s3k-zones/cnz-task7-regression.md` - Task 7 regression-check outcome
  (A+B clean vs. pre-existing AIZ failure; dispatch guidance for C-G agents).

## Likely causes (hypothesis - verify during C-G workstreams)

- **Frames 1-~600: Tails-carry intro -> workstream C.** Frame 1's `x_speed`
  mismatch (`0x0100` expected, `0x0000` actual) is the dead give-away: the ROM
  starts with Sonic riding Tails's grasp, moving right at constant speed while
  Tails descends. The engine spawns Sonic free-standing at
  `(0x0018, 0x0600)` instead of being carried, so every field that depends on
  the intro arc diverges. All 20 first divergences *start* in the first 280
  frames (row #20 starts at frame 280) and are flagged `C`; several of them
  extend past the 300-frame mark (for example, row #20 runs to frame 363),
  consistent with the broader carry-intro window used by workstream C.
- **Remaining ~1615 errors:** the JSON report actually runs through to
  frame 41937 (out of 42253), so divergences from across the whole playthrough
  are captured - it is the *first-20 summary* that stops early, not the report
  itself. After workstream C fixes the intro, re-run the test and re-triage:
  the top-20 window should shift into CNZ1 proper (post-carry physics,
  first-act objects), then into the CNZ1 mini-boss (around camera X ~0x3000),
  the CNZ2 arena post-act-transition (~frames 16669-17276), Knuckles
  cutscenes, and stragglers. Cascading ripples from the intro currently mask
  those later workstreams in the top-20, which is why a clean post-C re-capture
  is the next hand-off.

## Next step

Dispatch workstream agents per the top-level spec's section 7. Workstream C
(Tails-carry intro) is the clear first priority: fix it, re-run this test, and
the baseline's "first-20" plus most of the cascaded errors should collapse.
Once C is green, re-capture the baseline so D-G have a clean divergence window
to work from.
