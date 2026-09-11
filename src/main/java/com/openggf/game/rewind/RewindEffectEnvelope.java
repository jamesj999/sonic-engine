package com.openggf.game.rewind;

/**
 * Intensity envelope for the VHS picture-search rewind presentation.
 * <p>
 * Attack is fast (a VCR degrades the picture almost immediately when the head
 * speed changes); release is slower and covers the coast-after-release window.
 * The tape speed is latched from the last active frame so the release tail
 * keeps its scroll motion even though {@code RewindSpeedController} resets to
 * zero on the first non-held frame when tape coast is disabled.
 */
public final class RewindEffectEnvelope {

    private static final float ATTACK_PER_FRAME = 1.0f / 4.0f;
    private static final float RELEASE_PER_FRAME = 1.0f / 10.0f;
    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4.0f;
    private static final float DEFAULT_SPEED = 1.0f;

    private float intensity;
    private float latchedSpeed = DEFAULT_SPEED;

    /** Tick one frame in which rewind is actively stepping (held or coasting). */
    public void frameActive(double currentSpeed) {
        intensity = Math.min(1.0f, intensity + ATTACK_PER_FRAME);
        if (currentSpeed > 0.0) {
            latchedSpeed = (float) Math.max(MIN_SPEED, Math.min(MAX_SPEED, currentSpeed));
        }
    }

    /** Tick one frame in which rewind is not stepping. */
    public void frameInactive() {
        intensity = Math.max(0.0f, intensity - RELEASE_PER_FRAME);
    }

    /** Instantly kill the effect (boundaries, mode exits, teardown). */
    public void reset() {
        intensity = 0.0f;
        latchedSpeed = DEFAULT_SPEED;
    }

    public float intensity() {
        return intensity;
    }

    public float speed() {
        return latchedSpeed;
    }
}
