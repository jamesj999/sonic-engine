package com.openggf.game.sonic3k.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.BootstrapObjectServices;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized loader for all AIZ1 intro cinematic art, mappings, and palettes.
 *
 * <p>Called once during intro object initialization. Data is cached in static fields
 * and reused across all intro objects (plane, emeralds, waves, Knuckles).
 *
 * <p>Art sources:
 * <ul>
 *   <li>Plane art: KosinskiM at {@link Sonic3kConstants#ART_KOSM_AIZ_INTRO_PLANE_ADDR}</li>
 *   <li>Emerald art: KosinskiM at {@link Sonic3kConstants#ART_KOSM_AIZ_INTRO_EMERALDS_ADDR}</li>
 *   <li>Intro sprites (waves): Nemesis at {@link Sonic3kConstants#ART_NEM_AIZ_INTRO_SPRITES_ADDR}</li>
 *   <li>Cutscene Knuckles: Uncompressed at {@link Sonic3kConstants#ART_UNC_CUTSCENE_KNUX_ADDR}</li>
 * </ul>
 *
 * <p><b>S3K mapping format (6 bytes per piece):</b>
 * <pre>
 * byte 0:    yOffset (signed byte)
 * byte 1:    size (width bits 3:2, height bits 1:0)
 * bytes 2-3: tile word (16-bit BE: priority|pal|vflip|hflip|tileIndex)
 * bytes 4-5: xOffset (signed 16-bit BE)
 * </pre>
 * This differs from S2 which has an additional 2-byte "2P tile word" field (8 bytes per piece).
 */
public class AizIntroArtLoader {

    private static final Logger LOG = Logger.getLogger(AizIntroArtLoader.class.getName());

    /** Bytes per S3K mapping piece (no 2P tile word). */
    private static final int S3K_MAPPING_PIECE_SIZE = 6;

    // -----------------------------------------------------------------------
    // Cached data (loaded once, reused across intro objects)
    // -----------------------------------------------------------------------

    private static Pattern[] planePatterns;
    private static Pattern[] emeraldPatterns;
    private static Pattern[] introSpritesPatterns;
    private static Pattern[] knucklesPatterns;
    private static Pattern[] corkFloorPatterns;

    private static List<SpriteMappingFrame> planeMappings;
    private static List<SpriteMappingFrame> emeraldMappings;
    private static List<SpriteMappingFrame> waveMappings;
    private static List<SpriteMappingFrame> knucklesMappings;
    private static List<SpriteMappingFrame> corkFloorMappings;

    private static List<SpriteDplcFrame> knucklesDplcFrames;

    private static byte[] superSonicPaletteCycleData;
    private static byte[] cutsceneKnucklesPalette;
    private static byte[] emeraldPalette;

    private static ObjectSpriteSheet planeSheet;
    private static ObjectSpriteSheet emeraldSheet;
    private static ObjectSpriteSheet introSpritesSheet;
    private static ObjectSpriteSheet knucklesSheet;
    private static ObjectSpriteSheet corkFloorSheet;

    private static boolean loaded = false;
    private static ObjectServices activeServices;

    // Renderer cache — lazy-initialized on first render call
    private static final int INTRO_PATTERN_BASE = PatternAtlasRange.TITLE_CARDS.base();
    private static PatternSpriteRenderer planeRenderer;
    private static PatternSpriteRenderer emeraldRenderer;
    private static PatternSpriteRenderer introSpritesRenderer;
    private static PatternSpriteRenderer knucklesRenderer;
    private static PatternSpriteRenderer corkFloorRenderer;
    private static boolean renderersCached;

    private AizIntroArtLoader() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Loads all intro art, mappings, and palettes. Safe to call multiple times;
     * subsequent calls are no-ops if already loaded.
     */
    public static synchronized void loadAllIntroArt() {
        loadAllIntroArt(new BootstrapObjectServices());
    }

    public static synchronized void loadAllIntroArt(ObjectServices services) {
        if (loaded) {
            return;
        }

        ObjectServices previousServices = activeServices;
        activeServices = services;
        try {
            LOG.info("Loading AIZ1 intro art...");

            loadPlaneArt();
            loadEmeraldArt();
            loadIntroSpritesArt();
            loadKnucklesArt();
            loadCorkFloorArt();

            loadPlaneMappings();
            loadEmeraldMappings();
            loadWaveMappings();
            loadKnucklesMappings();
            loadKnucklesDplc();
            loadCorkFloorMappings();

            loadSuperSonicPaletteCycleData();
            loadCutsceneKnucklesPalette();
            loadEmeraldPalette();

            buildSpriteSheets();

            loaded = true;
            LOG.info("AIZ1 intro art loaded successfully.");
        } finally {
            activeServices = previousServices;
        }
    }

    /**
     * Loads only the shared Cutscene Knuckles art bundle used outside the AIZ
     * intro, without pulling in plane, emerald, wave, or cork-floor resources.
     */
    public static synchronized void loadCutsceneKnucklesArt(ObjectServices services) {
        if (knucklesSheet != null
                && knucklesPatterns != null
                && knucklesMappings != null
                && knucklesDplcFrames != null
                && cutsceneKnucklesPalette != null
                && cutsceneKnucklesPalette.length > 0) {
            return;
        }

        ObjectServices previousServices = activeServices;
        activeServices = services;
        try {
            loadKnucklesArt();
            loadKnucklesMappings();
            loadKnucklesDplc();
            loadCutsceneKnucklesPalette();
            buildCutsceneKnucklesSheet();
        } finally {
            activeServices = previousServices;
        }
    }

    /**
     * Resets all cached data, forcing a reload on the next call to
     * {@link #loadAllIntroArt()}. Intended for level transitions.
     */
    public static synchronized void reset() {
        planePatterns = null;
        emeraldPatterns = null;
        introSpritesPatterns = null;
        knucklesPatterns = null;
        corkFloorPatterns = null;
        planeMappings = null;
        emeraldMappings = null;
        waveMappings = null;
        knucklesMappings = null;
        corkFloorMappings = null;
        knucklesDplcFrames = null;
        superSonicPaletteCycleData = null;
        cutsceneKnucklesPalette = null;
        emeraldPalette = null;
        planeSheet = null;
        emeraldSheet = null;
        introSpritesSheet = null;
        knucklesSheet = null;
        corkFloorSheet = null;
        planeRenderer = null;
        emeraldRenderer = null;
        introSpritesRenderer = null;
        knucklesRenderer = null;
        corkFloorRenderer = null;
        renderersCached = false;
        activeServices = null;
        loaded = false;
    }

    // -----------------------------------------------------------------------
    // Art loading
    // -----------------------------------------------------------------------

    /**
     * Decompresses KosinskiM art for the Tornado biplane sprite.
     * ROM address: {@link Sonic3kConstants#ART_KOSM_AIZ_INTRO_PLANE_ADDR}
     * Expected decompressed size: 4352 bytes (136 tiles).
     */
    public static void loadPlaneArt() {
        if (planePatterns != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro plane art load because no ROM-backed ObjectServices are active");
            planePatterns = new Pattern[0];
            return;
        }
        try {
            byte[] data = decompressKosinskiModuled(rom, Sonic3kConstants.ART_KOSM_AIZ_INTRO_PLANE_ADDR);
            LOG.fine("Plane art decompressed: " + data.length + " bytes (" + (data.length / 32) +
                    " tiles, expected 136 tiles / 4352 bytes)");
            planePatterns = bytesToPatterns(data);
            LOG.fine("Loaded plane art: " + planePatterns.length + " patterns");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load plane art", e);
            planePatterns = new Pattern[0];
        }
    }

    /**
     * Decompresses KosinskiM art for the Chaos Emerald sprites.
     * ROM address: {@link Sonic3kConstants#ART_KOSM_AIZ_INTRO_EMERALDS_ADDR}
     * Expected decompressed size: 224 bytes (7 tiles).
     */
    public static void loadEmeraldArt() {
        if (emeraldPatterns != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro emerald art load because no ROM-backed ObjectServices are active");
            emeraldPatterns = new Pattern[0];
            return;
        }
        try {
            byte[] data = decompressKosinskiModuled(rom, Sonic3kConstants.ART_KOSM_AIZ_INTRO_EMERALDS_ADDR);
            emeraldPatterns = bytesToPatterns(data);
            LOG.fine("Loaded emerald art: " + emeraldPatterns.length + " patterns");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load emerald art", e);
            emeraldPatterns = new Pattern[0];
        }
    }

    /**
     * Decompresses Nemesis art for the wave/water spray sprites.
     * ROM address: {@link Sonic3kConstants#ART_NEM_AIZ_INTRO_SPRITES_ADDR}
     * Expected decompressed size: 11008 bytes (344 tiles).
     */
    public static void loadIntroSpritesArt() {
        if (introSpritesPatterns != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro sprite art load because no ROM-backed ObjectServices are active");
            introSpritesPatterns = new Pattern[0];
            return;
        }
        try {
            FileChannel channel = rom.getFileChannel();
            synchronized (rom) {
                channel.position(Sonic3kConstants.ART_NEM_AIZ_INTRO_SPRITES_ADDR);
                byte[] data = NemesisReader.decompress(channel);
                introSpritesPatterns = bytesToPatterns(data);
            }
            LOG.fine("Loaded intro sprites art: " + introSpritesPatterns.length + " patterns");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load intro sprites art", e);
            introSpritesPatterns = new Pattern[0];
        }
    }

    /**
     * Loads uncompressed cutscene Knuckles sprite art.
     * ROM address: {@link Sonic3kConstants#ART_UNC_CUTSCENE_KNUX_ADDR}
     * Size: {@link Sonic3kConstants#ART_UNC_CUTSCENE_KNUX_SIZE} (0x4EE0 = 20192 bytes, 631 tiles).
     */
    public static void loadKnucklesArt() {
        if (knucklesPatterns != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro Knuckles art load because no ROM-backed ObjectServices are active");
            knucklesPatterns = new Pattern[0];
            return;
        }
        try {
            byte[] data = rom.readBytes(
                    Sonic3kConstants.ART_UNC_CUTSCENE_KNUX_ADDR,
                    Sonic3kConstants.ART_UNC_CUTSCENE_KNUX_SIZE);
            knucklesPatterns = bytesToPatterns(data);
            LOG.fine("Loaded Knuckles art: " + knucklesPatterns.length + " patterns");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load Knuckles art", e);
            knucklesPatterns = new Pattern[0];
        }
    }

    /**
     * Loads cork floor art from the level's 8x8 pattern data (not Nemesis sprite art).
     *
     * <p>The cork floor object's {@code art_tile = make_art_tile($001,2,0)} adds +1 to
     * mapping tile indices. Mapping tiles $1C-$37 → VRAM tiles $1D-$38 → level pattern
     * indices 0x1D-0x38. These are loaded as part of AIZ's KosinskiM primary 8x8 patterns.
     *
     * <p>A tile offset of -0x1C is applied in {@link #loadCorkFloorMappings()} to remap
     * mapping indices to 0-based into our pattern array.
     */
    public static void loadCorkFloorArt() {
        if (corkFloorPatterns != null) return;
        try {
            // Cork floor tiles come from the LEVEL's pattern data, not Nemesis sprite art.
            // art_tile = make_art_tile($001,2,0) adds +1 to mapping tile indices.
            // Mapping tiles $1C-$37 → VRAM tiles $1D-$38 → level pattern indices 0x1D-0x38.
            var level = currentLevel();
            if (level == null || level.getPatternCount() < 0x39) {
                LOG.fine("Skipping AIZ intro cork floor art load because level patterns are unavailable (count="
                        + (level != null ? level.getPatternCount() : 0) + ")");
                corkFloorPatterns = new Pattern[0];
                return;
            }
            int startTile = 0x1D;  // art_tile base (1) + lowest mapping tile (0x1C)
            int tileCount = 28;    // 0x1D through 0x38 inclusive
            corkFloorPatterns = new Pattern[tileCount];
            for (int i = 0; i < tileCount; i++) {
                corkFloorPatterns[i] = new Pattern();
                corkFloorPatterns[i].copyFrom(level.getPattern(startTile + i));
            }
            LOG.fine("Loaded cork floor art from level patterns: " + tileCount
                    + " tiles (level indices 0x" + Integer.toHexString(startTile)
                    + "-0x" + Integer.toHexString(startTile + tileCount - 1) + ")");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load cork floor art from level patterns", e);
            corkFloorPatterns = new Pattern[0];
        }
    }

    // -----------------------------------------------------------------------
    // Mapping loading
    // -----------------------------------------------------------------------

    /**
     * Parses S3K sprite mappings for the Tornado biplane.
     * ROM address: {@link Sonic3kConstants#MAP_AIZ_INTRO_PLANE_ADDR}
     */
    public static void loadPlaneMappings() {
        if (planeMappings != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro plane mappings because no ROM-backed ObjectServices are active");
            planeMappings = List.of();
            return;
        }
        try {
            planeMappings = loadS3kMappingFrames(reader, Sonic3kConstants.MAP_AIZ_INTRO_PLANE_ADDR);
            LOG.fine("Loaded plane mappings: " + planeMappings.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load plane mappings", e);
            planeMappings = List.of();
        }
    }

    /**
     * Parses S3K sprite mappings for the Chaos Emeralds.
     * ROM address: {@link Sonic3kConstants#MAP_AIZ_INTRO_EMERALDS_ADDR}
     */
    public static void loadEmeraldMappings() {
        if (emeraldMappings != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro emerald mappings because no ROM-backed ObjectServices are active");
            emeraldMappings = List.of();
            return;
        }
        try {
            emeraldMappings = loadS3kMappingFrames(reader, Sonic3kConstants.MAP_AIZ_INTRO_EMERALDS_ADDR);
            LOG.fine("Loaded emerald mappings: " + emeraldMappings.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load emerald mappings", e);
            emeraldMappings = List.of();
        }
    }

    /**
     * Parses S3K sprite mappings for the water wave/spray sprites.
     * ROM address: {@link Sonic3kConstants#MAP_AIZ_INTRO_WAVES_ADDR}
     */
    public static void loadWaveMappings() {
        if (waveMappings != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro wave mappings because no ROM-backed ObjectServices are active");
            waveMappings = List.of();
            return;
        }
        try {
            waveMappings = loadS3kMappingFrames(reader, Sonic3kConstants.MAP_AIZ_INTRO_WAVES_ADDR);
            LOG.fine("Loaded wave mappings: " + waveMappings.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load wave mappings", e);
            waveMappings = List.of();
        }
    }

    /**
     * Parses S3K sprite mappings for cutscene Knuckles.
     * ROM address: {@link Sonic3kConstants#MAP_CUTSCENE_KNUX_ADDR}
     */
    public static void loadKnucklesMappings() {
        if (knucklesMappings != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro Knuckles mappings because no ROM-backed ObjectServices are active");
            knucklesMappings = List.of();
            return;
        }
        try {
            knucklesMappings = loadS3kMappingFrames(reader, Sonic3kConstants.MAP_CUTSCENE_KNUX_ADDR);
            LOG.fine("Loaded Knuckles mappings: " + knucklesMappings.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load Knuckles mappings", e);
            knucklesMappings = List.of();
        }
    }

    /**
     * Parses S3K sprite mappings for the cork floor (rock child).
     * ROM address: {@link Sonic3kConstants#MAP_AIZ_CORK_FLOOR_ADDR}
     *
     * <p>2 frames: frame 0 = intact rock (6 pieces), frame 1 = broken rock (12 pieces).
     * No DPLCs — tile indices reference VRAM positions directly. The ROM loads cork floor
     * art starting at VRAM tile 0x1C (via PLCs), so mapping tile indices are 0x1C-based.
     * We apply a tile offset of -0x1C to remap to our 0-based pattern array.
     */
    public static void loadCorkFloorMappings() {
        if (corkFloorMappings != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro cork floor mappings because no ROM-backed ObjectServices are active");
            corkFloorMappings = List.of();
            return;
        }
        try {
            corkFloorMappings = loadS3kMappingFramesWithTileOffset(
                    reader, Sonic3kConstants.MAP_AIZ_CORK_FLOOR_ADDR, -0x1C);
            LOG.fine("Loaded cork floor mappings: " + corkFloorMappings.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load cork floor mappings", e);
            corkFloorMappings = List.of();
        }
    }

    /**
     * Parses S3K DPLC frames for cutscene Knuckles.
     * ROM address: {@link Sonic3kConstants#DPLC_CUTSCENE_KNUX_ADDR}
     *
     * <p>S3K DPLC format (differs from S2):
     * <pre>
     * Offset table: word-sized offsets (first word = table size / frame count)
     * Per frame:
     *   word: request count
     *   Per request:
     *     word: (count-1 << 12) | startTile
     * </pre>
     */
    public static void loadKnucklesDplc() {
        if (knucklesDplcFrames != null) return;
        RomByteReader reader = tryReader();
        if (reader == null) {
            LOG.fine("Skipping AIZ intro Knuckles DPLC because no ROM-backed ObjectServices are active");
            knucklesDplcFrames = List.of();
            return;
        }
        try {
            knucklesDplcFrames = loadS3kDplcFrames(reader, Sonic3kConstants.DPLC_CUTSCENE_KNUX_ADDR);
            LOG.fine("Loaded Knuckles DPLC: " + knucklesDplcFrames.size() + " frames");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load Knuckles DPLC", e);
            knucklesDplcFrames = List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Palette loading
    // -----------------------------------------------------------------------

    /**
     * Reads Super Sonic palette cycle data (60 bytes = 10 entries x 6 bytes).
     * ROM address: {@link Sonic3kConstants#PAL_CYCLE_SUPER_SONIC_ADDR}
     *
     * <p>Each entry contains 3 words (6 bytes) representing Mega Drive colors
     * for the palette cycling effect during the intro Super Sonic sequence.
     */
    public static void loadSuperSonicPaletteCycleData() {
        if (superSonicPaletteCycleData != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro Super Sonic palette cycle load because no ROM-backed ObjectServices are active");
            superSonicPaletteCycleData = new byte[0];
            return;
        }
        try {
            int size = Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ENTRY_COUNT
                    * Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ENTRY_SIZE;
            superSonicPaletteCycleData = rom.readBytes(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ADDR, size);
            LOG.fine("Loaded Super Sonic palette cycle data: " + superSonicPaletteCycleData.length + " bytes");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load Super Sonic palette cycle data", e);
            superSonicPaletteCycleData = new byte[0];
        }
    }

    /**
     * Reads cutscene Knuckles palette (32 bytes = 16 colors in Mega Drive format).
     * ROM address: {@link Sonic3kConstants#PAL_CUTSCENE_KNUX_ADDR}
     */
    public static void loadCutsceneKnucklesPalette() {
        if (cutsceneKnucklesPalette != null && cutsceneKnucklesPalette.length > 0) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro cutscene Knuckles palette load because no ROM-backed ObjectServices are active");
            cutsceneKnucklesPalette = new byte[0];
            return;
        }
        try {
            cutsceneKnucklesPalette = rom.readBytes(Sonic3kConstants.PAL_CUTSCENE_KNUX_ADDR, 32);
            LOG.fine("Loaded cutscene Knuckles palette: " + cutsceneKnucklesPalette.length + " bytes");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load cutscene Knuckles palette", e);
            cutsceneKnucklesPalette = new byte[0];
        }
    }

    public static synchronized void loadCutsceneKnucklesPalette(ObjectServices services) {
        ObjectServices previousServices = activeServices;
        activeServices = services;
        try {
            loadCutsceneKnucklesPalette();
        } finally {
            activeServices = previousServices;
        }
    }

    /**
     * Reads emerald palette (32 bytes = 16 colors in Mega Drive format).
     * ROM address: {@link Sonic3kConstants#PAL_AIZ_INTRO_EMERALDS_ADDR}
     */
    public static void loadEmeraldPalette() {
        if (emeraldPalette != null) return;
        Rom rom = tryCurrentRom();
        if (rom == null) {
            LOG.fine("Skipping AIZ intro emerald palette load because no ROM-backed ObjectServices are active");
            emeraldPalette = new byte[0];
            return;
        }
        try {
            emeraldPalette = rom.readBytes(Sonic3kConstants.PAL_AIZ_INTRO_EMERALDS_ADDR, 32);
            LOG.fine("Loaded emerald palette: " + emeraldPalette.length + " bytes");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load emerald palette", e);
            emeraldPalette = new byte[0];
        }
    }

    // -----------------------------------------------------------------------
    // Sprite sheet accessors
    // -----------------------------------------------------------------------

    /** Returns the plane sprite sheet, or null if not loaded. */
    public static ObjectSpriteSheet getPlaneSheet() {
        return planeSheet;
    }

    /** Returns the emerald sprite sheet, or null if not loaded. */
    public static ObjectSpriteSheet getEmeraldSheet() {
        return emeraldSheet;
    }

    /** Returns the intro sprites (waves) sprite sheet, or null if not loaded. */
    public static ObjectSpriteSheet getIntroSpritesSheet() {
        return introSpritesSheet;
    }

    /** Returns the Knuckles sprite sheet, or null if not loaded. */
    public static ObjectSpriteSheet getKnucklesSheet() {
        return knucklesSheet;
    }

    /** Returns the cork floor sprite sheet, or null if not loaded. */
    public static ObjectSpriteSheet getCorkFloorSheet() {
        return corkFloorSheet;
    }

    // -----------------------------------------------------------------------
    // Raw data accessors (for callers that need direct access)
    // -----------------------------------------------------------------------

    /** Returns plane pattern tiles, or empty array if not loaded. */
    public static Pattern[] getPlanePatterns() {
        return planePatterns != null ? planePatterns : new Pattern[0];
    }

    /** Returns emerald pattern tiles, or empty array if not loaded. */
    public static Pattern[] getEmeraldPatterns() {
        return emeraldPatterns != null ? emeraldPatterns : new Pattern[0];
    }

    /** Returns intro sprites pattern tiles (waves/spray), or empty array if not loaded. */
    public static Pattern[] getIntroSpritesPatterns() {
        return introSpritesPatterns != null ? introSpritesPatterns : new Pattern[0];
    }

    /** Returns Knuckles pattern tiles, or empty array if not loaded. */
    public static Pattern[] getKnucklesPatterns() {
        return knucklesPatterns != null ? knucklesPatterns : new Pattern[0];
    }

    /** Returns plane mapping frames, or empty list if not loaded. */
    public static List<SpriteMappingFrame> getPlaneMappings() {
        return planeMappings != null ? planeMappings : List.of();
    }

    /** Returns emerald mapping frames, or empty list if not loaded. */
    public static List<SpriteMappingFrame> getEmeraldMappings() {
        return emeraldMappings != null ? emeraldMappings : List.of();
    }

    /** Returns wave mapping frames, or empty list if not loaded. */
    public static List<SpriteMappingFrame> getWaveMappings() {
        return waveMappings != null ? waveMappings : List.of();
    }

    /** Returns Knuckles mapping frames, or empty list if not loaded. */
    public static List<SpriteMappingFrame> getKnucklesMappings() {
        return knucklesMappings != null ? knucklesMappings : List.of();
    }

    /** Returns Knuckles DPLC frames, or empty list if not loaded. */
    public static List<SpriteDplcFrame> getKnucklesDplcFrames() {
        return knucklesDplcFrames != null ? knucklesDplcFrames : List.of();
    }

    /** Returns Super Sonic palette cycle data (60 bytes), or empty array if not loaded. */
    public static byte[] getSuperSonicPaletteCycleData() {
        return superSonicPaletteCycleData != null ? superSonicPaletteCycleData : new byte[0];
    }

    /** Returns cutscene Knuckles palette (32 bytes), or empty array if not loaded. */
    public static byte[] getCutsceneKnucklesPalette() {
        return cutsceneKnucklesPalette != null ? cutsceneKnucklesPalette : new byte[0];
    }

    /** Returns emerald palette (32 bytes), or empty array if not loaded. */
    public static byte[] getEmeraldPalette() {
        return emeraldPalette != null ? emeraldPalette : new byte[0];
    }

    /** Returns whether all intro art has been loaded. */
    public static boolean isLoaded() {
        return loaded;
    }

    // -----------------------------------------------------------------------
    // Palette application
    // -----------------------------------------------------------------------

    /**
     * Applies the cutscene Knuckles palette to palette line 1.
     * Safe to call before GL is initialized (no-ops gracefully).
     */
    public static void applyKnucklesPalette() {
        applyKnucklesPalette(new BootstrapObjectServices());
    }

    public static void applyKnucklesPalette(ObjectServices services) {
        if (cutsceneKnucklesPalette == null || cutsceneKnucklesPalette.length == 0) {
            loadCutsceneKnucklesPalette(services);
        }
        byte[] data = getCutsceneKnucklesPalette();
        if (data == null || data.length == 0) return;
        GraphicsManager gm = graphicsManager(services);
        S3kPaletteWriteSupport.applyLine(
                services != null ? services.paletteOwnershipRegistryOrNull() : null,
                services != null ? services.currentLevel() : null,
                gm,
                S3kPaletteOwners.AIZ_INTRO_CUTSCENE_KNUCKLES,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                1,
                data,
                true);
        if (services == null || services.currentLevel() == null) {
            S3kPaletteWriteSupport.cacheStandaloneLine(gm, 1, data);
        }
    }

    /**
     * Applies the emerald palette to palette line 3.
     * ROM: loc_67764 loads Pal_AIZIntroEmeralds to Normal_palette_line_4 (Java line 3).
     * Emerald ObjDat art_tile = 0x65B1 → palette bits 14-13 = 11 = palette 3.
     * Safe to call before GL is initialized (no-ops gracefully).
     */
    public static void applyEmeraldPalette() {
        applyEmeraldPalette(new BootstrapObjectServices());
    }

    public static void applyEmeraldPalette(ObjectServices services) {
        byte[] data = getEmeraldPalette();
        if (data == null || data.length == 0) return;
        GraphicsManager gm = graphicsManager(services);
        if (services != null && services.currentLevel() != null) {
            S3kPaletteWriteSupport.applyLine(
                    services.paletteOwnershipRegistryOrNull(),
                    services.currentLevel(),
                    gm,
                    S3kPaletteOwners.AIZ_INTRO_EMERALD_PALETTE,
                    S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                    3,
                    data,
                    true);
        } else {
            S3kPaletteWriteSupport.cacheStandaloneLine(gm, 3, data);
        }
    }

    // -----------------------------------------------------------------------
    // Renderer cache
    // -----------------------------------------------------------------------

    /**
     * Lazily creates PatternSpriteRenderers and uploads patterns to GPU.
     * Safe to call every frame — no-ops if already cached.
     */
    public static void ensureRenderersCached() {
        ensureRenderersCached(new BootstrapObjectServices());
    }

    public static void ensureRenderersCached(ObjectServices services) {
        if (renderersCached || !loaded) return;
        GraphicsManager gm = graphicsManager(services);
        if (gm == null) return;

        int nextBase = INTRO_PATTERN_BASE;

        if (planeSheet != null && planePatterns != null && planePatterns.length > 0) {
            planeRenderer = new PatternSpriteRenderer(planeSheet);
            planeRenderer.ensurePatternsCached(gm, nextBase);
            nextBase += planePatterns.length;
        }

        if (emeraldSheet != null && emeraldPatterns != null && emeraldPatterns.length > 0) {
            emeraldRenderer = new PatternSpriteRenderer(emeraldSheet);
            emeraldRenderer.ensurePatternsCached(gm, nextBase);
            nextBase += emeraldPatterns.length;
        }

        if (introSpritesSheet != null && introSpritesPatterns != null && introSpritesPatterns.length > 0) {
            introSpritesRenderer = new PatternSpriteRenderer(introSpritesSheet);
            introSpritesRenderer.ensurePatternsCached(gm, nextBase);
            nextBase += introSpritesPatterns.length;
        }

        if (knucklesSheet != null && knucklesPatterns != null && knucklesPatterns.length > 0) {
            knucklesRenderer = new PatternSpriteRenderer(knucklesSheet);
            knucklesRenderer.ensurePatternsCached(gm, nextBase);
            nextBase += knucklesPatterns.length;
        }

        if (corkFloorSheet != null && corkFloorPatterns != null && corkFloorPatterns.length > 0) {
            corkFloorRenderer = new PatternSpriteRenderer(corkFloorSheet);
            corkFloorRenderer.ensurePatternsCached(gm, nextBase);
        }

        renderersCached = true;
        LOG.info("AIZ intro renderers cached. Pattern bases: plane=" +
                Integer.toHexString(INTRO_PATTERN_BASE) + " total patterns=" +
                (planePatterns.length + emeraldPatterns.length +
                 introSpritesPatterns.length + knucklesPatterns.length +
                 corkFloorPatterns.length));
    }


    /** Returns the plane renderer, lazily caching if needed. */
    public static PatternSpriteRenderer getPlaneRenderer() {
        return getPlaneRenderer(new BootstrapObjectServices());
    }

    public static PatternSpriteRenderer getPlaneRenderer(ObjectServices services) {
        ensureRenderersCached(services);
        return planeRenderer;
    }

    /** Returns the emerald renderer, lazily caching if needed. */
    public static PatternSpriteRenderer getEmeraldRenderer() {
        return getEmeraldRenderer(new BootstrapObjectServices());
    }

    public static PatternSpriteRenderer getEmeraldRenderer(ObjectServices services) {
        ensureRenderersCached(services);
        return emeraldRenderer;
    }

    /** Returns the intro sprites (waves) renderer, lazily caching if needed. */
    public static PatternSpriteRenderer getIntroSpritesRenderer() {
        return getIntroSpritesRenderer(new BootstrapObjectServices());
    }

    public static PatternSpriteRenderer getIntroSpritesRenderer(ObjectServices services) {
        ensureRenderersCached(services);
        return introSpritesRenderer;
    }

    /** Returns the Knuckles renderer, lazily caching if needed. */
    public static PatternSpriteRenderer getKnucklesRenderer() {
        return getKnucklesRenderer(new BootstrapObjectServices());
    }

    public static PatternSpriteRenderer getKnucklesRenderer(ObjectServices services) {
        ensureCutsceneKnucklesRendererCached(services);
        return knucklesRenderer;
    }

    /** Returns the cork floor renderer (for rock child objects), lazily caching if needed. */
    public static PatternSpriteRenderer getCorkFloorRenderer() {
        return getCorkFloorRenderer(new BootstrapObjectServices());
    }

    public static PatternSpriteRenderer getCorkFloorRenderer(ObjectServices services) {
        ensureRenderersCached(services);
        return corkFloorRenderer;
    }

    // -----------------------------------------------------------------------
    // S3K mapping parser (6 bytes per piece)
    // -----------------------------------------------------------------------

    /**
     * Parses S3K sprite mapping frames from ROM.
     *
     * <p>S3K uses 6 bytes per mapping piece (vs S2's 8 bytes), with no 2P tile word:
     * <pre>
     * Offset table at mappingAddr:
     *   First word = size of offset table (frameCount = firstWord / 2)
     *   Subsequent words = per-frame offsets relative to mappingAddr
     *
     * Per frame (at mappingAddr + offset[i]):
     *   word: piece count
     *   Per piece (6 bytes):
     *     byte: yOffset (signed)
     *     byte: size (width bits 3:2, height bits 1:0)
     *     word: tile word (priority|pal|vflip|hflip|tileIndex)
     *     word: xOffset (signed)
     * </pre>
     *
     * @param reader  ROM byte reader
     * @param mappingAddr  ROM address of the mapping table
     * @return list of parsed mapping frames
     */
    public static List<SpriteMappingFrame> loadS3kMappingFrames(RomByteReader reader, int mappingAddr) {
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;

        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int rawOffset = reader.readU16BE(mappingAddr + i * 2);
            // Treat as signed 16-bit offset (negative offsets reference other tables)
            int signedOffset = (rawOffset > 32767) ? rawOffset - 65536 : rawOffset;
            int frameAddr = mappingAddr + signedOffset;

            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;

            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                // byte 0: yOffset (signed byte)
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;

                // byte 1: size (width bits 3:2, height bits 1:0)
                int size = reader.readU8(frameAddr);
                frameAddr += 1;

                // bytes 2-3: tile word (16-bit BE)
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;

                // bytes 4-5: xOffset (signed 16-bit BE)
                // NOTE: No 2P tile word in S3K format (unlike S2 which has +2 bytes here)
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;
                boolean priority = (tileWord & 0x8000) != 0;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles,
                        tileIndex, hFlip, vFlip, paletteIndex, priority));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    /**
     * Parses S3K sprite mapping frames with a tile index offset applied.
     * Used when mapping tile indices reference VRAM positions that differ
     * from the pattern array's zero-based indexing.
     *
     * @param reader      ROM byte reader
     * @param mappingAddr ROM address of the mapping table
     * @param tileOffset  Offset to add to each tile index (use negative to subtract)
     * @return list of parsed mapping frames with adjusted tile indices
     */
    public static List<SpriteMappingFrame> loadS3kMappingFramesWithTileOffset(
            RomByteReader reader, int mappingAddr, int tileOffset) {
        List<SpriteMappingFrame> raw = loadS3kMappingFrames(reader, mappingAddr);
        if (tileOffset == 0) {
            return raw;
        }

        List<SpriteMappingFrame> adjusted = new ArrayList<>(raw.size());
        for (SpriteMappingFrame frame : raw) {
            List<SpriteMappingPiece> pieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                int adjustedTile = Math.max(0, piece.tileIndex() + tileOffset);
                pieces.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        adjustedTile, piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()));
            }
            adjusted.add(new SpriteMappingFrame(pieces));
        }
        return adjusted;
    }

    // -----------------------------------------------------------------------
    // S3K DPLC parser
    // -----------------------------------------------------------------------

    /**
     * Parses S3K <b>object</b> DPLC (Dynamic Pattern Loading Cue) frames from ROM.
     *
     * <p>This uses the {@code Perform_DPLC} format (sonic3k.asm:178910), which differs
     * from the player DPLC format used by {@code Sonic_Load_PLC}:
     * <pre>
     * Player format: count-1 in high 4 bits, startTile in low 12 bits (subq #1,d5)
     * Object format: startTile in high 12 bits, count-1 in low 4 bits (NO subq)
     * </pre>
     *
     * <p>The request count word is already (count-1) for dbf — no subq adjustment.
     *
     * @param reader   ROM byte reader
     * @param dplcAddr ROM address of the DPLC table
     * @return list of parsed DPLC frames
     */
    public static List<SpriteDplcFrame> loadS3kDplcFrames(RomByteReader reader, int dplcAddr) {
        int offsetTableSize = reader.readU16BE(dplcAddr);
        int frameCount = offsetTableSize / 2;

        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = dplcAddr + reader.readU16BE(dplcAddr + i * 2);
            // Request count is already (count-1) for dbf — add 1 to get actual count
            int requestCount = reader.readU16BE(frameAddr) + 1;
            frameAddr += 2;

            List<TileLoadRequest> requests = new ArrayList<>(requestCount);
            for (int r = 0; r < requestCount; r++) {
                int entry = reader.readU16BE(frameAddr);
                frameAddr += 2;
                // Object DPLC format: startTile in upper 12 bits, (count-1) in lower 4 bits
                int startTile = (entry >> 4) & 0xFFF;
                int count = (entry & 0xF) + 1;
                requests.add(new TileLoadRequest(startTile, count));
            }
            frames.add(new SpriteDplcFrame(requests));
        }
        return frames;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Decompresses KosinskiM data from the given ROM address.
     * Reads enough data from ROM to cover the compressed stream, then
     * delegates to {@link KosinskiReader#decompressModuled(byte[], int)}.
     */
    private static byte[] decompressKosinskiModuled(Rom rom, int romAddr) throws IOException {
        // Read KosM 2-byte BE header: total decompressed size
        byte[] header = rom.readBytes(romAddr, 2);
        int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);

        // Compressed data is smaller than decompressed. Read enough for input.
        // Add padding for module alignment. Cap at 256KB.
        int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);

        // Guard against reading past end of ROM
        long romSize = rom.getSize();
        if (romAddr + inputSize > romSize) {
            inputSize = (int) (romSize - romAddr);
        }

        byte[] romData = rom.readBytes(romAddr, inputSize);
        return KosinskiReader.decompressModuled(romData, 0);
    }

    /**
     * Converts raw decompressed bytes (4bpp Sega format) into a {@link Pattern} array.
     * Each pattern is 32 bytes in ROM (8x8 pixels, 2 pixels per byte).
     */
    private static Pattern[] bytesToPatterns(byte[] data) {
        if (data == null || data.length == 0) {
            return new Pattern[0];
        }

        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];

        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(
                    data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    /**
     * Creates a {@link RomByteReader} from the current ROM.
     */
    private static RomByteReader getReader() throws IOException {
        return RomByteReader.fromRom(currentRom());
    }

    private static RomByteReader tryReader() {
        Rom rom = tryCurrentRom();
        if (rom == null) {
            return null;
        }
        try {
            return RomByteReader.fromRom(rom);
        } catch (IOException ex) {
            return null;
        }
    }

    private static Rom currentRom() throws IOException {
        if (activeServices == null) {
            throw new IOException("AIZ intro art loader requires ObjectServices");
        }
        return activeServices.rom();
    }

    private static Rom tryCurrentRom() {
        if (activeServices == null) {
            return null;
        }
        try {
            return activeServices.rom();
        } catch (IOException ex) {
            return null;
        }
    }

    private static com.openggf.level.Level currentLevel() {
        return activeServices != null ? activeServices.currentLevel() : null;
    }

    private static GraphicsManager graphicsManager(ObjectServices services) {
        if (services != null && services.graphicsManager() != null) {
            return services.graphicsManager();
        }
        return activeServices != null ? activeServices.graphicsManager() : null;
    }

    /**
     * Builds {@link ObjectSpriteSheet} instances from loaded art and mappings.
     * Called after all individual load methods have completed.
     */
    private static void buildSpriteSheets() {
        // Plane sheet: palette index 0, frame delay 1
        if (planePatterns != null && planeMappings != null) {
            planeSheet = new ObjectSpriteSheet(planePatterns, planeMappings, 0, 1);
        }

        // Emerald sheet: palette index 3 (ROM: art_tile 0x65B1, pal bits = 3), frame delay 1
        if (emeraldPatterns != null && emeraldMappings != null) {
            emeraldSheet = new ObjectSpriteSheet(emeraldPatterns, emeraldMappings, 3, 1);
        }

        // Intro sprites (waves) sheet: palette index 0, frame delay 1
        if (introSpritesPatterns != null && waveMappings != null) {
            introSpritesSheet = new ObjectSpriteSheet(introSpritesPatterns, waveMappings, 0, 1);
        }

        buildCutsceneKnucklesSheet();

        // Cork floor sheet: palette index 2 (ROM: make_art_tile($001,2,0)), frame delay 1
        // No DPLCs — static art, tile indices reference Nemesis patterns directly.
        if (corkFloorPatterns != null && corkFloorMappings != null) {
            corkFloorSheet = new ObjectSpriteSheet(corkFloorPatterns, corkFloorMappings, 2, 1);
        }
    }

    private static void buildCutsceneKnucklesSheet() {
        // Knuckles sheet: palette index 1 (Knuckles palette line), frame delay 1.
        // Apply DPLC remapping so mapping tile indices reference the correct source tiles.
        if (knucklesPatterns != null
                && knucklesPatterns.length > 0
                && knucklesMappings != null
                && !knucklesMappings.isEmpty()
                && knucklesDplcFrames != null
                && !knucklesDplcFrames.isEmpty()) {
            List<SpriteMappingFrame> remapped = applyDplcRemap(knucklesMappings, knucklesDplcFrames);
            knucklesSheet = new ObjectSpriteSheet(knucklesPatterns, remapped, 1, 1);
        }
    }

    private static void ensureCutsceneKnucklesRendererCached(ObjectServices services) {
        if (knucklesRenderer != null && knucklesRenderer.isReady()) {
            return;
        }
        if (knucklesSheet == null) {
            loadCutsceneKnucklesArt(services);
        }
        if (knucklesSheet == null || knucklesPatterns == null || knucklesPatterns.length == 0) {
            return;
        }
        GraphicsManager gm = graphicsManager(services);
        if (gm == null) {
            return;
        }
        knucklesRenderer = new PatternSpriteRenderer(knucklesSheet);
        knucklesRenderer.ensurePatternsCached(gm, INTRO_PATTERN_BASE);
    }

    /**
     * Remaps mapping tile indices through DPLC data.
     *
     * <p>In the ROM, DPLCs specify which subset of tiles from the full art set to load
     * into VRAM for each animation frame. Mapping tile indices are relative to the
     * DPLC-loaded VRAM position (0-based). Without remapping, the mappings reference
     * the wrong tiles in our full art array.
     *
     * <p>For each mapping frame, the corresponding DPLC frame lists tile load requests.
     * These requests define a contiguous VRAM layout:
     * <pre>
     *   VRAM slot 0..N-1  = request[0].startTile .. startTile+count-1
     *   VRAM slot N..N+M-1 = request[1].startTile .. startTile+count-1
     *   ...
     * </pre>
     * The mapping's tileIndex is an index into this virtual VRAM, which we remap
     * to the actual source tile index in the full art array.
     *
     * @param mappings  original mapping frames with VRAM-relative tile indices
     * @param dplcFrames DPLC frames (one per mapping frame), or null/empty to skip
     * @return remapped mapping frames with absolute tile indices into the art array
     */
    private static List<SpriteMappingFrame> applyDplcRemap(
            List<SpriteMappingFrame> mappings, List<SpriteDplcFrame> dplcFrames) {
        if (dplcFrames == null || dplcFrames.isEmpty()) {
            LOG.warning("No DPLC frames available for remapping — tile indices may be wrong");
            return mappings;
        }

        List<SpriteMappingFrame> remapped = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            SpriteMappingFrame frame = mappings.get(i);

            if (i >= dplcFrames.size()) {
                // No corresponding DPLC frame — keep original
                remapped.add(frame);
                continue;
            }

            // Build VRAM-slot → source-tile remap table from DPLC requests
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

            // Remap each piece's tileIndex through the DPLC table.
            // Multi-tile pieces (e.g., 4x4 = 16 tiles) use tileIndex + offset for
            // each tile. If the DPLC-remapped tiles are contiguous, we keep the piece
            // as-is. If they span non-contiguous DPLC requests, we must split into
            // individual 1x1 sub-pieces with correct remapped tile indices.
            List<SpriteMappingPiece> remappedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileIdx = piece.tileIndex();
                int wTiles = piece.widthTiles();
                int hTiles = piece.heightTiles();
                int tileCount = wTiles * hTiles;

                if (tileIdx < 0 || tileIdx >= vramToSource.length) {
                    LOG.fine("DPLC remap: frame " + i + " piece tileIndex " + tileIdx +
                            " exceeds VRAM slots (" + vramToSource.length + ")");
                    remappedPieces.add(piece);
                    continue;
                }

                int remappedBase = vramToSource[tileIdx];

                // Check if all tiles in this piece are contiguous after remapping
                boolean contiguous = true;
                for (int t = 1; t < tileCount; t++) {
                    int vramSlot = tileIdx + t;
                    if (vramSlot >= vramToSource.length ||
                            vramToSource[vramSlot] != remappedBase + t) {
                        contiguous = false;
                        break;
                    }
                }

                if (contiguous) {
                    // All tiles map contiguously — just remap the base index
                    remappedPieces.add(new SpriteMappingPiece(
                            piece.xOffset(), piece.yOffset(),
                            wTiles, hTiles,
                            remappedBase, piece.hFlip(), piece.vFlip(),
                            piece.paletteIndex(), piece.priority()));
                } else {
                    // Non-contiguous — split into 1x1 sub-pieces.
                    // VDP uses column-major ordering: tileOffset = tx * heightTiles + ty
                    for (int tx = 0; tx < wTiles; tx++) {
                        for (int ty = 0; ty < hTiles; ty++) {
                            int tileOffset = tx * hTiles + ty;
                            int vramSlot = tileIdx + tileOffset;
                            int remappedTile = (vramSlot < vramToSource.length)
                                    ? vramToSource[vramSlot] : tileIdx + tileOffset;
                            int xOff = piece.xOffset() + tx * 8;
                            int yOff = piece.yOffset() + ty * 8;
                            remappedPieces.add(new SpriteMappingPiece(
                                    xOff, yOff, 1, 1,
                                    remappedTile, piece.hFlip(), piece.vFlip(),
                                    piece.paletteIndex(), piece.priority()));
                        }
                    }
                }
            }
            remapped.add(new SpriteMappingFrame(remappedPieces));
        }

        LOG.fine("Applied DPLC remap to " + remapped.size() + " Knuckles mapping frames");
        return remapped;
    }
}
