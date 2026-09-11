package com.openggf.tools.audio.parity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Validation-first, no-realignment comparison of normalized S1 audio capture streams. */
public final class AudioParityComparator {
    private static final int CONTEXT_LIMIT = 8;
    private static final List<Field> TRACK_FIELDS = List.of(
            new Field("role", AudioParityTrackState::role),
            new Field("hardware", AudioParityTrackState::hardware),
            new Field("active", AudioParityTrackState::active),
            new Field("base_frequency", AudioParityTrackState::baseFrequency),
            new Field("detune", AudioParityTrackState::detune),
            new Field("do_not_attack", AudioParityTrackState::doNotAttack),
            new Field("duration", AudioParityTrackState::duration),
            new Field("duration_reload", AudioParityTrackState::durationReload),
            new Field("envelope_cursor", AudioParityTrackState::envelopeCursor),
            new Field("loop_counters", AudioParityTrackState::loopCounters),
            new Field("modulation_enabled", AudioParityTrackState::modulationEnabled),
            new Field("overridden", AudioParityTrackState::overridden),
            new Field("pan", AudioParityTrackState::pan),
            new Field("ams", AudioParityTrackState::ams),
            new Field("fms", AudioParityTrackState::fms),
            new Field("return_stack", AudioParityTrackState::returnStack),
            new Field("sequence_position", AudioParityTrackState::sequencePosition),
            new Field("transpose", AudioParityTrackState::transpose),
            new Field("voice_or_envelope", AudioParityTrackState::voiceOrEnvelope),
            new Field("volume", AudioParityTrackState::volume));

    private AudioParityComparator() {
    }

    /**
     * Validates each JSONL stream completely before comparing its first gating difference. The
     * second pass remains streaming and therefore never retains a complete music stream in memory.
     */
    public static AudioParityReport compare(Path referencePath, Path openGgfPath) {
        return compare(referencePath, openGgfPath, () -> { });
    }

