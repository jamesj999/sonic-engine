# Complete-run audio parity shared infrastructure implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the strict, bounded, cross-game capture envelope, storage, comparator, observer plumbing, and complete-run replay boundary required by S1, S2, and S3K audio parity.

**Architecture:** Natural BizHawk and OpenGGF producers emit the same canonical record model into deterministic 4,096-row chunks. Game profiles own identities and driver-specific state; shared code owns validation, publication, comparison, observer propagation, and exact BK2-row cadence. S2/S3K reference observation uses a separately installed exact-BizHawk-2.11 GPGX core with a bounded native event-log ABI; stock GPGX remains untouched and S1 retains its existing M68K callback path.

**Tech Stack:** Java 21, JUnit Jupiter, Jackson streaming JSON, deterministic GZIP, SHA-256, Bash, Mono/C# 7.x, BizHawk 2.11 Waterbox/GPGX C, pinned musl Waterbox sysroot, and deterministic Zstandard.

## Global Constraints

- Follow `docs/architecture/designs/audio/2026-08-10-cross-game-complete-run-audio-parity-design.md` exactly.
- Work only in the isolated `bugfix/ai-s1-audio-parity-frontier` worktree; do not merge or push.
- Test behavior changes with RED/GREEN TDD and commit each task with required policy trailers.
- Detailed captures remain ignored under `target/audio-parity/`; tracked docs are compact and non-reconstructive.
- Production packages must not import `com.openggf.tools.audio.completerun`.
- The OpenGGF producer must never read a reference capture or recorded audio-event sidecar.
- Existing GHZ sound-test and GHZ1 timeline tools remain green.
- Never synthesize a Genesis `Z80 BUS` callback scope. Exact stock BizHawk 2.11
  exposes only `M68K BUS`; S2/S3K use only the pinned native buffered observer.
- Never overwrite, hard-link, or symlink a patched core over
  `docs/BizHawk-2.11-linux-x64`. Observer builds and installations are
  create-new beneath ignored `target/audio-parity/native/`.
- The native observer is diagnostic only. Disabled stock-vs-patched and
  enabled-vs-disabled emulation identity gates must pass before capability
  counts or full captures are trusted.
- S2/S3K post-service state is copied synchronously by the native observer at
  verified completion PCs. Managed frame-end Z80 RAM/register polling may not
  populate or validate a `DriverService`, even when only one service is expected.
- S2/S3K Z80 opcode proofs arm only at a source-cited M68K `SoundDriverLoad`
  root completion after final ZRAM is synchronously validated. Do not delay
  managed configuration, key readiness to a movie row, or arm lazily from one
  matching Z80 hook.

---

### Task 1: Canonical record model and strict profile registry

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioRecordSink.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`

**Interfaces:**
- Produces: sealed `Record`, `Metadata`, `Baseline`, `Frame`, `DriverService`, `ServiceCompletion`, `Lifecycle`, `Terminal`, `Request`, `Decision`, `OwnerRef`, `NormalizedState`, and `ChipEvent` types.
- Produces: `CompleteRunAudioProfile#validateState(NormalizedState)`, `CompleteRunAudioProfiles#require(String)`, and append-only `CompleteRunAudioRecordSink`.
- Consumes: no runtime owners and no old S1 schema classes.

- [ ] **Step 1: Write the failing model tests**

```java
@Test
void oneFrameCanContainZeroOrMultipleOrderedServices() {
    var empty = fixture.frame(860, List.of(), List.of());
    var busy = fixture.frame(861, List.of(fixture.request(1)), List.of(
            fixture.service(0), fixture.service(1)));
    assertEquals(List.of(), empty.services());
    assertEquals(List.of(0L, 1L),
            busy.services().stream().map(DriverService::ordinal).toList());
}

@Test
void sameIdOwnersRemainDistinctByRequestOrdinal() {
    assertNotEquals(new OwnerRef(OwnerClass.SFX, "sfx.explosion", 0xC0, 7),
            new OwnerRef(OwnerClass.SFX, "sfx.explosion", 0xC0, 8));
}

@Test
void resetCancelledServiceIsDistinctFromOrdinaryCompletion() {
    assertNotEquals(ServiceCompletion.COMPLETED,
            ServiceCompletion.RESET_CANCELLED);
}
```

Also reject signed/out-of-range bytes, unordered/duplicate roles, duplicate state
fields, empty content keys, unknown profiles, inactive roles with stale fields,
and a terminal whose counts or exclusive end do not match metadata.

- [ ] **Step 2: Run the test and observe RED**

Run:

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace test
```

Expected: test compilation fails because the complete-run types do not exist.

- [ ] **Step 3: Implement immutable records and profile validation**

Use a sealed hierarchy with defensive `List.copyOf`/`Map.copyOf` constructors.
Represent unsigned bytes as validated `int`; do not use signed JSON bytes.
`NormalizedState` stores an ordered list of `StateField` objects so canonical
field order is explicit and profile-validated. `DriverService` stores a
non-null `ServiceCompletion` of `COMPLETED` or `RESET_CANCELLED`.

```java
public sealed interface Record permits Baseline, Frame, Lifecycle, Terminal { }

public sealed interface ChipEvent permits YmWrite, PsgWrite {
    long ordinal();
}

public record YmWrite(long ordinal, int port, int register, int value)
        implements ChipEvent { }
```

- [ ] **Step 4: Run the focused tests and observe GREEN**

Run the command from Step 2. Expected: all tests pass.

- [ ] **Step 5: Commit the model**

```bash
git add src/main/java/com/openggf/tools/audio/completerun \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java
git commit -m "feat(tools): define complete-run audio trace schema"
```

### Task 2: Deterministic chunk store and atomic publication

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureStore.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`

**Interfaces:**
- Consumes: Task 1 record/profile types.
- Produces: `CompleteRunAudioCaptureStore.Writer`, `Reader`, `writeNew(Path, Metadata, Iterator<Record>)`, and `read(Path)`.

- [ ] **Step 1: Write failing strictness and publication tests**

Test deterministic byte identity, exactly 4,096 frame rows per non-final chunk,
gzip header timestamp zero, duplicate/unknown JSON rejection at every nesting
level, trailing root rejection, digest validation, missing terminal rejection,
iterator failure cleanup, unsupported atomic move failure, and preservation of
an existing destination.

```java
@Test
void failedPublicationNeverReplacesExistingCapture() throws Exception {
    Path output = temp.resolve("capture");
    Files.createDirectory(output);
    Files.writeString(output.resolve("sentinel"), "keep");
    assertThrows(FileAlreadyExistsException.class,
            () -> store.writeNew(output, metadata(), records().iterator()));
    assertEquals("keep", Files.readString(output.resolve("sentinel")));
}
```

- [ ] **Step 2: Run the test and observe RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore test
```

Expected: compilation fails for the missing store.

- [ ] **Step 3: Implement streaming JSON, deterministic gzip, and staging**

Write canonical JSON with a `JsonGenerator`; parse with a streaming
`JsonParser` and strict exact-field sets. Create a sibling staging directory
with `Files.createTempDirectory(output.getParent(), ".audio-staging-")`. After
full re-read validation, publish only through:

```java
Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
```

Do not fall back to a non-atomic move. The capture manifest lists per-chunk
compressed/uncompressed hashes, record counts, frame bounds, and the root hash.

- [ ] **Step 4: Prove bounded reads and deterministic output**

Add a subprocess test that reads a synthetic 20,000-frame capture using
`java -Xmx16m`, then run the Step 2 command. Expected: all pass and duplicate
capture directories compare byte-for-byte.

- [ ] **Step 5: Commit the store**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureStore.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java
git commit -m "feat(tools): store complete audio traces in deterministic chunks"
```

### Task 3: No-realignment comparator and reports

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioReport.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java`

**Interfaces:**
- Consumes: Task 2 validated capture readers.
- Produces: `CompleteRunAudioComparator.compare(Path reference, Path engine)` and deterministic text/JSON reports.

- [ ] **Step 1: Write failing mismatch-classification tests**

Cover metadata identity, missing/extra frame, request, service, decision and chip
event, ordering, state-field name/value, owner, priority, lifecycle, terminal
count, source replacement between passes, and exact match. Assert first mismatch
only and at most eight before/eight after records from each side.

```java
@Test
void doesNotRealignOneFrameAdmissionDelay() throws Exception {
    Report report = compare(referenceWithAdmissionAt(959),
            engineWithAdmissionAt(958));
    assertEquals(Kind.DECISION_EXTRA, report.kind());
    assertEquals(958, report.frame());
}
```

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator test
```

- [ ] **Step 3: Implement validation-first two-pass comparison**

Pass one fully validates each capture and records its root digest. Pass two
reopens both, rechecks metadata/ordinals/EOF while comparing, and verifies the
same digest at completion. Do not classify errors by message substrings; use a
typed `ValidationException.Kind`.

- [ ] **Step 4: Run GREEN and bounded-memory comparison**

Run the Step 2 command and a `-Xmx32m` synthetic 50,000-frame comparison.
Expected: all pass with bounded context.

- [ ] **Step 5: Commit the comparator**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioReport.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java
git commit -m "feat(tools): compare complete-run audio captures"
```

### Task 4: Pre-construction observer propagation and authority guard

**Files:**
- Create: `src/main/java/com/openggf/audio/AudioAdmissionObserver.java`
- Create: `src/main/java/com/openggf/audio/driver/SmpsDriverServiceObserver.java`
- Create: `src/main/java/com/openggf/audio/driver/SmpsRequestAdmissionPolicy.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/GameAudioProfile.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Test: `src/test/java/com/openggf/audio/TestAudioDiagnosticObservers.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioAuthorityGuard.java`

**Interfaces:**
- Produces: disabled-by-default admission and service/lifecycle observers plus a default permissive request policy selected through `GameAudioProfile`.
- Produces: `AudioManager#setAdmissionObserver`, `setDriverServiceObserver`, and existing chip/SFX observer propagation to every subsequently constructed driver.
- Consumes: no tooling types.

- [ ] **Step 1: Write failing lifecycle tests**

Construct the audio manager, install observers before music creation, then
assert observers see the first driver service, every new override driver, the
restored driver, accepted and blocked submissions, and ordered chip writes.
Assert `NONE` preserves snapshot bytes and write order.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.audio.TestAudioDiagnosticObservers,com.openggf.tools.audio.completerun.TestCompleteRunAudioAuthorityGuard test
```

- [ ] **Step 3: Add minimal observer interfaces and propagation**

```java
public interface SmpsDriverServiceObserver {
    SmpsDriverServiceObserver NONE = new SmpsDriverServiceObserver() { };
    default void onServiceBegin(long ordinal) { }
    default void onServiceEnd(long ordinal, SmpsDriverSnapshot snapshot) { }
    default void onLifecycle(LifecycleKind kind) { }
}
```

Callbacks run after the observed mutation, propagate exceptions so a tooling
capture fails loudly, and are absent from snapshots. Production defaults remain
no-op. Add no game-name checks.

The shared request policy is exact and game-neutral:

```java
public interface SmpsRequestAdmissionPolicy {
    AdmissionResult evaluate(SmpsAdmissionContext context);

    record AdmissionResult(boolean accepted, RejectionReason reason,
            int priorityBefore, int priorityAfter, int resolvedSoundId) { }
}
```

`GameAudioProfile#getSfxAdmissionPolicy()` returns the permissive implementation
by default. S2 and S3K replace it in their plans; S1 retains its driver-owned
priority semantics until the S1 mailbox task selects its source-accurate path.

- [ ] **Step 4: Add static authority assertions**

Scan `src/main/java/com/openggf` excluding `tools/` and fail on imports or fully
qualified references to `com.openggf.tools.audio.completerun`. Scan complete-run
producer constructors and fail if they accept reference capture paths/readers.

- [ ] **Step 5: Run GREEN and regression tests**

```bash
mvn -Dmse=off -Dtest=com.openggf.audio.TestAudioDiagnosticObservers,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.tools.audio.completerun.TestCompleteRunAudioAuthorityGuard test
```

- [ ] **Step 6: Commit the observer boundary**

