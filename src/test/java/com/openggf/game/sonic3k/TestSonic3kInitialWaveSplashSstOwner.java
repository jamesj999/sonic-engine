package com.openggf.game.sonic3k;

import com.openggf.sprites.managers.ProcessSpritesEpoch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic3kInitialWaveSplashSstOwner {

    @Test
    void ownerIsExplicitlyUnregisteredWithoutAWaterSurfaceManager() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        assertFalse(provider.initialWaveSplashSstOwner().isRegistered());
    }

    @Test
    void ownerRegistrationFollowsTheSemanticWaterSurfaceOwner() throws Exception {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        Sonic3kWaterSurfaceManager manager = mock(Sonic3kWaterSurfaceManager.class);
        when(manager.isInitialized()).thenReturn(true);
        Field field = Sonic3kZoneFeatureProvider.class
                .getDeclaredField("waterSurfaceManager");
        field.setAccessible(true);
        field.set(provider, manager);

        InitialWaveSplashSstOwner owner = provider.initialWaveSplashSstOwner();
        assertTrue(owner.isRegistered());
        ProcessSpritesEpoch epoch = new ProcessSpritesEpoch(0, 1, false);
        owner.processInitialWaveSplash(epoch);
        verify(manager).processInitialSstSlot(epoch);
    }
}
