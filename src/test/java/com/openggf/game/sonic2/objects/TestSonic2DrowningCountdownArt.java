package com.openggf.game.sonic2.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArt;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.playable.DrowningController;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2DrowningCountdownArt {

    @Test
    void countdownNumbersSelectTheirMatchingAniObj0AFrames() throws Exception {
        Field frames = DrowningController.class.getDeclaredField("S2_COUNTDOWN_FRAMES");
        frames.setAccessible(true);

        assertArrayEquals(new int[] {8, 9, 10, 11, 12, 13}, (int[]) frames.get(null),
                "countdown numbers 0-5 use their matching ArtUnc_Countdown blocks");
    }

    @Test
    void countdownFramesUseTheirMatchingRomNumberTiles() throws IOException {
        assertEquals(0x7AF80, Sonic2Constants.ART_UNC_COUNTDOWN_ADDR,
                "ArtUnc_Countdown must start at the pinned REV01 ROM address");
        Rom rom = TestEnvironment.currentRom();
        ObjectSpriteSheet sheet = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom))
                .loadBubblesSheet();
        assertEquals(0, sheet.getPaletteIndex(),
                "ordinary Obj0A bubbles retain the sheet's palette line 0");
        Pattern[] countdownArt = loadCountdownPatterns(rom);

        for (int frame = 8; frame <= 13; frame++) {
            assertEquals(1, sheet.getFrame(frame).pieces().size());
            SpriteMappingPiece piece = sheet.getFrame(frame).pieces().getFirst();
            assertEquals(2, piece.widthTiles());
            assertEquals(3, piece.heightTiles());
            assertFalse(piece.hFlip(), "art_tile addition clears the mapping's horizontal flip bit");
            assertFalse(piece.vFlip(), "art_tile addition clears the mapping's vertical flip bit");
            assertEquals(1, piece.paletteIndex(),
                    "art_tile addition selects underwater palette line 1 for countdown digits");
            assertTrue(piece.tileIndex() + 5 < sheet.getPatterns().length,
                    "Obj0A countdown mapping must address six tiles present in the bubble sheet");
            for (int tile = 0; tile < 6; tile++) {
                assertTrue(pixelsEqual(
                                sheet.getPatterns()[piece.tileIndex() + tile],
                                countdownArt[(frame - 8) * 6 + tile]),
                        "Obj0A frame " + frame + " must use its matching six-tile countdown digit");
            }
        }
    }

    private static Pattern[] loadCountdownPatterns(Rom rom) throws IOException {
        byte[] bytes = new byte[6 * 6 * Pattern.PATTERN_SIZE_IN_ROM];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(Sonic2Constants.ART_UNC_COUNTDOWN_ADDR);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Unexpected EOF reading S2 countdown art");
                }
            }
        }
        Pattern[] patterns = new Pattern[36];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
            patterns[i].fromSegaFormat(java.util.Arrays.copyOfRange(bytes,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM));
        }
        return patterns;
    }

    private static boolean pixelsEqual(Pattern a, Pattern b) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
