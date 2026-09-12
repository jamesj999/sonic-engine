package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Water slide chunk detection in Labyrinth Zone.
 *
 * <p>The LZ water slide system detects whether Sonic is standing on a slide chunk
 * by looking up the current level layout chunk ID and comparing it against a
 * table of 7 known slide chunk IDs. Each chunk ID has a corresponding speed
 * value that determines Sonic's slide direction and velocity.
 *
 * <p>Disassembly references:
 * <ul>
 *   <li>{@code docs/s1disasm/_inc/LZWaterFeatures.asm:392-457} -
 *       {@code LZWaterSlides} subroutine</li>
 *   <li>{@code docs/s1disasm/_inc/LZWaterFeatures.asm:454-457} -
 *       {@code Slide_Chunks} table: chunk IDs that trigger sliding</li>
 *   <li>{@code docs/s1disasm/_inc/LZWaterFeatures.asm:450-452} -
 *       {@code Slide_Speeds} table: per-chunk speed values</li>
 * </ul>
 *
 * <p>The chunk lookup works by computing a level layout index from Sonic's
 * position:
 * <pre>
 *   d0 = (obY >> 1) & $380    ; row index (128-byte stride)
 *   d1 = obX.byte & $7F       ; column index within row
 *   chunkId = v_lvllayout[d0 + d1]
 * </pre>
 * Then the chunk ID is compared against Slide_Chunks in reverse order.
 */
public class TestTodo34_WaterSlideDetection {

    /**
     * Water slide chunk IDs from LZWaterFeatures.asm:454-455.
     * {@code Slide_Chunks: dc.b 2, 7, 3, $4C, $4B, 8, 4}
     */
    private static final int[] EXPECTED_SLIDE_CHUNK_IDS = {
            0x02, 0x07, 0x03, 0x4C, 0x4B, 0x08, 0x04
    };

    /**
     * Corresponding slide speeds from LZWaterFeatures.asm:450-451.
     * {@code Slide_Speeds: dc.b 10, -11, 10, -10, -11, -12, 11}
     */
    private static final int[] EXPECTED_SLIDE_SPEEDS = {
            10, -11, 10, -10, -11, -12, 11
    };

    /**
     * Reads a private static int[] field from Sonic1LZWaterEvents via reflection.
     */
    private static int[] getPrivateStaticIntArray(String fieldName) throws Exception {
        Class<?> clazz = Class.forName(
                "com.openggf.game.sonic1.events.Sonic1LZWaterEvents");
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    @Test
    public void testSlideChunkIdsMatchRom() throws Exception {
        int[] actual = getPrivateStaticIntArray("SLIDE_CHUNK_IDS");
        assertArrayEquals(EXPECTED_SLIDE_CHUNK_IDS, actual, "SLIDE_CHUNK_IDS must match disassembly Slide_Chunks table");
    }

    @Test
    public void testSlideSpeedsMatchRom() throws Exception {
        int[] actual = getPrivateStaticIntArray("SLIDE_SPEEDS");
        assertArrayEquals(EXPECTED_SLIDE_SPEEDS, actual, "SLIDE_SPEEDS must match disassembly Slide_Speeds table");
    }
}

