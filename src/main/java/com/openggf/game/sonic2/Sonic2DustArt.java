package com.openggf.game.sonic2;
import com.openggf.game.sonic2.constants.Sonic2Constants;

import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.util.PatternDecompressor;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Loads spindash dust art, mappings, and DPLCs for Sonic 2 (REV01).
 */
public class Sonic2DustArt {
    private final RomByteReader reader;
    private SpriteArtSet cachedSonicDust;
    private SpriteArtSet cachedTailsDust;

    public Sonic2DustArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        String normalized = characterCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tails" -> loadTailsDust();
            case "sonic" -> loadSonicDust();
            default -> null;
        };
    }

    public SpriteArtSet loadSonicDust() throws IOException {
        if (cachedSonicDust != null) {
            return cachedSonicDust;
        }
        cachedSonicDust = loadDustArt(Sonic2Constants.ART_TILE_SONIC_DUST);
        return cachedSonicDust;
    }

    public SpriteArtSet loadTailsDust() throws IOException {
        if (cachedTailsDust != null) {
            return cachedTailsDust;
        }
        cachedTailsDust = loadDustArt(Sonic2Constants.ART_TILE_TAILS_DUST);
        return cachedTailsDust;
    }

    private SpriteArtSet loadDustArt(int basePatternIndex) throws IOException {
        Pattern[] artTiles = loadArtTiles(
                Sonic2Constants.ART_UNC_SPLASH_DUST_ADDR,
                Sonic2Constants.ART_UNC_SPLASH_DUST_SIZE
        );
        List<SpriteMappingFrame> mappingFrames = S2SpriteDataLoader.loadMappingFrames(reader,
                Sonic2Constants.MAP_UNC_OBJ08_ADDR
        );
        List<SpriteDplcFrame> dplcFrames = S2SpriteDataLoader.loadDplcFrames(reader,
                Sonic2Constants.MAP_R_UNC_OBJ08_ADDR
        );

        int bankSize = resolveBankSize(dplcFrames, mappingFrames);
        int paletteIndex = 0;
        int frameDelay = 1;

        return new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                paletteIndex,
                basePatternIndex,
                frameDelay,
                bankSize,
                null,
                null
        );
    }

    private Pattern[] loadArtTiles(int artAddr, int artSize) throws IOException {
        return PatternDecompressor.uncompressed(reader, artAddr, artSize);
    }

    private int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        int maxTiles = 0;
        for (SpriteDplcFrame frame : dplcFrames) {
            int total = 0;
            for (TileLoadRequest request : frame.requests()) {
                total += Math.max(0, request.count());
            }
            maxTiles = Math.max(maxTiles, total);
        }
        if (maxTiles > 0) {
            return maxTiles;
        }
        int maxIndex = 0;
        for (SpriteMappingFrame frame : mappingFrames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileCount = piece.widthTiles() * piece.heightTiles();
                maxIndex = Math.max(maxIndex, piece.tileIndex() + tileCount);
            }
        }
        return Math.max(0, maxIndex);
    }
}
