package com.openggf.level;

import com.openggf.sprites.managers.ProcessSpritesEpoch;

record InitialProcessSpritesContext(
        InitialProcessSpritesStages stages,
        ProcessSpritesEpoch epoch) {
}
