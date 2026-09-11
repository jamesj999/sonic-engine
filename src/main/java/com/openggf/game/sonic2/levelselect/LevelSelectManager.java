package com.openggf.game.sonic2.levelselect;

import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.sonic2.audio.Sonic2SoundTestCatalog;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.game.sonic2.menu.MenuBackgroundAnimator;
import com.openggf.game.sonic2.menu.MenuBackgroundDataLoader;
import com.openggf.game.sonic2.menu.MenuBackgroundRenderer;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.glClearColor;
import com.openggf.game.GameServices;

/**
 * Manages the Sonic 2 Level Select screen.
 *
 * <p>The level select screen allows selecting any zone/act, special stage,
 * or sound test mode. It loads graphics directly from the ROM and replicates
 * the original menu layout.
 *
 * <p>State machine:
 * <pre>
 * INACTIVE → FADE_IN → ACTIVE → EXITING
 * </pre>
 */
public class LevelSelectManager implements LevelSelectProvider {
    private static final Logger LOGGER = Logger.getLogger(LevelSelectManager.class.getName());

    private static LevelSelectManager instance;

    private final SonicConfigurationService configService;
    private final LevelSelectDataLoader dataLoader = new LevelSelectDataLoader();
    private final MenuBackgroundDataLoader menuBackgroundDataLoader = new MenuBackgroundDataLoader();
    private final MenuBackgroundRenderer menuBackgroundRenderer = new MenuBackgroundRenderer();

    private MenuBackgroundAnimator menuBackgroundAnimator;
    private final PatternDesc reusableDesc = new PatternDesc();
    private final PatternDesc highlightDesc = new PatternDesc();

    private State state = State.INACTIVE;
    private int selectedIndex = 0;      // Menu selection (0-21)
    private int soundTestValue = 0;     // Sound test value (0x00-0x7F)

    // Input handling
    private int upHoldTimer = 0;
    private int downHoldTimer = 0;
    private int leftHoldTimer = 0;
    private int rightHoldTimer = 0;

    // Fade timing
    private int fadeTimer = 0;
    private static final int FADE_DURATION = 16;

    // Background color (blue like original)
    private static final float BG_R = 0.0f;
    private static final float BG_G = 0.0f;
    private static final float BG_B = 0.5f;

    private static final int HIGHLIGHT_PALETTE_INDEX = 3;
    private static final int ICON_PALETTE_INDEX = 2;

    /** Projection-space viewport width; default 320 (native). */
    private int viewportWidth = LevelSelectConstants.SCREEN_WIDTH;

    public LevelSelectManager() {
        this(null);
    }

    LevelSelectManager(SonicConfigurationService configService) {
        this.configService = configService;
    }

    private SonicConfigurationService configuration() {
        return configService != null ? configService : GameServices.configuration();
    }

