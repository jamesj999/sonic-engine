package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * Minimal CNZ miniboss scroll-control helper for Task 7.
 *
 * <p>ROM anchor: {@code Obj_CNZMinibossScrollControl}.
 *
 * <p>The disassembly uses two fixed-point words:
 * {@code Events_bg+$08} as the accumulated vertical boss-scroll offset and
 * {@code Events_bg+$0C} as the current 16.16 speed. The helper accelerates,
 * then after the boss-defeat flag arrives it waits until the accumulator reaches
 * {@code $1C0} before setting {@code Events_fg_5}. Task 7 preserves exactly
 * that producer responsibility while deliberately omitting the earlier init and
 * layout-copy routines that are outside the current test scope.
 */
public final class CnzMinibossScrollControlInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    /**
     * ROM: {@code addi.l #$200,d0} in {@code Obj_CNZMinibossScrollMain}.
     */
    private static final int ACCELERATION_STEP_16_16 = 0x200;

    /**
     * ROM: {@code cmpi.l #$40000,d0}.
     */
    private static final int MAX_SPEED_16_16 = 0x40000;
    private static final int SLOW_SPEED_16_16 = 0x10000;
    private static final int DECELERATION_STEP_16_16 = 0x400;

    /**
     * ROM: {@code cmpi.w #$1C0,(Events_bg+$08).w} in
     * {@code Obj_CNZMinibossScrollWait3}.
     */
    private static final int FG_HANDOFF_OFFSET_PIXELS = 0x1C0;
    private static final int CHUNK_SIZE_PIXELS = 0x80;
    private static final int INIT_FG_SOURCE_X = 0x3180 / CHUNK_SIZE_PIXELS;
    private static final int INIT_FG_SOURCE_Y = 0x0280 / CHUNK_SIZE_PIXELS;
    private static final int INIT_FG_DEST_Y = INIT_FG_SOURCE_Y - 1;
    private static final int INIT_BG_SOURCE_X = 4;
    private static final int INIT_BG_SOURCE_Y = 0;
    private static final int INIT_BG_FIRST_DEST_Y = 1;
    private static final int INIT_BG_DEST_ROWS = 2;
    private static final int POST_BOSS_FG_CLEAR_X = 0x3380 / CHUNK_SIZE_PIXELS;
    private static final int POST_BOSS_FG_CLEAR_ROWS = 7;
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_MAIN = 4;
    private static final int ROUTINE_WAIT_ALIGN = 8;
    private static final int ROUTINE_SLOW = 0x0C;
    private static final int ROUTINE_WAIT_ALIGN_2 = 0x10;
    private static final int ROUTINE_WAIT_FINAL = 0x14;

    private int currentVelocity16_16;
    private int accumulatedOffset16_16;
    private boolean bossDefeatSignalConsumed;
    private boolean initialLayoutMutated;
    private boolean postBossLayoutMutated;
    private int routine = ROUTINE_INIT;

    public CnzMinibossScrollControlInstance(ObjectSpawn spawn) {
        super(spawn, "CNZMinibossScrollControl");
    }

    /**
     * Test seam that injects the post-defeat phase transition the ROM normally
     * receives from {@code Events_fg_5}.
     */
    public void forceBossDefeatSignalForTest() {
        bossDefeatSignalConsumed = true;
        if (currentVelocity16_16 <= 0) {
            currentVelocity16_16 = SLOW_SPEED_16_16;
        }
    }

    /**
     * Test seam for the 16.16 accumulated scroll offset.
     */
    public void forceAccumulatedOffsetForTest(int accumulatedOffset16_16) {
        this.accumulatedOffset16_16 = accumulatedOffset16_16;
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "r=%02X off=%08X/%04X vel=%08X defeat=%s init=%s post=%s",
                routine & 0xFF,
                accumulatedOffset16_16,
                (accumulatedOffset16_16 >> 16) & 0xFFFF,
                currentVelocity16_16,
                bossDefeatSignalConsumed,
                initialLayoutMutated,
                postBossLayoutMutated);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!bossDefeatSignalConsumed
                && S3kCnzEventWriteSupport.consumeMinibossDefeatSignalForScrollControl(services())) {
            bossDefeatSignalConsumed = true;
        }

        switch (routine) {
            case ROUTINE_INIT -> {
                mutateInitialTunnelLayoutOnce();
                routine = ROUTINE_MAIN;
                updateMain();
            }
            case ROUTINE_MAIN -> updateMain();
            case ROUTINE_WAIT_ALIGN -> updateWaitAlign(ROUTINE_SLOW);
            case ROUTINE_SLOW -> updateSlow();
            case ROUTINE_WAIT_ALIGN_2 -> updateWaitAlign2();
            case ROUTINE_WAIT_FINAL -> updateWaitFinal();
            default -> publishScrollState();
        }
    }

    private void updateMain() {
        if (bossDefeatSignalConsumed) {
            // ROM: loc_52042 falls through into Obj_CNZMinibossScrollWait after
            // clearing Events_fg_5; it does not skip directly to the final handoff
            // based on the current Events_bg+$08 offset (sonic3k.asm:107770-107795).
            routine = ROUTINE_WAIT_ALIGN;
            updateWaitAlign(ROUTINE_SLOW);
            return;
        }
        currentVelocity16_16 = Math.min(currentVelocity16_16 + ACCELERATION_STEP_16_16,
                MAX_SPEED_16_16);
        accumulatedOffset16_16 += currentVelocity16_16;
        publishScrollState();
    }

    private void updateWaitAlign(int nextRoutine) {
        if (currentVelocity16_16 <= 0) {
            currentVelocity16_16 = SLOW_SPEED_16_16;
        }
        if (((accumulatedOffset16_16 >> 16) & 0xFF) < 4) {
            routine = nextRoutine;
            return;
        }
        accumulatedOffset16_16 += currentVelocity16_16;
        publishScrollState();
    }

    private void updateSlow() {
        if (currentVelocity16_16 <= SLOW_SPEED_16_16) {
            routine = ROUTINE_WAIT_ALIGN_2;
            updateWaitAlign2();
            return;
        }
        currentVelocity16_16 -= DECELERATION_STEP_16_16;
        accumulatedOffset16_16 += currentVelocity16_16;
        publishScrollState();
    }

    private void updateWaitAlign2() {
        if (((accumulatedOffset16_16 >> 16) & 0xFF) >= 4) {
            accumulatedOffset16_16 += currentVelocity16_16;
            publishScrollState();
            return;
        }
        accumulatedOffset16_16 = ((accumulatedOffset16_16 >> 16) & 0xFF) << 16;
        services().camera().setMaxYTarget((short) 0x1000);
        services().gameState().setBackgroundCollisionFlag(true);
        S3kCnzEventWriteSupport.advanceMinibossBackgroundRoutineAfterScrollSnap(services());
        mutatePostBossTunnelLayoutOnce();
        routine = ROUTINE_WAIT_FINAL;
        publishScrollState();
    }

    private void updateWaitFinal() {
        if (currentVelocity16_16 <= 0) {
            accumulatedOffset16_16 = FG_HANDOFF_OFFSET_PIXELS << 16;
        }
        if ((accumulatedOffset16_16 >> 16) < FG_HANDOFF_OFFSET_PIXELS) {
            accumulatedOffset16_16 += currentVelocity16_16;
            if ((accumulatedOffset16_16 >> 16) >= FG_HANDOFF_OFFSET_PIXELS) {
                completeFinalHandoff();
                return;
            }
            publishScrollState();
            return;
        }
        completeFinalHandoff();
    }

    private void completeFinalHandoff() {
        accumulatedOffset16_16 = FG_HANDOFF_OFFSET_PIXELS << 16;
        services().camera().setMaxYTarget((short) 0x1000);
        services().gameState().setBackgroundCollisionFlag(true);
        mutatePostBossTunnelLayoutOnce();
        publishScrollState();
        S3kCnzEventWriteSupport.setEventsFg5(services(), true);
        setDestroyed(true);
    }

    private void publishScrollState() {
        S3kCnzEventWriteSupport.setBossScrollState(
                services(), accumulatedOffset16_16 >> 16, currentVelocity16_16);
    }

    private void mutateInitialTunnelLayoutOnce() {
        if (initialLayoutMutated) {
            return;
        }
        initialLayoutMutated = true;
        applyLayoutMutation("CNZ miniboss initial tunnel layout", surface -> {
            int fgValue = services().currentLevel().getMap()
                    .getValue(0, INIT_FG_SOURCE_X, INIT_FG_SOURCE_Y) & 0xFF;
            surface.setBlockInMap(0, INIT_FG_SOURCE_X, INIT_FG_DEST_Y, fgValue);

            int bgValue = services().currentLevel().getMap()
                    .getValue(1, INIT_BG_SOURCE_X, INIT_BG_SOURCE_Y) & 0xFF;
            for (int row = 0; row < INIT_BG_DEST_ROWS; row++) {
                surface.setBlockInMap(1, INIT_BG_SOURCE_X,
                        INIT_BG_FIRST_DEST_Y + row, bgValue);
            }
            return MutationEffects.redrawAllTilemaps();
        });
    }

    private void mutatePostBossTunnelLayoutOnce() {
        if (postBossLayoutMutated) {
            return;
        }
        postBossLayoutMutated = true;
        applyLayoutMutation("CNZ miniboss post-boss FG reveal", surface -> {
            for (int y = 0; y < POST_BOSS_FG_CLEAR_ROWS; y++) {
                surface.setBlockInMap(0, POST_BOSS_FG_CLEAR_X, y, 0);
            }
            return MutationEffects.foregroundRedraw();
        });
    }

    private void applyLayoutMutation(String description, LayoutMutationBody body) {
        if (services().currentLevel() == null) {
            return;
        }
        LevelMutationSurface surface = LevelMutationSurface.forLevel(services().currentLevel());
        LayoutMutationContext context = new LayoutMutationContext(surface, effects -> {
            if (services().levelManager() != null) {
                services().levelManager().applyMutationEffects(effects);
            }
        });
        services().zoneLayoutMutationPipeline().applyImmediately(ctx -> body.apply(ctx.surface()), context);
    }

    @FunctionalInterface
    private interface LayoutMutationBody {
        MutationEffects apply(LevelMutationSurface surface);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Task 7 validates the event-production contract only.
    }
}
