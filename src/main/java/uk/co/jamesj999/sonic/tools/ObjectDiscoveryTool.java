package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectPlacement;
import uk.co.jamesj999.sonic.game.sonic2.ZoneAct;
import uk.co.jamesj999.sonic.game.sonic2.objects.Sonic2ObjectRegistryData;
import uk.co.jamesj999.sonic.level.LevelData;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants.LEVEL_SELECT_ADDR;

/**
 * Tool to discover unimplemented objects by scanning all zone/act object placements
 * and comparing against the Sonic2ObjectRegistry.
 * <p>
 * Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.ObjectDiscoveryTool" -q
 * </pre>
 */
public class ObjectDiscoveryTool {
    private static final String DEFAULT_ROM_PATH = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";

    // Level configurations using LevelData enum for correct ROM table lookups
    private static final List<LevelConfig> LEVELS = List.of(
            new LevelConfig(LevelData.EMERALD_HILL_1, "EHZ", "Emerald Hill Zone", 1),
            new LevelConfig(LevelData.EMERALD_HILL_2, "EHZ", "Emerald Hill Zone", 2),
            new LevelConfig(LevelData.CHEMICAL_PLANT_1, "CPZ", "Chemical Plant Zone", 1),
            new LevelConfig(LevelData.CHEMICAL_PLANT_2, "CPZ", "Chemical Plant Zone", 2),
            new LevelConfig(LevelData.AQUATIC_RUIN_1, "ARZ", "Aquatic Ruin Zone", 1),
            new LevelConfig(LevelData.AQUATIC_RUIN_2, "ARZ", "Aquatic Ruin Zone", 2),
            new LevelConfig(LevelData.CASINO_NIGHT_1, "CNZ", "Casino Night Zone", 1),
            new LevelConfig(LevelData.CASINO_NIGHT_2, "CNZ", "Casino Night Zone", 2),
            new LevelConfig(LevelData.HILL_TOP_1, "HTZ", "Hill Top Zone", 1),
            new LevelConfig(LevelData.HILL_TOP_2, "HTZ", "Hill Top Zone", 2),
            new LevelConfig(LevelData.MYSTIC_CAVE_1, "MCZ", "Mystic Cave Zone", 1),
            new LevelConfig(LevelData.MYSTIC_CAVE_2, "MCZ", "Mystic Cave Zone", 2),
            new LevelConfig(LevelData.OIL_OCEAN_1, "OOZ", "Oil Ocean Zone", 1),
            new LevelConfig(LevelData.OIL_OCEAN_2, "OOZ", "Oil Ocean Zone", 2),
            new LevelConfig(LevelData.METROPOLIS_1, "MTZ", "Metropolis Zone", 1),
            new LevelConfig(LevelData.METROPOLIS_2, "MTZ", "Metropolis Zone", 2),
            new LevelConfig(LevelData.METROPOLIS_3, "MTZ", "Metropolis Zone", 3),
            new LevelConfig(LevelData.SKY_CHASE, "SCZ", "Sky Chase Zone", 1),
            new LevelConfig(LevelData.WING_FORTRESS, "WFZ", "Wing Fortress Zone", 1),
            new LevelConfig(LevelData.DEATH_EGG, "DEZ", "Death Egg Zone", 1)
    );

    // Object IDs that have implementations (factory or manager-based)
    private static final Set<Integer> IMPLEMENTED_IDS = Set.of(
            0x03,  // LayerSwitcher (via PlaneSwitcherManager, no visual instance)
            0x06,  // Spiral
            0x0B,  // TippingFloor (CPZ)
            0x0D,  // Signpost
            0x11,  // Bridge
            0x15,  // SwingingPlatform
            0x18,  // ARZPlatform/EHZPlatform
            0x19,  // CPZPlatform/OOZMovingPform/WFZPlatform
            0x1B,  // SpeedBooster (CPZ)
            0x1C,  // BridgeStake
            0x1D,  // BlueBalls (CPZ)
            0x1E,  // CPZSpinTube
            0x22,  // ArrowShooter (ARZ arrow-firing hazard + projectile)
            0x23,  // FallingPillar (ARZ pillar that drops lower section)
            0x2B,  // RisingPillar (ARZ pillar that rises and launches player)
            0x2C,  // LeavesGenerator (ARZ falling leaves trigger)
            0x2D,  // Barrier (one-way rising platform)
            0x26,  // Monitor
            0x32,  // BreakableBlock (CPZ metal blocks / HTZ rocks)
            0x36,  // Spikes
            0x40,  // Springboard (CPZ/ARZ/MCZ lever spring)
            0x41,  // Spring
            0x44,  // Bumper
            0x49,  // EHZWaterfall
            0x4B,  // Buzzer
            0x5C,  // Masher
            0x6B,  // MTZPlatform (multi-purpose platform with 12 movement subtypes)
            0x74,  // InvisibleBlock
            0x78,  // CPZStaircase (4-piece triggered elevator platform)
            0x79,  // Checkpoint
            0x7A,  // SidewaysPform (CPZ/MCZ horizontal moving platform)
            0x7B,  // PipeExitSpring (CPZ warp tube exit spring)
            0x86,  // CNZFlipper
            0x9D,  // Coconuts
            0xA5,  // Spiny (CPZ crawling badnik)
            0xA6,  // SpinyOnWall (CPZ wall-climbing badnik)
            0xA7,  // Grabber (CPZ spider badnik)
            0xD7,  // HexBumper (CNZ hexagonal bumper)
            0xD8   // BonusBlock
    );

