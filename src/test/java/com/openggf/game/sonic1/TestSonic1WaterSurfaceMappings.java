package com.openggf.game.sonic1;

import com.openggf.level.render.SpriteFramePiece;
import org.junit.jupiter.api.Test;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1WaterSurfaceMappings {

    @Test
    public void romMappingsMatchMapSurfNormalFrames() {
        List<SpriteMappingFrame> frames = Sonic1WaterSurfaceManager.createRomMappingFrames();
        assertEquals(3, frames.size());

        assertFrame(frames.get(0), 0, false);
        assertFrame(frames.get(1), 8, false);
        assertFrame(frames.get(2), 0, true);
    }

    @Test
    public void baseXMatchesSurfActionAlignmentAndFrameAlternation() {
        assertEquals(0x120, Sonic1WaterSurfaceManager.computeSurfaceBaseX(0x123, 0));
        assertEquals(0x140, Sonic1WaterSurfaceManager.computeSurfaceBaseX(0x123, 1));
        assertEquals(0x0, Sonic1WaterSurfaceManager.computeSurfaceBaseX(0x1F, 2));
        assertEquals(0x20, Sonic1WaterSurfaceManager.computeSurfaceBaseX(0x1F, 3));
    }

    private static void assertFrame(SpriteMappingFrame frame, int tileIndex, boolean hFlip) {
        List<? extends SpriteFramePiece> pieces = frame.pieces();
        assertEquals(3, pieces.size());

        int[] expectedX = {-0x60, -0x20, 0x20};
        for (int i = 0; i < pieces.size(); i++) {
            SpriteMappingPiece piece = (SpriteMappingPiece) pieces.get(i);
            assertEquals(expectedX[i], piece.xOffset());
            assertEquals(-3, piece.yOffset());
            assertEquals(4, piece.widthTiles());
            assertEquals(2, piece.heightTiles());
            assertEquals(tileIndex, piece.tileIndex());
            assertEquals(hFlip, piece.hFlip());
            assertFalse(piece.vFlip());
            assertEquals(0, piece.paletteIndex());
            assertTrue(piece.priority());
        }
    }
}


