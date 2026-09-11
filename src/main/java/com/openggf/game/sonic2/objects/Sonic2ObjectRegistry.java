package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.boss.BossExplosionObjectInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2OOZBossInstance;
import com.openggf.level.objects.AbstractObjectRegistry;
import com.openggf.level.objects.ObjectFactory;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.game.sonic2.objects.badniks.AsteronBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.OctusBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.MasherBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.CoconutsBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.FlasherBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SpinyBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SpinyOnWallBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.ChopChopBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.WhispBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.CrawlBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.CrawltonBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SpikerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SpikerDrillObjectInstance;
import com.openggf.game.sonic2.objects.badniks.SolBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.RexonBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.ShellcrackerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.NebulaBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.BalkiryBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.CluckerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.WFZStickBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.WFZUnknownBadnikInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2ARZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2CNZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class Sonic2ObjectRegistry extends AbstractObjectRegistry {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ObjectRegistry.class.getName());

    private final Map<Integer, List<String>> namesById = new HashMap<>();
    private final Set<Integer> unknownIds = new HashSet<>();

    public Sonic2ObjectRegistry() {
    }

    @Override
    public ObjectInstance create(ObjectSpawn spawn) {
        ensureLoaded();
        int id = spawn.objectId();
        ObjectFactory factory = factories.get(id);
        if (factory == null) {
            factory = defaultFactory;
            if (!namesById.containsKey(id) && unknownIds.add(id)) {
                LOGGER.info(() -> String.format("Object registry missing id 0x%02X (seen in placement list).", id));
            }
        }
        return factory.create(spawn, this);
    }

    @Override
    public void registerFactory(int objectId, ObjectFactory factory) {
        factories.put(objectId & 0xFF, factory);
    }

    public boolean hasRegisteredFactory(int objectId) {
        ensureLoaded();
        return factories.containsKey(objectId & 0xFF);
    }

    @Override
    public String getPrimaryName(int objectId) {
        ensureLoaded();
        List<String> names = namesById.get(objectId);
        if (names == null || names.isEmpty()) {
            return String.format("Obj%02X", objectId & 0xFF);
        }
        return names.get(0);
    }

    @Override
    public List<String> getAliases(int objectId) {
        ensureLoaded();
        List<String> names = namesById.get(objectId);
        if (names == null) {
            return List.of();
        }
        return Collections.unmodifiableList(names);
    }

    @Override
    public ObjectSlotLayout objectSlotLayout() {
        return ObjectSlotLayout.SONIC_2;
    }

    @Override
    public com.openggf.level.objects.ObjectWindowingStrategy objectWindowingStrategy() {
        return S2ObjectWindowing.INSTANCE;
    }

    @Override
    public void reportCoverage(List<ObjectSpawn> spawns) {
        ensureLoaded();
        if (spawns == null || spawns.isEmpty()) {
            return;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        for (ObjectSpawn spawn : spawns) {
            counts.merge(spawn.objectId(), 1, Integer::sum);
        }

        int totalIds = counts.size();
        int missing = 0;
        List<String> missingEntries = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int id = entry.getKey();
            if (!namesById.containsKey(id)) {
                missing++;
                missingEntries.add(String.format("0x%02X (%d)", id, entry.getValue()));
            }
        }

        LOGGER.info(String.format("Object registry coverage: %d unique ids in level, %d missing names.",
                totalIds, missing));
        if (!missingEntries.isEmpty()) {
            LOGGER.info("Missing object ids: " + String.join(", ", missingEntries));
        }
    }

    @Override
    protected void registerDefaultFactories() {
        namesById.putAll(Sonic2ObjectRegistryData.NAMES_BY_ID);
        // LayerSwitcher (0x03) behavior is handled by ObjectManager.PlaneSwitchers,
        // but the ROM still allocates Obj03 into an SST slot before MarkObjGone3.
        registerFactory(Sonic2ObjectIds.LAYER_SWITCHER,
                (spawn, registry) -> new Sonic2LayerSwitcherObjectInstance(
                        spawn, registry.getPrimaryName(spawn.objectId())));

        registerFactory(Sonic2ObjectIds.SPRING,
                (spawn, registry) -> new SpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SPIKES,
                (spawn, registry) -> new SpikeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.MONITOR,
                (spawn, registry) -> new MonitorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.MONITOR_CONTENTS,
                (spawn, registry) -> new MonitorContentsObjectInstance(spawn, null));
        registerFactory(Sonic2ObjectIds.CHECKPOINT,
                (spawn, registry) -> new CheckpointObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // Springboard / Lever Spring (CPZ, ARZ, MCZ)
        registerFactory(Sonic2ObjectIds.SPRINGBOARD,
                (spawn, registry) -> new SpringboardObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // ARZ Leaves Generator
        registerFactory(Sonic2ObjectIds.LEAVES_GENERATOR,
                (spawn, registry) -> new LeavesGeneratorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // ARZ Bubble Generator (Object 0x24)
        // Subtype bit 7 determines mode: generator (invisible spawner) vs child bubble
        registerFactory(Sonic2ObjectIds.BUBBLES, (spawn, registry) -> {
            if ((spawn.subtype() & 0x80) != 0) {
                // Generator mode - invisible spawner that creates rising bubbles
                return new BubbleGeneratorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId()));
            } else {
                // Child bubble mode - shouldn't be in level data normally
                // but handle it by creating a rising bubble at that position
                return new BubbleObjectInstance(spawn.x(), spawn.y(), spawn.subtype() & 0x07, 0);
            }
        });

        // CPZ Objects
        registerFactory(Sonic2ObjectIds.TIPPING_FLOOR,
                (spawn, registry) -> new TippingFloorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SPEED_BOOSTER,
                (spawn, registry) -> new SpeedBoosterObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CPZ_SPIN_TUBE,
                (spawn, registry) -> new CPZSpinTubeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BLUE_BALLS,
                (spawn, registry) -> new BlueBallsObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BREAKABLE_BLOCK,
                (spawn, registry) -> new BreakableBlockObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.PIPE_EXIT_SPRING,
                (spawn, registry) -> new PipeExitSpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BARRIER,
                (spawn, registry) -> new BarrierObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CPZ_STAIRCASE,
                (spawn, registry) -> new CPZStaircaseObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CPZ_PYLON,
                (spawn, registry) -> new CPZPylonObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // CNZ Objects
        registerFactory(Sonic2ObjectIds.BUMPER,
                (spawn, registry) -> new BumperObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.HEX_BUMPER,
                (spawn, registry) -> new HexBumperObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BONUS_BLOCK,
                (spawn, registry) -> new BonusBlockObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.FLIPPER,
                (spawn, registry) -> new FlipperObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.FORCED_SPIN,
                (spawn, registry) -> new ForcedSpinObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.LAUNCHER_SPRING,
                (spawn, registry) -> new LauncherSpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CNZ_CONVEYOR_BELT,
                (spawn, registry) -> new CNZConveyorBeltObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CNZ_RECT_BLOCKS,
                (spawn, registry) -> new CNZRectBlocksObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CNZ_BIG_BLOCK,
                (spawn, registry) -> new CNZBigBlockObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CNZ_ELEVATOR,
                (spawn, registry) -> new ElevatorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.POINT_POKEY,
                (spawn, registry) -> new PointPokeyObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        ObjectFactory platformFactory = (spawn, registry) -> new PlatformObjectInstance(spawn,
                registry.getPrimaryName(spawn.objectId()));
        registerFactory(Sonic2ObjectIds.BRIDGE,
                (spawn, registry) -> new BridgeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BRIDGE_STAKE,
                (spawn, registry) -> new BridgeStakeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.EHZ_WATERFALL,
                (spawn, registry) -> new EHZWaterfallObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.OOZ_LAUNCHER,
                (spawn, registry) -> new OOZLauncherObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.LAUNCHER_BALL,
                (spawn, registry) -> new LauncherBallObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.FAN,
                (spawn, registry) -> new FanObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.OOZ_POPPING_PLATFORM,
                (spawn, registry) -> new OOZPoppingPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SLIDING_SPIKE,
                (spawn, registry) -> new SlidingSpikeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.OOZ_SPRING,
                (spawn, registry) -> new OOZSpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SPIRAL,
                (spawn, registry) -> new SpiralObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // OOZ Badniks
        registerFactory(Sonic2ObjectIds.OCTUS,
                (spawn, registry) -> new OctusBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.AQUIS,
                (spawn, registry) -> new AquisBadnikInstance(spawn));

        // EHZ Badniks
        registerFactory(Sonic2ObjectIds.MASHER,
                (spawn, registry) -> new MasherBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.BUZZER,
                (spawn, registry) -> new BuzzerBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.COCONUTS,
                (spawn, registry) -> new CoconutsBadnikInstance(spawn));

        // CPZ Badniks
        registerFactory(Sonic2ObjectIds.SPINY,
                (spawn, registry) -> new SpinyBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.SPINY_ON_WALL,
                (spawn, registry) -> new SpinyOnWallBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.GRABBER,
                (spawn, registry) -> new GrabberBadnikInstance(spawn));

        // ARZ Badniks
        registerFactory(Sonic2ObjectIds.CHOP_CHOP,
                (spawn, registry) -> new ChopChopBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.WHISP,
                (spawn, registry) -> new WhispBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.GROUNDER_IN_WALL,
                (spawn, registry) -> new GrounderBadnikInstance(spawn, false));
        registerFactory(Sonic2ObjectIds.GROUNDER_IN_WALL2,
                (spawn, registry) -> new GrounderBadnikInstance(spawn, true));
        // Note: GROUNDER_WALL (0x8F) and GROUNDER_ROCKS (0x90) are spawned dynamically

        // HTZ Fire Shooter (Obj20) - fire source that shoots paired fireballs
        registerFactory(Sonic2ObjectIds.LAVA_BUBBLE,
                (spawn, registry) -> new HtzFireShooterObjectInstance(spawn));

        // HTZ Badniks
        registerFactory(Sonic2ObjectIds.SPIKER,
                (spawn, registry) -> new SpikerBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.SPIKER_DRILL,
                (spawn, registry) -> new SpikerDrillObjectInstance(
                        spawn, spawn.x(), spawn.y(),
                        (spawn.renderFlags() & 0x01) != 0,
                        (spawn.renderFlags() & 0x02) != 0));
        registerFactory(Sonic2ObjectIds.SOL,
                (spawn, registry) -> new SolBadnikInstance(spawn));
        // Rexon (lava snake) - both 0x94 and 0x96 point to same implementation
        registerFactory(Sonic2ObjectIds.REXON,
                (spawn, registry) -> new RexonBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.REXON2,
                (spawn, registry) -> new RexonBadnikInstance(spawn));
        // Note: REXON_HEAD (0x97) is spawned dynamically by RexonBadnikInstance

        // CNZ Badniks
        registerFactory(Sonic2ObjectIds.CRAWL,
                (spawn, registry) -> new CrawlBadnikInstance(spawn));

        // MTZ Badniks
        registerFactory(Sonic2ObjectIds.ASTERON,
                (spawn, registry) -> new AsteronBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.SHELLCRACKER,
                (spawn, registry) -> new ShellcrackerBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.SLICER,
                (spawn, registry) -> new SlicerBadnikInstance(spawn));

        // MCZ Badniks
        registerFactory(Sonic2ObjectIds.CRAWLTON,
                (spawn, registry) -> new CrawltonBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.FLASHER,
                (spawn, registry) -> new FlasherBadnikInstance(spawn));

        // SCZ Badniks
        registerFactory(Sonic2ObjectIds.NEBULA,
                (spawn, registry) -> new NebulaBadnikInstance(spawn));
        registerFactory(Sonic2ObjectIds.TURTLOID,
                (spawn, registry) -> new TurtloidBadnikInstance(spawn));
        // Note: TURTLOID_RIDER (0x9B) and Turtloid jet are spawned dynamically by TurtloidBadnikInstance
        registerFactory(Sonic2ObjectIds.BALKIRY,
                (spawn, registry) -> new BalkiryBadnikInstance(spawn));
        // Note: BALKIRY_JET (0x9C) is spawned dynamically by BalkiryBadnikInstance

        // WFZ Badniks
        registerFactory(Sonic2ObjectIds.CLUCKER_BASE,
                (spawn, registry) -> new CluckerBaseObjectInstance(spawn));
        registerFactory(Sonic2ObjectIds.CLUCKER,
                (spawn, registry) -> new CluckerBadnikInstance(spawn));

        // Level completion objects
        registerFactory(Sonic2ObjectIds.SIGNPOST,
                (spawn, registry) -> new SignpostObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.EGG_PRISON,
                (spawn, registry) -> new EggPrisonObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // CNZ Boss (Object 0x51)
        registerFactory(Sonic2ObjectIds.CNZ_BOSS,
                (spawn, registry) -> new Sonic2CNZBossInstance(spawn));
        // HTZ Boss (Object 0x52)
        registerFactory(Sonic2ObjectIds.HTZ_BOSS,
                (spawn, registry) -> new Sonic2HTZBossInstance(spawn));
        // EHZ Boss (Object 0x56)
        registerFactory(Sonic2ObjectIds.EHZ_BOSS,
                (spawn, registry) -> new Sonic2EHZBossInstance(spawn));
        // OOZ Boss (Object 0x55)
        registerFactory(Sonic2ObjectIds.OOZ_BOSS,
                (spawn, registry) -> new Sonic2OOZBossInstance(spawn));
        // MCZ Boss (Object 0x57)
        registerFactory(Sonic2ObjectIds.MCZ_BOSS,
                (spawn, registry) -> new Sonic2MCZBossInstance(spawn));
        // CPZ Boss (Object 0x5D)
        registerFactory(Sonic2ObjectIds.CPZ_BOSS,
                (spawn, registry) -> new Sonic2CPZBossInstance(spawn));
        // ARZ Boss (Object 0x89)
        registerFactory(Sonic2ObjectIds.ARZ_BOSS,
                (spawn, registry) -> new Sonic2ARZBossInstance(spawn));
        // DEZ Mecha Sonic / Silver Sonic (Object 0xAF)
        registerFactory(Sonic2ObjectIds.MECHA_SONIC,
                (spawn, registry) -> new Sonic2MechaSonicInstance(spawn));
        // WFZ Boss (Object 0xC5) - Laser Platform Boss
        registerFactory(Sonic2ObjectIds.WFZ_BOSS,
                (spawn, registry) -> new Sonic2WFZBossInstance(spawn));
        // DEZ Eggman transition object (Object 0xC6)
        registerFactory(Sonic2ObjectIds.DEZ_EGGMAN,
                (spawn, registry) -> new Sonic2DEZEggmanInstance(spawn));
        // DEZ Death Egg Robot (Object 0xC7) - Final Boss
        registerFactory(Sonic2ObjectIds.DEATH_EGG_ROBOT,
                (spawn, registry) -> new Sonic2DeathEggRobotInstance(spawn));
        // Boss Explosion (Object 0x58)
        registerFactory(Sonic2ObjectIds.BOSS_EXPLOSION,
                (spawn, registry) -> new BossExplosionObjectInstance(
                        spawn.x(),
                        spawn.y(),
                        Sonic2ObjectIds.BOSS_EXPLOSION,
                        Sonic2Sfx.BOSS_EXPLOSION.id));

        // SwingingPlatform (Object 0x15) - chain-suspended platform in OOZ, ARZ, MCZ
        registerFactory(Sonic2ObjectIds.SWINGING_PLATFORM,
                (spawn, registry) -> new SwingingPlatformObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        registerFactory(Sonic2ObjectIds.GENERIC_PLATFORM_A,
                (spawn, registry) -> new ARZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.GENERIC_PLATFORM_B,
                (spawn, registry) -> new CPZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ Twin Stompers (Obj64) - crushing piston pair
        registerFactory(Sonic2ObjectIds.MTZ_TWIN_STOMPERS,
                (spawn, registry) -> new MTZTwinStompersObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // Button (Obj47) - trigger button that activates other objects via ButtonVine_Trigger
        registerFactory(Sonic2ObjectIds.BUTTON,
                (spawn, registry) -> new ButtonObjectInstance(spawn));

        // MTZ Spin Tube (Obj67) - tube transport with sinusoidal entry
        registerFactory(Sonic2ObjectIds.MTZ_SPIN_TUBE,
                (spawn, registry) -> new MTZSpinTubeObjectInstance(spawn));

        // MTZ spring wall - invisible solid wall that bounces player
        registerFactory(Sonic2ObjectIds.MTZ_SPRING_WALL,
                (spawn, registry) -> new MTZSpringWallObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ Nut - screw nut that moves vertically when player pushes it
        registerFactory(Sonic2ObjectIds.NUT,
                (spawn, registry) -> new NutObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ Long Platform (Obj65) - long moving platform with cog child
        registerFactory(Sonic2ObjectIds.MTZ_LONG_PLATFORM,
                (spawn, registry) -> {
                    // Properties index 2 = standalone cog (routine 6 in disassembly)
                    int propsIndex = ((spawn.subtype() >> 2) & 0x1C) >> 2;
                    if (propsIndex == 2) {
                        return new MTZLongPlatformCogInstance(spawn);
                    }
                    return new MTZLongPlatformObjectInstance(spawn);
                });

        // MTZ SpikyBlock (Obj68) - block with rotating spike from MTZ
        registerFactory(Sonic2ObjectIds.SPIKY_BLOCK,
                (spawn, registry) -> new SpikyBlockObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ SteamSpring (Obj42) - steam-powered spring piston from MTZ
        registerFactory(Sonic2ObjectIds.STEAM_SPRING,
                (spawn, registry) -> new SteamSpringObjectInstance(spawn));

        // MTZ Floor Spike (Obj6D) - retractable spike from MTZ
        registerFactory(Sonic2ObjectIds.FLOOR_SPIKE,
                (spawn, registry) -> new FloorSpikeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ LargeRotPform (Obj6E) - large rotating platform moving in circle
        registerFactory(Sonic2ObjectIds.LARGE_ROT_PFORM,
                (spawn, registry) -> new LargeRotPformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ Cog (Obj70) - giant rotating cog with 8 solid teeth
        registerFactory(Sonic2ObjectIds.COG,
                (spawn, registry) -> new CogObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ/CPZ multi-purpose platform with 12 movement subtypes
        registerFactory(Sonic2ObjectIds.MTZ_PLATFORM,
                (spawn, registry) -> new MTZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ Conveyor (Obj6C) - small platform on pulleys
        // Subtype bit 7 set = parent spawner (creates children), bit 7 clear = individual platform
        registerFactory(Sonic2ObjectIds.CONVEYOR,
                (spawn, registry) -> ConveyorObjectInstance.createOrSpawnChildren(spawn));

        // MTZ Lava Bubble (Obj71) - animated lava bubble scenery
        registerFactory(Sonic2ObjectIds.MTZ_LAVA_BUBBLE,
                (spawn, registry) -> new MTZLavaBubbleObjectInstance(spawn));

        // CPZ/MCZ horizontal moving platform
        registerFactory(Sonic2ObjectIds.SIDEWAYS_PFORM,
                (spawn, registry) -> new SidewaysPformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        registerFactory(Sonic2ObjectIds.INVISIBLE_BLOCK,
                (spawn, registry) -> new InvisibleBlockObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // ARZ Objects
        registerFactory(Sonic2ObjectIds.FALLING_PILLAR,
                (spawn, registry) -> new FallingPillarObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.RISING_PILLAR,
                (spawn, registry) -> new RisingPillarObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.ARROW_SHOOTER,
                (spawn, registry) -> new ArrowShooterObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // OOZ/MCZ/ARZ Collapsing Platform
        registerFactory(Sonic2ObjectIds.COLLAPSING_PLATFORM,
                (spawn, registry) -> new CollapsingPlatformObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // ARZ Swinging Platform
        registerFactory(Sonic2ObjectIds.SWINGING_PFORM,
                (spawn, registry) -> new SwingingPformObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ/MTZ Rotating Platforms (moving wooden crates)
        registerFactory(Sonic2ObjectIds.MCZ_ROT_PFORMS,
                (spawn, registry) -> new MCZRotPformsObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // ARZ Rotating Platforms
        registerFactory(Sonic2ObjectIds.ARZ_ROT_PFORMS,
                (spawn, registry) -> new ARZRotPformsObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // HTZ/MTZ Lava Marker (invisible hazard collision zone)
        registerFactory(Sonic2ObjectIds.LAVA_MARKER,
                (spawn, registry) -> new LavaMarkerObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // HTZ Rising Lava (invisible solid platform during earthquake)
        registerFactory(Sonic2ObjectIds.RISING_LAVA,
                (spawn, registry) -> new RisingLavaObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // HTZ Seesaw (Object 0x14)
        registerFactory(Sonic2ObjectIds.SEESAW,
                (spawn, registry) -> new SeesawObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // HTZ Zipline Lift (Object 0x16)
        registerFactory(Sonic2ObjectIds.HTZ_LIFT,
                (spawn, registry) -> new HTZLiftObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // HTZ Smashable Ground (Object 0x2F) - breakable rock platform
        registerFactory(Sonic2ObjectIds.SMASHABLE_GROUND,
                (spawn, registry) -> new SmashableGroundObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ Brick / Spike Ball (Object 0x75)
        registerFactory(Sonic2ObjectIds.MCZ_BRICK,
                (spawn, registry) -> new MCZBrickObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ Sliding Spikes (Object 0x76) - spike block that slides out of wall
        registerFactory(Sonic2ObjectIds.SLIDING_SPIKES,
                (spawn, registry) -> new SlidingSpikesObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ Stomper (Object 0x2A) - ceiling crusher
        registerFactory(Sonic2ObjectIds.STOMPER,
                (spawn, registry) -> new StomperObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ VineSwitch (Object 0x7F) - pull switch that triggers ButtonVine
        registerFactory(Sonic2ObjectIds.VINE_SWITCH,
                (spawn, registry) -> new VineSwitchObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ/WFZ MovingVine (Object 0x80) - vine pulley or hook on chain
        registerFactory(Sonic2ObjectIds.MOVING_VINE,
                (spawn, registry) -> new MovingVineObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ Bridge (Object 0x77) - horizontal gate triggered by ButtonVine
        registerFactory(Sonic2ObjectIds.MCZ_BRIDGE,
                (spawn, registry) -> new MCZBridgeObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // MCZ Drawbridge (Object 0x81) - rotatable drawbridge triggered by ButtonVine
        registerFactory(Sonic2ObjectIds.MCZ_DRAWBRIDGE,
                (spawn, registry) -> new MCZDrawbridgeObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // SCZ Cloud (ObjB3) - decorative scrolling clouds
        registerFactory(Sonic2ObjectIds.TORNADO,
                (spawn, registry) -> new TornadoObjectInstance(spawn));
        registerFactory(Sonic2ObjectIds.CLOUD,
                (spawn, registry) -> new CloudObjectInstance(spawn));

        // WFZ/SCZ Vertical Propeller (ObjB4) - animated spinning blade hazard
        registerFactory(Sonic2ObjectIds.VPROPELLER,
                (spawn, registry) -> new VPropellerObjectInstance(spawn));

        // WFZ/SCZ Horizontal Propeller (ObjB5) - horizontal spinning blade with upward push
        registerFactory(Sonic2ObjectIds.HPROPELLER,
                (spawn, registry) -> new HPropellerObjectInstance(spawn));

        // WFZ Palette Switcher (Obj8B) - cycling palette switcher
        registerFactory(Sonic2ObjectIds.WFZ_PAL_SWITCHER,
                (spawn, registry) -> new WFZPalSwitcherObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ Laser (ObjB9) - horizontal laser beam that fires leftward
        registerFactory(Sonic2ObjectIds.LASER,
                (spawn, registry) -> new LaserObjectInstance(spawn));

        // WFZ LateralCannon (ObjBE) - retracting platform
        registerFactory(Sonic2ObjectIds.LATERAL_CANNON,
                (spawn, registry) -> new LateralCannonObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ Stick (ObjBF) - unused rotating stick badnik in WFZ debug list
        registerFactory(Sonic2ObjectIds.WFZ_STICK,
                (spawn, registry) -> new WFZStickBadnikInstance(spawn));

        // WFZ Unknown (ObjBB) - removed unused object retained in the ROM pointer table
        registerFactory(Sonic2ObjectIds.WFZ_UNKNOWN,
                (spawn, registry) -> new WFZUnknownBadnikInstance(spawn));

        // WFZ WallTurret (ObjB8) - wall-mounted turret that shoots projectiles
        registerFactory(Sonic2ObjectIds.WALL_TURRET,
                (spawn, registry) -> new WallTurretObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ VerticalLaser (ObjB7) - unused huge vertical laser, also spawned by ObjB6
        registerFactory(Sonic2ObjectIds.VERTICAL_LASER,
                (spawn, registry) -> new VerticalLaserObjectInstance(spawn, spawn.x(), spawn.y()));

        // WFZ SpeedLauncher (ObjC0) - catapult platform that launches player
        registerFactory(Sonic2ObjectIds.SPEED_LAUNCHER,
                (spawn, registry) -> new SpeedLauncherObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ BreakablePlating (ObjC1) - breakable plating / grab point
        registerFactory(Sonic2ObjectIds.BREAKABLE_PLATING,
                (spawn, registry) -> new BreakablePlatingObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ Rivet (ObjC2) - rivet at end of WFZ that opens ship when busted
        registerFactory(Sonic2ObjectIds.RIVET,
                (spawn, registry) -> new RivetObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ Tornado smoke (ObjC3/ObjC4) - ObjC4 points to the same ROM routine
        registerFactory(Sonic2ObjectIds.TORNADO_SMOKE,
                (spawn, registry) -> new TornadoSmokeObjectInstance(spawn));
        registerFactory(Sonic2ObjectIds.TORNADO_SMOKE_2,
                (spawn, registry) -> new TornadoSmokeObjectInstance(spawn));

        // WFZ SmallMetalPform (ObjBD) - ascending/descending belt platform spawner
        registerFactory(Sonic2ObjectIds.SMALL_METAL_PFORM,
                (spawn, registry) -> new SmallMetalPformObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));

        // WFZ TiltingPlatform (ObjB6) - tilting/spinning platform with 4 behavior types
        registerFactory(Sonic2ObjectIds.TILTING_PLATFORM,
                (spawn, registry) -> new TiltingPlatformObjectInstance(spawn));

        // WFZ ShipFire (ObjBC) - flame from Robotnik's ship, flickers and tracks BG scroll
        registerFactory(Sonic2ObjectIds.WFZ_SHIP_FIRE,
                (spawn, registry) -> new WFZShipFireObjectInstance(spawn));

        // WFZ Wheel (ObjBA) - static conveyor belt wheel decoration
        registerFactory(Sonic2ObjectIds.WFZ_WHEEL,
                (spawn, registry) -> new WFZWheelObjectInstance(spawn));

        // WFZ Grab (ObjD9) - invisible hang-on point
        registerFactory(Sonic2ObjectIds.GRAB,
                (spawn, registry) -> new GrabObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));
    }
}
