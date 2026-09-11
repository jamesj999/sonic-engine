package com.openggf.game.sonic2;

import com.openggf.game.PhysicsModifiers;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.rules.GameRules;

/**
 * Physics provider for Sonic 2.
 * Returns Sonic or Tails profile based on character type.
 * Spindash is enabled with standard speed table.
 */
public class Sonic2PhysicsProvider implements PhysicsProvider {

    @Override
    public PhysicsProfile getProfile(String characterType) {
        if ("tails".equalsIgnoreCase(characterType)) {
            return PhysicsProfile.SONIC_2_TAILS;
        }
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    public PhysicsModifiers getModifiers() {
        return PhysicsModifiers.STANDARD;
    }

    @Override
    public GameRules getRules() {
        return GameRules.SONIC_2;
    }
}
