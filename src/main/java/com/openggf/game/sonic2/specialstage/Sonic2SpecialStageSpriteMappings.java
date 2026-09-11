package com.openggf.game.sonic2.specialstage;

/**
 * Sprite mappings for special stage player sprites (Obj09 = Sonic).
 *
 * Each frame consists of multiple sprite pieces at different positions.
 * Data ported from docs/s2disasm/mappings/sprite/obj09.asm
 *
 * IMPORTANT: The original game uses Dynamic Pattern Loading (DPLC) which loads
 * specific tiles from the source art into VRAM for each frame. The sprite mappings
 * in obj09.asm reference DESTINATION tile indices (after DPLC loads them).
 *
 * Since we load all art contiguously and don't use DPLC, the tile indices here
 * are converted to SOURCE tile indices using the DPLC data from s2.asm.
 *
 * DPLC format: dplcEntry count, source_offset
 * - Tiles are loaded sequentially into VRAM starting at destination 0
 * - Each dplcEntry loads 'count' tiles from 'source_offset' in the source art
 */
public final class Sonic2SpecialStageSpriteMappings {

    private Sonic2SpecialStageSpriteMappings() {}

    /**
     * A single piece of a sprite mapping.
     */
    public static class SpritePiece {
        public final int xOffset;      // Signed x offset from sprite center (in pixels)
        public final int yOffset;      // Signed y offset from sprite center (in pixels)
        public final int widthTiles;   // Width in 8x8 tiles
        public final int heightTiles;  // Height in 8x8 tiles
        public final int tileIndex;    // Source tile index in the art data
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

    // Helper to create sprite pieces
    private static SpritePiece p(int x, int y, int w, int h, int tile, int hf, int vf) {
        return new SpritePiece(x, y, w, h, tile, hf != 0, vf != 0);
    }

