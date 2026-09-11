package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLegalDisclaimerTextWrap {

    /** Fixed 6 px per char measure, matches a simple monospaced fixture. */
    private static final ToIntFunction<String> SIX_PER_CHAR =
            s -> s.length() * 6;

    @Test
    void singleShortLineIsReturnedAsIs() {
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "short line", SIX_PER_CHAR, 100);
        assertEquals(List.of("short line"), out);
    }

    @Test
    void exactlyFittingLineIsNotSplit() {
        // "abcdef ghij" = 11 chars = 66 px, fits in 66 px
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "abcdef ghij", SIX_PER_CHAR, 66);
        assertEquals(List.of("abcdef ghij"), out);
    }

    @Test
    void overflowingLineSplitsAtWordBoundary() {
        // "abcdef ghij" = 66 px, max = 60 → split into "abcdef" + "ghij"
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "abcdef ghij", SIX_PER_CHAR, 60);
        assertEquals(List.of("abcdef", "ghij"), out);
    }

    @Test
    void multiWordParagraphWrapsAcrossSeveralLines() {
        // 6 chars per word, 6 px per char → 36 px per word, plus 6 px space
        // Max 84 px = up to two 6-char words per line.
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "alphas bravos charlies deltas echoes", SIX_PER_CHAR, 84);
        assertEquals(List.of(
                "alphas bravos",
                "charlies",
                "deltas echoes"
        ), out);
    }

    @Test
    void emptyParagraphReturnsSingleEmptyLine() {
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "", SIX_PER_CHAR, 100);
        assertEquals(List.of(""), out);
    }

    @Test
    void wordLongerThanMaxStillEmittedAsOwnLine() {
        // "supercalifragilistic" alone exceeds width; helper must not loop.
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "supercalifragilistic foo", SIX_PER_CHAR, 60);
        assertEquals(List.of("supercalifragilistic", "foo"), out);
    }

    /**
     * Mirrors PixelFont.measureWidth(s, scale) — 9 px/char × scale, rounded.
     */
    private static int measureLike(String s, float scale) {
        return Math.round(s.length() * 9f * scale);
    }

    @Test
    void bodyParagraphsFitBetweenStartYAndPromptY() {
        ToIntFunction<String> measure =
                s -> measureLike(s, LegalDisclaimerScreen.BODY_SCALE);

        int y = LegalDisclaimerScreen.BODY_START_Y;
        for (String paragraph : LegalDisclaimerScreen.BODY_PARAGRAPHS) {
            if (paragraph.isEmpty()) {
                y += LegalDisclaimerScreen.BODY_PARAGRAPH_GAP;
                continue;
            }
            List<String> lines = LegalDisclaimerScreen.wrapParagraph(
                    paragraph, measure, LegalDisclaimerScreen.BODY_MAX_WIDTH);
            y += lines.size() * LegalDisclaimerScreen.BODY_LINE_HEIGHT;
        }
        assertTrue(y <= LegalDisclaimerScreen.PROMPT_Y,
                "Body wraps to Y=" + y + " which overlaps the dismiss prompt at Y="
                        + LegalDisclaimerScreen.PROMPT_Y);
    }
}
