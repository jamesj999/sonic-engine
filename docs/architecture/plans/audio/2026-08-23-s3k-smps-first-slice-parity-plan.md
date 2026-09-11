# S3K SMPS First-Slice Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the minimum trustworthy S3K write/PCM parity foundation and use
it to correct Collapse, Spindash Release, and Invincibility note-fill without
sound-ID runtime exceptions or fitted waveform constants.

**Architecture:** Extract a bounded first-slice reachability inventory from the
authenticated ROM, add typed source-owner chip-write capture, and replay native
and OpenGGF write schedules through the same pinned GPGX chip state. Exact
write/same-core output gates identify the owning driver semantic; only then does
the existing typed S3K configuration/handler/runtime change. Cross-core PCM is
diagnostic, while onset/tail and presentation transparency use exact anchors.

**Tech Stack:** Java 21, Maven/JUnit Jupiter, C# headless BizHawk tests, pinned
Genesis Plus GX native source/toolchain, JSONL/JSON research artifacts, locked-on
S3K ROM.

**Spec:**
`docs/architecture/designs/audio/2026-08-23-s3k-smps-driver-pcm-parity-design.md`

**Execution mode:** Inline execution with `superpowers:executing-plans`; the
user explicitly disabled subagents for the remainder of this session.

## Global constraints

- Work only in the isolated worktree containing this plan on
  `bugfix/ai-s3k-sfx-tail-fidelity`; never switch the main workspace branch.
- The first slice is `LOCKED_ON_S3K_V4` with `SonicDriverVer=4`,
  `fix_sndbugs=0`, `FixMusicAndSFXDataBugs=0`, and `FixBugs=0`.
- Runtime assets remain ROM-only. Research JSON never enters gameplay packages
  or selects runtime behavior.
- No game-name, zone, sound-ID, frame, movie, or trace carve-out may enter shared
  runtime code. The three sounds are tests of source-owned behavior families.
- Verify Maven runs on JDK 21 with `mvn -v` before every recorded Maven gate.
- Create retained capture space with `tools/agent-scratch new`; never retain raw
  traces/PCM in `/tmp`, `target/`, or the repository.
- Every native and OpenGGF capture has independent clean A/B outputs, exact
  provenance, fixed caps, and zero overflow/fault terminals.
- Cross-core PCM is diagnostic. Exact PCM acceptance replays native and OpenGGF
  writes from the same authenticated initial state through the same pinned core.
- Every production change starts with a focused RED and preserves atomic
  publication, snapshots, rewind, observer order, and capacity behavior.
- Every commit supplies all seven policy trailers explicitly and uses normal
  hooks. Do not merge or push before the human listening gate.
- This plan stops after the working first slice. The approved design requires
  separate follow-on plans for exhaustive V4/V3 stream closure and global-driver
  closure; their task boundaries will be based on the inventories produced here.

## File map

### First-slice inventory

- Create
  `src/main/java/com/openggf/tools/audio/s3kparity/S3kSmpsReachabilityInventory.java`:
  immutable bounded decoder for selected V4 stream roots, full semantic state,
  strict frontier reporting, and canonical records.
- Create
  `src/main/java/com/openggf/tools/audio/s3kparity/S3kDriverServiceInventory.java`:
  canonical first-slice/global service rows, status/source/timing dimensions,
  and deterministic JSON writer.
- Refactor
  `src/test/java/com/openggf/game/sonic3k/audio/smps/TestSonic3kSmpsMetaCommandReachability.java`:
  consume the shared decoder instead of its private approximation.
- Create
  `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kSmpsFirstSliceInventory.java`:
  ROM roots, source-condition tuple, strict-unknown, caps, shared-state poison,
  production-handler bijection, and artifact equality.
- Create
  `docs/architecture/research/audio/s3k-smps-first-slice-inventory-v1.json`:
  canonical first-slice inventory and gap classification.

### Typed write and PCM evidence

- Create
  `src/main/java/com/openggf/tools/audio/s3kparity/S3kAudioParityManifest.java`:
  strict bounded parser/validator for write, chip-PCM, and final-PCM schemas.
- Create
  `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kAudioParityManifest.java`:
  schema, cap, provenance, join, A/B, and mutation tests.
- Modify
  `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`:
  diagnostic typed service/track transaction and YM/PSG/DAC write ownership.
