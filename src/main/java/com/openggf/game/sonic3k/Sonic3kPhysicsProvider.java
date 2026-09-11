package com.openggf.game.sonic3k;

import com.openggf.game.PhysicsModifiers;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.rules.GameRules;

/**
 * Physics provider for Sonic 3 &amp; Knuckles.
 * Returns character-specific profiles; spindash is enabled.
 *
 * <p>Normal single-player mode uses the same base constants as S2 ($600/$C/$80).
 * The {@code Character_Speeds} table (sonic3k.asm:202288) is only used in
 * Competition mode ({@code Sonic2P_Index}, line 21457); it is NOT loaded
 * during normal single-player init.
 */
public class Sonic3kPhysicsProvider implements PhysicsProvider {

    // Cache character type for modifier resolution
    private String lastCharacterType = "sonic";

    @Override
    public PhysicsProfile getProfile(String characterType) {
        lastCharacterType = characterType;
        if ("tails".equalsIgnoreCase(characterType)) {
            return PhysicsProfile.SONIC_2_TAILS;
        }
        if ("knuckles".equalsIgnoreCase(characterType)) {
            // ROM: Knux_Jump (sonic3k.asm:32454) move.w #$600,d2 — lower jump than Sonic
            return PhysicsProfile.SONIC_3K_KNUCKLES;
        }
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    public PhysicsModifiers getModifiers() {
        if ("knuckles".equalsIgnoreCase(lastCharacterType)) {
            return PhysicsModifiers.KNUCKLES;
        }
        return PhysicsModifiers.STANDARD;
    }

    @Override
    public GameRules getRules() {
        return GameRules.SONIC_3K;
    }
}
