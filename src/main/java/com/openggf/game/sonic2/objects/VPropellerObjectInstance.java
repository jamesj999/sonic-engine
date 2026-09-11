package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xB4 - Vertical propeller from WFZ and SCZ.
 * <p>
 * A vertical column of animated spinning blades. When placed normally (y_flip=false),
 * touching the propeller hurts the player (collision_flags = $A8 = HURT category,
 * size index 0x28). When placed upside-down (y_flip=true), the propeller is purely
 * decorative with no collision.
 * <p>
 * The propeller animates between 3 frames at a rate of 2 game frames per animation
 * frame, and plays a helicopter sound effect every 32 frames ($1F mask).
 * <p>
 * Subtype: 0x64 (both SCZ and WFZ) - maps to ObjB4_SubObjData via LoadSubObject.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79157-79206 (ObjB4)
 * <ul>
 *   <li>SubObjData: mappings=ObjB4_MapUnc_3B3BE, art_tile=ArtTile_ArtNem_WfzVrtclPrpllr (palette 1, priority),
 *       render_flags=level_fg, priority=4, width_pixels=4, collision_flags=$A8</li>
 *   <li>Animation: Ani_objB4 = {1, 0, 1, 2, $FF, 0} (frames 0-1-2, duration 1, loop)</li>
 *   <li>Sound: SndID_Helicopter ($DE) every 32 frames via PlaySoundLocal</li>
 * </ul>
 */
public class VPropellerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // From disassembly: collision_flags = $A8
    // Upper 2 bits 0x80 = HURT category, lower 6 bits = 0x28 = size index 40
    private static final int COLLISION_FLAGS_NORMAL = 0xA8;
    private static final int COLLISION_FLAGS_NONE = 0x00;
    private static final int WIDTH_PIXELS = 4;

    // Animation: Ani_objB4 = dc.b 1, 0, 1, 2, $FF, 0
    // Duration 1 means each frame displays for 2 game frames (duration+1)
    private static final int ANIM_DURATION = 1;
    private static final int[] ANIM_FRAMES = { 0, 1, 2 };

    // Sound plays every 32 frames: andi.b #$1F,d0 / bne.s +
    private static final int SOUND_INTERVAL_MASK = 0x1F;
    private static final int VINT_RUNCOUNT_OFFSET = 3;

    private int currentX;
    private int currentY;
    private int collisionFlags;
    private boolean yFlipped;

    // Animation state
    private int animFrameIndex;
    private int animTimer;

    public VPropellerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "VPropeller");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // ROM: ObjB4_Init
        // bclr #render_flags.y_flip,render_flags(a0)
        // beq.s +
        // clr.b collision_flags(a0)
        // If y_flip was set in render_flags, clear collision (decorative only).
        // bclr tests the bit BEFORE clearing it, so beq branches if the bit was 0 (not set).
        this.yFlipped = (spawn.renderFlags() & 0x02) != 0;
        this.collisionFlags = yFlipped ? COLLISION_FLAGS_NONE : COLLISION_FLAGS_NORMAL;

        this.animFrameIndex = 0;
        this.animTimer = ANIM_DURATION;
    }

    @Override
    public VPropellerObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new VPropellerObjectInstance(ctx.spawn());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority=4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzVrtclPrpllr,1,1)
        return true;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: ObjB4_Main
        // 1. Animate sprite (Ani_objB4: duration=1, frames 0,1,2, loop)
        updateAnimation();

        // 2. Play helicopter sound every 32 frames
        // ROM: move.b (Vint_runcount+3).w,d0 / andi.b #$1F,d0 / bne.s +
        //      moveq #signextendB(SndID_Helicopter),d0 / jsrto JmpTo_PlaySoundLocal
        // PlaySoundLocal (s2.asm:1555) only queues the SFX when render_flags.on_screen
        // is set; without this gate the propeller keeps sounding across its whole
        // off-screen placement window. isOnScreen() is that on-screen bit.
        if (((vIntRunCount + VINT_RUNCOUNT_OFFSET) & SOUND_INTERVAL_MASK) == 0 && isOnScreen()) {
            services().playSfx(Sonic2AudioConstants.SFX_HELICOPTER);
        }

        // 3. MarkObjGone - standard despawn handled by engine's object placement system
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_VPROPELLER);
        if (renderer == null) return;

        int frame = ANIM_FRAMES[animFrameIndex];
        // ROM: bclr #render_flags.y_flip clears the flip, so the sprite is never actually
        // rendered y-flipped even if placed upside-down. The y_flip bit only determines
        // whether collision is active.
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(currentX, currentY, 4, 0.6f, 0.8f, 1.0f);
        String collisionStr = collisionFlags != 0 ? String.format("col=%02X", collisionFlags) : "no-col";
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("B4 f%d %s%s", ANIM_FRAMES[animFrameIndex],
                        yFlipped ? "yflip " : "", collisionStr),
                DebugColor.CYAN);
    }

    /**
     * Advance animation state.
     * ROM animation script: dc.b 1, 0, 1, 2, $FF, 0
     * Duration=1 means display each frame for (1+1)=2 game frames, then advance.
     * $FF = restart command, 0 = restart from frame index 0.
     */
    private void updateAnimation() {
        if (animTimer > 0) {
            animTimer--;
        } else {
            animTimer = ANIM_DURATION;
            animFrameIndex++;
            if (animFrameIndex >= ANIM_FRAMES.length) {
                // $FF, 0 = loop back to start
                animFrameIndex = 0;
            }
        }
    }
}