- Modify
  `tools/bizhawk-headless/native/gpgx-audio-lab/0001-trace-ym-write-cycles.patch`:
  explicit YM mix, PSG stereo, DAC-latch tap events and authenticated core state.
- Modify `tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs` and
  `tools/bizhawk-headless/tests/GpgxZ80AudioCapabilityTests.cs`: strict native
  vector, owner-join, cap, poison, and A/B capture entry points.
- Create
  `tools/bizhawk-headless/tests/GpgxS3kAudioParityManifestTests.cs`: canonical
  manifest writer and same-core replay harness.
- Modify `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`: include
  the new C# source.
- Create
  `src/main/java/com/openggf/audio/synth/ChipPcmDiagnosticTap.java`: package-
  private inert tap interface and immutable sample records.
- Modify `src/main/java/com/openggf/audio/synth/Ym2612Chip.java` and
  `src/main/java/com/openggf/audio/synth/PsgChip.java`: equivalent diagnostic
  tap publication without changing observer-free execution.
- Modify
  `src/main/java/com/openggf/audio/presentation/AudioPresentationParityProbe.java`:
  exact final-output anchor/component capture.
- Create
  `src/test/java/com/openggf/audio/synth/TestS3kChipPcmDiagnosticTap.java` and
  `src/test/java/com/openggf/audio/presentation/TestS3kFinalPcmParityProbe.java`.
- Create `docs/architecture/research/audio/s3k-first-slice-write-parity-v1.json`,
  `s3k-first-slice-chip-pcm-parity-v1.json`, and
  `s3k-first-slice-final-pcm-parity-v1.json` in the same research directory.

### Runtime correction and acceptance

- Modify `src/main/java/com/openggf/audio/smps/SmpsSequencer.java` only for a
  source-proved shared cadence/modulation/note-fill semantic.
- Modify
  `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
  only for a source-proved S3K dialect semantic and strict unknown rejection.
- Modify
  `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kYmServiceTimingProfile.java`
  and `src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java` only
  when an exact captured source segment requires timing expansion.
- Modify `src/main/java/com/openggf/audio/rewind/SmpsTrackSnapshot.java`,
  `SmpsSequencerSnapshot.java`, or `SmpsDriverSnapshot.java` only if the proved
  correction introduces persistent state.
- Extend `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`.
- Create
  `src/test/java/com/openggf/audio/driver/TestS3kInvincibilityNoteFillParity.java`.
- Extend `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`,
  `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`,
  and relevant rewind guards for changed state/APIs.
- Modify `CHANGELOG.md` and
  `docs/S3K_KNOWN_DISCREPANCIES.md` when runtime corrections land.
- Create
  `docs/architecture/validation/audio/2026-08-23-s3k-smps-first-slice-parity-validation.md`.

---

### Task 1: Extract the bounded first-slice inventory

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/s3kparity/S3kSmpsReachabilityInventory.java`
- Create: `src/main/java/com/openggf/tools/audio/s3kparity/S3kDriverServiceInventory.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/audio/smps/TestSonic3kSmpsMetaCommandReachability.java`
- Create: `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kSmpsFirstSliceInventory.java`
- Create: `docs/architecture/research/audio/s3k-smps-first-slice-inventory-v1.json`

**Interfaces:**
- Consumes: `AbstractSmpsData`, `SmpsProgramView`, authenticated V4 ROM roots
  `$59`, `$B6`, music `$2C`, exact coordination-flag operand rules, and a finite
  `ExternalEvent` alphabet.
- Produces:
  `InventoryResult inventory(StreamRoot root, InventoryLimits limits)` and
  `String writeCanonicalJson(FirstSliceInventory inventory)`.

