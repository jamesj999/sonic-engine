package com.openggf.game.sonic3k;

import com.openggf.sprites.managers.ProcessSpritesEpoch;

/** Semantic owner of S3K fixed SST slot 109 ({@code Wave_Splash}). */
public interface InitialWaveSplashSstOwner {
    boolean isRegistered();

    void processInitialWaveSplash(ProcessSpritesEpoch epoch);
}
