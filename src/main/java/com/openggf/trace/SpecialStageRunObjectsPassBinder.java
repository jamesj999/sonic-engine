package com.openggf.trace;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.SpecialStageInputMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Comparison-only cursor over atomic ROM {@code RunObjects} completions.
 *
 * <p>The special-stage 68K loop is not paced one-to-one with VBlank
 * observations: one observation can complete no object pass, while another can
 * expose two ordered completions. The recorder therefore binds each pass
 * forward to the first eligible observation at or after its return cursor.
 * Eligibility normally means non-lag; the recorder's single terminal pass is
 * instead owned by the raw finish observation because the ROM exits before a
 * later non-lag row. Replay consumes every pass bound to that exact observation
 * and does not step for an empty list.
 */
public final class SpecialStageRunObjectsPassBinder {

    /** One validated pass plus the raw physical controller sample it consumed. */
    public record CompletedPass(
            TraceEvent.StateSnapshot snapshot,
            int sequence,
            int firstEligibleFrame,
            int completionCursorFrame,
            int inputSampleFrame,
            int inputSampleBk2Frame,
            int previousInputSampleFrame,
            int previousInputSampleBk2Frame,
            int inputSampleSequence,
            int p1Held,
            int p2Held,
            int previousP1Held,
            int previousP2Held) {

        /** Mirrors ReadJoypads: newly pressed bits are current held minus prior held. */
        public int p1Pressed() {
            return p1Held & ~previousP1Held & 0xFF;
        }

        public int p2Pressed() {
            return p2Held & ~previousP2Held & 0xFF;
        }
    }

    private final List<CompletedPass> passes;
    private final int observationFrameCount;
    private final IntPredicate eligibleObservation;
    private final Integer bk2FrameOffset;
    private final List<Bk2FrameInput> movieFrames;
    private int nextIndex;
    private CompletedPass latestCompleted;

    public SpecialStageRunObjectsPassBinder(List<TraceEvent.StateSnapshot> snapshots) {
        this(snapshots, Integer.MAX_VALUE, ignored -> true);
    }

    public SpecialStageRunObjectsPassBinder(
            List<TraceEvent.StateSnapshot> snapshots,
            int observationFrameCount,
            IntPredicate eligibleObservation) {
        this(snapshots, observationFrameCount, eligibleObservation, null, null);
    }

    /**
     * Creates a pass cursor whose auxiliary input diagnostics are verified
     * against the identified current and previous BK2 rows before replay.
     */
    public SpecialStageRunObjectsPassBinder(
            List<TraceEvent.StateSnapshot> snapshots,
            int observationFrameCount,
            IntPredicate eligibleObservation,
            int bk2FrameOffset,
            List<Bk2FrameInput> movieFrames) {
        this(snapshots, observationFrameCount, eligibleObservation,
                Integer.valueOf(bk2FrameOffset), movieFrames);
    }

    private SpecialStageRunObjectsPassBinder(
            List<TraceEvent.StateSnapshot> snapshots,
            int observationFrameCount,
            IntPredicate eligibleObservation,
            Integer bk2FrameOffset,
            List<Bk2FrameInput> movieFrames) {
        if (observationFrameCount < 0) {
            throw new IllegalArgumentException("observation frame count is negative");
        }
        this.observationFrameCount = observationFrameCount;
        this.eligibleObservation = Objects.requireNonNull(
                eligibleObservation, "eligibleObservation");
        this.bk2FrameOffset = bk2FrameOffset;
        this.movieFrames = movieFrames == null ? null : List.copyOf(movieFrames);
        this.passes = snapshots.stream()
                .sorted(Comparator.comparingInt(SpecialStageRunObjectsPassBinder::sequence))
                .map(SpecialStageRunObjectsPassBinder::parse)
                .toList();
        validate();
    }

