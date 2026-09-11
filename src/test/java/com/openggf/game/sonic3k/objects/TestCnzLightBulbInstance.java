package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzLightBulbInstance {

    /**
     * {@code Sonic3kObjectRegistry.getCurrentZoneSet()} resolves through
     * {@code AbstractObjectRegistry.currentRomZoneId()}, which reads the
     * ambient {@code GameServices.levelOrNull()}. Without this reset, a
     * gameplay session left installed by an earlier test in the same fork puts
     * the registry in the {@code SKL} zone set, where this object id is
     * {@code SOZPushSwitch} rather than {@code CNZLightBulb} — the class
     * passes alone and fails only at certain suite orderings. The sibling
     * object tests in this package already reset for the same reason.
     */
    @BeforeEach
    void resetAmbientSessionState() {
        TestEnvironment.resetAll();
    }

    @Test
    void registryCreatesRealCnzLightBulbForPlacedAct2Object() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance object = registry.create(new ObjectSpawn(
                0x0E00, 0x0390, Sonic3kObjectIds.CNZ_LIGHT_BULB, 0, 0, false, 0));

        assertEquals("CNZLightBulb", object.getName());
        assertNotEquals("PlaceholderObjectInstance", object.getClass().getSimpleName(),
                "Obj_CNZLightBulb is placed in CNZ Act 2 and should not fall back to a placeholder");
    }

    @Test
    void lightBulbSwitchesToSubmergedFrameOnceWaterRisesAboveIt() {
        CnzLightBulbInstance lightBulb =
                new CnzLightBulbInstance(new ObjectSpawn(
                        0x0E00, 0x0390, Sonic3kObjectIds.CNZ_LIGHT_BULB, 0, 0, false, 0));

        assertEquals(0, lightBulb.getRenderFrame());
        assertFalse(lightBulb.isSubmerged());

        lightBulb.updateWaterState(true, 0x0380);
        assertEquals(1, lightBulb.getRenderFrame());
        assertTrue(lightBulb.isSubmerged());
    }
}
