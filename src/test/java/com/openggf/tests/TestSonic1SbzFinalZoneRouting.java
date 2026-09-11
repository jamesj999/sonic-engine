package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.Sonic1;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.levelselect.Sonic1LevelSelectConstants;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.game.sonic1.titlecard.Sonic1TitleCardMappings;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelData;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed regression tests for Sonic 1 SBZ3/Final Zone routing and palette parity.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1SbzFinalZoneRouting {
    private static final int V_PALETTE_RAM_ADDR = 0xFB00;
    private static final int DISASM_SONIC_PALID = 3;
    private static final int DISASM_PALID_SBZ3 = 0x0C;
    private static final int DISASM_PALID_SBZ2 = 0x0E;
    private static final int DISASM_PALID_ENDING = 0x13;
    private Rom rom;
    private Sonic1 sonic1;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
        sonic1 = new Sonic1(rom);
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
        GameServices.camera().resetState();
        GameServices.sprites().resetState();
    }

    @Test
    public void testSbz2UsesSbz2Palette() throws Exception {
        Level level = sonic1.loadLevel(LevelData.S1_SCRAP_BRAIN_2.getLevelIndex());
        assertEquals(Sonic1Constants.ZONE_SBZ, level.getZoneIndex(), "SBZ2 should load in SBZ ROM zone slot");

        Palette expectedLine1 = readLevelPaletteLineForDisasmId(DISASM_PALID_SBZ2);
        assertPaletteEquals(expectedLine1, level.getPalette(1));
    }

    @Test
    public void testSbz3LoadsLzAct4SlotAndSbz3Palette() throws Exception {
        Level level = sonic1.loadLevel(LevelData.S1_SCRAP_BRAIN_3.getLevelIndex());
        assertEquals(Sonic1Constants.ZONE_LZ, level.getZoneIndex(), "SBZ3 should load from LZ ROM zone slot");

        Palette expectedLine1 = readLevelPaletteLineForDisasmId(DISASM_PALID_SBZ3);
        assertPaletteEquals(expectedLine1, level.getPalette(1));
    }

    @Test
    public void testFinalZoneLoadsSbzAct3SlotAndSbz2Palette() throws Exception {
        Level level = sonic1.loadLevel(LevelData.S1_FINAL_ZONE.getLevelIndex());
        assertEquals(Sonic1Constants.ZONE_SBZ, level.getZoneIndex(), "Final Zone should load from SBZ ROM zone slot");

        Palette expectedLine1 = readLevelPaletteLineForDisasmId(DISASM_PALID_SBZ2);
        assertPaletteEquals(expectedLine1, level.getPalette(1));
    }

    @Test
    public void testEndingVariantsLoadEndZoneSlotsAndEndingPalette() throws Exception {
        Level endingFlowers = sonic1.loadLevel(LevelData.S1_ENDING_FLOWERS.getLevelIndex());
        assertEquals(Sonic1Constants.ZONE_ENDZ, endingFlowers.getZoneIndex(), "Ending flowers variant should load from ENDZ ROM slot");

        Level endingNoEmeralds = sonic1.loadLevel(LevelData.S1_ENDING_NO_EMERALDS.getLevelIndex());
        assertEquals(Sonic1Constants.ZONE_ENDZ, endingNoEmeralds.getZoneIndex(), "Ending no-emerald variant should load from ENDZ ROM slot");

        Palette[] expectedPalette = readExpectedMainPaletteAfterLoads(DISASM_PALID_ENDING);
        for (int line = 0; line < 4; line++) {
            assertPaletteEquals(expectedPalette[line], endingFlowers.getPalette(line));
            assertPaletteEquals(expectedPalette[line], endingNoEmeralds.getPalette(line));
        }
    }

    @Test
    public void testEndingVariantsPopulateLowTileRange() throws Exception {
        Level endingFlowers = sonic1.loadLevel(LevelData.S1_ENDING_FLOWERS.getLevelIndex());
        Level endingNoEmeralds = sonic1.loadLevel(LevelData.S1_ENDING_NO_EMERALDS.getLevelIndex());

        assertTrue(hasAnyVisibleTileInRange(endingFlowers, 0x00, 0x40), "Ending flowers should load visible level art into low tile indices");
        assertTrue(hasAnyVisibleTileInRange(endingNoEmeralds, 0x00, 0x40), "Ending no-emeralds should load visible level art into low tile indices");
    }

    @Test
    public void testLevelManagerUsesSbz3WaterContextWhenLoadingSbzAct3() throws Exception {
        initializeHeadlessLevelManager();
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(Sonic1ZoneConstants.ZONE_SBZ, 2);

        assertEquals(Sonic1Constants.ZONE_LZ, levelManager.getRomZoneId(), "SBZ3 map/art should come from LZ ROM slot");
        // SBZ3 is id_LZ_act4 ($0103): v_zone=id_LZ, v_act=act4. Gates ported from
        // the disassembly read that, not the logical act. (_Constants.asm:87,112)
        assertEquals(3, levelManager.getRomActId(), "SBZ3 v_act should be act4");
        assertEquals(Sonic1Constants.WATER_HEIGHT_SBZ3, GameServices.water().getWaterLevelY(Sonic1Constants.ZONE_SBZ, 2), "SBZ3 water should use SBZ3 height from ROM behavior");
    }

    @Test
    public void testLevelManagerLoadsFinalZoneWithoutUnderwaterState() throws Exception {
        initializeHeadlessLevelManager();
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(Sonic1ZoneConstants.ZONE_FZ, 0);

        assertEquals(Sonic1Constants.ZONE_SBZ, levelManager.getRomZoneId(), "Final Zone map/art should come from SBZ ROM slot");
        // id_FZ = (id_SBZ<<8)+act3 ($0502): v_zone=id_SBZ, v_act=act3
        // (_Constants.asm:113). sonic.asm:2755 gates water on v_zone=id_LZ only,
        // so FZ is dry however its v_act reads.
        assertEquals(2, levelManager.getRomActId(), "Final Zone v_act should be act3");
        assertFalse(GameServices.water().hasWater(Sonic1Constants.ZONE_SBZ, 0), "Final Zone must not be treated as water");
        // Query the pair the water system is actually keyed by, so an act remap
        // that collides FZ with SBZ3 cannot slip through as it did before.
        assertFalse(GameServices.water().hasWater(levelManager.getFeatureZoneId(), levelManager.getFeatureActId()),
                "Final Zone must not be treated as water under its own feature zone/act");
    }

    @Test
    public void testLevelSelectOrderMapsSbz3AndFinalZoneCorrectly() {
        assertEquals((Sonic1ZoneConstants.ZONE_SBZ << 8) | 2, Sonic1LevelSelectConstants.LEVEL_ORDER[17]);
        assertEquals((Sonic1ZoneConstants.ZONE_FZ << 8) | 0, Sonic1LevelSelectConstants.LEVEL_ORDER[18]);
    }

    @Test
    public void testSbz3AndFinalZoneStartPositionsMatchRomStartLocArray() throws Exception {
        int[] sbz3Start = readStartLocArrayEntry(Sonic1Constants.ZONE_LZ, 3);
        assertEquals(0x0B80, sbz3Start[0]);
        assertEquals(0x0000, sbz3Start[1]);
        assertEquals(sbz3Start[0], LevelData.S1_SCRAP_BRAIN_3.getStartXPos());
        assertEquals(sbz3Start[1], LevelData.S1_SCRAP_BRAIN_3.getStartYPos());

        int[] fzStart = readStartLocArrayEntry(Sonic1Constants.ZONE_SBZ, 2);
        assertEquals(0x2140, fzStart[0]);
        assertEquals(0x05AC, fzStart[1]);
        assertEquals(fzStart[0], LevelData.S1_FINAL_ZONE.getStartXPos());
        assertEquals(fzStart[1], LevelData.S1_FINAL_ZONE.getStartYPos());

        int[] endingFlowersStart = readStartLocArrayEntry(Sonic1Constants.ZONE_ENDZ, 0);
        assertEquals(0x0620, endingFlowersStart[0]);
        assertEquals(0x016B, endingFlowersStart[1]);
        assertEquals(endingFlowersStart[0], LevelData.S1_ENDING_FLOWERS.getStartXPos());
        assertEquals(endingFlowersStart[1], LevelData.S1_ENDING_FLOWERS.getStartYPos());

        int[] endingNoEmeraldsStart = readStartLocArrayEntry(Sonic1Constants.ZONE_ENDZ, 1);
        assertEquals(0x0EE0, endingNoEmeraldsStart[0]);
        assertEquals(0x016C, endingNoEmeraldsStart[1]);
        assertEquals(endingNoEmeraldsStart[0], LevelData.S1_ENDING_NO_EMERALDS.getStartXPos());
        assertEquals(endingNoEmeraldsStart[1], LevelData.S1_ENDING_NO_EMERALDS.getStartYPos());
    }

    @Test
    public void testTitleCardMappingsKeepSbz3DistinctFromFinalZone() {
        assertEquals(
                Sonic1TitleCardMappings.FRAME_SBZ,
                Sonic1TitleCardMappings.getZoneNameFrame(Sonic1ZoneConstants.ZONE_SBZ, 2));
        assertFalse(Sonic1TitleCardMappings.shouldHideActNumber(Sonic1ZoneConstants.ZONE_SBZ, 2));
        assertEquals(5, Sonic1TitleCardMappings.getConfigIndex(Sonic1ZoneConstants.ZONE_SBZ, 2));

        assertEquals(
                Sonic1TitleCardMappings.FRAME_FZ,
                Sonic1TitleCardMappings.getZoneNameFrame(Sonic1ZoneConstants.ZONE_FZ, 0));
        assertTrue(Sonic1TitleCardMappings.shouldHideActNumber(Sonic1ZoneConstants.ZONE_FZ, 0));
        assertEquals(6, Sonic1TitleCardMappings.getConfigIndex(Sonic1ZoneConstants.ZONE_FZ, 0));
    }

    private Palette readLevelPaletteLineForDisasmId(int disasmPaletteId) throws Exception {
        int adjustedPaletteId = adjustPaletteId(disasmPaletteId);
        int paletteEntryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + adjustedPaletteId * 8;
        int paletteDataAddr = rom.read32BitAddr(paletteEntryAddr);
        byte[] lineData = rom.readBytes(paletteDataAddr, 32);
        Palette palette = new Palette();
        palette.fromSegaFormat(lineData);
        return palette;
    }

    private int adjustPaletteId(int disasmPaletteId) throws Exception {
        int sonicPaletteId = findSonicPaletteId();
        return sonicPaletteId + (disasmPaletteId - DISASM_SONIC_PALID);
    }

    private int findSonicPaletteId() throws Exception {
        for (int id = 2; id < 10; id++) {
            int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + id * 8;
            int dest = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
            int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
            int byteCount = (countWord + 1) * 4;
            if (dest == 0xFB00 && byteCount == 32) {
                return id;
            }
        }
        return 3;
    }

    private int[] readStartLocArrayEntry(int romZone, int actSlot) throws Exception {
        int entryIndex = romZone * 4 + actSlot;
        int addr = Sonic1Constants.START_LOC_ARRAY_ADDR + entryIndex * 4;
        int x = rom.read16BitAddr(addr) & 0xFFFF;
        int y = rom.read16BitAddr(addr + 2) & 0xFFFF;
        return new int[] { x, y };
    }

    private Palette[] readExpectedMainPaletteAfterLoads(int disasmLevelPaletteId) throws Exception {
        int sonicPaletteId = findSonicPaletteId();
        int levelPaletteId = adjustPaletteId(disasmLevelPaletteId);
        Palette[] lines = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
        applyPalettePointerEntry(lines, sonicPaletteId);
        applyPalettePointerEntry(lines, levelPaletteId);
        return lines;
    }

    private void applyPalettePointerEntry(Palette[] lines, int paletteId) throws Exception {
        int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + paletteId * 8;
        int sourceAddr = rom.read32BitAddr(entryAddr);
        int destinationAddr = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
        int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
        int dataBytes = (countWord + 1) * 4;
        byte[] data = rom.readBytes(sourceAddr, dataBytes);
        int destinationOffset = destinationAddr - V_PALETTE_RAM_ADDR;

        for (int dataOffset = 0; dataOffset + 1 < data.length; dataOffset += Palette.BYTES_PER_COLOR) {
            int paletteByteOffset = destinationOffset + dataOffset;
            if (paletteByteOffset < 0) {
                continue;
            }
            int line = paletteByteOffset / Palette.PALETTE_SIZE_IN_ROM;
            if (line < 0 || line >= 4) {
                continue;
            }
            int color = (paletteByteOffset % Palette.PALETTE_SIZE_IN_ROM) / Palette.BYTES_PER_COLOR;
            if (color < 0 || color >= Palette.PALETTE_SIZE) {
                continue;
            }
            lines[line].getColor(color).fromSegaFormat(data, dataOffset);
        }
    }

    private boolean hasAnyVisibleTileInRange(Level level, int startInclusive, int endExclusive) {
        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(level.getPatternCount(), endExclusive);
        for (int i = safeStart; i < safeEnd; i++) {
            Pattern pattern = level.getPattern(i);
            if (pattern == null) {
                continue;
            }
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    if ((pattern.getPixel(x, y) & 0x0F) != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void assertPaletteEquals(Palette expected, Palette actual) {
        for (int i = 0; i < 16; i++) {
            Palette.Color e = expected.getColor(i);
            Palette.Color a = actual.getColor(i);
            assertEquals(e.r & 0xFF, a.r & 0xFF, "R mismatch at color " + i);
            assertEquals(e.g & 0xFF, a.g & 0xFF, "G mismatch at color " + i);
            assertEquals(e.b & 0xFF, a.b & 0xFF, "B mismatch at color " + i);
        }
    }

    private void initializeHeadlessLevelManager() {
        GraphicsManager.getInstance().initHeadless();

        SonicConfigurationService configService = SonicConfigurationService.getInstance();
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        Sonic sprite = new Sonic(mainCode, (short) 0x0050, (short) 0x0300);

        SpriteManager spriteManager = GameServices.sprites();
        spriteManager.addSprite(sprite);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);

        GroundSensor.setLevelManager(GameServices.level());
    }
}


