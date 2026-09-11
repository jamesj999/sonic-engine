package com.openggf.tools.audio.completerun.s3k;

import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.DAC;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.FM1;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.FM2;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.FM3;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.FM4;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.FM5;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.PSG1;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.PSG2;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole.PSG3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Asset;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.DriverGlobals;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.LiveSfx;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.RomPointer;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.SavedMusic;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Snapshot;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Track;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestS3kCompleteRunStateNormalizer {
    private static final Map<String, Asset> ASSETS = Map.of(
            "music.aiz1", new Asset("music.aiz1", 0x100000, 0x101000),
            "music.saved", new Asset("music.saved", 0x110000, 0x111000),
            "sfx.ring", new Asset("sfx.ring", 0x0f8000, 0x0f9000),
            "driver", new Asset("driver", 0, 0x2000));

    @Test
    void liveSfxOverlapOwnsItsPhysicalRoleWithoutExposingSavedCapacity() {
        List<Track> music = musicTracks("music.aiz1", 0x100100);
        List<Track> sfx = inactiveTracks(7);
        sfx.set(1, track("sfx.ring", 0x0f8120, 0x84, 0x04)); // SFX FM4 overrides music FM4.
        Snapshot snapshot = new Snapshot(globals(0, false), music, new LiveSfx(sfx));

        NormalizedState state = S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS);

        assertEquals(List.of(DAC, FM1, FM2, FM3, FM4, FM5, PSG1, PSG2, PSG3),
                state.roles().stream().map(role -> role.role()).toList());
        assertEquals("SFX", roleFields(state, FM4).get("sourceLayer"));
        assertEquals("sfx.ring", roleFields(state, FM4).get("assetKey"));
        assertEquals(0x120L, roleFields(state, FM4).get("cursor"));
        Map<String, Object> overlap = globalMap(state, "overlap");
        assertEquals("LIVE_SFX", overlap.get("mode"));
        assertFalse(overlap.containsKey("savedCurrentTempo"));
        assertFalse(overlap.containsKey("savedTracks"));
    }

    @Test
    void oneUpOverlapPreservesSavedTempoPointersAndShippedSaveLoopBug() {
        List<Track> saved = musicTracks("music.saved", 0x110100);
        saved.replaceAll(track -> withPlayback(track, track.playbackControl() & 0x7f));
        Snapshot snapshot = new Snapshot(globals(0x29, true), musicTracks("music.aiz1", 0x100100),
                new SavedMusic(saved));

        NormalizedState state = S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS);
        Map<String, Object> overlap = globalMap(state, "overlap");

        assertEquals("SAVED_MUSIC", overlap.get("mode"));
        assertEquals(0x96, overlap.get("savedCurrentTempo"));
        assertEquals(0x08, overlap.get("savedTempoSpeedup"));
        assertEquals(Map.of("assetKey", "music.saved", "cursor", 0x40L),
                overlap.get("savedVoiceTablePointer"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedTracks = (List<Map<String, Object>>) overlap.get("savedTracks");
        // fix_sndbugs=0 sets bit 2 in RAM and then overwrites it with A, so an ordinary
        // saved playing track becomes $00, not the intended $04.
        assertEquals(0, savedTracks.get(0).get("playbackControl"));
        assertTrue(state.roles().stream().anyMatch(role -> role.role() == FM1 && role.active()));
    }

    @Test
    void referenceAndEngineAdaptersCanonicalizePointersToTheSameAssetCursor() {
        Snapshot snapshot = new Snapshot(globals(0, false), musicTracks("music.aiz1", 0x100100),
                new LiveSfx(inactiveTracks(7)));

        assertEquals(S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS),
                S3kCompleteRunStateNormalizer.normalizeEngine(snapshot, ASSETS));
    }

    @Test
    void roleUnionsRetainOnlyFutureAffectingDacFmAndPsgState() {
        List<Track> music = musicTracks("music.aiz1", 0x100100);
        music.set(0, unionTrack("music.aiz1", 0x100100, 0x80, 0x06,
                0xab34, 0x55, 0x66, new RomPointer("music.aiz1", 0x100310), 0xe7,
                0x77, new RomPointer("music.aiz1", 0x100320), 0,
                new RomPointer("music.aiz1", 0x100330), 0x9876, 9, 8, 7, 6,
                new RomPointer("music.aiz1", 0x100340), 0x2e,
                List.of(new RomPointer("music.aiz1", 0x100350))));
        music.set(6, unionTrack("music.aiz1", 0x1001c0, 0x81, 0x80,
                0x1234, 0x55, 0x66, new RomPointer("music.aiz1", 0x100360), 0xe7,
                0x77, new RomPointer("music.aiz1", 0x100370), 0,
                new RomPointer("music.aiz1", 0x100380), 0x9876, 9, 8, 7, 6,
                new RomPointer("music.aiz1", 0x100390), 0x2e,
                List.of(new RomPointer("music.aiz1", 0x1003a0))));

        NormalizedState state = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), music, new LiveSfx(inactiveTracks(7))), ASSETS);

        Map<String, Object> dac = roleFields(state, DAC);
        assertEquals(0x34, dac.get("savedDac"));
        assertEquals(0, dac.get("frequency"));
        assertEquals(0, dac.get("fmVolumeEnvelope"));
        assertEquals(0, dac.get("psgNoise"));

        Map<String, Object> psg = roleFields(state, PSG1);
        assertEquals(0, psg.get("savedDac"));
        assertEquals(0x1234, psg.get("frequency"));
        assertEquals(0xe7, psg.get("psgNoise"));
        assertEquals(0, psg.get("feedbackAlgorithm"));
        assertEquals(Map.of("active", false), psg.get("ssgEgPointer"));
        assertEquals(Map.of("active", false), psg.get("totalLevelPointer"));
    }

    @Test
    void inactiveUnionCapacityDoesNotCreateFalseStateDifferences() {
        List<Track> cleanMusic = musicTracks("music.aiz1", 0x100100);
        List<Track> staleMusic = musicTracks("music.aiz1", 0x100100);
        cleanMusic.set(0, unionTrack("music.aiz1", 0x100100, 0x80, 0x06,
                0x0034, 0, 0, null, 0, 0, null, 0, null, 0, 0, 0, 0, 0,
                null, 0x30, List.of()));
        staleMusic.set(0, unionTrack("music.aiz1", 0x100100, 0x80, 0x06,
                0xab34, 0x55, 0x66, new RomPointer("music.aiz1", 0x100310), 0xe7,
                0x77, new RomPointer("music.aiz1", 0x100320), 0,
                new RomPointer("music.aiz1", 0x100330), 0x9876, 9, 8, 7, 6,
                new RomPointer("music.aiz1", 0x100340), 0x30, List.of()));
        staleMusic.set(0, withSharedStorage(staleMusic.get(0), cleanMusic.get(0).sharedStorage()));
        cleanMusic.set(6, unionTrack("music.aiz1", 0x1001c0, 0x80, 0x80,
                0x1234, 0, 0, null, 0, 0, null, 0, null, 0, 0, 0, 0, 0,
                null, 0x30, List.of()));
        staleMusic.set(6, unionTrack("music.aiz1", 0x1001c0, 0x80, 0x80,
                0x1234, 0x55, 0x66, new RomPointer("music.aiz1", 0x100350), 0xe7,
                0x77, new RomPointer("music.aiz1", 0x100360), 0,
                new RomPointer("music.aiz1", 0x100370), 0x9876, 9, 8, 7, 6,
                new RomPointer("music.aiz1", 0x100380), 0x30, List.of()));
        staleMusic.set(6, withSharedStorage(staleMusic.get(6), cleanMusic.get(6).sharedStorage()));

        NormalizedState clean = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), cleanMusic, new LiveSfx(inactiveTracks(7))), ASSETS);
        NormalizedState stale = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), staleMusic, new LiveSfx(inactiveTracks(7))), ASSETS);

        assertEquals(clean, stale);
    }

    @Test
    void modulationModesAndUpdatingSfxSelectTheirLiveUnionFields() {
        List<Track> music = musicTracks("music.aiz1", 0x100100);
        music.set(1, unionTrack("music.aiz1", 0x100120, 0x80, 0x00,
                0x1234, 4, 5, new RomPointer("music.aiz1", 0x100310), 0xe7,
                6, new RomPointer("music.aiz1", 0x100320), 3,
                new RomPointer("music.aiz1", 0x100330), 0x12fe, 9, 7, 6, 5,
                new RomPointer("music.aiz1", 0x100340), 0x2e,
                List.of(new RomPointer("music.aiz1", 0x100350))));
        music.set(2, unionTrack("music.aiz1", 0x100140, 0x80, 0x01,
                0x1234, 0x80, 0x77, new RomPointer("music.aiz1", 0x100360), 0xe7,
                6, new RomPointer("music.aiz1", 0x100370), 0x80,
                new RomPointer("music.aiz1", 0x100380), 0x1234, 9, 7, 6, 5,
                null, 0x30, List.of()));
        List<Track> sfx = inactiveTracks(7);
        sfx.set(1, unionTrack("sfx.ring", 0x0f8120, 0x80, 0x04,
                0x1234, 0, 0, null, 0, 6, new RomPointer("sfx.ring", 0x0f8320), 0,
                null, 0, 0, 0, 0, 0, new RomPointer("sfx.ring", 0x0f8340),
                0x30, List.of()));

        NormalizedState state = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), music, new LiveSfx(sfx)), ASSETS);
        Map<String, Object> fm1 = roleFields(state, FM1);
        assertEquals("ENVELOPE", fm1.get("modulationMode"));
        assertEquals(0xfe, fm1.get("modulationEnvelopeSensitivity"));
        assertEquals(7, fm1.get("modulationEnvelopeIndex"));
        assertEquals(0, fm1.get("modulationValue"));
        assertEquals(Map.of("active", false), fm1.get("modulationPointer"));
        assertEquals(5, fm1.get("fmVolumeEnvelopeMask"));
        assertEquals(Map.of("active", false), fm1.get("ssgEgPointer"));
        assertEquals(6, fm1.get("feedbackAlgorithm"));
        assertEquals(Map.of("assetKey", "music.aiz1", "cursor", 0x320L),
                fm1.get("totalLevelPointer"));

        Map<String, Object> fm2 = roleFields(state, FM2);
        assertEquals(Map.of("assetKey", "music.aiz1", "cursor", 0x360L), fm2.get("ssgEgPointer"));
        assertEquals(0, fm2.get("fmVolumeEnvelopeMask"));
        assertEquals(0, fm2.get("volumeEnvelope"));
        assertEquals("NORMAL", fm2.get("modulationMode"));
        assertEquals(Map.of("assetKey", "music.aiz1", "cursor", 0x380L),
                fm2.get("modulationPointer"));
        assertEquals(0x1234, fm2.get("modulationValue"));
        assertEquals(0, fm2.get("modulationEnvelopeSensitivity"));
        assertEquals("RAW", sharedCells(roleFields(state, FM4)).get(1).get("kind"));

        NormalizedState updating = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(withUpdatingSfx(globals(0, false), 1), music, new LiveSfx(sfx)), ASSETS);
        Map<String, Object> liveVoices = sharedCells(roleFields(updating, FM4)).get(1);
        assertEquals("VOICES_POINTER", liveVoices.get("kind"));
        assertEquals(Map.of("assetKey", "sfx.ring", "cursor", 0x340L), liveVoices.get("pointer"));
    }

    @Test
    void stackPointerMustDescribeExactlyTheLiveTwoEntryReturnStack() {
        Track source = track("music.aiz1", 0x100100, 0x80, 0x06);
        assertThrows(IllegalArgumentException.class, () -> withStack(source, 0x2d, List.of()));
        assertThrows(IllegalArgumentException.class, () -> withStack(source, 0x2e, List.of()));
        assertThrows(IllegalArgumentException.class, () -> withStack(source, 0x2a,
                List.of(source.dataPointer(), source.dataPointer(), source.dataPointer())));
    }

    @Test
    void uncheckedLoopIndexOverflowKeepsAllPreStackSharedBytesCanonical() {
        List<Track> first = musicTracks("music.aiz1", 0x100100);
        List<Track> second = musicTracks("music.aiz1", 0x100100);
        first.set(1, withSharedStorage(first.get(1),
                List.of(1, 2, 3, 4, 5, 6, 0x20, 0x01)));
        second.set(1, withSharedStorage(second.get(1),
                List.of(1, 2, 9, 4, 7, 6, 0x20, 0x01)));

        NormalizedState firstState = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), first, new LiveSfx(inactiveTracks(7))), ASSETS);
        NormalizedState secondState = S3kCompleteRunStateNormalizer.normalizeReference(
                new Snapshot(globals(0, false), second, new LiveSfx(inactiveTracks(7))), ASSETS);

        // cfRepeatAtPos adds the unchecked F7 index to $28, so both $2A and $2C
        // remain future-affecting raw storage while SP=$2E.
        assertNotEquals(firstState, secondState);
        List<Map<String, Object>> cells = sharedCells(roleFields(firstState, FM1));
        assertEquals(Map.of("kind", "RAW", "bytes", List.of(3, 4)), cells.get(1));
        assertEquals(Map.of("kind", "RAW", "bytes", List.of(5, 6)), cells.get(2));
        assertEquals("RETURN_POINTER", cells.get(3).get("kind"));
    }

    @Test
    void rejectsLiveSfxVoicesHintThatDisagreesWithRawSharedWord() {
        List<Track> sfx = inactiveTracks(7);
        Track live = unionTrack("sfx.ring", 0x0f8120, 0x80, 0x04,
                0x1234, 0, 0, null, 0, 0, null, 0,
                null, 0, 0, 0, 0, 0, new RomPointer("sfx.ring", 0x0f8340),
                0x30, List.of());
        sfx.set(1, withSharedStorage(live,
                List.of(1, 2, 0x41, 0x83, 5, 6, 7, 8)));
        Snapshot snapshot = new Snapshot(withUpdatingSfx(globals(0, false), 1),
                musicTracks("music.aiz1", 0x100100), new LiveSfx(sfx));

        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS));
    }

    @Test
    void rejectsReturnHintThatDisagreesWithRawSharedWord() {
        List<Track> music = musicTracks("music.aiz1", 0x100100);
        Track fm1 = music.get(1);
        music.set(1, withSharedStorage(fm1,
                List.of(1, 2, 3, 4, 5, 6, 0x21, 0x01)));
        Snapshot snapshot = new Snapshot(globals(0, false), music, new LiveSfx(inactiveTracks(7)));

        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS));
    }

    @Test
    void rejectsWrongTrackInventoryAndOutOfAssetPointers() {
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(globals(0, false),
                inactiveTracks(8), new LiveSfx(inactiveTracks(7))));

        List<Track> music = musicTracks("music.aiz1", 0x100100);
        music.set(0, track("music.aiz1", 0x101000, 0x80, 0x06));
        Snapshot badPointer = new Snapshot(globals(0, false), music, new LiveSfx(inactiveTracks(7)));
        assertThrows(IllegalArgumentException.class,
                () -> S3kCompleteRunStateNormalizer.normalizeReference(badPointer, ASSETS));
    }

    @Test
    void profileInventoryAcceptsTheStrictNormalizedState() {
        Snapshot snapshot = new Snapshot(globals(0, false), musicTracks("music.aiz1", 0x100100),
                new LiveSfx(inactiveTracks(7)));
        NormalizedState state = S3kCompleteRunStateNormalizer.normalizeReference(snapshot, ASSETS);

        S3kCompleteRunAudioProfile.profile().validateState(state);
        assertEquals(S3kCompleteRunStateNormalizer.GLOBAL_FIELDS,
                state.fields().stream().map(field -> field.name()).toList());
        assertEquals(S3kCompleteRunStateNormalizer.ACTIVE_ROLE_FIELDS,
                state.roles().get(0).fields().stream().map(field -> field.name()).toList());
    }

    private static DriverGlobals globals(int fadeToPrevious, boolean saved) {
        return new DriverGlobals(
                1, 2, List.of(0x01, 0x33, 0xbc), 0x08, 0x33, 0x01, 0x33, 0xbc,
                0x28, 6, 5, 0, 0, 0x90, fadeToPrevious, 0, 0x96,
                0xbc, 0x80, 5, 1, 0x40,
                saved ? new RomPointer("music.saved", 0x110040) : null,
                0x96, 0x20, 0x08, 3, 0x81, 2, 0,
                new RomPointer("music.aiz1", 0x100020), new RomPointer("driver", 0x700),
                new RomPointer("music.aiz1", 0x100030), new RomPointer("sfx.ring", 0x0f8010),
                1, 0x20, false);
    }

    private static List<Track> musicTracks(String assetKey, long pointer) {
        List<Track> tracks = new ArrayList<>();
        int[] voices = {0x06, 0x00, 0x01, 0x02, 0x04, 0x05, 0x80, 0xa0, 0xc0};
        for (int index = 0; index < voices.length; index++) {
            tracks.add(track(assetKey, pointer + index * 0x20L, 0x80, voices[index]));
        }
        return tracks;
    }

    private static List<Track> inactiveTracks(int count) {
        return new ArrayList<>(java.util.Collections.nCopies(count, Track.inactive()));
    }

    private static Track track(String assetKey, long pointer, int playback, int voiceControl) {
        RomPointer data = new RomPointer(assetKey, pointer);
        return new Track(true, data, playback, voiceControl, 1, 0xfe, 0x10,
                0x80, 2, 0x2e, 0xc0, 3, 4, 0x1234, 1, 0xff, 0x02,
                3, 4, 0, null, 0, 5, null, 6, 7, data, 0x1234,
                8, 9, 0xfe, 10, sharedStorage(data, 0x2e, List.of(data)), data, List.of(data));
    }

    private static Track withPlayback(Track source, int playback) {
        return new Track(source.populated(), source.dataPointer(), playback, source.voiceControl(),
                source.tempoDivider(), source.transpose(), source.volume(), source.modulationControl(),
                source.voiceIndex(), source.stackPointer(), source.amsFmsPan(), source.durationTimeout(),
                source.savedDuration(), source.frequencyOrDac(), source.voiceSongId(), source.detune(),
                source.unknown11(), source.volumeEnvelope(), source.fmVolumeEnvelope(),
                source.fmVolumeEnvelopeMask(), source.ssgEgPointer(), source.psgNoise(),
                source.feedbackAlgorithm(), source.totalLevelPointer(), source.noteFillTimeout(),
                source.noteFillMaster(), source.modulationPointer(), source.modulationValue(),
                source.modulationWait(), source.modulationSpeed(), source.modulationDelta(),
                source.modulationSteps(), source.sharedStorage(), source.voicesPointer(), source.returnStack());
    }

    private static Track unionTrack(String assetKey, long pointer, int playback, int voiceControl,
            int frequencyOrDac, int fmVolumeEnvelope, int fmVolumeEnvelopeMask, RomPointer ssgEgPointer,
            int psgNoise, int feedbackAlgorithm, RomPointer totalLevelPointer, int modulationControl,
            RomPointer modulationPointer, int modulationValue, int modulationWait, int modulationSpeed,
            int modulationDelta, int modulationSteps, RomPointer voicesPointer, int stackPointer,
            List<RomPointer> returnStack) {
        RomPointer data = new RomPointer(assetKey, pointer);
        return new Track(true, data, playback, voiceControl, 1, 0xfe, 0x10,
                modulationControl, 2, stackPointer, 0xc0, 3, 4, frequencyOrDac, 1, 0xff, 0x02,
                3, fmVolumeEnvelope, fmVolumeEnvelopeMask, ssgEgPointer, psgNoise,
                feedbackAlgorithm, totalLevelPointer, 6, 7, modulationPointer, modulationValue,
                modulationWait, modulationSpeed, modulationDelta, modulationSteps,
                sharedStorage(voicesPointer, stackPointer, returnStack), voicesPointer, returnStack);
    }

    private static List<Integer> sharedStorage(
            RomPointer voicesPointer, int stackPointer, List<RomPointer> returnStack) {
        List<Integer> bytes = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        if (voicesPointer != null) putWord(bytes, 2, voicesPointer.pointer());
        for (int index = 0; index < returnStack.size(); index++) {
            putWord(bytes, stackPointer - 0x28 + index * 2, returnStack.get(index).pointer());
        }
        return List.copyOf(bytes);
    }

    private static void putWord(List<Integer> bytes, int offset, long pointer) {
        bytes.set(offset, (int) pointer & 0xff);
        bytes.set(offset + 1, (int) (pointer >>> 8) & 0xff);
    }

    private static Track withStack(Track source, int stackPointer, List<RomPointer> returnStack) {
        return new Track(source.populated(), source.dataPointer(), source.playbackControl(), source.voiceControl(),
                source.tempoDivider(), source.transpose(), source.volume(), source.modulationControl(),
                source.voiceIndex(), stackPointer, source.amsFmsPan(), source.durationTimeout(),
                source.savedDuration(), source.frequencyOrDac(), source.voiceSongId(), source.detune(),
                source.unknown11(), source.volumeEnvelope(), source.fmVolumeEnvelope(),
                source.fmVolumeEnvelopeMask(), source.ssgEgPointer(), source.psgNoise(),
                source.feedbackAlgorithm(), source.totalLevelPointer(), source.noteFillTimeout(),
                source.noteFillMaster(), source.modulationPointer(), source.modulationValue(),
                source.modulationWait(), source.modulationSpeed(), source.modulationDelta(),
                source.modulationSteps(), source.sharedStorage(), source.voicesPointer(), returnStack);
    }

    private static Track withSharedStorage(Track source, List<Integer> sharedStorage) {
        return new Track(source.populated(), source.dataPointer(), source.playbackControl(), source.voiceControl(),
                source.tempoDivider(), source.transpose(), source.volume(), source.modulationControl(),
                source.voiceIndex(), source.stackPointer(), source.amsFmsPan(), source.durationTimeout(),
                source.savedDuration(), source.frequencyOrDac(), source.voiceSongId(), source.detune(),
                source.unknown11(), source.volumeEnvelope(), source.fmVolumeEnvelope(),
                source.fmVolumeEnvelopeMask(), source.ssgEgPointer(), source.psgNoise(),
                source.feedbackAlgorithm(), source.totalLevelPointer(), source.noteFillTimeout(),
                source.noteFillMaster(), source.modulationPointer(), source.modulationValue(),
                source.modulationWait(), source.modulationSpeed(), source.modulationDelta(),
                source.modulationSteps(), sharedStorage, source.voicesPointer(), source.returnStack());
    }

    private static DriverGlobals withUpdatingSfx(DriverGlobals source, int updatingSfx) {
        return new DriverGlobals(source.palFlag(), source.palDoubleUpdateCounter(), source.soundQueue(),
                source.tempoSpeedup(), source.nextSoundId(), source.musicInputId(), source.sfxInput0(),
                source.sfxInput1(), source.fadeOutTimeout(), source.fadeDelay(), source.fadeDelayTimeout(),
                source.pauseFlag(), source.haltFlag(), source.tempoAccumulator(), source.fadeToPreviousFlag(),
                updatingSfx, source.currentTempo(), source.continuousSfxId(), source.continuousSfxFlag(),
                source.spindashState(), source.ringSpeaker(), source.fadeInTimeout(),
                source.savedVoiceTablePointer(), source.savedCurrentTempo(), source.savedSongBank(),
                source.savedTempoSpeedup(), source.speedupTimeout(), source.dacIndex(), source.continuousLoop(),
                source.sfxSaveIndex(), source.songPosition(), source.trackInitPosition(), source.voiceTablePointer(),
                source.sfxVoiceTablePointer(), source.sfxTempoDivider(), source.songBank(), source.segaPcmPlaying());
    }

    private static Map<String, Object> roleFields(NormalizedState state, HardwareRole role) {
        return state.roles().stream().filter(candidate -> candidate.role() == role).findFirst().orElseThrow()
                .fields().stream().collect(LinkedHashMap::new,
                        (map, field) -> map.put(field.name(), field.value()), Map::putAll);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sharedCells(Map<String, Object> roleFields) {
        return (List<Map<String, Object>>) roleFields.get("sharedStackStorage");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> globalMap(NormalizedState state, String name) {
        return (Map<String, Object>) state.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().value();
    }
}
