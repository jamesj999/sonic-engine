package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
public class TestAizPlaneIntroInstance {

    private AizPlaneIntroInstance intro;
    private Camera camera;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetPerTest();
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.resetState();
        GameServices.hardwareTiming().resetForMissingSnapshot();
        intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        intro.setServices(TestEnvironment.objectServices());
    }

    @Test
    public void initialRoutineIsZero() {
        assertEquals(0, intro.getRoutine());
    }

    @Test
    public void initSetsCorrectPosition() {
        assertEquals(0x60, intro.getX());
        assertEquals(0x30, intro.getY());
    }

    @Test
    public void routineAdvancesBy2() {
        intro.advanceRoutine();
        assertEquals(2, intro.getRoutine());
    }

    @Test
    public void waveSpawnIntervalIs5Frames() {
        assertEquals(5, AizPlaneIntroInstance.WAVE_SPAWN_INTERVAL);
    }

    @Test
    public void introWaveUsesRomSelfDeleteInsteadOfObjectManagerOutOfRange() {
        AizIntroWaveChild wave = new AizIntroWaveChild(
                new ObjectSpawn(0x120, 0x48, 0, 0, 0, false, 0), intro);

        assertTrue(wave.isPersistent(),
                "AIZ intro waves store screen-space X and self-delete at x < $60; "
                        + "ObjectManager's world-space out_of_range check must not cull them");
    }

    @Test
    public void introWaveDeleteCallbackKeepsSlotUntilNextDispatch() {
        AizIntroWaveChild wave = new AizIntroWaveChild(
                new ObjectSpawn(0x120, 0x48, 0, 0, 0, false, 0), intro);

        for (int frame = 0; frame < 32 && !wave.isDeleteRoutinePending(); frame++) {
            wave.update(frame, null);
        }

        assertTrue(wave.isDeleteRoutinePending());
        assertFalse(wave.isDestroyed(),
                "Animate_RawMultiDelay $F4 only installs Go_Delete_Sprite on its callback frame");
        wave.update(33, null);
        assertTrue(wave.isDestroyed(),
                "Go_Delete_Sprite releases the SST slot on the following object dispatch");
    }

    @Test
    public void knucklesSpawnTriggerAt0x918() {
        assertEquals(0x918, AizPlaneIntroInstance.KNUCKLES_SPAWN_X);
    }

    @Test
    public void explosionTriggerAt0x13D0() {
        assertEquals(0x13D0, AizPlaneIntroInstance.EXPLOSION_TRIGGER_X);
    }

    @Test
    public void initLocksPlayerButDoesNotFreezeCamera() {
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x40);
        player.setCentreY((short) 0x420);

        camera.setFocusedSprite(player);
        camera.setX((short) 0x200);
        camera.setY((short) 0x200);
        camera.setFrozen(false);

        intro.update(0, player);

        assertEquals(2, intro.getRoutine());
        assertTrue(player.isControlLocked());
        assertTrue(player.isObjectControlled());
        assertTrue(player.isHidden());
        assertEquals(0, player.getMappingFrame(),
                "Obj_AIZPlaneIntro publishes mapping_frame=0 while it owns Player 1");
        assertTrue(player.isObjectMappingFrameControl(),
                "object_control=$53 bit 1 suppresses Animate_Sonic during the intro");
        // ROM: camera is NOT frozen; it stays at origin naturally via
        // Level_started_flag. Intro objects use screen-coordinate rendering.
        assertFalse(camera.getFrozen());
    }

    @Test
    public void initLeavesCpuSidekickAvailableForSonicAndTailsIntro() {
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x40);
        player.setCentreY((short) 0x420);
        Tails sidekick = new Tails("tails_p2", (short) 0x20, (short) 0x424);
        sidekick.setCpuControlled(true);
        GameServices.sprites().addSprite(sidekick, "tails");

        AizPlaneIntroInstance.resetIntroPhaseState();
        intro.update(0, player);

        assertEquals(java.util.List.of(sidekick), GameServices.sprites().getSidekicks(),
                "S&K SpawnLevelMainSprites creates Player_2 and leaves Tails_CPU_routine native; "
                        + "Obj_AIZPlaneIntro only controls Player_1.");
    }

    @Test
    public void initLocksFocusedPlayerWhenPlayerParamIsNull() {
        Sonic focusedPlayer = new Sonic("sonic", (short) 0, (short) 0);
        focusedPlayer.setCentreX((short) 0x40);
        focusedPlayer.setCentreY((short) 0x420);

        camera.setFocusedSprite(focusedPlayer);
        camera.setFrozen(false);

        intro.update(0, null);

        assertEquals(2, intro.getRoutine());
        assertTrue(focusedPlayer.isControlLocked());
        assertTrue(focusedPlayer.isObjectControlled());
        assertTrue(focusedPlayer.isHidden());
        assertFalse(camera.getFrozen());
    }

    @Test
    public void introEventuallyPassesKnucklesTriggerGate() {
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x40);
        player.setCentreY((short) 0x420);

        camera.setFocusedSprite(player);

        for (int frame = 0; frame < 2500 && intro.getRoutine() < 24; frame++) {
            intro.update(frame, player);
        }

        assertTrue(intro.getRoutine() >= 24, "Intro routine should progress past Knuckles trigger gate");
    }

    @Test
    public void introProgressesPastKnucklesGateWhenPlayerParamIsNull() {
        Sonic focusedPlayer = new Sonic("sonic", (short) 0, (short) 0);
        focusedPlayer.setCentreX((short) 0x40);
        focusedPlayer.setCentreY((short) 0x420);

        camera.setFocusedSprite(focusedPlayer);

        for (int frame = 0; frame < 2500 && intro.getRoutine() < 24; frame++) {
            intro.update(frame, null);
        }

        assertTrue(intro.getRoutine() >= 24, "Intro routine should progress past Knuckles trigger gate with focused fallback");
        assertTrue(focusedPlayer.getCentreX() > 0x40, "Focused player should advance rightward after intro scroll gate opens");
    }

    @Test
    public void waveSpawnsContinueThroughFinalWaitAndKnucklesMonitor() throws Exception {
        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x40);
        player.setCentreY((short) 0x420);
        camera.setFocusedSprite(player);

        int frame = 0;
        while (frame < 1500 && intro.getRoutine() < 20) {
            intro.update(frame++, player);
        }
        assertEquals(20, intro.getRoutine(), "setup should reach the final pre-Knuckles wait");

        int beforeFinalWait = activeWaveCount(intro);
        for (int i = 0; i < AizPlaneIntroInstance.WAVE_SPAWN_INTERVAL + 1; i++) {
            intro.update(frame++, player);
        }
        assertTrue(activeWaveCount(intro) > beforeFinalWait,
                "ROM Obj_Wait keeps firing the wave callback during routine $14");

        while (frame < 1600 && intro.getRoutine() < 22) {
            intro.update(frame++, player);
        }
        assertEquals(22, intro.getRoutine(), "setup should reach the Knuckles monitor wait");

        int beforeMonitor = activeWaveCount(intro);
        for (int i = 0; i < AizPlaneIntroInstance.WAVE_SPAWN_INTERVAL + 1; i++) {
            player.setCentreX((short) 0x800);
            intro.update(frame++, player);
        }
        assertTrue(activeWaveCount(intro) > beforeMonitor,
                "ROM Obj_Wait keeps firing the wave callback while waiting for the Knuckles trigger");
    }

    private static int activeWaveCount(AizPlaneIntroInstance intro) throws Exception {
        Field field = AizPlaneIntroInstance.class.getDeclaredField("activeWaves");
        field.setAccessible(true);
        return ((List<?>) field.get(intro)).size();
    }
}
