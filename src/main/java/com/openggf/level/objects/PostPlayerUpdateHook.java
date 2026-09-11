package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Optional hook for object logic that must observe the main player's
 * post-movement state for the current frame.
 *
 * <p>Legacy object-order modules execute regular object updates before the
 * separated player physics step, which means later SST-slot scripts cannot
 * directly see Sonic's just-updated air/position state. Objects that in the
 * ROM run after the player slot and only write globals for the following frame
 * can implement this hook instead of reading stale pre-physics state.</p>
 */
public interface PostPlayerUpdateHook {

    void updatePostPlayer(int frameCounter, PlayableEntity player);
}
