package uk.co.jamesj999.sonic.game.sonic2.titlecard;

/**
 * Sprite mapping data for Sonic 2 title cards.
 * Extracted from s2.asm MapUnc_TitleCards (lines 28336-28589).
 *
 * Each frame contains an array of SpritePiece records defining the sprite pieces.
 * The spritePiece macro format: spritePiece xOff, yOff, width, height, tileIndex, hFlip, vFlip, palette, priority
 */
public final class TitleCardMappings {

    private TitleCardMappings() {}

    /**
     * Represents a single sprite piece in a mapping frame.
     *
     * @param xOffset X offset from sprite center
     * @param yOffset Y offset from sprite center
     * @param widthTiles Width in 8x8 tiles
     * @param heightTiles Height in 8x8 tiles
     * @param tileIndex VRAM tile index
     * @param hFlip Horizontal flip
     * @param vFlip Vertical flip
     * @param paletteIndex Palette line (0-3)
     * @param priority High priority flag
     */
    public record SpritePiece(
            int xOffset, int yOffset,
            int widthTiles, int heightTiles,
            int tileIndex,
            boolean hFlip, boolean vFlip,
            int paletteIndex, boolean priority) {}

    // Frame indices for zone names (matches zone order in game)
    public static final int FRAME_EHZ = 0;   // Emerald Hill
    public static final int FRAME_MTZ = 1;   // Metropolis
    public static final int FRAME_HTZ = 2;   // Hill Top
    public static final int FRAME_HPZ = 3;   // Hidden Palace
    public static final int FRAME_OOZ = 4;   // Oil Ocean
    public static final int FRAME_MCZ = 5;   // Mystic Cave
    public static final int FRAME_CNZ = 6;   // Casino Night
    public static final int FRAME_CPZ = 7;   // Chemical Plant
    public static final int FRAME_ARZ = 8;   // Aquatic Ruin
    public static final int FRAME_SCZ = 9;   // Sky Chase
    public static final int FRAME_WFZ = 10;  // Wing Fortress
    public static final int FRAME_DEZ = 11;  // Death Egg

    // Miscellaneous frames (frame index = 0x11 + offset in original)
    public static final int FRAME_ZONE = 0x11;       // "ZONE" text
    public static final int FRAME_ACT_1 = 0x12;      // Act 1 number
    public static final int FRAME_ACT_2 = 0x13;      // Act 2 number
    public static final int FRAME_ACT_3 = 0x14;      // Act 3 number
    public static final int FRAME_STH = 0x15;        // "SONIC THE HEDGEHOG" yellow bar
    public static final int FRAME_RED_STRIPES = 0x16; // Red stripes (left swoosh)

    // Zone name frame mapping by internal zone ID
    // Maps from internal zone index to frame index
    private static final int[] ZONE_TO_FRAME = {
            FRAME_EHZ,  // 0 - Emerald Hill
            FRAME_CPZ,  // 1 - Chemical Plant
            FRAME_ARZ,  // 2 - Aquatic Ruin
            FRAME_CNZ,  // 3 - Casino Night
            FRAME_HTZ,  // 4 - Hill Top
            FRAME_MCZ,  // 5 - Mystic Cave
            FRAME_OOZ,  // 6 - Oil Ocean
            FRAME_MTZ,  // 7 - Metropolis
            FRAME_SCZ,  // 8 - Sky Chase
            FRAME_WFZ,  // 9 - Wing Fortress
            FRAME_DEZ,  // 10 - Death Egg
    };

    /**
     * Gets the mapping frame index for a zone.
     * @param zoneIndex Internal zone index (0-10)
     * @return Frame index for zone name mapping
     */
    public static int getZoneFrame(int zoneIndex) {
        if (zoneIndex >= 0 && zoneIndex < ZONE_TO_FRAME.length) {
            return ZONE_TO_FRAME[zoneIndex];
        }
        return FRAME_EHZ; // Default to Emerald Hill
    }

    /**
     * Gets the act number frame.
     * @param actNumber Act number (0-2)
     * @return Frame index for act number (1, 2, or 3)
     */
    public static int getActFrame(int actNumber) {
        return FRAME_ACT_1 + Math.min(actNumber, 2);
    }

