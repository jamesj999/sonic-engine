# Sonic 1 Audio Driver Parity Harness Implementation Plan

> **For Codex:** Execute this plan with `superpowers:subagent-driven-development`
> and `superpowers:test-driven-development`. Stop and amend both this plan and
> the reviewed design if implementation evidence invalidates a design
> assumption.

**Goal:** Add a repeatable, read-only differential harness that records Sonic 1
REV01 GHZ music from the supplied BK2 in BizHawk, records the same driver epoch
from OpenGGF, and reports the first normalized state or ordered YM2612/PSG write
divergence.

**Architecture:** A Lua observer brackets ROM `UpdateMusic` invocations and
emits deterministic JSONL. OpenGGF exposes a disabled-by-default chip-core write
observer, then a Java tool drives the real S1 loader/sequencer for the reference
record count and emits the same schema. A Java comparator validates capture
integrity before reporting the first exact mismatch. A shell entry point runs
both deterministic captures and the comparison beneath `target/audio-parity/`.

**Tech stack:** Java 21, JUnit Jupiter, Jackson, Lua 5.4/BizHawk Lua, Bash,
Maven, BizHawk 2.11 Genesis Plus GX.

**Reviewed design:**
`docs/architecture/designs/2026-08-09-s1-audio-driver-parity.md`

## Delivery constraints

- Work only in the isolated `feature/ai-s1-audio-parity` worktree.
- Treat the user-provided BK2 as immutable input and copy it byte-for-byte.
- Do not commit detailed capture JSONL, register streams, PCM, WAV, or VGM.
- Do not alter production chip-write order while adding observation.
- A red end-to-end parity result is an acceptable harness result; it is not
  authority to change the audio driver in this feature.
- Use JDK 21 and `mvn clean test -Pci` for full verification. The one-fork CI
  profile avoids the known LWJGL native extraction race.

## Task 1: Pin the controller-only BK2 contract

**Files:**

- Add: `src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityFixtureContract.java`

**Step 1: Write the failing fixture contract test**

The test must:

- require the tracked BK2;
- assert SHA-256
  `622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c`;
- open it as ZIP and require exactly the controller/movie metadata entries
  expected by the supplied file;
- reject any savestate, ROM payload, or unexpected large entry;
- count exactly 989 controller rows and 992 total `Input Log.txt` lines
  including `[Input]`, `LogKey:`, and `[/Input]`; and
- assert the movie names BizHawk 2.11, GPGX, and S1 World REV01 while treating
  its 32-character `SHA1` header as opaque metadata.

Run:

```bash
mvn -Pci -Dtest=com.openggf.tools.audio.parity.TestS1AudioParityFixtureContract test
```

Expected: FAIL because the tracked fixture is absent.

**Step 2: Copy the BK2 byte-for-byte**

Copy from the ignored BizHawk installation in the main workspace (set
`OPENGGF_MAIN_WORKSPACE` to that workspace root):

```text
${OPENGGF_MAIN_WORKSPACE}/docs/BizHawk-2.11-linux-x64/Movies/s1-soundtest-ghz.bk2
```

Do not rename or alter the source. Recalculate both hashes and use `cmp` to
prove identity.

**Step 3: Run the focused test**

Expected: PASS, 1 test and no errors.

**Step 4: Commit**

```bash
git add src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2 \
  src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityFixtureContract.java
git commit -m "test: pin the S1 GHZ sound-test movie"
```

Fill every required policy trailer; use `n/a` where no mapped documentation
changed.

## Task 2: Extend the declarative probe lifecycle safely

**Files:**

- Modify: `tools/bizhawk/probes/probe_runtime.lua`
- Modify: `src/test/resources/bizhawk/probe_runtime_contract_test.lua`
- Modify: `src/test/java/com/openggf/tests/TestBizhawkProbeContractGuard.java`

**Step 1: Add failing lifecycle tests**

Extend the pure-Lua fake runtime and Java lexical guard to require:

- hook callbacks receive all BizHawk callback arguments after `context`;
- `continueAfterMovie = true` suppresses the default FINISHED exit;
- default probes still exit at FINISHED;
- optional `onFrame(context)` runs once before each `frameadvance` and can call
  `context.finish()`;
- `context.movieFinished()` reports movie completion without exposing mutation;
- cleanup still unregisters hooks, flushes/closes output, and preserves the
  original error.

Run:

```bash
mvn -Pci -Dtest=com.openggf.tests.TestBizhawkProbeContractGuard test
```

Expected: FAIL on the new contract.

**Step 2: Implement the smallest runtime extension**

