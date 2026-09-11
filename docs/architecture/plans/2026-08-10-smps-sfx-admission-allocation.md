# SMPS SFX Admission Allocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make warmed SMPS SFX triggers allocate only new mutable voice state and bounded admission metadata, independent of SMPS/DAC size and unrelated live music, while preserving rewind and frontier observer behavior.

**Architecture:** `AudioPresentationSourceFactory` owns a generation-aware catalog whose immutable dependency entries hold one frozen DAC/config pair and whose music/SFX program entries hold one frozen SMPS program plus a precomputed source descriptor. Command resolution looks up before loading, and `SmpsDriver` prepares all fallible admission work before a bounded commit. The `develop` path commits without a whole-driver snapshot; the observer-heavy S1 frontier adds a channel-bounded mutation journal only when a potentially throwing observer is active.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, `com.sun.management.ThreadMXBean`, existing SMPS/YM2612/PSG snapshot and presentation-command infrastructure.

## Global Constraints

- Work in `.worktrees/audio-sfx-allocation` on `bugfix/ai-audio-sfx-allocation`; never switch the branch in the main workspace.
- Treat ROM bytes as the only runtime asset source; do not read audio payloads from `docs/`.
- Preserve exact S1/S2/S3K SMPS behavior, rewind identity, donor routing, continuous SFX, and same-ID/channel-contention semantics.
- Do not pool mutable sequencers or tracks, and do not make a zero-allocation claim for genuinely new mutable voice state.
- Reuse immutable music programs, voices, and envelope tables across repeated starts in the same dependency generation; keep every live sequencer/track/envelope cursor independent.
- The warmed trigger path must not copy/hash whole SMPS programs, copy DAC/config data, or snapshot unrelated music/synth state.
- Public asset APIs must not expose mutable arrays; zero-copy access is restricted to narrow internal read views.
- Frontier diagnostic callbacks retain their existing post-mutation timing, typed exception behavior, and retryable queue semantics.
- Build and test with Maven running on JDK 21 (`mvn -v` must report Java 21).
- Use JUnit 5 only and never bypass `.githooks` policy with `--no-verify`.
- Keep `docs/architecture/designs/2026-08-10-smps-sfx-admission-allocation-design.md` and this plan staged with the feature; do not stage the test-generated `docs/status/rewind-round-trip-gaps.md`.
- Before integration, fetch and fast-forward `develop` without overwriting the user's dirty main-workspace file, then record the official updated baseline.
- Before changing `.worktrees/s1-audio-parity-frontier`, record its full-suite baseline; leave that worktree intact and do not push its branch without separate authorization.

---

## File structure and ownership

- Create `src/main/java/com/openggf/audio/smps/SmpsProgramView.java`: package-owned indexed read interface for frozen SMPS bytes, voices, and envelopes; it never returns backing arrays.
- Create `src/main/java/com/openggf/audio/presentation/SmpsAssetCatalog.java`: generation-aware dependency/program registration, identity conflict detection, and descriptor lookup. `AudioPresentationSourceFactory` owns exactly one instance.
- Create `src/main/java/com/openggf/audio/driver/PreparedSfxAdmission.java`: immutable, channel-bounded result of admission analysis; no chip or driver mutation occurs in its construction.
- Create on the frontier only `src/main/java/com/openggf/audio/driver/SfxAdmissionMutationJournal.java`: observer-enabled rollback of the exact driver, coordination, and affected chip state.
- Modify `DacData`, `SmpsSequencerConfig`, and the factory's frozen SMPS implementation to establish the defensive-copy boundary and internal zero-copy views.
- Modify `SmpsSourceDescriptor`, `SmpsSequencer`, `AudioPresentationSourceFactory`, `AudioPresentationCommandResolver`, `AudioManager`, `AudioVoiceRegistry`, and `SmpsDriver` to carry generation and precomputed identity through lookup, construction, rewind, and admission.
- Modify frontier `VirtualSynthesizer`, `Ym2612Chip`, and `PsgChip` to expose package-safe selective capture/restore used only by the bounded journal.

### Task 1: Record official baselines and reconcile updated develop

**Files:**
- Preserve without edits: `docs/status/rewind-round-trip-gaps.md` in the main workspace.
- No committed file is produced solely by baseline execution.

**Interfaces:**
- Produces: exact post-fetch `develop` baseline and exact pre-port frontier baseline for regression comparison, plus a feature worktree rebased by merge onto the fetched integration head.

- [x] **Step 1: Verify workspace and toolchain state**

  In the main workspace run `git status --short --branch`, `git worktree list --porcelain`, and `mvn -v`. Confirm `develop`, the known dirty user file, clean frontier, feature branch commits, and Java 21.

- [x] **Step 2: Fetch and fast-forward develop safely**

  Run `git fetch origin`, then `git pull --ff-only` in the main workspace only if Git confirms the dirty user file will not be overwritten. If upstream overlaps that file, stop and report the authority blocker without stashing or discarding it.

- [x] **Step 3: Record the official develop baseline**

  Because the suite is known to rewrite the same status file that is user-dirty in the main workspace, create a detached temporary worktree at the updated `develop` commit under `.worktrees/audio-develop-baseline` and run `mvn test` there. Record exact totals and failing/erroring test names. Classify/remove its generated status-file change, remove the detached worktree, and verify the main workspace's user file hash is unchanged; do not run the mutating full suite over that file in place.

- [x] **Step 4: Reconcile updated develop into the feature worktree**

  Merge updated `develop` into `bugfix/ai-audio-sfx-allocation`, resolve conflicts without losing user/upstream work, and verify `git merge-base --is-ancestor develop HEAD` succeeds before implementation begins.

- [x] **Step 5: Record the untouched frontier baseline**

  In `.worktrees/s1-audio-parity-frontier`, confirm clean status and Java 21, then run `mvn test`. Record exact totals and failures/errors before applying any feature commit. Remove only test-generated artifacts after classifying them.

### Task 2: Establish the immutable DAC and static-config boundary

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/DacData.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Create: `src/test/java/com/openggf/audio/smps/TestDacDataImmutability.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestYm2612ChipSnapshot.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandResolver.java`
- Modify: `src/test/java/com/openggf/tests/TestRomAudioIntegration.java`

**Interfaces:**
- Produces: `DacData.Sample DacData.sample(int sampleId)`, `DacData.DacEntry DacData.mappingForNote(int noteId)`, `int DacData.baseCycles()`, safe scalar inspection methods `sampleCount()`, `mappingCount()`, `hasSample(int)`, and package-private indexed config accessors `fmChannelCount()`, `fmChannelAt(int)`, `psgChannelCount()`, `psgChannelAt(int)`.
- Preserves: existing public `getFmChannelOrder()` and `getPsgChannelOrder()` defensive-copy behavior for callers outside the sequencer package.
- Consumes: existing constructor maps and `DacEntry`; callers may mutate their inputs after construction without changing `DacData`.

