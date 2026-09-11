package com.openggf.tests.rules;

import com.openggf.data.Game;

/**
 * Identifies which Sonic game a test requires.
 * Named {@code SonicGame} to avoid collision with {@link Game}.
 */
public enum SonicGame {
    SONIC_1("Sonic 1"),
    SONIC_2("Sonic 2"),
    SONIC_3K("Sonic 3 & Knuckles");

    private final String displayName;

    SonicGame(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}


