package com.openggf.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestCliArguments {

    @Test
    void returnsTheValueAtTheRequestedArgumentIndex() {
        assertEquals("value",
                CliArguments.requireValue(new String[] {"--flag", "value"}, 1, "--flag"));
    }

    @Test
    void parsesNegativeIntegersWithoutAddingValidation() {
        assertEquals(-3, CliArguments.parseInt("-3"));
    }

    @Test
    void rejectsMalformedIntegersWithNumberFormatException() {
        assertThrows(NumberFormatException.class, () -> CliArguments.parseInt("three"));
    }

    @Test
    void reportsTheFlagWhenItsValueIsMissing() {
        assertEquals("Missing value for --flag",
                assertThrows(IllegalArgumentException.class,
                        () -> CliArguments.requireValue(new String[] {"--flag"}, 1, "--flag"))
                        .getMessage());
    }
}