    /**
     * Gets the sprite pieces for a mapping frame.
     * @param frameIndex Frame index
     * @return Array of sprite pieces
     */
    public static SpritePiece[] getFrame(int frameIndex) {
        return switch (frameIndex) {
            case FRAME_EHZ -> TC_EHZ;
            case FRAME_MTZ -> TC_MTZ;
            case FRAME_HTZ -> TC_HTZ;
            case FRAME_HPZ -> TC_HPZ;
            case FRAME_OOZ -> TC_OOZ;
            case FRAME_MCZ -> TC_MCZ;
            case FRAME_CNZ -> TC_CNZ;
            case FRAME_CPZ -> TC_CPZ;
            case FRAME_ARZ -> TC_ARZ;
            case FRAME_SCZ -> TC_SCZ;
            case FRAME_WFZ -> TC_WFZ;
            case FRAME_DEZ -> TC_DEZ;
            case FRAME_ZONE -> TC_ZONE;
            case FRAME_ACT_1 -> TC_No1;
            case FRAME_ACT_2 -> TC_No2;
            case FRAME_ACT_3 -> TC_No3;
            case FRAME_STH -> TC_STH;
            case FRAME_RED_STRIPES -> TC_RedStripes;
            default -> new SpritePiece[0];
        };
    }

    // ========== Zone Name Mappings ==========

    // EMERALD HILL
    private static final SpritePiece[] TC_EHZ = {
            new SpritePiece(-0x3D, 0, 2, 2, 0x580, false, false, 0, true), // E
            new SpritePiece(-0x30, 0, 3, 2, 0x5DE, false, false, 0, true), // M
            new SpritePiece(-0x18, 0, 2, 2, 0x580, false, false, 0, true), // E
            new SpritePiece(-8, 0, 2, 2, 0x5E4, false, false, 0, true),    // R
            new SpritePiece(8, 0, 2, 2, 0x5E8, false, false, 0, true),     // A
            new SpritePiece(0x18, 0, 2, 2, 0x5EC, false, false, 0, true),  // L
            new SpritePiece(0x28, 0, 2, 2, 0x5F0, false, false, 0, true),  // D
            new SpritePiece(0x48, 0, 2, 2, 0x5F4, false, false, 0, true),  // H
            new SpritePiece(0x58, 0, 1, 2, 0x5F8, false, false, 0, true),  // I
            new SpritePiece(0x60, 0, 2, 2, 0x5EC, false, false, 0, true),  // L
            new SpritePiece(0x70, 0, 2, 2, 0x5EC, false, false, 0, true),  // L
    };

    // METROPOLIS
    private static final SpritePiece[] TC_MTZ = {
            new SpritePiece(-0x20, 0, 3, 2, 0x5DE, false, false, 0, true), // M
            new SpritePiece(-8, 0, 2, 2, 0x580, false, false, 0, true),    // E
            new SpritePiece(8, 0, 2, 2, 0x5E4, false, false, 0, true),     // T
            new SpritePiece(0x18, 0, 2, 2, 0x5E8, false, false, 0, true),  // R
            new SpritePiece(0x28, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x38, 0, 2, 2, 0x5EC, false, false, 0, true),  // P
            new SpritePiece(0x48, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x58, 0, 2, 2, 0x5F0, false, false, 0, true),  // L
            new SpritePiece(0x68, 0, 1, 2, 0x5F4, false, false, 0, true),  // I
            new SpritePiece(0x70, 0, 2, 2, 0x5F6, false, false, 0, true),  // S
    };

    // HILL TOP
    private static final SpritePiece[] TC_HTZ = {
            new SpritePiece(8, 0, 2, 2, 0x5DE, false, false, 0, true),     // H
            new SpritePiece(0x18, 0, 1, 2, 0x5E2, false, false, 0, true),  // I
            new SpritePiece(0x20, 0, 2, 2, 0x5E4, false, false, 0, true),  // L
            new SpritePiece(0x30, 0, 2, 2, 0x5E4, false, false, 0, true),  // L
            new SpritePiece(0x51, 0, 2, 2, 0x5E8, false, false, 0, true),  // T
            new SpritePiece(0x60, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x70, 0, 2, 2, 0x5EC, false, false, 0, true),  // P
    };

