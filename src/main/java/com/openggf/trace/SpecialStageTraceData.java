package com.openggf.trace;

import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.HardwareTimingStreamLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Reads and holds the contents of a Sonic 2 special-stage trace directory:
 * {@code metadata.json}, {@code physics.csv(.gz)}, and optional
 * {@code aux_state.jsonl(.gz)}.
 *
 * <p>Mirrors {@link TraceData#load(Path)}'s load flow but parses rows with
 * {@link SpecialStageTraceFrame#parseCsvRow(String)} instead of
 * {@link TraceFrame}, and requires {@code metadata.trace_profile} to be
 * {@code "s2_special_stage"}.
 */
public final class SpecialStageTraceData {

    /** Typed view of a recorder {@code control_state} transition. */
    public record ControlStateTransition(int frame, boolean started) {
    }

    private static final String REQUIRED_TRACE_PROFILE = "s2_special_stage";

    private final TraceMetadata metadata;
    private final List<SpecialStageTraceFrame> frames;
    private final Map<Integer, List<TraceEvent>> eventsByFrame;
    private final List<ControlStateTransition> controlStateTransitions;
    private final HardwareTimingSchedule hardwareTimingSchedule;

    private SpecialStageTraceData(TraceMetadata metadata, List<SpecialStageTraceFrame> frames,
            Map<Integer, List<TraceEvent>> eventsByFrame,
            HardwareTimingSchedule hardwareTimingSchedule) {
        this.metadata = metadata;
        this.frames = frames;
        this.eventsByFrame = eventsByFrame;
        this.controlStateTransitions = parseControlStateTransitions(eventsByFrame);
        this.hardwareTimingSchedule =
                Objects.requireNonNull(hardwareTimingSchedule, "hardwareTimingSchedule");
    }

    public static SpecialStageTraceData load(Path traceDirectory) throws IOException {
        return load(traceDirectory, List.of());
    }

    public static SpecialStageTraceData load(
            Path traceDirectory,
            List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger)
            throws IOException {
        Path metadataPath = traceDirectory.resolve("metadata.json");
        TraceMetadata metadata = TraceMetadata.load(metadataPath);

        String traceProfile = metadata.traceProfile();
        if (!"s2".equals(metadata.game()) || !REQUIRED_TRACE_PROFILE.equals(traceProfile)) {
            throw new IllegalArgumentException(
                "Expected s2 trace_profile '" + REQUIRED_TRACE_PROFILE + "', got game '"
                    + metadata.game() + "' profile '" + traceProfile + "' in " + metadataPath);
        }

        Path physicsPath = TraceFiles.resolve(traceDirectory, "physics.csv");
        if (physicsPath == null) {
            throw new NoSuchFileException(traceDirectory.resolve("physics.csv").toString());
        }
        Path auxPath = TraceFiles.resolve(traceDirectory, "aux_state.jsonl");

        List<SpecialStageTraceFrame> frames = loadPhysicsCsv(physicsPath);
        Map<Integer, List<TraceEvent>> events = auxPath != null
            ? TraceData.loadAuxEvents(auxPath, metadata)
            : Collections.emptyMap();
        if (metadata.hasPerFrameDynamicArtTransferState()) {
            StoredPhysicsFrameDomain frameDomain = StoredPhysicsFrameDomain.scan(
                    physicsPath, StoredPhysicsFrameDomain.FrameEncoding.DECIMAL);
            TraceData.validateDynamicArtTransferStates(
                    metadata, frameDomain, events, openingDynamicArtLedger);
        }
        HardwareTimingSchedule hardwareTimingSchedule =
                HardwareTimingStreamLoader.load(traceDirectory, metadata);

        return new SpecialStageTraceData(
                metadata, frames, events, hardwareTimingSchedule);
    }

    public TraceMetadata metadata() {
        return metadata;
    }

    public int frameCount() {
        return frames.size();
    }

    public SpecialStageTraceFrame getFrame(int i) {
        if (i < 0 || i >= frames.size()) {
            throw new IndexOutOfBoundsException(
                "Frame " + i + " out of range [0, " + frames.size() + ")");
        }
        return frames.get(i);
    }

    public HardwareTimingSchedule hardwareTimingSchedule() {
        return hardwareTimingSchedule;
    }

    /** Reuses {@link TraceEvent} + aux jsonl parsing shared with {@link TraceData}. */
    public List<TraceEvent> getEventsForFrame(int i) {
        return eventsByFrame.getOrDefault(i, Collections.emptyList());
    }

    public TraceEvent.DynamicArtTransferState dynamicArtTransferStateForFrame(
            int frame) {
        return TraceData.dynamicArtTransferStateForFrame(
                metadata, eventsByFrame, frame);
    }

    /** Atomic object-pass snapshots; the validated binder owns execution ordering. */
    public List<TraceEvent.StateSnapshot> runObjectsEndSnapshots() {
        return eventsByFrame.values().stream()
                .flatMap(List::stream)
                .filter(TraceEvent.StateSnapshot.class::isInstance)
                .map(TraceEvent.StateSnapshot.class::cast)
                .filter(snapshot -> "run_objects_end".equals(snapshot.fields().get("type")))
                .toList();
    }

    /** Returns all initial/transition samples of ROM {@code SpecialStage_Started}. */
    public List<ControlStateTransition> controlStateTransitions() {
        return controlStateTransitions;
    }

    private static List<ControlStateTransition> parseControlStateTransitions(
            Map<Integer, List<TraceEvent>> eventsByFrame) {
        return eventsByFrame.values().stream()
                .flatMap(List::stream)
                .filter(TraceEvent.StateSnapshot.class::isInstance)
                .map(TraceEvent.StateSnapshot.class::cast)
                .filter(snapshot -> "control_state".equals(snapshot.fields().get("type")))
                .map(snapshot -> new ControlStateTransition(snapshot.frame(),
                        numericValue(snapshot.fields().get("started")) != 0))
                .sorted(Comparator.comparingInt(ControlStateTransition::frame))
                .toList();
    }

    /** Returns the logical frame where the final checkpoint resolved, if captured. */
    public OptionalInt stageFinishedFrame() {
        return firstEventFrame("stage_finished");
    }

    /**
     * Returns the raw VBlank observation that first saw the final
     * {@code SS_Check_Rings_flag} rise. This can be a lag-labelled row after
     * the logical non-lag frame used to label the finish boundary.
     */
    public OptionalInt stageFinishedObservedFrame() {
        return eventsByFrame.values().stream()
                .flatMap(List::stream)
                .filter(TraceEvent.StateSnapshot.class::isInstance)
                .map(TraceEvent.StateSnapshot.class::cast)
                .filter(snapshot -> "stage_finished".equals(snapshot.fields().get("type")))
                .map(snapshot -> snapshot.fields().get("observed_frame"))
                .filter(Objects::nonNull)
                .mapToInt(SpecialStageTraceData::numericValue)
                .min();
    }

    /** Returns the later frame where the ROM creates the Obj6F results object. */
    public OptionalInt resultsStartedFrame() {
        return firstEventFrame("results_started");
    }

    private OptionalInt firstEventFrame(String type) {
        return eventsByFrame.entrySet().stream()
            .filter(entry -> entry.getValue().stream().anyMatch(event -> isType(event, type)))
            .mapToInt(Map.Entry::getKey)
            .min();
    }

    private static boolean isType(TraceEvent event, String type) {
        return event instanceof TraceEvent.StateSnapshot snapshot
            && type.equals(snapshot.fields().get("type"));
    }

    private static int numericValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    private static List<SpecialStageTraceFrame> loadPhysicsCsv(Path csvPath) throws IOException {
        List<SpecialStageTraceFrame> frames = new ArrayList<>();
        try (BufferedReader reader = TraceFiles.openReader(csvPath)) {
            boolean firstMeaningfulLine = true;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (firstMeaningfulLine && TraceFiles.isCsvHeader(trimmed)) {
                    firstMeaningfulLine = false;
                    continue;
                }
                firstMeaningfulLine = false;
                frames.add(SpecialStageTraceFrame.parseCsvRow(trimmed));
            }
        }
        return frames;
    }
}
