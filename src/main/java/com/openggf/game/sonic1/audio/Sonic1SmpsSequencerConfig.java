package com.openggf.game.sonic1.audio;

import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSequencerConfig.FmSfxTakeoverMode;
import com.openggf.audio.smps.SmpsSequencerConfig.FmVoiceWriteProfile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * SMPS sequencer configuration for Sonic 1.
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Speed-up tempo table has only 8 entries (music IDs 0x81-0x88),
 *       covering zone themes + invincibility + extra life. The s1disasm notes
 *       that songs beyond the table will read into MusicIndex as fallback tempos.</li>
 *   <li>FM and PSG channel orders are the same as Sonic 2.</li>
 *   <li>Tempo algorithm uses the same TIMEOUT approach (counter decremented per
 *       frame, track update skipped when it wraps). TEMPO_MOD_BASE is 0x100.</li>
 * </ul>
 *
 * <p>Speed-up tempo values from s1disasm SpeedUpIndex (s1.sounddriver.asm).
 */
public final class Sonic1SmpsSequencerConfig {

    /** Tempo modulo base — same across all games, references shared default. */
    public static final int TEMPO_MOD_BASE = SmpsSequencerConfig.DEFAULT_TEMPO_MOD_BASE;

    /** FM channel order — same across all games, references shared default. */
    public static final int[] FM_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_FM_CHANNEL_ORDER;

    /** PSG channel order — same across all games, references shared default. */
    public static final int[] PSG_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_PSG_CHANNEL_ORDER;

    /**
     * Speed-up tempos map: music ID to sped-up main tempo byte.
     * Only 8 entries in Sonic 1 (0x81-0x88), from s1disasm SpeedUpIndex.
     */
    public static final Map<Integer, Integer> SPEED_UP_TEMPOS;

    /** Pre-built sequencer config instance. */
    public static final SmpsSequencerConfig CONFIG;

    static {
        Map<Integer, Integer> tempos = new HashMap<>();
        tempos.put(Sonic1Music.GHZ.id, 0x07);
        tempos.put(Sonic1Music.LZ.id, 0x72);
        tempos.put(Sonic1Music.MZ.id, 0x73);
        tempos.put(Sonic1Music.SLZ.id, 0x26);
        tempos.put(Sonic1Music.SYZ.id, 0x15);
        tempos.put(Sonic1Music.SBZ.id, 0x08);
        tempos.put(Sonic1Music.INVINCIBILITY.id, 0xFF);
        tempos.put(Sonic1Music.EXTRA_LIFE.id, 0x05);
        SPEED_UP_TEMPOS = Collections.unmodifiableMap(tempos);

        // Sonic 1 coord flag differences: ED is 1 byte (0 params), EE is 1 byte (0 params)
        // S2 defaults: ED = 1 param, EE = 0 params. Only ED needs overriding.
        Map<Integer, Integer> coordOverrides = new HashMap<>();
        coordOverrides.put(0xED, 0);  // S1: ClearPush (no param) vs S2: IGNORE (1 param)

        CONFIG = new SmpsSequencerConfig.Builder()
                .speedUpTempos(SPEED_UP_TEMPOS)
                .tempoModBase(TEMPO_MOD_BASE)
                .fmChannelOrder(FM_CHANNEL_ORDER)
                .psgChannelOrder(PSG_CHANNEL_ORDER)
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.NONE)
                .coordFlagParamOverrides(coordOverrides)
                .applyModOnNote(false)   // S1: don't apply modulation during note start (ModAlgo = 68k)
                .halveModSteps(true)     // S1 cfModulation and FinishTrackUpdate both use lsr.b #1
                .extraTrkEndFlags(Set.of(0xEE))
                .relativePointers(true)  // S1: PC-relative pointers for F6/F7/F8
                .tempoOnFirstTick(true)  // S1: process tempo on first frame (DOTEMPO)
                .stepModulationAtRest(true) // DoModulation has no rest check (SD:483-490)
                .direct68kDriver(true)   // S1 writes the YM/PSG cores from the 68k driver
                .fmVoiceWriteProfile(FmVoiceWriteProfile.S1_68K)
                // The shipped Sound_PlaySFX initializes track RAM only. SetVoice and the
                // track's own note-off establish the takeover during UpdateMusic.
                .fmSfxTakeoverMode(FmSfxTakeoverMode.REGISTER_SEQUENCE)
                // Sound_PlaySFX (SD:977-1087) emits no PSG takeover write
                // except the explicit PSG3 DF/FF pair in its own load path.
                .psgSfxTakeoverMode(
                        SmpsSequencerConfig.PsgSfxTakeoverMode.S1_PSG3_SILENCE_PAIR)
                // Sound_PlaySpecial's tail (SD:1183-1191) writes a second PSG
                // pair when the normal SFX PSG3 slot is already playing, built
                // from the stale d4 its own load loop left behind.
                .specialSfxPsg3SilenceMode(
                        SmpsSequencerConfig.SpecialSfxPsg3SilenceMode
                                .S1_STALE_VOICE_CONTROL_PAIR)
                // Sound_PlaySFX's header loader sets PlaybackControl bit 2 on
                // the displaced MUSIC track while loading each SFX track --
                // .sfx_loadloop for FM (SD:1029) and .sfxinitpsg for PSG
                // (SD:1037) -- and it is reached from PlaySoundID at the top of
                // UpdateMusic (SD:202), before the DAC/FM/PSG music walk
                // (SD:208-227). So the music track is already overridden on the
                // very invocation that admits the SFX, and emits no chip write
                // of its own, even though the SFX track has written nothing yet.
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                // cfStopTrack (SD:2489-2563): the completed SFX already sent
                // its note-off. FM restores voice/pan at rest; PSG clears the
                // override at rest and only re-latches noise when applicable.
                .fmSfxReleaseMode(
                        SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE)
                .psgSfxReleaseMode(
                        SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE)
                // UpdateMusic walks fixed SFX RAM slots (FM3..5, then PSG1..3),
                // independently of the order used by the SFX header loader.
                .sfxTrackWalkMode(
                        SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER)
                // FixBugs=0 SendVoiceTL addresses VoicePtr from the driver-RAM
                // base for ordinary SFX, aliasing v_special_voice_ptr.
                .fmVolumeVoiceBankMode(
                        SmpsSequencerConfig.FmVolumeVoiceBankMode.S1_SPECIAL_POINTER_BUG)
                .build();
    }

    private Sonic1SmpsSequencerConfig() {
    }
}