    // Art section offsets - each animation type has its own section in the art data
    // From s2.asm: SSPlayer_DPLCPtrs
    // The DPLC source offsets are RELATIVE to each section's start
    private static final int ART_OFFSET_DIAGONAL = 0x58;    // tiles_to_bytes($058) - 0xCC tiles
    private static final int ART_OFFSET_HORIZONTAL = 0x124; // tiles_to_bytes($124) - 0x4D tiles
    private static final int ART_OFFSET_BALL = 0x171;       // tiles_to_bytes($171) - 0x12 tiles
    /**
     * Sonic special stage sprite mappings.
     * 18 frames total, ported from obj09.asm with tile indices converted using DPLC data.
     *
     * Each frame's tile indices are the ABSOLUTE offsets in the art data.
     * Conversion: (DPLC source offset) + (art section offset) = absolute tile index.
     *
     * Art section offsets (from s2.asm SSPlayer_DPLCPtrs):
     * - Upright (frames 0-3): offset 0x00
     * - Diagonal (frames 4-11): offset 0x58
     * - Horizontal (frames 12-15): offset 0x124
     * - Ball (frames 16-17): offset 0x171
     *
     * DPLC data from s2.asm (Obj09_MapRUnc_345FA):
     *
     * Frame 0: dplcEntry $10, 0 / dplcEntry 9, $10 / dplcEntry 2, $19
     *   dest 0-15 -> source 0x00-0x0F, dest 16-24 -> source 0x10-0x18, dest 25-26 -> source 0x19-0x1A
     *
     * Frame 1: dplcEntry 9, $1B / dplcEntry 8, $24 / dplcEntry 4, $2C
     *   dest 0-8 -> source 0x1B-0x23, dest 9-16 -> source 0x24-0x2B, dest 17-20 -> source 0x2C-0x2F
     *
     * Frame 2: dplcEntry $C, $30 / dplcEntry 8, $3C / dplcEntry 6, $44
     *   dest 0-11 -> source 0x30-0x3B, dest 12-19 -> source 0x3C-0x43, dest 20-25 -> source 0x44-0x49
     *
     * Frame 3: dplcEntry 9, $1B / dplcEntry 8, $4A / dplcEntry 6, $52
     *   dest 0-8 -> source 0x1B-0x23, dest 9-16 -> source 0x4A-0x51, dest 17-22 -> source 0x52-0x57
     *
     * Frame 4: dplcEntry 9, 0 / dplcEntry 4, 9 / dplcEntry 2, $D / dplcEntry $C, $F
     *   dest 0-8 -> source 0x00-0x08, dest 9-12 -> source 0x09-0x0C, dest 13-14 -> source 0x0D-0x0E, dest 15-26 -> source 0x0F-0x1A
     *
     * Frame 5: dplcEntry 6, $1B / dplcEntry 2, $21 / dplcEntry 8, $23 / dplcEntry 8, $2B / dplcEntry 1, $33
     *   dest 0-5 -> source 0x1B-0x20, dest 6-7 -> source 0x21-0x22, dest 8-15 -> source 0x23-0x2A, dest 16-23 -> source 0x2B-0x32, dest 24 -> source 0x33
     *
     * Frame 6: dplcEntry 2, $34 / dplcEntry $C, $36 / dplcEntry 3, $42 / dplcEntry 6, $45 / dplcEntry 4, $4B
     *   dest 0-1 -> source 0x34-0x35, dest 2-13 -> source 0x36-0x41, dest 14-16 -> source 0x42-0x44, dest 17-22 -> source 0x45-0x4A, dest 23-26 -> source 0x4B-0x4E
     *
     * Frame 7: dplcEntry 2, $4F / dplcEntry $10, $51 / dplcEntry 3, $61 / dplcEntry 1, $64 / dplcEntry 4, $65
     *   dest 0-1 -> source 0x4F-0x50, dest 2-17 -> source 0x51-0x60, dest 18-20 -> source 0x61-0x63, dest 21 -> source 0x64, dest 22-25 -> source 0x65-0x68
     *
     * Frame 8: dplcEntry 4, $69 / dplcEntry 4, $6D / dplcEntry $C, $71 / dplcEntry 4, $7D
     *   dest 0-3 -> source 0x69-0x6C, dest 4-7 -> source 0x6D-0x70, dest 8-19 -> source 0x71-0x7C, dest 20-23 -> source 0x7D-0x80
     *
     * Frame 9: dplcEntry 4, $81 / dplcEntry 3, $85 / dplcEntry 8, $88 / dplcEntry 8, $90 / dplcEntry 1, $98
     *   dest 0-3 -> source 0x81-0x84, dest 4-6 -> source 0x85-0x87, dest 7-14 -> source 0x88-0x8F, dest 15-22 -> source 0x90-0x97, dest 23 -> source 0x98
     *
     * Frame 10: dplcEntry 6, $99 / dplcEntry 2, $9F / dplcEntry 8, $A1 / dplcEntry 8, $A9 / dplcEntry 1, $B1
     *   dest 0-5 -> source 0x99-0x9E, dest 6-7 -> source 0x9F-0xA0, dest 8-15 -> source 0xA1-0xA8, dest 16-23 -> source 0xA9-0xB0, dest 24 -> source 0xB1
     *
     * Frame 11: dplcEntry 1, $B2 / dplcEntry 8, $B3 / dplcEntry 1, $BB / dplcEntry 2, $BC / dplcEntry 8, $BE / dplcEntry 6, $C6
     *   dest 0 -> source 0xB2, dest 1-8 -> source 0xB3-0xBA, dest 9 -> source 0xBB, dest 10-11 -> source 0xBC-0xBD, dest 12-19 -> source 0xBE-0xC5, dest 20-25 -> source 0xC6-0xCB
     *
     * Frame 12: dplcEntry 6, 0 / dplcEntry 1, 6 / dplcEntry $10, 7
     *   dest 0-5 -> source 0x00-0x05, dest 6 -> source 0x06, dest 7-22 -> source 0x07-0x16
     *
     * Frame 13: dplcEntry 6, $17 / dplcEntry 4, $1D / dplcEntry $C, $21
     *   dest 0-5 -> source 0x17-0x1C, dest 6-9 -> source 0x1D-0x20, dest 10-21 -> source 0x21-0x2C
     *
     * Frame 14: dplcEntry 3, $2D / dplcEntry 3, $30 / dplcEntry $10, $33
     *   dest 0-2 -> source 0x2D-0x2F, dest 3-5 -> source 0x30-0x32, dest 6-21 -> source 0x33-0x42
     *
     * Frame 15: dplcEntry 6, $43 / dplcEntry 4, $49 / dplcEntry $C, $21
     *   dest 0-5 -> source 0x43-0x48, dest 6-9 -> source 0x49-0x4C, dest 10-21 -> source 0x21-0x2C
     *
     * Frame 16: dplcEntry 8, 0 / dplcEntry 2, 8
     *   dest 0-7 -> source 0x00-0x07, dest 8-9 -> source 0x08-0x09
     *
     * Frame 17: dplcEntry 8, $A / dplcEntry 2, 8
     *   dest 0-7 -> source 0x0A-0x11, dest 8-9 -> source 0x08-0x09
     */
    public static final SpriteFrame[] SONIC_FRAMES = {
        // ========== UPRIGHT FRAMES (0-3) ==========

        // Frame 0 (Map_obj09_0024): DPLC: dest 0->src 0, dest 16->src 0x10, dest 25->src 0x19
        // Mapping: tile 0 (4x4), tile $10 (3x3), tile $19 (1x2)
        // Source:  tile 0x00,     tile 0x10,      tile 0x19
        new SpriteFrame(
            p(-0x10, -0x1C, 4, 4, 0x00, 0, 0),
            p(-0x10,  0x04, 3, 3, 0x10, 0, 0),
            p( 0x08,  0x04, 1, 2, 0x19, 0, 0)
        ),

        // Frame 1 (Map_obj09_003E): DPLC: dest 0->src 0x1B, dest 9->src 0x24, dest 17->src 0x2C
        // Mapping: tile 0 (3x3), tile 9 (4x2), tile $11 (2x2)
        // Source:  tile 0x1B,    tile 0x24,    tile 0x2C
        new SpriteFrame(
            p(-0x0E, -0x1C, 3, 3, 0x1B, 0, 0),
            p(-0x10, -0x04, 4, 2, 0x24, 0, 0),
            p(-0x09,  0x0C, 2, 2, 0x2C, 0, 0)
        ),

        // Frame 2 (Map_obj09_0058): DPLC: dest 0->src 0x30, dest 12->src 0x3C, dest 20->src 0x44
        // Mapping: tile 0 (4x3), tile $C (4x2), tile $14 (3x2)
        // Source:  tile 0x30,    tile 0x3C,     tile 0x44
        new SpriteFrame(
            p(-0x10, -0x1C, 4, 3, 0x30, 0, 0),
            p(-0x10, -0x04, 4, 2, 0x3C, 0, 0),
            p(-0x10,  0x0C, 3, 2, 0x44, 0, 0)
        ),

        // Frame 3 (Map_obj09_0072): DPLC: dest 0->src 0x1B, dest 9->src 0x4A, dest 17->src 0x52
        // Mapping: tile 0 (3x3) hflip, tile 9 (4x2), tile $11 (3x2)
        // Source:  tile 0x1B,          tile 0x4A,    tile 0x52
        new SpriteFrame(
            p(-0x0A, -0x1C, 3, 3, 0x1B, 1, 0),
            p(-0x10, -0x04, 4, 2, 0x4A, 0, 0),
            p(-0x08,  0x0C, 3, 2, 0x52, 0, 0)
        ),

        // ========== DIAGONAL FRAMES (4-11) ==========
        // Note: All tile indices have ART_OFFSET_DIAGONAL (0x58) added

        // Frame 4 (Map_obj09_008C): DPLC: dest 0->src 0, dest 9->src 9, dest 13->src 0xD, dest 15->src 0xF
        // Mapping: tile 0 (3x3), tile 9 (1x4), tile $D (1x2), tile $F (4x3)
        // Source:  tile 0x00+0x58=0x58, tile 0x09+0x58=0x61, tile 0x0D+0x58=0x65, tile 0x0F+0x58=0x67
        new SpriteFrame(
            p(-0x14, -0x1C, 3, 3, ART_OFFSET_DIAGONAL + 0x00, 0, 0),
            p( 0x04, -0x1C, 1, 4, ART_OFFSET_DIAGONAL + 0x09, 0, 0),
            p( 0x0C, -0x14, 1, 2, ART_OFFSET_DIAGONAL + 0x0D, 0, 0),
            p(-0x1C, -0x04, 4, 3, ART_OFFSET_DIAGONAL + 0x0F, 0, 0)
        ),

        // Frame 5 (Map_obj09_00AE): DPLC: dest 0->src 0x1B, dest 6->src 0x21, dest 8->src 0x23, dest 16->src 0x2B, dest 24->src 0x33
        // Mapping: tile 0 (3x2), tile 6 (1x2), tile 8 (4x2), tile $10 (4x2), tile $18 (1x1)
        // Source:  tile 0x1B+0x58, tile 0x21+0x58, tile 0x23+0x58, tile 0x2B+0x58, tile 0x33+0x58
        new SpriteFrame(
            p(-0x0C, -0x1C, 3, 2, ART_OFFSET_DIAGONAL + 0x1B, 0, 0),
            p( 0x0C, -0x14, 1, 2, ART_OFFSET_DIAGONAL + 0x21, 0, 0),
            p(-0x14, -0x0C, 4, 2, ART_OFFSET_DIAGONAL + 0x23, 0, 0),
            p(-0x1A,  0x04, 4, 2, ART_OFFSET_DIAGONAL + 0x2B, 0, 0),
            p(-0x12,  0x14, 1, 1, ART_OFFSET_DIAGONAL + 0x33, 0, 0)
        ),

        // Frame 6 (Map_obj09_00D8): DPLC: dest 0->src 0x34, dest 2->src 0x36, dest 14->src 0x42, dest 17->src 0x45, dest 23->src 0x4B
        // Mapping: tile 0 (2x1), tile 2 (4x3), tile $E (1x3), tile $11 (2x3), tile $17 (2x2)
        // Source:  tile 0x34+0x58, tile 0x36+0x58, tile 0x42+0x58, tile 0x45+0x58, tile 0x4B+0x58
        new SpriteFrame(
            p(-0x05, -0x1C, 2, 1, ART_OFFSET_DIAGONAL + 0x34, 0, 0),
            p(-0x14, -0x14, 4, 3, ART_OFFSET_DIAGONAL + 0x36, 0, 0),
            p( 0x0C, -0x14, 1, 3, ART_OFFSET_DIAGONAL + 0x42, 0, 0),
            p(-0x19,  0x04, 2, 3, ART_OFFSET_DIAGONAL + 0x45, 0, 0),
            p(-0x09,  0x04, 2, 2, ART_OFFSET_DIAGONAL + 0x4B, 0, 0)
        ),

        // Frame 7 (Map_obj09_0102): DPLC: dest 0->src 0x4F, dest 2->src 0x51, dest 18->src 0x61, dest 21->src 0x64, dest 22->src 0x65
        // Mapping: tile 0 (2x1), tile 2 (4x4), tile $12 (1x3), tile $15 (1x1), tile $16 (2x2)
        // Source:  tile 0x4F+0x58, tile 0x51+0x58, tile 0x61+0x58, tile 0x64+0x58, tile 0x65+0x58
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 1, ART_OFFSET_DIAGONAL + 0x4F, 0, 0),
            p(-0x14, -0x14, 4, 4, ART_OFFSET_DIAGONAL + 0x51, 0, 0),
            p( 0x0C, -0x14, 1, 3, ART_OFFSET_DIAGONAL + 0x61, 0, 0),
            p(-0x1C,  0x04, 1, 1, ART_OFFSET_DIAGONAL + 0x64, 0, 0),
            p(-0x16,  0x0C, 2, 2, ART_OFFSET_DIAGONAL + 0x65, 0, 0)
        ),

