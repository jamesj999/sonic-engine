# Sonic 1 YM Write-Timing Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Source-time the authenticated Sonic 1 FM5 `SetVoice -> optional Pan
-> Note` SFX onset so a previous music instrument cannot leak into the first
audible attack, while leaving unsupported S1 paths, S2, S3K, and completion
timing unchanged.

**Architecture:** Extend the existing bounded YM timeline with one immutable,
incrementally resolved S1 source-cost/BUSY program. A side-effect-free
stream-shape classifier enables it only for the retained Ring control-flow
shapes, one program cursor spans Java helper boundaries, and the unique FM5 SFX owner
reserves the existing 4,096-entry queue atomically. Comparison-only native
oracles and source ledgers validate the typed production profile; runtime never
reads a trace or research artifact.

**Tech Stack:** Java 21, Maven/JUnit Jupiter, checked JSON/TSV research
artifacts, retained GPGX/BizHawk native oracle, SMPS ROM loaders.

**Spec:**
`docs/architecture/designs/audio/2026-08-23-s1-ym-write-timing-profile-design.md`

## Global Constraints

- Work only in the isolated `bugfix/ai-s1-dac-pause-resume` worktree.
  on `bugfix/ai-s1-dac-pause-resume`; never switch the main workspace branch.
- Use JDK 21 and verify it with `mvn -v` before every Maven gate.
- Use the shipped `FixBugs = 0` S1 path and cite it where a conditional is
  modeled.
- Runtime assets remain ROM-only; `docs/architecture/research/audio/` is test
  and review authority only.
- Runtime selection uses typed FM5 ownership and bytecode shape, never game,
  zone, sound ID, frame, movie, or trace identity in shared code.
- S1 timing is relative from the first YM write. Do not claim or synthesize
  VInt/service-entry-to-first-write timing.
- S1 completion remains immediate; S2 remains `none()`; S3K fixed timing and
  timed completion remain byte-identical.
- Write the failing test and observe the intended RED before each production
  change.
- Every commit command supplies all seven exact policy decisions; never rely on
  hook-generated `TODO` values and never bypass hooks.
- Do not merge or push before a positive human listening report.

## File Map

- Create
  `src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java`: immutable
  program, variants, sections, state, continuous cursor, and pure resolver.
- Modify
  `src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java`: timing
  ownership, source-program lookup, capability query, and fixed-segment
  compatibility.
- Create
  `src/main/java/com/openggf/game/sonic1/audio/Sonic1YmServiceTimingProfile.java`:
  hard-coded checked S1 program and 4,096 reservation.
- Modify
  `src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java`:
  install the S1 profile.
- Modify `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`: private
  side-effect-free first-path classifier, snapshotted path state, exact section
  transitions.
- Modify `src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java`:
  capture the classifier/path state if track snapshots are not already opaque.
- Modify `src/main/java/com/openggf/audio/driver/SmpsDriver.java`: exclusive
  FM5 profile selection, incremental program consumption, full-capacity
  reservation, completion capability gate, ordered sibling behavior.
- Modify `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java` and
  `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java` only for
  stable committed resolver/timeline state required by snapshot restoration;
  unpublished program state remains transaction-local.
- Create
  `docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.json` and
  `docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.md`:
  canonical checked derivation and provenance.
- Create `tools/audio/build-s1-ym-busy-program.py`: deterministic research
  generator from the retained ledger/calculation/source map.
- Modify the retained GPGX timing-lab patch, capture script, ledger builder, and
  C# collector so every 68K instruction row carries the cumulative refresh
  delay incremented at the exact native refresh-add sites.
- Create
  `docs/architecture/research/audio/s1-ring-no-pan-ym-write-instruction-ledger-v1.tsv`:
  A/B-authenticated decoded 30-write branch authority from the retained native
  lab.
- Create
  `src/test/java/com/openggf/audio/smps/TestSonic1YmSourceProgramTiming.java`:
  source-program parsing/resolution/oracle tests.
- Create
  `src/test/java/com/openggf/game/sonic1/audio/TestSonic1YmServiceTimingProfile.java`:
  profile equality, ROM shape census, and config/cross-game gates.
- Extend `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`:
  transaction, capacity, observer, rollback, and completion-capability tests.
- Extend
  `src/test/java/com/openggf/audio/synth/TestS1S2YmWriteTimingAudit.java`:
  production replay against all retained S1 groups and unchanged S2 ruling.
- Extend `src/test/java/com/openggf/TestSonic1UnifiedAudioPresentationRomIntegration.java`:
  ROM-backed Ring/eligible-effect/unsupported-control and pause-DAC regressions.
- Extend `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
  and `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java`:
  prevent public timing controls, runtime research-file reads, and uncaptured
  path state.
