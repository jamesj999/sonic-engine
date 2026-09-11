package com.openggf.editor.render;

import com.openggf.game.GameServices;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.EditorModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINES;

public class EditorWorldOverlayRenderer {
    private static final float CURSOR_R = 1.0f;
    private static final float CURSOR_G = 0.92f;
    private static final float CURSOR_B = 0.30f;
    private static final float GRID_R = 0.36f;
    private static final float GRID_G = 0.78f;
    private static final float GRID_B = 0.95f;

    private final GraphicsManager graphicsManager;

    public EditorWorldOverlayRenderer() {
        this(GameServices.graphics());
    }

    public EditorWorldOverlayRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    public void render() {
        EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
        if (editorMode == null) {
            return;
        }
        List<GLCommand> commands = new ArrayList<>();
        appendWorldCommands(commands, editorMode.getCursor());
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
    }

    protected void appendWorldCommands(List<GLCommand> commands, EditorCursorState cursor) {
        appendGridCommands(commands, cursor);
        appendCursorCommands(commands, cursor);
    }

    protected void appendGridCommands(List<GLCommand> commands, EditorCursorState cursor) {
        int baseX = cursor.x() & ~15;
        int baseY = cursor.y() & ~15;
        int span = 64;

        for (int x = baseX - span; x <= baseX + span; x += 16) {
            appendLine(commands, x, baseY - span, x, baseY + span, GRID_R, GRID_G, GRID_B);
        }
        for (int y = baseY - span; y <= baseY + span; y += 16) {
            appendLine(commands, baseX - span, y, baseX + span, y, GRID_R, GRID_G, GRID_B);
        }
    }

    protected void appendCursorCommands(List<GLCommand> commands, EditorCursorState cursor) {
        int x = cursor.x();
        int y = cursor.y();
        int outer = 16;
        int inner = 6;

        appendRectOutline(commands, x - outer, y - outer, x + outer, y + outer,
                CURSOR_R, CURSOR_G, CURSOR_B);
        appendLine(commands, x - outer, y, x - inner, y, CURSOR_R, CURSOR_G, CURSOR_B);
        appendLine(commands, x + inner, y, x + outer, y, CURSOR_R, CURSOR_G, CURSOR_B);
        appendLine(commands, x, y - outer, x, y - inner, CURSOR_R, CURSOR_G, CURSOR_B);
        appendLine(commands, x, y + inner, x, y + outer, CURSOR_R, CURSOR_G, CURSOR_B);
    }

    private static void appendRectOutline(List<GLCommand> commands,
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

    private static void appendLine(List<GLCommand> commands,
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
}
