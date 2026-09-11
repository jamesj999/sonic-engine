package com.openggf.game.sonic3k.levelselect;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameServices;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.sonic2.menu.MenuBackgroundAnimator;

import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSoundTestCatalog;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.glClearColor;

/**
 * Manages the Sonic 3&amp;K Level Select screen.
 *
 * <p>Implements a ROM-accurate level select with two-layer rendering:
 * Plane B background (SONICMILES animated text) and Plane A foreground
 * (zone names, act numbers, icons).
 *
 * <p>State machine: INACTIVE → FADE_IN → ACTIVE → EXITING
 */
public class Sonic3kLevelSelectManager implements LevelSelectProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kLevelSelectManager.class.getName());

    private static Sonic3kLevelSelectManager instance;

    private final SonicConfigurationService configService = GameServices.configuration();
    private final Sonic3kLevelSelectDataLoader dataLoader = new Sonic3kLevelSelectDataLoader();
    private final PatternDesc reusableDesc = new PatternDesc();
    private final PatternDesc highlightDesc = new PatternDesc();

    private MenuBackgroundAnimator menuBackgroundAnimator;

    private State state = State.INACTIVE;
    private int selectedIndex = 0;
    private int soundTestValue = 0;

    // Input handling
    private int upHoldTimer = 0;
    private int downHoldTimer = 0;
    private int leftHoldTimer = 0;
    private int rightHoldTimer = 0;

    // Fade timing
    private int fadeTimer = 0;
    private static final int FADE_DURATION = 16;

    // Fallback background color
    private static final float BG_R = 0.0f;
    private static final float BG_G = 0.0f;
    private static final float BG_B = 0.5f;

    /** Palette line used for icon rendering (separate from highlight line 3) */
    private static final int ICON_RENDER_PALETTE = 2;

    /** Projection-space viewport width; default 320 (native). */
    private int viewportWidth = Sonic3kLevelSelectConstants.SCREEN_WIDTH;

    public Sonic3kLevelSelectManager() {
    }

    public static synchronized Sonic3kLevelSelectManager getInstance() {
        if (instance == null) {
            instance = new Sonic3kLevelSelectManager();
        }
        return instance;
    }

    /**
     * Sets the projection-space viewport width for widescreen centering.
     *
     * <p>The level select content is always 320 px wide (one VDP plane). At widths
     * greater than 320 the content is shifted right by {@code (viewportWidth - 320) / 2}
     * so it stays visually centered. At native width 320 the offset is 0 — byte-identical.
     */
    @Override
    public void setViewportWidth(int width) {
        this.viewportWidth = Math.max(Sonic3kLevelSelectConstants.SCREEN_WIDTH, width);
    }

    /**
     * Returns the horizontal pixel offset to apply to all rendered elements so that
     * the 320-px-wide content block is centered within the current viewport.
     * Returns 0 at native width 320.
     */
    private int xOffset() {
        return (viewportWidth - Sonic3kLevelSelectConstants.SCREEN_WIDTH) / 2;
    }

    @Override
    public void initialize() {
        LOGGER.info("Initializing S3K level select screen");

        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }

        // Force palette re-upload
        dataLoader.resetCache();
        menuBackgroundAnimator = null;

        state = State.FADE_IN;
        fadeTimer = 0;
        selectedIndex = 0;
        soundTestValue = 0;
        resetHoldTimers();

        GameServices.audio().playMusic(Sonic3kMusic.DATA_SELECT.id);
        LOGGER.info("S3K level select initialized, entering FADE_IN state");
    }

    @Override
    public void update(InputHandler input) {
        switch (state) {
            case FADE_IN -> updateFadeIn();
            case ACTIVE -> updateActive(input);
            case INACTIVE, EXITING -> { }
        }
    }

    private void updateFadeIn() {
        fadeTimer++;
        if (fadeTimer >= FADE_DURATION) {
            state = State.ACTIVE;
        }
    }

    private void updateActive(InputHandler input) {
        int upKey = configService.getInt(SonicConfiguration.UP);
        int downKey = configService.getInt(SonicConfiguration.DOWN);
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);

        // Up/Down navigation
        if (input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP)) {
            if (upHoldTimer == 0 || (upHoldTimer >= Sonic3kLevelSelectConstants.HOLD_REPEAT_DELAY
                    && upHoldTimer % Sonic3kLevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveUp();
            }
            upHoldTimer++;
        } else {
            upHoldTimer = 0;
        }

        if (input.isDirectionHeld(downKey, AbstractPlayableSprite.INPUT_DOWN)) {
            if (downHoldTimer == 0 || (downHoldTimer >= Sonic3kLevelSelectConstants.HOLD_REPEAT_DELAY
                    && downHoldTimer % Sonic3kLevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveDown();
            }
            downHoldTimer++;
        } else {
            downHoldTimer = 0;
        }

        // Left/Right (column switch or sound test adjustment)
        if (input.isDirectionHeld(leftKey, AbstractPlayableSprite.INPUT_LEFT)) {
            if (leftHoldTimer == 0 || (leftHoldTimer >= Sonic3kLevelSelectConstants.HOLD_REPEAT_DELAY
                    && leftHoldTimer % Sonic3kLevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveLeft();
            }
            leftHoldTimer++;
        } else {
            leftHoldTimer = 0;
        }

        if (input.isDirectionHeld(rightKey, AbstractPlayableSprite.INPUT_RIGHT)) {
            if (rightHoldTimer == 0 || (rightHoldTimer >= Sonic3kLevelSelectConstants.HOLD_REPEAT_DELAY
                    && rightHoldTimer % Sonic3kLevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveRight();
            }
            rightHoldTimer++;
        } else {
            rightHoldTimer = 0;
        }

        // Jump/Start to select
        if (input.isKeyPressed(jumpKey) || input.logical().menuAccept()) {
            handleSelect();
        }

        // A button adds 0x10 to sound test value (like original, disasm line 8629)
        if (input.isKeyPressed(configService.getInt(SonicConfiguration.TEST))) {
            if (selectedIndex == Sonic3kLevelSelectConstants.SOUND_TEST_INDEX) {
                soundTestValue = (soundTestValue + 0x10) & 0xFF;
            }
        }
    }

    private void moveUp() {
        do {
            selectedIndex--;
            if (selectedIndex < 0) {
                selectedIndex = Sonic3kLevelSelectConstants.MENU_ENTRY_COUNT - 1;
            }
        } while (isDisabledEntry(selectedIndex));
    }

    private void moveDown() {
        do {
            selectedIndex++;
            if (selectedIndex >= Sonic3kLevelSelectConstants.MENU_ENTRY_COUNT) {
                selectedIndex = 0;
            }
        } while (isDisabledEntry(selectedIndex));
    }

    private void moveLeft() {
        if (selectedIndex == Sonic3kLevelSelectConstants.SOUND_TEST_INDEX) {
            soundTestValue--;
            if (soundTestValue < 0) {
                soundTestValue = 0xFF;
            }
        } else {
            int target = Sonic3kLevelSelectConstants.SWITCH_TABLE[selectedIndex];
            if (!isDisabledEntry(target)) {
                selectedIndex = target;
            }
        }
    }

    private void moveRight() {
        if (selectedIndex == Sonic3kLevelSelectConstants.SOUND_TEST_INDEX) {
            soundTestValue++;
            if (soundTestValue > 0xFF) {
                soundTestValue = 0;
            }
        } else {
            int target = Sonic3kLevelSelectConstants.SWITCH_TABLE[selectedIndex];
            if (!isDisabledEntry(target)) {
                selectedIndex = target;
            }
        }
    }

    private boolean isDisabledEntry(int index) {
        if (index < 0 || index >= Sonic3kLevelSelectConstants.LEVEL_ORDER.length) {
            return true;
        }
        return Sonic3kLevelSelectConstants.LEVEL_ORDER[index] == Sonic3kLevelSelectConstants.DISABLED_ENTRY;
    }

    private void handleSelect() {
        int zoneAct = Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex];

        if (zoneAct == Sonic3kLevelSelectConstants.DISABLED_ENTRY) {
            return; // Ignore disabled entries
        }

        if (zoneAct == Sonic3kLevelSelectConstants.SOUND_TEST_VALUE) {
            // Play the selected sound
            int soundId = Sonic3kSoundTestCatalog.toSoundId(soundTestValue);
            LOGGER.info("Playing sound test: 0x" + Integer.toHexString(soundTestValue)
                    + " -> soundId 0x" + Integer.toHexString(soundId));
            if (Sonic3kSoundTestCatalog.isMusicId(soundId)) {
                GameServices.audio().playMusic(soundId);
            } else if (Sonic3kSoundTestCatalog.isSfxId(soundId)) {
                GameServices.audio().playSfx(soundId);
            }
        } else {
            state = State.EXITING;
            LOGGER.info("S3K level select exiting for selection: " + selectedIndex);
        }
    }

    private void resetHoldTimers() {
        upHoldTimer = 0;
        downHoldTimer = 0;
        leftHoldTimer = 0;
        rightHoldTimer = 0;
    }

    // ===== Rendering =====

    @Override
    public void draw() {
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }
        dataLoader.cacheToGpu(gm);

        // Initialize SONICMILES animator on first draw
        Pattern[] smPatterns = dataLoader.getSonicMilesPatterns();
        if (menuBackgroundAnimator == null && smPatterns != null && smPatterns.length > 0) {
            // patternBase = PATTERN_BASE (not +1). The animator internally adds
            // DEST_TILE_OFFSET=1, so it writes to PATTERN_BASE+1..10, matching
            // the background mapping's tile indices 1-10.
            menuBackgroundAnimator = new MenuBackgroundAnimator(
                    smPatterns, gm, Sonic3kLevelSelectConstants.PATTERN_BASE);
            menuBackgroundAnimator.prime();
        }
        if (menuBackgroundAnimator != null) {
            menuBackgroundAnimator.update();
        }

        // Upload icon palette to palette line 2 (separate from highlight line 3).
        // In the original VDP both share line 3, but we need them independent
        // because the icon palette changes per-selection and would corrupt highlights.
        int iconIndex = selectedIndex < Sonic3kLevelSelectConstants.ICON_TABLE.length
                ? Sonic3kLevelSelectConstants.ICON_TABLE[selectedIndex] : -1;
        if (iconIndex >= 0) {
            Palette iconPalette = dataLoader.getIconPalette(iconIndex);
            if (iconPalette != null) {
                S3kFrontendPaletteUploader.cacheLine(gm, iconPalette, ICON_RENDER_PALETTE);
            }
        }

        gm.beginPatternBatch();

        // Render Plane B background (SONICMILES repeating text pattern, palette line 3).
        // Background expands to fill the full viewport width (tiled horizontally).
        // Do NOT apply xOffset() — background fills everything, not just the centered 320 box.
        // At native width 320 this is identical to the previous centered draw.
        int[] bgLayout = dataLoader.getBackgroundLayout();
        if (bgLayout != null && bgLayout.length > 0) {
            renderBackgroundTilemap(gm, bgLayout,
                    dataLoader.getBackgroundWidth(), dataLoader.getBackgroundHeight());
        }

        // Render Plane A foreground (level select tilemap — zone names, icons, borders).
        // Foreground stays centered via xOffset().
        int[] screenLayout = dataLoader.getScreenLayout();
        if (screenLayout != null && screenLayout.length > 0) {
            renderTilemap(gm, screenLayout,
                    Sonic3kLevelSelectConstants.PLANE_WIDTH, Sonic3kLevelSelectConstants.PLANE_HEIGHT);
            drawSelectionHighlight(gm, screenLayout,
                    Sonic3kLevelSelectConstants.PLANE_WIDTH, Sonic3kLevelSelectConstants.PLANE_HEIGHT);
        }

        // Draw sound test value
        int soundPalette = selectedIndex == Sonic3kLevelSelectConstants.SOUND_TEST_INDEX
                ? Sonic3kLevelSelectConstants.HIGHLIGHT_PALETTE_INDEX : 0;
        drawSoundTestValue(gm, soundPalette);

        // Draw zone icon
        drawZoneIcon(gm);

        gm.flushPatternBatch();

        // Apply fade effect
        float fadeAmount = 0.0f;
        if (state == State.FADE_IN) {
            fadeAmount = 1.0f - (float) fadeTimer / FADE_DURATION;
        }
        if (fadeAmount > 0.0f) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI,
                    -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    0.0f, 0.0f, 0.0f, fadeAmount,
                    0, 0, viewportWidth, Sonic3kLevelSelectConstants.SCREEN_HEIGHT
            ));
        }
    }

    /**
     * Renders a tilemap decoded from Enigma mappings (Plane A foreground).
     *
     * <p>The foreground is always 320 px wide and centered within the viewport
     * via {@link #xOffset()}.
     */
    private void renderTilemap(GraphicsManager gm, int[] map, int width, int height) {
        int xOff = xOffset();
        for (int ty = 0; ty < height; ty++) {
            int baseIndex = ty * width;
            for (int tx = 0; tx < width; tx++) {
                int idx = baseIndex + tx;
                if (idx >= map.length) continue;
                int word = map[idx];
                if (word == 0) continue;
                reusableDesc.set(word);
                int patternId = Sonic3kLevelSelectConstants.PATTERN_BASE + reusableDesc.getPatternIndex();
                gm.renderPatternWithId(patternId, reusableDesc, xOff + tx * 8, ty * 8);
            }
        }
    }

    /**
     * Renders the Plane B background tilemap tiled across the full viewport width.
     *
     * <p>The background pattern has period {@code width} tiles (= 320 px at native).
     * At widescreen widths the pattern is wrapped/repeated horizontally so that the
     * entire viewport is filled from {@code x=0} to {@code x=viewportWidth}.
     * No {@link #xOffset()} is applied — the background expands, it does not center.
     *
     * <p>Native parity: when {@code viewportWidth == 320} this draws exactly
     * {@code width} columns from {@code x=0}, identical to calling
     * {@link #renderTilemap} with {@code xOffset()==0}.
     */
    private void renderBackgroundTilemap(GraphicsManager gm, int[] map, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        // Number of tile columns needed to cover the full viewport width (round up).
        int tileColumns = (viewportWidth + 7) / 8;
        for (int ty = 0; ty < height; ty++) {
            for (int col = 0; col < tileColumns; col++) {
                // Wrap source column to tile the pattern horizontally.
                int tx = col % width;
                int idx = ty * width + tx;
                if (idx >= map.length) continue;
                int word = map[idx];
                if (word == 0) continue;
                reusableDesc.set(word);
                int patternId = Sonic3kLevelSelectConstants.PATTERN_BASE + reusableDesc.getPatternIndex();
                gm.renderPatternWithId(patternId, reusableDesc, col * 8, ty * 8);
            }
        }
    }

    /**
     * Draws the selection highlight by re-rendering marked tiles with the highlight palette.
     * Matches disasm LevelSelect_MarkFields (s3.asm line 8727).
     */
    private void drawSelectionHighlight(GraphicsManager gm, int[] map, int width, int height) {
        if (selectedIndex < 0 || selectedIndex >= Sonic3kLevelSelectConstants.MARK_TABLE.length) {
            return;
        }

        int[] mark = Sonic3kLevelSelectConstants.MARK_TABLE[selectedIndex];
        int row1 = mark[0];
        int col1 = mark[1] / 2;

        // Primary mark: 14-tile span for zone name
        renderHighlightSpan(gm, map, width, height, row1, col1,
                Sonic3kLevelSelectConstants.HIGHLIGHT_SPAN_LENGTH);

        // Secondary mark: single tile for act number
        int row2 = mark[2];
        int col2 = mark[3] / 2;
        if (row2 != 0 || col2 != 0) {
            renderHighlightTile(gm, map, width, height, row2, col2);
        }
    }

    private void renderHighlightSpan(GraphicsManager gm, int[] map, int width, int height,
                                     int row, int col, int length) {
        if (row < 0 || row >= height) return;
        for (int i = 0; i < length; i++) {
            int tileCol = col + i;
            if (tileCol >= width) break;
            renderHighlightTile(gm, map, width, height, row, tileCol);
        }
    }

    private void renderHighlightTile(GraphicsManager gm, int[] map, int width, int height,
                                     int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) return;
        int idx = row * width + col;
        if (idx >= map.length) return;
        int word = map[idx];
        if (word == 0) return;
        int patternIndex = word & 0x7FF;
        int flags = word & 0xF800;
        int adjusted = (flags & ~0x6000) | ((Sonic3kLevelSelectConstants.HIGHLIGHT_PALETTE_INDEX & 0x3) << 13) | patternIndex;
        highlightDesc.set(adjusted);
        int patternId = Sonic3kLevelSelectConstants.PATTERN_BASE + patternIndex;
        gm.renderPatternWithId(patternId, highlightDesc, xOffset() + col * 8, row * 8);
    }

    /**
     * Draws the sound test value as two hex digits.
     * Matches disasm LevelSelect_DrawSoundNumber (s3.asm line 8801).
     * Position: VRAM_Plane_A_Name_Table+$846 → row 16, col 35 (64-wide nametable).
     */
    private void drawSoundTestValue(GraphicsManager gm, int paletteIndex) {
        // VRAM offset $846: row = $846/128 = 16, col = ($846%128)/2 = 35
        // Shifted by xOffset() for widescreen centering
        int x = xOffset() + 35 * 8;
        int y = 16 * 8;

        int highNibble = (soundTestValue >> 4) & 0xF;
        int lowNibble = soundTestValue & 0xF;

        drawHexDigit(gm, highNibble, x, y, paletteIndex);
        drawHexDigit(gm, lowNibble, x + 8, y, paletteIndex);
    }

    /**
     * Draws a single hex digit using the font.
     * Matches disasm sub_6D48: digit 0-9 → tile digit+$10, A-F → tile digit+$14.
     */
    private void drawHexDigit(GraphicsManager gm, int digit, int x, int y, int paletteIndex) {
        int charIndex = Sonic3kLevelSelectConstants.getHexDigitTileIndex(digit);
        int patternId = Sonic3kLevelSelectConstants.PATTERN_BASE
                + Sonic3kLevelSelectConstants.FONT_OFFSET + charIndex;
        PatternDesc desc = new PatternDesc(charIndex | ((paletteIndex & 0x3) << 13));
        gm.renderPatternWithId(patternId, desc, x, y);
    }

    /**
     * Draws the zone preview icon.
     * Matches disasm LevelSelect_DrawIcon (s3.asm line 8828).
     */
    private void drawZoneIcon(GraphicsManager gm) {
        if (selectedIndex >= Sonic3kLevelSelectConstants.ICON_TABLE.length) return;

        int iconIdx = Sonic3kLevelSelectConstants.ICON_TABLE[selectedIndex];
        if (iconIdx < 0 || iconIdx >= 15) return;

        // Icon position: pixel (216, 176) from VRAM offset $B36, shifted for widescreen
        int iconX = xOffset() + 216;
        int iconY = 176;

        int[] iconMappings = dataLoader.getIconMappings();
        int iconMapWidth = dataLoader.getIconMappingsWidth();
        if (iconMappings == null || iconMappings.length == 0 || iconMapWidth <= 0) return;

        int iconRowStart = iconIdx * 3;
        for (int row = 0; row < 3; row++) {
            int mapRow = iconRowStart + row;
            int baseIndex = mapRow * iconMapWidth;
            for (int col = 0; col < 4; col++) {
                int idx = baseIndex + col;
                if (idx >= iconMappings.length) continue;
                int word = iconMappings[idx];
                if (word == 0) continue;
                int patternIndex = word & 0x7FF;
                // Force icon to use palette line 2 (where we uploaded the icon palette)
                int flags = word & 0x1800; // keep priority + flip bits, clear palette
                int adjusted = flags | (ICON_RENDER_PALETTE << 13) | patternIndex;
                reusableDesc.set(adjusted);
                int patternId = Sonic3kLevelSelectConstants.PATTERN_BASE + patternIndex;
                gm.renderPatternWithId(patternId, reusableDesc,
                        iconX + col * 8, iconY + row * 8);
            }
        }
    }

    // ===== LevelSelectProvider interface =====

    @Override
    public State getState() { return state; }

    @Override
    public boolean isExiting() { return state == State.EXITING; }

    @Override
    public boolean isActive() { return state != State.INACTIVE; }

    @Override
    public boolean isSpecialStageSelected() {
        return Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex]
                == Sonic3kLevelSelectConstants.SPECIAL_STAGE_VALUE;
    }

    @Override
    public boolean isSoundTestSelected() {
        return Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex]
                == Sonic3kLevelSelectConstants.SOUND_TEST_VALUE;
    }

    @Override
    public int getSelectedZoneAct() {
        return Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex];
    }

    @Override
    public int getSelectedZone() {
        int zoneAct = Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex];
        if (zoneAct == Sonic3kLevelSelectConstants.SPECIAL_STAGE_VALUE
                || zoneAct == Sonic3kLevelSelectConstants.SOUND_TEST_VALUE
                || zoneAct == Sonic3kLevelSelectConstants.DISABLED_ENTRY) {
            return -1;
        }
        return (zoneAct >> 8) & 0xFF;
    }

    @Override
    public int getSelectedAct() {
        int zoneAct = Sonic3kLevelSelectConstants.LEVEL_ORDER[selectedIndex];
        if (zoneAct == Sonic3kLevelSelectConstants.SPECIAL_STAGE_VALUE
                || zoneAct == Sonic3kLevelSelectConstants.SOUND_TEST_VALUE
                || zoneAct == Sonic3kLevelSelectConstants.DISABLED_ENTRY) {
            return -1;
        }
        return zoneAct & 0xFF;
    }

    @Override
    public int getSelectedIndex() { return selectedIndex; }

    @Override
    public int getSoundTestValue() { return soundTestValue; }

    @Override
    public void reset() {
        state = State.INACTIVE;
        selectedIndex = 0;
        soundTestValue = 0;
        fadeTimer = 0;
        resetHoldTimers();
    }

    @Override
    public void setClearColor() {
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        Palette pal0 = dataLoader.getMenuPalette(0);
        if (pal0 != null) {
            Palette.Color backdrop = pal0.getColor(0);
            glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
        } else {
            glClearColor(BG_R, BG_G, BG_B, 1.0f);
        }
    }
}
