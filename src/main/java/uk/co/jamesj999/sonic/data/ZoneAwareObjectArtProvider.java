package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.level.objects.ObjectArtData;

import java.io.IOException;

/**
 * Optional interface for games that can provide zone-specific object art.
 */
public interface ZoneAwareObjectArtProvider extends ObjectArtProvider {
    ObjectArtData loadObjectArt(int zoneIndex) throws IOException;

    @Override
    default ObjectArtData loadObjectArt() throws IOException {
        return loadObjectArt(-1);
    }
}
