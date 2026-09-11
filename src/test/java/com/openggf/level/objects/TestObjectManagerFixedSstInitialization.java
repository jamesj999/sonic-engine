package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectManagerFixedSstInitialization {

    @Test
    void fixedSstObjectsInstallBeforeInitialPlacementWindow() {
        ObjectSpawn layoutSpawn = new ObjectSpawn(0, 0, 1, 0, 0, false, 0);
        AtomicInteger installs = new AtomicInteger();
        ObjectRegistry registry = registry((zone, act, slots) -> {
            installs.incrementAndGet();
            slots.install(4, FixedObject::new);
        });
        ObjectManager manager = manager(List.of(layoutSpawn), registry);

        manager.reset(0);

        assertEquals(1, installs.get());
        assertEquals(4, only(manager, FixedObject.class).getExecutionSlotIndex());
        assertEquals(5, only(manager, LayoutObject.class).getExecutionSlotIndex());

        manager.reset(0);
        assertEquals(2, installs.get(), "fresh reset reinstalls the fixed ROM occupant once");
        assertEquals(1, manager.activeObjectsOfType(FixedObject.class).size());
        assertEquals(1, manager.activeObjectsOfType(LayoutObject.class).size());
    }

    @Test
    void duplicateExactSlotInstallFailsInsteadOfFallingBack() {
        ObjectRegistry registry = registry((zone, act, slots) -> {
            slots.install(4, FixedObject::new);
            slots.install(4, FixedObject::new);
        });
        ObjectManager manager = manager(List.of(), registry);

        assertThrows(IllegalStateException.class, () -> manager.reset(0));
        assertEquals(1, manager.activeObjectsOfType(FixedObject.class).size(),
                "the failed second install must not allocate a fallback slot");
    }

    @Test
    void defaultProviderIsNoOpAndLeavesFirstDynamicSlotForLayout() {
        ObjectSpawn layoutSpawn = new ObjectSpawn(0, 0, 1, 0, 0, false, 0);
        ObjectRegistry registry = registry((zone, act, slots) -> {
        });
        ObjectManager manager = manager(List.of(layoutSpawn), registry);

        manager.reset(0);

        assertEquals(4, only(manager, LayoutObject.class).getExecutionSlotIndex());
        assertEquals(0, manager.activeObjectsOfType(FixedObject.class).size());
    }

    @Test
    void ordinaryRewindRestoreDoesNotRerunFixedInitialization() {
        AtomicInteger installs = new AtomicInteger();
        ObjectRegistry registry = registry((zone, act, slots) -> {
            installs.incrementAndGet();
            slots.install(4, FixedObject::new);
        });
        ObjectManager manager = manager(List.of(), registry);
        manager.reset(0);
        var snapshot = manager.rewindSnapshottable().capture();

        manager.rewindSnapshottable().restore(snapshot);

        assertEquals(1, installs.get(),
                "ordinary rewind restores the captured SST graph without rerunning level initialization");
        assertEquals(4, only(manager, FixedObject.class).getExecutionSlotIndex());
    }

    private static ObjectManager manager(List<ObjectSpawn> spawns, ObjectRegistry registry) {
        ObjectServices services = mock(ObjectServices.class);
        when(services.romZoneId()).thenReturn(7);
        when(services.currentAct()).thenReturn(0);
        return new ObjectManager(spawns, registry, 0, null, null, null, null, services);
    }

    private static ObjectRegistry registry(FixedInstaller installer) {
        return new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return new LayoutObject(spawn);
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "test";
            }

            @Override
            public ObjectSlotLayout objectSlotLayout() {
                return ObjectSlotLayout.SONIC_3K;
            }

            @Override
            public void installFixedSstObjects(
                    int romZoneId,
                    int act,
                    FixedSstSlotSink slots) {
                installer.install(romZoneId, act, slots);
            }
        };
    }

    private static <T extends ObjectInstance> T only(ObjectManager manager, Class<T> type) {
        List<T> objects = manager.activeObjectsOfType(type);
        assertEquals(1, objects.size());
        return objects.get(0);
    }

    @FunctionalInterface
    private interface FixedInstaller {
        void install(int zone, int act, FixedSstSlotSink slots);
    }

    private static class FixedObject extends AbstractObjectInstance implements RewindRecreatable {
        private FixedObject() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "fixed");
        }

        @Override
        public FixedObject recreateForRewind(RewindRecreateContext ctx) {
            return new FixedObject();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static class LayoutObject extends AbstractObjectInstance {
        private LayoutObject(ObjectSpawn spawn) {
            super(spawn, "layout");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
