package com.openggf.game.rewind.schema;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindCollectionCodecs {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void restoresValueCollectionsAndMaps() {
        CollectionFixture fixture = new CollectionFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.mutate();
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(List.of(3, 1, 4), fixture.values);
        assertEquals(Arrays.asList(Mode.ALPHA, null, Mode.GAMMA), new ArrayList<>(fixture.modes));
        assertEquals(List.of("left", "right", "missing"), new ArrayList<>(fixture.counts.keySet()));
        assertEquals(7, fixture.counts.get("left"));
        assertEquals(9, fixture.counts.get("right"));
        assertEquals(null, fixture.counts.get("missing"));
    }

    @Test
    void restoresFinalCollectionsInPlace() {
        FinalCollectionFixture fixture = new FinalCollectionFixture();
        List<Integer> original = fixture.values;

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.values.clear();
        fixture.values.add(99);
        CompactFieldCapturer.restore(fixture, blob);

        assertSame(original, fixture.values);
        assertEquals(List.of(1, 2, 3), fixture.values);
    }

    @Test
    void restoresNonFinalIdentityHashMapsWithIdentitySemantics() {
        IdentityMapFixture fixture = new IdentityMapFixture();
        Object key = fixture.key;

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.values = new IdentityHashMap<>();
        CompactFieldCapturer.restore(fixture, blob);

        assertInstanceOf(IdentityHashMap.class, fixture.values);
        assertEquals(11, fixture.values.get(key));
        assertEquals(null, fixture.values.get(new String("key")));
    }

    @Test
    void distinguishesValueCollectionsFromReferenceCollectionsForIdentityContext() throws Exception {
        Field valueField = CollectionFixture.class.getDeclaredField("counts");
        Field referenceField = ReferenceCollectionFixture.class.getDeclaredField("riders");

        assertFalse(RewindCodecs.requiresIdentityTable(valueField));
        assertTrue(RewindCodecs.requiresIdentityTable(referenceField));
    }

    @Test
    void stateHoldersWithPlayerReferencesAreIdentityAware() {
        assertTrue(RewindCodecs.supportsInPlaceStateHolder(ReferenceState.class));
    }

    @Test
    void capturesMapWithPlayerKeysAndPlainStateValues() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(PlayerStateMapFixture.class);

        assertEquals(1, schema.capturedFields().size());
        assertTrue(RewindCodecs.requiresIdentityTable(
                schema.capturedFields().getFirst().field()));
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void capturesListOfPrimitiveArrays() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(PrimitiveArrayListFixture.class);

        assertEquals(1, schema.capturedFields().size());
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void rejectsCollectionWithUnsupportedElementType() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(UnsupportedCollectionFixture.class);

        assertEquals(1, schema.unsupportedFields().size());
        assertEquals("objects", schema.unsupportedFields().getFirst().field().getName());
        assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(new UnsupportedCollectionFixture()));
    }

    private enum Mode {
        ALPHA,
        BETA,
        GAMMA
    }

    private static final class CollectionFixture {
        List<Integer> values = new ArrayList<>(List.of(3, 1, 4));
        Set<Mode> modes = linkedSet(Mode.ALPHA, null, Mode.GAMMA);
        Map<String, Integer> counts = linkedMap();

        void mutate() {
            values = new ArrayList<>(List.of(-1));
            modes = linkedSet(Mode.BETA);
            counts = new LinkedHashMap<>();
            counts.put("mutated", -2);
        }
    }

    private static final class FinalCollectionFixture {
        final List<Integer> values = new ArrayList<>(List.of(1, 2, 3));
    }

    private static final class IdentityMapFixture {
        String key = new String("key");
        IdentityHashMap<String, Integer> values = new IdentityHashMap<>();

        IdentityMapFixture() {
            values.put(key, 11);
        }
    }

    private static final class UnsupportedCollectionFixture {
        List<Object> objects = new ArrayList<>(List.of(new Object()));
    }

    private static final class ReferenceCollectionFixture {
        Set<AbstractPlayableSprite> riders = new LinkedHashSet<>();
    }

    private static final class ReferenceState {
        AbstractPlayableSprite rider;
        int timer;
    }

    private static final class PlayerStateMapFixture {
        Map<AbstractPlayableSprite, ReferenceState> riders = new IdentityHashMap<>();
    }

    private static final class PrimitiveArrayListFixture {
        List<int[]> positions = new ArrayList<>(List.of(new int[] {1, 2}));
    }

    @SafeVarargs
    private static <T> Set<T> linkedSet(T... values) {
        Set<T> set = new LinkedHashSet<>();
        for (T value : values) {
            set.add(value);
        }
        return set;
    }

    private static Map<String, Integer> linkedMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("left", 7);
        map.put("right", 9);
        map.put("missing", null);
        return map;
    }
}
