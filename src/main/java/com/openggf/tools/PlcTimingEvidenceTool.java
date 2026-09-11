package com.openggf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Structural PLC readiness predictor used only to review BizHawk diagnostic
 * captures. It deliberately receives no captured decoder-progress fields.
 */
public final class PlcTimingEvidenceTool {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private PlcTimingEvidenceTool() {
    }

    public static void main(String[] arguments) throws IOException {
        if (!run(arguments)) {
            System.exit(1);
        }
    }

    /** Runs the command-line evidence derivation and leaves the raw probe untouched. */
    public static boolean run(String[] arguments) throws IOException {
        Map<String, String> options = parseOptions(arguments);
        String game = require(options, "--game");
        Path rom = Path.of(require(options, "--rom"));
        Path probe = Path.of(require(options, "--probe"));
        Path output = Path.of(require(options, "--out"));
        if (!Files.isRegularFile(rom) || !Files.isRegularFile(probe)) {
            throw new IllegalArgumentException("--rom and --probe must name regular files");
        }
        if (Files.exists(output)) {
            throw new IllegalArgumentException("refusing to overwrite evidence vector " + output);
        }

        Evidence evidence = derive(game, rom, probe);
        AnalysisResult result = analyze(evidence);
        JSON.writeValue(output.toFile(), new Vector(evidence, result));
        if (result.matches()) {
            System.out.println("PLC evidence matches: " + output);
        } else {
            System.err.println("PLC evidence mismatch: " + result.firstMismatch());
        }
        return result.matches();
    }

