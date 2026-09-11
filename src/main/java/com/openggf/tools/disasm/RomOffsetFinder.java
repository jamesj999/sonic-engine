package com.openggf.tools.disasm;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * Main CLI tool for finding ROM offsets of items defined in Sonic disassemblies.
 * Supports Sonic 1 (s1disasm), Sonic 2 (s2disasm), and Sonic 3&K (skdisasm).
 *
 * This tool:
 * 1. Searches disassembly for items by label or filename
 * 2. Tests decompression based on file extension
 * 3. Finds the ROM offset by matching decompressed data
 *
 * Usage:
 *   java RomOffsetFinder [--game s1|s2|s3k] search <pattern>
 *   java RomOffsetFinder [--game s1|s2|s3k] find <label> [startOffset]
 *   java RomOffsetFinder [--game s1|s2|s3k] test <offset> <type>
 *   java RomOffsetFinder [--game s1|s2|s3k] list [type]
 */
public class RomOffsetFinder {

    /**
     * Encapsulates all game-specific configuration for the offset finder.
     */
    public record GameProfile(
            String gameId,
            String gameName,
            String romVerName,
            String defaultRomPath,
            String defaultDisasmPath,
            String mainAsmFile,
            String paletteDirPrefix,
            Map<String, Long> anchorOffsets,
            LinkedHashMap<String, String> labelPrefixMap
    ) {
        /**
         * Create a Sonic 1 profile.
         */
        public static GameProfile sonic1() {
            Map<String, Long> anchors = new LinkedHashMap<>();
            // Verified anchors from Sonic1Constants
            anchors.put("Art_Text", 0x5F0L);          // artunc/menutext.bin
            anchors.put("Pal_Sonic", 0x2380L);        // palette/Sonic.bin
            anchors.put("Nem_TitleCard", 0x39204L);   // artnem/Title Cards.nem
            anchors.put("Col_GHZ", 0x64A00L);         // collide/GHZ.bin
            anchors.put("AngleMap", 0x62900L);         // collide/Angle Map.bin
            anchors.put("CollArray1", 0x62A00L);       // collide/Collision Array (Normal).bin

            LinkedHashMap<String, String> prefixes = new LinkedHashMap<>();
            // Longest-first to avoid partial matches
            prefixes.put("Blk256_", "BLK256_");
            prefixes.put("Blk16_", "BLK16_");
            prefixes.put("Nem_", "NEM_");
            prefixes.put("Art_", "ART_");
            prefixes.put("Pal_", "PAL_");
            prefixes.put("Map_", "MAP_");
            prefixes.put("Col_", "COL_");
            prefixes.put("Level_", "LEVEL_");
            prefixes.put("ObjPos_", "OBJPOS_");

            return new GameProfile(
                    "s1", "Sonic 1", "Sonic 1 (REV01)",
                    "s1.gen",
                    "docs/s1disasm",
                    "sonic.asm",
                    null, // S1 uses bincludePalette, not the palette macro
                    Collections.unmodifiableMap(anchors),
                    prefixes
            );
        }

        /**
         * Create a Sonic 2 profile (default).
         */
        public static GameProfile sonic2() {
            Map<String, Long> anchors = new LinkedHashMap<>();
            anchors.put("ArtNem_SpecialBack", 0x0DCD68L);
            anchors.put("ArtNem_SpecialHUD", 0x0DD48AL);
            anchors.put("ArtNem_SpecialStart", 0x0DD790L);
            anchors.put("ArtNem_SpecialRings", 0x0DDA7EL);
            anchors.put("ArtNem_SpecialFlatShadow", 0x0DDFA4L);
            anchors.put("ArtNem_SpecialDiagShadow", 0x0DE05AL);
            anchors.put("ArtNem_SpecialSideShadow", 0x0DE120L);
            anchors.put("ArtNem_SpecialBomb", 0x0DE4BCL);
            anchors.put("ArtNem_SpecialEmerald", 0x0DE8ACL);
            anchors.put("ArtNem_SpecialMessages", 0x0DEAF4L);
            anchors.put("ArtNem_SpecialSonicAndTails", 0x0DEEAEL);
            anchors.put("ArtKos_SpecialStage", 0x0DCA38L);
            anchors.put("Pal_Result", 0x3302L);

            LinkedHashMap<String, String> prefixes = new LinkedHashMap<>();
            prefixes.put("ArtNem_", "NEM_");
            prefixes.put("ArtKos_", "KOS_");
            prefixes.put("ArtEni_", "ENI_");
            prefixes.put("ArtSax_", "SAX_");
            prefixes.put("MapEni_", "MAP_ENI_");
            prefixes.put("MapUnc_", "MAP_UNC_");
            prefixes.put("Pal_", "PAL_");

            return new GameProfile(
                    "s2", "Sonic 2", "Sonic 2 (REV01)",
                    "s2.gen",
                    "docs/s2disasm",
                    "s2.asm",
                    "art/palettes/", // S2 uses the palette macro
                    Collections.unmodifiableMap(anchors),
                    prefixes
            );
        }

        /**
         * Create a Sonic 3&K profile.
         */
        public static GameProfile sonic3k() {
            Map<String, Long> anchors = new LinkedHashMap<>();
            // Initially empty — populate via verify-batch runs against actual ROM

            LinkedHashMap<String, String> prefixes = new LinkedHashMap<>();
            // Longest-first to avoid partial matches
            prefixes.put("ArtKosM_", "KOSM_");
            prefixes.put("ArtNem_", "NEM_");
            prefixes.put("ArtKos_", "KOS_");
            prefixes.put("AnPal_", "ANPAL_");
            prefixes.put("Pal_", "PAL_");

            return new GameProfile(
                    "s3k", "Sonic 3 & Knuckles", "Sonic 3 & Knuckles",
                    "s3k.gen",
                    "docs/skdisasm",
                    "sonic3k.asm",
                    null, // S3K does NOT use palette macro
                    Collections.unmodifiableMap(anchors),
                    prefixes
            );
        }

        /**
         * Auto-detect game from disassembly path.
         */
        public static GameProfile detect(String disasmPath) {
            if (disasmPath != null) {
                if (disasmPath.contains("s1disasm")) return sonic1();
                if (disasmPath.contains("skdisasm")) return sonic3k();
            }
            return sonic2();
        }
    }

    private final DisassemblySearchTool searchTool;
    private final CompressionTestTool testTool;
    private final RomOffsetCalculator offsetCalculator;
    private final String disasmPath;
    private final GameProfile profile;

    public RomOffsetFinder(String disasmPath, String romPath) throws IOException {
        this(disasmPath, romPath, GameProfile.detect(disasmPath));
    }

