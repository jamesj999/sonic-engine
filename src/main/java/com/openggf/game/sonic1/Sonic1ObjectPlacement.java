package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.objects.ObjectSpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses Sonic 1 object placement data into {@link ObjectSpawn} records.
 *
 * <p>Sonic 1 uses a 6-byte record format per object:
 * <pre>
 *   Bytes 0-1: X position (16-bit, big-endian)
 *   Bytes 2-3: Y word: AB00 YYYY YYYY YYYY
 *              A = vertical flip (bit 15)
 *              B = horizontal flip (bit 14)
 *              Y = 12-bit Y position (bits 0-11)
 *   Byte 4:   Object ID (bit 7 = respawn tracked, bits 0-6 = object type)
 *   Byte 5:   Subtype
 * </pre>
 *
 * <p>The object position index table (ObjPos_Index) has 2 word-offsets per act,
 * with 4 act slots per zone. Each word is a relative offset from the table base.
 */
public class Sonic1ObjectPlacement {

    private static final int RECORD_SIZE = 6;
    private static final int TERMINATOR = 0xFFFF;
    private static final int CONVEYOR_PATH_COUNT = 6;
    private static final int CONVEYOR_WAYPOINT_STEP = 4;
    // 4 act slots per zone, 2 words (4 bytes) per act entry
    private static final int ACT_SLOTS_PER_ZONE = 4;
    private static final int BYTES_PER_ACT_ENTRY = 4;

    private final RomByteReader rom;

    public Sonic1ObjectPlacement(RomByteReader rom) {
        this.rom = rom;
    }

    /**
     * Loads object spawns for the given zone and act.
     *
     * @param zone Zone index (0-based)
     * @param act  Act index (0-based)
     * @return chunk-ordered (ROM layout-table order preserved within each 0x80
     *         column), immutable list of object spawns
     */
    public List<ObjectSpawn> load(int zone, int act) {
        int baseAddr = Sonic1Constants.OBJ_POS_INDEX_ADDR;
        int indexOffset = (zone * ACT_SLOTS_PER_ZONE + act) * BYTES_PER_ACT_ENTRY;
        int listOffset = rom.readU16BE(baseAddr + indexOffset);
        int listAddr = baseAddr + listOffset;

        List<ObjectSpawn> spawns = new ArrayList<>();
        int cursor = listAddr;

        while (true) {
            int x = rom.readU16BE(cursor);
            if (x == TERMINATOR) {
                break;
            }

            int yWord = rom.readU16BE(cursor + 2);
            int y = yWord & 0x0FFF;
            // Sonic 1: bits 15-14 of Y word = vflip, hflip
            int renderFlags = (yWord >> 14) & 0x3;

            int objIdByte = rom.readU8(cursor + 4);
            // Sonic 1: bit 7 of object ID byte = respawn tracked
            boolean respawnTracked = (objIdByte & 0x80) != 0;
            int objectId = objIdByte & 0x7F;

            int subtype = rom.readU8(cursor + 5);

            spawns.add(new ObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked, yWord, spawns.size()));
            cursor += RECORD_SIZE;
        }

        // ROM parity: ObjPosLoad walks the layout table in stored order within
        // each chunk-aligned ($80) spawn column (docs/s1disasm/_inc/ObjPosLoad.asm
        // OPL_MovedRight/OPL_MovedLeft scans, FindFreeObj hands out ascending
        // slots in that order). Sort by chunk only (stable), so objects the level
        // data stores slightly out of full-X order within one chunk keep their
        // ROM table order -- a strict full-X sort would re-order co-column objects
        // (e.g. SBZ2 0x72@0x1594 before 0x15@0x1590/Bomb@0x1590) and assign them
        // the wrong FindFreeObj slots. AbstractPlacementManager re-applies the
        // same chunk-granular stable order.
        spawns.sort(Comparator.comparingInt(s -> s.x() & 0xFF80));
        return List.copyOf(spawns);
    }

    public int[][] loadLzPlatformChildren(int slotIndex) {
        return loadPlatformChildren(Sonic1Constants.OBJ_POS_LZ_PLATFORM_INDEX_ADDR, slotIndex);
    }

    public int[][] loadSbzPlatformChildren(int slotIndex) {
        return loadPlatformChildren(Sonic1Constants.OBJ_POS_SBZ_PLATFORM_INDEX_ADDR, slotIndex);
    }

    public ConveyorPathData loadLzConveyorPath(int pathIndex) {
        return loadConveyorPath(Sonic1Constants.LZ_CONVEYOR_PATH_TABLE_ADDR, pathIndex);
    }

    public ConveyorPathData loadSbzSpinConveyorPath(int pathIndex) {
        return loadConveyorPath(Sonic1Constants.SBZ_SPIN_CONVEYOR_PATH_TABLE_ADDR, pathIndex);
    }

    private int[][] loadPlatformChildren(int tableAddr, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 8) {
            return null;
        }
        int baseAddr = Sonic1Constants.OBJ_POS_INDEX_ADDR;
        int listOffset = rom.readU16BE(tableAddr + slotIndex * 2);
        int cursor = baseAddr + listOffset;
        int count = rom.readU16BE(cursor) + 1;
        cursor += 2;

        int[][] entries = new int[count][3];
        for (int i = 0; i < count; i++) {
            entries[i][0] = rom.readU16BE(cursor);
            entries[i][1] = rom.readU16BE(cursor + 2);
            entries[i][2] = rom.readU16BE(cursor + 4) & 0xFF;
            cursor += RECORD_SIZE;
        }
        return entries;
    }

    private ConveyorPathData loadConveyorPath(int tableAddr, int pathIndex) {
        if (pathIndex < 0 || pathIndex >= CONVEYOR_PATH_COUNT) {
            return null;
        }
        int groupAddr = tableAddr + rom.readU16BE(tableAddr + pathIndex * 2);
        int waypointBytes = rom.readU16BE(groupAddr);
        int count = waypointBytes / CONVEYOR_WAYPOINT_STEP;
        int baseX = rom.readU16BE(groupAddr + 2);
        int cursor = groupAddr + 4;

        int[][] waypoints = new int[count][2];
        for (int i = 0; i < count; i++) {
            waypoints[i][0] = rom.readU16BE(cursor);
            waypoints[i][1] = rom.readU16BE(cursor + 2);
            cursor += CONVEYOR_WAYPOINT_STEP;
        }
        return new ConveyorPathData(baseX, waypoints);
    }

    public record ConveyorPathData(int baseX, int[][] waypoints) {
        public ConveyorPathData {
            int[][] copy = new int[waypoints.length][];
            for (int i = 0; i < waypoints.length; i++) {
                copy[i] = waypoints[i].clone();
            }
            waypoints = copy;
        }
    }
}
