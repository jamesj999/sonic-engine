package com.openggf.tools.audio.timeline;

import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM3;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM4;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM5;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.PSG1;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.PSG2;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.PSG3;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.MUSIC;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.SPECIAL_SFX;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.SoundClass.SFX;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioRequestObserver;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioTimelineEntry;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.GameServices;
import com.openggf.tests.trace.runs.VisualRunReplayHarness;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local-only OpenGGF producer for the pinned S1 GHZ1 semantic timeline. The
 * capture is deliberately observational: it drives the normal visual replay
 * path and serializes what that path naturally presented.
 */
public final class S1Ghz1OpenGgfAudioTimelineCapture {
    private S1Ghz1OpenGgfAudioTimelineCapture() { }

    public static void capture(Path runDirectory, Path output) throws Exception {
        CaptureState state = new CaptureState();
        try {
            VisualRunReplayHarness.replayAudio(runDirectory,
                    VisualRunReplayHarness.stopAfterSegmentBody(0), state);
            S1GameplayAudioTimelineJsonl.writeNew(output, metadata(), state.records().iterator());
        } finally {
            state.detach();
            VisualRunReplayHarness.tearDown();
        }
    }

    private static S1GameplayAudioTimeline.Metadata metadata() {
        return new S1GameplayAudioTimeline.Metadata(S1GameplayAudioTimeline.SCHEMA,
                S1GameplayAudioTimeline.OPENGGF_CAPTURE,
                S1GameplayAudioTimeline.S1_REV01_SHA1,
                S1GameplayAudioTimeline.S1_REV01_CRC32,
                S1GameplayAudioTimeline.BK2_SHA256,
                S1GameplayAudioTimeline.OPENGGF_PRODUCER,
                S1GameplayAudioTimeline.SEGMENT_START_BK2_FRAME,
                S1GameplayAudioTimeline.SEGMENT_END_BK2_FRAME,
                S1GameplayAudioTimeline.SEGMENT_FRAME_COUNT);
    }

