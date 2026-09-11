package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x84 — FZ Crushing Cylinder.
 * ROM: _incObj/84 FZ Eggman's Cylinders.asm
 *
 * 4 instances with subtypes 0, 2, 4, 6:
 *   0: bottom-left  (boss_fz_x + $80,  boss_fz_y + $110)
 *   2: bottom-right  (boss_fz_x + $100, boss_fz_y + $110)
 *   4: top-left     (boss_fz_x + $40,  boss_fz_y - $50)
 *   6: top-right    (boss_fz_x + $C0,  boss_fz_y - $50)
 *
 * Bottom cylinders (subtypes 0-2) extend downward (objoff_29 = -1).
 * Top cylinders (subtypes 4-6) extend upward (objoff_29 = +1).
 *
 * Extension uses 32-bit fixed-point offset (objoff_3C):
 *   Bottom: subtract $8000/frame, boost -$28000 when near limit (-$10)
 *   Top: add $8000/frame, boost +$28000 when near limit (+$10)
 *   Range: -$A0 to +$A0
 *
 * SolidObject params: d1=$2B, d2=$60, d3=$61
 */
public class FZCylinder extends AbstractBossChild implements SolidObjectProvider, RewindRecreatable {

    // Position data from EggmanCylinder_PosData
    private static final int[][] CYLINDER_POS = {
            {Sonic1Constants.BOSS_FZ_X + 0x80,  Sonic1Constants.BOSS_FZ_Y + 0x110}, // subtype 0
            {Sonic1Constants.BOSS_FZ_X + 0x100, Sonic1Constants.BOSS_FZ_Y + 0x110}, // subtype 2
            {Sonic1Constants.BOSS_FZ_X + 0x40,  Sonic1Constants.BOSS_FZ_Y - 0x50},  // subtype 4
            {Sonic1Constants.BOSS_FZ_X + 0xC0,  Sonic1Constants.BOSS_FZ_Y - 0x50},  // subtype 6
    };

