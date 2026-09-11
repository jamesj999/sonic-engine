# SMPS YM2612 Write Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve source-derived time between audited locked-on S3K YM2612
writes so a Blue Sphere SFX replayed after completion starts more like the
shipped driver, without sound-ID logic or full Z80 emulation.

**Architecture:** A corrected diagnostic lab retains a compact native oracle,
while an independent calculation artifact derives S3K relative write spacing
from the shipped `fix_sndbugs = 0` path. `SmpsDriver` assigns global
master-cycle due times and `VirtualSynthesizer` drains committed writes at the
YM core's internal-sample boundary. The first slice models relative inter-write
timing only; absolute VInt/service-entry phase and DMA-contended bank access are
explicitly outside it.

**Tech Stack:** Java 21, JUnit 5, Maven 3.9.x, C#/.NET Framework 4.8 under Mono,
BizHawk 2.11 Waterbox, Genesis Plus GX MAME YM2612 core, Bash, JSON.

**Spec:**
`docs/architecture/designs/audio/2026-08-22-smps-ym-write-timeline-design.md`

## Global Constraints

- The shipped `fix_sndbugs = 0` path is authoritative.
- Runtime code must not read a trace, branch on sound ID, game name, zone,
  movie, frame number, or trace coordinate.
- Timing constants are integer Genesis master cycles: 15 per Z80 T-state and
  1008 per GPGX MAME YM2612 internal sample.
- The S3K profile models only normalized inter-write deltas. It does not claim
  absolute service-entry phase or DMA-contended timing.
- All queued entries are self-contained and bounded; committed entries survive
  ordinary SFX completion/stop and only generation barriers may discard them.
- Logical observer events publish after service commit. Chip/key-on callbacks
  publish only when the corresponding committed write drains.
- No merge into `develop` until automated gates pass and the user's listening
  test confirms a positive improvement.
- Use JDK 21 (`mvn -v` must report 21) and JUnit Jupiter only.
- Never commit ROMs, BK2 copies, raw PCM, or uncompressed trace payloads.

---

## File map

### New production files

- `src/main/java/com/openggf/audio/synth/YmWriteTimeline.java` — bounded,
  generation-aware queue, immutable entries, snapshots, and drain ordering.
- `src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java` — immutable
  operation/variant/segment contract and no-timing implementation.
- `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kYmServiceTimingProfile.java`
  — checked locked-on S3K `fix_sndbugs = 0` relative-cycle tables.

### Modified production files

- `src/main/java/com/openggf/audio/synth/Ym2612Chip.java` — internal master-cycle
  frontier and drain hook before each newly observable internal sample.
- `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java` — owns timeline,
  generation barriers, timeline snapshot, and immediate-drain mutation boundary.
- `src/main/java/com/openggf/audio/synth/Synthesizer.java` — default timing-scope
  API so non-driver synthesizers remain immediate.
- `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java` — immutable timing
  profile accessor/builder/copy support.
- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java` — opens typed timing
  segments around the exact S3K max-release, voice, key-off/frequency/key-on,
  completion, and restore operations.
- `src/main/java/com/openggf/audio/driver/SmpsDriver.java` — global service clock,
  service transaction, logical observer journal, capacity preflight, barriers,
  adoption, and live-command rollback.
- `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java` — timeline clock,
  generation, and pending-entry state.
- `src/main/java/com/openggf/audio/presentation/SmpsAssetCatalog.java` — copies the
  timing profile through every presentation-owned config clone.
- `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
  — installs the S3K timing profile; S1/S2 remain explicitly absent.

### Native validation and research files

- `tools/bizhawk-headless/native/gpgx-audio-lab/0001-trace-ym-write-cycles.patch`
  — diagnostic-only post-`fm_update` write/cycle/ordinal and DMA-stall events.
- `tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh`
  — pinned-input validation, clean patch/build/capture, compact candidate output.
- `tools/bizhawk-headless/src/Core/GpgxHost.cs` — diagnostic PCM drain boundary
  used by the opt-in lab only.
- `tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs` — deterministic
  cycle/ordinal capture and compact-oracle writer.
- `tools/bizhawk-headless/tests/TestMain.cs` and
  `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj` — lab registration.
- `docs/architecture/research/audio/s3k-blue-sphere-ym-write-oracle-v1.json`
  — compact FM5 groups, hashes, corrected cycle timestamps, DMA markers, and
  onset summaries; no raw PCM.
- `docs/architecture/research/audio/2026-08-22-s3k-ym-write-timing-calculation.md`
  — opcode/T-state/bank-wait calculation and source citations.

### Tests and delivery docs

- `src/test/java/com/openggf/audio/synth/TestYmWriteTimeline.java`
- `src/test/java/com/openggf/audio/synth/TestYm2612ChipGpgxParity.java`
- `src/test/java/com/openggf/audio/synth/YmNativeOracle.java`
- `docs/architecture/research/audio/s3k-ym-write-timing-calculation-v1.json`
- `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`
- `src/test/java/com/openggf/audio/driver/TestS3kBlueSphereSfxParity.java`
- `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kYmServiceTimingProfile.java`
- `src/test/java/com/openggf/tests/trace/s3k/TestS3kSpecialStageAudioPlaybackTrace.java`
- `docs/architecture/audits/audio/2026-08-22-s1-s2-ym-write-timing-audit.md`
- `docs/architecture/validation/audio/2026-08-22-s3k-blue-sphere-audio-validation.md`
- `CHANGELOG.md` and S3K known-discrepancy documentation as required by final
  behavior and commit policy.

---

### Task 1: Retain a corrected and reproducible native timing oracle

