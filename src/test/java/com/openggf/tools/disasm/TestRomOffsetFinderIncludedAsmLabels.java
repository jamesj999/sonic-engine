package com.openggf.tools.disasm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomOffsetFinderIncludedAsmLabels {
    @TempDir
    Path tempDir;

    @Test
    void calculatesOffsetsForLabelsInsideIncludedMappingAsm() throws IOException {
        writeSyntheticS3kDisasm();

        RomOffsetCalculator calculator = new RomOffsetCalculator(tempDir, RomOffsetFinder.GameProfile.sonic3k());

        assertEquals(0x3DC5C, calculator.calculateOffset("Map_MHZPollen"));
        assertEquals(0x3DC5C, calculator.calculateOffset("Map_MHZPollen_"));
        assertEquals(0x3DC64, calculator.calculateOffset("word_3DC64"));
        assertEquals(0x3DC6C, calculator.calculateOffset("word_3DC6C"));
    }

    @Test
    void searchReturnsIncludedMappingLabelsAsAssemblyData() throws IOException {
        writeSyntheticS3kDisasm();

        DisassemblySearchTool searchTool = new DisassemblySearchTool(
                tempDir, RomOffsetFinder.GameProfile.sonic3k());

        List<DisassemblySearchResult> results = searchTool.search("word_3DC64");

        assertEquals(1, results.size());
        DisassemblySearchResult result = results.get(0);
        assertEquals("word_3DC64", result.getLabel());
        assertEquals(CompressionType.ASSEMBLY_DATA, result.getCompressionType());
        assertEquals("Levels/MHZ/Misc Object Data/Map - Pollen Leaves.asm",
                result.getAsmFilePath().replace('\\', '/'));
    }

    private void writeSyntheticS3kDisasm() throws IOException {
        Path mapping = tempDir.resolve(Path.of(
                "Levels", "MHZ", "Misc Object Data", "Map - Pollen Leaves.asm"));
        Files.createDirectories(mapping.getParent());
        Files.writeString(mapping, """
                Map_MHZPollen_:
                    dc.w word_3DC64-Map_MHZPollen_
                    dc.w word_3DC6C-Map_MHZPollen_
                    dc.w word_3DC74-Map_MHZPollen_
                    dc.w word_3DC7C-Map_MHZPollen_
                word_3DC64:
                    dc.w 1
                    dc.b $FC,$00,$00,$00,$FF,$FC
                word_3DC6C:
                    dc.w 1
                    dc.b $FC,$00,$00,$00,$FF,$FC
                """);

        Path mainAsm = tempDir.resolve("sonic3k.asm");
        Files.writeString(mainAsm, """
                Map_MHZPollen:
                    include "Levels/MHZ/Misc Object Data/Map - Pollen Leaves.asm"
                """);

        assertTrue(Files.exists(mapping));
    }
}
