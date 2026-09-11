package com.openggf.audio.driver;

import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import java.util.Objects;

/**
 * Append-only diagnostic view of complete driver services and out-of-service
 * lifecycle mutations. Observers are deliberately absent from snapshots.
 */
public interface SmpsDriverServiceObserver {
    SmpsDriverServiceObserver NONE = new SmpsDriverServiceObserver() { };

    default void onServiceBegin(long ordinal) { }

    default void onServiceEnd(
            long ordinal, SmpsDriverSnapshot snapshot) { }

    default void onServiceBegin(ServiceEvent event) {
        onServiceBegin(event.ordinal());
    }

    default void onServiceEnd(
            ServiceEvent event, SmpsDriverSnapshot snapshot) {
        onServiceEnd(event.ordinal(), snapshot);
    }

    default void onLifecycle(LifecycleEvent event) { }

    enum DriverOriginKind {
        MUSIC,
        SFX,
        UNSPECIFIED
    }

    record DriverAdmissionOrigin(
            DriverOriginKind kind, long admissionOrdinal, int soundId) {
        public DriverAdmissionOrigin {
            Objects.requireNonNull(kind, "kind");
        }

        public static DriverAdmissionOrigin unspecified() {
            return new DriverAdmissionOrigin(
                    DriverOriginKind.UNSPECIFIED, -1, -1);
        }
    }

    record DriverIdentity(
            long instanceOrdinal, DriverAdmissionOrigin origin) {
        public DriverIdentity {
            Objects.requireNonNull(origin, "origin");
        }

        public static DriverIdentity unspecified() {
            return new DriverIdentity(-1,
                    DriverAdmissionOrigin.unspecified());
        }
    }

    record SequencerIdentity(
            long instanceOrdinal,
            SmpsSourceDescriptor source,
            boolean sfx) {
        public SequencerIdentity {
            Objects.requireNonNull(source, "source");
        }
    }

    /** The exact semantic mutation boundary represented by one service pair. */
    enum ServiceKind {
        SEQUENCER_TICK,
        FADE_STEP,
        COMPLETION_CLEANUP
    }

    record ServiceEvent(
            long ordinal,
            DriverIdentity driver,
            SequencerIdentity sequencer,
            ServiceKind kind) {
        public ServiceEvent {
            Objects.requireNonNull(driver, "driver");
            Objects.requireNonNull(sequencer, "sequencer");
            Objects.requireNonNull(kind, "kind");
        }
    }

    enum LifecycleScope {
        DRIVER,
        REGISTRY,
        SESSION,
        PCM
    }

    enum LifecycleSource {
        DRIVER_CONSTRUCTION,
        DRIVER_MUTATION,
        SNAPSHOT_RESTORE,
        MUSIC_OVERRIDE,
        COMMAND,
        SESSION_CONTROL,
        RAW_PCM
    }

    record LifecycleEvent(
            LifecycleKind kind,
            LifecycleScope scope,
            LifecycleSource source,
            DriverIdentity driver) {
        public LifecycleEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(source, "source");
            if (scope == LifecycleScope.DRIVER && driver == null) {
                throw new IllegalArgumentException(
                        "driver lifecycle must identify its driver");
            }
        }

        public static LifecycleEvent driver(
                LifecycleKind kind,
                LifecycleSource source,
                DriverIdentity identity) {
            return new LifecycleEvent(kind, LifecycleScope.DRIVER,
                    source, identity);
        }

        public static LifecycleEvent registry(
                LifecycleKind kind, LifecycleSource source) {
            return new LifecycleEvent(kind, LifecycleScope.REGISTRY,
                    source, null);
        }

        public static LifecycleEvent session(LifecycleKind kind) {
            return new LifecycleEvent(kind, LifecycleScope.SESSION,
                    LifecycleSource.SESSION_CONTROL, null);
        }

        public static LifecycleEvent pcm(LifecycleKind kind) {
            return new LifecycleEvent(kind, LifecycleScope.PCM,
                    LifecycleSource.RAW_PCM, null);
        }
    }

    enum LifecycleKind {
        DRIVER_CREATED,
        RESET,
        PAUSE,
        RESUME,
        STOP_ALL,
        STOP_ALL_SFX,
        SAVE,
        RESTORE,
        SEGA_PCM_ENTER,
        SEGA_PCM_LEAVE
    }
}