**Files:**
- Create: `tools/bizhawk-headless/native/gpgx-audio-lab/0001-trace-ym-write-cycles.patch`
- Create: `tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh`
- Create: `tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs`
- Create: `docs/architecture/research/audio/s3k-blue-sphere-ym-write-oracle-v1.json`
- Modify: `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `src/test/java/com/openggf/audio/synth/TestYm2612ChipGpgxParity.java`
- Create: `src/test/java/com/openggf/audio/synth/YmNativeOracle.java`
- Remove diagnostic-only hunks from:
  `tools/bizhawk-headless/tests/GpgxZ80AudioCapabilityTests.cs`

**Interfaces:**
- Produces: oracle schema
  `openggf.s3k-ym-write-timing-oracle.v1` with provenance, FM5 write groups,
  `master_cycle`, `internal_ordinal`, `dma_stall_count`, key-on attenuation,
  onset RMS, and terminal SHA-256.
- Produces: test-only `YmNativeOracle.load(Path)` with immutable provenance,
  group, and write records; production code cannot import it.
- Produces: opt-in test name
  `GpgxYmWriteTimingLabTests capture corrected S3K Blue Sphere YM timing`.
- Consumes: externally supplied pinned GPGX source/toolchain, S3K ROM, and BK2;
  the script validates hashes and never copies these inputs into the repository.

- [ ] **Step 0: Record the untouched main-workspace baseline**

  Do not switch the main workspace. Create a detached baseline worktree from its
  current HEAD, run the full suite on JDK 21, retain the log in agent scratch,
  and remove only the clean detached worktree:

  ```bash
  MAIN="${OPENGGF_MAIN_WORKSPACE:?set to the absolute main-workspace path}"
  S1_ROM="$MAIN/s1.gen"
  S2_ROM="$MAIN/s2.gen"
  S3K_ROM="$MAIN/s3k.gen"
  test -f "$S1_ROM" && test -f "$S2_ROM" && test -f "$S3K_ROM"
  test "$(sha1sum "$S1_ROM" | cut -d' ' -f1)" = 69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b
  test "$(sha1sum "$S2_ROM" | cut -d' ' -f1)" = 8bca5dcef1af3e00098666fd892dc1c2a76333f9
  test "$(sha1sum "$S3K_ROM" | cut -d' ' -f1)" = cfbf98c36c776677290a872547ac47c53d2761d6
  ROM_ARGS=(-Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM")
  BASE_HEAD="$(git -C "$MAIN" rev-parse HEAD)"
  BASE_TASK="$(agent-scratch new smps-ym-main-baseline | tail -n 1)"
  BASE_TREE="$BASE_TASK/worktree"
  git -C "$MAIN" worktree add --detach "$BASE_TREE" "$BASE_HEAD"
  (cd "$BASE_TREE" && JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off "${ROM_ARGS[@]}" test) >"$BASE_TASK/mvn-test.log" 2>&1
  BASE_EXIT=$?
  sha256sum "$BASE_TASK/mvn-test.log"
  git -C "$BASE_TREE" status --short
  git -C "$MAIN" worktree remove "$BASE_TREE"
  printf 'base=%s exit=%s s1=%s s2=%s s3k=%s\n' \
    "$BASE_HEAD" "$BASE_EXIT" "$S1_ROM" "$S2_ROM" "$S3K_ROM" \
    >"$BASE_TASK/result.txt"
  ```

  Preserve the exact failure/error list. A red baseline is acceptable; a new
  branch failure is not.

- [ ] **Step 1: Write the failing oracle-integrity test**

  Add a JUnit test which loads the candidate oracle and rejects the original
  pre-write shape:

  ```java
  @Test
  void correctedOracleUsesPostUpdateCyclesAndZeroDmaStalls() throws Exception {
      YmNativeOracle oracle = YmNativeOracle.load(ORACLE);
      assertEquals("post_fm_update", oracle.eventPhase());
      assertEquals(33,
              oracle.groups().get(7).writes().getLast().sourceOrdinal());
      assertEquals(151_590L,
              oracle.groups().get(7).relativeLastMasterCycle());
      assertTrue(oracle.groups().stream()
              .flatMap(group -> group.writes().stream())
              .allMatch(write -> write.dmaStallCount() == 0));
  }
  ```

- [ ] **Step 2: Run the RED test**

  Run:

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dtest=com.openggf.audio.synth.TestYm2612ChipGpgxParity \
      test
  ```

  Expected: FAIL because no retained corrected oracle/parser exists.

- [ ] **Step 3: Add the diagnostic-only native patch**

  The patch must call the real write first and record afterward:

  ```c
  void fm_write(unsigned int cycles, unsigned int address, unsigned int data)
  {
    fm_write_impl(cycles, address, data);
    if (__builtin_expect(gpgx_audio_trace_enabled != 0, 0))
      gpgx_audio_trace_fm_write(cycles, address, data,
        gpgx_audio_trace_dma_stall_count);
  }
  ```

  Instrument `z80_request_68k_bus_access()` to increment the group-local stall
  counter only when `Z80.cycles` is advanced to `dma_endCycles`. Keep this lab
  event outside the production ABI5 patch.

- [ ] **Step 4: Add the opt-in lab capture and no-replace writer**

  `GpgxYmWriteTimingLabTests` must stream the reviewed movie through
  `GpgxHost.AdvanceDiagnosticAudio()`, join FM5 writes to the twelve key-ons,
  normalize each group to its first max-release write, and write a new candidate
  path. It must fail on overflow, native fault, non-dense ordinals, a write event
  before the `fm_update` frontier, or any DMA stall in an audited group.

- [ ] **Step 5: Regenerate twice and publish only a byte-identical compact oracle**

  Allocate agent-owned scratch, record free-space status, and run twice with
  separate outputs:

  ```bash
  agent-scratch status
  TASK_DIR="$(agent-scratch new s3k-ym-write-oracle | tail -n 1)"
  test -d "$TASK_DIR"

  S3K_ROM_PATH="${OPENGGF_MAIN_WORKSPACE:?}/s3k.gen" \
  S3K_BK2_PATH="$PWD/src/test/resources/traces/s3k/runs/\
s3k-sonic-tails-complete-emeralds/s3k-sonic-tails-complete-emeralds.bk2" \
  tools/bizhawk-headless/native/gpgx-audio-lab/\
capture-ym-write-timing.sh --game s3k --output "$TASK_DIR/a.json"

  S3K_ROM_PATH="${OPENGGF_MAIN_WORKSPACE:?}/s3k.gen" \
  S3K_BK2_PATH="$PWD/src/test/resources/traces/s3k/runs/\
s3k-sonic-tails-complete-emeralds/s3k-sonic-tails-complete-emeralds.bk2" \
  tools/bizhawk-headless/native/gpgx-audio-lab/\
capture-ym-write-timing.sh --game s3k --output "$TASK_DIR/b.json"

  cmp "$TASK_DIR/a.json" "$TASK_DIR/b.json"
  ```

  Assert the corrected raw capture hashes before compacting:

  ```text
  native-writes.tsv 33cef3472ad2c9c0d0d50e27f6ae574b51e02755420cd9c542b0443996013f99
  native-fm5.s32le  4277bc5f29fa086013b49f006fd887b9795ebfbb17e8288de4c50005bb97e6d8
  ```

  If adding the DMA marker changes `native-writes.tsv`, record the new hash and
  require its address/data/cycle projection to equal the corrected hash above.

- [ ] **Step 6: Run corrected replay/collapse diagnostics**

  Expected representative group 7 results:

  ```text
  native key-on   [760, 976, 881, 1023]
  collapsed       [132, 384, 273, 1023]
  current engine  [130, 385, 274, 1023]
  exact replay RMS 5571.46; native RMS 5565.97
  ```

  These diagnose relative timing only; do not assert absolute VInt phase.

- [ ] **Step 7: Commit the retained diagnostic slice**

  Stage only the lab patch/script, compact oracle, lab registration/host boundary,
  and oracle test. Do not stage raw captures or unrelated spike code.

  ```bash
  git commit -m "test(audio): retain corrected S3K YM timing oracle"
  ```

---

### Task 2: Derive and freeze the S3K source timing profile

**Files:**
- Create: `src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java`
- Create: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kYmServiceTimingProfile.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kYmServiceTimingProfile.java`
- Create: `docs/architecture/research/audio/s3k-ym-write-timing-calculation-v1.json`
- Create: `docs/architecture/research/audio/2026-08-22-s3k-ym-write-timing-calculation.md`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/presentation/SmpsAssetCatalog.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
- Test: config-copy tests which enumerate all production configs.

**Interfaces:**
- Produces:

  ```java
  public interface YmServiceTimingProfile {
      enum SegmentKind {
          SFX_ADMISSION_PREP, SFX_MAX_RELEASE, FM_VOICE_UPLOAD, KEY_OFF,
          FREQUENCY_AND_KEY_ON, COMPLETION_RESTORE
      }
      record Variant(int port, int operatorCount,
                     boolean bankedVoice, boolean ssgEg,
                     int carrierMask, PathKind path) { }
      enum PathKind {
          FIRST_ADMISSION, FIRST_VOICE_ATTACK,
          ORDINARY_NOTE, COMPLETION_RESTORE
      }
      record Segment(SegmentKind kind, Variant variant,
                     long[] advanceBeforeWriteMasterCycles) { }
      Segment requireSegment(SegmentKind kind, Variant variant);
      int maximumWritesPerDriverService();
      static YmServiceTimingProfile none();
  }
  ```

- Produces builder methods
  `SmpsSequencerConfig.Builder.ymServiceTimingProfile(...)` and accessor
  `getYmServiceTimingProfile()`.

- [ ] **Step 1: Write RED tests for exact profile shape and config copying**

  Require S1/S2 to return `none()`, S3K to return the locked-on profile, and all
  builder/copy paths to preserve the same immutable profile identity.

- [ ] **Step 2: Write the machine-readable checked timing calculation**

  The JSON fixture must encode each write as executable rows rather than one
  copied total:

  ```json
  {
    "schema": "openggf.s3k-ym-write-calculation.v1",
    "segments": [{
      "kind": "SFX_MAX_RELEASE",
      "variant": {"port":1,"operators":4,"carrier_mask":14},
      "source_prefix_steps": [
        {"opcode":"CALL zWriteFMIorII","count":1,"t_states":17}
      ],
      "writes": [{
        "slot": 0,
        "advance_before_write_steps": [],
        "expected_delta_master_cycles": 0
      }]
    }]
  }
  ```

  The normalized boundary is the instant of the segment's first hardware write:
  slot 0 therefore has no `advance_before_write_steps` and is exactly zero.
  `source_prefix_steps` documents source work before that anchor but is not part
  of the relative vector. For every later slot, test code sums its
  `advance_before_write_steps` as `count * t_states * 15` with checked
  arithmetic, adds only declared bank-wait rows, then cumulatively sums those
  advances. It compares the derived per-slot advance, cumulative segment
  vector, cross-segment advance, production profile, and native oracle
  independently. The accompanying Markdown document must
  enumerate and cite the executed opcodes/T-states for
  `zWriteFMIorII`, `zWriteFMI`, `zWriteFMII`, `zSetMaxRelRate`,
  `zFMOperatorWriteLoop`, `zSendFMInstrument`, `zSendFMInstrData`,
  `zKeyOnOff`, frequency writes, completion, and restore. Include the GPGX
  average three-T-state bank wait and prove zero captured DMA stalls.

- [ ] **Step 3: Assert the exact first-slice cycle vector**

  The S3K test must independently derive and assert this normalized vector for
  the audited FM5 path:

  ```java
  long[] relative = {
      0, 3150, 6300, 9450, 15885, 19110, 22875, 26445,
      30015, 33585, 37155, 40725, 44295, 47865, 51435,
      55005, 58575, 62145, 65715, 69285, 72855, 76425,
      79995, 83565, 87135, 90705, 95850, 99675, 103500,
      107325, 115380, 146010, 148710, 151590
  };
  assertEquals(10_106L, relative[33] / 15L);
  ```

  Compare each delta to the checked opcode subtotal, not merely to the oracle.
  Freeze the segment partition and cross-segment advances explicitly:

  ```text
  SFX_ADMISSION_PREP: separate prior service, key-off + four SSG-EG clears,
      relative writes [0, 3570, 6720, 9870, 13020]
  SFX_MAX_RELEASE: indices 0..3, relative [0, 3150, 6300, 9450]
  FM_VOICE_UPLOAD: indices 4..29; first cross-segment advance 6435,
      final combined relative cycle 107325
  KEY_OFF: index 30; cross-segment advance 8055
  FREQUENCY_AND_KEY_ON: indices 31..33; advances [30630, 2700, 2880]
  ```

  `carrierMask` selects the `zSendTL` branch: carrier operators include the
  additional `add a,(ix+Volume)` T-states. Tests cover at least all-carrier,
  mixed-carrier, and no-carrier layouts and reject masks outside four bits.

- [ ] **Step 4: Implement immutable profile validation**

  Reject negative advances, empty required segments, mutable arrays, duplicate
  `(kind, variant)` keys, totals outside checked `long` arithmetic, and a maximum
  service count below any segment count. `none()` must contain zero segments and
  preserve immediate behavior.

- [ ] **Step 5: Run focused tests**

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dtest='com.openggf.game.sonic3k.audio.TestSonic3kYmServiceTimingProfile,*SmpsSequencerConfig*' \
      test
  ```

  Expected: all green; no S1/S2 profile enabled.

- [ ] **Step 6: Commit**

  ```bash
  git commit -m "feat(audio): define source-derived S3K YM timing"
  ```

  Include the required `CHANGELOG.md` entry or a policy-valid inline reason
  referring to the branch's final changelog commit.

---

### Task 3: Implement the bounded YM-internal write timeline

**Files:**
- Create: `src/main/java/com/openggf/audio/synth/YmWriteTimeline.java`
- Create: `src/test/java/com/openggf/audio/synth/TestYmWriteTimeline.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestYm2612ChipGpgxParity.java`

**Interfaces:**
- Produces:

  ```java
  public final class YmWriteTimeline {
      public static final long MASTER_CYCLES_PER_INTERNAL_SAMPLE = 1008L;
      public record Entry(long dueMasterCycle, long sourceOrdinal,
              int port, int register, int value, long driverGeneration,
              long serviceOrdinal, SmpsSourceDescriptor sourceDescriptor,
              YmServiceTimingProfile.SegmentKind segment) { }
      public record Snapshot(int capacity, long nextOrdinal,
              List<Entry> pending) { }
      public void commit(List<Entry> journal);
      public void drainDue(long renderedMasterCycle, Consumer<Entry> mutation);
      public void discardBeforeGeneration(long generation);
      public Snapshot captureSnapshot();
      public void restoreSnapshot(Snapshot snapshot);
  }
  ```

- `Ym2612Chip` produces `renderedMasterCyclesForTesting()` and a package-private
  drain hook invoked before each new internal sample.

- [ ] **Step 1: Write RED queue tests**

  Cover due-cycle/ordinal sorting, same-cycle stability, checked arithmetic,
  duplicate ordinal rejection, immutable entry data, capacity N/N-1, generation
  discard, and snapshot defensive copies.

- [ ] **Step 2: Write the RED chip-boundary test**

  Schedule a register write at master cycle 3,150. Assert four old-state internal
  samples are rendered through frontier 4,032, then the write applies before the
  fifth sample. Test direct internal rate, linear resampling, blip resampling,
  and a hybrid-sized render request.

- [ ] **Step 3: Implement the minimal bounded queue**

  Use an array-backed fixed-capacity queue with checked `long` comparisons. Do
  not merge writes or allocate during drain. `commit` validates the entire
  journal before mutating queue state.

- [ ] **Step 4: Add the YM internal drain boundary**

  At the start of each `renderOneSample`, drain entries whose due cycle is less
  than or equal to the already-rendered frontier; apply via a private immediate
  `write` path so draining cannot enqueue recursively. Increment the frontier by
  exactly 1008 after the internal sample.

- [ ] **Step 5: Keep chip callbacks drain-bound**

  Assert `ChipWriteObserver.onYm2612Write` and key-on attenuation fire only when
  the entry drains. A generation discard before due must fire neither callback.

- [ ] **Step 6: Run focused tests**

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dtest='com.openggf.audio.synth.TestYmWriteTimeline,com.openggf.audio.synth.TestYm2612ChipGpgxParity' \
      test
  ```

- [ ] **Step 7: Commit**

  ```bash
  git commit -m "feat(audio): schedule YM writes at internal chip time"
  ```

---

### Task 4: Make synth timeline state snapshot-safe and barrier-safe

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestYmWriteTimeline.java`
- Modify: synth snapshot tests under `src/test/java/com/openggf/audio/synth/`

**Interfaces:**
- `VirtualSynthesizer.Snapshot` gains timeline snapshot, rendered master-cycle
  frontier, and generation.

- [ ] **Step 1: Write RED synth snapshot tests**

  Capture with multiple pending entries, render to completion, restore, and
  require byte-identical PCM, chip callbacks, queue state, frontier, and
  ordinals. Mutate returned entry lists/arrays and require the live synth to
  remain unchanged.

- [ ] **Step 2: Write RED generation-barrier tests**

  Prove:

  ```text
  hard reset before due -> discarded, zero chip callbacks
  synth replacement     -> discarded, zero chip callbacks
  full silence barrier  -> discarded, zero chip callbacks
  ```

- [ ] **Step 3: Implement self-contained synth snapshots and barriers**

  Never store a live object in a timeline entry. Increment generation before
  reset/replacement/full-silence writes and snapshot the exact resampler/internal
  frontier required to reproduce drain order.

- [ ] **Step 4: Prove restore-and-drain callback identity**

  Restore the same snapshot twice into fresh synths and require one callback per
  committed write in identical order, with no callback for discarded generations.

- [ ] **Step 5: Run synth snapshot tests**

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dtest='com.openggf.audio.synth.TestYmWriteTimeline,*VirtualSynthesizer*Snapshot*' \
      test
  ```

- [ ] **Step 6: Commit**

  ```bash
  git commit -m "fix(audio): snapshot pending YM writes"
  ```

---

### Task 5: Add one global transactional driver-service clock

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/Synthesizer.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`
- Modify: driver snapshot/rewind tests under `src/test/java/com/openggf/audio/rewind/`
- Modify: contention/service observer tests.

**Interfaces:**
- Produces:

  ```java
  public interface Synthesizer {
      interface YmTimingScope extends AutoCloseable {
          void consumeSuppressedHardwareAttempt();
          @Override void close();
          static YmTimingScope immediate() {
              return ImmediateScope.INSTANCE;
          }
      }

      enum ImmediateScope implements YmTimingScope {
          INSTANCE;
          @Override public void consumeSuppressedHardwareAttempt() { }
          @Override public void close() { }
      }

      default YmTimingScope beginYmTiming(
              Object source,
              YmServiceTimingProfile.SegmentKind kind,
              YmServiceTimingProfile.Variant variant) {
          return YmTimingScope.immediate();
      }
  }
  ```

- `SmpsDriver` implements the scope with one service cursor shared across every
  sequencer/segment in source service order.
- `SmpsDriver.publishAuthorizedFmWrite(...)` is the single internal publication
  boundary after authorization. `SmpsDriver.writeFm(...)` performs ordinary
  channel arbitration and then delegates to it. The already-authorized
  `applyAdmissionFmPreparation(...)` path delegates directly to it instead of
  its current `super.writeFm(...)`, so admission-preparation slots participate
  in the same service transaction without a second arbitration pass.
- An authorized `SmpsDriver.writeFm` call consumes the next active timing slot
  atomically at the point it commits a real hardware write. A source helper that
  executes its suppression path calls `consumeSuppressedHardwareAttempt()` to
  advance source time without queuing an entry. Attempted writes rejected only
  by OpenGGF arbitration cannot silently consume an audited native slot; the
  semantic path test must classify them explicitly.
- `SmpsDriverSnapshot` captures only stable committed state after service
  publication: service cursor, service/write ordinals, driver generation, and
  the committed (possibly only partially drained) timeline through
  `VirtualSynthesizer.Snapshot`. The unpublished write and logical-observer
  journals remain transaction-local and can never appear in a rewind snapshot.
  `LiveCommandMutationToken` captures the same stable command-boundary state;
  an in-flight service failure rolls back from its private journal rather than
  exposing a half-service snapshot.

- [ ] **Step 1: Write RED global-order tests**

  Stage SFX completion/restore, an unprofiled music write, and a later profiled
  key-on in one service. Require due-cycle/ordinal order to match call order and
  prove the unprofiled write cannot leapfrog a delayed entry. Starting a new
  Java method must not reset the service cursor.

- [ ] **Step 2: Write RED scope-poison tests**

  Reject nested scopes, missing/excess authorized writes, wrong variant,
  unauthorized `consumeSuppressedHardwareAttempt`, negative or overflowing
  cursor advance, close-before-count, and write-after-close. Every rejection
  must preserve driver, locks, queue, chip, and observer state. Add one suppressed
  native-helper test which advances the cursor but publishes no entry.
  Add an admission-preparation test proving its key-off/SSG-EG writes consume
  `SFX_ADMISSION_PREP` exactly once through `publishAuthorizedFmWrite`, do not
  re-run arbitration, and cannot bypass or duplicate logical/chip callbacks.
  Audit every remaining scoped `super.writeFm(...)` call: full-silence/reset
  paths must be named generation barriers; any source-service path must use the
  authorized publication boundary or have an explicit poison test proving it is
  outside this timing slice.

- [ ] **Step 3: Stage logical observer notifications**

  Buffer `SmpsDriverServiceObserver` and `SfxContentionObserver` notifications in
  the service transaction. Publish only after timing/count/capacity validation
  and queue commit. Keep `ChipWriteObserver` out of this journal.

- [ ] **Step 4: Preflight aggregate capacity**

  Compute the live service bound from active music/SFX tracks, completion/restore
  work, and one PAL repeated service. N succeeds. N-1 must fail before logical
  callbacks or chip mutation and restore cursor/locks/sequencer state.

- [ ] **Step 5: Add driver rewind, adoption, and ordinary-stop semantics**

  Inject count, capacity, and scope-poison failures during service construction;
  require the transaction-local journal and `LiveCommandMutationToken` rollback
  to preserve cursor, ordinals, queue, callbacks, locks, and synth state without
  making an in-flight snapshot observable. Separately capture
  `SmpsDriverSnapshot` only after atomic service commit, both before drain and
  after a partially drained voice upload; restore must reproduce the remaining
  entries and callbacks exactly once. Prove ordinary completion and
  `stopAllSfx` retain committed entries, while `adoptActiveSfxFrom` remaps their
  generation by immutable `SmpsSourceDescriptor` and drains each once.

- [ ] **Step 6: Prove hybrid and sample-accurate equivalence**

  Render the same pending service using `ReadMode.HYBRID` and
  `ReadMode.SAMPLE_ACCURATE`; compare PCM, drained entries, callback order, and
  final snapshots byte-for-byte.

- [ ] **Step 7: Run focused driver and rewind tests**

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dtest='com.openggf.audio.driver.TestSmpsDriverYmWriteTimeline,*SfxContention*,*ServiceObserver*,*SmpsDriver*Snapshot*,*AudioRewind*' \
      test
  ```

- [ ] **Step 8: Commit**

  ```bash
  git commit -m "feat(audio): transact source-timed YM driver services"
  ```

---

### Task 6: Wire the audited locked-on S3K source paths

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsTrackSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kBlueSphereSfxParity.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/TestS3kSpecialStageAudioPlaybackTrace.java`
- Test: another S3K FM SFX fixture in `TestS3kBlueSphereSfxParity`.

**Interfaces:**
- Consumes the exact six `SegmentKind` values and S3K variants from Task 2.
- Produces no new public gameplay/audio-manager API.

- [ ] **Step 1: Write the real behavioral RED**

  Drive special-stage music and the reviewed admission intervals. Classify first,
  overlapping, completed-then-idle, and post-other-FM5 admissions separately.
  Assert current atomic output matches the known collapsed key-on state and fails
  the source-timed relative-cycle expectation.

- [ ] **Step 2: Add four synthetic starting-envelope RED cases**

  Seed FM5 at attack, decay, sustain, and near-release states, then compare the
  atomic and source-timed write groups. Fix the acceptance metric from the
  corrected oracle before production wiring; require every isolated state to
  improve and overlapping controls not to regress.

- [ ] **Step 3: Open timing scopes at source-shaped boundaries**

  Wrap only:

  ```text
  applyAdmissionFmPreparation -> SFX_ADMISSION_PREP / FIRST_ADMISSION
  prepareVoiceSelection -> SFX_MAX_RELEASE
  first refreshInstrument after an admitted voice selection
                        -> FM_VOICE_UPLOAD / FIRST_VOICE_ATTACK
  note key-off           -> KEY_OFF
  the matching first frequency + key-on after that upload
                        -> FREQUENCY_AND_KEY_ON / FIRST_VOICE_ATTACK
  completion/restore     -> COMPLETION_RESTORE
  ```

  Use semantic port/operator/banked/SSG/carrier-mask inputs. Do not inspect the
  sound ID or register pattern to select a segment. Track the first-admission
  path with an explicit per-track source state which is set by admitted voice
  selection and consumed by its first attack. Ordinary note key-off, frequency,
  key-on, modulation frequency updates, music refreshes, and later attacks stay
  outside this first profile. Add poison tests proving those ordinary branches
  do not open or consume an audited timing segment.

  The segment slot is consumed in `SmpsDriver.writeFm` only after channel
  authorization chooses to publish the hardware write. A native source helper
  suppression advances with `consumeSuppressedHardwareAttempt()` and no entry.
  Add an overridden-music test proving a suppressed helper consumes only its
  declared suppression slot and cannot shift the following SFX key-on.

  Persist that first-admission source state in `SmpsTrackSnapshot` and the
  enclosing `SmpsSequencerSnapshot`. `SmpsSequencer.LiveCommandMutationToken`
  must capture and restore it through the existing sequencer snapshot plus the
  identity-preserving Track restore. Add RED tests which snapshot and restore,
  and separately capture/rollback a live mutation, after admission but before
  first voice/attack; neither path may reopen, skip, or double-consume the timed
  first path. Run `TestRewindCoverageGuard` with the focused rewind suite.

- [ ] **Step 4: Verify exact register/value preservation**

  Compare pre-change and post-change ordered writes. Only due master cycles may
  differ. Ring panning, special-stage tempo, DAC, and PSG writes must remain
  byte-identical.

- [ ] **Step 5: Prove Spike Hit uses the same operation profile**

  Use source-verified `sfx_SpikeHit` / `Sound_37` on FM5
  (`docs/skdisasm/Sound/SFX/37 - Spike Hit.asm`): voice 0, algorithm 3 retained
  as voice data, and exact stored-operator carrier/TL timing mask `0b1000`
  derived from the TL bytes `$29,$20,$0F,$80` because locked-on `zSendTL` and
  OpenGGF `VolMode.BIT7` take the volume-add path from each stored TL byte's bit
  7. Its first notes are `nFs6` then `nD7`, with later modulation updates
  explicitly outside `FIRST_VOICE_ATTACK`. Assert the timing profile is chosen
  from the same admission/voice-operation semantics as Blue Sphere despite the
  different sound ID, voice data, notes, and modulation, and that the first
  write count/relative deltas validate without an ID-specific branch.

- [ ] **Step 6: Run focused S3K tests with the verified ROM**

  ```bash
  MAIN="${OPENGGF_MAIN_WORKSPACE:?set to the absolute main-workspace path}"
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off -Ds3k.rom.path="$MAIN/s3k.gen" \
      -Dtest='com.openggf.audio.driver.TestS3kBlueSphereSfxParity,com.openggf.tests.trace.s3k.TestS3kSpecialStageAudioPlaybackTrace,*Sonic3k*Audio*,*RewindCoverageGuard*' \
      test
  ```

- [ ] **Step 7: Commit**

  ```bash
  git commit -m "fix(audio): preserve S3K FM service write timing"
  ```

---

### Task 7: Close native parity and audit S1/S2 without automatic uplift

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh`
- Modify: `tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs`
- Modify: `src/test/java/com/openggf/audio/synth/TestYm2612ChipGpgxParity.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kBlueSphereSfxParity.java`
- Create: `src/test/java/com/openggf/audio/synth/TestS1S2YmWriteTimingAudit.java`
- Create: `docs/architecture/audits/audio/2026-08-22-s1-s2-ym-write-timing-audit.md`
- Add focused unchanged controls to S1/S2 audio tests.
- Conditionally create separate S1/S2 profile tasks only if this audit proves a
  material isolated-replay defect; do not add them inside this commit.

**Interfaces:**
- Produces the explicit S1 and S2 ruling with source labels, corrected capture
  hashes, isolated/overlap results, and follow-up issue/plan paths if needed.

- [ ] **Step 1: Run corrected S3K native parity**

  Regenerate the compact oracle and require exact relative master-cycle deltas,
  register/value order, zero DMA markers, zero overflow/fault, and deterministic
  A/B output. Absolute first-write phase is informational only.

- [ ] **Step 2: Audit S1 source and corrected native timing**

  Extend the lab with `--game s1 --sound-id 0xB5 --fm-channel 4`. Use the
  existing reviewed movie and pin its hash:

  ```text
  src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/
    sonic1-complete-withemeralds.bk2
  SHA-256 f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b
  sound SndB5_Ring, FM5
  ```

  Join groups to the source-auth ring request/admission event, not a register
  fingerprint. Cover `FinishTrackUpdate`, `WriteFMIorII`, `WriteFMI`,
  `WriteFMII`, `cfSetVoice`/`SetVoice`, and `cfStopTrack` in
  `docs/s1disasm/s1.sounddriver.asm`, plus isolated and overlapping ring groups.
  S1 calculations use 68k instruction/master-cycle timing and must not reuse Z80
  T-state constants.

- [ ] **Step 3: Audit S2 source and corrected native timing**

  Extend the same lab with `--game s2 --sound-id 0xB5 --fm-channel 4`. Use:

  ```text
  src/test/resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/
    sonic-2-sonic-tails-complete-emeralds.bk2
  SHA-256 e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5
  sound Sound35_RingRight, FM5
  ```

  Cover `zWriteFMIorII`, `zWriteFMI`, `zWriteFMII`, `zSetMaxRelRate`,
  `cfSetVoice`/`zSetVoice`, `zFinishTrackUpdate`, and `cfStopTrack` in
  `docs/s2disasm/s2.sounddriver.asm`, including `FixDriverBugs = 0`, bank access,
  DMA markers, isolated groups, and overlapping groups.

  Allocate one scratch root for all A/B audit captures:

  ```bash
  agent-scratch status
  TASK_DIR="$(agent-scratch new s1-s2-ym-write-audit | tail -n 1)"
  export S1_ROM_PATH="${OPENGGF_MAIN_WORKSPACE:?}/s1.gen"
  export S2_ROM_PATH="${OPENGGF_MAIN_WORKSPACE:?}/s2.gen"
  export S1_BK2_PATH="$PWD/src/test/resources/traces/s1/runs/\
s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2"
  export S2_BK2_PATH="$PWD/src/test/resources/traces/s2/runs/\
s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2"
  test "$(sha1sum "$S1_ROM_PATH" | cut -d' ' -f1)" = 69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b
  test "$(sha1sum "$S2_ROM_PATH" | cut -d' ' -f1)" = 8bca5dcef1af3e00098666fd892dc1c2a76333f9
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s1 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_DIR/s1-a.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s1 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_DIR/s1-b.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s2 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_DIR/s2-a.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s2 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_DIR/s2-b.json"
  cmp "$TASK_DIR/s1-a.json" "$TASK_DIR/s1-b.json"
  cmp "$TASK_DIR/s2-a.json" "$TASK_DIR/s2-b.json"
  ```

- [ ] **Step 4: Apply the ruling**

  Predeclare a material difference before reading results: at least one isolated
  group spans four or more internal YM samples (`>= 4032` master cycles) **and**
  collapsing the group changes any key-on operator attenuation by at least eight
  units or changes bounded onset RMS by at least 1%. If neither game crosses the
  threshold, keep `none()` and record exact values. If one crosses it, leave its
  runtime unchanged, record capture/core/ROM/movie hashes, and create a separate
  per-game design/plan path; do not add its profile inside this S3K commit.

- [ ] **Step 5: Run three-ROM controls**

  ```bash
  MAIN="${OPENGGF_MAIN_WORKSPACE:?set to the absolute main-workspace path}"
  S1_ROM="$MAIN/s1.gen"
  S2_ROM="$MAIN/s2.gen"
  S3K_ROM="$MAIN/s3k.gen"
  test "$(sha1sum "$S1_ROM" | cut -d' ' -f1)" = 69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b
  test "$(sha1sum "$S2_ROM" | cut -d' ' -f1)" = 8bca5dcef1af3e00098666fd892dc1c2a76333f9
  test "$(sha1sum "$S3K_ROM" | cut -d' ' -f1)" = cfbf98c36c776677290a872547ac47c53d2761d6
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    mvn -Dmse=off \
      -Dsonic1.rom.path="$S1_ROM" \
      -Dsonic2.rom.path="$S2_ROM" \
      -Ds3k.rom.path="$S3K_ROM" \
      -Dtest='*UnifiedAudioPresentationRomIntegration,*SmpsSequencer*,*SfxParity*' \
      test
  ```

  Reuse these same three authenticated absolute paths in every baseline,
  development, and package command; never rename, copy, or symlink a ROM.

- [ ] **Step 6: Commit the audit and parity gates**

  ```bash
  git commit -m "test(audio): close cross-game YM timing audit"
  ```

---

### Task 8: Final verification and listening handoff

**Files:**
- Create: `docs/architecture/validation/audio/2026-08-22-s3k-blue-sphere-audio-validation.md`
- Modify: `CHANGELOG.md`
- Modify: S3K known-discrepancy documentation to close or narrow the audible
  issue.
- Remove all superseded diagnostic spike code and untracked scratch artifacts
  from the deliverable.

**Interfaces:**
- Produces a local testable branch and exact commit for the user's listen test.
- Does not merge or push `develop`.

- [ ] **Step 1: Verify a clean diff and remove throwaway spike code**

  ```bash
  git status --short
  git diff --check
  rg -n 'openggf\.blueDriverDiag|OPENGGF_BLUE_PCM_DIAG|/tmp/openggf-blue' \
    src tools docs
  ```

  Only the retained lab names and documented regeneration command may remain.

- [ ] **Step 2: Run focused native/oracle gates twice**

  Require byte-identical compact oracle, no DMA stalls, no trace fault/overflow,
  and exact source-relative cycle vectors.

- [ ] **Step 3: Run the full JDK 21 suite and ROM-backed audio suite**

  ```bash
  MAIN="${OPENGGF_MAIN_WORKSPACE:?set to the absolute main-workspace path}"
  S1_ROM="$MAIN/s1.gen"
  S2_ROM="$MAIN/s2.gen"
  S3K_ROM="$MAIN/s3k.gen"
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn -Dmse=off \
    -Dsonic1.rom.path="$S1_ROM" \
    -Dsonic2.rom.path="$S2_ROM" \
    -Ds3k.rom.path="$S3K_ROM" test
  ```

  Then run the three-ROM command from Task 7 with discovered paths. Record exact
  totals, skips, failures, JVM, Maven version, and any baseline-only failures.
  Compare by test identity against the `BASE_HEAD` and baseline log from Task 1;
  no baseline-passing test may fail and no baseline failure may worsen.

- [ ] **Step 4: Run rewind, observer, cadence, and presentation regression groups**

  Explicitly include S3K service order, contention, modulation, pause, fade,
  one-up, PAL, ring panning, special-stage speed, OpenAL packetization, and
  deterministic snapshot/restore.

- [ ] **Step 5: Write the validation report**

  Record design/plan/commit hashes, native core/patch/oracle hashes, source cycle
  vector, all commands/results, S1/S2 ruling, known relative-only limitation,
  and the fact that merge is blocked on listening confirmation.

- [ ] **Step 6: Commit final docs and policy files**

  ```bash
  git commit -m "docs(audio): validate source-timed S3K FM playback"
  ```

- [ ] **Step 7: Build the exact handoff commit**

  After all commits, build—not merely test—the executable for the exact HEAD and
  record both hashes:

  ```bash
  HANDOFF_HEAD="$(git rev-parse HEAD)"
  MAIN="${OPENGGF_MAIN_WORKSPACE:?set to the absolute main-workspace path}"
  S1_ROM="$MAIN/s1.gen"
  S2_ROM="$MAIN/s2.gen"
  S3K_ROM="$MAIN/s3k.gen"
  test "$(sha1sum "$S1_ROM" | cut -d' ' -f1)" = 69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b
  test "$(sha1sum "$S2_ROM" | cut -d' ' -f1)" = 8bca5dcef1af3e00098666fd892dc1c2a76333f9
  test "$(sha1sum "$S3K_ROM" | cut -d' ' -f1)" = cfbf98c36c776677290a872547ac47c53d2761d6
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn -Dmse=off \
    -Dsonic1.rom.path="$S1_ROM" \
    -Dsonic2.rom.path="$S2_ROM" \
    -Ds3k.rom.path="$S3K_ROM" package
  test "$HANDOFF_HEAD" = "$(git rev-parse HEAD)"
  git diff --quiet && git diff --cached --quiet
  sha256sum target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
  ```

- [ ] **Step 8: Hand off the local branch for listening**

  Report:

  ```text
  worktree: output of `git rev-parse --show-toplevel`
  branch: feature/ai-smps-playback-verification
  commit: output of `git rev-parse HEAD`, copied as the exact 40-hex commit
  jar SHA-256: output of `sha256sum`, copied exactly
  run: java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
  ```

  Ask the user to test first pickup, rapid consecutive pickups, first pickup
  after completion/turn, pickup after another FM5 SFX, rings, and special-stage
  speed-shoes entry. Do not merge or push until they confirm a positive result.

---

## Plan self-review checklist

- [ ] Every design requirement maps to a task: corrected evidence (1), source
  timing/DMA (2), YM clock/drain (3), snapshots/barriers (4), global order and
  observer transaction (5), S3K behavior (6), S1/S2 ruling (7), human gate (8).
- [ ] No runtime task reads traces or branches on ID/game/zone/movie/frame.
- [ ] No task claims absolute VInt phase, exact native attenuation, or
  DMA-contended parity.
- [ ] Logical observers are commit-staged; chip observers remain drain-bound.
- [ ] Committed entries survive ordinary completion/stop; only generation
  barriers discard them.
- [ ] S1/S2 do not receive S3K timing by symmetry.
- [ ] All production file edits have explicit focused and final verification.
- [ ] The delivery flow stops before merge/push for the user's listen test.
