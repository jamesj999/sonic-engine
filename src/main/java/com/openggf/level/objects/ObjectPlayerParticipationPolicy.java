package com.openggf.level.objects;

/**
 * Declares which playable actors an object should consider.
 */
public enum ObjectPlayerParticipationPolicy {
    MAIN_ONLY_NATIVE,
    NATIVE_P1_P2,
    MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED,
    ALL_ENGINE_PLAYERS,
    NEAREST_ENGINE_PLAYER;

    public static ObjectPlayerParticipationPolicy nativePlayers(boolean includeNativeP2) {
        return includeNativeP2 ? NATIVE_P1_P2 : MAIN_ONLY_NATIVE;
    }

    public static ObjectPlayerParticipationPolicy engineSidekicksAsNativeP2(boolean includeSidekicks) {
        return includeSidekicks ? MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED : MAIN_ONLY_NATIVE;
    }
}
