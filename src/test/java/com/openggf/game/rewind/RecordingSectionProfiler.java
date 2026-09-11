package com.openggf.game.rewind;

import com.openggf.debug.SectionProfiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only {@link SectionProfiler} that records begin/end events and tracks
 * the currently-active section. Use {@link #activeSection()} to assert
 * balance after an operation, and {@link #transcript()} to verify ordering.
 */
final class RecordingSectionProfiler implements SectionProfiler {

    /** Transcript entries are "begin:NAME" or "end:NAME". */
    private final List<String> transcript = new ArrayList<>();

    /** Currently active section name, or null. */
    private String activeName;

    @Override
    public void beginSection(String name) {
        if (activeName != null) {
            transcript.add("end:" + activeName);
        }
        transcript.add("begin:" + name);
        activeName = name;
    }

    @Override
    public void endSection(String name) {
        if (activeName == null || !activeName.equals(name)) {
            return;
        }
        transcript.add("end:" + name);
        activeName = null;
    }

    /** Snapshot the recorded transcript (defensive copy). */
    List<String> transcript() {
        return Collections.unmodifiableList(new ArrayList<>(transcript));
    }

    /** Names that appeared as begin events, in first-occurrence order. */
    List<String> beginNames() {
        List<String> out = new ArrayList<>();
        for (String entry : transcript) {
            if (entry.startsWith("begin:")) {
                String n = entry.substring("begin:".length());
                if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    /** Currently active section, or {@code null} if no section is open. */
    String activeSection() {
        return activeName;
    }

    /** Reset transcript + active section. */
    void clearTranscript() {
        transcript.clear();
        activeName = null;
    }
}