- Modify `CHANGELOG.md` and create
  `docs/architecture/validation/audio/2026-08-23-s1-fm5-ym-write-timing-validation.md`
  after verification.

---

### Task 1: Generate the Checked S1 Source Program

**Files:**
- Create: `tools/audio/build-s1-ym-busy-program.py`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-lab/0001-trace-ym-write-cycles.patch`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-lab/build-representative-ledger.sh`
- Modify: `tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs`
- Create: `docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.json`
- Create: `docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.md`
- Create: `docs/architecture/research/audio/s1-ring-no-pan-ym-write-instruction-ledger-v1.tsv`
- Modify: `docs/architecture/research/audio/s1-ring-ym-write-instruction-ledger-v1.tsv`
- Modify: `docs/architecture/research/audio/s1-ring-ym-write-audit-v2.json`
- Modify: `docs/architecture/research/audio/s2-ringright-ym-write-audit-v2.json`
- Test: `src/test/java/com/openggf/audio/smps/TestSonic1YmSourceProgramTiming.java`

**Interfaces:**
- Consumes: retained S1 audit JSON, 909-row panning instruction ledger, a fresh
  A/B-authenticated no-pan instruction ledger, v2 timing calculation, and
  canonical source map.
- Produces: deterministic `VOICE_NOTE` and `VOICE_PAN_NOTE` program rows with
  section, PC/opcode span, fixed/busy-loop cycle terms, expected register role,
  source citation, and hashes.

- [ ] **Step 1: Allocate managed scratch and recapture A/B instruction authority.**

  ```bash
  TASK_SCRATCH="$(tools/agent-scratch new s1-ym-no-pan-ledger)"
  export OPENGGF_MAIN_WORKSPACE="$(git rev-parse --show-toplevel)"
  export S1_ROM_PATH="${S1_ROM_PATH:?point this at the authenticated S1 REV01 ROM}"
  export S1_BK2_PATH="${S1_BK2_PATH:?point this at the reviewed complete-run BK2}"
  export S2_ROM_PATH="${S2_ROM_PATH:?point this at the authenticated S2 REV01 ROM}"
  export S2_BK2_PATH="${S2_BK2_PATH:?point this at the reviewed S2 complete-run BK2}"
  export GPGX_SOURCE_PATH="${GPGX_SOURCE_PATH:?point this at the pinned pristine BizHawk source}"
  export GPGX_TOOLCHAIN_PATH="${GPGX_TOOLCHAIN_PATH:?point this at the pinned native toolchain}"
  # The script independently rejects wrong commits, trees, file counts, tools,
  # stock install, patches, ROM, and BK2.
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s1 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_SCRATCH/s1-a.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s1 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_SCRATCH/s1-b.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s2 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_SCRATCH/s2-a.json"
  tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    --game s2 --sound-id 0xB5 --fm-channel 4 \
    --output "$TASK_SCRATCH/s2-b.json"
  cmp "$TASK_SCRATCH/s1-a.json" "$TASK_SCRATCH/s1-b.json"
  cmp "$TASK_SCRATCH/s1-a.native-instructions.tsv" \
      "$TASK_SCRATCH/s1-b.native-instructions.tsv"
  cmp "$TASK_SCRATCH/s2-a.json" "$TASK_SCRATCH/s2-b.json"
  cmp "$TASK_SCRATCH/s2-a.native-instructions.tsv" \
      "$TASK_SCRATCH/s2-b.native-instructions.tsv"
  bash tools/bizhawk-headless/native/gpgx-audio-lab/build-representative-ledger.sh \
    s2 "$TASK_SCRATCH/s2-a.native-instructions.tsv" \
    "$TASK_SCRATCH/s2-ledger.tsv"
  cmp "$TASK_SCRATCH/s2-ledger.tsv" \
    docs/architecture/research/audio/s2-ringright-ym-write-instruction-ledger-v1.tsv
  # The instruction schema includes cumulative refresh delay. The native patch
  # increments it only at the actual 14-master-cycle refresh-add sites.
  ```

- [ ] **Step 2: Write the RED artifact/parser test.**

  Add a Jupiter test that loads the canonical program path, requires schema
  `openggf.s1-ym-busy-program.v1`, both shapes, 30/31 writes, one global row-zero
  anchor, exact section counts `26/(0|1)/1/3`, and hash-pinned source inputs.
  The same test invokes the missing extractor API and requires it to select the
  lowest group ordinal satisfying: S1 FM5 authenticated owner, isolated group,
  exactly 30 writes, exactly one B5 write (the voice B4/pan field), terminal
  key-on, and zero DMA/fault/overflow. The TSV header must equal the existing
  ledger schema and each occurrence must join densely from group start through
  terminal write. Mutations must reject a deleted instruction, wrong PC/opcode, wrong cycle,
  reordered row, second zero anchor, uncited row, and unconsumed ledger row.

