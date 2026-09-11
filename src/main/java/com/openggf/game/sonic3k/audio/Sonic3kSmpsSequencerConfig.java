package com.openggf.game.sonic3k.audio;

import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;

import java.util.Collections;

/**
 * SMPS sequencer configuration for Sonic 3 &amp; Knuckles.
 *
 * <p>From DefDrv.txt (S&amp;K final):
 * <ul>
 *   <li>PtrFmt = Z80 (relativePointers=false)</li>
 *   <li>TempoMode = OVERFLOW</li>
 *   <li>Tempo1Tick = DOTEMPO (tempoOnFirstTick=true)</li>
 *   <li>ModAlgo = Z80 (applyModOnNote=true, halveModSteps=true)</li>
 *   <li>VolMode = BIT7</li>
 *   <li>NoteOnPrevent = HOLD</li>
 *   <li>DelayFreq = KEEP</li>
 *   <li>PSG envelope 80 = RESET</li>
 *   <li>FadeOutSteps = 0x28, FadeOutDelay = 6</li>
 *   <li>FadeInSteps = 0x40, FadeInDelay = 2</li>
 *   <li>FMChnOrder = 16 0 1 2 4 5 6 (same as S2)</li>
 * </ul>
 *
 * <p>S3K has no speed-up tempo table; speed shoes use frame multiplier instead
 * (Z80 RAM 0x1C08). The speed-up tempos map is empty.
 */
public final class Sonic3kSmpsSequencerConfig {

    /** Tempo modulo base — same across all games, references shared default. */
    public static final int TEMPO_MOD_BASE = SmpsSequencerConfig.DEFAULT_TEMPO_MOD_BASE;

    /** FM channel order — same across all games, references shared default. */
    public static final int[] FM_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_FM_CHANNEL_ORDER;

    /** PSG channel order — same across all games, references shared default. */
    public static final int[] PSG_CHANNEL_ORDER = SmpsSequencerConfig.DEFAULT_PSG_CHANNEL_ORDER;

    /** Pre-built sequencer config instance for S3K. */
    public static final SmpsSequencerConfig CONFIG;

    static {
        CONFIG = create(new Sonic3kCoordFlagHandler());
    }

