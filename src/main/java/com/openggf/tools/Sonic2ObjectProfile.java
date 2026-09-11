package com.openggf.tools;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2ObjectPlacement;
import com.openggf.game.sonic2.Sonic2PlcArtRegistry;
import com.openggf.game.sonic2.ZoneAct;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistryData;
import com.openggf.level.LevelData;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.PlcParser;

import java.util.*;

import static com.openggf.game.sonic2.constants.Sonic2Constants.LEVEL_SELECT_ADDR;

/**
 * Sonic 2 object profile for the ObjectDiscoveryTool.
 */
public class Sonic2ObjectProfile implements GameObjectProfile {

    private static final List<ObjectDiscoveryTool.LevelConfig> LEVELS = List.of(
            new ObjectDiscoveryTool.LevelConfig(LevelData.EMERALD_HILL_1, "EHZ", "Emerald Hill Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.EMERALD_HILL_2, "EHZ", "Emerald Hill Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.CHEMICAL_PLANT_1, "CPZ", "Chemical Plant Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.CHEMICAL_PLANT_2, "CPZ", "Chemical Plant Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.AQUATIC_RUIN_1, "ARZ", "Aquatic Ruin Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.AQUATIC_RUIN_2, "ARZ", "Aquatic Ruin Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.CASINO_NIGHT_1, "CNZ", "Casino Night Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.CASINO_NIGHT_2, "CNZ", "Casino Night Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.HILL_TOP_1, "HTZ", "Hill Top Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.HILL_TOP_2, "HTZ", "Hill Top Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.MYSTIC_CAVE_1, "MCZ", "Mystic Cave Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.MYSTIC_CAVE_2, "MCZ", "Mystic Cave Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.OIL_OCEAN_1, "OOZ", "Oil Ocean Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.OIL_OCEAN_2, "OOZ", "Oil Ocean Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.METROPOLIS_1, "MTZ", "Metropolis Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.METROPOLIS_2, "MTZ", "Metropolis Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.METROPOLIS_3, "MTZ", "Metropolis Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.SKY_CHASE, "SCZ", "Sky Chase Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.WING_FORTRESS, "WFZ", "Wing Fortress Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.DEATH_EGG, "DEZ", "Death Egg Zone", 1)
    );

    private static final Set<Integer> IMPLEMENTED_IDS = Set.of(
            0x03, 0x06, 0x0B, 0x0D, 0x11, 0x14, 0x15, 0x16, 0x18, 0x19,
            0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x22, 0x23, 0x24, 0x26, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x36, 0x3D,
            0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x50,
            0x51, 0x52, 0x53, 0x54, 0x56, 0x57, 0x5C, 0x5D, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76,
            0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7F, 0x80, 0x81, 0x82, 0x83,
            0x84, 0x85, 0x86, 0x89, 0x8B, 0x8C, 0x8D, 0x8E, 0x91, 0x92, 0x94,
            0x95, 0x96, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA1, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xAC, 0xAD, 0xAE, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF, 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC8, 0xD2,
            0xC6, 0xC7, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9
    );

    private static final Set<Integer> BADNIK_IDS = Set.of(
            0x4A, 0x4B, 0x50, 0x5C, 0x8C, 0x8D, 0x8E, 0x91, 0x92, 0x94, 0x95, 0x96, 0x97,
            0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
            0xA5, 0xA6, 0xA7, 0xAC, 0xAD, 0xAE, 0xAF, 0xBB, 0xBF, 0xC8
    );

    private static final Set<Integer> BOSS_IDS = Set.of(
            0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x5D, 0x89, 0xC5, 0xC6, 0xC7
    );

    private static final Map<String, List<ObjectDiscoveryTool.DynamicBoss>> DYNAMIC_BOSSES = Map.of(
            "EHZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x56, "EHZBoss", "Drill car boss")),
            "CPZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x5D, "CPZBoss", "Water dropper boss")),
            "ARZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x89, "ARZBoss", "Hammer/arrow boss")),
            "CNZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x51, "CNZBoss", "Catcher boss")),
            "HTZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x52, "HTZBoss", "Lava-mobile boss")),
            "MCZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x57, "MCZBoss", "Drill boss")),
            "OOZ", List.of(new ObjectDiscoveryTool.DynamicBoss(0x55, "OOZBoss", "Laser/spike boss")),
            "MTZ", List.of(
                    new ObjectDiscoveryTool.DynamicBoss(0x53, "MTZBossOrb", "Bouncing orb projectiles"),
                    new ObjectDiscoveryTool.DynamicBoss(0x54, "MTZBoss", "Eggman's balloon machine")
            ),
            "SCZ", List.of()
    );

    /** Maps art key strings to the object IDs that use that art. */
    private static final Map<String, Set<Integer>> ART_KEY_TO_OBJECTS = buildArtKeyToObjects();

    private static Map<String, Set<Integer>> buildArtKeyToObjects() {
        Map<String, Set<Integer>> m = new HashMap<>();
        // Universal objects (Std PLCs)
        m.put(ObjectArtKeys.SPIKE, Set.of(0x36));
        m.put(ObjectArtKeys.SPIKE_SIDE, Set.of(0x36));
        m.put(ObjectArtKeys.SPRING_VERTICAL, Set.of(0x41));
        m.put(ObjectArtKeys.SPRING_HORIZONTAL, Set.of(0x41));
        m.put(ObjectArtKeys.SPRING_DIAGONAL, Set.of(0x41));
        m.put(ObjectArtKeys.CHECKPOINT, Set.of(0x79));
        m.put(ObjectArtKeys.POINTS, Set.of(0x29));
        // EHZ
        m.put(ObjectArtKeys.BRIDGE, Set.of(0x11));
        m.put(Sonic2ObjectArtKeys.BUZZER, Set.of(0x4B));
        m.put(Sonic2ObjectArtKeys.COCONUTS, Set.of(0x9D));
        m.put(Sonic2ObjectArtKeys.MASHER, Set.of(0x5C));
        // HTZ
        m.put(Sonic2ObjectArtKeys.SEESAW, Set.of(0x14));
        m.put(Sonic2ObjectArtKeys.SOL, Set.of(0x95));
        m.put(Sonic2ObjectArtKeys.HTZ_LIFT, Set.of(0x16));
        m.put(Sonic2ObjectArtKeys.SPIKER, Set.of(0x92));
        m.put(Sonic2ObjectArtKeys.LAVA_BUBBLE, Set.of(0x20));
        m.put(Sonic2ObjectArtKeys.REXON, Set.of(0x94));
        // CPZ
        m.put(Sonic2ObjectArtKeys.SPEED_BOOSTER, Set.of(0x1B));
        m.put(Sonic2ObjectArtKeys.BLUE_BALLS, Set.of(0x1D));
        m.put(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK, Set.of(0x78));
        m.put(Sonic2ObjectArtKeys.CPZ_PYLON, Set.of(0x7C));
        m.put(Sonic2ObjectArtKeys.PIPE_EXIT_SPRING, Set.of(0x7B));
        m.put(Sonic2ObjectArtKeys.TIPPING_FLOOR, Set.of(0x0B));
        m.put(Sonic2ObjectArtKeys.BARRIER, Set.of(0x2D));
        m.put(Sonic2ObjectArtKeys.SPRINGBOARD, Set.of(0x40));
        m.put(Sonic2ObjectArtKeys.SPINY, Set.of(0xA5));
        m.put(Sonic2ObjectArtKeys.GRABBER, Set.of(0xA7));
        // ARZ
        m.put(Sonic2ObjectArtKeys.CHOP_CHOP, Set.of(0x91));
        m.put(Sonic2ObjectArtKeys.WHISP, Set.of(0x8C));
        m.put(Sonic2ObjectArtKeys.GROUNDER, Set.of(0x8D));
        m.put(Sonic2ObjectArtKeys.ARROW_SHOOTER, Set.of(0x22));
        m.put(Sonic2ObjectArtKeys.LEAVES, Set.of(0x2C));
        m.put(Sonic2ObjectArtKeys.BUBBLES, Set.of(0x24));
        // CNZ
        m.put(Sonic2ObjectArtKeys.BUMPER, Set.of(0x44));
        m.put(Sonic2ObjectArtKeys.HEX_BUMPER, Set.of(0xD7));
        m.put(Sonic2ObjectArtKeys.BONUS_BLOCK, Set.of(0xD8));
        m.put(Sonic2ObjectArtKeys.FLIPPER, Set.of(0x86));
        m.put(Sonic2ObjectArtKeys.CNZ_RECT_BLOCKS, Set.of(0xD2));
        m.put(Sonic2ObjectArtKeys.CNZ_BIG_BLOCK, Set.of(0xD4));
        m.put(Sonic2ObjectArtKeys.CNZ_ELEVATOR, Set.of(0xD5));
        m.put(Sonic2ObjectArtKeys.CNZ_CAGE, Set.of(0xD6));
        m.put(Sonic2ObjectArtKeys.CNZ_BONUS_SPIKE, Set.of(0xD3));
        m.put(Sonic2ObjectArtKeys.LAUNCHER_SPRING_VERT, Set.of(0x85));
        m.put(Sonic2ObjectArtKeys.LAUNCHER_SPRING_DIAG, Set.of(0x85));
        m.put(Sonic2ObjectArtKeys.CRAWL, Set.of(0xC8));
        // OOZ
        m.put(Sonic2ObjectArtKeys.LAUNCH_BALL, Set.of(0x48));
        m.put(Sonic2ObjectArtKeys.OOZ_FAN_HORIZ, Set.of(0x3F));
        m.put(Sonic2ObjectArtKeys.OOZ_BURNER_LID, Set.of(0x33));
        m.put(Sonic2ObjectArtKeys.OOZ_BURN_FLAME, Set.of(0x33));
        m.put(Sonic2ObjectArtKeys.OOZ_LAUNCHER_VERT, Set.of(0x3D));
        m.put(Sonic2ObjectArtKeys.OOZ_LAUNCHER_HORIZ, Set.of(0x3D));
        m.put(Sonic2ObjectArtKeys.OOZ_COLLAPSING_PLATFORM, Set.of(0x1F));
        m.put(Sonic2ObjectArtKeys.OOZ_SLIDING_SPIKE, Set.of(0x43));
        m.put(Sonic2ObjectArtKeys.OOZ_PRESSURE_SPRING, Set.of(0x45));
        m.put(Sonic2ObjectArtKeys.OCTUS, Set.of(0x4A));
        m.put(Sonic2ObjectArtKeys.AQUIS, Set.of(0x50));
        // MCZ
        m.put(Sonic2ObjectArtKeys.VINE_PULLEY, Set.of(0x7F, 0x80));
        m.put(Sonic2ObjectArtKeys.MCZ_CRATE, Set.of(0x6A));
        m.put(Sonic2ObjectArtKeys.MCZ_DRAWBRIDGE, Set.of(0x81));
        m.put(Sonic2ObjectArtKeys.MCZ_COLLAPSING_PLATFORM, Set.of(0x1F));
        m.put(Sonic2ObjectArtKeys.CRAWLTON, Set.of(0x9E));
        m.put(Sonic2ObjectArtKeys.FLASHER, Set.of(0xA3));
        m.put(Sonic2ObjectArtKeys.BUTTON, Set.of(0x47));
        // MTZ
        m.put(Sonic2ObjectArtKeys.MTZ_COG, Set.of(0x70));
        m.put(Sonic2ObjectArtKeys.MTZ_NUT, Set.of(0x69));
        m.put(Sonic2ObjectArtKeys.MTZ_FLOOR_SPIKE, Set.of(0x6D));
        m.put(Sonic2ObjectArtKeys.MTZ_SPIKE_BLOCK, Set.of(0x68));
        m.put(Sonic2ObjectArtKeys.MTZ_STEAM, Set.of(0x42));
        m.put(Sonic2ObjectArtKeys.MTZ_LAVA_BUBBLE, Set.of(0x71));
        m.put(Sonic2ObjectArtKeys.MTZ_SPIN_TUBE_FLASH, Set.of(0x67));
        m.put(Sonic2ObjectArtKeys.ASTERON, Set.of(0xA4));
        m.put(Sonic2ObjectArtKeys.SLICER, Set.of(0xA1));
        m.put(Sonic2ObjectArtKeys.SHELLCRACKER, Set.of(0x9F));
        // WFZ
        m.put(Sonic2ObjectArtKeys.WFZ_HOOK, Set.of(0x80));
        m.put(Sonic2ObjectArtKeys.TORNADO, Set.of(0xB2));
        m.put(Sonic2ObjectArtKeys.TORNADO_THRUSTER, Set.of(0xB2));
        m.put(Sonic2ObjectArtKeys.WFZ_VPROPELLER, Set.of(0xB4));
        m.put(Sonic2ObjectArtKeys.WFZ_HPROPELLER, Set.of(0xB5));
        m.put(Sonic2ObjectArtKeys.WFZ_WALL_TURRET, Set.of(0xB8));
        m.put(Sonic2ObjectArtKeys.WFZ_LASER, Set.of(0xB9));
        m.put(Sonic2ObjectArtKeys.WFZ_VERTICAL_LASER, Set.of(0xB7));
        m.put(Sonic2ObjectArtKeys.WFZ_LAUNCH_CATAPULT, Set.of(0xC0));
        m.put(Sonic2ObjectArtKeys.WFZ_BREAK_PANELS, Set.of(0xC1));
        m.put(Sonic2ObjectArtKeys.WFZ_RIVET, Set.of(0xC2));
        m.put(Sonic2ObjectArtKeys.WFZ_BELT_PLATFORM, Set.of(0xBD));
        m.put(Sonic2ObjectArtKeys.WFZ_TILT_PLATFORM, Set.of(0xB6));
        m.put(Sonic2ObjectArtKeys.WFZ_CONVEYOR_BELT_WHEEL, Set.of(0xBA));
        m.put(Sonic2ObjectArtKeys.CLUCKER, Set.of(0xAD, 0xAE));
        m.put(Sonic2ObjectArtKeys.WFZ_UNKNOWN, Set.of(0xBB));
        m.put(Sonic2ObjectArtKeys.WFZ_STICK, Set.of(0xBF));
        // SCZ
        m.put(Sonic2ObjectArtKeys.NEBULA, Set.of(0x99));
        m.put(Sonic2ObjectArtKeys.TURTLOID, Set.of(0x9A));
        m.put(Sonic2ObjectArtKeys.BALKIRY, Set.of(0xAC));
        m.put(Sonic2ObjectArtKeys.CLOUDS, Set.of(0xB3));
        // Bosses
        m.put(Sonic2ObjectArtKeys.EHZ_BOSS, Set.of(0x56));
        m.put(Sonic2ObjectArtKeys.HTZ_BOSS, Set.of(0x52));
        m.put(Sonic2ObjectArtKeys.CPZ_BOSS_EGGPOD, Set.of(0x5D));
        m.put(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS, Set.of(0x5D));
        m.put(Sonic2ObjectArtKeys.ARZ_BOSS_MAIN, Set.of(0x89));
        m.put(Sonic2ObjectArtKeys.CNZ_BOSS, Set.of(0x51));
        m.put(Sonic2ObjectArtKeys.MTZ_BOSS, Set.of(0x53, 0x54));
        m.put(Sonic2ObjectArtKeys.MCZ_BOSS, Set.of(0x57));
        m.put(Sonic2ObjectArtKeys.DEZ_EGGMAN, Set.of(0xC6));
        m.put(Sonic2ObjectArtKeys.DEZ_WALL, Set.of(0xC6));
        m.put(Sonic2ObjectArtKeys.DEZ_BOSS, Set.of(0xC7));
        return Map.copyOf(m);
    }

    @Override public String gameName() { return "Sonic 2"; }
    @Override public String gameId() { return "s2"; }
    @Override public String defaultRomPath() { return "s2.gen"; }
    @Override public String outputFilename() { return "OBJECT_CHECKLIST.md"; }
    @Override public List<ObjectDiscoveryTool.LevelConfig> getLevels() { return LEVELS; }
    @Override public Set<Integer> getImplementedIds() { return IMPLEMENTED_IDS; }
    @Override public Set<Integer> getBadnikIds() { return BADNIK_IDS; }
    @Override public Set<Integer> getBossIds() { return BOSS_IDS; }
    @Override public Map<String, List<ObjectDiscoveryTool.DynamicBoss>> getDynamicBosses() { return DYNAMIC_BOSSES; }
    @Override public Map<Integer, List<String>> getObjectNames() { return Sonic2ObjectRegistryData.NAMES_BY_ID; }

    @Override
    public List<ObjectSpawn> loadObjects(RomByteReader rom, ObjectDiscoveryTool.LevelConfig level) {
        int levelIdx = level.levelData().getLevelIndex();
        int zoneIdx = rom.readU8(LEVEL_SELECT_ADDR + levelIdx * 2);
        int actIdx = rom.readU8(LEVEL_SELECT_ADDR + levelIdx * 2 + 1);
        ZoneAct zoneAct = new ZoneAct(zoneIdx, actIdx);
        return new Sonic2ObjectPlacement(rom).load(zoneAct);
    }

    @Override
    public List<PlcObjectMapping> getPlcObjectMappings(RomByteReader rom, ObjectDiscoveryTool.LevelConfig level) {
        int levelIdx = level.levelData().getLevelIndex();
        int zoneIdx = rom.readU8(LEVEL_SELECT_ADDR + levelIdx * 2);

        // Extract PLC IDs from LEVEL_DATA_DIR (byte[0] = plc1, byte[4] = plc2)
        int base = Sonic2Constants.LEVEL_DATA_DIR + zoneIdx * Sonic2Constants.LEVEL_DATA_DIR_ENTRY_SIZE;
        int plc1Id = rom.readU8(base);
        int plc2Id = rom.readU8(base + 4);

        // Collect zone PLCs + standard PLCs
        Set<Integer> plcIds = new LinkedHashSet<>();
        plcIds.add(Sonic2Constants.PLC_STD1);
        plcIds.add(Sonic2Constants.PLC_STD2);
        plcIds.add(plc1Id);
        plcIds.add(plc2Id);

        List<PlcObjectMapping> mappings = new ArrayList<>();
        for (int plcId : plcIds) {
            PlcParser.PlcDefinition def = PlcParser.parse(rom, Sonic2Constants.ART_LOAD_CUES_ADDR, plcId);
            for (PlcParser.PlcEntry entry : def.entries()) {
                Sonic2PlcArtRegistry.ArtRegistration reg = Sonic2PlcArtRegistry.lookup(entry.romAddr());
                if (reg != null) {
                    Set<Integer> objIds = ART_KEY_TO_OBJECTS.get(reg.key());
                    if (objIds != null && !objIds.isEmpty()) {
                        mappings.add(new PlcObjectMapping(plcId, entry.romAddr(), objIds));
                    }
                }
            }
        }
        return mappings;
    }

    @Override
    public boolean isFinalAct(ObjectDiscoveryTool.LevelConfig level) {
        return switch (level.shortName()) {
            case "MTZ" -> level.act() == 3;
            case "SCZ", "WFZ", "DEZ" -> level.act() == 1;
            default -> level.act() == 2;
        };
    }
}
