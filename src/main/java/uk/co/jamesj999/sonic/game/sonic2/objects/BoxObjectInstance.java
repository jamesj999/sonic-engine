package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;

import java.util.List;



public class BoxObjectInstance extends AbstractObjectInstance {
    private final int halfWidth;
    private final int halfHeight;
    private final float r;
    private final float g;
    private final float b;
    private final boolean highPriority;

    public BoxObjectInstance(ObjectSpawn spawn, String name, int halfWidth, int halfHeight,
                             float r, float g, float b, boolean highPriority) {
        super(spawn, name);
        this.halfWidth = Math.max(1, halfWidth);
        this.halfHeight = Math.max(1, halfHeight);
        this.r = r;
        this.g = g;
        this.b = b;
        this.highPriority = highPriority;
    }

    @Override
    public boolean isHighPriority() {
        return highPriority;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        int centerX = spawn.x();
        int centerY = spawn.y();

        int left = centerX - getHalfWidth();
        int right = centerX + getHalfWidth();
        int top = centerY - getHalfHeight();
        int bottom = centerY + getHalfHeight();

        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);

        int crossHalf = Math.min(halfWidth, halfHeight) / 2;
        if (crossHalf > 0) {
            appendLine(commands, centerX - crossHalf, centerY, centerX + crossHalf, centerY);
            appendLine(commands, centerX, centerY - crossHalf, centerX, centerY + crossHalf);
        }
    }

    protected void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    protected int getHalfWidth() {
        return halfWidth;
    }

    protected int getHalfHeight() {
        return halfHeight;
    }
}
