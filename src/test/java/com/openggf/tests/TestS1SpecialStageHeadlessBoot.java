package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageComparisonState;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless ROM-backed boot smoke coverage for the S1 special stage (maze)
 * provider -- de-risks the trace replay harness under
 * {@code com.openggf.tests.trace.s1} (multi-stage trace-run spec addition
 * #6-S1) before wiring a full BK2 trace comparator around it.
 *
 * <p>Boots through the same production seam the trace replay harness will
 * use, mirroring {@code AbstractS2SpecialStageTraceReplayTest.bootHarness}
 * (headless graphics, the real Sonic 1 ROM via {@code @RequiresRom}, then
 * {@link Sonic1SpecialStageProvider#initializeStage(int)} directly -- no
 * level, no zone). {@link com.openggf.game.sonic1.specialstage.Sonic1SpecialStageManagerTest}
 * already proves the manager itself boots headlessly via
 * {@code initHeadless()}; this test proves the provider-path boot the
 * harness/GameLoop uses (expected to confirm a no-op -- no new init hook
 * needed).
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1SpecialStageHeadlessBoot {

    private static final int STAGE_INDEX = 0;
    private static final int STEP_FRAMES = 180;

    @Test
    void specialStageBootsHeadlesslyAndCapturesStableComparisonState() throws Exception {
        // Headless graphics so the SS manager's pattern/renderer setup is safe
        // without a GL context (mirrors AbstractS2SpecialStageTraceReplayTest
        // .bootHarness's initHeadless() call around Rom install).
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        // Recorded maze runs are captured solo (Sonic); mirror the S2 harness
        // ctor convention of setting team config BEFORE initializeStage()
        // resolves the character from config.
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

        Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();
        provider.initializeStage(STAGE_INDEX);

        assertTrue(provider.isInitialized(),
                "Provider must report initialized after initializeStage()");

        Sonic1SpecialStageComparisonState startState = provider.getManager().captureComparisonState();
        assertNotNull(startState,
                "captureComparisonState() must return a snapshot immediately after init");

        for (int frame = 0; frame < STEP_FRAMES; frame++) {
            provider.handleInput(0, 0);
            provider.update();
        }

        assertTrue(provider.isInitialized(), "Provider must remain initialized after stepping");
        assertFalse(provider.isFinished(),
                "Idle input for " + STEP_FRAMES + " frames must not finish the stage");

        Sonic1SpecialStageComparisonState endState = provider.getManager().captureComparisonState();
        assertNotNull(endState,
                "captureComparisonState() must return a snapshot after stepping");

        // update() adds SS_INIT_ROTATION (0x40) to ssAngle unconditionally, so
        // this comparison is guaranteed non-vacuous even with idle input.
        assertNotEquals(startState.ssAngle(), endState.ssAngle(),
                "ssAngle must change after stepping frames: update() rotates it unconditionally");
    }
}
