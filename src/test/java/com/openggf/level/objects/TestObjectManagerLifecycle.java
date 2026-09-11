package com.openggf.level.objects;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestObjectManagerLifecycle {

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void testPersistentObjectsRemainActive() {
        ObjectSpawn persistentSpawn = new ObjectSpawn(0, 0, 0x01, 0, 0, false, 0);
        ObjectSpawn tempSpawn = new ObjectSpawn(400, 0, 0x02, 0, 0, false, 0);

        List<ObjectSpawn> spawns = List.of(persistentSpawn, tempSpawn);
        TestRegistry registry = new TestRegistry(Set.of(persistentSpawn));
        ObjectManager manager = new ObjectManager(spawns, registry, 0, null, null);

        manager.reset(0);
        manager.update(0, null, null, 1);
        assertEquals(2, manager.getActiveObjects().size());

        // Placement updates are streamed for the next frame; run one tick to apply.
        manager.update(2000, null, null, 2);
        manager.update(2000, null, null, 3);

        assertEquals(1, manager.getActiveObjects().size());
        assertTrue(manager.getActiveObjects().contains(registry.instances.get(persistentSpawn)));
        assertTrue(registry.unloadedInstances.contains(registry.instances.get(tempSpawn)));
    }

    @Test
    public void postCameraPlacementCreatesNonCounterGapSpawnImmediatelyWithoutUpdatingIt() {
        ObjectSpawn gapSpawn = new ObjectSpawn(0x02C0, 0, 0x03, 0, 0, false, 0);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(List.of(gapSpawn), registry, 0, null, null);

        manager.reset(0);
        assertEquals(0, registry.createCount,
                "Gap spawn should start outside the initial [0x0000,0x0280) window");

        manager.postCameraPlacementUpdate(0x0080);

        assertEquals(1, registry.createCount,
                "S2/S3K post-camera placement should materialize gap spawns in the current frame");
        assertTrue(manager.getActiveObjects().contains(registry.instances.get(gapSpawn)),
                "Gap spawn instance should exist immediately after post-camera placement");
        assertEquals(0, registry.instances.get(gapSpawn).updateCount,
                "Post-camera placement should not execute the newly created object until next frame");
    }

    @Test
    public void execThenLoadPlacementMaterializesNewSpawnsAfterObjectExecution() {
        ObjectSpawn streamedSpawn = new ObjectSpawn(0x02C0, 0, 0x03, 0, 0, false, 0);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(List.of(streamedSpawn), registry, 0, null, null);
        manager.enableExecThenLoadPlacement();

        manager.reset(0);
        assertEquals(0, registry.createCount,
                "Spawn should start outside the initial [0x0000,0x0280) window");

        manager.update(0x0080, null, null, 1);

        assertEquals(1, registry.createCount,
                "ObjPosLoad should materialize the newly streamed spawn after ExecuteObjects");
        assertEquals(0, registry.instances.get(streamedSpawn).updateCount,
                "New ObjPosLoad instances should not execute until the following frame");

        manager.update(0x0080, null, null, 2);

        assertEquals(1, registry.instances.get(streamedSpawn).updateCount);
    }

    @Test
    public void s2InitialAndRuntimeLoadsBothBypassVerticalFilter() {
        Camera camera = GameServices.camera();
        camera.setMinY((short) 0);
        camera.setY((short) 0);

        ObjectSpawn highYSpawn = new ObjectSpawn(0x0200, 0x0700, 0x03, 0, 0, false, 0x0700);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(List.of(highYSpawn), registry, 0, null, null);
        manager.enableExecThenLoadPlacement();

        manager.reset(0);
        assertEquals(1, registry.createCount,
                "S2 ObjectsManager_Init uses the same X-only load window as its runtime passes");
        assertEquals(0, registry.instances.get(highYSpawn).updateCount,
                "Reset materialization creates the object without executing it");

        manager.update(0, null, null, 1);

        assertEquals(1, registry.createCount,
                "The already-materialized object must not be recreated by the runtime load pass");
        assertEquals(1, registry.instances.get(highYSpawn).updateCount);

        manager.update(0, null, null, 2);

        assertEquals(2, registry.instances.get(highYSpawn).updateCount);
    }

    @Test
    public void bonusReturnRespawnStateSuppressesInitiallyVisibleSpawnBeforeMaterialization() {
        ObjectSpawn originalSpawn = new ObjectSpawn(0x0200, 0, 0x03, 0, 0, true, 0x8000);
        ObjectManager originalManager = new ObjectManager(
                List.of(originalSpawn), new TrackingRegistry(), 0, null, null);
        originalManager.reset(0);
        originalManager.markRemembered(originalSpawn);
        PersistentRespawnState savedRespawnState = originalManager.capturePersistentRespawn();

        ObjectSpawn reloadedSpawn = new ObjectSpawn(0x0200, 0, 0x03, 0, 0, true, 0x8000);
        TrackingRegistry reloadedRegistry = new TrackingRegistry();
        ObjectManager reloadedManager = new ObjectManager(
                List.of(reloadedSpawn), reloadedRegistry, 0, null, null);

        reloadedManager.reset(0, savedRespawnState);

        assertEquals(0, reloadedRegistry.createCount,
                "Bonus-return respawn bits must be restored before the fresh manager materializes its initial window");
        assertTrue(reloadedManager.getActiveObjects().isEmpty(),
                "A remembered non-stay-active spawn must remain absent after a bonus-return reload");
    }

    private static final class TestRegistry implements ObjectRegistry {
        private final Set<ObjectSpawn> persistentSpawns;
        private final Map<ObjectSpawn, ObjectInstance> instances = new IdentityHashMap<>();
        private final List<ObjectInstance> unloadedInstances = new ArrayList<>();

        private TestRegistry(Set<ObjectSpawn> persistentSpawns) {
            this.persistentSpawns = persistentSpawns;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            boolean persistent = persistentSpawns.contains(spawn);
            TestInstance instance = new TestInstance(spawn, persistent, unloadedInstances);
            instances.put(spawn, instance);
            return instance;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Test";
        }
    }

    private static final class TrackingRegistry implements ObjectRegistry {
        private final Map<ObjectSpawn, TrackingObjectInstance> instances = new IdentityHashMap<>();
        private int createCount;

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            createCount++;
            TrackingObjectInstance instance = new TrackingObjectInstance(spawn);
            instances.put(spawn, instance);
            return instance;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Tracking";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }
    }

    private static final class TestInstance implements ObjectInstance {
        private final ObjectSpawn spawn;
        private final boolean persistent;
        private final List<ObjectInstance> unloadedInstances;

        private TestInstance(ObjectSpawn spawn, boolean persistent, List<ObjectInstance> unloadedInstances) {
            this.spawn = spawn;
            this.persistent = persistent;
            this.unloadedInstances = unloadedInstances;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public boolean isPersistent() {
            return persistent;
        }

        @Override
        public void onUnload() {
            unloadedInstances.add(this);
        }
    }

    private static final class TrackingObjectInstance extends AbstractObjectInstance {
        private int updateCount;

        private TrackingObjectInstance(ObjectSpawn spawn) {
            super(spawn, "TrackingObject");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            updateCount++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
