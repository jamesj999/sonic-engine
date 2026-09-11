package com.openggf.game.sonic2.credits;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Loads all Nemesis-compressed art and palettes for the Sonic 2 ending cutscene.
 * <p>
 * The ending has three character variants determined by emerald count and player mode:
 * <ul>
 *   <li>SONIC: Normal Sonic ending (default)</li>
 *   <li>SUPER_SONIC: All 7 emeralds collected, not Tails-alone</li>
 *   <li>TAILS: Tails-alone player mode</li>
 * </ul>
 * Each variant loads different character art, palette, and animal companion sprite.
 * <p>
 * ROM reference: EndingSequence (s2.asm lines 12998-13100), ObjCA_Init.
 */
public class Sonic2EndingArt {

    private static final Logger LOGGER = Logger.getLogger(Sonic2EndingArt.class.getName());

    /**
     * Character variant for the ending sequence.
     * Determines which art, palette, and animal companion to load.
     */
    public enum EndingRoutine {
        /** Normal Sonic ending: Flicky birds. */
        SONIC,
        /** Super Sonic ending (7 emeralds): Eagle birds. */
        SUPER_SONIC,
        /** Tails-alone ending: Chicken birds. */
        TAILS
    }

    // ========================================================================
    // GPU pattern base IDs (unique ranges to avoid collision with other systems)
    // ========================================================================

    /**
     * Pattern base for ending character art (Sonic/Super Sonic/Tails).
     * Uses 0xF0000 range to avoid collision with credits text (0xE0000),
     * title screen (0x60000-0x80000), and level patterns.
     */
    static final int PATTERN_BASE_CHARACTER = PatternAtlasRange.SONIC2_ENDING_CHARACTER.base();

    /** Pattern base for ending final tornado closeup. */
    static final int PATTERN_BASE_FINAL_TORNADO = PatternAtlasRange.SONIC2_ENDING_FINAL_TORNADO.base();

    /** Pattern base for ending photo frame art. */
    static final int PATTERN_BASE_PICS = PatternAtlasRange.SONIC2_ENDING_PICS.base();

    /** Pattern base for ending mini tornado sprites. */
    static final int PATTERN_BASE_MINI_TORNADO = PatternAtlasRange.SONIC2_ENDING_MINI_TORNADO.base();

    /** Pattern base for cloud art. */
    static final int PATTERN_BASE_CLOUDS = PatternAtlasRange.SONIC2_ENDING_CLOUDS.base();

    /** Pattern base for animal art (flicky/eagle/chicken). */
    static final int PATTERN_BASE_ANIMAL = PatternAtlasRange.SONIC2_ENDING_ANIMAL.base();

    /**
     * Unified VRAM-relative pattern base for ObjCF sprite mapping rendering.
     * Art is cached at {@code PATTERN_BASE_VRAM + vramTileIndex} so that
     * ROM mapping pieces (which contain absolute VRAM tile indices) resolve
     * directly to GPU pattern IDs when using this as the base.
     */
    static final int PATTERN_BASE_VRAM = PatternAtlasRange.SONIC2_ENDING_VRAM.base();

    // ========================================================================
    // Loaded art
    // ========================================================================

    private Pattern[] characterPatterns;
    private Pattern[] finalTornadoPatterns;
    private Pattern[] picsPatterns;
    private Pattern[] miniTornadoPatterns;
    private Pattern[] tornadoPatterns;      // Standard tornado (ArtNem_Tornado at VRAM 0x0500)
    private Pattern[] cloudPatterns;
    private Pattern[] animalPatterns;
    private Pattern[] playerPatterns;       // Standard uncompressed player art (Sonic/Tails)
    private int playerArtTile;              // VRAM tile base for player art ($0780 Sonic, $07A0 Tails)
    private Pattern[] pilotPatterns;        // Pilot character art (opposite of main: Tails/Sonic)
    private int pilotArtTile;              // VRAM tile base for pilot art

