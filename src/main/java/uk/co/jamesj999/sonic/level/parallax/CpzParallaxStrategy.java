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
        // Based on typical CPZ behavior
        // CPZ Background is 512px high.
        // Sky: Moves at 1/8 speed
        // Buildings: Moves at 3/8 speed
        // Lower Building: Moves at 1/2 speed
        // Pipes: Moves at 3/4 speed (0.75)

        int mapHeight = 512;

        for (int y = 0; y < VISIBLE_LINES; y++) {
            // Apply bgScrollY for vertical scrolling
            int mapY = (y + bgScrollY) % mapHeight;
            if (mapY < 0) mapY += mapHeight;

            short planeB;

            // Updated banding logic for smoother gradients and more accurate speed
            if (mapY < 128) {
                // Sky - Slow (0.125x)
                planeB = (short) -(camX >> 3);
            } else if (mapY < 256) {
                 // Distant Buildings - (0.375x)
                 planeB = (short) (-(camX >> 2) - (camX >> 3));
            } else if (mapY < 384) {
                 // Closer Buildings - (0.5x)
                 planeB = (short) -(camX >> 1);
            } else {
                 // Pipes/Goo - Fast (0.75x)
                 planeB = (short) -(camX - (camX >> 2));
            }

            // Add a small cloud autoscroll to sky
            if (mapY < 80) {
                 int cloudScroll = (frameCounter >> 2);
                 planeB -= cloudScroll;
            }

            hScroll[y] = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        }
    }
}
