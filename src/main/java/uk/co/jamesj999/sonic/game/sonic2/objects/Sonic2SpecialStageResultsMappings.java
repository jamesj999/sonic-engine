package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprite mappings for special stage results screen (Obj6F).
 * Data ported from docs/s2disasm/mappings/sprite/obj6F.asm
 * <p>
 * The original game uses THREE VRAM regions for the special stage results:
 * <p>
 * 1. VRAM $0002-$003F: Selected letters (A,C,D,G,H,I,L,M,P,R,S,T,U,W) copied from title card
 *    These are loaded via SpecialStage_ResultsLetters to spell text like "SPECIAL STAGE"
 * <p>
 * 2. VRAM $0580-$058B: E, N, O letters from the start of the full title card art (ArtNem_TitleCard)
 *    The title card art starts with E, N, O (to spell "ZONE")
 * <p>
 * 3. VRAM $0590+: Special stage results art (ArtNem_SpecialStageResults)
 *    Contains emerald sprites, bonus text graphics, etc.
 */
public final class Sonic2SpecialStageResultsMappings {

    private Sonic2SpecialStageResultsMappings() {}

    // VRAM base addresses from original game
    public static final int VRAM_SS_LETTERS = 0x0002;    // Special stage letters (A,C,D,G,H,I,L,M,P,R,S,T,U,W)
    public static final int VRAM_TITLE_CARD = 0x0580;    // Full title card art (E, N, O at start)
    public static final int VRAM_RESULTS_ART = 0x0590;   // Results art (emeralds, bonus graphics)
    public static final int VRAM_HUD = 0x06CA;           // HUD art (SCORE, TIME, RINGS text)
    public static final int VRAM_NUMBERS = 0x04AC;       // Numbers art (bonus digits)

    // Art type constants for pieces
    public static final int ART_TYPE_SS_LETTERS = 0;     // Special stage letters at VRAM $02
    public static final int ART_TYPE_TITLE_CARD = 1;     // Title card art at VRAM $580 (E, N, O)
    public static final int ART_TYPE_RESULTS = 2;        // Results art at VRAM $590
    public static final int ART_TYPE_HUD = 3;            // HUD art at VRAM $6CA
    public static final int ART_TYPE_NUMBERS = 4;        // Numbers art at VRAM $4AC

    /**
     * Frame indices for different result screen elements.
     * Order matches obj6F.asm mappings table exactly.
     */
    public static final int FRAME_SPECIAL_STAGE = 0;           // "SPECIAL STAGE"
    public static final int FRAME_SONIC_GOT_A = 1;             // "SONIC GOT A"
    public static final int FRAME_MILES_GOT_A = 2;             // "MILES GOT A"
    public static final int FRAME_TAILS_GOT_A = 3;             // "TAILS GOT A"
    public static final int FRAME_CHAOS_EMERALD = 4;           // "CHAOS EMERALD" (singular)
    public static final int FRAME_EMERALD_BLUE = 5;            // Blue emerald (palette 2)
    public static final int FRAME_EMERALD_YELLOW = 6;          // Yellow emerald (palette 3)
    public static final int FRAME_EMERALD_PINK = 7;            // Pink emerald (palette 2)
    public static final int FRAME_EMERALD_GREEN = 8;           // Green emerald (palette 3)
    public static final int FRAME_EMERALD_ORANGE = 9;          // Orange emerald (palette 3)
    public static final int FRAME_EMERALD_PURPLE = 10;         // Purple emerald (palette 2)
    public static final int FRAME_EMERALD_GRAY = 11;           // Gray emerald (palette 1)
    public static final int FRAME_EMERALD_BONUS = 12;          // Emerald bonus display
    public static final int FRAME_RING_BONUS = 13;             // Ring bonus display
    public static final int FRAME_SCORE_AREA_1 = 14;           // Score area variant 1
    public static final int FRAME_SCORE_AREA_2 = 15;           // Score area variant 2
    public static final int FRAME_PERFECT_BONUS = 16;          // Perfect bonus display
    public static final int FRAME_TOTAL = 17;                  // Total display
    public static final int FRAME_RING_BONUS_ZERO = 18;        // Ring bonus with zero (Map_obj6F_027E)
    public static final int FRAME_SCORE_ZERO_1 = 19;           // Score zero variant 1 (Map_obj6F_0314)
    public static final int FRAME_SCORE_ZERO_2 = 20;           // Score zero variant 2 (Map_obj6F_0346)
    public static final int FRAME_SCORE_NUMBERS = 21;          // Score numbers only
    public static final int FRAME_SONIC_HAS_ALL = 22;          // "SONIC HAS ALL THE"
    public static final int FRAME_MILES_HAS_ALL = 23;          // "MILES HAS ALL THE"
    public static final int FRAME_TAILS_HAS_ALL = 24;          // "TAILS HAS ALL THE"
    public static final int FRAME_CHAOS_EMERALDS_LONG = 25;    // "CHAOS EMERALDS" (Super Sonic sequence)
    public static final int FRAME_CHAOS_EMERALDS = 25;        // Alias for FRAME_CHAOS_EMERALDS_LONG
    public static final int FRAME_NOW_SONIC_CAN = 26;          // "NOW SONIC CAN"
    public static final int FRAME_CHANGE_INTO = 27;            // "CHANGE INTO"
    public static final int FRAME_SUPER_SONIC = 28;            // "SUPER SONIC"

