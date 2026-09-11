package com.openggf.game.sonic3k;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

/**
 * S3K-specific bonus stage coordinator.
 * Provides zone ID and music ID mapping for Gumball, Pachinko, and Slots.
 *
 * <p>Ring-based selection formula: {@code remainder = ((rings - 20) / 15) % 3}
 * <p>ROM loc_2D47E (sonic3k.asm lines 61886-61912):
 * <ul>
 *   <li>0 -> SLOT_MACHINE (zone $1500, music $1D)</li>
 *   <li>1 -> GLOWING_SPHERE / Pachinko (zone $1400, music $1B)</li>
 *   <li>2 -> GUMBALL (zone $1300, music $1E)</li>
 * </ul>
 * <p>Concrete ring ranges: 20–34 rings → SLOT_MACHINE, 35–49 rings → GLOWING_SPHERE, 50–64 rings → GUMBALL.
 * <p>
 * Note: SK_alone_flag handling is not implemented. The only supported S3K ROM
 * is the combined "Sonic and Knuckles &amp; Sonic 3 (W) [!].gen" which always sets
 * SK_alone_flag=0 (3 bonus stages available). For S&amp;K standalone ROMs, the
 * divisor would be 2 and remainder=2 branch would route to Pachinko instead
 * of Gumball (ROM loc_2D47E lines 61897, 61910-61912).
 */
public class Sonic3kBonusStageCoordinator extends AbstractBonusStageCoordinator {

    private static final int ZONE_GUMBALL  = 0x1300;
    private static final int ZONE_PACHINKO = 0x1400;
    private static final int ZONE_SLOTS    = 0x1500;

    private static final int MUS_GUMBALL  = 0x1E;
    private static final int MUS_PACHINKO = 0x1B;
    private static final int MUS_SLOTS    = 0x1D;

    private static final int RING_THRESHOLD = 20;
    private static final int RING_DIVISOR   = 15;
    private static final int STAGE_COUNT    = 3;

    private static final BonusStageProvider.BootstrapObject PACHINKO_BOOTSTRAP =
            new BonusStageProvider.BootstrapObject(
                    new ObjectSpawn(0x78, 0x0F30,
                            Sonic3kObjectIds.PACHINKO_ENERGY_TRAP,
                            0, 0, false, 0),
                    PachinkoEnergyTrapObjectInstance.class,
                    PachinkoEnergyTrapObjectInstance::new);

    private S3kSlotBonusStageRuntime slotRuntime;

    @Override
    public BonusStageProvider.BootstrapObject bootstrapObject(BonusStageType type) {
        return type == BonusStageType.GLOWING_SPHERE ? PACHINKO_BOOTSTRAP : null;
    }

    @Override
    public BonusStageType selectBonusStage(int ringCount) {
        if (ringCount < RING_THRESHOLD) return BonusStageType.NONE;
        int remainder = ((ringCount - RING_THRESHOLD) / RING_DIVISOR) % STAGE_COUNT;
        return switch (remainder) {
            case 0 -> BonusStageType.SLOT_MACHINE;
            case 1 -> BonusStageType.GLOWING_SPHERE;
            case 2 -> BonusStageType.GUMBALL;
            default -> BonusStageType.NONE;
        };
    }

    @Override
    public int getZoneId(BonusStageType type) {
        return switch (type) {
            case GUMBALL -> ZONE_GUMBALL;
            case GLOWING_SPHERE -> ZONE_PACHINKO;
            case SLOT_MACHINE -> ZONE_SLOTS;
            default -> -1;
        };
    }

    @Override
    public int getMusicId(BonusStageType type) {
        return switch (type) {
            case GUMBALL -> MUS_GUMBALL;
            case GLOWING_SPHERE -> MUS_PACHINKO;
            case SLOT_MACHINE -> MUS_SLOTS;
            default -> -1;
        };
    }

    @Override
    public void onDeferredSetupComplete() {
        if (getActiveType() != BonusStageType.SLOT_MACHINE) {
            return;
        }
        slotRuntime = new S3kSlotBonusStageRuntime();
        slotRuntime.bootstrap();
        if (!slotRuntime.isInitialized()) {
            slotRuntime = null;
            return;
        }
    }

    @Override
    public void onFrameUpdate() {
        if (slotRuntime != null) {
            slotRuntime.update(romLevelFrameCounter());
            if (slotRuntime.isExitTriggered()) {
                requestExit();
            }
        }
    }

    @Override
    public boolean updateDuringLevelFrame() {
        return slotRuntime != null;
    }

    @Override
    public boolean suppressesDefaultCameraStep() {
        return slotRuntime != null;
    }

    @Override
    public boolean hasCompletedExitFadeToBlack() {
        return slotRuntime != null && slotRuntime.hasCompletedExitFadeToBlack();
    }

    @Override
    public void onExit() {
        if (slotRuntime != null) {
            slotRuntime.shutdown();
            slotRuntime = null;
        }
        super.onExit();
    }

    /**
     * The ROM's object-visible {@code Level_frame_counter}.
     *
     * <p>Every S3K/S2/S1 main loop increments it immediately after
     * {@code Wait_VSync} and BEFORE the object pass -- for the bonus/special
     * stages see {@code sonic3k.asm:10742-10744} and {@code :63207-63209}, where
     * {@code addq.w #1,(Level_frame_counter).w} sits between {@code Wait_VSync}
     * and {@code Process_Sprites} -- so an object running this frame reads the
     * already-incremented value. The engine advances its own counter at the same
     * point, in {@code LevelFrameStep} via
     * {@code LevelManager.advanceLevelFrameCounter()}, so every ported
     * {@code Level_frame_counter} gate reads {@code getFrameCounter()} with no
     * adjustment.
     *
     * <p>This replaces a free-running counter that was seeded from the level
     * counter once at stage setup and then self-incremented. It read one below
     * the ROM's object-visible value, so the cage's reward-spawn gate
     * {@code btst #0,(Level_frame_counter+1).w} ({@code sonic3k.asm:99435},
     * {@code :99417}) fired on the ROM's EVEN frames instead of its odd ones.
     */
    private int romLevelFrameCounter() {
        var levelManager = GameServices.levelOrNull();
        return levelManager != null ? levelManager.getFrameCounter() : 0;
    }

    public S3kSlotBonusStageRuntime activeSlotRuntime() {
        return slotRuntime;
    }

    public S3kSlotBonusStageRuntime activeSlotRuntimeForTest() {
        return activeSlotRuntime();
    }
}
