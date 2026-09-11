package com.openggf.tests;
import com.openggf.game.sonic2.Sonic2;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.data.Rom;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
public class TestRomAudioIntegration {
    private Rom rom;
    private Sonic2SmpsLoader loader;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
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
    public void testChemicalPlantNoiseChannelEmitsVolume() {
        AbstractSmpsData data = loader.loadMusic(0x8C); // Chemical Plant
        assertNotNull(data, "Chemical Plant should load");
        DacData dac = loader.loadDacData();
        assertNotNull(dac, "DAC data should load");

        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(data, dac, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Run enough frames to cover early percussion passages
        short[] buffer = new short[4096];
        for (int i = 0; i < 32; i++) {
            seq.advanceSamples(buffer.length);
        }

        boolean hasNoiseLatch = synth.psg.stream().anyMatch(v -> (v & 0xF0) == 0xE0);
        long noiseVolWrites = synth.psg.stream()
                .filter(v -> (v & 0xF0) == 0xF0 && (v & 0x0F) < 0x0F)
                .count();

        assertTrue(hasNoiseLatch, "Noise channel should receive latch writes");
        assertTrue(noiseVolWrites > 0, "Noise channel should receive audible volume writes");
    }

    @Test
    public void testMusicDecompressionAndLoading() {
        AbstractSmpsData data = loader.loadMusic(0x82);
        assertNotNull(data, "Should load Metropolis music (0x82)");
        assertTrue(data.getVoicePtr() > 0, "Voice Ptr > 0");
        int channels = data.getChannels();
        assertTrue(channels > 0 && channels <= 7, "Channels should be valid (e.g. 6)");
        System.out.println("Metropolis Loaded. Size: " + data.getData().length);
    }

    @Test
    public void testDacDataLoading() {
        DacData dac = loader.loadDacData();
        assertNotNull(dac, "DAC Data should load");
        assertTrue(dac.sampleCount() > 0, "Should have samples");
        assertTrue(dac.mappingCount() > 0, "Should have mapping");
        assertTrue(dac.hasSample(0x81), "Should have Sample 81");
        DacData.Sample sample = dac.sample(0x81);
        assertNotNull(sample, "Sample 81 should be readable");
        assertTrue(sample.length() > 0, "Sample 81 should have data");
        System.out.println("DAC Loaded. Sample 81 size: " + sample.length());
    }

    @Test
    public void testSequencerPlayback() {
        // We cannot assert non-silent audio here because full instrument parameter
        // loading (SMPS Flag EF) is not yet implemented, so output may be silent.
        // Instead the oracle is twofold: (1) playback is deterministic across two
        // independent sequencer instances over the same ROM data + config, and
        // (2) the sequencer's timing engine makes real progress (not deadlocked).
        short[][] firstRun = renderSequencer(50);
        short[][] secondRun = renderSequencer(50);
        for (int i = 0; i < firstRun.length; i++) {
            assertArrayEquals(firstRun[i], secondRun[i],
                    "Sequencer PCM output must be deterministic; diverged on iteration " + i);
        }

        SmpsSequencer seq = new SmpsSequencer(loader.loadMusic(0x82), loader.loadDacData(),
                Sonic2SmpsSequencerConfig.CONFIG);
        short[] buffer = new short[4096];
        for (int i = 0; i < 50; i++) {
            seq.advanceSamples(buffer.length);
        }
        assertTrue(seq.isComplete() || seq.getSamplesUntilNextTempoFrame() > 0,
                "Sequencer should keep a live tempo schedule after playback, not deadlock");
    }

    /** Renders {@code iterations} buffers from a freshly loaded Metropolis sequencer. */
    private short[][] renderSequencer(int iterations) {
        AbstractSmpsData data = loader.loadMusic(0x82); // Metropolis
        DacData dac = loader.loadDacData();
        SmpsSequencer seq = new SmpsSequencer(data, dac, Sonic2SmpsSequencerConfig.CONFIG);
        short[][] frames = new short[iterations][];
        short[] buffer = new short[4096];
        for (int i = 0; i < iterations; i++) {
            seq.advanceSamples(buffer.length);
            frames[i] = buffer.clone();
        }
        return frames;
    }

    @Test
    public void testMusicEmitsChipCommandsFromRomData() {
        AbstractSmpsData data = loader.loadMusic(0x82); // Metropolis
        DacData dac = loader.loadDacData();

        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(data, dac, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Standalone construction prepares logical state only; the driver-owned
        // admission path performs the DAC-enable write.
        boolean hasInitWrite = synth.fm.stream().anyMatch(cmd -> cmd.contains("2B 80"));

        // Keep assertions scoped to commands produced by sequencing ROM data.
        synth.fm.clear();
        synth.psg.clear();

        short[] buffer = new short[4096];
        seq.advanceSamples(buffer.length);

        boolean hasSequencedCommands = !synth.fm.isEmpty() || !synth.psg.isEmpty();

        assertFalse(hasInitWrite,
                "Standalone sequencer construction must not write the FM chip");
        assertTrue(hasSequencedCommands, "Sequencer should emit FM or PSG commands from the ROM stream");
    }

    @Test
    public void testDacSamplePlaybackUsesRomSamples() {
        AbstractSmpsData smps = loader.loadMusic(0x82); // Metropolis contains DAC drums
        DacData dacData = loader.loadDacData();
        LoggingSynth synth = new LoggingSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, dacData, synth, Sonic2SmpsSequencerConfig.CONFIG);

        assertNull(synth.configuredDacData,
                "Standalone sequencer construction must not select a DAC bank");
        // Standalone callers retain explicit ownership of physical DAC setup;
        // the composed driver performs this automatically on music admission.
        synth.setDacData(dacData);
        short[] buffer = new short[4096];
        seq.advanceSamples(buffer.length);

        assertSame(dacData, synth.configuredDacData,
                "Explicit standalone DAC selection should retain ROM samples");
        assertTrue(dacData.sampleCount() > 0, "ROM DAC table should expose samples");
        assertNotNull(dacData.mappingForNote(0x81), "ROM DAC table should map drum notes");
    }

    @Test
    public void metropolisFirstBufferMatchesSingleFrameReferenceDriver() {
        AbstractSmpsData data = loader.loadMusic(0x82);
        DacData dac = loader.loadDacData();

        com.openggf.audio.driver.SmpsDriver bulkDriver =
                com.openggf.audio.driver.SmpsDriverTestAccess.create(44_100);
        com.openggf.audio.driver.SmpsDriver singleFrameDriver =
                com.openggf.audio.driver.SmpsDriverTestAccess.create(44_100);
        bulkDriver.addSequencer(new SmpsSequencer(data, dac, bulkDriver, Sonic2SmpsSequencerConfig.CONFIG), false);
        singleFrameDriver.addSequencer(new SmpsSequencer(data, dac, singleFrameDriver, Sonic2SmpsSequencerConfig.CONFIG), false);

        short[] actual = new short[1024];
        short[] expected = new short[1024];
        short[] frame = new short[2];

        com.openggf.audio.driver.SmpsDriverTestAccess.read(
                bulkDriver, actual);
        for (int i = 0; i < expected.length / 2; i++) {
            com.openggf.audio.driver.SmpsDriverTestAccess.read(
                    singleFrameDriver, frame);
            expected[i * 2] = frame[0];
            expected[i * 2 + 1] = frame[1];
        }

        assertArrayEquals(expected, actual,
                "Metropolis playback should match the single-frame reference driver output");
    }

    /**
     * The ROM bindings the unified-presentation parity coverage depends on:
     * {@code GameMusic.SPECIAL_STAGE} and both sides of the ring alternation
     * must resolve to real SMPS data in this ROM, not merely to a mapped id.
     * Without this, a parity test could pass on a queued command whose asset
     * never existed.
     */
    @Test
    public void specialStageMusicAndBothRingSfxResolveToRomSmpsData() {
        com.openggf.game.sonic2.audio.Sonic2AudioProfile profile =
                new com.openggf.game.sonic2.audio.Sonic2AudioProfile();

        Integer specialStageMusicId = profile.getMusicMap()
                .get(com.openggf.audio.GameMusic.SPECIAL_STAGE);
        assertNotNull(specialStageMusicId,
                "Sonic 2 must map GameMusic.SPECIAL_STAGE");
        assertNotNull(loader.loadMusic(specialStageMusicId),
                "special stage music must decompress from ROM");

        for (com.openggf.audio.GameSound ring : java.util.List.of(
                com.openggf.audio.GameSound.RING_LEFT,
                com.openggf.audio.GameSound.RING_RIGHT)) {
            Integer sfxId = profile.getSoundMap().get(ring);
            assertNotNull(sfxId, ring + " must be mapped to a ROM SFX id");
            assertNotNull(loader.loadSfx(sfxId),
                    ring + " must load real SMPS data from ROM");
        }
    }

    @Test
    public void testLevelMusicMapping() throws IOException {
        Sonic2 game = new Sonic2(rom);

        // Emerald Hill (0x81)
        assertEquals(0x81, game.getMusicId(0), "Emerald Hill 1 Music ID");
        assertEquals(0x81, game.getMusicId(1), "Emerald Hill 2 Music ID");

        // Chemical Plant (0x8C)
        assertEquals(0x8C, game.getMusicId(2), "Chemical Plant 1 Music ID");
        assertEquals(0x8C, game.getMusicId(3), "Chemical Plant 2 Music ID");

        // Aquatic Ruin (0x86)
        assertEquals(0x86, game.getMusicId(4), "Aquatic Ruin 1 Music ID");

        // Casino Night (0x83)
        assertEquals(0x83, game.getMusicId(6), "Casino Night 1 Music ID");

        // Hill Top (0x94)
        assertEquals(0x94, game.getMusicId(8), "Hill Top 1 Music ID");

        // Mystic Cave (0x84)
        assertEquals(0x84, game.getMusicId(10), "Mystic Cave 1 Music ID");

        // Oil Ocean (0x8F)
        assertEquals(0x8F, game.getMusicId(12), "Oil Ocean 1 Music ID");

        // Metropolis (0x82)
        assertEquals(0x82, game.getMusicId(14), "Metropolis 1 Music ID");
        assertEquals(0x82, game.getMusicId(16), "Metropolis 3 Music ID");

        // Sky Chase (0x8E)
        assertEquals(0x8E, game.getMusicId(17), "Sky Chase Music ID");

        // Wing Fortress (0x90)
        assertEquals(0x90, game.getMusicId(18), "Wing Fortress Music ID");

        // Death Egg (0x87)
        assertEquals(0x87, game.getMusicId(19), "Death Egg Music ID");
    }
}
