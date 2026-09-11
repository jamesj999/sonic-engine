# S1 Reserved Audio Service Consume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retain every row-8775 M68K bus-wait retry as raw proof, then begin exactly one kind-4 `UpdateMusic` child when the accepted path enters `$71B82`, preserving truthful single-stack ownership through the kind-6 wait service.

**Architecture:** ABI v3 keeps one bounded action-11 reservation at `$71B4C` and adds one exact consume action at `$71B82`. Consume pushes an ordinary kind-4 child beneath the still-open kind-6 root without granting kind 6 generic child permission; existing LIFO then owns M68K work through `$71C4C` and restores kind 6 before its `$0077` tail. Managed and Java layers retain the raw reservation/consume proof while canonical comparison uses the ordinary nested service hierarchy.

**Tech Stack:** C99 GPGX patch/selftest harnesses, C# BizHawk headless observer/capture tests, Java 21 complete-run schema/store/comparator tests, Bash deterministic build/install tooling.

## Global Constraints

- Use Sonic 1 World REV01 SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` and the pinned all-emeralds BK2 without copying, renaming, or symlinking either fixture.
- Keep ABI version 3 and all native config/event/range/kind/hook struct sizes unchanged.
- Capacity is one pending reservation; observation count is not fitted to the recorded value three.
- Keep kind 6 without `ALLOW_CHILDREN`; only the unique `$71B4C` reserve / `$71B82` consume pair may create the child.
- Do not infer recently closed ancestry, add overlapping roots, route ownership by game name, or publish a partial capture.
- Retract the stale Task 4 12/13/20/21 and terminal-green claims; the clean Task 5 `$71BB2` failure is authoritative.
- Native/core/schema changes are conductor-owned; S2/S3 game semantics remain unchanged.
- Build and test Java on JDK 21.

---

### Task 1: Replace release-at-END with exact native reserve/consume

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/matrix_harness.c`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/cpu_boundary_harness.c`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`

**Interfaces:**
- Consumes: action-11 marker value 4, existing service stack/push/pop, hook proof, one native reservation.
- Produces: an exact consume action paired with reserve action 11; ordinary nested SERVICE_BEGIN at `$71B82` with parent=kind-6 token and depth=1.

- [ ] Write a native matrix RED whose physical order is kind-6 root BEGIN, one/four `$71B4C` reserve markers, `$71B82` consume, kind-4 child BEGIN, M68K observation/chip ownership, `$71C4C` child END, then `$0077` kind-6 tail; verify current code instead releases at parent END or rejects `$71BB2`.
- [ ] Add REDs for mismatched reserve/consume CPU, PC, opcode, expected/target kinds, duplicate pair, consume without reservation, wrong blocker token, second consume, capacity-short BEGIN, reset while pending, cutoff carry, interposed pre-consume M68K hook/write, and generic kind-6 child PUSH.
- [ ] Run `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/run.sh` and record the exact first expected RED.
- [ ] Replace release-on-blocker-END with a configure-validated consume action at M68K `$71B82`, opcode `4D F9 00 FF F0 00`; reserve and consume must be the sole exact pair and name blocker kind 6/target kind 4.
- [ ] At consume, preflight capacity and exact reservation/blocker identity, emit one ordinary child BEGIN with fresh token, clear/mark the reservation only after publication, and leave generic `ALLOW_CHILDREN` validation unchanged for every other PUSH.
- [ ] Preserve pending reservation across frames/cutoff, reject reset and non-reserve M68K activity before consume, and use ordinary LIFO/reset/continuation behavior after consume.
- [ ] Add a timing-boundary matrix for QueueSound/fresh `$71B4C` after `$71C4C` but before `$0077`; require exact fail-closed coverage rather than assuming this interval is empty.
- [ ] Rerun all six native harnesses from pristine patch-derived source and require prior ABI1/2/3/action8/9/10 behavior unchanged.
- [ ] Commit the native RED/GREEN checkpoint with policy trailers.

### Task 2: Correlate the accepted `$71B82` child begin transactionally

**Files:**
- Modify: `tools/bizhawk-headless/src/Audio/CompleteRunAudioObserver.cs`
- Modify: `tools/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs`
- Modify: `tools/bizhawk-headless/fixtures/s1-audio-service-manifest-v1.json`
- Modify: `tools/bizhawk-headless/tests/CompleteRunAudioObserverTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs`

**Interfaces:**
- Consumes: marker value 4 reservation and paired consume proof followed by an ordinary nested SERVICE_BEGIN.
- Produces: transactional reservation evidence keyed by blocker token and the same A7/return identity, consumed at `$71B82`.

