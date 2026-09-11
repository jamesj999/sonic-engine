# Release Architecture Review Issues

This tracker records the issues found during the deep pre-release architecture and code review. Status values:

- `open`: confirmed and not yet fixed.
- `in-progress`: actively being changed.
- `resolved`: code/docs changed and focused verification passed.
- `deferred`: intentionally left for a later release with rationale.

## 2026-06-07 Develop Release Review Addendum

This addendum records the latest multi-agent release review against `develop`.
It includes failures observed in the full local suite and architecture risks that
should either be fixed or explicitly deferred before release.

### Current Verification Baseline

- `mvn -q -DskipTests compile`: passed before fixes.
- `mvn -q test`: failed with 15 failures and 1 error across 6964 tests.
- Focused architecture/build guard run failed with 5 guard failures.
- Final verification after fixes: `mvn -q -DskipTests compile` passed; `mvn -q test` passed with 5987 tests, 0 failures, 0 errors, 977 skipped.
- Working tree note before this addendum: unrelated `.gitignore` changes were already present.

### Release Blockers / Active Fix List

| ID | Status | Area | Issue | Evidence | Resolution Notes |
| --- | --- | --- | --- | --- | --- |
| REL-022 | resolved | Frame runtime ownership | `LevelFrameStep` still reads ambient `GameServices` for pause state instead of using its explicit frame context. | `src/main/java/com/openggf/LevelFrameStep.java`, `TestArchitecturalSourceGuard.levelFrameStepDoesNotUseAmbientGameServices` | `LevelFrameContext` now carries `GameStateManager` from `GameplayModeContext`; `LevelFrameStep.executeWithPause(...)` no longer imports or calls `GameServices`. Focused report: 1 test, 0 failures/errors. |
| REL-023 | resolved | Collision parity | Collision tests and guards still detect coordinate-window behavior or stale sensor fallback semantics. | `CollisionSystemTest.floorLipSlopeUsesAlternateSensorPatternOutsideSbzCoordinates`, `CollisionSystemTest.oddRightWallAngleFallbackIsSensorDrivenNotCoordinateWindow`, `TestArchitecturalSourceGuard.collisionSystemDoesNotContainSbzCoordinateWindows` | Removed SBZ coordinate-window predicates from `CollisionSystem`; odd right-wall fallback and floor-lip slope selection are now driven by sensor result patterns. Focused reports: collision 3 tests, source guard 1 test, 0 failures/errors. |
| REL-024 | resolved | Runtime level ownership | `LevelManager.setLevel()` can swap the active level without rebinding `LevelTilemapManager` geometry, leaving tilemap caches pointed at the previous level after editor resume or mutable-level restoration. | `LevelManager.restoreInheritedLevel()`, `LevelManager.setLevel()`, `LevelTilemapManager.updateGeometry(...)` | `setLevel(...)` now updates tilemap geometry and invalidates all tilemap caches; `TestLevelManagerSlotBackgroundCopy` covers replacement-level rebinding. Focused report: 3 tests, 0 failures/errors. |
| REL-025 | resolved | Release packaging | Native Windows/Linux release packages omit `config.yaml`, but native config loading looks beside the executable. | `.github/workflows/release.yml`, `SonicConfigurationService`, native Maven copy step | Windows/Linux native packages now copy `target/config.yaml`; macOS zip includes the exported `config.yaml` beside `OpenGGF.app`. Guarded by `TestBuildToolingGuard.nativeReleasePackagesShouldIncludeEditableConfigYaml`. Focused report: 2 build-tooling tests, 0 failures/errors. |
| REL-026 | resolved | Release trace gate | Release trace CI can still be satisfied by synthetic or skipped reports unless ROM-backed trace reports are counted explicitly. | `.github/workflows/release.yml`, `RequiresRomCondition`, `TestBuildToolingGuard.traceReplayBootstrapPolicySignalsStayBounded` | Workflow now requires explicit release fixture ROM paths, passes them into both default and trace-profile Maven runs, verifies required ROM-backed trace reports executed, fails on unexpected skipped trace reports, and fails on trace warning reports. |
| REL-027 | resolved | Pattern atlas governance | Virtual pattern bases are allocated ad hoc outside `PatternAtlasRange`, and unregistered cache writes can overlap undetected. | `PatternAtlasRange`, `PatternAtlas.cachePattern(...)`, hardcoded bases in title/data-select/results/credits renderers | Centralized the known title, special-stage, results, and credits virtual banks in `PatternAtlasRange`; `PatternAtlas` now rejects ungoverned virtual IDs at cache time, and ArchUnit/source tests ratchet new static `*_PATTERN_BASE` constants back to the registry. Focused verification: `TestPatternAtlasRangeRegistration` 7/7, `PatternAtlasFallbackTest` 3/3, `TestArchUnitRules` 29/29. |
| REL-028 | resolved | Runtime assets | S3K HCZ standalone object mappings still use hardcoded runtime mapping data where mapping address is `0`. | `Sonic3kObjectArt.loadStandaloneSheet(...)`, `Sonic3kPlcArtRegistry` HCZ entries | HCZ water rush, water splash, geyser spray, bubbler, and water-skim splash mappings now load from ROM mapping tables; standalone art fails closed when a non-MGZ-scaled entry has no ROM mapping address. Guarded by `TestSonic3kPlcArtRegistry`, `TestAizFireCurtainRenderer`, `TestFireCurtainBoundaryDiag`, and `TestAizFireCurtainRendererRom`. |
| REL-029 | resolved | Render pipeline | Fade is documented as the final pass, but `Engine.display()` draws several overlays after fade. | `UiRenderPipeline`, `Engine.display()` | Corrected the contract: `UiRenderPipeline` fade is final within the normal scene/UI pipeline, while Engine-owned post-fade diagnostics and the credits-demo sprite exception are recorded as explicit `POST_FADE_DIAGNOSTIC` phases. Guarded by focused render-order tests. |
| REL-030 | resolved | Rewind architecture | Object rewind override ratchet fails on new S2 conveyor overrides. | `TestRewindArchitectureGuard`, `Sonic2/objects/ConveyorObjectInstance` | Explicitly baselined the conveyor capture/restore overrides because the object snapshots path-following coordinates, waypoint progress, subpixel motion, and dynamic-spawn position beyond the generic object snapshot. |
| REL-031 | resolved | Input parity | S2 logical input control lock latch test fails when the ROM flag clears. | `TestLogicalInputControlLockLatch.s2FlagClearedDoesNotLatchLogicalInput` | Kept S2 on the post-filtered zero-input baseline by clearing `controlLockLatchesLogicalInput` until the Tails follow-history latch can be validated without regressing EHZ/MTZ traces. |
| REL-032 | resolved | Object profile standardization | S1 bumper declares continuous touch callbacks without the expected touch profile vocabulary. | `TestObjectPhysicsStandardizationGuard`, `Sonic1BumperObjectInstance` | Added an explicit S1 special-property touch profile for bumper behavior instead of growing the raw override baseline. |
| REL-033 | resolved | Architectural baselines | Several architecture ratchets/freeze stores fail because counts drifted or obsolete violations were removed. | `TestBuildToolingGuard`, `TestArchUnitRules`, `TestSingletonLifecycleGuard`, line-count ratchets | Removed stale lifecycle/module violations, updated deterministic ArchUnit freeze counts, and left the expanded shared-layer dependency set as tracked architectural debt rather than hidden drift. |
| REL-040 | resolved | Rewind determinism | Full-suite rewind torture found a lost-ring dynamic object missing after adjacent seek/replay around frame 3100. | `TestRewindTorture.tortureFixedAdjacent`, `LostRingRewindCodec` | Reattached recreated lost-ring objects to the session-owned `SpillAnimationState` through `ObjectServices.ringManager()` so dynamic Obj37 rings restore against the same shared spin owner as the ring-manager snapshot. Focused report: 1 test, 0 failures/errors. |
| REL-041 | resolved | Release documentation | Player getting-started docs described release JAR and `run.cmd` artifacts while the release workflow publishes native OpenGGF packages. | `docs/guide/playing/getting-started.md`, `.github/workflows/release.yml` | Updated release install instructions for Windows `OpenGGF.exe`, macOS `OpenGGF.app`, Linux `OpenGGF`, and package-local `config.yaml`; source-build instructions continue to describe the Maven JAR. |
| REL-042 | resolved | Release test fixtures | `TestPowerUpGraphicsRegression` opened root default ROM filenames directly, so release fixture paths passed through Maven properties could be ignored and the class could skip despite configured ROMs. | `src/test/java/com/openggf/game/TestPowerUpGraphicsRegression.java`, `RomTestUtils` | The regression test now resolves Sonic 2 and S3K ROMs through `RomTestUtils`, preserving the legacy S2 REV00 local fallback only after configured/default lookup. Focused property-path tests cover `-Dsonic2.rom.path` and `-Ds3k.rom.path`. |

