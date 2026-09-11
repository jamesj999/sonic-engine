package com.openggf.game.sonic1.specialstage;

import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.StoredPhysicsFrameDomain;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFiles;
import com.openggf.trace.TraceMetadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads and holds the contents of a Sonic 1 special-stage trace directory:
 * {@code metadata.json}, {@code physics.csv(.gz)}, and optional
 * {@code aux_state.jsonl(.gz)}.
 *
 * <p>Mirrors {@link TraceData#load(Path)}'s load flow but parses rows with
 * {@link Sonic1SpecialStageTraceFrame#parseCsvRow(String)}, and requires
 * {@code metadata.trace_profile} to be {@code "s1_special_stage"}.
 */
public final class Sonic1SpecialStageTraceData {

    private static final String REQUIRED_TRACE_PROFILE = "s1_special_stage";

    private final TraceMetadata metadata;
    private final List<Sonic1SpecialStageTraceFrame> frames;
    private final Map<Integer, List<TraceEvent>> eventsByFrame;

    private Sonic1SpecialStageTraceData(TraceMetadata metadata,
            List<Sonic1SpecialStageTraceFrame> frames,
            Map<Integer, List<TraceEvent>> eventsByFrame) {
        this.metadata = metadata;
        this.frames = frames;
        this.eventsByFrame = eventsByFrame;
    }

    public static Sonic1SpecialStageTraceData load(Path traceDirectory) throws IOException {
        return load(traceDirectory, List.of());
    }

    public static Sonic1SpecialStageTraceData load(
            Path traceDirectory,
            List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger)
            throws IOException {
        Path metadataPath = traceDirectory.resolve("metadata.json");
        TraceMetadata metadata = TraceMetadata.load(metadataPath);

        String traceProfile = metadata.traceProfile();
        if (!"s1".equals(metadata.game()) || !REQUIRED_TRACE_PROFILE.equals(traceProfile)) {
            throw new IllegalArgumentException(
                "Expected s1 trace_profile '" + REQUIRED_TRACE_PROFILE + "', got game '"
                    + metadata.game() + "' profile '" + traceProfile + "' in " + metadataPath);
        }

        Path physicsPath = TraceFiles.resolve(traceDirectory, "physics.csv");
        if (physicsPath == null) {
            throw new NoSuchFileException(traceDirectory.resolve("physics.csv").toString());
        }
        Path auxPath = TraceFiles.resolve(traceDirectory, "aux_state.jsonl");

        List<Sonic1SpecialStageTraceFrame> frames = loadPhysicsCsv(physicsPath);
        Map<Integer, List<TraceEvent>> events = auxPath != null
            ? TraceData.loadAuxEvents(auxPath, metadata)
            : Collections.emptyMap();
        if (metadata.hasPerFrameDynamicArtTransferState()) {
            StoredPhysicsFrameDomain frameDomain = StoredPhysicsFrameDomain.scan(
                    physicsPath, StoredPhysicsFrameDomain.FrameEncoding.DECIMAL);
            TraceData.validateDynamicArtTransferStates(
                    metadata, frameDomain, events, openingDynamicArtLedger);
        }

        return new Sonic1SpecialStageTraceData(metadata, frames, events);
    }

    public TraceMetadata metadata() {
        return metadata;
    }

    public List<Sonic1SpecialStageTraceFrame> frames() {
        return frames;
    }

    public int frameCount() {
        return frames.size();
    }

    public Sonic1SpecialStageTraceFrame getFrame(int i) {
        if (i < 0 || i >= frames.size()) {
            throw new IndexOutOfBoundsException(
                "Frame " + i + " out of range [0, " + frames.size() + ")");
        }
        return frames.get(i);
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

    /** Returns all events for this trace indexed by frame. */
    public Map<Integer, List<TraceEvent>> eventsByFrame() {
        return eventsByFrame;
    }

    private static List<Sonic1SpecialStageTraceFrame> loadPhysicsCsv(Path csvPath) throws IOException {
        List<Sonic1SpecialStageTraceFrame> frames = new ArrayList<>();
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
                frames.add(Sonic1SpecialStageTraceFrame.parseCsvRow(trimmed));
            }
        }
        return frames;
    }
}
