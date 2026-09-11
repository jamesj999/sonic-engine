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

public class EditorCommandStripRenderer {
    private static final float CHROME_R = 0.38f;
    private static final float CHROME_G = 0.88f;
    private static final float CHROME_B = 0.92f;
    private static final int TEXT_X = 10;
    private static final int TEXT_Y = 200;

    private final LevelEditorController controller;
    private final GraphicsManager graphicsManager;
    private final EditorTextRenderer textRenderer;

    public EditorCommandStripRenderer() {
        this(null, GameServices.graphics());
    }

    public EditorCommandStripRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public EditorCommandStripRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this(controller, graphicsManager, new EditorTextRenderer(graphicsManager));
    }

    public EditorCommandStripRenderer(LevelEditorController controller,
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
        textRenderer.renderLines(buildCommandLines(), TEXT_X, TEXT_Y);
    }

    protected void appendCommands(List<GLCommand> commands) {
        EditorToolbarRenderer.appendRectOutline(commands, 4, 198, 316, 220,
                CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 108, 198, 108, 220,
                CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 212, 198, 212, 220,
                CHROME_R, CHROME_G, CHROME_B);
    }

    protected List<String> buildCommandLines() {
        EditorHierarchyDepth depth = controller == null ? EditorHierarchyDepth.WORLD : controller.depth();
        String focusHint = controller == null ? "Tab focus" : "Tab focus " + controller.focusRegion();
        return switch (depth) {
            case WORLD -> List.of(focusHint, "Space Place block | E Eyedrop | Enter Block");
            case BLOCK -> List.of(focusHint, "Space Apply chunk | E Eyedrop | Esc World | Enter Chunk");
            case CHUNK -> List.of(focusHint, "Space Apply pattern | E Eyedrop | Esc Block");
        };
    }

    protected List<EditorTextRenderer.TextCommand> buildCommandTextCommands() {
        return textRenderer.buildTextCommands(buildCommandLines(), TEXT_X, TEXT_Y);
    }
}