    static AudioParityReport compare(Path referencePath, Path openGgfPath,
            BetweenPassAction betweenPasses) {
        AudioParityMetadata reference;
        AudioParityMetadata openGgf;
        try {
            reference = readMetadata(referencePath);
        } catch (IOException | RuntimeException error) {
            return captureFailure(AudioParityReport.Side.REFERENCE, "reference_stream", error);
        }
        try {
            openGgf = readMetadata(openGgfPath);
        } catch (IOException | RuntimeException error) {
            return captureFailure(AudioParityReport.Side.OPENGGF, "openggf_stream", error);
        }
        AudioParityReport metadata = compareMetadata(reference, openGgf);
        if (metadata != null) {
            return metadata;
        }
        String referenceDigest;
        String openGgfDigest;
        try {
            referenceDigest = digest(referencePath);
            AudioParityMetadata validated = AudioParityJsonl.read(referencePath, ignored -> { });
            if (!validated.equals(reference)) {
                return sourceChanged(AudioParityReport.Side.REFERENCE);
            }
        } catch (AudioParityJsonl.ValidationException error) {
            return streamFailure(AudioParityReport.Side.REFERENCE, error);
        } catch (IOException | RuntimeException error) {
            return captureFailure(AudioParityReport.Side.REFERENCE, "reference_stream", error);
        }
        try {
            openGgfDigest = digest(openGgfPath);
            AudioParityMetadata validated = AudioParityJsonl.read(openGgfPath, ignored -> { });
            if (!validated.equals(openGgf)) {
                return sourceChanged(AudioParityReport.Side.OPENGGF);
            }
        } catch (AudioParityJsonl.ValidationException error) {
            return streamFailure(AudioParityReport.Side.OPENGGF, error);
        } catch (IOException | RuntimeException error) {
            return captureFailure(AudioParityReport.Side.OPENGGF, "openggf_stream", error);
        }
        try {
            betweenPasses.run();
        } catch (IOException error) {
            return captureFailure(null, "between_passes", error);
        }
        try (DigestingReader referenceInput = new DigestingReader(referencePath);
                DigestingReader openGgfInput = new DigestingReader(openGgfPath)) {
            AudioParityMetadata secondReference = null;
            AudioParityMetadata secondOpenGgf = null;
            AudioParityReport validationFailure = null;
            try {
                secondReference = AudioParityJsonl.parseMetadata(referenceInput.readLine());
            } catch (RuntimeException error) {
                validationFailure = captureFailure(AudioParityReport.Side.REFERENCE,
                        "reference_stream", error);
            }
            try {
                secondOpenGgf = AudioParityJsonl.parseMetadata(openGgfInput.readLine());
            } catch (RuntimeException error) {
                if (validationFailure == null) {
                    validationFailure = captureFailure(AudioParityReport.Side.OPENGGF,
                            "openggf_stream", error);
                }
            }
            AudioParityReport firstDifference = null;
            int referenceObserved = 0;
            int openGgfObserved = 0;
            for (int ordinal = 0; ordinal < reference.terminalRecordCount(); ordinal++) {
                AudioParityTick referenceTick = null;
                AudioParityTick openGgfTick = null;
                String referenceLine = referenceInput.readLine();
                String openGgfLine = openGgfInput.readLine();
                if (referenceLine != null) {
                    referenceObserved++;
                    try {
                        referenceTick = AudioParityJsonl.parseTick(referenceLine);
                    } catch (RuntimeException error) {
                        if (validationFailure == null) {
                            validationFailure = captureFailure(AudioParityReport.Side.REFERENCE,
                                    "reference_stream", error);
                        }
                    }
                }
                if (openGgfLine != null) {
                    openGgfObserved++;
                    try {
                        openGgfTick = AudioParityJsonl.parseTick(openGgfLine);
                    } catch (RuntimeException error) {
                        if (validationFailure == null) {
                            validationFailure = captureFailure(AudioParityReport.Side.OPENGGF,
                                    "openggf_stream", error);
                        }
                    }
                }
                if (referenceTick != null && referenceTick.ordinal() != ordinal && validationFailure == null) {
                    validationFailure = integrityDifference(AudioParityReport.Kind.ORDINAL_MISMATCH,
                            AudioParityReport.Side.REFERENCE, ordinal, referenceTick.ordinal(), ordinal);
                }
                if (openGgfTick != null && openGgfTick.ordinal() != ordinal && validationFailure == null) {
                    validationFailure = integrityDifference(AudioParityReport.Kind.ORDINAL_MISMATCH,
                            AudioParityReport.Side.OPENGGF, ordinal, openGgfTick.ordinal(), ordinal);
                }
                if (referenceTick != null && openGgfTick != null && firstDifference == null) {
                    firstDifference = compareTick(referenceTick, openGgfTick, ordinal);
                }
            }
            while (referenceInput.readLine() != null) {
                referenceObserved++;
            }
            while (openGgfInput.readLine() != null) {
                openGgfObserved++;
            }
            String secondReferenceDigest = referenceInput.digest();
            String secondOpenGgfDigest = openGgfInput.digest();
            if (!secondReferenceDigest.equals(referenceDigest)
                    || secondReference != null && !secondReference.equals(reference)) {
                return sourceChanged(AudioParityReport.Side.REFERENCE);
            }
            if (!secondOpenGgfDigest.equals(openGgfDigest)
                    || secondOpenGgf != null && !secondOpenGgf.equals(openGgf)) {
                return sourceChanged(AudioParityReport.Side.OPENGGF);
            }
            if (validationFailure == null && referenceObserved != reference.terminalRecordCount()) {
                validationFailure = integrityDifference(AudioParityReport.Kind.TICK_COUNT_MISMATCH,
                        AudioParityReport.Side.REFERENCE, reference.terminalRecordCount(),
                        referenceObserved, null);
            }
            if (validationFailure == null && openGgfObserved != openGgf.terminalRecordCount()) {
                validationFailure = integrityDifference(AudioParityReport.Kind.TICK_COUNT_MISMATCH,
                        AudioParityReport.Side.OPENGGF, openGgf.terminalRecordCount(),
                        openGgfObserved, null);
            }
            if (validationFailure != null) {
                return validationFailure;
            }
            return firstDifference == null ? match(reference.terminalRecordCount()) : firstDifference;
        } catch (IOException | RuntimeException error) {
            return captureFailure(null, "validated_stream_changed", error);
        }
    }

