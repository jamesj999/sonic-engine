package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Baseline;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.BoundaryFrontier;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CutoffFrontier;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;

/** Output-only projection from one production row into its observed v2 layers. */
interface CompleteRunAudioFrameProjection {
    default void discardedFrame(PreRowBoundary before, RowObservation after)
            throws Exception {
    }

    default Baseline baseline(PreRowBoundary boundary) throws Exception {
        return new Baseline(boundary.absoluteFrame(), null, null,
                BoundaryFrontier.unobserved());
    }

    Frame retainedFrame(PreRowBoundary before, RowObservation after,
            String segment, long firstChipOrdinal) throws Exception;

    default CutoffFrontier cutoff() throws Exception {
        return new CutoffFrontier(null, null, null, null, null, null, null);
    }

    default void abort() throws Exception {
    }
}
