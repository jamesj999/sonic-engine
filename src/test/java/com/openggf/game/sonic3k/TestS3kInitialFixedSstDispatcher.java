package com.openggf.game.sonic3k;

import com.openggf.game.PowerUpObject;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestS3kInitialFixedSstDispatcher {

    @Test
    void dispatchesAirPlayableEffectsPowerUpsThenRegisteredWaveOwner() {
        Sonic3kLevelEventManager events = mock(Sonic3kLevelEventManager.class);
        SpriteManager sprites = mock(SpriteManager.class);
        ObjectManager objects = mock(ObjectManager.class);
        AbstractPlayableSprite p1 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite p2 = mock(AbstractPlayableSprite.class);
        ObjectInstance p1Shield = powerUpObject();
        ObjectInstance p1Stars = powerUpObject();
        ObjectInstance p2Shield = powerUpObject();
        ObjectInstance p2Stars = powerUpObject();
        when(p1.getShieldObject()).thenReturn((PowerUpObject) p1Shield);
        when(p1.getInvincibilityObject()).thenReturn((PowerUpObject) p1Stars);
        when(p2.getShieldObject()).thenReturn((PowerUpObject) p2Shield);
        when(p2.getInvincibilityObject()).thenReturn((PowerUpObject) p2Stars);
        when(sprites.getMainPlayable()).thenReturn(p1);
        when(sprites.getRegisteredSidekicks()).thenReturn(List.of(p2));
        RecordingWaveOwner wave = new RecordingWaveOwner(true);
        ProcessSpritesEpoch epoch = new ProcessSpritesEpoch(0, 1, false);

        S3kInitialFixedSstDispatcher dispatcher =
                new S3kInitialFixedSstDispatcher(events, sprites, objects, wave);
        dispatcher.onInitialScopeAcquired();
        dispatcher.processPostDynamicFixedSlots(epoch);

        InOrder order = inOrder(events, sprites, objects);
        order.verify(events).processInitialFixedAirSlot(0, p1);
        order.verify(events).processInitialFixedAirSlot(1, p2);
        order.verify(sprites).processInitialTailsFixedSlot();
        order.verify(sprites).processInitialDustFixedSlot(0);
        order.verify(sprites).processInitialDustFixedSlot(1);
        order.verify(objects).processInitialFixedDispatchObject(p1Shield);
        order.verify(objects).processInitialFixedDispatchObject(p2Shield);
        order.verify(objects).processInitialFixedDispatchObject(p1Stars);
        order.verify(objects).processInitialFixedDispatchObject(p2Stars);
        assertEquals(1, wave.calls);
        assertEquals(epoch, wave.lastEpoch);
    }

    @Test
    void explicitEmptyWaveSlotDoesNotInvokeUnregisteredOwner() {
        Sonic3kLevelEventManager events = mock(Sonic3kLevelEventManager.class);
        SpriteManager sprites = mock(SpriteManager.class);
        ObjectManager objects = mock(ObjectManager.class);
        when(sprites.getRegisteredSidekicks()).thenReturn(List.of());
        RecordingWaveOwner wave = new RecordingWaveOwner(false);

        S3kInitialFixedSstDispatcher dispatcher =
                new S3kInitialFixedSstDispatcher(events, sprites, objects, wave);
        dispatcher.onInitialScopeAcquired();
        dispatcher.processPostDynamicFixedSlots(new ProcessSpritesEpoch(0, 1, false));

        assertEquals(0, wave.calls);
        verify(sprites).processInitialTailsFixedSlot();
    }

    private static final class RecordingWaveOwner implements InitialWaveSplashSstOwner {
        private final boolean registered;
        private int calls;
        private ProcessSpritesEpoch lastEpoch;

        private RecordingWaveOwner(boolean registered) {
            this.registered = registered;
        }

        @Override
        public boolean isRegistered() {
            return registered;
        }

        @Override
        public void processInitialWaveSplash(ProcessSpritesEpoch epoch) {
            calls++;
            lastEpoch = epoch;
        }
    }

    private static ObjectInstance powerUpObject() {
        return mock(ObjectInstance.class,
                withSettings().extraInterfaces(PowerUpObject.class));
    }
}
