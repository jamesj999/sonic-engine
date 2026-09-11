package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.sonic1.Sonic1ZoneRegistry;
import com.openggf.game.sonic2.Sonic2ZoneRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TODO #31 -- End-game loop prevention in LevelManager.advanceToNextLevel().
 *
 * <p>In {@code LevelManager.advanceToNextLevel()} (LevelManager.java:2527),
 * the code currently wraps zone index back to 0 when all zones are complete:
 * <pre>
 *   if (currentZone >= levels.size()) {
 *       LOGGER.info("All zones complete!");
 *       currentZone = 0; // Loop back for now - TODO: end game sequence
 *   }
 * </pre>
 *
 * <p>This is a placeholder that should be replaced with proper end-game
 * sequence handling (credits, ending screen, etc.) per game.
 *
 * <p>Expected zone progression order per game:
 * <ul>
 *   <li>Sonic 1: GHZ -> LZ -> MZ -> SLZ -> SYZ -> SBZ -> FZ -> END</li>
 *   <li>Sonic 2: EHZ -> CPZ -> ARZ -> CNZ -> HTZ -> MCZ -> OOZ -> MTZ -> SCZ -> WFZ -> DEZ -> END</li>
 *   <li>Sonic 3&K: AIZ -> HCZ -> MGZ -> CNZ -> ICZ -> LBZ -> MHZ -> FBZ -> SOZ -> LRZ -> HPZ -> SSZ -> DEZ -> DDZ -> END</li>
 * </ul>
 */
public class TestTodo31_EndGameLoopPrevention {

    @Test
    public void testSonic2ZoneProgressionOrder() {
        Sonic2ZoneRegistry registry = new Sonic2ZoneRegistry();

        // Sonic 2 has exactly 11 zones
        assertEquals(11, registry.getZoneCount(), "Sonic 2 has 11 zones");

        // Verify the zone order matches the original game
        String[] expectedOrder = {
                "EMERALD HILL",     // Zone 0
                "CHEMICAL PLANT",   // Zone 1
                "AQUATIC RUIN",     // Zone 2
                "CASINO NIGHT",     // Zone 3
                "HILL TOP",         // Zone 4
                "MYSTIC CAVE",      // Zone 5
                "OIL OCEAN",        // Zone 6
                "METROPOLIS",       // Zone 7
                "SKY CHASE",        // Zone 8
                "WING FORTRESS",    // Zone 9
                "DEATH EGG"         // Zone 10
        };

        for (int i = 0; i < expectedOrder.length; i++) {
            assertEquals(expectedOrder[i], registry.getZoneName(i), "Zone " + i + " name");
        }
    }

    @Test
    public void testSonic2ActCounts() {
        Sonic2ZoneRegistry registry = new Sonic2ZoneRegistry();

        // Most zones have 2 acts; Metropolis has 3; Sky Chase, Wing Fortress, Death Egg have 1
        assertEquals(2, registry.getActCount(0), "EHZ has 2 acts");
        assertEquals(2, registry.getActCount(1), "CPZ has 2 acts");
        assertEquals(2, registry.getActCount(2), "ARZ has 2 acts");
        assertEquals(2, registry.getActCount(3), "CNZ has 2 acts");
        assertEquals(2, registry.getActCount(4), "HTZ has 2 acts");
        assertEquals(2, registry.getActCount(5), "MCZ has 2 acts");
        assertEquals(2, registry.getActCount(6), "OOZ has 2 acts");
        assertEquals(3, registry.getActCount(7), "MTZ has 3 acts");
        assertEquals(1, registry.getActCount(8), "SCZ has 1 act");
        assertEquals(1, registry.getActCount(9), "WFZ has 1 act");
        assertEquals(1, registry.getActCount(10), "DEZ has 1 act");
    }

    @Test
    public void testSonic2TotalLevelCount() {
        Sonic2ZoneRegistry registry = new Sonic2ZoneRegistry();

        // Total levels: 7*2 + 3 + 1 + 1 + 1 = 20
        int totalLevels = 0;
        for (int z = 0; z < registry.getZoneCount(); z++) {
            totalLevels += registry.getActCount(z);
        }
        assertEquals(20, totalLevels, "Sonic 2 has 20 total levels");
    }

