package com.openggf.level;

import com.openggf.level.objects.InitialObjectDispatchScope;
import com.openggf.sprites.managers.ProcessSpritesEpoch;

interface InitialDynamicSstDispatcher {
    InitialObjectDispatchScope begin(ProcessSpritesEpoch epoch);

    void loadSprites();

    void processAbsoluteDynamicSlot3();

    void processManagedDynamicSlots4Through93();
}
