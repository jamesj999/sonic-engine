package com.openggf.game.sonic1.dataselect;

import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.dataselect.DataSelectExitTransition;
import com.openggf.game.dataselect.DataSelectGameProfile;
import com.openggf.game.dataselect.HostSlotPreview;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.game.sonic1.audio.Sonic1Sfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class S1DataSelectProfile implements DataSelectGameProfile {
    private static final List<DataSelectDestination> CLEAR_RESTARTS = List.of(
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_GHZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_MZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_SYZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_LZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_SLZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_SBZ, 0),
            new DataSelectDestination(Sonic1ZoneConstants.ZONE_FZ, 0)
    );

    @Override public String gameCode() { return "s1"; }
    @Override public int slotCount() { return 8; }
    @Override public DataSelectExitTransition exitTransition() {
        return new DataSelectExitTransition(Sonic1Sfx.ENTER_SS.id, 0x28, 3, 1);
    }
    @Override public List<SelectedTeam> builtInTeams() {
        return List.of(
                new SelectedTeam("sonic", List.of()),
                new SelectedTeam("sonic", List.of("tails")),
                new SelectedTeam("knuckles", List.of())
        );
    }
    @Override public SaveSlotSummary summarizeFreshSlot(int slot) { return SaveSlotSummary.empty(slot); }

    @Override
    public List<SelectedTeam> parseExtraTeams(String raw) {
        return parseTeams(raw);
    }

    @Override
    public List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
        return Boolean.TRUE.equals(payload.get("clear")) ? CLEAR_RESTARTS : List.of();
    }

    @Override
    public boolean isPayloadValid(Map<String, Object> payload) {
        return DataSelectPayloadValidators.validateCommonPayload(payload, 6, 6);
    }

    @Override
    public HostSlotPreview resolveSlotPreview(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object zoneObj = payload.get("zone");
        if (!(zoneObj instanceof Number zone)) {
            return null;
        }
        int zoneId = zone.intValue();
        return HostSlotPreview.numberedZone(zoneId + 1);
    }

    @Override
    public int resolveSelectedSlotIconIndex(Map<String, Object> payload, DataSelectDestination clearDestination) {
        if (clearDestination != null) {
            return zoneToPreviewIndex(clearDestination.zone());
        }
        if (payload == null) {
            return -1;
        }
        Object zoneObj = payload.get("zone");
        if (!(zoneObj instanceof Number zone)) {
            return -1;
        }
        return zoneToPreviewIndex(zone.intValue());
    }

    private static int zoneToPreviewIndex(int zoneId) {
        return zoneId >= 0 && zoneId < 7 ? zoneId : -1;
    }

    private static List<SelectedTeam> parseTeams(String raw) {
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
}
