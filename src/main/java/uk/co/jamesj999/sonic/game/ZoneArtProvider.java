package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.tools.disasm.CompressionType;

import java.util.List;

/**
 * Provider interface for zone-specific art loading configurations.
 * This abstracts the mapping of object IDs to art addresses, palettes,
 * and compression types based on the current zone.
 *
 * Each game implementation provides its own ZoneArtProvider that knows
 * which art assets to load for each zone and object type.
 */
public interface ZoneArtProvider {

    /**
     * Gets the art configuration for a specific object in the given zone.
     *
     * @param objectId the object type ID
     * @param zoneId the zone index
     * @return the art configuration, or null if no zone-specific art is needed
     */
    ObjectArtConfig getObjectArt(int objectId, int zoneId);

    /**
     * Gets all art load requests needed for a specific zone/act.
     * This can be used for preloading zone-specific art at level start.
     *
     * @param zoneId the zone index
     * @param actId the act index
     * @return list of art load requests for this zone/act
     */
    List<ArtLoadRequest> getZoneArt(int zoneId, int actId);

    /**
     * Configuration for loading a single object's art.
     *
     * @param artAddress ROM address of the art data
     * @param palette palette line to use (0-3)
     * @param compression compression type used
     */
    record ObjectArtConfig(int artAddress, int palette, CompressionType compression) {
        /**
         * Creates a Nemesis-compressed art config.
         */
        public static ObjectArtConfig nemesis(int artAddress, int palette) {
            return new ObjectArtConfig(artAddress, palette, CompressionType.NEMESIS);
        }

        /**
         * Creates an uncompressed art config.
         */
        public static ObjectArtConfig uncompressed(int artAddress, int palette) {
            return new ObjectArtConfig(artAddress, palette, CompressionType.UNCOMPRESSED);
        }
    }

    /**
     * Request to load art for a zone.
     *
     * @param name descriptive name for debugging
     * @param artAddress ROM address of the art data
     * @param palette palette line to use
     * @param compression compression type
     */
    record ArtLoadRequest(String name, int artAddress, int palette, CompressionType compression) {
        public static ArtLoadRequest nemesis(String name, int artAddress, int palette) {
            return new ArtLoadRequest(name, artAddress, palette, CompressionType.NEMESIS);
        }
    }
}
