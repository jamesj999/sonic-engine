package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.animation.AnimatedPaletteManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants.*;

/**
 * Applies Sonic 2 palette cycling (PalCycle_* routines) for supported zones.
 * Based on the original assembly routines in s2.asm.
 */
class Sonic2PaletteCycler implements AnimatedPaletteManager {
    // ROM zone indices (from s2.constants.asm)
    private static final int ZONE_EHZ = 0x00;
    private static final int ZONE_HTZ = 0x05;
    private static final int ZONE_OOZ = 0x0A;
    private static final int ZONE_MCZ = 0x0B;
    private static final int ZONE_CNZ = 0x0C;
    private static final int ZONE_CPZ = 0x0D;
    private static final int ZONE_ARZ = 0x0F;
    private static final int ZONE_MTZ = 0x10;

    private final Level level;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final List<PaletteCycle> cycles;

    public Sonic2PaletteCycler(Rom rom, Level level, int zoneIndex) throws IOException {
        this.level = level;
        RomByteReader reader = RomByteReader.fromRom(rom);
        this.cycles = loadCycles(reader, zoneIndex);
    }

    @Override
    public void update() {
        if (cycles == null || cycles.isEmpty()) {
            return;
        }
        for (PaletteCycle cycle : cycles) {
            cycle.tick(level, graphicsManager);
        }
    }

    private List<PaletteCycle> loadCycles(RomByteReader reader, int zoneIndex) {
        List<PaletteCycle> list = new ArrayList<>();
        switch (zoneIndex) {
            case ZONE_EHZ -> addIfNotNull(list, createEhzWaterCycle(reader));
            case ZONE_HTZ -> addIfNotNull(list, createHtzLavaCycle(reader));
            case ZONE_MTZ -> list.addAll(createMtzCycles(reader));
            case ZONE_OOZ -> addIfNotNull(list, createOozOilCycle(reader));
            case ZONE_MCZ -> addIfNotNull(list, createMczLanternCycle(reader));
            case ZONE_CNZ -> list.addAll(createCnzCycles(reader));
            case ZONE_CPZ -> list.addAll(createCpzCycles(reader));
            case ZONE_ARZ -> addIfNotNull(list, createArzWaterCycle(reader));
        }
        return list;
    }

    private void addIfNotNull(List<PaletteCycle> list, PaletteCycle cycle) {
        if (cycle != null) {
            list.add(cycle);
        }
    }

