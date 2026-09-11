package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1LargeGrassyPlatformObjectInstance {

    @Test
    public void wideVariantUsesSolidObject2fCompensation() {
        Sonic1LargeGrassyPlatformObjectInstance platform = create(0x00);

        SolidObjectParams params = platform.getSolidParams();
        assertEquals(0x20, params.airHalfHeight());
        assertEquals(0x20, params.groundHalfHeight());
        assertEquals(0x20, platform.getSlopeBaseline());
        assertFalse(platform.isTopSolidOnly());
        assertTrue(platform.addsSlopeCatchRangeToVerticalOverlap());
    }

    @Test
    public void narrowVariantUsesSolidObject2fCompensation() {
        Sonic1LargeGrassyPlatformObjectInstance platform = create(0x20);

        SolidObjectParams params = platform.getSolidParams();
        assertEquals(0x30, params.airHalfHeight());
        assertEquals(0x30, params.groundHalfHeight());
        assertEquals(0x30, platform.getSlopeBaseline());
        assertFalse(platform.isTopSolidOnly());
    }

    @Test
    public void grassyPlatformUsesHighPrioritySpriteBit() {
        Sonic1LargeGrassyPlatformObjectInstance platform = create(0x00);
        assertTrue(platform.isHighPriority());
    }

    @Test
    public void balanceUsesRomActiveWidthWithoutHeightmapPadding() {
        Sonic1LargeGrassyPlatformObjectInstance platform = create(0x00);

        assertEquals(0x40, platform.getBalanceWidthPixels(),
                "Sonic_Move reads Obj2F obActWid from LGrass_Data");
        assertEquals(0x4B, platform.getSolidParams().halfWidth(),
                "LGrass_Solid adds $B only to the collision width");
    }

    private static Sonic1LargeGrassyPlatformObjectInstance create(int subtype) {
        ObjectSpawn spawn = new ObjectSpawn(100, 100, 0x2F, subtype, 0, false, 0);
        return new Sonic1LargeGrassyPlatformObjectInstance(spawn);
    }
}

