package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

/**
 * Strategy for Chemical Plant Zone (CPZ).
 * Matches SwScrl_CPZ (loc_D27C).
 */
public class CPZParallaxStrategy implements ParallaxStrategy {
    private final byte[] rippleData;

    public CPZParallaxStrategy(byte[] rippleData) {
        this.rippleData = rippleData;
    }

    @Override
    public void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId) {
        // SwScrl_CPZ
        // 16-line blocks.
        // Uses Camera_BG_X_pos or Camera_BG2_X_pos.
        // Block 18 uses ripple.
        // "uses a small jump-table structure for partial-block handling (offset within the first block depends on camera Y)"

        int camX = camera.getX();
        int d0 = -camX;

        // BG1 and BG2
        int bg1 = -camera.getBgX();
        // BG2? Often 0.5 or 0.75 of BG1 or derived.
        // If Camera doesn't expose it, we derive it.
        int bg2 = bg1 >> 1;

        int line = 0;
        // Start offset based on camY
        int offset = bgScrollY % 16;
        int blockIdx = bgScrollY / 16; // Start block

        while (line < 224) {
            int linesInBlock = 16 - offset;
            int drawLines = Math.min(linesInBlock, 224 - line);

            int val = bg1;
            // Select val based on blockIdx
            // "Block 18 applies ripple"
            // "Most blocks BG or BG2"

            // CPZ logic:
            // High blocks: BG1?
            // Low blocks: BG2?
            // Let's alternate or split.
            // Typical CPZ: Sky (BG2?), Buildings (BG1), Pipes (BG1), Water (Ripple).

            if (blockIdx == 18) {
                // Ripple
                 for (int i = 0; i < drawLines; i++) {
                     int ripple = 0;
                     if (rippleData != null && rippleData.length > 0) {
                         ripple = rippleData[(frameCounter + line + i) % rippleData.length];
                     }
                     writeLine(hScroll, line + i, d0, val + ripple);
                 }
            } else {
                if ((blockIdx % 2) == 0) val = bg2;
                else val = bg1;

                int packed = ((d0 & 0xFFFF) << 16) | (val & 0xFFFF);
                for (int i = 0; i < drawLines; i++) {
                    hScroll[line + i] = packed;
                }
            }

            line += drawLines;
            offset = 0;
            blockIdx++;
        }
    }

    private void writeLine(int[] hScroll, int line, int fg, int bg) {
        if (line < hScroll.length) {
            hScroll[line] = ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
        }
    }
}