    // HIDDEN PALACE
    private static final SpritePiece[] TC_HPZ = {
            new SpritePiece(-0x48, 0, 2, 2, 0x5DE, false, false, 0, true), // H
            new SpritePiece(-0x38, 0, 1, 2, 0x5E2, false, false, 0, true), // I
            new SpritePiece(-0x30, 0, 2, 2, 0x5E4, false, false, 0, true), // D
            new SpritePiece(-0x20, 0, 2, 2, 0x5E4, false, false, 0, true), // D
            new SpritePiece(-0x10, 0, 2, 2, 0x580, false, false, 0, true), // E
            new SpritePiece(0, 0, 2, 2, 0x584, false, false, 0, true),     // N
            new SpritePiece(0x20, 0, 2, 2, 0x5E8, false, false, 0, true),  // P
            new SpritePiece(0x30, 0, 2, 2, 0x5EC, false, false, 0, true),  // A
            new SpritePiece(0x40, 0, 2, 2, 0x5F0, false, false, 0, true),  // L
            new SpritePiece(0x50, 0, 2, 2, 0x5EC, false, false, 0, true),  // A
            new SpritePiece(0x60, 0, 2, 2, 0x5F4, false, false, 0, true),  // C
            new SpritePiece(0x70, 0, 2, 2, 0x580, false, false, 0, true),  // E
    };

    // OIL OCEAN
    private static final SpritePiece[] TC_OOZ = {
            new SpritePiece(-5, 0, 2, 2, 0x588, false, false, 0, true),    // O
            new SpritePiece(0xB, 0, 1, 2, 0x5DE, false, false, 0, true),   // I
            new SpritePiece(0x13, 0, 2, 2, 0x5E0, false, false, 0, true),  // L
            new SpritePiece(0x33, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x43, 0, 2, 2, 0x5E4, false, false, 0, true),  // C
            new SpritePiece(0x53, 0, 2, 2, 0x580, false, false, 0, true),  // E
            new SpritePiece(0x60, 0, 2, 2, 0x5E8, false, false, 0, true),  // A
            new SpritePiece(0x70, 0, 2, 2, 0x584, false, false, 0, true),  // N
    };

    // MYSTIC CAVE
    private static final SpritePiece[] TC_MCZ = {
            new SpritePiece(-0x30, 0, 3, 2, 0x5DE, false, false, 0, true), // M
            new SpritePiece(-0x18, 0, 2, 2, 0x5E4, false, false, 0, true), // Y
            new SpritePiece(-8, 0, 2, 2, 0x5E8, false, false, 0, true),    // S
            new SpritePiece(8, 0, 2, 2, 0x5EC, false, false, 0, true),     // T
            new SpritePiece(0x18, 0, 1, 2, 0x5F0, false, false, 0, true),  // I
            new SpritePiece(0x20, 0, 2, 2, 0x5F2, false, false, 0, true),  // C
            new SpritePiece(0x41, 0, 2, 2, 0x5F2, false, false, 0, true),  // C
            new SpritePiece(0x50, 0, 2, 2, 0x5F6, false, false, 0, true),  // A
            new SpritePiece(0x60, 0, 2, 2, 0x5FA, false, false, 0, true),  // V
            new SpritePiece(0x70, 0, 2, 2, 0x580, false, false, 0, true),  // E
    };

    // CASINO NIGHT
    private static final SpritePiece[] TC_CNZ = {
            new SpritePiece(-0x2F, 0, 2, 2, 0x5DE, false, false, 0, true), // C
            new SpritePiece(-0x20, 0, 2, 2, 0x5E2, false, false, 0, true), // A
            new SpritePiece(-0x10, 0, 2, 2, 0x5E6, false, false, 0, true), // S
            new SpritePiece(0, 0, 1, 2, 0x5EA, false, false, 0, true),     // I
            new SpritePiece(8, 0, 2, 2, 0x584, false, false, 0, true),     // N
            new SpritePiece(0x18, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x38, 0, 2, 2, 0x584, false, false, 0, true),  // N
            new SpritePiece(0x48, 0, 1, 2, 0x5EA, false, false, 0, true),  // I
            new SpritePiece(0x50, 0, 2, 2, 0x5EC, false, false, 0, true),  // G
            new SpritePiece(0x60, 0, 2, 2, 0x5F0, false, false, 0, true),  // H
            new SpritePiece(0x70, 0, 2, 2, 0x5F4, false, false, 0, true),  // T
    };

