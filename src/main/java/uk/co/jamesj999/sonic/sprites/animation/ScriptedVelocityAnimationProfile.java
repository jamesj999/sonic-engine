package uk.co.jamesj999.sonic.sprites.animation;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Chooses animation script IDs based on simple movement state.
 */
public class ScriptedVelocityAnimationProfile implements SpriteAnimationProfile {
    private final int idleAnimId;
    private final int walkAnimId;
    private final int runAnimId;
    private final int rollAnimId;
    private final int roll2AnimId;
    private final int pushAnimId;
    private final int duckAnimId;
    private final int spindashAnimId;
    private final int springAnimId;
    private final int deathAnimId;
    private final int hurtAnimId;
    private final int skidAnimId;
    private final int airAnimId;
    private final int runSpeedThreshold;
    private final int walkSpeedThreshold;
    private final int fallbackFrame;

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, rollAnimId, -1, -1, -1, -1, airAnimId,
                walkSpeedThreshold,
                runSpeedThreshold, fallbackFrame);
    }

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, -1, -1, -1, airAnimId,
                walkSpeedThreshold, runSpeedThreshold, fallbackFrame);
    }

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int duckAnimId,
            int spindashAnimId,
            int springAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, duckAnimId, spindashAnimId,
                springAnimId, -1, airAnimId, walkSpeedThreshold, runSpeedThreshold, fallbackFrame);
    }

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int duckAnimId,
            int spindashAnimId,
            int springAnimId,
            int deathAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, duckAnimId, spindashAnimId,
                springAnimId, deathAnimId, -1, airAnimId, walkSpeedThreshold, runSpeedThreshold, fallbackFrame);
    }

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int duckAnimId,
            int spindashAnimId,
            int springAnimId,
            int deathAnimId,
            int hurtAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, duckAnimId, spindashAnimId,
                springAnimId, deathAnimId, hurtAnimId, -1, airAnimId, walkSpeedThreshold, runSpeedThreshold, fallbackFrame);
    }

    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int duckAnimId,
            int spindashAnimId,
            int springAnimId,
            int deathAnimId,
            int hurtAnimId,
            int skidAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this.idleAnimId = Math.max(0, idleAnimId);
        this.walkAnimId = Math.max(0, walkAnimId);
        this.runAnimId = Math.max(0, runAnimId);
        this.rollAnimId = Math.max(0, rollAnimId);
        this.roll2AnimId = Math.max(-1, roll2AnimId);
        this.pushAnimId = Math.max(-1, pushAnimId);
        this.duckAnimId = Math.max(-1, duckAnimId);
        this.spindashAnimId = Math.max(-1, spindashAnimId);
        this.springAnimId = Math.max(-1, springAnimId);
        this.deathAnimId = Math.max(-1, deathAnimId);
        this.hurtAnimId = Math.max(-1, hurtAnimId);
        this.skidAnimId = Math.max(-1, skidAnimId);
        this.airAnimId = Math.max(0, airAnimId);
        this.walkSpeedThreshold = Math.max(0, walkSpeedThreshold);
        this.runSpeedThreshold = Math.max(0, runSpeedThreshold);
        this.fallbackFrame = Math.max(0, fallbackFrame);
    }

    @Override
    public Integer resolveAnimationId(AbstractPlayableSprite sprite, int frameCounter, int scriptCount) {
        if (sprite.getDead() && deathAnimId >= 0) {
            return deathAnimId;
        }
        // Hurt state uses separate hurt animation (animation 0x19)
        if (sprite.isHurt() && hurtAnimId >= 0) {
            return hurtAnimId;
        }
        if (sprite.getSpringing() && sprite.getAir() && springAnimId >= 0) {
            return springAnimId;
        }
        if (sprite.getSpindash() && spindashAnimId >= 0) {
            return spindashAnimId;
        }
        if (sprite.getRolling()) {
            return rollAnimId;
        }
        if (sprite.getAir()) {
            return airAnimId;
        }
        if (sprite.getCrouching() && duckAnimId >= 0) {
            return duckAnimId;
        }
        // ROM-accurate: Pushing state takes priority over speed-based animations
        if (sprite.getPushing() && pushAnimId >= 0) {
            return pushAnimId;
        }
        // ROM-accurate: Skidding state (braking at speed >= 0x400)
        if (sprite.getSkidding() && skidAnimId >= 0) {
            return skidAnimId;
        }
        int speed = Math.abs(sprite.getGSpeed());
        if (speed >= runSpeedThreshold) {
            return runAnimId;
        }
        if (speed >= walkSpeedThreshold) {
            return walkAnimId;
        }
        return idleAnimId;
    }

    @Override
    public int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        return Math.min(fallbackFrame, frameCount - 1);
    }

    public int getIdleAnimId() {
        return idleAnimId;
    }

    public int getWalkAnimId() {
        return walkAnimId;
    }

    public int getRunAnimId() {
        return runAnimId;
    }

    public int getRollAnimId() {
        return rollAnimId;
    }

    public int getRoll2AnimId() {
        return roll2AnimId;
    }

    public int getPushAnimId() {
        return pushAnimId;
    }

    public int getDuckAnimId() {
        return duckAnimId;
    }

    public int getSpindashAnimId() {
        return spindashAnimId;
    }

    public int getSpringAnimId() {
        return springAnimId;
    }

    public int getDeathAnimId() {
        return deathAnimId;
    }

    public int getSkidAnimId() {
        return skidAnimId;
    }

    public int getAirAnimId() {
        return airAnimId;
    }

    public int getRunSpeedThreshold() {
        return runSpeedThreshold;
    }

    public int getWalkSpeedThreshold() {
        return walkSpeedThreshold;
    }

    public int getFallbackFrame() {
        return fallbackFrame;
    }
}
