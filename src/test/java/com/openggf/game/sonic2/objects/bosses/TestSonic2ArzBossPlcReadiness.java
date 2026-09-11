package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2ArzBossPlcReadiness {
    private static final int ARZ_PILLAR_WINDOW_X = 0x2AE0;

    private Sonic2PlcService plc;
    private Sonic2ARZBossInstance boss;
    private AbstractPlayableSprite player;
    private AbstractPlayableSprite sidekick;
    private ObjectManager objectManager;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic2PlcService.class);
        player = Mockito.mock(AbstractPlayableSprite.class);
        sidekick = Mockito.mock(AbstractPlayableSprite.class);
        objectManager = Mockito.mock(ObjectManager.class);
        when(player.getCentreX()).thenReturn((short) ARZ_PILLAR_WINDOW_X);
        when(sidekick.getCentreX()).thenReturn((short) ARZ_PILLAR_WINDOW_X);

        TestObjectServices services = new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> player, () -> java.util.List.of(sidekick));
            }
        }.withGameModule(GameServices.module())
                .withGameState(Mockito.mock(GameStateManager.class));

        boss = new Sonic2ARZBossInstance(new ObjectSpawn(0, 0, 0x89, 0, 0, false, 0));
        boss.setServices(services);
    }

    @Test
    void fullArzInitWaitsForFifoThenRunsP2GateAndPillarSetupOnFirstEmptyScan() throws Exception {
        plc.append(0);
        plc.append(43);

        updateBoss();

        assertFalse(initialized(), "any queued descriptor must hold ARZ initialization");
        verifyNoInteractions(objectManager);
        verifyNoInteractions(player, sidekick);

        drain();
        when(sidekick.getCentreX()).thenReturn((short) 0x2900);
        updateBoss();

        assertFalse(initialized(), "after readiness, native P2 still has to reach the pillar window");
        verify(sidekick).getCentreX();
        verifyNoInteractions(objectManager);

        when(sidekick.getCentreX()).thenReturn((short) ARZ_PILLAR_WINDOW_X);
        updateBoss();

        assertTrue(initialized(), "the first empty object scan with P1/P2 in range initializes ARZ");
        verify(objectManager, times(2)).addDynamicObject(any(ARZBossPillar.class));
    }

    @Test
    void sidekickFlightBypassIsEvaluatedOnlyAfterPlcReadiness() throws Exception {
        plc.append(43);
        when(sidekick.getCentreX()).thenReturn((short) 0x2900);
        when(sidekick.isObjectControlled()).thenReturn(true);
        when(sidekick.isObjectControlAllowsCpu()).thenReturn(false);
        when(sidekick.isObjectControlSuppressesMovement()).thenReturn(true);

        updateBoss();
        assertFalse(initialized());
        verifyNoInteractions(player, sidekick, objectManager);

        drain();
        updateBoss();

        assertTrue(initialized(), "Obj89's $81 sidekick-flight state bypasses its X-range gate after PLC release");
        verify(objectManager, times(2)).addDynamicObject(any(ARZBossPillar.class));
    }

    private void updateBoss() {
        boss.update(1, player);
    }

    private boolean initialized() throws Exception {
        Field field = Sonic2ARZBossInstance.class.getDeclaredField("initialized");
        field.setAccessible(true);
        return field.getBoolean(boss);
    }

    private void drain() {
        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceLevelVBlank();
        }
    }
}