    // ========== EHZ ==========
    private PaletteCycle createEhzWaterCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < 32) return null;
        // Palette line 2 (index 1), colors 3,4,14,15 - 4 frames, 8 bytes each
        int[] colorIndices = {3, 4, 14, 15};
        return new PaletteCycle(data, 4, 8, 7, 1, colorIndices);
    }

    // ========== ARZ ==========
    private PaletteCycle createArzWaterCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < 32) return null;
        // Palette line 3 (index 2), colors 2,3,4,5 - 4 frames, 8 bytes each, timer 5
        int[] colorIndices = {2, 3, 4, 5};
        return new PaletteCycle(data, 4, 8, 5, 2, colorIndices);
    }

    // ========== HTZ (Hill Top Zone - Lava) ==========
    private PaletteCycle createHtzLavaCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_LAVA_ADDR, CYCLING_PAL_LAVA_LEN);
        if (data.length < 128) return null;
        // Variable timing per frame from PalCycle_HTZ_LavaDelayData
        int[] delays = {0x0B, 0x0B, 0x0B, 0x0A, 0x08, 0x0A, 0x0B, 0x0B,
                        0x0B, 0x0B, 0x0D, 0x0F, 0x0D, 0x0B, 0x0B, 0x0B};
        // Two sets of 4 colors: line 2 colors 3,4,5,6 and colors 14,15,16,17 (two separate groups)
        return new HtzLavaCycle(data, delays);
    }

    // ========== MTZ (Metropolis Zone) ==========
    private List<PaletteCycle> createMtzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // Cycle 1: 1 color at palette line 3, offset $A (index 5) - 6 frames, timer 17
        byte[] data1 = safeSlice(reader, CYCLING_PAL_MTZ1_ADDR, CYCLING_PAL_MTZ1_LEN);
        if (data1.length >= 12) {
            cycles.add(new PaletteCycle(data1, 6, 2, 0x11, 2, new int[]{5}));
        }

        // Cycle 2: 3 colors at palette line 3, offset $2 (indices 1,2,3) - 3 frames, timer 2
        byte[] data2 = safeSlice(reader, CYCLING_PAL_MTZ2_ADDR, CYCLING_PAL_MTZ2_LEN);
        if (data2.length >= 12) {
            // 3 frames × 6 bytes (but only 12 bytes total, so 4 bytes/frame with padding)
            cycles.add(new MtzCycle2(data2));
        }

        // Cycle 3: 1 color at palette line 3, offset $1E (index 15) - 10 frames, timer 9
        byte[] data3 = safeSlice(reader, CYCLING_PAL_MTZ3_ADDR, CYCLING_PAL_MTZ3_LEN);
        if (data3.length >= 20) {
            cycles.add(new PaletteCycle(data3, 10, 2, 9, 2, new int[]{15}));
        }

        return cycles;
    }

    // ========== OOZ (Oil Ocean Zone) ==========
    private PaletteCycle createOozOilCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_OIL_ADDR, CYCLING_PAL_OIL_LEN);
        if (data.length < 16) return null;
        // Palette line 3 (index 2), offset $14 = 4 colors at indices 10,11,12,13
        // 4 frames with AND #6 wrap (so only 4 values used), 8 bytes per frame
        int[] colorIndices = {10, 11, 12, 13};
        return new PaletteCycle(data, 4, 8, 7, 2, colorIndices);
    }

    // ========== MCZ (Mystic Cave Zone - Lanterns) ==========
    private PaletteCycle createMczLanternCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_LANTERN_ADDR, CYCLING_PAL_LANTERN_LEN);
        if (data.length < 8) return null;
        // Palette line 2 (index 1), offset $16 = index 11, 4 frames, timer 1
        return new PaletteCycle(data, 4, 2, 1, 1, new int[]{11});
    }

    // ========== CNZ (Casino Night Zone) ==========
    private List<PaletteCycle> createCnzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // CNZ1: Interleaved - 6 colors at specific offsets, 3 frames
        byte[] data1 = safeSlice(reader, CYCLING_PAL_CNZ1_ADDR, CYCLING_PAL_CNZ1_LEN);
        if (data1.length >= 36) {
            cycles.add(new CnzCycle1(data1));
        }

        // CNZ3: 3 interleaved colors, 3 frames
        byte[] data3 = safeSlice(reader, CYCLING_PAL_CNZ3_ADDR, CYCLING_PAL_CNZ3_LEN);
        if (data3.length >= 18) {
            cycles.add(new CnzCycle3(data3));
        }

        // CNZ4: Scrolling colors - 18 frames, reversed order
        byte[] data4 = safeSlice(reader, CYCLING_PAL_CNZ4_ADDR, CYCLING_PAL_CNZ4_LEN);
        if (data4.length >= 40) {
            cycles.add(new CnzCycle4(data4));
        }

        return cycles;
    }

    // ========== CPZ (Chemical Plant Zone) ==========
    private List<PaletteCycle> createCpzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // Cycle 1: 3 colors at palette line 4, offset $18 (indices 12, 13, 14)
        byte[] data1 = safeSlice(reader, CYCLING_PAL_CPZ1_ADDR, CYCLING_PAL_CPZ1_LEN);
        if (data1.length >= CYCLING_PAL_CPZ1_LEN) {
            cycles.add(new PaletteCycle(data1, 9, 6, 7, 3, new int[]{12, 13, 14}));
        }

        // Cycle 2: 1 color at palette line 4, offset $1E (index 15)
        byte[] data2 = safeSlice(reader, CYCLING_PAL_CPZ2_ADDR, CYCLING_PAL_CPZ2_LEN);
        if (data2.length >= CYCLING_PAL_CPZ2_LEN) {
            cycles.add(new PaletteCycle(data2, 21, 2, 7, 3, new int[]{15}));
        }

        // Cycle 3: 1 color at palette line 3, offset $1E (index 15)
        byte[] data3 = safeSlice(reader, CYCLING_PAL_CPZ3_ADDR, CYCLING_PAL_CPZ3_LEN);
        if (data3.length >= CYCLING_PAL_CPZ3_LEN) {
            cycles.add(new PaletteCycle(data3, 16, 2, 7, 2, new int[]{15}));
        }

        return cycles;
    }

    private byte[] safeSlice(RomByteReader reader, int addr, int len) {
        if (addr < 0 || addr + len > reader.size()) {
            return new byte[0];
        }
        return reader.slice(addr, len);
    }

    // ========== Base PaletteCycle class ==========
    private static class PaletteCycle {
        protected final byte[] data;
        protected final int frameCount;
        protected final int frameSize;
        protected final int timerReset;
        protected final int paletteIndex;
        protected final int[] colorIndices;
        protected int timer;
        protected int frame;
        protected boolean dirty;

        protected PaletteCycle(byte[] data, int frameCount, int frameSize,
                               int timerReset, int paletteIndex, int[] colorIndices) {
            this.data = data;
            this.frameCount = frameCount;
            this.frameSize = frameSize;
            this.timerReset = timerReset;
            this.paletteIndex = paletteIndex;
            this.colorIndices = colorIndices;
        }

        protected void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0 || frameCount <= 0) return;

            if (timer > 0) {
                timer--;
            } else {
                timer = timerReset;
                int frameIndex = frame % frameCount;
                frame++;
                apply(level, frameIndex);
                dirty = true;
            }

            if (dirty && graphicsManager.getGraphics() != null) {
                graphicsManager.cachePaletteTexture(level.getPalette(paletteIndex), paletteIndex);
                dirty = false;
            }
        }

        protected void apply(Level level, int frameIndex) {
            Palette palette = level.getPalette(paletteIndex);
            int base = frameIndex * frameSize;
            for (int i = 0; i < colorIndices.length; i++) {
                int dataIndex = base + i * 2;
                if (dataIndex + 1 >= data.length) continue;
                Palette.Color color = palette.getColor(colorIndices[i]);
                color.fromSegaFormat(data, dataIndex);
            }
        }
    }

    // ========== HTZ Lava Cycle (variable timing) ==========
    private static class HtzLavaCycle extends PaletteCycle {
        private final int[] delays;

        HtzLavaCycle(byte[] data, int[] delays) {
            super(data, 16, 8, delays[0], 1, new int[]{3, 4, 5, 6});
            this.delays = delays;
        }

        @Override
        protected void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                int frameIndex = frame & 0x0F;
                timer = delays[frameIndex];
                frame++;

                // Apply to palette line 2: colors 3,4,5,6 and 14,15 (from same data)
                Palette palette = level.getPalette(1);
                int base = frameIndex * 8;

                // First group: indices 3,4 from offset 0
                palette.getColor(3).fromSegaFormat(data, base);
                palette.getColor(4).fromSegaFormat(data, base + 2);

                // Second group: indices 14,15 from offset 4
                palette.getColor(14).fromSegaFormat(data, base + 4);
                palette.getColor(15).fromSegaFormat(data, base + 6);

                dirty = true;
            }

            if (dirty && graphicsManager.getGraphics() != null) {
                graphicsManager.cachePaletteTexture(level.getPalette(1), 1);
                dirty = false;
            }
        }
    }

    // ========== MTZ Cycle 2 (3 colors, special layout) ==========
    private static class MtzCycle2 extends PaletteCycle {
        MtzCycle2(byte[] data) {
            super(data, 3, 6, 2, 2, new int[]{1, 2, 3});
        }

        @Override
        protected void apply(Level level, int frameIndex) {
            Palette palette = level.getPalette(2);
            int base = frameIndex * 2;
            // Data layout: 3 frames of 2+2+2 bytes for 3 colors
            // But the file is only 12 bytes, so it's 2 bytes per frame for 3 colors interleaved
            // Actually reads: move.l (a0,d0.w),(a1)+  move.w 4(a0,d0.w),(a1)
            // So it reads 6 bytes (3 colors) from base offset
            if (base + 5 < data.length) {
                palette.getColor(1).fromSegaFormat(data, base);
                palette.getColor(2).fromSegaFormat(data, base + 2);
                palette.getColor(3).fromSegaFormat(data, base + 4);
            }
        }
    }

    // ========== CNZ Cycle 1 (interleaved layout) ==========
    private static class CnzCycle1 extends PaletteCycle {
        CnzCycle1(byte[] data) {
            super(data, 3, 2, 7, 0, new int[]{});
        }

        @Override
        protected void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                timer = timerReset;
                int frameIndex = frame % 3;
                frame++;

                // Line 3 (palette 2): offsets $4A, $4C, $4E = indices 5,6,7
                // Line 3 (palette 2): offsets $56, $58, $5A = indices 11,12,13
                // Data is interleaved: color0_frame0, color0_frame1, color0_frame2, color1_frame0...
                Palette pal2 = level.getPalette(2);
                int d0 = frameIndex * 2;

                pal2.getColor(5).fromSegaFormat(data, d0);       // offset 0
                pal2.getColor(6).fromSegaFormat(data, 6 + d0);   // offset 6
                pal2.getColor(7).fromSegaFormat(data, 12 + d0);  // offset 12
                pal2.getColor(11).fromSegaFormat(data, 18 + d0); // offset 18
                pal2.getColor(12).fromSegaFormat(data, 24 + d0); // offset 24
                pal2.getColor(13).fromSegaFormat(data, 30 + d0); // offset 30

                dirty = true;
            }

            if (dirty && graphicsManager.getGraphics() != null) {
                graphicsManager.cachePaletteTexture(level.getPalette(2), 2);
                dirty = false;
            }
        }
    }

    // ========== CNZ Cycle 3 (3 interleaved colors) ==========
    private static class CnzCycle3 extends PaletteCycle {
        CnzCycle3(byte[] data) {
            super(data, 3, 2, 7, 0, new int[]{});
        }

        @Override
        protected void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                timer = timerReset;
                int frameIndex = frame % 3;
                frame++;

                // Line 4 (palette 3): offsets $64, $66, $68 = indices 2,3,4
                Palette pal3 = level.getPalette(3);
                int d0 = frameIndex * 2;

                pal3.getColor(2).fromSegaFormat(data, d0);      // offset 0
                pal3.getColor(3).fromSegaFormat(data, 6 + d0);  // offset 6
                pal3.getColor(4).fromSegaFormat(data, 12 + d0); // offset 12

                dirty = true;
            }

            if (dirty && graphicsManager.getGraphics() != null) {
                graphicsManager.cachePaletteTexture(level.getPalette(3), 3);
                dirty = false;
            }
        }
    }

    // ========== CNZ Cycle 4 (scrolling neon) ==========
    private static class CnzCycle4 extends PaletteCycle {
        private int cnzFrame;

        CnzCycle4(byte[] data) {
            super(data, 18, 2, 7, 3, new int[]{});
        }

        @Override
        protected void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
                return;
            }

            // This cycle uses a separate frame counter (PalCycle_Frame_CNZ)
            int d0 = cnzFrame * 2;
            cnzFrame++;
            if (cnzFrame >= 18) cnzFrame = 0;

            // Palette line 4, offset $12 = indices 9,10,11 - reads in reverse order
            Palette pal3 = level.getPalette(3);
            if (d0 + 4 < data.length) {
                pal3.getColor(9).fromSegaFormat(data, d0 + 4);  // move.w 4(a0,d0.w),(a1)+
                pal3.getColor(10).fromSegaFormat(data, d0 + 2); // move.w 2(a0,d0.w),(a1)+
                pal3.getColor(11).fromSegaFormat(data, d0);     // move.w (a0,d0.w),(a1)+
            }

            dirty = true;

            if (dirty && graphicsManager.getGraphics() != null) {
                graphicsManager.cachePaletteTexture(level.getPalette(3), 3);
                dirty = false;
            }
        }
    }
}
