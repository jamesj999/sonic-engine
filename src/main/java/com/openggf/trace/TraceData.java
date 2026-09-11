package com.openggf.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.HardwareTimingStreamLoader;
import com.openggf.trace.timing.HardwareCompletionEdge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and holds the contents of a trace directory:
 * metadata.json, physics.csv, and aux_state.jsonl.
 *
 * The primary CSV is loaded entirely into memory (small: ~100 bytes/frame).
 * Auxiliary events are lazy-loaded and indexed by frame number.
 */
public class TraceData {

    private final TraceMetadata metadata;
    private final HardwareTimingSchedule hardwareTimingSchedule;
    private final List<TraceFrame> frames;
    private final Map<Integer, List<TraceEvent>> eventsByFrame;
    private final Set<Class<? extends TraceEvent>> observedEventTypes;
    private final List<Integer> checkpointFramesAscending;
    private final Map<Integer, TraceEvent.Checkpoint> checkpointsByFrame;
    private final List<Integer> zoneActStateFramesAscending;
    private final Map<Integer, TraceEvent.ZoneActState> zoneActStatesByFrame;
    private final Map<Integer, HardwareCompletionEdge> unobservedDirectChildrenByFrame;
    private List<DynamicArtTransfer.Descriptor> terminalDynamicArtLedger = List.of();
    // Memoised pure derivations of the immutable event map. Both are queried
    // once per replayed frame, and each full computation is O(total events),
    // so recomputing them made replay quadratic in trace length.
    private List<TraceEvent.DynamicArtTransferState> cachedDynamicArtTransferStates;
    private Boolean cachedHasRecordedPreLevelPrefix;

    // Package-private so same-package test fixtures in src/test can
    // construct in-memory instances without going through disk I/O.
    TraceData(TraceMetadata metadata, List<TraceFrame> frames,
              Map<Integer, List<TraceEvent>> eventsByFrame,
              HardwareTimingSchedule hardwareTimingSchedule) {
        this.metadata = metadata;
        this.hardwareTimingSchedule = hardwareTimingSchedule;
        this.frames = frames;
        this.eventsByFrame = eventsByFrame;
        this.checkpointsByFrame = new HashMap<>();
        this.checkpointFramesAscending = new ArrayList<>();
        this.zoneActStatesByFrame = new HashMap<>();
        this.zoneActStateFramesAscending = new ArrayList<>();
        this.observedEventTypes = buildLatestEventIndexes();
        this.unobservedDirectChildrenByFrame = buildUnobservedDirectChildren();
    }

    public static TraceData load(Path traceDirectory) throws IOException {
        return load(traceDirectory, List.of());
    }

    /**
     * Loads a run segment whose first recorded row may inherit production-owned
     * dynamic-art work submitted in the preceding native transition gap.
     */
    public static TraceData load(
            Path traceDirectory,
            List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger)
            throws IOException {
        Path metadataPath = traceDirectory.resolve("metadata.json");
        Path physicsPath = TraceFiles.resolve(traceDirectory, "physics.csv");
        Path auxPath = TraceFiles.resolve(traceDirectory, "aux_state.jsonl");

        TraceMetadata metadata = TraceMetadata.load(metadataPath);
        rejectSpecialStageProfile(metadata, metadataPath);
        if (physicsPath == null) {
            throw new NoSuchFileException(traceDirectory.resolve("physics.csv").toString());
        }
        List<TraceFrame> frames = loadPhysicsCsv(physicsPath, metadata);
        Map<Integer, List<TraceEvent>> events = auxPath != null
            ? loadAuxEvents(auxPath, metadata)
            : Collections.emptyMap();
        HardwareTimingSchedule hardwareTimingSchedule = HardwareTimingStreamLoader.load(traceDirectory, metadata);

        TraceData trace = new TraceData(metadata, frames, events, hardwareTimingSchedule);
        trace.validateAdvertisedLoadQueueStates();
        trace.validateAdvertisedDynamicArtTransferStates(
                StoredPhysicsFrameDomain.fromTraceFrames(frames),
                openingDynamicArtLedger);
        return trace;
    }

    /**
     * Loads only {@code metadata.json} (plus aux events, if present), skipping
     * the {@code physics.csv} parse entirely. For chain-drive interiors that
     * are advance-uncompared (a {@code special_stage} segment under the SS-
     * interior policy — see {@link com.openggf.trace.replay.runs.TraceRunReplayWalker#isUncomparedInterior}),
     * no per-frame comparator is ever built from this segment's frames,
     * so its {@code physics.csv} schema need not match one of {@link TraceFrame}'s
     * known column widths — per-game special-stage physics CSVs (S1's maze
     * schema, S2's halfpipe schema, S3K's blue-spheres schema) are structurally
     * distinct from the primary-level physics schema {@link TraceFrame} parses,
     * and per-frame special-stage comparison is a later, separate workflow.
     * {@link #frameCount()} on the returned instance is always {@code 0}; callers
     * needing the segment's declared length must read
     * {@link com.openggf.trace.TraceRunManifest.Segment#traceFrameCount()} from
     * the manifest instead.
     */
    public static TraceData loadMetadataOnly(Path traceDirectory) throws IOException {
        return loadMetadataOnly(
                traceDirectory, StoredPhysicsFrameDomain.FrameEncoding.HEXADECIMAL,
                List.of());
    }

    /**
     * Metadata-only load with the trace profile's physical frame encoding.
     * Primary level traces use hexadecimal frame labels; special-stage
     * profiles use decimal labels even when their dynamic-art journal is
     * validated through this shared path.
     */
    public static TraceData loadMetadataOnly(
            Path traceDirectory,
            StoredPhysicsFrameDomain.FrameEncoding frameEncoding)
            throws IOException {
        return loadMetadataOnly(traceDirectory, frameEncoding, List.of());
    }

