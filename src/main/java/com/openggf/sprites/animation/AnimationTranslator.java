package com.openggf.sprites.animation;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.DonorCapabilities;

import java.util.Map;

/**
 * Translates a donor game's {@link ScriptedVelocityAnimationProfile} into a new profile
 * where every animation ID is validated against the donor's {@link SpriteAnimationSet}.
 *
 * <p>For each profile field, the translator resolves the canonical animation through the
 * donor's fallback map, converts it to a native animation ID, and verifies that a script
 * exists in the donor's animation set. If resolution fails or the script is absent the
 * field is set to {@code -1} (disabled).</p>
 */
public final class AnimationTranslator {

    private AnimationTranslator() {
    }

    /**
     * Translates {@code donorProfile} into a new {@link ScriptedVelocityAnimationProfile}
     * whose animation ID fields point to valid scripts in {@code donorAnimSet}.
     *
     * @param donor        capabilities of the donor game (fallback map + native ID resolver)
     * @param donorProfile the source profile from the donor game
     * @param donorAnimSet the animation set containing the donor's scripts
     * @return a new profile with translated (and validated) animation IDs
     */
    public static ScriptedVelocityAnimationProfile translate(
            DonorCapabilities donor,
            ScriptedVelocityAnimationProfile donorProfile,
            SpriteAnimationSet donorAnimSet) {

        Map<CanonicalAnimation, CanonicalAnimation> fallbacks = donor.getAnimationFallbacks();

        ScriptedVelocityAnimationProfile result = new ScriptedVelocityAnimationProfile();

        // Copy non-animation properties
        result.setAnglePreAdjust(donorProfile.isAnglePreAdjust());
        result.setCompactSuperRunSlope(donorProfile.isCompactSuperRunSlope());
        result.setSuperAlternateFrameStep(
                donorProfile.getSuperAlternateFrameMask(),
                donorProfile.getSuperAlternateFrameStep(),
                donorProfile.getSuperAlternateFrameCeiling());
        result.setWalkRunPublishesFrameBeforeTimerAdvance(
                donorProfile.isWalkRunPublishesFrameBeforeTimerAdvance());
        int highSpeedAnimId = donorProfile.getHighSpeedWalkRunAnimId();
        result.setHighSpeedWalkRunAnimId(highSpeedAnimId >= 0
                && donorAnimSet.getScript(highSpeedAnimId) != null ? highSpeedAnimId : -1);
        result.setHighSpeedWalkRunThreshold(donorProfile.getHighSpeedWalkRunThreshold());
        result.setWalkSlopeFrameStride(donorProfile.getWalkSlopeFrameStride());
        result.setRunSlopeFrameStride(donorProfile.getRunSlopeFrameStride());
        result.setHighSpeedSlopeFrameStride(donorProfile.getHighSpeedSlopeFrameStride());
        result.setDoubleWalkRunAnimationSpeedWhenSliding(
                donorProfile.isDoubleWalkRunAnimationSpeedWhenSliding());
        result.setWalkSpeedThreshold(donorProfile.getWalkSpeedThreshold());
        result.setRunSpeedThreshold(donorProfile.getRunSpeedThreshold());
        result.setRunFramesUseWalkAnimationId(donorProfile.isRunFramesUseWalkAnimationId());
        result.setFallbackFrame(donorProfile.getFallbackFrame());

        // Translate each animation field
        // walkId is kept as a variable because airAnimId reuses it
        int walkId = resolve(CanonicalAnimation.WALK, fallbacks, donor, donorAnimSet);

        result.setIdleAnimId(resolve(CanonicalAnimation.WAIT,      fallbacks, donor, donorAnimSet));
        result.setWalkAnimId(walkId);
        result.setRunAnimId(resolve(CanonicalAnimation.RUN,        fallbacks, donor, donorAnimSet));
        result.setRollAnimId(resolve(CanonicalAnimation.ROLL,       fallbacks, donor, donorAnimSet));
        result.setRoll2AnimId(resolve(CanonicalAnimation.ROLL2,    fallbacks, donor, donorAnimSet));
        result.setPushAnimId(resolve(CanonicalAnimation.PUSH,      fallbacks, donor, donorAnimSet));
        result.setDuckAnimId(resolve(CanonicalAnimation.DUCK,      fallbacks, donor, donorAnimSet));
        result.setLookUpAnimId(resolve(CanonicalAnimation.LOOK_UP, fallbacks, donor, donorAnimSet));
        result.setSpindashAnimId(resolve(CanonicalAnimation.SPINDASH, fallbacks, donor, donorAnimSet));
        result.setSpringAnimId(resolve(CanonicalAnimation.SPRING,  fallbacks, donor, donorAnimSet));
        result.setDeathAnimId(resolve(CanonicalAnimation.DEATH,    fallbacks, donor, donorAnimSet));
        result.setHurtAnimId(resolve(CanonicalAnimation.HURT,      fallbacks, donor, donorAnimSet));
        result.setSkidAnimId(resolve(CanonicalAnimation.SKID,      fallbacks, donor, donorAnimSet));
        result.setSlideAnimId(resolve(CanonicalAnimation.SLIDE,    fallbacks, donor, donorAnimSet));
        result.setDrownAnimId(resolve(CanonicalAnimation.DROWN,    fallbacks, donor, donorAnimSet));
        result.setBalanceAnimId(resolve(CanonicalAnimation.BALANCE,   fallbacks, donor, donorAnimSet));
        result.setBalance2AnimId(resolve(CanonicalAnimation.BALANCE2, fallbacks, donor, donorAnimSet));
        result.setBalance3AnimId(resolve(CanonicalAnimation.BALANCE3, fallbacks, donor, donorAnimSet));
        result.setBalance4AnimId(resolve(CanonicalAnimation.BALANCE4, fallbacks, donor, donorAnimSet));

        // Special case: airAnimId always equals the translated walkAnimId
        result.setAirAnimId(walkId);

        return result;
    }

    /**
     * Resolves a single canonical animation to a donor-native integer ID, then validates
     * that a script for that ID exists in {@code animSet}.
     *
     * <p>Resolution steps:</p>
     * <ol>
     *   <li>Look up the fallback canonical (defaulting to {@link CanonicalAnimation#WAIT} if absent).</li>
     *   <li>Convert the fallback canonical to a native integer ID via
     *       {@link DonorCapabilities#resolveNativeId(CanonicalAnimation)}.</li>
     *   <li>If the native ID is &ge;0 and a script exists in {@code animSet}, return the ID.</li>
     *   <li>Otherwise return {@code -1}.</li>
     * </ol>
     */
    private static int resolve(
            CanonicalAnimation canonical,
            Map<CanonicalAnimation, CanonicalAnimation> fallbacks,
            DonorCapabilities donor,
            SpriteAnimationSet animSet) {

        CanonicalAnimation fallbackCanonical =
                fallbacks.getOrDefault(canonical, CanonicalAnimation.WAIT);
        int nativeId = donor.resolveNativeId(fallbackCanonical);
        if (nativeId >= 0 && animSet.getScript(nativeId) != null) {
            return nativeId;
        }
        return -1;
    }
}
