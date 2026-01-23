package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsLoader;
import uk.co.jamesj999.sonic.data.Rom;

import java.util.Map;
import java.util.logging.Logger;

public class AudioManager {
    private static final Logger LOGGER = Logger.getLogger(AudioManager.class.getName());
    private static AudioManager instance;
    private AudioBackend backend;
    private SmpsLoader smpsLoader;
    private DacData dacData;
    private Map<GameSound, Integer> soundMap;
    private GameAudioProfile audioProfile;
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
            this.backend.setAudioProfile(audioProfile);
            LOGGER.info("AudioBackend initialized: " + backend.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize AudioBackend: " + e.getMessage());
            e.printStackTrace();
            this.backend = new NullAudioBackend();
        }
    }

    public void setAudioProfile(GameAudioProfile audioProfile) {
        this.audioProfile = audioProfile;
        if (backend != null) {
            backend.setAudioProfile(audioProfile);
        }
    }

    public GameAudioProfile getAudioProfile() {
        return audioProfile;
    }

    public void setRom(Rom rom) {
        if (audioProfile == null) {
            this.smpsLoader = null;
            this.dacData = null;
            return;
        }
        this.smpsLoader = audioProfile.createSmpsLoader(rom);
        this.dacData = smpsLoader != null ? smpsLoader.loadDacData() : null;
    }

    public void setSoundMap(Map<GameSound, Integer> soundMap) {
        this.soundMap = soundMap;
    }

    public void resetRingSound() {
        ringLeft = true;
    }

    public void playMusic(int musicId) {
        if (audioProfile != null) {
            if (musicId == audioProfile.getSpeedShoesOnCommandId()) {
                backend.setSpeedShoes(true);
                return;
            } else if (musicId == audioProfile.getSpeedShoesOffCommandId()) {
                backend.setSpeedShoes(false);
                return;
            }
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

    public void endMusicOverride(int musicId) {
        backend.endMusicOverride(musicId);
    }

    /**
     * Stops all music and sound playback.
     * Used when exiting special stages or changing game modes.
     */
    public void stopMusic() {
        if (backend != null) {
            backend.stopPlayback();
        }
    }

    /**
     * Fade out the currently playing music using ROM default timing.
     * ROM equivalent: MusID_FadeOut (0xF9) / zFadeOutMusic.
     * Does not affect SFX - only music channels fade.
     *
     * <p>ROM uses fadeOutMusic() in these situations (for future implementation):
     * <ul>
     *   <li>Special stage entry (s2.asm:6540) - IMPLEMENTED</li>
     *   <li>Special stage checkpoint fail (Obj5A, s2.asm:71358, 71878) - IMPLEMENTED</li>
     *   <li>Level entry - before entering a level with title card (s2.asm:4757) - IMPLEMENTED</li>
     *   <li>Boss area triggers - when approaching end-of-act boss fights
     *       (EHZ:20404, MTZ:20512, HTZ:21230, HPZ:21332, ARZ:21421, MCZ:21529, OOZ:21613, CNZ:21760)</li>
     *   <li>Title screen - starting new game (s2.asm:4526)</li>
     *   <li>Demo playback - before playing a demo (s2.asm:4581)</li>
     *   <li>WFZ/DEZ boss setup (s2.asm:77011, 80751)</li>
     *   <li>Ending sequence - final boss defeated, going to credits (s2.asm:82064, 82525)</li>
     * </ul>
     */
    public void fadeOutMusic() {
        // ROM default: 0x28 (40) steps, delay of 3 frames between steps
        fadeOutMusic(0x28, 3);
    }

    /**
     * Fade out the currently playing music over time.
     * ROM equivalent: MusID_FadeOut (0xF9) / zFadeOutMusic.
     * Does not affect SFX - only music channels fade.
     *
     * @param steps total number of volume steps (ROM default: 0x28 = 40)
     * @param delay frames between each volume step (ROM default: 3)
     */
    public void fadeOutMusic(int steps, int delay) {
        if (backend != null) {
            backend.fadeOutMusic(steps, delay);
        }
    }

    public void destroy() {
        if (backend != null) {
            backend.destroy();
        }
    }

    /**
     * Pauses audio playback. Called when the game window is minimized or loses focus.
     */
    public void pause() {
        if (backend != null) {
            backend.pause();
        }
    }

    /**
     * Resumes audio playback after being paused.
     */
    public void resume() {
        if (backend != null) {
            backend.resume();
        }
    }
}
