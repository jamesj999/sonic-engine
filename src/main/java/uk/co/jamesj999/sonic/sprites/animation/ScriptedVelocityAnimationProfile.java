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
    private final int lookUpAnimId;
    private final int spindashAnimId;
    private final int springAnimId;
    private final int deathAnimId;
    private final int hurtAnimId;
    private final int skidAnimId;
    private final int airAnimId;
    // Balance animations (ROM s2.asm:36246-36373)
    private final int balanceAnimId;   // 0x06 - facing toward edge, safe distance
    private final int balance2AnimId;  // 0x0C - facing toward edge, closer to falling
    private final int balance3AnimId;  // 0x1D - facing away from edge, safe distance
    private final int balance4AnimId;  // 0x1E - facing away from edge, closer to falling
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
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, duckAnimId, -1,
                spindashAnimId, springAnimId, deathAnimId, hurtAnimId, skidAnimId, airAnimId,
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
            int lookUpAnimId,
            int spindashAnimId,
            int springAnimId,
            int deathAnimId,
            int hurtAnimId,
            int skidAnimId,
            int airAnimId,
            int walkSpeedThreshold,
            int runSpeedThreshold,
            int fallbackFrame) {
        this(idleAnimId, walkAnimId, runAnimId, rollAnimId, roll2AnimId, pushAnimId, duckAnimId, lookUpAnimId,
                spindashAnimId, springAnimId, deathAnimId, hurtAnimId, skidAnimId, airAnimId,
                -1, -1, -1, -1,  // balance animations disabled by default
                walkSpeedThreshold, runSpeedThreshold, fallbackFrame);
    }

    /**
     * Full constructor with balance animation support.
     * Balance animations are ROM-accurate (s2.asm:36246-36373).
     */
    public ScriptedVelocityAnimationProfile(
            int idleAnimId,
            int walkAnimId,
            int runAnimId,
            int rollAnimId,
            int roll2AnimId,
            int pushAnimId,
            int duckAnimId,
            int lookUpAnimId,
            int spindashAnimId,
            int springAnimId,
            int deathAnimId,
            int hurtAnimId,
            int skidAnimId,
            int airAnimId,
            int balanceAnimId,
            int balance2AnimId,
            int balance3AnimId,
            int balance4AnimId,
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
        this.lookUpAnimId = Math.max(-1, lookUpAnimId);
        this.spindashAnimId = Math.max(-1, spindashAnimId);
        this.springAnimId = Math.max(-1, springAnimId);
        this.deathAnimId = Math.max(-1, deathAnimId);
        this.hurtAnimId = Math.max(-1, hurtAnimId);
        this.skidAnimId = Math.max(-1, skidAnimId);
        this.airAnimId = Math.max(0, airAnimId);
        this.balanceAnimId = Math.max(-1, balanceAnimId);
        this.balance2AnimId = Math.max(-1, balance2AnimId);
        this.balance3AnimId = Math.max(-1, balance3AnimId);
        this.balance4AnimId = Math.max(-1, balance4AnimId);
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
        if (sprite.getLookingUp() && lookUpAnimId >= 0) {
            return lookUpAnimId;
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

        // ROM-accurate animation selection (s2.asm:36558, 36619, 36242-36245):
        // - Sonic_MoveLeft/MoveRight set anim = Walk unconditionally when direction pressed
        // - Idle animation only set when inertia == 0 AND no direction pressed
        // This means: walk plays when pressing direction OR when still moving (coasting)
        // Use isMovementInputActive() which reflects EFFECTIVE input (after control lock filtering),
        // not raw button state, to match ROM behavior where animation is only set in movement routines.
        boolean pressingDirection = sprite.isMovementInputActive();
        int speed = Math.abs(sprite.getGSpeed());

        // Run animation at high speeds (ROM: cmpi.w #$600,d2)
        if (speed >= runSpeedThreshold) {
            return runAnimId;
        }

        // Walk animation when pressing direction OR when still moving (inertia != 0)
        if (pressingDirection || speed > 0) {
            return walkAnimId;
        }

        // Balance animation when standing at edge of ledge/platform (ROM s2.asm:36246-36373)
        // Balance state is set by movement code based on terrain/object edge detection
        int balanceState = sprite.getBalanceState();
        if (balanceState > 0 && balanceAnimId >= 0) {
            return switch (balanceState) {
                case 1 -> balanceAnimId;      // Balance - facing edge, safe distance
                case 2 -> balance2AnimId >= 0 ? balance2AnimId : balanceAnimId;  // Balance2 - facing edge, closer
                case 3 -> balance3AnimId >= 0 ? balance3AnimId : balanceAnimId;  // Balance3 - facing away, safe
                case 4 -> balance4AnimId >= 0 ? balance4AnimId : (balance3AnimId >= 0 ? balance3AnimId : balanceAnimId); // Balance4 - facing away, closer
                default -> balanceAnimId;
            };
        }

        // Idle only when not pressing direction AND completely stopped (inertia == 0)
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

    public int getLookUpAnimId() {
        return lookUpAnimId;
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

    public int getBalanceAnimId() {
        return balanceAnimId;
    }

    public int getBalance2AnimId() {
        return balance2AnimId;
    }

    public int getBalance3AnimId() {
        return balance3AnimId;
    }

    public int getBalance4AnimId() {
        return balance4AnimId;
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
