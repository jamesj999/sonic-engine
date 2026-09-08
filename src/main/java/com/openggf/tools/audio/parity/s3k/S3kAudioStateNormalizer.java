package com.openggf.tools.audio.parity.s3k;

import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer.TrackType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts an OpenGGF S3K driver snapshot into the driver-RAM-shaped oracle
 * vocabulary of {@link S3kAudioTick}.
 *
 * <p>Field mappings are the ones inventoried by {@link S3kAudioFieldRegistry};
 * values are re-expressed as the ROM's raw bytes (unsigned) so both sides of
 * the comparison share one coordinate system. Fields the engine does not
 * model are emitted as {@code null} and stay DIAGNOSTIC in the registry.
 */
public final class S3kAudioStateNormalizer {
    /** {@code Sonic3kAudioProfile.getSpeedMultiplierValue()}'s ROM value (zTempoSpeedup = 8). */
    private static final int SPEED_SHOES_TEMPO = 8;

    private S3kAudioStateNormalizer() {
    }

    public static S3kAudioTick normalize(int ordinal, List<Integer> mailbox,
            SmpsDriverSnapshot driver) {
        Objects.requireNonNull(driver, "driver");
        SmpsSequencerSnapshot music = null;
        List<SmpsSequencerSnapshot> sfx = new ArrayList<>();
        for (SmpsDriverSnapshot.SequencerEntry entry : driver.sequencers()) {
            if (entry.sfx()) {
                sfx.add(entry.snapshot());
            } else if (music == null) {
                music = entry.snapshot();
            }
        }

        List<S3kAudioTrackState> tracks = new ArrayList<>(S3kAudioParitySchema.ROLES.size());
        for (int index = 0; index < S3kAudioParitySchema.MUSIC_ROLE_COUNT; index++) {
            tracks.add(musicSlot(index, music));
        }
        for (int index = 0; index < S3kAudioParitySchema.SFX_ROLE_COUNT; index++) {
            tracks.add(sfxSlot(index, sfx));
        }
        return new S3kAudioTick(ordinal, false, mailbox, global(music, driver), tracks, List.of());
    }

    private static S3kAudioTick.GlobalState global(
            SmpsSequencerSnapshot music, SmpsDriverSnapshot driver) {
        if (music == null) {
            return new S3kAudioTick.GlobalState(0, 0, 0, 0, null,
                    driver.fadeOutTimeout(), driver.fadeDelay(),
                    driver.fadeDelayTimeout(), driver.fadeInTimeout(), null,
                    null, null, driver.palUpdateCounter());
        }
        return new S3kAudioTick.GlobalState(
                music.tempoWeight() & 0xff,
                music.tempoAccumulator() & 0xff,
                music.speedShoes() ? SPEED_SHOES_TEMPO : 0,
                music.speedupTimeout() & 0xff,
                null, driver.fadeOutTimeout(),
                // zFadeDelay (1C0Eh) and zFadeDelayTimeout (1C0Fh) are driver
                // variables, not song state (Sound/Z80 Sound
                // Driver.asm:2306-2312, :2784-2789).
                driver.fadeDelay(), driver.fadeDelayTimeout(),
                driver.fadeInTimeout(), null, null, null,
                driver.palUpdateCounter());
    }

    /**
     * Music slot order is the ROM's: FM6/DAC, FM1..FM5, PSG1..PSG3
     * (zTracksStart, D:176-186).
     */
    private static S3kAudioTrackState musicSlot(int slot, SmpsSequencerSnapshot music) {
        String role = S3kAudioParitySchema.ROLES.get(slot);
        if (music == null) {
            return S3kAudioTrackState.idle(role);
        }
        for (SmpsTrackSnapshot track : music.tracks()) {
            if (matchesMusicSlot(slot, track)) {
                return track.active() ? normalizeTrack(role, track) : S3kAudioTrackState.idle(role);
            }
        }
        return S3kAudioTrackState.idle(role);
    }

    private static boolean matchesMusicSlot(int slot, SmpsTrackSnapshot track) {
        TrackType type = track.type();
        return switch (slot) {
            case 0 -> type == TrackType.DAC;
            case 1, 2, 3, 4, 5 -> type == TrackType.FM && track.channelId() == slot - 1;
            default -> type == TrackType.PSG && track.channelId() == slot - 6;
        };
    }

    /** SFX slot order: FM3..FM6, PSG1..PSG3 (zTracksSFXStart, D:190-206). */
    private static S3kAudioTrackState sfxSlot(int slot, List<SmpsSequencerSnapshot> sfx) {
        String role = S3kAudioParitySchema.ROLES.get(S3kAudioParitySchema.MUSIC_ROLE_COUNT + slot);
        S3kAudioTrackState result = S3kAudioTrackState.idle(role);
        for (SmpsSequencerSnapshot sequencer : sfx) {
            for (SmpsTrackSnapshot track : sequencer.tracks()) {
                if (!track.active()) {
                    continue;
                }
                boolean matches = slot < 4
                        ? track.type() == TrackType.FM && track.channelId() == slot + 2
                        : track.type() == TrackType.PSG && track.channelId() == slot - 4;
                if (matches) {
                    // The ROM has one struct per hardware slot; the latest
                    // admitted SFX owns it, and sequencers iterate in
                    // admission order.
                    result = normalizeTrack(role, track);
                }
            }
        }
        return result;
    }