- [ ] **Step 1: Write the strict RED tests.**

  Define these immutable public-tooling types inside
  `S3kSmpsReachabilityInventory`:

  ```java
  public enum Dialect { LOCKED_ON_S3K_V4, STANDALONE_S3_V3_COMPAT }
  public enum Status { EXACT, PARTIAL, MISSING, UNREACHABLE }
  public enum SourceBehavior { NORMAL, SHIPPED_BUG }
  public enum TimingStatus { EXACT, PARTIAL, UNAVAILABLE }
  public enum ExternalEvent {
      SERVICE_ENTRY, MUSIC_QUEUE, SFX_QUEUE, CONTINUOUS_UPDATE,
      CONTINUOUS_STOP, RING_SPEAKER_TOGGLE, PAUSE, FADE, JINGLE, STOP_ALL
  }
  public record InventoryLimits(int maxStates, int maxEdges,
          int maxCallDepth, int maxOverlayBytes) { }
  public record StreamRoot(Dialect dialect, String key, AbstractSmpsData data,
          int trackIndex, ExternalEvent event) { }
  public record State(Dialect dialect, String rootKey, int bank, int pc,
          int trackType, List<Integer> callStack,
          Map<Integer, Integer> loopCounters,
          Map<String, Integer> sharedProjection,
          Map<Integer, Integer> overlay, ExternalEvent event) { }
  public record Edge(int fromState, int toState, String kind,
          int sourcePc, String sourceCitation) { }
  public record Frontier(int state, String reason, int sourcePc) { }
  public record Behavior(String key, Status status,
          SourceBehavior sourceBehavior, TimingStatus timingStatus,
          Set<String> roots, Set<String> trackTypes,
          String runtimeOwner, String sourceCitation,
          Set<String> evidenceIds) { }
  public record InventoryResult(List<State> states, List<Edge> edges,
          List<Frontier> frontiers, Set<Behavior> behaviors) { }
  ```

  `S3kDriverServiceInventory` exposes
  `static List<Behavior> firstSliceRows()` and
  `static void validateCompleteFirstSlice(Set<String> requiredKeys,
  Collection<Behavior> rows)`. The required keys are fixed in the test and
  include service order, tempo/speed cadence, SFX admission/restore, continuous
  SFX, note fill, Collapse modulation/PSG noise, pause/fade/jingle/StopAll,
  DAC/FM6, SEGA PCM, PAL repeat, and ring-speaker alternation. Rows outside the
  three cases may remain `PARTIAL`/`MISSING`, but no required key may be absent.

  Tests require exact V4 condition fields, all Collapse/Dash/Invincibility track
  roots, no unexplored frontier, explicit note-fill/modulation/noise/stop/restore
  behavior rows, and stable sorted JSON. Mutations must reject unknown `FF 08`,
  an S3K flag falling through to shared S2 semantics, stack underflow, cap N-1,
  overlay overflow, an orphan source citation, and a missing ring-speaker global
  row. A synthetic `FF 03` stream must preserve a bounded overlay and a synthetic
  unsafe return must classify `SHIPPED_BUG`, not disappear as malformed input.

- [ ] **Step 2: Run the tests and observe RED.**

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  mvn -v
  S3K_ROM="$(find "$(git rev-parse --show-toplevel)" -maxdepth 1 -type f -name '*.gen' -print0 \
    | xargs -0 sha1sum | awk 'toupper($1)=="CFBF98C36C776677290A872547AC47C53D2761D6" {print $2; exit}')"
  test -n "$S3K_ROM"
  mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
    -Dtest=com.openggf.tools.audio.s3kparity.TestS3kSmpsFirstSliceInventory test
  ```

  Expected: test compilation fails because the inventory types do not exist.

- [ ] **Step 3: Extract and harden the existing fixed-point decoder.**

  Move the private decoder logic from
  `TestSonic3kSmpsMetaCommandReachability` into the new tooling class. State
  equality includes dialect, bank/source PC, track type, call stack, loop
  counters, relevant shared-memory abstract values, external event, and overlay.
  Follow both conditional branches; summarize proven strongly connected
  components; retain a typed frontier instead of guessing. Use checked integer
  arithmetic and defensive copies. The first-slice artifact fixes limits at
  `131072` states, `524288` edges, call depth `16`, and overlay `256` bytes;
  tests exercise each N and N-1 boundary.

- [ ] **Step 4: Add strict runtime unknown ownership.**

  Add a dialect method such as
  `boolean ownsCommand(int command, int subcommand)` to the generated inventory
  view used only by tests. Change production only if the RED proves a reachable
  S3K unknown currently falls through: in that case `Sonic3kCoordFlagHandler`
  throws a typed service exception after aborting the active driver transaction.
  Preserve explicitly inventoried dialect-neutral delegation. Add the exact
  production-handler bijection guard before accepting the change.

- [ ] **Step 5: Generate twice and pin the canonical artifact.**

  Run the generator in two clean JVMs into two files beneath one managed scratch
  task, compare them byte-for-byte, then copy the reviewed canonical output to
  the research path with `apply_patch`. Assert its schema, ROM SHA-1, V4 tuple,
  roots, limits, counts, body digest, and zero frontier.

- [ ] **Step 6: Run focused and legacy reachability gates.**

  ```bash
  mvn -v
  mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
    -Dtest=com.openggf.tools.audio.s3kparity.TestS3kSmpsFirstSliceInventory,com.openggf.game.sonic3k.audio.smps.TestSonic3kSmpsMetaCommandReachability test
  ```

- [ ] **Step 7: Commit the tooling slice.**

  ```bash
  git add src/main/java/com/openggf/tools/audio/s3kparity \
    src/test/java/com/openggf/tools/audio/s3kparity \
    src/test/java/com/openggf/game/sonic3k/audio/smps/TestSonic3kSmpsMetaCommandReachability.java \
    docs/architecture/research/audio/s3k-smps-first-slice-inventory-v1.json
  git commit -m "feat(audio): inventory S3K first-slice SMPS paths" \
    -m "Changelog: n/a: internal parity tooling only" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 2: Define strict parity manifests and typed write ownership

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/s3kparity/S3kAudioParityManifest.java`
- Create: `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kAudioParityManifest.java`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/0002-s3k-audio-parity-events.patch`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/s3k-parity-artifact-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k_parity_harness.c`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k-parity-run.sh`