    @Test
    public void testSonic2FinalZoneIsDeathEgg() {
        Sonic2ZoneRegistry registry = new Sonic2ZoneRegistry();

        // The last zone should be Death Egg
        int lastZone = registry.getZoneCount() - 1;
        assertEquals("DEATH EGG", registry.getZoneName(lastZone), "Last zone is DEATH EGG");
    }

    @Test
    public void testSonic1ZoneProgressionOrder() {
        Sonic1ZoneRegistry registry = new Sonic1ZoneRegistry();

        // Sonic 1 has 8 zones (6 main + Final Zone + Ending)
        assertEquals(8, registry.getZoneCount(), "Sonic 1 has 8 zones");

        String[] expectedOrder = {
                "GREEN HILL",     // Zone 0
                "MARBLE",         // Zone 1
                "SPRING YARD",    // Zone 2
                "LABYRINTH",      // Zone 3
                "STAR LIGHT",     // Zone 4
                "SCRAP BRAIN",    // Zone 5
                "FINAL",          // Zone 6
                "ENDING"          // Zone 7
        };

        for (int i = 0; i < expectedOrder.length; i++) {
            assertEquals(expectedOrder[i], registry.getZoneName(i), "Zone " + i + " name");
        }
    }

    @Test
    public void testSonic1FinalZoneHasSingleAct() {
        Sonic1ZoneRegistry registry = new Sonic1ZoneRegistry();
        // Final Zone is zone 6 (second-to-last), has 1 act
        assertEquals(1, registry.getActCount(6), "Final Zone has 1 act");
        assertEquals("FINAL", registry.getZoneName(6), "Zone 6 is Final Zone");
        // Ending (zone 7) has 2 variants (flowers / no emeralds)
        assertEquals(2, registry.getActCount(7), "Ending has 2 acts");
        assertEquals("ENDING", registry.getZoneName(7), "Zone 7 is Ending");
    }

    @Test
    public void testZoneIndexBoundsAfterFinalZone() {
        Sonic2ZoneRegistry registry = new Sonic2ZoneRegistry();

        // After the final zone, currentZone would exceed the zone count.
        // The TODO at LevelManager:2527 wraps to 0 instead of triggering end-game.
        int zoneAfterFinal = registry.getZoneCount(); // would be 11
        assertTrue(zoneAfterFinal >= registry.getZoneCount(), "Zone index after final zone exceeds zone count");

        // Out-of-bounds zone name returns "UNKNOWN" (not crash)
        assertEquals("UNKNOWN", registry.getZoneName(zoneAfterFinal), "Out-of-bounds zone returns UNKNOWN");
    }

    /**
     * Verifies that all ZoneRegistry boundary methods return safe defaults
     * for out-of-bounds zone indices (at, beyond, and negative). This is the
     * behavior that advanceToNextLevel() would encounter if it did not wrap.
     */
    @Test
    public void testZoneIndexBeyondFinalIsHandledGracefully() {
        assertBoundaryBehavior("Sonic 2", new Sonic2ZoneRegistry());
        assertBoundaryBehavior("Sonic 1", new Sonic1ZoneRegistry());
    }

    private void assertBoundaryBehavior(String gameName, ZoneRegistry registry) {
        int count = registry.getZoneCount();
        assertTrue(count > 0, gameName + " must have at least one zone");

        // Indices at and beyond the final zone
        int[] outOfBounds = { count, count + 1, count + 100 };
        for (int idx : outOfBounds) {
            assertEquals("UNKNOWN", registry.getZoneName(idx), gameName + " getZoneName(" + idx + ") should return UNKNOWN");
            assertEquals(0, registry.getActCount(idx), gameName + " getActCount(" + idx + ") should return 0");
            assertTrue(registry.getLevelDataForZone(idx).isEmpty(), gameName + " getLevelDataForZone(" + idx + ") should return empty list");
        }

        // Negative index
        assertEquals("UNKNOWN", registry.getZoneName(-1), gameName + " getZoneName(-1) should return UNKNOWN");
        assertEquals(0, registry.getActCount(-1), gameName + " getActCount(-1) should return 0");
        assertTrue(registry.getLevelDataForZone(-1).isEmpty(), gameName + " getLevelDataForZone(-1) should return empty list");
    }

}


