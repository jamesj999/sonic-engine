package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestMhzMinibossExplosionAllocation {
    @Test
    void failedBurstConsumesAttemptWithoutRngAndNextBurstUsesBothRandomWords() {
        var manager = mock(ObjectManager.class);
        var rng = mock(GameRng.class);
        var controller = new MhzMinibossExplosionController(0x3000, 0x500);
        controller.setServices(new StubObjectServices() {
            @Override public ObjectManager objectManager() { return manager; }
            @Override public GameRng rng() { return rng; }
        });
        controller.setSlotIndex(7);
        when(manager.allocateSlotAfter(7)).thenReturn(-1, 8);
        when(rng.nextRaw()).thenReturn(0x003F0010);

        controller.update(0, null);
        controller.update(1, null);
        controller.update(2, null);
        verify(manager, times(1)).allocateSlotAfter(7);
        verifyNoInteractions(rng);
        controller.update(3, null);

        var child = ArgumentCaptor.forClass(S3kBossExplosionChild.class);
        verify(manager).addDynamicObjectAtSlot(child.capture(), eq(8));
        assertEquals(0x2FF0, child.getValue().getX());
        assertEquals(0x51F, child.getValue().getY(), "SWAP selects the random high word for Y");
        assertTrue(child.getValue().nativeInitSfxForTest());
        assertFalse(child.getValue().nativeInitSfxPlayedForTest(), "allocation does not play the child's sound");
        for (int frame = 4; frame < 95; frame++) {
            controller.update(frame, null);
        }
        verify(manager, times(31)).allocateSlotAfter(7);
        verify(rng, times(30)).nextRaw();
        assertTrue(controller.isDestroyed(), "the failed attempt does not extend the controller lifetime");
    }
}
