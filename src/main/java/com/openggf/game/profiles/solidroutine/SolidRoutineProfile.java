package com.openggf.game.profiles.solidroutine;

import com.openggf.level.objects.SolidObjectProvider;

import java.util.Objects;

public record SolidRoutineProfile(
        SolidRoutineKind kind,
        boolean topSolidOnly,
        boolean monitorSolidity,
        int monitorVerticalOffset,
        boolean inclusiveRightEdge,
        boolean stickyContactBuffer,
        boolean usesPlatformLandingSnap,
        boolean usesCollisionHalfWidthForTopLanding,
        boolean usesGroundHalfHeightForTopSolidContact,
        boolean bypassesOffscreenSolidGate,
        boolean allowsObjectControlledSolidContacts,
        boolean forceAirOnRideExit,
        boolean dropOnFloor,
        boolean carriesAirborneRiderAfterExitPlatform) {

    public SolidRoutineProfile {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Per-thread cache of built profiles. The solid path rebuilds a profile per
     * solid object per player per frame from provider reads that almost never
     * change; see {@link SolidRoutineProfileInterner} for why this is sound and
     * why it is thread-confined rather than shared.
     */
    private static final ThreadLocal<SolidRoutineProfileInterner<SolidRoutineProfile>> INTERNER =
            ThreadLocal.withInitial(SolidRoutineProfileInterner::new);

    public static SolidRoutineProfile fromProvider(SolidObjectProvider provider) {
        Objects.requireNonNull(provider, "provider");
        // The provider reads are cheap field accesses; it is the record
        // allocation behind them the cache exists to avoid, so the signature is
        // built from live reads on every call. A provider whose inputs genuinely
        // change therefore yields a different key and a correspondingly
        // different profile — this caches, it does not freeze.
        boolean topSolidOnly = provider.isTopSolidOnly();
        boolean monitorSolidity = provider.hasMonitorSolidity();
        int monitorVerticalOffset = provider.getMonitorSolidObjectVerticalOffset();
        boolean inclusiveRightEdge = provider.usesInclusiveRightEdge();
        boolean stickyContactBuffer = provider.usesStickyContactBuffer();
        boolean usesPlatformLandingSnap = provider.usesPlatformObjectLandingSnap();
        boolean usesCollisionHalfWidth = provider.usesCollisionHalfWidthForTopLanding();
        boolean usesGroundHalfHeight = provider.usesGroundHalfHeightForTopSolidContact();
        boolean bypassesOffscreenSolidGate = provider.bypassesOffscreenSolidGate();
        boolean allowsObjectControlled = provider.allowsObjectControlledSolidContacts();
        boolean forceAirOnRideExit = provider.forceAirOnRideExit();
        boolean dropOnFloor = provider.dropOnFloor();
        boolean carriesAirborneRider = provider.carriesAirborneRiderAfterExitPlatform();

        long signature = SolidRoutineProfileInterner.signature(
                topSolidOnly, monitorSolidity, monitorVerticalOffset,
                inclusiveRightEdge, stickyContactBuffer, usesPlatformLandingSnap,
                usesCollisionHalfWidth, usesGroundHalfHeight, bypassesOffscreenSolidGate,
                allowsObjectControlled, forceAirOnRideExit, dropOnFloor, carriesAirborneRider);
        SolidRoutineProfileInterner<SolidRoutineProfile> interner = INTERNER.get();
        SolidRoutineProfile cached = interner.get(signature);
        if (cached != null) {
            return cached;
        }
        SolidRoutineProfile built = new SolidRoutineProfile(
                kindFor(topSolidOnly, monitorSolidity),
                topSolidOnly,
                monitorSolidity,
                monitorVerticalOffset,
                inclusiveRightEdge,
                stickyContactBuffer,
                usesPlatformLandingSnap,
                usesCollisionHalfWidth,
                usesGroundHalfHeight,
                bypassesOffscreenSolidGate,
                allowsObjectControlled,
                forceAirOnRideExit,
                dropOnFloor,
                carriesAirborneRider);
        interner.put(signature, built);
        return built;
    }

    /** This profile's packed signature, so downstream views can share the key. */
    public long signature() {
        return SolidRoutineProfileInterner.signature(
                topSolidOnly, monitorSolidity, monitorVerticalOffset,
                inclusiveRightEdge, stickyContactBuffer, usesPlatformLandingSnap,
                usesCollisionHalfWidthForTopLanding, usesGroundHalfHeightForTopSolidContact,
                bypassesOffscreenSolidGate, allowsObjectControlledSolidContacts,
                forceAirOnRideExit, dropOnFloor, carriesAirborneRiderAfterExitPlatform);
    }

    public static SolidRoutineProfile fullSolid(boolean stickyContactBuffer) {
        // ROM full-solid X gates reject with bhi (S1 SolidObject.asm:122-123,
        // s2.asm:35344+, sonic3k.asm:41393-41399), so the right edge is
        // inclusive for the whole routine family.
        return fullSolid(stickyContactBuffer, true, false);
    }

    public static SolidRoutineProfile fullSolid(
            boolean stickyContactBuffer,
            boolean inclusiveRightEdge,
            boolean bypassesOffscreenSolidGate) {
        return interned(SolidRoutineKind.FULL_SOLID, false, false, 0,
                inclusiveRightEdge, stickyContactBuffer, true, false, false,
                bypassesOffscreenSolidGate, false, true, false, false);
    }

    public static SolidRoutineProfile topSolid(boolean stickyContactBuffer) {
        return interned(SolidRoutineKind.TOP_SOLID_ONLY, true, false, 0,
                false, stickyContactBuffer, true, false, false,
                false, false, true, false, false);
    }

    public static SolidRoutineProfile monitorSolid(int verticalOffset, boolean stickyContactBuffer) {
        return interned(SolidRoutineKind.MONITOR_SOLID, false, true, verticalOffset,
                false, stickyContactBuffer, true, false, false,
                false, false, true, false, false);
    }

    /**
     * Shared cache lookup for every factory on this record.
     *
     * <p>The named factories are called per frame by objects that override
     * {@code getSolidRoutineProfile()} with a constant shape, so they allocated
     * just as steadily as {@link #fromProvider} did.
     */
    private static SolidRoutineProfile interned(
            SolidRoutineKind kind,
            boolean topSolidOnly,
            boolean monitorSolidity,
            int monitorVerticalOffset,
            boolean inclusiveRightEdge,
            boolean stickyContactBuffer,
            boolean usesPlatformLandingSnap,
            boolean usesCollisionHalfWidthForTopLanding,
            boolean usesGroundHalfHeightForTopSolidContact,
            boolean bypassesOffscreenSolidGate,
            boolean allowsObjectControlledSolidContacts,
            boolean forceAirOnRideExit,
            boolean dropOnFloor,
            boolean carriesAirborneRiderAfterExitPlatform) {
        long signature = SolidRoutineProfileInterner.signature(
                topSolidOnly, monitorSolidity, monitorVerticalOffset,
                inclusiveRightEdge, stickyContactBuffer, usesPlatformLandingSnap,
                usesCollisionHalfWidthForTopLanding, usesGroundHalfHeightForTopSolidContact,
                bypassesOffscreenSolidGate, allowsObjectControlledSolidContacts,
                forceAirOnRideExit, dropOnFloor, carriesAirborneRiderAfterExitPlatform);
        SolidRoutineProfileInterner<SolidRoutineProfile> interner = INTERNER.get();
        SolidRoutineProfile cached = interner.get(signature);
        if (cached != null) {
            return cached;
        }
        SolidRoutineProfile built = new SolidRoutineProfile(
                kind, topSolidOnly, monitorSolidity, monitorVerticalOffset,
                inclusiveRightEdge, stickyContactBuffer, usesPlatformLandingSnap,
                usesCollisionHalfWidthForTopLanding, usesGroundHalfHeightForTopSolidContact,
                bypassesOffscreenSolidGate, allowsObjectControlledSolidContacts,
                forceAirOnRideExit, dropOnFloor, carriesAirborneRiderAfterExitPlatform);
        interner.put(signature, built);
        return built;
    }

    public static SolidRoutineAdapter adapt(SolidObjectProvider provider) {
        return new SolidRoutineAdapter(
                Objects.requireNonNull(provider, "provider"),
                fromProvider(provider));
    }

    private static SolidRoutineKind kindFor(boolean topSolidOnly, boolean monitorSolidity) {
        if (monitorSolidity) {
            return SolidRoutineKind.MONITOR_SOLID;
        }
        return topSolidOnly ? SolidRoutineKind.TOP_SOLID_ONLY : SolidRoutineKind.FULL_SOLID;
    }
}
