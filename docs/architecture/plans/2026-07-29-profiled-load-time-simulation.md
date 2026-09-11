# Profiled Load-Time Simulation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a normal-play load-time mode that can delay ROM-backed hardware jobs by deterministic profiled service costs without changing queue order, trace-replay authority, or the existing default behavior.

**Architecture:** A session-owned `LoadTimeProfile` assigns an immutable admission decision when a hardware job becomes the physical FIFO head. `HardwareTimingService` continues production preparation concurrently and releases live readiness only when both preparation and the profile countdown are complete. Configuration selects the profile at session composition; trace replay continues to replace only the existing readiness admission policy and never consults profile data.

**Tech Stack:** Java 21, JUnit Jupiter, Maven, YAML configuration, classpath JSON manifests, existing rewind and hardware-timing frameworks.

## Global Constraints

- The default and `NONE` modes preserve current normal-play timing exactly.
- `FAST` warns once per session and behaves as `NONE`.
- `REALISTIC` warns once per session and behaves as `PROFILED`.
- Unknown configuration values fail configuration parsing.
- Trace replay never consumes profiled values and retains the current schema-specific admission policy.
- A job becomes profile-active only when it reaches the physical FIFO head for its kind; countdown and production preparation then advance concurrently.
- Readiness requires both prepared payload and exhausted countdown.
- A KosM parent coordinator receives an explicit zero-cost service-model decision; child module jobs receive their own decisions.
- Manifest lookup uses `kind + submission fingerprint + service-model version`; completion boundaries and ordinals are provenance only.
- Rewind restores the assigned cost, remaining countdown, activation state, and decision source without another lookup or warning.
- Runtime assets remain ROM-only; profile metadata contains timing integers and provenance, never gameplay asset bytes.
- Trace comparison data cannot hydrate gameplay state or create work.
- New native measurement streams and captured data are a separately reviewed and approved artifact and are not part of the executable foundation tasks below.

---

### Task 1: Profile and Configuration Contract

**Files:**
- Create: `src/main/java/com/openggf/game/timing/LoadTimeSimulationMode.java`
- Create: `src/main/java/com/openggf/game/timing/LoadTimeProfileFactory.java`
- Create: `src/main/java/com/openggf/game/timing/LoadTimeDecisionSource.java`
- Create: `src/main/java/com/openggf/game/timing/LoadTimeDecision.java`
- Create: `src/main/java/com/openggf/game/timing/LoadTimeProfile.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/resources/config.yaml`
- Modify: `CONFIGURATION.md`
- Test: `src/test/java/com/openggf/game/timing/TestLoadTimeProfileFactory.java`
- Test: `src/test/java/com/openggf/configuration/TestLoadTimeSimulationConfiguration.java`

**Interfaces:**
- Produces: `LoadTimeSimulationMode { NONE, PROFILED, FAST, REALISTIC }`.
- Produces: `LoadTimeProfileFactory.resolve(String, LoadTimeProfile, Consumer<String>)`.
- Produces: `LoadTimeProfile.assign(HardwareWorkSubmission, HardwareWorkHandle)`.
- Produces: `LoadTimeDecision(int serviceFrames, Set<HardwareServiceBoundary>
  eligibleBoundaries, LoadTimeDecisionSource source, String serviceModel)`.

- [ ] **Step 1: Write failing configuration and fallback tests**

```java
assertEquals("NONE", config.getString(SonicConfiguration.LOAD_TIME_SIMULATION));
assertEquals(LoadTimeSimulationMode.PROFILED,
        LoadTimeSimulationMode.parse("profiled"));
assertThrows(IllegalArgumentException.class,
        () -> LoadTimeSimulationMode.parse("turbo"));

List<String> warnings = new ArrayList<>();
assertSame(LoadTimeProfile.IMMEDIATE,
        LoadTimeProfileFactory.resolve("FAST", profiled, warnings::add));
assertSame(profiled,
        LoadTimeProfileFactory.resolve("REALISTIC", profiled, warnings::add));
assertEquals(2, warnings.size());

LoadTimeDecision immediate = LoadTimeProfile.IMMEDIATE.assign(submission, handle);
assertEquals(0, immediate.serviceFrames());
assertTrue(immediate.eligibleBoundaries().isEmpty());
```

