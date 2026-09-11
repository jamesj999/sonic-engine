package com.openggf.game.rewind.snapshot;

import java.util.Objects;

/**
 * Immutable capture of Camera state for rewind snapshots.
 * Captures all mutable gameplay-relevant fields including position, boundaries,
 * scroll delays, freeze state, and wrap tracking. Target sprite references are
 * rebindable via SpriteManager lookups on restore.
 */
public record CameraSnapshot(
        short x,
        short y,
        short minX,
        short minY,
        short maxX,
        short maxY,
        short renderCopyX,
        short renderCopyY,
        short shakeOffsetX,
        short shakeOffsetY,
        short minXTarget,
        short minYTarget,
        short maxXTarget,
        short maxYTarget,
        short maxXBeforeBoundaryEasing,
        boolean maxYChanging,
        int horizScrollDelayFrames,
        boolean frozen,
        boolean deferHorizontalBoundaryClampOnce,
        boolean levelStarted,
        boolean verticalWrapEnabled,
        int verticalWrapRange,
        int verticalWrapMask,
        boolean lastFrameWrapped,
        short wrapDeltaY,
        short yPosBias,
        short fastScrollCap) {
}