    public RomOffsetFinder(String disasmPath, String romPath, GameProfile profile) throws IOException {
        this.disasmPath = disasmPath;
        this.profile = profile;
        this.searchTool = new DisassemblySearchTool(disasmPath, profile);
        this.testTool = new CompressionTestTool(romPath);
        this.offsetCalculator = new RomOffsetCalculator(disasmPath, profile);
    }

    public GameProfile getProfile() {
        return profile;
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
        for (DisassemblySearchResult r : searchResults) {
            if (labelPattern.equalsIgnoreCase(r.getLabel()) && r.hasFile()) {
                item = r;
                break;
            }
            if (labelPattern.equalsIgnoreCase(r.getLabel())) {
                item = r;
            }
        }

        if (searchResults.size() > 1) {
            System.out.println("Multiple matches found, using first: " + item.getLabel());
            for (DisassemblySearchResult r : searchResults) {
                System.out.println("  - " + r.getLabel() + ": " + (r.getFilePath() != null ? r.getFilePath() : "(label-only)"));
            }
        }

        if (item.getCompressionType() == CompressionType.ASSEMBLY_DATA) {
            long calculatedOffset = offsetCalculator.calculateOffset(item.getLabel());
            if (calculatedOffset >= 0) {
                return OffsetFinderResult.found(item, CompressionTestResult.success(
                        CompressionType.ASSEMBLY_DATA, calculatedOffset, 0, 0, null));
            }
            return OffsetFinderResult.notFound(labelPattern,
                    "Could not calculate assembly label offset: " + item.getLabel());
        }

        // Label-only results (Offs_*, PLC_*) have no binary data to search for
        if (!item.hasFile()) {
            return OffsetFinderResult.notFound(labelPattern,
                    "Label-only result (no binary data): " + item.getLabel());
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
        long rawOffset = testTool.findRawMatch(referenceData, startOffset, searchEnd);
        if (rawOffset >= 0) {
            CompressionTestResult testResult;
            if (type == CompressionType.UNCOMPRESSED) {
                testResult = CompressionTestResult.success(type, rawOffset,
                        referenceData.length, referenceData.length, referenceData);
            } else {
                CompressionTestResult decompressed = testTool.testDecompression(rawOffset, type);
                if (decompressed.isSuccess()) {
                    testResult = decompressed;
                } else {
                    testResult = CompressionTestResult.success(type, rawOffset,
                            referenceData.length, 0, null);
                }
            }
            return OffsetFinderResult.found(item, testResult);
        }

        CompressionTestResult testResult = testTool.searchForMatch(type, referenceData, startOffset, searchEnd, 1);
        if (testResult.isSuccess()) {
            return OffsetFinderResult.found(item, testResult);
        }

        return OffsetFinderResult.notFound(labelPattern,
                "Could not find matching ROM offset for " + item.getLabel());
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

    /**
     * Verify that a calculated ROM offset matches actual ROM data.
     * Compares the raw ROM bytes with the reference file from disassembly
     * (which contains the compressed data).
     *
     * @param labelPattern Label to verify
     * @return VerificationResult with status and details
     */
    public VerificationResult verify(String labelPattern) throws IOException {
        // 1. Search for the label
        List<DisassemblySearchResult> results = searchTool.search(labelPattern);
        if (results.isEmpty()) {
            return VerificationResult.notFound(labelPattern, -1, "Label not found in disassembly");
        }

        // Prefer exact match over partial match
        DisassemblySearchResult item = results.get(0);
        for (DisassemblySearchResult r : results) {
            if (labelPattern.equalsIgnoreCase(r.getLabel()) && r.hasFile()) {
                item = r;
                break;
            }
            if (labelPattern.equalsIgnoreCase(r.getLabel())) {
                item = r;
            }
        }
        String label = item.getLabel();
        if (label == null) {
            return VerificationResult.error(labelPattern, "Item has no label");
        }

        if (item.getCompressionType() == CompressionType.ASSEMBLY_DATA) {
            long calculatedOffset = offsetCalculator.calculateOffset(label);
            if (calculatedOffset < 0) {
                return VerificationResult.notFound(label, -1,
                        "Could not calculate assembly label offset (no anchor nearby)");
            }
            return VerificationResult.verified(label, calculatedOffset, CompressionType.ASSEMBLY_DATA, 0);
        }

        // Label-only results (Offs_*, PLC_*) have no binary data to verify
        if (!item.hasFile()) {
            return VerificationResult.error(label, "Label-only result (no binary data to verify)");
        }

        // 2. Calculate offset using the calculator
        long calculatedOffset = offsetCalculator.calculateOffset(label);
        if (calculatedOffset < 0) {
            return VerificationResult.notFound(label, -1, "Could not calculate offset (no anchor nearby)");
        }

        // 3. Read reference file (this is the compressed/raw data from disassembly)
        byte[] referenceData;
        try {
            referenceData = searchTool.readFileBytes(item.getFilePath());
        } catch (IOException e) {
            return VerificationResult.error(label, "Could not read reference file: " + e.getMessage());
        }

        CompressionType type = item.getCompressionType();

        // 4. Verify by comparing the raw bytes at the calculated offset
        // The reference file contains the exact bytes that should appear in the ROM
        byte[] romBytes = testTool.readRomBytes(calculatedOffset, referenceData.length);
        if (romBytes == null) {
            return VerificationResult.error(label, "Could not read ROM at offset 0x" +
                    Long.toHexString(calculatedOffset));
        }

        if (java.util.Arrays.equals(romBytes, referenceData)) {
            // Direct byte comparison matches - verified!
            VerificationResult result = VerificationResult.verified(label, calculatedOffset, type, referenceData.length);
            offsetCalculator.addVerifiedAnchor(label, calculatedOffset);
            return result;
        }

        // 5. Bytes don't match - search nearby for the actual location
        long searchStart = Math.max(0, calculatedOffset - 0x1000);
        long searchEnd = calculatedOffset + 0x1000;

        for (long offset = searchStart; offset < searchEnd; offset++) {
            byte[] testBytes = testTool.readRomBytes(offset, referenceData.length);
            if (testBytes != null && java.util.Arrays.equals(testBytes, referenceData)) {
                VerificationResult result = VerificationResult.mismatch(label, calculatedOffset, offset, type,
                        referenceData.length);
                offsetCalculator.addVerifiedAnchor(label, offset);
                return result;
            }
        }

        // Full ROM search for small assets to improve accuracy
        if (referenceData.length <= 0x4000) {
            long fullScanOffset = testTool.findRawMatch(referenceData, 0, Long.MAX_VALUE);
            if (fullScanOffset >= 0) {
                VerificationResult result = VerificationResult.mismatch(label, calculatedOffset, fullScanOffset, type,
                        referenceData.length);
                offsetCalculator.addVerifiedAnchor(label, fullScanOffset);
                return result;
            }
        }

        // Still not found
        return VerificationResult.notFound(label, calculatedOffset,
                "Data at calculated offset does not match reference file");
    }

    /**
     * Batch verify all items of a given compression type.
     * Verified offsets are automatically added as runtime anchors.
     *
     * @param type Compression type to verify, or null for all
     * @return List of VerificationResults
     */
    public List<VerificationResult> verifyBatch(CompressionType type) throws IOException {
        List<DisassemblySearchResult> items;
        if (type != null) {
            items = searchTool.searchByCompressionType(type);
        } else {
            items = searchTool.listAllIncludes();
        }

        List<VerificationResult> results = new ArrayList<>();
        for (DisassemblySearchResult item : items) {
            if (item.getLabel() == null) {
                continue; // Skip items without labels
            }

            try {
                VerificationResult result = verify(item.getLabel());
                results.add(result);

                // Add verified offsets as runtime anchors for better accuracy
                if (result.isVerified()) {
                    offsetCalculator.addVerifiedAnchor(result.getLabel(), result.getCalculatedOffset());
                } else if (result.getStatus() == VerificationResult.Status.MISMATCH) {
                    offsetCalculator.addVerifiedAnchor(result.getLabel(), result.getVerifiedOffset());
                }
            } catch (Exception e) {
                results.add(VerificationResult.error(item.getLabel(), e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Get the offset calculator (for adding runtime anchors).
     */
    public RomOffsetCalculator getOffsetCalculator() {
        return offsetCalculator;
    }

    public void close() {
        testTool.close();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        // Parse --game flag and strip it from args
        GameProfile profile = null;
        List<String> filteredArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--game".equals(args[i]) && i + 1 < args.length) {
                String gameId = args[++i].toLowerCase();
                profile = switch (gameId) {
                    case "s1", "sonic1" -> GameProfile.sonic1();
                    case "s2", "sonic2" -> GameProfile.sonic2();
                    case "s3k", "sonic3k", "s3" -> GameProfile.sonic3k();
                    default -> {
                        System.out.println("Unknown game: " + gameId + ". Use s1, s2, or s3k.");
                        yield null;
                    }
                };
                if (profile == null) return;
            } else {
                filteredArgs.add(args[i]);
            }
        }
        args = filteredArgs.toArray(new String[0]);

        if (args.length < 1) {
            printUsage();
            return;
        }

        // Check system property as fallback
        if (profile == null) {
            String gameProp = System.getProperty("game");
            if ("s1".equalsIgnoreCase(gameProp) || "sonic1".equalsIgnoreCase(gameProp)) {
                profile = GameProfile.sonic1();
            } else if ("s3k".equalsIgnoreCase(gameProp) || "sonic3k".equalsIgnoreCase(gameProp) || "s3".equalsIgnoreCase(gameProp)) {
                profile = GameProfile.sonic3k();
            }
        }

        String command = args[0].toLowerCase();
        String disasmPath = System.getProperty("disasm.path",
                profile != null ? profile.defaultDisasmPath() : null);

        // Auto-detect from disasm path if profile not set
        if (profile == null) {
            profile = disasmPath != null ? GameProfile.detect(disasmPath) : GameProfile.sonic2();
        }
        if (disasmPath == null) {
            disasmPath = profile.defaultDisasmPath();
        }

        String romPath = System.getProperty("rom.path", profile.defaultRomPath());

        try {
            RomOffsetFinder finder = new RomOffsetFinder(disasmPath, romPath, profile);

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

                case "verify":
                    if (args.length < 2) {
                        System.out.println("Usage: verify <label>");
                        return;
                    }
                    handleVerify(finder, args[1]);
                    break;

                case "verify-batch":
                    String batchTypeFilter = args.length > 1 ? args[1] : null;
                    handleVerifyBatch(finder, batchTypeFilter);
                    break;

                case "export":
                    if (args.length < 2) {
                        System.out.println("Usage: export <type> [prefix]");
                        System.out.println("  Exports verified offsets as Java constants");
                        return;
                    }
                    String exportPrefix = args.length > 2 ? args[2] : "";
                    handleExport(finder, args[1], exportPrefix);
                    break;

                case "plc":
                    if (args.length < 2) {
                        System.out.println("Usage: plc <name>    Show contents of a PLC definition");
                        return;
                    }
                    handlePlc(finder, args[1]);
                    break;

                case "search-rom":
                    handleSearchRom(finder, args);
                    break;

                case "verify-audio":
                    handleVerifyAudio(finder);
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

    // -----------------------------------------------------------------------
    // verify-audio: Find and verify Sonic 1 audio ROM addresses
    // -----------------------------------------------------------------------

    /**
     * Result of Sonic 1 audio address verification.
     */
    public static class AudioAddressResult {
        public final String name;
        public final long foundAddr;
        public final long expectedAddr;
        public final boolean verified;
        public final String note;

        public AudioAddressResult(String name, long foundAddr, long expectedAddr, String note) {
            this.name = name;
            this.foundAddr = foundAddr;
            this.expectedAddr = expectedAddr;
            this.verified = foundAddr >= 0 && foundAddr == expectedAddr;
            this.note = note;
        }
    }

    /**
     * Verify all Sonic 1 audio ROM addresses by searching for known byte patterns.
     * Uses three independent anchors (SpeedUpIndex, SoundPriorities, PSG1 envelope)
     * and cross-validates via the Go_ pointer table.
     *
     * @return list of results for each audio address
     */
    public List<AudioAddressResult> verifyAudioAddresses() throws IOException {
        List<AudioAddressResult> results = new ArrayList<>();

        // --- 1. Find SpeedUpIndex via its known byte pattern ---
        // From s1disasm: dc.b 7, $72, $73, $26, $15, 8, $FF, 5
        byte[] speedUpPattern = {0x07, 0x72, 0x73, 0x26, 0x15, 0x08, (byte) 0xFF, 0x05};
        long speedUpAddr = testTool.findRawMatch(speedUpPattern, 0x60000, Long.MAX_VALUE);
        results.add(new AudioAddressResult("SpeedUpIndex", speedUpAddr, 0x071A94,
                speedUpAddr >= 0 ? "pattern: {07,72,73,26,15,08,FF,05}" : "PATTERN NOT FOUND"));

        // --- 2. Derive MusicIndex = SpeedUpIndex + 8 ---
        long musicIndexAddr = speedUpAddr >= 0 ? speedUpAddr + 8 : -1;
        results.add(new AudioAddressResult("MusicIndex", musicIndexAddr, 0x071A9C,
                speedUpAddr >= 0 ? "SpeedUpIndex + 8" : "depends on SpeedUpIndex"));

        // --- 3. Find SoundPriorities via its known pattern ---
        // First 31 bytes are 0x90, byte 32 is 0x80
        // From s1disasm: 15 × 0x90 (music 0x81-0x8F) + 16 × 0x90 (0x90-0x9F) = 31 × 0x90, then 0x80
        byte[] priorityPattern = new byte[32];
        for (int i = 0; i < 31; i++) priorityPattern[i] = (byte) 0x90;
        priorityPattern[31] = (byte) 0x80;
        long soundPrioritiesAddr = testTool.findRawMatch(priorityPattern, 0x60000, Long.MAX_VALUE);
        results.add(new AudioAddressResult("SoundPriorities", soundPrioritiesAddr, 0x071AE8,
                soundPrioritiesAddr >= 0 ? "pattern: 31×0x90 + 0x80" : "PATTERN NOT FOUND"));

        // --- 4. Derive SoundIndex = SoundPriorities + 100 ---
        // SoundPriorities is 100 bytes (0x81-0xE4 = 100 entries)
        long soundIndexAddr = soundPrioritiesAddr >= 0 ? soundPrioritiesAddr + 100 : -1;
        results.add(new AudioAddressResult("SoundIndex", soundIndexAddr, 0x078B44,
                soundPrioritiesAddr >= 0 ? "SoundPriorities + 100" : "depends on SoundPriorities"));

        // --- 5. Derive SpecSoundIndex = SoundIndex + 48*4 ---
        // SoundIndex has 48 entries × 4 bytes = 192 bytes
        long specSoundIndexAddr = soundIndexAddr >= 0 ? soundIndexAddr + 192 : -1;
        results.add(new AudioAddressResult("SpecSoundIndex", specSoundIndexAddr, 0x078C04,
                soundIndexAddr >= 0 ? "SoundIndex + 192" : "depends on SoundIndex"));

        // --- 6. Find PSG1 envelope data ---
        // PSG1: dc.b 0,0,0,1,1,1,2,2,2,3,3,3,4,4,4,5,5,5,6,6,6,7,$80
        byte[] psg1Pattern = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, (byte) 0x80};
        long psg1Addr = testTool.findRawMatch(psg1Pattern, 0x60000, Long.MAX_VALUE);

        // PSG_Index = PSG1 - 36 (9 longwords × 4 bytes before PSG1)
        long psgIndexAddr = psg1Addr >= 0 ? psg1Addr - 36 : -1;
        results.add(new AudioAddressResult("PSG_Index", psgIndexAddr, 0x0719A8,
                psg1Addr >= 0 ? "PSG1_addr(0x" + Long.toHexString(psg1Addr) + ") - 36" : "PSG1 PATTERN NOT FOUND"));

        // --- 7. Derive Go_ section = PSG_Index - 24 ---
        // Go_ section is 6 longwords (24 bytes) immediately before PSG_Index
        long goSectionAddr = psgIndexAddr >= 0 ? psgIndexAddr - 24 : -1;
        results.add(new AudioAddressResult("Go_Section", goSectionAddr, -1,
                psgIndexAddr >= 0 ? "PSG_Index - 24" : "depends on PSG_Index"));

        // --- 8. Find DAC_sample_rate ---
        // The timpani pitch values are computed at assembly time. Search for the
        // distinctive pattern near the end: two 0xFF bytes followed by 0x00 (even padding)
        // The table is 6 bytes: {computed, computed, computed, computed, 0xFF, 0xFF}
        // Search relative to SpeedUpIndex area since it's in the same region
        long dacSampleRateAddr = -1;
        if (speedUpAddr >= 0) {
            // DAC_sample_rate is at label byte_71CC4 in disasm, which is after
            // the main sound driver code. Search for it relative to music area.
            // It's about 0x230 bytes after SpeedUpIndex in the default assembly.
            // Search a wider range in the music data section.
            // Pattern: 4 bytes (computed timpani rates) + FF FF + 00 (even) or next instruction
            // The rates are djnz counters: typically small values like 0x12, 0x15, 0x1C, 0x1A
            // Look for bytes where b[4]=0xFF, b[5]=0xFF in the sound driver area
            for (long scan = speedUpAddr + 0x100; scan < speedUpAddr + 0x400; scan++) {
                byte[] candidate = testTool.readRomBytes(scan, 6);
                if (candidate != null
                        && candidate[4] == (byte) 0xFF && candidate[5] == (byte) 0xFF
                        && (candidate[0] & 0xFF) > 0x05 && (candidate[0] & 0xFF) < 0x40
                        && (candidate[1] & 0xFF) > 0x05 && (candidate[1] & 0xFF) < 0x40
                        && (candidate[2] & 0xFF) > 0x05 && (candidate[2] & 0xFF) < 0x40
                        && (candidate[3] & 0xFF) > 0x05 && (candidate[3] & 0xFF) < 0x40) {
                    dacSampleRateAddr = scan;
                    break;
                }
            }
        }
        results.add(new AudioAddressResult("DAC_sample_rate", dacSampleRateAddr, 0x071CC4,
                dacSampleRateAddr >= 0 ? "4 timpani rates + FF FF near SpeedUpIndex" : "NOT FOUND"));

        // --- 9. Cross-validate via Go_ pointer table ---
        // The Go_ pointer table is authoritative: SoundIndex and SpecSoundIndex are NOT
        // adjacent to SoundPriorities in the compiled ROM, so derivation is unreliable.
        // Use Go_ values to override derived addresses.
        if (goSectionAddr >= 0) {
            byte[] goBytes = testTool.readRomBytes(goSectionAddr, 24);
            if (goBytes != null) {
                // Read 6 big-endian longwords
                long goSoundPriorities = readBE32(goBytes, 0);
                long goSpecSoundIndex = readBE32(goBytes, 4);
                long goMusicIndex = readBE32(goBytes, 8);
                long goSoundIndex = readBE32(goBytes, 12);
                long goSpeedUpIndex = readBE32(goBytes, 16);
                long goPsgIndex = readBE32(goBytes, 20);

                // Override derived SoundIndex/SpecSoundIndex with Go_ values
                // (derivation assumed adjacency which doesn't hold in compiled ROM)
                if (goSoundIndex > 0 && goSoundIndex < 0x100000) {
                    soundIndexAddr = goSoundIndex;
                    // Update the existing result entry
                    results.set(3, new AudioAddressResult("SoundIndex", goSoundIndex, 0x078B44,
                            "Go_ pointer table (authoritative)"));
                }
                if (goSpecSoundIndex > 0 && goSpecSoundIndex < 0x100000) {
                    specSoundIndexAddr = goSpecSoundIndex;
                    results.set(4, new AudioAddressResult("SpecSoundIndex", goSpecSoundIndex, 0x078C04,
                            "Go_ pointer table (authoritative)"));
                }

                results.add(new AudioAddressResult("Go_→SoundPriorities", goSoundPriorities,
                        soundPrioritiesAddr >= 0 ? soundPrioritiesAddr : -1,
                        crossValidateNote(goSoundPriorities, soundPrioritiesAddr, "SoundPriorities")));
                results.add(new AudioAddressResult("Go_→SpecSoundIndex", goSpecSoundIndex,
                        specSoundIndexAddr >= 0 ? specSoundIndexAddr : -1,
                        crossValidateNote(goSpecSoundIndex, specSoundIndexAddr, "SpecSoundIndex")));
                results.add(new AudioAddressResult("Go_→MusicIndex", goMusicIndex,
                        musicIndexAddr >= 0 ? musicIndexAddr : -1,
                        crossValidateNote(goMusicIndex, musicIndexAddr, "MusicIndex")));
                results.add(new AudioAddressResult("Go_→SoundIndex", goSoundIndex,
                        soundIndexAddr >= 0 ? soundIndexAddr : -1,
                        crossValidateNote(goSoundIndex, soundIndexAddr, "SoundIndex")));
                results.add(new AudioAddressResult("Go_→SpeedUpIndex", goSpeedUpIndex,
                        speedUpAddr >= 0 ? speedUpAddr : -1,
                        crossValidateNote(goSpeedUpIndex, speedUpAddr, "SpeedUpIndex")));
                results.add(new AudioAddressResult("Go_→PSG_Index", goPsgIndex,
                        psgIndexAddr >= 0 ? psgIndexAddr : -1,
                        crossValidateNote(goPsgIndex, psgIndexAddr, "PSG_Index")));
            }
        }

        // --- 10. Verify first MusicIndex entry points to valid SMPS data ---
        if (musicIndexAddr >= 0) {
            byte[] firstEntry = testTool.readRomBytes(musicIndexAddr, 4);
            if (firstEntry != null) {
                long musicPtr = readBE32(firstEntry, 0);
                String ptrNote = String.format("first ptr → 0x%X", musicPtr);
                if (musicPtr > 0 && musicPtr < 0x100000) {
                    // Read first few bytes of the music data to check if it looks like SMPS
                    byte[] musicHeader = testTool.readRomBytes(musicPtr, 6);
                    if (musicHeader != null) {
                        // SMPS header starts with voice pointer (word), then channels
                        ptrNote += String.format(" (header: %02X %02X %02X %02X %02X %02X)",
                                musicHeader[0] & 0xFF, musicHeader[1] & 0xFF,
                                musicHeader[2] & 0xFF, musicHeader[3] & 0xFF,
                                musicHeader[4] & 0xFF, musicHeader[5] & 0xFF);
                    }
                } else {
                    ptrNote += " (OUT OF RANGE - likely wrong address!)";
                }
                results.add(new AudioAddressResult("MusicIndex[0]→Music81", musicPtr, -1, ptrNote));
            }
        }

        // --- 11. Verify first SoundIndex entry (use Go_-corrected address) ---
        if (soundIndexAddr >= 0) {
            byte[] firstSfx = testTool.readRomBytes(soundIndexAddr, 4);
            if (firstSfx != null) {
                long sfxPtr = readBE32(firstSfx, 0);
                String ptrNote = String.format("first ptr → 0x%X", sfxPtr);
                if (sfxPtr > 0 && sfxPtr < 0x100000) {
                    ptrNote += " (valid ROM range)";
                } else {
                    ptrNote += " (OUT OF RANGE - likely wrong address!)";
                }
                results.add(new AudioAddressResult("SoundIndex[0]→SndA0", sfxPtr, -1, ptrNote));
            }
        }

        // --- 12. Find DAC driver via 'lea (DACDriver).l,a0; lea (z80_ram).l,a1' pattern ---
        // The DACDriverLoad routine uses: 41F9 xxxx xxxx 43F9 00A0 0000
        // Search for the z80_ram lea (43F9 00A0 0000) and check preceding bytes for the DAC address
        long dacDriverAddr = -1;
        byte[] z80RamLea = {0x43, (byte) 0xF9, 0x00, (byte) 0xA0, 0x00, 0x00};
        long z80LeaPos = testTool.findRawMatch(z80RamLea, 0, Long.MAX_VALUE);
        while (z80LeaPos >= 6) {
            byte[] preceding = testTool.readRomBytes(z80LeaPos - 6, 6);
            if (preceding != null && (preceding[0] & 0xFF) == 0x41 && (preceding[1] & 0xFF) == 0xF9) {
                dacDriverAddr = readBE32(preceding, 2);
                break;
            }
            // Search for next occurrence
            z80LeaPos = testTool.findRawMatch(z80RamLea, z80LeaPos + 1, Long.MAX_VALUE);
        }
        String dacNote;
        if (dacDriverAddr >= 0) {
            // Verify it's valid Kosinski data
            try {
                CompressionTestResult kos = testTool.testDecompression(dacDriverAddr, CompressionType.KOSINSKI);
                dacNote = kos.isSuccess()
                        ? String.format("lea instruction → Kosinski, decompresses to %d bytes", kos.getDecompressedSize())
                        : "lea instruction found, but Kosinski decompression failed";
            } catch (Exception e) {
                dacNote = "lea instruction found, Kosinski test error";
            }
        } else {
            dacNote = "DACDriverLoad lea pattern not found";
        }
        results.add(new AudioAddressResult("DAC_Driver", dacDriverAddr, 0x072E7C, dacNote));

        return results;
    }

    private static long readBE32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    private static String crossValidateNote(long goValue, long derivedValue, String name) {
        if (derivedValue < 0) {
            return String.format("Go_ says 0x%X (derived %s unknown)", goValue, name);
        }
        if (goValue == derivedValue) {
            return String.format("MATCH - Go_ confirms 0x%X", goValue);
        }
        return String.format("MISMATCH - Go_ says 0x%X, derived says 0x%X", goValue, derivedValue);
    }

    private static void handleVerifyAudio(RomOffsetFinder finder) throws IOException {
        if (!"s1".equals(finder.profile.gameId())) {
            System.out.println("verify-audio is currently only supported for Sonic 1 (--game s1)");
            return;
        }

        System.out.println("=== SONIC 1 AUDIO ADDRESS VERIFICATION ===");
        System.out.println();

        List<AudioAddressResult> results = finder.verifyAudioAddresses();

        // Print primary address results
        System.out.println("--- Primary Addresses (pattern-matched) ---");
        for (AudioAddressResult r : results) {
            if (r.name.startsWith("Go_→") || r.name.contains("[0]")) continue;

            String status;
            if (r.foundAddr < 0) {
                status = "[FAIL]";
            } else if (r.expectedAddr < 0) {
                status = "[ -- ]";
            } else if (r.verified) {
                status = "[ OK ]";
            } else {
                status = "[DIFF]";
            }

            if (r.foundAddr >= 0) {
                System.out.printf("%s %-20s  0x%05X", status, r.name, r.foundAddr);
                if (r.expectedAddr >= 0 && !r.verified) {
                    System.out.printf("  (expected 0x%05X, diff %+d)", r.expectedAddr,
                            r.foundAddr - r.expectedAddr);
                }
            } else {
                System.out.printf("%s %-20s  NOT FOUND", status, r.name);
                if (r.expectedAddr >= 0) {
                    System.out.printf("  (expected 0x%05X)", r.expectedAddr);
                }
            }
            System.out.printf("  [%s]%n", r.note);
        }

        // Print Go_ cross-validation
        System.out.println();
        System.out.println("--- Go_ Pointer Cross-Validation ---");
        for (AudioAddressResult r : results) {
            if (!r.name.startsWith("Go_→")) continue;
            String status = r.note.contains("MATCH -") && !r.note.contains("MISMATCH") ? "[ OK ]" : "[WARN]";
            System.out.printf("%s %-25s  %s%n", status, r.name, r.note);
        }

        // Print pointer validation
        System.out.println();
        System.out.println("--- Pointer Table Validation ---");
        for (AudioAddressResult r : results) {
            if (!r.name.contains("[0]")) continue;
            System.out.printf("      %-25s  %s%n", r.name, r.note);
        }

        // Print summary with recommended constants
        System.out.println();
        System.out.println("--- Recommended Sonic1SmpsConstants Values ---");
        for (AudioAddressResult r : results) {
            if (r.name.startsWith("Go_→") || r.name.contains("[0]") || r.name.equals("Go_Section")) continue;
            if (r.foundAddr < 0) continue;

            String constName = switch (r.name) {
                case "SpeedUpIndex" -> "SPEED_UP_INDEX_ADDR";
                case "MusicIndex" -> "MUSIC_PTR_TABLE_ADDR";
                case "SoundPriorities" -> "SOUND_PRIORITIES_ADDR";
                case "SoundIndex" -> "SFX_PTR_TABLE_ADDR";
                case "SpecSoundIndex" -> "SPECIAL_SFX_PTR_TABLE_ADDR";
                case "PSG_Index" -> "PSG_ENV_PTR_TABLE_ADDR";
                case "DAC_sample_rate" -> "DAC_SAMPLE_RATE_TABLE_ADDR";
                case "DAC_Driver" -> "DAC_DRIVER_ADDR";
                default -> null;
            };
            if (constName != null) {
                System.out.printf("  public static final int %-30s = 0x%05X;%n", constName, r.foundAddr);
            }
        }
    }

    // -----------------------------------------------------------------------
    // plc: Show contents of PLC definitions
    // -----------------------------------------------------------------------

    private static void handlePlc(RomOffsetFinder finder, String pattern) throws IOException {
        Map<String, List<String>> forwardIndex = finder.searchTool.getPlcArtForwardIndex();

        String lowerPattern = pattern.toLowerCase();
        List<Map.Entry<String, List<String>>> matches = forwardIndex.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(lowerPattern))
                .toList();

        if (matches.isEmpty()) {
            System.out.println("No PLCs found matching: " + pattern);
            return;
        }

        for (Map.Entry<String, List<String>> entry : matches) {
            System.out.printf("PLC: %s (%d entries)%n", entry.getKey(), entry.getValue().size());
            for (String artLabel : entry.getValue()) {
                System.out.printf("  %s%n", artLabel);
            }
            System.out.println();
        }
    }

    // -----------------------------------------------------------------------
    // search-rom: Search ROM binary for hex byte patterns
    // -----------------------------------------------------------------------

    private static void handleSearchRom(RomOffsetFinder finder, String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: search-rom <hex-bytes> [startOffset] [endOffset]");
            System.out.println("  hex-bytes: hex string, e.g. \"0002 5CC0 090C\" or \"00025CC0\"");
            return;
        }

        byte[] pattern;
        try {
            pattern = parseHexString(args[1]);
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid hex string: " + e.getMessage());
            return;
        }

        if (pattern.length == 0) {
            System.out.println("Empty pattern.");
            return;
        }

        long startOffset = args.length > 2 ? parseOffset(args[2]) : 0;
        long endOffset = args.length > 3 ? parseOffset(args[3]) : Long.MAX_VALUE;

        // Print header
        StringBuilder patternHex = new StringBuilder();
        for (int i = 0; i < pattern.length; i++) {
            if (i > 0) patternHex.append(' ');
            patternHex.append(String.format("%02X", pattern[i] & 0xFF));
        }
        System.out.printf("Searching ROM for pattern: %s (%d bytes)%n", patternHex, pattern.length);
        if (startOffset > 0 || endOffset < Long.MAX_VALUE) {
            System.out.printf("Range: 0x%06X - 0x%06X%n", startOffset,
                    endOffset < Long.MAX_VALUE ? endOffset : 0);
        }
        System.out.println();

        List<Long> matches = finder.testTool.findAllRawMatches(pattern, startOffset, endOffset);

        if (matches.isEmpty()) {
            System.out.println("No matches found.");
            return;
        }

        System.out.printf("Found %d match(es):%n%n", matches.size());

        int contextSize = 16;
        for (int m = 0; m < matches.size(); m++) {
            long offset = matches.get(m);
            System.out.printf("Match %d at 0x%06X:%n", m + 1, offset);

            // Context line: bytes before | MATCH | bytes after
            long contextStart = Math.max(0, offset - contextSize);
            int beforeLen = (int) (offset - contextStart);
            byte[] beforeBytes = beforeLen > 0 ? finder.testTool.readRomBytes(contextStart, beforeLen) : new byte[0];
            byte[] afterBytes = finder.testTool.readRomBytes(offset + pattern.length, contextSize);

            StringBuilder ctxLine = new StringBuilder("  Context: ");
            if (beforeBytes != null && beforeBytes.length > 0) {
                ctxLine.append("...");
                for (byte b : beforeBytes) ctxLine.append(String.format(" %02X", b & 0xFF));
                ctxLine.append(' ');
            }
            ctxLine.append('|');
            for (byte b : pattern) ctxLine.append(String.format("%02X ", b & 0xFF));
            ctxLine.setLength(ctxLine.length() - 1); // trim trailing space
            ctxLine.append('|');
            if (afterBytes != null && afterBytes.length > 0) {
                for (byte b : afterBytes) ctxLine.append(String.format(" %02X", b & 0xFF));
                ctxLine.append(" ...");
            }
            System.out.println(ctxLine);

            // Hex dump line: 32 bytes at match offset
            int dumpLen = 32;
            byte[] dumpBytes = finder.testTool.readRomBytes(offset, dumpLen);
            if (dumpBytes != null) {
                StringBuilder dumpLine = new StringBuilder();
                dumpLine.append(String.format("  Hex dump (%d bytes at offset):%n    %06X:", dumpLen, offset));
                for (int i = 0; i < dumpBytes.length; i++) {
                    if (i > 0 && i % 16 == 0) {
                        dumpLine.append(String.format("%n    %06X:", offset + i));
                    } else if (i > 0 && i % 8 == 0) {
                        dumpLine.append(' ');
                    }
                    dumpLine.append(String.format(" %02X", dumpBytes[i] & 0xFF));
                }
                System.out.println(dumpLine);
            }

            System.out.println();
        }
    }

    /**
     * Parse a hex string into a byte array.
     * Accepts formats: "0002 5CC0", "00025CC0", "00 02 5C C0"
     */
    static byte[] parseHexString(String hex) {
        String stripped = hex.replaceAll("\\s+", "");
        if (stripped.isEmpty()) {
            return new byte[0];
        }
        if (stripped.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length (got " + stripped.length() + ")");
        }
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                throw new IllegalArgumentException("Invalid hex character: '" + c + "' at position " + i);
            }
        }
        byte[] result = new byte[stripped.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(stripped.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    /**
     * Format PLC cross-references for an art label, or null if none found.
     */
    private static String formatPlcReferences(DisassemblySearchTool searchTool, String label) {
        if (label == null) return null;
        try {
            Set<String> plcs = searchTool.getPlcArtReverseIndex().get(label);
            if (plcs != null && !plcs.isEmpty()) {
                return String.join(", ", plcs);
            }
        } catch (IOException e) {
            // PLC cross-referencing unavailable
        }
        return null;
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

            if (result.hasFile()) {
                System.out.printf("File:        %s%n", result.getFilePath());
                System.out.printf("Compression: %s%n", result.getCompressionType().getDisplayName());
            } else if (result.getCompressionType() == CompressionType.ASSEMBLY_DATA) {
                System.out.printf("Type:        Assembly data label%n");
            } else {
                System.out.printf("Type:        Label-only (no binary data)%n");
            }

            System.out.printf("ASM Source:  %s:%d%n", result.getAsmFilePath(), result.getAsmLineNumber());

            if (result.hasFile()) {
                if (result.getCompressionType() == CompressionType.ASSEMBLY_DATA) {
                    printAssemblyDataOffset(finder, result, "ROM Offset:  ");
                } else {
                    try {
                        long size = finder.searchTool.getFileSize(result.getFilePath());
                        System.out.printf("File Size:   %d bytes%n", size);
                    } catch (IOException e) {
                        System.out.printf("File Size:   (not found)%n");
                    }

                    // Get verified ROM offset by searching the ROM directly
                    if (result.getLabel() != null) {
                        try {
                            VerificationResult vr = finder.verify(result.getLabel());
                            switch (vr.getStatus()) {
                                case VERIFIED:
                                    System.out.printf("ROM Offset:  0x%X (verified)%n", vr.getCalculatedOffset());
                                    break;
                                case MISMATCH:
                                    System.out.printf("ROM Offset:  0x%X (verified)%n", vr.getVerifiedOffset());
                                    break;
                                case NOT_FOUND:
                                    System.out.printf("ROM Offset:  (not found in ROM)%n");
                                    break;
                                case ERROR:
                                    System.out.printf("ROM Offset:  (error: %s)%n", vr.getMessage());
                                    break;
                            }
                        } catch (IOException e) {
                            // Ignore verification errors
                        }
                    }
                }
            } else if (result.getCompressionType() == CompressionType.ASSEMBLY_DATA) {
                printAssemblyDataOffset(finder, result, "ROM Offset:  ");
            }

            String plcRefs = formatPlcReferences(finder.searchTool, result.getLabel());
            if (plcRefs != null) {
                System.out.printf("PLCs:        %s%n", plcRefs);
            }

            System.out.println();
        }
    }

    private static void printAssemblyDataOffset(RomOffsetFinder finder,
                                                DisassemblySearchResult result,
                                                String prefix) {
        if (result.getLabel() == null) {
            return;
        }
        try {
            long offset = finder.offsetCalculator.calculateOffset(result.getLabel());
            if (offset >= 0) {
                System.out.printf("%s0x%X (calculated)%n", prefix, offset);
            } else {
                System.out.printf("%s(could not calculate)%n", prefix);
            }
        } catch (IOException e) {
            System.out.printf("%s(error: %s)%n", prefix, e.getMessage());
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

            String plcRefs = formatPlcReferences(finder.searchTool, result.getSearchResult().getLabel());
            if (plcRefs != null) {
                System.out.printf("PLCs:             %s%n", plcRefs);
            }
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
                    result.getCompressionType() != null ? result.getCompressionType().getDisplayName() : "N/A",
                    result.getFilePath() != null ? result.getFilePath() : "(label-only)");
        }

        System.out.println();
        System.out.println("Total: " + results.size() + " items");
    }

    private static void handleVerify(RomOffsetFinder finder, String pattern) throws IOException {
        System.out.println("Verifying: " + pattern);
        System.out.println();

        VerificationResult result = finder.verify(pattern);

        switch (result.getStatus()) {
            case VERIFIED: {
                System.out.println("=== VERIFIED ===");
                System.out.printf("Label:       %s%n", result.getLabel());
                System.out.printf("Offset:      0x%X%n", result.getCalculatedOffset());
                System.out.printf("Type:        %s%n", result.getCompressionType().getDisplayName());
                System.out.printf("File Size:   %d bytes%n", result.getFileSize());
                String plcRefs = formatPlcReferences(finder.searchTool, result.getLabel());
                if (plcRefs != null) {
                    System.out.printf("PLCs:        %s%n", plcRefs);
                }
                break;
            }
            case MISMATCH: {
                System.out.println("=== MISMATCH ===");
                System.out.printf("Label:       %s%n", result.getLabel());
                System.out.printf("Calculated:  0x%X%n", result.getCalculatedOffset());
                System.out.printf("Actual:      0x%X%n", result.getVerifiedOffset());
                System.out.printf("Difference:  %+d bytes%n",
                        result.getVerifiedOffset() - result.getCalculatedOffset());
                String plcRefs2 = formatPlcReferences(finder.searchTool, result.getLabel());
                if (plcRefs2 != null) {
                    System.out.printf("PLCs:        %s%n", plcRefs2);
                }
                break;
            }
            case NOT_FOUND:
                System.out.println("=== NOT FOUND ===");
                System.out.printf("Label:       %s%n", result.getLabel());
                if (result.getCalculatedOffset() >= 0) {
                    System.out.printf("Calculated:  0x%X%n", result.getCalculatedOffset());
                }
                System.out.printf("Message:     %s%n", result.getMessage());
                break;
            case ERROR:
                System.out.println("=== ERROR ===");
                System.out.printf("Label:       %s%n", result.getLabel());
                System.out.printf("Message:     %s%n", result.getMessage());
                break;
        }
    }

    private static void handleVerifyBatch(RomOffsetFinder finder, String typeStr) throws IOException {
        CompressionType type = typeStr != null ? parseCompressionType(typeStr) : null;

        System.out.printf("Batch verifying %s items...%n%n",
                type != null ? type.getDisplayName() : "all");

        List<VerificationResult> results = finder.verifyBatch(type);

        // Counters
        int verified = 0, mismatch = 0, notFound = 0, error = 0;

        for (VerificationResult r : results) {
            String statusChar;
            switch (r.getStatus()) {
                case VERIFIED:
                    statusChar = "[OK]";
                    verified++;
                    break;
                case MISMATCH:
                    statusChar = "[!!]";
                    mismatch++;
                    break;
                case NOT_FOUND:
                    statusChar = "[??]";
                    notFound++;
                    break;
                default:
                    statusChar = "[ER]";
                    error++;
                    break;
            }

            System.out.printf("%s %-40s", statusChar, r.getLabel());
            switch (r.getStatus()) {
                case VERIFIED:
                    System.out.printf(" 0x%X%n", r.getCalculatedOffset());
                    break;
                case MISMATCH:
                    System.out.printf(" calc=0x%X actual=0x%X%n",
                            r.getCalculatedOffset(), r.getVerifiedOffset());
                    break;
                default:
                    System.out.printf(" %s%n", r.getMessage());
                    break;
            }
        }

        System.out.println();
        System.out.printf("Summary: %d verified, %d mismatch, %d not found, %d errors%n",
                verified, mismatch, notFound, error);
    }

    private static void handleExport(RomOffsetFinder finder, String typeStr, String prefix) throws IOException {
        CompressionType type = parseCompressionType(typeStr);
        if (type == null) {
            System.out.println("Unknown compression type: " + typeStr);
            System.out.println("Valid types: nem, kos, eni, sax, bin");
            return;
        }

        System.out.println("Verifying and exporting " + type.getDisplayName() + " offsets...");
        System.out.println();

        List<VerificationResult> results = finder.verifyBatch(type);

        // Count results
        long verifiedCount = results.stream()
                .filter(r -> r.getStatus() == VerificationResult.Status.VERIFIED)
                .count();

        if (verifiedCount == 0) {
            System.out.println("No offsets could be verified for export.");
            return;
        }

        System.out.printf("Verified %d offsets, exporting as Java constants:%n%n", verifiedCount);

        // Export to stdout
        ConstantsExporter exporter = new ConstantsExporter();
        StringWriter sw = new StringWriter();
        exporter.exportAsJavaConstants(results, prefix, sw, finder.getProfile());
        System.out.println(sw.toString());
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
            case "asm":
                return CompressionType.ASSEMBLY_DATA;
            default:
                return null;
        }
    }

    private static void printUsage() {
        System.out.println("RomOffsetFinder - Find ROM offsets for Sonic disassembly items");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  [--game s1|s2|s3k] search <pattern>       Search for items by label/filename");
        System.out.println("  [--game s1|s2|s3k] find <label> [offset]  Find ROM offset for a specific item");
        System.out.println("  [--game s1|s2|s3k] test <offset> <type>   Test decompression at offset");
        System.out.println("  [--game s1|s2|s3k] list [type]            List all includes (optionally by type)");
        System.out.println("  [--game s1|s2|s3k] verify <label>         Verify calculated offset against ROM");
        System.out.println("  [--game s1|s2|s3k] verify-batch [type]    Batch verify all items (by type)");
        System.out.println("  [--game s1|s2|s3k] export <type> [prefix] Export verified offsets as constants");
        System.out.println("  [--game s1|s2|s3k] plc <name>             Show contents of a PLC definition");
        System.out.println("  [--game s1|s2|s3k] search-rom <hex> [start] [end]  Search ROM for hex byte pattern");
        System.out.println("  --game s1 verify-audio                    Verify Sonic 1 audio ROM addresses");
        System.out.println();
        System.out.println("Game selection:");
        System.out.println("  --game s1          Sonic 1 (s1disasm + Sonic 1 ROM)");
        System.out.println("  --game s2          Sonic 2 (s2disasm + Sonic 2 ROM) [default]");
        System.out.println("  --game s3k         Sonic 3&K (skdisasm + S3K ROM)");
        System.out.println("  -Dgame=s1          Alternative via system property");
        System.out.println("  (auto-detected from disasm path if --game not specified)");
        System.out.println();
        System.out.println("Compression types: nem, kos, kosm, eni, sax, bin, asm, auto");
        System.out.println();
        System.out.println("System properties:");
        System.out.println("  -Drom.path=<path>            Path to ROM file");
        System.out.println("  -Ddisasm.path=<path>         Path to disassembly directory");
        System.out.println("  -Dgame=<s1|s2|s3k>           Game selection");
        System.out.println();
        System.out.println("Examples (Sonic 2 - default):");
        System.out.println("  search ring                   Search for items containing 'ring'");
        System.out.println("  find ArtNem_Ring              Find ROM offset for ArtNem_Ring");
        System.out.println("  test 0x41A4C nem              Test Nemesis decompression at 0x41A4C");
        System.out.println("  list nem                      List all Nemesis-compressed files");
        System.out.println("  verify ArtNem_SpecialHUD      Verify offset of ArtNem_SpecialHUD");
        System.out.println();
        System.out.println("Examples (PLC cross-referencing):");
        System.out.println("  search ArtNem_Ring             Shows which PLCs load Ring art");
        System.out.println("  plc PlrList_Htz1               List art entries in HTZ Act 1 PLC");
        System.out.println("  --game s3k plc PLCKosM_AIZ     List art entries in AIZ PLC");
        System.out.println("  --game s1 plc PLC_GHZ          List art entries in GHZ PLC");
        System.out.println();
        System.out.println("Examples (Sonic 1):");
        System.out.println("  --game s1 search Nem_GHZ      Search S1 disassembly for GHZ art");
        System.out.println("  --game s1 list nem             List all S1 Nemesis files");
        System.out.println("  --game s1 search Pal_Sonic     Find Sonic palette (bincludePalette)");
        System.out.println("  --game s1 verify Pal_Sonic     Verify S1 palette offset against ROM");
        System.out.println();
        System.out.println("Examples (Sonic 3&K):");
        System.out.println("  --game s3k search AIZ          Search S3K disassembly for AIZ items");
        System.out.println("  --game s3k list nem             List all S3K Nemesis files");
        System.out.println("  --game s3k search Pal_AIZ       Find AIZ palette");
        System.out.println("  --game s3k verify ArtNem_TitleScreenText  Verify S3K offset against ROM");
        System.out.println();
        System.out.println("Examples (search-rom):");
        System.out.println("  --game s1 search-rom \"07 72 73 26 15 08 FF 05\"   Find SpeedUpIndex pattern");
        System.out.println("  --game s3k search-rom \"0002 5CC0 090C\"           Find AniPLC_AIZ1 table");
        System.out.println("  --game s3k search-rom \"0002\" 0x28000 0x29000     Restrict search range");
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
