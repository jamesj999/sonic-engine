package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteWrite;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.WaterSystem;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.data.compression.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies Sonic 3&amp;K palette cycling for supported zones.
 * Based on the original AnPal_* routines in sonic3k.asm (lines 3173-3282).
 *
 * <p>AIZ1 uses a shared timer controlling two sub-cycles (waterfall + secondary water),
 * matching the ROM's single counter1/counter0 pair. AIZ2 uses two independent timers
 * for water cycling and torch glow, with camera-dependent table switching at X >= 0x3800.
 */
class Sonic3kPaletteCycler implements AnimatedPaletteManager {
    private final Level level;
    private final int zoneIndex;
    private final int actIndex;
    private final PaletteOwnershipRegistry paletteRegistry;
    private final boolean localPaletteRegistry;
    private final Palette[] explicitUnderwaterPalettes;
    private final List<PaletteCycle> cycles;
    private Palette[] cachedLevelPalettes;

    static int resolveSlotsModeForTest(S3kSlotBonusStageRuntime runtime) {
        return runtime != null ? runtime.paletteCycleMode() : 0;
    }

    static int resolveSlotsModeFromSessionForTest() {
        return resolveSlotsModeFromSession();
    }

    Sonic3kPaletteCycler(RomByteReader reader, Level level, int zoneIndex, int actIndex) {
        this(reader, level, zoneIndex, actIndex, null, null);
    }

    Sonic3kPaletteCycler(RomByteReader reader, Level level, int zoneIndex, int actIndex,
                         PaletteOwnershipRegistry paletteRegistry,
                         Palette[] explicitUnderwaterPalettes) {
        this.level = level;
        this.zoneIndex = zoneIndex;
        this.actIndex = actIndex;
        this.paletteRegistry = paletteRegistry != null ? paletteRegistry : new PaletteOwnershipRegistry();
        this.localPaletteRegistry = paletteRegistry == null;
        this.explicitUnderwaterPalettes = explicitUnderwaterPalettes;
        this.cycles = loadCycles(reader, zoneIndex, actIndex);
    }

