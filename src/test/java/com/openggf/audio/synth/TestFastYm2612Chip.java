package com.openggf.audio.synth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.fast.FmDsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Facade contract over a scripted {@link FmDsp}: queuing, panning, muting,
 * scaling, DAC streaming, snapshots and SFX admission are the facade's, so
 * they are tested without any real synthesis.
 */
class TestFastYm2612Chip {

    /** Records writes and emits a fixed value per channel. */
    static final class ScriptedDsp implements FmDsp {
        final List<int[]> writes = new ArrayList<>();
        final int[] levels = new int[6];
        int status;
        int frames;

        @Override
        public void reset() {
            writes.clear();
            Arrays.fill(levels, 0);
            frames = 0;
        }

        @Override
        public void writeRegister(int port, int register, int value) {
            writes.add(new int[] {port, register, value});
        }

        @Override
        public void renderFrame(int[] out) {
            System.arraycopy(levels, 0, out, 0, 6);
            frames++;
        }

        @Override
        public int readStatus() {
            return status;
        }

        @Override
        public void copyStateTo(FmDsp target) {
            ScriptedDsp other = (ScriptedDsp) target;
            other.writes.clear();
            other.writes.addAll(writes);
            System.arraycopy(levels, 0, other.levels, 0, 6);
            other.status = status;
            other.frames = frames;
        }

        @Override
        public FmDsp newInstance() {
            return new ScriptedDsp();
        }

