package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelData;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRibotBadnikInstance {
    @BeforeEach
    void resetRuntimeState() {
        SessionManager.clear();
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @AfterEach
    void clearRuntimeState() {
        SessionManager.clear();
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void registryCreatesRibotAndProfileMarksS3klSlotOnly() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.RIBOT, 0, 0, false, 0));

        assertEquals("RibotBadnikInstance", instance.getClass().getSimpleName());

        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig lbz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_LAUNCH_BASE_1)
                .findFirst()
                .orElseThrow();
        LevelConfig mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst()
                .orElseThrow();

        assertTrue(profile.getImplementedIds(lbz1).contains(Sonic3kObjectIds.RIBOT));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.RIBOT));
    }

    @Test
    void subtypeZeroInitializesTwoDownwardLegChildrenAndLeavesParentStill() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createRibot(0);
        ObjectManager objectManager = installObjectManager(ribot);

        ribot.update(0, playerAt(0, 0));

        assertEquals(0x0200, ribot.getX(), "routine 0 spawns children, then returns");
        assertEquals(0x0100, ribot.getY());
        List<ObjectInstance> children = capturedChildren(objectManager, 2);
        assertEquals(0x01F4, children.get(0).getX());
        assertEquals(0x010C, children.get(0).getY());
        assertEquals(0x020C, children.get(1).getX());
        assertEquals(0x010C, children.get(1).getY());
    }

    @Test
    void subtypeTwoUsesHorizontalLegLayout() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createRibot(2);
        ObjectManager objectManager = installObjectManager(ribot);

        ribot.update(0, playerAt(0, 0));

        List<ObjectInstance> children = capturedChildren(objectManager, 2);
        assertEquals(0x01E8, children.get(0).getX());
        assertEquals(0x0100, children.get(0).getY());
        assertEquals(0x0218, children.get(1).getX());
        assertEquals(0x0100, children.get(1).getY());
    }

    @Test
    void subtypeFourUsesSingleUpperChildAndUpperAnimationFrames() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createRibot(4);
        ObjectManager objectManager = installObjectManager(ribot);

        ribot.update(0, playerAt(0, 0));

        List<ObjectInstance> children = capturedChildren(objectManager, 1);
        assertEquals(0x0200, children.get(0).getX());
        assertEquals(0x00F0, children.get(0).getY());
        assertEquals(3, readInt(ribot, "mappingFrame"),
                "byte_8C62C starts with parent frame 3 for subtype >= 4");
    }

    @Test
    void subtypeFourHeadSphereUsesRomCircularRadius() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createRibot(4);
        ObjectManager objectManager = installObjectManager(ribot);
        ribot.update(0, playerAt(0, 0));
        ObjectInstance headSphere = capturedChildren(objectManager, 1).get(0);
        assertTrue(headSphere instanceof AbstractObjectInstance);
        assertFalse(headSphere.usesCurrentTouchResponseState(),
                "the circular head retains its prior player-slot touch coordinate");
        installObjectManager((AbstractObjectInstance) headSphere);

        headSphere.update(1, playerAt(0, 0));

        assertTrue(Math.abs(headSphere.getY() - 0x00F0) >= 0x30,
                "loc_8C41E calls MoveSprite_CircularSimpleOffset with d2=2, a roughly 64px orbit radius");
    }

    @Test
    void firstActiveParentFrameConsumesChildTriggerAndOpensFirstGate() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createInitializedRibot(0);

        ribot.update(1, playerAt(0, 0));

        assertTrue(readBoolean(ribot, "childGateA"));
        assertFalse(readBoolean(ribot, "childGateB"));
        assertFalse(readBoolean(ribot, "childTrigger"));
        assertEquals(0, readInt(ribot, "mappingFrame"));
    }

    @Test
    void subtypeZeroHurtSphereExtendsDownAfterGateOpens() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance ribot = createRibot(0);
        ObjectManager objectManager = installObjectManager(ribot);
        ribot.update(0, playerAt(0, 0));
        ObjectInstance rightSphere = capturedChildren(objectManager, 2).get(1);
        assertTrue(rightSphere instanceof AbstractObjectInstance);
        assertTrue(rightSphere.usesCurrentTouchResponseState(),
                "Ribot publishes its hurt sphere after movement, so touch uses the post-move coordinates");
        installObjectManager((AbstractObjectInstance) rightSphere);

        ribot.update(1, playerAt(0, 0));
        rightSphere.update(1, playerAt(0, 0));
        rightSphere.update(2, playerAt(0, 0));
        rightSphere.update(3, playerAt(0, 0));
        rightSphere.update(4, playerAt(0, 0));
        rightSphere.update(5, playerAt(0, 0));

        assertEquals(0x010D, rightSphere.getY(),
                "loc_8C436 uses MoveSprite's $38 gravity while the hurt sphere extends");
    }

    private static AbstractObjectInstance createRibot(int subtype) {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.RIBOT, subtype, 0, false, 0));
        assertTrue(instance instanceof AbstractObjectInstance,
                "Ribot registry entry should create an object instance");
        return (AbstractObjectInstance) instance;
    }

    private static AbstractObjectInstance createInitializedRibot(int subtype) {
        AbstractObjectInstance ribot = createRibot(subtype);
        installObjectManager(ribot);
        ribot.update(0, playerAt(0, 0));
        return ribot;
    }

    private static ObjectManager installObjectManager(AbstractObjectInstance object) {
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.objectManager()).thenReturn(objectManager);
        object.setServices(services);
        if (object instanceof RibotBadnikInstance) {
            object.refreshPostCameraRenderState();
            object.update(-1, playerAt(0, 0));
        }
        return objectManager;
    }

    private static List<ObjectInstance> capturedChildren(ObjectManager objectManager, int count) {
        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(count)).addDynamicObjectAfterCurrent(childCaptor.capture());
        return childCaptor.getAllValues();
    }

    private static AbstractPlayableSprite playerAt(int x, int y) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn(Short.valueOf((short) x));
        when(player.getCentreY()).thenReturn(Short.valueOf((short) y));
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
