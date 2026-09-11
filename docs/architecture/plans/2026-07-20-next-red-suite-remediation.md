# Next Red-Suite Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every genuinely next-only in-scope red remaining after verified develop fixes are merged forward.

**Architecture:** Re-inventory before execution, then repair shared registry/range foundations before their Mod SDK and gameplay consumers. Preserve the published Mod API deliberately, use runtime-owned rewind/service contracts, and leave exact unfinished-S&K exclusions enabled and catalogued.

**Tech Stack:** Java 17, Maven, JUnit 5, Mod API/SDK, PatternAtlas dynamic ranges, GameplayModeContext/RewindRegistry, S3K object/profile systems.

---

## Precondition

- [ ] Merge the verified develop remediation head into an isolated next remediation branch.
- [ ] Run `mvn test` once and replace every “current upper bound” below with the observed remaining identities.
- [ ] Drop a leaf task when all its tests are green after forward integration; never reimplement its shared fix on next.

## N3 foundation: architecture and resource ownership (current upper bound 7)

### Task N3.1: Close process-singleton access in tooling

**Files:**
- Modify: `src/main/java/com/openggf/tools/fbzvisual/HiddenGlCaptureSession.java`
- Modify: `src/main/java/com/openggf/tools/fbzvisual/FbzVisualCadenceCapture.java`
- Modify: `src/main/java/com/openggf/tools/fbzvisual/FbzVisualManifest.java`
- Test: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`

- [ ] Run both failing guard methods; expected diagnostics name direct singleton/raw `getInstance()` use in the three files.
- [ ] Add/retain tool construction tests that pass explicit engine/global dependencies.
- [ ] Inject or route access through `EngineServices` bootstrap ownership; remove direct process-singleton calls without excluding FBZ-named tooling from this cross-cutting guard.
- [ ] Run tool tests and complete singleton-closure guard; expected PASS.
- [ ] Commit as `refactor: inject visual tooling services` with explicit changelog justification.

### Task N3.2: Reconcile S3K mod-zone object-set semantics

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectRegistry.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kModZoneObjectSet.java`

- [ ] Run both failing methods and list every uninventoryed factory/source branch.
- [ ] For each id, verify whether it is S3KL, SKL, or zone-dependent via `getPrimaryName(id, zoneSet)` and the actual registration path.
- [ ] Register explicit set semantics or add a justified inventory row; never copy the observed list wholesale.
- [ ] Run the full class and S3K registry/bootstrap tests; expected PASS.
- [ ] Commit as `fix: declare S3K mod-zone object sets`, updating `CHANGELOG.md` if runtime registration changes.

### Task N3.3: Enforce dynamic PatternAtlas range ownership

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Modify: `src/main/java/com/openggf/mods/code/ModPatternWindowAllocator.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlasDynamicRanges.java`
- Test: `src/test/java/com/openggf/mods/code/TestModPatternWindowAllocator.java`
- Test: `src/test/java/com/openggf/level/TestLevelManagerModPatternWindows.java`

- [ ] Run the three classes; expected reds include a dynamic aligned id accepted outside its owning range and allocator overlap accepted without `IllegalArgumentException`.
- [ ] Add boundary assertions for first/last owned id, one-before/one-after, overlap, clear, and exact re-registration.
- [ ] Make dynamic aligned ranges the authoritative owner for their ids and validate overlap/alignment on registration. `clear()` must release ownership so the same exact window can be registered again.
- [ ] Run all three classes; expected PASS without changing virtual-ID ranges.
- [ ] Commit as `fix: enforce dynamic pattern window ownership`, updating `CHANGELOG.md` and `KNOWN_DISCREPANCIES.md` only if the range table changes.

## N2: rewind and runtime registration (current upper bound 10)

