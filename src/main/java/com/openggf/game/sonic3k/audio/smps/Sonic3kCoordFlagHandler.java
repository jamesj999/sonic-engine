package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsProgramView;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Sonic 3 &amp; Knuckles coordination flag handler.
 *
 * <p>S3K uses a modified SMPS Z80 Type 2 driver with significantly different
 * coordination flag assignments compared to S2. This handler intercepts all
 * flags E0-FF and dispatches them according to the S3K DefCFlag.txt definitions.
 *
 * <p>Key differences from S2:
 * <ul>
 *   <li>E3 = TRK_END (mute), not Return</li>
 *   <li>E9 = SPINDASH_REV with 0 params (S2 has 1 param)</li>
 *   <li>F9 = RETURN (S2 has F9 = SND_OFF)</li>
 *   <li>FF = META_CF prefix for sub-commands 00-07</li>
 *   <li>Many new flags: E2, E4, E5, EA, EB, EE, F1, F4, FC, FD, FE</li>
 * </ul>
 *
 * <p>The S&K-loader-supported S3K music and SFX tables do not reach meta subcommands
 * {@code FF 01} (SND_CMD), {@code FF 02} (MUS_PAUSE), or {@code FF 03}
 * (COPY_MEM). Those cases still consume their documented operands so an
 * imported/custom stream remains aligned, but that is deliberately not
 * presented as native semantic support. See
 * {@code TestSonic3kSmpsMetaCommandReachability} and the audio research note
 * for the ROM inventory and source contract.</p>
 */
