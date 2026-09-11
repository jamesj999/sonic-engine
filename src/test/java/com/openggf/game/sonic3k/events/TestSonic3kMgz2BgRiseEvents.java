package com.openggf.game.sonic3k.events;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests the MGZ Act 2 BG rise ("rising floor") mechanic, Sonic path.
 *
 * <p>ROM: {@code MGZ2_BGEventTrigger} (sonic3k.asm:107117-107222) and
 * {@code Obj_MGZ2BGMoveSonic} (sonic3k.asm:107241-107325).
 *
 * <p>The ROM uses event state {@code Events_bg+$00} with values:
 * <ul>
 *   <li>{@code 0} - NORMAL: trigger check, BG collision off</li>
 *   <li>{@code 8} - SONIC_RISE: BG plane rising, BG collision on, player scripted-lifted</li>
 *   <li>{@code C} - AFTER_MOVE: BG stays at final offset, collision off</li>
 * </ul>
 *
 * <p>Rise velocity is {@code $6000} (subpixel per frame) until player crosses
 * X=$3D50, then 1 pixel per frame. Target offset is {@code $1D0}.
 */
class TestSonic3kMgz2BgRiseEvents {

    private static final int BG_RISE_NORMAL = 0;
    private static final int BG_RISE_SONIC = 8;
    private static final int BG_RISE_AFTER_MOVE = 0xC;
    private static final int BG_RISE_TARGET_SONIC = 0x1D0;
    private GameModule previousModule;

    @BeforeEach
    void setUp() {
        previousModule = GameModuleRegistry.getCurrent();
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        if (previousModule != null) {
            GameModuleRegistry.setCurrent(previousModule);
        } else {
            GameModuleRegistry.reset();
        }
    }

