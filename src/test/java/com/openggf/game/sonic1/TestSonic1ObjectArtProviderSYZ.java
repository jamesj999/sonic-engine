package com.openggf.game.sonic1;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1ObjectArtProviderSYZ {
    private static final int SYZ_REGISTRY_INDEX = 2;
    private static final int ACT_3 = 2;

    private SharedLevel sharedLevel;

    @AfterEach
    public void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @Test
    public void syzBossBlockLevelTileSheetUsesRomPaletteLine() throws IOException {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, SYZ_REGISTRY_INDEX, ACT_3);

        Sonic1ObjectArtProvider provider = new Sonic1ObjectArtProvider();
        provider.registerBossBlockSheet(sharedLevel.level());

        ObjectSpriteSheet sheet = provider.getSheet(ObjectArtKeys.SYZ_BOSS_BLOCK);
        assertNotNull(sheet, "SYZ boss block sheet should be registered from level tile art");
        assertEquals(2, sheet.getPaletteIndex(),
                "Obj76 uses make_art_tile(ArtTile_Level,2,0), so blocks must render with palette line 2");
        assertEquals(5, sheet.getFrameCount(), "Expected 5 mapping frames from Map_BossBlock");

        int patternCount = sheet.getPatterns().length;
        for (int frameIndex = 0; frameIndex < sheet.getFrameCount(); frameIndex++) {
            SpriteMappingFrame frame = sheet.getFrame(frameIndex);
            for (SpriteMappingPiece piece : frame.pieces()) {
                int exclusiveEnd = piece.tileIndex() + piece.widthTiles() * piece.heightTiles();
                assertTrue(exclusiveEnd <= patternCount,
                        "SYZ boss block frame " + frameIndex + " references tile range ending at $"
                                + Integer.toHexString(exclusiveEnd - 1)
                                + " but the registered sheet only has $" + Integer.toHexString(patternCount)
                                + " patterns");
            }
        }
    }
}
