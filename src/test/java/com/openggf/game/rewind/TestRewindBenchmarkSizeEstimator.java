package com.openggf.game.rewind;

import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.animation.DestinationPlan;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot;
import com.openggf.game.rewind.snapshot.CameraSnapshot;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.rewind.snapshot.SpriteManagerSnapshot;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindBenchmarkSizeEstimator {

    @Test
    void expandedCollisionSchemaPreservesOrderedIdsAndPartialBuildState() {
        ObjectRefId previousFirst = ObjectRefId.layout(4, 0, 10);
        ObjectRefId previousSecond = ObjectRefId.dynamic(5, 0, 11);
        ObjectRefId currentFirst = ObjectRefId.dynamic(6, 0, 12);

        ObjectManagerSnapshot.CollisionResponseState state =
                new ObjectManagerSnapshot.CollisionResponseState(
                        List.of(previousFirst, previousSecond),
                        List.of(currentFirst),
                        true,
                        1,
                        ObjectManagerSnapshot.CollisionBuildStage.DYNAMIC_BUILD_COMPLETE);

        assertEquals(List.of(previousFirst, previousSecond),
                state.previousCollisionObjectIds());
        assertEquals(List.of(currentFirst), state.currentCollisionBuildObjectIds());
        assertTrue(state.usePreviousCollisionList());
        assertEquals(1, state.currentCollisionBuildCursor());
        assertEquals(ObjectManagerSnapshot.CollisionBuildStage.DYNAMIC_BUILD_COMPLETE,
                state.collisionBuildStage());

        ObjectManagerSnapshot.CollisionResponseState legacy =
                new ObjectManagerSnapshot.CollisionResponseState(
                        List.of(previousFirst), List.of(currentFirst), true);
        assertEquals(1, legacy.currentCollisionBuildCursor());
        assertEquals(ObjectManagerSnapshot.CollisionBuildStage.IDLE,
                legacy.collisionBuildStage());
    }

    @Test
    void estimatesSnapshotsWithLiveSpawnReferences() {
        ObjectSpawn spawn = new ObjectSpawn(0x120, 0x280, 0x22, 0, 0, true, 0x280, 7);
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false,
                false, 0, 0,
                0, 0, false, 0,
                false, false,
                32, 3,
                null, null, null);
        ObjectManagerSnapshot snapshot = new ObjectManagerSnapshot(
                new long[] {1L << 12},
                List.of(new ObjectManagerSnapshot.PerSlotEntry(32, spawn, state)),
                123,
                456,
                0,
                33,
                true,
                List.of(new ObjectManagerSnapshot.ChildSpawnEntry(spawn, new int[] {45, 46})),
                List.of(),
                new ObjectManagerSnapshot.PlacementSnapshot(
                        new int[] {7},
                        new long[] {2L},
                        new long[] {4L},
                        new long[] {8L},
                        new long[] {16L},
                        10,
                        0x100,
                        1,
                        true,
                        false,
                        true,
                        80,
                        false,
                        9,
                        2,
                        3,
                        new byte[] {0, 1, 2},
                        List.of(new ObjectManagerSnapshot.SpawnCounterEntry(7, 3))));

        long bytes = RewindBenchmark.estimateStructuralSize(snapshot);

        assertTrue(bytes > 0, "structural estimate should be positive");
    }

    @Test
    void estimatesCompositeSnapshotsBySubsystemContent() {
        CompositeSnapshot snapshot = new CompositeSnapshot(Map.of(
                "camera", new CameraSnapshot(
                        (short) 1, (short) 2,
                        (short) 3, (short) 4,
                        (short) 5, (short) 6,
                        (short) 7, (short) 8,
                        (short) 9, (short) 10,
                        (short) 11, (short) 12,
                        (short) 13, (short) 14,
                        (short) 15,
                        true, 16,
                        false, false, true, false,
                        17, 18, false,
                        (short) 19, (short) 20, (short) 21)));

        long bytes = RewindBenchmark.estimateStructuralSize(snapshot);

        assertTrue(bytes > 0, "composite estimate should include subsystem entries");
    }

    @Test
    void levelSnapshotChargesSharedCowDataAsReferencesOnly() {
        LevelSnapshot snapshot = new LevelSnapshot(
                1L,
                new Block[] {new Block(), new Block()},
                new Chunk[] {new Chunk(), new Chunk(), new Chunk()},
                new byte[64 * 1024],
                42);

        long bytes = RewindBenchmark.estimateStructuralSize(snapshot);

        assertEquals(InitialProcessSpritesLifecycle.NONE,
                snapshot.pendingInitialProcessSpritesLifecycle());
        assertTrue(bytes < 1024,
                "per-keyframe level estimate should not include the shared map payload");
    }

    @Test
    void retainedWindowEstimateAccountsForSharedReferencesAcrossKeyframes() {
        byte[] sharedPayload = new byte[64 * 1024];
        Map<String, Object> first = Map.of("payload", sharedPayload);
        Map<String, Object> second = Map.of("payload", sharedPayload);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

        long firstBytes = RewindBenchmark.estimateStructuralSizeShared(first, seen);
        long secondBytes = RewindBenchmark.estimateStructuralSizeShared(second, seen);

        assertTrue(firstBytes > secondBytes,
                "second retained keyframe should not charge the shared payload again");
    }

    @Test
    void retainedEstimatorDoesNotDoubleCountAlreadySeenReferents() {
        byte[] sharedPayload = new byte[64 * 1024];
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

        long firstBytes = RewindBenchmark.estimateStructuralSizeShared(sharedPayload, seen);
        long repeatedBytes = RewindBenchmark.estimateStructuralSizeShared(sharedPayload, seen);

        assertTrue(firstBytes > 0L);
        assertEquals(0L, repeatedBytes,
                "the owning container already accounts for the repeated reference slot");
    }

    @Test
    void retainedWindowEstimateAccountsForSharedLevelArraysAcrossKeyframes() {
        Block[] sharedBlocks = new Block[] {new Block(), new Block()};
        Chunk[] sharedChunks = new Chunk[] {new Chunk(), new Chunk(), new Chunk()};
        byte[] sharedMap = new byte[64 * 1024];
        LevelSnapshot first = new LevelSnapshot(1L, sharedBlocks, sharedChunks, sharedMap, 42);
        LevelSnapshot second = new LevelSnapshot(1L, sharedBlocks, sharedChunks, sharedMap, 43);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

        long firstBytes = RewindBenchmark.estimateStructuralSizeShared(first, seen);
        long secondBytes = RewindBenchmark.estimateStructuralSizeShared(second, seen);

        assertTrue(firstBytes > secondBytes,
                "second level keyframe should charge shared block/chunk/map references only once");
    }

    @Test
    void compactAnimatedTileSnapshotsChargeSharedLayoutAndOwnedPayloadByIdentity() {
        AnimatedTileChannelGraph graph = animatedTileGraph(2);
        graph.update(new ChannelContext(null, null, null, null, 0, 0, 211));
        AnimatedTileChannelSnapshot first = graph.capture();
        graph.update(new ChannelContext(null, null, null, null, 0, 0, 212));
        AnimatedTileChannelSnapshot second = graph.capture();
        AnimatedTileChannelSnapshot publicWrapper =
                new AnimatedTileChannelSnapshot(first.lastPhaseByChannel());

        assertSame(first.compactLayoutOrNull(), second.compactLayoutOrNull());
        assertNotSame(first.lastPhaseByChannel(), second.lastPhaseByChannel());
        assertSame(first.lastPhaseByChannel(), publicWrapper.lastPhaseByChannel(),
                "the public constructor may safely retain the private immutable compact facade");

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long firstBytes = RewindBenchmark.estimateStructuralSizeShared(first, seen);
        long secondBytes = RewindBenchmark.estimateStructuralSizeShared(second, seen);
        long wrapperBytes = RewindBenchmark.estimateStructuralSizeShared(publicWrapper, seen);

        assertTrue(firstBytes > secondBytes,
                "only the first compact capture should charge the shared layout");
        assertTrue(secondBytes > wrapperBytes,
                "a distinct compact map owns a payload; the wrapper shares an already charged map");
        assertEquals(24L, wrapperBytes,
                "the wrapper should retain only its record and shared map reference");

        AnimatedTileChannelSnapshot external =
                new AnimatedTileChannelSnapshot(Map.of("external", 211));
        assertNull(external.compactLayoutOrNull());
        assertTrue(RewindBenchmark.estimateStructuralSize(external) > 24L,
                "external snapshots must continue through the generic record/map estimator");
    }

    @Test
    void retainedAnimatedTileEstimateUsesCompactLayoutOnceAcrossOneThousandCaptures() {
        AnimatedTileChannelGraph graph = animatedTileGraph(32);
        graph.update(new ChannelContext(null, null, null, null, 0, 0, 211));
        IdentityHashMap<Object, Boolean> compactSeen = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> legacySeen = new IdentityHashMap<>();
        long compactBytes = 0L;
        long legacyBytes = 0L;
        for (int capture = 0; capture < 1_000; capture++) {
            compactBytes += RewindBenchmark.estimateStructuralSizeShared(graph.capture(), compactSeen);
            LinkedHashMap<String, Integer> legacyMap = new LinkedHashMap<>();
            for (AnimatedTileChannel channel : graph.channels()) {
                legacyMap.put(channel.channelId(), 211);
            }
            legacyBytes += RewindBenchmark.estimateStructuralSizeShared(
                    new AnimatedTileChannelSnapshot(legacyMap), legacySeen);
        }

        System.out.printf("animated-channel retained 1000 snapshots legacyEstimate=%d compactEstimate=%d%n",
                legacyBytes, compactBytes);
        assertEquals(2_378_048L, legacyBytes,
                "frozen external Map.copyOf retained estimate must remain directly comparable");
        assertEquals(356_728L, compactBytes,
                "compact retained estimate must charge one shared layout and 1,000 owned payloads");
        assertTrue(compactBytes * 2 < legacyBytes,
                "identity-aware compact estimate should be less than half the external Map.copyOf graph");
    }

    @Test
    void placementSnapshotStoresObjStateAsBytes() {
        var component = java.util.Arrays.stream(ObjectManagerSnapshot.PlacementSnapshot.class.getRecordComponents())
                .filter(c -> c.getName().equals("objState"))
                .findFirst()
                .orElseThrow();

        assertEquals(byte[].class, component.getType());
    }

    @Test
    void spriteManagerSnapshotUsesCompactEntriesInsteadOfMapComponent() {
        boolean hasMapComponent = java.util.Arrays.stream(SpriteManagerSnapshot.class.getRecordComponents())
                .anyMatch(component -> java.util.Map.class.isAssignableFrom(component.getType()));

        assertFalse(hasMapComponent, "sprite manager snapshot should not retain map storage");
        assertEquals(SpriteManagerSnapshot.SpriteEntry[].class,
                SpriteManagerSnapshot.class.getRecordComponents()[1].getType());
    }

    @Test
    void benchmarkKeyframeIntervalComesFromProperty() {
        String oldValue = System.getProperty("openggf.rewind.benchmark.keyframeInterval");
        try {
            System.setProperty("openggf.rewind.benchmark.keyframeInterval", "30");
            assertEquals(30, RewindBenchmark.keyframeInterval());
        } finally {
            if (oldValue == null) {
                System.clearProperty("openggf.rewind.benchmark.keyframeInterval");
            } else {
                System.setProperty("openggf.rewind.benchmark.keyframeInterval", oldValue);
            }
        }
    }

    private static AnimatedTileChannelGraph animatedTileGraph(int channelCount) {
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        java.util.ArrayList<AnimatedTileChannel> channels = new java.util.ArrayList<>(channelCount);
        for (int index = 0; index < channelCount; index++) {
            channels.add(new AnimatedTileChannel(
                    "estimator." + index,
                    () -> true,
                    ChannelContext::frameCounter,
                    DestinationPlan.single(index),
                    AnimatedTileCachePolicy.ALWAYS,
                    context -> { }));
        }
        graph.install(channels);
        return graph;
    }
}
