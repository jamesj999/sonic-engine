package com.openggf.game.rewind;

import com.openggf.LevelFrameResult;

/**
 * Drives the engine forward one frame using the given inputs. Owned by
 * the visualiser / engine glue, passed into RewindController.
 */
@FunctionalInterface
public interface EngineStepper {
    LevelFrameResult step(com.openggf.debug.playback.Bk2FrameInput inputs);
}
