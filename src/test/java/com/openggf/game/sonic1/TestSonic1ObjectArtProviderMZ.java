package com.openggf.game.sonic1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1ObjectArtProviderMZ {
    private static final int MZ_REGISTRY_INDEX = 1;
    private static final int ACT_2 = 1;

    private SharedLevel sharedLevel;

    @AfterEach
    public void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @Test
    public void loadsChainedStomperSheetInMarbleZone() throws IOException {
        Sonic1ObjectArtProvider provider = new Sonic1ObjectArtProvider();
        provider.loadArtForZone(Sonic1Constants.ZONE_MZ);

        ObjectSpriteSheet sheet = provider.getSheet(ObjectArtKeys.MZ_CHAINED_STOMPER);
        assertNotNull(sheet, "MZ chained stomper sheet should be loaded in Marble Zone");
        assertEquals(11, sheet.getFrameCount(), "Expected 11 mapping frames from Map_CStom_internal");
    }

    @Test
    public void shieldFrameZeroRemainsInvisible() throws IOException {
        Sonic1ObjectArtProvider provider = new Sonic1ObjectArtProvider();
        provider.loadArtForZone(Sonic1Constants.ZONE_MZ);

        ObjectSpriteSheet sheet = provider.getSheet(ObjectArtKeys.SHIELD);
        assertNotNull(sheet, "S1 shield sheet should be loaded");
        assertEquals(List.of(0, 4, 4, 4),
                java.util.stream.IntStream.range(0, sheet.getFrameCount())
                        .mapToObj(frame -> sheet.getFrame(frame).pieces().size())
                        .toList(),
                "Map_Shield should keep its invisible frame followed by all three full ROM frames");
    }

    @Test
    public void mzLavaWallLevelTileSheetCoversAllRemappedTiles() throws IOException {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, MZ_REGISTRY_INDEX, ACT_2);

        Sonic1ObjectArtProvider provider = new Sonic1ObjectArtProvider();
        provider.registerLavaWallSheet(sharedLevel.level(), Sonic1Constants.ZONE_MZ);

        ObjectSpriteSheet sheet = provider.getSheet(ObjectArtKeys.MZ_LAVA_WALL);
        assertNotNull(sheet, "MZ lava wall sheet should be registered from level tile art");
        assertEquals(5, sheet.getFrameCount(), "Expected 5 mapping frames from Map_LWall");

        int patternCount = sheet.getPatterns().length;
        for (int frameIndex = 0; frameIndex < sheet.getFrameCount(); frameIndex++) {
            SpriteMappingFrame frame = sheet.getFrame(frameIndex);
            for (SpriteMappingPiece piece : frame.pieces()) {
                int exclusiveEnd = piece.tileIndex() + piece.widthTiles() * piece.heightTiles();
                assertTrue(exclusiveEnd <= patternCount,
                        "MZ lava wall frame " + frameIndex + " references tile range ending at $"
                                + Integer.toHexString(exclusiveEnd - 1)
                                + " but the registered sheet only has $" + Integer.toHexString(patternCount)
                                + " patterns");
            }
        }
    }
}
