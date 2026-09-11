package com.openggf.game.sonic3k.events;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the MGZ Act 2 Drilling Robotnik mini-event state machine.
 *
 * <p>ROM: {@code MGZ2_QuakeEvent} (sonic3k.asm:106579-106786) and
 * {@code MGZ2_QuakeEventArray} (Screen Events.asm:1027-1030).
 */
class TestSonic3kMgz2QuakeEvents {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    private static AbstractPlayableSprite placePlayer(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) x, (short) y);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        return player;
    }

    private static void movePlayer(AbstractPlayableSprite player, int x, int y) {
        player.setX((short) x);
        player.setY((short) y);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void initAct2_startsInCheckState_noAppearancesComplete() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        assertEquals(0, events.getQuakeEventRoutine(),
                "after init(act=1), quake routine should be in Check state");
        assertFalse(events.isAppearance1Complete());
        assertFalse(events.isAppearance2Complete());
        assertFalse(events.isAppearance3Complete());
    }

    @Test
    void playerInsideFirstBox_advancesToQuakeEvent1_andSetsCameraMaxX() {
        // ROM: QuakeEventArray[0] = { minX=$780, maxX=$7C0, minY=$580, maxY=$600,
        //                             camMaxY=$5A0, camMaxX=$7E0 }. Player inside → state 4.
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);

        Camera camera = GameServices.camera();
        assertEquals(4, events.getQuakeEventRoutine(),
                "player inside box 1 advances state to 4 (QuakeEvent1)");
        assertEquals((short) 0x7E0, camera.getMaxX(),
                "first appearance locks Camera_max_X to force player right");
        assertEquals((short) 0x5A0, camera.getMaxY(),
                "first appearance sets Camera_max_Y per array entry");
    }

    @Test
    void playerInsideSecondBox_advancesToQuakeEvent2_andSetsCameraMinX() {
        // ROM: QuakeEventArray[1] = { minX=$31C0, maxX=$3200, minY=$1C0, maxY=$280,
        //                             camMaxY=$1E0, camMinX=$2F60 }.
        AbstractPlayableSprite player = placePlayer(0x31E0, 0x200);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);

        Camera camera = GameServices.camera();
        assertEquals(8, events.getQuakeEventRoutine());
        assertEquals((short) 0x2F60, camera.getMinX(),
                "second appearance locks Camera_min_X to force player left");
        assertEquals((short) 0x1E0, camera.getMaxY());
    }

    @Test
    void playerInsideThirdBox_advancesToQuakeEvent3_andSetsCameraMinX() {
        // ROM: QuakeEventArray[2] = { minX=$3440, maxX=$3480, minY=$680, maxY=$700,
        //                             camMaxY=$6A0, camMinX=$32C0 }.
        AbstractPlayableSprite player = placePlayer(0x3460, 0x6A0);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);

        Camera camera = GameServices.camera();
        assertEquals(12, events.getQuakeEventRoutine());
        assertEquals((short) 0x32C0, camera.getMinX(),
                "third appearance locks Camera_min_X to force player left");
        assertEquals((short) 0x6A0, camera.getMaxY());
    }

    @Test
    void playerOutsideAllBoxes_stateRemainsInCheck() {
        AbstractPlayableSprite player = placePlayer(0x1000, 0x400);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);

        assertEquals(0, events.getQuakeEventRoutine());
    }

    @Test
    void quakeEvent1_advancesToFlee_whenCameraReachesLock() {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(4, events.getQuakeEventRoutine(), "precondition: state 4");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x7E0);
        events.update(1, 1);

        assertEquals(16, events.getQuakeEventRoutine(),
                "camera at lock point advances to state 16 (QuakeEvent1Cont)");
        assertTrue(events.isAppearance1Complete());
        assertEquals((short) 0x7E0, camera.getMinX(),
                "camera min_X pinned to lock point (screen frozen during mini-event)");
    }

    @Test
    void quakeEvent1_cancelledByPlayerRetreat() {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(4, events.getQuakeEventRoutine());

        movePlayer(player, 0x770, 0x590);
        events.update(1, 1);

        assertEquals(0, events.getQuakeEventRoutine(),
                "retreat (player X < $780) reverts state to QuakeEventCheck");
        assertFalse(events.isAppearance1Complete());
    }

    @Test
    void quakeEvent2_advancesToFlee_whenCameraReachesLock() {
        AbstractPlayableSprite player = placePlayer(0x31E0, 0x200);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(8, events.getQuakeEventRoutine(), "precondition: state 8");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F60);
        events.update(1, 1);

        assertEquals(20, events.getQuakeEventRoutine(),
                "camera at min_X lock advances to state 20 (QuakeEvent2Cont)");
        assertTrue(events.isAppearance2Complete());
        assertEquals((short) 0x2F60, camera.getMaxX(),
                "camera max_X pinned to lock point after spawn");
    }

    @Test
    void quakeEvent2_snapsCameraMinYToArenaHeightWhenCameraReachesCeilingBound() {
        AbstractPlayableSprite player = placePlayer(0x31E0, 0x200);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(8, events.getQuakeEventRoutine(), "precondition: state 8");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x3000); // keep the spawn lock path from firing in this step
        camera.setY((short) 0x01E0);
        camera.setMinY((short) 0x0100);
        camera.setMaxY((short) 0x01E0);

        events.update(1, 1);

        assertEquals((short) 0x01E0, camera.getMinY(),
                "QuakeEvent2 should raise Camera_min_Y_pos to the temporary arena ceiling");
    }

    @Test
    void activeQuakeSequence_clampsPlayerToCurrentViewportRightEdge() {
        AbstractPlayableSprite player = placePlayer(0, 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setCentreX((short) 0x31E0);
        player.setCentreY((short) 0x0200);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(8, events.getQuakeEventRoutine(), "precondition: state 8");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F80);
        player.setCentreX((short) 0x30C8);

        events.update(1, 1);

        assertEquals((short) (0x2F80 + 320 - 24), player.getCentreX(),
                "quake screen lock should clamp Sonic against the current camera viewport, not the future lock bound");
        assertEquals(0, player.getXSpeed(),
                "right-edge quake clamp should cancel forward x speed");
        assertEquals(0, player.getGSpeed(),
                "right-edge quake clamp should cancel forward ground speed");
    }

    @Test
    void activeQuakeSequence_preservesExactViewportRightEdgeUntilPlayerBoundaryCheck() {
        AbstractPlayableSprite player = placePlayer(0, 0);
        player.setWidth(20);
        player.setHeight(38);
        player.setCentreX((short) 0x31E0);
        player.setCentreY((short) 0x0200);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(8, events.getQuakeEventRoutine(), "precondition: state 8");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F80);
        int rightBoundary = 0x2F80 + 320 - 24;
        player.setCentreX((short) rightBoundary);
        player.setSubpixelRaw(0xB000, 0x2D00);
        player.setXSpeed((short) 0x065C);
        player.setGSpeed((short) 0x0669);

        events.update(1, 1);

        assertEquals(rightBoundary, player.getCentreX());
        assertEquals(0xB000, player.getXSubpixelRaw(),
                "S3K's strict right-boundary comparison preserves the exact-edge fraction");
        assertEquals(0x065C, player.getXSpeed());
        assertEquals(0x0669, player.getGSpeed());
    }

    @Test
    void quakeEvent3_advancesToFlee_whenCameraReachesLock() {
        AbstractPlayableSprite player = placePlayer(0x3460, 0x6A0);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        assertEquals(12, events.getQuakeEventRoutine(), "precondition: state 12");

        Camera camera = GameServices.camera();
        camera.setX((short) 0x32C0);
        events.update(1, 1);

        assertEquals(24, events.getQuakeEventRoutine(),
                "camera at min_X lock advances to state 24 (QuakeEvent3Cont)");
        assertTrue(events.isAppearance3Complete());
        assertEquals((short) 0x32C0, camera.getMaxX());
    }

    @Test
    void chunkEvent3Completion_releasesForcedLeftCameraLock() throws Exception {
        placePlayer(0x3050, 0x0780);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setMinX((short) 0x32C0);
        camera.setMinXTarget((short) 0x32C0);
        camera.setMaxX((short) 0x32C0);
        camera.setMaxXTarget((short) 0x32C0);
        setPrivateField(events, "chunkEventRoutine", 12);
        setPrivateField(events, "chunkReplaceIndex", 0x5C);

        events.update(1, 0);

        assertEquals((short) 0, camera.getMinX(),
                "finished MGZ2 terrain movement must release the leftward force lock");
        assertEquals((short) 0, camera.getMinXTarget());
        assertEquals((short) 0x6000, camera.getMaxX());
        assertEquals((short) 0x6000, camera.getMaxXTarget());
        assertEquals(20, events.getChunkEventRoutine());
    }

    @Test
    void quakeEvent1Cont_releasesWhenPlayerPassesThreshold_andResetsBounds() {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x7E0);
        events.update(1, 1);
        assertEquals(16, events.getQuakeEventRoutine());

        movePlayer(player, 0x990, 0x590);
        events.update(1, 2);

        assertEquals(0, events.getQuakeEventRoutine(),
                "QuakeEvent1Cont returns to Check state after release");
        assertEquals((short) 0x1000, camera.getMaxY(),
                "Camera_max_Y restored to default $1000 on release");
    }

    @Test
    void screenShake_remainsActiveAfterFirstRelease_untilChunkEventOwnsShutdown() {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x7E0);
        events.update(1, 1);
        assertTrue(events.isScreenShakeActive(),
                "screen shake active while Robotnik is on-screen (post-spawn)");

        movePlayer(player, 0x990, 0x590);
        events.update(1, 2);

        assertTrue(events.isScreenShakeActive(),
                "first quake release should keep continuous shake active so MGZ2_ChunkEvent1 can arm");
    }

    @Test
    void destroyedRobotnikStartsCameraUnlockBeforeReleaseThreshold() throws Exception {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.update(1, 0);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x7E0);
        events.update(1, 1);
        assertEquals(16, events.getQuakeEventRoutine());

        MgzDrillingRobotnikInstance robotnik = new MgzDrillingRobotnikInstance(
                new ObjectSpawn(0x08E0, 0x0690, 0, 0, 0, false, 0), false);
        robotnik.setDestroyed(true);
        setPrivateField(events, "activeRobotnik", robotnik);

        camera.setMaxX((short) 0x07E0);
        events.update(1, 2);
        assertEquals(0x07E0, camera.getMaxX() & 0xFFFF,
                "the newly allocated gradual worker starts on the following frame");
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        assertEquals(0x07E0, camera.getMaxX() & 0xFFFF);
        events.update(1, 6);
        assertEquals(0x07E1, camera.getMaxX() & 0xFFFF,
                "the fourth $4000 step publishes the first one-pixel bound increment");
    }

    @Test
    void quakeEvent3Cont_releaseFiresBossArenaHook() {
        AbstractPlayableSprite player = placePlayer(0x3460, 0x6A0);
        final boolean[] hookFired = {false};
        Sonic3kMGZEvents events = new Sonic3kMGZEvents() {
            @Override
            protected void onMgz2BossArenaReached() {
                hookFired[0] = true;
            }
        };
        events.init(1);
        events.update(1, 0);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x32C0);
        events.update(1, 1);
        assertEquals(24, events.getQuakeEventRoutine());

        movePlayer(player, 0x31F0, 0x6A0);
        events.update(1, 2);

        assertTrue(hookFired[0],
                "QuakeEvent3Cont release fires the boss-arena hook");
    }

    @Test
    void continuousQuakePlaysRumble2Every16Frames() {
        placePlayer(0x790, 0x590);
        AudioManager audio = mock(AudioManager.class);
        TestableMgzEvents events = new TestableMgzEvents(audio);
        events.init(1);

        events.update(1, 0);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x7E0);
        events.update(1, 1);
        verify(audio, never()).playSfx(Sonic3kSfx.RUMBLE_2.id);

        events.update(1, 17);
        events.update(1, 18);
        events.update(1, 32);
        events.update(1, 33);

        verify(audio, times(2)).playSfx(Sonic3kSfx.RUMBLE_2.id);
    }

    @Test
    void collapseRequestHandoffPollsThePreDispatchContinuousRumble() {
        placePlayer(0x790, 0x590);
        AudioManager audio = mock(AudioManager.class);
        TestableMgzEvents events = new TestableMgzEvents(audio);
        events.init(1);

        events.update(1, 0);
        GameServices.camera().setX((short) 0x7E0);
        events.update(1, 0);
        clearInvocations(audio);
        events.requestLevelCollapse();

        events.update(1, 1);

        verify(audio).playSfx(Sonic3kSfx.RUMBLE_2.id);
        verify(audio, never()).playSfx(Sonic3kSfx.BIG_RUMBLE.id);
    }

    @Test
    void collapsePlaysBigRumbleEvery16Frames() {
        placePlayer(0x1000, 0x400);
        AudioManager audio = mock(AudioManager.class);
        TestableMgzEvents events = new TestableMgzEvents(audio);
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 1; frame <= 22; frame++) {
            events.update(1, frame);
        }
        verify(audio, never()).playSfx(Sonic3kSfx.BIG_RUMBLE.id);

        events.update(1, 23);
        assertTrue(events.isCollapseInitialized(),
                "the scrolling collapse starts only after the $14-frame startup shake expires");
        events.update(1, 32);
        events.update(1, 33);
        events.update(1, 34);

        verify(audio).playSfx(Sonic3kSfx.BIG_RUMBLE.id);
        verify(audio, never()).playSfx(Sonic3kSfx.RUMBLE_2.id);
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
