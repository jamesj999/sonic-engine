package com.openggf.level.resources;

import com.openggf.data.Rom;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.Pattern;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestNemesisPlcRomVectors {

    @Nested
    @RequiresRom(SonicGame.SONIC_1)
    class Sonic1Vectors {
        @Test
        void derivesCountForFirstStandardPlcEntry() throws IOException {
            assertFirstEntryCount(TestEnvironment.currentRom(), Sonic1Constants.ART_LOAD_CUES_ADDR, 0);
        }
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_2)
    class Sonic2Vectors {
        @Test
        void derivesCountForFirstStandardPlcEntry() throws IOException {
            assertFirstEntryCount(TestEnvironment.currentRom(), Sonic2Constants.ART_LOAD_CUES_ADDR,
                    Sonic2Constants.PLC_STD1);
        }
    }

    private static void assertFirstEntryCount(Rom rom, int plcTableAddress, int plcId) throws IOException {
        PlcDefinition definition = PlcParser.parse(rom, plcTableAddress, plcId);
        assertFalse(definition.entries().isEmpty(), "vector PLC must contain a Nemesis stream");
        PlcEntry entry = definition.entries().getFirst();
        byte[] raw = PlcParser.decompressEntryRaw(rom, entry);

        assertEquals(raw.length / Pattern.PATTERN_SIZE_IN_ROM,
                NemesisPlcPatternCounts.derive(rom, definition).getFirst());
    }
}
