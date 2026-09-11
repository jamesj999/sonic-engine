package com.openggf.game.sonic2.constants;

import com.openggf.game.AnimationId;
import com.openggf.game.CanonicalAnimation;

public enum Sonic2AnimationIds implements AnimationId {
    WALK(0x00),
    RUN(0x01),
    ROLL(0x02),
    ROLL2(0x03),
    PUSH(0x04),
    WAIT(0x05),
    BALANCE(0x06),       // Balancing on edge, facing toward edge
    LOOK_UP(0x07),
    DUCK(0x08),
    SPINDASH(0x09),
    BLINK(0x0A),         // Impatient-wait interrupt: blink before resuming control
    GET_UP(0x0B),        // Impatient-wait interrupt: stand up from lying down
    BALANCE2(0x0C),      // Balancing on edge, more precarious (closer to falling)
    SKID(0x0D),          // Braking/halt animation
    FLOAT(0x0E),         // Suspended/floating (used by Grabber)
    FLOAT2(0x0F),        // Alternate float
    SPRING(0x10),
    HANG(0x11),          // Hanging from horizontal bar
    HANG2(0x14),         // Alternate hang
    BUBBLE(0x15),        // Breathing air bubble underwater
    DROWN(0x17),         // Drowning animation (pre-death sink)
    DEATH(0x18),
    HURT(0x19),
    HURT2(0x1A),         // Tails: frame $5C (distinct from death frame $5D)
    SLIDE(0x1B),         // Oil slide in OOZ
    BALANCE3(0x1D),      // Balancing on edge, facing away from edge
    BALANCE4(0x1E),      // Balancing on edge, facing away, more precarious
    FLY(0x20),           // Tails helicopter fly (Tails only)

    // Super Sonic animation IDs (indices into SuperSonicAniData table, s2.asm:38415).
    // Values intentionally duplicate normal IDs — these index a SEPARATE animation table.
    SUPER_WALK(0x00),
    SUPER_RUN(0x01),
    SUPER_ROLL(0x02),
    SUPER_ROLL2(0x03),
    SUPER_PUSH(0x04),
    SUPER_STAND(0x05),
    SUPER_BALANCE(0x06),
    SUPER_LOOK_UP(0x07),
    SUPER_DUCK(0x08),
    SUPER_SPINDASH(0x09),
    SUPER_TRANSFORM(0x1F); // AniIDSupSonAni_Transform (index 31 in normal table)

    private final int id;

    Sonic2AnimationIds(int id) {
        this.id = id;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * Maps this S2 animation to its canonical cross-game equivalent.
     *
     * <p>Super Sonic table variants (SUPER_WALK, SUPER_RUN, SUPER_ROLL, SUPER_ROLL2,
     * SUPER_PUSH, SUPER_STAND, SUPER_BALANCE, SUPER_LOOK_UP, SUPER_DUCK, SUPER_SPINDASH)
     * index a separate animation table and have no canonical mapping — returns null.
     * SUPER_TRANSFORM uses the normal table at index 0x1F and maps to
     * {@link CanonicalAnimation#SUPER_TRANSFORM}.</p>
     */
    public CanonicalAnimation toCanonical() {
        return switch (this) {
            case WALK           -> CanonicalAnimation.WALK;
            case RUN            -> CanonicalAnimation.RUN;
            case ROLL           -> CanonicalAnimation.ROLL;
            case ROLL2          -> CanonicalAnimation.ROLL2;
            case PUSH           -> CanonicalAnimation.PUSH;
            case WAIT           -> CanonicalAnimation.WAIT;
            case BALANCE        -> CanonicalAnimation.BALANCE;
            case LOOK_UP        -> CanonicalAnimation.LOOK_UP;
            case DUCK           -> CanonicalAnimation.DUCK;
            case SPINDASH       -> CanonicalAnimation.SPINDASH;
            case BLINK          -> CanonicalAnimation.BLINK;
            case GET_UP         -> CanonicalAnimation.GET_UP;
            case BALANCE2       -> CanonicalAnimation.BALANCE2;
            case SKID           -> CanonicalAnimation.SKID;
            case FLOAT          -> CanonicalAnimation.FLOAT;
            case FLOAT2         -> CanonicalAnimation.FLOAT2;
            case SPRING         -> CanonicalAnimation.SPRING;
            case HANG           -> CanonicalAnimation.HANG;
            case HANG2          -> CanonicalAnimation.HANG2;
            case BUBBLE         -> CanonicalAnimation.BUBBLE;
            case DROWN          -> CanonicalAnimation.DROWN;
            case DEATH          -> CanonicalAnimation.DEATH;
            // Hurt_Sidekick writes $1A for both characters. Keep $19
            // addressable through HURT2 for scripts that explicitly need the
            // other table entry (s2.asm:85497-85519).
            case HURT           -> CanonicalAnimation.HURT2;
            case HURT2          -> CanonicalAnimation.HURT;
            case SLIDE          -> CanonicalAnimation.SLIDE;
            case BALANCE3       -> CanonicalAnimation.BALANCE3;
            case BALANCE4       -> CanonicalAnimation.BALANCE4;
            case FLY            -> CanonicalAnimation.FLY;
            case SUPER_TRANSFORM -> CanonicalAnimation.SUPER_TRANSFORM;
            // Super table variants — no canonical mapping
            case SUPER_WALK,
                 SUPER_RUN,
                 SUPER_ROLL,
                 SUPER_ROLL2,
                 SUPER_PUSH,
                 SUPER_STAND,
                 SUPER_BALANCE,
                 SUPER_LOOK_UP,
                 SUPER_DUCK,
                 SUPER_SPINDASH -> null;
        };
    }

    /**
     * Returns the S2 animation ID for the given canonical animation,
     * or -1 if S2 does not have an equivalent for that animation.
     * Skips entries where {@link #toCanonical()} returns null (super table variants).
     */
    public static int fromCanonical(CanonicalAnimation canonical) {
        return AnimationId.fromCanonical(values(), Sonic2AnimationIds::toCanonical, canonical);
    }
}
