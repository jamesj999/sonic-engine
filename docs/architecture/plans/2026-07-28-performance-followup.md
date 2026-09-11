# Performance Follow-up Implementation Plan

> Implement the reviewed design in isolated branches. No candidate enters
> `develop` until the user selects it after the evidence report.

**Goal:** Test every remaining bounded performance hypothesis, retain only
repeatable wins, and hand back independent commits with exact verification and
measurement evidence.

**Design:** `docs/architecture/designs/2026-07-28-performance-followup-design.md`

**Baseline:** `405630a3e3e00c7e5c18dd530515580f823168ce`

## Coordination rules

- Create every branch from the baseline above in `.worktrees/`; never switch
  the main workspace branch.
- Candidate branches do not depend on the coordination design/plan commit.
- Workers modify only their exact owned files. Existing tests named for
  verification are read-only unless explicitly owned below.
- Workers do not edit `CHANGELOG.md`, `README.md`, architecture documents, or
  shared reports. Production commits use:

  `Changelog: n/a: independently cherry-pickable performance candidate; aggregate release note deferred until selected integration`

- Never bypass hooks. Include every required commit trailer.
- All Maven, Java benchmark, engine, and GL commands acquire:

  `flock -x /tmp/openggf-performance-measurement.lock`

- Measurement commands additionally use `taskset -c 31`. Live-display capture
  may omit `taskset` when the graphics driver requires worker affinity, but
  still holds the same `flock` lease. Baseline and after-change samples for one
  candidate run in the same lease; inspect active Maven/Java/engine processes
  before and after.
- Wall time: two warmups, seven reported samples. Allocation: 10,000 warmups
  and seven measured batches. Record all samples and medians.
- Timing/allocation thresholds are acceptance gates, not default-suite timing
  assertions. Default tests assert semantics, ownership, exact output, and
  deterministic mechanism counts.
- If a threshold is missed, remove the experiment, leave no code commit, and
  report the disproved hypothesis and samples.
- Each accepted branch ends clean with one cherry-pickable commit and reports
  the exact SHA, diff, tests, measurements, and remaining risks.

## Worktree and batch map

| Candidate | Branch | Worktree | Batch |
|---|---|---|---:|
| A | `feature/ai-performance-rewind-dispatch` | `.worktrees/performance-rewind-dispatch` | 1 |
| D | `feature/ai-performance-timing-guard-corpus` | `.worktrees/performance-timing-guard-corpus` | 1 |
| E | `feature/ai-performance-object-guard-corpus` | `.worktrees/performance-object-guard-corpus` | 1 |
| B | `feature/ai-performance-rewind-single-snapshot` | `.worktrees/performance-rewind-single-snapshot` | 2 |
| C | `feature/ai-performance-trace-event-types` | `.worktrees/performance-trace-event-types` | 2 |
| G | `feature/ai-performance-smps-scan-fusion` | `.worktrees/performance-smps-scan-fusion` | 2 |
| H | `feature/ai-performance-s3k-slot-panel` | `.worktrees/performance-s3k-slot-panel` | 3 |
| I | `feature/ai-performance-background-sampling` | `.worktrees/performance-background-sampling` | 3 |
| J | `feature/ai-performance-smps-event-arrays` | `.worktrees/performance-smps-event-arrays` | 3 |
| F | `feature/ai-performance-trace-presentation-profile` | `.worktrees/performance-trace-presentation-profile` | 4 |

Batching limits active implementation workers to three. Work in a later batch
does not start until all earlier workers have reported clean handoff state, but
independent code review can overlap the next batch's implementation.

## Task A: Cache object rewind dispatch routes

**Own:**

- Modify `src/main/java/com/openggf/level/objects/ObjectRewindTypeSafety.java`
- Create
  `src/test/java/com/openggf/level/objects/TestObjectRewindTypeSafetyDispatchPerformance.java`

1. Add failing semantic tests for default, legacy, context-aware, and mixed
   capture/restore routes. Add a deterministic reflective-resolution count
   oracle and an opt-in 10,000-object allocation/time measurement.
