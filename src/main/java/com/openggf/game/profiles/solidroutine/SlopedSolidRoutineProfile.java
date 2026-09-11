package com.openggf.game.profiles.solidroutine;

import com.openggf.level.objects.SlopedSolidProvider;

import java.util.Objects;

public record SlopedSolidRoutineProfile(
        boolean usesSlopeForNewLanding,
        boolean usesGroundedStandingCatchWindow,
        boolean addsSlopeCatchRangeToVerticalOverlap,
        int slopeBaseline) {

    public static SlopedSolidRoutineProfile fromProvider(SlopedSolidProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new SlopedSolidRoutineProfile(
                provider.usesSlopeForNewLanding(),
                provider.usesGroundedStandingCatchWindow(),
                provider.addsSlopeCatchRangeToVerticalOverlap(),
                provider.getSlopeBaseline());
    }

    public static SlopedSolidRoutineAdapter adapt(SlopedSolidProvider provider) {
        return new SlopedSolidRoutineAdapter(
                Objects.requireNonNull(provider, "provider"),
                fromProvider(provider));
    }
}
