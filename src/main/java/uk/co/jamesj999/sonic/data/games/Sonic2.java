package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.PlayerSpriteArtProvider;
import uk.co.jamesj999.sonic.data.SpindashDustArtProvider;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static uk.co.jamesj999.sonic.data.games.Sonic2Constants.*;

public class Sonic2 extends Game implements PlayerSpriteArtProvider, SpindashDustArtProvider {

    private final Rom rom;
    private RomByteReader romReader;
    private Sonic2ObjectPlacement objectPlacement;
    private Sonic2RingPlacement ringPlacement;
    private Sonic2RingArt ringArt;
    private Sonic2PlayerArt playerArt;
    private Sonic2DustArt dustArt;
    private static final int BG_SCROLL_TABLE_ADDR = 0x00C296;

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
        map.put(GameSound.ROLLING, SFX_ROLLING);
        return map;
    }

    @Override
    public Level loadLevel(int levelIdx) throws IOException {
        ZoneAct zoneAct = getZoneAct(levelIdx);
        ensurePlacementHelpers();
        int characterPaletteAddr = getCharacterPaletteAddr();

        int[] levelPaletteInfo = getLevelPaletteInfo(zoneAct);
        int levelPalettesAddr = levelPaletteInfo[0];
        int levelPalettesSize = levelPaletteInfo[1];

        int patternsAddr = getPatternsAddr(zoneAct);
        int chunksAddr = getChunksAddr(zoneAct);
        int blocksAddr = getBlocksAddr(zoneAct);
        int mapAddr = getTilesAddr(zoneAct);
        int collisionAddr = getCollisionMapAddr(zoneAct);
        int altCollisionAddr = getAltCollisionMapAddr(zoneAct);
        int solidTileHeightsAddr = getSolidTileHeightsAddr();
        int solidTileWidthsAddr = getSolidTileWidthsAddr();
        int solidTileAngleAddr = getSolidTileAngleAddr();
        int levelBoundariesAddr = getLevelBoundariesAddr(zoneAct);
        List<ObjectSpawn> objectSpawns = objectPlacement.load(zoneAct);
        List<RingSpawn> ringSpawns = ringPlacement.load(zoneAct);
        var ringSpriteSheet = ringArt.load();

        System.out.printf("Character palette addr: 0x%08X%n", characterPaletteAddr);
        System.out.printf("Level palettes addr: 0x%08X%n", levelPalettesAddr);
        System.out.printf("Level palettes size: %d bytes%n", levelPalettesSize);
        System.out.printf("Patterns addr: 0x%08X%n", patternsAddr);
        System.out.printf("Chunks addr: 0x%08X%n", chunksAddr);
        System.out.printf("Blocks addr: 0x%08X%n", blocksAddr);
        System.out.printf("Map/Tiles addr: 0x%08X%n", mapAddr);
        System.out.printf("Collision addr: 0x%08X%n", collisionAddr);
        System.out.printf("Alt Collision addr: 0x%08X%n", altCollisionAddr);
        System.out.printf("Solid Tile addr: 0x%08X%n", solidTileHeightsAddr);
        System.out.printf("Solid Tile Angle addr: 0x%08X%n", solidTileAngleAddr);
        System.out.printf("Level boundaries addr: 0x%08X%n", levelBoundariesAddr);

        return new Sonic2Level(rom, characterPaletteAddr, levelPalettesAddr, levelPalettesSize, patternsAddr, chunksAddr,
                blocksAddr, mapAddr, collisionAddr, altCollisionAddr, solidTileHeightsAddr, solidTileWidthsAddr,
                solidTileAngleAddr, objectSpawns, ringSpawns, ringSpriteSheet, levelBoundariesAddr);
    }

    @Override
    public int[] getBackgroundScroll(int levelIdx, int cameraX, int cameraY) {
        try {
            int zoneIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2) & 0xFF;
            int actIdx = rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2 + 1) & 0xFF;

            if (zoneIdx > 16) {
                return new int[]{0, 0};
            }

            int offset = rom.read16BitAddr(BG_SCROLL_TABLE_ADDR + zoneIdx * 2);
            int routineAddr = BG_SCROLL_TABLE_ADDR + offset;

            int d0 = cameraX;
            int d1 = cameraY;

            int ee08 = 0; // Y
            int ee0c = 0; // X

            switch (routineAddr) {
                case 0x00C2B8: // Clear/Reset
                case 0x00C2F4: // Clear/Reset
                    ee08 = 0;
                    ee0c = 0;
                    break;

                case 0x00C2E4: // Common parallax scaling
                    d0 = (d0 >>> 2) & 0xFFFF;
                    ee0c = d0;
                    d1 = (d1 >>> 3) & 0xFFFF;
                    ee08 = d1;
                    break;

                case 0x00C2F2:
                case 0x00C320:
                case 0x00C38A:
                    // RTS only. Using 0 as we start fresh.
                    break;

                case 0x00C322: // Scaled + constant, then clear base
                    d0 = (d0 >>> 3) & 0xFFFF;
                    d0 = (d0 + 0x0050) & 0xFFFF;
                    ee0c = d0;
                    ee08 = 0;
                    break;

                case 0x00C332: // Act-dependent baseline shift
                    ee08 = 0;
                    if (actIdx != 0) {
                        // Act 2: RTS, so use 0 (or previous? but we assume init so 0)
                    } else {
                        d0 = (d0 | 0x0003) & 0xFFFF;
                        d0 = (d0 - 0x0140) & 0xFFFF;
                        ee0c = d0;
                    }
                    break;

                case 0x00C364: // Clear specific bases
                    ee08 = 0;
                    ee0c = 0;
                    break;

                case 0x00C372: // Multi-layer vertical bases
                    d0 = (d0 >>> 2) & 0xFFFF;
                    ee0c = d0;
                    d1 = (d1 >>> 1) & 0xFFFF;
                    // EE10 = d1 (Ignored for now, using EE08 as primary)
                    d1 = (d1 >>> 2) & 0xFFFF;
                    ee08 = d1;
                    break;

                case 0x00C3C6: // Clear primary bases
                    ee08 = 0;
                    ee0c = 0;
                    break;

                case 0x00C38C: // Act-dependent plus MULU scale
                    if (actIdx != 0) {
                        d0 = (d0 - 0x00E0) & 0xFFFF;
                        d0 = (d0 >>> 1) & 0xFFFF;
                        ee0c = d0;
                    } else {
                        d0 = (d0 - 0x0180) & 0xFFFF;
                        ee0c = d0;
                    }
                    long mulRes = (d1 & 0xFFFFL) * 0x0119L;
                    d1 = (int) (mulRes >> 8);
                    ee08 = d1 & 0xFFFF;
                    break;

                default:
                    // Unknown routine, default to 0
                    break;
            }

            return new int[]{ ee0c, ee08 };
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    @Override
    public SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException {
        ensurePlacementHelpers();
        if (playerArt == null) {
            return null;
        }
        return playerArt.loadForCharacter(characterCode);
    }

    @Override
    public SpriteArtSet loadSpindashDustArt(String characterCode) throws IOException {
        ensurePlacementHelpers();
        if (dustArt == null) {
            return null;
        }
        return dustArt.loadForCharacter(characterCode);
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

    private ZoneAct getZoneAct(int levelIdx) throws IOException {
        int zoneIdx = Byte.toUnsignedInt(rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2));
        int actIdx = Byte.toUnsignedInt(rom.readByte(LEVEL_SELECT_ADDR + levelIdx * 2 + 1));
        return new ZoneAct(zoneIdx, actIdx);
    }

    private void ensurePlacementHelpers() throws IOException {
        if (romReader == null) {
            romReader = RomByteReader.fromRom(rom);
        }
        if (objectPlacement == null) {
            objectPlacement = new Sonic2ObjectPlacement(romReader);
        }
        if (ringPlacement == null) {
            ringPlacement = new Sonic2RingPlacement(romReader);
        }
        if (ringArt == null) {
            ringArt = new Sonic2RingArt(rom, romReader);
        }
        if (playerArt == null) {
            playerArt = new Sonic2PlayerArt(romReader);
        }
        if (dustArt == null) {
            dustArt = new Sonic2DustArt(romReader);
        }
    }

    private int getDataAddress(int zoneIdx, int entryOffset) throws IOException {
        return rom.read32BitAddr(LEVEL_DATA_DIR + zoneIdx * LEVEL_DATA_DIR_ENTRY_SIZE + entryOffset);
    }

    private int getCharacterPaletteAddr() {
        return SONIC_TAILS_PALETTE_ADDR;
    }

    private int getLevelPalettesAddr(ZoneAct zoneAct) throws IOException {
        return getLevelPaletteInfo(zoneAct)[0];
    }

    /**
     * Returns an array containing { address, size } for the level palettes.
     */
    private int[] getLevelPaletteInfo(ZoneAct zoneAct) throws IOException {
        int dataAddr = getDataAddress(zoneAct.zone(), 8);
        int paletteIndex = dataAddr >> 24;

        int entryAddr = LEVEL_PALETTE_DIR + paletteIndex * 8;
        int address = rom.read32BitAddr(entryAddr);
        int countMinus1 = rom.read16BitAddr(entryAddr + 6);
        int size = (countMinus1 + 1) * 4;

        return new int[]{address, size};
    }

    private int getBlocksAddr(ZoneAct zoneAct) throws IOException {
        return getDataAddress(zoneAct.zone(), 8) & 0xFFFFFF;
    }

    private int getChunksAddr(ZoneAct zoneAct) throws IOException {
        return getDataAddress(zoneAct.zone(), 4) & 0xFFFFFF;
    }

    private int getPatternsAddr(ZoneAct zoneAct) throws IOException {
        return getDataAddress(zoneAct.zone(), 0) & 0xFFFFFF;
    }

    /*
        FIXME: Level Layout, not 'tiles'
     */
    private int getTilesAddr(ZoneAct zoneAct) throws IOException {
        // The address at LEVEL_LAYOUT_DIR_ADDR_LOC points to another pointer table.
        // We read this base address first.
        int levelLayoutDirAddr = rom.read32BitAddr(LEVEL_LAYOUT_DIR_ADDR_LOC);

        // Then, we use the zone and act to find an offset within that table.
        // The table is structured with 4 bytes per zone, and 2 bytes per act.
        int levelOffsetAddr = levelLayoutDirAddr + (zoneAct.zone() * 4) + (zoneAct.act() * 2);

        // The value at this address is a 16-bit offset relative to the start of the table.
        int levelOffset = rom.read16BitAddr(levelOffsetAddr);

        // The final address is the base address of the table plus the offset.
        return levelLayoutDirAddr + levelOffset;
    }

    private int getCollisionMapAddr(ZoneAct zoneAct) throws IOException {
        int zoneIdxLoc = COLLISION_LAYOUT_DIR_ADDR + zoneAct.zone() * 4;
        return rom.read32BitAddr(zoneIdxLoc);
    }

    private int getAltCollisionMapAddr(ZoneAct zoneAct) throws IOException {
        int zoneIdxLoc = ALT_COLLISION_LAYOUT_DIR_ADDR + zoneAct.zone() * 4;
        return rom.read32BitAddr(zoneIdxLoc);
    }

    private int getLevelBoundariesAddr(ZoneAct zoneAct) {
        // 8 bytes per entry. 2 entries per zone (usually 2 acts, sometimes 1).
        // It seems standard Sonic 2 stride is based on 2 acts per zone for this table.
        // Or it's a linear table indexed by (Zone * 2 + Act).
        return LEVEL_BOUNDARIES_ADDR + ((zoneAct.zone() * 2) + zoneAct.act()) * 8;
    }
}