```java
public final class DacData {
    public Sample sample(int sampleId);
    public DacEntry mappingForNote(int noteId);
    public int baseCycles();
    public int sampleCount();
    public int mappingCount();
    public boolean hasSample(int sampleId);

    public static final class Sample {
        public int length();
        public byte byteAt(int index);
    }
}
```

- [x] **Step 1: Write failing immutability and sample-read tests**

  Add tests which construct a mutable `Map<Integer, byte[]>`, mutate the original map and byte array, and assert `sample(1).length()`/`sample(1).byteAt(0)` retain the original values. Reflect over `DacData` public fields/method return types and assert no `byte[]` or `Map<Integer, byte[]>` is exposed. Assert out-of-range `byteAt` uses the normal indexed-read exception and missing sample/note lookups return `null`.

- [x] **Step 2: Run the new test and capture the expected failure**

  Run `mvn -Dtest=TestDacDataImmutability test`. Expect failure because `samples` is public and constructor input arrays are shared.

- [x] **Step 3: Implement immutable DAC samples**

  Make `DacData` final; deep-copy each non-null input array into a private immutable `Sample` object whose public API is exactly `int length()` and `byte byteAt(int index)`. Store private unmodifiable sample/mapping maps; keep `DacEntry` as an immutable final value with primitive fields/accessors so existing loaders remain source-compatible; expose only scalar/sample lookup APIs; and update `Ym2612Chip` to retain/read `Sample` instead of `byte[]`. Because the constructor is now the defensive-copy boundary, remove `AudioPresentationSourceFactory.copyDac` in this task and pass the immutable `DacData` reference through `snapshotSource`/`freshSource`; later catalog work removes `freshSource` itself.

- [x] **Step 4: Add indexed config access without per-sequencer clones**

  Keep the public copying getters, add package-private scalar accessors over the already-owned channel-order arrays, and update `SmpsSequencer` call sites to populate tracks via count/index rather than calling an array-copying getter.

- [x] **Step 5: Verify DAC playback and snapshot parity**

  Replace direct test map inspection with `sampleCount`, `mappingCount`, `hasSample`, and `sample(...).byteAt(...)`, then run `mvn -Dtest=TestDacDataImmutability,TestYm2612ChipSnapshot,TestSmpsSequencerSnapshot,TestAudioPresentationCommandResolver,TestRomAudioIntegration test`. Expect all selected tests to pass and existing YM snapshot restore to retain the same sample id/offset behavior.

- [x] **Step 6: Commit the immutable dependency boundary**

  Stage only the nine listed files and commit `perf(audio): share immutable DAC dependencies` with required policy trailers and `Changelog: n/a: internal ownership groundwork with no user-visible behavior change`.

### Task 3: Freeze SMPS programs behind a zero-copy internal view

**Files:**
- Create: `src/main/java/com/openggf/audio/smps/SmpsProgramView.java`
- Modify: `src/main/java/com/openggf/audio/smps/AbstractSmpsData.java`
- Modify: `src/main/java/com/openggf/audio/smps/CoordFlagContext.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
- Create: `src/test/java/com/openggf/audio/smps/TestFrozenSmpsDataImmutability.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationSourceParity.java`

**Interfaces:**
- Produces: `SmpsProgramView` scalar/indexed methods for data, FM/PSG pointer and offset tables, voices, PSG envelopes, and modulation envelopes; no method returns an array or mutable collection.
- Produces: a factory-owned frozen `AbstractSmpsData` whose public array accessors return copies, whose inherited setters throw `UnsupportedOperationException`, and which also implements `SmpsProgramView` for sequencer internals.
- Consumes: mutable loader `AbstractSmpsData` only at first registration/freezing.

```java
public interface SmpsProgramView {
    int dataLength();
    byte dataByteAt(int index);
    int fmPointerCount();
    int fmPointerAt(int index);
    int fmKeyOffsetAt(int index);
    int fmVolumeOffsetAt(int index);
    int psgPointerCount();
    int psgPointerAt(int index);
    int psgKeyOffsetAt(int index);
    int psgVolumeOffsetAt(int index);
    int psgModEnvelopeAt(int index);
    int psgInstrumentAt(int index);
    int voiceLength(int voiceId);
    byte voiceByteAt(int voiceId, int index);
    int psgEnvelopeLength(int envelopeId);
    byte psgEnvelopeByteAt(int envelopeId, int index);
    int modEnvelopeLength(int envelopeId);
    byte modEnvelopeByteAt(int envelopeId, int index);
}
```

- [x] **Step 1: Write failing frozen-data API tests**

  Register a source containing sequence, voice, PSG-envelope, modulation-envelope, every pointer/offset table, id, and PAL metadata. Mutate every loader-owned input after registration; mutate every array returned by public getters; call `setId` and `setPalSpeedupDisabled`; assert a newly instantiated and rewind-restored voice still sees the originally registered bytes and metadata. Assert `CoordFlagContext` exposes only `SmpsProgramView`, not `byte[]`.

- [x] **Step 2: Run the frozen-data tests and confirm mutation leaks**

  Run `mvn -Dtest=TestFrozenSmpsDataImmutability,TestAudioPresentationSourceParity test`. Expect at least the raw public accessor/setter assertions to fail.

- [x] **Step 3: Add the narrow internal view and frozen implementation**

  Define `SmpsProgramView` without any array-returning method. Replace the factory's partially frozen nested classes with one implementation that clones all loader arrays at construction, returns defensive copies through `AbstractSmpsData`, rejects post-construction mutation, and services the indexed internal view without cloning.

- [x] **Step 4: Convert sequencer hot reads to the internal view**

  Store a `SmpsProgramView` beside the public metadata object in `SmpsSequencer`; replace constructor data/pointer/offset array reads and voice/envelope getter calls with indexed reads. Replace `CoordFlagContext.getData()` with `programView()` and convert `Sonic3kCoordFlagHandler`'s length/byte reads accordingly, so a game-specific handler cannot mutate frozen bytes. Keep fallback voice behavior by accepting another `SmpsProgramView`, not a raw loader array.

- [x] **Step 5: Verify sequence behavior and public immutability**

  Run `mvn -Dtest=TestFrozenSmpsDataImmutability,TestAudioPresentationSourceParity,TestSmpsSequencerSnapshot,TestSmpsSequencerTempoMath test`. Expect all selected tests to pass.

- [x] **Step 6: Commit the frozen-program boundary**

  Commit the listed production/tests as `perf(audio): freeze shared SMPS programs` with required trailers and `Changelog: n/a: internal asset ownership groundwork with no user-visible behavior change`.

### Task 4: Add generation-aware catalog registration and precomputed descriptors

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/SmpsAssetCatalog.java`
- Modify: `src/main/java/com/openggf/audio/presentation/SmpsAssetKey.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsSourceDescriptor.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestSmpsAssetCatalog.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverSnapshotDescriptorDedupPerformance.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverSnapshot.java`

