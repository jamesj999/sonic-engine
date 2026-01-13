package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprite mappings for special stage results screen (Obj6F).
 * Data ported from docs/s2disasm/mappings/sprite/obj6F.asm
 *
 * The original game uses multiple VRAM regions:
 * - Title card letters at VRAM $0002 (for text like "SPECIAL STAGE")
 * - Special stage results art at VRAM $0590 (emeralds and other graphics)
 *
 * For our engine, we use two separate art sets and need to translate tile indices.
 */
public final class Sonic2SpecialStageResultsMappings {

    private Sonic2SpecialStageResultsMappings() {}

    // VRAM base addresses from original game
    public static final int VRAM_TITLE_LETTERS = 0x0002;
    public static final int VRAM_RESULTS_ART = 0x0590;

    // Art type constants for pieces
    public static final int ART_TYPE_LETTERS = 0;  // Title card letters
    public static final int ART_TYPE_RESULTS = 1;  // Special stage results art

    /**
     * Frame indices for different result screen elements.
     */
    public static final int FRAME_SPECIAL_STAGE = 0;      // "SPECIAL STAGE" title
    public static final int FRAME_SONIC_GOT_A = 1;        // "Sonic got a"
    public static final int FRAME_CHAOS_EMERALD = 2;      // "Chaos Emerald"
    public static final int FRAME_SONIC_GOT = 3;          // "Sonic got"
    public static final int FRAME_CHAOS_EMERALDS = 4;     // "Chaos Emeralds" (all 7)
    public static final int FRAME_EMERALD_BLUE = 5;       // Blue emerald
    public static final int FRAME_EMERALD_YELLOW = 6;     // Yellow emerald
    public static final int FRAME_EMERALD_PINK = 7;       // Pink emerald
    public static final int FRAME_EMERALD_GREEN = 8;      // Green emerald
    public static final int FRAME_EMERALD_ORANGE = 9;     // Orange emerald
    public static final int FRAME_EMERALD_PURPLE = 10;    // Purple emerald
    public static final int FRAME_EMERALD_GRAY = 11;      // Gray emerald
    public static final int FRAME_EMERALD_BONUS = 12;     // "EMERALD BONUS"
    public static final int FRAME_RING_BONUS = 13;        // "RING BONUS"
    public static final int FRAME_PERFECT_BONUS = 14;     // "PERFECT BONUS"
    public static final int FRAME_TOTAL = 15;             // "TOTAL"
    // More frames for numbers and score display...

    /**
     * Extended piece with art type information.
     */
    public record ResultsPiece(
            int xOffset,
            int yOffset,
            int widthTiles,
            int heightTiles,
            int tileIndex,
            boolean hFlip,
            boolean vFlip,
            int paletteIndex,
            int artType
    ) {
        public SpriteMappingPiece toMappingPiece(int localTileIndex) {
            return new SpriteMappingPiece(
                    xOffset, yOffset, widthTiles, heightTiles,
                    localTileIndex, hFlip, vFlip, paletteIndex
            );
        }
    }

