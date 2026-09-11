package com.openggf.debug.playback;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.sprites.managers.SpriteManager;

/** Owns publication and cleanup of recorded logical input during playback. */
public final class PlaybackInputBridge {
    private boolean inputSuppressed;
    private boolean logicalOverrideApplied;

    public void sync(
            PlaybackDebugManager playback,
            GameMode mode,
            InputHandler input,
            SpriteManager sprites) {
        boolean shouldDrive = playback.isDriving(mode);
        if (sprites == null) {
            playback.clearLastAppliedState();
            clearOwnedOverride(input);
            inputSuppressed = false;
            return;
        }
        if (shouldDrive != inputSuppressed) {
            sprites.setPlaybackInputSuppressed(shouldDrive);
            inputSuppressed = shouldDrive;
        }
        if (shouldDrive) {
            publishImmediately(playback, input, sprites);
            return;
        }
        playback.clearLastAppliedState();
        clearOwnedOverride(input);
    }

    public void publishImmediately(
            PlaybackDebugManager playback,
            InputHandler input,
            SpriteManager sprites) {
        if (sprites != null && !inputSuppressed) {
            sprites.setPlaybackInputSuppressed(true);
            inputSuppressed = true;
        }
        if (input != null) {
            input.setLogicalOverride(playback.getCurrentLogicalInputSnapshot());
            logicalOverrideApplied = true;
        }
    }

    private void clearOwnedOverride(InputHandler input) {
        if (!logicalOverrideApplied) {
            return;
        }
        if (input != null) {
            input.clearLogicalOverride();
            input.refreshLogicalSnapshot();
        }
        logicalOverrideApplied = false;
    }
}
