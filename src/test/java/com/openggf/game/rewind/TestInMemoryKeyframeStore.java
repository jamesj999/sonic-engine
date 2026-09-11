package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestInMemoryKeyframeStore {

    private static CompositeSnapshot snap(int marker) {
        var entries = new LinkedHashMap<String, Object>();
        entries.put("marker", marker);
        return new CompositeSnapshot(entries);
    }

    @Test
    void putAndLatestAtOrBefore() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        s.put(0, snap(0));
        s.put(60, snap(60));
        s.put(120, snap(120));
        assertEquals(60, s.latestAtOrBefore(75).get().frame());
        assertEquals(60, s.latestAtOrBefore(60).get().frame());
        assertEquals(0, s.latestAtOrBefore(0).get().frame());
        assertTrue(s.latestAtOrBefore(-1).isEmpty());
        assertEquals(120, s.latestAtOrBefore(99999).get().frame());
    }

    @Test
    void earliestFrame() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        assertEquals(-1, s.earliestFrame());
        s.put(60, snap(60));
        assertEquals(60, s.earliestFrame());
        s.put(0, snap(0));
        assertEquals(0, s.earliestFrame());
    }

    @Test
    void putReplacesExistingEntry() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        s.put(60, snap(1));
        s.put(60, snap(2));
        assertEquals(2, ((Integer) s.latestAtOrBefore(60).get().snapshot().get("marker")));
    }

    @Test
    void discardBeforeDropsOlderKeyframesOnly() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        s.put(0, snap(0));
        s.put(60, snap(60));
        s.put(120, snap(120));

        s.discardBefore(60);

        assertTrue(s.latestAtOrBefore(59).isEmpty());
        assertEquals(60, s.earliestFrame());
        assertEquals(60, s.latestAtOrBefore(60).orElseThrow().frame());
        assertEquals(120, s.latestAtOrBefore(999).orElseThrow().frame());
    }
}
