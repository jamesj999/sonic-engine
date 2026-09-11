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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Sonic2SpecialStagePreRollTest {

    private Rom rom;
    private Sonic2SpecialStageManager manager;

    @BeforeEach
    void bootSpecialStage() throws Exception {
        Path romPath = Path.of("s2.gen");
        assumeTrue(Files.isRegularFile(romPath), "s2.gen ROM required for pre-roll tests");

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

        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
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
    void suppressesTrackSpeedDrawingAndBannerForRomPreRollWindow() {
        int initialBannerY = manager.getIntro().getBannerY();
        int preRollFrames = Sonic2SpecialStageIntro.PRE_ROLL_FRAMES;
        // Pal_FadeToWhite loads d4=$15 and uses dbf: 22 executed V-ints
        // (docs/s2disasm/s2.asm:3568-3578, called at s2.asm:6546).
        assertEquals(22, preRollFrames);

        for (int frame = 0; frame < preRollFrames; frame++) {
            Sonic2SpecialStageComparisonState before = manager.captureComparisonState();
            assertEquals(0, before.speedFactor(), "ROM current speed is zero at pre-roll frame " + frame);
            assertEquals(0, before.trackAnimFrame(), "track frame must stay zero before tick " + frame);
            assertEquals(0, before.trackFrameDelayCounter(), "track timer must stay zero before tick " + frame);
            assertEquals(0, before.drawingIndex(), "drawing index must stay zero before tick " + frame);
            assertTrue(manager.getIntro().isPreRollActive());

            manager.update();

            Sonic2SpecialStageComparisonState after = manager.captureComparisonState();
            assertEquals(0, after.speedFactor(), "pre-roll tick must not promote speed at frame " + frame);
            assertEquals(0, after.trackAnimFrame(), "pre-roll tick must not animate track at frame " + frame);
            assertEquals(0, after.trackFrameDelayCounter(), "pre-roll tick must not run track timer at frame " + frame);
            assertEquals(0, after.drawingIndex(), "pre-roll tick must not advance drawing index at frame " + frame);
            assertEquals(initialBannerY, manager.getIntro().getBannerY(),
                    "pre-roll tick must not start the DROP banner at frame " + frame);
        }

        assertFalse(manager.getIntro().isPreRollActive());
        assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP, manager.getIntro().getCurrentPhase());

        manager.update();

        Sonic2SpecialStageComparisonState firstActive = manager.captureComparisonState();
        assertEquals(12, firstActive.speedFactor(), "first post-pre-roll tick promotes SS_New_Speed_Factor");
        assertEquals(0, firstActive.trackFrameDelayCounter(),
                "speed promotion reloads the ROM duration timer on the first VInt");
        assertEquals(1, firstActive.drawingIndex(), "drawing index starts at the phase boundary");
        assertEquals(initialBannerY, manager.getIntro().getBannerY(),
                "the ROM startup waits do not start the DROP banner");
    }

    @Test
    void rewindSnapshotRestoresPreRollPhaseAndTimer() {
        for (int frame = 0; frame < 7; frame++) {
            manager.update();
        }
        assertTrue(manager.getIntro().isPreRollActive());
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        for (int frame = 7; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertFalse(manager.getIntro().isPreRollActive());

        manager.restoreRewindSnapshot(snapshot);

        assertTrue(manager.getIntro().isPreRollActive());
        assertEquals(0, manager.captureComparisonState().speedFactor());
        for (int frame = 7; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP, manager.getIntro().getCurrentPhase(),
                "restored pre-roll timer should reach the same boundary");

        manager.update();

        assertEquals(12, manager.captureComparisonState().speedFactor(),
                "rewinding into PRE_ROLL must re-arm the one-shot initial speed promotion");
    }

    @Test
    void laterRomZeroSpeedIsNotRePromotedToInitialSpeed() throws Exception {
        for (int frame = 0; frame <= Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertEquals(12, manager.captureComparisonState().speedFactor());

        // The ROM intentionally writes zero later in the stage (s2.asm:72418).
        manager.getTrackAnimator().setSpeedFactor(0);
        Sonic2SpecialStageSnapshot consumedPromotion = manager.captureRewindSnapshot();

        manager.update();

        assertEquals(0, manager.captureComparisonState().speedFactor(),
                "zero is a legitimate later ROM speed, not an initialization sentinel");

        manager.initialize(0);
        manager.restoreRewindSnapshot(consumedPromotion);
        manager.update();

        assertEquals(0, manager.captureComparisonState().speedFactor(),
                "rewind must restore that the initial promotion was already consumed");
    }

    @Test
    void emeraldFailureMovesAndProjectsBeforeZeroSpeedPromotionAtNextVint() throws Exception {
        for (int frame = 0; frame <= Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertEquals(12, manager.captureComparisonState().speedFactor());

        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        emerald.setManager(manager);
        emerald.setRingRequirement(1);
        set(emerald, "phase", Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING);
        set(emerald, "depthFixed", 7L << 16);
        set(emerald, "animIndex", 6);
        manager.getObjectManager().getActiveObjects().add(emerald);
        set(manager, "drawingIndex", 4);

        invoke(manager, "executeActiveSpecialStageObjects");

        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.FAILED, emerald.getPhase());
        assertEquals(0x4F, emerald.captureRewindSnapshot().emeraldPhaseTimer());
        assertEquals(6, emerald.getDepth(),
                "loc_36142 returns through loc_36022's movement tail in the failure pass");
        assertEquals(7, emerald.getAnimIndex(),
                "the same pass must project the post-movement depth");
        assertEquals(12, manager.captureComparisonState().speedFactor(),
                "SS_New_Speed_Factor does not change current speed inside RunObjects");
        Sonic2SpecialStageSnapshot pendingFailure = manager.captureRewindSnapshot();

        manager.update();

        assertEquals(0, manager.captureComparisonState().speedFactor(),
                "the following Vint_S2SS promotes Obj59's requested zero factor");

        manager.getTrackAnimator().setSpeedFactor(12);
        manager.restoreRewindSnapshot(pendingFailure);
        assertEquals(12, manager.captureComparisonState().speedFactor());

        manager.update();

        assertEquals(0, manager.captureComparisonState().speedFactor(),
                "rewind must preserve the pending zero factor, not just its latch");
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
