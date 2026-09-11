package com.openggf.trace.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded newest-first buffer with head deduplication. If the new entry
 * matches the current head on {@code (field, romValue, engineValue)} the
 * head's repeat counter is incremented and no new entry is pushed. Any
 * other combination resets the dedup state.
 */
public final class MismatchRingBuffer {
    private final int capacity;
    private final Deque<MismatchEntry> entries = new ArrayDeque<>();

    public MismatchRingBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void push(MismatchEntry entry) {
        MismatchEntry head = entries.peekFirst();
        if (head != null && sameKey(head, entry)) {
            entries.pollFirst();
            entries.addFirst(head.withIncrementedRepeat());
            return;
        }
        entries.addFirst(entry);
        while (entries.size() > capacity) {
            entries.pollLast();
        }
    }

    public synchronized List<MismatchEntry> recent() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static boolean sameKey(MismatchEntry a, MismatchEntry b) {
        return Objects.equals(a.field(), b.field())
                && Objects.equals(a.romValue(), b.romValue())
                && Objects.equals(a.engineValue(), b.engineValue());
    }
}