### Task N2.1: Repair gameplay rewind registry keys and lifecycle

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindRegistry.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGameplayInputFilterReplay.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java`
- Test: `src/test/java/com/openggf/mods/code/TestModZoneRuntimeProfile.java`
- Test: `src/test/java/com/openggf/sprites/managers/TestSpriteManagerDebugEmeraldGrant.java`

- [ ] Run the four classes in isolation and batch. Expected failures include null snapshot keys, duplicate null registration, and decorated-stock/custom-zone lifecycle mismatch.
- [ ] Add a lifecycle test covering fresh gameplay, decorated stock events, custom-zone empty contracts, rewind snapshot, teardown, and a second session.
- [ ] Give each registered runtime adapter a stable non-null key, make registration idempotent only for the same owner, and remove it on context teardown/custom replacement. Do not silently accept different owners under one key.
- [ ] Run the fully qualified four-class selector in two separate Maven invocations; expected both PASS with no order dependence.
- [ ] Commit as `fix: stabilize gameplay rewind registration`, updating `CHANGELOG.md`.

### Task N2.2: Remove stale captured policy and preserve exact HCZ policies

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/DefaultObjectRewindPolicies.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestCapturedPolicyCompactReachabilityGuard.java`

- [ ] Run both failing methods; expected stale exact policy for deleted `TensionBridgeObjectInstance#playerAtCollapse` plus HCZ result/boss policy mismatch.
- [ ] Delete or migrate only the stale exact entry and register the current fields through reachable compact paths. Keep exact captured policy for gameplay references.
- [ ] Run the full guard and relevant bridge/HCZ round-trip tests; expected PASS without an allowlist ratchet.
- [ ] Commit as `fix: reconcile captured rewind policies`.

### Task N2.3: Restore the Dragonfly linked-body graph

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/badniks/DragonflyBadnikInstance.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kBadnikChildGraphRewind.java`

- [ ] Add/retain the pre-rewind assertion that Dragonfly constructs all 14 managed segments with registered identities.
- [ ] Run the named methods; expected red before implementation.
- [ ] Route segment creation through managed child spawning and restore parent/previous links by identity.
- [ ] Run the full badnik graph class; expected PASS with exact identity assertions.
- [ ] Commit as `fix: restore Dragonfly rewind graph`, updating `CHANGELOG.md`.

### Task N2.4: Restore the HCZ vortex nested hurtbox graph

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/HczMinibossInstance.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS3kNestedHurtboxGraphRewind.java`

- [ ] Run the full class; expected Vortex bubble lacks/restores the wrong writable fractional X pull state.
- [ ] Add explicit fresh-recreate and pull-state assertions.
- [ ] Capture native subpixel state through the compact schema and restore it into the writable native-position representation.
- [ ] Run the full class; expected PASS.
- [ ] Commit as `fix: restore HCZ vortex rewind graph`, updating `CHANGELOG.md`.

## N1: Mod API and SDK compatibility (current upper bound 20)

### Task N1.1: Annotate the recursive SuperState rewind API

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SuperStateController.java`
- Modify only after compatibility is proven: `src/test/resources/mods/mod-api-signatures-2.4.txt`
- Test: `src/test/java/com/openggf/mods/TestModApiSignatureSurface.java`
- Test: `src/test/java/com/openggf/tools/modsdk/TestModApiJavadocTool.java`

- [ ] Run both classes; expected unaudited `SuperStateController$RewindState` and recursive inventory drift.
- [ ] Add a recursive-surface assertion for the nested type before editing production.
- [ ] Annotate the recursively exposed rewind state with `@ModApi`.
- [ ] Regenerate the 2.4 inventory only for additive annotated types after all compatibility tests pass. Never rewrite the snapshot to bless a removal.
- [ ] Run both classes and SDK Javadoc generation; expected PASS.
- [ ] Commit as `fix: annotate super-state rewind API`, updating SDK docs.

### Task N1.2: Restore CameraSnapshot constructor compatibility

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/CameraSnapshot.java`
- Test: `src/test/java/com/openggf/mods/TestModApiSignatureSurface.java`

