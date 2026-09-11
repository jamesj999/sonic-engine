package com.openggf.game.sonic3k.audio;

import com.openggf.audio.session.SmpsChipWrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable physical program for the shipped S3K {@code zStopSFX} routine.
 *
 * <p>The plan is deliberately derived solely from {@link S3kE4Projection}; it
 * never retains a live track or resolves state after its pre-write validation.
 * FixBugs is off in the shipped driver. In particular, a PSG SFX slot reaches
 * {@code zFMSilenceChannel}'s unchecked {@code zKeyOnOff} and writes its raw
 * voice-control byte to YM register {@code 28h}.
 */
public record S3kE4StopSfxPlan(boolean accepted,
        List<SmpsChipWrite> writes) {
    private static final int[] OPERATOR_OFFSETS = {0, 8, 4, 12};
    private static final int[] FM_PARAMETER_REGISTERS = {
            0x30, 0x50, 0x60, 0x70, 0x80
    };

    public S3kE4StopSfxPlan {
        writes = List.copyOf(Objects.requireNonNull(writes, "writes"));
        if (!accepted && !writes.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected E4 plan must not expose a partial write program");
        }
    }

    public static S3kE4StopSfxPlan prepare(S3kE4Projection projection) {
        S3kE4Projection input = Objects.requireNonNull(projection,
                "projection");
        if (!input.complete() || input.slots().stream().anyMatch(slot ->
                slot.availability() != S3kE4Projection.Availability.AVAILABLE
                        || !consistentSlot(slot))) {
            return rejected();
        }

        List<SmpsChipWrite> writes = new ArrayList<>();
        for (S3kE4Projection.SlotProjection slot : input.slots()) {
            S3kE4Projection.S3kE4Track sfx = slot.sfx();
            if (sfx == null || !sfx.playing()) {
                continue;
            }
            appendSilenceStop(writes, slot.slot(), sfx);
            appendMusicRestore(writes, slot.slot(), slot.music());
        }
        return new S3kE4StopSfxPlan(true, writes);
    }

    public static S3kE4StopSfxPlan rejected() {
        return new S3kE4StopSfxPlan(false, List.of());
    }

    private static boolean consistentSlot(S3kE4Projection.SlotProjection slot) {
        if (!matches(slot.slot(), slot.sfx()) || !matches(slot.slot(), slot.music())
                || !hasRepresentableRawState(slot.sfx())
                || !hasRepresentableRawState(slot.music())) {
            return false;
        }
        if (slot.sfx() == null || !slot.sfx().playing()) {
            return true;
        }
        S3kE4Projection.S3kE4Track music = slot.music();
        if (music == null || !music.playing()) {
            return true;
        }
        // A live active music slot must have been the one claimed by the SFX
        // track. Otherwise the Java ownership state is not a valid zTrack
        // pairing and E4 must not guess an unrelated restore program.
        if (!music.overriding()) {
            return false;
        }
        if (slot.slot().trackType() == com.openggf.audio.smps.SmpsSequencer.TrackType.FM) {
            byte[] voice = music.materializedVoice();
            return voice != null && voice.length >= 25;
        }
        return !music.noiseOrFm3Special()
                || music.rawPsgNoise().isPresent();
    }

    private static boolean matches(S3kE4Projection.S3kE4Slot slot,
            S3kE4Projection.S3kE4Track track) {
        return track == null || track.canonicalVoiceControl()
                == slot.rawVoiceControl();
    }

    private static boolean hasRepresentableRawState(
            S3kE4Projection.S3kE4Track track) {
        if (track == null) {
            return true;
        }
        if (track.rawPsgNoise().isPresent()
                && (track.rawPsgNoise().getAsInt() < 0
                || track.rawPsgNoise().getAsInt() > 0xFF)) {
            return false;
        }
        if (!track.customSsgEgPresent()) {
            return true;
        }
        int[] payload = track.customSsgEgPayload();
        return payload != null && payload.length == OPERATOR_OFFSETS.length
                && java.util.Arrays.stream(payload)
                .allMatch(value -> value >= 0 && value <= 0xFF);
    }

    private static void appendSilenceStop(List<SmpsChipWrite> writes,
            S3kE4Projection.S3kE4Slot slot,
            S3kE4Projection.S3kE4Track sfx) {
        if (slot.trackType() == com.openggf.audio.smps.SmpsSequencer.TrackType.FM
                && !sfx.overriding()) {
            int port = port(slot);
            int channel = channel(slot);
            // zFMSilenceChannel -> zSetMaxRelRate / zFMOperatorWriteLoop.
            for (int offset = 0; offset <= 12; offset += 4) {
                writes.add(new SmpsChipWrite.Ym2612(port,
                        0x80 + offset + channel, 0xFF));
            }
            for (int offset = 0; offset <= 12; offset += 4) {
                writes.add(new SmpsChipWrite.Ym2612(port,
                        0x40 + offset + channel, 0x7F));
            }
        }
        // The unchecked zKeyOnOff call is native even for PSG tracks.
        writes.add(new SmpsChipWrite.Ym2612(0, 0x28,
                slot.rawVoiceControl()));
        if (slot.trackType() == com.openggf.audio.smps.SmpsSequencer.TrackType.PSG) {
            // cfStopTrack reaches zGetSFXChannelPointers while ix still names
            // the stopped SFX track. FixBugs=0 runs zSilencePSGChannel, then
            // compensates for its broken noise test with an unconditional FF.
            writes.add(new SmpsChipWrite.Psg(0x1F
                    + slot.rawVoiceControl()));
            if (sfx.noiseOrFm3Special()) {
                writes.add(new SmpsChipWrite.Psg(0xFF));
            }
            writes.add(new SmpsChipWrite.Psg(0xFF));
            return;
        }
        // cfStopTrack reaches zKeyOffIfActive after the unconditional silence.
        if (!sfx.noAttack() && !sfx.overriding()) {
            writes.add(new SmpsChipWrite.Ym2612(0, 0x28,
                    slot.rawVoiceControl()));
        }
    }

    private static void appendMusicRestore(List<SmpsChipWrite> writes,
            S3kE4Projection.S3kE4Slot slot,
            S3kE4Projection.S3kE4Track music) {
        if (music == null || !music.playing() || !music.overriding()) {
            return;
        }
        if (slot.trackType() == com.openggf.audio.smps.SmpsSequencer.TrackType.PSG) {
            if (music.noiseOrFm3Special()
                    && music.rawPsgNoise().isPresent()
                    && (music.rawPsgNoise().getAsInt() & 0x80) != 0) {
                writes.add(new SmpsChipWrite.Psg(
                        music.rawPsgNoise().getAsInt()));
            }
            return;
        }

        int port = port(slot);
        int channel = channel(slot);
        if (slot == S3kE4Projection.S3kE4Slot.FM3) {
            writes.add(new SmpsChipWrite.Ym2612(0, 0x27,
                    music.noiseOrFm3Special() ? 0x4F : 0x0F));
        }
        byte[] voice = music.materializedVoice();
        writes.add(new SmpsChipWrite.Ym2612(port, 0xB4 + channel,
                (music.pan() & 0xC0)
                        | ((music.ams() & 0x3) << 4)
                        | (music.fms() & 0x7)));
        writes.add(new SmpsChipWrite.Ym2612(port, 0xB0 + channel,
                voice[0] & 0xFF));
        for (int groupIndex = 0;
                groupIndex < FM_PARAMETER_REGISTERS.length; groupIndex++) {
            int group = FM_PARAMETER_REGISTERS[groupIndex];
            for (int operator = 0; operator < OPERATOR_OFFSETS.length;
                    operator++) {
                int value = voice[1 + groupIndex * 4 + operator]
                        & 0xFF;
                writes.add(new SmpsChipWrite.Ym2612(port,
                        group + OPERATOR_OFFSETS[operator] + channel,
                        value));
            }
        }
        for (int operator = 0; operator < OPERATOR_OFFSETS.length; operator++) {
            int value = voice[21 + operator] & 0xFF;
            if ((value & 0x80) != 0) {
                value = (value + music.volume()) & 0xFF;
            }
            writes.add(new SmpsChipWrite.Ym2612(port,
                    0x40 + OPERATOR_OFFSETS[operator] + channel,
                    value & 0x7F));
        }
        if (music.customSsgEgPresent()) {
            int[] payload = music.customSsgEgPayload();
            for (int operator = 0; operator < OPERATOR_OFFSETS.length;
                    operator++) {
                writes.add(new SmpsChipWrite.Ym2612(port,
                        0x90 + OPERATOR_OFFSETS[operator] + channel,
                        payload[operator]));
            }
        }
    }

    private static int port(S3kE4Projection.S3kE4Slot slot) {
        return slot.channel() < 3 ? 0 : 1;
    }

    private static int channel(S3kE4Projection.S3kE4Slot slot) {
        return slot.channel() % 3;
    }
}
