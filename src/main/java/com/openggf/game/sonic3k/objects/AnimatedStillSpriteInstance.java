package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x30 - AnimatedStillSprite.
 * <p>
 * Animated decorative sprite using level VRAM patterns. Each of the 8 subtypes
 * selects an animation script from Ani_AnimatedStillSprites. The Animate_Sprite
 * routine advances frames based on a per-script delay.
 * <p>
 * Subtype → animation mapping:
 * <ul>
 *   <li>0-1: AIZ (firefly/leaf animations, palette 3)</li>
 *   <li>2: LRZ act 1 (lava surface, base 0xD3, palette 2)</li>
 *   <li>3: LRZ act 2 (misc animation, base 0x40D, palette 1)</li>
 *   <li>4-7: SOZ (torch flames, base 0x40F, palette 2)</li>
 * </ul>
 * <p>
 * ROM reference: sonic3k.asm lines 60377-60427
 */
public class AnimatedStillSpriteInstance extends AbstractObjectInstance implements RewindRecreatable {

    /**
     * Animation scripts from Ani_AnimatedStillSprites.
     * Each script: first byte = delay, remaining bytes = global mapping frames,
     * terminated by 0xFF (loop back to start).
     */
    private static final int[][] ANIM_SCRIPTS = {
            {3, 0, 1, 2, 3, 4},           // anim 0: AIZ fireflies
            {3, 5, 6, 7, 8},              // anim 1: AIZ leaves
            {7, 9, 0x0A},                 // anim 2: LRZ1 lava
            {4, 0x0B, 0x0C, 0x0D},        // anim 3: LRZ2 misc
            {7, 0x0E, 0x0F, 0x10, 0x11},  // anim 4: SOZ torch small
            {7, 0x12, 0x13, 0x14, 0x15},  // anim 5: SOZ torch medium-small
            {7, 0x16, 0x17, 0x18, 0x19},  // anim 6: SOZ torch medium-large
            {7, 0x1A, 0x1B, 0x1C, 0x1D},  // anim 7: SOZ torch large
    };

    /**
     * Per-subtype configuration.
     *
     * @param artKey          art sheet key
     * @param globalFrameBase first global frame in the art sheet (subtracted to get local frame)
     * @param priorityBucket  display priority bucket (ROM priority / 0x80)
     * @param highPriority    art_tile priority bit
     */
    private record SubtypeInfo(String artKey, int globalFrameBase, int priorityBucket,
                               boolean highPriority) {
    }

    private static final SubtypeInfo[] SUBTYPE_TABLE = {
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIMATED_STILL_SPRITES, 0, 6, false),  // 0: AIZ
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIMATED_STILL_SPRITES, 0, 6, false),  // 1: AIZ
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_LRZ_D3, 9, 4, true),       // 2: LRZ1
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_LRZ2, 11, 6, false),       // 3: LRZ2
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_SOZ, 14, 6, false),        // 4: SOZ
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_SOZ, 14, 6, false),        // 5: SOZ
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_SOZ, 14, 6, false),        // 6: SOZ
            new SubtypeInfo(Sonic3kObjectArtKeys.ANIM_STILL_SOZ, 14, 6, false),        // 7: SOZ
    };

    private final SubtypeInfo info;
    private final int[] animFrames;
    private int animDelay;

    private int animScriptIndex;
    private int animTimer;
    private int currentGlobalFrame;
    private PlaceholderObjectInstance placeholder;

    public AnimatedStillSpriteInstance(ObjectSpawn spawn) {
        super(spawn, "AnimatedStillSprite");
        int sub = spawn.subtype() & 0xFF;
        if (sub >= 0 && sub < SUBTYPE_TABLE.length) {
            this.info = SUBTYPE_TABLE[sub];
        } else {
            this.info = null;
        }

        if (sub >= 0 && sub < ANIM_SCRIPTS.length) {
            int[] script = ANIM_SCRIPTS[sub];
            this.animDelay = script[0];
            this.animFrames = new int[script.length - 1];
            System.arraycopy(script, 1, animFrames, 0, animFrames.length);
        } else {
            this.animDelay = 0;
            this.animFrames = new int[0];
        }

        this.animScriptIndex = 0;
        this.animTimer = animDelay;
        this.currentGlobalFrame = animFrames.length > 0 ? animFrames[0] : 0;
    }

    @Override
    public AnimatedStillSpriteInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AnimatedStillSpriteInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        return info != null ? info.priorityBucket : 6;
    }

    @Override
    public boolean isHighPriority() {
        return info != null && info.highPriority;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (animFrames.length == 0) {
            return;
        }
        // ROM Animate_Sprite: decrement timer, advance on zero, loop on end
        animTimer--;
        if (animTimer < 0) {
            animTimer = animDelay;
            animScriptIndex++;
            if (animScriptIndex >= animFrames.length) {
                animScriptIndex = 0;
            }
            currentGlobalFrame = animFrames[animScriptIndex];
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (info != null) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                PatternSpriteRenderer renderer = renderManager.getRenderer(info.artKey);
                if (renderer != null && renderer.isReady()) {
                    int localFrame = currentGlobalFrame - info.globalFrameBase;
                    renderer.drawFrameIndex(localFrame, getX(), getY(), false, false);
                    return;
                }
            }
        }

        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }
}
