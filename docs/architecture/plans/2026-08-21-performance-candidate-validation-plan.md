# Performance Candidate Validation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this validation plan task-by-task. Do not parallelise wall-clock, JFR, GC, or GPU measurements on the same host. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static performance survey's inferred rankings with reproducible evidence and produce separately approvable designs for only the candidates that clear their measurement and accuracy gates.

**Architecture:** Run three independent validation tracks: AIZ fire-curtain rendering, trace-run retained heap, and dynamic rewind identity lifetime. Each track first captures a pinned baseline, then either stops with a disproved/immaterial finding or produces a narrow follow-up design; this plan does not authorise runtime optimisation changes.

**Tech Stack:** Java 21, Maven Surefire, JUnit 5, JFR, G1 GC logs, LWJGL/OpenGL diagnostics, OpenGGF trace and screenshot tooling.

**Spec:** `docs/architecture/audits/performance/2026-08-21-performance-investigation-report-audit.md`

## Global Constraints

- Build and measure with JDK 21; verify with `mvn -v` before every measurement session.
- Stamp every result with the exact commit and dirty-tree state.
- Use `agent-scratch` storage, never `/tmp`, for durable profiles, captures, logs, and reports.
- Serialize timing-sensitive runs and keep A/B arms on the same pinned baseline, host, display, CPU set, ROM, and JVM configuration.
- Do not use `-Dsurefire.argLine` with `-Ptrace-replay`; that profile overrides it. Thread profiling JVM options through `-Dtest.cds.argLine` and verify the recording/log exists.
- Clear `target/surefire-reports` before each measured test run and prove the fresh report set is complete.
- No optimisation may change pixels, decoded bytes, audio samples, object execution/lifecycle order, physics values, or hardware-timing readiness semantics.
- Trace data remains comparison-only except for the two scheduling outcomes admitted by the hardware-timing contract.
- Any implementation work begins in an isolated worktree and requires separate user approval of that candidate's design.

---

### Task 1: Create the pinned evidence ledger

**Files:**

- Create during execution: `${TASK_SCRATCH}/measurement-context.md`
- Create during execution: `${TASK_SCRATCH}/commands.log`
- Create during execution: `${TASK_SCRATCH}/sha256.txt`
- No repository source changes

**Interfaces:**

- Consumes: the current integration baseline and ROMs discovered at the project root
- Produces: a task directory path and immutable measurement identity used by Tasks 2–4

- [x] **Step 1: Allocate managed scratch storage**

Run:

```bash
agent-scratch status
TASK_SCRATCH="$(agent-scratch new performance-candidate-validation)"
PROJECT_ROOT="$(pwd -P)"
S3K_ROM="${PROJECT_ROOT}/s3k.gen"
export TASK_SCRATCH PROJECT_ROOT S3K_ROM
```

Expected: `TASK_SCRATCH` names the new managed directory and `S3K_ROM` names
the verified locked-on image. Do not name either variable `HOME`, `home`, or
`CODEX_HOME`.

- [x] **Step 2: Record source and environment identity**

Run:

```bash
git status --short --branch
git rev-parse HEAD
mvn -v
nproc
uname -a
find . -maxdepth 1 -type f -name '*.gen' -printf '%f\n' | sort
```

Expected: Maven reports Java 21; the working-tree state and commit are copied
verbatim into `measurement-context.md`.

- [x] **Step 3: Verify ROM identities**

Run:

```bash
sha1sum "${PROJECT_ROOT}/s1.gen" "${PROJECT_ROOT}/s2.gen" "${S3K_ROM}"
```

Expected SHA-1 values:

```text
69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b  s1.gen
8bca5dcef1af3e00098666fd892dc1c2a76333f9  s2.gen
cfbf98c36c776677290a872547ac47c53d2761d6  s3k.gen
```

The validation tracks below pass `${S3K_ROM}` through `s3k.rom.path`; they do
not rename, copy, or symlink the ROM.

- [x] **Step 4: Define evidence completeness rules**

