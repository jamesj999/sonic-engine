package com.openggf.editor.render;

import com.openggf.editor.LevelEditorController;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.PatternDesc;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_LINES;

public class FocusedEditorPaneRenderer {
    public record PreviewPlacement(PatternDesc descriptor, int x, int y) {}

    private static final float BLOCK_R = 0.96f;
    private static final float BLOCK_G = 0.64f;
    private static final float BLOCK_B = 0.28f;
    private static final float CHUNK_R = 0.54f;
    private static final float CHUNK_G = 0.82f;
    private static final float CHUNK_B = 0.34f;
    private static final float ACTIVE_R = 1.0f;
    private static final float ACTIVE_G = 0.96f;
    private static final float ACTIVE_B = 0.24f;
    private static final float BACKDROP_ALPHA = 0.34f;

    private final LevelEditorController controller;
    private final GraphicsManager graphicsManager;

    public FocusedEditorPaneRenderer() {
        this(null, GameServices.graphics());
    }

    public FocusedEditorPaneRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public FocusedEditorPaneRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this.controller = controller;
        this.graphicsManager = java.util.Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    public void renderBlockEditorPane() {
        List<GLCommand> backdropCommands = new ArrayList<>();
        appendBlockBackdropCommands(backdropCommands);
        for (GLCommand command : backdropCommands) {
            graphicsManager.registerCommand(command);
        }

        List<GLCommand> commands = new ArrayList<>();
        appendBlockPaneCommands(commands);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
    }

    public void renderChunkEditorPane() {
        List<GLCommand> backdropCommands = new ArrayList<>();
        appendChunkBackdropCommands(backdropCommands);
        for (GLCommand command : backdropCommands) {
            graphicsManager.registerCommand(command);
        }

        List<GLCommand> commands = new ArrayList<>();
        appendChunkPaneCommands(commands);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
    }

    protected void appendBlockPaneCommands(List<GLCommand> commands) {
        appendPaneCommands(commands, 196, 34, 316, 194, BLOCK_R, BLOCK_G, BLOCK_B);
        renderPreviewPlacements(buildBlockPreviewPlacements());
        appendBlockActiveCellHighlight(commands, buildBlockPreviewLayout());
    }

    protected void appendChunkPaneCommands(List<GLCommand> commands) {
        appendPaneCommands(commands, 176, 34, 316, 194, CHUNK_R, CHUNK_G, CHUNK_B);
        renderPreviewPlacements(buildChunkPreviewPlacements());
        appendChunkActiveCellHighlight(commands, buildChunkPreviewLayout());
    }

    protected void appendBlockBackdropCommands(List<GLCommand> commands) {
        appendBackdropCommand(commands, 196, 34, 316, 194);
    }

    protected void appendChunkBackdropCommands(List<GLCommand> commands) {
        appendBackdropCommand(commands, 176, 34, 316, 194);
    }

    protected List<PreviewPlacement> buildBlockPreviewPlacements() {
        if (controller == null) {
            return List.of();
        }

        Block block = controller.selectedBlockPreview();
        if (block == null) {
            return List.of();
        }

        PreviewLayout layout = buildBlockPreviewLayout();
        List<PreviewPlacement> placements = new ArrayList<>();
        int gridSide = block.getGridSide();

        for (int cellY = 0; cellY < gridSide; cellY++) {
            for (int cellX = 0; cellX < gridSide; cellX++) {
                Chunk chunk = controller.selectedBlockChunkPreview(cellX, cellY);
                if (chunk == null) {
                    continue;
                }
                appendChunkPlacements(placements, chunk,
                        layout.originX + cellX * layout.chunkCellSize,
                        layout.originY + cellY * layout.chunkCellSize,
                        layout.patternCellSize);
            }
        }
        return placements;
    }

    protected List<PreviewPlacement> buildChunkPreviewPlacements() {
        if (controller == null) {
            return List.of();
        }

        Chunk chunk = controller.selectedChunkPreview();
        if (chunk == null) {
            return List.of();
        }

        PreviewLayout layout = buildChunkPreviewLayout();
        List<PreviewPlacement> placements = new ArrayList<>();
        appendChunkPlacements(placements, chunk, layout.originX, layout.originY, layout.patternCellSize);
        return placements;
    }

    protected void appendBlockActiveCellHighlight(List<GLCommand> commands, PreviewLayout layout) {
        if (controller == null) {
            return;
        }

        Block block = controller.selectedBlockPreview();
        if (block == null) {
            return;
        }

        int activeX = controller.selectedBlockCellX();
        int activeY = controller.selectedBlockCellY();
        if (activeX < 0 || activeY < 0 || activeX >= block.getGridSide() || activeY >= block.getGridSide()) {
            return;
        }

        int cellLeft = layout.originX + activeX * layout.chunkCellSize;
        int cellTop = layout.originY + activeY * layout.chunkCellSize;
        int cellRight = cellLeft + layout.chunkCellSize - 1;
        int cellBottom = cellTop + layout.chunkCellSize - 1;
        EditorToolbarRenderer.appendRectOutline(commands, cellLeft - 1, cellTop - 1, cellRight + 1, cellBottom + 1,
                ACTIVE_R, ACTIVE_G, ACTIVE_B);
    }

