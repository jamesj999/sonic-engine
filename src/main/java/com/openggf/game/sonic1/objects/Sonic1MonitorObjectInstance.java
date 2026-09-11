package com.openggf.game.sonic1.objects;

import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Monitor (item box) object.
 * <p>
 * Object ID 0x26. Contains power-ups awarded when broken by the player.
 * Subtypes: 0=static, 1=Eggman, 2=Sonic/1-up, 3=Speed Shoes, 4=Shield,
 * 5=Invincibility, 6=10 Rings, 7='S', 8=Goggles, 9=Broken.
 * <p>
 * Reference: docs/s1disasm/_incObj/26 Monitor.asm
 */
public class Sonic1MonitorObjectInstance extends AbstractMonitorObjectInstance
        implements SpawnRewindRecreatable, TouchResponseProvider, TouchResponseListener,
        SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(Sonic1MonitorObjectInstance.class.getName());

    // From disassembly: obHeight/obWidth = $0E
    private static final int HALF_RADIUS = 0x0E;

    // Map_Monitor frame 11 = broken shell
    private static final int BROKEN_FRAME = 0x0B;

    // From disassembly: Pow_ChkRings adds 10 rings
    private static final int RING_MONITOR_REWARD = 10;

    // From disassembly: Touch_Monitor upward pop velocity
    private static final int FALLING_INITIAL_VEL = -0x180;

    // Standard object gravity
    private static final int FALLING_GRAVITY = 0x38;

    private MonitorType type;
    private ObjectAnimationState animationState;
    private boolean broken;
    private int mappingFrame;

    // ROM Mon_BreakOpen (the monitor's own routine 4) performs the FindFreeObj
    // spawns of the power-up + explosion children — NOT ReactToItem. ReactToItem
    // (run during the player's slot-0 collision pass) only bounces the player and
    // sets the monitor's routine to 4. The break-open spawn therefore happens when
    // the monitor executes in its own (high) SST slot, so the lower-slot explosion
    // child defers its first ExecuteObjects pass to the next frame
    // (docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:181-198). The engine
    // runs touch responses BEFORE the object exec loop for S1, so spawning the
    // children directly in onTouchResponse would execute the explosion one frame
    // early (it would be swept into the same frame's rebuilt execOrder), expiring
    // it a frame ahead of ROM and freeing its slot a frame early — the S1 LZ2
    // f6418 -1 slot cascade. Deferring the spawn to update() (the monitor's own
    // slot execution) lets ObjectManager's slot-relative deferral run the child
    // next frame, matching ROM.
    private boolean pendingBreakSpawn;

    // Falling state (ob2ndRout = 4 in disassembly)
    private boolean falling;
    private int yVel;
    private int yFixed;
    private int currentY;

    public Sonic1MonitorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Monitor");
        this.type = MonitorType.fromSubtype(spawn.subtype());

        this.currentY = spawn.y();
        this.yFixed = spawn.y() << 8;
    }

    private void ensureInitialized() {
        if (animationState != null) {
            return;
        }

        // Check persistence: if previously broken, spawn as broken shell
        ObjectManager objectManager = services().objectManager();
        boolean previouslyBroken = objectManager != null && objectManager.isRemembered(spawn);
        this.broken = this.type == MonitorType.BROKEN || previouslyBroken;

        // Initialize animation: obAnim = obSubtype (from Mon_Main)
        int initialAnim = type.id;
        int initialFrame = broken ? BROKEN_FRAME : 0;
        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getMonitorAnimations() : null,
                initialAnim,
                initialFrame);
        this.mappingFrame = initialFrame;
        if (broken) {
            effectApplied = true;
        }
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM Mon_BreakOpen runs in the monitor's own SST slot (this update),
        // after ReactToItem set the break in the player's collision pass. Spawning
        // here (not in onTouchResponse) keeps the explosion/power-up children's
        // first ExecuteObjects pass deferred to next frame when they land in a
        // lower slot than the monitor — see pendingBreakSpawn field comment.
        if (pendingBreakSpawn) {
            pendingBreakSpawn = false;
            spawnBreakChildren(player);
        }
        if (falling) {
            updateFalling();
        }

        if (!broken) {
            animationState.update();
            mappingFrame = animationState.getMappingFrame();
        }
    }

    /**
     * Update falling monitor after being hit from below.
     * ROM: ObjectFall + ObjFloorDist
     */
    private void updateFalling() {
        yFixed += yVel;
        yVel += FALLING_GRAVITY;
        currentY = yFixed >> 8;

        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(spawn.x(), currentY, HALF_RADIUS);
        if (result.hasCollision()) {
            currentY = currentY + result.distance();
            yFixed = currentY << 8;
            yVel = 0;
            falling = false;
        }
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }

        // Sonic 1 never had a CPU sidekick. When cross-game sidekicks are donated into
        // S1, match the shared monitor rule used by S2/S3K and block sidekick breaks.
        if (player.isCpuControlled()) {
            return;
        }

        // Hit from below: player moving upward
        // ROM: Touch_Monitor - checks player y_pos - $10 >= monitor y_pos
        if (player.getYSpeed() < 0) {
            int playerCenterY = player.getCentreY();
            int monitorY = currentY;

            if (playerCenterY - 0x10 >= monitorY) {
                // Bounce player down: neg.w y_vel(a0)
                player.setYSpeed((short) -player.getYSpeed());

                // Make monitor pop up and fall
                if (!falling) {
                    falling = true;
                    yVel = FALLING_INITIAL_VEL;
                }
            }
            return;
        }

        // ROM: Touch_Monitor checks anim(a0) == id_Roll, not Status_Roll.
        // The two can diverge for a frame around object landings/releases.
        if (player.getAnimationId() != Sonic1AnimationIds.ROLL.id()) {
            return;
        }

        breakMonitor(player);
    }

    /**
     * Trigger the monitor break from the player's touch pass (ROM ReactToItem):
     * bounce the player and mark the monitor broken, but defer the child spawns to
     * the monitor's own {@link #update} execution (ROM Mon_BreakOpen runs in the
     * monitor's SST slot, not in ReactToItem). See {@link #pendingBreakSpawn}.
     */
    private void breakMonitor(AbstractPlayableSprite player) {
        broken = true;

        // Mark as broken in persistence table (ROM Mon_RememberBroken).
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        // Bounce player: neg.w obVelY(a0) — ROM ReactToItem.
        if (objectManager != null) {
            objectManager.solidContacts().markSameFrameMonitorBreakBounce(player);
        }
        player.setYSpeed((short) -player.getYSpeed());

        mappingFrame = BROKEN_FRAME;
        // Defer the FindFreeObj spawns to the monitor's own update (Mon_BreakOpen).
        pendingBreakSpawn = true;
    }

    /**
     * ROM Mon_BreakOpen body: allocate the power-up contents object and the
     * explosion via FindFreeObj. Runs during the monitor's own ExecuteObjects
     * slot so a child landing in a lower slot defers its first update one frame.
     */
    private void spawnBreakChildren(AbstractPlayableSprite player) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            spawnFreeChild(() -> new Sonic1MonitorPowerUpObjectInstance(
                    spawn.x(), currentY, type.id, player));
        }

        // Spawn explosion (id_ExplosionItem = $27) - only if explosion art is loaded
        final ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && objectManager != null
                && renderManager.getExplosionRenderer() != null) {
            spawnFreeChild(() -> new ExplosionObjectInstance(
                    Sonic1ObjectIds.EXPLOSION_ITEM, spawn.x(), currentY, renderManager));
        }
        services().playSfx(Sonic1Sfx.BREAK_ITEM.id);
    }

    /**
     * Apply the monitor's power-up effect.
     * ROM: Pow_ChkX branch table
     */
    @Override
    protected void applyPowerup(PlayableEntity playerEntity) {
        applyMonitorPowerup(type.id, playerEntity, services());
    }

    static void applyMonitorPowerup(int subtype, PlayableEntity playerEntity, ObjectServices services) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (subtype & 0xF) {
            // Pow_ChkRings: v_rings += 10, play sfx_Ring
            case 6 -> {
                player.addRings(RING_MONITOR_REWARD);
                services.playSfx(GameSound.RING);
            }
            // Pow_ChkShield: v_shield = 1, play sfx_Shield
            case 4 -> {
                player.giveShield();
                services.playSfx(Sonic1Sfx.SHIELD.id);
            }
            // Pow_ChkShoes: speed shoes on, play bgm_Speedup (CMD_SPEED_UP = $E2)
            case 3 -> {
                player.giveSpeedShoes();
                GameAudioProfile audioProfile = services.audioManager().getAudioProfile();
                if (audioProfile != null) {
                    services.playMusic(audioProfile.getSpeedShoesOnCommandId());
                }
            }
            // Pow_ChkInvinc: invincibility on, play bgm_Invincible
            case 5 -> {
                player.giveInvincibility();
                services.playMusic(GameMusic.INVINCIBILITY);
            }
            // Pow_ChkSonic: v_lives++, play bgm_ExtraLife
            case 2 -> {
                services.playMusic(GameMusic.EXTRA_LIFE);
                services.gameState().addLife();
            }
            // Pow_ChkEggman, Pow_ChkS, Pow_ChkGoggles: no effect
            default -> { }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            appendFallbackBox(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getMonitorRenderer();
        if (renderer == null || !renderer.isReady()) {
            appendFallbackBox(commands);
            return;
        }

        // Draw monitor body (broken shell or animated frame)
        int frameIndex = broken ? BROKEN_FRAME : mappingFrame;
        renderer.drawFrameIndex(frameIndex, spawn.x(), currentY, false, false);
    }

    /**
     * Debug fallback: render as a colored box when art is unavailable.
     */
    private void appendFallbackBox(List<GLCommand> commands) {
        int cx = spawn.x();
        int cy = currentY;
        int left = cx - HALF_RADIUS;
        int right = cx + HALF_RADIUS;
        int top = cy - HALF_RADIUS;
        int bottom = cy + HALF_RADIUS;
        float r = 0.4f, g = 0.9f, b = 1.0f;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
    }

    // -- Collision interfaces --

    // From disassembly: obColType = $46 (col_32x32|col_item).
    // ROM Mon_BreakOpen sets obColType = col_none once the monitor is broken
    // (docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:183), so a
    // broken monitor no longer participates in ReactToItem. Without this, a
    // broken-but-not-yet-deleted monitor keeps reporting $46 and, because
    // ReactToItem exits on the first overlapping object (rts), it preempts the
    // every-frame break check of a later monitor the player is rolling toward
    // (SYZ3: the broken invincibility monitor @0x19A0 blocked the shoes monitor
    // @0x19C8 for ~3 frames, applying the speed-shoes air-accel doubling late).
    // S2/S3K monitors already gate on broken (MonitorObjectInstance.java:324,
    // Sonic3kMonitorObjectInstance.java:502); this brings S1 to parity.
    @Override
    public int getCollisionFlags() {
        return broken ? 0 : 0x46;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // ROM ReactToItem polls monitors every frame. If the first overlap sees
        // status.rolling before anim reaches id_Roll, a later frame in the same
        // overlap still needs to be able to break the monitor.
        return true;
    }

    // From disassembly: Mon_SolidSides params d1=$1A, d2=$0F, d3=$10
    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(0x1A, 0x0F, 0x10);
    }

    /**
     * The monitor's SST {@code obActWid}, {@code move.b #30/2,obActWid(a0)}
     * = 15 (docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:43). The
     * {@code #16/2} at {@code :234} belongs to {@code Pow_Main}, the power-up
     * icon Obj2E, which is a separate object and a separate class here.
     *
     * <p>The byte's readers all want 15, and the engine already depends on it
     * twice without naming it: {@code Mon_Solid}'s {@code .normal} passes
     * {@code #30/2+sonic_solid_width} = {@code $1A} to {@code Mon_SolidSides}
     * ({@code :100}), which is the literal in {@link #getSolidParams()} above,
     * and the falling/stood-on branch reads {@code obActWid} and adds
     * {@code sonic_solid_width} itself ({@code :72}). {@code BuildSprites}
     * culls on it ({@code docs/s1disasm/_inc/BuildSprites.asm:49-58}) and
     * {@code Sonic_Balance} reads it off the stood-on object
     * ({@code _incObj/01 Sonic.asm:423}).
     *
     * <p>Without this the inherited 16 shifted the balance window by a pixel at
     * both edges on an object the player stands on constantly:
     * {@code d1 = player_x + width - object_x} against {@code #4} and
     * {@code 2*width-4} (_incObj/01 Sonic.asm:425-433).
     * {@code getBalanceWidthPixels()} defaults to this accessor.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return 30 / 2;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // ROM: Mon_Solid only calls Mon_SolidSides when ob2ndRout=0 (normal state).
        // When ob2ndRout=4 (falling after hit from below), Mon_Solid runs ObjectFall
        // + ObjFloorDist but does NOT call Mon_SolidSides — no solid collision.
        // When broken, the monitor is in routine 6/8 which never calls solid checks.
        return !broken && !falling;
    }

    @Override
    public boolean hasMonitorSolidity() {
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Mon_SolidSides reaches Mon_SolidSideAir when d0 equals 2*d1; its
        // horizontal rejection is BHI, so the exact right edge stays solid.
        return true;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // Monitors are static objects — the sticky buffer is only needed for
        // moving platforms to compensate for execution-order jitter. Using it
        // here would extend riding bounds 16px beyond the ROM's ExitPlatform width.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid contact used for standing/edge checks; no additional behavior needed.
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return spawn with dynamic Y for solid collision checks during falling
        if (currentY != spawn.y()) {
            return buildSpawnAt(spawn.x(), currentY);
        }
        return spawn;
    }

    /**
     * S1 Monitor subtypes (from obSubtype in disassembly).
     * Mapping: 0=static, 1=eggman, 2=sonic/1-up, 3=shoes, 4=shield,
     * 5=invincibility, 6=rings, 7=S, 8=goggles, 9=broken shell.
     */
    enum MonitorType {
        STATIC(0),
        EGGMAN(1),
        SONIC(2),
        SHOES(3),
        SHIELD(4),
        INVINCIBILITY(5),
        RINGS(6),
        S_MONITOR(7),
        GOGGLES(8),
        BROKEN(9);

        private final int id;

        MonitorType(int id) {
            this.id = id;
        }

        static MonitorType fromSubtype(int subtype) {
            int value = subtype & 0xF;
            for (MonitorType t : values()) {
                if (t.id == value) {
                    return t;
                }
            }
            return STATIC;
        }
    }
}
