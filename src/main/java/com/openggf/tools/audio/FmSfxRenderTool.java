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
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.openggf.version.AppVersion;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless renderer for one ROM-backed SFX or song through the real SMPS
 * driver, capturing the YM2612 write stream alongside the audio.
 *
 * <p>Generalises {@code PsgSfxRenderTool} (branch {@code feature/ai-psg-clean-room})
 * to the FM side for the Nuked-OPN2 port validation
 * ({@code docs/architecture/designs/2026-08-29-nuked-opn2-port-contract.md},
 * stages 1 and 2). For one SFX id or music id it writes three files into
 * {@code --out}:
 *
 * <ul>
 *   <li>{@code <game>-<kind>-<id>-mix.wav}: the full driver mix, 16-bit stereo;</li>
 *   <li>{@code <game>-<kind>-<id>-fm.wav}: the same render with every PSG
 *       channel muted, so only the FM chip (and its DAC) reaches the output;</li>
 *   <li>{@code <game>-<kind>-<id>-ym-writes.txt}: every resolved YM2612 write
 *       the driver issued, one {@code <frame> <port> <reg> <val>} line (port
 *       0/1, register and value in hex), where {@code frame} is the number of
 *       output frames rendered before the write landed. The driver is read
 *       one frame at a time so the position is exact.</li>
 * </ul>
 *
 * <p>The legacy write log remains a frame-stamped logical diagnostic. Pass
 * {@code --physical-writes} to also produce {@code -ym-bus.jsonl}: dispatched
 * raw YM address/data strobes (including DAC bytes) and PSG data bytes at
 * native chip clocks. It is an engine/Nuked comparison artifact, not a
 * hardware capture or gameplay timing authority. The physical file carries
 * reset, restore, policy, and rollback discontinuities explicitly and never
 * manufactures a bus write for those model changes.
 *
 * <pre>
 * java -cp target/classes:... com.openggf.tools.audio.FmSfxRenderTool \
 *     --game s1 --rom /path/to/s1.gen (--sfx A6 | --music 81) --out /task/dir \
 *     [--rate 44100 | --rate internal] [--max-seconds 5]
 *     [--physical-writes [--physical-capacity 1000000]]
 * </pre>
 */
public final class FmSfxRenderTool {

    private static final double DEFAULT_RATE = 44_100.0;
    private static final double DEFAULT_SFX_MAX_SECONDS = 5.0;
    private static final double DEFAULT_MUSIC_MAX_SECONDS = 30.0;
    private static final int PSG_CHANNELS = 4;
    private static final int DEFAULT_PHYSICAL_CAPTURE_CAPACITY = 1_000_000;

    private FmSfxRenderTool() {
    }

    public static void main(String[] arguments) throws IOException {
        String game = null;
        String romPath = null;
        int sfxId = -1;
        int musicId = -1;
        Path out = null;
        double rate = DEFAULT_RATE;
        double maxSeconds = -1;
        boolean physicalWrites = false;
        int physicalCapacity = DEFAULT_PHYSICAL_CAPTURE_CAPACITY;
        for (int i = 0; i < arguments.length; i++) {
            switch (arguments[i]) {
                case "--game" -> game = arguments[++i].toLowerCase(Locale.ROOT);
                case "--rom" -> romPath = arguments[++i];
                case "--sfx" -> sfxId = parseHex(arguments[++i]);
                case "--music" -> musicId = parseHex(arguments[++i]);
                case "--out" -> out = Path.of(arguments[++i]);
                case "--rate" -> rate = parseRate(arguments[++i]);
                case "--max-seconds" -> maxSeconds = Double.parseDouble(arguments[++i]);
                case "--physical-writes" -> physicalWrites = true;
                case "--physical-capacity" -> physicalCapacity = Integer.parseInt(arguments[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + arguments[i]);
            }
        }
        if (game == null || romPath == null || (sfxId < 0) == (musicId < 0) || out == null) {
            System.err.println("usage: --game s1|s2|s3k --rom <path> (--sfx <hex id> | --music <hex id>)"
                    + " --out <dir> [--rate hz|internal] [--max-seconds s]");
            System.exit(2);
        }
        boolean music = musicId >= 0;
        if (maxSeconds < 0) {
            maxSeconds = music ? DEFAULT_MUSIC_MAX_SECONDS : DEFAULT_SFX_MAX_SECONDS;
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
        int id = music ? musicId : sfxId;
        AbstractSmpsData data = music ? loader.loadMusic(musicId) : loader.loadSfx(sfxId);
        if (data == null) {
            System.err.println((music ? "music" : "SFX") + " 0x" + Integer.toHexString(id)
                    + " did not load for " + game);
            System.exit(1);
        }
        DacData dac = loader.loadDacData();

        Files.createDirectories(out);
        String stem = String.format(Locale.ROOT, "%s-%s-%02x", game, music ? "music" : "sfx", id);
        int maxFrames = (int) (maxSeconds * rate);

        Render mix = render(data, dac, config, rate, maxFrames, music, false,
                physicalWrites ? new PhysicalChipCapture(physicalCapacity) : null);
        Render fmOnly = render(data, dac, config, rate, maxFrames, music, true);

        writeWav(out.resolve(stem + "-mix.wav"), mix.samples, rate);
        writeWav(out.resolve(stem + "-fm.wav"), fmOnly.samples, rate);
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(out.resolve(stem + "-ym-writes.txt")))) {
            log.printf(Locale.ROOT, "# game=%s %s=%02X rate=%.6f frames=%d complete=%s%n",
                    game, music ? "music" : "sfx", id, rate, mix.frames, mix.complete);
            for (Write w : mix.writes) {
                log.printf(Locale.ROOT, "%d %d %02X %02X%n", w.frame, w.port, w.register, w.value);
            }
        }
        if (mix.physicalCapture != null) {
            PhysicalChipCapture capture = mix.physicalCapture;
            capture.write(out.resolve(stem + "-ym-bus.jsonl"), game,
                    music ? "music" : "sfx", id, rate,
                    Path.of(romPath).toAbsolutePath().normalize().toString(),
                    sha1(Path.of(romPath)), AppVersion.identity());
            if (capture.overflowed()) {
                throw new IllegalStateException("physical capture overflowed after render: "
                        + capture.dropped() + " events dropped; incomplete "
                        + stem + "-ym-bus.jsonl is not replayable");
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d frames (%.3f s), %d YM writes, complete=%s%n",
                stem, mix.frames, mix.frames / rate, mix.writes.size(), mix.complete);
    }

    private static int parseHex(String text) {
        return Integer.parseInt(text.replaceFirst("^0[xX]", ""), 16);
    }

    private static double parseRate(String text) {
        if ("internal".equalsIgnoreCase(text)) {
            return Ym2612Chip.getInternalRate();
        }
        return Double.parseDouble(text);
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK lacks SHA-1", failure);
        }
    }

