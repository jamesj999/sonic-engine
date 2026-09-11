package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Object 0x08 - Water splash (LZ).
 * Spawned when the player enters or exits water.
 * <p>
 * ROM reference: docs/s1disasm/_incObj/08 Water Splash.asm
 * <p>
 * Copies player X position on spawn, tracks water surface Y each frame.
 * Plays 3-frame animation (4 game ticks per frame) then deletes itself.
 * Uses Nem_Splash art at ArtTile_LZ_Splash ($259), palette line 2.
 */
public class Sonic1SplashObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Ani_Splash: dc.b 4, 0, 1, 2, afRoutine
    private static final int FRAME_COUNT = 3;
    private static final int FRAME_DELAY = 4; // duration byte value from animation script

    private int posX;
    private int posY;
    private int animTimer;
    private int frameIndex;
    /**
     * ROM {@code obRoutine}: {@code 2} while {@code Spla_Display} runs,
     * {@code 4} once {@code afRoutine} has ended the script. The object still
     * occupies its SST for that one extra frame, because {@code Spla_Delete}
     * calls {@code DeleteObject} on the NEXT pass rather than on the frame the
     * animation finished (docs/s1disasm/_incObj/08 LZ Water Splash.asm:29-40).
     */
    private int routine = ROUTINE_DISPLAY;

    private static final int ROUTINE_DISPLAY = 2;
    private static final int ROUTINE_DELETE = 4;

    /**
     * Creates a splash at the player's X position and the water surface Y.
     *
     * @param playerX player centre X at time of water entry/exit
     * @param waterY  water surface Y position
     */
    public Sonic1SplashObjectInstance(int playerX, int waterY) {
        super(new ObjectSpawn(playerX, waterY, 0x08, 0, 0, false, 0), "Splash");
        this.posX = playerX;
        this.posY = waterY;
        this.animTimer = FRAME_DELAY;
        this.frameIndex = 0;
    }

    @Override
    public Sonic1SplashObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1SplashObjectInstance(ctx.spawn().x(), ctx.spawn().y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM (Spla_Display): move.w (v_waterpos1).w,obY(a0)
        // Track water surface Y each frame
        if (services().currentLevel() != null) {
            var waterSystem = services().waterSystem();
            posY = waterSystem.getVisualWaterLevelY(
                    services().featureZoneId(), services().featureActId());
        }

        // Spla_Delete (routine 4): jmp (DeleteObject).l. Reached on the pass
        // AFTER the one where afRoutine advanced obRoutine, so the object is
        // still an SST occupant for that frame -- which is why the recording
        // shows sixteen rows per splash, fifteen at routine $02 and one at $04
        // (lz1_completerun slot 12: 11934-11948 then 11949).
        if (routine == ROUTINE_DELETE) {
            setDestroyed(true);
            return;
        }

        // AnimateSprite with Ani_Splash: duration 4, frames 0/1/2, afRoutine.
        // afRoutine advances obRoutine; it does not delete here.
        animTimer--;
        if (animTimer < 0) {
            animTimer = FRAME_DELAY;
            frameIndex++;
            if (frameIndex >= FRAME_COUNT) {
                routine = ROUTINE_DELETE;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || frameIndex >= FRAME_COUNT) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_SPLASH);
        if (renderer == null) return;

        renderer.drawFrameIndex(frameIndex, posX, posY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 1; // obPriority = 1 from ROM
    }
}
