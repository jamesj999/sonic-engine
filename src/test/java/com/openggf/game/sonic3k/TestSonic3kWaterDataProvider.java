package com.openggf.game.sonic3k;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Sonic3kWaterDataProvider}: zone water detection and
 * starting water heights from StartingWaterHeights.bin.
 */
public class TestSonic3kWaterDataProvider {

    private Sonic3kWaterDataProvider provider;

    @BeforeEach
    public void setUp() {
        provider = new Sonic3kWaterDataProvider();
    }

    // =====================================================================
    // hasWater tests
    // =====================================================================

    @Test
    public void aiz1HasWater() {
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 0, PlayerCharacter.SONIC_AND_TAILS), "AIZ1 Sonic should have water");
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 0, PlayerCharacter.KNUCKLES), "AIZ1 Knuckles should have water");
    }

    @Test
    public void aiz2SonicHasWater() {
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 1, PlayerCharacter.SONIC_AND_TAILS), "AIZ2 Sonic should have water");
    }

    @Test
    public void aiz2KnucklesHasNoWaterOnDirectLoad() {
        // ROM CheckLevelForWater (sonic3k.asm:9754-9759): Knuckles excluded from AIZ2 water
        // when Apparent_zone_and_act == Current_zone_and_act (direct load / level select).
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 1, PlayerCharacter.KNUCKLES), "AIZ2 Knuckles should NOT have water (direct load)");
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 1, PlayerCharacter.KNUCKLES, false), "AIZ2 Knuckles should NOT have water (seamless=false)");
    }

    @Test
    public void aiz2KnucklesHasWaterOnSeamlessTransition() {
        // ROM: During seamless AIZ1â†’AIZ2 transition, Apparent_zone_and_act still points to
        // AIZ1 (0), not AIZ2 (1). CheckLevelForWater: Apparent != Current â†’ water enabled
        // even for Knuckles (sonic3k.asm:9756-9757: bne.s loc_78F2).
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_AIZ, 1, PlayerCharacter.KNUCKLES, true), "AIZ2 Knuckles should have water during seamless transition from AIZ1");
    }

    @Test
    public void hczHasWater() {
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS), "HCZ1 should have water");
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_HCZ, 1, PlayerCharacter.KNUCKLES), "HCZ2 should have water");
    }

    @Test
    public void lbz2HasWater() {
        // Only LBZ2 has water â€” LBZ1 does NOT (sonic3k.asm:9772-9773)
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_LBZ, 0, PlayerCharacter.SONIC_AND_TAILS), "LBZ1 should NOT have water");
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.SONIC_AND_TAILS), "LBZ2 should have water");
    }

    @Test
    public void mgzHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_MGZ, 0, PlayerCharacter.SONIC_AND_TAILS), "MGZ should not have water");
    }

    @Test
    public void cnz1HasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_CNZ, 0, PlayerCharacter.SONIC_AND_TAILS), "CNZ1 should not have water");
    }

    @Test
    public void cnz2SonicHasWater() {
        // CNZ2 Sonic/Tails: water (sonic3k.asm:9764-9767)
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_CNZ, 1, PlayerCharacter.SONIC_AND_TAILS), "CNZ2 Sonic should have water");
    }

    @Test
    public void cnz2KnucklesHasNoWater() {
        // CNZ2 Knuckles: no water (falls through in ROM)
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_CNZ, 1, PlayerCharacter.KNUCKLES), "CNZ2 Knuckles should NOT have water");
    }

    @Test
    public void fbzHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_FBZ, 0, PlayerCharacter.SONIC_AND_TAILS), "FBZ should not have water");
    }

    @Test
    public void mhzHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_MHZ, 0, PlayerCharacter.SONIC_AND_TAILS), "MHZ should not have water");
    }

    @Test
    public void icz1HasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_ICZ, 0, PlayerCharacter.SONIC_AND_TAILS), "ICZ1 should not have water");
    }

    @Test
    public void icz2HasWater() {
        // ICZ2: water (sonic3k.asm:9770-9771)
        assertTrue(provider.hasWater(Sonic3kZoneIds.ZONE_ICZ, 1, PlayerCharacter.SONIC_AND_TAILS), "ICZ2 should have water");
    }

    @Test
    public void sozHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_SOZ, 0, PlayerCharacter.SONIC_AND_TAILS), "SOZ should not have water");
    }

    @Test
    public void lrzHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_LRZ, 0, PlayerCharacter.SONIC_AND_TAILS), "LRZ should not have water");
    }

    @Test
    public void sszHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_SSZ, 0, PlayerCharacter.SONIC_AND_TAILS), "SSZ should not have water");
    }

    @Test
    public void dezHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_DEZ, 0, PlayerCharacter.SONIC_AND_TAILS), "DEZ should not have water");
    }

    @Test
    public void ddzHasNoWater() {
        assertFalse(provider.hasWater(Sonic3kZoneIds.ZONE_DDZ, 0, PlayerCharacter.SONIC_AND_TAILS), "DDZ should not have water");
    }

    // =====================================================================
    // Starting water height tests
    // =====================================================================

    @Test
    public void aiz1StartingHeight() {
        assertEquals(0x0504, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_AIZ, 0), "AIZ1 starting height should be 0x0504");
    }

    @Test
    public void aiz2StartingHeight() {
        assertEquals(0x0528, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_AIZ, 1), "AIZ2 starting height should be 0x0528");
    }

    @Test
    public void hcz1StartingHeight() {
        assertEquals(0x0500, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_HCZ, 0), "HCZ1 starting height should be 0x0500");
    }

    @Test
    public void hcz2StartingHeight() {
        assertEquals(0x0700, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_HCZ, 1), "HCZ2 starting height should be 0x0700");
    }

    @Test
    public void lbz1StartingHeight() {
        assertEquals(0x0A80, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_LBZ, 0), "LBZ1 starting height should be 0x0A80 (ROM verified)");
    }

    @Test
    public void lbz2StartingHeight() {
        assertEquals(0x065E, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_LBZ, 1), "LBZ2 starting height should be 0x065E (ROM verified)");
    }

    @Test
    public void nonWaterZoneReturnsDefaultHeight() {
        // Non-water zones use 0x0600 (off-screen)
        assertEquals(0x0600, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_MGZ, 0));
        assertEquals(0x0600, provider.getStartingWaterLevel(Sonic3kZoneIds.ZONE_DEZ, 1));
    }

    @Test
    public void outOfBoundsZoneReturnsDefaultHeight() {
        assertEquals(0x0600, provider.getStartingWaterLevel(-1, 0), "Zone -1 should return default");
        assertEquals(0x0600, provider.getStartingWaterLevel(99, 0), "Zone 99 should return default");
    }

    @Test
    public void waterPaletteLoaderDoesNotPatchThroughPublicColorArray() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/game/sonic3k/Sonic3kWaterDataProvider.java"));

        assertFalse(source.contains(".colors[colorIndex]"),
                "Sonic3kWaterDataProvider should patch loader-local palette colors through a helper/accessor");
    }
}


