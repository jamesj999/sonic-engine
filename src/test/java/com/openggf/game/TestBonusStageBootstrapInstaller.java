package com.openggf.game;

import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.level.objects.ObjectManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBonusStageBootstrapInstaller {

    @Test
    void missingProviderBootstrapIsCreatedOnce() {
        BonusStageProvider provider = new Sonic3kBonusStageCoordinator();
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of());

        BonusStageBootstrapInstaller.ensurePresent(
                provider, BonusStageType.GLOWING_SPHERE, objectManager);

        verify(objectManager).createDynamicObject(any());
    }

    @Test
    void matchingProviderBootstrapIsNotDuplicated() {
        BonusStageProvider provider = new Sonic3kBonusStageCoordinator();
        BonusStageProvider.BootstrapObject bootstrap =
                provider.bootstrapObject(BonusStageType.GLOWING_SPHERE);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(
                List.of(bootstrap.create()));

        BonusStageBootstrapInstaller.ensurePresent(
                provider, BonusStageType.GLOWING_SPHERE, objectManager);

        verify(objectManager, never()).createDynamicObject(any());
    }

    @Test
    void missingSemanticDoesNotTouchTheObjectManager() {
        ObjectManager objectManager = mock(ObjectManager.class);

        BonusStageBootstrapInstaller.ensurePresent(
                NoOpBonusStageProvider.INSTANCE,
                BonusStageType.GLOWING_SPHERE,
                objectManager);

        verify(objectManager, never()).getActiveObjects();
        verify(objectManager, never()).createDynamicObject(any());
    }
}
