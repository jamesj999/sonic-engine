package com.openggf.editor;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.game.MenuFeedback;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Modal command access for keyboard and controllers without changing gameplay mappings. */
public final class EditorCommandPalette {
    private static final int VISIBLE_ROWS = 10;
    private static final EditorInputHandler.Action[] ACTIONS = EditorInputHandler.Action.values();
    private boolean open;
    private int selected;
    private String navigation = "Arrows";
    private String confirm = "Enter";
    private String back = "Esc";
    private Runnable playtest;
    private Runnable freshStart;
    private final Runnable filterAction;
    private List<ExtraCommand> extraCommands = List.of();
    private Consumer<MenuFeedback.Cue> feedback = cue -> { };

    public EditorCommandPalette() { this(null); }

    EditorCommandPalette(Runnable filterAction) {
        this.filterAction = filterAction;
        rebuildExtraCommands();
    }

    public void setFeedback(Consumer<MenuFeedback.Cue> feedback) {
        this.feedback = java.util.Objects.requireNonNull(feedback, "feedback");
    }

    public void setHostActions(Runnable playtest, Runnable freshStart) {
        this.playtest = playtest;
        this.freshStart = freshStart;
        rebuildExtraCommands();
    }

    public void close() { open = false; }

    public static EditorCommandPalette forController(LevelEditorController controller) {
        return controller.commandPalette();
    }

    public boolean isOpen() { return open; }

    /** True means this frame belongs exclusively to the palette. */
    public boolean update(InputHandler input, Consumer<EditorInputHandler.Action> dispatch) {
        navigation = MenuInput.directionLabel(input);
        confirm = MenuInput.confirmLabel(input);
        back = MenuInput.backLabel(input);
        if (MenuInput.back(input)) {
            if (!open) return false;
            open = false;
            feedback.accept(MenuFeedback.Cue.CANCEL);
            return true;
        }
        if (MenuInput.details(input)) {
            open = !open;
            feedback.accept(open ? MenuFeedback.Cue.CONFIRM : MenuFeedback.Cue.CANCEL);
            return true;
        }
        if (!open) return false;
        var actions = ACTIONS;
        int count = commandCount();
        int previous = selected;
        if (MenuInput.up(input)) selected = Math.floorMod(selected - 1, count);
        if (MenuInput.down(input)) selected = (selected + 1) % count;
        if (selected != previous) feedback.accept(MenuFeedback.Cue.NAVIGATE);
        if (MenuInput.accept(input)) {
            feedback.accept(MenuFeedback.Cue.CONFIRM);
            open = false;
            if (selected < actions.length) dispatch.accept(actions[selected]);
            else extraCommands.get(selected - actions.length).action().run();
        }
        return true;
    }

    public List<String> lines() {
        var actions = ACTIONS;
        List<String> lines = new ArrayList<>();
        int count = commandCount();
        lines.add("EDITOR COMMANDS  " + (selected + 1) + "/" + count);
        int start = Math.max(0, Math.min(selected - VISIBLE_ROWS / 2, count - VISIBLE_ROWS));
        for (int index = start; index < Math.min(start + VISIBLE_ROWS, count); index++) {
            lines.add((index == selected ? "> " : "  ") + (index < actions.length ? label(actions[index])
                    : extraCommands.get(index - actions.length).label()));
        }
        lines.add(navigation + " select  " + confirm + " run");
        lines.add(back + " close");
        return List.copyOf(lines);
    }

    private int commandCount() {
        return ACTIONS.length + extraCommands.size();
    }

    private void rebuildExtraCommands() {
        List<ExtraCommand> commands = new ArrayList<>();
        if (filterAction != null) commands.add(new ExtraCommand("Toggle library filter", filterAction));
        if (playtest != null) commands.add(new ExtraCommand("Playtest current edits", playtest));
        if (freshStart != null) commands.add(new ExtraCommand("Fresh start", freshStart));
        extraCommands = List.copyOf(commands);
        selected = Math.min(selected, commandCount() - 1);
    }

    private record ExtraCommand(String label, Runnable action) { }

    private static String label(EditorInputHandler.Action action) {
        return switch (action) {
            case DESCEND -> "Edit selected block / chunk";
            case ASCEND -> "Return to parent view";
            case CYCLE_FOCUS_REGION -> "Next editor pane";
            case APPLY_PRIMARY_ACTION -> "Place / apply selection";
            case PERFORM_EYEDROP -> "Pick content at cursor";
            case CYCLE_SPAWN_EDIT_MODE -> "Next placement mode";
            default -> {
                String text = action.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                yield Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
        };
    }
}
