package com.openggf.game.save;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;

@com.openggf.game.ModApi
public final class SaveSessionContext {
    private final String gameCode;
    private final Integer activeSlot;
    private final SelectedTeam selectedTeam;
    private final SelectedTeam durableSelectedTeam;
    private final int startZone;
    private final int startAct;
    private boolean clear;

    private SaveSessionContext(String gameCode, Integer activeSlot, SelectedTeam selectedTeam,
                               int startZone, int startAct) {
        this(gameCode, activeSlot, selectedTeam, selectedTeam, startZone, startAct);
    }

    private SaveSessionContext(String gameCode, Integer activeSlot, SelectedTeam selectedTeam,
                               SelectedTeam durableSelectedTeam, int startZone, int startAct) {
        this.gameCode = Objects.requireNonNull(gameCode, "gameCode");
        this.activeSlot = activeSlot;
        this.selectedTeam = Objects.requireNonNull(selectedTeam, "selectedTeam");
        this.durableSelectedTeam = Objects.requireNonNull(
                durableSelectedTeam, "durableSelectedTeam");
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

    /** Returns the effective team used to bootstrap this gameplay session. */
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

    /** Copies this context with a replacement selected team for launch and persistence. */
    public SaveSessionContext withSelectedTeam(SelectedTeam replacement) {
        SelectedTeam selected = Objects.requireNonNull(replacement, "replacement");
        SaveSessionContext copy = new SaveSessionContext(gameCode, activeSlot,
                selected, selected, startZone, startAct);
        copy.clear = clear;
        return copy;
    }

    /** Copies this context with a launch-local team resolved from tagged policy identities. */
    SaveSessionContext withLaunchTeam(com.openggf.game.GameplayLaunchTeam replacement) {
        Objects.requireNonNull(replacement, "replacement");
        SelectedTeam launchTeam = new SelectedTeam(replacement.main().persisted(),
                replacement.sidekicks().stream()
                        .map(com.openggf.game.CharacterKey::persisted).toList());
        SaveSessionContext copy = new SaveSessionContext(gameCode, activeSlot,
                launchTeam, durableSelectedTeam, startZone, startAct);
        copy.clear = clear;
        return copy;
    }

    public void requestSave(SaveReason reason,
                            RuntimeSaveContext context,
                            SaveSnapshotProvider snapshotProvider,
                            SaveManager saveManager) throws IOException {
        if (activeSlot == null) {
            return;
        }
        // Every provider receives the same persistence projection, so game-specific snapshot
        // implementations cannot accidentally serialize a destination-forced launch team.
        RuntimeSaveContext durableContext = RuntimeSaveContext.forGameplayMode(
                context.gameplayMode(), durableProjection());
        saveManager.writeSlot(gameCode, activeSlot,
                snapshotProvider.capture(reason, durableContext));
    }

    private SaveSessionContext durableProjection() {
        SaveSessionContext durable = new SaveSessionContext(gameCode, activeSlot,
                durableSelectedTeam, durableSelectedTeam, startZone, startAct);
        durable.clear = clear;
        return durable;
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
        RuntimeSaveContext durableContext = RuntimeSaveContext.forGameplayMode(
                context.gameplayMode(), durableProjection());
        saveManager.writeSlotAsync(gameCode, activeSlot,
                snapshotProvider.capture(reason, durableContext));
    }
}