**Interfaces:**
- Produces strict schemas `openggf.s3k-audio-write-parity.v1`,
  `openggf.s3k-chip-pcm-parity.v1`, and
  `openggf.s3k-final-pcm-parity.v1`.
- Java records are nested under `S3kAudioParityManifest`:

  ```java
  enum ManifestKind { WRITE, CHIP_PCM, FINAL_PCM }
  enum ChipTapKind { YM2612_MIX_STEREO, PSG_STEREO_NATIVE, DAC_LATCH_MONO }
  record Provenance(String dialect, Map<String, String> sourceConditions,
          String romSha1, String bk2Sha256, String gpgxSourceSha256,
          String nativePatchSha256, String artifactLockSha256,
          String openggfCommit, String openggfTree, boolean openggfDirty,
          String openggfArtifactSha256, String toolchainIdentity,
          String runtimeConfigSha256) { }
  record Owner(long transactionId, int serviceKind, long serviceOrdinal,
          long generation, int trackBase, int trackType, int channelId,
          int bank, int sourcePointer) { }
  record WriteRow(long eventOrdinal, long masterCycle, long vintOrdinal,
          long serviceEntryMasterCycle, int sourcePc, Owner owner,
          String chip, int port, int register, int value) { }
  record PcmWindow(ChipTapKind tap, long firstMasterCycle, int phase,
          String initialStateSha256, String writeGroupSha256,
          int frameCount, String pcmSha256, int leftOnset, int rightOnset,
          int leftTail, int rightTail, List<Integer> samples) { }
  ```
- Native transaction record:

  ```text
  transaction_id, service_kind, service_ordinal, owner_generation,
  track_base, track_type, channel_id, bank, source_pointer,
  source_pc, event_ordinal, master_cycle, chip_event
  ```

- [ ] **Step 1: Add parser/validator RED tests.**

  Require exact dialect/source tuple, native and OpenGGF commit/tree/artifact
  identity, dirty=false, JDK/toolchain/region/clock/config identities, fixed
  caps, dense transaction/event ordinals, reset/service/VInt coordinates, and
  terminal counts/digests. Poison wrong owner generation, register fingerprint-
  only joins, interrupted transaction, per-case time shift, duplicate row,
  missing A/B identity, dirty OpenGGF source, stale artifact lock, and overflow.

