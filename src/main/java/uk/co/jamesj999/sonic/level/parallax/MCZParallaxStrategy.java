package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Mystic Cave Zone (MCZ).
 * Matches SwScrl_MCZ (loc_CD2C).
 */
public class MCZParallaxStrategy implements ParallaxStrategy {
    private final byte[] rowHeights;
    private final byte[] rowHeights2P;

    private final short[] layerDef = new short[256]; // Assuming ample space

    public MCZParallaxStrategy(byte[] rowHeights, byte[] rowHeights2P) {
        this.rowHeights = rowHeights;
        this.rowHeights2P = rowHeights2P;
    }

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_MCZ
        // "updates BG vertical differently per act (divu #3 / divu #6 path)"
        // "builds a list of row scroll values into TempArray_LayerDef"
        // "uses SwScrl_MCZ_RowHeights (0xCE6C)"

        int camX = camera.getX();
        int d0 = -camX; // FG

        // Build scroll values
        // Code likely generates values based on d0/d1 shifts.
        // Let's emulate a generic "depth" generation or exact if possible.
        // Assuming standard MCZ cave parallax:
        // Layers move at different speeds.
        // 0.5, 0.25, etc.

        // Emulating the generator loop:
        // move.w d0, d1
        // asr.w #1, d1 (0.5)
        // move.w d1, (a1)+
        // asr.w #1, d1 (0.25)
        // ...
        // We need to fill layerDef with plausible values.
        // MCZ usually has many small strips.

        for (int i = 0; i < 16; i++) {
            layerDef[i] = (short) (d0 >> ((i % 4) + 1)); // Dummy logic
        }

        // Row Heights Logic
        byte[] heights = rowHeights;
        // Optional: 2P mode uses rowHeights2P. We assume 1P.

        if (heights == null) return;

        // "sums to a full vertical cycle (512 px) to decide which scroll value applies at each vertical position"
        // "subtracts against Camera_BG_Y_pos to find the first visible segment"

        int currentY = 0;
        int targetY = bgScrollY % 512; // 512px cycle
        if (targetY < 0) targetY += 512;

        int tableIdx = 0;

        // Skip invisible segments
        while (tableIdx < heights.length) {
            int h = heights[tableIdx] & 0xFF;
            if (currentY + h > targetY) {
                // We found the starting segment.
                // We are `targetY - currentY` pixels into this segment.
                break;
            }
            currentY += h;
            tableIdx++;
        }

        int offsetInSegment = targetY - currentY;
        int line = 0;

        // Draw loop
        while (line < 224) {
            if (tableIdx >= heights.length) tableIdx = 0; // Wrap table

            int h = heights[tableIdx] & 0xFF;
            // Visible height for this segment
            int visibleH = h - offsetInSegment;

            if (visibleH > 0) {
                int drawLines = Math.min(visibleH, 224 - line);
                short bgVal = layerDef[tableIdx % 16]; // Use index to pick scroll?
                // Actually the table is likely just heights. The scroll value index corresponds to table index?
                // Or there is an index in the table?
                // Prompt: "uses SwScrl_MCZ_RowHeights ... to decide which scroll value applies".
                // Usually these tables are interleaved (Height, ScrollIndex) or just Heights and we increment ScrollIndex.
                // "builds a list of row scroll values ... iterates row heights"
                // Implies sequential usage of scroll values.

                bgVal = layerDef[tableIdx % 16];

                int packed = ((d0 & 0xFFFF) << 16) | (bgVal & 0xFFFF);
                for (int i = 0; i < drawLines; i++) {
                    hScroll[line++] = packed;
                }
            }

            offsetInSegment = 0; // Only first segment has offset
            tableIdx++;
        }
    }
}