- [ ] **Step 3: Run the test and observe the intended RED.**

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  mvn -v
  mvn -Dmse=off -Dtest=com.openggf.audio.smps.TestSonic1YmSourceProgramTiming test
  ```

  Expected: test compilation or fixture loading fails because the program
  artifact/generator does not exist.

- [ ] **Step 4: Implement the deterministic generator.**

  Add `--extract-no-pan-ledger --oracle <json> --instructions <tsv> --output
  <tsv>` to the same tracked script. It must apply the exact lowest-qualifying
  selector above and embed oracle/instruction/group hashes in comment metadata.
  The generator must then parse the existing 31-write panning ledger and the new
  30-write no-pan ledger by ordinal, split each
  inter-write instruction slice, identify the exact busy-poll loop and fixed
  prefix/suffix, and reject unknown/duplicate/unconsumed instructions. Native
  instruction rows carry cumulative refresh delay from the exact GPGX sites
  that add 14 master cycles. Subtract only consecutive counter deltas; reject a
  regression, a non-14-cycle increment, or any remaining inconsistent BUSY
  loop. It must not infer cycles from target write gaps. Emit sorted canonical
  JSON with source SHA-256s and a Markdown calculation explaining seven master
  cycles per 68K cycle and GPGX's discrete-YM master/42, 32-clock BUSY rule.
  Captured final relative write cycles remain comparison-only fields.

- [ ] **Step 5: Generate twice and prove byte identity.**

  ```bash
  python3 tools/audio/build-s1-ym-busy-program.py \
    --extract-no-pan-ledger --oracle "$TASK_SCRATCH/s1-a.json" \
    --instructions "$TASK_SCRATCH/s1-a.native-instructions.tsv" \
    --output "$TASK_SCRATCH/no-pan-a.tsv"
  python3 tools/audio/build-s1-ym-busy-program.py \
    --extract-no-pan-ledger --oracle "$TASK_SCRATCH/s1-b.json" \
    --instructions "$TASK_SCRATCH/s1-b.native-instructions.tsv" \
    --output "$TASK_SCRATCH/no-pan-b.tsv"
  cmp "$TASK_SCRATCH/no-pan-a.tsv" "$TASK_SCRATCH/no-pan-b.tsv"
  cmp "$TASK_SCRATCH/no-pan-a.tsv" \
    docs/architecture/research/audio/s1-ring-no-pan-ym-write-instruction-ledger-v1.tsv
  bash tools/bizhawk-headless/native/gpgx-audio-lab/build-representative-ledger.sh \
    s1 "$TASK_SCRATCH/s1-a.native-instructions.tsv" \
    "$TASK_SCRATCH/pan-a.tsv"
  bash tools/bizhawk-headless/native/gpgx-audio-lab/build-representative-ledger.sh \
    s1 "$TASK_SCRATCH/s1-b.native-instructions.tsv" \
    "$TASK_SCRATCH/pan-b.tsv"
  cmp "$TASK_SCRATCH/pan-a.tsv" "$TASK_SCRATCH/pan-b.tsv"
  cmp "$TASK_SCRATCH/pan-a.tsv" \
    docs/architecture/research/audio/s1-ring-ym-write-instruction-ledger-v1.tsv
  cmp "$TASK_SCRATCH/s1-a.json" \
    docs/architecture/research/audio/s1-ring-ym-write-audit-v2.json
  cmp "$TASK_SCRATCH/s2-a.json" \
    docs/architecture/research/audio/s2-ringright-ym-write-audit-v2.json
  sha256sum "$TASK_SCRATCH/no-pan-a.tsv"
  python3 tools/audio/build-s1-ym-busy-program.py --output "$TASK_SCRATCH/program-a.json"
  python3 tools/audio/build-s1-ym-busy-program.py --output "$TASK_SCRATCH/program-b.json"
  cmp "$TASK_SCRATCH/program-a.json" "$TASK_SCRATCH/program-b.json"
  cmp "$TASK_SCRATCH/program-a.json" docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.json
  ```

- [ ] **Step 6: Run the artifact test GREEN and commit the evidence slice.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=com.openggf.audio.smps.TestSonic1YmSourceProgramTiming test
  git add tools/audio/build-s1-ym-busy-program.py \
    tools/bizhawk-headless/native/gpgx-audio-lab/0001-trace-ym-write-cycles.patch \
    tools/bizhawk-headless/native/gpgx-audio-lab/capture-ym-write-timing.sh \
    tools/bizhawk-headless/native/gpgx-audio-lab/build-representative-ledger.sh \
    tools/bizhawk-headless/tests/GpgxYmWriteTimingLabTests.cs \
    docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.json \
    docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.md \
    docs/architecture/research/audio/s1-ring-no-pan-ym-write-instruction-ledger-v1.tsv \
    docs/architecture/research/audio/s1-ring-ym-write-instruction-ledger-v1.tsv \
    docs/architecture/research/audio/s1-ring-ym-write-audit-v2.json \
    docs/architecture/research/audio/s2-ringright-ym-write-audit-v2.json \
    docs/architecture/designs/audio/2026-08-23-s1-ym-write-timing-profile-design.md \
    docs/architecture/plans/audio/2026-08-23-s1-ym-write-timing-profile-plan.md \
    src/test/java/com/openggf/audio/smps/TestSonic1YmSourceProgramTiming.java
  git commit -m "test(audio): derive S1 YM busy write program" \
    -m "Changelog: n/a" -m "Guide: n/a" \
    -m "Known-Discrepancies: n/a" -m "S3K-Known-Discrepancies: n/a" \
    -m "Agent-Docs: n/a" -m "Configuration-Docs: n/a" \
    -m "Skills: n/a"
  ```

  Verify all seven trailers are present with `git log -1 --format=%B`.