- [ ] **Step 2: Run the tests and confirm the new enum/key are absent**

Run: `mvn -Dtest=TestLoadTimeSimulationConfiguration,TestLoadTimeProfileFactory test`

Expected: compilation fails for the missing types and configuration key.

- [ ] **Step 3: Add the enum, catalog entry, bundled/default value, and resolver**

```java
public enum LoadTimeSimulationMode {
    NONE, PROFILED, FAST, REALISTIC;

    public static LoadTimeSimulationMode parse(String value) {
        try {
            return valueOf(Objects.requireNonNull(value, "value")
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "loadTimeSimulation must be NONE, PROFILED, FAST, or REALISTIC", ex);
        }
    }
}
```

Add the immutable profile contract in this task:

```java
public interface LoadTimeProfile {
    LoadTimeProfile IMMEDIATE = (submission, handle) ->
            new LoadTimeDecision(0, Set.of(), LoadTimeDecisionSource.IMMEDIATE,
                    "immediate-v1");

    LoadTimeDecision assign(HardwareWorkSubmission submission, HardwareWorkHandle handle);
}
```

Reject negative costs and a positive cost with no eligible service boundary. Implement
`resolve` so `FAST` emits one resolver-time warning and returns `IMMEDIATE`,
while `REALISTIC` emits one warning and returns the supplied profiled instance. Put
`LOAD_TIME_SIMULATION` in `ConfigCatalog` at `gameplay.loadTimeSimulation`, type `ENUM`,
allowed values from the enum, with `NONE` in both Java defaults and `config.yaml`.

- [ ] **Step 4: Document the four values and current fallback behavior**

Add a `gameplay.loadTimeSimulation` entry to `CONFIGURATION.md`, explicitly stating that
replay timing is unaffected and that `PROFILED` uses only published profile data.

- [ ] **Step 5: Run focused configuration tests**

Run: `mvn -Dtest=TestLoadTimeSimulationConfiguration,TestLoadTimeProfileFactory,TestConfigCatalog,TestBundledConfigResource test`

Expected: PASS.

- [ ] **Step 6: Commit the configuration contract**

```bash
git add CONFIGURATION.md src/main/java/com/openggf/configuration \
  src/main/java/com/openggf/game/timing/LoadTimeSimulationMode.java \
  src/main/java/com/openggf/game/timing/LoadTimeProfileFactory.java \
  src/main/java/com/openggf/game/timing/LoadTimeDecisionSource.java \
  src/main/java/com/openggf/game/timing/LoadTimeDecision.java \
  src/main/java/com/openggf/game/timing/LoadTimeProfile.java \
  src/main/resources/config.yaml src/test/java/com/openggf/configuration \
  src/test/java/com/openggf/game/timing/TestLoadTimeProfileFactory.java
git commit -m "feat: add load-time simulation configuration"
```

### Task 2: Manifest Lookup

**Files:**
- Create: `src/main/java/com/openggf/game/timing/ProfiledLoadTimeManifest.java`
- Create: `src/main/resources/load-time-profiles/s3k-v1.json`
- Test: `src/test/java/com/openggf/game/timing/TestProfiledLoadTimeManifest.java`

**Interfaces:**
- Produces: `ProfiledLoadTimeManifest.load(InputStream, Consumer<String>)`.
- Consumes: `HardwareWorkSubmission` and its already-computed `HardwareWorkHandle` fingerprint.

- [ ] **Step 1: Write failing decision and manifest tests**

```java
LoadTimeDecision decision = manifest.assign(submission, handle);
assertEquals(7, decision.serviceFrames());
assertEquals(LoadTimeDecisionSource.MEASURED, decision.source());
assertEquals("s3k-kos-v1", decision.serviceModel());
assertEquals(Set.of(PRE_MAIN_LOOP), decision.eligibleBoundaries());

LoadTimeDecision missing = manifest.assign(otherSubmission, otherHandle);
assertEquals(0, missing.serviceFrames());
assertEquals(LoadTimeDecisionSource.IMMEDIATE, missing.source());
assertEquals(1, warnings.size());
```

Also assert duplicate manifest keys, negative costs, unknown kinds, unknown sources, and
unsupported manifest versions fail loading. Assert repeated lookup of one missing key
warns only once.

- [ ] **Step 2: Run the manifest test and confirm it fails to compile**