- [ ] Write focused REDs for one/four same-identity `$71B4C` markers followed by `$71B82` consume and a kind-4 child BEGIN under kind 6; require `$71BB2` and subsequent M68K chip/observation events to be owned by the child.
- [ ] Add REDs for changed A7/return identity, wrong blocker token/kind/ancestry, wrong consume hook/opcode, missing/duplicate child BEGIN, root instead of child ancestry, interposed pre-consume M68K hook/write, reset, consumer rejection, and later-frame rollback.
- [ ] Update the S1 manifest with the exact reserve and consume hooks while keeping `begin_expected [0,2,3]` and kind-6 flags unchanged.
- [ ] Change managed reservation consumption from blocker END to `$71B82`; bind the consume callback to the stored A7/return identity and require the immediately associated child BEGIN to have parent=blocker token/depth=1.
- [ ] Remove the source-false synthetic END→root-BEGIN path while retaining cross-frame marker publication ordering and transactional rollback.
- [ ] Run the observer and S1 synthetic filters; require all focused cases green with the real test still opt-in.
- [ ] Commit the managed/manifest checkpoint with policy trailers.

### Task 3: Bind raw diagnostics to consume-at-entry

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCutoffFrontier.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`

**Interfaces:**
- Consumes: pending/consumed `NativeDeferredServiceBegin`, marker correlations, consume proof, and ordinary child `DriverService` begin/end.
- Produces: lossless raw/cutoff evidence while semantic state uses the normal parent/depth hierarchy.

- [ ] Write REDs that reject the old blocker-END→root-BEGIN transaction and accept only exact marker→consume→nested-BEGIN order.
- [ ] Change the diagnostic’s consumed coordinate/token meaning from blocker release to `$71B82` consume and nested child token; preserve raw/storage sensitivity and semantic exclusion.
- [ ] Merge consume proof and child BEGIN into the unified native raw-order inventory; reject coordinate/ordinal collisions, regression, missing proof, wrong parent/depth, duplicate consume, reset pending, and dangling terminal evidence.
- [ ] Add pending-cutoff and consumed-active-cutoff round trips; prove caller-list/JSON/digest immutability and OpenGGF producer exclusion.
- [ ] Run Trace, CutoffFrontier, CaptureStore, Comparator, Replay, Authority, and CLI classes on JDK 21 with zero failures/errors.
- [ ] Commit the raw-schema/validator checkpoint with policy trailers.

### Task 3A: Make managed lifecycle identity-only

**Files:**
- Modify: `tools/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs`
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs`

**Interfaces:**
- Consumes: native-validated service tokens, current ownership, action-8/event-11 promotion, reset lifecycle, and cutoff active services.
- Produces: one managed token+A7 identity tracker shared by pre-publication and published correlation, with no cached ancestry.

- [ ] Write a synthetic RED for the exact row-523 order: kind-2 root, kind-4 child begin, action-8 parent END plus event-11 promotion, `$71B82` action-7 with kind-4 token as root, `$71C4C` close; verify current tracker rejects stale depth.
- [ ] Add REDs for kind-4 observations with wrong token/A7; kind-2/3 observations with wrong parent token/A7; duplicate kind-4 token; close/retry with wrong token; cutoff missing/extra/duplicate tokens; reset/power cancellation; malformed reset rollback; and identical pre-publication/published behavior.
- [ ] Remove `ParentToken` and `Depth` from `ManagedServiceTracker.Entry`. Match kind-4 observations by `ServiceToken+A7` and kind-2/3 observations by `ParentToken+A7`; leave topology fields to `CompleteRunAudioObserver` validation.
- [ ] Change cutoff handoff to exact set equality between managed open tokens and active native kind-4 tokens, including duplicate/cardinality rejection, without comparing ancestry.
- [ ] Preserve A7 capture, exact callback/native order and PC, reservation A7/return binding, transaction rollback, reset cancellation, and zero pre-publication output.
- [ ] Run S1 capture and CompleteRunAudioObserver suites, then run the exact real row-523 prefix against the disposable revised core before claiming GREEN.
- [ ] Request independent review of the authority split and adversarial coverage; commit with policy trailers and no push.

### Task 4: Reproduce the clean S1 transaction and terminal frontier