    // Object categories for organized output
    private static final Set<Integer> BADNIK_IDS = Set.of(
            0x4A, 0x4B, 0x50, 0x5C, 0x8C, 0x8D, 0x8E, 0x91, 0x92, 0x94, 0x95, 0x96, 0x97,
            0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
            0xA5, 0xA6, 0xA7, 0xAC, 0xAD, 0xAE, 0xAF, 0xBF, 0xC8
    );

    private static final Set<Integer> BOSS_IDS = Set.of(
            0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x5D, 0x89, 0xC5, 0xC6, 0xC7
    );

    // Bosses spawned dynamically (not in placement data) - mapped by zone short name
    // These are triggered programmatically when reaching end of Act 2 (or final act)
    private static final Map<String, List<DynamicBoss>> DYNAMIC_BOSSES = Map.of(
            "EHZ", List.of(new DynamicBoss(0x56, "EHZBoss", "Drill car boss")),
            "CPZ", List.of(new DynamicBoss(0x5D, "CPZBoss", "Water dropper boss")),
            "ARZ", List.of(new DynamicBoss(0x89, "ARZBoss", "Hammer/arrow boss")),
            "CNZ", List.of(new DynamicBoss(0x51, "CNZBoss", "Catcher boss")),
            "HTZ", List.of(new DynamicBoss(0x52, "HTZBoss", "Lava-mobile boss")),
            "MCZ", List.of(new DynamicBoss(0x57, "MCZBoss", "Drill boss")),
            "OOZ", List.of(new DynamicBoss(0x55, "OOZBoss", "Laser/spike boss")),
            "MTZ", List.of(
                    new DynamicBoss(0x53, "MTZBossOrb", "Bouncing orb projectiles"),
                    new DynamicBoss(0x54, "MTZBoss", "Eggman's balloon machine")
            ),
            "SCZ", List.of()  // No boss in Sky Chase (transitions to WFZ)
    );

    public record DynamicBoss(int objectId, String name, String description) {}

    private final RomByteReader rom;
    private final Sonic2ObjectPlacement placementLoader;
    private final Map<Integer, List<String>> objectNames;

    public ObjectDiscoveryTool(RomByteReader rom) {
        this.rom = rom;
        this.placementLoader = new Sonic2ObjectPlacement(rom);
        this.objectNames = Sonic2ObjectRegistryData.NAMES_BY_ID;
    }

    /**
     * Read zone/act from level select table (same as engine does).
     */
    private ZoneAct getZoneAct(int levelIdx) {
        int zoneIdx = rom.readU8(LEVEL_SELECT_ADDR + levelIdx * 2);
        int actIdx = rom.readU8(LEVEL_SELECT_ADDR + levelIdx * 2 + 1);
        return new ZoneAct(zoneIdx, actIdx);
    }

    /**
     * Scan all levels and generate the discovery report.
     */
    public DiscoveryReport scan() {
        List<ZoneReport> zoneReports = new ArrayList<>();
        Map<Integer, ObjectStats> globalStats = new TreeMap<>();

        for (LevelConfig level : LEVELS) {
            ZoneReport report = scanLevel(level);
            zoneReports.add(report);

            // Aggregate into global stats
            for (ObjectUsage usage : report.objects) {
                globalStats.computeIfAbsent(usage.objectId,
                        id -> new ObjectStats(id, getName(id), getAliases(id), isImplemented(id)))
                        .addZoneUsage(level.shortName + level.act, usage.count, usage.subtypes);
            }
        }

        int implemented = (int) globalStats.values().stream().filter(s -> s.implemented).count();
        int unimplemented = globalStats.size() - implemented;

        return new DiscoveryReport(zoneReports, globalStats, implemented, unimplemented, LocalDateTime.now());
    }

