package com.openggf.game;

import java.util.function.BooleanSupplier;

/** Routes global shortcuts around title-owned menus without changing physical input. */
public final class TitleInputOwnership {
    private TitleInputOwnership() { }

    /** Consume the title's confirmed quit request through the existing host exit flow. */
    public static void routeQuit(MasterTitleScreen title, Runnable exit) {
        if (title != null && title.consumeQuitRequest()) exit.run();
    }

    /**
     * An already-open global picker keeps its input until it closes. Otherwise the
     * action pane and its children own all keys, including configurable shortcuts
     * that overlap ordinary typing such as V, brackets and backslash.
     *
     * @return whether the global picker consumed this presentation frame
     */
    public static boolean routeDisplay(GameMode mode, MasterTitleScreen title, boolean pickerAlreadyOpen,
                                       BooleanSupplier updatePicker, Runnable updateColor,
                                       Runnable updateShaders) {
        if (pickerAlreadyOpen) return updatePicker.getAsBoolean();
        if (!allowsGlobalShortcuts(mode, title)) return false;
        boolean consumed = updatePicker.getAsBoolean();
        if (!consumed) {
            updateColor.run();
            updateShaders.run();
        }
        return consumed;
    }

    /** Keep physical chord history current even when a menu suppresses its effect. */
    public static boolean routeCapture(GameMode mode, MasterTitleScreen title, BooleanSupplier updateChord) {
        boolean pressed = updateChord.getAsBoolean();
        return pressed && allowsGlobalShortcuts(mode, title);
    }

    private static boolean allowsGlobalShortcuts(GameMode mode, MasterTitleScreen title) {
        return mode != GameMode.MASTER_TITLE_SCREEN || (title != null && !title.blocksGlobalShortcuts());
    }

    /** Playback shortcuts are gameplay tooling, not commands within the title GUI. */
    public static void routePlayback(GameMode mode, Runnable updatePlayback) {
        if (mode != GameMode.MASTER_TITLE_SCREEN) updatePlayback.run();
    }
}
