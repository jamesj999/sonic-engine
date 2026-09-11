package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2HtzRuntimeStateRegistration {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void htzInstallsTypedRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);

        Object state = currentRegistry().currentAs(htzRuntimeStateType()).orElseThrow();
        assertEquals("s2", invokeString(state, "gameId"));
        assertEquals(Sonic2LevelEventManager.ZONE_HTZ, invokeInt(state, "zoneIndex"));
        assertEquals(0, invokeInt(state, "actIndex"));
    }

    @Test
    void nonHtzDoesNotInstallHtzRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_CNZ, 0);

        assertTrue(currentRegistry().currentAs(htzRuntimeStateType()).isEmpty());
    }

    @Test
    void htzStateClearsWhenLeavingHtz() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);
        manager.initLevel(Sonic2LevelEventManager.ZONE_CNZ, 0);

        assertTrue(currentRegistry().currentAs(htzRuntimeStateType()).isEmpty());
    }

    @Test
    void nonHtzInitDoesNotClearUnrelatedRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        ZoneRuntimeState sentinel = new ZoneRuntimeState() {
            @Override
            public String gameId() {
                return "test";
            }

            @Override
            public int zoneIndex() {
                return 99;
            }

            @Override
            public int actIndex() {
                return 7;
            }
        };
        currentRegistry().install(sentinel);

        manager.initLevel(Sonic2LevelEventManager.ZONE_CNZ, 0);

        assertSame(sentinel, currentRegistry().current());
    }

    @Test
    void htzInitDoesNotReplaceCustomHtzRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        HtzRuntimeState custom = new CustomHtzRuntimeState(88, 3);
        currentRegistry().install(custom);

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);

        assertSame(custom, currentRegistry().current());
    }

    @Test
    void nonHtzInitDoesNotClearCustomHtzRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        HtzRuntimeState custom = new CustomHtzRuntimeState(89, 4);
        currentRegistry().install(custom);

        manager.initLevel(Sonic2LevelEventManager.ZONE_EHZ, 0);

        assertSame(custom, currentRegistry().current());
    }

    @Test
    void adapterReflectsUnderlyingEventValues() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);

        Object state = currentRegistry()
                .currentAs(htzRuntimeStateType())
                .orElseThrow();

        assertFalse(invokeBoolean(state, "earthquakeActive"));
        assertEquals(0, invokeInt(state, "cameraBgYOffset"));
        assertEquals(0, invokeInt(state, "cameraBgXOffset"));
        assertEquals(0, invokeInt(state, "bgVerticalShift"));
    }

    @Test
    void htzActSwitchRefreshesInstalledMetadata() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);
        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 1);

        Object state = currentRegistry().currentAs(htzRuntimeStateType()).orElseThrow();
        assertEquals(1, invokeInt(state, "actIndex"));
    }

    @Test
    void resetStateClearsInstalledHtzRuntimeState() {
        requireRuntime();
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);
        manager.resetState();

        assertTrue(currentRegistry().currentAs(htzRuntimeStateType()).isEmpty());
    }

    private static void requireRuntime() {
        assertTrue(GameServices.hasRuntime(),
                "Expected TestEnvironment.resetAll() to create a gameplay mode");
    }

    private static ZoneRuntimeRegistry currentRegistry() {
        return GameServices.zoneRuntimeRegistry();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ZoneRuntimeState> htzRuntimeStateType() {
        try {
            return (Class<? extends ZoneRuntimeState>) Class
                    .forName("com.openggf.game.sonic2.runtime.HtzRuntimeState")
                    .asSubclass(ZoneRuntimeState.class);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Expected com.openggf.game.sonic2.runtime.HtzRuntimeState to exist", e);
        }
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        Object result = invoke(target, methodName);
        assertTrue(result instanceof Boolean, methodName + " should return a boolean");
        return (Boolean) result;
    }

    private static int invokeInt(Object target, String methodName) {
        Object result = invoke(target, methodName);
        assertTrue(result instanceof Integer, methodName + " should return an int");
        return (Integer) result;
    }

    private static String invokeString(Object target, String methodName) {
        Object result = invoke(target, methodName);
        assertTrue(result instanceof String, methodName + " should return a string");
        return (String) result;
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("Expected HTZ runtime state method " + methodName, e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("HTZ runtime state method " + methodName + " threw", e.getCause());
        }
    }

    private record CustomHtzRuntimeState(int zoneIndex, int actIndex) implements HtzRuntimeState {
        @Override
        public boolean earthquakeActive() {
            return false;
        }

        @Override
        public int cameraBgYOffset() {
            return 0;
        }

        @Override
        public int cameraBgXOffset() {
            return 0;
        }

        @Override
        public int bgVerticalShift() {
            return 0;
        }
    }
}
