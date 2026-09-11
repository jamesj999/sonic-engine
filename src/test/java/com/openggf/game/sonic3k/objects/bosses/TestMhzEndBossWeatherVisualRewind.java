package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMhzEndBossWeatherVisualRewind {
    private static final int[][] SPARK_OFFSETS = {
            {-0x4D, -0x4E},
            {-0xA1, -0xA2},
            {-0xF5, -0xF6},
            {-0x149, -0x14A}
    };

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void sparkFramesRestoreRomByte769CEQuartet() throws ReflectiveOperationException {
        Field field = MhzEndBossWeatherVisualChild.class.getDeclaredField("SPARK_FRAMES");
        field.setAccessible(true);
        int[] sparkFrames = (int[]) field.get(null);

        assertArrayEquals(new int[]{0x0D, 0x0E, 0x0F, 0x10}, sparkFrames,
                "byte_769CE (sonic3k.asm:157786-157787) is [1,$D,$E,$F,$10,$FC]; the initial mapping "
                        + "frame ($D) plus the three Animate_Raw ticks ($E,$F,$10) form the full quartet, "
                        + "so the frame array must not drop $D");
    }

    @ParameterizedTest
    @CsvSource({
            "false,1,17767,689,2,5,10",
            "true,2,16675,500,1,1,15"
    })
    void weatherVisualRestoresThroughGenericRecreateWithLiveWeatherMachineParent(
            boolean spark,
            int subtype,
            int capturedX,
            int capturedY,
            int capturedFrameIndex,
            int capturedFrameTimer,
            int capturedMappingFrame) throws Exception {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossWeatherVisualChild.class.getName()),
                "MhzEndBossWeatherVisualChild must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");

        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedBossSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedBoss = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedBossSpawn));
        MhzEndBossWeatherMachineChild capturedWeather = objectManager.createDynamicObject(
                () -> new MhzEndBossWeatherMachineChild(capturedBoss));
        MhzEndBossWeatherVisualChild capturedVisual = objectManager.createDynamicObject(
                () -> weatherVisual(capturedWeather, subtype, spark));
        writeInt(capturedVisual, "x", capturedX);
        writeInt(capturedVisual, "y", capturedY);
        writeInt(capturedVisual, "frameIndex", capturedFrameIndex);
        writeInt(capturedVisual, "frameTimer", capturedFrameTimer);
        writeInt(capturedVisual, "mappingFrame", capturedMappingFrame);
        capturedVisual.update(0, null);
        writeInt(capturedVisual, "x", capturedX);
        writeInt(capturedVisual, "y", capturedY);
        writeInt(capturedVisual, "frameIndex", capturedFrameIndex);
        writeInt(capturedVisual, "frameTimer", capturedFrameTimer);
        writeInt(capturedVisual, "mappingFrame", capturedMappingFrame);

        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherMachineChild.class).size(),
                "precondition: exactly one captured weather machine is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherVisualChild.class).size(),
                "precondition: exactly one captured weather visual is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedWeatherId = captureTable.idFor(capturedWeather);
        ObjectRefId capturedVisualId = captureTable.idFor(capturedVisual);
        assertNotNull(capturedWeatherId, "ObjectManager capture identity table must register the weather machine");
        assertNotNull(capturedVisualId, "ObjectManager capture identity table must register the weather visual");
        ObjectSpawn capturedVisualSpawn = capturedVisual.getSpawn();
        assertEquals(subtype, capturedVisualSpawn.subtype(),
                "precondition: captured dynamic spawn must encode the visual subtype");
        assertEquals(spark, readBoolean(capturedVisual, "spark"),
                "precondition: captured visual must use the requested constructor branch");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        removeAllActiveObjects(objectManager);
        MhzEndBossInstance divergentBoss = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossWeatherMachineChild divergentWeather = objectManager.createDynamicObject(
                () -> new MhzEndBossWeatherMachineChild(divergentBoss));
        MhzEndBossWeatherVisualChild divergentVisual = objectManager.createDynamicObject(
                () -> weatherVisual(divergentWeather, spark ? 0 : 1, !spark));
        writeInt(divergentVisual, "x", 0x1111);
        writeInt(divergentVisual, "y", 0x0222);
        writeInt(divergentVisual, "frameIndex", 0);
        writeInt(divergentVisual, "frameTimer", 0);
        writeInt(divergentVisual, "mappingFrame", 0);
        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherMachineChild.class).size(),
                "diverge step should leave one unrelated weather machine before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherVisualChild.class).size(),
                "diverge step should leave one unrelated weather visual before restore");

        registry.restore(snapshot);

        MhzEndBossWeatherMachineChild restoredWeather =
                singleLiveObject(objectManager, MhzEndBossWeatherMachineChild.class);
        MhzEndBossWeatherVisualChild restoredVisual =
                singleLiveObject(objectManager, MhzEndBossWeatherVisualChild.class);
        assertFalse(restoredWeather == capturedWeather,
                "restore should not retain the removed captured weather-machine instance");
        assertFalse(restoredWeather == divergentWeather,
                "restore should replace divergent weather machines with the captured snapshot entry");
        assertFalse(restoredVisual == capturedVisual,
                "restore should not retain the removed captured visual instance");
        assertFalse(restoredVisual == divergentVisual,
                "restore should replace divergent weather visuals with the captured snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedWeatherId, restoredTable.idFor(restoredWeather),
                "restored weather machine must retain the captured ObjectManager rewind identity");
        assertEquals(capturedVisualId, restoredTable.idFor(restoredVisual),
                "restored visual must retain the captured ObjectManager rewind identity");
        assertSame(restoredWeather, readParent(restoredVisual),
                "restored visual must relink to the restored weather-machine parent");

        assertEquals(subtype, readInt(restoredVisual, "subtype"),
                "restored visual must re-derive subtype from the captured spawn");
        assertEquals(spark, readBoolean(restoredVisual, "spark"),
                "restored visual must re-derive spark from the captured spawn");
        assertEquals(capturedVisualSpawn.subtype(), restoredVisual.getSpawn().subtype(),
                "restored dynamic spawn must keep the captured subtype");
        assertEquals((capturedVisualSpawn.rawYWord() & 1) != 0,
                (restoredVisual.getSpawn().rawYWord() & 1) != 0,
                "restored dynamic spawn must keep the captured spark discriminator");

        assertEquals(capturedX, readInt(restoredVisual, "x"),
                "restored visual must retain captured X before the next update");
        assertEquals(capturedY, readInt(restoredVisual, "y"),
                "restored visual must retain captured Y before the next update");
        assertEquals(capturedFrameIndex, readInt(restoredVisual, "frameIndex"),
                "restored visual must retain captured animation frame index");
        assertEquals(capturedFrameTimer, readInt(restoredVisual, "frameTimer"),
                "restored visual must retain captured animation frame timer");
        assertEquals(capturedMappingFrame, readInt(restoredVisual, "mappingFrame"),
                "restored visual must retain captured mapping frame");

        writeInt(restoredWeather, "x", 0x4800);
        writeInt(restoredWeather, "y", 0x0380);
        restoredVisual.update(1, null);
        assertEquals(expectedVisualX(restoredWeather, subtype, spark), restoredVisual.getX(),
                "restored visual X must derive from the restored weather-machine parent after update");
        assertEquals(expectedVisualY(restoredWeather, subtype, spark), restoredVisual.getY(),
                "restored visual Y must derive from the restored weather-machine parent after update");

        restoredWeather.setDestroyed(true);
        restoredVisual.update(2, null);
        assertTrue(restoredVisual.isDestroyed(),
                "restored weather visual must destroy itself when its restored parent is destroyed");
    }

    private static ObjectManager installObjectManager() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
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
        return objectManager;
    }

    private static MhzEndBossWeatherVisualChild weatherVisual(
            MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        return spark
                ? MhzEndBossWeatherVisualChild.spark(parent, subtype)
                : MhzEndBossWeatherVisualChild.animatedPart(parent, subtype);
    }

    private static int expectedVisualX(MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        return parent.getX() + (spark ? SPARK_OFFSETS[subtype][0] : 0);
    }

    private static int expectedVisualY(MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        return parent.getY() + (spark ? SPARK_OFFSETS[subtype][1] : 0);
    }

    private static void removeAllActiveObjects(ObjectManager objectManager) {
        List.copyOf(objectManager.getActiveObjects()).forEach(objectManager::removeDynamicObject);
    }

    private static MhzEndBossWeatherMachineChild readParent(MhzEndBossWeatherVisualChild visual)
            throws ReflectiveOperationException {
        Field field = MhzEndBossWeatherVisualChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossWeatherMachineChild) field.get(visual);
    }

    private static int readInt(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void writeInt(Object target, String fieldName, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static boolean readBoolean(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static <T> T singleLiveObject(ObjectManager objectManager, Class<T> type) {
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
