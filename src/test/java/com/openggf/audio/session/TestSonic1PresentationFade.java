package com.openggf.audio.session;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationSessionCommandApplier;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.AudioPresentationDependencyResolver;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerTestAccess;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the live presentation command, without the parity host's fade repairs. */
class TestSonic1PresentationFade {
    @Test
    void fadeStopsNormalAndSpecialSfxBeforeMusicFades() {
        try (SmpsDriverSession session = session()) {
            addSequencer(session, false, false, 0);
            addSequencer(session, true, false, 2);
            addSequencer(session, true, true, 4);
            assertEquals(3, session.captureLogicalSnapshot().sequencers().size());

            fade(session);

            // FadeOutMusic calls StopSFX and StopSpecialSFX (s1.sounddriver.asm:1360-1367).
            var remaining = session.captureLogicalSnapshot().sequencers();
            assertEquals(1, remaining.size());
            assertFalse(remaining.getFirst().sfx());
            assertTrue(remaining.getFirst().snapshot().tracks().getFirst().active());
        }
    }

    @Test
    void fadeClearsSpeedShoesInBothSessionAndMusic() {
        try (SmpsDriverSession session = session()) {
            addSequencer(session, false, false, 0);
            session.applyCommand(new SmpsSessionCommand.SetSpeedShoes(true));
            assertTrue(session.captureLogicalSnapshot().sequencers().getFirst().snapshot().speedShoes());

            fade(session);

            // FadeOutMusic clears f_speedup, including the retained host control.
            assertFalse(session.captureSnapshot().speedShoesEnabled());
            assertFalse(session.captureLogicalSnapshot().sequencers().getFirst().snapshot().speedShoes());
        }
    }

    @Test
    void fadeKeepsPresentationSpeedMetadataCoherentWithTheSession() {
        try (SmpsDriverSession session = session()) {
            addSequencer(session, false, false, 0);
            var registry = new AudioVoiceRegistry((source, driver) -> null,
                    new AudioPresentationDependencyResolver() {
                        @Override
                        public com.openggf.audio.presentation.DecodedPcm resolvePcm(
                                String key) {
                            throw new AssertionError("fade must not resolve PCM");
                        }
                    }, new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                    ignored -> { }, session);
            AudioPresentationSessionCommandApplier.apply(session, registry,
                    new AudioPresentationCommand.SetSpeedShoes(true));
            assertTrue(registry.snapshot().speedShoesEnabled());

            AudioPresentationSessionCommandApplier.apply(session, registry,
                    new AudioPresentationCommand.FadeMusic(0x28, 3));

            assertFalse(registry.snapshot().speedShoesEnabled());
        }
    }

    private static void fade(SmpsDriverSession session) {
        AudioPresentationSessionCommandApplier.apply(session, new AudioVoiceRegistry(),
                new AudioPresentationCommand.FadeMusic(0x28, 3));
    }

    private static SmpsDriverSession session() {
        var profile = new Sonic1AudioProfile();
        var settings = new SmpsPhysicalDevice.Settings(44100, false);
        var policy = profile.smpsPhysicalPolicy();
        var commands = profile.smpsStatefulCommandPolicy();
        var session = new SmpsDriverSession(settings, policy, ChipWriteObserver.NONE,
                new SmpsSessionProfileFingerprint("s1", 0, policy.identity(), settings, commands.identity()),
                new SmpsDriverSessionConfiguration(commands));
        session.install();
        return session;
    }

    private static void addSequencer(SmpsDriverSession session, boolean sfx,
            boolean special, int channel) {
        var driver = session.logicalDriverForTesting();
        var data = new AudioTestFixtures.StubSmpsData("fade-" + channel);
        data.setId(0x81 + channel);
        var sequencer = new SmpsSequencer(data,
                AudioTestFixtures.EMPTY_DAC, driver, () -> { },
                new Sonic1AudioProfile().getSequencerConfig());
        SmpsSequencerTestAccess.addActiveFmTrack(sequencer, channel);
        sequencer.setSfxMode(sfx);
        sequencer.setSpecialSfx(special);
        driver.addSequencer(sequencer, sfx);
    }
}
