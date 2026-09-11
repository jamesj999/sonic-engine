package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;

import java.util.function.Supplier;

public class HudRenderManager {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(HudRenderManager.class.getName());

    private final GraphicsManager graphicsManager;
    private final Camera camera;
    private final GameStateManager gameState;
    private final PatternDesc staticPieceDesc = new PatternDesc();

    private int digitPatternIndex;
    private int livesNumbersPatternIndex;
    private int hexDigitsPatternIndex;
    private HudStaticArt staticHudArt;
    private int staticHudPatternIndex;

    // Cached HUD values for performance - avoid String.valueOf() and parsing each frame
    private int cachedScore = -1;
    private int cachedRings = -1;
    private int cachedLives = -1;
    private String cachedTime = null;
    // Pre-computed digit arrays to avoid allocation each frame
    private final int[] scoreDigits = new int[6];
    private final int[] ringDigits = new int[3];
    private final int[] livesDigits = new int[3];
    private int scoreDigitCount = 0;
    private int ringDigitCount = 0;
    private int livesDigitCount = 0;

    // Icon/flash palette and HUD text palette — configurable per game
    private PatternDesc iconPatternDesc = new PatternDesc(0x8000);
    private PatternDesc hudPatternDesc = new PatternDesc(0xA000);
    private int iconPaletteLine;
    private Palette livesPaletteOverride;
    private Palette lastAppliedLivesPaletteOverride;
    private Supplier<Palette> livesPaletteOverrideSupplier;
    private boolean bonusStageHudLayout;

    public HudRenderManager(GraphicsManager graphicsManager, Camera camera, GameStateManager gameState) {
        this.graphicsManager = graphicsManager;
        this.camera = camera;
        this.gameState = gameState;
    }

    /**
     * Sets the palette lines for HUD rendering.
     * Different games use different palette lines for the yellow text vs flash colors.
     *
     * @param textPalLine  palette line for HUD text labels (yellow)
     * @param flashPalLine palette line for icon and flash state (red)
     */
    public void setHudPalettes(int textPalLine, int flashPalLine) {
        this.hudPatternDesc = new PatternDesc(0x8000 | (textPalLine << 13));
        this.iconPatternDesc = new PatternDesc(0x8000 | (flashPalLine << 13));
        this.iconPaletteLine = flashPalLine;
    }

    public void setBonusStageHudLayout(boolean enabled) {
        this.bonusStageHudLayout = enabled;
    }

    public void setDigitPatternIndex(int digitPatternIndex) {
        this.digitPatternIndex = digitPatternIndex;
        LOGGER.fine("HudRenderManager Digit Index: " + digitPatternIndex);
    }

    public void setStaticHudArt(int basePatternIndex, HudStaticArt staticHudArt) {
        this.staticHudPatternIndex = basePatternIndex;
        this.staticHudArt = staticHudArt;
    }

    public void setLivesNumbersPatternIndex(int livesNumbersPatternIndex) {
        this.livesNumbersPatternIndex = livesNumbersPatternIndex;
    }

    public void setLivesPaletteOverride(Palette palette) {
        this.livesPaletteOverride = palette != null ? palette.deepCopy() : null;
        this.lastAppliedLivesPaletteOverride = null;
    }

    public void setLivesPaletteOverrideSupplier(Supplier<Palette> supplier) {
        this.livesPaletteOverrideSupplier = supplier;
        this.lastAppliedLivesPaletteOverride = null;
    }

    /**
     * Registers the base pattern index for the ROM-native ASCII-aligned hex font used
     * by the debug HUD. Tile layout: '0'-'9' at offsets 0-9, 'A'-'F' at offsets 17-22
     * (matches 'A'-'0' = 0x11). Set to 0 to disable (falls back to lives digits,
     * which only cover 0-9).
     */
    public void setHexDigitsPatternIndex(int hexDigitsPatternIndex) {
        this.hexDigitsPatternIndex = hexDigitsPatternIndex;
    }

