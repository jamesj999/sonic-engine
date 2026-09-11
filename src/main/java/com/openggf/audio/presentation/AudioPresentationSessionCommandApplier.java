package com.openggf.audio.presentation;

import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsSessionCommand;

import java.util.Objects;

/**
 * Applies one resolved presentation command to the session-owned SMPS state
 * and its driverless registry metadata.
 *
 * <p>The caller owns the composite rollback boundary. Keeping this bridge
 * shared prevents held-rewind staging from replaying only registry metadata
 * while the authoritative logical snapshot still comes from the session.
 */
public final class AudioPresentationSessionCommandApplier {
    private AudioPresentationSessionCommandApplier() {
    }

    public static void apply(
            SmpsDriverSession session,
            AudioVoiceRegistry registry,
            AudioPresentationCommand command) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(command, "command");

        if (command instanceof AudioPresentationCommand.ReplaceMusic replace) {
            if (replace.music().voiceDescriptor()
                    instanceof AudioPresentationCommand.SmpsVoiceDescriptor smps
                    && smps.activation() != null) {
                session.queueActivation(smps.activation());
            } else {
                session.applyCommand(new SmpsSessionCommand.StopMusic());
            }
        } else if (command
                instanceof AudioPresentationCommand.PushMusicOverride push) {
            boolean replacesDifferentForeground =
                    registry.hasActiveMusicMetadata()
                            && registry.activeMusicMetadataId()
                            != push.music().musicId();
            if (push.music().voiceDescriptor()
                    instanceof AudioPresentationCommand.SmpsVoiceDescriptor smps
                    && smps.activation() != null) {
                if (replacesDifferentForeground
                        && !registry.activeMusicMetadataUsesSession()) {
                    session.applyCommand(
                            new SmpsSessionCommand.SuspendForPcmOverride());
                }
                if (session.stopsSfxWhenOverrideStarts()) {
                    // S1's extra-life branch stops every SFX track before it
                    // backs the driver up (s1.sounddriver.asm:769-774).
                    session.applyCommand(new SmpsSessionCommand.StopAllSfx());
                }
                session.applyCommand(
                        new SmpsSessionCommand.PushOverride(
                                smps.activation()));
            } else if (replacesDifferentForeground) {
                session.applyCommand(
                        new SmpsSessionCommand.SuspendForPcmOverride());
            } else {
                session.applyCommand(new SmpsSessionCommand.StopMusic());
            }
        } else if (command
                instanceof AudioPresentationCommand.RestoreMusicOverride) {
            session.applyCommand(
                    new SmpsSessionCommand.RestoreOverride());
        } else if (command
                instanceof AudioPresentationCommand.EndMusicOverride end) {
            if (registry.activeMusicMetadataId() == end.musicId()) {
                session.applyCommand(
                        new SmpsSessionCommand.RestoreOverride());
            } else {
                session.applyCommand(
                        new SmpsSessionCommand.EndOverride(end.musicId()));
            }
        } else if (command instanceof AudioPresentationCommand.AddSmpsSfx add) {
            var program = registry.prepareSessionSfx(add);
            if (program != null) {
                session.applyCommand(
                        new SmpsSessionCommand.AdmitSfx(program));
            }
        } else if (command instanceof AudioPresentationCommand.StopMusic) {
            session.applyCommand(new SmpsSessionCommand.StopMusic());
        } else if (command instanceof AudioPresentationCommand.StopAllSfx) {
            session.applyCommand(new SmpsSessionCommand.StopAllSfx());
        } else if (command instanceof AudioPresentationCommand.StopSmpsSfx) {
            session.applyCommand(new SmpsSessionCommand.StopSmpsSfx());
        } else if (command instanceof AudioPresentationCommand.SilencePsg) {
            session.applyCommand(new SmpsSessionCommand.SilencePsg());
        } else if (command
                instanceof AudioPresentationCommand.RetainGlobalStop) {
            session.retainGlobalStop();
        } else if (command instanceof AudioPresentationCommand
                .StopRawPcmAndRetainGlobalStop) {
            session.retainGlobalStop();
        } else if (command instanceof AudioPresentationCommand.FadeMusic fade) {
            session.applyCommand(new SmpsSessionCommand.FadeMusic(
                    fade.steps(), fade.delay()));
        } else if (command
                instanceof AudioPresentationCommand.SetSpeedMultiplier speed) {
            session.applyCommand(
                    new SmpsSessionCommand.SetSpeedMultiplier(
                            speed.multiplier()));
        } else if (command
                instanceof AudioPresentationCommand.SetSpeedShoes speed) {
            session.applyCommand(
                    new SmpsSessionCommand.SetSpeedShoes(speed.enabled()));
        } else if (command
                instanceof AudioPresentationCommand.ChangeMusicTempo tempo) {
            session.applyCommand(
                    new SmpsSessionCommand.ChangeMusicTempo(
                            tempo.dividingTiming()));
        } else if (command
                instanceof AudioPresentationCommand.ResetRingAlternation ring) {
            session.applyCommand(
                    new SmpsSessionCommand.ResetRingAlternation(
                            ring.ringLeft()));
        } else if (command instanceof AudioPresentationCommand.HardReset) {
            session.applyCommand(new SmpsSessionCommand.HardReset());
        }
        registry.apply(command);
    }
}