    public static synchronized LevelSelectManager getInstance() {
        if (instance == null) {
            instance = new LevelSelectManager();
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
        this.viewportWidth = Math.max(LevelSelectConstants.SCREEN_WIDTH, width);
    }

    /**
     * Returns the horizontal pixel offset to apply to all rendered elements so that
     * the 320-px-wide content block is centered within the current viewport.
     * Returns 0 at native width 320.
     */
    private int xOffset() {
        return (viewportWidth - LevelSelectConstants.SCREEN_WIDTH) / 2;
    }

    /**
     * Initializes the level select screen.
     * Loads data from ROM and sets state to FADE_IN.
     */
    public void initialize() {
        LOGGER.info("Initializing level select screen");

        // Load data if not already loaded
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        if (!menuBackgroundDataLoader.isDataLoaded()) {
            menuBackgroundDataLoader.loadData();
        }

        // Force palette re-upload on next draw - level palettes may have overwritten
        // the menu palette slots (0-3) on the GPU
        dataLoader.resetCache();

        menuBackgroundAnimator = null;

        // Reset state
        state = State.FADE_IN;
        fadeTimer = 0;
        selectedIndex = 0;
        soundTestValue = 0;
        resetHoldTimers();

        // Play level select music
        GameServices.audio().playMusic(Sonic2Music.OPTIONS.id);

        LOGGER.info("Level select initialized, entering FADE_IN state");
    }

    /**
     * Updates the level select state machine.
     *
     * @param input Input handler for keyboard input
     */
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
            LOGGER.fine("Level select entered ACTIVE state");
        }
    }

    private void updateActive(InputHandler input) {
        // Navigation keys
        int upKey = configuration().getInt(SonicConfiguration.UP);
        int downKey = configuration().getInt(SonicConfiguration.DOWN);
        int leftKey = configuration().getInt(SonicConfiguration.LEFT);
        int rightKey = configuration().getInt(SonicConfiguration.RIGHT);
        int jumpKey = configuration().getInt(SonicConfiguration.JUMP);

        // Handle up/down navigation
        if (input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP)) {
            if (upHoldTimer == 0 || (upHoldTimer >= LevelSelectConstants.HOLD_REPEAT_DELAY && upHoldTimer % LevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveUp();
            }
            upHoldTimer++;
        } else {
            upHoldTimer = 0;
        }

        if (input.isDirectionHeld(downKey, AbstractPlayableSprite.INPUT_DOWN)) {
            if (downHoldTimer == 0 || (downHoldTimer >= LevelSelectConstants.HOLD_REPEAT_DELAY && downHoldTimer % LevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveDown();
            }
            downHoldTimer++;
        } else {
            downHoldTimer = 0;
        }

        // Handle left/right (column switch or sound test adjustment)
        if (input.isDirectionHeld(leftKey, AbstractPlayableSprite.INPUT_LEFT)) {
            if (leftHoldTimer == 0 || (leftHoldTimer >= LevelSelectConstants.HOLD_REPEAT_DELAY && leftHoldTimer % LevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveLeft();
            }
            leftHoldTimer++;
        } else {
            leftHoldTimer = 0;
        }

        if (input.isDirectionHeld(rightKey, AbstractPlayableSprite.INPUT_RIGHT)) {
            if (rightHoldTimer == 0 || (rightHoldTimer >= LevelSelectConstants.HOLD_REPEAT_DELAY && rightHoldTimer % LevelSelectConstants.HOLD_REPEAT_RATE == 0)) {
                moveRight();
            }
            rightHoldTimer++;
        } else {
            rightHoldTimer = 0;
        }

        // Handle start/jump to select
        if (input.isKeyPressed(jumpKey) || input.logical().menuAccept()) {
            handleSelect();
        }

        // A button adds 16 to sound test value (like original game)
        // We'll use the 'A' key for this
        if (input.isKeyPressed(configuration().getInt(SonicConfiguration.TEST))) {
            if (selectedIndex == LevelSelectConstants.MENU_ENTRY_COUNT - 1) { // Sound test
                soundTestValue = (soundTestValue + 16) & 0x7F;
            }
        }
    }

    private void moveUp() {
        selectedIndex--;
        if (selectedIndex < 0) {
            selectedIndex = LevelSelectConstants.MENU_ENTRY_COUNT - 1;
        }
        LOGGER.fine("Level select moved up to index " + selectedIndex);
    }

    private void moveDown() {
        selectedIndex++;
        if (selectedIndex >= LevelSelectConstants.MENU_ENTRY_COUNT) {
            selectedIndex = 0;
        }
        LOGGER.fine("Level select moved down to index " + selectedIndex);
    }

    private void moveLeft() {
        if (selectedIndex == LevelSelectConstants.MENU_ENTRY_COUNT - 1) {
            // Sound test mode - decrease value
            soundTestValue--;
            if (soundTestValue < 0) {
                soundTestValue = 0x7F;
            }
        } else {
            // Switch columns
            selectedIndex = LevelSelectConstants.SWITCH_TABLE[selectedIndex];
        }
        LOGGER.fine("Level select moved left to index " + selectedIndex);
    }

    private void moveRight() {
        if (selectedIndex == LevelSelectConstants.MENU_ENTRY_COUNT - 1) {
            // Sound test mode - increase value
            soundTestValue++;
            if (soundTestValue > 0x7F) {
                soundTestValue = 0;
            }
        } else {
            // Switch columns
            selectedIndex = LevelSelectConstants.SWITCH_TABLE[selectedIndex];
        }
        LOGGER.fine("Level select moved right to index " + selectedIndex);
    }

    private void handleSelect() {
        int zoneAct = LevelSelectConstants.LEVEL_ORDER[selectedIndex];

        if (zoneAct == LevelSelectConstants.SOUND_TEST_VALUE) {
            // Sound test - play the selected sound
            int soundId = Sonic2SoundTestCatalog.toSoundId(soundTestValue);
            LOGGER.info("Playing sound test: 0x" + Integer.toHexString(soundTestValue)
                    + " -> soundId 0x" + Integer.toHexString(soundId));
            if (Sonic2SoundTestCatalog.isMusicId(soundId)) {
                GameServices.audio().playMusic(soundId);
            } else if (Sonic2SoundTestCatalog.isSfxId(soundId)) {
                GameServices.audio().playSfx(soundId);
            } else {
                LOGGER.fine("No mapped sound for sound test value: 0x" + Integer.toHexString(soundTestValue));
            }
        } else {
            // Signal exit - GameLoop will handle the fade
            state = State.EXITING;
            LOGGER.info("Level select exiting for selection: " + selectedIndex);
        }
    }

    private void resetHoldTimers() {
        upHoldTimer = 0;
        downHoldTimer = 0;
        leftHoldTimer = 0;
        rightHoldTimer = 0;
    }

    /**
     * Renders the level select screen.
     */
    public void draw() {
        // Ensure data is loaded and cached
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        if (!menuBackgroundDataLoader.isDataLoaded()) {
            menuBackgroundDataLoader.loadData();
        }
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }
        dataLoader.cacheToGpu(gm);
        menuBackgroundDataLoader.cacheToGpu(gm, LevelSelectConstants.PATTERN_BASE, LevelSelectConstants.MENU_BACK_OFFSET);

        if (menuBackgroundAnimator == null
                && menuBackgroundDataLoader.getMenuBackPatterns() != null
                && menuBackgroundDataLoader.getMenuBackPatterns().length > 0) {
            menuBackgroundAnimator = new MenuBackgroundAnimator(
                    menuBackgroundDataLoader.getMenuBackPatterns(),
                    gm,
                    LevelSelectConstants.PATTERN_BASE + LevelSelectConstants.MENU_BACK_OFFSET);
            menuBackgroundAnimator.prime();
        }
        if (menuBackgroundAnimator != null) {
            menuBackgroundAnimator.update();
        }

        int iconIndex = selectedIndex < LevelSelectConstants.ICON_TABLE.length ? LevelSelectConstants.ICON_TABLE[selectedIndex] : -1;
        if (iconIndex >= 0) {
            Palette iconPalette = dataLoader.getIconPalette(iconIndex);
            if (iconPalette != null) {
                gm.cachePaletteTexture(iconPalette, ICON_PALETTE_INDEX);
            }
        }

        boolean renderMenuBack = menuBackgroundDataLoader.getMenuBackMappings() != null
                && menuBackgroundDataLoader.getMenuBackMappings().length > 0;

        gm.beginPatternBatch();
        if (renderMenuBack) {
            // Background expands to fill the full viewport width (tiled horizontally).
            // Do NOT apply xOffset() — the background is not centered, it fills everything.
            // At native width 320 this is identical to the previous centered draw.
            menuBackgroundRenderer.renderTiled(
                    gm,
                    menuBackgroundDataLoader.getMenuBackMappings(),
                    menuBackgroundDataLoader.getMenuBackWidth(),
                    menuBackgroundDataLoader.getMenuBackHeight(),
                    LevelSelectConstants.PATTERN_BASE,
                    LevelSelectConstants.MENU_BACK_OFFSET,
                    viewportWidth
            );
        }

        int[] screenLayout = dataLoader.getScreenLayout();
        if (screenLayout != null && screenLayout.length > 0) {
            renderTilemap(gm, screenLayout, dataLoader.getScreenLayoutWidth(), dataLoader.getScreenLayoutHeight());
            drawSelectionHighlight(gm, screenLayout, dataLoader.getScreenLayoutWidth(),
                    dataLoader.getScreenLayoutHeight());
        } else {
            drawMenuEntries(gm);
        }

        int soundPalette = selectedIndex == LevelSelectConstants.MENU_ENTRY_COUNT - 1 ? HIGHLIGHT_PALETTE_INDEX : 0;
        drawSoundTestValue(gm, soundPalette);

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
                    0, 0, viewportWidth, LevelSelectConstants.SCREEN_HEIGHT
            ));
        }
    }

    /**
     * Renders a tilemap decoded from Enigma mappings.
     */
    private void renderTilemap(GraphicsManager gm, int[] map, int width, int height) {
        if (map == null || map.length == 0 || width <= 0 || height <= 0) {
            return;
        }

        int xOff = xOffset();
        for (int ty = 0; ty < height; ty++) {
            int baseIndex = ty * width;
            for (int tx = 0; tx < width; tx++) {
                int idx = baseIndex + tx;
                if (idx < 0 || idx >= map.length) {
                    continue;
                }
                int word = map[idx];
                if (word == 0) {
                    continue;
                }
                reusableDesc.set(word);
                int patternId = LevelSelectConstants.PATTERN_BASE + reusableDesc.getPatternIndex();
                gm.renderPatternWithId(patternId, reusableDesc, xOff + tx * 8, ty * 8);
            }
        }
    }

    /**
     * Draws the menu entries as text.
     *
     * <p>Layout matches the original game:
     * <ul>
     *   <li>Left column: Zone names with act numbers (1, 2) stacked vertically</li>
     *   <li>Right column: Metropolis (3 acts), single-act zones, Special Stage, Sound Test</li>
     * </ul>
     *
     * <p>Positions derived from MARK_TABLE (col values are col*2 byte offsets):
     * <ul>
     *   <li>col=6 → X=24 (left zone names)</li>
     *   <li>col=0x24 → X=144 (left act numbers)</li>
     *   <li>col=0x2C → X=176 (right zone names)</li>
     *   <li>col=0x48 → X=288 (right act numbers)</li>
     * </ul>
     */
    private void drawMenuEntries(GraphicsManager gm) {
        // Left column zone names (drawn once per zone)
        // From MARK_TABLE: lines are 3, 6, 9, 12, 15, 18, 21 (multiply by 8 for pixels)
        String[] leftZoneNames = {"EMERALD HILL", "CHEMICAL PLANT", "AQUATIC RUIN",
                "CASINO NIGHT", "HILL TOP", "MYSTIC CAVE", "OIL OCEAN"};
        int[] leftZoneLines = {3, 6, 9, 12, 15, 18, 21};
        int xOff = xOffset();
        int leftZoneX = xOff + 24;   // col=6 → tile 3 → X=24
        int leftActX = xOff + 144;   // col=0x24 → tile 18 → X=144

        for (int zone = 0; zone < 7; zone++) {
            int zoneY = leftZoneLines[zone] * 8;
            int entryIndex = zone * 2;  // First act of this zone

            // Draw zone name (highlighted if either act is selected)
            boolean zoneHighlighted = (selectedIndex == entryIndex || selectedIndex == entryIndex + 1);
            drawText(gm, leftZoneNames[zone], leftZoneX, zoneY, zoneHighlighted ? 3 : 0);

            // Draw act numbers stacked vertically
            // Act 1
            drawText(gm, "1", leftActX, zoneY, selectedIndex == entryIndex ? 3 : 0);
            // Act 2
            drawText(gm, "2", leftActX, zoneY + 8, selectedIndex == entryIndex + 1 ? 3 : 0);
        }

        // Right column
        int rightZoneX = xOff + 176;  // col=0x2C → tile 22 → X=176
        int rightActX = xOff + 288;   // col=0x48 → tile 36 → X=288

        // Metropolis (3 acts) - line 3
        int mtzY = 3 * 8;
        boolean mtzHighlighted = (selectedIndex >= 14 && selectedIndex <= 16);
        drawText(gm, "METROPOLIS", rightZoneX, mtzY, mtzHighlighted ? 3 : 0);
        drawText(gm, "1", rightActX, mtzY, selectedIndex == 14 ? 3 : 0);
        drawText(gm, "2", rightActX, mtzY + 8, selectedIndex == 15 ? 3 : 0);
        drawText(gm, "3", rightActX, mtzY + 16, selectedIndex == 16 ? 3 : 0);

        // Sky Chase (single act) - line 6
        int sczY = 6 * 8;
        drawText(gm, "SKY CHASE", rightZoneX, sczY, selectedIndex == 17 ? 3 : 0);

        // Wing Fortress (single act) - line 9
        int wfzY = 9 * 8;
        drawText(gm, "WING FORTRESS", rightZoneX, wfzY, selectedIndex == 18 ? 3 : 0);

        // Death Egg (single act) - line 12
        int dezY = 12 * 8;
        drawText(gm, "DEATH EGG", rightZoneX, dezY, selectedIndex == 19 ? 3 : 0);

        // Special Stage - line 15
        int ssY = 15 * 8;
        drawText(gm, "SPECIAL STAGE", rightZoneX, ssY, selectedIndex == 20 ? 3 : 0);

        // Sound Test - line 18
        int stY = 18 * 8;
        drawText(gm, "SOUND TEST", rightZoneX, stY, selectedIndex == 21 ? 3 : 0);
    }

    /**
     * Draws text using the loaded font patterns.
     *
     * @param gm           Graphics manager
     * @param text         Text to draw
     * @param x            X position
     * @param y            Y position
     * @param paletteIndex Palette line (0=normal, 3=highlight)
     */
    private void drawText(GraphicsManager gm, String text, int x, int y, int paletteIndex) {
        int charX = x;
        for (char c : text.toUpperCase().toCharArray()) {
            int charIndex = getCharacterTileIndex(c);
            if (charIndex >= 0) {
                // Font patterns are at FONT_OFFSET
                int patternId = LevelSelectConstants.PATTERN_BASE + dataLoader.getFontOffset() + charIndex;
                PatternDesc desc = new PatternDesc(charIndex | (paletteIndex << 13));
                gm.renderPatternWithId(patternId, desc, charX, y);
            }
            charX += 8;
        }
    }

    /**
     * Gets the tile index for a character in the font.
     *
     * <p>From PlaneEd project:
     * <ul>
     *   <li>Number Offset: 0x00 - digits 0-9 at indices 0-9</li>
     *   <li>Letter Offset: 0x0E - letters A-Z starting at index 14</li>
     * </ul>
     */
    private int getCharacterTileIndex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';  // 0-9 at indices 0-9
        } else if (c >= 'A' && c <= 'Z') {
            return 0x0E + (c - 'A');  // A-Z starting at index 14 (0x0E)
        } else if (c == ' ') {
            return -1; // Space - skip
        }
        return -1; // Unknown character
    }

    /**
     * Draws the zone preview icon.
     */
    private void drawZoneIcon(GraphicsManager gm) {
        if (selectedIndex >= LevelSelectConstants.ICON_TABLE.length) {
            return;
        }

        int iconIndex = LevelSelectConstants.ICON_TABLE[selectedIndex];
        if (iconIndex < 0 || iconIndex >= 15) {
            return;
        }

        // Icon position (bottom right area of screen), shifted by xOffset() for widescreen
        int iconX = xOffset() + 216;
        int iconY = 176;

        int[] iconMappings = dataLoader.getIconMappings();
        int iconMapWidth = dataLoader.getIconMappingsWidth();
        if (iconMappings == null || iconMappings.length == 0 || iconMapWidth <= 0) {
            return;
        }

        int iconRowStart = iconIndex * 3;
        for (int row = 0; row < 3; row++) {
            int mapRow = iconRowStart + row;
            int baseIndex = mapRow * iconMapWidth;
            for (int col = 0; col < 4; col++) {
                int idx = baseIndex + col;
                if (idx < 0 || idx >= iconMappings.length) {
                    continue;
                }
                int word = iconMappings[idx];
                if (word == 0) {
                    continue;
                }
                int patternIndex = word & 0x7FF;
                reusableDesc.set(word);
                int patternId = LevelSelectConstants.PATTERN_BASE + patternIndex;
                int tileX = iconX + col * 8;
                int tileY = iconY + row * 8;
                gm.renderPatternWithId(patternId, reusableDesc, tileX, tileY);
            }
        }
    }

    /**
     * Draws the selection highlight by re-rendering the marked tiles
     * with the highlight palette line.
     */
    private void drawSelectionHighlight(GraphicsManager gm, int[] map, int width, int height) {
        if (map == null || map.length == 0 || width <= 0 || height <= 0) {
            return;
        }
        if (selectedIndex < 0 || selectedIndex >= LevelSelectConstants.MARK_TABLE.length) {
            return;
        }

        int[] mark = LevelSelectConstants.MARK_TABLE[selectedIndex];
        int row1 = mark[0];
        int col1 = mark[1] / 2;
        renderHighlightSpan(gm, map, width, height, row1, col1, 14);

        int row2 = mark[2];
        int col2 = mark[3] / 2;
        if (row2 != 0 || col2 != 0) {
            renderHighlightTile(gm, map, width, height, row2, col2);
        }
    }

    private void renderHighlightSpan(GraphicsManager gm, int[] map, int width, int height,
                                     int row, int col, int length) {
        if (row < 0 || row >= height) {
            return;
        }
        for (int i = 0; i < length; i++) {
            int tileCol = col + i;
            if (tileCol < 0 || tileCol >= width) {
                break;
            }
            renderHighlightTile(gm, map, width, height, row, tileCol);
        }
    }

    private void renderHighlightTile(GraphicsManager gm, int[] map, int width, int height, int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            return;
        }
        int idx = row * width + col;
        if (idx < 0 || idx >= map.length) {
            return;
        }
        int word = map[idx];
        if (word == 0) {
            return;
        }
        int patternIndex = word & 0x7FF;
        int flags = word & 0xF800;
        int adjusted = (flags & ~0x6000) | ((HIGHLIGHT_PALETTE_INDEX & 0x3) << 13) | patternIndex;
        highlightDesc.set(adjusted);
        int patternId = LevelSelectConstants.PATTERN_BASE + patternIndex;
        gm.renderPatternWithId(patternId, highlightDesc, xOffset() + col * 8, row * 8);
    }

    /**
     * Draws the sound test value as hex digits.
     */
    private void drawSoundTestValue(GraphicsManager gm, int paletteIndex) {
        // Draw the sound test value at tile (34,18), shifted by xOffset() for widescreen
        int x = xOffset() + 34 * 8;
        int y = 18 * 8;   // line 18 → Y=144

        // Convert to 2-digit hex
        int highNibble = (soundTestValue >> 4) & 0xF;
        int lowNibble = soundTestValue & 0xF;

        // Draw high nibble
        drawHexDigit(gm, highNibble, x, y, paletteIndex);
        // Draw low nibble
        drawHexDigit(gm, lowNibble, x + 8, y, paletteIndex);
    }

    /**
     * Draws a single hex digit (0-F).
     */
    private void drawHexDigit(GraphicsManager gm, int digit, int x, int y, int paletteIndex) {
        int charIndex;
        if (digit < 10) {
            charIndex = digit; // 0-9 at indices 0-9
        } else {
            charIndex = 0x0E + (digit - 10); // A-F are letters starting at index 14
        }

        int patternId = LevelSelectConstants.PATTERN_BASE + dataLoader.getFontOffset() + charIndex;
        PatternDesc desc = new PatternDesc(charIndex | ((paletteIndex & 0x3) << 13));
        gm.renderPatternWithId(patternId, desc, x, y);
    }

    /**
     * Returns the current state.
     */
    public State getState() {
        return state;
    }

    /**
     * Returns true if the level select is exiting (level should be loaded).
     */
    public boolean isExiting() {
        return state == State.EXITING;
    }

    /**
     * Returns true if the level select is active (not inactive).
     */
    public boolean isActive() {
        return state != State.INACTIVE;
    }

    /**
     * Gets the selected zone/act word value.
     * High byte = zone ID, Low byte = act number.
     * Special values: 0x4000 = Special Stage, 0xFFFF = Sound Test.
     *
     * @return zone/act word or special value
     */
    public int getSelectedZoneAct() {
        return LevelSelectConstants.LEVEL_ORDER[selectedIndex];
    }

    /**
     * Gets the selected zone index (0-10) or -1 for special stage/sound test.
     */
    public int getSelectedZone() {
        int zoneAct = LevelSelectConstants.LEVEL_ORDER[selectedIndex];
        if (zoneAct == LevelSelectConstants.SPECIAL_STAGE_VALUE || zoneAct == LevelSelectConstants.SOUND_TEST_VALUE) {
            return -1;
        }
        return (zoneAct >> 8) & 0xFF;
    }

    /**
     * Gets the selected act index (0-2) or -1 for special stage/sound test.
     */
    public int getSelectedAct() {
        int zoneAct = LevelSelectConstants.LEVEL_ORDER[selectedIndex];
        if (zoneAct == LevelSelectConstants.SPECIAL_STAGE_VALUE || zoneAct == LevelSelectConstants.SOUND_TEST_VALUE) {
            return -1;
        }
        return zoneAct & 0xFF;
    }

    /**
     * Returns true if Special Stage is selected.
     */
    public boolean isSpecialStageSelected() {
        return LevelSelectConstants.LEVEL_ORDER[selectedIndex] == LevelSelectConstants.SPECIAL_STAGE_VALUE;
    }

    /**
     * Returns true if Sound Test is selected.
     */
    public boolean isSoundTestSelected() {
        return LevelSelectConstants.LEVEL_ORDER[selectedIndex] == LevelSelectConstants.SOUND_TEST_VALUE;
    }

    /**
     * Resets the manager to inactive state.
     */
    public void reset() {
        state = State.INACTIVE;
        selectedIndex = 0;
        soundTestValue = 0;
        fadeTimer = 0;
        resetHoldTimers();
        LOGGER.info("Level select reset to inactive");
    }

    /**
     * Sets the OpenGL clear color to the VDP backdrop color (palette 0, color 0).
     * This matches the original game's VDP register $8700 setting.
     */
    public void setClearColor() {
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        Palette pal0 = dataLoader.getMenuPalette(0);
        if (pal0 != null) {
            Palette.Color backdrop = pal0.getColor(0);
            glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
        } else {
            // Fallback
            glClearColor(BG_R, BG_G, BG_B, 1.0f);
        }
    }

    /**
     * Gets the current menu selection index.
     */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Gets the current sound test value.
     */
    public int getSoundTestValue() {
        return soundTestValue;
    }
}
