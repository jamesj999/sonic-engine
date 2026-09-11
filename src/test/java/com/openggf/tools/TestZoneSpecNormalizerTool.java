package com.openggf.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ZoneSpecNormalizerTool}. JUnit 5 / Jupiter only. No ROM, filesystem, or
 * OpenGL dependency — exercises the pure {@code normalize(String)} function directly.
 */
class TestZoneSpecNormalizerTool {

    /** Canonical section headings in the exact order the normalizer must emit them. */
    private static final String[] CANONICAL_ORDER = {
            "## Zone Metadata And Zone Set",
            "## Events / Dynamic Resize",
            "## Parallax / Deform",
            "## Animated Tiles / AniPLC",
            "## Palette Cycling / AnPal",
            "## Palette Mutations",
            "## Notable Objects",
            "## Route Blockers",
            "## Character-Specific Paths",
            "## Water / Screen Shake / Boss Gates / Act Transitions / Cutscene State",
            "## Confidence Per Feature",
            "## Owning Runtime Framework Per Feature",
            "## Required Tests And Validation Commands",
    };

    private static int indexOfHeading(String out, String heading) {
        // Match the heading as a line, not as a substring of another heading.
        int idx = out.indexOf("\n" + heading + "\n");
        if (idx >= 0) {
            return idx;
        }
        // Allow a heading at the very start (unlikely given the doc title, but safe).
        return out.startsWith(heading + "\n") ? 0 : -1;
    }

    @Test
    void allCanonicalSectionsPresentInOrderForMessyPartialSpec() {
        String messy = """
                # S3K HCZ Zone Analysis

                ## Summary
                - Zone: Hydrocity (HCZ)
                - Zone Set: S3KL

                ## Notable Objects
                | $0A | Spike | hazard | Yes |

                ## Events
                Act 1 HCZ1_Resize locks the camera at camera X >= $2A00.

                ## Animated tiles / AniPLC
                Waterfall script, dest tile $300.
                """;

        String out = ZoneSpecNormalizerTool.normalize(messy);

        // Every canonical section appears, in canonical order.
        int prev = -1;
        for (String heading : CANONICAL_ORDER) {
            int at = indexOfHeading(out, heading);
            assertTrue(at >= 0, "missing canonical section: " + heading);
            assertTrue(at > prev, "section out of order: " + heading);
            prev = at;
        }
    }

    @Test
    void paletteCyclingAndPaletteMutationAreSeparateSections() {
        String spec = """
                # S3K MGZ Zone Analysis

                ## Palette Cycling / AnPal
                Channel 0: AnPal_PalMGZ_0, counter step 2, limit $08, line 2.

                ## Palette Mutations
                MGZ1_Resize writes line 1 color 5 once when camera X >= $1800.
                """;

        String out = ZoneSpecNormalizerTool.normalize(spec);

        int cyclingAt = indexOfHeading(out, "## Palette Cycling / AnPal");
        int mutationAt = indexOfHeading(out, "## Palette Mutations");

        assertAll(
                () -> assertTrue(cyclingAt >= 0, "palette cycling section missing"),
                () -> assertTrue(mutationAt >= 0, "palette mutations section missing"),
                () -> assertTrue(cyclingAt != mutationAt, "cycling and mutation collapsed into one section"),
                () -> assertTrue(cyclingAt < mutationAt, "cycling must precede mutation"),
                // Content routed to the correct distinct section.
                () -> assertTrue(out.contains("AnPal_PalMGZ_0"), "cycling content lost"),
                () -> assertTrue(out.contains("writes line 1 color 5 once"), "mutation content lost"));

        // The cycling channel data must NOT have leaked into the mutation section, and vice versa.
        String mutationBody = out.substring(mutationAt);
        assertTrue(mutationBody.contains("writes line 1 color 5 once"),
                "mutation content not under mutation section");
        String cyclingBody = out.substring(cyclingAt, mutationAt);
        assertTrue(cyclingBody.contains("AnPal_PalMGZ_0"),
                "cycling content not under cycling section");
        assertTrue(!cyclingBody.contains("writes line 1 color 5 once"),
                "mutation content leaked into cycling section");
    }

