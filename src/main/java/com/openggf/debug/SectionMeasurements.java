package com.openggf.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reusable primitive accumulators; callers own frame boundaries and thread confinement. */
final class SectionMeasurements {
    static final class Section {
        final String name;
        final long[] history;
        long current;
        long sum;
        boolean recorded;
        boolean committed;

        Section(String name, int window) {
            this.name = name;
            history = new long[window];
        }
    }

    private final int window;
    private final Map<String, Section> byName = new HashMap<>();
    // Current order is first recording this frame; history order is first completed frame.
    private final List<Section> current = new ArrayList<>();
    private final List<Section> history = new ArrayList<>();

    SectionMeasurements(int window) {
        this.window = window;
    }

    void add(String name, long value) {
        Section section = byName.get(name);
        if (section == null) {
            section = new Section(name, window);
            byName.put(name, section);
        }
        if (!section.recorded) {
            section.recorded = true;
            current.add(section);
        }
        section.current += value;
    }

    void clearFrame() {
        for (int i = 0; i < current.size(); i++) {
            Section section = current.get(i);
            section.current = 0;
            section.recorded = false;
        }
        current.clear();
    }

    void finishFrame(int slot) {
        for (int i = 0; i < current.size(); i++) {
            Section section = current.get(i);
            if (!section.committed) {
                section.committed = true;
                history.add(section);
            }
        }
        for (int i = 0; i < history.size(); i++) {
            Section section = history.get(i);
            section.sum += section.current - section.history[slot];
            section.history[slot] = section.current;
        }
    }

    void emit(FrameSampleSink sink) {
        for (int i = 0; i < current.size(); i++) {
            Section section = current.get(i);
            sink.frameSample(section.name, section.current);
        }
    }

    int size() { return history.size(); }
    Section get(int index) { return history.get(index); }

    void reset() {
        byName.clear();
        current.clear();
        history.clear();
    }
}