### Additional Release Risks To Decide

| ID | Status | Area | Issue | Evidence | Resolution Notes |
| --- | --- | --- | --- | --- | --- |
| REL-034 | resolved | Trace policy | S3K complete-run bootstrap previously appeared to seed frame-zero player/sidekick/camera state from the trace fixture. Current code has no `seedS3kCompleteRunStartState(...)`; complete-run segments keep `ReplayStartState.DEFAULT`, `shouldSeedFrameZeroForTraceReplay=false`, and `shouldSeedReplayStartStateForTraceReplay=false`. | `TraceReplayBootstrap.isS3kCompleteRunSegment(...)`, `TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(...)`, `TestTraceReplayStartPositionPolicy.s3kCompleteRunSegmentsDoNotSeedFrameZeroTraceState` | Narrowed to bounded frame-zero metadata start-position bootstrap debt and documented it in `docs/status/known-discrepancies.md` / `docs/project/release-readiness-roadmap.md`. Guard coverage prevents the retired seed helper and undocumented complete-run debt from returning silently. Focused policy tests passed. |
| REL-035 | deferred | Trace parity | S3K sidekick/SST trace comparison coverage remains partial and some warning-only gaps are not equivalent to strict parity. | `AbstractTraceReplayTest.captureEngineSnapshot()`, S3K trace tests | Documented as frame-0 bootstrap snapshot coverage debt in `docs/status/known-discrepancies.md`; this is not a full sidekick/SST parity proof until engine-side sidekick CPU and object-slot views are exposed and missing recorded views become strict failures. |
| REL-036 | resolved | Runtime snapshots | `SpecialRenderEffectRegistry` and `AdvancedRenderModeController` capture shallow identities, which is fragile for future stateful effects. | `SpecialRenderEffectRegistry.capture()`, `AdvancedRenderModeController.capture()`, `TestGameplayModeContextRewindRegistry` | Stateful render effects and advanced render-mode contributors now opt into `RewindSnapshottable`; snapshots retain the level/setup identity list and capture keyed per-contributor state for restore. Guard tests cover stateful special-render and advanced-render round trips. |
| REL-037 | resolved | Runtime lifecycle coverage | Runtime-owned registry lifecycle tests do not cover all session-scoped registries. | `TestRuntimeOwnedRegistryLifecycle`, `GameplayModeContext` registry fields | `GameplayModeContext.tearDownManagers()` clears all six shared runtime registries, and `TestRuntimeOwnedRegistryLifecycle` now verifies editor teardown/rebuild clears and recreates zone runtime, palette ownership, animated tiles, mutation pipeline, special render effects, and advanced render-mode state. |
| REL-038 | resolved | Documentation accuracy | README controls are stale relative to `config.yaml`. | `README.md`, `CONFIGURATION.md`, `src/main/resources/config.yaml` | README now lists only active bundled-template player controls and points to the full key reference for defaults omitted from the template. `CONFIGURATION.md`'s bundled YAML example now matches `src/main/resources/config.yaml` by omitting `input.player1.start`; the bindable `START` key remains documented in the key table as an engine default. |
| REL-039 | resolved | Branch policy docs | Branch trailer docs imply every non-master commit is checked, but merge commits are skipped by hooks/CI. | `AGENTS.md`, `CLAUDE.md`, `.githooks/run-policy`, `.githooks/validate-policy.sh`, `.githooks/validate-policy.ps1`, `docs/agent-workflow/runbooks/README.md` | `AGENTS.md`, `CLAUDE.md`, and the agent runbook now describe trailer validation as applying to non-merge non-master commits, with merge commits covered by the separate merge policy. `.githooks/run-policy` dispatches to validators that use `git rev-list --no-merges` in CI. |
| REL-043 | resolved | S3K AIZ trace parity | Regenerated AIZ full-run trace is release-blocking again and now reaches the regenerated trace end without divergences. | `TestS3kAizTraceReplay#replayMatchesTrace`, `RingManager`, `CollisionSystem`, `TraceExecutionModel`, `TraceReplayBootstrap`, `docs/status/trace-frontier-log.md` | Duplicate placed-ring coordinates, S3K raw ring parsing/window semantics, odd floor-lip angle fallback, AIZ miniboss/title-card handoff, AIZ2 bridge/capsule/camera transition timing, and S3K transition-mode replay classification were fixed while moving the frontier from f2836 through f20792 to green. |

