package com.openggf.tools;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.LevelData;
import com.openggf.level.objects.ObjectSpawn;

import com.openggf.level.resources.PlcParser;

import java.util.*;

/**
 * Sonic 1 object profile for the ObjectDiscoveryTool.
 */
public class Sonic1ObjectProfile implements GameObjectProfile {

    private static final List<ObjectDiscoveryTool.LevelConfig> LEVELS = List.of(
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_GREEN_HILL_1, "GHZ", "Green Hill Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_GREEN_HILL_2, "GHZ", "Green Hill Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_GREEN_HILL_3, "GHZ", "Green Hill Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_LABYRINTH_1, "LZ", "Labyrinth Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_LABYRINTH_2, "LZ", "Labyrinth Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_LABYRINTH_3, "LZ", "Labyrinth Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_MARBLE_1, "MZ", "Marble Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_MARBLE_2, "MZ", "Marble Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_MARBLE_3, "MZ", "Marble Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_STAR_LIGHT_1, "SLZ", "Star Light Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_STAR_LIGHT_2, "SLZ", "Star Light Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_STAR_LIGHT_3, "SLZ", "Star Light Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SPRING_YARD_1, "SYZ", "Spring Yard Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SPRING_YARD_2, "SYZ", "Spring Yard Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SPRING_YARD_3, "SYZ", "Spring Yard Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SCRAP_BRAIN_1, "SBZ", "Scrap Brain Zone", 1),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SCRAP_BRAIN_2, "SBZ", "Scrap Brain Zone", 2),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_SCRAP_BRAIN_3, "SBZ", "Scrap Brain Zone", 3),
            new ObjectDiscoveryTool.LevelConfig(LevelData.S1_FINAL_ZONE, "FZ", "Final Zone", 1)
    );

