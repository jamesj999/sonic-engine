package com.openggf.level;

import com.openggf.audio.AudioManager;
import com.openggf.data.Game;
import com.openggf.game.LevelInitProfile;

import java.io.IOException;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Logger;

/** Focused policy for the level manager's level-music publication facade. */
final class LevelMusicCoordinator {
    private LevelMusicCoordinator() {
    }

    static boolean beginEntry(boolean alreadyBegun, LevelInitProfile profile) {
        if (!alreadyBegun) {
            profile.beginLevelEntry();
        }
        return true;
    }

    static OptionalInt prepare(
            Game game,
            LevelTransitionCoordinator transitions,
            int levelIndex) throws IOException {
        return transitions.consumeSuppressNextMusicChange()
                ? OptionalInt.empty()
                : OptionalInt.of(game.getMusicId(levelIndex));
    }

    static OptionalInt prepareCurrent(
            Game game,
            LevelTransitionCoordinator transitions,
            LevelData selected) throws IOException {
        return selected == null
                ? OptionalInt.empty()
                : prepare(game, transitions, selected.getLevelIndex());
    }

    /**
     * Publishes a resolved playlist request, unless the active game profile
     * still owns an unreleased level-music publication. That gate keeps the
     * profile's scheduled boundary the single publisher during level entry.
     */
    static void publish(AudioManager audioManager, int musicId,
            LevelInitProfile profile) {
        if (musicId >= 0 && !profile.isLevelMusicPublicationPending()) {
            audioManager.playMusic(musicId);
        }
    }

    static int currentMusicId(
            Game game,
            List<List<LevelData>> levels,
            int currentZone,
            int currentAct,
            Logger logger) {
        if (game == null || levels == null || levels.isEmpty()) {
            return -1;
        }
        try {
            return game.getMusicId(levels.get(currentZone).get(currentAct).getLevelIndex());
        } catch (Exception error) {
            logger.warning("Failed to get music ID for current level: " + error.getMessage());
            return -1;
        }
    }
}
