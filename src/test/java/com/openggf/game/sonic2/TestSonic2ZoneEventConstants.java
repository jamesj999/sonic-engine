package com.openggf.game.sonic2;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies zone constants in Sonic2LevelEventManager match
 * the Sonic2ZoneRegistry ordering (0-10).
 * The rest of the codebase (ParallaxManager, BackgroundCamera,
 * LevelSelectConstants, Sonic2ZoneConstants) all agree:
 * SCZ=8, WFZ=9, DEZ=10.
 */
public class TestSonic2ZoneEventConstants {

    @Test
    public void dezZoneConstantMatchesRegistry() {
        assertEquals(10, Sonic2LevelEventManager.ZONE_DEZ, "ZONE_DEZ must be 10 to match ZoneRegistry");
    }

    @Test
    public void wfzZoneConstantMatchesRegistry() {
        assertEquals(9, Sonic2LevelEventManager.ZONE_WFZ, "ZONE_WFZ must be 9 to match ZoneRegistry");
    }

    @Test
    public void sczZoneConstantMatchesRegistry() {
        assertEquals(8, Sonic2LevelEventManager.ZONE_SCZ, "ZONE_SCZ must be 8 to match ZoneRegistry");
    }

    @Test
    public void cpzZoneConstantMatchesRegistry() {
        assertEquals(1, Sonic2LevelEventManager.ZONE_CPZ, "ZONE_CPZ must be 1 to match ZoneRegistry");
    }
}