    /**
     * Returns all passes completed for exactly this observation, in execution
     * order. Calling past an unconsumed observation is a malformed replay.
     */
    public List<CompletedPass> passesForObservation(int traceFrame) {
        if (hasRemaining() && passes.get(nextIndex).snapshot().frame() < traceFrame) {
            throw new IllegalStateException(
                    "run_objects_end observation " + passes.get(nextIndex).snapshot().frame()
                            + " was skipped before replay frame " + traceFrame);
        }
        List<CompletedPass> result = new ArrayList<>();
        while (hasRemaining() && passes.get(nextIndex).snapshot().frame() == traceFrame) {
            latestCompleted = passes.get(nextIndex++);
            result.add(latestCompleted);
        }
        return List.copyOf(result);
    }

    public Optional<CompletedPass> latestCompleted() {
        return Optional.ofNullable(latestCompleted);
    }

    public boolean hasRemaining() {
        return nextIndex < passes.size();
    }

    private void validate() {
        CompletedPass previous = null;
        for (int i = 0; i < passes.size(); i++) {
            CompletedPass pass = passes.get(i);
            if (pass.sequence() != i) {
                throw new IllegalArgumentException(
                        "run_objects_end sequence discontinuity: expected " + i
                                + ", got " + pass.sequence());
            }
            int observation = pass.snapshot().frame();
            if (pass.firstEligibleFrame() > observation) {
                throw new IllegalArgumentException(
                        "run_objects_end frame precedes first eligible frame for sequence "
                                + pass.sequence());
            }
            if (pass.completionCursorFrame() > observation) {
                throw new IllegalArgumentException(
                        "run_objects_end frame precedes completion cursor for sequence "
                                + pass.sequence());
            }
            if (pass.completionCursorFrame() < pass.firstEligibleFrame()) {
                throw new IllegalArgumentException(
                        "run_objects_end completion cursor precedes input sample for sequence "
                                + pass.sequence());
            }
            int firstEligible = pass.completionCursorFrame();
            while (firstEligible < observationFrameCount
                    && !eligibleObservation.test(firstEligible)) {
                firstEligible++;
            }
            if (firstEligible >= observationFrameCount) {
                throw new IllegalArgumentException(
                        "run_objects_end has no eligible observation at/after completion for sequence "
                                + pass.sequence());
            }
            if (observation != firstEligible) {
                throw new IllegalArgumentException(
                        "run_objects_end frame " + observation
                                + " is not the first eligible observation " + firstEligible
                                + " at/after completion for sequence " + pass.sequence());
            }
            if (pass.inputSampleFrame() > pass.completionCursorFrame()) {
                throw new IllegalArgumentException(
                        "run_objects_end input sample follows pass completion for sequence "
                                + pass.sequence());
            }
            if (pass.inputSampleBk2Frame() < 0
                    || pass.previousInputSampleBk2Frame() < 0
                    || pass.inputSampleSequence() < 0) {
                throw new IllegalArgumentException(
                        "run_objects_end input source identity is negative for sequence "
                                + pass.sequence());
            }
            validateByte(pass.p1Held(), "p1_held", pass.sequence());
            validateByte(pass.p2Held(), "p2_held", pass.sequence());
            validateByte(pass.previousP1Held(), "previous_p1_held", pass.sequence());
            validateByte(pass.previousP2Held(), "previous_p2_held", pass.sequence());
            if (movieFrames != null) {
                validateInputAgainstMovie(pass);
            }
            if (previous != null) {
                if (pass.inputSampleSequence() != previous.inputSampleSequence() + 1) {
                    throw new IllegalArgumentException(
                            "run_objects_end input sample sequence discontinuity at pass "
                                    + pass.sequence());
                }
                if (pass.previousInputSampleFrame() != previous.inputSampleFrame()
                        || pass.previousInputSampleBk2Frame()
                        != previous.inputSampleBk2Frame()) {
                    throw new IllegalArgumentException(
                            "run_objects_end previous BK2 identity chain discontinuity at pass "
                                    + pass.sequence());
                }
                if (pass.previousP1Held() != previous.p1Held()) {
                    throw new IllegalArgumentException(
                            "run_objects_end P1 held chain discontinuity at sequence "
                                    + pass.sequence());
                }
                if (pass.previousP2Held() != previous.p2Held()) {
                    throw new IllegalArgumentException(
                            "run_objects_end P2 held chain discontinuity at sequence "
                                    + pass.sequence());
                }
            }
            previous = pass;
        }
    }

