package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpriteFrame;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpritePiece;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;

/**
 * Debug viewer for Special Stage graphics (sprites and UI elements).
 * Displays patterns in a paginated grid layout for visual inspection.
 *
 * Graphics Sets (cycle with Up/Down):
 * - Set 0: Sonic player sprites (animation frames)
 * - Set 1: HUD graphics (numbers, text, UI elements)
 *
 * Pages within each set (cycle with Left/Right):
 * - Sonic: UPRIGHT, DIAGONAL, HORIZONTAL, BALL frames
 * - HUD: Pages of raw patterns
 *
 * Controls (only active during Special Stage):
 * - F12: Toggle sprite frame viewer on/off
 * - Up/Down arrows: Change graphics set
 * - Left/Right arrows: Change page within current set
 */
public class DebugSpecialStageSprites {
    private static DebugSpecialStageSprites instance;

    private static final int TILE_SIZE = 8;
    private static final int FRAME_CELL_WIDTH = 72;
    private static final int FRAME_CELL_HEIGHT = 80;
    private static final int GRID_COLUMNS = 4;
    private static final int SCREEN_HEIGHT = 224;
    private static final int SCREEN_WIDTH = 320;

    // Graphics set definitions
    public enum GraphicsSet {
        SONIC_SPRITES("Sonic Sprites"),
        HUD_GRAPHICS("HUD Graphics"),
        START_BANNER("START Banner"),
        MESSAGES("Messages");

        private final String label;

