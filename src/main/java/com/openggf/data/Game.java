package com.openggf.data;

import com.openggf.audio.GameSound;
import com.openggf.level.Level;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public abstract class Game {

    public abstract boolean isCompatible();

    public abstract String getIdentifier();

    public abstract List<String> getTitleCards();

    public abstract Level loadLevel(int levelIdx) throws IOException;

    /**
     * Publishes ROM-backed work for a fresh level assembly when the production
     * load owns a runtime hardware queue. Games without such a queue remain
     * synchronous.
     */
    public void queueFreshLevelRuntimeArt(int levelIdx) throws IOException {
        // Most game loaders complete level art synchronously.
    }

    public abstract int getMusicId(int levelIdx) throws IOException;

    public abstract Map<GameSound, Integer> getSoundMap();

    public abstract boolean canRelocateLevels();

    public abstract boolean canSave();

    public abstract boolean relocateLevels(boolean unsafe) throws IOException;

    public abstract boolean save(int levelIdx, Level level);

    /**
     * Calculates the background scroll position based on the camera position.
     * This mimics the game's background initialization/deformation routines.
     *
     * @param levelIdx The level index.
     * @param cameraX  The current camera X position.
     * @param cameraY  The current camera Y position.
     * @return An array containing [bgScrollX, bgScrollY].
     */
    public abstract Rom getRom();

    public abstract int[] getBackgroundScroll(int levelIdx, int cameraX, int cameraY);
}
