package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.scroll.BackgroundCamera;
import com.openggf.game.sonic2.scroll.SwScrlHtz;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for HTZ earthquake scroll path parity.
 */
public class TestSwScrlHtzEarthquakeMode {

    private Sonic2LevelEventManager levelEvents;

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.resetAll();
        levelEvents = (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 1);
    }

    @Test
    public void usesEarthquakeModeWhenHtzFlagIsActiveEvenWithoutGeneralShake() throws Exception {
        levelEvents.getHtzEvents().setEarthquakeActive(true);
        GameServices.gameState().setScreenShakeActive(false);

        Object htzHandler = getHtzHandler(levelEvents);
        setPrivateInt(htzHandler, "cameraBgYOffset", 0x140);
        setPrivateInt(htzHandler, "htzCurrentBgXOffset", 0);

        SwScrlHtz handler = new SwScrlHtz(null, new BackgroundCamera());
        int[] hScroll = new int[224];
        handler.update(hScroll, 0x1800, 0x450, 10, 0);

        // BG scroll uses absolute Camera_BG positions:
        //   bgXPos = cameraX - bgXOffset = 0x1800 - 0 = 0x1800
        //   bgYPos = cameraY - bgYOffset = 0x450 - 0x140 = 0x310
        short expectedFg = (short) -0x1800;
        short expectedBg = (short) -0x1800;
        int expectedPacked = ((expectedFg & 0xFFFF) << 16) | (expectedBg & 0xFFFF);
        int[] expected = new int[224];
        java.util.Arrays.fill(expected, expectedPacked);

        assertArrayEquals(expected, hScroll);
        assertEquals(0x450, handler.getVscrollFactorFG() & 0xFFFF);
        assertEquals(0x450 - 0x140, handler.getVscrollFactorBG() & 0xFFFF);
        assertEquals(0, handler.getShakeOffsetX());
        assertEquals(0, handler.getShakeOffsetY());
    }

    @Test
    public void bottomRouteBgXOffsetAffectsBgHorizontalScroll() throws Exception {
        levelEvents.getHtzEvents().setEarthquakeActive(true);
        GameServices.gameState().setScreenShakeActive(false);

        Object htzHandler = getHtzHandler(levelEvents);
        setPrivateInt(htzHandler, "cameraBgYOffset", 0x300);
        setPrivateInt(htzHandler, "htzCurrentBgXOffset", -0x680);

        SwScrlHtz handler = new SwScrlHtz(null, new BackgroundCamera());
        int[] hScroll = new int[224];
        handler.update(hScroll, 0x1600, 0x400, 12, 1);

        // bgXPos = cameraX - bgXOffset = 0x1600 - (-0x680) = 0x1C80
        int bgXPos = 0x1600 - (-0x680);
        short expectedFg = (short) -0x1600;
        short expectedBg = (short) -bgXPos;
        int expectedPacked = ((expectedFg & 0xFFFF) << 16) | (expectedBg & 0xFFFF);
        assertEquals(expectedPacked, hScroll[0]);
        assertEquals(expectedPacked, hScroll[223]);
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

}


