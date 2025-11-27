package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.SmpsData;

public class NullAudioBackend implements AudioBackend {
    @Override
    public void init() {
        // No-op
    }

    @Override
    public void playMusic(int musicId) {
        // No-op
    }

    @Override
    public void playSmps(SmpsData data) {
        // No-op
    }

    @Override
    public void playSfx(String sfxName) {
        // No-op
    }

    @Override
    public void update() {
        // No-op
    }

    @Override
    public void destroy() {
        // No-op
    }
}
