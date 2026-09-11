package com.openggf.game.rewind.snapshot;

/**
 * Snapshot of {@link com.openggf.level.ParallaxManager} per-frame mutable
 * state. Dense scroll buffers and scalar factors are excluded — they are
 * recomputed from the restored camera/frame/zone after the registry restore.
 *
 * <p>The only retained component is {@code handlerRewindState}: an opaque,
 * value-equal snapshot of the active {@link com.openggf.level.scroll.ZoneScrollHandler}'s
 * genuinely-stateful logical scroll state (e.g. SCZ's camera-driven BG
 * accumulator and level-event routine), captured via
 * {@link com.openggf.level.scroll.ZoneScrollHandler#captureRewindState()}. It is
 * {@code null} for the overwhelming majority of handlers, which are fully
 * derived from the camera/frame.
 */
public record ParallaxSnapshot(Object handlerRewindState) {
    public ParallaxSnapshot() {
        this((Object) null);
    }

    public ParallaxSnapshot(
            int currentShakeOffsetX,
            int currentShakeOffsetY,
            int cachedBgCameraX,
            int cachedBgPeriodWidth,
            boolean hasPerLineVScrollBG,
            boolean hasPerColumnVScrollBG,
            boolean hasPerColumnVScrollFG,
            int minScroll,
            int maxScroll,
            short vscrollFactorFG,
            short vscrollFactorBG) {
        this();
    }

    public ParallaxSnapshot(
            int currentShakeOffsetX,
            int currentShakeOffsetY,
            int cachedBgCameraX,
            int cachedBgPeriodWidth,
            int[] ignoredHScroll,
            short[] ignoredVScrollPerLineBG,
            short[] ignoredVScrollPerColumnBG,
            short[] ignoredVScrollPerColumnFG,
            boolean hasPerLineVScrollBG,
            boolean hasPerColumnVScrollBG,
            boolean hasPerColumnVScrollFG,
            int minScroll,
            int maxScroll,
            short vscrollFactorFG,
            short vscrollFactorBG) {
        this(currentShakeOffsetX, currentShakeOffsetY, cachedBgCameraX, cachedBgPeriodWidth,
                hasPerLineVScrollBG, hasPerColumnVScrollBG, hasPerColumnVScrollFG,
                minScroll, maxScroll, vscrollFactorFG, vscrollFactorBG);
    }
}
