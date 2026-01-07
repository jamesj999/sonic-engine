package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;

public interface AudioBackend {
    void init();

    void setAudioProfile(GameAudioProfile profile);

    /**
     * Plays music by ID (potentially loading from ROM or fallback map).
     * 
     * @param musicId The music ID from the ROM/LevelData.
     */
    void playMusic(int musicId);

    void playSmps(AbstractSmpsData data, DacData dacData);

    void playSfxSmps(AbstractSmpsData data, DacData dacData);

    void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch);

    /**
     * Plays a sound effect by name (mapped to a WAV file).
     * 
     * @param sfxName The name of the SFX (e.g., "JUMP", "RING").
     */
    void playSfx(String sfxName);

    void playSfx(String sfxName, float pitch);

    /**
     * Stops any active music/streaming playback.
     */
    void stopPlayback();

    void toggleMute(ChannelType type, int channel);

    void toggleSolo(ChannelType type, int channel);

    boolean isMuted(ChannelType type, int channel);

    boolean isSoloed(ChannelType type, int channel);

    void setSpeedShoes(boolean enabled);

    void restoreMusic();

    /**
     * Ends a temporary music override (e.g., invincibility). If the override is
     * currently playing, it should restore the previous track. If it is queued
     * beneath another override, it should be removed so it does not resume.
     *
     * @param musicId The music ID to end.
     */
    void endMusicOverride(int musicId);

    void update();

    void destroy();
}
