package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.scroll.BackgroundCamera;
import uk.co.jamesj999.sonic.level.scroll.ParallaxTables;
import uk.co.jamesj999.sonic.level.scroll.SwScrlCpz;
import uk.co.jamesj999.sonic.level.scroll.SwScrlEhz;
import uk.co.jamesj999.sonic.level.scroll.SwScrlMcz;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages parallax scrolling effects.
 * Outputs scroll values compatible with the LevelManager rendering system.
 */
public class ParallaxManager {
    private static final Logger LOGGER = Logger.getLogger(ParallaxManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Zone IDs (matching LevelManager list index)
    public static final int ZONE_EHZ = 0;
    public static final int ZONE_CPZ = 1;
    public static final int ZONE_ARZ = 2;
    public static final int ZONE_CNZ = 3;
    public static final int ZONE_HTZ = 4;
    public static final int ZONE_MCZ = 5;
    public static final int ZONE_OOZ = 6;
    public static final int ZONE_MTZ = 7;
    public static final int ZONE_SCZ = 8;
    public static final int ZONE_WFZ = 9;
    public static final int ZONE_DEZ = 10;

    // Background map heights for wrapping

    private static final int CPZ_BG_HEIGHT = 256;
    private static final int ARZ_BG_HEIGHT = 256;
    private static final int CNZ_BG_HEIGHT = 512;
    private static final int OOZ_BG_HEIGHT = 256;
    private static final int MCZ_BG_HEIGHT = 256;

    // ARZ Constants and State
    private static final int[] ARZ_ROW_HEIGHTS = {
            0xB0, 0x70, 0x30, 0x60, 0x15, 0x0C, 0x0E, 0x06,
            0x0C, 0x1F, 0x30, 0xC0, 0xF0, 0xF0, 0xF0, 0xF0
    };

    private int arzBgXPos; // 16.16 Target
    private int arzBgXPosFast; // 16.16 Actual
    private int arzBgYPos; // 16.16 Accumulator

    private int lastCameraX;
    private int lastCameraY;

    // Packed as (planeA << 16) | (planeB & 0xFFFF)
    private final int[] hScroll = new int[VISIBLE_LINES];

    private int minScroll = 0;
    private int maxScroll = 0;

    private short vscrollFactorFG;
    private short vscrollFactorBG;

    private final BackgroundCamera bgCamera = new BackgroundCamera();
    private ParallaxTables tables;
    private boolean loaded = false;

    private SwScrlEhz ehzHandler;
    private SwScrlCpz cpzHandler;
    private SwScrlMcz mczHandler;

    private int currentZone = -1;
    private int currentAct = -1;

    // Pre-allocated arrays to avoid per-frame allocations
    private final int[] arzRowSpeeds = new int[16];
    private final int[] wfzOffsets = new int[4];

    private static ParallaxManager instance;

    public static synchronized ParallaxManager getInstance() {
        if (instance == null) {
            instance = new ParallaxManager();
        }
        return instance;
    }

    public void load(Rom rom) {
        if (loaded)
            return;
        try {
            tables = new ParallaxTables(rom);
            ehzHandler = new SwScrlEhz(tables);
            cpzHandler = new SwScrlCpz(tables);
            mczHandler = new SwScrlMcz(tables);
            loaded = true;
            LOGGER.info("Parallax tables loaded.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load parallax data: " + e.getMessage(), e);
        }
    }

    public void initZone(int zoneId, int actId, int cameraX, int cameraY) {
        if (zoneId != currentZone || actId != currentAct) {
            bgCamera.init(zoneId, actId, cameraX, cameraY);
            currentZone = zoneId;
            currentAct = actId;

            if (zoneId == ZONE_ARZ) {
                initArz(actId, cameraX, cameraY);
            } else if (zoneId == ZONE_CPZ && cpzHandler != null) {
                cpzHandler.init(cameraX, cameraY);
            }
        }
    }

    private void initArz(int actId, int cameraX, int cameraY) {
        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // Initial BG X: (Camera_X_pos * $0119) >> 8 (This is integer result from
        // original code)
        // We want 16.16. (cameraX * 0x119) is 24.8. Shift left 8 to get 16.16.
        long initialBgX = (long) cameraX * 0x119;
        arzBgXPos = (int) (initialBgX << 8);
        arzBgXPosFast = arzBgXPos;

        // Initial BG Y
        if (actId == 0) {
            // Act 1: Camera_Y_pos - $180
            arzBgYPos = (cameraY - 0x180) << 16;
        } else {
            // Act 2: (Camera_Y_pos - $E0) >> 1
            arzBgYPos = ((cameraY - 0xE0) >> 1) << 16;
        }
    }

    public int[] getHScroll() {
        return hScroll;
    }

    public int getMinScroll() {
        return minScroll;
    }

    public int getMaxScroll() {
        return maxScroll;
    }

    /**
     * Get the raw hScroll buffer for shader-based rendering.
     * This returns the packed (FG << 16 | BG) format array.
     * The BackgroundRenderer extracts BG values during upload.
     */
    public int[] getHScrollForShader() {
        return hScroll;
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    public void setScreenShakeFlag(boolean screenShakeFlag) {
        if (mczHandler != null) {
            mczHandler.setScreenShakeFlag(screenShakeFlag);
        }
    }

    public void update(int zoneId, int actId, Camera cam, int frameCounter, int bgScrollY) {
        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        if (!loaded) {
            fillMinimal(cam);
            return;
        }

        initZone(zoneId, actId, cam.getX(), cam.getY());

        int cameraX = cam.getX();
        int cameraY = cam.getY();

        // Calculate diffs for ARZ (8.8 fixed)
        int diffX = (cameraX - lastCameraX) << 8;
        int diffY = (cameraY - lastCameraY) << 8;

        lastCameraX = cameraX;
        lastCameraY = cameraY;

        vscrollFactorFG = (short) cameraY;
        vscrollFactorBG = (short) bgCamera.getBgYPos();

        switch (zoneId) {
            case ZONE_EHZ:
                if (ehzHandler != null) {
                    ehzHandler.update(hScroll, cameraX, cameraY, frameCounter, actId);
                    minScroll = ehzHandler.getMinScrollOffset();
                    maxScroll = ehzHandler.getMaxScrollOffset();
                    vscrollFactorBG = ehzHandler.getVscrollFactorBG();
                } else {
                    // Fallback should normally not happen if loaded
                    // fillEhz(cameraX, frameCounter, bgScrollY);
                }
                break;
            case ZONE_CPZ:
                if (cpzHandler != null) {
                    cpzHandler.update(hScroll, cameraX, cameraY, frameCounter, actId);
                    minScroll = cpzHandler.getMinScrollOffset();
                    maxScroll = cpzHandler.getMaxScrollOffset();
                    vscrollFactorBG = cpzHandler.getVscrollFactorBG();
                } else {
                    fillCpz(cameraX, bgScrollY, frameCounter);
                }
                break;
            case ZONE_ARZ:
                fillArz(cameraX, diffX, diffY);
                break;
            case ZONE_CNZ:
                fillCnz(cameraX, bgScrollY);
                break;
            case ZONE_HTZ:
                fillHtz(cameraX, bgScrollY);
                break;
            case ZONE_MCZ:
                if (mczHandler != null) {
                    mczHandler.update(hScroll, cameraX, cameraY, frameCounter, currentAct);
                    minScroll = mczHandler.getMinScrollOffset();
                    maxScroll = mczHandler.getMaxScrollOffset();
                    vscrollFactorBG = mczHandler.getVscrollFactorBG();
                    vscrollFactorFG = mczHandler.getVscrollFactorFG();
                    // Update bgCamera for renderer's vertical scroll
                    bgCamera.setBgYPos(mczHandler.getBgY());
                }
                break;
            case ZONE_OOZ:
                fillOoz(cameraX, bgScrollY, frameCounter);
                break;
            case ZONE_MTZ:
                fillMtz(cameraX);
                break;
            case ZONE_SCZ:
                fillScz(cameraX, cameraY);
                break;
            case ZONE_WFZ:
                fillWfz(cameraX, frameCounter);
                break;
            case ZONE_DEZ:
                fillDez(cameraX, frameCounter);
                break;
            default:
                fillMinimal(cam);
                break;
        }
    }

    // ========== Zone-Specific Scroll Routines ==========

    /**
     * EHZ - Emerald Hill Zone
     * Uses bgScrollY to map screen lines to background map positions.
     * Grass scrolls FASTER (smaller offset) toward the bottom.
     */
    /**
     * EHZ - Emerald Hill Zone
     * Pixel-perfect implementation matching SwScrl_EHZ (1P) from S2 disassembly.
     */

    /**
     * CPZ - Chemical Plant Zone
     * Background is blue pipes. Water with shimmer at bottom of BG map.
     * 
     * The shimmer must appear where the water texture is in the CPZ BG.
     * CPZ water shimmer. Adding a fixed offset to move shimmer down on screen.
     */
    private void fillCpz(int cameraX, int bgScrollY, int frameCounter) {
        short fgScroll = (short) -cameraX;

        // CPZ BG scrolls at 1/4 speed for X
        int baseOffset = cameraX - (cameraX >> 2);

        int bgCameraY = bgScrollY;

        // Calculate where water appears on screen
        // Water is at BG map Y 192-255. It appears on screen at line (192 - bgCameraY)
        // Add 96 to push shimmer lower on screen to align with actual water texture
        int shimmerScreenStart = 192 - bgCameraY + 96;
        int shimmerScreenEnd = shimmerScreenStart + 16; // 16 pixels of shimmer (was 32)

        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            int offset = baseOffset;

            // Apply shimmer only to screen lines that show the water
            if (screenLine >= shimmerScreenStart && screenLine < shimmerScreenEnd && tables != null) {
                int waterLineOffset = screenLine - shimmerScreenStart;
                int rippleIdx = ((frameCounter >> 2) + waterLineOffset) % tables.getRippleDataLength();
                offset += tables.getRippleSigned(rippleIdx);
            }

            setLineWithOffset(screenLine, fgScroll, offset);
        }
    }

    /**
     * ARZ - Aquatic Ruin Zone
     * Pixel-identical implementation of ARZ parallax.
     */
    private void fillArz(int cameraX, int diffX, int diffY) {
        // 1. Update BG X (Smoothing)
        // diffX is 8.8, 0x0119 is 8.8. Result is 16.16.
        int d4_fixed = diffX * 0x0119;

        arzBgXPos += d4_fixed;

        int targetBGX = arzBgXPos >> 16;
        int currentBGX = arzBgXPosFast >> 16;
        int delta = targetBGX - currentBGX;

        // Clamp delta to [-0x10, +0x10]
        if (delta < -16)
            delta = -16;
        if (delta > 16)
            delta = 16;

        arzBgXPosFast += (delta << 16);

        // 2. Update BG Y
        int d5_fixed = diffY << 7;
        if (currentAct == 0) {
            d5_fixed <<= 1;
        }
        arzBgYPos += d5_fixed;

        // Update vscrollFactorBG for external use
        vscrollFactorBG = (short) (arzBgYPos >> 16);

        // 3. Row Selection
        int bgY = arzBgYPos >> 16;
        // Normalize bgY to [0, 1728) for safety, though original relies on subtraction
        // loop
        int totalHeight = 1728;
        bgY %= totalHeight;
        if (bgY < 0)
            bgY += totalHeight;

        int currentRowIndex = 0;
        int remainingPixels = 0;

        int tempY = bgY;
        for (int i = 0; i < 16; i++) {
            int h = ARZ_ROW_HEIGHTS[i];
            tempY -= h;
            if (tempY < 0) {
                currentRowIndex = i;
                remainingPixels = -tempY;
                break;
            }
        }

        // 4. Row Speeds
        int[] rowSpeeds = new int[16];

        // Rows 4..11 (indices 3..10)
        // q = ((Camera_X_pos << 4) / 10)
        // baseFixed = q << 12
        int q = (cameraX << 4) / 10;
        int baseFixed = q << 12;

        int d1 = baseFixed;
        // Row 4 speed
        rowSpeeds[3] = baseFixed >> 16;

        d1 = d1 + d1 + baseFixed; // d1 = 3 * baseFixed

        // Rows 5..10
        for (int i = 4; i <= 9; i++) {
            rowSpeeds[i] = d1 >> 16;
            d1 += baseFixed;
        }

        // Row 11
        rowSpeeds[10] = d1 >> 16;

        // Rows 1..3 and 12..16 use Fast BG X
        int fastSpeed = arzBgXPosFast >> 16;
        rowSpeeds[1] = fastSpeed; // Row 2
        rowSpeeds[0] = fastSpeed; // Row 1 (overwrite)
        rowSpeeds[2] = fastSpeed; // Row 3 (overwrite)

        rowSpeeds[11] = fastSpeed;
        rowSpeeds[12] = fastSpeed;
        rowSpeeds[13] = fastSpeed;
        rowSpeeds[14] = fastSpeed;
        rowSpeeds[15] = fastSpeed;

        // 5. Fill Buffer
        short fgScroll = (short) -cameraX;
        int currentLine = 0;
        int rowIdx = currentRowIndex;
        int pixelsInRow = remainingPixels;

        while (currentLine < VISIBLE_LINES) {
            int speed = rowSpeeds[rowIdx];
            int count = Math.min(pixelsInRow, VISIBLE_LINES - currentLine);

            for (int k = 0; k < count; k++) {
                short bgScroll = (short) -speed;
                hScroll[currentLine++] = packScrollWords(fgScroll, bgScroll);

                short offset = (short) (bgScroll - fgScroll);
                if (offset < minScroll)
                    minScroll = offset;
                if (offset > maxScroll)
                    maxScroll = offset;
            }

            rowIdx = (rowIdx + 1) % 16;
            pixelsInRow = ARZ_ROW_HEIGHTS[rowIdx];
        }
    }

    public void dumpArzBuffer() {
        StringBuilder sb = new StringBuilder();
        sb.append("ARZ Scroll Buffer Dump:\n");
        for (int i = 0; i < VISIBLE_LINES; i++) {
            int val = hScroll[i];
            short fg = (short) (val >> 16);
            short bg = (short) (val & 0xFFFF);
            sb.append(String.format("Line %03d: FG=%d, BG=%d\n", i, fg, bg));
        }
        LOGGER.info(sb.toString());
    }

    /**
     * CNZ - Casino Night Zone
     * City skyline with multiple layers.
     */
    private void fillCnz(int cameraX, int bgScrollY) {
        short fgScroll = (short) -cameraX;

        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            int mapY = (screenLine + bgScrollY) % CNZ_BG_HEIGHT;
            if (mapY < 0)
                mapY += CNZ_BG_HEIGHT;

            int offset;
            // CNZ has tall buildings, graduated parallax
            if (mapY < 128) {
                // Top sky/stars - very slow
                offset = cameraX - (cameraX >> 5);
            } else if (mapY < 320) {
                // Mid buildings - slow
                offset = cameraX - (cameraX >> 4);
            } else {
                // Lower buildings - medium
                offset = cameraX - (cameraX >> 3);
            }

            setLineWithOffset(screenLine, fgScroll, offset);
        }
    }

