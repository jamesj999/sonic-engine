package com.openggf.level.objects;

import java.util.function.Supplier;

/**
 * Helper for non-{@link ObjectManager} call sites that still need object-style
 * construction context during {@code new X(...)}.
 */
public final class ObjectConstructionContext {

    private ObjectConstructionContext() {
    }

    private static final ThreadLocal<Boolean> REWIND_ACTIVE_RESTORE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PROBE_CONSTRUCTION = new ThreadLocal<>();

    public static <T> T construct(ObjectServices services, Supplier<T> factory) {
        return with(services, -1, factory);
    }

    public static <T> T with(ObjectServices services, int slot, Supplier<T> supplier) {
        ObjectServices previous = AbstractObjectInstance.currentConstructionContext();
        Integer previousSlot = AbstractObjectInstance.PRE_ALLOCATED_SLOT.get();
        setConstructionContext(services);
        if (slot >= 0) {
            AbstractObjectInstance.PRE_ALLOCATED_SLOT.set(slot);
        } else {
            AbstractObjectInstance.PRE_ALLOCATED_SLOT.remove();
        }
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                setConstructionContext(previous);
            } else {
                clearConstructionContext();
            }
            if (previousSlot != null) {
                AbstractObjectInstance.PRE_ALLOCATED_SLOT.set(previousSlot);
            } else {
                AbstractObjectInstance.PRE_ALLOCATED_SLOT.remove();
            }
        }
    }

    public static void with(ObjectServices services, Runnable action) {
        with(services, -1, action);
    }

    public static void with(ObjectServices services, int slot, Runnable action) {
        with(services, slot, () -> {
            action.run();
            return null;
        });
    }

    public static void setConstructionContext(ObjectServices services) {
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.set(services);
    }

    public static void clearConstructionContext() {
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
    }

    public static <T> T withRewindActiveRestore(Supplier<T> supplier) {
        Boolean previous = REWIND_ACTIVE_RESTORE.get();
        REWIND_ACTIVE_RESTORE.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                REWIND_ACTIVE_RESTORE.set(previous);
            } else {
                REWIND_ACTIVE_RESTORE.remove();
            }
        }
    }

    public static boolean isRewindActiveRestore() {
        return Boolean.TRUE.equals(REWIND_ACTIVE_RESTORE.get());
    }

    /**
     * Marks that {@code supplier} constructs a throwaway <em>probe</em> instance
     * ({@code ObjectRewindDynamicCodecs#invokeProbeCtor}) used only to obtain a live
     * receiver for calling a {@link RewindRecreatable#recreateForRewind} interface
     * method polymorphically -- never to be the actual restored object. A probe's
     * constructor is expected to run harmlessly (it is fed placeholder/zero args, e.g.
     * an {@code ObjectSpawn} with {@code objectId() == 0}), but a constructor with
     * unconditional side effects (e.g. an {@link com.openggf.level.objects.boss
     * .AbstractBossInstance} subclass whose {@code initializeBossState()}
     * unconditionally spawns child objects) does not know it is being probed and
     * would otherwise leak a real, live, wrongly-parented child into the object
     * manager every time its owner is reconstructed during a rewind restore. See
     * {@link #isProbeConstruction()}.
     */
    public static <T> T withProbeConstruction(Supplier<T> supplier) {
        Boolean previous = PROBE_CONSTRUCTION.get();
        PROBE_CONSTRUCTION.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                PROBE_CONSTRUCTION.set(previous);
            } else {
                PROBE_CONSTRUCTION.remove();
            }
        }
    }

    /**
     * True while a throwaway {@code RewindRecreatable} probe instance is under
     * construction (see {@link #withProbeConstruction}). {@code AbstractObjectInstance
     * #spawnChild}/{@code spawnFreeChild}/{@code spawnDynamicObject} consult this
     * FIRST (ahead of {@link #isRewindActiveRestore()}) and skip all registration when
     * true, so a probed constructor's child-spawning side effects are fully
     * suppressed instead of leaking a live or pooled stray object under the probe's
     * own (about-to-be-discarded) identity.
     */
    public static boolean isProbeConstruction() {
        return Boolean.TRUE.equals(PROBE_CONSTRUCTION.get());
    }

    static Integer consumePreAllocatedSlot() {
        Integer preSlot = AbstractObjectInstance.PRE_ALLOCATED_SLOT.get();
        if (preSlot != null) {
            AbstractObjectInstance.PRE_ALLOCATED_SLOT.remove();
        }
        return preSlot;
    }
}
