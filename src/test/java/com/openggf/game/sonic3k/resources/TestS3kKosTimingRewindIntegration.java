package com.openggf.game.sonic3k.resources;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingReplaySnapshot;
import com.openggf.trace.timing.HardwareTimingSchedule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kKosTimingRewindIntegration {
    private static final Path HCZ_COMPLETE = Path.of(
            "src/test/resources/traces/s3k/hcz_completerun");

    @Test
    void hczRecordedCompletionRewindsBeforeOnAndAfterAdmission()
            throws Exception {
        TraceData trace = TraceData.load(HCZ_COMPLETE);
        HardwareCompletionEdge edge =
                trace.hardwareTimingSchedule().edges().stream()
                        .filter(candidate -> candidate.kind()
                                == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                        .findFirst()
                        .orElseThrow();
        HardwareTimingService timing = new HardwareTimingService();
        var recorded = timing.beginRecordedAdmission();
        HardwareTimingReplayPort replay = new HardwareTimingReplayPort(
                recorded);
        S3kKosDecompressionQueue direct =
                new S3kKosDecompressionQueue(timing);
        S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
        HardwareWorkHandle handle = queue.queue(
                TestEnvironment.currentRom(),
                Sonic3kConstants.ART_KOSM_HCZ_BLASTOID_ADDR,
                Sonic3kConstants.ARTTILE_HCZ_BLASTOID_JAWZ);
        HardwareCompletionEdge recordedEdge = new HardwareCompletionEdge(
                edge.rawFrame(),
                edge.boundary(),
                HardwareWorkKind.KOS_MODULE_QUEUE,
                handle.ordinal(),
                handle.submissionFingerprint());
        replay.install(new HardwareTimingSchedule(java.util.List.of(recordedEdge)));

        replay.beginRawFrame(recordedEdge.rawFrame());
        // The module state step is the previous LevelLoop iteration's tail call
        // (sonic3k.asm:7908), ahead of Process_Kos_Queue (7887).
        queue.beforeTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
        queue.processModuleQueueAfterObjects();
        for (int servicePass = 0;
                servicePass < 100_000 && !isPrepared(timing);
                servicePass++) {
            queue.prepareQueuedModuleBeforeVSync();
            admitPreparedDirectChildren(timing, recorded);
            queue.processModuleQueueAfterObjects();
        }
        assertTrue(isPrepared(timing));
        assertFalse(queue.isReady(handle));

        HardwareTimingSnapshot serviceBefore = timing.capture();
        HardwareTimingReplaySnapshot replayBefore = replay.capture();
        replay.apply(recordedEdge.boundary());
        HardwareTimingSnapshot serviceOn = timing.capture();
        HardwareTimingReplaySnapshot replayOn = replay.capture();
        assertTrue(queue.isReady(handle));
        byte[] expected = queue.claim(handle);
        HardwareTimingSnapshot serviceAfter = timing.capture();
        HardwareTimingReplaySnapshot replayAfter = replay.capture();

        timing.restore(serviceBefore);
        replay.restore(replayBefore);
        replay.apply(recordedEdge.boundary());
        assertArrayEquals(expected, queue.claim(handle),
                "rewinding before the edge must reproduce output admission");

        timing.restore(serviceOn);
        replay.restore(replayOn);
        assertTrue(queue.isReady(handle));
        assertArrayEquals(expected, queue.claim(handle),
                "rewinding on the edge must preserve one available claim");

        timing.restore(serviceAfter);
        replay.restore(replayAfter);
        assertFalse(timing.isPending(handle));
        assertThrows(IllegalStateException.class, () -> queue.claim(handle),
                "rewinding after the consumer claim must not duplicate output");
        HardwareWorkHandle next = queue.queue(
                TestEnvironment.currentRom(),
                Sonic3kConstants.ART_KOSM_HCZ_JAWZ_ADDR,
                Sonic3kConstants.ARTTILE_HCZ_BLASTOID_JAWZ);
        assertEquals(recordedEdge.ordinal() + 1, next.ordinal(),
                "rewinding after completion must preserve the global ledger");
    }

    private static boolean isPrepared(HardwareTimingService timing) {
        return timing.capture().jobs().getFirst().preparedPayload() != null;
    }

    private static void admitPreparedDirectChildren(
            HardwareTimingService timing,
            com.openggf.game.timing.RecordedCompletionAuthority authority) {
        for (HardwareTimingJob.Snapshot job : timing.capture().jobs()) {
            if (job.kind() != HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                    || job.preparedPayload() == null
                    || job.ready()) {
                continue;
            }
            authority.admitRecordedCompletion(
                    HardwareServiceBoundary.PRE_MAIN_LOOP,
                    job.handle().kind(),
                    job.handle().ordinal(),
                    job.handle().submissionFingerprint());
        }
    }
}
