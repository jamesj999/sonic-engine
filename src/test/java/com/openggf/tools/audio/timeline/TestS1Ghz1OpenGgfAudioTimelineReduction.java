package com.openggf.tools.audio.timeline;

import static com.openggf.audio.driver.SfxContentionObserver.Bus.FM;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM3;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.MUSIC;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.SoundClass.SFX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestS1Ghz1OpenGgfAudioTimelineReduction {
    private static final Map<S1Ghz1OpenGgfAudioTimelineCapture.CaptureState, Long> NEXT_ORDINAL =
            new IdentityHashMap<>();

    @Test
    void semanticOrdinalsReserveZeroForBaselineAndMatchReferenceRequestOrder() throws Exception {
        // Break caught: the OpenGGF reducer exposes driver-local admission ordinal 0,
        // colliding with the shared GHZ baseline music identity instead of starting at 1.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var firstSource = source(0xA0, 0);
        var firstAdmission = admission(firstSource);
        state.onSfxAdmitted(firstAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, firstSource, null, true));
        var first = requestFor(state, SFX, 0xA0, firstAdmission);

        var secondSource = source(0xA1, 1);
        var secondAdmission = admission(secondSource);
        state.onSfxAdmitted(secondAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, secondSource, firstSource, true));
        var second = requestFor(state, SFX, 0xA1, secondAdmission);

        var baseline = new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0x81, 0);
        var firstOwner = new S1GameplayAudioTimeline.OwnerRef(NORMAL_SFX, 0xA0, 1);
        var secondOwner = new S1GameplayAudioTimeline.OwnerRef(NORMAL_SFX, 0xA1, 2);
        assertEquals(List.of(
                new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0, List.of(FM3), List.of(
                        new S1GameplayAudioTimeline.RoleArbitration(
                                FM3, true, baseline, firstOwner))),
                new S1GameplayAudioTimeline.Admission(2, SFX, 0xA1, List.of(FM3), List.of(
                        new S1GameplayAudioTimeline.RoleArbitration(
                                FM3, true, firstOwner, secondOwner)))),
                List.of(first, second));
    }

    @Test
    void repeatedOwnedDecisionRetainsTheFirstAuthoritativeDisplacement() throws Exception {
        // Break caught: a later B-to-B register decision replaces the authoritative A-to-B takeover.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var first = source(0xA0, 10);
        var second = source(0xA1, 11);
        var admission = admission(second);
        state.onSfxAdmitted(admission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, second, first, true));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, second, second, true));

        var decision = requestFor(state, SFX, 0xA1, admission).arbitration().getFirst();

        assertTrue(decision.acquired());
        assertEquals(0xA0, decision.displacedOwner().soundId());
        assertEquals(10, decision.displacedOwner().requestOrdinal());
    }

    @Test
    void sameFrameRequestsKeepTheirOwnFirstTransitionsInSourceOrder() throws Exception {
        // Break caught: post-admission repetitions rewrite either request in an ordered same-frame pair.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var first = source(0xA0, 20);
        var second = source(0xA1, 21);
        var firstAdmission = admission(first);
        var secondAdmission = admission(second);
        state.onSfxAdmitted(firstAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, first, null, true));
        state.onSfxAdmitted(secondAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, second, first, true));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, first, second, false));

        var firstAdmissionRecord = requestFor(state, SFX, 0xA0, firstAdmission);
        var secondAdmissionRecord = requestFor(state, SFX, 0xA1, secondAdmission);

        assertEquals(1, firstAdmissionRecord.requestOrdinal());
        assertTrue(firstAdmissionRecord.arbitration().getFirst().acquired());
        assertEquals(MUSIC, firstAdmissionRecord.arbitration().getFirst().displacedOwner().ownerClass());
        assertEquals(2, secondAdmissionRecord.requestOrdinal());
        assertEquals(0xA0, secondAdmissionRecord.arbitration().getFirst().displacedOwner().soundId());
    }

    @Test
    void restartedDriverOrdinalStillDisplacesTheSemanticRequestIdentity() throws Exception {
        // Break caught: a replacement driver restarts admission ordinals and leaks raw ordinal 0.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var oldDriverOwner = source(0xA0, 50);
        var restartedOwner = source(0xA1, 0);
        var challenger = source(0xA2, 1);
        state.onSfxAdmitted(admission(oldDriverOwner));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, oldDriverOwner, null, true));
        requestFor(state, SFX, 0xA0, admission(oldDriverOwner));
        state.onSfxAdmitted(admission(restartedOwner));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, restartedOwner, oldDriverOwner, true));
        var restartedRequest = requestFor(state, SFX, 0xA1, admission(restartedOwner));
        state.onSfxAdmitted(admission(challenger));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, challenger, restartedOwner, true));

        var takeover = requestFor(state, SFX, 0xA2, admission(challenger));

        assertEquals(2, restartedRequest.requestOrdinal());
        assertEquals(2, takeover.arbitration().getFirst().displacedOwner().requestOrdinal());
    }

    @Test
    void musicAdmissionCannotInheritStaleSfxArbitrationHistory() throws Exception {
        // Break caught: null music provenance wildcard-matches the latest unrelated SFX event.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var stale = source(0xA0, 30);
        state.onSfxAdmitted(admission(stale));
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, stale, stale, true));

        assertNull(arbitrationFor(state, FM3, null),
                "music has no SFX admission identity and must not wildcard-match SFX events");
    }

    @Test
    void musicAdmissionUsesTheCurrentEffectiveOwnerWithoutConsumingSfxEvents() throws Exception {
        // Break caught: music reduction defaults to $81 instead of retaining an active SFX owner.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        var sfx = source(0xA0, 40);
        var admission = admission(sfx);
        state.onSfxAdmitted(admission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(FM, 2, sfx, null, true));
        var sfxAdmissionRecord = requestFor(state, SFX, 0xA0, admission);
        assertTrue(sfxAdmissionRecord.arbitration().getFirst().acquired());
        addObservedMusicDriver(state, 0x87);

        var musicAdmission = requestFor(state, S1GameplayAudioTimeline.SoundClass.MUSIC, 0x87, null);

        var decision = musicAdmission.arbitration().getFirst();
        assertFalse(decision.acquired());
        assertEquals(0xA0, decision.displacedOwner().soundId());
        assertEquals(1, decision.finalOwner().requestOrdinal());
    }

    @Test
    void jingleTakeoverAndPresentationRestorationPreserveMusicIdentities() throws Exception {
        // Break caught: active/restored music roles remain hard-coded to baseline $81.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        addObservedMusicDriver(state, 0x88);
        var request = requestFor(state, S1GameplayAudioTimeline.SoundClass.MUSIC, 0x88, null);

        var duringJingle = owners(state, presentation(0x88, 8,
                List.of(new AudioPresentationSnapshot.MusicSlotSnapshot(
                        0x81, AudioSourceDescriptor.baseMusic(0x81), 1))));
        var afterRestore = owners(state, presentation(0x81, 1, List.of()));

        assertTrue(request.arbitration().getFirst().acquired());
        assertEquals(request.requestOrdinal(), duringJingle.fm3().requestOrdinal());
        assertEquals(0x88, duringJingle.fm3().soundId());
        assertEquals(0, afterRestore.fm3().requestOrdinal());
        assertEquals(0x81, afterRestore.fm3().soundId());
    }

    @Test
    void musicThenSameFrameSfxPreservesTheMusicBoundaryAcquisition() throws Exception {
        // Break caught: frame-final SFX ownership rewrites an earlier acquired music request.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        addObservedMusicDriver(state, 0x87);
        var music = requestFor(state, S1GameplayAudioTimeline.SoundClass.MUSIC, 0x87, null);
        var sfxSource = source(0xA0, 10);
        var sfxAdmission = admission(sfxSource);
        state.onSfxAdmitted(sfxAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, sfxSource, null, true));
        var sfx = requestFor(state, SFX, 0xA0, sfxAdmission);

        var admissions = reconcileMusic(state, List.of(music, sfx),
                ownersWithFm3(sfx.arbitration().getFirst().finalOwner()));

        var musicDecision = admissions.getFirst().arbitration().getFirst();
        assertTrue(musicDecision.acquired());
        assertEquals(0x81, musicDecision.displacedOwner().soundId());
        assertEquals(0x87, musicDecision.finalOwner().soundId());
        assertEquals(music.requestOrdinal(), musicDecision.finalOwner().requestOrdinal());
        assertEquals(0xA0, admissions.get(1).arbitration().getFirst().finalOwner().soundId());
    }

    @Test
    void mixedSameFrameRequestsRetainEveryBoundaryTransitionInSourceOrder() throws Exception {
        // Break caught: a single frame-final owner replaces both intermediate music transitions.
        var state = new S1Ghz1OpenGgfAudioTimelineCapture.CaptureState();
        addObservedMusicDriver(state, 0x87);
        addObservedMusicDriver(state, 0x88);
        var firstMusic = requestFor(state,
                S1GameplayAudioTimeline.SoundClass.MUSIC, 0x87, null);

        var firstSfxSource = source(0xA0, 10);
        var firstSfxAdmission = admission(firstSfxSource);
        state.onSfxAdmitted(firstSfxAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, firstSfxSource, null, true));
        var firstSfx = requestFor(state, SFX, 0xA0, firstSfxAdmission);

        var secondMusic = requestFor(state,
                S1GameplayAudioTimeline.SoundClass.MUSIC, 0x88, null);

        var secondSfxSource = source(0xA1, 12);
        var secondSfxAdmission = admission(secondSfxSource);
        state.onSfxAdmitted(secondSfxAdmission);
        state.onRoleArbitrated(new SfxContentionObserver.Arbitration(
                FM, 2, secondSfxSource, null, true));
        var secondSfx = requestFor(state, SFX, 0xA1, secondSfxAdmission);

        var admissions = reconcileMusic(state,
                List.of(firstMusic, firstSfx, secondMusic, secondSfx),
                ownersWithFm3(secondSfx.arbitration().getFirst().finalOwner()));

        assertEquals(List.of(0x87, 0xA0, 0x88, 0xA1),
                admissions.stream().map(S1GameplayAudioTimeline.Admission::soundId).toList());
        assertTransition(admissions.get(0), true, 0x81, 0x87);
        assertTransition(admissions.get(1), true, 0x87, 0xA0);
        assertTransition(admissions.get(2), true, 0xA0, 0x88);
        assertTransition(admissions.get(3), true, 0x88, 0xA1);
    }

    private static SfxContentionObserver.Source source(int soundId, long ordinal) {
        var descriptor = new SmpsSourceDescriptor(SmpsSourceDescriptor.Kind.BASE_SFX_ID,
                soundId, null, null, 0, 1, soundId, false, 0);
        return new SfxContentionObserver.Source(descriptor, ordinal, true, false);
    }

    private static SfxContentionObserver.Admission admission(SfxContentionObserver.Source source) {
        return new SfxContentionObserver.Admission(source,
                List.of(new SfxContentionObserver.Role(FM, 2)));
    }

    private static S1GameplayAudioTimeline.Admission requestFor(
            S1Ghz1OpenGgfAudioTimelineCapture.CaptureState state,
            S1GameplayAudioTimeline.SoundClass soundClass,
            int soundId,
            SfxContentionObserver.Admission admission) throws Exception {
        long ordinal = NEXT_ORDINAL.merge(state, 1L, Long::sum);
        return state.admissionFor(ordinal, soundClass, soundId, admission);
    }

    private static SfxContentionObserver.Arbitration arbitrationFor(
            S1Ghz1OpenGgfAudioTimelineCapture.CaptureState state,
            S1GameplayAudioTimeline.HardwareRole role,
            SfxContentionObserver.Source source) throws Exception {
        return state.firstOwnershipTransition(role, source);
    }

    @SuppressWarnings("unchecked")
    private static void addObservedMusicDriver(
            S1Ghz1OpenGgfAudioTimelineCapture.CaptureState state, int musicId) throws Exception {
        SmpsDriver driver = new SmpsDriver();
        driver.addSequencer(sequencer(musicId, driver), false);
        Field field = state.getClass().getDeclaredField("observedDrivers");
        field.setAccessible(true);
        ((List<SmpsDriver>) field.get(state)).add(driver);
    }

    private static SmpsSequencer sequencer(int id, SmpsDriver driver) {
        AbstractSmpsData data = new SingleFmTrackData();
        data.setId(id);
        return new SmpsSequencer(data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {2}).build());
    }

    private static S1GameplayAudioTimeline.OwnerVector owners(
            S1Ghz1OpenGgfAudioTimelineCapture.CaptureState state,
            AudioPresentationSnapshot presentation) throws Exception {
        return state.owners(presentation);
    }

    private static List<S1GameplayAudioTimeline.Admission> reconcileMusic(
            S1Ghz1OpenGgfAudioTimelineCapture.CaptureState state,
            List<S1GameplayAudioTimeline.Admission> admissions,
            S1GameplayAudioTimeline.OwnerVector finalOwners) {
        return state.reconcileCompletedMusic(admissions, finalOwners);
    }

    private static S1GameplayAudioTimeline.OwnerVector ownersWithFm3(
            S1GameplayAudioTimeline.OwnerRef fm3) {
        var baseline = new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0x81, 0);
        return new S1GameplayAudioTimeline.OwnerVector(
                fm3, baseline, baseline, baseline, baseline, baseline);
    }

    private static void assertTransition(S1GameplayAudioTimeline.Admission request,
            boolean acquired, int displacedSoundId, int finalSoundId) {
        var decision = request.arbitration().getFirst();
        assertEquals(acquired, decision.acquired());
        assertEquals(displacedSoundId, decision.displacedOwner().soundId());
        assertEquals(finalSoundId, decision.finalOwner().soundId());
    }

    private static AudioPresentationSnapshot presentation(
            int musicId, long voiceId,
            List<AudioPresentationSnapshot.MusicSlotSnapshot> overrideStack) {
        return new AudioPresentationSnapshot(voiceId + 1, List.of(),
                new AudioPresentationSnapshot.MusicSlotSnapshot(
                        musicId, AudioSourceDescriptor.baseMusic(musicId), voiceId),
                overrideStack, null, 0, 0, 0, 0, false, false, false,
                false, 1, true,
                new com.openggf.audio.smps.SmpsCoordFlagRuntimeState.Snapshot(0),
                null, null);
    }

    private static final class SingleFmTrackData extends AbstractSmpsData {
        private SingleFmTrackData() {
            super(new byte[] {0, 0}, 0);
        }

        @Override
        protected void parseHeader() {
            channels = 1;
            tempo = 1;
            fmPointers = new int[] {1};
            fmKeyOffsets = new int[] {0};
            fmVolumeOffsets = new int[] {0};
        }

        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
