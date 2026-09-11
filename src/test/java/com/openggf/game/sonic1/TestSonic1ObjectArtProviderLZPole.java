package com.openggf.game.sonic1;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1ObjectArtProviderLZPole {
    @Test
    public void loadsBreakablePoleSheetInLabyrinthZone() throws IOException {
        Sonic1ObjectArtProvider provider = new Sonic1ObjectArtProvider();
        provider.loadArtForZone(Sonic1Constants.ZONE_LZ);

        ObjectSpriteSheet sheet = provider.getSheet(ObjectArtKeys.LZ_BREAKABLE_POLE);
        assertNotNull(sheet, "LZ breakable pole sheet should be loaded in Labyrinth Zone");
        assertEquals(2, sheet.getFrameCount(), "Expected 2 mapping frames from Map_Pole_internal");
    }
}


