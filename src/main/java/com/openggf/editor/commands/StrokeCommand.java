package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.MutableLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class StrokeCommand implements EditorCommand {
    private final MutableLevel level;
    private final List<CellDelta> deltas;

    public StrokeCommand(MutableLevel level, List<CellDelta> deltas) {
        this.level = Objects.requireNonNull(level, "level");
        this.deltas = List.copyOf(deltas);
    }

    @Override
    public void apply() {
        for (CellDelta delta : deltas) {
            level.setBlockInMap(delta.layer(), delta.x(), delta.y(), delta.after());
        }
    }

    @Override
    public void undo() {
        List<CellDelta> reversed = new ArrayList<>(deltas);
        Collections.reverse(reversed);
        for (CellDelta delta : reversed) {
            level.setBlockInMap(delta.layer(), delta.x(), delta.y(), delta.before());
        }
    }

    public boolean isEmpty() {
        return deltas.isEmpty();
    }

    public record CellDelta(int layer, int x, int y, int before, int after) {
    }
}
