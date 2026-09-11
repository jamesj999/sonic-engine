package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMgzPulleyGraphRewind {
    private static final String CHAIN_CLASS =
            "com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance$PulleyChainChild";
    private static final ObjectSpawn PULLEY_SPAWN =
            new ObjectSpawn(0x0580, 0x0520, Sonic3kObjectIds.MGZ_PULLEY, 0x04, 0, false, 0x0520, 11);
    private static final ObjectSpawn DIVERGENT_CHAIN_SPAWN =
            new ObjectSpawn(0x05e0, 0x0560, Sonic3kObjectIds.MGZ_PULLEY, 0, 0, false, 18);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mgzPulleyChainRestoresFreshExactStateAndRelinksParent() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MGZPulleyObjectInstance sourcePulley = only(objectManager, MGZPulleyObjectInstance.class);
        ObjectInstance sourceChain = onlyChain(objectManager);
        ObjectRefId pulleyId = objectId(objectManager, sourcePulley);
        ObjectRefId chainId = objectId(objectManager, sourceChain);

        writeIntField(sourcePulley, "currentExtension", 0x18);
        writeIntField(sourcePulley, "launchRecovery", 3);
        writeIntField(sourcePulley, "wheelFrame", 2);
        writeIntField(sourcePulley, "wheelAnimTimer", 1);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceChain);
        ObjectInstance divergentChain = objectManager.createDynamicObject(
                () -> constructChain(DIVERGENT_CHAIN_SPAWN, sourcePulley));
        assertEquals(1, liveChains(objectManager).size(),
                "diverge step should leave one unrelated MGZ pulley chain before restore");

        rewindRegistry.restore(snapshot);

        MGZPulleyObjectInstance restoredPulley =
                objectById(objectManager, MGZPulleyObjectInstance.class, pulleyId);
        ObjectInstance restoredChain = objectByIdRaw(objectManager, Class.forName(CHAIN_CLASS), chainId);
        assertEquals(1, liveChains(objectManager).size(),
                "restore must keep exactly the captured MGZ pulley chain");
        assertNotSame(sourcePulley, restoredPulley,
                "restore must recreate the MGZ pulley parent");
        assertNotSame(sourceChain, restoredChain,
                "restore must recreate the MGZ pulley chain");
        assertNotSame(divergentChain, restoredChain,
                "restore must drop the divergent MGZ pulley chain");
        assertEquals(0x18, readIntField(restoredPulley, "currentExtension"),
                "restored pulley must keep captured extension state");
        assertEquals(3, readIntField(restoredPulley, "launchRecovery"),
                "restored pulley must keep captured launch recovery state");
        assertEquals(2, readIntField(restoredPulley, "wheelFrame"),
                "restored pulley must keep captured wheel frame state");
        assertEquals(1, readIntField(restoredPulley, "wheelAnimTimer"),
                "restored pulley must keep captured wheel animation timer state");
        assertSame(restoredPulley, readObjectField(restoredChain, "parent"),
                "pulley chain parent must resolve to the restored pulley");
        assertSame(restoredChain, readObjectField(restoredPulley, "chainChild"),
                "restored MGZ pulley must point at the restored chain child");
        assertNotSame(sourcePulley, readObjectField(restoredChain, "parent"),
                "pulley chain must not retain the stale pre-restore parent");
        assertEquals(restoredPulley.getX(), restoredChain.getX(),
                "restored chain should read position through the restored parent");
        assertEquals(restoredPulley.getY(), restoredChain.getY(),
                "restored chain should read position through the restored parent");
    }

    @Test
    void mgzPulleyFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(MGZPulleyObjectInstance.class),
                "MGZ pulley must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(CHAIN_CLASS)),
                "MGZ pulley chain must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        MGZPulleyObjectInstance.class.getName()),
                "MGZ pulley must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(CHAIN_CLASS),
                "MGZ pulley chain must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtPulley();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(PULLEY_SPAWN),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructChain(ObjectSpawn spawn, MGZPulleyObjectInstance parent) {
        try {
            Class<?> type = Class.forName(CHAIN_CLASS);
            Constructor<?> ctor = type.getDeclaredConstructor(ObjectSpawn.class, MGZPulleyObjectInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(spawn, parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct MGZ pulley chain", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance objectByIdRaw(
            ObjectManager objectManager,
            Class<?> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance onlyChain(ObjectManager objectManager) {
        List<ObjectInstance> chains = liveChains(objectManager);
        assertEquals(1, chains.size(), "expected exactly one live MGZ pulley chain");
        return chains.getFirst();
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static List<ObjectInstance> liveChains(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(CHAIN_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Camera mockCameraAtPulley() {
        return new Camera() {
            @Override public short getX() { return 0x0500; }
            @Override public short getY() { return 0x04c0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getInt(target);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