    @Test
    void missingSectionsGetNotAnalyzedPlaceholders() {
        // Only metadata supplied; everything else must become "(not analyzed)".
        String spec = """
                # S3K LBZ Zone Analysis

                ## Zone Metadata And Zone Set
                - Zone: Launch Base (LBZ)
                - Zone Set: S3KL
                """;

        String out = ZoneSpecNormalizerTool.normalize(spec);

        // Metadata section has real content (not the placeholder).
        int metaAt = indexOfHeading(out, "## Zone Metadata And Zone Set");
        int nextAt = indexOfHeading(out, "## Events / Dynamic Resize");
        String metaBody = out.substring(metaAt, nextAt);
        assertTrue(metaBody.contains("Launch Base"), "metadata content lost");
        assertTrue(!metaBody.contains(ZoneSpecNormalizerTool.NOT_ANALYZED),
                "populated metadata should not carry placeholder");

        // Several unprovided sections must carry the explicit placeholder.
        for (String heading : new String[]{
                "## Events / Dynamic Resize",
                "## Parallax / Deform",
                "## Route Blockers",
                "## Required Tests And Validation Commands"}) {
            int at = indexOfHeading(out, heading);
            assertTrue(at >= 0, "missing section: " + heading);
            // Body immediately after the heading should be the placeholder.
            String afterHeading = out.substring(at + ("\n" + heading + "\n").length());
            assertTrue(afterHeading.stripLeading().startsWith(ZoneSpecNormalizerTool.NOT_ANALYZED),
                    "expected placeholder under " + heading);
        }
    }

    @Test
    void emptyInputProducesFullCanonicalSkeletonOfPlaceholders() {
        String out = ZoneSpecNormalizerTool.normalize("");

        for (String heading : CANONICAL_ORDER) {
            assertTrue(indexOfHeading(out, heading) >= 0, "missing canonical section: " + heading);
        }
        // 13 canonical sections, each a standalone placeholder line. Count lines that are
        // exactly the placeholder so the explanatory header comment (which mentions the
        // placeholder text) is not counted.
        int placeholders = 0;
        for (String line : out.split("\n", -1)) {
            if (line.strip().equals(ZoneSpecNormalizerTool.NOT_ANALYZED)) {
                placeholders++;
            }
        }
        assertEquals(CANONICAL_ORDER.length, placeholders,
                "every canonical section in an empty spec should be a placeholder");
        assertTrue(out.endsWith("\n"), "output must end with a trailing newline");
    }

    @Test
    void unclassifiedContentIsPreservedNotDropped() {
        String spec = """
                # S3K AIZ Zone Analysis

                ## Some Bizarre Heading That Matches Nothing
                Important note that must not be lost.
                """;

        String out = ZoneSpecNormalizerTool.normalize(spec);

        assertTrue(out.contains("## Unclassified Notes"), "unclassified appendix missing");
        assertTrue(out.contains("Important note that must not be lost."),
                "unclassified content was dropped");
    }

    @Test
    void looseHeadingSynonymsRouteToCanonicalSections() {
        // Verify keyword routing for headings that don't match the canonical titles verbatim.
        String spec = """
                # S3K ICZ Zone Analysis

                ## Deform
                ICZ_Deform has 3 bands.

                ## AnPal
                Channel 0 cycles the snow palette.

                ## Knuckles path
                Knuckles enters via the lower route.

                ## Validation
                Run TestSonic3kLevelLoading.
                """;

        String out = ZoneSpecNormalizerTool.normalize(spec);

        int parallaxAt = indexOfHeading(out, "## Parallax / Deform");
        int cyclingAt = indexOfHeading(out, "## Palette Cycling / AnPal");
        int charAt = indexOfHeading(out, "## Character-Specific Paths");
        int testsAt = indexOfHeading(out, "## Required Tests And Validation Commands");

        String parallaxBody = out.substring(parallaxAt, cyclingAt);
        assertTrue(parallaxBody.contains("ICZ_Deform has 3 bands"), "deform content misrouted");

        String charBody = out.substring(charAt);
        assertTrue(charBody.contains("Knuckles enters via the lower route"),
                "character path content misrouted");

        String testsBody = out.substring(testsAt);
        assertTrue(testsBody.contains("TestSonic3kLevelLoading"), "validation content misrouted");
    }
}
