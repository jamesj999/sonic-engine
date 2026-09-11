package com.openggf.game.sonic3k.events;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.level.Pattern;

import java.util.Objects;

/**
 * Prepared ROM art retained by one gameplay session across an AIZ act reload.
 */
public final class AizPreparedTransitionArtState
        implements RewindSnapshottable<byte[]> {
    private static final String REWIND_KEY =
            "s3k-aiz-prepared-transition-art";
    private static final byte[] EMPTY = new byte[0];

    private byte[] fireOverlayTiles8x8 = EMPTY;

    public void retainFireOverlay(byte[] tiles8x8) {
        Objects.requireNonNull(tiles8x8, "tiles8x8");
        if (tiles8x8.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IllegalArgumentException(
                    "prepared AIZ fire overlay must contain whole patterns");
        }
        fireOverlayTiles8x8 = tiles8x8.clone();
    }

    public byte[] fireOverlayCopy() {
        return fireOverlayTiles8x8.clone();
    }

    public int fireOverlayTileCount() {
        return fireOverlayTiles8x8.length / Pattern.PATTERN_SIZE_IN_ROM;
    }

    public void reset() {
        fireOverlayTiles8x8 = EMPTY;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public byte[] capture() {
        return fireOverlayCopy();
    }

    @Override
    public void restore(byte[] snapshot) {
        retainFireOverlay(Objects.requireNonNull(snapshot, "snapshot"));
    }

    @Override
    public void resetForMissingSnapshot() {
        reset();
    }
}
