package com.openggf.editor.render;

import com.openggf.editor.EditorHierarchyDepth;
import com.openggf.editor.LevelEditorController;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINES;

public class EditorLibraryPaneRenderer {
    private static final float CHROME_R = 0.44f;
    private static final float CHROME_G = 0.72f;
    private static final float CHROME_B = 1.0f;

    private final LevelEditorController controller;
    private final GraphicsManager graphicsManager;
    private final EditorTextRenderer textRenderer;

    public EditorLibraryPaneRenderer() {
        this(null, GameServices.graphics());
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this(controller, graphicsManager, new EditorTextRenderer(graphicsManager));
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller,
                                     GraphicsManager graphicsManager,
                                     EditorTextRenderer textRenderer) {
        this.controller = controller;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
    }

    public void render(EditorHierarchyDepth depth) {
        List<GLCommand> commands = new ArrayList<>();
        appendCommands(commands);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
        textRenderer.renderLines(buildLibraryLines(depth), 18, 48);
    }

    protected void appendCommands(List<GLCommand> commands) {
        EditorToolbarRenderer.appendRectOutline(commands, 12, 34, 152, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 60, 152, 60, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 90, 152, 90, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 54, 90, 54, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 96, 90, 96, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 128, 152, 128, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 164, 152, 164, CHROME_R, CHROME_G, CHROME_B);
    }

    protected List<String> buildLibraryLines(EditorHierarchyDepth depth) {
        if (controller == null) {
            return List.of(libraryTitle(depth), "No controller");
        }

        List<String> lines = new ArrayList<>();
        lines.add(libraryTitle(depth));
        lines.add("Focus " + controller.focusRegion());
        lines.add("Block " + valueOrNone(controller.selection().selectedBlock()));
        lines.add("Chunk " + valueOrNone(controller.selection().selectedChunk()));
        lines.add("Block cell " + controller.selectedBlockCellX() + "," + controller.selectedBlockCellY());
        lines.add("Chunk cell " + controller.selectedChunkCellX() + "," + controller.selectedChunkCellY());
        return lines;
    }

    private static String libraryTitle(EditorHierarchyDepth depth) {
        return switch (depth) {
            case WORLD -> "Block library";
            case BLOCK -> "Chunk library";
            case CHUNK -> "Pattern library";
        };
    }

    private static String valueOrNone(Integer value) {
        return value == null ? "-" : value.toString();
    }
}
