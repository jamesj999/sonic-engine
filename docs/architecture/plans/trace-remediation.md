# Trace Remediation Plan

Date: 2026-06-12

This file is the durable trace-remediation artifact for the current release
push. It rolls the active problem statements, trace frontier table, cluster
analysis, and recommended remediation order into one place. Historical evidence
remains in `docs/status/trace-frontier-log.md`; broader release-review and
non-trace hygiene remains in `docs/project/release-readiness-roadmap.md`.

The removed scratch inputs `SIDEKICK_CPU_AUDIT.tmp.md` and
`RELEASE_REVIEW_FINDINGS.tmp.md` must not be reintroduced. Their confirmed
trace-relevant leftovers are represented here.

## Current Baseline

- Branch baseline: `develop` at `6511b3645` (`fix: align s3k complete-run
  handoff replay`).
- Latest full trace sweep command:
  - `mvn -Dmse=off "-Dtest=*TraceReplay" "-DfailIfNoTests=false" "-Ds1.rom.path=s1.gen" "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" "-Dsurefire.forkCount=1" "-Dsurefire.argLine=-Xshare:off -Xmx3g" test`
- Latest full trace sweep result:
  - `Tests run: 90, Failures: 62, Errors: 1, Skipped: 0`.
- Release state:
  - Not a green all-trace certification.
  - CI is expected to fail until the trace frontiers below are fixed or
    explicitly reclassified with evidence.

## Current Frontier Table

This table starts from the widened true-frontier comparator sweep and applies
the later committed frontier movements recorded at the top of
`docs/status/trace-frontier-log.md`.

| Trace | Frame | Field | Expected | Actual | Cluster |
|---|---:|---|---:|---:|---|
| `s1_credits_01_mz2` | 262 | `status_byte` | `0x0021` | `0x0001` | status/push timing |
| `s1_credits_04_slz3` | 447 | `status_byte` | `0x0028` | `0x0008` | status/push timing |
| `s1_credits_05_sbz1` | 413 | `status_byte` | `0x0029` | `0x0028` | status/on-object handoff |
| `s1_credits_06_sbz2` | 438 | `status_byte` | `0x0016` | `0x0006` | status/push timing |
| `s1_ghz1` | 527 | `rolling` | `1` | `0` | roll/radius timing |
| `s1_ghz2` | 2370 | `y` | `0x0267` | `0x0266` | one-pixel position |
| `s1_ghz3` | 370 | `y_speed` | `-0220` | `-0320` | exact `0x100` speed delta |
| `s1_lz1` | 302 | `y_speed` | `-0100` | `0x0000` | spring/launcher |
| `s1_lz2` | 1089 | `y` | `0x03A8` | `0x03AD` | roll/radius timing |
| `s1_lz3` | 466 | `y` | `0x0807` | `0x0007` | vertical wrap |
| `s1_lz4` | 1421 | `camera_y` | `0x038C` | `0x0388` | camera |
| `s1_mz1` | 3224 | `y_speed` | `0x02C8` | `0x01C8` | exact `0x100` speed delta |
| `s1_mz2` | 1295 | `y` | `0x0451` | `0x044C` | roll/radius timing |
| `s1_mz3` | 996 | `rolling` | `1` | `0` | roll/radius timing |
| `s1_sbz1` | 1367 | `rolling` | `1` | `0` | roll/radius timing |
| `s1_sbz2` | 576 | `y` | `0x0763` | `0x075C` | roll/radius timing |
| `s1_sbz3` | 713 | `y_speed` | `0x0000` | `-0700` | spring/launcher |
| `s1_slz1` | 672 | `y` | `0x01D1` | `0x01CC` | roll/radius timing |
| `s1_slz2` | 651 | `g_speed` | `0x1000` | `0x10AE` | slope factor |
| `s1_slz3` | 718 | `y_speed` | `0x0000` | `0x0610` | spring/launcher |
| `s1_syz1` | 250 | `y_speed` | `-0610` | `-0510` | exact `0x100` speed delta |
| `s1_syz2` | 1088 | `x_speed` | `0x02E8` | `0x02F4` | individual |
| `s1_syz3` | 1392 | `x_speed` | `-0200` | `0x0200` | bumper/bounce |
| `s2_arz1` | 990 | `y` | `0x03A3` | `0x039E` | roll/radius timing |
| `s2_arz2` | 899 | `y_speed` | `-02D0` | `-01D0` | exact `0x100` speed delta |
| `s2_cnz1` | 202 | `tails_x` | `0x0265` | `0x0264` | downstream Tails movement |
| `s2_cnz2` | 728 | `y` | `0x0571` | `0x056C` | roll/radius timing |
| `s2_cpz1` | 724 | `tails_status_byte` | `0x0000` | `0x0020` | sidekick status/CPU |
| `s2_cpz2` | 759 | `tails_status_byte` | `0x0020` | `0x0000` | sidekick status/CPU |
| `s2_dez1` | 1557 | `x_speed` | `0x0000` | `0x003C` | ending route |
| `s2_ehz1` | 395 | `tails_status_byte` | `0x0008` | `0x0009` | sidekick status/CPU |
| `s2_htz1` | 419 | `tails_cpu_interact` | `0x0000` | `0x0018` | sidekick CPU |
| `s2_htz2` | 831 | `tails_cpu_jumping` | `0x0001` | `0x0000` | sidekick CPU |
| `s2_mcz1` | 398 | `tails_routine` | `0x0006` | `0x0002` | sidekick routine |
| `s2_mcz2` | 1807 | `tails_x_speed` | `-0018` | `0x00E8` | downstream Tails movement |
| `s2_mtz1` | 375 | `tails_cpu_interact` | `0x0001` | `-0001` | sidekick CPU |
| `s2_mtz2` | 645 | `tails_x_speed` | `0x00C1` | `-0200` | downstream Tails movement |
| `s2_mtz3` | 461 | `tails_cpu_interact` | `0x006A` | `-0001` | sidekick CPU |
| `s2_ooz1` | 395 | `tails_status_byte` | `0x000A` | `0x0002` | sidekick status/CPU |
| `s2_ooz2` | 222 | `tails_cpu_interact` | `0x0000` | `0x001F` | sidekick CPU |
| `s2_scz1` | 6370 | `y` | `0x057D` | `0x0578` | Tornado route/non-sidekick |
| `s3k_aiz1` | 1058 | `tails_status_byte` | `0x0003` | `0x0002` | sidekick status/CPU |
| `s3k_cnz1` | 1 | `y` | `0x061C` | `0x0600` | S3K complete-run startup carry |
| `s3k_hcz1` | 97 | `status_byte` | `0x0021` | `0x0001` | status/push timing |
| `s3k_icz1` | 1156 | `tails_status_byte` | `0x0003` | `0x0002` | sidekick status/CPU |
| `s3k_lbz1` | 410 | `y_speed` | `0x0000` | `-0100` | S3K complete-run startup/path behavior |
| `s3k_mgz1` | 454 | `tails_status_byte` | `0x0003` | `0x0002` | sidekick status/CPU |
| `s3k_mhz1` | 1 | `y` | `0x051C` | `0x0500` | S3K complete-run startup carry |

