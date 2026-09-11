# Visual trace launch hardening implementation plan

Date: 2026-08-02
Design: `docs/architecture/designs/trace/2026-08-02-visual-trace-launch-hardening-design.md`

## Delivery strategy

Implement in the existing isolated worktree
`bugfix/ai-visual-trace-launch-regressions`. Each production change begins
with a focused failing JUnit 5 test. Keep the catalog, parser, lifecycle, and UI
changes separable enough to diagnose independently, then run the combined
launch regressions and full suite before integration.

## Task 1: Legacy complete-run discovery and transitionless adjacency

Files:

- `src/test/java/com/openggf/trace/catalog/TestTraceCatalogRunDiscovery.java`
- `src/test/java/com/openggf/tests/trace/runs/TestTraceRunPlaybackCoordinator.java`
- `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- `src/main/java/com/openggf/trace/catalog/TraceEntry.java`
- `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`

Steps:

1. Add catalog tests that build a temporary catalog containing multiple
   `_completerun` directories sharing one BK2. Prove the scan keeps every
   individual trace, adds one ordered schema-1 run, derives its id from the
   movie, leaves transitions empty, and points segment resolution at the game
   catalog root.
2. Add negative tests for a one-segment cohort, unrelated shared movies,
   unsupported profiles, equal/non-increasing offsets, and any overlapping
   member. The whole invalid cohort must be omitted rather than truncated, and
   per-cohort manifest validation failures must not abort catalog discovery.
3. Implement post-scan grouping using already-loaded `TraceEntry` metadata.
   Require the full cohort to be complete-run-shaped, ordered, non-overlapping,
   and backed by the same resolved movie. Count comparison rows without an
   optional recorder CSV header, while retaining headerless legacy fixtures in
   catalog counting, the ordinary parser, and the stored frame-domain scanner.
   Avoid duplicating a recorder manifest run with the same game/run id.
4. Give run entries a picker row label based on run id rather than their
   segment-root directory name.
5. Add coordinator tests proving a transitionless, identity-matching new level
   load is accepted for `ORDINARY` and `LEVEL_ADVANCE`, but not
   `DEATH_RESTART` or `INTERIOR_RETURN`.
6. Implement that narrow null-boundary load-cause compatibility while leaving
   recorder-published transitions on their existing strict checks.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.trace.catalog.TestTraceCatalogRunDiscovery,com.openggf.tests.trace.runs.TestTraceRunPlaybackCoordinator' test`

## Task 2: Prepared run loading and legacy level-profile compatibility

Files:

- `src/test/java/com/openggf/trace/catalog/TestTraceRunLaunchValidation.java`
- `src/test/java/com/openggf/trace/catalog/TestTraceCatalogRunDiscovery.java`
- `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add tests proving the committed S1 `s1-ghz-maze-roundtrip` run accepts null
   metadata profile for manifest `complete_run` level segments, while explicit
   mismatches and null special/bonus profiles remain invalid.
2. Add a committed-fixture test that discovers the grouped S1 and S3K main
   complete runs with their expected segment counts. Cover both recorder-style
   CSVs with a `frame,` header and legacy headerless CSVs so the catalog proves
   it counts only comparison rows in both formats.
3. Introduce `PreparedRunLaunch`, holding the parsed BK2 and planned segments.
   Make preparation own all BK2-range, profile, and row-count validation.
4. Extend `SegmentPlan` with an optional typed
   `TraceRunSpecialStageRows` payload. Preserve a source-compatible constructor
   for existing tests. Load the typed special-stage rows once during planning,
   retain them, and reuse them during visual segment admission.
5. Prove preparation returns the parsed BK2 and planned payloads together. Use
   counting movie-loader and segment-planner collaborators to assert one call
   to each, and prove destination admission uses the typed special-stage rows
   retained on the segment plan rather than reopening the profile-specific CSV.
6. Make `validateRunLaunch` a diagnostic wrapper around preparation. Make
   `TraceSessionLauncher.launchRun` consume the prepared movie and segment list
   directly rather than validating, planning, and loading the movie in
   separate passes.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.trace.catalog.TestTraceRunLaunchValidation,com.openggf.trace.catalog.TestTraceCatalogRunDiscovery,com.openggf.TestTraceSessionLauncherRunBranch' test`

