package com.openggf.game.sonic2;

import com.openggf.game.session.EngineServices;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.RomDetectionService;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.animation.strategies.ScriptFramesApplyStrategy;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.animation.AniPlcScriptState;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestScriptFramesApplyStrategyGraphicsRebind {

    @AfterEach
    void tearDown() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void applyResolvesTheCurrentGraphicsManagerAtExecutionTime() {
        RecordingGraphicsManager installGraphics = new RecordingGraphicsManager();
        RecordingGraphicsManager currentGraphics = new RecordingGraphicsManager();

        EngineServices.configure(new EngineContext(
                SonicConfigurationService.getInstance(),
                currentGraphics,
                AudioManager.getInstance(),
                RomManager.getInstance(),
                PerformanceProfiler.getInstance(),
                DebugOverlayManager.getInstance(),
                PlaybackDebugManager.getInstance(),
                RomDetectionService.getInstance(),
                CrossGameFeatureProvider.getInstance()));

        TestLevel level = new TestLevel();
        Pattern art = new Pattern();
        art.setPixel(0, 0, (byte) 1);
        AniPlcScriptState script = new AniPlcScriptState(
                (byte) 0,
                0,
                new int[] {0},
                null,
                1,
                new Pattern[] {art});
        ScriptFramesApplyStrategy strategy = new ScriptFramesApplyStrategy(script, installGraphics);

        strategy.apply(new ChannelContext(null, null, level, null, 0, 0, 0));

        assertEquals(0, installGraphics.updateCalls,
                "Install-time graphics should not be used after a graphics rebind");
        assertEquals(1, currentGraphics.updateCalls,
                "Apply-time graphics should be resolved from the current engine services");
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        private int updateCalls;

        @Override
        public boolean isGlInitialized() {
            return true;
        }

        @Override
        public void updatePatternTexture(Pattern pattern, int patternId) {
            updateCalls++;
        }
    }

    private static final class TestLevel implements Level {
        private Pattern[] patterns = {new Pattern()};

        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { throw new UnsupportedOperationException(); }
        @Override public int getPatternCount() { return patterns.length; }
        @Override public Pattern getPattern(int index) { return patterns[index]; }
        @Override public void ensurePatternCapacity(int minCount) {
            if (minCount <= patterns.length) {
                return;
            }
            Pattern[] expanded = new Pattern[minCount];
            System.arraycopy(patterns, 0, expanded, 0, patterns.length);
            for (int i = patterns.length; i < expanded.length; i++) {
                expanded[i] = new Pattern();
            }
            patterns = expanded;
        }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