    /**
     * Metadata-only variant for a run segment with a manifest-validated
     * dynamic-art opening ledger.
     */
    public static TraceData loadMetadataOnly(
            Path traceDirectory,
            StoredPhysicsFrameDomain.FrameEncoding frameEncoding,
            List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger)
            throws IOException {
        Path metadataPath = traceDirectory.resolve("metadata.json");
        Path auxPath = TraceFiles.resolve(traceDirectory, "aux_state.jsonl");

        TraceMetadata metadata = TraceMetadata.load(metadataPath);
        Map<Integer, List<TraceEvent>> events = auxPath != null
            ? loadAuxEvents(auxPath, metadata)
            : Collections.emptyMap();
        HardwareTimingSchedule hardwareTimingSchedule = HardwareTimingStreamLoader.load(traceDirectory, metadata);

        TraceData trace = new TraceData(
                metadata, Collections.emptyList(), events, hardwareTimingSchedule);
        Path physicsPath = TraceFiles.resolve(traceDirectory, "physics.csv");
        StoredPhysicsFrameDomain frameDomain = physicsPath == null
                ? null
                : StoredPhysicsFrameDomain.scan(physicsPath, frameEncoding);
        if (metadata.hasPerFrameDynamicArtTransferState()) {
            if (frameDomain == null) {
                throw new NoSuchFileException(
                        traceDirectory.resolve("physics.csv").toString());
            }
            trace.validateAdvertisedDynamicArtTransferStates(
                    frameDomain, openingDynamicArtLedger);
        }
        return trace;
    }

    public TraceMetadata metadata() { return metadata; }
    public HardwareTimingSchedule hardwareTimingSchedule() { return hardwareTimingSchedule; }
    public int frameCount() { return frames.size(); }

    /**
     * Returns the playable character names recorded in this trace, in order.
     * The current replay pipeline supports the primary character plus at most one sidekick.
     */
    public List<String> recordedCharacters() {
        return metadata.recordedCharacters();
    }

    /**
     * Returns the ROM VBlank counter value that corresponds to trace frame 0.
     *
     * <p>V5 rows record the ROM VBlank counter directly. Metadata-only loads
     * retain their explicit BK2 offset because they have no physics row.
     */
    public int initialVblankCounter() {
        if (!frames.isEmpty()) {
            int recorded = frames.get(0).vblankCounter();
            if (recorded >= 0) {
                return recorded;
            }
        }
        return metadata.bk2FrameOffset();
    }

    /**
     * Returns the explicitly recorded low-bit phase for ROM object routines
     * that read {@code V_int_run_count}; absent evidence means no adjustment.
     */
    public int initialVIntRunCounterPhaseOffset() {
        Integer recordedCounterPhase = metadata.ringFloorCheckCounterPhase();
        return recordedCounterPhase == null ? 0 : recordedCounterPhase & 7;
    }

    public TraceFrame getFrame(int traceFrame) {
        if (traceFrame < 0 || traceFrame >= frames.size()) {
            throw new IndexOutOfBoundsException(
                "Frame " + traceFrame + " out of range [0, " + frames.size() + ")");
        }
        return frames.get(traceFrame);
    }

    /**
     * Returns the recorded state for a named playable character on the given frame.
     * The current replay pipeline exposes the primary character plus the first sidekick.
     */
    public TraceCharacterState characterState(int traceFrame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }

