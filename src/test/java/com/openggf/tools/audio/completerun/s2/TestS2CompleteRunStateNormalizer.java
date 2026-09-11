package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.HardwareRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SourceLayer.MUSIC;
import static com.openggf.tools.audio.completerun.s2.S2CompleteRunStateNormalizer.SourceLayer.SFX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2CompleteRunStateNormalizer {
    private static final S2CompleteRunStateNormalizer.Asset EHZ =
            new S2CompleteRunStateNormalizer.Asset("music.rom.0f88c4", 0x1380, 0x1b80);
    private static final S2CompleteRunStateNormalizer.Asset JUMP =
            new S2CompleteRunStateNormalizer.Asset("sfx.native.a0", 0x8000, 0x8100);
    private static final Map<String, S2CompleteRunStateNormalizer.Asset> ASSETS =
            Map.of(EHZ.key(), EHZ, JUMP.key(), JUMP);

    @Test
    void normalizesAllGlobalQueuesTransformsAndEffectiveHardwareOwners() {
        var normalized = S2CompleteRunStateNormalizer.normalizeReference(liveState(false), ASSETS);

        assertEquals(S2CompleteRunStateNormalizer.GLOBAL_FIELDS,
                normalized.fields().stream().map(field -> field.name()).toList());
        assertEquals(10, normalized.roles().size());
        assertEquals(S2CompleteRunStateNormalizer.ACTIVE_ROLE_FIELDS,
                normalized.roles().get(HardwareRole.FM3.ordinal()).fields().stream()
                        .map(field -> field.name()).toList());
        assertEquals(0x34, roleValue(normalized, HardwareRole.DAC, "savedDac"));
        assertEquals(true, roleValue(normalized, HardwareRole.FM3, "doNotAttack"));
        assertTrue(normalized.roles().get(HardwareRole.FM3.ordinal()).active());
        assertEquals("sfx.native.a0", normalized.roles().get(HardwareRole.FM3.ordinal()).fields().getFirst().value());
        assertEquals(0x70, value(normalized, "priority"));
        assertEquals(0xa0, value(normalized, "queue0"));
        assertEquals(Map.of("active", true, "assetKey", EHZ.key(), "cursor", 0x10),
                value(normalized, "voiceTablePointer"));
        assertEquals(1, value(normalized, "ringSpeaker"));
        assertEquals(true, value(normalized, "gloopSuppressed"));
        assertEquals(3, value(normalized, "spindashPlayingCounter"));
        List<?> sourceSlots = (List<?>) value(normalized, "sourceSlots");
        assertEquals(16, sourceSlots.size());
        assertEquals("MUSIC", ((Map<?, ?>) sourceSlots.getFirst()).get("layer"));
        assertEquals("SFX", ((Map<?, ?>) sourceSlots.get(10)).get("layer"));
        assertEquals(false, ((Map<?, ?>) value(normalized, "savedMusic")).get("active"));
    }

    @Test
    void referenceAndEngineCoordinatesNormalizeToIdenticalState() {
        assertEquals(S2CompleteRunStateNormalizer.normalizeReference(liveState(false), ASSETS),
                S2CompleteRunStateNormalizer.normalizeEngine(liveState(false), ASSETS));
    }

    @Test
    void inactiveTracksCannotLeakStaleCapacityIntoCanonicalRoles() {
        var state = liveState(false);
        var normalized = S2CompleteRunStateNormalizer.normalizeReference(state, ASSETS);

        assertFalse(normalized.roles().get(HardwareRole.FM1.ordinal()).active());
        assertEquals(List.of(), normalized.roles().get(HardwareRole.FM1.ordinal()).fields());
    }

    @Test
    void futureIrrelevantUnionBytesNormalizeIdenticallyAcrossRoleLayerAndPlaybackModes() {
        var base = liveState(false);
        List<S2CompleteRunStateNormalizer.SourceSlot> baseSlots = new ArrayList<>(base.sourceSlots());
        baseSlots.set(9, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG3,
                activeTrack(EHZ.key(), 0x1390, 0xc0)));
        base = new S2CompleteRunStateNormalizer.LiveState(base.globals(), baseSlots, null);

        List<S2CompleteRunStateNormalizer.SourceSlot> changedSlots = new ArrayList<>(baseSlots);
        changedSlots.set(0, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.DAC,
                activeTrack(EHZ.key(), 0x1390, 6, 0x90,
                        new UnionBytes(0x1234, 0x13a0, 0x21, 0x22, 0x23, 0x24, 0x2526,
                                0x27, 0x28, 0x13a2, 0x13a4), 0x2a,
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
        changedSlots.set(3, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM3,
                activeTrack(EHZ.key(), 0x1390, 2, 0x90,
                        new UnionBytes(0x1234, 0x13a0, 0x21, 0x22, 0x23, 0x24, 0x2526,
                                0, 0, 0x13a2, 0x1390), 0x2a,
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
        changedSlots.set(9, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG3,
                activeTrack(EHZ.key(), 0x1390, 0xc0, 0x90,
                        new UnionBytes(0x1234, 0x13a0, 0x21, 0x22, 0x23, 0x24, 0x2526,
                                0x27, 0x28, 0x13a2, 0x13a4), 0x2a,
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
        var changed = new S2CompleteRunStateNormalizer.LiveState(base.globals(), changedSlots, null);

        assertEquals(S2CompleteRunStateNormalizer.normalizeReference(base, ASSETS),
                S2CompleteRunStateNormalizer.normalizeReference(changed, ASSETS));
    }

    @Test
    void applicableUnionFieldsAndSharedLoopReturnPartitionRemainExact() {
        var base = liveState(false);
        List<S2CompleteRunStateNormalizer.SourceSlot> slots = new ArrayList<>(base.sourceSlots());
        slots.set(3, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM3,
                activeTrack(EHZ.key(), 0x1390, 2, 0x98,
                        new UnionBytes(0x1234, 0x1390, 8, 9, 1, 10, 0x10,
                                0x0f, 0xe7, 0x1394, 0x1398), 0x26,
                        List.of(1, 2, 3, 4, 5, 6, 0x94, 0x13, 0x98, 0x13))));
        slots.set(9, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG3,
                activeTrack(EHZ.key(), 0x1390, 0xe0, 0x98,
                        new UnionBytes(0x1234, 0x1390, 8, 9, 1, 10, 0x10,
                                0x0f, 0xe7, 0x1394, 0x1398), 0x2a,
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
        slots.set(10, new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.FM3,
                activeTrack(JUMP.key(), 0x8010, 2, 0x90,
                        new UnionBytes(0x1234, 0x8010, 8, 9, 1, 10, 0x10,
                                0x0e, 0xe7, 0x8008, 0x800c), 0x2a,
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));
        var normalized = S2CompleteRunStateNormalizer.normalizeReference(
                new S2CompleteRunStateNormalizer.LiveState(base.globals(), slots, null), ASSETS);

        assertEquals(0x34, slotValue(normalized, 0, "savedDac"));
        assertEquals(0, slotValue(normalized, 0, "frequency"));
        assertEquals(0, slotValue(normalized, 3, "savedDac"));
        assertEquals(0x1234, slotValue(normalized, 3, "frequency"));
        assertEquals(true, slotValue(normalized, 3, "modulationEnabled"));
        assertEquals(Map.of("active", true, "assetKey", EHZ.key(), "cursor", 0x10),
                slotValue(normalized, 3, "modulationCursor"));
        assertEquals(8, slotValue(normalized, 3, "modulationWait"));
        assertEquals(9, slotValue(normalized, 3, "modulationSpeed"));
        assertEquals(1, slotValue(normalized, 3, "modulationDelta"));
        assertEquals(10, slotValue(normalized, 3, "modulationSteps"));
        assertEquals(0x10, slotValue(normalized, 3, "modulationValue"));
        assertEquals(0x0f, slotValue(normalized, 3, "volumeTlMask"));
        assertEquals(Map.of("active", false), slotValue(normalized, 3, "voicePointer"));
        assertEquals(Map.of("active", true, "assetKey", EHZ.key(), "cursor", 0x18),
                slotValue(normalized, 3, "tlPointer"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), slotValue(normalized, 3, "loopCounters"));
        assertEquals(List.of(0x14, 0x18), slotValue(normalized, 3, "returnStack"));

        assertEquals(0xe7, slotValue(normalized, 9, "psgNoise"));
        assertEquals(0, slotValue(normalized, 9, "volumeTlMask"));
        assertEquals(Map.of("active", false), slotValue(normalized, 9, "voicePointer"));
        assertEquals(Map.of("active", false), slotValue(normalized, 9, "tlPointer"));

        assertEquals(0x0e, slotValue(normalized, 10, "volumeTlMask"));
        assertEquals(Map.of("active", true, "assetKey", JUMP.key(), "cursor", 8),
                slotValue(normalized, 10, "voicePointer"));
        assertEquals(Map.of("active", true, "assetKey", JUMP.key(), "cursor", 12),
                slotValue(normalized, 10, "tlPointer"));
    }

    @Test
    void oneUpRequiresAndSerializesTheExactTenTrackSavedPayload() {
        assertThrows(NullPointerException.class,
                () -> S2CompleteRunStateNormalizer.normalizeReference(liveState(true), ASSETS));

        var base = liveState(false);
        var oneUpGlobals = globals(true);
        var saved = new S2CompleteRunStateNormalizer.SavedMusic(
                savedGlobals(), base.sourceSlots().subList(0, 10));
        var state = new S2CompleteRunStateNormalizer.LiveState(oneUpGlobals, base.sourceSlots(), saved);
        var normalized = S2CompleteRunStateNormalizer.normalizeReference(state, ASSETS);
        Map<?, ?> normalizedSaved = (Map<?, ?>) value(normalized, "savedMusic");
        assertEquals(true, normalizedSaved.get("active"));
        assertEquals(0x70, normalizedSaved.get("priority"));
        assertFalse(normalizedSaved.containsKey("currentSong"));
        assertFalse(normalizedSaved.containsKey("ringSpeaker"));
        assertEquals(10, ((List<?>) normalizedSaved.get("sourceSlots")).size());
    }

    @Test
    void rejectsOutOfRangePointersWrongSlotOrderAndIncompleteServices() {
        var state = liveState(false);
        var badTrack = activeTrack("music.rom.0f88c4", 0x1b80, 6);
        var slots = new ArrayList<>(state.sourceSlots());
        slots.set(0, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.DAC, badTrack));
        assertThrows(IllegalArgumentException.class, () -> S2CompleteRunStateNormalizer.normalizeReference(
                new S2CompleteRunStateNormalizer.LiveState(state.globals(), slots, null), ASSETS));
        assertThrows(IllegalArgumentException.class, () -> new S2CompleteRunStateNormalizer.LiveState(
                state.globals(), state.sourceSlots().reversed(), null));
        assertThrows(IllegalArgumentException.class, () -> S2CompleteRunStateNormalizer.normalizeReference(
                new S2CompleteRunStateNormalizer.LiveState(globals(false, 0xff), state.sourceSlots(), null), ASSETS));

        var endReturn = activeTrack(EHZ.key(), 0x1390, 6, 0x80,
                new UnionBytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0x28,
                List.of(0, 0, 0, 0, 0, 0, 0, 0, 0x80, 0x1b));
        var endSlots = new ArrayList<>(state.sourceSlots());
        endSlots.set(0, new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.DAC, endReturn));
        assertThrows(IllegalArgumentException.class, () -> S2CompleteRunStateNormalizer.normalizeReference(
                new S2CompleteRunStateNormalizer.LiveState(state.globals(), endSlots, null), ASSETS));
    }

    private static Object value(com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState state,
            String name) {
        return state.fields().stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow().value();
    }

    private static Object roleValue(
            com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState state,
            HardwareRole role, String name) {
        return state.roles().get(role.ordinal()).fields().stream()
                .filter(field -> field.name().equals(name)).findFirst().orElseThrow().value();
    }

    private static Object slotValue(
            com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NormalizedState state,
            int slot, String name) {
        List<?> slots = (List<?>) value(state, "sourceSlots");
        return ((Map<?, ?>) slots.get(slot)).get(name);
    }

    private static S2CompleteRunStateNormalizer.LiveState liveState(boolean oneUpWithoutSavedState) {
        List<S2CompleteRunStateNormalizer.SourceSlot> slots = new ArrayList<>();
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.DAC,
                activeTrack(EHZ.key(), 0x1390, 6)));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM1, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM2, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM3,
                activeTrack(EHZ.key(), 0x1390, 2)));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM4, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM5, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.FM6, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG1, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG2, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(MUSIC, HardwareRole.PSG3, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.FM3,
                activeTrack(JUMP.key(), 0x8010, 2)));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.FM4, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.FM5, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.PSG1, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.PSG2, inactiveTrack()));
        slots.add(new S2CompleteRunStateNormalizer.SourceSlot(SFX, HardwareRole.PSG3, inactiveTrack()));
        return new S2CompleteRunStateNormalizer.LiveState(globals(oneUpWithoutSavedState), slots, null);
    }

    private static S2CompleteRunStateNormalizer.DriverGlobals globals(boolean oneUp) {
        return globals(oneUp, 0);
    }

    private static S2CompleteRunStateNormalizer.DriverGlobals globals(boolean oneUp, int dacUpdating) {
        return new S2CompleteRunStateNormalizer.DriverGlobals(
                0x70, 4, 5, 0, 0, 0, 0, dacUpdating, 0x80, List.of(0xa0, 0, 0),
                new S2CompleteRunStateNormalizer.AssetPointer(EHZ.key(), 0x1390),
                0, 0, 0, oneUp, 6, 7, false, true, 0, false, false,
                0, 0, 0x82, false, 0xff, true, 3, 2, true);
    }

    private static S2CompleteRunStateNormalizer.SavedGlobals savedGlobals() {
        return new S2CompleteRunStateNormalizer.SavedGlobals(
                0x70, 4, 5, 0, 0, 0, 0, 0, 0x80, List.of(0xa0, 0, 0),
                new S2CompleteRunStateNormalizer.AssetPointer(EHZ.key(), 0x1390),
                0, 0, 0, false, 6, 7, false, true, 0, false);
    }

    private static S2CompleteRunStateNormalizer.Track inactiveTrack() {
        return new S2CompleteRunStateNormalizer.Track(false, null, 0, 0xff, 0xff, 0xff,
                0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xffff,
                0xff, 0xff, 0xffff, 0xff, 0xff, 0xff, 0xff, 0xffff, 0xff,
                0xff, 0xff, 0xffff, 0xffff, List.of());
    }

    private static S2CompleteRunStateNormalizer.Track activeTrack(String asset, int pointer, int voiceControl) {
        return activeTrack(asset, pointer, voiceControl, 0x90,
                new UnionBytes(0x1234, pointer, 8, 9, 1, 10, 0x10,
                        0, 0, pointer, pointer), 0x2a,
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    }

    private static S2CompleteRunStateNormalizer.Track activeTrack(String asset, int pointer, int voiceControl,
            int playbackControl, UnionBytes union, int stackPointer, List<Integer> loopAndStack) {
        return new S2CompleteRunStateNormalizer.Track(true, asset, pointer, playbackControl, voiceControl, 1,
                0, 1, 0xc0, 2, 3, stackPointer, 4, 5, union.frequency(),
                6, 7, union.modulationPointer(), union.modulationWait(), union.modulationSpeed(),
                union.modulationDelta(), union.modulationSteps(), union.modulationValue(), 0,
                union.volumeTlMask(), union.psgNoise(), union.voicePointer(), union.tlPointer(),
                loopAndStack);
    }

    private record UnionBytes(int frequency, int modulationPointer, int modulationWait,
            int modulationSpeed, int modulationDelta, int modulationSteps, int modulationValue,
            int volumeTlMask, int psgNoise, int voicePointer, int tlPointer) { }
}
