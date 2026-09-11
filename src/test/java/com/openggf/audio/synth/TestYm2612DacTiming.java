package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAC playback length is fixed by the ROM's Z80 playback loop alone. The
 * presentation-only interpolation option must never alter it: an earlier
 * implementation queued its synthetic {@code 0x2A} write in the slot that
 * gates the real sample cadence, so every pending synthetic write paused the
 * Z80 clock for the bus settle time and stretched samples by ~1.7x (about
 * 9.5 semitones flat).
 */
class TestYm2612DacTiming {
    /** Sonic 1 zPlayPCMLoop: 301 Z80 cycles per byte (s1disasm sound/z80.asm). */
    private static final int S1_BASE_CYCLES = 301;
    private static final int RATE = 0x08;
    private static final int SAMPLE_BYTES = 4000;
    /** Internal FM cycles per output frame of the chip core. */
    private static final int CYCLES_PER_INTERNAL_FRAME = 24;
    /** Accumulator units per internal cycle: 14 (Z80 ratio denominator) * 2 samples per byte. */
    private static final int DAC_TICK_UNITS = 14 * 2;

    @Test
    void interpolationDoesNotChangeSampleLength() {
        assertEquals(playbackFrames(false), playbackFrames(true),
                "interpolation altered the DAC cadence");
    }

    @Test
    void sampleFinishesAtTheRomDerivedCadence() {
        // (301 + 2 * 13 * (8 - 1)) Z80 cycles per byte * 5 = 2415 accumulator
        // units per sample; / 28 units per cycle = 86.25 FM cycles per sample.
        int periodUnits = Ym2612Chip.dacPeriod(S1_BASE_CYCLES, RATE);
        assertEquals(2415, periodUnits);
        double internalCycles = (double) SAMPLE_BYTES * periodUnits / DAC_TICK_UNITS;
        double expected = internalCycles / CYCLES_PER_INTERNAL_FRAME
                * Ym2612Chip.getDefaultOutputRate() / Ym2612Chip.getInternalRate();

        // Resampler latency and the final flush are a handful of frames; the
        // stall bug was +8,700 frames on this input.
        assertEquals(expected, playbackFrames(false), 16.0, "interpolation off");
        assertEquals(expected, playbackFrames(true), 16.0, "interpolation on");
    }

    private static long playbackFrames(boolean interpolate) {
        byte[] pcm = new byte[SAMPLE_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (0x80 + 60 * Math.sin(i / 7.0));
        }
        DacData dac = new DacData(
                Map.of(1, pcm),
                Map.of(0x81, new DacData.DacEntry(1, RATE)),
                S1_BASE_CYCLES);
        Ym2612Chip chip = new Ym2612Chip();
        chip.setDacData(dac);
        chip.setDacInterpolate(interpolate);
        chip.playDac(0x81);

        int[] left = new int[1];
        int[] right = new int[1];
        long frames = 0;
        do {
            chip.renderStereo(left, right, 1);
            frames++;
            assertTrue(frames < 100_000, "DAC sample never finished");
        } while (chip.captureSnapshot().currentDacSampleId() != -1);
        return frames;
    }
}
