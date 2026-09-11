package com.openggf.tools.audio.timeline;

import java.util.List;
import java.util.Objects;

/** Deterministic first-difference result for two validated gameplay-audio streams. */
public record S1GameplayAudioTimelineReport(Kind kind, String location, String detail,
        List<String> referenceContext, List<String> openGgfContext) {
    public enum Kind {
        MATCH,
        METADATA_MISMATCH,
        BASELINE_MISMATCH,
        REQUEST_MISSING,
        REQUEST_EXTRA,
        REQUEST_ORDINAL_MISMATCH,
        REQUEST_CLASS_MISMATCH,
        REQUEST_ID_MISMATCH,
        ADMISSION_MISSING,
        ADMISSION_EXTRA,
        ADMISSION_ORDINAL_MISMATCH,
        ADMISSION_CLASS_MISMATCH,
        ADMISSION_ID_MISMATCH,
        REQUEST_ROLE_MISMATCH,
        ROLE_ORDER_MISMATCH,
        ROLE_ACQUIRED_MISMATCH,
        ROLE_DISPLACED_OWNER_MISMATCH,
        ROLE_FINAL_OWNER_MISMATCH,
        FINAL_OWNER_MISMATCH,
        RESTORATION_MISMATCH,
        TERMINAL_MISMATCH,
        CAPTURE_FAILURE
    }

    public S1GameplayAudioTimelineReport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(detail, "detail");
        referenceContext = List.copyOf(referenceContext);
        openGgfContext = List.copyOf(openGgfContext);
    }

    public boolean matches() {
        return kind == Kind.MATCH;
    }

    /** A valid stream disagreement, rather than a malformed or untrusted capture. */
    public boolean isParityMismatch() {
        return kind != Kind.MATCH && kind != Kind.METADATA_MISMATCH && kind != Kind.CAPTURE_FAILURE;
    }

    public String toHumanText() {
        if (matches()) {
            return "S1 GHZ1 gameplay-audio timeline: MATCH";
        }
        StringBuilder text = new StringBuilder("S1 GHZ1 gameplay-audio timeline: ")
                .append(kind).append(" at ").append(location).append('\n').append(detail);
        appendContext(text, "Reference", referenceContext);
        appendContext(text, "OpenGGF", openGgfContext);
        return text.toString();
    }

    public String toJsonSummary() {
        return "{\"status\":\"" + (matches() ? "match" : "mismatch") + "\",\"kind\":\"" + kind
                + "\",\"location\":\"" + escape(location) + "\",\"detail\":\"" + escape(detail)
                + "\",\"reference_context\":" + jsonArray(referenceContext) + ",\"openggf_context\":"
                + jsonArray(openGgfContext) + "}";
    }

    static S1GameplayAudioTimelineReport match() {
        return new S1GameplayAudioTimelineReport(Kind.MATCH, "complete stream", "all semantic records match",
                List.of(), List.of());
    }

    static S1GameplayAudioTimelineReport failure(Kind kind, String location, String detail,
            List<String> referenceContext, List<String> openGgfContext) {
        return new S1GameplayAudioTimelineReport(kind, location, detail, referenceContext, openGgfContext);
    }

    private static void appendContext(StringBuilder text, String side, List<String> records) {
        if (!records.isEmpty()) {
            text.append('\n').append(side).append(" context:");
            records.forEach(record -> text.append('\n').append("  ").append(record));
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + escape(value) + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
