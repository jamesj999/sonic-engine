package com.openggf.game.sonic3k.audio;

import com.openggf.audio.driver.SmpsChannelOwnershipProjection;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Read-only S3K input for the host-owned {@code cmd_StopSFX (E4h)} operation.
 *
 * <p>This class itself plans nothing and emits no writes. It is intentionally
 * derived from the game-agnostic ownership view so the host operation does not
 * introduce a second mutable seven-slot table or claim that OpenGGF stores raw
 * Z80 RAM bytes it does not retain.
 */
public record S3kE4Projection(
        boolean complete,
        List<SlotProjection> slots) {
    public S3kE4Projection {
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (slots.size() != S3kE4Slot.values().length) {
            throw new IllegalArgumentException(
                    "S3K E4 projection requires exactly seven slots");
        }
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).slot() != S3kE4Slot.values()[index]) {
                throw new IllegalArgumentException(
                        "S3K E4 slots must retain native order");
            }
        }
    }

    public static S3kE4Projection capture(
            SmpsChannelOwnershipProjection ownership) {
        Objects.requireNonNull(ownership, "ownership");
        boolean complete = true;
        List<SlotProjection> slots = new ArrayList<>(7);
        for (S3kE4Slot slot : S3kE4Slot.values()) {
            SmpsChannelOwnershipProjection.RoleOwnership role = ownership.role(
                    slot.physicalChannel()).orElse(null);
            if (role == null) {
                slots.add(new SlotProjection(slot, Availability.AVAILABLE,
                        null, null));
                continue;
            }
            Optional<SmpsChannelOwnershipProjection.TrackProjection> sfx =
                    role.unambiguousSfxClaim();
            Optional<SmpsChannelOwnershipProjection.TrackProjection> music =
                    role.unambiguousMusicTrack();
            boolean ambiguousSfx = role.sfxClaims().size() > 1;
            boolean ambiguousMusic = role.musicTracks().size() > 1;
            boolean malformed = role.sfxClaims().stream().anyMatch(
                    claim -> !matches(slot, claim.track()));
            boolean missingRawState = sfx.map(track -> !hasRequiredRawState(track.track()))
                    .orElse(false)
                    || music.map(track -> !hasRequiredRawState(track.track()))
                    .orElse(false);
            Availability availability = ambiguousSfx || ambiguousMusic || malformed || missingRawState
                    ? Availability.UNAVAILABLE_AMBIGUOUS_OR_INVALID
                    : Availability.AVAILABLE;
            if (availability != Availability.AVAILABLE) {
                complete = false;
            }
            slots.add(new SlotProjection(slot, availability,
                    availability == Availability.AVAILABLE
                            ? sfx.map(S3kE4Track::from).orElse(null) : null,
                    availability == Availability.AVAILABLE
                            ? music.map(S3kE4Track::from).orElse(null) : null));
        }
        // An active SFX declaration outside zTracksSFX_FM3..PSG3 has no
        // well-defined E4 slot. Do not silently route it through a nearby
        // channel; the host operation must fail closed.
        for (SmpsChannelOwnershipProjection.RoleOwnership role
                : ownership.roles().values()) {
            if (role.sfxClaims().isEmpty()) {
                continue;
            }
            boolean known = false;
            for (S3kE4Slot slot : S3kE4Slot.values()) {
                if (slot.physicalChannel().equals(role.channel())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                complete = false;
            }
        }
        return new S3kE4Projection(complete, slots);
    }

    private static boolean matches(S3kE4Slot slot, SmpsTrackSnapshot track) {
        return track.type() == slot.trackType()
                && track.channelId() == slot.channel();
    }

    private static boolean hasRequiredRawState(SmpsTrackSnapshot track) {
        return (!track.noiseMode() || track.rawPsgNoiseKnown())
                && (!track.customSsgEgPresent()
                        || track.customSsgEgPayloadKnown());
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE_AMBIGUOUS_OR_INVALID
    }

    /** Native zTracksSFX order and the raw VoiceControl values it represents. */
    public enum S3kE4Slot {
        FM3(SmpsSequencer.TrackType.FM, 2, 0x02),
        FM4(SmpsSequencer.TrackType.FM, 3, 0x04),
        FM5(SmpsSequencer.TrackType.FM, 4, 0x05),
        FM6(SmpsSequencer.TrackType.FM, 5, 0x06),
        PSG1(SmpsSequencer.TrackType.PSG, 0, 0x80),
        PSG2(SmpsSequencer.TrackType.PSG, 1, 0xA0),
        PSG3(SmpsSequencer.TrackType.PSG, 2, 0xC0);

        private final SmpsSequencer.TrackType trackType;
        private final int channel;
        private final int rawVoiceControl;

        S3kE4Slot(SmpsSequencer.TrackType trackType, int channel,
                int rawVoiceControl) {
            this.trackType = trackType;
            this.channel = channel;
            this.rawVoiceControl = rawVoiceControl;
        }

        public SmpsSequencer.TrackType trackType() {
            return trackType;
        }

        public int channel() {
            return channel;
        }

        public int rawVoiceControl() {
            return rawVoiceControl;
        }

        public SmpsChannelOwnershipProjection.PhysicalChannel physicalChannel() {
            return new SmpsChannelOwnershipProjection.PhysicalChannel(
                    trackType == SmpsSequencer.TrackType.FM
                            ? SmpsChannelOwnershipProjection.Bus.FM
                            : SmpsChannelOwnershipProjection.Bus.PSG,
                    channel);
        }
    }

    public record SlotProjection(
            S3kE4Slot slot,
            Availability availability,
            S3kE4Track sfx,
            S3kE4Track music) {
        public SlotProjection {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(availability, "availability");
            if (availability != Availability.AVAILABLE
                    && (sfx != null || music != null)) {
                throw new IllegalArgumentException(
                        "unavailable E4 slots cannot expose guessed state");
            }
        }
    }

    /**
     * The subset of track state read by the S3K E4 loop.  playbackFlags is a
     * semantic reconstruction only: raw Z80 byte layout/custom bits are not
     * retained by the engine, and {@link #rawPlaybackFlags()} is therefore
     * deliberately empty. {@code noiseOrFm3Special} is reconstructed from the
     * retained FM3-special or PSG-noise semantic state, according to the slot.
     * The raw PSG-noise byte is exposed only when an executed F3 command
     * retained it; callers must not reconstruct it from {@code psgNoise}.
     */
    public record S3kE4Track(
            SmpsChannelOwnershipProjection.TrackCoordinate coordinate,
            int canonicalVoiceControl,
            boolean playing,
            boolean noAttack,
            boolean overriding,
            boolean noiseOrFm3Special,
            int canonicalPlaybackFlags,
            OptionalInt rawPlaybackFlags,
            int voiceId,
            SmpsSourceDescriptor voiceSource,
            byte[] materializedVoice,
            int volume,
            int pan,
            int ams,
            int fms,
            int psgNoise,
            OptionalInt rawPsgNoise,
            int[] customSsgEgPayload,
            boolean customSsgEgPresent) {
        public S3kE4Track {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(rawPlaybackFlags, "rawPlaybackFlags");
            Objects.requireNonNull(rawPsgNoise, "rawPsgNoise");
            Objects.requireNonNull(voiceSource, "voiceSource");
            materializedVoice = copy(materializedVoice);
            customSsgEgPayload = copy(customSsgEgPayload);
        }

        static S3kE4Track from(
                SmpsChannelOwnershipProjection.TrackProjection projection) {
            SmpsTrackSnapshot track = projection.track();
            S3kE4Slot slot = slotFor(track);
            boolean playing = track.active();
            boolean noAttack = track.tieNext();
            boolean overriding = track.overridden();
            boolean special = slot == S3kE4Slot.FM3
                    ? track.fm3SpecialMode()
                    : track.type() == SmpsSequencer.TrackType.PSG
                            && track.noiseMode();
            int flags = (playing ? 0x80 : 0)
                    | (noAttack ? 0x02 : 0)
                    | (overriding ? 0x04 : 0)
                    | (special ? 0x01 : 0);
            return new S3kE4Track(projection.coordinate(),
                    slot.rawVoiceControl(), playing, noAttack, overriding,
                    special, flags, OptionalInt.empty(), track.voiceId(),
                    projection.coordinate().source(), track.voiceData(),
                    track.volumeOffset(), track.pan(), track.ams(), track.fms(),
                    track.psgNoiseParam(),
                    track.rawPsgNoiseKnown()
                            ? OptionalInt.of(track.rawPsgNoise()) : OptionalInt.empty(),
                    track.customSsgEgPayload(), track.customSsgEgPresent());
        }

        @Override
        public byte[] materializedVoice() {
            return copy(materializedVoice);
        }

        @Override
        public int[] customSsgEgPayload() {
            return copy(customSsgEgPayload);
        }

        private static S3kE4Slot slotFor(SmpsTrackSnapshot track) {
            for (S3kE4Slot slot : S3kE4Slot.values()) {
                if (matches(slot, track)) {
                    return slot;
                }
            }
            throw new IllegalArgumentException(
                    "track does not have an S3K E4 slot");
        }

        private static byte[] copy(byte[] value) {
            return value == null ? null : Arrays.copyOf(value, value.length);
        }

        private static int[] copy(int[] value) {
            return value == null ? null : Arrays.copyOf(value, value.length);
        }
    }
}