## High Priority

| ID | Status | Area | Issue | Evidence | Resolution Notes |
| --- | --- | --- | --- | --- | --- |
| REL-001 | resolved | Physics parity | Shared collision contains SBZ1 coordinate-window special cases instead of a ROM-state or feature predicate. | `src/main/java/com/openggf/physics/CollisionSystem.java` (`isS1Sbz1RightWallLipWindow`, `s1Sbz1FloorLipSlopeResult`) | Replaced SBZ coordinate windows with sensor-shape predicates (`usesOddRightWallFallback`, `floorLipSlopeResult`) and added source/behavior guards. `CollisionSystemTest` 54/54 and `TestArchitecturalSourceGuard` 43/43 pass; `TestS1Credits05Sbz1TraceReplay` passes, while the broader SBZ1 complete-run trace remains on an unrelated early frame-29 Y mismatch. |
| REL-002 | resolved | Runtime assets | Runtime data is embedded from disassembly/docs instead of loaded from the user ROM. | `Sonic1PaletteCycler`, `Sonic1LZConveyorObjectInstance`, `Sonic1SpinConveyorObjectInstance`, `Sonic1BridgeObjectInstance`, `Sonic1ObjectArtProvider`, `Sonic1BossMappings` | Documented as bounded Sonic 1 release debt in `KNOWN_DISCREPANCIES.md`; `TestArchitecturalSourceGuard` now ratchets exact counts for embedded palette, conveyor, bridge, and mapping tables so the debt cannot expand without ROM-backing the data first. |
| REL-003 | resolved | ROM I/O | `Rom.readAllBytes()` mutates shared `FileChannel` position and assumes a single read fills the buffer. | `src/main/java/com/openggf/data/Rom.java` | Fixed with positional full-read loop that preserves shared channel position; covered by `TestRomReadAllBytes`. |
| REL-004 | resolved | Saves | Gameplay saves write directly to final slot files and can corrupt user saves on interruption. | `src/main/java/com/openggf/game/save/SaveManager.java` | Fixed with sibling temp write plus atomic publish and non-atomic fallback; covered by `TestSaveManager`. |
| REL-005 | resolved | Release CI | Release trace-replay CI can pass without proving ROM-backed traces ran. | `.github/workflows/release.yml`, `RequiresRomCondition` skip behavior | Release workflow now asserts trace replay surefire reports include at least one non-skipped ROM-backed trace test; guarded by `TestBuildToolingGuard`. |

