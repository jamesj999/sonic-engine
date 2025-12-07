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
        // Updated to Standard SMPS Layout (TL at 21).
        byte[] voice = new byte[] {
                (byte) 0x07,             // 0: alg=7 (all carriers), fb=0
                (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01, // 1-4: DT/MUL ops 1,3,2,4
                // 5-8: RS/AR
                (byte) 0x1F, (byte) 0x1F, (byte) 0x1F, (byte) 0x1F,
                // 9-12: AM/D1R
                (byte) 0x1F, (byte) 0x1F, (byte) 0x1F, (byte) 0x1F,
                // 13-16: D2R
                0x0A, 0x0A, 0x0A, 0x0A,
                // 17-20: D1L/RR
                (byte) 0x0F, (byte) 0x0F, (byte) 0x0F, (byte) 0x0F,
                // 21-24: TL for 4 ops
                0x00, 0x00, 0x00, 0x00
        };

        chip.setInstrument(0, voice);
        // Set frequency for channel 0 (A4-ish)
        chip.write(0, 0xA4, 0x27); // block+fnum hi
        chip.write(0, 0xA0, 0x6B); // fnum lo
        // Pan both
        chip.write(0, 0xB4, 0xC0);
        // Key on all operators for ch0
        chip.write(0, 0x28, 0xF0 | 0);

        short[] buffer = new short[2048];
        chip.render(buffer);

        long sum = 0;
        for (short s : buffer) {
            sum += Math.abs(s);
        }
        double avg = sum / (double) buffer.length;
        assertTrue("Expected audible output after key-on", avg > 50.0);
    }
}
