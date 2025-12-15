package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Synthesizer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SmpsSequencerTest {

    private static class MockSynthesizer implements Synthesizer {
        List<String> events = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            if (reg == 0x28) {
                // Key On/Off
                // val: 0xf0 (On), 0x00 (Off). Lower 3 bits channel.
                int ch = val & 0x7;
                boolean on = (val & 0xF0) != 0;
                String ev = String.format("FM Key %s Ch %d", on ? "On" : "Off", ch);
                events.add(ev);
            }
        }

        @Override
        public void writePsg(Object source, int val) {}
        @Override
        public void setInstrument(Object source, int channelId, byte[] voice) {}
        @Override
        public void playDac(Object source, int note) {}
        @Override
        public void stopDac(Object source) {}
        @Override
        public void setDacData(DacData data) {}
        @Override
        public void setFmMute(int channel, boolean mute) {}
        @Override
        public void setPsgMute(int channel, boolean mute) {}
        @Override
        public void setDacInterpolate(boolean interpolate) {}
    }

    @Test
    public void testDurationWrapping() {
        // Construct a mock SMPS track
        byte[] data = new byte[256];
        // Header
        data[0x02] = 1; // 1 FM channel (DAC)
        data[0x04] = 3; // Dividing timing 3
        data[0x05] = (byte) 0x80; // Tempo

        // FM Track Ptr at 0x06
        data[0x06] = 0x10;
        data[0x07] = 0x00;

        // Track Data at 0x10
        data[0x10] = (byte) 0x81; // Note C
        data[0x11] = 100;         // Duration 100
        data[0x12] = (byte) 0xF2; // Stop

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0); // S2 is Little Endian
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // First call primes the sequencer
        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        if (state.tracks.isEmpty()) {
            throw new RuntimeException("No tracks found");
        }

        SmpsSequencer.DebugTrack track = state.tracks.get(0);

        assertEquals("Duration should not wrap", 300, track.duration);
    }

    @Test
    public void testJumpEndiannessLittle() {
        byte[] data = new byte[256];
        data[0x02] = 1;
        data[0x04] = 1;
        data[0x05] = (byte) 0x80;

        data[0x06] = 0x10;
        data[0x07] = 0x00;

        data[0x10] = (byte) 0xF6;
        data[0x11] = 0x20;
        data[0x12] = 0x00;

        data[0x20] = (byte) 0x81;
        data[0x21] = 10;
        data[0x22] = (byte) 0xF2;

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        SmpsSequencer.DebugTrack track = state.tracks.get(0);

        assertEquals("Should have jumped to 0x20 and read note", 0x22, track.position);
    }

    @Test
    public void testJumpEndiannessBig() {
        byte[] data = new byte[256];
        data[0x00] = 0;
        data[0x01] = 0;
        data[0x02] = 1;
        data[0x04] = 1;
        data[0x05] = (byte) 0x80;

        data[0x06] = 0x00;
        data[0x07] = 0x10;

        data[0x10] = (byte) 0xF6;
        data[0x11] = 0x00;
        data[0x12] = 0x20;

        data[0x20] = (byte) 0x81;
        data[0x21] = 10;
        data[0x22] = (byte) 0xF2;

        Sonic1SmpsData smpsData = new Sonic1SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        if (state.tracks.isEmpty()) {
             throw new RuntimeException("No tracks found. Header parsing might have failed.");
        }
        SmpsSequencer.DebugTrack track = state.tracks.get(0);

        assertEquals("Should have jumped to 0x20 using BE pointer", 0x22, track.position);
    }

    @Test
    public void testNoteFillLogic() {
        byte[] data = new byte[256];
        data[0x02] = 2; // 2 FM channels (DAC, FM1)
        data[0x04] = 1; // Div 1
        data[0x05] = (byte) 0x80; // Tempo

        // DAC entry (0x06)
        data[0x06] = 0x00; data[0x07] = 0x00; // Ptr
        data[0x08] = 0x00;
        data[0x09] = 0x00;

        // FM1 entry (0x0A)
        data[0x0A] = 0x10; data[0x0B] = 0x00; // Ptr -> 0x10
        data[0x0C] = 0x00;
        data[0x0D] = 0x00;

        // Track at 0x10
        int p = 0x10;
        data[p++] = (byte) 0xE8; // Note Fill
        data[p++] = 23;          // 23 ticks

        data[p++] = (byte) 0x81; // Note C
        data[p++] = 24;          // Duration 24

        data[p++] = (byte) 0xF2; // Stop

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0);
        MockSynthesizer synth = new MockSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, synth);

        short[] buf = new short[735];
        sequencer.read(buf);

        boolean startFound = false;
        for(String e : synth.events) {
            if(e.equals("FM Key On Ch 0")) startFound = true;
        }
        assertTrue("Note should have started. Events: " + synth.events, startFound);

        synth.events.clear();

        boolean stopFound = false;
        int framesToRun = 50;
        for(int i=0; i<framesToRun; i++) {
            sequencer.read(buf);
            for(String e : synth.events) {
                if(e.equals("FM Key Off Ch 0")) {
                    stopFound = true;
                }
            }
            if(stopFound) break;
        }

        assertTrue("Note should have stopped (Fill)", stopFound);
    }
}
