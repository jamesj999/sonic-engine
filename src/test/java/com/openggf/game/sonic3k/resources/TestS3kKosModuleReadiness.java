package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kKosModuleReadiness {
    private static final byte[] ABC_KOSM = {
            0x00, 0x03,
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    @Test
    void rewindAfterDirectRetirementBeforeParentClaimIsExact() throws Exception {
        Path romPath = tempDir.resolve("fixture.gen");
        Files.write(romPath, ABC_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle handle = queue.queue(rom, 0, 0x500);

            // Process_Kos_Module_Queue (sonic3k.asm:7908) runs immediately after the
            // object pass and hands the head archive's current module to the direct
            // FIFO through Queue_Kos (2741). Process_Kos_Queue (7887) follows it in the
            // same LevelLoop tail and takes that direct entry to completion
            // (2840-2954), so the submitting iteration is also the one that retires it.
            queue.processModuleQueueAfterObjects();
            HardwareWorkHandle child = ((S3kKosModuleSnapshot) timing.capture()
                    .jobs().getFirst().preparationSnapshot()).activeChild();
            for (int frame = 0; !direct.isReady(child); frame++) {
                assertTrue(frame < 16,
                        "the direct FIFO must retire the submitted module within a "
                                + "bounded number of LevelLoop iterations");
                queue.prepareQueuedModuleBeforeVSync();
            }
            HardwareTimingSnapshot snapshot = timing.capture();
            S3kKosDecompressionQueueSnapshot directSnapshot = direct.capture();

            drain(queue, handle);
            byte[] expected = queue.claim(handle);

            timing.restore(snapshot);
            direct.restore(directSnapshot);
            S3kKosModuleQueue restoredQueue =
                    new S3kKosModuleQueue(timing, direct);
            assertFalse(restoredQueue.isReady(handle));
            assertFalse(direct.decompressionsPending());
            assertTrue(direct.isReady(child));
            drain(restoredQueue, handle);

            assertArrayEquals(expected, restoredQueue.claim(handle));
        }
    }

    private static void drain(
            S3kKosModuleQueue queue,
            HardwareWorkHandle handle) {
        for (int frame = 0; frame < 16 && !queue.isReady(handle); frame++) {
            queue.processModuleQueueAfterObjects();
            queue.prepareQueuedModuleBeforeVSync();
        }
        assertTrue(queue.isReady(handle));
    }
}
