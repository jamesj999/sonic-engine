package com.openggf.game.sonic2.specialstage;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <h2>Six failures and one error here encode a superseded cadence model.</h2>
 *
 * <p>They encode a superseded model of the pre-start pass cadence. This class was green at
 * {@code 06d570718} and was broken by {@code 649f21886} ("fix(trace): S2 special stage
 * intro pass pipeline and Obj88 startup tick"), which corrected the engine against the
 * recordings but never ran the default profile, so these expectations were left behind.
 * Bisected in 10 steps; a further failure accreted at {@code 8c6a701dc}.
 *
 * <p><b>The recordings outrank this class.</b> The recorder's own {@code run_objects_end}
 * ledger shows the first pre-start iteration overrunning, identically in <b>eight</b>
 * independent special-stage fixtures. So {@code introFirstRecurringPassDeferred} plus the
 * duplicate pre-start pass is a faithful model of recorded behaviour, <i>not</i> a
 * compensator to be removed — an earlier reading of it as a hack was refuted by
 * measurement.
 *
 * <p><b>Three engine "fixes" have been measured and rejected. Do not retry them:</b>
 * <ul>
 *   <li>Removing the duplicate pass (pure deferral, one pass per tick): this class goes
 *       green, but every special-stage trace goes 0 → 1375 errors, first error frame 161
 *       {@code sonic_slide_timer} exp 28 act 29.</li>
 *   <li>Removing the deferral, keeping unconditional immediate execution: traces go
 *       0 → 1 error, frame 159 {@code sonic_ss_y} exp 128 act 110.</li>
 *   <li>Adding a frozen {@code CTRL_DMA_WAIT} observation before {@code Pal_FadeFromWhite}
 *       (s2.asm:6665-6666) and then removing the deferral: all eight classes abort at
 *       {@code frameCounter=225} where HEAD reports 224 — one pre-start pass behind.</li>
 * </ul>
 *
 * <p>Fixing those stale cases means correcting the expectations in this class against the
 * ROM and the recorded ledger, per assertion and with citations — not changing the engine,
 * and not rewriting expected values to whatever the engine currently prints. The separate
 * {@code rewindDuringFadeRestoresTimerAndReplaysTheBoundaryDeterministically} failure is
 * intentionally excluded: it detects that the first-post-fade deferral is not restored and
 * therefore remains a genuine production rewind defect.
 */
class Sonic2SpecialStageBootstrapCadenceTest {

    private static final int STARTUP_WAIT_UPDATES = 10;
    private Rom rom;
    private Sonic2SpecialStageProvider provider;
    private Sonic2SpecialStageManager manager;

    @BeforeEach
    void bootSpecialStage() throws Exception {
        Path romPath = Path.of("s2.gen");
        assumeTrue(Files.isRegularFile(romPath), "s2.gen ROM required for bootstrap cadence tests");

        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        rom = new Rom();
        assertTrue(rom.open(romPath.toAbsolutePath().toString()), "s2.gen should open");
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);
        provider.setLagCompensation(0);
        manager = provider.getManager();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void fadeFromWhiteFreezesRuntimeThenDeferredAndCurrentPreStartPassesPublishTogether() {
        int[] activeObjectUpdates = {0};
        manager.getObjectManager().getActiveObjects().add(new Sonic2SpecialStageRing() {
            @Override
            public void update(
                    int currentTrackFrame,
                    boolean trackFlipped,
                    int speedFactor,
                    boolean drawingIndex4) {
                activeObjectUpdates[0]++;
            }
        });

        advanceThroughStartupRunObjects();

        assertEquals(22, Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES);
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                manager.getIntro().getCurrentPhase());
        assertEquals(1, activeObjectUpdates[0],
                "the startup RunObjects pass executes the later-slot probe exactly once");

        Sonic2SpecialStageComparisonState frozen = manager.captureComparisonState();
        int frozenBannerY = manager.getIntro().getBannerY();
        int frozenSkydomeScroll = manager.getSkydomeScrollXForTest();
        int frozenVScroll = manager.getVScrollBGForTest();
        assertEquals(2, frozen.trackAnimFrame());
        assertEquals(4, frozen.trackFrameDelayCounter(),
                "f136 leaves the ROM duration timer at one, or elapsed four");

        for (int fadeUpdate = 1;
             fadeUpdate <= Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES;
             fadeUpdate++) {
            manager.update();

            Sonic2SpecialStageComparisonState after = manager.captureComparisonState();
            assertEquals(frozen.trackAnimFrame(), after.trackAnimFrame(),
                    "fade must freeze track frame at update " + fadeUpdate);
            assertEquals(frozen.trackFrameDelayCounter(), after.trackFrameDelayCounter(),
                    "fade must freeze track duration at update " + fadeUpdate);
            assertEquals(frozen.drawingIndex(), after.drawingIndex(),
                    "fade must freeze drawing index at update " + fadeUpdate);
            assertEquals(frozen.sonic(), after.sonic(),
                    "fade must freeze initialized Sonic at update " + fadeUpdate);
            assertEquals(frozen.tails(), after.tails(),
                    "fade must freeze initialized Tails at update " + fadeUpdate);
            assertEquals(frozenBannerY, manager.getIntro().getBannerY(),
                    "fade must not start the banner drop at update " + fadeUpdate);
            assertEquals(frozenSkydomeScroll, manager.getSkydomeScrollXForTest(),
                    "fade must freeze skydome scroll at update " + fadeUpdate);
            assertEquals(frozenVScroll, manager.getVScrollBGForTest(),
                    "fade must freeze vertical scroll at update " + fadeUpdate);
            assertEquals(1, activeObjectUpdates[0],
                    "fade must freeze active objects at update " + fadeUpdate);
        }

        assertEquals(Sonic2SpecialStageIntro.Phase.DROP, manager.getIntro().getCurrentPhase(),
                "the final fade wait transitions to DROP for the next logical update");

        manager.update();

        Sonic2SpecialStageComparisonState firstDropVint = manager.captureComparisonState();
        assertEquals(1, firstDropVint.drawingIndex());
        assertEquals(0, firstDropVint.trackFrameDelayCounter());
        assertEquals(2, firstDropVint.trackAnimFrame());
        assertEquals(frozen.sonic(), firstDropVint.sonic(),
                "the f160 VInt observation precedes its recurring RunObjects pass");
        assertEquals(frozen.tails(), firstDropVint.tails());
        assertEquals(frozenBannerY, manager.getIntro().getBannerY(),
                "the f160 VInt observation precedes the banner object pass");
        assertEquals(1, activeObjectUpdates[0],
                "the f160 VInt observation precedes later-slot active objects");

        manager.update();

        Sonic2SpecialStageComparisonState publishedPriorMainPass = manager.captureComparisonState();
        assertEquals(2, publishedPriorMainPass.drawingIndex());
        assertEquals(1, publishedPriorMainPass.trackFrameDelayCounter());
        assertNotEquals(frozen.sonic(), publishedPriorMainPass.sonic(),
                "the next executed update publishes the prior VInt's RunObjects result");
        // The first post-fade iteration overruns and publishes here, then the current
        // pre-start iteration also completes before this observation. SS_MainLoop runs
        // RunObjects after each WaitForVint (s2.asm:6674-6688); the recorder ledger shows
        // only the first iteration deferred in all eight committed special-stage runs.
        assertEquals(frozenBannerY + 2, manager.getIntro().getBannerY());
        assertEquals(3, activeObjectUpdates[0]);

        manager.update(); // drawing index 3
        manager.update(); // drawing index 4
        manager.update(); // drawing index 0: current SSTrack_Draw advances
        Sonic2SpecialStageComparisonState currentDrawWrap = manager.captureComparisonState();
        assertEquals(0, currentDrawWrap.drawingIndex());
        assertEquals(3, currentDrawWrap.trackAnimFrame(),
                "current index-zero SSTrack_Draw is synchronous even while RunObjects is pending");
    }

    @Test
    void drawSkipsPlaceholderUntilFirstTrackFrameIsDecoded() throws Exception {
        TrackRenderRecorder renderer = new TrackRenderRecorder(GraphicsManager.getInstance());
        setManagerField("renderer", renderer);

        assertNull(decodedTrackFrame(), "pre-roll starts before the first track decode");
        manager.draw();
        assertEquals(0, renderer.trackRenderCalls,
                "production draw must not expose the renderer's test placeholder at startup");

        int updatesRemaining = 1_000;
        while (decodedTrackFrame() == null && updatesRemaining-- > 0) {
            manager.update();
        }
        assertNotNull(decodedTrackFrame(), "the first active runtime update should decode Plane A");

        manager.draw();
        assertEquals(1, renderer.trackRenderCalls,
                "Plane A should render normally once decoded track data is available");
    }

    @Test
    void livePacingSkipsUpdatesWhileExternalTracePacingRunsEverySubmittedTick() throws Exception {
        provider.setLagCompensation(0.35);
        boolean observedLiveSkip = false;
        for (int hostFrame = 0; hostFrame < 30 && !observedLiveSkip; hostFrame++) {
            int beforeFrame = manager.captureRewindSnapshot().frameCounter;
            manager.update();
            Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
            assertEquals(beforeFrame + 1, after.frameCounter,
                    "live pacing must keep advancing its host clock");
            observedLiveSkip = after.renderFrameCounter < after.frameCounter;
        }
        assertTrue(observedLiveSkip,
                "ordinary play must retain the S2 special-stage slowdown simulation");

        provider.setLagCompensation(0);
        Sonic2SpecialStageSnapshot externallyPaced = manager.captureRewindSnapshot();
        for (int submittedTick = 0; submittedTick < 30; submittedTick++) {
            int beforeFrame = manager.captureRewindSnapshot().frameCounter;
            manager.update();
            Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
            assertEquals(beforeFrame + 1, after.frameCounter);
            assertEquals(after.frameCounter, after.renderFrameCounter,
                    "externally paced replay must not receive an additional live-model skip");
        }

        provider.setLagCompensation(0.35);
        manager.restoreRewindSnapshot(externallyPaced);
        for (int submittedTick = 0; submittedTick < 30; submittedTick++) {
            manager.update();
            Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
            assertEquals(after.frameCounter, after.renderFrameCounter,
                    "rewind must restore external-pacing authority without live skips");
        }
    }

    @Test
    void ordinaryReinitializationRestoresLivePacingAfterAnExternalTraceSession() throws Exception {
        try {
            provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);
            provider.setLagCompensation(0);

            provider.initializeStage(0);

            boolean observedLiveSkip = false;
            for (int hostFrame = 0; hostFrame < 30 && !observedLiveSkip; hostFrame++) {
                manager.update();
                Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
                observedLiveSkip = after.renderFrameCounter < after.frameCounter;
            }
            assertTrue(observedLiveSkip,
                    "reusing the provider for ordinary play must restore live slowdown");
        } finally {
            // The cadence fixture shares the provider across methods; keep its external-pacing setup isolated.
            provider.setLagCompensation(0);
        }
    }

    @Test
    void jumpPressWhollyInsideFadeDoesNotLeakIntoFirstRecurringMainPass() {
        advanceThroughStartupRunObjects();

        manager.handleInput(0x10, 0x10);
        manager.update();
        manager.handleInput(0, 0);
        for (int update = 1; update < Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES; update++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.DROP, manager.getIntro().getCurrentPhase());

        manager.update();
        manager.update();

        assertEquals(Sonic2SpecialStagePlayer.RoutineState.NORMAL,
                manager.getSonicPlayer().getRoutine(),
                "a fade-only press edge must be discarded before DROP schedules input");
        assertFalse(manager.getSonicPlayer().isJumping());
    }

    @Test
    void currentVintRightInputIsCopiedAfterWaitIntoSameRunObjectsPass() {
        advanceToGameplay();

        manager.handleInput(0x08, 0);
        manager.update(); // VInt reads RIGHT; main copies raw Ctrl_1 after WaitForVint
        assertEquals(0, manager.getSonicPlayer().getInertia());
        assertEquals(0x08, manager.captureRewindSnapshot().pendingMainHeldButtons,
                "the current VInt raw word must feed its following RunObjects pass");

        manager.handleInput(0x08, 0);
        manager.update(); // publishes the RunObjects pass scheduled above

        assertEquals(-0x60, manager.getSonicPlayer().getInertia());
        // loc_33908 records the whole Ctrl_1_Logical WORD into SS_Ctrl_Record_Buf
        // (`move.w (Ctrl_1_Logical).w,-(a1)`, docs/s2disasm/s2.asm:69070), and
        // Ctrl_1_Logical is Ctrl_1_Held_Logical followed by Ctrl_1_Press_Logical
        // (s2.constants.asm:1384-1386), so the recorded word is held<<8|press. This
        // assertion previously expected the held byte alone (0x08), which was never a
        // valid word for a held-and-pressed RIGHT; the buffer became word-shaped when
        // the engine was corrected to match the ROM.
        assertEquals(0x0808, manager.captureRewindSnapshot().tailsCtrlRecordBuf[0]);
    }

    @Test
    void replayInputBindingChangesTheOwningPendingPassNotTheFollowingPass() throws Exception {
        advanceToGameplay();
        assertTrue(manager.captureRewindSnapshot().recurringMainPassPending);
        assertFalse(manager.getSonicPlayer().isJumping());

        provider.handleInput(0x10, 0x10);
        provider.handlePlayer2Input(0, 0);
        provider.bindPendingRecurringPassInput(0x10, 0x10, 0, 0);
        provider.update();

        assertTrue(manager.getSonicPlayer().isJumping(),
                "the first jump edge must affect the completed pass that recorded it");
    }

    @Test
    void terminalPassCompletionExecutesExactObj5fBoundaryWithoutAdvancingVint()
            throws Exception {
        advanceToTerminalPreStartBoundary();
        int[] activeObjectUpdates = {0};
        manager.getObjectManager().getActiveObjects().add(new Sonic2SpecialStageRing() {
            @Override
            public void update(
                    int currentTrackFrame,
                    boolean trackFlipped,
                    int speedFactor,
                    boolean drawingIndex4) {
                activeObjectUpdates[0]++;
            }
        });
        Sonic2SpecialStageSnapshot before = manager.captureRewindSnapshot();
        assertTrue(before.recurringMainPassPending);

        manager.completeTerminalPreStartPassWithoutVint();

        Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
        assertEquals(1, activeObjectUpdates[0], "exactly one pending pass must execute");
        assertEquals(before.frameCounter, after.frameCounter, "VInt frame must not advance");
        assertEquals(before.lastDrawingIndex, after.lastDrawingIndex,
                "VInt draw slice must not advance");
        assertArrayEquals(before.trackAnimator.stageLayout(), after.trackAnimator.stageLayout());
        assertEquals(before.trackAnimator.layoutLength(), after.trackAnimator.layoutLength());
        assertEquals(before.trackAnimator.currentSegmentIndex(),
                after.trackAnimator.currentSegmentIndex());
        assertEquals(before.trackAnimator.currentFrameInSegment(),
                after.trackAnimator.currentFrameInSegment());
        assertEquals(before.trackAnimator.frameDelayCounter(),
                after.trackAnimator.frameDelayCounter());
        assertEquals(before.trackAnimator.playerAnimFrameTimer(),
                after.trackAnimator.playerAnimFrameTimer());
        assertEquals(before.trackAnimator.currentSegmentType(),
                after.trackAnimator.currentSegmentType());
        assertEquals(before.trackAnimator.currentSegmentFlipped(),
                after.trackAnimator.currentSegmentFlipped());
        assertEquals(before.trackAnimator.speedFactor(), after.trackAnimator.speedFactor());
        assertEquals(before.trackAnimator.speedChangePending(),
                after.trackAnimator.speedChangePending());
        assertEquals(before.trackAnimator.stageComplete(), after.trackAnimator.stageComplete());
        assertEquals(before.trackAnimator.orientationFlipped(),
                after.trackAnimator.orientationFlipped());
        assertEquals(before.trackAnimator.lastOrientationFrame(),
                after.trackAnimator.lastOrientationFrame());
        assertEquals(Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT,
                after.intro.currentPhase());
        assertTrue(after.intro.specialStageStarted(),
                "Obj5F's terminal pass must publish SpecialStage_Started");
        assertTrue(after.recurringMainPassPending,
                "completion must leave the following recurring pass slot pending");

        provider.bindPendingRecurringPassInput(0x108, 0x110, 0x204, 0x208);
        Sonic2SpecialStageSnapshot rebound = manager.captureRewindSnapshot();
        assertEquals(0x08, rebound.pendingMainHeldButtons);
        assertEquals(0x10, rebound.pendingMainPressedButtons);
        assertEquals(0x04, rebound.pendingMainP2HeldButtons);
        assertEquals(0x08, rebound.pendingMainP2LogicalButtons);
    }

    @Test
    void terminalPassCompletionRejectsPendingGameplayPassWithoutMutation() {
        advanceToGameplay();
        Sonic2SpecialStageSnapshot before = manager.captureRewindSnapshot();
        assertTrue(before.recurringMainPassPending,
                "wrong-phase proof requires an otherwise valid pending pass");

        assertThrows(IllegalStateException.class,
                manager::completeTerminalPreStartPassWithoutVint);

        assertTerminalBoundaryStateUnchanged(before, manager.captureRewindSnapshot());
    }

    @Test
    void terminalPassCompletionIsOneShotAndRepeatLeavesStateUnchanged() {
        advanceToTerminalPreStartBoundary();
        manager.completeTerminalPreStartPassWithoutVint();
        Sonic2SpecialStageSnapshot afterFirst = manager.captureRewindSnapshot();

        assertThrows(IllegalStateException.class,
                manager::completeTerminalPreStartPassWithoutVint);

        assertTerminalBoundaryStateUnchanged(afterFirst, manager.captureRewindSnapshot());
    }

    @Test
    void initialObj5fChildrenRenderDuringCountdownAndMessageAppearsAtTerminalPass() {
        advanceToInitialWait2Start();
        Sonic2SpecialStageIntro intro = manager.getIntro();

        assertTrue(intro.isBannerVisible(),
                "the banner-child render pass must remain enabled at allocation");
        assertTrue(intro.isBannerInFlyoutPhase(),
                "WAIT2 owns the independent child flight after Obj5F allocation");
        assertEquals(7, intro.getBannerLetters().size());
        assertTrue(intro.getBannerLetters().stream().allMatch(letter -> letter.visible));
        assertEquals(1, intro.getLetterFlyoutProgress(),
                "new later-slot banner children execute once in their allocation pass");
        assertNotEquals(-0x48, intro.getBannerLetters().get(0).x,
                "the first child ObjectMove precedes its first display");
        assertFalse(intro.isMessageVisible(),
                "Obj5A ring-requirement message does not exist during initial countdown");
        assertEquals(0, manager.captureRewindSnapshot().intro.phaseTimer(),
                "Obj5F child creation stores $1E but does not decrement it");

        int initialLeftPieceX = intro.getBannerLetters().get(0).x;
        for (int countdownPass = 1;
             countdownPass <= Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1;
             countdownPass++) {
            manager.update();
            assertEquals(countdownPass,
                    manager.captureRewindSnapshot().intro.phaseTimer());
            assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2, intro.getCurrentPhase());
            assertFalse(intro.isSpecialStageStarted());
            assertFalse(intro.isMessageVisible());
            if (countdownPass == 5) {
                assertNotEquals(initialLeftPieceX, intro.getBannerLetters().get(0).x,
                        "banner children must keep moving during the overlapping countdown");
            }
        }
        assertTrue(intro.isBannerVisible());
        assertTrue(intro.isBannerInFlyoutPhase());
        assertFalse(intro.isMessageVisible());

        manager.update();
        Sonic2SpecialStageSnapshot terminalPending = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1,
                terminalPending.intro.phaseTimer(),
                "the terminal VInt schedules Obj5F without executing its deferred pass");
        assertTrue(terminalPending.recurringMainPassPending);

        manager.completeTerminalPreStartPassWithoutVint();

        assertFalse(intro.isBannerVisible());
        assertFalse(intro.isBannerInFlyoutPhase());
        assertTrue(intro.isMessageVisible(),
                "terminal Obj5F pass creates the initial GET-rings message");
        assertTrue(intro.isSpecialStageStarted());

        intro.showRingRequirementMessage(50);
        assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2, intro.getCurrentPhase());
        assertFalse(intro.isBannerVisible());
        assertTrue(intro.isMessageVisible(),
                "checkpoint reuse must retain its immediate message visibility");
    }

    @Test
    void emeraldInitMasksPendingLogicalControlsToStartOnly() throws Exception {
        advanceToGameplay();
        assertTrue(manager.captureRewindSnapshot().recurringMainPassPending);

        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(0x36, 0x40);
        emerald.setManager(manager);
        manager.getObjectManager().getActiveObjects().add(emerald);
        emerald.update(0, false, 12, false);

        provider.bindPendingRecurringPassInput(0x98, 0x90, 0x8C, 0x8C);

        Sonic2SpecialStageSnapshot pending = manager.captureRewindSnapshot();
        assertEquals(0x80, pending.pendingMainHeldButtons,
                "Obj59 pause-only mode must suppress P1 direction/action held bits");
        assertEquals(0x80, pending.pendingMainPressedButtons,
                "Obj59 pause-only mode must preserve only P1 Start presses");
        assertEquals(0x80, pending.pendingMainP2HeldButtons,
                "Obj59 pause-only mode must suppress P2 direction held bits");
        assertEquals(0x80, pending.pendingMainP2LogicalButtons,
                "Obj59 pause-only mode must preserve only P2 Start logically");
    }

    @Test
    void wait2MessageCreationSwitchesTheFollowingVintToCurrentInput() {
        advanceThroughStartupRunObjects();
        int updatesRemaining = 1_000;
        while ((manager.getIntro().getCurrentPhase() != Sonic2SpecialStageIntro.Phase.WAIT2
                || manager.captureRewindSnapshot().intro.phaseTimer()
                        < Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1)
                && updatesRemaining-- > 0) {
            manager.handleInput(0, 0);
            manager.update();
        }

        assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2,
                manager.getIntro().getCurrentPhase());
        assertEquals(Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1,
                manager.captureRewindSnapshot().intro.phaseTimer());
        assertFalse(manager.getIntro().isSpecialStageStarted());
        Sonic2SpecialStageSnapshot beforeStart = manager.captureRewindSnapshot();

        manager.handleInput(0x08, 0);
        manager.update();
        assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2,
                manager.getIntro().getCurrentPhase(),
                "the terminal Obj5F pass remains pending at the VInt observation");
        manager.completeTerminalPreStartPassWithoutVint();

        assertEquals(Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT,
                manager.getIntro().getCurrentPhase(),
                "Obj5F message creation sets SpecialStage_Started at this boundary");
        assertTrue(manager.getIntro().isSpecialStageStarted());
        assertEquals(0x08, manager.captureRewindSnapshot().pendingMainHeldButtons,
                "the loop after message creation copies current input after WaitForVint");
        Sonic2SpecialStageSnapshot afterStart = manager.captureRewindSnapshot();

        manager.restoreRewindSnapshot(beforeStart);
        assertFalse(manager.getIntro().isSpecialStageStarted());
        manager.restoreRewindSnapshot(afterStart);
        assertTrue(manager.getIntro().isSpecialStageStarted(),
                "rewind must restore the latched ROM control-loop boundary");
    }

    @Test
    void pendingRunObjectsUsesPlayerThenBannerThenDynamicSlotOrder() {
        advanceThroughStartupRunObjects();
        for (int update = 0; update < Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES; update++) {
            manager.update();
        }

        int initialBannerY = manager.getIntro().getBannerY();
        List<String> dynamicObservations = new ArrayList<>();
        manager.getObjectManager().getActiveObjects().add(new Sonic2SpecialStageRing() {
            @Override
            public void update(
                    int currentTrackFrame,
                    boolean trackFlipped,
                    int speedFactor,
                    boolean drawingIndex4) {
                dynamicObservations.add(manager.getSonicPlayer().getSSYPos()
                        + ":" + manager.getIntro().getBannerY());
            }
        });

        manager.update(); // schedules first recurring RunObjects pass
        manager.update(); // players, fixed banner, then later dynamic slot

        // The deferred first post-fade pass and the current pre-start pass both
        // complete before this observation. RunObjects scans players, Obj5F, then
        // later dynamic slots on each pass (s2.asm:29805-29846, 6674-6688).
        assertEquals(List.of(
                "110:" + (initialBannerY + 1),
                "110:" + (initialBannerY + 2)), dynamicObservations);
    }

    @Test
    void deferredMainPassReadsTheCurrentExecutedVintPhase() {
        advanceToGameplay();
        Sonic2SpecialStageSnapshot before = manager.captureRewindSnapshot();
        assertTrue(before.recurringMainPassPending);
        int[] observedPhase = {-1};
        manager.getObjectManager().getActiveObjects().add(new Sonic2SpecialStageRing() {
            @Override
            public void update(
                    int currentTrackFrame,
                    boolean trackFlipped,
                    int speedFactor,
                    boolean drawingIndex4) {
                observedPhase[0] = manager.captureRewindSnapshot().renderFrameCounter;
            }
        });

        manager.update();

        Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
        assertEquals(before.frameCounter + 1, after.frameCounter);
        assertEquals(after.frameCounter, observedPhase[0],
                "RunObjects after VInt must read that VInt's global run-count phase");
        assertEquals(after.frameCounter, after.renderFrameCounter);
    }

    @Test
    void rewindDuringFadeRestoresTimerAndReplaysTheBoundaryDeterministically() {
        advanceThroughStartupRunObjects();
        Sonic2SpecialStageComparisonState frozen = manager.captureComparisonState();
        int frozenBannerY = manager.getIntro().getBannerY();

        for (int update = 0; update < 7; update++) {
            manager.update();
        }
        Sonic2SpecialStageSnapshot rewindPoint = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                rewindPoint.intro.currentPhase());
        assertEquals(7, rewindPoint.intro.phaseTimer());

        for (int update = 7; update < Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES; update++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.DROP, manager.getIntro().getCurrentPhase());
        assertEquals(frozen, manager.captureComparisonState());
        assertEquals(frozenBannerY, manager.getIntro().getBannerY());

        manager.update();
        Sonic2SpecialStageComparisonState firstBoundary = manager.captureComparisonState();
        int firstBoundaryBannerY = manager.getIntro().getBannerY();

        manager.restoreRewindSnapshot(rewindPoint);

        Sonic2SpecialStageSnapshot restored = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                manager.getIntro().getCurrentPhase());
        assertEquals(7, restored.intro.phaseTimer());

        for (int update = 7; update < Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES; update++) {
            manager.update();
        }
        assertEquals(frozen, manager.captureComparisonState());
        assertEquals(frozenBannerY, manager.getIntro().getBannerY());

        manager.update();

        assertEquals(firstBoundary, manager.captureComparisonState());
        assertEquals(firstBoundaryBannerY, manager.getIntro().getBannerY());
    }

    @Test
    void rewindRestoresScheduledMainPassInputsAndDrawSlice() {
        advanceThroughStartupRunObjects();
        for (int update = 0; update < Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES; update++) {
            manager.handleInput(
                    update == Sonic2SpecialStageIntro.FADE_FROM_WHITE_FRAMES - 1 ? 0x08 : 0,
                    0);
            manager.update();
        }

        manager.handleInput(0, 0);
        manager.update();
        Sonic2SpecialStageSnapshot scheduled = manager.captureRewindSnapshot();
        assertTrue(scheduled.recurringMainPassPending);
        assertEquals(0x08, scheduled.pendingMainHeldButtons);
        assertEquals(0x08, scheduled.pendingMainPressedButtons,
                "pre-start loop copies the held transition sampled by the final fade VInt");
        assertFalse(scheduled.pendingMainCheckpointStep);
        assertEquals(0, scheduled.previousPhysicalHeldButtons);
        assertEquals(0, scheduled.previousPhysicalPressedButtons);
        assertEquals(0, scheduled.pressedButtons,
                "scheduling consumes the live press edge immediately");

        manager.handleInput(0, 0);
        manager.update();
        Sonic2SpecialStageComparisonState firstReplay = manager.captureComparisonState();
        int firstReplayBannerY = manager.getIntro().getBannerY();

        manager.restoreRewindSnapshot(scheduled);

        assertTrue(manager.captureRewindSnapshot().recurringMainPassPending);
        manager.handleInput(0, 0);
        manager.update();
        assertEquals(firstReplay, manager.captureComparisonState());
        assertEquals(firstReplayBannerY, manager.getIntro().getBannerY());
    }

    @Test
    void reinitializeRestoresPreRollAndClearsFadeProgress() throws Exception {
        advanceThroughStartupRunObjects();
        for (int update = 0; update < 5; update++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                manager.getIntro().getCurrentPhase());

        manager.initialize(0);

        Sonic2SpecialStageSnapshot reset = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageIntro.Phase.PRE_ROLL, reset.intro.currentPhase());
        assertEquals(0, reset.intro.phaseTimer());
        assertEquals(0, reset.drawingIndex);
        assertEquals(0, reset.trackAnimator.currentFrameInSegment());
        assertFalse(reset.trackAnimator.speedChangePending());
        assertFalse(reset.recurringMainPassPending);
        assertEquals(0, reset.pendingMainHeldButtons);
        assertEquals(0, reset.pendingMainPressedButtons);
        assertFalse(reset.pendingMainCheckpointStep);
        assertEquals(0, reset.previousPhysicalHeldButtons);
        assertEquals(0, reset.previousPhysicalPressedButtons);
        assertEquals(0, reset.previousPhysicalP2HeldButtons);
        assertEquals(0, reset.previousPhysicalP2LogicalButtons);
        assertTrue(manager.getPlayers().stream().noneMatch(Sonic2SpecialStagePlayer::isSpawned));
    }

    private void advanceThroughStartupRunObjects() {
        int updates = Sonic2SpecialStageIntro.PRE_ROLL_FRAMES + STARTUP_WAIT_UPDATES;
        for (int update = 0; update < updates; update++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageManager.PlayerBootstrapPhase.INITIALIZED,
                manager.captureRewindSnapshot().playerBootstrapPhase);
        assertEquals(Sonic2SpecialStagePlayer.RoutineState.NORMAL,
                manager.getSonicPlayer().getRoutine());
    }

    private void advanceToGameplay() {
        advanceThroughStartupRunObjects();
        int updatesRemaining = 1_000;
        while (!manager.getIntro().isComplete() && updatesRemaining-- > 0) {
            manager.handleInput(0, 0);
            manager.update();
        }
        assertTrue(manager.getIntro().isComplete(), "intro should reach semantic gameplay gate");
    }

    private void advanceToTerminalPreStartBoundary() {
        advanceThroughStartupRunObjects();
        int updatesRemaining = 1_000;
        while ((manager.getIntro().getCurrentPhase() != Sonic2SpecialStageIntro.Phase.WAIT2
                || manager.captureRewindSnapshot().intro.phaseTimer()
                        < Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1
                || !manager.captureRewindSnapshot().recurringMainPassPending)
                && updatesRemaining-- > 0) {
            manager.handleInput(0, 0);
            manager.update();
        }
        Sonic2SpecialStageSnapshot boundary = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2, boundary.intro.currentPhase());
        assertEquals(Sonic2SpecialStageConstants.INTRO_WAIT2_FRAMES - 1,
                boundary.intro.phaseTimer());
        assertFalse(boundary.intro.specialStageStarted());
        assertTrue(boundary.recurringMainPassPending);
    }

    private void advanceToInitialWait2Start() {
        advanceThroughStartupRunObjects();
        int updatesRemaining = 1_000;
        while (manager.getIntro().getCurrentPhase() != Sonic2SpecialStageIntro.Phase.WAIT2
                && updatesRemaining-- > 0) {
            manager.handleInput(0, 0);
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.WAIT2,
                manager.getIntro().getCurrentPhase());
        assertEquals(0, manager.captureRewindSnapshot().intro.phaseTimer());
    }

    private int rendererFrameCounter() throws Exception {
        java.lang.reflect.Field rendererField = Sonic2SpecialStageManager.class.getDeclaredField("renderer");
        rendererField.setAccessible(true);
        Object renderer = rendererField.get(manager);
        java.lang.reflect.Field frameField = Sonic2SpecialStageRenderer.class.getDeclaredField("frameCounter");
        frameField.setAccessible(true);
        return frameField.getInt(renderer);
    }

    private int[] decodedTrackFrame() throws Exception {
        java.lang.reflect.Field field = Sonic2SpecialStageManager.class.getDeclaredField("decodedTrackFrame");
        field.setAccessible(true);
        return (int[]) field.get(manager);
    }

    private void setManagerField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = Sonic2SpecialStageManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private static final class TrackRenderRecorder extends Sonic2SpecialStageRenderer {
        private int trackRenderCalls;

        private TrackRenderRecorder(GraphicsManager graphicsManager) {
            super(graphicsManager);
        }

        @Override
        public void renderTrack(int trackFrameIndex, int[] frameTiles) {
            trackRenderCalls++;
        }
    }

    private static void assertTerminalBoundaryStateUnchanged(
            Sonic2SpecialStageSnapshot expected,
            Sonic2SpecialStageSnapshot actual) {
        assertEquals(expected.frameCounter, actual.frameCounter);
        assertEquals(expected.renderFrameCounter, actual.renderFrameCounter);
        assertEquals(expected.lastDrawingIndex, actual.lastDrawingIndex);
        assertEquals(expected.recurringMainPassPending, actual.recurringMainPassPending);
        assertEquals(expected.pendingMainHeldButtons, actual.pendingMainHeldButtons);
        assertEquals(expected.pendingMainPressedButtons, actual.pendingMainPressedButtons);
        assertEquals(expected.pendingMainP2HeldButtons, actual.pendingMainP2HeldButtons);
        assertEquals(expected.pendingMainP2LogicalButtons, actual.pendingMainP2LogicalButtons);
        assertEquals(expected.pendingMainCheckpointStep, actual.pendingMainCheckpointStep);
        assertEquals(expected.intro, actual.intro);
        assertArrayEquals(expected.trackAnimator.stageLayout(), actual.trackAnimator.stageLayout());
        assertEquals(expected.trackAnimator.currentSegmentIndex(),
                actual.trackAnimator.currentSegmentIndex());
        assertEquals(expected.trackAnimator.currentFrameInSegment(),
                actual.trackAnimator.currentFrameInSegment());
        assertEquals(expected.trackAnimator.frameDelayCounter(),
                actual.trackAnimator.frameDelayCounter());
        assertEquals(expected.trackAnimator.playerAnimFrameTimer(),
                actual.trackAnimator.playerAnimFrameTimer());
    }
}
