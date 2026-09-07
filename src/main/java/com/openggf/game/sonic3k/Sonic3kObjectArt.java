package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpriteMappingPieces;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.level.resources.CompressionType;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds object sprite sheets for S3K objects.
 * Many S3K objects use level patterns rather than dedicated compressed art.
 */
public class Sonic3kObjectArt {
    private static final Logger LOG = Logger.getLogger(Sonic3kObjectArt.class.getName());
    private static final int CNZ_CANNON_DPLC_DEST_TILE =
            Sonic3kConstants.ARTTILE_CNZ_CANNON_DPLC_DEST - Sonic3kConstants.ARTTILE_CNZ_CANNON;
    private static final int CNZ_CANNON_SOURCE_BANK = 0x200;
    private static final int CNZ_CANNON_BASE_FRAME = 9;

    private final Level level;
    private final RomByteReader reader;

    public Sonic3kObjectArt(Level level) {
        this(level, null);
    }

    public Sonic3kObjectArt(Level level, RomByteReader reader) {
        this.level = level;
        this.reader = reader;
    }

    // Tracks the level tile range of the most recently built sheet,
    // so callers can record which tiles each sheet depends on.
    private int lastBuildStartTile = -1;
    private int lastBuildTileCount = -1;
    private List<Sonic3kPlcLoader.TileRange> lastBuildTileRanges = List.of();

    /** Returns the starting level tile index of the last built sheet, or -1. */
    public int getLastBuildStartTile() { return lastBuildStartTile; }

    /** Returns the tile count of the last built sheet, or -1. */
    public int getLastBuildTileCount() { return lastBuildTileCount; }

    /**
     * Returns the exact level-pattern ranges used by the last built sheet.
     * Most sheets are contiguous; this captures mappings that combine source banks.
     */
    public List<Sonic3kPlcLoader.TileRange> getLastBuildTileRanges() {
        return lastBuildTileRanges;
    }

    void clearLastBuildTileRanges() {
        lastBuildTileRanges = List.of();
    }

    /**
     * Builds a sprite sheet from level patterns for an object that uses level art.
     *
     * @param artTileBase     the art_tile base index (added to piece tile indices)
     * @param sheetPalette    the sheet palette line (0-3)
     * @param frames          the mapping frames
     * @param minTile         the minimum tile index used across all pieces
     * @param maxTileExclusive one past the maximum tile index used
     * @return the sprite sheet, or null if level lacks needed patterns
     */
    public ObjectSpriteSheet buildLevelArtSheet(int artTileBase, int sheetPalette,
            List<SpriteMappingFrame> frames, int minTile, int maxTileExclusive) {
        if (level == null) {
            lastBuildStartTile = -1;
            lastBuildTileCount = -1;
            return null;
        }

        int patternCount = maxTileExclusive - minTile;
        Pattern[] patterns = new Pattern[patternCount];
        int levelPatternCount = level.getPatternCount();

        lastBuildStartTile = artTileBase + minTile;
        lastBuildTileCount = patternCount;

        for (int i = 0; i < patternCount; i++) {
            int levelIndex = artTileBase + minTile + i;
            if (levelIndex < levelPatternCount) {
                patterns[i] = level.getPattern(levelIndex);
            } else {
                patterns[i] = new Pattern();
                LOG.fine("Level pattern " + levelIndex + " out of range (count=" + levelPatternCount + ")");
            }
        }

        // Adjust tile indices in frames by -minTile so they're 0-based
        List<SpriteMappingFrame> adjusted = adjustTileIndices(frames, -minTile);
        return new ObjectSpriteSheet(patterns, adjusted, sheetPalette, 1);
    }

    /**
     * Builds a sprite sheet by parsing S3K mapping frames from ROM at runtime.
     * Automatically computes the tile range from all pieces in the mapping.
     *
     * @param mappingAddr   ROM address of the S3K mapping table
     * @param artTileBase   the art_tile base index (VRAM tile destination)
     * @param sheetPalette  the sheet palette line (0-3)
     * @return the sprite sheet, or null if reader is unavailable or mapping is empty
     */
    public ObjectSpriteSheet buildLevelArtSheetFromRom(int mappingAddr,
            int artTileBase, int sheetPalette) {
        return buildLevelArtSheetFromRom(mappingAddr, artTileBase, sheetPalette,
                S3kSpriteDataLoader.MappingFormat.STANDARD);
    }

