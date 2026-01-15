package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Sprite mapping data for Special Stage objects (rings and bombs).
 *
 * Based on:
 * - mappings/sprite/obj5A_5B_60.asm (rings)
 * - mappings/sprite/obj61.asm (bombs)
 *
 * Each mapping frame defines sprite pieces with position, size, and tile offset.
 */
public class Sonic2SpecialStageSpriteData {

    /**
     * Represents a single sprite piece within a mapping frame.
     */
    public static class SpritePiece {
        public final int xOffset;      // X offset from center
        public final int yOffset;      // Y offset from center
        public final int widthTiles;   // Width in 8x8 tiles
        public final int heightTiles;  // Height in 8x8 tiles
        public final int tileIndex;    // Starting tile index in art
        public final boolean hFlip;
        public final boolean vFlip;

        public SpritePiece(int xOffset, int yOffset, int widthTiles, int heightTiles,
                          int tileIndex, boolean hFlip, boolean vFlip) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.widthTiles = widthTiles;
            this.heightTiles = heightTiles;
            this.tileIndex = tileIndex;
            this.hFlip = hFlip;
            this.vFlip = vFlip;
        }
    }

    /**
     * Ring mapping frames from obj5A_5B_60.asm.
     * Indices 0-9: Normal sizes (perspective)
     * Indices 10-20: Spin animation frames
     * Indices 21+: Sparkle animation
     */
    public static final SpritePiece[][] RING_MAPPINGS = {
        // Frame 0: 1x1 @ tile 0 (smallest)
        { new SpritePiece(-4, -4, 1, 1, 0, false, false) },
        // Frame 1: 1x1 @ tile 1
        { new SpritePiece(-4, -4, 1, 1, 1, false, false) },
        // Frame 2: 1x1 @ tile 2
        { new SpritePiece(-4, -4, 1, 1, 2, false, false) },
        // Frame 3: 2x2 @ tile 3
        { new SpritePiece(-8, -8, 2, 2, 3, false, false) },
        // Frame 4: 2x2 @ tile 7
        { new SpritePiece(-8, -8, 2, 2, 7, false, false) },
        // Frame 5: 2x2 @ tile $B
        { new SpritePiece(-8, -8, 2, 2, 0x0B, false, false) },
        // Frame 6: 2x2 @ tile $F
        { new SpritePiece(-8, -8, 2, 2, 0x0F, false, false) },
        // Frame 7: 3x3 @ tile $13
        { new SpritePiece(-12, -12, 3, 3, 0x13, false, false) },
        // Frame 8: 3x3 @ tile $1C
        { new SpritePiece(-12, -12, 3, 3, 0x1C, false, false) },
        // Frame 9: 3x3 @ tile $25
        { new SpritePiece(-12, -12, 3, 3, 0x25, false, false) },
        // Frame $A (10): 1x1 @ tile $2E (spin frame)
        { new SpritePiece(-4, -4, 1, 1, 0x2E, false, false) },
        // Frame $B (11): 1x1 @ tile $2F
        { new SpritePiece(-4, -4, 1, 1, 0x2F, false, false) },
        // Frame $C (12): 1x1 @ tile $30
        { new SpritePiece(-4, -4, 1, 1, 0x30, false, false) },
        // Frame $D (13): 1x2 @ tile $31
        { new SpritePiece(-4, -8, 1, 2, 0x31, false, false) },
        // Frame $E (14): 2x2 @ tile $33
        { new SpritePiece(-8, -8, 2, 2, 0x33, false, false) },
        // Frame $F (15): 2x2 @ tile $37
        { new SpritePiece(-8, -8, 2, 2, 0x37, false, false) },
        // Frame $10 (16): 2x2 @ tile $3B
        { new SpritePiece(-8, -8, 2, 2, 0x3B, false, false) },
        // Frame $11 (17): 2x3 @ tile $3F
        { new SpritePiece(-8, -12, 2, 3, 0x3F, false, false) },
        // Frame $12 (18): 2x3 @ tile $45
        { new SpritePiece(-8, -12, 2, 3, 0x45, false, false) },
        // Frame $13 (19): 3x3 @ tile $4B
        { new SpritePiece(-12, -12, 3, 3, 0x4B, false, false) },
        // Frame $14 (20): 1x1 @ tile $54 (spin edge frame)
        { new SpritePiece(-4, -4, 1, 1, 0x54, false, false) },
        // Frame $15 (21): 1x1 @ tile $55
        { new SpritePiece(-4, -4, 1, 1, 0x55, false, false) },
        // Frame $16 (22): 1x1 @ tile $56
        { new SpritePiece(-4, -4, 1, 1, 0x56, false, false) },
        // Frame $17 (23): 1x2 @ tile $57
        { new SpritePiece(-4, -8, 1, 2, 0x57, false, false) },
        // Frame $18 (24): 1x2 @ tile $59
        { new SpritePiece(-4, -8, 1, 2, 0x59, false, false) },
        // Frame $19 (25): 1x2 @ tile $5B
        { new SpritePiece(-4, -8, 1, 2, 0x5B, false, false) },
        // Frame $1A (26): 1x2 @ tile $5D
        { new SpritePiece(-4, -8, 1, 2, 0x5D, false, false) },
        // Frame $1B (27): 1x3 @ tile $5F
        { new SpritePiece(-4, -12, 1, 3, 0x5F, false, false) },
        // Frame $1C (28): 1x3 @ tile $62
        { new SpritePiece(-4, -12, 1, 3, 0x62, false, false) },
        // Frame $1D (29): 1x3 @ tile $65
        { new SpritePiece(-4, -12, 1, 3, 0x65, false, false) },
        // Frame $1E (30): Sparkle frame 1 (2x4 + 2x2)
        { new SpritePiece(-16, -16, 2, 4, 0, false, false),
          new SpritePiece(0, -8, 2, 2, 8, false, false) },
        // Frame $1F (31): Sparkle frame 2 (4x3 + 1x1)
        { new SpritePiece(-16, -16, 4, 3, 0x0C, false, false),
          new SpritePiece(-8, 8, 1, 1, 0x18, false, false) },
        // Frame $20 (32): Sparkle frame 3 (1x3 + 3x3)
        { new SpritePiece(-16, -16, 1, 3, 0x19, false, false),
          new SpritePiece(-8, -8, 3, 3, 0x1C, false, false) },
    };

    /**
     * Bomb mapping frames from obj61.asm.
     * Indices 0-9: Normal sizes (perspective)
     * Index 10-12: Explosion animation
     */
    public static final SpritePiece[][] BOMB_MAPPINGS = {
        // Frame 0: 1x1 @ tile 0 (smallest)
        { new SpritePiece(-4, -4, 1, 1, 0, false, false) },
        // Frame 1: 1x1 @ tile 1
        { new SpritePiece(-4, -4, 1, 1, 1, false, false) },
        // Frame 2: 2x2 @ tile 2
        { new SpritePiece(-8, -8, 2, 2, 2, false, false) },
        // Frame 3: 2x2 @ tile 6
        { new SpritePiece(-8, -8, 2, 2, 6, false, false) },
        // Frame 4: 2x2 @ tile $A
        { new SpritePiece(-8, -8, 2, 2, 0x0A, false, false) },
        // Frame 5: 3x3 @ tile $E
        { new SpritePiece(-12, -12, 3, 3, 0x0E, false, false) },
        // Frame 6: 3x3 @ tile $17
        { new SpritePiece(-12, -12, 3, 3, 0x17, false, false) },
        // Frame 7: 4x4 @ tile $20
        { new SpritePiece(-16, -16, 4, 4, 0x20, false, false) },
        // Frame 8: 4x4 @ tile $30
        { new SpritePiece(-16, -16, 4, 4, 0x30, false, false) },
        // Frame 9: 4x4 @ tile $40
        { new SpritePiece(-16, -16, 4, 4, 0x40, false, false) },
        // Frame $A (10): Explosion 1 - 4x4 @ tile 0
        { new SpritePiece(-16, -16, 4, 4, 0, false, false) },
        // Frame $B (11): Explosion 2 - two 4x4 pieces
        { new SpritePiece(-24, -24, 4, 4, 0x10, false, false),
          new SpritePiece(-8, -24, 4, 4, 0x10, false, false) },
        // Frame $C (12): Explosion 3 - three 4x4 pieces
        { new SpritePiece(-40, -32, 4, 4, 0x20, false, false),
          new SpritePiece(-16, -40, 4, 4, 0x20, false, true),
          new SpritePiece(8, -32, 4, 4, 0x20, true, false) },
    };

    /**
     * Gets ring mapping frame for a given animation index and spin frame.
     *
     * Animation data from Ani_obj5B_obj60:
     * - Animations 0-9 are perspective sizes
     * - Each has frames: size, size+$A, size+$14, size+$A, size (5 frame cycle)
     *
     * @param animIndex Perspective size (0-9) or 10 for sparkle
     * @param spinFrame Spin frame index (0-4) for normal, or sparkle frame
     * @return Mapping frame index
     */
    public static int getRingMappingFrame(int animIndex, int spinFrame) {
        if (animIndex == 10) {
            // Sparkle animation - frames 30, 31, 32, then loop
            return 30 + Math.min(spinFrame, 2);
        }

        // Normal perspective animation
        // Spin offsets: 0, $A, $14, $A, 0 (indices 0, 10, 20, 10, 0)
        int baseFrame = animIndex;
        switch (spinFrame % 5) {
            case 0: return baseFrame;
            case 1: return baseFrame + 10;
            case 2: return baseFrame + 20;
            case 3: return baseFrame + 10;
            case 4: return baseFrame;
            default: return baseFrame;
        }
    }

    /**
     * Gets bomb mapping frame for a given animation index and frame.
     *
     * @param animIndex Perspective size (0-9) or 10 for explosion
     * @param frame Frame within explosion (0-2)
     * @return Mapping frame index
     */
    public static int getBombMappingFrame(int animIndex, int frame) {
        if (animIndex == 10) {
            // Explosion animation
            return 10 + Math.min(frame, 2);
        }
        // Normal perspective - single frame per size
        return Math.min(animIndex, 9);
    }

    /**
     * Gets the sprite pieces for a ring at the given mapping frame.
     */
    public static SpritePiece[] getRingPieces(int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= RING_MAPPINGS.length) {
            return RING_MAPPINGS[0]; // Fallback to smallest
        }
        return RING_MAPPINGS[mappingFrame];
    }

    /**
     * Gets the sprite pieces for a bomb at the given mapping frame.
     */
    public static SpritePiece[] getBombPieces(int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= BOMB_MAPPINGS.length) {
            return BOMB_MAPPINGS[0]; // Fallback to smallest
        }
        return BOMB_MAPPINGS[mappingFrame];
    }

    /**
     * Emerald mapping frames from obj59.asm.
     * 10 frames for perspective sizes (0-9).
     * Uses palette line 3 (same as player).
     */
    public static final SpritePiece[][] EMERALD_MAPPINGS = {
        // Frame 0: 1x1 @ tile 0 (smallest, far)
        { new SpritePiece(-4, -4, 1, 1, 0, false, false) },
        // Frame 1: 1x1 @ tile 1
        { new SpritePiece(-4, -4, 1, 1, 1, false, false) },
        // Frame 2: 1x1 @ tile 2
        { new SpritePiece(-4, -4, 1, 1, 2, false, false) },
        // Frame 3: 2x2 @ tile 3
        { new SpritePiece(-8, -8, 2, 2, 3, false, false) },
        // Frame 4: 2x2 @ tile 7
        { new SpritePiece(-8, -8, 2, 2, 7, false, false) },
        // Frame 5: 2x2 @ tile $B
        { new SpritePiece(-8, -8, 2, 2, 0x0B, false, false) },
        // Frame 6: 2x2 @ tile $F
        { new SpritePiece(-8, -8, 2, 2, 0x0F, false, false) },
        // Frame 7: 3x3 @ tile $13
        { new SpritePiece(-12, -12, 3, 3, 0x13, false, false) },
        // Frame 8: 3x3 @ tile $1C
        { new SpritePiece(-12, -12, 3, 3, 0x1C, false, false) },
        // Frame 9: 3x3 @ tile $25 (largest, closest)
        { new SpritePiece(-12, -12, 3, 3, 0x25, false, false) },
    };

    /**
     * Gets the sprite pieces for an emerald at the given mapping frame.
     */
    public static SpritePiece[] getEmeraldPieces(int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= EMERALD_MAPPINGS.length) {
            return EMERALD_MAPPINGS[0]; // Fallback to smallest
        }
        return EMERALD_MAPPINGS[mappingFrame];
    }

    // ========== Shadow Sprite Mappings (from obj63.asm) ==========

    /**
     * Shadow type enum for the three shadow art types.
     */
    public enum ShadowType {
        FLAT,     // Horizontal shadow (used at bottom/top of half-pipe)
        DIAGONAL, // 45-degree shadow (used at diagonal positions)
        SIDE      // Vertical shadow (used at sides of half-pipe)
    }

    /**
     * Shadow orientation info for player shadows.
     * Based on word_3417A from s2.asm - maps angle index (0-7) to shadow properties.
     */
    public static class ShadowInfo {
        public final ShadowType type;
        public final int xOffset;    // X offset from parent position
        public final int yOffset;    // Y offset from parent position
        public final int sizeIndex;  // Size index (0 = largest, 9 = smallest)
        public final boolean xFlip;  // Whether to horizontally flip the shadow

        public ShadowInfo(ShadowType type, int xOffset, int yOffset, int sizeIndex, boolean xFlip) {
            this.type = type;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.sizeIndex = sizeIndex;
            this.xFlip = xFlip;
        }
    }

    /**
     * Shadow orientation table from word_3417A in s2.asm.
     * Maps player angle index (0-7) to shadow type and position offsets.
     *
     * Angle ranges:
     *   0: 0x00-0x1F (angle 0-31)   - diagonal shadow, +20, +20, x-flip
     *   1: 0x20-0x3F (angle 32-63)  - flat shadow, 0, +24
     *   2: 0x40-0x5F (angle 64-95)  - diagonal shadow, -20, +20
     *   3: 0x60-0x7F (angle 96-127) - side shadow, -20, 0
     *   4: 0x80-0x9F (angle 128-159) - diagonal shadow, -20, -20
     *   5: 0xA0-0xBF (angle 160-191) - flat shadow, 0, -24
     *   6: 0xC0-0xDF (angle 192-223) - diagonal shadow, +20, -20, x-flip
     *   7: 0xE0-0xFF (angle 224-255) - side shadow, +20, 0, x-flip
     */
    private static final ShadowInfo[] PLAYER_SHADOW_INFO = {
        new ShadowInfo(ShadowType.DIAGONAL, 0x14, 0x14, 0, true),   // angle 0-31
        new ShadowInfo(ShadowType.FLAT, 0, 0x18, 0, false),         // angle 32-63 (center bottom)
        new ShadowInfo(ShadowType.DIAGONAL, -0x14, 0x14, 0, false), // angle 64-95
        new ShadowInfo(ShadowType.SIDE, -0x14, 0, 0, false),        // angle 96-127
        new ShadowInfo(ShadowType.DIAGONAL, -0x14, -0x14, 2, false),// angle 128-159 (size 2 = medium)
        new ShadowInfo(ShadowType.FLAT, 0, -0x18, 1, false),        // angle 160-191 (size 1)
        new ShadowInfo(ShadowType.DIAGONAL, 0x14, -0x14, 2, true),  // angle 192-223 (size 2 = medium)
        new ShadowInfo(ShadowType.SIDE, 0x14, 0, 0, true),          // angle 224-255
    };

    /**
     * Gets shadow info for a player based on their angle.
     *
     * @param angle Player's angle (0-255)
     * @return Shadow info with type, offsets, and flip state
     */
    public static ShadowInfo getPlayerShadowInfo(int angle) {
        // Convert angle to index: subtract 0x10 then divide by 32
        // This matches the original: d0 = ((angle - 0x10) & 0xFF) >> 5
        int angleIndex = ((angle - 0x10) & 0xFF) >> 5;
        if (angleIndex < 0 || angleIndex >= PLAYER_SHADOW_INFO.length) {
            angleIndex = 0;
        }
        return PLAYER_SHADOW_INFO[angleIndex];
    }

    /**
     * Flat (horizontal) shadow mapping frames from obj63.asm.
     * Indexed by size: 0 = largest (closest), 9 = smallest (furthest).
     * Each uses tiles from ArtNem_SpecialFlatShadow.
     */
    public static final SpritePiece[][] SHADOW_FLAT_MAPPINGS = {
        // Size 0 (largest): 4x2 @ tile $1E (frame 0 in obj63)
        { new SpritePiece(-16, -8, 4, 2, 0x1E, false, false) },
        // Size 1: 4x2 @ tile $16 (frame 3)
        { new SpritePiece(-16, -8, 4, 2, 0x16, false, false) },
        // Size 2: 4x2 @ tile $E (frame 6)
        { new SpritePiece(-16, -8, 4, 2, 0x0E, false, false) },
        // Size 3: 3x1 @ tile $B (frame 9)
        { new SpritePiece(-12, -4, 3, 1, 0x0B, false, false) },
        // Size 4: 3x1 @ tile $8 (frame 12)
        { new SpritePiece(-12, -4, 3, 1, 0x08, false, false) },
        // Size 5: 2x1 @ tile $6 (frame 15)
        { new SpritePiece(-8, -4, 2, 1, 0x06, false, false) },
        // Size 6: 2x1 @ tile $4 (frame 18)
        { new SpritePiece(-8, -4, 2, 1, 0x04, false, false) },
        // Size 7: 2x1 @ tile $2 (frame 21)
        { new SpritePiece(-8, -4, 2, 1, 0x02, false, false) },
        // Size 8: 1x1 @ tile $1 (frame 24)
        { new SpritePiece(-4, -4, 1, 1, 0x01, false, false) },
        // Size 9 (smallest): 1x1 @ tile $0 (frame 27)
        { new SpritePiece(-4, -4, 1, 1, 0x00, false, false) },
    };

    /**
     * Diagonal shadow mapping frames from obj63.asm.
     * Indexed by size: 0 = largest (closest), 9 = smallest (furthest).
     * Each uses tiles from ArtNem_SpecialDiagShadow.
     */
    public static final SpritePiece[][] SHADOW_DIAG_MAPPINGS = {
        // Size 0 (largest): 4x4 @ tile $2A (frame 1 in obj63)
        { new SpritePiece(-16, -16, 4, 4, 0x2A, false, false) },
        // Size 1: 3x3 @ tile $21 (frame 4) - note different offset
        { new SpritePiece(-8, -16, 3, 3, 0x21, false, false) },
        // Size 2: 3x3 @ tile $18 (frame 7)
        { new SpritePiece(-12, -12, 3, 3, 0x18, false, false) },
        // Size 3: 3x3 @ tile $F (frame 10)
        { new SpritePiece(-12, -12, 3, 3, 0x0F, false, false) },
        // Size 4: 2x2 @ tile $B (frame 13) - note different offset
        { new SpritePiece(-4, -12, 2, 2, 0x0B, false, false) },
        // Size 5: 2x2 @ tile $7 (frame 16)
        { new SpritePiece(-8, -8, 2, 2, 0x07, false, false) },
        // Size 6: 2x2 @ tile $3 (frame 19)
        { new SpritePiece(-8, -8, 2, 2, 0x03, false, false) },
        // Size 7: 1x1 @ tile $2 (frame 22)
        { new SpritePiece(-4, -4, 1, 1, 0x02, false, false) },
        // Size 8: 1x1 @ tile $1 (frame 25)
        { new SpritePiece(-4, -4, 1, 1, 0x01, false, false) },
        // Size 9 (smallest): 1x1 @ tile $0 (frame 28)
        { new SpritePiece(-4, -4, 1, 1, 0x00, false, false) },
    };

    /**
     * Side (vertical) shadow mapping frames from obj63.asm.
     * Indexed by size: 0 = largest (closest), 9 = smallest (furthest).
     * Each uses tiles from ArtNem_SpecialSideShadow.
     */
    public static final SpritePiece[][] SHADOW_SIDE_MAPPINGS = {
        // Size 0 (largest): 1x4 @ tile $15 (frame 2 in obj63)
        { new SpritePiece(-4, -16, 1, 4, 0x15, false, false) },
        // Size 1: 1x4 @ tile $11 (frame 5)
        { new SpritePiece(-4, -16, 1, 4, 0x11, false, false) },
        // Size 2: 1x3 @ tile $E (frame 8)
        { new SpritePiece(-4, -12, 1, 3, 0x0E, false, false) },
        // Size 3: 1x3 @ tile $B (frame 11)
        { new SpritePiece(-4, -12, 1, 3, 0x0B, false, false) },
        // Size 4: 1x3 @ tile $8 (frame 14)
        { new SpritePiece(-4, -12, 1, 3, 0x08, false, false) },
        // Size 5: 1x2 @ tile $6 (frame 17)
        { new SpritePiece(-4, -8, 1, 2, 0x06, false, false) },
        // Size 6: 1x2 @ tile $4 (frame 20)
        { new SpritePiece(-4, -8, 1, 2, 0x04, false, false) },
        // Size 7: 1x2 @ tile $2 (frame 23)
        { new SpritePiece(-4, -8, 1, 2, 0x02, false, false) },
        // Size 8: 1x1 @ tile $1 (frame 26)
        { new SpritePiece(-4, -4, 1, 1, 0x01, false, false) },
        // Size 9 (smallest): 1x1 @ tile $0 (frame 29)
        { new SpritePiece(-4, -4, 1, 1, 0x00, false, false) },
    };

    /**
     * Gets shadow sprite pieces for a given type and size index.
     *
     * @param type Shadow type (FLAT, DIAGONAL, or SIDE)
     * @param sizeIndex Size index (0 = largest/closest, 9 = smallest/furthest)
     * @return Array of sprite pieces for rendering the shadow
     */
    public static SpritePiece[] getShadowPieces(ShadowType type, int sizeIndex) {
        // Clamp size index to valid range
        if (sizeIndex < 0) sizeIndex = 0;
        if (sizeIndex > 9) sizeIndex = 9;

        switch (type) {
            case FLAT:
                return SHADOW_FLAT_MAPPINGS[sizeIndex];
            case DIAGONAL:
                return SHADOW_DIAG_MAPPINGS[sizeIndex];
            case SIDE:
                return SHADOW_SIDE_MAPPINGS[sizeIndex];
            default:
                return SHADOW_FLAT_MAPPINGS[sizeIndex];
        }
    }

    /**
     * Shadow info for objects including type and x-flip state.
     */
    public static class ObjectShadowInfo {
        public final ShadowType type;
        public final boolean xFlip;

        public ObjectShadowInfo(ShadowType type, boolean xFlip) {
            this.type = type;
            this.xFlip = xFlip;
        }
    }

    /**
     * Gets shadow type for an object based on its angle.
     * Objects use a simpler shadow system than players.
     *
     * From s2.asm loc_3529C: shadow type is determined by object's angle
     * and stored in objoff_2B (0 = flat, 1 = diagonal, 2 = side).
     *
     * Angle ranges (from disassembly):
     *   0x00-0x10: SIDE (2), x-flipped
     *   0x11-0x30: DIAGONAL (1), x-flipped
     *   0x31-0x50: FLAT (0)
     *   0x51-0x70: DIAGONAL (1)
     *   0x71-0x7F: SIDE (2)
     *   0x80+: Mirror of above (other half of track)
     *
     * @param angle Object's angle (0-255)
     * @return Shadow info with type and x-flip state
     */
    public static ObjectShadowInfo getObjectShadowInfo(int angle) {
        // Normalize angle to 0-127 range, tracking which half
        int normalizedAngle = angle & 0x7F;
        boolean upperHalf = (angle & 0x80) != 0;

        // Determine shadow type based on angle ranges from disassembly
        ShadowType type;
        boolean xFlip;

        if (normalizedAngle <= 0x10) {
            // 0x00-0x10: SIDE shadow, x-flipped
            type = ShadowType.SIDE;
            xFlip = true;
        } else if (normalizedAngle <= 0x30) {
            // 0x11-0x30: DIAGONAL shadow, x-flipped
            type = ShadowType.DIAGONAL;
            xFlip = true;
        } else if (normalizedAngle <= 0x50) {
            // 0x31-0x50: FLAT shadow
            type = ShadowType.FLAT;
            xFlip = false;
        } else if (normalizedAngle <= 0x70) {
            // 0x51-0x70: DIAGONAL shadow
            type = ShadowType.DIAGONAL;
            xFlip = false;
        } else {
            // 0x71-0x7F: SIDE shadow
            type = ShadowType.SIDE;
            xFlip = false;
        }

        // If in upper half of track, might need to flip differently
        // (objects on upper half have mirrored shadow orientation)
        if (upperHalf) {
            xFlip = !xFlip;
        }

        return new ObjectShadowInfo(type, xFlip);
    }

    /**
     * Gets shadow type for an object based on its angle.
     * Simplified version that just returns the type.
     *
     * @param angle Object's angle (0-255)
     * @return Shadow type for the object
     */
    public static ShadowType getObjectShadowType(int angle) {
        return getObjectShadowInfo(angle).type;
    }
}
