package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
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

class TestS3kCnzMechanismRewind {

    private static final ObjectSpawn GIANT_WHEEL_SPAWN =
            new ObjectSpawn(0x0500, 0x0180, Sonic3kObjectIds.CNZ_GIANT_WHEEL, 0x00, 0x01, false, 91);
    private static final ObjectSpawn HOVER_FAN_SPAWN =
            new ObjectSpawn(0x0580, 0x01C0, Sonic3kObjectIds.CNZ_HOVER_FAN, 0x92, 0x01, false, 92);
    private static final ObjectSpawn SPIRAL_TUBE_SPAWN =
            new ObjectSpawn(0x0600, 0x0200, Sonic3kObjectIds.CNZ_SPIRAL_TUBE, 0x02, 0, false, 93);
    private static final ObjectSpawn TELEPORTER_BEAM_SPAWN =
            new ObjectSpawn(0x0640, 0x0A38, 0, 0, 0, false, 94);
    private static final ObjectSpawn TELEPORTER_SPAWN =
            new ObjectSpawn(0x0640, 0x0A30, 0, 0, 0, false, 95);
    private static final ObjectSpawn TRAP_DOOR_SPAWN =
            new ObjectSpawn(0x0680, 0x0240, Sonic3kObjectIds.CNZ_TRAP_DOOR, 0x00, 0, false, 96);
    private static final ObjectSpawn TRIANGLE_BUMPER_SPAWN =
            new ObjectSpawn(0x0700, 0x0280, Sonic3kObjectIds.CNZ_TRIANGLE_BUMPER, 0x30, 0x03, false, 97);
    private static final ObjectSpawn VACUUM_TUBE_SPAWN =
            new ObjectSpawn(0x0780, 0x02C0, Sonic3kObjectIds.CNZ_VACUUM_TUBE, 0x20, 0x01, false, 98);
    private static final ObjectSpawn WATER_LEVEL_BUTTON_SPAWN =
            new ObjectSpawn(0x0800, 0x0300, Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON, 0x00, 0, false, 99);
    private static final ObjectSpawn DIVERGENT_TELEPORTER_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, 0, 0, 0, false, 109);
    private static final ObjectSpawn DIVERGENT_TELEPORTER_BEAM_SPAWN =
            new ObjectSpawn(0x0110, 0x0110, 0, 0, 0, false, 110);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x1000, 0x600, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cnzMechanismsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CnzGiantWheelInstance giantWheel = objectManager.createDynamicObject(
                () -> new CnzGiantWheelInstance(GIANT_WHEEL_SPAWN));
        CnzHoverFanInstance hoverFan = objectManager.createDynamicObject(
                () -> new CnzHoverFanInstance(HOVER_FAN_SPAWN));
        CnzSpiralTubeInstance spiralTube = objectManager.createDynamicObject(
                () -> new CnzSpiralTubeInstance(SPIRAL_TUBE_SPAWN));
        CnzTeleporterBeamInstance teleporterBeam = objectManager.createDynamicObject(
                () -> new CnzTeleporterBeamInstance(TELEPORTER_BEAM_SPAWN));
        CnzTrapDoorInstance trapDoor = objectManager.createDynamicObject(
                () -> new CnzTrapDoorInstance(TRAP_DOOR_SPAWN));
        CnzTriangleBumperObjectInstance triangleBumper = objectManager.createDynamicObject(
                () -> new CnzTriangleBumperObjectInstance(TRIANGLE_BUMPER_SPAWN));
        CnzVacuumTubeInstance vacuumTube = objectManager.createDynamicObject(
                () -> new CnzVacuumTubeInstance(VACUUM_TUBE_SPAWN));
        CnzWaterLevelButtonInstance waterLevelButton = objectManager.createDynamicObject(
                () -> new CnzWaterLevelButtonInstance(WATER_LEVEL_BUTTON_SPAWN));

        ObjectRefId giantWheelId = objectId(objectManager, giantWheel);
        ObjectRefId hoverFanId = objectId(objectManager, hoverFan);
        ObjectRefId spiralTubeId = objectId(objectManager, spiralTube);
        ObjectRefId teleporterBeamId = objectId(objectManager, teleporterBeam);
        ObjectRefId trapDoorId = objectId(objectManager, trapDoor);
        ObjectRefId triangleBumperId = objectId(objectManager, triangleBumper);
        ObjectRefId vacuumTubeId = objectId(objectManager, vacuumTube);
        ObjectRefId waterLevelButtonId = objectId(objectManager, waterLevelButton);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(giantWheel);
        objectManager.removeDynamicObject(hoverFan);
        objectManager.removeDynamicObject(spiralTube);
        objectManager.removeDynamicObject(teleporterBeam);
        objectManager.removeDynamicObject(trapDoor);
        objectManager.removeDynamicObject(triangleBumper);
        objectManager.removeDynamicObject(vacuumTube);
        objectManager.removeDynamicObject(waterLevelButton);

        CnzGiantWheelInstance replacementGiantWheel = objectManager.createDynamicObject(
                () -> new CnzGiantWheelInstance(replacementSpawn(Sonic3kObjectIds.CNZ_GIANT_WHEEL, 101)));
        CnzHoverFanInstance replacementHoverFan = objectManager.createDynamicObject(
                () -> new CnzHoverFanInstance(replacementSpawn(Sonic3kObjectIds.CNZ_HOVER_FAN, 102)));
        CnzSpiralTubeInstance replacementSpiralTube = objectManager.createDynamicObject(
                () -> new CnzSpiralTubeInstance(replacementSpawn(Sonic3kObjectIds.CNZ_SPIRAL_TUBE, 103)));
        CnzTeleporterBeamInstance replacementTeleporterBeam = objectManager.createDynamicObject(
                () -> new CnzTeleporterBeamInstance(new ObjectSpawn(0x0100, 0x0100, 0, 0, 0, false, 104)));
        CnzTrapDoorInstance replacementTrapDoor = objectManager.createDynamicObject(
                () -> new CnzTrapDoorInstance(replacementSpawn(Sonic3kObjectIds.CNZ_TRAP_DOOR, 105)));
        CnzTriangleBumperObjectInstance replacementTriangleBumper = objectManager.createDynamicObject(
                () -> new CnzTriangleBumperObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.CNZ_TRIANGLE_BUMPER, 106)));
        CnzVacuumTubeInstance replacementVacuumTube = objectManager.createDynamicObject(
                () -> new CnzVacuumTubeInstance(replacementSpawn(Sonic3kObjectIds.CNZ_VACUUM_TUBE, 107)));
        CnzWaterLevelButtonInstance replacementWaterLevelButton = objectManager.createDynamicObject(
                () -> new CnzWaterLevelButtonInstance(
                        replacementSpawn(Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON, 108)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, CnzGiantWheelInstance.class).size(),
                "restore must keep exactly the captured CNZ giant wheel");
        assertEquals(1, liveObjects(objectManager, CnzHoverFanInstance.class).size(),
                "restore must keep exactly the captured CNZ hover fan");
        assertEquals(1, liveObjects(objectManager, CnzSpiralTubeInstance.class).size(),
                "restore must keep exactly the captured CNZ spiral tube");
        assertEquals(1, liveObjects(objectManager, CnzTeleporterBeamInstance.class).size(),
                "restore must keep exactly the captured CNZ teleporter beam");
        assertEquals(1, liveObjects(objectManager, CnzTrapDoorInstance.class).size(),
                "restore must keep exactly the captured CNZ trap door");
        assertEquals(1, liveObjects(objectManager, CnzTriangleBumperObjectInstance.class).size(),
                "restore must keep exactly the captured CNZ triangle bumper");
        assertEquals(1, liveObjects(objectManager, CnzVacuumTubeInstance.class).size(),
                "restore must keep exactly the captured CNZ vacuum tube");
        assertEquals(1, liveObjects(objectManager, CnzWaterLevelButtonInstance.class).size(),
                "restore must keep exactly the captured CNZ water-level button");

        CnzGiantWheelInstance restoredGiantWheel =
                objectById(objectManager, CnzGiantWheelInstance.class, giantWheelId);
        CnzHoverFanInstance restoredHoverFan =
                objectById(objectManager, CnzHoverFanInstance.class, hoverFanId);
        CnzSpiralTubeInstance restoredSpiralTube =
                objectById(objectManager, CnzSpiralTubeInstance.class, spiralTubeId);
        CnzTeleporterBeamInstance restoredTeleporterBeam =
                objectById(objectManager, CnzTeleporterBeamInstance.class, teleporterBeamId);
        CnzTrapDoorInstance restoredTrapDoor =
                objectById(objectManager, CnzTrapDoorInstance.class, trapDoorId);
        CnzTriangleBumperObjectInstance restoredTriangleBumper =
                objectById(objectManager, CnzTriangleBumperObjectInstance.class, triangleBumperId);
        CnzVacuumTubeInstance restoredVacuumTube =
                objectById(objectManager, CnzVacuumTubeInstance.class, vacuumTubeId);
        CnzWaterLevelButtonInstance restoredWaterLevelButton =
                objectById(objectManager, CnzWaterLevelButtonInstance.class, waterLevelButtonId);

        assertFresh(giantWheel, replacementGiantWheel, restoredGiantWheel);
        assertFresh(hoverFan, replacementHoverFan, restoredHoverFan);
        assertFresh(spiralTube, replacementSpiralTube, restoredSpiralTube);
        assertFresh(teleporterBeam, replacementTeleporterBeam, restoredTeleporterBeam);
        assertFresh(trapDoor, replacementTrapDoor, restoredTrapDoor);
        assertFresh(triangleBumper, replacementTriangleBumper, restoredTriangleBumper);
        assertFresh(vacuumTube, replacementVacuumTube, restoredVacuumTube);
        assertFresh(waterLevelButton, replacementWaterLevelButton, restoredWaterLevelButton);

        assertEquals(GIANT_WHEEL_SPAWN, restoredGiantWheel.getSpawn());
        assertEquals(HOVER_FAN_SPAWN, restoredHoverFan.getSpawn());
        assertEquals(SPIRAL_TUBE_SPAWN, restoredSpiralTube.getSpawn());
        assertEquals(TELEPORTER_BEAM_SPAWN, restoredTeleporterBeam.getSpawn());
        assertEquals(TRAP_DOOR_SPAWN, restoredTrapDoor.getSpawn());
        assertEquals(TRIANGLE_BUMPER_SPAWN, restoredTriangleBumper.getSpawn());
        assertEquals(VACUUM_TUBE_SPAWN, restoredVacuumTube.getSpawn());
        assertEquals(WATER_LEVEL_BUTTON_SPAWN, restoredWaterLevelButton.getSpawn());

        assertTrue(readBooleanField(restoredGiantWheel, "flipped"),
                "CNZ giant wheel flipped state must rebuild from render flags");
        assertEquals(1, restoredHoverFan.getRenderFrameForTest(),
                "CNZ hover fan initial frame must rebuild from subtype");
        assertEquals(SPIRAL_TUBE_SPAWN.x(), restoredSpiralTube.getX(),
                "CNZ spiral tube must restore at spawn X");
        assertEquals(TELEPORTER_BEAM_SPAWN.x(), restoredTeleporterBeam.getX(),
                "CNZ teleporter beam centre X must rebuild from spawn");
        assertEquals(0, restoredTrapDoor.getRenderFrameForTest(),
                "CNZ trap door initial frame must rebuild closed");
        assertEquals(TRIANGLE_BUMPER_SPAWN.subtype(), readIntField(restoredTriangleBumper, "halfWidth"),
                "CNZ triangle bumper width must rebuild from subtype");
        assertTrue(readBooleanField(restoredVacuumTube, "liftMode"),
                "CNZ vacuum tube lift mode must rebuild from subtype");
        assertEquals(WATER_LEVEL_BUTTON_SPAWN.y() + 4, restoredWaterLevelButton.getY(),
                "CNZ water-level button Y offset must rebuild from spawn");
    }

    @Test
    void cnzMechanismsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzGiantWheelInstance.class),
                "CnzGiantWheelInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzHoverFanInstance.class),
                "CnzHoverFanInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzSpiralTubeInstance.class),
                "CnzSpiralTubeInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzTeleporterBeamInstance.class),
                "CnzTeleporterBeamInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzTeleporterInstance.class),
                "CnzTeleporterInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzTrapDoorInstance.class),
                "CnzTrapDoorInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzTriangleBumperObjectInstance.class),
                "CnzTriangleBumperObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzVacuumTubeInstance.class),
                "CnzVacuumTubeInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzWaterLevelButtonInstance.class),
                "CnzWaterLevelButtonInstance must restore through generic recreate");
    }

    @Test
    void cnzTeleporterRestoresBeamLinkWithoutDropsDoublesOrStaleRefs() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CnzTeleporterInstance sourceTeleporter = objectManager.createDynamicObject(
                () -> new CnzTeleporterInstance(TELEPORTER_SPAWN));
        CnzTeleporterBeamInstance sourceBeam = objectManager.createDynamicObject(
                () -> new CnzTeleporterBeamInstance(TELEPORTER_BEAM_SPAWN));
        setBooleanField(sourceTeleporter, "armed", true);
        setBooleanField(sourceTeleporter, "paletteLine2Patched", true);
        setBooleanField(sourceTeleporter, "teleportArtQueued", true);
        setBooleanField(sourceTeleporter, "beamSpawned", true);
        setBooleanField(sourceTeleporter, "playerCaptured", true);
        setObjectField(sourceTeleporter, "beam", sourceBeam);
        setIntField(sourceBeam, "beamCounter", CnzTeleporterBeamInstance.PLAYER_CAPTURE_COUNTER);

        ObjectRefId teleporterId = objectId(objectManager, sourceTeleporter);
        ObjectRefId beamId = objectId(objectManager, sourceBeam);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(sourceBeam);
        objectManager.removeDynamicObject(sourceTeleporter);
        CnzTeleporterInstance divergentTeleporter = objectManager.createDynamicObject(
                () -> new CnzTeleporterInstance(DIVERGENT_TELEPORTER_SPAWN));
        CnzTeleporterBeamInstance divergentBeam = objectManager.createDynamicObject(
                () -> new CnzTeleporterBeamInstance(DIVERGENT_TELEPORTER_BEAM_SPAWN));
        setObjectField(divergentTeleporter, "beam", divergentBeam);

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, CnzTeleporterInstance.class).size(),
                "restore must keep exactly the captured CNZ teleporter parent");
        assertEquals(1, liveObjects(objectManager, CnzTeleporterBeamInstance.class).size(),
                "restore must keep exactly the captured CNZ teleporter beam");
        CnzTeleporterInstance restoredTeleporter =
                objectById(objectManager, CnzTeleporterInstance.class, teleporterId);
        CnzTeleporterBeamInstance restoredBeam =
                objectById(objectManager, CnzTeleporterBeamInstance.class, beamId);
        assertNotSame(sourceTeleporter, restoredTeleporter,
                "restore must recreate the CNZ teleporter parent");
        assertNotSame(sourceBeam, restoredBeam,
                "restore must recreate the CNZ teleporter beam");
        assertNotSame(divergentTeleporter, restoredTeleporter,
                "restore must drop the divergent CNZ teleporter parent");
        assertNotSame(divergentBeam, restoredBeam,
                "restore must drop the divergent CNZ teleporter beam");
        assertSame(restoredBeam, readObjectField(restoredTeleporter, "beam"),
                "CNZ teleporter beam reference must resolve to the restored beam");
        assertNotSame(sourceBeam, readObjectField(restoredTeleporter, "beam"),
                "CNZ teleporter must not retain the stale pre-restore beam");
        assertTrue(readBooleanField(restoredTeleporter, "armed"),
                "teleporter armed state must restore from compact state");
        assertTrue(readBooleanField(restoredTeleporter, "paletteLine2Patched"),
                "teleporter palette state must restore from compact state");
        assertTrue(readBooleanField(restoredTeleporter, "teleportArtQueued"),
                "teleporter queued-art state must restore from compact state");
        assertTrue(readBooleanField(restoredTeleporter, "beamSpawned"),
                "teleporter beam-spawned state must restore from compact state");
        assertTrue(readBooleanField(restoredTeleporter, "playerCaptured"),
                "teleporter player-captured state must restore from compact state");
        assertFalse(readBooleanField(restoredTeleporter, "playerHidden"),
                "uncaptured teleporter player-hidden state must remain false");
        assertEquals(CnzTeleporterBeamInstance.PLAYER_CAPTURE_COUNTER, restoredBeam.getBeamCounter(),
                "beam counter must restore from compact state");
    }

    @Test
    void captureFailsWhenCnzTeleporterBeamHasNoRewindIdentity() {
        Harness harness = Harness.create();
        CnzTeleporterInstance teleporter = harness.objectManager().createDynamicObject(
                () -> new CnzTeleporterInstance(TELEPORTER_SPAWN));
        CnzTeleporterBeamInstance unmanagedBeam = new CnzTeleporterBeamInstance(TELEPORTER_BEAM_SPAWN);
        unmanagedBeam.setServices(harness.services());
        setObjectField(teleporter, "beam", unmanagedBeam);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                registryFor(harness.objectManager())::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing CNZ teleporter beam identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic3kObjectRegistry(),
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

    private static <T extends ObjectInstance> List<T> liveObjects(
            ObjectManager objectManager,
            Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kCnzMechanismRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void assertFresh(Object original, Object replacement, Object restored) {
        assertNotSame(original, restored, "restore must recreate " + restored.getClass().getSimpleName());
        assertNotSame(replacement, restored, "restore must drop divergent "
                + restored.getClass().getSimpleName());
    }

    private static ObjectSpawn replacementSpawn(int objectId, int layoutIndex) {
        return new ObjectSpawn(0x0100, 0x0100, objectId, 0, 0, false, layoutIndex);
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
            @Override public short getWidth() { return 0x1000; }
            @Override public short getHeight() { return 0x600; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
