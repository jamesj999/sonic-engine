package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.level.ParallaxManager.VISIBLE_LINES;

public class EhzParallaxStrategy implements ParallaxStrategy {
    private static final Logger LOGGER = Logger.getLogger(EhzParallaxStrategy.class.getName());

    // Addresses (REV01)
    private static final int EHZ_RIPPLE_ADDR = 0x00C682;
    private static final int EHZ_RIPPLE_SIZE = 66;

    private byte[] ehzRipple;

    @Override
    public void load(Rom rom) {
        try {
            this.ehzRipple = rom.readBytes(EHZ_RIPPLE_ADDR, EHZ_RIPPLE_SIZE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load EHZ parallax data: " + e.getMessage(), e);
            ehzRipple = new byte[0];
        }
    }

    @Override
    public void update(Camera cam, int frameCounter, int bgScrollY, int[] hScroll) {
        int camX = cam.getX();
        short planeA = (short) -camX;

        // EHZ Parallax approximation

        // Calculate vertical background position to map bands to world coordinates (avoiding tearing during vertical scroll)
        int mapHeight = 256; // Adjusted to 256 based on observation of "bottom rows" wrapping behavior

        for (int y = 0; y < VISIBLE_LINES; y++) {
            // Map screen line to background map line
            int mapY = (y + bgScrollY) % mapHeight;
            if (mapY < 0) mapY += mapHeight;

            short baseB;

            // Banding based on Map Y (256px height)
            // Sky: 0-80 (0.25x)
            // Water Surface: 80-112 (0.25x + Ripple) - Base speed matches sky
            // Grass/Hills: 112-256 (Stepped bands for obvious parallax)

            if (mapY < 80) {
                baseB = (short) -(camX >> 2);
            } else if (mapY < 112) {
                baseB = (short) -(camX >> 2);
            } else {
                // Grass Bands (Stepped)
                // 112-148: 0.375x (3/8)
                // 148-184: 0.5x   (4/8)
                // 184-220: 0.625x (5/8)
                // 220-256: 0.75x  (6/8)

                int grassSection = (mapY - 112) / 36;

                // Base 0.25. Add (grassSection + 1) * 0.125
                int increment = (camX >> 3) * (grassSection + 1);
                baseB = (short) (-(camX >> 2) - increment);
            }

            short b = baseB;

            // Water region ripple
            // Limited to the Water Surface band (80-112)
            if (mapY >= 80 && mapY < 112) {
                if (ehzRipple != null && ehzRipple.length > 0) {
                    int slowFrame = frameCounter >> 3;
                    // Use mapY for ripple index
                    int idx = (slowFrame + (mapY - 80)) % ehzRipple.length;
                    if (idx < 0) idx += ehzRipple.length;

                    int offset = ehzRipple[idx] & 0x1;
                    b += (short) offset;
                }
            }

            hScroll[y] = ((planeA & 0xFFFF) << 16) | (b & 0xFFFF);
        }
    }
}
