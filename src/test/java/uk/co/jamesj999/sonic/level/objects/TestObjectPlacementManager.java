package uk.co.jamesj999.sonic.level.objects;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestObjectPlacementManager {
    @Test
    public void testWindowingAndRememberedObjects() {
        ObjectSpawn spawnA = new ObjectSpawn(0, 0, 0x01, 0, 0, false, 0);
        ObjectSpawn spawnB = new ObjectSpawn(800, 0, 0x02, 0, 0, false, 0);

        ObjectManager.Placement manager = new ObjectManager.Placement(List.of(spawnA, spawnB));
        manager.reset(0);

        assertTrue(manager.getActiveSpawns().contains(spawnA));
        assertFalse(manager.getActiveSpawns().contains(spawnB));

        manager.update(500);
        assertTrue(manager.getActiveSpawns().contains(spawnB));

        manager.markRemembered(spawnB);
        assertFalse(manager.getActiveSpawns().contains(spawnB));

        manager.update(2000);
        assertFalse(manager.getActiveSpawns().contains(spawnB));

        manager.update(0);
        assertFalse(manager.getActiveSpawns().contains(spawnB));
    }
}
