package com.openggf.game.sonic3k.objects;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact defeat-family oracle: sonic3k.asm:176659-176890, 147570-147592, 187282-187338. */
@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestFbzMinibossDefeatChildren {
    @BeforeEach
    void headless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x4000, 0x1000, 0);
    }

    @AfterEach
    void reset() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void subtypeZeroExplosionControllerAttemptsImmediatelyThenEveryThreeUpdates() {
        ControllerHarness h = new ControllerHarness(10, 0x12345678L, false);

        h.step(0);
        assertEquals(1, h.sfxCount.get(), "zeroed Obj_Wait word fires on the controller's init tick");
        S3kBossExplosionChild first = h.manager.activeObjectsOfType(S3kBossExplosionChild.class).getFirst();
        GameRng oracle = new GameRng(GameRng.Flavour.S3K, 0x12345678L);
        int random = oracle.nextRaw();
        assertEquals(0x1000 + (random & 0x3F) - 0x20, first.getSpawn().x());
        assertEquals(0x0800 + ((random >>> 16) & 0x3F) - 0x20, first.getSpawn().y(),
                "Random_Number SWAP makes Y consume the upper word, not bits 8-13");

        h.step(1);
        h.step(2);
        assertEquals(1, h.sfxCount.get());
        h.step(3);
        assertEquals(2, h.sfxCount.get());

        for (int frame = 4; frame <= 90; frame++) h.step(frame);
        assertEquals(31, h.sfxCount.get(), "$20 timer deletes at zero, yielding exactly 31 attempts");
        assertFalse(h.controller.isDestroyed());
        h.step(91);
        h.step(92);
        assertFalse(h.controller.isDestroyed());
        h.step(93);
        assertTrue(h.controller.isDestroyed());
    }

    @Test
    void failedExplosionAllocationIsOneShotAndConsumesNeitherRngNorSfx() {
        ControllerHarness h = new ControllerHarness(127, 0x13579BDFL, true);
        long seed = h.rng.getSeed();

        h.step(0);

        assertEquals(seed, h.rng.getSeed());
        assertEquals(0, h.sfxCount.get());
        assertEquals(31, intField(h.controller, "remaining"),
                "Obj_BossExpControl1 decrements its timer before the failed CreateChild6 call");
        assertTrue(h.manager.activeObjectsOfType(S3kBossExplosionChild.class).isEmpty());
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void explosionControllerGenericRewindRestoresNativeCountdownPhase() {
        ControllerHarness h = new ControllerHarness(10, 0x2468ACE0L, false);
        h.step(0);
        h.step(1);
        PerObjectRewindSnapshot snapshot = h.controller.captureRewindState();
        h.step(2);
        h.step(3);
        assertEquals(2, h.sfxCount.get());

        h.controller.restoreRewindState(snapshot);
        int before = h.sfxCount.get();
        h.controller.update(4, null);
        assertEquals(before, h.sfxCount.get());
        h.controller.update(5, null);
        assertEquals(before + 1, h.sfxCount.get());
    }

    @Test
    void prisonChildUsesRealFbzCapsuleFrameFullSolidAndCoarseCull() {
        FbzMinibossInstance boss = boss(0x2F00, 0x05E0);
        FbzMinibossPrisonChild prison = new FbzMinibossPrisonChild(boss);
        prison.setServices(new StubObjectServices());

        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, prison);
        assertEquals(new SolidObjectParams(0x23, 0x20, 0x1C), solid.getSolidParams());
        assertTrue(solid.skipsCpuSidekickWhenRenderFlagOffScreen());
        assertTrue(solid.usesInclusiveRightEdge());
        assertEquals(1, intField(prison, "mappingFrame"));
        assertEquals(4, prison.getPriorityBucket());
        assertFalse(prison.isHighPriority());

        AbstractObjectInstance.updateCameraBounds(0x4000, 0, 0x4140, 224, 0);
        prison.update(0, null);
        assertTrue(prison.isDestroyed(), "loc_6F764 deletes outside the unsigned coarse-$280 window");
    }

    @Test
    void fiveAnimalsUseExactOffsetsDelaysVelocitiesAndLightGravity() {
        int[] xOffsets = {0, 0x10, -0x10, 0x1C, -0x1C};
        int[] delays = {0, 8, 16, 24, 32};
        int[] xVelocities = {0x200, 0x200, -0x200, 0x200, -0x200};
        int[] yVelocities = {-0x380, -0x300, -0x280, -0x200, -0x380};
        for (int role = 0; role < 5; role++) {
            FbzMinibossAnimalChild animal = new FbzMinibossAnimalChild(boss(0x1000, 0x0800), role);
            animal.setServices(new StubObjectServices());
            int startX = 0x1000 + xOffsets[role];
            int startY = 0x0800 - 4;
            assertEquals(startX, animal.getX());
            assertEquals(startY, animal.getY());
            assertEquals(5, animal.getPriorityBucket());
            assertTrue(animal.isHighPriority());
            assertTrue(skipsSameFrameUpdate(animal),
                    "CreateChild1_Normal executes loc_89CE2 init in the allocation frame");

            for (int update = 0; update <= delays[role]; update++) animal.update(update, null);
            assertEquals(startX, animal.getX());
            assertEquals(startY, animal.getY(), "delay N activates only after N+1 decrements");
            assertEquals(1, animal.getPriorityBucket());

            animal.update(delays[role] + 1, null);
            int expectedX = startX + (xVelocities[role] >> 8);
            int expectedY = startY + Math.floorDiv(yVelocities[role], 0x100);
            assertEquals(expectedX, animal.getX());
            assertEquals(expectedY, animal.getY());
            assertEquals(yVelocities[role] + 0x20, intField(animal, "yVelocity"));
        }
    }

    @Test
    void activeAnimalsUseVintBitThreeFramesAndExactBounceVelocity() {
        FbzMinibossAnimalChild frameOne = new FbzMinibossAnimalChild(boss(0x1000, 0x0800), 0);
        frameOne.setServices(new StubObjectServices());
        frameOne.update(0, null);
        frameOne.update(0, null);
        assertEquals(1, intField(frameOne, "mappingFrame"));
        assertEquals(-0x380, intField(frameOne, "bounceYVelocity"));

        FbzMinibossAnimalChild frameZero = new FbzMinibossAnimalChild(boss(0x1000, 0x0800), 0);
        frameZero.setServices(new StubObjectServices());
        frameZero.update(0, null);
        frameZero.update(8, null);
        assertEquals(0, intField(frameZero, "mappingFrame"));
    }

    @Test
    void fiveFragmentsUseCompactFilteredFramesIndexedVelocitiesAndAlternatingFlicker() {
        int[] xOffsets = {0, -0x10, 0x10, -0x18, 0x18};
        int[][] velocities = {
                {0x100, -0x100}, {-0x200, -0x200}, {0x200, -0x200},
                {-0x300, -0x200}, {0x300, -0x200}
        };
        for (int role = 0; role < 5; role++) {
            FbzMinibossFragmentChild fragment = new FbzMinibossFragmentChild(boss(0x1000, 0x0800), role);
            fragment.setServices(new StubObjectServices());
            int startX = 0x1000 + xOffsets[role];
            int startY = 0x0800 - 8;
            assertTrue(skipsSameFrameUpdate(fragment));
            assertEquals(role, intField(fragment, "mappingFrame"));
            assertEquals(3, fragment.getPriorityBucket());
            assertTrue(fragment.isHighPriority());

            fragment.update(0, null);
            assertEquals(startX + (velocities[role][0] >> 8), fragment.getX());
            assertEquals(startY + (velocities[role][1] >> 8), fragment.getY());
            assertFalse(booleanField(fragment, "visibleThisUpdate"));
            fragment.update(1, null);
            assertTrue(booleanField(fragment, "visibleThisUpdate"));
            assertEquals(velocities[role][1], intField(fragment, "yVelocity"),
                    "Obj_FlickerMove has no gravity");
        }
    }

    @Test
    void fiveFragmentsRenderCompactGenericCapsuleFramesThroughDedicatedActOneKey() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderer.isReady()).thenReturn(true);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.FBZ1_MINIBOSS_FRAGMENTS)).thenReturn(renderer);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectRenderManager renderManager() { return renderManager; }
        };

        for (int role = 0; role < 5; role++) {
            FbzMinibossFragmentChild fragment = new FbzMinibossFragmentChild(boss(0x1000, 0x0800), role);
            fragment.setServices(services);
            fragment.appendRenderCommands(new java.util.ArrayList<>());
        }

        ArgumentCaptor<Integer> frames = ArgumentCaptor.forClass(Integer.class);
        verify(renderer, times(5)).drawFrameIndex(frames.capture(), anyInt(), anyInt(),
                eq(false), eq(false));
        assertEquals(List.of(0, 1, 2, 3, 4), frames.getAllValues());
        verify(renderManager, times(5)).getRenderer(Sonic3kObjectArtKeys.FBZ1_MINIBOSS_FRAGMENTS);
    }

    @Test
    void fragmentsUseObjFlickerMoveUnsignedXyCull() {
        Camera camera = camera(0, 0, 320, 224);
        FbzMinibossFragmentChild fragment = new FbzMinibossFragmentChild(boss(0x1000, 0x0800), 0);
        fragment.setServices(new StubObjectServices() {
            @Override public Camera camera() { return camera; }
        });
        fragment.update(0, null);
        assertTrue(fragment.isDestroyed(), "Obj_FlickerMove rejects y-camera+$80 above unsigned $200");
    }

    private static FbzMinibossInstance boss(int x, int y) {
        return new FbzMinibossInstance(new ObjectSpawn(x, y, 0xAA, 0, 0, false, 0));
    }

    private static int intField(Object target, String fieldName) {
        try {
            return fieldInHierarchy(target, fieldName).getInt(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("missing exact-state field " + fieldName, e);
        }
    }

    private static boolean booleanField(Object target, String fieldName) {
        try {
            return fieldInHierarchy(target, fieldName).getBoolean(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("missing exact-state field " + fieldName, e);
        }
    }

    private static Field fieldInHierarchy(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Shared object-family state can move into a superclass without changing behavior.
            }
        }
        throw new AssertionError("missing exact-state field " + fieldName);
    }

    private static boolean skipsSameFrameUpdate(AbstractObjectInstance target) {
        try {
            Method method = target.getClass().getDeclaredMethod("skipsSameFrameUpdateAfterSpawn");
            method.setAccessible(true);
            return (boolean) method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("child must declare exact same-frame allocation lifecycle", e);
        }
    }

    private static Camera camera(int x, int y, int width, int height) {
        return new Camera() {
            @Override public short getX() { return (short) x; }
            @Override public short getY() { return (short) y; }
            @Override public short getWidth() { return (short) width; }
            @Override public short getHeight() { return (short) height; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static final class ControllerHarness {
        private final AtomicInteger sfxCount = new AtomicInteger();
        private final GameRng rng;
        private final ObjectManager manager;
        private final FbzMinibossExplosionController controller;

        private ControllerHarness(int controllerSlot, long seed, boolean exhaustSlots) {
            rng = new GameRng(GameRng.Flavour.S3K, seed);
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = camera(0, 0, 0x4000, 0x1000);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public GameRng rng() { return rng; }
                @Override public void playSfx(int soundId) { sfxCount.incrementAndGet(); }
            };
            manager = new ObjectManager(List.of(), new Sonic3kObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = manager;
            manager.reset(0);
            controller = new FbzMinibossExplosionController(boss(0x1000, 0x0800));
            manager.addDynamicObjectAtSlot(controller, controllerSlot);
            if (exhaustSlots) manager.reserveAllButNFreeSlots(0);
        }

        private void step(int frame) {
            manager.update(0, (PlayableEntity) null, List.of(), frame, false);
        }
    }
}