    private static S3kAudioTrackState normalizeTrack(String role, SmpsTrackSnapshot track) {
        boolean psg = track.type() == TrackType.PSG;
        boolean dac = track.type() == TrackType.DAC;
        Integer frequency;
        if (dac) {
            // zTrack offset 0Dh is SavedDAC on a DAC track and FreqLow on an
            // FM/PSG one; they are the same byte (Sound/Z80 Sound Driver.asm:
            // 45-56). zUpdateDACTrack_cont stores the raw sample byte there
            // including bit 7, before the rest check, and reuses it verbatim
            // when a duration follows without a note (D:2880-2892). The engine
            // keeps that byte as the track note, so the two agree directly.
            // FreqHigh at 0Eh is unused by a DAC track and stays zero.
            frequency = track.note() & 0xff;
        } else if (psg) {
            frequency = track.baseFnum() & 0xffff;
        } else {
            int freqHigh = ((track.baseBlock() & 0x07) << 3) | ((track.baseFnum() >> 8) & 0x07);
            frequency = (freqHigh << 8) | (track.baseFnum() & 0xff);
        }
        // zTrack.AMSFMSPan (0Ah) is not an FM-only byte. zZeroFillTrackRAM
        // stores 0C0h into it for every track it initialises (Sound/Z80 Sound
        // Driver.asm:2181-2198), and zBGMLoad's PSG loop calls it for each PSG
        // track just as the FM/DAC loop does (:1867-1881). cfPanningAMSFMS
        // likewise stores the byte for whatever track it is given (:3010-3024).
        // So a PSG track carries the byte in ROM RAM, the engine's Track holds
        // the same default, and projecting null here would drop a real field.
        Integer amsFmsPan =
                (track.pan() & 0xc0) | ((track.ams() & 0x03) << 4) | (track.fms() & 0x07);
        return new S3kAudioTrackState(role, true,
                track.overridden(),
                track.tieNext(),
                track.resting(),
                voiceControl(track),
                track.dividingTiming() & 0xff,
                null,
                track.keyOffset() & 0xff,
                track.volumeOffset() & 0xff,
                modulationCtrl(track),
                (psg ? track.instrumentId() : track.voiceId()) & 0xff,
                amsFmsPan,
                track.duration() & 0xff,
                track.scaledDuration() & 0xff,
                frequency,
                track.detune() & 0xff,
                // VolEnv (17h) is the envelope position, which
                // zDoVolEnvAdvance increments and zFinishTrackUpdate clears
                // (Sound/Z80 Sound Driver.asm:1055-1069, :4211-4213), not the
                // envelope's identity. NoteFillTimeout (1Eh) and
                // NoteFillMaster (1Fh) are the running and reload copies of
                // the note fill (:4070-4077).
                track.envPos() & 0xff,
                track.fillCounter() & 0xff,
                track.fill() & 0xff,
                // The modulation pointer is a Z80 address the engine has no
                // equivalent for, like the data pointer above.
                null,
                // zTrack offsets 22h-27h. The engine's names differ but the
                // bytes are the same state: the accumulator zDoModulation adds
                // its delta into, and the wait, speed, delta and step counters
                // zPrepareModulation loads from the modulation data
                // (Sound/Z80 Sound Driver.asm:34-97, :1277-1327, :1330-1352).
                track.modAccumulator() & 0xffff,
                track.modDelay() & 0xff,
                track.modRateCounter() & 0xff,
                track.modCurrentDelta() & 0xff,
                track.modStepCounter() & 0xff);
    }

    /**
     * VoiceControl (offset 01): FM bits 0-1 channel + bit 2 part II; PSG
     * 80h|A0h|C0h; the FM6/DAC slot initialises to 06h (zFMDACInitBytes,
     * D:1899-1907; zPSGInitBytes, D:1913-1916).
     */
    private static Integer voiceControl(SmpsTrackSnapshot track) {
        return switch (track.type()) {
            case DAC -> 0x06;
            case FM -> track.channelId() < 3 ? track.channelId() : track.channelId() + 1;
            case PSG -> 0x80 | (track.channelId() << 5);
        };
    }

    /**
     * ModulationCtrl (offset 07): 0 off, 80h normal modulation, 1..8 mod
     * envelope index+1 (map §2, §7).
     */
    private static Integer modulationCtrl(SmpsTrackSnapshot track) {
        if (track.modEnvId() > 0) {
            return track.modEnvId() & 0xff;
        }
        return track.modEnabled() ? 0x80 : 0;
    }
}
