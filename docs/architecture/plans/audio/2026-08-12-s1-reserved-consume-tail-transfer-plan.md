# S1 Reserved Consume Tail-Owner Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve an action-11 deferred kind-4 begin while an exact kind-6 action-4 tail transfers its current blocking owner to kind 2 or 3, then consume it as a child at `$71B82`.

**Architecture:** ABI 3 retains immutable action-11 origin evidence and adds only internal mutable current-owner state. The configure-validated `$0077`/`$00C1` action-4 transactions emit existing END then successor BEGIN records atomically; action 12 selects by the exact current kind and begins kind 4 under the successor. Managed and Java validators retain exact transfer proof when they observe it; a baseline after omitted history instead accepts only the pinned native producer's structurally/configurationally legal current-owner attestation. No event kind, raw field, or schema version is added.

**Tech Stack:** C99 GPGX observer patch/selftest harnesses, C# BizHawk headless observer and capture tests, Java 21 JUnit 5 complete-run schema/store/comparator tests, Bash deterministic build/install tooling.

## Global Constraints

- Follow `docs/architecture/designs/audio/2026-08-12-s1-reserved-consume-tail-transfer-design.md` exactly.
- Keep GPGX audio ABI version 3 and config/kind/hook/range/event sizes `64/16/32/16/32` unchanged.
- Keep actions 11 and 12 and event kinds 1 through 11 unchanged; add no action or event value.
- Keep `openggf.s1-audio-service-manifest.v1`, `openggf.s1-complete-run-audio-raw.v1`, and Java strict JSON shapes unchanged.
- `NativeDeferredServiceBegin.blocker*` is immutable action-11 origin. Current owner is internal/derived from ordinary service records and cutoff active stack.
- A standalone/prepublication baseline attests a structurally/configurationally legal origin/current pair under the exact pinned producer identity; it does not prove omitted predecessor causality. Exact END-then-BEGIN causality is required only when the stream validator retained that transfer history.
- The only transfers are exact configured action-4 kind 6 to kind 2 at Z80 `$0077` opcode `1A`, and kind 6 to kind 3 at `$00C1` opcode `1A`.
- Derive the transfer reservation as `range_group_reservation(...) + 1`; for reviewed range id 2 this is exactly five event slots. Do not use a row, retry count, or measured fixture capacity.
- Preserve kind 6 without `ALLOW_CHILDREN`; do not add overlapping roots, recently closed ancestry, arbitrary tails, multi-hop transfer, trace fitting, or game/row checks in shared native code.
- Keep `ManagedServiceTracker` token+A7 identity-only. Do not add parent/depth or kind-2/3 entries.
- A tail transfer does not consume or rotate the deferred-publication generation. Action 12 consumes; a later action 11 creates the next generation.
- Build Java with JDK 21. Never use `--no-verify`.
- The worktree already contains unrelated/in-progress Task 5 changes. Inspect and preserve them; stage and commit only files owned by each checkpoint.

---

### Task 1: Freeze the native deferred-family configuration

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/matrix_harness.c`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`

**Interfaces:**
- Consumes: ABI-3 hook actions 4, 7, 11, and 12; service-kind flags; exact hook PC/opcode/range fields.
- Produces: one validated deferred family with origin expected kind 6, target kind 4, successor set `{2,3}`, and consume expected-kind set `{6,2,3}`.

