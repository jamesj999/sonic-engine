package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteWrite;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.animation.AnimatedPaletteManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies Sonic 2 palette cycling (PalCycle_* routines) for supported zones.
 * Based on the original assembly routines in s2.asm.
 * <p>
 * Uses ROM zone IDs from {@link Sonic2ZoneConstants}
 * which match the values returned by {@code level.getZoneIndex()}.
 */
class Sonic2PaletteCycler implements AnimatedPaletteManager {
    private static final int PALETTE_CYCLE_PRIORITY = 100;
    private static final String OWNER_EHZ_WATER = "s2.ehz.waterCycle";
    private static final String OWNER_ARZ_WATER = "s2.arz.waterCycle";
    private static final String OWNER_HTZ_LAVA = "s2.htz.lavaCycle";
    private static final String OWNER_MTZ_CYCLE_1 = "s2.mtz.cycle1";
    private static final String OWNER_MTZ_CYCLE_2 = "s2.mtz.cycle2";
    private static final String OWNER_MTZ_CYCLE_3 = "s2.mtz.cycle3";
    private static final String OWNER_OOZ_OIL = "s2.ooz.oilCycle";
    private static final String OWNER_MCZ_LANTERN = "s2.mcz.lanternCycle";
    private static final String OWNER_CNZ_CYCLE_1 = "s2.cnz.cycle1";
    private static final String OWNER_CNZ_CYCLE_3 = "s2.cnz.cycle3";
    private static final String OWNER_CNZ_CYCLE_4 = "s2.cnz.cycle4";
    private static final String OWNER_CNZ_BOSS_CYCLE_1 = "s2.cnz.bossCycle1";
    private static final String OWNER_CNZ_BOSS_CYCLE_2 = "s2.cnz.bossCycle2";
    private static final String OWNER_CNZ_BOSS_CYCLE_3 = "s2.cnz.bossCycle3";
    private static final String OWNER_CPZ_CYCLE_1 = "s2.cpz.cycle1";
    private static final String OWNER_CPZ_CYCLE_2 = "s2.cpz.cycle2";
    private static final String OWNER_CPZ_CYCLE_3 = "s2.cpz.cycle3";
    private static final String OWNER_WFZ_FIRE_BELT = "s2.wfz.fireBeltCycle";
    private static final String OWNER_WFZ_CYCLE_1 = "s2.wfz.cycle1";
    private static final String OWNER_WFZ_CYCLE_2 = "s2.wfz.cycle2";

    private final Level level;
    private final PaletteOwnershipRegistry paletteRegistry;
    private final boolean usingLocalPaletteRegistry;
    private final List<PaletteCycle> cycles;
    private Palette[] cachedLevelPalettes;

    public Sonic2PaletteCycler(Rom rom, Level level, int zoneIndex) throws IOException {
        this.level = level;
        PaletteOwnershipRegistry runtimeRegistry = GameServices.paletteOwnershipRegistryOrNull();
        this.paletteRegistry = runtimeRegistry != null ? runtimeRegistry : new PaletteOwnershipRegistry();
        this.usingLocalPaletteRegistry = runtimeRegistry == null;
        RomByteReader reader = RomByteReader.fromRom(rom);
        this.cycles = loadCycles(reader, zoneIndex);
    }

    @Override
    public void update() {
        if (cycles == null || cycles.isEmpty()) {
            return;
        }
        if (usingLocalPaletteRegistry) {
            paletteRegistry.beginFrame();
        }
        for (PaletteCycle cycle : cycles) {
            cycle.tick(level, paletteRegistry);
        }
        paletteRegistry.resolveInto(levelPalettes(), null, GameServices.graphics(), null);
    }

