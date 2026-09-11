package com.openggf.tests;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzAlarmObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestS3kLbzAlarmObject {

    @Test
    void registryCreatesLbzAlarmAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_ALARM, 0, 0, false, 0));

        assertInstanceOf(LbzAlarmObjectInstance.class, instance);
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.LBZ_ALARM));
    }

    @Test
    void alarmUsesS3kSpecialPropertyCollisionAndLatchesTouchProperty() {
        LbzAlarmObjectInstance alarm = new LbzAlarmObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_ALARM, 0, 0, false, 0));

        TouchResponseProfile profile = alarm.getTouchResponseProfile();

        assertEquals(0xD7, alarm.getCollisionFlags());
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());

        alarm.onTouchResponse(playerAt(0x0200, 0x0100), specialTouch(), 0);

        assertEquals(1, alarm.getCollisionProperty());
    }

    @Test
    void immediateSubtypeSpawnsFlybotAtPlayerOffsetAndPlaysAlarmEveryThirtyTwoFrames() {
        RecordingServices services = new RecordingServices();
        LbzAlarmObjectInstance alarm = createAlarm(services, 0x00);
        PlayableEntity player = playerAt(0x1000, 0x0500);

        alarm.onTouchResponse(player, specialTouch(), 0);
        alarm.update(0, player);

        assertEquals(0x81, alarm.alarmTimerForTesting());
        assertEquals(1, services.children.size());
        ObjectSpawn flybot = services.children.get(0).getSpawn();
        assertEquals(Sonic3kObjectIds.FLYBOT_767, flybot.objectId());
        assertEquals(0x10C0, flybot.x());
        assertEquals(0x04A0, flybot.y());
        assertEquals(0, alarm.getCollisionFlags(), "ROM omits Add_SpriteToCollisionResponseList while timer is active");

        alarm.update(1, player);

        assertEquals(List.of(Sonic3kSfx.ALARM.id), services.playedSfx);

        for (int frame = 2; frame <= 33; frame++) {
            alarm.update(frame, player);
        }

        assertEquals(List.of(Sonic3kSfx.ALARM.id, Sonic3kSfx.ALARM.id), services.playedSfx);
    }

    @Test
    void delayedSubtypeSpawnsFlybotWhenTimerReachesFortyOne() {
        RecordingServices services = new RecordingServices();
        LbzAlarmObjectInstance alarm = createAlarm(services, 0x03);
        PlayableEntity player = playerAt(0x1000, 0x0500);

        alarm.onTouchResponse(player, specialTouch(), 0);
        alarm.update(0, player);

        assertEquals(0, services.children.size());

        for (int frame = 1; frame <= 64; frame++) {
            alarm.update(frame, player);
        }

        assertEquals(0x41, alarm.alarmTimerForTesting());
        assertEquals(1, services.children.size());
        ObjectSpawn flybot = services.children.get(0).getSpawn();
        assertEquals(0x0F40, flybot.x());
        assertEquals(0x04A0, flybot.y());
    }

    private static LbzAlarmObjectInstance createAlarm(RecordingServices services, int subtype) {
        LbzAlarmObjectInstance alarm = new LbzAlarmObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_ALARM, subtype, 0, false, 0));
        alarm.setServices(services);
        return alarm;
    }

    private static TouchResponseResult specialTouch() {
        return new TouchResponseResult(0x17, 0x10, 0x10,
                com.openggf.level.objects.TouchCategory.SPECIAL, 0);
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ObjectManager objectManager;
        private final List<AbstractObjectInstance> children = new ArrayList<>();
        private final List<Integer> playedSfx = new ArrayList<>();

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                children.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
