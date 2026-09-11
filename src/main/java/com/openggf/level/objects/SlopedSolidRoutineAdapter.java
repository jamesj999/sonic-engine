package com.openggf.level.objects;

public record SlopedSolidRoutineAdapter(SlopedSolidProvider provider, SlopedSolidRoutineProfile profile) {
    public byte[] getSlopeData() {
        return provider.getSlopeData();
    }

    public boolean isSlopeFlipped() {
        return provider.isSlopeFlipped();
    }
}
