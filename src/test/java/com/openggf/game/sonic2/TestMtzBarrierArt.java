package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
class TestMtzBarrierArt {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void levelLoadRegistersVisibleBarrierFromMetropolisTiles(int act) throws Exception {
        SharedLevel shared = SharedLevel.load(SonicGame.SONIC_2, 7, act);
        try {
            var provider = GameServices.module().getObjectArtProvider();
            assertNotNull(provider.getRenderer(Sonic2ObjectArtKeys.BARRIER),
                    "Obj2D must render in every Metropolis act");
            ObjectSpriteSheet sheet = provider.getSheet(Sonic2ObjectArtKeys.BARRIER);
            assertNotNull(sheet);
            assertEquals(3, sheet.getPaletteIndex(), "Obj2D_Init level-art palette");
            var pieces = sheet.getFrame(1).pieces();
            assertEquals(2, pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                var piece = pieces.get(i);
                assertEquals(-12, piece.xOffset());
                assertEquals(-32 + i * 32, piece.yOffset());
                assertEquals(3, piece.widthTiles());
                assertEquals(4, piece.heightTiles());
                assertEquals(0x5F, piece.tileIndex());
            }
            boolean visible = false;
            for (int tile = 0x5F; tile < 0x6B; tile++) {
                Pattern pattern = sheet.getPatterns()[tile];
                assertSame(shared.level().getPattern(tile), pattern,
                        "Barrier must use the ROM-backed level tile at " + tile);
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        visible |= pattern.getPixel(x, y) != 0;
                    }
                }
            }
            assertTrue(visible, "Barrier tiles must contain visible pixels");
        } finally {
            shared.dispose();
        }
    }
}
