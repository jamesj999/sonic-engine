package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
public class TestCnzTraversalRegistry {

    @Test
    public void s3klTraversalSlotsResolveToConcreteCarnivalNightObjects() throws Exception {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        assertObjectType(registry.create(new ObjectSpawn(0x1200, 0x0580,
                        0x41, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzBalloonInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x1600, 0x0680,
                        0x42, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzCannonInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x1800, 0x05A0,
                        0x43, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzRisingPlatformInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x1A00, 0x05C0,
                        0x44, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzTrapDoorInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x1C00, 0x05E0,
                        0x46, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzHoverFanInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x1E00, 0x0600,
                        0x47, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzCylinderInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x2000, 0x0620,
                        0x48, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance");
        assertObjectType(registry.create(new ObjectSpawn(0x2200, 0x0640,
                        0x4C, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzSpiralTubeInstance");
    }

    @Test
    public void bumperSlotResolvesToCarnivalNightBumperInCarnivalNightContext() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertObjectType(registry.create(new ObjectSpawn(0x0340, 0x06BC,
                        0x4A, 0, 0, false, 0)),
                "com.openggf.game.sonic3k.objects.CnzBumperObjectInstance");
    }

    private static void assertObjectType(ObjectInstance instance, String expectedClassName)
            throws ClassNotFoundException {
        Class<?> expected = Class.forName(expectedClassName);
        assertTrue(expected.isInstance(instance),
                "Expected " + expectedClassName + " but found "
                        + (instance != null ? instance.getClass().getName() : "null"));
        assertEquals(expected, instance.getClass());
    }

    @Test
    public void visibleTraversalObjectsUseTheirRegisteredRendererInsteadOfRenderingNothing() {
        assertVisibleObjectRendersExpectedInitialFrame(new CnzBalloonInstance(new ObjectSpawn(0x1200, 0x0580,
                        0x41, 3, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_BALLOON, 15, 0x1200, 0x0580);
        assertCannonRendersComposedInitialFrame(new CnzCannonInstance(new ObjectSpawn(0x1600, 0x0680,
                        0x42, 0, 0, false, 0)),
                0x1600, 0x0680);
        assertVisibleObjectRendersExpectedInitialFrame(new CnzRisingPlatformInstance(new ObjectSpawn(0x1800, 0x05A0,
                        0x43, 0, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM, 0, 0x1800, 0x05A0);
        assertVisibleObjectRendersExpectedInitialFrame(new CnzTrapDoorInstance(new ObjectSpawn(0x1A00, 0x05C0,
                        0x44, 0, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_TRAP_DOOR, 0, 0x1A00, 0x05C0);
        assertVisibleObjectRendersExpectedInitialFrame(new CnzHoverFanInstance(new ObjectSpawn(0x1C00, 0x05E0,
                        0x46, 0x90, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_HOVER_FAN, 1, 0x1C00, 0x05E0);
        // ROM parity: the cylinder's visible mapping frame starts at 0 and only
        // advances through the shared 4-frame timer loop after initialization.
        assertVisibleObjectRendersExpectedInitialFrame(new CnzCylinderInstance(new ObjectSpawn(0x1E00, 0x0600,
                        0x47, 0, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_CYLINDER, 0, 0x1E00, 0x0600);
        assertVisibleObjectRendersExpectedInitialFrame(new CnzBumperObjectInstance(new ObjectSpawn(0x0340, 0x06BC,
                        0x4A, 0, 0, false, 0)),
                Sonic3kObjectArtKeys.CNZ_BUMPER, 0, 0x0340, 0x06BC);
    }

    @Test
    public void cnzCylinderVisibleCadenceAdvancesFromAZeroInitializedTimer() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(0x1E00, 0x0600,
                0x47, 0, 0, false, 0));
        setPrivateIntField(cylinder, "animFrameTimer", 0);

        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_CYLINDER)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        ((com.openggf.level.objects.AbstractObjectInstance) cylinder).setServices(
                new TestObjectServices().withLevelManager(levelManager));

        assertCylinderRenderedFrame(cylinder, renderer, renderManager, 0);

        int[] expectedFrames = {1, 1, 2, 2, 3, 3, 0, 0};
        for (int frameIndex : expectedFrames) {
            cylinder.update(0, null);
            assertCylinderRenderedFrame(cylinder, renderer, renderManager, frameIndex);
        }
    }

    private static void assertVisibleObjectRendersExpectedInitialFrame(ObjectInstance instance,
            String artKey, int frame, int x, int y) {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(artKey)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        ((com.openggf.level.objects.AbstractObjectInstance) instance).setServices(
                new TestObjectServices().withLevelManager(levelManager));
        instance.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(artKey);
        verify(renderer).drawFrameIndex(frame, x, y, false, false);
    }

    private static void assertCannonRendersComposedInitialFrame(CnzCannonInstance instance, int x, int y) {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_CANNON)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        instance.setServices(new TestObjectServices().withLevelManager(levelManager));
        instance.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.CNZ_CANNON);
        verify(renderer).drawFrameIndex(4, x, y, false, false);
    }

    private static void assertCylinderRenderedFrame(CnzCylinderInstance cylinder,
            PatternSpriteRenderer renderer, ObjectRenderManager renderManager,
            int frame) {
        cylinder.appendRenderCommands(new ArrayList<>());
        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.CNZ_CYLINDER);
        verify(renderer).drawFrameIndex(org.mockito.ArgumentMatchers.eq(frame),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false));
        org.mockito.Mockito.clearInvocations(renderManager, renderer);
    }

    private static void setPrivateIntField(Object target, String fieldName, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