```bash
git add src/main/java/com/openggf/audio src/test/java/com/openggf/audio \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioAuthorityGuard.java
git commit -m "feat(audio): expose inert complete-run diagnostic observers"
```

### Task 5: Complete-run outer-frame replay cadence

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/runs/VisualRunReplayHarness.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestCompleteRunAudioReplayCadence.java`

**Interfaces:**
- Produces: `VisualRunReplayHarness.replayCompleteAudio(Path, CompleteAudioStop, FrameObserver)`.
- Produces: frame views for every row in the comparison interval, with nullable segment coordinates.

- [ ] **Step 1: Write failing cadence tests**

Use a compact synthetic run with a segment, a three-row transition gap, a
second segment, a lag row, and a terminal tail. Assert one callback and one
presentation per absolute BK2 row, contiguous cursors, gap rows retained with
`segmentIndex == -1`, and exact terminal cursor. Assert fast-forward, pause,
trace abort, or premature movie end fails.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence test
```

- [ ] **Step 3: Implement explicit epoch/end cadence**

Do not change ordinary replay behavior. Add a mode whose budget is exactly the
validated interval length plus bounded bootstrap allowance. For every consumed
row call `loop.step()`, `loop.presentOuterFrame(false, false)`, and
`GameServices.audio().update()` exactly once before notifying the observer.

- [ ] **Step 4: Run GREEN and existing visual-run regressions**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence,com.openggf.tests.trace.runs.TestS1CompleteEmeraldVisualRun test
```

- [ ] **Step 5: Commit the cadence seam**

```bash
git add src/test/java/com/openggf/tests/trace/runs/VisualRunReplayHarness.java \
        src/test/java/com/openggf/tests/trace/runs/TestCompleteRunAudioReplayCadence.java
git commit -m "test(audio): drive every complete-run movie row"
```

### Task 6: Add native identity metadata and freeze the exact 2.11 inputs

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`
- Modify: `CHANGELOG.md`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/source-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/toolchain-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/managed-toolchain-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/fetch-source.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/prepare-toolchain.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/verify-inputs.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-core.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-managed.sh`
- Create: `tools/bizhawk-headless/tests/GpgxAudioObserverSourceLockTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: sealed `ObserverRuntimeIdentity` metadata with callback-only and buffered-native variants, strictly profile-pinned and serialized.
- Produces: a verified detached exact-2.11 source tree and Waterbox toolchain under a caller-supplied ignored `target/audio-parity/native/<run>/` root.
- Produces: immutable machine-readable source/toolchain identities consumed by Task 7.
- Produces: two independent byte-exact unmodified-core reproductions matching the installed stock artifact before any patch may be applied.
- Produces: two independent byte-exact unmodified managed-assembly reproductions so a first-class adapter can be selected without weakening the stock control.
- Consumes: stock `docs/BizHawk-2.11-linux-x64` read-only.

The source lock pins these literal identities:

```text
BizHawk tag/commit: 2.11 / 427556b5ef3ac437eba754d90c5e7e9096c9a8df
Genesis-Plus-GX:    051d430d3d1b54625f9900c8f152d7f232e06daf
waterbox/musl:      2063abc4e16c84218757b1db10d3cdf9f36ef3f8
stock gpgx.wbx.zst: c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12
stock gpgx.wbx:     b4cc6dabc069a6f1b87790212d80f665d216e603aa4990955cc816d5bf98d218
stock GPGX BuildID: 7696adca7ad14b79
Cores DLL:          0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7
Common DLL:         f20cd009f6f5b0a95bd47b66c48dc8de85afcd7ae0cc6aab3486baf55f501fb4
libwaterboxhost.so: d2367818aafb4e520ad5ab005b5762c61506b0c819c4d79687235acfb0fc0c78
stock gpgx.wbx size:     39558192
stock gpgx.wbx.zst size: 400161
```

The historical build lock is Ubuntu Mantic clang `16.0.6-15`, LLD `16.0.6`,
GNU Make `4.4.1`, binutils/ar `2.47`, and Zstandard `1.5.5`. The exact
`STOCK_INTERPRETER_PATH` is reconstructed from this pinned UTF-8 hex sequence
(rather than treated as a host filesystem dependency):
`2f686f6d652f66656f732f7368617265732f73686172652f42697a4861776b2f7761746572626f782f737973726f6f742f7379736c69622f6c642d6d75736c2d7761746572626f782e736f2e31`.
It also pins these literal SHA-256 values:

```text
clang-16 executable:        bb6556bdcdeb00dca0c758da9966a9982542a23ddcaffa784a2de9344ede3fc0
ld.lld-16 executable:       f8d0601bf957a1b063e29c3c43613a5b76482f6c14664b9fcac4d596871e14df
libLLVM-16.so.1:            55f9e1b3c3b98853fc31787414064de36a22cc23f870962b45832fc904c498a2
libclang-cpp.so.16:         f9bf97848329b4d444c8c8791b9f8a584b58016852a6ba4b55db164726623ac7
compiler-rt builtins:       2f257b223dbee10ea0415e5f95385a71dc05bb94505a21a4be1d22ce733e624d
built zstd 1.5.5:           7bc75866617449d384679bd29298a222a458ff0daea0fc4c221122b5513cf307
clang-16 deb:               b9cd4d27a5d1b6c429fccf56a4ac1c4ac5baf2cb9b5a53e2a20fcd6593153e5a
libclang-cpp16 deb:         39eb3e73119ef0180489c7e594d29398152b3a2d7eec2361cf87d367032f466a
libllvm16 deb:              3353bbe1910cfc99a8ef96e1cd7df45c65e2aaebefcfc801bcb7587bab819a15
llvm-16-linker-tools deb:   39f6c47b5ecc04c064899a99d224650b2d932e7f27ac02246073395fc8bd1300
libclang-common-16-dev deb: ada57e3ac045bb324397c6d269dbad56a0b0f3608c89d321d1fed38206570ff5
lld-16 deb:                 e75a2e784d2da2e3d90a31d7b8002892ac58b90e53073a14c7db1a8d80172204
libclang-rt-16-dev deb:     20f3b1a105d5b8fba261a03bd6ad531e09a87c929f33f54e5dd4db78f980dda2
libedit2 package:           d1c26768f5e108c97d9520c8a19356ddf5a1967222af4f38efb1f5af21da46b5
libxml2 package:            7c4d4ec04145f854bb824cb72fb34233c99f7db3eaafaa3d2049bd82800c0f85
libicu72 package:           3db0831a7a8da3c8d878fdbc4644d4131ed914b22c8a0cffbcabe68a2c3f6ec4
zstd-1.5.5.tar.gz:          9c4396cc829cfae319a6e2615202e82aad41372073482fce286fac78646d3ee4
```

- [ ] **Step 1: Write failing native-identity and source-lock tests**

Extend the Java model tests with this exact shape:

```java
public sealed interface ObserverRuntimeIdentity
        permits CallbackObserverIdentity, BufferedNativeObserverIdentity { }

public record CallbackObserverIdentity(String id)
        implements ObserverRuntimeIdentity { }

public record BufferedNativeObserverIdentity(
        String abiName, int abiVersion, int eventSize, int capacity,
        String installationId, String coreId, String coreBuildId,
        String watchMaskSha256, String serviceManifestSha256, boolean enabled,
        int maximumFrameOccupancy, long overflowCount)
        implements ObserverRuntimeIdentity { }
```

`Metadata` carries one required `ObserverRuntimeIdentity`, and
`CompleteRunAudioProfile` pins one per producer kind. Callback-only identities
serve S1 and OpenGGF. Buffered-native identity requires stable logical IDs
`bizhawk-2.11-gpgx-audio-observer-v1` and `gpgx-audio-observer-v1`, the ABI
values frozen by Task 7, a nonempty BuildID, lowercase SHA-256 watch-mask and
service-manifest digests, `enabled=true`, occupancy in `[1, capacity]`, and
overflow exactly zero. An absolute source, staging, or installation path is
validation-only and must be rejected by canonical JSON serialization. Task 6
adds the generic strict type and test vectors but leaves the S2/S3K reference
identity unavailable; Task 7 supplies the feasibility-proven event size/version
and only then enables those profile values. No provisional ABI value is
serialized as an accepted capture identity.

Extend `RuntimeArtifact` with exact SHA-256 slots for
`BIZHAWK_COMMON_DLL`, `WATERBOX_HOST`, `GPGX_CORE_UNCOMPRESSED`,
`GPGX_OBSERVER_PATCH`, `GPGX_OBSERVER_SOURCE_BUNDLE`,
`GPGX_OBSERVER_TOOLCHAIN`, and `GPGX_OBSERVER_BUILD_RECIPE`.
Add optional-but-paired `BIZHAWK_OBSERVER_MANAGED_PATCH` and
`BIZHAWK_OBSERVER_CORES_DLL`: both are required for the first-class adapter and
both forbidden for the reflection adapter; metadata carries the selected
adapter enum.
When Task 7 enables the S2/S3K buffered-native reference profiles, they require
and pin all of them plus the existing BizHawk executable, core DLL, and
compressed GPGX core. Add strict canonical
JSON vectors and reject a missing, duplicate, unknown, callback/native-mismatched,
disabled, overflowed, or out-of-capacity identity at every parser/profile gate.

Assert the locks require the literal commits/hashes and package/tool versions
above; exact byte digests
for the 2.11 `waterbox/emulibc`, `waterbox/common.mak`, and
`waterbox/linkscript.T` inputs; compiler, linker, musl, sysroot-tree, zstd, and
build-recipe digests; and no mutable tag, branch, latest URL, or unverified local
path. Assert BizHawk 2.11.1 commit
`bdddf4a58aa1a022afb11dc73294a81a5aa7bbd5` is rejected. Assert missing
submodules or the locally observed uninitialized 2.11.1 cache fail closed.
Hand-add the test file to the non-SDK test project and register its cases in
`TestMain.BuildRegistry()`; the test uses `TestScratch`, never `/tmp`.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore' test
tools/bizhawk-headless/test.sh --filter GpgxAudioObserverSourceLockTests
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/fetch-source.sh
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/prepare-toolchain.sh
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/verify-inputs.sh
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-core.sh
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-managed.sh
```

Expected: test compilation or script checks fail because the locks and scripts
do not exist.

- [ ] **Step 3: Implement and verify the strict metadata extension**

Add the sealed types, producer-specific artifact requirements, profile maps,
and streaming exact-field JSON support. Do not encode hashes, capacity, enabled
state, BuildID, occupancy, or overflow in `observerProfile`, `callbackSource`,
or another free-form string. Re-run both Java test classes and update the
independent canonical JSON/hash vectors with literal reviewed bytes.

- [ ] **Step 4: Implement create-new source acquisition**

`fetch-source.sh --output <absent-directory>` initializes a detached clone,
fetches commit `427556b5ef3ac437eba754d90c5e7e9096c9a8df` by object ID, and initializes
only `waterbox/gpgx/Genesis-Plus-GX` and `waterbox/musl`. It verifies the exact
submodule commits and the locked Waterbox input-byte digests before publishing
the source directory. It never falls forward to 2.11.1 and never modifies a
pre-existing output.

- [ ] **Step 5: Freeze and reproduce the complete Waterbox toolchain identity**

`prepare-toolchain.sh` consumes exactly the reviewed content-addressed Mantic
packages and zstd source listed above; it may not resolve a package repository
during the build. In a clean pinned 2.11 tree mounted at the canonical
`STOCK_BUILD_ROOT` reconstructed from UTF-8 hex
`2f686f6d652f66656f732f7368617265732f73686172652f42697a4861776b`, it runs the locked compiler through
`waterbox/musl/wbox_configure.sh` and `wbox_build.sh`, then builds
`waterbox/emulibc`, producing `waterbox/sysroot` without reading host compiler,
linker, or C-library paths. Record literal SHA-256 values for the compiler
archive, compiler backend, linker, musl commit, configure/build scripts,
sysroot tree, `musl-clang` or `musl-gcc`, runtime archives, headers, zstd
executable, and canonical command/environment recipe in `toolchain-lock.json`.
`verify-inputs.sh` accepts only that complete locked set and prints a canonical
identity digest. Clang 18 and zstd 1.5.7 are explicit negative controls, not
accepted substitutes: clang 18 produces a 4,026,152-byte decompressed core with
SHA-256 `fa05369287a490b19a9a13e74aff69e3223c091adf320fb7fe1895abe6908269`,
and zstd 1.5.7 produces a 399,100-byte compressed stream. This is a prerequisite
evidence task: do not begin the observer patch until two fresh toolchain
preparations are byte-identical and the populated lock has independent review.

- [ ] **Step 6: Reproduce the unmodified stock core twice**

`reproduce-stock-core.sh` accepts only a verified clean source tree and locked
toolchain, builds unmodified `waterbox/emulibc` and `waterbox/gpgx` with the
stock `-mcmodel=large -fno-pic -fno-pie -O3 -flto` and linkscript recipe, and
compresses with the locked zstd command `--ultra -22 --threads=0`. Run it from
two fresh roots mounted at the historical path. For each run, require:

```text
decompressed: size=39558192 sha256=b4cc6dabc069a6f1b87790212d80f665d216e603aa4990955cc816d5bf98d218
              BuildID=7696adca7ad14b79 cmp(stock,generated)=0
