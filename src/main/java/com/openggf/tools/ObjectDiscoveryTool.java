package com.openggf.tools;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.level.LevelData;
import com.openggf.level.objects.ObjectSpawn;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool to discover unimplemented objects by scanning all zone/act object placements
 * and comparing against the object registry for each game.
 * <p>
 * Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.openggf.sonic.tools.ObjectDiscoveryTool" -q
 * mvn exec:java -Dexec.mainClass="com.openggf.sonic.tools.ObjectDiscoveryTool" -Dexec.args="--game s1" -q
 * mvn exec:java -Dexec.mainClass="com.openggf.sonic.tools.ObjectDiscoveryTool" -Dexec.args="--game s2" -q
 * mvn exec:java -Dexec.mainClass="com.openggf.sonic.tools.ObjectDiscoveryTool" -Dexec.args="--game s3k" -q
 * </pre>
 */
public class ObjectDiscoveryTool {

    private final RomByteReader rom;
    private final GameObjectProfile profile;

    public ObjectDiscoveryTool(RomByteReader rom, GameObjectProfile profile) {
        this.rom = rom;
        this.profile = profile;
    }

    /**
     * Scan all levels and generate the discovery report.
     */
    public DiscoveryReport scan() {
        List<ZoneReport> zoneReports = new ArrayList<>();
        Map<String, ObjectStats> globalStats = new LinkedHashMap<>();

        for (LevelConfig level : profile.getLevels()) {
            ZoneReport report = scanLevel(level);
            zoneReports.add(report);

            for (ObjectUsage usage : report.objects) {
                // Composite key: same ID + same name merge; same ID + different name = separate entries
                String key = usage.objectId + ":" + usage.name;
                globalStats.computeIfAbsent(key,
                        k -> new ObjectStats(usage.objectId, usage.name,
                                getAliases(usage.objectId, level),
                                isImplemented(usage.objectId, level),
                                getCategory(usage.objectId, level)))
                        .addZoneUsage(level.shortName() + level.act(), usage.count, usage.subtypes, usage.plcIds);
            }
        }

        int implemented = (int) globalStats.values().stream().filter(s -> s.implemented).count();
        int unimplemented = globalStats.size() - implemented;

        return new DiscoveryReport(zoneReports, globalStats, implemented, unimplemented, LocalDateTime.now());
    }

    private ZoneReport scanLevel(LevelConfig level) {
        List<ObjectSpawn> spawns = profile.loadObjects(rom, level);

        // Build objectId -> plcIds mapping from PLC cross-references
        Map<Integer, Set<Integer>> objPlcMap = new HashMap<>();
        List<GameObjectProfile.PlcObjectMapping> plcMappings = profile.getPlcObjectMappings(rom, level);
        for (GameObjectProfile.PlcObjectMapping mapping : plcMappings) {
            for (int objId : mapping.objectIds()) {
                objPlcMap.computeIfAbsent(objId, k -> new TreeSet<>()).add(mapping.plcId());
            }
        }

        Map<Integer, List<ObjectSpawn>> byId = spawns.stream()
                .collect(Collectors.groupingBy(ObjectSpawn::objectId));

        List<ObjectUsage> usages = new ArrayList<>();
        for (Map.Entry<Integer, List<ObjectSpawn>> entry : byId.entrySet()) {
            int id = entry.getKey();
            List<ObjectSpawn> instances = entry.getValue();
            Set<Integer> subtypes = instances.stream()
                    .map(ObjectSpawn::subtype)
                    .collect(Collectors.toSet());
            Set<Integer> plcIds = objPlcMap.getOrDefault(id, Set.of());
            usages.add(new ObjectUsage(id, getName(id, level), instances.size(), isImplemented(id, level), subtypes, plcIds));
        }

        usages.sort(Comparator.comparingInt(u -> u.objectId));

        int implementedCount = (int) usages.stream().filter(u -> u.implemented).count();
        return new ZoneReport(level, usages, spawns.size(), implementedCount, usages.size() - implementedCount);
    }

    private String getName(int objectId, LevelConfig level) {
        Map<Integer, List<String>> names = profile.getObjectNames(level);
        List<String> nameList = names.get(objectId);
        if (nameList != null && !nameList.isEmpty()) {
            return nameList.get(0);
        }
        return String.format("%s_Obj_%02X", profile.gameId().toUpperCase(), objectId);
    }

    private List<String> getAliases(int objectId, LevelConfig level) {
        Map<Integer, List<String>> names = profile.getObjectNames(level);
        List<String> nameList = names.get(objectId);
        return (nameList != null && nameList.size() > 1) ? nameList.subList(1, nameList.size()) : List.of();
    }

    private boolean isImplemented(int objectId, LevelConfig level) {
        return profile.getImplementedIds(level).contains(objectId);
    }

    private String getCategory(int objectId, LevelConfig level) {
        if (profile.getBossIds(level).contains(objectId)) return "Boss";
        if (profile.getBadnikIds(level).contains(objectId)) return "Badnik";
        return "Object";
    }

