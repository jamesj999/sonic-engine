package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable capture identity and cycle boundary data from the first JSONL line. */
public record AudioParityMetadata(
        String schema,
        String capture,
        int cycleStart,
        int period,
        int terminalRecordCount,
        String romSha1,
        String romCrc32,
        JsonNode details) {
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern CRC32 = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:^|[\\s\\\"'=])(?:/(?:$|[^/\\s][^\\s]*)|\\\\\\\\[^\\\\/\\s]+[\\\\/][^\\s]+"
                    + "|[a-z]:[\\\\/][^\\s]+|file:/+[^\\s]+)");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            ".*\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:[Zz]|[+-]\\d{2}:?\\d{2})?.*");

    public AudioParityMetadata {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(romSha1, "romSha1");
        Objects.requireNonNull(romCrc32, "romCrc32");
        if (!AudioParitySchema.VERSION.equals(schema)) {
            throw new IllegalArgumentException("unknown audio parity schema: " + schema);
        }
        boolean sfxCapture = capture.equals(AudioParitySchema.SFX_REFERENCE_CAPTURE)
                || capture.equals(AudioParitySchema.SFX_OPENGGF_CAPTURE);
        boolean gameplayCapture = capture.equals(AudioParitySchema.GAMEPLAY_REFERENCE_CAPTURE)
                || capture.equals(AudioParitySchema.GAMEPLAY_OPENGGF_CAPTURE);
        boolean runWindowCapture = capture.equals(AudioParitySchema.RUN_WINDOW_REFERENCE_CAPTURE)
                || capture.equals(AudioParitySchema.RUN_WINDOW_OPENGGF_CAPTURE);
        if (!capture.equals(AudioParitySchema.REFERENCE_CAPTURE)
                && !capture.equals(AudioParitySchema.OPENGGF_CAPTURE)
                && !sfxCapture && !gameplayCapture && !runWindowCapture) {
            throw new IllegalArgumentException("unknown audio parity capture kind: " + capture);
        }
        if (sfxCapture || gameplayCapture || runWindowCapture) {
            // The SFX, gameplay and per-song window captures are bounded by
            // their movie/frame budget or their closing BGM dispatch, not a
            // recurrence proof.
            if (cycleStart != 0 || period != 0 || terminalRecordCount <= 0
                    || terminalRecordCount > AudioParitySchema.MAX_INVOCATIONS) {
                throw new IllegalArgumentException(
                        "SFX/gameplay/run-window capture requires cycle_start 0, period 0, "
                                + "and a bounded terminal_record_count");
            }
        } else {
            long expectedTerminal = (long) cycleStart + 2L * period + 1L;
            if (cycleStart < 0 || period <= 0 || terminalRecordCount <= 0
                    || expectedTerminal != terminalRecordCount
                    || terminalRecordCount > AudioParitySchema.MAX_INVOCATIONS) {
                throw new IllegalArgumentException("cycle_start, period, and terminal_record_count are out of range");
            }
        }
        romSha1 = romSha1.toLowerCase();
        romCrc32 = romCrc32.toLowerCase();
        if (!SHA1.matcher(romSha1).matches() || !CRC32.matcher(romCrc32).matches()) {
            throw new IllegalArgumentException("ROM SHA-1/CRC32 identity is malformed");
        }
        if (!romSha1.equals(AudioParitySchema.S1_REV01_SHA1)
                || !romCrc32.equals(AudioParitySchema.S1_REV01_CRC32)) {
            throw new IllegalArgumentException("audio parity v1 requires the pinned S1 World REV01 ROM");
        }
        rejectHostIdentity(capture);
        details = details == null ? JsonNodeFactory.instance.objectNode() : details.deepCopy();
        if (!details.isObject()) {
            throw new IllegalArgumentException("metadata details must be an object");
        }
        validatePortable(details);
    }

    public static AudioParityMetadata openGgf(int cycleStart, int period, int terminalRecordCount,
            String romSha1, String romCrc32) {
        return new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.OPENGGF_CAPTURE,
                cycleStart, period,
                terminalRecordCount, romSha1, romCrc32, JsonNodeFactory.instance.objectNode());
    }

    public static AudioParityMetadata openGgfSfx(int terminalRecordCount, String romSha1,
            String romCrc32) {
        return new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.SFX_OPENGGF_CAPTURE, 0, 0, terminalRecordCount, romSha1,
                romCrc32, JsonNodeFactory.instance.objectNode());
    }

    public static AudioParityMetadata openGgfGameplay(int terminalRecordCount, String romSha1,
            String romCrc32) {
        return new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.GAMEPLAY_OPENGGF_CAPTURE, 0, 0, terminalRecordCount, romSha1,
                romCrc32, JsonNodeFactory.instance.objectNode());
    }

    /**
     * OpenGGF side of a per-song run window. Like the other OpenGGF factories
     * it carries no details: the window's identity, including which song it
     * replays, is the reference's to state, and the host reads it from there.
     */
    public static AudioParityMetadata openGgfRunWindow(int terminalRecordCount, String romSha1,
            String romCrc32) {
        return new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.RUN_WINDOW_OPENGGF_CAPTURE, 0, 0, terminalRecordCount, romSha1,
                romCrc32, JsonNodeFactory.instance.objectNode());
    }

    @Override
    public JsonNode details() {
        return details.deepCopy();
    }

    private static void validatePortable(JsonNode node) {
        if (node.isTextual()) {
            rejectHostIdentity(node.textValue());
        } else if (node.isContainerNode()) {
            node.forEach(AudioParityMetadata::validatePortable);
        }
    }

    private static void rejectHostIdentity(String value) {
        if (ABSOLUTE_PATH.matcher(value).find()) {
            throw new IllegalArgumentException("normalized metadata contains an absolute path");
        }
        if (ISO_TIMESTAMP.matcher(value).matches()) {
            throw new IllegalArgumentException("normalized metadata contains a timestamp");
        }
    }
}
