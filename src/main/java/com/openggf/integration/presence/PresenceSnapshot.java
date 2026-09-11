package com.openggf.integration.presence;

import java.util.Objects;

public record PresenceSnapshot(
        PresenceMode mode,
        String menuName,
        String gameName,
        String zoneName,
        Integer actNumber,
        String teamName,
        String displayTime,
        long timerFrames) {

    public PresenceSnapshot {
        Objects.requireNonNull(mode, "mode");
    }

    public static PresenceSnapshot menu() {
        return menu(null, null);
    }

    public static PresenceSnapshot menu(String menuName, String gameName) {
        return new PresenceSnapshot(PresenceMode.MENU, menuName, gameName,
                null, null, null, null, 0);
    }

    public static PresenceSnapshot gameplay(String gameName,
                                            String zoneName,
                                            Integer actNumber,
                                            String teamName,
                                            String displayTime,
                                            long timerFrames) {
        return new PresenceSnapshot(PresenceMode.GAMEPLAY, null, gameName, zoneName,
                actNumber, teamName, displayTime, timerFrames);
    }

    public boolean sameExceptTimer(PresenceSnapshot other) {
        if (other == null) {
            return false;
        }
        return mode == other.mode
                && Objects.equals(menuName, other.menuName)
                && Objects.equals(gameName, other.gameName)
                && Objects.equals(zoneName, other.zoneName)
                && Objects.equals(actNumber, other.actNumber)
                && Objects.equals(teamName, other.teamName);
    }
}