compressed:   size=400161 sha256=c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12
              cmp(stock,generated)=0
```

Require the two generated files to compare equal to each other as well. Any
size/hash/BuildID/`cmp` difference blocks Task 7; do not patch a merely
deterministic but non-stock core.

From the exact 2.11 build workflow, also freeze every managed SDK/runtime/NuGet
input by immutable digest in `managed-toolchain-lock.json`; no live package
resolution is allowed. `reproduce-stock-managed.sh` builds the unmodified
managed solution twice in fresh historical-path roots. Each run must `cmp` and
hash exactly to stock `BizHawk.Emulation.Cores.dll`
`0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7`
and `BizHawk.Emulation.Common.dll`
`f20cd009f6f5b0a95bd47b66c48dc8de85afcd7ae0cc6aab3486baf55f501fb4`.
If this gate cannot pass, the observer distribution may not ship a patched
managed DLL and Task 8 must use the exact-hash reflection adapter.

- [ ] **Step 7: Prove the pinned source/toolchain and commit**

Run Step 2 again, then run source acquisition, native/managed toolchain
preparation, and both unmodified reproductions twice each into fresh ignored directories and
compare their canonical identity manifests byte-for-byte. Expected: both Java
metadata suites and the native source-lock suite pass, both stock `cmp` gates
pass, identities match, and the installed stock file hashes remain unchanged.

```bash
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java \
        CHANGELOG.md
git add -f tools/bizhawk-headless/native/gpgx-audio-observer/source-lock.json \
        tools/bizhawk-headless/native/gpgx-audio-observer/toolchain-lock.json \
        tools/bizhawk-headless/native/gpgx-audio-observer/managed-toolchain-lock.json \
        tools/bizhawk-headless/native/gpgx-audio-observer/fetch-source.sh \
        tools/bizhawk-headless/native/gpgx-audio-observer/prepare-toolchain.sh \
        tools/bizhawk-headless/native/gpgx-audio-observer/verify-inputs.sh \
        tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-core.sh \
        tools/bizhawk-headless/native/gpgx-audio-observer/reproduce-stock-managed.sh \
        tools/bizhawk-headless/tests/GpgxAudioObserverSourceLockTests.cs \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj \
        tools/bizhawk-headless/tests/TestMain.cs
git commit -m "feat(tools): pin exact GPGX observer identity"
```

### Task 7: Patch, build, and separately install the native buffered observer

Task 8's first real S2 bootstrap probe reopens this task before any observer
artifact has shipped. The packed ABI v1 struct sizes, offsets, exports, return
codes, and event kinds remain candidates, but the former always-zero hook flag
rule is replaced by the proof-arming flag below. The native patch and every
derived core/source/build/identity hash must therefore be rebuilt and rolled;
no previously frozen Task 7 observer artifact may satisfy Task 8.

**Files:**
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/0002-first-class-managed-adapter.patch`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/build-core.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/install-core.sh`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/artifact-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/README.md`
- Create: `tools/bizhawk-headless/tests/GpgxAudioObserverBuildTests.cs`
- Create: `tools/bizhawk-headless/src/Core/GpgxHost.AudioObserver.cs`
- Modify: `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- Modify: `tools/bizhawk-headless/src/Core/GpgxAudioObserverAdapter.cs`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Consumes: Task 6's exact detached source and locked Waterbox toolchain.
- Produces: observer-patched `gpgx.wbx.zst`, uncompressed core, complete corresponding source bundle, notices, build log, and identity manifest in a create-new isolated installation.
- Produces: `gpgx_audio_trace_*` exports only; it changes no emulation result or cycle count.
- Produces: a narrow managed-reflection identity over
  `GpgxHost.AudioObserver.cs` and `GpgxAudioObserverAdapter.cs`; it does not
  freeze the Task 8-owned remainder of `GpgxHost.cs`.

Do not freeze an event ABI from this plan alone. First compile the layout probe
in Step 1 with Task 6's exact clang/LLD/sysroot and inspect the linked Waterbox
ELF. If and only if its packing, alignment, endianness, `.invis` placement, and
departure-only pointer marshalling proofs pass, freeze these candidates as ABI v1
in the patch, source lock, tests, and Java metadata vectors:

```c
uint32_t gpgx_audio_trace_abi_version(void);       /* 1 after feasibility */
uint32_t gpgx_audio_trace_event_size(void);        /* 32 */
uint32_t gpgx_audio_trace_capacity(void);          /* 65536 */
int32_t gpgx_audio_trace_configure(
    const struct gpgx_audio_trace_config_v1 *config,
    const uint8_t *z80_pc_mask,
    const struct gpgx_audio_service_kind_v1 *kinds,
    const struct gpgx_audio_service_hook_v1 *hooks,
    const struct gpgx_audio_snapshot_range_v1 *ranges);
int32_t gpgx_audio_trace_begin_frame(void);
int32_t gpgx_audio_trace_end_frame(void);
int32_t gpgx_audio_trace_event_count(
    uint32_t *required_count, uint32_t *overflow_count);
int32_t gpgx_audio_trace_drain(
    struct gpgx_audio_trace_event *out, uint32_t out_capacity,
    uint32_t *out_count);
int32_t gpgx_audio_trace_abort_frame(void);
int32_t gpgx_audio_trace_disable(void);
```

The feasibility probe freezes these exact packed little-endian layouts:

```text
AudioTraceConfigV1 (64 bytes)
  0:u32 magic=0x31544147       4:u16 abi_version=1
  6:u16 struct_size=64         8:u16 hook_size=32
 10:u16 range_size=16         12:u16 event_size=32
 14:u8 max_depth=8            15:u8 max_opcode_bytes=8
 16:u16 reset_service_kind    18:u16 max_continuation_frames(0..255)
 20:u32 flags                 24:u32 watch_mask_bytes=8192
 28:u32 hook_count(1..512)    32:u32 range_count(1..128)
 36:u32 snapshot_bytes_total  40:u32 event_capacity=65536
 44:u32 max_service_tokens_per_frame=65535
 48:u16 kind_size=16          50:u16 kind_count(1..255)
 52:u32 reserved[3]={0}

ServiceKindV1 (16 bytes)
  0:u8 kind_id                 1:u8 flags
  2:u16 cancellation_range_first
  4:u16 cancellation_range_count
  6:u8 continuation_frame_limit(0..255)
  7:u8 reserved=0
  8:u32 reserved[2]={0}

ServiceHookV1 (32 bytes)
  0:u16 hook_token             2:u8 action
  3:u8 cpu                     4:u32 pc
  8:u8 service_kind            9:u8 expected_active_kind
 10:u8 flags                  11:u8 opcode_length(1..8)
 12:u16 range_first           14:u16 range_count
 16:u8 opcode[8]
 24:u8 reserved[8]={0}

SnapshotRangeV1 (16 bytes)
  0:u16 range_id               2:u16 start
  4:u16 length                 6:u16 flags=0
  8:u32 reserved[2]={0}

AudioTraceEventV1 (32 bytes)
  0:u32 ordinal                4:u16 service_token
  6:u16 parent_token           8:u32 pc
 12:u16 subject               14:u16 offset
 16:u8 kind                   17:u8 service_kind
 18:u8 depth                  19:u8 source_cpu
 20:u8 payload_length         21:u8 value
 22:u8 flags                  23:u8 reserved=0
 24:u8 payload[8]
