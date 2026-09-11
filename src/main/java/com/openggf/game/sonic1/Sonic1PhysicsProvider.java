package com.openggf.game.sonic1;

import com.openggf.game.PhysicsModifiers;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.rules.GameRules;

/**
 * Physics provider for Sonic 1.
 * All characters use the same profile (only Sonic is playable).
 * Spindash is absent.
 */
public class Sonic1PhysicsProvider implements PhysicsProvider {

    @Override
    public PhysicsProfile getProfile(String characterType) {
        // Sonic 1 only has Sonic; values are identical to S2 Sonic.
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    public PhysicsModifiers getModifiers() {
        return PhysicsModifiers.STANDARD;
    }

    @Override
    public GameRules getRules() {
        return GameRules.SONIC_1;
    }
}
