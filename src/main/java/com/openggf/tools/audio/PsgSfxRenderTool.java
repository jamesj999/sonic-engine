package com.openggf.tools.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless renderer for a single ROM-backed SFX through the real SMPS driver.
 *
 * <p>Generalises the S2-only {@code AudioReferenceGenerator} test utility to
 * all three loaders so PSG-carrying SFX can be captured for chip validation
 * (see {@code docs/architecture/designs/2026-08-29-psg-clean-room-contract.md},
 * stage 2). For one SFX id it writes three files into {@code --out}:
 *
 * <ul>
 *   <li>{@code <game>-<id>-mix.wav}: the full driver mix, 16-bit stereo;</li>
 *   <li>{@code <game>-<id>-psg.wav}: the same render with every FM channel
 *       muted, so only the PSG reaches the output;</li>
 *   <li>{@code <game>-<id>-psg-writes.txt}: every PSG byte the driver issued,
 *       one {@code <frame> <hex byte>} pair per line, where {@code frame} is the
 *       number of output frames rendered before the write landed. The driver
 *       is read one frame at a time so the position is exact.</li>
 * </ul>
 *
 * <p>The write log is the input for the chip-level golden-vector comparison:
 * replaying it into {@code PsgChip} and into the reference core reproduces the
 * PSG-only render from register writes alone.
 *
 * <pre>
 * java -cp target/classes:... com.openggf.tools.audio.PsgSfxRenderTool \
 *     --game s1 --rom /path/to/s1.gen --sfx A0 --out /task/dir [--rate 44100] [--max-seconds 5]
 * </pre>
 */
public final class PsgSfxRenderTool {

    private static final double DEFAULT_RATE = 44_100.0;
    private static final double DEFAULT_MAX_SECONDS = 5.0;
    private static final int FM_CHANNELS = 6;

    private PsgSfxRenderTool() {
    }

    public static void main(String[] arguments) throws IOException {
        String game = null;
        String romPath = null;
        int sfxId = -1;
        Path out = null;
        double rate = DEFAULT_RATE;
        double maxSeconds = DEFAULT_MAX_SECONDS;
        for (int i = 0; i < arguments.length; i++) {
            switch (arguments[i]) {
                case "--game" -> game = arguments[++i].toLowerCase(Locale.ROOT);
                case "--rom" -> romPath = arguments[++i];
                case "--sfx" -> sfxId = Integer.parseInt(arguments[++i].replaceFirst("^0[xX]", ""), 16);
                case "--out" -> out = Path.of(arguments[++i]);
                case "--rate" -> rate = Double.parseDouble(arguments[++i]);
                case "--max-seconds" -> maxSeconds = Double.parseDouble(arguments[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + arguments[i]);
            }
        }
        if (game == null || romPath == null || sfxId < 0 || out == null) {
            System.err.println("usage: --game s1|s2|s3k --rom <path> --sfx <hex id> --out <dir> [--rate hz] [--max-seconds s]");
            System.exit(2);
        }

        Rom rom = new Rom();
        if (!rom.open(romPath)) {
            System.err.println("failed to open ROM: " + romPath);
            System.exit(1);
        }
        SmpsLoader loader;
        SmpsSequencerConfig config;
        switch (game) {
            case "s1" -> {
                loader = new Sonic1SmpsLoader(rom);
                config = Sonic1SmpsSequencerConfig.CONFIG;
            }
            case "s2" -> {
                loader = new Sonic2SmpsLoader(rom);
                config = Sonic2SmpsSequencerConfig.CONFIG;
            }
            case "s3k" -> {
                loader = new Sonic3kSmpsLoader(rom);
                config = Sonic3kSmpsSequencerConfig.CONFIG;
            }
            default -> throw new IllegalArgumentException("unknown game: " + game);
        }
        AbstractSmpsData sfx = loader.loadSfx(sfxId);
        if (sfx == null) {
            System.err.println("SFX 0x" + Integer.toHexString(sfxId) + " did not load for " + game);
            System.exit(1);
        }
        DacData dac = loader.loadDacData();

        Files.createDirectories(out);
        String stem = String.format(Locale.ROOT, "%s-%02x", game, sfxId);
        int maxFrames = (int) (maxSeconds * rate);

        Render mix = render(sfx, dac, config, rate, maxFrames, false);
        Render psgOnly = render(sfx, dac, config, rate, maxFrames, true);

        writeWav(out.resolve(stem + "-mix.wav"), mix.samples, rate);
        writeWav(out.resolve(stem + "-psg.wav"), psgOnly.samples, rate);
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(out.resolve(stem + "-psg-writes.txt")))) {
            log.printf(Locale.ROOT, "# game=%s sfx=%02X rate=%.3f frames=%d%n", game, sfxId, rate, mix.frames);
            for (Write w : mix.writes) {
                log.printf(Locale.ROOT, "%d %02X%n", w.frame, w.value);
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d frames (%.3f s), %d PSG writes, complete=%s%n",
                stem, mix.frames, mix.frames / rate, mix.writes.size(), mix.complete);
    }

    private record Write(long frame, int value) {
    }

    private record Render(short[] samples, int frames, boolean complete, List<Write> writes) {
    }

    private static Render render(AbstractSmpsData sfx, DacData dac, SmpsSequencerConfig config,
            double rate, int maxFrames, boolean muteFm) {
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "psg-render", 0,
                new SmpsPhysicalDevice.Settings(rate, false),
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                ChipWriteObserver.NONE)) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);
            stream.applyChannelMasks(muteFm ? 0x3F : 0, 0);
        List<Write> writes = new ArrayList<>();
        long[] framesRendered = {0};
        stream.setChipWriteObserver(new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(new Write(framesRendered[0], value));
            }
        });

        SmpsSequencer seq = new SmpsSequencer(sfx, dac, driver, () -> { }, config);
        seq.setSampleRate(rate);
        seq.setSfxMode(true);
        driver.addSequencer(seq, true);

        short[] samples = new short[maxFrames * 2];
        short[] frame = new short[2];
        int frames = 0;
        while (frames < maxFrames && !driver.isComplete()) {
            stream.read(frame, 2);
            samples[frames * 2] = frame[0];
            samples[frames * 2 + 1] = frame[1];
            frames++;
            framesRendered[0] = frames;
        }
        short[] trimmed = new short[frames * 2];
        System.arraycopy(samples, 0, trimmed, 0, trimmed.length);
            return new Render(trimmed, frames, driver.isComplete(), writes);
        }
    }

    private static void writeWav(Path path, short[] interleaved, double rate) throws IOException {
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                (float) rate, 16, 2, 4, (float) rate, false);
        byte[] bytes = new byte[interleaved.length * 2];
        for (int i = 0; i < interleaved.length; i++) {
            bytes[i * 2] = (byte) interleaved[i];
            bytes[i * 2 + 1] = (byte) (interleaved[i] >> 8);
        }
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(bytes), format,
                interleaved.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
