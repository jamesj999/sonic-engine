package com.openggf.audio;

import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TestAudioLogicalSnapshot {
    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new AudioTestFixtures.RecordingAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void capturesAudioManagerIntentWithoutBackendObjects() {
        audio.beginCommandTimelineFrame(42);
        audio.playSfx("JUMP");
        audio.playSfx(GameSound.RING);

        AudioLogicalSnapshot snapshot = audio.captureLogicalSnapshot();

        assertFalse(snapshot.ringLeft());
        assertEquals(42, snapshot.commandTimelineFrame());
        assertEquals(2, snapshot.commandTimelineNextOrder());
        assertEquals(2, snapshot.commandEntryCount());
        assertTrue(snapshot.donorBindings().isEmpty());
        assertTrue(snapshot.donorGameIds().isEmpty());
    }

    @Test
    void capturesDonorBindingsAsDescriptorsOnly() {
        AudioTestFixtures.StubSmpsLoader donor = new AudioTestFixtures.StubSmpsLoader();
        audio.registerDonorLoader("s3k", donor, AudioTestFixtures.EMPTY_DAC);
        audio.registerDonorSound(GameSound.SPINDASH_CHARGE, "s3k", 0xA4);

        AudioLogicalSnapshot snapshot = audio.captureLogicalSnapshot();

        assertEquals(Set.of("s3k"), snapshot.donorGameIds());
        assertEquals(Set.of(new AudioLogicalSnapshot.DonorSfxBindingSnapshot(
                GameSound.SPINDASH_CHARGE, "s3k", 0xA4)), snapshot.donorBindings());

        Set<String> bindingComponents = Arrays.stream(
                        AudioLogicalSnapshot.DonorSfxBindingSnapshot.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        assertFalse(bindingComponents.contains("loader"));
        assertFalse(bindingComponents.contains("dacdata"));
        assertFalse(bindingComponents.contains("config"));
    }

    @Test
    void sourceDescriptorsDoNotDependOnJavaObjectIdentity() {
        AudioSourceDescriptor baseMusic = AudioSourceDescriptor.baseMusic(0x81);
        AudioSourceDescriptor donorSfx = AudioSourceDescriptor.donorSfx("s3k", 0xA4);
        AudioSourceDescriptor namedSfx = AudioSourceDescriptor.baseNamedSfx("RING_LEFT");

        assertEquals(AudioSourceDescriptor.Route.BASE_MUSIC_ID, baseMusic.route());
        assertEquals(0x81, baseMusic.id());
        assertNull(baseMusic.name());
        assertNull(baseMusic.donorGameId());

        assertEquals(AudioSourceDescriptor.Route.DONOR_SFX_ID, donorSfx.route());
        assertEquals(0xA4, donorSfx.id());
        assertEquals("s3k", donorSfx.donorGameId());

        assertEquals(AudioSourceDescriptor.Route.BASE_SFX_NAME, namedSfx.route());
        assertEquals("RING_LEFT", namedSfx.name());
    }

    @Test
    void logicalSnapshotRecordExcludesOpenAlPresentationState() {
        Set<String> recordComponentNames = Arrays.stream(AudioLogicalSnapshot.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        Set<String> forbiddenFragments = Set.of(
                "openal", "source", "buffer", "device", "context", "native", "queued", "processed");

        for (String componentName : recordComponentNames) {
            for (String forbidden : forbiddenFragments) {
                assertFalse(componentName.contains(forbidden),
                        () -> "presentation field leaked into snapshot: " + componentName);
            }
        }

        assertEquals(EnumSet.allOf(AudioSourceDescriptor.Route.class), AudioSourceDescriptor.supportedRoutes());
    }

    @Test
    void audioManagerRecordsFallbackMusicDescriptorInUnifiedTimeline() {
        DescriptorRecordingBackend backend = new DescriptorRecordingBackend();
        audio.setBackend(backend);

        audio.playMusic(0x90);

        AudioCommand.PlayMusic command = (AudioCommand.PlayMusic)
                audio.commandTimeline().entryAt(0).command();
        assertEquals(AudioCommand.MusicRoute.FALLBACK_WAV, command.route());
        assertEquals(0x90, command.musicId());
    }

    @Test
    void audioManagerRecordsDonorMusicDescriptorInUnifiedTimeline() {
        DescriptorRecordingBackend backend = new DescriptorRecordingBackend();
        audio.setBackend(backend);
        AudioTestFixtures.StubSmpsLoader donor = new AudioTestFixtures.StubSmpsLoader();
        donor.musicResults.put(0x2A, new AudioTestFixtures.StubSmpsData("donor-music"));
        audio.registerDonorLoader(
                "s3k", donor, AudioTestFixtures.EMPTY_DAC,
                new SmpsSequencerConfig.Builder().build());

        audio.playDonorMusic("s3k", 0x2A);

        AudioCommand.PlayMusic command = (AudioCommand.PlayMusic)
                audio.commandTimeline().entryAt(0).command();
        assertEquals(AudioCommand.MusicRoute.DONOR_SMPS, command.route());
        assertEquals("s3k", command.donorGameId());
    }

    @Test
    void restoreLogicalSnapshotRestoresManagerCursorAndRingAlternation() {
        audio.beginCommandTimelineFrame(12);
        audio.playSfx(GameSound.RING);
        AudioLogicalSnapshot snapshot = audio.captureLogicalSnapshot();

        audio.beginCommandTimelineFrame(20);
        audio.resetRingSound();

        audio.restoreLogicalSnapshot(snapshot);

        AudioLogicalSnapshot restored = audio.captureLogicalSnapshot();
        assertFalse(restored.ringLeft());
        assertEquals(12, restored.commandTimelineFrame());
        assertEquals(1, restored.commandTimelineNextOrder());
        assertEquals(snapshot.presentation(), restored.presentation(),
                "the presentation snapshot is the only restored audio state");
    }

    private static final class DescriptorRecordingBackend extends NullAudioBackend {
        private AudioSourceDescriptor currentMusic;

        @Override
        public void prepareLogicalMusicSource(AudioSourceDescriptor descriptor) {
            currentMusic = descriptor;
        }

        @Override
        public void playMusic(int musicId) {
        }

        @Override
        public void playSmps(AbstractSmpsData data, DacData dacData,
                             SmpsSequencerConfig config,
                             boolean forceOverride) {
        }

        AudioSourceDescriptor currentMusic() {
            return currentMusic;
        }
    }
}
