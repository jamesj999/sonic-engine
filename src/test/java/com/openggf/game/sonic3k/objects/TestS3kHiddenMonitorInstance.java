package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kHiddenMonitorInstance {
    private static final int HIDDEN_MONITOR = 0x80;

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void outOfRangeLandedSignpostSwitchesToOnScreenTestWithoutImmediateDestroy() throws Exception {
        S3kHiddenMonitorInstance hidden = new S3kHiddenMonitorInstance(new ObjectSpawn(
                0x1800, 0x0600, HIDDEN_MONITOR, 3, 0, false, 0));
        AbstractObjectInstance.updateCameraBounds(0x1700, 0, 0x1840, 0x00E0, 0);
        S3kSignpostInstance signpost = landedSignpostAt(0x1900, 0x0600);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.activeObjectsOfType(S3kSignpostInstance.class)).thenReturn(List.of(signpost));
        RecordingServices services = new RecordingServices(objectManager);
        hidden.setServices(services);

        hidden.update(0, null);
        hidden.update(1, null);

        assertFalse(hidden.isDestroyed(),
                "Obj_HiddenMonitor loc_8374C changes code to Sprite_OnScreen_Test, then only deletes by range");
        assertEquals(1, services.groundSlideCount,
                "Obj_HiddenMonitor must play sfx_GroundSlide once when the landed signpost is outside word_8379E");
        assertTrue(signpost.isLanded(),
                "The out-of-range branch must not clear signpost bit 0 at $38(a1)");
    }

    @Test
    void inRangeLandedSignpostTransfersCurrentSlotToRevealedMonitor() throws Exception {
        S3kHiddenMonitorInstance hidden = new S3kHiddenMonitorInstance(new ObjectSpawn(
                0x1800, 0x0600, HIDDEN_MONITOR, 3, 0, false, 0));
        hidden.setSlotIndex(42);
        ObjectManager objectManager = mock(ObjectManager.class);
        RecordingServices services = new RecordingServices(objectManager);
        hidden.setServices(services);
        AbstractObjectInstance.updateCameraBounds(0x1700, 0, 0x1840, 0x00E0, 0);
        S3kSignpostInstance signpost = landedSignpostAt(0x1800, 0x0600);
        when(objectManager.activeObjectsOfType(S3kSignpostInstance.class)).thenReturn(List.of(signpost));

        hidden.update(0, null);

        assertTrue(hidden.isDestroyed(),
                "Obj_HiddenMonitor loc_83760 replaces the current SST slot with Obj_Monitor");
        assertFalse(signpost.isLanded(),
                "Obj_HiddenMonitor loc_83760 clears signpost landed bit 0 at $38(a1)");
        assertEquals(1, services.bubbleAttackCount,
                "Obj_HiddenMonitor must play sfx_BubbleAttack when the signpost is inside word_8379E");
        verify(objectManager).addDynamicObjectAtSlot(any(Sonic3kMonitorObjectInstance.class), eq(42));
        verify(objectManager, never()).addDynamicObjectAfterCurrent(any());
    }

    private static S3kSignpostInstance landedSignpostAt(int x, int y) throws Exception {
        S3kSignpostInstance signpost = new S3kSignpostInstance(x, 0);
        setPrivateField(signpost, "worldY", y);
        signpost.setLanded(true);
        return signpost;
    }

    private static void setPrivateField(Object instance, String fieldName, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ObjectManager objectManager;
        private int groundSlideCount;
        private int bubbleAttackCount;

        private RecordingServices() {
            this(null);
        }

        private RecordingServices(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            if (soundId == Sonic3kSfx.GROUND_SLIDE.id) {
                groundSlideCount++;
            } else if (soundId == Sonic3kSfx.BUBBLE_ATTACK.id) {
                bubbleAttackCount++;
            }
        }
    }
}
