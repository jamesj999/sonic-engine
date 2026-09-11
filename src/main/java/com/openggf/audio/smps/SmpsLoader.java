package com.openggf.audio.smps;

public interface SmpsLoader {
    AbstractSmpsData loadMusic(int musicId);

    default LoadedSmpsMusic loadMusicWithReadiness(int musicId) {
        return LoadedSmpsMusic.immediate(loadMusic(musicId));
    }

    AbstractSmpsData loadSfx(int sfxId);

    AbstractSmpsData loadSfx(String sfxName);

    DacData loadDacData();

    /**
     * Returns the ROM offset for a music ID, or -1 if not available.
     * Only some loaders (e.g. Sonic 2) track this information.
     */
    default int findMusicOffset(int musicId) {
        return -1;
    }
}
