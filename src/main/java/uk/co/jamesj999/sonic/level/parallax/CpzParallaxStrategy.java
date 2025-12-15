package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import static uk.co.jamesj999.sonic.level.ParallaxManager.VISIBLE_LINES;

public class CpzParallaxStrategy implements ParallaxStrategy {

    @Override
    public void load(Rom rom) {
        // No ROM tables for this approximation yet
    }

    @Override
    public void update(Camera cam, int frameCounter, int bgScrollY, int[] hScroll) {
        int camX = cam.getX();
        short planeA = (short) -camX;

        // CPZ Parallax approximation
        // Based on typical CPZ behavior (Sky, Buildings, Pipes)

        // 3 Major Bands:
        // Top (Sky/Distant Buildings): Slow
        // Middle (Yellow structure): Medium
        // Bottom (Pipes/Goo): Fast

        // Approximating split points (relative to background map height, ~256 or 512)
        // But since we are rendering visible lines, we check map Y.

        int mapHeight = 512; // CPZ background is tall

        for (int y = 0; y < VISIBLE_LINES; y++) {
            int mapY = (y + bgScrollY) % mapHeight;
            if (mapY < 0) mapY += mapHeight;

            short planeB;

            // Simple banding for now
            if (mapY < 128) {
                // Sky/Top Buildings - Slow (0.25x)
                planeB = (short) -(camX >> 2);
            } else if (mapY < 320) {
                 // Main City Structure - Medium (0.5x)
                 planeB = (short) -(camX >> 1);
            } else {
                 // Pipes/Goo - Fast (0.75x or 0.875x)
                 planeB = (short) -(camX - (camX >> 3));
            }

            // TODO: Add "autoscroll" for clouds or goo if needed.
            // CPZ clouds move?
            // CPZ chemical goo moves?

            hScroll[y] = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        }
    }
}
