package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbz1CutsceneGraphRewind {
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x0060, 0x01A0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x14, 0, false, 0x6B);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void lbz1CutsceneHelpersRestoreFreshWithLiveParentRefsScalarsAndLatchState() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        Lbz1CutsceneGraph before = Lbz1CutsceneGraph.spawnInto(objectManager);
        Map<Class<?>, Integer> beforeCounts = familyCounts(objectManager);
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        CutsceneKnucklesLbz1Instance unmanagedReplacementParent =
                new CutsceneKnucklesLbz1Instance(new ObjectSpawn(
                        0x4100, 0x0200, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x14, 0, false, 0x7A));
        CutsceneKnucklesLbz1RangeHelper replacementRange = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesLbz1RangeHelper(unmanagedReplacementParent, 0x40C0, 0x0200));
        CutsceneKnucklesLbz1CollapseChild replacementCollapse = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesLbz1CollapseChild(unmanagedReplacementParent, 2));

        rewindRegistry.restore(snapshot);

        Lbz1CutsceneGraph restored = Lbz1CutsceneGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, familyCounts(objectManager),
                "restore must not drop or duplicate the LBZ1 cutscene helper family");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic helper identities");

        restored.assertChildrenAreFreshFrom(before);
        restored.assertChildrenAreFreshFrom(replacementRange, replacementCollapse);
        restored.assertChildrenRelinkToRestoredParent();
        restored.assertScalarsEqual(before);
        restored.assertLatchStateRestored();

        invokeNoArg(restored.parent(), "spawnRangeHelperOnce");
        invokeNoArg(restored.parent(), "spawnCollapseChildrenOnce");
        assertEquals(beforeCounts, familyCounts(objectManager),
                "restored parent latches must prevent duplicate helper spawns");

        restored.parent().update(0x20, null);
        assertEquals(1, liveObjects(objectManager, CutsceneKnucklesLbz1RangeHelper.class).size(),
                "ticking the restored parent must not duplicate the range helper");
        assertEquals(4, liveObjects(objectManager, CutsceneKnucklesLbz1CollapseChild.class).size(),
                "ticking the restored parent collapse routine must not duplicate collapse children");
    }

    @Test
    void lbz1CutsceneHelpersUseRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesLbz1RangeHelper.class),
                "range helper must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesLbz1CollapseChild.class),
                "collapse child must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesLbz1RangeHelper.class.getName()),
                "range helper must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesLbz1CollapseChild.class.getName()),
                "collapse child must not keep an explicit S3K dynamic codec");
    }

    @Test
    void genericRecreateDropsLbz1HelpersWhenParentIsMissingOrAmbiguous() {
        Harness noParent = Harness.create(List.of());
        assertNull(genericRecreate(noParent.objectManager(), CutsceneKnucklesLbz1RangeHelper.class,
                        new ObjectSpawn(0x3BC0, 0x01A0, 0, 0, 0, false, 0x80)),
                "range helper must drop when no live LBZ1 cutscene parent exists");
        assertNull(genericRecreate(noParent.objectManager(), CutsceneKnucklesLbz1CollapseChild.class,
                        new ObjectSpawn(0x3BC0, 0x01A0, 0, 0, 0, false, 0x81)),
                "collapse child must drop when no live LBZ1 cutscene parent exists");

        Harness ambiguous = Harness.create(List.of());
        ambiguous.objectManager().createDynamicObject(() -> new CutsceneKnucklesLbz1Instance(PARENT_SPAWN));
        ambiguous.objectManager().createDynamicObject(() -> new CutsceneKnucklesLbz1Instance(
                new ObjectSpawn(0x3C40, 0x01A0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x14, 0, false, 0x82)));

        assertNull(genericRecreate(ambiguous.objectManager(), CutsceneKnucklesLbz1RangeHelper.class,
                        new ObjectSpawn(0x3BC0, 0x01A0, 0, 0, 0, false, 0x83)),
                "range helper must drop when multiple live LBZ1 cutscene parents make relink ambiguous");
        assertNull(genericRecreate(ambiguous.objectManager(), CutsceneKnucklesLbz1CollapseChild.class,
                        new ObjectSpawn(0x3BC0, 0x01A0, 0, 0, 0, false, 0x84)),
                "collapse child must drop when multiple live LBZ1 cutscene parents make relink ambiguous");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Lbz1CutsceneParentTestRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private record Lbz1CutsceneGraph(
            CutsceneKnucklesLbz1Instance parent,
            CutsceneKnucklesLbz1RangeHelper rangeHelper,
            CutsceneKnucklesLbz1CollapseChild collapse0,
            CutsceneKnucklesLbz1CollapseChild collapse1,
            CutsceneKnucklesLbz1CollapseChild collapse2,
            CutsceneKnucklesLbz1CollapseChild collapse3) {

        static Lbz1CutsceneGraph spawnInto(ObjectManager objectManager) {
            CutsceneKnucklesLbz1Instance parent = only(objectManager, CutsceneKnucklesLbz1Instance.class);
            setBooleanField(parent, "helperSpawned", true);
            setBooleanField(parent, "bombSpawned", true);
            setBooleanField(parent, "collapseChildrenSpawned", true);
            setRoutine(parent, "WAIT_BEFORE_COLLAPSE");
            setIntField(parent, "timer", -1);

            CutsceneKnucklesLbz1RangeHelper rangeHelper = objectManager.createDynamicObject(
                    () -> new CutsceneKnucklesLbz1RangeHelper(parent, 0x3BC0, 0x01A0));
            CutsceneKnucklesLbz1CollapseChild collapse0 = objectManager.createDynamicObject(
                    () -> new CutsceneKnucklesLbz1CollapseChild(parent, 0));
            CutsceneKnucklesLbz1CollapseChild collapse1 = objectManager.createDynamicObject(
                    () -> new CutsceneKnucklesLbz1CollapseChild(parent, 1));
            CutsceneKnucklesLbz1CollapseChild collapse2 = objectManager.createDynamicObject(
                    () -> new CutsceneKnucklesLbz1CollapseChild(parent, 2));
            CutsceneKnucklesLbz1CollapseChild collapse3 = objectManager.createDynamicObject(
                    () -> new CutsceneKnucklesLbz1CollapseChild(parent, 3));

            setCollapseState(collapse0, 0x3BC1, 0x0190, 0x1E, 2);
            setCollapseState(collapse1, 0x3B81, 0x0180, 0x1D, 1);
            setCollapseState(collapse2, 0x3B41, 0x0170, 0x1C, 0);
            setCollapseState(collapse3, 0x3B01, 0x0160, 0x1B, 3);

            return new Lbz1CutsceneGraph(parent, rangeHelper, collapse0, collapse1, collapse2, collapse3);
        }

        static Lbz1CutsceneGraph fromLiveObjects(ObjectManager objectManager) {
            List<CutsceneKnucklesLbz1CollapseChild> collapse =
                    liveObjects(objectManager, CutsceneKnucklesLbz1CollapseChild.class).stream()
                            .sorted(Comparator.comparingInt(child -> readIntField(child, "subtype")))
                            .toList();
            assertEquals(4, collapse.size(), "expected four restored collapse children");
            return new Lbz1CutsceneGraph(
                    only(objectManager, CutsceneKnucklesLbz1Instance.class),
                    only(objectManager, CutsceneKnucklesLbz1RangeHelper.class),
                    collapse.get(0),
                    collapse.get(1),
                    collapse.get(2),
                    collapse.get(3));
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("rangeHelper", objectId(objectManager, rangeHelper));
            ids.put("collapse0", objectId(objectManager, collapse0));
            ids.put("collapse1", objectId(objectManager, collapse1));
            ids.put("collapse2", objectId(objectManager, collapse2));
            ids.put("collapse3", objectId(objectManager, collapse3));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            objectManager.removeDynamicObject(rangeHelper);
            objectManager.removeDynamicObject(collapse0);
            objectManager.removeDynamicObject(collapse1);
            objectManager.removeDynamicObject(collapse2);
            objectManager.removeDynamicObject(collapse3);
        }

        void assertChildrenAreFreshFrom(Lbz1CutsceneGraph before) {
            assertNotSame(before.rangeHelper, rangeHelper, "range helper must be recreated, not reused stale");
            assertNotSame(before.collapse0, collapse0, "collapse 0 must be recreated, not reused stale");
            assertNotSame(before.collapse1, collapse1, "collapse 1 must be recreated, not reused stale");
            assertNotSame(before.collapse2, collapse2, "collapse 2 must be recreated, not reused stale");
            assertNotSame(before.collapse3, collapse3, "collapse 3 must be recreated, not reused stale");
        }

        void assertChildrenAreFreshFrom(
                CutsceneKnucklesLbz1RangeHelper replacementRange,
                CutsceneKnucklesLbz1CollapseChild replacementCollapse) {
            assertNotSame(replacementRange, rangeHelper, "restore must drop replacement range helper");
            assertNotSame(replacementCollapse, collapse0, "restore must drop replacement collapse child");
            assertNotSame(replacementCollapse, collapse1, "restore must drop replacement collapse child");
            assertNotSame(replacementCollapse, collapse2, "restore must drop replacement collapse child");
            assertNotSame(replacementCollapse, collapse3, "restore must drop replacement collapse child");
        }

        void assertChildrenRelinkToRestoredParent() {
            assertSame(parent, readObjectField(rangeHelper, "parent"),
                    "range helper parent must be restored live parent");
            for (CutsceneKnucklesLbz1CollapseChild child : collapseChildren()) {
                assertSame(parent, readObjectField(child, "parent"),
                        "collapse child parent must be restored live parent");
            }
        }

        void assertScalarsEqual(Lbz1CutsceneGraph before) {
            assertEquals(readIntField(before.rangeHelper, "x"), readIntField(rangeHelper, "x"),
                    "range helper x must restore exactly");
            assertEquals(readIntField(before.rangeHelper, "y"), readIntField(rangeHelper, "y"),
                    "range helper y must restore exactly");
            List<CutsceneKnucklesLbz1CollapseChild> beforeChildren = before.collapseChildren();
            List<CutsceneKnucklesLbz1CollapseChild> restoredChildren = collapseChildren();
            for (int i = 0; i < restoredChildren.size(); i++) {
                assertScalarFieldsEqual(beforeChildren.get(i), restoredChildren.get(i),
                        "subtype", "x", "y", "explosionTimer", "explosionIntervalCounter");
            }
        }

        void assertLatchStateRestored() {
            assertTrue(readBooleanField(parent, "helperSpawned"),
                    "helper-spawn latch must restore to prevent range-helper duplicates");
            assertTrue(readBooleanField(parent, "bombSpawned"),
                    "bomb-spawn latch must restore with the parent cutscene state");
            assertTrue(readBooleanField(parent, "collapseChildrenSpawned"),
                    "collapse-spawn latch must restore to prevent collapse-helper duplicates");
        }

        private List<CutsceneKnucklesLbz1CollapseChild> collapseChildren() {
            return List.of(collapse0, collapse1, collapse2, collapse3);
        }
    }

    private static final class Lbz1CutsceneParentTestRegistry extends Sonic3kObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            if (spawn.objectId() == Sonic3kObjectIds.CUTSCENE_KNUCKLES && spawn.subtype() == 0x14) {
                return new CutsceneKnucklesLbz1Instance(spawn);
            }
            return super.create(spawn);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance genericRecreate(
            ObjectManager objectManager,
            Class<? extends AbstractObjectInstance> type,
            ObjectSpawn spawn) {
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(type.getName(), spawn, 0, emptyState());
        return ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
    }

    private static Map<Class<?>, Integer> familyCounts(ObjectManager objectManager) {
        Map<Class<?>, Integer> counts = new LinkedHashMap<>();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == CutsceneKnucklesLbz1Instance.class
                    || object.getClass() == CutsceneKnucklesLbz1RangeHelper.class
                    || object.getClass() == CutsceneKnucklesLbz1CollapseChild.class) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static void assertScalarFieldsEqual(Object before, Object restored, String... fields) {
        for (String field : fields) {
            assertEquals(readIntField(before, field), readIntField(restored, field),
                    () -> before.getClass().getSimpleName() + "." + field + " must restore exactly");
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setCollapseState(
            CutsceneKnucklesLbz1CollapseChild child,
            int x,
            int y,
            int explosionTimer,
            int explosionIntervalCounter) {
        setIntField(child, "x", x);
        setIntField(child, "y", y);
        setIntField(child, "explosionTimer", explosionTimer);
        setIntField(child, "explosionIntervalCounter", explosionIntervalCounter);
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRoutine(CutsceneKnucklesLbz1Instance parent, String value) {
        try {
            Field field = findField(parent.getClass(), "routine");
            Class<? extends Enum> enumType = (Class<? extends Enum>) field.getType();
            field.set(parent, Enum.valueOf(enumType, value));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write routine on " + parent.getClass(), e);
        }
    }

    private static void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
