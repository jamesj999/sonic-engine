package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic2SpecialStageObjectSnapshot {
    @Test
    void objectManagerRestoresOrderedConcreteObjectsCountersAndEmeraldOwner() throws Exception {
        Sonic2SpecialStageManager owner = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);

        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
        ring.initialize(32, 0x40);
        ring.collect();
        set(ring, "angle", 0x66);
        set(ring, "depthFixed", 0x1234ABCDL);
        set(ring, "screenX", 111);
        set(ring, "screenY", 112);
        set(ring, "trackFloorY", 160);
        set(ring, "animIndex", 10);
        set(ring, "animFrame", 2);
        set(ring, "animTimer", 4);
        set(ring, "onScreen", true);
        set(ring, "highPriority", true);
        set(ring, "spinFrame", 3);

        Sonic2SpecialStageBomb bomb = new Sonic2SpecialStageBomb();
        bomb.initialize(40, 0x50);
        bomb.explode();

        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        emerald.setRingRequirement(120);
        emerald.setManager(new Sonic2SpecialStageManager());
        set(emerald, "phase", Sonic2SpecialStageEmerald.EmeraldPhase.COLLECTED);
        set(emerald, "phaseTimer", 77);
        set(emerald, "bobbingOffset", 1);
        set(emerald, "bobbingCounter", 42);
        set(emerald, "musicFaded", true);
        set(emerald, "emeraldAwarded", true);

        objectManager.getActiveObjects().add(ring);
        objectManager.getActiveObjects().add(bomb);
        objectManager.getActiveObjects().add(emerald);
        set(objectManager, "objectLocationData", new byte[] { 1, 2, 3 });
        set(objectManager, "stageOffsets", new int[] { 10, 20, 30 });
        set(objectManager, "currentPosition", 9);
        set(objectManager, "currentStage", 2);
        set(objectManager, "lastProcessedSegment", 4);
        set(objectManager, "ringsCollected", 44);
        set(objectManager, "perfectRingsTotal", 55);
        set(objectManager, "currentSpecialAct", 3);
        set(objectManager, "noCheckpointFlag", true);
        set(objectManager, "noCheckpointMsgFlag", true);
        set(objectManager, "ringsToGoEnabled", true);
        set(objectManager, "emeraldSpawned", true);

        Sonic2SpecialStageSnapshot.ObjectManagerSnapshot snapshot =
                objectManager.captureRewindSnapshot();

        ((byte[]) get(objectManager, "objectLocationData"))[0] = 99;
        ((int[]) get(objectManager, "stageOffsets"))[0] = 99;
        snapshot.objectLocationData()[0] = 77;
        snapshot.stageOffsets()[0] = 88;
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.activeObjects().add(ring.captureRewindSnapshot()));
        objectManager.reset();

        objectManager.restoreRewindSnapshot(snapshot, owner);

        assertEquals(44, objectManager.getRingsCollected());
        assertEquals(55, objectManager.getPerfectRingsTotal());
        assertEquals(3, objectManager.getCurrentSpecialAct());
        assertEquals(true, objectManager.isRingsToGoEnabled());
        assertEquals(true, objectManager.isEmeraldSpawned());
        assertEquals(9, get(objectManager, "currentPosition"));
        assertEquals(2, get(objectManager, "currentStage"));
        assertEquals(4, get(objectManager, "lastProcessedSegment"));
        assertEquals(true, get(objectManager, "noCheckpointFlag"));
        assertEquals(true, get(objectManager, "noCheckpointMsgFlag"));
        assertEquals(1, ((byte[]) get(objectManager, "objectLocationData"))[0]);
        assertEquals(10, ((int[]) get(objectManager, "stageOffsets"))[0]);
        assertNotSame(snapshot.objectLocationData(), get(objectManager, "objectLocationData"));
        assertNotSame(snapshot.stageOffsets(), get(objectManager, "stageOffsets"));

        List<Sonic2SpecialStageObject> restored = objectManager.getActiveObjects();
        assertEquals(3, restored.size());

        Sonic2SpecialStageRing restoredRing =
                assertInstanceOf(Sonic2SpecialStageRing.class, restored.get(0));
        assertEquals(Sonic2SpecialStageObject.State.COLLECTED, restoredRing.getState());
        assertEquals(0x66, restoredRing.getAngle());
        assertEquals(0x1234ABCDL, get(restoredRing, "depthFixed"));
        assertEquals(111, restoredRing.getScreenX());
        assertEquals(112, restoredRing.getScreenY());
        assertEquals(160, restoredRing.getTrackFloorY());
        assertEquals(10, restoredRing.getAnimIndex());
        assertEquals(2, restoredRing.getAnimFrame());
        assertEquals(4, get(restoredRing, "animTimer"));
        assertEquals(true, restoredRing.isOnScreen());
        assertEquals(true, restoredRing.isHighPriority());
        assertEquals(3, restoredRing.getSpinFrame());

        Sonic2SpecialStageBomb restoredBomb =
                assertInstanceOf(Sonic2SpecialStageBomb.class, restored.get(1));
        assertEquals(Sonic2SpecialStageObject.State.EXPLODING, restoredBomb.getState());

        Sonic2SpecialStageEmerald restoredEmerald =
                assertInstanceOf(Sonic2SpecialStageEmerald.class, restored.get(2));
        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.COLLECTED, restoredEmerald.getPhase());
        assertEquals(77, get(restoredEmerald, "phaseTimer"));
        assertEquals(1, restoredEmerald.getBobbingOffset());
        assertEquals(42, get(restoredEmerald, "bobbingCounter"));
        assertEquals(120, get(restoredEmerald, "ringRequirement"));
        assertEquals(true, get(restoredEmerald, "musicFaded"));
        assertEquals(true, restoredEmerald.isEmeraldAwarded());
        assertSame(owner, get(restoredEmerald, "manager"));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type, String field) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