2. Run the focused test on the baseline to prove the uncached repeated
   resolution behavior and record seven measured batches under the lease.
3. Implement a typed per-concrete-class route cache, preferably `ClassValue`.
   Cache method-route metadata only.
4. Run the focused test, then:

   ```bash
   mvn -Dmse=off \
     "-Dtest=TestObjectRewindTypeSafetyDispatchPerformance,TestObjectManagerRewindSnapshot,TestEveryObjectRewindRoundTrip,TestRewindTorture" test
   ```

5. Repeat the seven measurement batches. Accept only if allocation median
   improves by at least 5%, every batch removes the expected reflection work,
   and time does not regress by more than 2%.
6. Review the diff for class-loader safety and distinct capture/restore routes,
   then commit as `perf(rewind): cache object rewind dispatch routes`.

## Task D: Share the timing-authority source corpus

**Own:**

- Modify
  `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`

1. Record two warmups and seven Surefire `testsuite time` values:

   ```bash
   mvn -Dmse=off -q \
     "-Dtest=com.openggf.trace.timing.TestHardwareTimingAuthorityGuard" test
   ```

2. Add failing in-class tests around an injected loader: the same root
   walks/reads once, a different root is isolated, and crafted violations keep
   exact order/text.
3. Load one sorted immutable `SourceFile` catalogue containing relative path,
   package, filename, and content. Reuse data, not policy/matcher results.
4. Preserve existing individual read-error behavior and synthetic string tests.
5. Re-run seven focused samples. Accept only if the class median improves at
   least 10% from its branch baseline and all 14 existing tests plus new tests
   pass.
6. Commit as `perf(test): share timing authority source corpus`.

## Task E: Share the object-constructor guard source corpus

**Own:**

- Modify
  `src/test/java/com/openggf/tests/TestNoServicesInObjectConstructors.java`

1. Record two warmups and seven focused Surefire class times:

   ```bash
   mvn -Dmse=off -q \
     "-Dtest=com.openggf.tests.TestNoServicesInObjectConstructors" test
   ```

2. Add failing in-class tests for one walk/read, exact object-package
   partitioning, non-object all-source call sites, current `IOException`
   tolerance, line numbers, and exact detector output.
3. Build one sorted immutable production source catalogue and derive the
   object-package view from it. Preserve every test's policy logic.
4. Repeat the seven focused samples. Accept only with at least a 10% class
   median improvement and exact green policy results.
5. Commit as `perf(test): reuse object constructor guard corpus`.

## Task B: Construct default object snapshots once

**Own:**

- Modify
  `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Create
  `src/test/java/com/openggf/level/objects/TestDefaultObjectRewindCapturePerformance.java`

1. Add a compact-captured default object fixture proving mutation isolation and
   exact restore. Instrument final snapshot construction count and add an
   opt-in full `ObjectManager` 10,000-capture allocation/time measurement.
2. Record baseline construction count and seven measured batches.
3. Capture the optional compact sidecar first and construct the final
   `PerObjectRewindSnapshot` once. Do not change snapshot/blob APIs or subclass
   override routing.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=TestDefaultObjectRewindCapturePerformance,TestRewindCaptureScratchReuse,TestObjectManagerRewindSnapshot,TestEveryObjectRewindRoundTrip,TestRewindInPlaceObjectRestore,TestRewindTorture,TestRewindTraceSeekDeterminism" test
   ```

5. Accept only if all seven batches remove the second record, allocation median
   improves by at least 5%, and time does not regress by more than 2%.
6. Commit as `perf(rewind): construct default object snapshots once`.

## Task C: Index observed auxiliary-event types

**Own:**

- Modify `src/main/java/com/openggf/trace/TraceData.java`
- Modify `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
- Create
  `src/test/java/com/openggf/tests/trace/TestTraceDataAuxSchemaPerformance.java`

1. Add exact parsing tests for duplicate-frame/multi-type events, every current
   missing-schema ordering, repeated calls, and one constructor traversal.
2. Add an opt-in post-load query benchmark using the largest available fixture.
   Record retained heap and seven missing-schema query batches on baseline.
3. During the existing constructor index pass, collect exact concrete event
   classes into an immutable set. Replace `hasEventOfType` scanning with set
   membership. Do not change event storage, resources, or report APIs.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=com.openggf.tests.trace.TestTraceDataParsing,com.openggf.tests.trace.TestTraceDataAuxSchemaPerformance,com.openggf.trace.timing.TestHardwareTimingAuthorityGuard" test
   ```