    private static AudioParityMetadata readMetadata(Path path) throws IOException {
        try (BufferedReader input = AudioParityJsonl.openReader(path)) {
            String line = input.readLine();
            if (line == null || line.isBlank()) {
                throw new IllegalArgumentException("missing capture metadata line");
            }
            return AudioParityJsonl.parseMetadata(line);
        }
    }

    /** In-memory entry point for compact capture producers and mutation tests. */
    public static AudioParityReport compare(AudioParityMetadata referenceMetadata,
            List<AudioParityTick> referenceTicks, AudioParityMetadata openGgfMetadata,
            List<AudioParityTick> openGgfTicks) {
        Objects.requireNonNull(referenceTicks, "referenceTicks");
        Objects.requireNonNull(openGgfTicks, "openGgfTicks");
        AudioParityReport metadata = compareMetadata(referenceMetadata, openGgfMetadata);
        if (metadata != null) {
            return metadata;
        }
        if (referenceTicks.size() != referenceMetadata.terminalRecordCount()) {
            return integrityDifference(AudioParityReport.Kind.TICK_COUNT_MISMATCH,
                    AudioParityReport.Side.REFERENCE, referenceMetadata.terminalRecordCount(),
                    referenceTicks.size(), null);
        }
        if (openGgfTicks.size() != openGgfMetadata.terminalRecordCount()) {
            return integrityDifference(AudioParityReport.Kind.TICK_COUNT_MISMATCH,
                    AudioParityReport.Side.OPENGGF, openGgfMetadata.terminalRecordCount(),
                    openGgfTicks.size(), null);
        }
        for (int ordinal = 0; ordinal < referenceTicks.size(); ordinal++) {
            AudioParityTick referenceTick = referenceTicks.get(ordinal);
            if (referenceTick.ordinal() != ordinal) {
                return integrityDifference(AudioParityReport.Kind.ORDINAL_MISMATCH,
                        AudioParityReport.Side.REFERENCE, ordinal, referenceTick.ordinal(), ordinal);
            }
        }
        for (int ordinal = 0; ordinal < openGgfTicks.size(); ordinal++) {
            AudioParityTick openGgfTick = openGgfTicks.get(ordinal);
            if (openGgfTick.ordinal() != ordinal) {
                return integrityDifference(AudioParityReport.Kind.ORDINAL_MISMATCH,
                        AudioParityReport.Side.OPENGGF, ordinal, openGgfTick.ordinal(), ordinal);
            }
        }
        for (int ordinal = 0; ordinal < referenceTicks.size(); ordinal++) {
            AudioParityReport difference = compareTick(referenceTicks.get(ordinal), openGgfTicks.get(ordinal),
                    ordinal);
            if (difference != null) {
                return difference;
            }
        }
        return match(referenceTicks.size());
    }