Run: `mvn -Dtest=TestProfiledLoadTimeManifest test`

Expected: compilation fails for the missing manifest type.

- [ ] **Step 3: Implement immutable decisions and a strict JSON manifest parser**

```java
public interface LoadTimeProfile {
    LoadTimeProfile IMMEDIATE = (submission, handle) ->
            new LoadTimeDecision(0, Set.of(), LoadTimeDecisionSource.IMMEDIATE,
                    "immediate-v1");

    LoadTimeDecision assign(HardwareWorkSubmission submission, HardwareWorkHandle handle);
}
```

Use the repository's existing JSON library. Key entries by
`HardwareWorkKind`, `submissionFingerprint`, and the manifest-level
`serviceModel`. A missing key warns once and returns `IMMEDIATE`; do not estimate until a
validated estimator artifact exists. Validate but retain `sampleCount`, `minFrames`,
`maxFrames`, and `provenance` so published records remain auditable.

- [ ] **Step 4: Add an empty, versioned S3K manifest**

The foundation branch may use an empty `entries` array only to exercise loading. It must
not enable or advertise `PROFILED` as functional until Task 6 publishes measured rows.
Use a manifest-level fixture dictionary and integer provenance indexes so repeated fixture
paths are not duplicated in every record.

- [ ] **Step 5: Run profile tests**

Run: `mvn -Dtest=TestProfiledLoadTimeManifest,TestLoadTimeProfileFactory test`

Expected: PASS.

- [ ] **Step 6: Commit the profile boundary**

```bash
git add src/main/java/com/openggf/game/timing src/main/resources/load-time-profiles \
  src/test/java/com/openggf/game/timing
git commit -m "feat: add deterministic load-time profile manifests"
```

### Task 3: Physical FIFO Lifecycle, Countdown, and Rewind State

**Files:**
- Modify: `src/main/java/com/openggf/game/timing/HardwareTimingService.java`
- Modify: `src/main/java/com/openggf/game/timing/HardwareTimingJob.java`
- Modify: `src/main/java/com/openggf/game/timing/HardwareTimingSnapshot.java`
- Test: `src/test/java/com/openggf/game/timing/TestHardwareTimingService.java`
- Test: `src/test/java/com/openggf/game/timing/TestHardwareTimingRewind.java`

**Interfaces:**
- Consumes: `LoadTimeProfile.assign(...)`.
- Produces: `HardwareTimingService(RomWorkBudgetScheduler, LoadTimeProfile)`.
- Produces: rewind job fields `assignedServiceFrames`, `remainingServiceFrames`,
  `profileActive`, `physicallyRetired`, `eligibleBoundaries`, `decisionSource`, and
  `serviceModel`.

- [ ] **Step 1: Write failing timing tests**

Create deterministic fake preparations and assert:

```java
service.service(POST_OBJECTS); // head activates, preparation and countdown both advance
assertFalse(service.isReady(first));
service.service(POST_OBJECTS);
assertTrue(service.isReady(first)); // both gates complete
assertFalse(service.isReady(second)); // second did not count down behind the head
```

Cover zero-cost identity with current live behavior, preparation slower than the countdown,
countdown slower than preparation, noneligible boundaries not decrementing, per-kind FIFO
independence, ready-but-unclaimed predecessors not blocking successors, coordinator
capture, and recorded admission ignoring the live countdown gate. A spy profile must
receive zero calls for every kind whose admission policy is `RECORDED`.

- [ ] **Step 2: Run service tests and observe immediate live admission**

Run: `mvn -Dtest=TestHardwareTimingService test`

Expected: new delayed-admission assertions fail.

- [ ] **Step 3: Assign once at physical-head activation and gate live readiness**

For each `LIVE` kind, find the first job that is not physically retired. If it is not
active, call `profile.assign(job.submission(), job.handle())` once and store the decision.
Decrement positive remaining frames only when the current boundary belongs to the
decision's typed `eligibleBoundaries`; preparation advances independently.

Retire/admit only when the head is prepared and countdown-complete. Then loop within the
same service call: activate the next head and immediately retire it if its assigned cost
is zero and the scheduler already prepared it. Continue until the next head is unprepared
or has positive remaining cost. This preserves the existing multi-release behavior for
`NONE`. A retired ready-but-unclaimed job never blocks its successor.

