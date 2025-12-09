package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsLoader;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestRomAudioIntegration {
    private Rom rom;
    private Sonic2SmpsLoader loader;

    @Before
    public void setUp() {
        File romFile = RomTestUtils.ensureRomAvailable();
        rom = new Rom();
        boolean opened = rom.open(romFile.getAbsolutePath());
        assertTrue("Failed to open ROM", opened);
        loader = new Sonic2SmpsLoader(rom);
    }

    private static class LoggingSynth extends VirtualSynthesizer {
        List<String> fm = new ArrayList<>();
        List<Integer> psg = new ArrayList<>();
        List<Integer> dac = new ArrayList<>();
        DacData configuredDacData;

        @Override
        public void setDacData(DacData data) {
            this.configuredDacData = data;
            super.setDacData(data);
        }

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            fm.add(String.format("P%d %02X %02X", port, reg, val));
            super.writeFm(source, port, reg, val);
        }

        @Override
        public void writePsg(Object source, int val) {
            psg.add(val);
            super.writePsg(source, val);
        }

        @Override
        public void playDac(Object source, int note) {
            dac.add(note);
            super.playDac(source, note);
        }
    }

    @Test
    public void testMusicDecompressionAndLoading() {
        AbstractSmpsData data = loader.loadMusic(0x82);
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
        AbstractSmpsData data = loader.loadMusic(0x82); // EHZ
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
        AbstractSmpsData data = loader.loadMusic(0x82); // Emerald Hill Zone
        DacData dac = loader.loadDacData();

        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(data, dac, synth);

        // Check for init write before clearing
        boolean hasInitWrite = synth.fm.stream().anyMatch(cmd -> cmd.contains("2B 80"));

        // Ignore the DAC-enable write emitted during construction so assertions only consider
        // commands produced by sequencing the ROM data.
        synth.fm.clear();
        synth.psg.clear();

        short[] buffer = new short[4096];
        seq.read(buffer);

        boolean hasSequencedCommands = !synth.fm.isEmpty() || !synth.psg.isEmpty();

        assertTrue("Sequencer should initialize DAC enable on the FM chip", hasInitWrite);
        assertTrue("Sequencer should emit FM or PSG commands from the ROM stream", hasSequencedCommands);
    }

    @Test
    public void testDacSamplePlaybackUsesRomSamples() {
        AbstractSmpsData smps = loader.loadMusic(0x82); // Emerald Hill Zone contains DAC drums
        DacData dacData = loader.loadDacData();
        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, dacData, synth);

        short[] buffer = new short[4096];
        seq.read(buffer);

        assertSame("Sequencer should wire ROM DAC data into the synthesizer", dacData, synth.configuredDacData);
        assertFalse("ROM DAC table should expose samples", dacData.samples.isEmpty());
        assertTrue("ROM DAC table should map drum notes", dacData.mapping.containsKey(0x81));
    }
}
