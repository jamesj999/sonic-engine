package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.profiles.solidroutine.SolidRoutineProviderForwarding;

public record SolidRoutineAdapter(SolidObjectProvider provider, SolidRoutineProfile profile) {
    public SolidObjectParams getSolidParams() {
        return SolidRoutineProviderForwarding.getSolidParams(provider);
    }

    public SolidExecutionMode solidExecutionMode() {
        return SolidRoutineProviderForwarding.solidExecutionMode(provider);
    }

    public boolean isSolidFor(PlayableEntity player) {
        return SolidRoutineProviderForwarding.isSolidFor(provider, player);
    }

    public int getTopSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return SolidRoutineProviderForwarding.getTopSolidPlayerPositionHistoryFrames(provider, player);
    }

    public int getFullSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return SolidRoutineProviderForwarding.getFullSolidPlayerPositionHistoryFrames(provider, player);
    }

    public boolean rejectsZeroDistanceTopSolidLanding() {
        return SolidRoutineProviderForwarding.rejectsZeroDistanceTopSolidLanding(provider);
    }

    public boolean rejectsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return SolidRoutineProviderForwarding.rejectsZeroDistanceTopSolidLanding(provider, player);
    }

    public boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return SolidRoutineProviderForwarding.allowsZeroDistanceTopSolidLanding(provider, player);
    }

    public boolean gatesNewTopSolidLandingWithPreviousPosition() {
        return SolidRoutineProviderForwarding.gatesNewTopSolidLandingWithPreviousPosition(provider);
    }

    public void onRejectedZeroDistanceTopSolidLanding(PlayableEntity player) {
        SolidRoutineProviderForwarding.onRejectedZeroDistanceTopSolidLanding(provider, player);
    }

    public boolean providesPreMovementGroundAttachmentSupport() {
        return SolidRoutineProviderForwarding.providesPreMovementGroundAttachmentSupport(provider);
    }

    public boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        return SolidRoutineProviderForwarding.preservesObjectManagedRideWhileNotSolidFor(provider, player);
    }

    public Integer getObjectManagedRideCentreY(PlayableEntity player, int objectY, SolidObjectParams params) {
        return SolidRoutineProviderForwarding.getObjectManagedRideCentreY(provider, player, objectY, params);
    }

    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return SolidRoutineProviderForwarding.getTopLandingSnapAdjustment(provider, player, solidTopYRadius);
    }

    public int getContinuedRideSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return SolidRoutineProviderForwarding.getContinuedRideSnapAdjustment(provider, player, solidTopYRadius);
    }

    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return SolidRoutineProviderForwarding.skipsCpuSidekickWhenRenderFlagOffScreen(provider);
    }

    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        return SolidRoutineProviderForwarding.getTopLandingHalfWidth(provider, player, collisionHalfWidth);
    }

    public boolean seedsNewRideCarryFromPreUpdateX() {
        return SolidRoutineProviderForwarding.seedsNewRideCarryFromPreUpdateX(provider);
    }

    public int staleHorizontalLogicalInputFramesWhileRiding(PlayableEntity player, int rideFrames) {
        return SolidRoutineProviderForwarding.staleHorizontalLogicalInputFramesWhileRiding(provider, player, rideFrames);
    }

    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        return SolidRoutineProviderForwarding.fullSolidBottomOverlapUsesCurrentYRadiusOnly(provider, player);
    }

    public void setPlayerPushing(PlayableEntity player, boolean pushing) {
        SolidRoutineProviderForwarding.setPlayerPushing(provider, player, pushing);
    }

    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return SolidRoutineProviderForwarding.carriesRiderOnHorizontalMove(provider, player);
    }

    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        return SolidRoutineProviderForwarding.suppressSlopeSampleThisFrame(provider, player);
    }

    public boolean sampleSlopeOnRideExit(PlayableEntity player) {
        return SolidRoutineProviderForwarding.sampleSlopeOnRideExit(provider, player);
    }
}
