package com.openggf.game.sonic1;

import com.openggf.game.OscillationManager;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.Test;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.TestEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1PatternAnimatorMagma {
    @Test
    public void marbleMagmaBodyTilesAreGeneratedAndAnimated() throws IOException {
        OscillationManager.reset();
        TestEnvironment.activeGameplayMode();

        try {
            int required = Sonic1Constants.ARTTILE_MZ_ANIMATED_MAGMA + 16;
            TestLevel level = new TestLevel(required + 32);

            Sonic1PatternAnimator animator = new Sonic1PatternAnimator(
                    RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom()),
                    level,
                    Sonic1Constants.ZONE_MZ);

            int magmaTile = Sonic1Constants.ARTTILE_MZ_ANIMATED_MAGMA;
            byte[] initial = dumpMagmaBlock(level, magmaTile);

            for (int frame = 1; frame <= 13; frame++) {
                OscillationManager.update(frame);
                animator.update();
            }

            byte[] animated = dumpMagmaBlock(level, magmaTile);
            assertFalse(Arrays.equals(initial, animated), "MZ magma block should animate over time");
        } finally {
            SessionManager.clear();
        }
    }

    private static byte[] dumpPattern(Pattern pattern) {
        byte[] out = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        int write = 0;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x += 2) {
                int left = pattern.getPixel(x, y) & 0x0F;
                int right = pattern.getPixel(x + 1, y) & 0x0F;
                out[write++] = (byte) ((left << 4) | right);
            }
        }
        return out;
    }

    private static byte[] dumpMagmaBlock(Level level, int baseTile) {
        byte[] out = new byte[16 * Pattern.PATTERN_SIZE_IN_ROM];
        for (int i = 0; i < 16; i++) {
            byte[] tile = dumpPattern(level.getPattern(baseTile + i));
            System.arraycopy(tile, 0, out, i * Pattern.PATTERN_SIZE_IN_ROM, Pattern.PATTERN_SIZE_IN_ROM);
        }
        return out;
    }

    private static final class TestLevel implements Level {
        private Pattern[] patterns;

        private TestLevel(int initialPatternCount) {
            patterns = new Pattern[initialPatternCount];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
        }

        @Override
        public int getPaletteCount() {
            return 0;
        }

        @Override
        public Palette getPalette(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getPatternCount() {
            return patterns.length;
        }

        @Override
        public Pattern getPattern(int index) {
            return patterns[index];
        }

        @Override
        public void ensurePatternCapacity(int minCount) {
            if (minCount <= patterns.length) {
                return;
            }
            Pattern[] expanded = Arrays.copyOf(patterns, minCount);
            for (int i = patterns.length; i < expanded.length; i++) {
                expanded[i] = new Pattern();
            }
            patterns = expanded;
        }

        @Override
        public int getChunkCount() {
            return 0;
        }

        @Override
        public Chunk getChunk(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getBlockCount() {
            return 0;
        }

        @Override
        public Block getBlock(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SolidTile getSolidTile(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map getMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }

        @Override
        public int getMinX() {
            return 0;
        }

        @Override
        public int getMaxX() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getMaxY() {
            return 0;
        }

        @Override
        public int getZoneIndex() {
            return Sonic1Constants.ZONE_MZ;
        }
    }
}


