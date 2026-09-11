package com.openggf.tests.trace.s2;

import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the run segments that precede a special-stage segment, so the engine
 * enters the special stage having <em>itself</em> submitted whatever dynamic-art
 * transfers are still in flight at that point.
 *
 * <h2>Why a segment can need this</h2>
 * <p>A special-stage segment recorded inside a multi-segment run is a window
 * into one continuous movie: its row 0 is mid-run, not a boot. The recorder
 * therefore declares, in {@code run_manifest.json}, the dynamic-art ledger the
 * segment opens with ({@code dynamic_art_initial_ledger_descriptors}). A
 * replay that starts cold at that row has an empty DMA queue and cannot have
 * those transfers outstanding — not because the engine is wrong, but because
 * the replay's scope is narrower than the recording's.
 *
 * <p>The fix is to widen the scope, not to relax the comparison: replay the
 * preceding segment(s) from the last point at which the recorded ledger was
 * empty, driving the engine only from the recorded BK2 controller stream. The
 * transfers then become outstanding because the engine submitted them, which
 * keeps the whole path comparison-only (hard rule 4): nothing from the manifest
 * or the trace is written into engine state — the manifest is read only to
 * decide <em>which movie rows to replay</em>.
 *
 * <h2>Which segments are replayed</h2>
 * <p>Purely a manifest-topology decision, with no stage number, directory name,
 * zone or frame index in the predicate: walk backwards from the target segment
 * while the segment being entered declares a non-empty opening ledger, and
 * start at the first segment whose own opening ledger is empty (a segment that
 * inherits nothing is a legitimate cold start). Every segment from there up to
 * the target is replayed, together with the movie rows of the gaps between
 * them, which is where a level's final player-art submission lands.
 *
 * <h2>Compared or not</h2>
 * <p>The predecessor is replayed <b>uncompared</b>. Its purpose here is to
 * evolve engine state to the state that existed when the special stage began —
 * the same thing the run-chain harness does for its uncompared interiors.
 * Comparing it too would be stricter, but it would also couple these
 * special-stage lanes to unrelated level-segment defects and report those as
 * special-stage failures.
 */
final class S2SpecialStagePredecessorReplay {

    private S2SpecialStagePredecessorReplay() {
    }

    /**
     * Replays every segment the target segment inherits dynamic-art state from.
     * A no-op when the target's declared opening ledger is empty.
     *
     * @param runRoot       run directory holding {@code run_manifest.json}
     * @param manifest      the run manifest
     * @param targetIndex   manifest index of the special-stage segment
     */
    static void replayInheritedPrefix(
            Path runRoot, TraceRunManifest manifest, int targetIndex)
            throws IOException {
        List<TraceRunManifest.Segment> prefix =
                inheritedPrefix(manifest, targetIndex);
        if (prefix.isEmpty()) {
            return;
        }
        Path bk2 = runRoot.resolve(manifest.sourceBk2());
        HeadlessTestFixture fixture = null;
        for (int i = 0; i < prefix.size(); i++) {
            TraceRunManifest.Segment segment = prefix.get(i);
            TraceData trace = TraceData.load(runRoot.resolve(segment.dir()),
                    segment.dynamicArtInitialLedgerDescriptors());
            fixture = bootSegment(bk2, segment, trace);
            replaySegmentRows(fixture, trace);
            int nextOffset = i + 1 < prefix.size()
                    ? prefix.get(i + 1).bk2FrameOffset()
                    : manifest.segments().get(targetIndex).bk2FrameOffset();
            // The rows between two recorded segments are a run GAP: the
            // recorder attributes art submitted there to submission_origin
            // "run_gap", precisely because no comparison window is open over
            // them. Take the window away from the automatic owner for the gap
            // so the engine's own ledger carries the same structure, then hand
            // it back for the next segment's row zero.
            gapOwnership(true);
            replayGapRows(fixture, segment, trace, nextOffset);
            gapOwnership(false);
        }
    }

