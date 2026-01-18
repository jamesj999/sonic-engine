package uk.co.jamesj999.sonic.game;

/**
 * Interface for accessing game-specific ROM offsets.
 * Provides type-safe access to ROM addresses without hardcoding offsets.
 *
 * <p>Each game module can provide its own offset provider with addresses
 * specific to that game's ROM layout.
 *
 * <p>Common offset categories:
 * <ul>
 *   <li>Art data (compressed/uncompressed graphics)</li>
 *   <li>Palette data</li>
 *   <li>Level data (layouts, collision, objects)</li>
 *   <li>Sound data (music, SFX)</li>
 *   <li>Sprite mappings</li>
 * </ul>
 */
public interface RomOffsetProvider {
    /**
     * Gets an offset by category and name.
     *
     * @param category the offset category (e.g., "art", "palette", "level")
     * @param name the specific offset name within the category
     * @return the ROM offset, or -1 if not found
     */
    int getOffset(String category, String name);

    /**
     * Gets an art data offset.
     *
     * @param artName the art data name
     * @return the ROM offset, or -1 if not found
     */
    default int getArtOffset(String artName) {
        return getOffset("art", artName);
    }

    /**
     * Gets a palette data offset.
     *
     * @param paletteName the palette name
     * @return the ROM offset, or -1 if not found
     */
    default int getPaletteOffset(String paletteName) {
        return getOffset("palette", paletteName);
    }

    /**
     * Gets a level data offset.
     *
     * @param levelDataName the level data name
     * @return the ROM offset, or -1 if not found
     */
    default int getLevelOffset(String levelDataName) {
        return getOffset("level", levelDataName);
    }

    /**
     * Checks if this provider has a specific offset.
     *
     * @param category the offset category
     * @param name the specific offset name
     * @return true if the offset exists
     */
    default boolean hasOffset(String category, String name) {
        return getOffset(category, name) >= 0;
    }
}
