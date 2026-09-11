package com.openggf.level.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestTouchResponseProfileMapping {

    @Test
    void providerDefaultsMapToSingleRegionNormalEdgeTriggeredProfile() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new DefaultProvider());

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertFalse(profile.enablesPostSpecialTouchAirborneSideVelocityPreservation());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void providerExposesTouchResponseProfileForDispatcherConsumption() {
        TouchResponseProvider provider = new MultiRegionProvider();

        assertEquals(TouchResponseProfile.fromProvider(provider), provider.getTouchResponseProfile());
        assertEquals(TouchResponseProfile.fromProvider(provider, true), provider.getTouchResponseProfile(true));
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                provider.getTouchResponseProfile().stopAfterFirstOverlapPolicy());
    }

    @Test
    void namedStandardEnemyProfileMatchesSingleRegionBadnikDefaults() {
        TouchResponseProfile profile = TouchResponseProfile.standardEnemy();

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertFalse(profile.enablesPostSpecialTouchAirborneSideVelocityPreservation());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void sonic2SpecialPropertyProviderMapsToSonic2DecodeMode() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new Sonic2SpecialProvider());

        assertEquals(TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY, profile.categoryDecodeMode());
    }

    @Test
    void sonic1SpecialPropertyProviderMapsToSonic1DecodeMode() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new Sonic1SpecialProvider());

        assertEquals(TouchCategoryDecodeMode.S1_SPECIAL_PROPERTY, profile.categoryDecodeMode());
    }

    @Test
    void s3kSpecialPropertyProviderMapsToS3kDecodeMode() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new S3kSpecialProvider());

        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
    }

    @Test
    void mutuallyExclusiveSpecialPropertyDecodeModesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TouchResponseProfile.fromProvider(new ConflictingSpecialProvider()));
    }

    @Test
    void renderFlagOptOutMapsAsProfileValue() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new RenderFlagOptOutProvider());

        assertFalse(profile.requiresRenderFlagForTouch());
    }

    @Test
    void continuousCallbacksMapAsProfileValue() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new ContinuousProvider());

        assertTrue(profile.continuousCallbacks());
    }

    @Test
    void multiRegionPresenceMapsButRegionGeometryStaysDelegated() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new MultiRegionProvider());

        assertTrue(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void nullMultiRegionProviderUsesSingleRegionStopPolicy() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new NullMultiRegionProvider());

        assertFalse(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void shieldReactionBounceBitMapsDeflectCapabilityAndPreservesFlags() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new ShieldDeflectProvider(0x0A));

        assertEquals(TouchShieldDeflectCapability.SHIELD_DEFLECT, profile.shieldDeflectCapability());
        assertEquals(0x0A, profile.shieldReactionFlags());
    }

    @Test
    void nonBounceShieldFlagsRemainFlagsWithoutDeflectCapability() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new ShieldDeflectProvider(0x02));

        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0x02, profile.shieldReactionFlags());
    }

    @Test
    void postSpecialAirborneSideVelocityPreservationMapsAsProfileValue() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new PostSpecialPreservationProvider());

        assertTrue(profile.enablesPostSpecialTouchAirborneSideVelocityPreservation());
    }

    @Test
    void canonicalRoundTripPreservesProfilePolicyValues() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new FullPolicyProvider());

        assertEquals(profile, TouchResponseProfile.fromCanonical(profile.toCanonical()));
    }

    @Test
    void dynamicCollisionAndCallbackStateRemainDelegatedDuringMapping() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new ThrowingDynamicProvider());

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
    }

    @Test
    void dispatcherMappingUsesKnownRegionPresenceWithoutReadingRegionGeometry() {
        TouchResponseProfile profile = TouchResponseProfile.fromProvider(new ThrowingRegionProvider(), true);

        assertTrue(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());
    }

    private static class DefaultProvider implements TouchResponseProvider {
        @Override
        public int getCollisionFlags() {
            return 0x08;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }

    private static final class Sonic2SpecialProvider extends DefaultProvider {
        @Override
        public boolean usesSonic2TouchSpecialPropertyResponse() {
            return true;
        }
    }

    private static final class Sonic1SpecialProvider extends DefaultProvider {
        @Override
        public boolean usesSonic1TouchSpecialPropertyResponse() {
            return true;
        }
    }

    private static final class S3kSpecialProvider extends DefaultProvider {
        @Override
        public boolean usesS3kTouchSpecialPropertyResponse() {
            return true;
        }
    }

    private static final class ConflictingSpecialProvider extends DefaultProvider {
        @Override
        public boolean usesSonic2TouchSpecialPropertyResponse() {
            return true;
        }

        @Override
        public boolean usesS3kTouchSpecialPropertyResponse() {
            return true;
        }
    }

    private static final class RenderFlagOptOutProvider extends DefaultProvider {
        @Override
        public boolean requiresRenderFlagForTouch() {
            return false;
        }
    }

    private static final class ContinuousProvider extends DefaultProvider {
        @Override
        public boolean requiresContinuousTouchCallbacks() {
            return true;
        }
    }

    private static final class MultiRegionProvider extends DefaultProvider {
        @Override
        public TouchRegion[] getMultiTouchRegions() {
            return new TouchRegion[] {
                    new TouchRegion(0x100, 0x200, 0x88),
                    new TouchRegion(0x120, 0x200, 0x88)
            };
        }
    }

    private static final class NullMultiRegionProvider extends DefaultProvider {
        @Override
        public TouchRegion[] getMultiTouchRegions() {
            return null;
        }
    }

    private static final class ShieldDeflectProvider extends DefaultProvider {
        private final int flags;

        private ShieldDeflectProvider(int flags) {
            this.flags = flags;
        }

        @Override
        public int getShieldReactionFlags() {
            return flags;
        }
    }

    private static final class PostSpecialPreservationProvider extends DefaultProvider {
        @Override
        public boolean enablesPostSpecialTouchAirborneSideVelocityPreservation() {
            return true;
        }
    }

    private static final class FullPolicyProvider extends DefaultProvider {
        @Override
        public boolean requiresContinuousTouchCallbacks() {
            return true;
        }

        @Override
        public boolean usesSonic2TouchSpecialPropertyResponse() {
            return true;
        }

        @Override
        public boolean enablesPostSpecialTouchAirborneSideVelocityPreservation() {
            return true;
        }

        @Override
        public boolean requiresRenderFlagForTouch() {
            return false;
        }

        @Override
        public int getShieldReactionFlags() {
            return 0x08;
        }
    }

    private static final class ThrowingDynamicProvider extends DefaultProvider {
        @Override
        public int getCollisionFlags() {
            throw new AssertionError("collision flags must stay delegated");
        }

        @Override
        public int getCollisionProperty() {
            throw new AssertionError("collision property must stay delegated");
        }

        @Override
        public boolean onShieldDeflect(com.openggf.game.PlayableEntity player) {
            throw new AssertionError("shield effects must stay delegated");
        }
    }

    private static final class ThrowingRegionProvider extends DefaultProvider {
        @Override
        public TouchRegion[] getMultiTouchRegions() {
            throw new AssertionError("region geometry must stay delegated");
        }
    }
}
