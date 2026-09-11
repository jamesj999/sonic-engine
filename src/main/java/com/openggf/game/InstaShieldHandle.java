package com.openggf.game;

/**
 * Extended handle for the S3K insta-shield object.
 * <p>
 * In addition to the basic {@link PowerUpObject} lifecycle, the insta-shield
 * has an attack trigger, DPLC cache management (needed after seamless level
 * transitions), and a per-frame update callback.
 */
public interface InstaShieldHandle extends PowerUpObject {

    /** Triggers the insta-shield attack animation. */
    void triggerAttack();

    /**
     * Invalidates the DPLC tile cache, forcing re-upload on the next draw.
     * Must be called after seamless level transitions that reload the pattern buffer.
     */
    void invalidateDplcCache();

    /**
     * Per-frame update for animation stepping and double-jump flag transitions.
     *
     * @param vIntRunCount object-visible V-int run count
     * @param player       the owning player entity
     */
    void update(int vIntRunCount, PlayableEntity player);
}
