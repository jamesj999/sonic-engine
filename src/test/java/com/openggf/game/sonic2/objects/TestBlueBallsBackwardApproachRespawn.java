package com.openggf.game.sonic2.objects;

import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard: the CPZ1 blue-balls cluster at X=0x1110 (Obj1D, subtype 0x15
 * = parent + 5 siblings) must respawn when the camera returns to it from the RIGHT
 * (a leftward/backward approach), not only when first approached from the left.
 *
 * <p>The player route that exposed the bug: run right past the cluster at a low Y,
 * land on the CPZ spring, get launched up to the cluster's height, then hold LEFT
 * back onto it. The cluster scrolled off-screen and self-deleted on the way out;
 * on the return it must re-appear.
 *
 * <p>The fix aligns {@code BlueBallsObjectInstance.checkMarkObjGone()} with the ROM
 * {@code Camera_X_pos_coarse} reference ({@code (cameraX - 128) & 0xFF80}); without
 * the 128px left margin the re-loaded ball was culled one chunk too early on the
 * left and never survived the return approach.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestBlueBallsBackwardApproachRespawn {
    private static final int ZONE_CPZ = 1;
    private static final int ACT_1 = 0;
    private static final int CLUSTER_X = 0x1110; // 4368
    private static final int CLUSTER_Y = 648;
    private static final int EXPECTED_CLUSTER_SIZE = 6; // parent + 5 siblings (subtype 0x15)

    private static SharedLevel sharedLevel;
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_CPZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
    }

    private int activeBallsNearCluster() {
        int n = 0;
        for (ObjectInstance o : GameServices.level().getObjectManager().getActiveObjects()) {
            if (o instanceof BlueBallsObjectInstance bb && Math.abs(bb.getX() - CLUSTER_X) <= 48) {
                n++;
            }
        }
        return n;
    }

    private void placeAt(int x) {
        fixture.sprite().setCentreX((short) x);
        fixture.sprite().setCentreY((short) CLUSTER_Y);
        fixture.sprite().setAir(false);
        fixture.sprite().setYSpeed((short) 0);
    }

    @Test
    public void clusterRespawnsWhenApproachedFromTheRight() {
        ObjectManager om = GameServices.level().getObjectManager();

        // Baseline: a fresh approach from the left spawns the full cluster.
        placeAt(0x1040);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        om.reset(fixture.camera().getX());
        fixture.stepFrame(false, false, false, false, false);
        assertEquals(EXPECTED_CLUSTER_SIZE, activeBallsNearCluster(),
                "fresh approach from the left should spawn the full blue-balls cluster");

        // Scroll RIGHT well past the cluster so it leaves the screen and self-deletes.
        for (int x = 0x1040; x <= 0x1600; x += 6) {
            placeAt(x);
            fixture.camera().updatePosition(false);
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(0, activeBallsNearCluster(), "cluster should be unloaded after scrolling well past it");

        // Return: scroll BACK LEFT onto the cluster. It must respawn.
        int maxOnReturn = 0;
        for (int x = 0x1600; x >= 0x1080; x -= 6) {
            placeAt(x);
            fixture.camera().updatePosition(false);
            fixture.stepFrame(false, false, false, false, false);
            maxOnReturn = Math.max(maxOnReturn, activeBallsNearCluster());
        }
        assertTrue(maxOnReturn >= EXPECTED_CLUSTER_SIZE,
                "blue-balls cluster must respawn on the leftward/backward approach (saw max "
                        + maxOnReturn + " of " + EXPECTED_CLUSTER_SIZE + ")");
    }
}