```

ABI v2 defines config flag bit `0x01=PREPUBLICATION_EPOCH` and adds the
one-shot `gpgx_audio_trace_begin_publication_epoch` departure. A flagged
session validates and drains from configuration without aging continuations;
the departure is accepted only after a successful empty READY drain, preserves
the active stack/tokens, arm proof and YM latches, resets carried ages, and
enables normal publication/aging. Duplicate, in-frame, unconfigured, undrained,
or faulted calls fail closed. ABI-v1 and zero-flag ABI-v2 configs keep immediate
publication behavior.

Because feasibility precedes ABI publication, the 64-byte header's offsets
48-51 now carry kind-table size/count instead of the discarded provisional
reset-range pair; the resulting five-struct protocol is the only candidate
that may be frozen as ABI v1.

Static assertions fix every size/offset, `CHAR_BIT==8`, integer widths, and
little endian. Hook actions are `PUSH_BEGIN`, `POP_END_AT_PC`,
`POP_END_FALLTHROUGH`, and atomic `TAIL_POP_PUSH`. Hook flag bit
`0x01=ARM_Z80_PROOFS_ON_COMPLETION` is the only defined hook flag;
kind flags are `0x01=TYPED_ASYNC`, `0x02=ALLOW_FRAME_CONTINUATION`, and
`0x04=ALLOW_CHILD_SERVICES`; CPU is `Z80` or `M68K`. A push
has nonzero new kind, required parent kind in `expected_active_kind` (zero only
for a root), and zero ranges; a pop has zero new kind,
nonzero expected kind and nonempty ranges; a tail has both kinds and snapshots
and ends the popped service before beginning the new one at that PC. Hook tokens and range IDs are
unique; kind entries are sorted by unique nonzero ID, and those IDs are shared
by their matching hooks. Configure builds an internal 256-entry lookup; every
push/tail-created token copies a valid kind ID. Opcode
bytes beyond length are zero,
range indices are contiguous/in bounds, and every completion or kind-owned
cancellation snapshots at most 8,192 nonoverlapping in-RAM bytes. Total
declared snapshot bytes include every hook reference plus every kind
cancellation reference and are bounded by 1,048,576. The Z80 mask is exactly the union of Z80 hook PCs; M68K hooks use
the pinned execute boundary. Z80 `pc` is at most `$FFFF`; M68K `pc` is full
24-bit with its high byte zero and `pc+opcode_length <= 0x1000000`. `configure` validates overflow-safe products/sums,
then copies header/mask/kind/hook/range arrays immediately into `.invis` and
retains no host or interior pointer. Configure validates proof structure only:
pinned `gen_reset` zeroes ZRAM before the game uploads its driver. Z80 proofs
require `pc + opcode_length <= 0x2000`. M68K hooks always compare bounded
24-bit bytes at execution only through side-effect-free direct mapped pages; a
custom-handler or null page fails proof, and the hook never performs an extra
`M68K BUS` read.

Configuration accepts zero or exactly one
`ARM_Z80_PROOFS_ON_COMPLETION` bit across the hook table. Zero preserves
immediate-armed behavior for synthetic/legacy manifests. One makes
`configure` begin Z80-unarmed and is legal only on an M68K
`POP_END_AT_PC`/`POP_END_FALLTHROUGH` that ends a source-cited root
`SoundDriverLoad` service. The hook has `service_kind=0` and names the upload
kind in `expected_active_kind`. That kind has exactly one M68K root push, no nested
or tail entry, must be the sole active token at completion, and all upload
paths converge there. Its canonical completion/cancellation slice covers
exactly final ZRAM `[0x0000,0x2000)` with no gap/overlap. A second flag, an
unknown flag bit, flag on a Z80/push/tail hook, non-root completion, completion
bypass, or incomplete upload slice fails.

Before arming, watched Z80 PC visits perform no proof, stack action, or event.
M68K/reset service hooks and all token-owned FM/PSG writes remain live. At the
flagged completion, emit the ordinary full-ZRAM snapshot/`SERVICE_END`, compare
every configured Z80 hook's exact bytes against final ZRAM synchronously, and
atomically arm only if the complete set matches before later CPU execution.
Mismatch remains sticky, leaves the observer unarmed, and makes `end_frame`
fail closed. Thereafter each selected Z80 hook revalidates synchronously.
Unarmed frames otherwise end/drain successfully; there is no new event kind or
phase, and the upload service completion is the semantic arm evidence.

Constants are exact: hook actions are 1-4 in listed order; hook CPU is
`1=Z80`, `2=M68K`; hook flag bit `0x01` has the meaning above and all others
are zero; kind flags are the three bits above;
event source is `0=NONE`, `1=Z80`, `2=M68K`,
`3=RESET`; event kinds are `1=SERVICE_BEGIN`, `2=SERVICE_END`, `3=FM_WRITE`,
`4=PSG_WRITE`, `5=SNAPSHOT_BEGIN`, `6=SNAPSHOT_CHUNK`, `7=SNAPSHOT_END`,
`8=RESET_BEGIN`, `9=RESET_END`. Reset event flag `0x01` means Power, zero means
Reset; `SERVICE_END` flag `0x02` means reset-cancelled. Flags are kind-specific
and every other event flag fails. Every kind owns a nonempty, contiguous,
in-bounds, nonoverlapping cancellation slice of at most 8,192 bytes.
`reset_service_kind` resolves to one kind and that slice is also its post-reset
snapshot. Kind continuation limit is zero without its allow flag and 1-255 with
it; the config maximum is zero when no kind continues and otherwise equals the
maximum kind limit. The byte age saturates at 255 and the attempted 256th
published continuation fails closed instead of wrapping. Legacy S2/S3K kinds
retain their reviewed limits of at most four. A child push requires
`ALLOW_CHILD_SERVICES` on its parent.
A push hook has zero range fields. Every pop/tail completion for the ended kind
must use exactly its kind-owned cancellation first/count, so alternate exits
share the canonical state slice even though their PCs/opcodes/actions differ.
Hooks are sorted by `(cpu, pc, hook_token)`. Multiple actions at one CPU/PC are
legal when `(cpu, pc, expected_active_kind)` is unique. On each instruction
visit native code snapshots the pre-action active kind, selects exactly one
matching alternative in deterministic hook-token order, and applies that
single action atomically. A new top kind cannot satisfy another same-PC
alternative during that visit. Zero or ambiguous matches are sticky-fatal.

Event kinds are `SERVICE_BEGIN`, `SERVICE_END`, `FM_WRITE`, `PSG_WRITE`,
`SNAPSHOT_BEGIN`, `SNAPSHOT_CHUNK`, `SNAPSHOT_END`, `RESET_BEGIN`, and
`RESET_END`. A depth-eight native stack allocates nonzero per-frame 16-bit
service tokens with parent/depth/profile kind. FM/PSG events carry exactly the
innermost token; parents exclude child writes. Snapshot payload is 1-8 bytes.
For ranges `i`, reserve exactly
`1 + 2 * range_count + sum(ceil(length_i / 8))` records. One 8 KiB range uses
1,027 records; the global bound is 1,393 for 128 nonempty ranges totaling 8,192
bytes (1 end, 256 markers, at most 1,136 independently rounded chunks). Each
completion group is reserved and emitted atomically in manifest/range/offset
order. Stack underflow/overflow,
kind/parent/token mismatch, token wrap, or orphan writes fail capture. Stack is
empty at frame end unless every remaining kind explicitly allows continuation.
The configured 1-4 frame budget measures execution exposure: at each
`begin_frame`, only the token that was the active top of stack across that frame
boundary increments its continuation counter. Suspended ancestors retain their
token/parent/depth and do not age while a bounded child owns execution. Every
open token is still validated at `end_frame`; a top token whose exposure exceeds
its kind limit is sticky-fatal. Child completion exposes its parent for the next
boundary, and Reset/Power cancels the whole stack without incrementing any
continuation counter.
The reset service reserves its exact snapshot range markers/chunks plus a
separate `RESET_END` (the same 1,393-record bound), in addition to
`RESET_BEGIN` and every open token's independently reserved cancellation
completion group. Configure precomputes the exact completion reservation for
every kind. At reset entry native code overflow-checks, before emitting,
`current_event_count + 1 + sum(open_kind_completion_reserve) +
reset_kind_snapshot_end_reserve <= 65,536` using the current stack and actual
slices. At depth eight the structural upper
bound is `1 + 8 * 1,393 + 1,393 = 12,538` events. The reset snapshot/end tail
reservation remains protected from intervening reset chip writes; excess
writes saturate the omitted count and fail the frame rather than consuming it.
`RESET_BEGIN` carries its new root reset token/kind, zero parent/depth,
cancelled old depth in `subject`, and Reset/Power flag. After the configured
reset snapshot, `RESET_END` repeats that token/kind/flag; every reset chip
write uses `source_cpu=RESET` and that token.

Every event ordinal is its zero-based per-frame array index. Normal service
begin/end records put `hook_token` in `subject`; a reset-cancelled end instead
uses zero PC/subject, `source_cpu=RESET`, and flag `0x02`. FM writes put raw
`address & 3` in `subject` and data in `value`; PSG writes use `subject=0` and
data in `value`; both repeat the innermost token's parent/kind/depth and exact
issue source/PC. Each snapshot begin/chunk/end repeats the completed token's
ownership and completion source/PC and uses `subject=range_id`; begin has zero
offset/payload, chunks use gap-free range-relative offsets and 1-8 payload
bytes with zero tail, and end has `offset=range.length` and no payload.
Reset/cancellation snapshots use zero PC and `source_cpu=RESET`. `RESET_BEGIN`
uses zero PC and parent/depth, its cancelled depth in `subject`, and the action
flag; `RESET_END` uses zero subject. Every field not assigned for its kind is
zero. The fixed capacity prevents ordinal wrap, and there is no native-cycle
field.

The compile-time array is 2 MiB. It, the mask, copied kind/hook/range manifests,
256-entry kind lookup, per-kind reservations, proof-armed bit, counters, phase,
and configuration are static `ECL_INVISIBLE` objects in the ELF `.invis`
section, not `alloc_invisible` allocations. Tests lock `.invis` section flags,
offset/alignment, total added byte size, containment within Waterbox's limit,
and exclusion from savestate serialization.

The exact return codes remain `0=OK`, `-1=INVALID_ARGUMENT`,
`-2=INVALID_PHASE`, `-3=ABI_OR_CONFIG_LIMIT`, `-4=OUTPUT_CAPACITY`, and
`-5=OVERFLOW`. Phase is `DISABLED -> CONFIGURED -> RECORDING -> READY ->
CONFIGURED`. `configure` is legal only in `DISABLED`, requires all five input
pointers non-null, copies them, and enters `CONFIGURED`; `begin_frame` is legal
only in `CONFIGURED`; and `end_frame` is legal only in `RECORDING`, validates
stack/continuation, and enters `READY` even on fail-closed runtime error.
`event_count` requires two non-null outputs and reports exact retained count
plus sticky saturated omitted-event count without copying. On overflow the
retained count is only the fixed-capacity prefix and is never published.
`drain` uses a reusable exact/bounded host buffer and is all-or-nothing. Zero
count accepts null output/capacity zero;
nonzero requires output. Too-small capacity sets `out_count=required`, copies
nothing, and remains `READY`; successful drain returns to `CONFIGURED`.
Overflow publishes nothing and clears through `abort_frame`. `abort_frame` is
legal only in `RECORDING` or `READY`, discards that frame for host/capture
failure, and returns to `CONFIGURED`. No normal frame, Reset, or Power path uses
it. `disable` is legal from every phase, clears copied observer state including
proof readiness, enters `DISABLED`, and is idempotent there. Proof readiness is
orthogonal to the phase machine: unarmed frames remain legal and there is no
new event kind. A drained flagged upload `SERVICE_END` is the sole semantic arm
evidence.
Stack/continuation mismatch makes `end_frame` return `-3` in `READY`; the host
records diagnostic counts then calls `abort_frame` without semantic drain.

- [ ] **Step 1: Prove feasibility, then write failing patch/build/publication tests**

Compile/link all five candidate structs plus a 65,536-entry static event array
with the exact stock-reproducing toolchain. Use `readelf`, `objdump`, and C/C#
departure-only marshal round trips to prove every size/offset, little-endian
byte vector, 2 MiB alignment/section placement, `.invis` size headroom, and
copy-in/copy-out behavior. Mutate/free the host arrays immediately after
`configure` and prove native configuration is unchanged. If any property fails,
stop and amend the design before assigning ABI version 1.

Using Task 6's managed reproduction evidence, compile the minimal first-class
adapter patch and run the same config/event marshal vectors through it. Select
it only if those vectors and stock/patched-disabled identity pass; otherwise
record the failed selection evidence, do not publish its DLL, and freeze the
exact-hash reflection adapter as the observer distribution's sole adapter.
For the reflection selection, first make `GpgxHost` partial and move only the
stable `_elf`/Waterbox adapter-construction bridge into
`GpgxHost.AudioObserver.cs`; leave lifecycle, game selection, CLI, and capture
orchestration in `GpgxHost.cs`. Compile the new partial in both non-SDK
projects. Freeze individual SHA-256 values for the dedicated partial and
`GpgxAudioObserverAdapter.cs`, and assert that neither `artifact-lock.json` nor
the installation identity contains a whole-file `GpgxHost.cs` hash. A fixture
mutation confined to a Task 8-owned method in `GpgxHost.cs` must leave the Task
7 observer identity unchanged while changing the complete compiled harness
hash; a mutation to either frozen bridge source must invalidate Task 7.

Audit exact pinned source before choosing hook sites. Enumerate all five
`GenesisFMSoundChipType` values (`MAME_YM2612`, `MAME_ASIC_YM3438`,
`MAME_Enhanced_YM3438`, `Nuked_YM2612`, `Nuked_YM3438`) and every Genesis
M68K/Z80 call path that reaches the selected `fm_write` pointer. Audit all PSG
issue paths, including M68K, Z80, and banked Z80, and prove the selected common
boundary precedes device mutation. If one boundary cannot cover them, patch and
test every issue site. If that cannot be proven, narrow the settings and
metadata to `MAME_YM2612`; never claim generic coverage. Include selectors
0/1/2/3, PSG, and address-latch `$2A` plus repeated data writes. Audit the exact
scheduler call sites and require an explicit scoped issue-source enum around
each Z80/M68K execution plus an instruction-start PC latch at each CPU's
prefetch boundary. Tests prove the common dispatch records that enum and the
latched 16-bit Z80 or full 24-bit M68K instruction-start PC, and uses zero for
reset; it may never infer source from PC contents or read the already-advanced
current PC at dispatch. A synthetic instruction with operand bytes before its
chip write asserts the literal start PC and would fail if dispatch-time PC were
used. M68K proof tests accept direct mapped bytes and fail custom-handler/null
pages without issuing an extra bus read.

Assert a clean exact-2.11 tree and completed Task 6 stock `cmp` evidence are
required; patch drift and dirty input fail; symbols/version/size/kinds/offsets/
reserved bytes are exact; null pointers, 8,191/8,193-byte masks, too many
or zero kinds/hooks/ranges, wrong magic/version/struct sizes/depth/capacity, overflowed
products/sums/range ends, too many kinds/hooks/ranges, mask/hook-bit mismatch,
overlapping/out-of-RAM completion ranges, duplicate/zero tokens/kinds/IDs,
duplicate `(cpu,pc,expected-kind)` guards, unsorted same-PC alternatives, unknown
CPU/action/kind flags, unknown hook-flag bits or reserved/unused opcode bytes, a
missing reset-kind entry, invalid continuation/child policy, empty/overlapping/
out-of-bounds kind cancellation slices, or a pop/tail slice different from its
ended kind's canonical slice fail. Z80
`pc+opcode_length > 0x2000`, M68K `pc+opcode_length > 0x1000000`, bad range
indices, and malformed proof bytes fail.
Runtime proof mismatch is sticky and fails `end_frame`. Null is accepted only for the zero-count
drain output defined above; configure/event-count/out-count pointers never are.
Add table-validation negatives for two arm flags; an arm flag on Z80, push, or
tail; nested/tail-entered/multiply-entered upload kinds; a non-root flagged
completion; upload exits that bypass the flag; and a canonical upload range
union other than exact `[0x0000,0x2000)`. A zero-arm-flag manifest must begin
immediate-armed. An armed synthetic manifest must retain the existing per-hook
proof failure. An arming synthetic manifest must ignore repeated watched-PC
visits over zero-filled ZRAM before upload, retain M68K/reset services and all
token-owned FM/PSG writes, then scan every distinct configured Z80 proof and
arm atomically at its sole flagged completion. Mutate the last proof in the
scan to prove there is no partial arm, and assert an unarmed error-free
`end_frame` succeeds. No test may arm from a frame number, first matching byte,
or managed reconfiguration.
Use test-only native selftest instrumentation to assert the exact number of
ignored pre-arm watched-PC visits, exactly one complete proof-set scan at each
flagged completion, atomic readiness only after the final proof succeeds, and
zero partial arm on mismatch. Those counters are not exports or event kinds,
are unavailable in the installed production core, and cannot enter canonical
capture/capability metadata; the production ABI remains the ten exports above.
Assert `SERVICE_BEGIN` occurs after IRQ admission
and before entry opcode fetch, and each possible completion PC produces a
synchronous complete snapshot/end group. Exercise multiple exits, fallthrough,
same-PC guarded alternatives (including S3K `$0121` outer-to-music and
music-to-music tails), exactly-one selection from the pre-action kind,
ambiguity failure, tail-pop/push, depth eight, depth-nine failure,
token-parent/depth fields,
continuation bounds, and orphan-write failure. A watched non-hook PC emits no
service group. Assert nested outer/inner services with a state mutation yield
distinct ordered snapshots and exclusive write ownership. Assert typed
asynchronous DAC/DPCM/SEGA-PCM hooks open and complete once per inner
loop iteration, including when nested under VInt/update; a manifest that wraps
the whole long-lived routine, exceeds its bound, or leaves a write orphaned
fails. Assert observer-disabled execution takes no trace branch after its
enable check.

Construct a depth-eight open stack whose kinds include multiple candidate exit
hooks. Trigger Reset and Power before any exit is selected and assert exact
innermost-to-outermost bytes from each kind-owned cancellation slice, followed
by its reset-cancelled end, independent of exit order. Exercise the exact
checked reset reservation sum, the 12,538-event structural bound, protected
reset-tail reservation, and fail-closed capacity/overflow behavior without a
partial cancellation group.
For an armed manifest, both actions must clear readiness before any post-reset
Z80 instruction while preserving reset/cancellation/write events. Exercise a
reupload completion later in the same native frame and in a later frame; both
must scan the complete proof set, rearm exactly once without a new event, and
capture the first subsequent Z80 service. Before rearm, watched zero/partial
ZRAM visits are ignored but any orphan chip write still fails. The
zero-arm-flag fixture remains immediate-armed across reset.

Exercise every phase transition, return code, `end_frame`, count query,
zero-count/null drain, reusable exact-count buffer, retryable too-small drain
with required count/no transition, `abort_frame`, idempotent `disable`, atomic
snapshot reservation, ordinal continuity/no wrap, and forced overflow with
saturating omitted count. Assert the managed lane never copies the unconditional
2 MiB capacity.
Inspect the patched ELF to prove all observer state is static in `.invis`, its
exact added size/alignment fits, no symbol lands in the savestated section, and
save/reset tests prove exclusion.

Assert `install-core.sh` requires an absent destination, copies rather than
hard-links/symlinks stock files, installs only in the supplied ignored root,
publishes the canonical complete source/patch/licenses with the binary, and
verifies the stock distribution hashes before and after successful/failed builds.
The publication guard requires exact pinned copies of BizHawk `LICENSE`, GPGX
`LICENSE.txt`, musl `COPYRIGHT`, zstd `LICENSE`, LLVM/Debian notices, and every
component license named by GPGX. It verifies the GPGX non-commercial clause,
complete-corresponding-source condition, copyright notices, and full warranty/
liability disclaimer are present verbatim; a binary-only or summarized-license
publication fails.
Hand-add and register the test in the non-SDK project/runner. Test builds use
`TestScratch` under the harness `.scratch/`; only an explicitly requested final
observer installation publishes beneath `target/audio-parity/native/`.

- [ ] **Step 2: Run RED**

```bash
tools/bizhawk-headless/test.sh --filter GpgxAudioObserverBuildTests
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/build-core.sh
bash -n tools/bizhawk-headless/native/gpgx-audio-observer/install-core.sh
```

- [ ] **Step 3: Freeze the proven ABI and implement the native service log**

Patch the pinned Z80 interpreter at the current 16-bit PC boundary after IRQ
handling and before `R++`/`ROP()`, and consume M68K hooks at the pinned execute
boundary. Apply verified push/pop/fallthrough/tail actions to the depth-eight
token stack. On every pop, reserve the complete snapshot/end group, copy Z80
RAM immediately into ordered eight-byte chunks, and append the tokenized end.
Copy the validated kind table into `.invis`, build its 256-entry lookup, and
store the resolved kind ID on every pushed/tail-created token. Normal exits use
the same canonical kind slice used for cancellation; alternate exit hooks
cannot substitute another slice.
Copy the validated arm-hook identity and proof readiness into `.invis` too.
With an arm hook, skip all Z80 hook proof/action/emission while unarmed. At the
sole root M68K upload completion, emit the ordinary complete-ZRAM service
boundary, scan the full configured Z80 proof table against final ZRAM, and set
readiness only after the final comparison succeeds. Keep the transition at
that instruction boundary and emit no additional event. Without an arm hook,
initialize immediate-armed behavior exactly as before. Never use a frame
counter, a managed RAM sample, or a first-byte/first-hook lazy match.
Nested services and repeated children remain separate; parent writes exclude
child writes. Patch the audited logical Mega Drive FM and PSG boundary/boundaries
before device mutation so all proven M68K/Z80 paths append to the same array;
do not hook only
`YM2612_Write`, only `z80_memory_w`, or inferred `LD` instructions. The event
log returns no replacement values and calls no managed or gameplay owner.

At scheduler entry set `ISSUE_Z80` around Z80 execution and `ISSUE_M68K` around
M68K execution, restore the prior enum on every exit, and set `ISSUE_RESET`
inside reset. At the Z80 boundary after IRQ admission/before fetch and the
M68K execute/prefetch boundary, latch the current instruction-start PC before
any opcode or operand consumption. FM/PSG events copy that source, latched PC,
and the innermost nonzero token; Z80 uses the latched 16-bit PC, M68K the
latched full 24-bit PC, and reset zero. Never read the dispatch-time current PC.
Orphan/unknown-source writes fail unless verified
hooks opened a bounded `TYPED_ASYNC` service for the current inner
DAC/DPCM/SEGA-PCM sample/loop iteration. Such an iteration may nest under
VInt/update, but a whole long-lived routine or playback loop may not be one
service. Preserve every raw `$2A` data byte.

Patch exact `gpgx_reset` wrapper entry while a frame is `RECORDING`. Resolve the
reset kind and every open token's kind, then checked-reserve before emitting
`current_event_count + 1 + sum(open_kind_completion_reserve) +
reset_kind_snapshot_end_reserve <= 65,536`.
For an arming manifest, atomically clear proof readiness at reset entry before
any post-reset Z80 execution; do not disable M68K/reset service or chip-write
observation.
Append `RESET_BEGIN` with Reset-vs-Power flag, canceled pre-reset depth, and a
root token allocated through the normal nonzero uniqueness/wrap checks. Append
each open token's exact kind-owned cancellation snapshot plus reset-cancelled
`SERVICE_END` in deterministic innermost-to-outermost order, independent of
its unselected alternate exit hooks, then clear the observer stack/source
diagnostics without clearing the event array/ordinal/phase. Protect the
reserved reset snapshot/`RESET_END` tail so intervening reset writes cannot
consume it; excess writes set saturated overflow. Clear host YM latches when
`RESET_BEGIN` is decoded and push the configured root reset token. Attribute
all reset writes to it under `ISSUE_RESET`, append its kind-owned synchronous
post-reset snapshot and `RESET_END`, then let the same
`gpgx_advance` invocation continue. Do not call `abort_frame`, start a second
native frame, or drop pre/post-reset events.
Reset before configuration emits nothing. After configuration, `GpgxHost`
issues Reset/Power only inside `begin_frame`/`end_frame`. A native reset entered
in `CONFIGURED` or `READY` emits nothing, sets a sticky invalid-observer
condition, and makes every subsequent status-returning departure except
`disable` (`configure`, `begin_frame`, `end_frame`, `event_count`, `drain`, and
`abort_frame`) return `-3`; `disable` clears it. The unsigned `abi_version`,
`event_size`, and `capacity` capability queries remain constant. The next
flagged upload completion after a legal in-frame reset performs the same
complete scan and rearm in that or a later frame. A no-arm-flag manifest
remains immediate-armed.
Test both illegal phases independently: no reset record may appear, the first
subsequent `begin_frame`/`event_count` boundary reports `-3`, every later API
with a status return except `disable` remains failed, the three unsigned
capability queries retain their constants, and disable clears the sticky
condition. A host-boundary test must prove every supported `GpgxHost` Reset and
Power call is enclosed by that row's `begin_frame`/`end_frame`. The host
proof-arm epoch is
advanced only while decoding a successfully drained `RESET_BEGIN` or flagged
upload `SERVICE_END`; no native readiness query, phase inference, or reset-call
side channel may alter it.

Implement exact copy-in config ABI, stack/continuation rules, packing, phase
machine, return/null/count-query rules, all-or-nothing capacity behavior,
saturated overflow, ordinal/token bounds, `abort_frame`, and idempotent
`disable`. Load cannot rely on savestate restoration of static `ECL_INVISIBLE`
state. At an empty drained boundary, persist the host's two YM latches and
host-only proof-arm epoch alongside its own core state. A same-profile load is
legal only when the saved host epoch equals the current host epoch before core
state is mutated; there is no native epoch query. It leaves native
configuration/readiness intact and restores those latches.
Cross-epoch/cross-core loads fail closed. Do not disable/reconfigure after load,
which would strand an already-uploaded loaded state unarmed. Reset/Power use
the in-frame reset protocol above, never abort.

- [ ] **Step 4: Implement deterministic build and isolated installation**

The build script verifies Task 6's locks, applies the native patch with
`git apply --check`, builds pinned `waterbox/emulibc` and `waterbox/gpgx` with
the 2.11 static `-mcmodel=large -fno-pic -fno-pie -O3 -flto` recipe and custom
linkscript, and compresses using the locked zstd with
`--ultra -22 --threads=0`. It fixes `LC_ALL=C`, `TZ=UTC`, umask, exact
historical `STOCK_BUILD_ROOT` from Task 6's pinned hex value, and
`SOURCE_DATE_EPOCH`, and rejects ambient
compiler/linker flags. Fresh host directories are always mounted at that same
internal path.

If Step 1 selected the first-class adapter, apply the separate managed patch
only after the native build, build it with Task 6's locked managed toolchain,
and freeze its DLL/patch/toolchain hashes. If reflection was selected, exclude
the managed patch/DLL from the installation and identity rather than shipping
an unproved alternative. For reflection, migrate the stable bridge into
`GpgxHost.AudioObserver.cs`, make both declarations of `GpgxHost` partial, and
freeze only that source and `GpgxAudioObserverAdapter.cs` as the Task 7 managed
bridge identity. Replace any former whole-`GpgxHost.cs` lock field rather than
retaining it as an alias. Both installations retain copies of the untouched
stock managed assemblies as identity inputs, never overwrite them, and label
the selected adapter kind explicitly.

Treat every pre-amendment Task 7 artifact as stale even though the ABI number
and packed sizes remain 1/32. Update the patch and native selftests first, then
recompute `.invis` size/alignment, decompressed/compressed core hashes and
BuildID, patch hash, complete source-bundle hashes/manifests, build log and
recipe hashes, install identity, Java profile constants, canonical JSON vector,
managed bridge-source hashes, and publication report as one dependency cascade.
This arming rebuild also performs the partial-source migration and is Task 7's
single final identity roll. The installer must reject the old identity. No
hand-carried old hash may remain in tracked source or documentation. Ordinary
Task 8 edits to `GpgxHost.cs` roll only Task 8's complete harness executable
hash; they do not trigger another Task 7 artifact rebuild.

Build twice from fresh source/toolchain roots. Create the source bundle with
the exact frozen `GpgxHost.AudioObserver.cs` and
`GpgxAudioObserverAdapter.cs` inputs (plus the selected first-class managed
patch/source when applicable), then byte-sort repository-relative POSIX paths,
normalizing file modes to
`0644`/`0755`, directory modes to `0755`, uid/gid to zero, owner names to empty,
mtime to `SOURCE_DATE_EPOCH`, and tracked text to LF. Exclude `.git`, build,
sysroot, output, cache, log, and generated-binary paths. From the normalized
staging-tree root, pin the GNU tar executable/version/hash and, under
`LC_ALL=C TZ=UTC`, run exactly:

```bash
tar --format=posix --sort=name --mtime=@$SOURCE_DATE_EPOCH --owner=0 \
  --group=0 --numeric-owner --pax-option=delete=atime,delete=ctime \
  --no-recursion --verbatim-files-from --files-from=source-bundle.paths \
  -cf source-bundle.tar