**Interfaces:**
- Produces: `SmpsAssetCatalog.DependencyKey(String gameId, DependencyKind kind, long generation)`, `ProgramKey(SmpsAssetKey assetKey, long generation)`, `ProgramEntry register(ProgramKey, AbstractSmpsData, DacData, SmpsSequencerConfig, boolean)`, `ProgramEntry find(ProgramKey)`, and `ProgramEntry require(SmpsSourceDescriptor)`. `SmpsAssetKey` gains distinct `BASE_MUSIC` and `DONOR_MUSIC` routes and asset-oriented accessors while retaining transitional SFX accessors.
- `ProgramEntry` exposes immutable references `program()`, `programView()`, `dac()`, `staticConfig()`, `sourceDescriptor()`, `specialSfx()`, and provenance identity used for exceptional equality checks.
- Produces: a `SmpsSequencer` construction overload that accepts the stored `SmpsSourceDescriptor` and does not call `SmpsSourceDescriptor.from`.

```java
final class SmpsAssetCatalog {
    enum DependencyKind { BASE, DONOR }
    record DependencyKey(String gameId, DependencyKind kind, long generation) {}
    record ProgramKey(SmpsAssetKey assetKey, long generation) {}
    record ProgramEntry(AbstractSmpsData program, SmpsProgramView programView,
            DacData dac, SmpsSequencerConfig staticConfig,
            SmpsSourceDescriptor sourceDescriptor, int assetId,
            int trackCount, boolean specialSfx) {}

    ProgramEntry register(ProgramKey key, AbstractSmpsData data,
            DacData dac, SmpsSequencerConfig config, boolean specialSfx);
    ProgramEntry find(ProgramKey key);
    ProgramEntry require(SmpsSourceDescriptor descriptor);
}
```

- [x] **Step 1: Write catalog identity, sharing, and collision tests**

  Cover first registration, same-object O(1) repeat, reconstructed-equal repeat, conflicting bytes under one key, two SFX sharing one dependency identity, and repeated base/donor music registrations sharing one frozen sequence/voice/PSG-envelope/modulation-envelope representation. Cover music-vs-SFX/base-vs-donor/named/special separation, old/new generation coexistence, and descriptor collisions across donor/base sources. Use identity assertions for frozen program, DAC, config, and descriptor objects. Assert repeated music instantiation shares the program/view/DAC/config/descriptor identities but creates distinct mutable sequencers and tracks.

- [x] **Step 2: Run the catalog tests and confirm the type is absent**

  Run `mvn -Dtest=TestSmpsAssetCatalog test`. Expect compilation failure until `SmpsAssetCatalog` exists.

- [x] **Step 3: Implement two-level catalog ownership**

  Use a dependency map keyed by `DependencyKey` (including base/donor provenance) and a program map keyed by `ProgramKey`. Freeze DAC/config only when the dependency is first registered; record their source-object identities and reject different dependency objects under the same generation before program publication. Freeze program and calculate descriptor/fingerprint in the same copying pass only when the program is first registered. On an existing program key, return immediately for the identical source object; otherwise perform one exceptional semantic comparison and either return the old entry or throw an `IllegalStateException` naming the conflicting key/generation.

- [x] **Step 4: Make descriptors generation-bearing and registration-time only**

  Add `long dependencyGeneration` to `SmpsSourceDescriptor`; update the named/base/donor factory methods to accept it and to consume the frozen program's already-computed length/hash rather than calling `getData()` again. Preserve current overloads with generation zero for the transitional legacy backend and direct driver tests. Retain `matchesData` only for exceptional registration/debug paths. Add a sequencer constructor/setter path receiving the catalog descriptor so SFX instantiation and restore never recompute a whole-program hash; keep legacy constructor and fallback snapshot hashing out of the warmed presentation path.

- [x] **Step 5: Route factory registration, instantiation, and rewind through entries**

  Replace `sfxAssets`/`CachedSmpsSource` ownership with catalog entries, rename `warmSmpsSfxAsset` to `registerSmpsSfxAsset`, add `findRegisteredSmpsSfxAsset(SmpsAssetKey,long)`, and add equivalent music registration/lookup entry points backed by the same catalog. Remove `freshSource` and `copyDac`, and make `sourcesByDescriptor` refer to the exact program entry. Freeze and bind the coordination handler once when creating a dependency entry; never rebuild config per sequencer. Registered music and SFX instantiation must pass stored program/DAC/config/descriptor references directly; `recreateSmps` must resolve the descriptor's generation and share those same references. Music descriptor construction receives the current dependency generation too, with a compatibility overload using generation zero only for transitional legacy sources. Keep `legacyMusicSmps` outside catalog ownership as its documented transitional exception.

- [x] **Step 6: Pin descriptor construction cost against program size**

  Extend `TestSmpsDriverSnapshotDescriptorDedupPerformance` with tiny and 1 MiB program fixtures. Warm registration before measurement, instantiate identical track topology repeatedly for both SFX and music, and assert `ThreadMXBean` per-instantiation allocation slopes differ only within the control-run tolerance; also assert the stored descriptor object is reused.

- [x] **Step 7: Verify catalog and rewind behavior**

  Run `mvn -Dtest=TestSmpsAssetCatalog,TestAudioPresentationSourceParity,TestSmpsDriverSnapshotDescriptorDedupPerformance,TestSmpsDriverSnapshot,TestSmpsSequencerSnapshot,TestAudioPresentationSnapshotParity test`. Expect all selected tests to pass.

- [x] **Step 8: Commit the versioned catalog**

  Commit as `perf(audio): catalog immutable SMPS assets` with required trailers and `Changelog: n/a: internal presentation cache ownership change`.

### Task 5: Propagate base and donor dependency generations

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationCommandResolver.java`
- Modify: `src/main/java/com/openggf/audio/presentation/ResolvedSmpsSfxSource.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerResetState.java`
- Modify: `src/test/java/com/openggf/audio/TestDonorAudioRouting.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandResolver.java`

**Interfaces:**
- Extends `AudioPresentationCommandResolver.Sources` with `long dependencyGeneration(SmpsAssetKey.Route route, String gameId)` so base and donor identities cannot collide when their game-id strings match.
- Extends `ResolvedSmpsSfxSource` with `long dependencyGeneration` so queued commands retain the version resolved at submission.
- `AudioManager` owns one immutable `BaseAudioSource` tuple and immutable `DonorAudioSource` entries; each tuple carries its monotonically increasing generation. `ShadowSources.dependencyGeneration` is O(1).

```java
public interface Sources {
    long dependencyGeneration(SmpsAssetKey.Route route, String gameId);
}

public record ResolvedSmpsSfxSource(
        long standaloneVoiceId, SmpsAssetKey assetKey,
        long dependencyGeneration, int pitchQ16, int priority,
        int continuousSfxId, int trackCount, int maxStereoFrames) {}

private record BaseAudioSource(
        Rom rom, GameAudioProfile profile, SmpsLoader loader,
        DacData dac, long generation) {}

private record DonorAudioSource(
        SmpsLoader loader, DacData dac, SmpsSequencerConfig config,
        GameAudioProfile profile, long generation) {}
