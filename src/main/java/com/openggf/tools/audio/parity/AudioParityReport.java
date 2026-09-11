package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Deterministic first-divergence result for one reference/OpenGGF capture pair. */
public record AudioParityReport(
        Kind kind,
        int ticksCompared,
        Integer tickOrdinal,
        Integer eventIndex,
        String role,
        String field,
        String referenceValue,
        String openGgfValue,
        EventContext eventContext,
        Side side,
        String expectedValue,
        String observedValue,
        StateContext stateContext) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Kind {
        MATCH,
        CAPTURE_FAILURE,
        METADATA_MISMATCH,
        TICK_COUNT_MISMATCH,
        ORDINAL_MISMATCH,
        GLOBAL_STATE_MISMATCH,
        DISPATCH_MISMATCH,
        TRACK_STATE_MISMATCH,
        EVENT_MISSING,
        EVENT_EXTRA,
        EVENT_REORDERED,
        EVENT_VALUE_DIFFERENT
    }

    public enum Side {
        REFERENCE,
        OPENGGF
    }

    public AudioParityReport(Kind kind, int ticksCompared, Integer tickOrdinal, Integer eventIndex,
            String role, String field, String referenceValue, String openGgfValue,
            EventContext eventContext) {
        this(kind, ticksCompared, tickOrdinal, eventIndex, role, field, referenceValue,
                openGgfValue, eventContext, null, null, null, null);
    }

    public AudioParityReport {
        if (kind == null || ticksCompared < 0) {
            throw new IllegalArgumentException("report kind and non-negative tick count are required");
        }
    }

    public boolean matches() {
        return kind == Kind.MATCH;
    }

    public String toHumanText() {
        if (matches()) {
            return "S1 audio parity: MATCH (" + ticksCompared + " ticks)"
                    + "\ncoverage: driver after harness initialization; production song startup is not validated";
        }
        StringBuilder result = new StringBuilder("S1 audio parity: ")
                .append(kind == Kind.CAPTURE_FAILURE ? "CAPTURE FAILURE" : "MISMATCH")
                .append('\n').append("kind: ").append(label(kind));
        append(result, "tick", tickOrdinal);
        append(result, "event", eventIndex);
        append(result, "role", role);
        append(result, "field", field);
        append(result, "side", side == null ? null : label(side));
        append(result, "expected", expectedValue);
        append(result, "observed", observedValue);
        append(result, "reference", referenceValue);
        append(result, "openggf", openGgfValue);
        if (eventContext != null) {
            result.append('\n').append("context: reference(before=")
                    .append(eventContext.referenceBefore()).append(", after=")
                    .append(eventContext.referenceAfter()).append(") openggf(before=")
                    .append(eventContext.openGgfBefore()).append(", after=")
                    .append(eventContext.openGgfAfter()).append(')');
        }
        if (stateContext != null) {
            result.append('\n').append("normalized state context: referenceGlobal=")
                    .append(stateContext.referenceGlobal()).append(" reference active track context=")
                    .append(stateContext.referenceTracks()).append(" openggfGlobal=")
                    .append(stateContext.openGgfGlobal()).append(" openggfActiveTracks=")
                    .append(stateContext.openGgfTracks());
        }
        return result.toString();
    }

    public String toJsonSummary() {
        ObjectNode root = JSON.createObjectNode();
        root.put("result", matches() ? "match" : kind == Kind.CAPTURE_FAILURE ? "capture_failure" : "mismatch");
        root.put("kind", label(kind));
        root.put("ticksCompared", ticksCompared);
        root.put("coverage", "driver-after-harness-initialization");
        root.put("productionStartupValidated", false);
        put(root, "tick", tickOrdinal);
        put(root, "event", eventIndex);
        put(root, "role", role);
        put(root, "field", field);
        put(root, "side", side == null ? null : label(side));
        put(root, "expected", expectedValue);
        put(root, "observed", observedValue);
        put(root, "reference", referenceValue);
        put(root, "openggf", openGgfValue);
        if (eventContext != null) {
            ObjectNode context = root.putObject("context");
            writes(context.putArray("referenceBefore"), eventContext.referenceBefore());
            writes(context.putArray("referenceAfter"), eventContext.referenceAfter());
            writes(context.putArray("openggfBefore"), eventContext.openGgfBefore());
            writes(context.putArray("openggfAfter"), eventContext.openGgfAfter());
        }
        if (stateContext != null) {
            ObjectNode context = root.putObject("stateContext");
            global(context.putObject("referenceGlobal"), stateContext.referenceGlobal());
            tracks(context.putArray("referenceActiveTracks"), stateContext.referenceTracks());
            global(context.putObject("openggfGlobal"), stateContext.openGgfGlobal());
            tracks(context.putArray("openggfActiveTracks"), stateContext.openGgfTracks());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot encode audio parity summary", error);
        }
    }

    private static String label(Kind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String label(Side side) {
        return side.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void append(StringBuilder target, String label, Object value) {
        if (value != null) {
            target.append('\n').append(label).append(": ").append(value);
        }
    }

    private static void put(ObjectNode target, String field, Integer value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private static void put(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private static void writes(ArrayNode target, List<IndexedWrite> writes) {
        for (IndexedWrite indexed : writes) {
            ObjectNode item = target.addObject();
            item.put("index", indexed.index());
            AudioParityChipWrite write = indexed.write();
            item.put("chip", write.chip());
            if (write.port() != null) {
                item.put("port", write.port());
                item.put("register", write.register());
            }
            item.put("value", write.value());
        }
    }

    private static void global(ObjectNode target, AudioParityTick.GlobalState global) {
        target.put("fadeActive", global.fadeActive());
        target.put("fadeDirection", global.fadeDirection());
        if (global.fadeDelay() != null) {
            target.put("fadeDelay", global.fadeDelay());
            target.put("fadeSteps", global.fadeSteps());
        }
        target.put("speedUp", global.speedUp());
        target.put("tempoReload", global.tempoReload());
        target.put("tempoTimeout", global.tempoTimeout());
    }

    private static void tracks(ArrayNode target, List<AudioParityTrackState> tracks) {
        for (AudioParityTrackState track : tracks) {
            ObjectNode item = target.addObject();
            item.put("role", track.role());
            item.put("hardware", track.hardware());
            item.put("active", true);
            item.put("baseFrequency", track.baseFrequency());
            item.put("detune", track.detune());
            item.put("doNotAttack", track.doNotAttack());
            item.put("duration", track.duration());
            item.put("durationReload", track.durationReload());
            if (track.envelopeCursor() != null) {
                item.put("envelopeCursor", track.envelopeCursor());
            }
            ArrayNode loops = item.putArray("loopCounters");
            track.loopCounters().forEach(loops::add);
            item.put("modulationEnabled", track.modulationEnabled());
            item.put("overridden", track.overridden());
            if (track.pan() != null) {
                item.put("pan", track.pan());
                item.put("ams", track.ams());
                item.put("fms", track.fms());
            }
            ArrayNode stack = item.putArray("returnStack");
            track.returnStack().forEach(stack::add);
            item.put("sequencePosition", track.sequencePosition());
            item.put("transpose", track.transpose());
            item.put("voiceOrEnvelope", track.voiceOrEnvelope());
            item.put("volume", track.volume());
        }
    }

    public record IndexedWrite(int index, AudioParityChipWrite write) {
        public IndexedWrite {
            if (index < 0 || write == null) {
                throw new IllegalArgumentException("indexed write requires a non-negative index and write");
            }
        }
    }

    public record EventContext(
            List<IndexedWrite> referenceBefore,
            List<IndexedWrite> referenceAfter,
            List<IndexedWrite> openGgfBefore,
            List<IndexedWrite> openGgfAfter) {
        public EventContext {
            referenceBefore = List.copyOf(referenceBefore);
            referenceAfter = List.copyOf(referenceAfter);
            openGgfBefore = List.copyOf(openGgfBefore);
            openGgfAfter = List.copyOf(openGgfAfter);
            if (referenceBefore.size() > 8 || referenceAfter.size() > 8
                    || openGgfBefore.size() > 8 || openGgfAfter.size() > 8) {
                throw new IllegalArgumentException("event context is limited to eight writes in each direction");
            }
        }
    }

    public record StateContext(
            AudioParityTick.GlobalState referenceGlobal,
            List<AudioParityTrackState> referenceTracks,
            AudioParityTick.GlobalState openGgfGlobal,
            List<AudioParityTrackState> openGgfTracks) {
        public StateContext {
            referenceTracks = activeCopy(referenceTracks);
            openGgfTracks = activeCopy(openGgfTracks);
            if (referenceGlobal == null || openGgfGlobal == null) {
                throw new IllegalArgumentException("event state context requires both global states");
            }
        }

        private static List<AudioParityTrackState> activeCopy(List<AudioParityTrackState> tracks) {
            List<AudioParityTrackState> result = List.copyOf(tracks);
            if (result.size() > AudioParitySchema.ROLES.size()
                    || result.stream().anyMatch(track -> !track.active())) {
                throw new IllegalArgumentException("event state context contains only active fixed roles");
            }
            return result;
        }
    }
}
