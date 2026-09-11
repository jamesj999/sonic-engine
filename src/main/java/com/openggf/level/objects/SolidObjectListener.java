package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

public interface SolidObjectListener {
    /**
     * Compatibility adapter only. Manual-checkpoint objects should branch on
     * PlayerSolidContactResult instead of relying on this callback long-term.
     */
    void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter);

    default void onSolidContactCleared(PlayableEntity player, int frameCounter) {
    }
}
