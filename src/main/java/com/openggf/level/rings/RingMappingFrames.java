package com.openggf.level.rings;

import com.openggf.level.render.SpriteMappingFrame;
import java.util.List;

/** Adapts decoded ROM mappings to the ring renderer's frame contract. */
public final class RingMappingFrames {
    private RingMappingFrames() {
    }

    public static List<RingFrame> fromMappings(List<SpriteMappingFrame> mappings) {
        return mappings.stream().map(frame -> new RingFrame(frame.pieces().stream()
                .map(piece -> new RingFramePiece(piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(), piece.tileIndex(),
                        piece.hFlip(), piece.vFlip(), piece.paletteIndex()))
                .toList())).toList();
    }
}
