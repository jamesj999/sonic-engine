package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Builds object sprite sheets for S1 objects.
 * Provides convenience methods for building sheets from Nemesis-compressed art
 * combined with either ROM-parsed or hardcoded mappings.
 */
public class Sonic1ObjectArt {
    private static final Logger LOG = Logger.getLogger(Sonic1ObjectArt.class.getName());

    private final Rom rom;
    private final RomByteReader reader;

    public Sonic1ObjectArt(Rom rom, RomByteReader reader) {
        this.rom = rom;
        this.reader = reader;
    }

    /**
     * Builds a sprite sheet from Nemesis-compressed art and ROM-parsed S1 mappings.
     *
     * @param artAddr ROM address of Nemesis-compressed art
     * @param mappingAddr ROM address of S1 mapping table
     * @param paletteIndex palette line (0-3)
     * @param bankSize bank size for renderer (typically 1)
     * @return sprite sheet, or null if art decompression fails
     */
    public ObjectSpriteSheet buildArtSheetFromRom(int artAddr, int mappingAddr,
            int paletteIndex, int bankSize) {
        Pattern[] patterns = loadNemesisPatterns(artAddr);
        if (patterns.length == 0) return null;

        List<SpriteMappingFrame> frames;
        try {
            frames = S1SpriteDataLoader.loadMappingFrames(reader, mappingAddr);
        } catch (IllegalArgumentException e) {
            LOG.warning("Failed to load S1 mappings at 0x" + Integer.toHexString(mappingAddr)
                    + ": " + e.getMessage());
            return null;
        }
        if (frames.isEmpty()) return null;

        return new ObjectSpriteSheet(patterns, frames, paletteIndex, bankSize);
    }

    /**
     * Loads S1-format sprite mappings from the current ROM.
     */
    public List<SpriteMappingFrame> loadMappingFrames(int mappingAddr) {
        try {
            return S1SpriteDataLoader.loadMappingFrames(reader, mappingAddr);
        } catch (IllegalArgumentException e) {
            LOG.warning("Failed to load S1 mappings at 0x" + Integer.toHexString(mappingAddr)
                    + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads S1-format sprite mappings from the current ROM with an explicit frame count.
     */
    public List<SpriteMappingFrame> loadMappingFrames(int mappingAddr, int frameCount) {
        try {
            return S1SpriteDataLoader.loadMappingFrames(reader, mappingAddr, frameCount);
        } catch (IllegalArgumentException e) {
            LOG.warning("Failed to load S1 mappings at 0x" + Integer.toHexString(mappingAddr)
                    + ": " + e.getMessage());
            return List.of();
        }
    }

    public List<SpriteMappingFrame> loadMappingFramesWithTileOffset(int mappingAddr, int frameCount, int tileOffset) {
        return offsetMappingTiles(loadMappingFrames(mappingAddr, frameCount), tileOffset);
    }

    public static List<SpriteMappingFrame> offsetMappingTiles(List<SpriteMappingFrame> frames, int tileOffset) {
        if (tileOffset == 0 || frames.isEmpty()) {
            return List.copyOf(frames);
        }

        List<SpriteMappingFrame> remappedFrames = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> remappedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                remappedPieces.add(new SpriteMappingPiece(
                        piece.xOffset(),
                        piece.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex() + tileOffset,
                        piece.hFlip(),
                        piece.vFlip(),
                        piece.paletteIndex(),
                        piece.priority()));
            }
            remappedFrames.add(new SpriteMappingFrame(remappedPieces));
        }
        return List.copyOf(remappedFrames);
    }

    /**
     * Builds a sprite sheet from Nemesis-compressed art and hardcoded mappings.
     *
     * @param artAddr ROM address of Nemesis-compressed art
     * @param mappings pre-built mapping frames
     * @param paletteIndex palette line (0-3)
     * @param bankSize bank size for renderer (typically 1)
     * @return sprite sheet, or null if art decompression fails
     */
    public ObjectSpriteSheet buildArtSheet(int artAddr, List<SpriteMappingFrame> mappings,
            int paletteIndex, int bankSize) {
        Pattern[] patterns = loadNemesisPatterns(artAddr);
        if (patterns.length == 0) return null;

        return new ObjectSpriteSheet(patterns, mappings, paletteIndex, bankSize);
    }

    /**
     * Builds a sprite sheet from uncompressed art and hardcoded mappings.
     *
     * @param artAddr ROM address of uncompressed art
     * @param artSize size of art in bytes
     * @param mappings pre-built mapping frames
     * @param paletteIndex palette line (0-3)
     * @param bankSize bank size for renderer (typically 1)
     * @return sprite sheet, or null if loading fails
     */
    public ObjectSpriteSheet buildUncompressedArtSheet(int artAddr, int artSize,
            List<SpriteMappingFrame> mappings, int paletteIndex, int bankSize) {
        Pattern[] patterns = loadUncompressedPatterns(artAddr, artSize);
        if (patterns.length == 0) return null;

        return new ObjectSpriteSheet(patterns, mappings, paletteIndex, bankSize);
    }

    /**
     * Loads Nemesis-compressed patterns from ROM.
     */
    /**
     * Obj39 GAME OVER / TIME OVER sheet: Nem_GameOver with Map_Over. The ROM
     * decompresses it through PLC_GameOver when Sonic_HandleDeath asks for it;
     * the engine keeps the decoded sheet resident and lets the PLC queue supply
     * only the ready-timing the card waits on.
     */
    public ObjectSpriteSheet loadGameOverSheet() {
        Pattern[] patterns = loadNemesisPatterns(Sonic1Constants.ART_NEM_GAME_OVER_ADDR);
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> frames = loadMappingFrames(
                Sonic1Constants.MAP_GAME_OVER_ADDR, Sonic1Constants.MAP_GAME_OVER_FRAME_COUNT);
        return new ObjectSpriteSheet(patterns, frames, 0, 1);
    }

    public Pattern[] loadNemesisPatterns(int address) {
        try {
            return PatternDecompressor.nemesis(rom, address);
        } catch (IOException e) {
            LOG.warning("Failed to decompress Nemesis art at 0x"
                    + Integer.toHexString(address) + ": " + e.getMessage());
            return new Pattern[0];
        }
    }

    /**
     * Loads uncompressed patterns from ROM.
     */
    public Pattern[] loadUncompressedPatterns(int address, int size) {
        try {
            byte[] data = rom.readBytes(address, size);
            if (data.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
                LOG.warning("Inconsistent uncompressed art size at 0x"
                        + Integer.toHexString(address));
                return new Pattern[0];
            }
            int count = data.length / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] patterns = new Pattern[count];
            for (int i = 0; i < count; i++) {
                patterns[i] = new Pattern();
                byte[] sub = Arrays.copyOfRange(data,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                patterns[i].fromSegaFormat(sub);
            }
            return patterns;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to load uncompressed art at 0x"
                    + Integer.toHexString(address) + ": " + e.getMessage());
            return new Pattern[0];
        }
    }
}
