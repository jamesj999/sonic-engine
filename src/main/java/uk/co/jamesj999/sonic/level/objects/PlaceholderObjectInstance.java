package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;

import java.util.List;

public class PlaceholderObjectInstance extends AbstractObjectInstance {
    private static final int DEFAULT_HALF_SIZE = 8;
    private static final float COLOR_R = 0.95f;
    private static final float COLOR_G = 0.25f;
    private static final float COLOR_B = 0.95f;

    public PlaceholderObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        int centerX = spawn.x();
        int centerY = spawn.y();
        int halfSize = DEFAULT_HALF_SIZE;

        int left = centerX - halfSize;
        int right = centerX + halfSize;
        int top = centerY - halfSize;
        int bottom = centerY + halfSize;

        appendBox(commands, left, top, right, bottom);

        int cross = Math.max(2, halfSize / 2);
        appendLine(commands, centerX - cross, centerY, centerX + cross, centerY);
        appendLine(commands, centerX, centerY - cross, centerX, centerY + cross);

        int flags = spawn.renderFlags();
        boolean flipX = (flags & 0x1) != 0;
        boolean flipY = (flags & 0x2) != 0;
        int markerSize = 3;
        if (flipX) {
            appendLine(commands, left, centerY, left + markerSize, centerY);
        } else {
            appendLine(commands, right - markerSize, centerY, right, centerY);
        }
        if (flipY) {
            appendLine(commands, centerX, top, centerX, top + markerSize);
        } else {
            appendLine(commands, centerX, bottom - markerSize, centerX, bottom);
        }
    }

    private void appendBox(List<GLCommand> commands, int left, int top, int right, int bottom) {
        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                COLOR_R, COLOR_G, COLOR_B, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                COLOR_R, COLOR_G, COLOR_B, x2, y2, 0, 0));
    }
}
