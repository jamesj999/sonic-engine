package com.openggf.game.sonic2.slotmachine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCNZSlotMachineRng {
    @Test
    void setupTargetsUsesVintCounterBytesForSpeedsAndTarget() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "routine", 0x18);
        manager.activate();

        manager.update(0x1200);

        assertArrayEquals(new int[]{
                (((0x00 & 0x07) - 4) + 0x30),
                (((Integer.rotateLeft(0x00, 4) & 0xFF) & 0x07) - 4) + 0x30,
                (((0x12 & 0x07) - 4) + 0x30)
        }, intArray(manager, "slotSpeeds"));
        assertEquals(3, intField(manager, "slot1Target"));
        assertEquals(0x33, intField(manager, "slot23Target"));
    }

    @Test
    void fineTuneTimerUsesVintCounterLowNibble() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "routine", 0x0C);
        setIntField(manager, "slotTimer", 0);

        manager.update(0xABCD);

        assertEquals((0xCD & 0x0F) + 0x0C, intField(manager, "slotTimer"));
    }

    @Test
    void packedTargetsMapToDisplayedReelOrder() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "slot1Target", CNZSlotMachineManager.FACE_EGGMAN);
        setIntField(manager, "slot23Target",
                (CNZSlotMachineManager.FACE_RING << 4) | CNZSlotMachineManager.FACE_BAR);

        assertEquals(CNZSlotMachineManager.FACE_BAR, invokeGetTargetForSlot(manager, 0));
        assertEquals(CNZSlotMachineManager.FACE_RING, invokeGetTargetForSlot(manager, 1));
        assertEquals(CNZSlotMachineManager.FACE_EGGMAN, invokeGetTargetForSlot(manager, 2));
    }

    @Test
    void fineTuneUsesRomPackedTargetOrderForThirdReel() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "routine", 0x10);
        setIntField(manager, "slotTimer", 0xE2);
        setIntField(manager, "slotIndex", 0x00);
        setIntField(manager, "slot1Target", CNZSlotMachineManager.FACE_EGGMAN);
        setIntField(manager, "slot23Target", 0x03);
        setIntArray(manager, "slotIndices", new int[]{0x00, 0x01, 0xF5});
        setIntArray(manager, "slotOffsets", new int[]{0x00, 0x00, 0x96});
        setIntArray(manager, "slotSpeeds", new int[]{0x00, 0x00, 0x63});
        setIntArray(manager, "slotSubroutines", new int[]{0x0C, 0x0C, 0x00});

        manager.update(0x1047);
        manager.update(0x1048);

        assertEquals(0x04, intArray(manager, "slotSubroutines")[2]);
        assertEquals(0x60, intArray(manager, "slotSpeeds")[2]);
    }

    @Test
    void targetAlignmentPreservesTheRomWordUnderflow() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "slot23Target", CNZSlotMachineManager.FACE_JACKPOT);
        setIntArray(manager, "slotIndices", new int[]{0x07, 0x00, 0x00});
        setIntArray(manager, "slotOffsets", new int[]{0x80, 0x00, 0x00});
        setIntArray(manager, "slotSpeeds", new int[]{0x60, 0x00, 0x00});
        setIntArray(manager, "slotSubroutines", new int[]{0x04, 0x00, 0x00});

        invokeProcessSlotSubroutine(manager, 0);

        assertEquals(0xFFF0,
                ((intArray(manager, "slotIndices")[0] & 0xFF) << 8)
                        | (intArray(manager, "slotOffsets")[0] & 0xFF),
                "SlotMachine_Routine5_2 masks the boundary to $0700, then subtracts $0010 "
                        + "as a 16-bit word (s2.asm:59550-59558).");
        assertEquals(0x08, intArray(manager, "slotSubroutines")[0]);
    }

    @Test
    void changingStoppedFaceUpdatesMatchingRewardSlot() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "slot1Target", CNZSlotMachineManager.FACE_SONIC);
        setIntField(manager, "slot23Target",
                (CNZSlotMachineManager.FACE_TAILS << 4) | CNZSlotMachineManager.FACE_RING);

        invokeSetTargetForSlot(manager, 0, CNZSlotMachineManager.FACE_EGGMAN);
        assertEquals(CNZSlotMachineManager.FACE_SONIC, intField(manager, "slot1Target") & 0x07);
        assertEquals((CNZSlotMachineManager.FACE_TAILS << 4) | CNZSlotMachineManager.FACE_EGGMAN,
                intField(manager, "slot23Target"));

        invokeSetTargetForSlot(manager, 2, CNZSlotMachineManager.FACE_BAR);
        assertEquals(CNZSlotMachineManager.FACE_BAR, intField(manager, "slot1Target") & 0x07);
        assertEquals((CNZSlotMachineManager.FACE_TAILS << 4) | CNZSlotMachineManager.FACE_EGGMAN,
                intField(manager, "slot23Target"));
    }

    @Test
    void linkedCageReleaseKeepsReelsRunningToRomCompletion() throws Exception {
        CNZSlotMachineManager manager = new CNZSlotMachineManager();
        setIntField(manager, "routine", 0x18);
        setIntArray(manager, "slotIndices", new int[]{0x64, 0x8C, 0x91});
        setIntArray(manager, "slotOffsets", new int[]{0x00, 0x00, 0x00});
        manager.update(0x41E6);
        manager.activate();

        for (int frame = 0x41E7; frame <= 0x4207; frame++) {
            manager.update(frame);
        }
        manager.releaseUse();

        assertFalse((boolean) booleanField(manager, "inUse"),
                "releaseUse mirrors ObjD6 clearing SlotMachineInUse");
        assertEquals(0x10, intField(manager, "routine"),
                "clearing SlotMachineInUse must not stop SlotMachine_Routine");

        for (int frame = 0x4208; frame <= 0x42BF; frame++) {
            manager.update(frame);
        }

        assertEquals(0x18, intField(manager, "routine"));
        assertTrue(manager.isComplete());
        assertArrayEquals(new int[]{0x00, 0x00, 0x00}, maskedSlots(manager, "slotOffsets"));
        assertArrayEquals(new int[]{0x00, 0x00, 0x00}, maskedSlots(manager, "slotSpeeds"));
        assertArrayEquals(new int[]{0x00, 0x00, 0x00}, maskedSlots(manager, "slotSubroutines"),
                "SlotMachine_Routine6 clears a word at each slot speed, which also clears "
                        + "the adjacent subroutine byte (s2.asm:59579-59581).");
    }

    @Test
    void managerDoesNotUseJvmRandomSources() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/game/sonic2/slotmachine/CNZSlotMachineManager.java"));

        assertFalse(source.contains("java.util.Random"));
        assertFalse(source.contains("new Random"));
        assertFalse(source.contains("random.next"));
    }

    private static int intField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String fieldName, int value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static boolean booleanField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntArray(Object target, String fieldName, int[] values) throws ReflectiveOperationException {
        int[] array = intArray(target, fieldName);
        System.arraycopy(values, 0, array, 0, values.length);
    }

    private static int[] intArray(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[]) field.get(target);
    }

    private static int[] maskedSlots(Object target, String fieldName) throws ReflectiveOperationException {
        int[] source = intArray(target, fieldName);
        return new int[]{source[0] & 0xFF, source[1] & 0xFF, source[2] & 0xFF};
    }

    private static int invokeGetTargetForSlot(CNZSlotMachineManager manager, int slot)
            throws ReflectiveOperationException {
        var method = CNZSlotMachineManager.class.getDeclaredMethod("getTargetForSlot", int.class);
        method.setAccessible(true);
        return (int) method.invoke(manager, slot);
    }

    private static void invokeSetTargetForSlot(CNZSlotMachineManager manager, int slot, int face)
            throws ReflectiveOperationException {
        var method = CNZSlotMachineManager.class.getDeclaredMethod("setTargetForSlot", int.class, int.class);
        method.setAccessible(true);
        method.invoke(manager, slot, face);
    }

    private static void invokeProcessSlotSubroutine(CNZSlotMachineManager manager, int slot)
            throws ReflectiveOperationException {
        var method = CNZSlotMachineManager.class.getDeclaredMethod("processSlotSubroutine", int.class);
        method.setAccessible(true);
        method.invoke(manager, slot);
    }
}