    private static AudioParityReport compareMetadata(AudioParityMetadata reference,
            AudioParityMetadata openGgf) {
        if (reference == null || openGgf == null) {
            return difference(AudioParityReport.Kind.CAPTURE_FAILURE, 0, null, null, null,
                    "metadata", String.valueOf(reference), String.valueOf(openGgf), null);
        }
        String expectedOpenGgfKind;
        if (AudioParitySchema.REFERENCE_CAPTURE.equals(reference.capture())) {
            expectedOpenGgfKind = AudioParitySchema.OPENGGF_CAPTURE;
        } else if (AudioParitySchema.SFX_REFERENCE_CAPTURE.equals(reference.capture())) {
            expectedOpenGgfKind = AudioParitySchema.SFX_OPENGGF_CAPTURE;
        } else if (AudioParitySchema.GAMEPLAY_REFERENCE_CAPTURE.equals(reference.capture())) {
            expectedOpenGgfKind = AudioParitySchema.GAMEPLAY_OPENGGF_CAPTURE;
        } else if (AudioParitySchema.RUN_WINDOW_REFERENCE_CAPTURE.equals(reference.capture())) {
            expectedOpenGgfKind = AudioParitySchema.RUN_WINDOW_OPENGGF_CAPTURE;
        } else {
            return metadataField("capture",
                    AudioParitySchema.REFERENCE_CAPTURE + " or " + AudioParitySchema.SFX_REFERENCE_CAPTURE
                            + " or " + AudioParitySchema.GAMEPLAY_REFERENCE_CAPTURE
                            + " or " + AudioParitySchema.RUN_WINDOW_REFERENCE_CAPTURE,
                    reference.capture());
        }
        AudioParityReport result = metadataField("capture", expectedOpenGgfKind, openGgf.capture());
        if (result != null) {
            return result;
        }
        result = metadataField("schema", reference.schema(), openGgf.schema());
        if (result != null) {
            return result;
        }
        result = metadataField("rom_sha1", reference.romSha1(), openGgf.romSha1());
        if (result != null) {
            return result;
        }
        result = metadataField("rom_crc32", reference.romCrc32(), openGgf.romCrc32());
        if (result != null) {
            return result;
        }
        result = metadataField("cycle_start", reference.cycleStart(), openGgf.cycleStart());
        if (result != null) {
            return result;
        }
        result = metadataField("period", reference.period(), openGgf.period());
        if (result != null) {
            return result;
        }
        return metadataField("terminal_record_count", reference.terminalRecordCount(),
                openGgf.terminalRecordCount());
    }

    private static AudioParityReport metadataField(String field, Object reference, Object openGgf) {
        return Objects.equals(reference, openGgf) ? null
                : difference(AudioParityReport.Kind.METADATA_MISMATCH, 0, null, null, null, field,
                        String.valueOf(reference), String.valueOf(openGgf), null);
    }

    private static AudioParityReport compareTick(AudioParityTick reference, AudioParityTick openGgf,
            int ticksCompared) {
        AudioParityReport global = compareGlobal(reference.global(), openGgf.global(), reference.ordinal(),
                ticksCompared);
        if (global != null) {
            return global;
        }
        AudioParityReport dispatches = stateField(AudioParityReport.Kind.DISPATCH_MISMATCH,
                reference.ordinal(), ticksCompared, "GLOBAL", "dispatches",
                reference.dispatches(), openGgf.dispatches());
        if (dispatches != null) {
            return dispatches;
        }
        for (int index = 0; index < AudioParitySchema.ROLES.size(); index++) {
            AudioParityReport track = compareTrack(reference.tracks().get(index), openGgf.tracks().get(index),
                    reference.ordinal(), ticksCompared);
            if (track != null) {
                return track;
            }
        }
        return compareEvents(reference, openGgf, ticksCompared);
    }