    // CHEMICAL PLANT
    private static final SpritePiece[] TC_CPZ = {
            new SpritePiece(-0x5C, 0, 2, 2, 0x5DE, false, false, 0, true), // C
            new SpritePiece(-0x4C, 0, 2, 2, 0x5E2, false, false, 0, true), // H
            new SpritePiece(-0x3C, 0, 2, 2, 0x580, false, false, 0, true), // E
            new SpritePiece(-0x2F, 0, 3, 2, 0x5E6, false, false, 0, true), // M
            new SpritePiece(-0x17, 0, 1, 2, 0x5EC, false, false, 0, true), // I
            new SpritePiece(-0xF, 0, 2, 2, 0x5DE, false, false, 0, true),  // C
            new SpritePiece(0, 0, 2, 2, 0x5EE, false, false, 0, true),     // A
            new SpritePiece(0x10, 0, 2, 2, 0x5F2, false, false, 0, true),  // L
            new SpritePiece(0x31, 0, 2, 2, 0x5F6, false, false, 0, true),  // P
            new SpritePiece(0x41, 0, 2, 2, 0x5F2, false, false, 0, true),  // L
            new SpritePiece(0x50, 0, 2, 2, 0x5EE, false, false, 0, true),  // A
            new SpritePiece(0x60, 0, 2, 2, 0x584, false, false, 0, true),  // N
            new SpritePiece(0x70, 0, 2, 2, 0x5FA, false, false, 0, true),  // T
    };

    // AQUATIC RUIN
    private static final SpritePiece[] TC_ARZ = {
            new SpritePiece(-0x2E, 0, 2, 2, 0x5DE, false, false, 0, true), // A
            new SpritePiece(-0x1E, 0, 2, 2, 0x5E2, false, false, 0, true), // Q
            new SpritePiece(-0xE, 0, 2, 2, 0x5E6, false, false, 0, true),  // U
            new SpritePiece(0, 0, 2, 2, 0x5DE, false, false, 0, true),     // A
            new SpritePiece(0x10, 0, 2, 2, 0x5EA, false, false, 0, true),  // T
            new SpritePiece(0x20, 0, 1, 2, 0x5EE, false, false, 0, true),  // I
            new SpritePiece(0x28, 0, 2, 2, 0x5F0, false, false, 0, true),  // C
            new SpritePiece(0x48, 0, 2, 2, 0x5F4, false, false, 0, true),  // R
            new SpritePiece(0x58, 0, 2, 2, 0x5E6, false, false, 0, true),  // U
            new SpritePiece(0x68, 0, 1, 2, 0x5EE, false, false, 0, true),  // I
            new SpritePiece(0x70, 0, 2, 2, 0x584, false, false, 0, true),  // N
    };

    // SKY CHASE
    private static final SpritePiece[] TC_SCZ = {
            new SpritePiece(-0x10, 0, 2, 2, 0x5DE, false, false, 0, true), // S
            new SpritePiece(0, 0, 2, 2, 0x5E2, false, false, 0, true),     // K
            new SpritePiece(0x10, 0, 2, 2, 0x5E6, false, false, 0, true),  // Y
            new SpritePiece(0x30, 0, 2, 2, 0x5EA, false, false, 0, true),  // C
            new SpritePiece(0x40, 0, 2, 2, 0x5EE, false, false, 0, true),  // H
            new SpritePiece(0x50, 0, 2, 2, 0x5F2, false, false, 0, true),  // A
            new SpritePiece(0x60, 0, 2, 2, 0x5DE, false, false, 0, true),  // S
            new SpritePiece(0x70, 0, 2, 2, 0x580, false, false, 0, true),  // E
    };

