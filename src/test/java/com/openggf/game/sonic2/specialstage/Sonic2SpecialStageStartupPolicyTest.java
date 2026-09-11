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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Sonic2SpecialStageStartupPolicyTest {

    private Rom rom;

    @BeforeEach
    void setUp() throws Exception {
        Path romPath = Path.of("s2.gen");
        assumeTrue(Files.isRegularFile(romPath), "s2.gen ROM required for startup policy tests");

        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();
        rom = new Rom();
        assertTrue(rom.open(romPath.toAbsolutePath().toString()));
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
        if (rom != null) {
            rom.close();
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    void defaultInitializationStepsPalFadeToWhiteThenStartsUpWithoutALoadHold(double lagFactor)
            throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.setLagCompensation(lagFactor);

        provider.initializeStage(0);
        Sonic2SpecialStageManager manager = provider.getManager();

        assertEquals(Sonic2SpecialStageIntro.Phase.PRE_ROLL, manager.getIntro().getCurrentPhase());
        assertFalse(provider.isEntryPresentationReady());
        assertTrue(provider.isEntryFadeToWhiteActive());
        assertEquals(0, manager.getLiveEntryLoadHoldFrames());

        // Pal_FadeToWhite rows never lag: exactly 22 updates leave PRE_ROLL
        // even with the live lag model armed.
        for (int update = 0; update < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES - 1; update++) {
            manager.update();
            assertTrue(provider.isEntryFadeToWhiteActive(), "update " + update);
        }
        manager.update();
        assertFalse(provider.isEntryFadeToWhiteActive());
        assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP,
                manager.getIntro().getCurrentPhase());

        // The masked-interrupt load is not reproduced in normal play: startup
        // proceeds at once through the ordinary update path to the reveal.
        int updates = 0;
        while (!provider.isEntryPresentationReady() && updates < 256) {
            manager.update();
            updates++;
        }
        assertTrue(provider.isEntryPresentationReady());
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                manager.getIntro().getCurrentPhase());
    }

    @Test
    void neitherPolicyArmsTheLiveEntryLoadHold() throws Exception {
        for (SpecialStageStartupPolicy policy : SpecialStageStartupPolicy.values()) {
            Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
            provider.initializeStage(0, policy);
            provider.setLagCompensation(0);
            Sonic2SpecialStageManager manager = provider.getManager();

            assertEquals(0, manager.getLiveEntryLoadHoldFrames(), policy.name());
            for (int update = 0; update < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; update++) {
                manager.update();
            }
            assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP,
                    manager.getIntro().getCurrentPhase(), policy.name());
            int drawingIndexAtStartup = manager.getDrawingIndex();
            manager.update();
            assertNotEquals(drawingIndexAtStartup, manager.getDrawingIndex(),
                    policy + " startup must not insert a load hold");
            provider.reset();
        }
    }

    @Test
    void armedLiveEntryLoadHoldSpendsTheRecordedLagSpanAfterPreRoll() throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0);
        provider.setLagCompensation(0);
        Sonic2SpecialStageManager manager = provider.getManager();
        manager.armLiveEntryLoadHold();

        for (int update = 0; update < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; update++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP,
                manager.getIntro().getCurrentPhase());
        assertEquals(Sonic2SpecialStageLagModel.ENTRY_LOAD_LAG_FRAMES,
                manager.getLiveEntryLoadHoldFrames(), "PRE_ROLL must not spend the hold");

        int drawingIndexAtLoad = manager.getDrawingIndex();
        for (int update = 0; update < Sonic2SpecialStageLagModel.ENTRY_LOAD_LAG_FRAMES; update++) {
            manager.update();
            assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP,
                    manager.getIntro().getCurrentPhase(), "update " + update);
            assertEquals(drawingIndexAtLoad, manager.getDrawingIndex(), "update " + update);
        }
        assertEquals(0, manager.getLiveEntryLoadHoldFrames());
        manager.update();
        assertNotEquals(drawingIndexAtLoad, manager.getDrawingIndex());
    }

    @Test
    void liveEntryLoadHoldSurvivesRewindSnapshotRoundTrip() throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0);
        Sonic2SpecialStageManager manager = provider.getManager();
        manager.armLiveEntryLoadHold();
        for (int update = 0; update < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES + 5; update++) {
            manager.update();
        }
        int expected = manager.getLiveEntryLoadHoldFrames();
        assertEquals(Sonic2SpecialStageLagModel.ENTRY_LOAD_LAG_FRAMES - 5, expected);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        manager.update();
        manager.update();
        manager.restoreRewindSnapshot(snapshot);

        assertEquals(expected, manager.getLiveEntryLoadHoldFrames());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    void accurateInitializationPreservesPreRollRegardlessOfLag(double lagFactor) throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.setLagCompensation(lagFactor);

        provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);

        assertEquals(Sonic2SpecialStageIntro.Phase.PRE_ROLL,
                provider.getManager().getIntro().getCurrentPhase());
        assertFalse(provider.isEntryPresentationReady());
    }

    @Test
    void armedHoldAndStartupProgressCannotLeakAcrossReinitialization() throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);
        provider.getManager().armLiveEntryLoadHold();
        provider.getManager().advanceToEntryPresentation();
        provider.reset();

        provider.initializeStage(0);

        assertEquals(0, provider.getManager().getLiveEntryLoadHoldFrames());
        assertEquals(Sonic2SpecialStageIntro.Phase.PRE_ROLL,
                provider.getManager().getIntro().getCurrentPhase());
    }

    @Test
    void nullPolicyIsRejected() {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        assertThrows(NullPointerException.class, () -> provider.initializeStage(0, null));
    }

    @Test
    void zeroUpdateBudgetReportsCurrentStartupPhase() throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.getManager().advanceToEntryPresentation(0));

        assertTrue(error.getMessage().contains("PRE_ROLL"));
    }

    @Test
    void fastForwardAfterRevealBoundaryIsRejected() throws Exception {
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0);
        provider.getManager().advanceToEntryPresentation();
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                provider.getManager().getIntro().getCurrentPhase());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.getManager().advanceToEntryPresentation());

        assertTrue(error.getMessage().contains("FADE_FROM_WHITE"));
    }
}