5. Accept only if the seven query batches improve median wall time by at least
   5% and 10 ms, retained heap does not regress, and output remains exact.
6. Commit as `perf(trace): index observed auxiliary event types`.

## Task G: Fuse SMPS hybrid admission and boundary scans

**Own:**

- Modify `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Create
  `src/test/java/com/openggf/audio/driver/TestSmpsHybridScanFusionPerformance.java`

1. Add failing cases for multiplier fallback, tempo boundary, observable-event
   boundary, `-1`, `MIN_BATCH_SAMPLES`, removal order, and scan count.
2. Record the baseline focused scan microbenchmark and the live-display CNZ
   benchmark. Build with `mvn -Dmse=off -DskipTests package` first.
3. Fuse fallback admission and safe-window calculation in one non-allocating
   driver-local scan. Keep the required advancement pass and re-evaluate after
   every batch/single-sample step.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=TestSmpsHybridScanFusionPerformance,AudioRegressionTest,TestSmpsFadeHybridParity,TestSmpsSequencerTempoMath,TestSmpsFadeAudioThroughput" test
   ```

5. Repeat CNZ with the exact baseline command. Require digest
   `cf6995fe1dc1a47d`, byte-identical PCM, at least 3% audio-section median
   improvement, and no frame-p99 regression over 2%. Discard if the host
   threshold is missed even when the microbenchmark wins.
6. Commit as `perf(audio): fuse hybrid sequencer boundary scans`.

## Task H: Remove S3K slot panel live-state churn

**Own:**

- Modify
  `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
- Modify
  `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify
  `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachineDisplayState.java`
- Modify
  `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachinePanelAnimator.java`
- Create
  `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotMachinePanelAllocation.java`

1. Add allocation and pixel checksum tests for unchanged state, spinning state,
   face transition, and offsets 0, 1, and 31. Record seven allocation batches.
2. Capture baseline live-display images for the same deterministic states and
   record window/scale/effect flags, seed, and camera.
3. Add a scalar/frame-owned runtime-to-panel path. Preserve the immutable public
   state snapshot and exact atlas batch/update order. Remove stream and
   temporary three-element arrays only from the live path.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotMachinePanelAllocation,com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotMachinePanelAnimator,com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotBonusStageRuntime,com.openggf.game.sonic3k.TestS3kSlotBonusStageRuntime,com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotRenderBuffers,com.openggf.game.sonic3k.TestS3kSlotsPaletteCycling,com.openggf.tests.TestS3kBonusStageHeadlessBoot" \
     "-Ds3k.rom.path=s3k.gen" test
   ```

5. Repeat allocation and live-display captures. Accept only if every batch
   meets the common allocation/time thresholds and pixel/capture output matches.
6. Do not reuse the 48 panel `Pattern` instances in this branch.
7. Commit as `perf(s3k): remove slot panel state churn`.

## Task I: Scalarize private background sampling

**Own:**

- Modify `src/main/java/com/openggf/level/LevelRenderer.java`
- Create
  `src/test/java/com/openggf/level/TestLevelRendererBackgroundSamplingPerformance.java`

1. Add a post-warmup render allocation probe, exact stationary/positive-scroll/
   negative-Y output cases, and a deferred-command mutation test.
2. Record seven baseline allocation/time batches and live-display captures.
3. Replace only the private `BackgroundTilemapSampling` record flow with
   scalars. Preserve command-owned anchors and ring-generation snapshots. Do
   not pool callback-facing render contexts.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=TestLevelRendererBackgroundSamplingPerformance,TestPersistentBgNametable,TestLevelRendererBackgroundViewport" test
   ```

5. Accept only when all batches remove both records, meet allocation/time
   thresholds, deferred state remains immutable, and captures match.
