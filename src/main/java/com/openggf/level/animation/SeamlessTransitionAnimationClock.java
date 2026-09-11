package com.openggf.level.animation;

/**
 * Animation state whose ROM update still runs after a seamless reload request
 * has replaced the level-owned managers.
 */
public interface SeamlessTransitionAnimationClock {
    void advanceForSeamlessTransition();
}