- [ ] **Step 1: Write configuration REDs.** Expand `deferred_reserve_consume_matrix` with both exact action-4 tails, action-12 hooks for expected kinds 6/2/3 at `$71B82`, and same-PC action-7 partners for kinds 2/3. Add rejections for: missing/duplicate reserve; missing origin consume; missing/extra successor consume; missing/duplicate action-7 pair; pair PC/opcode/CPU/expected-kind mismatch; target mismatch; same-PC unapproved duplicate; missing/duplicate tail; tail expected-kind mismatch; successor without `ALLOW_CHILDREN`; origin with `ALLOW_CHILDREN`; successor equal to origin/target; orphan successor; and a second-hop tail from kind 2 or 3.
- [ ] **Step 2: Run the native suite and capture the intended RED.** Run `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/run.sh`. Expected: `matrix_harness` rejects the new valid family under the current exactly-one-consume rule or accepts one of the malformed families.
- [ ] **Step 3: Implement the closed-family validator.** In the patch, replace the scalar consume count/pointer with bounded configure-time bookkeeping keyed by expected kind. Require exact set equality between `{origin}` plus action-4 successors and action-12 expected kinds. Permit an action-7/action-12 duplicate only when every same-PC proof field matches and the expected kind is a validated successor. Keep all previous sort, token, opcode, reserved-byte, range, kind, and ABI checks.
- [ ] **Step 4: Prove no hidden constants.** Add assertions that the same validator accepts an isomorphic synthetic family with different legal kind IDs and exact PCs/opcodes, while rejecting an extra tail. The production S1 values must occur only in the manifest-derived fixture.
- [ ] **Step 5: Run all native harnesses.** Run `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/run.sh`. Expected: every ABI1/2/3, matrix, CPU-boundary, reset, capacity, and install harness passes.
- [ ] **Step 6: Review checkpoint.** Review only configuration behavior against the design. Require confirmation that the family is closed, action-7 pairing is successor-only, and no runtime/manifest/schema behavior was broadened.
- [ ] **Step 7: Commit the checkpoint.** Stage the two files and commit `test(audio): validate deferred tail transfer family` with required policy trailers.