For a `RECORDED` kind, skip profile assignment, activation, warnings, and countdown
entirely. Production preparation continues and only `RecordedAuthority` retires/admit the
matching prepared head. Schema-1 kinds configured `LIVE` explicitly use immediate live
admission rather than the normal-play profile.

- [ ] **Step 4: Persist complete profile state in job snapshots**

Extend `HardwareTimingJob.Snapshot` with all lifecycle fields above. Unassigned jobs use
zero frames, empty eligible boundaries, source `IMMEDIATE`, service model `unassigned`,
and false activation/retirement flags. Validate impossible combinations. Restore verbatim
without a profile lookup; `resetForMissingSnapshot` still clears all jobs.

- [ ] **Step 5: Run service and rewind tests**

Run: `mvn -Dtest=TestHardwareTimingService,TestHardwareTimingRewind,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard test`

Expected: PASS.

Add a differential identity test that runs the default constructor and explicit
`IMMEDIATE` profile through multi-unit scheduler budgets, boundary-driven preparation,
mixed kinds, and delayed claims, comparing readiness, pending handles, payloads, and
snapshots after every boundary.

- [ ] **Step 6: Commit admission and rewind behavior**

```bash
git add src/main/java/com/openggf/game/timing \
  src/test/java/com/openggf/game/timing \
  src/test/java/com/openggf/trace/timing
git commit -m "feat: gate hardware readiness on profiled service time"
```

### Task 4: Session Composition and Replay Bypass

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeLoadTimeProfile.java`
- Test: `src/test/java/com/openggf/trace/replay/TraceReplaySessionBootstrapConfigTest.java`

**Interfaces:**
- Produces: `GameModule.createLoadTimeProfile(LoadTimeSimulationMode, Consumer<String>)`.
- Consumes: `GameplaySessionTimingOptions` resolved by `SessionManager` before
  `GameplayModeContext` constructs its timing service.
- Preserves: `beginRecordedAdmission(...)` as the sole replay readiness authority.

- [ ] **Step 1: Write failing composition tests**

Assert a normal session uses the configured mode, `NONE` is immediate, and `PROFILED`
creates a fresh session-owned provider. Cover open, reopen, editor resume, and editor
restart. A spy proves recorded kinds perform zero normal-profile calls.

- [ ] **Step 2: Run composition tests and confirm sessions always construct the default service**

Run: `mvn -Dtest=TestGameplayModeLoadTimeProfile,TraceReplaySessionBootstrapConfigTest test`

Expected: new assertions fail.

- [ ] **Step 3: Add the module-owned profile factory**

```java
default LoadTimeProfile createLoadTimeProfile(
        LoadTimeSimulationMode mode, Consumer<String> warningSink) {
    return LoadTimeProfileFactory.resolve(mode.name(), LoadTimeProfile.IMMEDIATE, warningSink);
}
```

Override it in `Sonic3kGameModule` to load `/load-time-profiles/s3k-v1.json` into a fresh
provider for each gameplay timing session. Never cache the provider on the module:
warn-once state is session-owned. Keep shared timing code free of game identifiers.

- [ ] **Step 4: Compose the profile before the timing service**

Add `GameplaySessionTimingOptions(LoadTimeSimulationMode normalMode,
HardwareReadinessAdmissionPolicy admissionPolicy)` and make `SessionManager` its owner.
Normal open paths read `GameServices.configuration().getString(LOAD_TIME_SIMULATION)`;
store the selected mode with the world/editor session so reopen, resume, and restart
neither revert to `NONE` nor reuse destroyed provider state. Every newly constructed
`GameplayModeContext` receives a new provider/warning set. Explicit replay paths install
their schema policy before the first submission: recorded kinds bypass the provider, and
schema-1 live direct work gets immediate production admission. Never replace a profile
after submission.

- [ ] **Step 5: Run session and replay suites**

Run: `mvn -Dtest=TestGameplayModeLoadTimeProfile,TraceReplaySessionBootstrapConfigTest,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard test`

Expected: PASS.

- [ ] **Step 6: Commit session integration**

```bash
git add src/main/java/com/openggf/game src/main/java/com/openggf/trace/replay \
  src/test/java/com/openggf/game/session \
  src/test/java/com/openggf/trace/replay