    /**
     * Generate markdown report.
     */
    public String toMarkdown(DiscoveryReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(profile.gameName()).append(" Object Implementation Checklist\n\n");
        sb.append("Generated: ").append(report.scanTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        if (profile.coverageNote() != null) {
            sb.append(profile.coverageNote()).append("\n\n");
        }

        // Summary
        int total = report.implemented + report.unimplemented;
        double pct = total > 0 ? (report.implemented * 100.0 / total) : 0;
        sb.append("## Summary\n\n");
        sb.append(String.format("- **Total unique objects found:** %d%n", total));
        sb.append(String.format("- **Implemented:** %d (%.1f%%)%n", report.implemented, pct));
        sb.append(String.format("- **Unimplemented:** %d (%.1f%%)%n%n", report.unimplemented, 100 - pct));

        // Implemented objects
        sb.append("## Implemented Objects\n\n");
        sb.append("| ID | Name | Total Uses | PLC | Zones |\n");
        sb.append("|----|------|------------|-----|-------|\n");
        for (ObjectStats stats : report.globalStats.values()) {
            if (stats.implemented) {
                sb.append(String.format("| 0x%02X | %s | %d | %s | %s |%n",
                        stats.objectId, stats.name, stats.totalCount,
                        formatPlcIdsInline(stats.allPlcIds),
                        String.join(", ", stats.zoneUsage.keySet())));
            }
        }
        sb.append("\n");

        // Unimplemented by priority
        sb.append("## Unimplemented Objects (By Usage)\n\n");
        sb.append("| ID | Category | Name | Total Uses | PLC | Zones |\n");
        sb.append("|----|----------|------|------------|-----|-------|\n");
        List<ObjectStats> unimplemented = report.globalStats.values().stream()
                .filter(s -> !s.implemented)
                .sorted((a, b) -> Integer.compare(b.totalCount, a.totalCount))
                .toList();
        for (ObjectStats stats : unimplemented) {
            sb.append(String.format("| 0x%02X | %s | %s | %d | %s | %s |%n",
                    stats.objectId, stats.category, stats.name, stats.totalCount,
                    formatPlcIdsInline(stats.allPlcIds),
                    String.join(", ", stats.zoneUsage.keySet())));
        }
        sb.append("\n");

        // By Zone
        sb.append("---\n\n");
        sb.append("## By Zone\n\n");

        String currentZone = null;
        for (ZoneReport zr : report.zoneReports) {
            if (currentZone == null || !currentZone.equals(zr.level.fullName())) {
                currentZone = zr.level.fullName();
                sb.append("### ").append(zr.level.fullName()).append("\n\n");
            }

            sb.append("#### Act ").append(zr.level.act()).append("\n\n");
            sb.append(String.format("Total: %d objects | Implemented: %d | Unimplemented: %d%n%n",
                    zr.totalObjects, zr.implementedCount, zr.unimplementedCount));

            Map<String, List<ObjectUsage>> byCategory = zr.objects.stream()
                    .collect(Collectors.groupingBy(u -> getCategory(u.objectId, zr.level)));

            for (String category : List.of("Badnik", "Boss", "Object")) {
                List<ObjectUsage> items = byCategory.getOrDefault(category, List.of());

                List<DynamicBoss> dynamicBosses = List.of();
                if (category.equals("Boss") && profile.isFinalAct(zr.level)) {
                    dynamicBosses = profile.getDynamicBosses().getOrDefault(zr.level.shortName(), List.of());
                }

                if (items.isEmpty() && dynamicBosses.isEmpty()) continue;

                String plural = category.equals("Boss") ? "Bosses" : category + "s";
                sb.append("**").append(plural).append(":**\n");

                for (ObjectUsage u : items) {
                    String check = u.implemented ? "x" : " ";
                    String subtypeStr = u.subtypes.size() <= 3
                            ? u.subtypes.stream().map(s -> String.format("0x%02X", s)).collect(Collectors.joining(", "))
                            : u.subtypes.size() + " subtypes";
                    String plcStr = formatPlcIds(u.plcIds);
                    sb.append(String.format("- [%s] 0x%02X %s (x%d) [%s]%s%n",
                            check, u.objectId, u.name, u.count, subtypeStr, plcStr));
                }

                for (DynamicBoss boss : dynamicBosses) {
                    String check = isImplemented(boss.objectId(), zr.level) ? "x" : " ";
                    sb.append(String.format("- [%s] 0x%02X %s *(dynamic)* - %s%n",
                            check, boss.objectId(), boss.name(), boss.description()));
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /** Formats PLC IDs as a suffix string for per-zone entries (e.g. " PLC:0x0B,0x01"). */
    private static String formatPlcIds(Set<Integer> plcIds) {
        if (plcIds == null || plcIds.isEmpty()) return "";
        String ids = plcIds.stream()
                .map(id -> String.format("0x%02X", id))
                .collect(Collectors.joining(","));
        return " PLC:" + ids;
    }

    /** Formats PLC IDs inline for table cells (e.g. "0x0B, 0x01" or empty string). */
    private static String formatPlcIdsInline(Set<Integer> plcIds) {
        if (plcIds == null || plcIds.isEmpty()) return "";
        return plcIds.stream()
                .map(id -> String.format("0x%02X", id))
                .collect(Collectors.joining(", "));
    }

    private static final Map<String, GameObjectProfile> PROFILES = Map.of(
            "s1", new Sonic1ObjectProfile(),
            "s2", new Sonic2ObjectProfile(),
            "s3k", new Sonic3kObjectProfile()
    );

    public static void main(String[] args) {
        // Parse --game argument
        List<String> selectedGames = new ArrayList<>();
        String overrideRom = null;
        String overrideOutput = null;

        for (int i = 0; i < args.length; i++) {
            if ("--game".equals(args[i]) && i + 1 < args.length) {
                selectedGames.add(args[++i].toLowerCase());
            } else if (("--output".equals(args[i]) || "-o".equals(args[i])) && i + 1 < args.length) {
                overrideOutput = args[++i];
            } else if (!args[i].startsWith("-")) {
                overrideRom = args[i];
            }
        }

        // Default: run all available games
        if (selectedGames.isEmpty()) {
            selectedGames.addAll(List.of("s1", "s2", "s3k"));
        }

        boolean anyRan = false;
        for (String gameId : selectedGames) {
            GameObjectProfile profile = PROFILES.get(gameId);
            if (profile == null) {
                System.err.println("Unknown game: " + gameId + " (valid: s1, s2, s3k)");
                continue;
            }

            String romPath = overrideRom != null ? overrideRom : profile.defaultRomPath();
            String outputPath = overrideOutput != null ? overrideOutput : profile.outputFilename();

            Path romFile = Path.of(romPath);
            if (!Files.exists(romFile)) {
                System.err.println("[" + profile.gameName() + "] ROM not found: " + romPath + " - skipping");
                continue;
            }

            try (Rom rom = new Rom()) {
                if (!rom.open(romPath)) {
                    System.err.println("[" + profile.gameName() + "] Failed to open ROM: " + romPath);
                    continue;
                }

                RomByteReader reader = RomByteReader.fromRom(rom);
                ObjectDiscoveryTool tool = new ObjectDiscoveryTool(reader, profile);

                System.out.println("[" + profile.gameName() + "] Scanning all zones for objects...");
                DiscoveryReport report = tool.scan();

                String markdown = tool.toMarkdown(report);

                try (PrintWriter writer = new PrintWriter(outputPath)) {
                    writer.print(markdown);
                }

                System.out.println("[" + profile.gameName() + "] Report written to: " + outputPath);
                System.out.printf("[%s] Found %d unique objects: %d implemented, %d unimplemented%n",
                        profile.gameName(),
                        report.implemented + report.unimplemented, report.implemented, report.unimplemented);
                anyRan = true;

            } catch (IOException e) {
                System.err.println("[" + profile.gameName() + "] Error: " + e.getMessage());
                e.printStackTrace();
            }

            // Reset override output for multi-game mode (each game uses its own default)
            if (selectedGames.size() > 1) {
                overrideOutput = null;
            }
        }

        if (!anyRan) {
            System.err.println("No ROMs found. Place ROM files in the working directory.");
            System.exit(1);
        }
    }

    // Data classes

    public record LevelConfig(LevelData levelData, String shortName, String fullName, int act) {}

    public record ObjectUsage(int objectId, String name, int count, boolean implemented, Set<Integer> subtypes, Set<Integer> plcIds) {}

    public record ZoneReport(LevelConfig level, List<ObjectUsage> objects,
                             int totalObjects, int implementedCount, int unimplementedCount) {}

    public record DiscoveryReport(List<ZoneReport> zoneReports, Map<String, ObjectStats> globalStats,
                                  int implemented, int unimplemented, LocalDateTime scanTime) {}

    public record DynamicBoss(int objectId, String name, String description) {}

    public static class ObjectStats {
        final int objectId;
        final String name;
        final List<String> aliases;
        final boolean implemented;
        final String category;
        int totalCount;
        final Map<String, Integer> zoneUsage = new LinkedHashMap<>();
        final Set<Integer> allSubtypes = new TreeSet<>();
        final Set<Integer> allPlcIds = new TreeSet<>();

        ObjectStats(int objectId, String name, List<String> aliases, boolean implemented, String category) {
            this.objectId = objectId;
            this.name = name;
            this.aliases = aliases;
            this.implemented = implemented;
            this.category = category;
        }

        void addZoneUsage(String zone, int count, Set<Integer> subtypes, Set<Integer> plcIds) {
            zoneUsage.merge(zone, count, Integer::sum);
            totalCount += count;
            allSubtypes.addAll(subtypes);
            allPlcIds.addAll(plcIds);
        }
    }
}
