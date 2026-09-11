package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StillSprite (obj 0x2F) loops in the ROM's {@code Sprite_OnScreen_Test}
 * (sonic3k.asm:37262-37277): it deletes only when its chunk-aligned X leaves
 * the coarse window {@code (camX-128)&0xFF80 .. +0x280}, NOT the exact screen.
 * Placement spawns objects beyond the screen edge, so an exact-screen check
 * kills every StillSprite on its first update before it can be seen (HCZ
 * waterfall curtains / HCZ2 tube-crossing pieces vanish in blocks).
 */
class TestStillSpriteOnScreenRange {

    @AfterEach
    void resetBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    private static StillSpriteInstance stillSpriteAt(int x) {
        // HCZ2 tube crossover piece: subtype 0x12.
        return new StillSpriteInstance(new ObjectSpawn(x, 844, 0x2F, 0x12, 0, true, 844, 0));
    }

    @Test
    void survivesFirstUpdateJustBeyondTheRightScreenEdge() {
        // Camera at 1000: screen is [1000,1320], coarse window keeps
        // chunk-aligned X while (x&0xFF80) - ((1000-128)&0xFF80) <= 0x280.
        AbstractObjectInstance.updateCameraBounds(1000, 0, 1320, 224, 0);

        StillSpriteInstance justSpawned = stillSpriteAt(1400);
        justSpawned.update(0, null);
        assertFalse(justSpawned.isDestroyed(),
                "sprite inside the ROM coarse window must survive even though it is off the exact screen");
    }

    @Test
    void despawnsOnceOutsideTheCoarseWindow() {
        AbstractObjectInstance.updateCameraBounds(1000, 0, 1320, 224, 0);

        StillSpriteInstance farAhead = stillSpriteAt(1800);
        farAhead.update(0, null);
        assertTrue(farAhead.isDestroyed(),
                "sprite beyond (camX-128)&0xFF80 + 0x280 must delete like Sprite_OnScreen_Test");
        assertTrue(farAhead.isDestroyedRespawnable(),
                "Sprite_OnScreen_Test clears the respawn bit so placement can respawn it");
    }
}
