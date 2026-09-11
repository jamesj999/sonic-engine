package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMhzEndBossArenaHelperRewind {

    @Test
    void spikeHelperRestoresThroughGenericRecreateWithLiveMhzEventsOwner() throws Exception {
        Sonic3kMHZEvents liveEvents = new Sonic3kMHZEvents();
        Sonic3kLevelEventManager eventManager = new Sonic3kLevelEventManager();
        set(eventManager, "mhzEvents", liveEvents);
        assertSame(liveEvents, eventManager.getMhzEvents(),
                "precondition: restore-time level event manager exposes the live MHZ events owner");
        configureSpikeEventState(liveEvents, true, 0x0450);

        ObjectManager objectManager = installObjectManager(eventManager);
        MhzEndBossArenaHelperInstance captured = objectManager.createDynamicObject(
                () -> MhzEndBossArenaHelperInstance.spike(liveEvents, 2, 1, true));
        captured.update(0, null);
        assertEquals(0x8B, captured.getCollisionFlags(),
                "precondition: captured spike helper reads the live event active/Y arrays");
        assertEquals(1, liveHelpers(objectManager).size(),
                "precondition: exactly one helper is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedId = captureTable.idFor(captured);
        assertNotNull(capturedId, "ObjectManager capture identity table must register the dynamic helper");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(captured);
        MhzEndBossArenaHelperInstance divergent = objectManager.createDynamicObject(
                () -> MhzEndBossArenaHelperInstance.pillar(liveEvents));
        assertEquals(1, liveHelpers(objectManager).size(),
                "diverge step should leave one unrelated helper before restore");

        registry.restore(snapshot);

        List<MhzEndBossArenaHelperInstance> restoredHelpers = liveHelpers(objectManager);
        assertEquals(1, restoredHelpers.size(),
                "restore must recreate the captured helper exactly once, not drop or double it");
        MhzEndBossArenaHelperInstance restored = restoredHelpers.getFirst();
        assertFalse(restored == captured,
                "restore should not retain the removed captured helper instance");
        assertFalse(restored == divergent,
                "restore should replace divergent live dynamics with the captured helper snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedId, restoredTable.idFor(restored),
                "restored helper must retain the captured dynamic rewind identity");
        assertEquals(MhzEndBossArenaHelperInstance.Role.SPIKE, restored.getRole(),
                "generic scalar restore must reapply the captured helper role");
        assertEquals(2, readIntField(restored, "spikeIndex"),
                "generic scalar restore must reapply spikeIndex");
        assertEquals(1, restored.getSpikeTier(),
                "generic scalar restore must reapply spikeTier");
        assertTrue(restored.isAlternateSide(),
                "generic scalar restore must reapply alternateSide");
        assertSame(liveEvents, readEventsOwner(restored),
                "restored helper must relink to the live Sonic3kMHZEvents owner from ObjectServices");

        restored.update(1, null);
        assertEquals(0x8B, restored.getCollisionFlags(),
                "restored spike helper must read the live event arrays after recreate");
        configureSpikeEventState(liveEvents, false, 0x0450);
        restored.update(2, null);
        assertEquals(0, restored.getCollisionFlags(),
                "restored spike helper must keep using the live MHZ events owner after restore");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        MhzEndBossArenaHelperInstance.class.getName()),
                "MhzEndBossArenaHelperInstance must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
    }

    private static ObjectManager installObjectManager(Sonic3kLevelEventManager eventManager) {
        GraphicsManager.getInstance().initHeadless();
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public LevelEventProvider levelEventProvider() { return eventManager; }
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
        return objectManager;
    }

    private static List<MhzEndBossArenaHelperInstance> liveHelpers(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == MhzEndBossArenaHelperInstance.class && !object.isDestroyed())
                .map(MhzEndBossArenaHelperInstance.class::cast)
                .toList();
    }

    private static void configureSpikeEventState(Sonic3kMHZEvents events, boolean active, int y)
            throws ReflectiveOperationException {
        set(events, "endBossArenaForegroundRefreshActive", true);
        set(events, "endBossArenaSpikeDeletionFlag", false);
        set(events, "endBossArenaSpikeActive", new boolean[] { false, false, active, false, false, false });
        set(events, "endBossArenaSpikeY", new int[] { 0, 0, y, 0, 0, 0 });
    }

    private static Sonic3kMHZEvents readEventsOwner(MhzEndBossArenaHelperInstance helper)
            throws ReflectiveOperationException {
        Field field = MhzEndBossArenaHelperInstance.class.getDeclaredField("events");
        field.setAccessible(true);
        return (Sonic3kMHZEvents) field.get(helper);
    }

    private static int readIntField(Object target, String fieldName)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void set(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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
