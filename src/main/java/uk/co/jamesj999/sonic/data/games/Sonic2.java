package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.Level;

import java.io.IOException;
import java.util.List;

public class Sonic2 extends Game {
    private static final int DEFAULT_ROM_SIZE = 0x100000;  // 1MB
    private static final int DEFAULT_LEVEL_LAYOUT_DIR_ADDR = 0x045A80;
    private static final int LEVEL_LAYOUT_DIR_ADDR_LOC = 0xE46E;
    private static final int LEVEL_LAYOUT_DIR_SIZE = 68;
    private static final int LEVEL_SELECT_ADDR = 0x9454;
    private static final int LEVEL_DATA_DIR = 0x42594;
    private static final int LEVEL_DATA_DIR_ENTRY_SIZE = 16;
    private static final int LEVEL_PALETTE_DIR = 0x2782;
    private static final int SONIC_TAILS_PALETTE_ADDR = 0x29E2;
    private static final int COLLISION_LAYOUT_DIR_ADDR = 0x49E8;
    private static final int ALT_COLLISION_LAYOUT_DIR_ADDR = 0x4A2C;
    private static final int SOLID_TILE_VERTICAL_MAP_ADDR = 0x42E50;
    private static final int SOLID_TILE_HORIZONTAL_MAP_ADDR = 0x43E50;
    public static final int SOLID_TILE_MAP_SIZE = 0x1000;
    private static final int SOLID_TILE_ANGLE_ADDR = 0x42D50;
    public static final int SOLID_TILE_ANGLE_SIZE = 0x100; //TODO are we sure?

    private static final int[][] START_POSITIONS = {
            {0x0060, 0x028F}, // 0 Emerald Hill 1   (EHZ_1.bin)
            {0x0060, 0x02AF}, // 1 Emerald Hill 2   (EHZ_2.bin)
            {0x0000, 0x0000}, // 2 Unused           (e.g. HPZ / WZ / etc. – not wired in final game)
            {0x0000, 0x0000}, // 3 Unused
            {0x0060, 0x01EC}, // 4 Chemical Plant 1 (CPZ_1.bin)
            {0x0000, 0x0000}, // 5 Chemical Plant 2 (CPZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 6 Aquatic Ruin 1   (ARZ_1.bin – not fetched)
            {0x0000, 0x0000}, // 7 Aquatic Ruin 2   (ARZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 8 Casino Night 1   (CNZ_1.bin – not fetched)
            {0x0000, 0x0000}, // 9 Casino Night 2   (CNZ_2.bin – not fetched)
            {0x0060, 0x03EF}, // 10 Hill Top 1      (HTZ_1.bin)
            {0x0000, 0x0000}, // 11 Hill Top 2      (HTZ_2.bin – not fetched)
            {0x0060, 0x06AC}, // 12 Mystic Cave 1   (MCZ_1.bin)
            {0x0000, 0x0000}, // 13 Mystic Cave 2   (MCZ_2.bin – not fetched)
            {0x0060, 0x06AC}, // 14 Oil Ocean 1     (OOZ_1.bin)
            {0x0000, 0x0000}, // 15 Oil Ocean 2     (OOZ_2.bin – not fetched)
            {0x0060, 0x028C}, // 16 Metropolis 1    (MTZ_1.bin)
            {0x0000, 0x0000}, // 17 Metropolis 2    (MTZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 18 Metropolis 3    (MTZ_3.bin – not fetched)
            {0x0000, 0x0000}, // 19 Unused
            {0x0120, 0x0070}, // 20 Sky Chase 1     (SCZ.bin)
            {0x0000, 0x0000}, // 21 Unused
            {0x0060, 0x04CC}, // 22 Wing Fortress 1 (WFZ_1.bin)
            {0x0000, 0x0000}, // 23 Unused
            {0x0060, 0x012D}, // 24 Death Egg 1     (DEZ_1.bin)
            {0x0000, 0x0000}, // 25 Unused
            {0x0000, 0x0000}, // 26 Special Stage
    };

    private final Rom rom;

    public Sonic2(Rom rom) {
        this.rom = rom;
    }

