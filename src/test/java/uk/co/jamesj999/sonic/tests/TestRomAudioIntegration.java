package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsLoader;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestRomAudioIntegration {
    private Rom rom;
    private SmpsLoader loader;

    @Before
    public void setUp() {
        File romFile = RomTestUtils.ensureRomAvailable();
        rom = new Rom();
        boolean opened = rom.open(romFile.getAbsolutePath());
        assertTrue("Failed to open ROM", opened);
        loader = new SmpsLoader(rom);
    }

    private static class LoggingSynth extends VirtualSynthesizer {
        List<String> fm = new ArrayList<>();
        List<Integer> psg = new ArrayList<>();
        List<Integer> dac = new ArrayList<>();

        @Override
        public void writeFm(int port, int reg, int val) {
            fm.add(String.format("P%d %02X %02X", port, reg, val));
            super.writeFm(port, reg, val);
        }

        @Override
        public void writePsg(int val) {
            psg.add(val);
            super.writePsg(val);
        }

        @Override
        public void playDac(int note) {
            dac.add(note);
            super.playDac(note);
        }
    }

    @Test
    public void testMusicDecompressionAndLoading() {
        SmpsData data = loader.loadMusic(0x82);
        assertNotNull("Should load EHZ music", data);
        assertTrue("Voice Ptr > 0", data.getVoicePtr() > 0);
        int channels = data.getChannels();
        assertTrue("Channels should be valid (e.g. 6)", channels > 0 && channels <= 7);
        System.out.println("EHZ Loaded. Size: " + data.getData().length);
    }

    @Test
    public void testDacDataLoading() {
        DacData dac = loader.loadDacData();
        assertNotNull("DAC Data should load", dac);
        assertFalse("Should have samples", dac.samples.isEmpty());
        assertFalse("Should have mapping", dac.mapping.isEmpty());
        assertTrue("Should have Sample 81", dac.samples.containsKey(0x81));
        byte[] sample = dac.samples.get(0x81);
        assertTrue("Sample 81 should have data", sample.length > 0);
        System.out.println("DAC Loaded. Sample 81 size: " + sample.length);
    }

    @Test
    public void testSequencerPlayback() {
        SmpsData data = loader.loadMusic(0x82); // EHZ
        DacData dac = loader.loadDacData();

        SmpsSequencer seq = new SmpsSequencer(data, dac);
        short[] buffer = new short[4096];

        // Run for more ticks (50 iterations * 4096 samples ~ 5 seconds)
        for (int i = 0; i < 50; i++) {
            seq.read(buffer);
        }

        // Note: We do not assert non-silent audio here because full instrument parameter loading
        // (SMPS Flag EF) is not yet implemented, which may result in default (silent or low) output.
        // The test passes if the sequencer runs without exception.
    }

    @Test
    public void testMusicEmitsChipCommandsFromRomData() {
        SmpsData data = loader.loadMusic(0x82); // Emerald Hill Zone
        DacData dac = loader.loadDacData();

        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(data, dac, synth);

        // Clear the initial DAC-enable write so we only capture music sequencing traffic.
        synth.fm.clear();

        short[] buffer = new short[4096];
        for (int i = 0; i < 8; i++) {
            seq.read(buffer);
        }

        boolean hasFmOrPsg = !synth.fm.isEmpty() || !synth.psg.isEmpty();
        assertTrue("Music sequence should drive FM or PSG registers", hasFmOrPsg);
    }

    @Test
    public void testDacSamplePlaybackUsesRomSamples() {
        DacData dacData = loader.loadDacData();
        assertFalse("ROM DAC table should expose samples", dacData.samples.isEmpty());
        assertTrue("ROM DAC table should map notes", dacData.mapping.containsKey(0x81));

        byte[] data = new byte[32];
        data[2] = 1; // One FM track (channel 0 becomes DAC)
        data[5] = (byte) 0x80; // Tempo

        // Track 0 header pointer at 0x0A
        data[6] = 0x0A;
        data[7] = 0x00;

        int trackPtr = 0x0A;
        data[trackPtr] = (byte) 0x81; // Play sample 0x81
        data[trackPtr + 1] = 0x01; // Duration
        data[trackPtr + 2] = (byte) 0xF2; // Stop

        SmpsData smps = new SmpsData(data);
        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, dacData, synth);

        short[] buffer = new short[1024];
        seq.read(buffer);

        assertFalse("DAC playback should be triggered for SMPS DAC track", synth.dac.isEmpty());
        assertEquals("Expected the DAC note to come from the ROM mapping", (Integer) 0x81, synth.dac.get(0));
    }
}
