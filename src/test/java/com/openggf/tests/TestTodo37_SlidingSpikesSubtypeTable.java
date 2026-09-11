package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verify SlidingSpikesObjectInstance constants match the ROM's Obj76_InitData.
 * ROM reference: s2.asm lines 55242-55245
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestTodo37_SlidingSpikesSubtypeTable {
    private static final int OBJ76_INIT_DATA_ADDR = 0x28E0A;

    @Test
    public void testEngineWidthMatchesRom() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int romWidth = rom.readByte(OBJ76_INIT_DATA_ADDR) & 0xFF;

        int engineWidth = getPrivateStaticInt(
                "com.openggf.game.sonic2.objects.SlidingSpikesObjectInstance",
                "WIDTH_PIXELS");
        assertEquals(romWidth, engineWidth, "Engine WIDTH_PIXELS should match ROM Obj76_InitData[0]");
    }

    @Test
    public void testEngineYRadiusMatchesRom() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int romYRadius = rom.readByte(OBJ76_INIT_DATA_ADDR + 1) & 0xFF;

        int engineYRadius = getPrivateStaticInt(
                "com.openggf.game.sonic2.objects.SlidingSpikesObjectInstance",
                "Y_RADIUS");
        assertEquals(romYRadius, engineYRadius, "Engine Y_RADIUS should match ROM Obj76_InitData[1]");
    }

    private static int getPrivateStaticInt(String className, String fieldName)
            throws Exception {
        Class<?> clazz = Class.forName(className);
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }
}


