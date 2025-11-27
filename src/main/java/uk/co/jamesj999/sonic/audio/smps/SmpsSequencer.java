package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

public class SmpsSequencer implements AudioStream {
    private final SmpsData data;
    private final VirtualSynthesizer synth;

    public SmpsSequencer(SmpsData data) {
        this.data = data;
        this.synth = new VirtualSynthesizer();
        // Initialize sequence (pointers etc)
    }

    @Override
    public int read(short[] buffer) {
        // Run sequencer logic to process SMPS events for the duration of the buffer
        // (Assuming 44100Hz or similar)

        // Render audio from synth
        synth.render(buffer);
        return buffer.length;
    }
}