    /**
     * Invalidates the HUD cache, forcing a full recalculation on next frame.
     * Call this when loading a new level or when HUD state needs full refresh.
     */
    public void invalidateCache() {
        cachedScore = -1;
        cachedRings = -1;
        cachedLives = -1;
        cachedTime = null;
        scoreDigitCount = 0;
        ringDigitCount = 0;
        livesDigitCount = 0;
        lastAppliedLivesPaletteOverride = null;
    }

    /**
     * Converts a number to digits and stores in the provided array.
     * Returns the number of digits written.
     * Avoids String allocation that would occur with String.valueOf().
     */
    private int numberToDigits(int value, int[] digits) {
        if (value == 0) {
            digits[0] = 0;
            return 1;
        }

        int temp = value;
        int count = 0;
        while (temp > 0) {
            count++;
            temp /= 10;
        }

        int idx = count - 1;
        temp = value;
        while (temp > 0) {
            digits[idx--] = temp % 10;
            temp /= 10;
        }

        return count;
    }

    public void draw(LevelState levelGamestate) {
        draw(levelGamestate, null);
    }

    public void draw(LevelState levelGamestate, PlayableEntity player) {
        if (levelGamestate == null) {
            return;
        }

        graphicsManager.beginPatternBatch();
        boolean batchOpen = true;
        try {
            if (bonusStageHudLayout) {
                drawBonusStageHud(levelGamestate);
                graphicsManager.flushPatternBatch();
                batchOpen = false;
                return;
            }

            boolean debugMode = player != null && player.isDebugMode();

            drawStaticFrame(debugMode ? staticHudArtOrNull(true) : staticHudArtOrNull(false), 16, 8);
            drawStaticFrame(selectTimeFrame(levelGamestate.shouldFlashTimer(), levelGamestate.getFlashCycle()), 16, 24);
            drawStaticFrame(selectRingsFrame(levelGamestate.getRings(), levelGamestate.getFlashCycle()), 16, 40);

            if (debugMode) {
                int hexStartX = 48;
                int playerX = player.getCentreX() & 0xFFFF;
                int playerY = player.getCentreY() & 0xFFFF;
                drawSmallHexCoordinates(hexStartX, 8, playerX, playerY);

                int camX = camera.getX() & 0xFFFF;
                int camY = camera.getY() & 0xFFFF;
                drawSmallHexCoordinates(hexStartX, 16, camX, camY);
            } else {
                drawScore(gameState.getScore());
            }

            drawTime(56, 24, levelGamestate.getDisplayTime());
            drawRings(levelGamestate.getRings(), bonusStageHudLayout ? 8 : 40);
            boolean paletteOverridden = shouldApplyLivesPaletteOverride();
            if (paletteOverridden) {
                graphicsManager.flushPatternBatch();
                graphicsManager.flush();
                batchOpen = false;
                applyLivesPaletteOverride();
                graphicsManager.beginPatternBatch();
                batchOpen = true;
            }
            drawStaticFrame(staticHudArt != null ? staticHudArt.livesFrame() : null, 16, 200);
            drawLives(gameState.getLives());
            graphicsManager.flushPatternBatch();
            batchOpen = false;
            if (paletteOverridden) {
                graphicsManager.flush();
            }
        } finally {
            if (batchOpen) {
                graphicsManager.flushPatternBatch();
            }
        }
    }

    private SpriteMappingFrame staticHudArtOrNull(boolean debugMode) {
        if (staticHudArt == null) {
            return null;
        }
        return debugMode ? staticHudArt.debugScoreFrame() : staticHudArt.scoreFrame();
    }

    private void drawBonusStageHud(LevelState levelGamestate) {
        drawStaticFrame(selectRingsFrame(levelGamestate.getRings(), levelGamestate.getFlashCycle()), 16, 8);
        drawRings(levelGamestate.getRings(), 8);
    }

    private void drawScore(int score) {
        drawNumberRightAligned(64, 8, score, 6);
    }

    private void drawRings(int rings, int y) {
        drawNumberRightAligned(64, y, rings, 3);
    }

