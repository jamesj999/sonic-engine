package com.openggf.tests;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures YM2612 voice loading accepts both 19-byte and 25-byte (TL-inclusive)
 * voices, and that the parsed voice bytes reach the chip's operator/channel
 * registers, observed at the resolved-write boundary.
 *
 * <p>Voice-byte -> register mapping (see Ym2612Chip.setInstrument):
 * <ul>
 *   <li>byte 0 -> feedback/algorithm ({@code 0xB0+ch}).</li>
 *   <li>TL bytes live at voice indices 21,23,22,24 (only present in 25-byte voices)
 *       and are written to slots 0..3 ({@code 0x40}, {@code 0x44}, {@code 0x48},
 *       {@code 0x4C}). 19-byte voices have no TL bytes, so no TL register is
 *       written and every operator TL stays as it was.</li>
 * </ul>
 */
public class TestYm2612VoiceLengths {

    @Test
    public void accepts19ByteVoice() {
        RecordingObserver observer = new RecordingObserver();
        VirtualSynthesizer synth = observedSynth(observer);
        byte[] voice = new byte[19];
        // Algorithm 5 in the low 3 bits of byte 0; feedback in bits 3-5.
        voice[0] = (byte) 0x05;
        synth.setInstrument(this, 0, voice);

        // Byte 0 reached the channel algorithm register, right after the key-off.
        assertEquals("0:28:00", observer.writes.get(0));
        assertEquals("0:B0:05", observer.writes.get(1), "Algorithm byte should reach the channel");

        // A 19-byte voice carries no TL bytes, so no TL register is written.
        for (String write : observer.writes) {
            assertFalse(write.startsWith("0:4"), "19-byte voice must not touch a TL register: " + write);
        }
    }

    @Test
    public void accepts25ByteVoice() {
        RecordingObserver observer = new RecordingObserver();
        VirtualSynthesizer synth = observedSynth(observer);
        byte[] voice = new byte[25];
        voice[0] = (byte) 0x06; // algorithm 6

        // Distinct TL values (< 0x80) at the 25-byte-voice TL positions.
        voice[21] = (byte) 0x11; // slot 0 -> register 0x40
        voice[23] = (byte) 0x22; // slot 1 -> register 0x44
        voice[22] = (byte) 0x33; // slot 2 -> register 0x48
        voice[24] = (byte) 0x44; // slot 3 -> register 0x4C

        synth.setInstrument(this, 0, voice);

        assertEquals("0:B0:06", observer.writes.get(1), "Algorithm byte should reach the channel");

        // The extra TL bytes must reach the operator TL registers, distinguishing
        // the 25-byte parse from the 19-byte one (which writes no TL at all).
        assertTrue(observer.writes.contains("0:40:11"), "voice[21] -> slot 0 TL");
        assertTrue(observer.writes.contains("0:44:22"), "voice[23] -> slot 1 TL");
        assertTrue(observer.writes.contains("0:48:33"), "voice[22] -> slot 2 TL");
        assertTrue(observer.writes.contains("0:4C:44"), "voice[24] -> slot 3 TL");
        assertEquals(4, observer.writes.stream().filter(write -> write.startsWith("0:4")).count(),
                "exactly one TL write per slot");
    }

    private static VirtualSynthesizer observedSynth(RecordingObserver observer) {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        synth.setChipWriteObserver(observer);
        return synth;
    }

    private static final class RecordingObserver implements ChipWriteObserver {
        private final List<String> writes = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            writes.add("%d:%02X:%02X".formatted(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
        }
    }
}
