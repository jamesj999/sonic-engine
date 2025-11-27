package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.SmpsData;

public interface AudioBackend {
    void init();
    /**
     * Plays music by ID (potentially loading from ROM or fallback map).
     * @param musicId The music ID from the ROM/LevelData.
     */
    void playMusic(int musicId);

    void playSmps(SmpsData data);

    /**
     * Plays a sound effect by name (mapped to a WAV file).
     * @param sfxName The name of the SFX (e.g., "JUMP", "RING").
     */
    void playSfx(String sfxName);

    void update();
    void destroy();
}