## Clustered Problem Statements

### 1. S3K Complete-Run Startup Carry / Native Sidekick State

Affected traces:

- `s3k_cnz1`: frame 1 `y expected=0x061C actual=0x0600`.
- `s3k_mhz1`: frame 1 `y expected=0x051C actual=0x0500`; the first-frame
  context also expects native `tails_cpu_routine=0x000E`.
- `s3k_lbz1`: frame 410 `y_speed expected=0x0000 actual=-0100`.

Problem statement:

The prior false frame-zero handoff/snap frontier is fixed. The remaining
cluster is native complete-run state carried across the segment boundary:
player center, startup object state, post-title-card sidekick routine, and
possibly per-zone startup/carry objects. Fixes must model ROM state or object
routine ownership; no trace-row hydration, zone carve-out, route carve-out, or
frame-number special case is allowed.

### 2. Status / Push / On-Object Timing

Affected traces:

- S1 credits traces: `s1_credits_01_mz2`, `s1_credits_04_slz3`,
  `s1_credits_05_sbz1`, `s1_credits_06_sbz2`.
- S3K `s3k_hcz1`.

Problem statement:

The widened comparator exposed earlier ROM-visible status-byte mismatches,
especially `Status_Push`, `Status_OnObj`, and direction/facing handoffs. The
`s1_credits_05_sbz1` Obj66 right-edge contact was fixed and advanced to a new
facing/on-object mismatch at frame 413. Continue from the earliest single-frame
status mismatch, using the object routine and `SolidObject`/ride ownership
state that actually sets or clears each bit.

### 3. Roll / Radius / Landing Timing

Affected traces:

- Rolling mismatch: `s1_ghz1`, `s1_mz3`, `s1_sbz1`.
- Y-off-by-about-five group: `s1_lz2`, `s1_slz1`, `s1_mz2`, `s1_sbz2`,
  `s2_arz1`, `s2_cnz2`, `s2_scz1`.

Problem statement:

Standing and rolling/jumping radii differ by 5 pixels for Sonic. A wrong
roll-clear, landing, or y-radius compensation frame can explain both
roll-state mismatches and the common 5-pixel y deltas. Treat these as one
shared hypothesis until disproven by per-frame state around the frontier.

### 4. Exact `0x100` Speed Deltas

Affected traces:

- `s1_ghz3`, `s1_syz1`, `s1_mz1`, `s2_arz2`, `s2_mcz1`.

Problem statement:

The deltas are exactly 1 pixel per frame in fixed-point speed units. That
points to one missing, extra, doubled, or skipped movement term in the owning
routine, not accumulated drift. Diff the single divergent frame's movement
subroutine before changing shared constants.

### 5. S2/S3K Sidekick CPU and Downstream Tails Movement

Affected root frontiers:

- `s2_htz1`, `s2_htz2`, `s2_mtz1`, `s2_mtz3`, `s2_ooz2`,
  `s3k_aiz1`, `s3k_icz1`, `s3k_mgz1`, and `s3k_mhz1`.

