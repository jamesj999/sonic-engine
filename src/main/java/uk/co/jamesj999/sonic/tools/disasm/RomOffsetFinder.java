package uk.co.jamesj999.sonic.tools.disasm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Main CLI tool for finding ROM offsets of items defined in the Sonic 2 disassembly.
 * 
 * This tool:
 * 1. Searches s2disasm for items by label or filename
 * 2. Tests decompression based on file extension
 * 3. Finds the ROM offset by matching decompressed data
 * 
 * Usage:
 *   java RomOffsetFinder search <pattern>           - Search for items by label/filename
 *   java RomOffsetFinder find <label> [startOffset] - Find ROM offset for a specific item
 *   java RomOffsetFinder test <offset> <type>       - Test decompression at offset
 *   java RomOffsetFinder list [type]                - List all includes (optionally filtered by type)
 */
public class RomOffsetFinder {

    private static final String DEFAULT_ROM_PATH = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
    private static final String DEFAULT_DISASM_PATH = "docs/s2disasm";

    private final DisassemblySearchTool searchTool;
    private final CompressionTestTool testTool;

    public RomOffsetFinder(String disasmPath, String romPath) throws IOException {
        this.searchTool = new DisassemblySearchTool(disasmPath);
        this.testTool = new CompressionTestTool(romPath);
    }

    /**
     * Search for items in the disassembly matching the pattern.
     */
    public List<DisassemblySearchResult> search(String pattern) throws IOException {
        return searchTool.search(pattern);
    }

    /**
     * Find the ROM offset for a specific item from the disassembly.
     * Returns the first match found.
     */
    public OffsetFinderResult findOffset(String labelPattern) throws IOException {
        return findOffset(labelPattern, 0, -1);
    }

    /**
     * Find the ROM offset for a specific item, searching from startOffset.
     */
    public OffsetFinderResult findOffset(String labelPattern, long startOffset, long endOffset) throws IOException {
        List<DisassemblySearchResult> searchResults = searchTool.search(labelPattern);

        if (searchResults.isEmpty()) {
            return OffsetFinderResult.notFound(labelPattern, "No matching items found in disassembly");
        }

        DisassemblySearchResult item = searchResults.get(0);

        if (searchResults.size() > 1) {
            System.out.println("Multiple matches found, using first: " + item.getLabel());
            for (DisassemblySearchResult r : searchResults) {
                System.out.println("  - " + r.getLabel() + ": " + r.getFilePath());
            }
        }

        byte[] referenceData;
        try {
            referenceData = searchTool.readFileBytes(item.getFilePath());
        } catch (IOException e) {
            return OffsetFinderResult.notFound(labelPattern,
                    "Could not read reference file: " + item.getFilePath() + " - " + e.getMessage());
        }

        CompressionType type = item.getCompressionType();
        if (type == CompressionType.UNKNOWN) {
            return OffsetFinderResult.notFound(labelPattern, "Unknown compression type for: " + item.getFilePath());
        }

        long searchEnd = endOffset > 0 ? endOffset : Long.MAX_VALUE;
        CompressionTestResult testResult = testTool.searchForMatch(type, referenceData, startOffset, searchEnd, 1);

        if (testResult.isSuccess()) {
            return OffsetFinderResult.found(item, testResult);
        } else {
            return OffsetFinderResult.notFound(labelPattern,
                    "Could not find matching ROM offset for " + item.getLabel());
        }
    }

    /**
     * Test decompression at a specific ROM offset.
     */
    public CompressionTestResult testAt(long offset, CompressionType type) throws IOException {
        return testTool.testDecompression(offset, type);
    }

    /**
     * Test decompression at an offset with auto-detection.
     */
    public CompressionTestResult testAutoDetect(long offset) throws IOException {
        return testTool.autoDetect(offset);
    }

    /**
     * List all includes of a specific compression type.
     */
    public List<DisassemblySearchResult> listByType(CompressionType type) throws IOException {
        return searchTool.searchByCompressionType(type);
    }

    /**
     * List all includes.
     */
    public List<DisassemblySearchResult> listAll() throws IOException {
        return searchTool.listAllIncludes();
    }

