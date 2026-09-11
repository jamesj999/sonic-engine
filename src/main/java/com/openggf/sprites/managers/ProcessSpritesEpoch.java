package com.openggf.sprites.managers;

/**
 * Immutable cadence values visible during one native {@code Process_Sprites}
 * dispatch.
 *
 * @param nativeLevelEpoch the ROM {@code Level_frame_counter} value
 * @param objectDispatchOrdinal the persistent object-dispatch ordinal
 * @param advanceGameplayCounter whether this dispatch publishes a new gameplay
 *                               frame in {@link SpriteManager}
 */
public record ProcessSpritesEpoch(
        int nativeLevelEpoch,
        int objectDispatchOrdinal,
        boolean advanceGameplayCounter) {

    static ProcessSpritesEpoch ordinary(
            int publishedLevelEpoch,
            int completedObjectDispatchOrdinal) {
        return new ProcessSpritesEpoch(
                publishedLevelEpoch,
                completedObjectDispatchOrdinal + 1,
                true);
    }
}