    // IDs with registered factories in Sonic1ObjectRegistry
    private static final Set<Integer> IMPLEMENTED_IDS = Set.of(
            Sonic1ObjectIds.LAMPPOST,
            Sonic1ObjectIds.SPINNING_LIGHT,
            Sonic1ObjectIds.BREAKABLE_POLE,
            Sonic1ObjectIds.FLAPPING_DOOR,
            Sonic1ObjectIds.SIGNPOST,
            Sonic1ObjectIds.MONITOR,
            Sonic1ObjectIds.ANIMALS,
            Sonic1ObjectIds.RING,
            Sonic1ObjectIds.HARPOON,
            Sonic1ObjectIds.SPIKED_POLE_HELIX,
            Sonic1ObjectIds.SWINGING_PLATFORM,
            Sonic1ObjectIds.PLATFORM,
            Sonic1ObjectIds.COLLAPSING_LEDGE,
            Sonic1ObjectIds.ROCK,
            Sonic1ObjectIds.BREAKABLE_WALL,
            Sonic1ObjectIds.EDGE_WALLS,
            Sonic1ObjectIds.MZ_BRICK,
            Sonic1ObjectIds.BRIDGE,
            Sonic1ObjectIds.SCENERY,
            Sonic1ObjectIds.SPIKES,
            Sonic1ObjectIds.SPRING,
            Sonic1ObjectIds.BUZZ_BOMBER,
            Sonic1ObjectIds.CHOPPER,
            Sonic1ObjectIds.JAWS,
            Sonic1ObjectIds.BURROBOT,
            Sonic1ObjectIds.CRABMEAT,
            Sonic1ObjectIds.MOTOBUG,
            Sonic1ObjectIds.NEWTRON,
            Sonic1ObjectIds.CATERKILLER,
            Sonic1ObjectIds.BATBRAIN,
            Sonic1ObjectIds.YADRIN,
            Sonic1ObjectIds.ROLLER,
            Sonic1ObjectIds.MZ_LARGE_GRASSY_PLATFORM,
            Sonic1ObjectIds.MZ_GLASS_BLOCK,
            Sonic1ObjectIds.CHAINED_STOMPER,
            Sonic1ObjectIds.PUSH_BLOCK,
            Sonic1ObjectIds.BUTTON,
            Sonic1ObjectIds.BURNING_GRASS,
            Sonic1ObjectIds.SMASH_BLOCK,
            Sonic1ObjectIds.MOVING_BLOCK,
            Sonic1ObjectIds.COLLAPSING_FLOOR,
            Sonic1ObjectIds.SPIKED_BALL_CHAIN,
            Sonic1ObjectIds.BIG_SPIKED_BALL,
            Sonic1ObjectIds.SLZ_ELEVATOR,
            Sonic1ObjectIds.SLZ_CIRCLING_PLATFORM,
            Sonic1ObjectIds.SLZ_STAIRCASE,
            Sonic1ObjectIds.INVISIBLE_BARRIER,
            Sonic1ObjectIds.LAVA_BALL_MAKER,
            Sonic1ObjectIds.LAVA_GEYSER_MAKER,
            Sonic1ObjectIds.LAVA_GEYSER,
            Sonic1ObjectIds.LAVA_TAG,
            Sonic1ObjectIds.LAVA_WALL,
            Sonic1ObjectIds.LABYRINTH_BLOCK,
            Sonic1ObjectIds.GARGOYLE,
            Sonic1ObjectIds.LZ_CONVEYOR,
            Sonic1ObjectIds.BUBBLES,
            Sonic1ObjectIds.WATERFALL,
            Sonic1ObjectIds.BUMPER,
            Sonic1ObjectIds.FLOATING_BLOCK,
            Sonic1ObjectIds.FAN,
            Sonic1ObjectIds.SEESAW,
            Sonic1ObjectIds.PYLON,
            Sonic1ObjectIds.WATERFALL_SOUND,
            Sonic1ObjectIds.ORBINAUT,
            Sonic1ObjectIds.BOMB,
            Sonic1ObjectIds.GIANT_RING,
            Sonic1ObjectIds.GHZ_BOSS,
            Sonic1ObjectIds.MZ_BOSS,
            Sonic1ObjectIds.SYZ_BOSS,
            Sonic1ObjectIds.SYZ_BOSS_BLOCK,
            Sonic1ObjectIds.LZ_BOSS,
            Sonic1ObjectIds.SLZ_BOSS,
            Sonic1ObjectIds.FZ_BOSS,
            Sonic1ObjectIds.BOSS_FIRE,
            Sonic1ObjectIds.EGG_PRISON,
            Sonic1ObjectIds.HIDDEN_BONUS,
            Sonic1ObjectIds.ELECTROCUTER,
            Sonic1ObjectIds.SBZ_SMALL_DOOR,
            Sonic1ObjectIds.SBZ_CONVEYOR_BELT,
            Sonic1ObjectIds.SBZ_SPINNING_PLATFORM,
            Sonic1ObjectIds.SBZ_SAW,
            Sonic1ObjectIds.SBZ_STOMPER_DOOR,
            Sonic1ObjectIds.SBZ_VANISHING_PLATFORM,
            Sonic1ObjectIds.FLAMETHROWER,
            Sonic1ObjectIds.GIRDER,
            Sonic1ObjectIds.BALL_HOG,
            Sonic1ObjectIds.TELEPORTER,
            Sonic1ObjectIds.RUNNING_DISC,
            Sonic1ObjectIds.SBZ_SPIN_CONVEYOR,
            Sonic1ObjectIds.JUNCTION,
            Sonic1ObjectIds.END_SONIC,
            Sonic1ObjectIds.END_CHAOS,
            Sonic1ObjectIds.END_STH
    );

    private static final Set<Integer> BADNIK_IDS = Set.of(
            Sonic1ObjectIds.CRABMEAT,
            Sonic1ObjectIds.BUZZ_BOMBER,
            Sonic1ObjectIds.CHOPPER,
            Sonic1ObjectIds.JAWS,
            Sonic1ObjectIds.BURROBOT,
            Sonic1ObjectIds.MOTOBUG,
            Sonic1ObjectIds.NEWTRON,
            Sonic1ObjectIds.YADRIN,
            Sonic1ObjectIds.ROLLER,
            Sonic1ObjectIds.BATBRAIN,
            Sonic1ObjectIds.BOMB,
            Sonic1ObjectIds.ORBINAUT,
            Sonic1ObjectIds.CATERKILLER,
            Sonic1ObjectIds.BALL_HOG
    );