- [ ] **Step 2: Observe RED.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=com.openggf.tools.audio.s3kparity.TestS3kAudioParityManifest test
  ```

- [ ] **Step 3: Implement the immutable bounded manifest parser.**

  Use records with compact constructors, defensive `List.copyOf`, exact key
  grammar, checked lengths/counts, canonical UTF-8 JSON, and SHA-256 over the
  canonical body. Expose only:

  ```java
  static S3kAudioParityManifest read(Path path);
  static S3kAudioParityManifest read(Reader reader);
  void validateAgainst(Provenance expected);
  String canonicalJson();
  ```

  Runtime packages must not import this class; add an architecture test.

- [ ] **Step 4: Add native typed owner events before chip writes.**

  Layer a separate diagnostic-only patch after the unchanged ordinary ABI4
  patch; do not repin the production core or complete-run capability. A compiled
  descriptor at each first-slice source boundary
  opens one transaction from exact Z80 PC/opcode plus track-base/type/channel
  and service state. Every instruction/write carries the transaction ID until a
  terminal. The collector rejects nested ambiguity, missing terminal, owner
  mutation, wrong bank/source pointer, ordinal gaps, and record-cap N-1. Do not
  join by register/value pattern.

- [ ] **Step 5: Prove native patch isolation and determinism.**

  Apply the patch to a pristine pinned GPGX source in managed scratch, compare
  the patched source byte-for-byte with the reviewed working source, run all
  existing native observer harnesses plus new wrong-owner/interrupt/cap poison
  cases, reverse-check the patch, and build twice from independent roots. Record
  core, compressed core, ELF/export, install-tree, patch, build-recipe, and
  artifact-lock hashes.

- [ ] **Step 6: Run Java/C# gates and commit the prerequisite.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=com.openggf.tools.audio.s3kparity.TestS3kAudioParityManifest test
  tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k-parity-run.sh \
    "$PINNED_SOURCE" "$PINNED_TOOLCHAIN" "$MANAGED_SCRATCH"
  tools/bizhawk-headless/test.sh --filter GpgxAudioObserverBuildTests
  ```

  Commit the native prerequisite separately after independent local review,
  staging only the manifest parser/tests, diagnostic patch/selftest, plan
  correction, and exact diagnostic artifact lock. The managed reflection/capture
  adapter remains Task 3 work so this slice cannot change the frozen complete-run
  harness executable identity. Use `feat(audio): authenticate S3K
  chip-write groups` with all seven explicit trailers; use
  `Changelog: n/a: diagnostic parity infrastructure only`.

### Task 3: Add exact diagnostic PCM taps and same-core replay