    static final class CaptureState implements VisualRunReplayHarness.FrameObserver,
            SfxContentionObserver, AudioRequestObserver {
        private final List<S1GameplayAudioTimeline.TimelineRecord> records = new ArrayList<>();
        private final List<SfxContentionObserver.Arbitration> frameArbitrations = new ArrayList<>();
        private final List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        private final List<SfxContentionObserver.Admission> frameAdmissions = new ArrayList<>();
        private final List<S1GameplayAudioTimeline.Request> frameRequests = new ArrayList<>();
        private final List<S1GameplayAudioTimeline.Request> pendingRequests = new ArrayList<>();
        private final List<SmpsDriver> observedDrivers = new ArrayList<>();
        private final Map<SfxContentionObserver.Source, S1GameplayAudioTimeline.OwnerRef>
                semanticSfxOwners = new HashMap<>();
        private final Map<Long, S1GameplayAudioTimeline.OwnerRef> musicOwnersByVoiceId = new HashMap<>();
        private final Map<Integer, S1GameplayAudioTimeline.OwnerRef> latestMusicOwnersById = new HashMap<>();
        private final Map<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef>
                effectiveOwners = new EnumMap<>(S1GameplayAudioTimeline.HardwareRole.class);
        private int nextCommandEntry;
        private long diagnosticPresentations;
        private long emittedRequestCount;
        private long emittedAdmissionCount;
        // Timeline ordinal 0 belongs to the frame-860 GHZ music baseline. Every
        // later admission retains the ordinal assigned at its raw request boundary.
        private long lastRequestOrdinal = 0;
        private boolean baselineCaptured;
        private S1GameplayAudioTimeline.OwnerRef currentMusicOwner = musicOwner();

        CaptureState() {
            latestMusicOwnersById.put(currentMusicOwner.soundId(), currentMusicOwner);
            for (S1GameplayAudioTimeline.HardwareRole role
                    : S1GameplayAudioTimeline.HardwareRole.values()) {
                effectiveOwners.put(role, currentMusicOwner);
            }
        }

        @Override
        public void beforeFirstSegmentRow(VisualRunReplayHarness.FrameView frame) {
            if (frame.consumedBk2Cursor() != S1GameplayAudioTimeline.SEGMENT_START_BK2_FRAME) {
                throw new IllegalStateException("GHZ1 baseline was not sampled before BK2 row 860");
            }
            installOnLiveDrivers();
            AudioManager audio = GameServices.audio();
            audio.setRequestObserver(this);
            AudioPresentationSnapshot presentation = audio.captureLogicalSnapshot().presentation();
            int activeMusic = presentation.activeMusic() == null ? -1 : presentation.activeMusic().musicId();
            currentMusicOwner = new S1GameplayAudioTimeline.OwnerRef(MUSIC, activeMusic, 0);
            latestMusicOwnersById.put(activeMusic, currentMusicOwner);
            S1GameplayAudioTimeline.OwnerVector baselineOwners = owners(presentation);
            records.add(new S1GameplayAudioTimeline.Baseline(frame.consumedBk2Cursor(), activeMusic,
                    diagnosticPresentations, baselineOwners));
            nextCommandEntry = audio.commandTimeline().entryCount();
            baselineCaptured = true;
            clearFrameObservations();
        }

        @Override
        public void afterOuterFrame(VisualRunReplayHarness.FrameView frame) {
            diagnosticPresentations++;
            try {
                if (!baselineCaptured || !frame.semanticRow() || frame.segmentIndex() != 0) {
                    return;
                }
                if (frame.consumedBk2Cursor() < S1GameplayAudioTimeline.SEGMENT_START_BK2_FRAME
                        || frame.consumedBk2Cursor() >= S1GameplayAudioTimeline.SEGMENT_END_BK2_FRAME) {
                    return;
                }
                installOnLiveDrivers();
                AudioManager audio = GameServices.audio();
                List<S1GameplayAudioTimeline.Admission> admissions = drainAdmissions(audio);
                AudioPresentationSnapshot presentation = audio.captureLogicalSnapshot().presentation();
                S1GameplayAudioTimeline.OwnerVector finalOwners = owners(presentation);
                admissions = reconcileCompletedMusic(admissions, finalOwners);
                records.add(new S1GameplayAudioTimeline.Frame(frame.consumedBk2Cursor(),
                        diagnosticPresentations, List.copyOf(frameRequests), admissions, finalOwners));
            } finally {
                clearFrameObservations();
            }
        }

        @Override
        public void onSfxAdmitted(SfxContentionObserver.Admission admission) {
            admissions.add(admission);
            frameAdmissions.add(admission);
        }

        @Override
        public void onRoleArbitrated(SfxContentionObserver.Arbitration arbitration) {
            frameArbitrations.add(arbitration);
        }

        @Override
        public void onRequested(AudioRequestObserver.RequestClass requestClass, int rawSoundId) {
            S1GameplayAudioTimeline.Request request = new S1GameplayAudioTimeline.Request(
                    ++lastRequestOrdinal, switch (requestClass) {
                        case MUSIC -> S1GameplayAudioTimeline.SoundClass.MUSIC;
                        case SFX -> S1GameplayAudioTimeline.SoundClass.SFX;
                        case SPECIAL_SFX -> S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX;
                        case COMMAND -> S1GameplayAudioTimeline.SoundClass.COMMAND;
                    }, rawSoundId);
            frameRequests.add(request);
            if (request.soundClass() != S1GameplayAudioTimeline.SoundClass.COMMAND) {
                pendingRequests.add(request);
            }
            emittedRequestCount++;
        }

        List<S1GameplayAudioTimeline.TimelineRecord> records() {
            if (!baselineCaptured || records.size() != S1GameplayAudioTimeline.SEGMENT_FRAME_COUNT + 1) {
                throw new IllegalStateException("capture did not produce exactly 4,115 semantic GHZ1 frames");
            }
            records.add(new S1GameplayAudioTimeline.Terminal(S1GameplayAudioTimeline.SEGMENT_FRAME_COUNT,
                    emittedRequestCount, emittedAdmissionCount,
                    S1GameplayAudioTimeline.SEGMENT_FRAME_COUNT + 1));
            return List.copyOf(records);
        }

        void detach() {
            for (SmpsDriver driver : observedDrivers) {
                driver.setSfxContentionObserver(SfxContentionObserver.NONE);
            }
            try {
                GameServices.audio().setRequestObserver(AudioRequestObserver.NONE);
            } catch (IllegalStateException ignored) {
                // Teardown may already have retired the service singleton.
            }
        }

        private List<S1GameplayAudioTimeline.Admission> drainAdmissions(AudioManager audio) {
            int end = audio.commandTimeline().entryCount();
            List<S1GameplayAudioTimeline.Admission> admitted = new ArrayList<>();
            for (int entryIndex = nextCommandEntry; entryIndex < end; entryIndex++) {
                AudioTimelineEntry entry = audio.commandTimeline().entryAt(entryIndex);
                if (entry.command() instanceof AudioCommand.PlaySfx sfx && sfx.sfxId() >= 0) {
                    S1GameplayAudioTimeline.Request request = takePendingSfxRequest();
                    admitted.add(admissionFor(request.requestOrdinal(), soundClassForSfx(sfx.sfxId()),
                            sfx.sfxId(), takeAdmission(sfx.sfxId())));
                } else if (entry.command() instanceof AudioCommand.PlayMusic music) {
                    S1GameplayAudioTimeline.Request request = takePendingRequest(
                            S1GameplayAudioTimeline.SoundClass.MUSIC);
                    admitted.add(admissionFor(request.requestOrdinal(), S1GameplayAudioTimeline.SoundClass.MUSIC,
                            music.musicId(), null));
                }
            }
            nextCommandEntry = end;
            emittedAdmissionCount += admitted.size();
            return List.copyOf(admitted);
        }

        List<S1GameplayAudioTimeline.Admission> reconcileCompletedMusic(
                List<S1GameplayAudioTimeline.Admission> admissions,
                S1GameplayAudioTimeline.OwnerVector finalOwners) {
            Map<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef>
                    ownersAfterRequest = new EnumMap<>(S1GameplayAudioTimeline.HardwareRole.class);
            for (S1GameplayAudioTimeline.HardwareRole role
                    : S1GameplayAudioTimeline.HardwareRole.values()) {
                ownersAfterRequest.put(role, finalOwners.owner(role));
            }

            List<S1GameplayAudioTimeline.Admission> reconciled = new ArrayList<>(admissions);
            for (int admissionIndex = admissions.size() - 1; admissionIndex >= 0; admissionIndex--) {
                S1GameplayAudioTimeline.Admission admission = admissions.get(admissionIndex);
                if (admission.soundClass() == S1GameplayAudioTimeline.SoundClass.MUSIC) {
                    S1GameplayAudioTimeline.OwnerRef identity = new S1GameplayAudioTimeline.OwnerRef(
                            MUSIC, admission.soundId(), admission.requestOrdinal());
                    List<S1GameplayAudioTimeline.RoleArbitration> decisions = new ArrayList<>();
                    for (S1GameplayAudioTimeline.RoleArbitration decision : admission.arbitration()) {
                        S1GameplayAudioTimeline.OwnerRef finalOwner = ownersAfterRequest.get(decision.role());
                        boolean acquired = identity.equals(finalOwner);
                        S1GameplayAudioTimeline.OwnerRef displaced = decision.displacedOwner();
                        if (acquired && displaced.equals(finalOwner)) {
                            throw new IllegalStateException("acquired music role cannot displace itself: "
                                    + decision.role() + " " + finalOwner);
                        }
                        if (!acquired && !displaced.equals(finalOwner)) {
                            throw new IllegalStateException("rejected music role changed owner: "
                                    + decision.role() + " " + displaced + " -> " + finalOwner);
                        }
                        decisions.add(new S1GameplayAudioTimeline.RoleArbitration(
                                decision.role(), acquired, displaced, finalOwner));
                    }
                    reconciled.set(admissionIndex, new S1GameplayAudioTimeline.Admission(
                            admission.requestOrdinal(), admission.soundClass(), admission.soundId(),
                            admission.requestedRoles(), decisions));
                }

                // Walk back across this request's request-local ownership transition. The
                // forward reducer captured the owner immediately before each requested role;
                // reversing later transitions therefore exposes this request's own post-state.
                for (S1GameplayAudioTimeline.RoleArbitration decision : admission.arbitration()) {
                    ownersAfterRequest.put(decision.role(), decision.displacedOwner());
                }
            }
            return List.copyOf(reconciled);
        }

        static S1GameplayAudioTimeline.SoundClass soundClassForSfx(int soundId) {
            return soundId == 0xD0 ? S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX : SFX;
        }

        S1GameplayAudioTimeline.Admission admissionFor(long ordinal,
                S1GameplayAudioTimeline.SoundClass soundClass,
                int soundId, SfxContentionObserver.Admission admission) {
            SfxContentionObserver.Source source = admission == null ? null : admission.source();
            List<S1GameplayAudioTimeline.HardwareRole> roles = declaredRoles(admission, soundId, soundClass);
            S1GameplayAudioTimeline.OwnerRef identity = new S1GameplayAudioTimeline.OwnerRef(
                    ownerClass(soundClass, soundId), soundId, ordinal);
            if (soundClass == S1GameplayAudioTimeline.SoundClass.MUSIC) {
                currentMusicOwner = identity;
                latestMusicOwnersById.put(soundId, identity);
            } else if (source != null) {
                semanticSfxOwners.put(source, identity);
            }
            List<S1GameplayAudioTimeline.RoleArbitration> decisions = new ArrayList<>();
            for (S1GameplayAudioTimeline.HardwareRole role : roles) {
                S1GameplayAudioTimeline.OwnerRef displaced = effectiveOwners.get(role);
                boolean acquired;
                S1GameplayAudioTimeline.OwnerRef finalOwner;
                if (soundClass == S1GameplayAudioTimeline.SoundClass.MUSIC) {
                    acquired = displaced.ownerClass() == MUSIC;
                    finalOwner = acquired ? identity : displaced;
                } else {
                    SfxContentionObserver.Arbitration event = firstOwnershipTransition(role, source);
                    acquired = event != null && event.acquired();
                    if (event != null) {
                        displaced = owner(event.previousOwner());
                    }
                    finalOwner = acquired ? identity : displaced;
                }
                if (acquired && displaced.equals(finalOwner)) {
                    throw new IllegalStateException("acquired role cannot displace its final owner: "
                            + role + " " + finalOwner);
                }
                decisions.add(new S1GameplayAudioTimeline.RoleArbitration(role, acquired,
                        displaced, finalOwner));
                effectiveOwners.put(role, finalOwner);
            }
            return new S1GameplayAudioTimeline.Admission(ordinal, soundClass, soundId, roles, decisions);
        }

        private S1GameplayAudioTimeline.Request takePendingSfxRequest() {
            for (int index = 0; index < pendingRequests.size(); index++) {
                S1GameplayAudioTimeline.SoundClass soundClass = pendingRequests.get(index).soundClass();
                if (soundClass == S1GameplayAudioTimeline.SoundClass.SFX
                        || soundClass == S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX) {
                    return pendingRequests.remove(index);
                }
            }
            throw new IllegalStateException("resolved SFX admission has no observed caller request");
        }

        private S1GameplayAudioTimeline.Request takePendingRequest(
                S1GameplayAudioTimeline.SoundClass soundClass) {
            for (int index = 0; index < pendingRequests.size(); index++) {
                if (pendingRequests.get(index).soundClass() == soundClass) {
                    return pendingRequests.remove(index);
                }
            }
            throw new IllegalStateException("audio admission has no observed caller request: " + soundClass);
        }

        private List<S1GameplayAudioTimeline.HardwareRole> declaredRoles(SfxContentionObserver.Admission admission, int soundId,
                S1GameplayAudioTimeline.SoundClass soundClass) {
            if (admission != null) {
                return admission.declaredRoles().stream().map(role -> role(role.bus(), role.channel()))
                        .filter(java.util.Objects::nonNull).distinct().toList();
            }
            for (SmpsDriver driver : observedDrivers) {
                SmpsDriverSnapshot snapshot = driver.captureSnapshot();
                for (SmpsDriverSnapshot.SequencerEntry entry : snapshot.sequencers()) {
                    if (matchesLiveSequencerRole(soundClass, entry.sfx())
                            && entry.source().id() == soundId) {
                        List<S1GameplayAudioTimeline.HardwareRole> roles = new ArrayList<>();
                        for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                            S1GameplayAudioTimeline.HardwareRole role = role(track.type(), track.channelId());
                            if (role != null && !roles.contains(role)) {
                                roles.add(role);
                            }
                        }
                        if (!roles.isEmpty()) {
                            return List.copyOf(roles);
                        }
                    }
                }
            }
            throw new IllegalStateException("no declared SMPS role for sound $%02X".formatted(soundId));
        }

