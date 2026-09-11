package com.openggf.game;

import com.openggf.physics.Direction;

/**
 * Abstraction over the playable sprite that game objects interact with.
 * <p>
 * This interface decouples {@code level.objects} from the concrete
 * {@code sprites.playable.AbstractPlayableSprite} hierarchy, breaking
 * the circular dependency between the two packages. All methods mirror
 * the existing public API on {@code AbstractPlayableSprite} (or its
 * parent {@code AbstractSprite}).
 */
public interface PlayableEntity {

    // ── Position ────────────────────────────────────────────────────
    short getCentreX();
    short getCentreY();
    void setCentreX(short x);
    void setCentreYPreserveSubpixel(short y);
    short getX();
    short getY();
    void setY(short y);
    void shiftX(int delta);
    void move(short xSpeed, short ySpeed);

    /** No-arg move using internal xSpeed/ySpeed. */
    void move();

    /** Centre X from N frames ago (position history ring buffer). */
    short getCentreX(int framesBehind);

    /** Centre Y from N frames ago (position history ring buffer). */
    short getCentreY(int framesBehind);

    // ── Dimensions ──────────────────────────────────────────────────
    int getHeight();
    short getYRadius();
    short getXRadius();
    short getRollHeightAdjustment();

    // ── Physics ─────────────────────────────────────────────────────
    short getXSpeed();
    short getYSpeed();
    void setXSpeed(short xSpeed);
    void setYSpeed(short ySpeed);
    short getGSpeed();
    void setGSpeed(short gSpeed);
    boolean getAir();
    void setAir(boolean air);
    byte getAngle();
    void setAngle(byte angle);

    // ── Ground mode ─────────────────────────────────────────────────
    boolean getRolling();
    void setRolling(boolean rolling);
    boolean getSpindash();
    GroundMode getGroundMode();
    void setGroundMode(GroundMode groundMode);

    // ── Object interaction ──────────────────────────────────────────
    boolean isObjectControlled();
    boolean isOnObject();
    void setOnObject(boolean onObject);
    void setPushing(boolean pushing);
    boolean getPinballMode();
    boolean isCpuControlled();
    int getAnimationId();

    /**
     * ROM {@code mapping_frame(a0)} - the sprite frame the animation script last published.
     * The shipped TouchResponse/Touch_Rings ducking test compares this byte, not the
     * animation id; see {@code ObjectInteractionRules#duckTouchBoxMappingFrame}.
     */
    int getMappingFrame();

    /**
     * Returns whether this player's {@code object_control} byte should
     * suppress the per-frame touch-response collision pass.
     * <p>
     * ROM gates (all three games) skip {@code jsr (TouchResponse).l} when the
     * sign bit ({@code bit 7}, {@code $80}) of {@code object_control} is set,
     * via {@code tst.b obj_control(a0); bmi.s +} (S1/S2) or via the
     * {@code andi.b #$A0,d0; bne.s +} test (S3K — also catches bit 5):
     * <ul>
     *   <li>S1: {@code _incObj/01 Sonic.asm:88-89}
     *       ({@code tst.b f_playerctrl; bmi.s .ignoreobjcoll}).</li>
     *   <li>S2: {@code s2.asm:35962-35964}
     *       ({@code tst.b obj_control(a0); bmi.s +; jsr (TouchResponse).l}).</li>
     *   <li>S3K: {@code sonic3k.asm:22019-22021} (Sonic_Display) and
     *       {@code sonic3k.asm:26263-26266} (Tails_Display) — both use
     *       {@code andi.b #$A0,d0; bne.s ...}.</li>
     * </ul>
     * In practice ROM only ever sets the sign bit (values {@code $81}/{@code $83}
     * for flight/CATCH_UP_FLIGHT/FLIGHT_AUTO_RECOVERY/super/debug); the engine
     * mirrors this via {@code isObjectControlled() && !isObjectControlAllowsCpu()}
     * in {@link com.openggf.sprites.playable.AbstractPlayableSprite}.
     * <p>
     * Default {@code false} for non-sprite implementations.
     */
    default boolean isTouchResponseSuppressedByObjectControl() {
        return false;
    }

    /**
     * Forces the animation system to restart the current animation script.
     * ROM equivalent: clearing prev_anim to trigger anim != prev_anim on next frame.
     */
    void forceAnimationRestart();

    // ── Collision path ──────────────────────────────────────────────
    void setTopSolidBit(byte topSolidBit);
    void setLrbSolidBit(byte lrbSolidBit);
    void setLayer(byte layer);
    boolean isHighPriority();
    void setHighPriority(boolean highPriority);

    // ── Vulnerability ───────────────────────────────────────────────
    boolean getDead();
    boolean isDebugMode();
    boolean getInvulnerable();
    boolean hasShield();
    ShieldType getShieldType();
    int getInvincibleFrames();
    int getDoubleJumpFlag();
    default void applyPostObjectLandingAbilities(int savedDoubleJumpFlag) {
    }
    boolean isSuperSonic();

    // ── Damage ──────────────────────────────────────────────────────
    boolean getCrouching();
    int getRingCount();
    Direction getDirection();
    boolean applyHurt(int sourceX);
    boolean applyHurt(int sourceX, boolean spikeHit);
    boolean applyHurt(int sourceX, DamageCause cause);
    /**
     * Applies hurt while ignoring the post-hit invulnerability (flashing) timer;
     * debug invulnerability and the invincibility power-up still block it. Used by
     * the hazards whose retail code omits the {@code invulnerability_timer} test
     * (S1 spikes, S3K ICZ harmful ice).
     */
    boolean applyHurtIgnoringIFrames(int sourceX, DamageCause cause);
    boolean applyHurtOrDeath(int sourceX, boolean spikeHit, boolean hadRings);
    boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings);
    boolean applyCrushDeath();

    // ── Scoring ─────────────────────────────────────────────────────
    int incrementBadnikChain();

    // ── Per-game rules ──────────────────────────────────────────────
    com.openggf.game.rules.GameRules getGameRules();
}
