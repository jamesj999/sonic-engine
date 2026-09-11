package com.openggf.game.dataselect;

import com.openggf.game.save.SaveSlotState;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Presentation-independent controller for data-select state and actions.
 */
public class DataSelectSessionController {
    private final DataSelectHostProfile hostProfile;
    private final DataSelectMenuModel model = new DataSelectMenuModel();
    private final List<SaveSlotSummary> slotSummaries = new ArrayList<>();
    private final int[] newSlotTeamIndices;
    private List<SelectedTeam> availableTeams = List.of();
    private DataSelectAction pendingAction = DataSelectAction.none();
    private int noSaveTeamIndex;

    public DataSelectSessionController(DataSelectHostProfile hostProfile) {
        this.hostProfile = hostProfile;
        this.newSlotTeamIndices = new int[hostProfile.slotCount()];
    }

    public DataSelectHostProfile hostProfile() {
        return hostProfile;
    }

    public DataSelectMenuModel model() {
        return model;
    }

    public DataSelectMenuModel menuModel() {
        return model;
    }

    public List<SelectedTeam> availableTeams() {
        return availableTeams;
    }

    public List<SaveSlotSummary> slotSummaries() {
        return Collections.unmodifiableList(slotSummaries);
    }

    public void reset() {
        model.reset();
        slotSummaries.clear();
        java.util.Arrays.fill(newSlotTeamIndices, 0);
        availableTeams = List.of();
        pendingAction = DataSelectAction.none();
        noSaveTeamIndex = 0;
    }

    public void loadAvailableTeams(String rawExtraTeams) {
        List<SelectedTeam> teams = new ArrayList<>(hostProfile.builtInTeams());
        teams.addAll(hostProfile.parseExtraTeams(rawExtraTeams));
        availableTeams = List.copyOf(teams);
        syncSelectedTeamIndexToCurrentRow();
    }

    public void loadSlotSummaries(List<SaveSlotSummary> summaries) {
        slotSummaries.clear();
        for (int slot = 1; slot <= hostProfile.slotCount(); slot++) {
            SaveSlotSummary summary = slot <= summaries.size() ? summaries.get(slot - 1) : null;
            slotSummaries.add(hostProfile.isSummaryValid(summary)
                    ? summary
                    : hostProfile.summarizeFreshSlot(slot));
        }
        syncSelectedTeamIndexToCurrentRow();
    }

    public int totalRows() {
        return hostProfile.slotCount() + 2;
    }

    public int deleteRowIndex() {
        return hostProfile.slotCount() + 1;
    }

    public void moveSelection(int delta) {
        int oldRow = model.getSelectedRow();
        int newRow = Math.max(0, Math.min(totalRows() - 1, oldRow + delta));
        if (newRow != oldRow) {
            model.setSelectedRow(newRow);
            model.setClearRestartIndex(defaultClearRestartIndexForRow(newRow));
            model.setSelectedTeamIndex(teamIndexForRow(newRow));
        }
    }

    public void cycleTeam(int delta) {
        if (availableTeams.isEmpty()) {
            return;
        }
        int row = model.getSelectedRow();
        int nextIndex = Math.floorMod(teamIndexForRow(row) + delta, availableTeams.size());
        if (row == 0) {
            noSaveTeamIndex = nextIndex;
        } else if (row >= 1 && row <= hostProfile.slotCount() && isFreshSlotRow(row)) {
            newSlotTeamIndices[row - 1] = nextIndex;
        }
        model.setSelectedTeamIndex(nextIndex);
    }

    public void cycleClearRestart(int delta) {
        int selectionCount = currentClearRestartSelectionCount();
        if (selectionCount <= 0) {
            return;
        }
        model.setClearRestartIndex(Math.floorMod(model.getClearRestartIndex() + delta, selectionCount));
    }

    public void dismissDeleteMode() {
        model.setDeleteMode(false);
    }

    public boolean shouldCycleClearRestart() {
        return !model.isDeleteMode() && !currentClearRestartDestinations().isEmpty();
    }

    public SelectedTeam currentTeam() {
        if (availableTeams.isEmpty()) {
            return new SelectedTeam("sonic", List.of());
        }
        return teamForRow(model.getSelectedRow());
    }

    public SelectedTeam noSaveTeam() {
        return teamForIndex(noSaveTeamIndex);
    }

    public SelectedTeam teamForRow(int row) {
        if (availableTeams.isEmpty()) {
            return new SelectedTeam("sonic", List.of());
        }
        if (row == 0) {
            return noSaveTeam();
        }
        if (row >= 1 && row <= hostProfile.slotCount()) {
            SaveSlotSummary summary = slotSummaries.get(row - 1);
            if (summary.state() == SaveSlotState.EMPTY) {
                return teamForIndex(newSlotTeamIndices[row - 1]);
            }
        }
        return teamForIndex(model.getSelectedTeamIndex());
    }

    public List<DataSelectDestination> currentClearRestartDestinations() {
        int row = model.getSelectedRow();
        if (row < 1 || row > hostProfile.slotCount()) {
            return List.of();
        }
        SaveSlotSummary summary = slotSummaries.get(row - 1);
        if (!summary.isLoadable()) {
            return List.of();
        }
        return hostProfile.clearRestartDestinations(summary.payload());
    }