```

- [x] **Step 1: Write stale-source replacement tests for every mutator**

  In one existing shadow-factory session, register/play a key, then call each of `setRom`, `setAudioProfile`, donor register, donor replace, and `clearDonorAudio`. Assert the applicable generation strictly increases, a new command cannot use the old program, and an old live/snapshot voice still resolves its original descriptor. Assert unrelated donor generations remain unchanged. Add profiles whose `createSmpsLoader`, loader `loadDacData`, and backend `setAudioProfile` throw; after each failure assert the complete previous ROM/profile/loader/DAC/generation tuple remains published, and a retry can publish exactly one next generation.

- [x] **Step 2: Run the generation tests and capture stale reuse**

  Run `mvn -Dtest=TestAudioManagerResetState,TestDonorAudioRouting,TestAudioPresentationCommandResolver test`. Expect new assertions to fail because current keys contain no generation.

- [x] **Step 3: Prepare and publish base source tuples transactionally**

  Replace separately published base fields with `BaseAudioSource`. `setRom(candidate)` first constructs the candidate profile's loader and loads its DAC into local variables, then publishes one new tuple with `generation + 1`; any construction/load failure leaves the old tuple untouched. `setAudioProfile(candidate)` likewise constructs the candidate loader/DAC against the current ROM before changing either manager or backend state, calls `backend.setAudioProfile(candidate)`, and only after success publishes the tuple with `generation + 1`. If the backend call throws, attempt `backend.setAudioProfile(previous.profile())`, attach restore failure as suppressed, keep the previous manager tuple/generation, and rethrow. A null ROM/profile produces a tuple with null loader/DAC rather than retaining incompatible dependencies.

- [x] **Step 4: Publish donor entries as one versioned value**

  Replace parallel donor loader/DAC/config/profile maps with a map of `DonorAudioSource`; validate all arguments and configure required backend/presentation coordination handlers before the single map publication. Successful register/replace uses the retained per-id counter plus one. `clearDonorAudio` advances and retains the counter for every currently registered donor before removing entries, so re-registering an id cannot reuse an old generation. On a pre-publication failure, restore the previous handler/profile configuration and keep the previous entry/generation. If restoration itself fails, remove the affected donor entry, advance its retained counter, attach the restore failure as suppressed, and rethrow so no stale catalog entry can be selected.

- [x] **Step 5: Carry generation through command resolution and cache keys**

  Read the route-specific token before lookup/load, include it in the catalog `ProgramKey` and resolved command, and reject application if the queued entry for that exact generation is missing. Preserve the old entry for live and rewind descriptors.

- [x] **Step 6: Verify source replacement and routing**

  Run `mvn -Dtest=TestAudioManagerResetState,TestDonorAudioRouting,TestAudioPresentationCommandResolver,TestAudioPresentationSnapshotParity test`. Expect all selected tests to pass.

- [x] **Step 7: Commit dependency invalidation**

  Commit as `fix(audio): invalidate replaced SMPS sources` with `CHANGELOG.md` staged and all required policy trailers.

### Task 6: Make resolver lookup-before-load the registered fast path

**Files:**
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationCommandResolver.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandResolver.java`
- Modify: `src/test/java/com/openggf/audio/TestShadowAudioPresentationRouting.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestHostUiSfx.java`
- Modify: `src/test/java/com/openggf/audio/TestDonorAudioRouting.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerPresentationModes.java`

**Interfaces:**
- Consumes: the music and SFX catalog lookup/registration entry points from Tasks 4–5.
- Produces: owner-submitted and directly resolved music/SFX commands whose descriptor identity and dependency references come from `ProgramEntry`; loaders are invoked only on a versioned miss.
- Produces: private `SmpsSfxLoader` and `resolveSmpsSfxCommand(String,SmpsAssetKey,int,SmpsSfxLoader,float)`; named routes pass requested id `-1` and registration stores the loaded program id.

```java
@FunctionalInterface
private interface SmpsSfxLoader {
    AbstractSmpsData load();
}

private AudioPresentationCommand resolveSmpsSfxCommand(
        String gameId, SmpsAssetKey key, int requestedSfxId,
        SmpsSfxLoader loader, float pitch) {
    long generation = sources.dependencyGeneration(key.route(), gameId);
    ProgramEntry entry = factory.findRegisteredSmpsSfxAsset(key, generation);
    if (entry == null) {
        AbstractSmpsData loaded = Objects.requireNonNull(
                loader.load(), "loader returned no SMPS data");
        int resolvedSfxId = requestedSfxId >= 0
                ? requestedSfxId : loaded.getId();
        entry = factory.registerSmpsSfxAsset(key, generation, loaded,
                requireDac(gameId), requireConfig(gameId),
                sources.specialSfx(gameId, resolvedSfxId));
    }
    int sfxId = entry.sfxId();
    int continuousId = sources.continuousSfx(gameId, sfxId) ? sfxId : 0;
    return new AddSmpsSfx(factory.resolveSmpsSfx(
            allocateVoiceId(), key, generation, pitchQ16(pitch),
            sources.sfxPriority(gameId, sfxId), continuousId,
            entry.trackCount(), sources.maxStereoFrames()));
}
```

- [x] **Step 1: Add loader-call-count tests**

  Use a loader that returns a fresh equal `AbstractSmpsData` on every call. Resolve the Sonic 2 `BASE_SMPS_NAME` route twice and assert one loader call/one registration; repeat for SFX id and donor routes. Through production `AudioManager.playMusic` and `playDonorMusic`, repeat base and donor SMPS music starts and assert one total loader call/freeze per key/generation across both manager classification and resolver application while each start creates a distinct live voice. Change generation and assert exactly one additional load for each asset kind. Explicit duplicate registration with reconstructed-equal data must reuse the old entry. Also pin a base null result to the existing fallback-WAV command and a donor null result to no command; negative-result caching is not required.

- [x] **Step 2: Run resolver tests and observe repeated loads**

  Run `mvn -Dtest=TestAudioPresentationCommandResolver,TestShadowAudioPresentationRouting,TestDonorAudioRouting test`. Expect the second named trigger and second SMPS music start loader call counts to be 2 before the fix.

