package uk.co.jamesj999.sonic.audio.smps;

public interface SmpsLoader {
    AbstractSmpsData loadMusic(int musicId);

    AbstractSmpsData loadSfx(int sfxId);

    AbstractSmpsData loadSfx(String sfxName);

    DacData loadDacData();
}