    private static final Set<Integer> BOSS_IDS = Set.of(
            Sonic1ObjectIds.GHZ_BOSS,
            Sonic1ObjectIds.MZ_BOSS,
            Sonic1ObjectIds.SYZ_BOSS,
            Sonic1ObjectIds.LZ_BOSS,
            Sonic1ObjectIds.SLZ_BOSS,
            Sonic1ObjectIds.FZ_BOSS
    );

    /**
     * Maps Nemesis ROM addresses to object IDs that use that art.
     * Built from s1disasm Pattern Load Cues (ArtNem_* entries).
     */
    private static final Map<Integer, Set<Integer>> NEM_ADDR_TO_OBJECTS = Map.ofEntries(
            // Universal objects (Std PLCs)
            Map.entry(Sonic1Constants.ART_NEM_SPIKES_ADDR, Set.of(Sonic1ObjectIds.SPIKES)),
            Map.entry(Sonic1Constants.ART_NEM_HSPRING_ADDR, Set.of(Sonic1ObjectIds.SPRING)),
            Map.entry(Sonic1Constants.ART_NEM_VSPRING_ADDR, Set.of(Sonic1ObjectIds.SPRING)),
            Map.entry(Sonic1Constants.ART_NEM_SIGNPOST_ADDR, Set.of(Sonic1ObjectIds.SIGNPOST)),
            Map.entry(Sonic1Constants.ART_NEM_LAMPPOST_ADDR, Set.of(Sonic1ObjectIds.LAMPPOST)),
            Map.entry(Sonic1Constants.ART_NEM_MONITOR_ADDR, Set.of(Sonic1ObjectIds.MONITOR)),
            Map.entry(Sonic1Constants.ART_NEM_POINTS_ADDR, Set.of(Sonic1ObjectIds.HIDDEN_BONUS)),
            // GHZ objects
            Map.entry(Sonic1Constants.ART_NEM_BRIDGE_ADDR, Set.of(Sonic1ObjectIds.BRIDGE)),
            Map.entry(Sonic1Constants.ART_NEM_SWING_ADDR, Set.of(Sonic1ObjectIds.SWINGING_PLATFORM)),
            Map.entry(Sonic1Constants.ART_NEM_SPIKE_POLE_ADDR, Set.of(Sonic1ObjectIds.SPIKED_POLE_HELIX)),
            Map.entry(Sonic1Constants.ART_NEM_PURPLE_ROCK_ADDR, Set.of(Sonic1ObjectIds.ROCK)),
            Map.entry(Sonic1Constants.ART_NEM_GHZ_BREAKABLE_WALL_ADDR, Set.of(Sonic1ObjectIds.BREAKABLE_WALL)),
            Map.entry(Sonic1Constants.ART_NEM_GHZ_EDGE_WALL_ADDR, Set.of(Sonic1ObjectIds.EDGE_WALLS)),
            // Badniks
            Map.entry(Sonic1Constants.ART_NEM_MOTOBUG_ADDR, Set.of(Sonic1ObjectIds.MOTOBUG)),
            Map.entry(Sonic1Constants.ART_NEM_CRABMEAT_ADDR, Set.of(Sonic1ObjectIds.CRABMEAT)),
            Map.entry(Sonic1Constants.ART_NEM_BUZZ_BOMBER_ADDR, Set.of(Sonic1ObjectIds.BUZZ_BOMBER)),
            Map.entry(Sonic1Constants.ART_NEM_CHOPPER_ADDR, Set.of(Sonic1ObjectIds.CHOPPER)),
            Map.entry(Sonic1Constants.ART_NEM_JAWS_ADDR, Set.of(Sonic1ObjectIds.JAWS)),
            Map.entry(Sonic1Constants.ART_NEM_BURROBOT_ADDR, Set.of(Sonic1ObjectIds.BURROBOT)),
            Map.entry(Sonic1Constants.ART_NEM_NEWTRON_ADDR, Set.of(Sonic1ObjectIds.NEWTRON)),
            Map.entry(Sonic1Constants.ART_NEM_ROLLER_ADDR, Set.of(Sonic1ObjectIds.ROLLER)),
            Map.entry(Sonic1Constants.ART_NEM_YADRIN_ADDR, Set.of(Sonic1ObjectIds.YADRIN)),
            Map.entry(Sonic1Constants.ART_NEM_BASARAN_ADDR, Set.of(Sonic1ObjectIds.BATBRAIN)),
            Map.entry(Sonic1Constants.ART_NEM_CATERKILLER_ADDR, Set.of(Sonic1ObjectIds.CATERKILLER)),
            Map.entry(Sonic1Constants.ART_NEM_BOMB_ADDR, Set.of(Sonic1ObjectIds.BOMB)),
            Map.entry(Sonic1Constants.ART_NEM_ORBINAUT_ADDR, Set.of(Sonic1ObjectIds.ORBINAUT)),
            Map.entry(Sonic1Constants.ART_NEM_BALL_HOG_ADDR, Set.of(Sonic1ObjectIds.BALL_HOG)),
            // MZ objects
            Map.entry(Sonic1Constants.ART_NEM_MZ_GLASS_ADDR, Set.of(Sonic1ObjectIds.MZ_GLASS_BLOCK)),
            Map.entry(Sonic1Constants.ART_NEM_MZ_SWITCH_ADDR, Set.of(Sonic1ObjectIds.BUTTON)),
            Map.entry(Sonic1Constants.ART_NEM_MZ_FIREBALL_ADDR, Set.of(Sonic1ObjectIds.LAVA_BALL_MAKER)),
            Map.entry(Sonic1Constants.ART_NEM_LAVA_ADDR, Set.of(Sonic1ObjectIds.LAVA_TAG)),
            // LZ objects
            Map.entry(Sonic1Constants.ART_NEM_LZ_HARPOON_ADDR, Set.of(Sonic1ObjectIds.HARPOON)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_GARGOYLE_ADDR, Set.of(Sonic1ObjectIds.GARGOYLE)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_WHEEL_ADDR, Set.of(Sonic1ObjectIds.LZ_CONVEYOR)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_BUBBLES_ADDR, Set.of(Sonic1ObjectIds.BUBBLES)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_POLE_ADDR, Set.of(Sonic1ObjectIds.BREAKABLE_POLE)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_FLAP_DOOR_ADDR, Set.of(Sonic1ObjectIds.FLAPPING_DOOR)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_SWITCH_ADDR, Set.of(Sonic1ObjectIds.BUTTON)),
            Map.entry(Sonic1Constants.ART_NEM_LZ_SPIKEBALL_ADDR, Set.of(Sonic1ObjectIds.SPIKED_BALL_CHAIN)),
            // SLZ objects
            Map.entry(Sonic1Constants.ART_NEM_SLZ_SEESAW_ADDR, Set.of(Sonic1ObjectIds.SEESAW)),
            Map.entry(Sonic1Constants.ART_NEM_SLZ_SPIKEBALL_ADDR, Set.of(Sonic1ObjectIds.BIG_SPIKED_BALL)),
            Map.entry(Sonic1Constants.ART_NEM_SLZ_PYLON_ADDR, Set.of(Sonic1ObjectIds.PYLON)),
            Map.entry(Sonic1Constants.ART_NEM_SLZ_FAN_ADDR, Set.of(Sonic1ObjectIds.FAN)),
            // SYZ objects
            Map.entry(Sonic1Constants.ART_NEM_BUMPER_ADDR, Set.of(Sonic1ObjectIds.BUMPER)),
            Map.entry(Sonic1Constants.ART_NEM_SYZ_SMALL_SPIKEBALL_ADDR, Set.of(Sonic1ObjectIds.SPIKED_BALL_CHAIN)),
            // SBZ objects
            Map.entry(Sonic1Constants.ART_NEM_SBZ_STOMPER_ADDR, Set.of(Sonic1ObjectIds.SBZ_STOMPER_DOOR)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_SAW_ADDR, Set.of(Sonic1ObjectIds.SBZ_SAW)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_SPINNING_PLATFORM_ADDR, Set.of(Sonic1ObjectIds.SBZ_SPINNING_PLATFORM)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_RUNNING_DISC_ADDR, Set.of(Sonic1ObjectIds.RUNNING_DISC)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_JUNCTION_ADDR, Set.of(Sonic1ObjectIds.JUNCTION)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_SMALL_DOOR_ADDR, Set.of(Sonic1ObjectIds.SBZ_SMALL_DOOR)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_ELECTROCUTER_ADDR, Set.of(Sonic1ObjectIds.ELECTROCUTER)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_FLAMETHROWER_ADDR, Set.of(Sonic1ObjectIds.FLAMETHROWER)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_GIRDER_ADDR, Set.of(Sonic1ObjectIds.GIRDER)),
            Map.entry(Sonic1Constants.ART_NEM_SBZ_VANISHING_BLOCK_ADDR, Set.of(Sonic1ObjectIds.SBZ_VANISHING_PLATFORM)),
            // Bosses
            Map.entry(Sonic1Constants.ART_NEM_EGGMAN_ADDR, Set.of(Sonic1ObjectIds.GHZ_BOSS)),
            Map.entry(Sonic1Constants.ART_NEM_FZ_BOSS_ADDR, Set.of(Sonic1ObjectIds.FZ_BOSS))
    );