**Files:**
- Create: `src/main/java/com/openggf/audio/synth/ChipPcmDiagnosticTap.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/synth/PsgChip.java`
- Modify: `src/main/java/com/openggf/audio/presentation/AudioPresentationParityProbe.java`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/0003-s3k-chip-pcm-events.patch`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/s3k-pcm-artifact-lock.json`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k_pcm_harness.c`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k_pcm_replay_harness.c`
- Create: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/s3k-pcm-run.sh`
- Create: `src/test/java/com/openggf/audio/synth/TestS3kChipPcmDiagnosticTap.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestS3kFinalPcmParityProbe.java`

**Interfaces:**
- `ChipPcmDiagnosticTap` is package-private and installed only through a
  test/diagnostic factory:

  ```java
  sealed interface ChipPcmSample permits YmMixStereo, PsgStereo, DacLatch {
      long masterCycle();
      long ordinal();
  }
  record YmMixStereo(long masterCycle, long ordinal, int left, int right)
          implements ChipPcmSample { }
  record PsgStereo(long masterCycle, long ordinal, int left, int right)
          implements ChipPcmSample { }
  record DacLatch(long masterCycle, long ordinal, int signedCode)
          implements ChipPcmSample { }
  interface ChipPcmDiagnosticTap {
      void onSample(ChipPcmSample sample);
  }
  ```

- [x] **Step 1: Add RED boundary tests.**

  Assert `YM2612_MIX_STEREO` fires immediately after one internal YM sample and
  before resampling/gain/PSG mix; `PSG_STEREO_NATIVE` fires after tone/noise,
  attenuation, and chip panning but before resampling; `DAC_LATCH_MONO` fires on
  the held decoded DAC code before interpolation/gain/filter/pan. Observer-free
  output and allocation behavior must be byte-identical. Add snapshot/reset
  digest tests covering YM phase/envelope/feedback/LFO/timer/DAC and PSG
  latch/counter/LFSR/pending state.

- [x] **Step 2: Observe RED.**

  ```bash
  mvn -v
  mvn -Dmse=off \
    -Dtest=com.openggf.audio.synth.TestS3kChipPcmDiagnosticTap,com.openggf.audio.presentation.TestS3kFinalPcmParityProbe test
  ```

- [x] **Step 3: Implement inert Java taps.**

  Keep the fast observer-free branch free of sample-record allocation. Use
  checked master-cycle/ordinal counters and immutable records. Do not expose the
  installation port through `Synthesizer`, `SmpsSequencer`, or gameplay
  packages; architecture tests import all `com.openggf` production classes and
  permit only the diagnostic factory/probe call sites.

- [x] **Step 4: Implement equivalent native taps.**

  Layer a separate diagnostic-only ABI after the frozen ordinary observer and
  Task 2 parity ABI. Add callbacks at the actual GPGX YM pre-buffer mixed-sample
  boundary, held DAC-code boundary, and PSG pre-Blip native-sample boundary.
  Prove exact ABI layout, dense ordinals, master-cycle units, and N/N+1 capacity
  behavior. Task 4 owns the case terminal that binds clock epoch, initial-state
  digest, write-group digest, sample digest/count, and zero overflow/fault; the
  raw ABI must not fabricate those case-level facts.

- [x] **Step 5: Implement actual-core replay.**

  In a standalone native harness compiled against the exact patched GPGX
  `ym2612.c`, save one real core state, replay one absolute-master-cycle
  schedule, restore the same state, and replay the identical projected schedule.
  Require byte-identical mixed/DAC tap samples, and require a one-internal-sample
  schedule poison to differ. Task 4 substitutes the authenticated native and
  OpenGGF case schedules; direct Java-vs-GPGX samples remain diagnostics with
  exact anchor-relative metrics and no invented passing tolerance.

- [x] **Step 6: Prove presentation transparency.**

  Feed a fixed component stream through `AudioPresentationParityProbe` using
  multiple output chunk partitions. Require exact final left/right samples,
  exact per-channel first/last nonzero indices, and identical digests. A
  configured nonzero noise floor uses a predeclared threshold from the selected
  numeric representation, not a retail capture.

- [ ] **Step 7: Run focused/native/regression gates and commit.**

  Run the two new Java classes, YM/PSG chip snapshot/parity tests, presentation
  mixer/producer/snapshot tests, and native lab/manifest selftests. Commit with
  subject `feat(audio): add exact S3K PCM parity taps`; update `CHANGELOG.md`
  because `src/main` gains diagnostic capability, and supply the other six
  trailers explicitly.

### Task 4: Capture the immutable first-slice RED oracles

**Files:**
- Create: `docs/architecture/research/audio/s3k-first-slice-write-parity-v1.json`
- Create: `docs/architecture/research/audio/s3k-first-slice-chip-pcm-parity-v1.json`
- Create: `docs/architecture/research/audio/s3k-first-slice-final-pcm-parity-v1.json`
- Modify: `src/test/java/com/openggf/tools/audio/s3kparity/TestS3kAudioParityManifest.java`

**Interfaces:**
- Cases: Collapse `$59`, Spindash Release `$B6`, and locked-on Invincibility
  music `$2C` at source-authenticated service/track boundaries.

- [ ] **Step 1: Authenticate inputs and allocate capture roots.**

  Discover the actual ROM/BK2 paths, verify SHA-1/SHA-256, verify the pinned
  artifact lock/toolchain, and allocate separate A/B native and OpenGGF scratch
  tasks. Record available capacity and retention dates.

- [ ] **Step 2: Capture native A/B and require identity.**

  Capture request through a source-proved post-tail silence window for both SFX,
  and all four note-fill attacks through key-off/silence for Invincibility.
  Require exact typed owners, zero DMA/fault/overflow, dense ordinals, and
  byte-identical canonical A/B outputs. Retain raw streams only in scratch.

- [ ] **Step 3: Capture OpenGGF A/B and observe strict RED.**

  Capture two clean exact-commit processes. Require byte-identical OpenGGF A/B
  manifests, then compare to native. Preserve the first divergent write/state/
  service and same-core PCM sample; do not alter expected data to match the
  engine. Expected first-slice result is at least one RED for each user-reported
  audible defect or an explicit source-proved ruling that the defect belongs to
  a later chip/presentation layer.

- [ ] **Step 4: Publish compact reviewed artifacts.**

  Store only provenance, initial-state digest, write groups, bounded PCM windows,
  structural metrics, terminal hashes/counts, and scratch retention references.
  Run parser poison tests and regenerate twice before staging.

- [ ] **Step 5: Commit evidence only.**

  Commit subject `test(audio): pin S3K first-slice parity oracles` with all seven
  trailers and `Changelog: n/a: comparison-only test evidence`.

### Task 5: Close Collapse tail parity

**Files:**
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`
- Modify only the source-proved runtime owner from the file map.
- Modify snapshot/guard files only if the proved state requires them.
- Modify: `CHANGELOG.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`

