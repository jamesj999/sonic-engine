package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * HUD rendering for the S3K Blue Ball special stage.
 * <p>
 * Displays the sphere count and ring count as 3-digit numbers,
 * plus the sphere/ring icons.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm
 * Update_SSMap (line 11049), Draw_SSNum (line 11212)
 */
public class Sonic3kSpecialStageHud {

    /** Whether the sphere count needs redrawing. */
    private boolean sphereHudDirty;
    /** Whether the ring count needs redrawing. */
    private boolean ringHudDirty;

    /** Cached display values. */
    private int displayedSphereCount;
    private int displayedRingCount;

    public void initialize() {
        sphereHudDirty = true;
        ringHudDirty = true;
        displayedSphereCount = -1;
        displayedRingCount = -1;
    }

    /**
     * Mark the sphere count HUD as needing a redraw.
     * ROM: move.b #$FF,(Special_stage_sphere_HUD_flag).w
     */
    public void markSphereDirty() {
        sphereHudDirty = true;
    }

    /**
     * Mark the ring count HUD as needing a redraw.
     * ROM: bset #7,(Special_stage_extra_life_flags).w
     */
    public void markRingDirty() {
        ringHudDirty = true;
    }

    /**
     * Update HUD display values.
     *
     * @param sphereCount current sphere count
     * @param ringCount current ring count
     */
    public void update(int sphereCount, int ringCount) {
        if (sphereCount != displayedSphereCount) {
            displayedSphereCount = sphereCount;
            sphereHudDirty = true;
        }
        if (ringCount != displayedRingCount) {
            displayedRingCount = ringCount;
            ringHudDirty = true;
        }
    }

    /**
     * Decompose a number into 3 digits for display.
     * ROM: Draw_SSNum uses SSNum_Precision [100, 10, 1]
     *
     * @param value the number (0-999)
     * @return array of 3 digit values [hundreds, tens, ones]
     */
    public static int[] toDigits(int value) {
        int[] digits = new int[3];
        int remaining = Math.max(0, Math.min(value, 999));
        for (int i = 0; i < 3; i++) {
            digits[i] = remaining / HUD_PRECISION[i];
            remaining %= HUD_PRECISION[i];
        }
        return digits;
    }

    public boolean isSphereDirty() { return sphereHudDirty; }
    public boolean isRingDirty() { return ringHudDirty; }
    public int getDisplayedSphereCount() { return displayedSphereCount; }
    public int getDisplayedRingCount() { return displayedRingCount; }

    Sonic3kSpecialStageSnapshot.HudSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.HudSnapshot(
                sphereHudDirty, ringHudDirty, displayedSphereCount, displayedRingCount);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.HudSnapshot snapshot) {
        sphereHudDirty = snapshot.sphereHudDirty();
        ringHudDirty = snapshot.ringHudDirty();
        displayedSphereCount = snapshot.displayedSphereCount();
        displayedRingCount = snapshot.displayedRingCount();
    }

    public void clearSphereDirty() { sphereHudDirty = false; }
    public void clearRingDirty() { ringHudDirty = false; }
}
