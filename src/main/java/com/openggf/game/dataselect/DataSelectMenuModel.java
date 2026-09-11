package com.openggf.game.dataselect;

/**
 * Mutable UI state for the basic Data Select screen.
 */
public class DataSelectMenuModel {
    private int selectedRow;
    private int selectedTeamIndex;
    private int clearRestartIndex;
    private boolean deleteMode;

    public int getSelectedRow() {
        return selectedRow;
    }

    public void setSelectedRow(int selectedRow) {
        this.selectedRow = selectedRow;
    }

    public int getSelectedTeamIndex() {
        return selectedTeamIndex;
    }

    public void setSelectedTeamIndex(int selectedTeamIndex) {
        this.selectedTeamIndex = selectedTeamIndex;
    }

    public int getClearRestartIndex() {
        return clearRestartIndex;
    }

    public void setClearRestartIndex(int clearRestartIndex) {
        this.clearRestartIndex = clearRestartIndex;
    }

    public boolean isDeleteMode() {
        return deleteMode;
    }

    public void setDeleteMode(boolean deleteMode) {
        this.deleteMode = deleteMode;
    }

    public void reset() {
        selectedRow = 0;
        selectedTeamIndex = 0;
        clearRestartIndex = 0;
        deleteMode = false;
    }

    public void clearTransientSelection() {
        clearRestartIndex = 0;
        deleteMode = false;
    }
}