zstd --ultra -22 --threads=0 --no-progress --force \
  source-bundle.tar -o source-bundle.tar.zst
```

The manifest has one normalized relative path per line and rejects absolute,
`..`, newline, or control-character entries. Lock both archive hashes and the
sorted path/mode manifest hash.

Freeze the literal patch hash,
compressed/uncompressed SHA-256, Waterbox BuildID, source-bundle hash, compiler/
linker/sysroot hashes, ABI/layout/capacity, build-log recipe digest, and the two
named managed bridge-source hashes in
`artifact-lock.json`; then rebuild twice more and require exact matches to that
independently reviewed lock. Install through a sibling staging directory into
an absent `target/audio-parity/native/<run>/install` and publish no binary
without its complete corresponding source and notices. The installation
manifest exposes stable logical IDs only; its absolute fresh path remains a
local validation input and is never copied into canonical capture metadata.
After the feasibility proof, update the Java profile and canonical JSON vectors
with the frozen ABI version/event size/capacity and artifact hashes; before that
change the S2/S3K native reference identities remain deliberately unavailable.

- [ ] **Step 5: Run GREEN and commit the native artifact recipe**

Run Steps 2 and 4. Expected: byte-identical clean builds, locked artifact
identity, proof-arm/bootstrap/reset-rearm and overflow selftests green, no
stock hash change, and no tracked binary or source bundle.

```bash
git add -f tools/bizhawk-headless/native/gpgx-audio-observer \
        tools/bizhawk-headless/src/Core/GpgxHost.cs \
        tools/bizhawk-headless/src/Core/GpgxHost.AudioObserver.cs \
        tools/bizhawk-headless/src/Core/GpgxAudioObserverAdapter.cs \
        tools/bizhawk-headless/tests/GpgxAudioObserverBuildTests.cs \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj \
        tools/bizhawk-headless/tests/TestMain.cs
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java
git commit -m "feat(tools): build a buffered GPGX audio observer"
```

### Task 8: Bind the native ABI and prove exact S2/S3K observation capability

**Files:**
- Modify: `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- Preserve unchanged: `tools/bizhawk-headless/src/Core/GpgxHost.AudioObserver.cs`
- Preserve unchanged: `tools/bizhawk-headless/src/Core/GpgxAudioObserverAdapter.cs`
- Create: `tools/bizhawk-headless/src/Audio/GpgxAudioTraceNative.cs`
- Create: `tools/bizhawk-headless/src/Audio/GpgxAudioTraceEvent.cs`
- Create: `tools/bizhawk-headless/src/Audio/CompleteRunAudioObserver.cs`
- Create: `tools/bizhawk-headless/src/Audio/IGpgxAudioTraceApi.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/tests/GpgxHostTests.cs`
- Create: `tools/bizhawk-headless/tests/GpgxAudioTraceNativeTests.cs`
- Create: `tools/bizhawk-headless/tests/CompleteRunAudioObserverTests.cs`
- Create: `tools/bizhawk-headless/tests/GpgxZ80AudioCapabilityTests.cs`
- Create: `tools/bizhawk-headless/fixtures/gpgx-audio-service-manifests-v1.json`
- Create: `tools/bizhawk-headless/fixtures/gpgx-audio-capability-v1.json`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Modify: `tools/bizhawk-headless/README.md`

