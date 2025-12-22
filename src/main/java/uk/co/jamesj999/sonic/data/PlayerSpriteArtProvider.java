package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

import java.io.IOException;

/**
 * Optional interface for games that can provide player sprite art from ROM.
 */
public interface PlayerSpriteArtProvider {
    SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException;
}
