package com.openggf.level.resources;

import com.openggf.data.Rom;
import com.openggf.level.Pattern;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Derives the pattern workload for each Nemesis entry in a PLC definition. */
public final class NemesisPlcPatternCounts {
    private NemesisPlcPatternCounts() {
    }

    /**
     * Decompresses each PLC entry through the shared parser boundary and returns its tile count.
     *
     * @throws IOException when a stream cannot be decompressed or is not whole-pattern aligned
     */
    public static List<Integer> derive(Rom rom, PlcDefinition definition) throws IOException {
        Objects.requireNonNull(rom, "rom");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(definition.entries(), "definition.entries");

        List<Integer> patternCounts = new ArrayList<>(definition.entries().size());
        for (PlcEntry entry : definition.entries()) {
            Objects.requireNonNull(entry, "definition entry");
            byte[] raw = PlcParser.decompressEntryRaw(rom, entry);
            if (raw == null || raw.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
                throw new IOException("Nemesis PLC entry 0x"
                        + Integer.toHexString(entry.romAddr())
                        + " does not decompress to whole patterns");
            }
            patternCounts.add(raw.length / Pattern.PATTERN_SIZE_IN_ROM);
        }
        return List.copyOf(patternCounts);
    }
}
