package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Object 0x6E - Electrocuter (SBZ).
 * <p>
 * Electrocution orbs that periodically zap with an electrical discharge.
 * The subtype controls the zap frequency: the orb zaps when
 * {@code (v_framecount & frequency) == 0}, where frequency is
 * {@code (subtype * 0x10) - 1}.
 * <p>
 * During the zap animation, frame 4 (the peak discharge) hurts Sonic
 * with collision type $A4 (HURT category, size index $24).
 * <p>
 * ROM reference: docs/s1disasm/_incObj/6E Electrocuter.asm
 */
public class Sonic1ElectrocuterObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // obColType value when the zap frame (frame 4) is displayed.
    // $A4 = HURT($80) | size index $24
    private static final int COLLISION_TYPE_HURT = 0xA4;

    // obActWid from ROM: $28 (40 pixels half-width for display culling)
    private static final int ACT_WIDTH = 0x28;

    /** Debug color (electric blue for zap hazard). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(80, 180, 255);

    // Ani_Elec animation 0 (idle): dc.b 7, 0, afEnd
    // Frame delay 7, shows frame 0, loops.
    private static final int IDLE_FRAME_DELAY = 7;
    private static final int IDLE_FRAME = 0;

    // Ani_Elec animation 1 (zap): dc.b 0, 1,1,1, 2, 3,3, 4,4,4, 5,5,5, 0, afChange,0
    // Frame delay 0, sequence of mapping frames, then switches back to anim 0.
    private static final int ZAP_FRAME_DELAY = 0;
    private static final int[] ZAP_SEQUENCE = {1, 1, 1, 2, 3, 3, 4, 4, 4, 5, 5, 5, 0};

    // The frequency mask derived from subtype: (subtype * $10) - 1
    private int frequencyMask;

    // Animation state
    private int animationId;        // 0 = idle, 1 = zap
    private int animFrameIndex;     // current index into the animation sequence
    private int animTimer;          // countdown timer for current frame
    private int mappingFrame;       // current mapping frame index (0-5)

    public Sonic1ElectrocuterObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Electrocuter");
        // From Elec_Main:
        //   moveq #0,d0
        //   move.b obSubtype(a0),d0
        //   lsl.w #4,d0       ; multiply by $10
        //   subq.w #1,d0
        //   move.w d0,elec_freq(a0)
        int subtype = spawn.subtype() & 0xFF;
        this.frequencyMask = (subtype << 4) - 1;

        // Start in idle animation
        this.animationId = 0;
        this.animFrameIndex = 0;
        this.animTimer = 0;
        this.mappingFrame = IDLE_FRAME;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Elec_Shock:
        //   move.w (v_framecount).w,d0
        //   and.w  elec_freq(a0),d0
        //   bne.s  .animate
        //   move.b #1,obAnim(a0)    ; start zap animation
        //   tst.b  obRender(a0)
        //   bpl.s  .animate
        //   play electricity sound
        // ObjectManager passes the VBla clock into update(...), but this S1 routine
        // reads the gameplay frame counter instead (docs/s1disasm/_incObj/6E Electrocuter.asm:29-34).
        int vFrameCounter = resolveVFrameCounter(vIntRunCount);
        if ((vFrameCounter & frequencyMask) == 0) {
            if (animationId != 1) {
                animationId = 1;
                animFrameIndex = 0;
                animTimer = 0;
            }
            if (isOnScreen()) {
                services().playSfx(Sonic1Sfx.ELECTRIC.id);
            }
        }

        animate();
    }

    private int resolveVFrameCounter(int vIntRunCount) {
        // ROM Elec_Shock reads v_framecount (Level_frame_counter). The engine's
        // canonical Level_frame_counter is LevelManager.frameCounter (the value the
        // trace records as gameplay_frame_counter and seeds on replay). The
        // ObjectManager's own free-running counter is NOT trace-seeded, so reading
        // it drifts the zap phase relative to ROM (SBZ1 f1925: a frame-4 zap that
        // hurts Sonic one trace-frame early). LevelManager.frameCounter has not yet
        // been incremented for the current frame at object-execution time (objects
        // run with frameCounter+1, see LevelManager.updateObjectPositions*), so +1
        // gives the current frame's v_framecount.
        LevelManager levelManager = services().levelManager();
        if (levelManager != null) {
            return levelManager.getFrameCounter();
        }
        return vIntRunCount;
    }

    private void animate() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        if (animationId == 0) {
            // Idle animation: frame delay 7, show frame 0, loop
            animTimer = IDLE_FRAME_DELAY;
            mappingFrame = IDLE_FRAME;
        } else {
            // Zap animation: frame delay 0, sequence through ZAP_SEQUENCE
            animTimer = ZAP_FRAME_DELAY;
            if (animFrameIndex >= ZAP_SEQUENCE.length) {
                // afChange,0 -> switch back to idle animation
                animationId = 0;
                animFrameIndex = 0;
                animTimer = IDLE_FRAME_DELAY;
                mappingFrame = IDLE_FRAME;
            } else {
                mappingFrame = ZAP_SEQUENCE[animFrameIndex];
                animFrameIndex++;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_ELECTROCUTER);
        if (renderer == null) return;

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip);
    }

    // ---- TouchResponseProvider ----

    /**
     * Returns collision flags based on the current mapping frame.
     * Only frame 4 (peak discharge) has collision enabled.
     * <p>
     * From the ROM:
     * <pre>
     *   move.b #0,obColType(a0)
     *   cmpi.b #4,obFrame(a0)
     *   bne.s  .display
     *   move.b #$A4,obColType(a0)
     * </pre>
     */
    @Override
    public int getCollisionFlags() {
        return mappingFrame == 4 ? COLLISION_TYPE_HURT : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // RememberState: persist while on screen
        return !isDestroyed() && isOnScreen();
    }

    // ---- Debug Rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int objX = getX();
        int objY = getY();
        // Draw collision area sized to obActWid
        boolean active = mappingFrame == 4;
        float r = active ? 1.0f : 0.3f;
        float g = active ? 0.2f : 0.7f;
        float b = active ? 0.2f : 1.0f;
        ctx.drawRect(objX, objY, ACT_WIDTH, 0x10, r, g, b);
        ctx.drawWorldLabel(objX, objY, -1,
                String.format("Elec frm=%d anim=%d mask=$%X",
                        mappingFrame, animationId, frequencyMask),
                DEBUG_COLOR);
    }
}
