package com.openggf.game.sonic1.audio;

import com.openggf.audio.session.SmpsFadeOutEffects;
import com.openggf.audio.session.SmpsStatefulCommandPolicy;
import com.openggf.audio.session.SmpsWriteProgram;

/** Command side effects owned by the Sonic 1 68000 sound driver. */
public final class Sonic1StatefulCommandPolicy implements SmpsStatefulCommandPolicy {
    public static final Sonic1StatefulCommandPolicy INSTANCE = new Sonic1StatefulCommandPolicy();
    private static final Identity IDENTITY = new Identity("sonic1-commands-v1");

    private Sonic1StatefulCommandPolicy() { }

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public boolean suppressesSfxDuringOverride() {
        // Sound_PlaySFX and Sound_PlaySpecial return through .clear_sndprio
        // while f_1up_playing is set (s1.sounddriver.asm:978-979, :1118-1119,
        // :1085-1086); Sound_PlayBGM's extra-life branch sets the flag
        // (:784) and only cfFadeInToPrevious clears it (:2222).
        return true;
    }

    @Override
    public boolean releasesSfxSuppressionAtRestore() {
        // cfFadeInToPrevious swaps the flag for f_fadein_flag (:2220), which
        // the same SFX entry tests (:982) and DoFadeIn clears only when the
        // fade-in counter has run down (:1650).
        return false;
    }

    @Override
    public boolean stopsSfxWhenOverrideStarts() {
        // The extra-life branch clears bit 7 on all six SFX tracks before
        // the RAM backup (:769-774). Ordinary song loads leave them alone.
        return true;
    }

    @Override
    public SmpsFadeOutEffects fadeOutEffects() {
        // FadeOutMusic (s1.sounddriver.asm:1360-1367) calls StopSFX and
        // StopSpecialSFX, arms the music fade, then clears f_speedup.
        // These instructions are common to the shipped FixBugs=0 path.
        return new SmpsFadeOutEffects(false, true, true, SmpsWriteProgram.EMPTY);
    }
}
