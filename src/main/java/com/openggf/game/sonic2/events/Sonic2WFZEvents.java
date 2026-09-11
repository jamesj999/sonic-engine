package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.nio.ByteBuffer;

/**
 * Wing Fortress Zone events.
 * ROM: LevEvents_WFZ (s2.asm:20560-20684)
 *
 * WFZ uses a unique dual-dispatch system. Each frame, it runs BOTH:
 * 1. Primary routine (indexed by Dynamic_Resize_Routine) - BG scroll management
 * 2. Secondary routine (indexed by WFZ_LevEvent_Subrout) - boss arena events
 *
 * WFZ boss spawns from level object layout (subtype $92), NOT from events.
 *
 * Primary routines (LevEvents_WFZ_Index):
 *   R0: Init BG sync (sync Camera_BG_X/Y_pos to camera, zero offsets/diffs)
 *   R2: Sync BG diffs until camera reaches platform ride trigger
 *   R4: Platform ride - accelerate BG X offset up to $800, accelerate BG Y speed
 *   R6: Reverse - decelerate BG X offset to -$2C0, decelerate BG Y speed
 *
 * Secondary routines (LevEvents_WFZ_Index2):
 *   S0: Boss PLC trigger at camera ($2880, $400)
 *   S2: control lock + Tornado PLC at camera Y >= $500
 *   S4: No-op
 */
public class Sonic2WFZEvents extends Sonic2ZoneEvents {
    public static final int SNAPSHOT_BYTES = Integer.BYTES * 8;

    // =========================================================================
    // Primary routine trigger thresholds
    // =========================================================================

    /** Camera X threshold for platform ride trigger (ROM: $2BC0) */
    private static final int PLATFORM_RIDE_TRIGGER_X = 0x2BC0;

    /** Camera Y threshold for platform ride trigger (ROM: $580) */
    private static final int PLATFORM_RIDE_TRIGGER_Y = 0x580;

    // =========================================================================
    // Primary routine R4 (platform ride) constants
    // =========================================================================

    /** Max BG X offset during platform ride (ROM: $800) */
    private static final int BG_X_OFFSET_MAX = 0x800;

    /** BG X offset threshold before Y acceleration starts (ROM: $600) */
    private static final int BG_X_OFFSET_Y_ACCEL_THRESHOLD = 0x600;

    /** BG Y speed max (0.8 fixed point, ROM: $840) */
    private static final int BG_Y_SPEED_MAX = 0x840;

    /** BG Y speed acceleration increment (ROM: 4) */
    private static final int BG_Y_SPEED_ACCEL = 4;

    // =========================================================================
    // Primary routine R6 (reverse) constants
    // =========================================================================

    /** Min BG X offset during reverse (ROM: -$2C0 = 0xFD40 signed 16-bit) */
    private static final int BG_X_OFFSET_MIN = -0x2C0;

    /** BG Y offset limit during reverse (ROM: $1B81) */
    private static final int BG_Y_OFFSET_LIMIT = 0x1B81;

    // =========================================================================
    // Secondary routine trigger thresholds
    // =========================================================================

    /** Camera X threshold for boss PLC trigger (ROM: $2880) */
    private static final int BOSS_PLC_TRIGGER_X = 0x2880;

    /** Camera Y threshold for boss PLC trigger (ROM: $400) */
    private static final int BOSS_PLC_TRIGGER_Y = 0x400;

    /** Camera Y threshold for control lock (ROM: $500) */
    private static final int CONTROL_LOCK_TRIGGER_Y = 0x500;

    // =========================================================================
    // BG scroll state
    // ROM: Camera_BG_X_offset, Camera_BG_Y_offset, WFZ_BG_Y_Speed
    // =========================================================================

    /** Camera_BG_X_offset: signed offset for BG X scroll (range: -$2C0 to +$800) */
    private int bgXOffset;

    /** Camera_BG_Y_offset: offset for BG Y scroll */
    private int bgYOffset;

    /** WFZ_BG_Y_Speed: 0.8 fixed-point BG Y speed (max $840) */
    private int bgYSpeed;

    /**
     * Camera_BG_X_pos: BG camera X position.
     * Synced to Camera_X_pos in routine 0, then tracked via diffs.
     */
    private int bgXPos;

    /**
     * Camera_BG_Y_pos: BG camera Y position.
     * Synced to Camera_Y_pos in routine 0, then tracked via diffs.
     */
    private int bgYPos;

    /** Camera_BG_X_pos_diff: per-frame BG X position delta */
    private int bgXPosDiff;