        @Override
        public boolean equals(Object candidate) {
            if (!(candidate instanceof ScriptedDsp other)) {
                return false;
            }
            return Arrays.equals(levels, other.levels) && status == other.status && frames == other.frames
                    && writes.size() == other.writes.size();
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(levels), status, frames, writes.size());
        }
    }

    private static FastYm2612Chip internalRateChip(ScriptedDsp dsp) {
        FastYm2612Chip chip = new FastYm2612Chip(dsp);
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        return chip;
    }

    private static int[] render(FastYm2612Chip chip, int frames) {
        int[] left = new int[frames];
        int[] right = new int[frames];
        chip.renderStereo(left, right, frames);
        return new int[] {left[frames - 1], right[frames - 1]};
    }

    @Test
    void writesAreQueuedUntilRenderAndPanningScalesToTheFacadeScale() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        dsp.levels[0] = 8192;   // channel 1 full scale
        dsp.levels[4] = -4096;  // channel 5 (port 1, hardware channel 1)
        chip.write(0, 0xB4, 0x80); // channel 1 left only
        chip.write(1, 0xB5, 0x40); // channel 5 right only
        assertTrue(dsp.writes.isEmpty(), "writes land at the next render");

        int[] frame = render(chip, 8); // one register write lands every 35 cycles (about 1.5 frames)
        assertEquals(2, dsp.writes.size());
        assertEquals(6144, frame[0], "full scale is 6144 at the mixer, i.e. 3/4 of the DSP range");
        assertEquals(-3072, frame[1]);
    }

    @Test
    void muteSilencesOneChannelWithoutTouchingTheDsp() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        dsp.levels[2] = 1000;
        chip.write(0, 0xB6, 0xC0);
        assertEquals(750, render(chip, 4)[0]);
        chip.setMute(2, true);
        assertEquals(0, render(chip, 1)[0]);
        chip.setMute(2, false);
        assertEquals(750, render(chip, 1)[0]);
    }

    @Test
    void dacBytesStreamAsRegister2aWritesAtTheRomCadenceAndReportTheEnd() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        Map<Integer, byte[]> samples = new HashMap<>();
        samples.put(0x81, new byte[] {10, 20, 30});
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
        mapping.put(0x81, new DacData.DacEntry(0x81, 0x17));
        chip.setDacData(new DacData(samples, mapping, 297));
        chip.playDac(0x81);

        int period = FmDacTiming.period(297, 0x17);
        int framesPerByte = (int) Math.ceil(period / (double) FmDacTiming.TICK_UNITS_PER_FRAME);
        render(chip, framesPerByte * 4 + 1);

        List<int[]> dacWrites = dsp.writes.stream()
                .filter(write -> write[0] == 0 && write[1] == 0x2A).toList();
        assertEquals(3, dacWrites.size(), "three sample bytes");
        assertEquals(List.of(10, 20, 30), dacWrites.stream().map(write -> write[2]).toList());
        assertTrue(chip.consumeDacSampleEnded());
        assertFalse(chip.consumeDacSampleEnded());
    }

    @Test
    void snapshotRoundTripsAndComparesByValue() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        dsp.levels[1] = 512;
        chip.write(0, 0xB5, 0xC0);
        chip.write(0, 0x28, 0xF1);
        render(chip, 3);
        chip.setMute(3, true);
        chip.write(1, 0xB4, 0x80); // still pending
        FmChip.Snapshot before = chip.captureSnapshot();

        render(chip, 5);
        chip.setMute(3, false);
        assertNotEquals(before, chip.captureSnapshot());

        chip.restoreSnapshot(before);
        FmChip.Snapshot restored = chip.captureSnapshot();
        assertEquals(before, restored);
        assertEquals(before.hashCode(), restored.hashCode());
        assertTrue(restored.mutes()[3]);

        Ym2612Chip.Snapshot foreign = new Ym2612Chip().captureSnapshot();
        assertThrows(IllegalArgumentException.class, () -> chip.restoreSnapshot(foreign));
    }

    @Test
    void mutationBackupRestoresPendingWritesAndDspState() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        FmChip.MutationBackup backup = chip.createMutationBackup();
        chip.write(0, 0xB4, 0xC0);
        chip.captureMutation(backup);
        chip.write(0, 0x30, 0x11);
        dsp.levels[0] = 99;
        chip.restoreMutation(backup);
        render(chip, 8);
        assertEquals(1, dsp.writes.size(), "only the pre-capture pan write survives");
        assertEquals(0xB4, dsp.writes.get(0)[1]);
        assertEquals(0, dsp.levels[0], "DSP state came back from the backup copy");

        FastYm2612Chip other = internalRateChip(new ScriptedDsp());
        assertThrows(IllegalArgumentException.class, () -> other.captureMutation(backup));
    }

    @Test
    void sfxAdmissionWithdrawsOnlyAffectedChannelsWritesQueuedAfterCapture() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        chip.write(0, 0xB4, 0xC0);                       // channel 1, before capture
        FmChip.SfxAdmissionState admission = chip.captureSfxAdmissionState(1 << 0);
        chip.write(0, 0x30, 0x22);                       // channel 1, withdrawn
        chip.write(0, 0x31, 0x33);                       // channel 2, kept
        chip.write(0, 0x28, 0xF0);                       // key on channel 1, withdrawn
        chip.restoreSfxAdmissionState(admission);
        render(chip, 8);
        assertArrayEquals(new int[] {0xB4, 0x31},
                dsp.writes.stream().mapToInt(write -> write[1]).toArray());
    }

    @Test
    void forceSilenceKeysOffAndAttenuatesEveryOperator() {
        ScriptedDsp dsp = new ScriptedDsp();
        FastYm2612Chip chip = internalRateChip(dsp);
        chip.forceSilenceChannel(4);
        render(chip, 1);
        assertEquals(9, dsp.writes.size());
        assertArrayEquals(new int[] {0, 0x28, 0x05}, dsp.writes.get(0));
        long tlWrites = dsp.writes.stream().filter(w -> w[0] == 1 && w[1] >= 0x40 && w[1] < 0x50 && w[2] == 0x7F).count();
        assertEquals(4, tlWrites);
    }

    @Test
    void fastSelectionBuildsTheBoundCoreAndConfigParsesLeniently() {
        assertTrue(com.openggf.audio.synth.fast.FastFmCores.available());
        VirtualSynthesizer synth = new VirtualSynthesizer(44100.0, ChipWriteObserver.NONE,
                VirtualSynthesizer.Initialization.DEFERRED, FmCoreSelection.FAST);
        assertEquals(FmCoreSelection.FAST, synth.fmCore());
        assertTrue(synth.captureSynthSnapshot().ym() instanceof FastYm2612Chip.Snapshot);
        assertEquals(FmCoreSelection.ACCURATE, FmCoreSelection.fromConfig("nonsense"));
        assertEquals(FmCoreSelection.FAST, FmCoreSelection.fromConfig(" Fast "));
    }
}
