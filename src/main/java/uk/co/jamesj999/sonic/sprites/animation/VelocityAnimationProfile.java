package uk.co.jamesj999.sonic.sprites.animation;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Simple state/velocity driven animation profile.
 */
public class VelocityAnimationProfile implements SpriteAnimationProfile {
    private final int idleFrame;
    private final int runStart;
    private final int runCount;
    private final int runFrameDelay;
    private final int airFrame;
    private final int rollFrame;
    private final int runSpeedThreshold;

    public VelocityAnimationProfile(
            int idleFrame,
            int runStart,
            int runCount,
            int runFrameDelay,
            int airFrame,
            int rollFrame,
            int runSpeedThreshold
    ) {
        this.idleFrame = Math.max(0, idleFrame);
        this.runStart = Math.max(0, runStart);
        this.runCount = Math.max(0, runCount);
        this.runFrameDelay = Math.max(1, runFrameDelay);
        this.airFrame = Math.max(0, airFrame);
        this.rollFrame = Math.max(0, rollFrame);
        this.runSpeedThreshold = Math.max(0, runSpeedThreshold);
    }

    @Override
    public int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        if (sprite.getAir()) {
            return clamp(airFrame, frameCount);
        }
        if (sprite.getRolling()) {
            return clamp(rollFrame, frameCount);
        }

        int speed = Math.abs(sprite.getGSpeed());
        if (speed >= runSpeedThreshold && runCount > 0) {
            int safeRunStart = clamp(runStart, frameCount);
            int available = Math.min(runCount, Math.max(0, frameCount - safeRunStart));
            if (available > 0) {
                int offset = (frameCounter / runFrameDelay) % available;
                return safeRunStart + offset;
            }
        }
        return clamp(idleFrame, frameCount);
    }

    private int clamp(int frame, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        return Math.min(frame, frameCount - 1);
    }
}
