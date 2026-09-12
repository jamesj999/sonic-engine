package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.session.SessionManager;
import com.openggf.level.scroll.ZoneScrollHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HPZ sanctuary background scroll parity against {@code HPZ_BackgroundEvent}
 * (sonic3k.asm:120069-120280).
 */
class SwScrlHpzTest {

    /** Sanctuary hub camera pinned by {@code Sonic3kLevelResourceProfile.HPZ_RESOURCES}. */
    private static final int SANCTUARY_CAMERA_X = 0x15A0;
    private static final int SANCTUARY_CAMERA_Y = 0x0320;

    @BeforeEach
    void clearRuntimeState() {
        SessionManager.clear();
    }

    @Test
    void providerRoutesHpzToDedicatedHandler() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneIds.ZONE_HPZ);

        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlHpz,
                "HPZ should not use the generic S3K fallback scroll handler");
    }

    @Test
    void giantRingDestinationUsesSanctuaryScrollButBossActKeepsFallback() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());
        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA);
        assertSame(provider.getHandler(Sonic3kZoneIds.ZONE_HPZ), handler);
        int[] actual = new int[VISIBLE_LINES];
        int[] expected = new int[VISIBLE_LINES];
        SwScrlS3kDefault fallback = new SwScrlS3kDefault();
        handler.update(actual, SANCTUARY_CAMERA_X, SANCTUARY_CAMERA_Y, 0, 1);
        assertEquals((short) 0x0096, handler.getVscrollFactorBG());
        handler.update(actual, 0x160, 0x240, 0, 0);
        fallback.update(expected, 0x160, 0x240, 0, 0);
        assertArrayEquals(expected, actual);
        assertEquals(fallback.getVscrollFactorBG(), handler.getVscrollFactorBG());
    }

    /**
     * With no runtime player the seam predicate reads as the Master Emerald
     * chamber framing, so {@code sub_5A32C}'s offsets ({@code $348}, {@code $000})
     * apply.
     */
    @Test
    void nearFramingUsesSub5A32COffsets() {
        SwScrlHpz handler = new SwScrlHpz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, SANCTUARY_CAMERA_X, SANCTUARY_CAMERA_Y, 0, 1);

        // (0x0320 + 0x000) * 3/16 = 0x0096
        assertEquals((short) 0x0096, handler.getVscrollFactorBG(),
                "Camera_Y_pos_BG_copy is 3/16 of camera Y with no sub_5A32C Y offset");
    }

    /**
     * The special-stage return spawn sits at {@code x_pos = $1640}, past the ROM's
     * {@code $EC0} seam, so {@code sub_5A334} supplies ({@code $E00}, {@code $700}).
     *
     * <p>{@code Camera_Y_pos_BG_copy = ($0320 + $0700) * 3/16 = $01E6}. Walking
     * {@code HPZ_BGDeformArray} from that base leaves 26 visible lines in the
     * 11/16 band before the remainder band takes over at 3/4.
     */
    @Test
    void farFramingUsesSub5A334OffsetsAndDeformGradient() {
        SwScrlHpz handler = new HpzHandlerPastSeam();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, SANCTUARY_CAMERA_X, SANCTUARY_CAMERA_Y, 0, 1);

        assertEquals((short) 0x01E6, handler.getVscrollFactorBG(),
                "sub_5A334 adds $700 before scaling camera Y by 3/16");

        // camera X - $E00 = $07A0; loc_5A388 word 12 = 11/16, word 13 = 3/4.
        assertEquals((short) -0x053E, unpackBG(buffer[0]),
                "the visible top band is HScroll_table word 12 (11/16 of the offset camera X)");
        assertEquals((short) -0x053E, unpackBG(buffer[25]),
                "the 11/16 band spans 26 lines for this Camera_Y_pos_BG_copy");
        assertEquals((short) -0x05B8, unpackBG(buffer[26]),
                "the remainder band is HScroll_table word 13 (3/4 of the offset camera X)");
        assertEquals((short) -0x05B8, unpackBG(buffer[VISIBLE_LINES - 1]),
                "the remainder band runs to the bottom of the display");
    }

    /**
     * The generic S3K fallback drove HPZ at a flat 1/4 rate with no ROM Y offset,
     * which framed the sanctuary on the wrong background rows. Guard the two
     * values that regression turned on.
     */
    @Test
    void farFramingDoesNotFallBackToFlatQuarterSpeedParallax() {
        SwScrlHpz handler = new HpzHandlerPastSeam();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, SANCTUARY_CAMERA_X, SANCTUARY_CAMERA_Y, 0, 1);

        assertTrue(handler.getVscrollFactorBG() != (short) (SANCTUARY_CAMERA_Y >> 2),
                "HPZ background Y must not use the fallback 1/4 camera-Y rate");
        assertTrue(unpackBG(buffer[0]) != unpackBG(buffer[VISIBLE_LINES - 1]),
                "HPZ_BGDeformArray must produce banded background scroll, not one flat value");
    }

    /** Pins the ROM {@code Player_1+x_pos} seam test without booting a runtime. */
    private static final class HpzHandlerPastSeam extends SwScrlHpz {
        @Override
        protected boolean isFarFraming() {
            return true;
        }
    }
}
