package com.openggf.game.sonic2;

import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2PlayerArtModeAuthority {
    @Test
    void defaultOnePlayerAuthorityOmitsLifePlcForSonicRoutes() {
        assertTrue(Sonic2PlayerArtModeAuthority.onePlayer(PlayerCharacter.SONIC_ALONE)
                .initialLifePlc().isEmpty());
        assertTrue(Sonic2PlayerArtModeAuthority.onePlayer(PlayerCharacter.SONIC_AND_TAILS)
                .initialLifePlc().isEmpty());
    }

    @Test
    void defaultOnePlayerAuthoritySelectsNativeTailsLifePlc() {
        assertEquals(9, Sonic2PlayerArtModeAuthority.onePlayer(PlayerCharacter.TAILS_ALONE)
                .initialLifePlc().orElseThrow());
    }
}
