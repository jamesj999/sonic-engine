package com.openggf.audio.presentation;

import java.util.Objects;

public record SmpsAssetKey(
        String gameId, Route route, int assetId, String assetName) {
    public enum Route {
        BASE_MUSIC,
        DONOR_MUSIC,
        BASE_ID,
        BASE_NAME,
        DONOR_ID,
        FALLBACK_NAME
    }

    public SmpsAssetKey {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(route, "route");
    }

    /** Transitional SFX-oriented name retained until command resolution migrates. */
    public int sfxId() {
        return assetId;
    }

    /** Transitional SFX-oriented name retained until command resolution migrates. */
    public String sfxName() {
        return assetName;
    }
}
