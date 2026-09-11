package com.openggf.audio.synth.nuked;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@link NukedOpn2State} compares by value over every field.
 *
 * <p>{@code Ym2612Chip.Snapshot} carries the core state as a record component,
 * and the audio snapshot-parity tests compare snapshots structurally, falling
 * back to {@code equals} for non-record components. An identity-only
 * {@code equals} makes two snapshots of identical chip state unequal; a
 * hand-written {@code equals} that skips a field would let a genuine
 * divergence through. This test walks the public fields reflectively so that
 * a field added to the struct port without a matching {@code equals} clause
 * fails here rather than in a downstream parity test.
 */
class TestNukedOpn2StateEquality {

    @Test
    void copyOfClockedChipIsEqualWithEqualHashCode() {
        NukedOpn2State state = clockedState();
        NukedOpn2State copy = state.copy();
        assertEquals(state, copy);
        assertEquals(copy, state);
        assertEquals(state.hashCode(), copy.hashCode());
    }

    @Test
    void everyPublicFieldParticipatesInEquality() throws IllegalAccessException {
        NukedOpn2State state = clockedState();
        int checked = 0;
        for (Field field : NukedOpn2State.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(Modifier.isPublic(field.getModifiers()),
                    "state field must be public: " + field.getName());
            NukedOpn2State mutated = state.copy();
            mutate(field, mutated);
            assertNotEquals(state, mutated,
                    "equals ignores field " + field.getName());
            assertNotEquals(mutated, state,
                    "equals ignores field " + field.getName());
            checked++;
        }
        assertTrue(checked > 100, "expected the full struct, saw " + checked);
    }

    @Test
    void equalsRejectsNullAndOtherTypes() {
        NukedOpn2State state = clockedState();
        assertNotEquals(state, null);
        assertNotEquals(state, new Object());
    }

    private static void mutate(Field field, NukedOpn2State target)
            throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == int.class) {
            field.setInt(target, field.getInt(target) + 1);
        } else if (type == int[].class) {
            int[] values = (int[]) field.get(target);
            values[values.length - 1]++;
        } else if (type == int[][].class) {
            int[][] values = (int[][]) field.get(target);
            int[] last = values[values.length - 1];
            last[last.length - 1]++;
        } else {
            throw new AssertionError("unexpected field type " + type
                    + " on " + field.getName());
        }
    }

    /** A state with non-zero content spread across the struct. */
    private static NukedOpn2State clockedState() {
        NukedOpn2 chip = new NukedOpn2();
        chip.reset();
        int[] buffer = new int[2];
        chip.write(0, 0x22);
        chip.write(1, 0x08);
        chip.write(0, 0xB0);
        chip.write(1, 0x07);
        chip.write(0, 0xB4);
        chip.write(1, 0xC0);
        chip.write(0, 0x30);
        chip.write(1, 0x01);
        chip.write(0, 0x50);
        chip.write(1, 0x1F);
        chip.write(0, 0x80);
        chip.write(1, 0x0F);
        chip.write(0, 0xA4);
        chip.write(1, 0x22);
        chip.write(0, 0xA0);
        chip.write(1, 0x69);
        chip.write(0, 0x28);
        chip.write(1, 0xF0);
        for (int i = 0; i < 4096; i++) {
            chip.clock(buffer);
        }
        return chip.state().copy();
    }
}