- [x] **Step 3: Reorder resolution around catalog lookup**

  Pass each SFX route load as a `SmpsSfxLoader`, read current generation, and ask the factory for the versioned program before invoking it. On a miss, load once, resolve the named route's id from the returned program, and register. Derive id, priority, continuous id, `trackCount`, and special-SFX metadata from the returned entry/current source policy, then enqueue the normal `AddSmpsSfx` command. Apply the same lookup-before-load ordering to base/donor SMPS music using its `AudioSourceDescriptor`-derived `SmpsAssetKey`; on a hit allocate only the new mutable music voice/sequencer state from the existing program entry.

  Make the earlier `AudioManager.playMusic`/`playDonorMusic` route-classification boundary catalog-aware as well. Before the existing loader probe, derive the base/donor music key and read the route-specific generation. On a hit, record the SMPS timeline command without loading. On a miss, load exactly once and register that exact result with the current immutable dependency tuple before publishing the command; do not publish if registration fails. Preserve base null-result fallback-WAV behavior and donor null-result no-op behavior. The presentation resolver must hit this registered entry, while its own lazy miss handling remains for direct resolver/replay-style callers and must not load a second time.

  Task 9's public-path benchmark showed that base-id, base-name, direct-donor,
  and GameSound-donor SFX have the same earlier manager classification owner.
  Apply the identical lookup/register-before-publication rule there, submit the
  command with the one captured source tuple, keep requested-name keys with the
  first program's resolved asset id, and retain the existing null boolean,
  no-op, and named-fallback semantics. A registration conflict publishes no
  timeline or presentation command; the dormant live-backend branch reads the
  retained catalog program and immutable dependencies rather than miss-local
  nullable data.

- [x] **Step 4: Update explicit warming callers to registration terminology**

  Rename AudioManager/test call sites from `warmSmpsSfxAsset` to `registerSmpsSfxAsset` and require an explicit dependency generation at owner boundaries.

- [x] **Step 5: Verify every route**

  Run `mvn -Dtest=TestAudioPresentationCommandResolver,TestAudioPresentationSourceParity,TestAudioManagerPresentationModes,TestShadowAudioPresentationRouting,TestHostUiSfx,TestDonorAudioRouting test`. Expect one total load/freeze per key/generation through production manager submission plus resolver application, distinct mutable voices/tracks/cursors per music start, shared program/view/voice/envelope/DAC/config/descriptor identities across playback and rewind restoration, and unchanged resolved command fields.

- [x] **Step 6: Commit lookup-before-load**

  Commit as `perf(audio): reuse registered SMPS assets` with `CHANGELOG.md` staged and required trailers.

### Task 7: Introduce pure, channel-bounded SFX preparation

**Files:**
- Create: `src/main/java/com/openggf/audio/driver/PreparedSfxAdmission.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java`
- Create: `src/test/java/com/openggf/audio/driver/TestPreparedSfxAdmission.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverSnapshot.java`

**Interfaces:**
- Produces: nullable `PreparedSfxAdmission SmpsDriver.prepareContinuousSfxExtension(int continuousSfxId,int trackCount)`, `PreparedSfxAdmission SmpsDriver.prepareNewSfxAdmission(SmpsSequencer,int continuousSfxId,int trackCount)`, and `void SmpsDriver.commitSfxAdmission(PreparedSfxAdmission)`.
- `PreparedSfxAdmission` contains the new sequencer, extension/replacement mode, affected-FM/PSG masks, ordered displaced sequencer/track action arrays sized from the new SFX track count, and continuous metadata. It exposes no general mutable collection. FM and DAC retain distinct exact-type actions even though both can address hardware channel 5.
- Produces: explicit `void SmpsSequencer.beginSfxAdmission()` which invokes its bound `CoordFlagHandler.onSfxStart(id)`; the constructor never calls it.
- Preserves: driver insertion/removal ordering, same-ID replacement, priority/contention rules, channel takeover, DAC selection, and coordination start timing.

```java
public final class PreparedSfxAdmission {
    SmpsDriver owner();
    SmpsSequencer sequencer();
    boolean continuousExtension();
    int affectedFmMask();
    int affectedPsgMask();
    int continuousSfxId();
    int trackCount();
}

public PreparedSfxAdmission prepareContinuousSfxExtension(
        int continuousSfxId, int trackCount);
public PreparedSfxAdmission prepareNewSfxAdmission(
        SmpsSequencer sequencer, int continuousSfxId, int trackCount);
public void commitSfxAdmission(PreparedSfxAdmission admission);

public void beginSfxAdmission();
```

- [x] **Step 1: Write preparation-purity tests**

  Capture driver, coordination, full synth, locks/latches, and registry state; prepare same-ID, FM-contention, PSG-contention, continuous-extension, and standalone cases; assert every captured state is byte/identity-equal before commit. Assert matching continuous extension produces a prepared admission without constructing a sequencer or invoking `onSfxStart`. Add invalid channel/pointer/continuous metadata cases and assert they fail during preparation.

- [x] **Step 2: Run purity tests and confirm no preparation API exists**

  Run `mvn -Dtest=TestPreparedSfxAdmission test`. Expect compilation failure until the new type/methods exist.

- [x] **Step 3: Implement allocation-bounded conflict analysis**

  Analyze live SFX using hardware-channel masks and pre-sized ordered action arrays sized from the new sequencer's track count; do not use streams, `HashSet`, or a growing list. Preserve the new program's header/track order because PSG writes and the final latch are order-sensitive. Represent FM and DAC exact types independently on channel 5, and do not schedule the same displaced track twice for duplicate new entries. Scanning unrelated live SFX is allowed, but allocated storage must remain independent of unrelated live channel/track counts.

- [x] **Step 4: Separate construction from live chip writes**

  Ensure sequencer construction, dependency binding, descriptor assignment, priority setup, and preparation do not enable DAC, silence channels, write YM/PSG, acquire locks, or publish coordination/observer events. Remove the constructor's `onSfxStart` call and expose it only through `beginSfxAdmission`; driver commit does not own or invoke coordination handlers. In this same commit, update the registry's existing atomic SFX path to instantiate and prepare first, then capture coordination state, invoke `beginSfxAdmission` immediately before driver commit, and restore only that snapshot if start/commit fails; continuous extension returns without capturing coordination or invoking start.

- [x] **Step 5: Implement deterministic commit**

  Convert the registry's still-atomic SFX path to call the prepared APIs. Commit in the existing native program/header order: extend when applicable; retire same-ID/displaced tracks; update locks/latches and channel silence/takeover; insert the sequencer; install continuous state. Guard against committing a prepared object to a different driver or twice. With chip observers disabled, every post-validation driver step must be internally non-throwing. With a develop chip observer enabled, use the reviewed transitional driver-local full snapshot only around commit, restore/release the claim on callback failure, and rethrow; the observer-free path must not capture it. Task 8 removes the registry whole-voice wrapper, and frontier reconciliation replaces this diagnostic fallback with the selective journal.

- [x] **Step 6: Verify admission parity**

  Run `mvn -Dtest=TestPreparedSfxAdmission,TestSmpsDriverSnapshot,TestAudioVoiceRegistry,TestMusicOverrideRestore,TestChipWriteObserver test`. Expect all cases to match the pre-change post-commit state. Include mixed FM6/DAC conflicts, duplicate exact-type entries, reversed multi-PSG header order with full latch/event comparison, explicit zero-track continuous metadata, complete pre/post preparation snapshots, unrelated-live-set allocation slopes, and real YM/PSG observer exceptions with exact driver/synth rollback and retry.

- [x] **Step 7: Commit prepared admission**

  Commit as `refactor(audio): prepare SMPS SFX admission` with required trailers and `Changelog: n/a: behavior-preserving admission decomposition`.

