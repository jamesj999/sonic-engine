package com.openggf.level;

import com.openggf.sprites.managers.ProcessSpritesEpoch;

@FunctionalInterface
public interface InitialFixedSstDispatcher {
    default void onInitialScopeAcquired() {
    }

    void processPostDynamicFixedSlots(ProcessSpritesEpoch epoch);
}