    // SolidObject params: d1=$2B, d2=$60, d3=$61
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x2B, 0x60, 0x61);
    // ROM: obActWid = $20; Solid_Landed uses this narrower width for top-standing checks.
    private static final int TOP_LANDING_HALF_WIDTH = 0x20;

    // Un-finaled for rewind: subtype is NOT spawn-derivable (AbstractBossChild's ctor
    // hardcodes ObjectSpawn subtype 0), so the generic field capturer reapplies the
    // captured per-cylinder values after the recreate hook uses placeholder subtype 0.
    private int subtype;      // 0, 2, 4, or 6
    private boolean isBottom;  // subtypes 0-2 are bottom, 4-6 are top
    private int baseX;
    private int baseY;         // objoff_38 — base Y position

    // Extension state
    private int direction;           // objoff_29: -1 = extending (bottom), +1 = extending (top), 0 = idle
    private int extensionFixed;      // objoff_3C: 32-bit fixed-point extension offset
    private boolean active;          // obRoutine >= 4
    private boolean activationDeferred; // first-frame defer: ROM runs the routine-2
                                        // Action body (objoff_3C=0) on the activation
                                        // frame; extension starts the next frame.
    private boolean drivesBossPosition; // ROM: objoff_30 < 0 branch drives boss X/Y
    private int currentFrame;

    @Override
    public boolean usesInclusiveRightEdge() {
        // SolidObject rejects with `bhi`, preserving exact-right-edge contact.
        // docs/s1disasm/_incObj/sub SolidObject.asm:158-166
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Extension rebuilds the dynamic child spawn every frame; the native
        // pushing bit stays in the cylinder's live Obj84 SST status byte.
        return true;
    }


    FZCylinder(Sonic1FZBossInstance parent) {
        this(parent, 0);
    }

    public FZCylinder(Sonic1FZBossInstance parent, int subtype) {
        super(parent, "FZ Cylinder " + subtype, 3, Sonic1ObjectIds.EGGMAN_CYLINDER);
        
        this.subtype = subtype;
        this.isBottom = subtype <= 2;

        // Initialize position from PosData
        int index = subtype >> 1;
        this.baseX = CYLINDER_POS[index][0];
        this.baseY = CYLINDER_POS[index][1];
        this.currentX = baseX;
        this.currentY = baseY;

        this.direction = 0;
        this.extensionFixed = 0;
        this.active = false;
        this.activationDeferred = false;
        this.drivesBossPosition = false;
        this.currentFrame = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic1FZBossInstance boss = firstLiveFzBoss(ctx);
        if (boss == null) {
            return null;
        }
        // FZ has one boss group; this preserves the deleted explicit restore path's
        // first-live parent matching while keeping the relink local to FZ.
        FZCylinder restored = new FZCylinder(boss);
        seedCapturedScalars(restored, ctx);
        boss.adoptCylinderForRewind(restored);
        return restored;
    }

    int subtypeForRewind() {
        return subtype;
    }

    private static void seedCapturedScalars(FZCylinder restored, RewindRecreateContext ctx) {
        if (ctx == null || ctx.state() == null || ctx.state().compactGenericState() == null) {
            return;
        }
        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(
                restored, ctx.state().compactGenericState());
    }

    private static Sonic1FZBossInstance firstLiveFzBoss(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1FZBossInstance boss && !boss.isDestroyed()) {
                return boss;
            }
        }
        return null;
    }

    /**
     * Activate this cylinder for extension.
     * @param dir -1 for bottom (extending down), +1 for top (extending up)
     */
    public void activate(int dir) {
        this.direction = dir;
        this.active = true;
        // ROM EggmanCylinder_Action (routine 2) sets objoff_29 then advances obRoutine
        // to 4 on the SAME frame, but falls through to its loc_1A4EA body with
        // objoff_3C still cleared to 0 (clr.l objoff_3C at _incObj/85,84,86 Boss - FZ
        // Main, Cylinders, and Plasma Balls.asm:692) — so the activation frame seats
        // the cylinder at its rest position and the first actual extension step
        // (EggmanCylinder_Move / routine 4) does not run until the NEXT frame. Defer
        // the engine's first extension step one frame to match that cadence.
        this.activationDeferred = true;
        // ROM: The first selected cylinder gets objoff_30=-1 and drives parent X/Y.
        this.drivesBossPosition = dir < 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!beginUpdate(vIntRunCount)) return;

        if (!active || activationDeferred) {
            // ROM: Routine 2 (Action) — clear extension and seat at rest. On the
            // activation frame the routine advances to 4 but still runs this body
            // with objoff_3C = 0 (asm:692), so extension begins next frame.
            extensionFixed = 0;
            activationDeferred = false;
        } else {
            // ROM: Routine 4 — extending/retracting
            if (isBottom) {
                updateBottomCylinder(vIntRunCount);
            } else {
                updateTopCylinder(vIntRunCount);
            }
        }

        // Calculate display Y from base + extension offset
        // ROM: loc_1A4EA — objoff_38 + objoff_3C -> obY
        int extensionPixels = extensionFixed >> 16;
        currentY = baseY + extensionPixels;
        currentX = baseX;

        // ROM: loc_1A514 — host cylinder updates parent X/Y while active.
        if (active && drivesBossPosition) {
            Sonic1FZBossInstance fzParent = (Sonic1FZBossInstance) parent;
            int yOffset = isBottom ? -0xA : 0xE;
            fzParent.syncPositionFromCylinder(currentX, currentY + yOffset);
        }

        // Calculate frame (ROM: loc_1A524 - loc_1A55C)
        calculateFrame();

        updateDynamicSpawn();
    }

    /**
     * Bottom cylinder extension logic (subtypes 0-2).
     * ROM: loc_1A598 (retracting) / loc_1A5D4 (extending)
     */
    private void updateBottomCylinder(int vIntRunCount) {
        Sonic1FZBossInstance fzParent = (Sonic1FZBossInstance) parent;

        if (direction == 0) {
            // Retracting (ROM: loc_1A598 — direction cleared, returning to base)
            if (fzParent.isBossDefeated()) {
                // ROM: BossDefeated — spawn explosions during post-defeat retraction
                fzParent.triggerBossDefeatedExplosion(vIntRunCount, currentX, currentY);
                // ROM: subi.l #$10000,objoff_3C — counter-force
                extensionFixed -= 0x10000;
            }

            // ROM: addi.l #$20000,objoff_3C — retract toward zero
            extensionFixed += 0x20000;

            // ROM: bcc.s locret — check for overflow past zero
            if (extensionFixed >= 0) {
                // Retraction complete
                extensionFixed = 0;
                active = false;
                drivesBossPosition = false;
                fzParent.onCylinderDone();
            }
        } else {
            // Extending (ROM: loc_1A5D4)
            // ROM: cmpi.w #-$10,objoff_3C / bge.s loc_1A5E4 — the boost
            // (subi.l #$28000) runs only when objoff_3C < -$10 (the bge SKIPS the
            // boost at exactly -$10). Using <= -0x10 here applied the boost one
            // extension-step too early, shortening the cylinder crush cycle
            // (docs/s1disasm/_incObj/85,84,86 Boss - FZ Main, Cylinders, and Plasma
            // Balls.asm:793-796).
            if ((extensionFixed >> 16) < -0x10) {
                extensionFixed -= 0x28000; // ROM: subi.l #$28000
            }

            // ROM: subi.l #$8000,objoff_3C — base extension speed
            extensionFixed -= 0x8000;

            // ROM: cmpi.w #-$A0,objoff_3C — check fully extended
            if ((extensionFixed >> 16) <= -0xA0) {
                extensionFixed = (-0xA0) << 16; // Clamp
                direction = 0; // ROM: clr.b objoff_29 — start retracting
            }
        }
    }

    /**
     * Top cylinder extension logic (subtypes 4-6).
     * ROM: loc_1A604 (retracting) / loc_1A646 (extending)
     */
    private void updateTopCylinder(int vIntRunCount) {
        Sonic1FZBossInstance fzParent = (Sonic1FZBossInstance) parent;

        if (direction == 0) {
            // Retracting (ROM: loc_1A604)
            if (fzParent.isBossDefeated()) {
                // ROM: BossDefeated + addi.l #$10000 during post-defeat retraction
                fzParent.triggerBossDefeatedExplosion(vIntRunCount, currentX, currentY);
                extensionFixed += 0x10000;
            }

            // ROM: subi.l #$20000,objoff_3C — retract toward zero
            extensionFixed -= 0x20000;

            // ROM: bcc.s locret — check for underflow past zero
            // ROM uses SUBI.L followed by BCC. Reaching exactly zero from a
            // positive accumulator has no borrow and stays in routine 4; only
            // the following subtraction across zero completes the retraction.
            if (extensionFixed < 0) {
                extensionFixed = 0;
                active = false;
                drivesBossPosition = false;
                fzParent.onCylinderDone();
            }
        } else {
            // Extending (ROM: loc_1A646)
            // ROM: cmpi.w #$10,objoff_3C — boost when near limit
            if ((extensionFixed >> 16) >= 0x10) {
                extensionFixed += 0x28000; // ROM: addi.l #$28000
            }

            // ROM: addi.l #$8000,objoff_3C — base extension speed
            extensionFixed += 0x8000;

            // ROM: cmpi.w #$A0,objoff_3C — check fully extended
            if ((extensionFixed >> 16) >= 0xA0) {
                extensionFixed = 0xA0 << 16; // Clamp
                direction = 0; // ROM: clr.b objoff_29 — start retracting
            }
        }
    }

    /**
     * Calculate display frame from extension offset.
     * ROM: loc_1A524 - loc_1A55C
     *   Bottom: frame = (neg_offset - 8) >> 4, clamped 0-8
     *   Top: frame = (offset - $27) >> 4, clamped 0-8
     */
    private void calculateFrame() {
        int offset = extensionFixed >> 16;
        int frame = 0;

        if (offset < 0) {
            // Bottom cylinder frame calculation
            int negOffset = -offset;
            negOffset -= 8;
            if (negOffset > 0) {
                frame = 1 + (negOffset >> 4);
            }
        } else if (offset > 0) {
            // Top cylinder frame calculation
            offset -= 0x27;
            if (offset > 0) {
                frame = 1 + (offset >> 4);
            }
        }

        // Clamp frame to mapping range (0-10 for extension, 11 for control panel)
        if (frame > 10) frame = 10;
        currentFrame = frame;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public int getBalanceWidthPixels() {
        // EggmanCylinder_Main writes obActWid=64/2. The $B in SolidObject's
        // d1 is Sonic's collision padding and is not part of Sonic_Move's
        // stood-on-object balance calculation.
        return TOP_LANDING_HALF_WIDTH;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return TOP_LANDING_HALF_WIDTH;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer cylRenderer = renderManager.getRenderer(ObjectArtKeys.FZ_CYLINDER);
        if (cylRenderer == null || !cylRenderer.isReady()) return;

        // ROM: Top cylinders (subtype > 2) have bset #1,obRender — vertical flip
        // _incObj/84 line 49-51: cmpi.b #2,obSubtype / ble.s / bset #1,obRender
        cylRenderer.drawFrameIndex(currentFrame, currentX, currentY, false, !isBottom);

        // Also draw control panel (frame 11) at base position for bottom cylinders
        // ROM: This is part of the boss's sub-object rendering
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM: obPriority = 3
    }
}
