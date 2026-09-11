package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Sonic 1 EggPrison button sub-object (subtype 1 from Pri_Var).
 * <p>
 * Reference: docs/s1disasm/_incObj/3E Prison Capsule.asm - Pri_Switched (routine 4)
 * <p>
 * SolidObject collision: d1=$17 (23), d2=8, d3=8
 * Animation: Ani_Pri .switchflash - alternates frames 1 and 3 at delay 2.
 * When boss is defeated (v_bossstatus changes) and Sonic lands on button,
 * triggers the capsule opening sequence via parent callback.
 */
public class Sonic1EggPrisonButtonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener,
        RewindRecreatable {

    // From disassembly: move.w #$17,d1 / moveq #8,d2 / moveq #8,d3
    private static final int HALF_WIDTH = 0x17;
    private static final int HALF_HEIGHT = 8;

    // From Pri_Var: subtype 1 priority = 5
    private static final int PRIORITY = 5;

    // Button depression: addq.w #8,obY(a0) in Pri_Switched
    private static final int DEPRESS_DISTANCE = 8;

    // Animation: .switchflash: dc.b 2, 1, 3, afEnd
    // Frame delay 2, alternates between mapping frames 1 and 3
    private static final int ANIM_DELAY = 2;
    private static final int FRAME_SWITCH_1 = 1;
    private static final int FRAME_SWITCH_2 = 3;

    // Map_Pri frame index 6 (".blank", 3E Prison Capsule.asm _maps: zero sprite
    // pieces). ROM sets this on the switch object itself in Pri_Explosion's
    // .makeanimal (3E Prison Capsule.asm:137): "move.b #6,obFrame(a0) ; 'delete'
    // switch by turning it invisible". Since the switch and the explosion/animal
    // spawner are the SAME ROM object slot (Pri_Switch transitions its own
    // obRoutine 4->$A->$C->$E), the switch visual is never destroyed -- it is
    // just blanked once the capsule leaves the explosion phase, and stays blank
    // for the rest of the act (Pri_EndAct/DeleteObject only free the slot at
    // GotThroughAct). The button sub-object here models that same lifecycle.
    private static final int FRAME_BLANK = 6;

    private int baseY;
    private int currentY;
    private boolean triggered;
    private boolean blanked;
    private Sonic1EggPrisonObjectInstance parent;
    private boolean parentResolved;
    private int animTimer;
    private int currentFrame = FRAME_SWITCH_1;

    /**
     * Standalone constructor for factory creation (subtype 1 placement entry).
     * Parent body is resolved on first update by scanning active objects.
     */
    public Sonic1EggPrisonButtonObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    public Sonic1EggPrisonButtonObjectInstance(ObjectSpawn spawn, Sonic1EggPrisonObjectInstance parent) {
        super(spawn, "EggPrison Button");
        this.baseY = spawn.y();
        this.currentY = spawn.y();
        this.triggered = false;
        this.animTimer = ANIM_DELAY;
        this.parent = parent;
        if (parent != null) {
            this.parentResolved = true;
            parent.registerButton(this);
        }
    }

    @Override
    public Sonic1EggPrisonButtonObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1EggPrisonButtonObjectInstance(ctx.spawn(), nearestParent(ctx));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Resolve parent body on first update
        if (!parentResolved) {
            resolveParent();
        }

        // ROM: once the capsule leaves Pri_Switch (routine 4) for Pri_Explosion
        // (routine $A), AnimateSprite is no longer called against this object, so
        // the switch-flash animation freezes at whatever frame it last showed.
        // Model that by no longer toggling once triggered.
        if (triggered) {
            return;
        }

        // Animate switch flash (always runs pre-trigger)
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_DELAY;
            currentFrame = (currentFrame == FRAME_SWITCH_1) ? FRAME_SWITCH_2 : FRAME_SWITCH_1;
        }
    }

    /**
     * ROM Pri_Explosion .makeanimal (3E Prison Capsule.asm:134-137): fired when
     * the capsule's explosion timer expires and it advances from Pri_Explosion
     * to Pri_Animals. Blanks the switch visual (frame 6) for the rest of the
     * act, matching the ROM's "'delete' switch by turning it invisible" comment.
     * Called by the parent {@link Sonic1EggPrisonObjectInstance} at that exact
     * transition.
     */
    void goBlank() {
        if (blanked) {
            return;
        }
        blanked = true;
        currentFrame = FRAME_BLANK;
    }

    /**
     * Scans active objects for the nearest EggPrison body and registers this button
     * with it so onButtonTriggered() can fire.
     */
    private void resolveParent() {
        parentResolved = true;
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        this.parent = nearestParent(objectManager, spawn.x());
        if (parent != null) {
            parent.registerButton(this);
        }
    }

    private static Sonic1EggPrisonObjectInstance nearestParent(RewindRecreateContext ctx) {
        ObjectManager objectManager = ctx.objectServices().objectManager();
        if (objectManager == null) {
            return null;
        }
        return nearestParent(objectManager, ctx.spawn().x());
    }

    private static Sonic1EggPrisonObjectInstance nearestParent(ObjectManager objectManager, int spawnX) {
        Sonic1EggPrisonObjectInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (var obj : objectManager.getActiveObjects()) {
            if (obj instanceof Sonic1EggPrisonObjectInstance body && !obj.isDestroyed()) {
                int distance = Math.abs(body.getSpawn().x() - spawnX);
                if (distance < nearestDistance) {
                    nearest = body;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(
                HALF_WIDTH,
                HALF_HEIGHT,
                HALF_HEIGHT,
                0,
                currentY - spawn.y()
        );
    }

    @Override
    public boolean isSolidFor(PlayableEntity sprite) {
        // ROM Pri_Switch (3E Prison Capsule.asm:94): on the trigger frame the
        // capsule object leaves routine 4 (SolidObject) for routine $A
        // (Pri_Explosion), which never calls SolidObject again. So once the
        // switch has fired it is no longer solid - the player it just released
        // into the air must NOT be re-seated onto the depressed switch.
        return !triggered;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!triggered && contact.standing() && player.getYSpeed() >= 0) {
            triggered = true;
            currentY = baseY + DEPRESS_DISTANCE;

            // ROM Pri_Switch (3E Prison Capsule.asm:101-102): on the switch-trigger
            // frame the capsule releases the player into the air -
            //   bclr #3,(v_player+obStatus).w  ; clear Status_OnObj
            //   bset #1,(v_player+obStatus).w  ; set Status_InAir
            // so the player drops off the depressed switch under gravity while the
            // controls are locked to btnR. Without this the engine left the player
            // seated/riding (air=0, OnObj set) where the ROM was airborne.
            player.setOnObject(false);
            player.setAir(true);

            if (parent != null) {
                // ROM Pri_Switch does addq.w #8,obY(a0) BEFORE advancing its own
                // routine to Pri_Explosion, and Pri_Explosion/Pri_SpawnAnimals
                // then read that same obX/obY to place the explosions and the
                // animals (3E Prison Capsule.asm:96-97,118-120,138,152-156).
                // The engine's spawner lives on the capsule body, so hand it the
                // depressed switch's coordinates rather than letting it fall back
                // to the body's own placement position.
                parent.onButtonTriggered(spawn.x(), currentY);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(currentFrame, spawn.x(), currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(spawn.x(), currentY, HALF_WIDTH, HALF_HEIGHT, 0.9f, 0.2f, 0.2f);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    public void detachFromParent() {
        this.parent = null;
    }

    public void destroyButton() {
        this.parent = null;
        setDestroyed(true);
    }
}
