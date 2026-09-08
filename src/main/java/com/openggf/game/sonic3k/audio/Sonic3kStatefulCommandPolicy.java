package com.openggf.game.sonic3k.audio;

import com.openggf.audio.session.SmpsStatefulCommandOperation;
import com.openggf.audio.session.SmpsStatefulCommandPolicy;
import com.openggf.audio.session.SmpsFadeOutEffects;
import com.openggf.audio.session.SmpsWriteProgram;

/** Host-owned S3K stateful-command semantics, independent of donor content. */
public final class Sonic3kStatefulCommandPolicy
        implements SmpsStatefulCommandPolicy {
    public static final Sonic3kStatefulCommandPolicy INSTANCE =
            new Sonic3kStatefulCommandPolicy();

    private static final Identity IDENTITY = new Identity("sonic3k-e4-v1");
    private static final SmpsFadeOutEffects FADE_OUT = new SmpsFadeOutEffects(
            true, false, false, SmpsWriteProgram.SILENCE_ALL_PSG);

    private Sonic3kStatefulCommandPolicy() {
    }

    @Override
    public Identity identity() {
        return IDENTITY;
    }

    @Override
    public SmpsFadeOutEffects fadeOutEffects() {
        // zFadeOutMusic arms driver counters even without music, then falls
        // through zHaltDACPSG to zPSGSilenceAll (Z80 driver:2307-2325).
        return FADE_OUT;
    }

    @Override
    public boolean suppressesSfxDuringOverride() {
        // zUpdateMusic clears both SFX mailboxes while zFadeToPrevFlag is 29h;
        // zFadeInToPrevious clears the flag before the restoration fade starts.
        // Sound/Z80 Sound Driver.asm:662-679,2725-2727 (retail fix_sndbugs=0).
        return true;
    }

    @Override
    public boolean fadeOutCompletesWithGlobalStop() {
        // zDoMusicFadeOut jumps to zStopAllSound immediately after decrement
        // reaches zero, before the final volume walk (Z80 driver:2347-2362).
        return true;
    }

    @Override
    public SmpsStatefulCommandOperation prepare(
            SmpsStatefulCommandOperation.Input input) {
        if (!(input.command()
                instanceof com.openggf.audio.session.SmpsSessionCommand.StopSmpsSfx)) {
            return SmpsStatefulCommandOperation.none(input);
        }
        S3kE4StopSfxPlan plan = S3kE4StopSfxPlan.prepare(
                S3kE4Projection.capture(input.ownership()));
        return plan.accepted()
                ? SmpsStatefulCommandOperation.stopSmpsSfx(input,
                        new com.openggf.audio.session.SmpsWriteProgram(
                                plan.writes()))
                : SmpsStatefulCommandOperation.reject(input);
    }
}
