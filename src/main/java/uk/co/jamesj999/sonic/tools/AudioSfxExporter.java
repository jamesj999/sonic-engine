package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.audio.driver.SmpsDriver;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import uk.co.jamesj999.sonic.game.sonic2.audio.smps.Sonic2SmpsLoader;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Export selected Sonic 2 SFX to WAV files for parity checks.
 *
 * Usage (from repo root):
 *   mvn -q -DskipTests package
 *   java -cp "target/classes;." uk.co.jamesj999.sonic.tools.AudioSfxExporter [options]
 */
public class AudioSfxExporter {
    private static final String ROM_PATH = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
    private static final String DEFAULT_OUTPUT_DIR = "docs/audio-debug";
    private static final int DEFAULT_SECONDS = 5;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_SILENCE_THRESHOLD = 64;
    private static final int DEFAULT_PREROLL_FRAMES = 64;
    private static final int DEFAULT_POSTROLL_FRAMES = 512;

    private static final class SfxSpec {
        final int id;
        final String name;
        final int seconds;

        SfxSpec(int id, String name, int seconds) {
            this.id = id;
            this.name = name;
            this.seconds = seconds;
        }
    }

    public static void main(String[] args) throws Exception {
        Rom rom = new Rom();
        if (!rom.open(ROM_PATH)) {
            System.err.println("Failed to open ROM: " + ROM_PATH);
            return;
        }

        Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
        DacData dacSamples = loader.loadDacData();

        ExportConfig config = ExportConfig.fromArgs(args);
        List<SfxSpec> specs = buildDefaultSpecs(config.seconds);

        File outputDir = new File(config.outputDir);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            System.err.println("Failed to create output directory: " + outputDir.getAbsolutePath());
            return;
        }

        int sampleRate = config.internalRate
                ? (int) Math.round(Ym2612Chip.getInternalRate())
                : DEFAULT_SAMPLE_RATE;

