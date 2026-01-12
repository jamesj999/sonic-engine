package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Sprite mappings for special stage player sprites (Obj09 = Sonic).
 *
 * Each frame consists of multiple sprite pieces at different positions.
 * This is fundamentally different from the simple grid-based rendering
 * originally implemented.
 *
 * Data ported from docs/s2disasm/mappings/sprite/obj09.asm
 *
 * IMPORTANT: The original game uses Dynamic Pattern Loading (DPLC) which loads
 * different art sections to VRAM based on the pose. The tile indices in obj09.asm
 * are relative to the start of each pose section:
 * - Upright (frames 0-3): base 0x000
 * - Diagonal (frames 4-11): base 0x058
 * - Horizontal (frames 12-15): base 0x124
 * - Ball (frames 16-17): base 0x171
 *
 * Since we load all art at once, tile indices here are ABSOLUTE (include pose base).
 */
public final class Sonic2SpecialStageSpriteMappings {

    private Sonic2SpecialStageSpriteMappings() {}

    // DPLC base offsets for each pose category
    private static final int BASE_UPRIGHT = 0x000;    // Frames 0-3
    private static final int BASE_DIAGONAL = 0x058;   // Frames 4-11
    private static final int BASE_HORIZONTAL = 0x124; // Frames 12-15
    private static final int BASE_BALL = 0x171;       // Frames 16-17

    /**
     * A single piece of a sprite mapping.
     */
    public static class SpritePiece {
        public final int xOffset;      // Signed x offset from sprite center (in pixels)
        public final int yOffset;      // Signed y offset from sprite center (in pixels)
        public final int widthTiles;   // Width in 8x8 tiles
        public final int heightTiles;  // Height in 8x8 tiles
        public final int tileIndex;    // Base tile index for this piece (ABSOLUTE)
        public final boolean hFlip;    // Horizontal flip
        public final boolean vFlip;    // Vertical flip

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
     * A complete sprite mapping frame consisting of multiple pieces.
     */
    public static class SpriteFrame {
        public final SpritePiece[] pieces;

        public SpriteFrame(SpritePiece... pieces) {
            this.pieces = pieces;
        }
    }

    // Helper to create sprite pieces - now takes base offset for the pose category
    private static SpritePiece p(int x, int y, int w, int h, int tile, int hf, int vf, int base) {
        return new SpritePiece(x, y, w, h, base + tile, hf != 0, vf != 0);
    }

