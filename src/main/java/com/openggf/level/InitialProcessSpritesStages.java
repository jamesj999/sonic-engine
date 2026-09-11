package com.openggf.level;

import com.openggf.sprites.managers.PlayableSstDispatcher;

record InitialProcessSpritesStages(
        InitialDynamicSstDispatcher dynamic,
        PlayableSstDispatcher playables,
        CollisionListSstDispatcher collisionList,
        InitialFixedSstDispatcher fixed) {
}