Keep all existing defaults. Validate optional field types. Change only the
shared callback wrapper and main loop; do not move lifecycle calls into probes.

**Step 3: Run the focused test**

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/bizhawk/probes/probe_runtime.lua \
  src/test/resources/bizhawk/probe_runtime_contract_test.lua \
  src/test/java/com/openggf/tests/TestBizhawkProbeContractGuard.java
git commit -m "feat(tools): let read-only probes outlive movies"
```

## Task 3: Build and test the deterministic Lua audio contract

**Files:**

- Add: `tools/bizhawk/audio/s1_audio_parity_contract.lua`
- Add: `src/test/resources/bizhawk/s1_audio_parity_contract_test.lua`
- Add: `src/test/resources/audio/parity/s1/normalization-contract-v1.json`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityLuaContract.java`

**Step 1: Write failing pure-Lua contract vectors**

Vectors must cover:

- canonical JSON object key order and escaping;
- unsigned byte/word and signed byte/word normalization;
- YM address/data pairing for both ports;
- orphan address/data rejection;
- deterministic state/event hashing;
- recurrence candidate rejection followed by full period proof;
- active loop-counter filtering and live return-stack normalization; and
- the 36,000-invocation limit.

Add one compact, synthetic, non-ROM golden vector containing raw ROM-shaped
global/track values, matching OpenGGF-shaped values, and the exact expected
canonical JSON. It must exercise signed fields, inactive roles, live loop and
stack entries, YM/PSG event encoding, and deterministic key order. The Lua test
must consume this file and reproduce its expected bytes; Task 6/7 Java tests
must consume the same file and reproduce the same bytes. This is the shared
cross-language conformance gate, not two independently authored expectations.

The Java test invokes `/usr/bin/lua` when available and otherwise fails only the
behavioral assumption, matching the existing probe-runtime test convention.

**Step 2: Implement the pure module**

The module must not use BizHawk globals. Export narrow functions used by the
observer and tests. Never emit ROM asset bytes.

**Step 3: Run focused tests**

```bash
mvn -Pci -Dtest=com.openggf.tools.audio.parity.TestS1AudioParityLuaContract test
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/bizhawk/audio/s1_audio_parity_contract.lua \
  src/test/resources/bizhawk/s1_audio_parity_contract_test.lua \
  src/test/resources/audio/parity/s1/normalization-contract-v1.json \
  src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityLuaContract.java
git commit -m "feat(tools): define the S1 audio capture contract"
```

## Task 4: Prove BizHawk write capture, then implement the ROM observer

**Files:**

- Add: `tools/bizhawk/probes/s1_audio_driver_parity_probe.lua`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityProbeContract.java`
- Modify: `tools/bizhawk/probes/README.md`

**Step 1: Write failing source-contract tests**

Require the probe to:

- use `ProbeRuntime.run({...})` and the pure audio module;
- contain no mutation APIs;
- register execute hooks at `$71B4C`, `$71C4C`, `$71FD0`, and `$71FD2`;
- define and verify the full FM/PSG fallback manifest from the design;
- read sound RAM through `mainmemory` offsets rooted at `$F000`;
- arm only for `D7 & $FF == $81`;
- enforce the active-invocation retry guard;
- verify neutral post-movie input; and
- emit only to the runtime-owned `OGGF_OUT` stream.

Expected: FAIL because the probe does not exist.

**Step 2: Implement a short callback-validation phase**

Register write callbacks for `$A04000..$A04003` and `$C00011` plus the FM data
PCs `$72752/$72788`. Preserve callback arguments verbatim in a short local
validation window and compare them with `D0`/`D1` at the PC hooks. Do not assume
the callback argument order; recognize it only after controlled address/value
checks establish the mapping.

**Step 3: Run the real validation capture before completing the observer**

Discover the root-level `.gen`, independently verify S1 REV01 SHA-1/CRC32, and
run:

```bash
mkdir -p "$PWD/target/audio-parity"
OGGF_OUT="$PWD/target/audio-parity/callback-validation.jsonl" \
BIZHAWK_HOME="${OPENGGF_MAIN_WORKSPACE}/docs/BizHawk-2.11-linux-x64" \
tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/probes/s1_audio_driver_parity_probe.lua \
  src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2 \
  "$S1_ROM_PATH"
