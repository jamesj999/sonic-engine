package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.level.objects.ObjectFactory;
import uk.co.jamesj999.sonic.level.objects.ObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRegistry;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.PlaceholderObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.MasherBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.BuzzerBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.CoconutsBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.SpinyBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.SpinyOnWallBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.GrabberBadnikInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class Sonic2ObjectRegistry implements ObjectRegistry {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ObjectRegistry.class.getName());
    private static Sonic2ObjectRegistry instance;

    private final Map<Integer, List<String>> namesById = new HashMap<>();
    private final Map<Integer, ObjectFactory> factories = new HashMap<>();
    private final Set<Integer> unknownIds = new HashSet<>();
    private boolean loaded;

    private final ObjectFactory defaultFactory = (spawn, registry) -> new PlaceholderObjectInstance(spawn,
            registry.getPrimaryName(spawn.objectId()));

    private Sonic2ObjectRegistry() {
    }

    public static synchronized Sonic2ObjectRegistry getInstance() {
        if (instance == null) {
            instance = new Sonic2ObjectRegistry();
        }
        return instance;
    }

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

    public void registerFactory(int objectId, ObjectFactory factory) {
        ensureLoaded();
        factories.put(objectId & 0xFF, factory);
    }

    public String getPrimaryName(int objectId) {
        ensureLoaded();
        List<String> names = namesById.get(objectId);
        if (names == null || names.isEmpty()) {
            return String.format("Obj%02X", objectId & 0xFF);
        }
        return names.get(0);
    }

    public List<String> getAliases(int objectId) {
        ensureLoaded();
        List<String> names = namesById.get(objectId);
        if (names == null) {
            return List.of();
        }
        return Collections.unmodifiableList(names);
    }

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

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        namesById.putAll(Sonic2ObjectRegistryData.NAMES_BY_ID);
        registerDefaultFactories();
        LOGGER.fine("Loaded " + namesById.size() + " object name ids from built-in registry.");
    }

    private void registerDefaultFactories() {
        registerFactory(Sonic2ObjectIds.SPRING,
                (spawn, registry) -> new SpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SPIKES,
                (spawn, registry) -> new SpikeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.MONITOR,
                (spawn, registry) -> new MonitorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.CHECKPOINT,
                (spawn, registry) -> new CheckpointObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // Springboard / Lever Spring (CPZ, ARZ, MCZ)
        registerFactory(Sonic2ObjectIds.SPRINGBOARD,
                (spawn, registry) -> new SpringboardObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // ARZ Leaves Generator
        registerFactory(Sonic2ObjectIds.LEAVES_GENERATOR,
                (spawn, registry) -> new LeavesGeneratorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

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

        ObjectFactory platformFactory = (spawn, registry) -> new PlatformObjectInstance(spawn,
                registry.getPrimaryName(spawn.objectId()));
        registerFactory(Sonic2ObjectIds.BRIDGE,
                (spawn, registry) -> new BridgeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.BRIDGE_STAKE,
                (spawn, registry) -> new BridgeStakeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.EHZ_WATERFALL,
                (spawn, registry) -> new EHZWaterfallObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.SPIRAL,
                (spawn, registry) -> new SpiralObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // EHZ Badniks
        registerFactory(Sonic2ObjectIds.MASHER,
                (spawn, registry) -> new MasherBadnikInstance(spawn, LevelManager.getInstance()));
        registerFactory(Sonic2ObjectIds.BUZZER,
                (spawn, registry) -> new BuzzerBadnikInstance(spawn, LevelManager.getInstance()));
        registerFactory(Sonic2ObjectIds.COCONUTS,
                (spawn, registry) -> new CoconutsBadnikInstance(spawn, LevelManager.getInstance()));

        // CPZ Badniks
        registerFactory(Sonic2ObjectIds.SPINY,
                (spawn, registry) -> new SpinyBadnikInstance(spawn, LevelManager.getInstance()));
        registerFactory(Sonic2ObjectIds.SPINY_ON_WALL,
                (spawn, registry) -> new SpinyOnWallBadnikInstance(spawn, LevelManager.getInstance()));
        registerFactory(Sonic2ObjectIds.GRABBER,
                (spawn, registry) -> new GrabberBadnikInstance(spawn, LevelManager.getInstance()));

        // Level completion objects
        registerFactory(Sonic2ObjectIds.SIGNPOST,
                (spawn, registry) -> new SignpostObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        registerFactory(Sonic2ObjectIds.SWINGING_PLATFORM, platformFactory);

        registerFactory(Sonic2ObjectIds.GENERIC_PLATFORM_A,
                (spawn, registry) -> new ARZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(Sonic2ObjectIds.GENERIC_PLATFORM_B,
                (spawn, registry) -> new CPZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        // MTZ/CPZ multi-purpose platform with 12 movement subtypes
        registerFactory(Sonic2ObjectIds.MTZ_PLATFORM,
                (spawn, registry) -> new MTZPlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

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

        // OOZ/MCZ/ARZ Collapsing Platform
        registerFactory(Sonic2ObjectIds.COLLAPSING_PLATFORM,
                (spawn, registry) -> new CollapsingPlatformObjectInstance(spawn,
                        registry.getPrimaryName(spawn.objectId())));
    }
}
