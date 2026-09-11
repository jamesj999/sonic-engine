package com.openggf.game.sonic2.audio;

import com.openggf.audio.smps.SmpsSequencerConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Sonic2SmpsSequencerConfig {
    /** Tempo modulo base — same across all games, references shared default. */
    public static final int TEMPO_MOD_BASE = SmpsSequencerConfig.DEFAULT_TEMPO_MOD_BASE;

    /** FM channel order — same across all games, references shared default. */
    public static final int[] FM_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_FM_CHANNEL_ORDER;

    /** PSG channel order — same across all games, references shared default. */
    public static final int[] PSG_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_PSG_CHANNEL_ORDER;

    public static final Map<Integer, Integer> SPEED_UP_TEMPOS;
    public static final SmpsSequencerConfig CONFIG;

    static {
        Map<Integer, Integer> tempos = new HashMap<>();
        tempos.put(Sonic2Music.RESULTS_2P.id, 0x68);
        tempos.put(Sonic2Music.EMERALD_HILL.id, 0xBE);
        tempos.put(Sonic2Music.MYSTIC_CAVE_2P.id, 0xFF);
        tempos.put(Sonic2Music.OIL_OCEAN.id, 0xF0);
        tempos.put(Sonic2Music.METROPOLIS.id, 0xFF);
        tempos.put(Sonic2Music.HILL_TOP.id, 0xDE);
        tempos.put(Sonic2Music.AQUATIC_RUIN.id, 0xFF);
        tempos.put(Sonic2Music.CASINO_NIGHT_2P.id, 0xDD);
        tempos.put(Sonic2Music.CASINO_NIGHT.id, 0x68);
        tempos.put(Sonic2Music.DEATH_EGG.id, 0x80);
        tempos.put(Sonic2Music.MYSTIC_CAVE.id, 0xD6);
        tempos.put(Sonic2Music.EMERALD_HILL_2P.id, 0x7B);
        tempos.put(Sonic2Music.SKY_CHASE.id, 0x7B);
        tempos.put(Sonic2Music.CHEMICAL_PLANT.id, 0xFF);
        tempos.put(Sonic2Music.WING_FORTRESS.id, 0xA8);
        tempos.put(Sonic2Music.HIDDEN_PALACE.id, 0xFF);
        tempos.put(Sonic2Music.OPTIONS.id, 0x87);
        tempos.put(Sonic2Music.SPECIAL_STAGE.id, 0xFF);
        tempos.put(Sonic2Music.BOSS.id, 0xFF);
        tempos.put(Sonic2Music.FINAL_BOSS.id, 0xC9);
        tempos.put(Sonic2Music.ENDING.id, 0x97);
        tempos.put(Sonic2Music.SUPER_SONIC.id, 0xFF);
        tempos.put(Sonic2Music.INVINCIBILITY.id, 0xFF);
        tempos.put(Sonic2Music.EXTRA_LIFE.id, 0xCD);
        tempos.put(Sonic2Music.TITLE.id, 0xCD);
        tempos.put(Sonic2Music.ACT_CLEAR.id, 0xAA);
        tempos.put(Sonic2Music.GAME_OVER.id, 0xF2);
        tempos.put(Sonic2Music.CONTINUE.id, 0xDB);
        tempos.put(Sonic2Music.GOT_EMERALD.id, 0xD5);
        tempos.put(Sonic2Music.CREDITS.id, 0xF0);
        SPEED_UP_TEMPOS = Collections.unmodifiableMap(tempos);
        CONFIG = new SmpsSequencerConfig.Builder()
                .speedUpTempos(SPEED_UP_TEMPOS)
                .tempoModBase(TEMPO_MOD_BASE)
                .fmChannelOrder(FM_CHANNEL_ORDER)
                .psgChannelOrder(PSG_CHANNEL_ORDER)
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                // zDoModulation is the only one of the three that tests the
                // rest bit on entry: bit 1,(ix+zTrack.PlaybackControl) / ret nz
                // (s2.sounddriver.asm:988-990). S1 and S3K keep stepping.
                .stepModulationAtRest(false)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.EXTRA_MUSIC)
                // TempoWait runs at the top of EVERY zUpdateMusic including the
                // first after a song load (sd:545-551): the load seeds
                // TempoTimeout = CurrentTempo (sd:1820-1822), so update 1 adds
                // the tempo to the seed (EHZ: 9Eh + 9Eh = 13Ch → carry → 3Ch).
                // DefDrv.txt's "Tempo1Tick = PlayMusic" claim does not match the
                // shipped driver and previously skipped the first TempoWait.
                .tempoOnFirstTick(true)
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                // Taking a PSG channel from the music costs no register write.
                // zPlaySound's .sfxinitpsg silences only PSG3, through the
                // explicit or 1Fh / xor 20h pair below (s2.sounddriver.asm:
                // 2221-2228); every other channel is claimed by nothing more
                // than `set 2,(hl)` on the corresponding music track (:2243-2245),
                // and the SFX's own bytecode owns the visible writes from there.
                .psgSfxTakeoverMode(
                        SmpsSequencerConfig.PsgSfxTakeoverMode.REGISTER_SEQUENCE)
                // zPlaySound .sfxinitpsg writes DF then FF while loading any
                // C0/PSG3 SFX header (sd:2208-2220), independent of ownership.
                .psg3SfxAdmissionWriteMode(
                        SmpsSequencerConfig.Psg3SfxAdmissionWriteMode
                                .SILENCE_TONE_AND_NOISE)
                .fmVoiceWriteProfile(SmpsSequencerConfig.FmVoiceWriteProfile.S2_Z80)
                // cfStopTrack's FM SFX tail (s2.sounddriver.asm:3548-3553) clears
                // the override bit and SETS the rest bit, then restores only the
                // music voice through zSetVoiceMusic. It sends no key-off, no
                // pan/AMS/FMS rewrite and no frequency resend; the SFX track's own
                // zFMNoteOff already silenced the channel. Leaving the music track
                // at rest is what keeps zDoModulation returning early
                // (s2.sounddriver.asm:989-991), so a released channel stays silent
                // until its next note instead of resuming modulation.
                .fmSfxReleaseMode(
                        SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE)
                // zStopPSGSFXTrack (s2.sounddriver.asm:3581-3589) is the same
                // shape: clear the override, set rest, and re-latch PSG noise only
                // for the PSG3 noise track.
                .psgSfxReleaseMode(
                        SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE)
                // zVInt updates the SFX tracks by walking the fixed SFX RAM
                // region, not the order the SFX header happened to list them:
                // it steps ix through SFX_FM_TRACK_COUNT tracks and then
                // SFX_PSG_TRACK_COUNT more (s2.sounddriver.asm:465-487), so
                // every FM SFX slot is serviced before any PSG SFX slot.
                .sfxTrackWalkMode(
                        SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER)
                .build();
    }

    private Sonic2SmpsSequencerConfig() {
    }
}