```

If callback values validate, select `memory_callback`. If they do not, finish
the reviewed PC-manifest fallback. If neither path can prove complete ordered
values, stop and amend/re-review the design and plan.

**Step 4: Complete the observer**

Implement:

- independent ROM identity and BK2 identity checks;
- exact opcode-byte verification for all fallback sites;
- dormant launch through title/Level Select;
- tick zero from `$71FD2` through `$71C4C`;
- one close per external `UpdateMusic`, identifying DAC-busy retries by the
  unchanged 68K stack pointer even when the invocation crosses emulator frames;
- pre-epoch `$71FD0` `PlaySegaSound` abnormal-exit reset without a record, and
  post-epoch rejection of that abnormal exit;
- fixed-slot role normalization that treats active PSG3 `VoiceControl` C0/E0
  as the tone/noise alias while retaining the raw byte diagnostically;
- global/track normalization with diagnostic-vs-gating fields explicit;
- contamination checks;
- reference cycle proof and metadata (`cycle_start`, `period`,
  `terminal_record_count`); and
- deterministic metadata/record JSONL with no paths or timestamps in the
  normalized identity.

The first line is capture metadata; following lines are tick records. Raw bus
events may be present in local output but are never copied to test resources.

Lifecycle coverage must include a pre-epoch `$71FD0` abnormal exit followed by
a later same-stack external call, an armed same-stack retry across emulator
frames, armed different-stack nested-entry rejection, and both PSG3 C0/E0
aliases producing fixed role PSG3.

**Step 5: Run source tests and two real captures**

Run the focused Maven test, then capture twice to distinct target files and
require byte identity with `cmp` and SHA-256 equality.

**Step 6: Commit source only**

Never stage target output.

```bash
git add tools/bizhawk/probes/s1_audio_driver_parity_probe.lua \
  tools/bizhawk/probes/README.md \
  src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityProbeContract.java
git commit -m "feat(tools): capture S1 music-driver reference ticks"
```

## Task 5: Add a non-interfering chip-core write observer

**Files:**

- Add: `src/main/java/com/openggf/audio/synth/ChipWriteObserver.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/synth/PsgChip.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Add: `src/test/java/com/openggf/audio/synth/TestChipWriteObserver.java`

**Step 1: Write failing direct and expansion tests**

Assert a disabled observer emits nothing and an enabled observer records:

- direct YM writes with resolved port/register/value bytes;
- direct PSG bytes;
- the exact existing `setInstrument(...)` key-off, B0, and operator-register
  expansion order; and
- all `silenceAll()` YM and PSG writes.

Capture synth snapshots with observation off/on for identical calls and assert
they are equal. The test's expected order must come from the existing
production algorithm, not a reordered convenience list.

Expected: FAIL because the seam is absent.

**Step 2: Implement the observer at the chip-core write methods**

Use a no-op default. `VirtualSynthesizer` installs/removes the same observer on
both chips. Notify synchronously exactly once for every resolved
`Ym2612Chip.write(...)` and `PsgChip.write(...)`; do not change method call
order, chip state, locking, or `SmpsDriver` arbitration.

**Step 3: Run focused audio tests**

```bash
mvn -Pci -Dtest=com.openggf.audio.synth.TestChipWriteObserver,com.openggf.audio.synth.TestVirtualSynthesizerSnapshot,com.openggf.audio.driver.TestSmpsDriverSnapshot test
```

Expected: PASS.

**Step 4: Update `CHANGELOG.md` and commit**

Describe the diagnostic-only disabled seam. Use `Changelog: updated`; all other
trailers reflect staged files.

## Task 6: Implement the shared Java schema and strict JSONL reader/writer

**Files:**

- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParitySchema.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityMetadata.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityTick.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityTrackState.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityChipWrite.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityJsonl.java`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestAudioParityJsonl.java`

**Step 1: Write failing schema tests**

Cover valid Lua-shaped vectors, unknown schema, duplicate/missing ordinals,
malformed YM/PSG values, absent fixed roles, diagnostic fields excluded from
equality, deterministic output order, and rejection of absolute paths or
timestamps in normalized metadata.

Consume
`src/test/resources/audio/parity/s1/normalization-contract-v1.json` and require
byte-identical canonical JSON to its expected output. Do not duplicate the
golden expectation in Java source.

**Step 2: Implement immutable records and streaming JSONL**

Use Jackson without loading full raw bus streams into unrelated runtime code.
Validate every byte range and role at the boundary. Keep this package under
`com.openggf.tools`; production gameplay must not import it.

**Step 3: Run focused test and architecture guards**

```bash
mvn -Pci -Dtest=com.openggf.tools.audio.parity.TestAudioParityJsonl,com.openggf.audio.TestAudioPresentationArchitectureGuard test
```

Expected: PASS.

**Step 4: Commit**

Commit with a tool-focused message and required trailers.

## Task 7: Normalize OpenGGF S1 state with executable field gates

**Files:**

