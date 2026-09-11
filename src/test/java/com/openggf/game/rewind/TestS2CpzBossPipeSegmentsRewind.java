package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipe;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipeSegment;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Round-trips the CPZ boss pipe's {@code final List<CPZBossPipeSegment> segments}
 * identity collection across a mid-fight rewind.
 *
 * <p>A {@code final} object-reference collection is deliberately treated as
 * <em>structural</em> rewind state (not captured through the identity table — see
 * {@code TestObjectManagerRewindDynamicClassification
 * #defaultObjectSnapshotTreatsFinalObjectReferenceCollectionsAsStructural}); it is meant
 * to be rebuilt by its owner on restore. {@link CPZBossPipeSegment} is rewind-recreatable
 * and relinks its {@code parentPipe} back-reference, but nothing re-populated the pipe's
 * forward {@code segments} list, so after a rewind the restored pipe drove its
 * one-segment-at-a-time retract sequence ({@code updateRetract}) off an empty list. The
 * fix has each recreated segment re-register with its restored pipe.
 */
class TestS2CpzBossPipeSegmentsRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.CPZ_BOSS, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void restoredPipeRebuildsItsSegmentListFromRecreatedSegments() throws Exception {
        ObjectManager objectManager = createManagerWithBoss();

        CPZBossPipe pipe = only(objectManager, CPZBossPipe.class);
        invokePrivate(pipe, "spawnPipeSegment", 0);
        List<?> segmentsBefore = readSegments(pipe);
        assertEquals(1, segmentsBefore.size(), "precondition: the pipe holds its spawned segment");
        CPZBossPipeSegment segmentBefore = only(objectManager, CPZBossPipeSegment.class);
        assertSame(segmentBefore, segmentsBefore.get(0), "precondition: list holds the live segment");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        // Diverge: drop the captured segment and spawn an unrelated replacement.
        objectManager.removeDynamicObject(segmentBefore);
        invokePrivate(pipe, "spawnPipeSegment", 8);

        rewindRegistry.restore(snapshot);

        CPZBossPipe restoredPipe = only(objectManager, CPZBossPipe.class);
        CPZBossPipeSegment restoredSegment = only(objectManager, CPZBossPipeSegment.class);
        List<?> segmentsAfter = readSegments(restoredPipe);

        assertEquals(1, segmentsAfter.size(),
                "the restored pipe must rebuild its segment list (one captured segment)");
        assertSame(restoredSegment, segmentsAfter.get(0),
                "the pipe's segment list must reference the restored segment instance");
        assertNotSame(segmentBefore, restoredSegment, "restore must recreate the segment fresh");
        assertSame(restoredPipe, readField(restoredSegment, "parentPipe"),
                "the restored segment's back-reference must resolve to the restored pipe");
    }

    private static ObjectManager createManagerWithBoss() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(BOSS_SPAWN), new Sonic2ObjectRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        // ROM Obj5D_Init is the boss's routine 0, executed from its own slot,
        // and it is what allocates the five child components
        // (docs/s2disasm/s2.asm:61628-61710). Drive that one execution here so
        // the graph under test is the one production builds.
        objectManager.getActiveObjects().stream()
                .filter(o -> o instanceof Sonic2CPZBossInstance)
                .forEach(o -> o.update(0, null));
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        return objectManager;
    }

    private static List<?> readSegments(CPZBossPipe pipe) throws Exception {
        return (List<?>) readField(pipe, "segments");
    }

    private static void invokePrivate(Object target, String method, int arg) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, int.class);
        m.setAccessible(true);
        m.invoke(target, arg);
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
