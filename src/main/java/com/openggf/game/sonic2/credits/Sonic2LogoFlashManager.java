package com.openggf.game.sonic2.credits;

import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.util.PatternDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the "Sonic the Hedgehog 2" logo with palette flash animation
 * shown after all 21 credit text slides.
 * <p>
 * From the disassembly ({@code s2.asm}, EndgameCredits):
 * <ol>
 *   <li>Fade to black and clear screen</li>
 *   <li>Load {@code ArtNem_EndingTitle} (Nemesis) to VRAM 0x0000</li>
 *   <li>Decode {@code MapEng_EndGameLogo} (Enigma, startingArtTile=0) to Chunk_Table</li>
 *   <li>Write 16x6 tile mapping to Plane A at planeLoc(64,12,11)</li>
 *   <li>Call {@code EndgameLogoFlash} once (applies initial palette frame)</li>
 *   <li>Wait 0x3B frames (initial pause)</li>
 *   <li>Loop 0x257 frames: increment CreditsScreenIndex, call {@code EndgameLogoFlash},
 *       stop flash at index 0x5E, then check B/C/A/Start to skip</li>
 * </ol>
 * <p>
 * The {@code EndgameLogoFlash} subroutine:
 * <ul>
 *   <li>Writes to {@code Normal_palette+2} (palette line 0, colors 1-12)</li>
 *   <li>Only updates on even CreditsScreenIndex values below 0x24 (36)</li>
 *   <li>Uses {@code byte_A0EC[index/2]} to look up a palette frame index</li>
 *   <li>Each frame is 24 bytes (12 colors) from {@code pal_A0FE}</li>
 * </ul>
 */
public class Sonic2LogoFlashManager {

    private static final Logger LOGGER = Logger.getLogger(Sonic2LogoFlashManager.class.getName());

    /**
     * Unique pattern base ID for the logo art GPU caching.
     * Avoids collision with credit text (0xE0000), S1 credit text (0xB0000),
     * title screen credit text (0x80000), and other ranges.
     */
    private static final int PATTERN_BASE = PatternAtlasRange.SONIC2_CREDITS_LOGO.base();

    /** Enigma mapping grid dimensions (from ROM: 16-1 and 6-1 in d1/d2). */
    private static final int LOGO_TILE_WIDTH = 16;
    private static final int LOGO_TILE_HEIGHT = 6;

    /**
     * Screen position of the logo's top-left corner.
     * From planeLoc(64,12,11): column 12, row 11 on the VDP nametable.
     * Screen pixel position = (col * 8, row * 8) = (96, 88).
     */
    private static final int LOGO_SCREEN_X = 12 * 8; // 96
    private static final int LOGO_SCREEN_Y = 11 * 8; // 88

    /** Screen center used as the origin for PatternSpriteRenderer.drawFrameIndex. */
    private static final int SCREEN_CENTER_X = 160;
    private static final int SCREEN_CENTER_Y = 112;

    /**
     * ROM threshold: CreditsScreenIndex >= 0x24 stops palette updates.
     * Only even indices below this trigger updates, so 18 updates total (0,2,...,34).
     */
    private static final int FLASH_STOP_INDEX = 0x24;

    /** State machine phases. */
    public enum State {
        LOADING,
        INITIAL_PAUSE,
        FLASHING,
        HOLDING,
        DONE
    }

    private State state = State.LOADING;
    private int frameCounter;

    // Palette flash state matching ROM's CreditsScreenIndex
    private int creditsScreenIndex;

    // ROM data
    private int[] strobeSequence;       // byte_A0EC: 18-byte lookup table
    private Palette[] cyclePalettes;    // 9 palette frames from pal_A0FE
    private Palette basePalette;        // current palette line 0 (modified in place)

    // Rendering
    private PatternSpriteRenderer renderer;
    private Pattern[] logoPatterns;
    private boolean initialized;

