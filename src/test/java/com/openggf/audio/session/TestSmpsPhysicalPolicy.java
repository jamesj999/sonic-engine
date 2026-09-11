package com.openggf.audio.session;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSequencerTestAccess;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1SmpsCompatibilityPolicy;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2SmpsCompatibilityPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSmpsPhysicalPolicy {
    @Test
    void s2LoadDispositionAndClosingNoteOffsFollowLiteralRomSlotOrder() {
        var policy = Sonic2SmpsCompatibilityPolicy.INSTANCE;
        var source = SmpsSourceDescriptor.baseMusic(new AudioTestFixtures.StubSmpsData("load"));
        // DAC + FM1 + FM2 and PSG1: uninitialised slots retain VoiceControl=0.
        assertEquals(List.of(
                new SmpsChipWrite.Ym2612(0, 0x28, 6),
                new SmpsChipWrite.Ym2612(1, 0x42, 0xFF),
                new SmpsChipWrite.Ym2612(1, 0x46, 0xFF),
                new SmpsChipWrite.Ym2612(1, 0x4A, 0xFF),
                new SmpsChipWrite.Ym2612(1, 0x4E, 0xFF),
                new SmpsChipWrite.Ym2612(1, 0xB6, 0xC0),
                new SmpsChipWrite.Ym2612(0, 0x2B, 0x80),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 1),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Psg(0x9F),
                new SmpsChipWrite.Psg(0x1F),
                new SmpsChipWrite.Psg(0x1F)),
                policy.activateMusic(new SmpsMusicActivation(source, 3, 1)).writes());
        assertEquals(List.of(
                new SmpsChipWrite.Ym2612(0, 0x2B, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 0),
                new SmpsChipWrite.Ym2612(0, 0x28, 1),
                new SmpsChipWrite.Ym2612(0, 0x28, 2),
                new SmpsChipWrite.Ym2612(0, 0x28, 4),
                new SmpsChipWrite.Ym2612(0, 0x28, 5),
                new SmpsChipWrite.Ym2612(0, 0x28, 6),
                new SmpsChipWrite.Psg(0x9F),
                new SmpsChipWrite.Psg(0xBF),
                new SmpsChipWrite.Psg(0xDF)),
                policy.activateMusic(new SmpsMusicActivation(source, 7, 3)).writes());
    }

    private static final String FIXTURE =
            "audio/parity/s3k/s3k-stop-all-write-program.v1.json";

    @Test
    void s3kBootAndStopProgramsMatchShippedOrder() {
        List<SmpsChipWrite> expectedStop84 =
                ExactWriteProgramFixture.load(FIXTURE);
        Sonic3kSmpsPhysicalPolicy policy =
                Sonic3kSmpsPhysicalPolicy.INSTANCE;

        assertEquals(84, expectedStop84.size());
        assertEquals(new SmpsChipWrite.Ym2612(1, 0x82, 0xFF),
                expectedStop84.getFirst());
        assertEquals(new SmpsChipWrite.Ym2612(0, 0x27, 0x00),
                expectedStop84.getLast());
        assertEquals(expectedStop84, policy.stopAll().writes());
        // zInitAudioDriver's last write is zStopAllSound's 27h; it then jumps
        // into zPlayDigitalAudio and never returns (Sound/Z80 Sound
        // Driver.asm:550-551), so that loop's 2Bh is a separate program.
        assertEquals(84, policy.boot().writes().size());
        assertEquals(expectedStop84, policy.boot().writes());
        assertEquals(List.of(new SmpsChipWrite.Ym2612(0, 0x2B, 0x00)),
                policy.enterDacIdleLoop().writes());
        assertFalse(policy.boot().writes().stream().anyMatch(write ->
                write instanceof SmpsChipWrite.Ym2612 ym
                        && ym.register() == 0x2A));
    }

    @Test
    void s3kStopKeepsSourceChannelAndTailOrder() {
        List<SmpsChipWrite> writes =
                ExactWriteProgramFixture.load(FIXTURE);

        assertEquals(List.of(6, 0, 1, 2, 4, 5), writes.stream()
                .filter(write -> write instanceof SmpsChipWrite.Ym2612 ym
                        && ym.port() == 0 && ym.register() == 0x28)
                .map(write -> ((SmpsChipWrite.Ym2612) write).value())
                .toList());
        assertEquals(List.of(
                        new SmpsChipWrite.Psg(0x9F),
                        new SmpsChipWrite.Psg(0xBF),
                        new SmpsChipWrite.Psg(0xDF),
                        new SmpsChipWrite.Psg(0xFF),
                        new SmpsChipWrite.Ym2612(0, 0x2B, 0),
                        new SmpsChipWrite.Ym2612(0, 0x27, 0)),
                writes.subList(78, 84));
    }

    @Test
    void compatibilityPoliciesRetainExactLegacy202Writes() {
        SmpsWriteProgram legacy =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE.stopAll();

        assertEquals(202, legacy.writes().size());
        assertEquals(legacy,
                Sonic1SmpsCompatibilityPolicy.INSTANCE.stopAll());
        assertEquals(legacy,
                Sonic2SmpsCompatibilityPolicy.INSTANCE.stopAll());
        assertEquals(legacy,
                Sonic1SmpsCompatibilityPolicy.INSTANCE.boot());
        assertEquals(legacy,
                Sonic2SmpsCompatibilityPolicy.INSTANCE.boot());
        // S1 and S2 have no zPlayDigitalAudio-style entry block modelled here,
        // so the S3K boundary correction leaves their physical stream alone.
        assertEquals(List.of(),
                Sonic1SmpsCompatibilityPolicy.INSTANCE.enterDacIdleLoop().writes());
        assertEquals(List.of(),
                Sonic2SmpsCompatibilityPolicy.INSTANCE.enterDacIdleLoop().writes());
    }

    static Stream<Arguments> hostDonorPairings() {
        return Stream.of(
                new Sonic1AudioProfile(),
                new Sonic2AudioProfile(),
                new Sonic3kAudioProfile()).flatMap(host ->
                Stream.of(
                        new Sonic1AudioProfile(),
                        new Sonic2AudioProfile(),
                        new Sonic3kAudioProfile()).map(donor ->
                        Arguments.of(host, donor)));
    }

    @ParameterizedTest(name = "host={0}, donor={1}")
    @MethodSource("hostDonorPairings")
    void baseHostOwnsExactPhysicalPolicyAcrossEveryDonorPairing(
            GameAudioProfile hostProfile,
            GameAudioProfile donorProfile) {
        SmpsPhysicalPolicy host = hostProfile.smpsPhysicalPolicy();
        SmpsPhysicalPolicy donor = donorProfile.smpsPhysicalPolicy();
        SmpsStatefulCommandPolicy hostStateful =
                hostProfile.smpsStatefulCommandPolicy();
        SmpsPhysicalDevice.Settings settings =
                SmpsSessionTestFixtures.settings();
        SmpsSessionProfileFingerprint fingerprint =
                new SmpsSessionProfileFingerprint(
                        hostProfile.presentationGameId(), 19,
                        host.identity(), settings, hostStateful.identity());
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = new SmpsDriverSession(
                settings, host, observer, fingerprint,
                new SmpsDriverSessionConfiguration(hostStateful));

        session.install();
        SmpsDriver logical = session.logicalDriverForTesting();
        Object physical = session.physicalIdentityForTesting();
        session.queueActivation(donorMusic(donorProfile));
        session.applyCommand(new SmpsSessionCommand.AdmitSfx(
                donorSfx(donorProfile)));
        SmpsDriverSnapshot donorState = session.captureLogicalSnapshot();

        assertEquals(List.of(
                        SmpsSourceDescriptor.Kind.DONOR_MUSIC,
                        SmpsSourceDescriptor.Kind.DONOR_SFX_ID),
                donorState.sequencers().stream()
                        .map(entry -> entry.source().kind()).toList());
        assertEquals(List.of(
                        donorProfile.presentationGameId(),
                        donorProfile.presentationGameId()),
                donorState.sequencers().stream()
                        .map(entry -> entry.source().donorGameId()).toList());
        observer.clear();
        session.retainGlobalStop();
        session.serviceForward();

        assertEquals(host.stopAll().writes().size(),
                observer.events().size());
        assertEquals(host.stopAll().writes().stream()
                        .map(TestSmpsPhysicalPolicy::event)
                        .toList(),
                observer.events());
        assertEquals(host.identity(),
                session.captureSnapshot().profile().physicalPolicyId(),
                "donor " + donor.identity().value()
                        + " must not replace the host policy");
        assertEquals(hostStateful.identity(),
                session.captureSnapshot().profile().statefulCommandPolicyId(),
                "donor " + donorProfile.presentationGameId()
                        + " must not replace the host stateful-command policy");
        assertEquals(host.identity().equals(
                        Sonic3kSmpsPhysicalPolicy.INSTANCE.identity())
                        ? 84 : 202,
                observer.events().size());
        assertSame(physical, session.physicalIdentityForTesting(),
                "donor selection must not replace the host device");
        assertSame(logical, session.logicalDriverForTesting(),
                "donor routes must not replace the host logical driver");
    }

    private static PreparedSmpsMusicActivation donorMusic(
            GameAudioProfile donor) {
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData("donor-music");
        data.setId(0x21);
        SmpsSourceDescriptor source = donorSource(
                SmpsSourceDescriptor.Kind.DONOR_MUSIC, 0x21,
                donor.presentationGameId(), data);
        SmpsSequencerConfig config = donor.getSequencerConfig();
        SmpsDriver detached = new SmpsDriver();
        SmpsSequencer sequencer = new SmpsSequencer(
                data, SmpsSessionTestFixtures.dac(), detached, detached,
                AudioManager.getInstance(), config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        SmpsSequencerTestAccess.addActiveFmTrack(sequencer, 0);
        SmpsDriverSnapshot.SequencerEntry incoming =
                new SmpsDriverSnapshot.SequencerEntry(
                        false, source,
                        SmpsSequencer.SourceDescriptorTrust
                                .PRECOMPUTED_IMMUTABLE,
                        null, data, SmpsSessionTestFixtures.dac(),
                        AudioManager.getInstance(), config,
                        sequencer.captureSnapshot());
        return new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(source, 0), incoming,
                SmpsLogicalTransitionPolicies.forConfig(config),
                new SmpsDacSelection(source,
                        SmpsSessionTestFixtures.dac()));
    }

    private static PreparedSmpsSfxProgram donorSfx(
            GameAudioProfile donor) {
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData("donor-sfx");
        data.setId(0xA4);
        SmpsSourceDescriptor source = donorSource(
                SmpsSourceDescriptor.Kind.DONOR_SFX_ID, 0xA4,
                donor.presentationGameId(), data);
        SmpsSequencerConfig config = donor.getSequencerConfig();
        SmpsDriver detached = new SmpsDriver();
        SmpsSequencer sequencer = new SmpsSequencer(
                data, SmpsSessionTestFixtures.dac(), detached, detached,
                AudioManager.getInstance(), config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        sequencer.setIsSfx(true);
        return new PreparedSmpsSfxProgram(
                new SmpsDriverSnapshot.SequencerEntry(
                        true, source,
                        SmpsSequencer.SourceDescriptorTrust
                                .PRECOMPUTED_IMMUTABLE,
                        null, data, SmpsSessionTestFixtures.dac(),
                        AudioManager.getInstance(), config,
                        sequencer.captureSnapshot()),
                0, 0);
    }

    private static SmpsSourceDescriptor donorSource(
            SmpsSourceDescriptor.Kind kind,
            int id,
            String donorGameId,
            AudioTestFixtures.StubSmpsData data) {
        return new SmpsSourceDescriptor(
                kind, id, null, donorGameId,
                data.getZ80StartAddress(), data.getData().length,
                Arrays.hashCode(data.getData()), false, 19);
    }

    @Test
    void fixtureRejectsUnknownKindsAndOutOfRangeValues() {
        String unknown = """
                {"schema":"openggf.smps_exact_write_program.v1",
                 "source":{"submodule_commit":"044fa46725c71187399e13f5ddb70e11d32dc024",
                 "path":"Sound/Z80 Sound Driver.asm","routine":"zStopAllSound","fix_sndbugs":0},
                 "writes":[{"chip":"DAC","port":0,"register":0,"value":0}]}
                """;
        String outOfRange = unknown.replace("\"DAC\"", "\"PSG\"")
                .replace("\"value\":0", "\"value\":256");

        assertThrows(IllegalArgumentException.class,
                () -> parse(unknown));
        assertThrows(IllegalArgumentException.class,
                () -> parse(outOfRange));
    }

    @Test
    void fixtureRejectsUnpinnedSourceAttribution() {
        String json = """
                {"schema":"openggf.smps_exact_write_program.v1",
                 "source":{"submodule_commit":"0000000000000000000000000000000000000000",
                 "path":"other.asm","routine":"zStopAllSound","fix_sndbugs":0},
                 "writes":[]}
                """;

        assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    private static List<SmpsChipWrite> parse(String json) throws Exception {
        return ExactWriteProgramFixture.parse(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String event(SmpsChipWrite write) {
        if (write instanceof SmpsChipWrite.Ym2612 ym) {
            return "YM:%d:%02X:%02X".formatted(
                    ym.port(), ym.register(), ym.value());
        }
        return "PSG:%02X".formatted(
                ((SmpsChipWrite.Psg) write).value());
    }
}
