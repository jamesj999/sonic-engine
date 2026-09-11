package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.List;

/**
 * Loads spindash dust art, mappings, and DPLCs for Sonic 3&amp;K.
 *
 * <p>Unlike S2 which has character-specific dust art tiles, S3K uses the same
 * uncompressed art ({@code ArtUnc_DashDust}) and mappings ({@code Map_DashDust})
 * for all characters.
 *
 * <p>The original VDP VRAM tile is {@code ArtTile_DashDust = $07E0}, but the
 * engine uses a water-surface sub-range base to avoid collision with ring
 * patterns that are placed right after level tiles in the atlas.
 */
public class Sonic3kDustArt {

    /**
     * Virtual pattern base for dust DPLC bank, placed in the free part of
     * the water-surface range before sidekick banks.
     */
    private static final int DUST_PATTERN_BASE = PatternAtlasRange.WATER_SURFACE.base() + 0x4000;

    private final RomByteReader reader;
    private SpriteArtSet cached;

    public Sonic3kDustArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        // S3K uses the same dust art for all characters
        if (cached != null) {
            return cached;
        }
        Pattern[] artTiles = S3kSpriteDataLoader.loadArtTiles(
                reader, Sonic3kConstants.ART_UNC_DASH_DUST_ADDR, Sonic3kConstants.ART_UNC_DASH_DUST_SIZE);
        List<SpriteMappingFrame> mappingFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_DASH_DUST_ADDR);
        List<SpriteDplcFrame> dplcFrames = S3kSpriteDataLoader.loadDplcFrames(
                reader, Sonic3kConstants.DPLC_DASH_DUST_ADDR);
        int bankSize = S3kSpriteDataLoader.resolveBankSize(dplcFrames, mappingFrames);

        cached = new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                0, // paletteIndex
                DUST_PATTERN_BASE,
                1, // frameDelay
                bankSize,
                null,
                null);
        return cached;
    }
}
