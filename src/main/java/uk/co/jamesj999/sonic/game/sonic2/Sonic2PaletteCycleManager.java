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
 */
public class Sonic2PaletteCycleManager implements AnimatedPaletteManager {
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_CPZ = 13;  // chemical_plant_zone = $0D
    private static final int ZONE_ARZ = 15;
    private static final int WATER_FRAME_COUNT = 4;
    private static final int WATER_FRAME_SIZE = 8;

    private final Level level;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final List<PaletteCycle> cycles;

    public Sonic2PaletteCycleManager(Rom rom, Level level, int zoneIndex) throws IOException {
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
        if (zoneIndex == ZONE_EHZ) {
            PaletteCycle ehzWater = createEhzWaterCycle(reader);
            if (ehzWater != null) {
                list.add(ehzWater);
            }
        } else if (zoneIndex == ZONE_CPZ) {
            list.addAll(createCpzCycles(reader));
        } else if (zoneIndex == ZONE_ARZ) {
            PaletteCycle arzWater = createArzWaterCycle(reader);
            if (arzWater != null) {
                list.add(arzWater);
            }
        }
        return list;
    }

    private PaletteCycle createEhzWaterCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < WATER_FRAME_COUNT * WATER_FRAME_SIZE) {
            return null;
        }
        int[] colorIndices = { 3, 4, 14, 15 };
        return new PaletteCycle(data, WATER_FRAME_COUNT, WATER_FRAME_SIZE, 7, 1, colorIndices);
    }

    private PaletteCycle createArzWaterCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < WATER_FRAME_COUNT * WATER_FRAME_SIZE) {
            return null;
        }
        int[] colorIndices = { 2, 3, 4, 5 };
        return new PaletteCycle(data, WATER_FRAME_COUNT, WATER_FRAME_SIZE, 5, 2, colorIndices);
    }

    /**
     * Creates the 3 CPZ palette cycles as per PalCycle_CPZ in s2.asm.
     * All 3 cycles share a common timer (7 frames) but have independent frame counters.
     */
    private List<PaletteCycle> createCpzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // Cycle 1: 3 colors at palette line 4, offset $18 (indices 12, 13, 14)
        // 9 frames, 6 bytes per frame (3 colors × 2 bytes)
        byte[] data1 = safeSlice(reader, CYCLING_PAL_CPZ1_ADDR, CYCLING_PAL_CPZ1_LEN);
        if (data1.length >= CYCLING_PAL_CPZ1_LEN) {
            int[] colorIndices1 = { 12, 13, 14 };
            cycles.add(new PaletteCycle(data1, 9, 6, 7, 3, colorIndices1));
        }

        // Cycle 2: 1 color at palette line 4, offset $1E (index 15)
        // 21 frames, 2 bytes per frame
        byte[] data2 = safeSlice(reader, CYCLING_PAL_CPZ2_ADDR, CYCLING_PAL_CPZ2_LEN);
        if (data2.length >= CYCLING_PAL_CPZ2_LEN) {
            int[] colorIndices2 = { 15 };
            cycles.add(new PaletteCycle(data2, 21, 2, 7, 3, colorIndices2));
        }

        // Cycle 3: 1 color at palette line 3, offset $1E (index 15)
        // 16 frames, 2 bytes per frame (wraps with AND #$1E, so 16 entries)
        byte[] data3 = safeSlice(reader, CYCLING_PAL_CPZ3_ADDR, CYCLING_PAL_CPZ3_LEN);
        if (data3.length >= CYCLING_PAL_CPZ3_LEN) {
            int[] colorIndices3 = { 15 };
            cycles.add(new PaletteCycle(data3, 16, 2, 7, 2, colorIndices3));
        }

        return cycles;
    }

    private byte[] safeSlice(RomByteReader reader, int addr, int len) {
        if (addr < 0 || addr + len > reader.size()) {
            return new byte[0];
        }
        return reader.slice(addr, len);
    }

    private static class PaletteCycle {
        private final byte[] data;
        private final int frameCount;
        private final int frameSize;
        private final int timerReset;
        private final int paletteIndex;
        private final int[] colorIndices;
        private int timer;
        private int frame;
        private boolean dirty;

        private PaletteCycle(byte[] data,
                int frameCount,
                int frameSize,
                int timerReset,
                int paletteIndex,
                int[] colorIndices) {
            this.data = data;
            this.frameCount = frameCount;
            this.frameSize = frameSize;
            this.timerReset = timerReset;
            this.paletteIndex = paletteIndex;
            this.colorIndices = colorIndices;
        }

        private void tick(Level level, GraphicsManager graphicsManager) {
            if (data.length == 0 || frameCount <= 0) {
                return;
            }
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

        private void apply(Level level, int frameIndex) {
            Palette palette = level.getPalette(paletteIndex);
            int base = frameIndex * frameSize;
            for (int i = 0; i < colorIndices.length; i++) {
                int dataIndex = base + i * 2;
                if (dataIndex + 1 >= data.length) {
                    continue;
                }
                Palette.Color color = palette.getColor(colorIndices[i]);
                color.fromSegaFormat(data, dataIndex);
            }
        }
    }
}
