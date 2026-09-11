package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kKosModuleQueue {
    private static final byte[] ABC_KOSM = {
            0x00, 0x03,
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };
    private static final byte[] EMPTY_KOSM = {0x00, 0x00};
    private static final byte[] TWO_MODULE_KOSM = {
            0x10, 0x01,
            0x17, 0x00, 'A', 'B', 'C', 0x00, 0x00, 0x00,
            0, 0, 0, 0, 0, 0, 0, 0,
            0x17, 0x00, 'D', 'E', 'F', 0x00, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    @Test
    void sequentialBatchPreflightsEveryArchiveBeforeSubmitting() throws Exception {
        byte[] fixture = new byte[ABC_KOSM.length + 2];
        System.arraycopy(ABC_KOSM, 0, fixture, 0, ABC_KOSM.length);
        Path romPath = tempDir.resolve("atomic-batch.gen");
        Files.write(romPath, fixture);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosModuleQueue modules = new S3kKosModuleQueue(
                    timing, new S3kKosDecompressionQueue(timing));

            assertThrows(java.io.IOException.class, () ->
                    modules.queueSequentialBatch(
                            rom, List.of(0, fixture.length - 1), 0));

            assertEquals(List.of(), timing.pendingHandles(),
                    "a bad second archive must not leave the first parent submitted");
        }
    }

    @Test
    void deferredFreshBatchRetainsRequestUntilTwoQueueSlotsAreAvailable()
            throws Exception {
        byte[] fixture = new byte[ABC_KOSM.length * 5];
        for (int index = 0; index < 5; index++) {
            System.arraycopy(ABC_KOSM, 0, fixture,
                    index * ABC_KOSM.length, ABC_KOSM.length);
        }
        Path romPath = tempDir.resolve("fresh-capacity.gen");
        Files.write(romPath, fixture);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kRuntimeArtCoordinator coordinator =
                    new S3kRuntimeArtCoordinator(timing);
            for (int index = 0; index < 3; index++) {
                coordinator.moduleQueue().queue(
                        rom, index * ABC_KOSM.length, index);
            }
            coordinator.deferFreshLevelRuntimeArt(
                    rom, 3 * ABC_KOSM.length, 4 * ABC_KOSM.length);

            coordinator.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);

            assertEquals(3, timing.pendingHandles().size());
            assertEquals(3 * ABC_KOSM.length,
                    coordinator.capture().deferredPrimarySource());
        }
    }

    @Test
    void replacingAndRewindingDeferredFreshBatchReplaysExactOrdinals()
            throws Exception {
        byte[] fixture = new byte[ABC_KOSM.length * 3];
        for (int index = 0; index < 3; index++) {
            System.arraycopy(ABC_KOSM, 0, fixture,
                    index * ABC_KOSM.length, ABC_KOSM.length);
        }
        Path romPath = tempDir.resolve("fresh-rewind.gen");
        Files.write(romPath, fixture);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kRuntimeArtCoordinator coordinator =
                    new S3kRuntimeArtCoordinator(timing);
            coordinator.deferFreshLevelRuntimeArt(rom, 0, ABC_KOSM.length);
            coordinator.deferFreshLevelRuntimeArt(
                    rom, ABC_KOSM.length, 2 * ABC_KOSM.length);
            HardwareTimingSnapshot timingBefore = timing.capture();
            S3kRuntimeArtCoordinator.Snapshot coordinatorBefore = coordinator.capture();

            coordinator.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            List<HardwareWorkHandle> first =
                    coordinator.capture().freshLevelHandoffHandles();

            timing.restore(timingBefore);
            coordinator.restore(coordinatorBefore);
            coordinator.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);

            assertEquals(first,
                    coordinator.capture().freshLevelHandoffHandles());
            assertEquals(List.of(1 * ABC_KOSM.length, 2 * ABC_KOSM.length),
                    timing.capture().jobs().stream()
                            .map(HardwareTimingJob.Snapshot::romSourceAddress)
                            .toList());
        }
    }

    @Test
    void transitionPreflightRejectsInvalidBatchBeforeSubmittingAnyWork() throws Exception {
        byte[] fixture = {
                0x17, 0x00, 'A', 'B', 'C', 0x00, 0x00, 0x00,
                0x17, 0x00, 'D', 'E', 'F', 0x00, 0x00, 0x00
        };
        Path romPath = tempDir.resolve("invalid-transition.gen");
        Files.write(romPath, fixture);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue modules = new S3kKosModuleQueue(timing, direct);

            assertThrows(java.io.IOException.class,
                    () -> S3kKosTransitionPreflight.validate(
                            rom, direct, modules, 0, 8, fixture.length));

            assertEquals(0, timing.pendingHandles().size());
            assertFalse(direct.decompressionsPending());
            assertFalse(modules.modulesLeft());
        }
    }

    @Test
    void modulesLeftRemainsGlobalAfterEarlierParentIsReady() throws Exception {
        byte[] fixture = new byte[ABC_KOSM.length * 2];
        System.arraycopy(ABC_KOSM, 0, fixture, 0, ABC_KOSM.length);
        System.arraycopy(ABC_KOSM, 0, fixture, ABC_KOSM.length, ABC_KOSM.length);
        Path romPath = tempDir.resolve("overlap.gen");
        Files.write(romPath, fixture);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue modules = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle first = modules.queue(rom, 0, 0x500);
            HardwareWorkHandle later = modules.queue(
                    rom, ABC_KOSM.length, 0x510);

            for (int frame = 0; frame < 32 && !modules.isReady(first); frame++) {
                modules.prepareQueuedModuleBeforeVSync();
                romFrameModuleStep(modules);
            }

            assertTrue(modules.isReady(first));
            assertFalse(modules.isReady(later));
            assertTrue(modules.modulesLeft(),
                    "Kos_modules_left remains nonzero for a later parent");

            while (modules.modulesLeft()) {
                modules.prepareQueuedModuleBeforeVSync();
                romFrameModuleStep(modules);
            }
            assertTrue(modules.isReady(later));
        }
    }

    @Test
    void submitsCanonicalArchiveSpanAndPublishesAfterPostObjectsCapture() throws Exception {
        Path romPath = tempDir.resolve("fixture.gen");
        Files.write(romPath, ABC_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);

            HardwareWorkHandle handle = queue.queue(rom, 0, 0x500);
            S3kKosModuleDescriptor descriptor = queue.descriptor(handle);

            assertEquals(ABC_KOSM.length, descriptor.compressedLength());
            assertEquals(3, descriptor.destinationLength());
            assertEquals(1, descriptor.moduleCount());
            assertEquals(0x500 * 32, descriptor.destinationAddress());
            assertFalse(queue.isReady(handle));

            for (int frame = 0; frame < 16 && !queue.isReady(handle); frame++) {
                queue.prepareQueuedModuleBeforeVSync();
                assertFalse(queue.isReady(handle),
                        "pre-VSync preparation must not publish readiness");
                romFrameModuleStep(queue);
            }

            assertTrue(queue.isReady(handle));
            assertFalse(queue.modulesLeft());
            assertArrayEquals(new byte[] {'A', 'B', 'C'}, queue.claim(handle));
        }
    }

    @Test
    void diagnosticsKeepModuleParentsSeparateFromDirectChildren()
            throws Exception {
        Path romPath = tempDir.resolve("diagnostics.gen");
        Files.write(romPath, TWO_MODULE_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct =
                    new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue modules =
                    new S3kKosModuleQueue(timing, direct);
            modules.queue(rom, 0, 0x500);
            romFrameModuleStep(modules);

            QueueDiagnosticSnapshot snapshot = modules.captureDiagnostics(
                    java.util.List.of());
            assertEquals(QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                    snapshot.kind());
            assertEquals(-1, snapshot.activeSource());
            assertEquals(-1, snapshot.activeTotalWork());
            assertEquals(2, snapshot.activeRemainingWork());
            assertEquals(-1, snapshot.activeDestination());
            assertEquals(1, direct.physicalQueueSize(),
                    "active child belongs only to the direct queue");
            assertFalse(direct.captureDiagnostics(java.util.List.of()).prepared(),
                    "a KosM child queued by the module step is not armed until the direct FIFO is serviced");
        }
    }

    @Test
    void heldTailStillPublishesLaterChildOfActiveParent() throws Exception {
        Path romPath = tempDir.resolve("held-continuation.gen");
        Files.write(romPath, TWO_MODULE_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct =
                    new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue modules =
                    new S3kKosModuleQueue(timing, direct);
            modules.queue(rom, 0, 0x500);
            romFrameModuleStep(modules);
            HardwareWorkHandle firstChild =
                    preparation(timing.capture()).activeChild();

            while (!direct.isReady(firstChild)) {
                modules.prepareQueuedModuleBeforeVSync();
            }
            romFrameModuleStep(modules);
            assertEquals(1, preparation(timing.capture()).completedModules());
            assertNull(preparation(timing.capture()).activeChild());

            modules.deferChildSubmissionForHeldLoopTail();
            romFrameModuleStep(modules);

            assertNotNull(preparation(timing.capture()).activeChild(),
                    "a held boundary cannot re-defer an active parent's next module");
            assertEquals(1, direct.physicalQueueSize());
            assertFalse(direct.captureDiagnostics(List.of()).prepared());

            direct.deferNewHeadPreparationVisibilityForHeldLoopTail();
            direct.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertFalse(direct.captureDiagnostics(List.of()).prepared(),
                    "the held row publishes the child before its direct service is visible");
            direct.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertTrue(direct.captureDiagnostics(List.of()).prepared(),
                    "the held closure exposes the direct service");
        }
    }

    @Test
    void decodesTitleCardArchiveFromVerifiedRom() throws Exception {
        String configured = System.getProperty("s3k.rom.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(configured));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle handle = queue.queue(
                    rom,
                    Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_BASE);
            S3kKosModuleDescriptor descriptor = queue.descriptor(handle);

            for (int frame = 0; frame < 512 && !queue.isReady(handle); frame++) {
                queue.prepareQueuedModuleBeforeVSync();
                romFrameModuleStep(queue);
            }

            assertTrue(queue.isReady(handle));
            byte[] archive = rom.readBytes(
                    descriptor.sourceAddress(),
                    descriptor.compressedLength());
            assertArrayEquals(
                    KosinskiReader.decompressModuled(archive, 0),
                    queue.claim(handle));
        }
    }

    @Test
    void aizIntroPlaneChildUsesRomKosDecompBufferIdentity() throws Exception {
        String configured = System.getProperty("s3k.rom.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(configured));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct =
                    new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);

            queue.queue(
                    rom,
                    Sonic3kConstants.ART_KOSM_AIZ_INTRO_PLANE_ADDR,
                    Sonic3kConstants.ARTTILE_AIZ_INTRO_PLANE);
            romFrameModuleStep(queue);

            var child = timing.capture().jobs().stream()
                    .filter(job -> job.kind()
                            == com.openggf.game.timing.HardwareWorkKind
                            .KOS_DECOMPRESSION_QUEUE)
                    .findFirst()
                    .orElseThrow();
            assertEquals(0x382626, child.romSourceAddress());
            assertEquals(1894, child.compressedLength());
            assertEquals(0xFFFFD000, child.destinationAddress());
            assertEquals(4096, child.destinationLength());
            assertEquals("kosinski", child.compressionVariant());
            assertEquals(1, child.moduleCount());
            assertEquals(
                    "sha256:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b",
                    child.handle().submissionFingerprint());
        }
    }

    @Test
    void explicitMixedPolicyMapAllowsLiveChildrenBeforeRecordedParentAdmission()
            throws Exception {
        String configured = System.getProperty("s3k.rom.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(configured));
            HardwareTimingService timing = new HardwareTimingService();
            var recorded = timing.beginRecordedAdmission(java.util.Map.of(
                    HardwareWorkKind.KOS_MODULE_QUEUE,
                    HardwareReadinessAdmissionPolicy.RECORDED,
                    HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                    HardwareReadinessAdmissionPolicy.LIVE,
                    HardwareWorkKind.NEMESIS_PLC_QUEUE,
                    HardwareReadinessAdmissionPolicy.LIVE));
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle handle = queue.queue(
                    rom,
                    Sonic3kConstants.ART_KOSM_HCZ2_SECONDARY_ADDR,
                    0x11B);

            assertEquals(5, queue.descriptor(handle).moduleCount(),
                    "the archive header pins five service modules");
            romFrameModuleStep(queue);
            for (int module = 1; module <= 5; module++) {
                S3kKosModuleSnapshot submitted = preparation(timing.capture());
                assertNotNull(submitted.activeChild());
                int preparations = 0;
                while (!direct.isReady(submitted.activeChild())) {
                    assertTrue(preparations++ < 4096,
                            "module " + module + "'s direct child must finish decompressing"
                                    + " within a bounded number of preparation steps");
                    queue.prepareQueuedModuleBeforeVSync();
                }
                S3kKosModuleSnapshot beforeRetirement = preparation(timing.capture());
                assertEquals(submitted.activeChild(), beforeRetirement.activeChild());
                assertEquals(module - 1, beforeRetirement.completedModules());
                assertFalse(queue.isReady(handle),
                        "preparation cannot admit recorded readiness");

                romFrameModuleStep(queue);
                assertEquals(module, preparation(timing.capture()).completedModules());
                if (module < 5) {
                    romFrameModuleStep(queue);
                }
            }

            HardwareTimingSnapshot preparedAtFrameFive = timing.capture();
            assertNotNull(preparedAtFrameFive.jobs().getFirst().preparedPayload(),
                    "all five modules must be prepared well before frame 131");
            assertFalse(queue.isReady(handle),
                    "RECORDED mode holds prepared work until its trace edge");

            for (int frame = 6; frame <= 131; frame++) {
                queue.prepareQueuedModuleBeforeVSync();
                romFrameModuleStep(queue);
                assertFalse(queue.isReady(handle),
                        "service cadence cannot admit readiness before the trace edge");
            }
            recorded.admitRecordedCompletion(
                    HardwareServiceBoundary.POST_OBJECTS,
                    handle.kind(),
                    handle.ordinal(),
                    handle.submissionFingerprint());
            assertTrue(queue.isReady(handle));
        }
    }

    @Test
    void stepRetiresOneChildBeforeFollowingStepSubmitsNextModule() throws Exception {
        Path romPath = tempDir.resolve("two-modules.gen");
        Files.write(romPath, TWO_MODULE_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle parent = queue.queue(rom, 0, 0x500);

            romFrameModuleStep(queue);
            S3kKosModuleSnapshot submittedFirst = preparation(timing.capture());
            assertEquals(0, submittedFirst.completedModules());
            assertNotNull(submittedFirst.activeChild());
            assertEquals(1, direct.physicalQueueSize());

            int firstPreparations = 0;
            while (!direct.isReady(submittedFirst.activeChild())) {
                assertTrue(firstPreparations++ < 4096,
                        "the first module's direct child must finish decompressing"
                                + " within a bounded number of preparation steps");
                queue.prepareQueuedModuleBeforeVSync();
            }
            romFrameModuleStep(queue);
            S3kKosModuleSnapshot retiredFirst = preparation(timing.capture());
            assertEquals(1, retiredFirst.completedModules());
            assertEquals(null, retiredFirst.activeChild());
            assertEquals(0, direct.physicalQueueSize());
            assertFalse(queue.isReady(parent));

            romFrameModuleStep(queue);
            S3kKosModuleSnapshot submittedSecond = preparation(timing.capture());
            assertNotNull(submittedSecond.activeChild());
            assertEquals(1, submittedSecond.completedModules());
            assertEquals(1, direct.physicalQueueSize());
        }
    }

    @Test
    void fullDirectFifoDefersModuleChildWithoutThrowing() throws Exception {
        Path romPath = tempDir.resolve("full-direct.gen");
        Files.write(romPath, ABC_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            for (int index = 0; index < 4; index++) {
                direct.queueStandardKos(
                        rom, 2, S3kKosRamDestinations.blockTableOffset(index));
            }
            queue.queue(rom, 0, 0x500);

            romFrameModuleStep(queue);

            assertEquals(4, direct.physicalQueueSize());
            assertEquals(null, preparation(timing.capture()).activeChild());
        }
    }

    @Test
    void ordinaryTailDelaysChildClaimUntilCompleteDirectFifoIsEmpty()
            throws Exception {
        Path romPath = tempDir.resolve("ordinary-tail.gen");
        Files.write(romPath, ABC_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle parent = queue.queue(rom, 0, 0x500);
            romFrameModuleStep(queue);
            HardwareWorkHandle child = preparation(timing.capture()).activeChild();
            HardwareWorkHandle ordinary = direct.queueStandardKos(
                    rom, 2, S3kKosRamDestinations.BLOCK_TABLE);

            int childPreparations = 0;
            while (!direct.isReady(child)) {
                assertTrue(childPreparations++ < 4096,
                        "the module child must finish decompressing within a bounded"
                                + " number of preparation steps");
                queue.prepareQueuedModuleBeforeVSync();
            }
            romFrameModuleStep(queue);
            assertEquals(0, preparation(timing.capture()).completedModules());
            assertFalse(queue.isReady(parent));

            int ordinaryPreparations = 0;
            while (!direct.isReady(ordinary)) {
                assertTrue(ordinaryPreparations++ < 4096,
                        "the ordinary direct entry behind the module child must finish"
                                + " decompressing within a bounded number of preparation steps");
                queue.prepareQueuedModuleBeforeVSync();
            }
            romFrameModuleStep(queue);

            assertTrue(queue.isReady(parent));
            assertEquals(1, timing.capture().jobs().stream()
                    .filter(job -> job.kind()
                            == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                    .count());
            assertEquals(2, timing.capture().jobs().stream()
                    .filter(job -> job.kind()
                            == com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                    .count());
        }
    }

    @Test
    void zeroModuleParentCanOnlyPrepareAtItsModuleStep() throws Exception {
        Path romPath = tempDir.resolve("empty-kosm.gen");
        Files.write(romPath, EMPTY_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            HardwareWorkHandle parent = queue.queue(rom, 0, 0x500);

            timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            direct.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertNull(timing.capture().jobs().getFirst().preparedPayload());
            assertFalse(queue.isReady(parent));

            timing.service(HardwareServiceBoundary.VINT_SERVICE);
            assertNull(timing.capture().jobs().getFirst().preparedPayload());
            assertFalse(queue.isReady(parent));

            romFrameModuleStep(queue);
            assertTrue(queue.isReady(parent));
            assertArrayEquals(new byte[0], queue.claim(parent));
        }
    }

    @Test
    void preparedButNotYetAdmittedParentsDoNotOccupyPhysicalFifoCapacity()
            throws Exception {
        Path romPath = tempDir.resolve("recorded-empty-kosm.gen");
        Files.write(romPath, EMPTY_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            timing.beginRecordedAdmission();
            S3kKosDecompressionQueue direct =
                    new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue =
                    new S3kKosModuleQueue(timing, direct);

            for (int index = 0; index < 4; index++) {
                HardwareWorkHandle parent =
                        queue.queue(rom, 0, 0x500 + index);
                romFrameModuleStep(queue);
                assertFalse(queue.isReady(parent),
                        "recorded admission must retain prepared results");
                assertNotNull(timing.capture().jobs().get(index)
                                .preparedPayload(),
                        "the parent must have left the physical FIFO");
            }

            assertNotNull(queue.queue(rom, 0, 0x504));
        }
    }

    @Test
    void rejectsDirectOwnerFromAnotherTimingLedger() {
        HardwareTimingService parentTiming = new HardwareTimingService();
        HardwareTimingService directTiming = new HardwareTimingService();
        S3kKosDecompressionQueue direct =
                new S3kKosDecompressionQueue(directTiming);

        assertThrows(IllegalArgumentException.class,
                () -> new S3kKosModuleQueue(parentTiming, direct));
    }

    @Test
    void ordinaryHeadWorkDelaysChildAndParentUntilLaterPost() throws Exception {
        Path romPath = tempDir.resolve("ordinary-head.gen");
        Files.write(romPath, ABC_KOSM);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            HardwareTimingService timing = new HardwareTimingService();
            S3kKosDecompressionQueue direct = new S3kKosDecompressionQueue(timing);
            S3kKosModuleQueue queue = new S3kKosModuleQueue(timing, direct);
            for (int index = 0; index < 4; index++) {
                direct.queueStandardKos(
                        rom, 2, S3kKosRamDestinations.blockTableOffset(index));
            }
            HardwareWorkHandle parent = queue.queue(rom, 0, 0x500);

            romFrameModuleStep(queue);
            assertNull(preparation(timing.capture()).activeChild());

            while (direct.physicalQueueSize() == 4) {
                queue.prepareQueuedModuleBeforeVSync();
            }
            romFrameModuleStep(queue);
            HardwareWorkHandle child =
                    preparation(timing.capture()).activeChild();
            assertNotNull(child);
            assertEquals(4, direct.physicalQueueSize(),
                    "the module child must join behind three ordinary entries");

            int queuedChildPreparations = 0;
            while (!direct.isReady(child)) {
                assertTrue(queuedChildPreparations++ < 4096,
                        "the module child queued behind three ordinary entries must finish"
                                + " decompressing within a bounded number of preparation steps");
                queue.prepareQueuedModuleBeforeVSync();
                if (!direct.isReady(child)) {
                    romFrameModuleStep(queue);
                    assertEquals(0,
                            preparation(timing.capture()).completedModules());
                    assertFalse(queue.isReady(parent));
                }
            }
            assertFalse(direct.decompressionsPending());
            assertEquals(0, preparation(timing.capture()).completedModules());
            assertFalse(queue.isReady(parent),
                    "PRE retirement cannot advance the module parent");

            romFrameModuleStep(queue);
            assertTrue(queue.isReady(parent),
                    "the following POST owns the one parent transition");
        }
    }

    /**
     * Drives one ROM module-queue transition: the {@code
     * Process_Kos_Module_Queue} state step the previous {@code LevelLoop}
     * iteration left in its tail (sonic3k.asm:7908), then the {@code
     * POST_OBJECTS} readiness capture. The direct FIFO is deliberately not
     * serviced in between, so a just-submitted child stays observable.
     */
    private static void romFrameModuleStep(S3kKosModuleQueue queue) {
        queue.beforeTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
        queue.processModuleQueueAfterObjects();
    }

    private static S3kKosModuleSnapshot preparation(HardwareTimingSnapshot snapshot) {
        return (S3kKosModuleSnapshot) snapshot.jobs().stream()
                .filter(job -> job.kind()
                        == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                .findFirst()
                .orElseThrow()
                .preparationSnapshot();
    }
}
