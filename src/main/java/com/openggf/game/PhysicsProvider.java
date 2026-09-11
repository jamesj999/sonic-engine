package com.openggf.game;

import com.openggf.game.rules.GameRules;

/**
 * Per-game physics provider interface.
 * Returns character-specific physics profiles, modifier rules, and typed per-game rules.
 *
 * <p>Implementations are game-specific (S1, S2, S3K) and accessed via
 * {@link GameModule#getPhysicsProvider()}.
 */
public interface PhysicsProvider {

    /**
     * Returns the physics profile for the given character type.
     *
     * @param characterType identifier such as "sonic" or "tails"
     * @return the physics profile for the character
     */
    PhysicsProfile getProfile(String characterType);

    /**
     * Returns the init-time physics profile for the given character type.
     *
     * <p>S3K loads per-character values from the {@code Character_Speeds} table
     * (sonic3k.asm:202288) at level init and respawn. These differ from the
     * canonical profile and persist until the first water or speed shoes event.
     *
     * <p>Returns {@code null} for S1/S2 where init values equal the canonical profile.
     *
     * @param characterType identifier such as "sonic" or "tails"
     * @return the init-time profile, or null if init uses the canonical profile
     */
    default PhysicsProfile getInitProfile(String characterType) {
        return null;
    }

    /**
     * Returns the physics modifiers (water/speed shoes rules) for this game.
     *
     * @return the physics modifiers
     */
    PhysicsModifiers getModifiers();

    GameRules getRules();
}
