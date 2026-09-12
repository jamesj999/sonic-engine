package com.openggf.game.profiles.touchresponse;

import com.openggf.level.objects.TouchResponseProvider;
import java.util.Objects;

/** Canonical provider-to-profile mapping used by compatibility views. */
public final class TouchResponseProfileMapper {
    private static final int SHIELD_REACTION_BOUNCE_BIT = 0x08;

    private TouchResponseProfileMapper() {
    }

    public static TouchResponseProfile fromProvider(
            TouchResponseProvider provider, boolean multiRegionSource) {
        Objects.requireNonNull(provider, "provider");
        int shieldFlags = provider.getShieldReactionFlags();
        return new TouchResponseProfile(
                decodeMode(provider),
                provider.requiresContinuousTouchCallbacks(),
                provider.requiresRenderFlagForTouch(),
                multiRegionSource,
                (shieldFlags & SHIELD_REACTION_BOUNCE_BIT) != 0
                        ? TouchShieldDeflectCapability.SHIELD_DEFLECT
                        : TouchShieldDeflectCapability.NONE,
                shieldFlags,
                provider.enablesPostSpecialTouchAirborneSideVelocityPreservation(),
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                multiRegionSource
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    private static TouchCategoryDecodeMode decodeMode(TouchResponseProvider provider) {
        boolean sonic1 = provider.usesSonic1TouchSpecialPropertyResponse();
        boolean sonic2 = provider.usesSonic2TouchSpecialPropertyResponse();
        boolean s3k = provider.usesS3kTouchSpecialPropertyResponse();
        if ((sonic1 ? 1 : 0) + (sonic2 ? 1 : 0) + (s3k ? 1 : 0) > 1) {
            throw new IllegalArgumentException(
                    "Touch special-property decode mode must be Sonic 1, Sonic 2, or S3K");
        }
        if (sonic1) {
            return TouchCategoryDecodeMode.S1_SPECIAL_PROPERTY;
        }
        if (sonic2) {
            return TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY;
        }
        if (s3k) {
            return TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY;
        }
        return TouchCategoryDecodeMode.NORMAL;
    }
}
