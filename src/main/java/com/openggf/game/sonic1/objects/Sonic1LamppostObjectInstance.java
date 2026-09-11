package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.audio.GameSound;
import com.openggf.game.RespawnState;
import com.openggf.game.CheckpointState;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Lamppost / Checkpoint (Object 0x79).
 * <p>
 * From docs/s1disasm/_incObj/79 Lamppost.asm:
 * <ul>
 *   <li>Routine 0 (Lamp_Main): Init - sets frame based on visited state</li>
 *   <li>Routine 2 (Lamp_Blue): Active - checks for player collision</li>
 *   <li>Routine 4 (Lamp_Finish): Terminal - does nothing</li>
 * </ul>
 * <p>
 * Mapping frames (Map_Lamp_internal):
 * <ul>
 *   <li>Frame 0 (.blue): Pole + blue ball (inactive)</li>
 *   <li>Frame 1 (.poleonly): Pole only (ball removed during twirl)</li>
 *   <li>Frame 2 (.redballonly): Red ball only (twirl sparkle child)</li>
 *   <li>Frame 3 (.red): Pole + red ball (visited)</li>
 * </ul>
 */
public class Sonic1LamppostObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1LamppostObjectInstance.class.getName());

    // Mapping frame indices from Map_Lamp_internal
    private static final int FRAME_BLUE = 0;
    private static final int FRAME_POLE_ONLY = 1;
    private static final int FRAME_RED = 3;

    // Activation zone dimensions from disassembly:
    // X: player.X - lamp.X + 8 < $10 => abs(dx) < 8
    // Y: player.Y - lamp.Y + $40 < $68 => dy in [-$40, $28)
    private static final int ACTIVATION_HALF_WIDTH = 8; // addq.w #8,d0; cmpi.w #$10,d0
    private static final int ACTIVATION_Y_OFFSET = 0x40; // addi.w #$40,d0
    private static final int ACTIVATION_Y_RANGE = 0x68; // cmpi.w #$68,d0

    // Twirl child Y offset from lamppost center
    // From disassembly: subi.w #$18,lamp_origY(a1)
    static final int TWIRL_Y_OFFSET = 0x18;

    private int checkpointIndex;
    private boolean cameraLockFlag;
    private int mappingFrame;
    private boolean activated;
    private boolean twirlActive;
    private boolean initialized;

    public Sonic1LamppostObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Lamppost");
        // obSubtype bits 0-6 = lamppost number, bit 7 = camera lock flag
        this.checkpointIndex = spawn.subtype() & 0x7F;
        this.cameraLockFlag = (spawn.subtype() & 0x80) != 0;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Lamp_Main: check if already visited
        var checkpointState = services().checkpointState();
        if (checkpointState != null && checkpointState.getLastCheckpointIndex() >= this.checkpointIndex) {
            // Already visited - show red, go to Lamp_Finish
            this.activated = true;
            this.mappingFrame = FRAME_RED;
        } else {
            // New lamppost - show blue, go to Lamp_Blue
            this.activated = false;
            this.mappingFrame = FRAME_BLUE;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (activated || player == null) {
            return; // Lamp_Finish: rts
        }
        checkActivation(player);
    }

    /**
     * Lamp_Blue routine: checks if player is within activation zone.
     * Also handles the case where a higher checkpoint was activated since creation.
     */
    private void checkActivation(AbstractPlayableSprite player) {
        // Check if a higher/equal checkpoint was activated since we spawned
        var checkpointState = services().checkpointState();
        if (checkpointState == null) {
            return;
        }
        if (checkpointState.getLastCheckpointIndex() >= this.checkpointIndex) {
            // Another checkpoint was hit - mark as visited (red)
            activated = true;
            mappingFrame = FRAME_RED;
            return;
        }

        // Collision check using center coordinates (matches ROM behavior)
        int px = player.getCentreX();
        int py = player.getCentreY();
        int cx = spawn.x();
        int cy = spawn.y();

        // ROM: move.w (v_player+obX).w,d0; sub.w obX(a0),d0; addq.w #8,d0; cmpi.w #$10,d0
        int dx = px - cx;
        if (dx + ACTIVATION_HALF_WIDTH < 0 || dx + ACTIVATION_HALF_WIDTH >= 16) {
            return;
        }

        // ROM: move.w (v_player+obY).w,d0; sub.w obY(a0),d0; addi.w #$40,d0; cmpi.w #$68,d0
        int dy = py - cy;
        if (dy + ACTIVATION_Y_OFFSET < 0 || dy + ACTIVATION_Y_OFFSET >= ACTIVATION_Y_RANGE) {
            return;
        }

        // Activate!
        activate(player, checkpointState);
    }

    private void activate(AbstractPlayableSprite player,
                          RespawnState respawnState) {
        activated = true;

        // Play lamppost sound: move.w #sfx_Lamppost,d0; jsr (QueueSound2).l
        try {
            services().playSfx(GameSound.CHECKPOINT);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // Spawn twirl child: jsr (FindFreeObj).l
        if (services().objectManager() != null) {
            twirlActive = true;
            spawnFreeChild(() -> new Sonic1LamppostTwirlInstance(this));
        }

        // Set frame to pole only: move.b #1,obFrame(a0)
        mappingFrame = FRAME_POLE_ONLY;

        // Save checkpoint state: bsr.w Lamp_StoreInfo
        if (respawnState instanceof CheckpointState cs) {
            cs.saveCheckpoint(checkpointIndex, spawn.x(), spawn.y(), cameraLockFlag);

            // ROM Lamp_StoreInfo also saves water state (v_waterpos2, v_wtr_routine)
            WaterSystem waterSystem = services().waterSystem();
            int featureZone = services().featureZoneId();
            int featureAct = services().featureActId();
            if (waterSystem.hasWater(featureZone, featureAct)) {
                int waterLevel = waterSystem.getWaterLevelY(featureZone, featureAct);
                ZoneFeatureProvider zfp = services().zoneFeatureProvider();
                int waterRoutine = zfp != null ? zfp.getWaterRoutine() : 0;
                cs.saveWaterState(waterLevel, waterRoutine);
            }
        }

        LOGGER.fine("S1 Lamppost " + checkpointIndex + " activated at (" + spawn.x() + ", " + spawn.y() + ")");
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #5,obPriority(a0)
        return RenderPriority.clamp(5);
    }

    /**
     * Called by the twirl child when its animation completes.
     * <p>
     * ROM Lamp_Finish (79 Lamppost.asm:122-123, routine 4) is a plain {@code rts}
     * -- the pole object's own mapping frame is never touched again this act.
     * Lamp_Finish itself does not delete the twirl child either. That does
     * <b>not</b> mean the twirl (or the pole) persists forever, though: every
     * routine dispatched through {@code Lamppost:} -- pole and twirl alike --
     * falls through to {@code jmp (RememberState).l} (79 Lamppost.asm:6-11),
     * and {@code RememberState} (sub RememberState.asm:8-10) runs the standard
     * ROM {@code out_of_range} check and deletes the object once it scrolls
     * off-screen, exactly like any other object. So both the pole and its
     * twirl child are deleted together once the camera moves far enough away,
     * matching the engine's generic per-frame out-of-range eviction.
     * <p>
     * A previous version of this method switched the pole to {@code FRAME_RED}
     * here, on the theory that the engine "destroys the twirl immediately" --
     * it doesn't (see {@link Sonic1LamppostTwirlInstance#update}, which freezes
     * rather than self-destructing once its orbit completes). With both objects
     * alive, that fabricated frame swap produced a second, exactly-centered ball
     * stacked next to the twirl's own frozen resting ball -- the "lamppost head
     * sometimes stops duplicated and X-offset" bug. Only clear the bookkeeping
     * flag; leave the pole's mapping frame at {@code FRAME_POLE_ONLY}, matching
     * ROM.
     */
    public void onTwirlComplete() {
        twirlActive = false;
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }
}
