package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.animation.AnimatedPatternManager;

import java.io.IOException;

/**
 * Provides zone animated pattern managers for a game.
 */
public interface AnimatedPatternProvider {
    AnimatedPatternManager loadAnimatedPatternManager(Level level, int zoneIndex) throws IOException;
}
