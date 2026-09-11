package com.openggf.tools.disasm;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tool for searching Sonic disassemblies (s1disasm/s2disasm/skdisasm) for items by label name or file name.
 * Parses BINCLUDE/binclude/bincludePalette directives and returns information about matching entries.
 */
public class DisassemblySearchTool {

    // Matches labeled BINCLUDE/binclude/bincludePalette directives
    private static final Pattern BINCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*(?:BINCLUDE|binclude(?:Palette)?)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Matches unlabeled BINCLUDE/binclude/bincludePalette directives
    private static final Pattern BINCLUDE_NO_LABEL_PATTERN = Pattern.compile(
            "^\\s*(?:BINCLUDE|binclude(?:Palette)?)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Matches labeled include directives (assembly text includes)
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*include\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // Matches unlabeled include directives
    private static final Pattern INCLUDE_NO_LABEL_PATTERN = Pattern.compile(
            "^\\s*include\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // Label on its own line (for multiline label+binclude/include resolution in S3K)
    private static final Pattern STANDALONE_LABEL_PATTERN = Pattern.compile(
            "^(\\w+):\\s*$"
    );

    private static final Pattern ASM_LABEL_PATTERN = Pattern.compile(
            "^(\\w+):\\b.*$"
    );

    // Offset table labels: "Offs_Foo:" standalone or "Offs_Foo: dc.w ..."
    private static final Pattern OFFSET_TABLE_PATTERN = Pattern.compile(
            "^(Offs_\\w+):(?:\\s+dc\\.w\\s+.*)?\\s*$"
    );

    // PLC macro labels: "PLC_0A: plrlistheader" or "PLCKosM_AIZ: plrlistheader"
    private static final Pattern PLC_LABEL_PATTERN = Pattern.compile(
            "^((?:PLC|PLCKosM)_\\w+):\\s+plrlistheader"
    );

    // Broad PLC header: any label followed by plrlistheader (S2 PlrList_*, S3K PLCKosM_*)
    private static final Pattern PLC_HEADER_PATTERN = Pattern.compile(
            "^(\\w+):\\s+plrlistheader"
    );

    // S1 PLC header: "PLC_xxx: dc.w ((End-Start-2)/6)-1"
    private static final Pattern S1_PLC_HEADER_PATTERN = Pattern.compile(
            "^(PLC_\\w+):\\s+dc\\.w\\s+"
    );

    // S2/S3K PLC entry: "plreq ArtTile_X, ArtNem_Y" — captures second param (art label)
    private static final Pattern PLREQ_ENTRY_PATTERN = Pattern.compile(
            "^\\s+plreq\\s+\\S+\\s*,\\s*(\\w+)"
    );

    // S1 PLC entry: "plcm Nem_X, ArtTile_Y" — captures first param (art label)
    private static final Pattern PLCM_ENTRY_PATTERN = Pattern.compile(
            "^\\s+plcm\\s+(\\w+)\\s*,"
    );

    // Pattern for S2 palette macro: "Label: palette path[,path2] [; comment]"
    // The macro expands to BINCLUDE "art/palettes/{path}"
    // path can contain spaces, stops at comma, semicolon, or end of line
    private static final Pattern PALETTE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*palette\\s+([^,;]+?)(?:\\s*,\\s*([^;]+?))?(?:\\s*;.*)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final Path disasmRoot;
    private final RomOffsetFinder.GameProfile profile;

    // Cached PLC art cross-reference indexes (built lazily)
    private Map<String, Set<String>> plcArtReverseIndex;
    private Map<String, List<String>> plcArtForwardIndex;

    public DisassemblySearchTool(Path disasmRoot) {
        this(disasmRoot, null);
    }

    public DisassemblySearchTool(String disasmRootPath) {
        this(Path.of(disasmRootPath), null);
    }

    public DisassemblySearchTool(String disasmRootPath, RomOffsetFinder.GameProfile profile) {
        this(Path.of(disasmRootPath), profile);
    }

    public DisassemblySearchTool(Path disasmRoot, RomOffsetFinder.GameProfile profile) {
        this.disasmRoot = disasmRoot;
        this.profile = profile;
    }

    private boolean hasPaletteMacro() {
        return profile == null || profile.paletteDirPrefix() != null;
    }

    /**
     * Search for items by label name (case-insensitive partial match).
     */
    public List<DisassemblySearchResult> searchByLabel(String labelPattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = labelPattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFile(asmFile, results, lowerPattern, true);
        }

        return results;
    }

    /**
     * Search for items by file name (case-insensitive partial match).
     */
    public List<DisassemblySearchResult> searchByFileName(String fileNamePattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = fileNamePattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFile(asmFile, results, lowerPattern, false);
        }

        return results;
    }

