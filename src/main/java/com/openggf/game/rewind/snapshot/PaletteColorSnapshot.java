package com.openggf.game.rewind.snapshot;

import java.util.Objects;

/**
 * Snapshot of live palette COLOR data for both palette surfaces.
 *
 * <p>Layout: 3 bytes (r, g, b) per color, 16 colors per line, lines in
 * index order. {@code normalRgb} covers the level's normal palette lines;
 * {@code underwaterRgb} covers the underwater mirror set and is empty when
 * the current level has no underwater palettes. Complements
 * {@link PaletteOwnershipSnapshot}, which captures ownership metadata only.
 *
 * <p>Arrays are owned by the snapshot once constructed - treat as immutable
 * (consistent with the other snapshot records in this package).
 */
public record PaletteColorSnapshot(byte[] normalRgb, byte[] underwaterRgb) {
    public PaletteColorSnapshot {
        normalRgb = Objects.requireNonNull(normalRgb, "normalRgb").clone();
        underwaterRgb = Objects.requireNonNull(underwaterRgb, "underwaterRgb").clone();
    }
}
