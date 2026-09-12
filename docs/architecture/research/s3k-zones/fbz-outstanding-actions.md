# Flying Battery Zone outstanding actions

Status updated during the 2026-09-12 route investigation. This branch contains a
large FBZ implementation uplift, but FBZ is not yet accepted as pixel-perfect.
The remaining work is intentionally recorded here rather than hidden behind a
green completion claim.

## Current native trace baseline

The 2026-09-12 replay on `f177bbdb7` reports **5,666 errors, 0 warnings**.
Its first error is frame **34**, `queue.s3k_kos_direct.busy`
(expected `true`, actual `false`). This is the current V5 complete-run baseline;
the July frame-18766/9-error result predates subsequent timing-contract changes
and must not be used as current release evidence.

The baseline used the `trace-replay-r7` profile, one alphabetical fork, and the
verified locked-on S3K ROM. See [trace frontier log](../../../status/trace-frontier-log.md)
for the exact command and worktree. Route-controller changes do not establish
trace parity; investigate the measured timing frontier separately.

## Native FBZ2 compatibility route

The native cold-Act 2 route remains red. The controller now recovers
from button egress using a fresh live elevator candidate, clears Obj28 layout
273 exactly once, and proves acquisition and exit of that car. It also waits
for the following descending car to clear its live spike wall and steers the
lower descent into the spike gap before an ordinary exit jump.

Charging occurs on terrain after the screw door opens. The retail landing
contract (`RideObject_SetRide` → `Player_TouchFloor`) unrolls Sonic on the car;
the oracle checks the resulting standing clearance instead of requiring rolling
through the entire crossing. These changes affect ordinary test inputs and
assertions, not production gameplay or the S1 squeeze assist.

The next measured native obstruction is the spike at `$2330,$0970` beside the
magnetic platform at `$2360,$0970`. The generic flat-underpass controller commits
while Sonic is still blocked at `$2315`; the assertion correctly rejects loss
of ordinary flat-ground control. The recorded route jumps onto and rides this
platform. Implement that distinct interaction with live geometry and ordinary
inputs, preserving the existing Obj74 safety checks.

Required complete-route evidence remains:

- every encountered Obj28 binds and clears exactly once, with real car entry/exit;
- every Obj74 encounter has its appropriate safe traversal evidence;
- the route reaches the subboss, boss, capsule, and Sandopolis handoff;
- native and S2 never consume the S1-only squeeze assist.

Diagnostic authority and geometry dumps remain useful while these routes are
red. Remove temporary print probes; do not remove the safety assertions.

## Compatibility matrix

The 13-row matrix remains pending and must not be relabelled PASS:

- five multi-sidekick team rows;
- five viewport widths: 320, 400, 512, 640, and 800;
- donation off, Sonic 1, and Sonic 2.

The early 640px and 800px deaths are closed by ordinary input recovery. Their
next measured failures are frame 25729 at `$0D92,$0BE5` near the floating
platform/ceiling, and frame 25751 at `$0BE5,$0A2C` near the lower spike,
respectively. The 400px and 512px rows and the two donated profiles still have
their earlier independent blockers. All five team rows advance past Obj28 to
the later `$2360` spike/platform interaction.

After the native route is green, run the focused donation, team, and viewport
methods, then the full `TestFbzCompatibilityMatrix`. The S1 row must prove that
Spindash remains absent, the squeeze assist is consumed exactly once, and the
car is acquired/exited. Native and S2 rows must prove that they never consume
the assist. Keep the existing consumption and car-acquisition/exit evidence
assertions in the route completion contract.

## September verification

The full ordinary baseline on `f177bbdb7` and combined candidate in
`.worktrees/ai-fbz-route-closure` both completed **20,217 tests, 14 failures,
0 errors, and 25 skips**. Comparison by test identity and full failure diagnostic
found exactly ten intended frontier advances and no other changed outcomes.
Four FBZ failures remain byte-for-byte unchanged. The 25 skips are unchanged;
they include opt-in measurements and unavailable reference/capture prerequisites,
not silently missing S1/S2/S3K runtime ROMs.

Separate fresh-JVM structural-guard runs completed **656 tests, all passing,
with no skips**, for both baseline and candidate. Candidate source SHA-256 is
`9216a2a35c125ef8912f28ec6ecc46df1d76b7dc8648430405b5791bdd2d9f8a` for
`TestFbzAct2TraversalPreboss.java`. Full logs, per-test snapshots, and the exact
comparison are archived under
the external task directory `$FBZ_EVIDENCE_ROOT` (`fbz-20260912`).

These results validate bounded controller progress. They do not close the
complete-route, canonical trace, or visual acceptance gates.

## Visual and final validation

[fbz-validation.md](fbz-validation.md) remains the authoritative honest record: the
immutable native/engine checkpoint pairs and comparison sidecars are incomplete,
so the visual gate is still FAIL. Do not commit ROM-derived screenshots under
`refs/`.

After trace and compatibility are green:

1. Capture the required BizHawk references and native engine frames.
2. Complete every named static and time-series comparison sidecar.
3. Run the focused FBZ suite, shared collision/movement/rewind regressions,
   policy guards, compatibility matrix, strict complete-run trace, and package.
4. Review discrepancies and rerun affected gates after each fix.

## Useful commands

Set `S1_ROM`, `S2_ROM`, and `S3K_ROM` to the absolute paths of the existing,
verified ROMs. Do not create aliases or links to match an example. Check skips
in the completed report.

```bash
mvn -Dmse=off -Dtest=TestFbzAct2RouteHeadless \
  "-Ds3k.rom.path=$S3K_ROM" test -B
mvn -Dmse=off -Dtest=TestFbzCompatibilityMatrix \
  "-Dsonic1.rom.path=$S1_ROM" "-Dsonic2.rom.path=$S2_ROM" \
  "-Ds3k.rom.path=$S3K_ROM" test -B
mvn -Dmse=off -Ptrace-replay-r7 -Dsurefire.forkCount=1 \
  -Dsurefire.runOrder=alphabetical -Dtest=TestS3kFbzCompleteRunTraceReplay \
  "-Ds3k.rom.path=$S3K_ROM" test -B
```
