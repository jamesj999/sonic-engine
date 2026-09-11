package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.game.sonic2.objects.RisingLavaObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression checks for HTZ rising lava behavior against s2.asm.
 */
public class TestHTZRisingLavaDisassemblyParity {

    private Camera camera;
    private Sonic2LevelEventManager levelEvents;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
        com.openggf.game.GameModuleRegistry.reset();
        com.openggf.game.GameModuleRegistry.setCurrent(new com.openggf.game.sonic2.Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        GameServices.gameState().resetSession();

        camera = GameServices.camera();
        levelEvents = (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void act1OscillationStartsAt1978NotBefore() throws Exception {
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);
        levelEvents.setEventRoutine(2);
        levelEvents.getHtzEvents().setEarthquakeActive(true);

        camera.setX((short) 0x1900); // < $1978, should not oscillate
        camera.setY((short) 0x410);
        Object htzHandler = getHtzHandler(levelEvents);
        setPrivateInt(htzHandler, "cameraBgYOffset", 300);
        setPrivateBoolean(htzHandler, "htzTerrainSinking", false);
        setPrivateInt(htzHandler, "htzTerrainDelay", 0);

        for (int i = 0; i < 16; i++) {
            levelEvents.update();
        }
        assertEquals(300, htzState().cameraBgYOffset());

        camera.setX((short) 0x1980); // >= $1978, oscillation now allowed
        for (int i = 0; i < 8; i++) {
            levelEvents.update();
        }
        assertTrue(htzState().cameraBgYOffset() > 300);
    }

    @Test
    public void act2BottomRouteInitialYOffsetIs300() throws Exception {
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 1);
        camera.setX((short) 0x14C0);
        camera.setY((short) 0x380);

        levelEvents.update();

        assertEquals(8, levelEvents.getEventRoutine());
        assertEquals(0x300, htzState().cameraBgYOffset());
        assertEquals(-0x680, htzState().cameraBgXOffset());
        assertTrue(htzState().earthquakeActive());
        assertEquals(0, htzState().bgVerticalShift());

        Object htzHandler = getHtzHandler(levelEvents);
        setPrivateInt(htzHandler, "cameraBgYOffset", 0x2F0);
        assertEquals(0x10, htzState().bgVerticalShift());
    }

    @Test
    public void obj30Subtype6And8FollowRouteSplitAt380() {
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 0);
        levelEvents.getHtzEvents().setEarthquakeActive(true);
        // Wire in levelEvents so that update() can read HTZ runtime state for
        // enabled objects without hitting a NullPointerException.
        ObjectServices services = new TestObjectServices() {
            @Override
            public com.openggf.game.LevelEventProvider levelEventProvider() {
                return levelEvents;
            }
        }.withCamera(camera)
                .withGameState(GameServices.gameState())
                .withZoneRuntimeRegistry(GameServices.zoneRuntimeRegistry());

        camera.setY((short) 0x200); // top route
        setConstructionContext(services);
        RisingLavaObjectInstance subtype6Top;
        RisingLavaObjectInstance subtype8Top;
        try {
            subtype6Top = new RisingLavaObjectInstance(spawnWithSubtype(6), "Obj30_6_top");
            subtype8Top = new RisingLavaObjectInstance(spawnWithSubtype(8), "Obj30_8_top");
        } finally {
            clearConstructionContext();
        }
        subtype6Top.setServices(services);
        subtype8Top.setServices(services);
        // update() triggers ensureInitialized(), which reads camera Y to set routeEnabled
        subtype6Top.update(0, null);
        subtype8Top.update(0, null);
        assertFalse(subtype6Top.isSolidFor(null));
        assertTrue(subtype8Top.isSolidFor(null));

        camera.setY((short) 0x400); // bottom route
        setConstructionContext(services);
        RisingLavaObjectInstance subtype6Bottom;
        RisingLavaObjectInstance subtype8Bottom;
        try {
            subtype6Bottom = new RisingLavaObjectInstance(spawnWithSubtype(6), "Obj30_6_bottom");
            subtype8Bottom = new RisingLavaObjectInstance(spawnWithSubtype(8), "Obj30_8_bottom");
        } finally {
            clearConstructionContext();
        }
        subtype6Bottom.setServices(services);
        subtype8Bottom.setServices(services);
        // update() triggers ensureInitialized(), which reads camera Y to set routeEnabled
        subtype6Bottom.update(0, null);
        subtype8Bottom.update(0, null);
        assertTrue(subtype6Bottom.isSolidFor(null));
        assertFalse(subtype8Bottom.isSolidFor(null));
    }

    private static ObjectSpawn spawnWithSubtype(int subtype) {
        return new ObjectSpawn(0x1800, 0x420, 0x30, subtype, 0, false, 0);
    }

    private static Object getHtzHandler(Object manager) throws Exception {
        Field f = manager.getClass().getDeclaredField("htzEvents");
        f.setAccessible(true);
        return f.get(manager);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static HtzRuntimeState htzState() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .orElseThrow(() -> new AssertionError("Expected HTZ runtime state to be installed"));
    }
    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}


