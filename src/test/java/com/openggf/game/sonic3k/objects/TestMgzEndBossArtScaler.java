package com.openggf.game.sonic3k.objects;

import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Golden vectors for the add/addx stepping in skdisasm sub_246DA. */
class TestMgzEndBossArtScaler {
    private static final SpriteMappingFrame FOUR_BY_FOUR = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(0, 0, 4, 4, 0, false, false, 1)));
    private static final SpriteMappingFrame ROM_FRAME_FOUR = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(0, 0, 4, 4, 0, false, false, 1),
            new SpriteMappingPiece(32, 0, 4, 4, 16, false, false, 1),
            new SpriteMappingPiece(0, 32, 4, 4, 32, false, false, 1),
            new SpriteMappingPiece(32, 32, 4, 4, 48, false, false, 1)));

    @Test
    void scaleFourMatchesNativeFractionalVerticalAndHorizontalSteps() {
        Pattern[] output = patterns(16);
        MgzEndBossArtScaler.scale(sourceRamp(), 4, FOUR_BY_FOUR, output);
        assertEquals(0, output[0].getPixel(0, 0));
        assertEquals(2, output[0].getPixel(1, 0));
        assertEquals(3, output[0].getPixel(0, 1)); // y advances by 4/8
        assertEquals(0x7151FFB8L, crc(output));
    }

    @Test
    void scaleTwelveMatchesNativePackedNibbleDecimation() {
        Pattern[] output = patterns(16);
        MgzEndBossArtScaler.scale(sourceRamp(), 12, FOUR_BY_FOUR, output);
        assertEquals(4, output[0].getPixel(1, 0)); // x advances by (12+4)/4
        assertEquals(6, output[0].getPixel(0, 1)); // y advances by (12+4)/8
        assertEquals(0x0ADF0CE0L, crc(output));
    }

    @Test
    void productionScalerMatchesIndependentAddWithCarryTranslation() {
        byte[] source = sourceRamp();
        for (int step : new int[]{4, 8, 12, 28}) {
            Pattern[] actual = patterns(16);
            MgzEndBossArtScaler.scale(source, step, FOUR_BY_FOUR, actual);
            assertEquals(crc(referenceAddWithCarry(source, step)), crc(actual), "step " + step);
        }
    }

    /** Test-only translation of sub_246DA's repeated ADD/ADDX accumulators. */
    private static Pattern[] referenceAddWithCarry(byte[] source, int step) {
        Pattern[] output = patterns(16);
        int increment = (step & 0x7F) + 4;
        for (int oy = 0; oy < 32; oy++) {
            int yAccumulator = 0;
            for (int i = 0; i < oy; i++) yAccumulator += increment;
            int sy = yAccumulator >>> 3;
            for (int ox = 0; ox < 32; ox++) {
                int xAccumulator = 0;
                for (int i = 0; i < ox; i++) xAccumulator += increment;
                int sx = xAccumulator >>> 2;
                int pixel = 0;
                if (sx < 0x80 && sy < 0x40) {
                    int packed = Byte.toUnsignedInt(source[sy * 0x40 + sx / 2]);
                    pixel = (sx & 1) == 0 ? packed >>> 4 : packed & 0xF;
                }
                int tile = (ox >>> 3) * 4 + (oy >>> 3);
                output[tile].setPixel(ox & 7, oy & 7, (byte) pixel);
            }
        }
        return output;
    }

    @Test
    void verifiedRomRasterMatchesRepresentativeGoldenCrcs() throws Exception {
        String romPath = System.getProperty("s3k.rom.path");
        assumeTrue(romPath != null && Files.isRegularFile(Path.of(romPath)));
        byte[] rom = Files.readAllBytes(Path.of(romPath));
        byte[] source = java.util.Arrays.copyOfRange(rom, 0x36C572, 0x36C572 + 0x1000);
        assertGolden(source, 4, 0xE7899A58L);
        assertGolden(source, 8, 0x8215C1CCL);
        assertGolden(source, 12, 0x73371142L);
        assertGolden(source, 28, 0x5B919E50L);
    }

    private static void assertGolden(byte[] source, int step, long expected) {
        Pattern[] output = patterns(64);
        MgzEndBossArtScaler.scale(source, step, ROM_FRAME_FOUR, output);
        assertEquals(expected, crc(output), "scale step " + step);
    }

    private static byte[] sourceRamp() {
        byte[] source = new byte[0x1000];
        for (int y = 0; y < 0x40; y++) {
            for (int x = 0; x < 0x80; x += 2) {
                int hi = (x + 3 * y) & 0xF;
                int lo = (x + 1 + 3 * y) & 0xF;
                source[y * 0x40 + x / 2] = (byte) (hi << 4 | lo);
            }
        }
        return source;
    }

    private static Pattern[] patterns(int count) {
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) patterns[i] = new Pattern();
        return patterns;
    }

    private static long crc(Pattern[] patterns) {
        CRC32 crc = new CRC32();
        byte[] pixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        for (Pattern pattern : patterns) {
            pattern.copyInto(pixels, 0);
            crc.update(pixels);
        }
        return crc.getValue();
    }
}
