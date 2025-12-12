package uk.co.jamesj999.sonic.audio.smps;

import java.util.Map;

public class DacData {
    public final Map<Integer, byte[]> samples;
    public final Map<Integer, DacEntry> mapping; // NoteID -> Entry

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping) {
        this.samples = samples;
        this.mapping = mapping;
    }

    public static class DacEntry {
        public final int sampleId;
        public final int rate;

        public DacEntry(int sampleId, int rate) {
            this.sampleId = sampleId;
            this.rate = rate;
        }
    }
}