### Task 2: Add Immutable Program Types and Pure Incremental Resolution

**Files:**
- Create: `src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java`
- Modify: `src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java`
- Test: `src/test/java/com/openggf/audio/smps/TestSonic1YmSourceProgramTiming.java`
- Test: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kYmServiceTimingProfile.java`

**Interfaces:**
- Produces:
  `FirstPathShape`, `SourceProgram`, `ProgramVariant`, `ProgramWrite`,
  `ProgramSection`, `ProgramState`, `ResolvedWrite`, and
  `YmSourceProgramResolver.resolveNext(...)` exactly as specified by the design.
- Preserves: `Segment` fixed-vector construction and all S3K profile equality.

- [ ] **Step 1: Add RED constructor/copy/cap tests.**

  Assert defensive copies, immutable returned collections, maximum 31 rows,
  dense sections, one row-zero anchor, valid port/carrier mask, checked cycle
  arithmetic, and rejection of empty/duplicate/gapped sections or negative and
  overflowing costs.

- [ ] **Step 2: Add RED resolver tests.**

  Assert row zero due at service cursor; pending tail and row zero share `C`
  with lower/higher ordinals; row one is `> C`; voice-to-pan-to-key-off carries
  one cursor; no-pan skips only its authenticated branch; wrong section,
  port/register, fixed key-off/key-on value, shape, or final state fails before
  publication. Exercise all 42 service-cursor residues. Variable ROM-owned
  voice/TL/pan/frequency values must remain accepted and are checked by the ROM
  playback tests.

- [ ] **Step 3: Run and observe missing-type/behavior RED.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=TestSonic1YmSourceProgramTiming,TestSonic3kYmServiceTimingProfile test
  ```

- [ ] **Step 4: Implement the immutable types and resolver minimally.**

  Use checked `Math.addExact`. Row zero anchors at the service cursor and sets
  `ymClock = addExact(floorDiv(due, 42), due % 42 == 0 ? 0 : 1)` then
  `busyUntil = multiplyExact(addExact(ymClock, 32), 42)`; never implement
  ceiling as unchecked `due+41`. Each later row begins at
  `lastDue+fixedBeforePoll`, adds the checked 259-cycle loop while the status
  cycle is below `busyUntil`, publishes after the checked ready-to-write tail,
  and recomputes the deadline. Keep this state continuous across sections. Do
  not inspect the live chip, gameplay state, or captured final write gaps.

- [ ] **Step 5: Add `supports(kind, variant)` and ownership without changing S3K.**

  `none()` supports nothing. Fixed profiles support only exact stored keys.
  Source profiles support their first-path sections and report
  `TimingOwnership.EXCLUSIVE_SFX_FM5`. Keep existing fixed profile constructors
  source-compatible.

