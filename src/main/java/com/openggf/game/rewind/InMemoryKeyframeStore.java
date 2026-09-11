package com.openggf.game.rewind;

import java.util.Optional;
import java.util.TreeMap;

/**
 * TreeMap-backed in-memory KeyframeStore for v1.
 */
public final class InMemoryKeyframeStore implements KeyframeStore {

    private final TreeMap<Integer, CompositeSnapshot> entries = new TreeMap<>();

    @Override
    public void put(int frame, CompositeSnapshot snapshot) {
        entries.put(frame, snapshot);
    }

    @Override
    public Optional<Entry> latestAtOrBefore(int frame) {
        var floor = entries.floorEntry(frame);
        return floor == null
                ? Optional.empty()
                : Optional.of(new Entry(floor.getKey(), floor.getValue()));
    }

    @Override
    public int earliestFrame() {
        return entries.isEmpty() ? -1 : entries.firstKey();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public void discardAfter(int frame) {
        entries.tailMap(frame, false).clear();
    }

    @Override
    public void discardBefore(int frame) {
        entries.headMap(frame, false).clear();
    }
}
