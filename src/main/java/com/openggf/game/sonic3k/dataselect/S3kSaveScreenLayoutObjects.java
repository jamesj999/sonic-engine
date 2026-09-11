package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;

import java.util.List;
import java.util.Objects;

/**
 * Authored object layout from {@code ObjDat_SaveScreen} in {@code sonic3k.asm}.
 *
 * <p>This preserves the original world coordinates and mapping-frame references so
 * later parity work can consume authored scene data instead of reconstructing it
 * from synthetic layout math.
 */
public record S3kSaveScreenLayoutObjects(
        SceneObject titleText,
        SceneObject selector,
        SceneObject deleteIcon,
        SceneObject noSave,
        List<SaveSlotObject> slots) {

    public S3kSaveScreenLayoutObjects {
        titleText = Objects.requireNonNull(titleText, "titleText");
        selector = Objects.requireNonNull(selector, "selector");
        deleteIcon = Objects.requireNonNull(deleteIcon, "deleteIcon");
        noSave = Objects.requireNonNull(noSave, "noSave");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (slots.size() != Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_SLOT_COUNT) {
            throw new IllegalArgumentException("Expected " + Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_SLOT_COUNT
                    + " authored save slots, got " + slots.size());
        }
        int objectCount = 4 + slots.size();
        if (objectCount != Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_OBJECT_COUNT) {
            throw new IllegalArgumentException("Expected " + Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_OBJECT_COUNT
                    + " authored save-screen objects, got " + objectCount);
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).slotIndex() != i) {
                throw new IllegalArgumentException("Expected slot index " + i + " but got " + slots.get(i).slotIndex());
            }
        }
    }

    public static S3kSaveScreenLayoutObjects original() {
        return new S3kSaveScreenLayoutObjects(
                new SceneObject(0x120, 0x14C, 3),
                new SceneObject(0x120, 0x0E2, 1),
                new SceneObject(0x448, 0x0D8, 0x0D),
                new SceneObject(0x0B0, 0x0C8, 0),
                List.of(
                        new SaveSlotObject(0x110, 0x108, 0, 0),
                        new SaveSlotObject(0x178, 0x108, 0, 1),
                        new SaveSlotObject(0x1E0, 0x108, 0, 2),
                        new SaveSlotObject(0x248, 0x108, 0, 3),
                        new SaveSlotObject(0x2B0, 0x108, 0, 4),
                        new SaveSlotObject(0x318, 0x108, 0, 5),
                        new SaveSlotObject(0x380, 0x108, 0, 6),
                        new SaveSlotObject(0x3E8, 0x108, 0, 7)));
    }

    public record SceneObject(int worldX, int worldY, int mappingFrame) {
    }

    public record SaveSlotObject(int worldX, int worldY, int mappingFrame, int slotIndex) {
    }
}
