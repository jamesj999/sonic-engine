package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses Sonic 2 Rev01 object placement data into {@link ObjectSpawn} records.
 */
public class Sonic2ObjectPlacement {
    // Off_Objects pointer table in s2.asm (Rev01) – label resolves to this ROM address
    // Verified against the disassembly and s2.txt split list (Off_Objects -> object BINCLUDE stream).
    public static final int OFF_OBJECTS_REV01 = 0x0E6800;
    private static final int ACTS_PER_ZONE = 2; // zoneOrderedOffsetTable 2,2 in s2.asm (SCZ/WFZ/DEZ are 1-act, MTZ is 3-act)
    private static final int RECORD_SIZE = 6;
    private static final int TERMINATOR = 0xFFFF;

    private final RomByteReader rom;

    public Sonic2ObjectPlacement(RomByteReader rom) {
        this.rom = rom;
    }

    public List<ObjectSpawn> load(ZoneAct zoneAct) {
        int pointerIndex = zoneAct.pointerIndex(ACTS_PER_ZONE);
        if (isSingleActZone(zoneAct.zone())) {
            pointerIndex = zoneAct.zone() * ACTS_PER_ZONE;
        }
        int listAddr = rom.readPointer16(OFF_OBJECTS_REV01, pointerIndex);
        List<ObjectSpawn> spawns = new ArrayList<>();

        int cursor = listAddr;
        while (true) {
            int x = rom.readU16BE(cursor);
            if (x == TERMINATOR) {
                break;
            }
            int yWord = rom.readU16BE(cursor + 2);
            int y = yWord & 0x0FFF;
            int renderFlags = (yWord >> 13) & 0x3;
            boolean respawnTracked = (yWord & 0x8000) != 0;
            int objectId = rom.readU8(cursor + 4);
            int subtype = rom.readU8(cursor + 5);

            spawns.add(new ObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked, yWord));
            cursor += RECORD_SIZE;
        }

        spawns.sort(Comparator.comparingInt(ObjectSpawn::x));
        return List.copyOf(spawns);
    }

    public String toCsv(List<ObjectSpawn> spawns) {
        StringBuilder sb = new StringBuilder("x,y,id,subtype,renderFlags,respawn,rawYWord\n");
        for (ObjectSpawn spawn : spawns) {
            sb.append(String.format(
                    "0x%04X,0x%04X,0x%02X,0x%02X,0x%02X,%s,0x%04X%n",
                    spawn.x(), spawn.y(), spawn.objectId(), spawn.subtype(),
                    spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord()));
        }
        return sb.toString();
    }

    private static boolean isSingleActZone(int zone) {
        return zone == 8 || zone == 9 || zone == 10;
    }
}
