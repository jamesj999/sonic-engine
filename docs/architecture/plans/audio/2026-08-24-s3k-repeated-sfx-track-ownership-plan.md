# S3K Repeated-SFX Service-Phase Implementation Plan

**Goal:** Match the shipped locked-on S3K request/service/admission order so
repeated Explosion and overlapping Collapse playback retain their native
physical-track lifetimes without adding sound- or zone-specific behavior.

**Design:**
`docs/architecture/designs/audio/2026-08-24-s3k-repeated-sfx-track-ownership-design.md`

**Worktree:**
the isolated `bugfix/ai-s3k-sfx-overwrite-parity` worktree

**Branch:** `bugfix/ai-s3k-sfx-overwrite-parity`

**Base:** `4618e882bdee5ca9c9f94f8344a60833f6e20ee1`

## Rules

- Use JDK 21 and run every certifying Maven gate through
  `tools/testing/test-session.sh --`; record its run ID, manifest, counts, and
  log SHA-256.
- Use absolute authenticated S1/S2/S3K ROM paths. Run `mvn -v` in every shell
  session before Maven certification.
- Raw native outputs go under `agent-scratch`; tracked evidence is compact.
- Follow RED -> GREEN -> refactor. Preserve the first RED.
- Production behavior may branch only on typed SMPS policy. Never branch on a
  sound ID, boss, zone, movie, frame, or captured ordinal.
- Use `apply_patch` for edits. Do not touch the dirty main-workspace files.
- No merge or push before automated evidence and the user's listening gate.

## Task 1: Freeze inputs and baseline

1. Verify branch/base/status, install tracked hooks, and assert their path:

   ```bash
   tools/testing/install-hooks.sh
   test "$(git config --get core.hooksPath)" = .githooks
   ```

   Discover all three ROMs by hash and export
   `S1_ROM_PATH`, `S2_ROM_PATH`, and `S3K_ROM_PATH`.
2. Authenticate the retained AIZ movie SHA-256:
   `6837de0f67db7eb68f20b6f6df6a2872713a613d8b4dbc804847209c16b56e97`.
3. Run the untouched focused baseline:

   ```bash
   mvn -v
   tools/testing/test-session.sh -- mvn -Dmse=off \
     -Ds3k.rom.path="$S3K_ROM_PATH" \
     -Dtest=TestS3kCollapseDashSfxParity,TestSfxContentionObserver,TestPreparedSfxAdmission,TestS3kAizRockCollapseAudioHeadless \
     test
   ```

   The observed baseline is 53/53 green.

## Task 2: Freeze source-authenticated first-divergence evidence

The current locked diagnostic core already exposes the decisive ordering. Do
not widen or repin native ABI in this slice.

### Files

