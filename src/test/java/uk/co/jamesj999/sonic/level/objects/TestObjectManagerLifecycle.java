package uk.co.jamesj999.sonic.level.objects;

import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestObjectManagerLifecycle {
    @Test
    public void testPersistentObjectsRemainActive() {
        ObjectSpawn persistentSpawn = new ObjectSpawn(0, 0, 0x01, 0, 0, false, 0);
        ObjectSpawn tempSpawn = new ObjectSpawn(400, 0, 0x02, 0, 0, false, 0);

        List<ObjectSpawn> spawns = List.of(persistentSpawn, tempSpawn);
        TestRegistry registry = new TestRegistry(Set.of(persistentSpawn));
        ObjectManager manager = new ObjectManager(spawns, registry, 0, null, null);

        manager.reset(0);
        manager.update(0, null, 1);
        assertEquals(2, manager.getActiveObjects().size());

        manager.update(2000, null, 2);

        assertEquals(1, manager.getActiveObjects().size());
        assertTrue(manager.getActiveObjects().contains(registry.instances.get(persistentSpawn)));
        assertTrue(registry.unloadedInstances.contains(registry.instances.get(tempSpawn)));
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
        public void update(int frameCounter, AbstractPlayableSprite player) {
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
}
