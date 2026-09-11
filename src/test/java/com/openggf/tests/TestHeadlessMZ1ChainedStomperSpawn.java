package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ChainedStomperObjectInstance;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for left-side object spawning in MZ Act 1.
 * <p>
 * The ROM calculates spawn window boundaries using chunk-aligned camera X:
 * <ul>
 *   <li>Backward boundary: {@code (cameraX & 0xFF80) - 0x80}</li>
 *   <li>Forward boundary: {@code (cameraX & 0xFF80) + 0x280}</li>
 * </ul>
 * Our engine was using raw {@code cameraX} instead of chunk-aligned, causing
 * the window to shift right by up to 127px. This made objects on the left side
 * of the window fall outside the spawn range when the camera was near the top
 * of a chunk boundary.
 * <p>
 * When Sonic is at centre (2826, 931) in MZ1, a ChainedStomper at (2656, 892)
 * should be within the spawn window. The ROM backward boundary at chunk-aligned
 * camera is ~2560, while our raw-cameraX boundary was ~2546-2682 depending on
 * exact camera position within the chunk.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestHeadlessMZ1ChainedStomperSpawn {

    private static final int ZONE_MZ = 1;
    private static final int ACT_1 = 0;

    /** Player centre position where the bug is observed. */
    private static final int SONIC_CENTRE_X = 2826;
    private static final int SONIC_CENTRE_Y = 931;

    /** ChainedStomper X position from the MZ1 layout. */
    private static final int STOMPER_X = 2656;

    /** Allow enough frames for the spawn window to stabilise. */
    private static final int SETTLE_FRAMES = 10;

    private static final int CHUNK_MASK = 0xFF80;
    private static final int UNLOAD_BEHIND = 0x80;
    private static final int LOAD_AHEAD = 0x280;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_MZ, ACT_1);
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

    /**
     * Verifies the ChainedStomper at (2656, 892) is in the active spawn set
     * when Sonic is at (2826, 931).
     * <p>
     * With camera at ~2674, the ROM backward boundary is (2674 & 0xFF80) - 0x80
     * = 2560 - 128 = 2432. The stomper at 2656 is well within range.
     * Our engine must use chunk-aligned boundaries to match.
     */
    @Test
    public void testChainedStomperToLeftOfScreenIsSpawned() {
        Sonic sprite = (Sonic) fixture.sprite();
        placeAndSettle(sprite);

        ObjectManager om = GameServices.level().getObjectManager();
        assertNotNull(om, "ObjectManager should exist");

        int cameraX = fixture.camera().getX();

        // Verify the stomper exists in the layout
        ObjectSpawn stomperSpawn = findStomperInLayout(om, STOMPER_X);
        assertNotNull(stomperSpawn, "ChainedStomper should exist in MZ1 layout at x=" + STOMPER_X);

        // Verify it is in the active spawn set
        assertTrue(om.getActiveSpawns().contains(stomperSpawn), "ChainedStomper at (" + stomperSpawn.x() + ", " + stomperSpawn.y()
                        + ") should be in the active spawn set. Camera X=" + cameraX
                        + ", chunk-aligned backward boundary="
                        + ((cameraX & CHUNK_MASK) - UNLOAD_BEHIND));

        // Verify it has been instantiated
        boolean instanceExists = om.getActiveObjects().stream()
                .anyMatch(obj -> obj instanceof Sonic1ChainedStomperObjectInstance);
        assertTrue(instanceExists, "ChainedStomper instance should exist. Camera X=" + cameraX);
    }

    /**
     * Verifies that the spawn window boundaries match the ROM algorithm.
     * <p>
     * The ROM uses chunk-aligned boundaries:
     * <pre>
     *   backward = (cameraX &amp; 0xFF80) - 0x80
     *   forward  = (cameraX &amp; 0xFF80) + 0x280
     * </pre>
     * When cameraX is near the top of a chunk (e.g., 0x7F above a boundary),
     * the raw-cameraX backward boundary is up to 127px further right than
     * the ROM's. This test checks that objects in the ROM's window but not
     * the raw-cameraX window are still spawned.
     */
    @Test
    public void testSpawnWindowUsesChunkAlignedBoundaries() {
        Sonic sprite = (Sonic) fixture.sprite();
        placeAndSettle(sprite);

        ObjectManager om = GameServices.level().getObjectManager();
        assertNotNull(om, "ObjectManager should exist");

        int cameraX = fixture.camera().getX();
        int chunkAligned = cameraX & CHUNK_MASK;

        // ROM boundaries
        int romBackward = Math.max(0, chunkAligned - UNLOAD_BEHIND);
        int romForward = chunkAligned + LOAD_AHEAD;

        // Check that ALL objects within the ROM's window are in the active set
        int missingCount = 0;
        StringBuilder missing = new StringBuilder();
        for (ObjectSpawn spawn : om.getAllSpawns()) {
            // ROM: bls (Branch if Lower or Same) means objects at exactly the forward
            // boundary are NOT spawned (windowEnd <= objectX â†’ skip). Use strict less-than.
            if (spawn.x() >= romBackward && spawn.x() < romForward) {
                if (!om.getActiveSpawns().contains(spawn)) {
                    missingCount++;
                    missing.append(String.format("  id=0x%02X at (%d, %d)%n",
                            spawn.objectId(), spawn.x(), spawn.y()));
                }
            }
        }

        assertEquals(0, missingCount, "All objects within ROM-parity window [" + romBackward + ", " + romForward
                        + "] should be in active set (camera X=" + cameraX
                        + ", chunk=" + chunkAligned + "). Missing:\n" + missing);
    }

    private void placeAndSettle(Sonic sprite) {
        sprite.setCentreX((short) SONIC_CENTRE_X);
        sprite.setCentreY((short) SONIC_CENTRE_Y);
        sprite.setAir(false);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);

        fixture.camera().updatePosition(true);

        for (int i = 0; i < SETTLE_FRAMES; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
    }

    private ObjectSpawn findStomperInLayout(ObjectManager om, int nearX) {
        for (ObjectSpawn spawn : om.getAllSpawns()) {
            if (spawn.objectId() == Sonic1ObjectIds.CHAINED_STOMPER
                    && Math.abs(spawn.x() - nearX) < 10) {
                return spawn;
            }
        }
        return null;
    }
}