**Files:**
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs`
- Modify: `docs/architecture/research/audio/2026-08-11-complete-run-audio-frontier-checkpoint.md`
- Modify: `docs/status/trace-frontier-log.md`
- Modify: `.superpowers/sdd/2026-08-12-s1-deferred-audio-service-begin-plan/task-4-report.md` only if that ignored workspace still exists.

**Interfaces:**
- Consumes: diagnostic reserve/consume core/install and exact S1 ROM/BK2.
- Produces: complete row-8775 physical proof and the next exact frontier.

- [ ] Preserve a durable clean RED log proving `$71BB2` hook failure on the old release-at-END design and explicitly retract the stale 12/13/20/21 and terminal-green evidence.
- [ ] Build/install a diagnostic revised core without changing committed identity literals.
- [ ] Run the real opt-in gate and assert: kind-6 root BEGIN `$003A`; distinct ordered marker-4 retries at `$71B4C`; exact `$71B82` consume and fresh kind-4 child BEGIN; `$71BB2` owned by the child; child END `$71C4C`; then kind-6 END/tail `$0077` and DPCM child BEGIN.
- [ ] Record every manifest M68K hook between `$71C4C` and `$0077`; cover QueueSound/fresh `$71B4C` if present.
- [ ] Run `OPENGGF_S1_AUDIO_TERMINAL_PROBE=1` through `Complete(225101)`; on failure record the exact next frontier and stop semantic broadening for a new reviewed round.
- [ ] Update research/frontier documents with exact commands, hashes, durable logs, result, and no-publication statement.
- [ ] Rerun S1 synthetic and real focused gates and request independent evidence review.
- [ ] Commit the corrected real-frontier evidence with policy trailers.

### Task 5: Regenerate deterministic artifacts and run compatibility gates

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/artifact-lock.json`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/task7-build-recipe.json`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/build-core.sh`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/README.md`
- Modify: `tools/bizhawk-headless/fixtures/gpgx-audio-capability-v1.json`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: identity assertions under `src/test/` and `tools/bizhawk-headless/tests/`
- Modify: `docs/architecture/research/audio/2026-08-11-complete-run-audio-frontier-checkpoint.md`

**Interfaces:**
- Produces: one frozen reserve/consume native/core/managed/capability identity family.

- [ ] Preserve the existing dirty Task 5 files and durable builds as historical evidence, but do not reuse their hashes after native/managed source changes.
- [ ] Regenerate the canonical patch from reviewed source and prove reverse/apply byte equality.
- [ ] Produce two clean builds from distinct copied durable source/toolchains and two installs; require raw core, compressed core, Build ID, source bundle, identity, and whole install tree byte equality.
- [ ] Record stale identity REDs before repinning Java, C#, capability, and documentation constants.
- [ ] Run `verify-deterministic-build.sh` across two checkout paths including spaces and hostile ambient compiler variables.
- [ ] Run native six-harness, managed observer/S1/S2/S3 focused gates, Java aggregate, real S1 transaction/terminal gate, S2 row769, S3K row810, and legacy S2/S3 vector/lifecycle gates.
- [ ] Run isolated paired S2 and S3K performance gates. Bind only fresh final-identity samples and require median <=10% and worst <=15%.
- [ ] Request independent review of exact frozen bytes; fix every Critical/Important/Minor finding under RED/GREEN and repeat affected identity gates.
- [ ] Commit the reviewed freeze with policy trailers. Do not push the worktree branch.

### Task 6: Integrate reviewed S2 and S3K R5 slices

**Files:**
- Integrate commits: S2 `110bc8e06`, `52aa85e4d`
- Integrate commits: S3K `3b704da19`, `73d6fe095`
- Modify only derived identity files if the combined managed assembly changes.

**Interfaces:**
- Consumes: independently clean S2 decoder/catalog and S3K publication-inert preflight.
- Produces: one reviewed remote-backed integration checkpoint.

- [ ] Cherry-pick the clean game commits after the conductor freeze, resolving only derived documentation/identity conflicts.
- [ ] Capture the expected deterministic executable/capability RED, recompute the combined tuple, and update derived constants once.
- [ ] Run Java combined audio tests, C# game/raw/profile tests, real row gates, native harnesses, legacy vectors/lifecycle, deterministic verifier, and fresh identity-bound performance.
- [ ] Request final cross-commit review and correct any stale handoff text or hash.
- [ ] Fetch and fast-forward the remote-backed integration worktree, record its baseline full-suite failures, cherry-pick the reviewed conductor/game commits, and rerun the same full suite plus focused gates.
- [ ] Push only `bugfix/ai-s1-audio-parity-frontier` after the integration worktree is clean and review is CLEAN; do not merge to `develop` before the user’s human test.

### Task 7: Resume per-game frontier rounds

**Files:**
- Determined by each game’s next strict frontier; use separate worktrees and game-owned files.

**Interfaces:**
- Consumes: the frozen shared core/harness and integrated S1/S2/S3K raw-state foundations.
- Produces: successively later truthful frontiers until every required complete-run reference and comparison gate is green.

- [ ] Dispatch one isolated S1 frontier worker immediately and S2/S3K workers when shared identities are stable; workers must stop at conductor-owned native/shared REDs.
- [ ] Require every round to cite ROM/disassembly ownership, commit locally, receive independent review, and report before/after frontier plus exact gate output.
- [ ] Cherry-pick reviewed wins into the remote-backed integration branch once per round, perform one combined identity cascade, and rerun affected real/semantic/performance gates.
- [ ] Continue until S1, S2, and S3K full reference captures, raw adapters/decoders, canonical producers, comparison/store publication gates, and terminal frontiers are all proven green; do not redefine completion around bounded prefixes.