**Interfaces:**
- Consumes: Collapse inventory row and exact native write/same-core PCM oracle.
- Produces: general S3K modulation/PSG/lifecycle semantics with exact tail and
  stereo behavior.

- [ ] **Step 1: Add complete-lifecycle RED assertions.**

  Extend the existing test beyond the first FM key-off. Compare every typed FM
  and PSG write/service through source-proved silence, per-track modulation
  state/cadence, effective PSG latch/noise/attenuation, music-owner restore, no
  duplicate keyoff, exact same-core L/R sample digest, exact L/R tail indices,
  and final presentation chunk-partition equality. Include active music and
  silence initial states.

- [ ] **Step 2: Run and record the first divergence.**

  ```bash
  mvn -v
  mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
    -Dtest=com.openggf.audio.driver.TestS3kCollapseDashSfxParity test
  ```

  State one source routine/field hypothesis. If the failure is chip-only after
  exact writes, do not modify the interpreter; route it to the exact chip owner
  under Task 3 evidence.

- [ ] **Step 3: Implement the minimum source semantic.**

  Match the exact `fix_sndbugs=0` source behavior for the affected behavior
  family. If persistent state changes, capture it in track/sequencer/driver
  snapshots and live rollback before rerunning. If source timing expands beyond
  the current FM5 first-attack profile, derive and test every new segment before
  enabling it.

- [ ] **Step 4: Run atomicity and sibling controls.**

  Add failure injection after the last valid pre-terminal operation and prove no
  chip/logical/observer prefix. Run N/N-1 capacity, rewind before/mid/after tail,
  repeated Collapse, another modulated PSG effect, ring-speaker alternation, S1,
  and S2 controls.

- [ ] **Step 5: Update docs and commit.**

  Update the discrepancy from its actual old state to the proved new boundary.
  Commit `fix(audio): preserve the S3K Collapse tail` with `Changelog: updated`,
  `S3K-Known-Discrepancies: updated`, and the other five explicit trailers.

### Task 6: Close Spindash Release parity

