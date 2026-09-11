package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility to generate reference audio WAV files for regression testing.
 * These reference files capture the audio output of the engine at a known-good state.
 * After optimizations, the AudioRegressionTest compares new output against these references.
 */
public class AudioReferenceGenerator {

    private static final String REFERENCE_DIR = "src/test/resources/audio-reference";
    private static final double SAMPLE_RATE = Ym2612Chip.getDefaultOutputRate();
    private static final int BUFFER_SIZE = 1024;

    // Music IDs
    private static final int MUSIC_EHZ = 0x81;  // Emerald Hill Zone
    private static final int MUSIC_CPZ = 0x8C;  // Chemical Plant Zone
    private static final int MUSIC_HTZ = 0x94;  // Hill Top Zone

    // SFX IDs (from Sonic2SmpsConstants)
    private static final int SFX_RING = 0xB5;
    private static final int SFX_JUMP = 0xA3;
    private static final int SFX_SPRING = 0xB1;
    private static final int SFX_SPINDASH_CHARGE = 0xAB;
    private static final int SFX_SPINDASH_RELEASE = 0xAC;

    private final Rom rom;
    private final Sonic2SmpsLoader loader;
    private final DacData dacData;

    public AudioReferenceGenerator(Rom rom) {
        this.rom = rom;
        this.loader = new Sonic2SmpsLoader(rom);
        this.dacData = loader.loadDacData();
    }

    /**
     * Generate all reference audio files.
     */
    public void generateAll() throws IOException {
        Path refDir = Paths.get(REFERENCE_DIR);
        Files.createDirectories(refDir);

        System.out.println("Generating audio reference files to: " + refDir.toAbsolutePath());

        // Music references (10 seconds each)
        generateMusicReference("music_ehz.wav", MUSIC_EHZ, 10.0);
        generateMusicReference("music_cpz.wav", MUSIC_CPZ, 10.0);
        generateMusicReference("music_htz.wav", MUSIC_HTZ, 10.0);

        // SFX references (full duration)
        generateSfxReference("sfx_ring.wav", SFX_RING);
        generateSfxReference("sfx_jump.wav", SFX_JUMP);
        generateSfxReference("sfx_spring.wav", SFX_SPRING);
        generateSfxReference("sfx_spindash_charge.wav", SFX_SPINDASH_CHARGE);
        generateSfxReference("sfx_spindash_release.wav", SFX_SPINDASH_RELEASE);

        // Mixed scenario: Music + SFX overlay
        generateMixedReference("mixed_music_sfx.wav", MUSIC_EHZ, SFX_RING, SFX_JUMP, 5.0);

        System.out.println("Reference generation complete.");
    }

