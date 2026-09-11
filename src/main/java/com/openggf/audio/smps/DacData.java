package com.openggf.audio.smps;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class DacData {
    private final Map<Integer, Sample> samples;
    private final Map<Integer, DacEntry> mapping; // NoteID -> Entry
    private final int baseCycles; // Game-specific DAC base cycles (S1=301, S2=288, S3K=297)

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping) {
        this(samples, mapping, 288); // Default to S2 value for backwards compatibility
    }

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping, int baseCycles) {
        Map<Integer, Sample> ownedSamples = new HashMap<>();
        for (Map.Entry<Integer, byte[]> entry : samples.entrySet()) {
            byte[] bytes = entry.getValue();
            ownedSamples.put(entry.getKey(), bytes == null ? null : new Sample(bytes));
        }
        this.samples = Collections.unmodifiableMap(ownedSamples);
        this.mapping = Collections.unmodifiableMap(new HashMap<>(mapping));
        this.baseCycles = baseCycles;
    }

    public Sample sample(int sampleId) {
        return samples.get(sampleId);
    }

    public DacEntry mappingForNote(int noteId) {
        return mapping.get(noteId);
    }

    public int baseCycles() {
        return baseCycles;
    }

    public int sampleCount() {
        return samples.size();
    }

    public int mappingCount() {
        return mapping.size();
    }

    public boolean hasSample(int sampleId) {
        return samples.containsKey(sampleId);
    }

    public static final class Sample {
        private final byte[] bytes;

        private Sample(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        public int length() {
            return bytes.length;
        }

        public byte byteAt(int index) {
            return bytes[index];
        }
    }

    public static final class DacEntry {
        private final int sampleId;
        private final int rate;

        public DacEntry(int sampleId, int rate) {
            this.sampleId = sampleId;
            this.rate = rate;
        }

        public int sampleId() {
            return sampleId;
        }

        public int rate() {
            return rate;
        }
    }
}