    private void drawLives(int lives) {
        int camX = camera.getXWithShake();
        int camY = camera.getYWithShake();
        int numDrawX = 56;
        int line2Y = 208;

        if (livesNumbersPatternIndex <= 0) {
            return;
        }
        if (lives != cachedLives) {
            cachedLives = lives;
            livesDigitCount = numberToDigits(lives, livesDigits);
        }
        for (int i = 0; i < livesDigitCount; i++) {
            int digit = livesDigits[i];
            renderSafe(livesNumbersPatternIndex + digit, iconPatternDesc, numDrawX + camX + (i * 8), line2Y + camY);
        }
    }

    private boolean applyLivesPaletteOverride() {
        Palette paletteOverride = resolveLivesPaletteOverride();
        if (paletteOverride == null) {
            lastAppliedLivesPaletteOverride = null;
            return false;
        }
        Palette uploadedOverride = paletteOverride.deepCopy();
        graphicsManager.cachePaletteTexture(uploadedOverride, iconPaletteLine);
        lastAppliedLivesPaletteOverride = uploadedOverride;
        return true;
    }

    private boolean shouldApplyLivesPaletteOverride() {
        Palette paletteOverride = resolveLivesPaletteOverride();
        if (paletteOverride == null) {
            lastAppliedLivesPaletteOverride = null;
            return false;
        }
        if (lastAppliedLivesPaletteOverride != null && lastAppliedLivesPaletteOverride.dataEquals(paletteOverride)) {
            return false;
        }
        return true;
    }

    private Palette resolveLivesPaletteOverride() {
        if (livesPaletteOverrideSupplier != null) {
            Palette supplied = livesPaletteOverrideSupplier.get();
            if (supplied != null) {
                return supplied;
            }
        }
        return livesPaletteOverride;
    }

    private void drawStaticFrame(SpriteMappingFrame frame, int originX, int originY) {
        if (frame == null || staticHudArt == null || staticHudPatternIndex <= 0) {
            return;
        }
        int camX = camera.getXWithShake();
        int camY = camera.getYWithShake();
        SpritePieceRenderer.renderPieces(
                frame.pieces(),
                originX + camX,
                originY + camY,
                staticHudPatternIndex,
                -1,
                false,
                false,
                staticPieceConsumer);
    }

    // Reused tile consumer (captures only `this`), hoisted so drawStaticFrame
    // doesn't allocate a capturing lambda per piece batch per frame.
    private final SpritePieceRenderer.TileConsumer staticPieceConsumer = this::renderStaticPiece;

    private void renderStaticPiece(int patternId, boolean hFlip, boolean vFlip,
            int paletteIndex, int drawX, int drawY) {
        int descIndex = patternId & 0x7FF;
        if (hFlip) {
            descIndex |= 0x800;
        }
        if (vFlip) {
            descIndex |= 0x1000;
        }
        descIndex |= (paletteIndex & 0x3) << 13;
        staticPieceDesc.set(descIndex);
        // renderPatternWithId consumes the desc immediately (batch copies
        // floats; PatternRenderCommand.init copies fields), so the reusable
        // desc can be passed without a copy.
        graphicsManager.renderPatternWithId(patternId, staticPieceDesc, drawX, drawY);
    }

    private SpriteMappingFrame selectTimeFrame(boolean shouldFlashTimer, boolean flashCycle) {
        if (staticHudArt == null) {
            return null;
        }
        return (shouldFlashTimer && flashCycle) ? staticHudArt.timeFlashFrame() : staticHudArt.timeFrame();
    }

    private SpriteMappingFrame selectRingsFrame(int rings, boolean flashCycle) {
        if (staticHudArt == null) {
            return null;
        }
        return (rings == 0 && flashCycle) ? staticHudArt.ringsFlashFrame() : staticHudArt.ringsFrame();
    }

