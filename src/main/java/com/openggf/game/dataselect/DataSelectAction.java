package com.openggf.game.dataselect;

import com.openggf.game.save.SelectedTeam;

/**
 * Represents a user action from the data select screen.
 * Consumed by the game loop to trigger level loading, slot deletion, etc.
 */
public record DataSelectAction(DataSelectActionType type, int slot, int zone, int act, SelectedTeam team) {

    /** Returns a no-op action indicating no selection has been made. */
    public static DataSelectAction none() {
        return new DataSelectAction(DataSelectActionType.NONE, -1, -1, -1, null);
    }

    public static DataSelectAction deleteSlot(int slot) {
        return new DataSelectAction(DataSelectActionType.DELETE_SLOT, slot, -1, -1, null);
    }
}
