package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.PatternAnimatorSnapshot;
import com.openggf.level.Level;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.animation.AnimatedPatternManager;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Combined level animation manager for Sonic 2.
 * Delegates to pattern animation and palette cycling helpers, and aggregates
 * snapshot state from both halves so a rewind restores visual state fully.
 */
public final class Sonic2LevelAnimationManager implements AnimatedPatternManager, AnimatedPaletteManager,
        RewindSnapshottable<PatternAnimatorSnapshot> {

    /**
     * Magic byte distinguishing the combined (pattern + palette) extra-bytes
     * format from a legacy pattern-only extra payload. Sentinel value chosen so
     * that pre-fix snapshots (whose first byte is part of a packed int) cannot
     * collide.
     */
    private static final byte COMBINED_EXTRA_MAGIC = (byte) 0xC2;

    private final Sonic2PatternAnimator patternAnimator;
    private final Sonic2PaletteCycler paletteCycler;

    public Sonic2LevelAnimationManager(Rom rom, Level level, int zoneIndex) throws IOException {
        this.patternAnimator = new Sonic2PatternAnimator(rom, level, zoneIndex);
        this.paletteCycler = new Sonic2PaletteCycler(rom, level, zoneIndex);
    }

    @Override
    public void update() {
        patternAnimator.update();
        paletteCycler.update();
    }

    @Override
    public String key() {
        return patternAnimator.key();
    }

    @Override
    public PatternAnimatorSnapshot capture() {
        PatternAnimatorSnapshot inner = patternAnimator.capture();
        byte[] innerExtra = inner.extra() != null ? inner.extra() : new byte[0];
        byte[] cyclerState = paletteCycler.captureCyclerState();
        ByteBuffer wrapped = ByteBuffer.allocate(1 + 4 + innerExtra.length + cyclerState.length);
        wrapped.put(COMBINED_EXTRA_MAGIC);
        wrapped.putInt(innerExtra.length);
        wrapped.put(innerExtra);
        wrapped.put(cyclerState);
        return new PatternAnimatorSnapshot(inner.scriptCounters(), inner.handlerCounters(),
                wrapped.array());
    }

    @Override
    public void restore(PatternAnimatorSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        byte[] extra = snapshot.extra();
        if (extra == null || extra.length == 0 || extra[0] != COMBINED_EXTRA_MAGIC) {
            // Legacy format (or no extra). Delegate as-is; cycler state is intentionally
            // unrestored to preserve backwards compatibility with pre-fix snapshots.
            patternAnimator.restore(snapshot);
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(extra, 1, extra.length - 1);
        int innerSize = buf.getInt();
        if (innerSize < 0 || innerSize > buf.remaining()) {
            patternAnimator.restore(snapshot);
            return;
        }
        byte[] innerExtra = new byte[innerSize];
        buf.get(innerExtra);
        byte[] cyclerState = new byte[buf.remaining()];
        buf.get(cyclerState);
        PatternAnimatorSnapshot inner = new PatternAnimatorSnapshot(snapshot.scriptCounters(),
                snapshot.handlerCounters(), innerExtra);
        patternAnimator.restore(inner);
        paletteCycler.restoreCyclerState(cyclerState);
    }
}
