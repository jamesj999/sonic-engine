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
