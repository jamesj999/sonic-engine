package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.ZoneArtProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.tools.disasm.CompressionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Sonic 2-specific implementation of ZoneArtProvider.
 * Maps object IDs to zone-specific art addresses, palettes, and compression types.
 */
public class Sonic2ZoneArtProvider implements ZoneArtProvider {

    @Override
    public ObjectArtConfig getObjectArt(int objectId, int zoneId) {
        return switch (objectId) {
            case Sonic2ObjectIds.GENERIC_PLATFORM_B -> getPlatformArt(zoneId);
            default -> null;
        };
    }

    @Override
    public List<ArtLoadRequest> getZoneArt(int zoneId, int actId) {
        List<ArtLoadRequest> requests = new ArrayList<>();

        // Add zone-specific platform art if applicable
        ObjectArtConfig platformArt = getPlatformArt(zoneId);
        if (platformArt != null) {
            requests.add(new ArtLoadRequest(
                    "Platform_" + zoneId,
                    platformArt.artAddress(),
                    platformArt.palette(),
                    platformArt.compression()));
        }

        return requests;
    }

    /**
     * Gets the art configuration for CPZ/OOZ/WFZ moving platforms (Object 0x19).
     * Each zone uses different art and potentially different palettes.
     */
    private ObjectArtConfig getPlatformArt(int zoneId) {
        return switch (zoneId) {
            case Sonic2Constants.ZONE_OIL_OCEAN ->
                    ObjectArtConfig.nemesis(Sonic2Constants.ART_NEM_OOZ_ELEVATOR_ADDR, 3);
            case Sonic2Constants.ZONE_WING_FORTRESS ->
                    ObjectArtConfig.nemesis(Sonic2Constants.ART_NEM_WFZ_PLATFORM_ADDR, 1);
            case Sonic2Constants.ZONE_CHEMICAL_PLANT ->
                    ObjectArtConfig.nemesis(Sonic2Constants.ART_NEM_CPZ_ELEVATOR_ADDR, 3);
            default ->
                    // Default to CPZ art for other zones (object may not appear but provides fallback)
                    ObjectArtConfig.nemesis(Sonic2Constants.ART_NEM_CPZ_ELEVATOR_ADDR, 3);
        };
    }
}
