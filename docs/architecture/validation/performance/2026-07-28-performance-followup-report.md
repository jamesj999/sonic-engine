# Performance Follow-up Validation Report

## Status

The recommended candidate set (A, C, D, F, H, and I) has been assembled and
validated against the refreshed `develop` baseline at `fd3ca4525`. The full
suite has the same 77 failing/error test identities as that red baseline, all
focused candidate coverage passes, and the engine packages successfully.

## Coordination baseline

- Candidate base: `405630a3e3e00c7e5c18dd530515580f823168ce`
- Coordination commit: `c1934e629`
- Session: Temurin/OpenJDK 21.0.11, G1, Linux, live Wayland/X11 display
- Measurement lease:
  `/tmp/openggf-performance-measurement.lock`
- Measurement affinity: CPU 31, except real-GL capture where driver affinity
  was retained

The display-backed S2 CNZ update baseline used 2,000 warmup frames, 7,469
measured frames, and three iterations:

| Metric | Baseline |
|---|---:|
| frame p50 | 0.114 ms |
| frame p90 | 0.139 ms |
| frame p99 | 0.254 ms |
| maximum | 1.700 ms |
| audio | 0.0936 ms/frame |
| objects | 0.0176 ms/frame |
| physics | 0.0014 ms/frame |
| trajectory digest | `cf6995fe1dc1a47d` |

JFR attributed the largest sampled CPU shares to YM2612 synthesis. Sequencer
observable-event lookup accounted for only 1.06% of samples.

## Candidate summary

| ID | Candidate | Verdict | Branch / commit | Evidence |
|---|---|---|---|---|
| A | Object rewind dispatch cache | Accepted, independently green | `feature/ai-performance-rewind-dispatch` / `55db25c392badc340fa726610ae06aaba1f3ab3c` | 57,571,008 B → 0 B median per 10k mixed dispatches; 98.96% time reduction; repeated 895-test selection green |
| B | One-pass default object snapshot | Disproved; restored | no commit | Full-manager allocation regressed 0.038%; HotSpot already scalar-replaced the intermediate record |
| C | Observed auxiliary-event type index | Accepted, independently green | `feature/ai-performance-trace-event-types` / `378188431ccb82b11e5a4b8c6d44aff9162b0893` | 92.712 ms → 0.029 ms median for four schema queries across 1,569,911 events |
| D | Timing-authority source catalogue | Accepted, independently green | `feature/ai-performance-timing-guard-corpus` / `06e8b00e819634bb3c9f64f41e59becdbb411951` | Surefire class median 1.821 s → 1.495 s, 17.90% |
| E | Object-constructor source catalogue | Disproved; restored | no commit | Surefire class median 12.068 s → 11.683 s, 3.19%, below 10% gate |
| F | Trace presentation profile | Accepted audit, independently green | `feature/ai-performance-trace-presentation-profile` / `603b99437926dc53f0803fa840228638c366f076` | Narrow eager `TraceBinder.formatHex` allocation weight median 17.04%; separate design recommended |
| G | SMPS hybrid scan fusion | Disproved; restored | no commit | CNZ audio regressed 2.19%; frame p99 regressed 5.56% |
| H | S3K slot panel live state | Accepted, independently green | `feature/ai-performance-s3k-slot-panel` / `7aa7743bcfc971a12af22d87e909acf326d9f464` | Real runtime path 148,000,000 B → 0 B; 52.7% faster; 1,026 lossless frames identical |
| I | Background sampling scalarization | Accepted, independently green | `feature/ai-performance-background-sampling` / `0cce16bd93a3b949d5e560deeb030f76d9ffa533` | 72 B → 24 B/render; 19.9% faster; three real-GL capture pairs byte-identical |
| J | Fixed SMPS operator arrays | Disproved; restored | no commit | Allocation -55.56%, but controlled timing regressed 46.09% |

## Accepted candidates

### A — Object rewind dispatch cache

`ObjectRewindTypeSafety` now uses a class-loader-safe immutable
`ClassValue<DispatchRoute>` and resolves capture and restore routes
independently. Review found the first default allocation assertion could observe
a fixed 112-byte JIT/window artifact. The branch replaced it with deterministic
route-descriptor identity reuse and added inherited-intermediate-superclass
coverage.

| Metric | Baseline median | Final median |
|---|---:|---:|
| allocated bytes / 10,000 mixed dispatches | 57,571,008 | 0 |
| time / 10,000 mixed dispatches | 41,240,131 ns | 427,786 ns |

Three fresh focused invocations passed. Two broad invocations each ran 895
tests with zero failures/errors and two skips. Independent re-review reported
no remaining blockers.