        static boolean matchesLiveSequencerRole(
                S1GameplayAudioTimeline.SoundClass soundClass, boolean sequencerSfx) {
            return sequencerSfx == (soundClass != S1GameplayAudioTimeline.SoundClass.MUSIC);
        }

        SfxContentionObserver.Arbitration firstOwnershipTransition(
                S1GameplayAudioTimeline.HardwareRole role,
                SfxContentionObserver.Source source) {
            if (source == null) {
                return null;
            }
            for (SfxContentionObserver.Arbitration event : frameArbitrations) {
                if (role(event.bus(), event.channel()) == role
                        && source.equals(event.challenger())
                        && !source.equals(event.previousOwner())) {
                    return event;
                }
            }
            return null;
        }

        S1GameplayAudioTimeline.OwnerVector owners(AudioPresentationSnapshot presentation) {
            Map<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef> owners =
                    new EnumMap<>(S1GameplayAudioTimeline.HardwareRole.class);
            rememberMusicSlots(presentation);
            S1GameplayAudioTimeline.OwnerRef presentedMusic = presentedMusicOwner(presentation);
            for (S1GameplayAudioTimeline.HardwareRole role : S1GameplayAudioTimeline.HardwareRole.values()) {
                owners.put(role, presentedMusic);
            }
            if (presentation.smpsLogical() != null) {
                applyLocks(presentation.smpsLogical(), owners);
            }
            effectiveOwners.putAll(owners);
            return new S1GameplayAudioTimeline.OwnerVector(owners.get(FM3), owners.get(FM4), owners.get(FM5),
                    owners.get(PSG1), owners.get(PSG2), owners.get(PSG3));
        }