### Task 2: Make native tail transfer and consume atomic

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/matrix_harness.c`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/cpu_boundary_harness.c`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`

**Interfaces:**
- Consumes: Task 1's configure-validated family.
- Produces: internal immutable `origin`, mutable `current_owner`, exact paired hook selection, five-slot action-4 transfer, and one-slot action-12 consume.

- [ ] **Step 1: Write kind-2 transfer REDs.** In `matrix_harness.c`, reserve under kind 6, execute `$0077`, and assert five events in exact snapshot BEGIN/CHUNK/END, kind-6 END, kind-2 BEGIN order; END and BEGIN must be adjacent. Assert the new root token/depth, unchanged origin fields/pending status, rebound current owner, and one token-cursor advance.
- [ ] **Step 2: Write the symmetric kind-3 RED.** Repeat with `$00C1`, kind 3, and its exact consume hook. Assert no route-specific branch is needed beyond config data.
- [ ] **Step 3: Write same-PC consume-selection REDs.** At the paired kind-2/kind-3 PC prove: an exact pending current owner selects action 12 and emits one kind-4 child; no pending reservation selects action 7 and emits its ordinary marker; any pending token/parent/kind/depth or expected-kind mismatch selects neither action, emits neither child BEGIN nor action-7 marker, and records the exact `SERVICE` fault. Also prove unchanged kind 6 selects its unique origin action 12; missing reservation on that unpaired route, duplicate consume, and corrupt opcode emit no BEGIN.
- [ ] **Step 4: Write atomicity/lifecycle REDs.** Cover exactly five free slots, four free slots, one-slot consume success, zero-slot consume, token exhaustion, duplicate transfer, wrong target/tail, Z80 chip ownership after transfer, forbidden M68K hook/write before consume, cross-frame transfer/consume, continuation aging, reset/power, abort, disable, and first-fault preservation. On every failure assert exact event count, stack bytes, token cursor, origin, current owner, and pending status.
- [ ] **Step 5: Write a CPU-boundary RED.** Make the real scheduling harness reserve during M68K bus request, let Z80 enter `$0077` before `$71B82`, then consume and close kind 4. Assert native ownership is kind 6 -> kind 2 -> kind-4 child and that the kind-6 origin marker remains unchanged.
- [ ] **Step 6: Run REDs.** Run `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/run.sh`. Expected: current code faults at the pending action-4 tail.
- [ ] **Step 7: Implement origin/current state and paired selection.** Split `trace_deferred_begin.blocker` into immutable origin plus current owner; resolve consume token by current expected kind. Add the pending-aware action-7/action-12 selector. Repeated action 11 validates origin only.
- [ ] **Step 8: Implement one prevalidated action-4 transaction.** Check owner, family route, ordinary parent legality, successor token availability, and `range_group_reservation + 1` capacity before all mutation. Emit completion then BEGIN and only then replace stack top/current owner. Do not call the ordinary pending-rejecting `pop_service` halfway through.
- [ ] **Step 9: Implement current-owner action 12.** Preflight one event and a token, require exact current owner and current-kind consume hook, emit the child, push it, then clear the reservation.
- [ ] **Step 10: Run native GREENs.** Run `tools/bizhawk-headless/native/gpgx-audio-observer/selftest/run.sh` twice, once from the worktree and once from a freshly patch-derived temporary source tree. Expected: identical green output.
- [ ] **Step 11: Review checkpoint.** Require a line-by-line atomicity review: no failure after mutation, exact five-slot derivation, END-before-BEGIN adjacency, immutable origin, one transfer maximum, no selector fallback while pending.
- [ ] **Step 12: Commit the checkpoint.** Commit `fix(audio): transfer deferred blocker across exact tails` with only native patch/harness files and required trailers.

### Task 3: Project origin/current ownership without publishing partial state

**Files:**
- Modify: `tools/bizhawk-headless/src/Audio/CompleteRunAudioObserver.cs`
- Modify: `tools/bizhawk-headless/tests/CompleteRunAudioObserverTests.cs`

**Interfaces:**
- Consumes: native ordinary action-4 END/BEGIN pairs and action-11/12 events.
- Produces: transactional `DeferredBeginReservation` origin/current state and cutoff evidence for S1 correlation.

- [ ] **Step 1: Write projection REDs.** Add exact kind-2 and kind-3 transfer frames. Require immutable `Blocker*`/marker coordinates and mutable current owner; then consume under the successor. Add a no-transfer kind-6 case.
- [ ] **Step 2: Add adversarial REDs.** Reject nonadjacent END/BEGIN, ordinal or coordinate gaps, different hook/PC/source, wrong old/new token, parent/depth change, wrong successor kind, duplicate transfer, END without BEGIN, BEGIN without END, and action-12 parent equal to origin after transfer. At the shared PC, require pending exact-current action 12, no-pending action 7, and pending mismatch to emit neither event/marker plus the exact `SERVICE` fault.
- [ ] **Step 3: Add rollback/cutoff REDs.** Fail a later event and consumer callback after a valid transfer and assert `activeServices`, pending/completed builders, global coordinate, port latches, origin/current reservation, and `LastCapture` are unchanged. Carry a transferred pending reservation through `CaptureCutoffFrontier` and `CaptureBoundaryFrontierAndResetPublication`; require cutoff current owner equals the open active-stack top.
- [ ] **Step 4: Run REDs.** Run `tools/bizhawk-headless/test.sh --jobs 1 --filter 'CompleteRunAudioObserverTests'`. Expected: current projection rejects the blocker completion before consume.
- [ ] **Step 5: Implement internal current-owner fields.** Extend only internal C# reservation/evidence/clone state; keep the public raw diagnostic origin properties intact. In `Project`, recognize transfer only after validating the adjacent action-4 pair, rebind current owner in scratch, and validate action 12 against it.
- [ ] **Step 6: Preserve exact cutoff representation.** Expose sufficient internal current-owner evidence to the S1 session, but do not add native ABI fields or raw JSON properties. Require the current owner to be the actual final open service.
- [ ] **Step 7: Run GREENs and allocation bounds.** Rerun the focused filter and existing scratch-capacity tests. Expected: all pass without a new unbounded collection.
- [ ] **Step 8: Review checkpoint.** Verify `CommitProjection` remains the only publication point and a failed transfer cannot leak service or reservation state.
- [ ] **Step 9: Commit the checkpoint.** Commit `fix(audio): project deferred tail owner transfer` with required trailers.

### Task 4: Correlate the transfer with immutable A7 identity

**Files:**
- Modify: `tools/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs`
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs`
- Verify unchanged: `tools/bizhawk-headless/fixtures/s1-audio-service-manifest-v1.json`

**Interfaces:**
- Consumes: Task 3 origin/current evidence, existing manifest action-4 tails, `deferred_consume_observation_kinds [2,3,4]`, and managed A7/return callbacks.
- Produces: action-12 hooks for expected kinds 6/2/3 and managed origin/current correlation in both epochs.

