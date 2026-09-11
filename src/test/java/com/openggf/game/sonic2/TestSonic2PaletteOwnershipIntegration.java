package com.openggf.game.sonic2;

import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2PaletteOwnershipIntegration {

    private static final String EHZ_WATER_CYCLE_OWNER = "s2.ehz.waterCycle";
    private static final String HTZ_LAVA_CYCLE_OWNER = "s2.htz.lavaCycle";
    private static final String CPZ_CYCLE_1_OWNER = "s2.cpz.cycle1";
    private static final String CPZ_CYCLE_2_OWNER = "s2.cpz.cycle2";
    private static final String CPZ_CYCLE_3_OWNER = "s2.cpz.cycle3";
    private static final String CNZ_BOSS_CYCLE_1_OWNER = "s2.cnz.bossCycle1";
    private static final String CNZ_BOSS_CYCLE_2_OWNER = "s2.cnz.bossCycle2";
    private static final String CNZ_BOSS_CYCLE_3_OWNER = "s2.cnz.bossCycle3";
    private static final String WFZ_FIRE_BELT_OWNER = "s2.wfz.fireBeltCycle";
    private static final String WFZ_CYCLE_1_OWNER = "s2.wfz.cycle1";
    private static final String WFZ_CYCLE_2_OWNER = "s2.wfz.cycle2";

    @Test
    void ehzCycleSubmitsPaletteOwnershipClaimsThroughRuntimeRegistry() {
        loadZone(Sonic2ZoneConstants.ZONE_EHZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        registry.beginFrame();

        updateAnimatedPaletteManager();

        assertEquals(EHZ_WATER_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 3));
        assertEquals(EHZ_WATER_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 4));
        assertEquals(EHZ_WATER_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 14));
        assertEquals(EHZ_WATER_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 15));
    }

    @Test
    void ehzCycleResolvesIntoExpectedNormalPaletteLineColors() {
        loadZone(Sonic2ZoneConstants.ZONE_EHZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Palette[] livePalettes = livePalettes();
        registry.beginFrame();

        updateAnimatedPaletteManager();

        clearColors(livePalettes[1], 3, 4, 14, 15);
        registry.resolveInto(livePalettes, null, null, null);

        byte[] cycleData = romSlice(Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_ADDR,
                Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        assertColorWord(livePalettes[1], 3, cycleData, 0);
        assertColorWord(livePalettes[1], 4, cycleData, 2);
        assertColorWord(livePalettes[1], 14, cycleData, 4);
        assertColorWord(livePalettes[1], 15, cycleData, 6);
    }

    @Test
    void htzVariableDelayCycleKeepsItsOwnershipAndAdvancesAfterTheExpectedDelay() {
        loadZone(Sonic2ZoneConstants.ZONE_HTZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();

        registry.beginFrame();
        updateAnimatedPaletteManager();

        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 3));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 4));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 14));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 15));

        for (int frame = 0; frame < 11; frame++) {
            registry.beginFrame();
            updateAnimatedPaletteManager();
            assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 3),
                    "HTZ lava cycle should wait out its variable delay before the next frame submission");
        }

        registry.beginFrame();
        updateAnimatedPaletteManager();

        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 3));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 4));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 14));
        assertEquals(HTZ_LAVA_CYCLE_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 15));
    }

    @Test
    void cpzMultiCycleZoneSubmitsOwnersForAllRepresentativeCycles() {
        loadZone(Sonic2ZoneConstants.ZONE_CPZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        registry.beginFrame();

        updateAnimatedPaletteManager();

        assertEquals(CPZ_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 3, 12));
        assertEquals(CPZ_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 3, 13));
        assertEquals(CPZ_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 3, 14));
        assertEquals(CPZ_CYCLE_2_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 3, 15));
        assertEquals(CPZ_CYCLE_3_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 15));
    }

    @Test
    void cnzBossOnlyPaletteCycleDoesNotSubmitWhenBossConditionIsFalse() {
        loadZone(Sonic2ZoneConstants.ZONE_CNZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        registry.beginFrame();

        GameServices.gameState().setCurrentBossId(0);
        updateAnimatedPaletteManager();

        assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 2));
        assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 14));
        assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 15));
    }

    @Test
    void cnzBossOnlyPaletteCycleDoesSubmitWhenBossConditionIsTrue() {
        loadZone(Sonic2ZoneConstants.ZONE_CNZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        registry.beginFrame();

        GameServices.gameState().setCurrentBossId(1);
        updateAnimatedPaletteManager();

        assertEquals(CNZ_BOSS_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 2));
        assertEquals(CNZ_BOSS_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 3));
        assertEquals(CNZ_BOSS_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 4));
        assertEquals(CNZ_BOSS_CYCLE_2_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 14));
        assertEquals(CNZ_BOSS_CYCLE_3_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 1, 15));
    }

    @Test
    void wfzPaletteCyclesSubmitAllRomTargetSlots() {
        loadZone(Sonic2ZoneConstants.ZONE_WFZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        registry.beginFrame();

        GameServices.gameState().setWfzFireToggle(false);
        updateAnimatedPaletteManager();

        assertEquals(WFZ_FIRE_BELT_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 7));
        assertEquals(WFZ_FIRE_BELT_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 8));
        assertEquals(WFZ_FIRE_BELT_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 9));
        assertEquals(WFZ_FIRE_BELT_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 10));
        assertEquals(WFZ_CYCLE_1_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 14));
        assertEquals(WFZ_CYCLE_2_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 15));
    }

    @Test
    void wfzFireCycleResolvesFromRomFirePaletteWhenToggleIsClear() {
        loadZone(Sonic2ZoneConstants.ZONE_WFZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Palette[] livePalettes = livePalettes();
        registry.beginFrame();

        GameServices.gameState().setWfzFireToggle(false);
        updateAnimatedPaletteManager();

        clearColors(livePalettes[2], 7, 8, 9, 10, 14, 15);
        registry.resolveInto(livePalettes, null, null, null);

        byte[] fireData = romSlice(Sonic2Constants.CYCLING_PAL_WFZ_FIRE_ADDR,
                Sonic2Constants.CYCLING_PAL_WFZ_FIRE_LEN);
        byte[] wfz1Data = romSlice(Sonic2Constants.CYCLING_PAL_WFZ1_ADDR,
                Sonic2Constants.CYCLING_PAL_WFZ1_LEN);
        byte[] wfz2Data = romSlice(Sonic2Constants.CYCLING_PAL_WFZ2_ADDR,
                Sonic2Constants.CYCLING_PAL_WFZ2_LEN);

        assertColorWord(livePalettes[2], 7, fireData, 0);
        assertColorWord(livePalettes[2], 8, fireData, 2);
        assertColorWord(livePalettes[2], 9, fireData, 4);
        assertColorWord(livePalettes[2], 10, fireData, 6);
        assertColorWord(livePalettes[2], 14, wfz1Data, 0);
        assertColorWord(livePalettes[2], 15, wfz2Data, 0);
    }

    @Test
    void wfzBeltToggleUsesConveyorPaletteAndFiveFrameDelay() {
        loadZone(Sonic2ZoneConstants.ZONE_WFZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Palette[] livePalettes = livePalettes();
        GameServices.gameState().setWfzFireToggle(true);

        registry.beginFrame();
        updateAnimatedPaletteManager();

        clearColors(livePalettes[2], 7, 8, 9, 10);
        registry.resolveInto(livePalettes, null, null, null);

        byte[] beltData = romSlice(Sonic2Constants.CYCLING_PAL_WFZ_BELT_ADDR,
                Sonic2Constants.CYCLING_PAL_WFZ_BELT_LEN);
        assertColorWord(livePalettes[2], 7, beltData, 0);
        assertColorWord(livePalettes[2], 8, beltData, 2);
        assertColorWord(livePalettes[2], 9, beltData, 4);
        assertColorWord(livePalettes[2], 10, beltData, 6);

        for (int frame = 0; frame < 5; frame++) {
            registry.beginFrame();
            updateAnimatedPaletteManager();
            assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 2, 7),
                    "WFZ conveyor palette should wait five full frames before the next submission");
        }

        registry.beginFrame();
        updateAnimatedPaletteManager();

        assertEquals(WFZ_FIRE_BELT_OWNER, registry.ownerAt(PaletteSurface.NORMAL, 2, 7));
    }

    @Test
    void sharedFrameFallbackSkipsResolutionWhenTheCyclerAlreadyResolved() {
        loadZone(Sonic2ZoneConstants.ZONE_EHZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Palette[] livePalettes = livePalettes();
        registry.beginFrame();

        updateAnimatedPaletteManager();
        assertTrue(registry.hasResolvedThisFrame(),
                "EHZ cycler resolves the registry itself on its first submission frame");

        clearColors(livePalettes[1], 3, 4, 14, 15);
        com.openggf.game.palette.PaletteWriteSupport.resolvePendingFrameWrites(
                registry, GameServices.level().getCurrentLevel(), null, null);

        assertEquals(0, livePalettes[1].getColor(3).r & 0xFF,
                "fallback must not re-apply writes the cycler already resolved this frame");
    }

    @Test
    void sharedFrameFallbackResolvesSubmittedWritesWhenNoCyclerResolved() {
        loadZone(Sonic2ZoneConstants.ZONE_EHZ, 0);

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Palette[] livePalettes = livePalettes();
        registry.beginFrame();

        // Simulates an SCZ/DEZ frame: a boss-flash write is submitted but no
        // palette cycle resolves the registry (loadCycles has no SCZ/DEZ case).
        com.openggf.game.palette.PaletteWriteSupport.applyColor(
                registry, GameServices.level().getCurrentLevel(), null,
                "test.bossFlash", 200, 1, 5, 0x0EEE);
        com.openggf.game.palette.PaletteWriteSupport.resolvePendingFrameWrites(
                registry, GameServices.level().getCurrentLevel(), null, null);

        assertEquals("test.bossFlash", registry.ownerAt(PaletteSurface.NORMAL, 1, 5));
        assertEquals(255, livePalettes[1].getColor(5).r & 0xFF,
                "fallback must apply submitted writes when no cycler resolved this frame");
    }

    private static void loadZone(int zone, int act) {
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture.builder()
                .withZoneAndAct(zone, act)
                .build();
    }

    private static void updateAnimatedPaletteManager() {
        assertNotNull(GameServices.level().getAnimatedPaletteManager(),
                "Expected Sonic 2 level animation manager to be installed by the production load path");
        GameServices.level().getAnimatedPaletteManager().update();
    }

    private static byte[] romSlice(int address, int length) {
        try {
            return RomByteReader.fromRom(TestEnvironment.currentRom()).slice(address, length);
        } catch (Exception e) {
            throw new AssertionError("Failed to read Sonic 2 ROM data", e);
        }
    }

    private static Palette[] livePalettes() {
        Level level = GameServices.level().getCurrentLevel();
        Palette[] palettes = new Palette[level.getPaletteCount()];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = level.getPalette(i);
        }
        return palettes;
    }

    private static void clearColors(Palette palette, int... colorIndices) {
        Palette blank = new Palette();
        for (int colorIndex : colorIndices) {
            palette.mergeColorsFrom(blank, colorIndex, colorIndex);
        }
    }

    private static void assertColorWord(Palette palette, int colorIndex, byte[] data, int dataOffset) {
        byte highByte = data[dataOffset];
        byte lowByte = data[dataOffset + 1];
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;

        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF, "Red for color " + colorIndex);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF, "Green for color " + colorIndex);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF, "Blue for color " + colorIndex);
    }
}
