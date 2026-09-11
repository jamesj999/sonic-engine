package com.openggf.audio;

import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.RejectionReason;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.AdmissionObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.Observation;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PsgWriteObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RequestObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.ServiceBeginObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.ServiceEndObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.SfxAdmittedObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.YmWriteObserved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production observation proof for S3K's one-up request-discard boundary. */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kProductionAdmissionObservation {
    private static final int JUMP = 0x62;

    private Rom rom;

    @Test
    void jumpAttemptIsRejectedDuringOneUpAndAcceptedAfterRestore() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        for (int frame = 0; frame < 34; frame++) {
            audio.presentFrame(PresentationMode.FORWARD);
        }

        try (CompleteRunAudioObserverLease observations =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            List<Observation> rejected = observeFrame(audio, observations, 0,
                    () -> audio.playSfx(JUMP));

            assertEquals(List.of(JUMP), rejected.stream()
                    .filter(RequestObserved.class::isInstance)
                    .map(RequestObserved.class::cast)
                    .map(RequestObserved::rawSoundId).toList());
            assertEquals(List.of(RejectionReason.BLOCKED), rejected.stream()
                    .filter(AdmissionObserved.class::isInstance)
                    .map(AdmissionObserved.class::cast)
                    .map(event -> event.decision().result().reason()).toList());
            assertTrue(rejected.stream().noneMatch(SfxAdmittedObserved.class::isInstance));
            assertTrue(indexOf(rejected, RequestObserved.class::isInstance)
                    < indexOf(rejected, AdmissionObserved.class::isInstance));
            assertTrue(indexOf(rejected, AdmissionObserved.class::isInstance)
                    < indexOf(rejected, TestS3kProductionAdmissionObservation::isServiceOrWrite));
            assertTrue(rejected.stream().noneMatch(
                    TestS3kProductionAdmissionObservation::isJumpService));
            assertNoSfx(audio);

            int frame = 1;
            while (playingMusicId(audio) != Sonic3kMusic.AIZ1.id && frame < 900) {
                observeFrame(audio, observations, frame++, () -> { });
            }
            assertEquals(Sonic3kMusic.AIZ1.id, playingMusicId(audio));

            List<Observation> accepted = observeFrame(audio, observations, frame,
                    () -> audio.playSfx(JUMP));
            assertEquals(List.of(JUMP), accepted.stream()
                    .filter(RequestObserved.class::isInstance)
                    .map(RequestObserved.class::cast)
                    .map(RequestObserved::rawSoundId).toList());
            assertEquals(List.of(true), accepted.stream()
                    .filter(AdmissionObserved.class::isInstance)
                    .map(AdmissionObserved.class::cast)
                    .map(event -> event.decision().result().accepted()).toList());
            SfxAdmittedObserved ownership = accepted.stream()
                    .filter(SfxAdmittedObserved.class::isInstance)
                    .map(SfxAdmittedObserved.class::cast)
                    .filter(event -> event.admission().source().descriptor().id() == JUMP)
                    .findFirst().orElseThrow();
            assertFalse(ownership.admission().declaredRoles().isEmpty());
            int requestIndex = indexOf(accepted, RequestObserved.class::isInstance);
            int decisionIndex = indexOf(accepted, AdmissionObserved.class::isInstance);
            int ownershipIndex = accepted.indexOf(ownership);
            assertTrue(requestIndex < decisionIndex);
            assertTrue(decisionIndex < ownershipIndex);

            List<Observation> serviced = observeFrame(audio, observations,
                    frame + 1, () -> { });
            int serviceBeginIndex = indexOf(serviced,
                    TestS3kProductionAdmissionObservation::isJumpServiceBegin);
            int serviceEndIndex = indexOf(serviced,
                    TestS3kProductionAdmissionObservation::isJumpServiceEnd);
            int writeIndex = indexOfAfter(serviced, serviceBeginIndex,
                    TestS3kProductionAdmissionObservation::isWrite);
            assertTrue(serviceBeginIndex < writeIndex && writeIndex < serviceEndIndex,
                    "the accepted jump must own a service containing chip writes");
            assertTrue(hasSfx(audio));
        }
    }

    @Test
    void ringAttemptUsesTheBlockedCallerBoundaryWithoutAdvancingItsPhase() {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
        audio.presentFrame(PresentationMode.FORWARD);
        boolean ringLeftBefore = audio.captureLogicalSnapshot().ringLeft();

        try (CompleteRunAudioObserverLease observations =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            List<Observation> events = observeFrame(audio, observations, 0,
                    () -> audio.playSfx(0x33));

            assertEquals(List.of(0x33), events.stream()
                    .filter(RequestObserved.class::isInstance)
                    .map(RequestObserved.class::cast)
                    .map(RequestObserved::rawSoundId).toList());
            assertEquals(List.of(RejectionReason.BLOCKED), events.stream()
                    .filter(AdmissionObserved.class::isInstance)
                    .map(AdmissionObserved.class::cast)
                    .map(event -> event.decision().result().reason()).toList());
            AdmissionObserved rejection = events.stream()
                    .filter(AdmissionObserved.class::isInstance)
                    .map(AdmissionObserved.class::cast).findFirst().orElseThrow();
            assertFalse(rejection.decision().result().accepted());
            assertEquals(0x33,
                    rejection.decision().context().requestedSoundId());
            assertEquals(0x33,
                    rejection.decision().context().resolvedSoundId(),
                    "blocked ring observation must not invent a speaker selection");
            assertTrue(events.stream().noneMatch(SfxAdmittedObserved.class::isInstance));
            assertTrue(indexOf(events, RequestObserved.class::isInstance)
                    < indexOf(events, AdmissionObserved.class::isInstance));
            assertTrue(indexOf(events, AdmissionObserved.class::isInstance)
                    < indexOf(events, TestS3kProductionAdmissionObservation::isServiceOrWrite));
            assertEquals(ringLeftBefore, audio.captureLogicalSnapshot().ringLeft());
            assertNoSfx(audio);
        }
    }

    private static int indexOf(List<Observation> events,
            Predicate<Observation> predicate) {
        return indexOfAfter(events, -1, predicate);
    }

    private static int indexOfAfter(List<Observation> events, int after,
            Predicate<Observation> predicate) {
        for (int index = after + 1; index < events.size(); index++) {
            if (predicate.test(events.get(index))) {
                return index;
            }
        }
        assertTrue(false, "required observation was not recorded after index " + after);
        return -1;
    }

    private static boolean isWrite(Observation event) {
        return event instanceof YmWriteObserved || event instanceof PsgWriteObserved;
    }

    private static boolean isServiceOrWrite(Observation event) {
        return event instanceof ServiceBeginObserved || isWrite(event);
    }

    private static boolean isJumpService(Observation event) {
        return isJumpServiceBegin(event) || isJumpServiceEnd(event);
    }

    private static boolean isJumpServiceBegin(Observation event) {
        return event instanceof ServiceBeginObserved begin
                && begin.event().sequencer().sfx()
                && begin.event().sequencer().source().id() == JUMP;
    }

    private static boolean isJumpServiceEnd(Observation event) {
        return event instanceof ServiceEndObserved end
                && end.event().sequencer().sfx()
                && end.event().sequencer().source().id() == JUMP;
    }

    private static List<Observation> observeFrame(AudioManager audio,
            CompleteRunAudioObserverLease observations, int frame,
            Runnable stimulus) {
        var before = audio.captureLogicalSnapshot();
        observations.beginRow(frame, before);
        stimulus.run();
        audio.presentFrame(PresentationMode.FORWARD);
        var row = observations.finishRow(frame, audio.captureLogicalSnapshot());
        assertTrue(row.events().stream().mapToLong(Observation::ordinal)
                .reduce((left, right) -> {
                    assertTrue(left < right, "observer ordinals must preserve production order");
                    return right;
                }).isPresent());
        return row.events();
    }

    private static void assertNoSfx(AudioManager audio) {
        assertFalse(hasSfx(audio));
    }

    private static boolean hasSfx(AudioManager audio) {
        return audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .anyMatch(SmpsDriverSnapshot.SequencerEntry::sfx);
    }

    private static int playingMusicId(AudioManager audio) {
        return audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(entry -> !entry.sfx())
                .mapToInt(entry -> entry.smpsData().getId())
                .findFirst().orElse(-1);
    }

    private AudioManager install() {
        File file = RomTestUtils.ensureSonic3kRomAvailable();
        rom = new Rom();
        assertTrue(rom.open(file.getAbsolutePath()));
        Sonic3kAudioProfile profile = new Sonic3kAudioProfile();
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        return audio;
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }
}