## Task 3: Cross-profile standalone special stages

Files:

- `src/test/java/com/openggf/trace/catalog/TraceCatalogSpecialStageTest.java`
- `src/test/java/com/openggf/TestSpecialStageVisualTraceSession.java`
- `src/main/java/com/openggf/trace/catalog/TraceEntry.java`
- `src/main/java/com/openggf/trace/replay/runs/TraceRunSpecialStageRows.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add catalog label tests for all three special-stage profiles.
2. Add a launcher/session test that loads the committed Sonic 1 standalone
   special-stage fixture through the typed dispatch path and proves its row
   count, lag admission, terminal row, and stage index are usable without the
   ordinary `TraceFrame` parser.
3. Add a Sonic 2 polymorphic-view regression so the generalized facade is
   committed coverage for the previously supported typed profile, not only
   new S1/S3K paths.
4. Add committed S3K coverage proving typed lag rows suppress gameplay and
   keep their lifecycle admission rather than being treated as unconditional
   gameplay rows.
5. Extend `TraceRunSpecialStageRows` with terminal-row and strict hardware
   schedule access. S1/S3K adapters call `HardwareTimingStreamLoader`; S2 reuses
   its typed loader schedule. An absent timing schema yields the loader's empty
   schedule, while an advertised malformed stream fails.
6. Replace the launcher's S2-specific standalone field and methods with the
   profile-polymorphic view. Recognise S1/S2/S3K profiles in launch dispatch,
   pacing, terminal handling, and timing admission.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.trace.catalog.TraceCatalogSpecialStageTest,com.openggf.TestSpecialStageVisualTraceSession' test`

## Task 4: Schema-2 hardware ordering compatibility

Files:

- `src/test/java/com/openggf/trace/timing/TestHardwareTimingStreamLoader.java`
- `src/main/java/com/openggf/trace/timing/HardwareTimingStreamLoader.java`

Steps:

1. Add a loader test containing the exact legacy same-frame direct-PRE then
   module-POST pair. Assert schema 2 accepts it and returns current canonical
   POST-before-PRE order without changing either edge's data.
2. Add tests proving schema 1, different kinds/boundaries, different raw
   frames, and a normalization that remains noncanonical are rejected.
3. Parse and validate source events as today, normalize only the authorised
   adjacent pair, then validate the entire normalized edge list with
   `CANONICAL_ORDER` before schedule construction.
4. Add the reported committed LBZ segment to committed timing-fixture coverage
   so its stream must parse.
5. Run:

   `mvn -Dmse=off -Dtest='com.openggf.trace.timing.TestHardwareTimingStreamLoader' test`

## Task 5: Single-trace dynamic-art segment ownership

Files:

- `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- `src/test/java/com/openggf/TestTraceSessionLauncherDynamicArtActivation.java` (new if a focused class is clearer)
- `src/main/java/com/openggf/TraceSessionLauncher.java`
- `src/main/java/com/openggf/game/resources/DynamicArtLifecycleService.java`

Steps:

1. Add a success regression with a gameplay dynamic-art lifecycle whose prior
   bootstrap segment published and then closed cleanly. Prove a single-level
   visual launch installs external segment ownership before comparator drive
   and that the first replay-owned iteration publishes generation-local row
   zero atomically.
2. Add a separate failure regression in which the new gameplay context already
   owns an open comparison segment. The lifecycle operation must fail safely
   and must not close, rebase, or discard that live window. Task 6 adds the
   presentation assertion once launch-status state exists.
3. Generalise the existing run-only dynamic-art segment installer so both
   single levels and runs call it in their game-bootstrap callback before
   `TraceReplayDriver.start`.
4. Keep special-stage-only sessions separate. Ensure normal teardown closes
   the controller and abort cleanup closes through the stored owning context,
   even when it is no longer process-global, so it cannot leave external
   segment management armed in a surviving context.
5. Add a buffered-edge abort regression: submit production work after segment
   installation but before row zero, clear the process-global gameplay
   context, and abort. Graceful close must retain its no-invented-row failure;
   expose that condition through a dedicated exception, and abort must catch
   only that type. Recover through a production-owned window-abandon operation
   on the stored context: clear buffered comparison edges and row-publication
   coordinates while preserving mapping decisions, the ledger, pending S1/S2
   transfers and preparations, gap state, and monotonic transfer/edge IDs.
   Prove no duplicate submission occurs, pending work retires at its normal
   VBlank, and automatic segment ownership resumes. A distinct unrelated-close
   failure must remain visible.
6. Retain the comparator's strict atomic-publication assertions; do not add a
   row-zero exception or source expected values into production.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.trace.live.TestLiveTraceComparatorObserver,com.openggf.game.resources.TestDynamicArtLifecycleService' test`

