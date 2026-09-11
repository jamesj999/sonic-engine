package com.openggf.tools.audio.benchmark;

import com.openggf.audio.synth.nuked.NukedOpn2;

/** Fixed-work driver for JFR sampling; intentionally records no wall-clock timing. */
public final class JavaNukedProfile {
    private static volatile long sink;

    private JavaNukedProfile() {
    }

    private static long sustain(int frames) {
        NukedOpn2 chip = JavaNukedBenchmark.programmed();
        int[] pins = new int[2];
        long checksum = 0;
        for (int index = 0; index < frames; index++) {
            checksum = Long.rotateLeft(checksum, 7) ^ JavaNukedBenchmark.frame(chip, pins);
        }
        return checksum;
    }

    private static long release(int frames) {
        NukedOpn2 chip = JavaNukedBenchmark.programmed();
        int[] pins = new int[2];
        JavaNukedBenchmark.register(chip, pins, 0, 0x28, 0x00);
        long checksum = 0;
        for (int index = 0; index < frames; index++) {
            checksum = Long.rotateLeft(checksum, 7) ^ JavaNukedBenchmark.frame(chip, pins);
        }
        return checksum;
    }

    private static long dac(int frames) {
        NukedOpn2 chip = JavaNukedBenchmark.programmed();
        int[] pins = new int[2];
        JavaNukedBenchmark.register(chip, pins, 0, 0x2b, 0x80);
        long checksum = 0;
        for (int index = 0; index < frames; index++) {
            JavaNukedBenchmark.register(chip, pins, 0, 0x2a, index * 29);
            checksum = Long.rotateLeft(checksum, 7) ^ JavaNukedBenchmark.frame(chip, pins);
        }
        return checksum;
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: JavaNukedProfile <frames-per-phase> <passes>");
        }
        int frames = Integer.parseInt(arguments[0]);
        int passes = Integer.parseInt(arguments[1]);
        if (frames <= 0 || passes <= 0) {
            throw new IllegalArgumentException("profile dimensions must be positive");
        }
        long checksum = 0;
        for (int pass = 0; pass < passes; pass++) {
            checksum ^= sustain(frames);
            checksum ^= Long.rotateLeft(release(frames), 17);
            checksum ^= Long.rotateLeft(dac(frames), 31);
        }
        sink = checksum;
        System.out.println(Long.toUnsignedString(sink));
    }
}
