package com.openggf.game.sonic2;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2CnzSlotMachineOrdering {

    private Sonic2ZoneFeatureProvider provider;
    private CNZSlotMachineManager slotMachine;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
        TestEnvironment.activeGameplayMode();
        provider = new Sonic2ZoneFeatureProvider();
        slotMachine = new CNZSlotMachineManager();
        Field field = Sonic2ZoneFeatureProvider.class.getDeclaredField("cnzSlotMachineManager");
        field.setAccessible(true);
        field.set(provider, slotMachine);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void prePhysicsDoesNotRunThePostObjectSlotMachineRoutine() {
        provider.updatePrePhysics(null, 0, Sonic2ZoneConstants.ROM_ZONE_CNZ);

        assertEquals(0x00, slotMachine.snapshot().routine(),
                "LevEvents_CNZ reaches SlotMachine through DeformBgLayer after RunObjects "
                        + "(s2.asm:5095,5098,15175,21511-21512).");
    }

    @Test
    void postObjectCallbacksRunTheZoneGlobalRoutineOnlyOncePerVint() {
        provider.updateAfterObjectExecution(null, 0, Sonic2ZoneConstants.ROM_ZONE_CNZ);
        CNZSlotMachineManager.Snapshot afterMainCallback = slotMachine.snapshot();

        provider.updateAfterObjectExecution(null, 0, Sonic2ZoneConstants.ROM_ZONE_CNZ);

        assertEquals(0x04, afterMainCallback.routine());
        assertEquals(afterMainCallback, slotMachine.snapshot(),
                "SlotMachine is one LevEvents_CNZ routine, not one routine per playable slot.");
    }
}
