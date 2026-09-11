package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestFastFmTimersAndChannelThree {
    @Test
    void timersCountAtTheirDocumentedFrameRates() {
        // Sega's YM2612 manual p12: A = 18 us/unit, B = 288 us/unit at
        // 8 MHz. One internal frame is 144 input clocks: 1 and 16 frames.
        for (boolean timerB : new boolean[] {false, true}) {
            FastYm2612Dsp dsp = new FastYm2612Dsp();
            int period = timerB ? 16 * 20 : 100;
            if (timerB) {
                dsp.writeRegister(0, 0x26, 256 - 20);
            } else {
                dsp.writeRegister(0, 0x24, (1024 - 100) >> 2);
                dsp.writeRegister(0, 0x25, (1024 - 100) & 3);
            }
            int enabled = timerB ? 0x0a : 0x05;
            int flag = timerB ? 2 : 1;
            int clear = timerB ? 0x20 : 0x10;
            dsp.writeRegister(0, 0x27, enabled);
            int[] channels = new int[6];
            for (int iteration = 0; iteration < 2; iteration++) {
                for (int frame = 1; frame < period; frame++) {
                    dsp.renderFrame(channels);
                    assertEquals(0, dsp.readStatus() & flag, "timer must not expire early");
                }
                dsp.renderFrame(channels);
                assertEquals(flag, dsp.readStatus() & flag, "timer must expire at its documented period");
                dsp.writeRegister(0, 0x27, enabled | clear);
                assertEquals(0, dsp.readStatus() & flag, "reset flag must acknowledge overflow");
            }
        }
    }

    @Test
    void stoppingTimerDuringCsmPulseCannotLeaveAStuckNote() {
        FastYm2612Dsp dsp = new FastYm2612Dsp();
        for (int slot = 0; slot < 4; slot++) {
            int offset = slot * 4 + 2;
            dsp.writeRegister(0, 0x30 + offset, 1);
            dsp.writeRegister(0, 0x40 + offset, slot == 3 ? 0 : 127);
            dsp.writeRegister(0, 0x50 + offset, 31);
            dsp.writeRegister(0, 0x80 + offset, 15);
        }
        dsp.writeRegister(0, 0xb2, 7);
        dsp.writeRegister(0, 0xa6, 0x22);
        dsp.writeRegister(0, 0xa2, 0x69);
        dsp.writeRegister(0, 0x24, 255);
        dsp.writeRegister(0, 0x25, 3);
        dsp.writeRegister(0, 0x27, 0x85);
        int[] channels = new int[6];
        dsp.renderFrame(channels); // The timer's one-frame key pulse is now active.
        dsp.writeRegister(0, 0x27, 0);
        for (int frame = 0; frame < 4096; frame++) dsp.renderFrame(channels);
        for (int frame = 0; frame < 256; frame++) {
            dsp.renderFrame(channels);
            assertEquals(0, channels[2], "CSM key-off must finish even after timer A stops");
        }
    }

    @Test
    void everyChannelThreeSlotUsesTheOraclesSpecialFrequency() {
        for (int slot = 0; slot < 4; slot++) {
            int accurate = crossings(tone(new Ym2612Chip(), slot));
            int fast = crossings(tone(new FastYm2612Chip(new FastYm2612Dsp()), slot));
            System.out.printf("ch3-frequency register-slot=%d accurate-crossings=%d fast-crossings=%d%n",
                    slot, accurate, fast);
            assertTrue(Math.abs(accurate - fast) <= 1,
                    "special frequency differs for register slot " + slot + ": " + accurate + "/" + fast);
        }
    }

    private static int[] tone(FmChip chip, int slot) {
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        for (int index = 0; index < 4; index++) {
            int offset = index * 4 + 2;
            chip.write(0, 0x30 + offset, 1);
            chip.write(0, 0x40 + offset, index == slot ? 0 : 127);
            chip.write(0, 0x50 + offset, 31);
            chip.write(0, 0x60 + offset, 0);
            chip.write(0, 0x70 + offset, 0);
            chip.write(0, 0x80 + offset, 15);
        }
        chip.write(0, 0xb2, 7);
        chip.write(0, 0xb6, 0xc0);
        chip.write(0, 0x27, 0x40);
        int[] fnums = {500, 1000, 1500, 1800};
        int[] high = {0xa6, 0xac, 0xad, 0xae};
        int[] low = {0xa2, 0xa8, 0xa9, 0xaa};
        for (int index = 0; index < 4; index++) {
            chip.write(0, high[index], (4 << 3) | (fnums[index] >> 8));
            chip.write(0, low[index], fnums[index] & 255);
        }
        chip.write(0, 0x28, 0xf2);
        chip.renderStereo(new int[256], new int[256], 256);
        int[] left = new int[8192];
        chip.renderStereo(left, new int[left.length], left.length);
        return left;
    }

    private static int crossings(int[] samples) {
        double mean = java.util.Arrays.stream(samples).average().orElseThrow();
        int count = 0;
        for (int index = 1; index < samples.length; index++) {
            if (samples[index - 1] < mean && samples[index] >= mean) count++;
        }
        return count;
    }
}
