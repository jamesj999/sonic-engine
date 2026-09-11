package com.openggf.game.dataselect;

import com.openggf.game.save.SaveSlotState;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;

import java.util.List;
import java.util.Map;

/**
 * Host-owned data select semantics for a game.
 */
public interface DataSelectHostProfile {
    String gameCode();

    int slotCount();

    /** Host-native confirmation cue and music-fade cadence for a data-select launch. */
    default DataSelectExitTransition exitTransition() {
        return DataSelectExitTransition.defaultTransition();
    }

    List<SelectedTeam> builtInTeams();

    default List<SelectedTeam> parseExtraTeams(String raw) {
        return List.of();
    }

    SaveSlotSummary summarizeFreshSlot(int slot);

    boolean isPayloadValid(Map<String, Object> payload);

    default boolean isSummaryValid(SaveSlotSummary summary) {
        return summary != null
                && (summary.state() == SaveSlotState.EMPTY
                || summary.state() == SaveSlotState.UNAVAILABLE
                || isPayloadValid(summary.payload()));
    }

    default List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
        return List.of();
    }

    default int clearRestartSelectionCount(Map<String, Object> payload) {
        return clearRestartDestinations(payload).size();
    }

    default int defaultClearRestartIndex(Map<String, Object> payload) {
        return 0;
    }

    /**
     * Returns a host-specific preview for the given save slot payload,
     * or {@code null} if the native S3K zone card rendering should be used.
     *
     * <p>S1 and S2 override this to provide host-owned selected-card metadata such as
     * numbered-zone labels while the donated S3K frontend continues to own the underlying
     * save-screen layout and rendering.
     */
    default HostSlotPreview resolveSlotPreview(Map<String, Object> payload) {
        return null;
    }

    /**
     * Returns a host-specific selected-slot icon index for the donated S3K
     * presentation, or {@code -1} if the native S3K stage card should be used.
     *
     * <p>{@code clearDestination} is the currently selected clear-restart
     * destination when the save is marked clear; otherwise it is {@code null}.
     */
    default int resolveSelectedSlotIconIndex(Map<String, Object> payload, DataSelectDestination clearDestination) {
        return -1;
    }
}
