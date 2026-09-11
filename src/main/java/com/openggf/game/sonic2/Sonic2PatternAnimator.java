package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.PatternAnimatorSnapshot;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.level.Level;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.animation.AniPlcParser;
import com.openggf.level.animation.AniPlcScriptState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Logger;

/**
 * Updates Sonic 2 zone animated tiles using the Dynamic_Normal scripts.
 */
class Sonic2PatternAnimator implements AnimatedPatternManager, RewindSnapshottable<PatternAnimatorSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(Sonic2PatternAnimator.class.getName());
    // Disassembly: loc_3FF94 (Animated_EHZ)
    private static final int ANIMATED_EHZ_ADDR = 0x3FF94;

    private enum AnimatedListId {
        EHZ(0),
        LEV1(1),
        WZ(2),
        LEV3(3),
        MTZ(4),
        MTZ3(5),
        WFZ(6),
        HTZ(7),
        HPZ(8),
        LEV9(9),
        OOZ(10),
        MCZ(11),
        CNZ(12),
        CPZ(13),
        DEZ(14),
        ARZ(15),
        SCZ(16),
        NULL_LIST(-1);

        final int index;

        AnimatedListId(int index) {
            this.index = index;
        }
    }

    // Mirrors PLC_DYNANM table (1P only).
    // Zones with Dynamic_Null/Animated_Null in the disassembly are marked NULL_LIST
    // since they have no animated patterns.
    private static final AnimatedListId[] ZONE_LISTS = {
            AnimatedListId.EHZ, // 0 EHZ
            AnimatedListId.NULL_LIST, // 1 Zone 1 (unused) - Dynamic_Null
            AnimatedListId.NULL_LIST, // 2 WZ (unused) - Dynamic_Null
            AnimatedListId.NULL_LIST, // 3 Zone 3 (unused) - Dynamic_Null
            AnimatedListId.MTZ, // 4 MTZ
            AnimatedListId.MTZ3, // 5 MTZ Act 3
            AnimatedListId.NULL_LIST, // 6 WFZ - Dynamic_Null
            AnimatedListId.HTZ, // 7 HTZ
            AnimatedListId.HPZ, // 8 HPZ
            AnimatedListId.NULL_LIST, // 9 Zone 9 (unused) - Dynamic_Null
            AnimatedListId.OOZ, // 10 OOZ
            AnimatedListId.NULL_LIST, // 11 MCZ - Dynamic_Null
            AnimatedListId.CNZ, // 12 CNZ
            AnimatedListId.CPZ, // 13 CPZ
            AnimatedListId.DEZ, // 14 DEZ
            AnimatedListId.ARZ, // 15 ARZ
            AnimatedListId.NULL_LIST // 16 SCZ - Dynamic_Null
    };

    private final Level level;
    private final AnimatedTileChannelGraph graph;
    private final List<AniPlcScriptState> scripts;
    private final int zoneIndex;
    private int tableAddr = -1;
    private int frameCounter;

    public Sonic2PatternAnimator(Rom rom, Level level, int zoneIndex) throws IOException {
        this.level = level;
        this.graph = GameServices.animatedTileChannelGraph();
        this.zoneIndex = zoneIndex;
        RomByteReader reader = RomByteReader.fromRom(rom);
        this.tableAddr = scanForTable(reader);
        this.scripts = loadScriptsForZone(reader, zoneIndex);
        this.graph.install(Sonic2AnimatedTileChannels.fromScripts(this.scripts));
    }

    private int scanForTable(RomByteReader reader) {
        // We know EHZ data is at 0x3FF94.
        // PLC_DYNANM has 2 entries per zone: routine pointer + data pointer (4 bytes/zone).
        // EHZ data pointer is at Word[1] (offset 2), not Word[0].
        // We seek a table where 'Word[1] + Address == 0x3FF94'.
        // Scan up to 0x70000 (Cover code and some data banks).
        for (int addr = 0; addr < 0x70000; addr += 2) {
            try {
                // Read the DATA pointer (second word, offset 2) not the routine pointer
                int offset = (short) reader.readU16BE(addr + 2);
                if (addr + offset == ANIMATED_EHZ_ADDR) {
                    // Candidate validation: check MTZ data pointer (zone 4) points to known area
                    // MTZ data is at offset 4 * 4 + 2 = 18 bytes from table start
                    int offsetMtz = (short) reader.readU16BE(addr + 4 * 4 + 2);
                    if (addr + offsetMtz > 0x3FF94 && addr + offsetMtz < 0x41000) {
                        return addr;
                    }
                }
            } catch (Exception e) {
                // Ignore read errors during scan
            }
        }
        LOGGER.warning("Sonic2PatternAnimator: Could not locate PLC_DYNANM table.");
        return -1;
    }

    @Override
    public void update() {
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        ZoneRuntimeState runtimeState = GameServices.zoneRuntimeState();
        int actIndex = GameServices.level().getCurrentAct();
        graph.update(new ChannelContext(graph, null, level, runtimeState, zoneIndex, actIndex, frameCounter++));
    }

    private List<AniPlcScriptState> loadScriptsForZone(RomByteReader reader, int zoneIndex) {
        AnimatedListId listId = resolveListId(zoneIndex);
        if (AnimatedListId.NULL_LIST.equals(listId) || tableAddr == -1) {
            return List.of();
        }

        // Calculate absolute address from table
        // PLC_DYNANM has 4 bytes per zone: routine pointer (2) + data pointer (2)
        // We want the data pointer, so offset is zoneIndex * 4 + 2
        int pointerAddr = tableAddr + (listId.index * 4) + 2;
        int offset = (short) reader.readU16BE(pointerAddr);
        int scriptAddr = tableAddr + offset;

        List<AniPlcScriptState> scripts = AniPlcParser.parseScripts(reader, scriptAddr);
        AniPlcParser.ensurePatternCapacity(scripts, level);
        AniPlcParser.primeScripts(scripts, level, GameServices.graphics());
        return scripts;
    }

    private AnimatedListId resolveListId(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_LISTS.length) {
            return AnimatedListId.NULL_LIST;
        }
        return ZONE_LISTS[zoneIndex];
    }

    @Override
    public String key() {
        return "pattern-animator";
    }

    @Override
    public PatternAnimatorSnapshot capture() {
        PatternAnimatorSnapshot.ScriptCounter[] counters =
                new PatternAnimatorSnapshot.ScriptCounter[scripts.size()];
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            counters[i] = new PatternAnimatorSnapshot.ScriptCounter(script.getTimer(), script.getFrameIndex());
        }
        byte[] extra = ByteBuffer.allocate(Integer.BYTES).putInt(frameCounter).array();
        return new PatternAnimatorSnapshot(counters, new PatternAnimatorSnapshot.HandlerCounter[0], extra);
    }

    @Override
    public void restore(PatternAnimatorSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        PatternAnimatorSnapshot.ScriptCounter[] counters = snapshot.scriptCounters();
        int count = Math.min(scripts.size(), counters != null ? counters.length : 0);
        for (int i = 0; i < count; i++) {
            PatternAnimatorSnapshot.ScriptCounter counter = counters[i];
            scripts.get(i).restoreCounters(counter.timer(), counter.frameIndex());
        }
        byte[] extra = snapshot.extra();
        if (extra != null && extra.length >= Integer.BYTES) {
            frameCounter = ByteBuffer.wrap(extra).getInt();
        }
    }

}