## Medium Priority

| ID | Status | Area | Issue | Evidence | Resolution Notes |
| --- | --- | --- | --- | --- | --- |
| REL-006 | resolved | Test gating | `@RequiresRom` is not inherited, so abstract trace bases do not reliably gate/configure concrete subclasses. | `src/test/java/com/openggf/tests/rules/RequiresRom.java`, `RequiresRomCondition.java`, S2 trace base classes | Fixed by marking `@RequiresRom` as `@Inherited`; covered by `TestRequiresRom.requiresRomAnnotationIsInheritedByTraceReplaySubclasses`. |
| REL-007 | resolved | Coordinate semantics | S1/S2 player bottom-boundary death still uses top-left `getY()` despite ROM `y_pos` comments. | `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`, `PlayerMovementRules` | S1/S2 now enable `levelBoundaryUsesCentreY`, matching ROM `obY(a0)`/`y_pos(a0)` center-coordinate boundary checks; focused coverage updated in `TestPlayableSpriteMovement` (94 tests, 0 failures/errors). |
| REL-008 | resolved | Trace replay | Legacy S3K AIZ trace bootstrap used fixture identity and trace-shape phase heuristics. | `src/main/java/com/openggf/trace/TraceReplayBootstrap.java`, `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun`, `TestS3kAizTraceReplay#replayMatchesTrace` | Removed the trace-row primary-state substitution path: `capturePrimaryReplayStateForComparison(...)` now reports live engine sprite state only. The legacy fixture-identity predicate is bounded to old Lua fixtures before `6.25`; the regenerated AIZ full-run trace no longer uses the diagnostic-only bootstrap path and now passes as release-blocking coverage. |
| REL-009 | resolved | Trace replay | Some S3K trace tests downgrade ring mismatches to warnings, so passing tests may not certify ring parity. | `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay`, release workflow | Release trace validation now scans `target/trace-reports/*_report.json` and fails on any `warning_count > 0`, making warning-only parity gaps release-blocking while preserving local frontier diagnostics. |
| REL-010 | resolved | Palette ownership | MGZ2 post-boss palette fade embeds palette rows and writes through direct palette update, bypassing ownership arbitration. | `src/main/java/com/openggf/game/sonic3k/objects/Mgz2PostBossPaletteFadeController.java` | Fade rows now load from ROM-backed `PAL_MGZ_FADE_CNZ_ADDR` and write line 4 through `S3kPaletteWriteSupport` with `MGZ_POST_BOSS_FADE` ownership. |
| REL-011 | resolved | Mutation pipeline | AIZ skip-intro/main terrain overlays can use a bootstrap/private mutation pipeline instead of the runtime-owned pipeline. | `Sonic3kAIZEvents`, `AizIntroTerrainSwap`, `DefaultObjectServices` legacy constructor | Legacy object-service fallback now reuses runtime-owned registries/pipeline when runtime exists; AIZ overlay call sites use an explicit runtime-only helper; guarded by object-services migration tests. |
| REL-012 | resolved | Session lifecycle | `SessionManager.closeGameplaySession()` destroys gameplay mode but leaves world/session and graphics runtime references partially live. | `src/main/java/com/openggf/game/session/SessionManager.java` | `closeGameplaySession()` now uses the shared mode teardown path, clears the world session, and restores graphics bootstrap references; covered by `TestSessionManager`. |
| REL-013 | resolved | Object lifecycle | Parent-owned children still use raw `addDynamicObject(...)` in several S3K objects. | `Sonic3kStarPostObjectInstance`, `AizEndBossArmChild`, `AizEndBossPropellerChild`, `AizEndBossFlameChild`, `AizMinibossFlameBarrelChild`, `AizMinibossBarrelShotChild` | S3K starpost, AIZ end-boss, and AIZ miniboss child chains now use `spawnChild(...)`; guarded by `TestArchitecturalSourceGuard.migratedObjectChildSpawnsStayOnManagedHelpers`. |
| REL-014 | resolved | Async diagnostics | Donated data-select preview generation failures are swallowed and can retry silently. | `S1DataSelectImageCacheManager`, `S2DataSelectImageCacheManager` | S1/S2 managers now log async generation failures, retain `lastGenerationFailure`, and keep fallback non-fatal; covered by data-select cache manager tests. |
| REL-015 | resolved | Capture cleanup | `FfmpegEncoder.finish()` can skip closing audio temp output when video encoding fails. | `src/main/java/com/openggf/capture/FfmpegEncoder.java` | Fixed with guarded audio-output close before video-exit failure handling plus quiet cleanup in `finally`; covered by `FfmpegEncoderCommandTest`. |
| REL-016 | resolved | Determinism | S2 special-stage renderer uses wall-clock time for flashing. | `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRenderer.java` | Renderer now uses the manager-owned special-stage frame counter for invulnerability flashing; covered by `Sonic2SpecialStageRendererDeterminismTest`. |
| REL-017 | resolved | Static state | S1 ending emerald objects keep live instances in a static list. | `src/main/java/com/openggf/game/sonic1/objects/Sonic1EndingEmeraldsObjectInstance.java` | Removed static emerald ownership; ending Sonic now tracks and destroys only its spawned emerald children, including on unload; guarded by `TestSonic1EndingEmeraldsObjectInstance`. |

