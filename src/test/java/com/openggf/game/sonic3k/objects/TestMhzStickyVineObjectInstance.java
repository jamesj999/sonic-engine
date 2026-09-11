package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzStickyVineObjectInstance {
    private static final int MHZ_STICKY_VINE = 0x0A;

    @Test
    void registryRoutesSklSlot0AToMhzStickyVine() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));

        assertEquals("MHZStickyVine", vine.getName(),
                "SKL slot $0A is Obj_MHZStickyVine and must not use the S3KL AIZ zipline peg");
    }

    @Test
    void playerInsideStickyWindowActivatesVineAndIsPulledTowardAnchor() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2408, (short) 0x0680);
        player.setAir(true);
        player.setXSpeed((short) 0x0400);

        assertEquals("MHZStickyVine", vine.getName(),
                "SKL slot $0A must construct the MHZ sticky vine before interaction can be validated");
        vine.update(0, player);
        int xAfterCapture = player.getCentreX();
        vine.update(1, player);

        assertTrue(xAfterCapture < 0x2408,
                "Obj_MHZStickyVine pulls an overlapping player back toward x_pos using sub_3EC66");
        assertTrue(player.getCentreX() <= xAfterCapture,
                "The active vine routine keeps applying the pull while the player remains captured");
        assertTrue(vine.traceDebugDetails().contains("active=true"),
                "Trace details expose the ROM active routine state for parity debugging");
    }

    @Test
    void spindashReleaseStartsRetractionTowardAnchor() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2408, (short) 0x0680);
        player.setAir(true);
        player.setSpindash(true);

        assertEquals("MHZStickyVine", vine.getName(),
                "SKL slot $0A must construct the MHZ sticky vine before retraction can be validated");
        vine.update(0, player);
        player.setSpindash(false);
        for (int frame = 1; frame <= 16; frame++) {
            vine.update(frame, player);
        }

        assertTrue(vine.traceDebugDetails().contains("retracting=true"),
                "ROM sets $3D/$3E then enters the loc_3EB50 retraction path after spindash release");
    }

    @Test
    void activeAirPullMatchesRomArcTanSineCosineVector() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        short dx = 6;
        short dy = -8;
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) (0x2400 + dx), (short) (0x0680 + dy));
        player.setAir(true);

        // sub_3EC66: angle = GetArcTan(dxPixel, dyPixel); (sin, cos) = GetSineCosine(angle);
        // d3 = (|dxPixel| + |dyPixel|) * 2; x_pos -= cos(angle)*d3*4; y_pos -= sin(angle)*d3*2
        // (air branch only) -- computed here from the engine's own production trig tables.
        int angle = TrigLookupTable.calcAngle(dx, dy);
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);
        short d3 = (short) ((Math.abs(dx) + Math.abs(dy)) * 2);
        int xPullQ = (cos * d3) << 2;
        int yPullQ = (sin * d3) << 1;

        int playerXQ = (player.getCentreX() << 16) | (player.getXSubpixelRaw() & 0xFFFF);
        int playerYQ = (player.getCentreY() << 16) | (player.getYSubpixelRaw() & 0xFFFF);
        int expectedXQ = playerXQ - xPullQ;
        int expectedYQ = playerYQ - yPullQ;

        vine.update(0, player);

        assertEquals(expectedXQ >> 16, player.getCentreX(),
                "sub_3EC66 subtracts cos(angle)*d3*4 from x_pos via GetArcTan/GetSineCosine over (dx,dy)");
        assertEquals(expectedXQ & 0xFFFF, player.getXSubpixelRaw(),
                "the x_pos pull is a full 32-bit sub.l, preserving ROM sub-pixel precision");
        assertEquals(expectedYQ >> 16, player.getCentreY(),
                "sub_3EC66 subtracts sin(angle)*d3*2 from y_pos while the player is airborne");
        assertEquals(expectedYQ & 0xFFFF, player.getYSubpixelRaw(),
                "the y_pos pull is a full 32-bit sub.l, preserving ROM sub-pixel precision");
    }

    @Test
    void activeStickyPullClearsPlayerPushStatus() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2408, (short) 0x0680);
        player.setPushing(true);

        vine.update(0, player);

        assertFalse(player.getPushing(),
                "Obj_MHZStickyVine sub_3EC66 ends by clearing Status_Push every active pull frame");
    }

    @Test
    void groundedStickyPullHalvesGroundSpeedWhenRomThresholdIsExceeded() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2406, (short) 0x0680);
        player.setAir(false);
        player.setGSpeed((short) 0x0200);

        // Capture within the grab window first (sub_3EC66's pull is sub-pixel and does not
        // re-check the window once active), then simulate the player's own ground momentum
        // carrying them well past the original window before the next per-frame pull --
        // this is what actually drives dx large enough for the ROM cos(angle)*d3*4 pull
        // magnitude (scaled to ground_vel's Q8.8 units via >>8) to clear the $200/$10 gate.
        vine.update(0, player);
        player.setCentreX((short) (0x2400 + 64));
        vine.update(1, player);

        assertEquals((short) 0x0100, player.getGSpeed(),
                "sub_3EC66 halves ground_vel when the ROM cos(angle)*d3*4 pull magnitude (>>8) "
                        + "exceeds abs(ground_vel)-$10");
    }

    @Test
    void hurtPlayerInsideStickyWindowIsNotCapturedOrPulled() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2408, (short) 0x0680);
        player.setHurt(true);

        vine.update(0, player);

        assertEquals(0x2408, player.getCentreX(),
                "loc_3EADA rejects players whose routine is >= 4 before entering the active pull routine");
        assertFalse(vine.traceDebugDetails().contains("active=true"),
                "A hurt-routine player inside the sticky window must not arm Obj_MHZStickyVine");
    }

    @Test
    void nativeP2InsideStickyWindowActivatesVineWhenP1UpdatesObject() {
        MhzStickyVineObjectInstance vine = new MhzStickyVineObjectInstance(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x2000, (short) 0x0680);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x2408, (short) 0x0680);
        tails.setAir(true);
        tails.setXSpeed((short) 0x0400);
        vine.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        vine.update(0, sonic);

        assertTrue(tails.getCentreX() < 0x2408,
                "loc_3EACA runs loc_3EADA for Player_2 after Player_1, so native P2 can activate the sticky vine");
        assertTrue(vine.traceDebugDetails().contains("active=true"),
                "native P2 capture must enter the same active sticky-vine routine as Player_1 capture");
        assertEquals(0x2000, sonic.getCentreX(),
                "P1 outside the sticky-vine window must remain unaffected while P2 is captured");
    }

    @Test
    void inactiveStickyVineRunsRomOffscreenDeletePath() {
        MhzStickyVineObjectInstance vine = new MhzStickyVineObjectInstance(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2000, (short) 0x0680);
        AbstractObjectInstance.updateCameraBounds(0x1000, 0, 0x1140, 0x00E0, 0);

        vine.update(0, player);

        assertTrue(vine.isDestroyed(),
                "Obj_MHZStickyVine reaches loc_3EBF8 even before capture and deletes when outside the ROM range window");
        assertTrue(vine.isDestroyedRespawnable(),
                "loc_3EC0E clears the remembered spawn bit so the sticky vine can respawn when the camera returns");
    }

    @Test
    void stickyVineRendersEightRomDisplayChildSegments() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_STICKY_VINE)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzStickyVineObjectInstance vine = new MhzStickyVineObjectInstance(new ObjectSpawn(
                0x2400, 0x0680, MHZ_STICKY_VINE, 0, 0, false, 0));
        vine.setServices(new TestObjectServices().withLevelManager(levelManager));

        vine.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_STICKY_VINE);
        verify(renderer, times(8)).drawFrameIndex(0, 0x2400, 0x0680, false, false);
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