    @Override
    public boolean isCompatible() {
        try {
            String name = rom.readDomesticName();
            return name.contains("SONIC THE") && name.contains("HEDGEHOG 2");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getIdentifier() {
        return "Sonic2";
    }

    @Override
    public List<String> getTitleCards() {
        return List.of(
                "Emerald Hill Zone - Act 1", "Emerald Hill Zone - Act 2",
                "Chemical Plant Zone - Act 1", "Chemical Plant Zone - Act 2",
                "Aquatic Ruins Zone - Act 1", "Aquatic Ruins Zone - Act 2",
                "Casino Night Zone - Act 1", "Casino Night Zone - Act 2",
                "Hill Top Zone - Act 1", "Hill Top Zone - Act 2",
                "Mystic Cave Zone - Act 1", "Mystic Zone - Act 2",
                "Oil Ocean Zone - Act 1", "Oil Ocean Zone - Act 2",
                "Metropolis Zone - Act 1", "Metropolis Zone - Act 2", "Metropolis Zone - Act 3",
                "Sky Chase Zone - Act 1", "Wing Fortress Zone - Act 1",
                "Death Egg Zone - Act 1"
        );
    }

    @Override
    public Level loadLevel(int levelIdx) throws IOException {
        int characterPaletteAddr = getCharacterPaletteAddr();
        int levelPalettesAddr = getLevelPalettesAddr(levelIdx);
        int patternsAddr = getPatternsAddr(levelIdx);
        int chunksAddr = getChunksAddr(levelIdx);
        int blocksAddr = getBlocksAddr(levelIdx);
        int mapAddr = getTilesAddr(levelIdx);
        int collisionAddr = getCollisionMapAddr(levelIdx);
        int altCollisionAddr = getAltCollisionMapAddr(levelIdx);
        int solidTileHeightsAddr = getSolidTileHeightsAddr();
        int solidTileWidthsAddr = getSolidTileWidthsAddr();
        int solidTileAngleAddr = getSolidTileAngleAddr();
        int levelWidth = getLevelWidth(levelIdx);
        int levelHeight = getLevelHeight(levelIdx);

        System.out.printf("Character palette addr: 0x%08X%n", characterPaletteAddr);
        System.out.printf("Level palettes addr: 0x%08X%n", levelPalettesAddr);
        System.out.printf("Patterns addr: 0x%08X%n", patternsAddr);
        System.out.printf("Chunks addr: 0x%08X%n", chunksAddr);
        System.out.printf("Blocks addr: 0x%08X%n", blocksAddr);
        System.out.printf("Map/Tiles addr: 0x%08X%n", mapAddr);
        System.out.printf("Collision addr: 0x%08X%n", collisionAddr);
        System.out.printf("Alt Collision addr: 0x%08X%n", altCollisionAddr);
        System.out.printf("Solid Tile addr: 0x%08X%n", solidTileHeightsAddr);
        System.out.printf("Solid Tile Angle addr: 0x%08X%n", solidTileAngleAddr);
        System.out.printf("Level Width: %d, Level Height: %d%n", levelWidth, levelHeight);

        return new Sonic2Level(rom, characterPaletteAddr, levelPalettesAddr, patternsAddr, chunksAddr, blocksAddr, mapAddr, collisionAddr, altCollisionAddr, solidTileHeightsAddr, solidTileWidthsAddr, solidTileAngleAddr, levelWidth, levelHeight);
    }

    private int getLevelWidth(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        long offset = LEVEL_DATA_DIR + zoneIdx * LEVEL_DATA_DIR_ENTRY_SIZE + 12;
        return rom.read16BitAddr(offset);
    }

    private int getLevelHeight(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        long offset = LEVEL_DATA_DIR + zoneIdx * LEVEL_DATA_DIR_ENTRY_SIZE + 14;
        return rom.read16BitAddr(offset);
    }

    private int getSolidTileHeightsAddr() {
        return SOLID_TILE_VERTICAL_MAP_ADDR;
    }

    private int getSolidTileWidthsAddr() {
        return SOLID_TILE_HORIZONTAL_MAP_ADDR;
    }

    private int getSolidTileAngleAddr() {
        return SOLID_TILE_ANGLE_ADDR;
    }

    @Override
    public boolean canRelocateLevels() {
        return true;
    }

    @Override
    public boolean canSave() {
        return false;
    }

    @Override
    public boolean relocateLevels(boolean unsafe) throws IOException {
        return false;
    }

    @Override
    public boolean save(int levelIdx, Level level) {
        return false;
    }

    private int getDataAddress(int zoneIdx, int entryOffset) throws IOException {
        return rom.read32BitAddr(LEVEL_DATA_DIR + zoneIdx * LEVEL_DATA_DIR_ENTRY_SIZE + entryOffset);
    }

    private int getCharacterPaletteAddr() {
        return SONIC_TAILS_PALETTE_ADDR;
    }

    private int getLevelPalettesAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        int dataAddr = getDataAddress(zoneIdx, 8);
        int paletteIndex = dataAddr >> 24;
        return rom.read32BitAddr(LEVEL_PALETTE_DIR + paletteIndex * 8);
    }

    private int getBlocksAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        return getDataAddress(zoneIdx, 8) & 0xFFFFFF;
    }

    private int getChunksAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        return getDataAddress(zoneIdx, 4) & 0xFFFFFF;
    }

    private int getPatternsAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        return getDataAddress(zoneIdx, 0) & 0xFFFFFF;
    }

    /*
        FIXME: Level Layout, not 'tiles'
     */
    private int getTilesAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        int actIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2 + 1) & 0xFF;

        // The address at LEVEL_LAYOUT_DIR_ADDR_LOC points to another pointer table.
        // We read this base address first.
        int levelLayoutDirAddr = rom.read32BitAddr(LEVEL_LAYOUT_DIR_ADDR_LOC);

        // Then, we use the zone and act to find an offset within that table.
        // The table is structured with 4 bytes per zone, and 2 bytes per act.
        int levelOffsetAddr = levelLayoutDirAddr + (zoneIdx * 4) + (actIdx * 2);

        // The value at this address is a 16-bit offset relative to the start of the table.
        int levelOffset = rom.read16BitAddr(levelOffsetAddr);

        // The final address is the base address of the table plus the offset.
        return levelLayoutDirAddr + levelOffset;
    }

    private int getCollisionMapAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        int zoneIdxLoc = COLLISION_LAYOUT_DIR_ADDR + zoneIdx * 4;
        return rom.read32BitAddr(zoneIdxLoc);
    }

    private int getAltCollisionMapAddr(int levelIdx) throws IOException {
        int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
        int zoneIdxLoc = ALT_COLLISION_LAYOUT_DIR_ADDR + zoneIdx * 4;
        return rom.read32BitAddr(zoneIdxLoc);
    }
}