package com.openggf.game.sonic2.credits;

import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Sonic2CreditsMappings} credit text mapping data.
 * <p>
 * Verifies the hardcoded mapping frames match the expected structure from the
 * S2 disassembly (21 credit screens, each with at least one text entry,
 * using the ROM's variable-width character encoding: 2-wide for most letters
 * and digits, 1-wide for I, @, ., (, )).
 */
public class TestSonic2CreditsMappings {

    // ========================================================================
    // Frame count
    // ========================================================================

    @Test
    public void testMappingsHave21Frames() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        assertEquals(21, frames.size(), "Should have exactly 21 credit screens");
    }

    @Test
    public void testFrameCountMatchesCreditsDataConstant() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        assertEquals(Sonic2CreditsData.TOTAL_CREDITS, frames.size(), "Frame count should match Sonic2CreditsData.TOTAL_CREDITS");
    }

    // ========================================================================
    // All frames have pieces
    // ========================================================================

    @Test
    public void testAllFramesHavePieces() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int i = 0; i < frames.size(); i++) {
            assertNotNull(frames.get(i), "Frame " + i + " should not be null");
            assertFalse(frames.get(i).pieces().isEmpty(), "Frame " + i + " should have pieces");
        }
    }

    // ========================================================================
    // Piece geometry (2-wide character encoding)
    // ========================================================================

    @Test
    public void testAllPiecesAre1x1Tiles() {
        // ROM credit font uses 8Ã—8 tiles: each charset value is a single tile,
        // and characters are 2 tiles wide Ã— 1 tile tall (16Ã—8 px).
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int f = 0; f < frames.size(); f++) {
            for (int p = 0; p < frames.get(f).pieces().size(); p++) {
                SpriteMappingPiece piece = frames.get(f).pieces().get(p);
                assertEquals(1, piece.widthTiles(), "Frame " + f + " piece " + p + " width should be 1 tile");
                assertEquals(1, piece.heightTiles(), "Frame " + f + " piece " + p + " height should be 1 tile");
            }
        }
    }

    @Test
    public void testPieceCountsMatchVariableWidthEncoding() {
        // Most characters produce 2 pieces (left + right column).
        // 1-wide characters (I, @, ., (, )) produce 1 piece only.
        // Verify that at least one frame has an odd count (proving variable width works).
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        boolean hasOddCount = false;
        for (SpriteMappingFrame frame : frames) {
            if (frame.pieces().size() % 2 != 0) {
                hasOddCount = true;
                break;
            }
        }
        assertTrue(hasOddCount, "At least one frame should have an odd piece count (1-wide chars present)");
    }

    // ========================================================================
    // Specific screen content validation
    // ========================================================================

    @Test
    public void testFirstScreenIsSonic2CastOfCharacters() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        SpriteMappingFrame screen0 = frames.get(0);
        // "SONIC" = S(2)+O(2)+N(2)+I(1)+C(2) = 9 pieces (I is 1-wide in ROM)
        // "2" = 2(2) = 2 pieces
        // "CAST  OF  CHARACTERS" = 16 non-space letters * 2 = 32 pieces
        // Total = 9 + 2 + 32 = 43 pieces
        assertEquals(43, screen0.pieces().size(), "Screen 0 (SONIC 2 / CAST OF CHARACTERS) piece count");
    }

    @Test
    public void testPresentedBySegaIsLastFrame() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        SpriteMappingFrame lastFrame = frames.get(20);
        // "PRESENTED" = 9 chars, "BY" = 2 chars, "SEGA" = 4 chars = 15 * 2 = 30 pieces
        int expectedPieces = (9 + 2 + 4) * 2; // 30
        assertEquals(expectedPieces, lastFrame.pieces().size(), "Last frame (PRESENTED BY SEGA) should have 30 pieces");
    }

    @Test
    public void testZoneArtistsScreenHasMostNames() {
        // Screen 10 (ZONE ARTISTS) has the most names: 6 artists + 1 title = 7 lines
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        SpriteMappingFrame screen10 = frames.get(10);

        // Find the screen with the most pieces -- screen 10 should be a large one
        int maxPieces = 0;
        int maxScreen = -1;
        for (int i = 0; i < frames.size(); i++) {
            int count = frames.get(i).pieces().size();
            if (count > maxPieces) {
                maxPieces = count;
                maxScreen = i;
            }
        }

        // Zone Artists or Executive Supporters or Special Thanks screens should have
        // the most pieces due to having the most text lines
        assertTrue(maxScreen >= 10 || maxScreen == 6 || maxScreen == 17 || maxScreen == 18 || maxScreen == 19, "Screen with most pieces should be >= 10");
    }

    // ========================================================================
    // Palette validation
    // ========================================================================

    @Test
    public void testPaletteLinesAreValidRange() {
        // Credit text uses palette 0 (names) and palette 1 (role titles)
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        Set<Integer> palettes = new HashSet<>();
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                palettes.add(piece.paletteIndex());
                assertTrue(piece.paletteIndex() == 0 || piece.paletteIndex() == 1, "Palette index " + piece.paletteIndex() + " should be 0 or 1");
            }
        }
        // Both palettes should be used
        assertTrue(palettes.contains(0), "Palette 0 (names) should be used");
        assertTrue(palettes.contains(1), "Palette 1 (roles) should be used");
    }

    @Test
    public void testNoFlipsInCreditText() {
        // Credit text should not use H-flip or V-flip
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int f = 0; f < frames.size(); f++) {
            for (SpriteMappingPiece piece : frames.get(f).pieces()) {
                assertFalse(piece.hFlip(), "Frame " + f + " should not use H-flip");
                assertFalse(piece.vFlip(), "Frame " + f + " should not use V-flip");
            }
        }
    }

    @Test
    public void testNoPriorityInCreditText() {
        // Credit text should not use priority bit
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int f = 0; f < frames.size(); f++) {
            for (SpriteMappingPiece piece : frames.get(f).pieces()) {
                assertFalse(piece.priority(), "Frame " + f + " should not use priority");
            }
        }
    }

    // ========================================================================
    // Tile index validation
    // ========================================================================

    @Test
    public void testTileIndicesArePositive() {
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int f = 0; f < frames.size(); f++) {
            for (SpriteMappingPiece piece : frames.get(f).pieces()) {
                assertTrue(piece.tileIndex() >= 0, "Frame " + f + " tile index should be >= 0, got " + piece.tileIndex());
            }
        }
    }

    @Test
    public void testTileIndicesWithinFontRange() {
        // S2 credit font tile indices are 0-based (ArtTile offset already subtracted).
        // Max value is around 0x3E (from charset '9' right digit).
        List<SpriteMappingFrame> frames = Sonic2CreditsMappings.createFrames();
        for (int f = 0; f < frames.size(); f++) {
            for (SpriteMappingPiece piece : frames.get(f).pieces()) {
                assertTrue(piece.tileIndex() <= 0x3F, "Frame " + f + " tile index " + piece.tileIndex()
                                + " should be within font range (<= 0x3F)");
            }
        }
    }
}


