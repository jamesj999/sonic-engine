package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.LevelData;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestOrbinautBadnikInstance {

    @Test
    void registryCreatesOrbinautAndProfileMarksS3klSlotOnly() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ORBINAUT, 0, 0, false, 0));

        assertEquals("OrbinautBadnikInstance", instance.getClass().getSimpleName());

        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig lbz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_LAUNCH_BASE_1)
                .findFirst()
                .orElseThrow();
        LevelConfig mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst()
                .orElseThrow();

        assertTrue(profile.getImplementedIds(lbz1).contains(Sonic3kObjectIds.ORBINAUT));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.ORBINAUT));
    }

    @Test
    void firstActiveFrameSpawnsFourOrbitingOrbsWithoutMovingBody() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createOrbinaut();
        AbstractPlayableSprite player = playerAt(0x0180, 0x0100, false, 0, 0);
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.objectManager()).thenReturn(objectManager);
        orbinaut.setServices(services);

        orbinaut.update(0, player);

        assertEquals(0x0200, orbinaut.getX(), "routine 0 sets attributes and children, then returns");
        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(childCaptor.capture());
        List<ObjectInstance> children = childCaptor.getAllValues();
        assertTrue(children.stream().allMatch(child ->
                child.getClass().getSimpleName().equals("OrbinautOrbInstance")));
        assertEquals(0x0200, children.get(0).getX());
        assertEquals(0x0110, children.get(0).getY());
        assertEquals(0x0210, children.get(1).getX());
        assertEquals(0x0100, children.get(1).getY());
        assertEquals(0x0200, children.get(2).getX());
        assertEquals(0x00F0, children.get(2).getY());
        assertEquals(0x01F0, children.get(3).getX());
        assertEquals(0x0100, children.get(3).getY());
    }

    @Test
    void stationaryGroundedPlayerSetsLeftVelocityButDoesNotMove() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createInitializedOrbinaut();
        AbstractPlayableSprite player = playerAt(0x0180, 0x0100, false, 0, 0);

        orbinaut.update(1, player);

        assertEquals(0x0200, orbinaut.getX(), "sub_8C6D4 returns zero for stationary grounded P1");
        assertEquals(-0x80, readInt(orbinaut, "xVelocity"));
    }

    @Test
    void airbornePlayerSetsRightVelocityButDoesNotMove() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createInitializedOrbinaut();
        AbstractPlayableSprite player = playerAt(0x0280, 0x0100, true, 0x100, 0);

        orbinaut.update(1, player);

        assertEquals(0x0200, orbinaut.getX());
        assertEquals(0x80, readInt(orbinaut, "xVelocity"));
        assertFalse(readBoolean(orbinaut, "facingLeft"));
    }

    @Test
    void groundedMovingPlayerOnLeftLetsOrbinautMoveLeft() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createInitializedOrbinaut();
        AbstractPlayableSprite player = playerAt(0x0180, 0x0100, false, -0x100, 0);

        orbinaut.update(1, player);
        orbinaut.update(2, player);

        assertEquals(0x01FF, orbinaut.getX(), "two MoveSprite2 frames at -$80 advance one pixel left");
        assertEquals(-0x80, readInt(orbinaut, "xVelocity"));
        assertTrue(readBoolean(orbinaut, "facingLeft"));
    }

    @Test
    void groundedMovingPlayerOnRightLetsOrbinautMoveRight() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createInitializedOrbinaut();
        AbstractPlayableSprite player = playerAt(0x0280, 0x0100, false, 0x100, 0);

        orbinaut.update(1, player);
        orbinaut.update(2, player);

        assertEquals(0x0201, orbinaut.getX(), "two MoveSprite2 frames at +$80 advance one pixel");
        assertEquals(0x80, readInt(orbinaut, "xVelocity"));
        assertFalse(readBoolean(orbinaut, "facingLeft"));
    }

    @Test
    void initializedBodyKeepsRunningWhenItBrieflyLeavesViewport() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance orbinaut = createInitializedOrbinaut();
        AbstractObjectInstance.updateCameraBounds(0x1000, 0, 1024, 1024, 0);
        AbstractPlayableSprite player = playerAt(0x0180, 0x0100, false, -0x100, 0);

        orbinaut.update(1, player);
        orbinaut.update(2, player);

        assertEquals(0x01FF, orbinaut.getX(),
                "Sprite_CheckDeleteTouch runs after the restored routine; Obj_WaitOffscreen is not re-entered");
    }

    @Test
    void deferredChildGraphRestoresTheNativeCallbackPhase() {
        AbstractObjectInstance shortWindowChild = createDeferredChild(3);
        AbstractObjectInstance longWindowChild = createDeferredChild(4);

        assertFalse(readBoolean(shortWindowChild, "initialized"),
                "a short deferred window still owes the child setup callback");
        assertTrue(readBoolean(longWindowChild, "initialized"),
                "a longer deferred window resumes at the circular-movement callback");
    }

    @Test
    void placeholderVisibilityCompletesRestoreBeforeMovementBegins() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 224, 0);
        AbstractObjectInstance orbinaut = (AbstractObjectInstance) new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0300, Sonic3kObjectIds.ORBINAUT, 0, 0, false, 0));
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(mock(ObjectManager.class));
        orbinaut.setServices(services);
        AbstractPlayableSprite player = playerAt(0x0180, 0x0300, false, -0x100, 0);

        orbinaut.update(0, player);
        orbinaut.update(1, player);
        assertEquals(0x0200, orbinaut.getX(), "X visibility creates the placeholder without moving the body");

        AbstractObjectInstance.updateCameraBounds(0, 0x0200, 1024, 0x0400, 0);
        orbinaut.update(2, player);
        orbinaut.update(3, player);
        assertEquals(0x0200, orbinaut.getX(), "the restored operation consumes two non-moving passes");

        orbinaut.update(4, player);
        orbinaut.update(5, player);
        assertEquals(0x01FF, orbinaut.getX(), "the resumed routine keeps running after restoration");
    }

    @Test
    void downwardPassLeavesAboveCameraPlaceholderWithoutChildSlots() {
        AbstractObjectInstance.updateCameraBounds(0, 0x0200, 1024, 224, 0);
        AbstractObjectInstance orbinaut = (AbstractObjectInstance) new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ORBINAUT, 0, 0, false, 0));
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        Camera camera = mock(Camera.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.camera()).thenReturn(camera);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0x0200);
        orbinaut.setServices(services);
        AbstractPlayableSprite player = playerAt(0x0180, 0x0280, true, 0, 0x0400);

        orbinaut.update(0, player);
        orbinaut.update(1, player);

        verify(objectManager, never()).addDynamicObjectAfterCurrent(any());
        assertFalse(readBoolean(orbinaut, "initialized"),
                "Obj_WaitOffscreen must retain its placeholder when the camera has passed below it");
    }

    private static AbstractObjectInstance createOrbinaut() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ORBINAUT, 0, 0, false, 0));
        assertTrue(instance instanceof AbstractObjectInstance,
                "Orbinaut registry entry should create an object instance");
        return (AbstractObjectInstance) instance;
    }

    private static AbstractObjectInstance createInitializedOrbinaut() {
        AbstractObjectInstance orbinaut = createOrbinaut();
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(mock(ObjectManager.class));
        orbinaut.setServices(services);
        orbinaut.update(0, playerAt(0x0180, 0x0100, false, 0, 0));
        return orbinaut;
    }

    private static AbstractObjectInstance createDeferredChild(int offscreenUpdates) {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x01F0, 0x0400, 0);
        AbstractObjectInstance orbinaut = createOrbinaut();
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.objectManager()).thenReturn(objectManager);
        orbinaut.setServices(services);
        AbstractPlayableSprite player = playerAt(0x0180, 0x0100, false, -0x100, 0);
        for (int i = 0; i < offscreenUpdates; i++) {
            orbinaut.update(i, player);
        }

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0400, 0x0400, 0);
        orbinaut.update(offscreenUpdates, player);

        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(childCaptor.capture());
        return (AbstractObjectInstance) childCaptor.getAllValues().getFirst();
    }

    private static AbstractPlayableSprite playerAt(int x, int y, boolean air, int xSpeed, int ySpeed) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn(Short.valueOf((short) x));
        when(player.getCentreY()).thenReturn(Short.valueOf((short) y));
        when(player.getAir()).thenReturn(air);
        when(player.getXSpeed()).thenReturn((short) xSpeed);
        when(player.getYSpeed()).thenReturn((short) ySpeed);
        when(player.getDead()).thenReturn(false);
        return player;
    }

    private static int readInt(Object target, String fieldName) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            return field.getInt(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static Field findField(Object target, String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + fieldName + " on " + target.getClass());
    }
}
