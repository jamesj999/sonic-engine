package uk.co.jamesj999.sonic.audio.synth;

public class VirtualSynthesizer {

    public void render(short[] buffer) {
        // Placeholder: Generate silence.
        // In a real implementation, this would mix FM and PSG output.
        // For demonstration, we could generate a sine wave if channels are active.
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = 0;
        }
    }

    public void writeFm(int port, int reg, int val) {
        // Simulate writing to YM2612
    }

    public void writePsg(int val) {
        // Simulate writing to SN76489
    }
}
