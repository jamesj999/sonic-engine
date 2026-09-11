package com.openggf.editor.render;

import com.openggf.debug.DebugColor;
import com.openggf.debug.FontSize;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PixelFontTextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EditorTextRenderer {
    public record TextCommand(String text, int x, int y, int lineHeight, DebugColor color, FontSize fontSize) {}

    private static final int DEFAULT_LINE_HEIGHT = 10;
    private static final DebugColor DEFAULT_COLOR = DebugColor.WHITE;
    private static final FontSize DEFAULT_FONT_SIZE = FontSize.SMALL;

    private final GraphicsManager graphicsManager;
    private final PixelFontTextRenderer textRenderer;

    public EditorTextRenderer() {
        this(GameServices.graphics(), new PixelFontTextRenderer());
    }

    public EditorTextRenderer(GraphicsManager graphicsManager) {
        this(graphicsManager, new PixelFontTextRenderer());
    }

    public EditorTextRenderer(GraphicsManager graphicsManager, PixelFontTextRenderer textRenderer) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
    }

    public void renderLines(List<String> lines, int x, int y) {
        List<TextCommand> commands = buildTextCommands(lines, x, y);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(buildTextBatchCommand(commands));
        }
    }

    protected List<TextCommand> buildTextCommands(List<String> lines, int x, int y) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        List<TextCommand> commands = new ArrayList<>();
        int lineY = y;
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                commands.add(new TextCommand(line, x, lineY, DEFAULT_LINE_HEIGHT, DEFAULT_COLOR, DEFAULT_FONT_SIZE));
            }
            lineY += DEFAULT_LINE_HEIGHT;
        }
        return commands;
    }

    protected GLCommandable buildTextBatchCommand(List<TextCommand> commands) {
        return new TextBatchCommand(List.copyOf(commands));
    }

    private final class TextBatchCommand implements GLCommandable {
        private final List<TextCommand> commands;

        private TextBatchCommand(List<TextCommand> commands) {
            this.commands = commands;
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (commands.isEmpty()) {
                return;
            }

            float[] projectionMatrix = graphicsManager.getProjectionMatrixBuffer();
            if (projectionMatrix != null) {
                textRenderer.setProjectionMatrix(projectionMatrix);
            }
            for (TextCommand command : commands) {
                textRenderer.drawShadowedText(command.text(), command.x(), command.y(), command.color());
            }
        }
    }
}