    private List<PaletteCycle> loadCycles(RomByteReader reader, int zoneIndex) {
        List<PaletteCycle> list = new ArrayList<>();
        switch (zoneIndex) {
            case Sonic2ZoneConstants.ROM_ZONE_EHZ -> addIfNotNull(list, createEhzWaterCycle(reader));
            case Sonic2ZoneConstants.ROM_ZONE_HTZ -> addIfNotNull(list, createHtzLavaCycle(reader));
            case Sonic2ZoneConstants.ROM_ZONE_MTZ -> list.addAll(createMtzCycles(reader));
            case Sonic2ZoneConstants.ROM_ZONE_OOZ -> addIfNotNull(list, createOozOilCycle(reader));
            case Sonic2ZoneConstants.ROM_ZONE_MCZ -> addIfNotNull(list, createMczLanternCycle(reader));
            case Sonic2ZoneConstants.ROM_ZONE_CNZ -> list.addAll(createCnzCycles(reader));
            case Sonic2ZoneConstants.ROM_ZONE_CPZ -> list.addAll(createCpzCycles(reader));
            case Sonic2ZoneConstants.ROM_ZONE_ARZ -> addIfNotNull(list, createArzWaterCycle(reader));
            case Sonic2ZoneConstants.ROM_ZONE_WFZ -> list.addAll(createWfzCycles(reader));
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
        byte[] data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_ADDR, Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < 32) return null;
        // Palette line 2 (index 1), colors 3,4,14,15 - 4 frames, 8 bytes each
        int[] colorIndices = {3, 4, 14, 15};
        return new PaletteCycle(OWNER_EHZ_WATER, data, 4, 8, 7, 1, colorIndices);
    }

