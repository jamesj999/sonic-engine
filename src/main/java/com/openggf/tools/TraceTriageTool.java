package com.openggf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trace Triage Assistant (Option 5 of {@code docs/agent-workflow/support-options.md}).
 *
 * <p>Reads existing trace-replay report artifacts that the engine already
 * produces and prints a short FIRST-DIVERGENCE brief. It performs no ROM run
 * and never mutates engine state; it is a read-only diagnostic over committed
 * report files.
 *
 * <p>Inputs (all under the configured {@code openggf.trace.reports} directory,
 * which defaults to {@code target/trace-reports}, unless overridden):
 * <ul>
 *   <li>a legacy {@code <game>_<zone>_report.json} or a unique session-owned
 *       report matching that logical key (required) — schema produced by
 *       {@link com.openggf.trace.DivergenceReport#toJson()}.</li>
 *   <li>the matching {@code <game>_<zone>_context.txt} (or session-owned
 *       sibling) (optional) — human-readable
 *       side-by-side frame table, scanned for nearby object/diagnostic lines.</li>
 *   <li>{@code aux_state.jsonl} (optional) — newline-delimited aux events,
 *       scanned for nearby object ids / routines / positions.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceTriageTool" "-Dexec.args=s2 mtz1"
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceTriageTool" "-Dexec.args=--report target/trace-reports/s2_mtz1_report.json"
 * </pre>
 *
 * <p>The report-parsing, first-divergence, and subsystem-classification logic
 * lives in pure static methods that operate on a parsed JSON string, so they
 * are unit-testable from a synthetic report fixture with no ROM and no OpenGL.
 */
public final class TraceTriageTool {

    /** Default directory that the engine writes trace reports into. */
    public static final String DEFAULT_REPORT_DIR = "target/trace-reports";

    private TraceTriageTool() {
    }

    // ------------------------------------------------------------------
    // Parsed model
    // ------------------------------------------------------------------

    /** A single divergence group lifted from the report's {@code errors}/{@code warnings} arrays. */
    public record Divergence(
            String field,
            String severity,
            int startFrame,
            int endFrame,
            int frameSpan,
            String expectedAtStart,
            String actualAtStart,
            boolean cascading) {
    }

    /** A bootstrap (frame-0) divergence lifted from the report's {@code bootstrap} array. */
    public record Bootstrap(
            String field,
            String severity,
            String expected,
            String actual,
            String context) {
    }

    /** Immutable view of a parsed trace report. */
    public record TraceReport(
            int errorCount,
            int warningCount,
            int totalFrames,
            String summary,
            List<Bootstrap> bootstrap,
            List<Divergence> errors,
            List<Divergence> warnings) {

        /**
         * The first semantic divergence: the earliest-starting hard error if
         * any exist, otherwise the earliest bootstrap error, otherwise the
         * earliest warning, otherwise {@code null}.
         */
        public Divergence firstDivergence() {
            Divergence best = null;
            for (Divergence d : errors) {
                if (best == null || d.startFrame() < best.startFrame()) {
                    best = d;
                }
            }
            if (best != null) {
                return best;
            }
            for (Divergence d : warnings) {
                if (best == null || d.startFrame() < best.startFrame()) {
                    best = d;
                }
            }
            return best;
        }

        /** The first bootstrap ERROR, if present (frame-0 prelude failure). */
        public Bootstrap firstBootstrapError() {
            for (Bootstrap b : bootstrap) {
                if ("ERROR".equalsIgnoreCase(b.severity())) {
                    return b;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Pure parsing
    // ------------------------------------------------------------------

    /**
     * Parses a trace report JSON string into a {@link TraceReport}. Pure: no
     * file IO, no ROM, no engine state. Uses the project's existing Jackson
     * dependency rather than introducing a new JSON library.
     *
     * @param json the raw report JSON (schema from {@code DivergenceReport.toJson()})
     * @return the parsed model
     * @throws IOException if the JSON is malformed
     */
    public static TraceReport parseReport(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        int errorCount = root.path("error_count").asInt(0);
        int warningCount = root.path("warning_count").asInt(0);
        int totalFrames = root.path("total_frames").asInt(0);
        String summary = root.path("summary").asText("");

        List<Bootstrap> bootstrap = new ArrayList<>();
        for (JsonNode n : root.path("bootstrap")) {
            bootstrap.add(new Bootstrap(
                    n.path("field").asText(""),
                    n.path("severity").asText(""),
                    n.path("expected").asText(""),
                    n.path("actual").asText(""),
                    n.path("context").asText("")));
        }

        List<Divergence> errors = parseGroups(root.path("errors"));
        List<Divergence> warnings = parseGroups(root.path("warnings"));

        return new TraceReport(errorCount, warningCount, totalFrames, summary,
                bootstrap, errors, warnings);
    }

    private static List<Divergence> parseGroups(JsonNode array) {
        List<Divergence> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode n : array) {
            out.add(new Divergence(
                    n.path("field").asText(""),
                    n.path("severity").asText(""),
                    n.path("start_frame").asInt(0),
                    n.path("end_frame").asInt(0),
                    n.path("frame_span").asInt(0),
                    n.path("expected_at_start").asText(""),
                    n.path("actual_at_start").asText(""),
                    n.path("cascading").asBoolean(false)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Pure classification
    // ------------------------------------------------------------------

    /** Likely owning subsystem for a divergent field. */
    public enum Subsystem {
        PLAYER_PHYSICS("player physics"),
        PLAYABLE_ANIMATION("playable animation"),
        OBJECT_SOLID("object solid"),
        TOUCH_RESPONSE("touch response"),
        EVENT("event"),
        SIDEKICK("sidekick"),
        PALETTE("palette"),
        LAYOUT_MUTATION("layout mutation"),
        ART_PLC("art/PLC"),
        TEST_BOOTSTRAP("test bootstrap"),
        UNKNOWN("unknown");

        private final String label;

        Subsystem(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Classifies the likely owning subsystem from a field name and an optional
     * bootstrap hint. Pure string logic, no ROM/engine. Field names follow the
     * recorder schema (see {@code TraceBinder.compareFrame()} and
     * {@code BootstrapDivergence}).
     *
     * @param field         the divergent field label (e.g. {@code "tails_x"}, {@code "angle"})
     * @param isBootstrap   whether the field came from the bootstrap array
     * @return the best-guess subsystem
     */
    public static Subsystem classifySubsystem(String field, boolean isBootstrap) {
        if (field == null) {
            return Subsystem.UNKNOWN;
        }
        String f = field.toLowerCase(Locale.ROOT);

        // Frame-0 prelude / ring-buffer / cpu-view setup is a test-bootstrap concern.
        if (isBootstrap) {
            if (f.startsWith("player_history") || f.contains("history.pos")
                    || f.contains("rng") || f.contains("seed")
                    || f.contains("oscill") || f.contains("frame_counter")
                    || f.contains("game_mode")) {
                return Subsystem.TEST_BOOTSTRAP;
            }
        }

        // Animation fields are independently gated and must be classified
        // before the sidekick prefix catches Tails animation mismatches.
        if (f.endsWith("animation_id") || f.endsWith("mapping_frame")
                || f.endsWith("animation_present")) {
            return Subsystem.PLAYABLE_ANIMATION;
        }

        // Sidekick (Tails / second player) fields.
        if (f.startsWith("tails") || f.contains("sidekick") || f.contains("cpu")) {
            return Subsystem.SIDEKICK;
        }

        // Object slot / routine / subtype fields.
        if (f.startsWith("object_slot") || f.contains("object_slot")
                || f.contains("routine") || f.contains("subtype")) {
            // A slot whose presence/absence diverges is most often a solid /
            // spawn-windowing problem; touch-specific fields handled below.
            if (f.contains("touch") || f.contains("hurt") || f.contains("bounce")) {
                return Subsystem.TOUCH_RESPONSE;
            }
            return Subsystem.OBJECT_SOLID;
        }

        // Touch-response (enemy bounce / hurt / collide).
        if (f.contains("touch") || f.contains("hurt") || f.contains("bounce")
                || f.contains("invuln") || f.contains("rings")
                || f.contains("lives")) {
            // A life count that diverges is a death the engine did or did not
            // take, which sits in the same causal family as ring loss.
            return Subsystem.TOUCH_RESPONSE;
        }

        // Palette.
        if (f.contains("palette") || f.contains("pal_") || f.contains("cram")
                || f.contains("color") || f.contains("colour")) {
            return Subsystem.PALETTE;
        }

        // Layout / tilemap mutation.
        if (f.contains("layout") || f.contains("tilemap") || f.contains("chunk")
                || f.contains("block") || f.contains("map")) {
            return Subsystem.LAYOUT_MUTATION;
        }

        // Art / PLC / pattern fields.
        if (f.contains("plc") || f.contains("art") || f.contains("pattern")
                || f.contains("dplc") || f.contains("vram")) {
            return Subsystem.ART_PLC;
        }

        // Event / camera / zone-act / boss-arena flow.
        if (f.startsWith("camera") || f.contains("camera")
                || f.contains("zone") || f.contains("act")
                || f.contains("boundary") || f.contains("event")
                || f.contains("boss")) {
            return Subsystem.EVENT;
        }

        // Player physics — position / speed / angle / air / rolling / ground mode.
        if (f.equals("x") || f.equals("y")
                || f.equals("x_speed") || f.equals("y_speed") || f.equals("g_speed")
                || f.equals("angle") || f.equals("air") || f.equals("rolling")
                || f.equals("ground_mode")
                || f.endsWith("_speed") || f.endsWith("_angle")) {
            return Subsystem.PLAYER_PHYSICS;
        }

        return Subsystem.UNKNOWN;
    }

    /**
     * Suggests disassembly search terms for a divergent field. Pure string
     * logic. Returned terms are intended for {@code RomOffsetFinder} / disasm
     * label search, not exact addresses.
     */
    public static List<String> suggestDisasmSearchTerms(String field, Subsystem subsystem) {
        Set<String> terms = new LinkedHashSet<>();
        switch (subsystem) {
            case PLAYER_PHYSICS -> {
                terms.add("Sonic_Move");
                terms.add("Obj01");
                terms.add("Player_Subroutines");
                terms.add("ChkRoll");
            }
            case PLAYABLE_ANIMATION -> {
                terms.add("Animate_Sonic");
                terms.add("AnimateSprite");
                terms.add("mapping_frame");
                terms.add("anim_frame");
            }
            case SIDEKICK -> {
                terms.add("Obj02");
                terms.add("Tails_");
                terms.add("Obj_Tails");
                terms.add("CtrlData_");
            }
            case OBJECT_SOLID -> {
                terms.add("SolidObject");
                terms.add("PlatformObject");
                terms.add("MvSonicOnPtfm");
            }
            case TOUCH_RESPONSE -> {
                terms.add("Touch_Response");
                terms.add("Touch_Loop");
                terms.add("HurtSonic");
            }
            case EVENT -> {
                terms.add("Dynamic_Resize");
                terms.add("LevEvents_");
                terms.add("Deform");
            }
            case PALETTE -> {
                terms.add("PalCycle");
                terms.add("Pal_");
                terms.add("AnPal");
            }
            case LAYOUT_MUTATION -> {
                terms.add("AniArt");
                terms.add("Dynamic_");
                terms.add("LoadZoneTiles");
            }
            case ART_PLC -> {
                terms.add("PLC_");
                terms.add("ArtNem_");
                terms.add("ArtKos_");
                terms.add("DPLC_");
            }
            case TEST_BOOTSTRAP -> {
                terms.add("Level_Init");
                terms.add("RandomNumber");
                terms.add("Oscillating_Data");
            }
            default -> {
                // fall through to field-name token below
            }
        }
        if (field != null && !field.isBlank()) {
            terms.add(field);
        }
        return new ArrayList<>(terms);
    }

    /**
     * Suggests focused tests / commands for a divergent field. Pure string
     * logic.
     */
    public static List<String> suggestFocusedTests(Subsystem subsystem) {
        List<String> tests = new ArrayList<>();
        switch (subsystem) {
            case PLAYER_PHYSICS -> {
                tests.add("TestPhysicsProfile");
                tests.add("TestCollisionModel");
                tests.add("TestSpindashGating");
            }
            case PLAYABLE_ANIMATION -> {
                tests.add("TestTraceBinder");
                tests.add("PlayableSpriteAnimation tests");
            }
            case SIDEKICK -> tests.add("TestPhysicsProfile (sidekick profile)");
            case OBJECT_SOLID -> {
                tests.add("TestNoDirectMapMutationsInGameplay");
                tests.add("TestObjectServicesMigrationGuard");
            }
            case TOUCH_RESPONSE -> tests.add("Object touch-response unit test for the involved badnik");
            case EVENT -> tests.add("Zone event-manager test (e.g. *EventsTest)");
            case PALETTE -> tests.add("Sonic3kPaletteCycler / palette-ownership test");
            case LAYOUT_MUTATION -> tests.add("TestNoDirectMapMutationsInGameplay");
            case ART_PLC -> {
                tests.add("TestSonic3kPlcArtRegistry");
                tests.add("TestPatternSpriteRendererCorruptionGuard");
            }
            case TEST_BOOTSTRAP -> tests.add("TestTraceReplayInvariantGuard");
            default -> tests.add("(no specific test guess; run the failing *TraceReplay)");
        }
        tests.add("TestTraceReplayInvariantGuard (comparison-only guard)");
        return tests;
    }

    /**
     * Decides whether {@code docs/status/trace-frontier-log.md} likely needs an
     * update. The frontier log must be updated when a trace frontier moves,
     * passes, regresses, or is used to select the next target. If the report
     * shows any errors, the frontier is currently failing and the log should
     * record (or already record) the current first-error frame/field.
     */
    public static boolean frontierLogLikelyNeedsUpdate(TraceReport report) {
        return report.errorCount() > 0 || report.firstBootstrapError() != null;
    }

    // ------------------------------------------------------------------
    // Pure brief rendering
    // ------------------------------------------------------------------

    /**
     * Renders the first-divergence brief from a parsed report plus optional
     * context-text and aux-event excerpts. Pure: takes already-loaded strings,
     * does no IO.
     *
     * @param game            game id (e.g. {@code "s2"}); may be empty/unknown
     * @param zone            zone id (e.g. {@code "mtz1"}); may be empty/unknown
     * @param report          parsed report model
     * @param contextExcerpt  nearby lines from {@code _context.txt}, or empty
     * @param auxExcerpt      nearby lines from {@code aux_state.jsonl}, or empty
     * @return the multi-line brief
     */
    public static String renderBrief(String game,
                                     String zone,
                                     TraceReport report,
                                     String contextExcerpt,
                                     String auxExcerpt) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Trace Triage Brief ===\n");
        sb.append("Game/Zone: ").append(blankToDash(game)).append(" / ")
                .append(blankToDash(zone)).append("\n");
        sb.append("Totals: ").append(report.errorCount()).append(" errors, ")
                .append(report.warningCount()).append(" warnings, ")
                .append(report.totalFrames()).append(" frames compared\n");
        sb.append("Report summary: ").append(blankToDash(report.summary())).append("\n");
        sb.append("\n");

        Bootstrap bootErr = report.firstBootstrapError();
        if (bootErr != null) {
            Subsystem bootSub = classifySubsystem(bootErr.field(), true);
            sb.append("BOOTSTRAP (frame 0) ERROR present:\n");
            sb.append("  field   : ").append(bootErr.field()).append("\n");
            sb.append("  ROM     : ").append(bootErr.expected()).append("\n");
            sb.append("  engine  : ").append(bootErr.actual()).append("\n");
            sb.append("  context : ").append(bootErr.context()).append("\n");
            sb.append("  likely owner: ").append(bootSub.label()).append("\n");
            sb.append("  NOTE: a frame-0 prelude failure usually means the engine did not reach\n");
            sb.append("        the correct natural start state. Fix the simulated prelude --\n");
            sb.append("        do NOT hydrate engine state from the trace to paper over it.\n");
            sb.append("\n");
        }

        Divergence first = report.firstDivergence();
        if (first == null) {
            sb.append("FIRST DIVERGENCE: none. All compared frames match.\n");
            sb.append("\n");
            sb.append(comparisonOnlyReminder());
            sb.append("\n");
            sb.append("Frontier log update likely needed: ")
                    .append(frontierLogLikelyNeedsUpdate(report) ? "YES" : "no")
                    .append("  (file: docs/status/trace-frontier-log.md)\n");
            return sb.toString();
        }

        Subsystem sub = classifySubsystem(first.field(), false);
        sb.append("FIRST DIVERGENCE\n");
        sb.append("  frame    : ").append(first.startFrame())
                .append(" (span ").append(first.frameSpan())
                .append(", ends ").append(first.endFrame()).append(")\n");
        sb.append("  field    : ").append(first.field())
                .append("  [").append(first.severity()).append("]\n");
        sb.append("  ROM      : ").append(first.expectedAtStart()).append("\n");
        sb.append("  engine   : ").append(first.actualAtStart()).append("\n");
        sb.append("  cascading: ").append(first.cascading()).append("\n");
        sb.append("  likely owning subsystem: ").append(sub.label()).append("\n");
        sb.append("\n");

        sb.append("Suggested disassembly search terms (RomOffsetFinder / disasm label search):\n");
        for (String t : suggestDisasmSearchTerms(first.field(), sub)) {
            sb.append("  - ").append(t).append("\n");
        }
        sb.append("\n");

        sb.append("Suggested focused tests:\n");
        for (String t : suggestFocusedTests(sub)) {
            sb.append("  - ").append(t).append("\n");
        }
        sb.append("\n");

        if (contextExcerpt != null && !contextExcerpt.isBlank()) {
            sb.append("Nearby context (").append("_context.txt").append("):\n");
            sb.append(indent(contextExcerpt));
            sb.append("\n");
        }
        if (auxExcerpt != null && !auxExcerpt.isBlank()) {
            sb.append("Nearby aux events (object ids/routines/subtypes/positions):\n");
            sb.append(indent(auxExcerpt));
            sb.append("\n");
        }

        sb.append(comparisonOnlyReminder());
        sb.append("\n");
        sb.append("Frontier log update likely needed: ")
                .append(frontierLogLikelyNeedsUpdate(report) ? "YES" : "no")
                .append("  (file: docs/status/trace-frontier-log.md)\n");

        return sb.toString();
    }

    private static String comparisonOnlyReminder() {
        return "REMINDER: Trace data is COMPARISON-ONLY. It is read-only diagnostic input.\n"
                + "Never hydrate, sync, or snap engine state from the trace in committed code.\n"
                + "The engine must reproduce ROM behaviour natively from controller input.\n"
                + "(Guard: TestTraceReplayInvariantGuard.)\n";
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    private static String indent(String block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            sb.append("  | ").append(line).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Context / aux excerpt extraction (pure)
    // ------------------------------------------------------------------

    /**
     * Extracts lines near a frame from a {@code _context.txt} body. Returns at
     * most {@code maxLines} lines whose leading frame number falls within
     * {@code radius} of {@code frame}, plus any ROM:/ENG: diagnostic lines that
     * immediately follow them. Pure: operates on the already-loaded text.
     */
    public static String extractContextNearFrame(String contextText, int frame, int radius, int maxLines) {
        if (contextText == null || contextText.isBlank()) {
            return "";
        }
        String[] lines = contextText.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean keepingDiag = false;
        for (String line : lines) {
            if (out.size() >= maxLines) {
                break;
            }
            int lf = leadingFrame(line);
            if (lf >= 0) {
                keepingDiag = Math.abs(lf - frame) <= radius;
                if (keepingDiag) {
                    out.add(line.stripTrailing());
                }
            } else if (keepingDiag) {
                String trimmed = line.strip();
                if (trimmed.startsWith("ROM:") || trimmed.startsWith("ENG:")) {
                    out.add("    " + trimmed);
                }
            }
        }
        return String.join("\n", out);
    }

    private static int leadingFrame(String line) {
        if (line == null || line.isEmpty() || !Character.isDigit(line.charAt(0))) {
            return -1;
        }
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        try {
            return Integer.parseInt(line.substring(0, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Extracts aux JSONL lines whose {@code "frame":N} value is within
     * {@code radius} of {@code frame}. Returns at most {@code maxLines} lines.
     * Pure string scan — does not fully parse each line (one object per line).
     */
    public static String extractAuxNearFrame(String auxJsonl, int frame, int radius, int maxLines) {
        if (auxJsonl == null || auxJsonl.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : auxJsonl.split("\n", -1)) {
            if (out.size() >= maxLines) {
                break;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int auxFrame = extractJsonIntField(trimmed, "frame");
            if (auxFrame >= 0 && Math.abs(auxFrame - frame) <= radius) {
                out.add(trimmed);
            }
        }
        return String.join("\n", out);
    }

    /** Reads a top-level integer JSON field by name from one line; -1 if absent. */
    static int extractJsonIntField(String jsonLine, String field) {
        String key = "\"" + field + "\"";
        int idx = jsonLine.indexOf(key);
        if (idx < 0) {
            return -1;
        }
        int colon = jsonLine.indexOf(':', idx + key.length());
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < jsonLine.length() && (jsonLine.charAt(i) == ' ' || jsonLine.charAt(i) == '"')) {
            i++;
        }
        int start = i;
        if (i < jsonLine.length() && (jsonLine.charAt(i) == '-' || jsonLine.charAt(i) == '+')) {
            i++;
        }
        while (i < jsonLine.length() && Character.isDigit(jsonLine.charAt(i))) {
            i++;
        }
        if (i == start) {
            return -1;
        }
        try {
            return Integer.parseInt(jsonLine.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // CLI entry point (IO lives here; logic above is pure)
    // ------------------------------------------------------------------

    static String configuredReportDirectory() {
        return System.getProperty("openggf.trace.reports", DEFAULT_REPORT_DIR);
    }

    static Path defaultReportPath(String reportDir, String game, String zone) {
        return Path.of(reportDir, game + "_" + zone + "_report.json");
    }

    static Path defaultContextPath(String reportDir, String game, String zone) {
        return Path.of(reportDir, game + "_" + zone + "_context.txt");
    }

    static Path contextPathForReport(Path reportPath) {
        String filename = reportPath.getFileName().toString();
        String stem = filename.endsWith(".json")
                ? filename.substring(0, filename.length() - ".json".length())
                : filename;
        if (stem.endsWith("_report")) {
            stem = stem.substring(0, stem.length() - "_report".length());
        }
        return reportPath.resolveSibling(stem + "_context.txt");
    }

    static Path resolveDefaultReportPath(String reportDir, String game, String zone)
            throws IOException {
        Path legacy = defaultReportPath(reportDir, game, zone);
        Path sessionReportRoot = Path.of(reportDir);
        if (!Files.isDirectory(sessionReportRoot)) {
            return legacy;
        }
        String prefix = game + "_" + zone + "-";
        List<Path> candidates;
        try (var paths = Files.walk(sessionReportRoot)) {
            candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getParent().equals(sessionReportRoot))
                    .filter(path -> !path.equals(legacy))
                    .filter(path -> path.getFileName().toString().startsWith(prefix)
                            || path.getFileName().toString().startsWith(
                                    game + "_" + zone + "_"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".owner.json"))
                    .sorted()
                    .toList();
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.size() > 1) {
            throw new IOException("multiple session reports match " + game + " " + zone
                    + "; rerun with --report <path>");
        }
        return legacy;
    }

    static Path resolveReportPath(String explicitPath, String reportDir,
            String game, String zone) {
        return explicitPath != null
                ? Path.of(explicitPath)
                : defaultReportPath(reportDir, game, zone);
    }

    public static void main(String[] args) {
        String game = null;
        String zone = null;
        String reportPath = null;
        String contextPath = null;
        String auxPath = null;
        String reportDir = configuredReportDirectory();

        List<String> positionals = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--report" -> reportPath = (i + 1 < args.length) ? args[++i] : null;
                case "--context" -> contextPath = (i + 1 < args.length) ? args[++i] : null;
                case "--aux" -> auxPath = (i + 1 < args.length) ? args[++i] : null;
                case "--dir" -> reportDir = (i + 1 < args.length) ? args[++i] : reportDir;
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown flag: " + args[i]);
                    } else {
                        positionals.add(args[i]);
                    }
                }
            }
        }

        if (positionals.size() >= 1) {
            game = positionals.get(0).toLowerCase(Locale.ROOT);
        }
        if (positionals.size() >= 2) {
            zone = positionals.get(1).toLowerCase(Locale.ROOT);
        }

        if (reportPath == null) {
            if (game == null || zone == null) {
                System.err.println("Usage: TraceTriageTool <game> <zone>");
                System.err.println("   or: TraceTriageTool --report <path> [--context <path>] [--aux <path>]");
                System.err.println("Reads " + reportDir + "/<game>_<zone>_report.json by default.");
                System.exit(2);
                return;
            }
            try {
                reportPath = resolveDefaultReportPath(reportDir, game, zone).toString();
            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(2);
                return;
            }
            if (contextPath == null) {
                contextPath = contextPathForReport(Path.of(reportPath)).toString();
            }
        }

        Path reportFile = Path.of(reportPath);
        if (!Files.exists(reportFile)) {
            System.err.println("Report not found: " + reportFile.toAbsolutePath());
            System.err.println("Run the relevant *TraceReplay test first to generate artifacts under "
                    + reportDir + "/.");
            System.exit(1);
            return;
        }

        try {
            String json = Files.readString(reportFile, StandardCharsets.UTF_8);
            TraceReport report = parseReport(json);

            Divergence first = report.firstDivergence();
            int focusFrame = first != null ? first.startFrame() : 0;

            String contextExcerpt = "";
            if (contextPath != null && Files.exists(Path.of(contextPath))) {
                String contextText = Files.readString(Path.of(contextPath), StandardCharsets.UTF_8);
                contextExcerpt = extractContextNearFrame(contextText, focusFrame, 2, 12);
            }

            String auxExcerpt = "";
            if (auxPath != null && Files.exists(Path.of(auxPath))) {
                String auxText = Files.readString(Path.of(auxPath), StandardCharsets.UTF_8);
                auxExcerpt = extractAuxNearFrame(auxText, focusFrame, 3, 12);
            }

            System.out.print(renderBrief(game, zone, report, contextExcerpt, auxExcerpt));
        } catch (IOException e) {
            System.err.println("Failed to read/parse report: " + e.getMessage());
            System.exit(1);
        }
    }
}
