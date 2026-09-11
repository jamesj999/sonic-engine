package com.openggf.tools.audio.completerun;

import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.Observation;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Baseline;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CutoffFrontier;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManifestSegment;
import java.util.List;
import java.util.Objects;

/** Streaming row and publication transaction for production OpenGGF capture. */
final class CompleteRunAudioCaptureReducer implements AutoCloseable {
    private enum State { OPEN, ACTIVE_ROW, READY_TO_FINISH, FINISHED, ABORTED }

    private final CompleteRunFixture fixture;
    private final CompleteRunAudioCaptureStore.Writer writer;
    private final CompleteRunAudioFrameProjection projection;
    private State state = State.OPEN;
    private PreRowBoundary activeBoundary;
    private AudioLogicalSnapshot lastPostSnapshot;
    private int nextFrame;
    private int segmentIndex;
    private long nextObservationOrdinal;
    private long nextChipOrdinal;

    CompleteRunAudioCaptureReducer(CompleteRunFixture fixture,
            CompleteRunAudioCaptureStore.Writer writer,
            CompleteRunAudioFrameProjection projection) {
        this.fixture = Objects.requireNonNull(fixture, "fixture");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public void beforeFrame(PreRowBoundary boundary) throws Exception {
        try {
            requireState(State.OPEN, "capture is not ready for a pre-row boundary");
            Objects.requireNonNull(boundary, "pre-row boundary");
            if (boundary.absoluteFrame() != nextFrame) {
                throw new IllegalStateException("capture rows must be consecutive from row zero");
            }
            if (nextFrame >= fixture.exclusiveEnd()) {
                throw new IllegalStateException("capture reached its exclusive terminal boundary");
            }
            acceptObservations(boundary.observationsBeforeRow());
            requireOrdinal(boundary.firstRowEventOrdinal(), nextObservationOrdinal,
                    "pre-row observation ordinal");
            if (nextFrame == fixture.firstFrame()) {
                Baseline baseline = Objects.requireNonNull(projection.baseline(boundary),
                        "projected baseline");
                if (baseline.absoluteFrame() != nextFrame) {
                    throw new IllegalArgumentException(
                            "projected baseline does not match the pre-row boundary");
                }
                writer.append(baseline);
            }
            activeBoundary = boundary;
            state = State.ACTIVE_ROW;
        } catch (Exception | Error failure) {
            abortAfter(failure);
            throw failure;
        }
    }

    public boolean acceptFrame(RowObservation observation) throws Exception {
        try {
            requireState(State.ACTIVE_ROW, "capture has no active pre-row boundary");
            Objects.requireNonNull(observation, "row observation");
            if (observation.absoluteFrame() != nextFrame
                    || activeBoundary.absoluteFrame() != nextFrame) {
                throw new IllegalStateException(
                        "completed row does not match its pre-row boundary");
            }
            acceptObservations(observation.events());
            if (nextFrame < fixture.firstFrame()) {
                projection.discardedFrame(activeBoundary, observation);
            } else {
                String segment = segment(nextFrame);
                Frame frame = Objects.requireNonNull(projection.retainedFrame(
                        activeBoundary, observation, segment, nextChipOrdinal),
                        "projected frame");
                validateFrame(frame, segment);
                writer.append(frame);
                nextChipOrdinal += frame.chipEvents().size();
            }
            lastPostSnapshot = observation.logicalSnapshot();
            activeBoundary = null;
            nextFrame++;
            state = nextFrame == fixture.exclusiveEnd()
                    ? State.READY_TO_FINISH : State.OPEN;
            return state == State.OPEN;
        } catch (Exception | Error failure) {
            abortAfter(failure);
            throw failure;
        }
    }

    public void finish(AudioLogicalSnapshot terminalSnapshot) throws Exception {
        try {
            requireState(State.READY_TO_FINISH,
                    "capture has not reached its exclusive terminal boundary");
            if (!Objects.requireNonNull(terminalSnapshot, "terminal snapshot")
                    .equals(lastPostSnapshot)) {
                throw new IllegalArgumentException(
                        "terminal snapshot is not the last completed row boundary");
            }
            CutoffFrontier cutoff = Objects.requireNonNull(projection.cutoff(),
                    "projected cutoff frontier");
            writer.append(cutoff);
            writer.finish(null);
            writer.close();
            state = State.FINISHED;
        } catch (Exception | Error failure) {
            abortAfter(failure);
            throw failure;
        }
    }

    public void abort() throws Exception {
        if (state == State.FINISHED || state == State.ABORTED) return;
        Throwable failure = abortResources(null);
        state = State.ABORTED;
        if (failure != null) rethrow(failure);
    }

    @Override public void close() throws Exception {
        if (state != State.FINISHED) abort();
    }

    private void validateFrame(Frame frame, String segment) {
        if (frame.absoluteFrame() != nextFrame || !Objects.equals(frame.segment(), segment)) {
            throw new IllegalArgumentException(
                    "projected frame does not match its retained row identity");
        }
        if (frame.chipEvents() == null) {
            throw new IllegalArgumentException("frame chip events must be observed");
        }
        long required = nextChipOrdinal;
        for (var chip : frame.chipEvents()) {
            requireOrdinal(chip.ordinal(), required++, "chip ordinal");
        }
    }

    private void acceptObservations(List<Observation> observations) {
        for (Observation observation : observations) {
            requireOrdinal(observation.ordinal(), nextObservationOrdinal++,
                    "observation ordinal");
        }
    }

    private String segment(int frame) {
        List<ManifestSegment> segments = fixture.segments();
        while (segmentIndex < segments.size()
                && frame >= segments.get(segmentIndex).exclusiveEnd()) segmentIndex++;
        if (segmentIndex == segments.size()) return null;
        ManifestSegment segment = segments.get(segmentIndex);
        return frame >= segment.firstFrame() ? segment.id() : null;
    }

    private void requireState(State required, String message) {
        if (state != required) throw new IllegalStateException(message);
    }

    private static void requireOrdinal(long actual, long required, String label) {
        if (actual != required) {
            throw new IllegalArgumentException(label + " must be globally contiguous");
        }
    }

    private void abortAfter(Throwable primary) {
        if (state == State.FINISHED || state == State.ABORTED) return;
        abortResources(primary);
        activeBoundary = null;
        lastPostSnapshot = null;
        state = State.ABORTED;
    }

    private Throwable abortResources(Throwable aggregate) {
        try { writer.abort(); }
        catch (Exception | Error cleanup) { aggregate = append(aggregate, cleanup); }
        try { projection.abort(); }
        catch (Exception | Error cleanup) { aggregate = append(aggregate, cleanup); }
        return aggregate;
    }

    private static Throwable append(Throwable aggregate, Throwable cleanup) {
        if (aggregate == null) return cleanup;
        aggregate.addSuppressed(cleanup);
        return aggregate;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        throw (Error) failure;
    }
}