    /**
     * Sonic special stage sprite mappings.
     * 18 frames total, ported from obj09.asm.
     *
     * Frame usage (from s2disasm DPLC analysis):
     * - Frames 0-3: Upright/running poses (BASE_UPRIGHT = 0x000)
     * - Frames 4-11: Diagonal poses (BASE_DIAGONAL = 0x058)
     * - Frames 12-15: Horizontal poses (BASE_HORIZONTAL = 0x124)
     * - Frames 16-17: Ball/jumping poses (BASE_BALL = 0x171)
     */
    public static final SpriteFrame[] SONIC_FRAMES = {
        // ========== UPRIGHT FRAMES (0-3, base 0x000) ==========

        // Frame 0 (Map_obj09_0024): Main running frame - upright
        new SpriteFrame(
            p(-0x10, -0x1C, 4, 4, 0x00, 0, 0, BASE_UPRIGHT),
            p(-0x10,  0x04, 3, 3, 0x10, 0, 0, BASE_UPRIGHT),
            p( 0x08,  0x04, 1, 2, 0x19, 0, 0, BASE_UPRIGHT)
        ),
        // Frame 1 (Map_obj09_003E) - upright
        new SpriteFrame(
            p(-0x0E, -0x1C, 3, 3, 0x00, 0, 0, BASE_UPRIGHT),
            p(-0x10, -0x04, 4, 2, 0x09, 0, 0, BASE_UPRIGHT),
            p(-0x09,  0x0C, 2, 2, 0x11, 0, 0, BASE_UPRIGHT)
        ),
        // Frame 2 (Map_obj09_0058) - upright
        new SpriteFrame(
            p(-0x10, -0x1C, 4, 3, 0x00, 0, 0, BASE_UPRIGHT),
            p(-0x10, -0x04, 4, 2, 0x0C, 0, 0, BASE_UPRIGHT),
            p(-0x10,  0x0C, 3, 2, 0x14, 0, 0, BASE_UPRIGHT)
        ),
        // Frame 3 (Map_obj09_0072) - upright
        new SpriteFrame(
            p(-0x0A, -0x1C, 3, 3, 0x00, 1, 0, BASE_UPRIGHT),  // hflip = 1
            p(-0x10, -0x04, 4, 2, 0x09, 0, 0, BASE_UPRIGHT),
            p(-0x08,  0x0C, 3, 2, 0x11, 0, 0, BASE_UPRIGHT)
        ),

        // ========== DIAGONAL FRAMES (4-11, base 0x058) ==========

        // Frame 4 (Map_obj09_008C) - diagonal
        new SpriteFrame(
            p(-0x14, -0x1C, 3, 3, 0x00, 0, 0, BASE_DIAGONAL),
            p( 0x04, -0x1C, 1, 4, 0x09, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 2, 0x0D, 0, 0, BASE_DIAGONAL),
            p(-0x1C, -0x04, 4, 3, 0x0F, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 5 (Map_obj09_00AE) - diagonal
        new SpriteFrame(
            p(-0x0C, -0x1C, 3, 2, 0x00, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 2, 0x06, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x0C, 4, 2, 0x08, 0, 0, BASE_DIAGONAL),
            p(-0x1A,  0x04, 4, 2, 0x10, 0, 0, BASE_DIAGONAL),
            p(-0x12,  0x14, 1, 1, 0x18, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 6 (Map_obj09_00D8) - diagonal
        new SpriteFrame(
            p(-0x05, -0x1C, 2, 1, 0x00, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x14, 4, 3, 0x02, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 3, 0x0E, 0, 0, BASE_DIAGONAL),
            p(-0x19,  0x04, 2, 3, 0x11, 0, 0, BASE_DIAGONAL),
            p(-0x09,  0x04, 2, 2, 0x17, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 7 (Map_obj09_0102) - diagonal
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 1, 0x00, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x14, 4, 4, 0x02, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 3, 0x12, 0, 0, BASE_DIAGONAL),
            p(-0x1C,  0x04, 1, 1, 0x15, 0, 0, BASE_DIAGONAL),
            p(-0x16,  0x0C, 2, 2, 0x16, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 8 (Map_obj09_012C) - diagonal
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 2, 0x00, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 4, 0x04, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x0C, 4, 3, 0x08, 0, 0, BASE_DIAGONAL),
            p(-0x14,  0x0C, 2, 2, 0x14, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 9 (Map_obj09_014E) - diagonal
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 2, 0x00, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x14, 1, 3, 0x04, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x0C, 4, 2, 0x07, 0, 0, BASE_DIAGONAL),
            p(-0x18,  0x04, 4, 2, 0x0F, 0, 0, BASE_DIAGONAL),
            p(-0x0C, -0x14, 1, 1, 0x17, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 10 (Map_obj09_0178) - diagonal
        new SpriteFrame(
            p(-0x05, -0x1C, 3, 2, 0x00, 0, 0, BASE_DIAGONAL),
            p( 0x0E, -0x0C, 1, 2, 0x06, 0, 0, BASE_DIAGONAL),
            p(-0x12, -0x0C, 4, 2, 0x08, 0, 0, BASE_DIAGONAL),
            p(-0x19,  0x04, 4, 2, 0x10, 0, 0, BASE_DIAGONAL),
            p(-0x11,  0x14, 1, 1, 0x18, 0, 0, BASE_DIAGONAL)
        ),
        // Frame 11 (Map_obj09_01A2) - diagonal
        new SpriteFrame(
            p( 0x02, -0x1C, 1, 1, 0x00, 0, 0, BASE_DIAGONAL),
            p(-0x0C, -0x14, 4, 2, 0x01, 0, 0, BASE_DIAGONAL),
            p( 0x0C, -0x04, 1, 1, 0x09, 0, 0, BASE_DIAGONAL),
            p(-0x1C,  0x04, 1, 2, 0x0A, 0, 0, BASE_DIAGONAL),
            p(-0x14, -0x04, 2, 4, 0x0C, 0, 0, BASE_DIAGONAL),
            p(-0x04, -0x04, 2, 3, 0x14, 0, 0, BASE_DIAGONAL)
        ),

        // ========== HORIZONTAL FRAMES (12-15, base 0x124) ==========

        // Frame 12 (Map_obj09_01D4) - horizontal
        new SpriteFrame(
            p(-0x18, -0x10, 2, 3, 0x00, 0, 0, BASE_HORIZONTAL),
            p(-0x10,  0x08, 1, 1, 0x06, 0, 0, BASE_HORIZONTAL),
            p(-0x08, -0x10, 4, 4, 0x07, 0, 0, BASE_HORIZONTAL)
        ),
        // Frame 13 (Map_obj09_01EE) - horizontal
        new SpriteFrame(
            p(-0x18, -0x10, 2, 3, 0x00, 0, 0, BASE_HORIZONTAL),
            p(-0x08, -0x0F, 1, 4, 0x06, 0, 0, BASE_HORIZONTAL),
            p( 0x00, -0x10, 3, 4, 0x0A, 0, 0, BASE_HORIZONTAL)
        ),
        // Frame 14 (Map_obj09_0208) - horizontal
        new SpriteFrame(
            p(-0x18, -0x0F, 1, 3, 0x00, 0, 0, BASE_HORIZONTAL),
            p(-0x10, -0x10, 1, 3, 0x03, 0, 0, BASE_HORIZONTAL),
            p(-0x08, -0x10, 4, 4, 0x06, 0, 0, BASE_HORIZONTAL)
        ),
        // Frame 15 (Map_obj09_0222) - horizontal
        new SpriteFrame(
            p(-0x18, -0x08, 2, 3, 0x00, 0, 0, BASE_HORIZONTAL),
            p(-0x08, -0x11, 1, 4, 0x06, 0, 0, BASE_HORIZONTAL),
            p( 0x00, -0x10, 3, 4, 0x0A, 0, 0, BASE_HORIZONTAL)
        ),

        // ========== BALL FRAMES (16-17, base 0x171) ==========

        // Frame 16 (Map_obj09_023C) - ball
        new SpriteFrame(
            p(-0x10, -0x14, 2, 4, 0x00, 0, 0, BASE_BALL),
            p(-0x10,  0x0C, 2, 1, 0x08, 0, 0, BASE_BALL),
            p( 0x00, -0x14, 2, 4, 0x00, 1, 0, BASE_BALL),  // hflip = 1
            p( 0x00,  0x0C, 2, 1, 0x08, 1, 0, BASE_BALL)   // hflip = 1
        ),
        // Frame 17 (Map_obj09_025E) - ball
        new SpriteFrame(
            p(-0x10, -0x14, 2, 4, 0x00, 0, 0, BASE_BALL),
            p(-0x10,  0x0C, 2, 1, 0x08, 0, 0, BASE_BALL),
            p( 0x00, -0x14, 2, 4, 0x00, 1, 0, BASE_BALL),  // hflip = 1
            p( 0x00,  0x0C, 2, 1, 0x08, 1, 0, BASE_BALL)   // hflip = 1
        )
    };

    /**
     * Get the sprite frame for a given mapping frame index.
     */
    public static SpriteFrame getSonicFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= SONIC_FRAMES.length) {
            return SONIC_FRAMES[0]; // Default to first frame
        }
        return SONIC_FRAMES[frameIndex];
    }
}