- [ ] **Step 6: Run GREEN and commit.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=TestSonic1YmSourceProgramTiming,TestSonic3kYmServiceTimingProfile test
  git add src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java \
    src/main/java/com/openggf/audio/smps/YmServiceTimingProfile.java \
    src/test/java/com/openggf/audio/smps/TestSonic1YmSourceProgramTiming.java \
    src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kYmServiceTimingProfile.java
  git commit -m "feat(audio): resolve continuous 68K YM timing programs" \
    -m "Changelog: n/a: intermediate source-program foundation; the user-facing entry lands after end-to-end verification" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 3: Classify Authenticated S1 Stream Shapes and Build the Profile

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/audio/Sonic1YmServiceTimingProfile.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java`
- Create: `src/test/java/com/openggf/game/sonic1/audio/TestSonic1YmServiceTimingProfile.java`

**Interfaces:**
- Produces private `FirstFmPathClassification classifyFirstFmPath(Track)` with
  `VOICE_NOTE`, `VOICE_PAN_NOTE`, or `UNSUPPORTED` and no mutation.
- Produces an uninstalled profile maximum 4,096, exclusive FM5 ownership, and
  exact hard-coded programs equal to Task 1's artifact. Installation is deferred
  until Task 4 driver integration is green.

- [ ] **Step 1: Add RED synthetic classifier tests.**

  Capture every mutable `Track` field before classification and prove equality
  afterward. Accept EF→note and EF→E0,param→note. Reject/leave immediate:
  E6 volume, E7 hold, E8 note fill, E9 transpose, second EF, repeated E0,
  F6/F7/F8/E3 control flow, end, truncated pan, and malformed note duration.

- [ ] **Step 2: Add RED ROM census tests.**

  Using the authenticated S1 REV01 ROM, load all `Sonic1Sfx.values()`, classify
  every FM track, record stable counts/shapes, prove all retained Ring 30/31
  variants are covered, and assert no eligibility by ID lookup. Verify any
  second eligible carrier-mask effect through the same classifier; do not force
  one if the retail census has none.

- [ ] **Step 3: Observe RED.**

  ```bash
  S1="${S1_ROM_PATH:?point this at the authenticated S1 REV01 ROM}"
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" \
    -Dtest=com.openggf.game.sonic1.audio.TestSonic1YmServiceTimingProfile test
  ```

- [ ] **Step 4: Implement classifier, private track state, and profile.**

  Classify from a local `ProgramView` cursor after EF's parameter. Arm timing
  only for SFX FM5 and an accepted shape. Store only the shape/program-progress
  bits needed across helper calls; capture them in the existing opaque track
  snapshot and live-command token path. Keep controls private/package-private.
  Keep driver-service reservation lookahead separate from this classifier:
  from the untouched cursor it may skip exactly one fixed-width, non-writing
  S1 F0 ModSet before requiring EF, then must reuse the same post-EF
  shape/carrier proof. Pin retail C1 Break Item as the positive prefix case and
  a SetVoice-to-rest effect as an immediate, no-reservation control.

- [ ] **Step 5: Prove profile identities without installing S1 yet.**

  Construct and validate `Sonic1YmServiceTimingProfile.PROFILE` directly while
  `Sonic1SmpsSequencerConfig.CONFIG` still returns `none()`. Assert S2 also
  remains `none()` and S3K remains the identical singleton/profile vectors.

- [ ] **Step 6: Run GREEN plus rewind guard and commit.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" \
    -Dtest=TestSonic1YmServiceTimingProfile,TestRewindArchitectureGuard test
  git add src/main/java/com/openggf/game/sonic1/audio/Sonic1YmServiceTimingProfile.java \
    src/main/java/com/openggf/audio/smps/SmpsSequencer.java \
    src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java \
    src/test/java/com/openggf/game/sonic1/audio/TestSonic1YmServiceTimingProfile.java
  git commit -m "feat(audio): classify source-authentic S1 FM5 attacks" \
    -m "Changelog: n/a: intermediate classifier/profile slice remains disabled until driver integration" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 4: Consume the Continuous Program in the Driver Transaction

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Test: `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`

**Interfaces:**
- Consumes: Task 2 resolver and Task 3 private shape state.
- Produces: transaction-local active program, exact incremental row
  consumption, stable committed timeline entries, and
  `supports(COMPLETION_RESTORE, variant)` cleanup gate.

- [ ] **Step 1: Write transaction RED tests.**

  Prove exact 26→optional-1→1→3 section order and due cycles; wrong section,
  missing/excess write, wrong register/value, unclassified flag, nested program,
  interrupted service, and observer exception leave no prefix or phantom
  callback and retry once with identical ordinals.

- [ ] **Step 2: Write completion-capability RED tests.**

  An S1 retiring FM5 SFX must execute current immediate key-off/restore without
  asking for `COMPLETION_RESTORE`. The same S3K fixture must retain its exact
  timed completion vector and due-cycle digest.

- [ ] **Step 3: Run and observe RED.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=com.openggf.audio.driver.TestSmpsDriverYmWriteTimeline test
  ```

