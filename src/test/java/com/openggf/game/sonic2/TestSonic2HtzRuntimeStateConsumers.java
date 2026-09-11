package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.RisingLavaObjectInstance;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.game.sonic2.scroll.BackgroundCamera;
import com.openggf.game.sonic2.scroll.SwScrlHtz;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestSonic2HtzRuntimeStateConsumers {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void swScrlHtzUsesTypedRuntimeStateOffsetsInEarthquakeMode() {
        GameServices.zoneRuntimeRegistry().install(new StubHtzRuntimeState(0, 1, true, 0x140, -0x680, 0));
        GameServices.gameState().setScreenShakeActive(false);

        SwScrlHtz handler = new SwScrlHtz(null, new BackgroundCamera());
        int[] hScroll = new int[224];

        handler.update(hScroll, 0x1600, 0x400, 12, 1);

        int bgXPos = 0x1600 - (-0x680);
        short expectedFg = (short) -0x1600;
        short expectedBg = (short) -bgXPos;
        int expectedPacked = ((expectedFg & 0xFFFF) << 16) | (expectedBg & 0xFFFF);
        assertEquals(expectedPacked, hScroll[0]);
        assertEquals(expectedPacked, hScroll[223]);
        assertEquals(0x400, handler.getVscrollFactorFG() & 0xFFFF);
        assertEquals(0x2C0, handler.getVscrollFactorBG() & 0xFFFF);
    }

    @Test
    void risingLavaUsesTypedRuntimeStateYOffset() {
        GameServices.zoneRuntimeRegistry().install(new StubHtzRuntimeState(0, 0, true, 0x120, 0, 0));

        ObjectServices services = new TestObjectServices()
                .withCamera(GameServices.camera())
                .withGameState(GameServices.gameState())
                .withZoneRuntimeRegistry(GameServices.zoneRuntimeRegistry());

        setConstructionContext(services);
        RisingLavaObjectInstance object;
        try {
            object = new RisingLavaObjectInstance(new ObjectSpawn(0x1800, 0x420, 0x30, 0, 0, false, 0), "Obj30");
        } finally {
            clearConstructionContext();
        }
        object.setServices(services);

        object.update(0, null);

        assertEquals(0x420 + 0x120, object.getY());
    }

    @Test
    void sonic2LevelEventManagerNoLongerExposesHtzSpecificGetterMethods() {
        String source = readSource("src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java");

        assertFalse(source.contains("getCameraBgYOffset("));
        assertFalse(source.contains("getHtzBgXOffset("));
        assertFalse(source.contains("getHtzBgVerticalShift("));
    }

    private static String readSource(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new AssertionError("Failed to read source: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(services);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to install object construction context", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to clear object construction context", e);
        }
    }

    private record StubHtzRuntimeState(
            int zoneIndex,
            int actIndex,
            boolean earthquakeActive,
            int cameraBgYOffset,
            int cameraBgXOffset,
            int bgVerticalShift) implements HtzRuntimeState {

        @Override
        public String gameId() {
            return HtzRuntimeState.GAME_ID;
        }
    }
}