Write these fields into `measurement-context.md` before running a profile:

```text
commit=
dirty_tree=
jdk=
rom_sha1=
display=
cpu_affinity=
warmup_count=
sample_count=
fresh_surefire_report=
raw_artifacts=
```

Expected: no reported percentage or delta can exist without all applicable
fields populated.

---

### Task 2: Validate the AIZ fire-curtain rendering candidate

**Files:**

- Inspect: `src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java`
- Inspect: `src/main/java/com/openggf/game/sonic3k/features/AizTransitionRenderFeature.java`
- Inspect: `src/main/java/com/openggf/level/LevelRenderer.java`
- Test: `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java`
- Test: `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRendererRom.java`
- Test: `src/test/java/com/openggf/tests/TestS3kAiz1FireCurtainHeadless.java`
- Diagnostic: `src/test/java/com/openggf/tests/TestAizFireCurtainGpuDiag.java`
- Create during execution: `${TASK_SCRATCH}/fire-curtain/`

**Interfaces:**

- Consumes: pinned identity from Task 1 and an available GL display
- Produces: per-frame tile/direct-command/draw counts, CPU/GPU timing context, and baseline framebuffer hashes

- [x] **Step 1: Prove the focused correctness fixtures execute**

Run with full Maven logging:

```bash
mkdir -p "${TASK_SCRATCH}/fire-curtain/baseline"
mvn -Dmse=off \
  "-Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRenderer,com.openggf.game.sonic3k.features.TestAizFireCurtainRendererRom,com.openggf.tests.TestS3kAiz1FireCurtainHeadless" \
  "-Ds3k.rom.path=${S3K_ROM}" test
```

Expected: fresh Surefire XML exists for each selected class. Record failures
exactly; a pre-existing red does not invalidate measurement but must remain
identical in later A/B work.

- [x] **Step 2: Capture the baseline GPU diagnostic**

Run on the pinned display with no concurrent GL workload:

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.tests.TestAizFireCurtainGpuDiag" \
  "-Ds3k.rom.path=${S3K_ROM}" test
