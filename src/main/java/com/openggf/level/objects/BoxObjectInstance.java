package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;

import java.util.List;



public class BoxObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private int halfWidth;
    private int halfHeight;
    private float r;
    private float g;
    private float b;
    private boolean highPriority;

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

    private BoxObjectInstance(ObjectSpawn spawn) {
        this(spawn, "Box", 1, 1, 1.0f, 1.0f, 1.0f, false);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "BoxObjectInstance",
                "ObjectSpawn",
                "box rewind recreate",
                new Class<?>[] {ObjectSpawn.class},
                ctx.spawn());
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
