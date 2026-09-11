package com.openggf;

import com.openggf.game.GameMode;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;

import java.util.List;

/**
 * Segment-advance state machine for a visual multi-stage trace run session
 * (spec: docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md).
 * A pure function of each frame's {@code (GameMode, cursorFrame)} pair — no
 * engine access, independently unit-testable, and never throws for any mode
 * sequence: an unrecognised mode transition (flicker, wrong target mode)
 * simply keeps waiting rather than emitting an event.
 *
 * <p>States: {@code COMPARING(segment k)} — the mode matches segment k's
 * expected mode — moves to {@code IN_TRANSITION(k -> k+1)} (comparator
 * detached) the first frame the mode leaves that expectation, then back to
 * {@code COMPARING(k+1)} — emitting an {@link AdvanceAction} — the first
 * frame the mode settles into segment {@code k+1}'s expected mode. The LAST
 * segment never transitions out: while {@code COMPARING} it instead watches
 * {@code cursorFrame} for that segment's trace frames being exhausted and
 * emits {@link EndOfRun}.
 *
 * <p>Expected-mode mapping is a pure function of {@link TraceRunManifest.Segment#kind()}
 * — data-driven, no zone checks.
 */
final class RunSegmentAdvancer {

    /** An event emitted by {@link #onFrame}; a {@code null} return means no-op this frame. */
    sealed interface Event permits AdvanceAction, EndOfRun {}

    /** Re-seek the BK2 movie to {@code reseekOffset} and rebind onto segment {@code nextSegmentIndex}. */
    record AdvanceAction(int reseekOffset, int nextSegmentIndex) implements Event {}

    /** The run's last segment has exhausted its trace frames while still in its expected mode. */
    enum EndOfRun implements Event { INSTANCE }

    private final List<TraceRunSegmentDescriptor> segments;
    private int segmentIndex;
    private boolean inTransition;
    private boolean done;

    RunSegmentAdvancer(List<TraceRunSegmentDescriptor> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("RunSegmentAdvancer requires at least one segment");
        }
        this.segments = segments;
    }

    /** The segment index currently being compared (or just advanced onto). */
    int currentSegmentIndex() {
        return segmentIndex;
    }

    boolean inTransition() {
        return inTransition;
    }

    /**
     * Feeds one frame's observed mode/cursor into the state machine. Returns
     * the emitted {@link Event}, or {@code null} when nothing happens this
     * frame (still comparing, still mid-transition, or the run already ended).
     */
    Event onFrame(GameMode mode, int cursorFrame) {
        if (done) {
            return null;
        }
        TraceRunManifest.Segment current = segments.get(segmentIndex).segment();
        boolean isLast = segmentIndex == segments.size() - 1;
        if (!inTransition) {
            if (isLast) {
                int endFrame = current.bk2FrameOffset() + current.traceFrameCount();
                if (cursorFrame >= endFrame) {
                    done = true;
                    return EndOfRun.INSTANCE;
                }
                return null;
            }
            if (mode != expectedMode(current)) {
                inTransition = true;
            }
            return null;
        }
        TraceRunManifest.Segment next = segments.get(segmentIndex + 1).segment();
        if (mode != expectedMode(next)) {
            return null;
        }
        segmentIndex++;
        inTransition = false;
        return new AdvanceAction(next.bk2FrameOffset(), segmentIndex);
    }

    private static GameMode expectedMode(TraceRunManifest.Segment segment) {
        return switch (segment.kind()) {
            case "level" -> GameMode.LEVEL;
            case "bonus_stage" -> GameMode.BONUS_STAGE;
            case "special_stage" -> GameMode.SPECIAL_STAGE;
            default -> throw new IllegalStateException(
                    "Unknown segment kind '" + segment.kind() + "'");
        };
    }
}
