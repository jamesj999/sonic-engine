package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.RecordedOrdinalSpan;

import java.util.Map;
import java.util.Objects;

/**
 * Run-level index of the recorded hardware-work ordinals that fall in the
 * spans between structural segments.
 *
 * <p>A recorder observes hardware completions across the whole movie, but a
 * run fixture only represents the segments it compares. The spans in between
 * — special-stage results, level reload, a locked level intro — still advanced
 * the ROM's hardware ledger, so the next segment's recorded ordinals start
 * above where the previous segment's ended. Production replaying the run does
 * not reproduce those submissions, so its own ledger would stay behind and
 * every later ordinal would miss by exactly the size of the gap.
 *
 * <p>This index carries only that gap: per segment boundary, per kind, the
 * first and last ordinal the recording consumed there. It holds no payload, no
 * boundary and no frame, and nothing here can complete, prepare or create
 * hardware work.
 */
public final class HardwareTimingInterstitialSpans {

    private static final HardwareTimingInterstitialSpans EMPTY =
            new HardwareTimingInterstitialSpans(Map.of());

    private final Map<Integer, Map<HardwareWorkKind, RecordedOrdinalSpan>> spansByBoundary;

    HardwareTimingInterstitialSpans(
            Map<Integer, Map<HardwareWorkKind, RecordedOrdinalSpan>> spansByBoundary) {
        Objects.requireNonNull(spansByBoundary, "spansByBoundary");
        this.spansByBoundary = spansByBoundary.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    /** No interstitial sidecar was recorded; every handoff behaves as before. */
    public static HardwareTimingInterstitialSpans empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return spansByBoundary.isEmpty();
    }

    /**
     * The ordinal spans the recording consumed after the given segment index
     * and before the next one. An index with no recorded span returns an empty
     * map, which leaves the handoff completely unchanged.
     */
    public Map<HardwareWorkKind, RecordedOrdinalSpan> spansAfterSegment(int segmentIndex) {
        return spansByBoundary.getOrDefault(segmentIndex, Map.of());
    }
}