    /**
     * Search for items matching a general pattern (matches both label and file name).
     */
    public List<DisassemblySearchResult> search(String pattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = pattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileBoth(asmFile, results, lowerPattern);
        }

        return results;
    }

    /**
     * Search for files by compression type.
     */
    public List<DisassemblySearchResult> searchByCompressionType(CompressionType type) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileByCompression(asmFile, results, type);
        }

        return results;
    }

    /**
     * List all binary includes in the disassembly.
     */
    public List<DisassemblySearchResult> listAllIncludes() throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileBoth(asmFile, results, "");
        }

        return results;
    }

    /**
     * Find a file in the disassembly by exact path.
     */
    public Path resolveFilePath(String relativePath) {
        return disasmRoot.resolve(relativePath);
    }

    /**
     * Get the file size of a disassembly file.
     */
    public long getFileSize(String relativePath) throws IOException {
        Path path = resolveFilePath(relativePath);
        if (Files.exists(path)) {
            return Files.size(path);
        }
        return -1;
    }

    /**
     * Read bytes from a disassembly file.
     */
    public byte[] readFileBytes(String relativePath) throws IOException {
        return Files.readAllBytes(resolveFilePath(relativePath));
    }

    private List<Path> findAsmFiles() throws IOException {
        List<Path> asmFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(disasmRoot)) {
            walk.filter(p -> p.toString().endsWith(".asm"))
                .forEach(asmFiles::add);
        }
        return asmFiles;
    }

    private void searchAsmFile(Path asmFile, List<DisassemblySearchResult> results,
                                String pattern, boolean matchLabel) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            String pendingLabel = null;
            int pendingLabelLine = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // 1. Same-line label+binclude
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    pendingLabel = null;
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);
                    String matchTarget = matchLabel ? label.toLowerCase() : filePath.toLowerCase();

                    if (matchTarget.contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.fromLabelAndExtension(label, filePath),
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2. Unlabeled binclude — associate with pendingLabel if available
                Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (noLabelMatcher.find()) {
                    String filePath = noLabelMatcher.group(1);
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;

                    if (resolvedLabel != null) {
                        String matchTarget = matchLabel ? resolvedLabel.toLowerCase() : filePath.toLowerCase();
                        if (matchTarget.contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    resolvedLabel,
                                    filePath,
                                    CompressionType.fromLabelAndExtension(resolvedLabel, filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    pendingLabelLine,
                                    line.trim()
                            ));
                        }
                    } else {
                        if (!matchLabel && filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.fromExtension(filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                    continue;
                }

                // 2b. Same-line label+include (assembly text include)
                Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                if (includeMatcher.find()) {
                    pendingLabel = null;
                    String label = includeMatcher.group(1);
                    String filePath = includeMatcher.group(2);
                    String matchTarget = matchLabel ? label.toLowerCase() : filePath.toLowerCase();

                    if (matchTarget.contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2c. Unlabeled include — associate with pendingLabel if available
                Matcher includeNoLabelMatcher = INCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (includeNoLabelMatcher.find()) {
                    String filePath = includeNoLabelMatcher.group(1);
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;

                    if (resolvedLabel != null) {
                        String matchTarget = matchLabel ? resolvedLabel.toLowerCase() : filePath.toLowerCase();
                        if (matchTarget.contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    resolvedLabel,
                                    filePath,
                                    CompressionType.ASSEMBLY_DATA,
                                    disasmRoot.relativize(asmFile).toString(),
                                    pendingLabelLine,
                                    line.trim()
                            ));
                        }
                    } else {
                        if (!matchLabel && filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.ASSEMBLY_DATA,
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                    continue;
                }

                // 3. Standalone label — check for Offs_ immediate emit, otherwise store as pending
                Matcher standaloneMatcher = STANDALONE_LABEL_PATTERN.matcher(line);
                if (standaloneMatcher.find()) {
                    String label = standaloneMatcher.group(1);
                    if (matchLabel && label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    if (label.startsWith("Offs_")) {
                        pendingLabel = null;
                    } else {
                        pendingLabel = label;
                        pendingLabelLine = lineNumber;
                    }
                    continue;
                }

                // 4. Any other line — clear pendingLabel, check extended patterns
                pendingLabel = null;

                // Offs_Foo: dc.w ... (same-line offset table)
                Matcher offsMatcher = OFFSET_TABLE_PATTERN.matcher(line);
                if (offsMatcher.find()) {
                    String label = offsMatcher.group(1);
                    if (matchLabel && label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, null,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    continue;
                }

                Matcher asmLabelMatcher = ASM_LABEL_PATTERN.matcher(line);
                if (asmLabelMatcher.find()) {
                    String label = asmLabelMatcher.group(1);
                    if (matchLabel && label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    continue;
                }

                // PLC macro labels
                Matcher plcMatcher = PLC_LABEL_PATTERN.matcher(line);
                if (plcMatcher.find()) {
                    String label = plcMatcher.group(1);
                    if (matchLabel && label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, null,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                }
            }
        }
    }

    private void searchAsmFileBoth(Path asmFile, List<DisassemblySearchResult> results,
                                    String pattern) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            String pendingLabel = null;
            int pendingLabelLine = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // 1. Same-line label+binclude
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    pendingLabel = null;
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);

                    if (pattern.isEmpty() ||
                        label.toLowerCase().contains(pattern) ||
                        filePath.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.fromLabelAndExtension(label, filePath),
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2. Unlabeled binclude — associate with pendingLabel if available
                Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (noLabelMatcher.find()) {
                    String filePath = noLabelMatcher.group(1);
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;

                    if (resolvedLabel != null) {
                        if (pattern.isEmpty() ||
                            resolvedLabel.toLowerCase().contains(pattern) ||
                            filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    resolvedLabel,
                                    filePath,
                                    CompressionType.fromLabelAndExtension(resolvedLabel, filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    pendingLabelLine,
                                    line.trim()
                            ));
                        }
                    } else {
                        if (pattern.isEmpty() || filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.fromExtension(filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                    continue;
                }

                // 2b. Same-line label+include (assembly text include)
                Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                if (includeMatcher.find()) {
                    pendingLabel = null;
                    String label = includeMatcher.group(1);
                    String filePath = includeMatcher.group(2);

                    if (pattern.isEmpty() ||
                        label.toLowerCase().contains(pattern) ||
                        filePath.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2c. Unlabeled include — associate with pendingLabel if available
                Matcher includeNoLabelMatcher = INCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (includeNoLabelMatcher.find()) {
                    String filePath = includeNoLabelMatcher.group(1);
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;

                    if (resolvedLabel != null) {
                        if (pattern.isEmpty() ||
                            resolvedLabel.toLowerCase().contains(pattern) ||
                            filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    resolvedLabel,
                                    filePath,
                                    CompressionType.ASSEMBLY_DATA,
                                    disasmRoot.relativize(asmFile).toString(),
                                    pendingLabelLine,
                                    line.trim()
                            ));
                        }
                    } else {
                        if (pattern.isEmpty() || filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.ASSEMBLY_DATA,
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                    continue;
                }

                // 3. Standalone label
                Matcher standaloneMatcher = STANDALONE_LABEL_PATTERN.matcher(line);
                if (standaloneMatcher.find()) {
                    String label = standaloneMatcher.group(1);
                    if (pattern.isEmpty() || label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    if (label.startsWith("Offs_")) {
                        pendingLabel = null;
                    } else {
                        pendingLabel = label;
                        pendingLabelLine = lineNumber;
                    }
                    continue;
                }

                // 4. Any other line — clear pendingLabel, check extended patterns
                pendingLabel = null;

                // Offs_Foo: dc.w ... (same-line offset table)
                Matcher offsMatcher = OFFSET_TABLE_PATTERN.matcher(line);
                if (offsMatcher.find()) {
                    String label = offsMatcher.group(1);
                    if (pattern.isEmpty() || label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, null,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    continue;
                }

                Matcher asmLabelMatcher = ASM_LABEL_PATTERN.matcher(line);
                if (asmLabelMatcher.find()) {
                    String label = asmLabelMatcher.group(1);
                    if (pattern.isEmpty() || label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    continue;
                }

                // PLC macro labels
                Matcher plcMatcher = PLC_LABEL_PATTERN.matcher(line);
                if (plcMatcher.find()) {
                    String label = plcMatcher.group(1);
                    if (pattern.isEmpty() || label.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label, null, null,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber, line.trim()
                        ));
                    }
                    continue;
                }

                if (hasPaletteMacro()) {
                    // Check for S2 palette macro (S1 uses bincludePalette, caught above)
                    Matcher paletteMatcher = PALETTE_PATTERN.matcher(line);
                    if (paletteMatcher.find()) {
                        parsePaletteMacroResults(paletteMatcher, asmFile, lineNumber, line, pattern, results);
                    }
                }
            }
        }
    }

    /**
     * Parse palette macro and add results.
     * Palette macro format: "Label: palette path[,path2]"
     * Expands to: BINCLUDE "art/palettes/{path}"
     */
    private void parsePaletteMacroResults(Matcher matcher, Path asmFile, int lineNumber,
                                           String line, String pattern, List<DisassemblySearchResult> results) {
        String label = matcher.group(1);
        String path1 = matcher.group(2).trim();
        String path2 = matcher.group(3) != null ? matcher.group(3).trim() : null;

        // First palette file
        String filePath1 = "art/palettes/" + path1;
        if (pattern.isEmpty() ||
            label.toLowerCase().contains(pattern) ||
            filePath1.toLowerCase().contains(pattern)) {
            results.add(new DisassemblySearchResult(
                    label,
                    filePath1,
                    CompressionType.UNCOMPRESSED, // Palettes are .bin (uncompressed)
                    disasmRoot.relativize(asmFile).toString(),
                    lineNumber,
                    line.trim()
            ));
        }

        // Second palette file (if present)
        if (path2 != null && !path2.isEmpty()) {
            String filePath2 = "art/palettes/" + path2;
            // Create a label with "_2" suffix for the second palette
            String label2 = label + "_2";
            if (pattern.isEmpty() ||
                label2.toLowerCase().contains(pattern) ||
                filePath2.toLowerCase().contains(pattern)) {
                results.add(new DisassemblySearchResult(
                        label2,
                        filePath2,
                        CompressionType.UNCOMPRESSED,
                        disasmRoot.relativize(asmFile).toString(),
                        lineNumber,
                        line.trim()
                ));
            }
        }
    }

    private void searchAsmFileByCompression(Path asmFile, List<DisassemblySearchResult> results,
                                             CompressionType type) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            String pendingLabel = null;
            int pendingLabelLine = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // 1. Same-line label+binclude
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    pendingLabel = null;
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);
                    CompressionType fileType = CompressionType.fromLabelAndExtension(label, filePath);

                    if (fileType == type) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                fileType,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2. Unlabeled binclude — associate with pendingLabel if available
                Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (noLabelMatcher.find()) {
                    String filePath = noLabelMatcher.group(1);
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;

                    CompressionType fileType = resolvedLabel != null
                            ? CompressionType.fromLabelAndExtension(resolvedLabel, filePath)
                            : CompressionType.fromExtension(filePath);

                    if (fileType == type) {
                        results.add(new DisassemblySearchResult(
                                resolvedLabel,
                                filePath,
                                fileType,
                                disasmRoot.relativize(asmFile).toString(),
                                resolvedLabel != null ? pendingLabelLine : lineNumber,
                                line.trim()
                        ));
                    }
                    continue;
                }

                // 2b. Same-line label+include (assembly text include)
                if (type == CompressionType.ASSEMBLY_DATA) {
                    Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                    if (includeMatcher.find()) {
                        pendingLabel = null;
                        String label = includeMatcher.group(1);
                        String filePath = includeMatcher.group(2);

                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                        continue;
                    }
                }

                // 2c. Unlabeled include — associate with pendingLabel if available
                if (type == CompressionType.ASSEMBLY_DATA) {
                    Matcher includeNoLabelMatcher = INCLUDE_NO_LABEL_PATTERN.matcher(line);
                    if (includeNoLabelMatcher.find()) {
                        String filePath = includeNoLabelMatcher.group(1);
                        String resolvedLabel = pendingLabel;
                        pendingLabel = null;

                        results.add(new DisassemblySearchResult(
                                resolvedLabel,
                                filePath,
                                CompressionType.ASSEMBLY_DATA,
                                disasmRoot.relativize(asmFile).toString(),
                                resolvedLabel != null ? pendingLabelLine : lineNumber,
                                line.trim()
                        ));
                        continue;
                    }
                }

                // 3. Standalone label — store as pending (no compression type to match for label-only)
                Matcher standaloneMatcher = STANDALONE_LABEL_PATTERN.matcher(line);
                if (standaloneMatcher.find()) {
                    String label = standaloneMatcher.group(1);
                    if (label.startsWith("Offs_")) {
                        pendingLabel = null;
                        // Label-only results have no compression type — skip for compression filter
                    } else {
                        pendingLabel = label;
                        pendingLabelLine = lineNumber;
                    }
                    continue;
                }

                // 4. Any other line — clear pendingLabel, check palette macro
                pendingLabel = null;

                if (hasPaletteMacro()) {
                    // Check for S2 palette macro (palettes are UNCOMPRESSED)
                    if (type == CompressionType.UNCOMPRESSED) {
                        Matcher paletteMatcher = PALETTE_PATTERN.matcher(line);
                        if (paletteMatcher.find()) {
                            parsePaletteMacroResultsForType(paletteMatcher, asmFile, lineNumber, line, results);
                        }
                    }
                }
            }
        }
    }

    /**
     * Parse palette macro for compression type search (no pattern filter).
     */
    private void parsePaletteMacroResultsForType(Matcher matcher, Path asmFile, int lineNumber,
                                                  String line, List<DisassemblySearchResult> results) {
        String label = matcher.group(1);
        String path1 = matcher.group(2).trim();
        String path2 = matcher.group(3) != null ? matcher.group(3).trim() : null;

        // First palette file
        String filePath1 = "art/palettes/" + path1;
        results.add(new DisassemblySearchResult(
                label,
                filePath1,
                CompressionType.UNCOMPRESSED,
                disasmRoot.relativize(asmFile).toString(),
                lineNumber,
                line.trim()
        ));

        // Second palette file (if present)
        if (path2 != null && !path2.isEmpty()) {
            String filePath2 = "art/palettes/" + path2;
            results.add(new DisassemblySearchResult(
                    label + "_2",
                    filePath2,
                    CompressionType.UNCOMPRESSED,
                    disasmRoot.relativize(asmFile).toString(),
                    lineNumber,
                    line.trim()
            ));
        }
    }

    /**
     * Get the reverse PLC art index: art label -> set of PLC labels that reference it.
     * Built lazily on first call and cached.
     */
    public Map<String, Set<String>> getPlcArtReverseIndex() throws IOException {
        if (plcArtReverseIndex == null) {
            buildPlcArtIndex();
        }
        return plcArtReverseIndex;
    }

    /**
     * Get the forward PLC art index: PLC label -> ordered list of art labels.
     * Built lazily on first call and cached.
     */
    public Map<String, List<String>> getPlcArtForwardIndex() throws IOException {
        if (plcArtForwardIndex == null) {
            buildPlcArtIndex();
        }
        return plcArtForwardIndex;
    }

    private void buildPlcArtIndex() throws IOException {
        plcArtReverseIndex = new LinkedHashMap<>();
        plcArtForwardIndex = new LinkedHashMap<>();
        for (Path asmFile : findAsmFiles()) {
            parsePlcDefinitions(asmFile);
        }
    }

    private void parsePlcDefinitions(Path asmFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            String currentPlcLabel = null;
            List<String> currentEntries = null;

            while ((line = reader.readLine()) != null) {
                // Check for S2/S3K PLC header: "Label: plrlistheader"
                Matcher headerMatcher = PLC_HEADER_PATTERN.matcher(line);
                if (headerMatcher.find()) {
                    savePlcBlock(currentPlcLabel, currentEntries);
                    currentPlcLabel = headerMatcher.group(1);
                    currentEntries = new ArrayList<>();
                    continue;
                }

                // Check for S1 PLC header: "PLC_xxx: dc.w ..."
                Matcher s1HeaderMatcher = S1_PLC_HEADER_PATTERN.matcher(line);
                if (s1HeaderMatcher.find()) {
                    savePlcBlock(currentPlcLabel, currentEntries);
                    currentPlcLabel = s1HeaderMatcher.group(1);
                    currentEntries = new ArrayList<>();
                    continue;
                }

                if (currentPlcLabel == null) continue;

                // Parse S2/S3K plreq entry: "plreq ArtTile_X, ArtNem_Y"
                Matcher plreqMatcher = PLREQ_ENTRY_PATTERN.matcher(line);
                if (plreqMatcher.find()) {
                    String artLabel = plreqMatcher.group(1);
                    currentEntries.add(artLabel);
                    plcArtReverseIndex.computeIfAbsent(artLabel, k -> new LinkedHashSet<>())
                            .add(currentPlcLabel);
                    continue;
                }

                // Parse S1 plcm entry: "plcm Nem_X, ArtTile_Y"
                Matcher plcmMatcher = PLCM_ENTRY_PATTERN.matcher(line);
                if (plcmMatcher.find()) {
                    String artLabel = plcmMatcher.group(1);
                    currentEntries.add(artLabel);
                    plcArtReverseIndex.computeIfAbsent(artLabel, k -> new LinkedHashSet<>())
                            .add(currentPlcLabel);
                    continue;
                }

                // Skip blank lines, comments, conditional assembly, and alignment directives
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(";") ||
                        trimmed.startsWith("if ") || trimmed.equals("endif") ||
                        trimmed.equals("else") || trimmed.startsWith("even")) {
                    continue;
                }

                // Any other line ends the current PLC block
                savePlcBlock(currentPlcLabel, currentEntries);
                currentPlcLabel = null;
                currentEntries = null;
            }

            // Save final block
            savePlcBlock(currentPlcLabel, currentEntries);
        }
    }

    private void savePlcBlock(String label, List<String> entries) {
        if (label != null && entries != null && !entries.isEmpty()) {
            plcArtForwardIndex.put(label, List.copyOf(entries));
        }
    }
}