**Interfaces:**
- Consumes: Task 7's separately installed locked observer distribution, exact
  stock managed controls, and the selected first-class or reflection adapter;
  for reflection this includes the two frozen bridge-source hashes, not a
  whole-file hash of Task 8-owned `GpgxHost.cs`.
- Produces: a game-neutral bounded native-frame collector and literal, independently frozen S2/S3K capability-count vectors.
- Preserves: existing `IGpgxHost.RegisterExecuteCallback(uint, Action)` on stock `M68K BUS` for S1; it adds no `Z80 BUS` enum or callback overload.

- [ ] **Step 1: Write failing binding and lifecycle tests**

Assert wrong stock/observer DLL/core/harness hash, missing selected adapter,
missing private `_elf` for the reflection variant, wrong runtime
`WaterboxHost`/resolver/adjuster types, missing exports, ABI/layout/capacity
mismatch, C/C# config/event marshal mismatch, nonzero reserved bytes,
noncontiguous ordinals/tokens, bad parent/depth/source, frame overlap, short
drain, overflow, orphan write, and dispose-after-use all fail publication.

Retain the conclusive stock negative proof: exact Genesis callback scopes are
only `M68K BUS`; `Z80 RAM` is exactly 8,192 bytes but there is no Genesis
`Z80 BUS`. Pin the managed IL showing the one-scope callback system, the native
source showing `bk_cpu_hook` handles only M68K hook kinds, and native
disassembly/export evidence showing no Z80 route. Separate 1,000-frame S2 and
S3K probes at `$0038`, `$4000`-`$4003`, and `$7F11` each report `exec38=0` and
five zero write counts only as a wrong-scope negative control. Any future scope,
source, disassembly, or count change is an identity/API change requiring
redesign, not a reason to relabel the callback.

Add the proven S2 bootstrap regression before changing the native patch.
Replay movie rows/frames 0-3 with the real manifest and assert that zero-filled
ZRAM visits diagnostically observed at future driver PCs `$0038`, `$00E7`,
`$010F`, `$0178`, and `$01B0` occur before M68K `SoundDriverLoad` at `$0EC000`.
Preserve that measured set exactly: it observed `$0178`, not `$017A`, and is
not the canonical watch-mask inventory. Its canonical watched members do not
proof, act, or emit while unarmed; `$0178` does not emit because it is excluded
from the mask. Do not claim a row-3 `$017A` visit without evidence. RED is
the frozen eager-proof core returning `-3` from `EndFrame` on row 3. GREEN
requires successful drain of that row, ordered retention of all preceding
reset/M68K/chip events and both YM latch transitions, one complete-ZRAM upload
service at its source-cited completion, and the first later armed Z80 service.
Add the equivalent locked-ROM S3K bootstrap test after its exact M68K upload
entry/completion is independently cited. A managed configure delayed to the
upload row, movie-row gate, frame-end RAM check, or first-matching-hook gate is
an explicit failing negative.

Assert configuration completes before the first `FrameAdvance`. Every bootstrap
and capture row calls `BeginFrame`, one `FrameAdvance`, `EndFrame`,
`EventCount`, then one successful all-or-nothing `Drain`. Count zero uses null/
zero output; nonzero rents/reuses only the required bounded array. Too-small
drain exposes required count, copies nothing, remains READY, and succeeds on
retry. Bootstrap events are structurally validated and update both YM address
latches; only semantic emission is suppressed before the epoch. Reset and Power
inside `FrameAdvance` preserve the same recording frame, clear latches at
`RESET_BEGIN`, and do not invoke abort/reconfigure or a second frame lifecycle.
Prove `GpgxHost` cannot issue either action outside that recording interval.
Direct native reset probes in both `CONFIGURED` and `READY` must emit nothing,
set the sticky invalid condition, make the next and subsequent status-returning
observer boundaries except `disable` return `-3`, leave `abi_version`,
`event_size`, and `capacity` constant, and recover only through `disable`. The host arm epoch
must change only when the corresponding `RESET_BEGIN` or flagged upload
`SERVICE_END` has been successfully drained; there is no native readiness or
epoch query.
Save/load is allowed only at an empty drained boundary. Each
stock/disabled/enabled core saves and reloads its own
state; tests never compare raw state bytes or cross-load. Because `.invis` is
excluded, the host checkpoint includes both YM latches and its host-only
proof-arm epoch. Load requires the same configured core/profile and equality
between the saved host epoch and current host epoch before mutating core state,
retains native `.invis` configuration/readiness, and restores the latches;
cross-epoch/cross-core load fails. It must not disable/reconfigure and
strand a loaded post-upload core unarmed. Callback tests must still prove the
M68K path unchanged.
Hand-add every new production `.cs` to both non-SDK projects, every test `.cs`
to the test project, and every test class to `TestMain.BuildRegistry()`.
Register every case that constructs `GpgxHost` through `RegisterSerial` because
two live Waterbox cores in one process are unsupported. Gate scratch uses
`TestScratch`; never use `Path.GetTempPath()`.

- [ ] **Step 2: Run RED**

```bash
tools/bizhawk-headless/test.sh --filter GpgxAudioTraceNativeTests
tools/bizhawk-headless/test.sh --filter CompleteRunAudioObserverTests
tools/bizhawk-headless/test.sh --filter GpgxHostTests
S2_ROM_PATH="Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
tools/bizhawk-headless/test.sh --filter GpgxZ80AudioCapabilityTests --jobs 1
```

- [ ] **Step 3: Implement the verified managed boundary and collector**

Expose one `IGpgxAudioTraceApi` to the collector. Prefer a minimal first-class
managed BizHawk adapter in the dedicated observer distribution only after its
pinned managed toolchain builds unmodified 2.11 DLLs twice and `cmp`s them to
stock. Freeze its patch/source/toolchain/patched-DLL hashes and expose only
typed Waterbox departure calls—not a generic Z80 scope. If that prerequisite
cannot pass, use the verified fallback: reflect `GPGX._elf` once, require public
`WaterboxHost`/`IImportResolver`/`ICallbackAdjuster`, bind a departure-only
`BizInvoker`, and wrap calls in `_elf.EnterExit()`. Both variants allocate no
per-event callback slot, fail before frame zero on identity drift, and publish
stable adapter kind plus hashes; the stock installation remains untouched.
For reflection, consume Task 7's frozen `GpgxHost.AudioObserver.cs` and
`GpgxAudioObserverAdapter.cs` unchanged. Task 8 may extend the other
`GpgxHost.cs` partial for lifecycle/game-name/capture behavior without rolling
Task 7; its complete compiled harness executable SHA-256 is the identity for
those combined bytes. A required change to either frozen bridge source reopens
Task 7 instead of being hidden in the Task 8 harness roll.

