# Code cleanup: three audit passes

Base: `develop` at `3295ee9cd`. Implementation branch: `bugfix/ai-slop-cleanup`.
The scope is unused code and ineffective coverage, with no intended gameplay change.

## Pass 1: confirmed findings

- Removed both unused `TouchResponseProfileAdapter` classes: each simply forwarded
  to its adjacent profile factory, with no callers.
- Removed `PersistentAccumulator` and its arithmetic-only test. No scroll handler
  used the wrapper.
- Removed `TraceHistoryHydration` and its two tests. Its only callers were those
  tests; the history conversion ignored its extent parameter and copied unchanged
  coordinates. Removed its entry from the current contributor reference. Dated
  historical plans and audit inventories remain historical records.
- Removed `LevelRewindBoundaryCoordinator`; its sole caller was a test. The test
  now exercises the existing `LevelManager.markRewindLevelLoadBoundary` production
  method. Inlined the additional private wrapper at the actual load boundary.
- Repaired `snapshotIsImmutable` to re-read the original snapshot after mutating
  its returned array, instead of capturing and checking a different snapshot.
- Replaced the game-mode-listener setter smoke test with two transitions that
  verify the old/new modes and exactly one callback per transition.
- Removed duplicate initial-game-mode assertions, two enum lookup tests, and four
  tests that merely called inherited empty ending-provider methods.

## Pass 2: adjacent duplication

- Removed the unused `AbstractCnzTraversalVisibleStubInstance`, which had no
  subclasses. Removed its stale file-list entry from the architectural source
  guard while retaining checks for the remaining files.
- Removed 103 adjacent duplicate `SessionManager.clear()` calls across 45 test
  and test-support files. These were setup, teardown, or state-reset operations,
  not idempotency tests. `clear()` nulls the session and destroys the current mode
  once; the immediately repeated call adds no cleanup.
- Removed the canonical-animation enum-name inventory and fixed enum-count tests.
  The actual per-game round trips, name bridge, unsupported-animation handling,
  hurt mapping, and super-variant tests remain.

## Pass 3: private helpers and resulting coverage

- Removed unused private `startDataSelectFromTitleScreen`, `isElementVertical`,
  `clearIfNoLongerStanding`, `onStandingAccelerating`, and `copyStaticConfig`.
  Checked both ordinary and string/reflection references before deletion.
- Removed private `s3kPreviewFrameAt`, its otherwise-unused constant, and the lone
  assertion that invoked it reflectively. Production does not use that helper;
  finger-wag and wink timeline coverage remains.
- Removed the test of generated `ScrollComposeContext` record accessors; real
  deformation/composition tests still exercise context inputs.

The audit also considered the legacy/canonical collision-profile conversions,
`InitialProcessSpritesLevelManagerBase`, rewind adapters, and the unused
`ObjectPlayerRangeOps` research primitive. They were retained: the former have
live ownership/migration roles, and the latter carries independently sourced ROM
arithmetic and boundary tests. Migrating live profile ownership or deciding the
fate of unintegrated parity research needs a separate review.

## Verification

Maven uses JDK 21.0.11. All runs use `LUA_BIN=lua5.4`, `-Dmse=off`, and verified
absolute ROM properties (`OPENGGF_MAIN` is the absolute main-checkout path):

```sh
-Dsonic1.rom.path=${OPENGGF_MAIN}/s1.gen
-Dsonic2.rom.path=${OPENGGF_MAIN}/s2.gen
-Ds3k.rom.path=${OPENGGF_MAIN}/s3k.gen
```

The ROM SHA-1 values match the repository's specified S1 REV01, S2 REV01, and
locked-on S3K hashes. Maven output remains below each worktree's `target/`.

- Mutation check: 5 tests, exactly 3 expected failures, no errors or skips. The
  repaired tests reject a discarded listener, suppressed real level-boundary
  notification, and an exposed snapshot array. All temporary mutations restored.
- Final focused command: `mvn -Dmse=off -Dtest=TestLevelManagerRewindBoundary,TestOscillationManagerSnapshot,TestGameLoop,TestScrollEffectComposer,TestCanonicalAnimationMapping,TestSonic2EndingProvider,TestArchitecturalSourceGuard,TestMasterTitleRomPreview,*SpeedLauncher*,*Sonic3kTitleCard*,*AudioPresentationSource* test -B`
  with the properties above: 246 tests, no failures, errors, or skips.
- Baseline ordinary suite: `mvn -Dmse=off test -B`, with the ROM properties above:
  16,477 tests, zero failures/errors, 40 skipped.
- Development ordinary suite, same command: 16,465 tests, zero failures/errors,
  the same 40 skipped. Every retained testcase kept its baseline outcome. Exactly
  12 tests were removed from this suite; the listener test was renamed/replaced.
  The two deleted history-helper tests were outside the ordinary suite.
- Baseline structural guards: `mvn -Dmse=off -Pguards test -B`, with the ROM
  properties above: 609 tests, zero failures/errors/skips.
- Development structural guards, same command: 609 tests, zero failures/errors/
  skips. All testcase identities and outcomes match the baseline.
- Independent read-only review found no actionable issues. Caller searches covered
  reflective/string references as well as Java calls; `git diff --check` passed.

Surefire emits repeated testcase identities for some nested/dynamic tests. Reported
suite totals above are Maven's final totals; outcome comparisons separately check
retained testcase identities and skip status. Baseline skips are the existing
optional/disabled tests, not missing-ROM skips.

Post-merge delivery repeats both commands on `develop` and compares their results
against these baselines before push. Generated logs and testcase outcome snapshots
are retained in the main workspace's `target/slop-cleanup-evidence/`; the final
delivery report records the pushed commits and post-merge results.

## Interrupted first post-merge run

The first ordinary run at merge `e03ebb9a8` finished with 16,465 tests, one
failure, six errors, and 40 skips. All seven unsuccessful tests were class-loading
failures (including an expected-exception assertion receiving
`NoClassDefFoundError`). They affected `TestGameLoopSpecialStageRewindGate`,
`TestS1VisualPlaybackControlLock`, both `TestLevelRewindFrameRecorder` tests,
two `TestSpecialStageHardwareTimingLifecycle` tests, and one
`TestTraceSessionLauncherFailureCleanup` test.

Evidence of build-output interference: the run compiled before tests started at
21:33, but production class files and the Maven compiler `createdFiles.lst` were
rewritten at 21:36:38–21:36:48, during the failing tests. The missing types were
`TraceSuppressedRowClosure`, `LevelRewindFrameRecorder`,
`SpecialStageTraceHudOverlay`, and `CompactFieldMap`. All four subsequently existed
and matched the verified development tree's bytecode hashes. No corresponding
source edits occurred. This interrupted run is not accepted as verification.

Delivery requires a fresh full run and guards, with compiler-output timestamps
checked for further interference. Its results are reported in the final delivery
message; the unsuccessful run and exact testcase failures remain under
`target/slop-cleanup-evidence/merged*`.