### Task 8: Remove whole-driver rollback from the common SFX path

**Files:**
- Modify: `src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java`
- Modify: `src/main/java/com/openggf/audio/presentation/SmpsSfxInstantiation.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioVoiceRegistry.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandQueue.java`

**Interfaces:**
- Consumes: factory program entries and `SmpsDriver.prepareContinuousSfxExtension`/`prepareNewSfxAdmission`/`commitSfxAdmission`.
- Produces: registry SFX application which completes all throwable factory/validation work before mutation, owns the narrow coordination snapshot/action for new sequencers, and never calls `captureLiveCommandMutation` for a successful/rejected `AddSmpsSfx` on `develop`. The optional develop chip-observer rollback is driver-local and absent when observers are `NONE`.

```java
PreparedSfxAdmission admission = driver.prepareContinuousSfxExtension(
        source.continuousSfxId(), source.trackCount());
if (admission == null) {
    SmpsSequencer sequencer = sfxInstantiation.instantiateCached(source, driver);
    admission = driver.prepareNewSfxAdmission(
            sequencer, source.continuousSfxId(), source.trackCount());
    SmpsCoordFlagRuntimeState.Snapshot coordState =
            coordFlagHandlers.state().snapshot();
    try {
        sequencer.beginSfxAdmission();
        driver.commitSfxAdmission(admission);
    } catch (RuntimeException failure) {
        rollbackCoordFlagState(coordState, failure);
        throw failure;
    }
} else {
    driver.commitSfxAdmission(admission);
}
```

- [x] **Step 1: Add rollback-capture spy tests**

  Count `captureLiveCommandMutation` and `onSfxStart` calls for existing-owner, same-ID replacement, rejected, continuous, and standalone SFX. Assert zero rollback-token calls; exactly one coordination start immediately before commit for each new sequencer; zero for preparation rejection and continuous extension; unchanged state on cache/preparation/coordination rejection; and exactly one committed insertion on success. Preserve command-queue retry behavior for failures that occur before commit.

- [x] **Step 2: Run registry tests and observe full snapshot capture**

  Run `mvn -Dtest=TestAudioVoiceRegistry,TestAudioPresentationCommandQueue test`. Expect rollback-capture counts to fail on the current `mutateVoicesAtomically` path.

- [x] **Step 3: Rework owner and standalone publication**

  Try continuous extension first and commit it without construction or coordination start. Otherwise instantiate and prepare before registry publication, snapshot `coordFlagHandlers.state()`, and wrap both `sequencer.beginSfxAdmission()` and `driver.commitSfxAdmission(admission)` in the same try/catch. On any `RuntimeException`, restore the coordination snapshot (attaching restore failure as suppressed) and rethrow; the driver-local observer fallback owns only driver/synth restoration and prepared-claim release. Existing owners use this sequence directly. Standalone drivers remain unpublished until all preparation, coordination work, and commit succeed; on failure dispose only the new unpublished voice and restore the narrow coordination snapshot. Publish `standaloneSmps`/voice id only after commit. Continuous extension remains outside this scope because it invokes no coordination start.

- [x] **Step 4: Keep generic atomic mutation for non-SFX commands**

  Remove the registry whole-voice wrapper only from SMPS SFX admission. Music override, stop, sample voice, restore, and other live mutations continue using their existing rollback contracts. Assert observer-free driver commit captures no snapshot; an enabled throwing chip observer uses the reviewed driver-local fallback, restores exactly, leaves the command retryable, and is later replaced by the frontier selective journal.

- [x] **Step 5: Verify registry and queue semantics**

  Run `mvn -Dtest=TestAudioVoiceRegistry,TestAudioPresentationCommandQueue,TestAudioPresentationSnapshotParity,TestUnifiedAudioPresentationIntegration test`. Expect all selected tests to pass and the SFX rollback-capture spy to remain zero. Inject real YM and PSG observer failures through registry/queue admission; assert exact driver/synth and coordination-byte restoration, released prepared claim, the same command retained for retry, and exactly one eventual commit.

- [x] **Step 6: Commit the common-path optimization**

  Commit as `perf(audio): bound SMPS SFX admission state` with `CHANGELOG.md` staged and required trailers.

### Task 9: Prove allocation independence and architecture ownership

**Files:**
- Create: `src/test/java/com/openggf/audio/TestSmpsSfxAdmissionAllocation.java`
- Create: `src/test/java/com/openggf/audio/TestSmpsRepeatedPlaybackBenchmark.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationAllocationBudget.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationSourceParity.java`

**Interfaces:**
- Consumes: the complete warmed command path from resolver through catalog, preparation, driver commit, and replacement cleanup.
- Produces: a structural regression test that reports per-trigger allocation slopes for program, DAC, unrelated-music, and trigger-count dimensions.
- Produces: a baseline-compatible end-to-end benchmark manifest using only public `AudioManager` music/SFX submission entry points common to updated `develop` and the feature, with machine-readable raw samples and fixed paired acceptance rules for a historical comparison.

```java
long slope(long allocationsAt64, long allocationsAt256) {
    return (allocationsAt256 - allocationsAt64) / (256 - 64);
}
```

- [x] **Step 1: Build constant-topology allocation fixtures**

  Create tiny/large pairs with identical SFX track count, pitch, priority, command route, registry topology, and trigger counts. Vary only one dimension per pair: program bytes (64 bytes vs 1 MiB), DAC bytes (64 bytes vs 4 MiB), or unrelated music tracks/state (minimal vs maximum legal topology). Use replacement/retrigger so live voice count stays constant.

- [x] **Step 2: Implement anti-flake measurement protocol**

  Require supported/enabled `ThreadMXBean`; warm the exact call site with discarded runs; measure at multiple counts such as 64, 128, and 256; repeat controls; compute least-squares or endpoint per-trigger slope; derive tolerance from the maximum repeated control spread plus a small documented fixed VM noise margin. On unsupported VMs, retain identity/structural assertions and skip only byte accounting.

- [x] **Step 3: Run the allocation test against the optimized path**

  Run `mvn -Dtest=TestSmpsSfxAdmissionAllocation test`. Expect all dimension slopes to remain within the derived tolerance and the absolute slope to reflect only new sequencer/track plus bounded metadata.

