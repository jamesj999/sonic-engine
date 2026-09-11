package com.openggf.game.sonic3k.events;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kZoneEventPaletteOwnership {

    private static final Path ZONE_EVENTS_SOURCE = Path.of(
            "src/main/java/com/openggf/game/sonic3k/events/Sonic3kZoneEvents.java");
    private static final int PAL_POINTER_AIZ_INDEX = 0x2A;

    private HeadlessTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
    }

    @Test
    void zoneEventPaletteHelpers_shouldUseSharedPaletteWriteSupport() throws IOException {
        String content = Files.readString(ZONE_EVENTS_SOURCE);
        List<String> violations = new ArrayList<>();

        if (content.contains("levelManager.updatePalette(")) {
            violations.add("Sonic3kZoneEvents still calls levelManager.updatePalette(...) directly");
        }
        if (content.contains("cachePaletteTextureIfReady(")) {
            violations.add("Sonic3kZoneEvents still has a direct palette texture upload fallback helper");
        }
        if (!content.contains("S3kPaletteWriteSupport.applyLine(")) {
            violations.add("Sonic3kZoneEvents does not route palette loads through S3kPaletteWriteSupport");
        }
        if (!content.contains("S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD")) {
            violations.add("Sonic3kZoneEvents does not tag palette loads with a zone-event owner id");
        }

        if (!violations.isEmpty()) {
            fail("S3K zone-event palette loads should flow through shared palette ownership helpers:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void loadPalette_submitsZoneEventOwnershipClaimsWhenRuntimeRegistryPresent() throws IOException {
        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Level level = GameServices.level().getCurrentLevel();
        Rom rom = GameServices.rom().getRom();

        registry.beginFrame();
        Sonic3kZoneEvents.loadPalette(1, Sonic3kConstants.PAL_AIZ_BATTLESHIP_ADDR);

        byte[] expectedLine = rom.readBytes(Sonic3kConstants.PAL_AIZ_BATTLESHIP_ADDR, 32);
        assertEquals(S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertColorWord(level.getPalette(1), 0, segaWord(expectedLine, 0));
        assertColorWord(level.getPalette(1), 1, segaWord(expectedLine, 2));
    }

    @Test
    void loadPaletteFromPalPointers_submitsZoneEventOwnershipClaimsWhenRuntimeRegistryPresent() throws IOException {
        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Level level = GameServices.level().getCurrentLevel();
        Rom rom = GameServices.rom().getRom();

        int entryAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                + PAL_POINTER_AIZ_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        int sourceAddr = rom.read32BitAddr(entryAddr) & 0x00FFFFFF;
        int ramDest = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
        int startLine = (ramDest & 0xFF) / 32;

        registry.beginFrame();
        Sonic3kZoneEvents.loadPaletteFromPalPointers(PAL_POINTER_AIZ_INDEX);

        byte[] expectedLine = rom.readBytes(sourceAddr, 32);
        assertEquals(S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                registry.ownerAt(PaletteSurface.NORMAL, startLine, 0));
        assertColorWord(level.getPalette(startLine), 0, segaWord(expectedLine, 0));
        assertColorWord(level.getPalette(startLine), 1, segaWord(expectedLine, 2));
    }

    private static int segaWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }
}