        GraphicsSet(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // Sonic sprite animation page definitions: {startFrame, endFrame (exclusive), showFlipped}
    private static final int[][] SONIC_PAGES = {
        {0, 4, 1},    // UPRIGHT: frames 0-3, show flipped versions too
        {4, 12, 0},   // DIAGONAL: frames 4-11, no flip (8 unique frames)
        {12, 16, 1},  // HORIZONTAL: frames 12-15, show flipped versions too
        {16, 18, 1},  // BALL: frames 16-17, show flipped versions too
    };

    private static final String[] SONIC_PAGE_LABELS = {
        "UPRIGHT (frames 0-3, + flipped)",
        "DIAGONAL (frames 4-11)",
        "HORIZONTAL (frames 12-15, + flipped)",
        "BALL (frames 16-17, + flipped)"
    };

    // Raw pattern grid settings
    private static final int RAW_TILE_COLUMNS = 16;
    private static final int RAW_TILE_ROWS = 12;
    private static final int RAW_TILES_PER_PAGE = RAW_TILE_COLUMNS * RAW_TILE_ROWS;
    private static final int RAW_TILE_SPACING = 10; // pixels between tiles

    private final GraphicsManager graphicsManager;
    private int playerPatternBase;
    private int hudPatternBase;
    private int hudPatternCount;
    private int startPatternBase;
    private int startPatternCount;
    private int messagesPatternBase;
    private int messagesPatternCount;

    private boolean enabled = false;
    private GraphicsSet currentSet = GraphicsSet.SONIC_SPRITES;
    private int currentPage = 0;

    private DebugSpecialStageSprites() {
        this.graphicsManager = GraphicsManager.getInstance();
    }

    public static synchronized DebugSpecialStageSprites getInstance() {
        if (instance == null) {
            instance = new DebugSpecialStageSprites();
        }
        return instance;
    }

    /**
     * Sets the pattern base for player art.
     */
    public void setPlayerPatternBase(int base) {
        this.playerPatternBase = base;
    }

    /**
     * Sets the pattern base and count for HUD art.
     */
    public void setHudPatternBase(int base, int count) {
        this.hudPatternBase = base;
        this.hudPatternCount = count;
    }

    /**
     * Sets the pattern base and count for START banner art.
     */
    public void setStartPatternBase(int base, int count) {
        this.startPatternBase = base;
        this.startPatternCount = count;
    }

    /**
     * Sets the pattern base and count for Messages art.
     */
    public void setMessagesPatternBase(int base, int count) {
        this.messagesPatternBase = base;
        this.messagesPatternCount = count;
    }

    /**
     * Enables or disables the debug viewer.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            currentSet = GraphicsSet.SONIC_SPRITES;
            currentPage = 0;
        }
    }

    /**
     * Toggles the debug viewer on/off.
     */
    public void toggle() {
        setEnabled(!this.enabled);
    }

    /**
     * Checks if the debug viewer is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the current graphics set.
     */
    public GraphicsSet getCurrentSet() {
        return currentSet;
    }

    /**
     * Gets the current page number (0-indexed).
     */
    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Gets the total number of pages for the current set.
     */
    public int getTotalPages() {
        switch (currentSet) {
            case SONIC_SPRITES:
                return SONIC_PAGES.length;
            case HUD_GRAPHICS:
                return hudPatternCount > 0 ? (hudPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
            case START_BANNER:
                return startPatternCount > 0 ? (startPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
            case MESSAGES:
                return messagesPatternCount > 0 ? (messagesPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
            default:
                return 0;
        }
    }

    /**
     * Moves to the next page if available.
     */
    public void nextPage() {
        if (currentPage < getTotalPages() - 1) {
            currentPage++;
        }
    }

    /**
     * Moves to the previous page if available.
     */
    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
        }
    }

    /**
     * Moves to the next graphics set.
     */
    public void nextSet() {
        GraphicsSet[] sets = GraphicsSet.values();
        int nextIndex = (currentSet.ordinal() + 1) % sets.length;
        currentSet = sets[nextIndex];
        currentPage = 0;
    }

    /**
     * Moves to the previous graphics set.
     */
    public void previousSet() {
        GraphicsSet[] sets = GraphicsSet.values();
        int prevIndex = (currentSet.ordinal() - 1 + sets.length) % sets.length;
        currentSet = sets[prevIndex];
        currentPage = 0;
    }

    /**
     * Renders the current page based on the active graphics set.
     */
    public void draw() {
        if (!enabled) {
            return;
        }

        switch (currentSet) {
            case SONIC_SPRITES:
                if (playerPatternBase != 0) {
                    drawSonicSprites();
                }
                break;
            case HUD_GRAPHICS:
                if (hudPatternBase != 0 && hudPatternCount > 0) {
                    drawRawPatterns(hudPatternBase, hudPatternCount, 1);
                }
                break;
            case START_BANNER:
                if (startPatternBase != 0 && startPatternCount > 0) {
                    drawRawPatterns(startPatternBase, startPatternCount, 1);
                }
                break;
            case MESSAGES:
                if (messagesPatternBase != 0 && messagesPatternCount > 0) {
                    drawRawPatterns(messagesPatternBase, messagesPatternCount, 2);
                }
                break;
        }
    }

    /**
     * Draws Sonic sprite animation frames (original implementation).
     */
    private void drawSonicSprites() {
        graphicsManager.beginPatternBatch();

        int[] pageInfo = SONIC_PAGES[currentPage];
        int startFrame = pageInfo[0];
        int endFrame = pageInfo[1];
        boolean showFlipped = pageInfo[2] != 0;
        int frameCount = endFrame - startFrame;

        int gridWidth = GRID_COLUMNS * FRAME_CELL_WIDTH;
        int gridStartX = (SCREEN_WIDTH - gridWidth) / 2;
        int gridStartY = 40;

        // Row 1: Normal frames
        for (int i = 0; i < frameCount; i++) {
            int frameIndex = startFrame + i;
            int gridCol = i % GRID_COLUMNS;
            int gridRow = i / GRID_COLUMNS;

            int cellCenterX = gridStartX + (gridCol * FRAME_CELL_WIDTH) + (FRAME_CELL_WIDTH / 2);
            int cellCenterY = gridStartY + (gridRow * FRAME_CELL_HEIGHT) + (FRAME_CELL_HEIGHT / 2);

            renderSonicFrame(frameIndex, cellCenterX, cellCenterY, false);
        }

        // Row 2 (if applicable): Flipped versions
        if (showFlipped) {
            int flipRowStart = ((frameCount + GRID_COLUMNS - 1) / GRID_COLUMNS);
            for (int i = 0; i < frameCount; i++) {
                int frameIndex = startFrame + i;
                int gridCol = i % GRID_COLUMNS;
                int gridRow = flipRowStart + (i / GRID_COLUMNS);

                int cellCenterX = gridStartX + (gridCol * FRAME_CELL_WIDTH) + (FRAME_CELL_WIDTH / 2);
                int cellCenterY = gridStartY + (gridRow * FRAME_CELL_HEIGHT) + (FRAME_CELL_HEIGHT / 2);

                renderSonicFrame(frameIndex, cellCenterX, cellCenterY, true);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a single Sonic sprite frame.
     */
    private void renderSonicFrame(int frameIndex, int centerX, int centerY, boolean flipX) {
        SpriteFrame frame = Sonic2SpecialStageSpriteMappings.getSonicFrame(frameIndex);

        for (SpritePiece piece : frame.pieces) {
            int pieceX = flipX ? -piece.xOffset - (piece.widthTiles * TILE_SIZE) : piece.xOffset;
            int pieceY = piece.yOffset;
            boolean finalHFlip = piece.hFlip ^ flipX;

            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    int srcCol = finalHFlip ? (piece.widthTiles - 1 - tx) : tx;
                    int srcRow = piece.vFlip ? (piece.heightTiles - 1 - ty) : ty;
                    int tileIndexInPiece = srcCol * piece.heightTiles + srcRow;
                    int patternId = playerPatternBase + piece.tileIndex + tileIndexInPiece;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);
                    desc.setPaletteIndex(1);
                    desc.setHFlip(finalHFlip);
                    desc.setVFlip(piece.vFlip);
                    desc.setPatternIndex(patternId & 0x7FF);

                    int tileScreenX = centerX + pieceX + (tx * TILE_SIZE);
                    int tileScreenY = centerY + pieceY + (ty * TILE_SIZE);

                    graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
                }
            }
        }
    }

    /**
     * Draws raw patterns in a grid layout.
     *
     * @param patternBase Base pattern index
     * @param patternCount Total number of patterns
     * @param paletteIndex Palette to use for rendering
     */
    private void drawRawPatterns(int patternBase, int patternCount, int paletteIndex) {
        graphicsManager.beginPatternBatch();

        int startIndex = currentPage * RAW_TILES_PER_PAGE;
        int endIndex = Math.min(startIndex + RAW_TILES_PER_PAGE, patternCount);

        int gridWidth = RAW_TILE_COLUMNS * (TILE_SIZE + RAW_TILE_SPACING);
        int gridHeight = RAW_TILE_ROWS * (TILE_SIZE + RAW_TILE_SPACING);
        int gridStartX = (SCREEN_WIDTH - gridWidth) / 2 + RAW_TILE_SPACING / 2;
        int gridStartY = (SCREEN_HEIGHT - gridHeight) / 2 + RAW_TILE_SPACING / 2;

        for (int i = startIndex; i < endIndex; i++) {
            int localIndex = i - startIndex;
            int col = localIndex % RAW_TILE_COLUMNS;
            int row = localIndex / RAW_TILE_COLUMNS;

            int screenX = gridStartX + col * (TILE_SIZE + RAW_TILE_SPACING);
            int screenY = gridStartY + row * (TILE_SIZE + RAW_TILE_SPACING);

            int patternId = patternBase + i;
            PatternDesc desc = new PatternDesc();
            desc.setPriority(true);
            desc.setPaletteIndex(paletteIndex);
            desc.setPatternIndex(patternId & 0x7FF);

            graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Gets the label for the current set and page.
     */
    public String getCurrentLabel() {
        String setLabel = currentSet.getLabel();
        int totalPages = getTotalPages();

        if (currentSet == GraphicsSet.SONIC_SPRITES && currentPage < SONIC_PAGE_LABELS.length) {
            return setLabel + ": " + SONIC_PAGE_LABELS[currentPage];
        } else if (totalPages > 0) {
            return setLabel + " (Page " + (currentPage + 1) + "/" + totalPages + ")";
        } else {
            return setLabel + " (No data)";
        }
    }

    /**
     * Gets the label for the current page (legacy method).
     */
    public String getCurrentPageLabel() {
        return getCurrentLabel();
    }

    /**
     * Gets the expected total height of the debug view.
     */
    public int getTotalHeight() {
        return (2 * FRAME_CELL_HEIGHT) + 80;
    }

    /**
     * Gets the expected total width of the debug view.
     */
    public int getTotalWidth() {
        return GRID_COLUMNS * FRAME_CELL_WIDTH + 32;
    }
}
