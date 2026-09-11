package com.openggf.game.sonic3k.dataselect;

/**
 * Original S3K save-screen world layout values from the disassembly.
 * This task only models the authored coordinates; presentation is added later.
 */
public record S3kDataSelectLayout(
        int noSaveWorldX,
        int deleteWorldX,
        int slotWorldXStart,
        int slotWorldXStep,
        int slotWorldY) {

    public static S3kDataSelectLayout original() {
        return new S3kDataSelectLayout(0xB0, 0x448, 0x110, 0x68, 0x108);
    }

    public int slotWorldX(int slotIndex) {
        return slotWorldXStart + (slotIndex * slotWorldXStep);
    }
}