    private static AbstractPlayableSprite placePlayer(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) x, (short) y);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        return player;
    }

    /**
     * Simulates one frame of MGZ Act 2 event processing. ROM-equivalent to
     * the background event publishing collision state before the player pass,
     * Obj_MGZ2BGMoveSonic consuming the resulting player position afterward,
     * and the remaining MGZ screen events running later in the frame.
     */
    private static void tick(Sonic3kMGZEvents events, int frame) {
        events.updatePrePhysics(1);
        events.updateBgRiseObjectAfterPlayerPhysics(1);
        events.update(1, frame);
    }

    @Test
    void initAct2_startsInNormalState() {
        placePlayer(0x1000, 0x500);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        assertEquals(BG_RISE_NORMAL, events.getBgRiseRoutine());
        assertEquals(0, events.getBgRiseOffset());
        assertFalse(GameServices.gameState().isBackgroundCollisionFlag());
    }

    @Test
    void playerInsideSonicTriggerBox_refreshesPlaneBeforeEnablingBgCollision() {
        placePlayer(0x3500, 0x850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setBgRiseLoadStateInitialised(true);

        tick(events, 0);

        assertEquals(BG_RISE_SONIC, events.getBgRiseRoutine());
        assertFalse(GameServices.gameState().isBackgroundCollisionFlag(),
                "state-0 trigger leaves BG collision clear while MGZ2BGE_Refresh redraws the plane");
        assertEquals(8, events.getBgRiseRefreshFramesRemaining(),
                "the pre-physics bridge retains the seven refresh calls plus the publishing ScreenEvents call");
        for (int frame = 1; frame <= 8; frame++) {
            tick(events, frame);
            assertFalse(GameServices.gameState().isBackgroundCollisionFlag(),
                    "player pass " + frame + " must precede the normal ScreenEvents flag publication");
        }
        tick(events, 9);
        assertTrue(GameServices.gameState().isBackgroundCollisionFlag(),
                "the player pass after normal ScreenEvents observes state-8 collision");
        assertEquals(0, events.getBgRiseOffset(), "rise hasn't started moving yet");
    }

    @Test
    void headlessBgRiseWithoutRuntimeStateDoesNotWarnAboutMissingScrollHandler() {
        Logger logger = Logger.getLogger(Sonic3kMGZEvents.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            placePlayer(0x3500, 0x850);
            Sonic3kMGZEvents events = new Sonic3kMGZEvents();
            events.init(1);

            tick(events, 0);

            assertTrue(records.stream().noneMatch(TestSonic3kMgz2BgRiseEvents::isMissingScrollHandlerWarning),
                    "headless MGZ event tests must not warn when runtime state is intentionally absent");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void sonicRise_doesNotMoveUntilPlayerReachesStartThreshold() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        // Player is in the trigger box but hasn't crossed the start threshold
        // (Y>=$A80 AND X>=$36D0 is required for Obj_MGZ2BGMoveSonic to begin).
        for (int frame = 1; frame <= 30; frame++) {
            tick(events, frame);
        }

        assertEquals(BG_RISE_SONIC, events.getBgRiseRoutine());
        assertEquals(0, events.getBgRiseOffset(),
                "offset should stay at 0 until player passes ($36D0, $A80)");
    }

    @Test
    void sonicRise_beginsWhenPlayerReachesStartThreshold() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3701);
        player.setCentreY((short) 0x0A81);
        for (int frame = 1; frame <= 3; frame++) {
            tick(events, frame);
        }

        assertTrue(events.getBgRiseOffset() > 0,
                "after a few frames past the threshold, offset should be > 0");
    }

    @Test
    void sonicRise_thresholdCrossingIsConsumedAfterCurrentPlayerMovement() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setBgRiseLoadStateInitialised(true);
        events.setBgRiseRoutine(BG_RISE_SONIC);

        events.updatePrePhysics(1);
        assertFalse(events.isBgRiseMotionStarted(),
                "the background-event bridge sees the prior player position");

        player.setCentreX((short) 0x36D1);
        player.setCentreY((short) 0x0A81);
        events.updateBgRiseObjectAfterPlayerPhysics(1);

        assertTrue(events.isBgRiseMotionStarted());
        assertEquals(0x6000, events.getBgRiseSubpixelAccum(),
                "threshold entry falls through to the first accumulator step on the same object pass");
    }

    @Test
    void collapseVScrollModeSuppressesBgRiseTriggerReentry() {
        placePlayer(0x3CC0, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setBgRiseLoadStateInitialised(true);
        events.setBgRiseRoutine(BG_RISE_AFTER_MOVE);
        events.setCollapseInitialized(true);

        events.updatePrePhysics(1);

        assertEquals(BG_RISE_AFTER_MOVE, events.getBgRiseRoutine(),
                "MGZ2_LevelCollapse's _unkEEA2 VScroll mode makes MGZ2_BGEventTrigger return before state-C re-entry");
    }

    @Test
    void sonicRise_locksCameraMinXWhenMotionActuallyStarts() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x36F0);
        player.setCentreX((short) 0x3701);
        player.setCentreY((short) 0x0A81);

        tick(events, 1);

        assertEquals((short) 0x36F0, camera.getMinX(),
                "starting the MGZ2 floor rise should immediately lock camera minX to the live camera position");
        assertEquals((short) 0x36F0, camera.getMinXTarget(),
                "starting the MGZ2 floor rise should also latch the minX target, matching Camera_target_min_X_pos");
    }

    @Test
    void sonicRise_acceleratesOnFrameAfterPlayerCrossesAccelThreshold() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0); // arm SONIC_RISE via trigger box

        // Now move past the start-motion thresholds AND past the accel threshold.
        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        tick(events, 1);

        assertEquals(0, events.getBgRiseOffset(),
                "threshold-crossing dispatch retains the final subpixel accumulator path");
        assertEquals(0x6000, events.getBgRiseSubpixelAccum());
        assertTrue(events.isBgRiseAccelLatched());

        tick(events, 2);

        assertEquals(1, events.getBgRiseOffset(),
                "the following dispatch advances exactly 1 pixel (ROM: loc_51B44/loc_51B6C)");
    }

    @Test
    void sonicRise_reachesTargetButStaysInRiseStateUntilTriggerLogicMovesItOut() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        // With accelerated path (+1/frame), we need $1D0 = 464 frames.
        for (int frame = 1; frame <= BG_RISE_TARGET_SONIC + 5; frame++) {
            tick(events, frame);
        }

        assertEquals(BG_RISE_SONIC, events.getBgRiseRoutine(),
                "ROM keeps Events_bg+$00 in state 8 until BGEventTrigger moves it to state C");
        assertEquals(BG_RISE_TARGET_SONIC, events.getBgRiseOffset());
        assertTrue(GameServices.gameState().isBackgroundCollisionFlag(),
                "BG collision stays on while state 8 remains active");
        assertTrue(events.isScreenShakeActive(),
                "reaching the target should start the final timed screen shake");
    }

    @Test
    void sonicRise_fallsIntoAfterMoveOnlyWhenBgTriggerConditionsMatch() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        for (int frame = 1; frame <= BG_RISE_TARGET_SONIC + 5; frame++) {
            tick(events, frame);
        }

        player.setCentreX((short) 0x3900);
        player.setCentreY((short) 0x07F0);
        tick(events, BG_RISE_TARGET_SONIC + 6);

        assertEquals(BG_RISE_AFTER_MOVE, events.getBgRiseRoutine());
        assertFalse(GameServices.gameState().isBackgroundCollisionFlag(),
                "state C turns BG collision off");
    }

    @Test
    void afterMove_canReturnToRiseStateWhenPlayerReentersTheRomThreshold() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        for (int frame = 1; frame <= BG_RISE_TARGET_SONIC + 5; frame++) {
            tick(events, frame);
        }
        player.setCentreX((short) 0x3900);
        player.setCentreY((short) 0x07F0);
        tick(events, BG_RISE_TARGET_SONIC + 6);
        player.setCentreX((short) 0x3A40);
        player.setCentreY((short) 0x0800);
        tick(events, BG_RISE_TARGET_SONIC + 7);

        assertEquals(BG_RISE_SONIC, events.getBgRiseRoutine());
        assertTrue(GameServices.gameState().isBackgroundCollisionFlag());
    }

    @Test
    void sonicRise_liftsPlayerByCentreOffsetDeltaEachFrame() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        tick(events, 1); // latch acceleration; this dispatch retains the accumulator path
        int startY = player.getCentreY();
        tick(events, 2);
        int afterOne = player.getCentreY();

        assertEquals(startY - 1, afterOne,
                "player centre Y should decrease by exactly the rise delta (1 pixel accelerated)");
    }

    @Test
    void sonicRise_liftsFirstSidekickBySameDeltaAsFocusedPlayer() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x3500, (short) 0x0A81);
        sidekick.setCpuControlled(true);
        GameServices.sprites().addSprite(sidekick, "tails");
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        tick(events, 1); // latch acceleration
        int sidekickStartY = sidekick.getCentreY();

        tick(events, 2);

        assertEquals(sidekickStartY - 1, sidekick.getCentreY(),
                "MGZ2 floor rise should lift Player 2 / the lead sidekick by the same rise delta as Sonic");
    }

    @Test
    void sonicRise_liftsEveryActiveSidekickBySameDeltaAsFocusedPlayer() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3500, (short) 0x0A81);
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x3500, (short) 0x0A90);
        tails.setCpuControlled(true);
        knuckles.setCpuControlled(true);
        GameServices.sprites().addSprite(tails, "tails");
        GameServices.sprites().addSprite(knuckles, "knuckles");
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        tick(events, 1); // latch acceleration
        int tailsStartY = tails.getCentreY();
        int knucklesStartY = knuckles.getCentreY();

        tick(events, 2);

        assertEquals(tailsStartY - 1, tails.getCentreY(),
                "MGZ2 floor rise should lift the first sidekick by the same rise delta as Sonic");
        assertEquals(knucklesStartY - 1, knuckles.getCentreY(),
                "MGZ2 floor rise should lift later active sidekicks for the multi-sidekick extension");
    }

    @Test
    void sonicRise_liftsEachEnginePlayerOnlyOnceWhenSidekickListContainsDuplicateInstances() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3500, (short) 0x0A81);
        tails.setCpuControlled(true);
        GameServices.sprites().addSprite(tails, "tails");
        addDuplicateSidekickForTest(tails);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        int playerStartY = player.getCentreY();
        int tailsStartY = tails.getCentreY();

        invokeLiftBgRisePassengers(events, player, 1);

        assertEquals(playerStartY - 1, player.getCentreY(),
                "MGZ2 floor rise should lift the focused main player exactly once");
        assertEquals(tailsStartY - 1, tails.getCentreY(),
                "MGZ2 floor rise should dedupe engine-player participation before lifting sidekicks");
    }

    @Test
    void finalTimedShake_expiresAfterTheRomCountdown() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        tick(events, 0);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        for (int frame = 1; frame <= BG_RISE_TARGET_SONIC + 20; frame++) {
            tick(events, frame);
        }

        assertFalse(events.isScreenShakeActive(),
                "the final $E-frame timed shake should decay back to 0");
    }

    @Test
    void sonicRise_stopsContinuousRumbleOnceTheFloorReachesItsFinalHeight() {
        AbstractPlayableSprite player = placePlayer(0x3500, 0x0850);
        AudioManager audio = mock(AudioManager.class);
        Sonic3kMGZEvents events = new TestableMgzEvents(audio);
        events.init(1);
        tick(events, 0);
        setPrivateField(events, "screenShakeActive", true);

        player.setCentreX((short) 0x3D60);
        player.setCentreY((short) 0x0A81);
        for (int frame = 1; frame <= BG_RISE_TARGET_SONIC + 1; frame++) {
            tick(events, frame);
        }

        assertEquals(BG_RISE_TARGET_SONIC, events.getBgRiseOffset());
        assertTrue(events.isScreenShakeActive(),
                "the short final crash shake should still be active after the floor stops");

        clearInvocations(audio);
        tick(events, BG_RISE_TARGET_SONIC + 2);

        verify(audio, never()).playSfx(Sonic3kSfx.RUMBLE_2.id);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) {
        try {
            var field = Sonic3kMGZEvents.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to set Sonic3kMGZEvents." + fieldName, e);
        }
    }

    private static boolean isMissingScrollHandlerWarning(LogRecord record) {
        return record.getLevel().intValue() >= Level.WARNING.intValue()
                && record.getMessage() != null
                && record.getMessage().startsWith("MGZ BG-rise: state SONIC armed");
    }

    private static void invokeLiftBgRisePassengers(Sonic3kMGZEvents events,
                                                   AbstractPlayableSprite player,
                                                   int delta) {
        try {
            var method = Sonic3kMGZEvents.class.getDeclaredMethod(
                    "liftBgRisePassengers", AbstractPlayableSprite.class, int.class);
            method.setAccessible(true);
            method.invoke(events, player, delta);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke Sonic3kMGZEvents.liftBgRisePassengers", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addDuplicateSidekickForTest(AbstractPlayableSprite sidekick) {
        try {
            var field = GameServices.sprites().getClass().getDeclaredField("sidekicks");
            field.setAccessible(true);
            ((List<AbstractPlayableSprite>) field.get(GameServices.sprites())).add(sidekick);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to duplicate SpriteManager sidekick list entry", e);
        }
    }

    private static final class TestableMgzEvents extends Sonic3kMGZEvents {
        private final AudioManager audio;

        private TestableMgzEvents(AudioManager audio) {
            this.audio = audio;
        }

        @Override
        protected AudioManager audio() {
            return audio;
        }
    }
}