- [ ] **Step 4: Implement transaction-local program state.**

  Start on the authenticated voice section, call `resolveNext` at each matching
  hardware write, carry `ProgramState` across helper scopes, and require terminal
  key-on before commit. Keep unpublished state out of public snapshots. Store
  only resolved self-contained entries in `YmWriteTimeline`.

- [ ] **Step 5: Implement exact profile ownership and sibling ordering.**

  Charge only the active/armed SFX FM5 lock owner. Identity-dedupe the same
  object in live/pending sets; reject a second distinct timed FM5 owner before
  mutation. If timed writes remain pending, route managed music writes through
  the existing ordered-sibling fence.

- [ ] **Step 6: Gate completion timing by capability.**

  In `releaseLocks`, open the timing scope only if the selected profile supports
  the exact completion key. Otherwise use the existing immediate ordered path.
  Do not modify owner selection or restoration bytes in this task.

- [ ] **Step 7: Install S1 profile only after driver integration is GREEN.**

  Set `.ymServiceTimingProfile(Sonic1YmServiceTimingProfile.PROFILE)` in
  `Sonic1SmpsSequencerConfig.CONFIG`, then run the real S1 ROM integration smoke
  test before committing.

- [ ] **Step 8: Run GREEN and commit.**

  ```bash
  S1="${S1_ROM_PATH:?point this at the authenticated S1 REV01 ROM}"
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" \
    -Dtest=TestSmpsDriverYmWriteTimeline,TestSonic1YmServiceTimingProfile,TestSonic1UnifiedAudioPresentationRomIntegration,TestSonic3kYmServiceTimingProfile test
  git add src/main/java/com/openggf/audio/driver/SmpsDriver.java \
    src/main/java/com/openggf/game/sonic1/audio/Sonic1SmpsSequencerConfig.java \
    src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java \
    src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java \
    src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java
  git commit -m "fix(audio): preserve S1 FM5 source timing across helpers" \
    -m "Changelog: n/a: intermediate runtime slice; the user-facing entry lands after native-oracle and full-suite verification" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 5: Prove Capacity, Rollback, Rewind, and Rendering Equivalence

**Files:**
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`

**Interfaces:**
- Verifies the Task 4 production path without adding new public runtime APIs.

- [ ] **Step 1: Add the 4,096/N-1 RED matrix.**

  Use a configurable test timeline: N=4,096 admits the exclusive profiled owner
  and its real 30/31-write journal; N-1=4,095 rejects the required whole-queue
  reservation before track cursor, lock, program state, service/write
  ordinal, PSG shadow, logical observer, chip observer, or YM timeline changes.
  Also test owner identity de-duplication and second-owner rejection.

- [ ] **Step 2: Add snapshot and rollback RED tests.**

  Capture/restore after commit before first drain, at each partial-drain prefix,
  after key-on, and after ordinary completion. Inject a failure after voice row
  25 and after optional pan; retries must match a clean dense run exactly.

- [ ] **Step 3: Add render-mode RED tests.**

  Sample-accurate and hybrid paths must produce identical PCM, full synth/driver
  snapshots, due cycles, callbacks, tempo, DAC, PSG, and panning bytes. A pending
  tail at cycle C must drain before row zero at C and row one after C.

- [ ] **Step 4: Implement the minimal scoped correction for every observed RED.**

  Fix production only within the green design's resolver, classifier, or driver
  transaction boundaries. Re-run each new test immediately to GREEN. If a RED
  requires a new source path, public API, capacity rule, or snapshot model,
  stop and amend/re-review both design and plan before editing production.

- [ ] **Step 5: Add architecture guards.**

  Reject source-program controls on `SmpsSequencer` or application-facing
  owners outside the existing injected `Synthesizer` transaction boundary,
  runtime reads of `docs/architecture/research/audio`, game/sound/zone switches
  in shared timing code, uncaptured final mutable fields, and extra transaction
  capture sites. The two `Synthesizer` methods are the deliberately narrow
  hardware-publication port used by the sequencer and implemented by the
  driver; they are not gameplay controls.

