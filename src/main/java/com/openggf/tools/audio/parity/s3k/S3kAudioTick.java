package com.openggf.tools.audio.parity.s3k;

import com.openggf.tools.audio.parity.AudioParityChipWrite;

import java.util.List;
import java.util.Objects;

/**
 * One S3K oracle tick: globals, sixteen track slots, and Z80-owned chip writes.
 *
 * <p>{@code ordinal} counts driver services from zero. {@code frame} is the
 * movie frame the service completed in. Under the v2 stream the two diverge,
 * because a frame whose driver work overruns it contributes no tick; under v1
 * they are equal by construction.
 */
public record S3kAudioTick(
        int ordinal,
        int frame,
        boolean lag,
        List<Integer> mailbox,
        GlobalState global,
        List<S3kAudioTrackState> tracks,
        List<AudioParityChipWrite> writes,
        ProducerInputEvidence producerInputEvidence) {

    public S3kAudioTick {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative");
        }
        if (frame < ordinal) {
            throw new IllegalArgumentException(
                    "a service cannot complete before its own ordinal: ordinal "
                            + ordinal + ", frame " + frame);
        }
        Objects.requireNonNull(global, "global");
        Objects.requireNonNull(producerInputEvidence,
                "producerInputEvidence");
        mailbox = List.copyOf(mailbox);
        tracks = List.copyOf(tracks);
        writes = List.copyOf(writes);
        if (mailbox.size() != 3) {
            throw new IllegalArgumentException("mailbox must carry zMusicNumber/zSFXNumber0/zSFXNumber1");
        }
        if (tracks.size() != S3kAudioParitySchema.ROLES.size()) {
            throw new IllegalArgumentException("tick must contain all sixteen fixed roles");
        }
        for (int index = 0; index < tracks.size(); index++) {
            if (!S3kAudioParitySchema.ROLES.get(index).equals(tracks.get(index).role())) {
                throw new IllegalArgumentException("fixed role absent or out of order at index " + index);
            }
        }
    }

    /** A tick whose service completed in its own frame, with available input. */
    public S3kAudioTick(
            int ordinal,
            boolean lag,
            List<Integer> mailbox,
            GlobalState global,
            List<S3kAudioTrackState> tracks,
            List<AudioParityChipWrite> writes) {
        this(ordinal, ordinal, lag, mailbox, global, tracks, writes,
                ProducerInputEvidence.available());
    }

    /** A tick whose service completed in its own frame. */
    public S3kAudioTick(
            int ordinal,
            boolean lag,
            List<Integer> mailbox,
            GlobalState global,
            List<S3kAudioTrackState> tracks,
            List<AudioParityChipWrite> writes,
            ProducerInputEvidence producerInputEvidence) {
        this(ordinal, ordinal, lag, mailbox, global, tracks, writes,
                producerInputEvidence);
    }

    /** Authenticated availability of the producer input consumed by a service. */
    public record ProducerInputEvidence(
            Availability availability,
            String detail) {
        public enum Availability {
            AVAILABLE,
            UNAVAILABLE_DURING_PRODUCER_SUSPENSION
        }

        public ProducerInputEvidence {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(detail, "detail");
            if (availability == Availability.AVAILABLE && !detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "available producer input cannot carry a limitation detail");
            }
            if (availability != Availability.AVAILABLE && detail.isBlank()) {
                throw new IllegalArgumentException(
                        "unavailable producer input requires evidence detail");
            }
        }

        public static ProducerInputEvidence available() {
            return new ProducerInputEvidence(Availability.AVAILABLE, "");
        }

        public static ProducerInputEvidence unavailable(String detail) {
            return new ProducerInputEvidence(
                    Availability.UNAVAILABLE_DURING_PRODUCER_SUSPENSION,
                    detail);
        }

        public boolean unavailable() {
            return availability != Availability.AVAILABLE;
        }
    }

    /**
     * Driver globals (zDataStart variables, routine map §1.3). Nullable values
     * are reference-only observations the engine does not model.
     */
    public record GlobalState(
            Integer currentTempo,       // 1C24 zCurrentTempo
            Integer tempoAccumulator,   // 1C13 zTempoAccumulator
            Integer tempoSpeedup,       // 1C08 zTempoSpeedup
            Integer speedupTimeout,     // 1C2F zSpeedupTimeout
            Integer dacIndex,           // 1C30 zDACIndex
            Integer fadeOutTimeout,     // 1C0D
            Integer fadeDelay,          // 1C0E
            Integer fadeDelayTimeout,   // 1C0F
            Integer fadeInTimeout,      // 1C29
            Integer pauseFlag,          // 1C10
            List<Integer> soundQueue,   // 1C05-07
            Integer nextSound,          // 1C09
            Integer palDoubleUpdateCounter) { // 1C04, boot-completion marker
        public GlobalState {
            soundQueue = soundQueue == null ? null : List.copyOf(soundQueue);
        }
    }
}
