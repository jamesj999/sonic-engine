package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.RomOffsetProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ROM offset provider for Sonic 2.
 * Wraps the Sonic2Constants class with a type-safe interface.
 *
 * <p>Offsets are categorized by their naming convention:
 * <ul>
 *   <li>"art" - Art offsets (ART_* constants)</li>
 *   <li>"palette" - Palette offsets (PAL_* constants)</li>
 *   <li>"level" - Level data offsets (LEVEL_*, LAYOUT_*, etc.)</li>
 *   <li>"mapping" - Mapping offsets (MAP_*, MAPPING_* constants)</li>
 * </ul>
 */
public class Sonic2RomOffsetProvider implements RomOffsetProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2RomOffsetProvider.class.getName());

    // Cache of offsets by category and name
    private final Map<String, Map<String, Integer>> offsetCache = new HashMap<>();

    public Sonic2RomOffsetProvider() {
        initializeCache();
    }

    private void initializeCache() {
        // Pre-populate cache from Sonic2Constants using reflection
        try {
            for (Field field : Sonic2Constants.class.getDeclaredFields()) {
                if (field.getType() == int.class || field.getType() == long.class) {
                    String name = field.getName();
                    int value = field.getInt(null);

                    // Categorize based on prefix
                    String category = categorizeOffset(name);
                    offsetCache.computeIfAbsent(category, k -> new HashMap<>())
                            .put(name, value);
                }
            }
            LOGGER.fine("Initialized Sonic 2 ROM offset cache with " +
                    offsetCache.values().stream().mapToInt(Map::size).sum() + " offsets");
        } catch (IllegalAccessException e) {
            LOGGER.warning("Failed to initialize ROM offset cache: " + e.getMessage());
        }
    }

    private String categorizeOffset(String name) {
        if (name.startsWith("ART_")) return "art";
        if (name.startsWith("PAL_")) return "palette";
        if (name.startsWith("LEVEL_") || name.startsWith("LAYOUT_")) return "level";
        if (name.startsWith("MAP_") || name.startsWith("MAPPING_")) return "mapping";
        if (name.startsWith("SOUND_") || name.startsWith("MUSIC_") || name.startsWith("SFX_")) return "sound";
        return "misc";
    }

    @Override
    public int getOffset(String category, String name) {
        Map<String, Integer> categoryMap = offsetCache.get(category);
        if (categoryMap != null) {
            Integer offset = categoryMap.get(name);
            if (offset != null) {
                return offset;
            }
        }
        return -1;
    }

    /**
     * Gets all offsets in a category.
     *
     * @param category the category name
     * @return map of name to offset, or empty map if category not found
     */
    public Map<String, Integer> getOffsetsInCategory(String category) {
        return offsetCache.getOrDefault(category, Map.of());
    }

    /**
     * Gets all available categories.
     *
     * @return set of category names
     */
    public java.util.Set<String> getCategories() {
        return offsetCache.keySet();
    }
}
