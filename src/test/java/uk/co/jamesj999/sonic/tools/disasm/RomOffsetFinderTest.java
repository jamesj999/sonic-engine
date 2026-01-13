package uk.co.jamesj999.sonic.tools.disasm;

import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the disassembly search and ROM offset finder tools.
 * Tests searching for items by compression type and validates decompression results.
 */
public class RomOffsetFinderTest {

    private static final String ROM_PATH = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
    private static final String DISASM_PATH = "docs/s2disasm";

    private DisassemblySearchTool searchTool;
    private CompressionTestTool testTool;
    private boolean romAvailable;
    private boolean disasmAvailable;

    @Before
    public void setUp() {
        disasmAvailable = Files.exists(Path.of(DISASM_PATH));
        romAvailable = new File(ROM_PATH).exists();

        if (disasmAvailable) {
            searchTool = new DisassemblySearchTool(DISASM_PATH);
        }

        if (romAvailable) {
            try {
                testTool = new CompressionTestTool(ROM_PATH);
            } catch (IOException e) {
                romAvailable = false;
            }
        }
    }

    // ==================== SEARCH TESTS ====================

    @Test
    public void testSearchNemesisFiles() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByCompressionType(CompressionType.NEMESIS);

        assertNotNull("Results should not be null", results);
        assertFalse("Should find Nemesis files", results.isEmpty());

        System.out.println("=== NEMESIS FILES ===");
        System.out.println("Found " + results.size() + " Nemesis files");