Affected downstream movement traces:

- `s2_cpz1`, `s2_cpz2`, `s2_mcz2`, `s2_mtz2`, `s2_ooz1`, `s2_cnz1`.

Problem statement:

Most S2 failures involve Tails rather than Sonic. Fix CPU routine, interact,
status publication, respawn/off-screen marker, delayed input, and object ride
state before treating later `tails_x_speed` or `tails_x` frontiers as
independent movement bugs.

Specific audit leftovers:

- `s2_mtz2` frame 645 is masked by Tails being hurt by a Shellcracker claw
  where ROM Tails is rolling; inspect solid/touch ordering before changing
  sidekick CPU.
- `s3k_aiz1` still has monitor/push sidekick frontier and auto-jump cadence
  sub-test failures.
- Dead-fall ordering still needs ROM review around `applyDeathMovement` and
  `Obj02_Dead` / `Tails_Max_Y_pos + $100`.
- S2 signpost airborne end-of-act trigger behavior, Obj85 preserved-roll jump
  suppression, and CPU `Level_frame_counter` visibility remain open.
- S3K Tails-alone CPU routines `$1A-$1E`, `$20`, `$22`, MHZ1 carry-intro
  spawn, ICZ/SSZ dormant park, and star-post zero-kinematics reset remain
  incomplete.

### 6. Springs / Launchers

Affected traces:

- `s1_sbz3`, `s1_slz3`, `s1_lz1`.

Problem statement:

These have large same-frame y-speed mismatches around launcher/spring trigger
timing. Compare the owning spring/launcher routine, trigger side, and
frame-phase ordering before touching generic jump or gravity constants.

### 7. Individual Frontiers

Affected traces:

- `s1_lz3` vertical wrap.
- `s1_syz3` bumper/bounce sign.
- `s1_slz2` slope factor.
- `s3k_mgz1` ring pickup/status.
- `s3k_icz1` sidekick status after prior ICZ progress.
- `s2_dez1` ending-route speed.
- `s1_lz4` camera.
- `s1_ghz2`, `s1_syz2` one-off position/speed gaps.

Problem statement:

These should be handled after higher-leverage clusters unless one becomes a
clear blocker for a broader fix. Each needs its own one-frame root-cause
investigation.

## Recommended Remediation Order

1. Keep comparison broad and honest.
   - Do not narrow the comparator or add tolerances to regain green output.
   - Add compared fields only when they expose true ROM-visible state.
2. Continue S3K complete-run startup carry/native sidekick state.
   - Start with CNZ/MHZ frame 1 because the frontier is at the first native
     tick after the handoff row.
   - Use LBZ frame 410 as the next validation target for repeated-row startup
     and launch-object behavior.
3. Continue the status/push/on-object cluster.
   - The first divergent frame has identical prior state, so bisect the single
     object/player frame into input, object control, object solid/touch, speed,
     collision, and position phases.
4. Test the roll/radius hypothesis.
   - Instrument y-radius, rolling, air, and center-y compensation around the
     earliest rolling/y-off-by-five frontiers before changing shared physics.
5. Diff exact `0x100` speed deltas in the owning movement routine.
   - Treat the exact delta as a routine-phase clue, not a sign to tune a
     generic constant.
6. Fix sidekick CPU roots before downstream Tails movement.
   - Prioritize routine/interact/status/off-screen marker correctness.
   - Recheck downstream Tails speed/position traces only after CPU fields move
     or clear.
7. Leave springs/launchers and individual frontiers for last, unless a root
   cause is proven to overlap a higher-leverage cluster.

## Per-Trace Workflow

For every fix:

1. Run the focused trace test and capture the first error from
   `target/trace-reports`.
2. Confirm frame `N - 1` is aligned, then debug only frame `N`.
3. Inspect ROM trace state, engine context, and relevant aux events.
4. Read the relevant disassembly routine completely.
5. State one root-cause hypothesis and test one variable.
6. Implement the smallest ROM-state fix.
7. Run the focused test and any cross-game tests touched by shared code.
8. Run a full `*TraceReplay` sweep when a cluster fix lands or before pushing
   to `develop`.
9. Update `docs/status/trace-frontier-log.md` whenever a frontier moves, clears,
   regresses, or a full sweep is used to choose the next target.
10. Commit with branch-policy trailers; do not use `--no-verify`.

## Completion Criteria

This remediation goal is complete only when all of the following are true:

- The consolidated plan/problem statement remains current with the latest
  committed trace frontier.
- Every trace frontier above is fixed, cleared, or reclassified with
  disassembly-backed evidence and permanent documentation.
- A full `*TraceReplay` sweep is green, or every remaining red trace is an
  explicit accepted release limitation with no hidden CI/test bypass.
- Focused tests for each touched subsystem pass.
- `docs/status/trace-frontier-log.md`, `docs/project/release-readiness-roadmap.md`, and
  discrepancy docs are updated where their claims changed.
- The workspace is clean except for intentionally separate user work.
