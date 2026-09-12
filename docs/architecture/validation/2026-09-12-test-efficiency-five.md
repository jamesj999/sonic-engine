# Five-target test efficiency pass

Pinned base: `65b5553413cb7f9bc134f8d171cd398cc882f812` on develop, after fetching
and fast-forwarding the main workspace. Candidate: `feature/ai-test-efficiency-five`
in `.worktrees/ai-test-efficiency-five`. See the
[work plan](../plans/2026-09-12-test-efficiency-five.md).

## Measurement and scope

Serial Java 21 Surefire runs in the same worktree, `-Dmse=off -Pguards`, explicit
`-Dtest` selection, verified absolute S1/S2/S3K ROM properties, separate reports
and native temporary directories. Focused runs use `run_logged` with a 600-second
wall deadline and 300-second idle deadline. Class times exclude Maven compilation.
Single before/after pairs are observations, not statistical confidence intervals.

The unchanged-base selection comprised `TestS3kOracleRequestSidecarWiring`,
`TestNoServicesInObjectConstructors`, `TestTraceFixtureMovieAlignmentGuard`,
`TestFbzCompatibilityMatrix`, `TestFbzAct2RouteHeadless`, `TestMasterEndToEnd`, and
`TestVerifiedRoomEndToEnd`: 52 tests, 38 passes, 14 FBZ failures, no errors or skips.

## Coverage and retired execution mapping

- **S3K sidecar:** class-scoped immutable plain/resolved reference lists and one
  full honest capture, released after the class. All 13 assertion methods remain.
  Each consumer retains its exact prefix length. Corrupt request and perturbed
  frame captures still run independently; corrupted DAC writes use a separate
  copied list. A temporary test compared all tick fields from separate old
  captures of 129, 1571, 1653, 1690 and 2358 services with the corresponding full
  capture prefixes. All five comparisons passed; the expensive proof was removed.
- **Constructor guard:** compute each class's service-method closure and each
  ancestor's constructor calls once per source snapshot, then retain the original
  ancestor/subclass cross-check. Reuse the same method extraction for service and
  raw-registration closures; compile each call pattern once and reject a body
  early only when the literally quoted name is absent. All six source rules and
  all object packages remain. Temporary old/new comparisons matched the complete
  context-required class set and both method closures for every scanned object
  class. Small retained cases cover direct/transitive calls, virtual constructor
  dispatch, qualified-call exclusions, registration sensitivity and fresh source
  snapshots with the same class name. Necessary literal checks also avoid running
  construction/registration regexes on lines that cannot match; a retained
  before/after-injection case checks pending-variable overwrite behavior.
- **FBZ:** 13 matrix full traversals become 11 effective configurations. Native
  320/Sonic+Tails viewport coverage and the separate native standalone route map
  to the Sonic+Tails team row. Concrete Sonic/Tails types, P1 ownership, sidekick
  codes/count and native mode are retained. Viewport completion assertions and
  the complete independent fresh starpost-6 viewport slice are retained. The
  donor-off full traversal maps to the solo team row, which now uses the original
  donor-off setup and checks provider inactivity, width, module rule identity and
  both S1-assist exclusions. All 13 synchronous preflights, four additional
  viewport widths, two active donors, all five teams, authority-isolation fixtures,
  team slices and the independent starpost-5 magnetic test remain. Optional probes
  run once per effective configuration. Live route evidence is never cached across
  test methods or reused after another fixture replaces its session.
- **Trace alignment:** parse only the named input column into a growable primitive
  array and retain read-only arrays for alignment and manifest row counts within
  the class. Movie caching now also spans the class. Every row, published offset,
  neighboring offset, auxiliary sample, manifest window and coverage floor remains.
  A temporary comparison matched old/new arrays for every committed physics
  payload directory. Retained boundary tests cover empty/missing files, missing
  input headers, blank rows, trailing fields, short rows, malformed hex, signs,
  whitespace and array growth. Exception categories remain those of the old
  `split(..., -1)` implementation.
