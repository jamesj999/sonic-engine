package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;

import java.util.logging.Logger;

public class NullAudioBackend implements AudioBackend {
    private static final Logger LOGGER = Logger.getLogger(NullAudioBackend.class.getName());

    @Override
    public void init() {
        LOGGER.info("NullAudioBackend initialized (no audio output).");
    }

    @Override
    public void playMusic(int musicId) {
        LOGGER.info("NullAudioBackend: Requesting Music ID: " + Integer.toHexString(musicId));
    }

    @Override
    public void playSmps(SmpsData data, DacData dacData) {
        LOGGER.info("NullAudioBackend: Playing SMPS data.");
    }

    @Override
    public void playSfxSmps(SmpsData data, DacData dacData) {
        LOGGER.info("NullAudioBackend: Playing SFX SMPS data.");
    }

    @Override
    public void playSfx(String sfxName) {
        LOGGER.info("NullAudioBackend: Playing SFX: " + sfxName);
    }

    @Override
    public void stopPlayback() {
        LOGGER.info("NullAudioBackend: Stopping playback.");
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
