package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.MutableLevel;

public final class PlaceBlockCommand implements EditorCommand {
    private final MutableLevel level;
    private final int layer;
    private final int x;
    private final int y;
    private final int before;
    private final int after;

    public PlaceBlockCommand(MutableLevel level, int layer, int x, int y, int before, int after) {
        this.level = level;
        this.layer = layer;
        this.x = x;
        this.y = y;
        this.before = before;
        this.after = after;
    }

    @Override
    public void apply() {
        level.setBlockInMap(layer, x, y, after);
    }

    @Override
    public void undo() {
        level.setBlockInMap(layer, x, y, before);
    }
}
