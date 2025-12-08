package uk.co.jamesj999.sonic.audio.smps;

public interface SmpsLoader {
    AbstractSmpsData loadMusic(int musicId);
    AbstractSmpsData loadSfx(String name);
    DacData loadDacData();
    int findMusicOffset(int musicId);
}