public class Sonic3kCoordFlagHandler implements CoordFlagHandler {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kCoordFlagHandler.class.getName());

    private final SmpsCoordFlagRuntimeState runtimeState;

    public Sonic3kCoordFlagHandler() {
        this(new SmpsCoordFlagRuntimeState());
    }

    public Sonic3kCoordFlagHandler(SmpsCoordFlagRuntimeState runtimeState) {
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }

    @Override
    public void onSfxStart(int sfxId) {
        if (sfxId != Sonic3kSfx.SPINDASH.id && sfxId < Sonic3kSfx.SLIDE_SKID_LOUD.id) {
            runtimeState.setSpindashRevCounter(0);
        }
    }

    @Override
    public boolean handleFlag(CoordFlagContext ctx, SmpsSequencer.Track t, int cmd) {
        SmpsProgramView program = ctx.programView();
        switch (cmd) {
            // ---- Basic flags (S2 equivalents or simple logic) ----

            case 0xE0: // PANAFMS - set pan/AMS/FMS
                if (t.pos < program.dataLength()) {
                    int val = program.dataByteAt(t.pos++) & 0xFF;
                    t.pan = ((val & 0x80) != 0 ? 0x80 : 0) | ((val & 0x40) != 0 ? 0x40 : 0);
                    t.ams = (val >> 4) & 0x3;
                    t.fms = val & 0x7;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        int ch = hwCh % 3;
                        int reg = 0xB4 + ch;
                        int regVal = (t.pan & 0xC0) | ((t.ams & 0x3) << 4) | (t.fms & 0x7);
                        ctx.writeFm(port, reg, regVal);
                    }
                }
                return true;

            case 0xE1: // DETUNE - set track detune
                if (t.pos < program.dataLength()) {
                    t.detune = program.dataByteAt(t.pos++); // signed byte
                }
                return true;

            case 0xE2: // FADE_TO_PREV - store fade-to-previous flag
                // Z80 driver: stores param into zFadeToPrevFlag, main loop checks it.
                // 0xFF = restore backed-up music with fade-in (zFadeInToPrevious)
                // Other values = SFX blocking control (no-op here, handled by isSfxBlockingMusic)
                if (t.pos < program.dataLength()) {
                    int param = program.dataByteAt(t.pos++) & 0xFF;
                    if (param == 0xFF) {
                        // Restore previous music with fade-in, through the
                        // sequencer's own restore sink. The global AudioManager
                        // is the wrong door from inside a service: this flag
                        // runs within the presentation command batch, which
                        // rejects a command submitted into it, and the failure
                        // is logged rather than raised, so the restore is
                        // simply lost. The injected sink defers it to the
                        // batch boundary instead, which is also how the S1 and
                        // S2 E4 handler reaches it.
                        ctx.restorePreviousMusic();
                    }
                    // Other values: no-op
                }
                return true;

            case 0xE3: // TRK_END (TEND_MUTE) - stop/mute track
                t.active = false;
                ctx.stopNote(t);
                return true;

            case 0xE4: // VOL_ABS_S3K - set absolute volume
                if (t.pos < program.dataLength()) {
                    int raw = program.dataByteAt(t.pos++) & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        // 00(min)..7F(max) -> 7F(min)..00(max)
                        t.volumeOffset = (~raw) & 0x7F;
                        ctx.refreshVolume(t);
                    } else if (t.type == SmpsSequencer.TrackType.PSG) {
                        // 00(min)..7F(max) -> 0F(min)..00(max)
                        t.volumeOffset = ((raw >> 3) & 0x0F) ^ 0x0F;
                        // cfSetVolume's PSG branch ends at zStoreTrackVolume,
                        // which stores the byte and returns without touching
                        // the chip; only the FM branch falls through to
                        // zSendTL to "begin using new volume immediately"
                        // (Sound/Z80 Sound Driver.asm:3128-3146, :3178-3181).
                        // The track's own zUpdatePSGTrack tail sends it.
                    }
                }
                return true;

            case 0xE5: // VOL_CC_FMP2 - S3K broken: ignore first param, apply second as FM volume delta
                if (t.pos + 1 < program.dataLength()) {
                    t.pos++; // First parameter is ignored in S3K.
                    int volChange = program.dataByteAt(t.pos++);
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        applySignedFmVolumeDelta(t, volChange);
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xE6: // VOL_CC_FM - add to volume offset
                if (t.pos < program.dataLength()) {
                    int delta = program.dataByteAt(t.pos++);
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        applySignedFmVolumeDelta(t, delta);
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xE7: // HOLD - tie next note
                t.tieNext = true;
                return true;

            case 0xE8: // NOTE_STOP (NSTOP_MULT) - set fill
                if (t.pos < program.dataLength()) {
                    t.fill = program.dataByteAt(t.pos++) & 0xFF;
                }
                return true;

            case 0xE9: // SPINDASH_REV (SDREV_INC) - no params in S3K!
                int spindashRevCounter = runtimeState.spindashRevCounter();
                int updatedTranspose = (t.keyOffset + spindashRevCounter) & 0xFF;
                t.keyOffset = (byte) updatedTranspose;
                if (updatedTranspose != 0x10) {
                    runtimeState.setSpindashRevCounter(
                            (spindashRevCounter + 1) & 0xFF);
                }
                return true;

            case 0xEA: // PLAY_DAC - play DAC sample
                if (t.pos < program.dataLength()) {
                    int dacId = program.dataByteAt(t.pos++) & 0xFF;
                    ctx.playDac(dacId);
                }
                return true;

            case 0xEB: // LOOP_EXIT - counter, count, pointer (4 bytes total)
                handleLoopExit(ctx, t, program);
                return true;

            case 0xEC: // PSG_VOL (VOL_CC_PSG) - add to PSG volume
                if (t.pos < program.dataLength()) {
                    int delta = program.dataByteAt(t.pos++);
                    if (t.type == SmpsSequencer.TrackType.PSG) {
                        // Z80 Type 2 behavior: unsigned add then clip upper bound to 0x0F.
                        int updated = (t.volumeOffset + delta) & 0xFF;
                        if (updated > 0x0F) {
                            updated = 0x0F;
                        }
                        t.volumeOffset = updated;
                        t.envAtRest = false;
                        // Retail cfChangePSGVolume clears rest, then DEC wraps
                        // VolEnv from 00h to FFh before the signed add and
                        // unsigned CP 0Fh clamp.
                        // (Sound/Z80 Sound Driver.asm:3263-3285).
                        t.resting = false;
                        t.envPos = (t.envPos - 1) & 0xFF;
                        // Like cfSetVolume's PSG branch, this ends at
                        // zStoreTrackVolume, which stores the byte and returns
                        // without touching the chip (:3273-3285). The track's
                        // own zUpdatePSGTrack tail sends it.
                    }
                }
                return true;

            case 0xED: // TRANSPOSE_SET (TRNSP_SET_S3K) - set absolute transposition
                if (t.pos < program.dataLength()) {
                    t.keyOffset = wrapSignedByte(
                            (program.dataByteAt(t.pos++) & 0xFF) - 0x40);
                }
                return true;

            case 0xEE: // FM_COMMAND - direct FM register write
                if (t.pos + 1 < program.dataLength()) {
                    int fmReg = program.dataByteAt(t.pos++) & 0xFF;
                    int fmVal = program.dataByteAt(t.pos++) & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        ctx.writeFm(port, fmReg, fmVal);
                    }
                }
                return true;

            case 0xEF: // INSTRUMENT (INS_C_FMP) - load voice/instrument
                if (t.pos < program.dataLength()) {
                    // cfSetVoice releases the channel's envelope before it even
                    // reads the voice index: for any non-PSG track it calls
                    // zSetMaxRelRate, which writes 0FFh to 80h + operator for
                    // all four operators, setting D1L to minimum and RR to
                    // maximum (skdisasm Sound/Z80 Sound Driver.asm:3444-3447,
                    // 2675-2698). zWriteFMIorII drops the write when
                    // PlaybackControl bit 2 says SFX is overriding the track
                    // (:2701-2709). Neither S1 cfSetVoice
                    // (s1.sounddriver.asm:2313-2360) nor S2 cfSetVoice
                    // (s2.sounddriver.asm:3271-3293) does this, which is why it
                    // lives in the S3K handler.
                    if (t.type != SmpsSequencer.TrackType.PSG && !t.overridden) {
                        int port = (t.channelId < 3) ? 0 : 1;
                        int channelOffset = t.channelId % 3;
                        for (int operator = 0; operator < 4; operator++) {
                            ctx.writeFm(port,
                                    0x80 + (operator * 4) + channelOffset, 0xFF);
                        }
                    }
                    int voiceId = program.dataByteAt(t.pos++) & 0xFF;
                    ctx.loadVoice(t, voiceId);
                }
                return true;

            case 0xF0: // MOD_SETUP - modulation setup (4 params: delay, rate, delta, steps)
                if (t.pos + 3 < program.dataLength()) {
                    t.modPendingDelayInit = program.dataByteAt(t.pos++) & 0xFF;
                    int rate = program.dataByteAt(t.pos++) & 0xFF;
                    t.modPendingRate = (rate == 0) ? 256 : rate;
                    t.modPendingDelta = program.dataByteAt(t.pos++); // signed
                    int steps = program.dataByteAt(t.pos++) & 0xFF;
                    t.modPendingStepsFull = steps;
                    // S3K uses Z80 driver: halve mod steps (srl a)
                    t.modPendingSteps = steps / 2;
                    // The Z80 driver's cfModulation only updates the modulation data
                    // pointer and arms bit 7 of ModulationCtrl. Live counters and the
                    // accumulator are copied/reset later by zPrepareModulation, which
                    // explicitly skips no-attack/tied notes. S3K uses this in the title
                    // sweep and spindash charge to change modulation data without
                    // dropping an already-rising pitch back to the base note.
                    t.customModEnabled = true;
                    t.modEnvId = 0;
                    t.modEnvData = null;
                    t.modEnvPos = 0;
                    t.modEnvMult = 0;
                    t.modEnvCache = 0;
                    t.modEnvHold = false;
                    t.modEnabled = true;
                }
                return true;

            case 0xF1: // MOD_ENV (MENV_FMP) - FM modulation envelope (2 params)
                if (t.pos + 1 < program.dataLength()) {
                    int psgEnvId = program.dataByteAt(t.pos++) & 0xFF;
                    int fmEnvId = program.dataByteAt(t.pos++) & 0xFF;
                    t.modEnvId = (t.type == SmpsSequencer.TrackType.PSG) ? psgEnvId : fmEnvId;
                    if (t.modEnvId == 0) {
                        ctx.clearModulation(t);
                    } else {
                        t.customModEnabled = false;
                        t.modEnvData = copyModEnvelope(program, t.modEnvId);
                        t.modEnvPos = 0;
                        t.modEnvMult = 0;
                        t.modEnvCache = 0;
                        t.modEnvHold = false;
                        t.modEnabled = t.modEnvData != null;
                    }
                }
                return true;

            case 0xF2: // TRK_END (TEND_STD) - standard track end
                t.active = false;
                if (t.type == SmpsSequencer.TrackType.PSG) {
                    // Retail fix_sndbugs=0 zGetSFXChannelPointers calls
                    // zSilencePSGChannel, then unconditionally writes FF to
                    // compensate for that routine's broken noise test. This
                    // happens before zUpdatingSFX is tested and before music
                    // ownership is restored (Sound/Z80 Sound Driver.asm:
                    // 2115-2142, 3443-3469, 4226-4249).
                    ctx.stopPsgNoteWithDriverSilence(t);
                } else {
                    ctx.stopNote(t);
                }
                // cfStopTrack does not stop at the key-off: it clears the
                // overridden music track's bit and sends that track's FM
                // instrument, inline, before the music update of the same
                // service (Sound/Z80 Sound Driver.asm:3059-3086).
                ctx.releaseChannelToMusic(t);
                return true;

            case 0xF3: // PSG_NOISE (PNOIS_SRES) - set + reset
                if (t.pos < program.dataLength()) {
                    int noiseVal = program.dataByteAt(t.pos++) & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.FM && t.channelId == 2) {
                        // FixBugs = 0: cfSetPSGNoise reuses PlaybackControl bit 0.
                        // On FM3 that bit is the distinct special-mode state;
                        // it is not PSG noise and produces no new hardware write here.
                        t.fm3SpecialMode = noiseVal != 0;
                    } else if (t.type == SmpsSequencer.TrackType.PSG) {
                        // FixBugs = 0 cfSetPSGNoise stores the exact command byte
                        // in zTrack.PSGNoise before interpreting zero/nonzero.
                        t.rawPsgNoise = noiseVal;
                        t.rawPsgNoiseKnown = true;
                        ctx.writePsg(0xDF);
                        if (noiseVal == 0) {
                            t.noiseMode = false;
                            t.psgNoiseParam = 0;
                            ctx.writePsg(0xFF);
                        } else {
                            int noiseReg = ((noiseVal & 0xE0) == 0xE0)
                                    ? noiseVal
                                    : (0xE0 | (noiseVal & 0x0F));
                            t.noiseMode = true;
                            t.psgNoiseParam = noiseReg & 0x0F;
                            ctx.writePsg(noiseReg);
                        }
                    }
                }
                return true;

            case 0xF4: // MOD_ENV (MENV_GEN) - generic modulation envelope (1 param)
                if (t.pos < program.dataLength()) {
                    t.modEnvId = program.dataByteAt(t.pos++) & 0xFF;
                    if (t.modEnvId == 0) {
                        ctx.clearModulation(t);
                    } else {
                        t.customModEnabled = false;
                        t.modEnvData = copyModEnvelope(program, t.modEnvId);
                        t.modEnvPos = 0;
                        t.modEnvMult = 0;
                        t.modEnvCache = 0;
                        t.modEnvHold = false;
                        t.modEnabled = t.modEnvData != null;
                    }
                }
                return true;

            case 0xF5: // PSG_INSTRUMENT (INS_C_PSG) - load PSG envelope
                if (t.pos < program.dataLength()) {
                    int insId = program.dataByteAt(t.pos++) & 0xFF;
                    t.instrumentId = insId;
                    ctx.loadPsgEnvelope(t, insId);
                }
                return true;

            case 0xF6: // GOTO - jump to pointer
                handleGoto(ctx, t);
                return true;

            case 0xF7: // LOOP - counter, count, pointer
                handleLoop(ctx, t, program);
                return true;

            case 0xF8: // GOSUB - call subroutine
                handleGosub(ctx, t);
                return true;

            case 0xF9: // RETURN - pop return stack (NOT SND_OFF like S2!)
                if (t.returnSp > 0) {
                    t.pos = t.returnStack[--t.returnSp];
                } else {
                    deactivateOnMalformedData(ctx, t);
                }
                return true;

            case 0xFA: // MODS_OFF - disable modulation
                ctx.clearModulation(t);
                return true;

            case 0xFB: // TRANSPOSE_ADD - add to transposition
                if (t.pos < program.dataLength()) {
                    t.keyOffset = wrapSignedByte(t.keyOffset
                            + program.dataByteAt(t.pos++)); // signed 8-bit add
                }
                return true;

            case 0xFC: // CONT_SFX - continuous SFX loop (like goto for SFX)
                handleContSfx(ctx, t);
                return true;

            case 0xFD: // RAW_FREQ - set raw frequency mode
                if (t.pos < program.dataLength()) {
                    int rawFreqVal = program.dataByteAt(t.pos++) & 0xFF;
                    t.rawFreqMode = (rawFreqVal == 0x01);
                }
                return true;

            case 0xFE: // SPC_FM3 - FM3 special mode (4 params)
                if (t.pos + 3 < program.dataLength()) {
                    // FixBugs = 0: the shipped handler consumes all four operands
                    // and sets FM3 PlaybackControl bit 0, but its special-frequency
                    // path is broken. Retain only the semantic bit: no $27 write,
                    // frequency write, or corruption emulation belongs in this slice.
                    t.pos += 4;
                    if (t.type == SmpsSequencer.TrackType.FM && t.channelId == 2) {
                        t.fm3SpecialMode = true;
                    }
                }
                return true;

            case 0xFF: // META_CF - meta command prefix
                handleMetaCommand(ctx, t, program);
                return true;

            default:
                return false; // Unknown flag - fall through to default S2 handler
        }
    }

    @Override
    public int flagParamLength(int cmd) {
        if (cmd == 0xFF) return 1; // Meta prefix + at least sub-command byte
        return switch (cmd) {
            case 0xE0 -> 1; // PANAFMS
            case 0xE1 -> 1; // DETUNE
            case 0xE2 -> 1; // FADE_IN_SONG
            case 0xE3 -> 0; // TRK_END (mute)
            case 0xE4 -> 1; // VOL_ABS
            case 0xE5 -> 2; // VOL_CC_FMP2
            case 0xE6 -> 1; // VOL_CC_FM
            case 0xE7 -> 0; // HOLD
            case 0xE8 -> 1; // NOTE_STOP
            case 0xE9 -> 0; // SPINDASH_REV (no param in S3K!)
            case 0xEA -> 1; // PLAY_DAC
            case 0xEB -> 3; // LOOP_EXIT (counter, count, ptr)
            case 0xEC -> 1; // PSG_VOL
            case 0xED -> 1; // TRANSPOSE_SET
            case 0xEE -> 2; // FM_COMMAND
            case 0xEF -> 1; // INSTRUMENT (basic)
            case 0xF0 -> 4; // MOD_SETUP
            case 0xF1 -> 2; // MOD_ENV (FMP)
            case 0xF2 -> 0; // TRK_END
            case 0xF3 -> 1; // PSG_NOISE
            case 0xF4 -> 1; // MOD_ENV (generic)
            case 0xF5 -> 1; // PSG_INSTRUMENT
            case 0xF6 -> 2; // GOTO
            case 0xF7 -> 4; // LOOP
            case 0xF8 -> 2; // GOSUB
            case 0xF9 -> 0; // RETURN
            case 0xFA -> 0; // MODS_OFF
            case 0xFB -> 1; // TRANSPOSE_ADD
            case 0xFC -> 2; // CONT_SFX
            case 0xFD -> 1; // RAW_FREQ
            case 0xFE -> 4; // SPC_FM3
            default -> -1;
        };
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void handleGoto(CoordFlagContext ctx, SmpsSequencer.Track t) {
        int newPos = ctx.readJumpPointer(t);
        if (newPos != -1) {
            t.pos = newPos;
        } else {
            deactivateOnMalformedData(ctx, t);
        }
    }

    private void handleLoop(
            CoordFlagContext ctx,
            SmpsSequencer.Track t,
            SmpsProgramView program) {
        if (t.pos + 1 < program.dataLength()) {
            int index = program.dataByteAt(t.pos++) & 0xFF;
            int count = program.dataByteAt(t.pos++) & 0xFF;
            int newPos = ctx.readJumpPointer(t);
            if (newPos == -1) {
                deactivateOnMalformedData(ctx, t);
                return;
            }
            if (count == 0) {
                t.pos = newPos;
                return;
            }
            if (index >= t.loopCounters.length) {
                int[] newCounters = new int[Math.max(t.loopCounters.length * 2, index + 1)];
                System.arraycopy(t.loopCounters, 0, newCounters, 0, t.loopCounters.length);
                t.loopCounters = newCounters;
            }
            if (t.loopCounters[index] == 0) {
                t.loopCounters[index] = count;
            }
            if (t.loopCounters[index] > 0) {
                t.loopCounters[index]--;
                if (t.loopCounters[index] > 0) {
                    t.pos = newPos;
                }
            }
        }
    }

    private void handleGosub(CoordFlagContext ctx, SmpsSequencer.Track t) {
        int newPos = ctx.readJumpPointer(t);
        if (newPos == -1 || t.returnSp >= t.returnStack.length) {
            deactivateOnMalformedData(ctx, t);
            return;
        }
        t.returnStack[t.returnSp++] = t.pos;
        t.pos = newPos;
    }

    private void handleLoopExit(
            CoordFlagContext ctx,
            SmpsSequencer.Track t,
            SmpsProgramView program) {
        // EB: counter index, target count, pointer
        // If loop counter has reached the target count, skip the pointer and continue;
        // otherwise jump to the pointer address.
        if (t.pos + 1 < program.dataLength()) {
            int index = program.dataByteAt(t.pos++) & 0xFF;
            int targetCount = program.dataByteAt(t.pos++) & 0xFF;
            int jumpTarget = ctx.readJumpPointer(t);
            if (jumpTarget == -1) {
                return;
            }
            if (index >= t.loopCounters.length) {
                // Counter doesn't exist yet - not at target, jump
                t.pos = jumpTarget;
                return;
            }
            // Check if the loop counter has reached the exit condition
            if (t.loopCounters[index] == targetCount) {
                // Exit: continue past the pointer (already advanced by readJumpPointer)
            } else {
                // Not yet: jump to the pointer
                t.pos = jumpTarget;
            }
        }
    }

    private void handleContSfx(CoordFlagContext ctx, SmpsSequencer.Track t) {
        // FC: cfLoopContinuousSFX - conditional loop for continuous SFX tracks.
        // ROM (Z80 Sound Driver.asm lines 3712-3736):
        //   If zContinuousSFXFlag != 0x80 → sound wasn't re-triggered, fall through
        //     to the fade-out section that follows the FC pointer in the SFX data.
        //   If flag == 0x80 → decrement zContSFXLoopCnt:
        //     non-zero: jump to target (keep looping)
        //     zero: clear flag, jump to target (one more loop, then stop next time)
        //
        // ROM pointer handling: cfLoopContinuousSFX's `inc de; ret` skips the first
        // pointer byte, and the loc_BF9 return stub's `inc de` skips the second.
        // Both bytes are consumed, and the track continues to the data after the
        // pointer (Sound_BD_Loop00 fade-out section for the Large Ship SFX).
        int jumpTarget = ctx.readJumpPointer(t);

        if (!ctx.isContinuousSfxFlagSet()) {
            // Not re-triggered: clear state. readJumpPointer already consumed both
            // pointer bytes, so the track continues to the fade-out section.
            ctx.clearContinuousSfxId();
            return;
        }

        // Re-triggered: decrement loop counter
        boolean counterReachedZero = ctx.decrementContSfxLoopCnt();
        if (counterReachedZero) {
            // All tracks have passed their loop point for this cycle — clear flag.
            // The jump still happens (cfJumpTo's `dec de` compensates for loc_BF9's
            // `inc de`), but next time through the flag won't be set so the SFX will
            // fall through to fade-out (unless re-triggered again by game code).
            ctx.clearContinuousSfxFlag();
        }

        // Jump to loop target
        if (jumpTarget != -1) {
            t.pos = jumpTarget;
        } else {
            deactivateOnMalformedData(ctx, t);
        }
    }

    private void handleMetaCommand(
            CoordFlagContext ctx,
            SmpsSequencer.Track t,
            SmpsProgramView program) {
        if (t.pos >= program.dataLength()) return;
        int sub = program.dataByteAt(t.pos++) & 0xFF;

        switch (sub) {
            case 0x00: // TEMPO_SET - set tempo
                if (t.pos < program.dataLength()) {
                    int tempo = program.dataByteAt(t.pos++) & 0xFF;
                    ctx.setNormalTempo(tempo);
                    ctx.recalculateTempo();
                }
                break;

            case 0x01: // SND_CMD - absent from loader-supported ROM streams
                if (t.pos < program.dataLength()) {
                    // Preserve the Z80 stream position for imported/custom data.
                    // No loader-supported stream reaches FF 01, so dispatching here
                    // would invent behavior without a ROM-owned caller.
                    t.pos++;
                }
                break;

            case 0x02: // MUS_PAUSE (MUSP_Z80) - absent from loader-supported streams
                if (t.pos < program.dataLength()) {
                    // Keep the operand consumed for stream alignment. Native
                    // all-track halt/resume has no reached ROM path to model.
                    t.pos++;
                }
                break;

            case 0x03: // COPY_MEM - absent from loader-supported streams
                if (t.pos + 2 < program.dataLength()) {
                    // Preserve the three documented operands. The native
                    // pointer-copy targets shared Z80 RAM, which this sequencer
                    // does not own; no loader-supported stream reaches FF 03.
                    t.pos += 3;
                }
                break;

            case 0x04: // TICK_MULT (TMULT_ALL) - set tick multiplier
                if (t.pos < program.dataLength()) {
                    int tickMult = program.dataByteAt(t.pos++) & 0xFF;
                    ctx.updateDividingTiming(tickMult);
                }
                break;

            case 0x05: // SSG_EG (SEG_NORMAL) - write SSG-EG registers for all 4 operators
                if (t.pos + 3 < program.dataLength()) {
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        int ch = hwCh % 3;
                        // FixBugs = 0 cfSetSSGEG sets HaveSSGEGFlag before
                        // retaining its four bytes, including an all-zero payload.
                        t.customSsgEgPresent = true;
                        // Retain the exact pointer-backed bytes separately:
                        // EF may clear live SSG-EG behavior without clearing the
                        // native custom-restore pointer used by zStopSFX.
                        for (int operator = 0; operator < 4; operator++) {
                            int value = program.dataByteAt(t.pos++) & 0xFF;
                            t.ssgEg[operator] = value;
                            t.customSsgEgPayload[operator] = value;
                        }
                        t.customSsgEgPayloadKnown = true;
                        // zFMInstrumentSSGEGTable traverses 90,98,94,9C.
                        ctx.writeFm(port, 0x90 + ch, t.ssgEg[0]);
                        ctx.writeFm(port, 0x98 + ch, t.ssgEg[1]);
                        ctx.writeFm(port, 0x94 + ch, t.ssgEg[2]);
                        ctx.writeFm(port, 0x9C + ch, t.ssgEg[3]);
                    } else {
                        // Not an FM track, just skip the 4 bytes
                        t.pos += 4;
                    }
                }
                break;

            case 0x06: // FM_VOLENV - FM volume envelope (2 params)
                if (t.pos + 1 < program.dataLength()) {
                    int envId = program.dataByteAt(t.pos++) & 0xFF;
                    int opMask = program.dataByteAt(t.pos++) & 0x0F;
                    // FixBugs = 0 cfFMVolEnv aliases HaveSSGEGFlag and the
                    // low custom SSG-EG pointer byte. A positive envelope
                    // clears custom restore; a negative one retains its sign
                    // but destroys enough of the pointer to reconstruct FF05.
                    t.customSsgEgPresent = (envId & 0x80) != 0;
                    Arrays.fill(t.customSsgEgPayload, 0);
                    t.customSsgEgPayloadKnown = false;
                    if (t.type == SmpsSequencer.TrackType.FM && envId != 0 && opMask != 0) {
                        t.fmVolEnvData = copyPsgEnvelope(program, envId);
                        t.fmVolEnvPos = 0;
                        t.fmVolEnvValue = 0;
                        t.fmVolEnvHold = false;
                        t.fmVolEnvOpMask = opMask;
                    } else {
                        t.fmVolEnvData = null;
                        t.fmVolEnvPos = 0;
                        t.fmVolEnvValue = 0;
                        t.fmVolEnvHold = true;
                        t.fmVolEnvOpMask = 0;
                    }
                    ctx.refreshVolume(t);
                }
                break;

            case 0x07: // SPINDASH_REV_RESET (SDREV_RESET) - reset spindash counter
                runtimeState.setSpindashRevCounter(0);
                break;

            default:
                LOGGER.warning("S3K unknown meta command: FF " + String.format("%02X", sub));
                break;
        }
    }

    private static void applySignedFmVolumeDelta(SmpsSequencer.Track t, int delta) {
        int updated = t.volumeOffset + delta;
        if (updated < 0) {
            updated = 0;
        } else if (updated > 0x7F) {
            updated = 0x7F;
        }
        t.volumeOffset = updated;
    }

    private static byte[] copyPsgEnvelope(
            SmpsProgramView program, int envelopeId) {
        int length = program.psgEnvelopeLength(envelopeId);
        if (length == 0) {
            return null;
        }
        byte[] copy = new byte[length];
        for (int index = 0; index < length; index++) {
            copy[index] = program.psgEnvelopeByteAt(envelopeId, index);
        }
        return copy;
    }

    private static byte[] copyModEnvelope(
            SmpsProgramView program, int envelopeId) {
        int length = program.modEnvelopeLength(envelopeId);
        if (length == 0) {
            return null;
        }
        byte[] copy = new byte[length];
        for (int index = 0; index < length; index++) {
            copy[index] = program.modEnvelopeByteAt(envelopeId, index);
        }
        return copy;
    }

    private static int wrapSignedByte(int value) {
        return (byte) value;
    }

    /**
     * Stops a track that the engine has to give up on because its data does
     * not resolve: an empty return stack, an unreadable jump or loop pointer,
     * or a full gosub stack. The shipped driver never reaches any of these,
     * so there is no ROM behaviour to model; what matters is that the channel
     * does not keep sounding. The track-end flags 0E3h and 0F2h stop the note
     * themselves, which is why {@code trackEndFlagOwnsTheStop} removed the
     * read loop's blanket stop, and these paths must therefore stop it here.
     */
    private static void deactivateOnMalformedData(
            CoordFlagContext ctx, SmpsSequencer.Track t) {
        t.active = false;
        ctx.stopNote(t);
    }
}
