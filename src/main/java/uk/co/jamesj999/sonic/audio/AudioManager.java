package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsLoader;
import uk.co.jamesj999.sonic.data.games.Sonic2Constants;
import uk.co.jamesj999.sonic.data.Rom;

import java.util.Map;
import java.util.logging.Logger;

public class AudioManager {
    private static final Logger LOGGER = Logger.getLogger(AudioManager.class.getName());
    private static AudioManager instance;
    private AudioBackend backend;
    private Sonic2SmpsLoader smpsLoader;
    private DacData dacData;
    private Map<GameSound, Integer> soundMap;
    private boolean ringLeft = true;

    private AudioManager() {
        // Default to NullBackend
        backend = new NullAudioBackend();
    }

    public static synchronized AudioManager getInstance() {
        if (instance == null) {
            instance = new AudioManager();
        }
        return instance;
    }

    public AudioBackend getBackend() {
        return backend;
    }

    public void setBackend(AudioBackend backend) {
        if (this.backend != null) {
            this.backend.destroy();
        }
        this.backend = backend;
        try {
            this.backend.init();
            LOGGER.info("AudioBackend initialized: " + backend.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize AudioBackend: " + e.getMessage());
            e.printStackTrace();
            this.backend = new NullAudioBackend();
        }
    }

    public void setRom(Rom rom) {
        this.smpsLoader = new Sonic2SmpsLoader(rom);
        this.dacData = smpsLoader.loadDacData();
    }

    public void setSoundMap(Map<GameSound, Integer> soundMap) {
        this.soundMap = soundMap;
    }

    public void resetRingSound() {
        ringLeft = true;
    }

    public void playMusic(int musicId) {
        if (musicId == Sonic2Constants.CMD_SPEED_UP) {
            backend.setSpeedShoes(true);
            return;
        } else if (musicId == Sonic2Constants.CMD_SLOW_DOWN) {
            backend.setSpeedShoes(false);
            return;
        }

        if (smpsLoader != null) {
            AbstractSmpsData data = smpsLoader.loadMusic(musicId);
            if (data != null) {
                backend.playSmps(data, dacData);
                return;
            }
        }
        backend.playMusic(musicId);
    }

    public void playSfx(String sfxName) {
        playSfx(sfxName, 1.0f);
    }

    public void playSfx(String sfxName, float pitch) {
        if (smpsLoader != null) {
            AbstractSmpsData sfx = smpsLoader.loadSfx(sfxName);
            if (sfx != null) {
                backend.playSfxSmps(sfx, dacData, pitch);
                return;
            }
        }
        backend.playSfx(sfxName, pitch);
    }

    public void playSfx(GameSound sound) {
        playSfx(sound, 1.0f);
    }

    public void playSfx(GameSound sound, float pitch) {
        if (sound == GameSound.RING) {
            playSfx(ringLeft ? GameSound.RING_LEFT : GameSound.RING_RIGHT, pitch);
            ringLeft = !ringLeft;
            return;
        }

        boolean played = false;
        if (soundMap != null && soundMap.containsKey(sound)) {
            played = playSfx(soundMap.get(sound), pitch);
        }
        if (!played) {
            backend.playSfx(sound.name(), pitch);
        }
    }

    public boolean playSfx(int sfxId) {
        return playSfx(sfxId, 1.0f);
    }

    public boolean playSfx(int sfxId, float pitch) {
        if (smpsLoader != null) {
            AbstractSmpsData sfx = smpsLoader.loadSfx(sfxId);
            if (sfx != null) {
                backend.playSfxSmps(sfx, dacData, pitch);
                return true;
            }
        }
        return false;
    }

    public void update() {
        backend.update();
    }

    public void destroy() {
        if (backend != null) {
            backend.destroy();
        }
    }
}
