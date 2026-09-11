package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPachinkoRegistry {

    private static Sonic3kObjectRegistry pachinkoRegistry() {
        return new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return 0x14;
            }
        };
    }

    @Test
    public void registryCreatesPachinkoBumper() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.BUMPER, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoBumperObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoTriangleBumper() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_TRIANGLE_BUMPER, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoTriangleBumperObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoFlipper() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_FLIPPER, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoFlipperObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoEnergyTrap() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_ENERGY_TRAP, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoEnergyTrapObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoPlatform() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_PLATFORM, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoPlatformObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoItemOrb() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_ITEM_ORB, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoItemOrbObjectInstance);
    }

    @Test
    public void registryCreatesPachinkoMagnetOrb() {
        Sonic3kObjectRegistry registry = pachinkoRegistry();
        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.PACHINKO_MAGNET_ORB, 0, 0, false, 0));
        assertTrue(instance instanceof PachinkoMagnetOrbObjectInstance);
    }
}


