package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertTrue;

/**
 * Sanity check that loading a voice and keying on produces audible PCM.
 */
public class TestYm2612InstrumentTone {

    @Test
    public void simpleVoiceProducesAudio() {
        Ym2612Chip chip = new Ym2612Chip();

        // Simple bright voice: all carriers, fast attack/decay.
        byte[] voice = new byte[] {
                (byte) 0x07,             // alg=7 (all carriers), fb=0
                (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, // DT/MUL ops 1,3,2,4
                // RS/AR (Indices 5-8) - Set to Max AR (0x1F)
                (byte) 0x1F, (byte) 0x1F, (byte) 0x1F, (byte) 0x1F,
                // AM/D1R (Indices 9-12)
                (byte) 0x1F, (byte) 0x1F, (byte) 0x1F, (byte) 0x1F,
                // D2R (Indices 13-16)
                0x0A, 0x0A, 0x0A, 0x0A,
                // D1L/RR (Indices 17-20)
                (byte) 0x0F, (byte) 0x0F, (byte) 0x0F, (byte) 0x0F
        };

        chip.setInstrument(0, voice);
        // Set frequency for channel 0 (A4-ish)
        chip.write(0, 0xA4, 0x27); // block+fnum hi
        chip.write(0, 0xA0, 0x6B); // fnum lo
        // Pan both
        chip.write(0, 0xB4, 0xC0);
        // Key on all operators for ch0
        chip.write(0, 0x28, 0xF0 | 0);

        int[] buffer = new int[2048];
        chip.render(buffer);

        long sum = 0;
        for (int s : buffer) {
            sum += Math.abs(s);
        }
        double avg = sum / (double) buffer.length;
        assertTrue("Expected audible output after key-on", avg > 50.0);
    }
}
