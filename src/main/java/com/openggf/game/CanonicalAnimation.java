package com.openggf.game;

/**
 * Cross-game animation vocabulary covering the union of all player animations
 * across Sonic 1, Sonic 2, and Sonic 3&K.
 *
 * <p>Each entry represents a logical animation state that exists in at least one
 * game in the mainline Sonic series. Game-specific animation ID tables map their
 * integer animation indices to these canonical names, enabling bidirectional
 * animation donation between games.</p>
 *
 * <p>Groupings:</p>
 * <ul>
 *   <li><b>Core movement</b> - shared across all three games</li>
 *   <li><b>Combat/state</b> - hurt, death, and drowning states</li>
 *   <li><b>S1-specific</b> - animations unique to Sonic 1 (warp, surf, etc.)</li>
 *   <li><b>Shared</b> - float and hang states present in multiple games</li>
 *   <li><b>S2+</b> - animations introduced in Sonic 2 (spindash, skid, fly, etc.)</li>
 *   <li><b>S3K</b> - animations introduced in Sonic 3&K (glide, blink, victory, etc.)</li>
 *   <li><b>Super</b> - Super Sonic transformation</li>
 * </ul>
 */
public enum CanonicalAnimation {

    // -------------------------------------------------------------------------
    // Core movement (shared across all games)
    // -------------------------------------------------------------------------

    /** Normal walking animation. */
    WALK,

    /** Running at high speed. */
    RUN,

    /** Rolling / spindash roll. */
    ROLL,

    /** Secondary roll (e.g. S3K underwater roll). */
    ROLL2,

    /** Pushing against a wall. */
    PUSH,

    /** Idle stand animation. */
    WAIT,

    /** Ducking / crouching. */
    DUCK,

    /** Looking upward. */
    LOOK_UP,

    /** Bouncing on a spring. */
    SPRING,

    /** Balancing on a ledge edge (state 1). */
    BALANCE,

    /** Balancing on a ledge edge (state 2). */
    BALANCE2,

    /** Balancing on a ledge edge (state 3). */
    BALANCE3,

    /** Balancing on a ledge edge (state 4). */
    BALANCE4,

    // -------------------------------------------------------------------------
    // Combat / state
    // -------------------------------------------------------------------------

    /** Hurt/invincibility flash. */
    HURT,

    /** Death animation. */
    DEATH,

    /** Drowning countdown animation. */
    DROWN,

    // -------------------------------------------------------------------------
    // S1-specific animations
    // -------------------------------------------------------------------------

    /** Stopping (S1 brake). */
    STOP,

    /** Star-post warp animation frame 1. */
    WARP1,

    /** Star-post warp animation frame 2. */
    WARP2,

    /** Star-post warp animation frame 3. */
    WARP3,

    /** Star-post warp animation frame 4. */
    WARP4,

    /** Float animation variant 3 (S1). */
    FLOAT3,

    /** Float animation variant 4 (S1). */
    FLOAT4,

    /** Leap animation frame 1 (S1 spring). */
    LEAP1,

    /** Leap animation frame 2 (S1 spring). */
    LEAP2,

    /** Surfing on water (S1). */
    SURF,

    /** Getting air (S1 underwater). */
    GET_AIR,

    /** Burnt by fire (S1). */
    BURNT,

    /** Shrinking (S1 special stage). */
    SHRINK,

    /** Water slide (S1). */
    WATER_SLIDE,

    /** Null / placeholder animation (S1). */
    NULL_ANIM,

    // -------------------------------------------------------------------------
    // Shared (present in multiple games)
    // -------------------------------------------------------------------------

    /** Airborne float animation (variant 1). */
    FLOAT,

    /** Airborne float animation (variant 2). */
    FLOAT2,

    /** Hanging from a bar or ledge. */
    HANG,

    // -------------------------------------------------------------------------
    // S2+ animations (introduced in Sonic 2)
    // -------------------------------------------------------------------------

    /** Charging / releasing a spindash. */
    SPINDASH,

    /** Skidding to a stop. */
    SKID,

    /** Sliding under a low ceiling. */
    SLIDE,

    /** Secondary hang animation (S2+). */
    HANG2,

    /** Bubble (underwater air supply). */
    BUBBLE,

    /** Secondary hurt animation (S2+). */
    HURT2,

    /** Tails flying animation. */
    FLY,

    /** Tails flying under player control. */
    TAILS_FLY,

    /** Tails ascending during flight. */
    TAILS_FLY_ASCEND,

    /** Tails flying while carrying another player. */
    TAILS_FLY_CARRY,

    /** Tails ascending while carrying another player. */
    TAILS_FLY_CARRY_ASCEND,

    /** Tails' tired flight animation. */
    TAILS_FLY_TIRED,

    /** Tails swimming underwater. */
    TAILS_SWIM,

    /** Tails ascending while swimming. */
    TAILS_SWIM_ASCEND,

    /** Tails swimming while carrying another player. */
    TAILS_SWIM_CARRY,

    /** Tails' tired swimming animation. */
    TAILS_SWIM_TIRED,

    // -------------------------------------------------------------------------
    // S3K animations (introduced in Sonic 3&K)
    // -------------------------------------------------------------------------

    /** Eye blink idle animation (S3K). */
    BLINK,

    /** Getting up after a fall (S3K). */
    GET_UP,

    /** Victory pose (S3K). */
    VICTORY,

    /** Blank / empty frame (S3K). */
    BLANK,

    /** Falling hurt animation (S3K). */
    HURT_FALL,

    /** Glide-to-drop transition (Knuckles, S3K). */
    GLIDE_DROP,

    /** Glide landing (Knuckles, S3K). */
    GLIDE_LAND,

    /** Glide wall-slide (Knuckles, S3K). */
    GLIDE_SLIDE,

    /** S3K: Sonic's animation while being carried by Tails.
     *  ROM reference: sub_1459E writes anim high-byte 0x22 on the carried Sonic.
     *  SpriteAnimationProfile lookup may not define a mapping yet — callers
     *  must tolerate a -1 return from SpriteAnimationSet.getAnimIdFor(...).
     */
    TAILS_CARRIED,

    // -------------------------------------------------------------------------
    // Super Sonic
    // -------------------------------------------------------------------------

    /** Super Sonic transformation sequence. */
    SUPER_TRANSFORM
}
