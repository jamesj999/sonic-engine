package com.openggf.tests;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.sonic3k.Sonic3k;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.Chunk;
import com.openggf.level.LevelConstants;
import com.openggf.level.Pattern;
import com.openggf.level.resources.CompressionType;
import com.openggf.level.resources.DeferredLevelResourceDescriptor;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import com.openggf.level.resources.DeferredLevelResourceLoader;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kIczAct1TransitionHeadless {

    @Test
    void act1OutdoorTransitionReloadsIcz2AndAppliesRomCameraBounds()
            throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 0)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kICZEvents events = manager.getIczEvents();

        sonic.setCentreX((short) 0x6950);
        sonic.setCentreY((short) 0x0700);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x6900);
        camera.setY((short) 0x0600);
        camera.setMinX((short) 0);
        camera.setMaxX((short) 0x7000);
        camera.setMinY((short) -0x0100);
        camera.setMaxY((short) 0x0800);
        camera.setMaxYTarget((short) 0x0800);
        events.forceAct1NormalBackgroundRoutineForTest();

        manager.update();

        assertEquals(0, GameServices.level().getCurrentAct(),
                "ICZ1BGE_Normal queues the Act 2 archives before changing levels");
        var jobs = GameServices.hardwareTiming().capture().jobs();
        var directJobs = jobs.stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .toList();
        var moduleJobs = jobs.stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
        assertEquals(2, directJobs.size(),
                "ICZ1BGE_Normal must queue the secondary 128x128 and 16x16 streams");
        assertEquals(1, moduleJobs.size(),
                "ICZ1BGE_Normal must queue the secondary KosM archive");
        assertTrue(moduleJobs.getFirst().exportableAcrossSegment(),
                "the exact ICZ2 KosM parent must remain exportable across the structural act handoff");
        assertEquals(
                java.util.Set.of(
                        S3kKosRamDestinations.RAM_START + 0x0A00,
                        S3kKosRamDestinations.blockTableOffset(0x0408)),
                directJobs.stream()
                        .map(HardwareTimingJob.Snapshot::destinationAddress)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(0x0122 * 32, moduleJobs.getFirst().destinationAddress());
        ResourceLoader loader =
                new ResourceLoader(GameServices.rom().getRom());
        byte[] expectedBlocks128x128 = loader.loadSingle(LoadOp.kosinskiBase(
                directJobs.stream()
                        .filter(job -> job.destinationAddress()
                                == S3kKosRamDestinations.RAM_START + 0x0A00)
                        .findFirst().orElseThrow().romSourceAddress()));
        byte[] expectedChunks16x16 = loader.loadSingle(LoadOp.kosinskiBase(
                directJobs.stream()
                        .filter(job -> job.destinationAddress()
                                == S3kKosRamDestinations.blockTableOffset(0x0408))
                        .findFirst().orElseThrow().romSourceAddress()));
        byte[] expectedPatterns8x8 =
                loader.loadSingle(LoadOp.kosinskiMBase(
                        moduleJobs.getFirst().romSourceAddress()));
        assertTrue(expectedBlocks128x128.length >= LevelConstants.BLOCK_SIZE_IN_ROM,
                "queued ICZ2 128x128 payload must contain a whole block; length="
                        + expectedBlocks128x128.length);
        assertTrue(expectedChunks16x16.length >= Chunk.CHUNK_SIZE_IN_ROM,
                "queued ICZ2 16x16 payload must contain a whole chunk; length="
                        + expectedChunks16x16.length);
        assertTrue(expectedPatterns8x8.length > 0,
                "queued ICZ2 KosM payload must not be empty");
        DeferredLevelResourceManifest transitionManifest =
                new DeferredLevelResourceManifest(List.of(
                        new DeferredLevelResourceDescriptor(
                                DeferredLevelResourceDescriptor.Kind
                                        .PATTERNS_8X8,
                                moduleJobs.getFirst().romSourceAddress(),
                                CompressionType.KOSINSKI_MODULED,
                                moduleJobs.getFirst().destinationAddress()),
                        new DeferredLevelResourceDescriptor(
                                DeferredLevelResourceDescriptor.Kind
                                        .CHUNKS_16X16,
                                directJobs.stream()
                                        .filter(job -> job.destinationAddress()
                                                == S3kKosRamDestinations
                                                        .blockTableOffset(0x0408))
                                        .findFirst().orElseThrow()
                                        .romSourceAddress(),
                                CompressionType.KOSINSKI,
                                S3kKosRamDestinations
                                        .blockTableOffset(0x0408)),
                        new DeferredLevelResourceDescriptor(
                                DeferredLevelResourceDescriptor.Kind
                                        .BLOCKS_128X128,
                                directJobs.stream()
                                        .filter(job -> job.destinationAddress()
                                                == S3kKosRamDestinations.RAM_START
                                                        + 0x0A00)
                                        .findFirst().orElseThrow()
                                        .romSourceAddress(),
                                CompressionType.KOSINSKI,
                                S3kKosRamDestinations.RAM_START
                                        + 0x0A00)));
        Sonic3kLevel independentlyDeferredIcz2 =
                (Sonic3kLevel) ((DeferredLevelResourceLoader)
                        new Sonic3k(GameServices.rom().getRom()))
                        .loadLevelWithDeferredResources(
                                0xC0 + 11,
                                transitionManifest.newTracker());
        int blockStart =
                0x0A00 / LevelConstants.BLOCK_SIZE_IN_ROM;
        int blockCount = expectedBlocks128x128.length
                / LevelConstants.BLOCK_SIZE_IN_ROM;
        int chunkStart = 0x0408 / Chunk.CHUNK_SIZE_IN_ROM;
        int chunkCount = expectedChunks16x16.length
                / Chunk.CHUNK_SIZE_IN_ROM;
        int patternStart = 0x0122;
        int patternCount = expectedPatterns8x8.length
                / Pattern.PATTERN_SIZE_IN_ROM;
        int[][] blocksBeforePublication = snapshotBlockRange(
                independentlyDeferredIcz2, blockStart, blockCount);
        int[][] chunksBeforePublication = snapshotChunkRange(
                independentlyDeferredIcz2, chunkStart, chunkCount);
        byte[][] patternsBeforePublication = snapshotPatternRange(
                independentlyDeferredIcz2, patternStart, patternCount);
        int[][] expectedPublishedBlocks = applyBlockPayload(
                blocksBeforePublication, expectedBlocks128x128);
        int[][] expectedPublishedChunks = applyChunkPayload(
                chunksBeforePublication, expectedChunks16x16);
        byte[][] expectedPublishedPatterns =
                decodePatternPayload(expectedPatterns8x8);
        assertFalse(Arrays.deepEquals(
                        blocksBeforePublication, expectedPublishedBlocks),
                "test fixture must distinguish deferred ICZ blocks from their payload");
        assertFalse(Arrays.deepEquals(
                        chunksBeforePublication, expectedPublishedChunks),
                "test fixture must distinguish deferred ICZ chunks from their payload");
        assertFalse(Arrays.deepEquals(
                        patternsBeforePublication,
                        expectedPublishedPatterns),
                "test fixture must distinguish deferred ICZ patterns from their payload");

        service(HardwareServiceBoundary.POST_OBJECTS);
        int frames = 0;
        while (S3kRuntimeArtCoordinator.current().directQueue().decompressionsPending()
                && frames++ < 100_000) {
            service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            manager.update();
            if (S3kRuntimeArtCoordinator.current().directQueue().decompressionsPending()) {
                assertEquals(0, GameServices.level().getCurrentAct(),
                        "ICZ1BGE_Transition must wait on physical direct FIFO occupancy");
                service(HardwareServiceBoundary.POST_OBJECTS);
            }
        }

        assertTrue(events.isAct2TransitionRequested(),
                "ICZ1BGE_Transition must request the ICZ2 reload on the first direct-empty scan "
                        + "(docs/skdisasm/sonic3k.asm:110280-110323)");
        assertEquals(1, GameServices.level().getCurrentAct(),
                "ROM writes Current_zone_and_act=$0501 before Load_Level");
        Sonic3kICZEvents icz2Events = manager.getIczEvents();
        assertNotSame(events, icz2Events,
                "the seamless reload must transfer queue ownership to the new ICZ2 event owner");
        Sonic3kLevel icz2Level =
                (Sonic3kLevel) GameServices.level().getCurrentLevel();
        Sonic3kObjectArtProvider artProvider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        PlcProgressSnapshot heldAdmission = artProvider.capture();
        assertEquals(RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER,
                heldAdmission.runtimeArtAdmissionOwnerKind());
        assertTrue(heldAdmission.runtimeArtAdmissionBound());
        assertFalse(heldAdmission.runtimeArtAdmissionConsumed());
        assertFalse(heldAdmission.kosSubmissionArmed());
        assertEquals(List.of(), heldAdmission.pendingKosOrdinals());
        assertEquals(List.of(),
                heldAdmission.pendingKosModules(),
                "the resource-owner transition must not speculatively admit ICZ enemy art");
        var beforePost = GameServices.hardwareTiming().capture().jobs();
        assertTrue(beforePost.stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                        .filter(job -> job.destinationAddress()
                                != S3kKosRamDestinations.KOS_DECOMP_BUFFER)
                        .noneMatch(HardwareTimingJob.Snapshot::claimed),
                "ICZ2 must retain both direct payloads until the transferred archive publishes");
        assertFalse(beforePost.stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                        .findFirst().orElseThrow().claimed(),
                "the KosM parent cannot publish before the later POST boundary");
        assert2dArrayEquals(
                blocksBeforePublication,
                snapshotBlockRange(icz2Level, blockStart, blockCount),
                "the in-frame reload must preserve every deferred 128x128 byte");
        assert2dArrayEquals(
                chunksBeforePublication,
                snapshotChunkRange(icz2Level, chunkStart, chunkCount),
                "the in-frame reload must preserve every deferred 16x16 byte");
        assert2dArrayEquals(
                patternsBeforePublication,
                snapshotPatternRange(icz2Level, patternStart, patternCount),
                "the in-frame reload must preserve every deferred pattern byte");

        HardwareTimingJob.Snapshot parent;
        int moduleFrames = 0;
        do {
            service(HardwareServiceBoundary.POST_OBJECTS);
            parent = kosModuleParent();
            assertFalse(parent.claimed(),
                    "POST module work remains invisible to the gameplay consumer in the same frame");
            assert2dArrayEquals(
                    blocksBeforePublication,
                    snapshotBlockRange(icz2Level, blockStart, blockCount),
                    "POST must not publish any 128x128 prefix before the owning scan");
            assert2dArrayEquals(
                    chunksBeforePublication,
                    snapshotChunkRange(icz2Level, chunkStart, chunkCount),
                    "POST must not publish any 16x16 prefix before the owning scan");
            assert2dArrayEquals(
                    patternsBeforePublication,
                    snapshotPatternRange(
                            icz2Level, patternStart, patternCount),
                    "POST must not publish any pattern prefix before the owning scan");
            if (!parent.ready()) {
                // Process_Kos_Module_Queue (docs/skdisasm/sonic3k.asm:2750-2752) runs
                // from LevelLoop's tail (7908), reached at the frame top ahead of
                // Process_Kos_Queue (7887), so the parent can retire across this
                // boundary. Re-read it: the object pass that follows a retirement is
                // the owning publication scan, not an intermediate one.
                service(HardwareServiceBoundary.PRE_MAIN_LOOP);
                assert2dArrayEquals(
                        blocksBeforePublication,
                        snapshotBlockRange(
                                icz2Level, blockStart, blockCount),
                        "PRE must preserve every deferred 128x128 byte");
                assert2dArrayEquals(
                        chunksBeforePublication,
                        snapshotChunkRange(
                                icz2Level, chunkStart, chunkCount),
                        "PRE must preserve every deferred 16x16 byte");
                assert2dArrayEquals(
                        patternsBeforePublication,
                        snapshotPatternRange(
                                icz2Level, patternStart, patternCount),
                        "PRE must preserve every deferred pattern byte");
                parent = kosModuleParent();
                if (parent.ready()) {
                    break;
                }
                manager.update();
                assert2dArrayEquals(
                        blocksBeforePublication,
                        snapshotBlockRange(
                                icz2Level, blockStart, blockCount),
                        "intermediate consumer scans must preserve all deferred blocks");
                assert2dArrayEquals(
                        chunksBeforePublication,
                        snapshotChunkRange(
                                icz2Level, chunkStart, chunkCount),
                        "intermediate consumer scans must preserve all deferred chunks");
                assert2dArrayEquals(
                        patternsBeforePublication,
                        snapshotPatternRange(
                                icz2Level, patternStart, patternCount),
                        "intermediate consumer scans must preserve all deferred patterns");
            }
        } while (!parent.ready() && moduleFrames++ < 100_000);
        assertTrue(parent.ready(), "test setup must reach ICZ2 KosM parent retirement");
        manager.update();
        assertTrue(GameServices.hardwareTiming().capture().jobs().stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                        .findFirst().orElseThrow().claimed(),
                "ICZ2 must publish the transferred KosM payload on the following scan");
        assertTrue(GameServices.hardwareTiming().capture().jobs().stream()
                        .filter(job -> job.kind()
                                == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                        .filter(job -> job.destinationAddress()
                                != S3kKosRamDestinations.KOS_DECOMP_BUFFER)
                        .allMatch(HardwareTimingJob.Snapshot::claimed),
                "the same ICZ2 publication scan must claim both direct payloads");
        assert2dArrayEquals(
                expectedPublishedBlocks,
                snapshotBlockRange(icz2Level, blockStart, blockCount),
                "the publication scan must expose every claimed 128x128 byte");
        assertChunkPayloadEquals(
                icz2Level, expectedChunks16x16, chunkStart,
                "the publication scan must expose every claimed 16x16 byte");
        assert2dArrayEquals(
                expectedPublishedPatterns,
                snapshotPatternRange(icz2Level, patternStart, patternCount),
                "the publication scan must expose every claimed pattern byte");
        PlcProgressSnapshot admitted = artProvider.capture();
        assertTrue(admitted.runtimeArtAdmissionConsumed(),
                "successful terrain and art publication consumes the exact lease last");
        assertTrue(admitted.kosSubmissionArmed());
        assertEquals(List.of(), admitted.pendingKosOrdinals(),
                "lease consumption arms the resource owner without fabricating enemy work");
        artProvider.processRuntimeArtQueue();
        assertEquals(List.of(), artProvider.capture().pendingKosOrdinals(),
                "the following provider pump must not submit speculative enemy work");
        byte[] publishedOwnerState =
                ZoneEventSchemaSidecar.capture(icz2Events);
        var providerAfterPublication = artProvider.capture();
        int jobsAfterPublication =
                GameServices.hardwareTiming().capture().jobs().size();
        Sonic3kICZEvents restoredPublishedOwner = new Sonic3kICZEvents();
        restoredPublishedOwner.init(1);
        ZoneEventSchemaSidecar.restore(
                restoredPublishedOwner, publishedOwnerState);
        restoredPublishedOwner.update(1, 2);
        assertEquals(providerAfterPublication, artProvider.capture(),
                "restoring after successful publication cannot consume or submit twice");
        assertEquals(jobsAfterPublication,
                GameServices.hardwareTiming().capture().jobs().size(),
                "restoring the successful fence cannot duplicate queue work");
        int[][] chunksAfterPublication =
                snapshotChunkRange(icz2Level, chunkStart, chunkCount);
        manager.update();
        assertTrue(Arrays.deepEquals(
                        expectedPublishedBlocks,
                        snapshotBlockRange(
                                icz2Level, blockStart, blockCount)),
                "later scans must not republish or partially rewrite 128x128 terrain");
        assertTrue(Arrays.deepEquals(
                        chunksAfterPublication,
                        snapshotChunkRange(
                                icz2Level, chunkStart, chunkCount)),
                "later scans must not republish or partially rewrite 16x16 terrain");
        assertTrue(Arrays.deepEquals(
                        expectedPublishedPatterns,
                        snapshotPatternRange(
                                icz2Level, patternStart, patternCount)),
                "later scans must not republish or partially rewrite pattern art");
        assertEquals(0x00D0, sonic.getCentreX() & 0xFFFF,
                "ICZ1BGE_Transition subtracts d0=$6880 from player x_pos");
        assertEquals(0x0800, sonic.getCentreY() & 0xFFFF,
                "ICZ1BGE_Transition subtracts d1=-$100 from player y_pos");
        assertEquals(0x0000, camera.getMinX() & 0xFFFF);
        assertEquals(0x7000, camera.getMaxX() & 0xFFFF);
        assertEquals(0x0000, camera.getMinY() & 0xFFFF);
        assertEquals(0x0B20, camera.getMaxY() & 0xFFFF);
        assertEquals(0x0B20, camera.getMaxYTarget() & 0xFFFF);

        GameServices.level().loadZoneAndAct(
                Sonic3kZoneIds.ZONE_ICZ, 1);
        Sonic3kLevel ordinaryReload =
                (Sonic3kLevel) GameServices.level().getCurrentLevel();
        assertBlockPayloadEquals(
                ordinaryReload, expectedBlocks128x128, blockStart,
                "a later ordinary ICZ2 load must not inherit the transition deferral");
        assertChunkPayloadEquals(
                ordinaryReload, expectedChunks16x16, chunkStart,
                "a later ordinary ICZ2 load must synchronously load 16x16 terrain");
        assertPatternPayloadEquals(
                ordinaryReload, expectedPatterns8x8, patternStart,
                "a later ordinary ICZ2 load must synchronously load its KosM art");
    }

    private static HardwareTimingJob.Snapshot kosModuleParent() {
        return GameServices.hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .findFirst().orElseThrow();
    }

    private static void service(HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(boundary);
    }

    private static int[][] applyBlockPayload(
            int[][] baseline, byte[] payload) {
        assertEquals(0, payload.length % LevelConstants.BLOCK_SIZE_IN_ROM);
        int count = payload.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        assertEquals(count, baseline.length);
        int[][] expected = new int[count][];
        for (int block = 0; block < count; block++) {
            expected[block] = baseline[block].length == 0
                    ? new int[LevelConstants.CHUNKS_PER_BLOCK]
                    : baseline[block].clone();
            int payloadOffset = block * LevelConstants.BLOCK_SIZE_IN_ROM;
            for (int word = 0; word < expected[block].length; word++) {
                int byteOffset = payloadOffset + word * 2;
                expected[block][word] =
                        ((payload[byteOffset] & 0xFF) << 8)
                        | (payload[byteOffset + 1] & 0xFF);
            }
        }
        return expected;
    }

    private static int[][] applyChunkPayload(
            int[][] baseline, byte[] payload) {
        assertEquals(0, payload.length % Chunk.CHUNK_SIZE_IN_ROM);
        int count = payload.length / Chunk.CHUNK_SIZE_IN_ROM;
        assertEquals(count, baseline.length);
        int[][] expected = new int[count][];
        for (int chunk = 0; chunk < count; chunk++) {
            expected[chunk] = baseline[chunk].length == 0
                    ? new int[Chunk.PATTERNS_PER_CHUNK + 2]
                    : baseline[chunk].clone();
            int payloadOffset = chunk * Chunk.CHUNK_SIZE_IN_ROM;
            for (int word = 0; word < Chunk.PATTERNS_PER_CHUNK; word++) {
                int byteOffset = payloadOffset + word * 2;
                expected[chunk][word] =
                        ((payload[byteOffset] & 0xFF) << 8)
                        | (payload[byteOffset + 1] & 0xFF);
            }
        }
        return expected;
    }

    private static byte[][] decodePatternPayload(byte[] payload) {
        assertEquals(0, payload.length % Pattern.PATTERN_SIZE_IN_ROM);
        int count = payload.length / Pattern.PATTERN_SIZE_IN_ROM;
        byte[][] expected = new byte[count][];
        for (int patternIndex = 0; patternIndex < count; patternIndex++) {
            Pattern pattern = new Pattern();
            int start = patternIndex * Pattern.PATTERN_SIZE_IN_ROM;
            pattern.fromSegaFormat(Arrays.copyOfRange(
                    payload, start, start + Pattern.PATTERN_SIZE_IN_ROM));
            byte[] pixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
            int cursor = 0;
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    pixels[cursor++] = pattern.getPixel(x, y);
                }
            }
            expected[patternIndex] = pixels;
        }
        return expected;
    }

    private static void assertBlockPayloadEquals(
            Sonic3kLevel level,
            byte[] payload,
            int startBlock,
            String message) {
        int count = payload.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        int[][] actual = snapshotBlockRange(level, startBlock, count);
        assert2dArrayEquals(
                applyBlockPayload(actual, payload), actual, message);
    }

    private static void assertChunkPayloadEquals(
            Sonic3kLevel level,
            byte[] payload,
            int startChunk,
            String message) {
        int count = payload.length / Chunk.CHUNK_SIZE_IN_ROM;
        int[][] actual = snapshotChunkRange(level, startChunk, count);
        assert2dArrayEquals(
                applyChunkPayload(actual, payload), actual, message);
    }

    private static void assertPatternPayloadEquals(
            Sonic3kLevel level,
            byte[] payload,
            int startPattern,
            String message) {
        assert2dArrayEquals(
                decodePatternPayload(payload),
                snapshotPatternRange(
                        level,
                        startPattern,
                        payload.length / Pattern.PATTERN_SIZE_IN_ROM),
                message);
    }

    private static void assert2dArrayEquals(
            int[][] expected, int[][] actual, String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int i = 0; i < expected.length; i++) {
            if (!Arrays.equals(expected[i], actual[i])) {
                throw new AssertionError(message + " at index " + i);
            }
        }
    }

    private static void assert2dArrayEquals(
            byte[][] expected, byte[][] actual, String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int i = 0; i < expected.length; i++) {
            if (!Arrays.equals(expected[i], actual[i])) {
                throw new AssertionError(message + " at index " + i);
            }
        }
    }

    private static int[][] snapshotBlockRange(
            Sonic3kLevel level, int start, int count) {
        int[][] snapshot = new int[count][];
        for (int i = 0; i < count; i++) {
            int index = start + i;
            snapshot[i] = index < level.getBlockCount()
                    ? level.getBlock(index).saveState()
                    : new int[0];
        }
        return snapshot;
    }

    private static int[][] snapshotChunkRange(
            Sonic3kLevel level, int start, int count) {
        int[][] snapshot = new int[count][];
        for (int i = 0; i < count; i++) {
            int index = start + i;
            snapshot[i] = index < level.getChunkCount()
                    ? level.getChunk(index).saveState()
                    : new int[0];
        }
        return snapshot;
    }

    private static byte[][] snapshotPatternRange(
            Sonic3kLevel level, int start, int count) {
        byte[][] snapshot = new byte[count][];
        for (int i = 0; i < count; i++) {
            int index = start + i;
            if (index >= level.getPatternCount()) {
                snapshot[i] = new byte[0];
                continue;
            }
            Pattern pattern = level.getPattern(index);
            byte[] pixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
            int cursor = 0;
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    pixels[cursor++] = pattern.getPixel(x, y);
                }
            }
            snapshot[i] = pixels;
        }
        return snapshot;
    }
}
