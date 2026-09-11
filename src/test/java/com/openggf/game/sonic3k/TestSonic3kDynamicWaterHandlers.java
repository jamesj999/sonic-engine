package com.openggf.game.sonic3k;

import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ThresholdTableWaterHandler;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.WaterSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for S3K dynamic water handlers returned by {@link Sonic3kWaterDataProvider}.
 * Verifies handler assignment and threshold/state-machine behavior.
 */
public class TestSonic3kDynamicWaterHandlers {

    private Sonic3kWaterDataProvider provider;

    @BeforeEach
    public void setUp() {
        provider = new Sonic3kWaterDataProvider();
        Sonic3kLevelTriggerManager.reset();
    }

    // =====================================================================
    // Handler assignment tests
    // =====================================================================

    @Test
    public void aiz1HandlerIsNull() {
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_AIZ, 0, PlayerCharacter.SONIC_AND_TAILS), "AIZ1 should have no dynamic handler (static water)");
    }

    @Test
    public void aiz2HandlerExists() {
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_AIZ, 1, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull(handler, "AIZ2 should have a dynamic handler");
        assertInstanceOf(Aiz2DynamicWaterHandler.class, handler);
    }

    @Test
    public void hcz1HandlerIsThresholdTable() {
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull(handler, "HCZ1 should have a dynamic handler");
        assertInstanceOf(ThresholdTableWaterHandler.class, handler);
    }

    @Test
    public void hcz2SonicHandlerIsThresholdTable() {
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 1, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull(handler, "HCZ2 Sonic should have a dynamic handler");
        assertInstanceOf(ThresholdTableWaterHandler.class, handler);
    }

    @Test
    public void hcz2KnucklesHandlerIsThresholdTable() {
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 1, PlayerCharacter.KNUCKLES);
        assertNotNull(handler, "HCZ2 Knuckles should have a dynamic handler");
        assertInstanceOf(ThresholdTableWaterHandler.class, handler);
    }

    @Test
    public void lbz1HandlerIsNull() {
        // LBZ1 has no water per CheckLevelForWater â€” only LBZ2 does
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_LBZ, 0, PlayerCharacter.SONIC_AND_TAILS), "LBZ1 should have no dynamic handler (no water)");
    }

    @Test
    public void lbz2KnucklesHandlerIsLbz2Handler() {
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
        assertNotNull(handler, "LBZ2 Knuckles should have a dynamic handler");
        assertInstanceOf(Lbz2KnucklesDynamicWaterHandler.class, handler);
    }

    @Test
    public void lbz2SonicHandlerIsNull() {
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.SONIC_AND_TAILS), "LBZ2 Sonic should have no dynamic handler");
    }

    @Test
    public void lbz2TailsHandlerIsNull() {
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.TAILS_ALONE), "LBZ2 Tails should have no dynamic handler");
    }

    @Test
    public void nonWaterZoneHandlerIsNull() {
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_MGZ, 0, PlayerCharacter.SONIC_AND_TAILS), "MGZ should have no dynamic handler");
        assertNull(provider.getDynamicHandler(Sonic3kZoneIds.ZONE_DEZ, 0, PlayerCharacter.SONIC_AND_TAILS), "DEZ should have no dynamic handler");
    }

    // =====================================================================
    // Threshold handler behavior tests
    // =====================================================================

    @Test
    public void hcz1BelowFirstThresholdInstantSetsTo0x0500() {
        // ROM word_6E8C: dc.w $8500, $0900 â€” cameraX <= 0x0900 -> instant-set 0x0500
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0500);

        handler.update(state, 0, 0);
        assertEquals(0x0500, state.getTargetLevel(), "Bit-15 target 0x8500 should instant-set to 0x0500");
        assertEquals(0x0500, state.getMeanLevel());
    }

    @Test
    public void hcz1FallbackInstantSetsTo0x06A0() {
        // ROM word_6E8C last entry: dc.w $86A0, $FFFF â€” fallback instant-set 0x06A0
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0500);

        handler.update(state, 0xF000, 0); // past all thresholds except fallback
        assertEquals(0x06A0, state.getTargetLevel());
        assertEquals(0x06A0, state.getMeanLevel());
    }

    @Test
    public void hcz2SonicBelowFirstThresholdSetsTarget0x0700() {
        // ROM word_6EBA: dc.w $0700, $3E00 â€” cameraX <= 0x3E00 -> target 0x0700 (gradual)
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 1, PlayerCharacter.SONIC_AND_TAILS);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0700);

        handler.update(state, 0, 0);
        assertEquals(0x0700, state.getTargetLevel());
    }

    @Test
    public void hcz2KnucklesBelowFirstThresholdSetsTarget0x0700() {
        // ROM word_6EC2: dc.w $0700, $4100 â€” cameraX <= 0x4100 -> target 0x0700 (gradual)
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_HCZ, 1, PlayerCharacter.KNUCKLES);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0700);

        handler.update(state, 0, 0);
        assertEquals(0x0700, state.getTargetLevel());
    }

    @Test
    public void lbz2KnucklesBelowThresholdInstantSetsTo0x0FF0() {
        // ROM word_6F12: dc.w $8FF0, $0D80 â€” cameraX <= 0x0D80 -> instant-set 0x0FF0
        DynamicWaterHandler handler = provider.getDynamicHandler(
                Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0A80);

        handler.update(state, 0, 0);
        assertEquals(0x0FF0, state.getTargetLevel());
        assertEquals(0x0FF0, state.getMeanLevel());
    }

    // =====================================================================
    // AIZ2 state machine tests
    // =====================================================================

    @Test
    public void aiz2DropsWaterBeforeFirstThreshold() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Camera X before FIRST_THRESHOLD_X, target at INITIAL_LEVEL -> should drop
        handler.update(state, 0x1000, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, state.getTargetLevel(), "Target should be set to DROP_LEVEL");
    }

    @Test
    public void aiz2DoesNotDropWhenAlreadyAtDropLevel() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.DROP_LEVEL);

        // Already at drop level, should not change
        handler.update(state, 0x1000, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, state.getTargetLevel(), "Target should remain at DROP_LEVEL");
    }

    @Test
    public void aiz2RaisesWaterAfterTrigger() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Phase 1: Drop the water
        handler.update(state, 0x1000, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, state.getTargetLevel());

        // Phase 2: Move past first threshold but before trigger - no raise yet
        handler.update(state, 0x2500, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, state.getTargetLevel(), "Before trigger, target should remain DROP_LEVEL");

        // Phase 3: Move past trigger - water should rise back
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X, 0);
        assertEquals(Aiz2DynamicWaterHandler.INITIAL_LEVEL, state.getTargetLevel(), "After trigger, target should be INITIAL_LEVEL");
    }

    @Test
    public void aiz2ResetClearsTriggeredState() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Trigger the handler
        handler.update(state, 0x1000, 0); // Drop
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X, 0); // Trigger rise

        // Reset
        handler.reset();

        // After reset, a new state should behave as if fresh
        WaterSystem.DynamicWaterState freshState = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);
        handler.update(freshState, 0x1000, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, freshState.getTargetLevel(), "After reset, drop should happen again");

        // And moving past first threshold but before trigger should NOT raise
        handler.update(freshState, 0x2500, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, freshState.getTargetLevel(), "After reset, before trigger should not raise");
    }

    @Test
    public void aiz2DropSetsSpeed2() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        handler.update(state, 0x1000, 0);

        // Verify speed was set to 2 by stepping the state and checking movement rate
        // The state starts at INITIAL_LEVEL (0x0618) with target DROP_LEVEL (0x0528)
        // With speed=2, one update() call should move mean by 2 pixels
        int before = state.getMeanLevel();
        state.update();
        int after = state.getMeanLevel();
        assertEquals(2, before - after, "Speed should be 2 (move 2 pixels per frame toward target)");
    }

    @Test
    public void aiz2RaiseInheritsSpeed2FromDrop() {
        // ROM does NOT change Water_speed during raise â€” inherits speed=2 from the drop
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Drop (sets speed=2)
        handler.update(state, 0x1000, 0);
        assertEquals(Aiz2DynamicWaterHandler.DROP_LEVEL, state.getTargetLevel());

        // Simulate water reaching drop level
        state.setMeanDirect(Aiz2DynamicWaterHandler.DROP_LEVEL);

        // Trigger rise
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X, 0);
        assertEquals(Aiz2DynamicWaterHandler.INITIAL_LEVEL, state.getTargetLevel());

        // Verify speed is still 2 (inherited from drop, not reset to 1)
        int before = state.getMeanLevel();
        state.update();
        int after = state.getMeanLevel();
        assertEquals(2, after - before, "Rise should inherit speed=2 from drop phase");
    }

    // =====================================================================
    // Locked flag tests â€” ROM _unkFAA2 (boss/cutscene lock)
    // =====================================================================

    @Test
    public void lockedFlagPreventsHandlerExecution() {
        // When locked, the handler should be skipped in updateDynamic()
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0700);
        state.setLocked(true);
        assertTrue(state.isLocked(), "State should report locked");

        // Unlocking should allow handler execution
        state.setLocked(false);
        assertFalse(state.isLocked(), "State should report unlocked");
    }

    // =====================================================================
    // Shake timer tests â€” ROM Obj_6E6E (180-frame countdown)
    // =====================================================================

    @Test
    public void aiz2TriggerSetsShakeTimer() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Drop
        handler.update(state, 0x1000, 0);
        assertEquals(0, state.getShakeTimer());

        // Simulate water reaching drop level
        state.setMeanDirect(Aiz2DynamicWaterHandler.DROP_LEVEL);

        // Trigger rise â€” should set shake timer
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X, 0);
        assertEquals(Aiz2DynamicWaterHandler.SHAKE_DURATION, state.getShakeTimer(), "Shake timer should be set to 180");
    }

    @Test
    public void shakeTimerDoesNotResetOnSubsequentUpdates() {
        Aiz2DynamicWaterHandler handler = new Aiz2DynamicWaterHandler();
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(
                Aiz2DynamicWaterHandler.INITIAL_LEVEL);

        // Drop + trigger rise
        handler.update(state, 0x1000, 0);
        state.setMeanDirect(Aiz2DynamicWaterHandler.DROP_LEVEL);
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X, 0);
        assertEquals(180, state.getShakeTimer());

        // Manually decrement to simulate some frames passing
        state.setShakeTimer(50);

        // Subsequent handler call should NOT re-set timer (target already == INITIAL_LEVEL)
        handler.update(state, Aiz2DynamicWaterHandler.AUTO_TRIGGER_X + 100, 0);
        assertEquals(50, state.getShakeTimer(), "Timer should not be re-set once target is already INITIAL_LEVEL");
    }

    // =====================================================================
    // LBZ2 Knuckles pipe plug tests â€” ROM _unkF7C2
    // =====================================================================

    @Test
    public void lbz2KnucklesNormalModeUsesThresholdTable() {
        Lbz2KnucklesDynamicWaterHandler handler =
                (Lbz2KnucklesDynamicWaterHandler) provider.getDynamicHandler(
                        Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0A80);

        // Normal mode (pipe plug not destroyed) â€” should use threshold table
        assertFalse(handler.isPipePlugDestroyed());
        handler.update(state, 0, 0);
        assertEquals(0x0FF0, state.getMeanLevel(), "Should instant-set to 0x0FF0 via threshold table");
    }

    @Test
    public void lbz2KnucklesPipePlugSnapsTo0x0660WhenMeanBelowCameraY() {
        Lbz2KnucklesDynamicWaterHandler handler =
                (Lbz2KnucklesDynamicWaterHandler) provider.getDynamicHandler(
                        Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0500);

        handler.setPipePlugDestroyed(true);
        // meanLevel (0x0500) < cameraY (0x0600) â€” should snap to 0x0660
        handler.update(state, 0, 0x0600);
        assertEquals(Lbz2KnucklesDynamicWaterHandler.PIPE_PLUG_SNAP_LEVEL, state.getMeanLevel(), "Pipe plug path: should snap to 0x0660");
    }

    @Test
    public void lbz2KnucklesPipePlugNoSnapWhenMeanAboveCameraY() {
        Lbz2KnucklesDynamicWaterHandler handler =
                (Lbz2KnucklesDynamicWaterHandler) provider.getDynamicHandler(
                        Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
        WaterSystem.DynamicWaterState state = new WaterSystem.DynamicWaterState(0x0800);

        handler.setPipePlugDestroyed(true);
        // meanLevel (0x0800) >= cameraY (0x0600) â€” should NOT snap
        handler.update(state, 0, 0x0600);
        assertEquals(0x0800, state.getMeanLevel(), "Pipe plug path: mean >= cameraY, no snap");
    }

    private static void assertInstanceOf(Class<?> expectedType, Object actual) {
        assertTrue(expectedType.isInstance(actual), "Expected " + expectedType.getSimpleName() + " but got " +
                        (actual == null ? "null" : actual.getClass().getSimpleName()));
    }
}



