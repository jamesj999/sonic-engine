package uk.co.jamesj999.sonic.level.scroll;

/**
 * Tracks background camera positions as per original Sonic 2 RAM.
 * Implements InitCam_* routines for zone-specific initialization.
 *
 * RAM variables replicated:
 * - Camera_BG_X_pos, Camera_BG_Y_pos (primary BG camera)
 * - Camera_BG2_X_pos, Camera_BG2_Y_pos (secondary, for CPZ etc.)
 * - Camera_BG3_X_pos, Camera_BG3_Y_pos (tertiary, for SCZ)
 * - Camera_ARZ_BG_X_pos (special ARZ variable)
 * - TempArray_LayerDef (256 bytes for intermediate scroll calculations)
 */
public class BackgroundCamera {

    // Primary BG camera (stored as 32-bit, but word portion used for scroll)
    private int bgXPos;
    private int bgYPos;

    // Secondary BG camera (CPZ, multi-layer zones)
    private int bg2XPos;
    private int bg2YPos;

    // Tertiary BG camera (SCZ)
    private int bg3XPos;
    private int bg3YPos;

    // Special ARZ variable
    private int arzBgXPos;

    // Camera diffs (delta from previous frame)
    private int bgXPosDiff;
    private int bgYPosDiff;

    // TempArray_LayerDef - used for intermediate scroll calculations
    // In original, offsets like +$22 store cloud animation counters etc.
    private final byte[] tempArrayLayerDef = new byte[256];

    // Zone IDs - must match LevelManager list indices
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_CPZ = 1;
    private static final int ZONE_ARZ = 2;
    private static final int ZONE_CNZ = 3;
    private static final int ZONE_HTZ = 4;
    private static final int ZONE_MCZ = 5;
    private static final int ZONE_OOZ = 6;
    private static final int ZONE_MTZ = 7;
    private static final int ZONE_SCZ = 8;
    private static final int ZONE_WFZ = 9;
    private static final int ZONE_DEZ = 10;

    public BackgroundCamera() {
        reset();
    }

    /**
     * Reset all camera positions to zero.
     */
    public void reset() {
        bgXPos = 0;
        bgYPos = 0;
        bg2XPos = 0;
        bg2YPos = 0;
        bg3XPos = 0;
        bg3YPos = 0;
        arzBgXPos = 0;
        bgXPosDiff = 0;
        bgYPosDiff = 0;
        java.util.Arrays.fill(tempArrayLayerDef, (byte) 0);
    }

    /**
     * Initialize background camera for a zone.
     * Replicates InitCameraValues -> InitCam_* dispatch.
     *
     * Reference: s2.asm InitCameraValues (ROM $C258) and InitCam_Index ($C296)
     */
    public void init(int zoneId, int actId, int cameraX, int cameraY) {
        reset();

        switch (zoneId) {
            case ZONE_EHZ:
                // InitCam_EHZ ($C2B8): Clears all BG positions
                // Already reset, nothing more needed
                break;

            case ZONE_MTZ:
                // InitCam_Std ($C2E4):
                // Camera_BG_Y_pos = Camera_Y_pos >> 2
                // Camera_BG_X_pos = Camera_X_pos >> 3
                bgYPos = cameraY >> 2;
                bgXPos = cameraX >> 3;
                break;

            case ZONE_HTZ:
                // InitCam_HTZ ($C2D4): Clears all BG positions and first 12 bytes of TempArray_LayerDef
                // reset() already zeros bgXPos/bgYPos, just need to clear TempArray_LayerDef
                // The disassembly clears: Camera_BG_X_pos, Camera_BG_Y_pos, Camera_BG2_Y_pos,
                // Camera_BG3_Y_pos, and first 12 bytes of TempArray_LayerDef
                setTempWord(0, 0);
                setTempWord(2, 0);
                setTempWord(4, 0);
                setTempWord(6, 0);
                setTempWord(8, 0);
                setTempWord(10, 0);
                break;

            case ZONE_OOZ:
                // InitCam_OOZ ($C322):
                // Camera_BG_Y_pos = (Camera_Y_pos >> 3) + $50
                // Camera_BG_X_pos = 0
                bgYPos = (cameraY >> 3) + 0x50;
                bgXPos = 0;
                break;

            case ZONE_MCZ:
                // InitCam_MCZ ($C332): Act-dependent
                bgXPos = 0;
                if (actId == 0) {
                    // Act 1: Camera_BG_Y_pos = (Camera_Y_pos / 3) - $140
                    bgYPos = (cameraY / 3) - 0x140;
                } else {
                    // Act 2: Camera_BG_Y_pos = (Camera_Y_pos / 6) - $10
                    bgYPos = (cameraY / 6) - 0x10;
                }
                break;

            case ZONE_CNZ:
                // InitCam_CNZ ($C364): Clear BG X and Y
                bgXPos = 0;
                bgYPos = 0;
                break;

            case ZONE_CPZ:
                // InitCam_CPZ ($C372):
                // Camera_BG_Y_pos = Camera_Y_pos >> 2
                // Camera_BG2_X_pos = Camera_X_pos >> 1
                // Camera_BG_X_pos = Camera_X_pos >> 2
                bgYPos = cameraY >> 2;
                bg2XPos = cameraX >> 1;
                bgXPos = cameraX >> 2;
                break;

            case ZONE_ARZ:
                // InitCam_ARZ ($C38C): Act-dependent + multiply
                if (actId != 0) {
                    // Act 2: Camera_BG_Y_pos = (Camera_Y_pos - $E0) >> 1
                    bgYPos = (cameraY - 0xE0) >> 1;
                } else {
                    // Act 1: Camera_BG_Y_pos = Camera_Y_pos - $180
                    bgYPos = cameraY - 0x180;
                }
                // Camera_BG_X_pos = (Camera_X_pos * $0119) >> 8
                // Also stored to Camera_ARZ_BG_X_pos
                int mulResult = (cameraX * 0x0119) >> 8;
                bgXPos = mulResult;
                arzBgXPos = mulResult;
                bg2YPos = 0;
                bg3YPos = 0;
                break;

            case ZONE_SCZ:
                // InitCam_SCZ ($C3C6): Clear BG X and Y
                bgXPos = 0;
                bgYPos = 0;
                break;

            default:
                // Default: BG = FG before zone-specific adjustments
                // This matches the original "copy camera to BG" fallback
                bgXPos = cameraX;
                bgYPos = cameraY;
                break;
        }
    }