    /**
     * HTZ - Hill Top Zone
     */
    private void fillHtz(int cameraX, int bgScrollY) {
        short fgScroll = (short) -cameraX;

        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            int offset;
            if (screenLine < 80) {
                // Sky - static
                offset = cameraX;
            } else if (screenLine < 160) {
                // Mountains - very slow
                offset = cameraX - (cameraX >> 5);
            } else {
                // Hills - slow
                offset = cameraX - (cameraX >> 4);
            }
            setLineWithOffset(screenLine, fgScroll, offset);
        }
    }

    /**
     * OOZ - Oil Ocean Zone
     * Sun, refinery, and oil. Uses bgScrollY for correct positioning.
     */
    private void fillOoz(int cameraX, int bgScrollY, int frameCounter) {
        short fgScroll = (short) -cameraX;

        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            int mapY = (screenLine + bgScrollY) % OOZ_BG_HEIGHT;
            if (mapY < 0)
                mapY += OOZ_BG_HEIGHT;

            int offset;

            // OOZ layout: sun at top (with shimmer), refinery in middle, oil at bottom
            if (mapY < 48) {
                // Sun/sky with heat shimmer
                offset = cameraX; // Static
                if (tables != null) {
                    int ripple = tables.getRippleSigned((frameCounter + mapY) % tables.getRippleDataLength());
                    offset += ripple;
                }
            } else if (mapY < 144) {
                // Refinery - slow parallax
                offset = cameraX - (cameraX >> 4);
            } else {
                // Oil/lower refinery - medium parallax
                offset = cameraX - (cameraX >> 3);
            }

            setLineWithOffset(screenLine, fgScroll, offset);
        }
    }

    /**
     * MTZ - Metropolis Zone
     */
    private void fillMtz(int cameraX) {
        short fgScroll = (short) -cameraX;
        int offset = cameraX - (cameraX >> 2);
        for (int line = 0; line < VISIBLE_LINES; line++) {
            setLineWithOffset(line, fgScroll, offset);
        }
    }

    /**
     * SCZ - Sky Chase Zone
     */
    private void fillScz(int cameraX, int cameraY) {
        short fgScroll = (short) -cameraX;

        for (int line = 0; line < VISIBLE_LINES; line++) {
            int offset;
            if (line < 64) {
                offset = cameraX - (cameraX >> 3);
            } else if (line < 144) {
                offset = cameraX - (cameraX >> 2);
            } else {
                offset = cameraX - (cameraX >> 2) - (cameraX >> 3);
            }
            setLineWithOffset(line, fgScroll, offset);
        }
    }

    /**
     * WFZ - Wing Fortress Zone
     */
    private void fillWfz(int cameraX, int frameCounter) {
        short fgScroll = (short) -cameraX;

        int[] offsets = {
                cameraX - (cameraX >> 4),
                cameraX - (cameraX >> 3),
                cameraX - (cameraX >> 2),
                cameraX - (cameraX >> 1)
        };

        int segmentHeight = VISIBLE_LINES / 4;
        for (int line = 0; line < VISIBLE_LINES; line++) {
            int layer = Math.min(line / segmentHeight, 3);
            setLineWithOffset(line, fgScroll, offsets[layer]);
        }
    }

    /**
     * DEZ - Death Egg Zone
     */
    private void fillDez(int cameraX, int frameCounter) {
        short fgScroll = (short) -cameraX;

        for (int line = 0; line < VISIBLE_LINES; line++) {
            int starLayer = (line / 32) % 4;
            int baseOffset = cameraX - (cameraX >> 5);
            int layerVar = starLayer * 2;
            setLineWithOffset(line, fgScroll, baseOffset + layerVar);
        }
    }

    // ========== Helper Methods ==========

    private void setLineWithOffset(int line, short fgScroll, int bgOffset) {
        if (line >= 0 && line < VISIBLE_LINES) {
            short bgScroll = (short) (fgScroll + bgOffset);
            hScroll[line] = packScrollWords(fgScroll, bgScroll);

            short offsetShort = (short) bgOffset;
            if (offsetShort < minScroll)
                minScroll = offsetShort;
            if (offsetShort > maxScroll)
                maxScroll = offsetShort;
        }
    }

    private static int packScrollWords(short fg, short bg) {
        return ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
    }

    private void fillMinimal(Camera cam) {
        short fgScroll = (short) -cam.getX();
        int offset = cam.getX() >> 1;
        for (int line = 0; line < VISIBLE_LINES; line++) {
            setLineWithOffset(line, fgScroll, offset);
        }
    }
}
