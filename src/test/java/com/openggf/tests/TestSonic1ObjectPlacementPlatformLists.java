package com.openggf.tests;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1ObjectPlacementPlatformLists {
    @Test
    void lzPlatformChildrenLoadFromObjPosIndexRelativePointer() {
        byte[] rom = new byte[Sonic1Constants.OBJ_POS_INDEX_ADDR + 0x300];
        int listAddr = Sonic1Constants.OBJ_POS_INDEX_ADDR + 0x120;
        writeWord(rom, Sonic1Constants.OBJ_POS_LZ_PLATFORM_INDEX_ADDR + 4, 0x0120);
        writeWord(rom, listAddr, 1);
        writeEntry(rom, listAddr + 2, 0x0D22, 0x0483, 0x8021);
        writeEntry(rom, listAddr + 8, 0x0D9C, 0x0482, 0x0020);

        int[][] actual = new Sonic1ObjectPlacement(new RomByteReader(rom)).loadLzPlatformChildren(2);

        assertArrayEquals(new int[][] {
                {0x0D22, 0x0483, 0x21},
                {0x0D9C, 0x0482, 0x20}
        }, actual);
    }

    @Test
    void sbzPlatformChildrenLoadFromObjPosIndexRelativePointer() {
        byte[] rom = new byte[Sonic1Constants.OBJ_POS_INDEX_ADDR + 0x300];
        int listAddr = Sonic1Constants.OBJ_POS_INDEX_ADDR + 0x180;
        writeWord(rom, Sonic1Constants.OBJ_POS_SBZ_PLATFORM_INDEX_ADDR + 10, 0x0180);
        writeWord(rom, listAddr, 0);
        writeEntry(rom, listAddr + 2, 0x1C14, 0x05E0, 0x0050);

        int[][] actual = new Sonic1ObjectPlacement(new RomByteReader(rom)).loadSbzPlatformChildren(5);

        assertArrayEquals(new int[][] {
                {0x1C14, 0x05E0, 0x50}
        }, actual);
    }

    @Test
    void lzConveyorPathLoadsFromInlinePointerTable() {
        byte[] rom = new byte[Sonic1Constants.LZ_CONVEYOR_PATH_TABLE_ADDR + 0x80];
        int groupAddr = Sonic1Constants.LZ_CONVEYOR_PATH_TABLE_ADDR + 0x20;
        writeWord(rom, Sonic1Constants.LZ_CONVEYOR_PATH_TABLE_ADDR + 4, 0x0020);
        writeWord(rom, groupAddr, 8);
        writeWord(rom, groupAddr + 2, 0x0D68);
        writeWord(rom, groupAddr + 4, 0x0D22);
        writeWord(rom, groupAddr + 6, 0x0482);
        writeWord(rom, groupAddr + 8, 0x0DAE);
        writeWord(rom, groupAddr + 10, 0x05DE);

        Sonic1ObjectPlacement.ConveyorPathData actual =
                new Sonic1ObjectPlacement(new RomByteReader(rom)).loadLzConveyorPath(2);

        assertEquals(0x0D68, actual.baseX());
        assertArrayEquals(new int[][] {
                {0x0D22, 0x0482},
                {0x0DAE, 0x05DE}
        }, actual.waypoints());
    }

    @Test
    void sbzSpinConveyorPathLoadsFromInlinePointerTable() {
        byte[] rom = new byte[Sonic1Constants.SBZ_SPIN_CONVEYOR_PATH_TABLE_ADDR + 0x80];
        int groupAddr = Sonic1Constants.SBZ_SPIN_CONVEYOR_PATH_TABLE_ADDR + 0x34;
        writeWord(rom, Sonic1Constants.SBZ_SPIN_CONVEYOR_PATH_TABLE_ADDR + 4, 0x0034);
        writeWord(rom, groupAddr, 8);
        writeWord(rom, groupAddr + 2, 0x1080);
        writeWord(rom, groupAddr + 4, 0x1014);
        writeWord(rom, groupAddr + 6, 0x0270);
        writeWord(rom, groupAddr + 8, 0x10EF);
        writeWord(rom, groupAddr + 10, 0x0202);

        Sonic1ObjectPlacement.ConveyorPathData actual =
                new Sonic1ObjectPlacement(new RomByteReader(rom)).loadSbzSpinConveyorPath(2);

        assertEquals(0x1080, actual.baseX());
        assertArrayEquals(new int[][] {
                {0x1014, 0x0270},
                {0x10EF, 0x0202}
        }, actual.waypoints());
    }

    private static void writeEntry(byte[] rom, int offset, int x, int y, int subtypeWord) {
        writeWord(rom, offset, x);
        writeWord(rom, offset + 2, y);
        writeWord(rom, offset + 4, subtypeWord);
    }

    private static void writeWord(byte[] rom, int offset, int value) {
        rom[offset] = (byte) (value >>> 8);
        rom[offset + 1] = (byte) value;
    }
}
