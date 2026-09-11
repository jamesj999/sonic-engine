# Suite Failure Remediation Implementation Plan

## Objective

Turn the post-backport red suite into a clean, evidence-based inventory, fix
shared causes before downstream symptoms, and deliver no new regressions to
`develop`.

## Execution Rules

- Run Maven on JDK 21.
- Discover all three ROM files and pass their absolute paths with
  `sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path`.
- Preserve the pre-clean Surefire XML and trace reports outside `target/`
  before the first clean build.
- Add a failing focused test or reproduction before each production change.
- Never change trace data, comparison logic, or guard baselines to make an
  unexplained runtime failure disappear.
- Do not remediate or gate delivery on trace replay failures. PLC, DPLC, and
  decompression queues require further work before traces are deterministic.
- Every authoritative suite command passes
  `-Dsurefire.excludesFile=config/surefire-non-trace-excludes.txt`.
  *(Retired 2026-08-28: the file was never wired into `pom.xml` or CI, the
  default Surefire configuration already excludes `**/tests/trace/**`, and its
  one extra entry only hid `TestS3kCnzVisualCapture`, whose failure has since
  been fixed at the root. Plain `mvn -Dmse=off test` is the ordinary suite.)*
- Update this plan and the design, then re-review both, if investigation changes
  an ownership or sequencing assumption.

## Task 1: Establish the Clean Baseline

1. Record `git status`, commit, `mvn -v`, and discovered ROM paths/hashes.
2. Copy current Surefire XML, dumps, and trace reports to a newly created,
   run-specific directory under `/tmp/openggf-suite-failure-remediation-*`.
3. Run:

   ```bash
   mvn -Dmse=off clean test \
     -Dsurefire.excludesFile=config/surefire-non-trace-excludes.txt \
     -Dsonic1.rom.path=<absolute-s1-rom> \
     -Dsonic2.rom.path=<absolute-s2-rom> \
     -Ds3k.rom.path=<absolute-s3k-rom>
   ```

4. Let the suite finish. Treat it as stalled only if the Maven process and
   Surefire report timestamps both stop progressing; obtain `jcmd` dumps before
   terminating a genuine stall.
5. Parse the completed reports into failure/error clusters and reconcile them
   with the pre-clean inventory.
6. Assert that no generated Surefire XML belongs to `com.openggf.*.trace`,
   `com.openggf.trace`, or a `*Trace*` test class.
7. Gate Tasks 2–8 from this clean inventory. A pre-clean-only failure receives
   a stale-output disposition and does not trigger production changes.

## Task 2: Restore Initial S3K Setup Authority

Files to inspect and likely modify:

- `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- `src/main/java/com/openggf/game/LevelLoadContext.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/level/InitialProcessSpritesLifecycleCoordinator.java`
- the production title-card/load caller that consumes admission
- `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`

Steps:

1. Add a boundary-focused test proving whether the fresh full production load
   requests, publishes, or prematurely consumes the lifecycle token.
2. Trace the exact transition that changes pending
   `LOAD_THEN_PROCESS_ONCE` to `NONE`.
3. Fix the owning production boundary. Preserve denial for preview,
   decode-only, state restoration, and post-load-disabled contexts.
4. Verify pause retains the token, first admission executes setup exactly once,
   retry runs gameplay using the same input row, and rewind restores both
   pre-consumption and post-consumption states.
5. Run:

   ```bash
   mvn -Dmse=off -Dtest=TestS3kInitialObjectSetupLifecycle,TestRecordingFrameDriverInputOnly,TestTitleCardObjectExecution test
   ```

6. Do not use trace classes to validate this task.

## Task 3: Repair Canonical Hardware Boundary Order

Files:

- `src/main/java/com/openggf/LevelFrameStep.java`
- `src/test/java/com/openggf/TestLevelFrameHardwareTimingBoundaries.java`
- `src/test/java/com/openggf/TestGameLoopHardwareTimingBoundaries.java`

Steps:

1. Correct the stale full-frame test expectation to match the ROM-backed
   sequence: profile-specific player/object dispatch, camera/ScreenEvents, then
   `POST_OBJECTS`.
2. Strengthen the test to count service and observer invocations, reject nested
   duplicates, and prove that newly retired work is first visible to
   object/event consumers on the next dispatch.
3. Preserve the profile-specific ordering: inline-solid runs physics then
   objects, while legacy runs objects then physics.
4. Retain the special object-scan helper’s standalone boundary sequence.
5. Verify full-frame, setup-only, paused, inline-solid, legacy-order, and
   alternate object-scan tests.

## Task 4: Make Results Harnesses Timing-Capable

Production queue behavior remains unchanged. A focused production-path test
has proved that restore-only results shells lack their derived ROM mapping
table.

Files to inspect and modify:

- lightweight `ObjectServices` fixtures used by results/boss tests
- `src/test/java/com/openggf/game/sonic3k/objects/TestMgzDrillingRobotnikInstance.java`
- LBZ, AIZ, ICZ results/boss tests named by the clean inventory
- results rewind tests

Steps:

1. Add a shared test fixture that owns a real `HardwareTimingService`.
2. Inject it through `ObjectServices.hardwareTiming()`.
3. Add assertions for stable submission ordinals, boundary-driven readiness,
   claim behavior, and rewind rebind without resubmission.
4. Exercise the actual production rewind capture/restore path for pending and
   ready-unclaimed results objects. In
   `S3kResultsScreenObjectInstance.restoreRewindState`, after captured state is
   restored, rehydrate a null mapping table from the injected ROM reader using
   the same tile-index and nonzero-act name adjustments as initial load. This
   restore-only path must not queue, submit, service, or claim art.
5. Add post-claim restoration: preserve the three stable ordinals after claim;
   expose a read-only `HardwareTimingService` lookup that clones the prepared
   payload of a matching claimed job and rejects non-claimed work; assemble the
   three payloads through the existing results pattern-placement logic; rebuild
   transient HUD patterns, sprite sheet, and renderer. Assert no submission,
   service, release, or claim, and unchanged job count/next ordinal.
6. Convert only affected lightweight harnesses to the shared fixture.
7. Run the focused results, boss, and rewind classes.

## Task 5: Isolate Cross-Test State When Clean Failures Remain

1. If clean Snale Blaster passes, record stale-output disposition and stop work
   on that cluster.
2. For a remaining Snale failure, discover the predecessor by bisecting the
   clean-suite class order, then reproduce predecessor→Snale and
   Snale→predecessor.
3. Only after identifying an actual leaked owner, add a deterministic
   regression test and fix reset/snapshot ownership at the session,
   singleton-reset extension, or registry owner.

## Task 6: Resolve Architecture and Rewind Guards

Run each guard separately and fix the reported source:

1. Resolve or disposition the already-failing `AGENTS.md`/`CLAUDE.md` mirror
   guard. If either file changes, stage them together.
2. Extract cohesive code from `LevelManager`, `AbstractPlayableSprite`, and
   `GameLoop` until their existing budgets pass.
3. Replace direct `GameServices` access in AIZ/HCZ events and playable movement
   with existing injected owners.
4. Reduce `PlayerMovementRules` through a cohesive nested rule or existing
   provider boundary, without weakening the record guard.
5. Route LBZ Robotnik ship touch behavior and cup-elevator subpixel writes
   through approved object/position APIs.
6. Add real rewind capture/recreate/probe coverage for Flybot, results,
   Orbinaut children, gradual camera child, and every cleanly reproduced
   inventory row.
7. Re-run the complete architecture and rewind guard sets.

Size-budget extraction must preserve ordinary formatting; line folding is not
an acceptable reduction. Any extracted shared startup helper must consume a
semantic provider result and must not import game-specific object IDs.

For bonus-stage bootstrap specifically:

1. Add an optional `BonusStageProvider.BootstrapObject` semantic containing the
   ROM `ObjectSpawn`, concrete type for duplicate detection, and factory.
2. Implement it in `Sonic3kBonusStageCoordinator` for
   `GLOWING_SPHERE`/Pachinko; other types and the no-op provider return null.
3. Make `GameLoop` query the active provider and consume only this semantic
   value, with no S3K ID or object import in shared startup code.
4. Test provider ownership, S3K spawn/factory values, duplicate prevention,
   and null behavior for other bonus types/games.

## Task 7: Close Every Remaining Non-trace Failure

For each non-trace failure remaining after Tasks 2–7:

1. Reproduce the class alone.
2. Reproduce it in the smallest relevant reused-fork sequence.
3. Trace the behavior to its ROM/disassembly or established runtime contract.
4. Add a focused failing test, implement the owning fix, and re-run neighbors.
5. If it is pre-existing, prove the same failure on the updated integration
   baseline and record the exact command/output. Unexplained failures remain
   blocking.

Prioritized groups are movement/collision, act transitions, S2 post-load
offsets, AIZ/HCZ/ICZ features, LBZ sequencing, and GameLoop structural tests.

## Task 8: Review and Verification

1. Request a code review covering lifecycle ownership, timing boundary order,
   fixture accuracy, state reset, and guard compliance; resolve all blockers.
2. Run all focused clusters again.
3. Run the authoritative JDK 21 `mvn clean test` with all ROM paths to natural
   completion.
4. Compare the resulting report set with the updated integration baseline.

## Task 9: Integrate and Deliver

1. Stage the design, plan, validation/frontier documents, and implementation;
   commit the development work with required trailers.
2. Fetch and fast-forward the main-workspace `develop` without disturbing user
   changes.
3. Record the non-trace suite result on that updated baseline using the same
   excludes file.
4. Reconcile updated `develop` into the development worktree, resolve
   conflicts, and run the same non-trace suite plus focused clusters there.
5. Merge the verified worktree branch into main-workspace `develop`, including the
   required `README.md` release/change-log update.
6. Run the merged non-trace suite and compare reports.
7. Push `develop`.
8. Verify the worktree contains no unknown changes, remove it, delete the fully
   merged local worktree branch, and prune metadata.

## Completion Evidence

The final report will include:

- root causes and fixes by cluster;
- clean and focused Maven commands with exact outcomes;
- ROM properties and verified hashes used;
- trace results listed separately as out of scope;
- baseline and merged-suite comparison;
- integration conflicts and resolutions;
- pushed branch and commits.
