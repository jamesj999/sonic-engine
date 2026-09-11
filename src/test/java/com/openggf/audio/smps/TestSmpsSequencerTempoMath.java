package com.openggf.audio.smps;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence proof for the closed-form {@link SmpsSequencer#samplesUntilTempoTicks(int)}.
 * {@link #referenceLoop} is a verbatim copy of the original O(ticks) implementation;
 * every test drives the production sequencer through real {@code advanceBatch} state
 * transitions (mirrored bit-exactly by a shadow counter) and asserts the production
 * result equals the reference loop exactly.
 */
class TestSmpsSequencerTempoMath {

    private static final int[] TICK_COUNTS = {1, 2, 3, 4, 5, 8, 16, 100, 255, 1000, 65535};

    /** Verbatim copy of the pre-optimization loop (the proven-correct reference). */
    private static int referenceLoop(int ticks, boolean tempoPathActive, double samplesPerFrame, double sampleCounter) {
        if (ticks <= 0) {
            return 0;
        }
        if (!tempoPathActive || samplesPerFrame <= 0) {
            return Integer.MAX_VALUE;
        }
        double counter = sampleCounter;
        double total = 0.0;
        for (int i = 0; i < ticks; i++) {
            double remaining = samplesPerFrame - counter;
            if (remaining <= 0.0) {
                remaining = samplesPerFrame;
            }
            total += remaining;
            counter += remaining;
            while (counter >= samplesPerFrame) {
                counter -= samplesPerFrame;
            }
        }
        return (int) Math.ceil(total);
    }

    /** Mirrors the sequencer state needed by samplesUntilTempoTicks plus a bit-exact shadow counter. */
    private static final class Harness {
        final SmpsSequencer sequencer;
        final boolean tempoPathActive;
        double shadowSamplesPerFrame;
        double shadowCounter;

        Harness(int tempo, SmpsSequencerConfig.TempoMode mode, double sampleRate, SmpsSequencer.Region region) {
            SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                    .tempoMode(mode)
                    .tempoModBase(0x100)
                    .build();
            sequencer = new SmpsSequencer(
                    new MinimalMusicData(tempo),
                    AudioTestFixtures.EMPTY_DAC,
                    new VirtualSynthesizer(),
                    AudioManager.getInstance(),
                    config);
            sequencer.setSampleRate(sampleRate);
            sequencer.setRegion(region);
            shadowSamplesPerFrame = sampleRate / region.frameRate;
            shadowCounter = 0.0;
            tempoPathActive = tempo != 0 || mode == SmpsSequencerConfig.TempoMode.OVERFLOW;
        }

        /** Same floating-point operations, in the same order, as SmpsSequencer.advanceBatch. */
        void advance(int samples) {
            sequencer.advanceBatch(samples);
            shadowCounter += samples;
            while (shadowCounter >= shadowSamplesPerFrame) {
                shadowCounter -= shadowSamplesPerFrame;
            }
        }

        void assertMatchesReference(int ticks) {
            int expected = referenceLoop(ticks, tempoPathActive, shadowSamplesPerFrame, shadowCounter);
            assertEquals(expected, sequencer.samplesUntilTempoTicks(ticks),
                    () -> "ticks=" + ticks + " samplesPerFrame=" + shadowSamplesPerFrame
                            + " sampleCounter=" + shadowCounter);
        }
    }

    @Test
    void exhaustiveCounterSweepNtsc44100() {
        Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, 44100.0, SmpsSequencer.Region.NTSC);
        // samplesPerFrame = 735: visit every integer counter state once.
        for (int c = 0; c < 735; c++) {
            for (int ticks : TICK_COUNTS) {
                h.assertMatchesReference(ticks);
            }
            h.assertMatchesReference(0);
            h.assertMatchesReference(-3);
            h.advance(1);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "44100, PAL",   // 882 samples/frame
            "48000, NTSC",  // 800 samples/frame
            "48000, PAL",   // 960 samples/frame
            "22050, NTSC",  // 367.5 samples/frame (half-integral)
    })
    void randomWalksAcrossProductionRates(double sampleRate, SmpsSequencer.Region region) {
        for (long seed = 1; seed <= 3; seed++) {
            Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, sampleRate, region);
            Random random = new Random(seed);
            for (int i = 0; i < 400; i++) {
                for (int ticks : TICK_COUNTS) {
                    h.assertMatchesReference(ticks);
                }
                h.advance(random.nextInt(2048));
            }
        }
    }

    @Test
    void nonIntegralInternalRateStaysLoopEquivalent() {
        // AUDIO_INTERNAL_RATE_OUTPUT path: samplesPerFrame is not an exact integer,
        // so the closed form must defer to the reference loop bit-for-bit.
        double internalRate = Ym2612Chip.getInternalRate();
        for (long seed = 10; seed <= 12; seed++) {
            Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, internalRate, SmpsSequencer.Region.NTSC);
            Random random = new Random(seed);
            for (int i = 0; i < 400; i++) {
                for (int ticks : TICK_COUNTS) {
                    h.assertMatchesReference(ticks);
                }
                h.advance(random.nextInt(4096));
            }
        }
    }

    @Test
    void zeroTempoOverflow2ReturnsMaxValue() {
        Harness h = new Harness(0, SmpsSequencerConfig.TempoMode.OVERFLOW2, 44100.0, SmpsSequencer.Region.NTSC);
        h.advance(100);
        for (int ticks : TICK_COUNTS) {
            h.assertMatchesReference(ticks);
            assertEquals(Integer.MAX_VALUE, h.sequencer.samplesUntilTempoTicks(ticks));
        }
    }

    @Test
    void zeroTempoOverflowModeStillTicksEveryFrame() {
        // S3K OVERFLOW mode ticks on non-overflow, so tempoWeight == 0 still advances.
        Harness h = new Harness(0, SmpsSequencerConfig.TempoMode.OVERFLOW, 44100.0, SmpsSequencer.Region.NTSC);
        Random random = new Random(99);
        for (int i = 0; i < 200; i++) {
            for (int ticks : TICK_COUNTS) {
                h.assertMatchesReference(ticks);
            }
            h.advance(random.nextInt(1024));
        }
    }

    @Test
    void exactBoundaryStates() {
        Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, 44100.0, SmpsSequencer.Region.NTSC);
        // counter exactly 0 (fresh) and exactly samplesPerFrame - 1.
        h.assertMatchesReference(1);
        h.advance(734);
        h.assertMatchesReference(1);
        h.assertMatchesReference(2);
        // crossing the boundary resets to 0 exactly.
        h.advance(1);
        h.assertMatchesReference(1);
        // advancing by exact multiples of the frame size keeps counter at 0.
        h.advance(735 * 7);
        h.assertMatchesReference(1);
        h.assertMatchesReference(65535);
    }

    @Test
    void counterAboveFrameSizeAfterRegionSwitchMatchesLoop() {
        // PAL (882) counter state can exceed the NTSC (735) frame size after a
        // region switch; the legacy loop has bespoke semantics there (first
        // "remaining" becomes a full frame, second absorbs the excess).
        Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, 44100.0, SmpsSequencer.Region.PAL);
        h.advance(800);
        h.sequencer.setRegion(SmpsSequencer.Region.NTSC);
        h.shadowSamplesPerFrame = 44100.0 / 60.0;
        // shadowCounter stays 800 (> 735), matching the sequencer.
        for (int ticks : TICK_COUNTS) {
            h.assertMatchesReference(ticks);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "44100, NTSC",
            "44100, PAL",
            "48000, NTSC",
            "48000, PAL",
    })
    void productionConfigsSatisfyClosedFormFastPathGuard(double sampleRate, SmpsSequencer.Region region) {
        // The closed form only runs when samplesPerFrame and sampleCounter are exact
        // integers and the first remaining span is positive. Pin that every real
        // production rate/region config keeps satisfying the guard across advanceBatch
        // walks, so a future fractional-rate change cannot silently park production on
        // the O(ticks) fallback loop.
        Harness h = new Harness(0x80, SmpsSequencerConfig.TempoMode.OVERFLOW2, sampleRate, region);
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            double samplesPerFrame = readDouble(h.sequencer, "samplesPerFrame");
            double sampleCounter = readDouble(h.sequencer, "sampleCounter");
            assertEquals(samplesPerFrame, Math.floor(samplesPerFrame),
                    "samplesPerFrame must stay an exact integer for the fast path");
            assertEquals(sampleCounter, Math.floor(sampleCounter),
                    "sampleCounter must stay an exact integer for the fast path");
            assertTrue(samplesPerFrame - sampleCounter > 0.0,
                    "first remaining span must stay positive for the fast path");
            h.advance(random.nextInt(2048));
        }
    }

    private static double readDouble(SmpsSequencer sequencer, String fieldName) {
        try {
            java.lang.reflect.Field field = SmpsSequencer.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getDouble(sequencer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 255, 65535})
    void perturbedClosedFormsDivergeFromReference(int ticks) {
        // Sensitivity check: the equivalence harness must be able to detect a wrong
        // formula. An off-by-one tick count must diverge on integral frame sizes,
        // and floor-instead-of-ceil rounding must diverge on fractional totals
        // (22050 Hz / 60 fps = 367.5 samples per frame).
        boolean offByOneDiverges = false;
        for (int c = 0; c < 735; c++) {
            double first = 735.0 - c;
            int offByOne = (int) Math.ceil(first + (double) ticks * 735.0);
            offByOneDiverges |= offByOne != referenceLoop(ticks, true, 735.0, c);
        }
        assertTrue(offByOneDiverges, "off-by-one tick perturbation should be detectable");

        boolean floorDiverges = false;
        for (int halfSteps = 0; halfSteps < 735; halfSteps++) {
            double c = halfSteps * 0.5;
            double first = 367.5 - c;
            int floored = (int) Math.floor(first + (ticks - 1) * 367.5);
            floorDiverges |= floored != referenceLoop(ticks, true, 367.5, c);
        }
        assertTrue(floorDiverges, "floor rounding perturbation should be detectable");
    }

    private static final class MinimalMusicData extends AbstractSmpsData {
        private MinimalMusicData(int tempo) {
            super(new byte[0], 0);
            this.tempo = tempo;
        }

        @Override
        protected void parseHeader() {
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return new byte[25];
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return new byte[0];
        }

        @Override
        public int read16(int offset) {
            return 0;
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }
}
