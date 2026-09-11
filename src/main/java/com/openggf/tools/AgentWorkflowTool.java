package com.openggf.tools;

import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Preflight Checklist Generator (Option 2 of {@code docs/agent-workflow/support-options.md}).
 *
 * <p>Prints a task-specific preflight checklist for an agent that is about to implement
 * an OpenGGF object. The checklist surfaces the discovery commands, registry/zone-set
 * resolution, likely disassembly labels, {@code RomOffsetFinder} commands, related existing
 * files, required guard tests, suggested focused tests, trace-replay relevance, and the
 * documentation files likely affected — so an external agent with no chat context can start
 * from a verified workflow.
 *
 * <p>Example invocation (matches the documented shape):
 * <pre>
 * mvn exec:java "-Dexec.mainClass=com.openggf.tools.AgentWorkflowTool" "-Dexec.args=object s3k MHZ 0x8A"
 * </pre>
 *
 * <h2>Design: pure logic separated from ROM access</h2>
 * All checklist text assembly, argument parsing, and S3K zone-set resolution live in this
 * class as <b>pure</b> functions (no ROM read, no OpenGL, no {@code RomOffsetFinder}
 * invocation). The tool never reads runtime asset bytes; it only <em>prints the
 * {@code RomOffsetFinder} commands an agent should run</em>. This keeps the logic
 * unit-testable without a ROM (see {@code TestAgentWorkflowTool}) and honours the
 * project rule that runtime assets come from the user ROM via the loader, while
 * {@code docs/} disassembly is for research/labels/offsets only.
 *
 * <p>Object-name and registry-status resolution are injected as functions so tests can
 * provide deterministic stubs. {@link #main(String[])} wires in the real, ROM-free
 * {@code Sonic3kObjectRegistry} name switch via reflection-free static defaults and degrades
 * gracefully (printing the command to run) if a resolver is unavailable.
 */
public final class AgentWorkflowTool {

    private AgentWorkflowTool() {}

    // ------------------------------------------------------------------
    // Argument model (pure)
    // ------------------------------------------------------------------

    /** Supported work types. Currently only {@code OBJECT} produces a full checklist. */
    public enum WorkType {
        OBJECT;

        static WorkType parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("workType is required (e.g. 'object')");
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "object", "badnik", "obj" -> OBJECT;
                default -> throw new IllegalArgumentException(
                        "Unsupported workType '" + raw + "'. Supported: object");
            };
        }
    }

    /** Supported games. */
    public enum Game {
        S1, S2, S3K;

        static Game parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("game is required (s1|s2|s3k)");
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "s1", "sonic1" -> S1;
                case "s2", "sonic2" -> S2;
                case "s3k", "sonic3k" -> S3K;
                default -> throw new IllegalArgumentException(
                        "Unsupported game '" + raw + "'. Supported: s1, s2, s3k");
            };
        }

        boolean isS3k() {
            return this == S3K;
        }
    }

    /**
     * Parsed, validated arguments for an object preflight request. Pure value object.
     *
     * @param workType   the work type (object)
     * @param game       the target game
     * @param zoneName   the raw zone token as supplied (e.g. "MHZ", "AIZ", "EHZ")
     * @param zoneId     resolved S3K zone id, or -1 when unknown / not S3K
     * @param objectId   parsed object id (hex {@code 0x..} or decimal), or -1 if absent
     * @param act        optional act (0-based or as supplied), or -1 if absent
     */
    public record Request(WorkType workType,
                          Game game,
                          String zoneName,
                          int zoneId,
                          int objectId,
                          int act) {

        /** True when the request is an S3K object task targeting a resolvable zone. */
        public boolean hasResolvedS3kZone() {
            return game.isS3k() && zoneId >= 0;
        }

        /** Zone-set resolution for S3K, or {@code null} when not S3K / unresolved. */
        public S3kZoneSet zoneSet() {
            return hasResolvedS3kZone() ? S3kZoneSet.forZone(zoneId) : null;
        }

        /** Formatted object id, e.g. {@code 0x8A}, or {@code "(none)"}. */
        public String objectIdHex() {
            return objectId < 0 ? "(none)" : String.format("0x%02X", objectId);
        }
    }

    // ------------------------------------------------------------------
    // Zone-name resolution (pure, S3K only — S1/S2 have no zone-set concept)
    // ------------------------------------------------------------------

    private static final Map<String, Integer> S3K_ZONE_IDS = buildS3kZoneMap();

    private static Map<String, Integer> buildS3kZoneMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("AIZ", Sonic3kZoneIds.ZONE_AIZ);
        m.put("HCZ", Sonic3kZoneIds.ZONE_HCZ);
        m.put("MGZ", Sonic3kZoneIds.ZONE_MGZ);
        m.put("CNZ", Sonic3kZoneIds.ZONE_CNZ);
        m.put("FBZ", Sonic3kZoneIds.ZONE_FBZ);
        m.put("ICZ", Sonic3kZoneIds.ZONE_ICZ);
        m.put("LBZ", Sonic3kZoneIds.ZONE_LBZ);
        m.put("MHZ", Sonic3kZoneIds.ZONE_MHZ);
        m.put("SOZ", Sonic3kZoneIds.ZONE_SOZ);
        m.put("LRZ", Sonic3kZoneIds.ZONE_LRZ);
        m.put("SSZ", Sonic3kZoneIds.ZONE_SSZ);
        m.put("DEZ", Sonic3kZoneIds.ZONE_DEZ);
        m.put("DDZ", Sonic3kZoneIds.ZONE_DDZ);
        // Competition zones (non-main).
        m.put("ALZ", Sonic3kZoneIds.ZONE_ALZ);
        m.put("BPZ", Sonic3kZoneIds.ZONE_BPZ);
        m.put("DPZ", Sonic3kZoneIds.ZONE_DPZ);
        m.put("CGZ", Sonic3kZoneIds.ZONE_CGZ);
        m.put("EMZ", Sonic3kZoneIds.ZONE_EMZ);
        // Special / bonus stages (non-main).
        m.put("HPZ", Sonic3kZoneIds.ZONE_HPZ);
        m.put("GUMBALL", Sonic3kZoneIds.ZONE_GUMBALL);
        m.put("GLOWINGSPHERE", Sonic3kZoneIds.ZONE_GLOWING_SPHERE);
        m.put("SLOTMACHINE", Sonic3kZoneIds.ZONE_SLOT_MACHINE);
        return m;
    }

    /** Resolves an S3K zone name (case-insensitive) to its zone id, or -1 if unknown. */
    public static int resolveS3kZoneId(String zoneName) {
        if (zoneName == null) {
            return -1;
        }
        return S3K_ZONE_IDS.getOrDefault(zoneName.toUpperCase(Locale.ROOT), -1);
    }

    private static int parseObjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String s = raw.trim();
        try {
            if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses positional arguments: {@code workType game zone [act/id] [act]}.
     *
     * <p>For an object workflow the documented shape is
     * {@code object s3k MHZ 0x8A}; the 4th token is the object id (hex or decimal).
     * An optional 5th token is the act. Pure: no ROM access.
     */
    public static Request parseArgs(String[] args) {
        if (args == null || args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: object <s1|s2|s3k> <zone> [objectId] [act]\n"
                            + "Example: object s3k MHZ 0x8A");
        }
        WorkType workType = WorkType.parse(args[0]);
        Game game = Game.parse(args[1]);
        String zoneName = args[2];
        int zoneId = game.isS3k() ? resolveS3kZoneId(zoneName) : -1;
        int objectId = args.length >= 4 ? parseObjectId(args[3]) : -1;
        int act = args.length >= 5 ? parseObjectId(args[4]) : -1;
        return new Request(workType, game, zoneName, zoneId, objectId, act);
    }

    // ------------------------------------------------------------------
    // Checklist assembly (pure)
    // ------------------------------------------------------------------

    /** Marker returned by a name resolver when the id is unknown. */
    public static final String UNKNOWN_NAME = "(unknown)";

    /**
     * Registry status for an object id, resolved purely from the registry name switch
     * plus a factory-presence probe.
     */
    public enum RegistryStatus {
        /** Has a dedicated factory (real instance class). */
        REGISTERED,
        /** Known name but renders via PlaceholderObjectInstance (no dedicated factory). */
        PLACEHOLDER_ONLY,
        /**
         * The id resolves to a known primary name in the active zone set, but this tool did
         * NOT probe the factory map (the registry's {@code factories} map is not exposed
         * without triggering object construction context). Whether a dedicated factory exists
         * or it falls back to {@code PlaceholderObjectInstance} must be confirmed by hand.
         */
        NAME_KNOWN_FACTORY_UNVERIFIED,
        /** No known name for this id in the active zone set. */
        UNKNOWN
    }

    /**
     * Builds the full preflight checklist text. Pure: takes already-parsed input plus two
     * injected resolvers and returns a deterministic string.
     *
     * @param req           parsed request
     * @param nameResolver  resolves an object id to its primary name in the active zone set;
     *                      should return {@link #UNKNOWN_NAME} when not found. May be null.
     * @param statusResolver resolves an object id to its {@link RegistryStatus}. May be null
     *                      (treated as {@link RegistryStatus#UNKNOWN}).
     */
    public static String buildChecklist(Request req,
                                        IntFunction<String> nameResolver,
                                        IntFunction<RegistryStatus> statusResolver) {
        StringBuilder sb = new StringBuilder();
        String gameId = gameIdToken(req.game());
        String objName = req.objectId() >= 0 && nameResolver != null
                ? nameResolver.apply(req.objectId())
                : UNKNOWN_NAME;
        RegistryStatus status = req.objectId() >= 0 && statusResolver != null
                ? statusResolver.apply(req.objectId())
                : RegistryStatus.UNKNOWN;

        line(sb, "=== OpenGGF Object Preflight Checklist ===");
        line(sb, "");

        // 1. Task header.
        line(sb, "## Task");
        line(sb, "  Work type : " + req.workType());
        line(sb, "  Game      : " + gameId);
        line(sb, "  Zone      : " + req.zoneName()
                + (req.hasResolvedS3kZone()
                        ? String.format(" (zone id 0x%02X)", req.zoneId())
                        : ""));
        line(sb, "  Act       : " + (req.act() >= 0 ? String.valueOf(req.act()) : "(unspecified)"));
        line(sb, "  Object id : " + req.objectIdHex());
        line(sb, "");

        // 2. S3K zone-set resolution.
        line(sb, "## S3K Zone-Set Resolution");
        if (req.game().isS3k()) {
            if (req.hasResolvedS3kZone()) {
                S3kZoneSet zs = req.zoneSet();
                line(sb, "  Resolved zone set: " + zs + describeZoneSet(zs, req.zoneId()));
                line(sb, "  Source: S3kZoneSet.forZone(0x" + Integer.toHexString(req.zoneId())
                        + ") -- zone-based, NOT game-mode-based.");
                line(sb, "  Reminder: object IDs above ~0x70 are remapped between S3KL and SKL;");
                line(sb, "  resolve names via Sonic3kObjectRegistry.getPrimaryName(id, zoneSet).");
                line(sb, "  Object spaces (locked-on cartridge): S3KL (zones 0-6) and SKL (zones 7-13)");
                line(sb, "  are the S&K-side object listings used at runtime. S3L (Sonic 3 standalone");
                line(sb, "  listing, in the s3.asm half) is NOT used by the locked-on S3K runtime --");
                line(sb, "  never resolve a name/address from S3L. Engine S3kZoneSet models S3KL+SKL only.");
            } else {
                line(sb, "  UNRESOLVED zone name '" + req.zoneName() + "'.");
                line(sb, "  Known S3K zones: " + String.join(", ", S3K_ZONE_IDS.keySet()));
            }
        } else {
            line(sb, "  N/A -- zone-set system is S3K-only (single object pointer table for "
                    + gameId + ").");
        }
        line(sb, "");

        // 3. Object id / name / registry status.
        line(sb, "## Object Identity & Registry Status");
        line(sb, "  Object id   : " + req.objectIdHex());
        line(sb, "  Primary name: " + objName);
        line(sb, "  Registry    : " + describeStatus(status, req.game()));
        line(sb, "");

        // 4. Likely disassembly labels.
        line(sb, "## Likely Disassembly Labels");
        if (req.game().isS3k()) {
            line(sb, "  Object code : Obj" + (req.objectId() >= 0
                    ? String.format("%02X", req.objectId()) : "<id>")
                    + " / " + (UNKNOWN_NAME.equals(objName) ? "<name>" : "Obj_" + objName));
            line(sb, "  Zone labels : search the zone prefix (e.g. " + req.zoneName() + "...) in");
            line(sb, "                docs/skdisasm/ -- use the sonic3k.asm (S&K-half) result ONLY.");
            line(sb, "  Art / map   : ArtKosM_/ArtNem_<...>, Map_obj.../Obj" + (req.objectId() >= 0
                    ? String.format("%02X", req.objectId()) : "<id>") + "_..., DPLC tables.");
        } else {
            line(sb, "  Object code : Obj" + (req.objectId() >= 0
                    ? String.format("%02X", req.objectId()) : "<id>") + " in docs/"
                    + (req.game() == Game.S1 ? "s1disasm" : "s2disasm") + "/.");
            line(sb, "  Art / map   : ArtNem_<...> / Map_obj... / inline spritePiece macros (S1).");
        }
        line(sb, "");

        // 5. RomOffsetFinder commands.
        line(sb, "## RomOffsetFinder Commands (run these; do NOT read bytes from docs/)");
        appendRomOffsetFinderCommands(sb, req, gameId, objName);
        line(sb, "");

        // 6. Related existing implementation & test files.
        line(sb, "## Related Existing Files To Inspect");
        appendRelatedFiles(sb, req);
        line(sb, "");

        // 7. Required guard tests.
        line(sb, "## Required Guard Tests (must stay green)");
        line(sb, "  - TestObjectServicesMigrationGuard  (no getInstance() in object code)");
        line(sb, "      src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java");
        line(sb, "  - TestNoServicesInObjectConstructors (no services() during construction)");
        line(sb, "      src/test/java/com/openggf/tests/TestNoServicesInObjectConstructors.java");
        line(sb, "  - TestConstructionContextGuard       (construction context wrapping)");
        line(sb, "      src/test/java/com/openggf/level/objects/TestConstructionContextGuard.java");
        line(sb, "  - TestNoDirectMapMutationsInGameplay (route tile edits via mutation pipeline)");
        line(sb, "      src/test/java/com/openggf/game/mutation/TestNoDirectMapMutationsInGameplay.java");
        if (req.game().isS3k()) {
            line(sb, "  - TestSonic3kPlcArtRegistry          (art/mapping/PLC registration sanity)");
            line(sb, "      src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java");
            line(sb, "  - TestPatternSpriteRendererCorruptionGuard (no pathological frame geometry)");
            line(sb, "      src/test/java/com/openggf/level/render/TestPatternSpriteRendererCorruptionGuard.java");
        }
        line(sb, "");

        // 8. Suggested focused tests.
        line(sb, "## Suggested Focused Tests");
        if (req.game().isS3k()) {
            line(sb, "  mvn \"-Dtest=TestSonic3kPlcArtRegistry\" test");
            line(sb, "  mvn \"-Dtest=TestObjectServicesMigrationGuard\" test");
            line(sb, "  Add a HeadlessTestRunner-based test for movement/collision if behavioural.");
        } else {
            line(sb, "  mvn \"-Dtest=TestObjectServicesMigrationGuard\" test");
            line(sb, "  Add a HeadlessTestRunner-based test for movement/collision if behavioural.");
        }
        line(sb, "  Reminder: JUnit 5 / Jupiter ONLY -- no org.junit.* (JUnit 4) imports/rules/runners.");
        line(sb, "");

        // 9. Trace replay relevance.
        line(sb, "## Trace Replay Workflow");
        line(sb, "  Relevance: " + traceRelevance(req));
        line(sb, "  If a trace diverges, model ROM state (object id/routine/status bits/event flag/");
        line(sb, "  profile) -- never branch on zone id/name, route, or frame number.");
        line(sb, "  Trace data is comparison-only: never hydrate/sync engine state from a trace.");
        line(sb, "  Use the trace-replay-bug-fixing skill; keep docs/status/trace-frontier-log.md current.");
        line(sb, "");

        // 10. Likely-affected docs.
        line(sb, "## Documentation Likely Affected (commit-trailer obligations)");
        line(sb, "  - CHANGELOG.md            (Changelog: updated -- engine src/main change)");
        line(sb, "  - docs/status/known-discrepancies.md / AGENTS_S3K.md S3K discrepancies if behaviour diverges");
        line(sb, "  - docs/status/trace-frontier-log.md if a trace frontier moves/regresses");
        line(sb, "  - rom-pitfalls.md (mirror BOTH .agents/skills/ and .claude/skills/) for reusable pitfalls");
        line(sb, "  - Trailers required on non-master commits: Changelog, Guide, Known-Discrepancies,");
        line(sb, "    S3K-Known-Discrepancies, Agent-Docs, Configuration-Docs, Skills.");
        line(sb, "");

        // Non-negotiable rules footer.
        line(sb, "## Non-Negotiable Rules");
        line(sb, "  - ROM-only runtime assets: never read asset bytes from docs/ disassembly.");
        if (req.game().isS3k()) {
            line(sb, "  - S3K addresses: prefer S&K-side (sonic3k.asm, < 0x200000); exhaust S&K variants");
            line(sb, "    first. Use an s3.asm reference only if the object has no S&K equivalent (rare; verify).");
        }
        line(sb, "  - Object code uses injected ObjectServices via services(); never getInstance();");
        line(sb, "    never call services() in constructors.");
        line(sb, "  - ROM positions use centre coords (getCentreX/Y / NativePositionOps), not getX/getY.");
        line(sb, "  - No game-name if/else for physics divergences; gate via the narrowest typed GameRules group.");

        return sb.toString();
    }

    private static void appendRomOffsetFinderCommands(StringBuilder sb,
                                                      Request req,
                                                      String gameId,
                                                      String objName) {
        String fqcn = "com.openggf.tools.disasm.RomOffsetFinder";
        String label = UNKNOWN_NAME.equals(objName) ? "<label>" : objName;
        String search = req.game().isS3k() ? req.zoneName() : "Obj" + (req.objectId() >= 0
                ? String.format("%02X", req.objectId()) : "");
        line(sb, "  # Object code / zone labels:");
        line(sb, "  mvn exec:java \"-Dexec.mainClass=" + fqcn + "\" \"-Dexec.args=--game "
                + gameId + " search " + search + "\" -q");
        line(sb, "  # Art (compressed pattern blocks):");
        line(sb, "  mvn exec:java \"-Dexec.mainClass=" + fqcn + "\" \"-Dexec.args=--game "
                + gameId + " find Art" + (req.game().isS3k() ? "KosM_" : "Nem_") + label + "\" -q");
        line(sb, "  # Mappings:");
        line(sb, "  mvn exec:java \"-Dexec.mainClass=" + fqcn + "\" \"-Dexec.args=--game "
                + gameId + " search Map_" + label + "\" -q");
        line(sb, "  # DPLCs:");
        line(sb, "  mvn exec:java \"-Dexec.mainClass=" + fqcn + "\" \"-Dexec.args=--game "
                + gameId + " search DPLC_" + label + "\" -q");
        if (req.game().isS3k()) {
            line(sb, "  # PLC tables / verify (S&K-half only):");
            line(sb, "  mvn exec:java \"-Dexec.mainClass=" + fqcn + "\" \"-Dexec.args=--game s3k plc "
                    + req.zoneName() + "\" -q");
            line(sb, "  # When a label returns BOTH sonic3k.asm and s3.asm hits, pick sonic3k.asm. If ONLY");
            line(sb, "  # s3.asm hits remain after trying S&K variants, that ref may be legitimate -- verify, don't loop.");
        }
    }

    private static void appendRelatedFiles(StringBuilder sb, Request req) {
        switch (req.game()) {
            case S3K -> {
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java"
                        + "  (factory registration + getPrimaryName)");
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java"
                        + "  (add ID constant)");
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java"
                        + "  (ROM addresses, S&K-half)");
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java"
                        + "  (buildLevelArtSheetFromRom)");
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java"
                        + "  (StandaloneArtEntry vs LevelArtEntry, getPlan)");
                line(sb, "  - src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java"
                        + "  (art keys)");
                line(sb, "  - Existing instances: src/main/java/com/openggf/game/sonic3k/objects/*Instance.java");
                line(sb, "  - Reusable base/util: src/main/java/com/openggf/level/objects/ "
                        + "(AbstractBadnikInstance, SubpixelMotion, DestructionEffects, ...)");
            }
            case S2 -> {
                line(sb, "  - src/main/java/com/openggf/game/sonic2/Sonic2ObjectRegistry.java");
                line(sb, "  - src/main/java/com/openggf/game/sonic2/constants/Sonic2ObjectIds.java");
                line(sb, "  - src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java");
                line(sb, "  - src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java (S2SpriteDataLoader)");
                line(sb, "  - Reusable base/util: src/main/java/com/openggf/level/objects/");
            }
            case S1 -> {
                line(sb, "  - src/main/java/com/openggf/game/sonic1/Sonic1ObjectRegistry.java");
                line(sb, "  - src/main/java/com/openggf/game/sonic1/constants/ (object IDs / constants)");
                line(sb, "  - src/main/java/com/openggf/game/sonic1/Sonic1ObjectArt.java (buildArtSheet)");
                line(sb, "  - Reusable base/util: src/main/java/com/openggf/level/objects/");
            }
        }
    }

    private static String traceRelevance(Request req) {
        // Traversal/hazard/badnik objects commonly affect physics traces; cosmetic ones less so.
        if (req.game().isS3k()) {
            return "LIKELY -- S3K route/physics work usually has *TraceReplay coverage. "
                    + "Check for an existing zone trace before/after changes.";
        }
        return "POSSIBLE -- if the object affects player physics/collision, run the matching "
                + "*TraceReplay test for the zone.";
    }

    private static String describeStatus(RegistryStatus status, Game game) {
        return switch (status) {
            case REGISTERED -> "REGISTERED (dedicated factory exists -- inspect the instance class)";
            case PLACEHOLDER_ONLY -> "PLACEHOLDER_ONLY (known name, renders via "
                    + "PlaceholderObjectInstance -- needs a real factory + instance)";
            case NAME_KNOWN_FACTORY_UNVERIFIED -> "NAME_KNOWN_FACTORY_UNVERIFIED (a primary name is "
                    + "known in this zone set; this tool did NOT probe the factory map -- open "
                    + "Sonic3kObjectRegistry.registerDefaultFactories() to confirm whether a dedicated "
                    + "factory/instance exists or it falls back to PlaceholderObjectInstance)";
            case UNKNOWN -> "UNKNOWN (no resolver available, or id not found -- run RomOffsetFinder "
                    + "and check the registry)";
        };
    }

    /**
     * Human description of the resolved zone set. {@code S3kZoneSet.forZone} returns SKL for every
     * zone id &gt; 6, which includes the non-main competition (0x0D-0x11), bonus (0x13-0x15), and
     * Hidden Palace (0x16) zones — those use specialized object handling, so the S3KL/SKL primary-name
     * table may not apply. Main SKL zones (MHZ-DDZ, 0x07-0x0C) keep the familiar label.
     */
    private static String describeZoneSet(S3kZoneSet zs, int zoneId) {
        if (zs == S3kZoneSet.S3KL) {
            return "  (S3K-Level set, zones 0-6: AIZ-LBZ)";
        }
        if (zoneId <= 0x0C) {
            return "  (SK-Level set, zones 7-13: MHZ-DDZ)";
        }
        return "  (SK-Level set by S3kZoneSet.forZone; NON-MAIN zone 0x"
                + Integer.toHexString(zoneId) + " -- competition/bonus/Hidden Palace use specialized "
                + "object handling, so the S3KL/SKL primary-name table may not apply. Verify in the disassembly.)";
    }

    private static String gameIdToken(Game game) {
        return switch (game) {
            case S1 -> "s1";
            case S2 -> "s2";
            case S3K -> "s3k";
        };
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    // ------------------------------------------------------------------
    // CLI entry point (wires ROM-free real resolvers; degrades gracefully)
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        Request req;
        try {
            req = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }

        IntFunction<String> nameResolver = defaultNameResolver(req);
        IntFunction<RegistryStatus> statusResolver = defaultStatusResolver(req);

        System.out.println(buildChecklist(req, nameResolver, statusResolver));
    }

    /**
     * Wires the real, ROM-free S3K registry name switch when available. The
     * {@code Sonic3kObjectRegistry.getPrimaryName(int, S3kZoneSet)} methods are pure switch
     * lookups (no ROM, no factory wiring), so they are safe to call here. Degrades to a
     * null resolver (UNKNOWN) for S1/S2 or on any failure -- the checklist then prints the
     * RomOffsetFinder command to run instead of a resolved name.
     */
    static IntFunction<String> defaultNameResolver(Request req) {
        if (!req.game().isS3k() || !req.hasResolvedS3kZone()) {
            return null;
        }
        try {
            com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry registry =
                    new com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry();
            S3kZoneSet zoneSet = req.zoneSet();
            return id -> {
                String name = registry.getPrimaryName(id, zoneSet);
                return (name == null || name.isBlank()) ? UNKNOWN_NAME : name;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Best-effort registry status without forcing factory wiring or a ROM read. The registry's
     * {@code factories} map is not exposed, and {@code create(spawn)} would construct an instance
     * (requiring object construction context), so this tool does NOT claim whether a dedicated
     * factory exists. A resolvable primary name yields {@link RegistryStatus#NAME_KNOWN_FACTORY_UNVERIFIED}
     * (the agent confirms factory presence by inspecting {@code Sonic3kObjectRegistry}); an
     * unresolvable id yields {@link RegistryStatus#UNKNOWN}. Returns null for non-S3K / unresolved zones.
     */
    static IntFunction<RegistryStatus> defaultStatusResolver(Request req) {
        IntFunction<String> names = defaultNameResolver(req);
        if (names == null) {
            return null;
        }
        return id -> {
            String name = names.apply(id);
            return UNKNOWN_NAME.equals(name)
                    ? RegistryStatus.UNKNOWN
                    : RegistryStatus.NAME_KNOWN_FACTORY_UNVERIFIED;
        };
    }
}
