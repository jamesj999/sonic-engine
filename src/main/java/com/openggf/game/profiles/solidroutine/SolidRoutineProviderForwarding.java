package com.openggf.game.profiles.solidroutine;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;

/** Canonical, allocation-free provider mechanics shared by compatibility facades. */
public final class SolidRoutineProviderForwarding {
    private SolidRoutineProviderForwarding() {}

    public static SolidObjectParams getSolidParams(SolidObjectProvider provider) { return provider.getSolidParams(); }
    public static SolidExecutionMode solidExecutionMode(SolidObjectProvider provider) { return provider.solidExecutionMode(); }
    public static boolean isSolidFor(SolidObjectProvider provider, PlayableEntity player) { return provider.isSolidFor(player); }
    public static int getTopSolidPlayerPositionHistoryFrames(SolidObjectProvider provider, PlayableEntity player) { return provider.getTopSolidPlayerPositionHistoryFrames(player); }
    public static int getFullSolidPlayerPositionHistoryFrames(SolidObjectProvider provider, PlayableEntity player) { return provider.getFullSolidPlayerPositionHistoryFrames(player); }
    public static boolean rejectsZeroDistanceTopSolidLanding(SolidObjectProvider provider) { return provider.rejectsZeroDistanceTopSolidLanding(); }
    public static boolean rejectsZeroDistanceTopSolidLanding(SolidObjectProvider provider, PlayableEntity player) { return provider.rejectsZeroDistanceTopSolidLanding(player); }
    public static boolean allowsZeroDistanceTopSolidLanding(SolidObjectProvider provider, PlayableEntity player) { return provider.allowsZeroDistanceTopSolidLanding(player); }
    public static boolean gatesNewTopSolidLandingWithPreviousPosition(SolidObjectProvider provider) { return provider.gatesNewTopSolidLandingWithPreviousPosition(); }
    public static void onRejectedZeroDistanceTopSolidLanding(SolidObjectProvider provider, PlayableEntity player) { provider.onRejectedZeroDistanceTopSolidLanding(player); }
    public static boolean providesPreMovementGroundAttachmentSupport(SolidObjectProvider provider) { return provider.providesPreMovementGroundAttachmentSupport(); }
    public static boolean preservesObjectManagedRideWhileNotSolidFor(SolidObjectProvider provider, PlayableEntity player) { return provider.preservesObjectManagedRideWhileNotSolidFor(player); }
    public static Integer getObjectManagedRideCentreY(SolidObjectProvider provider, PlayableEntity player, int objectY, SolidObjectParams params) { return provider.getObjectManagedRideCentreY(player, objectY, params); }
    public static int getTopLandingSnapAdjustment(SolidObjectProvider provider, PlayableEntity player, int solidTopYRadius) { return provider.getTopLandingSnapAdjustment(player, solidTopYRadius); }
    public static int getContinuedRideSnapAdjustment(SolidObjectProvider provider, PlayableEntity player, int solidTopYRadius) { return provider.getContinuedRideSnapAdjustment(player, solidTopYRadius); }
    public static boolean skipsCpuSidekickWhenRenderFlagOffScreen(SolidObjectProvider provider) { return provider.skipsCpuSidekickWhenRenderFlagOffScreen(); }
    public static int getTopLandingHalfWidth(SolidObjectProvider provider, PlayableEntity player, int collisionHalfWidth) { return provider.getTopLandingHalfWidth(player, collisionHalfWidth); }
    public static boolean seedsNewRideCarryFromPreUpdateX(SolidObjectProvider provider) { return provider.seedsNewRideCarryFromPreUpdateX(); }
    public static int staleHorizontalLogicalInputFramesWhileRiding(SolidObjectProvider provider, PlayableEntity player, int rideFrames) { return provider.staleHorizontalLogicalInputFramesWhileRiding(player, rideFrames); }
    public static boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(SolidObjectProvider provider, PlayableEntity player) { return provider.fullSolidBottomOverlapUsesCurrentYRadiusOnly(player); }
    public static void setPlayerPushing(SolidObjectProvider provider, PlayableEntity player, boolean pushing) { provider.setPlayerPushing(player, pushing); }
    public static boolean carriesRiderOnHorizontalMove(SolidObjectProvider provider, PlayableEntity player) { return provider.carriesRiderOnHorizontalMove(player); }
    public static boolean suppressSlopeSampleThisFrame(SolidObjectProvider provider, PlayableEntity player) { return provider.suppressSlopeSampleThisFrame(player); }
    public static boolean sampleSlopeOnRideExit(SolidObjectProvider provider, PlayableEntity player) { return provider.sampleSlopeOnRideExit(player); }
}
