package com.openggf.game.rewind;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable composite of per-subsystem snapshots, keyed by
 * {@link RewindSnapshottable#key()}, in registration order. Returned by
 * {@link RewindRegistry#capture()} and consumed by
 * {@link RewindRegistry#restore(CompositeSnapshot)}.
 *
 * <p>The public constructor defensively copies its input. Production captures
 * share an immutable key layout and own a compact value array. The public map
 * view is array-backed, so compatibility does not require a map node per key.
 */
public final class CompositeSnapshot {
    private static final long STANDALONE_LAYOUT_VERSION = 0L;

    private final CompositeSnapshotLayout layout;
    private final Object[] values;
    private final Map<String, Object> entries;

    public CompositeSnapshot(Map<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        ArrayList<String> keys = new ArrayList<>(entries.size());
        Object[] copiedValues = new Object[entries.size()];
        int index = 0;
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            keys.add(entry.getKey());
            copiedValues[index++] = entry.getValue();
        }
        this.layout = CompositeSnapshotLayout.fromKeys(STANDALONE_LAYOUT_VERSION, keys);
        this.values = copiedValues;
        this.entries = Collections.unmodifiableMap(new ArrayBackedMapView());
    }

    private CompositeSnapshot(CompositeSnapshotLayout layout, Object[] values) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.values = Objects.requireNonNull(values, "values");
        if (values.length != layout.size()) {
            throw new IllegalArgumentException(
                    "Snapshot value count " + values.length
                            + " does not match layout size " + layout.size());
        }
        this.entries = Collections.unmodifiableMap(new ArrayBackedMapView());
    }

    /**
     * Transfers ownership of {@code values} to the snapshot without copying.
     * The caller must not retain or mutate the array after this call.
     */
    static CompositeSnapshot owned(CompositeSnapshotLayout layout, Object[] values) {
        return new CompositeSnapshot(layout, values);
    }

    public Map<String, Object> entries() {
        return entries;
    }

    public Object get(String key) {
        int index = layout.indexOf(key);
        return index < 0 ? null : values[index];
    }

    public boolean containsKey(String key) {
        return layout.indexOf(key) >= 0;
    }

    CompositeSnapshotLayout layout() {
        return layout;
    }

    boolean sharesValueStorageWith(CompositeSnapshot other) {
        return values == Objects.requireNonNull(other, "other").values;
    }

    int size() {
        return values.length;
    }

    String keyAt(int index) {
        return layout.keyAt(index);
    }

    Object valueAt(int index) {
        return values[index];
    }

    int indexOf(Object key) {
        return layout.indexOf(key);
    }

    private final class ArrayBackedMapView extends AbstractMap<String, Object> {
        @Override
        public int size() {
            return values.length;
        }

        @Override
        public boolean containsKey(Object key) {
            return layout.indexOf(key) >= 0;
        }

        @Override
        public Object get(Object key) {
            int index = layout.indexOf(key);
            return index < 0 ? null : values[index];
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return values.length;
                }

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        private int index;

                        @Override
                        public boolean hasNext() {
                            return index < values.length;
                        }

                        @Override
                        public Entry<String, Object> next() {
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            int current = index++;
                            return new SimpleImmutableEntry<>(
                                    layout.keyAt(current), values[current]);
                        }
                    };
                }
            };
        }
    }
}