    // WING FORTRESS
    private static final SpritePiece[] TC_WFZ = {
            new SpritePiece(-0x4F, 0, 3, 2, 0x5DE, false, false, 0, true), // W
            new SpritePiece(-0x38, 0, 1, 2, 0x5E4, false, false, 0, true), // I
            new SpritePiece(-0x30, 0, 2, 2, 0x584, false, false, 0, true), // N
            new SpritePiece(-0x20, 0, 2, 2, 0x5E6, false, false, 0, true), // G
            new SpritePiece(1, 0, 2, 2, 0x5EA, false, false, 0, true),     // F
            new SpritePiece(0x10, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x20, 0, 2, 2, 0x5EE, false, false, 0, true),  // R
            new SpritePiece(0x30, 0, 2, 2, 0x5F2, false, false, 0, true),  // T
            new SpritePiece(0x40, 0, 2, 2, 0x5EE, false, false, 0, true),  // R
            new SpritePiece(0x50, 0, 2, 2, 0x580, false, false, 0, true),  // E
            new SpritePiece(0x5F, 0, 2, 2, 0x5F6, false, false, 0, true),  // S
            new SpritePiece(0x6F, 0, 2, 2, 0x5F6, false, false, 0, true),  // S
    };

    // DEATH EGG
    private static final SpritePiece[] TC_DEZ = {
            new SpritePiece(-0xE, 0, 2, 2, 0x5DE, false, false, 0, true),  // D
            new SpritePiece(2, 0, 2, 2, 0x580, false, false, 0, true),     // E
            new SpritePiece(0x10, 0, 2, 2, 0x5E2, false, false, 0, true),  // A
            new SpritePiece(0x20, 0, 2, 2, 0x5E6, false, false, 0, true),  // T
            new SpritePiece(0x30, 0, 2, 2, 0x5EA, false, false, 0, true),  // H
            new SpritePiece(0x51, 0, 2, 2, 0x580, false, false, 0, true),  // E
            new SpritePiece(0x60, 0, 2, 2, 0x5EE, false, false, 0, true),  // G
            new SpritePiece(0x70, 0, 2, 2, 0x5EE, false, false, 0, true),  // G
    };

    // ========== Miscellaneous Mappings ==========

    // "ZONE" text
    private static final SpritePiece[] TC_ZONE = {
            new SpritePiece(1, 0, 2, 2, 0x58C, false, false, 0, true),     // Z
            new SpritePiece(0x10, 0, 2, 2, 0x588, false, false, 0, true),  // O
            new SpritePiece(0x20, 0, 2, 2, 0x584, false, false, 0, true),  // N
            new SpritePiece(0x30, 0, 2, 2, 0x580, false, false, 0, true),  // E
    };

    // Act number 1
    private static final SpritePiece[] TC_No1 = {
            new SpritePiece(0, 0, 2, 4, 0x590, false, false, 1, true),
    };

    // Act number 2
    private static final SpritePiece[] TC_No2 = {
            new SpritePiece(0, 0, 3, 4, 0x598, false, false, 1, true),
    };

    // Act number 3
    private static final SpritePiece[] TC_No3 = {
            new SpritePiece(0, 0, 3, 4, 0x5A4, false, false, 1, true),
    };

    // "SONIC THE HEDGEHOG" yellow bar
    private static final SpritePiece[] TC_STH = {
            new SpritePiece(-0x48, 0, 4, 2, 0x5B0, false, false, 0, true),
            new SpritePiece(-0x28, 0, 4, 2, 0x5B8, false, false, 0, true),
            new SpritePiece(-8, 0, 4, 2, 0x5C0, false, false, 0, true),
            new SpritePiece(0x18, 0, 4, 2, 0x5C8, false, false, 0, true),
            new SpritePiece(0x38, 0, 2, 2, 0x5D0, false, false, 0, true),
    };

    // Red stripes (left swoosh)
    private static final SpritePiece[] TC_RedStripes = {
            new SpritePiece(0, -0x70, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, -0x50, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, -0x30, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, -0x10, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, 0x10, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, 0x30, 1, 4, 0x5D4, false, false, 0, true),
            new SpritePiece(0, 0x50, 1, 4, 0x5D4, false, false, 0, true),
    };

    // ========== Zones that skip act number display ==========
    // Sky Chase, Wing Fortress, Death Egg are single-act zones

    /**
     * Returns true if this zone should skip the act number display.
     * @param zoneIndex Internal zone index
     * @return true if single-act zone
     */
    public static boolean isSingleActZone(int zoneIndex) {
        // Sky Chase (8), Wing Fortress (9), Death Egg (10)
        return zoneIndex >= 8;
    }
}
