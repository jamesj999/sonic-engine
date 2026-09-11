package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@link ARZPlatformObjectInstance} zone detection.
 *
 * <p>Object 0x18 (floating platform, EHZ/ARZ/HTZ) selects the Obj18B mapping table and the
 * 0x28 solid {@code y_radius} only in Aquatic Ruin Zone. The ROM gate is
 * {@code cmpi.b #aquatic_ruin_zone,(Current_Zone).w} where {@code aquatic_ruin_zone = $0F}.
 *
 * <p>{@link com.openggf.level.Level#getZoneIndex()} returns the ROM zone ID, so the engine's
 * ARZ test must compare against {@code 0x0F} ({@link Sonic2Constants#ZONE_ARZ}), not the
 * internal level-select index {@code 2}. The original code compared against {@code 2}, so the
 * check never matched in ARZ and the platform rendered with the EHZ (Obj18A) mappings —
 * producing corrupted graphics.
 */
class TestArzPlatformZoneDetection {

    @Test
    void aquaticRuinRomZoneIdIsDetectedAsArz() {
        assertTrue(ARZPlatformObjectInstance.isAquaticRuinZone(Sonic2Constants.ZONE_ARZ),
                "ARZ ROM zone id (0x0F) must be detected as Aquatic Ruin Zone");
        assertTrue(ARZPlatformObjectInstance.isAquaticRuinZone(0x0F),
                "0x0F is the ROM zone id getZoneIndex() returns for ARZ");
    }

    @Test
    void nonArzZonesAreNotDetectedAsArz() {
        assertFalse(ARZPlatformObjectInstance.isAquaticRuinZone(0x00),
                "EHZ (ROM zone id 0x00) must use the Obj18A mappings, not ARZ");
        assertFalse(ARZPlatformObjectInstance.isAquaticRuinZone(Sonic2Constants.ZONE_HTZ),
                "HTZ must use the Obj18A mappings, not ARZ");
        // The internal level-select index for ARZ is 2; it must NOT be treated as the ROM zone id.
        assertFalse(ARZPlatformObjectInstance.isAquaticRuinZone(2),
                "The internal level-select index 2 is not the ROM zone id and must not match");
    }
}
