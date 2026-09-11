package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Sonic 1 Badnik 0x60 - Orbinaut (LZ/SLZ/SBZ).
 * <p>
 * ROM reference: docs/s1disasm/_incObj/60 Orbinaut.asm
 */
public class Sonic1OrbinautBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x0B;

    private static final int DETECT_X = 0xA0;
    private static final int DETECT_Y = 0x50;

    private static final int MOVE_SPEED = 0x40;
    private static final int SPIKE_SHOT_SPEED = 0x200;

    private static final int ANIM_SPEED = 0x10; // $F + 1

    private static final int ROUTINE_CHK_SONIC = 2;
    private static final int ROUTINE_MOVE = 4;

    private int routine;
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    private int animationId;
    private int animationFrame;
    private int animationTimer;

    private int angleStep;
    private int activeSpikes;
    private List<OrbSpikeObjectInstance> spikes;
    /**
     * True once React_BadnikHit replaced this Orbinaut, so teardown must leave the
     * balls to self-delete at their own slots rather than freeing them here.
     */
    private boolean playerDestroyed;
    private boolean initialized;

    public Sonic1OrbinautBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Orbinaut");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;

        this.routine = ((spawn.subtype() & 0xFF) == 2) ? ROUTINE_MOVE : ROUTINE_CHK_SONIC;
        this.xVelocity = facingLeft ? -MOVE_SPEED : MOVE_SPEED;
        this.angleStep = facingLeft ? 1 : -1;

        this.animationId = 0;
        this.animationFrame = 0;
        this.animationTimer = ANIM_SPEED;

        this.activeSpikes = 0;
        this.initialized = false;
    }

    @Override
    public Sonic1OrbinautBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1OrbinautBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            spawnSatellites();
            initialized = true;
        }

        if (routine == ROUTINE_CHK_SONIC && shouldBecomeAngry(player)) {
            // ROM: move.b #1,obAnim(a0) — AnimateSprite only resets when obAnim
            // changes. On first transition, immediately set frame 1 (ROM's
            // AnimateSprite sets the first frame instantly on animation change).
            if (animationId != 1) {
                animationId = 1;
                animationFrame = 1;         // Immediately show angry face
                animationTimer = ANIM_SPEED; // Timer for transition to frame 2
            }
        }

        if (activeSpikes == 0) {
            // ROM Orb_MoveOrb increments the parent routine as soon as the
            // last satellite fires, before the parent's next Orb_Display pass.
            routine = ROUTINE_MOVE;
        }

        if (routine >= ROUTINE_MOVE) {
            applySpeedToPos();
        }
    }

    private boolean shouldBecomeAngry(AbstractPlayableSprite player) {
        if (player == null || player.isDebugMode()) {
            return false;
        }

        int dx = Math.abs(player.getCentreX() - currentX);
        if (dx >= DETECT_X) {
            return false;
        }

        int dy = Math.abs(player.getCentreY() - currentY);
        return dy < DETECT_Y;
    }

    private void applySpeedToPos() {
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (animationId == 0) {
            animationFrame = 0;
            return;
        }

        // ROM animation: .angers: dc.b $F, 1, 2, afBack, 1
        // Plays frame 1 once, then stays at frame 2 permanently (afBack loops
        // back to frame 2). Frame 1 is set immediately in updateMovement when
        // the orbinaut first gets angry.
        if (animationFrame >= 2) {
            return; // afBack: stay at frame 2 permanently
        }

        animationTimer--;
        if (animationTimer > 0) {
            return;
        }
        animationTimer = ANIM_SPEED;
        animationFrame = 2; // Advance from frame 1 to frame 2
    }

    private void spawnSatellites() {
        if (services().objectManager() == null) {
            return;
        }

        spikes = new ArrayList<>(4);
        int[] angleOffsets = { 0x00, 0x40, 0x80, 0xC0 };
        // ROM: Orb_Loop uses FindNextFreeObj, allocating consecutive slots
        int prevSlot = getSlotIndex();
        for (int angleOffset : angleOffsets) {
            final int angle = angleOffset;
            final int prevSlotFinal = prevSlot;
            OrbSpikeObjectInstance spike = spawnFreeChild(() -> {
                OrbSpikeObjectInstance s = new OrbSpikeObjectInstance(this, angle);
                ObjectLifetimeOps.assignFindNextFreeChildSlot(services().objectManager(), s, prevSlotFinal);
                return s;
            });
            spikes.add(spike);
            if (spike.getSlotIndex() >= 0) {
                prevSlot = spike.getSlotIndex();
            }
        }
        activeSpikes = spikes.size();
    }

    int getAnimationFrame() {
        return animationFrame;
    }

    int getAngleStep() {
        return angleStep;
    }

    boolean isFacingLeft() {
        return facingLeft;
    }

    void onSpikeLaunched() {
        if (activeSpikes > 0) {
            activeSpikes--;
        }
    }

    void adoptSpikeForRewind(OrbSpikeObjectInstance restoredSpike) {
        if (spikes == null) {
            spikes = new ArrayList<>(4);
        }
        spikes.removeIf(spike -> spike == null || spike.isDestroyed() || spike.parent != this);
        spikes.remove(restoredSpike);
        spikes.add(restoredSpike);
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    protected void destroyBadnik(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM has TWO distinct teardown branches and they free the balls'"'"' SST slots
        // at different points in ExecuteObjects. Collapsing them into one eager
        // parent-side delete moves every following FindFreeObj allocation:
        //
        //  - OUT OF RANGE (Orb_ChkDel, docs/s1disasm/_incObj/60 Badnik - Orbinaut.asm:135-152):
        //    the PARENT walks orb_balldata and DeleteChild's each non-fired ball while
        //    it is executing, then DeleteObject's itself. Slots free at the parent'"'"'s slot.
        //  - DESTROYED BY THE PLAYER (React_BadnikHit): the parent'"'"'s obID becomes
        //    id_ExplosionItem in place, so Orb_ChkDel never runs. Each ball instead
        //    self-deletes when the ascending pass reaches ITS OWN slot and
        //    Orb_CircleSpikeball sees the parent is no longer an Orbinaut
        //    (:155-159). Slots free LATER, at each ball'"'"'s slot.
        //
        // The child already models the second branch (see OrbSpike.update'"'"'s
        // parent.isDestroyed() check), so the kill path must simply not pre-empt it.
        playerDestroyed = true;
        super.destroyBadnik(player);
    }

    @Override
    public void onUnload() {
        // Only the Orb_ChkDel out-of-range branch deletes the balls from the parent.
        if (!playerDestroyed) {
            destroySpikes();
        }
    }

    private void destroySpikes() {
        if (spikes != null) {
            ObjectServices svc = tryServices();
            ObjectManager objectManager = svc != null ? svc.objectManager() : null;
            for (OrbSpikeObjectInstance spike : spikes) {
                spike.setDestroyed(true);
                if (objectManager != null) {
                    // S1 Orb_ChkDel calls DeleteChild while the parent is
                    // executing, so these SST slots are reusable immediately.
                    objectManager.removeDynamicObject(spike);
                }
            }
            spikes.clear();
            activeSpikes = 0;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("parent r=%02X frame=%d active=%d step=%d vel=%04X",
                routine & 0xFF,
                animationFrame,
                activeSpikes,
                angleStep,
                xVelocity & 0xFFFF);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.ORBINAUT);
        if (renderer == null) return;

        renderer.drawFrameIndex(animationFrame, currentX, currentY, !facingLeft, false);
    }

    private static final class OrbSpikeObjectInstance extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int SPIKE_RENDER_HALF_WIDTH = 16 / 2;
        private static final int ASSUMED_RENDER_HALF_HEIGHT = 32;

        private final Sonic1OrbinautBadnikInstance parent;

        private int x;
        private int y;
        private int angle;

        private boolean launched;
        private int xVelocity;
        /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
        private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

        private OrbSpikeObjectInstance() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "OrbinautSpike");
            this.parent = null;
            this.angle = 0;
            this.x = 0;
            this.y = 0;
            this.launched = false;
            this.xVelocity = 0;
        }

        OrbSpikeObjectInstance(Sonic1OrbinautBadnikInstance parent, int startAngle) {
            super(new ObjectSpawn(parent.currentX, parent.currentY, parent.spawn.objectId(), 0, 0, false, 0),
                    "OrbinautSpike");
            this.parent = parent;
            this.angle = startAngle & 0xFF;
            this.x = parent.currentX;
            this.y = parent.currentY;
            this.launched = false;
            this.xVelocity = 0;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            Sonic1OrbinautBadnikInstance restoredParent = nearestLiveOrbinautParentForRewind(ctx);
            if (restoredParent == null) {
                return null;
            }
            OrbSpikeObjectInstance restored = new OrbSpikeObjectInstance(restoredParent, 0);
            restoredParent.adoptSpikeForRewind(restored);
            return restored;
        }

        private static Sonic1OrbinautBadnikInstance nearestLiveOrbinautParentForRewind(
                RewindRecreateContext ctx) {
            ObjectServices services = ctx.objectServices();
            ObjectManager objectManager = services != null ? services.objectManager() : null;
            ObjectSpawn capturedSpawn = ctx.spawn();
            if (objectManager == null) {
                return null;
            }
            Sonic1OrbinautBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (var object : objectManager.getActiveObjects()) {
                if (!(object instanceof Sonic1OrbinautBadnikInstance orbinaut) || orbinaut.isDestroyed()) {
                    continue;
                }
                if (capturedSpawn == null) {
                    return orbinaut;
                }
                long dx = orbinaut.getX() - capturedSpawn.x();
                long dy = orbinaut.getY() - capturedSpawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = orbinaut;
                }
            }
            return best;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), 0, false, 0);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }

            if (launched) {
                motion.x = x;
                motion.xVel = xVelocity;
                SubpixelMotion.moveX(motion);
                x = motion.x;

                if (!isOnScreen(256)) {
                    setDestroyed(true);
                }
                return;
            }

            // Orb_MoveOrb launch condition.
            if (parent.getAnimationFrame() == 2 && angle == 0x40) {
                launched = true;
                parent.onSpikeLaunched();
                xVelocity = parent.isFacingLeft() ? -SPIKE_SHOT_SPEED : SPIKE_SHOT_SPEED;
                return;
            }

            // ROM Orb_CircleSpikeball: CalcSine on obAngle, then asr.w #4 for both
            // components to give radius-16 orbit (docs/s1disasm/_incObj/60 Badnik -
            // Orbinaut.asm:181-191). Using integer lookup matches ROM's truncating
            // arithmetic shift exactly; floating-point Math.round rounds 254>>4=15.875
            // up to 16 and places the spike 1px too low, causing a premature hurt hit.
            int sinVal = TrigLookupTable.sinHex(angle); // d0 = CalcSine sine output
            int cosVal = TrigLookupTable.cosHex(angle); // d1 = CalcSine cosine output
            x = parent.currentX + (cosVal >> 4);        // obX + (d1 asr #4)
            y = parent.currentY + (sinVal >> 4);        // obY + (d0 asr #4)
            angle = (angle + parent.getAngleStep()) & 0xFF;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            ObjectServices svc = tryServices();
            ObjectRenderManager renderManager = svc != null ? svc.renderManager() : null;
            if (renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.ORBINAUT);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            renderer.drawFrameIndex(3, x, y, false, false);
        }

        @Override
        public int getCollisionFlags() {
            // $98 from Orb_MoveOrb setup.
            return 0x98;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }

        @Override
        public boolean isPersistent() {
            return !isDestroyed() && !launched;
        }

        @Override
        public boolean usesCustomOutOfRangeCheck() {
            return true;
        }

        @Override
        public boolean isCustomOutOfRange(int cameraX) {
            // S1 Orb_MoveOrb (routine 6) has no out_of_range call; it only
            // deletes when the parent is gone. Routine 8 launches the spike
            // and then deletes when obRender bit 7 from the previous
            // BuildSprites pass is clear (docs/s1disasm/s1disasm/_incObj/
            // 60 Badnik - Orbinaut.asm:183-186). BuildSprites uses the
            // satellite's obActWid=16/2 and the default 32px assumed height
            // when setting that bit (BuildSprites.asm:48-58, 86-94).
            return launched && !isWithinRenderSpriteBounds(
                    SPIKE_RENDER_HALF_WIDTH, ASSUMED_RENDER_HALF_HEIGHT);
        }

        @Override
        public String traceDebugDetails() {
            return String.format("spike angle=%02X launched=%s vel=%04X parentSlot=%d parentFrame=%d",
                    angle & 0xFF,
                    launched,
                    xVelocity & 0xFFFF,
                    parent.getSlotIndex(),
                    parent.getAnimationFrame());
        }
    }
}
