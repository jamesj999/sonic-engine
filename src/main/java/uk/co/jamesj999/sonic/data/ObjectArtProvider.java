package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.level.objects.ObjectArtData;

import java.io.IOException;

/**
 * Optional interface for games that can provide ROM-backed object art.
 */
public interface ObjectArtProvider {
    ObjectArtData loadObjectArt() throws IOException;
}
