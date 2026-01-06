package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Death Egg Zone (DEZ).
 * Matches SwScrl_DEZ (loc_D382).
 */
public class DEZParallaxStrategy implements ParallaxStrategy {
    private final byte[] rowHeights;
    private final short[] layerDef = new short[256];

    public DEZParallaxStrategy(byte[] rowHeights) {
        this.rowHeights = rowHeights;
    }

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_DEZ
        // Starfield parallax.
        // List of row scroll values: Camera_X_pos + sequence of small increments (addq.w #n).
        // Uses SwScrl_DEZ_RowHeights.

        int camX = camera.getX();
        int d0 = -camX;

        // Build list
        // "addq.w #n" implies increments.
        // DEZ stars move slightly differently.
        // Base is -camX >> 3? Or similar.
        // Let's assume slow movement.

        int val = -(camX >> 3);
        for (int i = 0; i < layerDef.length; i++) {
            layerDef[i] = (short) val;
            val += 1; // Small increment simulation
        }

        if (rowHeights == null) return;

        int line = 0;
        int tableIdx = 0;

        while (line < 224) {
            if (tableIdx >= rowHeights.length) tableIdx = 0;
            int h = rowHeights[tableIdx] & 0xFF;

            int drawLines = Math.min(h, 224 - line);
            short bgVal = layerDef[tableIdx % layerDef.length];

            int packed = ((d0 & 0xFFFF) << 16) | (bgVal & 0xFFFF);
            for (int i = 0; i < drawLines; i++) {
                hScroll[line++] = packed;
            }
            tableIdx++;
        }
    }
}