    /**
     * Extended piece with art type information.
     * spritePiece format: x, y, width, height, tileIndex, hFlip, vFlip, paletteIndex, priority
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

    // Helper to create a piece from special stage letters (VRAM $02-$3F)
    // These are letters A,C,D,G,H,I,L,M,P,R,S,T,U,W copied to low VRAM
    private static ResultsPiece ssLetters(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_SS_LETTERS, false, false, pal, ART_TYPE_SS_LETTERS);
    }

    // Helper to create a piece from title card art (VRAM $580+)
    // The title card has E, N, O at the start (for "ZONE")
    private static ResultsPiece titleCard(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_TITLE_CARD, false, false, pal, ART_TYPE_TITLE_CARD);
    }

    // Helper to create a piece from results art (VRAM $590+)
    // Contains emeralds, bonus graphics, etc.
    private static ResultsPiece results(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_RESULTS_ART, false, false, pal, ART_TYPE_RESULTS);
    }

    // Helper to create a piece from HUD art (VRAM $6CA+)
    // Contains SCORE/TIME/RING text, bonus label text
    private static ResultsPiece hud(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_HUD, false, false, pal, ART_TYPE_HUD);
    }

    // Helper to create a piece from Numbers art (VRAM $520+)
    // Contains digits for bonus counters
    private static ResultsPiece numbers(int x, int y, int w, int h, int tile, int pal) {
        return new ResultsPiece(x, y, w, h, tile - VRAM_NUMBERS, false, false, pal, ART_TYPE_NUMBERS);
    }

    /**
     * All mapping frames from obj6F.asm in exact order.
     * spritePiece x, y, w, h, tileIndex, flipX, flipY, paletteIndex, priority
     *
     * VRAM letter mapping:
     * SS Letters (VRAM $02-$3F):
     *   $02=A, $06=C, $0A=D, $0E=G, $12=H, $16=I, $18=L, $1C=M, $22=P, $26=R, $2A=S, $2E=T, $32=U, $36=W
     * Title Card (VRAM $580+):
     *   $580=E, $584=N, $588=O, $58C=Z
     */
    public static final ResultsPiece[][] FRAMES = {
            // Frame 0 (Map_obj6F_003A): "SPECIAL STAGE"
            {
                    ssLetters(-0x60, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(-0x50, 0, 2, 2, 0x22, 0),  // P
                    titleCard(-0x40, 0, 2, 2, 0x580, 0), // E
                    ssLetters(-0x30, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(-0x20, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x18, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x08, 0, 2, 2, 0x18, 0),  // L
                    ssLetters(0x10, 0, 2, 2, 0x2A, 0),   // S
                    ssLetters(0x20, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x2C, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x3C, 0, 2, 2, 0x0E, 0),   // G
                    titleCard(0x4C, 0, 2, 2, 0x580, 0),  // E
            },
            // Frame 1 (Map_obj6F_009C): "SONIC GOT A"
            {
                    ssLetters(-0x48, 0, 2, 2, 0x2A, 0),  // S
                    titleCard(-0x38, 0, 2, 2, 0x588, 0), // O
                    titleCard(-0x28, 0, 2, 2, 0x584, 0), // N
                    ssLetters(-0x18, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x10, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(0x08, 0, 2, 2, 0x0E, 0),   // G
                    titleCard(0x18, 0, 2, 2, 0x588, 0),  // O
                    ssLetters(0x28, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x40, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 2 (Map_obj6F_00E6): "MILES GOT A"
            {
                    ssLetters(-0x4C, 0, 3, 2, 0x1C, 0),  // M
                    ssLetters(-0x34, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x2C, 0, 2, 2, 0x18, 0),  // L
                    titleCard(-0x1C, 0, 2, 2, 0x580, 0), // E
                    ssLetters(-0x0C, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(0x0C, 0, 2, 2, 0x0E, 0),   // G
                    titleCard(0x1C, 0, 2, 2, 0x588, 0),  // O
                    ssLetters(0x2C, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x44, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 3 (Map_obj6F_0130): "TAILS GOT A"
            {
                    ssLetters(-0x45, 0, 2, 2, 0x2E, 0),  // T
                    ssLetters(-0x38, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x28, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x20, 0, 2, 2, 0x18, 0),  // L
                    ssLetters(-0x10, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(0x08, 0, 2, 2, 0x0E, 0),   // G
                    titleCard(0x18, 0, 2, 2, 0x588, 0),  // O
                    ssLetters(0x28, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x40, 0, 2, 2, 0x02, 0),   // A
            },
            // Frame 4 (Map_obj6F_017A): "CHAOS EMERALD" (singular - used when got 1 emerald)
            {
                    ssLetters(-0x68, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(-0x58, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x48, 0, 2, 2, 0x02, 0),  // A
                    titleCard(-0x38, 0, 2, 2, 0x588, 0), // O
                    ssLetters(-0x28, 0, 2, 2, 0x2A, 0),  // S
                    titleCard(-0x0D, 0, 2, 2, 0x580, 0), // E
                    ssLetters(0x00, 0, 3, 2, 0x1C, 0),   // M
                    titleCard(0x18, 0, 2, 2, 0x580, 0),  // E
                    ssLetters(0x28, 0, 2, 2, 0x26, 0),   // R
                    ssLetters(0x38, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x48, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x58, 0, 2, 2, 0x0A, 0),   // D
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
            // Frame 11 (Map_obj6F_0218): Gray Emerald - palette 1
            {
                    results(0, 0, 2, 2, 0x5A8, 1),
            },
            // Frame 12 (Map_obj6F_0222): Emerald bonus display
            // HUD tiles: $6CA (GEMS text), $6E0, $6E4, $6EA (BONUS text)
            // Results tiles: $5A0 (emerald icon)
            {
                    hud(-0x60, 0, 4, 2, 0x6CA, 1),      // "GEMS" text from HUD
                    hud(-0x40, 0, 1, 2, 0x6E0, 1),      // part of label
                    results(-0x44, 0, 2, 2, 0x5A0, 0),  // emerald icon
                    hud(0x28, 0, 3, 2, 0x6E4, 0),       // "BONUS" part 1
                    hud(0x40, 0, 4, 2, 0x6EA, 0),       // "BONUS" part 2
            },
            // Frame 13 (Map_obj6F_024C): Ring bonus display
            // HUD tiles: $6CA, $6D2 (text labels)
            // Results tiles: $5B0, $5A0
            // Numbers tiles: $528
            {
                    hud(-0x60, 0, 1, 2, 0x6CA, 1),      // "RING" part
                    results(-0x58, 0, 4, 2, 0x5B0, 1),  // results text
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    numbers(0x40, 0, 4, 2, 0x528, 0),   // number digits
            },
            // Frame 14 (Map_obj6F_02B0): Score area 1
            {
                    results(-0x60, 0, 4, 2, 0x5B8, 1),  // results text
                    hud(-0x40, 0, 1, 2, 0x6CA, 1),      // separator
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    numbers(0x40, 0, 4, 2, 0x530, 0),   // number digits
            },
            // Frame 15 (Map_obj6F_02E2): Score area 2
            {
                    results(-0x60, 0, 3, 2, 0x5CE, 1),  // results text
                    results(-0x48, 0, 2, 2, 0x5D4, 1),  // results text
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    numbers(0x40, 0, 4, 2, 0x530, 0),   // number digits
            },
            // Frame 16 (Map_obj6F_0378): Perfect bonus
            {
                    results(-0x60, 0, 4, 2, 0x598, 1),  // "PERFECT" text
                    results(-0x30, 0, 4, 2, 0x590, 1),  // results text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    numbers(0x38, 0, 4, 2, 0x520, 0),   // number digits
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank
            },
            // Frame 17 (Map_obj6F_03AA): Total display
            {
                    results(-0x70, 0, 4, 2, 0x5C0, 1),  // "TOTAL" text
                    results(-0x50, 0, 3, 2, 0x5C8, 1),  // results text
                    results(-0x30, 0, 4, 2, 0x590, 1),  // results text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    numbers(0x38, 0, 4, 2, 0x528, 0),   // number digits
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank
            },
            // Frame 18 (Map_obj6F_027E): Ring bonus with zero
            {
                    hud(-0x60, 0, 1, 2, 0x6CA, 1),      // "RING" part
                    results(-0x58, 0, 4, 2, 0x5B0, 1),  // results text
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank (zero)
            },
            // Frame 19 (Map_obj6F_0314): Score zero 1
            {
                    results(-0x60, 0, 4, 2, 0x5B8, 1),  // results text
                    hud(-0x40, 0, 1, 2, 0x6CA, 1),      // separator
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank (zero)
            },
            // Frame 20 (Map_obj6F_0346): Score zero 2
            {
                    results(-0x60, 0, 3, 2, 0x5CE, 1),  // results text
                    results(-0x48, 0, 2, 2, 0x5D4, 1),  // results text
                    hud(-0x30, 0, 4, 2, 0x6D2, 1),      // "BONUS" text
                    hud(-0x10, 0, 1, 2, 0x6CA, 1),      // separator
                    results(-0x14, 0, 2, 2, 0x5A0, 0),  // icon
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank (zero)
            },
            // Frame 21 (Map_obj6F_03E4): Score numbers only
            {
                    numbers(0x38, 0, 4, 2, 0x528, 0),   // number digits
                    hud(0x58, 0, 1, 2, 0x6F0, 0),       // trailing blank
            },
            // Frame 22 (Map_obj6F_03F6): "SONIC HAS ALL THE"
            {
                    ssLetters(-0x78, 0, 2, 2, 0x2A, 0),  // S
                    titleCard(-0x68, 0, 2, 2, 0x588, 0), // O (from title card)
                    titleCard(-0x58, 0, 2, 2, 0x584, 0), // N (from title card)
                    ssLetters(-0x48, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x40, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(-0x28, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x18, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x08, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(0x10, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x20, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x30, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x48, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x58, 0, 2, 2, 0x12, 0),   // H
                    titleCard(0x68, 0, 2, 2, 0x580, 0),  // E (from title card)
            },
            // Frame 23 (Map_obj6F_0468): "MILES HAS ALL THE"
            {
                    ssLetters(-0x7C, 0, 3, 2, 0x1C, 0),  // M (wide)
                    ssLetters(-0x64, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x5C, 0, 2, 2, 0x18, 0),  // L
                    titleCard(-0x4C, 0, 2, 2, 0x580, 0), // E (from title card)
                    ssLetters(-0x3C, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(-0x24, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x14, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x04, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(0x14, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x24, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x34, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x4C, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x5C, 0, 2, 2, 0x12, 0),   // H
                    titleCard(0x6C, 0, 2, 2, 0x580, 0),  // E (from title card)
            },
            // Frame 24 (Map_obj6F_04DA): "TAILS HAS ALL THE"
            {
                    ssLetters(-0x75, 0, 2, 2, 0x2E, 0),  // T
                    ssLetters(-0x68, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x58, 0, 1, 2, 0x16, 0),  // I
                    ssLetters(-0x50, 0, 2, 2, 0x18, 0),  // L
                    ssLetters(-0x40, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(-0x28, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x18, 0, 2, 2, 0x02, 0),  // A
                    ssLetters(-0x08, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(0x10, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x20, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x30, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x48, 0, 2, 2, 0x2E, 0),   // T
                    ssLetters(0x58, 0, 2, 2, 0x12, 0),   // H
                    titleCard(0x68, 0, 2, 2, 0x580, 0),  // E (from title card)
            },
            // Frame 25 (Map_obj6F_054C): "CHAOS EMERALDS" (Super Sonic sequence)
            {
                    ssLetters(-0x70, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(-0x60, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x50, 0, 2, 2, 0x02, 0),  // A
                    titleCard(-0x40, 0, 2, 2, 0x588, 0), // O (from title card)
                    ssLetters(-0x30, 0, 2, 2, 0x2A, 0),  // S
                    titleCard(-0x15, 0, 2, 2, 0x580, 0), // E (from title card)
                    ssLetters(-0x08, 0, 3, 2, 0x1C, 0),  // M (wide)
                    titleCard(0x10, 0, 2, 2, 0x580, 0),  // E (from title card)
                    ssLetters(0x20, 0, 2, 2, 0x26, 0),   // R
                    ssLetters(0x30, 0, 2, 2, 0x02, 0),   // A
                    ssLetters(0x40, 0, 2, 2, 0x18, 0),   // L
                    ssLetters(0x50, 0, 2, 2, 0x0A, 0),   // D
                    ssLetters(0x60, 0, 2, 2, 0x2A, 0),   // S
            },
            // Frame 26 (Map_obj6F_05B6): "NOW SONIC CAN"
            {
                    titleCard(-0x60, 0, 2, 2, 0x584, 0), // N (from title card)
                    titleCard(-0x50, 0, 2, 2, 0x588, 0), // O (from title card)
                    ssLetters(-0x40, 0, 3, 2, 0x36, 0),  // W (wide)
                    ssLetters(-0x20, 0, 2, 2, 0x2A, 0),  // S
                    titleCard(-0x10, 0, 2, 2, 0x588, 0), // O (from title card)
                    titleCard(0x00, 0, 2, 2, 0x584, 0),  // N (from title card)
                    ssLetters(0x10, 0, 1, 2, 0x16, 0),   // I
                    ssLetters(0x18, 0, 2, 2, 0x06, 0),   // C
                    ssLetters(0x30, 0, 2, 2, 0x06, 0),   // C
                    ssLetters(0x40, 0, 2, 2, 0x02, 0),   // A
                    titleCard(0x50, 0, 2, 2, 0x584, 0),  // N (from title card)
            },
            // Frame 27 (Map_obj6F_0610): "CHANGE INTO"
            {
                    ssLetters(-0x50, 0, 2, 2, 0x06, 0),  // C
                    ssLetters(-0x40, 0, 2, 2, 0x12, 0),  // H
                    ssLetters(-0x30, 0, 2, 2, 0x02, 0),  // A
                    titleCard(-0x20, 0, 2, 2, 0x584, 0), // N (from title card)
                    ssLetters(-0x10, 0, 2, 2, 0x0E, 0),  // G
                    titleCard(0x00, 0, 2, 2, 0x580, 0),  // E (from title card)
                    ssLetters(0x18, 0, 1, 2, 0x16, 0),   // I
                    titleCard(0x20, 0, 2, 2, 0x584, 0),  // N (from title card)
                    ssLetters(0x30, 0, 2, 2, 0x2E, 0),   // T
                    titleCard(0x40, 0, 2, 2, 0x588, 0),  // O (from title card)
            },
            // Frame 28 (Map_obj6F_0662): "SUPER SONIC"
            {
                    ssLetters(-0x50, 0, 2, 2, 0x2A, 0),  // S
                    ssLetters(-0x40, 0, 2, 2, 0x32, 0),  // U
                    ssLetters(-0x30, 0, 2, 2, 0x22, 0),  // P
                    titleCard(-0x20, 0, 2, 2, 0x580, 0), // E (from title card)
                    ssLetters(-0x10, 0, 2, 2, 0x26, 0),  // R
                    ssLetters(0x08, 0, 2, 2, 0x2A, 0),   // S
                    titleCard(0x18, 0, 2, 2, 0x588, 0),  // O (from title card)
                    titleCard(0x28, 0, 2, 2, 0x584, 0),  // N (from title card)
                    ssLetters(0x38, 0, 1, 2, 0x16, 0),   // I
                    ssLetters(0x40, 0, 2, 2, 0x06, 0),   // C
            },
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

    /**
     * Convert a frame to a unified SpriteMappingFrame where all three art types are combined.
     * SS letters at offset 0, title card at titleCardOffset, results at resultsOffset.
     *
     * @param frameIndex         Frame index to convert
     * @param titleCardOffset    Offset where title card E,N,O letters begin
     * @param resultsOffset      Offset where results art begins
     * @return SpriteMappingFrame with all pieces using unified tile indices
     */
    public static SpriteMappingFrame toUnifiedMappingFrame(int frameIndex, int titleCardOffset, int resultsOffset) {
        ResultsPiece[] pieces = getFrame(frameIndex);
        List<SpriteMappingPiece> mappingPieces = new ArrayList<>();

        for (ResultsPiece piece : pieces) {
            int tileIndex;
            if (piece.artType() == ART_TYPE_SS_LETTERS) {
                // SS letters start at index 0
                tileIndex = piece.tileIndex();
            } else if (piece.artType() == ART_TYPE_TITLE_CARD) {
                // Title card E,N,O start at titleCardOffset
                tileIndex = piece.tileIndex() + titleCardOffset;
            } else {
                // Results art starts at resultsOffset
                tileIndex = piece.tileIndex() + resultsOffset;
            }

            mappingPieces.add(new SpriteMappingPiece(
                    piece.xOffset(),
                    piece.yOffset(),
                    piece.widthTiles(),
                    piece.heightTiles(),
                    tileIndex,
                    piece.hFlip(),
                    piece.vFlip(),
                    piece.paletteIndex()
            ));
        }

        return new SpriteMappingFrame(mappingPieces);
    }
}