- [ ] Run the four signature-surface methods; expected the 2.3→2.4 check reports a breaking CameraSnapshot constructor change.
- [ ] Add a compile-time construction test for the published signature.
- [ ] Restore a delegating compatibility overload without removing the current canonical constructor.
- [ ] Run the signature class; expected PASS without blessing a removal in the snapshot.
- [ ] Commit as `fix: preserve camera snapshot API constructor`, updating Mod SDK docs.

### Task N1.3: Restore PlayerRewindExtra constructor compatibility

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- Test: `src/test/java/com/openggf/sprites/playable/TestPlayableSubclassRewind.java`

- [ ] Run `preservedOldCanonicalConstructorCompilesAndYieldsNullSubclassExtra`; expected compile/reflection failure for the old constructor.
- [ ] Restore the delegating constructor and assert its subclass extra is null.
- [ ] Run the full class; expected PASS.
- [ ] Commit as `fix: preserve playable rewind constructor`, updating Mod SDK docs.

### Task N1.4: Register mod pattern windows before stock art caching

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelPlayableArtInitializer.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Test: `src/test/java/com/openggf/level/TestLevelManagerModPatternWindows.java`

- [ ] Run the test after N3.3; expected any remaining red to show preflight/cache ordering mismatch.
- [ ] Add an order-recorder assertion for normal load and editor rebuild: clear atlas → register mod windows → cache stock art.
- [ ] Move registration to the shared preflight stage consumed by both paths; do not duplicate load logic.
- [ ] Run the class and level-load/editor-resume tests; expected PASS.
- [ ] Commit as `fix: preflight mod pattern windows`, updating `CHANGELOG.md`.

### Task N1.5: Repair shared sample-mod object-art bootstrap

**Files:**
- Modify: `src/main/java/com/openggf/game/ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectRenderManager.java`
- Modify only if load ordering is involved: `src/main/java/com/openggf/level/LevelManager.java`
- Test: `src/test/java/com/openggf/mods/code/TestSampleFlappyIntegration.java`
- Test: `src/test/java/com/openggf/mods/code/TestSampleRomArtRemixIntegration.java`
- Test: `src/test/java/com/openggf/mods/integration/TestPhase2SampleModIntegration.java`

- [ ] Run one failing method from each class with `-Dmse=off` and compare the first causal stack frame. Expected: the same shared `IOException: Failed to load level` owner before any sample-specific assertion.
- [ ] Add a minimal bootstrap test that resolves the custom zone, decoded art, palette, object creator, and empty runtime contracts without a ROM.
- [ ] Provide the supported explicit-empty/custom regular-pattern contract used by ROM-less mods; do not make production loading accept missing mandatory resources.
- [ ] Run all three complete classes; expected all 13 current reds PASS, including rewind/restart determinism.
- [ ] Commit as `fix: restore sample mod level bootstrap`, updating `CHANGELOG.md` and SDK guide if packaging requirements changed.

### Task N1.6: Enter packaged platformer gameplay without a ROM

**Files:**
- Modify: `src/main/java/com/openggf/game/launch/MasterTitleLaunchCoordinator.java`
- Modify only if transition dispatch is wrong: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/mods/integration/TestSamplePlatformerIntegration.java`

- [ ] Run the class; expected mode remains `MASTER_TITLE_SCREEN` instead of `LEVEL`.
- [ ] Add assertions for explicit sample launch intent, resolved custom character/level/audio, and terminal topology.
- [ ] Route the packaged sample through the supported direct-gameplay transition after its resources validate; do not globally skip the title screen.
- [ ] Run the full class and normal startup/title tests; expected PASS.
- [ ] Commit as `fix: launch packaged platformer sample`, updating `CHANGELOG.md`.

## N4: isolated gameplay and rendering (current upper bound 10)

### Task N4.1: Correct the HCZ launcher ROM profile

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/HCZHandLauncherObjectInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestHCZHandLauncherObjectInstance.java`

- [ ] Reproduce launcher extent `18` versus ROM `17` and missing native object-control bit zero.
- [ ] Express the launcher's literal D3 height and native bit-zero control policy through its object/profile owner.
- [ ] Run the full class plus object-control regressions; expected PASS.
- [ ] Commit as `fix: restore HCZ launcher profile`, updating `CHANGELOG.md`.

### Task N4.2: Preserve HCZ vortex fractional pull

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/HczMinibossInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestHczMinibossVortexBubbleMotion.java`

- [ ] Run both failing methods; expected truncated half-pixel/fractional pulls.
- [ ] Add signed edge assertions, accumulate in centre-coordinate native subpixel state, and apply ROM half-pixel vertical steps without integer/top-left truncation.
- [ ] Run the full class and native-position regressions; expected PASS.
- [ ] Commit as `fix: preserve HCZ vortex subpixel pull`, updating `CHANGELOG.md`.

### Task N4.3: Correct Pachinko speed-lock ownership

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/PachinkoFlipperObjectInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestPachinkoFlipperObjectInstance.java`

- [ ] Run the locking/releasing test; expected duplicate `setGSpeed` application.
- [ ] Add a state-transition assertion proving lock entry and release each own exactly one speed write.
- [ ] Move the write to the transition edge and keep steady-state updates idempotent.
- [ ] Run the full class; expected PASS.
- [ ] Commit as `fix: apply Pachinko speed lock on transitions`.

### Task N4.4: Release every MGZ twisting-loop owner correctly

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/MGZTwistingLoopObjectInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kMgzTwistingLoopObject.java`

- [ ] Run the three failing methods; expected omitted/demoted/unloaded owners remain object-controlled.
- [ ] Add an owner-ledger assertion distinguishing current native slot, demoted original owner, and captured extension player.
- [ ] On omit/unload, release the exact captured owner once and clear its slot/support/control without releasing an unrelated replacement.
- [ ] Run the full class and shared object-lifetime/control tests; expected PASS.
- [ ] Commit as `fix: release MGZ twisting-loop owners`, updating `CHANGELOG.md`.

### Task N4.5: Repair MGZ background-rise traversal state

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java`
- Test: `src/test/java/com/openggf/tests/TestS3kMgz2BgRiseHeadless.java`

- [ ] Run the named route test with collision trace enabled and capture the first divergent state at frame 12; do not branch on frame or route.
- [ ] Add a focused unit/integration assertion for the underlying teleport/event/control state.
- [ ] Implement the ROM-driven state transition at its event/object/profile owner.
- [ ] Run the focused assertion and full headless class; expected player survives and event state progresses.
- [ ] Commit as `fix: restore MGZ background-rise traversal state`, updating `CHANGELOG.md` and `docs/status/trace-frontier-log.md` only if a trace frontier moves.

### Task N4.6: Preserve special-stage viewport configuration

**Files:**
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Test: `src/test/java/com/openggf/TestTraceSessionLauncherSpecialStageEntry.java`

- [ ] Run the named method; expected special stage receives WIDE_16_9 instead of NATIVE_4_3.
- [ ] Add assertions for configured native viewport and recorded team on entry, and restoration of the prior aspect on exit.
- [ ] Capture/apply/restore viewport through session configuration ownership rather than a stage-name branch.
- [ ] Run the class and normal trace-session launch tests; expected PASS.
- [ ] Commit as `fix: restore trace special-stage viewport`, updating `CHANGELOG.md`.

## Literal Maven command contract

Each task uses its row for its initial red run and final green regression run. Run commands separately in PowerShell.