- [ ] **Step 1: Write manifest-generation REDs.** Assert generated action 12 exists for origin kind 6 and for exactly the action-4 successors 2/3; action-7 partners remain for 2/3/4; kind 4 receives no action 12. Assert all three action-12 hooks share `$71B82`, opcode `4D F9 00 FF F0 00`, target kind 4, and distinct tokens.
- [ ] **Step 2: Write published correlation REDs.** Capture action 11 with A7/return, native kind-6 END/kind-2 or kind-3 BEGIN, then `$71B82`. Require origin marker records to retain kind-6 token/parent/hook while consume BEGIN uses successor parent/depth. Repeat with multiple markers and cross-frame transfer/consume.
- [ ] **Step 3: Write boundary/cutoff REDs.** Repeat wholly before epoch, transfer before epoch and consume after epoch, and cutoff while transferred/pending. For a transfer observed before publication, validate the native producer's transactional projection. When the serialized baseline omits that history, validate immutable origin, the structurally/configurationally legal current top, exact carried-owner continuity, later consume under that current owner, and exact managed kind-4 token-set equality; do not reconstruct or claim an omitted origin END/successor BEGIN predecessor pair.
- [ ] **Step 4: Write identity/rollback REDs.** Reject changed A7 or return PC at retry/consume, wrong current token/kind/depth, malformed transfer adjacency, action-7 while pending, missing/duplicate consume, reset/power, later consumer failure, and terminal while pending. Assert transaction/output/deferred-generation state rolls back and session becomes terminally faulted where the existing lifecycle requires it.
- [ ] **Step 5: Prove tracker isolation.** Extend row-523 promotion tests so `ManagedServiceTracker.Entry` remains exactly token+A7, kind-2/3 never enter it, cutoff uses exact kind-4 token-set equality, and prepublication/published behavior is identical.
- [ ] **Step 6: Run REDs.** Run `tools/bizhawk-headless/test.sh --jobs 1 --filter 'S1CompleteRunAudioReferenceCaptureTests'`. Expected: current consume checks the immutable blocker token and/or generated config lacks successor action-12 routes.
- [ ] **Step 7: Implement derived successor generation.** Derive action-12 successor kinds from the already reviewed action-4 hooks whose expected kind is `deferred_begin_blocker_kind`; intersect with existing observation kinds and let native closed-family validation reject any mismatch. Do not add a manifest property or change its schema/content.
- [ ] **Step 8: Implement managed current-owner state.** Extend `DeferredManagedBegin` and clone/restore paths with internal current owner, update it only from exact adjacent native action-4 proof, retain origin fields in `EmitDeferredManaged`, and validate action-12 parent/depth against current owner plus original A7/return.
- [ ] **Step 9: Preserve generation semantics.** Confirm the publication generation id and held batch remain unchanged at tail transfer and rotate only on action-12 consume followed by a later action-11 reserve.
- [ ] **Step 10: Run focused GREENs.** Rerun both `S1CompleteRunAudioReferenceCaptureTests` and `CompleteRunAudioObserverTests`, including row-8775 and row-523 focused names. Expected: zero failures.
- [ ] **Step 11: Review checkpoint.** Require explicit review that origin output fields did not change, current owner came only from native producer projection (exact retained transfer or attested baseline top), omitted history was not reconstructed, and `ManagedServiceTracker` is still identity-only.
- [ ] **Step 12: Commit the checkpoint.** Commit `fix(audio): correlate deferred tail successor identity` with required trailers.

### Task 5: Validate Java origin/current state with the existing raw shape

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCutoffFrontier.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`
- Verify unchanged: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Verify unchanged: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureStore.java`

**Interfaces:**
- Consumes: existing `NativeDeferredServiceBegin` origin, ordinary `FrontierService` begin/end identities, action-12 `NativeManagedEvent`, and cutoff `activeStack`.
- Produces: internal `DeferredReservationState` with an attested baseline current owner or exact retained-history transfer proof, raw-sensitive equality, semantic exclusion of physical proof, exact cutoff continuity, and cross-frame terminal reconciliation.

