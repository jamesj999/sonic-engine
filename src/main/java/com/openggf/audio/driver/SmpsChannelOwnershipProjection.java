package com.openggf.audio.driver;

import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.rewind.SmpsTrackSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, logical ownership view captured by {@link SmpsDriver}.
 *
 * <p>The view deliberately describes declared, live SFX tracks rather than
 * write-acquired locks.  Locks are an output-routing implementation detail:
 * an admission-owned SFX can own a channel before its first write, while a
 * released track can remain present in a sequencer.  Consumers therefore get
 * stable coordinates and snapshot data, never mutable {@code Track} objects.
 */
public record SmpsChannelOwnershipProjection(
        Map<PhysicalChannel, RoleOwnership> roles) {
    public SmpsChannelOwnershipProjection {
        roles = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(roles, "roles")));
    }

    public Optional<RoleOwnership> role(PhysicalChannel channel) {
        return Optional.ofNullable(roles.get(Objects.requireNonNull(
                channel, "channel")));
    }

    public enum Bus {
        FM,
        PSG,
        DAC
    }

    /** A normalized physical role, independent of a sequencer's list order. */
    public record PhysicalChannel(Bus bus, int channel) {
        public PhysicalChannel {
            Objects.requireNonNull(bus, "bus");
            if (channel < 0) {
                throw new IllegalArgumentException(
                        "physical channel must not be negative");
            }
        }
    }

    /** Stable location and immutable source identity for one captured track. */
    public record TrackCoordinate(
            int sequencerIndex,
            int trackIndex,
            boolean sfx,
            SmpsSourceDescriptor source) {
        public TrackCoordinate {
            if (sequencerIndex < 0 || trackIndex < 0) {
                throw new IllegalArgumentException(
                        "track coordinates must not be negative");
            }
            Objects.requireNonNull(source, "source");
        }
    }

    /** A captured track and its coordinate; neither field holds a live track. */
    public record TrackProjection(
            TrackCoordinate coordinate,
            SmpsTrackSnapshot track) {
        public TrackProjection {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(track, "track");
        }
    }

    /** All declared live tracks for one physical role. */
    public record RoleOwnership(
            PhysicalChannel channel,
            List<TrackProjection> sfxClaims,
            List<TrackProjection> musicTracks) {
        public RoleOwnership {
            Objects.requireNonNull(channel, "channel");
            sfxClaims = List.copyOf(Objects.requireNonNull(
                    sfxClaims, "sfxClaims"));
            musicTracks = List.copyOf(Objects.requireNonNull(
                    musicTracks, "musicTracks"));
        }

        public boolean sfxOccupied() {
            return !sfxClaims.isEmpty();
        }

        public Optional<TrackProjection> unambiguousSfxClaim() {
            return sfxClaims.size() == 1
                    ? Optional.of(sfxClaims.getFirst()) : Optional.empty();
        }

        public Optional<TrackProjection> unambiguousMusicTrack() {
            return musicTracks.size() == 1
                    ? Optional.of(musicTracks.getFirst()) : Optional.empty();
        }
    }
}
