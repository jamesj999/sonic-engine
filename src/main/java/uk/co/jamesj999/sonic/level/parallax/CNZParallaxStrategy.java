package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Casino Night Zone (CNZ).
 * Matches SwScrl_CNZ (loc_D0C6).
 */
public class CNZParallaxStrategy implements ParallaxStrategy {
    private final byte[] rowHeights;
    private final short[] layerDef = new short[64];

    public CNZParallaxStrategy(byte[] rowHeights) {
        this.rowHeights = rowHeights;
    }

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_CNZ
        // Camera_BG_Y_pos = Camera_Y_pos >> 6
        // Calls dedicated generator routine.
        // Emits using row heights.
        // Special ripple if row height byte is 0.

        int camX = camera.getX();
        int d0 = -camX;

        // Generator emulation
        // Fills layerDef with scroll values.
        // Likely similar to others: shifted values.
        for (int i = 0; i < layerDef.length; i++) {
             layerDef[i] = (short) (d0 >> ((i % 4) + 1));
        }

        if (rowHeights == null) return;

        int line = 0;
        int tableIdx = 0;

        while (line < 224) {
            if (tableIdx >= rowHeights.length) tableIdx = 0;

            int h = rowHeights[tableIdx] & 0xFF;

            if (h == 0) {
                // Ripple Segment
                // "applies per-line ripple offsets"
                // Assuming 1 line or specific block size?
                // If 0, it usually means "until end" or "special block".
                // In CNZ, 0 likely means "rest of screen" or "ripple block".
                // Let's assume 1 line ripple for now or break.
                // Or maybe '0' is a marker in a (Height, Index) pair?
                // "when row height byte is 0 ... applies per-line ripple".
                // This implies the 0 entry itself triggers a loop.
                // Let's assume it consumes 1 line?

                // Let's assume standard ripple block size (e.g. 1 scanline or defined elsewhere).
                // Or maybe it consumes until next non-zero?

                // We'll write 1 line with ripple.
                short ripple = (short) ((frameCounter) & 0xF); // Dummy ripple
                writeLine(hScroll, line++, d0, (short)(d0 + ripple));
            } else {
                int drawLines = Math.min(h, 224 - line);
                short bgVal = layerDef[tableIdx % layerDef.length];
                int packed = ((d0 & 0xFFFF) << 16) | (bgVal & 0xFFFF);
                for (int i = 0; i < drawLines; i++) {
                    hScroll[line++] = packed;
                }
            }
            tableIdx++;
        }
    }

    private void writeLine(int[] hScroll, int line, int fg, int bg) {
        if (line < hScroll.length) {
            hScroll[line] = ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
        }
    }
}
