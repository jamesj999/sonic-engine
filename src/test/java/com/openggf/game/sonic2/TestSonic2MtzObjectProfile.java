package com.openggf.game.sonic2;

import com.openggf.tools.Sonic2ObjectProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2MtzObjectProfile {

    @Test
    void mtzDynamicBossObjectsAreMarkedImplemented() {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();

        assertTrue(profile.getImplementedIds().contains(0x53),
                "Obj53 MTZ boss orb is spawned dynamically by Obj54 and should be tracked as implemented");
        assertTrue(profile.getImplementedIds().contains(0x54),
                "Obj54 MTZ boss has a registered implementation and should not be reported as missing");
    }
}