    /** Suspends/restores automatic comparison-window ownership over a gap. */
    private static void gapOwnership(boolean externallyOwned) {
        var coordinator = com.openggf.game.session.SessionManager
                .getCurrentGameplayMode().plcFrameLifecycle();
        if (externallyOwned) {
            coordinator.acquireExternalComparisonSegmentOwnership();
        } else {
            coordinator.setComparisonSegmentsExternallyManaged(false);
        }
    }

    /**
     * The segments whose replay the target inherits state from, in movie order.
     * Empty when the target opens with an empty ledger.
     */
    static List<TraceRunManifest.Segment> inheritedPrefix(
            TraceRunManifest manifest, int targetIndex) {
        List<TraceRunManifest.Segment> segments = manifest.segments();
        if (segments.get(targetIndex)
                .dynamicArtInitialLedgerDescriptors().isEmpty()) {
            return List.of();
        }
        int start = targetIndex;
        while (start > 0 && !segments.get(start)
                .dynamicArtInitialLedgerDescriptors().isEmpty()) {
            start--;
        }
        List<TraceRunManifest.Segment> prefix = new ArrayList<>();
        for (int i = start; i < targetIndex; i++) {
            TraceRunManifest.Segment segment = segments.get(i);
            if (!"level".equals(segment.kind())) {
                throw new IllegalStateException(
                        "Cannot replay inherited prefix segment '" + segment.dir()
                                + "' of kind '" + segment.kind()
                                + "': only level segments have a replay driver here");
            }
            prefix.add(segment);
        }
        return prefix;
    }

    /**
     * Boots one level segment exactly as an ordinary level trace replay does —
     * recorded configuration, its own {@code bk2_frame_offset}, its own
     * bootstrap — but leaves the ROM/module install to the caller so the
     * dynamic-art lifecycle built for the run survives into the special stage.
     */
    private static HeadlessTestFixture bootSegment(
            Path bk2, TraceRunManifest.Segment segment, TraceData trace)
            throws IOException {
        TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withRecordingStartFrame(segment.bk2FrameOffset())
                .withZoneAndAct(segment.zoneId(), segment.act() - 1)
                .withHardwareReadinessAdmissionPolicy(
                        trace.hardwareTimingSchedule().hasRecordedInput()
                                ? HardwareReadinessAdmissionPolicy.RECORDED
                                : HardwareReadinessAdmissionPolicy.LIVE)
                .build();
        TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
        TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
        return fixture;
    }

    /** Drives the segment's recorded rows, honouring recorded lag rows. */
    private static void replaySegmentRows(
            HeadlessTestFixture fixture, TraceData trace) {
        for (int row = 0; row < trace.frameCount(); row++) {
            TraceFrame expected = trace.getFrame(row);
            TraceFrame previous = row > 0 ? trace.getFrame(row - 1) : null;
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
            TraceReplayBootstrap.markVblankStarvedIterationForReplay(
                    previous, expected);
            fixture.beginTraceRow(row, expected.frame());
            if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                fixture.skipFrameFromRecording();
            } else {
                fixture.stepFrameFromRecording();
            }
        }
    }

    /**
     * Drives the movie rows between a segment's last recorded row and the next
     * segment's first, which carry the run-gap art submissions the manifest
     * records as {@code submission_origin: run_gap}.
     */
    private static void replayGapRows(
            HeadlessTestFixture fixture,
            TraceRunManifest.Segment segment,
            TraceData trace,
            int nextOffset) {
        int gapRows = nextOffset - (segment.bk2FrameOffset() + trace.frameCount());
        if (gapRows < 0) {
            throw new IllegalStateException(
                    "Segment '" + segment.dir() + "' overlaps the next segment");
        }
        for (int row = 0; row < gapRows; row++) {
            fixture.stepFrameFromRecording();
        }
    }
}
