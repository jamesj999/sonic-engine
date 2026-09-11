package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.LiveSfx;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.SavedMusic;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.RomPointer;
import com.openggf.tests.RomTestUtils;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestS3kCompleteRunStateDecoder {
    private static final int TRACKS = 0x40;
    private static final int OVERLAP = TRACKS + 9 * 0x30;

    @Test
    void authenticatesTheLockedOnRomAndExposesOnlyOccupiedSourceAssetRanges() throws Exception {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom());

        assertEquals(221, catalog.assets().size());
        assertEquals(0x0e4104L, catalog.assets().get("music.Snd_SKCredits").romBase());
        assertEquals(0x0e5d4bL, catalog.assets().get("music.Snd_SKCredits").romEndExclusive());
        assertEquals(0x2ce48fL, catalog.assets().get("music.Snd_CNZ1").romBase());
        assertEquals(0x2cebf1L, catalog.assets().get("music.Snd_CNZ1").romEndExclusive());
        assertEquals(0x2d1345L, catalog.assets().get("music.Snd_LBZ1").romBase());
        assertEquals(0x2d17a7L, catalog.assets().get("music.Snd_LBZ1").romEndExclusive());
        assertEquals(0x2d8ae8L, catalog.assets().get("music.Snd_GumBonus").romBase());
        assertEquals(0x2d99f7L, catalog.assets().get("music.Snd_GumBonus").romEndExclusive());
        assertEquals(0x2dfabeL, catalog.assets().get("music.Snd_Drown").romBase());
        assertEquals(0x2dfeacL, catalog.assets().get("music.Snd_Drown").romEndExclusive());
        assertEquals(0x0fde30L, catalog.assets().get("sfx.Sound_33").romBase());
        assertEquals(0x0fde5eL, catalog.assets().get("sfx.Sound_33").romEndExclusive());
        assertEquals(0x0ffd94L, catalog.assets().get("sfx.Sound_DB").romBase());
        assertEquals(0x0ffda9L, catalog.assets().get("sfx.Sound_DB").romEndExclusive());
        assertEquals(0x1c00L, catalog.assets().get("z80.driver-data").romEndExclusive());
    }

    @Test
    void resolvesTheUnifiedVoiceTableInsideTheSecondInstalledDriverImage() throws Exception {
        byte[] raw = baseState();
        word(raw, 0x37, 0x17d8);
        track(raw, TRACKS, 0x80, 0x06, 0x8120, 0x30);
        track(raw, TRACKS + 0x30, 0x80, 0x00, 0x81bc, 0x30);
        word(raw, TRACKS + 0x30 + 0x1c, 0x1a45);

        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom());
        var snapshot = S3kCompleteRunStateDecoder.decode(raw, catalog);

        assertEquals("z80.driver-data", snapshot.globals().voiceTablePointer().assetKey());
        assertEquals(0x17d8L, snapshot.globals().voiceTablePointer().pointer());
        assertEquals("z80.driver-data", snapshot.musicTracks().get(1).totalLevelPointer().assetKey());
        assertEquals(0x1a45L, snapshot.musicTracks().get(1).totalLevelPointer().pointer());
    }

    @Test
    void failsClosedAtEveryMusicAndSfxBankPrefixOrTail() throws Exception {
        var catalog = S3kCompleteRunAssetCatalog.load(rom());

        assertEquals("music.Snd_PresSega", catalog.musicPointer(0x1c, 0xfffe).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x1c, 0xffff));
        assertEquals("music.Snd_Emerald", catalog.musicPointer(0x1d, 0xff40).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x1d, 0xff41));
        assertEquals("music.Snd_CNZ1", catalog.musicPointer(0x59, 0xebf0).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x59, 0xebf1));
        assertEquals("music.Snd_LBZ1", catalog.musicPointer(0x5a, 0x97a6).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x5a, 0x97a7));
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x5b, 0x8ae7));
        assertEquals("music.Snd_GumBonus", catalog.musicPointer(0x5b, 0x8ae8).assetKey());
        assertEquals("music.Snd_Drown", catalog.musicPointer(0x5b, 0xfeab).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.musicPointer(0x5b, 0xfeac));
        assertThrows(IllegalArgumentException.class, () -> catalog.sfxPointer(0xde2f));
        assertEquals("sfx.Sound_33", catalog.sfxPointer(0xde30).assetKey());
        assertEquals("sfx.Sound_DB", catalog.sfxPointer(0xfda8).assetKey());
        assertThrows(IllegalArgumentException.class, () -> catalog.sfxPointer(0xfda9));
    }

    @Test
    void decodesLiveMusicAndSfxPointersThroughTheirOwningBanks() throws Exception {
        byte[] raw = baseState();
        track(raw, TRACKS, 0x80, 0x06, 0x8120, 0x30);
        track(raw, OVERLAP, 0x80, 0x02, 0xde3a, 0x30);

        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom());
        var snapshot = S3kCompleteRunStateDecoder.decode(raw, catalog);

        assertEquals("music.Snd_AIZ1", snapshot.musicTracks().getFirst().dataPointer().assetKey());
        assertEquals(0x2c8120L, snapshot.musicTracks().getFirst().dataPointer().pointer());
        LiveSfx overlap = assertInstanceOf(LiveSfx.class, snapshot.overlap());
        assertEquals("sfx.Sound_33", overlap.tracks().getFirst().dataPointer().assetKey());
        assertEquals(0x0fde3aL, overlap.tracks().getFirst().dataPointer().pointer());
        assertFalse(snapshot.musicTracks().get(1).populated());
        S3kCompleteRunAudioProfile.profile().validateState(
                S3kCompleteRunStateNormalizer.normalizeReference(snapshot, catalog.assets()));
    }

    @Test
    void decodesEveryLivePointerUnionFromItsLittleEndianOwningBytes() throws Exception {
        byte[] raw = baseState();
        int fm1 = TRACKS + 0x30;
        track(raw, fm1, 0x80, 0x00, 0x81bc, 0x2e);
        raw[fm1 + 7] = (byte) 0x80;
        word(raw, fm1 + 0x0d, 0x3456);
        raw[fm1 + 0x18] = (byte) 0x80;
        word(raw, fm1 + 0x19, 0x1a13);
        word(raw, fm1 + 0x1c, 0x1a45);
        word(raw, fm1 + 0x20, 0x8200);
        word(raw, fm1 + 0x22, 0x1234);
        for (int offset = 0; offset < 6; offset++) raw[fm1 + 0x28 + offset] = (byte) (offset + 1);
        word(raw, fm1 + 0x2e, 0x8300);

        var snapshot = S3kCompleteRunStateDecoder.decode(raw,
                S3kCompleteRunAssetCatalog.load(rom()));
        var track = snapshot.musicTracks().get(1);

        assertEquals(0x3456, track.frequencyOrDac());
        assertEquals(new RomPointer("z80.driver-data", 0x1a13), track.ssgEgPointer());
        assertEquals(new RomPointer("z80.driver-data", 0x1a45), track.totalLevelPointer());
        assertEquals(new RomPointer("music.Snd_AIZ1", 0x2c8200), track.modulationPointer());
        assertEquals(0x1234, track.modulationValue());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 0x00, 0x83), track.sharedStorage());
        assertEquals(List.of(new RomPointer("music.Snd_AIZ1", 0x2c8300)), track.returnStack());
    }

    @Test
    void ignoresEveryStaleUnionByteOnAnInactiveLiveTrack() throws Exception {
        byte[] raw = baseState();
        int stale = TRACKS + 0x30;
        for (int offset = 0; offset < 0x30; offset++) raw[stale + offset] = (byte) 0xff;
        raw[stale] = 0;

        var snapshot = S3kCompleteRunStateDecoder.decode(raw,
                S3kCompleteRunAssetCatalog.load(rom()));

        assertFalse(snapshot.musicTracks().get(1).populated());
    }

    @Test
    void decodesAllNineFixSndbugsZeroSavedTracksFromTheOverlappingRegion() throws Exception {
        byte[] raw = baseState();
        raw[0x16] = 0x29;
        raw[0x2d] = 0x59;
        word(raw, 0x2a, 0x8240);
        int[] voices = {0x06, 0x00, 0x01, 0x02, 0x04, 0x05, 0x80, 0xa0, 0xc0};
        for (int index = 0; index < voices.length; index++) {
            track(raw, OVERLAP + index * 0x30, 0x00, voices[index], 0x8300 + index * 0x20, 0x30);
        }

        var snapshot = S3kCompleteRunStateDecoder.decode(raw,
                S3kCompleteRunAssetCatalog.load(rom()));

        SavedMusic saved = assertInstanceOf(SavedMusic.class, snapshot.overlap());
        assertEquals(9, saved.tracks().size());
        assertTrue(saved.tracks().stream().allMatch(track -> track.populated()));
        assertEquals(0x2c8400L, saved.tracks().get(8).dataPointer().pointer());
    }

    @Test
    void treatsFfAsSavedOverlapUntilRestoreClearsTheFlag() throws Exception {
        byte[] preRestore = baseState();
        preRestore[0x16] = (byte) 0xff;
        preRestore[0x2d] = 0x59;
        word(preRestore, 0x2a, 0x8240);
        int[] voices = {0x06, 0x00, 0x01, 0x02, 0x04, 0x05, 0x80, 0xa0, 0xc0};
        for (int index = 0; index < voices.length; index++) {
            track(preRestore, OVERLAP + index * 0x30, 0, voices[index],
                    0x8300 + index * 0x20, 0x30);
        }
        var catalog = S3kCompleteRunAssetCatalog.load(rom());

        var savedSnapshot = S3kCompleteRunStateDecoder.decode(preRestore, catalog);
        assertInstanceOf(SavedMusic.class, savedSnapshot.overlap());
        S3kCompleteRunAudioProfile.profile().validateState(
                S3kCompleteRunStateNormalizer.normalizeReference(savedSnapshot, catalog.assets()));

        byte[] postRestore = baseState();
        postRestore[0x16] = 0;
        track(postRestore, OVERLAP, 0x80, 0x02, 0xde3a, 0x30);
        assertInstanceOf(LiveSfx.class,
                S3kCompleteRunStateDecoder.decode(postRestore, catalog).overlap());
    }

    @Test
    void failsClosedOnUnknownBanksWindowEscapesAndInvalidLiveStackPointers() throws Exception {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom());

        byte[] unknownBank = baseState();
        unknownBank[0x3e] = 0x58;
        track(unknownBank, TRACKS, 0x80, 0x06, 0x8120, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(unknownBank, catalog));

        byte[] windowEscape = baseState();
        track(windowEscape, TRACKS, 0x80, 0x06, 0x7fff, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(windowEscape, catalog));

        byte[] bankPadding = baseState();
        bankPadding[0x3e] = 0x1c;
        word(bankPadding, 0x33, 0x8100);
        word(bankPadding, 0x37, 0x8100);
        track(bankPadding, TRACKS, 0x80, 0x06, 0x8100, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(bankPadding, catalog));

        byte[] bank59Tail = baseState();
        track(bank59Tail, TRACKS, 0x80, 0x06, 0xebf1, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(bank59Tail, catalog));

        byte[] bank5bPrefix = baseState();
        bank5bPrefix[0x3e] = 0x5b;
        track(bank5bPrefix, TRACKS, 0x80, 0x06, 0x8000, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(bank5bPrefix, catalog));

        byte[] bank5bTail = baseState();
        bank5bTail[0x3e] = 0x5b;
        track(bank5bTail, TRACKS, 0x80, 0x06, 0xfeac, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(bank5bTail, catalog));

        byte[] sfxPrefix = baseState();
        track(sfxPrefix, OVERLAP, 0x80, 0x02, 0x8100, 0x30);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(sfxPrefix, catalog));

        byte[] badStack = baseState();
        track(badStack, TRACKS, 0x80, 0x06, 0x8120, 0x2d);
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(badStack, catalog));
    }

    @Test
    void rejectsWrongStateWidthAndAnyRomOtherThanThePinnedLockedOnImage() throws Exception {
        S3kCompleteRunAssetCatalog catalog = S3kCompleteRunAssetCatalog.load(rom());
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateDecoder.decode(new byte[1023], catalog));
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunAssetCatalog.load(Path.of("pom.xml")));
    }

    private static Path rom() {
        // Skip (never error) when the ROM is absent: ROM-less CI runners must
        // report a skip, while release runs pass -Ds3k.rom.path explicitly.
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null, "Sonic 3&K locked-on ROM not available — skipping test");
        return romFile.toPath();
    }

    private static byte[] baseState() {
        byte[] raw = new byte[1024];
        raw[0x3e] = 0x59;
        word(raw, 0x33, 0x8120);
        word(raw, 0x35, 0x0695);
        word(raw, 0x37, 0x8100);
        word(raw, 0x39, 0xde45);
        return raw;
    }

    private static void track(byte[] raw, int base, int playback, int voice,
            int dataPointer, int stackPointer) {
        raw[base] = (byte) playback;
        raw[base + 1] = (byte) voice;
        raw[base + 2] = 1;
        word(raw, base + 3, dataPointer);
        raw[base + 9] = (byte) stackPointer;
    }

    private static void word(byte[] raw, int offset, int value) {
        raw[offset] = (byte) value;
        raw[offset + 1] = (byte) (value >>> 8);
    }
}