    /**
     * Draws hex coordinates using small 8x8 font (like original Sonic 2 debug
     * mode).
     * Format: XXXXYYYY (8 hex digits total, no gap between X and Y)
     * Uses the smaller lives number font for digits 0-9.
     *
     * @param x      Base X position on screen
     * @param y      Base Y position on screen
     * @param xCoord X coordinate to display (will be masked to 16-bit)
     * @param yCoord Y coordinate to display (will be masked to 16-bit)
     */
    private void drawSmallHexCoordinates(int x, int y, int xCoord, int yCoord) {
        int camX = camera.getXWithShake();
        int camY = camera.getYWithShake();

        for (int i = 0; i < 4; i++) {
            int nibble = (xCoord >> (12 - i * 4)) & 0xF;
            drawSmallHexDigit(x + camX + (i * 8), y + camY, nibble);
        }

        int yStartX = x + 32;
        for (int i = 0; i < 4; i++) {
            int nibble = (yCoord >> (12 - i * 4)) & 0xF;
            drawSmallHexDigit(yStartX + camX + (i * 8), y + camY, nibble);
        }
    }

    /**
     * Draws a single hex digit (0-F) using the ROM-native debug font when available.
     * Matches the disasm routine (S1 HudDb_XY2, S2 HudDb_XY2, S3K sub_EC90): each
     * nibble indexes an ASCII-aligned tile table where 0-9 are contiguous and A-F
     * start at offset 17 (+7 beyond '9'). Falls back to the lives-number font (0-9
     * only) when the debug font has not been registered.
     */
    private void drawSmallHexDigit(int x, int y, int digit) {
        if (hexDigitsPatternIndex > 0) {
            int tileOffset = digit < 0xA ? digit : digit + 7;
            renderSafe(hexDigitsPatternIndex + tileOffset, hudPatternDesc, x, y);
            return;
        }

        if (livesNumbersPatternIndex <= 0) {
            return;
        }
        if (digit < 10) {
            renderSafe(livesNumbersPatternIndex + digit, iconPatternDesc, x, y);
        }
    }

    private void drawNumberRightAligned(int startX, int y, int value, int maxDigits) {
        int camX = camera.getXWithShake();
        int camY = camera.getYWithShake();

        int[] digitArray;
        int digitCount;

        if (maxDigits == 6) {
            if (value != cachedScore) {
                cachedScore = value;
                scoreDigitCount = numberToDigits(value, scoreDigits);
            }
            digitArray = scoreDigits;
            digitCount = scoreDigitCount;
        } else if (maxDigits == 3 && value <= 999) {
            if (value != cachedRings) {
                cachedRings = value;
                ringDigitCount = numberToDigits(value, ringDigits);
            }
            digitArray = ringDigits;
            digitCount = ringDigitCount;
        } else {
            digitArray = new int[maxDigits];
            digitCount = numberToDigits(value, digitArray);
        }

        int padding = maxDigits - digitCount;
        if (padding < 0) {
            padding = 0;
        }

        for (int i = 0; i < digitCount; i++) {
            int digit = digitArray[i];
            int xPos = startX + (padding + i) * 8;
            renderSafe(digitPatternIndex + (digit * 2), hudPatternDesc, xPos + camX, y + camY);
            renderSafe(digitPatternIndex + (digit * 2) + 1, hudPatternDesc, xPos + camX, y + camY + 8);
        }
    }

    private void drawTime(int x, int y, String timeStr) {
        if (timeStr == null || timeStr.equals(cachedTime)) {
            cachedTime = timeStr;
        } else {
            cachedTime = timeStr;
        }

        int camX = camera.getXWithShake();
        int camY = camera.getYWithShake();
        for (int i = 0; i < timeStr.length(); i++) {
            char c = timeStr.charAt(i);
            int patternIdx = (c == ':')
                    ? digitPatternIndex + 20
                    : digitPatternIndex + ((c - '0') * 2);
            renderSafe(patternIdx, hudPatternDesc, x + camX + (i * 8), y + camY);
            renderSafe(patternIdx + 1, hudPatternDesc, x + camX + (i * 8), y + camY + 8);
        }
    }

    private void renderSafe(int patternId, PatternDesc desc, int x, int y) {
        graphicsManager.renderPatternWithId(patternId, desc, x, y);
    }
}