    private static final Map<String, List<ObjectDiscoveryTool.DynamicBoss>> DYNAMIC_BOSSES = Map.of();

    /** Object names from Sonic1ObjectRegistry.getPrimaryName() switch cases. */
    private static final Map<Integer, List<String>> OBJECT_NAMES = buildNames();

    private static Map<Integer, List<String>> buildNames() {
        Map<Integer, List<String>> map = new HashMap<>();
        map.put(Sonic1ObjectIds.SONIC, List.of("Sonic"));
        map.put(Sonic1ObjectIds.BREAKABLE_POLE, List.of("PoleThatBreaks"));
        map.put(Sonic1ObjectIds.FLAPPING_DOOR, List.of("FlappingDoor"));
        map.put(Sonic1ObjectIds.SIGNPOST, List.of("Signpost"));
        map.put(Sonic1ObjectIds.BRIDGE, List.of("Bridge"));
        map.put(Sonic1ObjectIds.HARPOON, List.of("Harpoon"));
        map.put(Sonic1ObjectIds.SPIKED_POLE_HELIX, List.of("SpikedPoleHelix"));
        map.put(Sonic1ObjectIds.SWINGING_PLATFORM, List.of("SwingingPlatform"));
        map.put(Sonic1ObjectIds.PLATFORM, List.of("Platform"));
        map.put(Sonic1ObjectIds.COLLAPSING_LEDGE, List.of("CollapsingLedge"));
        map.put(Sonic1ObjectIds.SCENERY, List.of("Scenery"));
        map.put(Sonic1ObjectIds.CRABMEAT, List.of("Crabmeat"));
        map.put(Sonic1ObjectIds.BUZZ_BOMBER, List.of("BuzzBomber"));
        map.put(Sonic1ObjectIds.BUZZ_BOMBER_MISSILE, List.of("BuzzBomberMissile"));
        map.put(Sonic1ObjectIds.MISSILE_DISSOLVE, List.of("MissileDissolve"));
        map.put(Sonic1ObjectIds.RING, List.of("Ring"));
        map.put(Sonic1ObjectIds.MONITOR, List.of("Monitor"));
        map.put(Sonic1ObjectIds.ANIMALS, List.of("Animals", "Animal"));
        map.put(Sonic1ObjectIds.CHOPPER, List.of("Chopper"));
        map.put(Sonic1ObjectIds.JAWS, List.of("Jaws"));
        map.put(Sonic1ObjectIds.BURROBOT, List.of("Burrobot"));
        map.put(Sonic1ObjectIds.SPIKES, List.of("Spikes"));
        map.put(Sonic1ObjectIds.ROCK, List.of("Rock"));
        map.put(Sonic1ObjectIds.BREAKABLE_WALL, List.of("BreakableWall"));
        map.put(Sonic1ObjectIds.GHZ_BOSS, List.of("GHZBoss"));
        map.put(Sonic1ObjectIds.MZ_BOSS, List.of("MZBoss"));
        map.put(Sonic1ObjectIds.SYZ_BOSS, List.of("SYZBoss"));
        map.put(Sonic1ObjectIds.SYZ_BOSS_BLOCK, List.of("BossBlock"));
        map.put(Sonic1ObjectIds.SLZ_BOSS, List.of("SLZBoss", "BossStarLight"));
        map.put(Sonic1ObjectIds.FZ_BOSS, List.of("FZBoss", "BossFinal"));
        map.put(Sonic1ObjectIds.EGGMAN_CYLINDER, List.of("EggmanCylinder"));
        map.put(Sonic1ObjectIds.BOSS_PLASMA, List.of("BossPlasma"));
        map.put(Sonic1ObjectIds.SLZ_BOSS_SPIKEBALL, List.of("BossSpikeball"));
        map.put(Sonic1ObjectIds.BOSS_FIRE, List.of("BossFire"));
        map.put(Sonic1ObjectIds.EGG_PRISON, List.of("EggPrison"));
        map.put(Sonic1ObjectIds.MOTOBUG, List.of("Motobug"));
        map.put(Sonic1ObjectIds.SPRING, List.of("Spring"));
        map.put(Sonic1ObjectIds.EDGE_WALLS, List.of("EdgeWalls"));
        map.put(Sonic1ObjectIds.MZ_BRICK, List.of("MzBrick"));
        map.put(Sonic1ObjectIds.NEWTRON, List.of("Newtron"));
        map.put(Sonic1ObjectIds.ROLLER, List.of("Roller"));
        map.put(Sonic1ObjectIds.BUMPER, List.of("Bumper"));
        map.put(Sonic1ObjectIds.BOSS_BALL, List.of("BossBall"));
        map.put(Sonic1ObjectIds.WATERFALL_SOUND, List.of("WaterfallSound"));
        map.put(Sonic1ObjectIds.GIANT_RING, List.of("GiantRing"));
        map.put(Sonic1ObjectIds.YADRIN, List.of("Yadrin"));
        map.put(Sonic1ObjectIds.MZ_LARGE_GRASSY_PLATFORM, List.of("MzLargeGrassyPlatform"));
        map.put(Sonic1ObjectIds.MZ_GLASS_BLOCK, List.of("MzGlassBlock"));
        map.put(Sonic1ObjectIds.SMASH_BLOCK, List.of("SmashBlock"));
        map.put(Sonic1ObjectIds.PUSH_BLOCK, List.of("PushBlock"));
        map.put(Sonic1ObjectIds.CHAINED_STOMPER, List.of("ChainedStomper"));
        map.put(Sonic1ObjectIds.BURNING_GRASS, List.of("BurningGrass"));
        map.put(Sonic1ObjectIds.LAVA_BALL_MAKER, List.of("LavaBallMaker"));
        map.put(Sonic1ObjectIds.LAVA_BALL, List.of("LavaBall"));
        map.put(Sonic1ObjectIds.LAVA_GEYSER_MAKER, List.of("LavaGeyserMaker"));
        map.put(Sonic1ObjectIds.LAVA_GEYSER, List.of("LavaGeyser"));
        map.put(Sonic1ObjectIds.LAVA_TAG, List.of("LavaTag"));
        map.put(Sonic1ObjectIds.LAVA_WALL, List.of("LavaWall"));
        map.put(Sonic1ObjectIds.LABYRINTH_BLOCK, List.of("LabyrinthBlock"));
        map.put(Sonic1ObjectIds.LZ_CONVEYOR, List.of("LZConveyor"));
        map.put(Sonic1ObjectIds.BUBBLES, List.of("Bubbles"));
        map.put(Sonic1ObjectIds.WATERFALL, List.of("Waterfall"));
        map.put(Sonic1ObjectIds.BATBRAIN, List.of("Batbrain"));
        map.put(Sonic1ObjectIds.SLZ_ELEVATOR, List.of("Elevator"));
        map.put(Sonic1ObjectIds.SLZ_CIRCLING_PLATFORM, List.of("CirclingPlatform"));
        map.put(Sonic1ObjectIds.SLZ_STAIRCASE, List.of("Staircase"));
        map.put(Sonic1ObjectIds.PYLON, List.of("Pylon"));
        map.put(Sonic1ObjectIds.FAN, List.of("Fan"));
        map.put(Sonic1ObjectIds.SEESAW, List.of("Seesaw"));
        map.put(Sonic1ObjectIds.BOMB, List.of("Bomb"));
        map.put(Sonic1ObjectIds.ORBINAUT, List.of("Orbinaut"));
        map.put(Sonic1ObjectIds.INVISIBLE_BARRIER, List.of("InvisibleBarrier"));
        map.put(Sonic1ObjectIds.TELEPORTER, List.of("Teleporter"));
        map.put(Sonic1ObjectIds.ELECTROCUTER, List.of("Electrocuter"));
        map.put(Sonic1ObjectIds.SBZ_SMALL_DOOR, List.of("SmallDoor", "AutoDoor"));
        map.put(Sonic1ObjectIds.CATERKILLER, List.of("Caterkiller"));
        map.put(Sonic1ObjectIds.LAMPPOST, List.of("Lamppost"));
        map.put(Sonic1ObjectIds.HIDDEN_BONUS, List.of("HiddenBonus"));
        map.put(Sonic1ObjectIds.SBZ_SPIN_CONVEYOR, List.of("SpinConveyor", "SpinConvey"));
        map.put(Sonic1ObjectIds.END_SONIC, List.of("EndSonic"));
        map.put(Sonic1ObjectIds.END_CHAOS, List.of("EndChaos"));
        map.put(Sonic1ObjectIds.END_STH, List.of("EndSTH"));
        return Map.copyOf(map);
    }

