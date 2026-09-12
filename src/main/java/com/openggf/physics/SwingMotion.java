package com.openggf.physics;

/**
 * Port of Swing_UpAndDown (sonic3k.asm:177851).
 * Oscillating motion utility for pendulum/bobbing objects.
 *
 * The object swings between +max and -max velocity, reversing direction
 * at each peak. Used by AIZ intro plane, swinging platforms, etc.
 */
public final class SwingMotion {
    private SwingMotion() {}

    public record Result(int velocity, boolean directionDown, boolean directionChanged) {}

    /**
     * Update swing motion for one frame.
     *
     * @param acceleration per-frame acceleration magnitude (ROM: $40(a0))
     * @param velocity     current velocity (ROM: y_vel)
     * @param maxVelocity  peak velocity magnitude (ROM: $3E(a0))
     * @param directionDown true=swinging down/positive, false=swinging up/negative (ROM: bit 0 of $38)
     * @return updated velocity, direction, and whether direction changed this frame
     */
    public static Result update(int acceleration, int velocity, int maxVelocity, boolean directionDown) {
        int signedAcceleration = acceleration;
        int nextVelocity = velocity;
        int velocityLimit = maxVelocity;
        boolean directionChanged = false;

        // ROM: if bit0 clear, apply upward acceleration first.
        if (!directionDown) {
            signedAcceleration = -signedAcceleration;
            nextVelocity += signedAcceleration;
            velocityLimit = -velocityLimit;
            if (nextVelocity <= velocityLimit) {
                // Hit upper bound: flip direction and cancel the overshoot step.
                directionDown = true;
                signedAcceleration = -signedAcceleration;
                velocityLimit = -velocityLimit;
                directionChanged = true;
            } else {
                return new Result(nextVelocity, false, false);
            }
        }

        // Downward phase (also entered immediately after an upper-bound flip).
        nextVelocity += signedAcceleration;
        if (nextVelocity >= velocityLimit) {
            // Hit lower bound: flip direction and cancel the overshoot step.
            directionDown = false;
            signedAcceleration = -signedAcceleration;
            nextVelocity += signedAcceleration;
            directionChanged = true;
        }

        return new Result(nextVelocity, directionDown, directionChanged);
    }
}
