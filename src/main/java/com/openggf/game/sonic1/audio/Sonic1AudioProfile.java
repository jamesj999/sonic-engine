package com.openggf.game.sonic1.audio;

import com.openggf.audio.AbstractAudioProfile;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.audio.SegaPcmSpec;
import com.openggf.game.audio.SegaPcmRomReader;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.session.SmpsStatefulCommandPolicy;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Sonic 1 audio profile. Provides SMPS loader, sequencer config, and
 * game-specific sound ID mappings for the Sonic 1 68000 sound driver.
 */
public class Sonic1AudioProfile extends AbstractAudioProfile {

    /**
     * GameSound to Sonic 1 SFX ID mappings.
     * Only sounds that have a Sonic 1 equivalent are mapped.
     * Sonic 1 has no spindash, CNZ-specific sounds, or casino bonus sounds.
     */
    private static final Map<GameSound, Integer> SOUND_MAP;
    private static final Map<GameMusic, Integer> MUSIC_MAP;

    static {
        Map<GameSound, Integer> map = new EnumMap<>(GameSound.class);
        map.put(GameSound.JUMP, Sonic1Sfx.JUMP.id);
        map.put(GameSound.RING_LEFT, Sonic1Sfx.RING_LEFT.id);
        map.put(GameSound.RING_RIGHT, Sonic1Sfx.RING.id);
        map.put(GameSound.RING_SPILL, Sonic1Sfx.RING_LOSS.id);
        map.put(GameSound.ROLLING, Sonic1Sfx.ROLL.id);
        map.put(GameSound.SKID, Sonic1Sfx.SKID.id);
        map.put(GameSound.HURT, Sonic1Sfx.DEATH.id);
        map.put(GameSound.HURT_SPIKE, Sonic1Sfx.SPIKE_HIT.id);
        map.put(GameSound.DROWN, Sonic1Sfx.DROWN.id);
        map.put(GameSound.BADNIK_HIT, Sonic1Sfx.HIT_BOSS.id);
        map.put(GameSound.CHECKPOINT, Sonic1Sfx.LAMPPOST.id);
        map.put(GameSound.SPRING, Sonic1Sfx.SPRING.id);
        map.put(GameSound.BUMPER, Sonic1Sfx.BUMPER.id);
        map.put(GameSound.SPLASH, Sonic1Sfx.SPLASH.id);
        map.put(GameSound.AIR_DING, Sonic1Sfx.WARNING.id);
        // GameSound.SPINDASH_CHARGE - no S1 equivalent (spindash not in original S1)
        // GameSound.SPINDASH_RELEASE - no S1 equivalent
        // GameSound.BONUS_BUMPER - CNZ-specific, not in S1
        // GameSound.LARGE_BUMPER - CNZ-specific, not in S1
        // GameSound.FLIPPER - CNZ-specific, not in S1
        // GameSound.CNZ_LAUNCH - CNZ-specific, not in S1
        // GameSound.CNZ_ELEVATOR - CNZ-specific, not in S1
        // GameSound.SLOW_SMASH - special stage bomb, not in S1
        map.put(GameSound.WALL_SMASH, Sonic1Sfx.WALL_SMASH.id);
        // GameSound.ERROR - no direct S1 equivalent
        // GameSound.CASINO_BONUS - CNZ-specific, not in S1
        SOUND_MAP = Collections.unmodifiableMap(map);

        Map<GameMusic, Integer> music = new EnumMap<>(GameMusic.class);
        music.put(GameMusic.ACT_CLEAR, Sonic1Music.GOT_THROUGH.id);
        music.put(GameMusic.DROWNING, Sonic1Music.DROWNING.id);
        music.put(GameMusic.EMERALD, Sonic1Music.CHAOS_EMERALD.id);
        music.put(GameMusic.EXTRA_LIFE, Sonic1Music.EXTRA_LIFE.id);
        music.put(GameMusic.INVINCIBILITY, Sonic1Music.INVINCIBILITY.id);
        music.put(GameMusic.SPECIAL_STAGE, Sonic1Music.SPECIAL_STAGE.id);
        MUSIC_MAP = Collections.unmodifiableMap(music);
    }

