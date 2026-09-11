package com.openggf.sprites.managers;

/**
 * Dispatches the playable SST slots at the native {@code Process_Sprites}
 * boundary.
 */
public interface PlayableSstDispatcher {

    void processInitialPlayableSlots(
            ProcessSpritesEpoch epoch,
            InitialPlayableInput input);
}
