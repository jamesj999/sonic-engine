package com.openggf.game.sonic2;

import com.openggf.data.RomByteReader;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2ArtDecoding {
    @Test
    void allPlayerAndDustFramesMatchVerifiedRev01Tables() throws Exception {
        var reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        var players = new Sonic2PlayerArt(reader);
        var dust = new Sonic2DustArt(reader);
        verify(players.loadSonic(), 214, 31,
                "24b173db8edf8be42ca03b2aacc048cd27bcb931ccb8e3e417bd565993d92cf8");
        verify(players.loadTails(), 139, 28,
                "ba87b0f16e69407c942a0371620d02ad8cd9dfc9f9ec50f6b1e964798d4efc31");
        verify(dust.loadSonicDust(), 22, 16,
                "e217a3b56c0077fa271b551d7e7c87eecc2fcce42edce452546f8193320d5fc9");
        verify(dust.loadTailsDust(), 22, 16,
                "e217a3b56c0077fa271b551d7e7c87eecc2fcce42edce452546f8193320d5fc9");
        assertEquals(players.loadSonic().dplcFrames(),
                Sonic2PlayerArt.parseDplcFrames(reader, 0x714E0));
    }

    private void verify(SpriteArtSet art, int frames, int bankSize, String expectedHash) throws Exception {
        assertEquals(frames, art.mappingFrames().size());
        assertEquals(frames, art.dplcFrames().size());
        assertEquals(bankSize, art.bankSize());
        assertEquals(0, art.paletteIndex());
        // Canonical descriptor digest independently measured from REV01 (CRC32 7B905383).
        // Covers every mapping field, piece order, empty frame, and DPLC request.
        StringBuilder descriptor = new StringBuilder();
        for (var frame : art.mappingFrames()) {
            for (var piece : frame.pieces()) {
                descriptor.append(piece.xOffset()).append(',').append(piece.yOffset()).append(',')
                        .append(piece.widthTiles()).append(',').append(piece.heightTiles()).append(',')
                        .append(piece.tileIndex()).append(',').append(piece.hFlip() ? 1 : 0).append(',')
                        .append(piece.vFlip() ? 1 : 0).append(',').append(piece.paletteIndex()).append(',')
                        .append(piece.priority() ? 1 : 0).append(';');
            }
            descriptor.append('\n');
        }
        for (var frame : art.dplcFrames()) {
            for (var request : frame.requests()) {
                assertEquals(-1, request.destinationOffset(), "S2 DPLC requests remain sequential");
                descriptor.append(request.startTile()).append(':').append(request.count()).append(';');
            }
            descriptor.append('\n');
        }
        assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(descriptor.toString().getBytes(StandardCharsets.UTF_8))));
    }
}
