package com.openggf.tests;

import com.openggf.GameLoop;
import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameResult;
import com.openggf.LevelFrameStep;
import com.openggf.LevelFrameTestStep;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.InitStep;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.LevelAssemblyKind;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.LevelLoadMode;
import com.openggf.game.OscillationManager;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageManager;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceCharacterState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestS3kInitialObjectSetupLifecycle {

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void firstAdmittedInvocationReturnsSetupOnlyAndTheRetryRunsGameplayFrame()
            throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            AbstractPlayableSprite p1 = GameServices.sprites().getMainPlayable();
            AbstractPlayableSprite p2 = GameServices.sprites().getSidekicks().getFirst();
            int objectBefore = manager.getObjectManager().getFrameCounter();
            int levelBefore = manager.getFrameCounter();
            int p2ControlBefore = nativeObjectControl(p2);

            LevelFrameResult setup = LevelFrameTestStep.executeWithPause(
                    LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                    manager, GameServices.camera(), () -> {
                        throw new AssertionError("SETUP_ONLY must not enter ordinary physics");
                    }, false, LevelFrameStep.DIRECT_WRAPPER);

            assertEquals(LevelFrameResult.SETUP_ONLY, setup);
            assertEquals(objectBefore + 1, manager.getObjectManager().getFrameCounter());
            assertEquals(levelBefore, manager.getFrameCounter());
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            // Obj_AIZPlaneIntro publishes Player_1 object_control=$53 from its
            // dynamic setup slot; it does not change P2 (sonic3k.asm:26101-26156).
            assertEquals(0x53, nativeObjectControl(p1));
            assertEquals(p2ControlBefore, nativeObjectControl(p2));

            LevelFrameResult gameplay = LevelFrameTestStep.executeWithPause(
                    LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                    manager, GameServices.camera(), () -> {
                    }, false, LevelFrameStep.DIRECT_WRAPPER);

            assertEquals(LevelFrameResult.GAMEPLAY_FRAME, gameplay);
            assertEquals(objectBefore + 2, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    private static int nativeObjectControl(AbstractPlayableSprite player) {
        int value = 0;
        if (player.isObjectControlled()) value |= 0x01;
        if (player.isObjectMappingFrameControl()) value |= 0x02;
        if (player.isControlLocked()) value |= 0x10;
        if (player.isHidden()) value |= 0x40;
        return value;
    }

    @Test
    void pausedAdmissionReturnsPausedWithoutConsumingSetupAuthority() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int objectBefore = manager.getObjectManager().getFrameCounter();

            LevelFrameResult result = LevelFrameTestStep.executeWithPause(
                    LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                    manager, GameServices.camera(), () -> {
                        throw new AssertionError("PAUSED must not enter ordinary physics");
                    }, true, LevelFrameStep.DIRECT_WRAPPER);

            assertEquals(LevelFrameResult.PAUSED, result);
            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(objectBefore, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void contextAuthorityMatrixAllowsAndPublishesOnlyFreshFullPostLoadAssembly() throws Exception {
        LevelManager manager = managerWithProfile(requestingProfile());
        List<AuthorityCase> cases = List.of(
                new AuthorityCase(true, LevelLoadMode.FULL, true,
                        LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY),
                new AuthorityCase(false, LevelLoadMode.PREVIEW_CAPTURE, true,
                        LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY),
                new AuthorityCase(false, LevelLoadMode.FULL, false,
                        LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY),
                new AuthorityCase(false, LevelLoadMode.FULL, true,
                        LevelAssemblyKind.DECODE_ONLY),
                new AuthorityCase(false, LevelLoadMode.FULL, true,
                        LevelAssemblyKind.STATE_RESTORATION));

        for (AuthorityCase authorityCase : cases) {
            LevelLoadContext ctx = context(
                    authorityCase.mode(), authorityCase.postLoad(), authorityCase.assemblyKind());
            manager.loadLevel(0, authorityCase.mode(), ctx);
            assertTrue(manager.hasPendingInitialProcessSpritesPass() == authorityCase.expected(),
                    authorityCase.toString());
            assertTrue((ctx.requestedInitialProcessSpritesLifecycle()
                            == InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE)
                            == authorityCase.expected(),
                    authorityCase.toString());
        }
    }

    @Test
    void successfulLoadPublishesOnlyAfterRequestedProfileCompletes() throws Exception {
        LevelLoadContext ctx = freshContext();
        LevelManager manager = managerWithProfile(requestingProfile());

        assertFalse(manager.hasPendingInitialProcessSpritesPass());
        manager.loadLevel(0, LevelLoadMode.FULL, ctx);

        assertTrue(manager.hasPendingInitialProcessSpritesPass());
    }

    @Test
    void reusedContextCannotRepublishPriorFreshLoadAuthorityAndCanRetryFresh() throws Exception {
        LevelManager manager = managerWithProfile(requestingProfile());
        LevelLoadContext reused = freshContext();
        manager.loadLevel(0, LevelLoadMode.FULL, reused);
        assertTrue(manager.hasPendingInitialProcessSpritesPass());

        List<AuthorityCase> deniedReuseCases = List.of(
                new AuthorityCase(false, LevelLoadMode.FULL, true,
                        LevelAssemblyKind.STATE_RESTORATION),
                new AuthorityCase(false, LevelLoadMode.FULL, true,
                        LevelAssemblyKind.DECODE_ONLY),
                new AuthorityCase(false, LevelLoadMode.PREVIEW_CAPTURE, true,
                        LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY),
                new AuthorityCase(false, LevelLoadMode.FULL, false,
                        LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY));

        for (AuthorityCase denied : deniedReuseCases) {
            reused.setIncludePostLoadAssembly(denied.postLoad());
            reused.setAssemblyKind(denied.assemblyKind());
            manager.loadLevel(0, denied.mode(), reused);

            assertFalse(manager.hasPendingInitialProcessSpritesPass(), denied.toString());
            assertEquals(InitialProcessSpritesLifecycle.NONE,
                    reused.requestedInitialProcessSpritesLifecycle(), denied.toString());

            reused.setIncludePostLoadAssembly(true);
            reused.setAssemblyKind(LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY);
            manager.loadLevel(0, LevelLoadMode.FULL, reused);
            assertTrue(manager.hasPendingInitialProcessSpritesPass(),
                    "a fresh authorized retry must publish after " + denied);
        }
    }

    @Test
    void genuineProductionLoadCurrentLevelPublishesRequest() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            assertTrue(GameServices.level().hasPendingInitialProcessSpritesPass());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void productionConsumerExecutesPendingSetupExactlyOnce() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int before = manager.getObjectManager().getFrameCounter();

            assertTrue(manager.consumePendingInitialProcessSpritesPass());
            assertEquals(before + 1, manager.getObjectManager().getFrameCounter());
            assertFalse(manager.hasPendingInitialProcessSpritesPass());

            assertFalse(manager.consumePendingInitialProcessSpritesPass());
            assertEquals(before + 1, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void rewindLifecycleDistinguishesBeforeAndAfterInitialSetupConsumption() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();

            InitialProcessSpritesLifecycle before =
                    manager.capturePendingInitialProcessSpritesLifecycleForRewind();
            assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE, before);

            assertTrue(manager.consumePendingInitialProcessSpritesPass());
            InitialProcessSpritesLifecycle after =
                    manager.capturePendingInitialProcessSpritesLifecycleForRewind();
            assertEquals(InitialProcessSpritesLifecycle.NONE, after);

            manager.restorePendingInitialProcessSpritesLifecycleForRewind(before);
            assertTrue(manager.hasPendingInitialProcessSpritesPass(),
                    "restoring a pre-consume rewind snapshot restores setup authority");
            manager.restorePendingInitialProcessSpritesLifecycleForRewind(after);
            assertFalse(manager.hasPendingInitialProcessSpritesPass(),
                    "restoring a post-consume rewind snapshot keeps setup authority consumed");
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void nonLevelTitleCardReleaseRetainsFreshSetupAuthority() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int before = manager.getObjectManager().getFrameCounter();
            GameLoop loop = releasingTitleCardLoop("BONUS_STAGE");

            assertTrue(releaseTitleCardInLogicalIteration(loop));

            assertEquals(GameMode.BONUS_STAGE, loop.getCurrentGameMode());
            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void specialStageEntryPausedTickAndResultsRetainPendingSetup() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int before = manager.getObjectManager().getFrameCounter();
            GameLoop loop = new GameLoop(new InputHandler());
            loop.setGameplayMode(TestEnvironment.activeGameplayMode());
            SpecialStageProvider provider = GameServices.module().getSpecialStageProvider();

            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter());

            invoke(loop, "doEnterSpecialStage",
                    new Class<?>[] { SpecialStageProvider.class, int.class, boolean.class },
                    provider, 0, false);
            Sonic3kSpecialStageManager specialStage =
                    ((Sonic3kSpecialStageProvider) provider).getManager();
            Sonic3kSpecialStageComparisonState specialStateBefore =
                    specialStage.captureComparisonState();
            loop.pause();
            assertTrue(loop.isPaused());
            loop.step();

            assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode());
            assertEquals(specialStateBefore, specialStage.captureComparisonState(),
                    "the loop-owned pause gate must skip the special-stage provider tick");
            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter(),
                    "a paused special-stage tick must not dispatch level objects");
            loop.resume();
            assertFalse(loop.isPaused());
            advanceActiveFade(loop);

            invoke(loop, "doEnterResultsScreen", new Class<?>[0]);
            loop.step();

            assertEquals(GameMode.SPECIAL_STAGE_RESULTS, loop.getCurrentGameMode());
            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter(),
                    "results ticks must not consume or dispatch fresh-level setup");
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void genuineSpecialStageReturnReloadArmsAndLevelReleaseConsumesExactlyOnce()
            throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            assertTrue(manager.consumePendingInitialProcessSpritesPass());
            GameLoop loop = new GameLoop(new InputHandler());
            loop.setGameplayMode(TestEnvironment.activeGameplayMode());
            setField(loop, "currentGameMode", GameMode.SPECIAL_STAGE_RESULTS);

            invoke(loop, "doExitResultsScreen", new Class<?>[0]);

            assertEquals(GameMode.TITLE_CARD, loop.getCurrentGameMode());
            assertTrue(manager.hasPendingInitialProcessSpritesPass(),
                    "the genuine return load publishes one fresh setup token");
            int beforeRelease = manager.getObjectManager().getFrameCounter();
            installReleasingTitleProvider(loop);
            assertTrue(releaseTitleCardInLogicalIteration(loop));

            assertEquals(GameMode.LEVEL, loop.getCurrentGameMode());
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(beforeRelease + 1, manager.getObjectManager().getFrameCounter());
            assertFalse(manager.consumePendingInitialProcessSpritesPass());
            assertEquals(beforeRelease + 1, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void representedSpecialStageReturnRestorationDiscardsBeforeLevelRelease()
            throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            assertTrue(manager.consumePendingInitialProcessSpritesPass());
            GameLoop loop = new GameLoop(new InputHandler());
            loop.setGameplayMode(TestEnvironment.activeGameplayMode());
            setField(loop, "currentGameMode", GameMode.SPECIAL_STAGE_RESULTS);
            invoke(loop, "doExitResultsScreen", new Class<?>[0]);
            assertTrue(manager.hasPendingInitialProcessSpritesPass());

            manager.discardPendingInitialProcessSpritesForStateRestoration();
            int beforeRelease = manager.getObjectManager().getFrameCounter();
            installReleasingTitleProvider(loop);
            assertTrue(releaseTitleCardInLogicalIteration(loop));

            assertEquals(GameMode.LEVEL, loop.getCurrentGameMode());
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(beforeRelease, manager.getObjectManager().getFrameCounter(),
                    "represented return state must not replay the native setup dispatch");
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void pausedFirstOrdinaryFrameRetainsSetupUntilGameplayResumes() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int before = manager.getObjectManager().getFrameCounter();

            LevelFrameResult paused = LevelFrameTestStep.executeWithPause(
                    LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                    manager, GameServices.camera(), () -> {
                    }, true, LevelFrameStep.DIRECT_WRAPPER);

            assertEquals(LevelFrameResult.PAUSED, paused);
            assertTrue(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter());

            LevelFrameResult resumed = LevelFrameTestStep.executeWithPause(
                    LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                    manager, GameServices.camera(), () -> {
                    }, true, LevelFrameStep.DIRECT_WRAPPER);

            assertEquals(LevelFrameResult.SETUP_ONLY, resumed);
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(before + 1, manager.getObjectManager().getFrameCounter(),
                    "resume runs setup only; the caller retries for the ordinary frame");
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void representedStateRestorationDiscardsWithoutExecutingFreshSetup() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            int before = manager.getObjectManager().getFrameCounter();

            manager.discardPendingInitialProcessSpritesForStateRestoration();

            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertFalse(manager.consumePendingInitialProcessSpritesPass());
            assertEquals(before, manager.getObjectManager().getFrameCounter());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void setupExceptionConsumesTokenAndLeavesSolidRegistryBalanced() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            ObjectInstance throwing = mock(ObjectInstance.class);
            doThrow(new IllegalStateException("setup boom"))
                    .when(throwing).update(org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());
            manager.getObjectManager().addDynamicObject(throwing);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    manager::consumePendingInitialProcessSpritesPass);

            assertEquals("setup boom", failure.getMessage());
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertFalse(manager.consumePendingInitialProcessSpritesPass());
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void visibleTitleReleaseAndNoTitleFirstFrameConvergeForFreshCnz() throws Exception {
        ConvergenceState visible = loadCnzThroughVisibleTitleRelease();
        TestEnvironment.resetAll();
        ConvergenceState noTitle = loadCnzThroughNoTitleFirstFrame();

        assertEquals(visible, noTitle);
        assertTrue(visible.runtime().objects().stream()
                        .anyMatch(row -> row.className().contains("CnzBalloon")),
                "the initial CNZ spawn window must include a balloon initialized by the setup pass");
    }

    @Test
    void requestThenThrowClearsOldTokenPreservesCauseAndRetryCanPublish() throws Exception {
        LevelManager manager = managerWithProfile(requestingProfile());
        manager.loadLevel(0, LevelLoadMode.FULL, freshContext());
        assertTrue(manager.hasPendingInitialProcessSpritesPass());

        IllegalStateException startupFailure = new IllegalStateException("synthetic startup failure");
        installProfile(requestingProfileThenThrow(startupFailure));
        IOException wrapped = assertThrows(IOException.class,
                () -> manager.loadLevel(0, LevelLoadMode.FULL, freshContext()));

        assertSame(startupFailure, wrapped.getCause());
        assertFalse(manager.hasPendingInitialProcessSpritesPass(),
                "entry must clear the old token and failure must not publish the request");

        installProfile(requestingProfile());
        manager.loadLevel(0, LevelLoadMode.FULL, freshContext());
        assertTrue(manager.hasPendingInitialProcessSpritesPass(),
                "a genuine successful retry must publish a fresh request");
    }

    @Test
    void sharedReuseAndProductionSeamlessTransitionCannotArmButFreshReloadCan() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            LevelManager manager = GameServices.level();
            LevelLoadContext restoration = context(
                    LevelLoadMode.FULL, true, LevelAssemblyKind.STATE_RESTORATION);
            manager.loadLevel(0, LevelLoadMode.FULL, restoration);
            assertFalse(manager.hasPendingInitialProcessSpritesPass());

            var reusedLevel = manager.getCurrentLevel();
            HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
            assertSame(reusedLevel, manager.getCurrentLevel(),
                    "the SharedLevel fixture must reuse the live level without another load");
            assertFalse(manager.hasPendingInitialProcessSpritesPass());

            manager.executeActTransition(SeamlessLevelTransitionRequest
                    .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_SAME_LEVEL)
                    .targetZoneAct(0, 0)
                    .preserveMusic(true)
                    .build());
            assertFalse(manager.hasPendingInitialProcessSpritesPass(),
                    "the production in-place transition bypasses fresh-load authority");

            manager.loadCurrentLevel();
            assertTrue(manager.hasPendingInitialProcessSpritesPass(),
                    "the next genuine playable-runtime assembly publishes one request");
        } finally {
            sharedLevel.dispose();
        }
    }

    @Test
    void teardownClearsPendingLifecycle() throws Exception {
        LevelManager manager = managerWithProfile(requestingProfile());
        manager.loadLevel(0, LevelLoadMode.FULL, freshContext());
        assertTrue(manager.hasPendingInitialProcessSpritesPass());

        manager.resetState();

        assertFalse(manager.hasPendingInitialProcessSpritesPass());
    }

    private static LevelLoadContext context(LevelLoadMode mode,
                                            boolean postLoad, LevelAssemblyKind assemblyKind) {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLoadMode(mode);
        ctx.setIncludePostLoadAssembly(postLoad);
        ctx.setAssemblyKind(assemblyKind);
        return ctx;
    }

    private static LevelLoadContext freshContext() {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        ctx.setAssemblyKind(LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY);
        return ctx;
    }

    private static LevelInitProfile requestingProfile() {
        return profileWithFactory(ctx -> List.of(new InitStep(
                "RequestInitialProcessSprites", "test",
                () -> ctx.requestInitialProcessSpritesFromProfile(
                        InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE))));
    }

    private static LevelInitProfile requestingProfileThenThrow(RuntimeException failure) {
        return profileWithFactory(ctx -> List.of(
                new InitStep("RequestInitialProcessSprites", "test",
                        () -> ctx.requestInitialProcessSpritesFromProfile(
                                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE)),
                new InitStep("ThrowAfterRequest", "test", () -> {
                    throw failure;
                })));
    }

    private static LevelInitProfile profileWithFactory(
            java.util.function.Function<LevelLoadContext, List<InitStep>> factory) {
        return new LevelInitProfile() {
            @Override
            public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
                return factory.apply(ctx);
            }

            @Override public List<InitStep> levelTeardownSteps() { return List.of(); }
            @Override public List<InitStep> perTestResetSteps() { return List.of(); }
            @Override public List<com.openggf.game.StaticFixup> postTeardownFixups() { return List.of(); }
        };
    }

    private static LevelManager managerWithProfile(LevelInitProfile profile) {
        Sonic3kGameModule real = new Sonic3kGameModule();
        GameModule module = mock(GameModule.class, delegatesTo(real));
        when(module.getLevelInitProfile()).thenReturn(profile);
        TestEnvironment.configureGameModuleFixture(module);
        return TestEnvironment.activeGameplayMode().getLevelManager();
    }

    private static void installProfile(LevelInitProfile profile) {
        when(GameModuleRegistry.getCurrent().getLevelInitProfile()).thenReturn(profile);
    }

    private static ConvergenceState loadCnzThroughVisibleTitleRelease() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 3, 0);
        try {
            LevelManager manager = GameServices.level();
            OscillatorState before = captureOscillator();
            GameLoop loop = releasingTitleCardLoop("LEVEL");
            assertTrue(releaseTitleCardInLogicalIteration(loop));
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            assertEquals(LevelFrameResult.GAMEPLAY_FRAME,
                    LevelFrameTestStep.execute(
                            LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                            manager, GameServices.camera(), () -> {
                            }));
            return new ConvergenceState(captureRuntimeState(manager),
                    before, captureOscillator());
        } finally {
            sharedLevel.dispose();
        }
    }

    private static ConvergenceState loadCnzThroughNoTitleFirstFrame() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 3, 0);
        try {
            LevelManager manager = GameServices.level();
            OscillatorState before = captureOscillator();
            assertEquals(LevelFrameResult.SETUP_ONLY,
                    LevelFrameTestStep.execute(
                            LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                            manager, GameServices.camera(), () -> {
                            }));
            assertEquals(LevelFrameResult.GAMEPLAY_FRAME,
                    LevelFrameTestStep.execute(
                            LevelFrameContext.from(TestEnvironment.activeGameplayMode()),
                            manager, GameServices.camera(), () -> {
                            }));
            assertFalse(manager.hasPendingInitialProcessSpritesPass());
            return new ConvergenceState(captureRuntimeState(manager),
                    before, captureOscillator());
        } finally {
            sharedLevel.dispose();
        }
    }

    private static OscillatorState captureOscillator() {
        return new OscillatorState(
                Arrays.stream(OscillationManager.valuesForTest()).boxed().toList(),
                Arrays.stream(OscillationManager.deltasForTest()).boxed().toList(),
                OscillationManager.controlForTest());
    }

    private static GameLoop releasingTitleCardLoop(String destination) throws Exception {
        GameLoop loop = new GameLoop(new InputHandler());
        loop.setGameplayMode(TestEnvironment.activeGameplayMode());
        installReleasingTitleProvider(loop);
        setField(loop, "currentGameMode", GameMode.TITLE_CARD);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object target = Enum.valueOf(
                (Class<? extends Enum>) Class.forName("com.openggf.PostTitleCardDestination"),
                destination);
        setField(loop, "postTitleCardDestination", target);
        return loop;
    }

    private static boolean releaseTitleCardInLogicalIteration(GameLoop loop) throws Exception {
        var frame = TestEnvironment.activeGameplayMode().plcFrameLifecycle()
                .latchBeforeFadeUpdate();
        setField(loop, "activePlcLifecycleFrame", frame);
        try {
            return (boolean) invoke(loop, "updateTitleCardMode",
                    new Class<?>[] { boolean.class }, false);
        } finally {
            setField(loop, "activePlcLifecycleFrame", null);
            frame.finish();
        }
    }

    private static void advanceActiveFade(GameLoop loop) {
        for (int frame = 0; frame < 64 && GameServices.fade().isActive(); frame++) {
            loop.step();
        }
        assertFalse(GameServices.fade().isActive(), "the entry reveal must complete first");
    }

    private static void installReleasingTitleProvider(GameLoop loop) throws Exception {
        TitleCardProvider provider = mock(TitleCardProvider.class);
        when(provider.shouldReleaseControl()).thenReturn(true);
        setField(loop, "titleCardProvider", provider);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types,
                                 Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static RuntimeState captureRuntimeState(LevelManager manager) {
        List<ObjectRow> objects = new ArrayList<>();
        for (ObjectInstance object : manager.getObjectManager().getActiveObjects()) {
            int slot = object instanceof AbstractObjectInstance instance
                    ? instance.getSlotIndex() : -1;
            objects.add(new ObjectRow(slot, object.getClass().getName(),
                    object.getX(), object.getY()));
        }
        objects.sort(Comparator.comparingInt(ObjectRow::slot)
                .thenComparing(ObjectRow::className));
        AbstractPlayableSprite player = GameServices.sprites().getMainPlayable();
        List<PlayerState> sidekicks = GameServices.sprites().getSidekicks().stream()
                .map(TestS3kInitialObjectSetupLifecycle::capturePlayer)
                .toList();
        return new RuntimeState(List.copyOf(objects), objects.size(),
                manager.getObjectManager().getFrameCounter(),
                manager.getObjectManager().getVblaCounter(), manager.getFrameCounter(),
                GameServices.camera().getX(), GameServices.camera().getY(),
                GameServices.rng().getSeed(), capturePlayer(player), sidekicks);
    }

    private static PlayerState capturePlayer(AbstractPlayableSprite player) {
        return new PlayerState(player.getCentreX(), player.getCentreY(),
                player.getXSpeed(), player.getYSpeed(), player.getGSpeed(),
                TraceCharacterState.statusByteFromSprite(player),
                player.getAnimationId(), player.getMappingFrame());
    }

    private record ObjectRow(int slot, String className, int x, int y) {
    }

    private record PlayerState(int centreX, int centreY, int xSpeed, int ySpeed,
                               int groundSpeed, int status, int animation, int mapping) {
    }

    private record RuntimeState(List<ObjectRow> objects, int activeSlotCount,
                                int objectFrame, int vbla, int levelFrame,
                                int cameraX, int cameraY, long rngSeed,
                                PlayerState player, List<PlayerState> sidekicks) {
    }

    private record OscillatorState(List<Integer> values, List<Integer> deltas,
                                   int control) {
    }

    private record ConvergenceState(RuntimeState runtime, OscillatorState before,
                                    OscillatorState after) {
    }

    private record AuthorityCase(boolean expected, LevelLoadMode mode, boolean postLoad,
                                 LevelAssemblyKind assemblyKind) {
    }
}