### C — Observed auxiliary-event type index

`TraceData` records exact concrete auxiliary event classes during its existing
constructor traversal and freezes the set. Missing-schema reporting retains its
fixed output order and no longer scans every event list for every advertised
subtype.

| Metric | Baseline median | Final median |
|---|---:|---:|
| four schema queries | 92,711,994 ns | 29,431 ns |
| retained-heap observation | 836,662,984 B | 833,959,928 B |

The heap observations establish no material regression; their small difference
is within forced-GC noise and is not attributed as a saving.

The mandated broad focused run has one baseline-identical failure:
`parsesRecordedRingFloorCheckCounterPhase` expects 2 and receives null.
Independent review reproduced the exact failure on the base commit and found no
authority, ordering, immutability, scope, or benchmark blocker.

### D — Timing-authority source catalogue

The test class loads one normalized-root-keyed, sorted immutable production
source catalogue while retaining independent policy scans and exact violation
text.

| Window | Warmups | Seven reported samples (s) | Median |
|---|---|---|---:|
| baseline | 1.854, 1.795 | 1.782, 2.135, 1.910, 1.821, 1.821, 1.969, 1.771 | 1.821 |
| final | 1.610, 1.505 | 1.546, 1.493, 1.495, 1.493, 1.479, 1.529, 1.593 | 1.495 |

The 17.90% improvement clears the 10% gate. All 17 tests pass. Independent
review found no blocking issue.

### I — Private background sampling scalarization

The live background render flow now carries X and aligned-Y/source-Y scalars
directly instead of constructing two private sampling records. Command-owned
anchors, ring base/generation, deferred lifetime, and callback-facing contexts
are unchanged.

| Metric | Baseline median | Final median |
|---|---:|---:|
| allocated bytes / render | 72 | 24 |
| time / 10,000 render batches | 6,184,612 ns | 4,954,344 ns |

The focused selection ran 35 tests with zero failures/errors. ROM-backed,
320×224 real-GL capture pairs were byte-identical:

- stationary:
  `f121ef2fbe34b10fe1743e939bc653443db094c7f4f72e3ef8a3e857bd654925`
- positive scroll:
  `49494337d40dc1049022844d6adb67e1e45dbeca45521d3c9ed2856b163665f5`
- negative Y:
  `d25673d06a394badbd3cc6f9a994fd1d23ecf07e3aecc05711681f852b4bcbf1`

Independent review found no issues.

### H — S3K slot panel live-state path

The optimized path resolves fallback, spinning reel words/offsets, and
resolved-idle faces directly from `S3kSlotStageState` into the panel animator.
The immutable `S3kSlotMachineDisplayState` compatibility API remains available.
Atlas batching, 48 pattern update ids, reel order, and face-transition behavior
are unchanged.

Independent review initially rejected a compatibility-overload-only allocation
probe. The amended test calls `S3kSlotBonusStageRuntime.syncSlotMachinePanel`
through the real stage-state path for all three resolution branches and compares
scalar caches with immutable snapshots.

| Metric / 250,000 live three-scenario operations | Baseline median | Final median |
|---|---:|---:|
| allocated bytes | 148,000,000 | 0 |
| time | 15,863,695 ns | 7,509,328 ns |

The exact ROM-backed selection ran 43 tests with no failures/errors/skips.
Before/after lossless captures used `bonus_slots`, Knuckles, seed zero, 320×224,
FFV1 at 60 fps. Both reached the same pre-existing hardware-completion edge at
raw frame 1056. All 1,026 usable frames match; complete frame-MD5 manifests hash
to:

`4260eecbd63d5eb0f68b79c378e0b2fddc14236147ee5291a929fd4c3f224fbd`

Independent re-review found no remaining issue.

## Profiling-only result

### F — Trace presentation

Seven green ROM-backed S2 CNZ LevelSelect replays produced a median 7.958-second
test-body time. JFR execution-sample percentages are CPU context, not measured
wall time:

- full presentation union CPU-sample median: 14.16%;
- narrow `TraceBinder.formatHex` CPU-sample median: 3.52%
  (2.63–4.59%);
- narrow `TraceBinder.formatHex` allocation-weight median: 17.04%
  (16.07–19.27%).

The narrow eager-formatting path therefore clears the 10% allocation gate only.
The audit recommends a separate reviewed raw/lazy `FieldComparison` design.
Frame/event/engine diagnostic deferral, auxiliary indexing, report grouping,
and comparison-history changes are excluded from its first branch.

