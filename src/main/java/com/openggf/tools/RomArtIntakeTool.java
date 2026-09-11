package com.openggf.tools;

import com.openggf.tools.disasm.DisassemblySearchResult;
import com.openggf.tools.disasm.RomOffsetFinder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RomArtIntakeTool — S3K ROM-backed object art / mapping / PLC intake helper.
 *
 * <p>This tool implements Option 3 of {@code docs/agent-workflow/support-options.md}. It targets the
 * most error-prone S3K asset path: ROM-backed object art and mappings. It does <b>not</b> duplicate
 * offset-finding logic — it wraps {@link RomOffsetFinder} (always with the {@code --game s3k} profile)
 * and layers S3K-specific guardrails and recommendations on top of the raw search results.
 *
 * <p>What it does, per the Option 3 acceptance criteria:
 * <ul>
 *   <li>Always searches with the S3K profile ({@code RomOffsetFinder.GameProfile.sonic3k()}).</li>
 *   <li>Loudly REJECTS / flags {@code s3.asm}-sourced labels (the Sonic 3 standalone / S3L half) for S3K
 *       locked-on runtime constants — the locked-on S3KL/SKL runtime only references the S&amp;K half
 *       ({@code sonic3k.asm}). NOTE: the label <b>search</b> path classifies by <b>source file</b>, not by
 *       a resolved address — {@link DisassemblySearchResult} carries no ROM offset. The complementary
 *       {@code >= 0x200000} <em>address</em> rule is encoded by {@link #isAcceptableForS3kRuntime(long, String)}
 *       / {@link #halfForAddress(long)} for use once you resolve a concrete offset (e.g. via the
 *       {@code verify}/{@code find} command this tool prints).</li>
 *   <li>Identifies whether a label result comes from {@code sonic3k.asm} or {@code s3.asm}.</li>
 *   <li>Surfaces mapping/art/PLC relationships discovered in the disassembly.</li>
 *   <li>Recommends {@code StandaloneArtEntry} vs {@code LevelArtEntry} for
 *       {@code Sonic3kPlcArtRegistry} registration.</li>
 *   <li>Prints candidate {@code Sonic3kConstants} names and registration hints.</li>
 *   <li>Suggests running {@code TestSonic3kPlcArtRegistry} and
 *       {@code TestPatternSpriteRendererCorruptionGuard}.</li>
 * </ul>
 *
 * <p><b>ROM-only runtime assets:</b> this is a research / intake helper. It searches the disassembly
 * for labels and offsets only. The actual runtime asset bytes must always be loaded from the
 * user-supplied ROM through the engine's loaders — never read from {@code docs/}.
 *
 * <h2>Invocation</h2>
 * <pre>
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.RomArtIntakeTool" "-Dexec.args=ArtNem_AIZSwingVine"
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.RomArtIntakeTool" "-Dexec.args=--label Map_MHZPollen"
 * </pre>
 *
 * <p>The pure decision logic (S3-standalone address/source rejection,
 * sonic3k.asm-vs-s3.asm half detection, StandaloneArtEntry-vs-LevelArtEntry recommendation,
 * constant-name suggestion) is isolated into static methods that need no ROM and are unit-tested
 * by {@code TestRomArtIntakeTool}. The CLI applies the source-file guard because label-search
 * results do not carry numeric ROM offsets.
 */
public final class RomArtIntakeTool {

    /** Boundary between the locked-on ROM's S&amp;K half and the Sonic 3 standalone half. */
    public static final long SK_HALF_LIMIT = 0x200000L;

    /** S&amp;K-side main asm file (authoritative source for S3K runtime constants). */
    public static final String SK_ASM_FILE = "sonic3k.asm";

    /** Sonic 3 standalone main asm file (must NOT be used for S3K runtime constants). */
    public static final String S3_STANDALONE_ASM_FILE = "s3.asm";

    private RomArtIntakeTool() {
    }

    // ---------------------------------------------------------------------
    // Pure decision logic (no ROM, no disassembly I/O) — unit-tested.
    // ---------------------------------------------------------------------

    /** Which half of the locked-on ROM a label/offset belongs to. */
    public enum RomHalf {
        /** S&amp;K half (&lt; 0x200000, sonic3k.asm). Correct for S3K runtime constants. */
        SK,
        /** Sonic 3 standalone half (&gt;= 0x200000, s3.asm). Prefer the S&amp;K half; use only as a fallback when no S&amp;K equivalent exists. */
        S3_STANDALONE,
        /** Source/offset not yet known. */
        UNKNOWN
    }

    /** What kind of {@code Sonic3kPlcArtRegistry} entry to register a label as. */
    public enum ArtEntryKind {
        /**
         * Standalone art: badnik/boss/object art compressed in ROM, decompressed at load time
         * (NEMESIS / KOSINSKI / UNCOMPRESSED). Registered as {@code StandaloneArtEntry}.
         */
        STANDALONE,
        /**
         * Level art: object sprite that references patterns already resident in level VRAM
         * (built via a {@code buildLevelArtSheet*} method, art tiles come from the level art).
         * Registered as {@code LevelArtEntry}.
         */
        LEVEL,
        /** Cannot decide from the label alone. */
        UNKNOWN
    }

    /**
     * Detect which ROM half a numeric address belongs to. Any address at or above
     * {@link #SK_HALF_LIMIT} is the Sonic 3 standalone half (prefer an S&amp;K equivalent; fallback only when none exists).
     */
    public static RomHalf halfForAddress(long address) {
        if (address < 0) {
            return RomHalf.UNKNOWN;
        }
        return address >= SK_HALF_LIMIT ? RomHalf.S3_STANDALONE : RomHalf.SK;
    }

    /**
     * Detect which ROM half a disassembly source file belongs to. A label sourced from
     * {@code s3.asm} (or any path under an {@code s3} standalone tree) is the Sonic 3 standalone half.
     *
     * @param asmFilePath the relativized asm path from {@link DisassemblySearchResult#getAsmFilePath()}
     */
    public static RomHalf halfForSource(String asmFilePath) {
        if (asmFilePath == null || asmFilePath.isBlank()) {
            return RomHalf.UNKNOWN;
        }
        String normalized = asmFilePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        // Check the standalone marker first: "s3.asm" must not be misread as a "sonic3k.asm" match.
        if (normalized.endsWith("/" + S3_STANDALONE_ASM_FILE) || normalized.equals(S3_STANDALONE_ASM_FILE)) {
            return RomHalf.S3_STANDALONE;
        }
        if (normalized.contains(SK_ASM_FILE)) {
            return RomHalf.SK;
        }
        // Files included from sonic3k.asm (mappings, etc.) live under the same tree; treat unknown asm
        // files conservatively as UNKNOWN so the caller verifies the offset rather than trusting it.
        return RomHalf.UNKNOWN;
    }

    /**
     * True if the given address is in the Sonic 3 standalone half. Prefer an S&amp;K-side equivalent;
     * such an address is acceptable only as a documented fallback when no S&amp;K equivalent exists.
     */
    public static boolean isS3StandaloneAddress(long address) {
        return halfForAddress(address) == RomHalf.S3_STANDALONE;
    }

    /**
     * True if the given disassembly source file is the Sonic 3 standalone half. Prefer an S&amp;K-side
     * equivalent; such a source is acceptable only as a documented fallback when no S&amp;K equivalent exists.
     */
    public static boolean isS3StandaloneSource(String asmFilePath) {
        return halfForSource(asmFilePath) == RomHalf.S3_STANDALONE;
    }

    /**
     * True if this label result is the PREFERRED S&amp;K-side source for an S3K locked-on runtime
     * constant: the S&amp;K half by both address (when known) and source file (when known), and not the
     * standalone half by either signal. NOTE: a Sonic 3 standalone reference can still be the correct,
     * legitimate one for the rare object that has no S&amp;K equivalent. This returns {@code false} for
     * such a reference, so treat {@code false} as "verify by hand", not "forbidden".
     *
     * @param address     resolved ROM address, or a negative value if unknown
     * @param asmFilePath disassembly source path, or null if unknown
     */
    public static boolean isAcceptableForS3kRuntime(long address, String asmFilePath) {
        if (isS3StandaloneAddress(address) || isS3StandaloneSource(asmFilePath)) {
            return false;
        }
        boolean addrKnown = address >= 0;
        boolean sourceKnown = asmFilePath != null && !asmFilePath.isBlank();
        // At least one positive S&K signal is required to call it acceptable.
        boolean addrIsSk = addrKnown && halfForAddress(address) == RomHalf.SK;
        boolean sourceIsSk = sourceKnown && halfForSource(asmFilePath) == RomHalf.SK;
        return addrIsSk || sourceIsSk;
    }

    /**
     * Recommend {@code StandaloneArtEntry} vs {@code LevelArtEntry} from a label name.
     *
     * <p>Heuristic (label naming conventions in skdisasm / {@code Sonic3kObjectArtKeys}):
     * <ul>
     *   <li>{@code ArtNem_*} / {@code ArtKos_*} / {@code ArtKosM_*} / {@code ArtUnc_*} — compressed or
     *       loose object art decompressed at load: {@code STANDALONE}.</li>
     *   <li>{@code Map_*} / {@code MapUnc_*} alone, or a {@code Blk*}/level-tile-backed sprite, where the
     *       art tiles come from the resident level art: {@code LEVEL}.</li>
     * </ul>
     * A bare {@code Map_*} label is treated as {@code LEVEL} because the art it references is normally the
     * zone level art (built through {@code buildLevelArtSheetFromRom}); an {@code Art*} label is treated as
     * {@code STANDALONE} because it names its own decompressible art block.
     */
    public static ArtEntryKind recommendArtEntryKind(String label) {
        if (label == null || label.isBlank()) {
            return ArtEntryKind.UNKNOWN;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.startsWith("artnem_") || lower.startsWith("artkos_")
                || lower.startsWith("artkosm_") || lower.startsWith("artunc_")
                || lower.startsWith("art_") || lower.startsWith("nem_") || lower.startsWith("kos_")) {
            return ArtEntryKind.STANDALONE;
        }
        if (lower.startsWith("map_") || lower.startsWith("mapunc_")
                || lower.startsWith("mapeni_") || lower.startsWith("blk")) {
            return ArtEntryKind.LEVEL;
        }
        return ArtEntryKind.UNKNOWN;
    }

    /**
     * Suggest a {@code Sonic3kConstants} field name from a disassembly label.
     *
     * <p>Convention (verified against {@code Sonic3kConstants.java}):
     * {@code ArtNem_AIZSwingVine -> ART_NEM_AIZ_SWING_VINE_ADDR},
     * {@code Map_MHZPollen -> MAP_MHZ_POLLEN_ADDR},
     * {@code DPLC_SSEntryRing -> DPLC_SS_ENTRY_RING_ADDR}.
     *
     * <p>The leading {@code Art}/{@code Map}/{@code DPLC}/{@code PLC} category and any compression
     * sub-prefix become a leading underscore-separated token group; the remainder is split on
     * camel-case / digit boundaries into UPPER_SNAKE; an {@code _ADDR} suffix is appended.
     */
    public static String suggestConstantName(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        // Split on existing underscores first (e.g. ArtNem_AIZSwingVine -> ["ArtNem", "AIZSwingVine"]).
        String[] underscoreParts = label.split("_");
        List<String> tokens = new ArrayList<>();
        for (String part : underscoreParts) {
            if (part.isEmpty()) {
                continue;
            }
            tokens.addAll(splitCamelAndDigits(part));
        }
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(tokens.get(i).toUpperCase(Locale.ROOT));
        }
        sb.append("_ADDR");
        return sb.toString();
    }

    /**
     * Split a camel-case / digit-mixed token into UPPER_SNAKE-friendly pieces, keeping runs of
     * consecutive capitals (zone acronyms like AIZ, MHZ, SS) intact.
     */
    static List<String> splitCamelAndDigits(String token) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char[] chars = token.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (cur.length() == 0) {
                cur.append(c);
                continue;
            }
            char prev = chars[i - 1];
            boolean boundary = false;
            if (Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                // aB  -> split before B  (e.g. SwingVine -> Swing | Vine)
                boundary = true;
            } else if (Character.isUpperCase(c) && i + 1 < chars.length
                    && Character.isLowerCase(chars[i + 1]) && Character.isUpperCase(prev)) {
                // ABCd -> split before C (acronym AB | Cd, e.g. AIZSwing -> AIZ | Swing)
                boundary = true;
            } else if (Character.isDigit(c) != Character.isDigit(prev)) {
                // letter<->digit boundary (e.g. AIZ1 -> AIZ | 1)
                boundary = true;
            }
            if (boundary) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // CLI entry point — wraps RomOffsetFinder (S3K profile only).
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--label".equals(args[i]) && i + 1 < args.length) {
                positional.add(args[++i]);
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage();
                return;
            } else {
                positional.add(args[i]);
            }
        }

        if (positional.isEmpty()) {
            printUsage();
            return;
        }

        // Always use the S3K (S&K-side) profile. This is non-negotiable per the project rules.
        RomOffsetFinder.GameProfile profile = RomOffsetFinder.GameProfile.sonic3k();
        String disasmPath = System.getProperty("disasm.path", profile.defaultDisasmPath());
        String romPath = System.getProperty("rom.path", profile.defaultRomPath());

        System.out.println("=== RomArtIntakeTool (S3K, S&K-side only) ===");
        System.out.println("Disasm path  : " + disasmPath);
        System.out.println("ROM path     : " + romPath);
        System.out.println("Labels       : " + String.join(", ", positional));
        System.out.println("Object spaces: S3KL (zones 0-6) + SKL (zones 7-13) are the S&K-side listings the");
        System.out.println("               locked-on runtime normally uses -- prefer the S&K (sonic3k.asm) half.");
        System.out.println("               S3L (Sonic 3 standalone, s3.asm half) is flagged below as a CAUTION,");
        System.out.println("               not a hard reject: it is the legitimate reference for the rare object");
        System.out.println("               that has no S&K equivalent (verify before use).");
        System.out.println();

        RomOffsetFinder finder = null;
        try {
            finder = new RomOffsetFinder(disasmPath, romPath, profile);
            // Process EVERY requested label, not just the first — supports batch art intake.
            for (String label : positional) {
                processLabel(finder, label);
            }
        } catch (IOException e) {
            System.err.println("Failed to search disassembly: " + e.getMessage());
            System.err.println("(Disasm tree is research-only. Verify docs/skdisasm exists.)");
            return;
        } finally {
            if (finder != null) {
                finder.close();
            }
        }

        printRelationshipAndGuardFooter();
    }

    /** Searches one label and prints its per-result intake verdict. */
    private static void processLabel(RomOffsetFinder finder, String label) throws IOException {
        System.out.println("############################################################");
        System.out.println("Label query : " + label);
        System.out.println();

        List<DisassemblySearchResult> results = finder.search(label);
        if (results.isEmpty()) {
            System.out.println("No matching labels found in skdisasm for: " + label);
            System.out.println("Tip: try other S&K-side (sonic3k.asm) label variants first. If none exists,");
            System.out.println("     an s3.asm reference may be the one this object actually uses -- verify it.");
            System.out.println();
            return;
        }

        boolean anySk = false;
        boolean anyS3Half = false;

        for (DisassemblySearchResult r : results) {
            String resolvedLabel = r.getLabel() != null ? r.getLabel() : "(unlabeled)";
            String src = r.getAsmFilePath();
            RomHalf sourceHalf = halfForSource(src);

            System.out.println("------------------------------------------------------------");
            System.out.println("Label   : " + resolvedLabel);
            System.out.println("Source  : " + src + ":" + r.getAsmLineNumber());
            System.out.println("File    : " + (r.getFileName() != null ? r.getFileName() : "(label-only)"));
            System.out.println("Compress: " + (r.getCompressionType() != null
                    ? r.getCompressionType().getDisplayName() : "N/A"));
            System.out.println("ROM half: " + describeHalf(sourceHalf));

            if (sourceHalf == RomHalf.S3_STANDALONE) {
                anyS3Half = true;
                System.out.println("CAUTION : sourced from s3.asm (Sonic 3 standalone / S3L half). Prefer an");
                System.out.println("          equivalent sonic3k.asm (S&K-side) label if one exists. But if none");
                System.out.println("          exists, some S3K objects DO reference S3-half assets directly --");
                System.out.println("          then this IS the correct reference; verify the object's code points");
                System.out.println("          here before committing the constant. (Hints printed below anyway.)");
            } else if (sourceHalf == RomHalf.SK) {
                anySk = true;
            }

            ArtEntryKind kind = recommendArtEntryKind(resolvedLabel);
            String constName = suggestConstantName(resolvedLabel);

            System.out.println("Entry   : " + describeEntryKind(kind));
            if (!constName.isEmpty()) {
                System.out.println("Constant: public static final int " + constName + " = 0x...;  // "
                        + resolvedLabel);
            }
            System.out.println("Registry: " + registrationHint(kind, resolvedLabel));
        }

        System.out.println("------------------------------------------------------------");

        if (anyS3Half && !anySk) {
            System.out.println("NOTE: no S&K-side (sonic3k.asm) match for '" + label + "' -- only the Sonic 3");
            System.out.println("      standalone (s3.asm / S3L) half. First try other S&K-side label variants");
            System.out.println("      (zone-specific prefix/suffix, Levels/{ZONE}/ paths). If there is genuinely");
            System.out.println("      no S&K equivalent, the S3-half reference is the one this object uses -- use");
            System.out.println("      it after verifying the object's code references that address. Do not loop");
            System.out.println("      forever hunting a non-existent S&K variant (this is rare, but it happens).");
        }
        System.out.println();
    }

    private static void printRelationshipAndGuardFooter() {
        System.out.println("Mapping / art / PLC relationship:");
        System.out.println("  - StandaloneArtEntry pairs: art addr + compression + mapping addr + palette");
        System.out.println("    (+ optional DPLC addr). Use RomOffsetFinder 'find'/'verify'/'plc' to resolve");
        System.out.println("    each address against the ROM before committing a constant.");
        System.out.println("  - LevelArtEntry pairs: mapping addr + art tile base + palette, art tiles come");
        System.out.println("    from the resident zone level art.");
        System.out.println();
        System.out.println("After registering in Sonic3kPlcArtRegistry, run the art-corruption guards:");
        System.out.println("  mvn \"-Dtest=com.openggf.game.sonic3k.TestSonic3kPlcArtRegistry\" test");
        System.out.println("  mvn \"-Dtest=com.openggf.level.render.TestPatternSpriteRendererCorruptionGuard\" test");
        System.out.println();
        System.out.println("Address rule: this tool classifies each match by SOURCE FILE (sonic3k.asm vs s3.asm),");
        System.out.println("          NOT by a resolved address (a label search carries no ROM offset). After you");
        System.out.println("          resolve a concrete offset with 'verify'/'find', confirm it is < 0x200000");
        System.out.println("          (S&K half) -- isAcceptableForS3kRuntime() / halfForAddress() encode that rule.");
        System.out.println();
        System.out.println("Reminder: runtime asset bytes must come from the user ROM via the loader.");
        System.out.println("          docs/skdisasm is for labels and offsets only.");
    }

    private static String describeHalf(RomHalf half) {
        return switch (half) {
            case SK -> "S&K (sonic3k.asm) — OK for S3K runtime";
            case S3_STANDALONE -> "Sonic 3 standalone (s3.asm / S3L) — prefer an S&K equivalent; use only if none exists (verify)";
            case UNKNOWN -> "unknown — verify the resolved offset before trusting it";
        };
    }

    private static String describeEntryKind(ArtEntryKind kind) {
        return switch (kind) {
            case STANDALONE -> "StandaloneArtEntry (decompressed object art block)";
            case LEVEL -> "LevelArtEntry (references resident zone level art)";
            case UNKNOWN -> "unknown — inspect the object's make_art_tile() call to decide";
        };
    }

    private static String registrationHint(ArtEntryKind kind, String label) {
        return switch (kind) {
            case STANDALONE -> "new StandaloneArtEntry(Sonic3kObjectArtKeys.<KEY>, "
                    + "Sonic3kConstants.<ART_ADDR>, CompressionType.<NEMESIS|KOSINSKI|UNCOMPRESSED>, "
                    + "<artSize>, Sonic3kConstants.<MAP_ADDR>, <palette 0-3>, <DPLC addr or -1>)";
            case LEVEL -> "new LevelArtEntry(Sonic3kObjectArtKeys.<KEY>, "
                    + "Sonic3kConstants.<MAP_ADDR>, <artTileBase>, <palette 0-3>, "
                    + "\"<builderName or null>\")";
            case UNKNOWN -> "decide StandaloneArtEntry vs LevelArtEntry from the object's art loading path";
        };
    }

    private static void printUsage() {
        System.out.println("RomArtIntakeTool — S3K ROM-backed object art/mapping/PLC intake helper");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  RomArtIntakeTool <label> [<label> ...]");
        System.out.println("  RomArtIntakeTool --label <label>");
        System.out.println();
        System.out.println("Always uses the S3K (S&K-side) RomOffsetFinder profile and rejects s3.asm-sourced");
        System.out.println("labels (the Sonic 3 standalone / S3L half). The search path classifies by source");
        System.out.println("file, not by address; confirm the resolved offset is < 0x200000 when you verify it.");
        System.out.println("All positional labels are processed.");
        System.out.println();
        System.out.println("System properties: -Ddisasm.path=... -Drom.path=...");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  mvn exec:java \"-Dexec.mainClass=com.openggf.tools.RomArtIntakeTool\" "
                + "\"-Dexec.args=ArtNem_AIZSwingVine\"");
    }
}
