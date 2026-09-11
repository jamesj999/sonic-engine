package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2PlcArtRegistry;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2OOZBossPath {

    @Test
    void registryCreatesObj55OozBoss() {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();

        assertEquals(0x55, Sonic2ObjectIds.OOZ_BOSS);
        assertTrue(registry.hasRegisteredFactory(Sonic2ObjectIds.OOZ_BOSS));

        ObjectInstance instance = registry.create(new ObjectSpawn(
                0, 0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0));

        assertInstanceOf(Sonic2OOZBossInstance.class, instance);
    }

    @Test
    void plcRegistryMapsOozBossArtToObj55Sheet() {
        Sonic2PlcArtRegistry.ArtRegistration registration =
                Sonic2PlcArtRegistry.lookup(Sonic2Constants.ART_NEM_OOZ_BOSS_ADDR);

        assertNotNull(registration);
        assertEquals(Sonic2ObjectArtKeys.OOZ_BOSS, registration.key());
    }

    @Test
    void mainVehicleInitialStateMatchesObj55MainInit() {
        Sonic2OOZBossInstance boss = new Sonic2OOZBossInstance(new ObjectSpawn(
                0, 0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices());

        // Obj55_Init only sets boss_subtype and returns; Obj55_Main_Init runs on the
        // next object pass (s2.asm:68225-68238,68258-68276), so drive one update.
        boss.update(0, null);

        assertEquals(0x2940, boss.getState().x);
        assertEquals(0x02D0, boss.getState().y);
        assertEquals(0x02, boss.getBossSubtypeForTesting());
        assertEquals(0x02, boss.getState().routineSecondary);
        assertEquals(-0x80, boss.getState().yVel);
        assertEquals(8, boss.getState().hitCount);
        assertEquals(8, boss.getMainFrameForTesting());
        assertEquals(1, boss.getChildSpriteCountForTesting());
        assertEquals(0, boss.getStatusForTesting());
        assertEquals(0xCF, boss.getCollisionFlags());
    }

    @Test
    void mainSurfaceWaitsUntilBossYHighWordDropsBelowSurfaceTarget() {
        Sonic2OOZBossInstance boss = new Sonic2OOZBossInstance(new ObjectSpawn(
                0, 0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices());

        // Run the deferred Obj55_Main_Init pass before seeding the surface-rise state.
        boss.update(0, null);

        boss.getState().yFixed = 0x0291_0000;
        boss.getState().y = 0x0291;
        boss.getState().yVel = -0x80;

        boss.update(1, null);

        assertEquals(0x02, boss.getState().routineSecondary);
        assertEquals(0x0290_8000, boss.getState().yFixed);

        boss.update(2, null);
        assertEquals(0x02, boss.getState().routineSecondary);
        assertEquals(0x0290_0000, boss.getState().yFixed);

        boss.update(3, null);
        assertEquals(0x04, boss.getState().routineSecondary);
        assertEquals(0x0290_8000, boss.getState().yFixed);
    }

    @Test
    void mainDiveWaitsUntilBossYHighWordDropsBelowPeakTargetBeforeFalling() {
        Sonic2OOZBossInstance boss = new Sonic2OOZBossInstance(new ObjectSpawn(
                0, 0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices());

        boss.getState().routineSecondary = 0x06;
        boss.getState().yFixed = 0x028C_4000;
        boss.getState().y = 0x028C;
        boss.getState().yVel = -0x40;

        boss.update(1, null);

        assertEquals(0x06, boss.getState().routineSecondary);
        assertEquals(-0x40, boss.getState().yVel);
        assertEquals(0, boss.getStatusForTesting());
        assertEquals(0x028C_0000, boss.getState().yFixed);

        boss.update(2, null);

        assertEquals(0x06, boss.getState().routineSecondary);
        assertEquals(0x80, boss.getState().yVel);
        assertEquals(0x40, boss.getStatusForTesting());
        assertEquals(0x028C_C000, boss.getState().yFixed);
    }

    @Test
    void laserShooterRiseWaitsUntilBossYHighWordDropsBelowTopTarget() {
        Sonic2OOZBossInstance boss = new Sonic2OOZBossInstance(new ObjectSpawn(
                0, 0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices());

        setIntField(boss, "bossSubtype", 0x04);
        boss.getState().routineSecondary = 0x00;
        boss.update(1, null);
        assertEquals(0x04, boss.getBossSubtypeForTesting());
        assertEquals(0x02, boss.getState().routineSecondary);

        boss.getState().yFixed = 0x0240_8000;
        boss.getState().y = 0x0240;
        boss.getState().yVel = -0x80;

        boss.update(2, null);

        assertEquals(0x02, boss.getState().routineSecondary);
        assertEquals(0x0240_0000, boss.getState().yFixed);
        assertEquals(-0x80, boss.getState().yVel);

        boss.update(3, null);

        assertEquals(0x04, boss.getState().routineSecondary);
        assertEquals(0x0240_8000, boss.getState().yFixed);
        assertEquals(0, boss.getState().yVel);
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to set field " + fieldName, e);
        }
    }
}
