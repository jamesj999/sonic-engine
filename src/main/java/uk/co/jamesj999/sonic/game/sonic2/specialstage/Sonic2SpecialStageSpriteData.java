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
}