6. Commit as `perf(render): scalarize background sampling`.

## Task J: Hoist fixed SMPS operator-order arrays

**Own:**

- Modify `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Create
  `src/test/java/com/openggf/audio/smps/TestSmpsSequencerEventArrayPerformance.java`

1. Add a 10,000-transition allocation probe around total-level refresh,
   retrigger, fade, and instrument refresh. Add exact PCM cases.
2. Record seven baseline batches.
3. Replace the two per-call fixed arrays with private static final order arrays.
   Prove no mutable reference escapes.
4. Run:

   ```bash
   mvn -Dmse=off \
     "-Dtest=TestSmpsSequencerEventArrayPerformance,AudioRegressionTest,TestSmpsSequencerTempoMath,TestSmpsFadeHybridParity" test
   ```

5. Accept only if all batches meet allocation/time thresholds and PCM remains
   byte-identical.
6. Commit as `perf(audio): reuse fixed operator order arrays`.

## Task F: Profile trace presentation

**Own:**

- Create
  `docs/architecture/audits/performance/2026-07-28-trace-presentation-profile.md`
- Temporary probes are uncommitted and removed before handoff.

1. Verify the chosen CNZ trace replay is green:

   ```bash
   mvn -Dmse=off -Ptrace-replay \
     "-Dtest=com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay" \
     "-Ds2.rom.path=s2.gen" test
   ```

2. Run seven replay samples with the same frames and a Surefire test-JVM JFR
   recording. Override `surefire.argLine` only for this profiling command and
   retain `-Xshare:off -Xmx1g`; the selected test must not require Mockito.
3. Use temporary formatter counters to attribute calls/bytes to `TraceBinder`,
   `FieldComparison`, `TraceFrame.formatDiagnostics`, event summaries, and
   engine diagnostics. Remove the counters afterward.
4. Write exact commands, JFR views, all samples, allocation/CPU shares, and the
   verdict to the owned audit.
5. If trace presentation is below 5% wall time and 10% allocation, record it as
   disproved. If either threshold is met, recommend a new narrow raw/lazy field
   design; do not implement it in this branch.
6. Commit the audit only as
   `docs(perf): profile trace presentation overhead`.

## Independent review loop

For every accepted branch:

1. Assign a reviewer who did not implement it.
2. Review against the design, exact ownership, deterministic behavior,
   benchmark validity, test quality, and `git diff`.
3. Fix every valid finding in the candidate worktree and rerun affected tests
   under the lease.
4. Repeat until the reviewer reports no blocking issues.
5. Record the clean branch head in
   `docs/architecture/validation/performance/2026-07-28-performance-followup-report.md`
   on the coordination branch.

Disproved candidates receive evidence in the same report but no empty branch
commit.

## Portfolio verification and handoff

1. Fetch and fast-forward `develop` without touching uncommitted user files.
2. Record the updated `develop` full-suite baseline:

   ```bash
   flock -x /tmp/openggf-performance-measurement.lock \
     mvn -Dmse=off test
   ```

3. Do not compare an old-baseline candidate worktree directly with the updated
   baseline. For each accepted commit, create a temporary validation branch and
   worktree from updated `develop`, cherry-pick that one candidate commit, and
   run the full suite, focused tests, and final measurement there. Keep the
   offered candidate branch unchanged and independently cherry-pickable. Remove
   the temporary validation worktree/branch after recording results; do not
   merge or switch the main workspace.
4. Compare the temporary validation result with the updated-develop baseline.
   Any new failure/error or worsened baseline failure attributable to the
   candidate blocks handoff.
5. Confirm every branch contains only its owned commit and is either directly
   cherry-pickable or documents any unavoidable dependency.
6. Complete the validation report with branch, SHA, files, baseline/after
   samples, exact failures, review verdict, and recommendation.
7. Present the report to the user and pause for candidate selection.
8. Only after selection: reconcile upstream changes, merge/cherry-pick chosen
   commits into the main-workspace `develop`, make the aggregate changelog and
   README release-log update required by policy, run the post-merge full suite
   against the recorded baseline, push, and clean fully merged worktrees/local
   branches.
