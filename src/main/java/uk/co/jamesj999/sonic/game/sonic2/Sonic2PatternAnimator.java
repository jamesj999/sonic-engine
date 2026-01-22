package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.animation.AnimatedPatternManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Updates Sonic 2 zone animated tiles using the Dynamic_Normal scripts.
 */
class Sonic2PatternAnimator implements AnimatedPatternManager {
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
        CNZ_2P(12), // Assumed shared
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
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final List<ScriptState> scripts;
    private int tableAddr = -1;

    public Sonic2PatternAnimator(Rom rom, Level level, int zoneIndex) throws IOException {
        this.level = level;
        RomByteReader reader = RomByteReader.fromRom(rom);
        this.tableAddr = scanForTable(reader);
        this.scripts = loadScriptsForZone(reader, zoneIndex);
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
        System.err.println("Sonic2PatternAnimator: Could not locate PLC_DYNANM table.");
        return -1;
    }

    @Override
    public void update() {
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        for (ScriptState script : scripts) {
            script.tick(level, graphicsManager);
        }
    }

    private List<ScriptState> loadScriptsForZone(RomByteReader reader, int zoneIndex) {
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

        ParseResult result = parseList(reader, scriptAddr);
        List<ScriptState> scripts = result.scripts();

        ensurePatternCapacity(scripts);
        primeScripts(scripts);
        return scripts;
    }

    private void ensurePatternCapacity(List<ScriptState> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        for (ScriptState script : scripts) {
            int required = script.requiredPatternCount();
            if (required > 0) {
                level.ensurePatternCapacity(required);
            }
        }
    }

    private void primeScripts(List<ScriptState> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        for (ScriptState script : scripts) {
            script.prime(level, graphicsManager);
        }
    }

