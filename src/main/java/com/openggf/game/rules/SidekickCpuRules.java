package com.openggf.game.rules;

public record SidekickCpuRules(
        int sidekickFollowSnapThreshold,
        int sidekickDespawnX,
        int sidekickFollowLeadOffset,
        boolean sidekickFollowNudgeBlockedByObjectControlBit0,
        boolean sidekickPanicTreatsPinballModeAsSpindashFlag,
        boolean sidekickSpawningRequiresGroundedLeader,
        int sidekickFlyLandStatusBlockerMask,
        boolean sidekickFlyLandRequiresLeaderAlive,
        int sidekickCatchUpYOffset,
        int sidekickFlightAutoLandFrames,
        int sidekickFlightMaxXStep,
        int sidekickFlightYStep,
        int sidekickFlightLeadXOffset,
        int sidekickFlightLeadSuppressGSpeed,
        boolean sidekickFlightClampsTargetYToWater,
        boolean sidekickRespawnEntersCatchUpFlight,
        boolean sidekickCpuUsesLevelFrameCounter,
        boolean sidekickDeathUsesDeferredDespawn,
        boolean sidekickHurtRestoresRadiiWithoutRoll) {
}
