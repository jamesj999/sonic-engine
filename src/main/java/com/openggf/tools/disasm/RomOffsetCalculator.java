package com.openggf.tools.disasm;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates ROM offsets for disassembly items by using known anchor offsets
 * and summing file sizes in assembly order.
 *
 * The disassembly assembles files sequentially, so we can calculate any offset if we know:
 * 1. A nearby anchor offset (from verified constants)
 * 2. The file sizes between the anchor and target
 */
public class RomOffsetCalculator {

    private static final Pattern BINCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*(?:BINCLUDE|binclude(?:Palette)?)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BINCLUDE_NO_LABEL_PATTERN = Pattern.compile(
            "^\\s*(?:BINCLUDE|binclude(?:Palette)?)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*include\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INCLUDE_NO_LABEL_PATTERN = Pattern.compile(
            "^\\s*include\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ALIGN_PATTERN = Pattern.compile(
            "^\\s*align\\s+(\\$?[0-9A-Fa-f]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EVEN_PATTERN = Pattern.compile(
            "^\\s*even\\b",
            Pattern.CASE_INSENSITIVE
    );
    // Label on its own line (for multiline label+binclude resolution in S3K)
    private static final Pattern STANDALONE_LABEL_PATTERN = Pattern.compile(
            "^(\\w+):\\s*$"
    );

    // Pattern for S2 palette macro: "Label: palette path[,path2] [; comment]"
    // The macro expands to BINCLUDE "art/palettes/{path}"
    private static final Pattern PALETTE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*palette\\s+([^,;]+?)(?:\\s*,\\s*([^;]+?))?(?:\\s*;.*)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final Path disasmRoot;
    private final RomOffsetFinder.GameProfile profile;
    private final Map<String, Long> anchorOffsets;
    private List<BincludeEntry> orderedEntries;

    /**
     * Runtime-discovered anchors from verified offsets.
     * These supplement the profile anchor offsets.
     */
    private final Map<String, Long> runtimeAnchors = new LinkedHashMap<>();
    private final Map<String, IncludedAsmLabel> includedAsmLabels = new LinkedHashMap<>();
    private final Map<String, Long> assemblyEntryBaseOffsets = new LinkedHashMap<>();

    public RomOffsetCalculator(Path disasmRoot) {
        this(disasmRoot, null);
    }

    public RomOffsetCalculator(String disasmRootPath) {
        this(Path.of(disasmRootPath), null);
    }

    public RomOffsetCalculator(String disasmRootPath, RomOffsetFinder.GameProfile profile) {
        this(Path.of(disasmRootPath), profile);
    }

    public RomOffsetCalculator(Path disasmRoot, RomOffsetFinder.GameProfile profile) {
        this.disasmRoot = disasmRoot;
        this.profile = profile;
        this.anchorOffsets = profile != null
                ? new LinkedHashMap<>(profile.anchorOffsets())
                : defaultS2Anchors();
    }

    private static Map<String, Long> defaultS2Anchors() {
        return RomOffsetFinder.GameProfile.sonic2().anchorOffsets();
    }

    private boolean hasPaletteMacro() {
        return profile == null || profile.paletteDirPrefix() != null;
    }

    private String mainAsmFile() {
        return profile != null ? profile.mainAsmFile() : "s2.asm";
    }

    /**
     * Add a verified anchor offset discovered at runtime.
     * This helps improve offset calculation accuracy for nearby items.
     *
     * @param label The label to add as anchor
     * @param offset The verified ROM offset
     */
    public void addVerifiedAnchor(String label, long offset) {
        // Don't override profile anchors
        if (!anchorOffsets.containsKey(label)) {
            runtimeAnchors.put(label, offset);
        }
    }

    /**
     * Get all anchors (profile + runtime).
     */
    public Map<String, Long> getAllAnchors() {
        Map<String, Long> all = new LinkedHashMap<>(anchorOffsets);
        all.putAll(runtimeAnchors);
        return Collections.unmodifiableMap(all);
    }

    /**
     * Get only runtime-discovered anchors.
     */
    public Map<String, Long> getRuntimeAnchors() {
        return Collections.unmodifiableMap(runtimeAnchors);
    }

    /**
     * Clear all runtime anchors.
     */
    public void clearRuntimeAnchors() {
        runtimeAnchors.clear();
    }

