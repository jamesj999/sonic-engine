package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.animation.AnimatedPaletteManager;

import java.io.IOException;

/**
 * Provides palette animation managers for a given level/zone.
 */
public interface AnimatedPaletteProvider {
    AnimatedPaletteManager loadAnimatedPaletteManager(Level level, int zoneIndex) throws IOException;
}
