package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ComparisonLayer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ObservationStatus;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerObservationClaim;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerObservationInventory;
import java.util.Arrays;

/** Canonical producer-observation inventory shapes shared by fixed producers. */
public final class CompleteRunAudioObservationInventories {
    private CompleteRunAudioObservationInventories() {
    }

    public static ProducerObservationInventory frameChipsOnly(String reason) {
        return new ProducerObservationInventory(Arrays.stream(ComparisonLayer.values())
                .map(layer -> new ProducerObservationClaim(layer,
                        layer == ComparisonLayer.FRAME_CHIP_EVENTS
                                ? ObservationStatus.OBSERVED
                                : ObservationStatus.UNOBSERVED,
                        layer == ComparisonLayer.FRAME_CHIP_EVENTS ? null : reason))
                .toList());
    }
}
