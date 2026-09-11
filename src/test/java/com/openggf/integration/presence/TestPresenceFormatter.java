package com.openggf.integration.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestPresenceFormatter {

    @Test
    void format_menuPresenceShowsOpenGgfInMenus() {
        PresenceFormatter formatter = new PresenceFormatter();

        PresencePayload payload = formatter.format(PresenceSnapshot.menu(), true, true);

        assertEquals("OpenGGF - In Menus", payload.details());
        assertNull(payload.state());
    }

    @Test
    void format_namedMenuPresenceShowsMenuNameAndGameWhenAvailable() {
        PresenceFormatter formatter = new PresenceFormatter();

        PresencePayload masterTitle = formatter.format(
                PresenceSnapshot.menu("Master Title", null), true, true);
        PresencePayload levelSelect = formatter.format(
                PresenceSnapshot.menu("Level Select", "Sonic 2"), true, true);

        assertEquals("OpenGGF - Master Title", masterTitle.details());
        assertNull(masterTitle.state());
        assertEquals("OpenGGF - Sonic 2", levelSelect.details());
        assertEquals("Level Select", levelSelect.state());
    }

    @Test
    void format_gameplayPresenceIncludesGameZoneTeamAndTimer() {
        PresenceFormatter formatter = new PresenceFormatter();
        PresenceSnapshot snapshot = PresenceSnapshot.gameplay(
                "Sonic 3 & Knuckles",
                "Angel Island Zone",
                1,
                "Sonic & Tails",
                "1:23",
                4980);

        PresencePayload payload = formatter.format(snapshot, true, true);

        assertEquals("OpenGGF - Sonic 3 & Knuckles", payload.details());
        assertEquals("Angel Island Zone Act 1 as Sonic & Tails - 1:23", payload.state());
    }

    @Test
    void format_canHideTimerAndZoneIndependently() {
        PresenceFormatter formatter = new PresenceFormatter();
        PresenceSnapshot snapshot = PresenceSnapshot.gameplay(
                "Sonic 2",
                "Emerald Hill Zone",
                2,
                "Sonic",
                "0:42",
                2520);

        assertEquals("Emerald Hill Zone Act 2 as Sonic",
                formatter.format(snapshot, false, true).state());
        assertEquals("Sonic - 0:42",
                formatter.format(snapshot, true, false).state());
    }
}