    public void close() {
        testTool.close();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String command = args[0].toLowerCase();
        String romPath = System.getProperty("rom.path", DEFAULT_ROM_PATH);
        String disasmPath = System.getProperty("disasm.path", DEFAULT_DISASM_PATH);

        try {
            RomOffsetFinder finder = new RomOffsetFinder(disasmPath, romPath);

            switch (command) {
                case "search":
                    if (args.length < 2) {
                        System.out.println("Usage: search <pattern>");
                        return;
                    }
                    handleSearch(finder, args[1]);
                    break;

                case "find":
                    if (args.length < 2) {
                        System.out.println("Usage: find <label> [startOffset]");
                        return;
                    }
                    long startOffset = args.length > 2 ? parseOffset(args[2]) : 0;
                    handleFind(finder, args[1], startOffset);
                    break;

                case "test":
                    if (args.length < 3) {
                        System.out.println("Usage: test <offset> <type>");
                        System.out.println("Types: nem, kos, eni, sax, auto");
                        return;
                    }
                    handleTest(finder, parseOffset(args[1]), args[2]);
                    break;

                case "list":
                    String typeFilter = args.length > 1 ? args[1] : null;
                    handleList(finder, typeFilter);
                    break;

                default:
                    printUsage();
            }

            finder.close();

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleSearch(RomOffsetFinder finder, String pattern) throws IOException {
        List<DisassemblySearchResult> results = finder.search(pattern);

        if (results.isEmpty()) {
            System.out.println("No results found for: " + pattern);
            return;
        }

        System.out.println("Found " + results.size() + " result(s):");
        System.out.println();

        for (DisassemblySearchResult result : results) {
            System.out.printf("Label:       %s%n", result.getLabel() != null ? result.getLabel() : "(none)");
            System.out.printf("File:        %s%n", result.getFilePath());
            System.out.printf("Compression: %s%n", result.getCompressionType().getDisplayName());
            System.out.printf("ASM Source:  %s:%d%n", result.getAsmFilePath(), result.getAsmLineNumber());

            try {
                long size = finder.searchTool.getFileSize(result.getFilePath());
                System.out.printf("File Size:   %d bytes%n", size);
            } catch (IOException e) {
                System.out.printf("File Size:   (not found)%n");
            }

            System.out.println();
        }
    }

    private static void handleFind(RomOffsetFinder finder, String pattern, long startOffset) throws IOException {
        System.out.println("Searching for: " + pattern);
        if (startOffset > 0) {
            System.out.printf("Starting from offset: 0x%X%n", startOffset);
        }
        System.out.println();

        OffsetFinderResult result = finder.findOffset(pattern, startOffset, -1);

        if (result.isFound()) {
            System.out.println("=== FOUND ===");
            System.out.printf("Label:            %s%n", result.getSearchResult().getLabel());
            System.out.printf("File:             %s%n", result.getSearchResult().getFilePath());
            System.out.printf("Compression:      %s%n", result.getTestResult().getCompressionType().getDisplayName());
            System.out.printf("ROM Offset:       0x%X%n", result.getTestResult().getRomOffset());
            System.out.printf("Compressed Size:  %d bytes%n", result.getTestResult().getCompressedSize());
            System.out.printf("Decompressed Size:%d bytes%n", result.getTestResult().getDecompressedSize());
        } else {
            System.out.println("=== NOT FOUND ===");
            System.out.println("Pattern: " + result.getSearchPattern());
            System.out.println("Reason:  " + result.getErrorMessage());
        }
    }

    private static void handleTest(RomOffsetFinder finder, long offset, String typeStr) throws IOException {
        CompressionTestResult result;

        if ("auto".equalsIgnoreCase(typeStr)) {
            result = finder.testAutoDetect(offset);
        } else {
            CompressionType type = parseCompressionType(typeStr);
            if (type == null) {
                System.out.println("Unknown compression type: " + typeStr);
                System.out.println("Valid types: nem, kos, eni, sax, auto");
                return;
            }
            result = finder.testAt(offset, type);
        }

        System.out.printf("Testing offset 0x%X with %s compression:%n", offset,
                result.getCompressionType().getDisplayName());
        System.out.println();

        if (result.isSuccess()) {
            System.out.println("=== SUCCESS ===");
            System.out.printf("Compressed Size:   %d bytes%n", result.getCompressedSize());
            System.out.printf("Decompressed Size: %d bytes%n", result.getDecompressedSize());
            System.out.printf("Compression Ratio: %.1f%%%n",
                    (1.0 - (double) result.getCompressedSize() / result.getDecompressedSize()) * 100);
        } else {
            System.out.println("=== FAILED ===");
            System.out.println("Error: " + result.getErrorMessage());
        }
    }

    private static void handleList(RomOffsetFinder finder, String typeFilter) throws IOException {
        List<DisassemblySearchResult> results;

        if (typeFilter != null) {
            CompressionType type = parseCompressionType(typeFilter);
            if (type == null) {
                System.out.println("Unknown compression type: " + typeFilter);
                return;
            }
            results = finder.listByType(type);
            System.out.println("Listing all " + type.getDisplayName() + " files:");
        } else {
            results = finder.listAll();
            System.out.println("Listing all binary includes:");
        }

        System.out.println();

        for (DisassemblySearchResult result : results) {
            System.out.printf("%-40s %-12s %s%n",
                    result.getLabel() != null ? result.getLabel() : "(no label)",
                    result.getCompressionType().getDisplayName(),
                    result.getFilePath());
        }

        System.out.println();
        System.out.println("Total: " + results.size() + " items");
    }

    private static long parseOffset(String str) {
        if (str.toLowerCase().startsWith("0x")) {
            return Long.parseLong(str.substring(2), 16);
        } else if (str.toLowerCase().startsWith("$")) {
            return Long.parseLong(str.substring(1), 16);
        } else {
            return Long.parseLong(str);
        }
    }

    private static CompressionType parseCompressionType(String str) {
        switch (str.toLowerCase()) {
            case "nem":
            case "nemesis":
                return CompressionType.NEMESIS;
            case "kos":
            case "kosinski":
                return CompressionType.KOSINSKI;
            case "kosm":
                return CompressionType.KOSINSKI_MODULED;
            case "eni":
            case "enigma":
                return CompressionType.ENIGMA;
            case "sax":
            case "saxman":
                return CompressionType.SAXMAN;
            case "bin":
            case "raw":
                return CompressionType.UNCOMPRESSED;
            default:
                return null;
        }
    }

    private static void printUsage() {
        System.out.println("RomOffsetFinder - Find ROM offsets for Sonic 2 disassembly items");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  search <pattern>              Search for items by label/filename");
        System.out.println("  find <label> [startOffset]    Find ROM offset for a specific item");
        System.out.println("  test <offset> <type>          Test decompression at offset");
        System.out.println("  list [type]                   List all includes (optionally by type)");
        System.out.println();
        System.out.println("Compression types: nem, kos, eni, sax, auto");
        System.out.println();
        System.out.println("System properties:");
        System.out.println("  -Drom.path=<path>            Path to ROM file");
        System.out.println("  -Ddisasm.path=<path>         Path to s2disasm directory");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  search ring                   Search for items containing 'ring'");
        System.out.println("  find Art_Ring                 Find ROM offset for Art_Ring");
        System.out.println("  test 0x41A4C nem              Test Nemesis decompression at 0x41A4C");
        System.out.println("  list nem                      List all Nemesis-compressed files");
    }

    /**
     * Result of an offset finding operation.
     */
    public static class OffsetFinderResult {
        private final boolean found;
        private final String searchPattern;
        private final DisassemblySearchResult searchResult;
        private final CompressionTestResult testResult;
        private final String errorMessage;

        private OffsetFinderResult(boolean found, String searchPattern,
                                    DisassemblySearchResult searchResult,
                                    CompressionTestResult testResult,
                                    String errorMessage) {
            this.found = found;
            this.searchPattern = searchPattern;
            this.searchResult = searchResult;
            this.testResult = testResult;
            this.errorMessage = errorMessage;
        }

        public static OffsetFinderResult found(DisassemblySearchResult searchResult,
                                                 CompressionTestResult testResult) {
            return new OffsetFinderResult(true, searchResult.getLabel(),
                    searchResult, testResult, null);
        }

        public static OffsetFinderResult notFound(String pattern, String errorMessage) {
            return new OffsetFinderResult(false, pattern, null, null, errorMessage);
        }

        public boolean isFound() {
            return found;
        }

        public String getSearchPattern() {
            return searchPattern;
        }

        public DisassemblySearchResult getSearchResult() {
            return searchResult;
        }

        public CompressionTestResult getTestResult() {
            return testResult;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
