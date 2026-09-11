package com.openggf.level.scroll.compose;

import java.util.Arrays;

/**
 * Mutable short table used for frame-local scroll value generation.
 *
 * <p>This is a small convenience wrapper around a {@code short[]} that keeps scroll-composition
 * helpers explicit about ownership and copying when they build intermediate deform tables.
 */
public final class ScrollValueTable {

    private final short[] values;

    private ScrollValueTable(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        this.values = new short[size];
    }

    private ScrollValueTable(short[] values) {
        this.values = values;
    }

    /** Allocates a zero-initialized table of the requested size. */
    public static ScrollValueTable ofLength(int size) {
        return new ScrollValueTable(size);
    }

    /** Creates a table by copying the supplied values. */
    public static ScrollValueTable from(short... values) {
        return new ScrollValueTable(Arrays.copyOf(values, values.length));
    }

    public int size() {
        return values.length;
    }

    public short get(int index) {
        return values[index];
    }

    public void set(int index, short value) {
        values[index] = value;
    }

    public void clear() {
        Arrays.fill(values, (short) 0);
    }

    public short[] toArray() {
        return Arrays.copyOf(values, values.length);
    }
}