    private Palette[] endingPalettes;

    private EndingRoutine loadedRoutine;
    private boolean initialized;

    // ========================================================================
    // Routine determination
    // ========================================================================

    /**
     * Determines the ending routine based on emerald count and player mode.
     * <p>
     * ROM reference: EndingSequence (s2.asm ~line 13030):
     * <pre>
     *   cmpi.w  #7,(Emerald_count).w
     *   bne.s   +
     *   cmpi.b  #2,(Player_mode).w   ; Tails alone?
     *   beq.s   +
     *   moveq   #2,d0                ; Super Sonic ending
     *   ...
     *   cmpi.b  #2,(Player_mode).w
     *   bne.s   +
     *   moveq   #4,d0                ; Tails ending
     * </pre>
     *
     * @return the ending routine to use
     */
    public static EndingRoutine determineEndingRoutine() {
        GameStateManager gs = GameServices.gameStateOrNull();
        if (gs == null) {
            return EndingRoutine.SONIC;
        }

        // Query actual PlayerCharacter from level event manager (matches ROM's Player_mode check)
        boolean tailsAlone = false;
        Sonic2LevelEventManager lem = GameServices.hasRuntime()
                ? (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider()
                : null;
        if (lem != null) {
            tailsAlone = (lem.getPlayerCharacter() == PlayerCharacter.TAILS_ALONE);
        }

        if (gs.hasAllEmeralds() && !tailsAlone) {
            return EndingRoutine.SUPER_SONIC;
        }
        if (tailsAlone) {
            return EndingRoutine.TAILS;
        }
        return EndingRoutine.SONIC;
    }

    // ========================================================================
    // Art loading
    // ========================================================================

    /**
     * Loads all ending art and palettes from ROM for the given routine.
     * <p>
     * Art loaded per routine:
     * <ul>
     *   <li>Character-specific: ArtNem_EndingSonic / EndingSuperSonic / EndingTails</li>
     *   <li>Shared: EndingFinalTornado, EndingPics, EndingMiniTornado, Clouds</li>
     *   <li>Animal companion: Flicky (SONIC), Eagle (SUPER_SONIC), Chicken (TAILS)</li>
     * </ul>
     *
     * @param rom     the ROM to load from
     * @param routine the character variant
     */
    public void loadArt(Rom rom, EndingRoutine routine) {
        this.loadedRoutine = routine;

        // Load character-specific art
        int characterAddr = switch (routine) {
            case SONIC -> Sonic2Constants.ART_NEM_ENDING_SONIC_ADDR;
            case SUPER_SONIC -> Sonic2Constants.ART_NEM_ENDING_SUPER_SONIC_ADDR;
            case TAILS -> Sonic2Constants.ART_NEM_ENDING_TAILS_ADDR;
        };
        characterPatterns = PatternDecompressor.nemesis(rom, characterAddr, 8192, "EndingCharacter");

        // Load shared art
        finalTornadoPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_ENDING_FINAL_TORNADO_ADDR, 16384, "EndingFinalTornado");
        picsPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_ENDING_PICS_ADDR, 16384, "EndingPics");
        miniTornadoPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_ENDING_MINI_TORNADO_ADDR, 8192, "EndingMiniTornado");
        // ROM loads standard Tornado art at VRAM 0x0500 for ObjCF frames that reference those tiles
        tornadoPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_TORNADO_ADDR, 16384, "Tornado");
        cloudPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_CLOUDS_ADDR, 8192, "Clouds");

        // Load animal companion art
        int animalAddr = switch (routine) {
            case SONIC -> Sonic2Constants.ART_NEM_FLICKY_ADDR;
            case SUPER_SONIC -> Sonic2Constants.ART_NEM_EAGLE_ADDR;
            case TAILS -> Sonic2Constants.ART_NEM_CHICKEN_ADDR;
        };
        animalPatterns = PatternDecompressor.nemesis(rom, animalAddr, 4096, "EndingAnimal");

        // Load standard uncompressed player art for CHARACTER_APPEAR rendering.
        // ROM: ObjCA routine $A spawns a real Sonic/Tails object using standard art
        // still in VRAM from the DEZ level. Our renderer needs to load it explicitly.
        int playerArtAddr;
        int playerArtSize;
        switch (routine) {
            case SONIC, SUPER_SONIC -> {
                playerArtAddr = Sonic2Constants.ART_UNC_SONIC_ADDR;
                playerArtSize = Sonic2Constants.ART_UNC_SONIC_SIZE;
                playerArtTile = Sonic2Constants.ART_TILE_SONIC;
            }
            case TAILS -> {
                playerArtAddr = Sonic2Constants.ART_UNC_TAILS_ADDR;
                playerArtSize = Sonic2Constants.ART_UNC_TAILS_SIZE;
                playerArtTile = Sonic2Constants.ART_TILE_TAILS;
            }
            default -> {
                playerArtAddr = Sonic2Constants.ART_UNC_SONIC_ADDR;
                playerArtSize = Sonic2Constants.ART_UNC_SONIC_SIZE;
                playerArtTile = Sonic2Constants.ART_TILE_SONIC;
            }
        }
        playerPatterns = loadUncompressedPatterns(rom, playerArtAddr, playerArtSize, "Player");

        // Load pilot character art (opposite of main character).
        // ROM: ObjB2_Animate_Pilot uses LoadSonicDynPLC_Part2 or LoadTailsDynPLC_Part2
        // to load the OTHER character's art into VRAM for the cockpit pilot overlay.
        // When main=Sonic → pilot=Tails; when main=Tails → pilot=Sonic.
        int pilotArtAddr;
        int pilotArtSz;
        switch (routine) {
            case SONIC, SUPER_SONIC -> {
                // Pilot is Tails
                pilotArtAddr = Sonic2Constants.ART_UNC_TAILS_ADDR;
                pilotArtSz = Sonic2Constants.ART_UNC_TAILS_SIZE;
                pilotArtTile = Sonic2Constants.ART_TILE_TAILS;
            }
            case TAILS -> {
                // Pilot is Sonic
                pilotArtAddr = Sonic2Constants.ART_UNC_SONIC_ADDR;
                pilotArtSz = Sonic2Constants.ART_UNC_SONIC_SIZE;
                pilotArtTile = Sonic2Constants.ART_TILE_SONIC;
            }
            default -> {
                pilotArtAddr = Sonic2Constants.ART_UNC_TAILS_ADDR;
                pilotArtSz = Sonic2Constants.ART_UNC_TAILS_SIZE;
                pilotArtTile = Sonic2Constants.ART_TILE_TAILS;
            }
        }
        pilotPatterns = loadUncompressedPatterns(rom, pilotArtAddr, pilotArtSz, "Pilot");

        LOGGER.info("Ending art loaded for routine " + routine
                + ": character=" + patternCount(characterPatterns)
                + " finalTornado=" + patternCount(finalTornadoPatterns)
                + " pics=" + patternCount(picsPatterns)
                + " miniTornado=" + patternCount(miniTornadoPatterns)
                + " tornado=" + patternCount(tornadoPatterns)
                + " clouds=" + patternCount(cloudPatterns)
                + " animal=" + patternCount(animalPatterns)
                + " player=" + patternCount(playerPatterns)
                + " pilot=" + patternCount(pilotPatterns));
    }

    /**
     * Loads ending palettes from ROM for the given routine.
     * <p>
     * ROM reference: EndingSequence palette loading (s2.asm ~line 13050):
     * <pre>
     *   Palette line 0: Character palette (Sonic/Super Sonic/Tails)
     *   Palette line 1: Tails palette (always loaded from PAL_ENDING_TAILS_ADDR)
     *   Palette line 2: Background palette (PAL_ENDING_BACKGROUND_ADDR)
     *   Palette line 3: Photos palette (PAL_ENDING_PHOTOS_ADDR)
     * </pre>
     * For Super Sonic, palette line 0 uses PAL_ENDING_SUPER_SONIC_ADDR.
     *
     * @param rom     the ROM to load from
     * @param routine the character variant
     */
    public void loadPalettes(Rom rom, EndingRoutine routine) {
        endingPalettes = new Palette[4];

        // ROM reference: EndingSequence (s2.asm line 13076-13081) loads ALL 4 palette
        // lines as a contiguous 128-byte block from Pal_AC7E into Target_palette.
        // The binary layout at Pal_AC7E is:
        //   Line 0 (+0):  "Ending Sonic.bin"       (32 bytes) - character colors
        //   Line 1 (+32): "Ending Tails.bin" pt.1   (32 bytes) - secondary colors
        //   Line 2 (+64): "Ending Tails.bin" pt.2   (32 bytes) - backdrop/env colors
        //   Line 3 (+96): "Ending Background.bin" pt.1 (32 bytes) - sky gradient
        // IMPORTANT: PAL_ENDING_BACKGROUND_ADDR (0xACDE) is line 3, NOT line 2!
        // Line 2 is at PAL_ENDING_FULL_ADDR + 64 = 0xACBE (second half of Tails file).
        int baseAddr = Sonic2Constants.PAL_ENDING_FULL_ADDR;
        endingPalettes[1] = loadPalette(rom, baseAddr + 32, "EndingPalLine1");
        endingPalettes[2] = loadPalette(rom, baseAddr + 64, "EndingPalLine2");
        endingPalettes[3] = loadPalette(rom, baseAddr + 96, "EndingPalLine3");

        // Palette line 0: character-specific (Super Sonic replaces line 0)
        int charPalAddr = switch (routine) {
            case SONIC -> baseAddr;
            case SUPER_SONIC -> Sonic2Constants.PAL_ENDING_SUPER_SONIC_ADDR;
            case TAILS -> baseAddr; // Tails ending still uses Sonic line 0
        };
        endingPalettes[0] = loadPalette(rom, charPalAddr, "EndingPalLine0");

        LOGGER.info("Ending palettes loaded for routine " + routine);
    }

    /**
     * Caches all loaded art patterns and palettes to the GPU.
     * Must be called after {@link #loadArt} and {@link #loadPalettes}.
     */
    public void cacheToGpu() {
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Cache palettes
        if (endingPalettes != null) {
            for (int i = 0; i < endingPalettes.length; i++) {
                if (endingPalettes[i] != null) {
                    gm.cachePaletteTexture(endingPalettes[i], i);
                }
            }
        }

        // Cache all pattern sets at their dedicated GPU bases (used by photo/cloud rendering)
        cachePatternSet(gm, characterPatterns, PATTERN_BASE_CHARACTER, "character");
        cachePatternSet(gm, finalTornadoPatterns, PATTERN_BASE_FINAL_TORNADO, "finalTornado");
        cachePatternSet(gm, picsPatterns, PATTERN_BASE_PICS, "pics");
        cachePatternSet(gm, miniTornadoPatterns, PATTERN_BASE_MINI_TORNADO, "miniTornado");
        cachePatternSet(gm, cloudPatterns, PATTERN_BASE_CLOUDS, "clouds");
        cachePatternSet(gm, animalPatterns, PATTERN_BASE_ANIMAL, "animal");

        // Also cache at VRAM-relative positions for ObjCF/Obj28 sprite mapping rendering.
        // ROM mapping pieces contain absolute VRAM tile indices, so caching at
        // PATTERN_BASE_VRAM + vramTileOffset allows direct lookup.
        cachePatternSet(gm, characterPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_CHARACTER, "character-vram");
        cachePatternSet(gm, finalTornadoPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_FINAL_TORNADO, "finalTornado-vram");
        cachePatternSet(gm, miniTornadoPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_MINI_TORNADO, "miniTornado-vram");
        // Standard Tornado art at VRAM 0x0500 — used by ObjCF frames referencing non-mini tornado tiles
        cachePatternSet(gm, tornadoPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_TORNADO, "tornado-vram");
        cachePatternSet(gm, cloudPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_CLOUDS, "clouds-vram");
        cachePatternSet(gm, animalPatterns, PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_ANIMAL_2, "animal-vram");
        // NOTE: Do NOT cache playerPatterns or pilotPatterns at VRAM positions here.
        // Both use DynamicPatternBank for DPLC-driven per-frame tile loading.
        // Caching full art at the VRAM base would pollute GPU tile positions beyond
        // what DPLC loads, creating ghost/after-image artifacts from stale tiles.

        initialized = true;
        LOGGER.info("Ending art cached to GPU");
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public Pattern[] getCharacterPatterns() {
        return characterPatterns;
    }

    public Pattern[] getFinalTornadoPatterns() {
        return finalTornadoPatterns;
    }

    public Pattern[] getPicsPatterns() {
        return picsPatterns;
    }

    public Pattern[] getMiniTornadoPatterns() {
        return miniTornadoPatterns;
    }

    public Pattern[] getCloudPatterns() {
        return cloudPatterns;
    }

    public Pattern[] getAnimalPatterns() {
        return animalPatterns;
    }

    public Palette[] getEndingPalettes() {
        return endingPalettes;
    }

    public EndingRoutine getLoadedRoutine() {
        return loadedRoutine;
    }

    public Pattern[] getPlayerPatterns() {
        return playerPatterns;
    }

    public int getPlayerArtTile() {
        return playerArtTile;
    }

    public Pattern[] getPilotPatterns() {
        return pilotPatterns;
    }

    public int getPilotArtTile() {
        return pilotArtTile;
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================


    /**
     * Loads uncompressed (raw) patterns from ROM. Used for standard player art
     * (ArtUnc_Sonic / ArtUnc_Tails) which is stored uncompressed in the ROM.
     */
    private Pattern[] loadUncompressedPatterns(Rom rom, int address, int size, String name) {
        try {
            byte[] data = rom.readBytes(address, size);
            int patternCount = size / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] patterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                patterns[i] = new Pattern();
                byte[] subArray = Arrays.copyOfRange(data,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                patterns[i].fromSegaFormat(subArray);
            }
            LOGGER.fine("Loaded " + patternCount + " " + name + " uncompressed patterns from ROM at 0x"
                    + Integer.toHexString(address));
            return patterns;
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load " + name + " uncompressed patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    /**
     * Loads a single 32-byte palette (1 VDP palette line) from ROM.
     */
    private Palette loadPalette(Rom rom, int address, String name) {
        try {
            byte[] data = rom.readBytes(address, 32);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            LOGGER.fine("Loaded " + name + " palette from ROM at 0x" + Integer.toHexString(address));
            return palette;
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load " + name + " palette: " + e.getMessage());
            return null;
        }
    }

    /**
     * Caches a set of patterns to the GPU at the given base index.
     */
    private void cachePatternSet(GraphicsManager gm, Pattern[] patterns, int baseIndex, String name) {
        if (patterns == null || patterns.length == 0) {
            return;
        }
        for (int i = 0; i < patterns.length; i++) {
            if (patterns[i] != null) {
                gm.cachePatternTexture(patterns[i], baseIndex + i);
            }
        }
        LOGGER.fine("Cached " + patterns.length + " " + name + " patterns to GPU at base 0x"
                + Integer.toHexString(baseIndex));
    }

    private static int patternCount(Pattern[] patterns) {
        return patterns != null ? patterns.length : 0;
    }
}
