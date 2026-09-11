package com.openggf.level.objects;

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

    public static SolidRoutineProfile fromProvider(SolidObjectProvider provider) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SolidRoutineProfile.fromProvider(provider));
    }

    public static SolidRoutineProfile fullSolid(boolean stickyContactBuffer) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SolidRoutineProfile.fullSolid(stickyContactBuffer));
    }

    public static SolidRoutineProfile fullSolid(
            boolean stickyContactBuffer,
            boolean inclusiveRightEdge,
            boolean bypassesOffscreenSolidGate) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SolidRoutineProfile.fullSolid(
                stickyContactBuffer,
                inclusiveRightEdge,
                bypassesOffscreenSolidGate));
    }

    public static SolidRoutineProfile topSolid(boolean stickyContactBuffer) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SolidRoutineProfile.topSolid(stickyContactBuffer));
    }

    public static SolidRoutineProfile monitorSolid(int verticalOffset, boolean stickyContactBuffer) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SolidRoutineProfile.monitorSolid(
                verticalOffset,
                stickyContactBuffer));
    }

    public static SolidRoutineAdapter adapt(SolidObjectProvider provider) {
        return new SolidRoutineAdapter(
                Objects.requireNonNull(provider, "provider"),
                fromProvider(provider));
    }

    public com.openggf.game.profiles.solidroutine.SolidRoutineProfile toCanonical() {
        return new com.openggf.game.profiles.solidroutine.SolidRoutineProfile(
                kind.toCanonical(),
                topSolidOnly,
                monitorSolidity,
                monitorVerticalOffset,
                inclusiveRightEdge,
                stickyContactBuffer,
                usesPlatformLandingSnap,
                usesCollisionHalfWidthForTopLanding,
                usesGroundHalfHeightForTopSolidContact,
                bypassesOffscreenSolidGate,
                allowsObjectControlledSolidContacts,
                forceAirOnRideExit,
                dropOnFloor,
                carriesAirborneRiderAfterExitPlatform);
    }

    /**
     * Per-thread cache of converted profiles, keyed by the canonical profile's
     * own signature. Every factory on this class funnels through here, so one
     * cache covers them all.
     *
     * <p>This is the second of the two allocations the solid path used to make
     * per object per player per frame — {@code fromProvider} built a canonical
     * profile and then immediately converted it. Both are now cached.
     */
    private static final ThreadLocal<
            com.openggf.game.profiles.solidroutine.SolidRoutineProfileInterner<SolidRoutineProfile>>
            INTERNER = ThreadLocal.withInitial(
                    com.openggf.game.profiles.solidroutine.SolidRoutineProfileInterner::new);

    public static SolidRoutineProfile fromCanonical(
            com.openggf.game.profiles.solidroutine.SolidRoutineProfile canonical) {
        Objects.requireNonNull(canonical, "canonical");
        long signature = canonical.signature();
        var interner = INTERNER.get();
        SolidRoutineProfile cached = interner.get(signature);
        if (cached != null) {
            return cached;
        }
        SolidRoutineProfile built = buildFrom(canonical);
        interner.put(signature, built);
        return built;
    }

    private static SolidRoutineProfile buildFrom(
            com.openggf.game.profiles.solidroutine.SolidRoutineProfile canonical) {
        return new SolidRoutineProfile(
                SolidRoutineKind.fromCanonical(canonical.kind()),
                canonical.topSolidOnly(),
                canonical.monitorSolidity(),
                canonical.monitorVerticalOffset(),
                canonical.inclusiveRightEdge(),
                canonical.stickyContactBuffer(),
                canonical.usesPlatformLandingSnap(),
                canonical.usesCollisionHalfWidthForTopLanding(),
                canonical.usesGroundHalfHeightForTopSolidContact(),
                canonical.bypassesOffscreenSolidGate(),
                canonical.allowsObjectControlledSolidContacts(),
                canonical.forceAirOnRideExit(),
                canonical.dropOnFloor(),
                canonical.carriesAirborneRiderAfterExitPlatform());
    }
}