- [x] **Step 4: Capture an apples-to-apples historical benchmark**

  Add `TestSmpsRepeatedPlaybackBenchmark` as an opt-in benchmark (excluded from ordinary budget assertions) whose measured caller uses only the same public `AudioManager.playMusic`/SFX submission APIs available on updated `develop`. Keep loader/program/DAC/config/driver topology, replacement/retrigger behavior, live voice count, warmup counts, measurement counts (including 64/128/256 operations), and JVM properties constant. The byte-identical test-local loader returns a fresh instrumented `AbstractSmpsData` whose primitive `programMaterializations` counter advances exactly once on its first program-data/defensive-copy access; this paired semantic counter and loader-call counter exist on both commits without a feature API. Assert feature-only catalog registration identity separately outside the paired metric. Record at least five warmed repetitions per music and SFX scenario, plus tiny/large program, DAC, and unrelated-music controls. Emit raw allocated bytes, elapsed nanoseconds, operation count, loader/materialization counts, and GC deltas in a stable line format; calculate median bytes/op, control spread, median warmed ns/op, and percentage delta outside the measured region. Treat allocation as normative and timing as descriptive.

  Fix acceptance before the first measured run: for every fixture, feature median bytes/op must be no greater than baseline median plus `max(baselineControlSpread, featureControlSpread) + vmNoiseMargin`; print and document the VM margin. For targeted large program/DAC/unrelated-music fixtures, require the feature size slope to be within zero/control tolerance and materially below the baseline slope, plus a feature large-case improvement greater than tolerance whenever baseline shows the targeted size-dependent cost. Require feature loader calls and program materializations to equal exactly one per key/generation after warmup. The final historical run must use JDK 21 with supported and enabled `ThreadMXBean` allocation accounting; unlike an ordinary test skip, missing allocation data invalidates the comparison. Timing is report-only.

  Copy the exact benchmark manifest (main class plus every test-local helper/fixture) into a detached clean worktree at the official updated `develop` baseline; it must compile without changing production code. Hash the complete manifest, run it there and archive raw output, then run the byte-identical manifest and command on the completed feature worktree under the same JDK 21/JVM settings. Reject and repeat the comparison if manifest hash, fixture constants, route, environment, allocation-counter support, or operation counts differ. Remove the temporary baseline worktree after archiving results.

- [x] **Step 5: Add production API/write guards**

  Extend architecture guards to reject public raw DAC/SMPS arrays, `freshSource`/`copyDac` reintroduction, resolver load-before-lookup ordering, observer-free SFX-path `captureLiveCommandMutation`, and calls to `SmpsSourceDescriptor.from` from warmed instantiation. Allow the explicitly observer-gated driver-local transitional snapshot until frontier reconciliation replaces it; allow asset-sized hashing/copy only inside registration/freezing and ordinary rewind snapshot capture where documented.

- [x] **Step 6: Run the focused ownership and budget suite**

  Run `mvn -Dtest=TestSmpsSfxAdmissionAllocation,TestSmpsRepeatedPlaybackBenchmark,TestAudioPresentationArchitectureGuard,TestAudioPresentationAllocationBudget,TestAudioPresentationSourceParity test`. Expect all selected tests to pass and preserve the benchmark source hash used on the detached baseline.

- [x] **Step 7: Commit performance contracts**

  Commit as `test(audio): guard bounded SFX allocation` with required trailers and `Changelog: n/a: regression coverage for the accompanying performance fix`.

### Task 10: Update user-facing documentation and verify the feature worktree

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `docs/architecture/designs/2026-08-10-smps-sfx-admission-allocation-design.md`
- Modify: `docs/architecture/plans/2026-08-10-smps-sfx-admission-allocation.md`
- Create: `docs/architecture/audits/2026-08-10-smps-repeated-playback-performance.md`

**Interfaces:**
- Produces: release-note text explaining reduced repeated-SFX GC pressure and the final design/plan status.

- [x] **Step 1: Mark architecture artifacts implemented**

  Change design status to `Implemented`, check completed plan steps as execution proceeds, and record any reviewed deviation with the exact replacement interface and rationale.

- [x] **Step 2: Add release documentation**

  Add a concise `CHANGELOG.md` entry for repeated SMPS music/SFX no longer cloning ROM audio assets/unrelated driver state. Add the required `README.md` release/change-log summary before the branch is merged into `develop`. Stage a performance audit containing baseline/feature commits, complete manifest hash, exact environment/commands, fixture topology and sizes, allocation-accounting support, all raw repetitions, median allocated bytes/op, paired tolerance/pass-fail result, loader/materialization counts, feature-only catalog identity evidence, control spreads, descriptive warmed ns/op, percentage deltas, and the size-slope evidence; do not summarize incompatible runs.

- [x] **Step 3: Run the focused develop verification set**

  Run `mvn -Dtest=TestDacDataImmutability,TestFrozenSmpsDataImmutability,TestSmpsAssetCatalog,TestAudioPresentationCommandResolver,TestPreparedSfxAdmission,TestAudioVoiceRegistry,TestSmpsSfxAdmissionAllocation,TestAudioPresentationSnapshotParity,TestAudioPresentationArchitectureGuard test`. Record test count and outcome.

- [x] **Step 4: Run the full feature-worktree suite**

  Run `mvn test` on JDK 21. Compare exact failures/errors with the official updated `develop` baseline; no baseline-passing test may newly fail and no baseline failure may worsen due to the feature.

- [x] **Step 5: Remove generated non-deliverables from the feature worktree**

  Inspect `git status`; restore only the known test-generated `docs/status/rewind-round-trip-gaps.md` change to the worktree HEAD, and preserve/report any unknown change instead of discarding it.

- [x] **Step 6: Commit documentation and final develop implementation state**

  Commit as `perf(audio): eliminate repeated SFX asset copies`, staging `CHANGELOG.md`, `README.md`, both architecture artifacts, and any remaining intended code/tests, with all required trailers.

### Task 11: Integrate and push develop

**Files:**
- Merge the reviewed feature commits into the main-workspace `develop`; do not switch its branch.

**Interfaces:**
- Produces: pushed `develop` containing immutable versioned assets, lookup-before-load, prepared admission, tests, and documentation.

- [x] **Step 1: Review the final feature diff and commit ancestry**

  Run `git status --short`, `git log --oneline develop..bugfix/ai-audio-sfx-allocation`, and `git diff --check develop...bugfix/ai-audio-sfx-allocation`. Require a clean intended diff and no untracked architecture artifact.

- [x] **Step 2: Merge into the main workspace**

  Merge `bugfix/ai-audio-sfx-allocation` into checked-out `develop`, reconciling any late conflict carefully. Ensure the required `README.md` change is staged for the merge policy and never alter the pre-existing user file.

- [x] **Step 3: Run post-merge verification**

  Create a detached temporary verification worktree at the exact merged `develop` HEAD, run the Task 10 focused test set and `mvn test` there, and compare against the official baseline from Task 1. Verify no new/worsened regression, remove classified generated outputs and the detached worktree, and confirm the main workspace's user file hash remains unchanged.

- [x] **Step 4: Push only develop**

  Run `git push origin develop`. Record the pushed merge/feature commit ids and do not push the temporary feature branch.

### Task 12: Port the semantic change to the S1 audio frontier

