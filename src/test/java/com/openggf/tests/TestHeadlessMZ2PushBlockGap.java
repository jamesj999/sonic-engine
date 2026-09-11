package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.objects.Sonic1PushBlockObjectInstance;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for MZ Act 2 push block gap.
 * <p>
 * In MZ2, a push block (33:81 R) sits above a gap. The player must push it
 * far enough rightward that Sonic falls through the gap underneath. The push
 * must continue even when Sonic's floor sensors momentarily straddle the gap
 * boundary (causing brief airborne frames), matching the ROM where the push
 * handler uses d0 (displacement) rather than the pushing status flag.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestHeadlessMZ2PushBlockGap {

    private static final int ZONE_MZ = 1;
    private static final int ACT_2 = 1;

    private static final int START_CENTRE_X = 634;
    private static final int START_CENTRE_Y = 1337;

    private static final int MAX_FRAMES = 600;
    private static final int MIN_FALL_DISTANCE = 32;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_MZ, ACT_2);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    public void testPushBlockClearsGapAndSonicFalls() {
        Sonic sprite = (Sonic) fixture.sprite();

        sprite.setCentreX((short) START_CENTRE_X);
        sprite.setCentreY((short) START_CENTRE_Y);
        sprite.setAir(false);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);

        fixture.camera().updatePosition(true);

        // Let Sonic settle onto ground
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        Sonic1PushBlockObjectInstance block = findNearestPushBlock(sprite);
        assertNotNull(block, "Should find a push block near Sonic");

        int blockStartX = block.getX();
        int startY = sprite.getCentreY();

        // Hold right to push the block
        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int currentY = sprite.getCentreY();
            if (currentY > startY + MIN_FALL_DISTANCE) {
                // Sonic fell through the gap
                return;
            }
        }

        fail("Sonic should have fallen through the gap after pushing the block in MZ2, "
                + "but his Y only reached " + sprite.getCentreY()
                + " (started at " + startY + ", needed drop of " + MIN_FALL_DISTANCE + "px). "
                + "Block: " + blockStartX + " -> " + block.getX()
                + " (moved " + (block.getX() - blockStartX) + "px)");
    }

    private Sonic1PushBlockObjectInstance findNearestPushBlock(Sonic sprite) {
        LevelManager lm = GameServices.level();
        if (lm == null || lm.getObjectManager() == null) return null;

        Collection<ObjectInstance> objects = lm.getObjectManager().getActiveObjects();
        Sonic1PushBlockObjectInstance nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (ObjectInstance obj : objects) {
            if (obj instanceof Sonic1PushBlockObjectInstance pb) {
                int dist = Math.abs(pb.getX() - sprite.getCentreX())
                         + Math.abs(pb.getY() - sprite.getCentreY());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = pb;
                }
            }
        }
        return nearest;
    }
}