- [ ] **Step 6: Run, mutate one guard, restore, and rerun GREEN.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dtest=TestSmpsDriverYmWriteTimeline,TestRewindArchitectureGuard,TestAudioPresentationArchitectureGuard test
  ```

  Temporarily weaken exact section-close or capacity ownership validation and
  confirm the relevant test fails; restore production and rerun the same command.

- [ ] **Step 7: Commit.**

  ```bash
  git add src/main/java/com/openggf/audio/driver/SmpsDriver.java \
    src/main/java/com/openggf/audio/smps/SmpsSequencer.java \
    src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java \
    src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java \
    src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java \
    src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java
  git commit -m "test(audio): guard S1 YM timing transactions" \
    -m "Changelog: n/a: verification-only follow-up unless scoped production corrections are required; final entry lands in Task 7" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 6: Close Native-Oracle and ROM Playback Parity

**Files:**
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify if a RED exposes a scoped defect: `src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java`
- Modify: `src/test/java/com/openggf/audio/synth/TestS1S2YmWriteTimingAudit.java`
- Modify: `src/test/java/com/openggf/TestSonic1UnifiedAudioPresentationRomIntegration.java`
- Modify: `src/test/java/com/openggf/game/sonic1/audio/smps/TestSonic1BreakItemSfxOnset.java`

**Interfaces:**
- Consumes: production S1 profile and retained comparison-only exact YM contexts.
- Produces: automated evidence that due-cycle timing improves the native onset
  metric without changing ordered hardware bytes or unsupported paths.

- [ ] **Step 1: Add production-oracle RED tests.**

  Require exact source-derived 30/31 register order and the dynamic schedule for
  every service-cursor residue modulo 42. Replay the full **42 residues × every
  retained isolated/overlap Ring state** matrix. For each residue independently,
  sum L1 attenuation error over all retained states and require that sum to be no
  larger than the atomic sum over those same states; require at least one residue
  and the full matrix total to be strictly better. Report every residue aggregate
  plus every per-state result, so a consistently regressing runtime phase cannot
  be hidden by another phase. Record the observed 2,590-master-cycle refresh
  spread as bounded omitted 68K behavior. Trace bytes are test-only.

- [ ] **Step 2: Add ROM playback RED tests.**

  Run authenticated S1 Ring and every other eligible FM5 path through the real
  driver. Assert exact ordered YM bytes, delayed due cycles, no music voice
  programming between SFX upload/key-on, ordinary subsequent notes unchanged,
  rapid overlap deterministic, and unsupported shapes byte/timing-identical to
  the immediate control.

- [ ] **Step 3: Keep the pause/focus DAC regression in the same gate.**

  Verify pause/unpause and focus loss/refocus restore FM6 DAC panning for the
  currently playing song and do not alter timing state.

- [ ] **Step 4: Implement the minimal scoped correction for every observed RED.**

  Correct only source-program constants/logic, classifier replay, or driver
  scheduling already authorized by the green design. Re-run the failing method
  after each correction. Any need for a new native path or broader SFX shape
  triggers design+plan amendment and independent rereview.

- [ ] **Step 5: Run the three-ROM focused gate GREEN.**

  ```bash
  S1="${S1_ROM_PATH:?point this at the authenticated S1 REV01 ROM}"
  S2="${S2_ROM_PATH:?point this at the authenticated S2 REV01 ROM}"
  S3="${S3K_ROM_PATH:?point this at the authenticated locked-on S3K ROM}"
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" -Dsonic2.rom.path="$S2" \
    -Ds3k.rom.path="$S3" \
    -Dtest=TestS1S2YmWriteTimingAudit,TestSonic1UnifiedAudioPresentationRomIntegration,TestSonic1BreakItemSfxOnset,TestSonic3kYmServiceTimingProfile,TestSmpsDriverYmWriteTimeline test
  ```

- [ ] **Step 6: Commit the playback proof.**

  ```bash
  git add src/main/java/com/openggf/audio/driver/SmpsDriver.java \
    src/main/java/com/openggf/audio/smps/SmpsSequencer.java \
    src/main/java/com/openggf/audio/smps/YmSourceProgramTiming.java \
    src/test/java/com/openggf/audio/synth/TestS1S2YmWriteTimingAudit.java \
    src/test/java/com/openggf/TestSonic1UnifiedAudioPresentationRomIntegration.java \
    src/test/java/com/openggf/game/sonic1/audio/smps/TestSonic1BreakItemSfxOnset.java
  git commit -m "test(audio): verify S1 FM5 onset against native YM state" \
    -m "Changelog: n/a: verification-only follow-up unless scoped production corrections are required; final entry lands in Task 7" \
    -m "Guide: n/a" -m "Known-Discrepancies: n/a" \
    -m "S3K-Known-Discrepancies: n/a" -m "Agent-Docs: n/a" \
    -m "Configuration-Docs: n/a" -m "Skills: n/a"
  ```