    /** Camera_BG_Y_pos_diff: per-frame BG Y position delta */
    private int bgYPosDiff;

    /** WFZ_LevEvent_Subrout: secondary routine counter */
    private int wfzSubRoutine;

    private int lastRequestedPlcId = -1;
    private int plcRequestCount;

    public Sonic2WFZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        bgXOffset = 0;
        bgYOffset = 0;
        bgYSpeed = 0;
        bgXPos = 0;
        bgYPos = 0;
        bgXPosDiff = 0;
        bgYPosDiff = 0;
        wfzSubRoutine = 0;
        lastRequestedPlcId = -1;
        plcRequestCount = 0;
    }

    /**
     * WFZ dual-dispatch: run primary routine (BG scroll), then secondary routine (boss events).
     * ROM: LevEvents_WFZ uses JSR for primary (returns), then JMP for secondary.
     */
    @Override
    public void update(int act, int frameCounter) {
        // Run primary routine (BG scroll management)
        updatePrimaryRoutine();

        // Run secondary routine (boss arena events)
        updateSecondaryRoutine();
    }

    // =========================================================================
    // Public Getters (for SwScrlWfz scroll handler)
    // =========================================================================

    /** Returns Camera_BG_X_offset. Range: -$2C0 to +$800. */
    public int getBgXOffset() {
        return bgXOffset;
    }

    /** Returns Camera_BG_Y_offset. */
    public int getBgYOffset() {
        return bgYOffset;
    }

    /** Returns Camera_BG_X_pos. */
    public int getBgXPos() {
        return bgXPos;
    }

    /** Returns Camera_BG_Y_pos. */
    public int getBgYPos() {
        return bgYPos;
    }

    /** Returns WFZ_BG_Y_Speed (0.8 fixed point). */
    public int getBgYSpeed() {
        return bgYSpeed;
    }

    public int getWfzSubRoutine() {
        return wfzSubRoutine;
    }

    public void setWfzSubRoutine(int wfzSubRoutine) {
        this.wfzSubRoutine = wfzSubRoutine;
    }

    public int getLastRequestedPlcIdForTest() {
        return lastRequestedPlcId;
    }

    public int getPlcRequestCountForTest() {
        return plcRequestCount;
    }

    public void setBgXOffsetForTest(int bgXOffset) {
        this.bgXOffset = bgXOffset;
    }

    public void setBgYOffsetForTest(int bgYOffset) {
        this.bgYOffset = bgYOffset;
    }

    public void setBgYSpeedForTest(int bgYSpeed) {
        this.bgYSpeed = bgYSpeed;
    }

    public void captureSnapshot(ByteBuffer buf) {
        buf.putInt(bgXOffset);
        buf.putInt(bgYOffset);
        buf.putInt(bgYSpeed);
        buf.putInt(bgXPos);
        buf.putInt(bgYPos);
        buf.putInt(bgXPosDiff);
        buf.putInt(bgYPosDiff);
        buf.putInt(wfzSubRoutine);
    }

    public void restoreSnapshot(ByteBuffer buf) {
        bgXOffset = buf.getInt();
        bgYOffset = buf.getInt();
        bgYSpeed = buf.getInt();
        bgXPos = buf.getInt();
        bgYPos = buf.getInt();
        bgXPosDiff = buf.getInt();
        bgYPosDiff = buf.getInt();
        wfzSubRoutine = buf.getInt();
    }

    // =========================================================================
    // Primary Routines (BG scroll management)
    // ROM: LevEvents_WFZ_Index table - Routine1 through Routine4
    // =========================================================================

    private void updatePrimaryRoutine() {
        switch (eventRoutine) {
            case 0 -> primaryRoutine0_initBgSync();
            case 2 -> primaryRoutine2_syncUntilTrigger();
            case 4 -> primaryRoutine4_platformRide();
            case 6 -> primaryRoutine6_reverse();
            default -> {
                // No further primary routines
            }
        }
    }

    /**
     * Primary R0: Init BG sync.
     * ROM: LevEvents_WFZ_Routine1 (s2.asm)
     *
     * Syncs Camera_BG_X/Y_pos to Camera_X/Y_pos, zeros all diffs and offsets,
     * then advances to routine 2.
     */
    private void primaryRoutine0_initBgSync() {
        // ROM: move.l (Camera_X_pos).w,(Camera_BG_X_pos).w
        bgXPos = camera().getX();
        // ROM: move.l (Camera_Y_pos).w,(Camera_BG_Y_pos).w
        bgYPos = camera().getY();
        // ROM: moveq #0,d0 + clear all offset/diff words
        bgXPosDiff = 0;
        bgYPosDiff = 0;
        bgXOffset = 0;
        bgYOffset = 0;

        // ROM: addq.b #2,(Dynamic_Resize_Routine).w
        eventRoutine += 2;
    }

    /**
     * Primary R2: Sync BG diffs until platform ride trigger.
     * ROM: LevEvents_WFZ_Routine2 (s2.asm)
     *
     * Chases the offset-adjusted camera position each frame. When camera reaches
     * ($2BC0, $580), advances to platform ride routine and zeros BG Y speed.
     */
    private void primaryRoutine2_syncUntilTrigger() {
        // ROM: cmpi.w #$2BC0,(Camera_X_pos).w / blo.s +
        // ROM: cmpi.w #$580,(Camera_Y_pos).w / blo.s +
        if (camera().getX() >= PLATFORM_RIDE_TRIGGER_X &&
                camera().getY() >= PLATFORM_RIDE_TRIGGER_Y) {
            // ROM: addq.b #2,(Dynamic_Resize_Routine).w
            eventRoutine += 2;
            // ROM: move.w #0,(WFZ_BG_Y_Speed).w
            bgYSpeed = 0;
        }

        // ROM: Sync BG diffs + ScrollBG (always executed, even after advancing)
        syncBgDiffs();
    }

    /**
     * Primary R4: Platform ride - increase BG X offset, accelerate BG Y.
     * ROM: LevEvents_WFZ_Routine3 (s2.asm)
     *
     * BG X offset increments by 2 each frame up to $800.
     * Once BG X offset >= $600, BG Y speed accelerates by 4 each frame up to $840.
     * BG Y offset increases by (bgYSpeed >> 8) each frame.
     */
    private void primaryRoutine4_platformRide() {
        // ROM: cmpi.w #$800,(Camera_BG_X_offset).w / beq.s +
        if (bgXOffset != BG_X_OFFSET_MAX) {
            // ROM: addq.w #2,(Camera_BG_X_offset).w
            bgXOffset += 2;
        }

        // ROM: cmpi.w #$600,(Camera_BG_X_offset).w / blt.s LevEvents_WFZ_Routine3_Part2
        if (bgXOffset >= BG_X_OFFSET_Y_ACCEL_THRESHOLD) {
            // ROM: move.w (WFZ_BG_Y_Speed).w,d0
            int speed = bgYSpeed;
            // ROM: cmpi.w #$840,d0 / bhs.s +
            if (speed < BG_Y_SPEED_MAX) {
                // ROM: add.w d1,d0 (d1=4) / move.w d0,(WFZ_BG_Y_Speed).w
                speed += BG_Y_SPEED_ACCEL;
                bgYSpeed = speed;
            }

            // ROM: lsr.w #8,d0 / add.w d0,(Camera_BG_Y_offset).w
            bgYOffset += (speed >> 8);
        }

        // ROM: LevEvents_WFZ_Routine3_Part2 - sync BG diffs + ScrollBG
        syncBgDiffs();
    }

    /**
     * Primary R6: Reverse - decrease BG X offset, decelerate BG Y.
     * ROM: LevEvents_WFZ_Routine4 (s2.asm)
     *
     * BG X offset decrements by 2 each frame down to -$2C0.
     * BG Y speed decelerates by 4 each frame down to 0.
     * BG Y offset increases by ((bgYSpeed >> 8) + 1) each frame,
     * capped at $1B81.
     */
    private void primaryRoutine6_reverse() {
        // ROM: cmpi.w #-$2C0,(Camera_BG_X_offset).w / beq.s ++
        boolean xDone = (bgXOffset <= BG_X_OFFSET_MIN);
        if (!xDone) {
            // ROM: subi_.w #2,(Camera_BG_X_offset).w
            bgXOffset -= 2;
        }

        // ROM: cmpi.w #$1B81,(Camera_BG_Y_offset).w / beq.s ++
        boolean yDone = (bgYOffset >= BG_Y_OFFSET_LIMIT);
        if (!xDone && !yDone) {
            // ROM: move.w (WFZ_BG_Y_Speed).w,d0 / beq.s +
            int speed = bgYSpeed;
            if (speed != 0) {
                // ROM: neg.w d1 (d1 = -4) / add.w d1,d0
                speed -= BG_Y_SPEED_ACCEL;
                bgYSpeed = speed;
                // ROM: lsr.w #8,d0
                speed = speed >> 8;
            }
            // ROM: addq.w #1,d0 / add.w d0,(Camera_BG_Y_offset).w
            bgYOffset += (speed + 1);
        }

        // ROM: Sync BG diffs + ScrollBG (same tail as R2/R4)
        syncBgDiffs();
    }

    /**
     * Common tail shared by primary routines 2, 4, and 6.
     * Computes the offset-adjusted camera delta, caps it to ScrollBG's
     * 16-pixel reload limit, and advances the tracked BG position.
     *
     * ROM equivalent of:
     *   move.w (Camera_X_pos_diff).w,(Camera_BG_X_pos_diff).w
     *   move.w (Camera_Y_pos_diff).w,(Camera_BG_Y_pos_diff).w
     *   move.w (Camera_X_pos).w,d0
     *   move.w (Camera_Y_pos).w,d1
     *   bra.w ScrollBG
     *
     * The actual BG rendering is handled by SwScrlWfz; we maintain the
     * state variables it will read.
     */
    private void syncBgDiffs() {
        int dx = clamp16(camera().getX() - bgXPos - bgXOffset);
        int dy = clamp16(camera().getY() - bgYPos - bgYOffset);
        bgXPosDiff = dx;
        bgYPosDiff = dy;
        bgXPos += dx;
        bgYPos += dy;
    }

    private static int clamp16(int value) {
        return value > 16 ? 16 : (value < -16 ? -16 : value);
    }

    // =========================================================================
    // Secondary Routines (boss arena events)
    // ROM: LevEvents_WFZ_Index2 table - Routine5, Routine6, RoutineNull
    // =========================================================================

    private void updateSecondaryRoutine() {
        switch (wfzSubRoutine) {
            case 0 -> secondaryRoutine0_bossPLC();
            case 2 -> secondaryRoutine2_controlLock();
            case 4 -> {
                // ROM: LevEvents_WFZ_RoutineNull - rts (no-op)
            }
            default -> {
                // No further secondary routines
            }
        }
    }

    public void updatePrePhysicsControlLock() {
        if (wfzSubRoutine == 2) {
            secondaryRoutine2_controlLock();
        }
    }

    /**
     * Secondary S0: Boss PLC trigger.
     * ROM: LevEvents_WFZ_Routine5 (s2.asm)
     *
     * When camera reaches ($2880, $400), loads WFZ boss PLC and locks
     * left camera boundary.
     */
    private void secondaryRoutine0_bossPLC() {
        // ROM: cmpi.w #$2880,(Camera_X_pos).w / blo.s +
        if (camera().getX() < BOSS_PLC_TRIGGER_X) {
            return;
        }
        // ROM: cmpi.w #$400,(Camera_Y_pos).w / blo.s +
        if (camera().getY() < BOSS_PLC_TRIGGER_Y) {
            return;
        }

        // ROM: moveq #PLCID_WfzBoss,d0 / jsrto JmpTo2_LoadPLC
        if (!requestPlc(Sonic2Constants.PLC_WFZ_BOSS)) {
            return;
        }

        // ROM: addq.w #2,(WFZ_LevEvent_Subrout).w
        wfzSubRoutine += 2;

        // ROM: move.w #$2880,(Camera_Min_X_pos).w
        camera().setMinX((short) BOSS_PLC_TRIGGER_X);
    }

    /**
     * Secondary S2: control lock + Tornado PLC.
     * ROM: LevEvents_WFZ_Routine6 (s2.asm)
     *
     * When camera Y reaches $500, locks player controls and loads Tornado PLC.
     */
    private void secondaryRoutine2_controlLock() {
        // ROM: cmpi.w #$500,(Camera_Y_pos).w / blo.s +
        if (camera().getY() < CONTROL_LOCK_TRIGGER_Y) {
            return;
        }

        // ROM: st.b (Control_Locked).w + moveq #PLCID_Tornado,d0 / jsrto JmpTo2_LoadPLC
        if (!requestPlc(Sonic2Constants.PLC_TORNADO)) {
            return;
        }
        lockPlayerInputWithCurrentLogicalState();

        // ROM: addq.w #2,(WFZ_LevEvent_Subrout).w
        wfzSubRoutine += 2;
    }

    private void lockPlayerInputWithCurrentLogicalState() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        int mask = player.getLogicalInputState();
        player.setControlLocked(true);
        player.setForcedInputMask(mask);
    }

    private boolean requestPlc(int plcId) {
        if (!requestSonic2Plc(plcId)) {
            return false;
        }
        lastRequestedPlcId = plcId;
        plcRequestCount++;
        return true;
    }
}
