package uk.co.jamesj999.sonic.level.scroll;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ROM-accurate software scroll manager implementing Sonic 2's DeformBgLayer and
 * SwScrl routines.
 * Uses Motorola 68000 arithmetic semantics for pixel-perfect accuracy.
 */
public class SoftwareScrollManager {
    private static final Logger LOGGER = Logger.getLogger(SoftwareScrollManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Zone IDs (matching Sonic 2 zone ordering)
    public static final int ZONE_EHZ = 0; // Emerald Hill
    public static final int ZONE_MTZ = 4; // Metropolis
    public static final int ZONE_WFZ = 5; // Wing Fortress
    public static final int ZONE_HTZ = 6; // Hill Top
    public static final int ZONE_HPZ = 7; // Hidden Palace (unused)
    public static final int ZONE_OOZ = 8; // Oil Ocean
    public static final int ZONE_MCZ = 11; // Mystic Cave
    public static final int ZONE_CNZ = 12; // Casino Night
    public static final int ZONE_CPZ = 13; // Chemical Plant
    public static final int ZONE_DEZ = 14; // Death Egg
    public static final int ZONE_ARZ = 2; // Aquatic Ruin
    public static final int ZONE_SCZ = 15; // Sky Chase

    // Horiz_Scroll_Buf: 224 entries, each packed as (FG_word << 16) | (BG_word &
    // 0xFFFF)
    private final int[] horizScrollBuf = new int[VISIBLE_LINES];

    // Vscroll factors (16-bit signed)
    private short vscrollFactorFG;
    private short vscrollFactorBG;

    // Background camera
    private final BackgroundCamera bgCamera = new BackgroundCamera();

    // ROM tables
    private ParallaxTables tables;
    private boolean tablesLoaded = false;

    // Zone-specific scroll routines
    private SwScrlEhz swScrlEhz;

    private static SoftwareScrollManager instance;

    public static synchronized SoftwareScrollManager getInstance() {
        if (instance == null) {
            instance = new SoftwareScrollManager();
        }
        return instance;
    }

    private SoftwareScrollManager() {
    }

    /**
     * Load parallax tables from ROM.
     */
    public void load(Rom rom) {
        if (tablesLoaded)
            return;
        try {
            tables = new ParallaxTables(rom);
            swScrlEhz = new SwScrlEhz(tables, bgCamera);
            tablesLoaded = true;
            LOGGER.info("Software scroll tables loaded.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load parallax tables: " + e.getMessage(), e);
            tablesLoaded = false;
        }
    }

    /**
     * Initialize background camera for a zone (replicates InitCameraValues
     * dispatching to InitCam_* routines).
     */
    public void initCamera(int zoneId, int actId, int cameraX, int cameraY) {
        bgCamera.init(zoneId, actId, cameraX, cameraY);
    }

    /**
     * Update scroll buffer and vscroll factors for the current frame.
     * Replicates DeformBgLayer -> SwScrl_Index dispatch.
     */
    public void update(int zoneId, int actId, Camera cam, int frameCounter) {
        int cameraX = cam.getX();
        int cameraY = cam.getY();

        // Set Vscroll factors (matches asm: move.w Camera_Y_pos, Vscroll_Factor_FG
        // etc.)
        vscrollFactorFG = (short) cameraY;
        vscrollFactorBG = (short) bgCamera.getBgYPos();

        // Dispatch to zone-specific scroll routine
        switch (zoneId) {
            case ZONE_EHZ:
                if (swScrlEhz != null) {
                    swScrlEhz.update(horizScrollBuf, cameraX, cameraY, frameCounter);
                } else {
                    fillMinimal(cameraX);
                }
                break;

            case ZONE_MTZ:
                fillMtz(cameraX);
                break;

            // TODO: Add other zones
            default:
                fillMinimal(cameraX);
                break;
        }
    }

    /**
     * SwScrl_MTZ - Minimal scroll, no parallax bands.
     * FG = -Camera_X_pos, BG = -Camera_BG_X_pos for all lines.
     */
    private void fillMtz(int cameraX) {
        short fgScroll = negWord(cameraX);
        short bgScroll = negWord(bgCamera.getBgXPos());
        int packed = packScrollWords(fgScroll, bgScroll);

        for (int i = 0; i < VISIBLE_LINES; i++) {
            horizScrollBuf[i] = packed;
        }
    }

    /**
     * SwScrl_Minimal - Simple half-speed BG parallax.
     */
    private void fillMinimal(int cameraX) {
        short fgScroll = negWord(cameraX);
        short bgScroll = (short) -(cameraX >> 1);
        int packed = packScrollWords(fgScroll, bgScroll);

        for (int i = 0; i < VISIBLE_LINES; i++) {
            horizScrollBuf[i] = packed;
        }
    }

    // ========== 68000 Arithmetic Helpers ==========

    /**
     * Extract low 16 bits as signed word.
     * Equivalent to treating a value as a 68k word register.
     */
    public static short wordOf(int value) {
        return (short) value;
    }

    /**
     * Negate a value and return as 16-bit word.
     * Equivalent to: neg.w d0
     */
    public static short negWord(int value) {
        return (short) (-value);
    }

    /**
     * Arithmetic shift right of a 16-bit signed value.
     * Equivalent to: asr.w #n,d0
     */
    public static short asrWord(int value, int shift) {
        short word = (short) value;
        return (short) (word >> shift);
    }

    /**
     * Signed 16-bit division.
     * Equivalent to: divs.w #divisor,d0
     * Returns quotient in low word (matches 68k).
     */
    public static short divsWord(int dividend, int divisor) {
        if (divisor == 0)
            return 0;
        short signedDividend = (short) dividend;
        short signedDivisor = (short) divisor;
        return (short) (signedDividend / signedDivisor);
    }

    /**
     * Unsigned 16-bit division.
     * Equivalent to: divu.w #divisor,d0
     * Returns quotient in low word.
     */
    public static short divuWord(int dividend, int divisor) {
        if (divisor == 0)
            return 0;
        int unsignedDividend = dividend & 0xFFFF;
        int unsignedDivisor = divisor & 0xFFFF;
        return (short) (unsignedDividend / unsignedDivisor);
    }

    /**
     * Pack FG and BG scroll words into a single int.
     * Format matches VDP HScroll table: FG in high word, BG in low word.
     * This matches: move.l d0,(a1)+ where d0 contains both words.
     */
    public static int packScrollWords(short fg, short bg) {
        return ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
    }

    /**
     * Extract FG scroll from packed value.
     */
    public static short unpackFG(int packed) {
        return (short) (packed >> 16);
    }

    /**
     * Extract BG scroll from packed value.
     */
    public static short unpackBG(int packed) {
        return (short) packed;
    }

    // ========== Accessors ==========

    public int[] getHorizScrollBuf() {
        return horizScrollBuf;
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    public BackgroundCamera getBackgroundCamera() {
        return bgCamera;
    }

    public boolean isLoaded() {
        return tablesLoaded;
    }
}