    private static Map<String, String> parseOptions(String[] arguments) {
        if ((arguments.length & 1) != 0) {
            throw new IllegalArgumentException("expected --game, --rom, --probe, and --out options");
        }
        Map<String, String> options = new TreeMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (options.put(arguments[index], arguments[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate option " + arguments[index]);
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static Evidence derive(String game, Path rom, Path probe) throws IOException {
        List<MutableRow> rows = new ArrayList<>();
        List<ObservedEdge> observed = new ArrayList<>();
        List<JsonNode> records = new ArrayList<>();
        boolean sawConsumerObservation = false;
        int lastFrame = -1;
        int lastOrder = -1;
        for (String line : Files.readAllLines(probe)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            int frame = requiredInt(node, "raw_frame");
            int order = requiredInt(node, "within_frame_order");
            if (frame < lastFrame || frame == lastFrame && order <= lastOrder) {
                throw new IllegalArgumentException("probe records must be ordered by raw_frame and within_frame_order");
            }
            lastFrame = frame;
            lastOrder = order;
            records.add(node);
        }
        MutableRow current = null;
        MutableRow openVint = null;
        for (JsonNode node : records) {
            int frame = requiredInt(node, "raw_frame");
            int order = requiredInt(node, "within_frame_order");
            String event = requiredText(node, "event");
            if (event.equals("plc_frame_state")) {
                current = new MutableRow(frame, order, StructuralPhase.PASSIVE, node);
                rows.add(current);
                openVint = null;
                continue;
            }
            if (event.equals("plc_vint_state")) {
                if (openVint != null) {
                    throw new IllegalArgumentException("plc_vint_state arrived before the prior VInt segment resolved");
                }
                current = new MutableRow(frame, order, StructuralPhase.VINT, node);
                rows.add(current);
                openVint = current;
                continue;
            }
            if (event.equals("plc_hblank_state")) {
                if (openVint == null || openVint.lag || !hblankCapable(game, openVint.handler)) {
                    throw new IllegalArgumentException("plc_hblank_state requires an open non-lag defer-capable VInt");
                }
                if (!requiredBoolean(node, "hblank_deferred")
                        || requiredInt(node, "interrupt_handler") != openVint.handler
                        || requiredBoolean(node, "lag")) {
                    throw new IllegalArgumentException("plc_hblank_state contradicts its open VInt");
                }
                openVint.phase = StructuralPhase.DEFERRED_VINT;
                openVint.hblankDeferred = true;
                current = new MutableRow(frame, order, StructuralPhase.HBLANK, node);
                rows.add(current);
                openVint = null;
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("PLC record has no independent structural state");
            }
            openVint = null;
            MutableRow row = MutableRow.action(frame, order, current);
            rows.add(row);
            int source = optionalInt(node, "queue_source", 0);
            int remaining = optionalInt(node, "patterns_left_after", 0);
            switch (event) {
                case "plc_submission" -> {
                    String operation = requiredText(node, "operation");
                    if (operation.equals("replace") || operation.equals("clear")) {
                        if (requiredInt(node, "patterns_left_before") != 0
                                || requiredInt(node, "patterns_left_after") != 0) {
                            throw new IllegalArgumentException(operation + " requires an idle decoder before and after completion");
                        }
                    }
                    if (operation.equals("replace") && requiredInt(node, "queue_slots_after") == 0) {
                        throw new IllegalArgumentException("replacement post-state must contain its copied queue entry");
                    }
                    row.submissions.addAll(romSubmissions(game, rom, operation, optionalInt(node, "plc_id", -1)));
                }
                case "plc_prepare_end" -> {
                    requireIncrease(node, "plc_prepare_end", "patterns_left_before", "patterns_left_after");
                    row.runPlcCalled = true;
                    observed.add(new ObservedEdge(frame, EdgeKind.PREPARE, source, remaining));
                }
                case "plc_service" -> {
                    requireDecrease(node, "plc_service", "patterns_left_before", "patterns_left_after");
                    observed.add(new ObservedEdge(frame,
                            current.phase == StructuralPhase.HBLANK
                                    ? EdgeKind.HBLANK_SERVICE : EdgeKind.SERVICE,
                            source, remaining));
                }
                case "plc_pop" -> {
                    requireDecrease(node, "plc_pop", "queue_slots_before", "queue_slots_after");
                    observed.add(new ObservedEdge(frame, EdgeKind.POP, source, remaining));
                }
                case "plc_empty" -> {
                    if (requiredInt(node, "queue_slots_after") != 0) {
                        throw new IllegalArgumentException("plc_empty must be sampled after the final queue entry is removed");
                    }
                    observed.add(new ObservedEdge(frame, EdgeKind.EMPTY, 0, 0));
                }
                case "plc_consumer_observation" -> {
                    sawConsumerObservation = true;
                    row.consumerPolls.add(new ConsumerPoll(requiredText(node, "consumer_id"), order));
                    observed.add(new ObservedEdge(frame,
                            requiredBoolean(node, "queue_empty") ? EdgeKind.CONSUMER_EMPTY : EdgeKind.CONSUMER_BUSY,
                            source, remaining));
                }
                case "plc_prepare_begin" -> { }
                default -> throw new IllegalArgumentException("unsupported probe event " + event);
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("probe contains no plc_frame_state records");
        }
        if (!sawConsumerObservation) {
            throw new IllegalArgumentException("probe contains no consumer observation; PLC readiness approval is forbidden");
        }
        return new Evidence(game, handlerBudgets(game), rows.stream().map(MutableRow::freeze).toList(), observed);
    }

    private static boolean hblankCapable(String game, int handler) {
        return switch (game) {
            case "s1" -> handler == 0x08 || handler == 0x10;
            case "s2" -> handler == 0x08;
            default -> false;
        };
    }

    private static void requireIncrease(JsonNode node, String event, String beforeField, String afterField) {
        if (requiredInt(node, afterField) <= requiredInt(node, beforeField)) {
            throw new IllegalArgumentException(event + " needs independently sampled " + beforeField
                    + " and later " + afterField);
        }
    }

    private static void requireDecrease(JsonNode node, String event, String beforeField, String afterField) {
        if (requiredInt(node, afterField) >= requiredInt(node, beforeField)) {
            throw new IllegalArgumentException(event + " needs independently sampled " + beforeField
                    + " and later " + afterField);
        }
    }

    private static int romPatternCount(Path rom, int sourceAddress) throws IOException {
        if (sourceAddress < 0) {
            throw new IllegalArgumentException("PLC source address must not be negative");
        }
        try (FileChannel channel = FileChannel.open(rom, StandardOpenOption.READ)) {
            ByteBuffer bytes = ByteBuffer.allocate(2);
            channel.position(sourceAddress);
            if (channel.read(bytes) != 2) {
                throw new IllegalArgumentException("PLC source is outside the supplied ROM: " + sourceAddress);
            }
            bytes.flip();
            int count = ((bytes.get() & 0xFF) << 8 | (bytes.get() & 0xFF)) & 0x7FFF;
            if (count == 0) {
                throw new IllegalArgumentException("PLC source has no Nemesis patterns: " + sourceAddress);
            }
            return count;
        }
    }

    private static List<Submission> romSubmissions(String game, Path rom, String operation, int plcId) throws IOException {
        SubmissionOperation kind = switch (operation) {
            case "append" -> SubmissionOperation.APPEND;
            case "replace" -> SubmissionOperation.REPLACE;
            case "clear" -> SubmissionOperation.CLEAR;
            default -> throw new IllegalArgumentException("unsupported PLC submission operation " + operation);
        };
        if (kind == SubmissionOperation.CLEAR) {
            return List.of(new Submission(0, 0, kind));
        }
        if (plcId < 0) {
            throw new IllegalArgumentException("PLC submission requires plc_id");
        }
        byte[] bytes = Files.readAllBytes(rom);
        int table = switch (game) {
            case "s1" -> 0x1DD86;
            case "s2" -> 0x42660;
            default -> throw new IllegalArgumentException("--game must be s1 or s2");
        };
        int offsetAddress = table + plcId * 2;
        requireRomRange(bytes, offsetAddress, 2);
        int definition = table + unsignedWord(bytes, offsetAddress);
        requireRomRange(bytes, definition, 2);
        int count = (short) unsignedWord(bytes, definition);
        if (count < 0) {
            return List.of();
        }
        var entries = new ArrayList<Submission>();
        for (int index = 0; index <= count; index++) {
            int entry = definition + 2 + index * 6;
            requireRomRange(bytes, entry, 6);
            int source = intWord(bytes, entry);
            entries.add(new Submission(source, romPatternCount(rom, source),
                    index == 0 ? kind : SubmissionOperation.APPEND));
        }
        return List.copyOf(entries);
    }

    private static void requireRomRange(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("PLC definition lies outside the supplied ROM");
        }
    }

    private static int unsignedWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 8 | bytes[offset + 1] & 0xFF;
    }

    private static int intWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }

    private static Map<Integer, Integer> handlerBudgets(String game) {
        return switch (game) {
            case "s1" -> Map.of(0x04, 9, 0x08, 3, 0x0C, 9, 0x10, 3, 0x12, 9, 0x18, 9);
            case "s2" -> Map.of(0x04, 6, 0x08, 3, 0x0A, 3, 0x0C, 6, 0x12, 6, 0x16, 6);
            default -> throw new IllegalArgumentException("--game must be s1 or s2");
        };
    }

    private static int requiredInt(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).canConvertToInt()) {
            throw new IllegalArgumentException("probe event requires integer " + field);
        }
        return node.get(field).intValue();
    }