    // ========== ARZ ==========
    private PaletteCycle createArzWaterCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_ADDR, Sonic2Constants.CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        if (data.length < 32) return null;
        // Palette line 3 (index 2), colors 2,3,4,5 - 4 frames, 8 bytes each, timer 5
        int[] colorIndices = {2, 3, 4, 5};
        return new PaletteCycle(OWNER_ARZ_WATER, data, 4, 8, 5, 2, colorIndices);
    }

    // ========== HTZ (Hill Top Zone - Lava) ==========
    private PaletteCycle createHtzLavaCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_LAVA_ADDR, Sonic2Constants.CYCLING_PAL_LAVA_LEN);
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
        byte[] data1 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_MTZ1_ADDR, Sonic2Constants.CYCLING_PAL_MTZ1_LEN);
        if (data1.length >= 12) {
            cycles.add(new PaletteCycle(OWNER_MTZ_CYCLE_1, data1, 6, 2, 0x11, 2, new int[]{5}));
        }

        // Cycle 2: 3 colors at palette line 3, offset $2 (indices 1,2,3) - 3 frames, timer 2
        byte[] data2 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_MTZ2_ADDR, Sonic2Constants.CYCLING_PAL_MTZ2_LEN);
        if (data2.length >= 12) {
            // 3 frames × 6 bytes (but only 12 bytes total, so 4 bytes/frame with padding)
            cycles.add(new MtzCycle2(data2));
        }

        // Cycle 3: 1 color at palette line 3, offset $1E (index 15) - 10 frames, timer 9
        byte[] data3 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_MTZ3_ADDR, Sonic2Constants.CYCLING_PAL_MTZ3_LEN);
        if (data3.length >= 20) {
            cycles.add(new PaletteCycle(OWNER_MTZ_CYCLE_3, data3, 10, 2, 9, 2, new int[]{15}));
        }

        return cycles;
    }

    // ========== OOZ (Oil Ocean Zone) ==========
    private PaletteCycle createOozOilCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_OIL_ADDR, Sonic2Constants.CYCLING_PAL_OIL_LEN);
        if (data.length < 16) return null;
        // Palette line 3 (index 2), offset $14 = 4 colors at indices 10,11,12,13
        // 4 frames with AND #6 wrap (so only 4 values used), 8 bytes per frame
        int[] colorIndices = {10, 11, 12, 13};
        return new PaletteCycle(OWNER_OOZ_OIL, data, 4, 8, 7, 2, colorIndices);
    }

    // ========== MCZ (Mystic Cave Zone - Lanterns) ==========
    private PaletteCycle createMczLanternCycle(RomByteReader reader) {
        byte[] data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_LANTERN_ADDR, Sonic2Constants.CYCLING_PAL_LANTERN_LEN);
        if (data.length < 8) return null;
        // Palette line 2 (index 1), offset $16 = index 11, 4 frames, timer 1
        return new PaletteCycle(OWNER_MCZ_LANTERN, data, 4, 2, 1, 1, new int[]{11});
    }

    // ========== CNZ (Casino Night Zone) ==========
    private List<PaletteCycle> createCnzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // CNZ1: Interleaved - 6 colors at specific offsets, 3 frames
        byte[] data1 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ1_ADDR, Sonic2Constants.CYCLING_PAL_CNZ1_LEN);
        if (data1.length >= 36) {
            cycles.add(new CnzCycle1(data1));
        }

        // CNZ3: 3 interleaved colors, 3 frames
        byte[] data3 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ3_ADDR, Sonic2Constants.CYCLING_PAL_CNZ3_LEN);
        if (data3.length >= 18) {
            cycles.add(new CnzCycle3(data3));
        }

        // CNZ4: Scrolling colors - 18 frames, reversed order
        byte[] data4 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ4_ADDR, Sonic2Constants.CYCLING_PAL_CNZ4_LEN);
        if (data4.length >= 40) {
            cycles.add(new CnzCycle4(data4));
        }

        // CNZ Boss Palette Cycles (only active when Current_Boss_ID != 0)
        // ROM: CNZ_SkipToBossPalCycle (s2.asm:2915-2944)
        // These cycles animate the electricity effects on the CNZ boss
        byte[] bossData1 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ_BOSS1_ADDR, Sonic2Constants.CYCLING_PAL_CNZ_BOSS1_LEN);
        byte[] bossData2 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ_BOSS2_ADDR, Sonic2Constants.CYCLING_PAL_CNZ_BOSS2_LEN);
        byte[] bossData3 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CNZ_BOSS3_ADDR, Sonic2Constants.CYCLING_PAL_CNZ_BOSS3_LEN);

        if (bossData1.length >= Sonic2Constants.CYCLING_PAL_CNZ_BOSS1_LEN) {
            cycles.add(new CnzBossCycle1(bossData1));
        }
        if (bossData2.length >= Sonic2Constants.CYCLING_PAL_CNZ_BOSS2_LEN) {
            cycles.add(new CnzBossCycle2(bossData2));
        }
        if (bossData3.length >= Sonic2Constants.CYCLING_PAL_CNZ_BOSS3_LEN) {
            cycles.add(new CnzBossCycle3(bossData3));
        }

        return cycles;
    }

    // ========== CPZ (Chemical Plant Zone) ==========
    private List<PaletteCycle> createCpzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // Cycle 1: 3 colors at palette line 4, offset $18 (indices 12, 13, 14)
        byte[] data1 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CPZ1_ADDR, Sonic2Constants.CYCLING_PAL_CPZ1_LEN);
        if (data1.length >= Sonic2Constants.CYCLING_PAL_CPZ1_LEN) {
            cycles.add(new PaletteCycle(OWNER_CPZ_CYCLE_1, data1, 9, 6, 7, 3, new int[]{12, 13, 14}));
        }

        // Cycle 2: 1 color at palette line 4, offset $1E (index 15)
        byte[] data2 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CPZ2_ADDR, Sonic2Constants.CYCLING_PAL_CPZ2_LEN);
        if (data2.length >= Sonic2Constants.CYCLING_PAL_CPZ2_LEN) {
            cycles.add(new PaletteCycle(OWNER_CPZ_CYCLE_2, data2, 21, 2, 7, 3, new int[]{15}));
        }

        // Cycle 3: 1 color at palette line 3, offset $1E (index 15)
        byte[] data3 = safeSlice(reader, Sonic2Constants.CYCLING_PAL_CPZ3_ADDR, Sonic2Constants.CYCLING_PAL_CPZ3_LEN);
        if (data3.length >= Sonic2Constants.CYCLING_PAL_CPZ3_LEN) {
            cycles.add(new PaletteCycle(OWNER_CPZ_CYCLE_3, data3, 16, 2, 7, 2, new int[]{15}));
        }

        return cycles;
    }

    // ========== WFZ (Wing Fortress Zone) ==========
    // ROM: PalCycle_WFZ (s2.asm:2994-3034) - 3 independent palette cycles
    private List<PaletteCycle> createWfzCycles(RomByteReader reader) {
        List<PaletteCycle> cycles = new ArrayList<>();

        // Cycle 1: Fire/Belt toggle cycle - 4 colors at palette line 3 offset $E (indices 7,8,9,10)
        // ROM switches between CyclingPal_WFZFire and CyclingPal_WFZBelt based on
        // WFZ_SCZ_Fire_Toggle. Timer is 1 for fire, 5 for belt.
        byte[] fireData = safeSlice(reader, Sonic2Constants.CYCLING_PAL_WFZ_FIRE_ADDR, Sonic2Constants.CYCLING_PAL_WFZ_FIRE_LEN);
        byte[] beltData = safeSlice(reader, Sonic2Constants.CYCLING_PAL_WFZ_BELT_ADDR, Sonic2Constants.CYCLING_PAL_WFZ_BELT_LEN);
        if (fireData.length >= Sonic2Constants.CYCLING_PAL_WFZ_FIRE_LEN && beltData.length >= Sonic2Constants.CYCLING_PAL_WFZ_BELT_LEN) {
            cycles.add(new WfzFireBeltCycle(fireData, beltData));
        }

        // Cycle 2: Flashing light 1 - 1 color at palette line 3 offset $1C (index 14)
        // ROM: PalCycle_Timer2, PalCycle_Frame2, 34 frames, timer 3
        byte[] wfz1Data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_WFZ1_ADDR, Sonic2Constants.CYCLING_PAL_WFZ1_LEN);
        if (wfz1Data.length >= Sonic2Constants.CYCLING_PAL_WFZ1_LEN) {
            cycles.add(new PaletteCycle(OWNER_WFZ_CYCLE_1, wfz1Data, 34, 2, 3, 2, new int[]{14}));
        }

        // Cycle 3: Flashing light 2 - 1 color at palette line 3 offset $1E (index 15)
        // ROM: PalCycle_Timer3, PalCycle_Frame3, 12 frames, timer 5
        byte[] wfz2Data = safeSlice(reader, Sonic2Constants.CYCLING_PAL_WFZ2_ADDR, Sonic2Constants.CYCLING_PAL_WFZ2_LEN);
        if (wfz2Data.length >= Sonic2Constants.CYCLING_PAL_WFZ2_LEN) {
            cycles.add(new PaletteCycle(OWNER_WFZ_CYCLE_2, wfz2Data, 12, 2, 5, 2, new int[]{15}));
        }

        return cycles;
    }

    private byte[] safeSlice(RomByteReader reader, int addr, int len) {
        if (addr < 0 || addr + len > reader.size()) {
            return new byte[0];
        }
        return reader.slice(addr, len);
    }

    private Palette[] levelPalettes() {
        if (cachedLevelPalettes == null || cachedLevelPalettes.length != level.getPaletteCount()) {
            cachedLevelPalettes = new Palette[level.getPaletteCount()];
        }
        for (int i = 0; i < cachedLevelPalettes.length; i++) {
            cachedLevelPalettes[i] = level.getPalette(i);
        }
        return cachedLevelPalettes;
    }

    /**
     * Captures per-cycle mutable state (timers, frame indices, dirty flags) across
     * all loaded cycles for the current zone. Intended for inclusion in the
     * level-animation manager's combined snapshot.
     */
    byte[] captureCyclerState() {
        if (cycles == null || cycles.isEmpty()) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream dout = new java.io.DataOutputStream(out)) {
            dout.writeInt(cycles.size());
            for (PaletteCycle cycle : cycles) {
                byte[] state = com.openggf.game.rewind.schema.PaletteCycleStateCodec.capture(cycle);
                dout.writeInt(state.length);
                dout.write(state);
            }
        } catch (java.io.IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    /** Inverse of {@link #captureCyclerState()}. Tolerant of null/empty/mismatched input. */
    void restoreCyclerState(byte[] data) {
        if (data == null || data.length < 4 || cycles == null || cycles.isEmpty()) {
            return;
        }
        try (java.io.DataInputStream din = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int count = din.readInt();
            if (count != cycles.size()) {
                return;
            }
            for (PaletteCycle cycle : cycles) {
                int size = din.readInt();
                if (size < 0 || size > data.length) {
                    return;
                }
                byte[] state = din.readNBytes(size);
                com.openggf.game.rewind.schema.PaletteCycleStateCodec.restore(cycle, state);
            }
        } catch (java.io.IOException ignored) {
        }
    }

    // ========== Base PaletteCycle class ==========
    private static class PaletteCycle {
        protected final String ownerId;
        protected final byte[] data;
        protected final int frameCount;
        protected final int frameSize;
        protected final int timerReset;
        protected final int paletteIndex;
        protected final int[] colorIndices;
        protected int timer;
        protected int frame;

        protected PaletteCycle(String ownerId, byte[] data, int frameCount, int frameSize,
                               int timerReset, int paletteIndex, int[] colorIndices) {
            this.ownerId = ownerId;
            this.data = data;
            this.frameCount = frameCount;
            this.frameSize = frameSize;
            this.timerReset = timerReset;
            this.paletteIndex = paletteIndex;
            this.colorIndices = colorIndices;
        }

        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            if (data.length == 0 || frameCount <= 0) return;

            if (timer > 0) {
                timer--;
            } else {
                timer = timerReset;
                int frameIndex = frame % frameCount;
                frame++;
                submitFrame(registry, frameIndex);
            }
        }

        protected void submitFrame(PaletteOwnershipRegistry registry, int frameIndex) {
            int base = frameIndex * frameSize;
            for (int i = 0; i < colorIndices.length; i++) {
                int dataIndex = base + i * 2;
                if (dataIndex + 1 >= data.length) continue;
                submitColor(registry, ownerId, paletteIndex, colorIndices[i], data, dataIndex);
            }
        }
    }

    // ========== HTZ Lava Cycle (variable timing) ==========
    private static class HtzLavaCycle extends PaletteCycle {
        private final int[] delays;

        HtzLavaCycle(byte[] data, int[] delays) {
            super(OWNER_HTZ_LAVA, data, 16, 8, delays[0], 1, new int[]{3, 4, 5, 6});
            this.delays = delays;
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                int frameIndex = frame & 0x0F;
                timer = delays[frameIndex];
                frame++;

                // Apply to palette line 2: colors 3,4,5,6 and 14,15 (from same data)
                int base = frameIndex * 8;

                // First group: indices 3,4 from offset 0
                submitColor(registry, ownerId, 1, 3, data, base);
                submitColor(registry, ownerId, 1, 4, data, base + 2);

                // Second group: indices 14,15 from offset 4
                submitColor(registry, ownerId, 1, 14, data, base + 4);
                submitColor(registry, ownerId, 1, 15, data, base + 6);
            }
        }
    }

    // ========== MTZ Cycle 2 (3 colors, special layout) ==========
    private static class MtzCycle2 extends PaletteCycle {
        MtzCycle2(byte[] data) {
            super(OWNER_MTZ_CYCLE_2, data, 3, 6, 2, 2, new int[]{1, 2, 3});
        }

        @Override
        protected void submitFrame(PaletteOwnershipRegistry registry, int frameIndex) {
            int base = frameIndex * 2;
            // Data layout: 3 frames of 2+2+2 bytes for 3 colors
            // But the file is only 12 bytes, so it's 2 bytes per frame for 3 colors interleaved
            // Actually reads: move.l (a0,d0.w),(a1)+  move.w 4(a0,d0.w),(a1)
            // So it reads 6 bytes (3 colors) from base offset
            if (base + 5 < data.length) {
                submitColor(registry, ownerId, 2, 1, data, base);
                submitColor(registry, ownerId, 2, 2, data, base + 2);
                submitColor(registry, ownerId, 2, 3, data, base + 4);
            }
        }
    }

    // ========== CNZ Cycle 1 (interleaved layout) ==========
    private static class CnzCycle1 extends PaletteCycle {
        CnzCycle1(byte[] data) {
            super(OWNER_CNZ_CYCLE_1, data, 3, 2, 7, 0, new int[]{});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
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
                int d0 = frameIndex * 2;

                submitColor(registry, ownerId, 2, 5, data, d0);
                submitColor(registry, ownerId, 2, 6, data, 6 + d0);
                submitColor(registry, ownerId, 2, 7, data, 12 + d0);
                submitColor(registry, ownerId, 2, 11, data, 18 + d0);
                submitColor(registry, ownerId, 2, 12, data, 24 + d0);
                submitColor(registry, ownerId, 2, 13, data, 30 + d0);
            }
        }
    }

    // ========== CNZ Cycle 3 (3 interleaved colors) ==========
    private static class CnzCycle3 extends PaletteCycle {
        CnzCycle3(byte[] data) {
            super(OWNER_CNZ_CYCLE_3, data, 3, 2, 7, 0, new int[]{});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                timer = timerReset;
                int frameIndex = frame % 3;
                frame++;

                // Line 4 (palette 3): offsets $64, $66, $68 = indices 2,3,4
                int d0 = frameIndex * 2;

                submitColor(registry, ownerId, 3, 2, data, d0);
                submitColor(registry, ownerId, 3, 3, data, 6 + d0);
                submitColor(registry, ownerId, 3, 4, data, 12 + d0);
            }
        }
    }

    // ========== CNZ Cycle 4 (scrolling neon) ==========
    private static class CnzCycle4 extends PaletteCycle {
        private int cnzFrame;

        CnzCycle4(byte[] data) {
            super(OWNER_CNZ_CYCLE_4, data, 18, 2, 7, 3, new int[]{});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
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
            if (d0 + 4 < data.length) {
                submitColor(registry, ownerId, 3, 9, data, d0 + 4);
                submitColor(registry, ownerId, 3, 10, data, d0 + 2);
                submitColor(registry, ownerId, 3, 11, data, d0);
            }
        }
    }

    // ========== WFZ Fire/Belt Cycle (toggle-dependent) ==========
    // ROM: PalCycle_WFZ first section (s2.asm:2994-3010)
    // When WFZ_SCZ_Fire_Toggle == 0: uses CyclingPal_WFZFire data, timer 1
    // When WFZ_SCZ_Fire_Toggle != 0: uses CyclingPal_WFZBelt data, timer 5
    // 4 frames of 8 bytes, writes to Normal_palette_line3+$E (palette line 3, indices 7,8,9,10)
    private static class WfzFireBeltCycle extends PaletteCycle {
        private final byte[] fireData;
        private final byte[] beltData;

        // Timer values from ROM (s2.asm:2997 and s2.asm:3001)
        private static final int FIRE_TIMER_RESET = 1;
        private static final int BELT_TIMER_RESET = 5;

        // 4 frames of 8 bytes = 32 bytes. Frame counter wraps at 4 (cmpi.w #$20 with addq.w #8).
        private static final int FRAME_COUNT = 4;
        private static final int FRAME_SIZE = 8;

        WfzFireBeltCycle(byte[] fireData, byte[] beltData) {
            // Palette line 3 (index 2), 4 colors at offset $E = indices 7,8,9,10
            super(OWNER_WFZ_FIRE_BELT, fireData, FRAME_COUNT, FRAME_SIZE, FIRE_TIMER_RESET, 2,
                    new int[]{7, 8, 9, 10});
            this.fireData = fireData;
            this.beltData = beltData;
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            if (fireData.length == 0 || beltData.length == 0) return;

            if (timer > 0) {
                timer--;
            } else {
                // ROM: tst.b (WFZ_SCZ_Fire_Toggle).w / beq.s +
                boolean useConveyor = GameServices.gameState().isWfzFireToggle();
                byte[] activeData = useConveyor ? beltData : fireData;
                timer = useConveyor ? BELT_TIMER_RESET : FIRE_TIMER_RESET;

                // ROM: addq.w #8,(PalCycle_Frame).w / cmpi.w #$20 wraps at 4 frames
                int frameIndex = frame % FRAME_COUNT;
                frame++;

                // ROM: lea (Normal_palette_line3+$E).w,a1
                // move.l (a0,d0.w),(a1)+ / move.l 4(a0,d0.w),(a1)
                // Writes 8 bytes (4 colors) to palette line 3, offset $E (indices 7-10)
                int base = frameIndex * FRAME_SIZE;
                submitContiguous(registry, ownerId, 2, 7, activeData, base, 8);
            }
        }
    }

    // ========== CNZ Boss Cycle 1 (electricity effect - 3 colors) ==========
    // ROM: CyclingPal_CNZ1_B (s2.asm:2926-2931)
    // Interleaved layout: 3 frames, reads at d0, d0+6, d0+12 for colors 2,3,4 of palette line 1
    private static class CnzBossCycle1 extends PaletteCycle {
        CnzBossCycle1(byte[] data) {
            // Timer 3, palette line 1 (index 1)
            super(OWNER_CNZ_BOSS_CYCLE_1, data, 3, 2, 3, 1, new int[]{});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            // Only run when boss is active (ROM: tst.b (Current_Boss_ID).w)
            if (GameServices.gameState().getCurrentBossId() == 0) {
                return;
            }
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
                return;
            }
            timer = timerReset;

            int frameIndex = frame % 3;
            frame++;

            // Palette line 2 (index 1): offsets $24, $26, $28 = indices 2, 3, 4
            // Data is interleaved: read at d0, d0+6, d0+12 (0xC)
            int d0 = frameIndex * 2;

            if (d0 + 12 < data.length) {
                submitColor(registry, ownerId, 1, 2, data, d0);
                submitColor(registry, ownerId, 1, 3, data, 6 + d0);
                submitColor(registry, ownerId, 1, 4, data, 12 + d0);
            }
        }
    }

    // ========== CNZ Boss Cycle 2 (electricity effect - 1 color scrolling) ==========
    // ROM: CyclingPal_CNZ2_B (s2.asm:2932-2938)
    // 10 frames (0x14 bytes / 2 = 10), writes to palette line 1, offset $3C (index 14)
    private static class CnzBossCycle2 extends PaletteCycle {
        CnzBossCycle2(byte[] data) {
            // Timer 3, palette line 1 (index 1), 10 frames
            super(OWNER_CNZ_BOSS_CYCLE_2, data, 10, 2, 3, 1, new int[]{14});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            // Only run when boss is active
            if (GameServices.gameState().getCurrentBossId() == 0) {
                return;
            }
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
                return;
            }
            timer = timerReset;

            int frameIndex = frame % 10;
            frame++;

            // Palette line 2 (index 1): offset $3C = index 14
            int d0 = frameIndex * 2;

            if (d0 + 1 < data.length) {
                submitColor(registry, ownerId, 1, 14, data, d0);
            }
        }
    }

    // ========== CNZ Boss Cycle 3 (electricity effect - 1 color cycling) ==========
    // ROM: CyclingPal_CNZ3_B (s2.asm:2939-2943)
    // 8 frames (andi.w #$E wraps frame counter), writes to palette line 1, offset $3E (index 15)
    private static class CnzBossCycle3 extends PaletteCycle {
        CnzBossCycle3(byte[] data) {
            // Timer 3, palette line 1 (index 1), 8 frames
            super(OWNER_CNZ_BOSS_CYCLE_3, data, 8, 2, 3, 1, new int[]{15});
        }

        @Override
        protected void tick(Level level, PaletteOwnershipRegistry registry) {
            // Only run when boss is active
            if (GameServices.gameState().getCurrentBossId() == 0) {
                return;
            }
            if (data.length == 0) return;

            if (timer > 0) {
                timer--;
                return;
            }
            timer = timerReset;

            // ROM uses: andi.w #$E,(PalCycle_Frame2_CNZ).w
            // This means frame wraps at 8 (0, 2, 4, 6, 8, 10, 12, 14 -> andi $E = 0-7 * 2)
            int frameIndex = frame & 7;  // Equivalent to andi #$E on d0*2
            frame++;

            // Palette line 2 (index 1): offset $3E = index 15
            int d0 = frameIndex * 2;

            if (d0 + 1 < data.length) {
                submitColor(registry, ownerId, 1, 15, data, d0);
            }
        }
    }

    private static void submitColor(PaletteOwnershipRegistry registry, String ownerId,
                                    int lineIndex, int colorIndex, byte[] sourceData, int dataIndex) {
        if (dataIndex < 0 || dataIndex + 1 >= sourceData.length) {
            return;
        }
        registry.submit(PaletteWrite.normal(ownerId, PALETTE_CYCLE_PRIORITY, lineIndex, colorIndex,
                new byte[]{sourceData[dataIndex], sourceData[dataIndex + 1]}));
    }

    private static void submitContiguous(PaletteOwnershipRegistry registry, String ownerId,
                                         int lineIndex, int startColor, byte[] sourceData,
                                         int dataIndex, int byteLength) {
        if (dataIndex < 0 || byteLength <= 0 || dataIndex + byteLength > sourceData.length) {
            return;
        }
        byte[] copy = new byte[byteLength];
        System.arraycopy(sourceData, dataIndex, copy, 0, byteLength);
        registry.submit(PaletteWrite.normal(ownerId, PALETTE_CYCLE_PRIORITY, lineIndex, startColor, copy));
    }
}