    /**
     * Update background camera each frame based on foreground camera movement.
     * This would replicate the per-frame BG camera tracking in SwScrl routines.
     */
    public void updateFromForeground(int fgX, int fgY, int fgXDiff, int fgYDiff, int zoneId) {
        // Most zones update BG based on FG movement with zone-specific ratios
        // For now, store diffs for routines that need them
        bgXPosDiff = fgXDiff;
        bgYPosDiff = fgYDiff;

        // Zone-specific BG tracking would go here
        // EHZ example: BG doesn't track X at all (static BG camera)
        // MTZ: BG tracks proportionally
    }

    // ========== TempArray_LayerDef accessors ==========

    public byte getTempByte(int offset) {
        if (offset < 0 || offset >= tempArrayLayerDef.length)
            return 0;
        return tempArrayLayerDef[offset];
    }

    public void setTempByte(int offset, byte value) {
        if (offset >= 0 && offset < tempArrayLayerDef.length) {
            tempArrayLayerDef[offset] = value;
        }
    }

    public int getTempWord(int offset) {
        if (offset < 0 || offset + 1 >= tempArrayLayerDef.length)
            return 0;
        return ((tempArrayLayerDef[offset] & 0xFF) << 8) | (tempArrayLayerDef[offset + 1] & 0xFF);
    }

    public void setTempWord(int offset, int value) {
        if (offset >= 0 && offset + 1 < tempArrayLayerDef.length) {
            tempArrayLayerDef[offset] = (byte) ((value >> 8) & 0xFF);
            tempArrayLayerDef[offset + 1] = (byte) (value & 0xFF);
        }
    }

    // ========== Getters/Setters ==========

    public int getBgXPos() {
        return bgXPos;
    }

    public void setBgXPos(int bgXPos) {
        this.bgXPos = bgXPos;
    }

    public int getBgYPos() {
        return bgYPos;
    }

    public void setBgYPos(int bgYPos) {
        this.bgYPos = bgYPos;
    }

    public int getBg2XPos() {
        return bg2XPos;
    }

    public void setBg2XPos(int bg2XPos) {
        this.bg2XPos = bg2XPos;
    }

    public int getBg2YPos() {
        return bg2YPos;
    }

    public void setBg2YPos(int bg2YPos) {
        this.bg2YPos = bg2YPos;
    }

    public int getBg3XPos() {
        return bg3XPos;
    }

    public void setBg3XPos(int bg3XPos) {
        this.bg3XPos = bg3XPos;
    }

    public int getBg3YPos() {
        return bg3YPos;
    }

    public void setBg3YPos(int bg3YPos) {
        this.bg3YPos = bg3YPos;
    }

    public int getArzBgXPos() {
        return arzBgXPos;
    }

    public void setArzBgXPos(int arzBgXPos) {
        this.arzBgXPos = arzBgXPos;
    }

    public int getBgXPosDiff() {
        return bgXPosDiff;
    }

    public int getBgYPosDiff() {
        return bgYPosDiff;
    }
}
