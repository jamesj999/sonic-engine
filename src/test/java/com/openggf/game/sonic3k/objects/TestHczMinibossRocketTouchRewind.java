package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestHczMinibossRocketTouchRewind {
    private static final String ROCKET_TOUCH_CLASS =
            "com.openggf.game.sonic3k.objects.HczMinibossInstance$RocketTouchChild";

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void rocketTouchChildrenRestoreThroughGenericRecreateAndRelinkParentSlots() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for HCZ rewind restore");

        HczMinibossInstance parent = installPlacedHczMinibossParent(objectManager);
        assertNotNull(parent, "precondition: HCZ miniboss parent must be installed as a placed active object");
        invokePrivateNoArg(parent, "spawnRocketTouchChildren");

        List<AbstractObjectInstance> capturedChildren = liveRocketChildren(objectManager);
        assertEquals(4, capturedChildren.size(),
                "precondition: HCZ miniboss must expose four rocket touch children before capture");

        int[] capturedSlots = capturedChildren.stream()
                .mapToInt(AbstractObjectInstance::getSlotIndex)
                .toArray();
        int[] capturedRocketIndexes = capturedChildren.stream()
                .mapToInt(child -> intField(child, "rocketIndex"))
                .toArray();
        int[] capturedObjectIds = capturedChildren.stream()
                .mapToInt(child -> intField(child, "objectId"))
                .toArray();
        int[] capturedLayoutIndexes = capturedChildren.stream()
                .mapToInt(child -> intField(child, "layoutIndex"))
                .toArray();

        assertParentSlots(parent, capturedChildren);

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");
        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        for (AbstractObjectInstance child : capturedChildren) {
            objectManager.removeDynamicObject(child);
        }
        assertEquals(0, liveRocketChildren(objectManager).size(),
                "diverge step must remove all rocket touch children");

        registry.restore(snapshot);

        HczMinibossInstance restoredParent = findHczMinibossParent(objectManager);
        assertNotNull(restoredParent, "restore must keep the live HCZ miniboss parent");

        List<AbstractObjectInstance> restoredChildren = liveRocketChildren(objectManager);
        assertEquals(4, restoredChildren.size(),
                "restore must recreate exactly four rocket touch children");

        for (int i = 0; i < restoredChildren.size(); i++) {
            AbstractObjectInstance restored = restoredChildren.get(i);
            assertEquals(capturedSlots[i], restored.getSlotIndex(),
                    "restored rocket child must keep captured dynamic slot " + i);
            assertEquals(capturedRocketIndexes[i], intField(restored, "rocketIndex"),
                    "restored rocket child must keep captured rocket index " + i);
            assertEquals(capturedObjectIds[i], intField(restored, "objectId"),
                    "restored rocket child must keep captured object id " + i);
            assertEquals(capturedLayoutIndexes[i], intField(restored, "layoutIndex"),
                    "restored rocket child must keep captured layout index " + i);
            assertEquals(capturedRocketIndexes[i] * 2, restored.getSpawn().subtype(),
                    "restored spawn subtype must remain slot-derivable");
            assertEquals(Sonic3kObjectIds.HCZ_MINIBOSS, restored.getSpawn().objectId(),
                    "restored spawn object id must remain the parent HCZ miniboss id");
            assertFalse(capturedChildren.contains(restored),
                    "restore must recreate the removed child instance rather than retaining a stale reference");
        }
        assertParentSlots(restoredParent, restoredChildren);
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ROCKET_TOUCH_CLASS),
                "RocketTouchChild must be restored by generic recreate, not by an explicit S3K codec");
    }

    private static HczMinibossInstance installPlacedHczMinibossParent(ObjectManager objectManager) throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(
                0x3600, 0x0500, Sonic3kObjectIds.HCZ_MINIBOSS, 0, 0, false, 0, 0x99);
        ObjectServices services = objectServices(objectManager);
        int slot = objectManager.allocateDynamicSlot();
        assertTrue(slot >= 0, "precondition: object manager must allocate a slot for the placed parent");

        HczMinibossInstance parent = ObjectConstructionContext.with(
                services, slot, () -> new HczMinibossInstance(spawn));
        parent.setServices(services);

        Method register = ObjectManager.class.getDeclaredMethod(
                "registerActiveObject", ObjectSpawn.class, ObjectInstance.class);
        register.setAccessible(true);
        register.invoke(objectManager, spawn, parent);
        setBooleanField(objectManager, "activeObjectsCacheDirty", true);
        return parent;
    }

    private static HczMinibossInstance findHczMinibossParent(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(HczMinibossInstance.class::isInstance)
                .map(HczMinibossInstance.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static List<AbstractObjectInstance> liveRocketChildren(ObjectManager objectManager) throws Exception {
        Class<?> type = Class.forName(ROCKET_TOUCH_CLASS);
        List<AbstractObjectInstance> children = new ArrayList<>();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == type && object instanceof AbstractObjectInstance aoi && !object.isDestroyed()) {
                children.add(aoi);
            }
        }
        children.sort(Comparator.comparingInt(child -> intField(child, "rocketIndex")));
        return children;
    }

    private static void assertParentSlots(
            HczMinibossInstance parent,
            List<AbstractObjectInstance> expectedChildren) throws Exception {
        Field field = HczMinibossInstance.class.getDeclaredField("rocketTouchChildren");
        field.setAccessible(true);
        Object array = field.get(parent);
        assertNotNull(array, "parent rocketTouchChildren array must be initialized");
        assertEquals(4, Array.getLength(array),
                "parent rocketTouchChildren array must have one slot per rocket");
        for (int i = 0; i < expectedChildren.size(); i++) {
            assertSame(expectedChildren.get(i), Array.get(array, i),
                    "parent rocketTouchChildren[" + i + "] must relink to the live restored child");
        }
    }

    private static void invokePrivateNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static ObjectServices objectServices(ObjectManager objectManager) throws Exception {
        Field field = ObjectManager.class.getDeclaredField("objectServices");
        field.setAccessible(true);
        return (ObjectServices) field.get(objectManager);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static int intField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

}
