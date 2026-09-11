package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.objects.boss.BossStateContext;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1FinalZonePlcIntegration {
    private Sonic1PlcService plc;
    private Sonic1FZBossInstance boss;
    private GameRng rng;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic1PlcService.class);
        rng = new GameRng(GameRng.Flavour.S1_S2);
        GameServices.camera().setX((short) 0x2450);
        boss = new Sonic1FZBossInstance(new ObjectSpawn(0, 0, 0x85, 0, 0, false, 0));
        boss.setServices(new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }
        }.withGameModule(GameServices.module())
                .withCamera(GameServices.camera())
                .withRng(rng));
    }

    @Test
    void bossWaitsForWholeQueueAndAdvancesRngEveryBusyFrame() throws Exception {
        plc.append(0);
        plc.append(31);
        long seed = rng.getSeed();

        runWait();

        assertEquals(0, routineSecondary(), "any queued descriptor must hold Final Zone initialization");
        assertEquals(seed + 1, rng.getSeed(), "the ROM wait-tail RNG increment runs while PLC work remains");

        drain();
        runWait();
        assertEquals(2, routineSecondary(), "the first empty object scan releases the boss that frame");
    }

    @Test
    void finalServiceAndCameraThresholdReleaseBossAndAdvanceRngOnTheSameFrame() throws Exception {
        GameServices.camera().setX((short) 0x244F);
        plc.append(31);
        long seedBeforeRelease = rng.getSeed();

        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceLevelVBlank();
        }
        assertTrue(!plc.isBusy(), "the VBlank service must empty the ROM descriptor before the object scan");

        GameServices.camera().setX((short) 0x2450);
        runWait();

        assertEquals(2, routineSecondary(), "the threshold crossed on the just-emptied queue frame releases FZ");
        assertEquals(seedBeforeRelease + 1, rng.getSeed(),
                "the FZ wait-tail RNG increment occurs on that same release frame");
    }

    @Test
    void unrelatedEarlierEntryExtendsTheBossWait() throws Exception {
        plc.append(31);
        int bossOnlyFrames = drainFrames();
        plc.clearQueued();

        plc.append(0);
        plc.append(31);
        int mixedFrames = drainFrames();

        assertTrue(mixedFrames > bossOnlyFrames,
                "the boss must observe the whole FIFO, including unrelated earlier work");
    }

    private void runWait() throws Exception {
        Method method = Sonic1FZBossInstance.class.getDeclaredMethod("updateWait");
        method.setAccessible(true);
        method.invoke(boss);
    }

    private int routineSecondary() throws Exception {
        Field field = AbstractBossInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return ((BossStateContext) field.get(boss)).routineSecondary;
    }

    private int drainFrames() {
        int frames = 0;
        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceLevelVBlank();
            frames++;
        }
        return frames;
    }

    private void drain() {
        drainFrames();
    }
}
