package uk.co.jamesj999.sonic.level.objects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class ObjectRegistry {
    private static final Logger LOGGER = Logger.getLogger(ObjectRegistry.class.getName());
    private static ObjectRegistry instance;

    private final Map<Integer, List<String>> namesById = new HashMap<>();
    private final Map<Integer, ObjectFactory> factories = new HashMap<>();
    private final Set<Integer> unknownIds = new HashSet<>();
    private boolean loaded;

    private final ObjectFactory defaultFactory = (spawn, registry) ->
            new PlaceholderObjectInstance(spawn, registry.getPrimaryName(spawn.objectId()));

    private ObjectRegistry() {
    }

    public static synchronized ObjectRegistry getInstance() {
        if (instance == null) {
            instance = new ObjectRegistry();
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
        namesById.putAll(ObjectRegistryData.NAMES_BY_ID);
        registerDefaultFactories();
        LOGGER.info("Loaded " + namesById.size() + " object name ids from built-in registry.");
    }

    private void registerDefaultFactories() {
        registerFactory(ObjectIds.SPRING, (spawn, registry) ->
                new SpringObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(ObjectIds.SPIKES, (spawn, registry) ->
                new SpikeObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(ObjectIds.MONITOR, (spawn, registry) ->
                new MonitorObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));
        registerFactory(ObjectIds.CHECKPOINT, (spawn, registry) ->
                new CheckpointObjectInstance(spawn, registry.getPrimaryName(spawn.objectId())));

        ObjectFactory platformFactory = (spawn, registry) ->
                new PlatformObjectInstance(spawn, registry.getPrimaryName(spawn.objectId()));
        registerFactory(ObjectIds.BRIDGE, platformFactory);
        registerFactory(ObjectIds.SWINGING_PLATFORM, platformFactory);
        registerFactory(ObjectIds.GENERIC_PLATFORM_A, platformFactory);
        registerFactory(ObjectIds.GENERIC_PLATFORM_B, platformFactory);
    }
}
