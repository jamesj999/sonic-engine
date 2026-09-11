package com.openggf.game.sonic1.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Reflection shine overlay for MZ Glass Block (Object 0x30).
 * <p>
 * Spawned by {@link Sonic1GlassBlockObjectInstance} as a child object.
 * This object renders the translucent shine effect on the glass pillar at a
 * lower sprite priority than the block itself.
 * <p>
 * Routine 4 (Glass_Reflect012, tall variant):
 *   Copies parent's glass_dist, then applies Glass_Types movement.
 * Routine 8 (Glass_Reflect34, short variant):
 *   Copies parent's glass_dist AND parent's LIVE current obY (via parent.getY(), per ROM
 *   Glass_Reflect34's move.w obY(a1) copy), then applies Glass_Types.
 * <p>
 * The reflection's subtype has bit 3 set (from the addq.b #8 / andi.b #$F in Glass_Main),
 * which modifies the behavior in Glass_Types:
 * <ul>
 *   <li>Types 1-2 (oscillation): neg.w d0 / add.w d1,d0 / lsr.b #1,d0 / addi.w #$20,d0</li>
 *   <li>Types 3-4 (stand/switch): uses direct oscillation (v_oscillate+$12 - $10) instead</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm
 */
public class Sonic1GlassReflectionInstance extends AbstractObjectInstance implements RewindRecreatable {

    // From Glass_Main: move.b #3,obPriority(a1)
    private static final int PRIORITY = 3;

    // v_oscillate+$12: byte offset $10 (same as parent)
    private static final int OSC_BYTE_OFFSET = 0x10;
    // Glass_Type01/02 amplitude: move.w #$40,d1
    private static final int OSC_AMPLITUDE = 0x40;
    // Glass_Type03/04 reflection offset: subi.w #$10,d0
    private static final int REFLECT_OSC_OFFSET = 0x10;

    // Shine frame index (frame 1 in mappings)
    private static final int SHINE_FRAME = 1;
    private final Sonic1GlassBlockObjectInstance parent;
    private int reflectSubtype;
    private boolean isTall;

    // Dynamic position
    private int x;
    private int y;

    // Base Y (objoff_30): for tall variant, set once; for short, updated from parent
    private int baseY;

    // glass_dist: synced from parent each frame
    private int glassDist;

    private Sonic1GlassReflectionInstance(ObjectSpawn probeSpawn) {
        super(probeSpawn, "MzGlassReflectProbe");
        this.parent = null;
        this.reflectSubtype = 0;
        this.isTall = false;
        this.x = probeSpawn.x();
        this.baseY = probeSpawn.y();
        this.glassDist = 0;
        this.y = baseY;
    }

    Sonic1GlassReflectionInstance(ObjectSpawn parentSpawn,
                                  Sonic1GlassBlockObjectInstance parent,
                                  int reflectSubtype,
                                  boolean isTall) {
        super(parentSpawn, "MzGlassReflect");
        this.parent = parent;
        this.reflectSubtype = reflectSubtype;
        this.isTall = isTall;

        this.x = parentSpawn.x();
        this.baseY = parentSpawn.y();
        this.glassDist = parent.getGlassDist();
        this.y = baseY - glassDist;

        updateDynamicSpawn(x, y);
    }

    @Override
    public Sonic1GlassReflectionInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        Sonic1GlassBlockObjectInstance liveParent = findLiveGlassBlockParentForRewind(ctx, spawn);
        if (liveParent == null) {
            return null;
        }
        int fullSubtype = spawn.subtype() & 0xFF;
        int reflectedSubtype = (fullSubtype + 8) & 0x0F;
        boolean tall = fullSubtype < 3;
        return new Sonic1GlassReflectionInstance(spawn, liveParent, reflectedSubtype, tall);
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Check if parent is destroyed -> self-destruct
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        // Sync glass_dist from parent
        glassDist = parent.getGlassDist();

        // Short variant (routine 8) also syncs baseY from parent's CURRENT obY.
        // Glass_Reflect34 (sonic.lst BB5E: 3169 000C 0030): move.w obY(a1),objoff_30(a0)
        // -- source displacement $0C is obY (live position), not objoff_30 ($30,
        // the static spawn baseline); using getBaseY() here displaced the
        // reflection by up to glass_dist (0x90/144px) from the parent.
        if (!isTall) {
            baseY = parent.getY();
        }

        // Apply Glass_Types movement using reflection subtype
        applyReflectionMovement();

        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_GLASS_BLOCK);
        if (renderer == null) return;

        renderer.drawFrameIndex(SHINE_FRAME, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // Reflection follows parent; if parent is gone, we go too
        return !isDestroyed() && parent != null && !parent.isDestroyed();
    }

    private static Sonic1GlassBlockObjectInstance findLiveGlassBlockParentForRewind(
            RewindRecreateContext ctx, ObjectSpawn spawn) {
        ObjectServices services = ctx.objectServices();
        if (services == null) {
            return null;
        }
        ObjectManager objectManager = services.objectManager();
        if (objectManager == null) {
            return null;
        }
        Sonic1GlassBlockObjectInstance best = null;
        long bestDist = Long.MAX_VALUE;
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof Sonic1GlassBlockObjectInstance block && !block.isDestroyed()) {
                long dx = block.getX() - spawn.x();
                long dy = block.getBaseY() - spawn.y();
                long dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = block;
                }
            }
        }
        return best;
    }

    /**
     * Applies Glass_Types movement for the reflection child.
     * The reflection subtype has bit 3 set, which modifies oscillation behavior.
     * <p>
     * For types 1-2: loc_B514 btst #3 is true ->
     *   neg.w d0 / add.w d1,d0 / lsr.b #1,d0 / addi.w #$20,d0 -> loc_B5EE
     * <p>
     * For types 3-4: btst #3 is true ->
     *   Uses oscillation (v_oscillate+$12 - $10) directly instead of stand/switch logic.
     */
    private void applyReflectionMovement() {
        int moveType = reflectSubtype & 0x07;

        int d0;
        switch (moveType) {
            case 0 -> {
                // Glass_Type00: rts -> stationary
                d0 = glassDist;
            }
            case 1, 2 -> {
                // Glass_Type01/02: read oscillator
                d0 = OscillationManager.getByte(OSC_BYTE_OFFSET);
                int d1 = OSC_AMPLITUDE;

                if (moveType == 2) {
                    // Glass_Type02: neg.w d0 / add.w d1,d0
                    d0 = -d0 + d1;
                }

                // Bit 3 set: loc_B514 -> neg.w d0 / add.w d1,d0 / lsr.b #1,d0 / addi.w #$20,d0
                d0 = -d0 + d1;
                d0 = (d0 & 0xFF) >>> 1;
                d0 += 0x20;
            }
            case 3, 4 -> {
                // Bit 3 set: Glass_Type03/04 reflection path
                // move.b (v_oscillate+$12).w,d0 / subi.w #$10,d0
                d0 = OscillationManager.getByte(OSC_BYTE_OFFSET);
                d0 -= REFLECT_OSC_OFFSET;
            }
            default -> {
                d0 = glassDist;
            }
        }

        // loc_B5EE: move.w objoff_30(a0),d1 / sub.w d0,d1 / move.w d1,obY(a0)
        y = baseY - d0;
    }
}