        // Frame 8 (Map_obj09_012C): DPLC: dest 0->src 0x69, dest 4->src 0x6D, dest 8->src 0x71, dest 20->src 0x7D
        // Mapping: tile 0 (2x2), tile 4 (1x4), tile 8 (4x3), tile $14 (2x2)
        // Source:  tile 0x69+0x58, tile 0x6D+0x58, tile 0x71+0x58, tile 0x7D+0x58
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 2, ART_OFFSET_DIAGONAL + 0x69, 0, 0),
            p( 0x0C, -0x14, 1, 4, ART_OFFSET_DIAGONAL + 0x6D, 0, 0),
            p(-0x14, -0x0C, 4, 3, ART_OFFSET_DIAGONAL + 0x71, 0, 0),
            p(-0x14,  0x0C, 2, 2, ART_OFFSET_DIAGONAL + 0x7D, 0, 0)
        ),

        // Frame 9 (Map_obj09_014E): DPLC: dest 0->src 0x81, dest 4->src 0x85, dest 7->src 0x88, dest 15->src 0x90, dest 23->src 0x98
        // Mapping: tile 0 (2x2), tile 4 (1x3), tile 7 (4x2), tile $F (4x2), tile $17 (1x1)
        // Source:  tile 0x81+0x58, tile 0x85+0x58, tile 0x88+0x58, tile 0x90+0x58, tile 0x98+0x58
        new SpriteFrame(
            p(-0x04, -0x1C, 2, 2, ART_OFFSET_DIAGONAL + 0x81, 0, 0),
            p( 0x0C, -0x14, 1, 3, ART_OFFSET_DIAGONAL + 0x85, 0, 0),
            p(-0x14, -0x0C, 4, 2, ART_OFFSET_DIAGONAL + 0x88, 0, 0),
            p(-0x18,  0x04, 4, 2, ART_OFFSET_DIAGONAL + 0x90, 0, 0),
            p(-0x0C, -0x14, 1, 1, ART_OFFSET_DIAGONAL + 0x98, 0, 0)
        ),

        // Frame 10 (Map_obj09_0178): DPLC: dest 0->src 0x99, dest 6->src 0x9F, dest 8->src 0xA1, dest 16->src 0xA9, dest 24->src 0xB1
        // Mapping: tile 0 (3x2), tile 6 (1x2), tile 8 (4x2), tile $10 (4x2), tile $18 (1x1)
        // Source:  tile 0x99+0x58, tile 0x9F+0x58, tile 0xA1+0x58, tile 0xA9+0x58, tile 0xB1+0x58
        new SpriteFrame(
            p(-0x05, -0x1C, 3, 2, ART_OFFSET_DIAGONAL + 0x99, 0, 0),
            p( 0x0E, -0x0C, 1, 2, ART_OFFSET_DIAGONAL + 0x9F, 0, 0),
            p(-0x12, -0x0C, 4, 2, ART_OFFSET_DIAGONAL + 0xA1, 0, 0),
            p(-0x19,  0x04, 4, 2, ART_OFFSET_DIAGONAL + 0xA9, 0, 0),
            p(-0x11,  0x14, 1, 1, ART_OFFSET_DIAGONAL + 0xB1, 0, 0)
        ),

        // Frame 11 (Map_obj09_01A2): DPLC: dest 0->src 0xB2, dest 1->src 0xB3, dest 9->src 0xBB, dest 10->src 0xBC, dest 12->src 0xBE, dest 20->src 0xC6
        // Mapping: tile 0 (1x1), tile 1 (4x2), tile 9 (1x1), tile $A (1x2), tile $C (2x4), tile $14 (2x3)
        // Source:  tile 0xB2+0x58, tile 0xB3+0x58, tile 0xBB+0x58, tile 0xBC+0x58, tile 0xBE+0x58, tile 0xC6+0x58
        new SpriteFrame(
            p( 0x02, -0x1C, 1, 1, ART_OFFSET_DIAGONAL + 0xB2, 0, 0),
            p(-0x0C, -0x14, 4, 2, ART_OFFSET_DIAGONAL + 0xB3, 0, 0),
            p( 0x0C, -0x04, 1, 1, ART_OFFSET_DIAGONAL + 0xBB, 0, 0),
            p(-0x1C,  0x04, 1, 2, ART_OFFSET_DIAGONAL + 0xBC, 0, 0),
            p(-0x14, -0x04, 2, 4, ART_OFFSET_DIAGONAL + 0xBE, 0, 0),
            p(-0x04, -0x04, 2, 3, ART_OFFSET_DIAGONAL + 0xC6, 0, 0)
        ),

        // ========== HORIZONTAL FRAMES (12-15) ==========
        // Note: All tile indices have ART_OFFSET_HORIZONTAL (0x124) added

        // Frame 12 (Map_obj09_01D4): DPLC: dest 0->src 0, dest 6->src 6, dest 7->src 7
        // Mapping: tile 0 (2x3), tile 6 (1x1), tile 7 (4x4)
        // Source:  tile 0x00+0x124, tile 0x06+0x124, tile 0x07+0x124
        new SpriteFrame(
            p(-0x18, -0x10, 2, 3, ART_OFFSET_HORIZONTAL + 0x00, 0, 0),
            p(-0x10,  0x08, 1, 1, ART_OFFSET_HORIZONTAL + 0x06, 0, 0),
            p(-0x08, -0x10, 4, 4, ART_OFFSET_HORIZONTAL + 0x07, 0, 0)
        ),

        // Frame 13 (Map_obj09_01EE): DPLC: dest 0->src 0x17, dest 6->src 0x1D, dest 10->src 0x21
        // Mapping: tile 0 (2x3), tile 6 (1x4), tile $A (3x4)
        // Source:  tile 0x17+0x124, tile 0x1D+0x124, tile 0x21+0x124
        new SpriteFrame(
            p(-0x18, -0x10, 2, 3, ART_OFFSET_HORIZONTAL + 0x17, 0, 0),
            p(-0x08, -0x0F, 1, 4, ART_OFFSET_HORIZONTAL + 0x1D, 0, 0),
            p( 0x00, -0x10, 3, 4, ART_OFFSET_HORIZONTAL + 0x21, 0, 0)
        ),

        // Frame 14 (Map_obj09_0208): DPLC: dest 0->src 0x2D, dest 3->src 0x30, dest 6->src 0x33
        // Mapping: tile 0 (1x3), tile 3 (1x3), tile 6 (4x4)
        // Source:  tile 0x2D+0x124, tile 0x30+0x124, tile 0x33+0x124
        new SpriteFrame(
            p(-0x18, -0x0F, 1, 3, ART_OFFSET_HORIZONTAL + 0x2D, 0, 0),
            p(-0x10, -0x10, 1, 3, ART_OFFSET_HORIZONTAL + 0x30, 0, 0),
            p(-0x08, -0x10, 4, 4, ART_OFFSET_HORIZONTAL + 0x33, 0, 0)
        ),

        // Frame 15 (Map_obj09_0222): DPLC: dest 0->src 0x43, dest 6->src 0x49, dest 10->src 0x21
        // Mapping: tile 0 (2x3), tile 6 (1x4), tile $A (3x4)
        // Source:  tile 0x43+0x124, tile 0x49+0x124, tile 0x21+0x124
        new SpriteFrame(
            p(-0x18, -0x08, 2, 3, ART_OFFSET_HORIZONTAL + 0x43, 0, 0),
            p(-0x08, -0x11, 1, 4, ART_OFFSET_HORIZONTAL + 0x49, 0, 0),
            p( 0x00, -0x10, 3, 4, ART_OFFSET_HORIZONTAL + 0x21, 0, 0)
        ),

        // ========== BALL FRAMES (16-17) ==========
        // Note: All tile indices have ART_OFFSET_BALL (0x171) added

        // Frame 16 (Map_obj09_023C): DPLC: dest 0->src 0, dest 8->src 8
        // Mapping: tile 0 (2x4), tile 8 (2x1), tile 0 hflip (2x4), tile 8 hflip (2x1)
        // Source:  tile 0x00+0x171, tile 0x08+0x171, tile 0x00+0x171, tile 0x08+0x171
        new SpriteFrame(
            p(-0x10, -0x14, 2, 4, ART_OFFSET_BALL + 0x00, 0, 0),
            p(-0x10,  0x0C, 2, 1, ART_OFFSET_BALL + 0x08, 0, 0),
            p( 0x00, -0x14, 2, 4, ART_OFFSET_BALL + 0x00, 1, 0),
            p( 0x00,  0x0C, 2, 1, ART_OFFSET_BALL + 0x08, 1, 0)
        ),

        // Frame 17 (Map_obj09_025E): DPLC: dest 0->src 0xA, dest 8->src 8
        // Mapping: tile 0 (2x4), tile 8 (2x1), tile 0 hflip (2x4), tile 8 hflip (2x1)
        // Source:  tile 0x0A+0x171, tile 0x08+0x171, tile 0x0A+0x171, tile 0x08+0x171
        new SpriteFrame(
            p(-0x10, -0x14, 2, 4, ART_OFFSET_BALL + 0x0A, 0, 0),
            p(-0x10,  0x0C, 2, 1, ART_OFFSET_BALL + 0x08, 0, 0),
            p( 0x00, -0x14, 2, 4, ART_OFFSET_BALL + 0x0A, 1, 0),
            p( 0x00,  0x0C, 2, 1, ART_OFFSET_BALL + 0x08, 1, 0)
        )
    };

    public static final int TAILS_UPRIGHT_ART_BASE = 0x183;
    public static final int TAILS_DIAGONAL_ART_BASE = 0x1C0;
    public static final int TAILS_HORIZONTAL_ART_BASE = 0x264;
    public static final int TAILS_BALL_ART_BASE = 0x29E;
    public static final int TAILS_TAILS_UPRIGHT_ART_BASE = 0x2AE;
    public static final int TAILS_TAILS_DIAGONAL_ART_BASE = 0x2E3;
    public static final int TAILS_TAILS_HORIZONTAL_ART_BASE = 0x31E;

    private static final int[] TAILS_ART_BASES = {
        TAILS_UPRIGHT_ART_BASE, TAILS_UPRIGHT_ART_BASE, TAILS_UPRIGHT_ART_BASE, TAILS_UPRIGHT_ART_BASE,
        TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE,
        TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE, TAILS_DIAGONAL_ART_BASE,
        TAILS_HORIZONTAL_ART_BASE, TAILS_HORIZONTAL_ART_BASE, TAILS_HORIZONTAL_ART_BASE, TAILS_HORIZONTAL_ART_BASE,
        TAILS_BALL_ART_BASE, TAILS_BALL_ART_BASE
    };

    // Obj09_MapRUnc_345FA records mappingFrame + $12 (Obj10).
    private static final int[][][] TAILS_DPLC = {
        {{9,0},{6,9},{1,0xF}}, {{4,0x10},{6,0x14},{4,0x1A},{4,0x1E}},
        {{4,0x22},{6,0x26},{4,0x2C},{4,0x30}}, {{4,0x10},{6,0x14},{4,0x34},{4,0x38},{1,0x3C}},
        {{4,0},{8,4},{8,0xC}}, {{2,0x14},{8,0x16},{9,0x1E},{2,0x27}},
        {{1,0x29},{3,0x2A},{8,0x2D},{1,0x35},{6,0x36}}, {{1,0x3C},{16,0x3D},{1,0x4D},{2,0x4E}},
        {{4,0x50},{4,0x54},{8,0x58},{6,0x60}}, {{1,0x66},{8,0x67},{1,0x6F},{8,0x70},{2,0x78}},
        {{1,0x7A},{12,0x7B},{1,0x87},{4,0x88},{2,0x8C}}, {{1,0x8E},{12,0x8F},{1,0x9B},{8,0x9C}},
        {{9,0},{8,9}}, {{4,0x11},{1,0x15},{12,0x16}}, {{2,0x22},{16,0x24}},
        {{3,0x34},{3,0x37},{12,0x16}}, {{8,0}}, {{8,8}}
    };

    private static final int[] TAILS_TAILS_SOURCES = {
        0,6,0xF,0x15,0x1B,0x23,0x2C, 0,9,0xF,0x15,0x1D,0x29,0x32,
        0,6,0xF,0x15,0x1B,0x23,0x2C
    };
    private static final int[] TAILS_TAILS_RUN_LENGTHS = {
        6,9,6,6,8,9,9, 9,6,6,8,12,9,9, 6,9,6,6,8,9,9
    };

    public static int getTailsArtBase(int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= TAILS_ART_BASES.length) {
            throw new IllegalArgumentException("Invalid Obj10 mapping frame: " + mappingFrame);
        }
        return TAILS_ART_BASES[mappingFrame];
    }

    public static int getTailsTailsArtBase(int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= TAILS_TAILS_SOURCES.length) {
            throw new IllegalArgumentException("Invalid Obj88 mapping frame: " + mappingFrame);
        }
        return mappingFrame < 7 ? TAILS_TAILS_UPRIGHT_ART_BASE
                : mappingFrame < 14 ? TAILS_TAILS_DIAGONAL_ART_BASE : TAILS_TAILS_HORIZONTAL_ART_BASE;
    }

    /** Reverse the Obj10 frame's destination VRAM index through its DPLC runs. */
    public static int translateTailsTileIndex(int mappingFrame, int destinationTile) {
        if (mappingFrame < 0 || mappingFrame >= TAILS_DPLC.length || destinationTile < 0) {
            throw new IllegalArgumentException("Invalid Obj10 frame or destination tile");
        }
        int destinationStart = 0;
        for (int[] run : TAILS_DPLC[mappingFrame]) {
            if (destinationTile < destinationStart + run[0]) {
                return getTailsArtBase(mappingFrame) + run[1] + destinationTile - destinationStart;
            }
            destinationStart += run[0];
        }
        throw new IllegalArgumentException("Destination tile is outside frame DPLC: " + destinationTile);
    }

    /** Reverse the compact Obj88 record at mappingFrame + $24. */
    public static int translateTailsTailsTileIndex(int mappingFrame, int destinationTile) {
        if (mappingFrame < 0 || mappingFrame >= TAILS_TAILS_SOURCES.length
                || destinationTile < 0 || destinationTile >= TAILS_TAILS_RUN_LENGTHS[mappingFrame]) {
            throw new IllegalArgumentException("Invalid Obj88 frame or destination tile");
        }
        int base = getTailsTailsArtBase(mappingFrame);
        return base + TAILS_TAILS_SOURCES[mappingFrame] + destinationTile;
    }

    private static SpriteFrame tailsFrame(int frame, int[]... pieces) {
        SpritePiece[] result = new SpritePiece[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            int[] v = pieces[i];
            result[i] = p(v[0], v[1], v[2], v[3], translateTailsTileIndex(frame, v[4]), v[5], 0);
        }
        return new SpriteFrame(result);
    }

    private static SpriteFrame tailsTailsFrame(int frame, int x, int y, int w, int h) {
        return new SpriteFrame(p(x, y, w, h, translateTailsTailsTileIndex(frame, 0), 0, 0));
    }

    /** Obj10_MapUnc_34B3E, with destination indices reverse-translated per frame. */
    public static final SpriteFrame[] TAILS_FRAMES = {
        tailsFrame(0,new int[]{-12,-24,3,3,0,0},new int[]{-12,0,3,2,9,0},new int[]{-12,16,1,1,15,0}),
        tailsFrame(1,new int[]{-16,-24,4,1,0,0},new int[]{-13,-16,3,2,4,0},new int[]{-16,0,4,1,10,0},new int[]{-8,8,2,2,14,0}),
        tailsFrame(2,new int[]{-16,-24,4,1,0,0},new int[]{-13,-16,3,2,4,0},new int[]{-16,0,4,1,10,0},new int[]{-8,8,2,2,14,0}),
        tailsFrame(3,new int[]{-16,-24,4,1,0,1},new int[]{-11,-16,3,2,4,1},new int[]{-16,0,4,1,10,0},new int[]{-8,8,2,2,14,0},new int[]{8,16,1,1,18,0}),
        tailsFrame(4,new int[]{0,-31,2,2,0,0},new int[]{-8,-15,4,2,4,0},new int[]{-20,1,4,2,12,0}),
        tailsFrame(5,new int[]{0,-31,1,2,0,0},new int[]{-9,-15,4,2,2,0},new int[]{-17,1,3,3,10,0},new int[]{7,1,1,2,19,0}),
        tailsFrame(6,new int[]{0,-24,1,1,0,0},new int[]{-8,-20,3,1,1,0},new int[]{-16,-12,4,2,4,0},new int[]{16,-12,1,1,12,0},new int[]{-16,4,3,2,13,0}),
        tailsFrame(7,new int[]{1,-23,1,1,0,0},new int[]{-16,-15,4,4,1,0},new int[]{16,-7,1,1,17,0},new int[]{-16,17,2,1,18,0}),
        tailsFrame(8,new int[]{0,-30,2,2,0,0},new int[]{-8,-14,4,1,4,0},new int[]{-16,-6,2,4,8,0},new int[]{0,-6,3,2,16,0}),
        tailsFrame(9,new int[]{0,-24,1,1,0,0},new int[]{-16,-16,4,2,1,0},new int[]{16,-8,1,1,9,0},new int[]{-16,0,4,2,10,0},new int[]{-16,16,2,1,18,0}),
        tailsFrame(10,new int[]{0,-24,1,1,0,0},new int[]{-16,-16,4,3,1,0},new int[]{16,-8,1,1,13,0},new int[]{-24,8,4,1,14,0},new int[]{-16,16,2,1,18,0}),
        tailsFrame(11,new int[]{0,-24,1,1,0,0},new int[]{-16,-16,4,3,1,0},new int[]{16,-8,1,1,13,0},new int[]{-24,8,4,2,14,0}),
        tailsFrame(12,new int[]{-20,-8,3,3,0,0},new int[]{4,-16,2,4,9,0}),
        tailsFrame(13,new int[]{-20,-8,2,2,0,0},new int[]{-12,8,1,1,4,0},new int[]{-4,-16,3,4,5,0}),
        tailsFrame(14,new int[]{-20,-9,1,2,0,0},new int[]{-12,-16,4,4,2,0}),
        tailsFrame(15,new int[]{-20,-8,1,3,0,0},new int[]{-12,-16,1,3,3,0},new int[]{-4,-16,3,4,6,0}),
        tailsFrame(16,new int[]{-16,-16,2,4,0,0},new int[]{0,-16,2,4,0,1}),
        tailsFrame(17,new int[]{-16,-16,2,4,0,0},new int[]{0,-16,2,4,0,1})
    };

    /** Obj88_MapUnc_34DA8. */
    public static final SpriteFrame[] TAILS_TAILS_FRAMES = {
        tailsTailsFrame(0,-6,-6,2,3), tailsTailsFrame(1,-8,0,3,3), tailsTailsFrame(2,-8,8,3,2),
        tailsTailsFrame(3,-9,-1,2,3), tailsTailsFrame(4,-11,-9,2,4), tailsTailsFrame(5,-16,-9,3,3),
        tailsTailsFrame(6,-16,-9,3,3), tailsTailsFrame(7,-12,0,3,3), tailsTailsFrame(8,-13,8,3,2),
        tailsTailsFrame(9,-15,0,2,3), tailsTailsFrame(10,-12,-8,2,4), tailsTailsFrame(11,-14,-8,3,4),
        tailsTailsFrame(12,-13,-8,3,3), tailsTailsFrame(13,-12,-8,3,3), tailsTailsFrame(14,-22,-4,3,2),
        tailsTailsFrame(15,-19,-8,3,3), tailsTailsFrame(16,-19,-8,2,3), tailsTailsFrame(17,-19,-9,3,2),
        tailsTailsFrame(18,-27,-11,4,2), tailsTailsFrame(19,-19,-16,3,3), tailsTailsFrame(20,-19,-16,3,3)
    };

    public static SpriteFrame getTailsFrame(int frameIndex) {
        return frameIndex >= 0 && frameIndex < TAILS_FRAMES.length ? TAILS_FRAMES[frameIndex] : TAILS_FRAMES[0];
    }

    public static SpriteFrame getTailsTailsFrame(int frameIndex) {
        return frameIndex >= 0 && frameIndex < TAILS_TAILS_FRAMES.length ? TAILS_TAILS_FRAMES[frameIndex] : TAILS_TAILS_FRAMES[0];
    }

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
