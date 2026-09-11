package com.openggf.game.sonic1;

import com.openggf.audio.GameSound;
import com.openggf.data.AnimatedPaletteProvider;
import com.openggf.data.AnimatedPatternProvider;
import com.openggf.data.Game;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Level;
import com.openggf.level.LevelData;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.sprites.art.SpriteArtSet;

import com.openggf.level.resources.PlcParser;
import com.openggf.level.resources.PlcParser.PlcEntry;

import java.io.IOException;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Game implementation for Sonic the Hedgehog 1 (Mega Drive/Genesis).
 *
 * <p>Loads levels from the original Sonic 1 ROM using per-zone level headers,
 * Nemesis/Enigma/Kosinski compressed art data, and plain binary layouts.
 *
 * <p>Level indices for Sonic 1 start at 0x80 (see {@link LevelData}).
 * Zone = (levelIdx - 0x80) / 3, Act = (levelIdx - 0x80) % 3.
 */
public class Sonic1 extends Game implements PlayerSpriteArtProvider, AnimatedPatternProvider, AnimatedPaletteProvider {
    private static final Logger LOG = Logger.getLogger(Sonic1.class.getName());

    private static final int S1_LEVEL_INDEX_BASE = 0x80;
    private static final int ACTS_PER_ZONE = 3;
    // Number of act slots in ROM tables (Level_Index, LevelSizeArray, ObjPos_Index) per zone
    private static final int ACT_SLOTS_PER_ZONE = 4;
    private static final int S1_LEVEL_INDEX_SBZ3 = 0x91;
    private static final int S1_LEVEL_INDEX_FINAL = 0x92;
    private static final int S1_LEVEL_INDEX_ENDING_FLOWERS = 0x95;
    private static final int S1_LEVEL_INDEX_ENDING_NO_EMERALDS = 0x96;
    // ROM: plcid_Ending in _inc/Pattern Load Cues.asm
    private static final int S1_PLCID_ENDING = 0x1C;
    private static final int DISASM_SONIC_PALID = 3;
    private static final int DISASM_PALID_SBZ3 = 0x0C;
    private static final int DISASM_PALID_SBZ2 = 0x0E;

    private final Rom rom;
    private RomByteReader romReader;
    private Sonic1ObjectPlacement objectPlacement;
    private Sonic1PlayerArt playerArt;
    private Sonic1RingPlacement ringPlacement;
    private Sonic1RingArt ringArt;

    public Sonic1(Rom rom) {
        this.rom = rom;
    }

    @Override
    public Rom getRom() {
        return rom;
    }

