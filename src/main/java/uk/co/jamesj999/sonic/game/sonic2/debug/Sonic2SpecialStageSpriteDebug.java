package uk.co.jamesj999.sonic.game.sonic2.debug;

import uk.co.jamesj999.sonic.game.SpecialStageDebugProvider;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpriteFrame;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageSpriteMappings.SpritePiece;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;

/**
 * Debug viewer for Sonic 2 Special Stage graphics (sprites and UI elements).
 * Displays patterns in a paginated grid layout for visual inspection.
 *
 * <p>
 * This is a Sonic 2-specific debug tool that displays:
 * <ul>
 * <li>Sonic player sprites (animation frames)</li>
 * <li>HUD graphics (numbers, text, UI elements)</li>
 * <li>START banner</li>
 * <li>Message graphics</li>
 * </ul>
 *
 * <p>
 * Controls (only active during Special Stage):
 * <ul>
 * <li>F12: Toggle sprite frame viewer on/off</li>
 * <li>Up/Down arrows: Change graphics set</li>
 * <li>Left/Right arrows: Change page within current set</li>
 * </ul>
 */
public class Sonic2SpecialStageSpriteDebug implements SpecialStageDebugProvider {
    private static Sonic2SpecialStageSpriteDebug instance;

    private static final int TILE_SIZE = 8;
    private static final int FRAME_CELL_WIDTH = 72;
    private static final int FRAME_CELL_HEIGHT = 80;
    private static final int GRID_COLUMNS = 4;
    private static final int SCREEN_HEIGHT = 224;
    private static final int SCREEN_WIDTH = 320;

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

    // Sonic sprite animation page definitions: {startFrame, endFrame (exclusive),
    // showFlipped}
    private static final int[][] SONIC_PAGES = {
            { 0, 4, 1 }, // UPRIGHT: frames 0-3, show flipped versions too
            { 4, 12, 0 }, // DIAGONAL: frames 4-11, no flip (8 unique frames)
            { 12, 16, 1 }, // HORIZONTAL: frames 12-15, show flipped versions too
            { 16, 18, 1 }, // BALL: frames 16-17, show flipped versions too
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
    private static final int RAW_TILE_SPACING = 10;

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

    private Sonic2SpecialStageSpriteDebug() {
        this.graphicsManager = GraphicsManager.getInstance();
    }

    public static synchronized Sonic2SpecialStageSpriteDebug getInstance() {
        if (instance == null) {
            instance = new Sonic2SpecialStageSpriteDebug();
        }
        return instance;
    }

    public void setPlayerPatternBase(int base) {
        this.playerPatternBase = base;
    }

    public void setHudPatternBase(int base, int count) {
        this.hudPatternBase = base;
        this.hudPatternCount = count;
    }

    public void setStartPatternBase(int base, int count) {
        this.startPatternBase = base;
        this.startPatternCount = count;
    }

    public void setMessagesPatternBase(int base, int count) {
        this.messagesPatternBase = base;
        this.messagesPatternCount = count;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            currentSet = GraphicsSet.SONIC_SPRITES;
            currentPage = 0;
        }
    }

    public void toggle() {
        setEnabled(!this.enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public GraphicsSet getCurrentSet() {
        return currentSet;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return switch (currentSet) {
            case SONIC_SPRITES -> SONIC_PAGES.length;
            case HUD_GRAPHICS ->
                hudPatternCount > 0 ? (hudPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
            case START_BANNER ->
                startPatternCount > 0 ? (startPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
            case MESSAGES ->
                messagesPatternCount > 0 ? (messagesPatternCount + RAW_TILES_PER_PAGE - 1) / RAW_TILES_PER_PAGE : 0;
        };
    }

    public void nextPage() {
        if (currentPage < getTotalPages() - 1) {
            currentPage++;
        }
    }

    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
        }
    }

    public void nextSet() {
        GraphicsSet[] sets = GraphicsSet.values();
        int nextIndex = (currentSet.ordinal() + 1) % sets.length;
        currentSet = sets[nextIndex];
        currentPage = 0;
    }

    public void previousSet() {
        GraphicsSet[] sets = GraphicsSet.values();
        int prevIndex = (currentSet.ordinal() - 1 + sets.length) % sets.length;
        currentSet = sets[prevIndex];
        currentPage = 0;
    }

    public void draw() {
        if (!enabled) {
            return;
        }

        switch (currentSet) {
            case SONIC_SPRITES -> {
                if (playerPatternBase != 0) {
                    drawSonicSprites();
                }
            }
            case HUD_GRAPHICS -> {
                if (hudPatternBase != 0 && hudPatternCount > 0) {
                    drawRawPatterns(hudPatternBase, hudPatternCount, 1);
                }
            }
            case START_BANNER -> {
                if (startPatternBase != 0 && startPatternCount > 0) {
                    drawRawPatterns(startPatternBase, startPatternCount, 1);
                }
            }
            case MESSAGES -> {
                if (messagesPatternBase != 0 && messagesPatternCount > 0) {
                    drawRawPatterns(messagesPatternBase, messagesPatternCount, 2);
                }
            }
        }
    }

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
}
