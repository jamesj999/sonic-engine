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
    private static final int LEVEL_DATA_DIR_ENTRY_SIZE = 12;
    private static final int LEVEL_PALETTE_DIR = 0x2782;
    private static final int SONIC_TAILS_PALETTE_ADDR = 0x29E2;
    private static final int COLLISION_INDEX_ADDR = 0x49E8;

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
        int collisionAddr = getCollisionAddr(levelIdx);

        System.out.printf("Character palette addr: 0x%08X%n", characterPaletteAddr);
        System.out.printf("Level palettes addr: 0x%08X%n", levelPalettesAddr);
        System.out.printf("Patterns addr: 0x%08X%n", patternsAddr);
        System.out.printf("Chunks addr: 0x%08X%n", chunksAddr);
        System.out.printf("Blocks addr: 0x%08X%n", blocksAddr);
        System.out.printf("Map/Tiles addr: 0x%08X%n", mapAddr);
        System.out.printf("Collision addr: 0x%08X%n", collisionAddr);

        return new Sonic2Level(rom, characterPaletteAddr, levelPalettesAddr, patternsAddr, chunksAddr, blocksAddr, mapAddr, collisionAddr);
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

    private int getDataAddress(int levelIdx, int entryOffset) throws IOException {
        int levelDataIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2);
        return rom.read32BitAddr(LEVEL_DATA_DIR + levelDataIdx * LEVEL_DATA_DIR_ENTRY_SIZE + entryOffset);
    }

    private int getCollisionDataAddress(int levelIdx) throws IOException {
        int levelDataIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2);
        return rom.read32BitAddr(levelDataIdx);
    }

    private int getCharacterPaletteAddr() {
        return SONIC_TAILS_PALETTE_ADDR;
    }

    private int getLevelPalettesAddr(int levelIdx) throws IOException {
        int dataAddr = getDataAddress(levelIdx, 8);
        int paletteIndex = dataAddr >> 24;
        return rom.read32BitAddr(LEVEL_PALETTE_DIR + paletteIndex * 8);
    }

    private int getBlocksAddr(int levelIdx) throws IOException {
        return getDataAddress(levelIdx, 8) & 0xFFFFFF;
    }

    private int getChunksAddr(int levelIdx) throws IOException {
        return getDataAddress(levelIdx, 4) & 0xFFFFFF;
    }

    private int getPatternsAddr(int levelIdx) throws IOException {
        return getDataAddress(levelIdx, 0) & 0xFFFFFF;
    }

    private int getTilesAddr(int levelIdx) throws IOException {
        int zoneIdxLoc = LEVEL_SELECT_ADDR + levelIdx * 2;
        int zoneIdx = rom.readByte(zoneIdxLoc);

        int actIdxLoc = zoneIdxLoc + 1;
        int actIdx = rom.readByte(actIdxLoc);

        int levelLayoutDirAddr = rom.read32BitAddr(LEVEL_LAYOUT_DIR_ADDR_LOC);

        int levelOffset = rom.read16BitAddr(levelLayoutDirAddr + zoneIdx * 4 + actIdx * 2);
        return levelLayoutDirAddr + levelOffset;
    }

    private int getCollisionAddr(int levelIdx) throws IOException {
        int zoneIdxLoc = COLLISION_INDEX_ADDR + levelIdx * 2;

        int collisionAddr = rom.read32BitAddr(zoneIdxLoc);

        return collisionAddr;
    }
}