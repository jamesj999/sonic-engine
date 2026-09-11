package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class TestMGZHeadTriggerObjectInstance {

    private static final TouchResponseResult ENEMY_HIT =
            new TouchResponseResult(0x17, 0, 0, TouchCategory.ENEMY);

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        Sonic3kLevelTriggerManager.reset();
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        Sonic3kLevelTriggerManager.reset();
        SessionManager.clear();
    }

    @Test
    void completedTriggerRespawnsOnTriggeredFrame() throws Exception {
        Sonic3kLevelTriggerManager.setBit(3, 0);

        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x03, 0, false, 0));

        assertEquals(0, readMappingFrame(head));
        assertEquals(0, head.getCollisionFlags());
    }

    @Test
    void nonFinalHitRearmsBlinkCycleWhichLaterSpawnsProjectile() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x02, 0, false, 0));
        head.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x140, (short) 0x100);

        runFrames(head, player, 0, 80);

        long projectileCount = countProjectiles(services);
        assertEquals(1, projectileCount);
        assertEquals(1, readMappingFrame(head), "After the first spit the exposed red gem should remain visible");

        head.onPlayerAttack(player, ENEMY_HIT);
        head.update(80, player);

        projectileCount = countProjectiles(services);
        assertEquals(1, projectileCount, "ROM does not fire the follow-up arrow immediately on hit");
        assertFalse(Sonic3kLevelTriggerManager.testAny(2));
        assertEquals(2, head.getCollisionProperty());
        assertNotEquals(0, readMappingFrame(head));

        runFrames(head, player, 81, 160);

        projectileCount = countProjectiles(services);
        assertEquals(2, projectileCount, "Follow-up arrow should come from the re-armed blink/spit animation");
    }

    @Test
    void nonFinalHitRecoveryCountsWhileBlinkRestartAdvances() {
        RecordingServices services = new RecordingServices();
        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x02, 0, false, 0));
        head.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x140, (short) 0x100);

        head.onPlayerAttack(player, ENEMY_HIT);
        head.update(0, player);
        for (int frame = 1; frame < 60; frame++) {
            head.update(frame, player);
        }
        assertEquals(0, head.getCollisionFlags(),
                "The head remains intangible through the 59th recovery tick");

        head.update(60, player);

        assertEquals(0x17, head.getCollisionFlags(),
                "The independent 60-frame recovery must re-arm collision on time");
    }

    @Test
    void thirdHitSetsLevelTriggerAndLeavesTriggeredFrameVisible() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x02, 0, false, 0));
        head.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x140, (short) 0x100);

        head.onPlayerAttack(player, ENEMY_HIT);
        head.update(0, player);
        assertFalse(Sonic3kLevelTriggerManager.testAny(2));
        runFrames(head, player, 1, 80);

        head.onPlayerAttack(player, ENEMY_HIT);
        head.update(80, player);
        assertFalse(Sonic3kLevelTriggerManager.testAny(2));
        runFrames(head, player, 81, 160);

        long projectileCount = countProjectiles(services);
        assertEquals(2, projectileCount, "Only non-final hits should emit arrows");

        head.onPlayerAttack(player, ENEMY_HIT);
        head.update(160, player);

        assertTrue(Sonic3kLevelTriggerManager.testAny(2));
        assertEquals(0, head.getCollisionFlags());
        assertEquals(0, head.getCollisionProperty());
        assertEquals(0, readMappingFrame(head));
        projectileCount = countProjectiles(services);
        assertEquals(2, projectileCount, "Only non-final hits should emit arrows");
    }

    @Test
    void watchAnimationSpawnsProjectileFromRomOffsets() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x00, 0, false, 0));
        head.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x140, (short) 0x100);

        for (int frame = 0; frame < 80; frame++) {
            head.update(frame, player);
        }

        AbstractObjectInstance projectile = services.spawnedChildren.stream()
                .filter(MGZHeadTriggerProjectileInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a projectile child"));

        assertEquals(0x190, projectile.getX());
        assertEquals(0x120, projectile.getY());
        assertEquals(1, readMappingFrame(head));

        runFrames(head, player, 80, 220);
        assertEquals(2, countProjectiles(services),
                "The idle watch-window check should re-arm the native 82-frame spit cadence");
        assertEquals(1, readMappingFrame(head),
                "The second cycle should reach the exposed red gem frame");
    }

    @Test
    void flippedHeadMirrorsProjectileSpawnOffset() {
        RecordingServices services = new RecordingServices();
        MGZHeadTriggerObjectInstance head = new MGZHeadTriggerObjectInstance(
                new ObjectSpawn(0x180, 0x100, Sonic3kObjectIds.MGZ_HEAD_TRIGGER, 0x00, 0x01, false, 0));
        head.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1C0, (short) 0x100);

        for (int frame = 0; frame < 80; frame++) {
            head.update(frame, player);
        }

        AbstractObjectInstance projectile = services.spawnedChildren.stream()
                .filter(MGZHeadTriggerProjectileInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a projectile child"));

        assertEquals(0x170, projectile.getX());
        assertEquals(0x120, projectile.getY());
    }

    @Test
    void projectilePublishesItsPostMoveTouchCoordinate() {
        MGZHeadTriggerProjectileInstance projectile =
                new MGZHeadTriggerProjectileInstance(0x180, 0x120, 0x400, false);

        assertTrue(projectile.usesCurrentTouchResponseState(),
                "loc_34518 moves before Add_SpriteToCollisionResponseList");
    }

    private static int readMappingFrame(MGZHeadTriggerObjectInstance head) throws Exception {
        Field field = MGZHeadTriggerObjectInstance.class.getDeclaredField("mappingFrame");
        field.setAccessible(true);
        return field.getInt(head);
    }

    private static void runFrames(MGZHeadTriggerObjectInstance head, TestablePlayableSprite player,
            int startInclusive, int endExclusive) {
        for (int frame = startInclusive; frame < endExclusive; frame++) {
            head.update(frame, player);
        }
    }

    private static long countProjectiles(RecordingServices services) {
        return services.spawnedChildren.stream()
                .filter(MGZHeadTriggerProjectileInstance.class::isInstance)
                .count();
    }

    private static final class RecordingServices extends StubObjectServices {
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }
}