    /**
     * Initializes the logo flash manager: loads art, decodes mapping, sets up renderer.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        state = State.LOADING;

        RomManager romManager = GameServices.rom();
        if (!romManager.isRomAvailable()) {
            LOGGER.warning("ROM not available for logo flash");
            state = State.DONE;
            return;
        }

        Rom rom;
        try {
            rom = romManager.getRom();
        } catch (IOException e) {
            LOGGER.warning("Failed to get ROM: " + e.getMessage());
            state = State.DONE;
            return;
        }

        try {
            // Load Nemesis-compressed logo art (ArtNem_EndingTitle loaded to VRAM 0x0000)
            logoPatterns = PatternDecompressor.nemesis(rom,
                    Sonic2Constants.ART_NEM_ENDING_TITLE_ADDR, 65536, "logo");
            if (logoPatterns == null || logoPatterns.length == 0) {
                LOGGER.warning("Failed to load logo patterns");
                state = State.DONE;
                return;
            }

            // Decode Enigma mapping (startingArtTile = 0, matching ROM's move.w #0,d0)
            SpriteMappingFrame logoFrame = decodeEnigmaMapping(rom);
            if (logoFrame == null) {
                LOGGER.warning("Failed to decode logo mapping");
                state = State.DONE;
                return;
            }

            // Load strobe sequence and palette cycle data from ROM
            loadStrobeSequence(rom);
            loadPaletteCycleData(rom);

            // Create sprite sheet and renderer
            List<SpriteMappingFrame> frames = List.of(logoFrame);
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(
                    logoPatterns,
                    frames,
                    -1, // absolute palette mode (each piece carries its own palette index)
                    1
            );
            renderer = new PatternSpriteRenderer(sheet);

            // Cache to GPU
            cacheToGpu();

            // Initialize palette: apply first strobe frame (index 0 from byte_A0EC)
            creditsScreenIndex = 0;
            applyLogoFlash();

            // Begin initial pause (ROM: move.w #$3B,d0; dbf d0,-)
            // dbf decrements d0 then branches while d0 >= 0, so runs 0x3C (60) times.
            // We use the raw value and check < 0 in update().
            state = State.INITIAL_PAUSE;
            frameCounter = Sonic2CreditsData.LOGO_INITIAL_PAUSE;

            initialized = true;
            LOGGER.info("Logo flash manager initialized: " + logoPatterns.length + " patterns, "
                    + LOGO_TILE_WIDTH + "x" + LOGO_TILE_HEIGHT + " tile mapping");

        } catch (Exception e) {
            LOGGER.warning("Failed to initialize logo flash: " + e.getMessage());
            state = State.DONE;
        }
    }

    /**
     * Updates the logo flash state machine each frame.
     * <p>
     * Matches the ROM's loop structure exactly:
     * <ul>
     *   <li>INITIAL_PAUSE: wait 0x3C (60) frames ({@code move.w #$3B,d0; dbf d0,-})</li>
     *   <li>FLASHING: increment CreditsScreenIndex each frame, apply palette flash,
     *       loop while index &lt; 0x5E without touching hold counter</li>
     *   <li>HOLDING: decrement hold counter ({@code dbf d6,-} with d6 starting at 0x257
     *       = 600 frames), check B/C/A/Start for skip</li>
     * </ul>
     *
     * @param inputHandler input handler for button skip detection, may be null
     */
    public void update(InputHandler inputHandler) {
        switch (state) {
            case LOADING -> {
                // Should not reach here after initialize(), but guard
            }
            case INITIAL_PAUSE -> {
                // ROM: move.w #$3B,d0; dbf d0,- (runs 0x3C = 60 frames)
                frameCounter--;
                if (frameCounter < 0) {
                    state = State.FLASHING;
                }
            }
            case FLASHING -> {
                // ROM main loop body: increment index, apply flash, check if done
                creditsScreenIndex++;
                applyLogoFlash();

                // ROM: cmpi.w #$5E,(CreditsScreenIndex).w / blo.s -
                // While index < 0x5E, loop back without checking buttons or d6.
                // When index first reaches 0x5E, fall through to button check + dbf
                // in the same frame (not next frame).
                if (creditsScreenIndex >= Sonic2CreditsData.LOGO_FLASH_FRAMES) {
                    // Flash animation complete, transition to hold phase
                    state = State.HOLDING;
                    // ROM: d6 starts at 0x257, dbf runs 0x258 = 600 times
                    frameCounter = Sonic2CreditsData.LOGO_HOLD_FRAMES;

                    // Fall through to button check + dbf on this same frame
                    if (checkButtonSkip(inputHandler)) {
                        state = State.DONE;
                        return;
                    }
                    frameCounter--;
                    if (frameCounter < 0) {
                        state = State.DONE;
                    }
                }
            }
            case HOLDING -> {
                // ROM continues the same loop body after flash ends:
                // increment index (harmless, just makes EndgameLogoFlash a no-op
                // since index >= 0x24), check button, decrement d6
                creditsScreenIndex++;
                applyLogoFlash(); // no-op when index >= 0x24

                // ROM: check Ctrl_1_Press for B/C/A/Start
                if (checkButtonSkip(inputHandler)) {
                    state = State.DONE;
                    return;
                }

                // ROM: dbf d6,- (d6 counts from 0x257 down to -1)
                frameCounter--;
                if (frameCounter < 0) {
                    state = State.DONE;
                }
            }
            case DONE -> {
                // Nothing to do
            }
        }
    }

