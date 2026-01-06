package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Metropolis Zone (MTZ).
 * Matches SwScrl_MTZ (loc_C7F2).
 */
public class MtzParallaxStrategy implements ParallaxStrategy {

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_MTZ
        // FG = -Camera_X_pos
        // BG = -Camera_BG_X_pos
        // Vscroll_Factor_BG = Camera_BG_Y_pos (implicit)
        // Fills all scanlines.

        int camX = camera.getX();
        int bgCamX = camera.getBgX(); // Or calculated if not stored in Camera

        int fg = -camX;
        int bg = -bgCamX;

        int packed = ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);

        for (int i = 0; i < hScroll.length; i++) {
            hScroll[i] = packed;
        }
    }
}
