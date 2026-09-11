package com.openggf.game.sonic3k.constants;

import com.openggf.game.AnimationId;
import com.openggf.game.CanonicalAnimation;

/**
 * Animation script IDs for Sonic/Tails/Knuckles in S3K (indices into AniSonic_ table).
 *
 * <p>IDs 0x00-0x1E largely follow the S2 table layout, but several entries
 * were repurposed (0x13 = Victory, 0x1A/0x1B = hurt variants, 0x19 = drown).
 * S3K adds 0x1F (Super transformation), Tails' flight/swim scripts at
 * 0x20-0x28, and Knuckles glide states that alias 0x21-0x23. Sonic's MGZ2
 * carried pose also writes native animation 0x22.
 */
public enum Sonic3kAnimationIds implements AnimationId {
    WALK(0x00),
    RUN(0x01),
    ROLL(0x02),
    ROLL2(0x03),
    PUSH(0x04),
    WAIT(0x05),
    BALANCE(0x06),
    LOOK_UP(0x07),
    DUCK(0x08),
    SPINDASH(0x09),
    BLINK(0x0A),         // Idle blink/tapping foot interrupt (sonic3k.asm:21613)
    GET_UP(0x0B),        // Get up from idle blink sequence (sonic3k.asm:21616)
    BALANCE2(0x0C),      // Balancing on edge, more precarious
    SKID(0x0D),
    FLOAT(0x0E),         // Suspended/floating (single frame $C8)
    FLOAT2(0x0F),        // Extended float animation sequence
    SPRING(0x10),
    HANG(0x11),
    VICTORY(0x13),       // Victory/celebration pose (Set_PlayerEndingPose, sonic3k.asm:181979)
    HANG2(0x14),         // Hanging from object
    BUBBLE(0x15),        // Breathing air bubble underwater (sonic3k.asm:64707)
    DROWN(0x17),         // Drowning death (s3.asm:27706, sonic3k.asm:33553)
    DEATH(0x18),         // Death (Kill_Character, sonic3k.asm:21152)
    HURT(0x1A),          // Hurt recoil (Player_Hurt, sonic3k.asm:21109)
    HURT_FALL(0x1B),     // Hurt/fall in intros (sonic3k.asm:8135, 9089)
    BLANK(0x1C),         // Blank/invisible animation (sonic3k.asm:67021)
    BALANCE3(0x1D),      // Balancing on edge, facing away
    BALANCE4(0x1E),      // Balancing on edge, facing away, more precarious
    SUPER_TRANSFORM(0x1F), // Super transformation (s3.asm:21148)
    FLY(0x20),           // Legacy CPU recovery flight meaning
    TAILS_FLY(0x20),
    TAILS_FLY_ASCEND(0x21),
    TAILS_FLY_CARRY(0x22),
    TAILS_FLY_CARRY_ASCEND(0x23),
    TAILS_FLY_TIRED(0x24),
    TAILS_SWIM(0x25),
    TAILS_SWIM_ASCEND(0x26),
    TAILS_SWIM_CARRY(0x27),
    TAILS_SWIM_TIRED(0x28),
    GLIDE_DROP(0x21),    // Knuckles falling after glide (sonic3k.asm:20930)
    TAILS_CARRIED(0x22), // Sonic carried by Tails during MGZ2 boss transition (sonic3k.asm:27387)
    GLIDE_LAND(0x22),    // Knuckles glide landing (sonic3k.asm:30987)
    GLIDE_SLIDE(0x23);   // Knuckles glide slide on ground (sonic3k.asm:30940)

    private final int id;

    Sonic3kAnimationIds(int id) {
        this.id = id;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * Maps this S3K animation to its canonical cross-game equivalent.
     * SUPER_TRANSFORM maps to {@link CanonicalAnimation#SUPER_TRANSFORM}.
     */
    public CanonicalAnimation toCanonical() {
        return switch (this) {
            case WALK            -> CanonicalAnimation.WALK;
            case RUN             -> CanonicalAnimation.RUN;
            case ROLL            -> CanonicalAnimation.ROLL;
            case ROLL2           -> CanonicalAnimation.ROLL2;
            case PUSH            -> CanonicalAnimation.PUSH;
            case WAIT            -> CanonicalAnimation.WAIT;
            case BALANCE         -> CanonicalAnimation.BALANCE;
            case LOOK_UP         -> CanonicalAnimation.LOOK_UP;
            case DUCK            -> CanonicalAnimation.DUCK;
            case SPINDASH        -> CanonicalAnimation.SPINDASH;
            case BLINK           -> CanonicalAnimation.BLINK;
            case GET_UP          -> CanonicalAnimation.GET_UP;
            case BALANCE2        -> CanonicalAnimation.BALANCE2;
            case SKID            -> CanonicalAnimation.SKID;
            case FLOAT           -> CanonicalAnimation.FLOAT;
            case FLOAT2          -> CanonicalAnimation.FLOAT2;
            case SPRING          -> CanonicalAnimation.SPRING;
            case HANG            -> CanonicalAnimation.HANG;
            case VICTORY         -> CanonicalAnimation.VICTORY;
            case HANG2           -> CanonicalAnimation.HANG2;
            case BUBBLE          -> CanonicalAnimation.BUBBLE;
            case DROWN           -> CanonicalAnimation.DROWN;
            case DEATH           -> CanonicalAnimation.DEATH;
            case HURT            -> CanonicalAnimation.HURT;
            case HURT_FALL       -> CanonicalAnimation.HURT_FALL;
            case BLANK           -> CanonicalAnimation.BLANK;
            case BALANCE3        -> CanonicalAnimation.BALANCE3;
            case BALANCE4        -> CanonicalAnimation.BALANCE4;
            case SUPER_TRANSFORM -> CanonicalAnimation.SUPER_TRANSFORM;
            case FLY             -> CanonicalAnimation.FLY;
            case TAILS_FLY       -> CanonicalAnimation.TAILS_FLY;
            case TAILS_FLY_ASCEND -> CanonicalAnimation.TAILS_FLY_ASCEND;
            case TAILS_FLY_CARRY -> CanonicalAnimation.TAILS_FLY_CARRY;
            case TAILS_FLY_CARRY_ASCEND -> CanonicalAnimation.TAILS_FLY_CARRY_ASCEND;
            case TAILS_FLY_TIRED -> CanonicalAnimation.TAILS_FLY_TIRED;
            case TAILS_SWIM      -> CanonicalAnimation.TAILS_SWIM;
            case TAILS_SWIM_ASCEND -> CanonicalAnimation.TAILS_SWIM_ASCEND;
            case TAILS_SWIM_CARRY -> CanonicalAnimation.TAILS_SWIM_CARRY;
            case TAILS_SWIM_TIRED -> CanonicalAnimation.TAILS_SWIM_TIRED;
            case GLIDE_DROP      -> CanonicalAnimation.GLIDE_DROP;
            case TAILS_CARRIED   -> CanonicalAnimation.TAILS_CARRIED;
            case GLIDE_LAND      -> CanonicalAnimation.GLIDE_LAND;
            case GLIDE_SLIDE     -> CanonicalAnimation.GLIDE_SLIDE;
        };
    }

    /**
     * Returns the S3K animation ID for the given canonical animation,
     * or -1 if S3K does not have an equivalent for that animation.
     */
    public static int fromCanonical(CanonicalAnimation canonical) {
        return AnimationId.fromCanonical(values(), Sonic3kAnimationIds::toCanonical, canonical);
    }
}