Independent audit review required and then verified runnable Surefire extraction
and JFR JSON aggregation, missing-stack handling, weighted allocation math,
overlap deduplication, median logic, and temporary probe definitions. Every
probe was removed; the post-removal trace remained green. The commit contains
only the audit artifact.

## Updated-`develop` compatibility validation

The main workspace was fetched and fast-forward pulled without changing
branches. At that validation point, `develop` and `origin/develop` were aligned
at `bd0f366ce532e90fa20957eb2ca537836be57a95`. Existing user modifications and
untracked files in the main workspace were left untouched.

The serialized baseline full suite completed all observable Surefire reports
and then hung after test execution. It was interrupted after repeated
no-output waits. Its fresh report set contains 1,706 suites, 13,306 tests,
26 failures, 14 errors, and 31 skips. This is a red, order-sensitive repository
baseline rather than a candidate regression baseline.

Each production candidate was then cherry-picked independently onto
`bd0f366ce` in a temporary validation worktree:

| Candidate | Validation result on updated `develop` | Repeated measurement |
|---|---|---|
| A — rewind dispatch | Pass: no attributable regression; 895-test focused selection green | 0 B median; 365,814 ns median, faster than the offered result |
| C — aux event index | Pass: no new or worsened full-suite failures; focused result retains only the baseline-identical ring-floor fixture failure | 28,650 ns median; no material retained-heap regression |
| H — slot panel | Pass: exact full-suite red set unchanged; 43/43 focused tests green | 0 B; confirmation medians 7,426,531 ns (-1.10%) and 7,524,471 ns (+0.20%) against the offered median |
| I — background sampling | Pass: focused 35-test selection green; full-suite-only differences reproduced on clean `bd0f366ce` | 24 B/render; 4,364,863 ns median, faster than the offered result |

The exact full-suite deltas were:

- A: 1,707 suites / 13,313 tests / 27 failures / 14 errors / 32 skips.
  New full-suite-only signatures were
  `TestCutsceneKnucklesAiz1Instance#exitHandoffReadsPreviousFrameRenderFlag`
  and
  `TestMGZSwingingPlatformObjectInstance#registryCreatesMgzSwingingPlatformInstance`;
  the baseline
  `TestBubblerObjectInstance#makerBeginsFirstProductionDispatchBeforeRenderVisibilityRefresh`
  failure disappeared.
- C: 1,706 / 13,306 / 25 failures / 14 errors / 31 skips. There were
  zero new or worsened signatures. The same Bubbler failure disappeared.
- H: 1,707 / 13,309 / 26 failures / 14 errors / 31 skips. The complete bad
  signature set was identical to the baseline; the three added tests passed.
- I: 1,706 / 13,281 / 28 failures / 14 errors / 33 skips. New
  full-suite-only signatures were
  `TestGroundSensor#upwardCeilingProbeAboveLevelTopUsesRomWrappedLookupWhenSolid`
  and
  `TestS3kIczCrushingColumnObject#nativeInitDispatchDoesNotRunSubtypeMovement`
  plus `#registryCreatesIczCrushingColumnInstance`; the Bubbler failure
  disappeared. The missing 29-test
  `TestEditorToggleIntegration` report accounts for the lower test total.

All A and I affected methods passed repeatedly on both the candidate and clean
`bd0f366ce` worktrees. They do not use the changed production classes. The
paired runs exposed existing reused-fork static/singleton order sensitivity.
I's full-suite JVM exited 134 before producing
`TestEditorToggleIntegration` XML; the exact 29-test class passed twice on both
candidate and base, while the dump showed `glGenTextures` being called without
a current context through legacy reused-fork render state. These are baseline
isolation defects, not candidate regressions.

H's decisive confirmation retained the candidate benchmark's compilation
contract by passing `-Xbatch` through Surefire:

```bash
flock -x /tmp/openggf-performance-measurement.lock \
  taskset -c 31 \
  mvn -Dmse=off -q \
  "-Dtest=com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotMachinePanelAllocation" \
  "-Dsurefire.argLine=-Xshare:off -javaagent:/home/farrell/.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar -Xmx1g -Xbatch" \
  test
```

Two earlier runs that accidentally omitted `-Xbatch` produced 19,386,096 ns
and 16,034,306 ns medians. They used non-identical flags and are superseded,
not part of the acceptance evidence.

D changes only its guard class. Its complete 17-test class passes on the
updated baseline. Four additional serialized whole-Maven invocations also
passed; their approximately 29-second process wall time includes Maven
lifecycle/plugin overhead and is not compared with the candidate's recorded
Surefire-class measurement.

F contains only the audit Markdown. The updated-baseline cherry-pick was clean,
and `TestArchitecturalReviewGuard` plus `TestBuildToolingGuard` ran 49/49
green.

