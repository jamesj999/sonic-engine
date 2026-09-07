package com.openggf.audio.session;

import com.openggf.audio.synth.ChipWriteObserver;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Ordered queue of diagnostics deferred until a session transaction commits.
 *
 * <p>Chip writes are stored as packed primitives rather than as a record and a
 * {@link Runnable} per write: a DAC stream reports one {@code 0x2A} write per
 * sample byte, so the per-write allocation was the largest steady-state
 * garbage source in whole-game profiles. Other diagnostics remain
 * {@link Runnable}s and keep their position in the same order.
 */
final class SmpsDiagnosticQueue {
    private static final int KIND_RUNNABLE = 0;
    private static final int KIND_YM = 1;
    private static final int KIND_PSG = 2;
    private static final int KIND_YM_BUS = 3;
    private static final int KIND_PSG_BUS = 4;
    private static final int KIND_BOUNDARY = 5;

    private int[] kinds = new int[256];
    /** Clock, tick or cycle for the bus kinds; packed port, register and value for the rest. */
    private long[] clocks = new long[256];
    private int[] words = new int[256];
    private Object[] refs = new Object[256];
    private Object[] refs2 = new Object[256];
    private int count;

    int size() {
        return count;
    }

    boolean isEmpty() {
        return count == 0;
    }

    void clear() {
        Arrays.fill(refs, 0, count, null);
        Arrays.fill(refs2, 0, count, null);
        count = 0;
    }

    /** Drops every entry past {@code size}, in rollback. */
    void truncate(int size) {
        if (size >= count) {
            return;
        }
        Arrays.fill(refs, size, count, null);
        Arrays.fill(refs2, size, count, null);
        count = size;
    }

    void addRunnable(Runnable diagnostic) {
        int index = reserve();
        kinds[index] = KIND_RUNNABLE;
        refs[index] = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    void addYm(int port, int register, int value) {
        int index = reserve();
        kinds[index] = KIND_YM;
        words[index] = ((port & 0xFF) << 16) | ((register & 0xFF) << 8) | (value & 0xFF);
    }

    void addPsg(int value) {
        int index = reserve();
        kinds[index] = KIND_PSG;
        words[index] = value;
    }

    void addYmBus(long cycle, int busPort, int value, ChipWriteObserver.PhysicalWriteOrigin origin) {
        int index = reserve();
        kinds[index] = KIND_YM_BUS;
        clocks[index] = cycle;
        words[index] = ((busPort & 0xFF) << 8) | (value & 0xFF);
        refs[index] = origin;
    }

    void addPsgBus(long tick, int value) {
        int index = reserve();
        kinds[index] = KIND_PSG_BUS;
        clocks[index] = tick;
        words[index] = value;
    }

    void addBoundary(ChipWriteObserver.ChipClockDomain domain, long clock,
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        int index = reserve();
        kinds[index] = KIND_BOUNDARY;
        clocks[index] = clock;
        refs[index] = domain;
        refs2[index] = boundary;
    }

    /**
     * Publishes every entry queued so far, in order, then removes them. Entries
     * queued while publishing (a transaction opened from a callback) stay queued.
     * A failing diagnostic goes to {@code errorSink} and cannot affect the rest.
     */
    void publishAll(ChipWriteObserver observer, Consumer<RuntimeException> errorSink) {
        int published = count;
        for (int index = 0; index < published; index++) {
            try {
                publish(index, observer);
            } catch (RuntimeException failure) {
                try {
                    errorSink.accept(failure);
                } catch (RuntimeException ignored) {
                    // Diagnostic failures cannot influence committed audio state.
                }
            }
        }
        int remaining = count - published;
        if (remaining > 0) {
            System.arraycopy(kinds, published, kinds, 0, remaining);
            System.arraycopy(clocks, published, clocks, 0, remaining);
            System.arraycopy(words, published, words, 0, remaining);
            System.arraycopy(refs, published, refs, 0, remaining);
            System.arraycopy(refs2, published, refs2, 0, remaining);
        }
        Arrays.fill(refs, remaining, count, null);
        Arrays.fill(refs2, remaining, count, null);
        count = remaining;
    }

    private void publish(int index, ChipWriteObserver observer) {
        int word = words[index];
        switch (kinds[index]) {
            case KIND_RUNNABLE -> ((Runnable) refs[index]).run();
            case KIND_YM -> observer.onYm2612Write((word >>> 16) & 0xFF, (word >>> 8) & 0xFF, word & 0xFF);
            case KIND_PSG -> observer.onPsgWrite(word);
            case KIND_YM_BUS -> observer.onYm2612BusWrite(clocks[index], (word >>> 8) & 0xFF, word & 0xFF,
                    (ChipWriteObserver.PhysicalWriteOrigin) refs[index]);
            case KIND_PSG_BUS -> observer.onPsgBusWrite(clocks[index], word);
            case KIND_BOUNDARY -> observer.onPhysicalTimelineBoundary(
                    (ChipWriteObserver.ChipClockDomain) refs[index], clocks[index],
                    (ChipWriteObserver.PhysicalTimelineBoundary) refs2[index]);
            default -> throw new IllegalStateException("unknown diagnostic kind " + kinds[index]);
        }
    }

    private int reserve() {
        if (count == kinds.length) {
            int grown = kinds.length * 2;
            kinds = Arrays.copyOf(kinds, grown);
            clocks = Arrays.copyOf(clocks, grown);
            words = Arrays.copyOf(words, grown);
            refs = Arrays.copyOf(refs, grown);
            refs2 = Arrays.copyOf(refs2, grown);
        }
        return count++;
    }
}