    // Helper to create a piece referencing title card letters
    private static ResultsPiece letters(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_TITLE_LETTERS, false, false, pal, ART_TYPE_LETTERS);
    }

    // Helper to create a piece referencing results art
    private static ResultsPiece results(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_RESULTS_ART, false, false, pal, ART_TYPE_RESULTS);
    }

    // Helper for results art with flip flags
    private static ResultsPiece resultsFlip(int x, int y, int w, int h, int tile, boolean hf, boolean vf, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_RESULTS_ART, hf, vf, pal, ART_TYPE_RESULTS);
    }

    /**
     * All mapping frames from obj6F.asm.
     * Each frame is an array of pieces with art type information.
     */
    public static final ResultsPiece[][] FRAMES = {
            // Frame 0 (Map_obj6F_003A): "SPECIAL STAGE"
            // spritePiece x, y, w, h, tileIndex, flipX, flipY, paletteIndex, priority
            {
                    letters(-0x60, 0, 2, 2, 0x2A, 0),  // S
                    letters(-0x50, 0, 2, 2, 0x22, 0),  // P
                    results(-0x40, 0, 2, 2, 0x580, 0), // E (from results art)
                    letters(-0x30, 0, 2, 2, 0x06, 0),  // C
                    letters(-0x20, 0, 1, 2, 0x16, 0),  // I
                    letters(-0x18, 0, 2, 2, 0x02, 0),  // A
                    letters(-0x08, 0, 2, 2, 0x18, 0),  // L
                    letters(0x10, 0, 2, 2, 0x2A, 0),   // S
                    letters(0x20, 0, 2, 2, 0x2E, 0),   // T
                    letters(0x2C, 0, 2, 2, 0x02, 0),   // A
                    letters(0x3C, 0, 2, 2, 0x0E, 0),   // G
                    results(0x4C, 0, 2, 2, 0x580, 0),  // E (from results art)
            },
            // Frame 1 (Map_obj6F_009C): "SONIC GOT A"
            {
                    letters(-0x48, 0, 2, 2, 0x2A, 0),  // S
                    results(-0x38, 0, 2, 2, 0x588, 0), // O (from results art)
                    results(-0x28, 0, 2, 2, 0x584, 0), // N (from results art)
                    letters(-0x18, 0, 1, 2, 0x16, 0),  // I
                    letters(-0x10, 0, 2, 2, 0x06, 0),  // C
                    letters(0x08, 0, 2, 2, 0x0E, 0),   // G
                    results(0x18, 0, 2, 2, 0x588, 0),  // O (from results art)
                    letters(0x28, 0, 2, 2, 0x2E, 0),   // T
                    letters(0x40, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 2 (Map_obj6F_00E6): "CHAOS EMERALD"
            {
                    letters(-0x4C, 0, 3, 2, 0x1C, 0),  // CH
                    letters(-0x34, 0, 1, 2, 0x16, 0),  // A (partial)
                    letters(-0x2C, 0, 2, 2, 0x18, 0),  // L -> should be AO
                    results(-0x1C, 0, 2, 2, 0x580, 0), // S -> E
                    letters(-0x0C, 0, 2, 2, 0x2A, 0),  // S
                    letters(0x0C, 0, 2, 2, 0x0E, 0),   // G -> space?
                    results(0x1C, 0, 2, 2, 0x588, 0),  // E -> O
                    letters(0x2C, 0, 2, 2, 0x2E, 0),   // T
                    letters(0x44, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 3 (Map_obj6F_0130): "SONIC GOT" (variant)
            {
                    letters(-0x45, 0, 2, 2, 0x2E, 0),  // T
                    letters(-0x38, 0, 2, 2, 0x02, 0),  // A
                    letters(-0x28, 0, 1, 2, 0x16, 0),  // I
                    letters(-0x20, 0, 2, 2, 0x18, 0),  // L
                    letters(-0x10, 0, 2, 2, 0x2A, 0),  // S
                    letters(0x08, 0, 2, 2, 0x0E, 0),   // G
                    results(0x18, 0, 2, 2, 0x588, 0),  // O
                    letters(0x28, 0, 2, 2, 0x2E, 0),   // T
                    letters(0x40, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 4 (Map_obj6F_017A): "CHAOS EMERALDS" (all 7)
            {
                    letters(-0x68, 0, 2, 2, 0x06, 0),  // C
                    letters(-0x58, 0, 2, 2, 0x12, 0),  // H
                    letters(-0x48, 0, 2, 2, 0x02, 0),  // A
                    results(-0x38, 0, 2, 2, 0x588, 0), // O
                    letters(-0x28, 0, 2, 2, 0x2A, 0),  // S
                    results(-0x0D, 0, 2, 2, 0x580, 0), // E
                    letters(0x00, 0, 3, 2, 0x1C, 0),   // M (wide)
                    results(0x18, 0, 2, 2, 0x580, 0),  // E
                    letters(0x28, 0, 2, 2, 0x26, 0),   // R
                    letters(0x38, 0, 2, 2, 0x02, 0),   // A
                    letters(0x48, 0, 2, 2, 0x18, 0),   // L
                    letters(0x58, 0, 2, 2, 0x0A, 0),   // D/S
            },
            // Frame 5 (Map_obj6F_01DC): Blue Emerald - palette 2
            {
                    results(0, 0, 2, 2, 0x5A4, 2),
            },
            // Frame 6 (Map_obj6F_01E6): Yellow Emerald - palette 3
            {
                    results(0, 0, 2, 2, 0x5A4, 3),
            },
            // Frame 7 (Map_obj6F_01F0): Pink Emerald - palette 2
            {
                    results(0, 0, 2, 2, 0x5AC, 2),
            },
            // Frame 8 (Map_obj6F_01FA): Green Emerald - palette 3
            {
                    results(0, 0, 2, 2, 0x5AC, 3),
            },
            // Frame 9 (Map_obj6F_0204): Orange Emerald - palette 3
            {
                    results(0, 0, 2, 2, 0x5A8, 3),
            },
            // Frame 10 (Map_obj6F_020E): Purple Emerald - palette 2
            {
                    results(0, 0, 2, 2, 0x5A8, 2),
            },
            // Frame 11 (Map_obj6F_0218): Gray/Cyan Emerald - palette 1
            {
                    results(0, 0, 2, 2, 0x5A8, 1),
            },
            // Frame 12 (Map_obj6F_0222): "EMERALD BONUS"
            // These use additional HUD/number art - simplified for now
            {
                    results(-0x60, 0, 4, 2, 0x6CA, 1),
                    results(-0x40, 0, 1, 2, 0x6E0, 1),
                    results(-0x44, 0, 2, 2, 0x5A0, 0),
                    results(0x28, 0, 3, 2, 0x6E4, 0),
                    results(0x40, 0, 4, 2, 0x6EA, 0),
            },
            // Frame 13 (Map_obj6F_024C): Ring bonus display
            {
                    results(-0x60, 0, 1, 2, 0x6CA, 1),
                    results(-0x58, 0, 4, 2, 0x5B0, 1),
                    results(-0x30, 0, 4, 2, 0x6D2, 1),
                    results(-0x10, 0, 1, 2, 0x6CA, 1),
                    results(-0x14, 0, 2, 2, 0x5A0, 0),
                    results(0x40, 0, 4, 2, 0x528, 0),
            },
            // Frame 14 (Map_obj6F_02B0): Ring bonus tally area
            {
                    results(-0x60, 0, 4, 2, 0x5B8, 1),
                    results(-0x40, 0, 1, 2, 0x6CA, 1),
                    results(-0x30, 0, 4, 2, 0x6D2, 1),
                    results(-0x10, 0, 1, 2, 0x6CA, 1),
                    results(-0x14, 0, 2, 2, 0x5A0, 0),
                    results(0x40, 0, 4, 2, 0x530, 0),
            },
            // Frame 15 (Map_obj6F_02E2): Additional bonus text
            {
                    results(-0x60, 0, 3, 2, 0x5CE, 1),
                    results(-0x48, 0, 2, 2, 0x5D4, 1),
                    results(-0x30, 0, 4, 2, 0x6D2, 1),
                    results(-0x10, 0, 1, 2, 0x6CA, 1),
                    results(-0x14, 0, 2, 2, 0x5A0, 0),
                    results(0x40, 0, 4, 2, 0x530, 0),
            },
            // Frame 16 (Map_obj6F_0378): Perfect bonus
            {
                    results(-0x60, 0, 4, 2, 0x598, 1),
                    results(-0x30, 0, 4, 2, 0x590, 1),
                    results(-0x10, 0, 1, 2, 0x6CA, 1),
                    results(-0x14, 0, 2, 2, 0x5A0, 0),
                    results(0x38, 0, 4, 2, 0x520, 0),
                    results(0x58, 0, 1, 2, 0x6F0, 0),
            },
            // Frame 17 (Map_obj6F_03AA): Total
            {
                    results(-0x70, 0, 4, 2, 0x5C0, 1),
                    results(-0x50, 0, 3, 2, 0x5C8, 1),
                    results(-0x30, 0, 4, 2, 0x590, 1),
                    results(-0x10, 0, 1, 2, 0x6CA, 1),
                    results(-0x14, 0, 2, 2, 0x5A0, 0),
                    results(0x38, 0, 4, 2, 0x528, 0),
                    results(0x58, 0, 1, 2, 0x6F0, 0),
            },
            // Frame 18 (Map_obj6F_03E4): Score number area only
            {
                    results(0x38, 0, 4, 2, 0x528, 0),
                    results(0x58, 0, 1, 2, 0x6F0, 0),
            },
            // Frame 19 (Map_obj6F_03F6): "SONIC GOT 2 CHAOS EMERALDS"
            {
                    letters(-0x78, 0, 2, 2, 0x2A, 0),  // S
                    results(-0x68, 0, 2, 2, 0x588, 0), // O
                    results(-0x58, 0, 2, 2, 0x584, 0), // N
                    letters(-0x48, 0, 1, 2, 0x16, 0),  // I
                    letters(-0x40, 0, 2, 2, 0x06, 0),  // C
                    letters(-0x28, 0, 2, 2, 0x12, 0),  // (space)
                    letters(-0x18, 0, 2, 2, 0x02, 0),  // G
                    letters(-0x08, 0, 2, 2, 0x2A, 0),  // O
                    letters(0x10, 0, 2, 2, 0x02, 0),   // T
                    letters(0x20, 0, 2, 2, 0x18, 0),   // (space)
                    letters(0x30, 0, 2, 2, 0x18, 0),   // C
                    letters(0x48, 0, 2, 2, 0x2E, 0),   // H
                    letters(0x58, 0, 2, 2, 0x12, 0),   // A
                    results(0x68, 0, 2, 2, 0x580, 0),  // O
            },
            // Frame 20-28: More text variations (abbreviated)
            // These can be added as needed
    };

    /**
     * Get frame pieces for a given frame index.
     */
    public static ResultsPiece[] getFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= FRAMES.length) {
            return FRAMES[0];
        }
        return FRAMES[frameIndex];
    }

    /**
     * Get the number of defined frames.
     */
    public static int getFrameCount() {
        return FRAMES.length;
    }

    /**
     * Convert a frame to a SpriteMappingFrame for rendering with a specific art set.
     * This filters pieces to only include those matching the specified art type,
     * and adjusts tile indices to be relative to that art set's base.
     */
    public static SpriteMappingFrame toMappingFrame(int frameIndex, int artType) {
        ResultsPiece[] pieces = getFrame(frameIndex);
        List<SpriteMappingPiece> mappingPieces = new ArrayList<>();

        for (ResultsPiece piece : pieces) {
            if (piece.artType() == artType) {
                // The tile index is already relative to the art type's VRAM base
                mappingPieces.add(new SpriteMappingPiece(
                        piece.xOffset(),
                        piece.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex(),
                        piece.hFlip(),
                        piece.vFlip(),
                        piece.paletteIndex()
                ));
            }
        }

        return new SpriteMappingFrame(mappingPieces);
    }
}
