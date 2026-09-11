package com.openggf.audio.debug;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSoundTestWavExport {
    @TempDir
    Path outputDirectory;

    @Test
    void renderToWavWritesExactStereoPcmFrameCount() throws Exception {
        Path output = outputDirectory.resolve("sound-test.wav");
        Files.write(output, new byte[4_096]);
        int frames = SoundTestApp.renderToWav(
                new AudioTestFixtures.StubSmpsData("sound-test-export"),
                AudioTestFixtures.EMPTY_DAC,
                new SmpsSequencerConfig.Builder().build(),
                output.toFile(), false, 8_000.0, 37);

        assertEquals(37, frames);
        assertEquals(44 + 37 * 4, Files.size(output));
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(
                output.toFile())) {
            AudioFormat format = wav.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED,
                    format.getEncoding());
            assertEquals(8_000.0f, format.getSampleRate());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(2, format.getChannels());
            assertEquals(37, wav.getFrameLength());
        }
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_1)
    class RomBackedSfxExport {
        @Test
        void renderToWavCompletesRomBackedElectricSfxWithAudiblePcm()
                throws Exception {
            var romFile = RomTestUtils.ensureSonic1RomAvailable();
            assertNotNull(romFile);
            Rom rom = new Rom();
            assertTrue(rom.open(romFile.getAbsolutePath()));
            Sonic1SmpsLoader loader = new Sonic1SmpsLoader(rom);
            int safetyCap = 8_000;
            Path output = outputDirectory.resolve("sound-test-sfx.wav");
            Files.write(output, new byte[16_384]);

            int frames = SoundTestApp.renderToWav(
                    loader.loadSfx(Sonic1Sfx.ELECTRIC.id),
                    loader.loadDacData(),
                    Sonic1SmpsSequencerConfig.CONFIG, output.toFile(), true,
                    8_000.0, safetyCap);

            assertTrue(frames > 0 && frames < safetyCap,
                    "ROM-backed SFX must complete before the safety cap");
            assertEquals(44L + frames * 4L, Files.size(output),
                    "completed export must truncate stale bytes exactly");
            assertTrue(hasNonzeroPcm(output),
                    "completed SFX export must contain rendered audio");
        }
    }

    private static boolean hasNonzeroPcm(Path output) throws Exception {
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(
                output.toFile())) {
            byte[] pcm = wav.readAllBytes();
            for (byte sampleByte : pcm) {
                if (sampleByte != 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
