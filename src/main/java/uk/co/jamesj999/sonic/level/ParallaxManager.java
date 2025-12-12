package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages parallax scrolling effects, loading data tables from ROM and generating
 * per-scanline H-Scroll values.
 */
public class ParallaxManager {
    private static final Logger LOGGER = Logger.getLogger(ParallaxManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Zone IDs (matching LevelManager list index)
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_WFZ = 9;

    // Packed as (planeA << 16) | (planeB & 0xFFFF)
    // Plane A is FG, Plane B is BG.
    private final int[] hScroll = new int[VISIBLE_LINES];

    private int minScroll = 0;
    private int maxScroll = 0;

    // ROM Tables
    private byte[] ehzRipple;
    private byte[] wfzNormalSegs;
    private byte[] wfzTransSegs;

    private boolean loaded = false;

    // Addresses (REV01)
    private static final int EHZ_RIPPLE_ADDR = 0x00C682;
    private static final int EHZ_RIPPLE_SIZE = 66;
    private static final int WFZ_TRANS_ADDR = 0x00C8CA;
    private static final int WFZ_NORMAL_ADDR = 0x00C916;

    // Guessing size for WFZ tables based on addresses or safe buffer
    private static final int WFZ_TRANS_SIZE = WFZ_NORMAL_ADDR - WFZ_TRANS_ADDR;
    private static final int WFZ_NORMAL_SIZE = 128; // Safe bet

    private static ParallaxManager instance;

    public static synchronized ParallaxManager getInstance() {
        if (instance == null) {
            instance = new ParallaxManager();
        }
        return instance;
    }

    public void load(Rom rom) {
        if (loaded) return;
        try {
            this.ehzRipple = rom.readBytes(EHZ_RIPPLE_ADDR, EHZ_RIPPLE_SIZE);
            this.wfzTransSegs = rom.readBytes(WFZ_TRANS_ADDR, WFZ_TRANS_SIZE);
            this.wfzNormalSegs = rom.readBytes(WFZ_NORMAL_ADDR, WFZ_NORMAL_SIZE);
            loaded = true;
            LOGGER.info("Parallax data loaded.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load parallax data: " + e.getMessage(), e);
            // We don't throw, just log. loaded remains false.
            // Initialize arrays to prevent NPE if methods are called
            ehzRipple = new byte[0];
            wfzTransSegs = new byte[0];
            wfzNormalSegs = new byte[0];
        }
    }

    public int[] getHScroll() {
        return hScroll;
    }

    public int getMinScroll() { return minScroll; }
    public int getMaxScroll() { return maxScroll; }

    public void update(int zoneId, int actId, Camera cam, int frameCounter) {
        // Reset min/max
        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        if (!loaded) {
             // Try to be safe even if not loaded properly via ROM
             // But updateMinimal works without tables.
             updateMinimal(cam);
             return;
        }

        switch (zoneId) {
            case ZONE_EHZ:
                updateEhz(cam, frameCounter);
                break;
            case ZONE_WFZ:
                updateWfz(cam, frameCounter);
                break;
            default:
                updateMinimal(cam);
                break;
        }
    }

    private void updateMinimal(Camera cam) {
        int camX = cam.getX();
        // Plane A moves with camera (FG) -> scroll = -camX
        short planeA = (short) -camX;
        // Plane B generic parallax (0.5)
        short planeB = (short) -(camX >> 1);

        updateMinMax(planeB);

        int packed = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        for (int y = 0; y < VISIBLE_LINES; y++) hScroll[y] = packed;
    }

    private void updateEhz(Camera cam, int frameCounter) {
        int camX = cam.getX();
        short planeA = (short) -camX;

        // EHZ Parallax approximation

        // Calculate vertical background position to map bands to world coordinates (avoiding tearing during vertical scroll)
        // Background moves at 0.1 vertical parallax.
        int bgCamY = (int)(cam.getY() * 0.1f);
        int mapHeight = 256; // Adjusted to 256 based on observation of "bottom rows" wrapping behavior

        for (int y = 0; y < VISIBLE_LINES; y++) {
            // Map screen line to background map line
            int mapY = (y + bgCamY) % mapHeight;
            if (mapY < 0) mapY += mapHeight;

            short baseB;

            // Banding based on Map Y
            // Sky (0-80 approx): Slower (0.25)
            // Hills (80-128 approx): Normal (0.5)
            // Water (128+): Normal (0.5) + Ripple

            if (mapY < 80) {
                baseB = (short) -(camX >> 2);
            } else {
                baseB = (short) -(camX >> 1);
            }

            short b = baseB;

            // Water region ripple
            // Starting at 128 (Halfway down 256px map)
            if (mapY >= 128) {
                if (ehzRipple != null && ehzRipple.length > 0) {
                    int slowFrame = frameCounter >> 3;
                    // Use mapY for ripple index to keep ripple consistent with world
                    int idx = (slowFrame + (mapY - 128)) % ehzRipple.length;
                    if (idx < 0) idx += ehzRipple.length;

                    int offset = ehzRipple[idx] & 0x3;
                    b += (short) offset;
                }
            }

            updateMinMax(b);

            hScroll[y] = ((planeA & 0xFFFF) << 16) | (b & 0xFFFF);
        }
    }

    private void updateWfz(Camera cam, int frameCounter) {
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
                updateMinMax(planeB);

                int packed = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
                for (int n = 0; n < count && y < VISIBLE_LINES; n++, y++) {
                    hScroll[y] = packed;
                }
            }
        }

        // Fill remaining
        while (y < VISIBLE_LINES) {
            short planeB = (short) -(camX >> 1);
            updateMinMax(planeB);
            hScroll[y++] = ((planeA & 0xFFFF) << 16) | (planeB & 0xFFFF);
        }
    }

    private void updateMinMax(short val) {
        if (val < minScroll) minScroll = val;
        if (val > maxScroll) maxScroll = val;
    }
}
