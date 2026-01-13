package uk.co.jamesj999.sonic.tools.disasm;

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
 * The s2disasm assembles files sequentially, so we can calculate any offset if we know:
 * 1. A nearby anchor offset (from Sonic2SpecialStageConstants or similar)
 * 2. The file sizes between the anchor and target
 */
public class RomOffsetCalculator {

    private static final Pattern BINCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*(?:BINCLUDE|binclude)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Known anchor offsets from verified ROM locations.
     * These are used as starting points for offset calculation.
     */
    private static final Map<String, Long> ANCHOR_OFFSETS = new LinkedHashMap<>();
    static {
        // Special stage art anchors (verified)
        ANCHOR_OFFSETS.put("ArtNem_SpecialBack", 0x0DCD68L);
        ANCHOR_OFFSETS.put("ArtNem_SpecialHUD", 0x0DD48AL);
        ANCHOR_OFFSETS.put("ArtNem_SpecialStart", 0x0DD790L);
        ANCHOR_OFFSETS.put("ArtNem_SpecialRings", 0x0DDA7EL);
        ANCHOR_OFFSETS.put("ArtNem_SpecialFlatShadow", 0x0DDFA4L);
        ANCHOR_OFFSETS.put("ArtNem_SpecialDiagShadow", 0x0DE05AL);
        ANCHOR_OFFSETS.put("ArtNem_SpecialSideShadow", 0x0DE120L);
        ANCHOR_OFFSETS.put("ArtNem_SpecialBomb", 0x0DE4BCL);
        ANCHOR_OFFSETS.put("ArtNem_SpecialEmerald", 0x0DE8ACL);
        ANCHOR_OFFSETS.put("ArtNem_SpecialMessages", 0x0DEAF4L);
        ANCHOR_OFFSETS.put("ArtNem_SpecialSonicAndTails", 0x0DEEAEL);

        // Track data anchors
        ANCHOR_OFFSETS.put("ArtKos_SpecialStage", 0x0DCA38L);

        // Other common anchors can be added here
    }

    private final Path disasmRoot;
    private List<BincludeEntry> orderedEntries;

    public RomOffsetCalculator(Path disasmRoot) {
        this.disasmRoot = disasmRoot;
    }

    public RomOffsetCalculator(String disasmRootPath) {
        this(Path.of(disasmRootPath));
    }

    /**
     * Calculate the ROM offset for a label.
     *
     * @param label The label to find (e.g., "ArtNem_SpecialStars")
     * @return The ROM offset, or -1 if not found
     */
    public long calculateOffset(String label) throws IOException {
        ensureEntriesLoaded();

        // First check if this label is a known anchor
        if (ANCHOR_OFFSETS.containsKey(label)) {
            return ANCHOR_OFFSETS.get(label);
        }

        // Find the target entry
        int targetIndex = -1;
        for (int i = 0; i < orderedEntries.size(); i++) {
            if (label.equals(orderedEntries.get(i).label)) {
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
        long anchorOffset = -1;

        // Search backwards for nearest anchor
        for (int i = targetIndex - 1; i >= 0; i--) {
            String entryLabel = orderedEntries.get(i).label;
            if (ANCHOR_OFFSETS.containsKey(entryLabel)) {
                anchorIndex = i;
                anchorLabel = entryLabel;
                anchorOffset = ANCHOR_OFFSETS.get(entryLabel);
                break;
            }
        }

        // If no anchor before, search forwards
        if (anchorIndex < 0) {
            for (int i = targetIndex + 1; i < orderedEntries.size(); i++) {
                String entryLabel = orderedEntries.get(i).label;
                if (ANCHOR_OFFSETS.containsKey(entryLabel)) {
                    anchorIndex = i;
                    anchorLabel = entryLabel;
                    anchorOffset = ANCHOR_OFFSETS.get(entryLabel);
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
                long fileSize = getFileSize(orderedEntries.get(i).filePath);
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
                long fileSize = getFileSize(orderedEntries.get(i).filePath);
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

        return offset;
    }

    /**
     * Get offset calculation details for debugging.
     */
    public OffsetCalculation getCalculationDetails(String label) throws IOException {
        ensureEntriesLoaded();

        if (ANCHOR_OFFSETS.containsKey(label)) {
            return new OffsetCalculation(label, ANCHOR_OFFSETS.get(label), label, 0, true);
        }

        int targetIndex = -1;
        for (int i = 0; i < orderedEntries.size(); i++) {
            if (label.equals(orderedEntries.get(i).label)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex < 0) {
            return null;
        }

        // Find nearest anchor
        int anchorIndex = -1;
        String anchorLabel = null;

        for (int i = targetIndex - 1; i >= 0; i--) {
            String entryLabel = orderedEntries.get(i).label;
            if (ANCHOR_OFFSETS.containsKey(entryLabel)) {
                anchorIndex = i;
                anchorLabel = entryLabel;
                break;
            }
        }

        if (anchorIndex < 0) {
            for (int i = targetIndex + 1; i < orderedEntries.size(); i++) {
                String entryLabel = orderedEntries.get(i).label;
                if (ANCHOR_OFFSETS.containsKey(entryLabel)) {
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
            orderedEntries = parseS2Asm();
        }
    }

    private List<BincludeEntry> parseS2Asm() throws IOException {
        List<BincludeEntry> entries = new ArrayList<>();
        Path s2asm = disasmRoot.resolve("s2.asm");

        if (!Files.exists(s2asm)) {
            return entries;
        }

        try (BufferedReader reader = Files.newBufferedReader(s2asm)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    entries.add(new BincludeEntry(
                            matcher.group(1),
                            matcher.group(2),
                            lineNumber
                    ));
                }
            }
        }

        return entries;
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

    /**
     * Check if a label is a known anchor.
     */
    public static boolean isKnownAnchor(String label) {
        return ANCHOR_OFFSETS.containsKey(label);
    }

    /**
     * Get all known anchor offsets.
     */
    public static Map<String, Long> getKnownAnchors() {
        return Collections.unmodifiableMap(ANCHOR_OFFSETS);
    }

    private static class BincludeEntry {
        final String label;
        final String filePath;
        final int lineNumber;

        BincludeEntry(String label, String filePath, int lineNumber) {
            this.label = label;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
        }
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