`CompleteRunAudioObserver` accepts an 8,192-byte profile mask plus bounded
kind/service/range manifests and structurally validates every source-cited proof
before configuring prior to the first frame. It validates the sole optional arm
flag, root M68K upload begin/completion, and exact full-ZRAM canonical slice.
M68K hooks verify full-24-bit side-effect-free direct mapped bytes at execution,
rejecting custom-handler/null pages without an extra bus read. With the flag,
pre-upload Z80 watched-PC visits are native-suppressed while reset/M68K/chip
records still drain; the flagged upload completion proves the complete Z80
hook set and moves the host readiness mirror to armed. Only after successful
drain does the host increment its monotonic checkpoint-only arm epoch for each
decoded `RESET_BEGIN` transition and flagged completion. Later Z80 hooks
revalidate normally. `RESET_BEGIN` moves that mirror to unarmed before later
Z80 work; the next flagged upload completion rearms it. With no flag, the host
mirrors immediate-armed legacy behavior. It never delays `Configure`, scans
ZRAM at frame end, or infers readiness from a frame/PC coincidence.
It reconstructs the
token/parent/depth stack and ordered snapshot groups, including nested children,
tail composition, typed async services, reset cancellation with
`RESET_CANCELLED` boundary state, and bounded
cross-frame continuation. It reconstructs each reset begin/end pair as one
`COMPLETED` reset `DriverService`; the separate lifecycle record references its
canonical service ordinal and never duplicates its writes. The buffered-native
sidecar alone retains the native reset token and proves its projection to that
ordinal. It rejects missing/misordered chunks, range gaps/
overlap, stack/kind/token/source mismatch, partial state, bad ordinals, or
unexpected PCs. It preserves one raw global event stream while
maintaining separate port-0 and port-1 YM address latches across every drained
bootstrap/capture frame and pairs data writes into `(port, register, value)`,
including every `$2A` byte. No sorting, frame-end RAM inference, `LD`
interposition, or M68K/Z80 scope relabeling is permitted.

Each chip event is attached exactly once to its innermost token. Parents retain
only events outside child lifetimes. Completed canonical `DriverService`
records sort by global begin coordinate, so an outer service precedes nested
children even if it completes later; per-event native ordinals remain unchanged
for global bus comparison. Flattening validates that every raw chip ordinal
appears exactly once and never duplicates child writes in its parent.

Expose one immutable capture identity containing the Task 6 source/toolchain
digest, Task 7 patch/artifact/source-bundle hashes and BuildID, ABI/layout/
capacity, stock managed DLL and `libwaterboxhost.so` hashes, stable logical
installation/core IDs, selected adapter kind and managed-patch/DLL hash when
applicable, harness executable SHA-256, PC-watch-mask and
service-manifest SHA-256, enabled state, and later capability/count/occupancy/
overflow proof. The hashed service manifest alone owns the arm-hook token,
kind, and policy; do not duplicate those fields in the Java capture schema or
immutable identity. The capability artifact records observed upload/arm
service evidence against that manifest digest. The adapter validates the absolute installation path locally,
but metadata must never serialize it. Metadata publication copies the logical
identity and hashes exactly; observer strings cannot replace artifact hashes.

- [ ] **Step 4: Prove observer-disabled and observer-enabled emulation identity**

Replay the same fixed S2 and S3K BK2 prefixes through stock GPGX, patched GPGX
observer distribution disabled (including its selected managed adapter), and
the same distribution enabled. Require identical deterministic video and
audio PCM hashes, lag flags, 68K/Z80 RAM, CPU-register checkpoints, reset path,
and post-save/load continuation. Each lane creates and reloads only its own
savestate; compare outputs/checkpoints after load, never savestate bytes, and
never load one core's bytes in another. Save/restore the enabled lane's two YM
latches and host-only arm epoch, retain native `.invis` configuration/readiness,
and prove the first post-load data write and Z80 hook decode exactly. A
deliberately different saved/current host epoch must reject load before core
state mutation or advancing. Repeat each lane and
require byte identity. Force the test observer's tiny-capacity variant to
overflow and assert capture aborts without a partial frame.

Add fixed Reset and Power BK2-row fixtures. Each advances exactly one movie
row and proves the single native frame contains, in order, any pre-reset events,
`RESET_BEGIN` with the correct action/canceled depth, reset-service FM/PSG
writes with `source_cpu=RESET`, synchronous post-reset snapshot, `RESET_END`,
and subsequent same-call `gpgx_advance` events. Assert no event is dropped or
duplicated. At least one fixture resets with nested services open and proves
their exact kind-owned synchronous bytes in innermost-to-outermost order and
reset-cancelled `SERVICE_END` records retain all pre-reset writes and become
`RESET_CANCELLED` canonical services. The open kinds must each have multiple
candidate exit hooks; cancellation remains unchanged when the candidate set is
reordered because no exit owns the slice.
Assert host cadence remains one row, both latches reset exactly once, and
`AbortFrame` is never called.
For both games, extend each action through its ROM reupload: readiness becomes
unarmed at `RESET_BEGIN`; watched Z80 visits before upload emit nothing; reset
and M68K upload writes remain ordered/token-owned and update the correct latch;
the sole flagged upload completion carries the exact full-ZRAM snapshot and
rearms after complete-set proof; and the first later Z80 service is captured.
Cover reupload later in the reset row and in a later row. Corrupt one late hook
proof to assert sticky `EndFrame=-3`, no semantic drain, and no partial rearm.

- [ ] **Step 5: Freeze and verify real S2/S3K capability counts**

Use S2 REV01 SHA-1 `8bca5dcef1af3e00098666fd892dc1c2a76333f9`
with `sonic-2-sonic-tails-complete-emeralds.bk2`, SHA-256
`e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`,
and 259,590 input rows. Use locked-on S3K SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6` with
`s3k-knuckles-complete-superemeralds.bk2`, SHA-256
`aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`,
and 434,417 input rows. The capability test records rows `[0,1000)` and fails
identity before frame zero if the logical name, hash, full row count, or window
differs; another locked-on S3K movie is not an interchangeable capability input.
Verify these source anchors before the epoch:

```text
S2:  zVInt=$0038 zUpdateEverything=$0051 zUpdateMusic=$0110
S3K: zVInt=$0038 zUpdateEverything=$011B zUpdateMusic=$0121
Both: FM=$4000-$4003 PSG=$7F11
```

Populate `gpgx-audio-service-manifests-v1.json` with stable hook tokens/service
kinds/range IDs, CPU/action, all entry and completion PCs (including alternate/
early exits, fallthrough, and tail composition), exact opcode bytes, source
file/label citations, async/continuation flags, half-open snapshot ranges, and a
short explanation proving state is final when each completion action fires.
For each game, include one M68K root `SoundDriverLoad` kind: a source-cited
entry before the first ZRAM write, one common source-cited completion after the
last write, no nested/tail entry or bypass, and a canonical range union exactly
`[0x0000,0x2000)`. Put `ARM_Z80_PROOFS_ON_COMPLETION` only on that completion.
S2 pins the observed `$0EC000` entry; S3K must independently derive its entry
and completion from the locked-on disassembly/ROM rather than copying S2.
Each kind entry declares its canonical cancellation slice and typed-async,
continuation, and child-service policy. Every completion hook for that kind
must cite the identical slice; alternate exits differ only in verified control
flow, not cancellation state inventory. The reset kind uses its slice for the
post-reset boundary.
At a shared PC, alternatives are keyed by the pre-action active kind and
exactly one may fire per visit; the S3K `$0121` manifest must distinguish the
normal `UpdateEverything -> Music` tail from the later `Music -> Music` speedup
revisit. Each DAC/DPCM/SEGA-PCM async manifest entry must bound one inner
sample/loop iteration with a synchronous completion boundary, not the entire
long-lived playback routine.
For S2 DPCM, exclude `$0178` from both hooks and the mask: its bytes `10 FE` are
the persistent `djnz $` busy wait, revisited roughly twenty times after the
iteration is already open, so a strict begin there would fail on the second
zero-match visit. Pin the one-shot begin to fallthrough `$017A` (`DI`, byte
`F3`) immediately before `$2A` selector/data work and retain `$01B0`
(`JP zWaitLoop`) as completion. An armed real iteration must prove one `$017A`
begin, every intervening `$2A` write, and one `$01B0` end.
Its canonical SHA-256 must equal the configured mask/manifest identity, and the
mask must be exactly the union of its **Z80** entry/completion PCs; M68K upload
hooks remain in the manifest but never in the Z80 mask. Independent review
of both disassemblies and ROM bytes is required before real capability counts.

Run short reviewed slices that exercise generic music/SFX, S2 DAC, and S3K
DPCM/SEGA PCM. Begin each real capability run at movie row zero and retain the
bootstrap stream through first upload/arm; S2 must lock the row-3 zero-ZRAM
regression above, and S3K must lock its independently derived equivalent. Add a
synthetic nested/tail program and a real S2 slice proving its source-correct
`zVInt -> zUpdateEverything -> zUpdateMusic` nesting with repeated music
children and exclusive parent/child writes. The real S3K slice instead proves
the source-correct sibling/tail phases under VInt: `$011B` UpdateEverything owns
pause, queue, and SFX writes until the atomic `$0121` tail emits its synchronous
snapshot/end followed by the Music begin; Music owns every later write and the
pre-action-kind `Music -> Music` speedup tail ends one pass before beginning the
next. It must prove both `$0121` alternatives, the same VInt parent where
applicable, service order by begin ordinal, and unchanged raw global chip order,
without retaining an UpdateEverything token around Music. Prove typed async
DAC/PCM hooks produce one bounded service per inner iteration, preserve real
VInt/typed-async nesting where the pinned source permits it, and produce neither
an orphan nor an overlong continuation. Frame-end RAM polling is forbidden even
as expected oracle.

Record the pinned BK2 logical name, SHA-256, complete input-row count, and
`[0,1000)` capability window, plus literal per-game counts for each hook
token/service kind, nesting depth, begin/end/reset event, snapshot
group/range/chunk/byte count, issuing CPU, FM selector, PSG, `$2A` register data
writes, M68K upload begin/end/full-ZRAM snapshot, successful arm/rearm
completion, pre-arm Z80 services (exactly zero), reset-to-reupload rows/ordinals,
total events, maximum per-frame occupancy, overflow (exactly zero), and duplicate digest in
`gpgx-audio-capability-v1.json`; then make tests compare fresh output to those
independent literal values. Derive the `$2A` count by replaying both YM address
latches in raw order and counting each paired data-port write while that latch
selects register `$2A`; never count a data byte merely because its value equals
`$2A`. A merely positive assertion or counts derived by the assertion under test
is insufficient.

```bash
S2_ROM_PATH="Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
S3K_ROM_PATH="Sonic and Knuckles & Sonic 3 (W) [!].gen" \
tools/bizhawk-headless/test.sh --filter GpgxZ80AudioCapabilityTests --jobs 1
```

- [ ] **Step 6: Run full-run performance/capacity proof and GREEN suite**

On the same otherwise-idle host, use identical ROM/movie prefix, core settings,
and process shape for patched-disabled and enabled lanes. Warm both orders, then
measure at least five matched pairs. Each pair uses one host, captures one exact
initial savestate, restores it between lanes, and alternates disabled/enabled
(`AB`) with enabled/disabled (`BA`) order. Compute slowdown for each matched
pair, never from separate lane medians. Record the order, both durations and
ratio for every pair, median and worst paired slowdown, frames/second, maximum
occupancy, capacity, and
headroom, queried event counts, bytes copied, managed-buffer growth/reuse,
observable upload/reupload flagged-`SERVICE_END` evidence, zero pre-arm Z80
service events, and full-ZRAM upload snapshot occupancy. Do not claim real-run
ignored-visit counts, proof-scan counts, or proof-scan duration: the frozen
production ABI exposes none. Each measured set
includes initial bootstrap and a fixed Reset/Power reupload path (from the
movie when reviewed, otherwise from the fixed fixture). Prove the pre-arm hot
path and exact one-scan/atomic-arm behavior with Task 7's test-only native
selftest instrumentation; in the real run infer only successful arm/rearm from
the source-verified flagged upload `SERVICE_END` followed by the first armed
Z80 service, without a new export or semantic event.
Assert each successful frame copies exactly `event_count * 32` bytes, zero-event
frames copy/allocate zero, and no lane performs a capacity-sized 2 MiB copy.
Require median enabled slowdown at most 10%, worst repetition at most
15%, no overflow, and at least 4x capacity headroom. Then replay both complete
movies, require the same capacity gates, all short-run literal counts unchanged,
and duplicate byte identity. Run all Task 8 tests plus the full native harness
suite.

- [ ] **Step 7: Commit the verified host boundary**

```bash
git add -f tools/bizhawk-headless/src/Core/GpgxHost.cs \
        tools/bizhawk-headless/src/Audio \
        tools/bizhawk-headless/tests/GpgxHostTests.cs \
        tools/bizhawk-headless/tests/GpgxAudioTraceNativeTests.cs \
        tools/bizhawk-headless/tests/CompleteRunAudioObserverTests.cs \
        tools/bizhawk-headless/tests/GpgxZ80AudioCapabilityTests.cs \
        tools/bizhawk-headless/fixtures/gpgx-audio-service-manifests-v1.json \
        tools/bizhawk-headless/fixtures/gpgx-audio-capability-v1.json \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj \
        tools/bizhawk-headless/tests/TestMain.cs \
        tools/bizhawk-headless/README.md
