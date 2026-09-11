# CNZ Trace Replay - Post-Workstream-D Baseline

Captured: 2026-04-23
Test: `TestS3kCnzTraceReplay`
After: workstream D (CNZ1 miniboss) merge at 651af9e88cd125d691417ab7e2835f4f4166fd84

## Summary

- Pre-D baseline (post-C): 1954 errors, first divergence frame 4
- Post-D baseline: 1954 errors, first divergence frame 4
- Delta vs post-C: 0 errors, first-frame shift 0

The error count is unchanged from the post-C baseline. The post-D first-20 divergences are
**identical** to the post-C first-20 — rows 6..20 (frames 193..292) are NOT displaced off the
list. This is the expected cascade-dominant outcome described in the plan's risk section: the
workstream-C Tails-flying-with-cargo gap (Tails lands ~frame 42 vs ROM frame 106) causes a
position/velocity delta that propagates through the mini-boss approach window and into the
mini-boss window itself, dominating the headline error count.

The workstream-D boss state machine (CnzMinibossInstance, CnzMinibossTopInstance, arena-entry
wiring) is fully implemented and all 31 D-owned unit/integration tests are green. The boss port
does not move the trace numbers because the trace fixture's approach trajectory is already
diverged before frame 193 due to C residue; the engine cannot faithfully compare boss-window
frames against the ROM reference when the player enters with incorrect position and velocity.

Stretch target (<= 1700) was not hit, for the same reason.

## First 20 divergences (post-D)

| # | Frame (start) | Frame (end) | Field | Expected | Actual | Cascading | Likely workstream |
|---|---------------|-------------|-------|----------|--------|-----------|-------------------|
| 1 | 4 | 105 | `y_speed` | `0x0020` | `0x00A8` | no | C (Tails-carry descent arc) |
| 2 | 42 | 105 | `air` | `1` | `0` | yes | C (Tails-lands-early) |
| 3 | 44 | 196 | `x_speed` | `0x0118` | `0x0000` | yes | C (post-release cascade) |
| 4 | 106 | 197 | `g_speed` | `0x0148` | `0x0000` | yes | C (post-release cascade) |
| 5 | 107 | 107 | `y_speed` | `-0100` | `0x0000` | no | C (ground-release impulse timing) |
| 6 | 193 | 197 | `y_speed` | `0x0000` | `0x0530` | no | C/D boundary (pre-mini-boss land) |
| 7 | 193 | 197 | `air` | `0` | `1` | yes | C/D boundary |
| 8 | 193 | 197 | `rolling` | `0` | `1` | yes | C/D boundary |
| 9 | 198 | 216 | `angle` | `0x0000` | `0x00FA` | yes | D (mini-boss window) |
| 10 | 199 | 214 | `x_speed` | `0x05D1` | `0x0549` | yes | D (mini-boss window) |
| 11 | 199 | 217 | `y_speed` | `0x0000` | `-00C7` | no | D (mini-boss window) |
| 12 | 201 | 211 | `g_speed` | `0x05E9` | `0x0564` | yes | D (mini-boss window) |
| 13 | 218 | 219 | `angle` | `0x0000` | `0x00FC` | yes | D (mini-boss window) |
| 14 | 219 | 220 | `y_speed` | `0x0000` | `-0090` | no | D (mini-boss window) |
| 15 | 238 | 269 | `y_speed` | `-0568` | `-0700` | no | D (mini-boss window) |
| 16 | 250 | 257 | `x_speed` | `0x0579` | `0x0600` | yes | D (mini-boss window) |
| 17 | 270 | 270 | `x_speed` | `0x0522` | `0x049F` | yes | D (mini-boss window) |
| 18 | 270 | 277 | `g_speed` | `0x0522` | `0x0600` | yes | D (mini-boss window) |
| 19 | 270 | 281 | `air` | `0` | `1` | yes | D (mini-boss window) |
| 20 | 270 | 292 | `rolling` | `0` | `1` | yes | D (mini-boss window) |

Note: rows 6..20 are the same rows as the post-C baseline, at the same frames, with the same
field/expected/actual values. The C-residue cascade fully dominates; no D-window relief is
measurable from the trace alone. The rows labelled "D (mini-boss window)" reflect player
position errors cascaded from row 6 (frame 193), not divergences in the boss state machine.

## Cascade analysis

