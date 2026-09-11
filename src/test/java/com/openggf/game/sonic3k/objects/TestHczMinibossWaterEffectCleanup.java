package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestHczMinibossWaterEffectCleanup {

    {
        TestEnvironment.activeGameplayMode();
    }

    @Test
    void defeatedParentCleanupSetsTrackedNativePlayersAirborne() throws Exception {
        Camera camera = GameServices.camera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x36E9, (short) 0x06F0);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x36E9, (short) 0x06F0);
        camera.setFocusedSprite(sonic);
        ObjectServices services = new NativePlayerServices(camera, sonic, tails);
        HczMinibossInstance boss = buildBoss(services);
        setBoolean(boss, "vortexTrackedP1", true);
        setBoolean(boss, "vortexTrackedP2", true);
        sonic.setAir(false);
        tails.setAir(false);
        sonic.setObjectControlled(true);
        tails.setObjectControlled(true);

        boss.releaseTrackedVortexPlayersOnWaterEffectDelete();

        assertTrue(sonic.getAir());
        assertTrue(tails.getAir());
        assertFalse(sonic.isObjectControlled());
        assertFalse(tails.isObjectControlled());
    }

    @Test
    void firstVortexContactUsesNativeBit0ObjectControlPolicy() throws Exception {
        Camera camera = GameServices.camera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x3720, (short) 0x0800);
        camera.setFocusedSprite(sonic);
        HczMinibossInstance boss = buildBoss(new NativePlayerServices(camera, sonic, sonic));

        Method applyVortexPullTo = HczMinibossInstance.class
                .getDeclaredMethod("applyVortexPullTo", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        applyVortexPullTo.setAccessible(true);
        applyVortexPullTo.invoke(boss, sonic);

        assertTrue(sonic.isObjectControlled(), "sub_6AA00 writes object_control=1");
        assertTrue(sonic.isObjectControlAllowsCpu(),
                "bit-0 object control must keep the CPU sidekick controller active");
        assertTrue(sonic.isObjectControlSuppressesMovement(),
                "the vortex owns player movement while object_control bit 0 is set");
        assertFalse(sonic.isTouchResponseSuppressedByObjectControl(),
                "object_control=1 is not the signed bit-7 touch-response gate");
        assertEquals(0, sonic.getXSpeed());
        assertEquals(0, sonic.getYSpeed());
        assertEquals(0, sonic.getGSpeed());
    }

    @Test
    void endSignControlKeepsNativeInitialWaitEntryBoundary() throws Exception {
        Camera camera = GameServices.camera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x3720, (short) 0x06F0);
        camera.setFocusedSprite(sonic);
        NativePlayerServices services = new NativePlayerServices(camera, sonic, sonic);
        HczMinibossInstance boss = buildBoss(services);
        setInt(boss, "defeatHandoffTimer", -1);
        setBoolean(boss, "defeatHandoffStarted", false);
        setObject(boss, "defeatExplosionController",
                new S3kBossExplosionController(0x3720, 0x068C, 0));

        Method updateDefeated = HczMinibossInstance.class.getDeclaredMethod("updateDefeated");
        updateDefeated.setAccessible(true);
        updateDefeated.invoke(boss);

        S3kBossDefeatSignpostFlow flow = services.spawnedChildren.stream()
                .filter(S3kBossDefeatSignpostFlow.class::isInstance)
                .map(S3kBossDefeatSignpostFlow.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0x77, flow.waitTimerAfterInitialization(),
                "Obj_EndSignControl installs its $77 wait and returns without consuming an entry");
    }

    private static HczMinibossInstance buildBoss(ObjectServices services) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            HczMinibossInstance boss = new HczMinibossInstance(
                    new ObjectSpawn(0x3720, 0x068C, 0x99, 0, 0, false, 0));
            boss.setServices(services);
            return boss;
        } finally {
            context.remove();
        }
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        Field field = HczMinibossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field field = HczMinibossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setObject(Object target, String name, Object value) throws Exception {
        Field field = HczMinibossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }

    private static final class NativePlayerServices extends TestObjectServices {
        private final Camera camera;
        private final PlayableEntity main;
        private final PlayableEntity sidekick;
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private final WaterSystem waterSystem = new WaterSystem();

        private NativePlayerServices(Camera camera, PlayableEntity main, PlayableEntity sidekick) {
            this.camera = camera;
            this.main = main;
            this.sidekick = sidekick;
            this.objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> List.of(sidekick));
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public WaterSystem waterSystem() {
            return waterSystem;
        }
    }
}
