package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizFallingLogGraphRewind {
    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void fallingLogPairRestoresFreshWithExactBidirectionalRelinksAndScalarState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AizFallingLogObjectInstance.FallingLogChild sourceLog =
                objectManager.createDynamicObject(() -> new AizFallingLogObjectInstance.FallingLogChild(
                        0x0100, 0x0150, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG));
        AizFallingLogObjectInstance.FallingLogChild distractorLog =
                objectManager.createDynamicObject(() -> new AizFallingLogObjectInstance.FallingLogChild(
                        0x0108, 0x0158, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG));
        AizFallingLogObjectInstance.SplashChild sourceSplash =
                objectManager.createDynamicObject(() -> new AizFallingLogObjectInstance.SplashChild(
                        sourceLog, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG_SPLASH));
        sourceLog.setLinkedSplash(sourceSplash);

        setLogState(sourceLog, 0x0340, 0x0210, 1, 37, true, Sonic3kObjectArtKeys.AIZ2_FALLING_LOG);
        setLogState(distractorLog, 0x0108, 0x0158, 0, 12, false, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG);
        setSplashState(sourceSplash, 3, 2, Sonic3kObjectArtKeys.AIZ2_FALLING_LOG_SPLASH);

        ObjectRefId sourceLogId = objectId(objectManager, sourceLog);
        ObjectRefId distractorLogId = objectId(objectManager, distractorLog);
        ObjectRefId sourceSplashId = objectId(objectManager, sourceSplash);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceSplash);
        objectManager.removeDynamicObject(sourceLog);
        objectManager.removeDynamicObject(distractorLog);
        AizFallingLogObjectInstance.FallingLogChild divergentLog =
                objectManager.createDynamicObject(() -> new AizFallingLogObjectInstance.FallingLogChild(
                        0x00F8, 0x0148, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG));
        AizFallingLogObjectInstance.SplashChild divergentSplash =
                objectManager.createDynamicObject(() -> new AizFallingLogObjectInstance.SplashChild(
                        divergentLog, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG_SPLASH));
        divergentLog.setLinkedSplash(divergentSplash);

        rewindRegistry.restore(snapshot);

        assertEquals(2, liveObjects(objectManager, AizFallingLogObjectInstance.FallingLogChild.class).size(),
                "restore must keep exactly the captured target and distractor logs");
        assertEquals(1, liveObjects(objectManager, AizFallingLogObjectInstance.SplashChild.class).size(),
                "restore must keep exactly the captured splash");

        AizFallingLogObjectInstance.FallingLogChild restoredLog =
                objectById(objectManager, AizFallingLogObjectInstance.FallingLogChild.class, sourceLogId);
        AizFallingLogObjectInstance.FallingLogChild restoredDistractorLog =
                objectById(objectManager, AizFallingLogObjectInstance.FallingLogChild.class, distractorLogId);
        AizFallingLogObjectInstance.SplashChild restoredSplash =
                objectById(objectManager, AizFallingLogObjectInstance.SplashChild.class, sourceSplashId);

        assertNotSame(sourceLog, restoredLog, "restore must recreate the target log");
        assertNotSame(distractorLog, restoredDistractorLog, "restore must recreate the distractor log");
        assertNotSame(sourceSplash, restoredSplash, "restore must recreate the splash");
        assertNotSame(divergentLog, restoredLog, "restore must drop the divergent log");
        assertNotSame(divergentSplash, restoredSplash, "restore must drop the divergent splash");

        assertSame(restoredSplash, readObjectField(restoredLog, "linkedSplash"),
                "log must relink to its exact restored splash by ObjectRefId");
        assertSame(restoredLog, readObjectField(restoredSplash, "linkedLog"),
                "splash must relink to its exact restored log by ObjectRefId");
        assertNotSame(restoredDistractorLog, readObjectField(restoredSplash, "linkedLog"),
                "splash must not keep the nearest heuristic log after compact relink");
        assertNotSame(sourceSplash, readObjectField(restoredLog, "linkedSplash"),
                "log must not retain the stale pre-restore splash");
        assertNotSame(sourceLog, readObjectField(restoredSplash, "linkedLog"),
                "splash must not retain the stale pre-restore log");
        assertNotSame(divergentSplash, readObjectField(restoredLog, "linkedSplash"),
                "log must not point at the divergent splash");
        assertNotSame(divergentLog, readObjectField(restoredSplash, "linkedLog"),
                "splash must not point at the divergent log");

        assertLogState(restoredLog, 0x0340, 0x0210, 1, 37, true, Sonic3kObjectArtKeys.AIZ2_FALLING_LOG);
        assertSplashState(restoredSplash, 3, 2, Sonic3kObjectArtKeys.AIZ2_FALLING_LOG_SPLASH);

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(sourceLogId, restoredTable.idFor(restoredLog),
                "restored log must retain its captured rewind identity");
        assertEquals(sourceSplashId, restoredTable.idFor(restoredSplash),
                "restored splash must retain its captured rewind identity");
    }

    @Test
    void fallingLogGraphClassesUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        AizFallingLogObjectInstance.class),
                "AizFallingLogObjectInstance must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        AizFallingLogObjectInstance.FallingLogChild.class),
                "FallingLogChild must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        AizFallingLogObjectInstance.SplashChild.class),
                "SplashChild must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizFallingLogObjectInstance.class.getName()),
                "AizFallingLogObjectInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizFallingLogObjectInstance.FallingLogChild.class.getName()),
                "FallingLogChild must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsForLogWhoseLinkedSplashHasNoRewindIdentity() {
        Harness harness = Harness.create();
        AizFallingLogObjectInstance.FallingLogChild log =
                harness.objectManager().createDynamicObject(
                        () -> new AizFallingLogObjectInstance.FallingLogChild(
                                0x0180, 0x0160, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG));
        AizFallingLogObjectInstance.SplashChild unmanagedSplash =
                new AizFallingLogObjectInstance.SplashChild(log, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG_SPLASH);
        unmanagedSplash.setServices(harness.services());
        log.setLinkedSplash(unmanagedSplash);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> registryFor(harness.objectManager()).capture());
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing required linkedSplash target must fail loudly");
    }

    @Test
    void captureFailsForSplashWhoseLinkedLogHasNoRewindIdentity() {
        Harness harness = Harness.create();
        AizFallingLogObjectInstance.FallingLogChild unmanagedLog =
                new AizFallingLogObjectInstance.FallingLogChild(
                        0x0180, 0x0160, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG);
        unmanagedLog.setServices(harness.services());
        AizFallingLogObjectInstance.SplashChild splash =
                harness.objectManager().createDynamicObject(
                        () -> new AizFallingLogObjectInstance.SplashChild(
                                unmanagedLog, Sonic3kObjectArtKeys.AIZ1_FALLING_LOG_SPLASH));

        assertSame(unmanagedLog, readObjectField(splash, "linkedLog"),
                "precondition: splash log is outside ObjectManager identity registration");
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> registryFor(harness.objectManager()).capture());
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing required linkedLog target must fail loudly");
    }

    private record Harness(ObjectManager objectManager, StubObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_AIZ; }
                @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_AIZ; }
                @Override public int currentAct() { return 0; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new AizTestRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services);
        }
    }

    private static final class AizTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_AIZ;
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestAizFallingLogGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void setLogState(
            AizFallingLogObjectInstance.FallingLogChild log,
            int x,
            int y,
            int state,
            int timer,
            boolean bobHidden,
            String artKey) {
        setIntField(log, "x", x);
        setIntField(log, "y", y);
        setIntField(log, "state", state);
        setIntField(log, "timer", timer);
        setBooleanField(log, "bobHidden", bobHidden);
        setObjectField(log, "artKey", artKey);
    }

    private static void setSplashState(
            AizFallingLogObjectInstance.SplashChild splash,
            int mappingFrame,
            int animTimer,
            String artKey) {
        setIntField(splash, "mappingFrame", mappingFrame);
        setIntField(splash, "animTimer", animTimer);
        setObjectField(splash, "artKey", artKey);
    }

    private static void assertLogState(
            AizFallingLogObjectInstance.FallingLogChild log,
            int x,
            int y,
            int state,
            int timer,
            boolean bobHidden,
            String artKey) {
        assertEquals(x, readIntField(log, "x"), "log x must restore exactly");
        assertEquals(y, readIntField(log, "y"), "log y must restore exactly");
        assertEquals(state, readIntField(log, "state"), "log state must restore exactly");
        assertEquals(timer, readIntField(log, "timer"), "log timer must restore exactly");
        assertEquals(bobHidden, readBooleanField(log, "bobHidden"), "log bobHidden must restore exactly");
        assertEquals(artKey, readObjectField(log, "artKey"), "log artKey must restore exactly");
    }

    private static void assertSplashState(
            AizFallingLogObjectInstance.SplashChild splash,
            int mappingFrame,
            int animTimer,
            String artKey) {
        assertEquals(mappingFrame, readIntField(splash, "mappingFrame"),
                "splash mappingFrame must restore exactly");
        assertEquals(animTimer, readIntField(splash, "animTimer"),
                "splash animTimer must restore exactly");
        assertEquals(artKey, readObjectField(splash, "artKey"),
                "splash artKey must restore exactly");
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

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
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

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
