package com.openggf.audio.synth;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestSynthMutationBackup {
    private static volatile Object allocationSink;
    @Test
    void reusableBackupMatchesDurableRestoreAcrossQueueGrowthAndRendering() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        VirtualSynthesizer control = new VirtualSynthesizer();
        VirtualSynthesizer.MutationBackup backup = synth.createMutationBackup();
        short[] actual = new short[2048];
        short[] expected = new short[2048];
        for (int round = 0; round < 12; round++) {
            synth.writePsg(this, 0x80 | round);
            synth.writePsg(this, 0x12);
            synth.writePsg(this, 0x90);
            for (int i = 0; i < round * 80; i++) synth.writeFm(this, 0, 0x22, i & 15);
            VirtualSynthesizer.Snapshot durable = synth.captureSynthSnapshot();
            synth.captureMutation(backup);
            synth.renderFrames(actual, 0, 1024);
            synth.writePsg(this, 0xff);
            synth.restoreMutation(backup);
            assertEquals(durable, synth.captureSynthSnapshot(), "round " + round);
            control.restoreSynthSnapshot(durable);
            control.renderFrames(expected, 0, 1024);
            synth.renderFrames(actual, 0, 1024);
            assertArrayEquals(expected, actual, "round " + round);
            // Reusing the mutable buffer cannot overwrite the durable capture.
            synth.captureMutation(backup);
            control.restoreSynthSnapshot(durable);
            assertEquals(durable, control.captureSynthSnapshot());
        }
    }

    @Test
    void warmedPhysicalBackupsDoNotAllocateSnapshotGraphs() {
        var raw = ManagementFactory.getThreadMXBean();
        assumeTrue(raw instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) raw;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        VirtualSynthesizer synth = new VirtualSynthesizer();
        VirtualSynthesizer.MutationBackup backup = synth.createMutationBackup();
        synth.renderFrames(new short[512], 0, 256);
        for (int i = 0; i < 2000; i++) synth.captureMutation(backup);
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 2000; i++) synth.captureMutation(backup);
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        for (int i = 0; i < 200; i++) allocationSink = synth.captureSynthSnapshot();
        long snapshotBefore = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 2000; i++) allocationSink = synth.captureSynthSnapshot();
        long snapshotAllocated = bean.getThreadAllocatedBytes(thread) - snapshotBefore;
        allocationSink = null;
        System.out.printf("Physical audio backup allocation: reusable=%d B/capture, immutable=%d B/capture%n",
                allocated / 2000, snapshotAllocated / 2000);
        assertTrue(allocated < 64_000, "bytes for 2000 warmed captures: " + allocated);
        assertTrue(snapshotAllocated > allocated * 10,
                "transaction buffers should avoid the immutable snapshot graph");
    }
}
