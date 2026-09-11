package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.boss.BossStateContext;

/**
 * Shared swing motion utility for AIZ miniboss objects (0x90 and 0x91).
 * Implements Swing_Setup1, Swing_UpAndDown, and Swing_UpAndDown_Count from ROM.
 *
 * ROM references:
 * - Swing_Setup1: sets max speed ($3E) = $C0, y_vel = $C0, accel ($40) = $10, clears direction bit
 * - Swing_UpAndDown: oscillates y_vel using direction flag (bit 0 of $38), returns d3=1 on peak
 * - Swing_UpAndDown_Count: calls Swing_UpAndDown, decrements $39 counter on each peak,
 *   returns d0=1 when counter goes negative
 */
public class AizMinibossSwingMotion {
    private int maxSpeed;
    private int accel;
    private boolean goingDown; // ROM: bit 0 of $38(a0)

    /** Count of remaining half-cycles; goes negative when expired. */
    private int cycleCounter;

    /**
     * ROM: Swing_Setup1
     * Sets max speed = $C0, y_vel = $C0, accel = $10, direction = up (decelerating).
     */
    public void setup1(BossStateContext state) {
        this.maxSpeed = 0xC0;
        this.accel = 0x10;
        this.goingDown = false; // ROM: bclr #0,$38(a0)
        state.yVel = maxSpeed;
    }

    /**
     * Set the half-cycle counter for Swing_UpAndDown_Count mode.
     * ROM: move.b #N,$39(a0)
     */
    public void setCycleCounter(int count) {
        this.cycleCounter = count;
    }

    /**
     * ROM: Swing_UpAndDown — oscillate y_vel between -maxSpeed and +maxSpeed.
     * Returns true if a half-cycle peak was reached (d3=1 in ROM).
     *
     * <p>ROM (sonic3k.asm:177851 Swing_UpAndDown) flow at peak:
     * <pre>
     *   ; going up branch (bit 0 of $38 clear):
     *   neg.w  d0           ; d0 = -accel
     *   add.w  d0,d1        ; d1 -= accel
     *   neg.w  d2           ; d2 = -maxSpeed
     *   cmp.w  d2,d1
     *   bgt.s  loc_84824    ; if d1 > -maxSpeed, store d1 and return (not at peak)
     *   bset   #0,$38(a0)   ; flip direction
     *   neg.w  d0           ; d0 = +accel
     *   neg.w  d2           ; d2 = +maxSpeed
     *   moveq  #1,d3        ; signal peak
     * loc_84812:
     *   add.w  d0,d1        ; bounce-back: d1 += accel (so peak vel = -maxSpeed + accel)
     *   cmp.w  d2,d1
     *   blt.s  loc_84824    ; if d1 < +maxSpeed, store d1 (peak handled)
     * </pre>
     * The peak frame's stored y_vel is therefore {@code -maxSpeed + accel}
     * (or {@code +maxSpeed - accel} on the down peak), NOT a clamped extreme.
     * Skipping this bounce-back kept y_vel at the extreme for one extra frame
     * and pushed the AIZ miniboss swing apex 6+ frames out of sync with ROM,
     * causing the engine to detect the boss/Sonic overlap one frame ahead of
     * ROM at trace F7660 (sonic3k.asm:20913 neg.w x_vel/y_vel/ground_vel).
     */
    public boolean update(BossStateContext state) {
        boolean peakReached = false;

        if (!goingDown) {
            // ROM going-up branch: d0 = -accel; d1 += d0; cmp d1 with -maxSpeed.
            int vel = state.yVel - accel;
            if (vel <= -maxSpeed) {
                // Peak reached. ROM toggles direction (bset #0), negates d0/d2,
                // then loc_84812 adds the now-positive d0 back to d1 in the SAME
                // frame. So the stored peak velocity is (vel + accel), which lies
                // strictly inside the (-maxSpeed, +maxSpeed) window.
                goingDown = true;
                peakReached = true;
                vel += accel; // bounce-back step (loc_84812: add.w d0,d1)
            }
            state.yVel = vel;
        } else {
            // ROM going-down branch (bit 0 set): d0 already +accel, d1 += d0.
            int vel = state.yVel + accel;
            if (vel >= maxSpeed) {
                // Peak reached. ROM clears bit 0, negates d0 (-> -accel), adds d0
                // back to d1 in the SAME frame so the stored peak velocity is
                // (vel - accel).
                goingDown = false;
                peakReached = true;
                vel -= accel; // bounce-back step (loc_84818: add.w d0,d1 with d0 negated)
            }
            state.yVel = vel;
        }

        return peakReached;
    }

    /**
     * ROM: Swing_UpAndDown_Count — update swing and count half-cycles.
     *
     * <p>Returns a result indicating whether the count has expired.
     * When the cycle counter goes negative, the caller should check
     * {@code state.yVel} to decide when to transition (ROM checks d1 < 0).
     *
     * @return {@link CountResult#CONTINUE} while swinging,
     *         {@link CountResult#EXPIRED} once the counter goes negative.
     */
    public CountResult updateAndCount(BossStateContext state) {
        boolean peak = update(state);
        if (!peak) {
            return CountResult.CONTINUE;
        }
        cycleCounter--;
        if (cycleCounter < 0) {
            return CountResult.EXPIRED;
        }
        return CountResult.CONTINUE;
    }

    public enum CountResult {
        CONTINUE,
        EXPIRED
    }
}