    protected void appendChunkActiveCellHighlight(List<GLCommand> commands, PreviewLayout layout) {
        if (controller == null) {
            return;
        }

        Chunk chunk = controller.selectedChunkPreview();
        if (chunk == null) {
            return;
        }

        int activeX = controller.selectedChunkCellX();
        int activeY = controller.selectedChunkCellY();
        int gridSide = controller.chunkGridSide();
        if (activeX < 0 || activeY < 0 || activeX >= gridSide || activeY >= gridSide) {
            return;
        }

        int cellLeft = layout.originX + activeX * layout.patternCellSize;
        int cellTop = layout.originY + activeY * layout.patternCellSize;
        int cellRight = cellLeft + layout.patternCellSize - 1;
        int cellBottom = cellTop + layout.patternCellSize - 1;
        EditorToolbarRenderer.appendRectOutline(commands, cellLeft - 1, cellTop - 1, cellRight + 1, cellBottom + 1,
                ACTIVE_R, ACTIVE_G, ACTIVE_B);
    }

    protected void renderPreviewPlacements(List<PreviewPlacement> placements) {
        if (placements.isEmpty()) {
            return;
        }
        for (PreviewPlacement placement : placements) {
            PatternDesc descriptor = placement.descriptor();
            graphicsManager.renderPatternWithId(descriptor.getPatternIndex(), descriptor, placement.x(), placement.y());
        }
    }

    protected PreviewLayout buildBlockPreviewLayout() {
        if (controller == null) {
            return new PreviewLayout(196, 34, Chunk.CHUNK_WIDTH, Chunk.CHUNK_WIDTH / 2);
        }
        Block block = controller.selectedBlockPreview();
        int gridSide = block != null ? Math.max(1, block.getGridSide()) : 1;
        return buildPreviewLayout(196, 34, 316, 194, gridSide * Chunk.CHUNK_WIDTH, gridSide * Chunk.CHUNK_WIDTH,
                Chunk.CHUNK_WIDTH, Chunk.CHUNK_WIDTH / 2);
    }

    protected PreviewLayout buildChunkPreviewLayout() {
        return buildPreviewLayout(176, 34, 316, 194, Chunk.CHUNK_WIDTH, Chunk.CHUNK_HEIGHT,
                Chunk.CHUNK_WIDTH, Chunk.CHUNK_WIDTH / 2);
    }

    protected PreviewLayout buildPreviewLayout(int left,
                                               int top,
                                               int right,
                                               int bottom,
                                               int previewWidth,
                                               int previewHeight,
                                               int chunkCellSize,
                                               int patternCellSize) {
        int originX = left + ((right - left) - previewWidth) / 2;
        int originY = top + ((bottom - top) - previewHeight) / 2;
        return new PreviewLayout(originX, originY, chunkCellSize, patternCellSize);
    }

    protected void appendChunkPlacements(List<PreviewPlacement> placements,
                                         Chunk chunk,
                                         int originX,
                                         int originY,
                                         int patternCellSize) {
        for (int cellY = 0; cellY < Chunk.PATTERNS_PER_CHUNK / 2; cellY++) {
            for (int cellX = 0; cellX < Chunk.PATTERNS_PER_CHUNK / 2; cellX++) {
                PatternDesc descriptor = chunk.getPatternDesc(cellX, cellY);
                int x = originX + cellX * patternCellSize;
                int y = originY + cellY * patternCellSize;
                placements.add(new PreviewPlacement(descriptor, x, y));
            }
        }
    }

    protected record PreviewLayout(int originX, int originY, int chunkCellSize, int patternCellSize) {
    }

    private void appendPaneCommands(List<GLCommand> commands,
                                    int left,
                                    int top,
                                    int right,
                                    int bottom,
                                    float r,
                                    float g,
                                    float b) {
        EditorToolbarRenderer.appendRectOutline(commands, left, top, right, bottom, r, g, b);
        EditorToolbarRenderer.appendLine(commands, left, top + 26, right, top + 26, r, g, b);
        EditorToolbarRenderer.appendLine(commands, left, top + 98, right, top + 98, r, g, b);
        EditorToolbarRenderer.appendLine(commands, left + 28, top + 26, left + 28, bottom, r, g, b);
    }

    private void appendBackdropCommand(List<GLCommand> commands, int left, int top, int right, int bottom) {
        commands.add(new GLCommand(GLCommand.CommandType.RECTI,
                -1,
                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                1.0f,
                1.0f,
                1.0f,
                BACKDROP_ALPHA,
                left,
                top,
                right,
                bottom));
    }
}