    /** Map S1 LevelData index to zone/act for Sonic1ObjectPlacement. */
    private static final Map<LevelData, int[]> LEVEL_ZONE_ACT = Map.ofEntries(
            Map.entry(LevelData.S1_GREEN_HILL_1, new int[]{Sonic1Constants.ZONE_GHZ, 0}),
            Map.entry(LevelData.S1_GREEN_HILL_2, new int[]{Sonic1Constants.ZONE_GHZ, 1}),
            Map.entry(LevelData.S1_GREEN_HILL_3, new int[]{Sonic1Constants.ZONE_GHZ, 2}),
            Map.entry(LevelData.S1_LABYRINTH_1, new int[]{Sonic1Constants.ZONE_LZ, 0}),
            Map.entry(LevelData.S1_LABYRINTH_2, new int[]{Sonic1Constants.ZONE_LZ, 1}),
            Map.entry(LevelData.S1_LABYRINTH_3, new int[]{Sonic1Constants.ZONE_LZ, 2}),
            Map.entry(LevelData.S1_MARBLE_1, new int[]{Sonic1Constants.ZONE_MZ, 0}),
            Map.entry(LevelData.S1_MARBLE_2, new int[]{Sonic1Constants.ZONE_MZ, 1}),
            Map.entry(LevelData.S1_MARBLE_3, new int[]{Sonic1Constants.ZONE_MZ, 2}),
            Map.entry(LevelData.S1_STAR_LIGHT_1, new int[]{Sonic1Constants.ZONE_SLZ, 0}),
            Map.entry(LevelData.S1_STAR_LIGHT_2, new int[]{Sonic1Constants.ZONE_SLZ, 1}),
            Map.entry(LevelData.S1_STAR_LIGHT_3, new int[]{Sonic1Constants.ZONE_SLZ, 2}),
            Map.entry(LevelData.S1_SPRING_YARD_1, new int[]{Sonic1Constants.ZONE_SYZ, 0}),
            Map.entry(LevelData.S1_SPRING_YARD_2, new int[]{Sonic1Constants.ZONE_SYZ, 1}),
            Map.entry(LevelData.S1_SPRING_YARD_3, new int[]{Sonic1Constants.ZONE_SYZ, 2}),
            Map.entry(LevelData.S1_SCRAP_BRAIN_1, new int[]{Sonic1Constants.ZONE_SBZ, 0}),
            Map.entry(LevelData.S1_SCRAP_BRAIN_2, new int[]{Sonic1Constants.ZONE_SBZ, 1}),
            Map.entry(LevelData.S1_SCRAP_BRAIN_3, new int[]{Sonic1Constants.ZONE_SBZ, 2}),
            Map.entry(LevelData.S1_FINAL_ZONE, new int[]{Sonic1Constants.ZONE_ENDZ, 0})
    );

