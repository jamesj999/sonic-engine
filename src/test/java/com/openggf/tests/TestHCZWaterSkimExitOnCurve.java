package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests water-entry suppression while skimming and resumption after skim exits.
 *
 * <p>These tests exercise the player sprite's waterSkimActive gate. The terrain
 * comparison that decides when to clear that gate is owned by
 * HCZWaterSkimHandler.processSkimPhysics() and is not exercised here.
 */
@ExtendWith(SingletonResetExtension.class)
public class TestHCZWaterSkimExitOnCurve {

    private TestablePlayableSprite sprite;

    @BeforeEach
    public void setUp() {
        sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
    }

    /**
     * Verify that waterSkimActive prevents water entry while skimming.
     * When skim exits (due to terrain), waterSkimActive clears and normal
     * water physics should resume.
     */
    @Test
    public void skimActive_PreventsWaterEntry() {
        sprite.setWaterSkimActive(true);
        sprite.setTestY((short) 500); // Below water
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(),
                "Player should NOT be marked as underwater while skim is active");
    }

    @Test
    public void skimInactive_AllowsNormalWaterEntry() {
        sprite.setWaterSkimActive(false);
        sprite.setTestY((short) 500); // Below water
        sprite.updateWaterState(400);

        assertTrue(sprite.isInWater(),
                "Player should enter water normally when skim is inactive");
    }

    /**
     * Verify that after skim exits (waterSkimActive cleared), the next
     * water state update correctly transitions the player into water if
     * they're below the water level.
     */
    @Test
    public void afterSkimExit_WaterEntryResumes() {
        // Start with skim active, player at water level
        sprite.setWaterSkimActive(true);
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);
        assertFalse(sprite.isInWater(), "Not in water while skimming");

        // Skim exits (e.g., terrain pushed above water or speed dropped)
        sprite.setWaterSkimActive(false);

        // Next frame: player falls below water level
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);
        assertTrue(sprite.isInWater(), "Should enter water after skim exits");
    }

}
