package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

public record SolidRoutineAdapter(SolidObjectProvider provider, SolidRoutineProfile profile) {
    public SolidObjectParams getSolidParams() {
        return provider.getSolidParams();
    }

    public SolidExecutionMode solidExecutionMode() {
        return provider.solidExecutionMode();
    }

    public boolean isSolidFor(PlayableEntity player) {
        return provider.isSolidFor(player);
    }

    public int getTopSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return provider.getTopSolidPlayerPositionHistoryFrames(player);
    }

    public int getFullSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return provider.getFullSolidPlayerPositionHistoryFrames(player);
    }

    public boolean rejectsZeroDistanceTopSolidLanding() {
        return provider.rejectsZeroDistanceTopSolidLanding();
    }

    public boolean rejectsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return provider.rejectsZeroDistanceTopSolidLanding(player);
    }

    public boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return provider.allowsZeroDistanceTopSolidLanding(player);
    }

    public boolean gatesNewTopSolidLandingWithPreviousPosition() {
        return provider.gatesNewTopSolidLandingWithPreviousPosition();
    }

    public void onRejectedZeroDistanceTopSolidLanding(PlayableEntity player) {
        provider.onRejectedZeroDistanceTopSolidLanding(player);
    }

    public boolean providesPreMovementGroundAttachmentSupport() {
        return provider.providesPreMovementGroundAttachmentSupport();
    }

    public boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        return provider.preservesObjectManagedRideWhileNotSolidFor(player);
    }

    public Integer getObjectManagedRideCentreY(PlayableEntity player, int objectY, SolidObjectParams params) {
        return provider.getObjectManagedRideCentreY(player, objectY, params);
    }

    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return provider.getTopLandingSnapAdjustment(player, solidTopYRadius);
    }

    public int getContinuedRideSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return provider.getContinuedRideSnapAdjustment(player, solidTopYRadius);
    }

    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return provider.skipsCpuSidekickWhenRenderFlagOffScreen();
    }

    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        return provider.getTopLandingHalfWidth(player, collisionHalfWidth);
    }

    public boolean seedsNewRideCarryFromPreUpdateX() {
        return provider.seedsNewRideCarryFromPreUpdateX();
    }

    public int staleHorizontalLogicalInputFramesWhileRiding(PlayableEntity player, int rideFrames) {
        return provider.staleHorizontalLogicalInputFramesWhileRiding(player, rideFrames);
    }

    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        return provider.fullSolidBottomOverlapUsesCurrentYRadiusOnly(player);
    }

    public void setPlayerPushing(PlayableEntity player, boolean pushing) {
        provider.setPlayerPushing(player, pushing);
    }

    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return provider.carriesRiderOnHorizontalMove(player);
    }

    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        return provider.suppressSlopeSampleThisFrame(player);
    }

    public boolean sampleSlopeOnRideExit(PlayableEntity player) {
        return provider.sampleSlopeOnRideExit(player);
    }
}
