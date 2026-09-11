package com.openggf.tools;

import com.openggf.tools.RomArtIntakeTool.ArtEntryKind;
import com.openggf.tools.RomArtIntakeTool.RomHalf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RomArtIntakeTool} pure decision logic. No ROM, no disassembly I/O required.
 */
class TestRomArtIntakeTool {

    // ---- S3 standalone address rejection -------------------------------

    @Test
    void addressAtOrAboveS3LimitIsStandalone() {
        assertEquals(RomHalf.S3_STANDALONE, RomArtIntakeTool.halfForAddress(0x200000L));
        assertEquals(RomHalf.S3_STANDALONE, RomArtIntakeTool.halfForAddress(0x382624L));
        assertTrue(RomArtIntakeTool.isS3StandaloneAddress(0x200000L));
        assertTrue(RomArtIntakeTool.isS3StandaloneAddress(0x2A1234L));
    }

    @Test
    void addressBelowS3LimitIsSkHalf() {
        assertEquals(RomHalf.SK, RomArtIntakeTool.halfForAddress(0x1FFFFFL));
        assertEquals(RomHalf.SK, RomArtIntakeTool.halfForAddress(0x146620L));
        assertFalse(RomArtIntakeTool.isS3StandaloneAddress(0x146620L));
        assertFalse(RomArtIntakeTool.isS3StandaloneAddress(0L));
    }

    @Test
    void negativeAddressIsUnknownNotRejected() {
        assertEquals(RomHalf.UNKNOWN, RomArtIntakeTool.halfForAddress(-1L));
        assertFalse(RomArtIntakeTool.isS3StandaloneAddress(-1L));
    }

    // ---- s3.asm vs sonic3k.asm source detection ------------------------

    @Test
    void s3StandaloneSourceIsRejected() {
        assertEquals(RomHalf.S3_STANDALONE, RomArtIntakeTool.halfForSource("s3.asm"));
        assertTrue(RomArtIntakeTool.isS3StandaloneSource("s3.asm"));
        assertTrue(RomArtIntakeTool.isS3StandaloneSource("some/dir/s3.asm"));
    }

    @Test
    void sonic3kSourceIsAccepted() {
        assertEquals(RomHalf.SK, RomArtIntakeTool.halfForSource("sonic3k.asm"));
        assertEquals(RomHalf.SK, RomArtIntakeTool.halfForSource("Mappings/sonic3k.asm"));
        assertFalse(RomArtIntakeTool.isS3StandaloneSource("sonic3k.asm"));
    }

    @Test
    void s3MarkerDoesNotFalseMatchSonic3k() {
        // "sonic3k.asm" contains neither "/s3.asm" nor equals "s3.asm" — must be SK, not standalone.
        assertFalse(RomArtIntakeTool.isS3StandaloneSource("sonic3k.asm"));
    }

    @Test
    void unknownSourceIsNeitherAcceptedNorRejected() {
        assertEquals(RomHalf.UNKNOWN, RomArtIntakeTool.halfForSource(null));
        assertEquals(RomHalf.UNKNOWN, RomArtIntakeTool.halfForSource(""));
        assertEquals(RomHalf.UNKNOWN, RomArtIntakeTool.halfForSource("Mappings - Misc.asm"));
        assertFalse(RomArtIntakeTool.isS3StandaloneSource("Mappings - Misc.asm"));
    }

    // ---- combined acceptability gate -----------------------------------

    @Test
    void skSourceBelowLimitIsAcceptableForRuntime() {
        assertTrue(RomArtIntakeTool.isAcceptableForS3kRuntime(0x146620L, "sonic3k.asm"));
        // S&K-side source alone (unknown addr) is enough.
        assertTrue(RomArtIntakeTool.isAcceptableForS3kRuntime(-1L, "sonic3k.asm"));
        // S&K-side address alone (unknown source) is enough.
        assertTrue(RomArtIntakeTool.isAcceptableForS3kRuntime(0x146620L, null));
    }