- Modify: `tools/bizhawk-headless/tests/GpgxS3kAudioParityManifestTests.cs`
- Add: `tools/bizhawk-headless/tests/S3kSfxLifecycleReferenceCaptureTests.cs`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`
- Add:
  `docs/architecture/research/audio/2026-08-24-s3k-repeated-sfx-first-divergence-audit.md`

### Evidence

1. Run the retained AIZ movie twice through the current locked core. The
   preflight reads native Z80 track RAM after each frame and drains ordinary
   trace events to prove zero overflow. It selects residences by source
   pointers, never by a runtime frame condition:

   ```bash
   TASK_DIR="$(agent-scratch new s3k-sfx-overwrite-parity | tail -n 1)"
   : "${DIAGNOSTIC_CORE_INSTALL:?set the retained diagnostic install path}"
   export BIZHAWK_HOME="$DIAGNOSTIC_CORE_INSTALL"
   for pass in a b; do
     OPENGGF_GPGX_S3K_LIFECYCLE_PREFLIGHT=1 \
     S3K_ROM_PATH="$S3K_ROM_PATH" \
     OPENGGF_S3K_LIFECYCLE_MOVIE="$PWD/src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2" \
     OPENGGF_S3K_LIFECYCLE_PREFLIGHT_OUTPUT="$TASK_DIR/preflight-$pass.json" \
     tools/bizhawk-headless/test.sh --jobs 1 \
       --filter 'S3kSfxLifecycleReferenceCaptureTests preflight retained gameplay movie'
   done
   cmp "$TASK_DIR/preflight-a.json" "$TASK_DIR/preflight-b.json"
   ```

   Require 21,309 movie frames; 134 Explosion FM5 reloads; first Collapse frame
   1,558; last active frame 1,678; exactly 121 active Collapse frames; later
   SFX traffic at 1,640, 1,641, 1,647, 1,653, 1,656, 1,657, 1,663, 1,669,
   1,672, and 1,673; and zero overflow.

2. Capture `explode-repeat` twice with the existing parity manifest, injecting
   B4 every three frames through frame 30. Require byte-identical output, zero
   fault/overflow, one FM5 residence, current-residence modulation before each
   later admission, and final stop at frame 57. Save hashes in the audit.

3. Cite and test the source contract:
   - `zUpdateEverything` services SFX before `zUpdateMusic`;
   - `zUpdateMusic` transfers and consumes the three sound cells;
   - 68K `Play_SFX` owns two SFX input cells, suppresses slot-0 duplicates,
     fills empty slot 0, otherwise overwrites slot 1;
   - explosion children at `$83F60/$83F62` and `$83F96/$83F98` publish B4.

4. Preserve a strict Java RED proving the current engine retires the old B4
   before its current-boundary service. Compare semantic service/admission
   ordering and final lifecycle, not admission-preparation writes mislabeled as
   track-service writes.

5. Write the first-divergence audit, run native focused tests and
   `git diff --check`, then commit these diagnostic/test paths with all seven
   explicit policy trailers. Because `tools/*` is ignored, force-stage only
   the new `S3kSfxLifecycleReferenceCaptureTests.cs`; stage the already tracked
   project/runner/manifest paths normally. This commit changes no `src/main`
   playback code.

## Task 3: Implement typed two-cell S3K queue phase with TDD

### Files

- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/driver/PreparedSfxAdmission.java`
- Modify: `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java`
- Modify only if necessary:
  `src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java`
- Modify/add focused tests:
  - `src/test/java/com/openggf/audio/driver/TestS3kCollapseDashSfxParity.java`
  - `src/test/java/com/openggf/audio/TestPreparedSfxAdmission.java`
  - `src/test/java/com/openggf/audio/TestSfxContentionObserver.java`
  - `src/test/java/com/openggf/audio/driver/TestSmpsDriverYmWriteTimeline.java`
  - `src/test/java/com/openggf/audio/TestAudioPresentationSnapshotParity.java`
  - `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `CHANGELOG.0.6.md`

### RED

1. Refine the repeated-B4 RED to assert:
   - each active old B4 receives its third current-boundary service before the
     next admission;
   - the next residence is installed after that service and first advances on
     the following boundary;
   - exactly one physical FM5 owner exists;
   - music FM5 never restores between requests;
   - the final residence reaches natural stop/restore.
2. Add source-cell tests for empty slot 0, duplicate slot-0 suppression,
   ordered slot 0 then slot 1 consumption, and slot-1 overwrite.
3. Add snapshot/restore with both cells occupied, reset/stop discard, checked
   request ordinal overflow, observer poison/retry, and exact queue-boundary
   rollback. Assert no admission/contention/chip callback before consumption.
   Add simultaneous music controls: ordinary music is applied before surviving
   SFX admissions, while the active 1-up/fade-to-previous clear path discards
   both cells without admission/chip callbacks.
4. Add a whole-service capacity N/N-1 test covering old SFX service + both
   admissions + post-admission music service with no prefix publication.
5. Add S1/S2 controls proving `SAME_DRIVER_UPDATE` stays immediate.

### GREEN

6. Reuse the existing typed `SfxStartTiming` owner:
   - `SAME_DRIVER_UPDATE`: current immediate S1/S2 path;
   - `NEXT_DRIVER_UPDATE`: two immutable pending SFX cells with shipped S3K
     insertion rules.
7. A pending entry stores the sequencer, continuous metadata, and checked
   request ordinal but owns no lock/claim. Do not precompute a stale physical
   action set. At consumption, prepare against then-current roles/priority and
   commit through the existing atomic admission path.
8. At each SFX-first boundary snapshot the active SFX set, service it, consume
   the typed music/jingle decision, consume the surviving two pending cells in
   order, then service music. Newly admitted SFX are excluded from that
   boundary's SFX snapshot.
9. Include pending cells and request ordinal in stable snapshots and live
   rollback. Keep unpublished service journals transaction-local. Extend
   aggregate preflight to include the active service, at most two admission
   preparations, post-admission music, and due PAL full-driver repeat.
10. Keep physical role handoff unchanged unless a focused role test exposes a
    second source-proved divergence. No sound-ID branch is allowed.
11. Add an architecture guard that confines pending-cell mutation to reviewed
    `SmpsDriver` entry/service/reset/restore paths.

### Verification and commit

12. Run JDK21 focused and cross-game ROM gates through test-session. Mutation
    test slot order and the boundary-entry active-set snapshot, revert each
    mutation, and rerun green.
13. Update `CHANGELOG.0.6.md`; commit with normal hooks and all seven explicit
    trailers. Use `fix(audio): preserve S3K SFX queue phase`.

## Task 4: Real gameplay acceptance

### Files

- Add: `src/test/java/com/openggf/tests/TestS3kAizBossExplosionAudioHeadless.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kAizRockCollapseAudioHeadless.java`

1. Boot AIZ1 with active music and drive the production miniboss defeat path;
   do not submit B4 directly. Separately assert object-update request cadence
   and driver service/admission cadence. Compare the native first-divergence
   contract, FM5 ownership/writes, final restore, and bounded mixed PCM.
2. Keep music enabled in the user-described Tails AIZ rock route. Assert exact
   Collapse roles, actual later SFX intersections, PSG3/noise ownership and
   writes through the 121-frame native residence, and component-attributed PCM
   rather than total packet energy.
3. Add rewind points before a request, with both pending cells populated, after
   admission, and before final completion. Clean and restored ownership/write/
   PCM digests must match.
4. Preserve the original REDs, then run:

   ```bash
   mvn -v
   tools/testing/test-session.sh -- mvn -Dmse=off \
     -Ds3k.rom.path="$S3K_ROM_PATH" \
     -Dtest=TestS3kAizBossExplosionAudioHeadless,TestS3kAizRockCollapseAudioHeadless,TestS3kCollapseDashSfxParity,TestSmpsDriverYmWriteTimeline,TestAudioPresentationSnapshotParity \
     test
   ```

5. Commit the gameplay tests with all seven explicit trailers.

## Task 5: Review and full regression comparison

1. Freeze the exact diff/path list and independently review source evidence,
   two-cell semantics, service order, role lifetime, rollback, snapshots,
   observers, capacity, real gameplay, and absence of carve-outs. Resolve every
   Critical/Important finding with RED/GREEN evidence.
2. Run the complete focused S3K audio, presentation, rewind, architecture, and
   native observer/parity gates. Regenerate both preflights and injected
   captures byte-identically.
3. Fetch remote. In an isolated baseline worktree at the updated integration
   commit, run the full JDK21 three-ROM suite and `-Pguards` suite. Save full
   logs plus sorted failure/error identity/status ledgers.
4. Run identical commands in the development worktree. A red baseline is
   acceptable; any new or worsened candidate identity is not.
5. Add validation report
   `docs/architecture/validation/audio/2026-08-24-s3k-repeated-sfx-ownership-validation.md`,
   update `docs/S3K_KNOWN_DISCREPANCIES.md`, and stage this design/plan plus all
   research evidence. Record commands, hashes, counts, mutation evidence, and
   remaining bounds. Commit with policy-valid trailers.

## Task 6: Exact-HEAD listening handoff

1. On a clean exact HEAD, build the JAR with all three ROM properties and tests
   enabled. If the unchanged red baseline blocks assembly, use only
   `-Dmaven.test.failure.ignore=true` and retain Surefire results.
2. Verify ZIP integrity, embedded clean commit metadata, size, and SHA-256.
3. Give the user the exact worktree, branch, commit, JAR, hash, and command.
   Listening checklist: AIZ1 miniboss/AIZ2 boss Explosion beginning/middle/end;
   the Tails rock Collapse route with music and Spring; isolated Collapse;
   nearby music and unrelated SFX.
4. Do not merge or push until the user confirms improvement.

## Task 7: Integrate only after listening approval

Follow the root `AGENTS.md` integration procedure exactly: preserve the dirty
main workspace, fetch and fast-forward its checked-out branch, record the
actual dirty-main baseline, reconcile updated develop in this worktree, merge
without switching the main workspace, stage the required `README.md` release
summary, rerun full/guard identity comparisons, push only the main-workspace
branch, then remove the clean merged worktree and delete its fully merged local
branch. Report every pushed commit and exact verification result.
