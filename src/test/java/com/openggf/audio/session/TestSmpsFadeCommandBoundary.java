package com.openggf.audio.session;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kStatefulCommandPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSmpsFadeCommandBoundary {
    @Test
    void songlessFadeCompletionStopsSfxAndClearsRetainedControls() {
        assertTerminalFade(false);
    }

    @Test
    void musicFadeCompletionStopsAllSoundOnTheTerminalStep() {
        assertTerminalFade(true);
    }

    private static void assertTerminalFade(boolean withMusic) {
        List<SmpsChipWrite> writes = new ArrayList<>();
        var observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(new SmpsChipWrite.Ym2612(port, register, value));
            }
            @Override public void onPsgWrite(int value) {
                writes.add(new SmpsChipWrite.Psg(value));
            }
        };
        var settings = SmpsSessionTestFixtures.settings();
        var policy = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        var commands = Sonic3kStatefulCommandPolicy.INSTANCE;
        try (var session = new SmpsDriverSession(settings, policy, observer,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings, commands.identity()),
                new SmpsDriverSessionConfiguration(commands))) {
            session.install();
            if (withMusic) {
                addSustainedTrack(session, false, 0);
            }
            addSustainedTrack(session, true, 2);
            session.serviceForward();
            session.applyCommand(new SmpsSessionCommand.FadeMusic(1, 1));
            if (withMusic) {
                // An out-of-service fade command does not step in its arming service.
                assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
                assertEquals(1, session.captureLogicalSnapshot().fadeOutTimeout());
            }
            session.applyCommand(new SmpsSessionCommand.SetSpeedShoes(true));
            session.applyCommand(new SmpsSessionCommand.SetSpeedMultiplier(2));

            writes.clear();
            assertEquals(SmpsServiceOutcome.GLOBAL_STOP_CONSUMED, session.serviceForward());
            List<SmpsChipWrite> expectedWrites = new ArrayList<>();
            if (withMusic) {
                // zUpdateSFXTracks precedes zDoMusicFadeOut: the sustained
                // FM3 SFX writes its frequency before the terminal stop.
                expectedWrites.add(new SmpsChipWrite.Ym2612(0, 0xa6, 0));
                expectedWrites.add(new SmpsChipWrite.Ym2612(0, 0xa2, 0));
            }
            expectedWrites.addAll(policy.stopAll().writes());
            assertEquals(expectedWrites, writes,
                    "terminal fade must invoke stop-all without an extra TL or note update");
            assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
            assertFalse(session.captureSnapshot().speedShoesEnabled());
            assertEquals(1, session.captureSnapshot().speedMultiplier());
            assertEquals(0, session.captureLogicalSnapshot().fadeOutTimeout());
            assertTrue(session.capturePhysicalSnapshotForTesting().outputSilenced());
            assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
        }
    }

    @Test
    void directReadMusicFadeUsesTheSameTerminalStop() {
        var settings = SmpsSessionTestFixtures.settings();
        var policy = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        var commands = Sonic3kStatefulCommandPolicy.INSTANCE;
        try (var session = new SmpsDriverSession(settings, policy, ChipWriteObserver.NONE,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings, commands.identity()),
                new SmpsDriverSessionConfiguration(commands))) {
            session.install();
            addSustainedTrack(session, false, 0);
            addSustainedTrack(session, true, 2);
            session.applyCommand(new SmpsSessionCommand.FadeMusic(1, 1));

            session.readDirect(new short[4410], 4410);

            assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
            assertTrue(session.capturePhysicalSnapshotForTesting().outputSilenced());
        }
    }

    @Test
    void terminalStopPrecedesAndPreservesTheLaterRequestPhase() {
        var settings = SmpsSessionTestFixtures.settings();
        var policy = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        var commands = Sonic3kStatefulCommandPolicy.INSTANCE;
        try (var session = new SmpsDriverSession(settings, policy, ChipWriteObserver.NONE,
                new SmpsSessionProfileFingerprint("s3k", 0, policy.identity(), settings, commands.identity()),
                new SmpsDriverSessionConfiguration(commands))) {
            session.install();
            addSustainedTrack(session, true, 2);
            session.applyCommand(new SmpsSessionCommand.FadeMusic(1, 1));
            var driver = session.logicalDriverForTesting();
            driver.submitServiceRequest(() -> {
                assertTrue(driver.captureSnapshot().sequencers().isEmpty(),
                        "the expired fade stops old slots before the queue is dispatched");
                addSustainedTrack(session, true, 4);
            });

            assertEquals(SmpsServiceOutcome.GLOBAL_STOP_CONSUMED, session.serviceForward());

            assertEquals(1, session.captureLogicalSnapshot().sequencers().size());
            assertEquals(4, session.captureLogicalSnapshot().sequencers().getFirst()
                    .snapshot().tracks().getFirst().channelId());
        }
    }

    @Test
    void donorDriverFadeRetainsLocalNoteOffWhenHostDoesNotOwnGlobalStop() {
        var profile = new com.openggf.game.sonic1.audio.Sonic1AudioProfile();
        var settings = SmpsSessionTestFixtures.settings();
        var policy = profile.smpsPhysicalPolicy();
        var commands = profile.smpsStatefulCommandPolicy();
        List<Integer> keys = new ArrayList<>();
        var observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && register == 0x28) {
                    keys.add(value);
                }
            }
            @Override public void onPsgWrite(int value) { }
        };
        try (var session = new SmpsDriverSession(settings, policy, observer,
                new SmpsSessionProfileFingerprint("s1", 0, policy.identity(), settings, commands.identity()),
                new SmpsDriverSessionConfiguration(commands))) {
            session.install();
            // S3K donor content under the S1 host must keep its existing local
            // fade tail; only a host that owns global stop may suppress it.
            addSustainedTrack(session, false, 0);
            session.serviceForward();
            session.applyCommand(new SmpsSessionCommand.FadeMusic(1, 1));
            session.serviceForward();
            keys.clear();

            assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
            assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());

            assertTrue(keys.contains(0), "a declined host stop must not lose the source-local key off");
        }
    }

    private static void addSustainedTrack(SmpsDriverSession session, boolean sfx, int channel) {
        var data = new com.openggf.audio.AudioTestFixtures.StubSmpsData("terminal-" + channel);
        data.setId(0x81 + channel);
        var sequencer = new com.openggf.audio.smps.SmpsSequencer(data,
                com.openggf.audio.AudioTestFixtures.EMPTY_DAC, session.logicalDriverForTesting(),
                () -> { }, new com.openggf.game.sonic3k.audio.Sonic3kAudioProfile().getSequencerConfig());
        var track = com.openggf.audio.smps.SmpsSequencerTestAccess.addActiveFmTrack(sequencer, channel);
        track.duration = 1000;
        sequencer.setSfxMode(sfx);
        session.logicalDriverForTesting().addSequencer(sequencer, sfx);
    }

    @Test
    void hostFadeArmsCountersAndSilencesPsgEvenWithoutMusic() {
        List<Integer> writes = new ArrayList<>();
        ChipWriteObserver observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { writes.add(value); }
        };
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream("fade-test", 0,
                SmpsSessionTestFixtures.settings(), Sonic3kSmpsPhysicalPolicy.INSTANCE, observer,
                new SmpsDriverSessionConfiguration(Sonic3kStatefulCommandPolicy.INSTANCE))) {
            assertTrue(stream.logicalDriver().captureSnapshot().sequencers().isEmpty());
            writes.clear();
            stream.fadeOutMusic(0x28, 6);
            var state = stream.logicalDriver().captureSnapshot();
            assertEquals(0x28, state.fadeOutTimeout());
            assertEquals(6, state.fadeDelay());
            assertEquals(6, state.fadeDelayTimeout());
            assertTrue(state.driverOwnedFade());
            assertEquals(List.of(0x9f, 0xbf, 0xdf, 0xff), writes);
        }
    }
}
