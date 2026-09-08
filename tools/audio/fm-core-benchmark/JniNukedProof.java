package com.openggf.tools.audio.benchmark;

import com.openggf.audio.synth.nuked.NukedOpn2;
import com.openggf.audio.synth.nuked.NukedOpn2State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/** Linux-only research proof for actual JNI PCM transfer and opaque native snapshots. */
public final class JniNukedProof {
    private JniNukedProof() {
    }

    private static native long nativeCreate();
    private static native void nativeWrite(long handle, int port, int value);
    private static native int nativeClock(long handle, int cycles, int[] output, int offsetSamples);
    private static native byte[] nativeSnapshot(long handle);
    private static native void nativeRestore(long handle, byte[] snapshot);
    private static native void nativeDestroy(long handle);

    private static final class NativeCore implements AutoCloseable {
        private long handle = nativeCreate();

        void write(int port, int value) {
            nativeWrite(openHandle(), port, value);
        }

        int[] clock(int cycles) {
            byte[] before = cycles == 0 ? nativeSnapshot(openHandle()) : null;
            int maximumFrames = (cycles + 23) / 24 + 1;
            int[] oversized = new int[maximumFrames * 2];
            int frames = nativeClock(openHandle(), cycles, oversized, 0);
            if (frames * 2 > oversized.length) {
                throw new AssertionError("native frame count exceeded capacity");
            }
            if (cycles == 0 && !Arrays.equals(before, nativeSnapshot(openHandle()))) {
                throw new AssertionError("zero-cycle call mutated native state");
            }
            return Arrays.copyOf(oversized, frames * 2);
        }

        byte[] snapshot() {
            return nativeSnapshot(openHandle());
        }

        void restore(byte[] snapshot) {
            nativeRestore(openHandle(), snapshot);
        }

        int clockInto(int cycles, int[] output, int offsetSamples) {
            return nativeClock(openHandle(), cycles, output, offsetSamples);
        }

        private long openHandle() {
            if (handle == 0) {
                throw new IllegalStateException("native core is closed");
            }
            return handle;
        }

        @Override
        public void close() {
            if (handle != 0) {
                nativeDestroy(handle);
                handle = 0;
            }
        }
    }

    private static final class JavaCore {
        private final NukedOpn2 chip = new NukedOpn2();
        private final int[] pins = new int[2];
        private int cycleInFrame;
        private int left;
        private int right;

        JavaCore() {
            chip.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        }

        void write(int port, int value) {
            chip.write(port, value);
        }

        int[] clock(int cycles) {
            int[] output = new int[((cycleInFrame + cycles) / 24) * 2];
            int position = 0;
            for (int index = 0; index < cycles; index++) {
                chip.clock(pins);
                left += pins[0];
                right += pins[1];
                cycleInFrame++;
                if (cycleInFrame == 24) {
                    output[position++] = left;
                    output[position++] = right;
                    cycleInFrame = 0;
                    left = 0;
                    right = 0;
                }
            }
            return output;
        }

        JavaState snapshot() {
            return new JavaState(chip.state().copy(), cycleInFrame, left, right);
        }

        void restore(JavaState snapshot) {
            chip.state().copyFrom(snapshot.chip());
            cycleInFrame = snapshot.cycleInFrame();
            left = snapshot.left();
            right = snapshot.right();
        }
    }

    private record JavaState(NukedOpn2State chip, int cycleInFrame, int left, int right) {
    }

