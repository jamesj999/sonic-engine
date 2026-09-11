package com.openggf.editor.render;

import com.openggf.editor.LevelEditorController;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINES;

public class EditorToolbarRenderer {
    private static final float CHROME_R = 0.95f;
    private static final float CHROME_G = 0.82f;
    private static final float CHROME_B = 0.24f;
    private static final int TEXT_X = 10;
    private static final int TEXT_Y = 10;

    private final LevelEditorController controller;
    private final GraphicsManager graphicsManager;
    private final EditorTextRenderer textRenderer;

    public EditorToolbarRenderer() {
        this(null, GameServices.graphics());
    }

    public EditorToolbarRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public EditorToolbarRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this(controller, graphicsManager, new EditorTextRenderer(graphicsManager));
    }

    public EditorToolbarRenderer(LevelEditorController controller,
                                 GraphicsManager graphicsManager,
                                 EditorTextRenderer textRenderer) {
        this.controller = controller;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
    }

    public void render() {
        List<GLCommand> commands = new ArrayList<>();
        appendCommands(commands);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
        textRenderer.renderLines(buildStateLines(), TEXT_X, TEXT_Y);
    }

    protected void appendCommands(List<GLCommand> commands) {
        appendRectOutline(commands, 4, 4, 316, 24, CHROME_R, CHROME_G, CHROME_B);
        appendLine(commands, 84, 4, 84, 24, CHROME_R, CHROME_G, CHROME_B);
        appendLine(commands, 164, 4, 164, 24, CHROME_R, CHROME_G, CHROME_B);
        appendLine(commands, 244, 4, 244, 24, CHROME_R, CHROME_G, CHROME_B);
    }

    protected List<String> buildStateLines() {
        if (controller == null) {
            return List.of("World | Focus - | Block - Chunk -");
        }

        return List.of(controller.breadcrumb()
                + " | Focus " + controller.focusRegion()
                + " | Layer " + (controller.activeLayer() == 0 ? "FG" : "BG")
                + " | " + (controller.currentLevel() != null && controller.currentLevel().isModifiedSinceLastSave()
                ? "unsaved*" : "saved")
                + " | Block " + valueOrNone(controller.selection().selectedBlock())
                + " Chunk " + valueOrNone(controller.selection().selectedChunk())
                + " | B " + controller.selectedBlockCellX() + "," + controller.selectedBlockCellY()
                + " C " + controller.selectedChunkCellX() + "," + controller.selectedChunkCellY());
    }

    protected List<EditorTextRenderer.TextCommand> buildToolbarTextCommands() {
        return textRenderer.buildTextCommands(buildStateLines(), TEXT_X, TEXT_Y);
    }

    protected static void appendRectOutline(List<GLCommand> commands,
                                            int left,
                                            int top,
                                            int right,
                                            int bottom,
                                            float r,
                                            float g,
                                            float b) {
        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    protected static void appendLine(List<GLCommand> commands,
                                     int x1,
                                     int y1,
                                     int x2,
                                     int y2,
                                     float r,
                                     float g,
                                     float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA, r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA, r, g, b, x2, y2, 0, 0));
    }

    private static String valueOrNone(Integer value) {
        return value == null ? "-" : value.toString();
    }
}