        for (SfxSpec spec : specs) {
            AbstractSmpsData sfxData = loader.loadSfx(spec.id);
            if (sfxData == null) {
                System.err.println("Failed to load SFX 0x" + Integer.toHexString(spec.id).toUpperCase());
                continue;
            }

            String filename = spec.name + (config.internalRate ? "-emu-internal.wav" : "-emu.wav");
            File outputFile = new File(outputDir, filename);
            int frames = renderToWav(sfxData, dacSamples, outputFile, sampleRate, spec.seconds, config);
            System.out.println("Exported SFX 0x" + Integer.toHexString(spec.id).toUpperCase()
                    + " to: " + outputFile.getAbsolutePath()
                    + " (" + frames + " frames)");
        }
    }

    private static int renderToWav(AbstractSmpsData data, DacData dacSamples, File outputFile,
                                   int sampleRate, int seconds, ExportConfig config) throws Exception {
        SmpsDriver driver = new SmpsDriver(sampleRate);
        driver.setRegion(SmpsSequencer.Region.NTSC);
        driver.setDacInterpolate(true);

        SmpsSequencer seq = new SmpsSequencer(data, dacSamples, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSfxMode(true);
        seq.setSampleRate(sampleRate);
        driver.addSequencer(seq, true);

        int maxFrames = sampleRate * seconds;
        int bufferSize = 1024;
        short[] buffer = new short[bufferSize * 2];
        short[] full = new short[maxFrames * 2];
        int totalFrames = 0;

        while (totalFrames < maxFrames && !driver.isComplete()) {
            driver.read(buffer);
            int framesToCopy = Math.min(bufferSize, maxFrames - totalFrames);
            System.arraycopy(buffer, 0, full, totalFrames * 2, framesToCopy * 2);
            totalFrames += framesToCopy;
        }

        short[] trimmed = config.trim
                ? trimSilence(full, totalFrames, config.silenceThreshold, config.preRollFrames, config.postRollFrames)
                : slice(full, totalFrames);

        int frames = trimmed.length / 2;
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            byte[] header = new byte[44];
            raf.write(header);
            for (int i = 0; i < trimmed.length; i++) {
                raf.writeByte(trimmed[i] & 0xFF);
                raf.writeByte((trimmed[i] >> 8) & 0xFF);
            }

            int dataSize = frames * 2 * 2;
            raf.seek(0);
            writeWavHeader(raf, sampleRate, 2, 16, dataSize);
        }

        return frames;
    }

    private static void writeWavHeader(RandomAccessFile raf, int sampleRate, int channels, int bitsPerSample, int dataSize) throws Exception {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        raf.writeBytes("RIFF");
        writeIntLE(raf, dataSize + 36);
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeIntLE(raf, 16);
        writeShortLE(raf, (short) 1);
        writeShortLE(raf, (short) channels);
        writeIntLE(raf, sampleRate);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, (short) blockAlign);
        writeShortLE(raf, (short) bitsPerSample);
        raf.writeBytes("data");
        writeIntLE(raf, dataSize);
    }

    private static void writeIntLE(RandomAccessFile raf, int val) throws Exception {
        raf.writeByte(val & 0xFF);
        raf.writeByte((val >> 8) & 0xFF);
        raf.writeByte((val >> 16) & 0xFF);
        raf.writeByte((val >> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile raf, short val) throws Exception {
        raf.writeByte(val & 0xFF);
        raf.writeByte((val >> 8) & 0xFF);
    }

    private static short[] slice(short[] data, int frames) {
        int len = frames * 2;
        short[] out = new short[len];
        System.arraycopy(data, 0, out, 0, len);
        return out;
    }

    private static short[] trimSilence(short[] data, int frames, int threshold, int preRoll, int postRoll) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < frames; i++) {
            int l = data[i * 2];
            int r = data[i * 2 + 1];
            int amp = Math.max(Math.abs(l), Math.abs(r));
            if (amp > threshold) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }

        if (first < 0) {
            return new short[0];
        }

        first = Math.max(0, first - preRoll);
        last = Math.min(frames - 1, last + postRoll);

        int outFrames = last - first + 1;
        short[] out = new short[outFrames * 2];
        System.arraycopy(data, first * 2, out, 0, out.length);
        return out;
    }

    private static List<SfxSpec> buildDefaultSpecs(int defaultSeconds) {
        List<SfxSpec> specs = new ArrayList<>();
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_SPIKE_HIT, "a6-hurt-by-spikes", defaultSeconds));
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_RING_RIGHT, "b5-ring-right", defaultSeconds));
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_ROLLING, "be-roll", defaultSeconds));
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_RING_SPILL, "c6-ring-loss", defaultSeconds));
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_RING_LEFT, "ce-ring-left", defaultSeconds));
        specs.add(new SfxSpec(Sonic2AudioConstants.SFX_SHIELD, "af-shield", defaultSeconds));
        return specs;
    }

    private static final class ExportConfig {
        final String outputDir;
        final boolean internalRate;
        final boolean trim;
        final int seconds;
        final int silenceThreshold;
        final int preRollFrames;
        final int postRollFrames;

        private ExportConfig(String outputDir, boolean internalRate, boolean trim, int seconds,
                             int silenceThreshold, int preRollFrames, int postRollFrames) {
            this.outputDir = outputDir;
            this.internalRate = internalRate;
            this.trim = trim;
            this.seconds = seconds;
            this.silenceThreshold = silenceThreshold;
            this.preRollFrames = preRollFrames;
            this.postRollFrames = postRollFrames;
        }

        static ExportConfig fromArgs(String[] args) {
            String outputDir = DEFAULT_OUTPUT_DIR;
            boolean internalRate = false;
            boolean trim = true;
            int seconds = DEFAULT_SECONDS;
            int silenceThreshold = DEFAULT_SILENCE_THRESHOLD;
            int preRollFrames = DEFAULT_PREROLL_FRAMES;
            int postRollFrames = DEFAULT_POSTROLL_FRAMES;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--out".equals(arg) && i + 1 < args.length) {
                    outputDir = args[++i];
                } else if ("--internal-rate".equals(arg)) {
                    internalRate = true;
                } else if ("--no-trim".equals(arg)) {
                    trim = false;
                } else if ("--seconds".equals(arg) && i + 1 < args.length) {
                    seconds = Integer.parseInt(args[++i]);
                } else if ("--threshold".equals(arg) && i + 1 < args.length) {
                    silenceThreshold = Integer.parseInt(args[++i]);
                } else if ("--preroll".equals(arg) && i + 1 < args.length) {
                    preRollFrames = Integer.parseInt(args[++i]);
                } else if ("--postroll".equals(arg) && i + 1 < args.length) {
                    postRollFrames = Integer.parseInt(args[++i]);
                } else if (!arg.startsWith("--") && i == 0) {
                    outputDir = arg;
                }
            }

            return new ExportConfig(outputDir, internalRate, trim, seconds,
                    silenceThreshold, preRollFrames, postRollFrames);
        }
    }
}
