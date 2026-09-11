package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AudioTestFixtures {
    public static final DacData EMPTY_DAC = new DacData(
            Collections.emptyMap(), Collections.emptyMap(), 288);

    private AudioTestFixtures() {
    }

    public static final class RecordingAudioBackend extends NullAudioBackend {
        public final List<String> calls = new ArrayList<>();

        public int totalCalls() {
            return calls.size();
        }

        public void clear() {
            calls.clear();
        }

        @Override public void playMusic(int musicId) { calls.add("playMusic:" + musicId); }
        @Override public void playSmps(AbstractSmpsData data, DacData dacData) { calls.add("playSmps:" + data); }
        @Override public void playSmps(AbstractSmpsData data, DacData dacData, SmpsSequencerConfig config, boolean forceOverride) { calls.add("playSmpsOverride:" + data + ":" + forceOverride); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData) { calls.add("playSfxSmps:" + data); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) { calls.add("playSfxSmpsPitch:" + data + ":" + pitch); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch, SmpsSequencerConfig config) { calls.add("playSfxSmpsConfig:" + data + ":" + pitch); }
        @Override public void playSfx(String sfxName) { calls.add("playSfx:" + sfxName); }
        @Override public void playSfx(String sfxName, float pitch) { calls.add("playSfxPitch:" + sfxName + ":" + pitch); }
        @Override public void stopPlayback() { calls.add("stopPlayback"); }
        @Override public void stopAllSfx() { calls.add("stopAllSfx"); }
        @Override public void fadeOutMusic(int steps, int delay) { calls.add("fadeOutMusic:" + steps + ":" + delay); }
        @Override public void setSpeedShoes(boolean enabled) { calls.add("setSpeedShoes:" + enabled); }
        @Override public void setSpeedMultiplier(int multiplier) { calls.add("setSpeedMultiplier:" + multiplier); }
        @Override public void changeMusicTempo(int newDividingTiming) { calls.add("changeMusicTempo:" + newDividingTiming); }
        @Override public void restoreMusic() { calls.add("restoreMusic"); }
        @Override public void endMusicOverride(int musicId) { calls.add("endMusicOverride:" + musicId); }
        @Override public void update() { calls.add("update"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void resume() { calls.add("resume"); }
    }

    public static final class StubSmpsData extends AbstractSmpsData {
        private final String name;

        public StubSmpsData(String name) {
            super(new byte[0], 0);
            this.name = name;
        }

        @Override protected void parseHeader() {}
        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
        @Override public String toString() { return name; }
    }

    public static final class StubSmpsLoader implements SmpsLoader {
        public final Map<Integer, AbstractSmpsData> musicResults = new HashMap<>();
        public final Map<Integer, AbstractSmpsData> sfxResults = new HashMap<>();
        public final Map<String, AbstractSmpsData> namedSfxResults = new HashMap<>();

        @Override public AbstractSmpsData loadMusic(int musicId) { return musicResults.get(musicId); }
        @Override public AbstractSmpsData loadSfx(int sfxId) { return sfxResults.get(sfxId); }
        @Override public AbstractSmpsData loadSfx(String sfxName) { return namedSfxResults.get(sfxName); }
        @Override public DacData loadDacData() { return EMPTY_DAC; }
    }

    public static class StubAudioProfile implements GameAudioProfile {
        private final SmpsLoader loader;
        private final int speedOn;
        private final int speedOff;
        private final SpeedMode speedMode;

        public StubAudioProfile(SmpsLoader loader) {
            this(loader, -1, -1, SpeedMode.TEMPO_SWAP);
        }

        public StubAudioProfile(SmpsLoader loader, int speedOn, int speedOff, SpeedMode speedMode) {
            this.loader = loader;
            this.speedOn = speedOn;
            this.speedOff = speedOff;
            this.speedMode = speedMode;
        }

        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() { return null; }
        @Override public int getSpeedShoesOnCommandId() { return speedOn; }
        @Override public int getSpeedShoesOffCommandId() { return speedOff; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public SpeedMode getSpeedMode() { return speedMode; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
    }
}