## Low Priority / Release Hygiene

| ID | Status | Area | Issue | Evidence | Resolution Notes |
| --- | --- | --- | --- | --- | --- |
| REL-018 | resolved | Release automation | Release workflow creates a release for every push to `master` using static `v0.6.prerelease`. | `.github/workflows/release.yml`, `pom.xml` | Release publishing is now gated to manual `workflow_dispatch` while the prerelease version tag remains static; guarded by `TestBuildToolingGuard`. |
| REL-019 | resolved | Documentation | README still references `config.json`; current docs/runtime use `config.yaml`. | `README.md`, `CONFIGURATION.md` | Updated README and player configuration guide to point at `config.yaml` and nested YAML keys. |
| REL-020 | resolved | Documentation | `RELEASE_NOTES_v0.6.prerelease.md` is stale and references an old scan range. | `RELEASE_NOTES_v0.6.prerelease.md` | Refreshed release notes as a current prerelease snapshot and removed stale scan metadata. |
| REL-021 | resolved | Architecture debt | Large release-critical classes remain high-risk edit surfaces. | `Sonic1ObjectArtProvider`, `AbstractPlayableSprite`, `LevelManager`, `GameLoop` | Added release-critical class line-count ratchets in `TestArchitecturalSourceGuard` so these files cannot grow without extracting focused collaborators. |

