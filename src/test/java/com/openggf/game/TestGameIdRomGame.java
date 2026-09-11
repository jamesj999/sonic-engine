package com.openggf.game;

import com.openggf.data.RomGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestGameIdRomGame {

    @Test
    void mapsSonic1ToItsRomFamily() {
        assertEquals(RomGame.S1, GameId.S1.romGame());
    }

    @Test
    void mapsSonic2ToItsRomFamily() {
        assertEquals(RomGame.S2, GameId.S2.romGame());
    }

    @Test
    void mapsSonic3kToItsRomFamily() {
        assertEquals(RomGame.S3K, GameId.S3K.romGame());
    }
}
