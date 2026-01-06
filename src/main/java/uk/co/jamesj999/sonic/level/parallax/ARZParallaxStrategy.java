package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Aquatic Ruin Zone (ARZ).
 * Matches SwScrl_ARZ (loc_D4AE).
 */
public class ARZParallaxStrategy implements ParallaxStrategy {
    private final byte[] rowHeights;
    private final short[] layerDef = new short[256];

    public ARZParallaxStrategy(byte[] rowHeights) {
        this.rowHeights = rowHeights;
    }

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_ARZ
        // Segment/row based.
        // Builds per-row scroll values into TempArray_LayerDef.
        // Emits based on BG Y position.

        int camX = camera.getX();
        int d0 = -camX;

        // Build values
        // ARZ columns/pillars move at different speeds.
        for (int i = 0; i < layerDef.length; i++) {
            layerDef[i] = (short) (d0 >> ((i % 3) + 1));
        }

        if (rowHeights == null) return;

        // Uses BG Y to find visible segment (like MCZ)
        int currentY = 0;
        int targetY = bgScrollY % 512; // Assuming 512px height
        if (targetY < 0) targetY += 512;

        int tableIdx = 0;
        while (tableIdx < rowHeights.length) {
            int h = rowHeights[tableIdx] & 0xFF;
            if (currentY + h > targetY) break;
            currentY += h;
            tableIdx++;
        }

        int offsetInSegment = targetY - currentY;
        int line = 0;

        while (line < 224) {
            if (tableIdx >= rowHeights.length) tableIdx = 0;
            int h = rowHeights[tableIdx] & 0xFF;
            int visibleH = h - offsetInSegment;

            if (visibleH > 0) {
                int drawLines = Math.min(visibleH, 224 - line);
                short bgVal = layerDef[tableIdx % 16];
                int packed = ((d0 & 0xFFFF) << 16) | (bgVal & 0xFFFF);
                for (int i = 0; i < drawLines; i++) {
                    hScroll[line++] = packed;
                }
            }
            offsetInSegment = 0;
            tableIdx++;
        }
    }
}