        TraceFrame frame = getFrame(traceFrame);
        List<String> recordedCharacters = metadata.recordedCharacters();
        if (!recordedCharacters.isEmpty()
                && recordedCharacters.getFirst().equalsIgnoreCase(characterCode)) {
            return frame.primaryCharacterState();
        }
        if (recordedCharacters.size() > 1
                && recordedCharacters.get(1).equalsIgnoreCase(characterCode)) {
            return frame.sidekick();
        }
        return null;
    }

    public List<TraceEvent> getEventsForFrame(int traceFrame) {
        return eventsByFrame.getOrDefault(traceFrame, Collections.emptyList());
    }

    /**
     * The ROM's own {@code SlotMachineVariables} for this row, or {@code null}
     * when the fixture does not carry the event. Comparison-only.
     */
    /** ObjB2's recorded SST for this row, or {@code null}. Comparison-only. */
    public TraceEvent.S2TornadoState s2TornadoStateForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.S2TornadoState state) {
                return state;
            }
        }
        return null;
    }

    public TraceEvent.CnzSlotMachineState cnzSlotMachineStateForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CnzSlotMachineState state) {
                return state;
            }
        }
        return null;
    }

    public List<TraceEvent.LoadQueueState> loadQueueStatesForFrame(int frame) {
        List<TraceEvent.LoadQueueState> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.LoadQueueState state) {
                states.add(state);
            }
        }
        return List.copyOf(states);
    }

    /**
     * Returns the queue heartbeat this row is compared against.
     *
     * <p>Recorded queue rows are compared as sampled. A child published by
     * {@code Process_Kos_Module_Queue} in LevelLoop's tail
     * (docs/skdisasm/sonic3k.asm:7908) is decompressed by the next pass's
     * {@code Process_Kos_Queue} (7887), which V-int can interrupt and bookmark
     * mid-stream (2840-2843, {@code Restore_Kos_Bookmark} at 2957). Its
     * {@code Kos_decomp_queue_count} entry is therefore still an unfinished
     * FIFO head at the {@code Wait_VSync} sample that follows (7888), on a held
     * loop tail as much as on any other row, and replay reproduces that head.
     */
    public List<TraceEvent.LoadQueueState> loadQueueStatesForComparisonFrame(
            int frame) {
        return loadQueueStatesForFrame(frame);
    }

    public HardwareCompletionEdge unobservedDirectChildForComparisonFrame(
            int frame) {
        return unobservedDirectChildrenByFrame.get(frame);
    }

    private Map<Integer, HardwareCompletionEdge> buildUnobservedDirectChildren() {
        // Selection below is by queue-kind wire name (s3k_kos_module /
        // s3k_kos_direct) and by KOS_DECOMPRESSION_QUEUE completion edges, which
        // no other game emits; a trace without those rows normalizes to an empty
        // map on its own, so no game-name gate belongs here.
        if (frames.size() < 2 || hardwareTimingSchedule == null) {
            return Map.of();
        }
        Map<Integer, HardwareCompletionEdge> unobservedDirectChildren =
                new HashMap<>();
        boolean moduleBatchActive = false;
        boolean moduleBatchAdmittedOnHeldTail = false;
        for (int index = 0; index < frames.size() - 1; index++) {
            TraceFrame currentFrame = frames.get(index);
            TraceFrame nextFrame = frames.get(index + 1);
            List<TraceEvent.LoadQueueState> currentStates =
                    loadQueueStatesForFrame(currentFrame.frame());
            List<TraceEvent.LoadQueueState> nextStates =
                    loadQueueStatesForFrame(nextFrame.frame());
            TraceEvent.LoadQueueState currentModule = queueState(
                    currentStates, "s3k_kos_module");
            TraceEvent.LoadQueueState currentDirect = queueState(
                    currentStates, "s3k_kos_direct");
            TraceEvent.LoadQueueState nextModule = queueState(
                    nextStates, "s3k_kos_module");
            TraceEvent.LoadQueueState nextDirect = queueState(
                    nextStates, "s3k_kos_direct");

            boolean heldTail = isHeldTail(currentFrame, nextFrame);
            if (currentModule != null && currentModule.busy()
                    && !moduleBatchActive) {
                moduleBatchActive = true;
                moduleBatchAdmittedOnHeldTail = heldTail;
            } else if (currentModule == null || !currentModule.busy()) {
                moduleBatchActive = false;
                moduleBatchAdmittedOnHeldTail = false;
            }

            List<HardwareCompletionEdge> nextDirectCompletions =
                    directCompletionEdges(nextFrame.frame());
            if (heldTail && moduleBatchActive
                    && !moduleBatchAdmittedOnHeldTail
                    && isIdle(currentDirect)
                    && isIdle(nextDirect)
                    && sameQueueStateIgnoringFrame(currentModule, nextModule)
                    && nextDirectCompletions.size() == 1) {
                unobservedDirectChildren.put(
                        currentFrame.frame(), nextDirectCompletions.getFirst());
            }
        }
        return Map.copyOf(unobservedDirectChildren);
    }

    private List<HardwareCompletionEdge> directCompletionEdges(int rawFrame) {
        return hardwareTimingSchedule.edgesAt(
                        rawFrame, HardwareServiceBoundary.PRE_MAIN_LOOP)
                .stream()
                .filter(edge -> edge.kind()
                        == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .toList();
    }

    private static boolean isHeldTail(TraceFrame current, TraceFrame next) {
        return current.gameplayFrameCounter() >= 0
                && current.gameplayFrameCounter()
                        == next.gameplayFrameCounter()
                && current.lagCounter() >= 0
                && next.lagCounter() > current.lagCounter()
                && current.vblankCounter() >= 0
                && next.vblankCounter() > current.vblankCounter();
    }

    private static boolean isIdle(TraceEvent.LoadQueueState state) {
        return state != null && !state.busy() && !state.prepared();
    }

    private static TraceEvent.LoadQueueState queueState(
            List<TraceEvent.LoadQueueState> states, String kind) {
        return states.stream()
                .filter(state -> kind.equals(state.kind()))
                .findFirst()
                .orElse(null);
    }

    private static boolean sameQueueStateIgnoringFrame(
            TraceEvent.LoadQueueState left,
            TraceEvent.LoadQueueState right) {
        return left != null && right != null
                && left.kind().equals(right.kind())
                && left.busy() == right.busy()
                && left.prepared() == right.prepared()
                && left.activeSource() == right.activeSource()
                && left.activeDestination() == right.activeDestination()
                && left.totalWork() == right.totalWork()
                && left.remainingWork() == right.remainingWork()
                && left.queuedFingerprints().equals(right.queuedFingerprints())
                && left.serviceObservations().equals(right.serviceObservations());
    }

    public List<TraceEvent.DynamicArtTransferState>
            dynamicArtTransferStatesForFrame(int frame) {
        List<TraceEvent.DynamicArtTransferState> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(
                frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.DynamicArtTransferState state) {
                states.add(state);
            }
        }
        return List.copyOf(states);
    }

    public TraceEvent.DynamicArtTransferState dynamicArtTransferStateForFrame(
            int frame) {
        return dynamicArtTransferStateForFrame(metadata, eventsByFrame, frame);
    }

    public static TraceEvent.DynamicArtTransferState
            dynamicArtTransferStateForFrame(
                    TraceMetadata metadata,
                    Map<Integer, List<TraceEvent>> eventsByFrame,
                    int frame) {
        if (!metadata.hasPerFrameDynamicArtTransferState()) {
            return null;
        }
        List<TraceEvent.DynamicArtTransferState> states =
                eventsByFrame.getOrDefault(frame, Collections.emptyList())
                        .stream()
                        .filter(TraceEvent.DynamicArtTransferState.class::isInstance)
                        .map(TraceEvent.DynamicArtTransferState.class::cast)
                        .toList();
        if (states.size() != 1) {
            throw new IllegalArgumentException(
                    "expected exactly one dynamic-art state at frame "
                            + frame + " but found " + states.size());
        }
        return states.getFirst();
    }

    /**
     * Enforces one typed heartbeat for every stored row and replays the
     * comparison-only lifecycle from an independently empty segment arm.
     */
    public void validateAdvertisedDynamicArtTransferStates(
            StoredPhysicsFrameDomain frameDomain) {
        validateAdvertisedDynamicArtTransferStates(frameDomain, List.of());
    }

    public void validateAdvertisedDynamicArtTransferStates(
            StoredPhysicsFrameDomain frameDomain,
            List<DynamicArtTransfer.Descriptor> openingLedger) {
        if (!metadata.hasPerFrameDynamicArtTransferState()) {
            terminalDynamicArtLedger = List.of();
            return;
        }
        terminalDynamicArtLedger = validateDynamicArtTransferStates(
                metadata, frameDomain, eventsByFrame, openingLedger);
    }

    public static List<DynamicArtTransfer.Descriptor>
            validateDynamicArtTransferStates(
                    TraceMetadata metadata,
                    StoredPhysicsFrameDomain frameDomain,
                    Map<Integer, List<TraceEvent>> eventsByFrame) {
        return validateDynamicArtTransferStates(
                metadata, frameDomain, eventsByFrame, List.of());
    }

    public static List<DynamicArtTransfer.Descriptor>
            validateDynamicArtTransferStates(
                    TraceMetadata metadata,
                    StoredPhysicsFrameDomain frameDomain,
                    Map<Integer, List<TraceEvent>> eventsByFrame,
                    List<DynamicArtTransfer.Descriptor> openingLedger) {
        Set<Integer> domain = new HashSet<>(frameDomain.frames());
        List<TraceEvent.DynamicArtTransferState> ordered =
                new ArrayList<>(frameDomain.frames().size());
        for (Map.Entry<Integer, List<TraceEvent>> entry : eventsByFrame.entrySet()) {
            for (TraceEvent event : entry.getValue()) {
                if (event instanceof TraceEvent.DynamicArtTransferState state
                        && !domain.contains(state.frame())) {
                    throw new IllegalArgumentException(
                            "dynamic-art event outside physics frame domain: "
                                    + state.frame());
                }
            }
        }
        for (int frame : frameDomain.frames()) {
            List<TraceEvent.DynamicArtTransferState> states =
                    eventsByFrame.getOrDefault(frame, Collections.emptyList())
                            .stream()
                            .filter(TraceEvent.DynamicArtTransferState.class::isInstance)
                            .map(TraceEvent.DynamicArtTransferState.class::cast)
                            .toList();
            if (states.size() != 1) {
                throw new IllegalArgumentException(
                        "expected exactly one dynamic-art state at frame "
                                + frame + " but found " + states.size());
            }
            ordered.add(states.getFirst());
        }
        return DynamicArtTransfer.validateSegment(
                ordered, frameDomain, metadata.game(),
                new DynamicArtTransfer.LifecycleIdentity(), openingLedger);
    }

    public List<DynamicArtTransfer.Descriptor> terminalDynamicArtLedger() {
        return terminalDynamicArtLedger;
    }

    public List<Long> terminalDynamicArtTransferIds() {
        return terminalDynamicArtLedger.stream()
                .map(DynamicArtTransfer.Descriptor::transferId)
                .toList();
    }

    public List<TraceEvent.DynamicArtTransferState> dynamicArtTransferStates() {
        if (cachedDynamicArtTransferStates == null) {
            cachedDynamicArtTransferStates = computeDynamicArtTransferStates();
        }
        return cachedDynamicArtTransferStates;
    }

    Boolean cachedPreLevelPrefix() {
        return cachedHasRecordedPreLevelPrefix;
    }

    void cachePreLevelPrefix(boolean value) {
        cachedHasRecordedPreLevelPrefix = value;
    }

    private List<TraceEvent.DynamicArtTransferState> computeDynamicArtTransferStates() {
        return eventsByFrame.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .filter(TraceEvent.DynamicArtTransferState.class::isInstance)
                .map(TraceEvent.DynamicArtTransferState.class::cast)
                .toList();
    }

    List<DynamicArtTransfer.Descriptor> validateDynamicArtLifecycle(
            DynamicArtTransfer.LifecycleIdentity identity) {
        return validateDynamicArtLifecycle(identity, List.of());
    }

    List<DynamicArtTransfer.Descriptor> validateDynamicArtLifecycle(
            DynamicArtTransfer.LifecycleIdentity identity,
            List<DynamicArtTransfer.Descriptor> openingLedger) {
        List<TraceEvent.DynamicArtTransferState> states =
                dynamicArtTransferStates();
        StoredPhysicsFrameDomain domain = new StoredPhysicsFrameDomain(
                states.stream()
                        .map(TraceEvent.DynamicArtTransferState::frame)
                        .toList());
        return DynamicArtTransfer.validateSegment(
                states, domain, metadata.game(), identity, openingLedger);
    }

    public void validateAdvertisedLoadQueueStates() {
        if (!metadata.hasPerFrameLoadQueueState()) {
            return;
        }
        Set<String> expectedKinds = switch (metadata.game()) {
            case "s1" -> Set.of("s1_nemesis_plc");
            case "s2" -> Set.of("s2_nemesis_plc");
            case "s3k" -> Set.of("s3k_kos_direct", "s3k_kos_module");
            default -> throw new IllegalArgumentException(
                    "load queue capability is unsupported for game: " + metadata.game());
        };
        Set<Integer> frameDomain = new HashSet<>();
        for (TraceFrame frame : frames) {
            frameDomain.add(frame.frame());
        }
        for (Map.Entry<Integer, List<TraceEvent>> entry : eventsByFrame.entrySet()) {
            for (TraceEvent event : entry.getValue()) {
                if (event instanceof TraceEvent.LoadQueueState state) {
                    if (!frameDomain.contains(state.frame())) {
                        throw new IllegalArgumentException(
                                "load queue event outside physics frame domain: " + state.frame());
                    }
                    if (!expectedKinds.contains(state.kind())) {
                        throw new IllegalArgumentException(
                                "unexpected load queue kind: " + state.kind());
                    }
                    validateLoadQueueShape(state);
                }
            }
        }
        for (int frame : frameDomain) {
            List<TraceEvent.LoadQueueState> states = loadQueueStatesForFrame(frame);
            Set<String> found = new HashSet<>();
            for (TraceEvent.LoadQueueState state : states) {
                if (!found.add(state.kind())) {
                    throw new IllegalArgumentException(
                            "duplicate load queue kind at frame " + frame + ": " + state.kind());
                }
            }
            if (!found.equals(expectedKinds)) {
                throw new IllegalArgumentException(
                        "incomplete load queue state at frame " + frame
                                + ": expected " + expectedKinds + " but found " + found);
            }
        }
    }

    private static void validateLoadQueueShape(TraceEvent.LoadQueueState state) {
        boolean maskedIdentity = state.activeSource() == -1
                && state.activeDestination() == -1
                && state.totalWork() == -1;
        switch (state.kind()) {
            case "s1_nemesis_plc", "s2_nemesis_plc", "s3k_kos_module" -> {
                if (!state.busy()) {
                    return;
                }
                if (!maskedIdentity) {
                    throw new IllegalArgumentException(
                            "mutable prepared queue identity must be masked: " + state.kind());
                }
                if (state.prepared()) {
                    if (state.remainingWork() <= 0) {
                        throw new IllegalArgumentException(
                                "prepared queue requires positive remaining work: "
                                        + state.kind());
                    }
                } else if (state.remainingWork() != -1
                        || state.queuedFingerprints().isEmpty()) {
                    throw new IllegalArgumentException(
                            "unprepared queue requires only waiting fingerprints: "
                                    + state.kind());
                }
            }
            case "s3k_kos_direct" -> {
                if (state.busy() && (state.activeSource() < 0
                        || state.activeDestination() < -65536
                        || state.activeDestination() >= -1
                        || state.totalWork() != -1
                        || state.remainingWork() != -1)) {
                    throw new IllegalArgumentException(
                            "invalid direct Kosinski active projection");
                }
            }
            default -> throw new IllegalArgumentException(
                    "unknown load queue kind: " + state.kind());
        }
    }

    public List<TraceEvent> getEventsInRange(int startFrame, int endFrame) {
        List<TraceEvent> result = new ArrayList<>();
        for (int f = startFrame; f <= endFrame; f++) {
            result.addAll(getEventsForFrame(f));
        }
        return result;
    }

    /**
     * Returns advertised aux schemas that have no matching events in the
     * loaded aux stream.
     *
     * <p><strong>Diagnostic only.</strong> This guards against stale regenerated
     * fixtures where {@code metadata.json} claims per-frame diagnostics exist
     * but {@code aux_state.jsonl(.gz)} does not actually contain the records.
     * The result is used only for reports/tests and never feeds replay state.
     */
    public List<String> missingAdvertisedAuxSchemas() {
        List<String> missing = new ArrayList<>();
        if (metadata.hasPerFrameCageState()
                && !hasEventOfType(TraceEvent.CageState.class)) {
            missing.add("cage_state_per_frame");
        }
        if (metadata.hasPerFrameCageExecution()
                && !hasEventOfType(TraceEvent.CageExecution.class)) {
            missing.add("cage_execution_per_frame");
        }
        if (metadata.hasPerFrameVelocityWrite()
                && !hasEventOfType(TraceEvent.VelocityWrite.class)) {
            missing.add("velocity_write_per_frame");
        }
        if (metadata.hasPerFramePositionWrite()
                && !hasEventOfType(TraceEvent.PositionWrite.class)) {
            missing.add("position_write_per_frame");
        }
        if (metadata.hasPerFrameAizShipLoop()
                && !hasEventOfType(TraceEvent.AizShipLoop.class)) {
            missing.add("aiz_ship_loop_per_frame");
        }
        if (metadata.hasPerFrameSonicRecordPos()
                && !hasEventOfType(TraceEvent.SonicRecordPos.class)) {
            missing.add("sonic_record_pos_per_frame");
        }
        if (metadata.hasPerFrameCpuState()
                && !hasEventOfType(TraceEvent.CpuState.class)) {
            missing.add("cpu_state_per_frame");
        }
        if (metadata.hasPerFrameS1Obj64State()
                && !hasEventOfType(TraceEvent.S1Obj64State.class)) {
            missing.add("s1_obj64_state_per_frame");
        }
        if (metadata.hasPerFrameTailsCpuNormalStep()
                && !hasEventOfType(TraceEvent.TailsCpuNormalStep.class)) {
            missing.add("tails_cpu_normal_step_per_frame");
        }
        if (metadata.hasPerFrameSidekickInteractObject()
                && !hasEventOfType(TraceEvent.SidekickInteractObjectState.class)) {
            missing.add("sidekick_interact_object_per_frame");
        }
        if (metadata.hasPerFrameCnzCylinderState()
                && !hasEventOfType(TraceEvent.CnzCylinderState.class)) {
            missing.add("cnz_cylinder_state_per_frame");
        }
        if (metadata.hasPerFrameCnzCylinderExecution()
                && !hasEventOfType(TraceEvent.CnzCylinderExecution.class)) {
            missing.add("cnz_cylinder_execution_per_frame");
        }
        if (metadata.hasPerFrameCnzEventRam()
                && !hasEventOfType(TraceEvent.CnzEventRamState.class)) {
            missing.add("cnz_event_ram_per_frame");
        }
        if (metadata.hasPerFrameAirCountdownState()
                && !hasEventOfType(TraceEvent.AirCountdownState.class)) {
            missing.add("air_countdown_state_per_frame");
        }
        if (metadata.hasPerFrameRngCall()
                && !hasEventOfType(TraceEvent.RngCall.class)) {
            missing.add("rng_call_per_frame");
        }
        if (metadata.hasPerFrameAizBoundaryState()
                && !hasEventOfType(TraceEvent.AizBoundaryState.class)) {
            missing.add("aiz_boundary_state_per_frame");
        }
        if (metadata.hasPerFrameAizTransitionFloorSolid()
                && !hasEventOfType(TraceEvent.AizTransitionFloorSolidState.class)) {
            missing.add("aiz_transition_floor_solid_per_frame");
        }
        if (metadata.hasPerFrameAizHandoffTerrainState()
                && !hasEventOfType(TraceEvent.AizHandoffTerrainState.class)) {
            missing.add("aiz_handoff_terrain_state_per_frame");
        }
        return missing;
    }

    public List<TraceEvent.CageState> cageStatesForFrame(int frame) {
        List<TraceEvent.CageState> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CageState state) {
                states.add(state);
            }
        }
        return states;
    }

    public TraceEvent.CageExecution cageExecutionForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CageExecution execution) {
                return execution;
            }
        }
        return null;
    }

    /**
     * Returns generic state snapshots for the requested frame. These are
     * diagnostic-only aux events; replay must never hydrate engine state from
     * the preserved field map.
     */
    public List<TraceEvent.StateSnapshot> stateSnapshotsForFrame(int frame) {
        List<TraceEvent.StateSnapshot> snapshots = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.StateSnapshot snapshot) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    /**
     * Returns Sonic 1 Obj64 air-bubble maker snapshots for the requested frame.
     * Diagnostic-only; replay must not hydrate object state from these values.
     */
    public List<TraceEvent.S1Obj64State> s1Obj64StatesForFrame(int frame) {
        List<TraceEvent.S1Obj64State> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.S1Obj64State state) {
                states.add(state);
            }
        }
        return states;
    }

    public TraceEvent.Checkpoint latestCheckpointAtOrBefore(int frame) {
        int index = latestIndexedFrameAtOrBefore(checkpointFramesAscending, frame);
        return index >= 0 ? checkpointsByFrame.get(checkpointFramesAscending.get(index)) : null;
    }

    public TraceEvent.ZoneActState latestZoneActStateAtOrBefore(int frame) {
        int index = latestIndexedFrameAtOrBefore(zoneActStateFramesAscending, frame);
        return index >= 0 ? zoneActStatesByFrame.get(zoneActStateFramesAscending.get(index)) : null;
    }

    private Set<Class<? extends TraceEvent>> buildLatestEventIndexes() {
        Set<Class<? extends TraceEvent>> eventTypes = new HashSet<>();
        for (Map.Entry<Integer, List<TraceEvent>> entry : eventsByFrame.entrySet()) {
            int frame = entry.getKey();
            for (TraceEvent event : entry.getValue()) {
                eventTypes.add(event.getClass());
                if (event instanceof TraceEvent.Checkpoint checkpoint && !checkpointsByFrame.containsKey(frame)) {
                    checkpointsByFrame.put(frame, checkpoint);
                    checkpointFramesAscending.add(frame);
                } else if (event instanceof TraceEvent.ZoneActState state && !zoneActStatesByFrame.containsKey(frame)) {
                    zoneActStatesByFrame.put(frame, state);
                    zoneActStateFramesAscending.add(frame);
                }
            }
        }
        Collections.sort(checkpointFramesAscending);
        Collections.sort(zoneActStateFramesAscending);
        return Set.copyOf(eventTypes);
    }

    private static int latestIndexedFrameAtOrBefore(List<Integer> sortedFrames, int frame) {
        int index = Collections.binarySearch(sortedFrames, frame);
        if (index >= 0) {
            return index;
        }
        return -index - 2;
    }

    private boolean hasEventOfType(Class<? extends TraceEvent> eventType) {
        return observedEventTypes.contains(eventType);
    }

    /**
     * Returns the pre-trace ROM object snapshots emitted by the Lua recorder
     * at the moment gameplay begins but before trace frame 0 is written.
     *
     * <p>Schema v4+ aux files include one {@code object_state_snapshot} event
     * per occupied SST slot, stored at frame {@code -1}. Older schemas return
     * an empty list.
     */
    public List<TraceEvent.ObjectStateSnapshot> preTraceObjectSnapshots() {
        List<TraceEvent> events = eventsByFrame.getOrDefault(-1, Collections.emptyList());
        List<TraceEvent.ObjectStateSnapshot> snapshots = new ArrayList<>();
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.ObjectStateSnapshot snapshot) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    public TraceEvent.PlayerHistorySnapshot preTracePlayerHistorySnapshot() {
        List<TraceEvent> events = eventsByFrame.getOrDefault(-1, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.PlayerHistorySnapshot snapshot) {
                return snapshot;
            }
        }
        return null;
    }

    public TraceEvent.CpuStateSnapshot preTraceCpuStateSnapshot(String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(-1, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.CpuStateSnapshot snapshot
                    && characterCode.equalsIgnoreCase(snapshot.character())) {
                return snapshot;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.CpuState} event for the requested
     * trace frame and character, or {@code null} when the trace was recorded
     * without v6+ per-frame CPU snapshots or when no event is present for that
     * frame/character.
     *
     * <p><strong>Diagnostic only.</strong> Used by trace replay reports/tests
     * to compare engine sidekick CPU state against ROM values. It must never
     * be copied into engine state during the replay loop.
     */
    public TraceEvent.CpuState cpuStateForFrame(int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.CpuState state
                    && characterCode.equalsIgnoreCase(state.character())) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.OscillationState} event for the
     * requested trace frame, or {@code null} when the trace was recorded
     * without v6.1+ per-frame oscillation snapshots or when no event is
     * present for that frame.
     *
     * <p><strong>Diagnostic only.</strong> Used by trace replay tests to
     * compare engine {@code OscillationManager} state against authoritative
     * ROM values per frame. The engine must NOT hydrate its oscillator from
     * these values; it must produce the correct phase natively.
     */
    public TraceEvent.OscillationState oscillationStateForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.OscillationState state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.VObjState} (S1 object respawn-state
     * bit array) for the requested trace frame, or {@code null} when the trace
     * was recorded without v3.7+ per-frame {@code v_objstate} snapshots or when
     * no event is present for that frame.
     *
     * <p><strong>Diagnostic only.</strong> Comparator context for the
     * slot-interleave / slot-cadence cluster; the engine must NOT hydrate its
     * placement/respawn state from these bytes.
     */
    public TraceEvent.VObjState vObjStateForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.VObjState state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.VOscillate} (S1 global oscillation
     * state: the {@code v_oscillate} bitfield word + the $40-byte oscillating-
     * values array) for the requested trace frame, or {@code null} when the trace
     * was recorded without v3.10+ per-frame {@code v_oscillate} snapshots or when
     * no event is present for that frame.
     *
     * <p><strong>Diagnostic only.</strong> Comparator context for the osc-phase
     * cluster (e.g. SLZ2 f3353); the engine must NOT hydrate its oscillation
     * state from these bytes.
     */
    public TraceEvent.VOscillate vOscillateForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.VOscillate state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.LagState} (BizHawk authoritative lag
     * flag {@code emu.islagged()} + cumulative {@code emu.lagcount()}) for the
     * requested trace frame, or {@code null} when the trace was recorded without
     * v3.11+ per-frame {@code lag_state} snapshots or when no event is present for
     * that frame.
     *
     * <p><strong>Comparison-diagnostic by default, with one sanctioned
     * scheduling use.</strong> Gameplay state must never be hydrated from this
     * event, and no physics, object, camera or input value may be derived from
     * it. What it may do is answer the main-loop admission question that the
     * cross-game hardware-timing contract's first replay contract already
     * assigns to the recorded lag outcome: whether the ROM's main loop ran for
     * a represented row. A lagged row's V-int took {@code VBlank_Lag}
     * (docs/s1disasm/sonic.asm:656-657), which reaches no {@code ProcessPLC}
     * call (:712-746) while still advancing {@code v_vblank_count} (:684-687),
     * so the row owns a V-int closure with no mode handler.
     *
     * <p>{@code TraceRunSpecialStageRows.syntheticLagPhase} is the existing
     * precedent for that use; the presentation-bridge disposition in
     * {@code TraceRunFrameDriver} is the second. Both consume the boolean
     * alone -- the special-stage path to gate main-loop admission and select a
     * {@link com.openggf.game.resources.PlcLifecyclePhase}, the bridge path to
     * select the suppressed-closure {@code Disposition}. Anything reading
     * {@code lagcount}, or deriving a value rather than an admission, is
     * outside the sanction.
     */
    public TraceEvent.LagState lagStateForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.LagState state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.CameraBoundary} (S1 camera
     * vertical-boundary / look-shift) event for the requested trace frame, or
     * {@code null} when the trace was recorded without v3.7+ camera-boundary
     * snapshots or when no event is present for that frame.
     *
     * <p><strong>Diagnostic only.</strong> Comparator context for the MZ1 f2101
     * camera-boundary frontier; never engine write-back.
     */
    public TraceEvent.CameraBoundary cameraBoundaryForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.CameraBoundary boundary) {
                return boundary;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.VelocityWrite} event for the
     * requested trace frame and character, or {@code null} when the trace was
     * recorded without v6.4+ per-frame velocity-write snapshots or when no
     * event is present for that frame/character.
     *
     * <p><strong>Diagnostic only.</strong> Captures every M68K write to the
     * sidekick's {@code x_vel}/{@code y_vel} during ROM frame processing,
     * with each writing-instruction PC. Used to root-cause CNZ1 trace F3649
     * where ROM Tails {@code x_speed} jumps from -$48 to -$0A00 in a single
     * frame: the PC list pinpoints which ROM routine writes the value.
     */
    public TraceEvent.VelocityWrite velocityWriteForFrame(int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.VelocityWrite vw
                    && characterCode.equalsIgnoreCase(vw.character())) {
                return vw;
            }
        }
        return null;
    }

    /**
     * Returns the per-frame {@link TraceEvent.PositionWrite} event for the
     * requested trace frame and character, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> Captures M68K writes to the
     * sidekick's {@code x_pos}/{@code y_pos} during ROM frame processing,
     * with each writing-instruction PC. Used to root-cause CNZ1 trace F4790.
     */
    public TraceEvent.PositionWrite positionWriteForFrame(int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.PositionWrite pw
                    && characterCode.equalsIgnoreCase(pw.character())) {
                return pw;
            }
        }
        return null;
    }

    /**
     * Returns the focused AIZ ship-loop execution diagnostic for the requested
     * frame, or {@code null} when absent.
     */
    public TraceEvent.AizShipLoop aizShipLoopForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.AizShipLoop shipLoop) {
                return shipLoop;
            }
        }
        return null;
    }

    /**
     * Returns the focused Player_1 Sonic_RecordPos diagnostic for the
     * requested frame, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This maps delayed sidekick
     * Stat_table reads back to the ROM write source; replay code must not
     * hydrate state from it.
     */
    public TraceEvent.SonicRecordPos sonicRecordPosForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.SonicRecordPos recordPos) {
                return recordPos;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K Tails CPU normal-step diagnostic for the
     * requested frame and character, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This is report context for the
     * native engine simulation; replay code must not hydrate state from it.
     */
    public TraceEvent.TailsCpuNormalStep tailsCpuNormalStepForFrame(
            int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.TailsCpuNormalStep state
                    && characterCode.equalsIgnoreCase(state.character())) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K sidekick interact-object diagnostic for the
     * requested frame and character, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This is report context for object
     * handoff diagnosis; replay code must not hydrate state from it.
     */
    public TraceEvent.SidekickInteractObjectState sidekickInteractObjectStateForFrame(
            int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.SidekickInteractObjectState state
                    && characterCode.equalsIgnoreCase(state.character())) {
                return state;
            }
        }
        return null;
    }

    public List<TraceEvent.CnzCylinderState> cnzCylinderStatesForFrame(int frame) {
        List<TraceEvent.CnzCylinderState> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CnzCylinderState state) {
                states.add(state);
            }
        }
        return states;
    }

    public TraceEvent.CnzCylinderExecution cnzCylinderExecutionForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CnzCylinderExecution execution) {
                return execution;
            }
        }
        return null;
    }

    public TraceEvent.CnzEventRamState cnzEventRamStateForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.CnzEventRamState state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns S3K fixed Breathing_bubbles / Breathing_bubbles_P2 diagnostics
     * for the requested frame.
     *
     * <p><strong>Diagnostic only.</strong> This is report context for ROM-side
     * AirCountdown controller cadence and visible child lifetime; replay code
     * must not hydrate state from it.
     */
    public List<TraceEvent.AirCountdownState> airCountdownStatesForFrame(int frame) {
        List<TraceEvent.AirCountdownState> states = new ArrayList<>();
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.AirCountdownState state) {
                states.add(state);
            }
        }
        return states;
    }

    /**
     * Returns focused S3K Random_Number call-order diagnostics for a frame.
     * Diagnostic-only; callers must not hydrate engine RNG state from it.
     */
    public TraceEvent.RngCall rngCallForFrame(int frame) {
        for (TraceEvent event : eventsByFrame.getOrDefault(frame, Collections.emptyList())) {
            if (event instanceof TraceEvent.RngCall call) {
                return call;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K AIZ tree/boundary diagnostic for the requested
     * frame and character, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This is report context for ROM-side
     * pre/post visibility; replay code must not hydrate state from it.
     */
    public TraceEvent.AizBoundaryState aizBoundaryStateForFrame(
            int frame, String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return null;
        }
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.AizBoundaryState state
                    && characterCode.equalsIgnoreCase(state.character())) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K AIZ transition-floor solid diagnostic for the
     * requested frame, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This exposes ROM-side
     * {@code SolidObjectTop} path evidence for reports; replay code must not
     * hydrate state from it.
     */
    public TraceEvent.AizTransitionFloorSolidState aizTransitionFloorSolidStateForFrame(
            int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.AizTransitionFloorSolidState state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K AIZ1-&gt;AIZ2 fake-fire transition diagnostic for
     * the requested frame, or {@code null} when absent (legacy traces).
     *
     * <p><strong>Diagnostic only.</strong> This exposes the ROM
     * {@code Camera_Y_pos_BG_copy} fire ramp + BG event state for reports;
     * replay code must not hydrate state from it.
     */
    public TraceEvent.AizFireTransition aizFireTransitionForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.AizFireTransition state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns the focused S3K AIZ fire-handoff terrain diagnostic for the
     * requested frame, or {@code null} when absent.
     *
     * <p><strong>Diagnostic only.</strong> This exposes ROM-side terrain and
     * delayed-refresh state for reports; replay code must not hydrate state
     * from it.
     */
    public TraceEvent.AizHandoffTerrainState aizHandoffTerrainStateForFrame(int frame) {
        List<TraceEvent> events = eventsByFrame.getOrDefault(frame, Collections.emptyList());
        for (TraceEvent event : events) {
            if (event instanceof TraceEvent.AizHandoffTerrainState state) {
                return state;
            }
        }
        return null;
    }

    private static List<TraceFrame> loadPhysicsCsv(Path csvPath, TraceMetadata metadata)
            throws IOException {
        List<TraceFrame> frames = new ArrayList<>();
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
                frames.add(TraceFrame.parseCsvRow(trimmed));
            }
        }
        return frames;
    }

    // Public so other trace-profile loaders (e.g. SpecialStageTraceData,
    // com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData) can
    // reuse the aux jsonl parsing without duplicating it.
    public static Map<Integer, List<TraceEvent>> loadAuxEvents(Path auxPath)
            throws IOException {
        return loadAuxEvents(auxPath, null);
    }

    public static Map<Integer, List<TraceEvent>> loadAuxEvents(
            Path auxPath, TraceMetadata metadata)
            throws IOException {
        Map<Integer, List<TraceEvent>> map = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        boolean strictAdvertisedEvents = metadata != null
                && metadata.hasPerFrameDynamicArtTransferState();
        try (BufferedReader reader = TraceFiles.openReader(auxPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    TraceEvent event = TraceEvent.parseJsonLine(
                            trimmed, mapper, strictAdvertisedEvents);
                    map.computeIfAbsent(event.frame(), k -> new ArrayList<>()).add(event);
                }
            }
        }
        return map;
    }

    /**
     * @deprecated Use {@link TraceFiles#resolve(Path, String)} directly.
     */
    @Deprecated(forRemoval = false)
    public static Path resolveTraceFile(Path traceDirectory, String fileName) {
        return TraceFiles.resolve(traceDirectory, fileName);
    }

    /**
     * @deprecated Use {@link TraceFiles#openReader(Path)} directly.
     */
    @Deprecated(forRemoval = false)
    public static BufferedReader openTraceReader(Path path) throws IOException {
        return TraceFiles.openReader(path);
    }

    private static void rejectSpecialStageProfile(TraceMetadata metadata, Path metadataPath) {
        if ("s1_special_stage".equals(metadata.traceProfile())
                || "s2_special_stage".equals(metadata.traceProfile())
                || "s3k_special_stage".equals(metadata.traceProfile())) {
            throw new IllegalArgumentException("special-stage trace profile must use its game-owned "
                    + "reader: " + metadataPath);
        }
    }
}
