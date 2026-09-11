package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageRomOffsets;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless ROM-backed boot smoke coverage for the S3K special stage (blue
 * spheres) provider -- de-risks the trace replay harness under
 * {@code com.openggf.tests.trace.s3k} (multi-stage trace-run spec addition
 * #6-S3K) before wiring a full BK2 trace comparator around it.
 *
 * <p>Boots through the same production seam the trace replay harness will
 * use, mirroring {@code AbstractS2SpecialStageTraceReplayTest.bootHarness}
 * (headless graphics, the real Sonic 3&amp;K ROM via {@code @RequiresRom},
 * then {@link Sonic3kSpecialStageProvider#initializeStage(int)} directly --
 * no level, no zone). This exercises the ROM-offsets-verified {@code
 * loadRomData()} branch (asserted below), not the placeholder-rendering
 * fallback used when offsets are unverified.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSpecialStageHeadlessBoot {

    private static final int STAGE_INDEX = 0;
    private static final int IDLE_FRAMES = 60;

    @Test
    void specialStageBootsHeadlesslyAndCapturesStableComparisonState() throws Exception {
        assertTrue(Sonic3kSpecialStageRomOffsets.areOffsetsVerified(),
                "S3K SS ROM offsets must be verified so this boot exercises loadRomData(), "
                        + "not the placeholder-rendering fallback");

        // Headless graphics so the SS manager's pattern/renderer setup is safe
        // without a GL context (mirrors AbstractS2SpecialStageTraceReplayTest
        // .bootHarness's initHeadless() call around Rom install).
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        // Recorded blue-spheres runs are captured solo (Sonic); mirror the S2
        // harness ctor convention of setting team config BEFORE
        // initializeStage() resolves the character from config.
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        Sonic3kSpecialStageProvider provider = new Sonic3kSpecialStageProvider();
        provider.initializeStage(STAGE_INDEX);

        assertTrue(provider.isInitialized(),
                "Provider must report initialized after initializeStage()");
        assertTrue(provider.getManager().getSpheresLeft() > 0,
                "Stage layout must load at least one blue sphere from ROM data");

        Sonic3kSpecialStageComparisonState startState = provider.getManager().captureComparisonState();
        assertNotNull(startState,
                "captureComparisonState() must return a snapshot immediately after init");
        assertFalse(startState.finished(), "Freshly initialized stage must not be finished");
        assertFalse(startState.started(), "Player has not yet started moving at frame 0");

        for (int frame = 0; frame < IDLE_FRAMES; frame++) {
            provider.handleInput(0, 0);
            provider.handlePlayer2Input(0, 0);
            provider.update();
            assertFalse(provider.isFinished(),
                    "Idle input for " + IDLE_FRAMES + " frames must not finish the stage at frame " + frame);
        }

        assertTrue(provider.isInitialized(), "Provider must remain initialized after idle stepping");
        assertTrue(provider.getManager().getSpheresLeft() > 0,
                "Idle stepping alone must not clear any spheres");

        // captureComparisonState() is a pure read with no caching: two calls
        // with no intervening update() must be identical.
        Sonic3kSpecialStageComparisonState first = provider.getManager().captureComparisonState();
        Sonic3kSpecialStageComparisonState second = provider.getManager().captureComparisonState();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first, second,
                "Two captureComparisonState() calls without an intervening update() must be identical");
    }
}