    private void validateInputAgainstMovie(CompletedPass pass) {
        int expectedCurrent = bk2FrameOffset + pass.inputSampleFrame();
        int expectedPrevious = bk2FrameOffset + pass.previousInputSampleFrame();
        if (pass.inputSampleBk2Frame() != expectedCurrent
                || pass.previousInputSampleBk2Frame() != expectedPrevious) {
            throw new IllegalArgumentException(
                    "run_objects_end BK2 identity mismatch at sequence " + pass.sequence());
        }
        Bk2FrameInput current = movieRow(pass.inputSampleBk2Frame(), pass.sequence());
        Bk2FrameInput previous = movieRow(
                pass.previousInputSampleBk2Frame(), pass.sequence());
        SpecialStageInputMapper.MappedInput mapped = SpecialStageInputMapper.map(
                RecordedInputSnapshots.fromBk2(current, previous));
        SpecialStageInputMapper.MappedInput previousMapped = SpecialStageInputMapper.map(
                RecordedInputSnapshots.fromBk2(previous, null));
        requireHeld(pass.p1Held(), mapped.p1Held(), "P1 held", pass.sequence());
        requireHeld(pass.p2Held(), mapped.p2Held(), "P2 held", pass.sequence());
        requireHeld(pass.previousP1Held(), previousMapped.p1Held(),
                "previous P1 held", pass.sequence());
        requireHeld(pass.previousP2Held(), previousMapped.p2Held(),
                "previous P2 held", pass.sequence());
    }

    private Bk2FrameInput movieRow(int index, int sequence) {
        if (index < 0 || index >= movieFrames.size()) {
            throw new IllegalArgumentException(
                    "run_objects_end BK2 identity needs missing row " + index
                            + " at sequence " + sequence);
        }
        Bk2FrameInput row = movieFrames.get(index);
        if (row.frameIndex() != index) {
            throw new IllegalArgumentException(
                    "run_objects_end BK2 identity " + index
                            + " resolves to movie row " + row.frameIndex()
                            + " at sequence " + sequence);
        }
        return row;
    }

    private static void requireHeld(int auxiliary, int movie, String label, int sequence) {
        if (auxiliary != movie) {
            throw new IllegalArgumentException(
                    "run_objects_end " + label + " differs from BK2 at sequence " + sequence
                            + ": aux=" + auxiliary + ", BK2=" + movie);
        }
    }

    private static CompletedPass parse(TraceEvent.StateSnapshot snapshot) {
        return new CompletedPass(
                snapshot,
                number(snapshot, "pass_sequence"),
                number(snapshot, "first_eligible_frame"),
                number(snapshot, "completion_cursor_frame"),
                number(snapshot, "input_sample_frame"),
                number(snapshot, "input_sample_bk2_frame"),
                number(snapshot, "previous_input_sample_frame"),
                number(snapshot, "previous_input_sample_bk2_frame"),
                number(snapshot, "input_sample_sequence"),
                number(snapshot, "p1_held"),
                number(snapshot, "p2_held"),
                number(snapshot, "previous_p1_held"),
                number(snapshot, "previous_p2_held"));
    }

    private static int sequence(TraceEvent.StateSnapshot snapshot) {
        return number(snapshot, "pass_sequence");
    }

    private static int number(TraceEvent.StateSnapshot snapshot, String field) {
        Object raw = snapshot.fields().get(field);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            throw new IllegalArgumentException("run_objects_end missing " + field);
        }
        return Integer.parseInt(raw.toString());
    }

    private static void validateByte(int value, String field, int sequence) {
        if ((value & ~0xFF) != 0) {
            throw new IllegalArgumentException(
                    "run_objects_end " + field + " is not a byte at sequence " + sequence);
        }
    }
}