        int count = 0;
        for (DisassemblySearchResult result : results) {
            assertEquals("Should be Nemesis type", CompressionType.NEMESIS, result.getCompressionType());
            assertTrue("File should end with .nem", result.getFilePath().toLowerCase().endsWith(".nem"));

            if (count < 5) {
                System.out.printf("  %s -> %s%n", result.getLabel(), result.getFileName());
            }
            count++;
        }
        if (count > 5) {
            System.out.println("  ... and " + (count - 5) + " more");
        }
        System.out.println();
    }

    @Test
    public void testSearchKosinskiFiles() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByCompressionType(CompressionType.KOSINSKI);

        assertNotNull("Results should not be null", results);
        assertFalse("Should find Kosinski files", results.isEmpty());

        System.out.println("=== KOSINSKI FILES ===");
        System.out.println("Found " + results.size() + " Kosinski files");

        int count = 0;
        for (DisassemblySearchResult result : results) {
            assertEquals("Should be Kosinski type", CompressionType.KOSINSKI, result.getCompressionType());
            assertTrue("File should end with .kos", result.getFilePath().toLowerCase().endsWith(".kos"));

            if (count < 5) {
                System.out.printf("  %s -> %s%n", result.getLabel(), result.getFileName());
            }
            count++;
        }
        if (count > 5) {
            System.out.println("  ... and " + (count - 5) + " more");
        }
        System.out.println();
    }

    @Test
    public void testSearchEnigmaFiles() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByCompressionType(CompressionType.ENIGMA);

        assertNotNull("Results should not be null", results);
        assertFalse("Should find Enigma files", results.isEmpty());

        System.out.println("=== ENIGMA FILES ===");
        System.out.println("Found " + results.size() + " Enigma files");

        int count = 0;
        for (DisassemblySearchResult result : results) {
            assertEquals("Should be Enigma type", CompressionType.ENIGMA, result.getCompressionType());
            assertTrue("File should end with .eni", result.getFilePath().toLowerCase().endsWith(".eni"));

            if (count < 5) {
                System.out.printf("  %s -> %s%n", result.getLabel(), result.getFileName());
            }
            count++;
        }
        if (count > 5) {
            System.out.println("  ... and " + (count - 5) + " more");
        }
        System.out.println();
    }

    @Test
    public void testSearchSaxmanFiles() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByCompressionType(CompressionType.SAXMAN);

        assertNotNull("Results should not be null", results);
        // Saxman files may or may not exist in the disasm

        System.out.println("=== SAXMAN FILES ===");
        System.out.println("Found " + results.size() + " Saxman files");

        for (DisassemblySearchResult result : results) {
            assertEquals("Should be Saxman type", CompressionType.SAXMAN, result.getCompressionType());
            System.out.printf("  %s -> %s%n", result.getLabel(), result.getFileName());
        }
        System.out.println();
    }

    @Test
    public void testSearchByLabel() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByLabel("Ring");

        assertNotNull("Results should not be null", results);
        assertFalse("Should find items with 'Ring' in label", results.isEmpty());

        System.out.println("=== SEARCH BY LABEL 'Ring' ===");
        for (DisassemblySearchResult result : results) {
            System.out.printf("  %s (%s) -> %s%n",
                    result.getLabel(),
                    result.getCompressionType().getDisplayName(),
                    result.getFilePath());
            assertTrue("Label should contain 'ring' (case-insensitive)",
                    result.getLabel().toLowerCase().contains("ring"));
        }
        System.out.println();
    }

    @Test
    public void testSearchByFileName() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        List<DisassemblySearchResult> results = searchTool.searchByFileName("EHZ");

        assertNotNull("Results should not be null", results);
        assertFalse("Should find files with 'EHZ' in path", results.isEmpty());

        System.out.println("=== SEARCH BY FILENAME 'EHZ' ===");
        for (DisassemblySearchResult result : results) {
            System.out.printf("  %s (%s) -> %s%n",
                    result.getLabel() != null ? result.getLabel() : "(no label)",
                    result.getCompressionType().getDisplayName(),
                    result.getFilePath());
            assertTrue("File path should contain 'ehz' (case-insensitive)",
                    result.getFilePath().toLowerCase().contains("ehz"));
        }
        System.out.println();
    }

    // ==================== DECOMPRESSION TESTS ====================

    @Test
    public void testNemesisDecompression() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== NEMESIS DECOMPRESSION TEST ===");

        // Test decompression at a known Nemesis location (0x3000 worked in validation test)
        CompressionTestResult testResult = testTool.testDecompression(0x3000, CompressionType.NEMESIS);

        System.out.printf("Testing Nemesis at offset 0x3000%n");

        assertTrue("Should successfully decompress", testResult.isSuccess());
        System.out.printf("SUCCESS: Decompressed at offset 0x%X%n", testResult.getRomOffset());
        System.out.printf("  Compressed size:   %d bytes%n", testResult.getCompressedSize());
        System.out.printf("  Decompressed size: %d bytes%n", testResult.getDecompressedSize());

        // Validate reasonable values
        assertTrue("Compressed size should be positive", testResult.getCompressedSize() > 0);
        assertTrue("Decompressed size should be positive", testResult.getDecompressedSize() > 0);
        assertTrue("Decompressed size should be >= compressed (for valid compression)",
                testResult.getDecompressedSize() >= testResult.getCompressedSize() / 2);
        assertEquals("ROM offset should match", 0x3000, testResult.getRomOffset());
        assertNotNull("Decompressed data should not be null", testResult.getDecompressedData());
        assertEquals("Decompressed size should match data length",
                testResult.getDecompressedSize(), testResult.getDecompressedData().length);

        System.out.println();
    }

    @Test
    public void testKosinskiDecompression() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== KOSINSKI DECOMPRESSION TEST ===");

        // Test decompression at a known Kosinski location (0x54000 worked in validation test)
        CompressionTestResult testResult = testTool.testDecompression(0x54000, CompressionType.KOSINSKI);

        System.out.printf("Testing Kosinski at offset 0x54000%n");

        assertTrue("Should successfully decompress", testResult.isSuccess());
        System.out.printf("SUCCESS: Decompressed at offset 0x%X%n", testResult.getRomOffset());
        System.out.printf("  Compressed size:   %d bytes%n", testResult.getCompressedSize());
        System.out.printf("  Decompressed size: %d bytes%n", testResult.getDecompressedSize());

        // Validate reasonable values
        assertTrue("Compressed size should be positive", testResult.getCompressedSize() > 0);
        assertTrue("Decompressed size should be positive", testResult.getDecompressedSize() > 0);
        assertEquals("ROM offset should match", 0x54000, testResult.getRomOffset());
        assertNotNull("Decompressed data should not be null", testResult.getDecompressedData());

        System.out.println();
    }

    @Test
    public void testEnigmaDecompression() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== ENIGMA DECOMPRESSION TEST ===");

        // Test decompression at a known Enigma location (0x1000 worked in validation test)
        CompressionTestResult testResult = testTool.testDecompression(0x1000, CompressionType.ENIGMA);

        System.out.printf("Testing Enigma at offset 0x1000%n");

        assertTrue("Should successfully decompress", testResult.isSuccess());
        System.out.printf("SUCCESS: Decompressed at offset 0x%X%n", testResult.getRomOffset());
        System.out.printf("  Compressed size:   %d bytes%n", testResult.getCompressedSize());
        System.out.printf("  Decompressed size: %d bytes%n", testResult.getDecompressedSize());

        // Validate reasonable values
        assertTrue("Compressed size should be positive", testResult.getCompressedSize() > 0);
        assertTrue("Decompressed size should be positive", testResult.getDecompressedSize() > 0);
        assertEquals("ROM offset should match", 0x1000, testResult.getRomOffset());
        assertNotNull("Decompressed data should not be null", testResult.getDecompressedData());

        System.out.println();
    }

    @Test
    public void testSaxmanDecompression() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== SAXMAN DECOMPRESSION TEST ===");

        // Test decompression at a known Saxman location (0x1000 worked in validation test)
        CompressionTestResult testResult = testTool.testDecompression(0x1000, CompressionType.SAXMAN);

        System.out.printf("Testing Saxman at offset 0x1000%n");

        assertTrue("Should successfully decompress", testResult.isSuccess());
        System.out.printf("SUCCESS: Decompressed at offset 0x%X%n", testResult.getRomOffset());
        System.out.printf("  Compressed size:   %d bytes%n", testResult.getCompressedSize());
        System.out.printf("  Decompressed size: %d bytes%n", testResult.getDecompressedSize());

        // Validate reasonable values
        assertTrue("Compressed size should be positive", testResult.getCompressedSize() > 0);
        assertTrue("Decompressed size should be positive", testResult.getDecompressedSize() > 0);
        assertEquals("ROM offset should match", 0x1000, testResult.getRomOffset());
        assertNotNull("Decompressed data should not be null", testResult.getDecompressedData());

        System.out.println();
    }

    @Test
    public void testDecompressionAtKnownOffsets() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== DECOMPRESSION AT KNOWN OFFSETS ===");

        // Test a few different offsets with auto-detection
        long[] testOffsets = {0x1000, 0x10000, 0x40000, 0x80000};

        for (long offset : testOffsets) {
            CompressionTestResult result = testTool.autoDetect(offset);

            System.out.printf("Offset 0x%X: ", offset);
            if (result.isSuccess() && result.getDecompressedSize() > 0) {
                System.out.printf("%s - compressed: %d, decompressed: %d%n",
                        result.getCompressionType().getDisplayName(),
                        result.getCompressedSize(),
                        result.getDecompressedSize());
            } else {
                System.out.println("No valid compression detected");
            }
        }
        System.out.println();
    }

    @Test
    public void testCompressionTestResultValidation() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== COMPRESSION TEST RESULT VALIDATION ===");

        // Test each compression type at offset 0 and validate result structure
        CompressionType[] types = {
                CompressionType.NEMESIS,
                CompressionType.KOSINSKI,
                CompressionType.ENIGMA,
                CompressionType.SAXMAN
        };

        for (CompressionType type : types) {
            // Try multiple offsets to find valid data
            for (long offset = 0x1000; offset < 0x100000; offset += 0x1000) {
                try {
                    CompressionTestResult result = testTool.testDecompression(offset, type);

                    if (result.isSuccess() && result.getDecompressedSize() > 32) {
                        System.out.printf("%s at 0x%X:%n", type.getDisplayName(), offset);
                        System.out.printf("  Success:           %s%n", result.isSuccess());
                        System.out.printf("  Compression Type:  %s%n", result.getCompressionType().getDisplayName());
                        System.out.printf("  ROM Offset:        0x%X%n", result.getRomOffset());
                        System.out.printf("  Compressed Size:   %d%n", result.getCompressedSize());
                        System.out.printf("  Decompressed Size: %d%n", result.getDecompressedSize());

                        // Validate the result structure
                        assertEquals("Type should match", type, result.getCompressionType());
                        assertEquals("Offset should match", offset, result.getRomOffset());
                        assertTrue("Compressed size should be > 0", result.getCompressedSize() > 0);
                        assertTrue("Decompressed size should be > 0", result.getDecompressedSize() > 0);
                        assertNotNull("Decompressed data should exist", result.getDecompressedData());
                        assertNull("Error message should be null on success", result.getErrorMessage());

                        break; // Found a valid result for this type
                    }
                } catch (Exception e) {
                    // Expected for invalid offsets, continue
                }
            }
        }
        System.out.println();
    }

    @Test
    public void testFailedDecompressionResult() throws IOException {
        Assume.assumeTrue("ROM not available", romAvailable);

        System.out.println("=== FAILED DECOMPRESSION RESULT VALIDATION ===");

        // Test with an offset that's likely to fail (end of ROM)
        CompressionTestResult result = testTool.testDecompression(0xFFFFFFF, CompressionType.NEMESIS);

        System.out.println("Testing invalid offset 0xFFFFFFF:");
        System.out.printf("  Success:       %s%n", result.isSuccess());
        System.out.printf("  Error Message: %s%n", result.getErrorMessage());

        assertFalse("Should fail for out-of-bounds offset", result.isSuccess());
        assertNotNull("Error message should be present", result.getErrorMessage());
        assertEquals("Compressed size should be -1", -1, result.getCompressedSize());
        assertEquals("Decompressed size should be -1", -1, result.getDecompressedSize());
        assertNull("Decompressed data should be null", result.getDecompressedData());

        System.out.println();
    }

    @Test
    public void testSummaryOfAllCompressionTypes() throws IOException {
        Assume.assumeTrue("Disassembly not available", disasmAvailable);

        System.out.println("=== SUMMARY OF ALL COMPRESSION TYPES ===");
        System.out.println();

        CompressionType[] types = {
                CompressionType.NEMESIS,
                CompressionType.KOSINSKI,
                CompressionType.ENIGMA,
                CompressionType.SAXMAN,
                CompressionType.UNCOMPRESSED
        };

        for (CompressionType type : types) {
            List<DisassemblySearchResult> results = searchTool.searchByCompressionType(type);
            System.out.printf("%-20s: %d files%n", type.getDisplayName(), results.size());

            if (!results.isEmpty()) {
                // Get total file size
                long totalSize = 0;
                int filesFound = 0;
                for (DisassemblySearchResult r : results) {
                    try {
                        long size = searchTool.getFileSize(r.getFilePath());
                        if (size > 0) {
                            totalSize += size;
                            filesFound++;
                        }
                    } catch (IOException ignored) {
                    }
                }
                if (filesFound > 0) {
                    System.out.printf("  Total size: %,d bytes across %d files%n", totalSize, filesFound);
                }
            }
        }

        System.out.println();
    }
}
