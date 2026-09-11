package com.openggf.level.objects;

import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the common static HUD art bundle from game-owned palette policy.
 */
public final class HudStaticArtFactory {
    private HudStaticArtFactory() {
    }

    public static HudStaticArt create(Pattern[] textPatterns, Pattern[] livesPatterns, Layout layout) {
        Objects.requireNonNull(layout, "layout");
        if (layout.requireNonEmptyTextAndLives()
                && (textPatterns == null || textPatterns.length == 0 || livesPatterns == null || livesPatterns.length == 0)) {
            return null;
        }

        Pattern[] safeTextPatterns = textPatterns != null ? textPatterns : new Pattern[0];
        Pattern[] safeLivesPatterns = livesPatterns != null ? livesPatterns : new Pattern[0];
        Pattern[] combined = combine(safeTextPatterns, safeLivesPatterns);
        int livesBase = safeTextPatterns.length;
        return new HudStaticArt(
                combined,
                scoreFrame(layout.labelPalette()),
                debugScoreFrame(layout.labelPalette()),
                timeFrame(layout.labelPalette()),
                flashFrame(layout.flashLabelPalette(), 8, 5, 10, 11),
                ringsFrame(layout.labelPalette()),
                flashFrame(layout.flashLabelPalette(), 3, 5, 6, 7, 0),
                livesFrame(livesBase, layout.livesNamePalette()));
    }

    public record Layout(
            int labelPalette,
            Integer flashLabelPalette,
            int livesNamePalette,
            boolean requireNonEmptyTextAndLives) {
    }

    private static Pattern[] combine(Pattern[] textPatterns, Pattern[] livesPatterns) {
        Pattern[] combined = new Pattern[textPatterns.length + livesPatterns.length];
        System.arraycopy(textPatterns, 0, combined, 0, textPatterns.length);
        System.arraycopy(livesPatterns, 0, combined, textPatterns.length, livesPatterns.length);
        return combined;
    }

    private static SpriteMappingFrame scoreFrame(int palette) {
        return textRow(palette, 0, 1, 2, 3, 11);
    }

    private static SpriteMappingFrame debugScoreFrame(int palette) {
        return textRow(palette, 0, 1, 2, 3);
    }

    private static SpriteMappingFrame timeFrame(int palette) {
        return textRow(palette, 8, 5, 10, 11);
    }

    private static SpriteMappingFrame ringsFrame(int palette) {
        return textRow(palette, 3, 5, 6, 7, 0);
    }

    private static SpriteMappingFrame flashFrame(Integer palette, int... pairIndices) {
        return palette == null ? new SpriteMappingFrame(List.of()) : textRow(palette, pairIndices);
    }

    private static SpriteMappingFrame textRow(int palette, int... pairIndices) {
        List<SpriteMappingPiece> pieces = new ArrayList<>();
        for (int i = 0; i < pairIndices.length; i++) {
            pieces.add(new SpriteMappingPiece(i * 8, 0, 1, 2, pairIndices[i] * 2, false, false, palette));
        }
        return new SpriteMappingFrame(pieces);
    }

    private static SpriteMappingFrame livesFrame(int livesBase, int namePalette) {
        return new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 2, 2, livesBase, false, false, 0),
                new SpriteMappingPiece(16, 0, 4, 2, livesBase + 4, false, false, namePalette)));
    }
}