## Task 6: Loading and persistent launch errors

Files:

- `src/test/java/com/openggf/testmode/TestModeTracePickerTest.java`
- `src/test/java/com/openggf/testmode/TestModeTracePickerRunFailureStatus.java`
- `src/test/java/com/openggf/testmode/TestModeTracePickerLaunchStatus.java` (new)
- `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- `src/main/java/com/openggf/testmode/TraceLaunchStatus.java` (new)
- `src/main/java/com/openggf/testmode/TestModeTracePicker.java`
- `src/main/java/com/openggf/game/MasterTitleScreen.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add picker tests proving Enter enters loading without immediately producing
   `LAUNCH`, a render presents `LOADING` plus the selected label, the following
   update emits exactly one launch action, and loading ignores navigation.
2. Add held-launch-failure tests for entry label, wrapped reason text,
   acknowledgement, selection dismissal, coexistence with the existing
   detailed run failure display, and manifest-based identity for synthetic
   legacy runs across picker/loading/error text.
3. Add `TraceLaunchStatus` as process-held presentation-only state. It carries
   entry identity and diagnostic text, never gameplay or comparison input.
4. Record status in synchronous parser/validation catches, bootstrap callback
   aborts, and non-run replay aborts. Preserve `TraceRunFailureStatus` for its
   richer in-run segment diagnostics.
5. Extend Task 5's already-externally-managed segment case through the launcher
   and prove its safe lifecycle failure is rendered as a held launch error.
6. Have the master title retain the picker on a synchronous false result and
   clear its loading latch. A callback failure may recreate the picker after
   teardown; the process-held failure remains available for it.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.testmode.TestModeTracePickerTest,com.openggf.testmode.TestModeTracePickerRunFailureStatus,com.openggf.testmode.TestModeTracePickerLaunchStatus,com.openggf.TestTraceSessionLauncherRunBranch' test`

## Task 7: Documentation and combined verification

Files:

- `CHANGELOG.md`
- `README.md`
- this design and plan
- `docs/status/trace-frontier-log.md` only if replay verification moves or
  regresses a trace frontier

Steps:

1. Add concise changelog and README release-log entries for complete-run
   discovery, cross-profile special stages, launch feedback, and parser/lifecycle
   compatibility.
2. Run the complete focused regression set from Tasks 1-6 in one Maven command.
3. Run `mvn test` on JDK 21 and record exact pass/failure totals. Exercise the
   reported committed Knuckles LBZ timing segment directly and use the shared
   representative run for full preparation assertions; do not retain the
   complete Knuckles decoded payload inside the long-lived 1 GiB suite fork.
