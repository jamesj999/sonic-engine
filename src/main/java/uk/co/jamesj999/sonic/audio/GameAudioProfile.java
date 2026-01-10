package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.SmpsLoader;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencerConfig;
import uk.co.jamesj999.sonic.data.Rom;

public interface GameAudioProfile {
    SmpsLoader createSmpsLoader(Rom rom);

    SmpsSequencerConfig getSequencerConfig();

    int getSpeedShoesOnCommandId();

    int getSpeedShoesOffCommandId();

    int getInvincibilityMusicId();

    int getExtraLifeMusicId();

    default boolean isMusicOverride(int musicId) {
        return musicId == getInvincibilityMusicId() || musicId == getExtraLifeMusicId();
    }
}