- **Network:** package-private master startup accepts a clock; public startup still
  supplies wall time. Both real relay and verified-room tests observe RoundStart,
  advance the countdown and wait behind the production room tick on its owning
  executor. Real sockets, player catalogue, standings, ghosts, teleport sanctions,
  trust rejection, HTTP recording upload, worker lease and signed verdict remain.
  Membership observations replace disconnect sleeps. Catalogue publication uses
  one production 20-tick cycle with broker/room/broker barriers before one real
  browser query. An initial polling implementation failed because the broker drops
  requests inside its two-second rate limit; it was corrected rather than hiding
  the failure with a longer timeout. This drives periodic publication behavior,
  not elapsed scheduler cadence; production scheduling is unchanged.

The independent audit found no missing transferred assertions. Its request-timeout
finding led to inspection of the catalogue rate limit and the event/barrier design.
Existing full-route FBZ failures still limit execution of downstream completion
assertions; consolidation does not constitute a gameplay fix.

## Before/after timings

| Target | Base seconds | Candidate seconds |
|---|---:|---:|
| S3K sidecar | 24.821 | 6.261 |
| Constructor guard | 16.957 | 5.111 |
| Trace/movie alignment | 9.694 | 9.313 |
| FBZ matrix | 63.447 | 54.999 |
| FBZ standalone | 6.635 | 0.447 |
| Master relay | 6.290 | 1.561 |
| Verified room | 3.246 | 0.133 |

The candidate matrix timing includes a temporary direct call of the moved native
viewport boss slice (0.845 seconds), which passed all of its assertions. That proof
was then removed. The 11 retained full-route failures matched the complete baseline
failure messages exactly, mapping shifted width/donor parameter indices by value.
All three removed route failures also exactly matched their surviving canonical
configuration's baseline failure. Completion-only evidence beyond the existing
full-route frontier remains unexecuted; the direct slice proof does not claim it.

Trace input alignment plus manifest validation fell from 3.143 to 1.373 seconds.
The unrelated auxiliary-stream scan varied from 6.545 to 7.935 seconds and still
dominates that class; the whole-class pair therefore shows only a small gain.
No auxiliary payload, sampled pass, or alignment window was removed.

The refined constructor selection completed all nine tests with no failures, errors
or skips. The other final focused checks passed apart from the 11 matched FBZ
failures. Parser boundary checks added two passing tests (0.006 seconds).

## Required delivery validation

Preflight passed Java 21, Lua 5.4 and PowerShell. Selection against the pinned base
is the full ordinary suite (2,515 candidate classes) plus every structural guard,
because the changes include shared test infrastructure. Domain-mandated S3K
bootstrap/loading checks remain in that ordinary selection. The stopping rule is
40 minutes total Maven time or 10 minutes without output, with no automatic retry.
The preceding comparable full pass took about 15.5 minutes; allow tens of minutes.
The source candidate is frozen before this one required broad run.

Candidate `4ffc035dd` completed the required command:

```sh
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base 65b5553413cb7f9bc134f8d171cd398cc882f812 --run
```

Run `20260912T183708Z-0ebce3b3`: ordinary 20,421 tests, 20,384 passes, 12 failures,
zero errors, 25 skips, 685.33 seconds; guards 661 passes across 82 reports, no
failures/errors/skips, 175.28 seconds. Total lane time: 860.61 seconds (14m21s).
The inventory count is candidate source classes; nested/dynamic report shapes
produce 2,534 ordinary XML reports. These are different units, not missing coverage.

All 11 FBZ failure messages in the completed ordinary lane exactly matched the
focused candidate and pinned baseline. The remaining failure is
`TestObjectPlacementEncoding.commonParserPreservesDescendingFullXOrderInsideOnePlacementColumn`:
expected `[448, 384]`, actual `[384, 448]`. A bounded one-test check on unchanged
main-workspace `65b555341` reproduced the exact message; its temporary diagnostics
were consumed and deleted. None of these baseline failures was changed to pass.

