package com.openggf.tools.audio.completerun.s1;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.StateField;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.openggf.tools.audio.completerun.s1.S1CompleteRunStateNormalizer.SourceLayer.MUSIC;
import static com.openggf.tools.audio.completerun.s1.S1CompleteRunStateNormalizer.SourceLayer.NORMAL_SFX;
import static com.openggf.tools.audio.completerun.s1.S1CompleteRunStateNormalizer.SourceLayer.SPECIAL_SFX;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1CompleteRunStateNormalizer {
    private static final Map<String, S1CompleteRunStateNormalizer.Asset> ASSETS = Map.of(
            "music.ghz", new S1CompleteRunStateNormalizer.Asset("music.ghz", 0x1000, 0x2000),
            "music.alt", new S1CompleteRunStateNormalizer.Asset("music.alt", 0x1000, 0x2000));
    private static final List<String> GLOBAL_FIELDS = List.of(
            "priority", "mainTempoTimeout", "mainTempo", "paused", "fadeOutCounter", "fadeOutDelay",
            "soundId", "queue0", "queue1", "queue2", "musicVoicePointer", "specialVoicePointer",
            "fadeIn", "fadeInDelay", "fadeInCounter", "oneUpPlaying", "tempoModifier", "speedUpTempo",
            "speedUp", "ringSpeaker", "pushPlaying", "sourceSlots", "savedMusic");
    private static final List<String> TRACK_FIELDS = List.of(
            "assetKey", "cursor", "resting", "voiceControl", "tempoDivider", "baseFrequency", "detune", "doNotAttack",
            "duration", "durationReload", "savedDac", "noteFillTimeout", "noteFillMaster", "envelopeCursor",
            "loopCounters", "modulationEnabled", "modulationCursor", "modulationWait", "modulationSpeed",
            "modulationDelta", "modulationSteps", "modulationValue", "overridden", "pan", "ams", "fms",
            "psgNoise", "fmFeedbackAlgorithm", "trackVoicePointer", "returnStack", "transpose", "voiceOrEnvelope",
            "volume");
    private static final List<String> RETAINED_GLOBAL_COMPONENTS = List.of(
            "priority", "mainTempoTimeout", "mainTempo", "paused", "fadeOutCounter", "fadeOutDelay",
            "soundId", "queue0", "queue1", "queue2", "musicVoicePointer", "specialVoicePointer",
            "fadeIn", "fadeInDelay", "fadeInCounter", "oneUpPlaying", "tempoModifier", "speedUpTempo",
            "speedUp", "ringSpeaker", "pushPlaying");
    private static final List<String> RETAINED_TRACK_COMPONENTS = List.of(
            "pointer", "resting", "tempoDivider", "baseFrequency", "detune", "doNotAttack",
            "duration", "durationReload", "savedDac", "noteFillTimeout", "noteFillMaster", "envelopeCursor",
            "loopCounters", "modulationEnabled", "modulationPointer", "modulationWait", "modulationSpeed",
            "modulationDelta", "modulationSteps", "modulationValue", "pan", "ams", "fms",
            "psgNoise", "fmFeedback", "transpose", "voiceOrEnvelope", "volume");

    @Test
    void retainsAllEighteenSlotsAndAppliesMusicThenNormalThenSpecialRomServicePrecedence() {
        var music = activeTrack("music.ghz", 0x1010, 10);
        var normal = activeTrack("music.ghz", 0x1020, 20);
        var special = activeTrack("music.ghz", 0x1030, 30);
        List<S1CompleteRunStateNormalizer.SourceSlot> slots = liveSlots(inactiveTrack(1));
        slots.set(4, slot(MUSIC, HardwareRole.FM4, replaceRecord(music, "overridden", true)));
        slots.set(11, slot(NORMAL_SFX, HardwareRole.FM4, normal));
        slots.set(16, slot(SPECIAL_SFX, HardwareRole.FM4,
                replaceRecord(special, "overridden", true)));
        var state = state(slots, savedSlots(activeTrack("music.ghz", 0x1020, 20)));

        NormalizedState normalized = S1CompleteRunStateNormalizer.normalizeReference(state, ASSETS);

        assertEquals(GLOBAL_FIELDS, normalized.fields().stream().map(StateField::name).toList());
        assertEquals(List.of(HardwareRole.values()), normalized.roles().stream().map(role -> role.role()).toList());
        assertEquals(TRACK_FIELDS, normalized.roles().get(4).fields().stream().map(StateField::name).toList());
        // UpdateMusic services music, then normal SFX ($80), then special SFX ($40). Normal
        // admission sets the special override bit, so its earlier normal write remains effective.
        assertEquals(0x20L, field(normalized.roles().get(4).fields(), "cursor"));
        assertEquals(Map.of("active", true, "assetKey", "music.ghz", "cursor", 0x21L),
                field(normalized.roles().get(4).fields(), "modulationCursor"));
        assertEquals(List.of(),
                field(normalized.roles().get(4).fields(), "returnStack"));
        assertEquals(12, ((List<?>) field(normalized.roles().get(4).fields(), "loopCounters")).size());
        List<?> canonicalSlots = (List<?>) field(normalized.fields(), "sourceSlots");
        assertEquals(18, canonicalSlots.size());
        assertSlot(canonicalSlots, 0, "MUSIC", "DAC", false);
        assertSlot(canonicalSlots, 4, "MUSIC", "FM4", true);
        assertSlot(canonicalSlots, 11, "NORMAL_SFX", "FM4", true);
        assertSlot(canonicalSlots, 16, "SPECIAL_SFX", "FM4", true);
        assertSlot(canonicalSlots, 17, "SPECIAL_SFX", "PSG3", false);
        Map<?, ?> saved = (Map<?, ?>) field(normalized.fields(), "savedMusic");
        Map<?, ?> savedFm1 = (Map<?, ?>) ((List<?>) saved.get("sourceSlots")).get(1);
        assertEquals(0x20L, savedFm1.get("cursor"));
        assertEquals(Map.of("active", true, "assetKey", "music.ghz", "cursor", 0x21L),
                savedFm1.get("modulationCursor"));
        assertEquals(List.of(), savedFm1.get("returnStack"));
        assertEquals(normalized, S1CompleteRunStateNormalizer.normalizeEngine(state, ASSETS));

        slots.set(11, slot(NORMAL_SFX, HardwareRole.FM4, inactiveTrack(21)));
        slots.set(16, slot(SPECIAL_SFX, HardwareRole.FM4, special));
        assertEquals(0x30L, field(S1CompleteRunStateNormalizer.normalizeReference(
                state(slots, savedSlots(activeTrack("music.ghz", 0x1020, 20))), ASSETS)
                .roles().get(4).fields(), "cursor"));
        slots.set(16, slot(SPECIAL_SFX, HardwareRole.FM4, inactiveTrack(31)));
        slots.set(4, slot(MUSIC, HardwareRole.FM4, music));
        assertEquals(0x10L, field(S1CompleteRunStateNormalizer.normalizeReference(
                state(slots, savedSlots(activeTrack("music.ghz", 0x1020, 20))), ASSETS)
                .roles().get(4).fields(), "cursor"));
    }

    @Test
    void everyRetainedLiveAndSavedGlobalChangesCanonicalBytes() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        byte[] expected = bytes(base);

        for (String component : RETAINED_GLOBAL_COMPONENTS) {
            var changed = new S1CompleteRunStateNormalizer.LiveState(
                    mutateGlobal(base.globals(), component), base.sourceSlots(), base.savedMusic());
            assertFalse(Arrays.equals(expected, bytes(changed)), "live global omitted: " + component);

            var saved = base.savedMusic();
            var changedSaved = new S1CompleteRunStateNormalizer.SavedMusic(
                    mutateGlobal(saved.globals(), component), saved.sourceSlots());
            changed = new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), base.sourceSlots(), changedSaved);
            assertFalse(Arrays.equals(expected, bytes(changed)), "saved global omitted: " + component);
        }
    }

    @Test
    void everyRetainedLiveAndSavedTrackFieldChangesCanonicalBytes() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        byte[] expected = bytes(base);

        for (String component : RETAINED_TRACK_COMPONENTS) {
            int musicSlot = applicableMusicSlot(component);
            List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
            S1CompleteRunStateNormalizer.SourceSlot source = live.get(musicSlot);
            live.set(musicSlot, slot(
                    source.layer(), source.role(), mutateTrack(source.track(), component)));
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), live, base.savedMusic()))), "live track field omitted: " + component);

            List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(base.savedMusic().sourceSlots());
            source = saved.get(musicSlot);
            saved.set(musicSlot, slot(
                    source.layer(), source.role(), mutateTrack(source.track(), component)));
            var changedSaved = new S1CompleteRunStateNormalizer.SavedMusic(base.savedMusic().globals(), saved);
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), base.sourceSlots(), changedSaved))), "saved track field omitted: " + component);
        }
    }

    @Test
    void activeMusicAssetIdentityChangesAsOneSourceOwnedLayerAndRejectsMixedAssets() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        byte[] expected = bytes(base);

        List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
        for (int index = 0; index < 10; index++) {
            var source = live.get(index);
            live.set(index, slot(source.layer(), source.role(),
                    replaceRecord(source.track(), "assetKey", "music.alt")));
        }
        var liveGlobals = replaceRecord(base.globals(), "musicVoicePointer",
                new S1CompleteRunStateNormalizer.RomPointer("music.alt", 0x1100));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                liveGlobals, live, base.savedMusic()))));

        List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(
                base.savedMusic().sourceSlots());
        for (int index = 0; index < saved.size(); index++) {
            var source = saved.get(index);
            saved.set(index, slot(source.layer(), source.role(),
                    replaceRecord(source.track(), "assetKey", "music.alt")));
        }
        var savedGlobals = replaceRecord(base.savedMusic().globals(), "musicVoicePointer",
                new S1CompleteRunStateNormalizer.RomPointer("music.alt", 0x1128));
        var changedSaved = new S1CompleteRunStateNormalizer.SavedMusic(savedGlobals, saved);
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(), changedSaved))));

        live = new ArrayList<>(base.sourceSlots());
        var fm1 = live.get(1);
        live.set(1, slot(fm1.layer(), fm1.role(),
                replaceRecord(fm1.track(), "assetKey", "music.alt")));
        List<S1CompleteRunStateNormalizer.SourceSlot> mixedAssets = live;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), mixedAssets, base.savedMusic())));
    }

    @Test
    void inactiveLiveAndSavedCapacityCannotLeakAnyStaleTrackField() {
        var first = state(liveSlots(inactiveTrack(1)), savedSlots(inactiveTrack(2)));
        var second = state(liveSlots(inactiveTrack(101)), savedSlots(inactiveTrack(202)));

        var cleared = inactiveTrack(3);
        cleared = replaceRecord(cleared, "assetKey", null);
        cleared = replaceRecord(cleared, "loopCounters", List.of(999));
        cleared = replaceRecord(cleared, "stackPointer", 0);
        cleared = replaceRecord(cleared, "tempoDivider", 999);
        var clearedState = state(liveSlots(cleared), savedSlots(cleared));

        assertArrayEquals(bytes(first), bytes(second));
        assertArrayEquals(bytes(first), bytes(clearedState));
        NormalizedState normalized = S1CompleteRunStateNormalizer.normalizeReference(first, ASSETS);
        assertTrue(normalized.roles().stream().noneMatch(role -> role.active() || !role.fields().isEmpty()));
        List<?> live = (List<?>) field(normalized.fields(), "sourceSlots");
        assertTrue(live.stream().map(value -> (Map<?, ?>) value)
                .allMatch(slot -> slot.size() == 3 && Boolean.FALSE.equals(slot.get("active"))));
        Map<?, ?> saved = (Map<?, ?>) field(normalized.fields(), "savedMusic");
        assertEquals(10, ((List<?>) saved.get("sourceSlots")).size());
        assertTrue(((List<?>) saved.get("sourceSlots")).stream().map(value -> (Map<?, ?>) value)
                .allMatch(slot -> slot.size() == 3 && Boolean.FALSE.equals(slot.get("active"))));
    }

    @Test
    void savedMusicExistsOnlyWhileTheLiveOneUpOwnerIsActive() {
        List<S1CompleteRunStateNormalizer.SourceSlot> live = liveSlots(inactiveTrack(1));
        var withoutOneUp = replaceRecord(globals(0), "oneUpPlaying", false);
        var firstSaved = new S1CompleteRunStateNormalizer.SavedMusic(
                globals(40), savedSlots(inactiveTrack(2)));
        var staleSaved = new S1CompleteRunStateNormalizer.SavedMusic(
                replaceRecord(globals(140), "ringSpeaker", 2),
                savedSlots(inactiveTrack(102)));

        byte[] expected = bytes(new S1CompleteRunStateNormalizer.LiveState(
                withoutOneUp, live, firstSaved));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                withoutOneUp, live, staleSaved)));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                withoutOneUp, live, null)));
        assertEquals(Map.of("active", false), field(S1CompleteRunStateNormalizer.normalizeReference(
                new S1CompleteRunStateNormalizer.LiveState(withoutOneUp, live, staleSaved), ASSETS)
                .fields(), "savedMusic"));

        assertThrows(NullPointerException.class, () -> bytes(new S1CompleteRunStateNormalizer.LiveState(
                globals(0), live, null)));
    }

    @Test
    void musicPointerNeedsAnActiveFmOwnerButFixBugsSpecialPointerRemainsFutureAffecting() {
        var inactive = state(liveSlots(inactiveTrack(1)), savedSlots(inactiveTrack(2)));
        byte[] expected = bytes(inactive);
        var changedLiveMusic = new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(inactive.globals(), "musicVoicePointer"), inactive.sourceSlots(), inactive.savedMusic());
        var changedLiveSpecial = new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(inactive.globals(), "specialVoicePointer"), inactive.sourceSlots(), inactive.savedMusic());
        var changedSavedMusic = new S1CompleteRunStateNormalizer.SavedMusic(
                mutateRecord(inactive.savedMusic().globals(), "musicVoicePointer"),
                inactive.savedMusic().sourceSlots());
        var changedSavedSpecial = new S1CompleteRunStateNormalizer.SavedMusic(
                mutateRecord(inactive.savedMusic().globals(), "specialVoicePointer"),
                inactive.savedMusic().sourceSlots());

        assertArrayEquals(expected, bytes(changedLiveMusic));
        assertFalse(Arrays.equals(expected, bytes(changedLiveSpecial)));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                inactive.globals(), inactive.sourceSlots(), changedSavedMusic)));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                inactive.globals(), inactive.sourceSlots(), changedSavedSpecial))));

        var uninitialized = new S1CompleteRunStateNormalizer.LiveState(
                replaceRecord(inactive.globals(), "specialVoicePointer", null),
                inactive.sourceSlots(), inactive.savedMusic());
        var rawZero = new S1CompleteRunStateNormalizer.LiveState(
                replaceRecord(inactive.globals(), "specialVoicePointer",
                        new S1CompleteRunStateNormalizer.RomPointer("music.ghz", 0)),
                inactive.sourceSlots(), inactive.savedMusic());
        assertArrayEquals(bytes(uninitialized), bytes(rawZero));

        var active = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        assertFalse(Arrays.equals(bytes(active), bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(active.globals(), "musicVoicePointer"), active.sourceSlots(), active.savedMusic()))));
        assertFalse(Arrays.equals(bytes(active), bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(active.globals(), "specialVoicePointer"), active.sourceSlots(), active.savedMusic()))));

        var foreignMusic = replaceRecord(active.globals(), "musicVoicePointer",
                new S1CompleteRunStateNormalizer.RomPointer("music.alt", 0x1100));
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        foreignMusic, active.sourceSlots(), active.savedMusic())));
        var foreignSpecial = replaceRecord(active.globals(), "specialVoicePointer",
                new S1CompleteRunStateNormalizer.RomPointer("music.alt", 0x1200));
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        foreignSpecial, active.sourceSlots(), active.savedMusic())));

        var postOneUpClear = new S1CompleteRunStateNormalizer.LiveState(
                replaceRecord(active.globals(), "specialVoicePointer", null),
                active.sourceSlots(), active.savedMusic());
        var postOneUpRawZero = new S1CompleteRunStateNormalizer.LiveState(
                replaceRecord(active.globals(), "specialVoicePointer",
                        new S1CompleteRunStateNormalizer.RomPointer("music.ghz", 0)),
                active.sourceSlots(), active.savedMusic());
        assertArrayEquals(bytes(postOneUpClear), bytes(postOneUpRawZero));
        assertEquals(Map.of("active", false), field(
                S1CompleteRunStateNormalizer.normalizeReference(postOneUpClear, ASSETS).fields(),
                "specialVoicePointer"));
    }

    @Test
    void musicPointerRequiresFmRatherThanDacOrPsgButSpecialPointerRetainsFixBugsAlias() {
        List<S1CompleteRunStateNormalizer.SourceSlot> live = liveSlots(inactiveTrack(1));
        live.set(0, slot(MUSIC, HardwareRole.DAC, activeTrack("music.ghz", 0x1010, 10)));
        live.set(7, slot(MUSIC, HardwareRole.PSG1, activeTrack("music.ghz", 0x1020, 11)));
        live.set(17, slot(SPECIAL_SFX, HardwareRole.PSG3,
                activeTrack("music.ghz", 0x1030, 12)));
        List<S1CompleteRunStateNormalizer.SourceSlot> saved = savedSlots(inactiveTrack(2));
        saved.set(0, slot(MUSIC, HardwareRole.DAC, activeTrack("music.ghz", 0x1040, 13)));
        saved.set(7, slot(MUSIC, HardwareRole.PSG1, activeTrack("music.ghz", 0x1050, 14)));
        var tailOnly = state(live, saved);
        byte[] expected = bytes(tailOnly);

        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(tailOnly.globals(), "musicVoicePointer"),
                tailOnly.sourceSlots(), tailOnly.savedMusic())));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(tailOnly.globals(), "specialVoicePointer"),
                tailOnly.sourceSlots(), tailOnly.savedMusic()))));
        var changedSaved = new S1CompleteRunStateNormalizer.SavedMusic(
                mutateRecord(tailOnly.savedMusic().globals(), "musicVoicePointer"),
                tailOnly.savedMusic().sourceSlots());
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                tailOnly.globals(), tailOnly.sourceSlots(), changedSaved)));

        live.set(1, slot(MUSIC, HardwareRole.FM1, activeTrack("music.ghz", 0x1060, 15)));
        live.set(16, slot(SPECIAL_SFX, HardwareRole.FM4,
                activeTrack("music.ghz", 0x1070, 16)));
        saved.set(1, slot(MUSIC, HardwareRole.FM1, activeTrack("music.ghz", 0x1080, 17)));
        var fmOwned = state(live, saved);
        assertFalse(Arrays.equals(bytes(fmOwned), bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(fmOwned.globals(), "musicVoicePointer"),
                fmOwned.sourceSlots(), fmOwned.savedMusic()))));
        assertFalse(Arrays.equals(bytes(fmOwned), bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(fmOwned.globals(), "specialVoicePointer"),
                fmOwned.sourceSlots(), fmOwned.savedMusic()))));
    }

    @Test
    void retainsEveryLoopStorageByteAndBoundsTheProjectedReturnStack() {
        var liveTrack = replaceRecord(activeTrack("music.ghz", 0x1010, 10), "stackPointer", 0x30);
        var savedTrack = replaceRecord(activeTrack("music.ghz", 0x1020, 20), "stackPointer", 0x30);
        var base = state(liveSlots(liveTrack), savedSlots(savedTrack));
        byte[] expected = bytes(base);
        S1CompleteRunStateNormalizer.Track source = base.sourceSlots().getFirst().track();
        for (int index = 0; index < 12; index++) {
            List<Integer> counters = new ArrayList<>(source.loopCounters());
            counters.set(index, counters.get(index) + 1);
            List<S1CompleteRunStateNormalizer.SourceSlot> slots = new ArrayList<>(base.sourceSlots());
            slots.set(0, slot(MUSIC, HardwareRole.DAC, replaceRecord(source, "loopCounters", counters)));
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), slots, base.savedMusic()))), "loop/stack storage byte omitted: " + index);

            S1CompleteRunStateNormalizer.Track savedSource = base.savedMusic().sourceSlots().getFirst().track();
            List<Integer> savedCounters = new ArrayList<>(savedSource.loopCounters());
            savedCounters.set(index, savedCounters.get(index) + 1);
            List<S1CompleteRunStateNormalizer.SourceSlot> savedSlots = new ArrayList<>(
                    base.savedMusic().sourceSlots());
            savedSlots.set(0, slot(MUSIC, HardwareRole.DAC,
                    replaceRecord(savedSource, "loopCounters", savedCounters)));
            var saved = new S1CompleteRunStateNormalizer.SavedMusic(
                    base.savedMusic().globals(), savedSlots);
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), base.sourceSlots(), saved))),
                    "saved loop/stack storage byte omitted: " + index);
        }
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(source, "loopCounters", List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(source, "stackPointer", 0x25));

        var referenceDeep = withReturnStack(activeTrack("music.ghz", 0x1010, 10),
                List.of(0x1010L, 0x1012L, 0x1014L));
        var referenceState = state(liveSlots(referenceDeep), savedSlots(withReturnStack(
                activeTrack("music.ghz", 0x1020, 20),
                List.of(0x1020L, 0x1022L, 0x1024L))));
        NormalizedState normalized = S1CompleteRunStateNormalizer.normalizeReference(
                referenceState, ASSETS);
        assertEquals(List.of(), field(normalized.roles().get(1).fields(), "loopCounters"));
        assertEquals(List.of(0x12L, 0x14L, 0x16L),
                field(normalized.roles().get(1).fields(), "returnStack"));
        Map<?, ?> saved = (Map<?, ?>) field(normalized.fields(), "savedMusic");
        Map<?, ?> savedFm1 = (Map<?, ?>) ((List<?>) saved.get("sourceSlots")).get(1);
        assertEquals(List.of(0x22L, 0x24L, 0x26L), savedFm1.get("returnStack"));

        var engineDeep = withReturnStack(activeTrack("music.ghz", 0x1010, 10),
                List.of(0x1012L, 0x1014L, 0x1016L));
        var engineState = state(liveSlots(engineDeep), savedSlots(withReturnStack(
                activeTrack("music.ghz", 0x1020, 20),
                List.of(0x1022L, 0x1024L, 0x1026L))));
        assertEquals(normalized, S1CompleteRunStateNormalizer.normalizeEngine(engineState, ASSETS));

        var badPointer = withReturnStack(activeTrack("music.ghz", 0x1010, 10), List.of(0x1fffL));
        assertThrows(IllegalArgumentException.class, () -> bytes(
                state(liveSlots(badPointer), savedSlots(savedTrack))));
    }

    @Test
    void trackVoicePointerIsNormalizedOnlyForFmSfxSlotsAndStaleEverywhereElse() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        byte[] expected = bytes(base);

        List<S1CompleteRunStateNormalizer.SourceSlot> normalSfx = new ArrayList<>(base.sourceSlots());
        S1CompleteRunStateNormalizer.SourceSlot fm3 = normalSfx.get(10);
        normalSfx.set(10, slot(fm3.layer(), fm3.role(), mutateRecord(fm3.track(), "trackVoicePointer")));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), normalSfx, base.savedMusic()))));

        List<S1CompleteRunStateNormalizer.SourceSlot> music = new ArrayList<>(base.sourceSlots());
        S1CompleteRunStateNormalizer.SourceSlot musicFm3 = music.get(3);
        music.set(3, slot(musicFm3.layer(), musicFm3.role(),
                mutateRecord(musicFm3.track(), "trackVoicePointer")));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), music, base.savedMusic())));

        List<S1CompleteRunStateNormalizer.SourceSlot> special = new ArrayList<>(base.sourceSlots());
        S1CompleteRunStateNormalizer.SourceSlot specialFm4 = special.get(16);
        special.set(16, slot(specialFm4.layer(), specialFm4.role(),
                mutateRecord(specialFm4.track(), "trackVoicePointer")));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), special, base.savedMusic())));

        List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(base.savedMusic().sourceSlots());
        S1CompleteRunStateNormalizer.SourceSlot savedFm3 = saved.get(3);
        saved.set(3, slot(savedFm3.layer(), savedFm3.role(),
                mutateRecord(savedFm3.track(), "trackVoicePointer")));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(),
                new S1CompleteRunStateNormalizer.SavedMusic(base.savedMusic().globals(), saved))));

        normalSfx = new ArrayList<>(base.sourceSlots());
        fm3 = normalSfx.get(10);
        var foreign = new S1CompleteRunStateNormalizer.RomPointer("music.alt", 0x1013);
        normalSfx.set(10, slot(fm3.layer(), fm3.role(),
                replaceRecord(fm3.track(), "trackVoicePointer", foreign)));
        List<S1CompleteRunStateNormalizer.SourceSlot> foreignVoice = normalSfx;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), foreignVoice, base.savedMusic())));
    }

    @Test
    void voiceControlAndOverrideBitsRetainOnlySourceLegalState() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        byte[] expected = bytes(base);

        List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
        var psg3 = live.get(9);
        live.set(9, new S1CompleteRunStateNormalizer.SourceSlot(
                psg3.layer(), psg3.role(), replaceRecord(psg3.track(), "voiceControl", 0xc0)));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), live, base.savedMusic()))));

        List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(
                base.savedMusic().sourceSlots());
        psg3 = saved.get(9);
        saved.set(9, new S1CompleteRunStateNormalizer.SourceSlot(
                psg3.layer(), psg3.role(), replaceRecord(psg3.track(), "voiceControl", 0xc0)));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(), new S1CompleteRunStateNormalizer.SavedMusic(
                        base.savedMusic().globals(), saved)))));

        live = new ArrayList<>(base.sourceSlots());
        var musicFm4 = live.get(4);
        live.set(4, slot(musicFm4.layer(), musicFm4.role(),
                mutateRecord(musicFm4.track(), "overridden")));
        assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), live, base.savedMusic()))));

        live = new ArrayList<>(base.sourceSlots());
        var specialFm4 = live.get(16);
        live.set(16, slot(specialFm4.layer(), specialFm4.role(),
                replaceRecord(specialFm4.track(), "overridden", false)));
        List<S1CompleteRunStateNormalizer.SourceSlot> invalidOverride = live;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), invalidOverride, base.savedMusic())));

        live = new ArrayList<>(base.sourceSlots());
        var fm1 = live.get(1);
        live.set(1, new S1CompleteRunStateNormalizer.SourceSlot(
                fm1.layer(), fm1.role(), replaceRecord(fm1.track(), "voiceControl", 2)));
        List<S1CompleteRunStateNormalizer.SourceSlot> invalidVoice = live;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), invalidVoice, base.savedMusic())));
    }

    @Test
    void activeTracksSuppressEveryRoleInapplicableUnionField() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));

        for (String component : List.of(
                "resting", "baseFrequency", "detune", "doNotAttack", "noteFillTimeout", "noteFillMaster",
                "envelopeCursor", "modulationEnabled", "modulationPointer", "modulationWait",
                "modulationSpeed", "modulationDelta", "modulationSteps", "modulationValue",
                "psgNoise", "fmFeedback", "trackVoicePointer", "transpose", "voiceOrEnvelope",
                "volume")) {
            assertRoleInapplicableMutationIsSuppressed(base, 0, component);
        }
        for (String component : List.of("savedDac", "envelopeCursor", "psgNoise", "trackVoicePointer")) {
            assertRoleInapplicableMutationIsSuppressed(base, 1, component);
        }
        for (String component : List.of(
                "savedDac", "pan", "ams", "fms", "fmFeedback", "trackVoicePointer")) {
            assertRoleInapplicableMutationIsSuppressed(base, 7, component);
        }

        byte[] expected = bytes(base);
        for (String component : List.of("pan", "ams", "fms")) {
            List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
            var dac = live.getFirst();
            live.set(0, slot(dac.layer(), dac.role(), mutateTrack(dac.track(), component)));
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), live, base.savedMusic()))), "DAC omitted live " + component);

            List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(
                    base.savedMusic().sourceSlots());
            dac = saved.getFirst();
            saved.set(0, slot(dac.layer(), dac.role(), mutateTrack(dac.track(), component)));
            assertFalse(Arrays.equals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                    base.globals(), base.sourceSlots(), new S1CompleteRunStateNormalizer.SavedMusic(
                            base.savedMusic().globals(), saved)))), "DAC omitted saved " + component);
        }
    }

    @Test
    void modulationCursorDistinguishesUninitializedFromDisabledButInitializedStorage() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));

        List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
        var fm1 = live.get(1);
        var uninitialized = uninitializedModulation(fm1.track());
        live.set(1, slot(fm1.layer(), fm1.role(), uninitialized));
        NormalizedState zero = S1CompleteRunStateNormalizer.normalizeReference(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), live, base.savedMusic()), ASSETS);
        assertEquals(Map.of("active", false),
                field(zero.roles().get(1).fields(), "modulationCursor"));
        assertEquals(false, field(zero.roles().get(1).fields(), "modulationEnabled"));

        live = new ArrayList<>(base.sourceSlots());
        fm1 = live.get(1);
        var disabled = replaceRecord(fm1.track(), "modulationEnabled", false);
        live.set(1, slot(fm1.layer(), fm1.role(), disabled));
        NormalizedState retained = S1CompleteRunStateNormalizer.normalizeReference(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), live, base.savedMusic()), ASSETS);
        assertEquals(Map.of("active", true, "assetKey", "music.ghz", "cursor", 0x11L),
                field(retained.roles().get(1).fields(), "modulationCursor"));
        assertEquals(false, field(retained.roles().get(1).fields(), "modulationEnabled"));

        var impossible = replaceRecord(uninitialized, "modulationEnabled", true);
        List<S1CompleteRunStateNormalizer.SourceSlot> invalid = new ArrayList<>(base.sourceSlots());
        invalid.set(1, slot(MUSIC, HardwareRole.FM1, impossible));
        List<S1CompleteRunStateNormalizer.SourceSlot> impossibleStorage = invalid;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), impossibleStorage, base.savedMusic())));

        var truncated = replaceRecord(fm1.track(), "modulationPointer", 0x1ffdL);
        invalid = new ArrayList<>(base.sourceSlots());
        invalid.set(1, slot(MUSIC, HardwareRole.FM1, truncated));
        List<S1CompleteRunStateNormalizer.SourceSlot> truncatedStorage = invalid;
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), truncatedStorage, base.savedMusic())));
    }

    @Test
    void signedRamFormsCanonicalizeTogetherAndImpossibleScalarsFailClosed() {
        var base = state(liveSlots(activeTrack("music.ghz", 0x1010, 10)),
                savedSlots(activeTrack("music.ghz", 0x1020, 20)));
        for (String component : List.of("detune", "modulationDelta", "transpose", "volume")) {
            assertSignedFormsEqual(base, component, 0xfe, -2);
        }
        assertSignedFormsEqual(base, "modulationValue", 0xfffe, -2);
        assertSignedFormsEqual(base, "fmFeedback", 0xf9, 0x01);

        var track = activeTrack("music.ghz", 0x1010, 10);
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(track, "tempoDivider", 0x100));
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(track, "baseFrequency", 0x1_0000));
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(track, "detune", -129));
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(track, "modulationValue", -32769));
        assertMalformedFmField(base, "ams", 4);
        assertMalformedFmField(base, "fms", 8);
        assertMalformedFmField(base, "pan", 1);
        assertThrows(IllegalArgumentException.class, () -> replaceRecord(base.globals(), "priority", 0x100));
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        replaceRecord(base.globals(), "ringSpeaker", 2),
                        base.sourceSlots(), base.savedMusic())));
    }

    @Test
    void rejectsMalformedLiveAndSavedSourceSlotCardinalityOrderAndDuplicates() {
        var track = inactiveTrack(1);
        List<S1CompleteRunStateNormalizer.SourceSlot> live = liveSlots(track);
        List<S1CompleteRunStateNormalizer.SourceSlot> saved = savedSlots(track);

        assertThrows(IllegalArgumentException.class, () -> state(live.subList(0, 17), saved));
        var wrongOrder = new ArrayList<>(live);
        java.util.Collections.swap(wrongOrder, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> state(wrongOrder, saved));
        var duplicate = new ArrayList<>(live);
        duplicate.set(1, duplicate.getFirst());
        assertThrows(IllegalArgumentException.class, () -> state(duplicate, saved));

        assertThrows(IllegalArgumentException.class, () -> state(live, saved.subList(0, 9)));
        var wrongSavedOrder = new ArrayList<>(saved);
        java.util.Collections.swap(wrongSavedOrder, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> state(live, wrongSavedOrder));
        var wrongSavedLayer = new ArrayList<>(saved);
        wrongSavedLayer.set(0, slot(NORMAL_SFX, HardwareRole.DAC, track));
        assertThrows(IllegalArgumentException.class, () -> state(live, wrongSavedLayer));
    }

    @Test
    void completedServiceInvariantIsRequiredBeforeEphemeralFlagsAreOmitted() {
        var base = state(liveSlots(inactiveTrack(1)), savedSlots(inactiveTrack(2)));

        assertThrows(IllegalArgumentException.class, () -> bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(base.globals(), "updatingDac"), base.sourceSlots(), base.savedMusic())));
        assertThrows(IllegalArgumentException.class, () -> bytes(new S1CompleteRunStateNormalizer.LiveState(
                mutateRecord(base.globals(), "voiceSelector"), base.sourceSlots(), base.savedMusic())));
        assertThrows(IllegalArgumentException.class, () -> bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(), new S1CompleteRunStateNormalizer.SavedMusic(
                        mutateRecord(base.savedMusic().globals(), "updatingDac"), base.savedMusic().sourceSlots()))));
        assertThrows(IllegalArgumentException.class, () -> bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(), new S1CompleteRunStateNormalizer.SavedMusic(
                        mutateRecord(base.savedMusic().globals(), "voiceSelector"), base.savedMusic().sourceSlots()))));

        var ordinary = replaceRecord(replaceRecord(base.globals(), "paused", false), "voiceSelector", 0x40);
        assertEquals(0x40, ordinary.voiceSelector());
        S1CompleteRunStateNormalizer.normalizeReference(new S1CompleteRunStateNormalizer.LiveState(
                ordinary, base.sourceSlots(), base.savedMusic()), ASSETS);
        var impossiblePaused = replaceRecord(base.globals(), "voiceSelector", 0x40);
        assertThrows(IllegalArgumentException.class, () -> S1CompleteRunStateNormalizer.normalizeReference(
                new S1CompleteRunStateNormalizer.LiveState(
                        impossiblePaused, base.sourceSlots(), base.savedMusic()), ASSETS));
        var impossibleSelector = replaceRecord(replaceRecord(base.globals(), "paused", false),
                "voiceSelector", 0x80);
        assertThrows(IllegalArgumentException.class, () -> S1CompleteRunStateNormalizer.normalizeReference(
                new S1CompleteRunStateNormalizer.LiveState(
                        impossibleSelector, base.sourceSlots(), base.savedMusic()), ASSETS));
    }

    @Test
    void profileInventoryMatchesTheSourceAuditedNormalizerContract() {
        var profile = CompleteRunAudioProfiles.require(S1CompleteRunAudioProfile.ID);
        assertEquals(GLOBAL_FIELDS, profile.stateInventory().globalFields());
        assertEquals(TRACK_FIELDS, profile.stateInventory().activeRoleFields());
        assertEquals(10, profile.hardwareRoles().size());
        assertEquals(List.of(HardwareRole.values()), profile.hardwareRoles());
    }

    private static S1CompleteRunStateNormalizer.LiveState state(
            List<S1CompleteRunStateNormalizer.SourceSlot> live,
            List<S1CompleteRunStateNormalizer.SourceSlot> saved) {
        return new S1CompleteRunStateNormalizer.LiveState(globals(0), live,
                new S1CompleteRunStateNormalizer.SavedMusic(globals(40), saved));
    }

    private static S1CompleteRunStateNormalizer.DriverGlobals globals(int offset) {
        return new S1CompleteRunStateNormalizer.DriverGlobals(
                1 + offset, 2 + offset, 3 + offset, true, 4 + offset, 5 + offset, 6 + offset,
                List.of(7 + offset, 8 + offset, 9 + offset),
                new S1CompleteRunStateNormalizer.RomPointer("music.ghz", 0x1100 + offset),
                new S1CompleteRunStateNormalizer.RomPointer("music.ghz", 0x1200 + offset),
                true, 10 + offset, 11 + offset, true, 12 + offset, 13 + offset, true,
                offset == 0 ? 0 : 1, true, 0, 0);
    }

    private static List<S1CompleteRunStateNormalizer.SourceSlot> liveSlots(
            S1CompleteRunStateNormalizer.Track track) {
        return new ArrayList<>(List.of(
                configuredSlot(MUSIC, HardwareRole.DAC, track, false),
                configuredSlot(MUSIC, HardwareRole.FM1, track, false),
                configuredSlot(MUSIC, HardwareRole.FM2, track, false),
                configuredSlot(MUSIC, HardwareRole.FM3, track, true),
                configuredSlot(MUSIC, HardwareRole.FM4, track, true),
                configuredSlot(MUSIC, HardwareRole.FM5, track, true),
                configuredSlot(MUSIC, HardwareRole.FM6, track, false),
                configuredSlot(MUSIC, HardwareRole.PSG1, track, true),
                configuredSlot(MUSIC, HardwareRole.PSG2, track, true),
                configuredSlot(MUSIC, HardwareRole.PSG3, track, true),
                configuredSlot(NORMAL_SFX, HardwareRole.FM3, track, false),
                configuredSlot(NORMAL_SFX, HardwareRole.FM4, track, false),
                configuredSlot(NORMAL_SFX, HardwareRole.FM5, track, false),
                configuredSlot(NORMAL_SFX, HardwareRole.PSG1, track, false),
                configuredSlot(NORMAL_SFX, HardwareRole.PSG2, track, false),
                configuredSlot(NORMAL_SFX, HardwareRole.PSG3, track, false),
                configuredSlot(SPECIAL_SFX, HardwareRole.FM4, track, true),
                configuredSlot(SPECIAL_SFX, HardwareRole.PSG3, track, true)));
    }

    private static List<S1CompleteRunStateNormalizer.SourceSlot> savedSlots(
            S1CompleteRunStateNormalizer.Track track) {
        return new ArrayList<>(List.of(
                configuredSlot(MUSIC, HardwareRole.DAC, track, false),
                configuredSlot(MUSIC, HardwareRole.FM1, track, false),
                configuredSlot(MUSIC, HardwareRole.FM2, track, false),
                configuredSlot(MUSIC, HardwareRole.FM3, track, false),
                configuredSlot(MUSIC, HardwareRole.FM4, track, false),
                configuredSlot(MUSIC, HardwareRole.FM5, track, false),
                configuredSlot(MUSIC, HardwareRole.FM6, track, false),
                configuredSlot(MUSIC, HardwareRole.PSG1, track, false),
                configuredSlot(MUSIC, HardwareRole.PSG2, track, false),
                configuredSlot(MUSIC, HardwareRole.PSG3, track, false)));
    }

    private static S1CompleteRunStateNormalizer.SourceSlot slot(
            S1CompleteRunStateNormalizer.SourceLayer layer, HardwareRole role,
            S1CompleteRunStateNormalizer.Track track) {
        return new S1CompleteRunStateNormalizer.SourceSlot(
                layer, role, replaceRecord(track, "voiceControl", voiceControl(role)));
    }

    private static S1CompleteRunStateNormalizer.SourceSlot configuredSlot(
            S1CompleteRunStateNormalizer.SourceLayer layer, HardwareRole role,
            S1CompleteRunStateNormalizer.Track track, boolean overridden) {
        return slot(layer, role, replaceRecord(track, "overridden", overridden));
    }

    private static S1CompleteRunStateNormalizer.Track activeTrack(String assetKey, long pointer, int value) {
        List<Integer> storage = new ArrayList<>(java.util.Collections.nCopies(12, value));
        return new S1CompleteRunStateNormalizer.Track(true, assetKey, pointer, 0, false, value,
                value, value, true, value, value, value, value, value, value,
                storage, 0x30, true,
                pointer + 1, value, value, value, value, value, true, 0xc0, value & 3, value & 7,
                value, value, new S1CompleteRunStateNormalizer.RomPointer(assetKey, pointer + 3),
                value, value, value);
    }

    private static S1CompleteRunStateNormalizer.Track inactiveTrack(int value) {
        return new S1CompleteRunStateNormalizer.Track(false, value % 2 == 0 ? "music.alt" : "music.ghz",
                0x1000L + value, 0, value % 2 == 0, value, value, value, value % 2 != 0, value, value,
                value, value, value, value, java.util.Collections.nCopies(12, value), 0x30,
                value % 2 == 0,
                0x1100L + value, value, value,
                value, value, value, value % 2 != 0, value, value & 3, value & 7, value, value,
                new S1CompleteRunStateNormalizer.RomPointer(
                        value % 2 == 0 ? "music.alt" : "music.ghz", 0x1150L + value),
                value, value, value);
    }

    private static int voiceControl(HardwareRole role) {
        return switch (role) {
            case DAC, FM6 -> 6;
            case FM1 -> 0;
            case FM2 -> 1;
            case FM3 -> 2;
            case FM4 -> 4;
            case FM5 -> 5;
            case PSG1 -> 0x80;
            case PSG2 -> 0xa0;
            case PSG3 -> 0xe0;
        };
    }

    private static void appendLong(List<Integer> bytes, long value) {
        bytes.add((int) (value >>> 24) & 0xff);
        bytes.add((int) (value >>> 16) & 0xff);
        bytes.add((int) (value >>> 8) & 0xff);
        bytes.add((int) value & 0xff);
    }

    private static byte[] bytes(S1CompleteRunStateNormalizer.LiveState state) {
        return S1CompleteRunStateNormalizer.canonicalBytes(state, ASSETS);
    }

    private static int applicableMusicSlot(String component) {
        return switch (component) {
            case "savedDac" -> 0;
            case "envelopeCursor" -> 7;
            case "psgNoise" -> 9;
            default -> 1;
        };
    }

    private static S1CompleteRunStateNormalizer.Track withReturnStack(
            S1CompleteRunStateNormalizer.Track track, List<Long> nextToPopFirst) {
        if (nextToPopFirst.size() > 3) throw new IllegalArgumentException("test stack too deep");
        int stackPointer = 0x30 - nextToPopFirst.size() * 4;
        List<Integer> storage = new ArrayList<>(track.loopCounters().subList(
                0, stackPointer - 0x24));
        nextToPopFirst.forEach(pointer -> appendLong(storage, pointer));
        return replaceRecord(replaceRecord(track, "loopCounters", storage),
                "stackPointer", stackPointer);
    }

    private static S1CompleteRunStateNormalizer.Track uninitializedModulation(
            S1CompleteRunStateNormalizer.Track track) {
        track = replaceRecord(track, "modulationEnabled", false);
        track = replaceRecord(track, "modulationPointer", 0L);
        track = replaceRecord(track, "modulationWait", 0);
        track = replaceRecord(track, "modulationSpeed", 0);
        track = replaceRecord(track, "modulationDelta", 0);
        track = replaceRecord(track, "modulationSteps", 0);
        return replaceRecord(track, "modulationValue", 0);
    }

    private static void assertRoleInapplicableMutationIsSuppressed(
            S1CompleteRunStateNormalizer.LiveState base, int musicSlot, String component) {
        byte[] expected = bytes(base);
        List<S1CompleteRunStateNormalizer.SourceSlot> live = new ArrayList<>(base.sourceSlots());
        var source = live.get(musicSlot);
        live.set(musicSlot, slot(source.layer(), source.role(), mutateRecord(source.track(), component)));
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), live, base.savedMusic())),
                source.role() + " leaked inapplicable live " + component);

        List<S1CompleteRunStateNormalizer.SourceSlot> saved = new ArrayList<>(
                base.savedMusic().sourceSlots());
        source = saved.get(musicSlot);
        saved.set(musicSlot, slot(source.layer(), source.role(), mutateRecord(source.track(), component)));
        var changedSaved = new S1CompleteRunStateNormalizer.SavedMusic(
                base.savedMusic().globals(), saved);
        assertArrayEquals(expected, bytes(new S1CompleteRunStateNormalizer.LiveState(
                base.globals(), base.sourceSlots(), changedSaved)),
                source.role() + " leaked inapplicable saved " + component);
    }

    private static void assertSignedFormsEqual(S1CompleteRunStateNormalizer.LiveState base,
            String component, int rawValue, int signedValue) {
        List<S1CompleteRunStateNormalizer.SourceSlot> rawSlots = new ArrayList<>(base.sourceSlots());
        var fm1 = rawSlots.get(1);
        rawSlots.set(1, slot(fm1.layer(), fm1.role(),
                replaceRecord(fm1.track(), component, rawValue)));
        List<S1CompleteRunStateNormalizer.SourceSlot> signedSlots = new ArrayList<>(base.sourceSlots());
        fm1 = signedSlots.get(1);
        signedSlots.set(1, slot(fm1.layer(), fm1.role(),
                replaceRecord(fm1.track(), component, signedValue)));
        assertArrayEquals(bytes(new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), rawSlots, base.savedMusic())),
                bytes(new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), signedSlots, base.savedMusic())),
                "signed source/engine form mismatch: " + component);
    }

    private static void assertMalformedFmField(S1CompleteRunStateNormalizer.LiveState base,
            String component, int value) {
        List<S1CompleteRunStateNormalizer.SourceSlot> slots = new ArrayList<>(base.sourceSlots());
        var fm1 = slots.get(1);
        slots.set(1, slot(fm1.layer(), fm1.role(),
                replaceRecord(fm1.track(), component, value)));
        assertThrows(IllegalArgumentException.class, () -> bytes(
                new S1CompleteRunStateNormalizer.LiveState(
                        base.globals(), slots, base.savedMusic())));
    }

    private static S1CompleteRunStateNormalizer.DriverGlobals mutateGlobal(
            S1CompleteRunStateNormalizer.DriverGlobals globals, String canonicalName) {
        if ("ringSpeaker".equals(canonicalName)) {
            return replaceRecord(globals, canonicalName, globals.ringSpeaker() == 0 ? 1 : 0);
        }
        if (canonicalName.startsWith("queue")) {
            int index = canonicalName.charAt(canonicalName.length() - 1) - '0';
            List<Integer> queues = new ArrayList<>(globals.queueSlots());
            queues.set(index, queues.get(index) + 1);
            return replaceRecord(globals, "queueSlots", List.copyOf(queues));
        }
        return mutateRecord(globals, canonicalName);
    }

    private static S1CompleteRunStateNormalizer.Track mutateTrack(
            S1CompleteRunStateNormalizer.Track track, String component) {
        if ("pan".equals(component)) {
            return replaceRecord(track, component, track.pan() == 0xc0 ? 0x80 : 0xc0);
        }
        return mutateRecord(track, component);
    }

    @SuppressWarnings("unchecked")
    private static <T> T mutateRecord(T record, String componentName) {
        RecordComponent component = Arrays.stream(record.getClass().getRecordComponents())
                .filter(value -> value.getName().equals(componentName)).findFirst()
                .orElseThrow(() -> new AssertionError("missing record component " + componentName));
        try {
            Object current = component.getAccessor().invoke(record);
            Object changed;
            if (current instanceof Boolean value) changed = !value;
            else if (current instanceof Integer value) changed = value + 1;
            else if (current instanceof Long value) changed = value + 1;
            else if (current instanceof String value) changed = value.equals("music.ghz") ? "music.alt" : "music.ghz";
            else if (current instanceof S1CompleteRunStateNormalizer.RomPointer value) {
                changed = new S1CompleteRunStateNormalizer.RomPointer(value.assetKey(), value.pointer() + 1);
            } else if (current instanceof List<?> values && values.getFirst() instanceof Integer value) {
                List<Integer> copy = new ArrayList<>((List<Integer>) values);
                copy.set(0, value + 1);
                changed = List.copyOf(copy);
            } else if (current instanceof List<?> values && values.getFirst() instanceof Long value) {
                List<Long> copy = new ArrayList<>((List<Long>) values);
                copy.set(0, value + 1);
                changed = List.copyOf(copy);
            } else throw new AssertionError("unsupported mutation component " + componentName);
            return replaceRecord(record, componentName, changed);
        } catch (ReflectiveOperationException failure) {
            if (failure instanceof java.lang.reflect.InvocationTargetException invocation
                    && invocation.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T replaceRecord(T record, String componentName, Object changed) {
        try {
            RecordComponent[] components = record.getClass().getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int index = 0; index < components.length; index++) {
                values[index] = components[index].getName().equals(componentName)
                        ? changed : components[index].getAccessor().invoke(record);
                types[index] = components[index].getType();
            }
            Constructor<?> constructor = record.getClass().getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return (T) constructor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            if (failure instanceof java.lang.reflect.InvocationTargetException invocation
                    && invocation.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(failure);
        }
    }

    private static Object field(List<StateField> fields, String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow().value();
    }

    private static void assertSlot(List<?> slots, int index, String layer, String role, boolean active) {
        Map<?, ?> slot = (Map<?, ?>) slots.get(index);
        assertEquals(layer, slot.get("layer"));
        assertEquals(role, slot.get("role"));
        assertEquals(active, slot.get("active"));
        assertNotEquals(null, slot);
    }
}