**Files:**
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`
- Modify only the source-proved runtime owner.
- Modify relevant snapshot/guard files if state changes.
- Modify: `CHANGELOG.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`

**Interfaces:**
- Consumes: independent `$B6` typed source/write/PCM oracle.
- Produces: exact general semantics for its stream shape without inheriting
  Collapse assumptions.

- [ ] **Step 1: Add RED assertions for the full stream.**

  Compare request/admission, optional coordination/modulation commands, voice,
  frequency/key-on, every service update, FM/PSG stop, restored owner, exact
  same-core PCM, and final onset/tail. Run low/high charge source inputs without
  using charge or sound ID in the shared runtime predicate.

- [ ] **Step 2: Observe and localize RED.**

  Run only the Dash methods first, then the whole class. Preserve the first
  native/OpenGGF divergence and cite its disassembly routine.

- [ ] **Step 3: Implement, snapshot, and prove rollback.**

  Change the smallest typed S3K config/handler/shared semantic. Add snapshot and
  live rollback coverage for new state. Prove the Collapse oracle remains exact
  and an unsupported sibling remains byte-identical.

- [ ] **Step 4: Commit the independent correction.**

  Update changelog/discrepancy text and commit `fix(audio): match S3K Spindash
  Release` with all policy trailers and mapped docs marked `updated`.

### Task 7: Close Invincibility note-fill parity

**Files:**
- Create: `src/test/java/com/openggf/audio/driver/TestS3kInvincibilityNoteFillParity.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java` only if the
  shared cadence is proved wrong; otherwise modify the smaller S3K owner.
- Modify snapshot/guard files if state changes.
- Modify: `CHANGELOG.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`

**Interfaces:**
- Consumes: locked-on music `$2C`, its four typed note-fill owner groups, and
  exact native write/same-core output.
- Produces: shipped `smpsNoteFill $05` service ordering and sharp cutoffs.

- [ ] **Step 1: Write the four-tone RED.**

  Load music `$2C` from the authenticated ROM. Identify the four source tracks
  by typed track base/channel/source pointer, not by listening position. For
  each tone assert note-fill master/timeout service sequence, exact key-off
  master cycle/register/value, no extra volume-softening service, exact same-core
  per-channel last-nonzero index, and exact presentation chunk-boundary output.

- [ ] **Step 2: Add scheduler phase matrix REDs.**

  Cover normal service, carry/no-carry tempo updates, S3K speed extra service,
  snapshot immediately before fill expiry, restore/replay, and a render buffer
  split at the expiry. S1/S2 note-fill controls retain their own dialect behavior.

- [ ] **Step 3: Implement the source order.**

  Correct the ordering of duration extension, track update, note-fill decrement,
  keyoff, modulation/envelope update, or service repetition only as proved by the
  first divergent source instruction. Do not tune a duration or volume. Capture
  every changed counter/flag in snapshots and live rollback.

- [ ] **Step 4: Run focused and cross-game cadence gates.**

  Run the new class, `TestSmpsSequencerDriverCadence`,
  `TestSmpsSequencerTempoMath`, S3K coord-flag parity, S1/S2 note-fill tests,
  driver snapshot/rewind, and presentation boundary tests.

- [ ] **Step 5: Commit.**

  Update changelog/discrepancy text and commit `fix(audio): match S3K note-fill
  cutoffs` with all seven trailers and mapped docs marked `updated`.

### Task 8: Close the first slice and prepare the listening build

**Files:**
- Create: `docs/architecture/validation/audio/2026-08-23-s3k-smps-first-slice-parity-validation.md`
- Modify: `docs/architecture/research/audio/s3k-smps-first-slice-inventory-v1.json`
- Modify: all three first-slice parity manifests with final OpenGGF identities.
- Modify: `CHANGELOG.md` only if the final summary is not already exact.

**Interfaces:**
- Produces: clean exact-HEAD JAR and complete Requirements/Integration
  Report/End-to-End Review evidence for human listening.

- [ ] **Step 1: Regenerate all first-slice artifacts twice.**

  Require identical native A/B, OpenGGF A/B, inventory, write, chip-PCM, and
  final-PCM artifacts. Require zero unclassified first-slice rows, zero
  fault/overflow, exact same-core output, and exact structural onset/tail.

- [ ] **Step 2: Run focused JDK21/all-ROM gates.**

  Discover and hash all three ROMs. Run the complete first-slice inventory,
  manifest, native, PCM, Collapse, Dash, Invincibility, timeline, observer,
  snapshot, rewind, presentation, S1, and S2 controls. Record exact commands,
  counts, failures/errors/skips, and log hashes.

- [ ] **Step 3: Record an identical-command integration baseline.**

  In a detached isolated worktree/cowtree at the current main-workspace branch,
  run the full JDK21 Maven suite with all three absolute ROM properties. Save the
  full log and sorted test-identity/status ledger beneath managed scratch and
  record hashes/counts.

- [ ] **Step 4: Run the same full suite on the feature branch.**

  Require no baseline-passing failure/error and no worsened baseline identity.
  Investigate order-sensitive differences by rerunning both commands; never
  waive an attributable regression.

- [ ] **Step 5: Write and self-review validation.**

  Record requirements traceability, architecture consistency, changed files,
  native/build/ROM/OpenGGF identities, artifacts, test evidence, exact remaining
  PARTIAL/MISSING rows, risks, scratch retention, and the next two follow-on plan
  boundaries: exhaustive stream closure and global-driver/synthesis closure.
  Scan for stale claims, placeholders, untracked artifacts, and mismatched hashes.

- [ ] **Step 6: Commit validation and package exact clean HEAD.**

  Commit `docs(audio): validate S3K first-slice parity` with all seven trailers.
  Run `mvn package` on JDK21 with all three ROM properties and tests enabled; if
  the recorded baseline remains red, use only
  `-Dmaven.test.failure.ignore=true` so Surefire still runs before assembly.
  Verify ZIP integrity, embedded commit/dirty=false, file size, and SHA-256.

- [ ] **Step 7: Perform the human listening gate.**

  Listen to Collapse from silence/over music/rapid repeat/after completion on
  each stereo side; Spindash Release at several charge lengths and after another
  FM SFX; all four Invincibility cutoffs; and the prior Blue Sphere/ring/speed-
  shoes/pause/focus regressions. Do not merge or push until the user confirms a
  positive improvement.

- [ ] **Step 8: Integrate only after confirmation.**

  Follow root `AGENTS.md`: fetch and fast-forward the branch in the main
  workspace without overwriting user changes, record the updated baseline,
  merge into that checked-out branch with the required staged `README.md`
  release/change-log update, rerun the identical full comparison, push only the
  main-workspace branch, then remove the clean merged worktree and delete the
  fully merged local scaffold branch. Report every commit and pushed branch.