### Task 7: Independent Review, Full Regression Comparison, and Listening Package

**Files:**
- Modify: `CHANGELOG.md`
- Create:
  `docs/architecture/validation/audio/2026-08-23-s1-fm5-ym-write-timing-validation.md`
- Modify: this plan and design only if implementation changes an assumption.

**Interfaces:**
- Produces: reviewed clean commit and exact local JAR for human listening; no
  merge/push.

- [ ] **Step 1: Request independent implementation review.**

  Give the reviewer the green design, this plan, base commit
  `b058bfb0cca511089661ac1846bc8ef60a7f90a5`, and exact implementation range.
  Fix every Critical/Important and repeat until green. If a blocker changes an
  architectural assumption, amend and re-review both design and plan before
  continuing.

- [ ] **Step 2: Record an isolated full-suite baseline at the feature base.**

  Use a detached cowtree/worktree without switching the main workspace. Run
  JDK21 Maven with the same three absolute ROM properties as the candidate.
  Save full logs and a sorted `class#method=status` red ledger under an
  agent-scratch allocation; record SHA-256s.

  ```bash
  BASELINE_SCRATCH="$(tools/agent-scratch new s1-ym-baseline)"
  S1="${S1_ROM_PATH:?point this at the authenticated S1 REV01 ROM}"
  S2="${S2_ROM_PATH:?point this at the authenticated S2 REV01 ROM}"
  S3="${S3K_ROM_PATH:?point this at the authenticated locked-on S3K ROM}"
  mvn -v
  # In the detached base worktree at b058bfb0, run the exact command from
  # Step 3 with output redirected to "$BASELINE_SCRATCH/full-suite.log".
  ```

- [ ] **Step 3: Run the exact candidate full suite and compare identities.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" -Dsonic2.rom.path="$S2" \
    -Ds3k.rom.path="$S3" test
  ```

  No baseline-passing test may newly fail/error, and no baseline red may change
  status attributable to this work. A red baseline is acceptable only with an
  exact identity/status comparison.

- [ ] **Step 4: Update tracked release/validation documentation and commit.**

  Record the relative-only scope, eligible shape census, native metric, S2
  deferral, S3K identity, pause-DAC preservation, test commands/counts, and any
  known unsupported S1 shape. Stage every generated architecture artifact.
  Commit with all required trailers; use `Changelog: updated` because
  `CHANGELOG.md` is staged, and pass the six unmapped `n/a` decisions
  explicitly. Never use `--no-verify`.

  ```bash
  git add CHANGELOG.md \
    docs/architecture/validation/audio/2026-08-23-s1-fm5-ym-write-timing-validation.md
  git commit -m "fix(audio): source-time S1 FM5 SFX onset" \
    -m "Changelog: updated" -m "Guide: n/a" \
    -m "Known-Discrepancies: n/a" -m "S3K-Known-Discrepancies: n/a" \
    -m "Agent-Docs: n/a" -m "Configuration-Docs: n/a" \
    -m "Skills: n/a"
  git log -1 --format=%B
  ```

- [ ] **Step 5: Verify the clean exact HEAD and package it.**

  ```bash
  mvn -v
  mvn -Dmse=off -Dsonic1.rom.path="$S1" -Dsonic2.rom.path="$S2" \
    -Ds3k.rom.path="$S3" -Dmaven.test.failure.ignore=true package
  unzip -t target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
  sha256sum target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
  git status --short
  ```

  Confirm the JAR manifest embeds the exact clean commit and `app.dirty=false`.

- [ ] **Step 6: Hand off the listening build without merging or pushing.**

  Report branch, commit, absolute worktree/JAR path, JAR SHA-256, exact run
  command, automated results, and these listening cases: reported S1 effect
  over active music; Ring from idle FM5; rapid repeats; replay after completion;
  replay after another FM5 effect; unsupported-shape control; pause/unpause;
  focus loss/refocus; S2 and S3K onset controls.

## Plan Self-Review

- Spec coverage: source authority, continuous busy state, row-zero boundary,
  shape eligibility, completion exclusion/capability, exclusive 4,096 capacity,
  snapshots/rollback/observers, cross-game controls, full comparison, and human
  gate each map to Tasks 1-7.
- Placeholder scan: no TBD/TODO or unspecified implementation decision remains.
- Type consistency: `FirstPathShape`, `SourceProgram`, `ProgramState`,
  `ProgramWrite`, `ResolvedWrite`, `supports(...)`, and
  `TimingOwnership.EXCLUSIVE_SFX_FM5` are introduced before consumption.