    @Override
    public void update() {
        if (localPaletteRegistry) {
            paletteRegistry.beginFrame();
        }
        if (cycles != null && !cycles.isEmpty()) {
            // ROM: AnimatePalettes dispatches to AnPal_* every frame unconditionally,
            // regardless of fire transition state. Never suspend palette cycling.
            for (PaletteCycle cycle : cycles) {
                cycle.tick(level, paletteRegistry);
            }
        }
        paletteRegistry.resolveInto(levelPalettes(), resolveUnderwaterPalettes(),
                GameServices.graphics(), level.getPalette(0));
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

    private Palette[] resolveUnderwaterPalettes() {
        if (explicitUnderwaterPalettes != null) {
            return explicitUnderwaterPalettes;
        }
        WaterSystem water = GameServices.waterOrNull();
        if (water != null) {
            return water.getUnderwaterPalette(zoneIndex, actIndex);
        }
        return null;
    }

    private List<PaletteCycle> loadCycles(RomByteReader reader, int zoneIndex, int actIndex) {
        List<PaletteCycle> list = new ArrayList<>();
        switch (zoneIndex) {
            case 0x00: // AIZ
                if (actIndex == 0) {
                    loadAiz1Cycles(reader, list);
                } else {
                    loadAiz2Cycles(reader, list);
                }
                break;

            case 0x01: // HCZ — AnPal_HCZ1 water surface shimmer + cave lighting
                // AnPal_HCZ2 is rts (no palette cycling for Act 2)
                loadHczCycles(reader, list, actIndex);
                break;

            case 0x03: // CNZ — AnPal_CNZ bumper glow, background, tertiary
                loadCnzCycles(reader, list);
                break;

            // FBZ (zone 0x04) — AnPal_FBZ: no palette table cycling. S3K version toggles
            // bit 0 of _unkF7C1 every other frame (flicker effect), not a color table
            // animation. S3 version is rts. Intentionally no-op.
            case 0x04: break;

            case 0x05: // ICZ — AnPal_ICZ geyser/ice shimmer, conditional channels
                loadIczCycles(reader, list);
                break;

            case 0x06: // LBZ — AnPal_LBZ1 / AnPal_LBZ2 pipe fluid cycling
                loadLbzCycles(reader, list, actIndex);
                break;

            case 0x09: // LRZ — AnPal_LRZ1 / AnPal_LRZ2 lava + crystal colors
                loadLrzCycles(reader, list, actIndex);
                break;

            // Competition-zone ids follow the ROM's OffsAnPal table, which is
            // 48 entries indexed zone*2 + act (skdisasm/sonic3k.asm:3110-3165):
            // BPZ occupies entries 30/31, CGZ 34/35 and EMZ 36/37.
            case 0x0F: // BPZ (competition) — AnPal_BPZ balloons + background
                loadBpzCycles(reader, list);
                break;

            case 0x11: // CGZ (competition) — AnPal_CGZ light animation
                loadCgzCycles(reader, list);
                break;

            case 0x12: // EMZ (competition) — AnPal_EMZ emerald glow + background
                loadEmzCycles(reader, list);
                break;

            case 0x14:
                loadPachinkoCycles(reader, list);
                break;
            case 0x15:
                loadSlotsCycles(reader, list);
                break;

            default: break;
        }
        return list;
    }

    private void loadAiz1Cycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] waterfallData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ1_1_ADDR, Sonic3kConstants.ANPAL_AIZ1_1_SIZE);
        byte[] secondaryData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ1_2_ADDR, Sonic3kConstants.ANPAL_AIZ1_2_SIZE);
        byte[] introData1 = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ1_3_ADDR, Sonic3kConstants.ANPAL_AIZ1_3_SIZE);
        byte[] introData2 = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ1_4_ADDR, Sonic3kConstants.ANPAL_AIZ1_4_SIZE);

        if (waterfallData.length >= Sonic3kConstants.ANPAL_AIZ1_1_SIZE
                && secondaryData.length >= Sonic3kConstants.ANPAL_AIZ1_2_SIZE
                && introData1.length >= Sonic3kConstants.ANPAL_AIZ1_3_SIZE
                && introData2.length >= Sonic3kConstants.ANPAL_AIZ1_4_SIZE) {
            list.add(new Aiz1Cycle(waterfallData, secondaryData, introData1, introData2));
        }
    }

    private void loadAiz2Cycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] waterData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ2_1_ADDR, Sonic3kConstants.ANPAL_AIZ2_1_SIZE);
        byte[] tricklePreData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ2_2_ADDR, Sonic3kConstants.ANPAL_AIZ2_2_SIZE);
        byte[] tricklePostData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ2_3_ADDR, Sonic3kConstants.ANPAL_AIZ2_3_SIZE);
        byte[] torchPreData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ2_4_ADDR, Sonic3kConstants.ANPAL_AIZ2_4_SIZE);
        byte[] torchPostData = safeSlice(reader, Sonic3kConstants.ANPAL_AIZ2_5_ADDR, Sonic3kConstants.ANPAL_AIZ2_5_SIZE);

        if (waterData.length >= Sonic3kConstants.ANPAL_AIZ2_1_SIZE
                && tricklePreData.length >= Sonic3kConstants.ANPAL_AIZ2_2_SIZE
                && tricklePostData.length >= Sonic3kConstants.ANPAL_AIZ2_3_SIZE) {
            list.add(new Aiz2WaterCycle(waterData, tricklePreData, tricklePostData));
        }
        if (torchPreData.length >= Sonic3kConstants.ANPAL_AIZ2_4_SIZE
                && torchPostData.length >= Sonic3kConstants.ANPAL_AIZ2_5_SIZE) {
            list.add(new Aiz2TorchCycle(torchPreData, torchPostData));
        }
    }

    private void loadHczCycles(RomByteReader reader, List<PaletteCycle> list, int actIndex) {
        // AnPal_HCZ2 (sonic3k.asm line 3315) is rts — Act 2 has no palette cycling.
        if (actIndex != 0) {
            return;
        }
        byte[] waterData = safeSlice(reader, Sonic3kConstants.ANPAL_HCZ1_ADDR, Sonic3kConstants.ANPAL_HCZ1_SIZE);
        if (waterData.length >= Sonic3kConstants.ANPAL_HCZ1_SIZE) {
            list.add(new HczCycle(waterData));
        }
    }

    private void loadCnzCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] bumperData = safeSlice(reader, Sonic3kConstants.ANPAL_CNZ_1_ADDR, Sonic3kConstants.ANPAL_CNZ_1_SIZE);
        byte[] bumperWaterData = safeSlice(reader, Sonic3kConstants.ANPAL_CNZ_2_ADDR, Sonic3kConstants.ANPAL_CNZ_2_SIZE);
        byte[] bgData = safeSlice(reader, Sonic3kConstants.ANPAL_CNZ_3_ADDR, Sonic3kConstants.ANPAL_CNZ_3_SIZE);
        byte[] bgWaterData = safeSlice(reader, Sonic3kConstants.ANPAL_CNZ_4_ADDR, Sonic3kConstants.ANPAL_CNZ_4_SIZE);
        byte[] tertiaryData = safeSlice(reader, Sonic3kConstants.ANPAL_CNZ_5_ADDR, Sonic3kConstants.ANPAL_CNZ_5_SIZE);

        if (bumperData.length >= Sonic3kConstants.ANPAL_CNZ_1_SIZE
                && bumperWaterData.length >= Sonic3kConstants.ANPAL_CNZ_2_SIZE
                && bgData.length >= Sonic3kConstants.ANPAL_CNZ_3_SIZE
                && bgWaterData.length >= Sonic3kConstants.ANPAL_CNZ_4_SIZE
                && tertiaryData.length >= Sonic3kConstants.ANPAL_CNZ_5_SIZE) {
            list.add(new CnzCycle(bumperData, bumperWaterData, bgData, bgWaterData, tertiaryData));
        }
    }

    private void loadIczCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] data1 = safeSlice(reader, Sonic3kConstants.ANPAL_ICZ_1_ADDR, Sonic3kConstants.ANPAL_ICZ_1_SIZE);
        byte[] data2 = safeSlice(reader, Sonic3kConstants.ANPAL_ICZ_2_ADDR, Sonic3kConstants.ANPAL_ICZ_2_SIZE);
        byte[] data3 = safeSlice(reader, Sonic3kConstants.ANPAL_ICZ_3_ADDR, Sonic3kConstants.ANPAL_ICZ_3_SIZE);
        byte[] data4 = safeSlice(reader, Sonic3kConstants.ANPAL_ICZ_4_ADDR, Sonic3kConstants.ANPAL_ICZ_4_SIZE);

        if (data1.length >= Sonic3kConstants.ANPAL_ICZ_1_SIZE
                && data2.length >= Sonic3kConstants.ANPAL_ICZ_2_SIZE
                && data3.length >= Sonic3kConstants.ANPAL_ICZ_3_SIZE
                && data4.length >= Sonic3kConstants.ANPAL_ICZ_4_SIZE) {
            list.add(new IczCycle(data1, data2, data3, data4));
        }
    }

    private void loadLbzCycles(RomByteReader reader, List<PaletteCycle> list, int actIndex) {
        int addr = (actIndex == 0)
                ? Sonic3kConstants.ANPAL_LBZ1_ADDR
                : Sonic3kConstants.ANPAL_LBZ2_ADDR;
        int size = (actIndex == 0)
                ? Sonic3kConstants.ANPAL_LBZ1_SIZE
                : Sonic3kConstants.ANPAL_LBZ2_SIZE;
        byte[] tableData = safeSlice(reader, addr, size);
        if (tableData.length >= size) {
            list.add(new LbzCycle(tableData));
        }
    }

    private void loadLrzCycles(RomByteReader reader, List<PaletteCycle> list, int actIndex) {
        byte[] sharedData1 = safeSlice(reader, Sonic3kConstants.ANPAL_LRZ12_1_ADDR, Sonic3kConstants.ANPAL_LRZ12_1_SIZE);
        byte[] sharedData2 = safeSlice(reader, Sonic3kConstants.ANPAL_LRZ12_2_ADDR, Sonic3kConstants.ANPAL_LRZ12_2_SIZE);

        if (actIndex == 0) {
            // LRZ Act 1: channels A+B (shared) + channel C
            byte[] lrz1Data3 = safeSlice(reader, Sonic3kConstants.ANPAL_LRZ1_3_ADDR, Sonic3kConstants.ANPAL_LRZ1_3_SIZE);
            if (sharedData1.length >= Sonic3kConstants.ANPAL_LRZ12_1_SIZE
                    && sharedData2.length >= Sonic3kConstants.ANPAL_LRZ12_2_SIZE
                    && lrz1Data3.length >= Sonic3kConstants.ANPAL_LRZ1_3_SIZE) {
                list.add(new Lrz1Cycle(sharedData1, sharedData2, lrz1Data3));
            }
        } else {
            // LRZ Act 2: channels A+B (shared) + channel D
            byte[] lrz2Data3 = safeSlice(reader, Sonic3kConstants.ANPAL_LRZ2_3_ADDR, Sonic3kConstants.ANPAL_LRZ2_3_SIZE);
            if (sharedData1.length >= Sonic3kConstants.ANPAL_LRZ12_1_SIZE
                    && sharedData2.length >= Sonic3kConstants.ANPAL_LRZ12_2_SIZE
                    && lrz2Data3.length >= Sonic3kConstants.ANPAL_LRZ2_3_SIZE) {
                list.add(new Lrz2Cycle(sharedData1, sharedData2, lrz2Data3));
            }
        }
    }

    private void loadBpzCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] balloonsData = safeSlice(reader, Sonic3kConstants.ANPAL_BPZ_1_ADDR, Sonic3kConstants.ANPAL_BPZ_1_SIZE);
        byte[] bgData = safeSlice(reader, Sonic3kConstants.ANPAL_BPZ_2_ADDR, Sonic3kConstants.ANPAL_BPZ_2_SIZE);

        if (balloonsData.length >= Sonic3kConstants.ANPAL_BPZ_1_SIZE
                && bgData.length >= Sonic3kConstants.ANPAL_BPZ_2_SIZE) {
            list.add(new BpzCycle(balloonsData, bgData));
        }
    }

    private void loadCgzCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] cgzData = safeSlice(reader, Sonic3kConstants.ANPAL_CGZ_ADDR, Sonic3kConstants.ANPAL_CGZ_SIZE);
        if (cgzData.length >= Sonic3kConstants.ANPAL_CGZ_SIZE) {
            list.add(new CgzCycle(cgzData));
        }
    }

    private void loadEmzCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] glowData = safeSlice(reader, Sonic3kConstants.ANPAL_EMZ1_ADDR, Sonic3kConstants.ANPAL_EMZ1_SIZE);
        byte[] bgData = safeSlice(reader, Sonic3kConstants.ANPAL_EMZ2_ADDR, Sonic3kConstants.ANPAL_EMZ2_SIZE);

        if (glowData.length >= Sonic3kConstants.ANPAL_EMZ1_SIZE
                && bgData.length >= Sonic3kConstants.ANPAL_EMZ2_SIZE) {
            list.add(new EmzCycle(glowData, bgData));
        }
    }

    private void loadPachinkoCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] tableData = loadKosinskiBytes(reader,
                Sonic3kConstants.PAL_KOS_PACHINKO_ADDR,
                Sonic3kConstants.PAL_KOS_PACHINKO_SIZE,
                0x532);
        if (tableData != null) {
            list.add(new PachinkoCycle(tableData));
        }
    }

    private void loadSlotsCycles(RomByteReader reader, List<PaletteCycle> list) {
        byte[] idleData = safeSlice(reader, Sonic3kConstants.ANPAL_SLOTS_1_ADDR,
                Sonic3kConstants.ANPAL_SLOTS_1_SIZE);
        byte[] captureData = safeSlice(reader, Sonic3kConstants.ANPAL_SLOTS_2_ADDR,
                Sonic3kConstants.ANPAL_SLOTS_2_SIZE);
        byte[] accentData = safeSlice(reader, Sonic3kConstants.ANPAL_SLOTS_3_ADDR,
                Sonic3kConstants.ANPAL_SLOTS_3_SIZE);
        if (idleData.length >= Sonic3kConstants.ANPAL_SLOTS_1_SIZE
                && captureData.length >= Sonic3kConstants.ANPAL_SLOTS_2_SIZE
                && accentData.length >= Sonic3kConstants.ANPAL_SLOTS_3_SIZE) {
            list.add(new SlotsCycle(idleData, captureData, accentData));
        }
    }

    private byte[] safeSlice(RomByteReader reader, int addr, int len) {
        if (addr < 0 || addr + len > reader.size()) {
            return new byte[0];
        }
        return reader.slice(addr, len);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static int segaWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void cacheFallbackPaletteTexture(PaletteOwnershipRegistry registry,
                                                    GraphicsManager graphics,
                                                    Level level,
                                                    int paletteIndex) {
        if (registry == null && graphics != null && graphics.isGlInitialized()) {
            graphics.cachePaletteTexture(level.getPalette(paletteIndex), paletteIndex);
        }
    }

    /**
     * Captures per-cycle mutable state (timers, counters, dirty flags, gate flags)
     * across all currently-loaded cycles. The returned bytes are intended to be
     * embedded in the level-animation manager's snapshot.
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
                return; // shape mismatch — refuse to corrupt state
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
            // tolerate truncation / corruption
        }
    }

    private byte[] loadKosinskiBytes(RomByteReader reader, int addr, int compressedSize,
                                     int minimumDecompressedSize) {
        if (addr < 0 || addr + compressedSize > reader.size()) {
            return null;
        }
        byte[] compressed = reader.slice(addr, compressedSize);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed)) {
            byte[] decompressed = KosinskiReader.decompress(Channels.newChannel(bais), false);
            return decompressed.length >= minimumDecompressedSize ? decompressed : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ========== Base class ==========
    private static abstract class PaletteCycle {
        abstract void tick(Level level, PaletteOwnershipRegistry registry);
    }

    // ========== AIZ1 Unified Cycle ==========
    // ROM: AnPal_AIZ1 (line 3173) checks AIZ1_palette_cycle_flag (a one-shot RAM flag)
    // set by AIZ1_Resize routine 0 (line 38873-38877):
    //   - Flag starts as 1 (intro mode)
    //   - Set to 0 when Camera X >= 0x1000 (gameplay mode)
    //   - Never set again — flag stays 0 for the rest of the level
    //
    // Intro mode (flag != 0, timer period 10):
    //   PalAIZ1_3 → palette 3 colors 2-5 (10 frames, wraps at 0x50)
    //   PalAIZ1_4 → palette 3 colors 13-15 (10 frames, wraps at 0x3C)
    //
    // Gameplay mode (flag == 0, timer period 8):
    //   PalAIZ1_1 → palette 2 colors 11-14 (4 frames, counter0 & 0x18)
    //   PalAIZ1_2 → palette 3 colors 12-14 (8 frames, wraps at 0x30, gated by isLevelStarted)
    //
    // Timers are shared across both modes (ROM uses same counter1/counter0/counters+$02).
    private static class Aiz1Cycle extends PaletteCycle {
        // Gameplay mode data
        private final byte[] waterfallData;  // PalAIZ1_1: 32 bytes (4 frames × 8 bytes)
        private final byte[] secondaryData;  // PalAIZ1_2: 48 bytes (8 frames × 6 bytes)
        // Intro mode data
        private final byte[] introData1;     // PalAIZ1_3: 80 bytes (10 frames × 8 bytes)
        private final byte[] introData2;     // PalAIZ1_4: 60 bytes (10 frames × 6 bytes)

        // Shared timer/counter state (persists through mode transitions)
        private int timer;    // Palette_cycle_counter1
        private int counter0; // Palette_cycle_counter0
        private int counter2; // Palette_cycle_counters+$02
        private boolean dirty2;
        private boolean dirty3;

        // One-shot flag matching ROM's AIZ1_palette_cycle_flag (sonic3k.constants.asm line 630).
        // Starts true (intro), cleared permanently once Camera X >= 0x1000.
        private boolean introFlag = true;

        Aiz1Cycle(byte[] waterfallData, byte[] secondaryData, byte[] introData1, byte[] introData2) {
            this.waterfallData = waterfallData;
            this.secondaryData = secondaryData;
            this.introData1 = introData1;
            this.introData2 = introData2;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            // ROM: AIZ1_Resize routine 0 (line 38873-38877) clears the flag once
            // when Camera X >= 0x1000. It never re-sets it.
            if (introFlag && (GameServices.camera().getX() & 0xFFFF) >= 0x1000) {
                introFlag = false;
            }

            if (timer > 0) {
                timer--;
            } else {
                if (introFlag) {
                    tickIntro(level, registry, gm);
                } else {
                    tickGameplay(level, registry, gm);
                }
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }

        private void tickIntro(Level level, PaletteOwnershipRegistry registry, GraphicsManager gm) {
            timer = 9;

            // PalAIZ1_3 → palette 3, colors 2-5
            int d0 = counter0;
            counter0 += 8;
            if (counter0 >= 0x50) {
                counter0 = 0;
            }
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.AIZ1_ANPAL,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    3,
                    2,
                    slice(introData1, d0, 8));

            // PalAIZ1_4 → palette 3, colors 13-15
            int d1 = counter2;
            counter2 += 6;
            if (counter2 >= 0x3C) {
                counter2 = 0;
            }
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.AIZ1_ANPAL,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    3,
                    13,
                    slice(introData2, d1, 6));

            dirty3 = true;
        }

        private void tickGameplay(Level level, PaletteOwnershipRegistry registry, GraphicsManager gm) {
            timer = 7;

            // PalAIZ1_1 → palette 2, colors 11-14
            // ROM uses byte-width counter (wraps at 256); mask & 0x18 cycles
            // through 0,8,16,24 for waterfallData indexing.
            int d0 = counter0 & 0x18;
            counter0 = (counter0 + 8) & 0xFF;
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.AIZ1_ANPAL,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    11,
                    slice(waterfallData, d0, 8));
            dirty2 = true;

            // PalAIZ1_2 → palette 3, colors 12-14
            // ROM gates this with tst.b (Palette_cycle_counters+$00) / bne.s skip.
            // During intro, byte is non-zero (emerald palette occupies palette 3).
            // We use isLevelStarted() as an equivalent gate.
            if (GameServices.camera().isLevelStarted()) {
                // counter2 may carry a value from intro mode (wraps at 0x3C)
                // that exceeds secondaryData bounds (0x30). Re-wrap first.
                if (counter2 >= 0x30) {
                    counter2 = 0;
                }
                int d1 = counter2;
                counter2 += 6;
                if (counter2 >= 0x30) {
                    counter2 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ1_ANPAL,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        12,
                        slice(secondaryData, d1, 6));
                dirty3 = true;
            }
        }
    }

    // ========== AIZ2 Water Cycle ==========
    // ROM: AnPal_AIZ2 first half (timer period 6)
    // Cycle A: AnPal_PalAIZ2_1 → palette 3 colors 12-15 (4 frames, counter0 & 0x18)
    // Cycle B: AnPal_PalAIZ2_2/3 → pal 2 colors 4,8 + pal 3 color 11 (8 frames, wraps at 0x30)
    //   Table switches from PalAIZ2_2 to PalAIZ2_3 when camera X >= 0x3800
    //   Also: pal 2 color 14 = fixed 0x0A0E when camera X >= 0x1C0, else from data
    private static class Aiz2WaterCycle extends PaletteCycle {
        private final byte[] waterData;       // 32 bytes: 4 frames × 8 bytes
        private final byte[] tricklePreData;  // 48 bytes: 8 frames × 6 bytes
        private final byte[] tricklePostData; // 48 bytes: 8 frames × 6 bytes
        private int timer;
        private int counter0;
        private int counter2;
        private boolean dirty2;
        private boolean dirty3;

        Aiz2WaterCycle(byte[] waterData, byte[] tricklePreData, byte[] tricklePostData) {
            this.waterData = waterData;
            this.tricklePreData = tricklePreData;
            this.tricklePostData = tricklePostData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            if (timer > 0) {
                timer--;
            } else {
                timer = 5;
                int cameraX = GameServices.camera().getX() & 0xFFFF;

                // Cycle A: water → palette 3, colors 12-15
                int d0 = counter0 & 0x18;
                counter0 += 8;
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ2_WATER_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        12,
                        slice(waterData, d0, 8));
                dirty3 = true;

                // Cycle B: trickle → pal 2 colors 4,8 + pal 3 color 11
                int d1 = counter2;
                counter2 += 6;
                if (counter2 >= 0x30) {
                    counter2 = 0;
                }
                byte[] trickleData = (cameraX >= 0x3800) ? tricklePostData : tricklePreData;
                S3kPaletteWriteSupport.applyColors(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ2_WATER_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        new int[] {4, 8},
                        new int[] {segaWord(trickleData, d1), segaWord(trickleData, d1 + 2)});
                S3kPaletteWriteSupport.applyColors(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ2_WATER_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        new int[] {11},
                        new int[] {segaWord(trickleData, d1 + 4)});

                // Pal 2 color 14: fixed $0A0E when camera >= 0x1C0, else animated from data
                int pal2Color14 = cameraX >= 0x1C0 ? 0x0A0E : segaWord(trickleData, d1 + 4);
                S3kPaletteWriteSupport.applyColors(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ2_WATER_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        new int[] {14},
                        new int[] {pal2Color14});
                dirty2 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }

    // ========== AIZ2 Torch Glow Cycle ==========
    // ROM: AnPal_AIZ2 second half (timer period 2)
    // AnPal_PalAIZ2_4/5 → palette 3 color 1 (26 frames, wraps at 0x34)
    // Table switches from PalAIZ2_4 to PalAIZ2_5 when camera X >= 0x3800
    private static class Aiz2TorchCycle extends PaletteCycle {
        private final byte[] torchPreData;   // 52 bytes: 26 frames × 2 bytes
        private final byte[] torchPostData;  // 52 bytes: 26 frames × 2 bytes
        private int timer;
        private int counter;
        private boolean dirty;

        Aiz2TorchCycle(byte[] torchPreData, byte[] torchPostData) {
            this.torchPreData = torchPreData;
            this.torchPostData = torchPostData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            if (timer > 0) {
                timer--;
            } else {
                timer = 1;
                int cameraX = GameServices.camera().getX() & 0xFFFF;

                int d0 = counter;
                counter += 2;
                if (counter >= 0x34) {
                    counter = 0;
                }

                byte[] torchData = (cameraX >= 0x3800) ? torchPostData : torchPreData;
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.AIZ2_TORCH_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        1,
                        slice(torchData, d0, 2));
                dirty = true;
            }

            if (dirty) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty = false;
            }
        }
    }

    // ========== HCZ1 Water Cycle ==========
    // ROM: AnPal_HCZ1 (sonic3k.asm line 3287), timer period 7
    // AnPal_PalHCZ1 → palette 2 colors 3-6 (Normal_palette_line_3+$06/$0A)
    //   counter0 & 0x18 for data index, counter0 += 8, wraps at 0x20 (cycles 0,8,16,24)
    // ROM also writes the SAME table to Water_palette_line_3+$06/$0A. Because the
    // normal and water sources are identical (unlike CNZ's separate water tables),
    // the cycle uses PaletteWrite.mirrorToUnderwater() to sync both surfaces.
    // (Covered by TestS3kPaletteOwnershipRegistryIntegration#hczCycleMirrorsWaterColorsIntoUnderwaterPalette.)
    // HCZ1_Resize secondary behavior: checks camera position each tick and writes 3 colors
    //   to palette[3] colors 8-10 (Normal_palette_line_4+$10) for cave entry/exit lighting.
    private static class HczCycle extends PaletteCycle {
        private final byte[] waterData; // 32 bytes: 4 frames × 8 bytes

        // Water cycle state
        private int timer;
        private int counter0;

        // Cave lighting state (HCZ1_Resize secondary behavior)
        // Routine 0 = watch for cave entry, routine 2 = watch for exit, routine 4 = idle
        private int resizeRoutine;

        HczCycle(byte[] waterData) {
            this.waterData = waterData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            tickWaterCycle(level, registry);
            tickCaveLighting(level, registry);
        }

        private void tickWaterCycle(Level level, PaletteOwnershipRegistry registry) {
            if (timer > 0) {
                timer--;
                return;
            }
            timer = 7;

            // AnPal_PalHCZ1 → palette 2, colors 3-6
            int d0 = counter0 & 0x18;
            counter0 += 8;
            if (counter0 >= 0x20) {
                counter0 = 0;
            }

            registry.submit(PaletteWrite.normal(
                    S3kPaletteOwners.HCZ_WATER_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    3,
                    new byte[] {
                            waterData[d0], waterData[d0 + 1],
                            waterData[d0 + 2], waterData[d0 + 3],
                            waterData[d0 + 4], waterData[d0 + 5],
                            waterData[d0 + 6], waterData[d0 + 7]
                    }).mirrorToUnderwater());
        }

        private void tickCaveLighting(Level level, PaletteOwnershipRegistry registry) {
            // HCZ1_Resize secondary behavior: per-frame camera-dependent palette mutation.
            // Palette[3] colors 8-10 = Normal_palette_line_4+$10 (3 words = 3 colors)
            Camera cam = GameServices.camera();
            int cameraX = cam.getX() & 0xFFFF;
            int cameraY = cam.getY() & 0xFFFF;

            if (resizeRoutine == 0) {
                // Watch for cave entry: cameraX < $360 AND cameraY >= $3E0
                if (cameraX < 0x360 && cameraY >= 0x3E0) {
                    registry.submit(PaletteWrite.normal(
                            S3kPaletteOwners.HCZ_CAVE_LIGHTING,
                            S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                            3,
                            8,
                            new byte[] { 0x06, (byte) 0x80, 0x02, 0x40, 0x02, 0x20 }));
                    resizeRoutine = 2;
                }
            } else if (resizeRoutine == 2) {
                // Watch for cave exit
                if (cameraX < 0x360 && cameraY < 0x3E0) {
                    // Back to cave entry watch
                    resizeRoutine = 0;
                } else if (cameraX >= 0x900 && cameraY >= 0x500) {
                    registry.submit(PaletteWrite.normal(
                            S3kPaletteOwners.HCZ_CAVE_LIGHTING,
                            S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                            3,
                            8,
                            new byte[] { 0x0C, (byte) 0xEE, 0x0A, (byte) 0xCE, 0x00, (byte) 0x8A }));
                    resizeRoutine = 4;
                }
            }
            // resizeRoutine == 4: idle, rts
        }
    }

    // ========== CNZ Unified Cycle ==========
    // ROM: AnPal_CNZ (sonic3k.asm line 3319)
    //
    // Channel 1 - Bumpers/teacups (gated by Palette_cycle_counter1, period 3):
    //   AnPal_PalCNZ_1 → Normal_palette_line_4+$12 → palette[3] colors 9-11
    //   counter0 step +6, wrap at 0x60 (16 frames)
    //   AnPal_PalCNZ_2 → Water_palette_line_4+$12 → underwater palette[3] colors 9-11
    //
    // Channel 2 - Background (runs every frame, NOT gated by channel 1 timer):
    //   AnPal_PalCNZ_3 → Normal_palette_line_3+$12 → palette[2] colors 9-11
    //   counter2 (Palette_cycle_counters+$02) step +6, wrap at 0xB4 (30 frames)
    //   AnPal_PalCNZ_4 → Water_palette_line_3+$12 → underwater palette[2] colors 9-11
    //
    // Channel 3 - Tertiary (gated by Palette_cycle_counters+$08, period 2):
    //   AnPal_PalCNZ_5 → Normal_palette_line_3+$0E → palette[2] colors 7-8
    //   counter4 (Palette_cycle_counters+$04) step +4, wrap at 0x40 (16 frames)
    private static class CnzCycle extends PaletteCycle {
        // Immutable ROM-derived resources, like the other cycles' final data tables.
        // Rewind captures only the mutable timer/counter fields below.
        private final List<PaletteWrite> bumperPatches;
        private final List<PaletteWrite> backgroundPatches;
        private final List<PaletteWrite> tertiaryPatches;

        // Channel 1 timer (Palette_cycle_counter1): period 3 → fires every 4 frames
        private int timer1;
        // Channel 1 counter (Palette_cycle_counter0): step +6, wrap 0x60
        private int counter0;
        // Channel 2 counter (Palette_cycle_counters+$02): step +6, wrap 0xB4, runs every frame
        private int counter2;
        // Channel 3 timer (Palette_cycle_counters+$08): period 2 → fires every 3 frames
        private int timer3;
        // Channel 3 counter (Palette_cycle_counters+$04): step +4, wrap 0x40
        private int counter4;

        CnzCycle(byte[] bumperData, byte[] bumperWaterData,
                 byte[] bgData, byte[] bgWaterData, byte[] tertiaryData) {
            bumperPatches = pairedPatches(3, 9, bumperData, bumperWaterData, 6);
            backgroundPatches = pairedPatches(2, 9, bgData, bgWaterData, 6);
            tertiaryPatches = pairedPatches(2, 7, tertiaryData, tertiaryData, 4);
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            // Channel 1 (bumpers) — gated by timer1
            if (timer1 > 0) {
                timer1--;
            } else {
                timer1 = 3;
                int d0 = counter0;
                counter0 += 6;
                if (counter0 >= 0x60) {
                    counter0 = 0;
                }
                submitPairedPatch(registry, bumperPatches, d0 / 6);
            }

            // Channel 2 (background) — always runs every frame
            int d0 = counter2;
            counter2 += 6;
            if (counter2 >= 0xB4) {
                counter2 = 0;
            }
            submitPairedPatch(registry, backgroundPatches, d0 / 6);

            // Channel 3 (tertiary) — gated by timer3
            if (timer3 > 0) {
                timer3--;
            } else {
                timer3 = 2;
                int d1 = counter4;
                counter4 += 4;
                if (counter4 >= 0x40) {
                    counter4 = 0;
                }
                submitPairedPatch(registry, tertiaryPatches, d1 / 4);
            }
        }

        /**
         * Submits paired CNZ normal/water palette patches to the ownership registry.
         *
         * <p>The write uses {@link S3kPaletteOwners#PRIORITY_ZONE_EVENT} so it
         * sits above baseline zone cycling and below object-driven overrides,
         * matching the Task 5 ownership contract for CNZ.
         */
        private static void submitPairedPatch(PaletteOwnershipRegistry registry,
                                              List<PaletteWrite> patches, int frame) {
            registry.submit(patches.get(frame * 2));
            registry.submit(patches.get(frame * 2 + 1));
        }

        private static List<PaletteWrite> pairedPatches(int paletteIndex, int startColor,
                                                       byte[] normalSource, byte[] waterSource,
                                                       int byteCount) {
            List<PaletteWrite> patches = new ArrayList<>();
            for (int offset = 0; offset < normalSource.length; offset += byteCount) {
                patches.add(PaletteWrite.normal(
                        S3kPaletteOwners.CNZ_ANPAL,
                        S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                        paletteIndex, startColor, slice(normalSource, offset, byteCount)));
                patches.add(PaletteWrite.underwater(
                        S3kPaletteOwners.CNZ_ANPAL,
                        S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                        paletteIndex, startColor, slice(waterSource, offset, byteCount)));
            }
            return List.copyOf(patches);
        }
    }

    // ========== ICZ Unified Cycle ==========
    // ROM: AnPal_ICZ (sonic3k.asm line 3379)
    //
    // Channel 1 — geyser/ice (timer period 5):
    //   counter0 +4, wrap 0x40 → Normal_palette_line_3+$1C = palette[2] colors 14-15
    //
    // Channel 2 — conditional (timer1 period 9; gated by Events_bg+$16 != 0):
    //   counter2 +4, wrap 0x48 → Normal_palette_line_4+$1C = palette[3] colors 14-15
    //
    // Channel 3 — conditional (timer2 period 7; same gate as channel 2):
    //   counter4 +4, wrap 0x18 → Normal_palette_line_4+$18 = palette[3] colors 12-13
    //
    // Channel 4 — always runs on timer2 (shares channel 3 timer):
    //   counter6 +4, wrap 0x40 → Normal_palette_line_3+$18 = palette[2] colors 12-13
    private static class IczCycle extends PaletteCycle {
        private final byte[] data1; // 64 bytes: 16 frames × 4 bytes
        private final byte[] data2; // 72 bytes: 18 frames × 4 bytes
        private final byte[] data3; // 24 bytes: 6 frames × 4 bytes
        private final byte[] data4; // 64 bytes: 16 frames × 4 bytes

        // Channel 1 timer (Palette_cycle_counter1), period 5
        private int timer1;
        // Channel 2 timer (Palette_cycle_counters+$08), period 9
        private int timer2;
        // Channel 3+4 timer (Palette_cycle_counters+$0A), period 7
        private int timer3;

        // Byte counters (step +4)
        private int counter0; // channel 1
        private int counter2; // channel 2
        private int counter4; // channel 3
        private int counter6; // channel 4

        private boolean dirty2;
        private boolean dirty3;

        IczCycle(byte[] data1, byte[] data2, byte[] data3, byte[] data4) {
            this.data1 = data1;
            this.data2 = data2;
            this.data3 = data3;
            this.data4 = data4;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            boolean line4CyclesEnabled = isIndoorPaletteCyclingActive();
            // Channel 1 (and 4): timer1
            if (timer1 > 0) {
                timer1--;
            } else {
                timer1 = 5;
                // Channel 1: palette[2] colors 14-15 (Normal_palette_line_3+$1C)
                int d0 = counter0;
                counter0 += 4;
                if (counter0 >= 0x40) {
                    counter0 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.ICZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        14,
                        slice(data1, d0, 4));
                dirty2 = true;
            }

            // Channel 2: independent timer
            if (timer2 > 0) {
                timer2--;
            } else {
                timer2 = 9;
                int d0 = counter2;
                counter2 += 4;
                if (counter2 >= 0x48) {
                    counter2 = 0;
                }
                if (line4CyclesEnabled) {
                    S3kPaletteWriteSupport.applyContiguousPatch(
                            registry,
                            level,
                            gm,
                            S3kPaletteOwners.ICZ_ZONE_CYCLE,
                            S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                            3,
                            14,
                            slice(data2, d0, 4));
                    dirty3 = true;
                }
            }

            // Channel 3+4: shared timer
            if (timer3 > 0) {
                timer3--;
            } else {
                timer3 = 7;
                // Channel 3: palette[3] colors 12-13 (Normal_palette_line_4+$18)
                int d0 = counter4;
                counter4 += 4;
                if (counter4 >= 0x18) {
                    counter4 = 0;
                }
                if (line4CyclesEnabled) {
                    S3kPaletteWriteSupport.applyContiguousPatch(
                            registry,
                            level,
                            gm,
                            S3kPaletteOwners.ICZ_ZONE_CYCLE,
                            S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                            3,
                            12,
                            slice(data3, d0, 4));
                    dirty3 = true;
                }

                // Channel 4: palette[2] colors 12-13 (Normal_palette_line_3+$18) — always runs
                int d1 = counter6;
                counter6 += 4;
                if (counter6 >= 0x40) {
                    counter6 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.ICZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        12,
                        slice(data4, d1, 4));
                dirty2 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }

        private boolean isIndoorPaletteCyclingActive() {
            try {
                return S3kRuntimeStates.currentIcz(GameServices.zoneRuntimeRegistry())
                        .map(state -> state.isIndoorPaletteCyclingActive())
                        .orElse(false);
            } catch (IllegalStateException ignored) {
                // Direct palette-cycler tests may run without a gameplay session.
            }
            return false;
        }
    }

    // ========== LBZ Cycle ==========
    // ROM: AnPal_LBZ1 / AnPal_LBZ2 (shared logic at loc_2516, sonic3k.asm line 3448)
    // Single channel, timer period 4 (reset to 3), counter0 step +6, wrap at 0x12 (18 bytes).
    // AnPal_PalLBZ1/2 → Normal_palette_line_3+$10: move.l + move.w = 3 colors
    //   palette 2 colors 8-10 (offset 0x10 = 8 words from line start)
    private static class LbzCycle extends PaletteCycle {
        private final byte[] tableData; // 18 bytes: 3 frames × 6 bytes

        private int timer;
        private int counter0;
        private boolean dirty;

        LbzCycle(byte[] tableData) {
            this.tableData = tableData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            if (timer > 0) {
                timer--;
            } else {
                timer = 3;
                int d0 = counter0;
                counter0 += 6;
                if (counter0 >= 0x12) {
                    counter0 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LBZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        8,
                        slice(tableData, d0, 6));
                dirty = true;
            }

            if (dirty) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty = false;
            }
        }
    }

    // ========== LRZ Act 1 Cycle ==========
    // ROM: AnPal_LRZ1 (sonic3k.asm)
    //
    // Shared timer (Palette_cycle_counter1, period 16 reset to 0xF):
    //   Channel A: AnPal_PalLRZ12_1 → palette 2 colors 1-4 (2 longwords)
    //     counter0 step +8, wraps at 0x80 (16 frames × 8 bytes = 128 bytes)
    //   Channel B: AnPal_PalLRZ12_2 → palette 3 colors 1-2 (1 longword)
    //     counters+$02 step +4, wraps at 0x1C (7 frames × 4 bytes = 28 bytes)
    //
    // Independent timer (Palette_cycle_counters+$08, period 8 reset to 7):
    //   Channel C: AnPal_PalLRZ1_3 → palette 2 color 11 (1 word)
    //     counters+$04 step +2, wraps at 0x22 (17 frames × 2 bytes = 34 bytes)
    private static class Lrz1Cycle extends PaletteCycle {
        private final byte[] sharedData1; // AnPal_PalLRZ12_1: 128 bytes (channel A)
        private final byte[] sharedData2; // AnPal_PalLRZ12_2: 28 bytes (channel B)
        private final byte[] lrz1Data3;   // AnPal_PalLRZ1_3: 34 bytes (channel C)

        // Shared timer (channels A+B)
        private int timerAB;    // Palette_cycle_counter1, reset to 0xF
        private int counterA;   // Palette_cycle_counter0
        private int counterB;   // Palette_cycle_counters+$02

        // Independent timer (channel C)
        private int timerC;     // Palette_cycle_counters+$08, reset to 7
        private int counterC;   // Palette_cycle_counters+$04

        private boolean dirty2;
        private boolean dirty3;

        Lrz1Cycle(byte[] sharedData1, byte[] sharedData2, byte[] lrz1Data3) {
            this.sharedData1 = sharedData1;
            this.sharedData2 = sharedData2;
            this.lrz1Data3 = lrz1Data3;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            // Shared timer (channels A+B): period 16
            if (timerAB > 0) {
                timerAB--;
            } else {
                timerAB = 0xF;

                // Channel A: AnPal_PalLRZ12_1 → palette 2 colors 1-4
                int d0 = counterA;
                counterA += 8;
                if (counterA >= 0x80) {
                    counterA = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        1,
                        slice(sharedData1, d0, 8));
                dirty2 = true;

                // Channel B: AnPal_PalLRZ12_2 → palette 3 colors 1-2
                int d1 = counterB;
                counterB += 4;
                if (counterB >= 0x1C) {
                    counterB = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        1,
                        slice(sharedData2, d1, 4));
                dirty3 = true;
            }

            // Independent timer (channel C): period 8
            if (timerC > 0) {
                timerC--;
            } else {
                timerC = 7;

                // Channel C: AnPal_PalLRZ1_3 → palette 2 color 11
                int d0 = counterC;
                counterC += 2;
                if (counterC >= 0x22) {
                    counterC = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        11,
                        slice(lrz1Data3, d0, 2));
                dirty2 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }

    // ========== LRZ Act 2 Cycle ==========
    // ROM: AnPal_LRZ2 (sonic3k.asm)
    //
    // Shared timer (Palette_cycle_counter1, period 16 reset to 0xF):
    //   Channel A: AnPal_PalLRZ12_1 → palette 2 colors 1-4 (2 longwords)
    //     counter0 step +8, wraps at 0x80 (16 frames × 8 bytes = 128 bytes)
    //   Channel B: AnPal_PalLRZ12_2 → palette 3 colors 1-2 (1 longword)
    //     counters+$02 step +4, wraps at 0x1C (7 frames × 4 bytes = 28 bytes)
    //
    // Independent timer (Palette_cycle_counters+$08, period 16 reset to 0xF):
    //   Channel D: AnPal_PalLRZ2_3 → palette 3 colors 11-14 (2 longwords)
    //     counters+$04 step +8, wraps at 0x100 (32 frames × 8 bytes = 256 bytes)
    //     ROM bug: writes (a0,d0.w) at both +$16 and +$1A (same 2 colors twice, not +4(a0,d0.w))
    private static class Lrz2Cycle extends PaletteCycle {
        private final byte[] sharedData1; // AnPal_PalLRZ12_1: 128 bytes (channel A)
        private final byte[] sharedData2; // AnPal_PalLRZ12_2: 28 bytes (channel B)
        private final byte[] lrz2Data3;   // AnPal_PalLRZ2_3: 256 bytes (channel D)

        // Shared timer (channels A+B)
        private int timerAB;    // Palette_cycle_counter1, reset to 0xF
        private int counterA;   // Palette_cycle_counter0
        private int counterB;   // Palette_cycle_counters+$02

        // Independent timer (channel D)
        private int timerD;     // Palette_cycle_counters+$08, reset to 0xF
        private int counterD;   // Palette_cycle_counters+$04

        private boolean dirty2;
        private boolean dirty3;

        Lrz2Cycle(byte[] sharedData1, byte[] sharedData2, byte[] lrz2Data3) {
            this.sharedData1 = sharedData1;
            this.sharedData2 = sharedData2;
            this.lrz2Data3 = lrz2Data3;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            // Shared timer (channels A+B): period 16
            if (timerAB > 0) {
                timerAB--;
            } else {
                timerAB = 0xF;

                // Channel A: AnPal_PalLRZ12_1 → palette 2 colors 1-4
                int d0 = counterA;
                counterA += 8;
                if (counterA >= 0x80) {
                    counterA = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        1,
                        slice(sharedData1, d0, 8));
                dirty2 = true;

                // Channel B: AnPal_PalLRZ12_2 → palette 3 colors 1-2
                int d1 = counterB;
                counterB += 4;
                if (counterB >= 0x1C) {
                    counterB = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        1,
                        slice(sharedData2, d1, 4));
                dirty3 = true;
            }

            // Independent timer (channel D): period 16
            if (timerD > 0) {
                timerD--;
            } else {
                timerD = 0xF;

                // Channel D: AnPal_PalLRZ2_3 → palette 3 colors 11-14
                // ROM bug: the second move.l uses (a0,d0.w) again instead of 4(a0,d0.w),
                // so colors 13+14 receive the same values as colors 11+12.
                int d0 = counterD;
                counterD += 8;
                if (counterD >= 0x100) {
                    counterD = 0;
                }
                byte[] duplicateColors = slice(lrz2Data3, d0, 4);
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        11,
                        duplicateColors);
                // ROM bug: same data again (d0, not d0+4) for colors 13+14
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.LRZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        13,
                        duplicateColors);
                dirty3 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }

    // ========== BPZ Cycle ==========
    // ROM: AnPal_BPZ (sonic3k.asm lines 3712-3743)
    //
    // Channel 1 (Balloons): timer period 8 (reset to 7), counter0 step +6, wrap at 0x12
    //   AnPal_PalBPZ_1 → palette 2, colors 13-15 (longword + word = 3 colors)
    //   Normal_palette_line_3+$1A → getPalette(2), color 13
    //
    // Channel 2 (Background): independent timer period 18 (reset to 0x11),
    //   counters+$08 step +6, wrap at 0x7E
    //   AnPal_PalBPZ_2 → palette 3, colors 2-4 (longword + word = 3 colors)
    //   Normal_palette_line_4+$04 → getPalette(3), color 2
    private static class BpzCycle extends PaletteCycle {
        private final byte[] balloonsData; // 18 bytes: 3 frames x 6 bytes
        private final byte[] bgData;       // 126 bytes: 21 frames x 6 bytes

        // Channel 1 state (Palette_cycle_counter1 / Palette_cycle_counter0)
        private int timer1;
        private int counter0;

        // Channel 2 state (Palette_cycle_counters+$08 / Palette_cycle_counters+$02)
        private int timer2;
        private int counter2;

        private boolean dirty2;
        private boolean dirty3;

        BpzCycle(byte[] balloonsData, byte[] bgData) {
            this.balloonsData = balloonsData;
            this.bgData = bgData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            // Channel 1: Balloons → palette 2, colors 13-15
            if (timer1 > 0) {
                timer1--;
            } else {
                timer1 = 7;
                int d0 = counter0;
                counter0 += 6;
                if (counter0 >= 0x12) {
                    counter0 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.BPZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        13,
                        slice(balloonsData, d0, 6));
                dirty2 = true;
            }

            // Channel 2: Background → palette 3, colors 2-4
            if (timer2 > 0) {
                timer2--;
            } else {
                timer2 = 0x11;
                int d0 = counter2;
                counter2 += 6;
                if (counter2 >= 0x7E) {
                    counter2 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.BPZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        2,
                        slice(bgData, d0, 6));
                dirty3 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }

    // ========== CGZ Light Animation Cycle ==========
    // ROM: AnPal_CGZ (shared routine for all acts, timer period 10)
    // AnPal_PalCGZ → palette 2 colors 2-5 (10 frames, counter0 step +8, wraps at 0x50)
    // Normal_palette_line_3+$04 = palette[2] color 2 (line 3 = index 2, +$04 = 2 words = color 2)
    private static class CgzCycle extends PaletteCycle {
        private final byte[] cgzData; // 80 bytes: 10 frames × 8 bytes
        private int timer;
        private int counter0;
        private boolean dirty;

        CgzCycle(byte[] cgzData) {
            this.cgzData = cgzData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            if (timer > 0) {
                timer--;
            } else {
                timer = 9;
                int d0 = counter0;
                counter0 += 8;
                if (counter0 >= 0x50) {
                    counter0 = 0;
                }
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.CGZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        2,
                        slice(cgzData, d0, 8));
                dirty = true;
            }

            if (dirty) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty = false;
            }
        }
    }

    // ========== EMZ Palette Cycle ==========
    // ROM: AnPal_EMZ (two independent channels)
    //
    // Channel 1 — Emerald glow (timer period 8, reset to 7):
    //   AnPal_PalEMZ_1 → palette 2 color 14 (Normal_palette_line_3+$1C)
    //   Counter0: step +2, wrap at 0x3C (30 frames). ROM uses 4(a0,d0.w) offset.
    //
    // Channel 2 — Background (timer period 32, reset to 0x1F, Palette_cycle_counters+$08):
    //   AnPal_PalEMZ_2 → palette 3 colors 9-10 (Normal_palette_line_4+$12, move.l = 2 colors)
    //   Counter (counters+$02): step +4, wrap at 0x34 (13 frames).
    private static class EmzCycle extends PaletteCycle {
        private final byte[] glowData;  // AnPal_PalEMZ_1: 64 bytes (covers 4(a0,d0) range)
        private final byte[] bgData;    // AnPal_PalEMZ_2: 52 bytes (13 frames x 4 bytes)

        private int glowTimer;   // Palette_cycle_counter1
        private int glowCounter; // Palette_cycle_counter0 (step +2, wrap at 0x3C)
        private int bgTimer;     // Palette_cycle_counters+$08
        private int bgCounter;   // Palette_cycle_counters+$02 (step +4, wrap at 0x34)
        private boolean dirty2;
        private boolean dirty3;

        EmzCycle(byte[] glowData, byte[] bgData) {
            this.glowData = glowData;
            this.bgData = bgData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            // Channel 1: emerald glow → palette 2 color 14
            if (glowTimer > 0) {
                glowTimer--;
            } else {
                glowTimer = 7;
                int d0 = glowCounter;
                glowCounter += 2;
                if (glowCounter >= 0x3C) {
                    glowCounter = 0;
                }
                // ROM: move.w 4(a0,d0.w) — skip 4 bytes from table base
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.EMZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        14,
                        slice(glowData, 4 + d0, 2));
                dirty2 = true;
            }

            // Channel 2: background → palette 3 colors 9-10
            if (bgTimer > 0) {
                bgTimer--;
            } else {
                bgTimer = 0x1F;
                int d0 = bgCounter;
                bgCounter += 4;
                if (bgCounter >= 0x34) {
                    bgCounter = 0;
                }
                // ROM: move.l (a0,d0.w) — write 2 colors at once
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.EMZ_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        9,
                        slice(bgData, d0, 4));
                dirty3 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }

    private static class SlotsCycle extends PaletteCycle {
        private static final byte[] FIXED_IDLE_COLOR = new byte[]{0x0E, 0x02};

        private final byte[] idleData;
        private final byte[] captureData;
        private final byte[] accentData;
        private int idleTimer;
        private int idleOffset;
        private int captureTimer;
        private int captureOffset;
        private int accentOffset;
        private boolean dirty2;
        private boolean dirty3;

        SlotsCycle(byte[] idleData, byte[] captureData, byte[] accentData) {
            this.idleData = idleData;
            this.captureData = captureData;
            this.accentData = accentData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            int mode = resolveMode();
            if (mode < 0) {
                return;
            }
            if (mode == 0) {
                tickIdle(level, registry, gm);
            } else {
                tickCapture(level, registry, gm);
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }

        private int resolveMode() {
            return resolveSlotsModeFromSession();
        }

        private void tickIdle(Level level, PaletteOwnershipRegistry registry, GraphicsManager gm) {
            if (idleTimer > 0) {
                idleTimer--;
                return;
            }
            idleTimer = 3;

            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    10,
                    slice(idleData, idleOffset, 8));
            idleOffset += 8;
            if (idleOffset >= 0x40) {
                idleOffset = 0;
            }
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    14,
                    FIXED_IDLE_COLOR);
            // ROM AnPal_Slots mirrors only the shared accent into line 4.
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    3,
                    14,
                    FIXED_IDLE_COLOR);
            dirty2 = true;
            dirty3 = true;
        }

        private void tickCapture(Level level, PaletteOwnershipRegistry registry, GraphicsManager gm) {
            if (captureTimer > 0) {
                captureTimer--;
                return;
            }
            captureTimer = 0;

            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    10,
                    slice(captureData, captureOffset, 8));
            captureOffset += 8;
            if (captureOffset >= 0x78) {
                captureOffset = 0;
            }
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    2,
                    14,
                    slice(accentData, accentOffset, 2));
            S3kPaletteWriteSupport.applyContiguousPatch(
                    registry,
                    level,
                    gm,
                    S3kPaletteOwners.SLOTS_ZONE_CYCLE,
                    S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                    3,
                    14,
                    slice(accentData, accentOffset, 2));
            accentOffset += 2;
            if (accentOffset >= 0x0C) {
                accentOffset = 0;
            }
            dirty2 = true;
            dirty3 = true;
        }
    }

    private static int resolveSlotsModeFromSession() {
        if (GameServices.module().getBonusStageProvider() instanceof Sonic3kBonusStageCoordinator coordinator
                && coordinator.activeSlotRuntime() != null) {
            return resolveSlotsModeForTest(coordinator.activeSlotRuntime());
        }
        return 0;
    }

    private static class PachinkoCycle extends PaletteCycle {
        private static final int[] LINE3_OFFSETS = {
                0x50, 0x52, 0x54, 0x56, 0x58,
                0x28, 0x2A, 0x2C, 0x2E, 0x30,
                0x00, 0x02, 0x04, 0x06, 0x08
        };

        private final byte[] tableData;
        private int line4Timer;
        private int line4Offset;
        private int line3Timer;
        private int line3Offset;
        private boolean dirty2;
        private boolean dirty3;

        private PachinkoCycle(byte[] tableData) {
            this.tableData = tableData;
        }

        @Override
        void tick(Level level, PaletteOwnershipRegistry registry) {
            GraphicsManager gm = GameServices.graphics();
            if (line4Timer > 0) {
                line4Timer--;
            } else {
                S3kPaletteWriteSupport.applyContiguousPatch(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.PACHINKO_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        3,
                        8,
                        slice(tableData, line4Offset, 14));
                line4Offset += 0x0E;
                if (line4Offset >= 0x0FC) {
                    line4Offset = 0;
                }
                dirty3 = true;
            }

            if (line3Timer > 0) {
                line3Timer--;
            } else {
                line3Timer = 3;
                int baseOffset = 0x0FC + line3Offset;
                int[] colorIndices = new int[LINE3_OFFSETS.length];
                int[] segaWords = new int[LINE3_OFFSETS.length];
                for (int i = 0; i < LINE3_OFFSETS.length; i++) {
                    colorIndices[i] = 1 + i;
                    segaWords[i] = segaWord(tableData, baseOffset + LINE3_OFFSETS[i]);
                }
                S3kPaletteWriteSupport.applyColors(
                        registry,
                        level,
                        gm,
                        S3kPaletteOwners.PACHINKO_ZONE_CYCLE,
                        S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                        2,
                        colorIndices,
                        segaWords);
                line3Offset += 0x0A;
                if (line3Offset >= 0x3E8) {
                    line3Offset = 0;
                }
                dirty2 = true;
            }

            if (dirty2) {
                cacheFallbackPaletteTexture(registry, gm, level, 2);
                dirty2 = false;
            }
            if (dirty3) {
                cacheFallbackPaletteTexture(registry, gm, level, 3);
                dirty3 = false;
            }
        }
    }
}
