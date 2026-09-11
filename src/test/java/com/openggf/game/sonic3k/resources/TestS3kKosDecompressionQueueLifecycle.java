package com.openggf.game.sonic3k.resources;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.LevelFrameTestStep;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kKosDecompressionQueueLifecycle {
    private static final byte[] ABC_STREAM = {
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void gameplayContextExposesOnlyTheModuleCreatedNeutralCoordinator() {
        GameplayModeContext context = openAttachedContext();
        RuntimeArtCoordinator neutral = context.runtimeArtCoordinator();
        S3kRuntimeArtCoordinator s3k =
                S3kRuntimeArtCoordinator.from(neutral);

        assertSame(neutral, GameServices.runtimeArtCoordinator());
        assertSame(neutral, GameServices.runtimeArtCoordinatorOrNull());
        assertSame(s3k, S3kRuntimeArtCoordinator.from(
                GameServices.runtimeArtCoordinator()));
    }

    @Test
    void rewindRegistryRestoresTimingBeforePhysicalQueueAndReadyPayloadState()
            throws Exception {
        GameplayModeContext context = openAttachedContext();
        S3kKosDecompressionQueue queue = S3kRuntimeArtCoordinator.from(
                context.runtimeArtCoordinator()).directQueue();
        try (Rom rom = romWith(ABC_STREAM)) {
            HardwareWorkHandle handle = queue.queueStandardKos(
                    rom, 0, S3kKosRamDestinations.BLOCK_TABLE);
            runProductionHardwareScan(context);

            CompositeSnapshot checkpoint = context.getRewindRegistry().capture();
            List<String> registrationOrder =
                    new ArrayList<>(checkpoint.entries().keySet());
            int timingIndex = registrationOrder.indexOf(HardwareTimingService.REWIND_KEY);
            int queueIndex = registrationOrder.indexOf(S3kKosDecompressionQueue.REWIND_KEY);
            assertTrue(timingIndex >= 0 && timingIndex < queueIndex,
                    "timing must restore before its dependent physical queue: "
                            + registrationOrder);

            HardwareTimingSnapshot timingCheckpoint = assertInstanceOf(
                    HardwareTimingSnapshot.class,
                    checkpoint.get(HardwareTimingService.REWIND_KEY));
            S3kKosDecompressionSnapshot preparationCheckpoint = assertInstanceOf(
                    S3kKosDecompressionSnapshot.class,
                    timingCheckpoint.jobs().getFirst().preparationSnapshot());
            assertArrayEquals(new byte[] {'A', 'B', 'C'},
                    preparationCheckpoint.decoder().output(),
                    "one production PRE boundary completes one direct child");
            S3kKosDecompressionQueueSnapshot queueCheckpoint = assertInstanceOf(
                    S3kKosDecompressionQueueSnapshot.class,
                    checkpoint.get(S3kKosDecompressionQueue.REWIND_KEY));
            assertEquals(1, queueCheckpoint.entries().size());
            assertFalse(queueCheckpoint.entries().getFirst().physical(),
                    "ready-unclaimed work has retired from the physical FIFO");

            assertTrue(queue.isReady(handle));
            assertArrayEquals(new byte[] {'A', 'B', 'C'}, queue.claim(handle));
            assertEquals(0, queue.physicalQueueSize());

            context.getRewindRegistry().restore(checkpoint);

            assertEquals(0, queue.physicalQueueSize());
            assertFalse(queue.decompressionsPending());
            assertTrue(queue.isReady(handle));
            CompositeSnapshot restored = context.getRewindRegistry().capture();
            HardwareTimingSnapshot restoredTiming = assertInstanceOf(
                    HardwareTimingSnapshot.class,
                    restored.get(HardwareTimingService.REWIND_KEY));
            S3kKosDecompressionSnapshot restoredPreparation = assertInstanceOf(
                    S3kKosDecompressionSnapshot.class,
                    restoredTiming.jobs().getFirst().preparationSnapshot());
            assertEquals(preparationCheckpoint.decoder().readPosition(),
                    restoredPreparation.decoder().readPosition());
            assertEquals(preparationCheckpoint.decoder().descriptorBitsRemaining(),
                    restoredPreparation.decoder().descriptorBitsRemaining());
            assertArrayEquals(preparationCheckpoint.decoder().output(),
                    restoredPreparation.decoder().output());
            assertEquals(queueCheckpoint,
                    restored.get(S3kKosDecompressionQueue.REWIND_KEY));

            assertArrayEquals(new byte[] {'A', 'B', 'C'}, queue.claim(handle));
        }
    }

    @Test
    void sessionCloseWithdrawsFacadeAndFreshContextStartsWithoutLeakedQueueState()
            throws Exception {
        GameplayModeContext firstContext = openAttachedContext();
        S3kKosDecompressionQueue firstQueue =
                S3kRuntimeArtCoordinator.from(
                        GameServices.runtimeArtCoordinator()).directQueue();
        try (Rom rom = romWith(ABC_STREAM)) {
            firstQueue.queueStandardKos(
                    rom, 0, S3kKosRamDestinations.BLOCK_TABLE);
            runProductionHardwareScan(firstContext);
            assertEquals(0, firstQueue.physicalQueueSize(),
                    "one PRE boundary retires one complete direct child");
            assertEquals(1, firstContext.hardwareTiming().pendingHandles().size());

            SessionManager.closeGameplaySession();

            assertEquals(0, firstQueue.physicalQueueSize(),
                    "teardown must reset the retired context's physical owner");
            assertTrue(firstContext.hardwareTiming().pendingHandles().isEmpty(),
                    "teardown must reset the retired context's timing ledger");
            assertNull(GameServices.runtimeArtCoordinatorOrNull());
            assertThrows(IllegalStateException.class,
                    GameServices::runtimeArtCoordinator);

            GameplayModeContext secondContext = openAttachedContext();
            S3kKosDecompressionQueue secondQueue =
                    S3kRuntimeArtCoordinator.from(
                            GameServices.runtimeArtCoordinator()).directQueue();

            assertNotSame(firstContext, secondContext);
            assertNotSame(firstQueue, secondQueue);
            assertSame(S3kRuntimeArtCoordinator.from(
                    secondContext.runtimeArtCoordinator()).directQueue(),
                    secondQueue);
            assertEquals(0, secondQueue.physicalQueueSize());
            assertFalse(secondQueue.decompressionsPending());
            assertTrue(secondContext.hardwareTiming().pendingHandles().isEmpty());
        }
    }

    private static GameplayModeContext openAttachedContext() {
        GameplayModeContext context =
                SessionManager.openGameplaySession(new Sonic3kGameModule());
        GameplaySessionFactory.attachManagers(context, EngineServices.current());
        return context;
    }

    private static void runProductionHardwareScan(GameplayModeContext context) {
        LevelFrameTestStep.executeHardwareTimedObjectScan(
                LevelFrameContext.from(context), () -> {
                });
    }

    private Rom romWith(byte[] bytes) throws Exception {
        Path path = tempDir.resolve("fixture.gen");
        Files.write(path, bytes);
        Rom rom = new Rom();
        assertTrue(rom.open(path.toString()));
        return rom;
    }
}