    private static void compareClock(JavaCore java, NativeCore nativeCore, int cycles, String label) {
        int[] expected = java.clock(cycles);
        int[] actual = nativeCore.clock(cycles);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + " Java/C PCM mismatch");
        }
    }

    private static int advanceCapture(JavaCore java, NativeCore nativeCore,
            long cycles, String label) {
        int frames = 0;
        while (cycles != 0) {
            int count = (int) Math.min(cycles, 24L * 1_024);
            int[] expected = java.clock(count);
            int[] actual = nativeCore.clock(count);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError(label + " Java/C PCM mismatch");
            }
            frames += expected.length / 2;
            cycles -= count;
        }
        return frames;
    }

    private static void replayCapture(String[] arguments) throws Exception {
        Path events = Path.of(arguments[2]);
        long terminal = Long.parseLong(arguments[3]);
        long cycle = 0;
        int eventCount = 0;
        int frames = 0;
        try (NativeCore nativeCore = new NativeCore()) {
            JavaCore java = new JavaCore();
            for (String line : Files.readAllLines(events)) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) {
                    throw new IllegalArgumentException("invalid normalized capture event");
                }
                long eventCycle = Long.parseLong(fields[0]);
                if (eventCycle < cycle || eventCycle >= terminal) {
                    throw new IllegalArgumentException("invalid normalized capture cycle");
                }
                frames += advanceCapture(java, nativeCore, eventCycle - cycle, "capture");
                cycle = eventCycle;
                int port = Integer.parseInt(fields[1]);
                int value = Integer.parseInt(fields[2]);
                java.write(port, value);
                nativeCore.write(port, value);
                eventCount++;
            }
            frames += advanceCapture(java, nativeCore, terminal - cycle, "capture endpoint");
        }
        System.out.printf("{\"terminal_ym_cycle\":%d,\"ym_events\":%d," +
                "\"java_c_pcm_frames\":%d,\"java_c_pcm_mismatches\":0}%n",
                terminal, eventCount, frames);
    }

    private static void register(JavaCore java, NativeCore nativeCore,
            int part, int address, int value) {
        java.write(part * 2, address);
        nativeCore.write(part * 2, address);
        compareClock(java, nativeCore, 24, "address");
        java.write(part * 2 + 1, value);
        nativeCore.write(part * 2 + 1, value);
        compareClock(java, nativeCore, 24, "data");
    }

    private static void program(JavaCore java, NativeCore nativeCore) {
        register(java, nativeCore, 0, 0x22, 0x08);
        register(java, nativeCore, 0, 0x27, 0x00);
        register(java, nativeCore, 0, 0x2b, 0x80);
        for (int part = 0; part < 2; part++) {
            for (int channel = 0; channel < 3; channel++) {
                for (int operator = 0; operator < 4; operator++) {
                    int offset = channel + operator * 4;
                    register(java, nativeCore, part, 0x30 + offset, 0x71 + operator);
                    register(java, nativeCore, part, 0x40 + offset, operator < 2 ? 0x23 : 0x10);
                    register(java, nativeCore, part, 0x50 + offset, 0x5f);
                    register(java, nativeCore, part, 0x60 + offset, 0x80);
                    register(java, nativeCore, part, 0x70 + offset, 0x00);
                    register(java, nativeCore, part, 0x80 + offset, 0x2a);
                    register(java, nativeCore, part, 0x90 + offset, 0x00);
                }
                register(java, nativeCore, part, 0xb0 + channel, 0x34);
                register(java, nativeCore, part, 0xb4 + channel, 0xf3);
                register(java, nativeCore, part, 0xa4 + channel, 0x22 + channel);
                register(java, nativeCore, part, 0xa0 + channel, 0x69 + channel * 7);
            }
        }
        for (int channel = 0; channel < 6; channel++) {
            register(java, nativeCore, 0, 0x28,
                    0xf0 | (channel < 3 ? channel : channel + 1));
        }
    }

    private static int[] clockChunked(NativeCore core, int cycles) {
        int[] chunkSizes = {1, 23, 24, 1_024};
        List<Integer> samples = new ArrayList<>();
        int done = 0;
        int chunk = 0;
        while (done < cycles) {
            int count = Math.min(chunkSizes[chunk++ % chunkSizes.length], cycles - done);
            for (int sample : core.clock(count)) {
                samples.add(sample);
            }
            done += count;
        }
        return samples.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int changedFrames(int[] first, int[] second) {
        if (first.length != second.length) {
            throw new AssertionError("control lengths differ");
        }
        int changed = 0;
        for (int index = 0; index < first.length; index += 2) {
            if (first[index] != second[index] || first[index + 1] != second[index + 1]) {
                changed++;
            }
        }
        return changed;
    }

    static void requireRejectionControls(boolean invalidCapacity,
            boolean invalidSnapshot, boolean useAfterClose) {
        if (!invalidCapacity) {
            throw new AssertionError("invalid capacity rejection did not preserve state");
        }
        if (!invalidSnapshot) {
            throw new AssertionError("invalid snapshot rejection did not preserve state");
        }
        if (!useAfterClose) {
            throw new AssertionError("use after close was not rejected");
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 && (arguments.length != 4
                || !arguments[1].equals("--capture-events"))) {
            throw new IllegalArgumentException("usage: JniNukedProof <absolute-library-path> " +
                    "[--capture-events <normalized-tsv> <terminal-cycle>]");
        }
        System.load(arguments[0]);
        if (arguments.length == 4) {
            replayCapture(arguments);
            return;
        }
        int pcmFrames = 512;
        int snapshotBytes;
        int changed;
        boolean invalidCapacity = false;
        boolean invalidSnapshot = false;
        boolean useAfterClose = false;
        try (NativeCore nativeCore = new NativeCore()) {
            JavaCore java = new JavaCore();
            program(java, nativeCore);
            compareClock(java, nativeCore, pcmFrames * 24, "sustained PCM");

            byte[] programmed = nativeCore.snapshot();
            try (NativeCore contiguous = new NativeCore(); NativeCore chunked = new NativeCore()) {
                contiguous.restore(programmed);
                chunked.restore(programmed);
                int[] expected = contiguous.clock(2_048);
                int[] actual = clockChunked(chunked, 2_048);
                if (!Arrays.equals(expected, actual)) {
                    throw new AssertionError("chunking changed PCM");
                }
            }

            nativeCore.clock(7);
            byte[] partial = nativeCore.snapshot();
            snapshotBytes = partial.length;
            int[] expectedAfterSnapshot = nativeCore.clock(257);
            try (NativeCore restored = new NativeCore()) {
                restored.restore(partial);
                if (!Arrays.equals(expectedAfterSnapshot, restored.clock(257))) {
                    throw new AssertionError("fresh-handle snapshot replay mismatch");
                }
            }

            try (NativeCore control = new NativeCore(); NativeCore keyOff = new NativeCore()) {
                control.restore(partial);
                keyOff.restore(partial);
                control.write(0, 0x28);
                keyOff.write(0, 0x28);
                control.clock(24);
                keyOff.clock(24);
                control.write(1, 0xf0);
                keyOff.write(1, 0x00);
                control.clock(24);
                keyOff.clock(24);
                changed = changedFrames(control.clock(128 * 24), keyOff.clock(128 * 24));
            }
            if (changed == 0) {
                throw new AssertionError("key-off negative control was inert");
            }

            byte[] beforeInvalid = nativeCore.snapshot();
            try {
                nativeCore.clockInto(24, new int[1], 0);
            } catch (IllegalArgumentException expected) {
                invalidCapacity = Arrays.equals(beforeInvalid, nativeCore.snapshot());
            }
            byte[] corrupt = beforeInvalid.clone();
            corrupt[0] ^= 1;
            try {
                nativeCore.restore(corrupt);
            } catch (IllegalArgumentException expected) {
                invalidSnapshot = Arrays.equals(beforeInvalid, nativeCore.snapshot());
            }
        }

        NativeCore closed = new NativeCore();
        closed.close();
        closed.close();
        try {
            closed.snapshot();
        } catch (IllegalStateException expected) {
            useAfterClose = true;
        }

        requireRejectionControls(invalidCapacity, invalidSnapshot, useAfterClose);

        System.out.printf("{\"schema\":\"openggf.fm-core-jni-proof.v1\"," +
                        "\"timing_collected\":false,\"java_c_pcm_frames\":%d," +
                        "\"java_c_pcm_mismatches\":0,\"chunking_mismatches\":0," +
                        "\"snapshot_replay_mismatches\":0," +
                        "\"snapshot_restored_into_fresh_handle\":true," +
                        "\"partial_frame_snapshot_cycle\":7,\"snapshot_bytes\":%d," +
                        "\"negative_control_changed_frames\":%d," +
                        "\"invalid_capacity_rejected_before_mutation\":%s," +
                        "\"invalid_snapshot_rejected_before_mutation\":%s," +
                        "\"use_after_close_rejected\":%s,\"double_close_safe\":true," +
                        "\"relocated_absolute_load\":true," +
                        "\"native_snapshot_portability\":\"same-library-build-only\"}%n",
                pcmFrames, snapshotBytes, changed,
                invalidCapacity, invalidSnapshot, useAfterClose);
    }
}