    private AnimatedListId resolveListId(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_LISTS.length) {
            return AnimatedListId.NULL_LIST;
        }
        return ZONE_LISTS[zoneIndex];
    }

    private ParseResult parseList(RomByteReader reader, int addr) {
        int countMinus1 = reader.readU16BE(addr);
        if (countMinus1 == 0xFFFF) {
            return new ParseResult(List.of(), 2);
        }

        int scriptCount = countMinus1 + 1;
        int pos = addr + 2;
        List<ScriptState> scripts = new ArrayList<>(scriptCount);

        for (int i = 0; i < scriptCount; i++) {
            int header = readU32BE(reader, pos);
            byte globalDuration = (byte) ((header >> 24) & 0xFF);
            int artAddr = header & 0xFFFFFF;
            int destBytes = reader.readU16BE(pos + 4);
            int destTileIndex = destBytes >> 5;
            int frameCount = reader.readU8(pos + 6);
            int tilesPerFrame = reader.readU8(pos + 7);

            int dataStart = pos + 8;
            boolean perFrame = globalDuration < 0;
            int dataLen = frameCount * (perFrame ? 2 : 1);
            int dataLenAligned = (dataLen + 1) & ~1;

            int[] frameTileIds = new int[frameCount];
            int[] frameDurations = perFrame ? new int[frameCount] : null;
            for (int f = 0; f < frameCount; f++) {
                int offset = dataStart + (perFrame ? (f * 2) : f);
                frameTileIds[f] = reader.readU8(offset);
                if (perFrame) {
                    frameDurations[f] = reader.readU8(offset + 1);
                }
            }

            Pattern[] artPatterns = loadArtPatterns(reader, artAddr, tilesPerFrame, frameTileIds);
            scripts.add(new ScriptState(globalDuration, destTileIndex, frameTileIds, frameDurations,
                    tilesPerFrame, artPatterns));

            pos = dataStart + dataLenAligned;
        }

        return new ParseResult(scripts, pos - addr);
    }

    private Pattern[] loadArtPatterns(RomByteReader reader, int artAddr, int tilesPerFrame, int[] frameTileIds) {
        int maxTile = 0;
        for (int tileId : frameTileIds) {
            int frameMax = tileId + Math.max(tilesPerFrame, 1) - 1;
            if (frameMax > maxTile) {
                maxTile = frameMax;
            }
        }
        int tileCount = maxTile + 1;
        int byteCount = tileCount * Pattern.PATTERN_SIZE_IN_ROM;
        if (artAddr < 0 || artAddr + byteCount > reader.size()) {
            int available = Math.max(0, reader.size() - artAddr);
            tileCount = available / Pattern.PATTERN_SIZE_IN_ROM;
            byteCount = tileCount * Pattern.PATTERN_SIZE_IN_ROM;
        }
        if (tileCount <= 0 || byteCount <= 0) {
            return new Pattern[0];
        }

        byte[] data = reader.slice(artAddr, byteCount);
        Pattern[] patterns = new Pattern[tileCount];
        for (int i = 0; i < tileCount; i++) {
            Pattern pattern = new Pattern();
            int start = i * Pattern.PATTERN_SIZE_IN_ROM;
            int end = start + Pattern.PATTERN_SIZE_IN_ROM;
            pattern.fromSegaFormat(slice(data, start, end));
            patterns[i] = pattern;
        }
        return patterns;
    }

    private int readU32BE(RomByteReader reader, int addr) {
        int upper = reader.readU16BE(addr);
        int lower = reader.readU16BE(addr + 2);
        return (upper << 16) | lower;
    }

    private byte[] slice(byte[] data, int start, int end) {
        int len = Math.max(0, end - start);
        byte[] out = new byte[len];
        System.arraycopy(data, start, out, 0, len);
        return out;
    }

    private record ParseResult(List<ScriptState> scripts, int length) {
    }

    private static class ScriptState {
        private final byte globalDuration;
        private final int destTileIndex;
        private final int[] frameTileIds;
        private final int[] frameDurations;
        private final int tilesPerFrame;
        private final Pattern[] artPatterns;
        private int timer;
        private int frameIndex;

        private ScriptState(byte globalDuration,
                int destTileIndex,
                int[] frameTileIds,
                int[] frameDurations,
                int tilesPerFrame,
                Pattern[] artPatterns) {
            this.globalDuration = globalDuration;
            this.destTileIndex = destTileIndex;
            this.frameTileIds = frameTileIds;
            this.frameDurations = frameDurations;
            this.tilesPerFrame = tilesPerFrame;
            this.artPatterns = artPatterns;
            this.timer = 0;
            this.frameIndex = 0;
        }

        private void tick(Level level, GraphicsManager graphicsManager) {
            if (frameTileIds.length == 0 || artPatterns.length == 0) {
                return;
            }
            if (timer > 0) {
                timer = (timer - 1) & 0xFF;
                return;
            }

            int currentFrame = frameIndex;
            if (currentFrame >= frameTileIds.length) {
                currentFrame = 0;
                frameIndex = 0;
            }
            frameIndex = currentFrame + 1;

            int duration = globalDuration & 0xFF;
            if (globalDuration < 0 && frameDurations != null) {
                duration = frameDurations[currentFrame];
            }
            timer = duration & 0xFF;

            int tileId = frameTileIds[currentFrame];
            applyFrame(level, graphicsManager, tileId);
        }

        private int requiredPatternCount() {
            return destTileIndex + Math.max(tilesPerFrame, 1);
        }

        private void prime(Level level, GraphicsManager graphicsManager) {
            if (frameTileIds.length == 0 || artPatterns.length == 0) {
                return;
            }
            applyFrame(level, graphicsManager, frameTileIds[0]);
        }

        private void applyFrame(Level level, GraphicsManager graphicsManager, int tileId) {
            int maxPatterns = level.getPatternCount();
            boolean canUpdateTextures = graphicsManager.getGraphics() != null;
            for (int i = 0; i < tilesPerFrame; i++) {
                int srcIndex = tileId + i;
                int destIndex = destTileIndex + i;
                if (srcIndex < 0 || srcIndex >= artPatterns.length) {
                    continue;
                }
                if (destIndex < 0 || destIndex >= maxPatterns) {
                    continue;
                }
                Pattern dest = level.getPattern(destIndex);
                dest.copyFrom(artPatterns[srcIndex]);
                if (canUpdateTextures) {
                    graphicsManager.updatePatternTexture(dest, destIndex);
                }
            }
        }
    }
}
