package com.openggf.trace.timing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.RecordedOrdinalSpan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict loader for the run-level interstitial companion to the per-segment
 * hardware-timing stream.
 *
 * <p>The two streams are field-disjoint on purpose. A per-segment record names
 * a represented row through {@code raw_frame}; an interstitial record names no
 * row at all, so it carries {@code origin}, {@code after_segment},
 * {@code after_segment_index} and a {@code bk2_frame} that exists purely as
 * provenance for whoever has to re-derive the capture. Nothing downstream
 * reads {@code bk2_frame} or {@code after_segment}: the only load-bearing
 * fields are the boundary index and the per-kind ordinals, which is what keeps
 * this off a frame index.
 *
 * <p>A missing file is not an error. Every fixture recorded before this stream
 * existed loads as {@link HardwareTimingInterstitialSpans#empty()} and takes
 * exactly the handoff path it took before.
 */
public final class HardwareTimingInterstitialStreamLoader {

    private static final String FILE_NAME = "hardware_timing_interstitial.jsonl";
    private static final String EVENT_NAME = "hardware_work_completed";
    private static final String ORIGIN_NAME = "interstitial";
    private static final Set<String> EVENT_FIELDS = Set.of(
            "event", "origin", "after_segment", "after_segment_index", "bk2_frame",
            "boundary", "kind", "ordinal", "submission_fingerprint");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

    /**
     * The index the recorder writes for completions observed before the run's
     * first segment opens. Production never submits that work either, but the
     * run's one-time identity base already accounts for it, so this loader
     * parses and validates the records and then keeps them out of the handoff
     * index rather than rebasing a second time over the same gap.
     */
    private static final int PRE_RUN_BOUNDARY_INDEX = -1;

    private HardwareTimingInterstitialStreamLoader() {
    }

    public static HardwareTimingInterstitialSpans load(Path runDirectory) throws IOException {
        Path path = runDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            return HardwareTimingInterstitialSpans.empty();
        }
        if (Files.size(path) == 0) {
            return HardwareTimingInterstitialSpans.empty();
        }
        String content = decodeUtf8(path);
        if (!content.endsWith("\n") || content.indexOf('\r') >= 0) {
            throw rejected(path, FILE_NAME + " must use LF-terminated UTF-8 lines");
        }

        Map<Integer, Map<HardwareWorkKind, RecordedOrdinalSpan>> spans = new LinkedHashMap<>();
        Map<HardwareWorkKind, Long> lastOrdinalByKind = new EnumMap<>(HardwareWorkKind.class);
        Map<Integer, String> nameByIndex = new HashMap<>();
        Set<String> identities = new HashSet<>();
        int lastBoundaryIndex = Integer.MIN_VALUE;

        String[] lines = content.split("\n", -1);
        for (int index = 0; index < lines.length - 1; index++) {
            String line = lines[index];
            int lineNumber = index + 1;
            if (line.isEmpty() || !line.equals(line.trim())) {
                throw rejected(path, "line " + lineNumber + " must be one compact JSON event");
            }
            Record record = parse(path, lineNumber, line);

            if (record.boundaryIndex() < lastBoundaryIndex) {
                throw rejected(path, "line " + lineNumber
                        + " after_segment_index moved backward: " + lastBoundaryIndex
                        + " -> " + record.boundaryIndex());
            }
            lastBoundaryIndex = record.boundaryIndex();
            String knownName = nameByIndex.putIfAbsent(record.boundaryIndex(), String.valueOf(record.segmentName()));
            if (knownName != null && !knownName.equals(String.valueOf(record.segmentName()))) {
                throw rejected(path, "line " + lineNumber + " renames after_segment_index "
                        + record.boundaryIndex() + ": " + knownName + " -> " + record.segmentName());
            }
            if (!identities.add(record.kind() + "#" + record.ordinal())) {
                throw rejected(path, "line " + lineNumber + " repeats identity "
                        + record.kind() + "#" + record.ordinal());
            }
            Long lastOrdinal = lastOrdinalByKind.put(record.kind(), record.ordinal());
            if (lastOrdinal != null && record.ordinal() <= lastOrdinal) {
                throw rejected(path, "line " + lineNumber + " ordinal must increase per kind "
                        + record.kind() + ": " + lastOrdinal + " -> " + record.ordinal());
            }

            Map<HardwareWorkKind, RecordedOrdinalSpan> boundarySpans =
                    spans.computeIfAbsent(record.boundaryIndex(),
                            ignored -> new EnumMap<>(HardwareWorkKind.class));
            RecordedOrdinalSpan open = boundarySpans.get(record.kind());
            if (open == null) {
                boundarySpans.put(record.kind(),
                        new RecordedOrdinalSpan(record.ordinal(), record.ordinal()));
            } else {
                // A span is the ledger's own consumption of a contiguous block,
                // so a hole inside one boundary would mean the recorder lost a
                // completion. Rebasing over a hole would hide exactly the
                // ordinal skew this stream exists to close.
                if (record.ordinal() != open.nextOrdinal()) {
                    throw rejected(path, "line " + lineNumber + " leaves a hole in the "
                            + record.kind() + " span after segment " + record.boundaryIndex()
                            + ": expected ordinal " + open.nextOrdinal()
                            + ", found " + record.ordinal());
                }
                boundarySpans.put(record.kind(),
                        new RecordedOrdinalSpan(open.firstOrdinal(), record.ordinal()));
            }
        }

        spans.remove(PRE_RUN_BOUNDARY_INDEX);
        return new HardwareTimingInterstitialSpans(spans);
    }

    private static Record parse(Path path, int lineNumber, String line) throws IOException {
        JsonNode node;
        try (JsonParser parser = MAPPER.getFactory().createParser(line)) {
            node = MAPPER.readTree(parser);
            if (parser.nextToken() != null) {
                throw rejected(path, "line " + lineNumber + " has trailing JSON values");
            }
        } catch (JsonProcessingException e) {
            String detail = e.getOriginalMessage() != null
                    && e.getOriginalMessage().contains("Duplicate")
                    ? "has duplicate JSON field"
                    : "is malformed JSON";
            throw rejected(path, "line " + lineNumber + " " + detail);
        }
        if (node == null || !node.isObject()) {
            throw rejected(path, "line " + lineNumber + " must be a JSON object");
        }
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(EVENT_FIELDS)) {
            Set<String> unexpected = new HashSet<>(fields);
            unexpected.removeAll(EVENT_FIELDS);
            Set<String> missing = new HashSet<>(EVENT_FIELDS);
            missing.removeAll(fields);
            throw rejected(path, "line " + lineNumber + " has unknown or missing field "
                    + (!unexpected.isEmpty() ? unexpected.iterator().next() : missing.iterator().next()));
        }
        requireText(path, lineNumber, node, "event", EVENT_NAME);
        requireText(path, lineNumber, node, "origin", ORIGIN_NAME);

        int boundaryIndex = requireInt(path, lineNumber, node, "after_segment_index");
        if (boundaryIndex < PRE_RUN_BOUNDARY_INDEX) {
            throw rejected(path, "line " + lineNumber
                    + " has invalid after_segment_index " + boundaryIndex);
        }
        JsonNode name = node.get("after_segment");
        String segmentName;
        if (boundaryIndex == PRE_RUN_BOUNDARY_INDEX) {
            if (!name.isNull()) {
                throw rejected(path, "line " + lineNumber
                        + " must name no segment before the run opens");
            }
            segmentName = null;
        } else {
            if (!name.isTextual() || name.textValue().isEmpty()) {
                throw rejected(path, "line " + lineNumber + " has invalid after_segment");
            }
            segmentName = name.textValue();
        }
        // Provenance only: validated for shape so a malformed capture is
        // rejected, then discarded. Nothing keys on it.
        int bk2Frame = requireInt(path, lineNumber, node, "bk2_frame");
        if (bk2Frame < 0) {
            throw rejected(path, "line " + lineNumber + " has invalid bk2_frame " + bk2Frame);
        }

        HardwareServiceBoundary boundary =
                parseBoundary(path, lineNumber, requireText(path, lineNumber, node, "boundary", null));
        HardwareWorkKind kind =
                parseKind(path, lineNumber, requireText(path, lineNumber, node, "kind", null));
        if (kind == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                && boundary != HardwareServiceBoundary.PRE_MAIN_LOOP) {
            throw rejected(path, "line " + lineNumber
                    + " direct completion kind requires pre_main_loop boundary");
        }
        long ordinal = requireOrdinal(path, lineNumber, node);
        String fingerprint = requireText(path, lineNumber, node, "submission_fingerprint", null);
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw rejected(path, "line " + lineNumber + " has invalid submission_fingerprint");
        }
        return new Record(boundaryIndex, segmentName, kind, ordinal);
    }

    private static String requireText(Path path, int line, JsonNode node, String field, String exact)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || (exact != null && !exact.equals(value.textValue()))) {
            throw rejected(path, "line " + line + " has invalid " + field);
        }
        return value.textValue();
    }

    private static int requireInt(Path path, int line, JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw rejected(path, "line " + line + " has invalid " + field);
        }
        return value.intValue();
    }

    private static long requireOrdinal(Path path, int line, JsonNode node) throws IOException {
        JsonNode value = node.get("ordinal");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw rejected(path, "line " + line + " has invalid ordinal");
        }
        return value.longValue();
    }

    private static HardwareServiceBoundary parseBoundary(Path path, int line, String wireName)
            throws IOException {
        try {
            return HardwareServiceBoundary.fromWireName(wireName);
        } catch (IllegalArgumentException e) {
            throw rejected(path, "line " + line + " has invalid boundary");
        }
    }

    private static HardwareWorkKind parseKind(Path path, int line, String wireName) throws IOException {
        try {
            return HardwareWorkKind.fromWireName(wireName);
        } catch (IllegalArgumentException e) {
            throw rejected(path, "line " + line + " has invalid kind");
        }
    }

    private static String decodeUtf8(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw rejected(path, FILE_NAME + " must be valid UTF-8");
        }
    }

    private static IOException rejected(Path path, String reason) {
        return new IOException(path.getFileName() + ": " + reason);
    }

    private record Record(
            int boundaryIndex, String segmentName, HardwareWorkKind kind, long ordinal) {
    }
}