    /**
     * Check if a label is any anchor (profile or runtime).
     */
    private boolean isAnyAnchor(String label) {
        return anchorOffsets.containsKey(label) || runtimeAnchors.containsKey(label);
    }

    /**
     * Get anchor offset (profile or runtime).
     */
    private long getAnyAnchorOffset(String label) {
        if (anchorOffsets.containsKey(label)) {
            return anchorOffsets.get(label);
        }
        return runtimeAnchors.getOrDefault(label, -1L);
    }

    /**
     * Calculate the ROM offset for a label.
     *
     * @param label The label to find (e.g., "ArtNem_SpecialStars")
     * @return The ROM offset, or -1 if not found
     */
    public long calculateOffset(String label) throws IOException {
        ensureEntriesLoaded();

        // First check if this label is a known anchor (static or runtime)
        long anchorOffset = getAnyAnchorOffset(label);
        if (anchorOffset >= 0) {
            return anchorOffset;
        }

        IncludedAsmLabel directIncludedLabel = includedAsmLabels.get(label);
        if (directIncludedLabel != null && directIncludedLabel.includeBaseOffset() >= 0) {
            return directIncludedLabel.includeBaseOffset() + directIncludedLabel.offsetWithinInclude();
        }
        Long assemblyBaseOffset = assemblyEntryBaseOffsets.get(label);
        if (assemblyBaseOffset != null) {
            return assemblyBaseOffset;
        }

        // Find the target entry. Labels inside included asm files resolve through
        // the include entry plus their byte offset within that file.
        IncludedAsmLabel includedLabel = directIncludedLabel;
        String targetEntryLabel = includedLabel != null ? includedLabel.includeLabel() : label;
        int targetIndex = -1;
        for (int i = 0; i < orderedEntries.size(); i++) {
            if (targetEntryLabel != null && targetEntryLabel.equals(orderedEntries.get(i).label)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex < 0) {
            return -1; // Label not found
        }

        // Find nearest anchor (prefer before, then after)
        int anchorIndex = -1;
        String anchorLabel = null;

        // Search backwards for nearest anchor (check both static and runtime)
        for (int i = targetIndex - 1; i >= 0; i--) {
            String entryLabel = orderedEntries.get(i).label;
            if (isAnyAnchor(entryLabel)) {
                anchorIndex = i;
                anchorLabel = entryLabel;
                anchorOffset = getAnyAnchorOffset(entryLabel);
                break;
            }
        }

        // If no anchor before, search forwards
        if (anchorIndex < 0) {
            for (int i = targetIndex + 1; i < orderedEntries.size(); i++) {
                String entryLabel = orderedEntries.get(i).label;
                if (isAnyAnchor(entryLabel)) {
                    anchorIndex = i;
                    anchorLabel = entryLabel;
                    anchorOffset = getAnyAnchorOffset(entryLabel);
                    break;
                }
            }
        }

        if (anchorIndex < 0) {
            return -1; // No anchor found
        }

        // Calculate offset by summing file sizes
        long offset = anchorOffset;

        if (anchorIndex < targetIndex) {
            // Anchor is before target - add file sizes
            for (int i = anchorIndex; i < targetIndex; i++) {
                BincludeEntry entry = orderedEntries.get(i);
                if (entry.isAlignmentEntry()) {
                    offset = alignOffset(offset, entry.alignment);
                    continue;
                }
                long fileSize = getEntrySize(entry);
                if (fileSize < 0) {
                    return -1; // File not found
                }
                offset += fileSize;
                // Align to even boundary (MC68000 requirement)
                if (offset % 2 != 0) {
                    offset++;
                }
            }
        } else {
            // Anchor is after target - subtract file sizes
            for (int i = anchorIndex - 1; i >= targetIndex; i--) {
                BincludeEntry entry = orderedEntries.get(i);
                if (entry.isAlignmentEntry()) {
                    if (entry.alignment > 2) {
                        return -1; // Cannot reliably reverse non-even alignment
                    }
                    continue;
                }
                long fileSize = getEntrySize(entry);
                if (fileSize < 0) {
                    return -1; // File not found
                }
                // Account for alignment padding
                long alignedSize = fileSize;
                if (alignedSize % 2 != 0) {
                    alignedSize++;
                }
                offset -= alignedSize;
            }
        }

        return includedLabel != null ? offset + includedLabel.offsetWithinInclude() : offset;
    }

    /**
     * Get offset calculation details for debugging.
     */
    public OffsetCalculation getCalculationDetails(String label) throws IOException {
        ensureEntriesLoaded();

        // Check if this is any anchor (static or runtime)
        if (isAnyAnchor(label)) {
            return new OffsetCalculation(label, getAnyAnchorOffset(label), label, 0, true);
        }

        IncludedAsmLabel includedLabel = includedAsmLabels.get(label);
        String targetEntryLabel = includedLabel != null ? includedLabel.includeLabel() : label;
        int targetIndex = -1;
        for (int i = 0; i < orderedEntries.size(); i++) {
            if (targetEntryLabel != null && targetEntryLabel.equals(orderedEntries.get(i).label)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex < 0) {
            return null;
        }

        // Find nearest anchor (static or runtime)
        int anchorIndex = -1;
        String anchorLabel = null;

        for (int i = targetIndex - 1; i >= 0; i--) {
            String entryLabel = orderedEntries.get(i).label;
            if (isAnyAnchor(entryLabel)) {
                anchorIndex = i;
                anchorLabel = entryLabel;
                break;
            }
        }

        if (anchorIndex < 0) {
            for (int i = targetIndex + 1; i < orderedEntries.size(); i++) {
                String entryLabel = orderedEntries.get(i).label;
                if (isAnyAnchor(entryLabel)) {
                    anchorIndex = i;
                    anchorLabel = entryLabel;
                    break;
                }
            }
        }

        if (anchorIndex < 0) {
            return null;
        }

        long offset = calculateOffset(label);
        int distance = Math.abs(targetIndex - anchorIndex);

        return new OffsetCalculation(label, offset, anchorLabel, distance, false);
    }

    private void ensureEntriesLoaded() throws IOException {
        if (orderedEntries == null) {
            orderedEntries = parseMainAsm();
        }
    }

    private List<BincludeEntry> parseMainAsm() throws IOException {
        List<BincludeEntry> entries = new ArrayList<>();
        Path s2asm = disasmRoot.resolve(mainAsmFile());

        if (!Files.exists(s2asm)) {
            return entries;
        }

        try (BufferedReader reader = Files.newBufferedReader(s2asm)) {
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
                    entries.add(new BincludeEntry(
                            matcher.group(1),
                            matcher.group(2),
                            lineNumber
                    ));
                    continue;
                }

                // 2. Unlabeled binclude — associate with pendingLabel if available
                Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (noLabelMatcher.find()) {
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;
                    entries.add(new BincludeEntry(
                            resolvedLabel,
                            noLabelMatcher.group(1),
                            resolvedLabel != null ? pendingLabelLine : lineNumber
                    ));
                    continue;
                }

                // 3. Assembly text include — register the include as an assembled entry and index labels within it.
                Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                if (includeMatcher.find()) {
                    pendingLabel = null;
                    String includeLabel = includeMatcher.group(1);
                    String includePath = includeMatcher.group(2);
                    BincludeEntry entry = BincludeEntry.assembly(includeLabel, includePath, lineNumber);
                    entries.add(entry);
                    indexIncludedAsmLabels(entry);
                    continue;
                }

                Matcher includeNoLabelMatcher = INCLUDE_NO_LABEL_PATTERN.matcher(line);
                if (includeNoLabelMatcher.find()) {
                    String resolvedLabel = pendingLabel;
                    pendingLabel = null;
                    String includePath = includeNoLabelMatcher.group(1);
                    BincludeEntry entry = BincludeEntry.assembly(
                            resolvedLabel,
                            includePath,
                            resolvedLabel != null ? pendingLabelLine : lineNumber);
                    entries.add(entry);
                    indexIncludedAsmLabels(entry);
                    continue;
                }

                // 4. Standalone label — store as pending for next binclude/include
                Matcher standaloneMatcher = STANDALONE_LABEL_PATTERN.matcher(line);
                if (standaloneMatcher.find()) {
                    pendingLabel = standaloneMatcher.group(1);
                    pendingLabelLine = lineNumber;
                    continue;
                }

                // 5. Alignment directives — don't clear pendingLabel (can appear between label and binclude)
                Matcher alignMatcher = ALIGN_PATTERN.matcher(line);
                if (alignMatcher.find()) {
                    int alignment = parseAlignment(alignMatcher.group(1));
                    if (alignment > 1) {
                        entries.add(BincludeEntry.alignment(alignment, lineNumber));
                    }
                    continue;
                }

                Matcher evenMatcher = EVEN_PATTERN.matcher(line);
                if (evenMatcher.find()) {
                    entries.add(BincludeEntry.alignment(2, lineNumber));
                    continue;
                }

                // 6. Any other line — clear pendingLabel
                pendingLabel = null;

                // Check for S2 palette macro (S1 uses bincludePalette, caught by BINCLUDE regex)
                if (hasPaletteMacro()) {
                    Matcher paletteMatcher = PALETTE_PATTERN.matcher(line);
                    if (paletteMatcher.find()) {
                        String label = paletteMatcher.group(1);
                        String path1 = paletteMatcher.group(2).trim();
                        String path2 = paletteMatcher.group(3) != null ? paletteMatcher.group(3).trim() : null;

                        // First palette file
                        entries.add(new BincludeEntry(label, "art/palettes/" + path1, lineNumber));

                        // Second palette file (if present) - same label, follows immediately
                        if (path2 != null && !path2.isEmpty()) {
                            entries.add(new BincludeEntry(label + "_2", "art/palettes/" + path2, lineNumber));
                        }
                    }
                }
            }
        }

        return entries;
    }