    private ZoneReport scanLevel(LevelConfig level) {
        // Use the level select table to get proper zone/act, same as the engine
        ZoneAct zoneAct = getZoneAct(level.levelData.getLevelIndex());
        List<ObjectSpawn> spawns = placementLoader.load(zoneAct);

        // Group by object ID
        Map<Integer, List<ObjectSpawn>> byId = spawns.stream()
                .collect(Collectors.groupingBy(ObjectSpawn::objectId));

        List<ObjectUsage> usages = new ArrayList<>();
        for (Map.Entry<Integer, List<ObjectSpawn>> entry : byId.entrySet()) {
            int id = entry.getKey();
            List<ObjectSpawn> instances = entry.getValue();
            Set<Integer> subtypes = instances.stream()
                    .map(ObjectSpawn::subtype)
                    .collect(Collectors.toSet());
            usages.add(new ObjectUsage(id, getName(id), instances.size(), isImplemented(id), subtypes));
        }

        usages.sort(Comparator.comparingInt(u -> u.objectId));

        int implementedCount = (int) usages.stream().filter(u -> u.implemented).count();
        return new ZoneReport(level, usages, spawns.size(), implementedCount, usages.size() - implementedCount);
    }

    private String getName(int objectId) {
        List<String> names = objectNames.get(objectId);
        return (names != null && !names.isEmpty()) ? names.get(0) : String.format("Obj%02X", objectId);
    }

    private List<String> getAliases(int objectId) {
        List<String> names = objectNames.get(objectId);
        return (names != null && names.size() > 1) ? names.subList(1, names.size()) : List.of();
    }

    private boolean isImplemented(int objectId) {
        return IMPLEMENTED_IDS.contains(objectId);
    }

    private String getCategory(int objectId) {
        if (BOSS_IDS.contains(objectId)) return "Boss";
        if (BADNIK_IDS.contains(objectId)) return "Badnik";
        return "Object";
    }

    private static boolean isFinalAct(LevelConfig level) {
        return switch (level.shortName) {
            case "MTZ" -> level.act == 3;
            case "SCZ", "WFZ", "DEZ" -> level.act == 1;  // Single-act zones
            default -> level.act == 2;
        };
    }