git commit -m "feat: compose load-time profiles for normal play"
```

### Task 5: S3K KosM Composite Service Model

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosDecompressionQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kRuntimeArtCoordinator.java`
- Test: `src/test/java/com/openggf/game/sonic3k/resources/TestS3kKosQueueProfileTiming.java`

**Interfaces:**
- Consumes: the profile admission gate without changing queue predicates.
- Produces: an explicit `moduleCount > 0` parent classification that receives zero
  additional profile frames under service model `s3k-kos-v1`.

- [ ] **Step 1: Write failing coordinator tests**

Assert child module submissions receive their normal profile decisions, the KosM parent
receives zero additional frames without a missing-key warning, and the final child plus
parent can both become ready at the same decisive `POST_OBJECTS` boundary.

- [ ] **Step 2: Run the S3K queue test and observe parent lookup/final-boundary mismatch**

Run: `mvn -Dtest=TestS3kKosQueueProfileTiming test`

Expected: new assertions fail.

- [ ] **Step 3: Encode the composite-parent service-model rule at the S3K provider boundary**

The S3K profile implementation returns:

```java
if (submission.kind() == KOS_MODULE_QUEUE
        && submission.compressionVariant().equals("kosinski_moduled")) {
    return new LoadTimeDecision(
            0, Set.of(), LoadTimeDecisionSource.IMMEDIATE,
            "s3k-kos-v1-composite-parent");
}
```

Do not special-case zones or object callers. Preserve coordinator submission, queue
ordering, and same-boundary coordinator capture.

- [ ] **Step 4: Run focused S3K runtime-art tests**

Run: `mvn -Dtest='TestS3kKos*,TestS3kRuntimeArtCoordinator' test`

Expected: PASS.

- [ ] **Step 5: Commit S3K service-model semantics**

```bash
git add src/main/java/com/openggf/game/sonic3k/resources \
  src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java \
  src/test/java/com/openggf/game/sonic3k/resources
git commit -m "feat: model S3K KosM profiled queue timing"
```

### Task 6: Measurement Artifact Design and Tooling Gate

**Files:**
- Create: `docs/architecture/designs/2026-07-29-native-load-time-measurement-stream.md`
- Create: `docs/architecture/plans/2026-07-29-native-load-time-measurement-stream.md`
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkFeatures.java`
- Create: `src/main/java/com/openggf/game/timing/LoadTimeEstimator.java`
- Create: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosinskiWorkFeatureExtractor.java`
- Create: `src/main/java/com/openggf/tools/timing/NativeLoadTimeManifestGenerator.java`
- Create: tests at the corresponding `src/test/java` paths.
- Modify: `src/main/java/com/openggf/game/timing/HardwareWorkSubmission.java`
- Modify: S3K direct/module queue submissions to attach production-derived features.

**Interfaces:**
- Consumes: manifest schema and service-model identifiers from Tasks 2 and 5.
- Produces: separately reviewed and implemented diagnostic capture, feature extraction,
  estimator fitting/validation, and manifest-generation tooling; publishing measurements
  remains independently authorized.

- [ ] **Step 1: Specify the diagnostic stream**

Define JSONL records for submission, physical-head activation, before/after decoder
service, module-child coordination, prepared state, and retirement. Every record includes
fixture/movie identity, frame, monotonic within-frame sequence, kind, ordinal,
fingerprint, service-model version, and hook name. State explicitly that this stream is
tooling-only and cannot be loaded by runtime or replay.

- [ ] **Step 2: Specify aggregation and validation**

Define lower-median aggregation, sample/min/max/provenance output, whole-fingerprint
cross-validation, fixture-family secondary grouping, nearest-rank p95, thresholds of
median absolute error at most 2 eligible frames and p95 at most 5, and minimum dataset
requirements of 20 fingerprints, 3 families, and nonconstant retained features.
The generated manifest includes fitted integer/rational coefficients, retained feature
names, eligibility boundaries, validation metrics, dataset counts, and service-model
version. Unsupported models are omitted rather than silently accepted.

- [ ] **Step 3: Review the design and plan independently until no blocking issues remain**

The reviewer must verify hook completeness, native service-unit derivation, artifact
authority isolation, deterministic aggregation, source hashes, publication workflow, and
that existing completion-only fixtures are never treated as duration measurements.

