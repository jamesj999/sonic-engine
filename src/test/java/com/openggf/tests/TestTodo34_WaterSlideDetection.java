package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

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
    public void testReverseSearchLogic() {
        // The game searches Slide_Chunks backward (from end to start).
        // After the search, d1 contains the index into Slide_Chunks/Slide_Speeds.
        //
        // LZWaterFeatures.asm:404-410:
        //   lea Slide_Chunks_End(pc),a2
        //   moveq #Slide_Chunks_End-Slide_Chunks-1,d1   ; d1 = 6 (count-1)
        //   loc_3F62:
        //   cmp.b -(a2),d0
        //   dbeq d1,loc_3F62
        //   beq.s LZSlide_Move
        //
        // Because the search goes backward, d1=6 checks the last entry first (chunk $04),
        // d1=5 checks chunk $08, etc. When a match is found, d1 is the array index.
        int searchCount = EXPECTED_SLIDE_CHUNK_IDS.length - 1; // 6 (initial d1 value)
        assertEquals(6, searchCount, "Initial d1 should be Slide_Chunks length - 1");

        // Verify a sample reverse search: looking for chunk $4B
        int targetChunk = 0x4B;
        int foundIndex = -1;
        // Simulate the dbeq loop (reverse search)
        for (int d1 = searchCount; d1 >= 0; d1--) {
            if (EXPECTED_SLIDE_CHUNK_IDS[d1] == targetChunk) {
                foundIndex = d1;
                break;
            }
        }
        assertEquals(4, foundIndex, "Chunk $4B should be found at index 4");
        assertEquals(-11, EXPECTED_SLIDE_SPEEDS[foundIndex], "Speed for chunk $4B should be -11");
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

    @Test
    public void testSlideChunkAndSpeedTablesHaveSameLength() throws Exception {
        int[] chunkIds = getPrivateStaticIntArray("SLIDE_CHUNK_IDS");
        int[] speeds = getPrivateStaticIntArray("SLIDE_SPEEDS");
        assertEquals(7, chunkIds.length, "SLIDE_CHUNK_IDS and SLIDE_SPEEDS must have the same length");
        assertEquals(chunkIds.length, speeds.length, "SLIDE_CHUNK_IDS and SLIDE_SPEEDS must have the same length");
    }
}


