package com.openggf.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zone Analysis Spec Normalizer (Option 6 in {@code docs/agent-workflow/support-options.md}).
 *
 * <p>Reads a loose / partial S3K zone-analysis Markdown spec (as produced by the
 * {@code s3k-zone-analysis} skill, see {@code .claude/skills/s3k-zone-analysis/SKILL.md})
 * and rewrites it into the STABLE canonical section layout so follow-on implementation
 * agents can consume one feature category without repeating the full disassembly pass.
 *
 * <p>Canonical section order (from Option 6's acceptance criteria):
 * <ol>
 *   <li>Zone Metadata And Zone Set</li>
 *   <li>Events / Dynamic Resize</li>
 *   <li>Parallax / Deform</li>
 *   <li>Animated Tiles / AniPLC</li>
 *   <li>Palette Cycling / AnPal</li>
 *   <li>Palette Mutations</li>
 *   <li>Notable Objects</li>
 *   <li>Route Blockers</li>
 *   <li>Character-Specific Paths</li>
 *   <li>Water / Screen Shake / Boss Gates / Act Transitions / Cutscene State</li>
 *   <li>Confidence Per Feature</li>
 *   <li>Owning Runtime Framework Per Feature</li>
 *   <li>Required Tests And Validation Commands</li>
 * </ol>
 *
 * <p><b>Palette Cycling and Palette Mutations are deliberately distinct sections.</b>
 * Cycling ({@code AnPal_*}) is timer-driven and lives in {@code Sonic3kPaletteCycler};
 * mutation is a one-shot camera-triggered write in a {@code _Resize} routine and lives in
 * the zone event handler. Conflating them is a known analysis mistake.
 *
 * <p>Any canonical section with no matching heading in the input is emitted with an explicit
 * {@code (not analyzed)} placeholder so gaps are visible rather than silently dropped.
 *
 * <p>The normalization is a pure {@link #normalize(String)} (String -&gt; String) function with
 * no ROM, filesystem, or OpenGL dependencies, so it is directly unit-testable.
 *
 * <p>Usage:
 * <pre>
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.ZoneSpecNormalizerTool" "-Dexec.args=docs/architecture/research/s3k-zones/hcz-analysis.md"
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.ZoneSpecNormalizerTool" "-Dexec.args=docs/architecture/research/s3k-zones/hcz-analysis.md -o docs/architecture/research/s3k-zones/hcz-normalized.md"
 * </pre>
 */
public final class ZoneSpecNormalizerTool {

    /** Placeholder body emitted for any canonical section absent from the input. */
    public static final String NOT_ANALYZED = "(not analyzed)";

    /**
     * Canonical sections in stable order. Each entry pairs the emitted heading title with the
     * lowercase keyword fragments that, when present in a loose input heading, route that
     * heading's body into this section. Order of declaration is the emitted output order.
     */
    private enum Section {
        ZONE_METADATA("Zone Metadata And Zone Set",
                "zone metadata", "zone set", "metadata", "summary", "zone index", "zone overview"),
        EVENTS("Events / Dynamic Resize",
                "event", "dynamic resize", "dynamic_resize", "_resize", "resize", "eventroutine"),
        PARALLAX("Parallax / Deform",
                "parallax", "deform", "scroll", "hscroll", "band"),
        ANIMATED_TILES("Animated Tiles / AniPLC",
                "animated tile", "aniplc", "animated tiles", "tile animation"),
        PALETTE_CYCLING("Palette Cycling / AnPal",
                "palette cycling", "palette cycle", "anpal", "color cycling", "colour cycling"),
        // Palette mutations MUST stay separate from palette cycling.
        PALETTE_MUTATIONS("Palette Mutations",
                "palette mutation", "palette mutations", "palette write", "one-shot palette",
                "event palette"),
        NOTABLE_OBJECTS("Notable Objects",
                "notable object", "objects", "object inventory", "badnik"),
        ROUTE_BLOCKERS("Route Blockers",
                "route blocker", "blocker", "traversal blocker", "progression blocker"),
        CHARACTER_PATHS("Character-Specific Paths",
                "character-specific", "character specific", "character path", "knuckles path",
                "player_mode", "character branching", "knuckles difference"),
        CROSS_CUTTING("Water / Screen Shake / Boss Gates / Act Transitions / Cutscene State",
                "cross-cutting", "cross cutting", "water", "screen shake", "boss gate", "boss",
                "act transition", "cutscene", "screen shake/boss"),
        CONFIDENCE("Confidence Per Feature",
                "confidence per feature", "confidence", "known risk", "risk"),
        FRAMEWORK("Owning Runtime Framework Per Feature",
                "owning runtime framework", "framework routing", "framework", "runtime owner",
                "owner per feature"),
        TESTS("Required Tests And Validation Commands",
                "required tests", "validation command", "tests and validation", "tests",
                "validation", "test commands");

        final String title;
        final String[] keywords;

        Section(String title, String... keywords) {
            this.title = title;
            this.keywords = keywords;
        }
    }

    private ZoneSpecNormalizerTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return;
        }

        Path input = Path.of(args[0]);
        Path output = null;
        for (int i = 1; i < args.length - 1; i++) {
            if ("-o".equals(args[i]) || "--output".equals(args[i])) {
                output = Path.of(args[i + 1]);
            }
        }

        String raw;
        try {
            raw = Files.readString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ERROR: could not read spec file: " + input + " (" + e.getMessage() + ")");
            return;
        }

        String normalized = normalize(raw);

        if (output != null) {
            try {
                Files.writeString(output, normalized, StandardCharsets.UTF_8);
                System.out.println("Normalized zone spec written to: " + output.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("ERROR: could not write output file: " + output + " (" + e.getMessage() + ")");
            }
        } else {
            System.out.print(normalized);
        }
    }

    /**
     * Pure normalization: loose Markdown spec -&gt; canonical ordered Markdown spec.
     *
     * <p>Parses every top-level ({@code #}) and second-level ({@code ##}) heading in the input,
     * routes each heading's body text to the best-matching canonical {@link Section} by keyword,
     * and re-emits all sections in canonical order. Sections that received no input content are
     * emitted with the {@link #NOT_ANALYZED} placeholder. Content that matched no section is kept
     * under an "Unclassified Notes" appendix so nothing is silently lost.
     *
     * @param raw the raw Markdown spec text (may be empty, partial, or out of order)
     * @return the normalized Markdown spec text
     */
    public static String normalize(String raw) {
        String title = extractTitle(raw);

        // Accumulate routed body content per canonical section (preserve insertion order).
        Map<Section, StringBuilder> bodies = new LinkedHashMap<>();
        for (Section s : Section.values()) {
            bodies.put(s, new StringBuilder());
        }
        StringBuilder unclassified = new StringBuilder();

        List<Heading> headings = parseHeadings(raw == null ? "" : raw);
        for (Heading h : headings) {
            Section target = routeHeading(h.text);
            if (target == null) {
                if (!h.body.isBlank()) {
                    unclassified.append("### ").append(h.text.trim()).append('\n');
                    unclassified.append(h.body.strip()).append("\n\n");
                }
                continue;
            }
            StringBuilder sb = bodies.get(target);
            if (!h.body.isBlank()) {
                // Demote the original heading to ### so the canonical ## headings stay authoritative.
                sb.append("### ").append(h.text.trim()).append('\n');
                sb.append(h.body.strip()).append("\n\n");
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        out.append("<!-- Normalized by com.openggf.tools.ZoneSpecNormalizerTool. ")
                .append("Sections marked \"").append(NOT_ANALYZED)
                .append("\" still need a disassembly pass. -->\n\n");

        for (Section s : Section.values()) {
            out.append("## ").append(s.title).append("\n\n");
            String body = bodies.get(s).toString().strip();
            if (body.isEmpty()) {
                out.append(NOT_ANALYZED).append("\n\n");
            } else {
                out.append(body).append("\n\n");
            }
        }

        String extra = unclassified.toString().strip();
        if (!extra.isEmpty()) {
            out.append("## Unclassified Notes\n\n");
            out.append(extra).append("\n\n");
        }

        // Single trailing newline.
        String result = out.toString().stripTrailing();
        return result + "\n";
    }

    private static String extractTitle(String raw) {
        if (raw == null) {
            return "S3K Zone Analysis (normalized)";
        }
        for (String line : raw.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("# ") && !t.startsWith("##")) {
                String title = t.substring(2).strip();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        }
        return "S3K Zone Analysis (normalized)";
    }

    /** Route a loose heading title to the first canonical section whose keyword it contains. */
    private static Section routeHeading(String headingText) {
        String h = headingText.toLowerCase(Locale.ROOT).strip();
        Section best = null;
        int bestLen = -1;
        // Prefer the longest matching keyword so "palette mutations" wins over "palette".
        for (Section s : Section.values()) {
            for (String kw : s.keywords) {
                if (h.contains(kw) && kw.length() > bestLen) {
                    best = s;
                    bestLen = kw.length();
                }
            }
        }
        return best;
    }

    /**
     * Parse {@code #}/{@code ##}/{@code ###} headings and their bodies. The top-level title
     * heading is skipped (handled by {@link #extractTitle}); all other headings become routable
     * units. Body text runs until the next heading at the same-or-shallower depth.
     */
    private static List<Heading> parseHeadings(String raw) {
        List<Heading> result = new ArrayList<>();
        String[] lines = raw.split("\n", -1);

        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        boolean skippedFirstTopTitle = false;

        for (String line : lines) {
            String trimmed = line.stripLeading();
            int level = headingLevel(trimmed);
            if (level == 1 && !skippedFirstTopTitle) {
                // The document title (first '# '): start fresh, don't emit as a section.
                skippedFirstTopTitle = true;
                flush(result, currentTitle, body);
                currentTitle = null;
                body.setLength(0);
                continue;
            }
            if (level == 1 || level == 2) {
                flush(result, currentTitle, body);
                currentTitle = trimmed.substring(level).strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(result, currentTitle, body);
        return result;
    }

    private static void flush(List<Heading> result, String title, StringBuilder body) {
        if (title != null) {
            result.add(new Heading(title, body.toString()));
        }
    }

    /** Returns the heading depth (1, 2, 3...) for a line starting with '#', else 0. */
    private static int headingLevel(String trimmed) {
        if (!trimmed.startsWith("#")) {
            return 0;
        }
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '#') {
            i++;
        }
        // Require a space after the hashes to count as a heading.
        if (i < trimmed.length() && trimmed.charAt(i) == ' ') {
            return i;
        }
        return 0;
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg);
    }

    private static void printUsage() {
        System.out.println("Zone Analysis Spec Normalizer (Option 6)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ZoneSpecNormalizerTool <spec.md> [-o|--output <out.md>]");
        System.out.println();
        System.out.println("Reads a loose/partial S3K zone-analysis Markdown spec and rewrites it into the");
        System.out.println("canonical section layout. Missing sections are emitted as \"" + NOT_ANALYZED + "\".");
        System.out.println("Palette cycling and palette mutations are kept as separate sections.");
        System.out.println("Without -o the normalized spec is printed to stdout.");
    }

    /** A loose input heading and the body text beneath it. */
    private record Heading(String text, String body) {
    }
}