        private void rememberMusicSlots(AudioPresentationSnapshot presentation) {
            if (presentation.activeMusic() != null
                    && presentation.activeMusic().musicId() == currentMusicOwner.soundId()) {
                musicOwnersByVoiceId.put(presentation.activeMusic().voiceId(), currentMusicOwner);
            }
            for (AudioPresentationSnapshot.MusicSlotSnapshot slot : presentation.overrideStack()) {
                musicOwnersByVoiceId.computeIfAbsent(slot.voiceId(), ignored -> musicOwnerForId(slot.musicId()));
            }
        }

        private S1GameplayAudioTimeline.OwnerRef presentedMusicOwner(
                AudioPresentationSnapshot presentation) {
            if (presentation.activeMusic() == null) {
                return noneOwner();
            }
            AudioPresentationSnapshot.MusicSlotSnapshot active = presentation.activeMusic();
            S1GameplayAudioTimeline.OwnerRef owner = musicOwnersByVoiceId.get(active.voiceId());
            if (owner == null) {
                owner = musicOwnerForId(active.musicId());
                musicOwnersByVoiceId.put(active.voiceId(), owner);
            }
            currentMusicOwner = owner;
            return owner;
        }

        private S1GameplayAudioTimeline.OwnerRef musicOwnerForId(int musicId) {
            S1GameplayAudioTimeline.OwnerRef known = latestMusicOwnersById.get(musicId);
            if (known != null) {
                return known;
            }
            throw new IllegalStateException("active music $%02X has no request or baseline identity"
                    .formatted(musicId));
        }

