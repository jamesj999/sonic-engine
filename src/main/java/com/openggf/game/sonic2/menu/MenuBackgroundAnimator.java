package com.openggf.game.sonic2.menu;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;

import java.util.Objects;

/**
 * ROM-accurate animator for the Sonic/Miles menu background (Anim_SonicMilesBG).
 */
public class MenuBackgroundAnimator {
    private static final int DEST_TILE_OFFSET = 1;
    private static final int TILES_PER_FRAME = 0x0A;

    private static final int[] FRAME_TILE_IDS = {
            0x00, 0x0A, 0x14, 0x1E, 0x14, 0x0A
    };

    private static final int[] FRAME_DURATIONS = {
            0xC7, 0x05, 0x05, 0xC7, 0x05, 0x05
    };

    private final Pattern[] patterns;
    private final GraphicsManager graphicsManager;
    private final int patternBase;
    private int timer;
    private int frameIndex;

    public MenuBackgroundAnimator(Pattern[] patterns, GraphicsManager graphicsManager, int patternBase) {
        this.patterns = patterns;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.patternBase = patternBase;
        this.timer = 0;
        this.frameIndex = 0;
    }

    public void prime() {
        if (patterns == null || patterns.length == 0) {
            return;
        }
        applyFrame(FRAME_TILE_IDS[0]);
    }

    public void update() {
        if (patterns == null || patterns.length == 0) {
            return;
        }
        if (timer > 0) {
            timer = (timer - 1) & 0xFF;
            return;
        }

        int currentFrame = frameIndex;
        frameIndex = (frameIndex + 1) % FRAME_TILE_IDS.length;
        timer = FRAME_DURATIONS[currentFrame] & 0xFF;

        applyFrame(FRAME_TILE_IDS[currentFrame]);
    }

    private void applyFrame(int tileId) {
        if (graphicsManager == null || !graphicsManager.isGlInitialized()) {
            return;
        }

        for (int i = 0; i < TILES_PER_FRAME; i++) {
            int srcIndex = tileId + i;
            int destIndex = DEST_TILE_OFFSET + i;
            if (srcIndex < 0 || srcIndex >= patterns.length) {
                continue;
            }
            Pattern source = patterns[srcIndex];
            if (source == null) {
                continue;
            }
            graphicsManager.updatePatternTexture(source, patternBase + destIndex);
        }
    }
}
