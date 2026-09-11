package com.openggf.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DivergenceReport {

    private final List<FrameComparison> allComparisons;
    private final List<DivergenceGroup> errors;
    private final List<DivergenceGroup> warnings;
    private final List<BootstrapDivergence> bootstrapDivergences;
    private final TraceData traceData;

    public DivergenceReport(List<FrameComparison> comparisons) {
        this(comparisons, null, List.of());
    }

    public DivergenceReport(List<FrameComparison> comparisons, TraceData traceData) {
        this(comparisons, traceData, List.of());
    }

    /**
     * Variant that includes bootstrap (frame-0) divergences detected by
     * {@link TraceBinder#compareBootstrapFrame0(TraceData, EngineSnapshot)}.
     * Bootstrap divergences render ahead of the per-frame block in both
     * JSON and text outputs.
     */
    public DivergenceReport(List<FrameComparison> comparisons,
                            TraceData traceData,
                            List<BootstrapDivergence> bootstrapDivergences) {
        this.allComparisons = List.copyOf(comparisons);
        this.traceData = traceData;
        List<BootstrapDivergence> boot = bootstrapDivergences == null
                ? new ArrayList<>()
                : new ArrayList<>(bootstrapDivergences);
        boot.sort(Comparator.comparingInt(
                (BootstrapDivergence d) -> d.severity() == BootstrapDivergence.Severity.ERROR
                        ? 0 : 1));
        this.bootstrapDivergences = List.copyOf(boot);
        List<DivergenceGroup> allGroups = buildGroups(comparisons);
        this.errors = allGroups.stream()
            .filter(g -> g.severity() == Severity.ERROR)
            .toList();
        this.warnings = allGroups.stream()
            .filter(g -> g.severity() == Severity.WARNING)
            .toList();
    }

    public List<DivergenceGroup> errors() { return errors; }
    public List<DivergenceGroup> warnings() { return warnings; }
    public boolean hasErrors() { return totalErrorCount() > 0; }
    public boolean hasWarnings() { return totalWarningCount() > 0; }

    public List<DivergenceGroup> errors(TraceVerificationScope scope) {
        return errors.stream()
                .filter(group -> scope.includes(group.verificationGroup()))
                .toList();
    }

    public List<DivergenceGroup> warnings(TraceVerificationScope scope) {
        return warnings.stream()
                .filter(group -> scope.includes(group.verificationGroup()))
                .toList();
    }

    public boolean hasErrors(TraceVerificationScope scope) {
        return totalErrorCount(scope) > 0;
    }

    public boolean hasWarnings(TraceVerificationScope scope) {
        return totalWarningCount(scope) > 0;
    }

    /** Bootstrap (frame-0) divergences, sorted ERROR-first then WARNING. */
    public List<BootstrapDivergence> bootstrapDivergences() {
        return bootstrapDivergences;
    }

    public boolean hasBootstrapDivergences() {
        return !bootstrapDivergences.isEmpty();
    }

    public String toSummary() {
        return buildSummary(true);
    }

    /**
     * Compact one-line summary for assertion messages and sweep logs.
     * Full ROM/engine diagnostics are still written to JSON and context files
     * by callers; keeping this short avoids duplicating large context windows
     * into Surefire XML and console buffers during full trace sweeps.
     */
    public String toCompactSummary() {
        return buildSummary(false);
    }

    /**
     * Short assertion-only summary for failing trace replay tests. The
     * JSON/context report files carry checkpoint, zone, and diagnostics detail;
     * this string stays small so full sweeps do not flood Surefire XML and
     * console output with repeated context.
     */
    public String toAssertionSummary() {
        return toAssertionSummary(TraceVerificationScope.ALL);
    }

    /** Assertion summary restricted to one independent verification gate. */
    public String toAssertionSummary(TraceVerificationScope scope) {
        int errorCount = totalErrorCount(scope);
        int warningCount = totalWarningCount(scope);

        if (errorCount == 0 && warningCount == 0) {
            return "All frames match trace. No divergences.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(errorCount > 0
                ? "Trace replay diverged. "
                : "Trace replay produced warnings. ");
        sb.append(String.format("Totals: %d error%s, %d warning%s.",
            errorCount, errorCount == 1 ? "" : "s",
            warningCount, warningCount == 1 ? "" : "s"));

        BootstrapDivergence firstBootstrapError = scope.includes(VerificationGroup.PHYSICS)
                ? firstBootstrapDivergence(BootstrapDivergence.Severity.ERROR)
                : null;
        if (firstBootstrapError != null) {
            appendBootstrapSummary(sb, "error", firstBootstrapError, false);
            return sb.toString();
        }
        List<DivergenceGroup> scopedErrors = errors(scope);
        if (!scopedErrors.isEmpty()) {
            appendGroupSummary(sb, "error", scopedErrors.get(0));
            return sb.toString();
        }

        BootstrapDivergence firstBootstrapWarning = scope.includes(VerificationGroup.PHYSICS)
                ? firstBootstrapDivergence(BootstrapDivergence.Severity.WARNING)
                : null;
        if (firstBootstrapWarning != null) {
            appendBootstrapSummary(sb, "warning", firstBootstrapWarning, false);
            return sb.toString();
        }
        List<DivergenceGroup> scopedWarnings = warnings(scope);
        if (!scopedWarnings.isEmpty()) {
            appendGroupSummary(sb, "warning", scopedWarnings.get(0));
        }
        return sb.toString();
    }

    private String buildSummary(boolean includeInlineDiagnostics) {
        int errorCount = totalErrorCount();
        int warningCount = totalWarningCount();

        if (errorCount == 0 && warningCount == 0) {
            return "All frames match trace. No divergences.";
        }

        StringBuilder sb = new StringBuilder();
        int bootstrapErrorCount = bootstrapErrorCount();
        int bootstrapWarningCount = bootstrapWarningCount();
        if (bootstrapErrorCount > 0 || bootstrapWarningCount > 0) {
            sb.append(String.format("%d bootstrap error%s, %d bootstrap warning%s. ",
                bootstrapErrorCount, bootstrapErrorCount == 1 ? "" : "s",
                bootstrapWarningCount, bootstrapWarningCount == 1 ? "" : "s"));
        }
        sb.append(String.format("%d error%s, %d warning%s.",
            errorCount, errorCount == 1 ? "" : "s",
            warningCount, warningCount == 1 ? "" : "s"));

        BootstrapDivergence firstBootstrapError = firstBootstrapDivergence(BootstrapDivergence.Severity.ERROR);
        if (firstBootstrapError != null) {
            appendBootstrapSummary(sb, "error", firstBootstrapError, includeInlineDiagnostics);
        } else if (!errors.isEmpty()) {
            DivergenceGroup first = errors.get(0);
            sb.append(String.format(" First error: frame %d -- %s mismatch (expected=%s, actual=%s)",
                first.startFrame(), first.field(), first.expectedAtStart(), first.actualAtStart()));
            if (includeInlineDiagnostics) {
                appendFirstErrorDiagnostics(sb, first);
            }
        } else {
            BootstrapDivergence firstBootstrapWarning =
                    firstBootstrapDivergence(BootstrapDivergence.Severity.WARNING);
            if (firstBootstrapWarning != null) {
                appendBootstrapSummary(sb, "warning", firstBootstrapWarning, includeInlineDiagnostics);
            }
        }

        appendTraceContextSummary(sb, summaryReferenceFrame());
        return sb.toString();
    }

    private void appendBootstrapSummary(StringBuilder sb, String label,
                                        BootstrapDivergence divergence,
                                        boolean includeInlineDiagnostics) {
        sb.append(String.format(" First bootstrap %s: frame 0 -- %s mismatch (expected=%s, actual=%s)",
            label, divergence.field(), divergence.expected(), divergence.actual()));
        if (includeInlineDiagnostics
                && divergence.context() != null
                && !divergence.context().isBlank()) {
            sb.append(" ").append(divergence.context());
        }
    }

    private void appendGroupSummary(StringBuilder sb, String label, DivergenceGroup group) {
        sb.append(String.format(" First %s: frame %d -- %s mismatch (expected=%s, actual=%s)",
            label, group.startFrame(), group.field(), group.expectedAtStart(), group.actualAtStart()));
    }

    /**
     * Surface sub-pixel + counter context for the first failing frame inline
     * on the summary line so frontier-advancement iter loops can read it
     * without opening the report JSON. Only emitted for position/speed-shape
     * fields where the diagnostic context is informative.
     */
    private void appendFirstErrorDiagnostics(StringBuilder sb, DivergenceGroup first) {
        String field = first.field();
        if (field == null) {
            return;
        }
        boolean positionShape = field.equals("x") || field.equals("y")
                || field.endsWith("_x") || field.endsWith("_y")
                || field.endsWith("_x_speed") || field.endsWith("_y_speed")
                || field.endsWith("_g_speed")
                || field.equals("x_speed") || field.equals("y_speed")
                || field.equals("g_speed");
        if (!positionShape) {
            return;
        }
        FrameComparison fc = findComparison(first.startFrame());
        if (fc == null) {
            return;
        }
        String rom = fc.romDiagnostics();
        String engine = fc.engineDiagnostics();
        if ((rom == null || rom.isEmpty()) && (engine == null || engine.isEmpty())) {
            return;
        }
        sb.append(" rom={").append(rom == null ? "" : rom).append('}');
        sb.append(" engine={").append(engine == null ? "" : engine).append('}');
    }

    private FrameComparison findComparison(int frame) {
        for (FrameComparison fc : allComparisons) {
            if (fc.frame() == frame) {
                return fc;
            }
        }
        return null;
    }

    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode root = mapper.createObjectNode();

            root.put("error_count", totalErrorCount());
            root.put("warning_count", totalWarningCount());
            root.put("bootstrap_error_count", bootstrapErrorCount());
            root.put("bootstrap_warning_count", bootstrapWarningCount());
            root.put("total_frames", allComparisons.size());
            root.put("summary", toCompactSummary());

            ObjectNode verificationNode = root.putObject("verification_groups");
            appendVerificationGroupJson(mapper, verificationNode, VerificationGroup.PHYSICS);
            appendVerificationGroupJson(mapper, verificationNode, VerificationGroup.ANIMATION);

            int referenceFrame = summaryReferenceFrame();
            TraceEvent.Checkpoint checkpoint = latestCheckpointAtOrBefore(referenceFrame);
            if (checkpoint != null) {
                root.set("latest_checkpoint", checkpointToJson(mapper, checkpoint));
            }

            TraceEvent.ZoneActState zoneActState = latestZoneActStateAtOrBefore(referenceFrame);
            if (zoneActState != null) {
                root.set("latest_zone_act_state", zoneActStateToJson(mapper, zoneActState));
            }

            List<String> missingAuxSchemas = missingAdvertisedAuxSchemas();
            if (!missingAuxSchemas.isEmpty()) {
                ArrayNode missingNode = root.putArray("missing_advertised_aux_schemas");
                for (String schema : missingAuxSchemas) {
                    missingNode.add(schema);
                }
            }

            // Bootstrap (frame-0) divergences render ahead of the per-frame
            // groups so consumers see prelude failures first.
            ArrayNode bootstrapNode = root.putArray("bootstrap");
            for (BootstrapDivergence divergence : bootstrapDivergences) {
                bootstrapNode.add(bootstrapDivergenceToJson(mapper, divergence));
            }

            ArrayNode errorsNode = root.putArray("errors");
            for (DivergenceGroup g : errors) {
                errorsNode.add(groupToJson(mapper, g));
            }

            ArrayNode warningsNode = root.putArray("warnings");
            for (DivergenceGroup g : warnings) {
                warningsNode.add(groupToJson(mapper, g));
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\": \"Failed to serialise report: " + e.getMessage() + "\"}";
        }
    }

    public String getContextWindow(int centreFrame, int radius) {
        int centreIndex = comparisonIndexForFrame(centreFrame);
        int start = Math.max(0, centreIndex - radius);
        int end = Math.min(allComparisons.size() - 1, centreIndex + radius);
        List<FrameComparison> rows = contextRows(start, end, centreFrame);

        StringBuilder sb = new StringBuilder();
        if (shouldRenderBootstrapSection()) {
            appendBootstrapSection(sb);
        }
        appendTraceContextWindow(sb, centreFrame);
        sb.append("=== Per-frame ===\n");
        sb.append(String.format("%-6s", "Frame"));

        Set<String> fieldNames = new LinkedHashSet<>();
        boolean allFields = shouldRenderAllContextFields();
        for (FrameComparison fc : rows) {
            if (allFields) {
                fieldNames.addAll(fc.fields().keySet());
            } else {
                fc.fields().entrySet().stream()
                        .filter(entry -> entry.getValue().isContextRelevant())
                        .map(Map.Entry::getKey)
                        .forEach(fieldNames::add);
            }
        }
        if (fieldNames.isEmpty()) {
            for (FrameComparison fc : rows) {
                fieldNames.addAll(fc.fields().keySet());
            }
        }

        for (String field : fieldNames) {
            sb.append(String.format(" | %-8s | %-8s", "Exp " + field, "Act " + field));
        }
        sb.append("\n");

        for (FrameComparison fc : rows) {
            sb.append(String.format("%-6d", fc.frame()));
            for (String field : fieldNames) {
                FieldComparison comp = fc.fields().get(field);
                if (comp != null) {
                    String marker = comp.severity() == Severity.ERROR
                            ? "*"
                            : comp.observedMismatch() ? "~" : " ";
                    sb.append(String.format(" | %-8s |%s%-7s",
                        comp.expected(), marker, comp.actual()));
                } else {
                    sb.append(String.format(" | %-8s | %-8s", "?", "?"));
                }
            }
            if (shouldRenderFrameDiagnostics(fc, centreFrame)) {
                String romDiag = fc.romDiagnostics();
                String engDiag = fc.engineDiagnostics();
                if (!romDiag.isEmpty() || !engDiag.isEmpty()) {
                    sb.append("\n       ROM: ").append(formatContextDiagnostics(romDiag));
                    sb.append("\n       ENG: ").append(formatContextDiagnostics(engDiag));
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<FrameComparison> contextRows(int start, int end, int centreFrame) {
        List<FrameComparison> radiusRows = new ArrayList<>();
        for (int i = start; i <= end && i < allComparisons.size(); i++) {
            radiusRows.add(allComparisons.get(i));
        }
        if (shouldRenderAllContextRows()) {
            return radiusRows;
        }
        List<FrameComparison> relevantRows = radiusRows.stream()
                .filter(fc -> fc.frame() == centreFrame || hasContextRelevantField(fc))
                .toList();
        return relevantRows.isEmpty() ? radiusRows : relevantRows;
    }

    private boolean hasContextRelevantField(FrameComparison comparison) {
        return comparison.fields().values().stream()
                .anyMatch(FieldComparison::isContextRelevant);
    }

    private boolean shouldRenderAllContextRows() {
        String mode = System.getProperty("trace.context.rows", "relevant")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "all", "full", "radius", "window" -> true;
            default -> false;
        };
    }

    private boolean shouldRenderFrameDiagnostics(FrameComparison comparison, int centreFrame) {
        if (!comparison.hasDivergence()) {
            return false;
        }
        String mode = System.getProperty("trace.context.diagnostics", "frontier")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "all", "full", "verbose" -> true;
            case "none", "off", "false" -> false;
            default -> comparison.frame() == centreFrame;
        };
    }

    private boolean shouldRenderAllContextFields() {
        String mode = System.getProperty("trace.context.fields", "divergent")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "all", "full", "compared" -> true;
            default -> false;
        };
    }

    private String formatContextDiagnostics(String diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "-";
        }
        int maxChars = contextDiagnosticMaxChars();
        if (maxChars < 0 || diagnostics.length() <= maxChars) {
            return diagnostics;
        }
        int visibleChars = Math.max(0, maxChars);
        return diagnostics.substring(0, visibleChars)
                + "... [truncated "
                + (diagnostics.length() - visibleChars)
                + " chars; set -Dtrace.context.diagnosticChars=full]";
    }

    private int contextDiagnosticMaxChars() {
        String value = System.getProperty("trace.context.diagnosticChars", "900")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (value.equals("full") || value.equals("all") || value.equals("unlimited")) {
            return -1;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 900;
        }
    }

    private int comparisonIndexForFrame(int frame) {
        if (allComparisons.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < allComparisons.size(); i++) {
            if (allComparisons.get(i).frame() == frame) {
                return i;
            }
        }
        int insertion = 0;
        while (insertion < allComparisons.size() && allComparisons.get(insertion).frame() < frame) {
            insertion++;
        }
        if (insertion == 0) {
            return 0;
        }
        if (insertion >= allComparisons.size()) {
            return allComparisons.size() - 1;
        }
        int beforeFrame = allComparisons.get(insertion - 1).frame();
        int afterFrame = allComparisons.get(insertion).frame();
        return Math.abs(frame - beforeFrame) <= Math.abs(afterFrame - frame)
                ? insertion - 1
                : insertion;
    }

    private void appendTraceContextSummary(StringBuilder sb, int frame) {
        TraceEvent.Checkpoint checkpoint = latestCheckpointAtOrBefore(frame);
        if (checkpoint != null) {
            sb.append(" Latest checkpoint: ")
                .append(TraceEventFormatter.summariseFrameEvents(List.of(checkpoint)));
        }

        TraceEvent.ZoneActState zoneActState = latestZoneActStateAtOrBefore(frame);
        if (zoneActState != null) {
            sb.append(" Latest zone/act state: ")
                .append(TraceEventFormatter.summariseFrameEvents(List.of(zoneActState)));
        }
    }

    private void appendTraceContextWindow(StringBuilder sb, int frame) {
        TraceEvent.Checkpoint checkpoint = latestCheckpointAtOrBefore(frame);
        if (checkpoint != null) {
            sb.append("Latest checkpoint: ")
                .append(TraceEventFormatter.summariseFrameEvents(List.of(checkpoint)))
                .append("\n");
        }

        TraceEvent.ZoneActState zoneActState = latestZoneActStateAtOrBefore(frame);
        if (zoneActState != null) {
            sb.append("Latest zone_act_state: ")
                .append(TraceEventFormatter.summariseFrameEvents(List.of(zoneActState)))
                .append("\n");
        }

        List<String> missingAuxSchemas = missingAdvertisedAuxSchemas();
        if (!missingAuxSchemas.isEmpty()) {
            sb.append("Missing advertised aux schemas: ")
                .append(String.join(", ", missingAuxSchemas))
                .append("\n");
        }

        appendFocusedTraceDiagnostics(sb, frame);
    }

    private int summaryReferenceFrame() {
        if (hasBootstrapDivergences()) {
            return 0;
        }
        if (!errors.isEmpty()) {
            return errors.get(0).startFrame();
        }
        if (!warnings.isEmpty()) {
            return warnings.get(0).startFrame();
        }
        if (!allComparisons.isEmpty()) {
            return allComparisons.get(allComparisons.size() - 1).frame();
        }
        return -1;
    }

    private int totalErrorCount() {
        return errors.size() + bootstrapErrorCount();
    }

    private int totalErrorCount(TraceVerificationScope scope) {
        int count = errors(scope).size();
        return scope.includes(VerificationGroup.PHYSICS) ? count + bootstrapErrorCount() : count;
    }

    /**
     * Sum of the per-group {@code error_count} values this report publishes under
     * {@code verification_groups}. Bootstrap errors are folded into PHYSICS by
     * {@link #appendVerificationGroupJson}, so this is exactly what a reader
     * adding up the published groups gets, and it must equal the flat
     * {@code error_count}. Exposed so the report writers can assert that
     * accounting rather than merely display it -- the chain reports publish the
     * same breakdown and assert the same invariant, and the reason both do is
     * that the chain path drifted once where nobody was checking.
     */
    public int errorCountByVerificationGroups() {
        int sum = 0;
        for (VerificationGroup group : VerificationGroup.values()) {
            sum += totalErrorCount(group == VerificationGroup.PHYSICS
                    ? TraceVerificationScope.PHYSICS
                    : TraceVerificationScope.ANIMATION);
        }
        return sum;
    }

    /** Flat error total, including bootstrap errors. */
    public int publishedErrorCount() {
        return totalErrorCount();
    }

    private int totalWarningCount() {
        return warnings.size() + bootstrapWarningCount();
    }

    private int totalWarningCount(TraceVerificationScope scope) {
        int count = warnings(scope).size();
        return scope.includes(VerificationGroup.PHYSICS) ? count + bootstrapWarningCount() : count;
    }

    private int bootstrapErrorCount() {
        return (int) bootstrapDivergences.stream()
                .filter(d -> d.severity() == BootstrapDivergence.Severity.ERROR)
                .count();
    }

    private int bootstrapWarningCount() {
        return (int) bootstrapDivergences.stream()
                .filter(d -> d.severity() == BootstrapDivergence.Severity.WARNING)
                .count();
    }

    public boolean hasBootstrapErrors() {
        return bootstrapErrorCount() > 0;
    }

    public boolean hasBootstrapWarnings() {
        return bootstrapWarningCount() > 0;
    }

    /** First blocking frame for the selected gate, or {@code -1} when clean. */
    public int firstErrorFrame(TraceVerificationScope scope) {
        if (scope.includes(VerificationGroup.PHYSICS) && hasBootstrapErrors()) {
            return 0;
        }
        List<DivergenceGroup> scoped = errors(scope);
        return scoped.isEmpty() ? -1 : scoped.getFirst().startFrame();
    }

    private BootstrapDivergence firstBootstrapDivergence(BootstrapDivergence.Severity severity) {
        for (BootstrapDivergence divergence : bootstrapDivergences) {
            if (divergence.severity() == severity) {
                return divergence;
            }
        }
        return null;
    }

    private TraceEvent.Checkpoint latestCheckpointAtOrBefore(int frame) {
        if (traceData == null || frame < 0) {
            return null;
        }
        return traceData.latestCheckpointAtOrBefore(frame);
    }

    private TraceEvent.ZoneActState latestZoneActStateAtOrBefore(int frame) {
        if (traceData == null || frame < 0) {
            return null;
        }
        return traceData.latestZoneActStateAtOrBefore(frame);
    }

    private List<String> missingAdvertisedAuxSchemas() {
        return traceData == null
                ? List.of()
                : traceData.missingAdvertisedAuxSchemas();
    }

    private void appendFocusedTraceDiagnostics(StringBuilder sb, int frame) {
        if (traceData == null || frame < 0) {
            return;
        }
        List<TraceEvent> diagnostics = new ArrayList<>();
        diagnostics.addAll(traceData.stateSnapshotsForFrame(frame));
        diagnostics.addAll(traceData.cageStatesForFrame(frame));
        TraceEvent.CageExecution cageExecution = traceData.cageExecutionForFrame(frame);
        if (cageExecution != null) {
            diagnostics.add(cageExecution);
        }
        TraceEvent.VelocityWrite velocityWrite = traceData.velocityWriteForFrame(frame, "tails");
        if (velocityWrite != null) {
            diagnostics.add(velocityWrite);
        }
        TraceEvent.PositionWrite positionWrite = traceData.positionWriteForFrame(frame, "tails");
        if (positionWrite != null) {
            diagnostics.add(positionWrite);
        }
        TraceEvent.PositionWrite sonicPositionWrite = traceData.positionWriteForFrame(frame, "sonic");
        if (sonicPositionWrite != null) {
            diagnostics.add(sonicPositionWrite);
        }
        TraceEvent.AizShipLoop aizShipLoop = traceData.aizShipLoopForFrame(frame);
        if (aizShipLoop != null) {
            diagnostics.add(aizShipLoop);
        }
        TraceEvent.SonicRecordPos sonicRecordPos = traceData.sonicRecordPosForFrame(frame);
        if (sonicRecordPos != null) {
            diagnostics.add(sonicRecordPos);
        }
        TraceEvent.CpuState cpuState = traceData.cpuStateForFrame(frame, "tails");
        if (cpuState != null) {
            diagnostics.add(cpuState);
        }
        TraceEvent.TailsCpuNormalStep cpuNormalStep =
                traceData.tailsCpuNormalStepForFrame(frame, "tails");
        if (cpuNormalStep != null) {
            diagnostics.add(cpuNormalStep);
            TraceEvent.SonicRecordPos sourceRecord =
                    latestSonicRecordPosForStatIndex(frame,
                            (cpuNormalStep.posTableIndex() - 0x44) & 0xFF);
            if (sourceRecord != null && sourceRecord != sonicRecordPos) {
                diagnostics.add(sourceRecord);
            }
        }
        TraceEvent.SidekickInteractObjectState interactObject =
                traceData.sidekickInteractObjectStateForFrame(frame, "tails");
        if (interactObject != null) {
            diagnostics.add(interactObject);
        }
        diagnostics.addAll(traceData.cnzCylinderStatesForFrame(frame));
        TraceEvent.CnzCylinderExecution cnzCylinderExecution =
                traceData.cnzCylinderExecutionForFrame(frame);
        if (cnzCylinderExecution != null) {
            diagnostics.add(cnzCylinderExecution);
        }
        TraceEvent.CnzEventRamState cnzEventRam =
                traceData.cnzEventRamStateForFrame(frame);
        if (cnzEventRam != null) {
            diagnostics.add(cnzEventRam);
        }
        diagnostics.addAll(traceData.airCountdownStatesForFrame(frame));
        TraceEvent.RngCall rngCall = traceData.rngCallForFrame(frame);
        if (rngCall != null) {
            diagnostics.add(rngCall);
        }
        TraceEvent.AizBoundaryState aizBoundary =
                traceData.aizBoundaryStateForFrame(frame, "tails");
        if (aizBoundary != null) {
            diagnostics.add(aizBoundary);
        }
        TraceEvent.AizTransitionFloorSolidState aizFloor =
                traceData.aizTransitionFloorSolidStateForFrame(frame);
        if (aizFloor != null) {
            diagnostics.add(aizFloor);
        }
        TraceEvent.AizHandoffTerrainState aizHandoffTerrain =
                traceData.aizHandoffTerrainStateForFrame(frame);
        if (aizHandoffTerrain != null) {
            diagnostics.add(aizHandoffTerrain);
        }
        TraceEvent.AizFireTransition aizFireTransition =
                traceData.aizFireTransitionForFrame(frame);
        if (aizFireTransition != null) {
            diagnostics.add(aizFireTransition);
        }
        // v3.7 S1 diagnostic context: object respawn-state bit array (slot-cadence
        // cluster) and camera vertical-boundary state (MZ1). Comparison-only.
        TraceEvent.VObjState vObjState = traceData.vObjStateForFrame(frame);
        if (vObjState != null) {
            diagnostics.add(vObjState);
        }
        // v3.10 S1 diagnostic context: global oscillation state (osc-phase cluster,
        // e.g. SLZ2 f3353). Comparison-only.
        TraceEvent.VOscillate vOscillate = traceData.vOscillateForFrame(frame);
        if (vOscillate != null) {
            diagnostics.add(vOscillate);
        }
        // v3.11 S1 diagnostic context: BizHawk authoritative lag state (confirms
        // the counter/oscillation skip-frame vs emulator-lag coincidence).
        // Comparison-only.
        TraceEvent.LagState lagState = traceData.lagStateForFrame(frame);
        if (lagState != null) {
            diagnostics.add(lagState);
        }
        TraceEvent.CameraBoundary cameraBoundary = traceData.cameraBoundaryForFrame(frame);
        if (cameraBoundary != null) {
            diagnostics.add(cameraBoundary);
        }
        if (!diagnostics.isEmpty()) {
            sb.append("Trace diagnostics @")
                .append(frame)
                .append(": ")
                .append(TraceEventFormatter.summariseFrameEvents(diagnostics))
                .append("\n");
        }
    }

    private TraceEvent.SonicRecordPos latestSonicRecordPosForStatIndex(int frame, int statIndex) {
        if (traceData == null || frame < 0) {
            return null;
        }
        int start = Math.max(0, frame - 80);
        for (int f = frame; f >= start; f--) {
            TraceEvent.SonicRecordPos record = traceData.sonicRecordPosForFrame(f);
            if (record == null) {
                continue;
            }
            for (TraceEvent.SonicRecordPos.Hit hit : record.hits()) {
                if ((hit.posTableIndex() & 0xFF) == statIndex) {
                    return record;
                }
            }
        }
        return null;
    }

    private boolean shouldRenderBootstrapSection() {
        if (!bootstrapDivergences.isEmpty()) {
            return true;
        }
        String mode = System.getProperty("trace.context.bootstrap", "divergent")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "all", "always", "full", "true" -> true;
            default -> false;
        };
    }

    private static List<DivergenceGroup> buildGroups(List<FrameComparison> comparisons) {
        List<DivergenceGroup> groups = new ArrayList<>();
        Map<String, DivergenceGroupBuilder> openGroups = new LinkedHashMap<>();

        for (FrameComparison fc : comparisons) {
            Set<String> activeFields = new HashSet<>();

            for (Map.Entry<String, FieldComparison> entry : fc.fields().entrySet()) {
                String field = entry.getKey();
                FieldComparison comp = entry.getValue();

                if (comp.isDivergent()) {
                    activeFields.add(field);
                    DivergenceGroupBuilder builder = openGroups.get(field);
                    if (builder != null && builder.severity == comp.severity()
                            && builder.endFrame == fc.frame() - 1) {
                        builder.endFrame = fc.frame();
                    } else {
                        if (builder != null) {
                            groups.add(builder.build());
                        }
                        openGroups.put(field, new DivergenceGroupBuilder(
                            field, comp.severity(), comp.verificationGroup(), fc.frame(),
                            comp.expected(), comp.actual()));
                    }
                }
            }

            Iterator<Map.Entry<String, DivergenceGroupBuilder>> iter =
                openGroups.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, DivergenceGroupBuilder> entry = iter.next();
                if (!activeFields.contains(entry.getKey())) {
                    groups.add(entry.getValue().build());
                    iter.remove();
                }
            }
        }

        for (DivergenceGroupBuilder builder : openGroups.values()) {
            groups.add(builder.build());
        }

        groups.sort(Comparator
            .comparingInt(DivergenceGroup::startFrame)
            .thenComparingInt(g -> fieldSummaryPriority(g.field())));
        markCascading(groups);
        return groups;
    }

    private static int fieldSummaryPriority(String field) {
        if (field == null) {
            return 50;
        }
        if (field.equals("x") || field.equals("y")
                || field.endsWith("_x") || field.endsWith("_y")
                || field.equals("x_sub") || field.equals("y_sub")
                || field.endsWith("_x_sub") || field.endsWith("_y_sub")
                || field.equals("x_speed") || field.equals("y_speed")
                || field.equals("g_speed")
                || field.endsWith("_x_speed") || field.endsWith("_y_speed")
                || field.endsWith("_g_speed")) {
            return 0;
        }
        if (field.equals("routine") || field.endsWith("_routine")) {
            return 10;
        }
        if (field.equals("status_byte") || field.endsWith("_status_byte")) {
            return 40;
        }
        return 20;
    }

    private static void markCascading(List<DivergenceGroup> groups) {
        for (VerificationGroup verificationGroup : VerificationGroup.values()) {
            int earliestErrorFrame = Integer.MAX_VALUE;
            String earliestErrorField = null;
            for (DivergenceGroup group : groups) {
                if (group.verificationGroup() == verificationGroup
                        && group.severity() == Severity.ERROR
                        && group.startFrame() < earliestErrorFrame) {
                    earliestErrorFrame = group.startFrame();
                    earliestErrorField = group.field();
                }
            }

            if (earliestErrorField == null) {
                continue;
            }

            for (int i = 0; i < groups.size(); i++) {
                DivergenceGroup group = groups.get(i);
                if (group.verificationGroup() != verificationGroup) {
                    continue;
                }
                boolean cascading = group.severity() == Severity.ERROR
                        && group.startFrame() > earliestErrorFrame
                        && !group.field().equals(earliestErrorField);
                if (cascading != group.cascading()) {
                    groups.set(i, new DivergenceGroup(group.field(), group.severity(),
                            group.startFrame(), group.endFrame(),
                            group.expectedAtStart(), group.actualAtStart(), cascading,
                            group.verificationGroup()));
                }
            }
        }
    }

    private ObjectNode groupToJson(ObjectMapper mapper, DivergenceGroup g) {
        ObjectNode node = mapper.createObjectNode();
        node.put("field", g.field());
        node.put("severity", g.severity().name());
        node.put("start_frame", g.startFrame());
        node.put("end_frame", g.endFrame());
        node.put("frame_span", g.frameSpan());
        node.put("expected_at_start", g.expectedAtStart());
        node.put("actual_at_start", g.actualAtStart());
        node.put("cascading", g.cascading());
        node.put("verification_group", g.verificationGroup().id());
        return node;
    }

    private void appendVerificationGroupJson(
            ObjectMapper mapper, ObjectNode parent, VerificationGroup group) {
        TraceVerificationScope scope = group == VerificationGroup.PHYSICS
                ? TraceVerificationScope.PHYSICS
                : TraceVerificationScope.ANIMATION;
        ObjectNode node = parent.putObject(group.id());
        List<DivergenceGroup> scopedErrors = errors(scope);
        List<DivergenceGroup> scopedWarnings = warnings(scope);
        node.put("error_count", totalErrorCount(scope));
        node.put("warning_count", totalWarningCount(scope));
        boolean wroteBootstrapError = false;
        if (group == VerificationGroup.PHYSICS) {
            BootstrapDivergence bootstrap =
                    firstBootstrapDivergence(BootstrapDivergence.Severity.ERROR);
            if (bootstrap != null) {
                node.set("first_error", bootstrapDivergenceToJson(mapper, bootstrap));
                wroteBootstrapError = true;
            }
        }
        if (!wroteBootstrapError && !scopedErrors.isEmpty()) {
            node.set("first_error", groupToJson(mapper, scopedErrors.getFirst()));
        }
        if (group == VerificationGroup.PHYSICS && hasBootstrapWarnings()) {
            node.set("first_warning", bootstrapDivergenceToJson(mapper,
                    firstBootstrapDivergence(BootstrapDivergence.Severity.WARNING)));
        } else if (!scopedWarnings.isEmpty()) {
            node.set("first_warning", groupToJson(mapper, scopedWarnings.getFirst()));
        }
    }

    private ObjectNode checkpointToJson(ObjectMapper mapper, TraceEvent.Checkpoint checkpoint) {
        ObjectNode node = mapper.createObjectNode();
        node.put("frame", checkpoint.frame());
        node.put("name", checkpoint.name());
        putNullableInt(node, "actual_zone_id", checkpoint.actualZoneId());
        putNullableInt(node, "actual_act", checkpoint.actualAct());
        putNullableInt(node, "apparent_act", checkpoint.apparentAct());
        putNullableInt(node, "game_mode", checkpoint.gameMode());
        if (checkpoint.notes() == null) {
            node.putNull("notes");
        } else {
            node.put("notes", checkpoint.notes());
        }
        return node;
    }

    private ObjectNode zoneActStateToJson(ObjectMapper mapper, TraceEvent.ZoneActState zoneActState) {
        ObjectNode node = mapper.createObjectNode();
        node.put("frame", zoneActState.frame());
        putNullableInt(node, "actual_zone_id", zoneActState.actualZoneId());
        putNullableInt(node, "actual_act", zoneActState.actualAct());
        putNullableInt(node, "apparent_act", zoneActState.apparentAct());
        putNullableInt(node, "game_mode", zoneActState.gameMode());
        return node;
    }

    private void putNullableInt(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Appends the "=== Bootstrap (frame 0) ===" section to the text context
     * window. Always emits the header so the per-frame header that follows
     * is unambiguous; the body is empty for legacy traces.
     */
    private void appendBootstrapSection(StringBuilder sb) {
        sb.append("=== Bootstrap (frame 0) ===\n");
        if (bootstrapDivergences.isEmpty()) {
            sb.append("(no bootstrap divergences)\n");
            return;
        }
        for (BootstrapDivergence divergence : bootstrapDivergences) {
            sb.append(String.format("[%s] %s  expected=%s  actual=%s%n",
                    divergence.severity().name(),
                    divergence.field(),
                    divergence.expected(),
                    divergence.actual()));
            if (divergence.context() != null && !divergence.context().isBlank()) {
                sb.append("    ").append(divergence.context()).append("\n");
            }
        }
    }

    /** Builds the JSON node for a single {@link BootstrapDivergence}. */
    private ObjectNode bootstrapDivergenceToJson(ObjectMapper mapper,
                                                 BootstrapDivergence divergence) {
        ObjectNode node = mapper.createObjectNode();
        node.put("field", divergence.field());
        node.put("severity", divergence.severity().name());
        node.put("expected", divergence.expected());
        node.put("actual", divergence.actual());
        node.put("context", divergence.context() == null ? "" : divergence.context());
        return node;
    }

    private static class DivergenceGroupBuilder {
        final String field;
        final Severity severity;
        final VerificationGroup verificationGroup;
        final int startFrame;
        final String expectedAtStart;
        final String actualAtStart;
        int endFrame;

        DivergenceGroupBuilder(String field, Severity severity,
                VerificationGroup verificationGroup, int frame,
                String expected, String actual) {
            this.field = field;
            this.severity = severity;
            this.verificationGroup = verificationGroup;
            this.startFrame = frame;
            this.endFrame = frame;
            this.expectedAtStart = expected;
            this.actualAtStart = actual;
        }

        DivergenceGroup build() {
            return new DivergenceGroup(field, severity, startFrame, endFrame,
                expectedAtStart, actualAtStart, false, verificationGroup);
        }
    }
}
