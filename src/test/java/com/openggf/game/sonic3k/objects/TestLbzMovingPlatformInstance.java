package com.openggf.game.sonic3k.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SolidObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLbzMovingPlatformInstance {

    @BeforeEach
    void resetOscillation() {
        OscillationManager.reset();
    }

    @Test
    void registryRoutesS3klSlot11ToLbzMovingPlatform() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x11, 0, 0, false, 0));

        assertFalse(platform instanceof PlaceholderObjectInstance,
                "S3KL slot $11 is Obj_LBZMovingPlatform and must not remain a placeholder");
        assertEquals("LBZMovingPlatform", platform.getName());
        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, platform,
                "Obj_LBZMovingPlatform calls SolidObjectTop when visible");
        assertTrue(solid.usesGroundHalfHeightForTopSolidContact(),
                "loc_24F0E passes SolidObjectTop d3=9 independently of height_pixels=8");
        assertFalse(solid.usesPlatformObjectLandingSnap(),
                "SolidObjectTop keeps SolidObject_Landed's relative y_pos correction");
        assertTrue(solid.rejectsZeroDistanceTopSolidLanding(),
                "SolidObjectTop accepts only its unsigned negative overlap band");
    }

    @Test
    void registryKeepsSklSlot11AsMhzMushroomPlatform() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x11, 0, 0, false, 0));

        assertFalse(platform instanceof PlaceholderObjectInstance);
        assertEquals("MHZMushroomPlatform", platform.getName(),
                "S3K object slot $11 is zone-set-specific; SKL/MHZ must keep Obj_MHZMushroomPlatform");
    }

    @Test
    void subtypeHighBitSelectsDiagonalLiftDistanceAndNormalizesToType7() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x11, 0x82, 0, false, 0));

        platform.update(0, null);

        assertEquals(0x1800, platform.getX(),
                "Obj_LBZMovingPlatform high-bit init stores (($82&$7F)<<4) as the lift distance");
        assertEquals(0x0600, platform.getY(),
                "Platform_DiagonalLift does not move until standing_mask is set");
        assertEquals(0x20, assertInstanceOf(SolidObjectProvider.class, platform)
                .getSolidParams().halfWidth());
    }

    @Test
    void callerSkipsSolidObjectTopWhenPlatformRenderBoxIsOffscreen() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        SolidObjectProvider visible = assertInstanceOf(SolidObjectProvider.class, registry.create(
                new ObjectSpawn(0x0100, 0x00E0, 0x11, 0, 0, false, 0)));
        SolidObjectProvider below = assertInstanceOf(SolidObjectProvider.class, registry.create(
                new ObjectSpawn(0x0100, 0x0100, 0x11, 0, 0, false, 0)));

        assertTrue(visible.isSolidFor(null));
        assertFalse(below.isSolidFor(null),
                "loc_24F02 observes the post-render bit before calling SolidObjectTop");
    }

    @Test
    void fallingSubtypeUsesRomDelayThenMoveBeforeGravity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x11, 0x0E, 0, false, 0));

        assertInstanceOf(SolidObjectProvider.class, platform);

        platform.update(0, null);
        assertEquals(0x0600, platform.getY(),
                "First falling frame moves with old y_vel=0, then adds gravity");
        platform.update(1, null);
        assertEquals(0x0600, platform.getY(),
                "Second falling frame has y_vel=$38, still below one whole pixel in 16.16 movement");
        platform.update(2, null);
        assertEquals(0x0600, platform.getY());
        platform.update(3, null);
        assertEquals(0x0601, platform.getY(),
                "Fourth falling frame carries the accumulated 16.16 subpixel fraction into y_pos");
    }

    @Test
    void lbzPlanIncludesMovingPlatformLevelArt() {
        var plan = com.openggf.game.sonic3k.Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_LBZ, 0);

        var platform = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_MOVING_PLATFORM))
                .findFirst().orElse(null);

        assertNotNull(platform, "Obj_LBZMovingPlatform uses resident LBZ misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_MOVING_PLATFORM_ADDR, platform.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC, platform.artTileBase());
        assertEquals(2, platform.palette());
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
