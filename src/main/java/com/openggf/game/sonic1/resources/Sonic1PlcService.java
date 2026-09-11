package com.openggf.game.sonic1.resources;

import com.openggf.data.Rom;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import com.openggf.level.resources.PlcParser;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Sonic 1-owned façade for the ROM's logical Pattern Load Cue FIFO. */
public final class Sonic1PlcService
        implements PlcLifecycleService, RewindSnapshottable<NemesisPlcQueueSnapshot> {
    public static final String REWIND_KEY = "sonic1-plc-service";
    private static final int SAFE_QUEUE_CAPACITY = 15;

    private final Rom rom;
    private final NemesisPlcServiceQueue queue;
    /**
     * Optional loop-tail arm gate. Absent outside a hardware-timed session,
     * where the arm is unconditional exactly as it always was. The queue
     * itself is shared with Sonic 2, so the gate lives here and never inside
     * {@link NemesisPlcServiceQueue}.
     */
    private Sonic1PlcArmTiming armTiming;

    public Sonic1PlcService(Rom rom) {
        this(rom, new NemesisPlcServiceQueue());
    }

    Sonic1PlcService(Rom rom, NemesisPlcServiceQueue queue) {
        this.rom = Objects.requireNonNull(rom, "rom");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /** Models S1 {@code NewPLC}: replace idle waiting descriptors from one ROM PLC. */
    public void replaceQueued(int plcId) throws IOException {
        Submission submission = readSubmission(plcId);
        requireReplacementFits(submission.definition());
        queue.replaceQueued(submission.definition(), submission.patternCounts());
    }

    /** Models S1 {@code AddPLC}: append every descriptor from one ROM PLC. */
    public void append(int plcId) throws IOException {
        Submission submission = readSubmission(plcId);
        requireAppendFits(submission.definition());
        queue.append(submission.definition(), submission.patternCounts());
    }

    /** Models S1 {@code ClearPLC}; an active decoder is never interrupted. */
    public void clearQueued() {
        queue.clearQueued();
    }

    /** Preflights and atomically commits an ordered native Clear/New/Add PLC sequence. */
    public void transact(Operation... operations) throws IOException {
        List<PreparedOperation> prepared = new java.util.ArrayList<>(operations.length);
        int occupied = occupiedDescriptorCount();
        boolean active = queue.capture().activeEntry() != null;
        for (Operation operation : operations) {
            Submission submission = operation.kind() == OperationKind.CLEAR ? null : readSubmission(operation.plcId());
            switch (operation.kind()) {
                case CLEAR -> {
                    if (active) throw new IllegalStateException("cannot mutate queued PLC entries while the decoder is active");
                    occupied = 0;
                }
                case REPLACE -> {
                    if (active) throw new IllegalStateException("cannot mutate queued PLC entries while the decoder is active");
                    occupied = submission.definition().entries().size();
                }
                case APPEND -> occupied += submission.definition().entries().size();
            }
            if (occupied > SAFE_QUEUE_CAPACITY) throw new IllegalStateException("Sonic 1 PLC queue cannot use the retail-retained sixteenth slot");
            prepared.add(new PreparedOperation(operation.kind(), submission));
        }
        for (PreparedOperation operation : prepared) {
            if (operation.kind() == OperationKind.CLEAR) queue.clearQueued();
            else if (operation.kind() == OperationKind.REPLACE) queue.replaceQueued(operation.submission().definition(), operation.submission().patternCounts());
            else queue.append(operation.submission().definition(), operation.submission().patternCounts());
        }
    }

    public static Operation clear() { return new Operation(OperationKind.CLEAR, -1); }
    public static Operation replace(int plcId) { return new Operation(OperationKind.REPLACE, plcId); }
    public static Operation appendOperation(int plcId) { return new Operation(OperationKind.APPEND, plcId); }
    public enum OperationKind { CLEAR, REPLACE, APPEND }
    public record Operation(OperationKind kind, int plcId) { }

    /** Binds this service's loop-tail arm to a session's hardware-timing ledger. */
    public void bindArmTiming(Sonic1PlcArmTiming armTiming) {
        this.armTiming = armTiming;
    }

    @Override
    public boolean ownsTimedLoopTailArm() {
        return armTiming != null && armTiming.isRecordedAuthority();
    }

    /** Submits the arm {@code RunPLC} is about to make, at the loop-tail boundary. */
    public void submitArmableHead() {
        if (armTiming != null) {
            armTiming.submitArmableHead(queue.capture());
        }
    }

    /** Models S1 {@code RunPLC}, which arms only the current FIFO head. */
    public void prepare() {
        if (armTiming != null && !armTiming.releaseArm()) {
            return;
        }
        queue.prepareHead();
    }

    /** Models S1's three-pattern ordinary level VBlank service. */
    public void serviceLevelVBlank() {
        queue.servicePatterns(3);
    }

    /** Models S1's nine-pattern fast VBlank service. */
    public void serviceFastVBlank() {
        queue.servicePatterns(9);
    }

    /** Returns whether either the active decoder or a waiting ROM PLC descriptor remains. */
    public boolean isBusy() {
        return queue.isBusy();
    }

    /** Immutable logical FIFO state for producer-contract tests and rewind adapters. */
    @Override
    public NemesisPlcQueueSnapshot capture() { return queue.capture(); }

    /** Restores only logical queue state; restoration neither parses nor submits work. */
    @Override
    public void restore(NemesisPlcQueueSnapshot snapshot) {
        queue.restore(snapshot);
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public void resetForMissingSnapshot() {
        queue.restore(new NemesisPlcQueueSnapshot(null, List.of()));
    }

    @Override
    public void serviceVBlank(PlcLifecyclePhase phase) {
        switch (phase) {
            case TITLE_SCREEN, LEVEL_SELECT, LEVEL_TITLE_CARD, PALETTE_FADE,
                    SPECIAL_STAGE_RESULTS, CREDITS_TEXT, ENDING, POST_CREDITS ->
                    serviceFastVBlank();
            case ORDINARY_LEVEL, CREDITS_DEMO, CREDITS_DEMO_FADE, NORMAL_PAUSE ->
                    serviceLevelVBlank();
            case CONTINUE_SCREEN, LAG, SPECIAL_STAGE, TWO_PLAYER_RESULTS, SPECIAL_STAGE_PAUSE -> {
                // The selected S1 VBlank handler does not service PLCs.
            }
        }
    }

    @Override
    public List<QueueDiagnosticSnapshot> captureQueueDiagnostics() {
        return List.of(queue.captureDiagnostics(
                QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC,
                List.of()));
    }

    @Override
    public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
        return switch (phase) {
            case TITLE_SCREEN, LEVEL_SELECT, LEVEL_TITLE_CARD, ORDINARY_LEVEL,
                    PALETTE_FADE, SPECIAL_STAGE_RESULTS, CREDITS_TEXT, CREDITS_DEMO -> true;
            case CONTINUE_SCREEN, LAG, SPECIAL_STAGE, TWO_PLAYER_RESULTS, CREDITS_DEMO_FADE,
                    ENDING, POST_CREDITS, NORMAL_PAUSE, SPECIAL_STAGE_PAUSE -> false;
        };
    }

    @Override
    public void prepareAfterLoop(PlcLifecyclePhase phase) {
        if (hasPreparationBoundary(phase)) {
            prepare();
        }
    }

    private Submission readSubmission(int plcId) throws IOException {
        validatePlcId(plcId);
        PlcDefinition definition = PlcParser.parse(rom, Sonic1Constants.ART_LOAD_CUES_ADDR, plcId);
        return new Submission(definition, NemesisPlcPatternCounts.derive(rom, definition));
    }

    private static void validatePlcId(int plcId) {
        if (plcId < 0 || plcId >= Sonic1Constants.ART_LOAD_CUES_ENTRY_COUNT) {
            throw new IllegalArgumentException("Sonic 1 PLC ID out of range: " + plcId
                    + " (expected 0-" + (Sonic1Constants.ART_LOAD_CUES_ENTRY_COUNT - 1) + ")");
        }
    }

    private void requireAppendFits(PlcDefinition definition) {
        if (occupiedDescriptorCount() + definition.entries().size() > SAFE_QUEUE_CAPACITY) {
            throw new IllegalStateException("Sonic 1 PLC queue cannot use the retail-retained sixteenth slot");
        }
    }

    private void requireReplacementFits(PlcDefinition definition) {
        if (definition.entries().size() > SAFE_QUEUE_CAPACITY) {
            throw new IllegalStateException("Sonic 1 PLC replacement exceeds safe queue capacity");
        }
    }

    private int occupiedDescriptorCount() {
        return queue.queuedEntryCount() + (queue.capture().activeEntry() == null ? 0 : 1);
    }

    private record Submission(PlcDefinition definition, List<Integer> patternCounts) {
    }
    private record PreparedOperation(OperationKind kind, Submission submission) { }
}
