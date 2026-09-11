package com.openggf.integration.presence;

import java.util.ArrayList;
import java.util.List;

public final class PresenceFormatter {
    private static final String APP_NAME = "OpenGGF";

    public PresencePayload format(PresenceSnapshot snapshot,
                                  boolean showTimer,
                                  boolean showZone) {
        if (snapshot == null || snapshot.mode() == PresenceMode.MENU) {
            if (snapshot == null || isBlank(snapshot.menuName())) {
                return new PresencePayload(APP_NAME + " - In Menus", null);
            }
            if (isBlank(snapshot.gameName())) {
                return new PresencePayload(APP_NAME + " - " + snapshot.menuName(), null);
            }
            return new PresencePayload(APP_NAME + " - " + snapshot.gameName(), snapshot.menuName());
        }

        String gameName = nonBlankOr(snapshot.gameName(), "Gameplay");
        String details = APP_NAME + " - " + gameName;
        List<String> stateParts = new ArrayList<>();

        if (showZone && !isBlank(snapshot.zoneName())) {
            String zoneText = snapshot.zoneName();
            if (snapshot.actNumber() != null && snapshot.actNumber() > 0) {
                zoneText += " Act " + snapshot.actNumber();
            }
            stateParts.add(zoneText);
        }

        if (!isBlank(snapshot.teamName())) {
            if (stateParts.isEmpty()) {
                stateParts.add(snapshot.teamName());
            } else {
                int last = stateParts.size() - 1;
                stateParts.set(last, stateParts.get(last) + " as " + snapshot.teamName());
            }
        }

        if (showTimer && !isBlank(snapshot.displayTime())) {
            stateParts.add(snapshot.displayTime());
        }

        return new PresencePayload(details, String.join(" - ", stateParts));
    }

    private static String nonBlankOr(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