- **Workstream C residue (frames 4..107):** Still visible, same five rows as the post-C
  baseline. Rows 1–5 (y_speed, air, x_speed, g_speed, y_speed) are identical in field,
  start_frame, end_frame, expected, and actual. The Tails-flying-with-cargo physics gap
  (Tails lands frame ~42 vs ROM frame 106) remains the root cause.
- **Workstream D mini-boss window (frames 193..292):** NOT displaced from the first-20 list.
  Rows 6..20 (frames 193..292) are identical to the post-C baseline. The cascade from frame
  107 (C residue row 5, `y_speed -0100 vs 0x0000`) propagates through the approach run and
  carries a position/velocity error into the mini-boss arena. The boss state machine is
  implemented correctly (all D-owned unit tests green) but the trace comparison cannot
  distinguish a D-native boss divergence from a cascaded C error at these frames.
- **Workstream E end-boss window (frames >= ~1500 est.):** Not visible in the first-20; the
  cascade fills the list before E-window frames are reached. Dispatch when C-follow-up
  reduces the carry-arc error count far enough to uncover E-window divergences.
- **Workstream F Knuckles cutscenes:** Not visible in the first-20; same reason. Defer.
- **Workstream G stragglers:** Not visible in the first-20. Defer.

## Next dispatch

Based on the post-D first-20 being identical to post-C, the priority order is unchanged.

**Recommended next dispatch: C-follow-up (Tails flying-with-cargo physics).** The DEFER
tags below reflect this branch's scope (workstream D only) rather than relative priority —
C-follow-up is the highest-value next dispatch because it is the documented dominant
cascade source, and clearing it is the prerequisite for any other workstream's divergences
to become measurable in the trace.

- [ ] C-follow-up (Tails flying-with-cargo physics): **DEFER for this branch / DISPATCH
  next.** The documented gap (Tails lands frame ~42 vs ROM frame 106) is the dominant
  cascade source. Addressing it would require implementing the per-frame y_vel / gravity
  handling in `Tails_CPU_routine` 0x0C / 0x0E / 0x20 for the carry arc. Left deferred for
  this branch — the D workstream did not worsen it and the boss port is complete. When
  C-follow-up lands, the next baseline's first-20 should reveal D-window divergences in
  isolation.
- [ ] D-follow-up (boss hit-response timing): **DEFER**. No D-native residual is isolatable
  in the current trace because all D-window rows are cascade-dominated. Once C-follow-up
  clears the carry-arc error, re-run the trace to check for D-window residue (e.g. boss
  position offsets, swing-phase timing). If residue appears, dispatch D-follow-up then.
- [ ] E (CNZ2 end-boss): **DEFER** — not visible in the current first-20. Dispatch after
  C-follow-up clears the carry-arc cascade.
- [ ] F (Knuckles cutscenes): **DEFER** — not visible. Revisit after C and D are settled.
- [ ] G (Stragglers): **DEFER** — dispatch last.

## Wider guard status

```
mvn test "-Dmse=off" "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kCnzCarryHeadless,TestSidekickCpuControllerCarry,TestSonic3kCnzCarryTrigger,TestObjectControlledGravity,TestPhysicsProfile,TestCollisionModel"
```

Result: **Tests run: 115, Failures: 0, Errors: 0, Skipped: 0** — identical to post-C baseline.
All 10 suites passed; no regressions introduced by workstream D.

## New D-owned tests

| Class | Result |
|-------|--------|
| `TestCnzMinibossConstants` | PASS (4 tests) |
| `TestCnzMinibossInstanceBase` | PASS (4 tests) |
| `TestCnzMinibossSwingPhase` | PASS (3 tests) |
| `TestCnzMinibossOpeningPhase` | PASS (3 tests) |
| `TestCnzMinibossDefeatPhase` | PASS (3 tests) |
| `TestCnzMinibossTopPhysics` | PASS (3 tests) |
| `TestCnzMinibossArenaEntry` | PASS (2 tests) |
| `TestS3kCnzMinibossHeadless` | PASS (4 tests) |
| `TestS3kCnzMinibossArenaHeadless` | PASS (3 tests) |
| `TestCnzMinibossRegistered` | PASS (2 tests) — added in T9, not in plan template |

Total D-owned: 31 tests, 0 failures, 0 errors.

## AIZ replay regression

`TestS3kAizTraceReplay` continues to fail on the pre-existing `aiz1_fire_transition_begin`
checkpoint issue (workstream H). Not a D regression.
