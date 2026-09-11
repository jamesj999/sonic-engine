package com.openggf.game;

/**
 * Lightweight handle for power-up visual objects (shields, invincibility stars).
 * <p>
 * Lives in the {@code game} package so that {@code sprites.playable} can reference
 * power-up objects without importing concrete types from {@code level.objects}.
 * Implementations live in {@code level.objects} and {@code game.sonic3k.objects}.
 */
public interface PowerUpObject {

    /** Marks this object as destroyed (removes it from rendering/updates). */
    void destroy();

    /** Returns {@code true} if this object has been destroyed. */
    boolean isDestroyed();

    /** Shows or hides the visual representation without destroying the object. */
    void setVisible(boolean visible);

    /**
     * Returns whether this visual object represents the restored shield type.
     * Basic shields use the shared default implementation; elemental shields
     * override this with their concrete type.
     */
    default boolean matchesShieldType(ShieldType type) {
        return type == ShieldType.BASIC;
    }

    /**
     * Returns whether this power-up is the shield visual owned by the given
     * player. Non-shield power-ups must leave the default false so rewind
     * relinking cannot confuse invincibility stars for a basic shield.
     */
    default boolean isShieldFor(PlayableEntity player, ShieldType type) {
        return false;
    }

    /**
     * Returns whether this power-up is an invincibility-stars visual.
     * The rewind player-bound restore path uses this shared marker so game-specific
     * stars can consume the captured pending entry without shared code importing
     * concrete game packages.
     */
    default boolean isInvincibilityStars() {
        return false;
    }

    /**
     * Semantic player owner for player-bound power-up SSTs.
     * Used to preserve per-player rewind identity when two equivalent visuals
     * are recreated in a different order.
     */
    default PlayableEntity boundPlayer() {
        return null;
    }

    /**
     * Called after a rewind restore relinks or respawns this power-up visual.
     * Implementations with transient renderer/DPLC caches should invalidate
     * them so the next draw uploads art for the restored animation frame.
     */
    default void refreshArtAfterRewindRestore() {
        // Default no-op for power-ups without transient art caches.
    }

    /**
     * Notifies this power-up that the player activated its secondary ability.
     * <p>
     * Elemental shields override this to trigger their attack animation/effect:
     * <ul>
     *   <li>Fire shield: dash animation (actionId=1)</li>
     *   <li>Lightning shield: spark particles (actionId=1)</li>
     *   <li>Bubble shield: bounce animation (actionId=1 for slam, actionId=2 for ground bounce)</li>
     * </ul>
     * <p>
     * Non-elemental power-ups (standard shield, invincibility) ignore this call.
     *
     * @param actionId the ability action identifier (1=primary, 2=secondary/variant)
     */
    default void onAbilityActivated(int actionId) {
        // Default no-op for non-elemental power-ups
    }
}
