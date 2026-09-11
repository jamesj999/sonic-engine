package com.openggf.game.sonic3k.events;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.Map;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * LBZ1 interior reveals are foreground layout copies, not render-only masks.
 *
 * <p>ROM: {@code LBZ1_ScreenEvent} / {@code LBZ1_CheckLayoutMod}
 * (docs/skdisasm/s3.asm:74713-75083).
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kLbzInteriorRevealEvents {
    private static final int FG_LAYER = 0;

    @BeforeAll
    static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    void enteringFirstInteriorRangeCopiesRevealedForegroundLayout() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .startPosition((short) 0x1400, (short) 0x0300)
                .startPositionIsCentre()
                .build();
        Map map = GameServices.level().getCurrentLevel().getMap();

        int[] covered = readRect(map, 0x26, 0x02, 8, 9);

        fixture.stepIdleFrames(1);

        int[] revealed = readRect(map, 0x26, 0x02, 8, 9);
        int[] revealSource = readRect(map, 0x80, 0x00, 8, 9);

        assertNotEquals(checksum(covered), checksum(revealed),
                "entering LBZ1 layout mod 1 should alter the visible foreground cells");
        assertArrayEquals(revealSource, revealed,
                "LBZ1 layout mod 1 should copy the ROM staging cells at map x=$80 into visible x=$26");
    }

    @Test
    void enteringSecondInteriorRangeCopiesRevealedForegroundLayout() {
        // ROM LBZ1_DoMod2 (docs/skdisasm/s3.asm): source = FG row 9 + $80,
        // destination = FG row 0 + $42, 10 cols x 14 rows. The reveal must land
        // in visible FG row 0 (the door), not the hidden staging row 9, or the
        // door stays solid and ejects the player.
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .startPosition((short) 0x2300, (short) 0x0300)
                .startPositionIsCentre()
                .build();
        Map map = GameServices.level().getCurrentLevel().getMap();

        int[] covered = readRect(map, 0x42, 0x00, 10, 14);

        fixture.stepIdleFrames(1);

        int[] revealed = readRect(map, 0x42, 0x00, 10, 14);
        int[] revealSource = readRect(map, 0x80, 0x09, 10, 14);

        assertNotEquals(checksum(covered), checksum(revealed),
                "entering LBZ1 layout mod 2 should alter the visible foreground cells");
        assertArrayEquals(revealSource, revealed,
                "LBZ1 layout mod 2 should copy ROM staging cells at map x=$80,y=9 into visible x=$42,y=0");
    }

    @Test
    void leavingFirstInteriorRangeRestoresCoveredForegroundLayout() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .startPosition((short) 0x1400, (short) 0x0300)
                .startPositionIsCentre()
                .build();
        Map map = GameServices.level().getCurrentLevel().getMap();
        int[] covered = readRect(map, 0x26, 0x02, 8, 9);

        fixture.stepIdleFrames(1);
        fixture.sprite().setCentreX((short) 0x1800);
        fixture.sprite().setCentreY((short) 0x0300);
        fixture.stepIdleFrames(1);

        assertArrayEquals(covered, readRect(map, 0x26, 0x02, 8, 9),
                "leaving LBZ1 layout mod 1 exit range should restore the covered foreground cells");
    }

    private static int[] readRect(Map map, int x, int y, int width, int height) {
        int[] values = new int[width * height];
        int index = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                values[index++] = map.getValue(FG_LAYER, x + col, y + row) & 0xFF;
            }
        }
        return values;
    }

    private static int checksum(int[] values) {
        int checksum = 1;
        for (int value : values) {
            checksum = 31 * checksum + value;
        }
        return checksum;
    }
}