    @Test
    void s3StandaloneAddressOrSourceIsNotAcceptable() {
        assertFalse(RomArtIntakeTool.isAcceptableForS3kRuntime(0x382624L, "sonic3k.asm"));
        assertFalse(RomArtIntakeTool.isAcceptableForS3kRuntime(0x146620L, "s3.asm"));
        assertFalse(RomArtIntakeTool.isAcceptableForS3kRuntime(0x382624L, "s3.asm"));
    }

    @Test
    void fullyUnknownIsNotAcceptable() {
        // No positive S&K signal at all.
        assertFalse(RomArtIntakeTool.isAcceptableForS3kRuntime(-1L, null));
        assertFalse(RomArtIntakeTool.isAcceptableForS3kRuntime(-1L, "Mappings - Misc.asm"));
    }

    // ---- StandaloneArtEntry vs LevelArtEntry recommendation ------------

    @Test
    void artLabelsRecommendStandalone() {
        assertEquals(ArtEntryKind.STANDALONE, RomArtIntakeTool.recommendArtEntryKind("ArtNem_AIZSwingVine"));
        assertEquals(ArtEntryKind.STANDALONE, RomArtIntakeTool.recommendArtEntryKind("ArtKos_Foo"));
        assertEquals(ArtEntryKind.STANDALONE, RomArtIntakeTool.recommendArtEntryKind("ArtKosM_AIZ_Primary"));
        assertEquals(ArtEntryKind.STANDALONE, RomArtIntakeTool.recommendArtEntryKind("ArtUnc_SSEntryRing"));
    }

    @Test
    void mappingAndBlockLabelsRecommendLevel() {
        assertEquals(ArtEntryKind.LEVEL, RomArtIntakeTool.recommendArtEntryKind("Map_MHZPollen"));
        assertEquals(ArtEntryKind.LEVEL, RomArtIntakeTool.recommendArtEntryKind("MapUnc_Spikes"));
        assertEquals(ArtEntryKind.LEVEL, RomArtIntakeTool.recommendArtEntryKind("Blk16_AIZ"));
    }

    @Test
    void unrecognizedLabelIsUnknownKind() {
        assertEquals(ArtEntryKind.UNKNOWN, RomArtIntakeTool.recommendArtEntryKind("SomethingElse"));
        assertEquals(ArtEntryKind.UNKNOWN, RomArtIntakeTool.recommendArtEntryKind(""));
        assertEquals(ArtEntryKind.UNKNOWN, RomArtIntakeTool.recommendArtEntryKind(null));
    }

    // ---- constant-name suggestion format -------------------------------

    @Test
    void constantNameFromArtLabel() {
        assertEquals("ART_NEM_AIZ_SWING_VINE_ADDR",
                RomArtIntakeTool.suggestConstantName("ArtNem_AIZSwingVine"));
    }

    @Test
    void constantNameFromMappingLabel() {
        assertEquals("MAP_MHZ_POLLEN_ADDR", RomArtIntakeTool.suggestConstantName("Map_MHZPollen"));
    }

    @Test
    void constantNameKeepsAcronymsAndSplitsDigits() {
        // SS acronym kept intact; "EntryRing" split on camel case.
        assertEquals("DPLC_SS_ENTRY_RING_ADDR", RomArtIntakeTool.suggestConstantName("DPLC_SSEntryRing"));
        // Letter<->digit boundary.
        assertEquals("MAP_AIZ_1_TREE_ADDR", RomArtIntakeTool.suggestConstantName("Map_AIZ1Tree"));
    }

    @Test
    void constantNameEndsWithAddrSuffix() {
        assertTrue(RomArtIntakeTool.suggestConstantName("ArtNem_Foo").endsWith("_ADDR"));
        assertEquals("", RomArtIntakeTool.suggestConstantName(""));
        assertEquals("", RomArtIntakeTool.suggestConstantName(null));
    }
}
