package com.openggf.game.sonic2.audio;

import com.openggf.audio.session.SmpsStatefulCommandPolicy;

/** Command side effects owned by the Sonic 2 Z80 sound driver. */
public final class Sonic2StatefulCommandPolicy implements SmpsStatefulCommandPolicy {
    public static final Sonic2StatefulCommandPolicy INSTANCE = new Sonic2StatefulCommandPolicy();
    private static final Identity IDENTITY = new Identity("sonic2-commands-v1");

    private Sonic2StatefulCommandPolicy() { }

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public boolean suppressesSfxDuringOverride() {
        // zPlaySound_CheckRing jumps to zKillSFXPrio while 1upPlaying or
        // FadeInFlag is set (s2.sounddriver.asm:2118-2120); zPlayMusic's
        // extra-life branch sets 1upPlaying (:1712) and cfFadeInToPrevious
        // clears it (:3155).
        return true;
    }

    @Override
    public boolean releasesSfxSuppressionAtRestore() {
        // cfFadeInToPrevious raises FadeInFlag in the same breath (:3151),
        // and zUpdateFadeIn clears it only once FadeInCounter is zero
        // (:2736-2740).
        return false;
    }

    @Override
    public boolean stopsSfxWhenOverrideStarts() {
        // zPlayMusic calls zStopSoundEffects for every song under the
        // shipped ~~FixDriverBugs build (:1667-1672); the request path
        // already issues that stop ahead of each music load.
        return false;
    }
}
