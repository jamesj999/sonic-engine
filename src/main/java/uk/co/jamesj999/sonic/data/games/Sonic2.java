package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.Level;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static uk.co.jamesj999.sonic.data.games.Sonic2Constants.*;

public class Sonic2 extends Game {

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
    public int getMusicId(int levelIdx) throws IOException {
        switch (levelIdx) {
            case 0: // Emerald Hill 1
            case 1: // Emerald Hill 2
                return 0x81;
            case 2: // Chemical Plant 1
            case 3: // Chemical Plant 2
                return 0x8C;
            case 4: // Aquatic Ruin 1
            case 5: // Aquatic Ruin 2
                return 0x86;
            case 6: // Casino Night 1
            case 7: // Casino Night 2
                return 0x83;
            case 8: // Hill Top 1
            case 9: // Hill Top 2
                return 0x94;
            case 10: // Mystic Cave 1
            case 11: // Mystic Cave 2
                return 0x84;
            case 12: // Oil Ocean 1
            case 13: // Oil Ocean 2
                return 0x8F;
            case 14: // Metropolis 1
            case 15: // Metropolis 2
            case 16: // Metropolis 3
                return 0x82;
            case 17: // Sky Chase
                return 0x8E;
            case 18: // Wing Fortress
                return 0x90;
            case 19: // Death Egg
                return 0x87;
            default:
                // Fallback to original logic for unknown levels (e.g. 2P)
                int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
                return rom.readByte(MUSIC_PLAYLIST_ADDR + zoneIdx) & 0xFF;
        }
    }

    @Override
    public Map<GameSound, Integer> getSoundMap() {
        Map<GameSound, Integer> map = new HashMap<>();
        map.put(GameSound.JUMP, SFX_JUMP);
        map.put(GameSound.RING_LEFT, SFX_RING_LEFT);
        map.put(GameSound.RING_RIGHT, SFX_RING_RIGHT);
        map.put(GameSound.SPINDASH_CHARGE, SFX_SPINDASH_CHARGE);
        map.put(GameSound.SPINDASH_RELEASE, SFX_SPINDASH_RELEASE);
        map.put(GameSound.SKID, SFX_SKID);
        map.put(GameSound.DEATH, SFX_DEATH);
        map.put(GameSound.BADNIK_HIT, SFX_BADNIK_HIT);
        map.put(GameSound.CHECKPOINT, SFX_CHECKPOINT);
        map.put(GameSound.SPIKE_HIT, SFX_SPIKE_HIT);
        map.put(GameSound.SPRING, SFX_SPRING);
        return map;
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

        return new Sonic2Level(rom, characterPaletteAddr, levelPalettesAddr, patternsAddr, chunksAddr, blocksAddr, mapAddr, collisionAddr, altCollisionAddr, solidTileHeightsAddr, solidTileWidthsAddr, solidTileAngleAddr);
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