    private static AudioParityReport compareGlobal(AudioParityTick.GlobalState reference,
            AudioParityTick.GlobalState openGgf, int ordinal, int ticksCompared) {
        AudioParityReport result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "fade_active", reference.fadeActive(), openGgf.fadeActive());
        if (result != null) {
            return result;
        }
        result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "fade_direction", reference.fadeDirection(),
                openGgf.fadeDirection());
        if (result != null) {
            return result;
        }
        result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "fade_delay", reference.fadeDelay(), openGgf.fadeDelay());
        if (result != null) {
            return result;
        }
        result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "fade_steps", reference.fadeSteps(), openGgf.fadeSteps());
        if (result != null) {
            return result;
        }
        result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "speed_up", reference.speedUp(), openGgf.speedUp());
        if (result != null) {
            return result;
        }
        result = stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "tempo_reload", reference.tempoReload(),
                openGgf.tempoReload());
        if (result != null) {
            return result;
        }
        return stateField(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH,
                ordinal, ticksCompared, "GLOBAL", "tempo_timeout", reference.tempoTimeout(),
                openGgf.tempoTimeout());
    }

    private static AudioParityReport compareTrack(AudioParityTrackState reference,
            AudioParityTrackState openGgf, int ordinal, int ticksCompared) {
        String role = reference.role();
        for (Field field : TRACK_FIELDS) {
            Object referenceValue = field.value().apply(reference);
            Object openGgfValue = field.value().apply(openGgf);
            AudioParityReport result = stateField(AudioParityReport.Kind.TRACK_STATE_MISMATCH,
                    ordinal, ticksCompared, role, field.name(), referenceValue, openGgfValue);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static AudioParityReport stateField(AudioParityReport.Kind kind, int ordinal,
            int ticksCompared, String role, String field, Object reference, Object openGgf) {
        return Objects.equals(reference, openGgf) ? null
                : difference(kind, ticksCompared, ordinal, null, role, field,
                        String.valueOf(reference), String.valueOf(openGgf), null);
    }

    private static AudioParityReport compareEvents(AudioParityTick referenceTick,
            AudioParityTick openGgfTick, int ticksCompared) {
        List<AudioParityChipWrite> reference = referenceTick.events();
        List<AudioParityChipWrite> openGgf = openGgfTick.events();
        int common = Math.min(reference.size(), openGgf.size());
        int index = 0;
        while (index < common && reference.get(index).equals(openGgf.get(index))) {
            index++;
        }
        if (index == reference.size() && index == openGgf.size()) {
            return null;
        }

        AudioParityReport.Kind kind;
        Integer referenceFocus = index < reference.size() ? index : null;
        Integer openGgfFocus = index < openGgf.size() ? index : null;
        if (isAdjacentSwap(reference, openGgf, index)) {
            kind = AudioParityReport.Kind.EVENT_REORDERED;
        } else if (isSingleDeletion(reference, openGgf, index)) {
            kind = AudioParityReport.Kind.EVENT_MISSING;
            openGgfFocus = null;
        } else if (isSingleDeletion(openGgf, reference, index)) {
            kind = AudioParityReport.Kind.EVENT_EXTRA;
            referenceFocus = null;
        } else {
            kind = AudioParityReport.Kind.EVENT_VALUE_DIFFERENT;
        }
        String referenceValue = referenceFocus == null ? "<missing>" : reference.get(referenceFocus).toString();
        String openGgfValue = openGgfFocus == null ? "<missing>" : openGgf.get(openGgfFocus).toString();
        AudioParityReport.EventContext context = context(reference, openGgf, index,
                referenceFocus != null, openGgfFocus != null);
        AudioParityReport.StateContext state = new AudioParityReport.StateContext(
                referenceTick.global(), active(referenceTick.tracks()),
                openGgfTick.global(), active(openGgfTick.tracks()));
        return new AudioParityReport(kind, ticksCompared, referenceTick.ordinal(), index, null,
                "decoded_write", referenceValue, openGgfValue, context, null, null, null, state);
    }

    private static boolean isAdjacentSwap(List<AudioParityChipWrite> reference,
            List<AudioParityChipWrite> openGgf, int index) {
        if (reference.size() != openGgf.size() || index + 1 >= reference.size()
                || !reference.get(index).equals(openGgf.get(index + 1))
                || !reference.get(index + 1).equals(openGgf.get(index))) {
            return false;
        }
        return reference.subList(index + 2, reference.size())
                .equals(openGgf.subList(index + 2, openGgf.size()));
    }

    /** True only when deleting exactly the first differing element makes the remaining suffix identical. */
    private static boolean isSingleDeletion(List<AudioParityChipWrite> longer,
            List<AudioParityChipWrite> shorter, int index) {
        return longer.size() == shorter.size() + 1 && index < longer.size()
                && longer.subList(index + 1, longer.size()).equals(shorter.subList(index, shorter.size()));
    }

    private static AudioParityReport.EventContext context(List<AudioParityChipWrite> reference,
            List<AudioParityChipWrite> openGgf, int index, boolean referenceHasFocus,
            boolean openGgfHasFocus) {
        return new AudioParityReport.EventContext(
                indexed(reference, Math.max(0, index - CONTEXT_LIMIT), index),
                indexed(reference, Math.min(reference.size(), index + (referenceHasFocus ? 1 : 0)),
                        Math.min(reference.size(), index + (referenceHasFocus ? 1 : 0) + CONTEXT_LIMIT)),
                indexed(openGgf, Math.max(0, index - CONTEXT_LIMIT), index),
                indexed(openGgf, Math.min(openGgf.size(), index + (openGgfHasFocus ? 1 : 0)),
                        Math.min(openGgf.size(), index + (openGgfHasFocus ? 1 : 0) + CONTEXT_LIMIT)));
    }

    private static List<AudioParityReport.IndexedWrite> indexed(List<AudioParityChipWrite> source,
            int start, int end) {
        List<AudioParityReport.IndexedWrite> result = new ArrayList<>(Math.max(0, end - start));
        for (int index = start; index < end; index++) {
            result.add(new AudioParityReport.IndexedWrite(index, source.get(index)));
        }
        return result;
    }

    private static List<AudioParityTrackState> active(List<AudioParityTrackState> tracks) {
        return tracks.stream().filter(AudioParityTrackState::active).toList();
    }

    private static AudioParityReport captureFailure(AudioParityReport.Side side, String field,
            Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new AudioParityReport(AudioParityReport.Kind.CAPTURE_FAILURE, 0, null, null,
                null, field, side == AudioParityReport.Side.REFERENCE ? message : null,
                side == AudioParityReport.Side.OPENGGF ? message : null, null, side, null, null, null);
    }

    private static AudioParityReport streamFailure(AudioParityReport.Side side,
            AudioParityJsonl.ValidationException error) {
        return switch (error.kind()) {
            case ORDINAL_CONTINUITY -> integrityDifference(AudioParityReport.Kind.ORDINAL_MISMATCH,
                    side, error.expected(), error.observed(), error.expected());
            case TICK_COUNT -> integrityDifference(AudioParityReport.Kind.TICK_COUNT_MISMATCH,
                    side, error.expected(), error.observed(), null);
            case MALFORMED -> captureFailure(side,
                    side == AudioParityReport.Side.REFERENCE ? "reference_stream" : "openggf_stream", error);
        };
    }

    private static AudioParityReport integrityDifference(AudioParityReport.Kind kind,
            AudioParityReport.Side side, int expected, int observed, Integer tick) {
        String observedText = Integer.toString(observed);
        return new AudioParityReport(kind, tick == null ? 0 : tick, tick, null, null,
                kind == AudioParityReport.Kind.ORDINAL_MISMATCH ? "ordinal" : "tick_count",
                side == AudioParityReport.Side.REFERENCE ? observedText : null,
                side == AudioParityReport.Side.OPENGGF ? observedText : null, null, side,
                Integer.toString(expected), observedText, null);
    }

    private static AudioParityReport sourceChanged(AudioParityReport.Side side) {
        return new AudioParityReport(AudioParityReport.Kind.CAPTURE_FAILURE, 0, null, null,
                null, "source_changed", null, null, null, side, null, null, null);
    }

    private static AudioParityReport match(int ticks) {
        return new AudioParityReport(AudioParityReport.Kind.MATCH, ticks, null, null, null,
                null, null, null, null);
    }

    private static AudioParityReport difference(AudioParityReport.Kind kind, int ticksCompared,
            Integer tick, Integer event, String role, String field, String reference, String openGgf,
            AudioParityReport.EventContext context) {
        return new AudioParityReport(kind, ticksCompared, tick, event, role, field, reference, openGgf, context);
    }

    private record Field(String name, Function<AudioParityTrackState, Object> value) {
    }

    @FunctionalInterface
    interface BetweenPassAction {
        void run() throws IOException;
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static final class DigestingReader implements AutoCloseable {
        private final MessageDigest digest = sha256();
        private final BufferedReader reader;

        private DigestingReader(Path path) throws IOException {
            // The digest covers the file's stored bytes (compressed for a .gz
            // fixture) so both passes observe the same identity.
            InputStream stream = new DigestInputStream(Files.newInputStream(path), digest);
            if (path.getFileName().toString().endsWith(".gz")) {
                stream = new java.util.zip.GZIPInputStream(stream);
            }
            reader = new BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        private String readLine() throws IOException {
            return reader.readLine();
        }

        private String digest() {
            return HexFormat.of().formatHex(digest.digest());
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
