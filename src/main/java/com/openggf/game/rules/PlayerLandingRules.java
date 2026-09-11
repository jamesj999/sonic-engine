package com.openggf.game.rules;

public record PlayerLandingRules(
        boolean pinballLandingPreservesRoll,
        boolean pinballLandingPreservesPinballMode,
        boolean landingRollClearUsesCurrentYRadiusDelta,
        boolean objectSolidHurtLandingRetainsRoutine) {
}