    @Override
    public boolean isCompatible() {
        try {
            String name = rom.readDomesticName();
            return name != null && name.contains("SONIC THE")
                    && !name.contains("HEDGEHOG 2") && !name.contains("HEDGEHOG 3");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getIdentifier() {
        return "Sonic1";
    }

    @Override
    public List<String> getTitleCards() {
        return List.of(
                "Green Hill Zone - Act 1", "Green Hill Zone - Act 2", "Green Hill Zone - Act 3",
                "Marble Zone - Act 1", "Marble Zone - Act 2", "Marble Zone - Act 3",
                "Spring Yard Zone - Act 1", "Spring Yard Zone - Act 2", "Spring Yard Zone - Act 3",
                "Labyrinth Zone - Act 1", "Labyrinth Zone - Act 2", "Labyrinth Zone - Act 3",
                "Star Light Zone - Act 1", "Star Light Zone - Act 2", "Star Light Zone - Act 3",
                "Scrap Brain Zone - Act 1", "Scrap Brain Zone - Act 2", "Scrap Brain Zone - Act 3",
                "Final Zone",
                "Ending Sequence - Flowers",
                "Ending Sequence - No Emeralds");
    }

    @Override
    public Level loadLevel(int levelIdx) throws IOException {
        int zone;
        int act;
        if (levelIdx >= S1_LEVEL_INDEX_BASE) {
            int s1Idx = levelIdx - S1_LEVEL_INDEX_BASE;
            zone = s1Idx / ACTS_PER_ZONE;
            act = s1Idx % ACTS_PER_ZONE;
        } else {
            zone = 0;
            act = 0;
        }
        int[] romZoneAct = resolveRomZoneAct(levelIdx, zone, act);
        zone = romZoneAct[0];
        act = romZoneAct[1];

        ensureHelpers();

        // Read LevelHeaders (16 bytes per zone)
        int headerAddr = Sonic1Constants.LEVEL_HEADERS_ADDR + zone * 16;

        // Bytes 0-3: (PLC_ID << 24) | gfx_address
        // The top byte is the primary PLC ID; the lower 3 bytes are unused by us
        // (they point to the last level gfx file, but we read all sources from the PLC).
        int headerWord0 = rom.read32BitAddr(headerAddr);
        int plcId = (headerWord0 >> 24) & 0xFF;

        // Bytes 4-7: (PLC2_ID << 24) | 16x16_chunks_address (Enigma compressed)
        int headerWord1 = rom.read32BitAddr(headerAddr + 4);
        int plc2Id = (headerWord1 >> 24) & 0xFF;
        int chunksAddr = headerWord1 & 0xFFFFFF;

        // Bytes 8-11: 256x256 block mappings address (Kosinski compressed)
        int blocksAddr = rom.read32BitAddr(headerAddr + 8);

        // Byte 15: palette index
        int headerPaletteId = rom.readByte(headerAddr + 15) & 0xFF;
        int paletteId = resolveLevelPaletteId(zone, act, headerPaletteId);

        // Read pattern load cues from ArtLoadCues.
        // For normal levels, we combine primary + secondary cues.
        // Ending is special: GM_Ending uses QuickPLC(plcid_Ending), while LevelHeaders keeps plc1/plc2 as 0.
        List<PlcEntry> patternCues = new ArrayList<>();
        if (zone == Sonic1Constants.ZONE_ENDZ) {
            patternCues.addAll(readPatternLoadCues(S1_PLCID_ENDING));
        } else {
            patternCues.addAll(readPatternLoadCues(plcId));
            // ROM LevelDataLoad skips AddPLC when plc2==0.
            if (plc2Id != 0) {
                patternCues.addAll(readPatternLoadCues(plc2Id));
            }
        }

        // Get collision index for this zone
        int collisionIndexAddr = getCollisionIndexAddr(zone);

        // Get layout addresses from Level_Index
        int[] layoutAddrs = getLayoutAddresses(zone, act);
        int fgLayoutAddr = layoutAddrs[0];
        int bgLayoutAddr = layoutAddrs[1];

        // Get boundaries
        int[] boundaries = loadBoundaries(zone, act);

        // Get palette addresses from PalPointers table.
        // The Sonic palette ID differs between ROM revisions: the disassembly defines
        // palid_Sonic=3, but REV01 removed the Level Select entry, shifting everything
        // down by 1 (Sonic=2, GHZ=3, etc.). The level headers still reference the OLD IDs.
        // We detect the Sonic palette position and adjust the level palette ID to match.
        int sonicPaletteId = findSonicPaletteId();
        int sonicPaletteAddr = getPaletteDataAddr(sonicPaletteId);
        // Adjust level palette ID: header uses disassembly IDs (Sonic=3 based),
        // so offset by the difference between expected and actual Sonic position.
        int adjustedPaletteId = sonicPaletteId + (paletteId - DISASM_SONIC_PALID);
        int levelPaletteAddr = getPaletteDataAddr(adjustedPaletteId);
        LOG.info("Palette addresses: sonic(id=" + sonicPaletteId + ")=0x" +
                Integer.toHexString(sonicPaletteAddr) +
                " level(headerId=" + headerPaletteId + " effective=" + paletteId +
                " adjusted=" + adjustedPaletteId +
                ")=0x" + Integer.toHexString(levelPaletteAddr));

        // Load object spawns, then extract ring objects into RingSpawn records
        List<ObjectSpawn> allObjects = objectPlacement.load(zone, act);
        Sonic1RingPlacement.Result ringResult = ringPlacement.extract(allObjects);
        List<RingSpawn> rings = ringResult.rings();
        List<ObjectSpawn> objects = ringResult.remainingObjects();

        // Load ring sprite sheet
        RingSpriteSheet ringSpriteSheet = ringArt.load();

        LOG.info("Loading Sonic 1 level: zone=" + zone + " act=" + act +
                " plcId=" + plcId + " plc2Id=" + plc2Id +
                " patternCues=" + patternCues.size() +
                " chunks=0x" + Integer.toHexString(chunksAddr) +
                " blocks=0x" + Integer.toHexString(blocksAddr) +
                " rings=" + rings.size() + " objects=" + objects.size());

        Sonic1Level level = new Sonic1Level(rom, zone, sonicPaletteId, adjustedPaletteId,
                patternCues, chunksAddr, blocksAddr,
                fgLayoutAddr, bgLayoutAddr, collisionIndexAddr,
                Sonic1Constants.COLLISION_ARRAY_NORMAL_ADDR,
                Sonic1Constants.COLLISION_ARRAY_ROTATED_ADDR,
                Sonic1Constants.ANGLE_MAP_ADDR,
                allObjects, rings, ringSpriteSheet, boundaries);
        // Pass ring spawn mapping so Sonic1ObjectRegistry can create Sonic1RingInstance
        // with the correct expanded ring positions when InitObjectManager runs.
        level.setRingSpawnMapping(ringResult.ringSpawnMapping());
        return level;
    }

    @Override
    public int getMusicId(int levelIdx) throws IOException {
        // Sonic 1 level indices are 0x80-based, 3 acts per zone
        // Zone = (levelIdx - 0x80) / 3, except special cases
        int offset = levelIdx - 0x80;
        if (offset < 0) return 0;
        // SBZ Act 3 (level 0x91) uses SBZ music in-game
        if (levelIdx == 0x91) return Sonic1Music.SBZ.id;
        // Final Zone (level 0x92)
        if (levelIdx == 0x92) return Sonic1Music.FZ.id;
        // Ending sequence variants (level 0x95 / 0x96)
        if (levelIdx == S1_LEVEL_INDEX_ENDING_FLOWERS
                || levelIdx == S1_LEVEL_INDEX_ENDING_NO_EMERALDS) {
            return Sonic1Music.ENDING.id;
        }
        int zone = offset / 3;
        return switch (zone) {
            case 0 -> Sonic1Music.GHZ.id;
            case 1 -> Sonic1Music.LZ.id;
            case 2 -> Sonic1Music.MZ.id;
            case 3 -> Sonic1Music.SLZ.id;
            case 4 -> Sonic1Music.SYZ.id;
            case 5 -> Sonic1Music.SBZ.id;
            default -> 0;
        };
    }

    @Override
    public Map<GameSound, Integer> getSoundMap() {
        return new Sonic1AudioProfile().getSoundMap();
    }

    @Override
    public int[] getBackgroundScroll(int levelIdx, int cameraX, int cameraY) {
        // Compatibility implementation only. Runtime S1 parallax is owned by
        // Sonic1ScrollHandlerProvider handlers and is restored through
        // ParallaxManager; LevelFrameRuntimeUpdater does not query this API.
        return new int[]{0, 0};
    }

    @Override
    public boolean canRelocateLevels() {
        return false;
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

    // ===== AnimatedPatternProvider =====

    @Override
    public AnimatedPatternManager loadAnimatedPatternManager(Level level, int zoneIndex) throws IOException {
        if (level == null) return null;
        ensureHelpers();
        return new Sonic1PatternAnimator(romReader, level, zoneIndex);
    }

    // ===== AnimatedPaletteProvider =====

    @Override
    public AnimatedPaletteManager loadAnimatedPaletteManager(Level level, int zoneIndex) throws IOException {
        if (level == null) return null;
        return new Sonic1PaletteCycler(level, zoneIndex);
    }

    // ===== Private helpers =====

    @Override
    public SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException {
        ensureHelpers();
        if (playerArt == null) {
            return null;
        }
        return playerArt.loadForCharacter(characterCode);
    }

    private void ensureHelpers() throws IOException {
        if (romReader == null) {
            romReader = RomByteReader.fromRom(rom);
        }
        if (objectPlacement == null) {
            objectPlacement = new Sonic1ObjectPlacement(romReader);
        }
        if (playerArt == null) {
            playerArt = new Sonic1PlayerArt(romReader);
        }
        if (ringPlacement == null) {
            ringPlacement = new Sonic1RingPlacement();
        }
        if (ringArt == null) {
            ringArt = new Sonic1RingArt(rom);
        }
    }

    /**
     * Reads pattern load cue entries from the ArtLoadCues table in ROM.
     *
     * <p>The ArtLoadCues table is a list of word offsets indexed by PLC ID.
     * Each PLC list starts with a word (entry_count - 1), followed by 6-byte entries:
     * {@code dc.l rom_address, dc.w vram_byte_offset}.
     * The VRAM byte offset is divided by 0x20 to get the tile index.
     *
     * <p>Returns all entries for the given PLC ID. The caller (Sonic1Level) filters
     * to only contiguous level tile entries.
     */
    private List<PlcEntry> readPatternLoadCues(int plcId) throws IOException {
        PlcParser.PlcDefinition definition = PlcParser.parse(rom, Sonic1Constants.ART_LOAD_CUES_ADDR, plcId);
        LOG.fine("PLC " + plcId + ": " + definition.entries().size() + " entries");
        return new ArrayList<>(definition.entries());
    }

    /**
     * Gets layout addresses (FG and BG) from the Level_Index table.
     *
     * <p>The Level_Index table has word-offsets relative to its own base.
     * Each zone has 4 act slots, each act slot has 3 word entries (6 bytes):
     * FG offset, BG offset, and a third (unused).
     */
    private int[] getLayoutAddresses(int zone, int act) throws IOException {
        int baseAddr = Sonic1Constants.LEVEL_INDEX_ADDR;
        int entryOffset = (zone * ACT_SLOTS_PER_ZONE + act) * 6;

        int fgOffset = rom.read16BitAddr(baseAddr + entryOffset);
        int bgOffset = rom.read16BitAddr(baseAddr + entryOffset + 2);

        return new int[]{baseAddr + fgOffset, baseAddr + bgOffset};
    }

    /**
     * Gets the collision index address for a zone.
     * Sonic 1 uses per-zone uncompressed collision index tables.
     */
    private int getCollisionIndexAddr(int zone) {
        return switch (zone) {
            case Sonic1Constants.ZONE_GHZ -> Sonic1Constants.COL_GHZ_ADDR;
            case Sonic1Constants.ZONE_LZ -> Sonic1Constants.COL_LZ_ADDR;
            case Sonic1Constants.ZONE_MZ -> Sonic1Constants.COL_MZ_ADDR;
            case Sonic1Constants.ZONE_SLZ -> Sonic1Constants.COL_SLZ_ADDR;
            case Sonic1Constants.ZONE_SYZ -> Sonic1Constants.COL_SYZ_ADDR;
            case Sonic1Constants.ZONE_SBZ -> Sonic1Constants.COL_SBZ_ADDR;
            default -> Sonic1Constants.COL_GHZ_ADDR;
        };
    }

    /**
     * Loads level boundaries from LevelSizeArray.
     *
     * <p>The array has 4 act slots per zone. Each entry is 12 bytes (6 words):
     * unused, left boundary, right boundary, top boundary, bottom boundary, vshift.
     *
     * <p>Falls back to reasonable defaults if the read fails.
     */
    private int[] loadBoundaries(int zone, int act) {
        try {
            // 12 bytes per entry, 4 acts per zone
            int addr = Sonic1Constants.LEVEL_SIZE_ARRAY_ADDR
                    + (zone * ACT_SLOTS_PER_ZONE + act) * 12;
            // Skip word 0 (unused), read words 1-4
            int leftBound = rom.read16BitAddr(addr + 2);
            int rightBound = rom.read16BitAddr(addr + 4);
            int topBound = (short) rom.read16BitAddr(addr + 6);
            int bottomBound = (short) rom.read16BitAddr(addr + 8);
            return new int[]{leftBound, rightBound, topBound, bottomBound};
        } catch (Exception e) {
            LOG.warning("Failed to read boundaries for zone " + zone + " act " + act +
                    ", using defaults: " + e.getMessage());
            return new int[]{0, 0x3000, 0, 0x800};
        }
    }

    /**
     * Finds the Sonic character palette ID by scanning PalPointers entries.
     *
     * <p>The Sonic palette is the first entry (after the title/menu palettes) that
     * targets palette line 0 (dest=0xFB00) with exactly 32 bytes (1 palette line).
     * This handles ROM revisions where the palette ID differs (REV00=3, REV01=2).
     */
    private int findSonicPaletteId() throws IOException {
        int tableAddr = Sonic1Constants.PALETTE_TABLE_ADDR;
        // Scan entries 2-9 (skip 0=Sega, 1=Title)
        for (int id = 2; id < 10; id++) {
            int entryAddr = tableAddr + id * 8;
            int dest = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
            int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
            int byteCount = (countWord + 1) * 4;
            if (dest == 0xFB00 && byteCount == 32) {
                LOG.info("Found Sonic palette at PalPointers entry " + id);
                return id;
            }
        }
        LOG.warning("Could not find Sonic palette in PalPointers, falling back to ID 3");
        return 3;
    }

    /**
     * Gets the palette data ROM address for a given palette ID from the PalPointers table.
     *
     * <p>The PalPointers table at 0x2168 has 8-byte entries:
     * {@code dc.l sourceAddress, dc.w ramDest, dc.w (count-1)}.
     * The first longword is an absolute ROM pointer to the palette data.
     *
     * @param paletteId palette table index (e.g., 2 for Sonic in REV01, 4 for GHZ)
     */
    private int getPaletteDataAddr(int paletteId) throws IOException {
        int tableAddr = Sonic1Constants.PALETTE_TABLE_ADDR;
        return rom.read32BitAddr(tableAddr + paletteId * 8);
    }

    /**
     * Resolves engine level indices to the ROM zone/act pair.
     *
     * <p>In Sonic 1, SBZ3 is stored as {@code id_LZ act 3}, and Final Zone is
     * stored as {@code id_SBZ act 2}. The engine exposes these as dedicated
     * entries (0x91, 0x92), so we remap them before reading ROM tables.
     */
    private int[] resolveRomZoneAct(int levelIdx, int zone, int act) {
        if (levelIdx == S1_LEVEL_INDEX_SBZ3) {
            return new int[] { Sonic1Constants.ZONE_LZ, 3 };
        }
        if (levelIdx == S1_LEVEL_INDEX_FINAL) {
            return new int[] { Sonic1Constants.ZONE_SBZ, 2 };
        }
        if (levelIdx == S1_LEVEL_INDEX_ENDING_FLOWERS) {
            return new int[] { Sonic1Constants.ZONE_ENDZ, 0 };
        }
        if (levelIdx == S1_LEVEL_INDEX_ENDING_NO_EMERALDS) {
            return new int[] { Sonic1Constants.ZONE_ENDZ, 1 };
        }
        return new int[] { zone, act };
    }

    /**
     * ROM palette selection parity with {@code LevelDataLoad}:
     * <ul>
     *   <li>SBZ3 (LZ act 3): force palid_SBZ3</li>
     *   <li>SBZ2 and Final Zone (SBZ acts 1/2): force palid_SBZ2</li>
     * </ul>
     */
    private int resolveLevelPaletteId(int zone, int act, int headerPaletteId) {
        if (zone == Sonic1Constants.ZONE_LZ && act == 3) {
            return DISASM_PALID_SBZ3;
        }
        if (zone == Sonic1Constants.ZONE_SBZ && (act == 1 || act == 2)) {
            return DISASM_PALID_SBZ2;
        }
        return headerPaletteId;
    }
}