- Add: `src/main/java/com/openggf/tools/audio/parity/S1AudioStateNormalizer.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/S1AudioFieldRegistry.java`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1AudioStateNormalizer.java`

**Step 1: Write failing normalization tests**

Use synthetic `SmpsSequencerSnapshot`/`SmpsTrackSnapshot` instances to cover:

- ten fixed roles and channel decoding;
- inactive roles ignoring stale fields;
- signed transpose/volume/detune;
- GHZ-relative sequence positions;
- packed pan/AMS/FMS;
- FM `voiceId` versus PSG `instrumentId`;
- frequency packing;
- only parsed `$F7` loop-counter indices;
- live return-stack entries only; and
- every proposed diagnostic-to-gating promotion (rest, tempo, note fill, and
  modulation transition derivations).

Also consume the shared normalization golden vector and prove the OpenGGF-shaped
input produces the same expected canonical bytes already required of Lua.

Expected: FAIL.

**Step 2: Implement the registry and normalizer**

Each field declares source, signedness, applicability, and gate/diagnostic
status. Do not compare snapshot containers wholesale. Do not read disassembly
assets at runtime.

**Step 3: Run the focused test**

Expected: PASS.

**Step 4: Commit**

Commit schema normalization separately from capture orchestration.

## Task 8: Capture the OpenGGF GHZ epoch for the reference interval

**Files:**

- Add: `src/main/java/com/openggf/tools/audio/parity/S1OpenGgfAudioCapture.java`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1OpenGgfAudioCapture.java`

**Step 1: Write failing ROM-backed and non-ROM unit tests**

Unit tests must prove:

- observer is armed only after `SmpsDriver` power-on silence;
- tick zero contains sequencer construction plus exactly one S1 priming tempo
  service;
- later ticks call `SmpsSequencer.advanceBatch(735)` exactly once, yielding one
  `44100 / 60` NTSC service boundary (do not pass 735 interleaved shorts to
  `SmpsDriver.read`, which would advance only 367 stereo frames);
- the sequencer sample counter returns to the expected zero phase after every
  captured record;
- state is snapped after writes for that tick;
- reference `terminal_record_count` controls run length even on divergence;
- a missing/wrong ROM fails before output publication; and
- capture output is atomically published and deterministic.

The ROM-backed test uses `-Dsonic1.rom.path` and the discovered root ROM; it
must not copy or rename the ROM.

**Step 2: Implement the test host**

Open the real `Rom`, independently verify REV01 SHA-1/CRC32, load GHZ `$81`
through `Sonic1SmpsLoader`, load its real `DacData`, create a 44.1 kHz
`SmpsDriver`, arm `ChipWriteObserver`, construct/add `SmpsSequencer` with
`Sonic1SmpsSequencerConfig.CONFIG`, prime tick zero, and capture the exact
reference count. Prime with `sequencer.read(new short[0], 0)`, then use
`sequencer.advanceBatch(735)` for every later ordinal.

Use a minimal no-op `MusicRestoreSink`; do not initialize `AudioManager`, GLFW,
or a gameplay session.

**Step 3: Run focused tests twice**

```bash
mvn -Pci -Dsonic1.rom.path="$S1_ROM_PATH" \
  -Dtest=com.openggf.tools.audio.parity.TestS1OpenGgfAudioCapture test
```

Expected: PASS twice with byte-identical local output.

**Step 4: Commit**

Commit the OpenGGF capture tool with required trailers.

## Task 9: Implement first-divergence comparison

**Files:**

- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityComparator.java`
- Add: `src/main/java/com/openggf/tools/audio/parity/AudioParityReport.java`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestAudioParityComparator.java`

**Step 1: Write failing mutation tests**

Start from a valid in-memory stream and mutate one property at a time:

- metadata/schema/identity;
- tick count and ordinal;
- one global state field;
- one fixed-role field;
- one YM value;
- swapped adjacent writes;
- missing write; and
- extra write.

Require classification, first mismatch only, and eight events of bounded
before/after context. Raw bus detail is optional attachment and cannot alter
the comparison result.

**Step 2: Implement validation-first comparison**

Never attempt realignment. Compare state before writes at the same ordinal.
Emit deterministic human text and compact JSON summary.

**Step 3: Run focused tests**

Expected: PASS.

**Step 4: Commit**

Commit comparator independently.

## Task 10: Add the local end-to-end command

**Files:**

- Add: `src/main/java/com/openggf/tools/audio/parity/S1AudioParityTool.java`
- Add: `tools/audio/run_s1_audio_parity.sh`
- Add: `src/test/java/com/openggf/tools/audio/parity/TestS1AudioParityCli.java`
- Modify: `tools/bizhawk/README.md`
- Modify: `CONFIGURATION.md` only if a persistent configuration key is added
  (prefer CLI arguments, so no change should be needed).

