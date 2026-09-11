package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.Observation;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PsgWriteObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.YmWriteObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ChipEvent;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.PsgWrite;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ComparisonLayer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerObservationInventory;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.YmWrite;
import java.util.ArrayList;
import java.util.List;

/** Canonical OpenGGF projection for the independently observed frame-chip layer. */
final class CompleteRunAudioFrameChipProjection
        implements CompleteRunAudioFrameProjection {
    CompleteRunAudioFrameChipProjection(
            ProducerObservationInventory inventory) {
        for (ComparisonLayer layer : ComparisonLayer.values()) {
            boolean required = layer == ComparisonLayer.FRAME_CHIP_EVENTS;
            if (inventory.isObserved(layer) != required) {
                throw new IllegalArgumentException(
                        "frame-chip projection requires the frame-chips-only observation inventory");
            }
        }
    }

    @Override
    public Frame retainedFrame(PreRowBoundary before, RowObservation after,
            String segment, long firstChipOrdinal) {
        List<ChipEvent> chips = new ArrayList<>();
        long ordinal = firstChipOrdinal;
        for (Observation event : after.events()) {
            if (event instanceof YmWriteObserved ym) {
                chips.add(new YmWrite(ordinal++, ym.port(), ym.register(), ym.value()));
            } else if (event instanceof PsgWriteObserved psg) {
                chips.add(new PsgWrite(ordinal++, psg.value()));
            }
        }
        return new Frame(after.absoluteFrame(), segment, null, null, null, null,
                null, List.copyOf(chips), null);
    }
}
