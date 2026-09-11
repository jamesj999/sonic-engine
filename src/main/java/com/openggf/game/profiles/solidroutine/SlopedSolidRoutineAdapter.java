package com.openggf.game.profiles.solidroutine;

import com.openggf.level.objects.SlopedSolidProvider;

public record SlopedSolidRoutineAdapter(SlopedSolidProvider provider, SlopedSolidRoutineProfile profile) {
    public byte[] getSlopeData() {
        return provider.getSlopeData();
    }

    public boolean isSlopeFlipped() {
        return provider.isSlopeFlipped();
    }
}