### Concurrent `develop` movement after validation

While this report was being assembled, other work advanced `develop` beyond
the validated `bd0f366ce` snapshot: first to `2c10ca812`, then to
`1cabdabbf`. All six accepted commits cherry-picked together without conflict
onto `2c10ca812`, and production compilation passed there. Test compilation
could not start because that clean baseline independently failed in
`TestS3kSignpostInstance`: the test referenced absent
`S3kSignpostInstance.ResultsChildTimingAdjustment`,
`resultsChildTimingAdjustment`, `romVelocityAfterGravity`, and
`romBumpCheckAvailableAfterCooldownEntry` APIs. The portfolio worktree produced
the identical compiler errors.

At final evidence capture, the main workspace contained a separate staged,
in-progress signpost repair from concurrent work. It was not modified,
incorporated, or treated as a committed baseline. Consequently, the candidate
set is independently green and conflict-free through the recorded snapshots,
but current-head post-selection validation remains required after the signpost
repair reaches `develop`.

All eight disposable validation worktrees were verified to have no tracked
changes and removed. Their seven temporary local validation branches were also
deleted. The six offered candidate worktrees and branches remain available for
selection.

## Selected-set integration validation

The user selected the recommended set: production candidates A, C, D, H, and
I, plus profiling audit F and the coordination design, plan, and report. The
commits were cherry-picked onto
`feature/ai-performance-followup-integration`, aggregate `CHANGELOG.md` and
`README.md` release notes were added, and then the branch was reconciled with
the latest `develop`.

During validation, `develop` advanced to `fd3ca4525` with the restored macOS
application-icon implementation and tests. The integration branch merged that
updated baseline. The sole conflict was the top `CHANGELOG.md` section; the
resolution retained both the performance summary and the newer icon entry.
There were no production-code conflicts.

The refreshed full-suite comparison used the real display/native environment:

| Tree | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `develop` at `fd3ca4525` | 13,406 | 62 | 15 | 13 |
| selected integration at `960564e02` | 13,423 | 62 | 15 | 16 |

The integrated branch adds 17 tests and three intentionally skipped
performance benchmarks. XML comparison found all 77 baseline failure/error
identities unchanged. A separately run trace-parser report can remain beside
the full-suite reports because that class is excluded from the normal full
suite; it is not part of the 13,423-test result.

The combined focused selection for rewind dispatch, auxiliary schema indexing,
timing-authority guards, S3K slot-panel state, background rendering, build and
architecture guards, and the upstream window-icon loader exited successfully.
The excluded trace parser was run independently on both trees:

- baseline: 47 tests, one failure;
- integration: 49 tests, one failure;
- both fail only
  `parsesRecordedRingFloorCheckCounterPhase`, expecting 2 and receiving null.

The two candidate-added parser tests pass. Finally,
`mvn -Dmse=off -q -DskipTests package` succeeds on the assembled branch.

## Disproved candidates

### B — One-pass default object snapshot

The experiment reduced source-level `PerObjectRewindSnapshot` construction from
two records to one, and its 900-test focused suite passed. In a complete
`ObjectManager` with 64 compact-captured dynamic objects, however:

- allocation median: 1,901,440,000 B → 1,902,160,000 B (+0.038%);
- time median: 1,328,166,769 ns → 1,258,177,461 ns (-5.27%).

The allocation gate failed, consistent with HotSpot already scalar-replacing
the intermediate immutable record. The experiment was removed; no commit
exists.

### E — Object-constructor guard catalogue

The implementation made all 11 semantic tests green but improved focused class
time only 3.19%, from 12.068 s to 11.683 s, below the 10% gate. It was removed;
no commit exists.

### G — SMPS hybrid scan fusion

The fused scan passed its eight new semantic cases and a 45-test focused
selection. The exact CNZ digest remained stable on every run, but:

- audio median: 0.0959 → 0.0980 ms/frame (+2.19%);
- frame p99 median: 1.781 → 1.880 ms (+5.56%);
- scan microbenchmark median: 3,076,361 → 3,147,992 ns.

The experiment was removed; no commit exists.

### J — Fixed SMPS operator-order arrays

Hoisting two fixed arrays reduced transition-probe allocation from 2,880,000 B
to 1,280,000 B (55.56%), with exact PCM output. Under the controlled compilation
pair, median time regressed from 3,359,027 ns to 4,907,212 ns (46.09%). The
time gate failed, so the experiment was removed; no commit exists.

## Remaining work

Merge the validated integration branch into `develop`, verify the exact merged
commit against the recorded baseline, push, and clean the completed worktrees
and local branches.