    @Override public String gameName() { return "Sonic 1"; }
    @Override public String gameId() { return "s1"; }
    @Override public String defaultRomPath() { return "s1.gen"; }
    @Override public String outputFilename() { return "S1_OBJECT_CHECKLIST.md"; }
    @Override public List<ObjectDiscoveryTool.LevelConfig> getLevels() { return LEVELS; }
    @Override public Set<Integer> getImplementedIds() { return IMPLEMENTED_IDS; }
    @Override public Set<Integer> getBadnikIds() { return BADNIK_IDS; }
    @Override public Set<Integer> getBossIds() { return BOSS_IDS; }
    @Override public Map<String, List<ObjectDiscoveryTool.DynamicBoss>> getDynamicBosses() { return DYNAMIC_BOSSES; }
    @Override public Map<Integer, List<String>> getObjectNames() { return OBJECT_NAMES; }

    @Override
    public List<PlcObjectMapping> getPlcObjectMappings(RomByteReader rom, ObjectDiscoveryTool.LevelConfig level) {
        int[] za = LEVEL_ZONE_ACT.get(level.levelData());
        int zoneIdx = za[0];

        // Extract PLC IDs from LevelHeaders (byte[0] = plc1, byte[4] = plc2)
        int base = Sonic1Constants.LEVEL_HEADERS_ADDR + zoneIdx * 16;
        int plc1Id = (rom.readU32BE(base) >> 24) & 0xFF;
        int plc2Id = (rom.readU32BE(base + 4) >> 24) & 0xFF;

        Set<Integer> plcIds = new LinkedHashSet<>();
        plcIds.add(plc1Id);
        plcIds.add(plc2Id);

        List<PlcObjectMapping> mappings = new ArrayList<>();
        for (int plcId : plcIds) {
            PlcParser.PlcDefinition def = PlcParser.parse(rom, Sonic1Constants.ART_LOAD_CUES_ADDR, plcId);
            for (PlcParser.PlcEntry entry : def.entries()) {
                Set<Integer> objIds = NEM_ADDR_TO_OBJECTS.get(entry.romAddr());
                if (objIds != null && !objIds.isEmpty()) {
                    mappings.add(new PlcObjectMapping(plcId, entry.romAddr(), objIds));
                }
            }
        }
        return mappings;
    }

    @Override
    public List<ObjectSpawn> loadObjects(RomByteReader rom, ObjectDiscoveryTool.LevelConfig level) {
        int[] za = LEVEL_ZONE_ACT.get(level.levelData());
        return new Sonic1ObjectPlacement(rom).load(za[0], za[1]);
    }

    @Override
    public boolean isFinalAct(ObjectDiscoveryTool.LevelConfig level) {
        return switch (level.shortName()) {
            case "FZ" -> level.act() == 1;
            default -> level.act() == 3;
        };
    }
}
