package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.physics.Direction;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kHcz1GroundWallProbe {
    private static SharedLevel sharedLevel;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_HCZ, 0);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    void hcz1FlatRightWallProbeDoesNotPushAtTheEmptyCellEdge() {
        // ROM BizHawk capture (hcz_tails_wallprobe.txt): on the non-push frames
        // Tails sits one pixel short of the wall (x_pos=0x0315, x_sub=0x1500,
        // x_vel=6). CalcRoomInFront predicts x=0x031F (x_pos+$A), the flush edge of
        // the empty cell, so FindWall returns distance 0 and there is no push.
        Tails tails = newRecordedHczTails(0x0315, 0x1500);
        tails.setXSpeed((short) 0x0006);
        tails.setGSpeed((short) 0x0006);

        TerrainCheckResult terrain = ObjectTerrainUtils.checkRightWallDist(0x031F, 0x0618);
        assertTrue(terrain.hasCollision(),
                "HCZ1 terrain sanity check: ROM CalcRoomInFront probes x_pos+$A,y_pos+$8 at this frame");
        assertEquals(0, terrain.distance(),
                "HCZ1 terrain sanity check: the predicted probe pixel is flush at the empty-cell edge");

        GameServices.collision().resolveGroundWallCollision(tails);

        assertEquals(0x0006, tails.getGSpeed() & 0xFFFF,
                "A flush empty-cell-edge probe (distance 0) must not clear ground_vel");
        assertEquals(0x0006, tails.getXSpeed() & 0xFFFF,
                "A flush empty-cell-edge probe must leave the just-applied CPU acceleration intact");
        assertFalse(tails.getPushing(),
                "ROM CalcRoomInFront only sets Status_Push on a negative (penetrating) distance");
    }

    @Test
    void hcz1FlatRightWallProbePushesAtRecordedTailsFrontier() {
        // ROM BizHawk capture: on the push frames the CPU follow nudge
        // (Tails_CPU_Control loc_13E34 addq.w #1,x_pos, sonic3k.asm:26734-26741)
        // has already advanced Tails to x_pos=0x0316. Tails_InputAcceleration_Path
        // accelerates to x_vel=12 and CalcRoomInFront predicts x=0x0320, one pixel
        // inside the flat right-wall cell, so FindWall returns a genuine distance
        // -1 (sub_F584 loc_F60C not.w d1, sonic3k.asm:19666-19672) and the wall
        // response runs immediately (loc_14C00, sonic3k.asm:28012-28018).
        Tails tails = newRecordedHczTails(0x0316, 0x1500);
        tails.setXSpeed((short) 0x000C);
        tails.setGSpeed((short) 0x000C);

        TerrainCheckResult terrain = ObjectTerrainUtils.checkRightWallDist(0x0320, 0x0618);
        assertTrue(terrain.hasCollision(),
                "HCZ1 terrain sanity check: ROM CalcRoomInFront probes x_pos+$A,y_pos+$8 at this frame");
        assertEquals(-1, terrain.distance(),
                "HCZ1 terrain sanity check: the predicted probe pixel is one pixel inside the right-wall cell");

        GameServices.collision().resolveGroundWallCollision(tails);

        assertEquals(0, tails.getGSpeed(),
                "CalcRoomInFront should clear ground_vel as soon as the projected right-wall probe overlaps terrain");
        assertEquals(0xFF0C, tails.getXSpeed() & 0xFFFF,
                "Distance -1 at x=$0320 should subtract one pixel of velocity, matching S3K Tails_InputAcceleration_Path");
        assertTrue(tails.getPushing(),
                "S3K ground-wall collision should set Status_Push when Tails faces into the right wall");
    }

    private static Tails newRecordedHczTails(int centreX, int xSubpixel) {
        Tails tails = new Tails("tails", (short) centreX, (short) 0x0610);
        tails.setCpuControlled(true);
        tails.setCentreX((short) centreX);
        tails.setCentreY((short) 0x0610);
        tails.setSubpixelRaw(xSubpixel, 0x5E00);
        tails.setGroundMode(GroundMode.GROUND);
        tails.setDirection(Direction.RIGHT);
        tails.setAir(false);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setInWater(true);
        tails.setYSpeed((short) 0x0000);
        return tails;
    }
}
