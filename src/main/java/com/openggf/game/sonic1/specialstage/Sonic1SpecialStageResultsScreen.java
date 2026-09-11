package com.openggf.game.sonic1.specialstage;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.ResultsScreen;
import com.openggf.game.sonic1.S1SpriteDataLoader;
import com.openggf.game.sonic1.Sonic1ResultsMappingLoader;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 special-stage results screen (Obj7E behavior).
 *
 * <p>Implements the three message scenarios:
 * 1) Failed to get emerald: "SPECIAL STAGE"
 * 2) Got an emerald: "CHAOS EMERALDS"
 * 3) Got all emeralds: "SONIC GOT THEM ALL"
 *
 * <p>Art loading is self-contained (loaded directly from ROM) so the results
 * screen works regardless of whether a level is loaded. This follows the same
 * pattern as the S2 {@code SpecialStageResultsScreenObjectInstance}.
 */
public final class Sonic1SpecialStageResultsScreen implements ResultsScreen {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SpecialStageResultsScreen.class.getName());

    private enum Scenario {
        FAILED_TO_GET_EMERALD,
        GOT_CHAOS_EMERALD,
        GOT_ALL_CHAOS_EMERALDS
    }

    // Sonic 1 has six emeralds.
    private static final int SONIC1_CHAOS_EMERALD_COUNT = 6;

    // Obj7E sequencing/timing.
    private static final int STATE_SLIDE_IN = 0;
    private static final int STATE_PRE_TALLY_DELAY = 1;
    private static final int STATE_RING_TALLY = 2;
    private static final int STATE_POST_TALLY_DELAY = 3;
    private static final int STATE_CONTINUE_JINGLE = 4;
    private static final int STATE_CONTINUE_DELAY = 5;
    /**
     * {@code SSR_Exit} (routine $A on the plain path, $12 after a continue --
     * "_incObj/7E, 7F Special Stage Results and Chaos Emeralds.asm":156-158).
     * The wait that precedes it only advances {@code obRoutine}; the exit body
     * is a routine of its own, so the card runs one more whole
     * {@code SS_NormalExit} iteration -- {@code ExecuteObjects} reaching
     * {@code SSR_Exit}, which sets {@code f_restart} -- before that loop's
     * {@code tst.w (f_restart).w} can see it (sonic.asm:3401-3412).
     */
    private static final int STATE_EXIT = 6;
    private static final int SLIDE_SPEED_PIXELS_PER_FRAME = 16;
    private static final int PRE_TALLY_DELAY_FRAMES = 180;
    private static final int POST_TALLY_DELAY_FRAMES = 180;
    /**
     * ROM {@code SSR_RingBonus.finished}
     * ("_incObj/7E, 7F Special Stage Results and Chaos Emeralds.asm":143-151):
     * with {@code ss_continue_rings} or more rings the card does NOT take the
     * plain three-second exit wait. {@code SSR_Wait} counts one second,
     * {@code SSR_Continue} spends a frame of its own on the jingle and the
     * mini-Sonic element, and {@code SSR_Wait} then counts six more seconds --
     * 421 frames where the ordinary path spends 180.
     * <p>
     * The recorded bridge confirms each boundary. Its aux stream shows the
     * card's root object appearing on frame 67, the remaining FIVE elements
     * (four plus the continue tally, {@code SSR_Loop}'s {@code addq.w #1,d1})
     * on frame 85, and the emerald object that {@code SSR_Move.reachedXTarget}
     * spawns on frame 121: an 18-frame {@code SSR_ChkPLC} and a 36-frame
     * slide, which with these waits accounts for all 800 rows.
     */
    private static final int CONTINUE_RINGS = 50;
    private static final int PRE_CONTINUE_DELAY_FRAMES = 60;
    private static final int CONTINUE_JINGLE_FRAMES = 1;
    private static final int POST_CONTINUE_DELAY_FRAMES = 360;
    private static final int TALLY_DECREMENT = 10;
    private static final int TALLY_TICK_INTERVAL = 4;

    // Composite results-frame indices from Sonic1ResultsMappingLoader.
    private static final int FRAME_SCORE = 2;
    private static final int FRAME_RING_BONUS = 4;
    private static final int FRAME_OVAL = 5;
    private static final int FRAME_CHAOS_EMERALDS = 10;
    private static final int FRAME_SPECIAL_STAGE = 11;
    private static final int FRAME_GOT_THEM_ALL = 12;

    // Obj7E position config (VDP coordinates).
    private static final int TEXT_START_X = 0x20;
    private static final int TEXT_TARGET_X = 0x120;
    private static final int TEXT_GOT_ALL_START_X = 0x18;
    private static final int TEXT_GOT_ALL_TARGET_X = 0x118;
    private static final int SCORE_START_X = 0x320;
    private static final int SCORE_TARGET_X = 0x120;
    private static final int RING_START_X = 0x360;
    private static final int RING_TARGET_X = 0x120;
    private static final int OVAL_START_X = 0x1EC;
    private static final int OVAL_TARGET_X = 0x11C;
    private static final int TEXT_Y = 0xC4;
    private static final int SCORE_Y = 0x118;
    private static final int RING_Y = 0x128;
    private static final int OVAL_Y = 0xC4;

    // Obj7F emerald positions.
    private static final int[] EMERALD_X_POSITIONS = {0x110, 0x128, 0xF8, 0x140, 0xE0, 0x158};
    private static final int EMERALD_Y = 0xF0;
    private static final float[][] EMERALD_COLORS = {
            {0.25f, 0.55f, 1.0f},
            {1.0f, 0.95f, 0.25f},
            {1.0f, 0.45f, 0.65f},
            {0.30f, 0.95f, 0.35f},
            {1.0f, 0.70f, 0.25f},
            {0.80f, 0.45f, 1.0f}
    };

    // Score digit layout in the composite results sheet.
    // Sonic 1 score is 6 digits max (clamped to 999999 in AddPoints).
    private static final int SCORE_DIGITS_COUNT = 6;
    private static final int SCORE_DIGIT_TILES = SCORE_DIGITS_COUNT * 2;
    private static final int SCORE_DIGITS_START_INDEX =
            (Sonic1Constants.VRAM_RESULTS_HUD_TEXT + 0x1A) - Sonic1Constants.VRAM_RESULTS_BASE;

    // GPU cache base IDs (avoids collision with level 0x20000, SS 0x10000, HUD 0x28000).
    private static final int PATTERN_BASE = PatternAtlasRange.TITLE_CARDS.base();
    private static final int EMERALD_PATTERN_BASE = PatternAtlasRange.TITLE_CARDS.base() + 0x1000;

    // Score digit copy constants (matching Sonic1ObjectArtProvider).
    private static final int RESULTS_SCORE_DIGIT_PAIR_COUNT = 8;
    private static final int RESULTS_SCORE_DIGIT_TILE_COUNT = RESULTS_SCORE_DIGIT_PAIR_COUNT * 2;
    private static final int HUD_TEXT_E_PAIR_INDEX = 22;

    // Disassembly palette IDs (REV00). REV01 shifts IDs; resolve via Sonic offset.
    private static final int DISASM_SONIC_PALETTE_ID = 3;
    private static final int DISASM_SS_RESULT_PALETTE_ID = 17;
    private static final int SS_RESULT_PALETTE_OFFSET_FROM_SONIC =
            DISASM_SS_RESULT_PALETTE_ID - DISASM_SONIC_PALETTE_ID;
    private static final int PALETTE_RAM_BASE = 0xFB00;

    private final Scenario scenario;
    private final int stageIndex;

    private final int ringsCollected;
    private int state = STATE_SLIDE_IN;
    private boolean continueAwarded;
    private int stateTimer;
    private int totalFrames;
    private boolean complete;
    /** True once SSR_ChkPLC has completed its routine-0 readiness poll. */
    private boolean plcReadinessPassed;
    private int frameCounter;

    private int textX;
    private final int textTargetX;
    private int scoreX = SCORE_START_X;
    private int ringX = RING_START_X;
    private int ovalX = OVAL_START_X;

    private int ringBonus;

    private int lastRingBonus = Integer.MIN_VALUE;
    private int lastScoreValue = Integer.MIN_VALUE;
    private final Pattern blankDigit = new Pattern();

    // Self-contained art state.
    private Pattern[] combinedPatterns;
    private Pattern[] sourceDigitPatterns;
    private PatternSpriteRenderer resultsRenderer;
    private PatternSpriteRenderer emeraldRenderer;
    private boolean artLoaded;
    private boolean artCached;

    public Sonic1SpecialStageResultsScreen(int ringsCollected, boolean gotEmerald,
            int stageIndex, int totalEmeraldCount) {
        this.stageIndex = stageIndex;
        this.ringsCollected = Math.max(0, ringsCollected);
        this.ringBonus = this.ringsCollected * 10;
        if (!gotEmerald) {
            this.scenario = Scenario.FAILED_TO_GET_EMERALD;
            this.textX = TEXT_START_X;
            this.textTargetX = TEXT_TARGET_X;
        } else if (totalEmeraldCount >= SONIC1_CHAOS_EMERALD_COUNT) {
            this.scenario = Scenario.GOT_ALL_CHAOS_EMERALDS;
            this.textX = TEXT_GOT_ALL_START_X;
            this.textTargetX = TEXT_GOT_ALL_TARGET_X;
        } else {
            this.scenario = Scenario.GOT_CHAOS_EMERALD;
            this.textX = TEXT_START_X;
            this.textTargetX = TEXT_TARGET_X;
        }
    }

    @Override
    public void update(int frameCounter, Object context) {
        this.frameCounter = Math.max(this.frameCounter, frameCounter);
        if (complete) {
            return;
        }

        if (!plcReadinessPassed) {
            Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
            if (plcService != null && plcService.isBusy()) {
                return;
            }
            plcReadinessPassed = true;
        }

        totalFrames++;
        stateTimer++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_PRE_TALLY_DELAY -> updatePreTallyDelay();
            case STATE_RING_TALLY -> updateRingTally();
            case STATE_POST_TALLY_DELAY -> updatePostTallyDelay();
            case STATE_CONTINUE_JINGLE -> updateContinueJingle();
            case STATE_CONTINUE_DELAY -> updateContinueDelay();
            case STATE_EXIT -> complete = true;
            default -> complete = true;
        }
    }

    private void updateSlideIn() {
        textX = moveTowards(textX, textTargetX, SLIDE_SPEED_PIXELS_PER_FRAME);
        scoreX = moveTowards(scoreX, SCORE_TARGET_X, SLIDE_SPEED_PIXELS_PER_FRAME);
        ringX = moveTowards(ringX, RING_TARGET_X, SLIDE_SPEED_PIXELS_PER_FRAME);
        ovalX = moveTowards(ovalX, OVAL_TARGET_X, SLIDE_SPEED_PIXELS_PER_FRAME);

        // Obj7E advances when the ring bonus row reaches target.
        if (ringX == RING_TARGET_X) {
            state = STATE_PRE_TALLY_DELAY;
            stateTimer = 0;
        }
    }

    private void updatePreTallyDelay() {
        if (stateTimer >= PRE_TALLY_DELAY_FRAMES) {
            state = STATE_RING_TALLY;
            stateTimer = 0;
        }
    }

    private void updateRingTally() {
        if (ringBonus <= 0) {
            playTallyEndSound();
            state = STATE_POST_TALLY_DELAY;
            stateTimer = 0;
            return;
        }

        int decrement = Math.min(TALLY_DECREMENT, ringBonus);
        ringBonus -= decrement;
        GameServices.gameState().addScore(decrement);

        if ((stateTimer % TALLY_TICK_INTERVAL) == 0) {
            playTickSound();
        }
    }

    private void updatePostTallyDelay() {
        if (stateTimer < postTallyDelayFrames()) {
            return;
        }
        if (ringsCollected < CONTINUE_RINGS) {
            // SSR_Wait routine 8 advances to routine $A, SSR_Exit.
            state = STATE_EXIT;
            stateTimer = 0;
            return;
        }
        state = STATE_CONTINUE_JINGLE;
        stateTimer = 0;
    }

    /** One second before the continue jingle, three seconds without one. */
    private int postTallyDelayFrames() {
        return ringsCollected >= CONTINUE_RINGS
                ? PRE_CONTINUE_DELAY_FRAMES : POST_TALLY_DELAY_FRAMES;
    }

    /**
     * {@code SSR_Continue}, which owns a frame of its own before the six-second
     * wait it arms. The continue itself was already awarded inside the stage
     * ("_incObj/09 Sonic in Special Stage.asm":638), so this owns only the
     * jingle, the mini-Sonic element, and that frame.
     */
    private void updateContinueJingle() {
        if (stateTimer < CONTINUE_JINGLE_FRAMES) {
            return;
        }
        continueAwarded = true;
        playContinueSound();
        state = STATE_CONTINUE_DELAY;
        stateTimer = 0;
    }

    private void updateContinueDelay() {
        if (stateTimer >= POST_CONTINUE_DELAY_FRAMES) {
            // SSR_Wait routine $10 advances to routine $12, SSR_Exit.
            state = STATE_EXIT;
            stateTimer = 0;
        }
    }

    /** True once the continue jingle has played, for the mini-Sonic element. */
    public boolean isContinueAwarded() {
        return continueAwarded;
    }

    private void playContinueSound() {
        try {
            GameServices.audio().playSfx(Sonic1Sfx.GOT_CONTINUE.id);
        } catch (RuntimeException e) {
            // Audio failure must not stall the card's timing.
        }
    }

    @Override
    public boolean isComplete() {
        // The special-stage mode loop exits only once Obj7E has completed and
        // the final PLC poll sees an empty FIFO. This is deliberately separate
        // from SSR_ChkPLC: later work must not freeze the running card.
        Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
        return complete && (plcService == null || !plcService.isBusy());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        Camera camera = GameServices.cameraOrNull();
        if (camera == null) {
            return;
        }

        ensureArtCached();

        if (resultsRenderer == null) {
            appendPlaceholder(commands, camera);
            return;
        }

        updateDynamicNumberPatterns();

        // Draw oval first so other text appears on top.
        resultsRenderer.drawFrameIndex(FRAME_OVAL,
                toWorldX(ovalX, camera), toWorldY(OVAL_Y, camera), false, false);

        resultsRenderer.drawFrameIndex(getScenarioFrameIndex(),
                toWorldX(textX, camera), toWorldY(TEXT_Y, camera), false, false);

        resultsRenderer.drawFrameIndex(FRAME_SCORE,
                toWorldX(scoreX, camera), toWorldY(SCORE_Y, camera), false, false);

        resultsRenderer.drawFrameIndex(FRAME_RING_BONUS,
                toWorldX(ringX, camera), toWorldY(RING_Y, camera), false, false);

        appendEmeraldIndicators(commands, camera);

        GraphicsManager graphicsManager = GameServices.graphics();
        if (graphicsManager != null) {
            graphicsManager.flushPatternBatch();
        }
    }

    // ===== Self-contained art loading =====

    /**
     * Loads all art from ROM and builds renderers. Follows the same pattern as
     * {@code SpecialStageResultsScreenObjectInstance.loadArt()} in S2.
     */
    private void loadArt() {
        try {
            RomManager romManager = GameServices.rom();
            if (!romManager.isRomAvailable()) {
                LOGGER.warning("ROM not available for S1 SS results art loading");
                return;
            }
            Rom rom = romManager.getRom();

            // Load title card patterns (Nem_TitleCard) -> VRAM $580+
            Pattern[] titleCardPatterns = PatternDecompressor.nemesis(rom,
                    Sonic1Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");

            // Load HUD text patterns (Nem_Hud: SCORE/TIME/RINGS labels) -> VRAM $6CA+
            Pattern[] hudTextPatterns = PatternDecompressor.nemesis(rom,
                    Sonic1Constants.ART_NEM_HUD_ADDR, "HUDText");

            // Load HUD digit patterns (uncompressed, for number rendering)
            Pattern[] hudDigitPatterns = loadUncompressedPatterns(rom,
                    Sonic1Constants.ART_UNC_HUD_NUMBERS_ADDR,
                    Sonic1Constants.ART_UNC_HUD_NUMBERS_SIZE, "HUDNumbers");

            if (titleCardPatterns.length == 0 || hudTextPatterns.length == 0) {
                LOGGER.warning("Failed to load essential results screen art");
                return;
            }

            // Build composite pattern array matching Sonic1ObjectArtProvider.loadResultsScreenArt()
            int hudTextStartIndex = Sonic1Constants.VRAM_RESULTS_HUD_TEXT - Sonic1Constants.VRAM_RESULTS_BASE;
            int hudScoreDigitsStartIndex =
                    (Sonic1Constants.VRAM_RESULTS_HUD_TEXT + 0x18) - Sonic1Constants.VRAM_RESULTS_BASE;
            int totalSize = Math.max(
                    hudTextStartIndex + hudTextPatterns.length,
                    hudScoreDigitsStartIndex + RESULTS_SCORE_DIGIT_TILE_COUNT);

            combinedPatterns = new Pattern[totalSize];
            for (int i = 0; i < totalSize; i++) {
                combinedPatterns[i] = new Pattern();
            }

            // Copy title card patterns at index RESULTS_TILE_ADJUST (0x10)
            int titleCardStart = Sonic1Constants.RESULTS_TILE_ADJUST;
            for (int i = 0; i < titleCardPatterns.length && (titleCardStart + i) < totalSize; i++) {
                combinedPatterns[titleCardStart + i] = titleCardPatterns[i];
            }

            // Copy HUD text patterns
            for (int i = 0; i < hudTextPatterns.length && (hudTextStartIndex + i) < totalSize; i++) {
                combinedPatterns[hudTextStartIndex + i] = hudTextPatterns[i];
            }

            // Copy score digit tiles (E + seven zeros)
            copyResultsScoreDigitTiles(combinedPatterns, hudScoreDigitsStartIndex,
                    hudTextPatterns, hudDigitPatterns);

            // Preserve source digit patterns for tally updates
            if (hudDigitPatterns.length >= 20) {
                sourceDigitPatterns = new Pattern[hudDigitPatterns.length];
                for (int i = 0; i < hudDigitPatterns.length; i++) {
                    sourceDigitPatterns[i] = new Pattern();
                    sourceDigitPatterns[i].copyFrom(hudDigitPatterns[i]);
                }
            }

            // Create results sprite sheet and renderer
            List<SpriteMappingFrame> mappings = Sonic1ResultsMappingLoader.load(RomByteReader.fromRom(rom));
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(combinedPatterns, mappings, 0, 1);
            resultsRenderer = new PatternSpriteRenderer(sheet);

            // Load emerald art and create emerald renderer
            loadEmeraldArt(rom);

            artLoaded = true;
            LOGGER.info("S1 SS results art loaded: " + totalSize + " composite patterns, "
                    + mappings.size() + " frames");

        } catch (IOException e) {
            LOGGER.warning("Failed to load S1 SS results art: " + e.getMessage());
            combinedPatterns = null;
        }
    }

    private void loadEmeraldArt(Rom rom) {
        Pattern[] emeraldPatterns = PatternDecompressor.nemesis(rom,
                Sonic1Constants.ART_NEM_SS_RESULT_EM_ADDR, "SSResultEmerald");
        if (emeraldPatterns.length == 0) {
            LOGGER.warning("Failed to load SS results emerald art");
            return;
        }

        List<SpriteMappingFrame> frames;
        try {
            frames = S1SpriteDataLoader.loadMappingFrames(
                    RomByteReader.fromRom(rom), Sonic1Constants.MAP_SS_RESULT_EMERALDS_ADDR);
        } catch (IOException e) {
            LOGGER.warning("Failed to load SS results emerald mappings: " + e.getMessage());
            return;
        }

        ObjectSpriteSheet sheet = new ObjectSpriteSheet(emeraldPatterns, frames, 0, 1);
        emeraldRenderer = new PatternSpriteRenderer(sheet);
    }

    /**
     * Ensures art is loaded from ROM and cached to GPU. Also restores the
     * special-stage results palette entry exactly as PalLoad would, so emerald
     * colors match the original.
     */
    private void ensureArtCached() {
        if (artCached) {
            return;
        }

        if (!artLoaded) {
            loadArt();
        }

        if (combinedPatterns == null || resultsRenderer == null) {
            return;
        }

        GraphicsManager graphicsManager = GameServices.graphics();
        if (graphicsManager == null) {
            return;
        }

        // Cache results patterns
        resultsRenderer.ensurePatternsCached(graphicsManager, PATTERN_BASE);

        // Cache emerald patterns
        if (emeraldRenderer != null) {
            emeraldRenderer.ensurePatternsCached(graphicsManager, EMERALD_PATTERN_BASE);
        }

        // Restore palettes as the ROM does for palid_SSResult.
        restorePalettes(graphicsManager);

        artCached = true;
        LOGGER.fine("S1 SS results art cached to GPU");
    }

    /**
     * Restores SS result palettes using the palette table destination and size,
     * matching PalLoad semantics.
     */
    private void restorePalettes(GraphicsManager graphicsManager) {
        try {
            RomManager romManager = GameServices.rom();
            if (!romManager.isRomAvailable()) {
                return;
            }
            Rom rom = romManager.getRom();

            PaletteEntry entry = resolveSsResultPaletteEntry(rom);
            if (entry.byteCount <= 0) {
                return;
            }

            byte[] paletteData = rom.readBytes(entry.dataAddress, entry.byteCount);
            int startLine = Math.max(0, (entry.destination - PALETTE_RAM_BASE) / Palette.PALETTE_SIZE_IN_ROM);
            int linesToUpload = Math.min(4 - startLine, paletteData.length / Palette.PALETTE_SIZE_IN_ROM);
            for (int i = 0; i < linesToUpload; i++) {
                int start = i * Palette.PALETTE_SIZE_IN_ROM;
                int end = start + Palette.PALETTE_SIZE_IN_ROM;
                Palette pal = new Palette();
                pal.fromSegaFormat(Arrays.copyOfRange(paletteData, start, end));
                graphicsManager.cachePaletteTexture(pal, startLine + i);
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to restore palettes: " + e.getMessage());
        }
    }

    private PaletteEntry resolveSsResultPaletteEntry(Rom rom) throws IOException {
        int sonicPaletteId = findSonicPaletteId(rom);
        int ssResultPaletteId = sonicPaletteId + SS_RESULT_PALETTE_OFFSET_FROM_SONIC;
        return readPaletteEntry(rom, ssResultPaletteId);
    }

    private int findSonicPaletteId(Rom rom) throws IOException {
        for (int id = 2; id < 10; id++) {
            int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + id * 8;
            int destination = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
            int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
            int byteCount = (countWord + 1) * 4;
            if (destination == 0xFB00 && byteCount == Palette.PALETTE_SIZE_IN_ROM) {
                return id;
            }
        }
        LOGGER.fine("Failed to detect Sonic palette ID from table, using disassembly default");
        return DISASM_SONIC_PALETTE_ID;
    }

    private PaletteEntry readPaletteEntry(Rom rom, int paletteId) throws IOException {
        int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + paletteId * 8;
        int dataAddress = rom.read32BitAddr(entryAddr) & 0x00FFFFFF;
        int destination = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
        int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
        int byteCount = (countWord + 1) * 4;
        return new PaletteEntry(dataAddress, destination, byteCount);
    }

    private record PaletteEntry(int dataAddress, int destination, int byteCount) {
    }

    // ===== Dynamic number patterns =====

    private void updateDynamicNumberPatterns() {
        if (resultsRenderer == null || combinedPatterns == null || sourceDigitPatterns == null) {
            return;
        }

        int score = Math.max(0, GameServices.gameState().getScore());
        if (ringBonus == lastRingBonus && score == lastScoreValue) {
            return;
        }

        // Ring bonus digits live at tile slots 8-15 in the composite sheet.
        int ringDigitStart = Sonic1Constants.S1_RESULTS_BONUS_DIGIT_GROUP_TILES;
        if (ringDigitStart + Sonic1Constants.S1_RESULTS_BONUS_DIGIT_GROUP_TILES > combinedPatterns.length) {
            return;
        }
        ensurePatternSlots(combinedPatterns, ringDigitStart, Sonic1Constants.S1_RESULTS_BONUS_DIGIT_GROUP_TILES);
        writeBonusValue(combinedPatterns, ringDigitStart, ringBonus, sourceDigitPatterns);

        // Score digits live in the HUD text range.
        if (SCORE_DIGITS_START_INDEX + SCORE_DIGIT_TILES <= combinedPatterns.length) {
            ensurePatternSlots(combinedPatterns, SCORE_DIGITS_START_INDEX, SCORE_DIGIT_TILES);
            writeScoreValue(combinedPatterns, score, sourceDigitPatterns);
        }

        GraphicsManager graphicsManager = GameServices.graphics();
        if (graphicsManager != null) {
            resultsRenderer.updatePatternRange(graphicsManager, ringDigitStart,
                    Sonic1Constants.S1_RESULTS_BONUS_DIGIT_GROUP_TILES);
            if (SCORE_DIGITS_START_INDEX + SCORE_DIGIT_TILES <= combinedPatterns.length) {
                resultsRenderer.updatePatternRange(graphicsManager, SCORE_DIGITS_START_INDEX, SCORE_DIGIT_TILES);
            }
        }

        lastRingBonus = ringBonus;
        lastScoreValue = score;
    }

    // ===== Rendering helpers =====

    private void appendPlaceholder(List<GLCommand> commands, Camera camera) {
        int messageX = toWorldX(textX, camera);
        int messageY = toWorldY(TEXT_Y, camera);
        int scoreXWorld = toWorldX(scoreX, camera);
        int scoreYWorld = toWorldY(SCORE_Y, camera);
        int ringXWorld = toWorldX(ringX, camera);
        int ringYWorld = toWorldY(RING_Y, camera);

        renderPlaceholderBox(commands, messageX - 70, messageY - 8, 140, 16, 0.2f, 0.75f, 1.0f);
        renderPlaceholderBox(commands, scoreXWorld - 80, scoreYWorld - 8, 160, 16, 1.0f, 0.95f, 0.35f);
        renderPlaceholderBox(commands, ringXWorld - 80, ringYWorld - 8, 160, 16, 1.0f, 0.7f, 0.25f);
        renderPlaceholderBox(commands, toWorldX(ovalX, camera) - 20, toWorldY(OVAL_Y, camera) - 20,
                40, 40, 0.55f, 0.7f, 1.0f);
        appendEmeraldIndicators(commands, camera);
    }

    private void appendEmeraldIndicators(List<GLCommand> commands, Camera camera) {
        // Flash effect: on odd frames, show emeralds; on even frames, blank (30fps blink).
        if ((totalFrames & 1) == 0) {
            return;
        }

        int emeraldCount = Math.min(SONIC1_CHAOS_EMERALD_COUNT, GameServices.gameState().getEmeraldCount());
        if (emeraldCount <= 0) {
            return;
        }

        for (int i = 0; i < emeraldCount; i++) {
            int worldX = toWorldX(EMERALD_X_POSITIONS[i], camera);
            int worldY = toWorldY(EMERALD_Y, camera);
            if (emeraldRenderer != null) {
                emeraldRenderer.drawFrameIndex(i, worldX, worldY, false, false);
            } else {
                float[] color = EMERALD_COLORS[i];
                renderPlaceholderBox(commands, worldX - 8, worldY - 8, 16, 16,
                        color[0], color[1], color[2]);
            }
        }
    }

    // ===== Pattern/digit helpers =====

    private void ensurePatternSlots(Pattern[] patterns, int start, int count) {
        int end = Math.min(patterns.length, start + count);
        for (int i = Math.max(0, start); i < end; i++) {
            if (patterns[i] == null) {
                patterns[i] = new Pattern();
            }
        }
    }

    private void writeBonusValue(Pattern[] destination, int startIndex, int value, Pattern[] digits) {
        int[] divisors = {1000, 100, 10, 1};
        boolean hasDigit = false;

        for (int i = 0; i < divisors.length; i++) {
            int divisor = divisors[i];
            int digit = value / divisor;
            value %= divisor;
            int tileIndex = startIndex + (i * 2);
            boolean isLastDigit = i == divisors.length - 1;
            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                copyDigit(destination, tileIndex, digit, digits);
            } else {
                destination[tileIndex].copyFrom(blankDigit);
                destination[tileIndex + 1].copyFrom(blankDigit);
            }
        }
    }

    private void writeScoreValue(Pattern[] destination, int score, Pattern[] digits) {
        int clampedScore = Math.min(score, 9_999_999);
        int divisor = 100_000;
        boolean hasDigit = false;
        for (int i = 0; i < SCORE_DIGITS_COUNT; i++) {
            int digit = (clampedScore / divisor) % 10;
            int tileIndex = SCORE_DIGITS_START_INDEX + (i * 2);
            boolean isLastDigit = i == SCORE_DIGITS_COUNT - 1;
            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                copyDigit(destination, tileIndex, digit, digits);
            } else {
                destination[tileIndex].copyFrom(blankDigit);
                destination[tileIndex + 1].copyFrom(blankDigit);
            }
            divisor /= 10;
        }
    }

    private void copyDigit(Pattern[] destination, int destinationIndex, int digit, Pattern[] digits) {
        int sourceIndex = digit * 2;
        if (sourceIndex + 1 >= digits.length || destinationIndex + 1 >= destination.length) {
            return;
        }
        destination[destinationIndex].copyFrom(digits[sourceIndex]);
        destination[destinationIndex + 1].copyFrom(digits[sourceIndex + 1]);
    }

    /**
     * Copies the initial score digit tiles into the composite array.
     * Tile pair at startIndex is "E" (from HUD text), followed by six "0" pairs.
     * The final pair at $6F0-$6F1 is forced blank for trailing blank mappings.
     */
    private static void copyResultsScoreDigitTiles(Pattern[] dest, int startIndex,
            Pattern[] hudTextPatterns, Pattern[] hudDigitPatterns) {
        if (dest == null || hudDigitPatterns == null || hudDigitPatterns.length < 2) {
            return;
        }
        // "E" pair from HUD text
        copyPatternPair(dest, startIndex, hudTextPatterns, HUD_TEXT_E_PAIR_INDEX);
        // Six "0" pairs from digit patterns.
        for (int pair = 1; pair < RESULTS_SCORE_DIGIT_PAIR_COUNT - 1; pair++) {
            copyPatternPair(dest, startIndex + (pair * 2), hudDigitPatterns, 0);
        }
        // Explicitly clear trailing pair in case source HUD art has non-blank data there.
        int trailingPairIndex = startIndex + ((RESULTS_SCORE_DIGIT_PAIR_COUNT - 1) * 2);
        if (trailingPairIndex >= 0 && trailingPairIndex + 1 < dest.length) {
            dest[trailingPairIndex].copyFrom(new Pattern());
            dest[trailingPairIndex + 1].copyFrom(new Pattern());
        }
    }

    private static void copyPatternPair(Pattern[] dest, int destIndex, Pattern[] src, int srcIndex) {
        if (src == null || srcIndex < 0 || srcIndex + 1 >= src.length) {
            return;
        }
        if (destIndex < 0 || destIndex + 1 >= dest.length) {
            return;
        }
        if (dest[destIndex] == null) {
            dest[destIndex] = new Pattern();
        }
        if (dest[destIndex + 1] == null) {
            dest[destIndex + 1] = new Pattern();
        }
        dest[destIndex].copyFrom(src[srcIndex]);
        dest[destIndex + 1].copyFrom(src[srcIndex + 1]);
    }

    // ===== ROM art loading helpers =====

    private Pattern[] loadUncompressedPatterns(Rom rom, int address, int size, String name) {
        try {
            byte[] data = rom.readBytes(address, size);
            if (data.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
                LOGGER.warning("Inconsistent uncompressed art size for " + name);
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
        } catch (Exception e) {
            LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    // ===== Coordinate / utility helpers =====

    private int toWorldX(int vdpX, Camera camera) {
        return camera.getX() + (vdpX - 128);
    }

    private int toWorldY(int vdpY, Camera camera) {
        return camera.getY() + (vdpY - 128);
    }

    private int moveTowards(int current, int target, int speed) {
        if (current == target) {
            return current;
        }
        if (current < target) {
            return Math.min(target, current + speed);
        }
        return Math.max(target, current - speed);
    }

    private void playTickSound() {
        try {
            GameServices.audio().playSfx(Sonic1Sfx.SWITCH.id);
        } catch (Exception ignored) {
            // Audio failure should not affect results flow.
        }
    }

    private void playTallyEndSound() {
        try {
            GameServices.audio().playSfx(Sonic1Sfx.TALLY.id);
        } catch (Exception ignored) {
            // Audio failure should not affect results flow.
        }
    }

    private void renderPlaceholderBox(List<GLCommand> commands, int x, int y, int width, int height,
            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
    }

    private int getScenarioFrameIndex() {
        return switch (scenario) {
            case FAILED_TO_GET_EMERALD -> FRAME_SPECIAL_STAGE;
            case GOT_CHAOS_EMERALD -> FRAME_CHAOS_EMERALDS;
            case GOT_ALL_CHAOS_EMERALDS -> FRAME_GOT_THEM_ALL;
        };
    }

    // Package-private accessors for tests.
    int getState() {
        return state;
    }

    int getRingBonus() {
        return ringBonus;
    }

    int getScenarioFrameForTesting() {
        return getScenarioFrameIndex();
    }

    int getStageIndex() {
        return stageIndex;
    }

    int getObservedFrameCounter() {
        return frameCounter;
    }
}