    public ObjectSpriteSheet buildLevelArtSheetFromRom(int mappingAddr,
            int artTileBase, int sheetPalette, S3kSpriteDataLoader.MappingFormat mappingFormat) {
        if (reader == null) return null;
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr, mappingFormat);
        return buildLevelArtSheetFromRomFrames(artTileBase, sheetPalette, frames);
    }

    public ObjectSpriteSheet buildLevelArtSheetFromRom(int mappingAddr,
            int artTileBase, int sheetPalette, S3kSpriteDataLoader.MappingFormat mappingFormat,
            int mappingFrameCount) {
        if (reader == null) return null;
        List<SpriteMappingFrame> frames = mappingFrameCount > 0
                ? S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr, mappingFrameCount, mappingFormat)
                : S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr, mappingFormat);
        return buildLevelArtSheetFromRomFrames(artTileBase, sheetPalette, frames);
    }

    private ObjectSpriteSheet buildLevelArtSheetFromRomFrames(
            int artTileBase, int sheetPalette, List<SpriteMappingFrame> frames) {
        if (frames.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, sheetPalette, frames, minTile, maxTile);
    }

    /**
     * Builds the AIZ1Tree sprite sheet.
     * <p>
     * From disassembly (Map - Act 1 Tree.asm):
     * art_tile = make_art_tile($001, 2, 0) → base tile 1, palette 2
     * Mapping: 1 frame, 3 pieces (2x2 each), tile index 0x38
     * Y offsets: -24, -8, +8; X offset: -8
     */
    public ObjectSpriteSheet buildAiz1TreeSheet(int artTileBase) {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ1_TREE_ADDR, artTileBase, 2);
    }

    /**
     * Builds the AIZ1ZiplinePeg sprite sheet.
     * <p>
     * From disassembly (Map - Act 1 Zipline Peg.asm):
     * art_tile = make_art_tile(ArtTile_AIZSlideRope, 2, 0) → base tile 0x324, palette 2
     * Mapping: 1 frame, 3 pieces
     * Piece 0: 4x1 (32x8px), tile 0, Y=-12, X=-32
     * Piece 1: 2x1 (16x8px), tile 4, Y=-4, X=-8
     * Piece 2: 3x3 (24x24px), tile 6, Y=-12, X=+8
     */
    public ObjectSpriteSheet buildAiz1ZiplinePegSheet(int artTileBase) {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ1_ZIPLINE_PEG_ADDR, artTileBase, 2);
    }

    /**
     * Builds the AIZ Ride Vine / Giant Ride Vine sprite sheet.
     * <p>
     * From disassembly:
     * Obj_AIZRideVine / Obj_AIZGiantRideVine:
     * art_tile = make_art_tile(ArtTile_AIZSwingVine, 0, 0)
     * mappings = Map_AIZMHZRideVine
     */
    public ObjectSpriteSheet buildAizRideVineSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_AIZ_MHZ_RIDE_VINE_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_SWING_VINE,
                0);
    }

    /**
     * Builds the Animated Still Sprites sheet used by AIZ (firefly + leaf animations).
     *
     * <p>Disassembly reference:
     * Map_AnimatedStillSprites / Ani_AnimatedStillSprites (sonic3k.asm:60424+).
     * art_tile = make_art_tile(ArtTile_AIZMisc2,3,0). Frames 0-8.
     */
    public ObjectSpriteSheet buildAnimatedStillSpritesSheet(int artTileBase) {
        return buildAnimStillSheet(artTileBase, 3, 0, 8);
    }

    /**
     * Builds the Spikes sprite sheet.
     * <p>
     * From disassembly (Map - Spikes.asm / Obj_Spikes), 8 frames:
     * Frames 0-3: upright spikes (2w×4h pieces), art_tile = ArtTile_SpikesSprings+$8 ($049C)
     * Frames 4-7: sideways spikes (4w×2h pieces with hflip), art_tile = ArtTile_SpikesSprings ($0494)
     * <p>
     * The ROM overrides art_tile for sideways spikes (size index >= 4) to use
     * tiles $0494-$049B instead of $049C-$04A3. Sheet covers both ranges (16 tiles).
     */
    public ObjectSpriteSheet buildSpikesSheet(int artTileBase) {
        if (reader == null) return null;
        List<SpriteMappingFrame> romFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_SPIKES_ADDR, 8);
        List<SpriteMappingFrame> frames = new ArrayList<>(romFrames.size());
        for (int i = 0; i < romFrames.size(); i++) {
            int tileDelta = i < 4 ? 8 : 0;
            List<SpriteMappingPiece> pieces = new ArrayList<>(romFrames.get(i).pieces().size());
            for (SpriteMappingPiece piece : romFrames.get(i).pieces()) {
                pieces.add(new SpriteMappingPiece(
                        piece.xOffset(),
                        piece.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex() + tileDelta,
                        piece.hFlip(),
                        piece.vFlip(),
                        piece.paletteIndex(),
                        piece.priority()));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 16);
    }

    // --- Spring art sheets ---
    // Mapping data parsed from Map - Spring.asm (skdisasm/General/Sprites/Level Misc/)
    // Vertical/Down springs: art_tile = ArtTile_SpikesSprings + $10 = $04A4
    // Horizontal springs: art_tile = ArtTile_SpikesSprings + $20 = $04B4
    // Diagonal springs: art_tile = ArtTile_DiagonalSpring = $043A

    /** Red vertical spring: 3 frames (idle, triggered-compress, triggered-extend). */
    public ObjectSpriteSheet buildSpringVerticalSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                // Frame 0 (idle): coil plate + base plate
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, -8, 4, 1, 0, false, false, 0),
                        new SpriteMappingPiece(-8, 0, 2, 1, 8, false, false, 0))),
                // Frame 1 (triggered-compress): coil plate only, shifted down
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, 0, 4, 1, 0, false, false, 0))),
                // Frame 2 (triggered-extend): coil plate shifted up + extended piece
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, -24, 4, 1, 0, false, false, 0),
                        new SpriteMappingPiece(-8, -16, 2, 3, 0xA, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x10);
    }

    /** Yellow vertical spring: same layout as red, different coil tiles (4) and palette (1). */
    public ObjectSpriteSheet buildSpringVerticalYellowSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, -8, 4, 1, 4, false, false, 1),
                        new SpriteMappingPiece(-8, 0, 2, 1, 8, false, false, 0))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, 0, 4, 1, 4, false, false, 1))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-16, -24, 4, 1, 4, false, false, 1),
                        new SpriteMappingPiece(-8, -16, 2, 3, 0xA, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x10);
    }

    /** Red horizontal spring: 3 frames. */
    public ObjectSpriteSheet buildSpringHorizontalSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                // Frame 0 (idle): coil column + base column
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, -16, 1, 4, 0, false, false, 0),
                        new SpriteMappingPiece(-8, -8, 1, 2, 8, false, false, 0))),
                // Frame 1 (triggered-compress): coil column only
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-8, -16, 1, 4, 0, false, false, 0))),
                // Frame 2 (triggered-extend): coil column shifted + extended piece
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(16, -16, 1, 4, 0, false, false, 0),
                        new SpriteMappingPiece(-8, -8, 3, 2, 0xA, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x10);
    }

    /** Yellow horizontal spring: same layout, different coil tiles. */
    public ObjectSpriteSheet buildSpringHorizontalYellowSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, -16, 1, 4, 4, false, false, 1),
                        new SpriteMappingPiece(-8, -8, 1, 2, 8, false, false, 0))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-8, -16, 1, 4, 4, false, false, 1))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(16, -16, 1, 4, 4, false, false, 1),
                        new SpriteMappingPiece(-8, -8, 3, 2, 0xA, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x10);
    }

    /** Red diagonal spring: 3 frames. */
    public ObjectSpriteSheet buildSpringDiagonalSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                // Frame 0 (idle): 4 pieces
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-21, -15, 3, 1, 0, false, false, 0),
                        new SpriteMappingPiece(-13, -7, 3, 1, 3, false, false, 0),
                        new SpriteMappingPiece(-5, 1, 2, 2, 6, false, false, 0),
                        new SpriteMappingPiece(-15, -5, 2, 2, 0x14, false, false, 0))),
                // Frame 1 (triggered-compress): 3 pieces
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-26, -9, 3, 1, 0, false, false, 0),
                        new SpriteMappingPiece(-18, -1, 3, 1, 3, false, false, 0),
                        new SpriteMappingPiece(-10, 7, 2, 2, 6, false, false, 0))),
                // Frame 2 (triggered-extend): 5 pieces
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-10, -26, 3, 1, 0, false, false, 0),
                        new SpriteMappingPiece(-2, -18, 3, 1, 3, false, false, 0),
                        new SpriteMappingPiece(6, -10, 2, 2, 6, false, false, 0),
                        new SpriteMappingPiece(-6, -11, 2, 1, 0x18, false, false, 0),
                        new SpriteMappingPiece(-14, -3, 2, 1, 0x1A, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x1C);
    }

    /** Yellow diagonal spring: different coil tiles (0xA/0xD/0x10), palette 1. */
    public ObjectSpriteSheet buildSpringDiagonalYellowSheet(int artTileBase) {
        List<SpriteMappingFrame> frames = List.of(
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-21, -15, 3, 1, 0xA, false, false, 1),
                        new SpriteMappingPiece(-13, -7, 3, 1, 0xD, false, false, 1),
                        new SpriteMappingPiece(-5, 1, 2, 2, 0x10, false, false, 1),
                        new SpriteMappingPiece(-15, -5, 2, 2, 0x14, false, false, 0))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-26, -9, 3, 1, 0xA, false, false, 1),
                        new SpriteMappingPiece(-18, -1, 3, 1, 0xD, false, false, 1),
                        new SpriteMappingPiece(-10, 7, 2, 2, 0x10, false, false, 1))),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-10, -26, 3, 1, 0xA, false, false, 1),
                        new SpriteMappingPiece(-2, -18, 3, 1, 0xD, false, false, 1),
                        new SpriteMappingPiece(6, -10, 2, 2, 0x10, false, false, 1),
                        new SpriteMappingPiece(-6, -11, 2, 1, 0x18, false, false, 0),
                        new SpriteMappingPiece(-14, -3, 2, 1, 0x1A, false, false, 0))));
        return buildLevelArtSheet(artTileBase, 0, frames, 0, 0x1C);
    }

    /**
     * Builds the AIZForegroundPlant sprite sheet.
     * <p>
     * From disassembly (Map - AIZ Foreground Plant.asm):
     * art_tile = make_art_tile(ArtTile_AIZMisc1, 2, 1) → base tile 0x333, palette 2, priority
     * Mapping: 2 frames (0=with flowers, 1=without flowers), 8 pieces each.
     * Tile range: 0x64 to 0x9B (56 patterns from level art).
     */
    public ObjectSpriteSheet buildAizForegroundPlantSheet(int artTileBase) {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_FOREGROUND_PLANT_ADDR, artTileBase, 2);
    }

    /**
     * Builds a sprite sheet from ROM mappings, selecting only specific frame indices.
     * Used for StillSprite/AnimatedStillSprite where each zone group uses a subset of
     * the shared mapping table.
     *
     * @param mappingAddr    ROM address of the S3K mapping table
     * @param artTileBase    the art_tile base index (VRAM tile destination)
     * @param sheetPalette   the sheet palette line (0-3)
     * @param frameIndices   which mapping frames to include in the sheet
     * @return the sprite sheet, or null if unavailable
     */
    public ObjectSpriteSheet buildLevelArtSheetFromRomFiltered(int mappingAddr,
            int artTileBase, int sheetPalette, int[] frameIndices) {
        return buildLevelArtSheetFromRomFiltered(mappingAddr, artTileBase, sheetPalette,
                frameIndices, S3kSpriteDataLoader.MappingFormat.STANDARD);
    }

    public ObjectSpriteSheet buildLevelArtSheetFromRomFiltered(int mappingAddr,
            int artTileBase, int sheetPalette, int[] frameIndices,
            S3kSpriteDataLoader.MappingFormat mappingFormat) {
        return buildLevelArtSheetFromRomFiltered(mappingAddr, artTileBase, sheetPalette,
                frameIndices, mappingFormat, -1);
    }

    public ObjectSpriteSheet buildLevelArtSheetFromRomFiltered(int mappingAddr,
            int artTileBase, int sheetPalette, int[] frameIndices,
            S3kSpriteDataLoader.MappingFormat mappingFormat, int mappingFrameCount) {
        if (reader == null || frameIndices == null || frameIndices.length == 0) return null;
        List<SpriteMappingFrame> allFrames = mappingFrameCount > 0
                ? S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr, mappingFrameCount, mappingFormat)
                : S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr, mappingFormat);
        if (allFrames.isEmpty()) return null;

        List<SpriteMappingFrame> selected = new ArrayList<>(frameIndices.length);
        for (int idx : frameIndices) {
            if (idx >= 0 && idx < allFrames.size()) {
                selected.add(allFrames.get(idx));
            }
        }
        if (selected.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : selected) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, sheetPalette, selected, minTile, maxTile);
    }

    /**
     * Builds AnimatedStillSprite sheet for LRZ subtype 2 (ceiling rock flicker).
     * art_tile = $0D3, palette 2. Animation frames 9-10.
     */
    public ObjectSpriteSheet buildAnimStillLrzD3Sheet(int artTileBase) {
        return buildAnimStillSheet(artTileBase, 2, 9, 10);
    }

    /**
     * Builds AnimatedStillSprite sheet for LRZ2 subtype 3 (torch flame).
     * art_tile = LRZ2Misc ($040D), palette 1. Animation frames 11-13.
     */
    public ObjectSpriteSheet buildAnimStillLrz2Sheet(int artTileBase) {
        return buildAnimStillSheet(artTileBase, 1, 11, 13);
    }

    /**
     * Builds AnimatedStillSprite sheet for SOZ subtypes 4-7 (torches).
     * art_tile = SOZMisc+$46 ($040F), palette 2. Animation frames 14-29.
     */
    public ObjectSpriteSheet buildAnimStillSozSheet(int artTileBase) {
        return buildAnimStillSheet(artTileBase, 2, 14, 29);
    }

    private ObjectSpriteSheet buildAnimStillSheet(int artTileBase, int palette,
            int firstFrame, int lastFrame) {
        if (reader == null) return null;
        List<SpriteMappingFrame> allFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_ANIMATED_STILL_SPRITES_ADDR);
        if (allFrames.isEmpty()) return null;

        List<SpriteMappingFrame> selected = new ArrayList<>();
        for (int i = firstFrame; i <= lastFrame && i < allFrames.size(); i++) {
            selected.add(allFrames.get(i));
        }
        if (selected.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : selected) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, palette, selected, minTile, maxTile);
    }

    // ===== Collapsing Platform sprite sheets (parsed from ROM) =====

    /**
     * Builds the AIZ Act 1 Collapsing Platform sprite sheet (Map_AIZCollapsingPlatform, 4 frames).
     * art_tile = make_art_tile($001, 2, 0) → base tile 1, palette 2.
     */
    public ObjectSpriteSheet buildCollapsingPlatformAiz1Sheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_COLLAPSING_PLATFORM_ADDR, 1, 2);
    }

    /**
     * Builds the AIZ Act 2 Collapsing Platform sprite sheet (Map_AIZCollapsingPlatform2, 4 frames).
     * art_tile = make_art_tile($001, 2, 0) → base tile 1, palette 2.
     */
    public ObjectSpriteSheet buildCollapsingPlatformAiz2Sheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_COLLAPSING_PLATFORM2_ADDR, 1, 2);
    }

    /**
     * Builds the ICZ Collapsing Platform sprite sheet (Map_ICZCollapsingBridge, 6 frames).
     * art_tile = make_art_tile($001, 2, 0) → base tile 1, palette 2.
     */
    public ObjectSpriteSheet buildCollapsingPlatformIczSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_ICZ_COLLAPSING_BRIDGE_ADDR, 1, 2);
    }

    // ===== AIZ Flipping Bridge sprite sheet (parsed from ROM) =====

    /**
     * Builds the AIZ Flipping Bridge sprite sheet (Map_AIZFlippingBridge, 32 frames).
     * art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 0) → base tile 0x2E9, palette 2.
     * <p>
     * Note: The mapping's first pointer entry (0x78) doesn't equal the table size (0x40),
     * so the auto-detect frame count method would compute 60 instead of 32. Uses explicit
     * frame count.
     */
    public ObjectSpriteSheet buildFlippingBridgeSheet(int artTileBase) {
        if (reader == null) return null;
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_AIZ_FLIPPING_BRIDGE_ADDR, 32);
        if (frames.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, 2, frames, minTile, maxTile);
    }

    /**
     * Builds the AIZ draw bridge sheet from ROM mappings.
     * ROM: Obj_AIZDrawBridge uses art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 1).
     * Map_AIZDrawBridge has 2 frames: frame 0 = empty, frame 1 = single 2x2 segment.
     */
    public ObjectSpriteSheet buildDrawBridgeSheet(int artTileBase) {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_AIZ_DRAW_BRIDGE_ADDR,
                artTileBase,
                2);
    }

    // ===== AIZ Disappearing Floor sprite sheets (parsed from ROM) =====

    /**
     * Builds the AIZ Disappearing Floor parent sprite sheet (Map_AIZDisappearingFloor, 6 frames).
     * art_tile = make_art_tile($001, 2, 0) → base tile 1, palette 2.
     * <p>
     * Note: Map_AIZDisappearingFloor and Map_AIZDisappearingFloor2 share a memory region
     * (interleaved offset tables), so auto-detect frame count would fail. Uses explicit count 6.
     */
    public ObjectSpriteSheet buildDisappearingFloorSheet(int artTileBase) {
        if (reader == null) return null;
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_AIZ_DISAPPEARING_FLOOR_ADDR, 6);
        if (frames.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, 2, frames, minTile, maxTile);
    }

    /**
     * Builds the AIZ Disappearing Floor water border sprite sheet
     * (Map_AIZDisappearingFloor2, 4 frames).
     * art_tile = make_art_tile(ArtTile_AIZMisc2, 3, 0) → base tile 0x2E9, palette 3.
     * <p>
     * Interleaved with Map_AIZDisappearingFloor; uses explicit count 4.
     */
    public ObjectSpriteSheet buildDisappearingFloorBorderSheet(int artTileBase) {
        if (reader == null) return null;
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_AIZ_DISAPPEARING_FLOOR_BORDER_ADDR, 4);
        if (frames.isEmpty()) return null;

        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
            }
        }
        if (minTile == Integer.MAX_VALUE) return null;

        return buildLevelArtSheet(artTileBase, 3, frames, minTile, maxTile);
    }

    // ===== AIZ Spiked Log sprite sheet (parsed from ROM) =====

    /**
     * Builds the AIZ Spiked Log sprite sheet (Map_AIZSpikedLog, 16 frames).
     * art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 0) → base tile 0x2E9, palette 2.
     */
    public ObjectSpriteSheet buildSpikedLogSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_SPIKED_LOG_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC2, 2);
    }

    // ===== AIZ Collapsing Log Bridge sprite sheets (parsed from ROM) =====

    /**
     * Builds the AIZ Collapsing Log Bridge sprite sheet (Map_AIZCollapsingLogBridge, 3 frames).
     * art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 0) → base tile 0x2E9, palette 2.
     */
    public ObjectSpriteSheet buildCollapsingLogBridgeSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_COLLAPSING_LOG_BRIDGE_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC2, 2);
    }

    /**
     * Builds the AIZ Draw Bridge Fire sprite sheet (Map_AIZDrawBridgeFire, 8 frames).
     * art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 1) → base tile 0x2E9, palette 2.
     * Note: fire animation frames (3-7) use palette 3 in the ROM via art_tile addition;
     * piece-level palette in the mapping data handles this.
     */
    public ObjectSpriteSheet buildDrawBridgeFireSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_DRAW_BRIDGE_FIRE_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC2, 2);
    }

    // ===== AIZ/LRZ Rock sprite sheets (parsed from ROM) =====

    /**
     * Builds the AIZ Act 1 Rock sprite sheet (Map_AIZRock, 7 frames).
     * art_tile = ArtTile_AIZ_Misc1 ($0333), palette 1.
     */
    public ObjectSpriteSheet buildAiz1RockSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_ROCK_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC1, 1);
    }

    /**
     * Builds the AIZ Act 2 Rock sprite sheet (Map_AIZRock2, 7 frames).
     * art_tile = ArtTile_AIZ_Misc2 ($02E9), palette 2.
     */
    public ObjectSpriteSheet buildAiz2RockSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_AIZ_ROCK2_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC2, 2);
    }

    /**
     * Builds the LRZ Act 1 Breakable Rock sprite sheet (Map_LRZBreakableRock, 11 frames).
     * art_tile = $00D3, palette 2.
     */
    public ObjectSpriteSheet buildLrz1RockSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_LRZ_BREAKABLE_ROCK_ADDR,
                0x00D3, 2);
    }

    /**
     * Builds the LRZ Act 2 Breakable Rock sprite sheet (Map_LRZBreakableRock2, 12 frames).
     * art_tile = ArtTile_LRZ2_MISC ($040D), palette 3.
     */
    public ObjectSpriteSheet buildLrz2RockSheet() {
        return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_LRZ_BREAKABLE_ROCK2_ADDR,
                Sonic3kConstants.ARTTILE_LRZ2_MISC, 3);
    }

    // ===== AIZ badnik dedicated-art sprite sheets =====

    /**
     * Loads Bloominator (Obj $8C) dedicated art sheet.
     * Art: ArtKosM_AIZ_Bloominator, map: Map_Bloominator.
     */
    public ObjectSpriteSheet loadBloominatorSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] patterns = loadKosinskiModuledPatterns(rom, Sonic3kConstants.ART_KOSM_AIZ_BLOOMINATOR_ADDR);
            if (patterns.length == 0) {
                return null;
            }
            List<SpriteMappingFrame> mappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_BLOOMINATOR_ADDR);
            return new ObjectSpriteSheet(patterns, mappings, 1, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading Bloominator art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads Monkey Dude (Obj $8E) dedicated art sheet.
     * Art: ArtKosM_AIZ_MonkeyDude, map: Map_MonkeyDude.
     */
    public ObjectSpriteSheet loadMonkeyDudeSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] patterns = loadKosinskiModuledPatterns(rom, Sonic3kConstants.ART_KOSM_AIZ_MONKEY_DUDE_ADDR);
            if (patterns.length == 0) {
                return null;
            }
            List<SpriteMappingFrame> mappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_MONKEY_DUDE_ADDR);
            return new ObjectSpriteSheet(patterns, mappings, 1, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading Monkey Dude art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads Rhinobot (Obj $8D) dedicated art sheet.
     * Uses object-format DPLC remap (Perform_DPLC path).
     */
    public ObjectSpriteSheet loadRhinobotSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] patterns = loadUncompressedPatterns(rom,
                    Sonic3kConstants.ART_UNC_AIZ_RHINOBOT_ADDR,
                    Sonic3kConstants.ART_UNC_AIZ_RHINOBOT_SIZE);
            if (patterns.length == 0) {
                return null;
            }
            List<SpriteMappingFrame> rawMappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_RHINOBOT_ADDR);
            List<SpriteDplcFrame> dplcFrames = loadObjectDplcFrames(reader, Sonic3kConstants.DPLC_RHINOBOT_ADDR);
            List<SpriteMappingFrame> remapped = applyDplcRemap(rawMappings, dplcFrames);
            return new ObjectSpriteSheet(patterns, remapped, 1, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading Rhinobot art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads the shared egg capsule / prison art used by S3K boss endings.
     */
    public ObjectSpriteSheet loadEggCapsuleSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] patterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_EGG_CAPSULE_ADDR);
            if (patterns == null || patterns.length == 0) {
                return null;
            }
            List<SpriteMappingFrame> mappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_EGG_CAPSULE_ADDR);
            return new ObjectSpriteSheet(patterns, mappings, 0, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading Egg Capsule art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads the dedicated CNZ teleporter beam sheet used by {@code Obj_CNZTeleporter}
     * and the shared {@code Obj_TeleporterBeam} route in CNZ.
     *
     * <p>ROM behavior:
     * {@code Obj_CNZTeleporter} queues {@code ArtKosM_CNZTeleport} directly rather
     * than relying on a zone PLC, then both the teleporter and beam objects render
     * through {@code Map_SSZHPZTeleporter}. Palette writes and control-lock
     * timing are owned by the concrete CNZ teleporter route objects.
     */
    public ObjectSpriteSheet loadCnzTeleporterSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] patterns = loadKosinskiModuledPatterns(rom, Sonic3kConstants.ART_KOSM_CNZ_TELEPORT_ADDR);
            if (patterns == null || patterns.length == 0) {
                return null;
            }
            List<SpriteMappingFrame> mappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_SSZ_HPZ_TELEPORTER_ADDR);
            return new ObjectSpriteSheet(patterns, mappings, 0, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading CNZ teleporter art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads a standalone art sheet from a registry entry.
     * Dispatches based on the entry's compression type and DPLC presence.
     */
    public ObjectSpriteSheet loadStandaloneSheet(Rom rom,
            Sonic3kPlcArtRegistry.StandaloneArtEntry entry) throws IOException {
        if (rom == null || reader == null) return null;

        Pattern[] patterns;
        switch (entry.compression()) {
            case KOSINSKI_MODULED ->
                patterns = loadKosinskiModuledPatterns(rom, entry.artAddr());
            case NEMESIS ->
                patterns = PatternDecompressor.nemesis(rom, entry.artAddr());
            case UNCOMPRESSED ->
                patterns = loadUncompressedPatterns(rom, entry.artAddr(), entry.artSize());
            default -> { return null; }
        }
        if (patterns == null || patterns.length == 0) return null;

        if (entry.mappingAddr() <= 0) {
            if (Sonic3kObjectArtKeys.MGZ_ENDBOSS_SCALED.equals(entry.key())) {
                List<SpriteMappingFrame> mappings =
                        S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_SCALED_ART_ADDR);
                Pattern[] generatedBank = new Pattern[0x100];
                for (int i = 0; i < generatedBank.length; i++) {
                    generatedBank[i] = new Pattern();
                }
                return new ObjectSpriteSheet(generatedBank, mappings, entry.palette(), 1);
            }
            LOG.warning("Standalone object art '" + entry.key() + "' has no ROM mapping address");
            return null;
        }

        List<SpriteMappingFrame> mappings = entry.mappingFrameCount() > 0
                ? S3kSpriteDataLoader.loadMappingFrames(reader, entry.mappingAddr(), entry.mappingFrameCount())
                : S3kSpriteDataLoader.loadMappingFrames(reader, entry.mappingAddr(), entry.mappingFormat());
        if (Sonic3kObjectArtKeys.CNZ_CLAMER_SHOT.equals(entry.key())) {
            mappings = List.of(mappings.get(9));
        } else if (Sonic3kObjectArtKeys.MGZ_ENDBOSS.equals(entry.key())) {
            mappings = selectFramesPreservingIndices(mappings,
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    0x0C, 0x0F,
                    0x12, 0x13, 0x14,
                    0x18, 0x19, 0x1A, 0x1B,
                    0x1E, 0x1F, 0x20,
                    0x23, 0x24, 0x25,
                    0x2E, 0x2F, 0x30);
        } else if (Sonic3kObjectArtKeys.MHZ_END_BOSS_PILLAR.equals(entry.key())) {
            mappings = selectFramesPreservingIndices(mappings, 0, 1);
        } else if (Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES.equals(entry.key())) {
            mappings = selectFramesPreservingIndices(mappings, 2, 3, 4);
        } else if (Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER.equals(entry.key())) {
            mappings = selectFramesPreservingIndices(mappings, 5, 6, 7);
        } else if (Sonic3kObjectArtKeys.HCZ_GEYSER_SPRAY.equals(entry.key())) {
            mappings = selectFramesPreservingIndices(mappings, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        } else if (Sonic3kObjectArtKeys.BUBBLER.equals(entry.key())) {
            mappings = blankFrames(mappings, 9, 18);
        } else if (Sonic3kObjectArtKeys.HCZ_WATER_SPLASH.equals(entry.key())) {
            mappings = offsetDmaWindowFrames(mappings, 24);
        }

        if (entry.dplcAddr() > 0) {
            List<SpriteDplcFrame> dplcFrames = loadObjectDplcFrames(reader, entry.dplcAddr());
            mappings = applyDplcRemap(mappings, dplcFrames);
        } else {
            if (entry.mappingTileOffset() != 0) {
                mappings = adjustTileIndices(mappings, entry.mappingTileOffset());
            } else {
                mappings = normalizeStandaloneMappings(entry, patterns, mappings);
            }
            patterns = padSparseStandalonePatterns(entry, patterns, mappings);
        }
        validateStandaloneMappings(entry, patterns, mappings);

        return new ObjectSpriteSheet(patterns, mappings, entry.palette(), 1);
    }

    private static Pattern[] padSparseStandalonePatterns(
            Sonic3kPlcArtRegistry.StandaloneArtEntry entry,
            Pattern[] patterns,
            List<SpriteMappingFrame> mappings) {
        if (!Sonic3kObjectArtKeys.GUMBALL_BONUS.equals(entry.key())
                && !Sonic3kObjectArtKeys.GUMBALL_SPRING.equals(entry.key())) {
            return patterns;
        }
        TileRange range = mappingTileRange(mappings);
        if (range.empty() || range.maxExclusive() <= patterns.length) {
            return patterns;
        }

        Pattern[] padded = Arrays.copyOf(patterns, range.maxExclusive());
        for (int i = patterns.length; i < padded.length; i++) {
            padded[i] = new Pattern();
        }
        return padded;
    }

    private static List<SpriteMappingFrame> normalizeStandaloneMappings(
            Sonic3kPlcArtRegistry.StandaloneArtEntry entry,
            Pattern[] patterns,
            List<SpriteMappingFrame> mappings) {
        TileRange range = mappingTileRange(mappings);
        if (range.empty() || range.maxExclusive() <= patterns.length) {
            return mappings;
        }
        int tileCount = range.maxExclusive() - range.min();
        if (range.min() <= 0 || tileCount > patterns.length) {
            return mappings;
        }
        LOG.fine("Normalizing standalone object art '" + entry.key()
                + "' mapping tiles 0x" + Integer.toHexString(range.min())
                + "-0x" + Integer.toHexString(range.maxExclusive() - 1)
                + " against " + patterns.length + " decompressed tiles");
        return adjustTileIndices(mappings, -range.min());
    }

    private static List<SpriteMappingFrame> selectFramesPreservingIndices(
            List<SpriteMappingFrame> mappings, int... frameIndices) {
        int maxFrame = -1;
        for (int frameIndex : frameIndices) {
            maxFrame = Math.max(maxFrame, frameIndex);
        }
        if (maxFrame < 0 || mappings == null || mappings.isEmpty()) {
            return mappings;
        }

        List<SpriteMappingFrame> selected = new ArrayList<>(maxFrame + 1);
        for (int i = 0; i <= maxFrame; i++) {
            selected.add(new SpriteMappingFrame(List.of()));
        }
        for (int frameIndex : frameIndices) {
            if (frameIndex >= 0 && frameIndex < mappings.size()) {
                selected.set(frameIndex, mappings.get(frameIndex));
            }
        }
        return selected;
    }

    private static List<SpriteMappingFrame> blankFrames(
            List<SpriteMappingFrame> mappings, int firstFrame, int lastFrame) {
        if (mappings == null || mappings.isEmpty()) {
            return mappings;
        }
        List<SpriteMappingFrame> adjusted = new ArrayList<>(mappings);
        for (int i = Math.max(0, firstFrame); i <= lastFrame && i < adjusted.size(); i++) {
            adjusted.set(i, new SpriteMappingFrame(List.of()));
        }
        return adjusted;
    }

    private static List<SpriteMappingFrame> offsetDmaWindowFrames(
            List<SpriteMappingFrame> mappings, int tilesPerFrame) {
        if (mappings == null || mappings.isEmpty() || tilesPerFrame == 0) {
            return mappings;
        }
        List<SpriteMappingFrame> adjusted = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            adjusted.add(adjustTileIndices(List.of(mappings.get(i)), i * tilesPerFrame).get(0));
        }
        return adjusted;
    }

    private static void validateStandaloneMappings(
            Sonic3kPlcArtRegistry.StandaloneArtEntry entry,
            Pattern[] patterns,
            List<SpriteMappingFrame> mappings) throws IOException {
        TileRange range = mappingTileRange(mappings);
        if (range.empty()) {
            return;
        }
        if (range.min() < 0 || range.maxExclusive() > patterns.length) {
            throw new IOException("Standalone art '" + entry.key()
                    + "' has mapping tile range 0x" + Integer.toHexString(range.min())
                    + "-0x" + Integer.toHexString(range.maxExclusive() - 1)
                    + " outside decompressed " + entry.compression()
                    + " art at 0x" + Integer.toHexString(entry.artAddr())
                    + " (" + patterns.length + " tiles)");
        }
    }

    private static TileRange mappingTileRange(List<SpriteMappingFrame> frames) {
        int minTile = Integer.MAX_VALUE;
        int maxTile = Integer.MIN_VALUE;
        if (frames == null) {
            return TileRange.emptyRange();
        }
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                maxTile = Math.max(maxTile,
                        piece.tileIndex() + (piece.widthTiles() * piece.heightTiles()));
            }
        }
        if (minTile == Integer.MAX_VALUE) {
            return TileRange.emptyRange();
        }
        return new TileRange(minTile, maxTile);
    }

    private record TileRange(int min, int maxExclusive) {
        private static TileRange emptyRange() {
            return new TileRange(0, 0);
        }

        private boolean empty() {
            return maxExclusive <= min;
        }
    }

    // ===== Results Screen art loading =====

    /**
     * Loads all results screen art from ROM into a combined pattern array
     * covering the VRAM range $520-$61F (256 tiles).
     *
     * <p>Layout matches the ROM's Load_EndOfAct routine:
     * <ol>
     *   <li>General art ("GOT THROUGH", bonus labels) at VRAM $520 (index 0)</li>
     *   <li>Number art at VRAM $568 (index $48). Uses Num1 for act 1 (non-DDZ), Num2 otherwise</li>
     *   <li>Character name art at VRAM $578 (act 1) or $5A0 (act 2), selected by character</li>
     * </ol>
     *
     * @param character the player character, determines which name art to load
     * @param act       act index (0 = act 1, 1 = act 2)
     * @return pattern array of size {@link Sonic3kConstants#VRAM_RESULTS_ARRAY_SIZE}, or null on failure
     */
    public Pattern[] loadResultsArt(PlayerCharacter character, int act) {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return null;
            Pattern[] patterns = new Pattern[Sonic3kConstants.VRAM_RESULTS_ARRAY_SIZE];
            Pattern empty = new Pattern();
            Arrays.fill(patterns, empty);

            // 1. General art → VRAM $520 (index 0)
            loadKosmArtInto(rom, Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR,
                    patterns, 0);

            // 2. Number art → VRAM $568 (index $48)
            // Use Num1 when act == 0 AND zone != 0x16 (DDZ), else Num2
            int zone = GameServices.level().getCurrentZone();
            int numArtAddr = (act == 0 && zone != 0x16)
                    ? Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR
                    : Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR;
            loadKosmArtInto(rom, numArtAddr, patterns,
                    Sonic3kConstants.VRAM_RESULTS_NUMBERS - Sonic3kConstants.VRAM_RESULTS_BASE);

            // 3. Character name art → VRAM $578 (act 1) or $5A0 (act 2)
            int charArtAddr = getCharacterNameArtAddr(character);
            int charDestVram = (act == 0)
                    ? Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1
                    : Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2;
            loadKosmArtInto(rom, charArtAddr, patterns,
                    charDestVram - Sonic3kConstants.VRAM_RESULTS_BASE);

            return patterns;
        } catch (Exception e) {
            LOG.warning("Failed to load results screen art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Queues the three ROM KosM archives owned by {@code Obj_LevelResultsInit}.
     */
    public QueuedResultsArt queueResultsArt(
            Rom rom,
            PlayerCharacter character,
            int act,
            int zone,
            S3kKosModuleQueue queue) throws IOException {
        int numArtAddr = (act == 0 && zone != 0x16)
                ? Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR
                : Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR;
        int charDestVram = act == 0
                ? Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1
                : Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2;
        List<HardwareWorkHandle> handles = List.of(
                queue.queue(
                        rom,
                        Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR,
                        Sonic3kConstants.VRAM_RESULTS_BASE),
                queue.queue(
                        rom,
                        numArtAddr,
                        Sonic3kConstants.VRAM_RESULTS_NUMBERS),
                queue.queue(
                        rom,
                        getCharacterNameArtAddr(character),
                        charDestVram));
        return new QueuedResultsArt(
                queue,
                handles,
                resultsArtDestinations(charDestVram));
    }

    /**
     * Reassembles transient results-screen patterns from payloads retained by
     * already-claimed hardware jobs after rewind.
     */
    public static Pattern[] assembleClaimedResultsArt(
            List<byte[]> payloads, int act) {
        int charDestVram = act == 0
                ? Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1
                : Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2;
        return assembleResultsPatterns(
                payloads, resultsArtDestinations(charDestVram));
    }

    private static int[] resultsArtDestinations(int charDestVram) {
        return new int[] {
                0,
                Sonic3kConstants.VRAM_RESULTS_NUMBERS
                        - Sonic3kConstants.VRAM_RESULTS_BASE,
                charDestVram - Sonic3kConstants.VRAM_RESULTS_BASE
        };
    }

    private static Pattern[] assembleResultsPatterns(
            List<byte[]> payloads, int[] destinations) {
        if (payloads.size() != 3 || destinations.length != 3) {
            throw new IllegalArgumentException(
                    "results art assembly requires exactly three payloads");
        }
        Pattern[] patterns =
                new Pattern[Sonic3kConstants.VRAM_RESULTS_ARRAY_SIZE];
        Pattern empty = new Pattern();
        Arrays.fill(patterns, empty);
        for (int i = 0; i < payloads.size(); i++) {
            placePatterns(payloads.get(i), patterns, destinations[i]);
        }
        return patterns;
    }

    private static void placePatterns(
            byte[] data,
            Pattern[] patterns,
            int destination) {
        int tileCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        for (int tile = 0; tile < tileCount; tile++) {
            int index = destination + tile;
            if (index < 0 || index >= patterns.length) {
                continue;
            }
            byte[] tileData = Arrays.copyOfRange(
                    data,
                    tile * Pattern.PATTERN_SIZE_IN_ROM,
                    (tile + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            Pattern pattern = new Pattern();
            pattern.fromSegaFormat(tileData);
            patterns[index] = pattern;
        }
    }

    public static final class QueuedResultsArt {
        private final S3kKosModuleQueue queue;
        private final List<HardwareWorkHandle> handles;
        private final int[] destinations;

        private QueuedResultsArt(
                S3kKosModuleQueue queue,
                List<HardwareWorkHandle> handles,
                int[] destinations) {
            this.queue = queue;
            this.handles = List.copyOf(handles);
            this.destinations = destinations.clone();
        }

        /**
         * Rebinds a results owner to jobs already present in the restored
         * session timing ledger. This deliberately does not submit work.
         */
        public static QueuedResultsArt restore(
                S3kKosModuleQueue queue,
                List<HardwareWorkHandle> handles,
                int[] destinations) {
            if (handles.size() != 3 || destinations.length != 3) {
                throw new IllegalArgumentException(
                        "results art restore requires exactly three jobs");
            }
            return new QueuedResultsArt(queue, handles, destinations);
        }

        public List<HardwareWorkHandle> handles() {
            return handles;
        }

        public boolean isReady() {
            return handles.stream().allMatch(queue::isReady);
        }

        public Pattern[] claim() {
            if (!isReady()) {
                throw new IllegalStateException("results KosM art is not ready");
            }
            return assembleResultsPatterns(
                    handles.stream().map(queue::claim).toList(),
                    destinations);
        }

    }

    /**
     * Loads the results screen palette (128 bytes = 4 lines x 16 colors).
     *
     * @return raw palette bytes, or null on failure
     */
    public byte[] loadResultsPalette() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return null;
            return rom.readBytes(Sonic3kConstants.PAL_RESULTS_ADDR, 128);
        } catch (Exception e) {
            LOG.warning("Failed to load results palette: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses results screen mapping frames from ROM.
     *
     * @return list of sprite mapping frames (59 entries), or empty list on failure
     */
    public List<SpriteMappingFrame> loadResultsMappings() {
        if (reader == null) return List.of();
        try {
            return S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_RESULTS_ADDR);
        } catch (Exception e) {
            LOG.warning("Failed to load results mappings: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Load special stage results screen art from ROM.
     * <p>
     * ROM loads art at the SS-specific VRAM positions ($4F1, $523, $5B8, $6BC),
     * NOT the level results positions. Per-object art_tile offsets in the results
     * screen code compensate for the difference between these positions and the
     * tile references in Map_Results.
     * <p>
     * Layout (base = $4F1):
     * <ul>
     *   <li>$4F1 (index 0): Character name art</li>
     *   <li>$523 (index $32): SS results text art</li>
     *   <li>$5B8 (index $C7): General results art</li>
     *   <li>$6BC (index $1CB): HUD text art</li>
     * </ul>
     *
     * @param character the player character
     * @return pattern array covering VRAM range $4F1-$7F0, or null on failure
     */
    public Pattern[] loadSSResultsArt(PlayerCharacter character) {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return null;

            int base = Sonic3kConstants.VRAM_SS_RESULTS_BASE; // $4F1
            Pattern[] patterns = new Pattern[Sonic3kConstants.VRAM_SS_RESULTS_ARRAY_SIZE];
            Pattern empty = new Pattern();
            Arrays.fill(patterns, empty);

            // 1. Character name → VRAM $4F1 (index 0)
            loadKosmArtInto(rom, getCharacterNameArtAddr(character), patterns,
                    Sonic3kConstants.VRAM_SS_RESULTS_CHAR_NAME - base);

            // 2. SS results text → VRAM $523 (index $32)
            loadKosmArtInto(rom, Sonic3kConstants.ART_KOSM_SS_RESULTS_ADDR, patterns,
                    Sonic3kConstants.VRAM_SS_RESULTS_TEXT - base);

            // 3. General results art → VRAM $5B8 (index $C7)
            loadKosmArtInto(rom, Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR, patterns,
                    Sonic3kConstants.VRAM_SS_RESULTS_GENERAL - base);

            // 4. Ring/HUD text → VRAM $6BC (index $1CB, Nemesis compressed)
            loadNemesisArtInto(rom, Sonic3kConstants.ART_NEM_RING_HUD_TEXT_ADDR, patterns,
                    Sonic3kConstants.VRAM_SS_RESULTS_HUD_TEXT - base);

            // 5. Mirror HUD_DrawInitial, which overwrites $6E2+ with large HUD digits.
            // This is what gives frame $31 its correct "E" plus blank/zero suffix tiles.
            overlaySpecialStageHudInitial(rom, patterns, base);

            return patterns;
        } catch (Exception e) {
            LOG.warning("Failed to load SS results screen art: " + e.getMessage());
            return null;
        }
    }

    // ROM: HUD_DrawInitial reads HUD_Initial_Parts then HUD_Zero_Rings,
    // producing 15 8x16 glyph uploads: "E      00:00  0"
    private static final char[] HUD_INITIAL_GLYPHS =
            {'E', ' ', ' ', ' ', ' ', ' ', ' ', '0', '0', ':', '0', '0', ' ', ' ', '0'};

    private void overlaySpecialStageHudInitial(Rom rom, Pattern[] dest, int base)
            throws IOException {
        Pattern[] hudDigits = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_SIZE);
        int destIndex = Sonic3kConstants.VRAM_SS_RESULTS_HUD_INITIAL - base;

        for (char glyph : HUD_INITIAL_GLYPHS) {
            writeHudInitialGlyph(dest, destIndex, glyph, hudDigits);
            destIndex += 2;
        }
    }

    private void writeHudInitialGlyph(Pattern[] dest, int destIndex, char glyph,
            Pattern[] hudDigits) {
        int srcIndex = switch (glyph) {
            case '0' -> 0;
            case ':' -> 0x14;
            case 'E' -> 0x16;
            default -> -1;
        };
        // Each position needs its own Pattern instance (Pattern is mutable and
        // downstream code may modify individual tiles in-place).
        Pattern top = (srcIndex >= 0) ? hudDigits[srcIndex] : new Pattern();
        Pattern bottom = (srcIndex >= 0 && srcIndex + 1 < hudDigits.length)
                ? hudDigits[srcIndex + 1] : new Pattern();
        if (destIndex >= 0 && destIndex + 1 < dest.length) {
            dest[destIndex] = top;
            dest[destIndex + 1] = bottom;
        }
    }

    /**
     * Decompresses Nemesis art from ROM and places patterns into a destination array.
     */
    private void loadNemesisArtInto(Rom rom, int romAddr, Pattern[] dest, int destIndex)
            throws IOException {
        loadNemesisArtInto(rom, romAddr, dest, destIndex, -1);
    }

    /**
     * Decompresses Nemesis art from ROM and places patterns into a destination array.
     * @param maxTiles maximum tiles to load, or -1 for all
     */
    private void loadNemesisArtInto(Rom rom, int romAddr, Pattern[] dest, int destIndex, int maxTiles)
            throws IOException {
        FileChannel channel = rom.getFileChannel();
        // Rom exposes a shared FileChannel; lock around seek+decode so concurrent
        // readers cannot move the channel position mid-stream.
        byte[] data;
        synchronized (rom) {
            channel.position(romAddr);
            data = NemesisReader.decompress(channel);
        }
        int tileCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (maxTiles >= 0) tileCount = Math.min(tileCount, maxTiles);
        for (int i = 0; i < tileCount; i++) {
            int idx = destIndex + i;
            if (idx >= 0 && idx < dest.length) {
                byte[] tileData = Arrays.copyOfRange(data,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                Pattern pat = new Pattern();
                pat.fromSegaFormat(tileData);
                dest[idx] = pat;
            }
        }
    }

    private int getCharacterNameArtAddr(PlayerCharacter character) {
        return switch (character) {
            case SONIC_AND_TAILS, SONIC_ALONE -> Sonic3kConstants.ART_KOSM_RESULTS_SONIC_ADDR;
            case TAILS_ALONE -> Sonic3kConstants.ART_KOSM_RESULTS_TAILS_ADDR;
            case KNUCKLES -> Sonic3kConstants.ART_KOSM_RESULTS_KNUCKLES_ADDR;
        };
    }

    /**
     * Decompresses KosinskiM art from ROM and places patterns into a destination array.
     * Follows the same pattern as {@code Sonic3kTitleCardManager.loadKosmArt()}.
     */
    private void loadKosmArtInto(Rom rom, int romAddr, Pattern[] dest, int destIndex)
            throws IOException {
        byte[] header = rom.readBytes(romAddr, 2);
        int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);
        long romSize = rom.getSize();
        if (romAddr + inputSize > romSize) {
            inputSize = (int) (romSize - romAddr);
        }

        byte[] romData = rom.readBytes(romAddr, inputSize);
        byte[] decompressed = KosinskiReader.decompressModuled(romData, 0);

        int tileCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
        for (int i = 0; i < tileCount; i++) {
            int idx = destIndex + i;
            if (idx >= 0 && idx < dest.length) {
                byte[] tileData = Arrays.copyOfRange(decompressed,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                Pattern pat = new Pattern();
                pat.fromSegaFormat(tileData);
                dest[idx] = pat;
            }
        }
    }


    private Pattern[] loadKosinskiModuledPatterns(Rom rom, int romAddr) throws IOException {
        byte[] header = rom.readBytes(romAddr, 2);
        if (header.length < 2) {
            return new Pattern[0];
        }
        int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);
        long romSize = rom.getSize();
        if (romAddr + inputSize > romSize) {
            inputSize = (int) (romSize - romAddr);
        }
        byte[] compressed = rom.readBytes(romAddr, inputSize);
        byte[] data = KosinskiReader.decompressModuled(compressed, 0);
        return bytesToPatterns(data);
    }

    private Pattern[] loadUncompressedPatterns(Rom rom, int romAddr, int size) throws IOException {
        byte[] data = rom.readBytes(romAddr, size);
        return bytesToPatterns(data);
    }

    private Pattern[] bytesToPatterns(byte[] data) {
        return PatternDecompressor.fromBytes(data);
    }

    private Pattern[] buildCnzCannonMixedPatterns(Pattern[] cannonPatterns) {
        int patternCount = CNZ_CANNON_SOURCE_BANK + cannonPatterns.length;
        Pattern[] patterns = new Pattern[patternCount];
        int levelPatternCount = level.getPatternCount();
        for (int i = 0; i < CNZ_CANNON_SOURCE_BANK; i++) {
            int levelIndex = Sonic3kConstants.ARTTILE_CNZ_CANNON + i;
            patterns[i] = levelIndex < levelPatternCount ? level.getPattern(levelIndex) : new Pattern();
        }
        System.arraycopy(cannonPatterns, 0, patterns, CNZ_CANNON_SOURCE_BANK, cannonPatterns.length);
        return patterns;
    }

    /**
     * S3K object DPLC parser (Perform_DPLC format):
     * startTile in upper 12 bits, (count-1) in lower 4 bits.
     */
    private static List<SpriteDplcFrame> loadObjectDplcFrames(RomByteReader reader, int dplcAddr) {
        int offsetTableSize = reader.readU16BE(dplcAddr);
        int frameCount = offsetTableSize / 2;

        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = dplcAddr + reader.readU16BE(dplcAddr + i * 2);
            int requestCount = reader.readU16BE(frameAddr) + 1;
            frameAddr += 2;

            List<TileLoadRequest> requests = new ArrayList<>(requestCount);
            for (int r = 0; r < requestCount; r++) {
                int entry = reader.readU16BE(frameAddr);
                frameAddr += 2;
                int startTile = (entry >> 4) & 0xFFF;
                int count = (entry & 0xF) + 1;
                requests.add(new TileLoadRequest(startTile, count));
            }
            frames.add(new SpriteDplcFrame(requests));
        }
        return frames;
    }

    /**
     * Remaps mapping tile indices through object DPLC requests.
     */
    private static List<SpriteMappingFrame> applyDplcRemap(
            List<SpriteMappingFrame> mappings, List<SpriteDplcFrame> dplcFrames) {
        if (dplcFrames == null || dplcFrames.isEmpty()) {
            return mappings;
        }

        List<SpriteMappingFrame> remapped = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            SpriteMappingFrame frame = mappings.get(i);
            if (i >= dplcFrames.size()) {
                remapped.add(frame);
                continue;
            }

            SpriteDplcFrame dplc = dplcFrames.get(i);
            int totalSlots = 0;
            for (TileLoadRequest req : dplc.requests()) {
                totalSlots += req.count();
            }

            int[] vramToSource = new int[totalSlots];
            int slot = 0;
            for (TileLoadRequest req : dplc.requests()) {
                for (int t = 0; t < req.count(); t++) {
                    vramToSource[slot++] = req.startTile() + t;
                }
            }

            List<SpriteMappingPiece> remappedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileIdx = piece.tileIndex();
                int wTiles = piece.widthTiles();
                int hTiles = piece.heightTiles();
                int tileCount = wTiles * hTiles;

                if (tileIdx < 0 || tileIdx >= vramToSource.length) {
                    remappedPieces.add(piece);
                    continue;
                }

                int remappedBase = vramToSource[tileIdx];
                boolean contiguous = true;
                for (int t = 1; t < tileCount; t++) {
                    int vramSlot = tileIdx + t;
                    if (vramSlot >= vramToSource.length || vramToSource[vramSlot] != remappedBase + t) {
                        contiguous = false;
                        break;
                    }
                }

                if (contiguous) {
                    remappedPieces.add(new SpriteMappingPiece(
                            piece.xOffset(), piece.yOffset(),
                            wTiles, hTiles,
                            remappedBase, piece.hFlip(), piece.vFlip(),
                            piece.paletteIndex(), piece.priority()));
                    continue;
                }

                for (int tx = 0; tx < wTiles; tx++) {
                    for (int ty = 0; ty < hTiles; ty++) {
                        int tileOffset = tx * hTiles + ty;
                        int vramSlot = tileIdx + tileOffset;
                        int remappedTile = vramSlot < vramToSource.length
                                ? vramToSource[vramSlot]
                                : tileIdx + tileOffset;
                        remappedPieces.add(new SpriteMappingPiece(
                                piece.xOffset() + tx * 8,
                                piece.yOffset() + ty * 8,
                                1, 1,
                                remappedTile, piece.hFlip(), piece.vFlip(),
                                piece.paletteIndex(), piece.priority()));
                    }
                }
            }
            remapped.add(new SpriteMappingFrame(remappedPieces));
        }
        return remapped;
    }

    private static List<SpriteMappingFrame> applyDplcRemapWithDestinationBase(
            List<SpriteMappingFrame> mappings,
            List<SpriteDplcFrame> dplcFrames,
            int destinationBaseTile,
            int sourceBankTile) {
        if (dplcFrames == null || dplcFrames.isEmpty()) {
            return mappings;
        }

        List<SpriteMappingFrame> remapped = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            SpriteMappingFrame frame = mappings.get(i);
            if (i >= dplcFrames.size()) {
                remapped.add(frame);
                continue;
            }

            SpriteDplcFrame dplc = dplcFrames.get(i);
            int totalSlots = 0;
            for (TileLoadRequest req : dplc.requests()) {
                totalSlots += req.count();
            }

            int[] vramToSource = new int[totalSlots];
            int slot = 0;
            for (TileLoadRequest req : dplc.requests()) {
                for (int t = 0; t < req.count(); t++) {
                    vramToSource[slot++] = sourceBankTile + req.startTile() + t;
                }
            }

            List<SpriteMappingPiece> remappedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                int relativeDest = piece.tileIndex() - destinationBaseTile;
                int remappedBase = relativeDest >= 0 && relativeDest < vramToSource.length
                        ? vramToSource[relativeDest]
                        : piece.tileIndex();
                remappedPieces.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        remappedBase, piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()));
            }
            remapped.add(new SpriteMappingFrame(remappedPieces));
        }
        return remapped;
    }

    private static List<SpriteMappingFrame> combineCnzCannonChildAndBaseFrames(
            List<SpriteMappingFrame> frames) {
        if (frames == null || frames.size() <= CNZ_CANNON_BASE_FRAME) {
            return frames;
        }

        SpriteMappingFrame baseFrame = frames.get(CNZ_CANNON_BASE_FRAME);
        List<SpriteMappingFrame> combined = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            SpriteMappingFrame frame = frames.get(i);
            if (i >= CNZ_CANNON_BASE_FRAME) {
                combined.add(frame);
                continue;
            }

            List<SpriteMappingPiece> pieces = new ArrayList<>(
                    frame.pieces().size() + baseFrame.pieces().size());
            // ROM submits the parent/base sprite before the child chamber sprite;
            // earlier sprite slots render in front.
            pieces.addAll(baseFrame.pieces());
            pieces.addAll(frame.pieces());
            combined.add(new SpriteMappingFrame(pieces));
        }
        return combined;
    }

    private static SpriteMappingFrame singlePieceFrame(
            int xOffset, int yOffset, int widthTiles, int heightTiles, int tileIndex, boolean hFlip) {
        SpriteMappingPiece piece = new SpriteMappingPiece(
                xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, false, 0);
        return new SpriteMappingFrame(List.of(piece));
    }

    public static List<SpriteMappingFrame> adjustTileIndices(List<SpriteMappingFrame> frames, int adjustment) {
        if (adjustment == 0) {
            return frames;
        }
        List<SpriteMappingFrame> adjusted = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> adjustedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                adjustedPieces.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        piece.tileIndex() + adjustment,
                        piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()
                ));
            }
            adjusted.add(new SpriteMappingFrame(adjustedPieces));
        }
        return adjusted;
    }

    /**
     * Builds the CNZ Balloon sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZBalloon}.
     * <p>Mapping table: {@code Map_CNZBalloon} (25 frames).
     * <p>Art tile: {@code ArtTile_CNZMisc} (palette 0). Frames 20-24 also
     * reference the separately PLC-loaded {@code ArtTile_CNZBalloon} body art
     * at {@code ArtTile_CNZMisc+$223}; the string pieces remain in CNZ misc.
     * <p>The mapping address is the final S3K lock-on offset published in
     * {@link Sonic3kConstants}; it is not the raw Sonic 3-side disassembly
     * address.
     */
    public ObjectSpriteSheet buildCnzBalloonSheet() {
        ObjectSpriteSheet sheet = buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_BALLOON_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_BALLOON,
                0);
        spliceCnzBalloonPlcArt(sheet);
        return sheet;
    }

    private void spliceCnzBalloonPlcArt(ObjectSpriteSheet sheet) {
        if (sheet == null) {
            return;
        }
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            Pattern[] plcPatterns = loadKosinskiModuledPatterns(rom, Sonic3kConstants.ART_KOSM_CNZ_BALLOON_ADDR);
            Pattern[] sheetPatterns = sheet.getPatterns();
            int dest = Sonic3kConstants.ARTTILE_CNZ_BALLOON_PLC - Sonic3kConstants.ARTTILE_CNZ_BALLOON;
            int count = Math.min(plcPatterns.length, sheetPatterns.length - dest);
            if (count > 0) {
                System.arraycopy(plcPatterns, 0, sheetPatterns, dest, count);
            }
        } catch (IOException e) {
            LOG.warning("Failed splicing CNZ balloon PLC art: " + e.getMessage());
        }
    }

    /**
     * Loads the CNZ Cannon sprite sheet from the ROM's dedicated Cannon.bin art.
     *
     * <p>Verified ROM anchors:
     * <ul>
     *   <li>{@code Obj_CNZCannon}</li>
     *   <li>{@code Map_CNZCannon} at the final lock-on offset published in
     *   {@link Sonic3kConstants#MAP_CNZ_CANNON_ADDR}</li>
     *   <li>{@code DPLC_CNZCannon} at {@link Sonic3kConstants#DPLC_CNZ_CANNON_ADDR}</li>
     *   <li>Dedicated art block {@code Cannon.bin} at
     *   {@link Sonic3kConstants#ART_UNC_CNZ_CANNON_ADDR}</li>
     * </ul>
     *
     * <p>The sheet is built from ROM-parsed mappings plus the DPLC remap table so
     * the animated chamber pieces keep their original tile selection instead of
     * relying on a level-art subset.
     */
    public ObjectSpriteSheet loadCnzCannonSheet(Rom rom) {
        if (rom == null || reader == null) {
            return null;
        }
        try {
            Pattern[] cannonPatterns = loadUncompressedPatterns(rom,
                    Sonic3kConstants.ART_UNC_CNZ_CANNON_ADDR,
                    Sonic3kConstants.ART_UNC_CNZ_CANNON_SIZE);
            if (cannonPatterns.length == 0 || level == null) {
                return null;
            }

            List<SpriteMappingFrame> rawMappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_CNZ_CANNON_ADDR, 10);
            List<SpriteDplcFrame> dplcFrames =
                    S3kSpriteDataLoader.loadDplcFrames(reader, Sonic3kConstants.DPLC_CNZ_CANNON_ADDR, 9);
            List<SpriteMappingFrame> remapped = applyDplcRemapWithDestinationBase(
                    rawMappings,
                    dplcFrames,
                    CNZ_CANNON_DPLC_DEST_TILE,
                    CNZ_CANNON_SOURCE_BANK);
            remapped = combineCnzCannonChildAndBaseFrames(remapped);
            Pattern[] patterns = buildCnzCannonMixedPatterns(cannonPatterns);

            lastBuildStartTile = Sonic3kConstants.ARTTILE_CNZ_CANNON;
            lastBuildTileCount = patterns.length;
            return new ObjectSpriteSheet(patterns, remapped, 2, 1);
        } catch (IOException e) {
            LOG.warning("Failed loading CNZ cannon art: " + e.getMessage());
            return null;
        }
    }

    /**
     * Backwards-compatible wrapper used by the registry builder path.
     */
    public ObjectSpriteSheet buildCnzCannonSheet() {
        try {
            return loadCnzCannonSheet(GameServices.rom().getRom());
        } catch (IOException e) {
            LOG.warning("Failed to load CNZ cannon art via compatibility wrapper: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds the CNZ Rising Platform sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZRisingPlatform}.
     * <p>Mapping table: {@code Map_CNZRisingPlatform} (3 frames).
     * <p>Art tile: {@code ArtTile_CNZMisc+$6D} (palette 2).
     * <p>The mapping address is the final S3K lock-on offset published in
     * {@link Sonic3kConstants}; the table is shared by both the idle and rising
     * animation states.
     */
    public ObjectSpriteSheet buildCnzRisingPlatformSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_RISING_PLATFORM_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_RISING_PLATFORM,
                2);
    }

    /**
     * Builds the CNZ Trap Door sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZTrapDoor}.
     * <p>Mapping table: {@code Map_CNZTrapDoor} (3 frames).
     * <p>Art tile: {@code ArtTile_CNZMisc+$9F} (palette 2).
     */
    public ObjectSpriteSheet buildCnzTrapDoorSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_TRAP_DOOR_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_TRAP_DOOR,
                2);
    }

    /**
     * Builds the CNZ Light Bulb sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZLightBulb}.
     * <p>Mapping table: {@code Map_CNZLightBulb} (2 frames).
     * <p>Art tile: {@code ArtTile_CNZMisc+$B3} (palette 2).
     */
    public ObjectSpriteSheet buildCnzLightBulbSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_LIGHT_BULB_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_LIGHT_BULB,
                2);
    }

    /**
     * Builds the CNZ Hover Fan sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZHoverFan}.
     * <p>Mapping table: {@code Map_CNZHoverFan} (8 frames, repeated idle frames).
     * <p>Art tile: {@code ArtTile_CNZMisc+$97} (palette 2).
     */
    public ObjectSpriteSheet buildCnzHoverFanSheet() {
        if (reader == null || level == null) {
            lastBuildStartTile = -1;
            lastBuildTileCount = -1;
            lastBuildTileRanges = List.of();
            return null;
        }

        List<SpriteMappingFrame> rawFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_CNZ_HOVER_FAN_ADDR);
        int artTileWord = Sonic3kConstants.ARTTILE_CNZ_HOVER_FAN | (2 << 13);
        Map<Integer, Integer> compactTiles = new LinkedHashMap<>();
        List<SpriteMappingFrame> resolvedFrames = new ArrayList<>(rawFrames.size());

        int minSourceTile = Integer.MAX_VALUE;
        int maxSourceTile = Integer.MIN_VALUE;
        for (SpriteMappingFrame rawFrame : rawFrames) {
            List<SpriteMappingPiece> resolvedPieces = new ArrayList<>(rawFrame.pieces().size());
            for (SpriteMappingPiece rawPiece : rawFrame.pieces()) {
                SpriteMappingPiece resolvedPiece = SpriteMappingPieces.withTileWord(
                        rawPiece, (SpriteMappingPieces.toTileWord(rawPiece) + artTileWord) & 0xFFFF);
                int tileCount = resolvedPiece.widthTiles() * resolvedPiece.heightTiles();
                int compactStart = -1;
                for (int tileOffset = 0; tileOffset < tileCount; tileOffset++) {
                    int sourceTile = (resolvedPiece.tileIndex() + tileOffset) & 0x7FF;
                    int compactIndex = compactTiles.computeIfAbsent(sourceTile, ignored -> compactTiles.size());
                    if (tileOffset == 0) {
                        compactStart = compactIndex;
                    }
                    minSourceTile = Math.min(minSourceTile, sourceTile);
                    maxSourceTile = Math.max(maxSourceTile, sourceTile);
                }
                resolvedPieces.add(SpriteMappingPieces.withTileIndex(resolvedPiece, compactStart));
            }
            resolvedFrames.add(new SpriteMappingFrame(resolvedPieces));
        }

        Pattern[] patterns = new Pattern[compactTiles.size()];
        for (Map.Entry<Integer, Integer> entry : compactTiles.entrySet()) {
            int sourceTile = entry.getKey();
            patterns[entry.getValue()] = sourceTile < level.getPatternCount()
                    ? level.getPattern(sourceTile)
                    : new Pattern();
        }
        lastBuildStartTile = minSourceTile;
        lastBuildTileCount = maxSourceTile - minSourceTile + 1;
        lastBuildTileRanges = contiguousTileRanges(compactTiles.keySet());
        // The full art-tile addition above has already resolved each piece's
        // palette bits. Supplying a sheet base here would add that palette a
        // second time in SpritePieceRenderer.
        return new ObjectSpriteSheet(patterns, resolvedFrames, 0, 1);
    }

    private static List<Sonic3kPlcLoader.TileRange> contiguousTileRanges(Iterable<Integer> tiles) {
        List<Integer> sortedTiles = new ArrayList<>();
        for (int tile : tiles) {
            sortedTiles.add(tile);
        }
        sortedTiles.sort(Integer::compareTo);
        if (sortedTiles.isEmpty()) {
            return List.of();
        }

        List<Sonic3kPlcLoader.TileRange> ranges = new ArrayList<>();
        int start = sortedTiles.getFirst();
        int previous = start;
        for (int index = 1; index < sortedTiles.size(); index++) {
            int tile = sortedTiles.get(index);
            if (tile != previous + 1) {
                ranges.add(new Sonic3kPlcLoader.TileRange(start, previous - start + 1));
                start = tile;
            }
            previous = tile;
        }
        ranges.add(new Sonic3kPlcLoader.TileRange(start, previous - start + 1));
        return List.copyOf(ranges);
    }

    /**
     * Builds the CNZ Cylinder sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_CNZCylinder}.
     * <p>Mapping table: {@code Map_CNZCylinder} (4 frames), parsed directly from
     * {@code Map - Cylinder.asm} rather than falling back to a hand-authored
     * mapping transcription.
     * <p>Art tile: {@code ArtTile_CNZMisc+$3D} (palette 2).
     */
    public ObjectSpriteSheet buildCnzCylinderSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_CYLINDER_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_CYLINDER,
                2);
    }

    /**
     * Builds the CNZ Bumper sprite sheet.
     *
     * <p>ROM anchor: {@code Obj_Bumper}, non-Pachinko/non-competition path.
     * <p>Mapping table: {@code Map_Bumper} (2 frames).
     * <p>Art tile: {@code ArtTile_CNZMisc+$13} (palette 2).
     */
    public ObjectSpriteSheet buildCnzBumperSheet() {
        return buildLevelArtSheetFromRom(
                Sonic3kConstants.MAP_CNZ_BUMPER_ADDR,
                Sonic3kConstants.ARTTILE_CNZ_BUMPER,
                2);
    }

    /**
     * CNZ Vacuum Tube is controller-only in this task slice.
     * The ROM object exists, but no dedicated traversal sprite sheet is claimed here.
     */
    public ObjectSpriteSheet buildCnzVacuumTubeSheet() {
        return null;
    }

    /**
     * CNZ Spiral Tube is controller-only in this task slice.
     * The ROM object exists, but no dedicated traversal sprite sheet is claimed here.
     */
    public ObjectSpriteSheet buildCnzSpiralTubeSheet() {
        return null;
    }
}