    private void indexIncludedAsmLabels(BincludeEntry entry) throws IOException {
        Path path = disasmRoot.resolve(entry.filePath);
        if (!Files.exists(path)) {
            return;
        }

        long offset = 0;
        long inferredBaseOffset = -1;
        List<IncludedAsmLabel> labels = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String label = parseStandaloneOrSameLineLabel(line);
                if (label != null) {
                    long labelAddress = parseAddressFromLabel(label);
                    if (labelAddress >= 0 && inferredBaseOffset < 0) {
                        inferredBaseOffset = labelAddress - offset;
                    }
                    labels.add(new IncludedAsmLabel(label, entry.label, entry.filePath, offset, -1));
                }
                offset += assembledDataSize(line);
            }
        }

        if (entry.label != null && inferredBaseOffset >= 0) {
            assemblyEntryBaseOffsets.putIfAbsent(entry.label, inferredBaseOffset);
        }
        for (IncludedAsmLabel label : labels) {
            includedAsmLabels.putIfAbsent(label.label(),
                    new IncludedAsmLabel(label.label(), entry.label, entry.filePath,
                            label.offsetWithinInclude(), inferredBaseOffset));
        }
    }

    private static String parseStandaloneOrSameLineLabel(String line) {
        Matcher standalone = STANDALONE_LABEL_PATTERN.matcher(line);
        if (standalone.find()) {
            return standalone.group(1);
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String candidate = line.substring(0, colon).trim();
        return candidate.matches("\\w+") ? candidate : null;
    }

    private static int assembledDataSize(String line) {
        String data = stripComment(line);
        int colon = data.indexOf(':');
        if (colon >= 0) {
            data = data.substring(colon + 1);
        }
        data = data.trim();
        if (data.isEmpty()) {
            return 0;
        }

        String lower = data.toLowerCase(Locale.ROOT);
        if (lower.startsWith("dc.b")) {
            return countDcOperands(data.substring(4), 1);
        }
        if (lower.startsWith("dc.w")) {
            return countDcOperands(data.substring(4), 2);
        }
        if (lower.startsWith("dc.l")) {
            return countDcOperands(data.substring(4), 4);
        }
        if (lower.startsWith("dcb.b")) {
            return countDcbBytes(data.substring(5), 1);
        }
        if (lower.startsWith("dcb.w")) {
            return countDcbBytes(data.substring(5), 2);
        }
        if (lower.startsWith("dcb.l")) {
            return countDcbBytes(data.substring(5), 4);
        }
        if (lower.startsWith("even")) {
            return 0;
        }
        return 0;
    }

    private static String stripComment(String line) {
        int semicolon = line.indexOf(';');
        return semicolon >= 0 ? line.substring(0, semicolon) : line;
    }

    private static int countDcOperands(String operands, int bytesPerOperand) {
        int count = 0;
        for (String ignored : splitOperands(operands)) {
            count++;
        }
        return count * bytesPerOperand;
    }

    private static int countDcbBytes(String operands, int bytesPerElement) {
        List<String> parts = splitOperands(operands);
        if (parts.isEmpty()) {
            return 0;
        }
        try {
            return parseAssemblerInteger(parts.get(0)) * bytesPerElement;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> splitOperands(String operands) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < operands.length(); i++) {
            char ch = operands.charAt(i);
            if (ch == '"') {
                inString = !inString;
            }
            if (ch == ',' && !inString) {
                String value = current.toString().trim();
                if (!value.isEmpty()) {
                    parts.add(value);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            parts.add(value);
        }
        return parts;
    }

    private static int parseAssemblerInteger(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16);
        }
        return Integer.parseInt(trimmed, 10);
    }

    private static long parseAddressFromLabel(String label) {
        int underscore = label.lastIndexOf('_');
        if (underscore < 0 || underscore == label.length() - 1) {
            return -1;
        }
        String suffix = label.substring(underscore + 1);
        if (!suffix.matches("[0-9A-Fa-f]{4,8}")) {
            return -1;
        }
        return Long.parseLong(suffix, 16);
    }

    private long getFileSize(String relativePath) {
        try {
            Path path = disasmRoot.resolve(relativePath);
            if (Files.exists(path)) {
                return Files.size(path);
            }
        } catch (IOException e) {
            // Ignore
        }
        return -1;
    }

    private long getEntrySize(BincludeEntry entry) {
        if (entry.assemblyData) {
            try {
                return getIncludedAsmSize(entry.filePath);
            } catch (IOException e) {
                return -1;
            }
        }
        return getFileSize(entry.filePath);
    }

    private long getIncludedAsmSize(String relativePath) throws IOException {
        Path path = disasmRoot.resolve(relativePath);
        if (!Files.exists(path)) {
            return -1;
        }
        long size = 0;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                size += assembledDataSize(line);
            }
        }
        return size;
    }

    /**
     * Check if a label is a known anchor (profile anchor).
     */
    public boolean isKnownAnchor(String label) {
        return anchorOffsets.containsKey(label);
    }

    /**
     * Get all known anchor offsets (profile anchors).
     */
    public Map<String, Long> getKnownAnchors() {
        return Collections.unmodifiableMap(anchorOffsets);
    }

    private static class BincludeEntry {
        final String label;
        final String filePath;
        final int lineNumber;
        final int alignment;
        final boolean assemblyData;

        BincludeEntry(String label, String filePath, int lineNumber) {
            this.label = label;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.alignment = 0;
            this.assemblyData = false;
        }

        private BincludeEntry(String label, String filePath, int lineNumber, boolean assemblyData) {
            this.label = label;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.alignment = 0;
            this.assemblyData = assemblyData;
        }

        private BincludeEntry(int alignment, int lineNumber) {
            this.label = null;
            this.filePath = null;
            this.lineNumber = lineNumber;
            this.alignment = alignment;
            this.assemblyData = false;
        }

        static BincludeEntry alignment(int alignment, int lineNumber) {
            return new BincludeEntry(alignment, lineNumber);
        }

        static BincludeEntry assembly(String label, String filePath, int lineNumber) {
            return new BincludeEntry(label, filePath, lineNumber, true);
        }

        boolean isAlignmentEntry() {
            return alignment > 0;
        }
    }

    private record IncludedAsmLabel(String label, String includeLabel, String includePath,
                                    long offsetWithinInclude, long includeBaseOffset) {
    }

    private static int parseAlignment(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16);
        }
        return Integer.parseInt(trimmed, 10);
    }

    private static long alignOffset(long offset, int alignment) {
        if (alignment <= 1) {
            return offset;
        }
        long mask = alignment - 1L;
        return (offset + mask) & ~mask;
    }

    /**
     * Details about how an offset was calculated.
     */
    public static class OffsetCalculation {
        public final String label;
        public final long offset;
        public final String anchorLabel;
        public final int distanceFromAnchor;
        public final boolean isAnchor;

        OffsetCalculation(String label, long offset, String anchorLabel,
                         int distanceFromAnchor, boolean isAnchor) {
            this.label = label;
            this.offset = offset;
            this.anchorLabel = anchorLabel;
            this.distanceFromAnchor = distanceFromAnchor;
            this.isAnchor = isAnchor;
        }
    }
}
