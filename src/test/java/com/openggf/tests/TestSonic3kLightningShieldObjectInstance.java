package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic3k.objects.LightningShieldObjectInstance;
import com.openggf.game.sonic3k.objects.LightningSparkObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TestSonic3kLightningShieldObjectInstance {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void abilityCreatesFourSparksWithoutLoadedArt() {
        ObjectManager objectManager = mock(ObjectManager.class);
        AbstractPlayableSprite sonic = new Tails("sonic", (short) 0x693, (short) 0x67F);
        sonic.setCentreX((short) 0x693);
        sonic.setCentreY((short) 0x67F);
        LightningShieldObjectInstance shield = new LightningShieldObjectInstance(sonic);
        shield.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        shield.triggerSparks();

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(captor.capture());
        List<ObjectInstance> sparks = captor.getAllValues().stream()
                .sorted(Comparator.comparingInt(ObjectInstance::getX)
                        .thenComparingInt(ObjectInstance::getY))
                .toList();
        for (ObjectInstance spark : sparks) {
            assertInstanceOf(LightningSparkObjectInstance.class, spark);
        }
        assertEquals(0x693, sparks.get(0).getX());
        assertEquals(0x67F, sparks.get(0).getY());

        for (ObjectInstance spark : sparks) {
            spark.update(0, sonic);
        }
        List<String> positionsAfterFirstMove = sparks.stream()
                .map(spark -> String.format("%04X,%04X", spark.getX() & 0xFFFF, spark.getY() & 0xFFFF))
                .sorted()
                .toList();
        assertEquals(List.of("0691,067D", "0691,0681", "0695,067D", "0695,0681"),
                positionsAfterFirstMove,
                "Obj_LightningShield_CreateSpark seeds +/-$200 velocities before MoveSprite2");
    }

    @Test
    void abilityDefersSparkAllocationUntilShieldObjectTurn() {
        ObjectManager objectManager = mock(ObjectManager.class);
        AbstractPlayableSprite sonic = new Tails("sonic", (short) 0x693, (short) 0x67F);
        LightningShieldObjectInstance shield = new LightningShieldObjectInstance(sonic);
        shield.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        shield.onAbilityActivated(1);
        verify(objectManager, times(0)).addDynamicObjectAfterCurrent(org.mockito.ArgumentMatchers.any());
        verify(objectManager, times(4)).queueDynamicObjectAfterExec(org.mockito.ArgumentMatchers.any());
    }
}
