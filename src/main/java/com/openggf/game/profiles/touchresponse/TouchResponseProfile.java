package com.openggf.game.profiles.touchresponse;

import java.util.Objects;

@com.openggf.game.ModApi
public record TouchResponseProfile(
        TouchCategoryDecodeMode categoryDecodeMode,
        boolean continuousCallbacks,
        boolean requiresRenderFlagForTouch,
        boolean multiRegionSource,
        TouchShieldDeflectCapability shieldDeflectCapability,
        int shieldReactionFlags,
        boolean enablesPostSpecialTouchAirborneSideVelocityPreservation,
        TouchAttackBouncePolicy attackBouncePolicy,
        TouchActorContextPolicy actorContextPolicy,
        TouchOverlapStopPolicy stopAfterFirstOverlapPolicy) {

    private static final int SHIELD_REACTION_BOUNCE_BIT = 0x08;

    public TouchResponseProfile {
        Objects.requireNonNull(categoryDecodeMode, "categoryDecodeMode");
        Objects.requireNonNull(shieldDeflectCapability, "shieldDeflectCapability");
        Objects.requireNonNull(attackBouncePolicy, "attackBouncePolicy");
        Objects.requireNonNull(actorContextPolicy, "actorContextPolicy");
        Objects.requireNonNull(stopAfterFirstOverlapPolicy, "stopAfterFirstOverlapPolicy");
    }

    public TouchResponseProfile(
            TouchCategoryDecodeMode categoryDecodeMode,
            boolean continuousCallbacks,
            boolean requiresRenderFlagForTouch,
            boolean multiRegionSource,
            TouchShieldDeflectCapability shieldDeflectCapability,
            int shieldReactionFlags,
            TouchAttackBouncePolicy attackBouncePolicy,
            TouchActorContextPolicy actorContextPolicy,
            TouchOverlapStopPolicy stopAfterFirstOverlapPolicy) {
        this(categoryDecodeMode,
                continuousCallbacks,
                requiresRenderFlagForTouch,
                multiRegionSource,
                shieldDeflectCapability,
                shieldReactionFlags,
                false,
                attackBouncePolicy,
                actorContextPolicy,
                stopAfterFirstOverlapPolicy);
    }

    public static TouchResponseProfile fromProvider(com.openggf.level.objects.TouchResponseProvider provider) {
        Objects.requireNonNull(provider, "provider");
        boolean sonic1 = provider.usesSonic1TouchSpecialPropertyResponse();
        boolean sonic2 = provider.usesSonic2TouchSpecialPropertyResponse();
        boolean s3k = provider.usesS3kTouchSpecialPropertyResponse();
        TouchCategoryDecodeMode decodeMode = TouchResponseProfileMapper.decodeMode(sonic1, sonic2, s3k);
        return TouchResponseProfileMapper.fromDecodedProvider(
                provider, decodeMode, provider.getMultiTouchRegions() != null);
    }

    public static TouchResponseProfile standardEnemy() {
        return standardEnemy(false);
    }

    public static TouchResponseProfile standardEnemy(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                false,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                multiRegionSource
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    public static TouchResponseProfile singleRegionContinuousCallbacks() {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                false,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    public static TouchResponseProfile singleRegionShieldDeflect() {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                SHIELD_REACTION_BOUNCE_BIT,
                false,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }
}