    public int currentClearRestartIndex() {
        return model.getClearRestartIndex();
    }

    public DataSelectDestination currentClearRestartDestination() {
        List<DataSelectDestination> destinations = currentClearRestartDestinations();
        if (destinations.isEmpty()) {
            return null;
        }
        int index = model.getClearRestartIndex();
        if (index < 0 || index >= destinations.size()) {
            return null;
        }
        return destinations.get(index);
    }

    public DataSelectAction confirmSelection() {
        int row = model.getSelectedRow();
        if (model.isDeleteMode()) {
            if (row < 1 || row > hostProfile.slotCount()) {
                return DataSelectAction.none();
            }
            model.setDeleteMode(false);
            return DataSelectAction.deleteSlot(row);
        }
        if (row == 0) {
            return new DataSelectAction(DataSelectActionType.NO_SAVE_START, -1, 0, 0, currentTeam());
        }
        if (row == deleteRowIndex()) {
            model.setDeleteMode(!model.isDeleteMode());
            return DataSelectAction.none();
        }
        if (row < 1 || row > hostProfile.slotCount()) {
            return DataSelectAction.none();
        }

        int slot = row;
        SaveSlotSummary summary = slotSummaries.get(slot - 1);
        if (summary.state() == SaveSlotState.EMPTY) {
            return new DataSelectAction(DataSelectActionType.NEW_SLOT_START, slot, 0, 0, currentTeam());
        }
        if (!summary.isLoadable()) {
            return DataSelectAction.none();
        }

        Map<String, Object> payload = summary.payload();
        DataSelectDestination clearDestination = currentClearRestartDestination();
        if (Boolean.TRUE.equals(payload.get("clear")) && clearDestination == null) {
            return DataSelectAction.none();
        }
        boolean clear = Boolean.TRUE.equals(payload.get("clear")) && clearDestination != null;
        int zone = clear ? clearDestination.zone() : readInt(payload, "zone", 0);
        int act = clear ? clearDestination.act() : readInt(payload, "act", 0);
        return new DataSelectAction(
                clear ? DataSelectActionType.CLEAR_RESTART : DataSelectActionType.LOAD_SLOT,
                slot,
                zone,
                act,
                teamFromPayload(payload));
    }

    public void queuePendingAction(DataSelectAction action) {
        pendingAction = action == null ? DataSelectAction.none() : action;
    }

    public DataSelectAction consumePendingAction() {
        DataSelectAction action = pendingAction;
        pendingAction = DataSelectAction.none();
        return action;
    }

    @SuppressWarnings("unchecked")
    private static SelectedTeam teamFromPayload(Map<String, Object> payload) {
        String main = String.valueOf(payload.getOrDefault("mainCharacter", "sonic"));
        Object sidekicksRaw = payload.get("sidekicks");
        List<String> sidekicks = sidekicksRaw instanceof List<?>
                ? ((List<?>) sidekicksRaw).stream().map(String::valueOf).toList()
                : List.of();
        return new SelectedTeam(main, sidekicks);
    }

    private static int readInt(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private int defaultClearRestartIndexForRow(int row) {
        if (row < 1 || row > hostProfile.slotCount()) {
            return 0;
        }
        SaveSlotSummary summary = slotSummaries.get(row - 1);
        if (!summary.isLoadable()) {
            return 0;
        }
        List<DataSelectDestination> destinations = hostProfile.clearRestartDestinations(summary.payload());
        int selectionCount = hostProfile.clearRestartSelectionCount(summary.payload());
        if (selectionCount <= 0) {
            return 0;
        }
        int requested = hostProfile.defaultClearRestartIndex(summary.payload());
        return Math.max(0, Math.min(selectionCount - 1, requested));
    }

    private int currentClearRestartSelectionCount() {
        int row = model.getSelectedRow();
        if (row < 1 || row > hostProfile.slotCount()) {
            return 0;
        }
        SaveSlotSummary summary = slotSummaries.get(row - 1);
        if (!summary.isLoadable()) {
            return 0;
        }
        return hostProfile.clearRestartSelectionCount(summary.payload());
    }

    private SelectedTeam teamForIndex(int index) {
        if (availableTeams.isEmpty()) {
            return new SelectedTeam("sonic", List.of());
        }
        return availableTeams.get(Math.floorMod(index, availableTeams.size()));
    }

    private int teamIndexForRow(int row) {
        if (row == 0) {
            return noSaveTeamIndex;
        }
        if (row >= 1 && row <= hostProfile.slotCount() && isFreshSlotRow(row)) {
            return newSlotTeamIndices[row - 1];
        }
        return model.getSelectedTeamIndex();
    }

    private boolean isFreshSlotRow(int row) {
        if (row < 1 || row > hostProfile.slotCount() || row > slotSummaries.size()) {
            return false;
        }
        return slotSummaries.get(row - 1).state() == SaveSlotState.EMPTY;
    }

    private void syncSelectedTeamIndexToCurrentRow() {
        model.setSelectedTeamIndex(teamIndexForRow(model.getSelectedRow()));
    }
}
