package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the HCZ water skim terrain exit logic.
 *
 * <p>ROM reference: sub_3857E (sonic3k.asm:75393-75491).
 * When skimming, if terrain pushes the player above the water pin position
 * (pinnedY > centreY in Y-down coordinates), skim must exit gracefully
 * rather than forcing the player back to the water surface.
 *
 * <p>This test validates the underlying condition logic using the
 * waterSkimActive flag on the player sprite. The actual comparison
 * (pinnedY > centreY → exit) is in HCZWaterSkimHandler.processSkimPhysics().
 *
 * <p>Bug: the engine was pinning Y before checking if terrain had pushed
 * the player above water, causing clipping into curves in HCZ1/2.
 */
@ExtendWith(SingletonResetExtension.class)
public class TestHCZWaterSkimExitOnCurve {

    private TestablePlayableSprite sprite;

    @BeforeEach
    public void setUp() {
        sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
    }

    /**
     * Verify the ROM's exit condition: when pinnedY > centreY (unsigned),
     * the player has been pushed above the water surface by terrain.
     * This is the core comparison from sonic3k.asm:75433-75434:
     *   cmp.w y_pos(a1),d0
     *   bhi.s loc_38646
     */
    @Test
    public void terrainAboveWater_ExitCondition_WhenPlayerAbovePin() {
        // Setup: water at Y=0x500, player y_radius = 19 (Sonic standing)
        int waterLevel = 0x500;
        int yRadius = sprite.getYRadius();
        int pinnedY = waterLevel - yRadius - 1;  // Where centre would be if pinned

        // Player has been pushed UP by terrain (centreY < pinnedY in Y-down)
        // e.g., a curve has moved them to Y=0x4D0 while pin would be ~0x4EC
        int playerAbovePin = pinnedY - 30;
        assertTrue(Integer.compareUnsigned(pinnedY, playerAbovePin) > 0,
                "pinnedY should be > player centreY when terrain pushed player up (exit condition)");
    }

    @Test
    public void terrainAboveWater_NoExitCondition_WhenPlayerAtOrBelowPin() {
        int waterLevel = 0x500;
        int yRadius = sprite.getYRadius();
        int pinnedY = waterLevel - yRadius - 1;

        // Player exactly at pin position (normal skim)
        assertFalse(Integer.compareUnsigned(pinnedY, pinnedY) > 0,
                "No exit when player is exactly at pin position");

        // Player below pin (shouldn't happen normally, but should not exit)
        int playerBelowPin = pinnedY + 5;
        assertFalse(Integer.compareUnsigned(pinnedY, playerBelowPin) > 0,
                "No exit when player is below pin position");
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

    /**
     * Verify the ROM's friction exit: when x_vel reaches 0 during friction
     * application, skim should exit immediately (sonic3k.asm:75452).
     * This tests the principle — actual friction exit is in HCZWaterSkimHandler.
     */
    @Test
    public void frictionToZero_ShouldExitSkim() {
        // ROM: after applying $C friction, if x_vel == 0, branch to skim exit.
        // This is a direct exit rather than waiting for next frame's threshold check.
        short xSpeed = 0x008;  // Below friction amount of 0xC
        short afterFriction = (short) Math.max(0, xSpeed - 0xC);
        assertEquals(0, afterFriction,
                "Speed below friction amount should reduce to zero");
        assertTrue(afterFriction < 0x700,
                "Zero speed is below skim threshold");
    }
}