4. Run the affected ROM-backed execution paths with the discovered project-root
   ROMs. The execution-time discovery performed for this plan found these
   hash-correct files:

   - S1: `<project-root>/Sonic The Hedgehog (W) (REV01) [!].gen`
   - S2: `<project-root>/Sonic The Hedgehog 2 (W) (REV01) [!].gen`
   - S3K: `<project-root>/Sonic and Knuckles & Sonic 3 (W) [!].gen`

   Re-discover and hash-check at execution if workspace contents change, then
   substitute the discovered paths in:

   `mvn -Ptrace-replay -Dmse=off -Dtest='com.openggf.tests.trace.s1.TestS1Ghz1CompleteRunTraceReplay,com.openggf.tests.trace.s1.TestS1SpecialStageTraceReplay,com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay,com.openggf.tests.trace.s3k.TestS3kAizCompleteRunTraceReplay,com.openggf.tests.trace.s3k.TestS3kSpecialStageTraceReplay,com.openggf.tests.trace.runs.TestS1GhzMazeRoundTripChain' -Dsonic1.rom.path='<discovered S1 REV01 path>' -Dsonic2.rom.path='<discovered S2 REV01 path>' -Ds3k.rom.path='<discovered S3K locked-on path>' test`

   Record exact failures and first trace errors. Because these changes reach
   replay execution, this command is required rather than conditional. Update
   the trace frontier log if a frontier moves, a passing trace regresses, or
   the run selects a new target under the repository policy.
5. Inspect `git diff --check`, `git status`, and all changed files. Request an
   independent code review and resolve every blocking finding.
6. Commit on `bugfix/ai-visual-trace-launch-regressions` with required trailers.
7. Fetch and fast-forward the main-workspace `develop`, record its full-suite
   baseline, rerun the full suite and focused tests in the worktree, merge into
   main-workspace `develop`, and rerun the full regression comparison.
8. Push only `develop`. After successful push, verify the worktree is clean and
   merged, remove it, delete the local worktree branch, and prune worktree
   metadata. Preserve all pre-existing main-workspace user changes.

## Task 8: Deferred callback dynamic-art ownership handoff

Files:

- `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- `src/test/java/com/openggf/game/resources/TestPlcFrameLifecycleCoordinator.java`
- `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`
- this design and plan

Steps:

1. Add a launcher regression that lets ordinary PLC lifecycle ownership open
   and publish a comparison window containing a real pending S2 transfer, then
   installs visual run ownership. Assert installation succeeds, closes the
   automatic generation, opens a fresh generation at unpublished row zero,
   leaves external management armed, and preserves the transfer identity and
   outstanding ledger without duplicate submission. Service its normal VBlank
   and prove the original transfer retires. Run the test before production
   changes and confirm it fails with
   `dynamic-art comparison segment is already open`.
2. Retain a distinct regression in which external management is already armed.
   Installing a second visual owner must fail without closing, abandoning, or
   incrementing that segment's generation.
3. Add a coordinator regression that attempts acquisition while an automatic
   window is open but has not published any row. Even with an empty edge buffer,
   acquisition must fail and preserve the open window, generation, and automatic
   ownership.
4. Add an atomic PLC coordinator operation that acquires external comparison
   ownership. It rejects duplicate external acquisition, requires any open
   automatic window's latest snapshot to be published, marks the coordinator
   externally managed before touching the lifecycle, and closes that completed
   automatic window. If validation or close fails, it restores automatic
   ownership and propagates the original lifecycle failure; it never invokes
   the unpublished-window abandon path.
5. Make `TraceSessionLauncher.installDynamicArtSegments` use the acquisition
   operation before opening its controller window. Existing close and abort
   paths continue to release external management, and installation rollback
   does the same if opening trace row zero fails.
6. Add a launcher regression whose completed automatic window carries a pending
   direct/non-FIFO ROM transfer that makes the fresh trace-window open fail
   after acquisition. Assert the failure is propagated, external ownership is
   released, and no window or transfer is abandoned. Complete that transfer
   through its real production completion path, then prove the next logical
   iteration reopens automatic ownership.
7. Run:

   `mvn -Dmse=off -Dtest='com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.game.resources.TestPlcFrameLifecycleCoordinator,com.openggf.trace.live.TestLiveTraceComparatorObserver,com.openggf.game.resources.TestDynamicArtLifecycleService' test`

8. Run the combined visual-trace focused suite, `git diff --check`, and an
   independent code review. Re-run the full JDK 21 suite against the recorded
   integration baseline before merging and pushing `develop`.
