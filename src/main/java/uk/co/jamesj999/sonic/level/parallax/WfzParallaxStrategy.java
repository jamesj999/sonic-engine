package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.level.ParallaxManager.VISIBLE_LINES;

public class WfzParallaxStrategy implements ParallaxStrategy {
    private static final Logger LOGGER = Logger.getLogger(WfzParallaxStrategy.class.getName());

    // Addresses (REV01)
    private static final int WFZ_TRANS_ADDR = 0x00C8CA;
    private static final int WFZ_NORMAL_ADDR = 0x00C916;
    private static final int WFZ_TRANS_SIZE = WFZ_NORMAL_ADDR - WFZ_TRANS_ADDR;
    private static final int WFZ_NORMAL_SIZE = 128; // Safe bet

    private byte[] wfzNormalSegs;
    private byte[] wfzTransSegs;

    @Override
    public void load(Rom rom) {
        try {
            this.wfzTransSegs = rom.readBytes(WFZ_TRANS_ADDR, WFZ_TRANS_SIZE);
            this.wfzNormalSegs = rom.readBytes(WFZ_NORMAL_ADDR, WFZ_NORMAL_SIZE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load WFZ parallax data: " + e.getMessage(), e);
            wfzTransSegs = new byte[0];
            wfzNormalSegs = new byte[0];
        }
    }

    @Override
    public void update(Camera cam, int frameCounter, int bgScrollY, int[] hScroll) {
        int camX = cam.getX();
        short planeA = (short) -camX;

        // WFZ Parallax
        short[] layerScroll = new short[16];
        // Populate layer scrolls (Route B approximation)
        layerScroll[0] = (short) -(camX >> 2);  // Slow
        layerScroll[1] = (short) -(camX >> 1);  // Medium
        layerScroll[2] = (short) -camX;         // Fast (Near)
        for(int i=3; i<16; i++) layerScroll[i] = (short) -(camX >> 1);

        byte[] segs = wfzNormalSegs;

        int y = 0;
        if (segs != null && segs.length > 0) {
            for (int i = 0; i + 1 < segs.length && y < VISIBLE_LINES; i += 2) {
                int count = segs[i] & 0xFF;
                int idx = segs[i + 1] & 0xFF;

                short planeB = (idx < layerScroll.length) ? layerScroll[idx] : layerScroll[0];

                int packed = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
                for (int n = 0; n < count && y < VISIBLE_LINES; n++, y++) {
                    hScroll[y] = packed;
                }
            }
        }

        // Fill remaining
        while (y < VISIBLE_LINES) {
            short planeB = (short) -(camX >> 1);
            hScroll[y++] = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        }
    }
}