- [ ] **Step 1: Write same-frame and cross-frame REDs.** Model kind-6 END at action 4, a successor whose ordinary record is released later, and action-12 child BEGIN before parent release. Require the comparator to bind current owner to the child parent provisionally, then reconcile successor `beginFrame == origin.endFrame`, `beginOrdinal == origin.endOrdinal + 1`, same hook/PC/source, preserved parent/depth, and exact token/kind.
- [ ] **Step 2: Write kind-2/kind-3 and no-transfer REDs.** Cover both reviewed successors plus unchanged origin consume. Use profile service rules to pin exact begin/end hook, PC, source, and kind; do not recognize kinds by a shared-code constant.
- [ ] **Step 3: Write standalone and attached cutoff REDs.** For a standalone/prepublication baseline, construct pending origin plus a transferred successor at `activeStack.getLast()` with exact pinned producer/observer identity. Accept it without a predecessor record when the top is open, has a distinct token, preserves origin parent/depth, uses exactly configured successor kind/hook/PC/source, and matches the producer projection. Reject wrong producer identity, illegal successor, reused token, wrong parent/depth, wrong hook/PC/source, non-open top, or missing origin. For a stream-attached cutoff whose validator retained the transfer, require exact derived-current equality and exact adjacent action-4 END/successor BEGIN proof; reject origin-as-top or any mismatched proof after that observed transfer. Continue an already-transferred attested baseline by exact carried-owner identity and allow later consume without reconstructing pre-baseline history.
- [ ] **Step 4: Write malformed/terminal REDs.** Reject END without eventual successor, wrong adjacency/hook/PC/source/kind/token, duplicate transfer, consume under origin after transfer, reset interposition, coordinate/ordinal collision, cutoff mismatch, and terminal with pending reservation or consumed-but-unreconciled transfer.
- [ ] **Step 5: Write raw/store equality REDs.** Change only origin or only physical successor token/hook/PC/source while preserving its semantic projection; each must change raw JSON/storage root while semantic JSON/root remains equal. Prove two omitted histories with the same attested standalone baseline serialize identically because predecessor causality is outside that baseline. Change successor kind/depth/topology and require both the appropriate structural rejection or semantic difference. Assert exact JSON field names/order for `NativeDeferredServiceBegin` are byte-identical to the current codec.
- [ ] **Step 6: Run REDs on JDK 21.** Verify `mvn -v` reports 21, then run `mvn "-Dtest=TestCompleteRunAudioTrace,TestCompleteRunAudioCutoffFrontier,TestCompleteRunAudioComparator,TestCompleteRunAudioCaptureStore" test`. Expected: current constructor insists cutoff top equals origin and comparator validates consume parent against origin.
- [ ] **Step 7: Implement attested and retained-history state.** Keep the Java record/codec unchanged. At standalone/prepublication baseline, bind metadata to the pinned producer/observer proof and accept only the exact origin or a structurally/configurationally legal one-hop current top; initialize carried current-owner state without fabricating predecessor proof. For transfers observed after baseline, make the stateful comparator own exact tail/adjacency reconciliation and retain that proof beyond action-12 consumption until the ordinary successor service record reconciles it. At cutoff, compare the exact carried or derived current owner according to which authority is available.
- [ ] **Step 8: Integrate raw order and lifecycle.** Include transfer END/successor BEGIN positions in the existing native order checks through service begin/end ordinals; forbid collisions/regression and reconcile before terminal. Do not synthesize a producer-neutral event.
- [ ] **Step 9: Run focused GREENs.** Rerun the four test classes. Then run `mvn "-Dtest=TestCompleteRunAudioReplayCadence,TestHardwareTimingAuthorityGuard" test`. Expected: both compatibility guards pass.
- [ ] **Step 10: Review checkpoint.** Compare strict JSON output before/after for existing fixtures. Require no new field/version; explicit pinned-producer attestation at standalone baseline; no invented pre-baseline causality; exact END/BEGIN causality whenever retained stream history contains the transfer; exact cutoff continuity; correctly scoped raw/store versus semantic sensitivity; and cross-frame terminal rejection.
- [ ] **Step 11: Commit the checkpoint.** Commit `fix(audio): validate deferred tail owner history` with Java source/tests and required trailers.

### Task 6: Rebuild identities and run the final real gate