**Files:**
- Modify as conflicts require: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/presentation/AudioPresentationCommandResolver.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Create: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/driver/SfxAdmissionMutationJournal.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/main/java/com/openggf/audio/synth/PsgChip.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/test/java/com/openggf/audio/TestAudioDiagnosticObservers.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/test/java/com/openggf/audio/driver/TestSfxContentionObserver.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/test/java/com/openggf/audio/smps/TestSmpsSfxConstructionPurity.java`
- Modify: `.worktrees/s1-audio-parity-frontier/src/test/java/com/openggf/audio/synth/TestChipWriteObserver.java`
- Create: `.worktrees/s1-audio-parity-frontier/src/test/java/com/openggf/audio/driver/TestSfxAdmissionMutationJournal.java`

**Interfaces:**
- Consumes: Tasks 2–9 semantics cherry-picked from landed develop commits.
- Produces: `SfxAdmissionMutationJournal.capture(driver, preparedAdmission, coordState)` and `restore()`; selective `VirtualSynthesizer.captureSfxAdmissionState(fmMask,psgMask)`/`restoreSfxAdmissionState(...)` APIs.
- Preserves: request/policy/admission/contention/chip callback values, ordering, and post-mutation visibility; pre-submission failures create no command, while owner-boundary `AudioDiagnosticObserverException` leaves the existing command queued and exact internal state restored.

```java
static SfxAdmissionMutationJournal capture(
        SmpsDriver driver, PreparedSfxAdmission admission,
        SmpsCoordFlagRuntimeState.Snapshot coordState);
void restore();

VirtualSynthesizer.SfxAdmissionState captureSfxAdmissionState(
        int affectedFmMask, int affectedPsgMask);
void restoreSfxAdmissionState(VirtualSynthesizer.SfxAdmissionState state);
```

- [x] **Step 1: Cherry-pick the semantic develop commits in order**

  Cherry-pick Tasks 2–9 commits onto `bugfix/ai-s1-audio-parity-frontier`. Resolve conflicts against frontier observer/admission code, keeping its observer event production and SFX-construction purity. Do not merge the 160 intervening develop commits merely to obtain this fix.

- [x] **Step 2: Write pre-submission observer-failure tests**

  Inject failure at the request observer and any policy callback that runs before `recordTimelineCommand`/presentation command creation. Assert the typed exception escapes with pre-submission state unchanged, no timeline/presentation command is queued, and no automatic retry or `onSfxStart` occurs. Preserve the frontier's existing request event timing and values.

- [x] **Step 3: Write queued owner-boundary observer-failure tests**

  Inject failure at owner-boundary policy, role-admission, contention, driver-service, YM write, PSG write, and registry admission callbacks. For same-ID replacement, FM/PSG contention, continuous extension, and standalone admission, assert: earlier callbacks observe the same committed locks/chip/order as the frontier baseline; thrown type is unchanged; the already-created command remains queued; all internal state equals the pre-command snapshot; retry commits exactly once. Assert `onSfxStart` runs once per new-admission attempt and its state is restored between failed attempt and retry, while continuous extension invokes it zero times.

- [x] **Step 4: Write selective-chip oracle tests**

  For each legal FM/PSG affected mask, capture both full synth snapshot and selective state, execute representative silence/key-off/noise/DAC takeover writes, restore selectively, and assert a new full snapshot equals restoration through the old full-snapshot oracle, including YM operator/key/envelope/register fields, `ssgEgActiveCount`, DAC selection, PSG noise/latch fields, and resampler-visible state written by admission.

- [x] **Step 5: Implement affected-channel synth capture/restore**

  Add immutable internal state records to YM/PSG/virtual synth containing only affected channel/operator arrays plus the small global fields mutated by SFX admission. Do not include DAC sample bytes, frozen program/config data, unrelated channels, or unrelated music sequencers.

- [x] **Step 6: Implement the frontier mutation journal**

  For new-sequencer admission, capture displaced/new track fields, affected locks/latches, driver memberships/removal buffers, continuous counters, DAC source/reference, admission ordinals, the registry-owned coordination snapshot taken before `beginSfxAdmission`, and selective synth state. For continuous extension, omit coordination state/action and capture only its counters/observed ordinal metadata. Allocate the journal only when at least one potentially throwing diagnostic observer is not `NONE`; restore in reverse mutation order and attach rollback failures as suppressed exceptions.

- [x] **Step 7: Preserve post-mutation observer timing and queue retry**

  For a new sequencer, registry captures the journal/coordination state, invokes `beginSfxAdmission`, commits normally, invokes every observer at its existing point, and holds the journal through the registry-level admission callback. On `AudioDiagnosticObserverException`, restore coordination plus driver/synth state and rethrow before the command queue consumes the head. Continuous extension skips `beginSfxAdmission`. With all observers `NONE`, use the Task 8 narrow coordination-failure handling and no-journal driver commit.

- [x] **Step 8: Verify frontier observer and allocation behavior**

  Run `mvn -Dtest=TestAudioDiagnosticObservers,TestSfxContentionObserver,TestSmpsSfxConstructionPurity,TestChipWriteObserver,TestSfxAdmissionMutationJournal,TestSmpsSfxAdmissionAllocation test`. Expect exact event-order/state assertions and allocation slopes to pass.

- [x] **Step 9: Run frontier full regression comparison**

  Run `mvn test` and compare exact totals/failures/errors with the untouched baseline from Task 1. No previously passing frontier test may fail and no baseline failure may worsen because of the port. Classify and restore the known test-generated `docs/status/rewind-round-trip-gaps.md` change; preserve/report any unknown change.

- [x] **Step 10: Commit the frontier reconciliation locally**

  Require a clean intended frontier status, then commit conflict resolutions and the observer journal as `perf(audio): bound observed SFX admission state` with required trailers. Leave `bugfix/ai-s1-audio-parity-frontier` and its worktree intact; do not push it without separate user authorization.

### Task 13: Independent review, cleanup, and final evidence

**Files:**
- No new production file unless review finds a concrete defect.

**Interfaces:**
- Produces: independently reviewed changes on both branches, cleaned temporary scaffolding, and a reproducible final report.

- [x] **Step 1: Request independent code review on both diffs**

  Provide reviewers the design, this plan, `develop` feature diff, frontier reconciliation diff, focused test results, allocation slopes, and baseline comparisons. Resolve every blocking finding and rerun affected tests until both reviews are green.

- [x] **Step 2: Re-run verification immediately before completion**

  Use `superpowers:verification-before-completion`; rerun `git diff --check`, the focused suites, and any test changed by review. Confirm pushed `develop` contains the reviewed commits and frontier HEAD contains its local reconciliation.

- [x] **Step 3: Clean the temporary feature worktree and branch**

  Verify `.worktrees/audio-sfx-allocation` has no uncommitted/unmerged user work. Remove classified test outputs, remove the worktree, verify `bugfix/ai-audio-sfx-allocation` is fully merged into `develop`, delete that local branch, and prune worktree metadata. Do not remove the S1 frontier worktree.

- [x] **Step 4: Report exact delivery state**

  Report the root cause, implementation summary, observer/catalog challenges, upstream/conflict reconciliation, every focused/full test command and outcome, the reproducible historical music/SFX before/after table and allocation slope evidence, pushed `develop` branch/commits, unpushed frontier local commit, preserved dirty user file, and successful temporary worktree/branch cleanup.
