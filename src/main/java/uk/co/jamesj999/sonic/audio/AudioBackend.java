package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;

public interface AudioBackend {
    void init();
    /**
     * Plays music by ID (potentially loading from ROM or fallback map).
     * @param musicId The music ID from the ROM/LevelData.
     */
    void playMusic(int musicId);

    void playSmps(AbstractSmpsData data, DacData dacData);

    void playSfxSmps(AbstractSmpsData data, DacData dacData);

    /**
     * Plays a sound effect by name (mapped to a WAV file).
     * @param sfxName The name of the SFX (e.g., "JUMP", "RING").
     */
    void playSfx(String sfxName);

    /**
     * Stops any active music/streaming playback.
     */
    void stopPlayback();

    void toggleMute(ChannelType type, int channel);
    void toggleSolo(ChannelType type, int channel);
    boolean isMuted(ChannelType type, int channel);
    boolean isSoloed(ChannelType type, int channel);

    void update();
    void destroy();
}