The 25 ordinary skips comprise seven missing generated audio WAV references,
five explicit S2 ROM/BK2 measurement requirements, one missing local independent
S1 audio reference, ten opt-in measurements/captures/soak cases, unavailable
surfaceless EGL, and the existing CPZ spin-tube capture/release assumption.
Present root ROMs were identity-verified; skips are not passing coverage.

Largest completed ordinary classes: FBZ matrix 95.476s, complete-run audio
comparator 83.413s, rewind torture 60.610s, FM bit-exact scripts 43.635s,
FBZ Act-1 routes 25.846s. Guard leaders observed before diagnostic compaction:
clock terminology 40.487s, build tooling 30.920s, active-payload authority 20.995s.
The timing-only monitor captured all ordinary reports and 81 of 82 guard reports;
this is not a complete guard timing inventory. Target times in the complete run:
S3K sidecar 6.045s, constructor guard 6.586s, trace/movie guard 8.199s, native
standalone slice 0.209s, master relay 1.201s, verified room 0.137s. Whole-suite
FBZ timing differs substantially from the focused pair; the focused 53.265-second
saving is not a claim of an equivalent measured whole-suite improvement.

While validation ran, origin/develop advanced to `770ce255c`, adding SMPS header
normalization, ROM ring mappings, graphics/timing refactors, test retirements and
a delivery-wide validation budget. Main develop fast-forwarded without switching
branches. Merging upstream into the task branch was conflict-free, including the
combined release-note edits; develop then fast-forwarded to integrated `50631c5a0`.

The post-integration focused command was `mvn -Dmse=off -Pguards -Dtest=<selection>
test -B` with the same verified ROM properties and isolated main-workspace reports.
Selection retained all eight final target/helper classes plus
`TestSmpsHeaderConstruction`, `TestRomRingMappings`, `TestS3kAiz1SkipHeadless`,
`TestSonic3kLevelLoading` (both matching classes), `TestSonic3kBootstrapResolver`,
and `TestSonic3kDecodingUtils`. On `50631c5a0`, it completed 117 tests: 106 passed,
11 FBZ failures, no errors/skips, 129.074 seconds including compilation. All 11
full failure messages exactly matched the earlier candidate/base. New upstream
SMPS and ring-mapping checks and all mandated S3K checks passed. No second full
suite was run after that upstream merge; combined-tree evidence is this focused
selection, not a claim that all upstream changes received full validation here.

The newly introduced shared task receipt adopted this same delivery's completed
broad attempt and historical time, preserving rather than resetting its allowance.
Accounting before the final test-only reconciliation totaled 1,399.112 seconds (23m19s) against the 40-minute ceiling: one
completed broad attempt, prior focused Maven totals, 177-second original baseline,
a conservative 30-second allowance for the consumed placement baseline, and the
129.074-second integrated check. Broad diagnostics were acknowledged and removed;
focused diagnostics were consumed after exact-message comparison. Only the
concise evidence and timing ledger is durable. Main-workspace dirty disassemblies
and `raiscan-0.6-thoughts.md` were preserved.

The first push was held by the policy hook because origin had advanced again.
After fetching, upstream `a69ad61a2` and `af95ddd5a` changed only ten test classes
(removing copied arithmetic and simulated checks). They merged without conflicts
at `7f774aff1`; none of this pass's target or production files changed. A focused
`mvn -Dmse=off -Dtest=TestLookScrollDelay,TestSonic3kInvincibilityStars,BossStateContextTest,SwScrlMczTest,SwScrlOozTest,TestPlayableSpriteMovement,TestDEZDeathEggRobot,TestDEZMechaSonic,TestHCZWaterSkimExitOnCurve,TestWFZBoss test -B`
with verified ROM properties passed all 302 tests in 19.067 seconds including
compilation, with no skips. Final accounted validation cost is 1,418.179 seconds
(23m38s), still one broad attempt. Unchanged targets were not rerun again.