    /**
     * Generate a music reference file.
     */
    public void generateMusicReference(String filename, int musicId, double durationSeconds) throws IOException {
        System.out.println("Generating " + filename + " (" + durationSeconds + "s)...");

        AbstractSmpsData musicData = loader.loadMusic(musicId);
        if (musicData == null) {
            System.err.println("  WARNING: Could not load music ID 0x" + Integer.toHexString(musicId));
            return;
        }

        SmpsDriver driver = SmpsDriverTestAccess.create(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer seq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(seq, false);

        int totalSamples = (int) (durationSeconds * SAMPLE_RATE) * 2; // stereo
        short[] audio = renderAudio(driver, totalSamples);

        writeWav(filename, audio);
        System.out.println("  Written: " + filename + " (" + audio.length / 2 + " samples)");
    }

    /**
     * Generate an SFX reference file (runs until SFX completes).
     */
    public void generateSfxReference(String filename, int sfxId) throws IOException {
        System.out.println("Generating " + filename + "...");

        AbstractSmpsData sfxData = loader.loadSfx(sfxId);
        if (sfxData == null) {
            System.err.println("  WARNING: Could not load SFX ID 0x" + Integer.toHexString(sfxId));
            return;
        }

        SmpsDriver driver = SmpsDriverTestAccess.create(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer seq = new SmpsSequencer(sfxData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(SAMPLE_RATE);
        seq.setSfxMode(true);
        driver.addSequencer(seq, true);

        // SFX are typically short, render until complete or max 5 seconds
        int maxSamples = (int) (5.0 * SAMPLE_RATE) * 2;
        short[] audio = renderAudioUntilComplete(driver, maxSamples);

        writeWav(filename, audio);
        System.out.println("  Written: " + filename + " (" + audio.length / 2 + " samples)");
    }

    /**
     * Generate a mixed reference with music and overlaid SFX.
     */
    public void generateMixedReference(String filename, int musicId, int sfx1Id, int sfx2Id, double durationSeconds) throws IOException {
        System.out.println("Generating " + filename + " (mixed scenario)...");

        AbstractSmpsData musicData = loader.loadMusic(musicId);
        if (musicData == null) {
            System.err.println("  WARNING: Could not load music ID 0x" + Integer.toHexString(musicId));
            return;
        }

        AbstractSmpsData sfx1Data = loader.loadSfx(sfx1Id);
        AbstractSmpsData sfx2Data = loader.loadSfx(sfx2Id);

        SmpsDriver driver = SmpsDriverTestAccess.create(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer musicSeq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        musicSeq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(musicSeq, false);

        int totalFrames = (int) (durationSeconds * SAMPLE_RATE);
        int totalSamples = totalFrames * 2; // stereo
        short[] audio = new short[totalSamples];
        short[] buffer = new short[BUFFER_SIZE * 2];

        int samplesWritten = 0;
        int sfx1TriggerFrame = (int) (0.5 * SAMPLE_RATE); // Trigger at 0.5s
        int sfx2TriggerFrame = (int) (1.0 * SAMPLE_RATE); // Trigger at 1.0s
        boolean sfx1Triggered = false;
        boolean sfx2Triggered = false;

        while (samplesWritten < totalSamples) {
            int currentFrame = samplesWritten / 2;

            // Trigger SFX at specific times
            if (!sfx1Triggered && currentFrame >= sfx1TriggerFrame && sfx1Data != null) {
                SmpsSequencer sfxSeq = new SmpsSequencer(sfx1Data, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
                sfxSeq.setSampleRate(SAMPLE_RATE);
                sfxSeq.setSfxMode(true);
                driver.addSequencer(sfxSeq, true);
                sfx1Triggered = true;
            }

            if (!sfx2Triggered && currentFrame >= sfx2TriggerFrame && sfx2Data != null) {
                SmpsSequencer sfxSeq = new SmpsSequencer(sfx2Data, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
                sfxSeq.setSampleRate(SAMPLE_RATE);
                sfxSeq.setSfxMode(true);
                driver.addSequencer(sfxSeq, true);
                sfx2Triggered = true;
            }

            int toRead = Math.min(buffer.length, totalSamples - samplesWritten);
            SmpsDriverTestAccess.read(driver, buffer);
            System.arraycopy(buffer, 0, audio, samplesWritten, toRead);
            samplesWritten += toRead;
        }

        writeWav(filename, audio);
        System.out.println("  Written: " + filename + " (" + audio.length / 2 + " samples)");
    }

    private short[] renderAudio(SmpsDriver driver, int totalSamples) {
        short[] audio = new short[totalSamples];
        short[] buffer = new short[BUFFER_SIZE * 2];

        int samplesWritten = 0;
        while (samplesWritten < totalSamples) {
            int toRead = Math.min(buffer.length, totalSamples - samplesWritten);
            SmpsDriverTestAccess.read(driver, buffer);
            System.arraycopy(buffer, 0, audio, samplesWritten, toRead);
            samplesWritten += toRead;
        }

        return audio;
    }

    private short[] renderAudioUntilComplete(SmpsDriver driver, int maxSamples) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        short[] buffer = new short[BUFFER_SIZE * 2];

        int samplesWritten = 0;
        try {
            while (!driver.isComplete() && samplesWritten < maxSamples) {
                SmpsDriverTestAccess.read(driver, buffer);
                for (int i = 0; i < buffer.length && samplesWritten < maxSamples; i++) {
                    dos.writeShort(buffer[i]);
                    samplesWritten++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Convert back to short array
        byte[] bytes = baos.toByteArray();
        short[] audio = new short[bytes.length / 2];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            for (int i = 0; i < audio.length; i++) {
                audio[i] = dis.readShort();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return audio;
    }

    private void writeWav(String filename, short[] audio) throws IOException {
        Path filePath = Paths.get(REFERENCE_DIR, filename);

        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                (float) SAMPLE_RATE,
                16,     // bits per sample
                2,      // channels (stereo)
                4,      // frame size (2 channels * 2 bytes)
                (float) SAMPLE_RATE,
                false   // little endian
        );

        // Convert short[] to byte[]
        byte[] byteData = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            // Little endian
            byteData[i * 2] = (byte) (audio[i] & 0xFF);
            byteData[i * 2 + 1] = (byte) ((audio[i] >> 8) & 0xFF);
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteData);
             AudioInputStream ais = new AudioInputStream(bais, format, audio.length / 2)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, filePath.toFile());
        }
    }

    /**
     * Main entry point for generating reference files.
     * Requires ROM file in working directory or via -Dsonic.rom.path
     */
    public static void main(String[] args) {
        String romPath = System.getProperty("sonic.rom.path", "s2.gen");
        File romFile = new File(romPath);

        if (!romFile.exists()) {
            System.err.println("ROM file not found: " + romFile.getAbsolutePath());
            System.err.println("Please provide the ROM via -Dsonic.rom.path or place it in the working directory.");
            System.exit(1);
        }

        Rom rom = new Rom();
        if (!rom.open(romFile.getAbsolutePath())) {
            System.err.println("Failed to open ROM file.");
            System.exit(1);
        }

        try {
            AudioReferenceGenerator generator = new AudioReferenceGenerator(rom);
            generator.generateAll();
        } catch (IOException e) {
            System.err.println("Error generating reference files: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
