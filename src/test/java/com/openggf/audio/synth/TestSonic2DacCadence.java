package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2DacCadence {

    @Test
    void loaderReportsTheRetailTwoSampleBudget() {
        DacData dac = new Sonic2SmpsLoader(TestEnvironment.currentRom())
                .loadDacData();

        assertEquals(295, dac.baseCycles(),
                "retail zWriteToDAC costs 295 Z80 cycles per byte");
    }

    @Test
    void loadedRatesDriveTheRetailCadenceConsumer() {
        DacData dac = new Sonic2SmpsLoader(TestEnvironment.currentRom())
                .loadDacData();

        assertEquals(1475, Ym2612Chip.dacPeriod(dac.baseCycles(), 1),
                "rate 1 has no taken pitch-loop iterations");

        DacData.DacEntry kick = dac.mappingForNote(0x81);
        assertEquals(23, kick.rate(),
                "the REV01 kick rate byte was assembled against the retail loop");
        assertEquals(4335, Ym2612Chip.dacPeriod(
                        dac.baseCycles(), kick.rate()),
                "295 + two 13-cycle loops times 22 iterations");
        double expectedFrames = (double) dac.sample(kick.sampleId()).length()
                * 4335 / (14 * 2) / 24
                * Ym2612Chip.getDefaultOutputRate() / Ym2612Chip.getInternalRate();
        // BlipResampler requires FILTER_TAPS/2 = 8 future internal frames
        // before exposing an output sample. Converting that look-ahead to the
        // output clock plus one frame of phase rounding bounds the observation
        // offset by 9 output frames; this is scheduler latency, not fixture fit.
        assertEquals(expectedFrames, playbackFrames(dac, 0x81), 9.0,
                "the loaded kick must finish at the retail cadence");
    }

    @Test
    void cadenceFormulaRetainsIndependentS1AndS3kBudgets() {
        assertEquals(1505, Ym2612Chip.dacPeriod(301, 1),
                "S1 retains its 301-cycle input");
        assertEquals(1485, Ym2612Chip.dacPeriod(297, 1),
                "S3K retains its independently owned current input");
    }

    private static long playbackFrames(DacData dac, int note) {
        Ym2612Chip chip = new Ym2612Chip();
        chip.setDacData(dac);
        chip.playDac(note);
        int[] left = new int[1];
        int[] right = new int[1];
        long frames = 0;
        do {
            chip.renderStereo(left, right, 1);
            frames++;
            if (frames >= 100_000) {
                throw new AssertionError("DAC sample never finished");
            }
        } while (chip.captureSnapshot().currentDacSampleId() != -1);
        return frames;
    }
}
