package com.openggf.level.resources;

import com.openggf.data.Rom;
import com.openggf.level.Pattern;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class TestNemesisPlcPatternCounts {

    @Test
    void derivesPatternCountsFromRawNemesisData() throws IOException {
        Rom rom = mock(Rom.class);
        PlcEntry entry = new PlcEntry(0x100, 0x20);
        PlcDefinition definition = new PlcDefinition(1, List.of(entry));
        byte[] raw = new byte[2 * Pattern.PATTERN_SIZE_IN_ROM];

        try (MockedStatic<PlcParser> parser = mockStatic(PlcParser.class)) {
            parser.when(() -> PlcParser.decompressEntryRaw(rom, entry)).thenReturn(raw);

            List<Integer> counts = NemesisPlcPatternCounts.derive(rom, definition);

            assertEquals(raw.length / Pattern.PATTERN_SIZE_IN_ROM, counts.getFirst());
            assertThrows(UnsupportedOperationException.class, () -> counts.add(3));
        }
    }

    @Test
    void derivesEveryPatternCountInDefinitionOrder() throws IOException {
        Rom rom = mock(Rom.class);
        PlcEntry first = new PlcEntry(0x100, 0x20);
        PlcEntry second = new PlcEntry(0x200, 0x40);
        PlcDefinition definition = new PlcDefinition(1, List.of(first, second));

        try (MockedStatic<PlcParser> parser = mockStatic(PlcParser.class)) {
            parser.when(() -> PlcParser.decompressEntryRaw(rom, first))
                    .thenReturn(new byte[Pattern.PATTERN_SIZE_IN_ROM]);
            parser.when(() -> PlcParser.decompressEntryRaw(rom, second))
                    .thenReturn(new byte[3 * Pattern.PATTERN_SIZE_IN_ROM]);

            assertEquals(List.of(1, 3), NemesisPlcPatternCounts.derive(rom, definition));
        }
    }

    @Test
    void rejectsRawNemesisDataThatDoesNotContainWholePatterns() {
        Rom rom = mock(Rom.class);
        PlcEntry entry = new PlcEntry(0x100, 0x20);
        PlcDefinition definition = new PlcDefinition(1, List.of(entry));

        try (MockedStatic<PlcParser> parser = mockStatic(PlcParser.class)) {
            parser.when(() -> PlcParser.decompressEntryRaw(rom, entry))
                    .thenReturn(new byte[Pattern.PATTERN_SIZE_IN_ROM + 1]);

            assertThrows(IOException.class, () -> NemesisPlcPatternCounts.derive(rom, definition));
        }
    }
}