    public Sonic1AudioProfile() {
        super(SOUND_MAP, MUSIC_MAP);
    }

    @Override
    public SmpsLoader createSmpsLoader(Rom rom) {
        return new Sonic1SmpsLoader(rom);
    }

    @Override
    public SmpsSequencerConfig getSequencerConfig() {
        return Sonic1SmpsSequencerConfig.CONFIG;
    }

    @Override
    public SmpsPhysicalPolicy smpsPhysicalPolicy() {
        return Sonic1SmpsCompatibilityPolicy.INSTANCE;
    }

    @Override
    public SmpsStatefulCommandPolicy smpsStatefulCommandPolicy() {
        return Sonic1StatefulCommandPolicy.INSTANCE;
    }

    @Override
    public int getSpeedShoesOnCommandId() {
        return Sonic1SmpsConstants.CMD_SPEED_UP;
    }

    @Override
    public int getSpeedShoesOffCommandId() {
        return Sonic1SmpsConstants.CMD_SLOW_DOWN;
    }

    @Override
    public int getInvincibilityMusicId() {
        return Sonic1Music.INVINCIBILITY.id;
    }

    @Override
    public int getExtraLifeMusicId() {
        return Sonic1Music.EXTRA_LIFE.id;
    }

    @Override
    public int getDrowningMusicId() {
        return Sonic1Music.DROWNING.id;
    }

    @Override
    protected int getFadeOutCommandId() {
        return Sonic1SmpsConstants.CMD_FADE_OUT;
    }

    @Override
    protected int getStopAllCommandId() {
        return Sonic1SmpsConstants.CMD_STOP_ALL;
    }

    @Override
    protected int getSegaCommandId() {
        return Sonic1SmpsConstants.CMD_SEGA;
    }

    @Override
    public SegaPcmSpec getSegaPcmSpec() {
        return new SegaPcmSpec(
                Sonic1SmpsConstants.SEGA_SOUND_ADDR,
                Sonic1SmpsConstants.SEGA_SOUND_SIZE,
                Sonic1SmpsConstants.SEGA_SOUND_SAMPLE_RATE);
    }

    /**
     * Reads the chant through the runtime-layer ROM reader so the audio layer
     * never depends on {@code com.openggf.data} for this sample.
     */
    @Override
    public byte[] loadSegaPcm(Object rom) throws java.io.IOException {
        return SegaPcmRomReader.read(rom, getSegaPcmSpec());
    }

    /**
     * Look up sound priority for a given sound ID using the Sonic 1 priority table.
     *
     * @param soundId any sound ID in the 0x81-0xE4 range
     * @return priority value from the ROM priority table
     */
    @Override
    public int getSfxPriority(int soundId) {
        return Sonic1SmpsConstants.getSfxPriority(soundId);
    }

    @Override
    public boolean isSpecialSfx(int soundId) {
        return soundId >= Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE
                && soundId < Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE + Sonic1SmpsConstants.SPECIAL_SFX_COUNT;
    }

    /**
     * {@code ResumeMusic} (s1disasm "_incObj/sub ResumeMusic.asm":7-33) picks the
     * zone's track, LZ or SBZ for LZ act 4, then overrides it twice under
     * {@code Revision<>0}, which holds for the REV01 ROM this engine models
     * (sonic.asm:14 sets {@code Revision = 1}): {@code v_invinc} selects
     * {@code bgm_Invincible} and {@code f_lockscreen} selects {@code bgm_Boss}.
     * The boss test is second, so it wins. Sonic 1 has no Super form, so that
     * argument has no counterpart here and is ignored.
     */
    @Override
    public int resolveAirResetMusic(int levelMusicId, boolean invincible,
            boolean superForm, boolean bossActive) {
        if (bossActive) {
            return Sonic1Music.BOSS.id;
        }
        if (invincible) {
            return Sonic1Music.INVINCIBILITY.id;
        }
        return levelMusicId;
    }
}
