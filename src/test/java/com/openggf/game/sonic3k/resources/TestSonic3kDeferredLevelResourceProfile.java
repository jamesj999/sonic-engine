package com.openggf.game.sonic3k.resources;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.resources.DeferredLevelResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kDeferredLevelResourceProfile {

    @Test
    void aizIntroActiveEntryDeclaresDeferredMainLevelResources() {
        Sonic3kDeferredLevelResourceProfile profile =
                Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(0);

        assertEquals(
                Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX,
                profile.deferredSourceLevelLoadBlockIndex());
        assertTrue(profile.initiallyDeferred());
    }

    @Test
    void aizIntroCompositeDefersOnlyMainLevelPatternAndChunkStreams() {
        Sonic3kDeferredLevelResourceProfile profile =
                Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(0);
        var manifest = profile.manifest(0x100, 0x200, 0x300);

        assertTrue(profile.initiallyDeferred());
        assertEquals(
                Set.of(
                        DeferredLevelResourceDescriptor.Kind.PATTERNS_8X8,
                        DeferredLevelResourceDescriptor.Kind.CHUNKS_16X16),
                manifest.descriptors().stream()
                        .map(DeferredLevelResourceDescriptor::kind)
                        .collect(Collectors.toSet()));
    }

    @Test
    void postIntroEntryLoadsItsOwnSecondaryResourcesImmediately() {
        assertNull(Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(
                Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX));
    }

    @Test
    void icz2EntryRequiresAllThreeExactSecondaryStreams() {
        Sonic3kDeferredLevelResourceProfile profile =
                Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(11);
        var manifest = profile.manifest(0x100, 0x200, 0x300);

        assertEquals(3, manifest.descriptors().size());
        assertEquals(Set.of(
                        DeferredLevelResourceDescriptor.Kind.PATTERNS_8X8,
                        DeferredLevelResourceDescriptor.Kind.CHUNKS_16X16,
                        DeferredLevelResourceDescriptor.Kind.BLOCKS_128X128),
                manifest.descriptors().stream()
                        .map(DeferredLevelResourceDescriptor::kind)
                        .collect(Collectors.toSet()));
    }
}