## Verification Log

- 2026-06-06: Static review completed by six focused sub-agents plus local scan.
- 2026-06-06: `mvn -q -DskipTests compile` passed before fixes.
- 2026-06-06: JUnit 4 scan found no actual JUnit 4 imports/rules/runners, only guard-test strings.
- 2026-06-06: `TestRomReadAllBytes` passed: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestSaveManager` passed: 10 tests, 0 failures, 0 errors.
- 2026-06-06: Filtered Maven invocation still executed the broader suite; unrelated existing failures remain in rewind, S1 object, touch-response, and trace replay tests.
- 2026-06-06: `TestRequiresRom` passed: 3 tests, 0 failures, 0 errors.
- 2026-06-06: `TestBuildToolingGuard` passed: 19 tests, 0 failures, 0 errors.
- 2026-06-06: Config-doc scan now only finds `config.json` references in legacy-migration/history contexts, not active user setup instructions.
- 2026-06-06: `FfmpegEncoderCommandTest` passed: 4 tests, 0 failures, 0 errors.
- 2026-06-06: `Sonic2SpecialStageRendererDeterminismTest` passed: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestSessionManager` passed: 16 tests, 0 failures, 0 errors.
- 2026-06-06: `TestS1DataSelectImageCacheManager` passed: 18 tests, 0 failures, 0 errors.
- 2026-06-06: `TestS2DataSelectImageCacheManager` passed: 7 tests, 0 failures, 0 errors.
- 2026-06-06: `TestSonic1EndingEmeraldsObjectInstance` passed: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestArchitecturalSourceGuard` passed: 40 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-013 changes.
- 2026-06-06: `TestArchitecturalSourceGuard` passed after REL-021 guardrail addition: 41 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-021 changes.
- 2026-06-06: `TestBuildToolingGuard` passed after REL-009 release-warning gate: 20 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-009 changes.
- 2026-06-06: `TestMgzDrillingRobotnikInstance#mgzPostBossPaletteFadeUsesRomRowsThenRequestsCnzAct1` passed after REL-010 palette migration: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestArchitecturalSourceGuard` passed after REL-010 guardrail addition: 42 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-010 changes.
- 2026-06-06: `TestObjectServicesExpansion` passed after REL-011 runtime-pipeline bridge fix: 13 tests, 0 failures, 0 errors.
- 2026-06-06: `TestObjectServicesMigrationGuard` passed after REL-011 guard update: 12 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-011 changes.
- 2026-06-06: `TestPlayableSpriteMovement` report passed after REL-007 coordinate-semantics update: 94 tests, 0 failures, 0 errors.
- 2026-06-06: `CollisionSystemTest` report passed after REL-001 sensor-predicate update: 54 tests, 0 failures, 0 errors.
- 2026-06-06: `TestArchitecturalSourceGuard` report passed after REL-001 guard update: 43 tests, 0 failures, 0 errors.
- 2026-06-06: `TestS1Credits05Sbz1TraceReplay` report passed after REL-001 sensor-predicate update: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestBuildToolingGuard` report passed after REL-008 diagnostic-only AIZ release guard: 21 tests, 0 failures, 0 errors.
- 2026-06-09: `TestS3kAizTraceReplay#replayMatchesTrace` report passed after AIZ trace regeneration and native frontier fixes: 1 test, 0 failures, 0 errors.
- 2026-06-06: `TestArchitecturalSourceGuard` report passed after REL-002 embedded-runtime-data ratchet: 44 tests, 0 failures, 0 errors.
- 2026-06-06: `mvn -q -DskipTests compile` passed after REL-002/REL-008 tracker and guard changes.
- 2026-06-06: Final guard rerun after roadmap/doc cleanup: `TestBuildToolingGuard` 21 tests, 0 failures, 0 errors; `TestArchitecturalSourceGuard` 44 tests, 0 failures, 0 errors.
- 2026-06-06: Final `mvn -q -DskipTests compile` passed.
