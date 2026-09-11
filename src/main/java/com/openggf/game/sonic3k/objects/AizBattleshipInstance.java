package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.events.S3kAizEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnYCoordinateRewindRecreatable;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ2 Flying Battery battleship — crosses the screen dropping bombs.
 *
 * <p>ROM: Obj_AIZBattleship (sonic3k.asm:105257).
 * The ship moves left via a 16:16 fixed-point secondary camera X counter
 * (ROM: _unkEE98 -= $8800 per frame ≈ 0.53 px/frame).
 * A scripted bomb table drives 21 bomb drops. Bombs are positioned relative
 * to the ship's secondary camera, converted to world coordinates via:
 * {@code world_x = bomb_script_x - shipX + camera_x}.
 *
 * <p>The ship starts with an initial counter of $1A4, but like the ROM it waits
 * for the counter to underflow before consuming the first script entry. That
 * makes the first bomb appear on update 421 after spawn, and each entry's delay
 * governs the gap before the following bomb.
 * When the ship crosses below $3CDC, spawns {@link AizBossSmallInstance}.
 * Every 16 frames plays {@code cfx_LargeShip} SFX.
 */
public class AizBattleshipInstance extends AbstractObjectInstance implements SpawnYCoordinateRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizBattleshipInstance.class.getName());

    /** ROM: Battleship bomb script — {delay, bombX} pairs in secondary camera space. */
    private static final int[][] BOMB_SCRIPT = {
            {0x20, 0x3F5C}, {0x20, 0x3F2C}, {0x20, 0x3F5C}, {0x20, 0x3F2C}, {0x20, 0x3F5C}, {0x38, 0x3F2C},
            {0x20, 0x3EDC}, {0x20, 0x3EAC}, {0x20, 0x3EDC}, {0x20, 0x3EAC}, {0x20, 0x3EDC}, {0x38, 0x3EAC},
            {0x20, 0x3E5C}, {0x20, 0x3E2C}, {0x20, 0x3E5C}, {0x20, 0x3E2C}, {0x20, 0x3E5C}, {0x38, 0x3E2C},
            {0x40, 0x3DEC}, {0x40, 0x3DEC}, {0x40, 0x3DEC},
    };

    /** ROM: Ship X threshold to transition to the small boss craft. */
    private static final int SHIP_EXIT_X = 0x3CDC;
    /** ROM: once ship X passes below $3D5C, the secondary BG camera rises upward. */
    private static final int SHIP_RISE_TRIGGER_X = 0x3D5C;

    /** ROM: Initial secondary camera X (ship starts here). */
    private static final int INITIAL_SHIP_X = 0x4020;

    /** ROM: Ship movement speed in 16:16 fixed-point ($8800 per frame ≈ 0.53 px/frame). */
    private static final int SHIP_SPEED = 0x8800;

    /** ROM: Initial delay counter before the first underflow ($1A4 -> first bomb on update 421). */
    private static final int INITIAL_DELAY = 0x1A4;

    /** ROM: bomb source Y in secondary BG camera space. */
    private static final int BOMB_SOURCE_Y = 0x0A60;
    /** ROM: 16-step secondary-camera bobbing motion while the ship is on-screen. */
    private static final int[] BOBBING_MOTION = {
            4, 4, 3, 3, 2, 1, 1, 0, 0, 0, 1, 1, 2, 3, 3, 4
    };

    // 16:16 fixed-point ship position (integer part = upper 16 bits)
    private int shipXFixed;
    /** False until the object pass that created the ship has gone by unused. */
    private boolean creationPassConsumed;
    // Non-final so the generic rewind field capturer reapplies it after
    // Phase-2 generic recreate.
    private int baseSecondaryY;
    private int effectiveSecondaryY;
    private int scriptIndex;
    private int delayTimer;
    private int frameCounter;
    private boolean finished;
    private boolean bombingStarted;

    public AizBattleshipInstance(ObjectSpawn spawn, int baseSecondaryY) {
        super(spawn, "AIZBattleship");
        this.shipXFixed = INITIAL_SHIP_X << 16;
        this.baseSecondaryY = baseSecondaryY;
        this.effectiveSecondaryY = baseSecondaryY;
        this.scriptIndex = 0;
        this.delayTimer = INITIAL_DELAY;
        this.frameCounter = 0;
        this.finished = false;
        this.bombingStarted = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed() || finished) return;

        if (!creationPassConsumed) {
            // ROM: AIZ2SE_ShipRefresh sets the secondary camera X and then takes
            // a slot with plain AllocateObject (sonic3k.asm:104917-104928), NOT
            // AllocateObjectAfterCurrent -- so the slot is not guaranteed to sit
            // ahead of the object pass that created it, and for this run it does
            // not: the recording shows slot 4 still holding Obj_AIZBattleship
            // (the init) at the end of its creation frame and Obj_AIZBattleshipMain
            // only on the frame after. The init writes the Main pointer before
            // falling through, so a pointer still reading init means the slot was
            // allocated without being executed.
            //
            // The ship's first real pass -- init falling through into
            // Obj_AIZBattleshipMain and its opening `subi.l #$8800,(_unkEE98).w`
            // (:105261-105286) -- therefore happens one frame after creation.
            // Advancing on the creation frame left the secondary camera one step
            // ahead in its fraction for the rest of the act.
            creationPassConsumed = true;
            return;
        }

        this.frameCounter++;

        // Move ship left (16:16 fixed-point)
        shipXFixed -= SHIP_SPEED;
        int shipX = shipXFixed >> 16;

        // ROM parity: exit check happens BEFORE SFX replay and bobbing.
        // When the ship crosses $3CDC, it transitions to Obj_AIZ2BossSmall
        // immediately — the continuous SFX stops being re-triggered and
        // naturally fades out via the cfLoopContinuousSFX fallthrough.
        if (shipX < SHIP_EXIT_X) {
            onShipExited();
            return;
        }

        updateSecondaryCameraY(shipX);

        // ROM: replay the large ship SFX every 16th frame.
        if (((this.frameCounter - 1) & 0xF) == 0) {
            services().playSfx(Sonic3kSfx.LARGE_SHIP.id);
        }

        if (!bombDelayUnderflowed() || scriptIndex >= BOMB_SCRIPT.length) {
            return;
        }

        if (!bombingStarted) {
            bombingStarted = true;
            LOG.info("AIZ2 battleship: bombing started at frame " + this.frameCounter
                    + ", shipX=0x" + Integer.toHexString(shipX));
        }

        int currentScriptIndex = scriptIndex;
        int nextDelay = BOMB_SCRIPT[currentScriptIndex][0];

        // ROM: Translate_Camera2ObjPosition/Translate_Camera2ObjX.
        int bombScriptX = BOMB_SCRIPT[currentScriptIndex][1];
        int screenX = bombScriptX - shipX;
        int worldY = services().camera().getY() + (BOMB_SOURCE_Y - effectiveSecondaryY);

        spawnBomb(screenX, worldY, bombScriptX);
        scriptIndex = currentScriptIndex + 1;
        delayTimer = nextDelay;
    }

    private boolean bombDelayUnderflowed() {
        return --delayTimer < 0;
    }

    private void spawnBomb(int screenX, int worldY, int bombScriptX) {
        // Spawn with the translated world position for bookkeeping; the bomb itself
        // continues translating from the ship's live secondary-camera coordinates.
        int cameraX = services().camera().getX();
        // ROM Obj_AIZBattleshipMain creates bombs with AllocateObjectAfterCurrent
        // (sonic3k.asm:105315), so bomb slots must follow the ship's slot.
        spawnChild(() -> new AizShipBombInstance(
                new ObjectSpawn(cameraX + screenX, worldY, 0, 0, 0, false, 0),
                this, bombScriptX, worldY));
    }

    private void updateSecondaryCameraY(int shipX) {
        if (shipX < SHIP_RISE_TRIGGER_X) {
            int delta = SHIP_RISE_TRIGGER_X - shipX;
            effectiveSecondaryY = baseSecondaryY + (delta >> 1);
            return;
        }
        int bobIndex = (shipX >> 2) & 0xF;
        effectiveSecondaryY = baseSecondaryY + BOBBING_MOTION[bobIndex];
    }

    private void onShipExited() {
        finished = true;
        int shipX = shipXFixed >> 16;

        // Signal the event system that the battleship is complete
        S3kAizEventWriteSupport.onBattleshipComplete(services());

        LOG.info("AIZ2 battleship: ship exited at shipX=0x" + Integer.toHexString(shipX)
                + " after " + frameCounter + " frames, " + scriptIndex + " bombs dropped");
        setDestroyed(true);
    }

    @Override
    public int getX() { return shipXFixed >> 16; }

    @Override
    public int getY() { return 0; }

    public int getSecondaryCameraX() {
        return shipXFixed >> 16;
    }

    public int getSecondaryCameraY() {
        return effectiveSecondaryY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The battleship manipulates the background plane in the ROM and does not
        // render as an individual sprite. No rendering needed here.
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() { return 4; }
}