    /**
     * Draws the logo on screen.
     */
    public void draw() {
        if (!initialized || renderer == null || !renderer.isReady()) {
            return;
        }
        if (state == State.LOADING || state == State.DONE) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();
        gm.beginPatternBatch();
        // Draw at screen center; piece offsets are relative to center
        renderer.drawFrameIndex(0, SCREEN_CENTER_X, SCREEN_CENTER_Y);
        gm.flushPatternBatch();
    }

    /**
     * Returns true when the logo sequence is complete and the caller should
     * transition to the title screen.
     */
    public boolean isDone() {
        return state == State.DONE;
    }

    /**
     * Returns the current state.
     */
    public State getState() {
        return state;
    }

    // ========================================================================
    // ROM data loading
    // ========================================================================

    /**
     * Loads Nemesis-compressed logo art from ROM.
     */

    /**
     * Decodes the Enigma-compressed logo mapping and builds a SpriteMappingFrame.
     * <p>
     * The ROM decodes with startingArtTile=0 and writes a 16x6 grid to Plane A
     * at planeLoc(64,12,11). Each decoded word is a VDP nametable entry:
     * <ul>
     *   <li>Bits 0-10: tile index</li>
     *   <li>Bit 11: H-flip</li>
     *   <li>Bit 12: V-flip</li>
     *   <li>Bits 13-14: palette line</li>
     *   <li>Bit 15: priority</li>
     * </ul>
     */
    private SpriteMappingFrame decodeEnigmaMapping(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic2Constants.MAP_ENG_END_GAME_LOGO_ADDR, 1024);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, 0);

                int wordCount = decompressed.length / 2;
                int[] map = new int[wordCount];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount; i++) {
                    map[i] = buf.getShort() & 0xFFFF;
                }

                LOGGER.fine("Decoded logo mapping: " + wordCount + " tiles"
                        + " (expected " + (LOGO_TILE_WIDTH * LOGO_TILE_HEIGHT) + ")");

                return buildMappingFrame(map);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to decode logo mapping: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds a SpriteMappingFrame from the decoded Enigma tile map.
     * <p>
     * Each tile becomes a 1x1 SpriteMappingPiece. Offsets are relative to
     * screen center so that drawFrameIndex(0, 160, 112) places the logo
     * at screen position (96, 88).
     */
    private SpriteMappingFrame buildMappingFrame(int[] map) {
        List<SpriteMappingPiece> pieces = new ArrayList<>();

        for (int row = 0; row < LOGO_TILE_HEIGHT; row++) {
            for (int col = 0; col < LOGO_TILE_WIDTH; col++) {
                int idx = row * LOGO_TILE_WIDTH + col;
                if (idx >= map.length) {
                    break;
                }

                int word = map[idx];
                int tileIndex = word & 0x7FF;
                boolean hFlip = (word & 0x0800) != 0;
                boolean vFlip = (word & 0x1000) != 0;
                int palette = (word >> 13) & 0x3;
                boolean priority = (word & 0x8000) != 0;

                // Skip blank tiles (tile index 0 with no flags is empty background)
                // Actually, tile 0 could be valid art; keep all tiles

                // Pixel offset from screen center
                int xOff = LOGO_SCREEN_X + col * 8 - SCREEN_CENTER_X;
                int yOff = LOGO_SCREEN_Y + row * 8 - SCREEN_CENTER_Y;

                pieces.add(new SpriteMappingPiece(
                        xOff, yOff, 1, 1, tileIndex,
                        hFlip, vFlip, palette, priority));
            }
        }

        return new SpriteMappingFrame(pieces);
    }

    /**
     * Loads the 18-byte strobe index sequence from ROM (byte_A0EC).
     */
    private void loadStrobeSequence(Rom rom) {
        try {
            byte[] data = rom.readBytes(Sonic2Constants.LOGO_FLASH_STROBE_ADDR,
                    Sonic2Constants.LOGO_FLASH_STROBE_SIZE);
            strobeSequence = new int[data.length];
            for (int i = 0; i < data.length; i++) {
                strobeSequence[i] = data[i] & 0xFF;
            }
            LOGGER.fine("Loaded strobe sequence: " + strobeSequence.length + " entries");
        } catch (Exception e) {
            LOGGER.warning("Failed to load strobe sequence: " + e.getMessage());
            strobeSequence = new int[]{0}; // fallback
        }
    }

    /**
     * Loads the 9 palette cycle frames from ROM (pal_A0FE).
     * Each frame is 24 bytes = 12 colors at 2 bytes per color.
     */
    private void loadPaletteCycleData(Rom rom) {
        try {
            byte[] data = rom.readBytes(Sonic2Constants.PAL_ENDING_CYCLE_ADDR,
                    Sonic2Constants.PAL_ENDING_CYCLE_SIZE);
            int frameSize = Sonic2Constants.PAL_ENDING_CYCLE_FRAME_SIZE;
            int frameCount = Sonic2CreditsData.PALETTE_CYCLE_FRAME_COUNT;

            cyclePalettes = new Palette[frameCount];
            for (int f = 0; f < frameCount; f++) {
                Palette pal = new Palette();
                int offset = f * frameSize;
                // Each frame has 12 colors (24 bytes). These replace Normal_palette+2
                // which is palette line 0, colors 1-12.
                int colorCount = frameSize / Palette.BYTES_PER_COLOR;
                for (int c = 0; c < colorCount && c < Palette.PALETTE_SIZE; c++) {
                    int byteOffset = offset + c * Palette.BYTES_PER_COLOR;
                    if (byteOffset + 1 < data.length) {
                        pal.colors[c].fromSegaFormat(data, byteOffset);
                    }
                }
                cyclePalettes[f] = pal;
            }

            // Initialize base palette as a copy that we modify in place
            basePalette = new Palette();

            LOGGER.fine("Loaded " + frameCount + " palette cycle frames");
        } catch (Exception e) {
            LOGGER.warning("Failed to load palette cycle data: " + e.getMessage());
            cyclePalettes = null;
        }
    }

    // ========================================================================
    // Palette flash logic (EndgameLogoFlash)
    // ========================================================================

    /**
     * Applies the palette flash for the current CreditsScreenIndex.
     * Matches the ROM's EndgameLogoFlash subroutine exactly:
     * <ul>
     *   <li>If index >= 0x24: no-op (palette stays as-is)</li>
     *   <li>If index is odd: no-op (only update on even frames)</li>
     *   <li>Otherwise: read byte_A0EC[index/2], multiply by 0x18, copy 12 colors
     *       from pal_A0FE+offset to Normal_palette+2 (palette line 0, colors 1-12)</li>
     * </ul>
     */
    private void applyLogoFlash() {
        if (cyclePalettes == null || strobeSequence == null || basePalette == null) {
            return;
        }

        // ROM: cmpi.w #$24,d0 / bhs.s return
        if (creditsScreenIndex >= FLASH_STOP_INDEX) {
            return;
        }

        // ROM: btst #0,d0 / bne.s return (skip odd indices)
        if ((creditsScreenIndex & 1) != 0) {
            return;
        }

        // ROM: lsr.w #1,d0; move.b byte_A0EC(pc,d0.w),d0
        int strobeIdx = creditsScreenIndex >> 1;
        if (strobeIdx >= strobeSequence.length) {
            return;
        }
        int paletteFrameIdx = strobeSequence[strobeIdx];
        if (paletteFrameIdx >= cyclePalettes.length) {
            return;
        }

        // Copy 12 colors from the cycle palette into basePalette colors 1-12
        // ROM writes to Normal_palette+2 (skipping color 0 / background)
        Palette source = cyclePalettes[paletteFrameIdx];
        for (int c = 0; c < 12; c++) {
            Palette.Color src = source.colors[c];
            basePalette.colors[c + 1].r = src.r;
            basePalette.colors[c + 1].g = src.g;
            basePalette.colors[c + 1].b = src.b;
        }

        // Upload modified palette to GPU
        GraphicsManager gm = GameServices.graphics();
        if (gm != null && !gm.isHeadlessMode()) {
            gm.cachePaletteTexture(basePalette, 0);
        }
    }

    // ========================================================================
    // Button skip check
    // ========================================================================

    /**
     * Checks for B/C/A/Start button press to skip the hold phase.
     * ROM checks {@code Ctrl_1_Press} for {@code button_B_mask|button_C_mask|button_A_mask|button_start_mask}.
     * JUMP key covers A/B/C; PAUSE_KEY (Enter) covers Start.
     */
    private boolean checkButtonSkip(InputHandler inputHandler) {
        if (inputHandler == null) {
            return false;
        }
        SonicConfigurationService config = GameServices.configuration();
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        int startKey = config.getInt(SonicConfiguration.PAUSE_KEY);
        return (jumpKey > 0 && inputHandler.isKeyPressed(jumpKey))
                || (startKey > 0 && inputHandler.isKeyPressed(startKey))
                || inputHandler.logical().menuAccept();
    }

    // ========================================================================
    // GPU caching
    // ========================================================================

    /**
     * Caches logo patterns and initial palette to GPU.
     */
    private void cacheToGpu() {
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Cache patterns
        renderer.ensurePatternsCached(gm, PATTERN_BASE);

        // Cache initial palette (all black on line 0, the flash will fill in colors)
        if (basePalette != null) {
            gm.cachePaletteTexture(basePalette, 0);
        }
    }
}
