package com.openggf.game.sonic2;

import com.openggf.game.PlayerCharacter;

import java.util.OptionalInt;

/** Session-owned authority for the native S2 player-life PLC selection. */
@FunctionalInterface
public interface Sonic2PlayerArtModeAuthority {
    OptionalInt initialLifePlc();

    static Sonic2PlayerArtModeAuthority onePlayer(PlayerCharacter character) {
        return () -> character == PlayerCharacter.TAILS_ALONE ? OptionalInt.of(9) : OptionalInt.empty();
    }
}
