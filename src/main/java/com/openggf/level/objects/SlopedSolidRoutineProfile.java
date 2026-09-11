package com.openggf.level.objects;

import java.util.Objects;

public record SlopedSolidRoutineProfile(
        boolean usesSlopeForNewLanding,
        boolean usesGroundedStandingCatchWindow,
        boolean addsSlopeCatchRangeToVerticalOverlap,
        int slopeBaseline) {

    public static SlopedSolidRoutineProfile fromProvider(SlopedSolidProvider provider) {
        return fromCanonical(com.openggf.game.profiles.solidroutine.SlopedSolidRoutineProfile.fromProvider(provider));
    }

    public static SlopedSolidRoutineAdapter adapt(SlopedSolidProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new SlopedSolidRoutineAdapter(
                provider,
                Objects.requireNonNull(provider.getSlopedSolidRoutineProfile(), "slopedSolidRoutineProfile"));
    }

    public com.openggf.game.profiles.solidroutine.SlopedSolidRoutineProfile toCanonical() {
        return new com.openggf.game.profiles.solidroutine.SlopedSolidRoutineProfile(
                usesSlopeForNewLanding,
                usesGroundedStandingCatchWindow,
                addsSlopeCatchRangeToVerticalOverlap,
                slopeBaseline);
    }

    public static SlopedSolidRoutineProfile fromCanonical(
            com.openggf.game.profiles.solidroutine.SlopedSolidRoutineProfile canonical) {
        Objects.requireNonNull(canonical, "canonical");
        return new SlopedSolidRoutineProfile(
                canonical.usesSlopeForNewLanding(),
                canonical.usesGroundedStandingCatchWindow(),
                canonical.addsSlopeCatchRangeToVerticalOverlap(),
                canonical.slopeBaseline());
    }
}
