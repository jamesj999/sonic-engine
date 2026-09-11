package com.openggf.game.profiles;

import com.openggf.game.profiles.objectlifecycle.ObjectLifecycleDestructionMode;
import com.openggf.game.profiles.objectlifecycle.ObjectLifecycleProfile;
import com.openggf.game.profiles.objectlifecycle.ObjectLifecycleRespawnPolicy;
import com.openggf.game.profiles.objectlifecycle.ObjectLifecycleSlotPolicy;
import com.openggf.game.profiles.solidroutine.SolidRoutineKind;
import com.openggf.game.profiles.solidroutine.SolidRoutineProfile;
import com.openggf.game.profiles.solidroutine.SlopedSolidRoutineProfile;
import com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode;
import com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy;
import com.openggf.game.profiles.touchresponse.TouchResponseProfile;
import com.openggf.game.profiles.touchresponse.TouchShieldDeflectCapability;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProvider.TouchRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCanonicalObjectPhysicsProfiles {

    @Test
    void canonicalSolidProfileMapsCurrentProviderDefaults() {
        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(
                new MinimalSolidProvider(new SolidObjectParams(16, 8, 16)));

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertFalse(profile.topSolidOnly());
        assertFalse(profile.monitorSolidity());
        assertTrue(profile.stickyContactBuffer());
        assertTrue(profile.usesPlatformLandingSnap());
        assertTrue(profile.forceAirOnRideExit());
    }

    @Test
    void legacySolidProfileConvertsToCanonicalProfile() {
        com.openggf.level.objects.SolidRoutineProfile legacy =
                com.openggf.level.objects.SolidRoutineProfile.fromProvider(
                        new MinimalSolidProvider(new SolidObjectParams(16, 8, 16)) {
                            @Override
                            public boolean isTopSolidOnly() {
                                return true;
                            }
                        });

        SolidRoutineProfile canonical = legacy.toCanonical();

        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, canonical.kind());
        assertTrue(canonical.topSolidOnly());
        assertEquals(legacy.usesPlatformLandingSnap(), canonical.usesPlatformLandingSnap());
    }

    @Test
    void canonicalSolidProfileConvertsThroughLegacyWrapperWithoutFieldLoss() {
        SolidRoutineProfile canonical = new SolidRoutineProfile(
                SolidRoutineKind.MONITOR_SOLID,
                false,
                true,
                11,
                false,
                true,
                false,
                true,
                false,
                true,
                false,
                true,
                false,
                true);

        assertEquals(canonical,
                com.openggf.level.objects.SolidRoutineProfile.fromCanonical(canonical).toCanonical());
    }

    @Test
    void canonicalSolidFactoriesNameFullAndMonitorPolicies() {
        SolidRoutineProfile full = SolidRoutineProfile.fullSolid(false);
        SolidRoutineProfile horizontalSpring = SolidRoutineProfile.fullSolid(true, true, true);
        SolidRoutineProfile top = SolidRoutineProfile.topSolid(false);
        SolidRoutineProfile monitor = SolidRoutineProfile.monitorSolid(4, false);

        assertEquals(SolidRoutineKind.FULL_SOLID, full.kind());
        assertFalse(full.monitorSolidity());
        assertFalse(full.stickyContactBuffer());
        assertTrue(full.forceAirOnRideExit());
        assertEquals(SolidRoutineKind.FULL_SOLID, horizontalSpring.kind());
        assertTrue(horizontalSpring.inclusiveRightEdge());
        assertTrue(horizontalSpring.bypassesOffscreenSolidGate());
        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, top.kind());
        assertTrue(top.topSolidOnly());
        assertFalse(top.stickyContactBuffer());
        assertEquals(SolidRoutineKind.MONITOR_SOLID, monitor.kind());
        assertTrue(monitor.monitorSolidity());
        assertEquals(4, monitor.monitorVerticalOffset());
        assertFalse(monitor.stickyContactBuffer());
        assertTrue(monitor.forceAirOnRideExit());
    }

    @Test
    void canonicalSlopedProfileMapsCurrentProviderDefaultsAndDelegatesSlopeData() {
        byte[] slopeData = {1, 2, 3};
        SlopedSolidProvider provider = new MinimalSlopedProvider(slopeData, true);

        SlopedSolidRoutineProfile profile = SlopedSolidRoutineProfile.fromProvider(provider);

        assertTrue(profile.usesSlopeForNewLanding());
        assertEquals(1, profile.slopeBaseline());
        assertArrayEquals(slopeData, SlopedSolidRoutineProfile.adapt(provider).getSlopeData());
    }

    @Test
    void canonicalSlopedProfileConvertsThroughLegacyWrapperWithoutFieldLoss() {
        SlopedSolidRoutineProfile canonical = new SlopedSolidRoutineProfile(false, true, false, 9);

        assertEquals(canonical,
                com.openggf.level.objects.SlopedSolidRoutineProfile.fromCanonical(canonical).toCanonical());
    }

    @Test
    void canonicalTouchProfileMapsCurrentProviderDefaultsAndMultiRegionPolicy() {
        TouchResponseProfile defaults = TouchResponseProfile.fromProvider(new MinimalTouchProvider());
        TouchResponseProfile multiRegion = TouchResponseProfile.fromProvider(new MultiRegionTouchProvider());
        TouchResponseProfile standardEnemy = TouchResponseProfile.standardEnemy();

        assertEquals(TouchCategoryDecodeMode.NORMAL, defaults.categoryDecodeMode());
        assertEquals(TouchShieldDeflectCapability.NONE, defaults.shieldDeflectCapability());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                defaults.stopAfterFirstOverlapPolicy());
        assertTrue(multiRegion.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                multiRegion.stopAfterFirstOverlapPolicy());
        assertEquals(defaults, standardEnemy);
    }

    @Test
    void legacyTouchProfileConvertsToCanonicalProfile() {
        com.openggf.level.objects.TouchResponseProfile legacy =
                com.openggf.level.objects.TouchResponseProfile.fromProvider(new ShieldTouchProvider());

        TouchResponseProfile canonical = legacy.toCanonical();

        assertEquals(TouchShieldDeflectCapability.SHIELD_DEFLECT, canonical.shieldDeflectCapability());
        assertEquals(0x08, canonical.shieldReactionFlags());
    }

    @Test
    void canonicalTouchProfileConvertsThroughLegacyWrapperWithoutFieldLoss() {
        TouchResponseProfile canonical = new TouchResponseProfile(
                TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                true,
                true,
                true,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                0x8C,
                com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);

        assertEquals(canonical,
                com.openggf.level.objects.TouchResponseProfile.fromCanonical(canonical).toCanonical());
    }

    @Test
    void lifecycleProfilesNamePhaseZeroCategoriesWithoutOwningSlotIdentity() {
        assertEquals(ObjectLifecycleProfile.LATCHED_DESTRUCTION,
                new ObjectLifecycleProfile(
                        ObjectLifecycleDestructionMode.DESTROY_LATCHED,
                        ObjectLifecycleRespawnPolicy.NO_RESPAWN,
                        ObjectLifecycleSlotPolicy.RETAIN_CURRENT_SLOT));
        assertEquals(ObjectLifecycleSlotPolicy.TRANSFER_SLOT_TO_REPLACEMENT,
                ObjectLifecycleProfile.TRANSFER_TO_REPLACEMENT.slotPolicy());
        assertEquals(ObjectLifecycleRespawnPolicy.RESPAWN_WHEN_REENTERED,
                ObjectLifecycleProfile.RESPAWNABLE_OFFSCREEN.respawnPolicy());
        assertEquals(ObjectLifecycleSlotPolicy.RELEASE_PARENT_KEEP_CHILDREN,
                ObjectLifecycleProfile.PARENT_RELEASE_KEEP_CHILDREN.slotPolicy());
    }

    private static class MinimalSolidProvider implements SolidObjectProvider {
        private final SolidObjectParams params;

        private MinimalSolidProvider(SolidObjectParams params) {
            this.params = params;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }
    }

    private static final class MinimalSlopedProvider extends MinimalSolidProvider implements SlopedSolidProvider {
        private final byte[] slopeData;
        private final boolean flipped;

        private MinimalSlopedProvider(byte[] slopeData, boolean flipped) {
            super(new SolidObjectParams(16, 8, 16));
            this.slopeData = slopeData;
            this.flipped = flipped;
        }

        @Override
        public byte[] getSlopeData() {
            return slopeData;
        }

        @Override
        public boolean isSlopeFlipped() {
            return flipped;
        }
    }

    private static class MinimalTouchProvider implements TouchResponseProvider {
        @Override
        public int getCollisionFlags() {
            return 0x08;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }

    private static final class MultiRegionTouchProvider extends MinimalTouchProvider {
        @Override
        public TouchRegion[] getMultiTouchRegions() {
            return new TouchRegion[] {new TouchRegion(0, 0, 0x88)};
        }
    }

    private static final class ShieldTouchProvider extends MinimalTouchProvider {
        @Override
        public int getShieldReactionFlags() {
            return 0x08;
        }
    }
}
