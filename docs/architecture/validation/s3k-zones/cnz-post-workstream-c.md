# CNZ Trace Replay - Post-Workstream-C Baseline

Captured: 2026-04-22
Test: `TestS3kCnzTraceReplay`
After: workstream C (Tails-carry intro) merge + 0x00->0x0C same-frame fix at c627ba2bc35402dc1045fd23dc8a4966c6b8ff1d

## Summary

- Previous baseline (pre-workstream-C): 1635 errors, first divergence frame 1
- Post-C + fix baseline: 1954 errors, first divergence frame 4
- Delta vs pre-C: +319 errors, first-divergence frame shifted +3 (frame 4 vs frame 1)

## Notes on the 0x00->0x0C fix

The initial post-workstream-C trace showed 2547 errors with first-divergence
frame 0 because `SidekickCpuController.updateInit()` invoked
`updateCarryInit()` in the same tick it transitioned into `CARRY_INIT`.
That wrote `x_speed=0x0100` one frame earlier than the ROM.

ROM evidence (sonic3k.asm):
- `loc_13A10` (line 26414, INIT handler for CNZ act 0) executes
  `move.w #$C,(Tails_CPU_routine).w` then `rts`. No fall-through.
- `loc_13FC2` (line 26903, 0x0C body) writes `x_vel=$100` and falls
  through (no `rts`) into `loc_13FFA` (0x0E body).

The same-frame fall-through is **0x0C -> 0x0E**, not **0x00 -> 0x0C**.
Fix commit `c627ba2bc` removes the premature `updateCarryInit()` call.
The 0x0C body now runs on the tick AFTER the INIT handler sets the
state, matching the ROM's `rts` boundary.

First divergence now lands at frame 4 (`y_speed` mismatch), i.e. a few
ticks into the carry arc rather than at frame 0. The residual error
count (1954) is dominated by the Tails-flying-with-cargo physics gap
tracked in `docs/S3K_KNOWN_DISCREPANCIES.md` ("Tails Flying-With-Cargo
Physics") — Tails grounds around frame 42 instead of the ROM's frame
106, cascading through the rest of the carry arc.

## First 20 divergences (post-fix)

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

The first five errors (frames 4..107) remain in workstream C territory:
they reflect the still-deferred Tails-flying-with-cargo physics gap
(Tails lands at frame ~42 vs ROM frame 106). Errors 6..20 (frames 193+)
all fall in the mini-boss window and are workstream D concerns.

## Next dispatch

Based on the updated first-20 distribution:
- [ ] C-follow-up (Tails flying-with-cargo physics): **DEFER**. This is
  the documented Tails carry-aware-lift gap (see
  `docs/S3K_KNOWN_DISCREPANCIES.md` "Tails Flying-With-Cargo Physics").
  The 0x00->0x0C fix landing here does not address it; addressing it
  would likely knock errors 1..5 off the first-20 list but require a
  separate investigation of `Tails_CPU_routine` 0x0C/0x0E/0x20's
  per-frame y_vel/gravity handling. Left deferred per task scope.
- [x] D (CNZ1 mini-boss): **DISPATCH**. All of errors 6..20 are now in
  the D-owned window (frames 193..292). With carry-arc errors reduced
  from 2547 to 1954, the mini-boss window is the next-highest-value
  target; a D workstream should take the mini-boss from here.
- [ ] E (CNZ2 end-boss): **DEFER** — not visible in the current first-20;
  dispatch after D clears the mini-boss window.
- [ ] F (Knuckles cutscenes): **DEFER** — not visible in the current
  first-20; revisit after D.
- [ ] G (Stragglers): **DEFER** — dispatch last, once C-follow-up and D
  clear.

## Wider guard status

Ran against commit c627ba2bc (same tests as the prior baseline):

```
Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
```

Tests included:
`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
`TestSonic3kDecodingUtils`, `TestS3kCnzCarryHeadless`, `TestSidekickCpuControllerCarry`,
`TestSonic3kCnzCarryTrigger`, `TestObjectControlledGravity`, `TestPhysicsProfile`,
`TestCollisionModel`.

**Pre-existing failures** (not introduced by workstream C, not changed since before
workstream C):
- `TestPhysicsProfileRegression` (18 errors) — `RuntimeManager.createGameplay()` throws
  `EngineServices have not been configured` in this branch's test harness; no workstream C
  file touches its dependencies.
- `TestSpindashGating` (4 errors) — same `EngineServices` singleton configuration error;
  no workstream C file touches its dependencies.

Both pre-existing failures have the same root cause (singleton configuration gap in the
test harness) and are not regressions from this workstream.

## AIZ replay regression

`TestS3kAizTraceReplay` continues to fail on the pre-existing
`aiz1_fire_transition_begin` checkpoint issue documented in
`docs/architecture/audits/s3k-zones/cnz-task7-regression.md`. Workstream H owns that
recovery and does not block this baseline.
