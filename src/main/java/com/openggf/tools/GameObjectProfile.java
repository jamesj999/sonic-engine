package com.openggf.tools;

import com.openggf.data.RomByteReader;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tools.ObjectDiscoveryTool.DynamicBoss;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Game-specific data profile for the ObjectDiscoveryTool.
 * Each game (S1, S2, S3K) provides its own implementation.
 */
public interface GameObjectProfile {

    /** Human-readable game name for report title. */
    String gameName();

    /** Short game ID (s1, s2, s3k). */
    String gameId();

    /** Default ROM filename. */
    String defaultRomPath();

    /** Output markdown filename. */
    String outputFilename();

    /** Ordered list of levels to scan. */
    List<LevelConfig> getLevels();

    /** Object IDs that have factory or manager-based implementations. */
    Set<Integer> getImplementedIds();

    /**
     * Optional Markdown note emitted under the generated header, describing what
     * "implemented" certifies for this game. {@code null} emits nothing.
     */
    default String coverageNote() {
        return null;
    }

    /**
     * Implemented IDs for a specific level. Games with zone-set-dependent
     * object tables (e.g. S3K) override this to avoid false positives
     * when the same ID maps to different objects per zone set.
     */
    default Set<Integer> getImplementedIds(LevelConfig level) {
        return getImplementedIds();
    }

    /** Object IDs classified as badniks. */
    Set<Integer> getBadnikIds();

    /** Object IDs classified as bosses. */
    Set<Integer> getBossIds();

    /** Bosses spawned dynamically (not in placement data), keyed by zone short name. */
    Map<String, List<DynamicBoss>> getDynamicBosses();

    /** Object name lookup: ID -> [primary name, alias1, alias2, ...]. */
    Map<Integer, List<String>> getObjectNames();

    /**
     * Object name lookup for a specific level. Games with zone-set-dependent
     * object tables (e.g. S3K) override this to return the correct names.
     */
    default Map<Integer, List<String>> getObjectNames(LevelConfig level) {
        return getObjectNames();
    }

    /**
     * Badnik IDs for a specific level. Games with zone-set-dependent
     * tables override this.
     */
    default Set<Integer> getBadnikIds(LevelConfig level) {
        return getBadnikIds();
    }

    /**
     * Boss IDs for a specific level. Games with zone-set-dependent
     * tables override this.
     */
    default Set<Integer> getBossIds(LevelConfig level) {
        return getBossIds();
    }

    /** Load object placements for the given level. */
    List<ObjectSpawn> loadObjects(RomByteReader rom, LevelConfig level);

    /** Whether this level is the final act of its zone (for dynamic boss display). */
    boolean isFinalAct(LevelConfig level);

    /** A PLC entry cross-referenced with the object IDs it loads art for. */
    record PlcObjectMapping(int plcId, int nemesisRomAddr, Set<Integer> objectIds) {}

    /**
     * Returns PLC entries cross-referenced with object IDs for a given level.
     * Each mapping associates a PLC entry's Nemesis ROM address with the object
     * IDs that use that art. Default returns empty (opt-in per game).
     */
    default List<PlcObjectMapping> getPlcObjectMappings(RomByteReader rom, LevelConfig level) {
        return List.of();
    }
}
