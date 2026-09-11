package com.openggf.game.sonic3k.resources;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.resources.CompressionType;
import com.openggf.level.resources.DeferredLevelResourceDescriptor;
import com.openggf.level.resources.DeferredLevelResourceManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * ROM LevelLoadBlock-owned identities for resources published asynchronously.
 */
public record Sonic3kDeferredLevelResourceProfile(
        int activeLevelLoadBlockIndex,
        int deferredSourceLevelLoadBlockIndex,
        int secondaryPatternDestination,
        int secondaryChunkDestination,
        int secondaryBlockDestination,
        boolean initiallyDeferred) {

    private static final int ICZ2_LEVEL_LOAD_BLOCK_INDEX = 11;

    public static Sonic3kDeferredLevelResourceProfile forLevelLoadBlock(
            int levelLoadBlockIndex) {
        if (levelLoadBlockIndex == 0) {
            return new Sonic3kDeferredLevelResourceProfile(
                    levelLoadBlockIndex,
                    Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX,
                    0x00BE * 32,
                    S3kKosRamDestinations.blockTableOffset(0x0268),
                    -1,
                    true);
        }
        if (levelLoadBlockIndex == ICZ2_LEVEL_LOAD_BLOCK_INDEX) {
            return new Sonic3kDeferredLevelResourceProfile(
                    levelLoadBlockIndex,
                    levelLoadBlockIndex,
                    0x0122 * 32,
                    S3kKosRamDestinations.blockTableOffset(0x0408),
                    S3kKosRamDestinations.RAM_START + 0x0A00,
                    false);
        }
        return null;
    }

    public DeferredLevelResourceManifest manifest(
            int secondaryArtSource,
            int secondaryChunkSource,
            int secondaryBlockSource) {
        List<DeferredLevelResourceDescriptor> descriptors =
                new ArrayList<>(3);
        if (defersPatterns()) {
            descriptors.add(patternDescriptor(secondaryArtSource));
        }
        if (defersChunks()) {
            descriptors.add(chunkDescriptor(secondaryChunkSource));
        }
        if (defersBlocks()) {
            descriptors.add(blockDescriptor(secondaryBlockSource));
        }
        return new DeferredLevelResourceManifest(descriptors);
    }

    public DeferredLevelResourceDescriptor patternDescriptor(int source) {
        requireDestination("pattern", secondaryPatternDestination);
        return new DeferredLevelResourceDescriptor(
                DeferredLevelResourceDescriptor.Kind.PATTERNS_8X8,
                source,
                CompressionType.KOSINSKI_MODULED,
                secondaryPatternDestination);
    }

    public DeferredLevelResourceDescriptor chunkDescriptor(int source) {
        requireDestination("chunk", secondaryChunkDestination);
        return new DeferredLevelResourceDescriptor(
                DeferredLevelResourceDescriptor.Kind.CHUNKS_16X16,
                source,
                CompressionType.KOSINSKI,
                secondaryChunkDestination);
    }

    public DeferredLevelResourceDescriptor blockDescriptor(int source) {
        requireDestination("block", secondaryBlockDestination);
        return new DeferredLevelResourceDescriptor(
                DeferredLevelResourceDescriptor.Kind.BLOCKS_128X128,
                source,
                CompressionType.KOSINSKI,
                secondaryBlockDestination);
    }

    public boolean defersPatterns() {
        return secondaryPatternDestination != -1;
    }

    public boolean defersChunks() {
        return secondaryChunkDestination != -1;
    }

    public boolean defersBlocks() {
        return secondaryBlockDestination != -1;
    }

    private static void requireDestination(String kind, int destination) {
        if (destination == -1) {
            throw new IllegalStateException(
                    "profile does not defer its secondary " + kind + " resource");
        }
    }
}
