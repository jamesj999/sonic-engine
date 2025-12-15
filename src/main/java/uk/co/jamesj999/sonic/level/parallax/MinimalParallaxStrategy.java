package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import static uk.co.jamesj999.sonic.level.ParallaxManager.VISIBLE_LINES;

public class MinimalParallaxStrategy implements ParallaxStrategy {

    @Override
    public void load(Rom rom) {
        // No data to load
    }

    @Override
    public void update(Camera cam, int frameCounter, int bgScrollY, int[] hScroll) {
        int camX = cam.getX();
        // Plane A moves with camera (FG) -> scroll = -camX
        short planeA = (short) -camX;
        // Plane B generic parallax (0.5)
        short planeB = (short) -(camX >> 1);

        int packed = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        for (int y = 0; y < VISIBLE_LINES; y++) hScroll[y] = packed;
    }
}