| Task | Red command | Green regression command |
|---|---|---|
| N3.1 | `mvn "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#productionCodeDoesNotUseForbiddenProcessSingletonsOutsideEngineServices+productionCodeOnlyUsesRawGetInstanceAtEngineServicesBootstrapBridge" test` | `mvn "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard" test` |
| N3.2 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestSonic3kModZoneObjectSet#everyCurrentRomZoneDependentFactoryIsExplicitlyInventoried+sourceBranchesReadingRomZoneIdCannotSilentlyUseSetOnlyRegistration" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestSonic3kModZoneObjectSet" test` |
| N3.3 | `mvn "-Dtest=com.openggf.graphics.TestPatternAtlasDynamicRanges#dynamicallyRegisteredAlignedRangeGovernsItsPatternIds,com.openggf.mods.code.TestModPatternWindowAllocator#assignmentsDoNotOverlapAndCanBeReRegisteredAfterAtlasClear,com.openggf.level.TestLevelManagerModPatternWindows#everyLoadAndEditorRebuildRegistersModWindowsBeforeStockArtCaching" test` | `mvn "-Dtest=com.openggf.graphics.TestPatternAtlasDynamicRanges,com.openggf.mods.code.TestModPatternWindowAllocator,com.openggf.level.TestLevelManagerModPatternWindows" test` |
| N2.1 | `mvn "-Dtest=com.openggf.game.rewind.TestGameplayInputFilterReplay#rewindSeekResimulatesTheRawRecordedRowThroughTheGameplayFilter,com.openggf.game.session.TestGameplayModeContextRewindRegistry#decoratedStockEventsRegisterRewindStateAndCustomZonesRemoveIt,com.openggf.mods.code.TestModZoneRuntimeProfile#customS3kZoneInstallsExplicitEmptyRuntimeContracts,com.openggf.sprites.managers.TestSpriteManagerDebugEmeraldGrant#giveEmeraldsDebugKeyIsIgnoredWhenDebugViewIsDisabled+giveEmeraldsDebugKeyPlaysEmeraldChimeWhenEmeraldsAreGranted" test` | `mvn "-Dtest=com.openggf.game.rewind.TestGameplayInputFilterReplay,com.openggf.game.session.TestGameplayModeContextRewindRegistry,com.openggf.mods.code.TestModZoneRuntimeProfile,com.openggf.sprites.managers.TestSpriteManagerDebugEmeraldGrant" test` |
| N2.2 | `mvn "-Dtest=com.openggf.game.rewind.schema.TestCapturedPolicyCompactReachabilityGuard#capturedPoliciesNeedingCompactPathAreReachable+hczResultAndBossReferencesUseExactCapturedPolicies" test` | `mvn "-Dtest=com.openggf.game.rewind.schema.TestCapturedPolicyCompactReachabilityGuard" test` |
| N2.3 | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind#dragonflyFollowAnchorFailsForObjectWithoutRegisteredRewindIdentity+dragonflyLinkedBodyGraphRestoresExactParentAndPreviousSegmentByIdentity" test` | `mvn "-Dtest=com.openggf.game.rewind.TestS3kBadnikChildGraphRewind" test` |
| N2.4 | `mvn "-Dtest=com.openggf.game.rewind.TestS3kNestedHurtboxGraphRewind#hczVortexBubbleRestoresFreshAndPreservesPullState" test` | `mvn "-Dtest=com.openggf.game.rewind.TestS3kNestedHurtboxGraphRewind" test` |
| N1.1 | `mvn "-Dtest=com.openggf.mods.TestModApiSignatureSurface#annotatedClasspathInventoryExactlyMatchesRecursiveSurface+publishedTwoFourSurfaceIsPinnedToTheCurrentSurface+recursiveSurfaceIsAnnotatedAndHasNoUnauditedSignatureTypes+twoThreeToTwoFourIsAnAdditiveMinorBump,com.openggf.tools.modsdk.TestModApiJavadocTool#canonicalInventoryIsExactSortedAndContainsTheMandatedRoots" test` | `mvn "-Dtest=com.openggf.mods.TestModApiSignatureSurface,com.openggf.tools.modsdk.TestModApiJavadocTool" test` |
| N1.2 | `mvn "-Dtest=com.openggf.mods.TestModApiSignatureSurface#publishedTwoFourSurfaceIsPinnedToTheCurrentSurface+twoThreeToTwoFourIsAnAdditiveMinorBump" test` | `mvn "-Dtest=com.openggf.mods.TestModApiSignatureSurface" test` |
| N1.3 | `mvn "-Dtest=com.openggf.sprites.playable.TestPlayableSubclassRewind#preservedOldCanonicalConstructorCompilesAndYieldsNullSubclassExtra" test` | `mvn "-Dtest=com.openggf.sprites.playable.TestPlayableSubclassRewind" test` |
| N1.4 | `mvn "-Dtest=com.openggf.level.TestLevelManagerModPatternWindows#everyLoadAndEditorRebuildRegistersModWindowsBeforeStockArtCaching" test` | `mvn "-Dtest=com.openggf.level.TestLevelManagerModPatternWindows" test` |
| N1.5 | `mvn "-Dtest=com.openggf.mods.code.TestSampleFlappyIntegration,com.openggf.mods.code.TestSampleRomArtRemixIntegration,com.openggf.mods.integration.TestPhase2SampleModIntegration" test` | `mvn "-Dtest=com.openggf.mods.code.TestSampleFlappyIntegration,com.openggf.mods.code.TestSampleRomArtRemixIntegration,com.openggf.mods.integration.TestPhase2SampleModIntegration" test` |
| N1.6 | `mvn "-Dtest=com.openggf.mods.integration.TestSamplePlatformerIntegration#realPackagedPlatformerLoadsWithoutRomAndExercisesCharacterLevelAudioAndTerminalTopology" test` | `mvn "-Dtest=com.openggf.mods.integration.TestSamplePlatformerIntegration" test` |
| N4.1 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHCZHandLauncherObjectInstance#grabbedPlayerUsesNativeBitZeroObjectControlPolicy+topSolidUsesLiteralRomD3Height" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHCZHandLauncherObjectInstance" test` |
| N4.2 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHczMinibossVortexBubbleMotion#farEdgeBubblesAccumulateFractionalPullTowardVortex+verticalPullUsesRomHalfPixelSteps" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHczMinibossVortexBubbleMotion" test` |
| N4.3 | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestPachinkoFlipperObjectInstance#lockingAndReleasingTogglesPinballSpeedLock" test` | `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestPachinkoFlipperObjectInstance" test` |
| N4.4 | `mvn "-Dtest=com.openggf.tests.TestS3kMgzTwistingLoopObject#mgzTwistingLoopReleasesOmittedNativeP2AndUnloadStillTargetsDemotedOwner+mgzTwistingLoopUnloadAfterDemotionReleasesOriginalOwnerOnly+mgzTwistingLoopUnloadReleasesCapturedExtensionPlayer" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgzTwistingLoopObject" test` |
| N4.5 | `mvn "-Dtest=com.openggf.tests.TestS3kMgz2BgRiseHeadless#holdRightFromTeleport_playerSurvivesAndStateProgresses" test` | `mvn "-Dtest=com.openggf.tests.TestS3kMgz2BgRiseHeadless" test` |
| N4.6 | `mvn "-Dtest=com.openggf.TestTraceSessionLauncherSpecialStageEntry#specialStageConfigurationUsesNativeViewportAndRecordedTeamThenRestoresAspect" test` | `mvn "-Dtest=com.openggf.TestTraceSessionLauncherSpecialStageEntry" test` |

## Next wave gate

- [ ] Run focused executable class/method selectors for every remaining root after the develop merge; parameterized accounting identities collapse to their Java method selector for focused execution.
- [ ] Run affected Mod SDK, rewind, architecture, registry, and gameplay packages; expected zero failures/errors.
- [ ] Run unfiltered `mvn test` twice and invoke `tools/testing/Compare-SurefireRedSet.ps1` after each run; expected the exact unfinished-S&K exclusion multiset and nothing else.
- [ ] Dispatch whole-change spec and quality reviews and loop until approved.