- [ ] **Step 4: Implement and verify the reviewed tooling**

Implement the recorder hooks, strict parser, deterministic aggregator, deterministic
coefficient fitter, whole-fingerprint and fixture-family validators, validation report,
and byte-stable manifest generator described by the green follow-on plan.

`HardwareWorkFeatures` carries only deterministic command-stream metrics: literal command
count, short/long copy counts, copied output length, compressed/decompressed lengths,
module count, final-module size, and coordination count. Production queue owners compute
it from the same ROM-backed compressed bytes already inspected for submission; the
profile never reads disassembly or trace data. `LoadTimeEstimator` evaluates only
manifest-published coefficients and returns `ESTIMATED`. If no validated model exists for
the kind/service model, it warns once per fingerprint and returns immediate.

Add the immutable feature vector to `HardwareTimingJob.Snapshot` and the submission
recreation path. Validate restored features against the submission kind/service model.
A snapshot taken before physical-head activation must later make the same measured,
estimated, or unsupported-model decision without rereading ROM, trace, or disassembly
data and without duplicating a warning.

Add tests for exact feature extraction from known synthetic Kos streams, deterministic
coefficient serialization, leave-one-fingerprint-out grouping, fixture-family grouping,
nearest-rank p95, every minimum-data threshold, and runtime measured/estimated/immediate
selection. Extend rewind tests with pre-activation snapshots for measured, estimated, and
unsupported-model decisions. Existing completion-only timing streams may be used for
cardinality tests only, never duration.

- [ ] **Step 5: Stop only at the publication authority boundary**

Do not capture, commit, or publish trace-derived duration data without the repository's
explicit artifact authorization and immutable-input checks. Foundation and tooling
commits may remain on the feature branch, but do not merge or advertise `PROFILED` as
functional yet.

### Task 7: Publish Measurements, Enable PROFILED, Verify, and Deliver

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md` during merge into `develop`

**Interfaces:**
- Consumes: all executable tasks and the reviewed measurement follow-on documents.
- Produces: a nonempty, validated manifest and a clean feature branch ready for the
  repository integration workflow.

- [ ] **Step 1: Capture and publish authorized measurements**

After artifact authorization, replay the approved immutable source movies with the
diagnostic recorder, verify hashes and FIFO attribution, aggregate lower medians, and
generate a nonempty S3K manifest plus validation report. Include estimator coefficients
only for a model that passes every validation gate. Fail publication on malformed records,
high variance, identity disagreement,
or an unsupported estimator being emitted or selected. A candidate estimator that misses
thresholds is omitted and does not block publication of valid measured rows. Verify at
runtime that a known measured fingerprint selects `MEASURED`. If a validated model was
published, a held-out eligible fingerprint must select `ESTIMATED`; otherwise it must
select `IMMEDIATE` with one warning. An unsupported kind/model always selects `IMMEDIATE`
with one warning.

- [ ] **Step 2: Run focused timing, configuration, replay, rewind, and S3K tests**

Run: `mvn -Dtest='TestLoadTime*,TestHardwareTiming*,TraceReplaySessionBootstrapConfigTest,*S3kKos*Test,TestS3kRuntimeArtCoordinator' test`

Expected: PASS.

- [ ] **Step 3: Run the full JDK 21 suite**

Run: `mvn -v`

Expected: Maven reports Java 21.

Run: `mvn test`

Expected: no new failures against the updated `develop` baseline.

- [ ] **Step 4: Enable and document functional PROFILED mode**

Only after the manifest is nonempty and validated, remove the temporary unavailable
wording and document the new modes, measured coverage, missing-fingerprint behavior, and
replay isolation in `CHANGELOG.md` and `CONFIGURATION.md`. Fill every required commit
trailer and never bypass hooks.

- [ ] **Step 5: Follow the repository integration workflow**

Fetch and fast-forward `develop` without overwriting user changes, record its full-suite
baseline, rerun the feature worktree suite, merge into the main workspace, add the required
`README.md` release-log summary, rerun the merged full suite, and push only `develop`.

- [ ] **Step 6: Clean up implementation scaffolding**

After push, verify the feature worktree has no unknown or unmerged changes, remove it,
delete the fully merged local feature branch, and prune worktree metadata.
