package com.openggf.game.sonic2.specialstage;

import java.util.Map;

/**
 * Stateless live-play approximation of Mega Drive lag in the Sonic 2 special
 * stage. This empirical model preserves interactive pacing only; trace replay
 * disables it and admits recorded lag solely through the timing port.
 */
final class Sonic2SpecialStageLagModel {
    /**
     * V-ints the ROM's entry load blocks for. {@code SpecialStage} masks all
     * interrupts right after {@code Pal_FadeToWhite} returns
     * ({@code move #$2700,sr}, docs/s2disasm/s2.asm:6557) and does not wait
     * for a V-int again until the first startup loop (s2.asm:6645), so the
     * VDP fill, RAM clears, {@code ssLdComprsdData} and the entry PLC all run
     * with no V-int at all. Only the layout differs per stage, not the art:
     * every S2 special-stage fixture segment (stages 1-7 across both recorded
     * runs) shows exactly 104 lag rows between the 22 {@code Pal_FadeToWhite}
     * rows and the first {@code Vint_S2SS} row. Trace replay admits those
     * rows through the timing port. Ordinary play skips the span; the
     * manager's {@code armLiveEntryLoadHold()} reproduces it for an accurate
     * presentation mode that has not been wired to configuration yet.
     */
    static final int ENTRY_LOAD_LAG_FRAMES = 104;

    private static final Map<Bucket, BucketRatio> EMPIRICAL_BUCKETS = Map.of(
            new Bucket(0, 12), new BucketRatio(65, 185),
            new Bucket(1, 12), new BucketRatio(82, 202),
            new Bucket(2, 12), new BucketRatio(297, 837),
            new Bucket(3, 0), new BucketRatio(104, 126),
            new Bucket(3, 12), new BucketRatio(1121, 3152),
            new Bucket(4, 12), new BucketRatio(302, 797));
    private static final BucketRatio FALLBACK_RATIO = new BucketRatio(1971, 5299);

    private Sonic2SpecialStageLagModel() {
    }

    static boolean shouldSkipLiveUpdate(int frameCounter, int speedFactor, int segmentType) {
        BucketRatio ratio = EMPIRICAL_BUCKETS.getOrDefault(
                new Bucket(segmentType, speedFactor), FALLBACK_RATIO);
        long phase = Math.floorMod((long) frameCounter * ratio.numerator(), ratio.denominator());
        return phase < ratio.numerator();
    }

    private record BucketRatio(int numerator, int denominator) {
    }

    private record Bucket(int segmentType, int speedFactor) {
    }
}