```

Expected: the test either produces fresh `target/fire-curtain-gpu/*.png` files
or reports an explicit assumption/initialisation failure. A skipped or stale
capture is not evidence. Copy fresh PNGs into
`${TASK_SCRATCH}/fire-curtain/baseline/` and record SHA-256 hashes.

- [x] **Step 3: Count the actual command path**

In a disposable measurement worktree, add counters at the command admission
and draw boundaries that distinguish:

```text
curtain TileDraws emitted
PatternRenderCommand instances admitted
instanced batches executed
GL draw calls executed
frame identifier and curtain stage
```

The counter must be diagnostic-only, default off, and observed firing during
the GPU diagnostic. Do not infer GL calls by multiplying source statements.
Keep the probe patch out of any candidate implementation commit unless it is
accepted as reusable profiling infrastructure.

- [x] **Step 4: Capture repeatable timing context**

Run two warmups followed by at least seven serialized samples of the same
curtain interval. Record frame-time distribution, command counts, and GPU
timer results if supported. If GPU timers are unavailable, report CPU-side GL
submission time explicitly rather than calling it GPU time.

- [x] **Step 5: Apply the promotion gate**

Promote the candidate to a separate rendering design only if:

```text
the direct-command path is observed on real curtain frames;
the measured cost is material to frame time or driver submission;
a proposed batch boundary can preserve execute-time shader/palette/priority state;
the existing framebuffer hashes form a usable before/after oracle.
```

Otherwise close it as “confirmed source shape, immaterial on measured host” and
retain the raw evidence.

---

### Task 3: Validate and bound trace-run retained heap

**Files:**

- Inspect: `src/main/java/com/openggf/trace/TraceData.java`
- Inspect: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Inspect: `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`
- Inspect: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Test: `src/test/java/com/openggf/tests/trace/runs/TestS3kKnucklesSuperEmeraldRunChain.java`
- Create during execution: `${TASK_SCRATCH}/trace-retention/`

**Interfaces:**

- Consumes: pinned identity from Task 1 and the committed 67-segment run
- Produces: retained-heap attribution by class/load site and a consumer inventory for a bounded-memory design

- [x] **Step 1: Record static fixture sizes without decompressing to disk**

Run `mkdir -p "${TASK_SCRATCH}/trace-retention"`, then walk the 67 segment
directories and sum uncompressed gzip sizes from their headers or a streaming
reader for `physics.csv(.gz)` and `aux_state.jsonl(.gz)`. Record segment count,
totals, and the largest five segments in
`${TASK_SCRATCH}/trace-retention/fixture-sizes.tsv`.

- [x] **Step 2: Capture a single-class baseline with effective JVM options**

Set `test.cds.argLine`, not `surefire.argLine`, so the trace profile expands
the diagnostics into its fork:

```bash
mvn -Dmse=off -Ptrace-replay-r7 \
  "-Dtest=com.openggf.tests.trace.runs.TestS3kKnucklesSuperEmeraldRunChain" \
  "-Ds3k.rom.path=${S3K_ROM}" \
  "-Dtest.cds.argLine=-Xshare:off -Xlog:gc*:file=${TASK_SCRATCH}/trace-retention/baseline-gc.log:time,uptime,level,tags -XX:StartFlightRecording=filename=${TASK_SCRATCH}/trace-retention/baseline.jfr,settings=profile,dumponexit=true" \
  test
```

Expected: the JFR and GC log are freshly created and the selected class has a
fresh XML report. The chain is deliberately red today; preserve its exact
failure message, segment/row frontier, and `framesCompared` rather than
requiring green.

- [x] **Step 3: Attribute retained memory**

Use `jfr view`, `jfr print`, and a post-plan class histogram or heap dump to
separate at least:

```text
TraceFrame and backing list storage
TraceEvent implementations
eventsByFrame maps and per-frame lists
HardwareTimingSchedule data
dynamic-art indexes/ledgers
decompression/parser temporary storage
engine state unrelated to trace planning
```

Expected: percentages are of retained bytes or allocation weight as labelled;
execution samples are never described as wall time.

- [x] **Step 4: Inventory random-access consumers**

For every `SegmentPlan.trace()` consumer, record whether it needs metadata,
sequential physics rows, random frame lookup, random auxiliary-event lookup,
cross-segment hardware timing, or a terminal/opening dynamic-art ledger.
Produce `${TASK_SCRATCH}/trace-retention/consumer-inventory.tsv` with source
location, lifetime, and required access shape.

- [x] **Step 5: Select the smallest valid ownership design**

Compare these designs against the inventory:

```text
A. Lazy-load one complete TraceData per segment and close it at boundary.
B. Retain physics rows but replace eager aux maps with a regenerable frame index and bounded event window.
C. Keep only metadata/timing plans globally and open a segment-local row/event cursor during drive.
```

Recommend the smallest design that lowers the measured peak while preserving
all required random access and cross-boundary state. Do not write code under
this task; save the approved choice as a separate architecture design.

- [x] **Step 6: Define the later implementation acceptance gate**

The eventual implementation must demonstrate on the same pinned workload:

```text
identical first failure/frontier and framesCompared;
no missing or starved trace class;
identical hardware-timing and dynamic-art comparison results;
lower peak live heap with the same or lower configured Xmx;
no file descriptor or gzip-stream leak at segment boundaries.
```

---

### Task 4: Prove the dynamic rewind identity lifetime

**Files:**

- Inspect: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectManagerRewindDynamicClassification.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectManagerDynamicChainRewindRestore.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS1RingFlashGraphRewind.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS2BadnikChildGraphRewind.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kAizIntroGraphRewind.java`
- Guard: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageGuard.java`
- Guard: `src/test/java/com/openggf/game/rewind/coverage/TestStaticStateRewindCoverageGuard.java`
- Create during execution: `${TASK_SCRATCH}/rewind-identity/`

**Interfaces:**

- Consumes: `ObjectManager` dynamic add/remove/capture/restore lifecycle
- Produces: proof whether removed instances are required after removal and an explicit ownership blocker if they are

- [x] **Step 1: Map every `rewindObjectIds` read and write**

Classify each access as add, active removal, dynamic removal, snapshot capture,
graph-reference encoding, restore, or reset. Record whether any read can occur
after an object has been removed from both live collections.

- [x] **Step 2: Write the failing lifetime test in an implementation worktree**

Add a package-private test seam that reports the live rewind-identity entry
count without exposing identities themselves. Extend
`TestObjectManagerRewindDynamicClassification` with this sequence:

```java
manager.addDynamicObject(projectile);
assertEquals(1, manager.rewindIdentityCountForTests());
manager.removeDynamicObject(projectile);
assertEquals(0, manager.rewindIdentityCountForTests());
```

Also capture before removal, remove, restore the captured snapshot, and assert
that the recreated object receives the captured `ObjectRefId` through snapshot
data rather than a stale live-map entry.

- [x] **Step 3: Run the test and prove it fails for the intended reason**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.level.objects.TestObjectManagerRewindDynamicClassification" test
```

Expected before a fix: the post-removal count assertion fails with one retained
entry. A compile error, skipped class, or unrelated singleton failure is not the
required red.

- [x] **Step 4: Review graph-reference lifetime before proposing removal**

Trace `collisionResponseList.captureRewindState(rewindObjectIds::get)` and all
other graph encoders. Prove they enumerate only live objects or carry removed
object IDs in snapshot-owned data. If any subsystem legitimately resolves a
removed object through the live map, stop and design explicit ownership rather
than pruning the entry.

- [x] **Step 5: Apply the bounded-fix promotion gate**

If Step 4 confirms no post-removal reader, propose pruning inside
`removeDynamicObjectInstance()` only after successful removal. Require focused
classification/chain tests, the three named cross-game graph-rewind tests, and
both rewind guards. The focused verification command is:

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.level.objects.TestObjectManagerRewindDynamicClassification,com.openggf.level.objects.TestObjectManagerDynamicChainRewindRestore,com.openggf.game.rewind.TestS1RingFlashGraphRewind,com.openggf.game.rewind.TestS2BadnikChildGraphRewind,com.openggf.game.rewind.TestS3kAizIntroGraphRewind,com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestStaticStateRewindCoverageGuard" \
  test
```

The eventual implementation also requires the full baseline/development/
merged-suite comparison from `AGENTS.md`. Runtime code still requires separate
approval.

Result: not promoted. Previous and partial-current collision-response lists
retain object references after dynamic membership can end, and
`cleanupDestroyedDynamicObjects()` is a separate retirement path. The follow-up
must design explicit identity ownership and test those seams before pruning.

---

### Task 5: Publish the evidence-backed shortlist

**Files:**

- Modify after measurements: `docs/architecture/audits/performance/2026-08-21-performance-investigation-report-audit.md`
- Create after each accepted implementation: a candidate-specific design under `docs/architecture/designs/`
- Create after implementation: validation evidence under `docs/architecture/validation/performance/`

**Interfaces:**

- Consumes: raw artifacts and decisions from Tasks 1–4
- Produces: an updated shortlist containing only measured candidates

- [x] **Step 1: Update the audit with measured results**

For every candidate, add commit, workload, host, raw artifact path, samples,
metric, median/range, semantic invariant, and confidence. Record negative
findings with equal prominence.

- [x] **Step 2: Separate independent implementation designs**

Do not combine rendering, trace ownership, and rewind lifetime into one runtime
implementation branch or design. This validation branch may publish their
independent evidence documents together; each future implementation must be
reviewed, rejected, tested, and delivered independently.

- [x] **Step 3: Stop before implementation**

Present the evidence and recommended candidate order to the user. Begin no
runtime implementation until the corresponding narrow design is explicitly
approved.