git commit -m "feat(tools): observe ordered GPGX Z80 audio events"
```

### Task 9: Shared CLI orchestration and final shared verification

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureStore.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioReport.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProducer.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProducerRegistry.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTool.java`
- Create: `tools/audio/run_complete_audio_parity.sh`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCutoffFrontier.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java`
- Create: `tools/audio/README.md`
- Modify: the shared design and S1/S2/S3K producer plans for the closed dispatcher and cutoff contract
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `validate`, `publish`, and `compare` subcommands and exit codes 0/2/3/4.
- Consumes: only fixed in-process producer implementations supplied at the exact main-source paths owned by later plans.

- [ ] **Step 1: Write failing CLI/security tests**

Test canonical target confinement, control/newline rejection, create-new/no-
overwrite publication primitives, fixed trusted Java class, sanitized child
environment, unavailable-producer discard, validate/compare classification,
fixed dispatcher IDs, and exact exit classification. Reject
`JAVA_TOOL_OPTIONS`, `MAVEN_OPTS`, `BASH_ENV`,
`ENV`, `LD_*`, and producer/tool replacement variables. S2/S3K reference
commands must reject the stock BizHawk home, an unlocked observer install,
observer-disabled metadata, wrong ABI/artifact/source/toolchain/patch hashes,
nonzero overflow, or capability counts that differ from Task 8's literal
fixture. S1 must continue to accept only its pinned stock M68K path.

Because Task 9 deliberately ships no game producer, successful orchestrator
publication, producer-nonzero cleanup after a real launch, duplicate reference/
engine byte gates, atomic report-pair publication, and exit 0/3 are acceptance
tests owned by the first later game task that fills a reserved dispatcher entry.
Task 9 proves their fixed helper paths and the typed `PRODUCER_UNAVAILABLE`
no-output path without installing a mutable test executable seam.

Before any S2/S3K producer may publish a complete run, add failing shared
schema/store/comparator vectors for the canonical `cutoff_frontier` record.
They must cover an empty frontier and Task 8's nonempty S3K frontier: explicit
`OPEN` versus completed state, outer-to-inner active stack order, pending
descendants in global begin order, producer-neutral parent coordinate/depth,
exact exclusive cutoff-local chip ownership, YM latches, normalized terminal
state, duplicate/missing ownership rejection, and inclusion in the canonical
root digest. Buffered-native reference records alone must also carry a
raw-root-only diagnostics sidecar containing GPGX tokens/hooks/PCs/sources,
native ordinals and bus inventory, raw range snapshots, host arm epoch/status,
and the full terminal-Z80 digest; callback/OpenGGF records must omit it. Native
per-frame ordinals never become semantic cutoff ordinals. Canonical cutoff
service begin/end ordinals are reassigned from the strict per-frame order of
retained service boundaries alone, excluding native chip/snapshot callbacks;
canonical chip ordinals are cutoff-local bus order. A producer
that disables or discards the observer before serializing this record is RED.
Require exactly one frontier, including the empty case, immediately before the
terminal record. Reject a missing, duplicate, early, or post-terminal frontier;
the terminal/CaptureCounts contract includes its active and pending counts and
its bytes participate in the terminal root digest.
Task 9 pins the exact Task 8 capability file, complete-run counts, and frontier
digests while exercising the full nonempty structure with adversarial canonical
vectors. The S3K producer task must feed its actual terminal frontier through
this schema/store/comparator before its first successful publication; Task 9
does not fabricate or publish a game capture to manufacture that evidence.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.TestCompleteRunAudioCli test
bash -n tools/audio/run_complete_audio_parity.sh
```

- [ ] **Step 3: Implement the safe generic orchestration**

Task 9 installs a closed `S1`/`S2`/`S3K` dispatcher with fixed profile and
implementation class IDs. Until a later game task supplies its reserved
built-in profile and producer implementation, `produce` returns typed
`PRODUCER_UNAVAILABLE` (exit 4) and leaves no output. The S1, S2, and S3K
producer tasks must fill only their reserved entries and add successful
end-to-end publish coverage; no caller-supplied executable or registration seam
is permitted. Each fixed profile class registers its immutable profile from a
static initializer. `CompleteRunAudioProfiles.require` lazily initializes only
the closed registry's exact profile class, so validate/compare work in every
fresh CLI JVM rather than relying on process-local state from an earlier call.

The shell validates identities through the fixed Java tool, creates one fresh
run directory, invokes reference twice and engine twice into direct children,
uses byte comparison for duplicate gates, then invokes the comparator. It never
deletes published captures and never accepts a replacement executable seam.
S2/S3K take a separately installed observer BizHawk home and publish its full
source/toolchain/patch/compressed-core/uncompressed-core/ABI/layout/capacity/
watch-mask/service-manifest/count identity plus stable logical installation and
core IDs into canonical metadata. They validate but never serialize the fresh
absolute install path, and recheck that the supplied observer installation
remains byte-identical before and after capture. Each fixed producer separately
owns discovery and verification of any stock control distribution required by
its game plan; no caller-supplied stock path or executable seam is added.
The two Task 8 continuation installs independently lock the exact plain-tree
identity `f635ac95e1cc40f4440396c8156aa81b37b04236d8b5a500f35db76a439feb9c`,
computed from sorted NUL-delimited `type mode relative-path` entries followed
by sorted NUL-delimited `sha256  ./relative-path` regular-file entries. Task 9
recomputes that bounded whole-tree identity before launch, so extra files,
mode changes, links, and specials are authentication failures rather than
merely inputs covered by a before/after equality check.

The S2/S3K producer serializes the immutable Task 8 cutoff frontier before
native Disable. The Java parser, capture store, and comparator validate and
compare that frontier exactly; it is not optional terminal diagnostics and
cannot be reconstructed from only completed frame services. Only after the
record is durably included in the root digest may the producer discard open
native/managed observer state.

- [ ] **Step 4: Run all shared verification**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore,com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator,com.openggf.tools.audio.completerun.TestCompleteRunAudioAuthorityGuard,com.openggf.tools.audio.completerun.TestCompleteRunAudioCli,com.openggf.tools.audio.completerun.TestCompleteRunAudioCutoffFrontier,com.openggf.audio.TestAudioDiagnosticObservers,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence' test
tools/bizhawk-headless/test.sh
tools/bizhawk-headless/test.sh --filter GpgxAudioObserverSourceLockTests
tools/bizhawk-headless/test.sh --filter GpgxAudioObserverBuildTests
tools/bizhawk-headless/test.sh --filter GpgxAudioTraceNativeTests
tools/bizhawk-headless/test.sh --filter GpgxZ80AudioCapabilityTests
bash -n tools/audio/run_complete_audio_parity.sh
git diff --check
```

Verify the Surefire XML rather than trusting wildcard console summaries.

- [ ] **Step 5: Commit shared orchestration and docs**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureStore.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioReport.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProducer.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProducerRegistry.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTool.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCutoffFrontier.java \
        tools/audio/run_complete_audio_parity.sh tools/audio/README.md CHANGELOG.md \
        docs/architecture/designs/audio/2026-08-10-cross-game-complete-run-audio-parity-design.md \
        docs/architecture/plans/audio/2026-08-10-complete-run-audio-parity-shared-plan.md \
        docs/architecture/plans/audio/2026-08-10-s1-complete-run-audio-parity-plan.md \
        docs/architecture/plans/audio/2026-08-10-s2-complete-run-audio-parity-plan.md \
        docs/architecture/plans/audio/2026-08-10-s3k-complete-run-audio-parity-plan.md
git commit -m "feat(tools): orchestrate complete-run audio parity"
```

### Task 10: Cross-game integration report and end-to-end review

**Files:**
- Create: `docs/architecture/validation/audio/2026-08-10-cross-game-complete-run-audio-parity-integration.md`
- Create: `docs/architecture/validation/audio/2026-08-10-cross-game-complete-run-audio-parity-end-to-end-review.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: completed S1, S2, and S3K plans with real `MATCH` artifacts.
- Produces: requirements traceability, exact verification evidence, residual-risk assessment, and a human listening checklist. It performs no merge or push.

- [ ] **Step 1: Audit every design acceptance criterion**

Build a table mapping each numbered goal and acceptance criterion to the exact
capture manifest, comparator result, test XML, source file, or command log that
proves it. Mark missing or indirect evidence RED and return to the owning game
plan; do not describe an unproved item as complete.

- [ ] **Step 2: Run all three real commands from fresh output roots**

```bash
tools/audio/run_s1_complete_audio_parity.sh --rom "$S1_ROM" --bizhawk-home docs/BizHawk-2.11-linux-x64
tools/audio/run_s2_complete_audio_parity.sh --rom "$S2_ROM" --bizhawk-home "$OBSERVER_BIZHAWK_HOME"
tools/audio/run_s3k_complete_audio_parity.sh --rom "$S3K_ROM" --bizhawk-home "$OBSERVER_BIZHAWK_HOME"
```

Expected: all exit 0; each has two byte-identical reference captures, two
byte-identical OpenGGF captures, and a cross-producer `MATCH` report. Before
these commands, Tasks 6-8 must have produced and verified the fresh
create-new `OBSERVER_BIZHAWK_HOME` under `target/audio-parity/native/`; it is
never `docs/BizHawk-2.11-linux-x64`.

- [ ] **Step 3: Run final focused and full JDK21 verification**

```bash
mvn -Dmse=off -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" \
  -Dtest='com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore,com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator,com.openggf.tools.audio.completerun.TestCompleteRunAudioAuthorityGuard,com.openggf.tools.audio.completerun.TestCompleteRunAudioCli,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunOpenGgfCapture,com.openggf.tools.audio.completerun.s2.TestS2CompleteRunOpenGgfCapture,com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunOpenGgfCapture,com.openggf.audio.TestAudioDiagnosticObservers,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.game.sonic1.audio.TestSonic1ExtraLifePriority,com.openggf.game.sonic2.audio.TestSonic2ExtraLifeRestore,com.openggf.game.sonic3k.audio.TestSonic3kExtraLifeRestore' test
tools/bizhawk-headless/test.sh
S2_ROM_PATH="$S2_ROM" S3K_ROM_PATH="$S3K_ROM" \
  tools/bizhawk-headless/test.sh --filter GpgxZ80AudioCapabilityTests --jobs 1
mvn -Pci -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" test
```

Compare exact failure/error IDs against the pre-feature baseline. Any new red
test returns to its owner.

- [ ] **Step 4: Perform independent source and policy review**

Review for trace authority, game-name checks in shared runtime code, unpinned
identities, a synthesized `Z80 BUS`, frame-end polling or instruction
interposition, fallback without native proof, observer/core source or licensing
gaps, stock installation mutation, disabled/enabled emulation differences,
overflow, skipped rows/services/writes, non-atomic output, stale snapshot
fields, `FixBugs` branch errors, and changes to operator/chip-port order. Record
every finding and its fix in the end-to-end review document.

- [ ] **Step 5: Write the human listening checklist**

For each game list concrete all-emeralds scenes covering normal music, FM SFX,
PSG SFX, overlapping SFX, 1-up takeover and restore, speed changes, DAC/PCM,
special-stage/bonus music, transitions, and ending/credits. State explicitly
that automated byte parity does not authorize merging without human approval.

- [ ] **Step 6: Commit the reports without integrating**

```bash
git add CHANGELOG.md \
  docs/architecture/validation/audio/2026-08-10-cross-game-complete-run-audio-parity-integration.md \
  docs/architecture/validation/audio/2026-08-10-cross-game-complete-run-audio-parity-end-to-end-review.md
git commit -m "docs(audio): validate complete-run parity across games"
```

Stop after this commit and present the branch/commits/artifacts to the humans.
Do not fetch/merge/push/clean up the worktree until they explicitly authorize
integration.