**Step 1: Write failing CLI tests**

Test argument parsing, safe target-root resolution, refusal to accept output
under `src/test/resources`, missing BizHawk/ROM/movie diagnostics, and exit
codes that distinguish capture failure from parity mismatch.

**Step 2: Implement orchestration**

The shell command:

1. discovers or accepts the S1 ROM without renaming it;
2. validates ROM/BK2/BizHawk 2.11 identities;
3. creates a fresh `target/audio-parity/s1-ghz/` run directory;
4. records two ROM captures and compares normalized bytes;
5. runs two OpenGGF captures for the reference count and compares bytes;
6. invokes the comparator; and
7. prints the report path and returns 0 for parity, a dedicated nonzero code
   for a valid mismatch, and a different nonzero code for capture/tool failure.

It must not delete any path outside its exact target run root and must preserve
both captures for diagnosis.

**Step 3: Run CLI unit tests**

Expected: PASS.

**Step 4: Commit**

Update `CHANGELOG.md` for the user-facing tool and fill guide/config trailers
according to the staged files.

## Task 11: Run the real two-sided capture and document the result

**Files:**

- Add: `docs/architecture/research/audio/2026-08-09-s1-ghz-driver-parity-result.md`
- Modify: `docs/architecture/designs/2026-08-09-s1-audio-driver-parity.md` only
  if verified implementation details refine the design without changing it
- Modify: `docs/architecture/plans/2026-08-09-s1-audio-driver-parity.md` only if
  implementation amendments were required

**Step 1: Execute the real harness**

```bash
tools/audio/run_s1_audio_parity.sh --rom "$S1_ROM_PATH"
```

Expected tool outcome: both sides deterministic, valid two-cycle reference,
and either exact parity or a precisely classified first divergence. A mismatch
exit code is evidence, not a task failure.

**Step 2: Inspect integrity and copyright boundaries**

Verify:

- capture mode selected and fallback reason, if any;
- cycle start/period/record count;
- no contamination;
- both repeat hashes;
- first mismatch classification and bounded context;
- no detailed output is tracked; and
- the research report contains only compact, non-reconstructive facts.

**Step 3: Write the research result**

Record exact commands, ROM/BK2 hashes, capture mode, deterministic hashes,
cycle proof, parity status, and first mismatch summary. Do not paste register
streams or ROM data.

**Step 4: Commit the report**

Stage every task artifact and use accurate documentation trailers.

## Task 12: Verification, independent review, and integration

**Step 1: Run focused verification**

```bash
mvn -Pci -Dsonic1.rom.path="$S1_ROM_PATH" \
  -Dtest='com.openggf.tools.audio.parity.*,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.tests.TestBizhawkProbeContractGuard' test
tools/bizhawk-headless/test.sh --filter S1
```

Record exact counts and failures. If the native harness filter syntax differs,
use its documented equivalent; do not silently omit the harness regression
suite.

**Step 2: Run the full worktree suite cleanly**

```bash
mvn clean test -Pci \
  -Dsonic1.rom.path="$S1_ROM_PATH" \
  -Dsonic2.rom.path="$S2_ROM_PATH" \
  -Ds3k.rom.path="$S3K_ROM_PATH"
```

Classify generated reports before restoring them. Never discard unknown edits.

**Step 3: Request independent code review**

Review design compliance, read-only Lua behavior, observer non-interference,
ordering tests, schema authority isolation, copyright boundaries, and actual
test evidence. Fix all valid findings and repeat review until no blocking or
significant issue remains.

**Step 4: Follow the repository integration workflow**

1. In the main workspace, fetch and fast-forward the checked-out `develop`
   branch without overwriting its existing user changes.
2. Record the updated integration baseline with `mvn clean test -Pci` and all
   discovered ROM properties.
3. Re-run the same full suite and focused tests in this worktree.
4. Update the `README.md` release/change-log section as required for a merge
   into `develop`.
5. Merge `feature/ai-s1-audio-parity` into main-workspace `develop` without
   switching the main workspace branch.
6. Run the clean post-merge full suite and compare it with the baseline.
7. Push only `develop`.
8. Verify no uncommitted/unmerged work remains here, remove the worktree,
   delete the fully merged local feature branch, and prune worktree metadata.

Do not claim completion if fetch/pull, baseline comparison, merge, post-merge
verification, push, or required cleanup fails. Report exact pre-existing and
new failure sets separately.
