package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestAizFallingLogObjectInstance {

    @Test
    void spawnerUsesProcessSpritesVisibleLevelFrameCounter() {
        CountingSpawner spawner = new CountingSpawner(
                new ObjectSpawn(0x2000, 0x0400, Sonic3kObjectIds.AIZ_FALLING_LOG,
                        0x00, 0, false, 0));
        spawner.setServices(new TestObjectServices());

        spawner.processSpritesVisibleFrame = 1;
        spawner.update(0, null);
        assertEquals(0, spawner.spawnCount,
                "stored level frame 0 is visible as frame 1 during Process_Sprites, so mask 1 rejects it");

        spawner.processSpritesVisibleFrame = 2;
        spawner.update(1, null);
        assertEquals(2, spawner.spawnCount,
                "stored level frame 1 is visible as frame 2 and spawns the paired log/splash children");
    }

    @Test
    void fallingLogUsesExactSolidObjectTopHeight() {
        AizFallingLogObjectInstance.FallingLogChild log =
                new AizFallingLogObjectInstance.FallingLogChild(
                        0x2000, 0x0400, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG);

        assertEquals(8, log.getSolidParams().airHalfHeight());
        assertEquals(8, log.getSolidParams().groundHalfHeight(),
                "loc_2B6D8 passes d3=8 to SolidObjectTop for both first landing and standing carry");
    }

    private static final class CountingSpawner extends AizFallingLogObjectInstance {
        private int spawnCount;
        private int processSpritesVisibleFrame;

        private CountingSpawner(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected void spawnDynamicObject(AbstractObjectInstance object) {
            spawnCount++;
        }

        @Override
        protected int levelFrameCounterForSpawner(int fallbackFrameCounter) {
            return processSpritesVisibleFrame;
        }
    }
}
