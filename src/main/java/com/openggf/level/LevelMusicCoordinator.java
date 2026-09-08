package com.openggf.level;

import com.openggf.audio.AudioManager;
import com.openggf.data.Game;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.ZoneKey;
import com.openggf.game.ZoneRegistry;

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
            ZoneRegistry registry,
            LevelTransitionCoordinator transitions,
            int levelIndex) throws IOException {
        return transitions.consumeSuppressNextMusicChange()
                ? OptionalInt.empty()
                : OptionalInt.of(resolveMusicId(game, registry, levelIndex));
    }

    static OptionalInt prepareCurrent(
            Game game,
            ZoneRegistry registry,
            LevelTransitionCoordinator transitions,
            LevelDescriptor selected) throws IOException {
        return selected == null
                ? OptionalInt.empty()
                : prepare(game, registry, transitions, selected.levelIndex());
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

    private static int resolveMusicId(Game game, ZoneRegistry registry, int levelIndex)
            throws IOException {
        // Additive descriptors have opaque indices, not stock ROM zone/act encodings.
        // Resolve their music through the same registry that owns their identity.
        if (registry != null) {
            for (int zone = 0; zone < registry.getZoneCount(); zone++) {
                if (!(registry.zoneKey(zone) instanceof ZoneKey.Mod)) continue;
                List<LevelDescriptor> acts = registry.getLevelDataForZone(zone);
                for (int act = 0; act < acts.size(); act++) {
                    if (acts.get(act).levelIndex() == levelIndex) {
                        return registry.getMusicId(zone, act);
                    }
                }
            }
        }
        return game.getMusicId(levelIndex);
    }

    static int currentMusicId(
            Game game,
            ZoneRegistry registry,
            List<List<LevelDescriptor>> levels,
            int currentZone,
            int currentAct,
            Logger logger) {
        if (game == null || levels == null || levels.isEmpty()) {
            return -1;
        }
        try {
            return resolveMusicId(game, registry, levels.get(currentZone).get(currentAct).levelIndex());
        } catch (Exception error) {
            logger.warning("Failed to get music ID for current level: " + error.getMessage());
            return -1;
        }
    }
}
