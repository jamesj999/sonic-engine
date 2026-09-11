package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.game.PlayableEntity;

import java.util.List;
import java.util.logging.Logger;

public class ExplosionObjectInstance extends AbstractObjectInstance implements SpawnServicesRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(ExplosionObjectInstance.class.getName());
    private final ObjectRenderManager renderManager;
    private final DestructionEffects.AnimalFactory animalFactory;
    private final DestructionEffects.PointsFactory pointsFactory;
    private int pointsValue;
    private boolean pointsAllocatedBeforeAnimal;
    private int pendingSfxId = -1;
    private boolean firstUpdatePendingForPassedSlot;
    private int animFrame = 0;
    private boolean spawnedDestructionChildren;

    /**
     * ROM-faithful animation timing for Obj27 (badnik-death explosion).
     * <p>
     * The ROM animate routine is identical across S1/S2/S3K:
     * <pre>
     *   subq.b  #1,anim_frame_duration(a0)   ; predecrement
     *   bpl.s   +                            ; still >= 0 -> just display
     *   move.b  #7,anim_frame_duration(a0)   ; reload
     *   addq.b  #1,mapping_frame(a0)         ; advance frame
     *   cmpi.b  #5,mapping_frame(a0)         ; reached frame 5?
     *   beq.w   DeleteObject                 ; if so, delete (not displayed)
     * + jmpto   DisplaySprite
     * </pre>
     * docs/s2disasm/s2.asm:46678-46686, docs/s1disasm/_incObj/24, 27 &amp; 3F
     * Explosions.asm (ExItem_Animate), docs/skdisasm/sonic3k.asm:42199-42208.
     * <p>
     * Only the initial duration differs per game (S1 = 7, S2/S3K = 3); see
     * {@link com.openggf.game.GameModule#explosionInitialAnimDuration()}.
     * <p>
     * Whether the object's own spawn frame already takes a predecrement does
     * <em>not</em> follow the animate routine: it depends on how each init
     * routine ends. See {@link #initFallsThroughToAnimate()}.
     */
    private static final int RELOAD_DURATION = 7;
    private static final int FINAL_MAPPING_FRAME = 5;
    /**
     * Sonic 1 object $3F, the fiery explosion used by the prison capsule, the
     * Walking Bomb badnik and the Ball Hog cannonball. It is the one explosion
     * whose init does not fall through into the animate routine.
     */
    private static final int S1_FIERY_EXPLOSION_ID = 0x3F;
    private int animFrameDuration = -1; // resolved lazily from the game module on first update
    /**
     * Whether the ROM's init routine falls through into the animate routine on
     * the frame the object is allocated, so that frame already takes the
     * predecrement.
     *
     * <p>Obj27's init ends {@code jsr (QueueSound2).l} and the next instruction
     * <em>is</em> {@code ExItem_Animate}, so it falls through
     * (docs/s1disasm/sonic.lst: 9450 then 9456). S2's {@code Obj27_Init} ends
     * {@code jsr (PlaySound).l} immediately above {@code Obj27_Main}
     * (docs/s2disasm/s2.asm:46734-46737), and S3K writes
     * {@code move.l #loc_1E66E,(a0)} immediately above {@code loc_1E66E}
     * (docs/skdisasm/sonic3k.asm:42202-42205). All three take the predecrement
     * on their spawn frame.
     *
     * <p>Sonic 1's Obj3F does not. {@code Expl_Main} ends
     * {@code jmp (QueueSound2).l} and returns to the object loop
     * (docs/s1disasm/sonic.lst: 94C0; the bytes at 94C6 are the unrelated Ball
     * Hog animation script), so its animate routine does not run until the
     * following frame and the object lives exactly one frame longer -- 40
     * frames from spawn to delete rather than 39. Measured on the shipped ROM
     * by tools/tracechaser/bizhawk/probes/s1_lz3_explosion_lifetime_probe.lua.
     *
     * <p>That one frame decides when the explosion's object slot is freed, and
     * therefore which slot {@code FindFreeObj} hands the next allocation. In
     * LZ3 it decides whether the prison capsule's first burst animal lands
     * above or below the capsule, which decides whether it executes in the same
     * frame or the next, which rotates the eight animals' species draws.
     *
     * <p>Read from the spawn's object id rather than cached, because that is
     * what the ROM's own routine dispatch reads, and it keeps the answer
     * reconstructible from the rewind-captured spawn alone.
     */
    private boolean initFallsThroughToAnimate() {
        return (spawn.objectId() & 0xFF) != S1_FIERY_EXPLOSION_ID;
    }

    public ExplosionObjectInstance(int id, int x, int y, ObjectRenderManager renderManager) {
        this(id, x, y, renderManager, -1);
    }

    public ExplosionObjectInstance(ObjectSpawn spawn, ObjectServices services) {
        this(spawn.objectId(), spawn.x(), spawn.y(),
                services != null ? services.renderManager() : null, -1);
    }

    /**
     * Creates an explosion that makes its own sound request.
     *
     * <p>ROM explosion objects play their SFX from their init routine, in their
     * own SST slot's pass (docs/s2disasm/s2.asm:46717-46734), so the request is
     * made on this object's first {@code update}, not when it is constructed.
     *
     * @param sfxId SFX id the explosion requests on its first pass, or -1 for none
     */
    public ExplosionObjectInstance(int id, int x, int y, ObjectRenderManager renderManager, int sfxId) {
        super(new ObjectSpawn(x, y, id, 0, 0, false, 0), "Explosion");
        this.renderManager = renderManager;
        this.animalFactory = null;
        this.pointsFactory = null;
        this.pointsValue = 0;
        this.pointsAllocatedBeforeAnimal = false;
        this.pendingSfxId = sfxId;
    }

    public ExplosionObjectInstance(int id, int x, int y, ObjectRenderManager renderManager,
            DestructionEffects.AnimalFactory animalFactory,
            DestructionEffects.PointsFactory pointsFactory,
            int pointsValue,
            boolean pointsAllocatedBeforeAnimal) {
        super(new ObjectSpawn(x, y, id, 0, 0, false, 0), "Explosion");
        this.renderManager = renderManager;
        this.animalFactory = animalFactory;
        this.pointsFactory = pointsFactory;
        this.pointsValue = pointsValue;
        this.pointsAllocatedBeforeAnimal = pointsAllocatedBeforeAnimal;
    }

    private void playPendingSfxIfPossible() {
        if (pendingSfxId < 0) {
            return;
        }
        try {
            ObjectServices ctx = tryServices();
            if (ctx != null) {
                ctx.playSfx(pendingSfxId);
                pendingSfxId = -1;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to play explosion sound: " + e.getMessage());
        }
    }

    /**
     * Marks an explosion allocated into an SST slot the ROM's object scan has
     * already passed this frame, so its init routine first runs on the next
     * one. {@code Obj26_Break} allocates with lowest-free {@code AllocateObject}
     * from the monitor's own slot (docs/s2disasm/s2.asm:25702-25707), so the
     * explosion regularly lands below the object currently executing. This
     * mirrors {@code MonitorContentsObjectInstance#delayFirstIconUpdateForPassedSlot}.
     */
    public void delayFirstUpdateForPassedSlot() {
        firstUpdatePendingForPassedSlot = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (firstUpdatePendingForPassedSlot) {
            firstUpdatePendingForPassedSlot = false;
            return;
        }
        // ROM Obj27_Init plays the explosion sound from the explosion's own
        // execution, not from whatever destroyed the object
        // (docs/s2disasm/s2.asm:46717-46734; docs/s1disasm/_incObj/24, 27 & 3F
        // Explosions.asm; docs/skdisasm/sonic3k.asm:42199-42208).
        playPendingSfxIfPossible();
        spawnDestructionChildrenOnce();
        if (animFrameDuration < 0) {
            animFrameDuration = resolveInitialAnimDuration();
            if (!initFallsThroughToAnimate()) {
                // Expl_Main returned rather than falling through, so this frame
                // ran the init alone and the animate routine starts next frame.
                return;
            }
        }
        // ROM: subq.b #1,anim_frame_duration / bpl.s + (still showing this frame)
        animFrameDuration--;
        if (animFrameDuration >= 0) {
            return;
        }
        // ROM: reload, advance mapping_frame, delete when it reaches frame 5.
        animFrameDuration = RELOAD_DURATION;
        animFrame++;
        if (animFrame >= FINAL_MAPPING_FRAME) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    private void spawnDestructionChildrenOnce() {
        if (spawnedDestructionChildren || (animalFactory == null && pointsFactory == null)) {
            return;
        }
        spawnedDestructionChildren = true;
        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }
        ObjectManager objectManager = svc.objectManager();
        if (objectManager == null) {
            return;
        }

        int x = spawn.x();
        int y = spawn.y();
        if (pointsAllocatedBeforeAnimal && pointsFactory != null) {
            objectManager.createDynamicObject(() -> pointsFactory.create(
                    new ObjectSpawn(x, y, 0x29, 0, 0, false, 0), svc, pointsValue));
        }
        // S3K Obj_Explosion routine 0 allocates Obj_Animal before initializing
        // its animation/SFX (docs/skdisasm/sonic3k.asm:42157-42180).
        if (animalFactory != null) {
            objectManager.createDynamicObject(() -> animalFactory.create(
                    new ObjectSpawn(x, y, 0x28, 0, 0, false, pointsValue), svc));
        }
        if (!pointsAllocatedBeforeAnimal && pointsFactory != null) {
            objectManager.createDynamicObject(() -> pointsFactory.create(
                    new ObjectSpawn(x, y, 0x29, 0, 0, false, 0), svc, pointsValue));
        }
    }

    /**
     * Per-game initial {@code anim_frame_duration} (ROM {@code move.b #N}).
     * Resolved from the active {@link com.openggf.game.GameModule} so the shared
     * class stays game-agnostic; falls back to the S2/S3K value (3) when the
     * module is unavailable (e.g. unit tests with no module registered).
     */
    private int resolveInitialAnimDuration() {
        try {
            ObjectServices ctx = tryServices();
            if (ctx != null && ctx.gameModule() != null) {
                return ctx.gameModule().explosionInitialAnimDuration();
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return 3;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || animFrame >= FINAL_MAPPING_FRAME)
            return;
        if (renderManager == null || renderManager.getExplosionRenderer() == null) {
            return;
        }
        renderManager.getExplosionRenderer().drawFrameIndex(animFrame, spawn.x(), spawn.y(), false, false);
    }
}
