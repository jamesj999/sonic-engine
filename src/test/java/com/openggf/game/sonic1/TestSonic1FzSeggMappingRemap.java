package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1FzSeggMappingRemap {

    @Test
    @SuppressWarnings("unchecked")
    public void intubeOverlayUsesWrappedFzBossTilesAfterObjectBaseAdd() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> raw = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SEGG_ADDR);

        Method method = Sonic1ObjectArtProvider.class.getDeclaredMethod(
                "remapMappingsForObjectBase",
                List.class,
                int.class,
                int.class
        );
        method.setAccessible(true);

        List<SpriteMappingFrame> remapped = (List<SpriteMappingFrame>) method.invoke(
                null,
                raw,
                Sonic1Constants.ART_TILE_FZ_EGGMAN_NO_VEHICLE,
                Sonic1Constants.ART_TILE_FZ_BOSS
        );

        // Frame 9 = .intube; piece 4 is one of the four tube overlay strips:
        // spritePiece -$10,-$20,4,2,$6F0,1,1,1,0
        SpriteMappingPiece tubePiece = remapped.get(9).pieces().get(4);
        assertEquals(0x60, tubePiece.tileIndex(), "Tube overlay tile should wrap to FZ boss local tile $60");
        assertFalse(tubePiece.hFlip(), "H-flip should cancel after add.w overflow");
        assertFalse(tubePiece.vFlip(), "V-flip should cancel after add.w overflow");
        assertEquals(2, tubePiece.paletteIndex(), "Palette should resolve to line 2 after add.w overflow");
    }

}