        private void applyLocks(SmpsDriverSnapshot snapshot,
                Map<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef> owners) {
            applyLock(snapshot, snapshot.fmLockSequencerIds(), 2, FM3, owners);
            applyLock(snapshot, snapshot.fmLockSequencerIds(), 3, FM4, owners);
            applyLock(snapshot, snapshot.fmLockSequencerIds(), 4, FM5, owners);
            applyLock(snapshot, snapshot.psgLockSequencerIds(), 0, PSG1, owners);
            applyLock(snapshot, snapshot.psgLockSequencerIds(), 1, PSG2, owners);
            applyLock(snapshot, snapshot.psgLockSequencerIds(), 2, PSG3, owners);
        }

        private void applyLock(SmpsDriverSnapshot snapshot, int[] lockIds, int channel,
                S1GameplayAudioTimeline.HardwareRole role,
                Map<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef> owners) {
            if (channel >= lockIds.length || lockIds[channel] < 0 || lockIds[channel] >= snapshot.sequencers().size()) {
                return;
            }
            SmpsDriverSnapshot.SequencerEntry sequencer = snapshot.sequencers().get(lockIds[channel]);
            SfxContentionObserver.Source source = latestAdmission(sequencer.source().id());
            owners.put(role, source == null ? noneOwner() : owner(source));
        }

