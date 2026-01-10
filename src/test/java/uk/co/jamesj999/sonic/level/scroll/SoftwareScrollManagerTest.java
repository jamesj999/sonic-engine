package uk.co.jamesj999.sonic.level.scroll;

import org.junit.Test;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.level.scroll.SoftwareScrollManager.*;

/**
 * Tests for 68000 arithmetic helpers in SoftwareScrollManager.
 * Verifies that Java operations match Motorola 68000 semantics.
 */
public class SoftwareScrollManagerTest {

    // ==================== wordOf tests ====================

    @Test
    public void testWordOfPositive() {
        // Word view of positive value within 16-bit range
        assertEquals((short) 100, wordOf(100));
        assertEquals((short) 32767, wordOf(32767));
    }

    @Test
    public void testWordOfNegative() {
        // Sign extension from low 16 bits
        assertEquals((short) -1, wordOf(0xFFFF));
        assertEquals((short) -100, wordOf(-100));
    }

    @Test
    public void testWordOfTruncation() {
        // Only low 16 bits should be preserved
        assertEquals((short) 0x1234, wordOf(0x00001234));
        assertEquals((short) 0x1234, wordOf(0xABCD1234));
    }

    // ==================== negWord tests ====================

    @Test
    public void testNegWordPositive() {
        // neg.w d0 where d0 = 100 -> -100
        assertEquals((short) -100, negWord(100));
    }

    @Test
    public void testNegWordNegative() {
        // neg.w d0 where d0 = -100 -> 100
        assertEquals((short) 100, negWord(-100));
    }

    @Test
    public void testNegWordZero() {
        assertEquals((short) 0, negWord(0));
    }

    @Test
    public void testNegWordCameraX() {
        // Common use case: negate camera X position
        int cameraX = 256;
        short result = negWord(cameraX);
        assertEquals((short) -256, result);
    }

    // ==================== asrWord tests ====================

    @Test
    public void testAsrWordPositive() {
        // asr.w #2,d0 where d0 = 100 -> 25
        assertEquals((short) 25, asrWord(100, 2));
    }

    @Test
    public void testAsrWordNegative() {
        // asr.w preserves sign bit (arithmetic shift)
        // -100 >> 2 = -25
        assertEquals((short) -25, asrWord(-100, 2));
    }

    @Test
    public void testAsrWordByZero() {
        assertEquals((short) 100, asrWord(100, 0));
        assertEquals((short) -100, asrWord(-100, 0));
    }

    @Test
    public void testAsrWordEhzBands() {
        // EHZ uses asr.w #6 and asr.w #4 for cloud/hill parallax
        int cameraX = 256;
        short d2 = negWord(cameraX); // -256

        // asr.w #6,d2 -> -4
        assertEquals((short) -4, asrWord(d2, 6));

        // asr.w #4,d2 -> -16
        assertEquals((short) -16, asrWord(d2, 4));
    }

    // ==================== divsWord tests ====================

    @Test
    public void testDivsWordPositive() {
        // divs.w #10,d0 where d0 = 100 -> 10
        assertEquals((short) 10, divsWord(100, 10));
    }

    @Test
    public void testDivsWordNegative() {
        // Signed division
        assertEquals((short) -10, divsWord(-100, 10));
        assertEquals((short) -10, divsWord(100, -10));
        assertEquals((short) 10, divsWord(-100, -10));
    }

    @Test
    public void testDivsWordByZero() {
        // Should return 0 (our safety behavior)
        assertEquals((short) 0, divsWord(100, 0));
    }

    @Test
    public void testDivsWordEhzGradient() {
        // EHZ uses divs.w #$30 (48) for gradient calculation
        assertEquals((short) 2, divsWord(96, 0x30));
    }

    // ==================== divuWord tests ====================

    @Test
    public void testDivuWordPositive() {
        assertEquals((short) 10, divuWord(100, 10));
    }

    @Test
    public void testDivuWordUnsigned() {
        // 0xFFFF (65535) / 256 = 255
        assertEquals((short) 255, divuWord(0xFFFF, 256));
    }

    @Test
    public void testDivuWordByZero() {
        assertEquals((short) 0, divuWord(100, 0));
    }

    // ==================== packScrollWords/unpack tests ====================

    @Test
    public void testPackScrollWords() {
        short fg = (short) -256;
        short bg = (short) -128;
        int packed = packScrollWords(fg, bg);

        // FG should be in high word, BG in low word
        assertEquals(fg, unpackFG(packed));
        assertEquals(bg, unpackBG(packed));
    }

    @Test
    public void testPackScrollWordsVdpFormat() {
        // Verify format matches VDP HScroll table: (PlaneA << 16) | PlaneB
        short planeA = (short) 0xFF00;
        short planeB = (short) 0x00FF;
        int packed = packScrollWords(planeA, planeB);

        // High word: 0xFF00, Low word: 0x00FF
        assertEquals(0xFF0000FF, packed);
    }

    @Test
    public void testUnpackPreservesSign() {
        short fg = (short) -1;
        short bg = (short) -2;
        int packed = packScrollWords(fg, bg);

        assertEquals((short) -1, unpackFG(packed));
        assertEquals((short) -2, unpackBG(packed));
    }

    // ==================== Integration test: EHZ band calculation
    // ====================

    @Test
    public void testEhzScrollCalculation() {
        // Simulate EHZ scroll math at camera X = 256
        int cameraX = 256;
        short d2 = negWord(cameraX); // FG scroll = -256

        // Band 1 (sky): BG = 0
        assertEquals((short) 0, (short) 0);

        // Band 2 (far clouds): BG = d2 >> 6 = -4
        assertEquals((short) -4, asrWord(d2, 6));

        // Band 5 (near hills): BG = d2 >> 4 = -16
        short band5Bg = asrWord(d2, 4);
        assertEquals((short) -16, band5Bg);

        // Band 6 (nearer hills): BG = (d2 >> 4) + ((d2 >> 4) >> 1) = -16 + (-8) = -24
        short band6Bg = (short) (band5Bg + asrWord(band5Bg, 1));
        assertEquals((short) -24, band6Bg);
    }
}
