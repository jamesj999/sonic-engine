package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

/**
 * Ensures YM2612 voice loading accepts both 19-byte (S2) and 25-byte (TL-inclusive) voices without errors.
 */
public class TestYm2612VoiceLengths {

    @Test
    public void accepts19ByteVoice() {
        Ym2612Chip chip = new Ym2612Chip();
        byte[] voice = new byte[19];
        chip.setInstrument(0, voice);
        // No exception indicates acceptance.
    }

    @Test
    public void accepts25ByteVoice() {
        Ym2612Chip chip = new Ym2612Chip();
        byte[] voice = new byte[25];
        chip.setInstrument(0, voice);
        // No exception indicates acceptance.
    }
}
