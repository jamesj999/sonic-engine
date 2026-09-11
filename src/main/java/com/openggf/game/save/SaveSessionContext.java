package com.openggf.game.save;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;

public final class SaveSessionContext {
    private final String gameCode;
    private final Integer activeSlot;
    private final SelectedTeam selectedTeam;
    private final int startZone;
    private final int startAct;
    private boolean clear;

    private SaveSessionContext(String gameCode, Integer activeSlot, SelectedTeam selectedTeam,
                               int startZone, int startAct) {
        this.gameCode = Objects.requireNonNull(gameCode, "gameCode");
        this.activeSlot = activeSlot;
        this.selectedTeam = Objects.requireNonNull(selectedTeam, "selectedTeam");
        this.startZone = startZone;
        this.startAct = startAct;
    }

    public static SaveSessionContext forSlot(String gameCode, int slot, SelectedTeam team,
                                             int zone, int act) {
        return new SaveSessionContext(gameCode, slot, team, zone, act);
    }

    public static SaveSessionContext noSave(String gameCode, SelectedTeam team,
                                            int zone, int act) {
        return new SaveSessionContext(gameCode, null, team, zone, act);
    }

    public OptionalInt activeSlot() {
        return activeSlot == null ? OptionalInt.empty() : OptionalInt.of(activeSlot);
    }

    public SelectedTeam selectedTeam() {
        return selectedTeam;
    }

    public String gameCode() {
        return gameCode;
    }

    public int startZone() {
        return startZone;
    }

    public int startAct() {
        return startAct;
    }

    public boolean isClear() {
        return clear;
    }

    public void markClear() {
        this.clear = true;
    }

    public void requestSave(SaveReason reason,
                            RuntimeSaveContext context,
                            SaveSnapshotProvider snapshotProvider,
                            SaveManager saveManager) throws IOException {
        if (activeSlot == null) {
            return;
        }
        saveManager.writeSlot(gameCode, activeSlot, snapshotProvider.capture(reason, context));
    }

    /**
     * As {@link #requestSave} but hands the disk write to the save-writer
     * thread; the snapshot itself is still captured and encoded here, on the
     * caller's thread. For in-game saves issued from a frame.
     */
    public void requestSaveAsync(SaveReason reason,
                                 RuntimeSaveContext context,
                                 SaveSnapshotProvider snapshotProvider,
                                 SaveManager saveManager) throws IOException {
        if (activeSlot == null) {
            return;
        }
        saveManager.writeSlotAsync(gameCode, activeSlot, snapshotProvider.capture(reason, context));
    }
}
