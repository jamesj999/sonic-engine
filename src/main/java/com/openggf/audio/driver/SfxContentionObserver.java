package com.openggf.audio.driver;

import com.openggf.audio.rewind.SmpsSourceDescriptor;
import java.util.Objects;
import java.util.List;

/** Opt-in, void-only diagnostic view of SFX admission and channel arbitration. */
public interface SfxContentionObserver {
    SfxContentionObserver NONE = new SfxContentionObserver() { };

    default void onSfxAdmitted(Admission admission) { }

    default void onRoleArbitrated(Arbitration arbitration) { }

    enum Bus { FM, PSG }

    record Source(SmpsSourceDescriptor descriptor, long admissionOrdinal,
                  boolean sfx, boolean specialSfx) {
        public Source {
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    record Admission(Source source, List<Role> declaredRoles) {
        public Admission {
            Objects.requireNonNull(source, "source");
            declaredRoles = List.copyOf(Objects.requireNonNull(declaredRoles, "declaredRoles"));
            if (!source.sfx() || source.admissionOrdinal() < 0) {
                throw new IllegalArgumentException("admission must identify an SFX request");
            }
        }
    }

    record Role(Bus bus, int channel) {
        public Role {
            Objects.requireNonNull(bus, "bus");
            if (channel < 0) throw new IllegalArgumentException("channel must be non-negative");
        }
    }

    record Arbitration(Bus bus, int channel, Source challenger,
                       Source previousOwner, boolean acquired) {
        public Arbitration {
            Objects.requireNonNull(bus, "bus");
            Objects.requireNonNull(challenger, "challenger");
            if (channel < 0) {
                throw new IllegalArgumentException("channel must be non-negative");
            }
        }
    }
}
