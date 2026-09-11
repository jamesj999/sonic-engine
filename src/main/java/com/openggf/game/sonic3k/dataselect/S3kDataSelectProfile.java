package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.dataselect.DataSelectExitTransition;
import com.openggf.game.dataselect.DataSelectGameProfile;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * S3K-specific data select game profile.
 * Provides 8 save slots, 3 built-in team selections (Sonic, Sonic+Tails, Knuckles),
 * and custom team parsing from semicolon-separated strings.
 */
public final class S3kDataSelectProfile implements DataSelectGameProfile {

    @Override
    public String gameCode() {
        return "s3k";
    }

    @Override
    public int slotCount() {
        return 8;
    }

    @Override
    public DataSelectExitTransition exitTransition() {
        // SaveScreen exits with sfx_EnterSS, then Level dispatches cmd_FadeOut.
        // S3K's driver owns the 0x28-step, delay-6 cadence.
        return new DataSelectExitTransition(Sonic3kSfx.ENTER_SS.id, 0x28, 6, 1);
    }

    @Override
    public List<SelectedTeam> builtInTeams() {
        return List.of(
                new SelectedTeam("sonic", List.of("tails")),
                new SelectedTeam("sonic", List.of()),
                new SelectedTeam("tails", List.of()),
                new SelectedTeam("knuckles", List.of())
        );
    }

    @Override
    public SaveSlotSummary summarizeFreshSlot(int slot) {
        return SaveSlotSummary.empty(slot);
    }

    @Override
    public List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
        return S3kSaveProgressions.clearRestartDestinations(payload);
    }

    @Override
    public int clearRestartSelectionCount(Map<String, Object> payload) {
        return S3kSaveProgressions.clearRestartSelectionCount(payload);
    }

    @Override
    public int defaultClearRestartIndex(Map<String, Object> payload) {
        return Math.max(0, clearRestartSelectionCount(payload) - 1);
    }

    /**
     * Parses extra team combinations from a semicolon-separated string.
     * Each team is a comma-separated list where the first element is the main character
     * and the rest are sidekicks.
     * <p>
     * Example: {@code "sonic,knuckles;knuckles,tails"} produces two teams:
     * Sonic+Knuckles and Knuckles+Tails.
     *
     * @param raw the raw team string, or null/blank for no extra teams
     * @return the parsed list of extra teams
     */
    public List<SelectedTeam> parseExtraTeams(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<SelectedTeam> result = new ArrayList<>();
        for (String teamRaw : raw.split(";")) {
            List<String> parts = Arrays.stream(teamRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!parts.isEmpty()) {
                result.add(new SelectedTeam(parts.get(0), parts.subList(1, parts.size())));
            }
        }
        return result;
    }

    @Override
    public boolean isPayloadValid(Map<String, Object> payload) {
        return com.openggf.game.sonic1.dataselect.DataSelectPayloadValidators
                .validateCommonPayload(payload, 21, 7);
    }
}