    /**
     * Generate markdown report.
     */
    public String toMarkdown(DiscoveryReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sonic 2 Object Implementation Checklist\n\n");
        sb.append("Generated: ").append(report.scanTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        // Summary
        int total = report.implemented + report.unimplemented;
        double pct = total > 0 ? (report.implemented * 100.0 / total) : 0;
        sb.append("## Summary\n\n");
        sb.append(String.format("- **Total unique objects found:** %d%n", total));
        sb.append(String.format("- **Implemented:** %d (%.1f%%)%n", report.implemented, pct));
        sb.append(String.format("- **Unimplemented:** %d (%.1f%%)%n%n", report.unimplemented, 100 - pct));

        // Implemented objects
        sb.append("## Implemented Objects\n\n");
        sb.append("| ID | Name | Total Uses | Zones |\n");
        sb.append("|----|------|------------|-------|\n");
        for (ObjectStats stats : report.globalStats.values()) {
            if (stats.implemented) {
                sb.append(String.format("| 0x%02X | %s | %d | %s |%n",
                        stats.objectId, stats.name, stats.totalCount,
                        String.join(", ", stats.zoneUsage.keySet())));
            }
        }
        sb.append("\n");

        // Unimplemented by priority
        sb.append("## Unimplemented Objects (By Usage)\n\n");
        sb.append("| ID | Category | Name | Total Uses | Zones |\n");
        sb.append("|----|----------|------|------------|-------|\n");
        List<ObjectStats> unimplemented = report.globalStats.values().stream()
                .filter(s -> !s.implemented)
                .sorted((a, b) -> Integer.compare(b.totalCount, a.totalCount))
                .toList();
        for (ObjectStats stats : unimplemented) {
            sb.append(String.format("| 0x%02X | %s | %s | %d | %s |%n",
                    stats.objectId, getCategory(stats.objectId), stats.name, stats.totalCount,
                    String.join(", ", stats.zoneUsage.keySet())));
        }
        sb.append("\n");

        // By Zone
        sb.append("---\n\n");
        sb.append("## By Zone\n\n");

        String currentZone = null;
        for (ZoneReport zr : report.zoneReports) {
            if (currentZone == null || !currentZone.equals(zr.level.fullName)) {
                currentZone = zr.level.fullName;
                sb.append("### ").append(zr.level.fullName).append("\n\n");
            }

            sb.append("#### Act ").append(zr.level.act).append("\n\n");
            sb.append(String.format("Total: %d objects | Implemented: %d | Unimplemented: %d%n%n",
                    zr.totalObjects, zr.implementedCount, zr.unimplementedCount));

            // Group by category
            Map<String, List<ObjectUsage>> byCategory = zr.objects.stream()
                    .collect(Collectors.groupingBy(u -> getCategory(u.objectId)));

            for (String category : List.of("Badnik", "Boss", "Object")) {
                List<ObjectUsage> items = byCategory.getOrDefault(category, List.of());

                // For Boss category, also check for dynamic bosses in final act
                List<DynamicBoss> dynamicBosses = List.of();
                if (category.equals("Boss") && isFinalAct(zr.level)) {
                    dynamicBosses = DYNAMIC_BOSSES.getOrDefault(zr.level.shortName, List.of());
                }

                if (items.isEmpty() && dynamicBosses.isEmpty()) continue;

                String plural = category.equals("Boss") ? "Bosses" : category + "s";
                sb.append("**").append(plural).append(":**\n");

                // Placement-data bosses first
                for (ObjectUsage u : items) {
                    String check = u.implemented ? "x" : " ";
                    String subtypeStr = u.subtypes.size() <= 3
                            ? u.subtypes.stream().map(s -> String.format("0x%02X", s)).collect(Collectors.joining(", "))
                            : u.subtypes.size() + " subtypes";
                    sb.append(String.format("- [%s] 0x%02X %s (x%d) [%s]%n",
                            check, u.objectId, u.name, u.count, subtypeStr));
                }

                // Dynamic bosses (spawned programmatically)
                for (DynamicBoss boss : dynamicBosses) {
                    String check = isImplemented(boss.objectId) ? "x" : " ";
                    sb.append(String.format("- [%s] 0x%02X %s *(dynamic)* - %s%n",
                            check, boss.objectId, boss.name, boss.description));
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    public static void main(String[] args) {
        String romPath = args.length > 0 ? args[0] : DEFAULT_ROM_PATH;
        String outputPath = "OBJECT_CHECKLIST.md";

        // Parse args
        for (int i = 0; i < args.length - 1; i++) {
            if ("--output".equals(args[i]) || "-o".equals(args[i])) {
                outputPath = args[i + 1];
            }
        }

        Path romFile = Path.of(romPath);
        if (!Files.exists(romFile)) {
            System.err.println("ROM not found: " + romPath);
            System.err.println("Please place the ROM in the working directory or specify path as argument.");
            System.exit(1);
        }

        try (Rom rom = new Rom()) {
            if (!rom.open(romPath)) {
                System.err.println("Failed to open ROM: " + romPath);
                System.exit(1);
            }

            RomByteReader reader = RomByteReader.fromRom(rom);
            ObjectDiscoveryTool tool = new ObjectDiscoveryTool(reader);

            System.out.println("Scanning all zones for objects...");
            DiscoveryReport report = tool.scan();

            String markdown = tool.toMarkdown(report);

            // Write to file
            try (PrintWriter writer = new PrintWriter(outputPath)) {
                writer.print(markdown);
            }

            System.out.println("Report written to: " + outputPath);
            System.out.printf("Found %d unique objects: %d implemented, %d unimplemented%n",
                    report.implemented + report.unimplemented, report.implemented, report.unimplemented);

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Data classes

    public record LevelConfig(LevelData levelData, String shortName, String fullName, int act) {}

    public record ObjectUsage(int objectId, String name, int count, boolean implemented, Set<Integer> subtypes) {}

    public record ZoneReport(LevelConfig level, List<ObjectUsage> objects,
                             int totalObjects, int implementedCount, int unimplementedCount) {}

    public record DiscoveryReport(List<ZoneReport> zoneReports, Map<Integer, ObjectStats> globalStats,
                                  int implemented, int unimplemented, LocalDateTime scanTime) {}

    public static class ObjectStats {
        final int objectId;
        final String name;
        final List<String> aliases;
        final boolean implemented;
        int totalCount;
        final Map<String, Integer> zoneUsage = new LinkedHashMap<>();
        final Set<Integer> allSubtypes = new TreeSet<>();

        ObjectStats(int objectId, String name, List<String> aliases, boolean implemented) {
            this.objectId = objectId;
            this.name = name;
            this.aliases = aliases;
            this.implemented = implemented;
        }

        void addZoneUsage(String zone, int count, Set<Integer> subtypes) {
            zoneUsage.merge(zone, count, Integer::sum);
            totalCount += count;
            allSubtypes.addAll(subtypes);
        }
    }
}
