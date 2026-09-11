package com.openggf.game.sonic3k;

import com.openggf.game.DynamicWaterHandler;
import com.openggf.level.WaterSystem;

/**
 * Dynamic water handler for Angel Island Zone Act 2.
 * Implements the ROM logic from DynamicWaterHeight_AIZ2 (sonic3k.asm:8648-8695).
 * <p>
 * The water starts at 0x0528 (from StartingWaterHeights.bin), drops with speed=2
 * early in the act, then rises back to 0x0618 when triggered.
 * <p>
 * Trigger sources (ROM: loc_6E14):
 * <ul>
 *   <li>Button 0x33 (subtype 0x10) sets Level_trigger_array[0] — immediate trigger</li>
 *   <li>Camera reaching X=$2850 auto-sets Level_trigger_array[0] — checkpoint fallback</li>
 * </ul>
 * The rise inherits the current speed (2 after the drop).
 */
public class Aiz2DynamicWaterHandler implements DynamicWaterHandler {

    /** Water level the ROM raises TO after the trigger (NOT the starting height).
     *  Starting height is 0x0528 from StartingWaterHeights.bin. */
    static final int INITIAL_LEVEL = 0x0618;

    /** Water level after the early drop. */
    static final int DROP_LEVEL = 0x0528;

    /** Camera X below which the initial drop occurs. */
    static final int FIRST_THRESHOLD_X = 0x2440;

    /** Camera X at which the trigger is auto-set if the button hasn't been pressed.
     *  ROM: cmpi.w #$2850,(Camera_X_pos).w */
    static final int AUTO_TRIGGER_X = 0x2850;

    /** Camera X above which screen shake is skipped (already past the visual area).
     *  ROM: cmpi.w #$2900,(Camera_X_pos).w / bhs.s loc_6E52 */
    static final int SKIP_SHAKE_X = 0x2900;

    /** Frames of screen shake when the rise is triggered (ROM: Obj_6E6E timer). */
    static final int SHAKE_DURATION = 180;

    /** Trigger index in Level_trigger_array checked by this handler (entry 0). */
    private static final int TRIGGER_INDEX = 0;

    /** Whether the water-raise + shake has already been applied this session. */
    private boolean waterRaised;

    public Aiz2DynamicWaterHandler() {
        this.waterRaised = false;
    }

    @Override
    public void update(WaterSystem.DynamicWaterState state, int cameraX, int cameraY) {
        // Phase 1: Before first threshold, if at initial level -> drop water
        // ROM loc_6DF6: cmpi.w #$2440,(Camera_X_pos).w / bhs.s loc_6E14
        if (cameraX < FIRST_THRESHOLD_X) {
            if (state.getTargetLevel() == INITIAL_LEVEL) {
                state.setTarget(DROP_LEVEL);
                state.setSpeed(2);
            }
            return;
        }

        // Phase 2: Camera past $2440 — check Level_trigger_array[0]
        // ROM loc_6E14: tst.b (Level_trigger_array).w / bne.s loc_6E28
        boolean triggerSet = Sonic3kLevelTriggerManager.testAny(TRIGGER_INDEX);

        if (!triggerSet) {
            // Button not pressed yet — check if camera reached auto-trigger X
            // ROM: cmpi.w #$2850,(Camera_X_pos).w / blo.s locret_6E6C
            if (cameraX < AUTO_TRIGGER_X) {
                return; // Not yet — wait for button press or camera progress
            }
            // Auto-set trigger at checkpoint X (ensures respawn consistency)
            // ROM: move.b #1,(Level_trigger_array).w
            Sonic3kLevelTriggerManager.setBit(TRIGGER_INDEX, 0);
        }

        // Phase 3: Trigger is set — raise water to 0x0618
        // ROM loc_6E28: cmpi.w #$618,(Target_water_level).w / beq.s locret_6E6C
        if (state.getTargetLevel() == INITIAL_LEVEL) {
            return; // Already at target
        }

        // Screen shake only if camera < $2900 and not already started
        // ROM: cmpi.w #$2900,(Camera_X_pos).w / bhs.s loc_6E52
        if (!waterRaised && cameraX < SKIP_SHAKE_X) {
            state.setShakeTimer(SHAKE_DURATION);
        }
        waterRaised = true;

        // ROM loc_6E52: move.w #$618,(Target_water_level).w
        // ROM does NOT change Water_speed here — it inherits the last-set speed
        // (initially 1, or 2 after a drop cycle)
        state.setTarget(INITIAL_LEVEL);
    }

    @Override
    public boolean shakeTimerOccupiesObjectSlot() {
        return true;
    }

    /**
     * Reset handler state for level reload.
     */
    public void reset() {
        this.waterRaised = false;
        Sonic3kLevelTriggerManager.clearBit(TRIGGER_INDEX, 0);
        Sonic3kLevelTriggerManager.clearBit(TRIGGER_INDEX, 7);
    }
}