    private record Write(long frame, int port, int register, int value) {
    }

    private record Render(short[] samples, int frames, boolean complete,
            List<Write> writes, PhysicalChipCapture physicalCapture) {
    }

    private static Render render(AbstractSmpsData data, DacData dac, SmpsSequencerConfig config,
            double rate, int maxFrames, boolean music, boolean mutePsg) {
        return render(data, dac, config, rate, maxFrames, music, mutePsg, null);
    }

    private static Render render(AbstractSmpsData data, DacData dac, SmpsSequencerConfig config,
            double rate, int maxFrames, boolean music, boolean mutePsg,
            PhysicalChipCapture physicalCapture) {
        List<Write> writes = new ArrayList<>();
        long[] framesRendered = {0};
        ChipWriteObserver physicalOnly = physicalObserver(physicalCapture);
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "fm-render", 0,
                new SmpsPhysicalDevice.Settings(rate, false, com.openggf.audio.synth.FmCoreSelection.ACCURATE),
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                physicalOnly)) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);
            stream.applyChannelMasks(0, mutePsg ? 0x0F : 0);
            if (physicalCapture != null) {
                physicalCapture.beginYmReplaySegment(stream.captureSynthSnapshotForTesting().ym());
            }
            // Keep the historical logical log starting after boot and mask setup,
            // while physical capture has already observed those dispatched strobes.
            stream.setChipWriteObserver(new ChipWriteObserver() {
                @Override
                public void onYm2612Write(int port, int register, int value) {
                    writes.add(new Write(framesRendered[0], port, register, value));
                }

                @Override
                public void onPsgWrite(int value) {
                }

                @Override
                public boolean observesPhysicalWrites() {
                    return physicalCapture != null;
                }

                @Override
                public void onYm2612BusWrite(long cycle, int busPort, int value,
                        ChipWriteObserver.PhysicalWriteOrigin origin) {
                    physicalCapture.onYm2612BusWrite(cycle, busPort, value, origin);
                }

                @Override
                public void onPsgBusWrite(long tick, int value) {
                    physicalCapture.onPsgBusWrite(tick, value);
                }

                @Override
                public void onPhysicalTimelineBoundary(
                        ChipWriteObserver.ChipClockDomain domain, long clock,
                        ChipWriteObserver.PhysicalTimelineBoundary boundary) {
                    physicalCapture.onPhysicalTimelineBoundary(domain, clock, boundary);
                }
            });

            SmpsSequencer seq = new SmpsSequencer(data, dac, driver, () -> { }, config);
            seq.setSampleRate(rate);
            seq.setSfxMode(!music);
            driver.addSequencer(seq, !music);

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
            if (physicalCapture != null) {
                physicalCapture.finish(frames, stream.captureSynthSnapshotForTesting().ym());
            }
            return new Render(trimmed, frames, driver.isComplete(), writes,
                    physicalCapture);
        }
    }

    private static ChipWriteObserver physicalObserver(
            PhysicalChipCapture physicalCapture) {
        if (physicalCapture == null) {
            return ChipWriteObserver.NONE;
        }
        return new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
            }

            @Override
            public boolean observesPhysicalWrites() {
                return true;
            }

            @Override
            public void onYm2612BusWrite(long cycle, int busPort, int value,
                    ChipWriteObserver.PhysicalWriteOrigin origin) {
                physicalCapture.onYm2612BusWrite(cycle, busPort, value, origin);
            }

            @Override
            public void onPsgBusWrite(long tick, int value) {
                physicalCapture.onPsgBusWrite(tick, value);
            }

            @Override
            public void onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain domain, long clock,
                    ChipWriteObserver.PhysicalTimelineBoundary boundary) {
                physicalCapture.onPhysicalTimelineBoundary(domain, clock, boundary);
            }
        };
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
