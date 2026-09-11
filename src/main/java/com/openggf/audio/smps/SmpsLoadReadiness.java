package com.openggf.audio.smps;

import java.util.Objects;

/** Immutable description of ROM-owned work required before music can service. */
public interface SmpsLoadReadiness {
    record Context(SmpsSequencer.Region region, boolean speedShoesEnabled) {
        public Context {
            Objects.requireNonNull(region, "region");
        }
    }

    interface Work {
        boolean ready();
        boolean advanceOnePresentation();
        long remainingTStates();
        Work copy();
    }

    SmpsLoadReadiness IMMEDIATE = new SmpsLoadReadiness() {
        private final Work ready = new Work() {
            @Override public boolean ready() { return true; }
            @Override public boolean advanceOnePresentation() { return true; }
            @Override public long remainingTStates() { return 0; }
            @Override public Work copy() { return this; }
        };

        @Override public boolean immediate() { return true; }
        @Override public int compressedByteCount() { return 0; }
        @Override public int workUnitCount() { return 0; }
        @Override public long minimumTStates(Context context) { return 0; }
        @Override public Work begin(Context context) { return ready; }
        @Override public String provenance() { return "immediate-v1"; }
    };

    static SmpsLoadReadiness immediatePlan() {
        return IMMEDIATE;
    }

    boolean immediate();
    int compressedByteCount();
    int workUnitCount();
    long minimumTStates(Context context);
    Work begin(Context context);
    default Work resume(Context context, long remainingTStates) {
        if (remainingTStates != 0) {
            throw new IllegalArgumentException(
                    "immediate readiness cannot resume pending work");
        }
        return begin(context);
    }
    String provenance();

    default String provenance(Context context) {
        return provenance() + ":" + context.region()
                + ":speed=" + context.speedShoesEnabled();
    }
}