    private static int optionalInt(JsonNode node, String field, int fallback) {
        return node.has(field) ? requiredInt(node, field) : fallback;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isBoolean()) {
            throw new IllegalArgumentException("probe event requires boolean " + field);
        }
        return node.get(field).booleanValue();
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).isTextual()) {
            throw new IllegalArgumentException("probe event requires text " + field);
        }
        return node.get(field).textValue();
    }

    private static final class MutableRow {
        private final int frame;
        private final int withinFrameOrder;
        private final int gameMode;
        private final int handler;
        private final boolean lag;
        private boolean hblankDeferred;
        private StructuralPhase phase;
        private final List<Submission> submissions = new ArrayList<>();
        private final List<ConsumerPoll> consumerPolls = new ArrayList<>();
        private boolean runPlcCalled;

        private MutableRow(int frame, int withinFrameOrder, StructuralPhase phase, JsonNode node) {
            this.frame = frame;
            this.withinFrameOrder = withinFrameOrder;
            this.phase = phase;
            this.gameMode = requiredInt(node, "game_mode");
            this.handler = requiredInt(node, "interrupt_handler");
            this.lag = requiredBoolean(node, "lag");
            this.hblankDeferred = phase != StructuralPhase.PASSIVE
                    && requiredBoolean(node, "hblank_deferred");
        }

        private MutableRow(int frame, int withinFrameOrder, MutableRow state) {
            this.frame = frame;
            this.withinFrameOrder = withinFrameOrder;
            this.phase = StructuralPhase.ACTION;
            this.gameMode = state.gameMode;
            this.handler = state.handler;
            this.lag = state.lag;
            this.hblankDeferred = state.hblankDeferred;
        }

        private static MutableRow action(int frame, int withinFrameOrder, MutableRow state) {
            return new MutableRow(frame, withinFrameOrder, state);
        }

        private StructuralRow freeze() {
            return new StructuralRow(frame, gameMode, handler, lag, hblankDeferred,
                    submissions, runPlcCalled, consumerPolls, withinFrameOrder, phase);
        }
    }

    public record Vector(Evidence evidence, AnalysisResult analysis) {
    }

    public enum EdgeKind {
        PREPARE,
        SERVICE,
        HBLANK_SERVICE,
        POP,
        EMPTY,
        CONSUMER_BUSY,
        CONSUMER_EMPTY
    }

    public enum SubmissionOperation { APPEND, REPLACE, CLEAR }

    public enum StructuralPhase { PASSIVE, VINT, DEFERRED_VINT, HBLANK, ACTION }

    public record Submission(int sourceAddress, int patternCount, SubmissionOperation operation) {
        public Submission(int sourceAddress, int patternCount) {
            this(sourceAddress, patternCount, SubmissionOperation.APPEND);
        }

        public Submission {
            Objects.requireNonNull(operation, "operation");
            if (operation != SubmissionOperation.CLEAR && (sourceAddress < 0 || patternCount <= 0)) {
                throw new IllegalArgumentException("PLC submissions need a ROM source and positive pattern count");
            }
        }
    }

    public record ConsumerPoll(String consumerId, int withinFrameOrder) {
        public ConsumerPoll {
            Objects.requireNonNull(consumerId, "consumerId");
            if (consumerId.isBlank() || withinFrameOrder < 1) {
                throw new IllegalArgumentException("consumer polls need an id and positive within-frame order");
            }
        }
    }

    public record StructuralRow(
            int rawFrame,
            int gameMode,
            int interruptHandler,
            boolean lag,
            boolean hblankDeferred,
            List<Submission> submissions,
            boolean runPlcCalled,
            List<ConsumerPoll> consumerPolls,
            int withinFrameOrder,
            StructuralPhase phase) {
        public StructuralRow(
                int rawFrame,
                int gameMode,
                int interruptHandler,
                boolean lag,
                boolean hblankDeferred,
                List<Submission> submissions,
                boolean runPlcCalled,
                List<ConsumerPoll> consumerPolls) {
            this(rawFrame, gameMode, interruptHandler, lag, hblankDeferred,
                    submissions, runPlcCalled, consumerPolls, 1, StructuralPhase.VINT);
        }

        public StructuralRow {
            if (rawFrame < 0) {
                throw new IllegalArgumentException("rawFrame must not be negative");
            }
            if (withinFrameOrder < 1) {
                throw new IllegalArgumentException("withinFrameOrder must be positive");
            }
            Objects.requireNonNull(phase, "phase");
            submissions = List.copyOf(submissions);
            consumerPolls = List.copyOf(consumerPolls);
        }

        public StructuralRow withInterruptHandler(int value) {
            return new StructuralRow(rawFrame, gameMode, value, lag, hblankDeferred,
                    submissions, runPlcCalled, consumerPolls, withinFrameOrder, phase);
        }

        public StructuralRow withLag(boolean value) {
            return new StructuralRow(rawFrame, gameMode, interruptHandler, value, hblankDeferred,
                    submissions, runPlcCalled, consumerPolls, withinFrameOrder, phase);
        }

        public StructuralRow withHblankDeferred(boolean value) {
            return new StructuralRow(rawFrame, gameMode, interruptHandler, lag, value,
                    submissions, runPlcCalled, consumerPolls, withinFrameOrder, phase);
        }

        public StructuralRow withRunPlcCalled(boolean value) {
            return new StructuralRow(rawFrame, gameMode, interruptHandler, lag, hblankDeferred,
                    submissions, value, consumerPolls, withinFrameOrder, phase);
        }

        public StructuralRow withConsumerPolls(List<ConsumerPoll> value) {
            return new StructuralRow(rawFrame, gameMode, interruptHandler, lag, hblankDeferred,
                    submissions, runPlcCalled, value, withinFrameOrder, phase);
        }
    }

    public record ObservedEdge(int rawFrame, EdgeKind kind, int sourceAddress, int remainingPatterns) {
    }

    public record PredictedEdge(int rawFrame, EdgeKind kind, int sourceAddress, int remainingPatterns) {
    }

    public record Evidence(
            String game,
            Map<Integer, Integer> handlerBudgets,
            List<StructuralRow> rows,
            List<ObservedEdge> observedEdges) {
        public Evidence {
            Objects.requireNonNull(game, "game");
            handlerBudgets = java.util.Collections.unmodifiableMap(new TreeMap<>(handlerBudgets));
            rows = List.copyOf(rows);
            observedEdges = List.copyOf(observedEdges);
        }

        public Evidence withRows(List<StructuralRow> value) {
            return new Evidence(game, handlerBudgets, value, observedEdges);
        }

        public Evidence withHandlerBudgets(Map<Integer, Integer> value) {
            return new Evidence(game, value, rows, observedEdges);
        }
    }

    public record AnalysisResult(boolean matches, List<PredictedEdge> predictedEdges, String firstMismatch) {
    }

    /**
     * Runs the logical model against an oracle derived from execute-hook
     * diagnostics. Captured patterns-left and queue-empty values are never
     * read by this method.
     */
    public static AnalysisResult analyze(Evidence evidence) {
        List<PredictedEdge> predicted;
        try {
            predicted = predict(evidence);
        } catch (IllegalArgumentException invalidStructure) {
            return new AnalysisResult(false, List.of(), invalidStructure.getMessage());
        }
        List<ObservedEdge> observed = evidence.observedEdges();
        int compared = Math.min(predicted.size(), observed.size());
        for (int index = 0; index < compared; index++) {
            PredictedEdge expected = predicted.get(index);
            ObservedEdge actual = observed.get(index);
            if (!same(expected, actual)) {
                return new AnalysisResult(false, predicted, "edge " + index + ": expected " + expected
                        + " but observed " + actual);
            }
        }
        if (predicted.size() != observed.size()) {
            return new AnalysisResult(false, predicted, "edge count: expected " + predicted.size()
                    + " but observed " + observed.size());
        }
        return new AnalysisResult(true, predicted, "");
    }

    private static boolean same(PredictedEdge predicted, ObservedEdge observed) {
        return predicted.rawFrame() == observed.rawFrame()
                && predicted.kind() == observed.kind()
                && predicted.sourceAddress() == observed.sourceAddress()
                && predicted.remainingPatterns() == observed.remainingPatterns();
    }

    private static List<PredictedEdge> predict(Evidence evidence) {
        var result = new ArrayList<PredictedEdge>();
        var queue = new ArrayDeque<Submission>();
        Submission active = null;
        int remaining = 0;
        int previousFrame = -1;
        int previousOrder = -1;

        for (StructuralRow row : evidence.rows()) {
            if (row.rawFrame() < previousFrame
                    || row.rawFrame() == previousFrame && row.withinFrameOrder() <= previousOrder) {
                throw new IllegalArgumentException(
                        "structural rows must be ordered by raw frame and within-frame order");
            }
            previousFrame = row.rawFrame();
            previousOrder = row.withinFrameOrder();
            if ((row.phase() == StructuralPhase.VINT || row.phase() == StructuralPhase.PASSIVE)
                    && row.hblankDeferred()
                    || (row.phase() == StructuralPhase.HBLANK
                    || row.phase() == StructuralPhase.DEFERRED_VINT) && !row.hblankDeferred()) {
                throw new IllegalArgumentException("structural phase contradicts HBlank state");
            }

            Integer budget = evidence.handlerBudgets().get(row.interruptHandler());
            boolean servicePoint = row.phase() == StructuralPhase.VINT
                    || row.phase() == StructuralPhase.HBLANK;
            if (servicePoint && !row.lag() && budget != null && active != null) {
                remaining -= Math.min(remaining, budget);
                EdgeKind serviceKind = row.phase() == StructuralPhase.HBLANK
                        ? EdgeKind.HBLANK_SERVICE : EdgeKind.SERVICE;
                result.add(new PredictedEdge(row.rawFrame(), serviceKind, active.sourceAddress(), remaining));
                if (remaining == 0) {
                    result.add(new PredictedEdge(row.rawFrame(), EdgeKind.POP, active.sourceAddress(), 0));
                    active = null;
                    if (queue.isEmpty()) {
                        result.add(new PredictedEdge(row.rawFrame(), EdgeKind.EMPTY, 0, 0));
                    }
                }
            }

            for (Submission submission : row.submissions()) {
                if (submission.operation() == SubmissionOperation.CLEAR) {
                    if (active != null) {
                        throw new IllegalArgumentException("retail clear occurred while a decoder was active");
                    }
                    queue.clear();
                } else if (submission.operation() == SubmissionOperation.REPLACE) {
                    if (active != null) {
                        throw new IllegalArgumentException("retail replacement occurred while a decoder was active");
                    }
                    queue.clear();
                    queue.addLast(submission);
                } else {
                    queue.addLast(submission);
                }
            }
            if (row.runPlcCalled() && active == null && !queue.isEmpty()) {
                active = queue.removeFirst();
                remaining = active.patternCount();
                result.add(new PredictedEdge(row.rawFrame(), EdgeKind.PREPARE,
                        active.sourceAddress(), remaining));
            }

            int previousPollOrder = -1;
            for (ConsumerPoll ignored : row.consumerPolls()) {
                if (ignored.withinFrameOrder() <= previousPollOrder) {
                    throw new IllegalArgumentException(
                            "consumer polls must be in increasing within-frame order");
                }
                previousPollOrder = ignored.withinFrameOrder();
                if (active == null && queue.isEmpty()) {
                    result.add(new PredictedEdge(row.rawFrame(), EdgeKind.CONSUMER_EMPTY, 0, 0));
                } else if (active != null) {
                    result.add(new PredictedEdge(row.rawFrame(), EdgeKind.CONSUMER_BUSY,
                            active.sourceAddress(), remaining));
                } else {
                    Submission head = queue.getFirst();
                    result.add(new PredictedEdge(row.rawFrame(), EdgeKind.CONSUMER_BUSY,
                            head.sourceAddress(), 0));
                }
            }
        }
        return List.copyOf(result);
    }
}
