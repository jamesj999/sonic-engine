package com.openggf.game;

import com.openggf.Engine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSidekickConfigParsing {

    @Test
    void emptyStringReturnsEmptyList() {
        assertEquals(List.of(), Engine.parseSidekickConfig(""));
    }

    @Test
    void singleValueReturnsSingletonList() {
        assertEquals(List.of("tails"), Engine.parseSidekickConfig("tails"));
    }

    @Test
    void commaSeparatedReturnsList() {
        assertEquals(List.of("tails", "knuckles", "sonic"),
            Engine.parseSidekickConfig("tails,knuckles,sonic"));
    }

    @Test
    void whitespaceIsTrimmed() {
        assertEquals(List.of("tails", "sonic"),
            Engine.parseSidekickConfig(" tails , sonic "));
    }

    @Test
    void duplicatesPreserved() {
        assertEquals(List.of("sonic", "sonic", "sonic"),
            Engine.parseSidekickConfig("sonic,sonic,sonic"));
    }
}


