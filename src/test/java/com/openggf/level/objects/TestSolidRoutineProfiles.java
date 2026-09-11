package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSolidRoutineProfiles {
    @Test
    void defaultProviderMapsToDefaultFullSolidProfile() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 16, 1, 2);
        SolidObjectProvider provider = new MinimalSolidProvider(params);

        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertFalse(profile.topSolidOnly());
        assertFalse(profile.monitorSolidity());
        assertEquals(0, profile.monitorVerticalOffset());
        // ROM full-solid X gates reject with bhi (S1 SolidObject.asm:122-123,
        // s2.asm:35344+, sonic3k.asm:41393-41399): inclusive right edge is the
        // routine-family default.
        assertTrue(profile.inclusiveRightEdge());
        assertTrue(profile.stickyContactBuffer());
        assertTrue(profile.usesPlatformLandingSnap());
        assertFalse(profile.usesCollisionHalfWidthForTopLanding());
        assertFalse(profile.usesGroundHalfHeightForTopSolidContact());
        assertFalse(profile.bypassesOffscreenSolidGate());
        assertFalse(profile.allowsObjectControlledSolidContacts());
        assertTrue(profile.forceAirOnRideExit());
        assertFalse(profile.dropOnFloor());
        assertFalse(profile.carriesAirborneRiderAfterExitPlatform());

        SolidRoutineAdapter adapter = SolidRoutineProfile.adapt(provider);
        assertEquals(profile, adapter.profile());
        assertSame(params, adapter.getSolidParams());
    }

    @Test
    void providerExposesSolidRoutineProfileForDispatcherConsumption() {
        SolidObjectProvider provider = new MinimalSolidProvider(new SolidObjectParams(14, 14, 14)) {
            @Override
            public boolean hasMonitorSolidity() {
                return true;
            }
        };

        assertEquals(SolidRoutineProfile.fromProvider(provider), provider.getSolidRoutineProfile());
        assertEquals(SolidRoutineKind.MONITOR_SOLID, provider.getSolidRoutineProfile().kind());
    }

    @Test
    void namedFullSolidFactoryUsesDefaultFullSolidPolicy() {
        SolidRoutineProfile profile = SolidRoutineProfile.fullSolid(false);

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertFalse(profile.monitorSolidity());
        assertFalse(profile.stickyContactBuffer());
        assertTrue(profile.usesPlatformLandingSnap());
        assertTrue(profile.forceAirOnRideExit());
    }

    @Test
    void namedFullSolidFactoryCanCarrySpringEdgeAndOffscreenPolicy() {
        SolidRoutineProfile profile = SolidRoutineProfile.fullSolid(true, true, true);

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertTrue(profile.stickyContactBuffer());
        assertTrue(profile.inclusiveRightEdge());
        assertTrue(profile.bypassesOffscreenSolidGate());
        assertTrue(profile.forceAirOnRideExit());
    }

    @Test
    void namedTopSolidFactoryCarriesTopOnlyAndStickyPolicy() {
        SolidRoutineProfile profile = SolidRoutineProfile.topSolid(false);

        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertTrue(profile.topSolidOnly());
        assertFalse(profile.monitorSolidity());
        assertFalse(profile.stickyContactBuffer());
        assertTrue(profile.usesPlatformLandingSnap());
        assertTrue(profile.forceAirOnRideExit());
    }

    @Test
    void namedMonitorSolidFactoryCarriesMonitorOffsetAndStickyPolicy() {
        SolidRoutineProfile profile = SolidRoutineProfile.monitorSolid(4, false);

        assertEquals(SolidRoutineKind.MONITOR_SOLID, profile.kind());
        assertTrue(profile.monitorSolidity());
        assertEquals(4, profile.monitorVerticalOffset());
        assertFalse(profile.stickyContactBuffer());
        assertTrue(profile.forceAirOnRideExit());
    }

    @Test
    void topOnlyPlatformLikeProviderMapsStablePlatformFlags() {
        SolidObjectProvider provider = new MinimalSolidProvider(new SolidObjectParams(24, 8, 4)) {
            @Override
            public boolean isTopSolidOnly() {
                return true;
            }

            @Override
            public boolean usesCollisionHalfWidthForTopLanding() {
                return true;
            }

            @Override
            public boolean usesGroundHalfHeightForTopSolidContact() {
                return true;
            }
        };

        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertTrue(profile.topSolidOnly());
        assertTrue(profile.usesCollisionHalfWidthForTopLanding());
        assertTrue(profile.usesGroundHalfHeightForTopSolidContact());
        assertTrue(profile.usesPlatformLandingSnap());
    }

    @Test
    void monitorLikeProviderMapsMonitorFields() {
        SolidObjectProvider provider = new MinimalSolidProvider(new SolidObjectParams(14, 14, 14)) {
            @Override
            public boolean hasMonitorSolidity() {
                return true;
            }

            @Override
            public int getMonitorSolidObjectVerticalOffset() {
                return 5;
            }
        };

        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.MONITOR_SOLID, profile.kind());
        assertTrue(profile.monitorSolidity());
        assertEquals(5, profile.monitorVerticalOffset());
        assertFalse(profile.topSolidOnly());
    }

    @Test
    void springLikeInclusiveOffscreenProviderMapsSharedFlags() {
        SolidObjectProvider provider = new MinimalSolidProvider(new SolidObjectParams(16, 16, 16)) {
            @Override
            public boolean usesInclusiveRightEdge() {
                return true;
            }

            @Override
            public boolean bypassesOffscreenSolidGate() {
                return true;
            }

            @Override
            public boolean usesStickyContactBuffer() {
                return false;
            }

            @Override
            public boolean usesPlatformObjectLandingSnap() {
                return false;
            }
        };

        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertTrue(profile.inclusiveRightEdge());
        assertTrue(profile.bypassesOffscreenSolidGate());
        assertFalse(profile.stickyContactBuffer());
        assertFalse(profile.usesPlatformLandingSnap());
    }

    @Test
    void solidAdapterDelegatesRiskyHooksInsteadOfOwningThem() {
        SpySolidProvider provider = new SpySolidProvider();
        SolidRoutineAdapter adapter = SolidRoutineProfile.adapt(provider);

        assertSame(provider.params, adapter.getSolidParams());
        assertFalse(adapter.isSolidFor(null));
        assertTrue(adapter.allowsZeroDistanceTopSolidLanding(null));
        assertEquals(7, adapter.getTopLandingHalfWidth(null, 20));
        assertEquals(Integer.valueOf(123), adapter.getObjectManagedRideCentreY(null, 0, provider.params));
        adapter.setPlayerPushing(null, true);

        assertEquals(1, provider.getSolidParamsCalls);
        assertEquals(1, provider.isSolidForCalls);
        assertEquals(1, provider.zeroDistanceCalls);
        assertEquals(1, provider.topWidthCalls);
        assertEquals(1, provider.managedRideYCalls);
        assertEquals(1, provider.pushingCalls);
    }

    @Test
    void slopedDefaultsAndBaselineMapWithoutOwningSlopeData() {
        byte[] slopeData = new byte[] {4, 5, 6};
        SlopedSolidProvider provider = new MinimalSlopedProvider(slopeData, true);

        SlopedSolidRoutineProfile profile = SlopedSolidRoutineProfile.fromProvider(provider);

        assertTrue(profile.usesSlopeForNewLanding());
        assertFalse(profile.usesGroundedStandingCatchWindow());
        assertFalse(profile.addsSlopeCatchRangeToVerticalOverlap());
        assertEquals(4, profile.slopeBaseline());

        SlopedSolidRoutineAdapter adapter = SlopedSolidRoutineProfile.adapt(provider);
        assertEquals(profile, adapter.profile());
        assertArrayEquals(slopeData, adapter.getSlopeData());
        assertTrue(adapter.isSlopeFlipped());
    }

    @Test
    void slopedBaselineMappingReadsProviderOverrideButDelegatesSlopeShape() {
        byte[] slopeData = new byte[] {9, 8, 7};
        SlopedSolidProvider provider = new MinimalSlopedProvider(slopeData, false) {
            @Override
            public boolean usesSlopeForNewLanding() {
                return false;
            }

            @Override
            public boolean usesGroundedStandingCatchWindow() {
                return true;
            }

            @Override
            public boolean addsSlopeCatchRangeToVerticalOverlap() {
                return true;
            }

            @Override
            public int getSlopeBaseline() {
                return 0;
            }
        };

        SlopedSolidRoutineProfile profile = SlopedSolidRoutineProfile.fromProvider(provider);

        assertFalse(profile.usesSlopeForNewLanding());
        assertTrue(profile.usesGroundedStandingCatchWindow());
        assertTrue(profile.addsSlopeCatchRangeToVerticalOverlap());
        assertEquals(0, profile.slopeBaseline());
        assertArrayEquals(slopeData, SlopedSolidRoutineProfile.adapt(provider).getSlopeData());
    }

    @Test
    void slopedAdapterSnapshotsProviderRoutineProfileAndStillDelegatesShape() {
        byte[] slopeData = new byte[] {2, 4, 6};
        SlopedSolidProvider provider = new MinimalSlopedProvider(slopeData, true) {
            @Override
            public boolean usesSlopeForNewLanding() {
                return true;
            }

            @Override
            public boolean usesGroundedStandingCatchWindow() {
                return false;
            }

            @Override
            public boolean addsSlopeCatchRangeToVerticalOverlap() {
                return false;
            }

            @Override
            public int getSlopeBaseline() {
                return 2;
            }

            @Override
            public SlopedSolidRoutineProfile getSlopedSolidRoutineProfile() {
                return new SlopedSolidRoutineProfile(false, true, true, 11);
            }
        };

        SlopedSolidRoutineAdapter adapter = SlopedSolidRoutineProfile.adapt(provider);

        assertEquals(new SlopedSolidRoutineProfile(false, true, true, 11), adapter.profile());
        assertArrayEquals(slopeData, adapter.getSlopeData());
        assertTrue(adapter.isSlopeFlipped());
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

    private static class MinimalSlopedProvider extends MinimalSolidProvider implements SlopedSolidProvider {
        private final byte[] slopeData;
        private final boolean flipped;

        private MinimalSlopedProvider(byte[] slopeData, boolean flipped) {
            super(new SolidObjectParams(32, 8, 16));
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

    private static final class SpySolidProvider extends MinimalSolidProvider {
        private final SolidObjectParams params = new SolidObjectParams(20, 8, 12);
        private int getSolidParamsCalls;
        private int isSolidForCalls;
        private int zeroDistanceCalls;
        private int topWidthCalls;
        private int managedRideYCalls;
        private int pushingCalls;

        private SpySolidProvider() {
            super(new SolidObjectParams(1, 1, 1));
        }

        @Override
        public SolidObjectParams getSolidParams() {
            getSolidParamsCalls++;
            return params;
        }

        @Override
        public boolean isSolidFor(PlayableEntity player) {
            isSolidForCalls++;
            return false;
        }

        @Override
        public boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
            zeroDistanceCalls++;
            return true;
        }

        @Override
        public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
            topWidthCalls++;
            return 7;
        }

        @Override
        public Integer getObjectManagedRideCentreY(PlayableEntity player, int objectY, SolidObjectParams params) {
            managedRideYCalls++;
            return 123;
        }

        @Override
        public void setPlayerPushing(PlayableEntity player, boolean pushing) {
            pushingCalls++;
        }
    }
}