**Files:**
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/artifact-lock.json`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/task7-build-recipe.json`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/build-core.sh`
- Modify: `tools/bizhawk-headless/native/gpgx-audio-observer/README.md`
- Modify: `tools/bizhawk-headless/fixtures/gpgx-audio-capability-v1.json`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: exact identity assertions under `src/test/` and `tools/bizhawk-headless/tests/`
- Modify: `docs/architecture/research/audio/2026-08-11-complete-run-audio-frontier-checkpoint.md`
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: reviewed Tasks 1-5 and the existing deterministic native build flow.
- Produces: one frozen identity family plus durable row-119247/next-frontier evidence; no capture publication unless terminal gates genuinely pass.

- [ ] **Step 1: Run the complete synthetic gate before identity repin.** Run native selftests, both C# focused classes, the four Java focused classes, and existing action-8/9, row-8775, row-12525, S2, and S3K focused gates. Record exact failures; only expected stale identity literals may remain.
- [ ] **Step 2: Regenerate the canonical patch and prove reversibility.** Use the repository's documented patch generation flow, then apply and reverse it against pristine pinned GPGX source and require byte equality.
- [ ] **Step 3: Produce two deterministic builds/installations.** Build from two distinct clean copied source/toolchain paths, including one path containing spaces, into `target/audio-parity/native/tail-transfer-install-a` and `target/audio-parity/native/tail-transfer-install-b`. Require raw core, compressed core, Build ID, source bundle, recipe, capability, and full installation tree byte equality before updating literals.
- [ ] **Step 4: Repin once.** Update artifact lock, recipe, README, capability, Java/C# identity constants, and assertions from the reviewed identical outputs. Preserve unrelated pre-existing Task 5 changes and reconcile rather than overwrite them.
- [ ] **Step 5: Run the exact real configured-terminal command.** From this repository's conventional `.worktrees/<name>` worktree, use the verified ROM `../../Sonic The Hedgehog (W) (REV01) [!].gen` (SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`), movie `src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2` (SHA-256 `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`), and install A:

  ```bash
  OPENGGF_S1_AUDIO_PREFIX=1 \
  OPENGGF_S1_AUDIO_TERMINAL_PROBE=1 \
  S1_ROM_PATH='../../Sonic The Hedgehog (W) (REV01) [!].gen' \
  S1_AUDIO_BK2_PATH='src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2' \
  BIZHAWK_HOME='target/audio-parity/native/tail-transfer-install-a' \
  tools/bizhawk-headless/test.sh --jobs 1 \
    --filter 'S1CompleteRunAudioReferenceCaptureTests consume one deferred child begin during row 8775 wait service'
  ```

  Require that row 119247 does not repeat native fault `4:1:77:6:1:0:4`. Preserve the complete stdout/stderr log and SHA-256. If the command stops later, record the exact new first frontier and do not broaden this contract. Claim `Complete(225101)`, publication, or semantic MATCH only if the log actually proves it.
- [ ] **Step 6: Repeat the real gate against install B.** Require byte-identical relevant lifecycle/native evidence and the same deterministic result/frontier.
- [ ] **Step 7: Run compatibility and performance gates.** Run all native harnesses; C# observer/S1/S2/S3 tests; Java full audio aggregate on JDK 21; S2 row769; S3K row810; legacy S2/S3 vectors/lifecycle; deterministic verifier; and fresh identity-bound paired performance gates with existing median `<=10%` and worst `<=15%` thresholds.
- [ ] **Step 8: Update durable evidence.** Record command, worktree/commit, identities/hashes, PASS/FAIL, error count, first-error row/frame/field, row-119247 movement, next frontier, and explicit no-publication statement in the checkpoint and trace-frontier log.
- [ ] **Step 9: Final independent review checkpoint.** Require review of source authenticity, config closure, atomicity, ABI/schema preservation, managed identity, Java cutoff/terminal reconciliation, frozen bytes, and the unedited real log. Resolve every finding under a new RED/GREEN cycle and rerun affected gates.
- [ ] **Step 10: Commit the reviewed freeze.** Commit `fix(audio): accept source-authentic deferred tail transfer` with artifact/docs/identity changes and policy trailers. Do not push the worktree branch; follow the parent integration workflow for baseline comparison, merge, post-merge full suite, push, and cleanup.
