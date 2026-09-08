package com.openggf.tools.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestSfxRenderToolEntryPoints {
    private static final int RATE = 8_000;
    private static final int RENDER_CAP_FRAMES = RATE;

    @TempDir
    Path outputDirectory;

    @Test
    void fmToolMainRendersElectricSfxToPcmWavsAndYmLog()
            throws Exception {
        File rom = RomTestUtils.ensureSonic1RomAvailable();
        assertNotNull(rom);

        String[] arguments = arguments(rom, outputDirectory,
                Sonic1Sfx.ELECTRIC.id);
        String[] physicalArguments = Arrays.copyOf(arguments,
                arguments.length + 1);
        physicalArguments[arguments.length] = "--physical-writes";
        FmSfxRenderTool.main(physicalArguments);

        assertStereoPcm(outputDirectory.resolve("s1-sfx-b1-mix.wav"));
        Path fmOnly = outputDirectory.resolve("s1-sfx-b1-fm.wav");
        assertStereoPcm(fmOnly);
        assertTrue(Arrays.stream(toInts(readPcm(fmOnly)))
                .anyMatch(sample -> sample != 0),
                "FM-only output must contain a real rendered signal");
        String ymLog = Files.readString(outputDirectory.resolve(
                "s1-sfx-b1-ym-writes.txt"));
        assertTrue(ymLog.lines().skip(1).findAny().isPresent(),
                "FM fixture must issue real YM2612 writes");
        assertTrue(ymLog.lines().findFirst().orElseThrow()
                        .contains("complete=true"),
                "FM fixture must complete rather than hit the render cap");
        String physicalLog = Files.readString(outputDirectory.resolve(
                "s1-sfx-b1-ym-bus.jsonl"));
        assertTrue(physicalLog.contains("\"format\":\"openggf-physical-chip-bus-v1\""));
        assertTrue(physicalLog.contains("\"ym_domain\":\"YM2612_INTERNAL_CYCLE\""));
        assertTrue(physicalLog.contains("\"initial_state\":\"constructor_reset\""));
        assertTrue(physicalLog.contains("\"rom_sha1\":\"69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b\""));
        assertTrue(physicalLog.contains("\"type\":\"psg\",\"ordinal\":"),
                "early physical attachment includes session boot PSG writes");
        assertTrue(physicalLog.lines().anyMatch(line -> line.contains("\"type\":\"ym\"")),
                "opt-in capture must include dispatched YM bus strobes");
        int frames = frameCount(ymLog);
        assertArrayEquals(readPcm(outputDirectory.resolve(
                        "s1-sfx-b1-mix.wav")),
                renderAdapterPackets(rom, Sonic1Sfx.ELECTRIC.id, frames),
                "tool one-frame reads and packeted adapter reads must agree");
    }

    @Test
    void psgToolMainRendersJumpSfxToPcmWavsAndPsgLog()
            throws Exception {
        File rom = RomTestUtils.ensureSonic1RomAvailable();
        assertNotNull(rom);

        PsgSfxRenderTool.main(arguments(rom, outputDirectory,
                Sonic1Sfx.JUMP.id));

        assertStereoPcm(outputDirectory.resolve("s1-a0-mix.wav"));
        Path psgOnly = outputDirectory.resolve("s1-a0-psg.wav");
        assertStereoPcm(psgOnly);
        assertTrue(Arrays.stream(toInts(readPcm(psgOnly)))
                .anyMatch(sample -> sample != 0),
                "PSG-only output must contain a real rendered signal");
        String psgLog = Files.readString(outputDirectory.resolve(
                "s1-a0-psg-writes.txt"));
        assertTrue(psgLog.lines().skip(1).findAny().isPresent(),
                "PSG fixture must issue real SN76489 writes");
        assertTrue(frameCount(psgLog) < RENDER_CAP_FRAMES,
                "PSG fixture must complete rather than hit the render cap");
    }

    private static String[] arguments(File rom, Path output, int sfxId) {
        return new String[] {
                "--game", "s1",
                "--rom", rom.getAbsolutePath(),
                "--sfx", Integer.toHexString(sfxId),
                "--out", output.toString(),
                "--rate", Integer.toString(RATE),
                "--max-seconds", "1"
        };
    }

    private static int frameCount(String writeLog) {
        String header = writeLog.lines().findFirst().orElseThrow();
        for (String field : header.split(" ")) {
            if (field.startsWith("frames=")) {
                return Integer.parseInt(field.substring("frames=".length()));
            }
        }
        throw new AssertionError("write log has no frame count: " + header);
    }

    private static void assertStereoPcm(Path wavPath) throws Exception {
        assertTrue(Files.size(wavPath) > 44);
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(
                wavPath.toFile())) {
            AudioFormat format = wav.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED,
                    format.getEncoding());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(2, format.getChannels());
            assertTrue(wav.getFrameLength() > 0);
        }
    }

    private static short[] renderAdapterPackets(
            File romFile, int sfxId, int frames) throws Exception {
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        Sonic1SmpsLoader loader = new Sonic1SmpsLoader(rom);
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "fm-render", 0,
                new SmpsPhysicalDevice.Settings(RATE, false),
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                ChipWriteObserver.NONE)) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);
            SmpsSequencer sequencer = new SmpsSequencer(
                    loader.loadSfx(sfxId), loader.loadDacData(), driver,
                    () -> { }, Sonic1SmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(RATE);
            sequencer.setSfxMode(true);
            driver.addSequencer(sequencer, true);

            short[] rendered = new short[frames * 2];
            int offset = 0;
            while (offset < rendered.length) {
                int packetLength = Math.min(96 * 2,
                        rendered.length - offset);
                short[] packet = new short[packetLength];
                stream.read(packet, packetLength);
                System.arraycopy(packet, 0, rendered, offset,
                        packetLength);
                offset += packetLength;
            }
            return rendered;
        }
    }

    private static short[] readPcm(Path wavPath) throws Exception {
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(
                wavPath.toFile())) {
            byte[] bytes = wav.readAllBytes();
            short[] pcm = new short[bytes.length / 2];
            for (int index = 0; index < pcm.length; index++) {
                pcm[index] = (short) ((bytes[index * 2] & 0xFF)
                        | (bytes[index * 2 + 1] << 8));
            }
            return pcm;
        }
    }

    private static int[] toInts(short[] samples) {
        int[] result = new int[samples.length];
        for (int index = 0; index < samples.length; index++) {
            result[index] = samples[index];
        }
        return result;
    }
}