        private SfxContentionObserver.Admission takeAdmission(int soundId) {
            for (int index = 0; index < frameAdmissions.size(); index++) {
                if (frameAdmissions.get(index).source().descriptor().id() == soundId) {
                    return frameAdmissions.remove(index);
                }
            }
            return null;
        }

        private void clearFrameObservations() {
            frameAdmissions.clear();
            frameArbitrations.clear();
            frameRequests.clear();
        }

        private SfxContentionObserver.Source latestAdmission(int soundId) {
            for (int index = admissions.size() - 1; index >= 0; index--) {
                if (admissions.get(index).source().descriptor().id() == soundId) return admissions.get(index).source();
            }
            return null;
        }

        private void installOnLiveDrivers() {
            try {
                Field sessionField = AudioManager.class
                        .getDeclaredField("shadowSmpsSession");
                sessionField.setAccessible(true);
                SmpsDriverSession session = (SmpsDriverSession)
                        sessionField.get(GameServices.audio());
                if (session == null) {
                    return;
                }
                Field driverField = SmpsDriverSession.class
                        .getDeclaredField("driver");
                driverField.setAccessible(true);
                SmpsDriver driver = (SmpsDriver) driverField.get(session);
                if (!observedDrivers.contains(driver)) {
                    driver.setSfxContentionObserver(this);
                    observedDrivers.add(driver);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("audio presentation registry seam moved", failure);
            }
        }

        private static S1GameplayAudioTimeline.HardwareRole role(SfxContentionObserver.Bus bus, int channel) {
            return bus == SfxContentionObserver.Bus.FM ? fmRole(channel) : psgRole(channel);
        }

        private static S1GameplayAudioTimeline.HardwareRole role(SmpsSequencer.TrackType type, int channel) {
            return type == SmpsSequencer.TrackType.FM ? fmRole(channel)
                    : type == SmpsSequencer.TrackType.PSG ? psgRole(channel) : null;
        }

        private static S1GameplayAudioTimeline.HardwareRole fmRole(int channel) {
            return switch (channel) { case 2 -> FM3; case 3 -> FM4; case 4 -> FM5; default -> null; };
        }

        private static S1GameplayAudioTimeline.HardwareRole psgRole(int channel) {
            return switch (channel) { case 0 -> PSG1; case 1 -> PSG2; case 2 -> PSG3; default -> null; };
        }

        private S1GameplayAudioTimeline.OwnerRef owner(SfxContentionObserver.Source source) {
            if (source == null) {
                return currentMusicOwner;
            }
            S1GameplayAudioTimeline.OwnerRef semantic = semanticSfxOwners.get(source);
            if (semantic != null) {
                return semantic;
            }
            return new S1GameplayAudioTimeline.OwnerRef(
                    source.specialSfx() || source.descriptor().id() == 0xD0 ? SPECIAL_SFX : NORMAL_SFX,
                    source.descriptor().id(), source.admissionOrdinal());
        }

        private static S1GameplayAudioTimeline.OwnerRef musicOwner() {
            return new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0x81, 0);
        }

        private static S1GameplayAudioTimeline.OwnerRef noneOwner() {
            return new S1GameplayAudioTimeline.OwnerRef(
                    S1GameplayAudioTimeline.OwnerClass.NONE, 0, -1);
        }

        private static S1GameplayAudioTimeline.OwnerClass ownerClass(
                S1GameplayAudioTimeline.SoundClass soundClass, int soundId) {
            return soundClass == S1GameplayAudioTimeline.SoundClass.MUSIC ? MUSIC
                    : soundId == 0xD0 ? SPECIAL_SFX : NORMAL_SFX;
        }
    }
}
