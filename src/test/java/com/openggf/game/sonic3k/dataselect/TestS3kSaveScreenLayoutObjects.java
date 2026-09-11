package com.openggf.game.sonic3k.dataselect;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSaveScreenLayoutObjects {

    @Test
    void original_matchesAuthoredObjDatSaveScreenPositions() {
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        assertEquals(0x120, layout.titleText().worldX());
        assertEquals(0x14C, layout.titleText().worldY());
        assertEquals(3, layout.titleText().mappingFrame());

        assertEquals(0x120, layout.selector().worldX());
        assertEquals(0x0E2, layout.selector().worldY());
        assertEquals(1, layout.selector().mappingFrame());

        assertEquals(0x448, layout.deleteIcon().worldX());
        assertEquals(0x0D8, layout.deleteIcon().worldY());
        assertEquals(0x0D, layout.deleteIcon().mappingFrame());

        assertEquals(0x0B0, layout.noSave().worldX());
        assertEquals(0x0C8, layout.noSave().worldY());
        assertEquals(0, layout.noSave().mappingFrame());

        List<S3kSaveScreenLayoutObjects.SaveSlotObject> slots = layout.slots();
        assertEquals(8, slots.size());
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x110, 0x108, 0, 0), slots.get(0));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x178, 0x108, 0, 1), slots.get(1));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x1E0, 0x108, 0, 2), slots.get(2));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x248, 0x108, 0, 3), slots.get(3));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x2B0, 0x108, 0, 4), slots.get(4));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x318, 0x108, 0, 5), slots.get(5));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x380, 0x108, 0, 6), slots.get(6));
        assertEquals(new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3E8, 0x108, 0, 7), slots.get(7));
    }

    @Test
    void loader_exposesAuthoredLayoutObjectsAndLoadedMappings_fromRealS3kRom() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getPath()), "Failed to open S3K ROM");

            RomByteReader reader = RomByteReader.fromRom(rom);
            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(reader);
            loader.loadData();

            S3kSaveScreenLayoutObjects layout = loader.getSaveScreenLayoutObjects();
            assertEquals(readObjDatSaveScreenFromRom(reader), layout);
            assertEquals(S3kSaveScreenLayoutObjects.original(), layout);

            List<SpriteMappingFrame> mappings = loader.getSaveScreenMappings();
            assertFalse(mappings.isEmpty(), "save-screen mappings should stay loader-backed");
            assertMappingFrameResolved(mappings, layout.titleText().mappingFrame());
            assertMappingFrameResolved(mappings, layout.selector().mappingFrame());
            assertMappingFrameResolved(mappings, layout.deleteIcon().mappingFrame());
            assertMappingFrameResolved(mappings, layout.noSave().mappingFrame());
            assertFalse(mappings.get(layout.titleText().mappingFrame()).pieces().isEmpty());
            assertFalse(mappings.get(layout.selector().mappingFrame()).pieces().isEmpty());
            assertFalse(mappings.get(layout.deleteIcon().mappingFrame()).pieces().isEmpty());
            for (S3kSaveScreenLayoutObjects.SaveSlotObject slot : layout.slots()) {
                assertMappingFrameResolved(mappings, slot.mappingFrame());
            }
        }
    }

    private static void assertMappingFrameResolved(List<SpriteMappingFrame> mappings, int frameIndex) {
        assertTrue(frameIndex >= 0, "frame index must be non-negative");
        assertTrue(frameIndex < mappings.size(), "frame index should resolve against loaded mappings");
    }

    private static S3kSaveScreenLayoutObjects readObjDatSaveScreenFromRom(RomByteReader reader) {
        List<int[]> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int address = Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ADDR
                    + i * Sonic3kConstants.OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE;
            int x = reader.readU16BE(address + 4);
            int y = reader.readU16BE(address + 6);
            int frame = reader.readU8(address + 8);
            int slotOrUnused = reader.readU8(address + 9);
            entries.add(new int[]{x, y, frame, slotOrUnused});
        }

        assertEquals(12, entries.size(), "ObjDat_SaveScreen should define 12 authored objects");
        S3kSaveScreenLayoutObjects.SceneObject titleText =
                new S3kSaveScreenLayoutObjects.SceneObject(entries.get(0)[0], entries.get(0)[1], entries.get(0)[2]);
        S3kSaveScreenLayoutObjects.SceneObject selector =
                new S3kSaveScreenLayoutObjects.SceneObject(entries.get(1)[0], entries.get(1)[1], entries.get(1)[2]);
        S3kSaveScreenLayoutObjects.SceneObject deleteIcon =
                new S3kSaveScreenLayoutObjects.SceneObject(entries.get(2)[0], entries.get(2)[1], entries.get(2)[2]);
        S3kSaveScreenLayoutObjects.SceneObject noSave =
                new S3kSaveScreenLayoutObjects.SceneObject(entries.get(3)[0], entries.get(3)[1], entries.get(3)[2]);
        List<S3kSaveScreenLayoutObjects.SaveSlotObject> slots = new ArrayList<>();
        for (int i = 4; i < entries.size(); i++) {
            int[] entry = entries.get(i);
            slots.add(new S3kSaveScreenLayoutObjects.SaveSlotObject(entry[0], entry[1], entry[2], entry[3]));
        }
        return new S3kSaveScreenLayoutObjects(titleText, selector, deleteIcon, noSave, slots);
    }

}
