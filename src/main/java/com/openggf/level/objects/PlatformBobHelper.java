package com.openggf.level.objects;

import com.openggf.physics.TrigLookupTable;

/**
 * Sine-based standing-nudge displacement for platform objects.
 * <p>
 * When a player stands on a platform, the bob angle ramps up toward a maximum;
 * when the player leaves, it ramps back to zero. The vertical offset is computed
 * from the Mega Drive sine table at the current angle.
 * <p>
 * Used identically across S1 (Object 18), S2 (Object 18), and S3K (Object 0x51)
 * platforms. All three games use step=4, maxAngle=0x40, and an effective amplitude
 * of sinHex(angle) >> 6 (which equals (sinHex(angle) * 0x400) >> 16).
 * <p>
 * ROM references:
 * <ul>
 *   <li>S1: docs/s1disasm/_incObj/18 Platforms.asm — Plat_Nudge</li>
 *   <li>S2: docs/s2disasm/Objects/Obj18 - Platforms.asm — Obj18_Bob</li>
 *   <li>S3K: sonic3k.asm line 50190 — Platform_Stationary</li>
 * </ul>
 */
public final class PlatformBobHelper {

    /** Default angle step per frame (addq.b #4 / subq.b #4). */
    public static final int DEFAULT_STEP = 4;

    /** Default maximum angle (0x40 = 90 degrees in Mega Drive hex angles). */
    public static final int DEFAULT_MAX_ANGLE = 0x40;

    /** Default amplitude shift (>> 6, equivalent to * 0x400 >> 16). */
    public static final int DEFAULT_AMPLITUDE_SHIFT = 6;

    private final int stepSize;
    private final int maxAngle;
    private final int amplitudeShift;
    private int angle;

    /**
     * Creates a bob helper with the default parameters used by all three games.
     */
    public PlatformBobHelper() {
        this(DEFAULT_STEP, DEFAULT_MAX_ANGLE, DEFAULT_AMPLITUDE_SHIFT);
    }

    /**
     * Creates a bob helper with custom parameters.
     *
     * @param stepSize       angle increment/decrement per frame
     * @param maxAngle       maximum bob angle (clamped ceiling)
     * @param amplitudeShift right-shift applied to sinHex result
     */
    public PlatformBobHelper(int stepSize, int maxAngle, int amplitudeShift) {
        this.stepSize = stepSize;
        this.maxAngle = maxAngle;
        this.amplitudeShift = amplitudeShift;
    }

    /**
     * Advances the bob angle by one frame.
     * Increments toward {@code maxAngle} while standing, decrements toward 0 otherwise.
     *
     * @param isStanding true if a player is currently riding the platform
     */
    public void update(boolean isStanding) {
        if (isStanding) {
            angle = Math.min(angle + stepSize, maxAngle);
        } else {
            angle = Math.max(angle - stepSize, 0);
        }
    }

    /**
     * Returns the current vertical offset in pixels (positive = downward).
     * Computed as {@code TrigLookupTable.sinHex(angle) >> amplitudeShift}.
     */
    public int getOffset() {
        return TrigLookupTable.sinHex(angle) >> amplitudeShift;
    }

    /**
     * Returns the current bob angle (0 to maxAngle).
     */
    public int getAngle() {
        return angle;
    }

    /**
     * Restores the current bob angle from a rewind snapshot.
     */
    public void restoreAngle(int angle) {
        this.angle = Math.max(0, Math.min(angle, maxAngle));
    }
}
