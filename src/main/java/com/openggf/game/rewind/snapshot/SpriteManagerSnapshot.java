package com.openggf.game.rewind.snapshot;

import com.openggf.level.objects.PerObjectRewindSnapshot;

import java.util.Map;

/**
 * Snapshot of {@link com.openggf.sprites.managers.SpriteManager}'s
 * gameplay-relevant runtime state — the per-sprite captures keyed by
 * sprite code and the manager's own per-frame counter.
 *
 * <p>SpriteManager.frameCounter is incremented every {@code stepFrame} and
 * passed into {@code SidekickCpuController.update(frameCount)} and the
 * per-sprite {@code AnimationManager.update(frameCount)}. Without
 * snapshotting it, the post-restore counter remains at the live (post-
 * forward-run) value, causing CPU-controller {@code frameCounter} fields
 * to read the wrong absolute frame on the very first replay step. Same
 * cumulative-counter pattern as {@code LevelManager.frameCounter}, which
 * is already snapshotted via {@link LevelSnapshot}.
 */
public record SpriteManagerSnapshot(
        int frameCounter,
        SpriteEntry[] sprites) {

    public SpriteManagerSnapshot {
        sprites = sprites == null ? new SpriteEntry[0] : sprites.clone();
    }

    public SpriteManagerSnapshot(int frameCounter, Map<String, PerObjectRewindSnapshot> sprites) {
        this(frameCounter, sprites.entrySet().stream()
                .map(entry -> new SpriteEntry(entry.getKey(), entry.getValue()))
                .toArray(SpriteEntry[]::new));
    }

    public record SpriteEntry(String code, PerObjectRewindSnapshot state) {}
}