    public static SmpsSequencerConfig create(CoordFlagHandler coordFlagHandler) {
        return new SmpsSequencerConfig.Builder()
                .speedUpTempos(Collections.emptyMap())
                .tempoModBase(TEMPO_MOD_BASE)
                .fmChannelOrder(FM_CHANNEL_ORDER)
                .psgChannelOrder(PSG_CHANNEL_ORDER)
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .palUpdateMode(SmpsSequencerConfig.PalUpdateMode.EXTRA_FULL)
                .applyModOnNote(true)       // ModAlgo = Z80
                .halveModSteps(true)        // Z80 driver halves mod steps (srl a)
                .relativePointers(false)    // PtrFmt = Z80 (absolute addresses)
                .tempoOnFirstTick(true)     // Tempo1Tick = DOTEMPO
                // Retail fix_sndbugs=0: ordinary zPlayMusic_DoFade calls
                // zStopAllSound, which clears zTempoSpeedup (driver:1786,
                // 2459-2473). The one-up save/load bypasses this boundary.
                .resetTempoOnMusicLoad(true)
                // Preserve the existing S3K Z80 rest path; the S2 oracle's
                // post-rest envelope step is selected independently.
                .advancePsgEnvelopeOnRest(false)
                .writeFmPanOnNote(false)
                .dacNoteKeysOffFm6AndRestoresFm3(true)
                .enableDacOnSequencerStart(false)
                .psgFrequencyHighByteNibbleSwap(true)
                .fmVoiceWriteProfile(SmpsSequencerConfig.FmVoiceWriteProfile.S3K_Z80)
                .volMode(SmpsSequencerConfig.VolMode.BIT7)
                .psgEnvCmd80(SmpsSequencerConfig.PsgEnvCmd80.RESET)
                .noteOnPrevent(SmpsSequencerConfig.NoteOnPrevent.HOLD)
                .delayFreq(SmpsSequencerConfig.DelayFreq.KEEP)
                .coordFlagHandler(coordFlagHandler)
                .modAlgo(SmpsSequencerConfig.ModAlgo.MOD_Z80)
                // zDoModulation is an ordinary subroutine here, so every one of
                // its returns falls through to the frequency send
                // (Sound/Z80 Sound Driver.asm:791-799, :4077-4090). S1 and S2
                // discard the return address instead and skip it.
                .noteGoingFreqSend(
                        SmpsSequencerConfig.NoteGoingFreqSend.EVERY_PASS)
                // zUpdatePSGTrack latches the frequency before it reads the
                // volume envelope (Sound/Z80 Sound Driver.asm:4077-4110); S1
                // and S2 run their volume effects first instead.
                .psgNoteGoingOrder(
                        SmpsSequencerConfig.PsgNoteGoingOrder.FREQUENCY_THEN_VOLUME)
                // zDoVolEnv rests the track on 81h and 83h without silencing
                // it (Sound/Z80 Sound Driver.asm:4169-4175, :4187-4208).
                .psgEnvRestCmd(
                        SmpsSequencerConfig.PsgEnvRestCmd.Z80_81_AND_83)
                // zDoModulation tests only ModulationCtrl, never the rest bit
                // (Sound/Z80 Sound Driver.asm:1277-1283), so a resting track
                // keeps stepping. Only S2 returns early at rest.
                .stepModulationAtRest(true)
                // zFinishTrackUpdate zeroes ModEnvIndex and ModEnvSens, which
                // alias ModulationSpeed and ModulationValLow in this layout
                // (Sound/Z80 Sound Driver.asm:1055-1069, :76-92).
                .noteResetAliasesModulationState(true)
                // zUpdateFMorPSGTrack .note_going returns on the rest bit
                // before it does anything (Sound/Z80 Sound Driver.asm:781-783).
                .fmNoteGoingReturnsAtRest(true)
                // zFadeOutMusic falls through zHaltDACPSG, which halts the
                // PSG tracks as well as FM6/DAC (Sound/Z80 Sound
                // Driver.asm:2307-2325).
                .fadeOutHalt(SmpsSequencerConfig.FadeOutHalt.DAC_AND_PSG)
                // zDoMusicFadeOut decrements the delay before testing it
                // (Sound/Z80 Sound Driver.asm:2337-2343).
                .fadeDelayCadence(
                        SmpsSequencerConfig.FadeDelayCadence.DECREMENT_THEN_TEST)
                // TempoWait sits in zUpdateEverything, ahead of zUpdateMusic's
                // zFillSoundQueue (Sound/Z80 Sound Driver.asm:653-701,
                // :2607-2621), so a load service does not accumulate for the
                // song it loads.
                .tempoWaitPrecedesRequest(true)
                // zSilencePSGChannel writes the track's own tone channel
                // first and adds the noise byte only for a noise track
                // (Sound/Z80 Sound Driver.asm:4226-4245).
                .psgSilenceShape(
                        SmpsSequencerConfig.PsgSilenceShape.TONE_THEN_NOISE)
                // zUpdatePSGTrack's .note_going path sends the frequency pair
                // and then the volume tail on every pass of a sounding note,
                // with no attack test on the tail
                // (Sound/Z80 Sound Driver.asm:4079-4135).
                .psgVolumeTail(
                        SmpsSequencerConfig.PsgVolumeTail.EVERY_NOTE_GOING_PASS)
                // zUpdateSFXTracks runs before zUpdateMusic fills the queue
                // (Sound/Z80 Sound Driver.asm:650-701), so an SFX admitted in
                // a service first updates in the next one.
                .sfxWalkPrecedesRequest(true)
                // zUpdateSFXTracks walks the fixed zTracksSFXStart RAM block:
                // FM3..FM6, then PSG1..PSG3, regardless of which sound header
                // supplied each occupied slot (Sound/Z80 Sound Driver.asm:727-759).
                .sfxTrackWalkMode(
                        SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER)
                // zSFXTrackInitLoop keys each SFX channel off and clears its
                // SSG-EG operators while loading (Sound/Z80 Sound
                // Driver.asm:2092-2103, :2528-2536).
                .sfxAdmissionKeyOffAndClearsSsgEg(true)
                // fix_sndbugs=0: zGetSFXChannelPointers.is_psg unconditionally
                // writes FF after its stale-IX silence call (:2131-2136).
                // The fixed branch relies on corrected channel silence instead.
                .psgSfxAdmissionSilencesNoise(true)
                // Retail fix_sndbugs=0 owns silence at admission above;
                // cfSetPSGNoise then writes DF and its operand consecutively
                // (:3562-3572). Acquiring noise ownership must not inject FF.
                // The fixed branch changes the command's guards and zero
                // handling, not the need for a synthetic first-write silence.
                .psgSfxTakeoverMode(
                        SmpsSequencerConfig.PsgSfxTakeoverMode.REGISTER_SEQUENCE)
                // cfStopTrack keys the channel off exactly once as it clears
                // the playing bit (Sound/Z80 Sound Driver.asm:3040-3046).
                .trackEndFlagOwnsTheStop(true)
                // zSFXTrackInitLoop's FM chip writes are that key-off and
                // SSG-EG clear (Sound/Z80 Sound Driver.asm:2092-2103); the
                // SFX's own bytecode then loads its voice. The engine's
                // legacy takeover additionally forced RR = 0FFh and TL = 07Fh
                // on the channel, which the ROM never writes.
                .fmSfxTakeoverMode(
                        SmpsSequencerConfig.FmSfxTakeoverMode.REGISTER_SEQUENCE)
                // Retail fix_sndbugs=0 cfStopTrack keys off the SFX, clears
                // music override bit 2 and uploads the voice without synthetic
                // RR/TL silence or changing rest bit 4 (driver:3443-3518).
                // The fixed branch changes key-off/FM3 bookkeeping, not rest.
                .fmSfxReleaseMode(
                        SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE_PRESERVE_REST)
                .psgSfxReleaseMode(SmpsSequencerConfig.PsgSfxReleaseMode
                        .ROM_NOISE_RESTORE_PRESERVE_REST)
                // zSFXTrackInitLoop sets bit 2 on the overridden music track
                // while the SFX is still being loaded (Sound/Z80 Sound
                // Driver.asm:1997-2003), so ownership exists from the
                // admitting service even though that service gives the SFX
                // track itself no update.
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                .noteFillTail(SmpsSequencerConfig.NoteFillTail.S3K_SPLIT)
                .fadeOutDelay(6)            // FadeOutDelay = 6
                .fadeOutSteps(0x28)         // FadeOutSteps = 28h
                .fadeInSteps(0x40)          // FadeInSteps = 40h
                .fadeInDelay(2)             // FadeInDelay = 2
                // zFadeInToPrevious silences by the overriding bit, not the
                // resting one: it ORs 84h over every track and clears bit 2
                // again on the FM ones (Sound/Z80 Sound Driver.asm:2761-2770).
                .fadeInRestore(SmpsSequencerConfig.FadeInRestore.OVERRIDE_PSG)
                // zFadeDelay and zFadeDelayTimeout are driver variables, not
                // song state, and zFadeOutMusic writes them whether or not a
                // song is loaded (Sound/Z80 Sound Driver.asm:2306-2312).
                .driverOwnedFadeDelay(true)
                .build();
    }

    private Sonic3kSmpsSequencerConfig() {
    }
}
