package com.openggf.game;

import com.openggf.game.sonic1.Sonic1HudStaticArtFactory;
import com.openggf.game.sonic2.Sonic2HudStaticArtFactory;
import com.openggf.game.sonic3k.Sonic3kHudStaticArtFactory;
import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.HudStaticArtFactory;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestHudStaticArtLivesFrameMappings {

    @Test
    void sharedBuilderConcatenatesPatternsWithoutReplacingInstances() {
        Pattern text = new Pattern();
        Pattern lives = new Pattern();

        HudStaticArt art = HudStaticArtFactory.create(
                new Pattern[] {text},
                new Pattern[] {lives},
                new HudStaticArtFactory.Layout(1, 0, 1, false));

        assertArrayEquals(new Pattern[] {text, lives}, art.patterns());
        assertSame(text, art.patterns()[0]);
        assertSame(lives, art.patterns()[1]);
    }

    @Test
    void sonic1UsesPaletteZeroForLabelsFlashesAndLivesName() {
        HudStaticArt art = Sonic1HudStaticArtFactory.create(patterns(12), patterns(12));

        assertTextFrames(art, 0, 0);
        assertLivesFrame(art.livesFrame(), 12, 0);
    }

    @Test
    void sonic2NativeUsesPaletteOneForLabelsZeroForFlashesAndOneForLivesName() {
        HudStaticArt art = Sonic2HudStaticArtFactory.create(patterns(12), patterns(12), false);

        assertTextFrames(art, 1, 0);
        assertLivesFrame(art.livesFrame(), 12, 1);
    }

    @Test
    void sonic2DonorSelectionChangesOnlyLivesNamePalette() {
        HudStaticArt nativeArt = Sonic2HudStaticArtFactory.create(patterns(12), patterns(12), false);
        HudStaticArt donorArt = Sonic2HudStaticArtFactory.create(patterns(12), patterns(12), true);

        assertEquals(nativeArt.patterns().length, donorArt.patterns().length);
        assertEquals(nativeArt.scoreFrame(), donorArt.scoreFrame());
        assertEquals(nativeArt.debugScoreFrame(), donorArt.debugScoreFrame());
        assertEquals(nativeArt.timeFrame(), donorArt.timeFrame());
        assertEquals(nativeArt.timeFlashFrame(), donorArt.timeFlashFrame());
        assertEquals(nativeArt.ringsFrame(), donorArt.ringsFrame());
        assertEquals(nativeArt.ringsFlashFrame(), donorArt.ringsFlashFrame());
        assertEquals(nativeArt.livesFrame().pieces().get(0), donorArt.livesFrame().pieces().get(0));
        assertNotEquals(nativeArt.livesFrame().pieces().get(1), donorArt.livesFrame().pieces().get(1));
        assertLivesFrame(donorArt.livesFrame(), 12, 0);
    }

    @Test
    void sonic3kUsesPaletteOneAndEmptyFlashFrames() {
        HudStaticArt art = Sonic3kHudStaticArtFactory.create(patterns(12), patterns(12));

        assertScoreFrame(art.scoreFrame(), 1);
        assertDebugScoreFrame(art.debugScoreFrame(), 1);
        assertTimeFrame(art.timeFrame(), 1);
        assertRingsFrame(art.ringsFrame(), 1);
        assertEquals(List.of(), art.timeFlashFrame().pieces());
        assertEquals(List.of(), art.ringsFlashFrame().pieces());
        assertLivesFrame(art.livesFrame(), 12, 1);
    }

    @Test
    void sonic1RequiresBothTextAndLivesPatternsIndependently() {
        Pattern[] onePattern = patterns(1);

        assertNull(Sonic1HudStaticArtFactory.create(null, onePattern));
        assertNull(Sonic1HudStaticArtFactory.create(new Pattern[0], onePattern));
        assertNull(Sonic1HudStaticArtFactory.create(onePattern, null));
        assertNull(Sonic1HudStaticArtFactory.create(onePattern, new Pattern[0]));
    }

    @Test
    void sonic2AndSonic3kNormalizeNullPatternInputsToEmptyBundles() {
        assertArrayEquals(new Pattern[0], Sonic2HudStaticArtFactory.create(null, null, false).patterns());
        assertArrayEquals(new Pattern[0], Sonic3kHudStaticArtFactory.create(null, null).patterns());
    }

    private static void assertTextFrames(HudStaticArt art, int normalPalette, int flashPalette) {
        assertScoreFrame(art.scoreFrame(), normalPalette);
        assertDebugScoreFrame(art.debugScoreFrame(), normalPalette);
        assertTimeFrame(art.timeFrame(), normalPalette);
        assertTimeFrame(art.timeFlashFrame(), flashPalette);
        assertRingsFrame(art.ringsFrame(), normalPalette);
        assertRingsFrame(art.ringsFlashFrame(), flashPalette);
    }

    private static void assertScoreFrame(SpriteMappingFrame frame, int palette) {
        assertEquals(List.of(
                new SpriteMappingPiece(0, 0, 1, 2, 0, false, false, palette),
                new SpriteMappingPiece(8, 0, 1, 2, 2, false, false, palette),
                new SpriteMappingPiece(16, 0, 1, 2, 4, false, false, palette),
                new SpriteMappingPiece(24, 0, 1, 2, 6, false, false, palette),
                new SpriteMappingPiece(32, 0, 1, 2, 22, false, false, palette)),
                frame.pieces());
    }

    private static void assertDebugScoreFrame(SpriteMappingFrame frame, int palette) {
        assertEquals(List.of(
                new SpriteMappingPiece(0, 0, 1, 2, 0, false, false, palette),
                new SpriteMappingPiece(8, 0, 1, 2, 2, false, false, palette),
                new SpriteMappingPiece(16, 0, 1, 2, 4, false, false, palette),
                new SpriteMappingPiece(24, 0, 1, 2, 6, false, false, palette)),
                frame.pieces());
    }

    private static void assertTimeFrame(SpriteMappingFrame frame, int palette) {
        assertEquals(List.of(
                new SpriteMappingPiece(0, 0, 1, 2, 16, false, false, palette),
                new SpriteMappingPiece(8, 0, 1, 2, 10, false, false, palette),
                new SpriteMappingPiece(16, 0, 1, 2, 20, false, false, palette),
                new SpriteMappingPiece(24, 0, 1, 2, 22, false, false, palette)),
                frame.pieces());
    }

    private static void assertRingsFrame(SpriteMappingFrame frame, int palette) {
        assertEquals(List.of(
                new SpriteMappingPiece(0, 0, 1, 2, 6, false, false, palette),
                new SpriteMappingPiece(8, 0, 1, 2, 10, false, false, palette),
                new SpriteMappingPiece(16, 0, 1, 2, 12, false, false, palette),
                new SpriteMappingPiece(24, 0, 1, 2, 14, false, false, palette),
                new SpriteMappingPiece(32, 0, 1, 2, 0, false, false, palette)),
                frame.pieces());
    }

    private static void assertLivesFrame(SpriteMappingFrame frame, int livesBase, int namePalette) {
        assertEquals(List.of(
                new SpriteMappingPiece(0, 0, 2, 2, livesBase, false, false, 0),
                new SpriteMappingPiece(16, 0, 4, 2, livesBase + 4, false, false, namePalette)),
                frame.pieces());
    }

    private static Pattern[] patterns(int count) {
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
        }
        return patterns;
    }
}
