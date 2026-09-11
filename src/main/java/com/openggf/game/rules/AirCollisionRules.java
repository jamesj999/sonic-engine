package com.openggf.game.rules;

/** Per-game collision branches used only while a playable sprite is airborne. */
public record AirCollisionRules(
        boolean bottomSolidHitClearsGroundSpeed,
        boolean bottomSolidHitAlwaysSeparates,
        boolean rightWallHitContinuesIntoCeilingSeparation,
        boolean leftWallHitContinuesIntoCeilingSeparation,
        boolean probesResetStaleGroundMode) {
}
