package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestGameId {

    @Test
    public void fromCodeReturnsCorrectValues() {
        assertEquals(GameId.S1, GameId.fromCode("s1"));
        assertEquals(GameId.S2, GameId.fromCode("s2"));
        assertEquals(GameId.S3K, GameId.fromCode("s3k"));
    }

    @Test
    public void fromCodeIsCaseInsensitive() {
        assertEquals(GameId.S2, GameId.fromCode("S2"));
        assertEquals(GameId.S3K, GameId.fromCode("S3K"));
    }

    @Test
    public void fromCodeThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> GameId.fromCode("s4"));
    }

    @Test
    public void codeReturnsLowercase() {
        assertEquals("s1", GameId.S1.code());
        assertEquals("s2", GameId.S2.code());
        assertEquals("s3k", GameId.S3K.code());
    }
}


