package com.openggf.game.sonic3k.specialstage;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * ROM data loader for the S3K Blue Ball special stage.
 * <p>
 * Lazily loads and caches all art, layout, palette, and perspective data
 * from the ROM using the appropriate decompression algorithms.
 * <p>
 * ROM offsets are defined in {@link Sonic3kSpecialStageRomOffsets} and need
 * to be verified with RomOffsetFinder when the ROM is available.
 * <p>
 * Follows the pattern established by {@code Sonic2SpecialStageDataLoader}.
 */
public class Sonic3kSpecialStageDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSpecialStageDataLoader.class.getName());

    /** Extra bytes to read past declared size (Nemesis decompression may overread). */
    private static final int NEMESIS_PADDING = 32;

    private final Rom rom;

    // ==================== Cached Data ====================

    private Pattern[] sphereArt;
    private Pattern[] ringArt;
    private Pattern[] bgArt;
    private Pattern[] layoutArt;
    private Pattern[] shadowArt;
    private Pattern[] getBlueSphereArt;
    private Pattern[] gbsArrowArt;
    private Pattern[] digitsArt;
    private Pattern[] iconsArt;
    private Pattern[] chaosEmeraldArt;
    private Pattern[] superEmeraldArt;

    private byte[] bgEnigmaMap;
    private byte[] layoutEnigmaMap;
    private byte[] perspectiveMaps;
    private byte[] scalarTable;

    private byte[] mainPalette;
    private byte[] knuxPalettePatch;
    private byte[][] stagePalettesS3; // 8 stage palettes (Sonic 3 stages)
    private byte[][] stagePalettesK;  // 8 stage palettes (S&K stages)
    private byte[] emeraldPalette;

    private byte[][] layoutData; // 8 decompressed stage layouts (from SK Set 1 compressed data)
    private byte[] layoutDataCompressed1; // SK Set 1 compressed
    private byte[] layoutDataCompressed2; // SK Set 2 compressed

    // Player art (uncompressed)
    private byte[] sonicArt;
    private byte[] knucklesArt;
    private byte[] tailsArt;
    private byte[] tailsTailsArt;

    // HUD maps (uncompressed)
    private byte[] hudNumberMap;
    private byte[] hudDisplayMap;

    public Sonic3kSpecialStageDataLoader(Rom rom) {
        this.rom = rom;
    }

    /**
     * Create a data loader using the current game ROM.
     *
     * @throws IOException if the ROM cannot be accessed
     */
    public static Sonic3kSpecialStageDataLoader create() throws IOException {
        return new Sonic3kSpecialStageDataLoader(GameServices.rom().getRom());
    }

    // ==================== Art Loading ====================

    public Pattern[] getSphereArt() throws IOException {
        if (sphereArt == null) {
            sphereArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_SPHERE,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_SPHERE_SIZE,
                    "sphere");
        }
        return sphereArt;
    }

    public Pattern[] getRingArt() throws IOException {
        if (ringArt == null) {
            ringArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_RING,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_RING_SIZE,
                    "ring");
        }
        return ringArt;
    }

    public Pattern[] getBgArt() throws IOException {
        if (bgArt == null) {
            bgArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_BG,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_BG_SIZE,
                    "BG");
        }
        return bgArt;
    }

    public Pattern[] getLayoutArt() throws IOException {
        if (layoutArt == null) {
            layoutArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_LAYOUT,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_LAYOUT_SIZE,
                    "layout");
        }
        return layoutArt;
    }

    public Pattern[] getShadowArt() throws IOException {
        if (shadowArt == null) {
            shadowArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_SHADOW,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_SHADOW_SIZE,
                    "shadow");
        }
        return shadowArt;
    }

    public Pattern[] getGetBlueSphereArt() throws IOException {
        if (getBlueSphereArt == null) {
            getBlueSphereArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_GET_BLUE_SPHERES,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_GET_BLUE_SPHERES_SIZE,
                    "Get Blue Spheres");
        }
        return getBlueSphereArt;
    }

    public Pattern[] getGbsArrowArt() throws IOException {
        if (gbsArrowArt == null) {
            gbsArrowArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_GBS_ARROW,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_GBS_ARROW_SIZE,
                    "GBS Arrow");
        }
        return gbsArrowArt;
    }

    public Pattern[] getDigitsArt() throws IOException {
        if (digitsArt == null) {
            digitsArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_DIGITS,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_DIGITS_SIZE,
                    "digits");
        }
        return digitsArt;
    }

    public Pattern[] getIconsArt() throws IOException {
        if (iconsArt == null) {
            iconsArt = loadNemesisArt(
                    Sonic3kSpecialStageRomOffsets.ART_NEM_ICONS,
                    Sonic3kSpecialStageRomOffsets.ART_NEM_ICONS_SIZE,
                    "icons");
        }
        return iconsArt;
    }

    // ==================== Emerald Art (KosinskiM) ====================

    public Pattern[] getChaosEmeraldArt() throws IOException {
        if (chaosEmeraldArt == null) {
            chaosEmeraldArt = loadKosinskiModuledArt(
                    Sonic3kSpecialStageRomOffsets.ART_KOSM_CHAOS_EMERALD,
                    Sonic3kSpecialStageRomOffsets.ART_KOSM_CHAOS_EMERALD_SIZE,
                    "chaos emerald");
        }
        return chaosEmeraldArt;
    }

    public Pattern[] getSuperEmeraldArt() throws IOException {
        if (superEmeraldArt == null) {
            superEmeraldArt = loadKosinskiModuledArt(
                    Sonic3kSpecialStageRomOffsets.ART_KOSM_SUPER_EMERALD,
                    Sonic3kSpecialStageRomOffsets.ART_KOSM_SUPER_EMERALD_SIZE,
                    "super emerald");
        }
        return superEmeraldArt;
    }

    // ==================== Enigma Maps ====================

    public byte[] getBgEnigmaMap() throws IOException {
        if (bgEnigmaMap == null) {
            // ROM: make_art_tile(ArtTile_SStage_BG,2,0) = (palette 2 << 13) | 0x59B = 0x459B
            int startTile = (2 << 13) | Sonic3kSpecialStageConstants.ART_TILE_BG;
            bgEnigmaMap = loadEnigmaMap(
                    Sonic3kSpecialStageRomOffsets.MAP_ENI_BG,
                    Sonic3kSpecialStageRomOffsets.MAP_ENI_BG_SIZE,
                    startTile,
                    "BG");
        }
        return bgEnigmaMap;
    }

    public byte[] getLayoutEnigmaMap() throws IOException {
        if (layoutEnigmaMap == null) {
            layoutEnigmaMap = loadEnigmaMap(
                    Sonic3kSpecialStageRomOffsets.MAP_ENI_LAYOUT,
                    Sonic3kSpecialStageRomOffsets.MAP_ENI_LAYOUT_SIZE,
                    0, // Layout uses art tile 0
                    "layout");
        }
        return layoutEnigmaMap;
    }

    // ==================== Perspective Maps (Kosinski) ====================

    public byte[] getPerspectiveMaps() throws IOException {
        if (perspectiveMaps == null) {
            byte[] compressed = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.PERSPECTIVE_MAPS,
                    Sonic3kSpecialStageRomOffsets.PERSPECTIVE_MAPS_SIZE + NEMESIS_PADDING);
            perspectiveMaps = decompressKosinski(compressed);
            LOGGER.fine("Loaded perspective maps: " + perspectiveMaps.length + " bytes");
        }
        return perspectiveMaps;
    }

    // ==================== Scalar Table ====================

    public byte[] getScalarTable() throws IOException {
        if (scalarTable == null) {
            scalarTable = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.SCALAR_TABLE,
                    Sonic3kSpecialStageRomOffsets.SCALAR_TABLE_SIZE);
            LOGGER.fine("Loaded scalar table: " + scalarTable.length + " bytes");
        }
        return scalarTable;
    }

    // ==================== Palettes ====================

    public byte[] getMainPalette() throws IOException {
        if (mainPalette == null) {
            mainPalette = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.PAL_MAIN,
                    Sonic3kSpecialStageRomOffsets.PAL_MAIN_SIZE);
            LOGGER.fine("Loaded main palette: " + mainPalette.length + " bytes");
        }
        return mainPalette;
    }

    public byte[] getKnuxPalettePatch() throws IOException {
        if (knuxPalettePatch == null) {
            knuxPalettePatch = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.PAL_KNUX,
                    Sonic3kSpecialStageRomOffsets.PAL_KNUX_SIZE);
        }
        return knuxPalettePatch;
    }

    public byte[] getStagePalette(int stageIndex, boolean skMode) throws IOException {
        if (skMode) {
            if (stagePalettesK == null) {
                stagePalettesK = new byte[8][];
            }
            if (stagePalettesK[stageIndex] == null) {
                long offset = Sonic3kSpecialStageRomOffsets.PAL_K_BASE
                        + (long) stageIndex * Sonic3kSpecialStageRomOffsets.PAL_STAGE_SIZE;
                stagePalettesK[stageIndex] = rom.readBytes(offset,
                        Sonic3kSpecialStageRomOffsets.PAL_STAGE_SIZE);
            }
            return stagePalettesK[stageIndex];
        } else {
            if (stagePalettesS3 == null) {
                stagePalettesS3 = new byte[8][];
            }
            if (stagePalettesS3[stageIndex] == null) {
                long offset = Sonic3kSpecialStageRomOffsets.PAL_S3_BASE
                        + (long) stageIndex * Sonic3kSpecialStageRomOffsets.PAL_STAGE_SIZE;
                stagePalettesS3[stageIndex] = rom.readBytes(offset,
                        Sonic3kSpecialStageRomOffsets.PAL_STAGE_SIZE);
            }
            return stagePalettesS3[stageIndex];
        }
    }

    public byte[] getEmeraldPalette() throws IOException {
        if (emeraldPalette == null) {
            emeraldPalette = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.PAL_EMERALDS,
                    Sonic3kSpecialStageRomOffsets.PAL_EMERALDS_SIZE);
        }
        return emeraldPalette;
    }

    // ==================== Layout Data ====================

    /**
     * Load and decompress the compressed layout data (SK Set 1 or 2).
     * ROM: SSCompressedLayoutPtrs (sonic3k.asm:202580)
     *
     * @param setIndex 0 for SK Set 1, 1 for SK Set 2
     * @return decompressed layout data
     */
    public byte[] getCompressedLayoutSet(int setIndex) throws IOException {
        if (setIndex == 0) {
            if (layoutDataCompressed1 == null) {
                byte[] compressed = rom.readBytes(
                        Sonic3kSpecialStageRomOffsets.LAYOUT_SK_SET_1,
                        Sonic3kSpecialStageRomOffsets.LAYOUT_SK_SET_1_SIZE + NEMESIS_PADDING);
                layoutDataCompressed1 = decompressKosinski(compressed);
                LOGGER.fine("Loaded SK Set 1 layouts: " + layoutDataCompressed1.length + " bytes");
            }
            return layoutDataCompressed1;
        } else {
            if (layoutDataCompressed2 == null) {
                byte[] compressed = rom.readBytes(
                        Sonic3kSpecialStageRomOffsets.LAYOUT_SK_SET_2,
                        Sonic3kSpecialStageRomOffsets.LAYOUT_SK_SET_2_SIZE + NEMESIS_PADDING);
                layoutDataCompressed2 = decompressKosinski(compressed);
                LOGGER.fine("Loaded SK Set 2 layouts: " + layoutDataCompressed2.length + " bytes");
            }
            return layoutDataCompressed2;
        }
    }

    // ==================== Player Art (Uncompressed) ====================

    public byte[] getSonicArt() throws IOException {
        if (sonicArt == null) {
            sonicArt = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.ART_UNC_SONIC,
                    Sonic3kSpecialStageRomOffsets.ART_UNC_SONIC_SIZE);
            LOGGER.fine("Loaded Sonic SS art: " + sonicArt.length + " bytes");
        }
        return sonicArt;
    }

    public byte[] getKnucklesArt() throws IOException {
        if (knucklesArt == null) {
            knucklesArt = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.ART_UNC_KNUCKLES,
                    Sonic3kSpecialStageRomOffsets.ART_UNC_KNUCKLES_SIZE);
            LOGGER.fine("Loaded Knuckles SS art: " + knucklesArt.length + " bytes");
        }
        return knucklesArt;
    }

    public byte[] getTailsArt() throws IOException {
        if (tailsArt == null) {
            tailsArt = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS,
                    Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_SIZE);
            LOGGER.fine("Loaded Tails SS art: " + tailsArt.length + " bytes");
        }
        return tailsArt;
    }

    public byte[] getTailsTailsArt() throws IOException {
        if (tailsTailsArt == null) {
            tailsTailsArt = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_TAILS,
                    Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_TAILS_SIZE);
            LOGGER.fine("Loaded Tails tails SS art: " + tailsTailsArt.length + " bytes");
        }
        return tailsTailsArt;
    }

    // ==================== HUD Maps (Uncompressed) ====================

    public byte[] getHudNumberMap() throws IOException {
        if (hudNumberMap == null) {
            hudNumberMap = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.MAP_UNC_HUD_NUMBERS,
                    Sonic3kSpecialStageRomOffsets.MAP_UNC_HUD_NUMBERS_SIZE);
        }
        return hudNumberMap;
    }

    public byte[] getHudDisplayMap() throws IOException {
        if (hudDisplayMap == null) {
            hudDisplayMap = rom.readBytes(
                    Sonic3kSpecialStageRomOffsets.MAP_UNC_HUD_DISPLAY,
                    Sonic3kSpecialStageRomOffsets.MAP_UNC_HUD_DISPLAY_SIZE);
        }
        return hudDisplayMap;
    }

    // ==================== Palette Helpers ====================

    /**
     * Convert raw palette bytes to a Palette array (4 lines from main palette).
     */
    public Palette[] createMainPalettes() throws IOException {
        byte[] raw = getMainPalette();
        Palette[] palettes = new Palette[4];
        for (int line = 0; line < 4; line++) {
            palettes[line] = new Palette();
            for (int c = 0; c < 16; c++) {
                int offset = (line * 32) + (c * 2);
                if (offset + 1 < raw.length) {
                    palettes[line].getColor(c).fromSegaFormat(raw, offset);
                }
            }
        }
        return palettes;
    }

    // ==================== Decompression Helpers ====================

    private Pattern[] loadNemesisArt(long offset, int size, String name) throws IOException {
        byte[] compressed = rom.readBytes(offset, size + NEMESIS_PADDING);
        byte[] decompressed = decompressNemesis(compressed);
        Pattern[] patterns = bytesToPatterns(decompressed);
        LOGGER.fine("Loaded " + name + " art: " + patterns.length + " patterns");
        return patterns;
    }

    private Pattern[] loadKosinskiModuledArt(long offset, int size, String name)
            throws IOException {
        byte[] compressed = rom.readBytes(offset, size + NEMESIS_PADDING);
        byte[] decompressed = KosinskiReader.decompressModuled(compressed, 0);
        Pattern[] patterns = bytesToPatterns(decompressed);
        LOGGER.fine("Loaded " + name + " art (KosM): " + patterns.length + " patterns");
        return patterns;
    }

    private byte[] loadEnigmaMap(long offset, int size, int startingArtTile, String name)
            throws IOException {
        byte[] compressed = rom.readBytes(offset, size + NEMESIS_PADDING);
        byte[] decompressed = decompressEnigma(compressed, startingArtTile);
        LOGGER.fine("Loaded " + name + " Enigma map: " + decompressed.length + " bytes");
        return decompressed;
    }

    private byte[] decompressNemesis(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return NemesisReader.decompress(channel);
        }
    }

    private byte[] decompressKosinski(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return KosinskiReader.decompress(channel);
        }
    }

    private byte[] decompressEnigma(byte[] compressed, int startingArtTile) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return EnigmaReader.decompress(channel, startingArtTile);
        }
    }

    private Pattern[] bytesToPatterns(byte[] data) {
        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    /**
     * Clear all cached data (for memory management / reload).
     */
    public void clearCache() {
        sphereArt = null;
        ringArt = null;
        bgArt = null;
        layoutArt = null;
        shadowArt = null;
        getBlueSphereArt = null;
        gbsArrowArt = null;
        digitsArt = null;
        iconsArt = null;
        chaosEmeraldArt = null;
        superEmeraldArt = null;
        bgEnigmaMap = null;
        layoutEnigmaMap = null;
        perspectiveMaps = null;
        scalarTable = null;
        mainPalette = null;
        knuxPalettePatch = null;
        stagePalettesS3 = null;
        stagePalettesK = null;
        emeraldPalette = null;
        layoutDataCompressed1 = null;
        layoutDataCompressed2 = null;
        sonicArt = null;
        knucklesArt = null;
        tailsArt = null;
        tailsTailsArt = null;
        hudNumberMap = null;
        hudDisplayMap = null;
    }
}
