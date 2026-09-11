package com.openggf.tools.audio.completerun.s2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tests.RomTestUtils;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestS2CompleteRunStateDecoder {
    private static final int MUSIC = 0x1b98;
    private static final int SFX = 0x1d3c;

    @Test
    void authenticatesRev01AndDerivesOccupiedSongAndSfxRanges() throws Exception {
        var catalog = S2CompleteRunAssetCatalog.load(rom());

        assertEquals("8bca5dcef1af3e00098666fd892dc1c2a76333f9",
                S2CompleteRunAssetCatalog.ROM_SHA1);
        assertEquals(112, catalog.assets().size());
        assertEquals(0x1380, catalog.assets().get("music.82").addressBase());
        assertTrue(catalog.assets().get("music.82").addressEndExclusive() > 0x1380);
        assertEquals(0xef33, catalog.assets().get("sfx.A0").addressBase());
        assertEquals(0xffec, catalog.assets().get("sfx.F0").addressEndExclusive());
        assertThrows(IllegalArgumentException.class, () -> catalog.sfx(0xfff0));
    }

    @Test
    void decodesLiveMusicAndSfxFromTheirExactPhysicalSlots() throws Exception {
        byte[] raw = baseState();
        track(raw, MUSIC, 0x80, 0x06, 0x1390, 0x2a);
        int jump = S2CompleteRunAssetCatalog.load(rom()).assets().get("sfx.A0").addressBase();
        track(raw, SFX, 0x80, 0x02, jump, 0x2a);

        var catalog = S2CompleteRunAssetCatalog.load(rom());
        var state = S2CompleteRunStateDecoder.decode(raw, catalog);

        assertEquals("music.82", state.sourceSlots().getFirst().track().assetKey());
        assertEquals("sfx.A0", state.sourceSlots().get(10).track().assetKey());
        assertFalse(state.sourceSlots().get(1).track().active());
        S2CompleteRunAudioProfile.profile().validateState(
                S2CompleteRunStateNormalizer.normalizeReference(state, catalog.assets()));
    }

    @Test
    void ignoresStaleInactiveUnionsButRejectsUnknownLivePointersAndVoices() throws Exception {
        var catalog = S2CompleteRunAssetCatalog.load(rom());
        byte[] stale = baseState();
        for (int offset = 0; offset < 0x2a; offset++) stale[MUSIC + 0x2a + offset] = (byte) 0xff;
        stale[MUSIC + 0x2a] = 0;
        assertFalse(S2CompleteRunStateDecoder.decode(stale, catalog)
                .sourceSlots().get(1).track().active());

        byte[] outside = baseState();
        track(outside, MUSIC, 0x80, 0x06, 0x137f, 0x2a);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(outside, catalog));

        byte[] badVoice = baseState();
        track(badVoice, MUSIC, 0x80, 0x05, 0x1390, 0x2a);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(badVoice, catalog));

        byte[] badBank = baseState();
        badBank[0x1b80 + 0x16] = 0;
        track(badBank, MUSIC, 0x80, 0x06, 0x1390, 0x2a);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(badBank, catalog));
    }

    @Test
    void suppressesStaleGlobalVoicePointerWhenNoMusicTrackIsLive() throws Exception {
        byte[] raw = baseState();
        word(raw, 0x1b80 + 0x0c, 0xffff);

        var state = S2CompleteRunStateDecoder.decode(raw, S2CompleteRunAssetCatalog.load(rom()));

        assertEquals(0, state.globals().voiceTablePointer().address());
        assertEquals(null, state.globals().voiceTablePointer().assetKey());
    }

    @Test
    void decodesFixBugsZeroSavedTracksOnlyWhileOneUpOwnsTheUnion() throws Exception {
        var catalog = S2CompleteRunAssetCatalog.load(rom());
        byte[] raw = baseState();
        int savedPointer = catalog.assets().get("music.9E").addressBase();
        raw[0x1b80 + 0x11] = (byte) 0x80;
        raw[0x1e38] = 0x55; // fixBugs=0 saves priority before live priority is cleared.
        raw[0x1e38 + 0x16] = (byte) 0x80;
        word(raw, 0x1e38 + 0x0c, savedPointer);
        track(raw, 0x1e50, 0x80, 0x06, savedPointer, 0x2a);

        var state = S2CompleteRunStateDecoder.decode(raw, catalog);

        assertEquals(0x55, state.savedMusic().globals().priority());
        assertEquals("music.9E", state.savedMusic().sourceSlots().getFirst().track().assetKey());

        raw[0x1b80 + 0x11] = 0;
        for (int offset = 0; offset < 0x1c8; offset++) raw[0x1e38 + offset] = (byte) 0xff;
        assertEquals(null, S2CompleteRunStateDecoder.decode(raw, catalog).savedMusic());
    }

    @Test
    void rejectsMalformedWidthStackAndPinnedRomMismatch() throws Exception {
        var catalog = S2CompleteRunAssetCatalog.load(rom());
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(new byte[0x1fff], catalog));
        byte[] badStack = baseState();
        track(badStack, MUSIC, 0x80, 0x06, 0x1390, 0x29);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(badStack, catalog));
        byte[] nextAsset = baseState();
        AssetRange ehz = range(catalog, "music.82");
        track(nextAsset, MUSIC, 0x80, 0x06, ehz.base(), 0x28);
        word(nextAsset, MUSIC + 0x28, ehz.end());
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunStateDecoder.decode(nextAsset, catalog));
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunAssetCatalog.load(Path.of("pom.xml")));
    }

    private static AssetRange range(S2CompleteRunAssetCatalog catalog, String key) {
        var asset = catalog.assets().get(key);
        return new AssetRange(asset.addressBase(), asset.addressEndExclusive());
    }

    private record AssetRange(int base, int end) { }

    private static Path rom() {
        // Skip (never error) when the ROM is absent: ROM-less CI runners must
        // report a skip, while release runs pass -Dsonic2.rom.path explicitly.
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null, "Sonic 2 REV01 ROM not available — skipping test");
        return romFile.toPath();
    }

    private static byte[] baseState() {
        byte[] raw = new byte[0x2000];
        raw[0x1b80 + 0x0c] = (byte) 0x90;
        raw[0x1b80 + 0x0d] = 0x13;
        raw[0x1b80 + 0x16] = (byte) 0x80;
        raw[0x1300] = (byte) 0x82;
        return raw;
    }

    private static void track(byte[] raw, int base, int playback, int voice,
            int dataPointer, int stackPointer) {
        raw[base] = (byte) playback;
        raw[base + 1] = (byte) voice;
        raw[base + 2] = 1;
        word(raw, base + 3, dataPointer);
        raw[base + 0x0a] = (byte) stackPointer;
    }

    private static void word(byte[] raw, int offset, int value) {
        raw[offset] = (byte) value;
        raw[offset + 1] = (byte) (value >>> 8);
    }
}
