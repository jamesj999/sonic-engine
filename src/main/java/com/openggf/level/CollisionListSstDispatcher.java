package com.openggf.level;

interface CollisionListSstDispatcher {
    void freezePreviousReadView();

    void resetCurrentBuild();

    default void markDynamicBuildComplete() {
    }

    void captureCompletedBuild();
}
