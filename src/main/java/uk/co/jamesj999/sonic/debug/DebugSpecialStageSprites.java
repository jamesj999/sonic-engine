package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpriteFrame;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpritePiece;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;

/**
 * Debug viewer for Special Stage Sonic sprite animation frames.
 * Displays frames in a paginated grid layout for visual inspection.
 *
 * Pages show one animation type each, with flipped versions where applicable:
 * - Page 0: UPRIGHT - frames 0-3 normal, then 0-3 flipped (8 total)
 * - Page 1: DIAGONAL - frames 4-11 (8 unique frames)
 * - Page 2: HORIZONTAL - frames 12-15 normal, then 12-15 flipped (8 total)
 * - Page 3: BALL - frames 16-17 normal, then 16-17 flipped (4 total)
 *
 * Controls (only active during Special Stage):
 * - F12: Toggle sprite frame viewer on/off
 * - Left/Right arrows: Change page
 */
public class DebugSpecialStageSprites {
    private static DebugSpecialStageSprites instance;

    private static final int TILE_SIZE = 8;
    private static final int FRAME_CELL_WIDTH = 72;
    private static final int FRAME_CELL_HEIGHT = 80;
    private static final int GRID_COLUMNS = 4;
    private static final int SCREEN_HEIGHT = 224;

    // Animation page definitions: {startFrame, endFrame (exclusive), showFlipped}
    // If showFlipped is true, we show normal frames on row 1, flipped on row 2
    private static final int[][] ANIMATION_PAGES = {
        {0, 4, 1},    // UPRIGHT: frames 0-3, show flipped versions too
        {4, 12, 0},   // DIAGONAL: frames 4-11, no flip (8 unique frames)
        {12, 16, 1},  // HORIZONTAL: frames 12-15, show flipped versions too
        {16, 18, 1},  // BALL: frames 16-17, show flipped versions too
    };

    private static final String[] PAGE_LABELS = {
        "UPRIGHT (frames 0-3, + flipped)",
        "DIAGONAL (frames 4-11)",
        "HORIZONTAL (frames 12-15, + flipped)",
        "BALL (frames 16-17, + flipped)"
    };

    private final GraphicsManager graphicsManager;
    private int playerPatternBase;
    private boolean enabled = false;
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
     * Must be called after special stage patterns are loaded.
     */
    public void setPlayerPatternBase(int base) {
        this.playerPatternBase = base;
    }

    /**
     * Enables or disables the debug viewer.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            currentPage = 0;  // Reset to first page when enabling
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
     * Gets the current page number (0-indexed).
     */
    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Gets the total number of pages.
     */
    public int getTotalPages() {
        return ANIMATION_PAGES.length;
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
     * Renders the current page of sprite frames in a grid layout.
     * Call this instead of normal special stage rendering when debug mode is active.
     *
     * Coordinate system uses Mega Drive convention (Y increases downward).
     * The graphics layer handles OpenGL Y-axis conversion.
     */
    public void draw() {
        if (!enabled || playerPatternBase == 0) {
            return;
        }

        graphicsManager.beginPatternBatch();

        int[] pageInfo = ANIMATION_PAGES[currentPage];
        int startFrame = pageInfo[0];
        int endFrame = pageInfo[1];
        boolean showFlipped = pageInfo[2] != 0;
        int frameCount = endFrame - startFrame;

        // Calculate grid start position
        // Center the grid horizontally, start near top of screen
        int gridWidth = GRID_COLUMNS * FRAME_CELL_WIDTH;
        int gridStartX = (320 - gridWidth) / 2;
        int gridStartY = 40;  // Start 40 pixels from top (in MD coords, Y down)

        // Row 1: Normal frames
        for (int i = 0; i < frameCount; i++) {
            int frameIndex = startFrame + i;
            int gridCol = i % GRID_COLUMNS;
            int gridRow = i / GRID_COLUMNS;

            int cellCenterX = gridStartX + (gridCol * FRAME_CELL_WIDTH) + (FRAME_CELL_WIDTH / 2);
            int cellCenterY = gridStartY + (gridRow * FRAME_CELL_HEIGHT) + (FRAME_CELL_HEIGHT / 2);

            renderFrame(frameIndex, cellCenterX, cellCenterY, false);
        }

        // Row 2 (if applicable): Flipped versions
        if (showFlipped) {
            int flipRowStart = ((frameCount + GRID_COLUMNS - 1) / GRID_COLUMNS);  // Next row after normal frames
            for (int i = 0; i < frameCount; i++) {
                int frameIndex = startFrame + i;
                int gridCol = i % GRID_COLUMNS;
                int gridRow = flipRowStart + (i / GRID_COLUMNS);

                int cellCenterX = gridStartX + (gridCol * FRAME_CELL_WIDTH) + (FRAME_CELL_WIDTH / 2);
                int cellCenterY = gridStartY + (gridRow * FRAME_CELL_HEIGHT) + (FRAME_CELL_HEIGHT / 2);

                renderFrame(frameIndex, cellCenterX, cellCenterY, true);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a single sprite frame at the given center position.
     * Position is in Mega Drive screen coordinates (Y increases downward).
     *
     * @param frameIndex The sprite frame index to render
     * @param centerX Center X position in screen coordinates
     * @param centerY Center Y position in screen coordinates
     * @param flipX If true, render the frame horizontally flipped
     */
    private void renderFrame(int frameIndex, int centerX, int centerY, boolean flipX) {
        SpriteFrame frame = Sonic2SpecialStageSpriteMappings.getSonicFrame(frameIndex);

        for (SpritePiece piece : frame.pieces) {
            // Calculate piece position relative to center, applying player flip
            int pieceX = flipX ? -piece.xOffset - (piece.widthTiles * TILE_SIZE) : piece.xOffset;
            int pieceY = piece.yOffset;

            // Combine piece flip with player flip
            boolean finalHFlip = piece.hFlip ^ flipX;

            // Render all tiles in this piece using column-major ordering
            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    // Determine source tile based on flip state
                    int srcCol = finalHFlip ? (piece.widthTiles - 1 - tx) : tx;
                    int srcRow = piece.vFlip ? (piece.heightTiles - 1 - ty) : ty;

                    // Column-major index: column * height + row
                    int tileIndexInPiece = srcCol * piece.heightTiles + srcRow;
                    int patternId = playerPatternBase + piece.tileIndex + tileIndexInPiece;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);
                    desc.setPaletteIndex(1); // Sonic uses palette 1
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
     * Gets the label for the current page.
     */
    public String getCurrentPageLabel() {
        if (currentPage < PAGE_LABELS.length) {
            return PAGE_LABELS[currentPage];
        }
        return "Page " + (currentPage + 1);
    }

    /**
     * Gets the expected total height of the debug view.
     */
    public int getTotalHeight() {
        // Max 2 rows per page (normal + flipped)
        return (2 * FRAME_CELL_HEIGHT) + 80;
    }

    /**
     * Gets the expected total width of the debug view.
     */
    public int getTotalWidth() {
        return GRID_COLUMNS * FRAME_CELL_WIDTH + 32;
    }
}
